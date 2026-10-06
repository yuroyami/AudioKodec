package io.github.yuroyami.audiokodec.internal.mp4

import io.github.yuroyami.audiokodec.AudioChannel
import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.internal.PacketCodec
import io.github.yuroyami.audiokodec.internal.alac.AlacConfig
import io.github.yuroyami.audiokodec.internal.alac.AlacPacketCodec
import io.github.yuroyami.audiokodec.internal.codec.G711Codec
import io.github.yuroyami.audiokodec.internal.codec.LinearPcmCodec
import io.github.yuroyami.audiokodec.internal.codec.PcmLayout
import io.github.yuroyami.audiokodec.internal.codec.SampleCodec
import io.github.yuroyami.audiokodec.internal.container.CafReader
import io.github.yuroyami.audiokodec.internal.flac.FlacFrameReader
import io.github.yuroyami.audiokodec.internal.flac.FlacMetadata
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.opus.OpusMultistreamDecoder
import io.github.yuroyami.audiokodec.internal.opus.OpusPacket
import io.github.yuroyami.audiokodec.internal.vorbis.VorbisDecoder

/**
 * Works out how an MP4 audio track is coded, and builds its decoder when
 * AudioKodec has one.
 *
 * The sample entry's four-character code names the coding, and a child box
 * of the entry configures it: `esds` for the MPEG codings, `dOps` for Opus,
 * `dfLa` for FLAC. Codings without a decoder are still named, so that
 * [io.github.yuroyami.audiokodec.AudioKodec.probe] can report them.
 */
internal object Mp4Codecs {

    class Choice(
        val codec: AudioCodec,
        /** The coding's own name, for a refusal. */
        val name: String,
        val format: AudioFormat,
        val bitDepth: Int?,
        /** Coded frames before the recording starts when the file has no edit list to say. */
        val defaultTrimStart: Long,
        /** Builds the decoder, or is null when AudioKodec cannot decode the coding. */
        val decoder: ((largestPacket: Int) -> PacketCodec)?,
    )

    fun choose(entry: Mp4Reader.SampleEntry, reader: SourceReader, options: DecodeOptions): Choice {
        val channels = entry.channels
        val rate = entry.sampleRate
        fun named(codec: AudioCodec, name: String, channelCount: Int = channels, sampleRate: Int = rate) = Choice(
            codec, name,
            AudioFormat(sampleRate.coerceAtLeast(1), ChannelLayout.canonical(channelCount.coerceAtLeast(1))),
            null, 0, null,
        )
        return when (entry.format) {
            "mp4a" -> mpeg4Audio(entry)
            ".mp3" -> named(AudioCodec.MP3, "MP3")
            "ac-3", "ec-3" -> named(AudioCodec.AC3, if (entry.format == "ac-3") "AC-3" else "Enhanced AC-3")
            "samr" -> named(AudioCodec.AMR_NB, "AMR-NB", 1, 8_000)
            "sawb" -> named(AudioCodec.AMR_WB, "AMR-WB", 1, 16_000)
            "alac" -> alac(entry)
            "Opus" -> opus(entry)
            "fLaC" -> flac(entry, reader, options)
            else -> {
                val pcm = pcmCodec(entry) ?: return named(AudioCodec.UNKNOWN, "'${entry.format}'")
                pcm
            }
        }
    }

    // MPEG-4 audio, which is AAC almost always and MP3 sometimes.

    private fun mpeg4Audio(entry: Mp4Reader.SampleEntry): Choice {
        val esds = entry.boxes["esds"]
        val descriptor = esds?.let { decoderConfig(it) }
        val objectType = descriptor?.first ?: 0x40
        if (objectType == 0x69 || objectType == 0x6B) {
            return Choice(AudioCodec.MP3, "MP3", AudioFormat(entry.sampleRate.coerceAtLeast(1), ChannelLayout.canonical(entry.channels.coerceAtLeast(1))), null, 0, null)
        }
        // The AudioSpecificConfig states the real channel count and rate,
        // which the sample entry may not: an entry is limited to 65535 Hz and
        // HE-AAC doubles the rate it states.
        var channels = entry.channels
        var rate = entry.sampleRate
        descriptor?.second?.let { config ->
            parseAudioSpecificConfig(config)?.let { (configRate, configChannels) ->
                if (configRate > 0) rate = configRate
                if (configChannels > 0) channels = configChannels
            }
        }
        val name = if (objectType == 0x40 || objectType in 0x66..0x68) "AAC" else "MPEG-4 object type 0x${objectType.toString(16)}"
        return Choice(
            if (name == "AAC") AudioCodec.AAC else AudioCodec.UNKNOWN,
            name,
            AudioFormat(rate.coerceAtLeast(1), ChannelLayout.canonical(channels.coerceAtLeast(1))),
            null, 0, null,
        )
    }

    /** The object type and decoder specific information an `esds` box carries, or null if it cannot be read. */
    private fun decoderConfig(esds: ByteArray): Pair<Int, ByteArray?>? {
        var at = 4
        fun tag(expected: Int): Int? {
            if (at >= esds.size || esds[at].toInt() and 0xFF != expected) return null
            at++
            var length = 0
            repeat(4) {
                if (at >= esds.size) return null
                val b = esds[at++].toInt() and 0xFF
                length = (length shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) return length
            }
            return length
        }
        tag(0x03) ?: return null
        if (at + 3 > esds.size) return null
        val flags = esds[at + 2].toInt() and 0xFF
        at += 3
        if (flags and 0x80 != 0) at += 2
        if (flags and 0x40 != 0) at += 1 + (esds.getOrNull(at)?.toInt()?.and(0xFF) ?: return null)
        if (flags and 0x20 != 0) at += 2
        tag(0x04) ?: return null
        if (at + 13 > esds.size) return null
        val objectType = esds[at].toInt() and 0xFF
        at += 13
        val specific = tag(0x05)?.let { length ->
            if (at + length > esds.size) null else esds.copyOfRange(at, at + length)
        }
        return objectType to specific
    }

    /** The output rate and channel count an AAC AudioSpecificConfig states, as far as it states them. */
    private fun parseAudioSpecificConfig(config: ByteArray): Pair<Int, Int>? {
        if (config.size < 2) return null
        var bit = 0
        fun read(count: Int): Int {
            var value = 0
            repeat(count) {
                val byte = bit / 8
                if (byte >= config.size) return -1
                value = (value shl 1) or ((config[byte].toInt() ushr (7 - bit % 8)) and 1)
                bit++
            }
            return value
        }
        fun objectType(): Int = read(5).let { if (it == 31) 32 + read(6) else it }
        fun frequency(): Int = read(4).let { if (it == 15) read(24) else AAC_RATES.getOrElse(it) { 0 } }
        var type = objectType()
        var rate = frequency()
        val channels = read(4)
        if (type == 5 || type == 29) {
            // Explicitly signalled SBR doubles the rate, and says so.
            rate = frequency()
            type = objectType()
        }
        return rate to AAC_CHANNELS.getOrElse(channels) { 0 }
    }

    private val AAC_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    private val AAC_CHANNELS = intArrayOf(0, 1, 2, 3, 4, 5, 6, 8)

    // Apple Lossless.

    private fun alac(entry: Mp4Reader.SampleEntry): Choice {
        val box = entry.boxes["alac"] ?: throw CorruptStreamException("an ALAC track has no configuration box", entry.offset)
        // The box is a full box, so its body opens with a version and flags
        // before the configuration itself.
        if (box.size < 4) throw CorruptStreamException("the ALAC configuration box is too short", entry.offset)
        val config = AlacConfig.parse(box.copyOfRange(4, box.size), entry.offset)
        if (entry.channels != 0 && entry.channels != config.channels) {
            throw CorruptStreamException("the track says ${entry.channels} channels and its ALAC configuration ${config.channels}", entry.offset)
        }
        // A sample entry cannot state a rate past 65535 Hz; the configuration can.
        val rate = if (config.sampleRate in 1..Int.MAX_VALUE.toLong()) config.sampleRate.toInt() else entry.sampleRate
        if (rate < 1) throw CorruptStreamException("an ALAC track states no sample rate", entry.offset)
        val codec = AlacPacketCodec(config, rate)
        return Choice(AudioCodec.ALAC, "ALAC", codec.format, config.bitDepth, 0) { _ -> AlacPacketCodec(config, rate) }
    }

    // Opus.

    private fun opus(entry: Mp4Reader.SampleEntry): Choice {
        val dops = entry.boxes["dOps"] ?: throw CorruptStreamException("an Opus track has no 'dOps' box", entry.offset)
        val cursor = Mp4Cursor(dops, 0, dops.size, entry.offset, "the 'dOps' box")
        if (cursor.u8() != 0) throw CorruptStreamException("the 'dOps' box has an unknown version", entry.offset)
        val channels = cursor.u8()
        val preSkip = cursor.u16()
        cursor.skip(4)
        val gain = cursor.i16()
        val family = cursor.u8()
        if (channels == 0) throw CorruptStreamException("an Opus track declares no channels", entry.offset)
        val streams: Int
        val coupled: Int
        val mapping: IntArray
        if (family == 0) {
            if (channels > 2) throw CorruptStreamException("Opus channel mapping family 0 carries one or two channels, not $channels", entry.offset)
            streams = 1
            coupled = channels - 1
            mapping = IntArray(channels) { it }
        } else {
            streams = cursor.u8()
            coupled = cursor.u8()
            mapping = IntArray(channels) { cursor.u8() }
        }
        if (streams == 0 || coupled > streams || mapping.any { it != OpusMultistreamDecoder.SILENT && it >= streams + coupled }) {
            throw CorruptStreamException("the 'dOps' box describes an impossible channel layout", entry.offset)
        }
        if (family == 3) {
            throw io.github.yuroyami.audiokodec.UnsupportedFeatureException(
                "Opus projection in MP4",
                "this MP4 file codes ambisonics by projection, whose matrix MP4 carries in a box AudioKodec does not read",
            )
        }
        val order = if (family == 1 && channels <= 8) VorbisDecoder.orderFor(channels) else IntArray(channels) { it }
        val layout = when (family) {
            0 -> if (channels == 1) ChannelLayout.MONO else ChannelLayout.STEREO
            1 -> VorbisDecoder.layoutFor(channels)
            else -> ChannelLayout.unknown(channels)
        }
        val format = AudioFormat(48_000, layout)
        return Choice(AudioCodec.OPUS, "Opus", format, null, preSkip.toLong()) { _ ->
            OpusPackets(format, OpusMultistreamDecoder.withMapping(48_000, channels, streams, coupled, mapping).also { it.gain = gain }, order)
        }
    }

    private class OpusPackets(
        override val format: AudioFormat,
        private val decoder: OpusMultistreamDecoder,
        private val order: IntArray,
    ) : PacketCodec {
        private val channels = format.channels
        private val scratch = FloatArray(OpusPacket.MAX_SAMPLES * channels)
        override val storedBitDepth: Int? = null
        override val maxFramesPerPacket: Int = OpusPacket.MAX_SAMPLES

        /** RFC 7845 recommends 80 ms of decoding before a seek target. */
        override val preRollFrames: Long = 3_840

        override fun decode(packet: ByteArray, length: Int, out: FloatArray): Int {
            val frames = decoder.decode(packet, 0, length, scratch, OpusPacket.MAX_SAMPLES)
            for (frame in 0 until frames) {
                val base = frame * channels
                for (slot in 0 until channels) out[base + slot] = scratch[base + order[slot]]
            }
            return frames
        }

        override fun reset() = decoder.reset()
    }

    // FLAC.

    private fun flac(entry: Mp4Reader.SampleEntry, reader: SourceReader, options: DecodeOptions): Choice {
        val dfla = entry.boxes["dfLa"] ?: throw CorruptStreamException("a FLAC track has no 'dfLa' box", entry.offset)
        if (dfla.size < 8 || dfla[4].toInt() and 0x7F != 0) {
            throw CorruptStreamException("the 'dfLa' box does not start with stream information", entry.offset)
        }
        val info = FlacMetadata.parseStreamInfoBody(dfla, 8, options, entry.offset)
        val format = AudioFormat(info.sampleRate, ChannelLayout.canonical(info.channels))
        return Choice(AudioCodec.FLAC, "FLAC", format, info.bitsPerSample, 0) { _ ->
            FlacPackets(format, FlacFrameReader(reader, info, options), info.bitsPerSample, info.maximumBlockSize)
        }
    }

    private class FlacPackets(
        override val format: AudioFormat,
        private val frames: FlacFrameReader,
        private val bits: Int,
        override val maxFramesPerPacket: Int,
    ) : PacketCodec {
        private val channels = format.channels
        override val storedBitDepth: Int = bits
        override val preRollFrames: Long = 0
        private val scale = 1f / (1L shl (bits - 1)).toFloat()

        private fun frame(packet: ByteArray, length: Int): FlacFrameReader.Frame {
            val frame = frames.decodePacket(packet, length, 0) ?: throw CorruptStreamException("a FLAC packet is too short to hold a frame")
            if (frame.channels.size != channels || frame.bitsPerSample != bits || frame.blockSize > maxFramesPerPacket) {
                throw CorruptStreamException("a FLAC frame disagrees with the track's stream information")
            }
            return frame
        }

        override fun decode(packet: ByteArray, length: Int, out: FloatArray): Int {
            val frame = frame(packet, length)
            for (c in 0 until channels) {
                val samples = frame.channels[c]
                for (i in 0 until frame.blockSize) out[i * channels + c] = samples[i] * scale
            }
            return frame.blockSize
        }

        override fun decodeInt(packet: ByteArray, length: Int, out: IntArray): Int {
            val frame = frame(packet, length)
            for (c in 0 until channels) {
                val samples = frame.channels[c]
                for (i in 0 until frame.blockSize) out[i * channels + c] = samples[i]
            }
            return frame.blockSize
        }

        override fun reset() = Unit
    }

    // Uncompressed QuickTime audio.

    /** Bytes in one frame of uncompressed audio, or null when the entry is not uncompressed. */
    fun pcmFrameBytes(entry: Mp4Reader.SampleEntry): Int? = pcmLayout(entry)?.let { (layout, _) ->
        when (layout) {
            null -> entry.channels
            else -> layout.storageBytes * entry.channels
        }
    }

    /** The sample layout of an uncompressed entry, null for G.711, paired with whether it is A-law. */
    private fun pcmLayout(entry: Mp4Reader.SampleEntry): Pair<PcmLayout?, Boolean>? {
        val littleEndianOverride = entry.boxes["enda"]?.let { it.size >= 2 && Mp4Box.readU16(it, 0) != 0 }
        fun bigEndianUnlessEnda(bits: Int, float: Boolean) =
            PcmLayout(bits, littleEndian = littleEndianOverride ?: false, signed = true, isFloat = float)
        return when (entry.format) {
            "sowt" -> PcmLayout(entry.sampleSize.coerceIn(8, 16), littleEndian = true, signed = true) to false
            "twos" -> PcmLayout(entry.sampleSize.coerceIn(8, 16), littleEndian = false, signed = true) to false
            "raw " -> PcmLayout(8, littleEndian = false, signed = false) to false
            "in24" -> bigEndianUnlessEnda(24, false) to false
            "in32" -> bigEndianUnlessEnda(32, false) to false
            "fl32" -> bigEndianUnlessEnda(32, true) to false
            "fl64" -> bigEndianUnlessEnda(64, true) to false
            "ulaw" -> null to false
            "alaw" -> null to true
            "lpcm" -> {
                val flags = entry.formatFlags
                val float = flags and 0x1 != 0
                val bits = entry.bitsPerChannel
                val storage = if (entry.channels > 0 && entry.bytesPerFrame > 0) entry.bytesPerFrame / entry.channels else (bits + 7) / 8
                try {
                    PcmLayout(
                        bits, littleEndian = flags and 0x2 == 0, signed = float || flags and 0x4 != 0, isFloat = float,
                        storageBytes = storage, highAligned = !float && storage * 8 != bits && flags and 0x10 != 0,
                    ) to false
                } catch (failure: IllegalArgumentException) {
                    throw CorruptStreamException("an 'lpcm' entry describes $bits-bit samples in $storage bytes", entry.offset)
                }
            }
            "ipcm", "fpcm" -> {
                val pcmc = entry.boxes["pcmC"] ?: throw CorruptStreamException("an '${entry.format}' entry has no 'pcmC' box", entry.offset)
                if (pcmc.size < 6) throw CorruptStreamException("the 'pcmC' box is too short", entry.offset)
                val littleEndian = pcmc[4].toInt() and 1 != 0
                val bits = pcmc[5].toInt() and 0xFF
                try {
                    PcmLayout(bits, littleEndian, signed = true, isFloat = entry.format == "fpcm") to false
                } catch (failure: IllegalArgumentException) {
                    throw CorruptStreamException("an '${entry.format}' entry describes $bits-bit samples", entry.offset)
                }
            }
            else -> null
        }
    }

    private fun pcmCodec(entry: Mp4Reader.SampleEntry): Choice? {
        val (layout, aLaw) = pcmLayout(entry) ?: return null
        val channels = entry.channels
        if (channels < 1) throw CorruptStreamException("an uncompressed track declares no channels", entry.offset)
        if (entry.sampleRate < 1) throw CorruptStreamException("an uncompressed track declares no sample rate", entry.offset)
        val inner: SampleCodec = if (layout == null) G711Codec(channels, aLaw) else LinearPcmCodec(channels, layout)
        val stored = entry.boxes["chan"]?.let { channelsOf(it, channels, entry.offset) }
        val (layoutOut, order) = when {
            stored == null -> (if (channels <= 2) ChannelLayout.canonical(channels) else ChannelLayout.unknown(channels)) to null
            stored.any { it == AudioChannel.UNKNOWN } -> ChannelLayout.of(stored) to null
            else -> {
                val canonical = stored.sortedBy { it.ordinal }
                ChannelLayout.of(canonical) to IntArray(channels) { stored.indexOf(canonical[it]) }
            }
        }
        val codec = when {
            layout == null -> if (aLaw) AudioCodec.A_LAW else AudioCodec.MU_LAW
            layout.isFloat -> AudioCodec.PCM_FLOAT
            else -> AudioCodec.PCM_INTEGER
        }
        val format = AudioFormat(entry.sampleRate, layoutOut)
        return Choice(codec, codec.displayName, format, inner.storedBitDepth, 0) { largest ->
            PcmPackets(format, inner, order, largest / inner.blockBytes)
        }
    }

    /** The speakers a `chan` box names, in stored order, when it names them by a layout tag or a bitmap. */
    private fun channelsOf(chan: ByteArray, channels: Int, offset: Long): List<AudioChannel>? {
        if (chan.size < 16) throw CorruptStreamException("the 'chan' box is too short", offset)
        val tag = Mp4Box.readU32(chan, 4)
        val bitmap = Mp4Box.readU32(chan, 8).toInt()
        val named = when ((tag ushr 16).toInt()) {
            0 -> return null
            1 -> ChannelLayout.fromWaveMask(bitmap, channels).channels
            else -> CafReader.storedOrderForTag(tag) ?: return null
        }
        if (named.size != channels) throw CorruptStreamException("the 'chan' box names ${named.size} channels for $channels-channel audio", offset)
        if (named.filter { it != AudioChannel.UNKNOWN }.toSet().size != named.count { it != AudioChannel.UNKNOWN }) {
            throw CorruptStreamException("the 'chan' box names a speaker twice", offset)
        }
        return named
    }

    private class PcmPackets(
        override val format: AudioFormat,
        private val inner: SampleCodec,
        /** Which stored channel each output channel comes from, or null when they agree. */
        private val order: IntArray?,
        override val maxFramesPerPacket: Int,
    ) : PacketCodec {
        private val channels = format.channels
        override val storedBitDepth: Int? = inner.storedBitDepth
        override val preRollFrames: Long = 0
        private val floatScratch = if (order != null) FloatArray(maxFramesPerPacket * channels) else null
        private val intScratch = if (order != null && storedBitDepth != null) IntArray(maxFramesPerPacket * channels) else null

        private fun frames(length: Int): Int {
            if (length % inner.blockBytes != 0) {
                throw CorruptStreamException("a packet of $length bytes does not hold whole ${inner.blockBytes}-byte frames")
            }
            return length / inner.blockBytes
        }

        override fun decode(packet: ByteArray, length: Int, out: FloatArray): Int {
            val frames = frames(length)
            val target = floatScratch ?: out
            inner.decode(packet, 0, frames, target, 0)
            if (order != null) {
                for (f in 0 until frames) for (c in 0 until channels) out[f * channels + c] = target[f * channels + order[c]]
            }
            return frames
        }

        override fun decodeInt(packet: ByteArray, length: Int, out: IntArray): Int {
            val frames = frames(length)
            val target = intScratch ?: out
            inner.decodeInt(packet, 0, frames, target, 0)
            if (order != null) {
                for (f in 0 until frames) for (c in 0 until channels) out[f * channels + c] = target[f * channels + order[c]]
            }
            return frames
        }

        override fun reset() = Unit
    }
}

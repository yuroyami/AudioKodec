package io.github.yuroyami.audiokodec.internal.container

import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.PacketCodec
import io.github.yuroyami.audiokodec.internal.aac.AacPacketCodec
import io.github.yuroyami.audiokodec.internal.aac.AacStreamConfig
import io.github.yuroyami.audiokodec.internal.ac3.Ac3Frame
import io.github.yuroyami.audiokodec.internal.ac3.Ac3PacketCodec
import io.github.yuroyami.audiokodec.internal.codec.LinearPcmCodec
import io.github.yuroyami.audiokodec.internal.codec.PcmLayout
import io.github.yuroyami.audiokodec.internal.flac.FlacFrameReader
import io.github.yuroyami.audiokodec.internal.flac.FlacMetadata
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.mp4.Mp4Codecs
import io.github.yuroyami.audiokodec.internal.mpeg.MpegPacketCodec
import io.github.yuroyami.audiokodec.internal.opus.OpusMultistreamDecoder
import io.github.yuroyami.audiokodec.internal.vorbis.VorbisConfig
import io.github.yuroyami.audiokodec.internal.vorbis.VorbisDecoder
import io.github.yuroyami.audiokodec.internal.vorbis.VorbisSynthesis

/**
 * What each Matroska codec ID means for decoding, after the Matroska codec
 * mappings: which of AudioKodec's decoders takes the frames, what the
 * CodecPrivate element configures it with, and how many samples a frame
 * holds, which Matroska's millisecond timestamps are too coarse to say.
 */
internal object MatroskaCodecs {

    class Choice(
        val codec: AudioCodec,
        /** The coding's own name, for a refusal. */
        val name: String,
        val format: AudioFormat,
        val bitDepth: Int?,
        /** Counts the samples of each frame in turn, or null when the coding is not decoded. */
        val counter: (() -> FrameCounter)?,
        /** Builds the decoder, or is null when AudioKodec cannot decode the coding. */
        val decoder: ((largestPacket: Int) -> PacketCodec)?,
        /** Leading bytes of a frame [counter] needs, or 0 when it needs none. */
        val headerBytes: Int,
    )

    /** Counts the samples of one frame after another, given each frame's first bytes. */
    fun interface FrameCounter {
        fun samples(head: ByteArray, length: Int, frameSize: Int): Int
    }

    fun choose(track: MatroskaReader.Track, reader: SourceReader, options: DecodeOptions, first: ByteArray?): Choice {
        val id = track.codecId
        val rate = track.sampleRate.toInt()
        val channels = track.channels
        fun named(codec: AudioCodec, name: String): Choice {
            options.checkStreamShape(rate, channels)
            return Choice(codec, name, AudioFormat(rate, ChannelLayout.canonical(channels)), null, null, null, 0)
        }
        return when {
            id == "A_OPUS" -> opus(track, options)
            id == "A_VORBIS" -> vorbis(track, options)
            id == "A_FLAC" -> flac(track, reader, options)
            id == "A_MPEG/L1" || id == "A_MPEG/L2" || id == "A_MPEG/L3" -> mpeg(track, options, first)
            id == "A_AAC" || id.startsWith("A_AAC/") -> aac(track, options)
            id == "A_PCM/INT/LIT" || id == "A_PCM/INT/BIG" || id == "A_PCM/FLOAT/IEEE" -> pcm(track, options)
            id == "A_AC3" || id.startsWith("A_AC3/") -> ac3(track, options, first)
            id == "A_EAC3" -> named(AudioCodec.AC3, "Enhanced AC-3")
            else -> named(AudioCodec.UNKNOWN, "'$id'")
        }
    }

    // Opus: the CodecPrivate is the OpusHead of RFC 7845.

    private fun opus(track: MatroskaReader.Track, options: DecodeOptions): Choice {
        val head = track.codecPrivate ?: throw CorruptStreamException("a Matroska Opus track has no OpusHead")
        if (head.size < 19 || head.copyOfRange(0, 8).decodeToString() != "OpusHead") {
            throw CorruptStreamException("a Matroska Opus track's CodecPrivate is not an OpusHead")
        }
        val channels = head[9].toInt() and 0xFF
        val gain = (head[16].toInt() and 0xFF) or (head[17].toInt() shl 8)
        val family = head[18].toInt() and 0xFF
        if (channels == 0) throw CorruptStreamException("an OpusHead cannot declare zero channels")
        options.checkStreamShape(48_000, channels)
        if (family == 3) {
            throw UnsupportedFeatureException(
                "Opus projection in Matroska",
                "this Matroska file codes ambisonics by projection, which AudioKodec decodes only from Ogg",
            )
        }
        val streams: Int
        val coupled: Int
        val mapping: IntArray
        if (family == 0) {
            if (channels > 2) throw CorruptStreamException("Opus channel mapping family 0 carries one or two channels, not $channels")
            streams = 1
            coupled = channels - 1
            mapping = IntArray(channels) { it }
        } else {
            if (head.size < 21 + channels) throw CorruptStreamException("the OpusHead channel mapping is cut short")
            streams = head[19].toInt() and 0xFF
            coupled = head[20].toInt() and 0xFF
            mapping = IntArray(channels) { head[21 + it].toInt() and 0xFF }
        }
        if (!OpusMultistreamDecoder.validStreamCounts(streams, coupled) || mapping.any { it != OpusMultistreamDecoder.SILENT && it >= streams + coupled }) {
            throw CorruptStreamException("the OpusHead describes an impossible channel layout")
        }
        val order = if (family == 1 && channels <= 8) VorbisDecoder.orderFor(channels) else IntArray(channels) { it }
        val layout = when (family) {
            0 -> if (channels == 1) ChannelLayout.MONO else ChannelLayout.STEREO
            1 -> VorbisDecoder.layoutFor(channels)
            else -> ChannelLayout.unknown(channels)
        }
        val format = AudioFormat(48_000, layout)
        return Choice(
            AudioCodec.OPUS, "Opus", format, null,
            counter = { FrameCounter { bytes, length, _ -> opusSamples(bytes, length) } },
            decoder = { _ ->
                Mp4Codecs.OpusPackets(format, OpusMultistreamDecoder.withMapping(48_000, channels, streams, coupled, mapping).also { it.gain = gain }, order)
            },
            headerBytes = 2,
        )
    }

    /** Samples at 48 kHz in an Opus packet, from its table of contents, RFC 6716 section 3.1. */
    private fun opusSamples(bytes: ByteArray, length: Int): Int {
        if (length < 1) return 0
        val toc = bytes[0].toInt() and 0xFF
        val config = toc ushr 3
        val frameSize = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
            config < 16 -> if (config and 1 == 0) 480 else 960
            else -> intArrayOf(120, 240, 480, 960)[config and 3]
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (length < 2) 0 else bytes[1].toInt() and 0x3F
        }
        return frameSize * frames
    }

    // Vorbis: the CodecPrivate holds the three header packets, Xiph-laced.

    private fun vorbis(track: MatroskaReader.Track, options: DecodeOptions): Choice {
        val private = track.codecPrivate ?: throw CorruptStreamException("a Matroska Vorbis track has no headers")
        val headers = xiphPackets(private)
        if (headers.size != 3) throw CorruptStreamException("a Matroska Vorbis track carries ${headers.size} header packets, not 3")
        val identification = VorbisConfig.parseIdentification(headers[0], options, 0)
        val config = VorbisConfig.parseSetup(headers[2], identification, options, 0)
        val channels = identification.channels
        options.checkStreamShape(identification.sampleRate, channels)
        val format = AudioFormat(identification.sampleRate, VorbisDecoder.layoutFor(channels))
        return Choice(
            AudioCodec.VORBIS, "Vorbis", format, null,
            counter = {
                // A packet's output runs from the previous block's centre to its own.
                val sizes = VorbisSynthesis(identification, config, options, 0)
                var previous = 0
                FrameCounter { bytes, length, _ ->
                    val block = sizes.blockSizeOf(bytes, length)
                    if (block == 0) 0 else {
                        val count = if (previous > 0) (previous + block) / 4 else 0
                        previous = block
                        count
                    }
                }
            },
            decoder = { _ -> VorbisPackets(format, VorbisSynthesis(identification, config, options, 0), identification.longBlock) },
            headerBytes = 1,
        )
    }

    private class VorbisPackets(
        override val format: AudioFormat,
        private val synthesis: VorbisSynthesis,
        longBlock: Int,
    ) : PacketCodec {
        private val channels = format.channels
        private val order = VorbisDecoder.orderFor(channels)
        override val storedBitDepth: Int? = null
        override val maxFramesPerPacket: Int = longBlock / 2

        /** One packet before a target completes the block that overlaps it. */
        override val preRollFrames: Long = longBlock.toLong()

        override fun decode(packet: ByteArray, length: Int, out: FloatArray): Int {
            if (length == 0) return 0
            val frames = synthesis.decode(packet, length)
            val output = synthesis.output
            val stride = synthesis.stride
            for (f in 0 until frames) for (c in 0 until channels) out[f * channels + c] = output[order[c] * stride + f]
            return frames
        }

        override fun reset() = synthesis.reset()
    }

    /** Splits a block of Xiph-laced packets: a count less one, the sizes of all but the last, then the packets. */
    private fun xiphPackets(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        val count = (bytes[0].toInt() and 0xFF) + 1
        var at = 1
        val sizes = IntArray(count)
        for (i in 0 until count - 1) {
            var size = 0
            while (true) {
                if (at >= bytes.size) throw CorruptStreamException("Xiph-laced headers are cut short")
                val b = bytes[at++].toInt() and 0xFF
                size += b
                if (b != 255) break
            }
            sizes[i] = size
        }
        val used = sizes.sum()
        if (used > bytes.size - at) throw CorruptStreamException("Xiph-laced headers are larger than their element")
        sizes[count - 1] = bytes.size - at - used
        return sizes.map { size -> bytes.copyOfRange(at, at + size).also { at += size } }
    }

    // FLAC: the CodecPrivate is "fLaC" and the metadata blocks, RFC 9639 section 10.2.

    private fun flac(track: MatroskaReader.Track, reader: SourceReader, options: DecodeOptions): Choice {
        val private = track.codecPrivate ?: throw CorruptStreamException("a Matroska FLAC track has no stream information")
        if (private.size < 8 + 34 || private.copyOfRange(0, 4).decodeToString() != "fLaC" || private[4].toInt() and 0x7F != 0) {
            throw CorruptStreamException("a Matroska FLAC track's CodecPrivate does not begin with stream information")
        }
        val info = FlacMetadata.parseStreamInfoBody(private, 8, options, 0)
        val format = AudioFormat(info.sampleRate, io.github.yuroyami.audiokodec.internal.flac.FlacDecoder.layoutFor(info.channels))
        return Choice(
            AudioCodec.FLAC, "FLAC", format, info.bitsPerSample,
            counter = {
                val headers = FlacFrameReader(reader, info, options, headersOnly = true)
                FrameCounter { bytes, length, _ -> headers.packetHeader(bytes, length, 0).blockSize }
            },
            decoder = { _ -> Mp4Codecs.FlacPackets(format, FlacFrameReader(reader, info, options), info.bitsPerSample, info.maximumBlockSize) },
            headerBytes = 16,
        )
    }

    // MPEG audio: each frame is one MPEG frame, header included.

    private fun mpeg(track: MatroskaReader.Track, options: DecodeOptions, first: ByteArray?): Choice {
        val header = first?.let { MpegPacketCodec.headerOf(it, it.size) }
        val rate = header?.sampleRate ?: track.sampleRate.toInt()
        val channels = header?.channels ?: track.channels
        options.checkStreamShape(rate, channels)
        val format = AudioFormat(rate, ChannelLayout.canonical(channels))
        val codec = header?.codec ?: AudioCodec.MP3
        return Choice(
            codec, codec.displayName, format, null,
            counter = { FrameCounter { bytes, length, size -> if (length < 4) 0 else MpegPacketCodec.headerOf(bytes, size).samplesPerFrame } },
            decoder = { _ -> MpegPacketCodec(format, header, options) },
            headerBytes = 4,
        )
    }

    // AC-3: one syncframe to a Block.

    private fun ac3(track: MatroskaReader.Track, options: DecodeOptions, first: ByteArray?): Choice {
        val shape = first?.let {
            if (it.size < Ac3Frame.HEADER_BYTES) throw CorruptStreamException("a Matroska AC-3 frame is shorter than its header")
            Ac3PacketCodec.shapeOf(it, 0, -1)
        }
        val format = shape?.let { AudioFormat(it.sampleRate, it.layout) }
            ?: AudioFormat(track.sampleRate.toInt(), ChannelLayout.canonical(track.channels))
        options.checkStreamShape(format.sampleRate, format.channels)
        return Choice(
            AudioCodec.AC3, "AC-3", format, null,
            counter = { FrameCounter { _, _, size -> if (size == 0) 0 else Ac3Frame.FRAMES_PER_SYNCFRAME } },
            decoder = if (shape == null) null else { _ -> Ac3PacketCodec(shape, options.tolerant) },
            headerBytes = 0,
        )
    }

    // AAC: raw blocks, configured by the AudioSpecificConfig in the CodecPrivate.

    private fun aac(track: MatroskaReader.Track, options: DecodeOptions): Choice {
        val private = track.codecPrivate ?: throw UnsupportedFeatureException(
            "AAC in Matroska without an AudioSpecificConfig",
            "this Matroska AAC track names its profile by codec ID alone and carries no AudioSpecificConfig to decode it by",
        )
        val config = AacStreamConfig.parse(private, 0)
        val rate = if (config.sbr && config.extensionRate > 0) config.extensionRate else config.sampleRate
        options.checkStreamShape(rate, config.channels)
        val format = AudioFormat(rate, config.layout)
        return Choice(
            AudioCodec.AAC, "AAC", format, null,
            counter = { FrameCounter { _, _, size -> if (size == 0) 0 else 1024 } },
            decoder = { _ -> AacPacketCodec(config, null) },
            headerBytes = 0,
        )
    }

    // PCM: interleaved samples of the track's bit depth.

    private fun pcm(track: MatroskaReader.Track, options: DecodeOptions): Choice {
        val float = track.codecId == "A_PCM/FLOAT/IEEE"
        val bits = track.bitDepth ?: throw CorruptStreamException("a Matroska PCM track states no bit depth")
        val layout = try {
            PcmLayout(bits, littleEndian = track.codecId != "A_PCM/INT/BIG", signed = float || bits > 8, isFloat = float)
        } catch (_: IllegalArgumentException) {
            throw CorruptStreamException("a Matroska PCM track of $bits-bit samples")
        }
        val rate = track.sampleRate.toInt()
        val channels = track.channels
        options.checkStreamShape(rate, channels)
        val inner = LinearPcmCodec(channels, layout)
        val format = AudioFormat(rate, ChannelLayout.canonical(channels))
        val codec = if (float) AudioCodec.PCM_FLOAT else AudioCodec.PCM_INTEGER
        val frameBytes = inner.blockBytes
        return Choice(
            codec, codec.displayName, format, inner.storedBitDepth,
            counter = { FrameCounter { _, _, size -> size / frameBytes } },
            decoder = { largest -> Mp4Codecs.PcmPackets(format, inner, null, largest / frameBytes, options) },
            headerBytes = 0,
        )
    }
}

/*
 * Translated from WavPack 5.9.0, commit 5803634a030e2a11dba602ba057b89cc34486c67,
 * which is Copyright (c) 1998 - 2025 David Bryant and licensed under the
 * BSD 3-Clause licence, reproduced in NOTICE. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.wavpack

import io.github.yuroyami.audiokodec.AudioChannel
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.AudioSink
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.Dither
import io.github.yuroyami.audiokodec.IntPcmSource
import io.github.yuroyami.audiokodec.PcmSource
import io.github.yuroyami.audiokodec.internal.codec.IntegerPcmConverter
import io.github.yuroyami.audiokodec.internal.flac.Md5
import io.github.yuroyami.audiokodec.internal.tag.ApeTag
import io.github.yuroyami.audiokodec.readChecked
import io.github.yuroyami.audiokodec.tag.TagBundle
import kotlin.math.round

/**
 * Writes a lossless WavPack file: the high-level half of `pack_utils.c` in
 * the reference, with the choices its command-line encoder makes.
 *
 * The channels are split into streams of one or two, a stereo stream for
 * each pair of speakers the reference pairs, and every block of the file
 * holds the same samples of each stream in turn. The first block carries a
 * RIFF header made the way the library makes one, so `wvunpack` restores a
 * WAV file; the last carries the MD5 sum of the audio, as `wavpack -m`
 * stores it; an APEv2 tag follows the blocks when there are tags.
 *
 * [effort] is 0 to 3 for the reference's fast, normal, high and very high
 * modes, which differ in the decorrelation filter and, from high up, in the
 * block length.
 */
internal class WavPackWriter(
    private val format: AudioFormat,
    private val bitDepth: Int,
    effort: Int,
    private val dither: Dither,
) {
    private val channels = format.channels
    private val bytesPerSample = bitDepth / 8
    private val channelMask: Int
    private val config: WavPackConfig

    /** Each stream's first channel, in the source's interleaving, and its width. */
    private val streamFirst: IntArray
    private val streamWidth: IntArray
    private val blockSamples: Int
    private val maxSamples: Int

    init {
        require(bitDepth == 8 || bitDepth == 16 || bitDepth == 24 || bitDepth == 32) {
            "AudioKodec writes 8, 16, 24 or 32-bit WavPack, not $bitDepth"
        }
        require(effort in 0..3) { "the WavPack effort is 0 to 3, not $effort" }
        require(channels in 1..MAX_CHANNELS) { "AudioKodec writes WavPack with 1 to $MAX_CHANNELS channels, not $channels" }
        require(format.sampleRate >= 1) { "WavPack needs a positive sample rate, not ${format.sampleRate}" }
        channelMask = maskFor(format.channelLayout)

        val filter: WavPackFilter
        var configFlags = CONFIG_MD5_CHECKSUM
        when (effort) {
            0 -> {
                filter = WavPackFilter.FAST
                configFlags = configFlags or CONFIG_FAST_FLAG
            }
            1 -> filter = WavPackFilter.NORMAL
            2 -> {
                filter = WavPackFilter.HIGH
                configFlags = configFlags or CONFIG_HIGH_FLAG
            }
            else -> {
                filter = WavPackFilter.VERY_HIGH
                configFlags = configFlags or CONFIG_HIGH_FLAG or CONFIG_VERY_HIGH_FLAG
            }
        }
        config = WavPackConfig(format.sampleRate, channels, channelMask, configFlags, filter)

        // `WavpackSetConfiguration64`: speakers the reference pairs go in
        // one stereo stream, and every other channel in a mono one.
        val ids = format.channelLayout.channels.map { if (it == AudioChannel.UNKNOWN) 0xFF else it.ordinal + 1 }
        val firsts = ArrayList<Int>()
        val widths = ArrayList<Int>()
        var at = 0
        while (at < channels) {
            val paired = at + 1 < channels && isStereoPair(ids[at], ids[at + 1])
            firsts += at
            widths += if (paired) 2 else 1
            at += if (paired) 2 else 1
        }
        streamFirst = firsts.toIntArray()
        streamWidth = widths.toIntArray()

        // `WavpackPackInit`: about a quarter of a second, half a second in
        // the high modes, kept between 20000 and 75000 samples in all.
        var divisor = if (configFlags and CONFIG_HIGH_FLAG != 0) 2 else 4
        while (format.sampleRate % divisor != 0) divisor--
        var samples = format.sampleRate / divisor
        while (samples > 12000 && samples.toLong() * channels > 75000) samples /= 2
        while (samples.toLong() * channels < 20000) samples *= 2
        blockSamples = samples
        maxSamples = samples + (samples shr 1)
    }

    private val packers = Array(streamFirst.size) { s ->
        val rateIndex = SAMPLE_RATES.indexOf(format.sampleRate).let { if (it < 0) 15 else it }
        var flags = (bytesPerSample - 1) or (rateIndex shl WavPackStream.SRATE_LSB) or
            WavPackPacker.CROSS_DECORR or WavPackStream.JOINT_STEREO
        if (s == 0) flags = flags or WavPackStream.INITIAL_BLOCK
        if (s == streamFirst.size - 1) flags = flags or WavPackStream.FINAL_BLOCK
        if (streamWidth[s] == 1) {
            flags = flags and (WavPackStream.JOINT_STEREO or WavPackPacker.CROSS_DECORR or WavPackStream.HYBRID_BALANCE).inv()
            flags = flags or WavPackStream.MONO_FLAG
        }
        WavPackPacker(config, s, flags)
    }

    fun encode(pcm: PcmSource, tags: TagBundle?, sink: AudioSink) {
        val read = FloatArray(CHUNK_FRAMES * channels)
        val scale = (1L shl (bitDepth - 1)).toDouble()
        val minimum = -scale
        val maximum = scale - 1.0
        write(pcm.frameCount, tags, sink) { into ->
            val got = pcm.readChecked(read, 0, CHUNK_FRAMES)
            for (i in 0 until got * channels) {
                val value = read[i]
                into[i] = if (value.isNaN()) {
                    0
                } else {
                    val noise = when (dither) {
                        Dither.None -> 0.0
                        is Dither.Tpdf -> dither.nextSample().toDouble()
                    }
                    round(value.toDouble() * scale + noise).coerceIn(minimum, maximum).toInt()
                }
            }
            got
        }
    }

    fun encode(pcm: IntPcmSource, tags: TagBundle?, sink: AudioSink) {
        val conversion = IntegerPcmConverter(pcm.bitDepth, bitDepth, dither)
        write(pcm.frameCount, tags, sink) { into ->
            val got = pcm.readChecked(into, 0, CHUNK_FRAMES)
            conversion.convertInPlace(into, got * channels)
            got
        }
    }

    /**
     * Writes the file, calling [readChunk] for up to [CHUNK_FRAMES] frames
     * of signed integers at the target depth, interleaved in the source's
     * channel order, until it returns zero.
     */
    private fun write(declaredFrames: Long?, tags: TagBundle?, sink: AudioSink, readChunk: (IntArray) -> Int) {
        require(declaredFrames == null || declaredFrames in 0..MAX_SAMPLES) {
            "WavPack frameCount must be 0..$MAX_SAMPLES, was $declaredFrames"
        }
        if (declaredFrames == 0L) throw AudioKodecException("a WavPack file cannot hold no samples; the reference refuses a length of zero")
        // Everything that can refuse does so before the first byte.
        val ape = tags?.takeIf { !it.isEmpty }?.let { ApeTag.build(it) }

        // An unknown length is stated as unknown, and corrected at the end
        // when the sink allows, which is what the reference asks of a caller.
        val statedTotal = declaredFrames ?: -1L
        val chunk = IntArray(CHUNK_FRAMES * channels)
        val buffers = Array(streamFirst.size) { IntArray(maxSamples * streamWidth[it]) }
        val hash = Md5()
        val hashBytes = ByteArray(CHUNK_FRAMES * channels * bytesPerSample)
        val block = WavPackBlock(maxSamples * 2 * bytesPerSample + 1024)
        var pending: List<Pair<Int, ByteArray>> = listOf(ID_RIFF_HEADER to riffHeader(statedTotal))
        var firstBlock: ByteArray? = null
        var firstBlockAt = 0L
        var accumulated = 0
        var frames = 0L

        fun packStreams(count: Int) {
            for (s in packers.indices) {
                packers[s].packBlock(buffers[s], count, statedTotal, pending, block)
                if (s == 0 && firstBlock == null && declaredFrames == null && sink.canPatch) {
                    firstBlockAt = sink.position
                    firstBlock = block.bytes.copyOf(block.length)
                }
                sink.write(block.bytes, 0, block.length)
            }
            pending = emptyList()
            // Move what is left up to the front for the next block.
            if (accumulated != count) {
                for (s in buffers.indices) {
                    val width = streamWidth[s]
                    buffers[s].copyInto(buffers[s], 0, count * width, accumulated * width)
                }
            }
            accumulated -= count
        }

        while (true) {
            val got = readChunk(chunk)
            if (got == 0) break
            if (frames > MAX_SAMPLES - got) throw AudioKodecException("WavPack cannot hold more than $MAX_SAMPLES frames")
            frames += got
            hashChunk(chunk, got, hash, hashBytes)
            var taken = 0
            while (taken < got) {
                val copy = minOf(got - taken, maxSamples - accumulated)
                for (s in buffers.indices) {
                    val width = streamWidth[s]
                    val first = streamFirst[s]
                    val into = buffers[s]
                    var to = accumulated * width
                    var from = taken * channels + first
                    repeat(copy) {
                        into[to] = chunk[from]
                        if (width == 2) into[to + 1] = chunk[from + 1]
                        to += width
                        from += channels
                    }
                }
                taken += copy
                accumulated += copy
                if (accumulated == maxSamples) packStreams(blockSamples)
            }
        }
        // `WavpackFlushSamples`: what is left goes in one block, or in two
        // halves when it is longer than a block.
        while (accumulated > 0) packStreams(if (accumulated > blockSamples) accumulated / 2 else accumulated)

        if (frames == 0L) throw AudioKodecException("a WavPack file cannot hold no samples; the source produced none")
        if (declaredFrames != null && declaredFrames != frames) {
            throw AudioKodecException(
                "the source promised $declaredFrames frames and produced $frames; " +
                    "source frameCount must describe the complete stream",
            )
        }

        // The MD5 sum goes in a block of its own after the audio, as the
        // reference's encoder stores it once every sample has been seen.
        block.startHeader(0, statedTotal, 0, 0)
        block.putMetadata(WavPackStream.ID_MD5_CHECKSUM, hash.digest())
        block.finish(4)
        sink.write(block.bytes, 0, block.length)

        if (ape != null) sink.write(ape)

        // `WavpackUpdateNumSamples`: the first block learns the length, and
        // so does the RIFF header in it.
        val first = firstBlock
        if (declaredFrames == null && sink.canPatch && first != null) {
            val patched = WavPackBlock(first.size)
            patched.append(first)
            val header = riffHeader(frames)
            header.copyInto(patched.bytes, WavPackHeader.SIZE + 2)
            val total = frames + frames / 0xFFFF_FFFFL
            patched.bytes[11] = (total ushr 32).toByte()
            patched.setU32(12, total.toInt())
            patched.updateChecksum()
            sink.patchAt(firstBlockAt, patched.bytes, 0, patched.length)
        }
    }

    /** Feeds the samples to the MD5 sum as a WAV file holds them: little-endian, 8-bit unsigned. */
    private fun hashChunk(samples: IntArray, frames: Int, hash: Md5, scratch: ByteArray) {
        var at = 0
        for (i in 0 until frames * channels) {
            val value = samples[i]
            if (bytesPerSample == 1) {
                scratch[at++] = (value + 128).toByte()
            } else {
                for (b in 0 until bytesPerSample) scratch[at++] = (value ushr (8 * b)).toByte()
            }
        }
        hash.update(scratch, 0, at)
    }

    /**
     * The RIFF header the library makes when the caller gives none:
     * `create_riff_header`. A junk chunk holds the place of the `ds64` chunk
     * a file too large for RIFF needs, so the header keeps its length when
     * the frame count is corrected at the end.
     */
    private fun riffHeader(totalSamples: Long): ByteArray {
        val frameBytes = bytesPerSample * channels
        val total = if (totalSamples < 0) 0x7FFF_F000L / frameBytes else totalSamples
        val dataBytes = total * frameBytes
        val rf64 = dataBytes > 0xFF00_0000L
        val extensible = channels > 2 || channelMask != 0x5 - channels
        val formatBytes = if (extensible) 40 else 16
        var riffBytes = 12L + formatBytes + 8 + dataBytes
        riffBytes += if (rf64) 8 + 28 else 36
        val out = WavPackBlock(128)
        fun ascii(text: String) = text.forEach { out.append(it.code) }
        fun u16(value: Int) {
            out.append(value)
            out.append(value ushr 8)
        }
        fun u64(value: Long) {
            out.appendU32(value.toInt())
            out.appendU32((value ushr 32).toInt())
        }
        ascii(if (rf64) "RF64" else "RIFF")
        out.appendU32(if (rf64) -1 else riffBytes.toInt())
        ascii("WAVE")
        if (rf64) {
            ascii("ds64")
            out.appendU32(28)
            u64(riffBytes)
            u64(dataBytes)
            u64(total)
            out.appendU32(0)
        } else {
            ascii("junk")
            out.appendU32(28)
            repeat(28) { out.append(0) }
        }
        ascii("fmt ")
        out.appendU32(formatBytes)
        u16(if (extensible) 0xFFFE else 1)
        u16(channels)
        out.appendU32(format.sampleRate)
        out.appendU32(format.sampleRate * channels * bytesPerSample)
        u16(bytesPerSample * channels)
        u16(if (extensible) bytesPerSample * 8 else bitDepth)
        if (extensible) {
            u16(22)
            u16(bitDepth)
            out.appendU32(channelMask)
            // KSDATAFORMAT_SUBTYPE_PCM.
            u16(1)
            for (b in PCM_GUID_TAIL) out.append(b)
        }
        ascii("data")
        out.appendU32(if (rf64) -1 else dataBytes.toInt())
        return out.bytes.copyOf(out.length)
    }

    companion object {
        /** Channels AudioKodec writes: the eight that fit WavPack's original channel information. */
        const val MAX_CHANNELS: Int = 8

        /** `MAX_WAVPACK_SAMPLES`. */
        const val MAX_SAMPLES: Long = (1L shl 40) - 257

        private const val CHUNK_FRAMES = 4096
        private const val ID_RIFF_HEADER = WavPackStream.ID_OPTIONAL_DATA or 0x1

        private const val CONFIG_FAST_FLAG = 0x200
        private const val CONFIG_HIGH_FLAG = 0x800
        private const val CONFIG_VERY_HIGH_FLAG = 0x1000
        private const val CONFIG_MD5_CHECKSUM = 0x8000000

        private val SAMPLE_RATES = intArrayOf(6000, 8000, 9600, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000, 192000)
        private val PCM_GUID_TAIL = intArrayOf(0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71)

        /** The speaker pairs, by WAV channel number from 1, that the reference codes as one stereo stream. */
        private val STEREO_PAIRS = arrayOf(1 to 2, 5 to 6, 7 to 8, 10 to 11, 13 to 15, 16 to 18)

        private fun isStereoPair(left: Int, right: Int): Boolean =
            STEREO_PAIRS.any { (a, b) -> (left == a && right == b) || (left == b && right == a) }

        /**
         * The WAV channel mask for [layout], or a refusal when a WavPack file
         * cannot carry it so that it reads back the same.
         *
         * WavPack keeps the speakers of a channel mask first, in the mask's
         * order, and any unassigned channels after them. A mono or stereo
         * file without a mask reads back as the canonical layout.
         */
        fun maskFor(layout: ChannelLayout): Int {
            val count = layout.count
            val mask = layout.channels.fold(0) { acc, c -> acc or c.waveMaskBit }
            val readBack = if (mask == 0 && count > 2) ChannelLayout.unknown(count) else ChannelLayout.fromWaveMask(mask, count)
            require(readBack == layout) {
                "WavPack stores channel layout $layout as $readBack; it keeps named speakers in WAV " +
                    "order followed by any unassigned channels, and a mono or stereo file cannot leave " +
                    "its speakers unassigned"
            }
            return mask
        }
    }
}

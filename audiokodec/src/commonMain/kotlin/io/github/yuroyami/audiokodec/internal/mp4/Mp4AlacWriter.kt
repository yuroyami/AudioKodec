package io.github.yuroyami.audiokodec.internal.mp4

import io.github.yuroyami.audiokodec.AudioChannel
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.AudioSink
import io.github.yuroyami.audiokodec.Dither
import io.github.yuroyami.audiokodec.IntPcmSource
import io.github.yuroyami.audiokodec.PcmSource
import io.github.yuroyami.audiokodec.readChecked
import io.github.yuroyami.audiokodec.internal.alac.AlacConfig
import io.github.yuroyami.audiokodec.internal.alac.AlacEncoder
import io.github.yuroyami.audiokodec.internal.codec.IntegerPcmConverter
import io.github.yuroyami.audiokodec.internal.tag.Mp4Ilst
import io.github.yuroyami.audiokodec.tag.TagBundle
import kotlin.math.round

/**
 * Writes Apple Lossless into an `.m4a` file.
 *
 * The file is an `ftyp` box, the packets in one `mdat` box, and the `moov`
 * box after them, which is the order a writer that does not know the
 * packets' sizes in advance has to use. The `mdat` header is written first
 * with room for a 64-bit size, as an empty `free` box followed by a 32-bit
 * header, and patched at the end: a file under 4 GiB keeps the plain form,
 * and a larger one turns the two into one 64-bit header. That needs a sink
 * that can be patched.
 *
 * The sample entry and the magic cookie follow Apple's description of ALAC in
 * MP4, which ships with its reference implementation; the boxes around them
 * follow Apple's QuickTime File Format specification. Tags go into the iTunes
 * item list, and chapters into a Nero `chpl` list.
 */
internal class Mp4AlacWriter(
    private val format: AudioFormat,
    private val bitDepth: Int,
    private val dither: Dither,
) {
    private val channels = format.channels
    private val frameSize = AlacEncoder.DEFAULT_FRAME_SIZE

    /** Which input channel each of ALAC's stored channels comes from. */
    private val sourceOfStored: IntArray

    init {
        require(channels in 1..AlacEncoder.MAX_CHANNELS) { "ALAC carries one to eight channels, not $channels" }
        require(bitDepth == 16 || bitDepth == 20 || bitDepth == 24 || bitDepth == 32) {
            "AudioKodec writes 16, 20, 24 or 32-bit ALAC, not $bitDepth"
        }
        require(format.sampleRate >= 1) { "ALAC needs a positive sample rate, not ${format.sampleRate}" }
        val stored = AlacConfig.storedOrder(channels)
        val given = format.channelLayout.channels
        require(given.toSet() == stored.toSet() && AudioChannel.UNKNOWN !in given) {
            "ALAC stores $channels channels as ${stored.joinToString(" ")}, " +
                "and cannot preserve channel layout ${format.channelLayout}"
        }
        sourceOfStored = IntArray(channels) { given.indexOf(stored[it]) }
    }

    fun encode(pcm: PcmSource, tags: TagBundle?, sink: AudioSink) {
        val read = FloatArray(frameSize * channels)
        val scale = (1L shl (bitDepth - 1)).toDouble()
        val minimum = -scale
        val maximum = scale - 1.0
        write(pcm.frameCount, tags, sink) { into ->
            var filled = 0
            while (filled < frameSize) {
                val got = pcm.readChecked(read, filled * channels, frameSize - filled)
                if (got == 0) break
                filled += got
            }
            for (i in 0 until filled * channels) {
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
            filled
        }
    }

    fun encode(pcm: IntPcmSource, tags: TagBundle?, sink: AudioSink) {
        val conversion = IntegerPcmConverter(pcm.bitDepth, bitDepth, dither)
        write(pcm.frameCount, tags, sink) { into ->
            var filled = 0
            while (filled < frameSize) {
                val got = pcm.readChecked(into, filled * channels, frameSize - filled)
                if (got == 0) break
                filled += got
            }
            conversion.convertInPlace(into, filled * channels)
            filled
        }
    }

    /**
     * Writes the file, calling [readPacket] for each packet's frames,
     * interleaved in the source's channel order, until it returns zero.
     */
    private fun write(declaredFrames: Long?, tags: TagBundle?, sink: AudioSink, readPacket: (IntArray) -> Int) {
        require(declaredFrames == null || declaredFrames >= 0) { "source frameCount must not be negative, was $declaredFrames" }
        if (!sink.canPatch) {
            throw AudioKodecException(
                "an MP4 file states the size of its audio before the audio, so ALAC needs a sink that " +
                    "can be patched; encode to a file or a byte array",
            )
        }
        // Everything that can refuse does so before the first byte.
        val items = tags?.let { Mp4Ilst.build(it) }
        val chapters = tags?.chapters?.takeIf { it.isNotEmpty() }?.let { Mp4Metadata.neroChapters(it) }

        val start = sink.position
        sink.write(ftyp())
        val mdatAt = sink.position
        sink.write(box("free"))
        sink.write(box("mdat"))
        val dataStart = sink.position

        val encoder = AlacEncoder(channels, bitDepth, frameSize)
        val source = IntArray(frameSize * channels)
        val stored = IntArray(frameSize * channels)
        val sizes = GrowingInts()
        var frames = 0L
        var lastFrames = 0
        var bytes = 0L
        while (true) {
            val got = readPacket(source)
            if (got == 0) break
            for (f in 0 until got) {
                val base = f * channels
                for (c in 0 until channels) stored[base + c] = source[base + sourceOfStored[c]]
            }
            val packet = encoder.encode(stored, got)
            sink.write(packet)
            sizes.add(packet.size)
            frames += got
            lastFrames = got
            bytes += packet.size
            if (got < frameSize) break
        }
        if (declaredFrames != null && declaredFrames != frames) {
            throw AudioKodecException("the source promised $declaredFrames frames and produced $frames")
        }

        // The media data's size, in whichever header form it needs.
        val mdatSize = 8L + bytes
        if (mdatSize <= UINT32_MAX) {
            sink.patchAt(mdatAt + 8, u32(mdatSize))
        } else {
            sink.patchAt(mdatAt, u32(1) + ascii("mdat") + u64(16L + bytes))
        }

        val avgBitRate = if (frames > 0) {
            (bytes.toDouble() * 8.0 * format.sampleRate / frames).coerceAtMost(UINT32_MAX.toDouble()).toLong()
        } else {
            0L
        }
        val cookie = cookie(encoder.maxFrameBytes.toLong(), avgBitRate)
        sink.write(moov(dataStart - start, sizes, frames, lastFrames, cookie, items, chapters))
    }

    // The boxes.

    private fun ftyp(): ByteArray = box("ftyp", ascii("M4A "), u32(0), ascii("M4A "), ascii("mp42"), ascii("isom"))

    private fun moov(
        dataStart: Long,
        sizes: GrowingInts,
        frames: Long,
        lastFrames: Int,
        cookie: ByteArray,
        items: ByteArray?,
        chapters: ByteArray?,
    ): ByteArray {
        val rate = format.sampleRate.toLong()
        val wide = frames > UINT32_MAX
        val packets = sizes.count

        // Chunks of about a second each, which is what players expect to read.
        val perChunk = maxOf(1, (rate / frameSize).toInt())
        val chunks = (packets + perChunk - 1) / perChunk
        val offsets = LongArray(chunks)
        var at = dataStart
        for (i in 0 until packets) {
            if (i % perChunk == 0) offsets[i / perChunk] = at
            at += sizes[i]
        }
        val chunkOffsets = if (chunks > 0 && offsets[chunks - 1] > UINT32_MAX) {
            fullBox("co64", 0, 0, u32(chunks.toLong()), table(chunks, 8) { offsets[it] })
        } else {
            fullBox("stco", 0, 0, u32(chunks.toLong()), table(chunks, 4) { offsets[it] })
        }
        val lastChunk = packets - (chunks - 1) * perChunk
        val stsc = when {
            chunks == 0 -> fullBox("stsc", 0, 0, u32(0))
            chunks == 1 -> fullBox("stsc", 0, 0, u32(1), u32(1), u32(lastChunk.toLong()), u32(1))
            lastChunk == perChunk -> fullBox("stsc", 0, 0, u32(1), u32(1), u32(perChunk.toLong()), u32(1))
            else -> fullBox("stsc", 0, 0, u32(2), u32(1), u32(perChunk.toLong()), u32(1), u32(chunks.toLong()), u32(lastChunk.toLong()), u32(1))
        }
        val full = if (packets > 0 && lastFrames < frameSize) packets - 1 else packets
        val timing = ArrayList<ByteArray>()
        if (full > 0) timing += u32(full.toLong()) + u32(frameSize.toLong())
        if (full < packets) timing += u32(1) + u32(lastFrames.toLong())
        val stts = fullBox("stts", 0, 0, u32(timing.size.toLong()), *timing.toTypedArray())
        val stsz = fullBox("stsz", 0, 0, u32(0), u32(packets.toLong()), table(packets, 4) { sizes[it].toLong() })

        val entryRate = if (rate <= 0xFFFF) rate shl 16 else 0L
        val sampleEntry = box(
            "alac",
            ByteArray(6), u16(1),
            ByteArray(8),
            u16(channels), u16(bitDepth), u16(0), u16(0), u32(entryRate),
            fullBox("alac", 0, 0, cookie),
        )
        val stbl = box(
            "stbl",
            fullBox("stsd", 0, 0, u32(1), sampleEntry),
            stts, stsc, stsz, chunkOffsets,
        )
        val minf = box(
            "minf",
            fullBox("smhd", 0, 0, u16(0), u16(0)),
            box("dinf", fullBox("dref", 0, 0, u32(1), fullBox("url ", 0, 1))),
            stbl,
        )
        val mdhd = if (wide) {
            fullBox("mdhd", 1, 0, u64(0), u64(0), u32(rate), u64(frames), u16(LANGUAGE_UND), u16(0))
        } else {
            fullBox("mdhd", 0, 0, u32(0), u32(0), u32(rate), u32(frames), u16(LANGUAGE_UND), u16(0))
        }
        val mdia = box(
            "mdia",
            mdhd,
            fullBox("hdlr", 0, 0, u32(0), ascii("soun"), ByteArray(12), ascii("SoundHandler"), ByteArray(1)),
            minf,
        )
        val tkhd = if (wide) {
            fullBox("tkhd", 1, 7, u64(0), u64(0), u32(1), u32(0), u64(frames), ByteArray(8), u16(0), u16(0), u16(0x0100), u16(0), MATRIX, u32(0), u32(0))
        } else {
            fullBox("tkhd", 0, 7, u32(0), u32(0), u32(1), u32(0), u32(frames), ByteArray(8), u16(0), u16(0), u16(0x0100), u16(0), MATRIX, u32(0), u32(0))
        }
        val mvhd = if (wide) {
            fullBox("mvhd", 1, 0, u64(0), u64(0), u32(rate), u64(frames), u32(0x0001_0000), u16(0x0100), ByteArray(10), MATRIX, ByteArray(24), u32(2))
        } else {
            fullBox("mvhd", 0, 0, u32(0), u32(0), u32(rate), u32(frames), u32(0x0001_0000), u16(0x0100), ByteArray(10), MATRIX, ByteArray(24), u32(2))
        }
        val trak = box("trak", tkhd, mdia)
        val udtaParts = ArrayList<ByteArray>()
        if (items != null) {
            udtaParts += fullBox("meta", 0, 0, ITUNES_HANDLER, box("ilst", items))
        }
        if (chapters != null) udtaParts += chapters
        val udta = if (udtaParts.isEmpty()) ByteArray(0) else box("udta", *udtaParts.toTypedArray())
        return box("moov", mvhd, trak, udta)
    }

    /**
     * The magic cookie: Apple's 24-byte `ALACSpecificConfig`, and above two
     * channels the 24-byte channel layout record naming ALAC's order.
     */
    private fun cookie(maxFrameBytes: Long, avgBitRate: Long): ByteArray {
        val config = u32(frameSize.toLong()) +
            byteArrayOf(0, bitDepth.toByte(), AlacEncoder.PB0.toByte(), AlacEncoder.MB0.toByte(), AlacEncoder.KB0.toByte(), channels.toByte()) +
            u16(AlacEncoder.MAX_RUN_DEFAULT) + u32(maxFrameBytes) + u32(avgBitRate) + u32(format.sampleRate.toLong())
        if (channels <= 2) return config
        return config + u32(24) + ascii("chan") + u32(0) + u32(LAYOUT_TAGS[channels - 1]) + u32(0) + u32(0)
    }

    /** Packet sizes, which a long recording has too many of to guess in advance. */
    private class GrowingInts {
        private var values = IntArray(1024)
        var count = 0
            private set

        fun add(value: Int) {
            if (count == values.size) {
                if (values.size >= Int.MAX_VALUE / 2) throw AudioKodecException("an MP4 track cannot hold this many packets")
                values = values.copyOf(values.size * 2)
            }
            values[count++] = value
        }

        operator fun get(index: Int): Int = values[index]
    }

    private companion object {
        const val UINT32_MAX = 0xFFFF_FFFFL

        /** ISO 639-2 `und`, packed as three five-bit letters. */
        const val LANGUAGE_UND = 0x55C4

        /** `ALACChannelLayoutTags`, for three channels and up. */
        val LAYOUT_TAGS = longArrayOf(
            (100L shl 16) or 1, (101L shl 16) or 2, (113L shl 16) or 3, (116L shl 16) or 4,
            (120L shl 16) or 5, (124L shl 16) or 6, (142L shl 16) or 7, (127L shl 16) or 8,
        )

        /** The identity transformation, in 16.16 and 2.30 fixed point. */
        val MATRIX: ByteArray = u32(0x0001_0000) + u32(0) + u32(0) + u32(0) + u32(0x0001_0000) + u32(0) + u32(0) + u32(0) + u32(0x4000_0000)

        /** The `hdlr` box iTunes puts in its metadata: `mdir`, Apple, and no name. */
        val ITUNES_HANDLER: ByteArray = fullBox("hdlr", 0, 0, u32(0), ascii("mdir"), ascii("appl"), ByteArray(9))

        /** [count] big-endian entries of [width] bytes, the body of a sample table. */
        inline fun table(count: Int, width: Int, value: (Int) -> Long): ByteArray {
            if (count.toLong() * width > Int.MAX_VALUE - 64) throw AudioKodecException("an MP4 sample table is too large for a Kotlin array")
            val out = ByteArray(count * width)
            for (i in 0 until count) {
                val v = value(i)
                for (b in 0 until width) out[i * width + b] = (v ushr (8 * (width - 1 - b))).toByte()
            }
            return out
        }

        fun box(type: String, vararg parts: ByteArray): ByteArray {
            var size = 8L
            for (part in parts) size += part.size
            if (size > Int.MAX_VALUE) throw AudioKodecException("the MP4 '$type' box is too large for a Kotlin array")
            val out = ByteArray(size.toInt())
            u32(size).copyInto(out, 0)
            ascii(type).copyInto(out, 4)
            var at = 8
            for (part in parts) {
                part.copyInto(out, at)
                at += part.size
            }
            return out
        }

        fun fullBox(type: String, version: Int, flags: Int, vararg parts: ByteArray): ByteArray =
            box(type, byteArrayOf(version.toByte(), (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte()), *parts)

        fun ascii(text: String): ByteArray = ByteArray(text.length) { text[it].code.toByte() }

        fun u16(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())

        fun u32(value: Long): ByteArray =
            byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

        fun u64(value: Long): ByteArray = u32(value ushr 32) + u32(value and UINT32_MAX)
    }
}

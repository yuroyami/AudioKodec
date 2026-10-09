package io.github.yuroyami.audiokodec.internal.container

import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.AudioInfo
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.UnsupportedFormatException
import io.github.yuroyami.audiokodec.internal.PacketCodec
import io.github.yuroyami.audiokodec.internal.PacketIndex
import io.github.yuroyami.audiokodec.internal.PacketStreamDecoder
import io.github.yuroyami.audiokodec.internal.io.Inflate
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.tag.RawTagBlock
import io.github.yuroyami.audiokodec.tag.RawTagEntry
import io.github.yuroyami.audiokodec.tag.TagChapter
import io.github.yuroyami.audiokodec.tag.TagFormat

/**
 * What [io.github.yuroyami.audiokodec.AudioKodec] does with a Matroska or
 * WebM file: reads its structure, names the coding of its first audio track,
 * and decodes it when AudioKodec has the decoder.
 *
 * Matroska times its Blocks in TimestampScale units, a millisecond by
 * default, which cannot place a sample. So the timeline is built from the
 * frames themselves: each is counted from its own header, and the track's
 * CodecDelay and the last Block's DiscardPadding trim the two ends, which is
 * how the format carries Opus's pre-skip and end trim.
 */
internal object MatroskaContainer {

    private class Opened(
        val choice: MatroskaCodecs.Choice,
        val track: MatroskaReader.Track,
        val index: PacketIndex,
        /** The largest frame once its content encoding is undone. */
        val largestRestored: Int,
        val trimStart: Long,
        val playFrames: Long,
        val info: AudioInfo,
    )

    fun info(reader: SourceReader, options: DecodeOptions): AudioInfo = open(reader, options).info

    fun decoder(reader: SourceReader, options: DecodeOptions): AudioDecoder {
        val opened = open(reader, options)
        val build = opened.choice.decoder ?: throw UnsupportedFormatException(
            AudioContainer.MATROSKA,
            "this Matroska file holds ${opened.choice.name}, which AudioKodec cannot decode yet. Its headers, tags and chapters are readable.",
        )
        val largest = opened.index.largestPacket
        options.checkAllocation(largest.toLong(), "reading a $largest-byte frame")
        val inner = build(opened.largestRestored)
        val codec = if (opened.track.zlib || opened.track.strippedHeader != null) Restoring(inner, opened.track, options) else inner
        return PacketStreamDecoder.of(reader, opened.index, codec, opened.info, opened.trimStart, opened.playFrames, options)
    }

    /** The tag block of the segment and its first audio track, with the segment's title, or none for a file that cannot be read. */
    fun tags(reader: SourceReader, options: DecodeOptions): List<RawTagBlock> {
        val file = try {
            MatroskaReader.read(reader, options, withFrames = false)
        } catch (_: AudioKodecException) {
            return emptyList()
        }
        val entries = ArrayList<RawTagEntry>()
        if (file.title != null && file.tags.none { it.key.equals("TITLE", ignoreCase = true) }) {
            entries.add(RawTagEntry("TITLE", text = file.title))
        }
        entries.addAll(file.tags)
        if (entries.isEmpty()) return emptyList()
        return listOf(RawTagBlock(TagFormat.MATROSKA, 0, 0, entries))
    }

    /** The chapters of the default edition, or none for a file that cannot be read. */
    fun chapters(reader: SourceReader, options: DecodeOptions): List<TagChapter> = try {
        val file = MatroskaReader.read(reader, options, withFrames = false)
        val chapters = file.chapters.sortedBy { it.startNs }
        val endOfFile = file.durationNs?.toLong()
        chapters.mapIndexed { i, c ->
            val end = c.endNs ?: chapters.getOrNull(i + 1)?.startNs ?: endOfFile ?: c.startNs
            TagChapter(
                id = "chp$i",
                startMillis = c.startNs / 1_000_000,
                endMillis = maxOf(end, c.startNs) / 1_000_000,
                title = c.title,
            )
        }
    } catch (_: AudioKodecException) {
        emptyList()
    }

    private fun open(reader: SourceReader, options: DecodeOptions): Opened {
        val file = MatroskaReader.read(reader, options, withFrames = true)
        val track = file.audio ?: throw UnsupportedFormatException(AudioContainer.MATROSKA, "this Matroska file has no audio track")
        val frames = file.frames ?: throw CorruptStreamException("a Matroska file's frames could not be placed")
        val first = if (frames.count > 0) restored(reader, track, frames.offsets[0], frames.sizes[0], options, frames.sizes[0]) else null
        val choice = MatroskaCodecs.choose(track, reader, options, first)
        val rate = choice.format.sampleRate

        // Count every frame's samples from its own header.
        val starts = LongArray(frames.count + 1)
        val counter = choice.counter?.invoke()
        var total = 0L
        var largestRestored = 0
        for (i in 0 until frames.count) {
            starts[i] = total
            val samples = if (counter == null) {
                0
            } else {
                val head = if (choice.headerBytes == 0 && !track.zlib) EMPTY else restored(reader, track, frames.offsets[i], frames.sizes[i], options, choice.headerBytes)
                val size = if (track.zlib) head.size else frames.sizes[i] + (track.strippedHeader?.size ?: 0)
                if (size > largestRestored) largestRestored = size
                try {
                    counter.samples(head, head.size, size)
                } catch (failure: CorruptStreamException) {
                    throw CorruptStreamException(failure.message ?: "a damaged Matroska frame", reader.absoluteOffset(frames.offsets[i]))
                }
            }
            total += samples
        }
        starts[frames.count] = total
        val index = PacketIndex(frames.offsets, frames.sizes, starts)

        val trimStart = (track.codecDelay.toDouble() * rate / 1e9).let { kotlin.math.round(it).toLong() }.coerceIn(0L, total)
        val endTrim = (frames.discardPadding.toDouble() * rate / 1e9).let { kotlin.math.round(it).toLong() }.coerceIn(0L, total - trimStart)
        val playFrames = if (counter == null) {
            // Without a decoder the frames cannot be counted; the segment's stated duration is the length.
            ((file.durationNs ?: 0.0) * rate / 1e9).toLong()
        } else {
            total - trimStart - endTrim
        }
        val audioBytes = frames.sizes.fold(0L) { sum, s -> sum + s }
        val bitrate = if (playFrames > 0) (audioBytes * 8.0 * rate / playFrames).coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt() else null
        val info = AudioInfo(
            container = AudioContainer.MATROSKA,
            codec = choice.codec,
            format = choice.format,
            durationFrames = playFrames,
            bitDepth = choice.bitDepth,
            bitrate = bitrate,
            isVariableBitrate = frames.count > 1 && frames.sizes.any { it != frames.sizes[0] },
            dataOffset = reader.absoluteOffset(frames.offsets.firstOrNull() ?: 0L),
            dataSize = audioBytes,
        )
        if (counter == null) largestRestored = frames.sizes.maxOrNull() ?: 0
        return Opened(choice, track, index, largestRestored, trimStart, playFrames, info)
    }

    private val EMPTY = ByteArray(0)

    /** The first [limit] bytes of a frame as the codec sees it, with its content encoding undone. */
    private fun restored(reader: SourceReader, track: MatroskaReader.Track, offset: Long, size: Int, options: DecodeOptions, limit: Int): ByteArray {
        val read = if (track.zlib) size else minOf(size, limit)
        options.checkAllocation(read.toLong(), "reading a Matroska frame")
        reader.seek(offset)
        val bytes = reader.readBytes(read)
        return restore(bytes, bytes.size, track, options)
    }

    private fun restore(bytes: ByteArray, length: Int, track: MatroskaReader.Track, options: DecodeOptions): ByteArray {
        if (track.zlib) {
            val limit = minOf(options.maxAllocationBytes, Int.MAX_VALUE.toLong()).toInt()
            return Inflate.zlib(bytes, 0, length, limit)
        }
        val prefix = track.strippedHeader ?: return if (length == bytes.size) bytes else bytes.copyOf(length)
        return prefix + bytes.copyOf(length)
    }

    /** Undoes a track's content encoding before the codec sees each frame. */
    private class Restoring(
        private val inner: PacketCodec,
        private val track: MatroskaReader.Track,
        private val options: DecodeOptions,
    ) : PacketCodec by inner {
        override fun decode(packet: ByteArray, length: Int, out: FloatArray): Int {
            val restored = restore(packet, length, track, options)
            return inner.decode(restored, restored.size, out)
        }

        override fun decodeInt(packet: ByteArray, length: Int, out: IntArray): Int {
            val restored = restore(packet, length, track, options)
            return inner.decodeInt(restored, restored.size, out)
        }
    }
}

package io.github.yuroyami.audiokodec.internal.mp4

import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.AudioInfo
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.UnsupportedFormatException
import io.github.yuroyami.audiokodec.internal.PacketIndex
import io.github.yuroyami.audiokodec.internal.PacketStreamDecoder
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.tag.TagChapter

/**
 * What [io.github.yuroyami.audiokodec.AudioKodec] does with an MP4 file:
 * reads its structure, names its coding, and when AudioKodec can decode the
 * coding, decodes it.
 */
internal object Mp4Container {

    /** A parsed file with its coding chosen and its packets placed in frames. */
    private class Opened(
        val file: Mp4Reader.Mp4File,
        val choice: Mp4Codecs.Choice,
        val index: PacketIndex,
        val trimStart: Long,
        val playFrames: Long,
        val info: AudioInfo,
    )

    fun info(reader: SourceReader, options: DecodeOptions): AudioInfo = open(reader, options, build = false).info

    fun decoder(reader: SourceReader, options: DecodeOptions): AudioDecoder {
        val opened = open(reader, options, build = true)
        val build = opened.choice.decoder ?: throw UnsupportedFormatException(
            AudioContainer.MP4,
            "this MP4 file holds ${opened.choice.name}, which AudioKodec cannot decode yet. " +
                "Its headers, length and chapters are readable.",
        )
        val largestPacket = opened.index.largestPacket
        options.checkAllocation(largestPacket.toLong(), "reading a $largestPacket-byte packet")
        val codec = build(largestPacket)
        return PacketStreamDecoder.of(reader, opened.index, codec, opened.info, opened.trimStart, opened.playFrames, options)
    }

    /**
     * The file's chapters, or none when its structure cannot be read: tags
     * are read from files that do not decode, and a damaged one has none.
     */
    fun chapters(reader: SourceReader, options: DecodeOptions): List<TagChapter> = try {
        Mp4Reader.parse(reader, options).chapters
    } catch (_: AudioKodecException) {
        emptyList()
    }

    private fun open(reader: SourceReader, options: DecodeOptions, build: Boolean): Opened {
        val file = Mp4Reader.parse(reader, options)
        val track = file.audio
        val firstPacket = track.offsets.firstOrNull()?.let { it to track.sizes[0] }
        val choice = Mp4Codecs.choose(track.entry, reader, options, firstPacket)
        options.checkStreamShape(choice.format.sampleRate, choice.format.channels, track.entry.offset)
        val rate = choice.format.sampleRate.toLong()

        // The tables count time in the track's own units, which for audio
        // are nearly always samples. Where they are not, every position is
        // carried over to frames at the decoded rate.
        val starts = LongArray(track.times.size) { Mp4Reader.rescale(track.times[it], rate, track.timescale) }
        val index = PacketIndex(track.offsets, track.sizes, starts)
        val total = index.totalFrames
        val hasEdit = track.editStart != 0L || track.editDuration != null
        var trimStart = if (hasEdit) Mp4Reader.rescale(track.editStart, rate, track.timescale) else choice.defaultTrimStart
        if (trimStart > total && total > 0) {
            if (!options.tolerant) throw CorruptStreamException("the edit list starts after the track's last packet", file.moov.offset)
        }
        val available = (total - trimStart).coerceAtLeast(0L)
        // An edit list's length is rounded to the movie's coarser timescale,
        // so it can overshoot the packets by a fraction of a millisecond.
        var playFrames = track.editDuration?.let { minOf(Mp4Reader.rescale(it, rate, track.timescale), available) } ?: available

        // iTunes states the delay and padding in its own record. The edit
        // list is the container's timeline and wins when it trims anything;
        // the record applies when there is no edit list, or one that plays
        // every frame, and only for a coding with no trim of its own.
        val gapless = file.itunesGapless
        if (gapless != null && trimStart == 0L && playFrames == total && choice.defaultTrimStart == 0L && gapless.delay <= total) {
            val after = total - gapless.delay
            val stated = if (gapless.frames > 0) gapless.frames else after - minOf(gapless.padding, after)
            trimStart = gapless.delay
            playFrames = minOf(stated, after)
        }

        val bitrate = if (playFrames > 0 && file.audioBytes > 0) {
            (file.audioBytes.toDouble() * 8.0 * rate / playFrames).coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()
        } else {
            null
        }
        val info = AudioInfo(
            container = AudioContainer.MP4,
            codec = choice.codec,
            format = choice.format,
            durationFrames = playFrames,
            bitDepth = choice.bitDepth,
            bitrate = bitrate,
            // Fixed-width samples stay constant-rate even when read packets
            // have different lengths after coalescing or at chunk boundaries.
            isVariableBitrate = when (choice.codec) {
                AudioCodec.PCM_INTEGER, AudioCodec.PCM_FLOAT, AudioCodec.A_LAW, AudioCodec.MU_LAW -> false
                else -> track.sizes.isNotEmpty() && track.sizes.any { it != track.sizes[0] }
            },
            dataOffset = reader.absoluteOffset(track.offsets.firstOrNull() ?: 0L),
            dataSize = file.audioBytes,
        )
        return Opened(file, choice, index, trimStart, playFrames, info)
    }
}

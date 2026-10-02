package io.github.yuroyami.audiokodecs.internal.mp4

import io.github.yuroyami.audiokodecs.AudioContainer
import io.github.yuroyami.audiokodecs.AudioDecoder
import io.github.yuroyami.audiokodecs.AudioInfo
import io.github.yuroyami.audiokodecs.AudioKodecsException
import io.github.yuroyami.audiokodecs.CorruptStreamException
import io.github.yuroyami.audiokodecs.DecodeOptions
import io.github.yuroyami.audiokodecs.UnsupportedFormatException
import io.github.yuroyami.audiokodecs.internal.PacketIndex
import io.github.yuroyami.audiokodecs.internal.PacketStreamDecoder
import io.github.yuroyami.audiokodecs.internal.io.SourceReader
import io.github.yuroyami.audiokodecs.tag.TagChapter

/**
 * What [io.github.yuroyami.audiokodecs.AudioKodecs] does with an MP4 file:
 * reads its structure, names its coding, and when AudioKodecs can decode the
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
            "this MP4 file holds ${opened.choice.name}, which AudioKodecs cannot decode yet. " +
                "Its headers, length and chapters are readable.",
        )
        val codec = build(opened.index.largestPacket)
        return PacketStreamDecoder.of(reader, opened.index, codec, opened.info, opened.trimStart, opened.playFrames, options)
    }

    /**
     * The file's chapters, or none when its structure cannot be read: tags
     * are read from files that do not decode, and a damaged one has none.
     */
    fun chapters(reader: SourceReader, options: DecodeOptions): List<TagChapter> = try {
        Mp4Reader.parse(reader, options).chapters
    } catch (_: AudioKodecsException) {
        emptyList()
    }

    private fun open(reader: SourceReader, options: DecodeOptions, build: Boolean): Opened {
        val file = Mp4Reader.parse(reader, options)
        val track = file.audio
        val choice = Mp4Codecs.choose(track.entry, reader, options)
        options.checkStreamShape(choice.format.sampleRate, choice.format.channels, track.entry.offset)
        val rate = choice.format.sampleRate.toLong()

        // The tables count time in the track's own units, which for audio
        // are nearly always samples. Where they are not, every position is
        // carried over to frames at the decoded rate.
        val starts = LongArray(track.times.size) { Mp4Reader.rescale(track.times[it], rate, track.timescale) }
        val index = PacketIndex(track.offsets, track.sizes, starts)
        val total = index.totalFrames
        val hasEdit = track.editStart != 0L || track.editDuration != null
        val trimStart = if (hasEdit) Mp4Reader.rescale(track.editStart, rate, track.timescale) else choice.defaultTrimStart
        if (trimStart > total && total > 0) {
            if (!options.tolerant) throw CorruptStreamException("the edit list starts after the track's last packet", file.moov.offset)
        }
        val available = (total - trimStart).coerceAtLeast(0L)
        // An edit list's length is rounded to the movie's coarser timescale,
        // so it can overshoot the packets by a fraction of a millisecond.
        val playFrames = track.editDuration?.let { minOf(Mp4Reader.rescale(it, rate, track.timescale), available) } ?: available

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
            isVariableBitrate = track.sizes.isNotEmpty() && track.sizes.any { it != track.sizes[0] },
            dataOffset = reader.absoluteOffset(track.offsets.firstOrNull() ?: 0L),
            dataSize = file.audioBytes,
        )
        return Opened(file, choice, index, trimStart, playFrames, info)
    }
}

package io.github.yuroyami.audiokodec.internal.flac

import io.github.yuroyami.audiokodec.AudioSink
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.tag.FlacPicture
import io.github.yuroyami.audiokodec.internal.tag.VorbisComment
import io.github.yuroyami.audiokodec.tag.TagBundle

/**
 * Rewrites the metadata blocks of a FLAC file.
 *
 * The audio frames are copied without being looked at, which is the whole
 * point: a FLAC file that has its title corrected must come out with the same
 * audio, and re-encoding it would be both slow and pointless.
 *
 * The blocks are rebuilt rather than edited in place. Stream information is
 * kept exactly as it was, the comment and picture blocks are replaced with what
 * the caller asked for, seek tables and cue sheets are carried across
 * untouched, and padding is regenerated so the next edit has room again.
 */
internal object FlacTagWriter {

    /** Bytes of padding left behind for the next edit. */
    private const val PADDING_BYTES = 4096

    /** Bytes copied at a time when moving the audio across. */
    private const val COPY_CHUNK = 1 shl 16

    fun write(
        reader: SourceReader,
        tags: TagBundle,
        sink: AudioSink,
        options: DecodeOptions,
        includeTags: Boolean = true,
        audioEnd: Long = reader.size,
        chaptersPreservedElsewhere: Boolean = false,
    ) {
        val parsed = FlacMetadata.parse(reader, options)

        // Build the new chain first, so the last-block flag can be set on
        // whichever block ends up last.
        val bodies = ArrayList<Pair<Int, ByteArray>>()

        for (block in parsed.blocks) {
            when (block.type) {
                FlacMetadata.TYPE_STREAMINFO -> {
                    bodies.add(FlacMetadata.TYPE_STREAMINFO to readBody(reader, block))
                }
                // The comment and picture blocks are what this rewrites, and
                // padding is regenerated rather than carried.
                FlacMetadata.TYPE_VORBIS_COMMENT,
                FlacMetadata.TYPE_PICTURE,
                FlacMetadata.TYPE_PADDING,
                -> Unit
                // A seek table, a cue sheet or an application block belongs to
                // somebody else and is carried across as it is.
                else -> bodies.add(block.type to readBody(reader, block))
            }
        }

        if (includeTags) {
            val vendor = parsed.tagBlocks.asSequence()
                .flatMap { it.entries.asSequence() }
                .firstOrNull { it.isVorbisVendorHeader }?.text ?: VorbisComment.DEFAULT_VENDOR
            val comment = VorbisComment.build(
                tags, vendor, includePictures = false,
                chaptersPreservedElsewhere = chaptersPreservedElsewhere,
            )
            bodies.add(FlacMetadata.TYPE_VORBIS_COMMENT to checkedBody("Vorbis comment", comment))
            for (picture in tags.pictures) {
                bodies.add(
                    FlacMetadata.TYPE_PICTURE to
                        checkedBody("picture", FlacPicture.build(picture)),
                )
            }
        }
        bodies.add(FlacMetadata.TYPE_PADDING to ByteArray(PADDING_BYTES))

        sink.write(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))
        for ((index, entry) in bodies.withIndex()) {
            val (type, body) = entry
            val isLast = index == bodies.size - 1
            val header = ByteArray(4)
            header[0] = ((if (isLast) 0x80 else 0) or type).toByte()
            header[1] = (body.size ushr 16).toByte()
            header[2] = (body.size ushr 8).toByte()
            header[3] = body.size.toByte()
            sink.write(header)
            sink.write(body)
        }

        copyAudio(reader, sink, parsed.audioOffset, audioEnd)
    }

    private fun readBody(reader: SourceReader, block: FlacMetadata.BlockLocation): ByteArray {
        reader.seek(block.offset + 4)
        return reader.readBytes(block.bodySize)
    }

    private fun checkedBody(what: String, body: ByteArray): ByteArray {
        if (body.size > MAX_METADATA_BODY) {
            throw AudioKodecException(
                "a FLAC $what is ${body.size} bytes; metadata blocks are limited to " +
                    "$MAX_METADATA_BODY",
            )
        }
        return body
    }

    private fun copyAudio(
        reader: SourceReader,
        sink: AudioSink,
        from: Long,
        to: Long,
    ) {
        if (to >= 0 && to < from) {
            throw TruncatedStreamException(
                "the FLAC audio begins after the end of its container",
                from,
            )
        }
        val buffer = ByteArray(COPY_CHUNK)
        reader.seek(from)
        var left = if (to < 0) Long.MAX_VALUE else to - from
        while (left > 0) {
            val wanted = minOf(left, buffer.size.toLong()).toInt()
            val got = reader.readSome(buffer, 0, wanted)
            if (got <= 0) {
                if (to >= 0) {
                    throw TruncatedStreamException(
                        "the FLAC source ended with $left audio bytes still to copy",
                        reader.position,
                    )
                }
                break
            }
            sink.write(buffer, 0, got)
            left -= got
        }
    }

    private const val MAX_METADATA_BODY = 0xFF_FFFF
}

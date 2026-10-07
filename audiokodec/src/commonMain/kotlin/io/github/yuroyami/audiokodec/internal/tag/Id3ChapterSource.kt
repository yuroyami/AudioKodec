package io.github.yuroyami.audiokodec.internal.tag

import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.tag.TagPicture

/** The original embedded sequence and modelled values of a parsed chapter. */
internal class Id3ChapterSource(
    stored: ByteArray,
    val major: Int,
    val title: String?,
    pictures: List<TagPicture>,
    val options: DecodeOptions,
) {
    val body: ByteArray
    private val originalPictures: List<TagPicture>

    init {
        options.checkAllocation(stored.size.toLong(), "retaining chapter subframes")
        body = stored.copyOf()
        originalPictures = pictures.map { picture ->
            options.checkAllocation(picture.bytes.size.toLong(), "retaining chapter artwork identity")
            TagPicture(picture.type, picture.mimeType, picture.description, picture.bytes.copyOf(),
                picture.width, picture.height, picture.colourDepth, picture.colourCount)
        }
    }

    class Frame(val id: String, val flags: Int, val start: Int, val end: Int) {
        val payloadStart: Int get() = start + 10
        fun discardOnTagAlter(major: Int): Boolean = flags and (if (major == 4) 0x4000 else 0x8000) != 0
        fun opaque(major: Int): Boolean = flags and (if (major == 4) 0x8FBC else 0x1FDF) != 0
    }

    fun frameBytes(frame: Frame): ByteArray = body.copyOfRange(frame.start, frame.end)

    class Payload(val bytes: ByteArray, val group: Int?)

    /** Unsynchronisation includes the flag prefixes, so undo it before stripping them. */
    fun payload(frame: Frame): Payload {
        var bytes = body.copyOfRange(frame.payloadStart, frame.end)
        if (major == 4 && frame.flags and 2 != 0) bytes = Id3v2Reader.undoUnsynchronisation(bytes)
        var at = 0
        val grouped = frame.flags and (if (major == 4) 0x40 else 0x20) != 0
        val group = if (grouped) {
            if (bytes.isEmpty()) throw CorruptStreamException("truncated chapter '${frame.id}' group identity")
            bytes[at++].toInt() and 255
        } else null
        if (major == 4 && frame.flags and 1 != 0) {
            if (bytes.size - at < 4) throw CorruptStreamException("truncated chapter '${frame.id}' data length")
            var length = 0L
            repeat(4) {
                val byte = bytes[at++].toInt() and 255
                if (byte > 127) throw CorruptStreamException("non-synchsafe chapter '${frame.id}' data length")
                length = (length shl 7) or byte.toLong()
            }
            if (length != bytes.size - at.toLong()) throw CorruptStreamException("incorrect chapter '${frame.id}' data length")
        }
        return Payload(bytes.copyOfRange(at, bytes.size), group)
    }

    /** A malformed tail cannot be silently removed while preserving a chapter. */
    fun frames(): List<Frame> {
        val idEnd = body.indexOf(0)
        if (idEnd < 0 || idEnd > body.size - 17) throw CorruptStreamException("invalid stored ID3 chapter header")
        val frames = ArrayList<Frame>()
        var at = idEnd + 17
        while (at < body.size) {
            if (body[at].toInt() == 0) {
                if ((at until body.size).any { body[it].toInt() != 0 }) {
                    throw CorruptStreamException("non-zero bytes after chapter subframe padding")
                }
                break
            }
            if (body.size - at < 10) throw CorruptStreamException("truncated chapter subframe header")
            if ((at until at + 4).any {
                    val char = body[it].toInt() and 255
                    char !in 'A'.code..'Z'.code && char !in '0'.code..'9'.code
                }) throw CorruptStreamException("invalid chapter subframe identifier")
            val id = body.decodeToString(at, at + 4)
            var size = 0L
            for (index in 4..7) {
                val byte = body[at + index].toInt() and 255
                if (major == 4 && byte > 127) throw CorruptStreamException("non-synchsafe chapter '$id' size")
                size = (size shl (if (major == 4) 7 else 8)) or byte.toLong()
            }
            if (size > body.size - at - 10L) throw CorruptStreamException("chapter '$id' subframe extends past its parent")
            options.checkAllocation((frames.size.toLong() + 1L) * 64L, "retaining chapter subframe headers")
            val flags = ((body[at + 8].toInt() and 255) shl 8) or (body[at + 9].toInt() and 255)
            val end = at + 10 + size.toInt()
            frames.add(Frame(id, flags, at, end))
            at = end
        }
        return frames
    }

    fun samePictures(pictures: List<TagPicture>): Boolean = pictures.size == originalPictures.size &&
        pictures.indices.all { index ->
            val left = pictures[index]
            val right = originalPictures[index]
            left.type == right.type && left.mimeType == right.mimeType && left.description == right.description &&
                left.width == right.width && left.height == right.height && left.colourDepth == right.colourDepth &&
                left.colourCount == right.colourCount && left.bytes.contentEquals(right.bytes)
        }
}

package io.github.yuroyami.audiokodec.internal.container

import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.internal.io.SourceReader

/**
 * Reads EBML, the binary format of RFC 8794 that Matroska is written in.
 *
 * An EBML document is a tree of elements. Each starts with its ID and the
 * size of its data, both variable-size integers whose first byte says how
 * many bytes they take, and a master element's data is more elements. A size
 * whose data bits are all ones is unknown: the element runs until something
 * that cannot be its child begins, which is how a live recording writes its
 * clusters.
 */
internal object Ebml {

    /** A size that the element left unknown. */
    const val UNKNOWN_SIZE = -1L

    /** One element header: where it starts, what it is, and where its data lies. */
    class Header(
        val offset: Long,
        val id: Long,
        /** The data's size, or [UNKNOWN_SIZE]. */
        val size: Long,
        val dataOffset: Long,
    ) {
        /** One past the data, or [Long.MAX_VALUE] when the size is unknown. */
        val end: Long get() = if (size == UNKNOWN_SIZE) Long.MAX_VALUE else dataOffset + size
    }

    /**
     * Reads the element header at [at], or returns null when the source ends
     * there. An ID keeps its marker bit, as the specifications write IDs.
     */
    fun header(reader: SourceReader, at: Long): Header? {
        reader.seek(at)
        val first = readByteOrNull(reader) ?: return null
        val idLength = lengthOf(first, 4) ?: throw CorruptStreamException("an EBML element ID longer than four bytes", at)
        var id = first.toLong()
        for (i in 1 until idLength) id = (id shl 8) or readByte(reader, at).toLong()
        val sizeAt = reader.position
        val sizeFirst = readByte(reader, sizeAt)
        val sizeLength = lengthOf(sizeFirst, 8) ?: throw CorruptStreamException("an EBML size longer than eight bytes", sizeAt)
        var size = (sizeFirst and (0xFF ushr sizeLength)).toLong()
        var allOnes = size == (0xFF ushr sizeLength).toLong()
        for (i in 1 until sizeLength) {
            val b = readByte(reader, sizeAt)
            size = (size shl 8) or b.toLong()
            allOnes = allOnes && b == 0xFF
        }
        if (allOnes) size = UNKNOWN_SIZE
        return Header(at, id, size, reader.position)
    }

    /** Reads a variable-size integer's value from [bytes] at [at], returning it and its length. */
    fun vint(bytes: ByteArray, at: Int, limit: Int): Pair<Long, Int>? {
        if (at >= limit) return null
        val first = bytes[at].toInt() and 0xFF
        val length = lengthOf(first, 8) ?: return null
        if (at + length > limit) return null
        var value = (first and (0xFF ushr length)).toLong()
        for (i in 1 until length) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        return value to length
    }

    /** An unsigned integer element's value, of zero to eight bytes. */
    fun unsigned(reader: SourceReader, header: Header): Long {
        if (header.size !in 0..8) throw CorruptStreamException("an EBML integer of ${header.size} bytes", header.offset)
        reader.seek(header.dataOffset)
        var value = 0L
        for (i in 0 until header.size) value = (value shl 8) or readByte(reader, header.offset).toLong()
        return value
    }

    /** A float element's value, of zero, four or eight bytes. */
    fun float(reader: SourceReader, header: Header): Double {
        return when (header.size) {
            0L -> 0.0
            4L -> Float.fromBits(unsigned(reader, header).toInt()).toDouble()
            8L -> Double.fromBits(unsigned(reader, header))
            else -> throw CorruptStreamException("an EBML float of ${header.size} bytes", header.offset)
        }
    }

    /** A string or UTF-8 element's text, up to its first zero byte. */
    fun string(reader: SourceReader, header: Header, limit: Int): String {
        val bytes = bytes(reader, header, limit)
        val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
        return bytes.decodeToString(0, end)
    }

    /** A binary element's bytes, refusing more than [limit]. */
    fun bytes(reader: SourceReader, header: Header, limit: Int): ByteArray {
        if (header.size !in 0..limit.toLong()) {
            throw CorruptStreamException("an EBML element of ${header.size} bytes where at most $limit are expected", header.offset)
        }
        reader.seek(header.dataOffset)
        return reader.readBytes(header.size.toInt())
    }

    /** How many bytes a variable-size integer starting with [first] takes, or null past [max]. */
    private fun lengthOf(first: Int, max: Int): Int? {
        for (length in 1..max) if (first and (0x100 ushr length) != 0) return length
        return null
    }

    private fun readByteOrNull(reader: SourceReader): Int? {
        val one = ByteArray(1)
        return if (reader.readSome(one, 0, 1) == 1) one[0].toInt() and 0xFF else null
    }

    private fun readByte(reader: SourceReader, at: Long): Int =
        readByteOrNull(reader) ?: throw TruncatedStreamException("an EBML element header is cut short", at)
}

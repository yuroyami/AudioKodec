package io.github.yuroyami.audiokodecs.internal.io

import io.github.yuroyami.audiokodecs.AudioSource
import io.github.yuroyami.audiokodecs.CorruptStreamException
import io.github.yuroyami.audiokodecs.DecodeOptions
import io.github.yuroyami.audiokodecs.AudioKodecsException
import io.github.yuroyami.audiokodecs.TruncatedStreamException
import kotlin.math.min

/**
 * A buffered, random-access byte reader over an [AudioSource].
 *
 * Every container parser in AudioKodecs reads through this, so the endian
 * handling and the end-of-input behaviour are written once. Reads that fall
 * short of what was asked for throw [TruncatedStreamException]; the caller
 * decides whether that is fatal by looking at [DecodeOptions.tolerant].
 *
 * Reads larger than the buffer bypass it and go straight to the source, so
 * pulling a megabyte of samples does not walk through a 64 KiB window.
 */
internal class SourceReader(
    val source: AudioSource,
    val options: DecodeOptions,
    bufferSize: Int = 64 * 1024,
    /** Absolute source position represented by logical position zero. */
    val baseOffset: Long = 0L,
) {
    init {
        require(baseOffset >= 0) { "base offset must not be negative, was $baseOffset" }
        require(bufferSize > 0) { "bufferSize must be positive, was $bufferSize" }
    }

    private val buffer = ByteArray(
        minOf(
            bufferSize.toLong(),
            options.maxAllocationBytes,
            Int.MAX_VALUE.toLong(),
        ).toInt(),
    )
    private var bufferStart = -1L
    private var bufferLength = 0

    /** Position of the next byte relative to [baseOffset]. */
    var position: Long = 0L
        private set

    /** Size of this logical view, or -1 when the source size is not known. */
    val size: Long
        get() = if (source.size < 0) -1L else (source.size - baseOffset).coerceAtLeast(0L)

    /** Converts an offset in this view to its position in the original source. */
    fun absoluteOffset(offset: Long): Long {
        if (offset < 0) {
            throw CorruptStreamException("a source offset is negative", offset)
        }
        if (offset > Long.MAX_VALUE - baseOffset) {
            throw CorruptStreamException("a source offset overflows 64 bits", offset)
        }
        return baseOffset + offset
    }

    /** Bytes left, or [Long.MAX_VALUE] when the size is not known. */
    val remaining: Long
        get() = if (size < 0) Long.MAX_VALUE else (size - position).coerceAtLeast(0L)

    fun seek(newPosition: Long) {
        require(newPosition >= 0) { "position must not be negative, was $newPosition" }
        position = newPosition
    }

    fun skip(count: Long) {
        require(count >= 0) { "skip count must not be negative, was $count" }
        if (position > Long.MAX_VALUE - count) {
            throw CorruptStreamException("a source position overflows 64 bits", position)
        }
        position += count
    }

    /** True when the source has no more bytes at the current position. */
    fun exhausted(): Boolean {
        if (size >= 0) return position >= size
        return peekByteOrEof() < 0
    }

    /** Reads one byte as 0..255, or -1 at end of input, without advancing. */
    fun peekByteOrEof(): Int {
        val here = position
        val b = readByteOrEof()
        position = here
        return b
    }

    /** Reads one byte as 0..255, or -1 at end of input. */
    fun readByteOrEof(): Int {
        if (!isBuffered(position)) {
            if (!fill(position)) return -1
        }
        val b = buffer[(position - bufferStart).toInt()].toInt() and 0xFF
        if (position == Long.MAX_VALUE) {
            throw CorruptStreamException("a source position overflows 64 bits", position)
        }
        position++
        return b
    }

    /**
     * Reads up to [count] bytes into [into]. Returns how many were read, which
     * is less than [count] only at end of input.
     */
    fun readSome(into: ByteArray, offset: Int, count: Int): Int {
        require(offset >= 0 && count >= 0 && offset <= into.size - count) {
            "offset=$offset count=$count is out of bounds for a ${into.size}-byte array"
        }
        var done = 0
        while (done < count) {
            val inBuffer = isBuffered(position)
            if (inBuffer) {
                val start = (position - bufferStart).toInt()
                val n = min(bufferLength - start, count - done)
                buffer.copyInto(into, offset + done, start, start + n)
                advancePosition(n)
                done += n
            } else if (count - done >= buffer.size) {
                val n = readAtChecked(
                    absoluteOffset(position),
                    into,
                    offset + done,
                    count - done,
                )
                if (n <= 0) break
                advancePosition(n)
                done += n
            } else {
                if (!fill(position)) break
            }
        }
        return done
    }

    /** Reads exactly [count] bytes, or throws. */
    fun readFully(into: ByteArray, offset: Int = 0, count: Int = into.size - offset) {
        val start = position
        val got = readSome(into, offset, count)
        if (got != count) {
            throw TruncatedStreamException(
                "wanted $count bytes but only $got were available", start,
            )
        }
    }

    /** Reads exactly [count] bytes into a fresh array, or throws. */
    fun readBytes(count: Int): ByteArray {
        options.checkAllocation(count.toLong(), "reading $count bytes")
        val out = ByteArray(count)
        readFully(out)
        return out
    }

    fun readU8(): Int {
        val b = readByteOrEof()
        if (b < 0) throw TruncatedStreamException("wanted 1 byte, found end of input", position)
        return b
    }

    fun readI8(): Int = readU8().toByte().toInt()

    fun readU16Le(): Int {
        val a = readU8()
        val b = readU8()
        return a or (b shl 8)
    }

    fun readU16Be(): Int {
        val a = readU8()
        val b = readU8()
        return (a shl 8) or b
    }

    fun readI16Le(): Int = readU16Le().toShort().toInt()

    fun readI16Be(): Int = readU16Be().toShort().toInt()

    fun readU24Le(): Int {
        val a = readU8()
        val b = readU8()
        val c = readU8()
        return a or (b shl 8) or (c shl 16)
    }

    fun readU24Be(): Int {
        val a = readU8()
        val b = readU8()
        val c = readU8()
        return (a shl 16) or (b shl 8) or c
    }

    fun readI32Le(): Int {
        val a = readU8()
        val b = readU8()
        val c = readU8()
        val d = readU8()
        return a or (b shl 8) or (c shl 16) or (d shl 24)
    }

    fun readI32Be(): Int {
        val a = readU8()
        val b = readU8()
        val c = readU8()
        val d = readU8()
        return (a shl 24) or (b shl 16) or (c shl 8) or d
    }

    /** A 32-bit field read as unsigned, so a size field near 4 GiB stays positive. */
    fun readU32Le(): Long = readI32Le().toLong() and 0xFFFF_FFFFL

    /** A 32-bit field read as unsigned, so a size field near 4 GiB stays positive. */
    fun readU32Be(): Long = readI32Be().toLong() and 0xFFFF_FFFFL

    fun readI64Le(): Long {
        val low = readU32Le()
        val high = readU32Le()
        return low or (high shl 32)
    }

    fun readI64Be(): Long {
        val high = readU32Be()
        val low = readU32Be()
        return low or (high shl 32)
    }

    fun readF32Le(): Float = Float.fromBits(readI32Le())

    fun readF32Be(): Float = Float.fromBits(readI32Be())

    fun readF64Le(): Double = Double.fromBits(readI64Le())

    fun readF64Be(): Double = Double.fromBits(readI64Be())

    /**
     * Reads a four-character chunk identifier.
     *
     * Bytes outside printable ASCII are kept as they are rather than rejected,
     * because an unknown chunk with a strange name has to be skippable, and
     * the name only ever ends up in an error message.
     */
    fun readFourCc(): String {
        val bytes = ByteArray(4)
        readFully(bytes)
        return fourCcOf(bytes, 0)
    }

    /** Throws unless the next four bytes are [expected]. */
    fun expectFourCc(expected: String, what: String) {
        val at = position
        val actual = readFourCc()
        if (actual != expected) {
            throw CorruptStreamException("$what: expected '$expected' but found '$actual'", at)
        }
    }

    private fun fill(at: Long): Boolean {
        var got = 0
        while (got < buffer.size) {
            val logical = if (got > Long.MAX_VALUE - at) return false else at + got
            val n = readAtChecked(
                absoluteOffset(logical),
                buffer,
                got,
                buffer.size - got,
            )
            if (n <= 0) break
            got += n
        }
        bufferStart = at
        bufferLength = got
        return got > 0
    }

    private fun readAtChecked(
        at: Long,
        into: ByteArray,
        offset: Int,
        count: Int,
    ): Int {
        val got = source.readAt(at, into, offset, count)
        if (got > count || got < -1 || (got == 0 && count != 0)) {
            throw AudioKodecsException(
                "AudioSource.readAt returned $got bytes when it was asked for $count",
            )
        }
        return got
    }

    private fun isBuffered(at: Long): Boolean =
        bufferStart >= 0 &&
            at >= bufferStart &&
            at - bufferStart < bufferLength

    private fun advancePosition(count: Int) {
        if (count < 0 || count.toLong() > Long.MAX_VALUE - position) {
            throw CorruptStreamException("a source position overflows 64 bits", position)
        }
        position += count
    }
}

/** Reads four bytes of [bytes] at [offset] as a chunk identifier. */
internal fun fourCcOf(bytes: ByteArray, offset: Int): String {
    val chars = CharArray(4)
    for (i in 0 until 4) {
        chars[i] = (bytes[offset + i].toInt() and 0xFF).toChar()
    }
    return chars.concatToString()
}

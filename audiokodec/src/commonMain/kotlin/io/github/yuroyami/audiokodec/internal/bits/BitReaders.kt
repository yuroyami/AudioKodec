package io.github.yuroyami.audiokodec.internal.bits

import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.TruncatedStreamException

/**
 * Reads bits from a byte array, most significant bit of each byte first.
 *
 * This is the order MPEG audio, AAC, FLAC and ALAC use. Codecs decode from a
 * packet or frame buffer rather than straight from the file, so this works on
 * an array instead of on a source.
 */
internal class BitReaderMsb(
    private val data: ByteArray,
    private val offset: Int = 0,
    private val length: Int = data.size - offset,
) {
    init {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) {
            "offset=$offset length=$length is out of bounds for a ${data.size}-byte array"
        }
        if (length > MAX_BYTES) {
            throw CorruptStreamException(
                "a bit-reader region of $length bytes is too large to address safely",
            )
        }
    }

    /** Bits consumed so far, counted from the start of the region. */
    var bitPosition: Int = 0
        private set

    /** Bits in the region. */
    val bitLength: Int get() = length * 8

    /** Bits left to read. */
    val bitsRemaining: Int get() = bitLength - bitPosition

    fun seekBits(position: Int) {
        require(position in 0..bitLength) {
            "bit position must be in 0..$bitLength, was $position"
        }
        bitPosition = position
    }

    /** Reads one bit as 0 or 1. */
    fun readBit(): Int {
        if (bitPosition >= bitLength) {
            throw TruncatedStreamException("ran out of bits after $bitLength")
        }
        val byte = data[offset + (bitPosition ushr 3)].toInt() and 0xFF
        val bit = (byte ushr (7 - (bitPosition and 7))) and 1
        bitPosition++
        return bit
    }

    /**
     * Reads [count] bits, 0 to 32, as an unsigned value.
     *
     * A count of 32 returns a value whose top bit lands in the sign bit of the
     * `Int`, which is what every caller of a 32-bit field wants: the bit
     * pattern, not a magnitude.
     */
    fun readBits(count: Int): Int {
        require(count in 0..32) { "count must be 0..32, was $count" }
        if (count == 0) return 0
        if (count > bitsRemaining) {
            throw TruncatedStreamException(
                "wanted $count bits at $bitPosition but the region holds $bitLength",
            )
        }
        // Up to 25 bits never reach past the fourth byte from the one they start in, so one load of four serves.
        val first = offset + (bitPosition ushr 3)
        if (count <= 25 && first + 4 <= offset + length) {
            val window = ((data[first].toInt() and 0xFF) shl 24) or ((data[first + 1].toInt() and 0xFF) shl 16) or
                ((data[first + 2].toInt() and 0xFF) shl 8) or (data[first + 3].toInt() and 0xFF)
            val value = (window shl (bitPosition and 7)) ushr (32 - count)
            bitPosition += count
            return value
        }
        var result = 0
        var left = count
        while (left > 0) {
            val byteIndex = offset + (bitPosition ushr 3)
            val bitInByte = bitPosition and 7
            val available = 8 - bitInByte
            val take = if (left < available) left else available
            val byte = data[byteIndex].toInt() and 0xFF
            val shifted = byte ushr (available - take)
            val mask = (1 shl take) - 1
            result = (result shl take) or (shifted and mask)
            bitPosition += take
            left -= take
        }
        return result
    }

    /** Reads [count] bits, 0 to 64, as an unsigned value in a `Long`. */
    fun readBitsLong(count: Int): Long {
        require(count in 0..64) { "count must be 0..64, was $count" }
        if (count <= 32) return readBits(count).toLong() and 0xFFFF_FFFFL
        val high = readBits(count - 32).toLong() and 0xFFFF_FFFFL
        val low = readBits(32).toLong() and 0xFFFF_FFFFL
        return (high shl 32) or low
    }

    /** Reads [count] bits and sign-extends them from bit `count - 1`. */
    fun readSignedBits(count: Int): Int {
        require(count in 1..32) { "count must be 1..32, was $count" }
        val raw = readBits(count)
        if (count == 32) return raw
        val signBit = 1 shl (count - 1)
        return if (raw and signBit != 0) raw - (1 shl count) else raw
    }

    /** Reads [count] bits and sign-extends them into a `Long`. */
    fun readSignedBitsLong(count: Int): Long {
        require(count in 1..64) { "count must be 1..64, was $count" }
        val raw = readBitsLong(count)
        if (count == 64) return raw
        val signBit = 1L shl (count - 1)
        return if (raw and signBit != 0L) raw - (1L shl count) else raw
    }

    /** Reads bits without consuming them. */
    fun peekBits(count: Int): Int {
        val here = bitPosition
        val value = readBits(count)
        bitPosition = here
        return value
    }

    fun skipBits(count: Int) {
        require(count >= 0) { "count must not be negative, was $count" }
        if (count > bitsRemaining) {
            throw TruncatedStreamException(
                "wanted to skip $count bits at $bitPosition but the region holds $bitLength",
            )
        }
        bitPosition += count
    }

    /** Counts zero bits up to the next one bit, and consumes that one bit. */
    fun readUnary(): Int {
        var zeros = 0
        var position = bitPosition
        while (position < bitLength) {
            // The bits left in this byte, moved to the top of an eight-bit window.
            val window = (data[offset + (position ushr 3)].toInt() shl (position and 7)) and 0xFF
            if (window != 0) {
                val leading = window.countLeadingZeroBits() - 24
                bitPosition = position + leading + 1
                return zeros + leading
            }
            val step = 8 - (position and 7)
            zeros += step
            position += step
        }
        bitPosition = bitLength
        throw TruncatedStreamException("ran out of bits after $bitLength")
    }

    /** Moves to the next byte boundary. Returns the bits skipped. */
    fun alignToByte(): Int {
        val skew = bitPosition and 7
        if (skew == 0) return 0
        val pad = 8 - skew
        skipBits(pad)
        return pad
    }

    /** True when the reader is on a byte boundary. */
    val isByteAligned: Boolean get() = (bitPosition and 7) == 0

    /** Byte offset of the next unread byte. Only valid when byte aligned. */
    val bytePosition: Int get() = bitPosition ushr 3

    private companion object {
        const val MAX_BYTES = Int.MAX_VALUE / 8
    }
}

/**
 * Reads bits from a byte array, least significant bit of each byte first.
 *
 * This is the order Vorbis uses, and it is not interchangeable with
 * [BitReaderMsb]: the same bytes give different values.
 */
internal class BitReaderLsb(
    private val data: ByteArray,
    private val offset: Int = 0,
    private val length: Int = data.size - offset,
) {
    init {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) {
            "offset=$offset length=$length is out of bounds for a ${data.size}-byte array"
        }
        if (length > MAX_BYTES) {
            throw CorruptStreamException(
                "a bit-reader region of $length bytes is too large to address safely",
            )
        }
    }

    var bitPosition: Int = 0
        private set

    val bitLength: Int get() = length * 8

    val bitsRemaining: Int get() = bitLength - bitPosition

    fun seekBits(position: Int) {
        require(position in 0..bitLength) {
            "bit position must be in 0..$bitLength, was $position"
        }
        bitPosition = position
    }

    fun readBit(): Int {
        if (bitPosition >= bitLength) {
            throw TruncatedStreamException("ran out of bits after $bitLength")
        }
        val byte = data[offset + (bitPosition ushr 3)].toInt() and 0xFF
        val bit = (byte ushr (bitPosition and 7)) and 1
        bitPosition++
        return bit
    }

    /** Reads [count] bits, 0 to 32, with the first bit read as the lowest. */
    fun readBits(count: Int): Int {
        require(count in 0..32) { "count must be 0..32, was $count" }
        if (count == 0) return 0
        if (count > bitsRemaining) {
            throw TruncatedStreamException(
                "wanted $count bits at $bitPosition but the region holds $bitLength",
            )
        }
        var result = 0
        var filled = 0
        while (filled < count) {
            val byteIndex = offset + (bitPosition ushr 3)
            val bitInByte = bitPosition and 7
            val available = 8 - bitInByte
            val take = if (count - filled < available) count - filled else available
            val byte = data[byteIndex].toInt() and 0xFF
            val chunk = (byte ushr bitInByte) and ((1 shl take) - 1)
            result = result or (chunk shl filled)
            bitPosition += take
            filled += take
        }
        return result
    }

    fun skipBits(count: Int) {
        require(count >= 0) { "count must not be negative, was $count" }
        if (count > bitsRemaining) {
            throw TruncatedStreamException(
                "wanted to skip $count bits at $bitPosition but the region holds $bitLength",
            )
        }
        bitPosition += count
    }

    fun alignToByte(): Int {
        val skew = bitPosition and 7
        if (skew == 0) return 0
        val pad = 8 - skew
        skipBits(pad)
        return pad
    }

    val isByteAligned: Boolean get() = (bitPosition and 7) == 0

    val bytePosition: Int get() = bitPosition ushr 3

    private companion object {
        const val MAX_BYTES = Int.MAX_VALUE / 8
    }
}

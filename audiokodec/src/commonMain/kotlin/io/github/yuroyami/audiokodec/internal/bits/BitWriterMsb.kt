package io.github.yuroyami.audiokodec.internal.bits

import io.github.yuroyami.audiokodec.AudioKodecException

/**
 * Writes bits into a growable buffer, most significant bit first.
 *
 * This is the order FLAC writes, and the mirror of [BitReaderMsb]. Output is
 * collected in memory because a codec needs to know how long a frame came out
 * before it can write the frame's own checksum over it.
 */
internal class BitWriterMsb(initialCapacity: Int = 4096) {

    init {
        require(initialCapacity >= 0) {
            "initialCapacity must not be negative, was $initialCapacity"
        }
    }

    private var buffer = ByteArray(if (initialCapacity < 64) 64 else initialCapacity)

    /** Bytes already complete. */
    var byteLength: Int = 0
        private set

    /** Bits of the byte after those that are filled. */
    private var pendingBits = 0
    private var pending = 0

    /** Bits written so far. */
    val bitLength: Long get() = byteLength.toLong() * 8L + pendingBits

    /** True when the next write starts a fresh byte. */
    val isByteAligned: Boolean get() = pendingBits == 0

    fun writeBit(value: Int) {
        pending = (pending shl 1) or (value and 1)
        pendingBits++
        if (pendingBits == 8) {
            ensure(byteLength.toLong() + 1L)
            buffer[byteLength++] = pending.toByte()
            pending = 0
            pendingBits = 0
        }
    }

    /** Writes the low [count] bits of [value], 0 to 32 of them. */
    fun writeBits(value: Int, count: Int) {
        require(count in 0..32) { "count must be 0..32, was $count" }
        var left = count
        while (left > 0) {
            val room = 8 - pendingBits
            val take = if (left < room) left else room
            val shifted = if (left - take >= 32) 0 else (value ushr (left - take))
            val mask = if (take == 32) -1 else (1 shl take) - 1
            pending = (pending shl take) or (shifted and mask)
            pendingBits += take
            left -= take
            if (pendingBits == 8) {
                ensure(byteLength.toLong() + 1L)
                buffer[byteLength++] = pending.toByte()
                pending = 0
                pendingBits = 0
            }
        }
    }

    /** Writes [count] bits of [value] as an unsigned value in a `Long`. */
    fun writeBitsLong(value: Long, count: Int) {
        require(count in 0..64) { "count must be 0..64, was $count" }
        if (count > 32) {
            writeBits((value ushr 32).toInt(), count - 32)
            writeBits(value.toInt(), 32)
        } else {
            writeBits(value.toInt(), count)
        }
    }

    /** Writes [zeros] zero bits and then a one, which is how unary is coded. */
    fun writeUnary(zeros: Int) {
        require(zeros >= 0) { "zeros must not be negative, was $zeros" }
        var left = zeros
        while (left >= 8) {
            writeBits(0, 8)
            left -= 8
        }
        if (left > 0) writeBits(0, left)
        writeBit(1)
    }

    fun writeBytes(bytes: ByteArray, from: Int = 0, to: Int = bytes.size) {
        require(from >= 0 && to >= from && to <= bytes.size) {
            "range $from until $to is out of bounds for a ${bytes.size}-byte array"
        }
        if (isByteAligned) {
            val count = to - from
            ensure(byteLength.toLong() + count.toLong())
            bytes.copyInto(buffer, byteLength, from, to)
            byteLength += count
        } else {
            for (i in from until to) writeBits(bytes[i].toInt() and 0xFF, 8)
        }
    }

    /** Pads with zeros to the next byte boundary. Returns the bits added. */
    fun alignToByte(): Int {
        if (pendingBits == 0) return 0
        val pad = 8 - pendingBits
        writeBits(0, pad)
        return pad
    }

    /** The bytes written. Only valid when byte aligned. */
    fun toByteArray(): ByteArray {
        check(pendingBits == 0) { "the writer is $pendingBits bits into a byte" }
        return buffer.copyOf(byteLength)
    }

    /**
     * The buffer itself, for a caller that wants to checksum what was written
     * without copying it. Valid up to [byteLength].
     */
    fun rawBuffer(): ByteArray = buffer

    /** Discards everything, keeping the buffer for reuse. */
    fun reset() {
        byteLength = 0
        pending = 0
        pendingBits = 0
    }

    private fun ensure(needed: Long) {
        if (needed <= buffer.size) return
        if (needed > Int.MAX_VALUE) {
            throw AudioKodecException("an encoded bit buffer cannot exceed ${Int.MAX_VALUE} bytes")
        }
        var next = buffer.size.toLong()
        while (next < needed) {
            next = minOf(Int.MAX_VALUE.toLong(), next * 2L)
            if (next == Int.MAX_VALUE.toLong()) break
        }
        if (next < needed) {
            throw AudioKodecException("an encoded bit buffer cannot exceed ${Int.MAX_VALUE} bytes")
        }
        buffer = buffer.copyOf(next.toInt())
    }
}

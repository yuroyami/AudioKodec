package io.github.yuroyami.audiokodec.internal.bits

import io.github.yuroyami.audiokodec.AudioKodecException

/**
 * Writes bits into a growable buffer, least significant bit first.
 *
 * This is the order Vorbis packs its headers and packets in, and the mirror of
 * [BitReaderLsb]: the first bit written lands in bit 0 of the first byte, and
 * a value's low bits go before its high ones.
 */
internal class BitWriterLsb(initialCapacity: Int = 4096) {

    init {
        require(initialCapacity >= 0) {
            "initialCapacity must not be negative, was $initialCapacity"
        }
    }

    private var buffer = ByteArray(if (initialCapacity < 64) 64 else initialCapacity)

    /** Bytes already complete. */
    private var byteLength = 0

    /** Bits waiting in [pending], at most 7 between calls. */
    private var pendingBits = 0
    private var pending = 0L

    /** Bits written so far. */
    val bitLength: Long get() = byteLength.toLong() * 8L + pendingBits

    /** Writes the low [count] bits of [value], 0 to 32 of them. */
    fun writeBits(value: Int, count: Int) {
        require(count in 0..32) { "count must be 0..32, was $count" }
        if (count == 0) return
        val masked = (value.toLong() and 0xFFFF_FFFFL) and ((1L shl count) - 1L)
        pending = pending or (masked shl pendingBits)
        pendingBits += count
        while (pendingBits >= 8) {
            ensure(byteLength.toLong() + 1L)
            buffer[byteLength++] = pending.toByte()
            pending = pending ushr 8
            pendingBits -= 8
        }
    }

    fun writeBit(value: Boolean) {
        writeBits(if (value) 1 else 0, 1)
    }

    /**
     * Writes a codeword whose first bit is the most significant of its
     * [length] bits, which is how Vorbis assigns them: the reader takes the
     * code one bit at a time, so the bit that decides the first branch has to
     * come out of the stream first.
     */
    fun writeCodeword(code: Int, length: Int) {
        require(length in 0..32) { "a codeword is 0..32 bits, not $length" }
        var reversed = 0
        var remaining = code
        for (i in 0 until length) {
            reversed = (reversed shl 1) or (remaining and 1)
            remaining = remaining ushr 1
        }
        writeBits(reversed, length)
    }

    /** The bytes written, with the last partial byte padded with zeros. */
    fun toByteArray(): ByteArray {
        val size = byteLength + if (pendingBits > 0) 1 else 0
        val out = buffer.copyOf(size)
        if (pendingBits > 0) out[byteLength] = pending.toByte()
        return out
    }

    /** Discards everything, keeping the buffer for reuse. */
    fun reset() {
        byteLength = 0
        pending = 0L
        pendingBits = 0
    }

    private fun ensure(needed: Long) {
        if (needed <= buffer.size) return
        if (needed > Int.MAX_VALUE) {
            throw AudioKodecException("an encoded bit buffer cannot exceed ${Int.MAX_VALUE} bytes")
        }
        var next = buffer.size.toLong()
        while (next < needed) next = minOf(Int.MAX_VALUE.toLong(), next * 2L)
        buffer = buffer.copyOf(next.toInt())
    }
}

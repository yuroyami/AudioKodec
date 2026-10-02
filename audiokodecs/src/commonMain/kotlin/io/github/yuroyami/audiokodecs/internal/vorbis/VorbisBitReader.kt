package io.github.yuroyami.audiokodecs.internal.vorbis

import io.github.yuroyami.audiokodecs.CorruptStreamException

/**
 * Reads bits out of a Vorbis packet.
 *
 * Vorbis packs bits least significant first, which [io.github.yuroyami.audiokodecs.internal.bits.BitReaderLsb]
 * already does. What it does not do is the part of the format that matters
 * here: in Vorbis, reading past the end of a packet is not corruption. The
 * format lets an encoder end a packet early once the rest of it would decode to
 * silence, and it defines the condition as "end of packet", to be noticed and
 * acted on rather than thrown.
 *
 * So this reader returns zeroes past the end and raises [endOfPacket] instead.
 * The packet decoder checks it at the points the specification names, and the
 * partial packet is either finished off with silence or dropped.
 */
internal class VorbisBitReader(
    private var data: ByteArray,
    private var length: Int = data.size,
) {

    init {
        checkLength(length, data.size)
    }

    /** Bits consumed, counted from the start of the packet. */
    var bitPosition: Int = 0
        private set

    /** True once a read has run off the end of the packet. */
    var endOfPacket: Boolean = false
        private set

    /** Points the reader at another packet, keeping the object. */
    fun reset(bytes: ByteArray, count: Int) {
        checkLength(count, bytes.size)
        data = bytes
        length = count
        bitPosition = 0
        endOfPacket = false
    }

    /**
     * Refuses a packet whose bit count would not fit in an `Int`.
     *
     * Ogg will reassemble a packet of any size the allocation ceiling allows,
     * and a quarter of a gigabyte of it would make the bit position wrap
     * negative. That would not be unsafe, but it would make the whole packet
     * read as zeroes, which is a silent wrong answer rather than a refusal.
     */
    private fun checkLength(count: Int, bufferSize: Int) {
        if (count < 0 || count > bufferSize) {
            throw CorruptStreamException(
                "a Vorbis packet length of $count is invalid for a $bufferSize-byte buffer",
            )
        }
        if (count > MAX_BYTES) {
            throw CorruptStreamException(
                "a Vorbis packet of $count bytes is larger than the format can address",
            )
        }
    }

    private val bitLength: Int get() = length * 8

    /** Bits left before the end of the packet. */
    val bitsRemaining: Int get() = (bitLength - bitPosition).coerceAtLeast(0)

    fun readBit(): Int {
        if (bitPosition >= bitLength) {
            endOfPacket = true
            return 0
        }
        val byte = data[bitPosition ushr 3].toInt() and 0xFF
        val bit = (byte ushr (bitPosition and 7)) and 1
        bitPosition++
        return bit
    }

    /** Reads [count] bits, 0 to 32, the first bit read being the lowest. */
    fun readBits(count: Int): Int {
        require(count in 0..32) { "count must be 0..32, was $count" }
        if (count == 0) return 0
        if (count > bitsRemaining) {
            // Consume what is there, so the caller's position is honest, and
            // report the shortfall.
            var partial = 0
            var filled = 0
            while (filled < count) {
                partial = partial or (readBit() shl filled)
                filled++
            }
            return partial
        }
        var result = 0
        var filled = 0
        while (filled < count) {
            val byteIndex = bitPosition ushr 3
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

    /**
     * Reads up to [count] bits without consuming them, padding with zeroes past
     * the end of the packet.
     *
     * Looking ahead past the end is normal when a codeword is being matched
     * against a lookup table, so this does not set [endOfPacket]: only actually
     * consuming the bits does.
     */
    fun peekBits(count: Int): Int {
        require(count in 0..32) { "count must be 0..32, was $count" }
        var result = 0
        var filled = 0
        var at = bitPosition
        while (filled < count) {
            if (at >= bitLength) break
            val byteIndex = at ushr 3
            val bitInByte = at and 7
            val available = 8 - bitInByte
            val take = if (count - filled < available) count - filled else available
            val byte = data[byteIndex].toInt() and 0xFF
            val chunk = (byte ushr bitInByte) and ((1 shl take) - 1)
            result = result or (chunk shl filled)
            at += take
            filled += take
        }
        return result
    }

    /** Consumes [count] bits that a peek has already been matched against. */
    fun skipBits(count: Int) {
        require(count >= 0) { "count must not be negative, was $count" }
        if (count > bitsRemaining) {
            endOfPacket = true
            bitPosition = bitLength
            return
        }
        bitPosition += count
    }

    /** Marks the packet as ended, for a caller that found a value it cannot use. */
    fun markEndOfPacket() {
        endOfPacket = true
    }

    private companion object {
        /** Bytes whose bit count still fits in a positive `Int`. */
        const val MAX_BYTES = Int.MAX_VALUE / 8
    }
}

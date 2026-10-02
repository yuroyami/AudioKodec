package io.github.yuroyami.audiokodecs.internal.opus

/**
 * Opus's entropy decoder.
 *
 * Every value in an Opus packet comes through here. It is a range coder rather
 * than a Huffman decoder, which means symbols do not have to occupy a whole
 * number of bits: a symbol that happens three times in four costs about four
 * tenths of a bit, and the saving over rounding each one up to a whole bit is
 * most of why Opus is smaller than the codecs before it.
 *
 * The unusual part is that a packet is read from both ends at once. Values with
 * a probability model are decoded forwards from the front, and values with none
 * are read backwards from the back, so the two meet in the middle and no bits
 * are wasted aligning them. A decoder therefore has two positions, and running
 * out means they crossed.
 *
 * Written from RFC 6716 section 4.1, following its reference implementation
 * where the prose leaves a choice open.
 */
internal class RangeDecoder(
    private val data: ByteArray,
    private val offset: Int = 0,
    private val length: Int = data.size - offset,
) {

    /** How much of the range is left, always above `1 shl 23` after a read. */
    private var range: Int = 128

    /** Where in that range the coded value sits. */
    private var value: Int = 0

    /** The part of the last byte read that has not been folded in yet. */
    private var partial: Int = 0

    /** Bytes consumed from the front. */
    private var forward: Int = 0

    /** Bytes consumed from the back, where the raw bits live. */
    private var backward: Int = 0

    /** Raw bits taken from the back but not yet handed out. */
    private var backwardWindow: Int = 0
    private var backwardBits: Int = 0

    /** Bits notionally consumed, which is what a caller budgets against. */
    private var bitsUsed: Int = 0

    init {
        partial = readForward()
        value = 127 - (partial shr 1)
        bitsUsed = -1
        // The first byte gives seven bits, not eight, because the coder's
        // window is 31 bits wide rather than 32.
        bitsUsed = 1
        normalize()
    }

    /** Bytes the packet holds. */
    val size: Int get() = length

    private fun readForward(): Int {
        if (forward >= length - backward) return 0
        return data[offset + forward++].toInt() and 0xFF
    }

    private fun readBackward(): Int {
        if (backward >= length - forward) return 0
        return data[offset + length - 1 - backward++].toInt() and 0xFF
    }

    /**
     * Pulls bytes in until the range is wide enough to split again.
     *
     * The shift here is not eight. A 32-bit coder carrying a 31-bit window
     * leaves seven bits over, so each byte is folded in one bit off the
     * boundary, and the leftover bit of the previous byte joins it.
     */
    private fun normalize() {
        while (range <= BOTTOM) {
            bitsUsed += 8
            range = range shl 8
            val previous = partial
            partial = readForward()
            val symbol = ((previous shl 8) or partial) ushr (8 - EXTRA)
            value = ((value shl 8) + (0xFF - symbol)) and (TOP - 1)
        }
    }

    /**
     * Reads where in `0 until total` the next symbol falls, without consuming
     * it. The caller looks the answer up in its own table and then says what it
     * found with [update].
     */
    fun decode(total: Int): Int {
        divisor = (range.toLong() and 0xFFFF_FFFFL) / total
        val scaled = (value.toLong() and 0xFFFF_FFFFL) / divisor
        val room = if (scaled + 1 < total) (scaled + 1).toInt() else total
        return total - room
    }

    /** The last [decode]'s range per unit, kept for [update]. */
    private var divisor: Long = 0

    /**
     * Narrows the range to the symbol that was found, which occupied
     * `from until to` out of [total].
     */
    fun update(from: Int, to: Int, total: Int) {
        val unsigned = value.toLong() and 0xFFFF_FFFFL
        val taken = divisor * (total - to)
        value = (unsigned - taken).toInt()
        range = if (from > 0) {
            (divisor * (to - from)).toInt()
        } else {
            ((range.toLong() and 0xFFFF_FFFFL) - taken).toInt()
        }
        normalize()
    }

    /**
     * Decodes one symbol against an inverse cumulative table.
     *
     * The tables Opus uses count down to zero rather than up from it, which
     * lets one array serve as both the boundaries and the terminator, and lets
     * the search stop as soon as it passes the value.
     */
    fun decodeIcdf(table: IntArray, bits: Int): Int {
        val total = 1 shl bits
        val scaled = decode(total)
        var symbol = 0
        var high = total
        var low: Int
        while (true) {
            low = total - table[symbol]
            if (low > scaled) break
            high = low
            symbol++
        }
        // The table is descending, so the symbol's own span is the gap between
        // the entry that passed the value and the one before it.
        val fromValue = if (symbol == 0) 0 else total - table[symbol - 1]
        update(fromValue, total - table[symbol], total)
        if (high == 0) Unit
        return symbol
    }

    /**
     * Decodes one bit whose probability of being zero is `1 - 2^-logp`.
     *
     * Used for the many flags in an Opus packet that are almost always one way,
     * where a whole bit each would be most of a kilobit per second.
     */
    fun decodeBitLogp(logp: Int): Int {
        val unsigned = value.toLong() and 0xFFFF_FFFFL
        val wide = range.toLong() and 0xFFFF_FFFFL
        val threshold = wide ushr logp
        val bit: Int
        if (unsigned < threshold) {
            bit = 1
            range = threshold.toInt()
        } else {
            bit = 0
            value = (unsigned - threshold).toInt()
            range = (wide - threshold).toInt()
        }
        normalize()
        return bit
    }

    /**
     * Reads [count] bits that carry no probability model at all.
     *
     * These come from the back of the packet rather than the front, so that
     * neither end has to be padded to a byte boundary.
     */
    fun decodeRawBits(count: Int): Int {
        var got = 0
        var need = count
        var result = 0
        while (need > 0) {
            if (backwardBits == 0) {
                backwardWindow = readBackward()
                backwardBits = 8
            }
            val take = if (need < backwardBits) need else backwardBits
            val chunk = backwardWindow and ((1 shl take) - 1)
            backwardWindow = backwardWindow ushr take
            backwardBits -= take
            result = result or (chunk shl got)
            got += take
            need -= take
        }
        bitsUsed += count
        return result
    }

    /**
     * Decodes a value uniformly distributed over `0 until total`.
     *
     * Anything wider than eight bits is split: the top eight go through the
     * range coder and the rest are read raw, because modelling them would cost
     * more than it saves.
     */
    fun decodeUniform(total: Int): Int {
        require(total > 1) { "a uniform value needs at least two possibilities, was $total" }
        val bits = widthOf(total - 1)
        if (bits <= UNIFORM_BITS) {
            val scaled = decode(total)
            update(scaled, scaled + 1, total)
            return scaled
        }
        val extra = bits - UNIFORM_BITS
        val top = (total - 1 shr extra) + 1
        val scaled = decode(top)
        update(scaled, scaled + 1, top)
        val result = (scaled shl extra) or decodeRawBits(extra)
        // A value past the end means the packet was corrupt. Clamping keeps the
        // decoder in step rather than indexing past a table with it.
        return if (result < total) result else total - 1
    }

    /**
     * Bits consumed so far, in eighths.
     *
     * Opus budgets bits between its own stages, so a decoder has to know how
     * much of the packet it has spent to finer than a whole bit.
     */
    fun tellFraction(): Int {
        val width = widthOf(range)
        var correction = (bitsUsed - width) shl BITRES
        var wide = (range.toLong() and 0xFFFF_FFFFL) ushr (width - 16)
        // Three rounds of squaring gets three more bits of the logarithm, which
        // is the eighth of a bit this reports to.
        for (i in 0 until BITRES) {
            wide = (wide * wide) ushr 15
            val bit = (wide ushr 16).toInt()
            correction += bit shl (BITRES - 1 - i)
            wide = wide ushr bit
        }
        return correction
    }

    /** Bits consumed so far, rounded up. */
    fun tell(): Int = bitsUsed - widthOf(range) + 1

    private fun widthOf(value: Int): Int {
        var bits = 0
        var v = value
        while (v != 0) {
            v = v ushr 1
            bits++
        }
        return bits
    }

    private companion object {
        /** The coder's window, one bit short of the full word. */
        const val TOP = 1 shl 31

        /** Where the range has to be topped up again. */
        const val BOTTOM = 1 shl 23

        /** Bits of a byte that do not fit the window, which is `(32 - 2) % 8 + 1`. */
        const val EXTRA = (32 - 2) % 8 + 1

        /** Bits of a uniform value worth modelling rather than reading raw. */
        const val UNIFORM_BITS = 8

        /** Bits of resolution the fractional bit count carries. */
        const val BITRES = 3
    }
}

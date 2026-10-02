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
 * are wasted aligning them.
 *
 * Both halves of Opus budget their bits from [tell] and [tellFraction], and the
 * encoder made the same calls on the same state, so this has to agree with the
 * reference to the bit rather than approximately. It is a translation of
 * libopus `celt/entdec.c` and `celt/entcode.c` (see `reference/REFERENCES.md`),
 * with the 32-bit unsigned state carried in `Long`s.
 */
internal class RangeDecoder(
    private val data: ByteArray,
    private val offset: Int = 0,
    length: Int = data.size - offset,
) {
    /** Bytes the decoder may read, which the caller can shrink with [shrinkStorage]. */
    var storage: Int = length
        private set

    private var forward: Int = 0
    private var backward: Int = 0
    private var endWindow: Long = 0
    private var endBits: Int = 0
    private var bitsTotal: Int = CODE_BITS + 1 - ((CODE_BITS - CODE_EXTRA) / SYM_BITS) * SYM_BITS
    private var range: Long = 1L shl CODE_EXTRA
    private var remainder: Int
    private var value: Long
    private var ext: Long = 0

    /** Set when a value decoded out of range, which only a damaged packet causes. */
    var error: Boolean = false
        private set

    init {
        remainder = readByte()
        value = range - 1 - (remainder shr (SYM_BITS - CODE_EXTRA))
        normalize()
    }

    /**
     * The range at this point, which the encoder reports for the same point.
     * Agreeing on it after a packet means every symbol was decoded as coded.
     */
    val finalRange: Long get() = range

    private fun readByte(): Int =
        if (forward < storage) data[offset + forward++].toInt() and 0xFF else 0

    private fun readByteFromEnd(): Int =
        if (backward < storage) data[offset + storage - ++backward].toInt() and 0xFF else 0

    private fun normalize() {
        while (range <= CODE_BOT) {
            bitsTotal += SYM_BITS
            range = (range shl SYM_BITS) and MASK
            var symbol = remainder
            remainder = readByte()
            symbol = ((symbol shl SYM_BITS) or remainder) shr (SYM_BITS - CODE_EXTRA)
            value = ((value shl SYM_BITS) + (SYM_MAX and symbol.inv())) and (CODE_TOP - 1)
        }
    }

    /** `ec_decode`: where in `0 until total` the next symbol falls, without consuming it. */
    fun decode(total: Int): Int {
        ext = range / total
        val s = value / ext
        return (total - minOf(s + 1, total.toLong())).toInt()
    }

    /** `ec_decode_bin`: [decode] for a power-of-two total. */
    fun decodeBin(bits: Int): Int {
        ext = range shr bits
        val s = value / ext
        val total = 1L shl bits
        return (total - minOf(s + 1, total)).toInt()
    }

    /** `ec_dec_update`: consumes the symbol occupying `low until high` of [total]. */
    fun update(low: Int, high: Int, total: Int) {
        val s = (ext * (total - high)) and MASK
        value = (value - s) and MASK
        range = if (low > 0) (ext * (high - low)) and MASK else (range - s) and MASK
        normalize()
    }

    /** `ec_dec_bit_logp`: a bit that is one with probability `2^-logp`. */
    fun decodeBitLogp(logp: Int): Boolean {
        val r = range
        val d = value
        val s = r shr logp
        val bit = d < s
        if (!bit) value = d - s
        range = if (bit) s else r - s
        normalize()
        return bit
    }

    /** `ec_dec_icdf`: a symbol from an inverse cumulative table of `1 shl bits`. */
    fun decodeIcdf(table: IntArray, bits: Int): Int = decodeIcdf(table, 0, bits)

    /** [decodeIcdf] from a table starting at [start] within a larger array. */
    fun decodeIcdf(table: IntArray, start: Int, bits: Int): Int {
        var s = range
        val d = value
        val r = s shr bits
        var symbol = -1
        var t: Long
        do {
            t = s
            s = r * table[start + ++symbol]
        } while (d < s)
        value = d - s
        range = t - s
        normalize()
        return symbol
    }

    /** `ec_dec_uint`: a value uniformly distributed over `0 until total`. */
    fun decodeUniform(total: Long): Long {
        require(total > 1) { "a uniform value needs at least two possibilities, was $total" }
        val top = total - 1
        var bits = ilog(top)
        if (bits > UINT_BITS) {
            bits -= UINT_BITS
            val ft = ((top shr bits) + 1).toInt()
            val s = decode(ft)
            update(s, s + 1, ft)
            val t = (s.toLong() shl bits) or decodeRawBits(bits).toLong()
            if (t <= top) return t
            error = true
            return top
        }
        val s = decode(total.toInt())
        update(s, s + 1, total.toInt())
        return s.toLong()
    }

    /** [decodeUniform] for totals that fit in an `Int`. */
    fun decodeUniform(total: Int): Int = decodeUniform(total.toLong()).toInt()

    /** `ec_dec_bits`: [count] raw bits from the back of the packet, up to 25. */
    fun decodeRawBits(count: Int): Int {
        var window = endWindow
        var available = endBits
        if (available < count) {
            do {
                window = window or (readByteFromEnd().toLong() shl available)
                available += SYM_BITS
            } while (available <= WINDOW_SIZE - SYM_BITS)
        }
        val result = (window and ((1L shl count) - 1)).toInt()
        window = window ushr count
        available -= count
        endWindow = window and MASK
        endBits = available
        bitsTotal += count
        return result
    }

    /** `ec_tell`: whole bits consumed so far, rounded up. */
    fun tell(): Int = bitsTotal - ilog(range)

    /** `ec_tell_frac`: bits consumed so far, in eighths. */
    fun tellFraction(): Int {
        val bits = bitsTotal shl BITRES
        var l = ilog(range)
        val r = range shr (l - 16)
        var b = ((r shr 12) - 8).toInt()
        if (r > TELL_CORRECTION[b]) b++
        l = (l shl 3) + b
        return bits - l
    }

    /**
     * Stops the decoder reading past [bytes], as when a packet's last bytes
     * belong to a redundant frame rather than to this one.
     */
    fun shrinkStorage(bytes: Int) {
        storage -= bytes
    }

    /** Bytes read from the front so far. */
    val bytesRead: Int get() = forward

    /** Bytes the packet holds, before any shrinking. */
    val size: Int = length

    companion object {
        private const val SYM_BITS = 8
        private const val CODE_BITS = 32
        private const val SYM_MAX = (1 shl SYM_BITS) - 1
        private const val CODE_TOP = 1L shl (CODE_BITS - 1)
        private const val CODE_BOT = CODE_TOP ushr SYM_BITS
        private const val CODE_EXTRA = (CODE_BITS - 2) % SYM_BITS + 1
        private const val WINDOW_SIZE = 32
        private const val UINT_BITS = 8
        private const val MASK = 0xFFFF_FFFFL

        /** Bits of resolution [tellFraction] reports to, three being eighths. */
        const val BITRES: Int = 3

        private val TELL_CORRECTION = longArrayOf(35733, 38967, 42495, 46340, 50535, 55109, 60097, 65535)

        /** `EC_ILOG`: one more than the index of the highest set bit, and 0 for 0. */
        fun ilog(value: Long): Int = 64 - (value and MASK).countLeadingZeroBits()
    }
}

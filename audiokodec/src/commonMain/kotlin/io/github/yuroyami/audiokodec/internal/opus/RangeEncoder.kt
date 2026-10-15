package io.github.yuroyami.audiokodec.internal.opus

/**
 * Opus's entropy encoder, the exact inverse of [RangeDecoder].
 *
 * Symbols with a probability model are coded forwards from the front of the
 * packet and raw bits backwards from its end, and [done] joins the two in the
 * middle. Every call here has a twin in [RangeDecoder] that reads the same
 * value back, and both ends budget their bits from [tell] and [tellFraction]
 * on the same state, which is why the arithmetic is the reference's to the
 * bit.
 *
 * A translation of libopus `celt/entenc.c` and `celt/entcode.c` (see
 * `reference/REFERENCES.md`), with the 32-bit unsigned state carried in
 * `Long`s. The encoder writes into a caller's buffer of [storage] bytes,
 * which [shrink] can reduce once the encoder knows the packet will be
 * shorter.
 *
 * The CELT encoder tries some decisions both ways and keeps the better one.
 * [saveTo] and [restoreFrom] copy the coder's state for that, as the
 * reference copies its `ec_enc` structure; the bytes already written are
 * the caller's to copy, through [buffer], exactly as there.
 */
internal class RangeEncoder(
    /** The buffer the packet is written into, from index 0. */
    val buffer: ByteArray,
    size: Int = buffer.size,
) {
    /** Bytes the packet may occupy. */
    var storage: Int = size
        private set

    /** Bytes written from the front so far: `ec_range_bytes`. */
    var offset: Int = 0
        private set

    private var endOffset: Int = 0
    private var endWindow: Long = 0
    private var endBits: Int = 0
    private var bitsTotal: Int = CODE_BITS + 1
    private var range: Long = CODE_TOP
    private var value: Long = 0
    private var remainder: Int = -1
    private var extra: Int = 0

    /** Set when the packet overflowed its storage, which makes it undecodable. */
    var error: Boolean = false
        private set

    /** The range after the last symbol, which the decoder reports at the same point. */
    val finalRange: Long get() = range

    init {
        require(size in 0..buffer.size) { "a $size-byte packet does not fit a ${buffer.size}-byte buffer" }
    }

    private fun writeByte(byte: Int) {
        if (offset + endOffset >= storage) {
            error = true
            return
        }
        buffer[offset++] = byte.toByte()
    }

    private fun writeByteAtEnd(byte: Int) {
        if (offset + endOffset >= storage) {
            error = true
            return
        }
        buffer[storage - ++endOffset] = byte.toByte()
    }

    /**
     * `ec_enc_carry_out`: outputs a byte, holding back a run of 0xFF bytes
     * until it is known whether a carry will ripple into them.
     */
    private fun carryOut(c: Int) {
        if (c != SYM_MAX) {
            val carry = c shr SYM_BITS
            if (remainder >= 0) writeByte(remainder + carry)
            if (extra > 0) {
                val symbol = (SYM_MAX + carry) and SYM_MAX
                do writeByte(symbol) while (--extra > 0)
            }
            remainder = c and SYM_MAX
        } else {
            extra++
        }
    }

    private fun normalize() {
        while (range <= CODE_BOT) {
            carryOut((value ushr CODE_SHIFT).toInt())
            value = (value shl SYM_BITS) and (CODE_TOP - 1)
            range = (range shl SYM_BITS) and MASK
            bitsTotal += SYM_BITS
        }
    }

    /** `ec_encode`: codes the symbol occupying `low until high` of [total]. */
    fun encode(low: Int, high: Int, total: Int) {
        val r = range / total
        if (low > 0) {
            value = (value + range - r * (total - low)) and MASK
            range = (r * (high - low)) and MASK
        } else {
            range = (range - r * (total - high)) and MASK
        }
        normalize()
    }

    /** `ec_encode_bin`: [encode] for a power-of-two total of `1 shl bits`. */
    fun encodeBin(low: Int, high: Int, bits: Int) {
        val r = range shr bits
        if (low > 0) {
            value = (value + range - r * ((1L shl bits) - low)) and MASK
            range = (r * (high - low)) and MASK
        } else {
            range = (range - r * ((1L shl bits) - high)) and MASK
        }
        normalize()
    }

    /** `ec_enc_bit_logp`: a bit that is one with probability `2^-logp`. */
    fun encodeBitLogp(bit: Boolean, logp: Int) {
        val r = range
        val l = value
        val s = r shr logp
        val rest = r - s
        if (bit) value = (l + rest) and MASK
        range = if (bit) s else rest
        normalize()
    }

    /** `ec_enc_icdf`: [symbol] from an inverse cumulative table of `1 shl bits`. */
    fun encodeIcdf(symbol: Int, table: IntArray, bits: Int) {
        val r = range shr bits
        if (symbol > 0) {
            value = (value + range - r * table[symbol - 1]) and MASK
            range = (r * (table[symbol - 1] - table[symbol])) and MASK
        } else {
            range = (range - r * table[symbol]) and MASK
        }
        normalize()
    }

    /** `ec_enc_uint`: [symbol] uniformly distributed over `0 until total`. */
    fun encodeUniform(symbol: Long, total: Long) {
        require(total > 1) { "a uniform value needs at least two possibilities, was $total" }
        val top = total - 1
        var bits = RangeDecoder.ilog(top)
        if (bits > UINT_BITS) {
            bits -= UINT_BITS
            val ft = ((top ushr bits) + 1).toInt()
            val fl = (symbol ushr bits).toInt()
            encode(fl, fl + 1, ft)
            encodeRawBits((symbol and ((1L shl bits) - 1)).toInt(), bits)
        } else {
            encode(symbol.toInt(), symbol.toInt() + 1, total.toInt())
        }
    }

    /** [encodeUniform] for totals that fit in an `Int`. */
    fun encodeUniform(symbol: Int, total: Int) = encodeUniform(symbol.toLong(), total.toLong())

    /** `ec_enc_bits`: [count] raw bits of [bits], written from the back of the packet, up to 25. */
    fun encodeRawBits(bits: Int, count: Int) {
        var window = endWindow
        var used = endBits
        if (used + count > WINDOW_SIZE) {
            do {
                writeByteAtEnd((window and SYM_MAX.toLong()).toInt())
                window = window ushr SYM_BITS
                used -= SYM_BITS
            } while (used >= SYM_BITS)
        }
        window = (window or ((bits.toLong() and MASK) shl used)) and MASK
        used += count
        endWindow = window
        endBits = used
        bitsTotal += count
    }

    /** `ec_tell`: whole bits written so far, rounded up. */
    fun tell(): Int = bitsTotal - RangeDecoder.ilog(range)

    /** `ec_tell_frac`: bits written so far, in eighths. */
    fun tellFraction(): Int {
        val bits = bitsTotal shl RangeDecoder.BITRES
        var l = RangeDecoder.ilog(range)
        val r = range shr (l - 16)
        var b = ((r shr 12) - 8).toInt()
        if (r > TELL_CORRECTION[b]) b++
        l = (l shl 3) + b
        return bits - l
    }

    /**
     * Counts [bits] as written without writing them, which CELT does for a
     * silent frame: the decoder treats the rest of the packet as read.
     */
    fun addBits(bits: Int) {
        bitsTotal += bits
    }

    /** `ec_enc_shrink`: makes the packet [size] bytes, moving the raw bits already written to its new end. */
    fun shrink(size: Int) {
        check(offset + endOffset <= size) { "cannot shrink a packet below what is already written" }
        buffer.copyInto(buffer, size - endOffset, storage - endOffset, storage)
        storage = size
    }

    /**
     * `ec_enc_done`: writes out what is still held and fills the space between
     * the two ends, after which the packet is [storage] bytes long.
     */
    fun done() {
        var l = CODE_BITS - RangeDecoder.ilog(range)
        var mask = (CODE_TOP - 1) ushr l
        // The sums wrap at 32 bits, as the reference's unsigned ones do.
        var end = ((value + mask) and MASK) and mask.inv()
        if ((end or mask) >= ((value + range) and MASK)) {
            l++
            mask = mask ushr 1
            end = ((value + mask) and MASK) and mask.inv()
        }
        while (l > 0) {
            carryOut((end ushr CODE_SHIFT).toInt())
            end = (end shl SYM_BITS) and (CODE_TOP - 1)
            l -= SYM_BITS
        }
        if (remainder >= 0 || extra > 0) carryOut(0)
        var window = endWindow
        var used = endBits
        while (used >= SYM_BITS) {
            writeByteAtEnd((window and SYM_MAX.toLong()).toInt())
            window = window ushr SYM_BITS
            used -= SYM_BITS
        }
        if (!error) {
            buffer.fill(0, offset, storage - endOffset)
            if (used > 0) {
                if (endOffset >= storage) {
                    error = true
                } else {
                    l = -l
                    if (offset + endOffset >= storage && l < used) {
                        window = window and ((1L shl l) - 1)
                        error = true
                    }
                    val at = storage - endOffset - 1
                    buffer[at] = (buffer[at].toInt() or window.toInt()).toByte()
                }
            }
        }
    }

    /** A copy of everything but the buffer, for [saveTo] and [restoreFrom]. */
    class Snapshot {
        internal var storage = 0
        internal var offset = 0
        internal var endOffset = 0
        internal var endWindow = 0L
        internal var endBits = 0
        internal var bitsTotal = 0
        internal var range = 0L
        internal var value = 0L
        internal var remainder = 0
        internal var extra = 0
        internal var error = false

        /** `ec_range_bytes` at the time of the copy. */
        val rangeBytes: Int get() = offset

        /** Storage at the time of the copy. */
        val storageBytes: Int get() = storage
    }

    /** Copies the coder's state, as `ec_save = *ec` does. */
    fun saveTo(snapshot: Snapshot) {
        snapshot.storage = storage
        snapshot.offset = offset
        snapshot.endOffset = endOffset
        snapshot.endWindow = endWindow
        snapshot.endBits = endBits
        snapshot.bitsTotal = bitsTotal
        snapshot.range = range
        snapshot.value = value
        snapshot.remainder = remainder
        snapshot.extra = extra
        snapshot.error = error
    }

    /** Returns to a state [saveTo] copied, as `*ec = ec_save` does. */
    fun restoreFrom(snapshot: Snapshot) {
        storage = snapshot.storage
        offset = snapshot.offset
        endOffset = snapshot.endOffset
        endWindow = snapshot.endWindow
        endBits = snapshot.endBits
        bitsTotal = snapshot.bitsTotal
        range = snapshot.range
        value = snapshot.value
        remainder = snapshot.remainder
        extra = snapshot.extra
        error = snapshot.error
    }

    private companion object {
        const val SYM_BITS = 8
        const val CODE_BITS = 32
        const val SYM_MAX = (1 shl SYM_BITS) - 1
        const val CODE_SHIFT = CODE_BITS - SYM_BITS - 1
        const val CODE_TOP = 1L shl (CODE_BITS - 1)
        const val CODE_BOT = CODE_TOP ushr SYM_BITS
        const val WINDOW_SIZE = 32
        const val UINT_BITS = 8
        const val MASK = 0xFFFF_FFFFL

        val TELL_CORRECTION = longArrayOf(35733, 38967, 42495, 46340, 50535, 55109, 60097, 65535)
    }
}

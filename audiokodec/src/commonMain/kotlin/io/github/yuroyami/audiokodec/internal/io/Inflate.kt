package io.github.yuroyami.audiokodec.internal.io

import io.github.yuroyami.audiokodec.CorruptStreamException

/**
 * Inflates zlib data, RFC 1950 around RFC 1951's DEFLATE, written from the
 * two RFCs.
 *
 * DEFLATE is a sequence of blocks, each stored, or coded with the fixed
 * Huffman codes of section 3.2.6, or with codes the block describes itself
 * (section 3.2.7). The codes are canonical, so a table of code lengths is all
 * a block needs to send, and decoding walks the codes length by length
 * (section 3.2.2). The length and distance bases of section 3.2.5 are
 * computed from the rule the RFC states for them.
 */
internal object Inflate {

    /**
     * Inflates the zlib stream in [input] from [offset] for [length] bytes,
     * refusing output past [limit] bytes, and checks its Adler-32.
     */
    fun zlib(input: ByteArray, offset: Int, length: Int, limit: Int): ByteArray {
        if (length < 6) throw CorruptStreamException("a zlib stream of $length bytes is too short")
        val cmf = input[offset].toInt() and 0xFF
        val flg = input[offset + 1].toInt() and 0xFF
        if (cmf and 0x0F != 8 || (cmf ushr 4) > 7) throw CorruptStreamException("a zlib stream that is not DEFLATE with a window of 32 KiB or less")
        if ((cmf * 256 + flg) % 31 != 0) throw CorruptStreamException("a zlib header whose check bits are wrong")
        if (flg and 0x20 != 0) throw CorruptStreamException("a zlib stream that needs a preset dictionary")
        val out = Output(limit)
        val bits = Bits(input, offset + 2, offset + length - 4)
        deflate(bits, out)
        val at = offset + length - 4
        val stated = ((input[at].toLong() and 0xFF) shl 24) or ((input[at + 1].toLong() and 0xFF) shl 16) or
            ((input[at + 2].toLong() and 0xFF) shl 8) or (input[at + 3].toLong() and 0xFF)
        if (adler32(out.bytes, out.size) != stated) throw CorruptStreamException("a zlib stream whose Adler-32 is wrong")
        return out.bytes.copyOf(out.size)
    }

    /** Inflates raw DEFLATE data, with no zlib wrapper, refusing output past [limit] bytes. */
    fun raw(input: ByteArray, offset: Int, length: Int, limit: Int): ByteArray {
        val out = Output(limit)
        deflate(Bits(input, offset, offset + length), out)
        return out.bytes.copyOf(out.size)
    }

    private fun deflate(bits: Bits, out: Output) {
        do {
            val last = bits.read(1) == 1
            when (bits.read(2)) {
                0 -> stored(bits, out)
                1 -> codes(bits, out, FIXED_LITERALS, FIXED_DISTANCES)
                2 -> {
                    val (literals, distances) = dynamic(bits)
                    codes(bits, out, literals, distances)
                }
                else -> throw CorruptStreamException("a DEFLATE block of the reserved type 3")
            }
        } while (!last)
    }

    private fun stored(bits: Bits, out: Output) {
        bits.alignToByte()
        val len = bits.read(16)
        val nlen = bits.read(16)
        if (len != nlen.inv() and 0xFFFF) throw CorruptStreamException("a stored DEFLATE block whose length check is wrong")
        for (i in 0 until len) out.put(bits.read(8))
    }

    private fun codes(bits: Bits, out: Output, literals: Huffman, distances: Huffman) {
        while (true) {
            val symbol = literals.decode(bits)
            when {
                symbol < 256 -> out.put(symbol)
                symbol == 256 -> return
                symbol <= 285 -> {
                    val index = symbol - 257
                    val length = LENGTH_BASE[index] + bits.read(LENGTH_EXTRA[index])
                    val code = distances.decode(bits)
                    if (code >= 30) throw CorruptStreamException("a DEFLATE distance code of $code")
                    val distance = DISTANCE_BASE[code] + bits.read(DISTANCE_EXTRA[code])
                    out.copy(distance, length)
                }
                else -> throw CorruptStreamException("a DEFLATE length code of $symbol")
            }
        }
    }

    /** Reads a block's own code lengths, section 3.2.7. */
    private fun dynamic(bits: Bits): Pair<Huffman, Huffman> {
        val hlit = bits.read(5) + 257
        val hdist = bits.read(5) + 1
        val hclen = bits.read(4) + 4
        if (hlit > 286 || hdist > 30) throw CorruptStreamException("a DEFLATE block with $hlit literal and $hdist distance codes")
        val codeLengths = IntArray(19)
        for (i in 0 until hclen) codeLengths[CODE_LENGTH_ORDER[i]] = bits.read(3)
        val lengthCode = Huffman(codeLengths)
        val lengths = IntArray(hlit + hdist)
        var i = 0
        while (i < lengths.size) {
            when (val symbol = lengthCode.decode(bits)) {
                in 0..15 -> lengths[i++] = symbol
                16 -> {
                    if (i == 0) throw CorruptStreamException("a DEFLATE code length repeat with nothing to repeat")
                    val previous = lengths[i - 1]
                    repeat(3 + bits.read(2)) { put(lengths, i++, previous) }
                }
                17 -> repeat(3 + bits.read(3)) { put(lengths, i++, 0) }
                else -> repeat(11 + bits.read(7)) { put(lengths, i++, 0) }
            }
        }
        if (lengths[256] == 0) throw CorruptStreamException("a DEFLATE block with no end-of-block code")
        return Huffman(lengths.copyOfRange(0, hlit)) to Huffman(lengths.copyOfRange(hlit, lengths.size))
    }

    private fun put(lengths: IntArray, at: Int, value: Int) {
        if (at >= lengths.size) throw CorruptStreamException("DEFLATE code lengths repeat past their end")
        lengths[at] = value
    }

    /** A canonical Huffman code from its code lengths, decoded length by length, section 3.2.2. */
    private class Huffman(lengths: IntArray) {
        private val counts = IntArray(16)
        private val symbols: IntArray

        init {
            for (l in lengths) counts[l]++
            counts[0] = 0
            var left = 1
            for (l in 1..15) {
                left = (left shl 1) - counts[l]
                if (left < 0) throw CorruptStreamException("a DEFLATE code with too many codes of one length")
            }
            val offsets = IntArray(16)
            for (l in 1 until 15) offsets[l + 1] = offsets[l] + counts[l]
            symbols = IntArray(lengths.size)
            for (s in lengths.indices) if (lengths[s] != 0) symbols[offsets[lengths[s]]++] = s
        }

        fun decode(bits: Bits): Int {
            var code = 0
            var first = 0
            var index = 0
            for (l in 1..15) {
                code = code or bits.read(1)
                val count = counts[l]
                if (code - first < count) return symbols[index + code - first]
                index += count
                first = (first + count) shl 1
                code = code shl 1
            }
            throw CorruptStreamException("a DEFLATE code that is not in its table")
        }
    }

    /** Reads bits from the least significant end of each byte, as section 3.1.1 packs them. */
    private class Bits(private val data: ByteArray, private var at: Int, private val end: Int) {
        private var buffer = 0
        private var count = 0

        fun read(n: Int): Int {
            while (count < n) {
                if (at >= end) throw CorruptStreamException("a DEFLATE stream that ends early")
                buffer = buffer or ((data[at++].toInt() and 0xFF) shl count)
                count += 8
            }
            val value = buffer and ((1 shl n) - 1)
            buffer = buffer ushr n
            count -= n
            return value
        }

        fun alignToByte() {
            buffer = 0
            count = 0
        }
    }

    private class Output(private val limit: Int) {
        var bytes = ByteArray(minOf(limit, 4_096).coerceAtLeast(1))
        var size = 0

        fun put(b: Int) {
            if (size == bytes.size) grow()
            bytes[size++] = b.toByte()
        }

        fun copy(distance: Int, length: Int) {
            if (distance > size) throw CorruptStreamException("a DEFLATE distance of $distance reaches before the start of the data")
            for (i in 0 until length) put(bytes[size - distance].toInt())
        }

        private fun grow() {
            if (size >= limit) throw CorruptStreamException("inflated data larger than the $limit bytes allowed")
            bytes = bytes.copyOf(minOf(limit.toLong(), bytes.size * 2L).toInt())
        }
    }

    private fun adler32(bytes: ByteArray, length: Int): Long {
        var a = 1L
        var b = 0L
        for (i in 0 until length) {
            a = (a + (bytes[i].toInt() and 0xFF)) % 65521
            b = (b + a) % 65521
        }
        return (b shl 16) or a
    }

    private val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    // Section 3.2.5: lengths 3 to 10 take no extra bits, then each further
    // four codes take one bit more, and code 285 is 258 exactly; distances 1
    // to 4 take none, then each further two codes take one bit more.
    private val LENGTH_EXTRA = IntArray(29) { if (it < 8 || it == 28) 0 else (it - 4) / 4 }
    private val LENGTH_BASE = IntArray(29).also { base ->
        var next = 3
        for (i in 0 until 28) {
            base[i] = next
            next += 1 shl LENGTH_EXTRA[i]
        }
        base[28] = 258
    }
    private val DISTANCE_EXTRA = IntArray(30) { if (it < 4) 0 else (it - 2) / 2 }
    private val DISTANCE_BASE = IntArray(30).also { base ->
        var next = 1
        for (i in 0 until 30) {
            base[i] = next
            next += 1 shl DISTANCE_EXTRA[i]
        }
    }

    // Section 3.2.6.
    private val FIXED_LITERALS = Huffman(IntArray(288) { if (it < 144) 8 else if (it < 256) 9 else if (it < 280) 7 else 8 })
    private val FIXED_DISTANCES = Huffman(IntArray(30) { 5 })
}

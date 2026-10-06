package io.github.yuroyami.audiokodec.internal.mpeg

import io.github.yuroyami.audiokodec.internal.bits.BitReaderMsb

/**
 * Decodes Layer III's Huffman coded spectrum.
 *
 * The format has thirty-four code tables, and a granule names three of them for
 * its three frequency regions plus one more for the run of small values above
 * them. They are ordinary prefix codes, given in the standard as a codeword and
 * a length for every value pair, so decoding is a walk from the root of the
 * implied tree.
 *
 * Two things make it less simple than that. The tables for the highest
 * frequencies code only the values 0 to 14 directly and spell anything larger
 * as 15 followed by a fixed number of extra bits, which is what lets one table
 * cover a range that would otherwise need thousands of entries. And a granule
 * declares how many bits it occupies, so decoding has to stop at that count
 * rather than at a codeword boundary: an encoder is allowed to leave the last
 * values out, and a decoder that keeps reading eats the next granule's data.
 * Enforcing that bound is the caller's job, because what to do about an overrun
 * differs between the two regions.
 *
 * The tables themselves are in [Mp3HuffmanTables], which records how they were
 * verified. This file only turns them into something that can be read from.
 */
internal object Mp3Huffman {

    /**
     * Decodes one pair of values into the first two entries of [out].
     *
     * Returns false when the data ran out partway through, which leaves [out]
     * meaningless and ends the granule.
     */
    fun decodePair(reader: BitReaderMsb, table: Int, out: IntArray): Boolean {
        if (table == 0) {
            // Table 0 codes "both values are zero" and consumes no bits.
            out[0] = 0
            out[1] = 0
            return true
        }
        if (table >= TREES.size) return false
        val tree = TREES[table] ?: return false

        val symbol = decodeSymbol(reader, tree, table)
        if (symbol < 0) return false

        // Every big value table is square, so one dimension gives both.
        val width = Mp3HuffmanTables.XLEN[table]
        var x = symbol / width
        var y = symbol % width

        val linbits = Mp3HuffmanTables.LINBITS[table]
        if (linbits > 0 && x == 15) {
            val extra = takeBits(reader, linbits)
            if (extra < 0) return false
            x += extra
        }
        if (x != 0) {
            val sign = takeBits(reader, 1)
            if (sign < 0) return false
            if (sign != 0) x = -x
        }
        if (linbits > 0 && y == 15) {
            val extra = takeBits(reader, linbits)
            if (extra < 0) return false
            y += extra
        }
        if (y != 0) {
            val sign = takeBits(reader, 1)
            if (sign < 0) return false
            if (sign != 0) y = -y
        }

        out[0] = x
        out[1] = y
        return true
    }

    /**
     * Decodes one quadruple of values into the first four entries of [out].
     *
     * Above the big value region every value is -1, 0 or 1, so the format codes
     * four at a time with one of two small tables. The second of them is barely
     * a code at all: sixteen four-bit patterns, one per combination.
     */
    fun decodeQuad(reader: BitReaderMsb, table: Int, out: IntArray): Boolean {
        val id = if (table == 0) COUNT1_A else COUNT1_B
        val tree = TREES[id] ?: return false
        val symbol = decodeSymbol(reader, tree, id)
        if (symbol < 0) return false

        // The four values are the low four bits of the index, most significant
        // first, and each nonzero one is followed by its sign.
        for (i in 0 until 4) {
            var value = (symbol ushr (3 - i)) and 1
            if (value != 0) {
                val sign = takeBits(reader, 1)
                if (sign < 0) return false
                if (sign != 0) value = -value
            }
            out[i] = value
        }
        return true
    }

    /**
     * Walks one codeword and returns the index it names, or -1 when the data
     * ran out first.
     *
     * The first [FAST_BITS] bits are resolved by a lookup rather than a walk,
     * which covers most codewords in one step. The lookup is built from the
     * tree, so the two cannot disagree.
     */
    private fun decodeSymbol(reader: BitReaderMsb, tree: IntArray, table: Int): Int {
        val fast = FAST[table] ?: return -1
        val remaining = reader.bitsRemaining
        if (remaining <= 0) return -1

        val take = if (remaining < FAST_BITS) remaining else FAST_BITS
        val window = reader.peekBits(take) shl (FAST_BITS - take)
        val entry = fast[window]
        if (entry == INVALID) return -1

        if (entry > 0) {
            val length = entry and 0x1F
            // A codeword that only the zero padding completed is not one the
            // data holds.
            if (length > take) return -1
            reader.skipBits(length)
            return entry ushr 5
        }

        // Longer than the lookup covers, so continue the walk from where it
        // left off. That needs all of the bits the lookup consumed.
        if (take < FAST_BITS) return -1
        reader.skipBits(FAST_BITS)
        var node = -entry
        while (true) {
            if (reader.bitsRemaining <= 0) return -1
            val bit = reader.readBit()
            val next = tree[2 * node + bit]
            if (next == EMPTY) return -1
            if (next < 0) return -next - 1
            node = next
        }
    }

    /** Reads [count] bits, or returns -1 when that many are not there. */
    private fun takeBits(reader: BitReaderMsb, count: Int): Int {
        if (reader.bitsRemaining < count) return -1
        return reader.readBits(count)
    }

    /** How many bits the lookup resolves in one step. */
    private const val FAST_BITS = 8

    /** No branch here, which a complete code never has. */
    private const val EMPTY = Int.MIN_VALUE

    /**
     * No codeword here. A real entry is either a positive leaf, whose low five
     * bits are a length of at least one, or a negative node, so zero is free to
     * mean neither.
     */
    private const val INVALID = 0

    private const val COUNT1_A = 32
    private const val COUNT1_B = 33

    /**
     * One decode tree per table, as pairs of child slots.
     *
     * A slot holds [EMPTY] for no branch, a negative value for a leaf naming
     * index `-value - 1`, and a positive value for the next node. The root is
     * node 0, and nothing points back at it.
     */
    private val TREES: Array<IntArray?> = Array(34) { buildTree(it) }

    /** The first [FAST_BITS] bits of every codeword, resolved ahead of time. */
    private val FAST: Array<IntArray?> = Array(34) { buildFast(TREES[it]) }

    private fun buildTree(table: Int): IntArray? {
        val lengths = Mp3HuffmanTables.TABLE_LENGTHS[table]
        val codes = Mp3HuffmanTables.TABLE_CODES[table]
        if (lengths.isEmpty()) return null

        // Every entry adds at most its own length in nodes, which bounds the
        // tree without having to count it first.
        var slots = 1
        for (length in lengths) if (length > 0) slots += length
        val tree = IntArray(2 * slots) { EMPTY }
        var used = 1

        for (index in lengths.indices) {
            val length = lengths[index]
            if (length <= 0) continue
            val code = codes[index]
            var node = 0
            for (depth in length - 1 downTo 0) {
                val bit = (code ushr depth) and 1
                val slot = 2 * node + bit
                if (depth == 0) {
                    tree[slot] = -index - 1
                } else {
                    var next = tree[slot]
                    if (next == EMPTY) {
                        next = used++
                        tree[slot] = next
                    }
                    node = next
                }
            }
        }
        return tree
    }

    private fun buildFast(tree: IntArray?): IntArray? {
        if (tree == null) return null
        val fast = IntArray(1 shl FAST_BITS)
        for (prefix in fast.indices) {
            var node = 0
            var depth = 0
            var entry = INVALID
            while (depth < FAST_BITS) {
                val bit = (prefix ushr (FAST_BITS - 1 - depth)) and 1
                val next = tree[2 * node + bit]
                depth++
                if (next == EMPTY) {
                    // Unreachable for a complete code, and harmless if one ever
                    // were not: the caller is told the codeword did not decode.
                    entry = INVALID
                    break
                }
                if (next < 0) {
                    entry = ((-next - 1) shl 5) or depth
                    break
                }
                node = next
                entry = -node
            }
            fast[prefix] = entry
        }
        return fast
    }
}

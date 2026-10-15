package io.github.yuroyami.audiokodec.internal.mpeg.encoder

import io.github.yuroyami.audiokodec.internal.bits.BitWriterMsb
import io.github.yuroyami.audiokodec.internal.mpeg.Mp3HuffmanTables

/**
 * The encoding direction of Layer III's Huffman tables.
 *
 * The codes are the decoder's own arrays in [Mp3HuffmanTables], read the
 * other way round: the decoder builds a tree from each codeword and length,
 * and this looks the codeword up by the pair it stands for. Nothing is typed
 * a second time, so the two directions cannot drift apart.
 *
 * Tables 16 to 23 share one code and differ only in how many extra bits
 * follow a 15, and so do 24 to 31. Which table of each family a region uses is
 * therefore just the smallest escape width that holds its largest value.
 */
internal object Mp3HuffmanEncoder {

    /** The largest value Layer III can code: 15 plus a 13-bit escape. */
    const val MAX_VALUE: Int = 15 + 8191

    private val LENGTHS = Mp3HuffmanTables.TABLE_LENGTHS
    private val CODES = Mp3HuffmanTables.TABLE_CODES
    private val XLEN = Mp3HuffmanTables.XLEN
    private val LINBITS = Mp3HuffmanTables.LINBITS

    /**
     * The tables worth trying for a region whose largest value is at most
     * the index, grouped by the size of table that holds it: there is no
     * point trying a table that cannot code the value, and a larger table of
     * the same family is never cheaper for small values in practice.
     */
    private val CANDIDATES: Array<IntArray> = arrayOf(
        intArrayOf(0),
        intArrayOf(1),
        intArrayOf(2, 3),
        intArrayOf(5, 6),
        intArrayOf(7, 8, 9),
        intArrayOf(7, 8, 9),
        intArrayOf(10, 11, 12),
        intArrayOf(10, 11, 12),
    )

    private val FIFTEEN = intArrayOf(13, 15, 16, 24)

    /** Bits a pair costs in a table without escapes, signs included, indexed by table then `x * xlen + y`. */
    private val PAIR_BITS: Array<IntArray> = Array(16) { table ->
        val xlen = XLEN[table]
        IntArray(xlen * xlen) { index ->
            val x = index / xlen
            val y = index % xlen
            LENGTHS[table][index] + (if (x != 0) 1 else 0) + (if (y != 0) 1 else 0)
        }
    }

    /** The same for the two escape families' shared codes, before the escape bits. */
    private val ESCAPE_BITS: Array<IntArray> = Array(2) { family ->
        val table = if (family == 0) 16 else 24
        IntArray(256) { index ->
            val x = index / 16
            val y = index % 16
            LENGTHS[table][index] + (if (x != 0) 1 else 0) + (if (y != 0) 1 else 0)
        }
    }

    /** The table with the fewest escape bits that holds [max], in each family. */
    private fun escapeTable(family: Int, max: Int): Int {
        val first = if (family == 0) 16 else 24
        for (table in first until first + 8) {
            if (max - 15 < (1 shl LINBITS[table])) return table
        }
        return -1
    }

    /** Bits [table] costs for the pairs of `values[from until to]`. */
    fun pairBits(values: IntArray, from: Int, to: Int, table: Int): Int {
        if (table == 0 || from >= to) return 0
        var bits = 0
        if (table < 16) {
            val xlen = XLEN[table]
            val costs = PAIR_BITS[table]
            var i = from
            while (i < to) {
                bits += costs[abs(values[i]) * xlen + abs(values[i + 1])]
                i += 2
            }
            return bits
        }
        val costs = ESCAPE_BITS[if (table < 24) 0 else 1]
        val linbits = LINBITS[table]
        var i = from
        while (i < to) {
            var x = abs(values[i])
            var y = abs(values[i + 1])
            if (x >= 15) {
                x = 15
                bits += linbits
            }
            if (y >= 15) {
                y = 15
                bits += linbits
            }
            bits += costs[x * 16 + y]
            i += 2
        }
        return bits
    }

    /** Largest magnitude among `values[from until to]`. */
    fun maxOf(values: IntArray, from: Int, to: Int): Int {
        var max = 0
        for (i in from until to) {
            val v = abs(values[i])
            if (v > max) max = v
        }
        return max
    }

    /**
     * Picks the cheapest table for `values[from until to]` and returns it
     * packed as `bits shl 6 or table`, so the caller gets both from one call.
     */
    fun bestTable(values: IntArray, from: Int, to: Int): Int {
        if (from >= to) return 0
        val max = maxOf(values, from, to)
        if (max == 0) return 0
        var bestBits = Int.MAX_VALUE
        var best = 0
        val candidates = when {
            max < CANDIDATES.size -> CANDIDATES[max]
            max <= 15 -> FIFTEEN
            else -> null
        }
        if (candidates != null) {
            for (table in candidates) {
                val bits = pairBits(values, from, to, table)
                if (bits < bestBits) {
                    bestBits = bits
                    best = table
                }
            }
        } else {
            for (family in 0 until 2) {
                val table = escapeTable(family, max)
                if (table < 0) continue
                val bits = pairBits(values, from, to, table)
                if (bits < bestBits) {
                    bestBits = bits
                    best = table
                }
            }
        }
        return (bestBits shl 6) or best
    }

    /** Bits the quadruples of `values[from until to]` cost in count1 table [table], 0 or 1. */
    fun quadBits(values: IntArray, from: Int, to: Int, table: Int): Int {
        var bits = 0
        var i = from
        while (i < to) {
            val v = abs(values[i])
            val w = abs(values[i + 1])
            val x = abs(values[i + 2])
            val y = abs(values[i + 3])
            val signs = v + w + x + y
            bits += if (table == 0) LENGTHS[32][v * 8 + w * 4 + x * 2 + y] + signs else 4 + signs
            i += 4
        }
        return bits
    }

    /** Writes the pairs of `values[from until to]` with [table]. */
    fun writePairs(writer: BitWriterMsb, values: IntArray, from: Int, to: Int, table: Int) {
        if (table == 0) return
        val linbits = LINBITS[table]
        val xlen = XLEN[table]
        var i = from
        while (i < to) {
            val x = values[i]
            val y = values[i + 1]
            var ax = abs(x)
            var ay = abs(y)
            var escapeX = 0
            var escapeY = 0
            if (linbits > 0) {
                if (ax >= 15) {
                    escapeX = ax - 15
                    ax = 15
                }
                if (ay >= 15) {
                    escapeY = ay - 15
                    ay = 15
                }
            }
            val index = ax * xlen + ay
            writer.writeBits(CODES[table][index], LENGTHS[table][index])
            if (linbits > 0 && ax == 15) writer.writeBits(escapeX, linbits)
            if (x != 0) writer.writeBit(if (x < 0) 1 else 0)
            if (linbits > 0 && ay == 15) writer.writeBits(escapeY, linbits)
            if (y != 0) writer.writeBit(if (y < 0) 1 else 0)
            i += 2
        }
    }

    /** Writes the quadruples of `values[from until to]` with count1 table [table]. */
    fun writeQuads(writer: BitWriterMsb, values: IntArray, from: Int, to: Int, table: Int) {
        val id = 32 + table
        var i = from
        while (i < to) {
            val index = abs(values[i]) * 8 + abs(values[i + 1]) * 4 + abs(values[i + 2]) * 2 + abs(values[i + 3])
            writer.writeBits(CODES[id][index], LENGTHS[id][index])
            for (k in 0 until 4) {
                val v = values[i + k]
                if (v != 0) writer.writeBit(if (v < 0) 1 else 0)
            }
            i += 4
        }
    }

    private fun abs(value: Int): Int = if (value < 0) -value else value
}

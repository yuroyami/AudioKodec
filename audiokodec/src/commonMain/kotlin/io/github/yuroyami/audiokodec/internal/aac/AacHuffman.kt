package io.github.yuroyami.audiokodec.internal.aac

import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.internal.bits.BitReaderMsb

/**
 * Decodes the codewords of AAC's scalefactor and spectral codebooks, and
 * turns a spectral codeword's index into the values it codes.
 *
 * Written from ISO/IEC 14496-3:2009, 4.6.3. Each codebook is walked as a
 * binary tree built from [AacTables], with the first [FAST_BITS] bits of a
 * codeword resolved by a lookup built from the same tree, so that the two
 * cannot disagree.
 */
internal object AacHuffman {

    /** The codebook whose values are coded as escape sequences above 15. */
    const val ESCAPE_CODEBOOK: Int = 11

    /**
     * Reads one scalefactor codeword and returns the difference it codes,
     * from -60 to 60.
     */
    fun readScalefactorDelta(bits: BitReaderMsb): Int = decodeIndex(bits, 0) - 60

    /**
     * Reads one spectral codeword of [codebook], 1 to 11, with its sign bits
     * and escape sequences, and writes its two or four values to [out] from
     * [at]. Returns how many values it wrote.
     */
    fun readSpectral(bits: BitReaderMsb, codebook: Int, out: IntArray, at: Int): Int {
        var index = decodeIndex(bits, codebook)
        val dimension = DIMENSION[codebook]
        val unsigned = UNSIGNED[codebook]
        val modulus = MODULUS[codebook]
        val offset = if (unsigned) 0 else LAV[codebook]
        // 4.6.3.3: the index is the values written in base mod, most
        // significant first, each offset by lav when the codebook is signed.
        var divisor = if (dimension == 4) modulus * modulus * modulus else modulus
        for (i in 0 until dimension) {
            val digit = index / divisor
            index -= digit * divisor
            out[at + i] = digit - offset
            divisor /= modulus
        }
        if (unsigned) {
            for (i in 0 until dimension) {
                if (out[at + i] != 0 && bits.readBit() == 1) out[at + i] = -out[at + i]
            }
        }
        if (codebook == ESCAPE_CODEBOOK) {
            for (i in 0 until dimension) {
                val value = out[at + i]
                if (value == 16 || value == -16) {
                    val escaped = readEscape(bits)
                    out[at + i] = if (value < 0) -escaped else escaped
                }
            }
        }
        return dimension
    }

    /**
     * An escape sequence: N ones, a zero, and an N + 4 bit word, coding
     * 2^(N+4) plus the word. 4.6.1.3 limits a quantised value to 8191, so N
     * is at most 8.
     */
    private fun readEscape(bits: BitReaderMsb): Int {
        var prefix = 0
        while (bits.readBit() == 1) {
            prefix++
            if (prefix > 8) throw CorruptStreamException("an AAC escape sequence codes a value past 8191")
        }
        val width = prefix + 4
        return (1 shl width) + bits.readBits(width)
    }

    /** Signed or unsigned, by codebook, Table 4.151. Index 0 is the zero codebook. */
    private val UNSIGNED = booleanArrayOf(false, false, false, true, true, false, false, true, true, true, true, true)
    private val DIMENSION = intArrayOf(0, 4, 4, 4, 4, 2, 2, 2, 2, 2, 2, 2)
    private val LAV = intArrayOf(0, 1, 1, 2, 2, 4, 4, 7, 7, 12, 12, 16)
    private val MODULUS = IntArray(12) { if (UNSIGNED[it]) LAV[it] + 1 else 2 * LAV[it] + 1 }

    /** Walks one codeword of codebook [table], 0 for scalefactors, and returns its index. */
    private fun decodeIndex(bits: BitReaderMsb, table: Int): Int {
        val remaining = bits.bitsRemaining
        if (remaining <= 0) throw CorruptStreamException("an AAC block ends inside a codeword")
        val take = if (remaining < FAST_BITS) remaining else FAST_BITS
        val entry = FAST[table][bits.peekBits(take) shl (FAST_BITS - take)]
        if (entry > 0) {
            val length = entry and 0x1F
            // A codeword that only the zero padding completed is not one the data holds.
            if (length > take) throw CorruptStreamException("an AAC block ends inside a codeword")
            bits.skipBits(length)
            return entry ushr 5
        }
        if (take < FAST_BITS) throw CorruptStreamException("an AAC block ends inside a codeword")
        bits.skipBits(FAST_BITS)
        val tree = TREES[table]
        var node = -entry
        while (true) {
            val next = tree[2 * node + bits.readBit()]
            if (next < 0) return -next - 1
            node = next
        }
    }

    /** How many bits the lookup resolves in one step. */
    private const val FAST_BITS = 9

    /**
     * One decode tree per codebook, as pairs of child slots: a negative slot
     * is a leaf naming index `-slot - 1`, a positive one the next node. The
     * codes are complete, so every slot is filled.
     */
    private val TREES: Array<IntArray> = Array(12) { buildTree(AacTables.LENGTHS[it], AacTables.CODES[it]) }

    /**
     * The first [FAST_BITS] bits of every codeword: a positive entry is a leaf,
     * its index above five bits of length, and a negative one the node the
     * walk continues from.
     */
    private val FAST: Array<IntArray> = Array(12) { buildFast(TREES[it]) }

    private fun buildTree(lengths: IntArray, codes: IntArray): IntArray {
        var slots = 1
        for (length in lengths) slots += length
        val tree = IntArray(2 * slots)
        var used = 1
        for (index in lengths.indices) {
            val code = codes[index]
            var node = 0
            for (depth in lengths[index] - 1 downTo 0) {
                val slot = 2 * node + ((code ushr depth) and 1)
                if (depth == 0) {
                    tree[slot] = -index - 1
                } else {
                    if (tree[slot] == 0) tree[slot] = used++
                    node = tree[slot]
                }
            }
        }
        return tree.copyOf(2 * used)
    }

    private fun buildFast(tree: IntArray): IntArray = IntArray(1 shl FAST_BITS) { prefix ->
        var node = 0
        var entry = 0
        for (depth in 1..FAST_BITS) {
            val next = tree[2 * node + ((prefix ushr (FAST_BITS - depth)) and 1)]
            if (next < 0) {
                entry = ((-next - 1) shl 5) or depth
                break
            }
            node = next
            entry = -node
        }
        entry
    }
}

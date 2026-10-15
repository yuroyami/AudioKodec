package io.github.yuroyami.audiokodec.internal.vorbis

import io.github.yuroyami.audiokodec.internal.bits.BitWriterLsb

/**
 * A Vorbis codebook as the encoder sees it: entries, their codewords, and for
 * a vector book the lattice the entries stand for.
 *
 * Every codebook a Vorbis stream uses travels in its setup header, so the
 * encoder is free to design its own. These are built from a model of how
 * likely each entry is: the lengths are a Huffman code for that model, and the
 * codewords follow from the lengths by the rule in section 3.2.1 of the
 * Vorbis I specification, which is what the decoder reproduces.
 *
 * A vector book is a lattice, lookup type 1: [dimensions] axes that each take
 * the [values] integers `minimum, minimum + delta, ...`, every combination one
 * entry. The first axis is the fastest-changing digit of the entry number.
 */
internal class VorbisEncodeBook private constructor(
    val dimensions: Int,
    val lengths: IntArray,
    /** Values per axis of a lattice book, or 0 for a book of plain numbers. */
    val values: Int,
    val minimum: Int,
    val delta: Int,
) {
    val entries: Int get() = lengths.size

    private val codes: IntArray = assignCodewords(lengths)

    /** Writes the codeword of [entry]. */
    fun write(writer: BitWriterLsb, entry: Int) {
        writer.writeCodeword(codes[entry], lengths[entry])
    }

    /** The entry whose vector is `vector[from until from + dimensions]`, every value on the lattice. */
    fun entryOf(vector: IntArray, from: Int): Int {
        var entry = 0
        var weight = 1
        for (i in 0 until dimensions) {
            val index = (vector[from + i] - minimum) / delta
            entry += index * weight
            weight *= values
        }
        return entry
    }

    /** The largest magnitude a lattice axis reaches. */
    val reach: Int get() = (values - 1) / 2 * delta

    /** Writes this book's part of the setup header, section 3.2.1. */
    fun writeHeader(writer: BitWriterLsb) {
        writer.writeBits(SYNC, 24)
        writer.writeBits(dimensions, 16)
        writer.writeBits(entries, 24)
        writer.writeBits(0, 1) // not ordered
        val sparse = lengths.any { it == 0 }
        writer.writeBit(sparse)
        for (length in lengths) {
            if (sparse) {
                writer.writeBit(length > 0)
                if (length == 0) continue
            }
            writer.writeBits(length - 1, 5)
        }
        if (values == 0) {
            writer.writeBits(0, 4)
            return
        }
        writer.writeBits(1, 4)
        writer.writeBits(packFloat(minimum), 32)
        writer.writeBits(packFloat(delta), 32)
        val valueBits = VorbisCodebook.ilog(values - 1).coerceAtLeast(1)
        writer.writeBits(valueBits - 1, 4)
        writer.writeBits(0, 1) // not a sequence
        for (multiplicand in 0 until values) writer.writeBits(multiplicand, valueBits)
    }

    companion object {
        private const val SYNC = 0x56_4342

        /** The longest codeword the encoder makes, well inside the format's 32. */
        private const val MAX_LENGTH = 24

        /**
         * A book of plain numbers, entry `i` as likely as `weights[i]`.
         *
         * [dimensions] matters only to a residue's classification book, which
         * reads it as how many partitions one entry classifies.
         */
        fun scalar(weights: DoubleArray, dimensions: Int = 1): VorbisEncodeBook =
            VorbisEncodeBook(dimensions, huffmanLengths(weights), 0, 0, 0)

        /**
         * A lattice of [dimensions] axes over `-reach..reach` in steps of
         * [delta], where `reach` is `half * delta`, each axis value as likely
         * as [weight] says and the axes independent.
         */
        fun lattice(dimensions: Int, half: Int, delta: Int, weight: (Int) -> Double): VorbisEncodeBook {
            val values = 2 * half + 1
            var entries = 1
            repeat(dimensions) { entries *= values }
            val axis = DoubleArray(values) { weight(it - half) }
            val weights = DoubleArray(entries) { entry ->
                var product = 1.0
                var rest = entry
                repeat(dimensions) {
                    product *= axis[rest % values]
                    rest /= values
                }
                product
            }
            return VorbisEncodeBook(dimensions, huffmanLengths(weights), values, -half * delta, delta)
        }

        /**
         * Codeword lengths for [weights], a Huffman code no longer than
         * [MAX_LENGTH] bits.
         *
         * A Huffman code fills its tree exactly, which the format requires of
         * every book with more than one entry. When the plain code would run
         * too long, the rarest entries are made a little more likely and the
         * code is built again, which shortens the deepest branches at almost
         * no cost.
         */
        fun huffmanLengths(weights: DoubleArray): IntArray {
            val count = weights.size
            require(count >= 2) { "a Huffman code needs two symbols" }
            val total = weights.sum()
            var floor = total * 1e-9
            while (true) {
                val lengths = huffman(DoubleArray(count) { maxOf(weights[it], floor) })
                if (lengths.max() <= MAX_LENGTH) return lengths
                floor *= 4.0
            }
        }

        private fun huffman(weights: DoubleArray): IntArray {
            val count = weights.size
            // Two queues: the leaves in ascending order, then the merged nodes,
            // which are created in ascending order too. Taking the smaller head
            // twice is the whole algorithm.
            val order = (0 until count).sortedBy { weights[it] }
            val nodeWeight = DoubleArray(2 * count - 1)
            val parent = IntArray(2 * count - 1) { -1 }
            for (i in 0 until count) nodeWeight[i] = weights[order[i]]
            var leaf = 0
            var merged = count
            var next = count
            fun take(): Int = if (leaf < count && (merged >= next || nodeWeight[leaf] <= nodeWeight[merged])) {
                leaf++
            } else {
                merged++
            }
            while (next < 2 * count - 1) {
                val a = take()
                val b = take()
                nodeWeight[next] = nodeWeight[a] + nodeWeight[b]
                parent[a] = next
                parent[b] = next
                next++
            }
            val depth = IntArray(2 * count - 1)
            for (node in 2 * count - 3 downTo 0) depth[node] = depth[parent[node]] + 1
            val lengths = IntArray(count)
            for (i in 0 until count) lengths[order[i]] = depth[i]
            return lengths
        }

        /**
         * The codewords the decoder will derive from [lengths], right
         * aligned: each entry in turn takes the lowest free codeword of its
         * length, which section 3.2.1 of the specification defines.
         */
        fun assignCodewords(lengths: IntArray): IntArray {
            val codes = IntArray(lengths.size)
            // available[level] is the one free node at that depth, left
            // aligned in 32 bits, or -1 when there is none.
            val available = LongArray(33) { -1L }
            var first = true
            for (i in lengths.indices) {
                val length = lengths[i]
                if (length == 0) continue
                if (first) {
                    first = false
                    codes[i] = 0
                    for (level in 1..length) available[level] = 1L shl (32 - level)
                    continue
                }
                var level = length
                while (level > 0 && available[level] < 0) level--
                check(level > 0) { "codeword lengths overfill the tree" }
                val code = available[level]
                available[level] = -1L
                for (deeper in length downTo level + 1) available[deeper] = code + (1L shl (32 - deeper))
                codes[i] = (code ushr (32 - length)).toInt()
            }
            return codes
        }

        /**
         * Packs an integer into the format's own float: a 21-bit mantissa, a
         * sign and an exponent biased by 788, which for an integer is just the
         * integer with the bias.
         */
        private fun packFloat(value: Int): Int {
            val magnitude = if (value < 0) -value else value
            require(magnitude < 1 shl 21) { "$value does not fit a Vorbis float mantissa" }
            val sign = if (value < 0) 1 shl 31 else 0
            return sign or (788 shl 21) or magnitude
        }
    }
}

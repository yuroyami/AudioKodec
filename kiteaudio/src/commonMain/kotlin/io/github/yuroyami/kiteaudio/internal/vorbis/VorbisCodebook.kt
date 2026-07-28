package io.github.yuroyami.kiteaudio.internal.vorbis

import io.github.yuroyami.kiteaudio.CorruptStreamException
import io.github.yuroyami.kiteaudio.DecodeOptions
import kotlin.math.floor
import kotlin.math.pow

/**
 * One Vorbis codebook.
 *
 * A codebook is two things at once. It is a Huffman code, mapping bit patterns
 * of unequal length onto entry numbers, and for most books it is also a
 * dictionary: an entry number stands for a short vector of numbers, packed as
 * indices into a table of multiplicands. That second part is why Vorbis files
 * carry their own codebooks instead of using fixed ones. The encoder trains
 * them on the material.
 *
 * Written from the Vorbis I specification, section 3.
 */
internal class VorbisCodebook private constructor(
    val dimensions: Int,
    val entries: Int,
    /** 0 for a book that only codes numbers, 1 or 2 for one that codes vectors. */
    val lookupType: Int,
    private val codeLength: IntArray,
    private val fastBits: Int,
    private val fastEntry: IntArray,
    private val fastLength: IntArray,
    private val longCode: LongArray,
    private val longLength: IntArray,
    private val longEntry: IntArray,
    /**
     * The only entry with a codeword, when there is exactly one.
     *
     * A book like that has no bit patterns to tell apart, and the format says
     * so: reading from it always returns that entry and consumes one bit,
     * whichever way the bit reads. Encoders in the wild have written both.
     */
    private val singleEntry: Int,
    private val minimumValue: Float,
    private val deltaValue: Float,
    private val sequenceP: Boolean,
    private val lookupValues: Int,
    private val multiplicands: IntArray,
) {

    /** Every entry's vector, expanded up front when that is not too large. */
    private var expanded: FloatArray? = null

    /**
     * Reads one entry number.
     *
     * Returns -1 when the packet ran out, which the specification treats as an
     * ordinary end rather than as damage.
     */
    fun decodeScalar(reader: VorbisBitReader): Int {
        if (singleEntry >= 0) {
            reader.readBit()
            return if (reader.endOfPacket) -1 else singleEntry
        }
        val peeked = reader.peekBits(fastBits)
        val quick = fastEntry[peeked]
        if (quick >= 0) {
            reader.skipBits(fastLength[peeked])
            return if (reader.endOfPacket) -1 else quick
        }
        if (longCode.isEmpty()) throw unmatched()

        // The codewords are matched most significant bit first, and the packet
        // is packed least significant bit first, so the peeked bits are turned
        // around before they are compared.
        val wide = reverseBits(reader.peekBits(32)).toLong() and 0xFFFF_FFFFL
        var low = 0
        var high = longCode.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (longCode[middle] <= wide) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        if (found < 0) throw unmatched()
        val length = longLength[found]
        // The candidate is the largest codeword not greater than the bits at
        // hand. It is the right entry only if its own bits are their prefix.
        val mask = if (length >= 32) -1L else ((1L shl length) - 1L) shl (32 - length)
        if ((wide and mask) != longCode[found]) throw unmatched()
        reader.skipBits(length)
        return if (reader.endOfPacket) -1 else longEntry[found]
    }

    /**
     * A codeword that matches nothing.
     *
     * The book's codewords cover every bit pattern, because a book whose tree
     * has a hole in it is refused when the file is opened. So this cannot come
     * from running out of bits, which reads as zeroes and matches the codeword
     * beginning with zeroes; it means the bitstream is not what it claims.
     */
    private fun unmatched(): CorruptStreamException = CorruptStreamException(
        "a Vorbis codeword matches no entry of the codebook that should hold it",
    )

    /**
     * Adds one entry's vector into [into] at [at], which is what residue
     * decoding does on every pass after the first.
     */
    fun addVector(reader: VorbisBitReader, into: FloatArray, at: Int): Boolean {
        val entry = decodeScalar(reader)
        if (entry < 0) return false
        val table = expanded
        if (table != null) {
            val from = entry * dimensions
            for (i in 0 until dimensions) into[at + i] += table[from + i]
            return true
        }
        var last = 0f
        if (lookupType == 1) {
            var divisor = 1
            for (i in 0 until dimensions) {
                val offset = (entry / divisor) % lookupValues
                val value = multiplicands[offset] * deltaValue + minimumValue + last
                into[at + i] += value
                if (sequenceP) last = value
                divisor *= lookupValues
            }
        } else {
            var offset = entry * dimensions
            for (i in 0 until dimensions) {
                val value = multiplicands[offset] * deltaValue + minimumValue + last
                into[at + i] += value
                if (sequenceP) last = value
                offset++
            }
        }
        return true
    }

    /**
     * Adds one entry's vector into [into] with a gap of [step] between its
     * values, which is how a type 0 residue lays a codeword out.
     */
    fun addVectorScattered(reader: VorbisBitReader, into: FloatArray, at: Int, step: Int): Boolean {
        val entry = decodeScalar(reader)
        if (entry < 0) return false
        val table = expanded
        if (table != null) {
            val from = entry * dimensions
            for (i in 0 until dimensions) {
                val index = at + i * step
                if (index >= into.size) break
                into[index] += table[from + i]
            }
            return true
        }
        var last = 0f
        if (lookupType == 1) {
            var divisor = 1
            for (i in 0 until dimensions) {
                val offset = (entry / divisor) % lookupValues
                val value = multiplicands[offset] * deltaValue + minimumValue + last
                val index = at + i * step
                if (index < into.size) into[index] += value
                if (sequenceP) last = value
                divisor *= lookupValues
            }
        } else {
            var offset = entry * dimensions
            for (i in 0 until dimensions) {
                val value = multiplicands[offset] * deltaValue + minimumValue + last
                val index = at + i * step
                if (index < into.size) into[index] += value
                if (sequenceP) last = value
                offset++
            }
        }
        return true
    }

    private fun writeVector(entry: Int, into: FloatArray, at: Int) {
        val table = expanded
        if (table != null) {
            table.copyInto(into, at, entry * dimensions, (entry + 1) * dimensions)
            return
        }
        var last = 0f
        if (lookupType == 1) {
            var divisor = 1
            for (i in 0 until dimensions) {
                val offset = (entry / divisor) % lookupValues
                val value = multiplicands[offset] * deltaValue + minimumValue + last
                into[at + i] = value
                if (sequenceP) last = value
                divisor *= lookupValues
            }
        } else {
            var offset = entry * dimensions
            for (i in 0 until dimensions) {
                val value = multiplicands[offset] * deltaValue + minimumValue + last
                into[at + i] = value
                if (sequenceP) last = value
                offset++
            }
        }
    }

    internal companion object {

        /** Bits looked up in one step. Longer codewords fall back to a search. */
        private const val MAX_FAST_BITS = 10

        /**
         * Bytes a book with [entries] entries keeps for its codewords.
         *
         * Four arrays outlive the parse: the lengths, the codewords, and the
         * three that hold the codewords too long for the fast table. A file is
         * free to declare hundreds of books, so the caller adds these up and
         * checks the running total rather than each one on its own.
         */
        fun codewordBytes(entries: Int): Long = entries.toLong() * 24

        fun parse(
            reader: VorbisBitReader,
            options: DecodeOptions,
            at: Long,
            alreadyAllocatedCodewordBytes: Long = 0L,
        ): VorbisCodebook {
            val sync = reader.readBits(24)
            if (sync != 0x564342) {
                throw CorruptStreamException(
                    "a Vorbis codebook does not start with its marker, found " +
                        sync.toString(16),
                    at,
                )
            }
            val dimensions = reader.readBits(16)
            val entries = reader.readBits(24)
            if (dimensions <= 0 || entries <= 0) {
                throw CorruptStreamException(
                    "a Vorbis codebook declares $entries entries of $dimensions values",
                    at,
                )
            }

            options.checkAllocation(
                alreadyAllocatedCodewordBytes + codewordBytes(entries),
                "the codebooks of a Vorbis stream",
            )
            val lengths = IntArray(entries)
            val ordered = reader.readBits(1) == 1
            if (!ordered) {
                val sparse = reader.readBits(1) == 1
                for (i in 0 until entries) {
                    if (sparse && reader.readBits(1) == 0) {
                        lengths[i] = 0
                        continue
                    }
                    lengths[i] = reader.readBits(5) + 1
                }
            } else {
                // Ordered books state a run length per codeword length instead
                // of a length per entry, which is much smaller for the large
                // books and is why they exist.
                var current = 0
                var length = reader.readBits(5) + 1
                while (current < entries) {
                    val run = reader.readBits(ilog(entries - current))
                    if (run < 0 || current + run > entries) {
                        throw CorruptStreamException(
                            "a Vorbis codebook's ordered lengths overrun its $entries entries",
                            at,
                        )
                    }
                    for (i in current until current + run) lengths[i] = length
                    current += run
                    length++
                    // A book may end on a run of 32-bit codewords, so the limit
                    // only bites when there are entries left to give one to.
                    if (current < entries && length > 32) {
                        throw CorruptStreamException(
                            "a Vorbis codebook declares a codeword longer than 32 bits",
                            at,
                        )
                    }
                    if (reader.endOfPacket) {
                        throw CorruptStreamException(
                            "a Vorbis codebook ends inside its ordered lengths", at,
                        )
                    }
                }
            }
            if (reader.endOfPacket) {
                throw CorruptStreamException("a Vorbis codebook ends inside its lengths", at)
            }

            val lookupType = reader.readBits(4)
            var minimumValue = 0f
            var deltaValue = 0f
            var sequenceP = false
            var lookupValues = 0
            var multiplicands = IntArray(0)
            if (lookupType == 1 || lookupType == 2) {
                minimumValue = unpackFloat(reader.readBits(32))
                deltaValue = unpackFloat(reader.readBits(32))
                val valueBits = reader.readBits(4) + 1
                sequenceP = reader.readBits(1) == 1
                lookupValues = if (lookupType == 1) {
                    lookup1Values(entries, dimensions)
                } else {
                    val product = entries.toLong() * dimensions
                    if (product > Int.MAX_VALUE) {
                        throw CorruptStreamException(
                            "a Vorbis codebook declares $product multiplicands",
                            at,
                        )
                    }
                    product.toInt()
                }
                // The multiplicands are stored one after another with no
                // compression, so a book cannot declare more of them than the
                // packet has bits left to hold. Checking that before allocating
                // is what stops a fifteen-byte header asking for a gigabyte.
                if (lookupValues.toLong() * valueBits > reader.bitsRemaining) {
                    throw CorruptStreamException(
                        "a Vorbis codebook declares $lookupValues multiplicands of " +
                            "$valueBits bits, and the packet has ${reader.bitsRemaining} bits left",
                        at,
                    )
                }
                options.checkAllocation(
                    lookupValues.toLong() * 4,
                    "a Vorbis codebook of $lookupValues multiplicands",
                )
                multiplicands = IntArray(lookupValues) { reader.readBits(valueBits) }
            } else if (lookupType != 0) {
                throw CorruptStreamException(
                    "a Vorbis codebook uses lookup type $lookupType, which the format does not define",
                    at,
                )
            }
            if (reader.endOfPacket) {
                throw CorruptStreamException("a Vorbis codebook ends inside its lookup table", at)
            }

            var maxLength = 0
            var used = 0
            var only = -1
            for (i in 0 until entries) {
                if (lengths[i] > 0) {
                    used++
                    only = i
                    if (lengths[i] > maxLength) maxLength = lengths[i]
                }
            }
            if (used == 0) {
                throw CorruptStreamException("a Vorbis codebook has no usable entries", at)
            }

            // A book with one entry is the one shape the format lets be
            // incomplete, and then only with a one-bit codeword. Every other
            // book has to fill its tree exactly: a hole would leave bit
            // patterns that decode to nothing, and an overlap would leave one
            // that decodes to two things.
            val singleEntry = if (used == 1) {
                if (lengths[only] != 1) {
                    throw CorruptStreamException(
                        "a Vorbis codebook has one entry with a ${lengths[only]}-bit codeword, " +
                            "and the format allows only one bit",
                        at,
                    )
                }
                only
            } else {
                var kraft = 0L
                for (i in 0 until entries) {
                    if (lengths[i] > 0) kraft += 1L shl (32 - lengths[i])
                }
                if (kraft != 1L shl 32) {
                    throw CorruptStreamException(
                        "a Vorbis codebook's codeword lengths " +
                            (if (kraft < 1L shl 32) "leave part of" else "overrun") +
                            " the tree they have to fill",
                        at,
                    )
                }
                -1
            }

            val codes = assignCodewords(lengths, at)

            val fastBits = if (maxLength < MAX_FAST_BITS) maxLength else MAX_FAST_BITS
            val fastEntry = IntArray(1 shl fastBits) { -1 }
            val fastLength = IntArray(1 shl fastBits)
            var longCount = 0
            for (i in 0 until entries) {
                if (lengths[i] in 1..fastBits) {
                    // The bits arrive least significant first, so a codeword's
                    // first bit is the lowest bit of what a peek returns.
                    var key = 0
                    for (bit in 0 until lengths[i]) {
                        key = key or (((codes[i] ushr (31 - bit)) and 1) shl bit)
                    }
                    var high = 0
                    val spare = 1 shl (fastBits - lengths[i])
                    while (high < spare) {
                        val index = key or (high shl lengths[i])
                        fastEntry[index] = i
                        fastLength[index] = lengths[i]
                        high++
                    }
                } else if (lengths[i] > fastBits) {
                    longCount++
                }
            }

            val longCode = LongArray(longCount)
            val longLength = IntArray(longCount)
            val longEntry = IntArray(longCount)
            var write = 0
            for (i in 0 until entries) {
                if (lengths[i] > fastBits) {
                    longCode[write] = codes[i].toLong() and 0xFFFF_FFFFL
                    longLength[write] = lengths[i]
                    longEntry[write] = i
                    write++
                }
            }
            sortByCode(longCode, longLength, longEntry)

            val book = VorbisCodebook(
                dimensions = dimensions,
                entries = entries,
                lookupType = lookupType,
                codeLength = lengths,
                fastBits = fastBits,
                fastEntry = fastEntry,
                fastLength = fastLength,
                longCode = longCode,
                longLength = longLength,
                longEntry = longEntry,
                singleEntry = singleEntry,
                minimumValue = minimumValue,
                deltaValue = deltaValue,
                sequenceP = sequenceP,
                lookupValues = lookupValues,
                multiplicands = multiplicands,
            )

            // Expanding every entry's vector once is worth it for the books a
            // residue uses on every partition, and the cap keeps a file from
            // asking for an unreasonable table.
            val expandedSize = entries.toLong() * dimensions
            if (lookupType != 0 && expandedSize <= 1 shl 16) {
                options.checkAllocation(
                    expandedSize * Float.SIZE_BYTES,
                    "an expanded Vorbis codebook",
                )
                val table = FloatArray(expandedSize.toInt())
                for (entry in 0 until entries) book.writeVector(entry, table, entry * dimensions)
                book.expanded = table
            }
            return book
        }

        /**
         * Gives every entry with a length its codeword.
         *
         * The format does not store codewords, only their lengths, and the rule
         * that turns one into the other is the whole of section 3.2.1: walk the
         * entries in order and hand each one the shortest unused codeword that
         * fits under a free node of its length, splitting a shorter free node
         * when there is none. Codewords are held with their first bit in bit 31,
         * which makes "is this codeword a prefix of these bits" a mask and a
         * comparison.
         */
        private fun assignCodewords(lengths: IntArray, at: Long): IntArray {
            val codes = IntArray(lengths.size)
            val available = IntArray(33)

            var first = 0
            while (first < lengths.size && lengths[first] == 0) first++
            if (first == lengths.size) return codes

            codes[first] = 0
            for (i in 1..lengths[first]) available[i] = 1 shl (32 - i)

            for (i in first + 1 until lengths.size) {
                val length = lengths[i]
                if (length == 0) continue
                var level = length
                while (level > 0 && available[level] == 0) level--
                if (level == 0) {
                    throw CorruptStreamException(
                        "a Vorbis codebook's codeword lengths do not form a tree",
                        at,
                    )
                }
                val code = available[level]
                available[level] = 0
                codes[i] = code
                if (level != length) {
                    // Splitting a shorter free node leaves one free node at
                    // every level between it and the length just used.
                    for (deeper in length downTo level + 1) {
                        available[deeper] = code + (1 shl (32 - deeper))
                    }
                }
            }
            return codes
        }

        /**
         * Sorts the long codewords ascending, keeping their lengths and entry
         * numbers beside them.
         *
         * Hostile setup packets can make this list large and reverse ordered.
         * Heap sort keeps that case O(n log n), without allocating another
         * attacker-sized index array.
         */
        internal fun sortByCode(
            codes: LongArray,
            lengths: IntArray,
            entries: IntArray,
        ) {
            require(codes.size == lengths.size && codes.size == entries.size)
            if (codes.size < 2) return

            fun swap(left: Int, right: Int) {
                val code = codes[left]
                codes[left] = codes[right]
                codes[right] = code
                val length = lengths[left]
                lengths[left] = lengths[right]
                lengths[right] = length
                val entry = entries[left]
                entries[left] = entries[right]
                entries[right] = entry
            }

            fun siftDown(start: Int, endInclusive: Int) {
                if (start >= endInclusive) return
                var root = start
                while (root <= (endInclusive - 1) / 2) {
                    var child = root * 2 + 1
                    if (child + 1 <= endInclusive && codes[child] < codes[child + 1]) {
                        child++
                    }
                    if (codes[root] >= codes[child]) return
                    swap(root, child)
                    root = child
                }
            }

            for (start in (codes.size - 2) / 2 downTo 0) {
                siftDown(start, codes.lastIndex)
            }
            for (end in codes.lastIndex downTo 1) {
                swap(0, end)
                siftDown(0, end - 1)
            }
        }

        /**
         * Turns the format's own 32-bit float into a real one.
         *
         * Vorbis does not store IEEE floats in its headers. It stores a sign, a
         * 21-bit mantissa and a 10-bit exponent, so that a file means the same
         * number on a machine that has never heard of IEEE 754.
         */
        fun unpackFloat(packed: Int): Float {
            val mantissa = packed and 0x1F_FFFF
            val sign = packed and -0x8000_0000
            val exponent = (packed and 0x7FE0_0000) ushr 21
            val value = if (sign != 0) -mantissa.toDouble() else mantissa.toDouble()
            return (value * 2.0.pow(exponent - 788)).toFloat()
        }

        /**
         * The largest value whose [dimensions]th power still fits in [entries].
         *
         * A lookup type 1 book stores one multiplicand per axis and builds its
         * vectors from every combination, so this is how many values each axis
         * has.
         */
        fun lookup1Values(entries: Int, dimensions: Int): Int {
            if (dimensions <= 0) return 0
            var value = floor(entries.toDouble().pow(1.0 / dimensions)).toInt()
            if (value < 0) value = 0
            // Rounding a root is not exact, so the answer is nudged into place.
            while (power(value + 1, dimensions) <= entries) value++
            while (value > 0 && power(value, dimensions) > entries) value--
            return value
        }

        private fun power(base: Int, exponent: Int): Long {
            var result = 1L
            for (i in 0 until exponent) {
                result *= base
                if (result > Int.MAX_VALUE) return Long.MAX_VALUE / 2
            }
            return result
        }

        /** The format's own integer logarithm: the position of the top set bit. */
        fun ilog(value: Int): Int {
            if (value <= 0) return 0
            return 32 - value.countLeadingZeroBits()
        }

        private fun reverseBits(value: Int): Int {
            var v = value
            v = ((v and 0x5555_5555) shl 1) or ((v ushr 1) and 0x5555_5555)
            v = ((v and 0x3333_3333) shl 2) or ((v ushr 2) and 0x3333_3333)
            v = ((v and 0x0F0F_0F0F) shl 4) or ((v ushr 4) and 0x0F0F_0F0F)
            v = ((v and 0x00FF_00FF) shl 8) or ((v ushr 8) and 0x00FF_00FF)
            return (v shl 16) or (v ushr 16)
        }
    }
}

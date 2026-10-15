package io.github.yuroyami.audiokodec.internal.vorbis

import io.github.yuroyami.audiokodec.internal.bits.BitWriterLsb

/**
 * Writes a residue of type 1 or 2: the quantised spectrum, in partitions.
 *
 * Each partition is classified by its largest value, and the class names the
 * codebooks that code it. Small values go through small lattices of several
 * dimensions at once, where most of the probability sits on a few entries and
 * a codeword can cost well under a bit per value. Large values go through a
 * cascade: a coarse pass codes the value to the nearest multiple of a step,
 * and finer passes code what is left, which keeps every codebook small.
 *
 * Type 2 interleaves the channels into one vector first, so a coupled stereo
 * pair shares one classification per partition. Type 1 classifies each
 * channel on its own.
 *
 * Written from the Vorbis I specification, section 8.
 */
internal class VorbisResidueEncoder(
    val type: Int,
    val begin: Int,
    val end: Int,
    val partitionSize: Int,
    private val classes: Array<ResidueClass>,
    private val classbookIndex: Int,
    private val books: List<VorbisEncodeBook>,
) {
    init {
        require(type == 1 || type == 2) { "the encoder writes residue type 1 or 2" }
    }

    /**
     * One classification: the largest magnitude it codes, and for each pass
     * the codebook that pass uses, or -1. A pass codes multiples of its book's
     * delta, the coarsest first.
     */
    class ResidueClass(val maximum: Int, val passBooks: IntArray)

    private val classbook = books[classbookIndex]
    private val passes = classes.maxOf { it.passBooks.size }

    private var classOf = IntArray(0)
    private var components = Array(0) { IntArray(0) }
    private var flat = IntArray(0)
    private val vector = IntArray(8)

    /**
     * Writes [channels], each holding at least `half` values, skipping those
     * marked in [skip] as the decoder will.
     */
    fun write(writer: BitWriterLsb, channels: Array<IntArray>, count: Int, skip: BooleanArray, half: Int) {
        if (type == 2) {
            var any = false
            for (c in 0 until count) if (!skip[c]) any = true
            if (!any) return
            val size = half * count
            if (flat.size < size) flat = IntArray(size)
            for (i in 0 until half) {
                for (c in 0 until count) flat[i * count + c] = if (skip[c]) 0 else channels[c][i]
            }
            writeVectors(writer, arrayOf(flat), 1, booleanArrayOf(false), size)
        } else {
            writeVectors(writer, channels, count, skip, half)
        }
    }

    private fun writeVectors(
        writer: BitWriterLsb,
        vectors: Array<IntArray>,
        count: Int,
        skip: BooleanArray,
        actualSize: Int,
    ) {
        val limitBegin = minOf(begin, actualSize)
        val limitEnd = minOf(end, actualSize)
        val partitionCount = (limitEnd - limitBegin) / partitionSize
        if (partitionCount <= 0) return
        val classwords = classbook.dimensions
        val stride = partitionCount + classwords
        if (classOf.size < count * stride) classOf = IntArray(count * stride)
        if (components.size < passes || (components.firstOrNull()?.size ?: 0) < count * actualSize) {
            components = Array(passes) { IntArray(count * actualSize) }
        }

        // Classify every partition and split its values into the passes.
        for (c in 0 until count) {
            if (skip[c]) continue
            val values = vectors[c]
            for (p in 0 until stride) {
                if (p >= partitionCount) {
                    classOf[c * stride + p] = 0
                    continue
                }
                val at = limitBegin + p * partitionSize
                var largest = 0
                for (i in at until at + partitionSize) {
                    val magnitude = if (values[i] < 0) -values[i] else values[i]
                    if (magnitude > largest) largest = magnitude
                }
                var chosen = 0
                while (classes[chosen].maximum < largest) chosen++
                classOf[c * stride + p] = chosen
                val passBooks = classes[chosen].passBooks
                for (i in at until at + partitionSize) {
                    var rest = values[i]
                    for (pass in 0 until passes) {
                        val book = if (pass < passBooks.size) passBooks[pass] else -1
                        if (book < 0) {
                            components[pass][c * actualSize + i] = 0
                            continue
                        }
                        val step = books[book].delta
                        val reach = books[book].reach
                        var part = roundedQuotient(rest, step) * step
                        if (part > reach) part = reach
                        if (part < -reach) part = -reach
                        components[pass][c * actualSize + i] = part
                        rest -= part
                    }
                    check(rest == 0) { "a residue value of ${values[i]} escaped its class" }
                }
            }
        }

        for (pass in 0 until passes) {
            var partition = 0
            while (partition < partitionCount) {
                if (pass == 0) {
                    for (c in 0 until count) {
                        if (skip[c]) continue
                        var word = 0
                        for (i in 0 until classwords) word = word * classes.size + classOf[c * stride + partition + i]
                        classbook.write(writer, word)
                    }
                }
                var i = 0
                while (i < classwords && partition < partitionCount) {
                    for (c in 0 until count) {
                        if (skip[c]) continue
                        val passBooks = classes[classOf[c * stride + partition]].passBooks
                        val bookIndex = if (pass < passBooks.size) passBooks[pass] else -1
                        if (bookIndex < 0) continue
                        val book = books[bookIndex]
                        val values = components[pass]
                        val at = c * actualSize + limitBegin + partition * partitionSize
                        var j = 0
                        while (j < partitionSize) {
                            for (d in 0 until book.dimensions) vector[d] = values[at + j + d]
                            book.write(writer, book.entryOf(vector, 0))
                            j += book.dimensions
                        }
                    }
                    partition++
                    i++
                }
            }
        }
    }

    /** Writes this residue's part of the setup header, section 8.6.1. */
    fun writeHeader(writer: BitWriterLsb) {
        writer.writeBits(begin, 24)
        writer.writeBits(end, 24)
        writer.writeBits(partitionSize - 1, 24)
        writer.writeBits(classes.size - 1, 6)
        writer.writeBits(classbookIndex, 8)
        for (cls in classes) {
            var cascade = 0
            for (pass in cls.passBooks.indices) if (cls.passBooks[pass] >= 0) cascade = cascade or (1 shl pass)
            writer.writeBits(cascade and 7, 3)
            if (cascade > 7) {
                writer.writeBits(1, 1)
                writer.writeBits(cascade ushr 3, 5)
            } else {
                writer.writeBits(0, 1)
            }
        }
        for (cls in classes) {
            for (book in cls.passBooks) if (book >= 0) writer.writeBits(book, 8)
        }
    }
}

/** [value] over [step], rounded to the nearest integer, halves away from zero. */
private fun roundedQuotient(value: Int, step: Int): Int =
    if (value >= 0) (value + step / 2) / step else -((-value + step / 2) / step)

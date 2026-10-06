package io.github.yuroyami.audiokodec.internal.vorbis

import io.github.yuroyami.audiokodec.CorruptStreamException

/**
 * A Vorbis residue: what is left of the spectrum once the floor is divided out.
 *
 * The residual is nearly flat, so it is coded in partitions, each partition
 * classified into one of a few kinds and then coded by the codebook that suits
 * that kind. Several passes run over the same partitions, each adding a finer
 * correction, which is how one stream can be decoded at more than one quality
 * and how an encoder spends its bits where they matter.
 *
 * The three types differ only in how a partition's numbers are laid out.
 * Type 0 scatters each codeword's values with a stride, type 1 lays them down
 * one after another, and type 2 lays them down one after another across all
 * channels at once, interleaved, so that a stereo pair shares its classifier.
 *
 * Written from the Vorbis I specification, section 8.
 */
internal class VorbisResidue(
    val type: Int,
    private val begin: Int,
    private val end: Int,
    private val partitionSize: Int,
    private val classifications: Int,
    private val classbook: Int,
    private val books: Array<IntArray>,
) {

    /**
     * Buffers a residue decode needs, held for the life of the stream.
     *
     * The classification array is the only one whose size depends on the
     * packet, and it is grown rather than reallocated.
     */
    class Scratch {
        var classes = IntArray(0)
        var interleaved = FloatArray(0)

        fun classesOf(size: Int): IntArray {
            if (classes.size < size) classes = IntArray(size)
            return classes
        }

        fun interleavedOf(size: Int): FloatArray {
            if (interleaved.size < size) interleaved = FloatArray(size)
            return interleaved
        }
    }

    /**
     * Decodes this residue into [channels], each of which holds [half] values.
     *
     * A channel flagged in [skip] has no residual in this packet and is left
     * alone. Returns false when the packet ran out partway, which is not damage
     * and leaves whatever was decoded in place.
     */
    fun decode(
        reader: VorbisBitReader,
        codebooks: Array<VorbisCodebook>,
        channels: Array<FloatArray>,
        used: Int,
        skip: BooleanArray,
        half: Int,
        scratch: Scratch,
    ): Boolean {
        if (type == 2) {
            var anyWanted = false
            for (j in 0 until used) if (!skip[j]) anyWanted = true
            if (!anyWanted) return true

            // One vector holding every channel's values, one channel after
            // another at each position. The classifier then sees the channels
            // together, which is the whole point of the type.
            val size = half * used
            val flat = scratch.interleavedOf(size)
            for (i in 0 until size) flat[i] = 0f
            val ok = decodeOneVector(reader, codebooks, flat, size, scratch)
            for (i in 0 until half) {
                val base = i * used
                for (j in 0 until used) channels[j][i] += flat[base + j]
            }
            return ok
        }

        val actualSize = half
        val limitBegin = if (begin < actualSize) begin else actualSize
        val limitEnd = if (end < actualSize) end else actualSize
        val toRead = limitEnd - limitBegin
        if (toRead <= 0) return true
        val partitionsToRead = toRead / partitionSize
        if (partitionsToRead <= 0) return true

        val classbookEntry = codebooks.getOrNull(classbook) ?: throw CorruptStreamException(
            "a Vorbis residue names codebook $classbook, which the file does not have",
            0,
        )
        val classwords = classbookEntry.dimensions
        val classes = scratch.classesOf(used * (partitionsToRead + classwords))

        for (pass in 0 until 8) {
            var partition = 0
            while (partition < partitionsToRead) {
                if (pass == 0) {
                    for (j in 0 until used) {
                        if (skip[j]) continue
                        var word = classbookEntry.decodeScalar(reader)
                        if (word < 0) return false
                        for (i in classwords - 1 downTo 0) {
                            classes[j * (partitionsToRead + classwords) + partition + i] =
                                word % classifications
                            word /= classifications
                        }
                    }
                }
                var i = 0
                while (i < classwords && partition < partitionsToRead) {
                    for (j in 0 until used) {
                        if (skip[j]) continue
                        val vqClass = classes[j * (partitionsToRead + classwords) + partition]
                        val bookNumber = books[vqClass][pass]
                        if (bookNumber < 0) continue
                        val book = codebooks.getOrNull(bookNumber) ?: throw CorruptStreamException(
                            "a Vorbis residue names codebook $bookNumber, which the file does not have",
                            0,
                        )
                        val at = limitBegin + partition * partitionSize
                        val ok = if (type == 0) {
                            decodeScattered(reader, book, channels[j], at)
                        } else {
                            decodeInOrder(reader, book, channels[j], at, partitionSize)
                        }
                        if (!ok) return false
                    }
                    partition++
                    i++
                }
            }
        }
        return true
    }

    /** The type 2 path, which is type 1 run over one long interleaved vector. */
    private fun decodeOneVector(
        reader: VorbisBitReader,
        codebooks: Array<VorbisCodebook>,
        flat: FloatArray,
        actualSize: Int,
        scratch: Scratch,
    ): Boolean {
        val limitBegin = if (begin < actualSize) begin else actualSize
        val limitEnd = if (end < actualSize) end else actualSize
        val toRead = limitEnd - limitBegin
        if (toRead <= 0) return true
        val partitionsToRead = toRead / partitionSize
        if (partitionsToRead <= 0) return true

        val classbookEntry = codebooks.getOrNull(classbook) ?: throw CorruptStreamException(
            "a Vorbis residue names codebook $classbook, which the file does not have",
            0,
        )
        val classwords = classbookEntry.dimensions
        val classes = scratch.classesOf(partitionsToRead + classwords)

        for (pass in 0 until 8) {
            var partition = 0
            while (partition < partitionsToRead) {
                if (pass == 0) {
                    var word = classbookEntry.decodeScalar(reader)
                    if (word < 0) return false
                    for (i in classwords - 1 downTo 0) {
                        classes[partition + i] = word % classifications
                        word /= classifications
                    }
                }
                var i = 0
                while (i < classwords && partition < partitionsToRead) {
                    val bookNumber = books[classes[partition]][pass]
                    if (bookNumber >= 0) {
                        val book = codebooks.getOrNull(bookNumber) ?: throw CorruptStreamException(
                            "a Vorbis residue names codebook $bookNumber, which the file does not have",
                            0,
                        )
                        val at = limitBegin + partition * partitionSize
                        if (!decodeInOrder(reader, book, flat, at, partitionSize)) return false
                    }
                    partition++
                    i++
                }
            }
        }
        return true
    }

    /**
     * Type 0: each codeword's values are spread across the partition with a
     * stride, so that one codeword covers the whole width coarsely rather than
     * a narrow slice finely.
     */
    private fun decodeScattered(
        reader: VorbisBitReader,
        book: VorbisCodebook,
        into: FloatArray,
        at: Int,
    ): Boolean {
        val step = partitionSize / book.dimensions
        if (step <= 0) return true
        for (i in 0 until step) {
            if (!book.addVectorScattered(reader, into, at + i, step)) return false
        }
        return true
    }

    /** Types 1 and 2: the values of each codeword sit next to each other. */
    private fun decodeInOrder(
        reader: VorbisBitReader,
        book: VorbisCodebook,
        into: FloatArray,
        at: Int,
        count: Int,
    ): Boolean {
        var i = 0
        while (i < count) {
            // A partition is meant to be a whole number of codewords. One that
            // is not would run a codeword's tail off the end of the vector.
            // Stopping here without reading the codeword would leave the reader
            // at the wrong bit for everything after it, so the packet ends.
            if (at + i + book.dimensions > into.size) return false
            if (!book.addVector(reader, into, at + i)) return false
            i += book.dimensions
        }
        return true
    }

    companion object {

        fun parse(
            type: Int,
            reader: VorbisBitReader,
            codebooks: Array<VorbisCodebook>,
            at: Long,
        ): VorbisResidue {
            val codebookCount = codebooks.size
            if (type !in 0..2) {
                throw CorruptStreamException(
                    "this Vorbis file declares residue type $type, which the format does not define",
                    at,
                )
            }
            val begin = reader.readBits(24)
            val end = reader.readBits(24)
            val partitionSize = reader.readBits(24) + 1
            val classifications = reader.readBits(6) + 1
            val classbook = reader.readBits(8)
            if (classbook >= codebookCount) {
                throw CorruptStreamException(
                    "a Vorbis residue names codebook $classbook of $codebookCount",
                    at,
                )
            }

            // One classbook entry names a whole run of partition classes at
            // once, so the book has to have an entry for every combination.
            // A book too small for that would leave classes unreachable.
            var combinations = 1L
            for (i in 0 until codebooks[classbook].dimensions) {
                combinations *= classifications
                if (combinations > codebooks[classbook].entries) {
                    throw CorruptStreamException(
                        "a Vorbis residue classifies into $classifications kinds " +
                            "${codebooks[classbook].dimensions} at a time, which codebook " +
                            "$classbook has too few entries to name",
                        at,
                    )
                }
            }

            val cascade = IntArray(classifications) {
                val low = reader.readBits(3)
                val high = if (reader.readBits(1) == 1) reader.readBits(5) else 0
                high * 8 + low
            }
            val books = Array(classifications) { i ->
                IntArray(8) { pass ->
                    if (cascade[i] and (1 shl pass) != 0) {
                        val book = reader.readBits(8)
                        if (book >= codebookCount) {
                            throw CorruptStreamException(
                                "a Vorbis residue names codebook $book of $codebookCount",
                                at,
                            )
                        }
                        // A residue reads vectors from its books, and a book
                        // with no lookup table has no vectors to give. The
                        // format forbids the pairing, and catching it here is
                        // what keeps the decode loop free of the check.
                        if (codebooks[book].lookupType == 0) {
                            throw CorruptStreamException(
                                "a Vorbis residue reads vectors from codebook $book, which " +
                                    "stores none",
                                at,
                            )
                        }
                        book
                    } else {
                        -1
                    }
                }
            }
            if (reader.endOfPacket) {
                throw CorruptStreamException("a Vorbis residue ends inside its codebook list", at)
            }
            return VorbisResidue(
                type = type,
                begin = begin,
                end = end,
                partitionSize = partitionSize,
                classifications = classifications,
                classbook = classbook,
                books = books,
            )
        }
    }
}

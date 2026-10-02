package io.github.yuroyami.audiokodecs

import io.github.yuroyami.audiokodecs.internal.vorbis.VorbisBitReader
import io.github.yuroyami.audiokodecs.internal.vorbis.VorbisCodebook
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checks the rules a Vorbis codebook has to obey.
 *
 * Every real file satisfies these, which is exactly why they need their own
 * tests: a decoder that never checks them passes on every real file and then
 * reads a malformed one into whatever the arithmetic happens to produce. The
 * books here are written bit by bit, so each rule is broken deliberately and on
 * its own.
 */
class VorbisCodebookTest {

    /** Writes bits the way Vorbis packs them, least significant first. */
    private class Bits {
        private val bytes = ArrayList<Byte>()
        private var partial = 0
        private var filled = 0

        fun write(value: Int, count: Int): Bits {
            for (i in 0 until count) {
                val bit = (value ushr i) and 1
                partial = partial or (bit shl filled)
                filled++
                if (filled == 8) {
                    bytes.add(partial.toByte())
                    partial = 0
                    filled = 0
                }
            }
            return this
        }

        fun toByteArray(): ByteArray {
            val out = ArrayList(bytes)
            if (filled > 0) out.add(partial.toByte())
            return ByteArray(out.size) { out[it] }
        }
    }

    /** A book header up to and including its codeword lengths. */
    private fun book(dimensions: Int, lengths: IntArray, lookupType: Int = 0): Bits {
        val bits = Bits()
        bits.write(0x564342, 24)
        bits.write(dimensions, 16)
        bits.write(lengths.size, 24)
        bits.write(0, 1)
        val sparse = lengths.any { it == 0 }
        bits.write(if (sparse) 1 else 0, 1)
        for (length in lengths) {
            if (sparse) {
                bits.write(if (length == 0) 0 else 1, 1)
                if (length == 0) continue
            }
            bits.write(length - 1, 5)
        }
        bits.write(lookupType, 4)
        return bits
    }

    private fun parse(bits: Bits): VorbisCodebook {
        val bytes = bits.toByteArray()
        return VorbisCodebook.parse(VorbisBitReader(bytes), DecodeOptions.DEFAULT, 0L)
    }

    @Test
    fun longCodewordSortingHasABoundedWorstCase() {
        val count = 4_096
        val codes = LongArray(count) { (count - it - 1).toLong() }
        val lengths = IntArray(count) { ((count - it - 1) % 31) + 1 }
        val entries = IntArray(count) { count - it - 1 }

        VorbisCodebook.sortByCode(codes, lengths, entries)

        assertContentEquals(LongArray(count) { it.toLong() }, codes)
        for (index in 0 until count) {
            assertEquals(index, entries[index])
            assertEquals(index % 31 + 1, lengths[index])
        }
    }

    @Test
    fun aCompleteBookIsAccepted() {
        // The specification's own worked example: four two-bit codewords.
        val decoded = parse(book(1, intArrayOf(2, 2, 2, 2)))
        assertEquals(4, decoded.entries, "entries")

        // 00, 01, 10, 11 in the order the assignment gives them out.
        val bits = Bits().write(0, 1).write(0, 1)
            .write(0, 1).write(1, 1)
            .write(1, 1).write(0, 1)
            .write(1, 1).write(1, 1)
        val reader = VorbisBitReader(bits.toByteArray())
        assertEquals(0, decoded.decodeScalar(reader), "the first codeword")
        assertEquals(1, decoded.decodeScalar(reader), "the second codeword")
        assertEquals(2, decoded.decodeScalar(reader), "the third codeword")
        assertEquals(3, decoded.decodeScalar(reader), "the fourth codeword")
    }

    /**
     * A tree with a hole in it is refused.
     *
     * Three two-bit codewords leave the pattern `11` meaning nothing. A decoder
     * that accepts the book has no answer when that pattern arrives.
     */
    @Test
    fun anUnderfullBookIsRefused() {
        val failure = assertFailsWith<CorruptStreamException> {
            parse(book(1, intArrayOf(2, 2, 2)))
        }
        assertTrue(
            failure.message!!.contains("leave part of"),
            "the error should say what is wrong: ${failure.message}",
        )
    }

    /** A tree with more codewords than it has room for is refused. */
    @Test
    fun anOverfullBookIsRefused() {
        assertFailsWith<CorruptStreamException> {
            parse(book(1, intArrayOf(1, 1, 2, 2)))
        }
    }

    /**
     * A book with one entry is the one shape allowed to be incomplete, and
     * reading from it consumes a bit whichever way that bit reads.
     *
     * Both spellings exist in files, which is why the format's errata says a
     * decoder has to accept either.
     */
    @Test
    fun aSingleEntryBookReadsEitherBit() {
        val decoded = parse(book(1, intArrayOf(1)))
        for (bit in 0..1) {
            val reader = VorbisBitReader(Bits().write(bit, 1).write(bit, 1).toByteArray())
            assertEquals(0, decoded.decodeScalar(reader), "a bit of $bit gives the only entry")
            assertEquals(1, reader.bitPosition, "one bit is consumed")
        }
    }

    /** A single-entry book whose codeword is not one bit long is refused. */
    @Test
    fun aSingleEntryBookWithALongCodewordIsRefused() {
        val failure = assertFailsWith<CorruptStreamException> {
            parse(book(1, intArrayOf(0, 5, 0, 0)))
        }
        assertTrue(
            failure.message!!.contains("one bit"),
            "the error should say what is wrong: ${failure.message}",
        )
    }

    /**
     * A book may end on a run of 32-bit codewords.
     *
     * The length counter passes 32 as it finishes such a book, and a decoder
     * that checks the counter rather than whether entries remain refuses a
     * perfectly legal book.
     */
    @Test
    fun aBookEndingOnTheLongestCodewordIsAccepted() {
        val bits = Bits()
        bits.write(0x564342, 24)
        bits.write(1, 16)
        bits.write(33, 24)
        bits.write(1, 1)
        bits.write(0, 5)
        // One codeword of each length from 1 to 31, then two of 32, which fills
        // the tree exactly.
        var remaining = 33
        for (length in 1..31) {
            bits.write(1, ilog(remaining))
            remaining--
        }
        bits.write(2, ilog(remaining))
        bits.write(0, 4)
        val decoded = parse(bits)
        assertEquals(33, decoded.entries, "entries")
    }

    /**
     * A book cannot declare more lookup values than the packet has bits.
     *
     * Without this, fifteen bytes of header ask for a gigabyte of memory and
     * four billion reads that all return zero.
     */
    @Test
    fun aBookDeclaringMoreValuesThanTheStreamHoldsIsRefused() {
        val bits = book(15, intArrayOf(1), lookupType = 2)
        bits.write(0, 32)
        bits.write(0, 32)
        bits.write(15, 4)
        bits.write(0, 1)
        val failure = assertFailsWith<CorruptStreamException> {
            // The book claims 15 values per entry with only one entry declared,
            // so the multiplicand table is far larger than what follows it.
            val bytes = bits.toByteArray()
            VorbisCodebook.parse(VorbisBitReader(bytes), DecodeOptions.DEFAULT, 0L)
        }
        assertTrue(
            failure.message!!.contains("bits left") || failure.message!!.contains("multiplicands"),
            "the error should say what is wrong: ${failure.message}",
        )
    }

    @Test
    fun codewordArraysAreGuardedBeforeTheFirstOneIsAllocated() {
        val bytes = book(1, intArrayOf(2, 2, 2, 2)).toByteArray()
        assertFailsWith<AllocationGuardException> {
            VorbisCodebook.parse(
                VorbisBitReader(bytes),
                DecodeOptions(maxAllocationBytes = 80),
                0L,
            )
        }
    }

    private fun ilog(value: Int): Int = if (value <= 0) 0 else 32 - value.countLeadingZeroBits()
}

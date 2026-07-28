package io.github.yuroyami.kiteaudio

import io.github.yuroyami.kiteaudio.internal.bits.BitReaderMsb
import io.github.yuroyami.kiteaudio.internal.bits.BitWriterMsb
import io.github.yuroyami.kiteaudio.internal.mpeg.Mp3Huffman
import io.github.yuroyami.kiteaudio.internal.mpeg.Mp3HuffmanTables
import io.github.yuroyami.kiteaudio.internal.mpeg.Mp3Tables
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks Layer III's constant tables against the properties they must have.
 *
 * These are not tests of the decoder. They are tests of the numbers it decodes
 * with, and they exist because a single wrong digit in one of them is silent:
 * the audio still comes out, slightly wrong, in a way no assertion about a
 * waveform reliably catches. Every property here is one the standard's own
 * structure guarantees, so a transcription error breaks at least one of them.
 */
class Mp3TablesTest {

    /**
     * Every code table is a complete prefix code.
     *
     * A Huffman table whose lengths sum to less than one has bit patterns that
     * decode to nothing, and one that sums to more than one has two values
     * sharing a codeword. Both mean a digit was copied wrongly.
     */
    @Test
    fun everyCodeTableIsAFullPrefixCode() {
        for (table in 0 until 34) {
            val lengths = Mp3HuffmanTables.TABLE_LENGTHS[table]
            val codes = Mp3HuffmanTables.TABLE_CODES[table]
            assertEquals(lengths.size, codes.size, "table $table: lengths and codes differ in size")
            if (lengths.isEmpty()) continue

            // Kraft's sum, in units of 2^-32 so it stays an exact integer.
            var kraft = 0L
            for (length in lengths) {
                assertTrue(length in 1..32, "table $table: a codeword is $length bits long")
                kraft += 1L shl (32 - length)
            }
            assertEquals(1L shl 32, kraft, "table $table: the code is not exactly full")

            // No codeword is a prefix of another, checked by comparing every
            // pair on the shorter one's length.
            for (i in lengths.indices) {
                for (j in i + 1 until lengths.size) {
                    val shorter = if (lengths[i] <= lengths[j]) i else j
                    val longer = if (shorter == i) j else i
                    val shift = lengths[longer] - lengths[shorter]
                    assertTrue(
                        (codes[longer] ushr shift) != codes[shorter],
                        "table $table: entry $shorter is a prefix of entry $longer",
                    )
                }
            }

            // A codeword must fit in the length it declares.
            for (i in lengths.indices) {
                val limit = if (lengths[i] >= 32) -1L else (1L shl lengths[i]) - 1L
                if (limit >= 0) {
                    assertTrue(
                        codes[i].toLong() and 0xFFFF_FFFFL <= limit,
                        "table $table: entry $i does not fit in ${lengths[i]} bits",
                    )
                }
            }
        }
    }

    /** The declared dimensions have to match how many entries there are. */
    @Test
    fun tableDimensionsMatchTheirContents() {
        for (table in 0 until 34) {
            val entries = Mp3HuffmanTables.TABLE_LENGTHS[table].size
            val width = Mp3HuffmanTables.XLEN[table]
            if (entries == 0) {
                assertEquals(0, width, "table $table is empty and should declare no width")
                continue
            }
            if (table >= 32) {
                // The count1 tables code four values at a time, sixteen ways.
                assertEquals(16, entries, "table $table: quadruple tables hold sixteen entries")
                continue
            }
            assertEquals(width * width, entries, "table $table: entries do not match its width")
        }
    }

    /**
     * Decoding a codeword returns the value pair it was written for.
     *
     * This walks every entry of every table through the decoder, which is the
     * only check that the fast lookup and the tree it was built from agree, and
     * the only one that covers the escape and sign bits.
     */
    @Test
    fun everyCodewordDecodesBackToItsPair() {
        val out = IntArray(4)
        for (table in 1 until 32) {
            val lengths = Mp3HuffmanTables.TABLE_LENGTHS[table]
            if (lengths.isEmpty()) continue
            val codes = Mp3HuffmanTables.TABLE_CODES[table]
            val width = Mp3HuffmanTables.XLEN[table]
            val linbits = Mp3HuffmanTables.LINBITS[table]

            for (index in lengths.indices) {
                val x = index / width
                val y = index % width
                // The largest value the escape can carry, which is the one
                // most likely to expose a wrong linbits count.
                val extra = (1 shl linbits) - 1
                val writer = BitWriterMsb()
                writer.writeBits(codes[index], lengths[index])
                // An escaped value carries its extra bits and then its sign.
                if (linbits > 0 && x == 15) writer.writeBits(extra, linbits)
                if (x != 0) writer.writeBits(1, 1)
                if (linbits > 0 && y == 15) writer.writeBits(extra, linbits)
                if (y != 0) writer.writeBits(0, 1)
                // Pad so the reader never runs out mid-codeword.
                writer.writeBits(0, 32)
                writer.alignToByte()

                val reader = BitReaderMsb(writer.toByteArray())
                assertTrue(
                    Mp3Huffman.decodePair(reader, table, out),
                    "table $table entry $index did not decode",
                )
                val expectedX = if (linbits > 0 && x == 15) -(15 + extra) else -x
                val expectedY = if (linbits > 0 && y == 15) 15 + extra else y
                assertEquals(expectedX, out[0], "table $table entry $index: first value")
                assertEquals(expectedY, out[1], "table $table entry $index: second value")
            }
        }
    }

    /** The quadruple tables code four values, most significant bit first. */
    @Test
    fun quadrupleTablesDecodeFourValues() {
        val out = IntArray(4)
        for (table in 0..1) {
            val id = 32 + table
            val lengths = Mp3HuffmanTables.TABLE_LENGTHS[id]
            val codes = Mp3HuffmanTables.TABLE_CODES[id]
            for (index in lengths.indices) {
                val writer = BitWriterMsb()
                writer.writeBits(codes[index], lengths[index])
                // One sign bit per nonzero value, all of them negative.
                repeat(countOneBits(index)) { writer.writeBits(1, 1) }
                writer.writeBits(0, 32)
                writer.alignToByte()

                val reader = BitReaderMsb(writer.toByteArray())
                assertTrue(Mp3Huffman.decodeQuad(reader, table, out), "table $id entry $index")
                for (i in 0 until 4) {
                    val bit = (index ushr (3 - i)) and 1
                    assertEquals(-bit, out[i], "table $id entry $index: value $i")
                }
            }
        }
    }

    /**
     * The synthesis window's values are exact sixteenths of a thousandth.
     *
     * Every number the standard prints for it is a whole multiple of 1/65536,
     * and the nine decimals it prints are that value rounded. Holding the
     * numerators means the table cannot drift, and this checks the property
     * that makes that legitimate.
     */
    @Test
    fun theSynthesisWindowIsExact() {
        val window = Mp3Tables.SYNTHESIS_WINDOW
        assertEquals(512, window.size, "the window holds 512 coefficients")
        for (i in window.indices) {
            val scaled = window[i] * 65536.0
            assertTrue(
                abs(scaled - kotlin.math.round(scaled)) < 1e-9,
                "D[$i] = ${window[i]} is not a whole multiple of 1/65536",
            )
        }
        assertEquals(0.0, window[0], "D[0] is zero")
        for (i in 1..63) {
            assertTrue(window[i] < 0.0, "D[$i] should be negative and is ${window[i]}")
        }
        assertEquals(75038.0 / 65536.0, window[256], "the peak is at D[256]")
        // The two halves mirror each other in magnitude.
        for (i in 1..255) {
            assertEquals(
                abs(window[i]), abs(window[512 - i]), 1e-12,
                "D[$i] and D[${512 - i}] should have the same size",
            )
        }
    }

    /**
     * The scalefactor bands cover the granule exactly.
     *
     * A band table that starts anywhere but zero, ends anywhere but the end, or
     * goes backwards puts the scalefactors on the wrong lines.
     */
    @Test
    fun scalefactorBandsSpanTheGranule() {
        for (rate in intArrayOf(44_100, 48_000, 32_000, 22_050, 24_000, 16_000, 11_025, 12_000, 8_000)) {
            val long = Mp3Tables.longBands(rate)
            assertEquals(23, long.size, "$rate Hz: long bands")
            assertEquals(0, long.first(), "$rate Hz: long bands start at zero")
            assertEquals(576, long.last(), "$rate Hz: long bands end at 576")
            for (i in 1 until long.size) {
                assertTrue(long[i] >= long[i - 1], "$rate Hz: long band $i goes backwards")
            }

            val short = Mp3Tables.shortBands(rate)
            assertEquals(14, short.size, "$rate Hz: short bands")
            assertEquals(0, short.first(), "$rate Hz: short bands start at zero")
            assertEquals(192, short.last(), "$rate Hz: short bands end at 192")
            for (i in 1 until short.size) {
                assertTrue(short[i] >= short[i - 1], "$rate Hz: short band $i goes backwards")
            }

            // A mixed block's two halves have to meet exactly, and the long
            // half has to be a whole number of subbands.
            val lines = Mp3Tables.mixedLongLines(rate)
            assertEquals(
                lines, long[Mp3Tables.mixedLongBands(rate)],
                "$rate Hz: a mixed block's long and short halves do not meet",
            )
            assertEquals(0, lines % 18, "$rate Hz: a mixed block's long half is not whole subbands")
        }
    }

    /** MPEG-2's scalefactor counts have to add up to a whole granule's worth. */
    @Test
    fun theMpeg2ScalefactorCountsAddUp() {
        for (block in 0 until 6) {
            assertEquals(21, Mp3Tables.lsfCounts(block, isShort = false, isMixed = false).sum(),
                "block $block: a long granule holds 21 scalefactors")
            assertEquals(36, Mp3Tables.lsfCounts(block, isShort = true, isMixed = false).sum(),
                "block $block: a short granule holds 36")
            assertEquals(33, Mp3Tables.lsfCounts(block, isShort = true, isMixed = true).sum(),
                "block $block: a mixed granule holds 33")
        }
    }

    /** The alias coefficients are the pair the standard's own ci implies. */
    @Test
    fun theAliasCoefficientsAreConsistent() {
        for (i in 0 until 8) {
            val cs = Mp3Tables.ALIAS_CS[i]
            val ca = Mp3Tables.ALIAS_CA[i]
            assertEquals(1.0, cs * cs + ca * ca, 1e-12, "butterfly $i is not a rotation")
            assertTrue(ca < 0.0, "butterfly $i: every ci the standard prints is negative")
        }
        // The last one is -0.0037, not the -0.0032 one widely copied source has.
        assertEquals(-0.0037, Mp3Tables.ALIAS_CA[7] / Mp3Tables.ALIAS_CS[7], 1e-9, "ci[7]")
    }

    private fun countOneBits(value: Int): Int {
        var n = 0
        for (i in 0 until 4) if ((value ushr i) and 1 != 0) n++
        return n
    }
}

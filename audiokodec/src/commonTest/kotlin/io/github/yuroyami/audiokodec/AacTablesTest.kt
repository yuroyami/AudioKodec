package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.aac.AacTables
import io.github.yuroyami.audiokodec.internal.aac.AacWindows
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the properties AAC's constant tables must have, rather than their
 * digits: a wrong digit in a codebook breaks completeness or prefix
 * freedom, and a wrong band offset breaks monotony or the multiple-of-four
 * widths codewords rely on. The digits themselves were agreed with a second
 * rendering of the tables when they were generated; see [AacTables].
 */
class AacTablesTest {

    @Test
    fun everyCodebookIsACompletePrefixCodeOfTheRightSize() {
        // Table 4.151: dimension and largest absolute value by codebook, and
        // 121 scalefactor differences for codebook 0.
        val sizes = intArrayOf(121, 81, 81, 81, 81, 81, 81, 64, 64, 169, 169, 289)
        for (book in 0 until 12) {
            val lengths = AacTables.LENGTHS[book]
            val codes = AacTables.CODES[book]
            assertEquals(sizes[book], lengths.size, "codebook $book")
            assertEquals(lengths.size, codes.size, "codebook $book")
            // Kraft's sum is exactly one, in units of the longest codeword.
            val longest = lengths.max()
            var kraft = 0L
            for (length in lengths) kraft += 1L shl (longest - length)
            assertEquals(1L shl longest, kraft, "codebook $book is not complete")
            val words = lengths.indices.map { i ->
                assertTrue(codes[i] < (1 shl lengths[i]), "codebook $book, index $i: the codeword is longer than its length")
                codes[i].toString(2).padStart(lengths[i], '0')
            }.sorted()
            for (k in 1 until words.size) {
                assertTrue(!words[k].startsWith(words[k - 1]), "codebook $book: ${words[k - 1]} is a prefix of ${words[k]}")
            }
        }
        // The scalefactor codebook's shortest codeword is the zero difference.
        assertEquals(1, AacTables.LENGTHS[0][60])
    }

    @Test
    fun bandOffsetsCoverEveryWindowInWholeQuadruples() {
        val longBands = intArrayOf(41, 41, 47, 49, 49, 51, 47, 47, 43, 43, 43, 40, 40)
        val shortBands = intArrayOf(12, 12, 12, 14, 14, 14, 15, 15, 15, 15, 15, 15, 15)
        for (index in 0 until 13) {
            for ((tables, length, bands) in listOf(Triple(AacTables.SWB_LONG, 1024, longBands), Triple(AacTables.SWB_SHORT, 128, shortBands))) {
                val offsets = tables[index]
                assertEquals(bands[index] + 1, offsets.size, "rate index $index, $length lines")
                assertEquals(0, offsets.first())
                assertEquals(length, offsets.last())
                for (b in 1 until offsets.size) {
                    val width = offsets[b] - offsets[b - 1]
                    assertTrue(width > 0 && width % 4 == 0, "rate index $index, $length lines, band $b is $width wide")
                    // Bands never narrow as frequency rises.
                    if (b > 1) assertTrue(width >= offsets[b - 1] - offsets[b - 2], "rate index $index, $length lines, band $b narrows")
                }
            }
            assertTrue(AacTables.TNS_MAX_BANDS_LONG[index] <= longBands[index])
            assertTrue(AacTables.TNS_MAX_BANDS_SHORT[index] <= shortBands[index])
        }
    }

    @Test
    fun windowsMeetThePrincenBradleyConditionAndPacketVideosTables() {
        for (window in listOf(AacWindows.SINE_LONG, AacWindows.SINE_SHORT, AacWindows.KBD_LONG, AacWindows.KBD_SHORT)) {
            val half = window.size
            for (n in 0 until half) {
                // The rising half and the mirrored falling half overlap to unit power.
                val power = window[n].toDouble() * window[n] + window[half - 1 - n].toDouble() * window[half - 1 - n]
                assertTrue(abs(power - 1.0) < 1e-6, "power $power at $n of $half")
                if (n > 0) assertTrue(window[n] >= window[n - 1], "the window falls at $n of $half")
            }
        }
        // The first Q15 values of the PacketVideo decoder's Kaiser-Bessel tables,
        // which agree with the computed windows to within one step everywhere.
        val q15 = listOf(
            AacWindows.KBD_LONG to intArrayOf(0x000A, 0x000E, 0x0012, 0x0015, 0x0019, 0x001C, 0x0020, 0x0023),
            AacWindows.KBD_SHORT to intArrayOf(0x0001, 0x0004, 0x0008, 0x000D, 0x0014, 0x001D, 0x0029, 0x0039),
            AacWindows.SINE_LONG to intArrayOf(0x0019, 0x004B, 0x007E, 0x00B0, 0x00E2, 0x0114, 0x0147, 0x0179),
        )
        for ((window, expected) in q15) {
            for (n in expected.indices) {
                assertTrue(abs((window[n] * 32768).roundToInt() - expected[n]) <= 1, "Q15 value $n: ${window[n] * 32768} for ${expected[n]}")
            }
        }
    }
}

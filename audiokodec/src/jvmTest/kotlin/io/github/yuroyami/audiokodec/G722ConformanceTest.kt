package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.codec.G722Decoder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue

/**
 * Runs the G.722 sub-band decoders over the digital test sequences of
 * Appendix II to the Recommendation, in its Configuration 2: the receive QMF
 * bypassed, the lower sub-band decoded in each of the three modes and the
 * higher sub-band once, for the encoder's output on tones, d.c. and noise,
 * for its output on the overflow sequence, and for an artificial sequence
 * that drives the predictors over their whole range.
 *
 * The sequences are fetched from the ITU by CI into
 * `reference/g72x/g722-appii`; see `reference/REFERENCES.md`.
 */
class G722ConformanceTest {

    @Test
    fun everyTestSequenceDecodesToItsOutputWords() {
        val root = root()
        var compared = 0
        for ((input, number) in listOf("T2R1.COD" to 1, "T2R2.COD" to 2, "T1D3.COD" to 3)) {
            val words = read(File(root, input))
            val expected = listOf(1, 2, 3).associateWith { read(File(root, "T3L$number.RC$it")) }
            val high = read(File(root, "T3H$number.RC0"))
            for (mode in 1..3) {
                val decoder = G722Decoder(mode)
                val low = expected.getValue(mode)
                assertEquals(words.size, low.size, input)
                for (n in words.indices) {
                    // INFC and INFD: the reset bit, the code words, and the outputs scaled by a one-bit shift.
                    val word = words[n]
                    val reset = word and 1
                    val (rl, rh) = if (reset == 1) {
                        decoder.reset()
                        0 to 0
                    } else {
                        decoder.decodeSubBands((word ushr 8) and 63, word ushr 14)
                        decoder.lowOutput to decoder.highOutput
                    }
                    val lowWord = ((rl shl 1) + reset) and 0xFFFF
                    if (lowWord != low[n]) {
                        throw AssertionError("T3L$number.RC$mode: word $n is ${lowWord.toString(16)}, not ${low[n].toString(16)}")
                    }
                    val highWord = ((rh shl 1) + reset) and 0xFFFF
                    if (mode == 1 && highWord != high[n]) {
                        throw AssertionError("T3H$number.RC0: word $n is ${highWord.toString(16)}, not ${high[n].toString(16)}")
                    }
                }
                compared++
            }
        }
        assertEquals(9, compared)
    }

    /** The 16-bit words of a sequence file: comment lines, then lines of sixteen words and a checksum. */
    private fun read(file: File): IntArray {
        val words = ArrayList<Int>()
        for (line in file.readText().split('\n').map { it.trim() }) {
            if (line.isEmpty() || line.startsWith("/*") || line.all { it == '\u001a' }) continue
            assertEquals(66, line.length, "${file.name}: line length")
            for (i in 0 until 16) words += line.substring(4 * i, 4 * i + 4).toInt(16)
            // Each line ends with the byte that brings the sum of its 64 characters' codes to a multiple of 256.
            val sum = line.take(64).sumOf { it.code }
            assertEquals(0, (sum + line.substring(64).toInt(16)) and 0xFF, "${file.name}: checksum")
        }
        return words.toIntArray()
    }

    private fun root(): File {
        val dir = File(System.getProperty("user.dir"))
        val root = listOf(dir, dir.parentFile)
            .map { File(it, "reference/g72x/g722-appii") }
            .firstOrNull { File(it, "T1D3.COD").isFile }
        assumeTrue("the G.722 Appendix II test sequences are required, under reference/g72x/g722-appii", root != null)
        return root!!
    }
}

package io.github.yuroyami.audiokodec.internal.mpeg.encoder

import io.github.yuroyami.audiokodec.internal.mpeg.Mp3Tables
import kotlin.math.PI
import kotlin.math.cos

/**
 * The analysis filterbank, which splits audio into thirty-two subbands.
 *
 * This is the mirror of the decoder's [io.github.yuroyami.audiokodec.internal.mpeg.PolyphaseFilter].
 * Thirty-two samples go in and one value per subband comes out, each a
 * windowed sum over the last 512 samples. The window is the same prototype
 * low-pass filter the synthesis side uses: ISO/IEC 11172-3 prints it twice,
 * as C in Annex C and as D in Table 3-B.3, and the two differ only by the
 * factor of 32 that the synthesis side's interpolation needs. Deriving C from
 * the decoder's D instead of typing it again means the two cannot disagree.
 *
 * The filterbank and the decoder's together delay the signal by
 * [DELAY] samples, which a test checks by running one into the other.
 *
 * Written from ISO/IEC 11172-3 Annex C, the analysis subband filter.
 */
internal class Mp3AnalysisFilter(channels: Int) {

    /** The last 512 samples of each channel, newest at [historyAt]. */
    private val history = Array(channels) { DoubleArray(512) }
    private val historyAt = IntArray(channels)
    private val partial = DoubleArray(64)

    fun reset() {
        for (channel in history.indices) {
            history[channel].fill(0.0)
            historyAt[channel] = 0
        }
    }

    /**
     * Takes thirty-two samples, oldest first, from [input] at [offset] and
     * writes the thirty-two subband values to [out] at
     * `outOffset + subband * outStride`.
     */
    fun step(input: DoubleArray, offset: Int, channel: Int, out: DoubleArray, outOffset: Int, outStride: Int) {
        val buffer = history[channel]
        // The standard shifts the whole buffer along by thirty-two and puts the
        // newest sample at X[0]. Moving the start instead does the same.
        val at = (historyAt[channel] - 32) and 511
        for (k in 0 until 32) buffer[(at + 31 - k) and 511] = input[offset + k]
        historyAt[channel] = at

        // Window, then fold the 512 products into 64 partial sums.
        val window = WINDOW
        for (i in 0 until 64) {
            var sum = 0.0
            var j = i
            while (j < 512) {
                sum += window[j] * buffer[(at + j) and 511]
                j += 64
            }
            partial[i] = sum
        }

        // Matrixing: 64 partial sums into 32 subbands.
        val matrix = MATRIX
        for (subband in 0 until 32) {
            val row = subband * 64
            var s0 = 0.0
            var s1 = 0.0
            var i = 0
            while (i < 64) {
                s0 += matrix[row + i] * partial[i]
                s1 += matrix[row + i + 1] * partial[i + 1]
                i += 2
            }
            out[outOffset + subband * outStride] = s0 + s1
        }
    }

    companion object {

        /**
         * Samples by which the analysis and synthesis filterbanks together
         * delay the signal: the prototype filter's 512 taps less the 31 that
         * the block of 32 shifts by.
         */
        const val DELAY: Int = 481

        /** Annex C's analysis window C, from Table 3-B.3's D. */
        private val WINDOW = DoubleArray(512) { Mp3Tables.SYNTHESIS_WINDOW[it] / 32.0 }

        /** Annex C's matrixing coefficients, `cos((2i + 1)(k - 16) pi / 64)`. */
        private val MATRIX = DoubleArray(32 * 64) { n ->
            val i = n / 64
            val k = n % 64
            cos((2 * i + 1) * (k - 16) * PI / 64.0)
        }
    }
}

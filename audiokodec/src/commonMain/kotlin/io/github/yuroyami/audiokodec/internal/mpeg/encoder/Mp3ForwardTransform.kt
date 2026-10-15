package io.github.yuroyami.audiokodec.internal.mpeg.encoder

import io.github.yuroyami.audiokodec.internal.mpeg.Mp3Tables
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns a granule's subband samples into its 576 spectral values.
 *
 * Every subband holds eighteen samples per granule, and the transform runs
 * over thirty-six of them: the previous granule's eighteen and this one's.
 * That overlap is what lets the decoder cancel the time-domain aliasing the
 * transform introduces, as long as both granules used windows whose
 * overlapping halves match. The block types exist for that reason: a start
 * block (1) narrows its right half to meet a short block (2), and a stop
 * block (3) widens back out from one.
 *
 * Three steps undo, in reverse, what the decoder's
 * [io.github.yuroyami.audiokodec.internal.mpeg.Mp3Synthesis] does: every
 * other sample of every odd subband is negated, because the analysis
 * filterbank leaves those subbands' spectra mirrored; the windowed MDCT; and,
 * for long blocks, the alias reduction butterflies, whose inverse the decoder
 * applies. A short block is three twelve-point transforms staggered across
 * the middle of the thirty-six samples, with its values left in the
 * transform's order, six per window per subband; [toBitstreamOrder] then
 * groups them by scalefactor band as the bitstream wants.
 *
 * The scaling is the one that makes the decoder's unscaled inverse give back
 * what went in: a ninth for long blocks and a third for short ones, which a
 * test checks for all four block types.
 *
 * Written from ISO/IEC 11172-3 Annex C, the Layer III MDCT and window
 * switching.
 */
internal class Mp3ForwardTransform(channels: Int) {

    /** Each channel's previous granule of subband samples, `subband * 18 + time`. */
    private val previous = Array(channels) { DoubleArray(576) }
    private val block = DoubleArray(36)

    fun reset() {
        for (channel in previous) channel.fill(0.0)
    }

    /**
     * Transforms one granule of [subbands], laid out `subband * 18 + time`,
     * into [out] using [blockType]. Long blocks come out in frequency order;
     * short blocks in transform order, see [toBitstreamOrder].
     */
    fun transform(subbands: DoubleArray, channel: Int, blockType: Int, out: DoubleArray) {
        val past = previous[channel]
        for (subband in 0 until 32) {
            val base = subband * 18
            val odd = subband and 1 != 0
            for (t in 0 until 18) {
                val sign = if (odd && t and 1 != 0) -1.0 else 1.0
                block[t] = past[base + t]
                val now = subbands[base + t] * sign
                block[t + 18] = now
                past[base + t] = now
            }
            if (blockType == SHORT) shortTransform(out, base) else longTransform(out, base, blockType)
        }
        if (blockType != SHORT) reduceAlias(out)
    }

    private fun longTransform(out: DoubleArray, base: Int, blockType: Int) {
        val window = WINDOWS[blockType]
        for (i in 0 until 36) block[i] *= window[i]
        for (k in 0 until 18) {
            val row = COS36[k]
            var even = 0.0
            var odd = 0.0
            var i = 0
            while (i < 36) {
                even += block[i] * row[i]
                odd += block[i + 1] * row[i + 1]
                i += 2
            }
            out[base + k] = (even + odd) * (1.0 / 9.0)
        }
    }

    private fun shortTransform(out: DoubleArray, base: Int) {
        for (window in 0 until 3) {
            val from = 6 + window * 6
            for (k in 0 until 6) {
                val row = COS12[k]
                var sum = 0.0
                for (i in 0 until 12) sum += block[from + i] * SHORT_WINDOW[i] * row[i]
                out[base + window * 6 + k] = sum * (1.0 / 3.0)
            }
        }
    }

    /** The butterflies the decoder's alias reduction undoes. */
    private fun reduceAlias(spectrum: DoubleArray) {
        val cs = Mp3Tables.ALIAS_CS
        val ca = Mp3Tables.ALIAS_CA
        for (boundary in 1 until 32) {
            val centre = 18 * boundary
            for (i in 0 until 8) {
                val lower = spectrum[centre - 1 - i]
                val upper = spectrum[centre + i]
                spectrum[centre - 1 - i] = lower * cs[i] + upper * ca[i]
                spectrum[centre + i] = upper * cs[i] - lower * ca[i]
            }
        }
    }

    companion object {

        const val NORMAL: Int = 0
        const val START: Int = 1
        const val SHORT: Int = 2
        const val STOP: Int = 3

        /**
         * Puts a short granule's values, which [transform] leaves six per
         * window per subband, into the bitstream's order: each scalefactor
         * band's lines for window 0, then window 1, then window 2.
         */
        fun toBitstreamOrder(transformed: DoubleArray, shortBands: IntArray, out: DoubleArray) {
            var target = 0
            for (band in 0 until 13) {
                for (window in 0 until 3) {
                    for (line in shortBands[band] until shortBands[band + 1]) {
                        out[target++] = transformed[(line / 6) * 18 + window * 6 + line % 6]
                    }
                }
            }
        }

        /** `cos(pi / 72 (2i + 1 + 18)(2k + 1))`, the long kernel, by k then i. */
        private val COS36 = Array(18) { k ->
            DoubleArray(36) { i -> cos(PI / 72.0 * (2 * i + 1 + 18) * (2 * k + 1)) }
        }

        /** The short kernel, by k then i. */
        private val COS12 = Array(6) { k ->
            DoubleArray(12) { i -> cos(PI / 24.0 * (2 * i + 1 + 6) * (2 * k + 1)) }
        }

        private val SHORT_WINDOW = DoubleArray(12) { sin(PI / 12.0 * (it + 0.5)) }

        /** The windows of block types 0, 1 and 3; index 2 is unused. */
        private val WINDOWS = Array(4) { type ->
            DoubleArray(36) { i ->
                when (type) {
                    START -> when {
                        i < 18 -> sin(PI / 36.0 * (i + 0.5))
                        i < 24 -> 1.0
                        i < 30 -> sin(PI / 12.0 * (i - 18 + 0.5))
                        else -> 0.0
                    }
                    STOP -> when {
                        i < 6 -> 0.0
                        i < 12 -> sin(PI / 12.0 * (i - 6 + 0.5))
                        i < 18 -> 1.0
                        else -> sin(PI / 36.0 * (i + 0.5))
                    }
                    else -> sin(PI / 36.0 * (i + 0.5))
                }
            }
        }
    }
}

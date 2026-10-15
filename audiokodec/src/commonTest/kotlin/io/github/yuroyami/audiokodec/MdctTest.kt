package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.dsp.Fft
import io.github.yuroyami.audiokodec.internal.dsp.ForwardMdct
import io.github.yuroyami.audiokodec.internal.dsp.InverseMdct
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Checks the transforms against their definitions.
 *
 * A fast transform is a rearrangement of a sum anyone can write down, and the
 * rearrangement is where the mistakes live. So both are computed the slow
 * obvious way here and the fast one has to agree.
 */
class MdctTest {

    private fun pseudoRandom(seed: Int, count: Int): FloatArray {
        // A fixed generator, so a failure is the same failure on every target.
        var state = seed
        return FloatArray(count) {
            state = state * 1_664_525 + 1_013_904_223
            ((state ushr 8).toFloat() / (1 shl 23).toFloat()) - 1f
        }
    }

    private fun directMdct(input: FloatArray, n: Int): FloatArray {
        val m = n / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            var sum = 0.0
            for (k in 0 until m) {
                sum += input[k] * cos(PI / m * (i + 0.5 + n / 4.0) * (k + 0.5))
            }
            out[i] = sum.toFloat()
        }
        return out
    }

    @Test
    fun theFastInverseMdctAgreesWithItsDefinition() {
        for (n in listOf(8, 16, 64, 128, 256, 512, 2048)) {
            val input = pseudoRandom(n + 7, n / 2)
            val expected = directMdct(input, n)
            val actual = FloatArray(n)
            InverseMdct(n).transform(input, actual, 1f)

            var worst = 0f
            var scale = 1e-6f
            for (i in 0 until n) {
                worst = maxOf(worst, abs(actual[i] - expected[i]))
                scale = maxOf(scale, abs(expected[i]))
            }
            assertTrue(
                worst < scale * 1e-4f,
                "inverse MDCT of length $n is off by $worst against a peak of $scale",
            )
        }
    }

    @Test
    fun theInverseMdctScalesLinearly() {
        val n = 256
        val input = pseudoRandom(3, n / 2)
        val one = FloatArray(n)
        val seven = FloatArray(n)
        val mdct = InverseMdct(n)
        mdct.transform(input, one, 1f)
        mdct.transform(input, seven, 7f)
        for (i in 0 until n) {
            assertTrue(
                abs(seven[i] - 7f * one[i]) < 1e-3f,
                "scaling is not linear at $i: ${seven[i]} against ${7f * one[i]}",
            )
        }
    }

    /**
     * A single coefficient has to come back as a single cosine.
     *
     * This is the property the whole transform exists for, and it catches the
     * kind of index error that a random-input comparison can hide when both
     * implementations are wrong in the same way.
     */
    @Test
    fun oneCoefficientBecomesOneCosine() {
        val n = 64
        val m = n / 2
        for (k in listOf(0, 1, 7, m - 1)) {
            val input = FloatArray(m)
            input[k] = 1f
            val out = FloatArray(n)
            InverseMdct(n).transform(input, out, 1f)
            for (i in 0 until n) {
                val want = cos(PI / m * (i + 0.5 + n / 4.0) * (k + 0.5)).toFloat()
                assertTrue(
                    abs(out[i] - want) < 1e-4f,
                    "coefficient $k gave ${out[i]} at $i, wanted $want",
                )
            }
        }
    }

    @Test
    fun theFftAgreesWithItsDefinition() {
        for (size in listOf(2, 4, 16, 64, 256)) {
            val re = pseudoRandom(size, size)
            val im = pseudoRandom(size * 3, size)
            for (forward in listOf(true, false)) {
                val sign = if (forward) -1.0 else 1.0
                val wantRe = DoubleArray(size)
                val wantIm = DoubleArray(size)
                for (p in 0 until size) {
                    for (j in 0 until size) {
                        val angle = sign * 2.0 * PI * p * j / size
                        wantRe[p] += re[j] * cos(angle) - im[j] * sin(angle)
                        wantIm[p] += re[j] * sin(angle) + im[j] * cos(angle)
                    }
                }
                val gotRe = re.copyOf()
                val gotIm = im.copyOf()
                Fft(size, forward).transform(gotRe, gotIm)
                for (p in 0 until size) {
                    assertTrue(
                        abs(gotRe[p] - wantRe[p]) < size * 1e-4 &&
                            abs(gotIm[p] - wantIm[p]) < size * 1e-4,
                        "FFT size $size forward=$forward differs at $p: " +
                            "(${gotRe[p]}, ${gotIm[p]}) against (${wantRe[p]}, ${wantIm[p]})",
                    )
                }
            }
        }
    }

    @Test
    fun theFastForwardMdctAgreesWithItsDefinition() {
        for (n in listOf(8, 16, 64, 256, 2048)) {
            val input = pseudoRandom(n + 11, n + 3)
            val m = n / 2
            val actual = FloatArray(m)
            ForwardMdct(n).transform(input, 3, actual, 1f)
            var worst = 0.0
            var scale = 1e-6
            for (k in 0 until m) {
                var sum = 0.0
                for (i in 0 until n) sum += input[3 + i] * cos(PI / m * (i + 0.5 + n / 4.0) * (k + 0.5))
                worst = maxOf(worst, abs(actual[k] - sum))
                scale = maxOf(scale, abs(sum))
            }
            assertTrue(worst < scale * 1e-4, "forward MDCT of length $n is off by $worst against a peak of $scale")
        }
    }

    /**
     * Windowing, the forward transform scaled by `4 / n`, the inverse unscaled,
     * windowing again and overlap-adding gives back the signal, which is the
     * contract the Vorbis encoder relies on to mirror the decoder.
     */
    @Test
    fun theForwardAndInverseMdctReconstructWithOverlapAdd() {
        for (n in listOf(16, 256, 2048)) {
            val hop = n / 2
            val signal = pseudoRandom(n, hop * 8)
            val window = FloatArray(n) {
                val inner = sin((it + 0.5) / n * PI)
                sin(PI / 2 * inner * inner).toFloat()
            }
            val output = FloatArray(signal.size)
            val forward = ForwardMdct(n)
            val inverse = InverseMdct(n)
            val block = FloatArray(n)
            val coefficients = FloatArray(hop)
            val time = FloatArray(n)
            for (start in 0..signal.size - n step hop) {
                for (i in 0 until n) block[i] = signal[start + i] * window[i]
                forward.transform(block, 0, coefficients, 4f / n)
                inverse.transform(coefficients, time, 1f)
                for (i in 0 until n) output[start + i] += time[i] * window[i]
            }
            for (i in hop until signal.size - hop) {
                assertTrue(
                    abs(output[i] - signal[i]) < 1e-4f,
                    "length $n: sample $i came back as ${output[i]}, not ${signal[i]}",
                )
            }
        }
    }
}

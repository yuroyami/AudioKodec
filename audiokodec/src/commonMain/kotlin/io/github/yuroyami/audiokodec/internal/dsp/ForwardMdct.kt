package io.github.yuroyami.audiokodec.internal.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The forward modified discrete cosine transform, the exact mirror of
 * [InverseMdct].
 *
 * It computes
 *
 * ```
 * X[k] = scale * sum over i of x[i] * cos( (pi / (n/2)) * (i + 1/2 + n/4) * (k + 1/2) )
 * ```
 *
 * for `k` in `0 until n/2`, which is the same kernel [InverseMdct] uses, read
 * the other way: as matrices, one transform is the transpose of the other.
 * [InverseMdct] is a DCT-IV followed by an unfolding of its `n/2` outputs into
 * `n`, so this is a folding of the `n` inputs into `n/2` followed by the same
 * DCT-IV, through the same complex FFT a quarter the length.
 *
 * With a window whose squares on either side of an overlap add to one, and
 * [scale] at `4 / n`, windowing, this, [InverseMdct] unscaled, windowing again
 * and adding the overlapping halves gives back the input exactly, up to
 * rounding. That is the scaling Vorbis expects of an encoder, because its
 * decoder applies none.
 */
internal class ForwardMdct(
    /** Input length. A power of two of at least 8. */
    val n: Int,
) {

    init {
        require(n >= 8 && n and (n - 1) == 0) {
            "a forward MDCT is a power of two of at least 8, was $n"
        }
    }

    private val m = n / 2
    private val quarter = n / 4
    private val fft = Fft(quarter, forward = false)
    private val twiddleCos = FloatArray(quarter)
    private val twiddleSin = FloatArray(quarter)
    private val re = FloatArray(quarter)
    private val im = FloatArray(quarter)
    private val folded = FloatArray(m)

    init {
        for (j in 0 until quarter) {
            val angle = PI / m * (j + 0.125)
            twiddleCos[j] = cos(angle).toFloat()
            twiddleSin[j] = sin(angle).toFloat()
        }
    }

    /**
     * Transforms the [n] samples of [input] starting at [from] into `n/2`
     * coefficients in [output], scaled by [scale].
     */
    fun transform(input: FloatArray, from: Int, output: FloatArray, scale: Float) {
        require(from >= 0 && input.size - from >= n) { "the transform needs $n samples from $from" }
        require(output.size >= m) { "the transform writes $m coefficients, got room for ${output.size}" }

        // Fold: the transpose of the unfolding at the end of InverseMdct. Each
        // of the m values gathers the two samples the inverse writes it to.
        for (j in 0 until quarter) {
            folded[j] = -input[from + 3 * quarter - 1 - j] - input[from + 3 * quarter + j]
        }
        for (j in quarter until m) {
            folded[j] = input[from + j - quarter] - input[from + 3 * quarter - 1 - j]
        }

        // The DCT-IV, step for step as InverseMdct computes it.
        for (j in 0 until quarter) {
            val a = folded[2 * j]
            val b = -folded[m - 1 - 2 * j]
            val wr = twiddleCos[j]
            val wi = twiddleSin[j]
            re[j] = a * wr - b * wi
            im[j] = a * wi + b * wr
        }
        fft.transform(re, im)
        for (p in 0 until quarter) {
            val wr = twiddleCos[p]
            val wi = twiddleSin[p]
            val ar = re[p]
            val ai = im[p]
            output[2 * p] = scale * (ar * wr - ai * wi)
            output[m - 1 - 2 * p] = scale * (ar * wi + ai * wr)
        }
    }
}

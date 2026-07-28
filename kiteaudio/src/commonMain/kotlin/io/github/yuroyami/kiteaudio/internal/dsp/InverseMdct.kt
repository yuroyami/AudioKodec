package io.github.yuroyami.kiteaudio.internal.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The inverse modified discrete cosine transform.
 *
 * Every transform codec worth the name is built on this: the encoder turns
 * overlapping blocks of samples into half as many coefficients, and the decoder
 * turns them back into overlapping blocks that add up to the original. Vorbis,
 * AAC, AC-3 and the music half of Opus all do it, which is why this lives in
 * `dsp` rather than beside one codec.
 *
 * The transform computed here is
 *
 * ```
 * y[i] = sum over k of X[k] * cos( (pi / (n/2)) * (i + 1/2 + n/4) * (k + 1/2) )
 * ```
 *
 * for `i` in `0 until n`, with `k` running over the `n/2` inputs.
 *
 * Doing that directly costs `n^2 / 2` multiplications, which for a Vorbis long
 * block is two million per channel per packet. So it is computed the way every
 * real codec computes it, through a complex FFT a quarter the length, in three
 * steps that are worth writing down because they are otherwise unreadable:
 *
 * 1. The cosine above is the kernel of a type-IV DCT of length `m = n/2`, read
 *    at index `i + m/2` instead of `i`. So the whole job is one DCT-IV plus a
 *    rearrangement.
 * 2. A DCT-IV of length `m` is a complex FFT of length `m/2` between a twiddle
 *    before and a twiddle after, because pairing the even inputs with the
 *    reversed odd inputs into one complex sequence makes the two halves of the
 *    output come back as the real and imaginary parts.
 * 3. The DCT-IV kernel is even about index `-1/2` and odd about index
 *    `m - 1/2`, and those two symmetries are what fold the `n` outputs back
 *    onto the `m` values the transform produced.
 *
 * The unit tests check this against the definition above computed directly, so
 * the derivation is not something a reader has to take on trust.
 */
internal class InverseMdct(
    /** Output length. A power of two of at least 8. */
    val n: Int,
) {

    init {
        require(n >= 8 && n and (n - 1) == 0) {
            "an inverse MDCT is a power of two of at least 8, was $n"
        }
    }

    /** Coefficients in, which is also the length of the DCT-IV. */
    private val m = n / 2

    /** The FFT length, a quarter of the output. */
    private val quarter = n / 4

    private val fft = Fft(quarter, forward = false)

    // The twiddle before the FFT and the twiddle after it are the same numbers
    // read with different indices, so one table serves both.
    private val twiddleCos = FloatArray(quarter)
    private val twiddleSin = FloatArray(quarter)

    private val re = FloatArray(quarter)
    private val im = FloatArray(quarter)
    private val dct = FloatArray(m)

    init {
        for (j in 0 until quarter) {
            val angle = PI / m * (j + 0.125)
            twiddleCos[j] = cos(angle).toFloat()
            twiddleSin[j] = sin(angle).toFloat()
        }
    }

    /**
     * Transforms the `n/2` coefficients in [input] into `n` samples in
     * [output], scaled by [scale].
     *
     * [output] may be longer than `n`; only the first `n` entries are written.
     */
    fun transform(input: FloatArray, output: FloatArray, scale: Float) {
        require(input.size >= m) { "the transform needs $m coefficients, got ${input.size}" }
        require(output.size >= n) { "the transform writes $n samples, got room for ${output.size}" }

        // Pair the even coefficients with the reversed odd ones, and twiddle.
        for (j in 0 until quarter) {
            val a = input[2 * j]
            val b = -input[m - 1 - 2 * j]
            val wr = twiddleCos[j]
            val wi = twiddleSin[j]
            re[j] = a * wr - b * wi
            im[j] = a * wi + b * wr
        }

        fft.transform(re, im)

        // Twiddle again, and the real and imaginary parts fall into the two
        // interleaved halves of the DCT-IV output.
        for (p in 0 until quarter) {
            val wr = twiddleCos[p]
            val wi = twiddleSin[p]
            val ar = re[p]
            val ai = im[p]
            dct[2 * p] = ar * wr - ai * wi
            dct[m - 1 - 2 * p] = ar * wi + ai * wr
        }

        // Unfold the m values into n outputs using the kernel's symmetries.
        // The middle half is the DCT-IV output reversed and negated, which is
        // where the transform's characteristic time-domain aliasing comes from:
        // it is the aliasing the overlap-add cancels.
        for (i in 0 until quarter) {
            output[i] = scale * dct[i + quarter]
        }
        for (i in quarter until 3 * quarter) {
            output[i] = -scale * dct[3 * quarter - 1 - i]
        }
        for (i in 3 * quarter until n) {
            output[i] = -scale * dct[i - 3 * quarter]
        }
    }
}

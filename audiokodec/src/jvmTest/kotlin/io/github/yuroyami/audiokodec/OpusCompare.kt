package io.github.yuroyami.audiokodec

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * The quality measure RFC 6716 defines for judging an Opus decoder, from
 * `opus_compare.c` in libopus (BSD-3, see `reference/REFERENCES.md`).
 *
 * It compares a decoder's output with the reference's in 21 bands of a short
 * spectrum, after letting the reference mask the error the way hearing does:
 * across neighbouring bands, over the following few milliseconds, and between
 * channels. A decoder passes when the result is not negative. The reference
 * is always 48 kHz stereo; a mono decode is compared with its fold-down, and a
 * decode at a lower rate with the bands that rate can carry.
 *
 * The one change from the C is how the spectrum is evaluated. The program
 * sums every bin's DFT directly, which over a 30 second vector is billions of
 * operations; this evaluates the same bins with an FFT, in double precision.
 * Only the rounding differs, far below anything the measure can see.
 */
internal object OpusCompare {

    class Result(val quality: Double, val error: Double) {
        val passes: Boolean get() = quality >= 0
        override fun toString(): String = "quality %.1f %% (internal weighted error %f)".format(quality, error)
    }

    private const val NBANDS = 21
    private const val NFREQS = 240
    private const val TEST_WIN_SIZE = 480
    private const val TEST_WIN_STEP = 120
    private val BANDS = intArrayOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 20, 24, 28, 32, 40, 48, 56, 68, 80, 96, 120, 156, 200)

    /**
     * The reference's half of the measure: its band energies and spectrum,
     * which do not depend on the decode being scored, so that one reference
     * can score decodes at every rate without being analysed again.
     */
    class Reference(reference: ShortArray, val channels: Int) {
        val length: Int = reference.size / 2
        val frames: Int
        val bandEnergies: FloatArray
        val spectrum: FloatArray

        init {
            require(channels == 1 || channels == 2) { "opus_compare takes one or two channels" }
            require(length >= TEST_WIN_SIZE) { "insufficient sample data ($length < $TEST_WIN_SIZE)" }
            val x = FloatArray(length * channels)
            if (channels == 1) {
                for (i in 0 until length) x[i] = (0.5 * (reference[2 * i] + reference[2 * i + 1])).toFloat()
            } else {
                for (i in reference.indices) x[i] = reference[i].toFloat()
            }
            frames = (length - TEST_WIN_SIZE + TEST_WIN_STEP) / TEST_WIN_STEP
            bandEnergies = FloatArray(frames * NBANDS * channels)
            spectrum = FloatArray(frames * NFREQS * channels)
            bandEnergy(bandEnergies, spectrum, NBANDS, x, channels, frames, TEST_WIN_SIZE, TEST_WIN_STEP, 1)
        }
    }

    /**
     * Scores [decoded], [channels] interleaved channels at [rate], against
     * [reference], 48 kHz stereo, both as 16-bit samples. Throws when their
     * lengths do not correspond, which the program also refuses.
     */
    fun compare(reference: ShortArray, decoded: ShortArray, channels: Int, rate: Int): Result =
        compare(Reference(reference, channels), decoded, rate)

    /** [compare] against a reference already analysed. */
    fun compare(reference: Reference, decoded: ShortArray, rate: Int): Result {
        val channels = reference.channels
        val downsample = 48_000 / rate
        val ybands = when (rate) {
            8_000 -> 13
            12_000 -> 15
            16_000 -> 17
            24_000 -> 19
            48_000 -> NBANDS
            else -> throw IllegalArgumentException("opus_compare takes 8, 12, 16, 24 or 48 kHz, not $rate")
        }
        val yfreqs = NFREQS / downsample
        val xlength = reference.length
        val ylength = decoded.size / channels
        require(xlength == ylength * downsample) { "sample counts do not match ($xlength != ${ylength * downsample})" }
        val y = FloatArray(decoded.size) { decoded[it].toFloat() }

        val nframes = reference.frames
        val xb = reference.bandEnergies.copyOf()
        val bigX = reference.spectrum.copyOf()
        val bigY = FloatArray(nframes * yfreqs * channels)
        bandEnergy(null, bigY, ybands, y, channels, nframes, TEST_WIN_SIZE / downsample, TEST_WIN_STEP / downsample, downsample)

        for (xi in 0 until nframes) {
            // Frequency masking, low to high: 10 dB per Bark.
            for (bi in 1 until NBANDS) {
                for (ci in 0 until channels) {
                    xb[(xi * NBANDS + bi) * channels + ci] += 0.1f * xb[(xi * NBANDS + bi - 1) * channels + ci]
                }
            }
            // Frequency masking, high to low: 15 dB per Bark.
            for (bi in NBANDS - 2 downTo 0) {
                for (ci in 0 until channels) {
                    xb[(xi * NBANDS + bi) * channels + ci] += 0.03f * xb[(xi * NBANDS + bi + 1) * channels + ci]
                }
            }
            if (xi > 0) {
                // Temporal masking: -3 dB per 2.5 ms.
                for (bi in 0 until NBANDS) {
                    for (ci in 0 until channels) {
                        xb[(xi * NBANDS + bi) * channels + ci] += 0.5f * xb[((xi - 1) * NBANDS + bi) * channels + ci]
                    }
                }
            }
            // Some cross-talk is allowed.
            if (channels == 2) {
                for (bi in 0 until NBANDS) {
                    val l = xb[(xi * NBANDS + bi) * channels]
                    val r = xb[(xi * NBANDS + bi) * channels + 1]
                    xb[(xi * NBANDS + bi) * channels] += 0.01f * r
                    xb[(xi * NBANDS + bi) * channels + 1] += 0.01f * l
                }
            }
            for (bi in 0 until ybands) {
                for (xj in BANDS[bi] until BANDS[bi + 1]) {
                    for (ci in 0 until channels) {
                        bigX[(xi * NFREQS + xj) * channels + ci] += 0.1f * xb[(xi * NBANDS + bi) * channels + ci]
                        bigY[(xi * yfreqs + xj) * channels + ci] += 0.1f * xb[(xi * NBANDS + bi) * channels + ci]
                    }
                }
            }
        }

        // Each frame is averaged with the one before, which makes the
        // comparison a little less sensitive.
        for (bi in 0 until ybands) {
            for (xj in BANDS[bi] until BANDS[bi + 1]) {
                for (ci in 0 until channels) {
                    var xtmp = bigX[xj * channels + ci]
                    var ytmp = bigY[xj * channels + ci]
                    for (xi in 1 until nframes) {
                        val xtmp2 = bigX[(xi * NFREQS + xj) * channels + ci]
                        val ytmp2 = bigY[(xi * yfreqs + xj) * channels + ci]
                        bigX[(xi * NFREQS + xj) * channels + ci] += xtmp
                        bigY[(xi * yfreqs + xj) * channels + ci] += ytmp
                        xtmp = xtmp2
                        ytmp = ytmp2
                    }
                }
            }
        }

        // Below 48 kHz the last 300 Hz are left out, to allow for different
        // transition bands; at 12 kHz the last band already leaves out 400.
        val maxCompare = when (rate) {
            48_000 -> BANDS[NBANDS]
            12_000 -> BANDS[ybands]
            else -> BANDS[ybands] - 3
        }
        var err = 0.0
        for (xi in 0 until nframes) {
            var ef = 0.0
            for (bi in 0 until ybands) {
                var eb = 0.0
                var xj = BANDS[bi]
                while (xj < BANDS[bi + 1] && xj < maxCompare) {
                    for (ci in 0 until channels) {
                        val re = bigY[(xi * yfreqs + xj) * channels + ci] / bigX[(xi * NFREQS + xj) * channels + ci]
                        var im = (re - ln(re.toDouble()) - 1).toFloat()
                        // Less sensitive around the crossover between SILK
                        // and CELT, where either mode's filters may be used.
                        if (xj in 79..81) im *= 0.1f
                        if (xj == 80) im *= 0.1f
                        eb += im
                    }
                    xj++
                }
                eb /= ((BANDS[bi + 1] - BANDS[bi]) * channels).toDouble()
                ef += eb * eb
            }
            // A fixed normalisation accepts slightly lower quality at lower rates.
            ef /= NBANDS
            ef *= ef
            err += ef * ef
        }
        err = (err / nframes).pow(1.0 / 16)
        val quality = 100 * (1 - 0.5 * ln(1 + err) / ln(1.13))
        return Result(quality, err)
    }

    /** `band_energy`: the power of each bin, and when [out] is given each band's mean, in every window. */
    private fun bandEnergy(
        out: FloatArray?,
        ps: FloatArray,
        nbands: Int,
        input: FloatArray,
        channels: Int,
        nframes: Int,
        windowSize: Int,
        step: Int,
        downsample: Int,
    ) {
        val window = FloatArray(windowSize) { (0.5f - 0.5f * cos((2 * PI.toFloat() / (windowSize - 1)) * it)) }
        val psSize = windowSize / 2
        val fft = Fft(windowSize)
        val re = DoubleArray(windowSize)
        val im = DoubleArray(windowSize)
        val power = Array(channels) { FloatArray(BANDS[nbands]) }
        for (xi in 0 until nframes) {
            for (ci in 0 until channels) {
                for (xk in 0 until windowSize) {
                    re[xk] = (window[xk] * input[(xi * step + xk) * channels + ci]).toDouble()
                    im[xk] = 0.0
                }
                fft.transform(re, im)
                for (xj in 0 until BANDS[nbands]) {
                    val r = (re[xj] * downsample).toFloat()
                    val i = (im[xj] * downsample).toFloat()
                    power[ci][xj] = r * r + i * i + 100_000
                }
            }
            for (bi in 0 until nbands) {
                val p = FloatArray(2)
                for (xj in BANDS[bi] until BANDS[bi + 1]) {
                    for (ci in 0 until channels) {
                        ps[(xi * psSize + xj) * channels + ci] = power[ci][xj]
                        p[ci] += power[ci][xj]
                    }
                }
                if (out != null) {
                    out[(xi * NBANDS + bi) * channels] = p[0] / (BANDS[bi + 1] - BANDS[bi])
                    if (channels == 2) out[(xi * NBANDS + bi) * channels + 1] = p[1] / (BANDS[bi + 1] - BANDS[bi])
                }
            }
        }
    }

    /**
     * A forward DFT of any length whose factors are 2, 3 and 5, which covers
     * every window opus_compare uses: 480 samples at 48 kHz down to 80 at
     * 8 kHz. Mixed-radix and recursive, in place.
     */
    private class Fft(private val n: Int) {
        private val cosines = DoubleArray(n) { cos(2 * PI * it / n) }
        private val sines = DoubleArray(n) { sin(2 * PI * it / n) }
        private val scratchRe = DoubleArray(n)
        private val scratchIm = DoubleArray(n)

        fun transform(re: DoubleArray, im: DoubleArray) {
            transform(re, im, 0, 1, n, scratchRe, scratchIm)
        }

        /** Transforms the [size] samples at [offset], [stride] apart, writing the result there in order. */
        private fun transform(re: DoubleArray, im: DoubleArray, offset: Int, stride: Int, size: Int, tre: DoubleArray, tim: DoubleArray) {
            if (size == 1) return
            val radix = when {
                size % 2 == 0 -> 2
                size % 3 == 0 -> 3
                size % 5 == 0 -> 5
                else -> throw IllegalArgumentException("$n has a factor other than 2, 3 and 5")
            }
            val m = size / radix
            // Gather each decimated subsequence into its own block, then
            // transform the blocks in place.
            for (r in 0 until radix) {
                for (k in 0 until m) {
                    tre[offset + r * m + k] = re[offset + (k * radix + r) * stride]
                    tim[offset + r * m + k] = im[offset + (k * radix + r) * stride]
                }
            }
            for (i in 0 until size) {
                re[offset + i * stride] = tre[offset + i]
                im[offset + i * stride] = tim[offset + i]
            }
            for (r in 0 until radix) transform(re, im, offset + r * m * stride, stride, m, tre, tim)
            // Butterflies: output k + q m is the sum over r of block r's bin
            // k turned by the twiddle for (k + q m) r.
            val twiddleStep = n / size
            for (k in 0 until m) {
                for (q in 0 until radix) {
                    var sumRe = 0.0
                    var sumIm = 0.0
                    val turn = ((k + q * m) * twiddleStep) % n
                    var index = 0
                    for (r in 0 until radix) {
                        val c = cosines[index]
                        val s = -sines[index]
                        val vr = re[offset + (r * m + k) * stride]
                        val vi = im[offset + (r * m + k) * stride]
                        sumRe += vr * c - vi * s
                        sumIm += vr * s + vi * c
                        index += turn
                        if (index >= n) index -= n
                    }
                    tre[offset + k + q * m] = sumRe
                    tim[offset + k + q * m] = sumIm
                }
            }
            for (i in 0 until size) {
                re[offset + i * stride] = tre[offset + i]
                im[offset + i * stride] = tim[offset + i]
            }
        }
    }
}

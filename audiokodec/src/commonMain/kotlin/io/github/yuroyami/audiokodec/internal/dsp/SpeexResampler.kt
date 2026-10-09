package io.github.yuroyami.audiokodec.internal.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * The windowed sinc resampler of speexdsp 1.2.1, in its floating-point
 * build, translated from `libspeexdsp/resample.c`.
 *
 * The filter is a sinc with a Kaiser window, tabulated once per stream: for
 * every phase of the conversion ratio when there are few of them, or else
 * at a fixed oversampling with cubic interpolation between the entries. The
 * arithmetic follows the reference's exactly, single-precision products and
 * single or double-precision sums alike, so the samples are the reference's.
 *
 * The reference's buffering, and its handling of a rate or quality change
 * mid-stream, are not translated: neither changes a sample, and the stream's
 * rate is fixed here. What is kept is each channel's position in the input,
 * as a whole sample and a fraction in units of the reduced ratio, and the
 * history the filter still needs.
 */
internal class SpeexResampler(
    inRate: Int,
    outRate: Int,
    private val quality: Int,
) {
    /** The reduced ratio: [numRate] input samples for every [denRate] output samples. */
    val numRate: Int
    val denRate: Int
    val filterLength: Int
    private val intAdvance: Int
    private val fracAdvance: Int
    private val oversample: Int
    private val direct: Boolean
    private val sincTable: FloatArray

    init {
        require(quality in 0..10)
        val g = gcd(inRate, outRate)
        numRate = inRate / g
        denRate = outRate / g
        intAdvance = numRate / denRate
        fracAdvance = numRate % denRate
        val mapping = QUALITY[quality]
        var over = mapping.oversample
        var length = mapping.baseLength
        val cutoff: Float
        if (numRate > denRate) {
            // Downsampling: narrower, and longer in proportion.
            cutoff = mapping.downsampleBandwidth * denRate.toFloat() / numRate.toFloat()
            length = multiplyFrac(length, numRate, denRate)
            length = ((length - 1) and 7.inv()) + 8
            if (2L * denRate < numRate) over = over shr 1
            if (4L * denRate < numRate) over = over shr 1
            if (8L * denRate < numRate) over = over shr 1
            if (16L * denRate < numRate) over = over shr 1
            if (over < 1) over = 1
        } else {
            cutoff = mapping.upsampleBandwidth
        }
        filterLength = length
        oversample = over
        direct = length.toLong() * denRate <= length.toLong() * over + 8 &&
            Int.MAX_VALUE / 4 / denRate >= length
        val window = mapping.window
        if (direct) {
            sincTable = FloatArray(length * denRate)
            for (i in 0 until denRate) {
                for (j in 0 until length) {
                    val x = (j - length / 2 + 1).toFloat() - i.toFloat() / denRate.toFloat()
                    sincTable[i * length + j] = sinc(cutoff, x, length, window)
                }
            }
        } else {
            sincTable = FloatArray(over * length + 8)
            for (i in -4 until over * length + 4) {
                val x = i / over.toFloat() - (length / 2).toFloat()
                sincTable[i + 4] = sinc(cutoff, x, length, window)
            }
        }
    }

    /**
     * Writes the outputs whose filter lies wholly inside [input], from
     * [start] for [count] samples at [stride], into [out] from [outAt] at
     * [outStride], and returns how many. [state] holds the position: the
     * whole input sample in its first element, relative to [start], and the
     * fraction in its second. Each output reads input samples `position` to
     * `position + filterLength - 1`, centred on `position + filterLength / 2
     * - 1` plus the fraction.
     */
    fun run(input: FloatArray, start: Int, count: Int, stride: Int, state: IntArray, out: FloatArray, outAt: Int, outStride: Int, maxOut: Int): Int {
        val n = filterLength
        var last = state[0]
        var frac = state[1]
        var produced = 0
        var o = outAt
        while (last < count && produced < maxOut) {
            val base = start + last * stride
            out[o] = if (direct) {
                val row = frac * n
                if (quality > 8) {
                    var a0 = 0.0
                    var a1 = 0.0
                    var a2 = 0.0
                    var a3 = 0.0
                    var j = 0
                    while (j < n) {
                        a0 += (sincTable[row + j] * input[base + j * stride]).toDouble()
                        a1 += (sincTable[row + j + 1] * input[base + (j + 1) * stride]).toDouble()
                        a2 += (sincTable[row + j + 2] * input[base + (j + 2) * stride]).toDouble()
                        a3 += (sincTable[row + j + 3] * input[base + (j + 3) * stride]).toDouble()
                        j += 4
                    }
                    (a0 + a1 + a2 + a3).toFloat()
                } else {
                    var sum = 0f
                    for (j in 0 until n) sum += sincTable[row + j] * input[base + j * stride]
                    sum
                }
            } else {
                val offset = (frac.toLong() * oversample / denRate).toInt()
                val f = ((frac.toLong() * oversample) % denRate).toFloat() / denRate.toFloat()
                if (quality > 8) {
                    var a0 = 0.0
                    var a1 = 0.0
                    var a2 = 0.0
                    var a3 = 0.0
                    for (j in 0 until n) {
                        val x = input[base + j * stride]
                        val at = 4 + (j + 1) * oversample - offset
                        a0 += (x * sincTable[at - 2]).toDouble()
                        a1 += (x * sincTable[at - 1]).toDouble()
                        a2 += (x * sincTable[at]).toDouble()
                        a3 += (x * sincTable[at + 1]).toDouble()
                    }
                    val c = cubic(f)
                    (c[0] * a0 + c[1] * a1 + c[2] * a2 + c[3] * a3).toFloat()
                } else {
                    var a0 = 0f
                    var a1 = 0f
                    var a2 = 0f
                    var a3 = 0f
                    for (j in 0 until n) {
                        val x = input[base + j * stride]
                        val at = 4 + (j + 1) * oversample - offset
                        a0 += x * sincTable[at - 2]
                        a1 += x * sincTable[at - 1]
                        a2 += x * sincTable[at]
                        a3 += x * sincTable[at + 1]
                    }
                    val c = cubic(f)
                    c[0] * a0 + c[1] * a1 + c[2] * a2 + c[3] * a3
                }
            }
            o += outStride
            produced++
            last += intAdvance
            frac += fracAdvance
            if (frac >= denRate) {
                frac -= denRate
                last++
            }
        }
        state[0] = last
        state[1] = frac
        return produced
    }

    private val interp = FloatArray(4)

    /** The reference's cubic interpolation weights, in its single precision. */
    private fun cubic(f: Float): FloatArray {
        interp[0] = -0.16667f * f + 0.16667f * f * f * f
        interp[1] = f + 0.5f * f * f - 0.5f * f * f * f
        interp[3] = -0.33333f * f + 0.5f * f * f - 0.16667f * f * f * f
        interp[2] = (1.0 - interp[0] - interp[1] - interp[3]).toFloat()
        return interp
    }

    private class Window(val table: DoubleArray, val oversample: Int)

    private class Mapping(
        val baseLength: Int,
        val oversample: Int,
        val downsampleBandwidth: Float,
        val upsampleBandwidth: Float,
        val window: Window,
    )

    private companion object {
        fun gcd(a: Int, b: Int): Int {
            var x = a
            var y = b
            while (y != 0) {
                val t = x
                x = y
                y = t % y
            }
            return x
        }

        /** [value] times [num] over [den], in the reference's unsigned 32-bit steps, refusing what overflows them. */
        fun multiplyFrac(value: Int, num: Int, den: Int): Int {
            val major = value.toLong() / den
            val remain = value.toLong() % den
            val result = remain * num / den + major * num
            require(result <= 0xFFFFFFFFL) { "the resampling filter would be too long" }
            return result.toInt().also { require(it > 0) { "the resampling filter would be too long" } }
        }

        fun sinc(cutoff: Float, x: Float, n: Int, window: Window): Float {
            val xx = x * cutoff
            return when {
                abs(x.toDouble()) < 1e-6 -> cutoff
                abs(x.toDouble()) > 0.5 * n -> 0f
                else -> (cutoff * sin(PI * xx) / (PI * xx) * window(abs(2.0 * x / n).toFloat(), window)).toFloat()
            }
        }

        /** The window at [x], from 0 at the centre to 1 at the edge, by cubic interpolation in its table. */
        fun window(x: Float, w: Window): Double {
            val y = x * w.oversample
            val ind = floor(y.toDouble()).toInt()
            val frac = y - ind
            val f3 = frac * frac * frac
            val f2 = frac * frac
            val i3 = -0.1666666667 * frac + 0.1666666667 * f3
            val i2 = frac + 0.5 * f2 - 0.5 * f3
            val i0 = -0.3333333333 * frac + 0.5 * f2 - 0.1666666667 * f3
            val i1 = 1.0f - i3 - i2 - i0
            val t = w.table
            return i0 * t[ind] + i1 * t[ind + 1] + i2 * t[ind + 2] + i3 * t[ind + 3]
        }

        val KAISER12 = Window(
            doubleArrayOf(
                0.99859849, 1.00000000, 0.99859849, 0.99440475, 0.98745105, 0.97779076,
                0.96549770, 0.95066529, 0.93340547, 0.91384741, 0.89213598, 0.86843014,
                0.84290116, 0.81573067, 0.78710866, 0.75723148, 0.72629970, 0.69451601,
                0.66208321, 0.62920216, 0.59606986, 0.56287762, 0.52980938, 0.49704014,
                0.46473455, 0.43304576, 0.40211431, 0.37206735, 0.34301800, 0.31506490,
                0.28829195, 0.26276832, 0.23854851, 0.21567274, 0.19416736, 0.17404546,
                0.15530766, 0.13794294, 0.12192957, 0.10723616, 0.09382272, 0.08164178,
                0.07063950, 0.06075685, 0.05193064, 0.04409466, 0.03718069, 0.03111947,
                0.02584161, 0.02127838, 0.01736250, 0.01402878, 0.01121463, 0.00886058,
                0.00691064, 0.00531256, 0.00401805, 0.00298291, 0.00216702, 0.00153438,
                0.00105297, 0.00069463, 0.00043489, 0.00025272, 0.00013031, 0.0000527734,
                0.00001000, 0.00000000,
            ),
            64,
        )

        val KAISER10 = Window(
            doubleArrayOf(
                0.99537781, 1.00000000, 0.99537781, 0.98162644, 0.95908712, 0.92831446,
                0.89005583, 0.84522401, 0.79486424, 0.74011713, 0.68217934, 0.62226347,
                0.56155915, 0.50119680, 0.44221549, 0.38553619, 0.33194107, 0.28205962,
                0.23636152, 0.19515633, 0.15859932, 0.12670280, 0.09935205, 0.07632451,
                0.05731132, 0.04193980, 0.02979584, 0.02044510, 0.01345224, 0.00839739,
                0.00488951, 0.00257636, 0.00115101, 0.00035515, 0.00000000, 0.00000000,
            ),
            32,
        )

        val KAISER8 = Window(
            doubleArrayOf(
                0.99635258, 1.00000000, 0.99635258, 0.98548012, 0.96759014, 0.94302200,
                0.91223751, 0.87580811, 0.83439927, 0.78875245, 0.73966538, 0.68797126,
                0.63451750, 0.58014482, 0.52566725, 0.47185369, 0.41941150, 0.36897272,
                0.32108304, 0.27619388, 0.23465776, 0.19672670, 0.16255380, 0.13219758,
                0.10562887, 0.08273982, 0.06335451, 0.04724088, 0.03412321, 0.02369490,
                0.01563093, 0.00959968, 0.00527363, 0.00233883, 0.00050000, 0.00000000,
            ),
            32,
        )

        val KAISER6 = Window(
            doubleArrayOf(
                0.99733006, 1.00000000, 0.99733006, 0.98935595, 0.97618418, 0.95799003,
                0.93501423, 0.90755855, 0.87598009, 0.84068475, 0.80211977, 0.76076565,
                0.71712752, 0.67172623, 0.62508937, 0.57774224, 0.53019925, 0.48295561,
                0.43647969, 0.39120616, 0.34752997, 0.30580127, 0.26632152, 0.22934058,
                0.19505503, 0.16360756, 0.13508755, 0.10953262, 0.08693120, 0.06722600,
                0.05031820, 0.03607231, 0.02432151, 0.01487334, 0.00752000, 0.00000000,
            ),
            32,
        )

        val QUALITY = arrayOf(
            Mapping(8, 4, 0.830f, 0.860f, KAISER6),
            Mapping(16, 4, 0.850f, 0.880f, KAISER6),
            Mapping(32, 4, 0.882f, 0.910f, KAISER6),
            Mapping(48, 8, 0.895f, 0.917f, KAISER8),
            Mapping(64, 8, 0.921f, 0.940f, KAISER8),
            Mapping(80, 16, 0.922f, 0.940f, KAISER10),
            Mapping(96, 16, 0.940f, 0.945f, KAISER10),
            Mapping(128, 16, 0.950f, 0.950f, KAISER10),
            Mapping(160, 16, 0.960f, 0.960f, KAISER10),
            Mapping(192, 32, 0.968f, 0.968f, KAISER12),
            Mapping(256, 32, 0.975f, 0.975f, KAISER12),
        )
    }
}

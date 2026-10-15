package io.github.yuroyami.audiokodec.internal.opus.celt

import io.github.yuroyami.audiokodec.internal.opus.RangeDecoder
import io.github.yuroyami.audiokodec.internal.opus.RangeEncoder
import kotlin.math.abs
import kotlin.math.floor

/**
 * CELT's pyramid vector quantiser: the shape of each band, as pulses.
 *
 * A band's shape is a vector of integers whose absolute values sum to K, the
 * number of pulses the allocation gave it. Every such vector has an index, the
 * stream carries the index as one uniformly coded number, and decoding
 * enumerates back from it. The vector is then normalised to unit energy and
 * spread, so that a band with few pulses sounds like noise in the right place
 * rather than a handful of tones.
 *
 * Translated from libopus `cwrs.c` and `vq.c`, the encoder's search and
 * index computation as well as the decoder's enumeration.
 */
internal object CeltVq {

    private val ROW_OFFSETS = intArrayOf(0, 176, 351, 525, 698, 870, 1041, 1131, 1178, 1207, 1226, 1240, 1248, 1254, 1257)

    /** `CELT_PVQ_U`: how many vectors of dimension [n] have exactly [k] pulses, folded by symmetry. */
    private fun u(n: Int, k: Int): Long = CeltPvqTable.U_DATA[ROW_OFFSETS[minOf(n, k)] + maxOf(n, k)]

    /** `CELT_PVQ_V`: how many vectors of dimension [n] have at most [k] pulses. */
    private fun v(n: Int, k: Int): Long = u(n, k) + u(n, k + 1)

    private fun row(n: Int, k: Int): Long = CeltPvqTable.U_DATA[ROW_OFFSETS[n] + k]

    /** `decode_pulses`: the vector with index read from [range], and its energy. */
    fun decodePulses(y: IntArray, n: Int, k: Int, range: RangeDecoder): Float =
        cwrsi(n, k, range.decodeUniform(v(n, k)), y)

    /** `cwrsi`: the vector of dimension [n] with [k] pulses at index [index]. */
    private fun cwrsi(nIn: Int, kIn: Int, index: Long, y: IntArray): Float {
        var n = nIn
        var k = kIn
        var i = index
        var yy = 0f
        var out = 0
        var p: Long
        var s: Int
        var k0: Int
        var value: Int
        while (n > 2) {
            if (k >= n) {
                p = row(n, k + 1)
                s = if (i >= p) -1 else 0
                i -= p and s.toLong()
                k0 = k
                val q = row(n, n)
                if (q > i) {
                    k = n
                    do {
                        p = row(--k, n)
                    } while (p > i)
                } else {
                    p = row(n, k)
                    while (p > i) {
                        k--
                        p = row(n, k)
                    }
                }
                i -= p
                value = (k0 - k + s) xor s
                y[out++] = value
                yy += (value * value).toFloat()
            } else {
                p = row(k, n)
                val q = row(k + 1, n)
                if (p <= i && i < q) {
                    i -= p
                    y[out++] = 0
                } else {
                    s = if (i >= q) -1 else 0
                    i -= q and s.toLong()
                    k0 = k
                    do {
                        p = row(--k, n)
                    } while (p > i)
                    i -= p
                    value = (k0 - k + s) xor s
                    y[out++] = value
                    yy += (value * value).toFloat()
                }
            }
            n--
        }
        p = 2L * k + 1
        s = if (i >= p) -1 else 0
        i -= p and s.toLong()
        k0 = k
        k = ((i + 1) shr 1).toInt()
        if (k != 0) i -= 2L * k - 1
        value = (k0 - k + s) xor s
        y[out++] = value
        yy += (value * value).toFloat()
        s = -i.toInt()
        value = (k + s) xor s
        y[out] = value
        yy += (value * value).toFloat()
        return yy
    }

    /** `icwrs`: the index of the vector [y] of dimension [n] among those with the same pulse count. */
    private fun icwrs(n: Int, y: IntArray): Long {
        var j = n - 1
        var i = if (y[j] < 0) 1L else 0L
        var k = abs(y[j])
        do {
            j--
            i += u(n - j, k)
            k += abs(y[j])
            if (y[j] < 0) i += u(n - j, k + 1)
        } while (j > 0)
        return i
    }

    /** `encode_pulses`: writes the index of [y], which has [k] pulses in [n] dimensions. */
    fun encodePulses(y: IntArray, n: Int, k: Int, range: RangeEncoder) {
        range.encodeUniform(icwrs(n, y), v(n, k))
    }

    /**
     * `op_pvq_search`: the vector of [k] pulses closest in direction to the
     * [n] values of [x] at [xOffset], into [iy], returning its energy. [x] is
     * left holding its absolute values, as in the reference.
     */
    fun pvqSearch(x: FloatArray, xOffset: Int, iy: IntArray, k: Int, n: Int): Float {
        val y = FloatArray(n)
        val signx = IntArray(n)
        var sum = 0f
        for (j in 0 until n) {
            signx[j] = if (x[xOffset + j] < 0) 1 else 0
            x[xOffset + j] = abs(x[xOffset + j])
            iy[j] = 0
        }
        var xy = 0f
        var yy = 0f
        var pulsesLeft = k
        if (k > (n shr 1)) {
            // A first guess by projecting onto the pyramid.
            for (j in 0 until n) sum += x[xOffset + j]
            if (!(sum > EPSILON && sum < 64)) {
                // Too small, or not finite: a single pulse at the start.
                x[xOffset] = 1f
                for (j in 1 until n) x[xOffset + j] = 0f
                sum = 1f
            }
            // K plus less than one can never place more than K pulses.
            val rcp = (k + 0.8f) * (1f / sum)
            for (j in 0 until n) {
                iy[j] = floor(rcp * x[xOffset + j]).toInt()
                y[j] = iy[j].toFloat()
                yy += y[j] * y[j]
                xy += x[xOffset + j] * y[j]
                y[j] *= 2f
                pulsesLeft -= iy[j]
            }
        }
        if (pulsesLeft > n + 3) {
            val tmp = pulsesLeft.toFloat()
            yy += tmp * tmp
            yy += tmp * y[0]
            iy[0] += pulsesLeft
            pulsesLeft = 0
        }
        for (i in 0 until pulsesLeft) {
            var bestId = 0
            yy += 1f
            var rxy = xy + x[xOffset]
            var ryy = yy + y[0]
            rxy *= rxy
            var bestDen = ryy
            var bestNum = rxy
            for (j in 1 until n) {
                rxy = xy + x[xOffset + j]
                ryy = yy + y[j]
                rxy *= rxy
                if (bestDen * rxy > ryy * bestNum) {
                    bestDen = ryy
                    bestNum = rxy
                    bestId = j
                }
            }
            xy += x[xOffset + bestId]
            yy += y[bestId]
            y[bestId] += 2f
            iy[bestId]++
        }
        for (j in 0 until n) iy[j] = (iy[j] xor -signx[j]) + signx[j]
        return yy
    }

    /**
     * `alg_quant`: codes the shape of the [n] values of [x] at [xOffset] with
     * [k] pulses and returns the collapse mask. With [resynth], [x] is left
     * holding what the decoder will reconstruct.
     */
    fun quantise(
        x: FloatArray,
        xOffset: Int,
        n: Int,
        k: Int,
        spread: Int,
        blocks: Int,
        range: RangeEncoder,
        gain: Float,
        resynth: Boolean,
    ): Int {
        val iy = IntArray(n + 3)
        expRotation(x, xOffset, n, 1, blocks, k, spread)
        val yy = pvqSearch(x, xOffset, iy, k, n)
        encodePulses(iy, n, k, range)
        if (resynth) {
            normaliseResidual(iy, x, xOffset, n, yy, gain)
            expRotation(x, xOffset, n, -1, blocks, k, spread)
        }
        return collapseMask(iy, n, blocks)
    }

    /**
     * `stereo_itheta`: the angle, in units of a quarter turn over 16384,
     * between the energies of [x] and [y], or of their sum and difference
     * when [stereo].
     */
    fun stereoItheta(x: FloatArray, xOffset: Int, y: FloatArray, yOffset: Int, stereo: Boolean, n: Int): Int {
        var emid = EPSILON
        var eside = EPSILON
        if (stereo) {
            for (i in 0 until n) {
                val m = x[xOffset + i] + y[yOffset + i]
                val s = x[xOffset + i] - y[yOffset + i]
                emid += m * m
                eside += s * s
            }
        } else {
            emid += CeltMath.innerProduct(x, xOffset, x, xOffset, n)
            eside += CeltMath.innerProduct(y, yOffset, y, yOffset, n)
        }
        val mid = CeltMath.sqrt(emid)
        val side = CeltMath.sqrt(eside)
        return floor(0.5f + 16384 * 0.63662f * fastAtan2(side, mid)).toInt()
    }

    /** `fast_atan2f`: a rational approximation of the arctangent, as the float build has it. */
    private fun fastAtan2(y: Float, x: Float): Float {
        val x2 = x * x
        val y2 = y * y
        if (x2 + y2 < 1e-18f) return 0f
        return if (x2 < y2) {
            val den = (y2 + ATAN_B * x2) * (y2 + ATAN_C * x2)
            -x * y * (y2 + ATAN_A * x2) / den + (if (y < 0) -ATAN_E else ATAN_E)
        } else {
            val den = (x2 + ATAN_B * y2) * (x2 + ATAN_C * y2)
            x * y * (x2 + ATAN_A * y2) / den + (if (y < 0) -ATAN_E else ATAN_E) - (if (x * y < 0) -ATAN_E else ATAN_E)
        }
    }

    private const val ATAN_A = 0.43157974f
    private const val ATAN_B = 0.67848403f
    private const val ATAN_C = 0.08595542f
    private const val ATAN_E = 3.141592653f / 2

    /** `alg_unquant`: decodes a band's shape into [x] and returns its collapse mask. */
    fun unquantise(x: FloatArray, xOffset: Int, n: Int, k: Int, spread: Int, blocks: Int, range: RangeDecoder, gain: Float): Int {
        val pulses = IntArray(n)
        val energy = decodePulses(pulses, n, k, range)
        normaliseResidual(pulses, x, xOffset, n, energy, gain)
        expRotation(x, xOffset, n, -1, blocks, k, spread)
        return collapseMask(pulses, n, blocks)
    }

    private fun normaliseResidual(pulses: IntArray, x: FloatArray, xOffset: Int, n: Int, energy: Float, gain: Float) {
        val g = CeltMath.rsqrt(energy) * gain
        for (i in 0 until n) x[xOffset + i] = g * pulses[i]
    }

    /** `extract_collapse_mask`: which of the [blocks] short blocks got any pulse. */
    private fun collapseMask(pulses: IntArray, n: Int, blocks: Int): Int {
        if (blocks <= 1) return 1
        val perBlock = n / blocks
        var mask = 0
        for (i in 0 until blocks) {
            var any = 0
            for (j in 0 until perBlock) any = any or pulses[i * perBlock + j]
            if (any != 0) mask = mask or (1 shl i)
        }
        return mask
    }

    /** `renormalise_vector`: scales [x] to the energy [gain] squared. */
    fun renormalise(x: FloatArray, offset: Int, n: Int, gain: Float) {
        val energy = EPSILON + CeltMath.innerProduct(x, offset, x, offset, n)
        val g = CeltMath.rsqrt(energy) * gain
        for (i in 0 until n) x[offset + i] = g * x[offset + i]
    }

    /**
     * `exp_rotation`: spreads the energy of a sparse vector across its
     * neighbours, or undoes that with [direction] -1, which is what the
     * decoder does. The rotation is applied in place.
     */
    fun expRotation(x: FloatArray, offset: Int, lenIn: Int, direction: Int, stride: Int, k: Int, spread: Int) {
        if (2 * k >= lenIn || spread == SPREAD_NONE) return
        val factor = SPREAD_FACTOR[spread - 1]
        val gain = lenIn.toFloat() / (lenIn + factor * k).toFloat()
        val theta = 0.5f * (gain * gain)
        val c = CeltMath.cosNorm(theta)
        val s = CeltMath.cosNorm(1f - theta)
        var stride2 = 0
        if (lenIn >= 8 * stride) {
            stride2 = 1
            while ((stride2 * stride2 + stride2) * stride + (stride shr 2) < lenIn) stride2++
        }
        val len = CeltMath.udiv(lenIn, stride)
        for (i in 0 until stride) {
            val at = offset + i * len
            if (direction < 0) {
                if (stride2 != 0) rotate(x, at, len, stride2, s, c)
                rotate(x, at, len, 1, c, s)
            } else {
                rotate(x, at, len, 1, c, -s)
                if (stride2 != 0) rotate(x, at, len, stride2, s, -c)
            }
        }
    }

    /** `exp_rotation1`: one pass of Givens rotations forwards and back. */
    private fun rotate(x: FloatArray, offset: Int, len: Int, stride: Int, c: Float, s: Float) {
        val ms = -s
        var p = offset
        for (i in 0 until len - stride) {
            val x1 = x[p]
            val x2 = x[p + stride]
            x[p + stride] = c * x2 + s * x1
            x[p++] = c * x1 + ms * x2
        }
        p = offset + len - 2 * stride - 1
        for (i in len - 2 * stride - 1 downTo 0) {
            val x1 = x[p]
            val x2 = x[p + stride]
            x[p + stride] = c * x2 + s * x1
            x[p--] = c * x1 + ms * x2
        }
    }

    const val SPREAD_NONE: Int = 0
    const val SPREAD_LIGHT: Int = 1
    const val SPREAD_NORMAL: Int = 2
    const val SPREAD_AGGRESSIVE: Int = 3

    private val SPREAD_FACTOR = intArrayOf(15, 10, 5)

    private const val EPSILON = 1e-15f
}

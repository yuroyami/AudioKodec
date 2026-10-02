package io.github.yuroyami.audiokodecs.internal.opus.celt

import io.github.yuroyami.audiokodecs.internal.opus.RangeDecoder

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
 * Translated from libopus `cwrs.c` and `vq.c`.
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

package io.github.yuroyami.audiokodec.internal.opus.celt

import io.github.yuroyami.audiokodec.internal.opus.RangeDecoder
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The arithmetic CELT shares between its stages.
 *
 * Most of CELT runs in floating point, but the parts the encoder and decoder
 * must agree on exactly, such as the angle a band is split at and the noise a
 * collapsed band is refilled with, are integer computations the specification
 * fixes to the bit. Those are here, translated from libopus `bands.c`,
 * `mathops.c` and `mathops.h`, alongside the few float helpers whose
 * precision is part of the reference's behaviour.
 */
internal object CeltMath {

    /** `celt_lcg_rand`: the linear congruential generator every noise fill uses. */
    fun lcgRand(seed: Int): Int = 1_664_525 * seed + 1_013_904_223

    /** `FRAC_MUL16`: a Q15 product of two 16-bit values, rounded. */
    fun fracMul16(a: Int, b: Int): Int = (16_384 + a.toShort().toInt() * b.toShort().toInt()) shr 15

    /** `bitexact_cos`: a cosine both ends compute identically, for the stereo and split angles. */
    fun bitexactCos(x: Int): Int {
        val tmp = (4096 + x * x) shr 13
        var x2 = tmp.toShort().toInt()
        x2 = ((32_767 - x2) + fracMul16(x2, -7651 + fracMul16(x2, 8277 + fracMul16(-626, x2)))).toShort().toInt()
        return 1 + x2
    }

    /** `bitexact_log2tan`: log2 of the ratio of two such cosines, in Q11. */
    fun bitexactLog2tan(sin: Int, cos: Int): Int {
        val lc = RangeDecoder.ilog(cos.toLong())
        val ls = RangeDecoder.ilog(sin.toLong())
        val icos = cos shl (15 - lc)
        val isin = sin shl (15 - ls)
        return (ls - lc) * (1 shl 11) +
            fracMul16(isin, fracMul16(isin, -2597) + 7932) -
            fracMul16(icos, fracMul16(icos, -2597) + 7932)
    }

    /** `isqrt32`: the integer square root of an unsigned 32-bit value. */
    fun isqrt32(value: Long): Int {
        var v = value
        var g = 0L
        var bshift = (RangeDecoder.ilog(v) - 1) shr 1
        var b = 1L shl bshift
        do {
            val t = ((g shl 1) + b) shl bshift
            if (t <= v) {
                g += b
                v -= t
            }
            b = b shr 1
            bshift--
        } while (bshift >= 0)
        return g.toInt()
    }

    /** `celt_udiv`: unsigned 32-bit division, which the reference uses on values that may look negative. */
    fun udiv(n: Int, d: Int): Int = ((n.toLong() and 0xFFFF_FFFFL) / d).toInt()

    /** `celt_exp2`: computed in double and narrowed, as the reference's float build does. */
    fun exp2(x: Float): Float = exp(0.6931471805599453094 * x).toFloat()

    /** `celt_log2`. */
    fun log2(x: Float): Float = (1.442695040888963387 * ln(x.toDouble())).toFloat()

    /** `celt_sqrt`. */
    fun sqrt(x: Float): Float = sqrt(x.toDouble()).toFloat()

    /** `celt_rsqrt`. */
    fun rsqrt(x: Float): Float = 1f / sqrt(x)

    /**
     * `celt_cos_norm`: the cosine of `x` quarter turns. The angle is formed in
     * single precision and only the cosine itself in double, as in the
     * reference.
     */
    fun cosNorm(x: Float): Float = cos(((0.5f * PI) * x).toDouble()).toFloat()

    /** `celt_inner_prod`, summed in index order. */
    fun innerProduct(x: FloatArray, xOffset: Int, y: FloatArray, yOffset: Int, n: Int): Float {
        var sum = 0f
        for (i in 0 until n) sum += x[xOffset + i] * y[yOffset + i]
        return sum
    }

    /** The reference's single-precision pi, which feeds `celt_cos_norm` in float. */
    private const val PI = 3.141592653f
}

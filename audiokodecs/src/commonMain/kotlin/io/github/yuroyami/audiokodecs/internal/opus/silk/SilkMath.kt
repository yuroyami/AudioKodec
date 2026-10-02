package io.github.yuroyami.audiokodecs.internal.opus.silk

/*
 * The fixed-point arithmetic SILK is written in.
 *
 * SILK is integer arithmetic in every build of libopus, float or fixed, and its
 * output is defined by that arithmetic to the last bit. Each helper here is one
 * of the reference's macros from silk/macros.h, silk/SigProc_FIX.h or
 * silk/Inlines.h, under a Kotlin name, with the same truncation, rounding and
 * wrapping. Three habits of the reference matter more than they look:
 *
 * - Many multiplies take only the low 16 bits of an operand as a signed value,
 *   so a 32-bit variable holding 40000 multiplies as -25536. That is what the
 *   `toShort()` calls reproduce, and leaving one out changes the output.
 * - Right shifts are arithmetic and round towards minus infinity, which is why
 *   the filters add a small bias before accumulating.
 * - Some sums are allowed to wrap, on purpose, because two wraps cancel. Kotlin
 *   `Int` arithmetic wraps the same way, so those need nothing special.
 *
 * Values the reference stores as 16-bit are kept in `Int` here, already
 * truncated or saturated to 16 bits wherever the reference truncates or
 * saturates them.
 */

internal const val INT16_MAX = 32767
internal const val INT16_MIN = -32768

/** `silk_SMULWB`: a 32-bit value times the low 16 bits of another, shifted down by 16. */
internal fun smulwb(a: Int, b: Int): Int = ((a.toLong() * b.toShort()) shr 16).toInt()

/** `silk_SMLAWB`: [smulwb] accumulated onto [acc]. */
internal fun smlawb(acc: Int, a: Int, b: Int): Int = (acc + ((a.toLong() * b.toShort()) shr 16)).toInt()

/** `silk_SMULWW`: two 32-bit values multiplied, shifted down by 16. */
internal fun smulww(a: Int, b: Int): Int = ((a.toLong() * b) shr 16).toInt()

/** `silk_SMLAWW`: [smulww] accumulated onto [acc]. */
internal fun smlaww(acc: Int, a: Int, b: Int): Int = (acc + ((a.toLong() * b) shr 16)).toInt()

/** `silk_SMULBB`: the low 16 bits of each, multiplied. */
internal fun smulbb(a: Int, b: Int): Int = a.toShort() * b.toShort()

/** `silk_SMLABB`: [smulbb] accumulated onto [acc]. */
internal fun smlabb(acc: Int, a: Int, b: Int): Int = acc + a.toShort() * b.toShort()

/** `silk_SMULTT`: the high 16 bits of each, multiplied. */
internal fun smultt(a: Int, b: Int): Int = (a shr 16) * (b shr 16)

/** `silk_SMMUL`: the high word of the 64-bit product. */
internal fun smmul(a: Int, b: Int): Int = ((a.toLong() * b) shr 32).toInt()

/** `silk_RSHIFT_ROUND`: a right shift that rounds half up. [shift] must be positive. */
internal fun rshiftRound(a: Int, shift: Int): Int =
    if (shift == 1) (a shr 1) + (a and 1) else ((a shr (shift - 1)) + 1) shr 1

/** `silk_RSHIFT_ROUND64`. */
internal fun rshiftRound64(a: Long, shift: Int): Long =
    if (shift == 1) (a shr 1) + (a and 1L) else ((a shr (shift - 1)) + 1L) shr 1

/** `silk_SAT16`. */
internal fun sat16(a: Int): Int = if (a > INT16_MAX) INT16_MAX else if (a < INT16_MIN) INT16_MIN else a

/** `silk_LIMIT`, which accepts its limits in either order. */
internal fun limit(a: Int, limit1: Int, limit2: Int): Int =
    if (limit1 > limit2) {
        if (a > limit1) limit1 else if (a < limit2) limit2 else a
    } else {
        if (a > limit2) limit2 else if (a < limit1) limit1 else a
    }

/** `silk_LSHIFT_SAT32`: saturates first, so the shift cannot overflow. */
internal fun lshiftSat32(a: Int, shift: Int): Int =
    limit(a, Int.MIN_VALUE shr shift, Int.MAX_VALUE shr shift) shl shift

/** `silk_ADD_SAT32`. */
internal fun addSat32(a: Int, b: Int): Int {
    val sum = a + b
    return if (sum and Int.MIN_VALUE == 0) {
        if ((a and b) and Int.MIN_VALUE != 0) Int.MIN_VALUE else sum
    } else {
        if ((a or b) and Int.MIN_VALUE == 0) Int.MAX_VALUE else sum
    }
}

/** `silk_SUB_SAT32`. */
internal fun subSat32(a: Int, b: Int): Int {
    val difference = a - b
    return if (difference and Int.MIN_VALUE == 0) {
        if (a and (b xor Int.MIN_VALUE) and Int.MIN_VALUE != 0) Int.MIN_VALUE else difference
    } else {
        if ((a xor Int.MIN_VALUE) and b and Int.MIN_VALUE != 0) Int.MAX_VALUE else difference
    }
}

/** `silk_abs`, which like the reference leaves the most negative value negative. */
internal fun silkAbs(a: Int): Int = if (a > 0) a else -a

/** `silk_CLZ32`. */
internal fun clz32(a: Int): Int = a.countLeadingZeroBits()

/** `silk_RAND`: the linear congruential generator SILK uses for dither and noise. */
internal fun silkRand(seed: Int): Int = 907_633_515 + seed * 196_314_165

/**
 * `silk_SQRT_APPROX`: a square root good to a few percent, from the position
 * of the leading one and the seven bits after it.
 */
internal fun sqrtApprox(x: Int): Int {
    if (x <= 0) return 0
    val leadingZeros = clz32(x)
    val fracQ7 = x.rotateRight(24 - leadingZeros) and 0x7F
    var y = if (leadingZeros and 1 != 0) 32768 else 46214
    y = y shr (leadingZeros shr 1)
    return smlawb(y, y, smulbb(213, fracQ7))
}

/**
 * `silk_DIV32_varQ`: approximately `(a32 shl qRes) / b32`, from a 14-bit
 * reciprocal refined once. The result is not the exact quotient, and the
 * decoder's output depends on it being exactly this approximation.
 */
internal fun div32VarQ(a32: Int, b32: Int, qRes: Int): Int {
    val aHeadroom = clz32(silkAbs(a32)) - 1
    var aNorm = a32 shl aHeadroom
    val bHeadroom = clz32(silkAbs(b32)) - 1
    val bNorm = b32 shl bHeadroom
    val bInverse = (Int.MAX_VALUE shr 2) / (bNorm shr 16)
    var result = smulwb(aNorm, bInverse)
    // The residual may wrap; its final value is always small.
    aNorm -= smmul(bNorm, result) shl 3
    result = smlawb(result, aNorm, bInverse)
    val shift = 29 + aHeadroom - bHeadroom - qRes
    return when {
        shift < 0 -> lshiftSat32(result, -shift)
        shift < 32 -> result shr shift
        else -> 0
    }
}

/** `silk_INVERSE32_varQ`: approximately `(1 shl qRes) / b32`. */
internal fun inverse32VarQ(b32: Int, qRes: Int): Int {
    val bHeadroom = clz32(silkAbs(b32)) - 1
    val bNorm = b32 shl bHeadroom
    val bInverse = (Int.MAX_VALUE shr 2) / (bNorm shr 16)
    var result = bInverse shl 16
    val errorQ32 = ((1 shl 29) - smulwb(bNorm, bInverse)) shl 3
    result = smlaww(result, errorQ32, bInverse)
    val shift = 61 - bHeadroom - qRes
    return when {
        shift <= 0 -> lshiftSat32(result, -shift)
        shift < 32 -> result shr shift
        else -> 0
    }
}

/**
 * Translates `silk_log2lin`: two to the power of a Q7 logarithm, with a
 * parabola standing in for the fractional part.
 */
internal fun log2lin(inLogQ7: Int): Int {
    if (inLogQ7 < 0) return 0
    if (inLogQ7 >= 3967) return Int.MAX_VALUE
    var out = 1 shl (inLogQ7 shr 7)
    val fracQ7 = inLogQ7 and 0x7F
    val parabola = smlawb(fracQ7, smulbb(fracQ7, 128 - fracQ7), -174)
    out = if (inLogQ7 < 2048) {
        out + ((out * parabola) shr 7)
    } else {
        out + (out shr 7) * parabola
    }
    return out
}

/**
 * Translates `silk_sum_sqr_shift`: the energy of [length] 16-bit samples from
 * [offset], shifted down far enough to leave two bits of headroom.
 *
 * Writes the energy to `result[0]` and the shift to `result[1]`.
 */
internal fun sumSqrShift(result: IntArray, x: IntArray, offset: Int, length: Int) {
    // A first pass with the largest shift that could be needed finds how big
    // the sum is; the second pass then uses the smallest shift that fits.
    var shift = 31 - clz32(length)
    var energy = length
    var i = 0
    while (i < length - 1) {
        var pair = smulbb(x[offset + i], x[offset + i])
        pair += smulbb(x[offset + i + 1], x[offset + i + 1])
        energy += pair ushr shift
        i += 2
    }
    if (i < length) {
        energy += smulbb(x[offset + i], x[offset + i]) ushr shift
    }
    shift = maxOf(0, shift + 3 - clz32(energy))
    energy = 0
    i = 0
    while (i < length - 1) {
        var pair = smulbb(x[offset + i], x[offset + i])
        pair += smulbb(x[offset + i + 1], x[offset + i + 1])
        energy += pair ushr shift
        i += 2
    }
    if (i < length) {
        energy += smulbb(x[offset + i], x[offset + i]) ushr shift
    }
    result[0] = energy
    result[1] = shift
}

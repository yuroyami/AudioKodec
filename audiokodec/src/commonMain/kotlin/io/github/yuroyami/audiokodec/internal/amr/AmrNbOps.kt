/*
 * Translated from opencore-amr 0.1.6, Copyright 1998-2009 PacketVideo and
 * licensed under the Apache License 2.0, with portions from the 3GPP AMR
 * reference code (3GPP TS 26.073) distributed under it. See NOTICE and
 * reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.amr

/**
 * The fixed-point operations the AMR narrowband decoder is written in, as
 * opencore-amr's portable C implements them.
 *
 * Values travel as Int. A Word16 in the reference is an Int between -32768
 * and 32767 here, and [s16] is the conversion C applies where it stores a
 * wider value into one. A Word32 is an Int, whose arithmetic wraps as the
 * reference's does on every platform it is built for. The reference also
 * keeps an overflow flag, which nothing in the decoder reads, so it is left
 * out.
 */
internal object AmrNbOps {
    const val MAX_16 = 0x7fff
    const val MIN_16 = -0x8000
    const val MAX_32 = 0x7fffffff
    const val MIN_32 = -0x7fffffff - 1

    /** The conversion of a wider value to Word16. */
    fun s16(x: Int): Int = x.toShort().toInt()

    fun add16(var1: Int, var2: Int): Int {
        val sum = var1 + var2
        return when {
            sum > 0x7fff -> MAX_16
            sum < -32768 -> MIN_16
            else -> sum
        }
    }

    fun sub(var1: Int, var2: Int): Int {
        val diff = var1 - var2
        return when {
            diff > 0x7fff -> MAX_16
            diff < -32768 -> MIN_16
            else -> diff
        }
    }

    fun mult(var1: Int, var2: Int): Int {
        val product = (var1 * var2) shr 15
        return if (product > 0x7fff) MAX_16 else s16(product)
    }

    fun multR(var1: Int, var2: Int): Int {
        var product = var1 * var2 + 0x4000
        product = product shr 15
        product = product or -(product and 0x10000)
        return when {
            product > 0x7fff -> MAX_16
            product < -32768 -> MIN_16
            else -> product
        }
    }

    fun lMult(var1: Int, var2: Int): Int {
        val product = var1 * var2
        return if (product != 0x40000000) product shl 1 else MAX_32
    }

    fun lAdd(a: Int, b: Int): Int {
        var sum = a + b
        if ((a xor b) >= 0 && (sum xor a) < 0) sum = if (a < 0) MIN_32 else MAX_32
        return sum
    }

    fun lSub(a: Int, b: Int): Int {
        var diff = a - b
        if ((a xor b) < 0 && ((diff xor a) and MIN_32) != 0) diff = if (a < 0) MIN_32 else MAX_32
        return diff
    }

    fun lMac(acc: Int, var1: Int, var2: Int): Int {
        val result = var1 * var2
        if (result == 0x40000000) return MAX_32
        var sum = (result shl 1) + acc
        if ((acc xor result) > 0 && (sum xor acc) < 0) sum = if (acc < 0) MIN_32 else MAX_32
        return sum
    }

    fun lMsu(acc: Int, var1: Int, var2: Int): Int = lSub(acc, lMult(var1, var2))

    fun shl(var1: Int, var2: Int): Int {
        if (var2 < 0) {
            val n = -var2
            return if (n < 15) var1 shr n else 0
        }
        val out = s16(var1 shl var2)
        return if (out shr var2 != var1) s16((var1 shr 15) xor MAX_16) else out
    }

    fun shr(var1: Int, var2: Int): Int {
        if (var2 == 0) return var1
        if (var2 > 0) return var1 shr minOf(var2, 15)
        val n = minOf(-var2, 15)
        val result = s16(var1 shl n)
        return if (result shr n != var1) (if (var1 > 0) MAX_16 else MIN_16) else result
    }

    fun shrR(var1: Int, var2: Int): Int {
        if (var2 > 15) return 0
        var out = shr(var1, var2)
        if (var2 > 0 && (var1 and (1 shl (var2 - 1))) != 0) out = s16(out + 1)
        return out
    }

    fun lShl(value: Int, var2: Int): Int {
        if (var2 > 0) {
            val out = value shl var2
            return if (out shr var2 != value) (value shr 31) xor MAX_32 else out
        }
        val n = -var2
        return if (n < 31) value shr n else 0
    }

    fun lShr(value: Int, var2: Int): Int {
        if (var2 > 0) return if (var2 < 31) value shr var2 else 0
        val n = -var2
        val out = value shl n
        return if (out shr n != value) (value shr 31) xor MAX_32 else out
    }

    fun lShrR(value: Int, var2: Int): Int {
        if (var2 > 31) return 0
        var result = lShr(value, var2)
        if (var2 > 0 && (value and (1 shl (var2 - 1))) != 0) result++
        return result
    }

    fun pvRound(value: Int): Int = s16(lAdd(value, 0x8000) shr 16)

    fun negate(var1: Int): Int = if (var1 == MIN_16) MAX_16 else -var1

    fun absS(var1: Int): Int {
        val y = s16(var1 - if (var1 < 0) 1 else 0)
        return s16(y xor (y shr 15))
    }

    fun normS(value: Int): Int {
        if (value == 0) return 0
        var out = 0
        val y = s16(value - if (value < 0) 1 else 0)
        var v = s16(y xor (y shr 15))
        while ((0x4000 and v) == 0) {
            out++
            if ((0x2000 and v) != 0) break
            out++
            if ((0x1000 and v) != 0) break
            out++
            if ((0x0800 and v) != 0) break
            out++
            v = s16(v shl 4)
        }
        return out
    }

    fun normL(value: Int): Int {
        if (value == 0) return 0
        var out = 0
        val y = value - if (value < 0) 1 else 0
        var v = y xor (y shr 31)
        while ((0x40000000 and v) == 0) {
            out++
            if ((0x20000000 and v) != 0) break
            out++
            if ((0x10000000 and v) != 0) break
            out++
            if ((0x08000000 and v) != 0) break
            out++
            v = v shl 4
        }
        return out
    }

    fun divS(var1: Int, var2: Int): Int {
        if (var1 > var2 || var1 < 0) return 0
        if (var1 == 0) return 0
        if (var1 == var2) return MAX_16
        var out = 0
        var num = var1
        val denom = var2
        val denom2 = denom shl 1
        val denom4 = denom shl 2
        for (iteration in 5 downTo 1) {
            out = s16(out shl 3)
            num = num shl 3
            if (num >= denom4) {
                num -= denom4
                out = out or 4
            }
            if (num >= denom2) {
                num -= denom2
                out = out or 2
            }
            if (num >= denom) {
                num -= denom
                out = out or 1
            }
        }
        return out
    }

    fun mpy32x16(hi: Int, lo: Int, n: Int): Int {
        var product = hi * n
        product = if (product != 0x40000000) product shl 1 else MAX_32
        val result = (lo * n) shr 15
        var sum = product + (result shl 1)
        if ((product xor result) > 0 && (sum xor product) < 0) sum = if (product < 0) MIN_32 else MAX_32
        return sum
    }

    /** `Log2_norm`: the exponent and fraction of a normalised value. */
    fun log2Norm(value: Int, exp: Int, out: IntArray) {
        if (value <= 0) {
            out[0] = 0
            out[1] = 0
            return
        }
        out[0] = 30 - exp
        val x = value shr 10
        var i = s16(x shr 15)
        val a = x and 0x7fff
        i -= 32
        var y = AmrNbTables.LOG2[i].toInt() shl 16
        val tmp = s16(AmrNbTables.LOG2[i] - AmrNbTables.LOG2[i + 1])
        y -= (tmp * a) shl 1
        out[1] = s16(y shr 16)
    }

    /** `Log2`: [out] receives the exponent and the fraction. */
    fun log2(value: Int, out: IntArray) {
        val exp = normL(value)
        log2Norm(value shl exp, exp, out)
    }

    fun pow2(exponent: Int, fraction: Int): Int {
        var x = lMult(fraction, 32)
        val i = s16(x shr 16) and 31
        val a = s16((x shr 1) and 0x7fff)
        x = AmrNbTables.POW2[i].toInt() shl 16
        val tmp = s16(AmrNbTables.POW2[i] - AmrNbTables.POW2[i + 1])
        x = lMsu(x, tmp, a)
        return lShrR(x, s16(30 - exponent))
    }

    fun invSqrt(value: Int): Int {
        if (value <= 0) return 0x3fffffff
        var x = value
        var exp = normL(x)
        x = x shl exp
        exp = 30 - exp
        if ((exp and 1) == 0) x = x shr 1
        exp = (exp shr 1) + 1
        x = x shr 9
        var i = s16(x shr 16)
        val a = s16(x shr 1) and 0x7fff
        i -= 16
        var y = AmrNbTables.INV_SQRT[i].toInt() shl 16
        val tmp = s16(AmrNbTables.INV_SQRT[i] - AmrNbTables.INV_SQRT[i + 1])
        y -= (tmp * a) shl 1
        return y shr exp
    }

    /** `sqrt_l_exp`: returns the root and leaves the exponent in [exp] at 0. */
    fun sqrtLExp(value: Int, exp: IntArray): Int {
        if (value <= 0) {
            exp[0] = 0
            return 0
        }
        val e = normL(value) and 0xFFFE
        var x = lShl(value, e)
        exp[0] = e
        x = x shr 10
        var i = s16(x shr 15) and 63
        val a = s16(x) and 0x7fff
        if (i > 15) i -= 16
        var y = AmrNbTables.SQRT_L[i].toInt() shl 16
        val tmp = s16(AmrNbTables.SQRT_L[i] - AmrNbTables.SQRT_L[i + 1])
        y = lMsu(y, tmp, a)
        return y
    }

    /** `gmed_n`: the median of the first [n] values from [from]. */
    fun gmedN(ind: IntArray, from: Int, n: Int): Int {
        val tmp = IntArray(n)
        val tmp2 = IntArray(n)
        for (i in 0 until n) tmp2[i] = ind[from + i]
        var ix = 0
        for (i in 0 until n) {
            var max = -32767
            for (j in 0 until n) {
                if (tmp2[j] >= max) {
                    max = tmp2[j]
                    ix = j
                }
            }
            tmp2[ix] = -32768
            tmp[i] = ix
        }
        return ind[from + tmp[n shr 1]]
    }
}

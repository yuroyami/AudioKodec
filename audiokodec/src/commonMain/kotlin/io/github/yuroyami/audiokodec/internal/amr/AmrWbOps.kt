/*
 * Translated from opencore-amr 0.1.6, Copyright 1998-2009 PacketVideo and
 * licensed under the Apache License 2.0, with portions from the 3GPP AMR-WB
 * reference code (3GPP TS 26.173) distributed under it. See NOTICE and
 * reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.amr

/**
 * The fixed-point operations the AMR wideband decoder is written in, as
 * opencore-amr's portable C implements them. They are not quite the
 * narrowband decoder's: the wideband decoder saturates and shifts in its own
 * way, and its `norm_s` is defined through `normalize_amr_wb`, so each is
 * translated from the wideband sources.
 *
 * As in [AmrNbOps], an int16 is an Int between -32768 and 32767 and an int32
 * is an Int whose arithmetic wraps.
 */
internal object AmrWbOps {
    const val MAX_16 = 0x7fff
    const val MIN_16 = -0x8000
    const val MAX_32 = 0x7fffffff
    const val MIN_32 = -0x7fffffff - 1

    fun s16(x: Int): Int = x.toShort().toInt()

    fun addInt16(var1: Int, var2: Int): Int {
        var sum = var1 + var2
        if ((sum shr 15) != (sum shr 31)) sum = (sum shr 31) xor MAX_16
        return s16(sum)
    }

    fun subInt16(var1: Int, var2: Int): Int {
        var diff = var1 - var2
        if ((diff shr 15) != (diff shr 31)) diff = (diff shr 31) xor MAX_16
        return s16(diff)
    }

    fun multInt16(var1: Int, var2: Int): Int {
        var product = (var1 * var2) shr 15
        if ((product shr 15) != (product shr 31)) product = (product shr 31) xor MAX_16
        return s16(product)
    }

    fun addInt32(a: Int, b: Int): Int {
        var out = a + b
        if (((a xor b) and MIN_32) == 0 && ((out xor a) and MIN_32) != 0) out = (a shr 31) xor MAX_32
        return out
    }

    fun subInt32(a: Int, b: Int): Int {
        var out = a - b
        if (((a xor b) and MIN_32) != 0 && ((out xor a) and MIN_32) != 0) out = (a shr 31) xor MAX_32
        return out
    }

    fun mul16(var1: Int, var2: Int): Int {
        val m = var1 * var2
        return if (m != 0x40000000) m shl 1 else MAX_32
    }

    fun mac16(acc: Int, var1: Int, var2: Int): Int {
        val m = mul16(var1, var2)
        var out = acc + m
        if (((m xor acc) and MIN_32) == 0 && ((out xor acc) and MIN_32) != 0) out = (acc shr 31) xor MAX_32
        return out
    }

    fun msu16(acc: Int, var1: Int, var2: Int): Int {
        val m = mul16(var1, var2)
        var out = acc - m
        if (((m xor acc) and MIN_32) != 0 && ((out xor acc) and MIN_32) != 0) out = (acc shr 31) xor MAX_32
        return out
    }

    fun round(value: Int): Int = s16((if (value != MAX_32) value + 0x8000 else value) shr 16)

    fun shl1Round(value: Int): Int =
        if ((value shl 1) shr 1 == value) s16((value + 0x4000) shr 15) else s16(((value shr 31) xor MAX_32) shr 16)

    fun mul32by16(hi: Int, lo: Int, n: Int): Int = ((hi * n) + ((lo * n) shr 15)) shl 1

    fun mul32by16b(value: Int, var2: Int): Int = ((value.toLong() * (var2 shl 16).toLong()) shr 32).toInt()

    fun shlInt16(var1: Int, var2: Int): Int {
        if (var2 < 0) return var1 shr ((-var2) and 0xf)
        val n = var2 and 0xf
        val out = s16(var1 shl n)
        return if (out shr n != var1) s16((var1 shr 15) xor MAX_16) else out
    }

    fun shlInt32(value: Int, var2: Int): Int {
        if (var2 > 0) {
            val out = value shl var2
            return if (out shr var2 != value) (value shr 31) xor MAX_32 else out
        }
        return value shr ((-var2) and 0xf)
    }

    fun shrInt32(value: Int, var2: Int): Int {
        if (var2 >= 0) return value shr (var2 and 0x1f)
        val n = s16(-var2) and 0x1f
        val out = value shl n
        return if (out shr n != value) (value shr 31) xor MAX_32 else out
    }

    fun negateInt16(var1: Int): Int = if (var1 == MIN_16) MAX_16 else -var1

    fun normalize(value: Int): Int {
        var i = when {
            value > 0x0FFFFFFF -> 0
            value > 0x00FFFFFF -> 3
            value > 0x0000FFFF -> if (value > 0x000FFFFF) 7 else 11
            value > 0x000000FF -> if (value > 0x00000FFF) 15 else 19
            else -> if (value > 0x0000000F) 23 else 27
        }
        when ((value shl i) and 0x78000000) {
            0x08000000 -> i += 3
            0x18000000, 0x10000000 -> i += 2
            0x28000000, 0x20000000, 0x38000000, 0x30000000 -> i++
        }
        return i
    }

    /** `norm_s`, which the wideband decoder defines through [normalize]. */
    fun normS(value: Int): Int = normalize(value) - 16

    fun multInt16R(var1: Int, var2: Int): Int {
        var p = var1 * var2 + 0x4000
        p = p shr 15
        if ((p shr 15) != (p shr 31)) p = (p shr 31) xor MAX_16
        return s16(p)
    }

    fun shrRnd(var1: Int, var2: Int): Int {
        var out = s16(var1 shr (var2 and 0xf))
        if (var2 != 0 && (var1 and (1 shl (var2 - 1))) != 0) out = s16(out + 1)
        return out
    }

    fun div16by16(var1: Int, var2: Int): Int {
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

    /** `one_ov_sqrt_norm` on [fe], which holds the fraction and the exponent. */
    fun oneOvSqrtNorm(fe: IntArray) {
        if (fe[0] <= 0) {
            fe[1] = 0
            fe[0] = MAX_32
            return
        }
        if ((fe[1] and 1) == 1) fe[0] = fe[0] shr 1
        fe[1] = negateInt16(s16((fe[1] - 1) shr 1))
        fe[0] = fe[0] shr 9
        var i = s16(fe[0] shr 16)
        fe[0] = fe[0] shr 1
        val a = s16(fe[0]) and 0x7fff
        i -= 16
        val table = AmrWbTables.ISQRT
        fe[0] = table[i].toInt() shl 16
        val tmp = s16(table[i] - table[i + 1])
        fe[0] = msu16(fe[0], tmp, a)
    }

    fun oneOvSqrt(value: Int): Int {
        val fe = IntArray(2)
        var exp = normalize(value)
        fe[0] = value shl exp
        exp = 31 - exp
        fe[1] = exp
        oneOvSqrtNorm(fe)
        return shlInt32(fe[0], fe[1])
    }

    fun powerOf2(exponent: Int, fraction: Int): Int {
        var x = fraction shl 5
        val i = fraction shr 10
        val a = s16(x) and 0x7fff
        val table = AmrWbTables.POW2
        x = table[i].toInt() shl 15
        val tmp = s16(table[i] - table[i + 1])
        x -= tmp * a
        val exp = s16(29 - exponent)
        if (exp != 0) x = (x shr exp) + ((x shr (exp - 1)) and 1)
        return x
    }

    /** `Dot_product12`: the normalised sum, with its exponent left in [exp]. */
    fun dotProduct12(x: IntArray, xAt: Int, y: IntArray, yAt: Int, lg: Int, exp: IntArray): Int {
        var sum = 1
        for (i in 0 until lg) sum = mac16(sum, x[xAt + i], y[yAt + i])
        val sft = normalize(sum)
        sum = sum shl sft
        exp[0] = 30 - sft
        return sum
    }

    /** `Lg2_normalized`: [out] receives the exponent and the fraction. */
    fun lg2Normalized(value: Int, exp: Int, out: IntArray) {
        if (value <= 0) {
            out[0] = 0
            out[1] = 0
            return
        }
        out[0] = 30 - exp
        var x = value shr 9
        var i = s16(x shr 16)
        x = x shr 1
        val a = s16(x) and 0x7fff
        i -= 32
        val table = AmrWbTables.LOG2
        var y = table[i].toInt() shl 16
        val tmp = s16(table[i] - table[i + 1])
        y = msu16(y, tmp, a)
        out[1] = s16(y shr 16)
    }

    fun log2(value: Int, out: IntArray) {
        val exp = normalize(value)
        lg2Normalized(shlInt32(value, exp), exp, out)
    }

    fun mpyDpf32(hi1: Int, lo1: Int, hi2: Int, lo2: Int): Int {
        var l = mul16(hi1, hi2)
        l = mac16(l, multInt16(hi1, lo2), 1)
        l = mac16(l, multInt16(lo1, hi2), 1)
        return l
    }

    fun noiseGen(seed: IntArray, at: Int): Int {
        seed[at] = s16(seed[at] * 31821 + 13849)
        return seed[at]
    }
}

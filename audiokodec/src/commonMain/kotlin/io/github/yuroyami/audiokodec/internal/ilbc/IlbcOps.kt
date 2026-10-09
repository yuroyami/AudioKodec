/*
 * Translated from WebRTC's signal processing library, Copyright (c) 2011 The
 * WebRTC project authors, under the BSD licence reproduced in NOTICE, as
 * packaged by libilbc 3.0.4. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.ilbc

/**
 * The fixed-point helpers of WebRTC's signal processing library that the iLBC
 * decoder calls, in their portable C forms. A vector is an array and the
 * offset of its first element, as C passes a pointer.
 */
internal object IlbcOps {
    /** A store to `int16_t`. */
    fun s16(x: Int): Int = x.toShort().toInt()

    /** `WebRtcSpl_GetSizeInBits`, of [n] read as unsigned. */
    fun sizeInBits(n: Int): Int = 32 - n.countLeadingZeroBits()

    /** `WebRtcSpl_NormW32`. */
    fun normW32(a: Int): Int = if (a == 0) 0 else (if (a < 0) a.inv() else a).countLeadingZeroBits() - 1

    /** `WEBRTC_SPL_SHIFT_W32`: left for a positive [c], right for a negative one. */
    fun shiftW32(x: Int, c: Int): Int = if (c >= 0) x * (1 shl c) else x shr -c

    /** `WEBRTC_SPL_ABS_W32`. */
    fun absW32(a: Int): Int = if (a >= 0) a else -a

    /** `WebRtcSpl_SatW32ToW16`. */
    fun satW16(value: Int): Int = if (value > 32767) 32767 else if (value < -32768) -32768 else value

    /** `WebRtcSpl_DivW32W16`, with C's truncation towards zero. */
    fun divW32W16(num: Int, den: Int): Int = if (den != 0) num / den else 0x7FFFFFFF

    /** `WebRtcSpl_MaxAbsValueW16C`. */
    fun maxAbsW16(v: IntArray, at: Int, length: Int): Int {
        var maximum = 0
        for (i in 0 until length) {
            val absolute = kotlin.math.abs(v[at + i])
            if (absolute > maximum) maximum = absolute
        }
        return if (maximum > 32767) 32767 else maximum
    }

    /** `WebRtcSpl_MaxAbsValueW32C`, whose absolute value of the most negative number is that number, unsigned. */
    fun maxAbsW32(v: IntArray, at: Int, length: Int): Int {
        var maximum = 0L
        for (i in 0 until length) {
            val absolute = kotlin.math.abs(v[at + i]).toLong() and 0xFFFFFFFFL
            if (absolute > maximum) maximum = absolute
        }
        return minOf(maximum, 0x7FFFFFFFL).toInt()
    }

    /** `WebRtcSpl_MaxIndexW32`. */
    fun maxIndexW32(v: IntArray, at: Int, length: Int): Int {
        var index = 0
        var maximum = Int.MIN_VALUE
        for (i in 0 until length) {
            if (v[at + i] > maximum) {
                maximum = v[at + i]
                index = i
            }
        }
        return index
    }

    /** `WebRtcSpl_DotProductWithScale`: each product shifted, summed in 64 bits, saturated to 32. */
    fun dotProductWithScale(a: IntArray, aAt: Int, b: IntArray, bAt: Int, length: Int, scaling: Int): Int {
        var sum = 0L
        for (i in 0 until length) sum += (a[aAt + i] * b[bAt + i]) shr scaling
        return sum.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /** `WebRtcSpl_CrossCorrelationC`. */
    fun crossCorrelation(
        out: IntArray, seq1: IntArray, seq1At: Int, seq2: IntArray, seq2At: Int,
        dimSeq: Int, dimCross: Int, rightShifts: Int, stepSeq2: Int,
    ) {
        var s2 = seq2At
        for (i in 0 until dimCross) {
            var corr = 0
            for (j in 0 until dimSeq) corr += (seq1[seq1At + j] * seq2[s2 + j]) shr rightShifts
            s2 += stepSeq2
            out[i] = corr
        }
    }

    /** `WebRtcSpl_FilterMAFastQ12`. */
    fun filterMAFastQ12(input: IntArray, inAt: Int, out: IntArray, outAt: Int, b: IntArray, bAt: Int, bLength: Int, length: Int) {
        for (i in 0 until length) {
            var o = 0
            for (j in 0 until bLength) o += b[bAt + j] * input[inAt + i - j]
            o = o.coerceIn(-134217728, 134215679)
            out[outAt + i] = s16((o + 2048) shr 12)
        }
    }

    /** `WebRtcSpl_FilterARFastQ12`, whose state is the [coefLength] - 1 outputs before [outAt]. */
    fun filterARFastQ12(input: IntArray, inAt: Int, out: IntArray, outAt: Int, coef: IntArray, coefAt: Int, coefLength: Int, length: Int) {
        for (i in 0 until length) {
            var sum = 0L
            for (j in coefLength - 1 downTo 1) sum += coef[coefAt + j] * out[outAt + i - j]
            var output = coef[coefAt].toLong() * input[inAt + i] - sum
            output = output.coerceIn(-134217728L, 134215679L)
            out[outAt + i] = s16(((output + 2048) shr 12).toInt())
        }
    }

    /** `WebRtcSpl_DownsampleFastC`, for inputs long enough, as every call here is. */
    fun downsampleFast(
        input: IntArray, inAt: Int, out: IntArray, outLength: Int,
        coef: IntArray, coefLength: Int, factor: Int, delay: Int,
    ) {
        var o = 0
        val end = delay + factor * (outLength - 1) + 1
        var i = delay
        while (i < end) {
            var sum = 2048
            for (j in 0 until coefLength) sum += coef[j] * input[inAt + i - j]
            out[o++] = s16(satW16(sum shr 12))
            i += factor
        }
    }

    /** `WebRtcSpl_SqrtFloor`, Wilco Dijkstra's public domain successive approximation, with C's wrapping shifts. */
    fun sqrtFloor(input: Int): Int {
        var value = input
        var root = 0
        for (n in 15 downTo 0) {
            val try1 = root + (1 shl n)
            if (value >= try1 shl n) {
                value -= try1 shl n
                root = root or (2 shl n)
            }
        }
        return root shr 1
    }
}

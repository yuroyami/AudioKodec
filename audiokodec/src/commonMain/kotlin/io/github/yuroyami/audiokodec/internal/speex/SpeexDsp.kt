/*
 * Translated from libspeex 1.2.1, which is Copyright 2002-2008 Xiph.org
 * Foundation, Jean-Marc Valin and others and licensed under the BSD 3-Clause
 * licence, reproduced in NOTICE. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.speex

import kotlin.math.PI
import kotlin.math.sqrt

/*
 * The float build of libspeex, function by function. Its arithmetic macros
 * are plain float operations there, but a constant written as a double
 * literal makes C evaluate the expression in double precision and round once
 * on assignment, so each of those is written here as a double expression
 * rounded with toFloat() where the reference stores it.
 */
internal object SpeexDsp {

    /** `bw_lpc`: bandwidth expansion of an LPC filter by powers of [gamma]. */
    fun bwLpc(gamma: Float, input: FloatArray, output: FloatArray, order: Int) {
        var tmp = gamma
        for (i in 0 until order) {
            output[i] = tmp * input[i]
            tmp *= gamma
        }
    }

    /** `sanitize_values32`, which also turns a NaN into 0. */
    fun sanitize(v: FloatArray, at: Int, min: Float, max: Float, length: Int) {
        for (i in at until at + length) {
            if (!(v[i] >= min && v[i] <= max)) {
                v[i] = when {
                    v[i] < min -> min
                    v[i] > max -> max
                    else -> 0f
                }
            }
        }
    }

    /** `highpass` with the output filters, [filter] 1 for narrowband and 3 inside wideband. */
    fun highpass(x: FloatArray, at: Int, length: Int, filter: Int, mem: FloatArray) {
        val id = minOf(filter, 4)
        val d1 = SpeexTables.HIGHPASS_POLES[3 * id + 1]
        val d2 = SpeexTables.HIGHPASS_POLES[3 * id + 2]
        val n0 = SpeexTables.HIGHPASS_ZEROS[3 * id]
        val n1 = SpeexTables.HIGHPASS_ZEROS[3 * id + 1]
        val n2 = SpeexTables.HIGHPASS_ZEROS[3 * id + 2]
        for (i in at until at + length) {
            val xi = x[i]
            val vout = n0 * xi + mem[0]
            mem[0] = (mem[1] + n1 * xi) + (-d1) * vout
            mem[1] = n2 * xi + (-d2) * vout
            x[i] = vout
        }
    }

    /** `compute_rms16`: sqrt(0.1 + mean square), the mean in float and the root in double. */
    fun rms(x: FloatArray, at: Int, length: Int): Float {
        var sum = 0f
        for (i in at until at + length) sum += x[i] * x[i]
        return sqrt(0.1 + (sum / length).toDouble()).toFloat()
    }

    /** `inner_prod`, which sums in groups of four as the reference does. */
    fun innerProduct(x: FloatArray, xAt: Int, y: FloatArray, yAt: Int, length: Int): Float {
        var sum = 0f
        var i = 0
        while (i + 4 <= length) {
            var part = 0f
            part += x[xAt + i] * y[yAt + i]
            part += x[xAt + i + 1] * y[yAt + i + 1]
            part += x[xAt + i + 2] * y[yAt + i + 2]
            part += x[xAt + i + 3] * y[yAt + i + 3]
            sum += part
            i += 4
        }
        return sum
    }

    /** `iir_mem16`: the all-pole synthesis filter, in place when [x] is [y]. */
    fun iir(x: FloatArray, xAt: Int, den: FloatArray, y: FloatArray, yAt: Int, length: Int, order: Int, mem: FloatArray) {
        for (i in 0 until length) {
            val yi = x[xAt + i] + mem[0]
            val nyi = -yi
            for (j in 0 until order - 1) mem[j] = mem[j + 1] + den[j] * nyi
            mem[order - 1] = den[order - 1] * nyi
            y[yAt + i] = yi
        }
    }

    /** `speex_rand`: uniform noise of standard deviation [std]. */
    fun random(std: Float, seed: IntArray): Float {
        seed[0] = 1664525 * seed[0] + 1013904223
        var r = Float.fromBits(0x3f800000 or (seed[0] and 0x007fffff))
        r = (r - 1.5).toFloat()
        return (3.4642 * std * r).toFloat()
    }

    private const val C1 = 0.9999932946f
    private const val C2 = -0.4999124376f
    private const val C3 = 0.0414877472f
    private const val C4 = -0.0012712095f

    /** `spx_cos` of the float build: a polynomial in the angle, folded about pi/2. */
    fun cos(angle: Float): Float {
        var x = angle
        return if (x < 1.5707963268) {
            x *= x
            C1 + x * (C2 + x * (C3 + C4 * x))
        } else {
            x = (PI - x).toFloat()
            x *= x
            -(C1 + x * (C2 + x * (C3 + C4 * x)))
        }
    }

    /** `lsp_interpolate`, with the margins that keep the filter stable. */
    fun lspInterpolate(old: FloatArray, new: FloatArray, lsp: FloatArray, length: Int, subframe: Int, subframes: Int, margin: Float) {
        val tmp = (1.0f + subframe) / subframes
        for (i in 0 until length) lsp[i] = (1 - tmp) * old[i] + tmp * new[i]
        if (lsp[0] < margin) lsp[0] = margin
        if (lsp[length - 1] > PI - margin) lsp[length - 1] = (PI - margin).toFloat()
        for (i in 1 until length - 1) {
            if (lsp[i] < lsp[i - 1] + margin) lsp[i] = lsp[i - 1] + margin
            if (lsp[i] > lsp[i + 1] - margin) lsp[i] = .5f * (lsp[i] + lsp[i + 1] - margin)
        }
    }

    /** `lsp_to_lpc` of the float build. */
    fun lspToLpc(freq: FloatArray, ak: FloatArray, order: Int) {
        val m = order shr 1
        val wp = FloatArray(4 * m + 2)
        val xFreq = FloatArray(order) { cos(freq[it]) }
        var xin1 = 1.0f
        var xin2 = 1.0f
        for (j in 0..order) {
            var n4 = 0
            var i2 = 0
            for (i in 0 until m) {
                val n1 = i * 4
                val n2 = n1 + 1
                val n3 = n2 + 1
                n4 = n3 + 1
                val xout1 = xin1 - 2f * xFreq[i2] * wp[n1] + wp[n2]
                val xout2 = xin2 - 2f * xFreq[i2 + 1] * wp[n3] + wp[n4]
                wp[n2] = wp[n1]
                wp[n4] = wp[n3]
                wp[n1] = xin1
                wp[n3] = xin2
                xin1 = xout1
                xin2 = xout2
                i2 += 2
            }
            val xout1 = xin1 + wp[n4 + 1]
            val xout2 = xin2 - wp[n4 + 2]
            if (j > 0) ak[j - 1] = (xout1 + xout2) * 0.5f
            wp[n4 + 1] = xin1
            wp[n4 + 2] = xin2
            xin1 = 0.0f
            xin2 = 0.0f
        }
    }

    /** `qmf_synth`: the two bands of [x] at 0 and [x2] joined into [y], which may be [x]. */
    fun qmfSynth(x: FloatArray, x1At: Int, x2At: Int, a: FloatArray, y: FloatArray, yAt: Int, n: Int, m: Int, mem1: FloatArray, mem2: FloatArray) {
        val m2 = m shr 1
        val n2 = n shr 1
        val xx1 = FloatArray(m2 + n2)
        val xx2 = FloatArray(m2 + n2)
        for (i in 0 until n2) xx1[i] = x[x1At + n2 - 1 - i]
        for (i in 0 until m2) xx1[n2 + i] = mem1[2 * i + 1]
        for (i in 0 until n2) xx2[i] = x[x2At + n2 - 1 - i]
        for (i in 0 until m2) xx2[n2 + i] = mem2[2 * i + 1]
        var i = 0
        while (i < n2) {
            var y0 = 0f
            var y1 = 0f
            var y2 = 0f
            var y3 = 0f
            var x10 = xx1[n2 - 2 - i]
            var x20 = xx2[n2 - 2 - i]
            var j = 0
            while (j < m2) {
                var a0 = a[2 * j]
                var a1 = a[2 * j + 1]
                val x11 = xx1[n2 - 1 + j - i]
                val x21 = xx2[n2 - 1 + j - i]
                y0 += a0 * (x11 - x21)
                y1 += a1 * (x11 + x21)
                y2 += a0 * (x10 - x20)
                y3 += a1 * (x10 + x20)
                a0 = a[2 * j + 2]
                a1 = a[2 * j + 3]
                x10 = xx1[n2 + j - i]
                x20 = xx2[n2 + j - i]
                y0 += a0 * (x10 - x20)
                y1 += a1 * (x10 + x20)
                y2 += a0 * (x11 - x21)
                y3 += a1 * (x11 + x21)
                j += 2
            }
            y[yAt + 2 * i] = 2f * y0
            y[yAt + 2 * i + 1] = 2f * y1
            y[yAt + 2 * i + 2] = 2f * y2
            y[yAt + 2 * i + 3] = 2f * y3
            i += 2
        }
        for (k in 0 until m2) mem1[2 * k + 1] = xx1[k]
        for (k in 0 until m2) mem2[2 * k + 1] = xx2[k]
    }

    /** `interp_pitch`: the excitation [pitch] samples back, at the best of four fractional delays. */
    private fun interpolatePitch(exc: FloatArray, excAt: Int, interp: FloatArray, interpAt: Int, pitch: Int, length: Int) {
        val corr = Array(4) { FloatArray(7) }
        for (i in 0 until 7) corr[0][i] = innerProduct(exc, excAt, exc, excAt - pitch - 3 + i, length)
        for (i in 0 until 3) {
            for (j in 0 until 7) {
                val i1 = maxOf(3 - j, 0)
                val i2 = minOf(10 - j, 7)
                var tmp = 0f
                for (k in i1 until i2) tmp += SpeexTables.SHIFT_FILTER[7 * i + k] * corr[0][j + k - 3]
                corr[i + 1][j] = tmp
            }
        }
        var maxi = 0
        var maxj = 0
        var maxCorr = corr[0][0]
        for (i in 0 until 4) {
            for (j in 0 until 7) {
                if (corr[i][j] > maxCorr) {
                    maxCorr = corr[i][j]
                    maxi = i
                    maxj = j
                }
            }
        }
        val lag = pitch - maxj + 3
        for (i in 0 until length) {
            var tmp = 0f
            if (maxi > 0) {
                for (k in 0 until 7) tmp += exc[excAt + i - lag + k - 3] * SpeexTables.SHIFT_FILTER[7 * (maxi - 1) + k]
            } else {
                tmp = exc[excAt + i - lag]
            }
            interp[interpAt + i] = tmp
        }
    }

    /** `multicomb`: the perceptual enhancer's comb filter over two subframes. */
    fun multicomb(exc: FloatArray, excAt: Int, newExc: FloatArray, newAt: Int, nsf: Int, pitch: Int, maxPitch: Int, combGain: Float) {
        val iexc = FloatArray(2 * nsf)
        interpolatePitch(exc, excAt, iexc, 0, pitch, 80)
        if (pitch > maxPitch) {
            interpolatePitch(exc, excAt, iexc, nsf, 2 * pitch, 80)
        } else {
            interpolatePitch(exc, excAt, iexc, nsf, -pitch, 80)
        }
        val iexc0Mag = sqrt((1000 + innerProduct(iexc, 0, iexc, 0, nsf)).toDouble()).toFloat()
        val iexc1Mag = sqrt((1000 + innerProduct(iexc, nsf, iexc, nsf, nsf)).toDouble()).toFloat()
        val excMag = sqrt((1 + innerProduct(exc, excAt, exc, excAt, nsf)).toDouble()).toFloat()
        var corr0 = innerProduct(iexc, 0, exc, excAt, nsf)
        if (corr0 < 0) corr0 = 0f
        var corr1 = innerProduct(iexc, nsf, exc, excAt, nsf)
        if (corr1 < 0) corr1 = 0f
        val pgain1 = if (corr0 > iexc0Mag * excMag) 1f else corr0 / excMag / iexc0Mag
        val pgain2 = if (corr1 > iexc1Mag * excMag) 1f else corr1 / excMag / iexc1Mag
        val gg1 = excMag / iexc0Mag
        val gg2 = excMag / iexc1Mag
        val c1: Float
        val c2: Float
        if (combGain > 0) {
            c1 = (.4 * combGain + .07).toFloat()
            c2 = (.5 + 1.72 * (c1 - .07)).toFloat()
        } else {
            c1 = 0f
            c2 = 0f
        }
        var g1 = 1 - c2 * pgain1 * pgain1
        var g2 = 1 - c2 * pgain2 * pgain2
        if (g1 < c1) g1 = c1
        if (g2 < c1) g2 = c1
        g1 = c1 / g1
        g2 = c1 / g2
        val gain0: Float
        val gain1: Float
        if (pitch > maxPitch) {
            gain0 = (.7 * (g1 * gg1)).toFloat()
            gain1 = (.3 * (g2 * gg2)).toFloat()
        } else {
            gain0 = (.6 * (g1 * gg1)).toFloat()
            gain1 = (.6 * (g2 * gg2)).toFloat()
        }
        for (i in 0 until nsf) newExc[newAt + i] = exc[excAt + i] + (gain0 * iexc[i] + gain1 * iexc[i + nsf])
        var newEnergy = rms(newExc, newAt, nsf)
        var oldEnergy = rms(exc, excAt, nsf)
        if (oldEnergy < 1) oldEnergy = 1f
        if (newEnergy < 1) newEnergy = 1f
        if (oldEnergy > newEnergy) oldEnergy = newEnergy
        val ngain = oldEnergy / newEnergy
        for (i in 0 until nsf) newExc[newAt + i] = ngain * newExc[newAt + i]
    }
}

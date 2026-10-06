package io.github.yuroyami.audiokodec.internal.opus.celt

/**
 * The time-domain filters CELT runs around its transform: the pitch
 * postfilter after every frame, and the linear prediction and pitch search
 * that concealment uses to extrapolate a lost one.
 *
 * Translated from libopus `celt.c` (`comb_filter`), `celt_lpc.c` and
 * `pitch.c`, float build. Every shift and rounding the fixed-point build does
 * in these functions is the identity in float, which is why none appear here.
 */
internal object CeltFilters {

    const val COMBFILTER_MINPERIOD: Int = 15

    private val COMB_GAINS = arrayOf(
        floatArrayOf(0.3066406250f, 0.2170410156f, 0.1296386719f),
        floatArrayOf(0.4638671875f, 0.2680664062f, 0f),
        floatArrayOf(0.7998046875f, 0.1000976562f, 0f),
    )

    /**
     * `comb_filter`: the pitch postfilter, cross-fading over the first
     * [overlap] samples from the previous frame's period and gain to this
     * one's. Reads up to `max(t0, t1) + 2` samples before [xOffset], so [x]
     * must hold that much history. [y] may be [x] itself.
     */
    fun comb(
        y: FloatArray,
        yOffset: Int,
        x: FloatArray,
        xOffset: Int,
        t0In: Int,
        t1In: Int,
        n: Int,
        g0: Float,
        g1: Float,
        tapset0: Int,
        tapset1: Int,
        window: FloatArray?,
        overlapIn: Int,
    ) {
        if (g0 == 0f && g1 == 0f) {
            if (x !== y || xOffset != yOffset) x.copyInto(y, yOffset, xOffset, xOffset + n)
            return
        }
        val t0 = maxOf(t0In, COMBFILTER_MINPERIOD)
        val t1 = maxOf(t1In, COMBFILTER_MINPERIOD)
        val g00 = g0 * COMB_GAINS[tapset0][0]
        val g01 = g0 * COMB_GAINS[tapset0][1]
        val g02 = g0 * COMB_GAINS[tapset0][2]
        val g10 = g1 * COMB_GAINS[tapset1][0]
        val g11 = g1 * COMB_GAINS[tapset1][1]
        val g12 = g1 * COMB_GAINS[tapset1][2]
        var x1 = x[xOffset - t1 + 1]
        var x2 = x[xOffset - t1]
        var x3 = x[xOffset - t1 - 1]
        var x4 = x[xOffset - t1 - 2]
        val overlap = if (g0 == g1 && t0 == t1 && tapset0 == tapset1) 0 else overlapIn
        var i = 0
        while (i < overlap) {
            val x0 = x[xOffset + i - t1 + 2]
            val f = window!![i] * window[i]
            y[yOffset + i] = x[xOffset + i] +
                ((1f - f) * g00) * x[xOffset + i - t0] +
                ((1f - f) * g01) * (x[xOffset + i - t0 + 1] + x[xOffset + i - t0 - 1]) +
                ((1f - f) * g02) * (x[xOffset + i - t0 + 2] + x[xOffset + i - t0 - 2]) +
                (f * g10) * x2 +
                (f * g11) * (x1 + x3) +
                (f * g12) * (x0 + x4)
            x4 = x3
            x3 = x2
            x2 = x1
            x1 = x0
            i++
        }
        if (g1 == 0f) {
            if (x !== y || xOffset != yOffset) x.copyInto(y, yOffset + overlap, xOffset + overlap, xOffset + n)
            return
        }
        combConst(y, yOffset + i, x, xOffset + i, t1, n - i, g10, g11, g12)
    }

    /** `comb_filter_const`: the postfilter with a fixed period and gain. */
    private fun combConst(y: FloatArray, yOffset: Int, x: FloatArray, xOffset: Int, t: Int, n: Int, g10: Float, g11: Float, g12: Float) {
        var x4 = x[xOffset - t - 2]
        var x3 = x[xOffset - t - 1]
        var x2 = x[xOffset - t]
        var x1 = x[xOffset - t + 1]
        for (i in 0 until n) {
            val x0 = x[xOffset + i - t + 2]
            y[yOffset + i] = x[xOffset + i] + g10 * x2 + g11 * (x1 + x3) + g12 * (x0 + x4)
            x4 = x3
            x3 = x2
            x2 = x1
            x1 = x0
        }
    }

    /** `_celt_lpc`: Levinson-Durbin recursion from autocorrelation [ac] to [p] coefficients. */
    fun lpc(lpc: FloatArray, lpcOffset: Int, ac: FloatArray, p: Int) {
        var error = ac[0]
        lpc.fill(0f, lpcOffset, lpcOffset + p)
        if (ac[0] > 1e-10f) {
            for (i in 0 until p) {
                var rr = 0f
                for (j in 0 until i) rr += lpc[lpcOffset + j] * ac[i - j]
                rr += ac[i + 1]
                val r = -(rr / error)
                lpc[lpcOffset + i] = r
                for (j in 0 until ((i + 1) shr 1)) {
                    val tmp1 = lpc[lpcOffset + j]
                    val tmp2 = lpc[lpcOffset + i - 1 - j]
                    lpc[lpcOffset + j] = tmp1 + r * tmp2
                    lpc[lpcOffset + i - 1 - j] = tmp2 + r * tmp1
                }
                error -= (r * r) * error
                if (error <= 0.001f * ac[0]) break
            }
        }
    }

    /**
     * `celt_fir`: filters [n] samples of [x] from [xOffset] through [num],
     * reading [order] samples of history before it, into [y].
     */
    fun fir(x: FloatArray, xOffset: Int, num: FloatArray, numOffset: Int, y: FloatArray, yOffset: Int, n: Int, order: Int) {
        val rnum = FloatArray(order) { num[numOffset + order - it - 1] }
        for (i in 0 until n) {
            var sum = x[xOffset + i]
            for (j in 0 until order) sum += rnum[j] * x[xOffset + i + j - order]
            y[yOffset + i] = sum
        }
    }

    /**
     * `celt_iir`: the all-pole filter [den] over [n] samples, in place when
     * [x] and [y] are the same region, carrying [order] samples of state in
     * [mem]. Kept in the reference's four-at-a-time form, whose summation order
     * differs from a plain loop's.
     */
    fun iir(x: FloatArray, xOffset: Int, den: FloatArray, denOffset: Int, y: FloatArray, yOffset: Int, n: Int, order: Int, mem: FloatArray) {
        val rden = FloatArray(order) { den[denOffset + order - it - 1] }
        val buf = FloatArray(n + order)
        for (i in 0 until order) buf[i] = -mem[order - i - 1]
        val sum = FloatArray(4)
        var i = 0
        while (i < n - 3) {
            sum[0] = x[xOffset + i]
            sum[1] = x[xOffset + i + 1]
            sum[2] = x[xOffset + i + 2]
            sum[3] = x[xOffset + i + 3]
            xcorrKernel(rden, 0, buf, i, sum, order)
            buf[i + order] = -sum[0]
            y[yOffset + i] = sum[0]
            sum[1] += buf[i + order] * den[denOffset]
            buf[i + order + 1] = -sum[1]
            y[yOffset + i + 1] = sum[1]
            sum[2] += buf[i + order + 1] * den[denOffset]
            sum[2] += buf[i + order] * den[denOffset + 1]
            buf[i + order + 2] = -sum[2]
            y[yOffset + i + 2] = sum[2]
            sum[3] += buf[i + order + 2] * den[denOffset]
            sum[3] += buf[i + order + 1] * den[denOffset + 1]
            sum[3] += buf[i + order] * den[denOffset + 2]
            buf[i + order + 3] = -sum[3]
            y[yOffset + i + 3] = sum[3]
            i += 4
        }
        while (i < n) {
            var s = x[xOffset + i]
            for (j in 0 until order) s -= rden[j] * buf[i + j]
            buf[i + order] = s
            y[yOffset + i] = s
            i++
        }
        for (k in 0 until order) mem[k] = y[yOffset + n - k - 1]
    }

    /** `xcorr_kernel`: four running correlations of [x] against [y] at lags 0 to 3. */
    private fun xcorrKernel(x: FloatArray, xOffset: Int, y: FloatArray, yOffset: Int, sum: FloatArray, len: Int) {
        for (j in 0 until len) {
            val t = x[xOffset + j]
            sum[0] += t * y[yOffset + j]
            sum[1] += t * y[yOffset + j + 1]
            sum[2] += t * y[yOffset + j + 2]
            sum[3] += t * y[yOffset + j + 3]
        }
    }

    /**
     * `_celt_autocorr` for lags 0 to [lag] of [n] samples, windowed at both
     * ends by [overlap] samples of [window] when it is given.
     */
    fun autocorr(x: FloatArray, xOffset: Int, ac: FloatArray, window: FloatArray?, overlap: Int, lag: Int, n: Int) {
        val fastN = n - lag
        val xx: FloatArray
        val base: Int
        if (overlap == 0) {
            xx = x
            base = xOffset
        } else {
            xx = FloatArray(n) { x[xOffset + it] }
            for (i in 0 until overlap) {
                xx[i] = x[xOffset + i] * window!![i]
                xx[n - i - 1] = x[xOffset + n - i - 1] * window[i]
            }
            base = 0
        }
        pitchXcorr(xx, base, xx, base, ac, fastN, lag + 1)
        for (k in 0..lag) {
            var d = 0f
            for (i in k + fastN until n) d += xx[base + i] * xx[base + i - k]
            ac[k] += d
        }
    }

    /**
     * `celt_pitch_xcorr`: correlations of [len] samples of [x] against [y] at
     * lags 0 until [maxPitch]. Each lag is summed in sample order, which is
     * what the reference's four-lag kernel does too.
     */
    fun pitchXcorr(x: FloatArray, xOffset: Int, y: FloatArray, yOffset: Int, xcorr: FloatArray, len: Int, maxPitch: Int) {
        for (i in 0 until maxPitch) {
            var sum = 0f
            for (j in 0 until len) sum += x[xOffset + j] * y[yOffset + i + j]
            xcorr[i] = sum
        }
    }

    /**
     * `pitch_downsample`: halves the sample rate of [channels] signals into
     * [out], summing them, and whitens the result with a short predictor.
     */
    fun pitchDownsample(x: Array<FloatArray>, xOffsets: IntArray, out: FloatArray, len: Int, channels: Int) {
        val half = len shr 1
        val x0 = x[0]
        val o0 = xOffsets[0]
        for (i in 1 until half) out[i] = 0.25f * x0[o0 + 2 * i - 1] + 0.25f * x0[o0 + 2 * i + 1] + 0.5f * x0[o0 + 2 * i]
        out[0] = 0.25f * x0[o0 + 1] + 0.5f * x0[o0]
        if (channels == 2) {
            val x1 = x[1]
            val o1 = xOffsets[1]
            for (i in 1 until half) out[i] += 0.25f * x1[o1 + 2 * i - 1] + 0.25f * x1[o1 + 2 * i + 1] + 0.5f * x1[o1 + 2 * i]
            out[0] += 0.25f * x1[o1 + 1] + 0.5f * x1[o1]
        }
        val ac = FloatArray(5)
        autocorr(out, 0, ac, null, 0, 4, half)
        ac[0] *= 1.0001f
        for (i in 1..4) ac[i] -= ac[i] * (0.008f * i) * (0.008f * i)
        val lpc = FloatArray(4)
        lpc(lpc, 0, ac, 4)
        var tmp = 1f
        for (i in 0 until 4) {
            tmp *= 0.9f
            lpc[i] = lpc[i] * tmp
        }
        val c1 = 0.8f
        val lpc2 = floatArrayOf(
            lpc[0] + 0.8f,
            lpc[1] + c1 * lpc[0],
            lpc[2] + c1 * lpc[1],
            lpc[3] + c1 * lpc[2],
            c1 * lpc[3],
        )
        fir5(out, lpc2, half)
    }

    /** `celt_fir5`: a five-tap FIR in place. */
    private fun fir5(x: FloatArray, num: FloatArray, n: Int) {
        var mem0 = 0f
        var mem1 = 0f
        var mem2 = 0f
        var mem3 = 0f
        var mem4 = 0f
        for (i in 0 until n) {
            var sum = x[i]
            sum += num[0] * mem0
            sum += num[1] * mem1
            sum += num[2] * mem2
            sum += num[3] * mem3
            sum += num[4] * mem4
            mem4 = mem3
            mem3 = mem2
            mem2 = mem1
            mem1 = mem0
            mem0 = x[i]
            x[i] = sum
        }
    }

    /**
     * `pitch_search`: the lag, up to [maxPitch], at which [y] best matches
     * [xLp], found coarsely at a quarter of the rate and refined at half.
     */
    fun pitchSearch(xLp: FloatArray, xOffset: Int, y: FloatArray, yOffset: Int, len: Int, maxPitch: Int): Int {
        val lag = len + maxPitch
        val xLp4 = FloatArray(len shr 2) { xLp[xOffset + 2 * it] }
        val yLp4 = FloatArray(lag shr 2) { y[yOffset + 2 * it] }
        val xcorr = FloatArray(maxPitch shr 1)
        val best = IntArray(2)
        pitchXcorr(xLp4, 0, yLp4, 0, xcorr, len shr 2, maxPitch shr 2)
        findBestPitch(xcorr, yLp4, 0, len shr 2, maxPitch shr 2, best)
        for (i in 0 until (maxPitch shr 1)) {
            xcorr[i] = 0f
            if (kotlin.math.abs(i - 2 * best[0]) > 2 && kotlin.math.abs(i - 2 * best[1]) > 2) continue
            val sum = CeltMath.innerProduct(xLp, xOffset, y, yOffset + i, len shr 1)
            xcorr[i] = maxOf(-1f, sum)
        }
        findBestPitch(xcorr, y, yOffset, len shr 1, maxPitch shr 1, best)
        val offset = if (best[0] > 0 && best[0] < (maxPitch shr 1) - 1) {
            val a = xcorr[best[0] - 1]
            val b = xcorr[best[0]]
            val c = xcorr[best[0] + 1]
            when {
                (c - a) > 0.7f * (b - a) -> 1
                (a - c) > 0.7f * (b - c) -> -1
                else -> 0
            }
        } else {
            0
        }
        return 2 * best[0] - offset
    }

    private fun findBestPitch(xcorr: FloatArray, y: FloatArray, yOffset: Int, len: Int, maxPitch: Int, best: IntArray) {
        var syy = 1f
        val bestNum = floatArrayOf(-1f, -1f)
        val bestDen = floatArrayOf(0f, 0f)
        best[0] = 0
        best[1] = 1
        for (j in 0 until len) syy += y[yOffset + j] * y[yOffset + j]
        for (i in 0 until maxPitch) {
            if (xcorr[i] > 0) {
                var xcorr16 = xcorr[i]
                xcorr16 *= 1e-12f
                val num = xcorr16 * xcorr16
                if (num * bestDen[1] > bestNum[1] * syy) {
                    if (num * bestDen[0] > bestNum[0] * syy) {
                        bestNum[1] = bestNum[0]
                        bestDen[1] = bestDen[0]
                        best[1] = best[0]
                        bestNum[0] = num
                        bestDen[0] = syy
                        best[0] = i
                    } else {
                        bestNum[1] = num
                        bestDen[1] = syy
                        best[1] = i
                    }
                }
            }
            syy += y[yOffset + i + len] * y[yOffset + i + len] - y[yOffset + i] * y[yOffset + i]
            syy = maxOf(1f, syy)
        }
    }
}

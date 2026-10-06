package io.github.yuroyami.audiokodec.internal.opus.celt

/**
 * The mixed-radix FFT CELT's inverse MDCT is built on.
 *
 * CELT's transform sizes are 120 times a power of two, so the FFT behind them
 * has to handle factors of 3 and 5 as well as 2 and 4. The Vorbis transform in
 * `internal/dsp` only handles powers of two, which is why this is a separate,
 * direct translation of libopus `kiss_fft.c` rather than a reuse of it. The
 * butterflies run in place on interleaved complex samples.
 */
internal class CeltFft(
    val size: Int,
    /** log2 of how much this FFT's twiddles are subsampled from the 480-point table. */
    private val shift: Int,
    private val factors: IntArray,
    private val bitrev: IntArray,
) {
    private val twiddles: FloatArray = CeltFftTables.TWIDDLES
    private val strides = IntArray(MAX_FACTORS)

    /** Bit-reversed position of complex sample [index]. */
    fun bitrev(index: Int): Int = bitrev[index]

    /** `opus_fft_impl`: transforms the [size] complex samples at [offset] in [data] in place. */
    fun transform(data: FloatArray, offset: Int) {
        val shift = if (shift > 0) shift else 0
        strides[0] = 1
        var stages = 0
        var m: Int
        do {
            val p = factors[2 * stages]
            m = factors[2 * stages + 1]
            strides[stages + 1] = strides[stages] * p
            stages++
        } while (m != 1)
        m = factors[2 * stages - 1]
        for (i in stages - 1 downTo 0) {
            val m2 = if (i != 0) factors[2 * i - 1] else 1
            when (factors[2 * i]) {
                2 -> butterfly2(data, offset, strides[i])
                4 -> butterfly4(data, offset, strides[i] shl shift, m, strides[i], m2)
                3 -> butterfly3(data, offset, strides[i] shl shift, m, strides[i], m2)
                5 -> butterfly5(data, offset, strides[i] shl shift, m, strides[i], m2)
            }
            m = m2
        }
    }

    /** `kf_bfly2`, which CELT only ever reaches with m = 4. */
    private fun butterfly2(f: FloatArray, offset: Int, n: Int) {
        val tw = 0.7071067812f
        var o = offset
        repeat(n) {
            // Complex k sits at o + 2k; its partner four places on.
            var tr = f[o + 8]
            var ti = f[o + 9]
            f[o + 8] = f[o] - tr
            f[o + 9] = f[o + 1] - ti
            f[o] += tr
            f[o + 1] += ti

            tr = (f[o + 10] + f[o + 11]) * tw
            ti = (f[o + 11] - f[o + 10]) * tw
            f[o + 10] = f[o + 2] - tr
            f[o + 11] = f[o + 3] - ti
            f[o + 2] += tr
            f[o + 3] += ti

            tr = f[o + 13]
            ti = -f[o + 12]
            f[o + 12] = f[o + 4] - tr
            f[o + 13] = f[o + 5] - ti
            f[o + 4] += tr
            f[o + 5] += ti

            tr = (f[o + 15] - f[o + 14]) * tw
            ti = -((f[o + 15] + f[o + 14])) * tw
            f[o + 14] = f[o + 6] - tr
            f[o + 15] = f[o + 7] - ti
            f[o + 6] += tr
            f[o + 7] += ti
            o += 16
        }
    }

    /** `kf_bfly4`. */
    private fun butterfly4(f: FloatArray, offset: Int, fstride: Int, m: Int, n: Int, mm: Int) {
        if (m == 1) {
            var o = offset
            repeat(n) {
                val s0r = f[o] - f[o + 4]
                val s0i = f[o + 1] - f[o + 5]
                f[o] += f[o + 4]
                f[o + 1] += f[o + 5]
                var s1r = f[o + 2] + f[o + 6]
                var s1i = f[o + 3] + f[o + 7]
                f[o + 4] = f[o] - s1r
                f[o + 5] = f[o + 1] - s1i
                f[o] += s1r
                f[o + 1] += s1i
                s1r = f[o + 2] - f[o + 6]
                s1i = f[o + 3] - f[o + 7]
                f[o + 2] = s0r + s1i
                f[o + 3] = s0i - s1r
                f[o + 6] = s0r - s1i
                f[o + 7] = s0i + s1r
                o += 8
            }
            return
        }
        val m2 = 2 * m
        val m3 = 3 * m
        for (i in 0 until n) {
            var o = offset + 2 * i * mm
            var tw1 = 0
            var tw2 = 0
            var tw3 = 0
            repeat(m) {
                val a = o + 2 * m
                val b = o + 2 * m2
                val c = o + 2 * m3
                val s0r = f[a] * twiddles[tw1] - f[a + 1] * twiddles[tw1 + 1]
                val s0i = f[a] * twiddles[tw1 + 1] + f[a + 1] * twiddles[tw1]
                val s1r = f[b] * twiddles[tw2] - f[b + 1] * twiddles[tw2 + 1]
                val s1i = f[b] * twiddles[tw2 + 1] + f[b + 1] * twiddles[tw2]
                val s2r = f[c] * twiddles[tw3] - f[c + 1] * twiddles[tw3 + 1]
                val s2i = f[c] * twiddles[tw3 + 1] + f[c + 1] * twiddles[tw3]
                val s5r = f[o] - s1r
                val s5i = f[o + 1] - s1i
                f[o] += s1r
                f[o + 1] += s1i
                val s3r = s0r + s2r
                val s3i = s0i + s2i
                val s4r = s0r - s2r
                val s4i = s0i - s2i
                f[b] = f[o] - s3r
                f[b + 1] = f[o + 1] - s3i
                tw1 += 2 * fstride
                tw2 += 4 * fstride
                tw3 += 6 * fstride
                f[o] += s3r
                f[o + 1] += s3i
                f[a] = s5r + s4i
                f[a + 1] = s5i - s4r
                f[c] = s5r - s4i
                f[c + 1] = s5i + s4r
                o += 2
            }
        }
    }

    /** `kf_bfly3`. */
    private fun butterfly3(f: FloatArray, offset: Int, fstride: Int, m: Int, n: Int, mm: Int) {
        val m2 = 2 * m
        val epi3i = twiddles[2 * fstride * m + 1]
        for (i in 0 until n) {
            var o = offset + 2 * i * mm
            var tw1 = 0
            var tw2 = 0
            repeat(m) {
                val a = o + 2 * m
                val b = o + 2 * m2
                val s1r = f[a] * twiddles[tw1] - f[a + 1] * twiddles[tw1 + 1]
                val s1i = f[a] * twiddles[tw1 + 1] + f[a + 1] * twiddles[tw1]
                val s2r = f[b] * twiddles[tw2] - f[b + 1] * twiddles[tw2 + 1]
                val s2i = f[b] * twiddles[tw2 + 1] + f[b + 1] * twiddles[tw2]
                val s3r = s1r + s2r
                val s3i = s1i + s2i
                var s0r = s1r - s2r
                var s0i = s1i - s2i
                tw1 += 2 * fstride
                tw2 += 4 * fstride
                f[a] = f[o] - s3r * 0.5f
                f[a + 1] = f[o + 1] - s3i * 0.5f
                s0r *= epi3i
                s0i *= epi3i
                f[o] += s3r
                f[o + 1] += s3i
                f[b] = f[a] + s0i
                f[b + 1] = f[a + 1] - s0r
                f[a] += -s0i
                f[a + 1] += s0r
                o += 2
            }
        }
    }

    /** `kf_bfly5`. */
    private fun butterfly5(f: FloatArray, offset: Int, fstride: Int, m: Int, n: Int, mm: Int) {
        val yar = twiddles[2 * fstride * m]
        val yai = twiddles[2 * fstride * m + 1]
        val ybr = twiddles[2 * fstride * 2 * m]
        val ybi = twiddles[2 * fstride * 2 * m + 1]
        for (i in 0 until n) {
            val base = offset + 2 * i * mm
            for (u in 0 until m) {
                val o0 = base + 2 * u
                val o1 = o0 + 2 * m
                val o2 = o0 + 4 * m
                val o3 = o0 + 6 * m
                val o4 = o0 + 8 * m
                val t1 = 2 * u * fstride
                val t2 = 2 * 2 * u * fstride
                val t3 = 2 * 3 * u * fstride
                val t4 = 2 * 4 * u * fstride
                val s0r = f[o0]
                val s0i = f[o0 + 1]
                val s1r = f[o1] * twiddles[t1] - f[o1 + 1] * twiddles[t1 + 1]
                val s1i = f[o1] * twiddles[t1 + 1] + f[o1 + 1] * twiddles[t1]
                val s2r = f[o2] * twiddles[t2] - f[o2 + 1] * twiddles[t2 + 1]
                val s2i = f[o2] * twiddles[t2 + 1] + f[o2 + 1] * twiddles[t2]
                val s3r = f[o3] * twiddles[t3] - f[o3 + 1] * twiddles[t3 + 1]
                val s3i = f[o3] * twiddles[t3 + 1] + f[o3 + 1] * twiddles[t3]
                val s4r = f[o4] * twiddles[t4] - f[o4 + 1] * twiddles[t4 + 1]
                val s4i = f[o4] * twiddles[t4 + 1] + f[o4 + 1] * twiddles[t4]
                val s7r = s1r + s4r
                val s7i = s1i + s4i
                val s10r = s1r - s4r
                val s10i = s1i - s4i
                val s8r = s2r + s3r
                val s8i = s2i + s3i
                val s9r = s2r - s3r
                val s9i = s2i - s3i
                f[o0] = f[o0] + (s7r + s8r)
                f[o0 + 1] = f[o0 + 1] + (s7i + s8i)
                val s5r = s0r + (s7r * yar + s8r * ybr)
                val s5i = s0i + (s7i * yar + s8i * ybr)
                val s6r = s10i * yai + s9i * ybi
                val s6i = -(s10r * yai + s9r * ybi)
                f[o1] = s5r - s6r
                f[o1 + 1] = s5i - s6i
                f[o4] = s5r + s6r
                f[o4 + 1] = s5i + s6i
                val s11r = s0r + (s7r * ybr + s8r * yar)
                val s11i = s0i + (s7i * ybr + s8i * yar)
                val s12r = s9i * yai - s10i * ybi
                val s12i = s10r * ybi - s9r * yai
                f[o2] = s11r + s12r
                f[o2 + 1] = s11i + s12i
                f[o3] = s11r - s12r
                f[o3 + 1] = s11i - s12i
            }
        }
    }

    companion object {
        private const val MAX_FACTORS = 8

        /**
         * The four FFTs of the 48 kHz mode, for MDCT sizes 1920 down to 240.
         * Their factorisations are libopus's `fft_state48000_960_*`.
         */
        val MODE: Array<CeltFft> = arrayOf(
            CeltFft(480, 0, intArrayOf(5, 96, 3, 32, 4, 8, 2, 4, 4, 1), CeltFftTables.BITREV_480),
            CeltFft(240, 1, intArrayOf(5, 48, 3, 16, 4, 4, 4, 1), CeltFftTables.BITREV_240),
            CeltFft(120, 2, intArrayOf(5, 24, 3, 8, 2, 4, 4, 1), CeltFftTables.BITREV_120),
            CeltFft(60, 3, intArrayOf(5, 12, 3, 4, 4, 1), CeltFftTables.BITREV_60),
        )
    }
}

/**
 * CELT's inverse MDCT with its windowed overlap, `clt_mdct_backward` from
 * libopus `mdct.c`.
 */
internal object CeltMdct {

    /** Transform size at the largest block, 20 ms at 48 kHz. */
    private const val SIZE = 1920

    /**
     * Inverse-transforms the spectrum read from [input] at [inOffset] with
     * stride [stride] into [out] at [outOffset], and windows the first
     * [overlap] samples against what is already there.
     *
     * [shift] picks the block size: 0 for 1920, up to 3 for 240.
     */
    fun backward(
        input: FloatArray,
        inOffset: Int,
        out: FloatArray,
        outOffset: Int,
        window: FloatArray,
        overlap: Int,
        shift: Int,
        stride: Int,
    ) {
        val trigTable = CeltMdctTable.TWIDDLES
        var n = SIZE
        var trig = 0
        for (i in 0 until shift) {
            n = n shr 1
            trig += n
        }
        val n2 = n shr 1
        val n4 = n shr 2
        val fft = CeltFft.MODE[shift]

        // Pre-rotation, writing straight into bit-reversed order.
        var xp1 = inOffset
        var xp2 = inOffset + stride * (n2 - 1)
        val yp = outOffset + (overlap shr 1)
        for (i in 0 until n4) {
            val rev = fft.bitrev(i)
            val yr = input[xp2] * trigTable[trig + i] + input[xp1] * trigTable[trig + n4 + i]
            val yi = input[xp1] * trigTable[trig + i] - input[xp2] * trigTable[trig + n4 + i]
            out[yp + 2 * rev + 1] = yr
            out[yp + 2 * rev] = yi
            xp1 += 2 * stride
            xp2 -= 2 * stride
        }

        fft.transform(out, yp)

        // Post-rotation and de-shuffling, working in from both ends at once.
        var yp0 = yp
        var yp1 = yp + n2 - 2
        for (i in 0 until ((n4 + 1) shr 1)) {
            var re = out[yp0 + 1]
            var im = out[yp0]
            var t0 = trigTable[trig + i]
            var t1 = trigTable[trig + n4 + i]
            var yr = re * t0 + im * t1
            var yi = re * t1 - im * t0
            re = out[yp1 + 1]
            im = out[yp1]
            out[yp0] = yr
            out[yp1 + 1] = yi
            t0 = trigTable[trig + n4 - i - 1]
            t1 = trigTable[trig + n2 - i - 1]
            yr = re * t0 + im * t1
            yi = re * t1 - im * t0
            out[yp1] = yr
            out[yp0 + 1] = yi
            yp0 += 2
            yp1 -= 2
        }

        // Mirror the overlap around its centre with the window applied, which
        // is the time-domain aliasing cancellation against the previous block.
        var x1p = outOffset + overlap - 1
        var y1p = outOffset
        var wp1 = 0
        var wp2 = overlap - 1
        for (i in 0 until overlap / 2) {
            val x1 = out[x1p]
            val x2 = out[y1p]
            out[y1p++] = window[wp2] * x2 - window[wp1] * x1
            out[x1p--] = window[wp1] * x2 + window[wp2] * x1
            wp1++
            wp2--
        }
    }
}

package io.github.yuroyami.audiokodec.internal.codec

/**
 * Decodes ITU-T G.722 sub-band ADPCM, one octet into two 16 kHz samples.
 *
 * Written from sections 5 and 6 of the Recommendation (11/88). The ADPCM
 * decoders of both sub-bands are kept in the Recommendation's 16-bit
 * arithmetic, whose additions saturate and whose products are shifted right
 * by 15. Section 5.2 leaves the receive QMF's word lengths and rounding
 * open, and its test sequences do not cover it; this one accumulates
 * exactly, truncates, and keeps one bit more than the 15 of XOUT, which is
 * what ffmpeg does, so that its 16-bit output and ffmpeg's are the same.
 *
 * [mode] is the Recommendation's mode: 1 for 64 kbit/s of audio, 2 and 3 for
 * 56 and 48 kbit/s, which leave the last one or two bits of each octet to an
 * auxiliary data channel. Each octet carries the higher sub-band's two bits
 * above the lower sub-band's six.
 */
internal class G722Decoder(private val mode: Int) {
    init {
        require(mode in 1..3) { "G.722 has modes 1 to 3, not $mode" }
    }

    private val low = Band(DETL_RESET)
    private val high = Band(DETH_RESET)
    private var nbl = 0
    private var nbh = 0

    /** The receive QMF's delay lines, newest first. */
    private val xd = IntArray(12)
    private val xs = IntArray(12)

    /** RS: every delayed variable to its reset value. */
    fun reset() {
        low.reset(DETL_RESET)
        high.reset(DETH_RESET)
        nbl = 0
        nbh = 0
        xd.fill(0)
        xs.fill(0)
    }

    /** The lower sub-band's reconstructed signal RL from the last [decodeSubBands]. */
    var lowOutput: Int = 0
        private set

    /** The higher sub-band's reconstructed signal RH from the last [decodeSubBands]. */
    var highOutput: Int = 0
        private set

    /** Decodes [octet] into two samples at [outOffset], 16-bit and in time order. */
    fun decode(octet: Int, out: ShortArray, outOffset: Int) {
        decodeSubBands(octet and 63, (octet shr 6) and 3)
        val rl = lowOutput
        val rh = highOutput

        // The receive QMF: RECA, RECB, ACCUMC, ACCUMD and SELECT.
        for (i in 11 downTo 1) {
            xd[i] = xd[i - 1]
            xs[i] = xs[i - 1]
        }
        xd[0] = rl - rh
        xs[0] = rl + rh
        var wdEven = 0
        var wdOdd = 0
        for (i in 0 until 12) {
            wdEven += xd[i] * H[2 * i]
            wdOdd += xs[i] * H[2 * i + 1]
        }
        out[outOffset] = (wdEven shr 11).coerceIn(-32768, 32767).toShort()
        out[outOffset + 1] = (wdOdd shr 11).coerceIn(-32768, 32767).toShort()
    }

    /**
     * Runs the two sub-band ADPCM decoders on [ilr] and [ih], leaving RL and
     * RH in [lowOutput] and [highOutput], without the receive QMF; this is
     * the configuration the Recommendation's test sequences check.
     */
    fun decodeSubBands(ilr: Int, ih: Int) {
        // Lower sub-band: BLOCKS 2L to 6L.
        val sl = low.predict()
        val ril4 = ilr shr 2
        val dlt = mult(low.det, signed(QQ4[IL4[ril4]] shl 3, SIL4[ril4]))
        val dl = when (mode) {
            1 -> mult(low.det, signed(QQ6[IL6[ilr]] shl 3, SIL6[ilr]))
            2 -> mult(low.det, signed(QQ5[IL5[ilr shr 1]] shl 3, SIL5[ilr shr 1]))
            else -> dlt
        }
        val rl = limit(add(sl, dl))
        low.adapt(dlt)
        nbl = (add(mult(nbl, 32512), WL[IL4[ril4]])).coerceIn(0, 18432)
        low.det = scale(nbl, 8)

        // Higher sub-band: BLOCKS 2H to 5H.
        val sh = high.predict()
        val dh = mult(high.det, signed(QQ2[IH2[ih]] shl 3, SIH[ih]))
        val rh = limit(add(sh, dh))
        high.adapt(dh)
        nbh = (add(mult(nbh, 32512), WH[IH2[ih]])).coerceIn(0, 22528)
        high.det = scale(nbh, 10)
        lowOutput = rl
        highOutput = rh
    }

    /** One sub-band's adaptive predictor and quantizer scale factor, BLOCK 4L or 4H with DELAYL or DELAYH. */
    private class Band(detReset: Int) {
        var det = detReset
        val a = IntArray(2)
        val b = IntArray(6)
        val d = IntArray(6)
        val p = IntArray(2)
        val r = IntArray(2)
        private var sz = 0
        private var s = 0

        fun reset(detReset: Int) {
            det = detReset
            a.fill(0)
            b.fill(0)
            d.fill(0)
            p.fill(0)
            r.fill(0)
        }

        /** FILTEZ, FILTEP and PREDIC: the predictor output value. */
        fun predict(): Int {
            var zero = mult(b[5], add(d[5], d[5]))
            for (i in 4 downTo 0) zero = add(zero, mult(b[i], add(d[i], d[i])))
            sz = zero
            val pole = add(mult(a[0], add(r[0], r[0])), mult(a[1], add(r[1], r[1])))
            s = add(pole, zero)
            return s
        }

        /** PARREC, RECONS, UPPOL2, UPPOL1 and UPZERO, then the delays. */
        fun adapt(dq: Int) {
            val pt = add(dq, sz)
            val rt = add(s, dq)
            val sg0 = pt shr 15
            val sg1 = p[0] shr 15
            val sg2 = p[1] shr 15

            // UPPOL2.
            var wd1 = add(a[0], a[0])
            wd1 = add(wd1, wd1)
            val wd2 = (if (sg0 == sg1) sub(0, wd1) else wd1) shr 7
            val wd3 = if (sg0 == sg2) 128 else -128
            val ap2 = add(add(wd2, wd3), mult(a[1], 32512)).coerceIn(-12288, 12288)

            // UPPOL1.
            val ap1Unlimited = add(if (sg0 == sg1) 192 else -192, mult(a[0], 32640))
            val bound = sub(15360, ap2)
            val ap1 = if (ap1Unlimited > bound) bound else if (ap1Unlimited < -bound) -bound else ap1Unlimited

            // UPZERO.
            val gain = if (dq == 0) 0 else 128
            val sgd = dq shr 15
            for (i in 0 until 6) {
                val signed = if (sgd == (d[i] shr 15)) gain else -gain
                b[i] = add(signed, mult(b[i], 32640))
            }

            // DELAYA.
            for (i in 5 downTo 1) d[i] = d[i - 1]
            d[0] = dq
            p[1] = p[0]
            p[0] = pt
            r[1] = r[0]
            r[0] = rt
            a[0] = ap1
            a[1] = ap2
        }
    }

    private companion object {
        const val DETL_RESET = 32
        const val DETH_RESET = 8

        /** `+` of section 6.2: 16-bit addition that saturates. */
        fun add(x: Int, y: Int): Int = (x + y).coerceIn(-32768, 32767)

        /** `-` of section 6.2. */
        fun sub(x: Int, y: Int): Int = (x - y).coerceIn(-32768, 32767)

        /** `*` of section 6.2: the product shifted right by 15. */
        fun mult(x: Int, y: Int): Int = (x * y) shr 15

        /** LIMIT, and the QMF's output limits. */
        fun limit(x: Int): Int = x.coerceIn(-16384, 16383)

        fun signed(magnitude: Int, sign: Int): Int = if (sign == 0) magnitude else -magnitude

        /** SCALEL and SCALEH, by the 32-entry table: [base] is 8 for the lower sub-band and 10 for the higher. */
        fun scale(nb: Int, base: Int): Int {
            val fraction = ILB[(nb shr 6) and 31]
            val shift = base - (nb shr 11)
            return (if (shift >= 0) fraction shr shift else fraction shl -shift) shl 2
        }

        // Table 11, the QMF coefficients H0 to H23, scaled by 2 to the 13th.
        val H = intArrayOf(3, -11, -11, 53, 12, -156, 32, 362, -210, -805, 951, 3876, 3876, 951, -805, -210, 362, 32, -156, 12, 53, -11, -11, 3)

        // Table 14, extracted from the Recommendation's text by reference/g72x/gen_tables.py.
        val QQ6 = intArrayOf(0, 17, 54, 91, 130, 170, 211, 254, 300, 347, 396, 447, 501, 558, 618, 682, 750, 822, 899, 982, 1072, 1170, 1279, 1399, 1535, 1689, 1873, 2088, 2376, 2738, 3101)
        val QQ5 = intArrayOf(0, 35, 110, 190, 276, 370, 473, 587, 714, 858, 1023, 1219, 1458, 1765, 2195, 2919)
        val QQ4 = intArrayOf(0, 150, 323, 530, 786, 1121, 1612, 2557)
        val WL = intArrayOf(-60, -30, 58, 172, 334, 538, 1198, 3042)
        val QQ2 = intArrayOf(0, 202, 926)
        val WH = intArrayOf(0, -214, 798)

        // Table 15's 32-entry log-to-linear table, 2048 times 2 to the power i/32.
        val ILB = intArrayOf(2048, 2093, 2139, 2186, 2233, 2282, 2332, 2383, 2435, 2489, 2543, 2599, 2656, 2714, 2774, 2834, 2896, 2960, 3025, 3091, 3158, 3228, 3298, 3371, 3444, 3520, 3597, 3676, 3756, 3838, 3922, 4008)

        // Tables 17, 18, 19 and 21: code words to quantizer intervals, with sign 1 for SIL or SIH of -1.
        val IL4 = IntArray(16) { if (it == 0 || it == 15) 0 else if (it < 8) 8 - it else 15 - it }
        val SIL4 = IntArray(16) { if (it in 1..7) 1 else 0 }
        val IL6 = IntArray(64) {
            when {
                it < 4 -> 1
                it < 32 -> 34 - it
                it >= 62 -> 64 - it
                else -> 62 - it
            }
        }
        val SIL6 = IntArray(64) { if (it < 32 || it >= 62) 1 else 0 }
        val IL5 = IntArray(32) {
            when {
                it < 2 -> 1
                it < 16 -> 17 - it
                it == 31 -> 1
                else -> 31 - it
            }
        }
        val SIL5 = IntArray(32) { if (it < 16 || it == 31) 1 else 0 }
        val IH2 = intArrayOf(2, 1, 2, 1)
        val SIH = intArrayOf(1, 1, 0, 0)
    }
}

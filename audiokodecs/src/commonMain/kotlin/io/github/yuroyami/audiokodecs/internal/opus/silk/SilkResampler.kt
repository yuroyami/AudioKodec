package io.github.yuroyami.audiokodecs.internal.opus.silk

/**
 * SILK's resampler, from the internal rate (8, 12 or 16 kHz) to the rate the
 * caller asked for (8, 12, 16, 24 or 48 kHz).
 *
 * Its exact output is part of what a conforming decoder produces, so this is a
 * bit-exact port rather than a resampler of similar quality. It translates
 * `silk/resampler.c` and the private filters it can select for decoding:
 *
 * - equal rates copy;
 * - exactly twice the rate uses a cascade of allpass filters (`up2_HQ`);
 * - other increases use that 2x upsampler followed by a 12-phase FIR
 *   interpolator (`IIR_FIR`);
 * - decreases use a second-order AR filter followed by a polyphase FIR
 *   (`down_FIR`).
 *
 * Every rate combination delays the signal by its own amount, and each pairing
 * adds a little input delay of its own (`inputDelay`) so that the total comes
 * out the same whatever the rates, which matters when the internal rate
 * changes mid-stream.
 */
internal class SilkResampler {

    /** Allpass or AR filter state; `sIIR`. */
    private val iirState = IntArray(MAX_IIR_ORDER)

    /**
     * FIR history; `sFIR`. The reference keeps it as a union of 16-bit and
     * 32-bit views, and each filter only ever uses one of them, so one array
     * serves both.
     */
    private val firState = IntArray(MAX_FIR_ORDER)

    /** The last few input samples, held back by [inputDelay]. */
    private val delayBuffer = IntArray(48)

    private var function = COPY
    private var batchSize = 0
    private var invRatioQ16 = 0
    private var firOrder = 0
    private var firFracs = 0
    private var fsInKHz = 0
    private var fsOutKHz = 0
    private var inputDelay = 0
    private var coefficients: IntArray = SilkTables.RESAMPLER_1_2_COEFS

    // Working buffers, sized for the largest batch: 10 ms at 16 kHz in, before
    // doubling.
    private val upsampled = IntArray(2 * MAX_BATCH_IN + RESAMPLER_ORDER_FIR_12)
    private val filtered = IntArray(MAX_BATCH_IN + MAX_FIR_ORDER)

    /** A copy of this resampler's whole state in [other]; the reference's `memcpy` of the struct. */
    fun copyFrom(other: SilkResampler) {
        other.iirState.copyInto(iirState)
        other.firState.copyInto(firState)
        other.delayBuffer.copyInto(delayBuffer)
        function = other.function
        batchSize = other.batchSize
        invRatioQ16 = other.invRatioQ16
        firOrder = other.firOrder
        firFracs = other.firFracs
        fsInKHz = other.fsInKHz
        fsOutKHz = other.fsOutKHz
        inputDelay = other.inputDelay
        coefficients = other.coefficients
    }

    /** Clears the state as zeroing the decoder does, which leaves a resampler that copies nothing. */
    fun clear() {
        iirState.fill(0)
        firState.fill(0)
        delayBuffer.fill(0)
        function = COPY
        batchSize = 0
        invRatioQ16 = 0
        firOrder = 0
        firFracs = 0
        fsInKHz = 0
        fsOutKHz = 0
        inputDelay = 0
        coefficients = SilkTables.RESAMPLER_1_2_COEFS
    }

    /**
     * Translates `silk_resampler_init` for decoding. Returns false, leaving the
     * state cleared, when the pair of rates is not one the decoder supports.
     */
    fun init(fsHzIn: Int, fsHzOut: Int): Boolean {
        clear()
        val inId = rateId(fsHzIn)
        val outId = rateId(fsHzOut)
        if ((fsHzIn != 8000 && fsHzIn != 12000 && fsHzIn != 16000) ||
            (fsHzOut != 8000 && fsHzOut != 12000 && fsHzOut != 16000 && fsHzOut != 24000 && fsHzOut != 48000)
        ) {
            return false
        }
        inputDelay = DELAY_MATRIX_DEC[inId * 5 + outId]
        fsInKHz = fsHzIn / 1000
        fsOutKHz = fsHzOut / 1000
        batchSize = fsInKHz * MAX_BATCH_SIZE_MS

        var up2x = 0
        if (fsHzOut > fsHzIn) {
            if (fsHzOut == fsHzIn * 2) {
                function = UP2_HQ
            } else {
                function = IIR_FIR
                up2x = 1
            }
        } else if (fsHzOut < fsHzIn) {
            function = DOWN_FIR
            when {
                fsHzOut * 4 == fsHzIn * 3 -> {
                    firFracs = 3
                    firOrder = DOWN_ORDER_FIR0
                    coefficients = SilkTables.RESAMPLER_3_4_COEFS
                }
                fsHzOut * 3 == fsHzIn * 2 -> {
                    firFracs = 2
                    firOrder = DOWN_ORDER_FIR0
                    coefficients = SilkTables.RESAMPLER_2_3_COEFS
                }
                fsHzOut * 2 == fsHzIn -> {
                    firFracs = 1
                    firOrder = DOWN_ORDER_FIR1
                    coefficients = SilkTables.RESAMPLER_1_2_COEFS
                }
                // Only 1:3, 1:4 and 1:6 remain, and no decoder pairing needs them.
                else -> {
                    clear()
                    return false
                }
            }
        } else {
            function = COPY
        }

        // The step through the input per output sample, rounded up so that
        // the interpolators never ask for a sample past the end.
        invRatioQ16 = ((fsHzIn shl (14 + up2x)) / fsHzOut) shl 2
        while (smulww(invRatioQ16, fsHzOut) < (fsHzIn shl up2x)) {
            invRatioQ16++
        }
        return true
    }

    /**
     * Translates `silk_resampler`: resamples [inLength] samples of [input] from
     * [inOffset] into [out] from [outOffset]. At least one millisecond of input
     * is needed, and that first millisecond is the one the delay applies to.
     */
    fun resample(out: IntArray, outOffset: Int, input: IntArray, inOffset: Int, inLength: Int) {
        val fresh = fsInKHz - inputDelay
        input.copyInto(delayBuffer, inputDelay, inOffset, inOffset + fresh)

        when (function) {
            UP2_HQ -> {
                up2Hq(iirState, out, outOffset, delayBuffer, 0, fsInKHz)
                up2Hq(iirState, out, outOffset + fsOutKHz, input, inOffset + fresh, inLength - fsInKHz)
            }
            IIR_FIR -> {
                iirFir(out, outOffset, delayBuffer, 0, fsInKHz)
                iirFir(out, outOffset + fsOutKHz, input, inOffset + fresh, inLength - fsInKHz)
            }
            DOWN_FIR -> {
                downFir(out, outOffset, delayBuffer, 0, fsInKHz)
                downFir(out, outOffset + fsOutKHz, input, inOffset + fresh, inLength - fsInKHz)
            }
            else -> {
                delayBuffer.copyInto(out, outOffset, 0, fsInKHz)
                input.copyInto(out, outOffset + fsOutKHz, inOffset + fresh, inOffset + inLength - inputDelay)
            }
        }

        input.copyInto(delayBuffer, 0, inOffset + inLength - inputDelay, inOffset + inLength)
    }

    /** Translates `silk_resampler_private_IIR_FIR`: upsample 2x, then interpolate. */
    private fun iirFir(out: IntArray, outStart: Int, input: IntArray, inStart: Int, inLength: Int) {
        val buf = upsampled
        firState.copyInto(buf, 0, 0, RESAMPLER_ORDER_FIR_12)
        val increment = invRatioQ16
        var outAt = outStart
        var inAt = inStart
        var remaining = inLength
        var batch: Int
        while (true) {
            batch = minOf(remaining, batchSize)
            up2Hq(iirState, buf, RESAMPLER_ORDER_FIR_12, input, inAt, batch)

            // Twice as many upsampled samples as input ones.
            val maxIndexQ16 = batch shl (16 + 1)
            var indexQ16 = 0
            while (indexQ16 < maxIndexQ16) {
                val phase = smulwb(indexQ16 and 0xFFFF, 12)
                val at = indexQ16 shr 16
                val fir = SilkTables.RESAMPLER_FRAC_FIR_12
                val p = phase * 4
                val m = (11 - phase) * 4
                var resQ15 = smulbb(buf[at], fir[p])
                resQ15 = smlabb(resQ15, buf[at + 1], fir[p + 1])
                resQ15 = smlabb(resQ15, buf[at + 2], fir[p + 2])
                resQ15 = smlabb(resQ15, buf[at + 3], fir[p + 3])
                resQ15 = smlabb(resQ15, buf[at + 4], fir[m + 3])
                resQ15 = smlabb(resQ15, buf[at + 5], fir[m + 2])
                resQ15 = smlabb(resQ15, buf[at + 6], fir[m + 1])
                resQ15 = smlabb(resQ15, buf[at + 7], fir[m])
                out[outAt++] = sat16(rshiftRound(resQ15, 15))
                indexQ16 += increment
            }
            inAt += batch
            remaining -= batch
            if (remaining > 0) {
                buf.copyInto(buf, 0, batch shl 1, (batch shl 1) + RESAMPLER_ORDER_FIR_12)
            } else {
                break
            }
        }
        buf.copyInto(firState, 0, batch shl 1, (batch shl 1) + RESAMPLER_ORDER_FIR_12)
    }

    /** Translates `silk_resampler_private_down_FIR`: AR2 lowpass, then polyphase FIR. */
    private fun downFir(out: IntArray, outStart: Int, input: IntArray, inStart: Int, inLength: Int) {
        val buf = filtered
        firState.copyInto(buf, 0, 0, firOrder)
        val coefs = coefficients
        val increment = invRatioQ16
        var outAt = outStart
        var inAt = inStart
        var remaining = inLength
        var batch: Int
        while (true) {
            batch = minOf(remaining, batchSize)
            ar2(iirState, buf, firOrder, input, inAt, coefs, batch)
            val maxIndexQ16 = batch shl 16
            outAt = downFirInterpolate(out, outAt, buf, coefs, maxIndexQ16, increment)
            inAt += batch
            remaining -= batch
            // The reference continues only while more than one sample is left,
            // and a single leftover sample is then never filtered. Batches are
            // 10 ms and frames whole milliseconds, so it never arises.
            if (remaining > 1) {
                buf.copyInto(buf, 0, batch, batch + firOrder)
            } else {
                break
            }
        }
        buf.copyInto(firState, 0, batch, batch + firOrder)
    }

    private fun downFirInterpolate(
        out: IntArray,
        outStart: Int,
        buf: IntArray,
        coefs: IntArray,
        maxIndexQ16: Int,
        increment: Int,
    ): Int {
        var outAt = outStart
        var indexQ16 = 0
        // The FIR coefficients follow the two AR2 ones.
        val c = 2
        when (firOrder) {
            DOWN_ORDER_FIR0 -> while (indexQ16 < maxIndexQ16) {
                val at = indexQ16 shr 16
                val phase = smulwb(indexQ16 and 0xFFFF, firFracs)
                // Each phase stores half its taps; the other half is the
                // mirror image of the opposite phase.
                var p = c + DOWN_ORDER_FIR0 / 2 * phase
                var res = smulwb(buf[at], coefs[p])
                for (k in 1 until 9) res = smlawb(res, buf[at + k], coefs[p + k])
                p = c + DOWN_ORDER_FIR0 / 2 * (firFracs - 1 - phase)
                for (k in 0 until 9) res = smlawb(res, buf[at + 17 - k], coefs[p + k])
                out[outAt++] = sat16(rshiftRound(res, 6))
                indexQ16 += increment
            }
            DOWN_ORDER_FIR1 -> while (indexQ16 < maxIndexQ16) {
                val at = indexQ16 shr 16
                // A single symmetric phase: pair the taps from both ends.
                var res = smulwb(buf[at] + buf[at + 23], coefs[c])
                for (k in 1 until 12) res = smlawb(res, buf[at + k] + buf[at + 23 - k], coefs[c + k])
                out[outAt++] = sat16(rshiftRound(res, 6))
                indexQ16 += increment
            }
            else -> while (indexQ16 < maxIndexQ16) {
                val at = indexQ16 shr 16
                var res = smulwb(buf[at] + buf[at + 35], coefs[c])
                for (k in 1 until 18) res = smlawb(res, buf[at + k] + buf[at + 35 - k], coefs[c + k])
                out[outAt++] = sat16(rshiftRound(res, 6))
                indexQ16 += increment
            }
        }
        return outAt
    }

    private companion object {
        const val COPY = 0
        const val UP2_HQ = 1
        const val IIR_FIR = 2
        const val DOWN_FIR = 3

        const val MAX_IIR_ORDER = 6
        const val MAX_FIR_ORDER = 36
        const val MAX_BATCH_SIZE_MS = 10
        const val MAX_BATCH_IN = MAX_BATCH_SIZE_MS * 16

        const val DOWN_ORDER_FIR0 = 18
        const val DOWN_ORDER_FIR1 = 24
        const val RESAMPLER_ORDER_FIR_12 = 8

        /** Input delay per pairing, internal rate by output rate; `delay_matrix_dec`. */
        val DELAY_MATRIX_DEC = intArrayOf(
            4, 0, 2, 0, 0,
            0, 9, 4, 7, 4,
            0, 3, 12, 7, 7,
        )

        /** Allpass coefficients of the even and odd output phases; `silk_resampler_up2_hq_0` and `_1`. */
        val UP2_HQ_0 = intArrayOf(1746, 14986, 39083 - 65536)
        val UP2_HQ_1 = intArrayOf(6854, 25769, 55542 - 65536)

        /** The reference's `rateID`: 8, 12, 16, 24 and 48 kHz to 0 through 4. */
        fun rateId(rate: Int): Int {
            val a = (rate shr 12) - (if (rate > 16000) 1 else 0)
            return (a shr (if (rate > 24000) 1 else 0)) - 1
        }

        /**
         * Translates `silk_resampler_private_up2_HQ`: two outputs per input,
         * each phase from three first-order allpass sections in Q10.
         */
        fun up2Hq(state: IntArray, out: IntArray, outStart: Int, input: IntArray, inStart: Int, length: Int) {
            for (k in 0 until length) {
                val in32 = input[inStart + k] shl 10

                var y = in32 - state[0]
                var x = smulwb(y, UP2_HQ_0[0])
                var out1 = state[0] + x
                state[0] = in32 + x

                y = out1 - state[1]
                x = smulwb(y, UP2_HQ_0[1])
                var out2 = state[1] + x
                state[1] = out1 + x

                y = out2 - state[2]
                x = smlawb(y, y, UP2_HQ_0[2])
                out1 = state[2] + x
                state[2] = out2 + x

                out[outStart + 2 * k] = sat16(rshiftRound(out1, 10))

                y = in32 - state[3]
                x = smulwb(y, UP2_HQ_1[0])
                out1 = state[3] + x
                state[3] = in32 + x

                y = out1 - state[4]
                x = smulwb(y, UP2_HQ_1[1])
                out2 = state[4] + x
                state[4] = out1 + x

                y = out2 - state[5]
                x = smlawb(y, y, UP2_HQ_1[2])
                out1 = state[5] + x
                state[5] = out2 + x

                out[outStart + 2 * k + 1] = sat16(rshiftRound(out1, 10))
            }
        }

        /**
         * Translates `silk_resampler_private_AR2`: the two-pole lowpass ahead of
         * downsampling, output in Q8 to [out] from [outStart].
         */
        fun ar2(
            state: IntArray,
            out: IntArray,
            outStart: Int,
            input: IntArray,
            inStart: Int,
            coefsQ14: IntArray,
            length: Int,
        ) {
            for (k in 0 until length) {
                var out32 = state[0] + (input[inStart + k] shl 8)
                out[outStart + k] = out32
                out32 = out32 shl 2
                state[0] = smlawb(state[1], out32, coefsQ14[0])
                state[1] = smulwb(out32, coefsQ14[1])
            }
        }
    }
}

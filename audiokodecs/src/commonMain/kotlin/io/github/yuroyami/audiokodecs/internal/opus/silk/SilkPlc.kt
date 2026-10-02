package io.github.yuroyami.audiokodecs.internal.opus.silk

/*
 * What SILK does when a frame is missing, and the comfort noise that fills
 * silence.
 *
 * Concealment carries on from the last good frame: the same LPC filter,
 * softened a little more with each lost frame; for voiced speech the same
 * pitch, drifting slowly longer and fading; and noise drawn from the last
 * frame's own excitation, fading too. When good frames return, the first one is
 * faded in if it is louder than the concealment was, so the join does not
 * click.
 *
 * Comfort noise is estimated from frames the encoder marked as inactive and is
 * added only to concealed frames, so a long gap fades to a background that
 * sounds like the room rather than to digital silence.
 *
 * Translates silk/PLC.c and silk/CNG.c, without the deep (neural) concealment.
 */

private const val NB_ATT = 2

/** 0.99 and 0.95: how fast the pitch pulse fades per lost frame. */
private val HARM_ATT_Q15 = intArrayOf(32440, 31130)

/** 0.95 and 0.8: how fast the noise fades after voiced speech. */
private val PLC_RAND_ATTENUATE_V_Q15 = intArrayOf(31130, 26214)

/** 0.99 and 0.9: how fast the noise fades after unvoiced speech. */
private val PLC_RAND_ATTENUATE_UV_Q15 = intArrayOf(32440, 29491)

private const val V_PITCH_GAIN_START_MIN_Q14 = 11469
private const val V_PITCH_GAIN_START_MAX_Q14 = 15565
private const val MAX_PITCH_LAG_MS = 18
private const val RAND_BUF_SIZE = 128
private const val RAND_BUF_MASK = RAND_BUF_SIZE - 1
private const val LOG2_INV_LPC_GAIN_HIGH_THRES = 3
private const val LOG2_INV_LPC_GAIN_LOW_THRES = 8
private const val PITCH_DRIFT_FAC_Q16 = 655

/** `SILK_FIX_CONST(BWE_COEF, 16)`, with `BWE_COEF` 0.99. */
private const val BWE_COEF_Q16 = 64881

private const val CNG_BUF_MASK_MAX = 255
private const val CNG_GAIN_SMTH_Q16 = 4634
private const val CNG_GAIN_SMTH_THRESHOLD_Q16 = 46396
private const val CNG_NLSF_SMTH_Q16 = 16348

/** `silk_PLC_struct`. */
internal class SilkPlcState {
    var pitchLQ8 = 0
    val ltpCoefQ14 = IntArray(LTP_ORDER)
    val prevLpcQ12 = IntArray(MAX_LPC_ORDER)
    var lastFrameLost = 0
    var randSeed = 0
    var randScaleQ14 = 0
    var concEnergy = 0
    var concEnergyShift = 0
    var prevLtpScaleQ14 = 0
    val prevGainQ16 = IntArray(2)
    var fsKHz = 0
    var nbSubfr = 0
    var subfrLength = 0

    // Scratch space for concealment.
    val sLtp = IntArray(MAX_FRAME_LENGTH)
    val sLtpQ14 = IntArray(2 * MAX_FRAME_LENGTH)
    val aQ12 = IntArray(MAX_LPC_ORDER)
    val excBuf = IntArray(2 * MAX_SUB_FRAME_LENGTH)
    val energy = IntArray(2)

    fun clear() {
        pitchLQ8 = 0
        ltpCoefQ14.fill(0)
        prevLpcQ12.fill(0)
        lastFrameLost = 0
        randSeed = 0
        randScaleQ14 = 0
        concEnergy = 0
        concEnergyShift = 0
        prevLtpScaleQ14 = 0
        prevGainQ16.fill(0)
        fsKHz = 0
        nbSubfr = 0
        subfrLength = 0
    }
}

/** `silk_CNG_struct`. */
internal class SilkCngState {
    val excBufQ14 = IntArray(MAX_FRAME_LENGTH)
    val smthNlsfQ15 = IntArray(MAX_LPC_ORDER)
    val synthState = IntArray(MAX_LPC_ORDER)
    var smthGainQ16 = 0
    var randSeed = 0
    var fsKHz = 0

    // Scratch space for synthesis.
    val aQ12 = IntArray(MAX_LPC_ORDER)
    val sigQ14 = IntArray(MAX_FRAME_LENGTH + MAX_LPC_ORDER)

    fun clear() {
        excBufQ14.fill(0)
        smthNlsfQ15.fill(0)
        synthState.fill(0)
        smthGainQ16 = 0
        randSeed = 0
        fsKHz = 0
    }
}

/** Translates `silk_PLC_Reset`. */
internal fun plcReset(channel: SilkChannelDecoder) {
    val plc = channel.plc
    plc.pitchLQ8 = channel.frameLength shl (8 - 1)
    plc.prevGainQ16[0] = 1 shl 16
    plc.prevGainQ16[1] = 1 shl 16
    plc.subfrLength = 20
    plc.nbSubfr = 2
}

/**
 * Translates `silk_PLC`: after a good frame, remembers what concealment will
 * need; for a lost one, writes a concealed frame to [frame] from [offset].
 */
internal fun plcUpdateOrConceal(
    channel: SilkChannelDecoder,
    params: SilkFrameParameters,
    frame: IntArray,
    offset: Int,
    lost: Boolean,
) {
    if (channel.fsKHz != channel.plc.fsKHz) {
        plcReset(channel)
        channel.plc.fsKHz = channel.fsKHz
    }
    if (lost) {
        plcConceal(channel, params, frame, offset)
        channel.lossCnt++
    } else {
        plcUpdate(channel, params)
    }
}

/** Translates `silk_PLC_update`. */
private fun plcUpdate(channel: SilkChannelDecoder, params: SilkFrameParameters) {
    val plc = channel.plc
    val nbSubfr = channel.nbSubfr
    channel.prevSignalType = channel.indices.signalType
    var ltpGainQ14 = 0
    if (channel.indices.signalType == TYPE_VOICED) {
        // Take the pitch from the strongest of the subframes within one pitch
        // period of the end, since that is the pulse concealment will repeat.
        var j = 0
        while (j * channel.subfrLength < params.pitchL[nbSubfr - 1]) {
            if (j == nbSubfr) break
            val subframe = nbSubfr - 1 - j
            var tempLtpGainQ14 = 0
            for (i in 0 until LTP_ORDER) {
                tempLtpGainQ14 += params.ltpCoefQ14[subframe * LTP_ORDER + i]
            }
            if (tempLtpGainQ14 > ltpGainQ14) {
                ltpGainQ14 = tempLtpGainQ14
                params.ltpCoefQ14.copyInto(plc.ltpCoefQ14, 0, subframe * LTP_ORDER, (subframe + 1) * LTP_ORDER)
                plc.pitchLQ8 = params.pitchL[subframe] shl 8
            }
            j++
        }

        // Concealment uses a single centre tap with the combined gain.
        plc.ltpCoefQ14.fill(0)
        plc.ltpCoefQ14[LTP_ORDER / 2] = ltpGainQ14.toShort().toInt()

        // Keep that gain within a range that neither dies at once nor rings.
        if (ltpGainQ14 < V_PITCH_GAIN_START_MIN_Q14) {
            val scaleQ10 = (V_PITCH_GAIN_START_MIN_Q14 shl 10) / maxOf(ltpGainQ14, 1)
            for (i in 0 until LTP_ORDER) {
                plc.ltpCoefQ14[i] = (smulbb(plc.ltpCoefQ14[i], scaleQ10) shr 10).toShort().toInt()
            }
        } else if (ltpGainQ14 > V_PITCH_GAIN_START_MAX_Q14) {
            val scaleQ14 = (V_PITCH_GAIN_START_MAX_Q14 shl 14) / maxOf(ltpGainQ14, 1)
            for (i in 0 until LTP_ORDER) {
                plc.ltpCoefQ14[i] = (smulbb(plc.ltpCoefQ14[i], scaleQ14) shr 14).toShort().toInt()
            }
        }
    } else {
        plc.pitchLQ8 = smulbb(channel.fsKHz, 18) shl 8
        plc.ltpCoefQ14.fill(0)
    }

    params.predCoefQ12[1].copyInto(plc.prevLpcQ12, 0, 0, channel.lpcOrder)
    plc.prevLtpScaleQ14 = params.ltpScaleQ14.toShort().toInt()
    plc.prevGainQ16[0] = params.gainsQ16[nbSubfr - 2]
    plc.prevGainQ16[1] = params.gainsQ16[nbSubfr - 1]
    plc.subfrLength = channel.subfrLength
    plc.nbSubfr = nbSubfr
}

/**
 * Translates `silk_PLC_energy`: the energies of the last two subframes'
 * excitation, scaled by their gains, into `energy` as (energy, shift) pairs.
 */
private fun plcEnergy(channel: SilkChannelDecoder, prevGainQ10: IntArray, energies: IntArray) {
    val plc = channel.plc
    val subfrLength = channel.subfrLength
    val excBuf = plc.excBuf
    for (k in 0 until 2) {
        for (i in 0 until subfrLength) {
            excBuf[k * subfrLength + i] = sat16(
                smulww(channel.excQ14[i + (k + channel.nbSubfr - 2) * subfrLength], prevGainQ10[k]) shr 8,
            )
        }
    }
    sumSqrShift(plc.energy, excBuf, 0, subfrLength)
    energies[0] = plc.energy[0]
    energies[1] = plc.energy[1]
    sumSqrShift(plc.energy, excBuf, subfrLength, subfrLength)
    energies[2] = plc.energy[0]
    energies[3] = plc.energy[1]
}

/** Translates `silk_PLC_conceal`. */
private fun plcConceal(channel: SilkChannelDecoder, params: SilkFrameParameters, frame: IntArray, offset: Int) {
    val plc = channel.plc
    val lpcOrder = channel.lpcOrder
    val ltpMemLength = channel.ltpMemLength
    val prevGainQ10 = intArrayOf(plc.prevGainQ16[0] shr 6, plc.prevGainQ16[1] shr 6)

    if (channel.firstFrameAfterReset != 0) {
        plc.prevLpcQ12.fill(0)
    }

    // The noise source is the quieter of the last two subframes' excitation.
    val energies = IntArray(4)
    plcEnergy(channel, prevGainQ10, energies)
    val energy1 = energies[0]
    val shift1 = energies[1]
    val energy2 = energies[2]
    val shift2 = energies[3]
    val randStart = if ((energy1 shr shift2) < (energy2 shr shift1)) {
        maxOf(0, (plc.nbSubfr - 1) * plc.subfrLength - RAND_BUF_SIZE)
    } else {
        maxOf(0, plc.nbSubfr * plc.subfrLength - RAND_BUF_SIZE)
    }

    val bQ14 = plc.ltpCoefQ14
    var randScaleQ14 = plc.randScaleQ14

    val attenuation = minOf(NB_ATT - 1, channel.lossCnt)
    val harmGainQ15 = HARM_ATT_Q15[attenuation]
    var randGainQ15 = if (channel.prevSignalType == TYPE_VOICED) {
        PLC_RAND_ATTENUATE_V_Q15[attenuation]
    } else {
        PLC_RAND_ATTENUATE_UV_Q15[attenuation]
    }

    bwexpander(plc.prevLpcQ12, lpcOrder, BWE_COEF_Q16)
    val aQ12 = plc.aQ12
    plc.prevLpcQ12.copyInto(aQ12, 0, 0, lpcOrder)

    if (channel.lossCnt == 0) {
        // First lost frame: decide how much noise to mix with the pitch.
        randScaleQ14 = 1 shl 14
        if (channel.prevSignalType == TYPE_VOICED) {
            for (i in 0 until LTP_ORDER) {
                randScaleQ14 = (randScaleQ14 - bQ14[i]).toShort().toInt()
            }
            randScaleQ14 = maxOf(3277, randScaleQ14)
            randScaleQ14 = (smulbb(randScaleQ14, plc.prevLtpScaleQ14) shr 14).toShort().toInt()
        } else {
            // Unvoiced: less noise when the filter has a lot of gain, so that
            // the result does not whistle.
            val invGainQ30 = lpcInversePredGain(plc.prevLpcQ12, lpcOrder)
            var downScaleQ30 = minOf((1 shl 30) shr LOG2_INV_LPC_GAIN_HIGH_THRES, invGainQ30)
            downScaleQ30 = maxOf((1 shl 30) shr LOG2_INV_LPC_GAIN_LOW_THRES, downScaleQ30)
            downScaleQ30 = downScaleQ30 shl LOG2_INV_LPC_GAIN_HIGH_THRES
            randGainQ15 = smulwb(downScaleQ30, randGainQ15) shr 14
        }
    }

    var randSeed = plc.randSeed
    var lag = rshiftRound(plc.pitchLQ8, 8)
    var sLtpBufIdx = ltpMemLength

    // Filter the output history back to a residual, as the decoder does
    // before pitch prediction.
    val sLtp = plc.sLtp
    val sLtpQ14 = plc.sLtpQ14
    val idx = ltpMemLength - lag - lpcOrder - LTP_ORDER / 2
    lpcAnalysisFilter(sLtp, idx, channel.outBuf, idx, aQ12, ltpMemLength - idx, lpcOrder)
    var invGainQ30 = inverse32VarQ(plc.prevGainQ16[1], 46)
    invGainQ30 = minOf(invGainQ30, Int.MAX_VALUE shr 1)
    for (i in idx + lpcOrder until ltpMemLength) {
        sLtpQ14[i] = smulwb(invGainQ30, sLtp[i])
    }

    // Long-term synthesis: the pitch pulse repeated plus noise, both fading,
    // the pitch period stretching slightly each subframe.
    for (k in 0 until channel.nbSubfr) {
        var predLag = sLtpBufIdx - lag + LTP_ORDER / 2
        for (i in 0 until channel.subfrLength) {
            var ltpPredQ12 = 2
            ltpPredQ12 = smlawb(ltpPredQ12, sLtpQ14[predLag], bQ14[0])
            ltpPredQ12 = smlawb(ltpPredQ12, sLtpQ14[predLag - 1], bQ14[1])
            ltpPredQ12 = smlawb(ltpPredQ12, sLtpQ14[predLag - 2], bQ14[2])
            ltpPredQ12 = smlawb(ltpPredQ12, sLtpQ14[predLag - 3], bQ14[3])
            ltpPredQ12 = smlawb(ltpPredQ12, sLtpQ14[predLag - 4], bQ14[4])
            predLag++

            randSeed = silkRand(randSeed)
            val noiseAt = randStart + ((randSeed shr 25) and RAND_BUF_MASK)
            sLtpQ14[sLtpBufIdx] = smlawb(ltpPredQ12, channel.excQ14[noiseAt], randScaleQ14) shl 2
            sLtpBufIdx++
        }

        for (j in 0 until LTP_ORDER) {
            bQ14[j] = (smulbb(harmGainQ15, bQ14[j]) shr 15).toShort().toInt()
        }
        randScaleQ14 = (smulbb(randScaleQ14, randGainQ15) shr 15).toShort().toInt()

        plc.pitchLQ8 = smlawb(plc.pitchLQ8, plc.pitchLQ8, PITCH_DRIFT_FAC_Q16)
        plc.pitchLQ8 = minOf(plc.pitchLQ8, smulbb(MAX_PITCH_LAG_MS, channel.fsKHz) shl 8)
        lag = rshiftRound(plc.pitchLQ8, 8)
    }

    // Short-term synthesis, in place over the end of the LTP buffer, starting
    // from the decoder's own LPC state.
    val lpcStart = ltpMemLength - MAX_LPC_ORDER
    channel.sLpcQ14Buf.copyInto(sLtpQ14, lpcStart, 0, MAX_LPC_ORDER)
    for (i in 0 until channel.frameLength) {
        val at = lpcStart + MAX_LPC_ORDER + i
        var lpcPredQ10 = lpcOrder shr 1
        for (j in 0 until lpcOrder) {
            lpcPredQ10 = smlawb(lpcPredQ10, sLtpQ14[at - j - 1], aQ12[j])
        }
        sLtpQ14[at] = addSat32(sLtpQ14[at], lshiftSat32(lpcPredQ10, 4))
        frame[offset + i] = sat16(rshiftRound(smulww(sLtpQ14[at], prevGainQ10[1]), 8))
    }

    sLtpQ14.copyInto(channel.sLpcQ14Buf, 0, lpcStart + channel.frameLength, lpcStart + channel.frameLength + MAX_LPC_ORDER)

    plc.randSeed = randSeed
    plc.randScaleQ14 = randScaleQ14
    for (i in 0 until MAX_NB_SUBFR) {
        params.pitchL[i] = lag
    }
}

/**
 * Translates `silk_PLC_glue_frames`: after a loss, fades the first good frame
 * in from the concealment's level if it is louder.
 */
internal fun plcGlueFrames(channel: SilkChannelDecoder, frame: IntArray, offset: Int, length: Int) {
    val plc = channel.plc
    if (channel.lossCnt != 0) {
        sumSqrShift(plc.energy, frame, offset, length)
        plc.concEnergy = plc.energy[0]
        plc.concEnergyShift = plc.energy[1]
        plc.lastFrameLost = 1
        return
    }
    if (plc.lastFrameLost != 0) {
        sumSqrShift(plc.energy, frame, offset, length)
        var energy = plc.energy[0]
        val energyShift = plc.energy[1]

        if (energyShift > plc.concEnergyShift) {
            plc.concEnergy = plc.concEnergy shr (energyShift - plc.concEnergyShift)
        } else if (energyShift < plc.concEnergyShift) {
            energy = energy shr (plc.concEnergyShift - energyShift)
        }

        if (energy > plc.concEnergy) {
            val leadingZeros = clz32(plc.concEnergy) - 1
            plc.concEnergy = plc.concEnergy shl leadingZeros
            energy = energy shr maxOf(24 - leadingZeros, 0)

            val fracQ24 = plc.concEnergy / maxOf(energy, 1)
            var gainQ16 = sqrtApprox(fracQ24) shl 4
            // Four times steeper than the frame needs, so that an onset after
            // silence is not lost.
            val slopeQ16 = (((1 shl 16) - gainQ16) / length) shl 2
            for (i in 0 until length) {
                frame[offset + i] = smulwb(gainQ16, frame[offset + i]).toShort().toInt()
                gainQ16 += slopeQ16
                if (gainQ16 > 1 shl 16) break
            }
        }
    }
    plc.lastFrameLost = 0
}

/** Translates `silk_CNG_Reset`: a flat spectrum and no gain. */
internal fun cngReset(channel: SilkChannelDecoder) {
    val cng = channel.cng
    val stepQ15 = INT16_MAX / (channel.lpcOrder + 1)
    var accQ15 = 0
    for (i in 0 until channel.lpcOrder) {
        accQ15 += stepQ15
        cng.smthNlsfQ15[i] = accQ15
    }
    cng.smthGainQ16 = 0
    cng.randSeed = 3_176_576
}

/**
 * Translates `silk_CNG`: tracks the background from inactive frames and, while
 * frames are being concealed, adds comfort noise to [frame] from [offset].
 */
internal fun cngApply(channel: SilkChannelDecoder, params: SilkFrameParameters, frame: IntArray, offset: Int, length: Int) {
    val cng = channel.cng
    val lpcOrder = channel.lpcOrder

    if (channel.fsKHz != cng.fsKHz) {
        cngReset(channel)
        cng.fsKHz = channel.fsKHz
    }

    if (channel.lossCnt == 0 && channel.prevSignalType == TYPE_NO_VOICE_ACTIVITY) {
        for (i in 0 until lpcOrder) {
            cng.smthNlsfQ15[i] = (
                cng.smthNlsfQ15[i] + smulwb(channel.prevNlsfQ15[i] - cng.smthNlsfQ15[i], CNG_NLSF_SMTH_Q16)
                ).toShort().toInt()
        }

        // Keep the excitation of the loudest subframe as the noise source.
        var maxGainQ16 = 0
        var subframe = 0
        for (i in 0 until channel.nbSubfr) {
            if (params.gainsQ16[i] > maxGainQ16) {
                maxGainQ16 = params.gainsQ16[i]
                subframe = i
            }
        }
        val subfrLength = channel.subfrLength
        cng.excBufQ14.copyInto(cng.excBufQ14, subfrLength, 0, (channel.nbSubfr - 1) * subfrLength)
        channel.excQ14.copyInto(cng.excBufQ14, 0, subframe * subfrLength, (subframe + 1) * subfrLength)

        for (i in 0 until channel.nbSubfr) {
            cng.smthGainQ16 += smulwb(params.gainsQ16[i] - cng.smthGainQ16, CNG_GAIN_SMTH_Q16)
            // Follow a drop of more than 3 dB at once.
            if (smulww(cng.smthGainQ16, CNG_GAIN_SMTH_THRESHOLD_Q16) > params.gainsQ16[i]) {
                cng.smthGainQ16 = params.gainsQ16[i]
            }
        }
    }

    if (channel.lossCnt != 0) {
        // The noise level is what the background had, less what concealment
        // is still contributing.
        var gainQ16 = smulww(channel.plc.randScaleQ14, channel.plc.prevGainQ16[1])
        if (gainQ16 >= (1 shl 21) || cng.smthGainQ16 > (1 shl 23)) {
            gainQ16 = smultt(gainQ16, gainQ16)
            gainQ16 = smultt(cng.smthGainQ16, cng.smthGainQ16) - (gainQ16 shl 5)
            gainQ16 = sqrtApprox(gainQ16) shl 16
        } else {
            gainQ16 = smulww(gainQ16, gainQ16)
            gainQ16 = smulww(cng.smthGainQ16, cng.smthGainQ16) - (gainQ16 shl 5)
            gainQ16 = sqrtApprox(gainQ16) shl 8
        }
        val gainQ10 = gainQ16 shr 6

        val sig = cng.sigQ14
        cngExcitation(sig, MAX_LPC_ORDER, cng, length)

        nlsfToLpc(cng.aQ12, cng.smthNlsfQ15, lpcOrder)

        cng.synthState.copyInto(sig, 0, 0, MAX_LPC_ORDER)
        for (i in 0 until length) {
            val at = MAX_LPC_ORDER + i
            var lpcPredQ10 = lpcOrder shr 1
            for (j in 0 until lpcOrder) {
                lpcPredQ10 = smlawb(lpcPredQ10, sig[at - j - 1], cng.aQ12[j])
            }
            sig[at] = addSat32(sig[at], lshiftSat32(lpcPredQ10, 4))
            frame[offset + i] = sat16(frame[offset + i] + sat16(rshiftRound(smulww(sig[at], gainQ10), 8)))
        }
        sig.copyInto(cng.synthState, 0, length, length + MAX_LPC_ORDER)
    } else {
        cng.synthState.fill(0, 0, lpcOrder)
    }
}

/** Translates `silk_CNG_exc`: excitation samples picked at random from the stored buffer. */
private fun cngExcitation(out: IntArray, outStart: Int, cng: SilkCngState, length: Int) {
    var mask = CNG_BUF_MASK_MAX
    while (mask > length) {
        mask = mask shr 1
    }
    var seed = cng.randSeed
    for (i in 0 until length) {
        seed = silkRand(seed)
        out[outStart + i] = cng.excBufQ14[(seed shr 24) and mask]
    }
    cng.randSeed = seed
}

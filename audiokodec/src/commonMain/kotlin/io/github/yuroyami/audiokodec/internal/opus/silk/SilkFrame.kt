package io.github.yuroyami.audiokodec.internal.opus.silk

import io.github.yuroyami.audiokodec.internal.opus.RangeDecoder

/*
 * Decoding one SILK frame for one channel.
 *
 * A SILK frame is 10 or 20 ms of speech described as a source and a filter. The
 * side information gives, per 5 ms subframe, a gain and, for voiced speech, a
 * pitch lag and a five-tap long-term (pitch) predictor; per frame, a spectral
 * envelope as NLSFs. The excitation is a sparse pulse train coded with a shell
 * coder. Decoding runs the excitation through the long-term predictor and then
 * through the short-term (LPC) synthesis filter, scaled by the gains. This is
 * the inverse of the encoder's noise shaping quantiser, which is why the
 * reference calls the core step "inverse NSQ".
 */

internal const val MAX_NB_SUBFR = 4
internal const val MAX_LPC_ORDER = 16
internal const val LTP_ORDER = 5
internal const val MAX_FRAME_LENGTH = 320
internal const val MAX_SUB_FRAME_LENGTH = 80
internal const val MAX_FRAMES_PER_PACKET = 3

internal const val TYPE_NO_VOICE_ACTIVITY = 0
internal const val TYPE_UNVOICED = 1
internal const val TYPE_VOICED = 2

internal const val CODE_INDEPENDENTLY = 0
internal const val CODE_INDEPENDENTLY_NO_LTP_SCALING = 1
internal const val CODE_CONDITIONALLY = 2

internal const val FLAG_DECODE_NORMAL = 0
internal const val FLAG_PACKET_LOST = 1
internal const val FLAG_DECODE_LBRR = 2

private const val SHELL_CODEC_FRAME_LENGTH = 16
private const val LOG2_SHELL_CODEC_FRAME_LENGTH = 4
private const val MAX_NB_SHELL_BLOCKS = MAX_FRAME_LENGTH / SHELL_CODEC_FRAME_LENGTH
private const val SILK_MAX_PULSES = 16
private const val N_RATE_LEVELS = 10
private const val NLSF_QUANT_MAX_AMPLITUDE = 4
private const val QUANT_LEVEL_ADJUST_Q10 = 80
private const val LTP_MEM_LENGTH_MS = 20
private const val SUB_FRAME_LENGTH_MS = 5
private const val MIN_LPC_ORDER = 10
private const val BWE_AFTER_LOSS_Q16 = 63570
private const val PE_MIN_LAG_MS = 2
private const val PE_MAX_LAG_MS = 18
private const val N_LEVELS_QGAIN = 64
private const val MIN_DELTA_GAIN_QUANT = -4
private const val MAX_DELTA_GAIN_QUANT = 36

/** `( MIN_QGAIN_DB * 128 ) / 6 + 16 * 128` from gain_quant.c. */
private const val GAIN_OFFSET = 2090

/** `INV_SCALE_Q16` from gain_quant.c: Q7 decibels per gain index step. */
private const val GAIN_INV_SCALE_Q16 = 1_907_825

/** The quantisation indices of one frame; `SideInfoIndices`. */
internal class SilkSideInfoIndices {
    val gainsIndices = IntArray(MAX_NB_SUBFR)
    val ltpIndex = IntArray(MAX_NB_SUBFR)
    val nlsfIndices = IntArray(MAX_LPC_ORDER + 1)
    var lagIndex = 0
    var contourIndex = 0
    var signalType = 0
    var quantOffsetType = 0
    var nlsfInterpCoefQ2 = 0
    var perIndex = 0
    var ltpScaleIndex = 0
    var seed = 0

    fun clear() {
        gainsIndices.fill(0)
        ltpIndex.fill(0)
        nlsfIndices.fill(0)
        lagIndex = 0
        contourIndex = 0
        signalType = 0
        quantOffsetType = 0
        nlsfInterpCoefQ2 = 0
        perIndex = 0
        ltpScaleIndex = 0
        seed = 0
    }
}

/** The dequantised parameters of one frame; `silk_decoder_control`. */
internal class SilkFrameParameters {
    val pitchL = IntArray(MAX_NB_SUBFR)
    val gainsQ16 = IntArray(MAX_NB_SUBFR)

    /** LPC coefficients for the first and second half of the frame. */
    val predCoefQ12 = arrayOf(IntArray(MAX_LPC_ORDER), IntArray(MAX_LPC_ORDER))
    val ltpCoefQ14 = IntArray(LTP_ORDER * MAX_NB_SUBFR)
    var ltpScaleQ14 = 0
}

/**
 * The state of one SILK channel; `silk_decoder_state`, with the functions that
 * act on one channel at a time.
 *
 * Its packet loss concealment and comfort noise live in `SilkPlc.kt`, and the
 * fields they share are therefore not private.
 */
internal class SilkChannelDecoder {
    var prevGainQ16 = 0
    val excQ14 = IntArray(MAX_FRAME_LENGTH)
    val sLpcQ14Buf = IntArray(MAX_LPC_ORDER)

    /** The last `ltpMemLength` output samples, which the pitch predictor reaches back into. */
    val outBuf = IntArray(MAX_FRAME_LENGTH + 2 * MAX_SUB_FRAME_LENGTH)
    var lagPrev = 0
    var lastGainIndex = 0
    var fsKHz = 0
    var fsApiHz = 0
    var nbSubfr = 0
    var frameLength = 0
    var subfrLength = 0
    var ltpMemLength = 0
    var lpcOrder = 0
    val prevNlsfQ15 = IntArray(MAX_LPC_ORDER)
    var firstFrameAfterReset = 0
    var pitchLagLowBitsIcdf: IntArray = SilkTables.UNIFORM4_ICDF
    var pitchContourIcdf: IntArray = SilkTables.PITCH_CONTOUR_NB_ICDF

    var nFramesDecoded = 0
    var nFramesPerPacket = 0

    var ecPrevSignalType = 0
    var ecPrevLagIndex = 0

    val vadFlags = IntArray(MAX_FRAMES_PER_PACKET)
    var lbrrFlag = 0
    val lbrrFlags = IntArray(MAX_FRAMES_PER_PACKET)

    val resampler = SilkResampler()
    var nlsfCodebook: SilkNlsfCodebook = SilkNlsfCodebook.NB_MB
    val indices = SilkSideInfoIndices()

    val cng = SilkCngState()
    var lossCnt = 0
    var prevSignalType = 0
    val plc = SilkPlcState()

    /** The per-frame parameters, reused from frame to frame. */
    val control = SilkFrameParameters()

    // Scratch space, sized for the largest frame.
    private val pulses = IntArray(MAX_FRAME_LENGTH)
    private val sumPulses = IntArray(MAX_NB_SHELL_BLOCKS)
    private val nLshifts = IntArray(MAX_NB_SHELL_BLOCKS)
    private val ecIx = IntArray(MAX_LPC_ORDER)
    private val predQ8 = IntArray(MAX_LPC_ORDER)
    private val nlsfQ15 = IntArray(MAX_LPC_ORDER)
    private val nlsf0Q15 = IntArray(MAX_LPC_ORDER)
    private val signIcdf = IntArray(2)
    private val shell1 = IntArray(8)
    private val shell2 = IntArray(4)
    private val shell3 = IntArray(2)
    private val sLtp = IntArray(MAX_FRAME_LENGTH)
    private val sLtpQ15 = IntArray(2 * MAX_FRAME_LENGTH)
    private val resQ14 = IntArray(MAX_SUB_FRAME_LENGTH)
    private val sLpcQ14 = IntArray(MAX_SUB_FRAME_LENGTH + MAX_LPC_ORDER)
    private val aQ12Tmp = IntArray(MAX_LPC_ORDER)

    /**
     * Translates `silk_init_decoder` and `silk_reset_decoder`, which are the
     * same thing in this build: every field to zero, then the few that start
     * elsewhere.
     */
    fun reset() {
        prevGainQ16 = 0
        excQ14.fill(0)
        sLpcQ14Buf.fill(0)
        outBuf.fill(0)
        lagPrev = 0
        lastGainIndex = 0
        fsKHz = 0
        fsApiHz = 0
        nbSubfr = 0
        frameLength = 0
        subfrLength = 0
        ltpMemLength = 0
        lpcOrder = 0
        prevNlsfQ15.fill(0)
        nFramesDecoded = 0
        nFramesPerPacket = 0
        ecPrevSignalType = 0
        ecPrevLagIndex = 0
        vadFlags.fill(0)
        lbrrFlag = 0
        lbrrFlags.fill(0)
        resampler.clear()
        indices.clear()
        cng.clear()
        lossCnt = 0
        prevSignalType = 0
        plc.clear()

        // Without a previous frame there is nothing to interpolate from.
        firstFrameAfterReset = 1
        prevGainQ16 = 65536
        cngReset(this)
        plcReset(this)
    }

    /**
     * Translates `silk_decoder_set_fs`: switches to an internal rate of [fsKHz]
     * and an output rate of [fsApiHz], resetting whatever depends on the rate.
     * Returns false when the resampler cannot serve the pair of rates.
     */
    fun setFs(fsKHz: Int, fsApiHz: Int): Boolean {
        var ok = true
        subfrLength = SUB_FRAME_LENGTH_MS * fsKHz
        val newFrameLength = nbSubfr * subfrLength

        if (this.fsKHz != fsKHz || this.fsApiHz != fsApiHz) {
            ok = resampler.init(fsKHz * 1000, fsApiHz)
            this.fsApiHz = fsApiHz
        }

        if (this.fsKHz != fsKHz || newFrameLength != frameLength) {
            pitchContourIcdf = if (fsKHz == 8) {
                if (nbSubfr == MAX_NB_SUBFR) SilkTables.PITCH_CONTOUR_NB_ICDF else SilkTables.PITCH_CONTOUR_10_MS_NB_ICDF
            } else {
                if (nbSubfr == MAX_NB_SUBFR) SilkTables.PITCH_CONTOUR_ICDF else SilkTables.PITCH_CONTOUR_10_MS_ICDF
            }
            if (this.fsKHz != fsKHz) {
                ltpMemLength = LTP_MEM_LENGTH_MS * fsKHz
                if (fsKHz == 8 || fsKHz == 12) {
                    lpcOrder = MIN_LPC_ORDER
                    nlsfCodebook = SilkNlsfCodebook.NB_MB
                } else {
                    lpcOrder = MAX_LPC_ORDER
                    nlsfCodebook = SilkNlsfCodebook.WB
                }
                pitchLagLowBitsIcdf = when (fsKHz) {
                    16 -> SilkTables.UNIFORM8_ICDF
                    12 -> SilkTables.UNIFORM6_ICDF
                    else -> SilkTables.UNIFORM4_ICDF
                }
                firstFrameAfterReset = 1
                lagPrev = 100
                lastGainIndex = 10
                prevSignalType = TYPE_NO_VOICE_ACTIVITY
                outBuf.fill(0)
                sLpcQ14Buf.fill(0)
            }
            this.fsKHz = fsKHz
            frameLength = newFrameLength
        }
        return ok
    }

    /**
     * Translates `silk_decode_frame`: decodes or conceals one frame into [out]
     * from [outOffset], and returns its length in samples.
     */
    fun decodeFrame(range: RangeDecoder?, out: IntArray, outOffset: Int, lostFlag: Int, condCoding: Int): Int {
        val length = frameLength
        val params = control
        params.ltpScaleQ14 = 0

        if (lostFlag == FLAG_DECODE_NORMAL ||
            (lostFlag == FLAG_DECODE_LBRR && lbrrFlags[nFramesDecoded] == 1)
        ) {
            val decoder = range!!
            decodeIndices(decoder, nFramesDecoded, lostFlag != FLAG_DECODE_NORMAL, condCoding)
            decodePulses(decoder, pulses, indices.signalType, indices.quantOffsetType, frameLength)
            decodeParameters(params, condCoding)
            decodeCore(params, out, outOffset, pulses)

            updateOutBuf(out, outOffset)
            plcUpdateOrConceal(this, params, out, outOffset, lost = false)

            lossCnt = 0
            prevSignalType = indices.signalType
            firstFrameAfterReset = 0
        } else {
            // Nothing arrived for this frame: extrapolate one.
            plcUpdateOrConceal(this, params, out, outOffset, lost = true)
            updateOutBuf(out, outOffset)
        }

        cngApply(this, params, out, outOffset, length)
        plcGlueFrames(this, out, outOffset, length)

        lagPrev = params.pitchL[nbSubfr - 1]
        return length
    }

    /** Shifts the output history along and appends the frame just produced. */
    private fun updateOutBuf(out: IntArray, outOffset: Int) {
        val keep = ltpMemLength - frameLength
        outBuf.copyInto(outBuf, 0, frameLength, frameLength + keep)
        out.copyInto(outBuf, keep, outOffset, outOffset + frameLength)
    }

    /**
     * Translates `silk_decode_indices`: the side information of frame
     * [frameIndex], in the order the encoder wrote it.
     */
    fun decodeIndices(range: RangeDecoder, frameIndex: Int, decodeLbrr: Boolean, condCoding: Int) {
        val ix = if (decodeLbrr || vadFlags[frameIndex] != 0) {
            range.decodeIcdf(SilkTables.TYPE_OFFSET_VAD_ICDF, 8) + 2
        } else {
            range.decodeIcdf(SilkTables.TYPE_OFFSET_NO_VAD_ICDF, 8)
        }
        indices.signalType = ix shr 1
        indices.quantOffsetType = ix and 1

        // Gains: the first subframe either relative to the last frame or
        // absolute, as a coarse step and three fine bits; the rest relative.
        if (condCoding == CODE_CONDITIONALLY) {
            indices.gainsIndices[0] = range.decodeIcdf(SilkTables.DELTA_GAIN_ICDF, 8)
        } else {
            val coarse = range.decodeIcdf(SilkTables.GAIN_ICDF, indices.signalType * 8, 8) shl 3
            indices.gainsIndices[0] = (coarse + range.decodeIcdf(SilkTables.UNIFORM8_ICDF, 8)).toByte().toInt()
        }
        for (i in 1 until nbSubfr) {
            indices.gainsIndices[i] = range.decodeIcdf(SilkTables.DELTA_GAIN_ICDF, 8)
        }

        // NLSFs: a first-stage vector, then one residual per coefficient, each
        // with a table chosen by the first stage. Residuals at either end of
        // the table continue into an extension table.
        val codebook = nlsfCodebook
        indices.nlsfIndices[0] = range.decodeIcdf(
            codebook.cb1Icdf,
            (indices.signalType shr 1) * codebook.nVectors,
            8,
        )
        nlsfUnpack(ecIx, predQ8, codebook, indices.nlsfIndices[0])
        for (i in 0 until codebook.order) {
            var value = range.decodeIcdf(codebook.ecIcdf, ecIx[i], 8)
            if (value == 0) {
                value -= range.decodeIcdf(SilkTables.NLSF_EXT_ICDF, 8)
            } else if (value == 2 * NLSF_QUANT_MAX_AMPLITUDE) {
                value += range.decodeIcdf(SilkTables.NLSF_EXT_ICDF, 8)
            }
            indices.nlsfIndices[i + 1] = (value - NLSF_QUANT_MAX_AMPLITUDE).toByte().toInt()
        }

        indices.nlsfInterpCoefQ2 = if (nbSubfr == MAX_NB_SUBFR) {
            range.decodeIcdf(SilkTables.NLSF_INTERPOLATION_FACTOR_ICDF, 8)
        } else {
            4
        }

        if (indices.signalType == TYPE_VOICED) {
            // Pitch lag: relative to the last frame's when that frame was
            // voiced and the change is small, otherwise absolute.
            var absolute = true
            if (condCoding == CODE_CONDITIONALLY && ecPrevSignalType == TYPE_VOICED) {
                val delta = range.decodeIcdf(SilkTables.PITCH_DELTA_ICDF, 8)
                if (delta > 0) {
                    indices.lagIndex = (ecPrevLagIndex + delta - 9).toShort().toInt()
                    absolute = false
                }
            }
            if (absolute) {
                indices.lagIndex = (range.decodeIcdf(SilkTables.PITCH_LAG_ICDF, 8) * (fsKHz shr 1)).toShort().toInt()
                indices.lagIndex = (indices.lagIndex + range.decodeIcdf(pitchLagLowBitsIcdf, 8)).toShort().toInt()
            }
            ecPrevLagIndex = indices.lagIndex

            indices.contourIndex = range.decodeIcdf(pitchContourIcdf, 8)

            // Long-term predictor: a codebook, then one filter from it per subframe.
            indices.perIndex = range.decodeIcdf(SilkTables.LTP_PER_INDEX_ICDF, 8)
            val gainIcdf = LTP_GAIN_ICDF[indices.perIndex]
            for (k in 0 until nbSubfr) {
                indices.ltpIndex[k] = range.decodeIcdf(gainIcdf, 8)
            }

            indices.ltpScaleIndex = if (condCoding == CODE_INDEPENDENTLY) {
                range.decodeIcdf(SilkTables.LTP_SCALE_ICDF, 8)
            } else {
                0
            }
        }
        ecPrevSignalType = indices.signalType

        indices.seed = range.decodeIcdf(SilkTables.UNIFORM4_ICDF, 8)
    }

    /**
     * Translates `silk_decode_parameters`: turns the indices into gains,
     * prediction coefficients and pitch parameters.
     */
    private fun decodeParameters(params: SilkFrameParameters, condCoding: Int) {
        lastGainIndex = gainsDequant(
            params.gainsQ16,
            indices.gainsIndices,
            lastGainIndex,
            condCoding == CODE_CONDITIONALLY,
            nbSubfr,
        )

        nlsfDecode(nlsfQ15, indices.nlsfIndices, nlsfCodebook)
        nlsfToLpc(params.predCoefQ12[1], nlsfQ15, lpcOrder)

        // Interpolating from a frame before a reset would blend in a filter
        // that has nothing to do with this one.
        if (firstFrameAfterReset == 1) {
            indices.nlsfInterpCoefQ2 = 4
        }

        if (indices.nlsfInterpCoefQ2 < 4) {
            // The first half of the frame uses NLSFs part way from the last
            // frame's to this one's.
            for (i in 0 until lpcOrder) {
                nlsf0Q15[i] = (
                    prevNlsfQ15[i] + ((indices.nlsfInterpCoefQ2 * (nlsfQ15[i] - prevNlsfQ15[i])) shr 2)
                    ).toShort().toInt()
            }
            nlsfToLpc(params.predCoefQ12[0], nlsf0Q15, lpcOrder)
        } else {
            params.predCoefQ12[1].copyInto(params.predCoefQ12[0], 0, 0, lpcOrder)
        }

        nlsfQ15.copyInto(prevNlsfQ15, 0, 0, lpcOrder)

        // After a loss, soften the filters so that a mismatch with the
        // concealed history does not ring.
        if (lossCnt != 0) {
            bwexpander(params.predCoefQ12[0], lpcOrder, BWE_AFTER_LOSS_Q16)
            bwexpander(params.predCoefQ12[1], lpcOrder, BWE_AFTER_LOSS_Q16)
        }

        if (indices.signalType == TYPE_VOICED) {
            decodePitch(indices.lagIndex, indices.contourIndex, params.pitchL, fsKHz, nbSubfr)

            val codebook = LTP_VQ_Q7[indices.perIndex]
            for (k in 0 until nbSubfr) {
                val filter = indices.ltpIndex[k]
                for (i in 0 until LTP_ORDER) {
                    params.ltpCoefQ14[k * LTP_ORDER + i] = codebook[filter * LTP_ORDER + i] shl 7
                }
            }
            params.ltpScaleQ14 = SilkTables.LTP_SCALES_TABLE_Q14[indices.ltpScaleIndex]
        } else {
            params.pitchL.fill(0, 0, nbSubfr)
            params.ltpCoefQ14.fill(0, 0, LTP_ORDER * nbSubfr)
            indices.perIndex = 0
            params.ltpScaleQ14 = 0
        }
    }

    /**
     * Translates `silk_decode_core`: excitation through the long-term and then
     * the short-term synthesis filters, [frameLength] samples into [xq] from
     * [xqOffset].
     */
    private fun decodeCore(params: SilkFrameParameters, xq: IntArray, xqOffset: Int, pulses: IntArray) {
        val offsetQ10 = SilkTables.QUANTISATION_OFFSETS_Q10[(indices.signalType shr 1) * 2 + indices.quantOffsetType]
        val nlsfInterpolated = indices.nlsfInterpCoefQ2 < 1 shl 2

        // The excitation: each pulse pulled slightly towards zero, offset, and
        // given a pseudo-random sign so that the quantisation noise is white.
        var randSeed = indices.seed
        for (i in 0 until frameLength) {
            randSeed = silkRand(randSeed)
            var exc = pulses[i] shl 14
            if (exc > 0) {
                exc -= QUANT_LEVEL_ADJUST_Q10 shl 4
            } else if (exc < 0) {
                exc += QUANT_LEVEL_ADJUST_Q10 shl 4
            }
            exc += offsetQ10 shl 4
            if (randSeed < 0) exc = -exc
            excQ14[i] = exc
            randSeed += pulses[i]
        }

        sLpcQ14Buf.copyInto(sLpcQ14, 0, 0, MAX_LPC_ORDER)

        var excAt = 0
        var xqAt = xqOffset
        var sLtpBufIdx = ltpMemLength
        var lag = 0
        for (k in 0 until nbSubfr) {
            val aQ12 = params.predCoefQ12[k shr 1]
            aQ12.copyInto(aQ12Tmp, 0, 0, lpcOrder)
            val bOffset = k * LTP_ORDER
            var signalType = indices.signalType

            val gainQ10 = params.gainsQ16[k] shr 6
            var invGainQ31 = inverse32VarQ(params.gainsQ16[k], 47)

            // When the gain changes, rescale the filter state to the new gain
            // rather than letting the old one leak into this subframe.
            val gainAdjQ16: Int
            if (params.gainsQ16[k] != prevGainQ16) {
                gainAdjQ16 = div32VarQ(prevGainQ16, params.gainsQ16[k], 16)
                for (i in 0 until MAX_LPC_ORDER) {
                    sLpcQ14[i] = smulww(gainAdjQ16, sLpcQ14[i])
                }
            } else {
                gainAdjQ16 = 1 shl 16
            }
            prevGainQ16 = params.gainsQ16[k]

            // After concealing voiced speech, keep a little pitch prediction
            // going into an unvoiced frame instead of cutting it off.
            if (lossCnt != 0 && prevSignalType == TYPE_VOICED &&
                indices.signalType != TYPE_VOICED && k < MAX_NB_SUBFR / 2
            ) {
                params.ltpCoefQ14.fill(0, bOffset, bOffset + LTP_ORDER)
                params.ltpCoefQ14[bOffset + LTP_ORDER / 2] = 4096
                signalType = TYPE_VOICED
                params.pitchL[k] = lagPrev
            }

            if (signalType == TYPE_VOICED) {
                lag = params.pitchL[k]

                // The pitch predictor runs on the residual, not the output, so
                // the output history is filtered back to a residual with this
                // subframe's LPC filter whenever that filter is new.
                if (k == 0 || (k == 2 && nlsfInterpolated)) {
                    val startIdx = ltpMemLength - lag - lpcOrder - LTP_ORDER / 2
                    if (k == 2) {
                        xq.copyInto(outBuf, ltpMemLength, xqOffset, xqOffset + 2 * subfrLength)
                    }
                    lpcAnalysisFilter(
                        sLtp, startIdx, outBuf, startIdx + k * subfrLength,
                        aQ12, ltpMemLength - startIdx, lpcOrder,
                    )
                    if (k == 0) {
                        // Scale down the history at the start of a packet, so
                        // that a lost packet before it does less damage.
                        invGainQ31 = smulwb(invGainQ31, params.ltpScaleQ14) shl 2
                    }
                    for (i in 0 until lag + LTP_ORDER / 2) {
                        sLtpQ15[sLtpBufIdx - i - 1] = smulwb(invGainQ31, sLtp[ltpMemLength - i - 1])
                    }
                } else if (gainAdjQ16 != 1 shl 16) {
                    for (i in 0 until lag + LTP_ORDER / 2) {
                        sLtpQ15[sLtpBufIdx - i - 1] = smulww(gainAdjQ16, sLtpQ15[sLtpBufIdx - i - 1])
                    }
                }
            }

            val residual: IntArray
            val residualAt: Int
            if (signalType == TYPE_VOICED) {
                val b = params.ltpCoefQ14
                var predLag = sLtpBufIdx - lag + LTP_ORDER / 2
                for (i in 0 until subfrLength) {
                    // Starting from 2 rather than 0 offsets the downward bias
                    // of the truncating multiplies.
                    var ltpPredQ13 = 2
                    ltpPredQ13 = smlawb(ltpPredQ13, sLtpQ15[predLag], b[bOffset])
                    ltpPredQ13 = smlawb(ltpPredQ13, sLtpQ15[predLag - 1], b[bOffset + 1])
                    ltpPredQ13 = smlawb(ltpPredQ13, sLtpQ15[predLag - 2], b[bOffset + 2])
                    ltpPredQ13 = smlawb(ltpPredQ13, sLtpQ15[predLag - 3], b[bOffset + 3])
                    ltpPredQ13 = smlawb(ltpPredQ13, sLtpQ15[predLag - 4], b[bOffset + 4])
                    predLag++

                    resQ14[i] = excQ14[excAt + i] + (ltpPredQ13 shl 1)
                    sLtpQ15[sLtpBufIdx] = resQ14[i] shl 1
                    sLtpBufIdx++
                }
                residual = resQ14
                residualAt = 0
            } else {
                residual = excQ14
                residualAt = excAt
            }

            for (i in 0 until subfrLength) {
                // Half the order, again to offset the multiplies' bias.
                var lpcPredQ10 = lpcOrder shr 1
                for (j in 0 until lpcOrder) {
                    lpcPredQ10 = smlawb(lpcPredQ10, sLpcQ14[MAX_LPC_ORDER + i - j - 1], aQ12Tmp[j])
                }
                sLpcQ14[MAX_LPC_ORDER + i] = addSat32(residual[residualAt + i], lshiftSat32(lpcPredQ10, 4))
                xq[xqAt + i] = sat16(rshiftRound(smulww(sLpcQ14[MAX_LPC_ORDER + i], gainQ10), 8))
            }

            sLpcQ14.copyInto(sLpcQ14, 0, subfrLength, subfrLength + MAX_LPC_ORDER)
            excAt += subfrLength
            xqAt += subfrLength
        }

        sLpcQ14.copyInto(sLpcQ14Buf, 0, 0, MAX_LPC_ORDER)
    }

    private companion object {
        val LTP_GAIN_ICDF = arrayOf(SilkTables.LTP_GAIN_ICDF_0, SilkTables.LTP_GAIN_ICDF_1, SilkTables.LTP_GAIN_ICDF_2)
        val LTP_VQ_Q7 = arrayOf(SilkTables.LTP_GAIN_VQ_0, SilkTables.LTP_GAIN_VQ_1, SilkTables.LTP_GAIN_VQ_2)
        val SHELL_TABLES = arrayOf(
            SilkTables.SHELL_CODE_TABLE0,
            SilkTables.SHELL_CODE_TABLE1,
            SilkTables.SHELL_CODE_TABLE2,
            SilkTables.SHELL_CODE_TABLE3,
        )

        /**
         * Translates `silk_gains_dequant`: gain indices to linear gains in Q16,
         * accumulating from [previousIndex]. Returns the last index, which
         * the next frame continues from.
         */
        fun gainsDequant(
            gainQ16: IntArray,
            ind: IntArray,
            previousIndex: Int,
            conditional: Boolean,
            nbSubfr: Int,
        ): Int {
            var prevInd = previousIndex
            for (k in 0 until nbSubfr) {
                if (k == 0 && !conditional) {
                    // An absolute index may not fall more than 16 steps below
                    // the previous one, about 22 dB.
                    prevInd = maxOf(ind[k], prevInd - 16)
                } else {
                    // Deltas above a threshold count double, so that a sudden
                    // rise can be followed in fewer bits.
                    val indTmp = ind[k] + MIN_DELTA_GAIN_QUANT
                    val doubleStepSizeThreshold = 2 * MAX_DELTA_GAIN_QUANT - N_LEVELS_QGAIN + prevInd
                    prevInd += if (indTmp > doubleStepSizeThreshold) {
                        (indTmp shl 1) - doubleStepSizeThreshold
                    } else {
                        indTmp
                    }
                }
                prevInd = limit(prevInd, 0, N_LEVELS_QGAIN - 1)
                gainQ16[k] = log2lin(minOf(smulwb(GAIN_INV_SCALE_Q16, prevInd) + GAIN_OFFSET, 3967))
            }
            return prevInd
        }

        /**
         * Translates `silk_decode_pitch`: a lag per subframe from the frame's
         * lag and contour, kept within the 2 to 18 ms that SILK codes.
         */
        fun decodePitch(lagIndex: Int, contourIndex: Int, pitchLags: IntArray, fsKHz: Int, nbSubfr: Int) {
            val lagCb: IntArray
            val cbkSize: Int
            if (fsKHz == 8) {
                if (nbSubfr == MAX_NB_SUBFR) {
                    lagCb = SilkTables.CB_LAGS_STAGE2
                    cbkSize = 11
                } else {
                    lagCb = SilkTables.CB_LAGS_STAGE2_10_MS
                    cbkSize = 3
                }
            } else {
                if (nbSubfr == MAX_NB_SUBFR) {
                    lagCb = SilkTables.CB_LAGS_STAGE3
                    cbkSize = 34
                } else {
                    lagCb = SilkTables.CB_LAGS_STAGE3_10_MS
                    cbkSize = 12
                }
            }
            val minLag = PE_MIN_LAG_MS * fsKHz
            val maxLag = PE_MAX_LAG_MS * fsKHz
            val lag = minLag + lagIndex
            for (k in 0 until nbSubfr) {
                pitchLags[k] = limit(lag + lagCb[k * cbkSize + contourIndex], minLag, maxLag)
            }
        }
    }

    /**
     * Translates `silk_decode_pulses`: the excitation's pulses for a frame of
     * [frameLength] samples, in blocks of 16, into [pulses].
     */
    fun decodePulses(range: RangeDecoder, pulses: IntArray, signalType: Int, quantOffsetType: Int, frameLength: Int) {
        val rateLevel = range.decodeIcdf(SilkTables.RATE_LEVELS_ICDF, (signalType shr 1) * (N_RATE_LEVELS - 1), 8)

        // A 10 ms frame at 12 kHz is 120 samples, seven and a half blocks; the
        // last block is coded whole.
        var blocks = frameLength shr LOG2_SHELL_CODEC_FRAME_LENGTH
        if (blocks * SHELL_CODEC_FRAME_LENGTH < frameLength) blocks++

        // The number of pulses in each block. A count of 17 means the block's
        // amplitudes are too large and a bit is split off each one, as often
        // as needed; after ten such splits 17 is no longer allowed.
        val countRow = rateLevel * (SILK_MAX_PULSES + 2)
        for (i in 0 until blocks) {
            nLshifts[i] = 0
            sumPulses[i] = range.decodeIcdf(SilkTables.PULSES_PER_BLOCK_ICDF, countRow, 8)
            while (sumPulses[i] == SILK_MAX_PULSES + 1) {
                nLshifts[i]++
                val shifted = (N_RATE_LEVELS - 1) * (SILK_MAX_PULSES + 2) + if (nLshifts[i] == 10) 1 else 0
                sumPulses[i] = range.decodeIcdf(SilkTables.PULSES_PER_BLOCK_ICDF, shifted, 8)
            }
        }

        for (i in 0 until blocks) {
            val at = i * SHELL_CODEC_FRAME_LENGTH
            if (sumPulses[i] > 0) {
                shellDecoder(pulses, at, range, sumPulses[i])
            } else {
                pulses.fill(0, at, at + SHELL_CODEC_FRAME_LENGTH)
            }
        }

        // The bits split off above, most significant first.
        for (i in 0 until blocks) {
            val shifts = nLshifts[i]
            if (shifts > 0) {
                val at = i * SHELL_CODEC_FRAME_LENGTH
                for (k in 0 until SHELL_CODEC_FRAME_LENGTH) {
                    var absQ = pulses[at + k]
                    for (j in 0 until shifts) {
                        absQ = (absQ shl 1) + range.decodeIcdf(SilkTables.LSB_ICDF, 8)
                    }
                    pulses[at + k] = absQ
                }
                // Marks the block as having pulses for the sign decoding.
                sumPulses[i] = sumPulses[i] or (shifts shl 5)
            }
        }

        decodeSigns(range, pulses, frameLength, signalType, quantOffsetType)
    }

    /**
     * Translates `silk_shell_decoder`: splits [total] pulses over 16 samples by
     * halving recursively, each split coded given the count being split.
     */
    private fun shellDecoder(pulses0: IntArray, at: Int, range: RangeDecoder, total: Int) {
        decodeSplit(shell3, 0, range, total, 3)

        decodeSplit(shell2, 0, range, shell3[0], 2)

        decodeSplit(shell1, 0, range, shell2[0], 1)
        decodeSplit(pulses0, at, range, shell1[0], 0)
        decodeSplit(pulses0, at + 2, range, shell1[1], 0)

        decodeSplit(shell1, 2, range, shell2[1], 1)
        decodeSplit(pulses0, at + 4, range, shell1[2], 0)
        decodeSplit(pulses0, at + 6, range, shell1[3], 0)

        decodeSplit(shell2, 2, range, shell3[1], 2)

        decodeSplit(shell1, 4, range, shell2[2], 1)
        decodeSplit(pulses0, at + 8, range, shell1[4], 0)
        decodeSplit(pulses0, at + 10, range, shell1[5], 0)

        decodeSplit(shell1, 6, range, shell2[3], 1)
        decodeSplit(pulses0, at + 12, range, shell1[6], 0)
        decodeSplit(pulses0, at + 14, range, shell1[7], 0)
    }

    /** The two halves of [count] pulses into `child[at]` and `child[at + 1]`. */
    private fun decodeSplit(child: IntArray, at: Int, range: RangeDecoder, count: Int, level: Int) {
        if (count > 0) {
            child[at] = range.decodeIcdf(SHELL_TABLES[level], SilkTables.SHELL_CODE_TABLE_OFFSETS[count], 8)
            child[at + 1] = count - child[at]
        } else {
            child[at] = 0
            child[at + 1] = 0
        }
    }

    /**
     * Translates `silk_decode_signs`: a sign for every nonzero pulse, with a
     * probability that depends on the frame type and on how many pulses the
     * block has.
     */
    private fun decodeSigns(range: RangeDecoder, pulses: IntArray, frameLength: Int, signalType: Int, quantOffsetType: Int) {
        val row = 7 * (quantOffsetType + (signalType shl 1))
        signIcdf[1] = 0
        val blocks = (frameLength + SHELL_CODEC_FRAME_LENGTH / 2) shr LOG2_SHELL_CODEC_FRAME_LENGTH
        var at = 0
        for (i in 0 until blocks) {
            val p = sumPulses[i]
            if (p > 0) {
                signIcdf[0] = SilkTables.SIGN_ICDF[row + minOf(p and 0x1F, 6)]
                for (j in 0 until SHELL_CODEC_FRAME_LENGTH) {
                    if (pulses[at + j] > 0) {
                        // Zero means negative.
                        pulses[at + j] *= (range.decodeIcdf(signIcdf, 8) shl 1) - 1
                    }
                }
            }
            at += SHELL_CODEC_FRAME_LENGTH
        }
    }
}

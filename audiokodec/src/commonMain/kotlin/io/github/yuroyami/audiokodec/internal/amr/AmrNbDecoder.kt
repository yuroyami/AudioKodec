/*
 * Translated from opencore-amr 0.1.6, Copyright 1998-2009 PacketVideo and
 * licensed under the Apache License 2.0, with portions from the 3GPP AMR
 * reference code (3GPP TS 26.073) distributed under it. See NOTICE and
 * reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.amr

import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.MAX_16
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.MAX_32
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.MIN_16
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.MIN_32
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.absS
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.add16
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.divS
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.gmedN
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.invSqrt
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.lAdd
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.lMac
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.lMsu
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.lMult
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.lShl
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.lShr
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.lShrR
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.lSub
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.log2
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.log2Norm
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.mpy32x16
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.mult
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.negate
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.normL
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.normS
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.pow2
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.pvRound
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.s16
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.shl
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.shr
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.shrR
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.sqrtLExp
import io.github.yuroyami.audiokodec.internal.amr.AmrNbOps.sub

/**
 * Decodes AMR narrowband frames as opencore-amr's `Decoder_Interface_Decode`
 * does: 160 samples at 8 kHz per frame, as 13-bit integers in 16-bit words.
 *
 * Each frame comes in the storage format of RFC 4867 section 5: a header
 * byte whose frame type field names the mode, followed by the speech bits in
 * order of importance. As in the reference, the header's quality bit is not
 * consulted; a frame of type 15 carries no data, and the decoder conceals or
 * continues comfort noise in its place.
 */
internal class AmrNbDecoder {

    // Speech_Decode_FrameState
    private var prevMode = MR475

    // Decoder_amrState
    private val oldExc = IntArray(L_SUBFR + PIT_MAX + L_INTERPOL)
    private val excAt = PIT_MAX + L_INTERPOL
    private val lspOld = IntArray(M)
    private val memSyn = IntArray(M)
    private var sharp = 0
    private var oldT0 = 0
    private var prevBf = 0
    private var prevPdf = 0
    private var state = 0
    private val excEnergyHist = IntArray(EXC_ENERGY_HIST_LEN)
    private var t0LagBuff = 0
    private var inBackgroundNoise = 0
    private var voicedHangover = 0
    private val ltpGainHistory = IntArray(LTP_GAIN_HISTORY_LEN)
    private var nodataSeed = 0

    // D_plsfState
    private val pastRq = IntArray(M)
    private val pastLsfQ = IntArray(M)

    // ec_gain_pitchState and ec_gain_codeState
    private val pbuf = IntArray(5)
    private var pastGainPit = 0
    private var prevGp = 0
    private val gbuf = IntArray(5)
    private var pastGainCode = 0
    private var prevGc = 0

    // gc_predState
    private val pastQuaEn = IntArray(NPRED)
    private val pastQuaEnMr122 = IntArray(NPRED)

    // Cb_gain_averageState
    private val cbGainHistory = IntArray(L_CBGAINHIST)
    private var hangVar = 0
    private var hangCount = 0

    // lsp_avgState
    private val lspMeanSave = IntArray(M)

    // Bgn_scdState
    private val frameEnergyHist = IntArray(L_ENERGYHIST)
    private var bgHangover = 0

    // ph_dispState
    private val gainMem = IntArray(PHDGAINMEMSIZE)
    private var phPrevState = 0
    private var phPrevCbGain = 0
    private var lockFull = 0
    private var onset = 0

    // dtx_decState
    private var sinceLastSid = 0
    private var trueSidPeriodInv = 0
    private var logEn = 0
    private var oldLogEn = 0
    private var pnSeedRx = 0
    private val dtxLsp = IntArray(M)
    private val dtxLspOld = IntArray(M)
    private val lsfHist = IntArray(M * DTX_HIST_SIZE)
    private var lsfHistPtr = 0
    private val lsfHistMean = IntArray(M * DTX_HIST_SIZE)
    private var logPgMean = 0
    private val logEnHist = IntArray(DTX_HIST_SIZE)
    private var logEnHistPtr = 0
    private var logEnAdjust = 0
    private var dtxHangoverCount = 0
    private var decAnaElapsedCount = 0
    private var sidFrame = 0
    private var validData = 0
    private var dtxHangoverAdded = 0
    private var dtxGlobalState = DTX
    private var dataUpdated = 0

    // Post_FilterState, with its agc and preemphasis states
    private val res2 = IntArray(L_SUBFR)
    private val memSynPst = IntArray(M)
    private val synthBuf = IntArray(M + L_FRAME)
    private var pastGain = 0
    private var memPre = 0

    // Post_ProcessState
    private var y2Hi = 0
    private var y2Lo = 0
    private var y1Hi = 0
    private var y1Lo = 0
    private var x0 = 0
    private var x1 = 0

    // Working memory, which the reference keeps on its stack.
    private val serial = IntArray(MAX_SERIAL_SIZE)
    private val parm = IntArray(MAX_PRM_SIZE + 1)
    private val azDec = IntArray(AZ_SIZE)
    private val synth = IntArray(L_FRAME)
    private val two = IntArray(2)

    init {
        reset()
    }

    /** `Speech_Decode_Frame_reset`, which is also the state a new decoder starts in. */
    fun reset() {
        decoderAmrReset(MR475)
        memSynPst.fill(0)
        res2.fill(0)
        synthBuf.fill(0)
        pastGain = 4096
        memPre = 0
        y2Hi = 0; y2Lo = 0; y1Hi = 0; y1Lo = 0; x0 = 0; x1 = 0
        prevMode = MR475
    }

    /**
     * Decodes the frame at [offset] in [frame], header byte first, into 160
     * samples in [out] from [outOffset], and returns how many bytes the
     * frame takes, header included, or -1 for a frame type the reference
     * does not decode (the speech codecs of other systems, and the reserved
     * types), whose output it leaves untouched.
     */
    fun decode(frame: ByteArray, offset: Int, out: ShortArray, outOffset: Int): Int {
        val frameType = (frame[offset].toInt() shr 3) and 0x0f
        val bits = offset + 1
        var mode = MR475
        var rxType = RX_NO_DATA
        // wmf_to_ets
        val count = AmrNbFrameTables.NUM_OF_BITS[frameType].toInt()
        if (frameType < AMR_SID) {
            val reorder = REORDER[frameType]
            for (i in count - 1 downTo 0) {
                serial[reorder[i].toInt()] = (frame[bits + (i shr 3)].toInt() shr (i.inv() and 7)) and 1
            }
        } else {
            for (i in count - 1 downTo 0) {
                serial[i] = (frame[bits + (i shr 3)].toInt() shr (i.inv() and 7)) and 1
            }
        }
        var size = AmrNbFrameTables.BYTES_PER_FRAME[frameType].toInt()
        when {
            frameType <= AMR_122 -> {
                mode = frameType
                rxType = RX_SPEECH_GOOD
            }
            frameType == AMR_SID -> {
                var modeStore = 0
                for (i in 0 until NUM_AMRSID_RXMODE_BITS) modeStore = modeStore or (serial[AMRSID_RXMODE_BIT_OFFSET + i] shl i)
                mode = modeStore
                rxType = if (serial[AMRSID_RXTYPE_BIT_OFFSET] == 0) RX_SID_FIRST else RX_SID_UPDATE
            }
            frameType < AMR_NO_DATA -> size = -1
            else -> {
                mode = prevMode
                rxType = RX_NO_DATA
            }
        }
        if (size == -1) return -1
        gsmFrameDecode(mode, rxType)
        prevMode = mode
        for (i in 0 until L_FRAME) out[outOffset + i] = synth[i].toShort()
        return size + 1
    }

    // sp_dec.cpp

    private fun gsmFrameDecode(mode: Int, frameType: Int) {
        bits2prm(if (frameType == RX_SID_BAD || frameType == RX_SID_UPDATE) MRDTX else mode)
        decoderAmr(mode, frameType)
        postFilter(mode)
        postProcess()
        for (i in 0 until L_FRAME) synth[i] = s16(synth[i] and 0xfff8)
    }

    private fun bits2prm(mode: Int) {
        val bitno = BITNO[mode]
        var at = 0
        for (i in 0 until AmrNbFrameTables.PRMNO[mode]) {
            var value = 0
            for (b in 0 until bitno[i]) value = s16((value shl 1) or serial[at + b])
            parm[i] = value
            at += bitno[i]
        }
    }

    // dec_amr.cpp

    private fun decoderAmrReset(mode: Int) {
        oldExc.fill(0, 0, PIT_MAX + L_INTERPOL)
        if (mode != MRDTX) memSyn.fill(0)
        sharp = SHARPMIN
        oldT0 = 40
        if (mode != MRDTX) LSP_INIT.copyInto(lspOld)
        prevBf = 0
        prevPdf = 0
        state = 0
        t0LagBuff = 40
        inBackgroundNoise = 0
        voicedHangover = 0
        if (mode != MRDTX) excEnergyHist.fill(0)
        ltpGainHistory.fill(0)
        cbGainHistory.fill(0)
        hangVar = 0
        hangCount = 0
        if (mode != MRDTX) for (i in 0 until M) lspMeanSave[i] = AmrNbTables.MEAN_LSF_5[i].toInt()
        // D_plsf_reset
        pastRq.fill(0)
        for (i in 0 until M) pastLsfQ[i] = AmrNbTables.MEAN_LSF_5[i].toInt()
        // ec_gain_pitch_reset and ec_gain_code_reset
        pbuf.fill(1640)
        pastGainPit = 0
        prevGp = 16384
        gbuf.fill(1)
        pastGainCode = 0
        prevGc = 1
        if (mode != MRDTX) {
            pastQuaEn.fill(MIN_ENERGY)
            pastQuaEnMr122.fill(MIN_ENERGY_MR122)
        }
        frameEnergyHist.fill(0)
        bgHangover = 0
        nodataSeed = 21845
        // ph_disp_reset
        gainMem.fill(0)
        phPrevState = 0
        phPrevCbGain = 0
        lockFull = 0
        onset = 0
        if (mode != MRDTX) dtxDecReset()
    }

    private fun decoderAmr(mode: Int, frameType: Int) {
        val newDtxState = rxDtxHandler(frameType)
        if (newDtxState != SPEECH) {
            decoderAmrReset(MRDTX)
            dtxDec(newDtxState, mode)
            lsfLsp(pastLsfQ, 0, lspOld)
            lspAvg(pastLsfQ)
            dtxGlobalState = newDtxState
            return
        }
        var bfi = 0
        var pdfi = 0
        if (frameType == RX_SPEECH_BAD || frameType == RX_NO_DATA || frameType == RX_ONSET) {
            bfi = 1
            if (frameType == RX_NO_DATA || frameType == RX_ONSET) buildCnParam(AmrNbFrameTables.PRMNO[mode].toInt(), BITNO[mode])
        } else if (frameType == RX_SPEECH_DEGRADED) {
            pdfi = 1
        }
        state = when {
            bfi != 0 -> state + 1
            state == 6 -> 5
            else -> 0
        }
        if (state > 6) state = 6
        if (dtxGlobalState == DTX) {
            state = 5
            prevBf = 0
        } else if (dtxGlobalState == DTX_MUTE) {
            state = 5
            prevBf = 1
        }
        val prevLsf = pastLsfQ.copyOf()
        val lspNew = IntArray(M)
        val lspMid = IntArray(M)
        var p = 0
        if (mode != MR122) {
            dPlsf3(mode, bfi, parm, 0, lspNew)
            p += 3
            intLpc1to3(lspOld, lspNew, azDec)
        } else {
            dPlsf5(bfi, parm, 0, lspMid, lspNew)
            p += 5
            intLpc1and3(lspOld, lspMid, lspNew, azDec)
        }
        lspNew.copyInto(lspOld)

        val code = IntArray(L_SUBFR)
        val excp = IntArray(L_SUBFR)
        val excEnhanced = IntArray(L_SUBFR)
        val lsfI = IntArray(M)
        var az = 0
        var evenSubfr = 0
        var subfrNr = -1
        var t0 = 0
        var t0Frac = 0
        var indexMr475 = 0
        var gainPit = 0
        var gainCode = 0
        var iSubfr = 0
        while (iSubfr < L_FRAME) {
            subfrNr += 1
            evenSubfr = 1 - evenSubfr
            var pitFlag = iSubfr
            if (iSubfr == L_FRAME_BY2 && mode != MR475 && mode != MR515) pitFlag = 0
            var index = parm[p++]
            if (mode != MR122) {
                val flag4 = if (mode == MR475 || mode == MR515 || mode == MR59 || mode == MR67) 1 else 0
                var deltaFrcLow = 5
                var deltaFrcRange = 9
                if (mode == MR795) {
                    deltaFrcLow = 10
                    deltaFrcRange = 19
                }
                var t0Min = s16(oldT0 - deltaFrcLow)
                if (t0Min < PIT_MIN) t0Min = PIT_MIN
                var t0Max = s16(t0Min + deltaFrcRange)
                if (t0Max > PIT_MAX) {
                    t0Max = PIT_MAX
                    t0Min = t0Max - deltaFrcRange
                }
                decLag3(index, t0Min, t0Max, pitFlag, oldT0, flag4)
                t0 = two[0]
                t0Frac = two[1]
                t0LagBuff = t0
                if (bfi != 0) {
                    if (oldT0 < PIT_MAX) oldT0 += 1
                    t0 = oldT0
                    t0Frac = 0
                    if (inBackgroundNoise != 0 && voicedHangover > 4 && (mode == MR475 || mode == MR515 || mode == MR59)) {
                        t0 = t0LagBuff
                    }
                }
                predLt3or6(oldExc, excAt, t0, t0Frac, L_SUBFR, 1)
            } else {
                decLag6(index, PIT_MIN_MR122, PIT_MAX, pitFlag, t0)
                t0 = two[0]
                t0Frac = two[1]
                if (!(bfi == 0 && (pitFlag == 0 || index < 61))) {
                    t0LagBuff = t0
                    t0 = oldT0
                    t0Frac = 0
                }
                predLt3or6(oldExc, excAt, t0, t0Frac, L_SUBFR, 0)
            }

            var pitSharp: Int
            when {
                mode == MR475 || mode == MR515 -> {
                    index = parm[p++]
                    val i = parm[p++]
                    decode2i40x9bits(subfrNr, i, index, code)
                    pitSharp = doubled(sharp)
                }
                mode == MR59 -> {
                    index = parm[p++]
                    val i = parm[p++]
                    decode2i40x11bits(i, index, code)
                    pitSharp = doubled(sharp)
                }
                mode == MR67 -> {
                    index = parm[p++]
                    val i = parm[p++]
                    decode3i40x14bits(i, index, code)
                    pitSharp = doubled(sharp)
                }
                mode <= MR795 -> {
                    index = parm[p++]
                    val i = parm[p++]
                    decode4i40x17bits(i, index, code)
                    pitSharp = doubled(sharp)
                }
                mode == MR102 -> {
                    dec8i40x31bits(parm, p, code)
                    p += 7
                    pitSharp = doubled(sharp)
                }
                else -> {
                    index = parm[p++]
                    gainPit = if (bfi != 0) ecGainPitch(state) else dGainPitch(mode, index)
                    gainPit = ecGainPitchUpdate(bfi, prevBf, gainPit)
                    dec10i40x35bits(parm, p, code)
                    p += 10
                    pitSharp = doubled(gainPit)
                }
            }
            for (i in t0 until L_SUBFR) code[i] = add16(code[i], mult(code[i - t0], pitSharp))

            if (mode == MR475) {
                if (evenSubfr != 0) indexMr475 = parm[p++]
                if (bfi == 0) {
                    decGain(mode, indexMr475, code, evenSubfr)
                    gainPit = two[0]
                    gainCode = two[1]
                } else {
                    gainPit = ecGainPitch(state)
                    gainCode = ecGainCode(state)
                }
                gainPit = ecGainPitchUpdate(bfi, prevBf, gainPit)
                gainCode = ecGainCodeUpdate(bfi, prevBf, gainCode)
                pitSharp = gainPit
                if (pitSharp > SHARPMAX) pitSharp = SHARPMAX
            } else if (mode <= MR74 || mode == MR102) {
                index = parm[p++]
                if (bfi == 0) {
                    decGain(mode, index, code, evenSubfr)
                    gainPit = two[0]
                    gainCode = two[1]
                } else {
                    gainPit = ecGainPitch(state)
                    gainCode = ecGainCode(state)
                }
                gainPit = ecGainPitchUpdate(bfi, prevBf, gainPit)
                gainCode = ecGainCodeUpdate(bfi, prevBf, gainCode)
                pitSharp = gainPit
                if (pitSharp > SHARPMAX) pitSharp = SHARPMAX
                if (mode == MR102 && oldT0 > L_SUBFR + 5) pitSharp = pitSharp shr 2
            } else {
                index = parm[p++]
                if (mode == MR795) {
                    gainPit = if (bfi != 0) ecGainPitch(state) else dGainPitch(mode, index)
                    gainPit = ecGainPitchUpdate(bfi, prevBf, gainPit)
                    index = parm[p++]
                    gainCode = if (bfi == 0) dGainCode(mode, index, code) else ecGainCode(state)
                    gainCode = ecGainCodeUpdate(bfi, prevBf, gainCode)
                    pitSharp = gainPit
                    if (pitSharp > SHARPMAX) pitSharp = SHARPMAX
                } else {
                    gainCode = if (bfi == 0) dGainCode(mode, index, code) else ecGainCode(state)
                    gainCode = ecGainCodeUpdate(bfi, prevBf, gainCode)
                    pitSharp = gainPit
                }
            }

            if (mode != MR475 || evenSubfr == 0) {
                sharp = gainPit
                if (sharp > SHARPMAX) sharp = SHARPMAX
            }
            pitSharp = shl(pitSharp, 1)
            if (pitSharp > 16384) {
                for (i in 0 until L_SUBFR) {
                    val temp = mult(oldExc[excAt + i], pitSharp)
                    var lTemp = lMult(temp, gainPit)
                    if (mode == MR122) lTemp = lTemp shr 1
                    excp[i] = pvRound(lTemp)
                }
            }
            if (bfi == 0) {
                for (i in 0 until 8) ltpGainHistory[i] = ltpGainHistory[i + 1]
                ltpGainHistory[8] = gainPit
            }
            if ((prevBf != 0 || bfi != 0) && inBackgroundNoise != 0 && (mode == MR475 || mode == MR515 || mode == MR59)) {
                if (gainPit > 12288) gainPit = ((gainPit - 12288) shr 1) + 12288
                if (gainPit > 14745) gainPit = 14745
            }
            intLsf(prevLsf, pastLsfQ, iSubfr, lsfI)
            var gainCodeMix = cbGainAverage(mode, gainCode, lsfI, lspMeanSave, bfi, prevBf, pdfi, prevPdf, inBackgroundNoise, voicedHangover)
            if (mode > MR67 && mode != MR102) gainCodeMix = gainCode
            val pitchFac: Int
            val tmpShift: Int
            if (mode <= MR102) {
                pitchFac = gainPit
                tmpShift = 1
            } else {
                pitchFac = gainPit shr 1
                tmpShift = 2
            }
            for (i in 0 until L_SUBFR) {
                excEnhanced[i] = oldExc[excAt + i]
                var lTemp = lMult(oldExc[excAt + i], pitchFac)
                lTemp = lMac(lTemp, code[i], gainCode)
                lTemp = lShl(lTemp, tmpShift)
                oldExc[excAt + i] = pvRound(lTemp)
            }
            lockFull = 0
            if ((mode == MR475 || mode == MR515 || mode == MR59) && voicedHangover > 3 && inBackgroundNoise != 0 && bfi != 0) {
                lockFull = 1
            }
            phDisp(mode, excEnhanced, gainCodeMix, gainPit, code, pitchFac, tmpShift)

            var lTemp = 0
            for (i in 0 until L_SUBFR) lTemp = lMac(lTemp, excEnhanced[i], excEnhanced[i])
            lTemp = lTemp shr 1
            val exp = IntArray(1)
            lTemp = sqrtLExp(lTemp, exp)
            lTemp = lShr(lTemp, s16((exp[0] shr 1) + 15))
            val excEnergy = s16(lTemp shr 2)
            if ((mode == MR475 || mode == MR515 || mode == MR59) && voicedHangover > 5 && inBackgroundNoise != 0 && state < 4 &&
                ((pdfi != 0 && prevPdf != 0) || bfi != 0 || prevBf != 0)
            ) {
                val carefulFlag = if (pdfi != 0 && bfi == 0) 1 else 0
                exCtrl(excEnhanced, excEnergy, excEnergyHist, voicedHangover, prevBf, carefulFlag)
            }
            if (!(inBackgroundNoise != 0 && (bfi != 0 || prevBf != 0) && state < 4)) {
                for (i in 0 until 8) excEnergyHist[i] = excEnergyHist[i + 1]
                excEnergyHist[8] = excEnergy
            }
            if (pitSharp > 16384) {
                for (i in 0 until L_SUBFR) excp[i] = add16(excp[i], excEnhanced[i])
                agc2(excEnhanced, excp, L_SUBFR)
                synFilt(azDec, az, excp, 0, synth, iSubfr, L_SUBFR, memSyn, 0, false)
            } else {
                synFilt(azDec, az, excEnhanced, 0, synth, iSubfr, L_SUBFR, memSyn, 0, false)
            }
            // The reference checks an overflow flag its synthesis filter never sets, so it always does this.
            synth.copyInto(memSyn, 0, iSubfr + L_SUBFR - M, iSubfr + L_SUBFR)
            oldExc.copyInto(oldExc, 0, L_SUBFR, L_SUBFR + PIT_MAX + L_INTERPOL)
            az += MP1
            oldT0 = t0
            iSubfr += L_SUBFR
        }
        inBackgroundNoise = bgnScd(synth)
        dtxDecActivityUpdate(pastLsfQ, synth)
        prevBf = bfi
        prevPdf = pdfi
        lspAvg(pastLsfQ)
        dtxGlobalState = newDtxState
    }

    /** `sharp << 1`, saturated, as the reference writes it out. */
    private fun doubled(value: Int): Int {
        val l = value shl 1
        return if (l != s16(l)) (if (value > 0) MAX_16 else MIN_16) else l
    }

    // d_plsf_3.cpp and d_plsf_5.cpp

    private fun dPlsf3(mode: Int, bfi: Int, indice: IntArray, at: Int, lsp1Q: IntArray) {
        val lsf1R = IntArray(M)
        val lsf1Q = IntArray(M)
        val mean = AmrNbTables.MEAN_LSF_3
        val predFac = AmrNbTables.PRED_FAC_3
        if (bfi != 0) {
            for (i in 0 until M) {
                val temp = mult(pastLsfQ[i], 29491)
                val index = mult(mean[i].toInt(), 3277)
                lsf1Q[i] = add16(index, temp)
            }
            if (mode != MRDTX) {
                for (i in 0 until M) {
                    var temp = mult(pastRq[i], predFac[i].toInt())
                    temp = add16(mean[i].toInt(), temp)
                    pastRq[i] = sub(lsf1Q[i], temp)
                }
            } else {
                for (i in 0 until M) {
                    val temp = add16(mean[i].toInt(), pastRq[i])
                    pastRq[i] = sub(lsf1Q[i], temp)
                }
            }
        } else {
            val cb1: ShortArray
            val cb3: ShortArray
            val limit1: Int
            val limit3: Int
            val limit2 = (DICO2_SIZE - 1) * 3
            val cb2 = AmrNbLsf3Tables.DICO2
            if (mode == MR475 || mode == MR515) {
                cb1 = AmrNbLsf3Tables.DICO1
                cb3 = AmrNbLsf3ExtraTables.MR515_3
                limit1 = (DICO1_SIZE - 1) * 3
                limit3 = (MR515_3_SIZE - 1) * 4
            } else if (mode == MR795) {
                cb1 = AmrNbLsf3ExtraTables.MR795_1
                cb3 = AmrNbLsf3Tables.DICO3
                limit1 = (MR795_1_SIZE - 1) * 3
                limit3 = (DICO3_SIZE - 1) * 4
            } else {
                cb1 = AmrNbLsf3Tables.DICO1
                cb3 = AmrNbLsf3Tables.DICO3
                limit1 = (DICO1_SIZE - 1) * 3
                limit3 = (DICO3_SIZE - 1) * 4
            }
            var index = indice[at]
            var temp = s16(index + (index shl 1))
            if (temp > limit1) temp = limit1
            lsf1R[0] = cb1[temp].toInt()
            lsf1R[1] = cb1[temp + 1].toInt()
            lsf1R[2] = cb1[temp + 2].toInt()
            index = indice[at + 1]
            if (mode == MR475 || mode == MR515) index = s16(index shl 1)
            temp = s16(index + (index shl 1))
            if (temp > limit2) temp = limit2
            lsf1R[3] = cb2[temp].toInt()
            lsf1R[4] = cb2[temp + 1].toInt()
            lsf1R[5] = cb2[temp + 2].toInt()
            index = indice[at + 2]
            temp = s16(index shl 2)
            if (temp > limit3) temp = limit3
            lsf1R[6] = cb3[temp].toInt()
            lsf1R[7] = cb3[temp + 1].toInt()
            lsf1R[8] = cb3[temp + 2].toInt()
            lsf1R[9] = cb3[temp + 3].toInt()
            if (mode != MRDTX) {
                for (i in 0 until M) {
                    var t = mult(pastRq[i], predFac[i].toInt())
                    t = add16(mean[i].toInt(), t)
                    lsf1Q[i] = add16(lsf1R[i], t)
                    pastRq[i] = lsf1R[i]
                }
            } else {
                for (i in 0 until M) {
                    val t = add16(mean[i].toInt(), pastRq[i])
                    lsf1Q[i] = add16(lsf1R[i], t)
                    pastRq[i] = lsf1R[i]
                }
            }
        }
        reorderLsf(lsf1Q, LSF_GAP)
        lsf1Q.copyInto(pastLsfQ)
        lsfLsp(lsf1Q, 0, lsp1Q)
    }

    private fun dPlsf5(bfi: Int, indice: IntArray, at: Int, lsp1Q: IntArray, lsp2Q: IntArray) {
        val lsf1R = IntArray(M)
        val lsf2R = IntArray(M)
        val lsf1Q = IntArray(M)
        val lsf2Q = IntArray(M)
        val mean = AmrNbTables.MEAN_LSF_5
        if (bfi != 0) {
            for (i in 0 until M) {
                var temp = s16((pastLsfQ[i] * 31128) shr 15)
                val sign = s16((mean[i] * 1639) shr 15)
                lsf1Q[i] = add16(sign, temp)
                lsf2Q[i] = lsf1Q[i]
                temp = s16((pastRq[i] * LSP_PRED_FAC_MR122) shr 15)
                temp = add16(mean[i].toInt(), temp)
                pastRq[i] = sub(lsf2Q[i], temp)
            }
        } else {
            var temp = shl(indice[at], 2)
            var d = AmrNbLsf5Tables.DICO1
            lsf1R[0] = d[temp].toInt(); lsf1R[1] = d[temp + 1].toInt(); lsf2R[0] = d[temp + 2].toInt(); lsf2R[1] = d[temp + 3].toInt()
            temp = shl(indice[at + 1], 2)
            d = AmrNbLsf5Tables.DICO2
            lsf1R[2] = d[temp].toInt(); lsf1R[3] = d[temp + 1].toInt(); lsf2R[2] = d[temp + 2].toInt(); lsf2R[3] = d[temp + 3].toInt()
            val sign = indice[at + 2] and 1
            val i3 = indice[at + 2] shr 1
            temp = shl(i3, 2)
            d = AmrNbLsf5Tables.DICO3
            if (sign == 0) {
                lsf1R[4] = d[temp].toInt(); lsf1R[5] = d[temp + 1].toInt(); lsf2R[4] = d[temp + 2].toInt(); lsf2R[5] = d[temp + 3].toInt()
            } else {
                lsf1R[4] = negate(d[temp].toInt()); lsf1R[5] = negate(d[temp + 1].toInt())
                lsf2R[4] = negate(d[temp + 2].toInt()); lsf2R[5] = negate(d[temp + 3].toInt())
            }
            temp = shl(indice[at + 3], 2)
            d = AmrNbLsf5Tables.DICO4
            lsf1R[6] = d[temp].toInt(); lsf1R[7] = d[temp + 1].toInt(); lsf2R[6] = d[temp + 2].toInt(); lsf2R[7] = d[temp + 3].toInt()
            temp = shl(indice[at + 4], 2)
            d = AmrNbLsf5Tables.DICO5
            lsf1R[8] = d[temp].toInt(); lsf1R[9] = d[temp + 1].toInt(); lsf2R[8] = d[temp + 2].toInt(); lsf2R[9] = d[temp + 3].toInt()
            for (i in 0 until M) {
                var t = mult(pastRq[i], LSP_PRED_FAC_MR122)
                t = add16(mean[i].toInt(), t)
                lsf1Q[i] = add16(lsf1R[i], t)
                lsf2Q[i] = add16(lsf2R[i], t)
                pastRq[i] = lsf2R[i]
            }
        }
        reorderLsf(lsf1Q, LSF_GAP)
        reorderLsf(lsf2Q, LSF_GAP)
        lsf2Q.copyInto(pastLsfQ)
        lsfLsp(lsf1Q, 0, lsp1Q)
        lsfLsp(lsf2Q, 0, lsp2Q)
    }

    private fun reorderLsf(lsf: IntArray, minDist: Int) {
        var lsfMin = minDist
        for (i in 0 until M) {
            if (lsf[i] < lsfMin) {
                lsf[i] = lsfMin
                lsfMin = s16(lsfMin + minDist)
            } else {
                lsfMin = s16(lsf[i] + minDist)
            }
        }
    }

    private fun lsfLsp(lsf: IntArray, at: Int, lsp: IntArray) {
        val table = AmrNbTables.LSP_LSF_TABLE
        for (i in 0 until M) {
            val ind = lsf[at + i] shr 8
            val offset = lsf[at + i] and 0x00ff
            val lTmp = ((table[ind + 1] - table[ind]) * offset) shr 8
            lsp[i] = s16(table[ind] + s16(lTmp))
        }
    }

    private fun lspLsf(lsp: IntArray, lsf: IntArray) {
        val table = AmrNbTables.LSP_LSF_TABLE
        val slope = AmrNbTables.LSP_LSF_SLOPE
        var ind = 63
        for (i in M - 1 downTo 0) {
            val temp = lsp[i]
            while (table[ind] < temp) ind--
            var lTmp = (temp - table[ind]) * slope[ind]
            lTmp = (lTmp + 0x00000800) shr 12
            lsf[i] = s16(s16(lTmp) + (ind shl 8))
        }
    }

    // int_lpc.cpp and lsp_az.cpp

    private fun intLpc1and3(lspOld: IntArray, lspMid: IntArray, lspNew: IntArray, a: IntArray) {
        val lsp = IntArray(M)
        for (i in 0 until M) lsp[i] = s16((lspOld[i] shr 1) + (lspMid[i] shr 1))
        lspAz(lsp, a, 0)
        lspAz(lspMid, a, MP1)
        for (i in 0 until M) lsp[i] = s16((lspMid[i] shr 1) + (lspNew[i] shr 1))
        lspAz(lsp, a, 2 * MP1)
        lspAz(lspNew, a, 3 * MP1)
    }

    private fun intLpc1to3(lspOld: IntArray, lspNew: IntArray, a: IntArray) {
        val lsp = IntArray(M)
        for (i in 0 until M) lsp[i] = s16(s16(lspOld[i] - (lspOld[i] shr 2)) + (lspNew[i] shr 2))
        lspAz(lsp, a, 0)
        for (i in 0 until M) lsp[i] = s16((lspNew[i] shr 1) + (lspOld[i] shr 1))
        lspAz(lsp, a, MP1)
        for (i in 0 until M) lsp[i] = s16(s16(lspNew[i] - (lspNew[i] shr 2)) + (lspOld[i] shr 2))
        lspAz(lsp, a, 2 * MP1)
        lspAz(lspNew, a, 3 * MP1)
    }

    private fun getLspPol(lsp: IntArray, start: Int, f: IntArray) {
        var l = start
        var fi = 0
        f[fi++] = 0x01000000
        f[fi++] = -lsp[l] shl 10
        l += 2
        for (i in 2..5) {
            f[fi] = f[fi - 2]
            for (j in 1 until i) {
                val hi = s16(f[fi - 1] shr 16)
                val lo = s16((f[fi - 1] shr 1) - (hi shl 15))
                var t0 = hi * lsp[l]
                t0 += (lo * lsp[l]) shr 15
                f[fi] += f[fi - 2]
                f[fi] -= t0 shl 2
                fi--
            }
            f[fi] -= lsp[l] shl 10
            l++
            fi += i
            l++
        }
    }

    private fun lspAz(lsp: IntArray, a: IntArray, at: Int) {
        val f1 = IntArray(6)
        val f2 = IntArray(6)
        getLspPol(lsp, 0, f1)
        getLspPol(lsp, 1, f2)
        for (i in 5 downTo 1) {
            f1[i] += f1[i - 1]
            f2[i] -= f2[i - 1]
        }
        a[at] = 4096
        var j = 10
        for (i in 1..5) {
            var t0 = f1[i] + f2[i]
            var t1 = f1[i] - f2[i]
            t0 += 1 shl 12
            t1 += 1 shl 12
            a[at + i] = s16(t0 shr 13)
            a[at + j] = s16(t1 shr 13)
            j--
        }
    }

    // dec_lag3.cpp and dec_lag6.cpp: [two] receives T0 and its fraction.

    private fun decLag3(index: Int, t0Min: Int, t0Max: Int, iSubfr: Int, t0Prev: Int, flag4: Int) {
        if (iSubfr == 0) {
            if (index < 197) {
                var tmpLag = s16(index + 2)
                tmpLag = mult(tmpLag, 10923)
                var i = s16(tmpLag + 19)
                two[0] = i
                i = s16(i shl 1)
                i = s16(i + two[0])
                tmpLag = s16(index - i)
                two[1] = s16(tmpLag + 58)
            } else {
                two[0] = s16(index - 112)
                two[1] = 0
            }
        } else if (flag4 == 0) {
            var i = s16(index + 2)
            i = (i * 10923) shr 15
            i -= 1
            two[0] = s16(i + t0Min)
            i = s16(i + (i shl 1))
            val tmpLag = s16(index - 2)
            two[1] = s16(tmpLag - i)
        } else {
            var tmpLag = t0Prev
            var i = sub(tmpLag, t0Min)
            if (i > 5) tmpLag = s16(t0Min + 5)
            i = s16(t0Max - tmpLag)
            if (i > 4) tmpLag = s16(t0Max - 4)
            if (index < 4) {
                i = s16(tmpLag - 5)
                two[0] = s16(i + index)
                two[1] = 0
            } else if (index < 12) {
                i = s16(index - 5)
                i = (i * 10923) shr 15
                i--
                two[0] = s16(i + tmpLag)
                i = s16(i + (i shl 1))
                val t = s16(index - 9)
                two[1] = s16(t - i)
            } else {
                i = s16(index - 12)
                i = s16(i + tmpLag)
                two[0] = s16(i + 1)
                two[1] = 0
            }
        }
    }

    private fun decLag6(index: Int, pitMin: Int, pitMax: Int, iSubfr: Int, previousT0: Int) {
        if (iSubfr == 0) {
            if (index < 463) {
                var i = s16(index + 5)
                i = (i * 5462) shr 15
                i += 17
                two[0] = i
                i = s16(i shl 1)
                i = s16(i + two[0])
                i = s16(i shl 1)
                i = s16(index - i)
                two[1] = s16(i + 105)
            } else {
                two[0] = s16(index - 368)
                two[1] = 0
            }
        } else {
            var t0Min = s16(previousT0 - 5)
            if (t0Min < pitMin) t0Min = pitMin
            var t0Max = s16(t0Min + 9)
            if (t0Max > pitMax) {
                t0Max = pitMax
                t0Min = t0Max - 9
            }
            var i = s16(index + 5)
            i = (i * 5462) shr 15
            i -= 1
            two[0] = s16(i + t0Min)
            i = s16(i + (i shl 1))
            i = s16(i shl 1)
            val k = s16(index - 3)
            two[1] = s16(k - i)
        }
    }

    // pred_lt.cpp

    private fun predLt3or6(exc: IntArray, at: Int, t0: Int, fraction: Int, lSubfr: Int, flag3: Int) {
        var x0 = at - t0
        var frac = -fraction
        if (flag3 != 0) frac = frac shl 1
        if (frac < 0) {
            frac += UP_SAMP_MAX
            x0--
        }
        val inter = AmrNbTables.INTER_6_PRED_LT
        val coeff = IntArray(L_INTER10 shl 1)
        var c = 0
        var k = 0
        for (i in 0 until (L_INTER10 shr 1)) {
            coeff[c++] = inter[frac + k].toInt()
            coeff[c++] = inter[UP_SAMP_MAX - frac + k].toInt()
            k += UP_SAMP_MAX
            coeff[c++] = inter[frac + k].toInt()
            coeff[c++] = inter[UP_SAMP_MAX - frac + k].toInt()
            k += UP_SAMP_MAX
        }
        var out = at
        for (j in 0 until (lSubfr shr 1)) {
            x0++
            var x2 = x0
            var x3 = x0++
            var p = 0
            var s1 = 0x00004000
            var s2 = 0x00004000
            for (i in 0 until (L_INTER10 shr 1)) {
                s2 += exc[x3--] * coeff[p]
                s1 += exc[x3] * coeff[p++]
                s1 += exc[x2++] * coeff[p]
                s2 += exc[x2] * coeff[p++]
                s2 += exc[x3--] * coeff[p]
                s1 += exc[x3] * coeff[p++]
                s1 += exc[x2++] * coeff[p]
                s2 += exc[x2] * coeff[p++]
            }
            exc[out++] = s16(s1 shr 15)
            exc[out++] = s16(s2 shr 15)
        }
    }

    // The algebraic codebooks: d2_9pf.cpp, d2_11pf.cpp, d3_14pf.cpp, d4_17pf.cpp, d8_31pf.cpp, d1035pf.cpp.

    private fun pulses(cod: IntArray, pos: IntArray, count: Int, signs: Int) {
        cod.fill(0)
        var sign = signs
        for (j in 0 until count) {
            val i = sign and 1
            cod[pos[j]] = i * 16383 - 8192
            sign = sign shr 1
        }
    }

    private fun decode2i40x9bits(subNr: Int, sign: Int, index: Int, cod: IntArray) {
        val pos = IntArray(2)
        val j = (index and 64) shr 3
        var k = s16(shl(subNr, 1) + j)
        pos[0] = (index and 7) * 5 + AmrNbTables.START_POS[k++]
        pos[1] = ((index shr 3) and 7) * 5 + AmrNbTables.START_POS[k]
        pulses(cod, pos, 2, sign)
    }

    private fun decode2i40x11bits(sign: Int, indexIn: Int, cod: IntArray) {
        val pos = IntArray(2)
        var index = indexIn
        var j = index and 1
        index = index shr 1
        var i = index and 7
        pos[0] = i * 5 + j * 2 + 1
        index = index shr 3
        j = index and 3
        index = index shr 2
        i = index and 7
        pos[1] = if (j == 3) i * 5 + 4 else i * 5 + j
        pulses(cod, pos, 2, sign)
    }

    private fun decode3i40x14bits(sign: Int, indexIn: Int, cod: IntArray) {
        val pos = IntArray(3)
        var index = indexIn
        var i = index and 7
        pos[0] = i * 5
        index = index shr 3
        var j = index and 1
        index = index shr 1
        i = index and 7
        pos[1] = i * 5 + j * 2 + 1
        index = index shr 3
        j = index and 1
        index = index shr 1
        i = index and 7
        pos[2] = i * 5 + j * 2 + 2
        pulses(cod, pos, 3, sign)
    }

    private fun decode4i40x17bits(sign: Int, indexIn: Int, cod: IntArray) {
        val pos = IntArray(4)
        val dgray = AmrNbTables.DGRAY
        var index = indexIn
        pos[0] = dgray[index and 7] * 5
        index = index shr 3
        pos[1] = dgray[index and 7] * 5 + 1
        index = index shr 3
        pos[2] = dgray[index and 7] * 5 + 2
        index = index shr 3
        val j = index and 1
        index = index shr 1
        pos[3] = dgray[index and 7] * 5 + 3 + j
        pulses(cod, pos, 4, sign)
    }

    private fun decompress10(msbsIn: Int, lsbs: Int, index1: Int, index2: Int, index3: Int, posIndx: IntArray) {
        var msbs = msbsIn
        if (msbs > 124) msbs = 124
        var ia = mult(msbs, 1311)
        var temp = lMult(ia, 25)
        ia = s16(msbs - (temp shr 1))
        var ib = mult(ia, 6554)
        temp = lMult(ib, 5)
        ib = s16(ia - s16(temp shr 1))
        ib = shl(ib, 1)
        var ic = s16(lsbs - ((lsbs shr 2) shl 2))
        posIndx[index1] = s16(ib + (ic and 1))
        ib = mult(ia, 6554)
        ib = shl(ib, 1)
        posIndx[index2] = s16(ib + (ic shr 1))
        ib = lsbs shr 2
        ic = mult(msbs, 1311)
        ic = shl(ic, 1)
        posIndx[index3] = add16(ib, ic)
    }

    private fun dec8i40x31bits(index: IntArray, at: Int, cod: IntArray) {
        cod.fill(0)
        val signs = IntArray(NB_TRACK_MR102)
        val codewords = IntArray(8)
        for (i in 0 until NB_TRACK_MR102) signs[i] = index[at + i]
        var msbs = index[at + NB_TRACK_MR102] shr 3
        var lsbs = index[at + NB_TRACK_MR102] and 7
        decompress10(msbs, lsbs, 0, 4, 1, codewords)
        msbs = index[at + NB_TRACK_MR102 + 1] shr 3
        lsbs = index[at + NB_TRACK_MR102 + 1] and 7
        decompress10(msbs, lsbs, 2, 6, 5, codewords)
        msbs = index[at + NB_TRACK_MR102 + 2] shr 2
        lsbs = index[at + NB_TRACK_MR102 + 2] and 3
        var temp = lMult(msbs, 25)
        var ia = s16(lShr(temp, 1))
        ia = s16(ia + 12)
        val msbs024 = ia shr 5
        ia = mult(msbs024, 6554) and 1
        var ib = mult(msbs024, 6554)
        temp = lMult(ib, 5)
        ib = s16(msbs024 - s16(temp shr 1))
        if (ia == 1) ib = s16(4 - ib)
        ib = shl(ib, 1)
        ia = lsbs and 1
        codewords[3] = add16(ib, ia)
        ia = mult(msbs024, 6554)
        ia = shl(ia, 1)
        codewords[7] = s16(ia + (lsbs shr 1))
        for (j in 0 until NB_TRACK_MR102) {
            val pos1 = s16((codewords[j] shl 2) + j)
            var sign = if (signs[j] == 0) 8191 else -8191
            if (pos1 < L_SUBFR) cod[pos1] = sign
            val pos2 = s16((codewords[j + 4] shl 2) + j)
            if (pos2 < pos1) sign = negate(sign)
            if (pos2 < L_SUBFR) cod[pos2] = s16(cod[pos2] + sign)
        }
    }

    private fun dec10i40x35bits(index: IntArray, at: Int, cod: IntArray) {
        cod.fill(0)
        val dgray = AmrNbTables.DGRAY
        for (j in 0 until NB_TRACK) {
            val tmp = index[at + j]
            val pos1 = dgray[tmp and 7] * 5 + j
            var sign = if (((tmp shr 3) and 1) == 0) 4096 else -4096
            cod[pos1] = sign
            val pos2 = dgray[index[at + j + 5] and 7] * 5 + j
            if (pos2 < pos1) sign = negate(sign)
            cod[pos2] = s16(cod[pos2] + sign)
        }
    }

    // Gains: d_gain_p.cpp, d_gain_c.cpp, dec_gain.cpp, gc_pred.cpp, ec_gains.cpp.

    private fun dGainPitch(mode: Int, index: Int): Int {
        var gain = AmrNbTables.QUA_GAIN_PITCH[index].toInt()
        if (mode == MR122) gain = s16(gain and 0xFFFC)
        return gain
    }

    private fun dGainCode(mode: Int, indexIn: Int, code: IntArray): Int {
        gcPred(mode, code)
        val exp = gcExp
        val frac = gcFrac
        val index = indexIn and 31
        var p = index + (index shl 1)
        val table = AmrNbTables.QUA_GAIN_CODE
        val gainCode: Int
        if (mode == MR122) {
            var gcode0 = s16(pow2(exp, frac))
            gcode0 = shl(gcode0, 4)
            gainCode = shl(mult(gcode0, table[p++].toInt()), 1)
        } else {
            val gcode0 = s16(pow2(14, frac))
            var lTmp = lMult(table[p++].toInt(), gcode0)
            lTmp = lShr(lTmp, sub(9, exp))
            gainCode = s16(lTmp shr 16)
        }
        val quaEnerMr122 = table[p++].toInt()
        val quaEner = table[p].toInt()
        gcPredUpdate(quaEnerMr122, quaEner)
        return gainCode
    }

    /** [two] receives the pitch gain and the code gain. */
    private fun decGain(mode: Int, indexIn: Int, code: IntArray, evenSubfr: Int) {
        var index = shl(indexIn, 2)
        val gainPit: Int
        val gCode: Int
        val quaEnerMr122: Int
        val quaEner: Int
        if (mode == MR102 || mode == MR74 || mode == MR67) {
            val t = AmrNbGainTables.HIGH_RATES
            gainPit = t[index].toInt()
            gCode = t[index + 1].toInt()
            quaEnerMr122 = t[index + 2].toInt()
            quaEner = t[index + 3].toInt()
        } else if (mode == MR475) {
            index = s16(index + ((1 xor evenSubfr) shl 1))
            if (index > MR475_VQ_SIZE * 4 - 2) index = MR475_VQ_SIZE * 4 - 2
            val t = AmrNbGainTables.MR475
            gainPit = t[index].toInt()
            gCode = t[index + 1].toInt()
            val ef = IntArray(2)
            log2(gCode, ef)
            val exp = ef[0] - 12
            val frac = ef[1]
            val temp1 = shrR(frac, 5)
            val temp2 = shl(exp, 10)
            quaEnerMr122 = add16(temp1, temp2)
            var lTmp = mpy32x16(exp, frac, 24660)
            lTmp = lShl(lTmp, 13)
            quaEner = pvRound(lTmp)
        } else {
            val t = AmrNbGainTables.LOW_RATES
            gainPit = t[index].toInt()
            gCode = t[index + 1].toInt()
            quaEnerMr122 = t[index + 2].toInt()
            quaEner = t[index + 3].toInt()
        }
        gcPred(mode, code)
        val gcode0 = s16(pow2(14, gcFrac))
        var lTmp = lMult(gCode, gcode0)
        lTmp = lShr(lTmp, s16(10 - gcExp))
        two[0] = gainPit
        two[1] = s16(lTmp shr 16)
        gcPredUpdate(quaEnerMr122, quaEner)
    }

    private var gcExp = 0
    private var gcFrac = 0

    /** `gc_pred`, leaving the exponent and fraction of the predicted gain in [gcExp] and [gcFrac]. */
    private fun gcPred(mode: Int, code: IntArray) {
        var enerCode = 0
        for (i in 0 until L_SUBFR) enerCode += (code[i] * code[i]) shr 3
        enerCode = enerCode shl 4
        if ((enerCode shr 31) != 0) enerCode = MAX_32
        if (mode == MR122) {
            enerCode = (pvRound(enerCode) * 26214) shl 1
            val ef = IntArray(2)
            log2(enerCode, ef)
            var lTemp1 = (ef[0] - 30) shl 16
            enerCode = lTemp1 + (ef[1] shl 1)
            var ener = MEAN_ENER_MR122
            for (i in 0 until NPRED) {
                lTemp1 = (pastQuaEnMr122[i] * PRED_MR122[i]) shl 1
                ener = lAdd(ener, lTemp1)
            }
            lTemp1 = lSub(ener, enerCode)
            gcExp = s16(lTemp1 shr 17)
            val lTemp2 = gcExp shl 15
            lTemp1 = lTemp1 shr 2
            gcFrac = s16(lTemp1 - lTemp2)
        } else {
            val expCode = normL(enerCode)
            enerCode = lShl(enerCode, expCode)
            val ef = IntArray(2)
            log2Norm(enerCode, expCode, ef)
            var lTemp2 = (ef[0] * -24660) shl 1
            var lTmp = (ef[1] * -24660) shr 15
            if ((lTmp and 0x00010000) != 0) lTmp = lTmp or 0xffff0000.toInt()
            lTmp = lTmp shl 1
            lTmp = lAdd(lTmp, lTemp2)
            lTemp2 = when (mode) {
                MR795 -> 17062 shl 7
                MR74 -> 32588 shl 6
                MR67 -> 32268 shl 6
                else -> 16678 shl 7
            }
            lTmp = lAdd(lTmp, lTemp2)
            lTmp = when {
                lTmp > 0x001fffff -> MAX_32
                lTmp < -2097152 -> MIN_32
                else -> lTmp shl 10
            }
            for (i in 0 until 4) {
                lTemp2 = (PRED[i] * pastQuaEn[i]) shl 1
                lTmp = lAdd(lTmp, lTemp2)
            }
            val gcode0 = s16(lTmp shr 16)
            lTmp = if (mode == MR74) (gcode0 * 5439) shl 1 else (gcode0 * 5443) shl 1
            lTmp = lTmp shr 8
            gcExp = s16(lTmp shr 16)
            val lTemp1 = lTmp shr 1
            lTemp2 = gcExp shl 15
            gcFrac = s16(lSub(lTemp1, lTemp2))
        }
    }

    private fun gcPredUpdate(quaEnerMr122: Int, quaEner: Int) {
        for (i in 3 downTo 1) {
            pastQuaEn[i] = pastQuaEn[i - 1]
            pastQuaEnMr122[i] = pastQuaEnMr122[i - 1]
        }
        pastQuaEnMr122[0] = quaEnerMr122
        pastQuaEn[0] = quaEner
    }

    private fun gcPredAverageLimited(out: IntArray) {
        var av = 0
        for (i in 0 until NPRED) av = add16(av, pastQuaEnMr122[i])
        av = if (av < 0) s16((av shr 2) or 0xc000) else av shr 2
        if (av < MIN_ENERGY_MR122) av = MIN_ENERGY_MR122
        out[0] = av
        av = 0
        for (i in 0 until NPRED) av = add16(av, pastQuaEn[i])
        av = if (av < 0) s16((av shr 2) or 0xc000) else av shr 2
        if (av < MIN_ENERGY) av = MIN_ENERGY
        out[1] = av
    }

    private fun ecGainCode(state: Int): Int {
        var tmp = gmedN(gbuf, 0, 5)
        if (sub(tmp, pastGainCode) > 0) tmp = pastGainCode
        tmp = mult(tmp, CDOWN[state])
        val av = IntArray(2)
        gcPredAverageLimited(av)
        gcPredUpdate(av[0], av[1])
        return tmp
    }

    private fun ecGainCodeUpdate(bfi: Int, prevBf: Int, gainCodeIn: Int): Int {
        var gainCode = gainCodeIn
        if (bfi == 0) {
            if (prevBf != 0 && sub(gainCode, prevGc) > 0) gainCode = prevGc
            prevGc = gainCode
        }
        pastGainCode = gainCode
        for (i in 1 until 5) gbuf[i - 1] = gbuf[i]
        gbuf[4] = gainCode
        return gainCode
    }

    private fun ecGainPitch(state: Int): Int {
        var tmp = gmedN(pbuf, 0, 5)
        if (sub(tmp, pastGainPit) > 0) tmp = pastGainPit
        return mult(tmp, PDOWN[state])
    }

    private fun ecGainPitchUpdate(bfi: Int, prevBf: Int, gainPitchIn: Int): Int {
        var gainPitch = gainPitchIn
        if (bfi == 0) {
            if (prevBf != 0 && sub(gainPitch, prevGp) > 0) gainPitch = prevGp
            prevGp = gainPitch
        }
        pastGainPit = gainPitch
        if (sub(pastGainPit, 16384) > 0) pastGainPit = 16384
        for (i in 1 until 5) pbuf[i - 1] = pbuf[i]
        pbuf[4] = pastGainPit
        return gainPitch
    }

    // int_lsf.cpp, c_g_aver.cpp, lsp_avg.cpp

    private fun intLsf(lsfOld: IntArray, lsfNew: IntArray, iSubfr: Int, lsfOut: IntArray) {
        when (iSubfr) {
            0 -> for (i in M - 1 downTo 0) lsfOut[i] = add16(s16(lsfOld[i] - (lsfOld[i] shr 2)), lsfNew[i] shr 2)
            40 -> for (i in M - 1 downTo 0) lsfOut[i] = s16((lsfOld[i] shr 1) + (lsfNew[i] shr 1))
            80 -> for (i in M - 1 downTo 0) lsfOut[i] = add16(lsfOld[i] shr 2, s16(lsfNew[i] - (lsfNew[i] shr 2)))
            120 -> for (i in M - 1 downTo 0) lsfOut[i] = lsfNew[i]
        }
    }

    private fun cbGainAverage(
        mode: Int, gainCode: Int, lsp: IntArray, lspAver: IntArray, bfi: Int, prevBf: Int,
        pdfi: Int, prevPdf: Int, inBackgroundNoise: Int, voicedHangover: Int,
    ): Int {
        var cbGainMix = gainCode
        for (i in 0 until L_CBGAINHIST - 1) cbGainHistory[i] = cbGainHistory[i + 1]
        cbGainHistory[L_CBGAINHIST - 1] = gainCode
        var diff = 0
        for (i in 0 until M) {
            var tmp1 = absS(sub(lspAver[i], lsp[i]))
            val shift1 = s16(normS(tmp1) - 1)
            tmp1 = shl(tmp1, shift1)
            val shift2 = normS(lspAver[i])
            val tmp2 = shl(lspAver[i], shift2)
            var t = divS(tmp1, tmp2)
            val shift = s16(2 + shift1 - shift2)
            t = if (shift >= 0) shr(t, shift) else shl(t, negate(shift))
            diff = add16(diff, t)
        }
        hangVar = if (diff > 5325) s16(hangVar + 1) else 0
        if (hangVar > 10) hangCount = 0
        if (mode <= MR67 || mode == MR102) {
            val tmpDiff = if ((((pdfi != 0) && (prevPdf != 0)) || bfi != 0 || prevBf != 0) && voicedHangover > 1 &&
                inBackgroundNoise != 0 && (mode == MR475 || mode == MR515 || mode == MR59)
            ) s16(diff - 4506) else s16(diff - 3277)
            val tmp1 = if (tmpDiff > 0) tmpDiff else 0
            var bgMix = if (2048 < tmp1) 8192 else shl(tmp1, 2)
            if (hangCount < 40 || diff > 5325) bgMix = 8192
            var sum = lMult(6554, cbGainHistory[2])
            for (i in 3 until L_CBGAINHIST) sum = lMac(sum, 6554, cbGainHistory[i])
            var cbGainMean = pvRound(sum)
            if ((bfi != 0 || prevBf != 0) && inBackgroundNoise != 0 && (mode == MR475 || mode == MR515 || mode == MR59)) {
                sum = lMult(4681, cbGainHistory[0])
                for (i in 1 until L_CBGAINHIST) sum = lMac(sum, 4681, cbGainHistory[i])
                cbGainMean = pvRound(sum)
            }
            sum = lMult(bgMix, cbGainMix)
            sum = lMac(sum, 8192, cbGainMean)
            sum = lMsu(sum, bgMix, cbGainMean)
            cbGainMix = pvRound(lShl(sum, 2))
        }
        hangCount = s16(hangCount + 1)
        return cbGainMix
    }

    private fun lspAvg(lsp: IntArray) {
        for (i in 0 until M) {
            var t = lspMeanSave[i] shl 16
            t = lMsu(t, EXPCONST, lspMeanSave[i])
            t = lMac(t, EXPCONST, lsp[i])
            lspMeanSave[i] = pvRound(t)
        }
    }

    // ph_disp.cpp

    private fun phDisp(mode: Int, x: IntArray, cbGain: Int, ltpGain: Int, inno: IntArray, pitchFac: Int, tmpShift: Int) {
        for (i in 4 downTo 1) gainMem[i] = gainMem[i - 1]
        gainMem[0] = ltpGain
        var impNr = if (ltpGain < PHDTHR2LTP) (if (ltpGain > PHDTHR1LTP) 1 else 0) else 2
        var lTemp = (phPrevCbGain * ONFACTPLUS1) shl 1
        lTemp = when {
            lTemp > 0x1fffffff -> MAX_32
            lTemp < -536870912 -> MIN_32
            else -> lTemp shl 2
        }
        val tmp1 = pvRound(lTemp)
        if (cbGain > tmp1) {
            onset = ONLENGTH
        } else if (onset > 0) {
            onset -= 1
        }
        if (onset == 0) {
            var i1 = 0
            for (i in 0 until PHDGAINMEMSIZE) if (gainMem[i] < PHDTHR1LTP) i1 += 1
            if (i1 > 2) impNr = 0
        }
        if (impNr > phPrevState + 1 && onset == 0) impNr -= 1
        if (impNr < 2 && onset > 0) impNr += 1
        if (cbGain < 10) impNr = 2
        if (lockFull == 1) impNr = 0
        phPrevState = impNr
        phPrevCbGain = cbGain
        if (mode != MR122 && mode != MR102 && mode != MR74 && impNr < 2) {
            val innoSav = IntArray(L_SUBFR)
            val psPoss = IntArray(L_SUBFR)
            var nze = 0
            for (i in 0 until L_SUBFR) {
                if (inno[i] != 0) psPoss[nze++] = i
                innoSav[i] = inno[i]
                inno[i] = 0
            }
            val phImp = if (mode == MR795) {
                if (impNr == 0) AmrNbTables.PH_IMP_LOW_MR795 else AmrNbTables.PH_IMP_MID_MR795
            } else {
                if (impNr == 0) AmrNbTables.PH_IMP_LOW else AmrNbTables.PH_IMP_MID
            }
            for (n in 0 until nze) {
                val ppos = psPoss[n]
                val c = innoSav[ppos]
                var k = 0
                for (i in ppos until L_SUBFR) inno[i] = add16(inno[i], s16((c * phImp[k++]) shr 15))
                for (i in 0 until ppos) inno[i] = add16(inno[i], s16((c * phImp[k++]) shr 15))
            }
        }
        for (i in 0 until L_SUBFR) {
            var l = lMult(x[i], pitchFac)
            val l2 = (inno[i] * cbGain) shl 1
            l = lAdd(l, l2)
            l = lShl(l, tmpShift)
            x[i] = pvRound(l)
        }
    }

    // ex_ctrl.cpp, agc.cpp

    private fun exCtrl(excitation: IntArray, excEnergyIn: Int, hist: IntArray, voicedHangover: Int, prevBfi: Int, carefulFlag: Int) {
        var excEnergy = excEnergyIn
        var avgEnergy = gmedN(hist, 0, 9)
        var prevEnergy = s16((hist[7] + hist[8]) shr 1)
        if (hist[8] < prevEnergy) prevEnergy = hist[8]
        if (excEnergy < avgEnergy && excEnergy > 5) {
            var testEnergy = shl(prevEnergy, 2)
            if (voicedHangover < 7 || prevBfi != 0) testEnergy = sub(testEnergy, prevEnergy)
            if (avgEnergy > testEnergy) avgEnergy = testEnergy
            val exp = normS(excEnergy)
            excEnergy = shl(excEnergy, exp)
            excEnergy = divS(16383, excEnergy)
            var t0 = lMult(avgEnergy, excEnergy)
            t0 = lShr(t0, sub(20, exp))
            if (t0 > 32767) t0 = 32767
            var scaleFactor = s16(t0)
            if (carefulFlag != 0 && scaleFactor > 3072) scaleFactor = 3072
            for (i in 0 until L_SUBFR) {
                var t = lMult(scaleFactor, excitation[i])
                t = lShr(t, 11)
                excitation[i] = s16(t)
            }
        }
    }

    private fun energyOld(input: IntArray, at: Int, length: Int): Int {
        var s = 0
        for (i in 0 until length) {
            val temp = input[at + i] shr 2
            s = lMac(s, temp, temp)
        }
        return s
    }

    private fun energyNew(input: IntArray, at: Int, length: Int): Int {
        var s = 0
        for (i in 0 until length) s = lMac(s, input[at + i], input[at + i])
        return if (s != MAX_32) s shr 4 else energyOld(input, at, length)
    }

    private fun agc(sigIn: IntArray, inAt: Int, sigOut: IntArray, outAt: Int, agcFac: Int, length: Int) {
        var s = energyNew(sigOut, outAt, length)
        if (s == 0) {
            pastGain = 0
            return
        }
        var exp = s16(normL(s) - 1)
        var lTemp = lShl(s, exp)
        val gainOut = pvRound(lTemp)
        s = energyNew(sigIn, inAt, length)
        val g0: Int
        if (s == 0) {
            g0 = 0
        } else {
            var i = normL(s)
            lTemp = s shl i
            val gainIn = pvRound(lTemp)
            exp = s16(exp - i)
            val temp = divS(gainOut, gainIn)
            s = temp shl 7
            s = lShr(s, exp)
            s = invSqrt(s)
            lTemp = s shl 9
            i = s16((lTemp + 0x00008000) shr 16)
            g0 = s16((i * (32767 - agcFac)) shr 15)
        }
        var gain = pastGain
        for (i in 0 until length) {
            gain = s16((gain * agcFac) shr 15)
            gain = s16(gain + g0)
            val l = (sigOut[outAt + i] * gain) shl 1
            sigOut[outAt + i] = s16(l shr 13)
        }
        pastGain = gain
    }

    private fun agc2(sigIn: IntArray, sigOut: IntArray, length: Int) {
        var s = energyNew(sigOut, 0, length)
        if (s == 0) return
        var exp = s16(normL(s) - 1)
        var lTemp = lShl(s, exp)
        val gainOut = pvRound(lTemp)
        s = energyNew(sigIn, 0, length)
        val g0: Int
        if (s == 0) {
            g0 = 0
        } else {
            val i = normL(s)
            lTemp = lShl(s, i)
            val gainIn = pvRound(lTemp)
            exp = s16(exp - i)
            val temp = divS(gainOut, gainIn)
            s = temp
            s = when {
                s > 0x00FFFFFF -> MAX_32
                s < -16777216 -> MIN_32
                else -> s shl 7
            }
            s = lShr(s, exp)
            s = invSqrt(s)
            lTemp = when {
                s > 0x003FFFFF -> MAX_32
                s < -4194304 -> MIN_32
                else -> s shl 9
            }
            g0 = pvRound(lTemp)
        }
        for (i in length - 1 downTo 0) {
            val l = lMult(sigOut[i], g0)
            sigOut[i] = when {
                l > 0x0FFFFFFF -> MAX_16
                l < -268435456 -> MIN_16
                else -> s16(l shr 13)
            }
        }
    }

    // syn_filt.cpp, residu.cpp, weight_a.cpp

    /**
     * `Syn_filt`. [x] and [y] may be the same array, as the post filter
     * calls it, so the order of reads and writes is the reference's.
     */
    private fun synFilt(
        a: IntArray, aAt: Int, x: IntArray, xAt: Int, y: IntArray, yAt: Int, lg: Int,
        mem: IntArray, memAt: Int, update: Boolean,
    ) {
        val tmp = IntArray(2 * M)
        for (i in 0 until M) tmp[i] = mem[memAt + i]
        var yy = M
        var px = xAt
        var py = yAt
        var pyy1 = M - 1
        for (n in 0 until (M shr 1)) {
            var pa = aAt
            var s1 = x[px++] * a[pa] + 0x800
            var s2 = x[px++] * a[pa++] + 0x800
            s1 -= a[pa++] * tmp[pyy1]
            for (j in 0 until (M shr 1) - 2) {
                s2 -= a[pa] * tmp[pyy1--]
                s1 -= a[pa++] * tmp[pyy1]
                s2 -= a[pa] * tmp[pyy1--]
                s1 -= a[pa++] * tmp[pyy1]
                s2 -= a[pa] * tmp[pyy1--]
                s1 -= a[pa++] * tmp[pyy1]
            }
            var temp = clip12(s1)
            s2 -= a[aAt + 1] * temp
            tmp[yy++] = temp
            y[py++] = temp
            pyy1 = yy
            temp = clip12(s2)
            tmp[yy++] = temp
            y[py++] = temp
        }
        // The rest reads its history from the output itself.
        pyy1 = yAt + M - 1
        for (n in 0 until ((lg - M) shr 1)) {
            var pa = aAt
            var s1 = x[px++] * a[pa] + 0x800
            var s2 = x[px++] * a[pa++] + 0x800
            s1 -= a[pa++] * y[pyy1]
            for (j in 0 until (M shr 1) - 2) {
                s2 -= a[pa] * y[pyy1--]
                s1 -= a[pa++] * y[pyy1]
                s2 -= a[pa] * y[pyy1--]
                s1 -= a[pa++] * y[pyy1]
                s2 -= a[pa] * y[pyy1--]
                s1 -= a[pa++] * y[pyy1]
            }
            val temp = clip12(s1)
            s2 -= a[aAt + 1] * temp
            y[py++] = temp
            pyy1 = py
            y[py++] = clip12(s2)
        }
        if (update) for (i in 0 until M) mem[memAt + i] = y[yAt + lg - M + i]
    }

    private fun clip12(s: Int): Int = when {
        (s + 134217728).toUInt() < 0x0fffffffu -> s16(s shr 12)
        s > 0x07ffffff -> MAX_16
        else -> MIN_16
    }

    private fun residu(coef: IntArray, input: IntArray, inAt: Int, residual: IntArray, length: Int) {
        var pr = length - 1
        var pin = inAt + length - 1 - M
        for (n in 0 until (length shr 2)) {
            var s1 = 0x800
            var s2 = 0x800
            var s3 = 0x800
            var s4 = 0x800
            var pc = M
            var p1 = pin--
            var p2 = pin--
            var p3 = pin--
            var p4 = pin--
            for (j in 0 until (M shr 1)) {
                s1 += coef[pc] * input[p1++]
                s2 += coef[pc] * input[p2++]
                s3 += coef[pc] * input[p3++]
                s4 += coef[pc--] * input[p4++]
                s1 += coef[pc] * input[p1++]
                s2 += coef[pc] * input[p2++]
                s3 += coef[pc] * input[p3++]
                s4 += coef[pc--] * input[p4++]
            }
            s1 += coef[pc] * input[p1]
            s2 += coef[pc] * input[p2]
            s3 += coef[pc] * input[p3]
            s4 += coef[pc] * input[p4]
            residual[pr--] = s16(s1 shr 12)
            residual[pr--] = s16(s2 shr 12)
            residual[pr--] = s16(s3 shr 12)
            residual[pr--] = s16(s4 shr 12)
        }
    }

    private fun weightAi(a: IntArray, aAt: Int, fac: ShortArray, aExp: IntArray) {
        aExp[0] = a[aAt]
        for (i in 1..M) aExp[i] = s16((a[aAt + i] * fac[i - 1] + 0x00004000) shr 15)
    }

    // pstfilt.cpp, preemph.cpp, post_pro.cpp

    private fun postFilter(mode: Int) {
        val ap3 = IntArray(MP1)
        val ap4 = IntArray(MP1)
        val h = IntArray(L_H)
        val work = M
        synth.copyInto(synthBuf, work, 0, L_FRAME)
        var az = 0
        var iSubfr = 0
        while (iSubfr < L_FRAME) {
            if (mode == MR122 || mode == MR102) {
                weightAi(azDec, az, GAMMA3_MR122, ap3)
                weightAi(azDec, az, GAMMA4_MR122, ap4)
            } else {
                weightAi(azDec, az, GAMMA3, ap3)
                weightAi(azDec, az, GAMMA4, ap4)
            }
            residu(ap3, synthBuf, work + iSubfr, res2, L_SUBFR)
            ap3.copyInto(h, 0, 0, M + 1)
            h.fill(0, M + 1, L_H)
            synFilt(ap4, 0, h, 0, h, 0, L_H, h, M + 1, false)
            var lTmp = 0
            for (i in L_H - 1 downTo 0) {
                var l2 = h[i] * h[i]
                if (l2 != 0x40000000) {
                    l2 = l2 shl 1
                } else {
                    break
                }
                lTmp = lAdd(lTmp, l2)
            }
            val temp1 = s16(lTmp shr 16)
            lTmp = 0
            for (i in L_H - 2 downTo 0) {
                var l2 = h[i] * h[i + 1]
                if (l2 != 0x40000000) {
                    l2 = l2 shl 1
                } else {
                    break
                }
                lTmp = lAdd(lTmp, l2)
            }
            var temp2 = s16(lTmp shr 16)
            if (temp2 <= 0) {
                temp2 = 0
            } else {
                var l = (temp2 * MU) shr 15
                if ((l and 0x00010000) != 0) l = l or 0xffff0000.toInt()
                temp2 = s16(l)
                temp2 = divS(temp2, temp1)
            }
            preemphasis(res2, temp2, L_SUBFR)
            synFilt(ap4, 0, res2, 0, synth, iSubfr, L_SUBFR, memSynPst, 0, true)
            agc(synthBuf, work + iSubfr, synth, iSubfr, AGC_FAC, L_SUBFR)
            az += MP1
            iSubfr += L_SUBFR
        }
        synthBuf.copyInto(synthBuf, 0, work + L_FRAME - M, work + L_FRAME)
    }

    private fun preemphasis(signal: IntArray, g: Int, length: Int) {
        var p1 = length - 1
        var p2 = p1 - 1
        val temp = signal[p1]
        for (i in 0..length - 2) {
            val temp2 = mult(g, signal[p2--])
            signal[p1] = sub(signal[p1], temp2)
            p1--
        }
        val temp2 = mult(g, memPre)
        signal[p1] = sub(signal[p1], temp2)
        memPre = temp
    }

    private fun postProcess() {
        for (i in 0 until L_FRAME) {
            val x2 = x1
            x1 = x0
            x0 = synth[i]
            var lTmp = y1Hi * POST_A1
            lTmp += (y1Lo * POST_A1) shr 15
            lTmp += y2Hi * POST_A2
            lTmp += (y2Lo * POST_A2) shr 15
            lTmp += x0 * POST_B0
            lTmp += x1 * POST_B1
            lTmp += x2 * POST_B2
            lTmp = lShl(lTmp, 3)
            synth[i] = pvRound(lShl(lTmp, 1))
            y2Hi = y1Hi
            y2Lo = y1Lo
            y1Hi = s16(lTmp shr 16)
            y1Lo = s16((lTmp shr 1) - (y1Hi shl 15))
        }
    }

    // bgnscd.cpp

    private fun bgnScd(speech: IntArray): Int {
        var s = 0
        for (i in L_FRAME - 1 downTo 0) {
            var l = speech[i] * speech[i]
            l = if (l != 0x40000000) l shl 1 else MAX_32
            s = lAdd(s, l)
        }
        val currEnergy = if (s > 0x1fffffff) MAX_16 else s16(s shr 14)
        var frameEnergyMin = 32767
        for (i in L_ENERGYHIST - 1 downTo 0) if (frameEnergyHist[i] < frameEnergyMin) frameEnergyMin = frameEnergyHist[i]
        val l = frameEnergyMin shl 4
        val noiseFloor = if (l != s16(l)) (if (l > 0) MAX_16 else MIN_16) else l
        var maxEnergy = frameEnergyHist[0]
        for (i in L_ENERGYHIST - 5 downTo 1) if (maxEnergy < frameEnergyHist[i]) maxEnergy = frameEnergyHist[i]
        var maxEnergyLastPart = frameEnergyHist[2 * L_ENERGYHIST / 3]
        for (i in 2 * L_ENERGYHIST / 3 + 1 until L_ENERGYHIST) {
            if (maxEnergyLastPart < frameEnergyHist[i]) maxEnergyLastPart = frameEnergyHist[i]
        }
        if (maxEnergy > LOWERNOISELIMIT && currEnergy < FRAMEENERGYLIMIT && currEnergy > LOWERNOISELIMIT &&
            (currEnergy < noiseFloor || maxEnergyLastPart < UPPERNOISELIMIT)
        ) {
            bgHangover = if (bgHangover + 1 > 30) 30 else bgHangover + 1
        } else {
            bgHangover = 0
        }
        val inbgNoise = if (bgHangover > 1) 1 else 0
        for (i in 0 until L_ENERGYHIST - 1) frameEnergyHist[i] = frameEnergyHist[i + 1]
        frameEnergyHist[L_ENERGYHIST - 1] = currEnergy
        val ltpLimit = when {
            bgHangover > 15 -> 16383
            bgHangover > 8 -> 15565
            else -> 13926
        }
        var prevVoiced = gmedN(ltpGainHistory, 4, 5) > ltpLimit
        if (bgHangover > 20) prevVoiced = gmedN(ltpGainHistory, 0, 9) > ltpLimit
        if (prevVoiced) {
            voicedHangover = 0
        } else {
            val temp = voicedHangover + 1
            voicedHangover = if (temp > 10) 10 else temp
        }
        return inbgNoise
    }

    // b_cn_cod.cpp

    private fun pseudonoise(noBits: Int): Int {
        var noiseBits = 0
        for (i in 0 until noBits) {
            var sn = if ((pnSeedRx and 1) != 0) 1 else 0
            if ((pnSeedRx and 0x10000000) != 0) sn = sn xor 1
            noiseBits = (noiseBits shl 1) or (pnSeedRx and 1)
            pnSeedRx = pnSeedRx shr 1
            if ((sn and 1) != 0) pnSeedRx = pnSeedRx or 0x40000000
        }
        return noiseBits
    }

    private fun buildCnCode(cod: IntArray) {
        cod.fill(0)
        for (k in 0 until 10) {
            var i = pseudonoise(2)
            val temp = s16(lMult(i, 10))
            i = temp shr 1
            i = add16(i, k)
            val j = pseudonoise(1)
            cod[i] = if (j > 0) 4096 else -4096
        }
    }

    private fun buildCnParam(nParam: Int, sizes: ShortArray) {
        var l = lMult(nodataSeed, 31821)
        l = l shr 1
        nodataSeed = s16(lAdd(l, 13849))
        var p = nodataSeed and 0x7F
        val window = AmrNbTables.WINDOW_200_40
        for (i in 0 until nParam) {
            val mask = s16((0xFFFF shl sizes[i].toInt()).inv())
            parm[i] = s16(window[p++].toInt() and mask)
        }
    }

    // dtx_dec.cpp

    private fun dtxDecReset() {
        sinceLastSid = 0
        trueSidPeriodInv = 1 shl 13
        logEn = 3500
        oldLogEn = 3500
        pnSeedRx = PN_INITIAL_SEED
        LSP_INIT.copyInto(dtxLsp)
        LSP_INIT.copyInto(dtxLspOld)
        lsfHistPtr = 0
        logPgMean = 0
        logEnHistPtr = 0
        for (i in 0 until DTX_HIST_SIZE) LSF_HIST_INIT.copyInto(lsfHist, i * M)
        lsfHistMean.fill(0)
        logEnHist.fill(logEn)
        logEnAdjust = 0
        dtxHangoverCount = DTX_HANG_CONST
        decAnaElapsedCount = 32767
        sidFrame = 0
        validData = 0
        dtxHangoverAdded = 0
        dtxGlobalState = DTX
        dataUpdated = 0
    }

    private fun dtxDec(newState: Int, mode: Int) {
        if (dtxHangoverAdded != 0 && sidFrame != 0) {
            logEnAdjust = DTX_LOG_EN_ADJUST[mode]
            var ptr = lsfHistPtr + M
            if (ptr == 80) ptr = 0
            lsfHist.copyInto(lsfHist, ptr, lsfHistPtr, lsfHistPtr + M)
            ptr = logEnHistPtr + 1
            if (ptr == DTX_HIST_SIZE) ptr = 0
            logEnHist[ptr] = logEnHist[logEnHistPtr]
            logEn = 0
            val lLsf = IntArray(M)
            for (i in DTX_HIST_SIZE - 1 downTo 0) {
                logEn = add16(logEn, logEnHist[i] shr 3)
                for (j in M - 1 downTo 0) lLsf[j] = lAdd(lLsf[j], lsfHist[i * M + j])
            }
            val lsf = IntArray(M)
            for (j in M - 1 downTo 0) lsf[j] = s16(lLsf[j] shr 3)
            lsfLsp(lsf, 0, dtxLsp)
            logEn = sub(logEn, logEnAdjust)
            lsfHist.copyInto(lsfHistMean, 0, 0, 80)
            for (i in M - 1 downTo 0) {
                var lMean = 0
                for (j in 7 downTo 0) lMean = lAdd(lMean, lsfHistMean[i + j * M])
                val lsfMean = s16(lMean shr 3)
                for (j in 7 downTo 0) {
                    val at = i + j * M
                    lsfHistMean[at] = sub(lsfHistMean[at], lsfMean)
                    lsfHistMean[at] = mult(lsfHistMean[at], LSF_HIST_MEAN_SCALE[i])
                    val negative = lsfHistMean[at] < 0
                    lsfHistMean[at] = absS(lsfHistMean[at])
                    if (lsfHistMean[at] > 655) lsfHistMean[at] = 655 + ((lsfHistMean[at] - 655) shr 2)
                    if (lsfHistMean[at] > 1310) lsfHistMean[at] = 1310
                    if (negative) lsfHistMean[at] = -lsfHistMean[at]
                }
            }
        }
        if (sidFrame != 0) {
            dtxLsp.copyInto(dtxLspOld)
            oldLogEn = logEn
            if (validData != 0) {
                var tmpIntLength = sinceLastSid
                sinceLastSid = 0
                if (tmpIntLength >= 32) tmpIntLength = 32
                val temp = tmpIntLength shl 10
                trueSidPeriodInv = if (tmpIntLength >= 2) divS(1 shl 10, temp) else 1 shl 14
                for (i in 0 until M) pastRq[i] = AmrNbTables.PAST_RQ_INIT[parm[0] * M + i].toInt()
                dPlsf3(MRDTX, 0, parm, 1, dtxLsp)
                pastRq.fill(0)
                val logEnIndex = parm[4]
                logEn = if (logEnIndex > 63 || logEnIndex < -64) {
                    if (logEnIndex > 0) MAX_16 else MIN_16
                } else {
                    s16(logEnIndex shl (11 - 2))
                }
                logEn = s16(logEn - 2560 * 2)
                if (logEnIndex == 0) logEn = MIN_16
                if (dataUpdated == 0 || dtxGlobalState == SPEECH) {
                    dtxLsp.copyInto(dtxLspOld)
                    oldLogEn = logEn
                }
            }
            var maPredInit = s16((logEn shr 1) - 9000)
            if (maPredInit > 0) {
                maPredInit = 0
            } else if (maPredInit < -14436) {
                maPredInit = -14436
            }
            pastQuaEn.fill(maPredInit)
            maPredInit = s16((maPredInit * 5443) shr 15)
            pastQuaEnMr122.fill(maPredInit)
        }
        var temp = s16(((DTX_LOG_EN_ADJUST[mode] shl 5) * 3277) shr 15)
        temp = temp shr 5
        logEnAdjust = add16(s16((logEnAdjust * 29491) shr 15), temp)
        var intFac = shl(s16(sinceLastSid + 1), 10)
        intFac = mult(intFac, trueSidPeriodInv)
        intFac = when {
            intFac > 1024 -> 16384
            intFac < -2048 -> MIN_16
            else -> s16(intFac shl 4)
        }
        var lLogEnInt = lMult(intFac, logEn)
        val lspInt = IntArray(M)
        for (i in M - 1 downTo 0) lspInt[i] = mult(intFac, dtxLsp[i])
        intFac = s16(16384 - intFac)
        lLogEnInt = lMac(lLogEnInt, intFac, oldLogEn)
        for (i in M - 1 downTo 0) {
            lspInt[i] = add16(lspInt[i], mult(intFac, dtxLspOld[i]))
            val l = lspInt[i] shl 1
            lspInt[i] = if (l != s16(l)) (if (lspInt[i] > 0) MAX_16 else MIN_16) else l
        }
        var lsfVariabFactor = s16(logPgMean - 2457)
        lsfVariabFactor = s16(4096 - mult(lsfVariabFactor, 9830))
        lsfVariabFactor = when {
            lsfVariabFactor > 4095 -> MAX_16
            lsfVariabFactor < 0 -> 0
            else -> s16(lsfVariabFactor shl 3)
        }
        val lsfVariabIndex = pseudonoise(3)
        val lsfInt = IntArray(M)
        lspLsf(lspInt, lsfInt)
        val lsfIntVariab = lsfInt.copyOf()
        for (i in M - 1 downTo 0) {
            lsfIntVariab[i] = add16(lsfIntVariab[i], mult(lsfVariabFactor, lsfHistMean[i + lsfVariabIndex * M]))
        }
        reorderLsf(lsfInt, LSF_GAP)
        reorderLsf(lsfIntVariab, LSF_GAP)
        lsfInt.copyInto(pastLsfQ)
        val lspIntVariab = IntArray(M)
        lsfLsp(lsfInt, 0, lspInt)
        lsfLsp(lsfIntVariab, 0, lspIntVariab)
        val acoeff = IntArray(M + 1)
        val acoeffVariab = IntArray(M + 1)
        lspAz(lspInt, acoeff, 0)
        lspAz(lspIntVariab, acoeffVariab, 0)
        for (k in 0 until 4) acoeff.copyInto(azDec, k * (M + 1))
        val refl = IntArray(M)
        aRefl(acoeff, 1, refl)
        var predErr = MAX_16
        for (i in 0 until M) {
            val l = (refl[i] * refl[i]) shr 15
            val t = if (l <= 0x00007fff) s16(MAX_16 - l) else 0
            predErr = mult(predErr, t)
        }
        val ef = IntArray(2)
        log2(predErr, ef)
        var logPg = shl(s16(ef[0] - 15), 12)
        logPg = shr(sub(0, add16(logPg, shr(ef[1], 15 - 12))), 1)
        logPgMean = add16(mult(29491, logPgMean), mult(3277, logPg))
        lLogEnInt = lShr(lLogEnInt, 10)
        lLogEnInt = lAdd(lLogEnInt, 4 * 65536)
        lLogEnInt = lSub(lLogEnInt, lShl(logPg, 4))
        lLogEnInt = lAdd(lLogEnInt, lShl(logEnAdjust, 5))
        val logEnIntE = s16(lLogEnInt shr 16)
        val logEnIntM = s16(lShr(lSub(lLogEnInt, logEnIntE shl 16), 1))
        val level = s16(pow2(logEnIntE, logEnIntM))
        val ex = IntArray(L_SUBFR)
        for (i in 0 until 4) {
            buildCnCode(ex)
            for (j in L_SUBFR - 1 downTo 0) ex[j] = mult(level, ex[j])
            synFilt(acoeffVariab, 0, ex, 0, synth, i * L_SUBFR, L_SUBFR, memSyn, 0, true)
        }
        hangVar = 20
        hangCount = 0
        if (newState == DTX_MUTE) {
            var tmpIntLength = sinceLastSid
            if (tmpIntLength > 32) {
                tmpIntLength = 32
            } else if (tmpIntLength <= 0) {
                tmpIntLength = 8
            }
            trueSidPeriodInv = divS(1 shl 10, tmpIntLength shl 10)
            sinceLastSid = 0
            dtxLsp.copyInto(dtxLspOld)
            oldLogEn = logEn
            logEn = s16(logEn - 256)
            if (logEn < 0) logEn = 0
        }
        if (sidFrame != 0 && (validData != 0 || (validData == 0 && dtxHangoverAdded != 0))) {
            sinceLastSid = 0
            dataUpdated = 1
        }
    }

    private fun dtxDecActivityUpdate(lsf: IntArray, frame: IntArray) {
        lsfHistPtr += M
        if (lsfHistPtr == 80) lsfHistPtr = 0
        lsf.copyInto(lsfHist, lsfHistPtr, 0, M)
        var frameEn = 0
        for (i in L_FRAME - 1 downTo 0) {
            var l = frame[i] * frame[i]
            l = if (l != 0x40000000) l shl 1 else MAX_32
            frameEn = lAdd(frameEn, l)
        }
        val ef = IntArray(2)
        log2(frameEn, ef)
        val l = ef[0] shl 10
        val logEnE = if (l != s16(l)) (if (ef[0] > 0) MAX_16 else MIN_16) else l
        val logEnM = ef[1] shr 5
        var logEnValue = s16(logEnE + logEnM)
        logEnValue = s16(logEnValue - (7497 + 1024))
        logEnHistPtr += 1
        if (logEnHistPtr == DTX_HIST_SIZE) logEnHistPtr = 0
        logEnHist[logEnHistPtr] = logEnValue
    }

    private fun rxDtxHandler(frameType: Int): Int {
        var newState: Int
        if (frameType == RX_SID_FIRST || frameType == RX_SID_UPDATE || frameType == RX_SID_BAD ||
            ((dtxGlobalState == DTX || dtxGlobalState == DTX_MUTE) &&
                (frameType == RX_NO_DATA || frameType == RX_SPEECH_BAD || frameType == RX_ONSET))
        ) {
            newState = DTX
            if (dtxGlobalState == DTX_MUTE &&
                (frameType == RX_SID_BAD || frameType == RX_SID_FIRST || frameType == RX_ONSET || frameType == RX_NO_DATA)
            ) {
                newState = DTX_MUTE
            }
            sinceLastSid = s16(sinceLastSid + 1)
            if (frameType != RX_SID_UPDATE && sinceLastSid > DTX_MAX_EMPTY_THRESH) newState = DTX_MUTE
        } else {
            newState = SPEECH
            sinceLastSid = 0
        }
        if (dataUpdated == 0 && frameType == RX_SID_UPDATE) decAnaElapsedCount = 0
        decAnaElapsedCount = add16(decAnaElapsedCount, 1)
        dtxHangoverAdded = 0
        val encState = if (frameType == RX_SID_FIRST || frameType == RX_SID_UPDATE || frameType == RX_SID_BAD ||
            frameType == RX_ONSET || frameType == RX_NO_DATA
        ) {
            if (frameType == RX_NO_DATA && newState == SPEECH) SPEECH else DTX
        } else {
            SPEECH
        }
        if (encState == SPEECH) {
            dtxHangoverCount = DTX_HANG_CONST
        } else if (decAnaElapsedCount > DTX_ELAPSED_FRAMES_THRESH) {
            dtxHangoverAdded = 1
            decAnaElapsedCount = 0
            dtxHangoverCount = 0
        } else if (dtxHangoverCount == 0) {
            decAnaElapsedCount = 0
        } else {
            dtxHangoverCount -= 1
        }
        if (newState != SPEECH) {
            sidFrame = 0
            validData = 0
            when (frameType) {
                RX_SID_FIRST -> sidFrame = 1
                RX_SID_UPDATE -> {
                    sidFrame = 1
                    validData = 1
                }
                RX_SID_BAD -> {
                    sidFrame = 1
                    dtxHangoverAdded = 0
                }
            }
        }
        return newState
    }

    // a_refl.cpp

    private fun aRefl(a: IntArray, at: Int, refl: IntArray) {
        val aState = IntArray(M)
        val bState = IntArray(M)
        for (i in 0 until M) aState[i] = a[at + i]
        for (i in M - 1 downTo 0) {
            if (absS(aState[i]) >= 4096) {
                refl.fill(0)
                break
            }
            refl[i] = shl(aState[i], 3)
            var lTemp = lMult(refl[i], refl[i])
            var lAcc = lSub(MAX_32, lTemp)
            val normShift = normL(lAcc)
            val scale = 15 - normShift
            lAcc = lShl(lAcc, normShift)
            val normProd = pvRound(lAcc)
            val m = divS(16384, normProd)
            for (j in 0 until i) {
                lAcc = aState[j] shl 16
                lAcc = lMsu(lAcc, refl[i], aState[i - j - 1])
                val temp = pvRound(lAcc)
                lTemp = lMult(m, temp)
                lTemp = lShrR(lTemp, scale)
                var abs = lTemp - if (lTemp < 0) 1 else 0
                abs = abs xor (abs shr 31)
                if (abs > 32767) {
                    refl.fill(0)
                    break
                }
                bState[j] = s16(lTemp)
            }
            for (j in 0 until i) aState[j] = bState[j]
        }
    }

    companion object {
        const val MR475 = 0
        const val MR515 = 1
        const val MR59 = 2
        const val MR67 = 3
        const val MR74 = 4
        const val MR795 = 5
        const val MR102 = 6
        const val MR122 = 7
        const val MRDTX = 8

        const val AMR_122 = 7
        const val AMR_SID = 8
        const val AMR_NO_DATA = 15

        private const val RX_SPEECH_GOOD = 0
        private const val RX_SPEECH_DEGRADED = 1
        private const val RX_ONSET = 2
        private const val RX_SPEECH_BAD = 3
        private const val RX_SID_FIRST = 4
        private const val RX_SID_UPDATE = 5
        private const val RX_SID_BAD = 6
        private const val RX_NO_DATA = 7

        private const val SPEECH = 0
        private const val DTX = 1
        private const val DTX_MUTE = 2

        const val L_FRAME = 160
        private const val L_FRAME_BY2 = 80
        private const val L_SUBFR = 40
        private const val M = 10
        private const val MP1 = M + 1
        private const val AZ_SIZE = 4 * M + 4
        private const val PIT_MIN = 20
        private const val PIT_MIN_MR122 = 18
        private const val PIT_MAX = 143
        private const val L_INTERPOL = 10 + 1
        private const val L_INTER10 = L_INTERPOL - 1
        private const val UP_SAMP_MAX = 6
        private const val MAX_PRM_SIZE = 57
        private const val MAX_SERIAL_SIZE = 244
        private const val LSF_GAP = 205
        private const val LSP_PRED_FAC_MR122 = 21299
        private const val SHARPMAX = 13017
        private const val SHARPMIN = 0
        private const val MU = 26214
        private const val AGC_FAC = 29491
        private const val L_H = 22
        private const val NB_TRACK = 5
        private const val NB_TRACK_MR102 = 4
        private const val DICO1_SIZE = 256
        private const val DICO2_SIZE = 512
        private const val DICO3_SIZE = 512
        private const val MR515_3_SIZE = 128
        private const val MR795_1_SIZE = 512
        private const val MR475_VQ_SIZE = 256
        private const val NPRED = 4
        private const val MEAN_ENER_MR122 = 783741
        private const val MIN_ENERGY = -14336
        private const val MIN_ENERGY_MR122 = -2381
        private const val EXC_ENERGY_HIST_LEN = 9
        private const val LTP_GAIN_HISTORY_LEN = 9
        private const val L_CBGAINHIST = 7
        private const val EXPCONST = 5243
        private const val L_ENERGYHIST = 60
        private const val LOWERNOISELIMIT = 20
        private const val UPPERNOISELIMIT = 1953
        private const val FRAMEENERGYLIMIT = 17578
        private const val PHDGAINMEMSIZE = 5
        private const val PHDTHR1LTP = 9830
        private const val PHDTHR2LTP = 14746
        private const val ONFACTPLUS1 = 16384
        private const val ONLENGTH = 2
        private const val DTX_HIST_SIZE = 8
        private const val DTX_HANG_CONST = 7
        private const val DTX_MAX_EMPTY_THRESH = 50
        private const val DTX_ELAPSED_FRAMES_THRESH = 24 + 7 - 1
        private const val PN_INITIAL_SEED = 0x70816958
        private const val AMRSID_RXTYPE_BIT_OFFSET = 35
        private const val AMRSID_RXMODE_BIT_OFFSET = 36
        private const val NUM_AMRSID_RXMODE_BITS = 3
        private const val POST_A1 = 15836
        private const val POST_A2 = -7667
        private const val POST_B0 = 7699
        private const val POST_B1 = -15398
        private const val POST_B2 = 7699

        private val LSP_INIT = intArrayOf(30000, 26000, 21000, 15000, 8000, 0, -8000, -15000, -21000, -26000)
        private val LSF_HIST_INIT = intArrayOf(1384, 2077, 3420, 5108, 6742, 8122, 9863, 11092, 12714, 13701)
        private val LSF_HIST_MEAN_SCALE = intArrayOf(20000, 20000, 20000, 20000, 20000, 18000, 16384, 8192, 0, 0)
        private val DTX_LOG_EN_ADJUST = intArrayOf(-1023, -878, -732, -586, -440, -294, -148, 0, 0)
        private val PRED = intArrayOf(5571, 4751, 2785, 1556)
        private val PRED_MR122 = intArrayOf(44, 37, 22, 12)
        private val CDOWN = intArrayOf(32767, 32112, 32112, 32112, 32112, 32112, 22937)
        private val PDOWN = intArrayOf(32767, 32112, 32112, 26214, 9830, 6553, 6553)
        private val GAMMA3_MR122 = shortArrayOf(22938, 16057, 11240, 7868, 5508, 3856, 2699, 1889, 1322, 925)
        private val GAMMA3 = shortArrayOf(18022, 9912, 5451, 2998, 1649, 907, 499, 274, 151, 83)
        private val GAMMA4_MR122 = shortArrayOf(24576, 18432, 13824, 10368, 7776, 5832, 4374, 3281, 2461, 1846)
        private val GAMMA4 = shortArrayOf(22938, 16057, 11240, 7868, 5508, 3856, 2699, 1889, 1322, 925)

        private val BITNO: Array<ShortArray> = arrayOf(
            AmrNbFrameTables.BITNO_MR475, AmrNbFrameTables.BITNO_MR515, AmrNbFrameTables.BITNO_MR59,
            AmrNbFrameTables.BITNO_MR67, AmrNbFrameTables.BITNO_MR74, AmrNbFrameTables.BITNO_MR795,
            AmrNbFrameTables.BITNO_MR102, AmrNbFrameTables.BITNO_MR122, AmrNbFrameTables.BITNO_MRDTX,
        )
        private val REORDER: Array<ShortArray> = arrayOf(
            AmrNbFrameTables.REORDER_MR475, AmrNbFrameTables.REORDER_MR515, AmrNbFrameTables.REORDER_MR59,
            AmrNbFrameTables.REORDER_MR67, AmrNbFrameTables.REORDER_MR74, AmrNbFrameTables.REORDER_MR795,
            AmrNbFrameTables.REORDER_MR102, AmrNbFrameTables.REORDER_MR122,
        )
    }
}

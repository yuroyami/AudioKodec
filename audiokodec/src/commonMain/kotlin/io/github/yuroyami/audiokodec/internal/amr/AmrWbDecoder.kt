/*
 * Translated from opencore-amr 0.1.6, Copyright 1998-2009 PacketVideo and
 * licensed under the Apache License 2.0, with portions from the 3GPP AMR-WB
 * reference code (3GPP TS 26.173) distributed under it. See NOTICE and
 * reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.amr

import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.MAX_16
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.addInt16
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.addInt32
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.div16by16
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.dotProduct12
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.log2
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.mac16
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.mpyDpf32
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.msu16
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.mul16
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.mul32by16
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.mul32by16b
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.multInt16
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.multInt16R
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.negateInt16
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.noiseGen
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.normS
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.normalize
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.oneOvSqrt
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.oneOvSqrtNorm
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.powerOf2
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.round
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.s16
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.shl1Round
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.shlInt16
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.shlInt32
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.shrInt32
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.shrRnd
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.subInt16
import io.github.yuroyami.audiokodec.internal.amr.AmrWbOps.subInt32

/**
 * Decodes AMR wideband frames as opencore-amr's `D_IF_decode` does: 320
 * samples at 16 kHz per frame, as 14-bit integers in 16-bit words, with the
 * decoder homing frames of 3GPP TS 26.173 recognised and acted on.
 *
 * Each frame comes in the storage format of RFC 4867 section 5. As in the
 * reference, the header's quality bit is not consulted; a frame of type 14
 * is a lost frame and 15 carries no data, and the decoder conceals or
 * continues comfort noise in their place.
 */
internal class AmrWbDecoder {

    // The wrapper's state
    private var mode = 0
    private var modeOld = 0
    private var frameType = 0
    private var resetFlag = 0
    private var resetFlagOld = 1
    private var rxPrevMode = 0
    private val prms = IntArray(KAMRWB_NB_BITS_MAX)
    private var p = 0

    // Decoder_State
    private val oldExcState = IntArray(PIT_MAX + L_INTERPOL)
    private val ispold = IntArray(M)
    private val isfold = IntArray(M)
    private val isfBuf = IntArray(L_MEANBUF * M)
    private val pastIsfq = IntArray(M)
    private var tiltCode = 0
    private var qOld = 0
    private val qSubfr = IntArray(4)
    private var lGcThres = 0
    private val memSynHi = IntArray(M)
    private val memSynLo = IntArray(M)
    private var memDeemph = 0
    private val memSigOut = IntArray(6)
    private val memOversamp = IntArray(2 * L_FILT)
    private val memSynHf = IntArray(M16K)
    private val memHf = IntArray(2 * L_FILT16K)
    private val memHf3 = IntArray(2 * L_FILT16K)
    /** `seed`, `seed2` and `seed3`, in an array so the noise generator can advance each. */
    private val seeds = IntArray(3)
    private var oldT0 = 0
    private var oldT0Frac = 0
    private val lagHist = IntArray(5)
    private val decGain = IntArray(23)
    private val dispMem = IntArray(8)
    private val memHp400 = IntArray(6)
    private var prevBfi = 0
    private var state = 0
    private var firstFrame = 0
    private var vadHist = 0

    // dtx_decState
    private var sinceLastSid = 0
    private var trueSidPeriodInv = 0
    private var logEn = 0
    private var oldLogEn = 0
    private val dtxIsf = IntArray(M)
    private val dtxIsfOld = IntArray(M)
    /** `cng_seed` and `dither_seed`. */
    private val dtxSeeds = IntArray(2)
    private val isfHist = IntArray(M * DTX_HIST_SIZE)
    private val logEnHist = IntArray(DTX_HIST_SIZE)
    private var histPtr = 0
    private var dtxHangoverCount = 0
    private var decAnaElapsedCount = 0
    private var sidFrame = 0
    private var validData = 0
    private var dtxHangoverAdded = 0
    private var dtxGlobalState = SPEECH
    private var dataUpdated = 0
    private var cnDith = 0

    // Working memory, which the reference keeps in its scratch area.
    private val oldExc = IntArray((L_FRAME + 1) + PIT_MAX + L_INTERPOL)
    private val aq = IntArray(NB_SUBFR * (M + 1))
    private val ispnew = IntArray(M)
    private val isf = IntArray(M)
    private val isfTmp = IntArray(M)
    private val code = IntArray(L_SUBFR)
    private val excp = IntArray(L_SUBFR)
    private val exc2 = IntArray(L_FRAME)
    private val hfIsf = IntArray(M16K)
    private val ind = IntArray(8)
    private val synth = IntArray(AMR_WB_PCM_FRAME)
    private val synthHi = IntArray(M + L_SUBFR)
    private val synthLo = IntArray(M + L_SUBFR)
    private val synth12k8 = IntArray(L_SUBFR)
    private val hf = IntArray(L_SUBFR16K)
    private val ap = IntArray(M16K + 1)
    private val hfA = IntArray(M16K + 1)
    private val scratch = IntArray(2 * L_SUBFR + 2 * NB_COEF_UP + M16K)
    private val two = IntArray(2)

    init {
        dtxReset()
        reset(true)
    }

    /**
     * Decodes the frame at [offset] in [frame], header byte first, into 320
     * samples in [out] from [outOffset], and returns how many bytes the
     * frame takes, header included.
     */
    fun decode(frame: ByteArray, offset: Int, out: ShortArray, outOffset: Int): Int {
        mode = (frame[offset].toInt() shr 3) and 0x0f
        val size = AmrWbFrameTables.PACKED_SIZE[mode].toInt()
        mimeUnsorting(frame, offset + 1)
        if (frameType == RX_NO_DATA || frameType == RX_SPEECH_LOST) {
            mode = modeOld
            resetFlag = 0
        } else {
            modeOld = mode
            if (resetFlagOld == 1) resetFlag = homingFrameTest(mode, AmrWbFrameTables.PRMNOFSF)
        }
        if (resetFlag != 0 && resetFlagOld != 0) {
            synth.fill(EHF_MASK)
        } else {
            decodeFrame(mode)
        }
        for (i in 0 until AMR_WB_PCM_FRAME) out[outOffset + i] = (synth[i] and 0xfffC).toShort()
        if (resetFlagOld == 0) resetFlag = homingFrameTest(mode, null)
        if (resetFlag != 0) reset(true)
        resetFlagOld = resetFlag
        return size + 1
    }

    // mime_io.cpp

    private fun mimeUnsorting(frame: ByteArray, at: Int) {
        val size = AmrWbFrameTables.UNPACKED_SIZE[mode].toInt()
        val sort = SORTING[mode]
        prms.fill(0, 0, size)
        for (i in 0 until size) {
            val bit = (frame[at + (i shr 3)].toInt() shr (7 - (i and 7))) and 1
            if (bit != 0) prms[sort!![i].toInt()] = 1
        }
        // The reference goes on to read the bit after the last, which in a SID frame says which kind it is.
        val last = at + (size shr 3)
        val temp = (if (last < frame.size) frame[last].toInt() else 0) shl (size and 7)
        when (mode) {
            in MODE_7K..MODE_24K -> frameType = RX_SPEECH_GOOD
            MRSID -> {
                frameType = if ((temp and 0x80) != 0) RX_SID_UPDATE else RX_SID_FIRST
                mode = rxPrevMode
            }
            14 -> {
                frameType = RX_SPEECH_LOST
                mode = rxPrevMode
            }
            else -> {
                frameType = RX_NO_DATA
                mode = rxPrevMode
            }
        }
        rxPrevMode = mode
    }

    // homing_amr_wb_dec.cpp: `pvDecoder_AmrWb_homing_frame_test`, or its `_first` with [counts].

    private fun homingFrameTest(mode: Int, counts: ShortArray?): Int {
        if (mode == MRDTX) return 0
        val nparms = counts?.get(mode)?.toInt() ?: COMPRESSED[mode]
        val dhf = DHF[if (mode > MODE_24K) MODE_24K else mode]
        val param = IntArray(DHF_PARMS_MAX)
        p = 0
        var j = 0
        var i = 0
        val shift: Int
        if (mode != MODE_24K) {
            val limit = nparms - 15
            while (limit > j) {
                param[i] = serialParm(15)
                j += 15
                i++
            }
            val tmp = nparms - j
            param[i] = serialParm(tmp)
            shift = 15 - tmp
            param[i] = shlInt16(param[i], shift)
        } else {
            for (k in 0 until 10) param[k] = serialParm(15)
            param[10] = serialParm(15) and 0x61FF
            for (k in 11 until 17) param[k] = serialParm(15)
            param[17] = serialParm(15) and 0xE0FF
            for (k in 18 until 24) param[k] = serialParm(15)
            param[24] = serialParm(15) and 0x7F0F
            for (k in 25 until 31) param[k] = serialParm(15)
            param[31] = shlInt16(serialParm(8), 7)
            i = 31
            shift = 0
        }
        val count = i
        j = 0
        i = 0
        while (i < count) {
            j = param[i] xor dhf[i].toInt()
            if (j != 0) break
            i++
        }
        var tmp = 0x7fff shr shift
        tmp = shlInt16(tmp, shift)
        tmp = dhf[i].toInt() and tmp
        tmp = param[i] xor tmp
        j = s16(j or tmp)
        return if (j == 0) 1 else 0
    }

    private fun serialParm(bits: Int): Int {
        var value = 0
        for (i in 0 until (bits shr 1)) {
            value = s16(value shl 2)
            if (prms[p++] == 1) value = value or 2
            if (prms[p++] == 1) value = value or 1
        }
        if ((bits and 1) != 0) {
            value = s16(value shl 1)
            if (prms[p++] == 1) value = value or 1
        }
        return value
    }

    private fun serialParm1() = if (prms[p++] == 1) 1 else 0

    // pvamrwbdecoder.cpp

    private fun reset(all: Boolean) {
        oldExcState.fill(0)
        pastIsfq.fill(0)
        oldT0Frac = 0
        oldT0 = 64
        firstFrame = 1
        lGcThres = 0
        tiltCode = 0
        dispMem.fill(0)
        qOld = Q_MAX
        qSubfr.fill(Q_MAX)
        if (all) {
            decGain[0] = -14336
            decGain[1] = -14336
            decGain[2] = -14336
            decGain[3] = -14336
            decGain.fill(0, 4, 22)
            decGain[22] = 21845
            memOversamp.fill(0)
            memHf.fill(0)
            memHf3.fill(0)
            memSigOut.fill(0)
            memHp400.fill(0)
            lagHist.fill(64)
            for (i in 0 until M) {
                ispold[i] = AmrWbTables.ISP_INIT[i].toInt()
                isfold[i] = AmrWbTables.ISF_INIT[i].toInt()
            }
            for (k in 0 until L_MEANBUF) for (i in 0 until M) isfBuf[k * M + i] = AmrWbTables.ISF_INIT[i].toInt()
            memDeemph = 0
            seeds.fill(21845)
            state = 0
            prevBfi = 0
            memSynHf.fill(0)
            memSynHi.fill(0)
            memSynLo.fill(0)
            dtxReset()
            vadHist = 0
        }
    }

    private fun decodeFrame(mode: Int) {
        p = 0
        val nbBits = COMPRESSED[mode]
        val newDtxState = rxDtxHandler(frameType)
        if (newDtxState != SPEECH) dtxDec(newDtxState)
        val bfi: Int
        val unusableFrame: Int
        when (frameType) {
            RX_SPEECH_BAD, RX_SPEECH_PROBABLY_DEGRADED -> {
                bfi = 1
                unusableFrame = 0
            }
            RX_NO_DATA, RX_SPEECH_LOST -> {
                bfi = 1
                unusableFrame = 1
            }
            else -> {
                bfi = 0
                unusableFrame = 0
            }
        }
        if (bfi != 0) {
            state += 1
            if (state > 6) state = 6
        } else {
            state = state shr 1
        }
        if (dtxGlobalState == DTX) {
            state = 5
            prevBfi = 0
        } else if (dtxGlobalState == DTX_MUTE) {
            state = 5
            prevBfi = 1
        }
        if (newDtxState == SPEECH) {
            val vadFlag = serialParm1()
            if (bfi == 0) vadHist = if (vadFlag == 0) addInt16(vadHist, 1) else 0
        }
        if (newDtxState != SPEECH) {
            isfIsp(isf, ispnew, M)
            ispAz(ispnew, 0, aq, 0, M, true)
            isfold.copyInto(isfTmp)
            var iSubfr = 0
            while (iSubfr < L_FRAME) {
                val j = iSubfr shr 6
                val frac = AmrWbTables.INTERPOL_FRAC[j].toInt()
                for (i in 0 until M) {
                    var l = mul16(isfTmp[i], subInt16(32767, frac))
                    l = mac16(l, isf[i], frac)
                    hfIsf[i] = round(l)
                }
                synthesis(aq, 0, exc2, iSubfr, 0, iSubfr * 5 / 4, 1, nbBits, newDtxState, bfi)
                iSubfr += L_SUBFR
            }
            reset(false)
            isf.copyInto(isfold)
            prevBfi = bfi
            dtxGlobalState = newDtxState
            return
        }
        oldExcState.copyInto(oldExc)
        val exc = PIT_MAX + L_INTERPOL
        if (nbBits > NBBITS_7K) {
            ind[0] = serialParm(8)
            ind[1] = serialParm(8)
            ind[2] = serialParm(6)
            ind[3] = serialParm(7)
            ind[4] = serialParm(7)
            ind[5] = serialParm(5)
            ind[6] = serialParm(5)
            dpisf2s46b(bfi)
        } else {
            ind[0] = serialParm(8)
            ind[1] = serialParm(8)
            ind[2] = serialParm(14)
            ind[3] = ind[2] and 0x007F
            ind[2] = ind[2] shr 7
            ind[4] = serialParm(6)
            dpisf2s36b(bfi)
        }
        isfIsp(isf, ispnew, M)
        if (firstFrame != 0) {
            firstFrame = 0
            ispnew.copyInto(ispold)
        }
        interpolateIsp()
        ispnew.copyInto(ispold)
        var lTmp = 0
        for (i in 0 until M - 1) {
            val t = subInt16(isf[i], isfold[i])
            lTmp = mac16(lTmp, t, t)
        }
        var tmp = s16(shlInt32(lTmp, 8) shr 16)
        tmp = multInt16(tmp, 26214)
        tmp = s16(20480 - tmp)
        var stabFac = shlInt16(tmp, 1)
        if (stabFac < 0) stabFac = 0
        isfold.copyInto(isfTmp)
        isf.copyInto(isfold)

        var pAq = 0
        var t0 = 0
        var t0Frac: Int
        var t0Min = 0
        var t0Max: Int
        var qNew = 0
        var iSubfr = 0
        while (iSubfr < L_FRAME) {
            var pitFlag = iSubfr
            if (iSubfr == 2 * L_SUBFR && nbBits > NBBITS_7K) pitFlag = 0
            var index: Int
            if (pitFlag == 0) {
                if (nbBits <= NBBITS_9K) {
                    index = serialParm(8)
                    if (index < (PIT_FR1_8B - PIT_MIN) * 2) {
                        t0 = PIT_MIN + (index shr 1)
                        t0Frac = subInt16(index, shlInt16(subInt16(t0, PIT_MIN), 1))
                        t0Frac = shlInt16(t0Frac, 1)
                    } else {
                        t0 = addInt16(index, PIT_FR1_8B - ((PIT_FR1_8B - PIT_MIN) * 2))
                        t0Frac = 0
                    }
                } else {
                    index = serialParm(9)
                    if (index < (PIT_FR2 - PIT_MIN) * 4) {
                        t0 = PIT_MIN + (index shr 2)
                        t0Frac = subInt16(index, shlInt16(subInt16(t0, PIT_MIN), 2))
                    } else if (index < (((PIT_FR2 - PIT_MIN) shl 2) + ((PIT_FR1_9B - PIT_FR2) shl 1))) {
                        index -= (PIT_FR2 - PIT_MIN) shl 2
                        t0 = PIT_FR2 + (index shr 1)
                        t0Frac = subInt16(index, shlInt16(subInt16(t0, PIT_FR2), 1))
                        t0Frac = shlInt16(t0Frac, 1)
                    } else {
                        t0 = addInt16(index, PIT_FR1_9B - ((PIT_FR2 - PIT_MIN) * 4) - ((PIT_FR1_9B - PIT_FR2) * 2))
                        t0Frac = 0
                    }
                }
                t0Min = t0 - 8
                if (t0Min < PIT_MIN) t0Min = PIT_MIN
                t0Max = t0Min + 15
                if (t0Max > PIT_MAX) {
                    t0Max = PIT_MAX
                    t0Min = PIT_MAX - 15
                }
            } else if (nbBits <= NBBITS_9K) {
                index = serialParm(5)
                t0 = s16(t0Min + (index shr 1))
                t0Frac = subInt16(index, shlInt16(s16(t0 - t0Min), 1))
                t0Frac = shlInt16(t0Frac, 1)
            } else {
                index = serialParm(6)
                t0 = s16(t0Min + (index shr 2))
                t0Frac = subInt16(index, shlInt16(s16(t0 - t0Min), 2))
            }
            if (bfi != 0) {
                t0 = lagConceal(t0, unusableFrame)
                t0Frac = 0
            }
            predLt4(exc + iSubfr, t0, t0Frac, L_SUBFR + 1)
            val select = if (unusableFrame != 0) 1 else if (nbBits <= NBBITS_9K) 0 else serialParm1()
            if (select == 0) {
                for (i in 0 until L_SUBFR) {
                    var l = oldExc[exc + i - 1 + iSubfr] + oldExc[exc + i + 1 + iSubfr]
                    l *= 5898
                    l += oldExc[exc + i + iSubfr] * 20972
                    code[i] = round(l shl 1)
                }
                code.copyInto(oldExc, exc + iSubfr, 0, L_SUBFR)
            }
            if (unusableFrame != 0) {
                for (i in 0 until L_SUBFR) code[i] = noiseGen(seeds, 0) shr 3
            } else if (nbBits <= NBBITS_7K) {
                decAcelp2pIn64(serialParm(12))
            } else if (nbBits <= NBBITS_9K) {
                for (i in 0 until 4) ind[i] = serialParm(5)
                decAcelp4pIn64(20)
            } else if (nbBits <= NBBITS_12K) {
                for (i in 0 until 4) ind[i] = serialParm(9)
                decAcelp4pIn64(36)
            } else if (nbBits <= NBBITS_14K) {
                ind[0] = serialParm(13)
                ind[1] = serialParm(13)
                ind[2] = serialParm(9)
                ind[3] = serialParm(9)
                decAcelp4pIn64(44)
            } else if (nbBits <= NBBITS_16K) {
                for (i in 0 until 4) ind[i] = serialParm(13)
                decAcelp4pIn64(52)
            } else if (nbBits <= NBBITS_18K) {
                for (i in 0 until 4) ind[i] = serialParm(2)
                for (i in 4 until 8) ind[i] = serialParm(14)
                decAcelp4pIn64(64)
            } else if (nbBits <= NBBITS_20K) {
                ind[0] = serialParm(10)
                ind[1] = serialParm(10)
                ind[2] = serialParm(2)
                ind[3] = serialParm(2)
                ind[4] = serialParm(10)
                ind[5] = serialParm(10)
                ind[6] = serialParm(14)
                ind[7] = serialParm(14)
                decAcelp4pIn64(72)
            } else {
                for (i in 0 until 8) ind[i] = serialParm(11)
                decAcelp4pIn64(88)
            }
            // preemph_amrwb_dec
            for (i in L_SUBFR - 1 downTo 1) code[i] = round(msu16(code[i] shl 16, code[i - 1], tiltCode))
            tmp = t0
            if (t0Frac > 2) tmp++
            // Pit_shrp
            for (i in tmp until L_SUBFR) code[i] = round(mac16(code[i] shl 16, code[i - tmp], PIT_SHARP))
            index = serialParm(if (nbBits <= NBBITS_9K) 6 else 7)
            decGain2(index, if (nbBits <= NBBITS_9K) 6 else 7, bfi, prevBfi, state, unusableFrame, vadHist)
            val gainPit = two[0]
            var lGainCode = two[1]
            tmp = qSubfr[0]
            for (i in 1 until 4) if (qSubfr[i] < tmp) tmp = qSubfr[i]
            if (tmp > Q_MAX) tmp = Q_MAX
            qNew = 0
            lTmp = lGainCode
            while (lTmp < 0x08000000 && qNew < tmp) {
                lTmp = lTmp shl 1
                qNew += 1
            }
            var gainCode = round(lTmp)
            scaleSignal(oldExc, exc + iSubfr - (PIT_MAX + L_INTERPOL), PIT_MAX + L_INTERPOL + L_SUBFR, s16(qNew - qOld))
            qOld = qNew
            if (bfi == 0) {
                for (i in 4 downTo 1) lagHist[i] = lagHist[i - 1]
                lagHist[0] = t0
                oldT0 = t0
                oldT0Frac = 0
            }
            for (i in L_SUBFR - 1 downTo 0) {
                val e = oldExc[exc + iSubfr + i]
                exc2[i] = s16((e + (if (e != MAX_16) 4 else 0)) shr 3)
            }
            val pitSharp: Int
            if (nbBits <= NBBITS_9K) {
                pitSharp = shlInt16(gainPit, 1)
                if (pitSharp > 16384) {
                    for (i in 0 until L_SUBFR) {
                        val t = multInt16(exc2[i], pitSharp)
                        var l = mul16(t, gainPit)
                        l = l shr 1
                        excp[i] = round(l)
                    }
                }
            } else {
                pitSharp = 0
            }
            val voiceFac = voiceFactor(exc2, -3, gainPit, code, gainCode, L_SUBFR)
            tiltCode = s16((voiceFac shr 2) + 8192)
            oldExc.copyInto(exc2, 0, exc + iSubfr, exc + iSubfr + L_SUBFR)
            var max = 1
            for (i in 0 until L_SUBFR) {
                var l = mul16(code[i], gainCode)
                l = shlInt32(l, 5)
                l = mac16(l, oldExc[exc + i + iSubfr], gainPit)
                l = shlInt32(l, 1)
                var t = round(l)
                oldExc[exc + i + iSubfr] = t
                t = s16(t - if (t < 0) 1 else 0)
                max = max or s16(t xor (t shr 15))
            }
            tmp = s16(addInt16(normS(max), qNew) - 1)
            qSubfr[3] = qSubfr[2]
            qSubfr[2] = qSubfr[1]
            qSubfr[1] = qSubfr[0]
            qSubfr[0] = tmp
            val j = if (nbBits <= NBBITS_7K) 0 else if (nbBits <= NBBITS_9K) 1 else 2
            phaseDispersion(s16(lGainCode shr 16), gainPit, j)
            tmp = s16(16384 - (voiceFac shr 1))
            val fac = multInt16(stabFac, tmp)
            lTmp = lGainCode
            if (lTmp < lGcThres) {
                lTmp += mul32by16b(lGainCode, 6226) shl 1
                if (lTmp > lGcThres) lTmp = lGcThres
            } else {
                lTmp = mul32by16b(lGainCode, 27536) shl 1
                if (lTmp < lGcThres) lTmp = lGcThres
            }
            lGcThres = lTmp
            lGainCode = mul32by16b(lGainCode, 32767 - fac) shl 1
            lGainCode = addInt32(lGainCode, mul32by16b(lTmp, fac) shl 1)
            tmp = s16((voiceFac shr 3) + 4096)
            gainCode = round(shlInt32(lGainCode, qNew))
            var l = code[0] shl 16
            l = msu16(l, code[1], tmp)
            l = mul16(round(l), gainCode)
            l = shlInt32(l, 5)
            l = mac16(l, exc2[0], gainPit)
            l = shlInt32(l, 1)
            exc2[0] = round(l)
            for (i in 1 until L_SUBFR - 1) {
                l = code[i] shl 16
                l = msu16(l, s16(code[i + 1] + code[i - 1]), tmp)
                l = mul16(round(l), gainCode)
                l = shlInt32(l, 5)
                l = mac16(l, exc2[i], gainPit)
                l = shlInt32(l, 1)
                exc2[i] = round(l)
            }
            l = code[L_SUBFR - 1] shl 16
            l = msu16(l, code[L_SUBFR - 2], tmp)
            l = mul16(round(l), gainCode)
            l = shlInt32(l, 5)
            l = mac16(l, exc2[L_SUBFR - 1], gainPit)
            l = shlInt32(l, 1)
            exc2[L_SUBFR - 1] = round(l)
            if (nbBits <= NBBITS_9K && pitSharp > 16384) {
                for (i in 0 until L_SUBFR) excp[i] = addInt16(excp[i], exc2[i])
                agc2(exc2, excp, L_SUBFR)
                excp.copyInto(exc2, 0, 0, L_SUBFR)
            }
            if (nbBits <= NBBITS_7K) {
                val k = iSubfr shr 6
                val frac = AmrWbTables.INTERPOL_FRAC[k].toInt()
                for (i in 0 until M) {
                    var t = mul16(isfTmp[i], subInt16(32767, frac))
                    t = mac16(t, isf[i], frac)
                    hfIsf[i] = round(t)
                }
            } else {
                memSynHf.fill(0, 0, M16K - M)
            }
            val corrGain = if (nbBits >= NBBITS_24K) serialParm(4) else 0
            synthesis(aq, pAq, exc2, 0, qNew, iSubfr + (iSubfr shr 2), corrGain, nbBits, newDtxState, bfi)
            pAq += M + 1
            iSubfr += L_SUBFR
        }
        oldExc.copyInto(oldExcState, 0, L_FRAME, L_FRAME + PIT_MAX + L_INTERPOL)
        scaleSignal(oldExc, exc, L_FRAME, s16(-qNew))
        dtxActivityUpdate(isf, oldExc, exc)
        dtxGlobalState = newDtxState
        prevBfi = bfi
    }

    // qpisf_2s.cpp

    private fun dpisf2s46b(bfi: Int) {
        if (bfi == 0) {
            val d1 = AmrWbIsfTables.DICO1
            val d2 = AmrWbIsfTables.DICO2
            for (i in 0 until 9) isf[i] = d1[(ind[0] shl 3) + ind[0] + i].toInt()
            for (i in 0 until 7) isf[i + 9] = d2[(ind[1] shl 3) - ind[1] + i].toInt()
            val s = AmrWbIsfStageTables
            for (i in 0 until 3) {
                isf[i] = s16(isf[i] + s.DICO21[ind[2] * 3 + i])
                isf[i + 3] = s16(isf[i + 3] + s.DICO22[ind[3] * 3 + i])
                isf[i + 6] = s16(isf[i + 6] + s.DICO23[ind[4] * 3 + i])
                isf[i + 9] = s16(isf[i + 9] + s.DICO24[ind[5] * 3 + i])
                isf[i + 12] = s16(isf[i + 12] + s.DICO25[(ind[6] shl 2) + i])
            }
            isf[15] = s16(isf[15] + s.DICO25[(ind[6] shl 2) + 3])
            for (i in 0 until ORDER) {
                val tmp = isf[i]
                isf[i] = s16(isf[i] + AmrWbIsfTables.MEAN_ISF[i])
                isf[i] = s16(isf[i] + ((MU * pastIsfq[i]) shr 15))
                pastIsfq[i] = tmp
            }
            shiftIsfBuffer()
        } else {
            concealIsf()
        }
        reorderIsf(isf, ISF_GAP, ORDER)
    }

    private fun dpisf2s36b(bfi: Int) {
        if (bfi == 0) {
            val d1 = AmrWbIsfTables.DICO1
            val d2 = AmrWbIsfTables.DICO2
            val s = AmrWbIsfStageTables
            for (i in 0 until 9) isf[i] = d1[ind[0] * 9 + i].toInt()
            for (i in 0 until 7) isf[i + 9] = addInt16(d2[ind[1] * 7 + i].toInt(), s.DICO23_36B[ind[4] * 7 + i].toInt())
            for (i in 0 until 5) isf[i] = addInt16(isf[i], s.DICO21_36B[ind[2] * 5 + i].toInt())
            for (i in 0 until 4) isf[i + 5] = addInt16(isf[i + 5], s.DICO22_36B[(ind[3] shl 2) + i].toInt())
            for (i in 0 until ORDER) {
                val tmp = isf[i]
                isf[i] = addInt16(tmp, AmrWbIsfTables.MEAN_ISF[i].toInt())
                isf[i] = addInt16(isf[i], multInt16(MU, pastIsfq[i]))
                pastIsfq[i] = tmp
            }
            shiftIsfBuffer()
        } else {
            concealIsf()
        }
        reorderIsf(isf, ISF_GAP, ORDER)
    }

    private fun shiftIsfBuffer() {
        for (i in 0 until M) {
            for (j in L_MEANBUF - 1 downTo 1) isfBuf[j * M + i] = isfBuf[(j - 1) * M + i]
            isfBuf[i] = isf[i]
        }
    }

    private fun concealIsf() {
        val refIsf = IntArray(M)
        for (i in 0 until M) {
            var l = mul16(AmrWbIsfTables.MEAN_ISF[i].toInt(), 8192)
            for (j in 0 until L_MEANBUF) l = mac16(l, isfBuf[j * M + i], 8192)
            refIsf[i] = round(l)
        }
        for (i in 0 until ORDER) isf[i] = addInt16(multInt16(ALPHA, isfold[i]), multInt16(ONE_ALPHA, refIsf[i]))
        for (i in 0 until ORDER) {
            val tmp = addInt16(refIsf[i], multInt16(pastIsfq[i], MU))
            pastIsfq[i] = subInt16(isf[i], tmp) shr 1
        }
    }

    private fun reorderIsf(isf: IntArray, minDist: Int, n: Int) {
        var isfMin = minDist
        for (i in 0 until n - 1) {
            if (isf[i] < isfMin) isf[i] = isfMin
            isfMin = addInt16(isf[i], minDist)
        }
    }

    // isp_isf.cpp, isp_az.cpp, interpolate_isp.cpp, isf_extrapolation.cpp, weight_amrwb_lpc.cpp

    private fun isfIsp(isfIn: IntArray, isp: IntArray, m: Int) {
        for (i in 0 until m - 1) isp[i] = isfIn[i]
        isp[m - 1] = shlInt16(isfIn[m - 1], 1)
        val table = AmrWbTables.ISP_ISF
        for (i in 0 until m) {
            val ind = isp[i] shr 7
            val offset = isp[i] and 0x007f
            val l = mul16(s16(table[ind + 1] - table[ind]), offset)
            isp[i] = addInt16(table[ind].toInt(), s16(l shr 8))
        }
    }

    private fun ispAz(isp: IntArray, at: Int, a: IntArray, aAt: Int, m: Int, adaptiveScaling: Boolean) {
        val f1 = IntArray(NC16K + 1)
        val f2 = IntArray(NC16K)
        val nc = m shr 1
        if (nc > 8) {
            getIspPol16k(isp, at, f1, nc)
            for (i in 0..nc) f1[i] = shlInt32(f1[i], 2)
            getIspPol16k(isp, at + 1, f2, nc - 1)
            for (i in 0..nc - 1) f2[i] = shlInt32(f2[i], 2)
        } else {
            getIspPol(isp, at, f1, nc)
            getIspPol(isp, at + 1, f2, nc - 1)
        }
        for (i in nc - 1 downTo 2) f2[i] -= f2[i - 2]
        val last = isp[at + m - 1]
        for (i in 0 until nc) {
            val t0 = mul32by16b(f1[i], last) shl 1
            val t1 = mul32by16b(f2[i], last) shl 1
            f1[i] += t0
            f2[i] -= t1
        }
        a[aAt] = 4096
        var tmax = 1
        var j = m - 1
        for (i in 1 until nc) {
            var t0 = addInt32(f1[i], f2[i])
            var t1 = t0 - if (t0 < 0) 1 else 0
            t1 = t1 xor (t1 shr 31)
            tmax = tmax or t1
            a[aAt + i] = s16((t0 shr 12) + ((t0 shr 11) and 1))
            t0 = subInt32(f1[i], f2[i])
            t1 = t0 - if (t0 < 0) 1 else 0
            t1 = t1 xor (t1 shr 31)
            tmax = tmax or t1
            a[aAt + j--] = s16((t0 shr 12) + ((t0 shr 11) and 1))
        }
        var q = if (adaptiveScaling) 4 - normalize(tmax) else 0
        val qSug: Int
        if (q > 0) {
            qSug = 12 + q
            j = m - 1
            for (i in 1 until nc) {
                var t0 = addInt32(f1[i], f2[i])
                a[aAt + i] = s16((t0 shr qSug) + ((t0 shr (qSug - 1)) and 1))
                t0 = subInt32(f1[i], f2[i])
                a[aAt + j] = s16((t0 shr qSug) + ((t0 shr (qSug - 1)) and 1))
                j--
            }
            a[aAt] = a[aAt] shr q
        } else {
            qSug = 12
            q = 0
        }
        var t0 = ((f1[nc].toLong() * last) shr 16).toInt() shl 1
        t0 = addInt32(f1[nc], t0)
        a[aAt + nc] = s16((t0 shr qSug) + ((t0 shr (qSug - 1)) and 1))
        a[aAt + m] = shrRnd(last, 3 + q)
    }

    private fun getIspPol(isp: IntArray, start: Int, f: IntArray, n: Int) {
        f[0] = 0x00800000
        f[1] = -isp[start] shl 9
        var fi = 2
        var ii = start + 2
        for (i in 2..n) {
            f[fi] = f[fi - 2]
            for (j in 1 until i) {
                var t0 = mul32by16b(f[fi - 1], isp[ii])
                t0 = shlInt32(t0, 2)
                f[fi] -= t0
                f[fi] += f[fi - 2]
                fi--
            }
            f[fi] -= isp[ii] shl 9
            fi += i
            ii += 2
        }
    }

    private fun getIspPol16k(isp: IntArray, start: Int, f: IntArray, n: Int) {
        f[0] = 0x00200000
        f[1] = -isp[start] shl 7
        var fi = 2
        var ii = start + 2
        for (i in 2..n) {
            f[fi] = f[fi - 2]
            for (j in 1 until i) {
                var t0 = mul32by16b(f[fi - 1], isp[ii])
                t0 = shlInt32(t0, 2)
                f[fi] -= t0
                f[fi] += f[fi - 2]
                fi--
            }
            f[fi] -= isp[ii] shl 7
            fi += i
            ii += 2
        }
    }

    private fun interpolateIsp() {
        val isp = IntArray(M)
        var az = 0
        for (k in 0 until 3) {
            val facNew = AmrWbTables.INTERPOL_FRAC[k].toInt()
            val facOld = addInt16(subInt16(32767, facNew), 1)
            for (i in 0 until M) {
                var l = mul16(ispold[i], facOld)
                l = mac16(l, ispnew[i], facNew)
                isp[i] = round(l)
            }
            ispAz(isp, 0, aq, az, M, false)
            az += M + 1
        }
        ispAz(ispnew, 0, aq, az, M, false)
    }

    private fun isfExtrapolation(hf: IntArray) {
        val isfDiff = IntArray(M - 2)
        val isfCorr = IntArray(3)
        hf[M16K - 1] = hf[M - 1]
        for (i in 1 until M - 1) isfDiff[i - 1] = subInt16(hf[i], hf[i - 1])
        var l = 0
        for (i in 3 until M - 1) l = mac16(l, isfDiff[i - 1], INV_LENGTH)
        var mean = round(l)
        var tmp = 0
        for (i in 0 until M - 2) if (isfDiff[i] > tmp) tmp = isfDiff[i]
        var exp = normS(tmp)
        for (i in 0 until M - 2) isfDiff[i] = shlInt16(isfDiff[i], exp)
        mean = shlInt16(mean, exp)
        for (c in 0 until 3) {
            for (i in 7 until M - 2) {
                val tmp2 = subInt16(isfDiff[i], mean)
                val tmp3 = subInt16(isfDiff[i - 2 - c], mean)
                l = mul16(tmp2, tmp3)
                val hi = s16(l shr 16)
                val lo = s16((l - (hi shl 16)) shr 1)
                l = mpyDpf32(hi, lo, hi, lo)
                isfCorr[c] = addInt32(isfCorr[c], l)
            }
        }
        var maxCorr = if (isfCorr[0] > isfCorr[1]) 0 else 1
        if (isfCorr[2] > isfCorr[maxCorr]) maxCorr = 2
        maxCorr++
        for (i in M - 1 until M16K - 1) {
            tmp = subInt16(hf[i - 1 - maxCorr], hf[i - 2 - maxCorr])
            hf[i] = addInt16(hf[i - 1], tmp)
        }
        tmp = addInt16(hf[4], hf[3])
        tmp = subInt16(hf[2], tmp)
        tmp = multInt16(tmp, 5461)
        tmp = s16(tmp + 20390)
        if (tmp > 19456) tmp = 19456
        tmp = subInt16(tmp, hf[M - 2])
        var tmp2 = subInt16(hf[M16K - 2], hf[M - 2])
        val exp2 = normS(tmp2)
        exp = normS(tmp)
        exp--
        tmp = s16(tmp shl exp)
        tmp2 = s16(tmp2 shl exp2)
        val coeff = div16by16(tmp, tmp2)
        exp = exp2 - exp
        for (i in M - 1 until M16K - 1) {
            tmp = multInt16(subInt16(hf[i], hf[i - 1]), coeff)
            isfDiff[i - (M - 1)] = shlInt16(tmp, exp)
        }
        for (i in M until M16K - 1) {
            tmp = s16(isfDiff[i - (M - 1)] + isfDiff[i - M] - 1280)
            if (tmp < 0) {
                if (isfDiff[i - (M - 1)] > isfDiff[i - M]) {
                    isfDiff[i - M] = s16(1280 - isfDiff[i - (M - 1)])
                } else {
                    isfDiff[i - (M - 1)] = s16(1280 - isfDiff[i - M])
                }
            }
        }
        for (i in M - 1 until M16K - 1) hf[i] = addInt16(hf[i - 1], isfDiff[i - (M - 1)])
        for (i in 0 until M16K - 1) hf[i] = multInt16(hf[i], 26214)
        isfIsp(hf, hf, M16K)
    }

    private fun weightLpc(a: IntArray, aAt: Int, out: IntArray, gamma: Int, m: Int) {
        out[0] = a[aAt]
        var fac = gamma
        var i = 1
        while (i < m) {
            out[i] = s16((a[aAt + i] * fac + 0x4000) shr 15)
            fac = s16((fac * gamma + 0x4000) shr 15)
            i++
        }
        out[i] = s16((a[aAt + i] * fac + 0x4000) shr 15)
    }

    // pred_lt4.cpp

    private fun predLt4(at: Int, t0: Int, fraction: Int, lSubfr: Int) {
        val exc = oldExc
        var pt = at - t0
        var frac = -fraction
        if (frac < 0) {
            frac += UP_SAMP
            pt--
        }
        pt -= L_INTERPOL2 - 1
        val inter = AmrWbTables.INTER4_2
        val c = (UP_SAMP - 1 - frac) * 2 * L_INTERPOL2
        var j = 0
        while (j < (lSubfr shr 2)) {
            var s1 = 0x2000
            var s2 = 0x2000
            var s3 = 0x2000
            var s4 = 0x2000
            var i = 0
            while (i < L_INTERPOL2 shl 1) {
                var t1 = exc[pt + i]
                var t2 = exc[pt + i + 1]
                val t3 = exc[pt + i + 2]
                s1 += t1 * inter[c + i]
                s2 += t2 * inter[c + i]
                s1 += t2 * inter[c + i + 1]
                s2 += t3 * inter[c + i + 1]
                s3 += t3 * inter[c + i]
                s1 += t3 * inter[c + i + 2]
                t1 = exc[pt + i + 3]
                t2 = exc[pt + i + 4]
                s4 += t1 * inter[c + i]
                s3 += t1 * inter[c + i + 1]
                s2 += t1 * inter[c + i + 2]
                s1 += t1 * inter[c + i + 3]
                s4 += t2 * inter[c + i + 1]
                s2 += t2 * inter[c + i + 3]
                s3 += t2 * inter[c + i + 2]
                t1 = exc[pt + i + 5]
                t2 = exc[pt + i + 6]
                s4 += t1 * inter[c + i + 2]
                s3 += t1 * inter[c + i + 3]
                s4 += t2 * inter[c + i + 3]
                i += 4
            }
            exc[at + (j shl 2)] = s16(s1 shr 14)
            exc[at + (j shl 2) + 1] = s16(s2 shr 14)
            exc[at + (j shl 2) + 2] = s16(s3 shr 14)
            exc[at + (j shl 2) + 3] = s16(s4 shr 14)
            pt += 4
            j++
        }
        if ((lSubfr and 1) != 0) {
            var s1 = 0x2000
            var i = 0
            while (i < 2 * L_INTERPOL2) {
                s1 += exc[pt + i] * inter[c + i]
                s1 += exc[pt + i + 1] * inter[c + i + 1]
                s1 += exc[pt + i + 2] * inter[c + i + 2]
                s1 += exc[pt + i + 3] * inter[c + i + 3]
                i += 4
            }
            exc[at + (j shl 2)] = s16(s1 shr 14)
        }
    }

    // dec_acelp_2p_in_64.cpp, dec_acelp_4p_in_64.cpp, dec_alg_codebook.cpp

    private fun decAcelp2pIn64(index: Int) {
        code.fill(0)
        var i = (index shr 5) and 0x003E
        code[i] = if (((index shr 6) and 32) == 0) 512 else -512
        i = ((index and 0x001F) shl 1) + 1
        code[i] = if ((index and 32) == 0) 512 else -512
    }

    private fun decAcelp4pIn64(nbbits: Int) {
        code.fill(0)
        val pos = IntArray(6)
        when (nbbits) {
            20 -> for (k in 0 until 4) {
                dec1pN1(ind[k], 4, 0, pos, 0)
                addPulses(pos, 1, k)
            }
            36 -> for (k in 0 until 4) {
                dec2p2N1(ind[k], 4, 0, pos, 0)
                addPulses(pos, 2, k)
            }
            44 -> {
                for (k in 0 until 2) {
                    dec3p3N1(ind[k], 4, 0, pos, 0)
                    addPulses(pos, 3, k)
                }
                for (k in 2 until 4) {
                    dec2p2N1(ind[k], 4, 0, pos, 0)
                    addPulses(pos, 2, k)
                }
            }
            52 -> for (k in 0 until 4) {
                dec3p3N1(ind[k], 4, 0, pos, 0)
                addPulses(pos, 3, k)
            }
            64 -> for (k in 0 until 4) {
                dec4p4N((ind[k] shl 14) + ind[k + 4], 4, 0, pos, 0)
                addPulses(pos, 4, k)
            }
            72 -> {
                for (k in 0 until 2) {
                    dec5p5N((ind[k] shl 10) + ind[k + 4], 4, 0, pos, 0)
                    addPulses(pos, 5, k)
                }
                for (k in 2 until 4) {
                    dec4p4N((ind[k] shl 14) + ind[k + 4], 4, 0, pos, 0)
                    addPulses(pos, 4, k)
                }
            }
            88 -> for (k in 0 until 4) {
                dec6p6N2((ind[k] shl 11) + ind[k + 4], 4, 0, pos, 0)
                addPulses(pos, 6, k)
            }
        }
    }

    private fun addPulses(pos: IntArray, nbPulse: Int, track: Int) {
        for (k in 0 until nbPulse) {
            val i = ((pos[k] and (NB_POS - 1)) shl 2) + track
            code[i] = s16(if ((pos[k] and NB_POS) == 0) code[i] + 512 else code[i] - 512)
        }
    }

    private fun dec1pN1(index: Int, n: Int, offset: Int, pos: IntArray, at: Int) {
        val mask = (1 shl n) - 1
        var pos1 = s16((index and mask) + offset)
        if (((index shr n) and 1) == 1) pos1 = s16(pos1 + NB_POS)
        pos[at] = pos1
    }

    private fun dec2p2N1(index: Int, n: Int, offset: Int, pos: IntArray, at: Int) {
        val mask = subInt16(shlInt16(1, n), 1)
        var pos1 = s16(addInt32(shrInt32(index, n) and mask, offset))
        val tmp = shlInt16(n, 1)
        val i = (index shr tmp) and 1
        var pos2 = addInt16(s16(index and mask), offset)
        if (pos2 < pos1) {
            if (i == 1) pos1 = s16(pos1 + NB_POS) else pos2 = s16(pos2 + NB_POS)
        } else if (i == 1) {
            pos1 = s16(pos1 + NB_POS)
            pos2 = s16(pos2 + NB_POS)
        }
        pos[at] = pos1
        pos[at + 1] = pos2
    }

    private fun dec3p3N1(index: Int, n: Int, offset: Int, pos: IntArray, at: Int) {
        var mask = (1 shl ((2 * n) - 1)) - 1
        var idx = index and mask
        var j = offset
        var tmp = (n shl 1) - 1
        if (((index shr tmp) and 1) != 0) j = s16(j + (1 shl (n - 1)))
        dec2p2N1(idx, n - 1, j, pos, at)
        mask = (1 shl (n + 1)) - 1
        tmp = n shl 1
        idx = (index shr tmp) and mask
        dec1pN1(idx, n, offset, pos, at + 2)
    }

    private fun dec4p4N1(index: Int, n: Int, offset: Int, pos: IntArray, at: Int) {
        var tmp = (n shl 1) - 1
        var mask = (1 shl tmp) - 1
        var idx = index and mask
        var j = offset
        if (((index shr tmp) and 1) != 0) j = s16(j + (1 shl (n - 1)))
        dec2p2N1(idx, n - 1, j, pos, at)
        tmp = (n shl 1) + 1
        mask = (1 shl tmp) - 1
        idx = (index shr (n shl 1)) and mask
        dec2p2N1(idx, n, offset, pos, at + 2)
    }

    private fun dec4p4N(index: Int, n: Int, offset: Int, pos: IntArray, at: Int) {
        val n1 = n - 1
        val j = s16(offset + (1 shl n1))
        when ((index shr ((n shl 2) - 2)) and 3) {
            0 -> if (((index shr ((n1 shl 2) + 1)) and 1) != 0) dec4p4N1(index, n1, j, pos, at) else dec4p4N1(index, n1, offset, pos, at)
            1 -> {
                dec1pN1(index shr ((3 * n1) + 1), n1, offset, pos, at)
                dec3p3N1(index, n1, j, pos, at + 1)
            }
            2 -> {
                dec2p2N1(index shr ((n1 shl 1) + 1), n1, offset, pos, at)
                dec2p2N1(index, n1, j, pos, at + 2)
            }
            3 -> {
                dec3p3N1(index shr (n1 + 1), n1, offset, pos, at)
                dec1pN1(index, n1, j, pos, at + 3)
            }
        }
    }

    private fun dec5p5N(index: Int, n: Int, offset: Int, pos: IntArray, at: Int) {
        val n1 = n - 1
        val j = addInt16(offset, shlInt16(1, n1))
        val idx = index shr ((n shl 1) + 1)
        if (((index shr ((5 * n) - 1)) and 1) != 0) {
            dec3p3N1(idx, n1, j, pos, at)
            dec2p2N1(index, n, offset, pos, at + 3)
        } else {
            dec3p3N1(idx, n1, offset, pos, at)
            dec2p2N1(index, n, offset, pos, at + 3)
        }
    }

    private fun dec6p6N2(index: Int, n: Int, offset: Int, pos: IntArray, at: Int) {
        val n1 = n - 1
        val j = s16(offset + (1 shl n1))
        var offsetA = j
        var offsetB = j
        if (((index shr (6 * n - 5)) and 1) == 0) offsetA = offset else offsetB = offset
        when ((index shr (6 * n - 4)) and 3) {
            0 -> {
                dec5p5N(index shr n, n1, offsetA, pos, at)
                dec1pN1(index, n1, offsetA, pos, at + 5)
            }
            1 -> {
                dec5p5N(index shr n, n1, offsetA, pos, at)
                dec1pN1(index, n1, offsetB, pos, at + 5)
            }
            2 -> {
                dec4p4N(index shr (2 * n1 + 1), n1, offsetA, pos, at)
                dec2p2N1(index, n1, offsetB, pos, at + 4)
            }
            3 -> {
                dec3p3N1(index shr (3 * n1 + 1), n1, offset, pos, at)
                dec3p3N1(index, n1, j, pos, at + 3)
            }
        }
    }

    // dec_gain2_amr_wb.cpp: [two] receives the pitch gain and the code gain.

    private fun decGain2(index: Int, nbits: Int, bfi: Int, prevBfi: Int, state: Int, unusableFrame: Int, vadHist: Int) {
        val mem = decGain
        val fe = IntArray(2)
        val e = IntArray(1)
        fe[0] = dotProduct12(code, 0, code, 0, L_SUBFR, e)
        fe[1] = e[0] - 24
        oneOvSqrtNorm(fe)
        val gcodeInov = s16(shlInt32(fe[0], fe[1] - 3) shr 16)
        if (bfi != 0) {
            var tmp = median5(mem, PBUF + 2)
            mem[PAST_GAIN_PIT] = tmp
            if (mem[PAST_GAIN_PIT] > 15565) mem[PAST_GAIN_PIT] = 15565
            val gainPit = if (unusableFrame != 0) {
                multInt16(AmrWbTables.PDOWN_UNUSABLE[state].toInt(), mem[PAST_GAIN_PIT])
            } else {
                multInt16(AmrWbTables.PDOWN_USABLE[state].toInt(), mem[PAST_GAIN_PIT])
            }
            tmp = median5(mem, GBUF + 2)
            mem[PAST_GAIN_CODE] = if (vadHist > 2) {
                tmp
            } else if (unusableFrame != 0) {
                multInt16(AmrWbTables.CDOWN_UNUSABLE[state].toInt(), tmp)
            } else {
                multInt16(AmrWbTables.CDOWN_USABLE[state].toInt(), tmp)
            }
            var lTmp = mem[3] + mem[2]
            mem[3] = mem[3]
            lTmp += mem[1]
            lTmp += mem[0]
            mem[2] = mem[1]
            var quaEner = s16(lTmp shr 3)
            mem[1] = mem[0]
            quaEner = s16(quaEner - 3072)
            if (quaEner < -14336) quaEner = -14336
            mem[0] = quaEner
            for (i in 1 until 5) {
                mem[GBUF + i - 1] = mem[GBUF + i]
                mem[PBUF + i - 1] = mem[PBUF + i]
            }
            mem[GBUF + 4] = mem[PAST_GAIN_CODE]
            mem[PBUF + 4] = mem[PAST_GAIN_PIT]
            two[0] = gainPit
            two[1] = mul16(mem[PAST_GAIN_CODE], gcodeInov)
            return
        }
        var lTmp = MEAN_ENER shl 16
        lTmp = shlInt32(lTmp, 8)
        for (k in 0 until 4) lTmp = mac16(lTmp, AmrWbTables.GAIN_PRED[k].toInt(), mem[k])
        var gcode0 = s16(lTmp shr 16)
        lTmp = (gcode0 * 5443) shr 7
        var expGcode0 = s16(lTmp shr 16)
        val frac = s16((lTmp - (expGcode0 shl 16)) shr 1)
        gcode0 = s16(powerOf2(14, frac))
        expGcode0 = s16(expGcode0 - 14)
        val table = if (nbits == 6) AmrWbTables.QUA_GAIN6B else AmrWbTables.QUA_GAIN7B
        val gainPit = table[index shl 1].toInt()
        val gCode = table[(index shl 1) + 1].toInt()
        lTmp = mul16(gCode, gcode0)
        lTmp = shlInt32(lTmp, expGcode0 + 4)
        var gainCod = lTmp
        if (prevBfi == 1) {
            lTmp = mul16(mem[PREV_GC], 5120)
            if (gainCod > lTmp && gainCod > 6553600) gainCod = lTmp
        }
        mem[PAST_GAIN_CODE] = round(shlInt32(gainCod, 3))
        mem[PAST_GAIN_PIT] = gainPit
        mem[PREV_GC] = mem[PAST_GAIN_CODE]
        var tmp = mem[GBUF + 1]
        var tmp1 = mem[PBUF + 1]
        var tmp2 = mem[PBUF2 + 1]
        for (i in 1 until 5) {
            mem[GBUF + i - 1] = tmp
            mem[PBUF + i - 1] = tmp1
            mem[PBUF2 + i - 1] = tmp2
            tmp = mem[GBUF + i]
            tmp1 = mem[PBUF + i]
            tmp2 = mem[PBUF2 + i]
        }
        mem[GBUF + 4] = mem[PAST_GAIN_CODE]
        mem[PBUF + 4] = mem[PAST_GAIN_PIT]
        mem[PBUF2 + 4] = mem[PAST_GAIN_PIT]
        val hi = s16(gainCod shr 16)
        val lo = s16((gainCod - (hi shl 16)) shr 1)
        lTmp = mul32by16(hi, lo, gcodeInov)
        gainCod = shlInt32(lTmp, 3)
        mem[3] = mem[2]
        mem[2] = mem[1]
        mem[1] = mem[0]
        val ef = IntArray(2)
        log2(gCode, ef)
        val exp = s16(ef[0] - 11)
        lTmp = mul32by16(exp, ef[1], 24660)
        mem[0] = s16(lTmp shr 3)
        two[0] = gainPit
        two[1] = gainCod
    }

    private fun median5(x: IntArray, at: Int): Int {
        var x1 = x[at - 2]
        var x2 = x[at - 1]
        var x3 = x[at]
        var x4 = x[at + 1]
        var x5 = x[at + 2]
        var tmp: Int
        if (x2 < x1) { tmp = x1; x1 = x2; x2 = tmp }
        if (x3 < x1) { tmp = x1; x1 = x3; x3 = tmp }
        if (x4 < x1) { tmp = x1; x1 = x4; x4 = tmp }
        if (x5 < x1) x5 = x1
        if (x3 < x2) { tmp = x2; x2 = x3; x3 = tmp }
        if (x4 < x2) { tmp = x2; x2 = x4; x4 = tmp }
        if (x5 < x2) x5 = x2
        if (x4 < x3) x3 = x4
        if (x5 < x3) x3 = x5
        return x3
    }

    // lagconceal.cpp

    private fun lagConceal(t0In: Int, unusableFrame: Int): Int {
        var t0 = t0In
        val gainHist = decGain
        val g = PBUF2
        val lastGain = gainHist[g + 4]
        val secLastGain = gainHist[g + 3]
        val lastLag = lagHist[0]
        var minLag = lagHist[0]
        var maxLag = lagHist[0]
        for (i in 1 until L_LTPHIST) {
            if (lagHist[i] < minLag) minLag = lagHist[i]
            if (lagHist[i] > maxLag) maxLag = lagHist[i]
        }
        var minGain = gainHist[g]
        for (i in 1 until L_LTPHIST) if (gainHist[g + i] < minGain) minGain = gainHist[g + i]
        val lagDif = subInt16(maxLag, minLag)
        if (unusableFrame != 0) {
            t0 = if (minGain > 8192 && lagDif < 10) {
                oldT0
            } else if (lastGain > 8192 && secLastGain > 8192) {
                lagHist[0]
            } else {
                randomLag()
            }
            if (t0 > maxLag) t0 = maxLag
            if (t0 < minLag) t0 = minLag
        } else {
            var meanLag = 0
            for (i in 0 until L_LTPHIST) meanLag = addInt16(meanLag, lagHist[i])
            meanLag = multInt16(meanLag, ONE_PER_LTPHIST)
            val tmp = s16(t0 - maxLag)
            val tmp2 = s16(t0 - lastLag)
            val keep = (lagDif < 10 && t0 > minLag - 5 && tmp < 5) ||
                (lastGain > 8192 && secLastGain > 8192 && (tmp2 + 10 > 0 && tmp2 < 10)) ||
                (minGain < 6554 && lastGain == minGain && (t0 > minLag && t0 < maxLag)) ||
                (lagDif < 70 && t0 > minLag && t0 < maxLag) ||
                (t0 > meanLag && t0 < maxLag)
            if (!keep) {
                t0 = if (minGain > 8192 && lagDif < 10) {
                    lagHist[0]
                } else if (lastGain > 8192 && secLastGain > 8192) {
                    lagHist[0]
                } else {
                    randomLag()
                }
                if (t0 > maxLag) t0 = maxLag
                if (t0 < minLag) t0 = minLag
            }
        }
        return t0
    }

    private fun randomLag(): Int {
        val sorted = lagHist.copyOf()
        for (i in 0 until L_LTPHIST) {
            val x = sorted[i]
            var k = i - 1
            while (k >= 0 && x < sorted[k]) {
                sorted[k + 1] = sorted[k]
                k--
            }
            sorted[k + 1] = x
        }
        var lagDif = subInt16(sorted[4], sorted[2])
        if (lagDif > 40) lagDif = 40
        val d = noiseGen(seeds, 2)
        val d2 = multInt16(lagDif shr 1, d)
        val tmp = addInt16(addInt16(sorted[2], sorted[3]), sorted[4])
        return addInt16(multInt16(tmp, ONE_PER_3), d2)
    }

    // scale_signal.cpp, voice_factor.cpp, agc2_amr_wb.cpp, phase_dispersion.cpp

    private fun scaleSignal(x: IntArray, at: Int, lg: Int, expIn: Int) {
        if (expIn > 0) {
            for (i in 0 until lg) x[at + i] = round(shlInt32(x[at + i] shl 16, expIn))
        } else if (expIn < 0) {
            val exp = (-expIn) and 0xf
            val tmp = s16(0x00008000 shr (16 - exp))
            for (i in 0 until lg) x[at + i] = addInt16(x[at + i], tmp) shr exp
        }
    }

    private fun voiceFactor(exc: IntArray, qExc: Int, gainPit: Int, code: IntArray, gainCode: Int, lSubfr: Int): Int {
        val e = IntArray(1)
        var ener1 = s16(dotProduct12(exc, 0, exc, 0, lSubfr, e) shr 16)
        var exp1 = subInt16(e[0], s16(qExc shl 1))
        val l = mul16(gainPit, gainPit)
        var exp = normalize(l)
        var tmp = s16((l shl exp) shr 16)
        ener1 = multInt16(ener1, tmp)
        exp1 = s16(exp1 - (exp + 10))
        var ener2 = s16(dotProduct12(code, 0, code, 0, lSubfr, e) shr 16)
        var exp2 = e[0]
        exp = normS(gainCode)
        tmp = shlInt16(gainCode, exp)
        tmp = multInt16(tmp, tmp)
        ener2 = multInt16(ener2, tmp)
        exp2 = s16(exp2 - (exp shl 1))
        val i = s16(exp1 - exp2)
        if (i >= 0) {
            ener1 = ener1 shr 1
            ener2 = ener2 shr (i + 1)
        } else {
            ener1 = ener1 shr (1 - i)
            ener2 = ener2 shr 1
        }
        tmp = s16(ener1 - ener2)
        ener1 = s16(ener1 + ener2 + 1)
        return if (tmp >= 0) div16by16(tmp, ener1) else negateInt16(div16by16(negateInt16(tmp), ener1))
    }

    private fun agc2(sigIn: IntArray, sigOut: IntArray, lTrm: Int) {
        var temp = sigOut[0] shr 2
        var s = (temp * temp) shl 1
        for (i in 1 until lTrm) {
            temp = sigOut[i] shr 2
            s = mac16(s, temp, temp)
        }
        if (s == 0) return
        var exp = s16(normalize(s) - 1)
        val gainOut = round(s shl exp)
        temp = sigIn[0] shr 2
        s = mul16(temp, temp)
        for (i in 1 until lTrm) {
            temp = sigIn[i] shr 2
            s = mac16(s, temp, temp)
        }
        val g0: Int
        if (s == 0) {
            g0 = 0
        } else {
            val i = normalize(s)
            val gainIn = round(s shl i)
            exp = s16(exp - i)
            s = div16by16(gainOut, gainIn)
            s = shlInt32(s, 7)
            s = shrInt32(s, exp)
            s = oneOvSqrt(s)
            g0 = round(shlInt32(s, 9))
        }
        for (i in 0 until lTrm) sigOut[i] = s16(shlInt32(sigOut[i] * g0, 3) shr 16)
    }

    private fun phaseDispersion(gainCode: Int, gainPit: Int, mode: Int) {
        val code2 = IntArray(2 * L_SUBFR)
        var state = if (gainPit < PITCH_0_6) 0 else if (gainPit < PITCH_0_9) 1 else 2
        // disp_mem: the previous state, the previous code gain, then six pitch gains.
        for (i in 5 downTo 1) dispMem[2 + i] = dispMem[2 + i - 1]
        dispMem[2] = gainPit
        if (subInt16(gainCode, dispMem[1]) > shlInt16(dispMem[1], 1)) {
            if (state < 2) state++
        } else {
            var j = 0
            for (i in 0 until 6) if (dispMem[2 + i] < PITCH_0_6) j++
            if (j > 2) state = 0
            if (state > dispMem[0] + 1) state--
        }
        dispMem[1] = gainCode
        dispMem[0] = state
        state += mode
        if (state == 0 || state == 1) {
            val imp = if (state == 0) AmrWbTables.PH_IMP_LOW else AmrWbTables.PH_IMP_MID
            for (i in 0 until L_SUBFR) {
                if (code[i] != 0) {
                    for (j in 0 until L_SUBFR) code2[i + j] = addInt16(code2[i + j], multInt16R(code[i], imp[j].toInt()))
                }
            }
        }
        if (state < 2) for (i in 0 until L_SUBFR) code[i] = addInt16(code2[i], code2[i + L_SUBFR])
    }

    // synthesis_amr_wb.cpp and the filters it runs

    private fun synthesis(
        aqAll: IntArray, aqAt: Int, exc: IntArray, excAt: Int, qNewIn: Int, outAt: Int, prmsGain: Int,
        nbBits: Int, newDtxState: Int, bfi: Int,
    ) {
        memSynHi.copyInto(synthHi)
        memSynLo.copyInto(synthLo)
        synFilt32(aqAll, aqAt, M, exc, excAt, qNewIn, synthHi, synthLo, M, L_SUBFR)
        synthHi.copyInto(memSynHi, 0, L_SUBFR, L_SUBFR + M)
        synthLo.copyInto(memSynLo, 0, L_SUBFR, L_SUBFR + M)
        deemphasis32(synthHi, synthLo, M, synth12k8, PREEMPH_FAC, L_SUBFR)
        highpass50(synth12k8, L_SUBFR)
        oversample(synth12k8, L_SUBFR, synth, outAt)
        for (i in 0 until L_SUBFR16K) hf[i] = noiseGen(seeds, 1) shr 3
        for (i in 0 until L_SUBFR) exc[excAt + i] = addInt16(exc[excAt + i], 0x0004) shr 3
        val qNew = qNewIn - 3
        val e = IntArray(1)
        var ener = s16(dotProduct12(exc, excAt, exc, excAt, L_SUBFR, e) shr 16)
        var expEner = s16(e[0] - (qNew shl 1))
        var tmp = s16(dotProduct12(hf, 0, hf, 0, L_SUBFR16K, e) shr 16)
        var exp = e[0]
        if (tmp > ener) {
            tmp = tmp shr 1
            exp += 1
        }
        val fe = IntArray(2)
        fe[0] = div16by16(tmp, ener) shl 16
        fe[1] = s16(exp - expEner)
        oneOvSqrtNorm(fe)
        var lTmp = shlInt32(fe[0], fe[1] + 1)
        tmp = s16(lTmp shr 16)
        for (i in 0 until L_SUBFR16K) hf[i] = s16((hf[i] * tmp) shr 15)
        highpass400(synth12k8, L_SUBFR)
        lTmp = 1
        var lTmp2 = 1
        lTmp = mac16(lTmp, synth12k8[0], synth12k8[0])
        for (i in 1 until L_SUBFR) {
            lTmp = mac16(lTmp, synth12k8[i], synth12k8[i])
            lTmp2 = mac16(lTmp2, synth12k8[i], synth12k8[i - 1])
        }
        exp = normalize(lTmp)
        ener = s16((lTmp shl exp) shr 16)
        tmp = s16((lTmp2 shl exp) shr 16)
        val fac = if (tmp > 0) div16by16(tmp, ener) else 0
        val gain1 = s16(32767 - fac)
        var gain2 = multInt16(gain1, 20480)
        gain2 = shlInt16(gain2, 1)
        tmp = if (vadHist > 0) s16(gain2 - 1) else s16(gain1 - 1)
        if (tmp != 0) tmp = s16(tmp + 1)
        if (tmp < 3277) tmp = 3277
        if (nbBits >= NBBITS_24K && bfi == 0) {
            val corr = AmrWbTables.HP_GAIN[prmsGain].toInt()
            for (i in 0 until L_SUBFR16K) hf[i] = s16(multInt16(hf[i], corr) shl 1)
        } else {
            for (i in 0 until L_SUBFR16K) hf[i] = multInt16(hf[i], tmp)
        }
        if (nbBits <= NBBITS_7K && newDtxState == SPEECH) {
            isfExtrapolation(hfIsf)
            ispAz(hfIsf, 0, hfA, 0, M16K, false)
            weightLpc(hfA, 0, ap, 29491, M16K)
            wbSynFilt(ap, M16K, hf, L_SUBFR16K, memSynHf, 0)
        } else {
            weightLpc(aqAll, aqAt, ap, 19661, M)
            wbSynFilt(ap, M, hf, L_SUBFR16K, memSynHf, M16K - M)
        }
        bandPass6k7k(hf, L_SUBFR16K)
        if (nbBits >= NBBITS_24K) lowPass7k(hf, L_SUBFR16K)
        for (i in 0 until L_SUBFR16K) synth[outAt + i] = addInt16(synth[outAt + i], hf[i])
    }

    /** `Syn_filt_32` writing from [sigAt] in [sigHi] and [sigLo], whose history is before it. */
    private fun synFilt32(a: IntArray, aAt: Int, m: Int, exc: IntArray, excAt: Int, qNew: Int, sigHi: IntArray, sigLo: IntArray, sigAt: Int, lg: Int) {
        val a0 = 9 - qNew
        for (i in 0 until (lg shr 1)) {
            val n = sigAt + (i shl 1)
            var l3 = 0
            var l4 = 0
            var l1 = sigLo[n - 1] * a[aAt + 1]
            var l2 = sigHi[n - 1] * a[aAt + 1]
            var k = 2
            while (k < m) {
                l1 += sigLo[n - 1 - k] * a[aAt + k + 1]
                l2 += sigHi[n - 1 - k] * a[aAt + k + 1]
                l1 += sigLo[n - k] * a[aAt + k]
                l2 += sigHi[n - k] * a[aAt + k]
                l3 += sigLo[n - k] * a[aAt + k + 1]
                l4 += sigHi[n - k] * a[aAt + k + 1]
                l3 += sigLo[n + 1 - k] * a[aAt + k]
                l4 += sigHi[n + 1 - k] * a[aAt + k]
                k += 2
            }
            l1 = -(l1 + sigLo[n - k] * a[aAt + k])
            l3 += sigLo[n + 1 - k] * a[aAt + k]
            l2 += sigHi[n - k] * a[aAt + k]
            l4 += sigHi[n + 1 - k] * a[aAt + k]
            l1 = l1 shr 11
            l1 += exc[excAt + (i shl 1)] shl a0
            l1 -= l2 shl 1
            l1 = shlInt32(l1, 3)
            sigHi[n] = s16(l1 shr 16)
            l4 += s16(l1 shr 16) * a[aAt + 1]
            sigLo[n] = s16((l1 shr 4) - ((l1 shr 16) shl 12))
            l3 += sigLo[n] * a[aAt + 1]
            l3 = -l3 shr 11
            l3 += exc[excAt + (i shl 1) + 1] shl a0
            l3 -= l4 shl 1
            l3 = shlInt32(l3, 3)
            sigHi[n + 1] = s16(l3 shr 16)
            sigLo[n + 1] = s16((l3 shr 4) - (sigHi[n + 1] shl 12))
        }
    }

    private fun deemphasis32(xHi: IntArray, xLo: IntArray, at: Int, y: IntArray, mu: Int, len: Int) {
        var l = xHi[at] shl 16
        l += xLo[at] shl 4
        l = shlInt32(l, 3)
        l += memDeemph * mu
        l = shlInt32(l, 1)
        y[0] = round(l)
        var lo = xLo[at + 1]
        var hi = xHi[at + 1]
        var i = 1
        while (i < len - 1) {
            l = hi shl 16
            l += lo shl 4
            l = shlInt32(l, 3)
            l += y[i - 1] * mu
            l = shlInt32(l, 1)
            y[i] = round(l)
            lo = xLo[at + i + 1]
            hi = xHi[at + i + 1]
            i++
        }
        l = hi shl 16
        l += lo shl 4
        l = shlInt32(l, 3)
        l += y[i - 1] * mu
        l = shlInt32(l, 1)
        y[i] = round(l)
        memDeemph = y[len - 1]
    }

    private fun highpass50(signal: IntArray, lg: Int) {
        val mem = memSigOut
        var y2Hi = mem[0]
        var y2Lo = mem[1]
        var y1Hi = mem[2]
        var y1Lo = mem[3]
        var x0 = mem[4]
        var x1 = mem[5]
        for (i in 0 until lg) {
            var l1 = y1Lo * 16211 + 8192
            l1 += y2Lo * -8021
            var l2 = y1Hi * 32422
            l2 += y2Hi * -16042
            val x2 = x1
            x1 = x0
            x0 = signal[i]
            l2 += x2 * 8106
            l2 += x1 * -16212
            l2 += x0 * 8106
            l1 = ((l1 shr 14) + l2) shl 2
            y2Hi = y1Hi
            y2Lo = y1Lo
            y1Hi = s16(l1 shr 16)
            y1Lo = s16((l1 - (y1Hi shl 16)) shr 1)
            signal[i] = shl1Round(l1)
        }
        mem[0] = y2Hi; mem[1] = y2Lo; mem[2] = y1Hi; mem[3] = y1Lo; mem[4] = x0; mem[5] = x1
    }

    private fun highpass400(signal: IntArray, lg: Int) {
        val mem = memHp400
        var y2Hi = mem[0]
        var y2Lo = mem[1]
        var y1Hi = mem[2]
        var y1Lo = mem[3]
        var x0 = mem[4]
        var x1 = mem[5]
        for (i in 0 until lg) {
            var l1 = y1Lo * 29280 + 8192
            var l2 = y1Hi * 29280
            l1 += y2Lo * -14160
            l2 += y2Hi * -14160
            val x2 = x1
            x1 = x0
            x0 = signal[i]
            l2 += x2 * 915
            l2 += x1 * -1830
            l2 += x0 * 915
            l1 = (l1 shr 13) + (l2 shl 2)
            y2Hi = y1Hi
            y2Lo = y1Lo
            signal[i] = s16((l1 + 0x00008000) shr 16)
            y1Hi = s16(l1 shr 16)
            y1Lo = s16((l1 - (y1Hi shl 16)) shr 1)
        }
        mem[0] = y2Hi; mem[1] = y2Lo; mem[2] = y1Hi; mem[3] = y1Lo; mem[4] = x0; mem[5] = x1
    }

    private fun oversample(sig12k8: IntArray, lg: Int, out: IntArray, outAt: Int) {
        val signal = scratch
        memOversamp.copyInto(signal, 0, 0, 2 * NB_COEF_UP)
        sig12k8.copyInto(signal, 2 * NB_COEF_UP, 0, lg)
        val lgUp = lg + (lg shr 2)
        // AmrWbUp_samp
        val sigD = NB_COEF_UP
        var frac = 1
        val fir = AmrWbTables.FIR_UP
        for (j in 0 until lgUp) {
            val i = (j * INV_FAC5) shr 13
            frac--
            if (frac != 0) {
                val f = ((FAC5 - 1) - frac) * 24
                var x = sigD + i - N_LOOP_COEF_UP - (N_LOOP_COEF_UP shl 1) + 1
                var sum = 0x00002000
                for (k in 0 until 24) sum += signal[x++] * fir[f + k]
                sum = shlInt32(sum, 2)
                out[outAt + j] = s16(sum shr 16)
            } else {
                out[outAt + j] = signal[sigD + i + 12 - NB_COEF_UP]
                frac = FAC5
            }
        }
        signal.copyInto(memOversamp, 0, lg, lg + 2 * NB_COEF_UP)
    }

    /** `wb_syn_filt` on [x] in place, with history in [mem] from [memAt]. */
    private fun wbSynFilt(a: IntArray, m: Int, x: IntArray, lg: Int, mem: IntArray, memAt: Int) {
        val yBuf = scratch
        mem.copyInto(yBuf, 0, memAt, memAt + m)
        val yy = m
        for (i in 0 until (lg shr 2)) {
            val b = i shl 2
            var l1 = -(x[b] shl 11)
            var l2 = -(x[b + 1] shl 11)
            var l3 = -(x[b + 2] shl 11)
            var l4 = -(x[b + 3] shl 11)
            l1 += yBuf[yy + b - 3] * a[3]
            l2 += yBuf[yy + b - 2] * a[3]
            l1 += yBuf[yy + b - 2] * a[2]
            l2 += yBuf[yy + b - 1] * a[2]
            l1 += yBuf[yy + b - 1] * a[1]
            var j = 4
            while (j < m) {
                l1 += yBuf[yy + b - 1 - j] * a[j + 1]
                l2 += yBuf[yy + b - j] * a[j + 1]
                l1 += yBuf[yy + b - j] * a[j]
                l2 += yBuf[yy + b + 1 - j] * a[j]
                l3 += yBuf[yy + b + 1 - j] * a[j + 1]
                l4 += yBuf[yy + b + 2 - j] * a[j + 1]
                l3 += yBuf[yy + b + 2 - j] * a[j]
                l4 += yBuf[yy + b + 3 - j] * a[j]
                j += 2
            }
            l1 += yBuf[yy + b - j] * a[j]
            l2 += yBuf[yy + b + 1 - j] * a[j]
            l3 += yBuf[yy + b + 2 - j] * a[j]
            l4 += yBuf[yy + b + 3 - j] * a[j]
            l1 = shlInt32(l1, 4)
            yBuf[yy + b] = round(-l1)
            x[b] = yBuf[yy + b]
            l2 += yBuf[yy + b] * a[1]
            l2 = shlInt32(l2, 4)
            yBuf[yy + b + 1] = round(-l2)
            x[b + 1] = yBuf[yy + b + 1]
            l3 += yBuf[yy + b - 1] * a[3]
            l4 += yBuf[yy + b] * a[3]
            l3 += yBuf[yy + b] * a[2]
            l4 += yBuf[yy + b + 1] * a[2]
            l3 += yBuf[yy + b + 1] * a[1]
            l3 = shlInt32(l3, 4)
            yBuf[yy + b + 2] = round(-l3)
            x[b + 2] = yBuf[yy + b + 2]
            l4 += yBuf[yy + b + 2] * a[1]
            l4 = shlInt32(l4, 4)
            yBuf[yy + b + 3] = round(-l4)
            x[b + 3] = yBuf[yy + b + 3]
        }
        x.copyInto(mem, memAt, lg - m, lg)
    }

    private fun bandPass6k7k(signal: IntArray, lg: Int) {
        val x = scratch
        val fir = AmrWbTables.FIR_6K_7K
        memHf.copyInto(x, 0, 0, L_FIR)
        for (i in 0 until (lg shr 2)) {
            val b = i shl 2
            x[b + L_FIR] = signal[b] shr 2
            x[b + L_FIR + 1] = signal[b + 1] shr 2
            x[b + L_FIR + 2] = signal[b + 2] shr 2
            x[b + L_FIR + 3] = signal[b + 3] shr 2
            var l1 = 0x4000 - (x[b + L_FIR] shl 5)
            var l2 = 0x4000 - (x[b + L_FIR + 1] shl 5)
            var l3 = 0x4000 - (x[b + L_FIR + 2] shl 5)
            var l4 = 0x4000 - (x[b + L_FIR + 3] shl 5)
            l1 -= x[b] shl 5
            l2 -= x[b + 1] shl 5
            l3 -= x[b + 2] shl 5
            l4 -= x[b + 3] shl 5
            var j = 1
            while (j < L_FIR - 1) {
                var t1 = x[b + j]
                var t2 = x[b + j + 1]
                val t3 = x[b + j + 2]
                l1 += t1 * fir[j]
                l2 += t2 * fir[j]
                l1 += t2 * fir[j + 1]
                l2 += t3 * fir[j + 1]
                l3 += t3 * fir[j]
                l1 += t3 * fir[j + 2]
                t1 = x[b + j + 3]
                t2 = x[b + j + 4]
                l2 += t1 * fir[j + 2]
                l4 += t1 * fir[j]
                l3 += t1 * fir[j + 1]
                l1 += t1 * fir[j + 3]
                l2 += t2 * fir[j + 3]
                l4 += t2 * fir[j + 1]
                l3 += t2 * fir[j + 2]
                t1 = x[b + j + 5]
                t2 = x[b + j + 6]
                l4 += t1 * fir[j + 2]
                l3 += t1 * fir[j + 3]
                l4 += t2 * fir[j + 3]
                j += 4
            }
            l1 += x[b + j] * fir[L_FIR - 1]
            l2 += x[b + j + 1] * fir[L_FIR - 1]
            l3 += x[b + j + 2] * fir[L_FIR - 1]
            l4 += x[b + j + 3] * fir[L_FIR - 1]
            signal[b] = s16(l1 shr 15)
            signal[b + 1] = s16(l2 shr 15)
            signal[b + 2] = s16(l3 shr 15)
            signal[b + 3] = s16(l4 shr 15)
        }
        x.copyInto(memHf, 0, lg, lg + L_FIR)
    }

    private fun lowPass7k(signal: IntArray, lg: Int) {
        val x = scratch
        val fir = AmrWbTables.FIR_7K
        memHf3.copyInto(x, 0, 0, L_FIR)
        for (i in 0 until (lg shr 2)) {
            val b = i shl 2
            x[b + L_FIR] = signal[b]
            x[b + L_FIR + 1] = signal[b + 1]
            x[b + L_FIR + 2] = signal[b + 2]
            x[b + L_FIR + 3] = signal[b + 3]
            var l1 = s16(x[b] + signal[b]) * fir[0] + 0x4000
            var l2 = s16(x[b + 1] + signal[b + 1]) * fir[0] + 0x4000
            var l3 = s16(x[b + 2] + signal[b + 2]) * fir[0] + 0x4000
            var l4 = s16(x[b + 3] + signal[b + 3]) * fir[0] + 0x4000
            var j = 1
            while (j < L_FIR - 1) {
                var t1 = x[b + j]
                var t2 = x[b + j + 1]
                val t3 = x[b + j + 2]
                l1 += t1 * fir[j]
                l2 += t2 * fir[j]
                l1 += t2 * fir[j + 1]
                l2 += t3 * fir[j + 1]
                l3 += t3 * fir[j]
                l1 += t3 * fir[j + 2]
                t1 = x[b + j + 3]
                t2 = x[b + j + 4]
                l2 += t1 * fir[j + 2]
                l4 += t1 * fir[j]
                l3 += t1 * fir[j + 1]
                l1 += t1 * fir[j + 3]
                l2 += t2 * fir[j + 3]
                l4 += t2 * fir[j + 1]
                l3 += t2 * fir[j + 2]
                t1 = x[b + j + 5]
                t2 = x[b + j + 6]
                l4 += t1 * fir[j + 2]
                l3 += t1 * fir[j + 3]
                l4 += t2 * fir[j + 3]
                j += 4
            }
            l1 += x[b + j] * fir[j]
            l2 += x[b + j + 1] * fir[j]
            l3 += x[b + j + 2] * fir[j]
            l4 += x[b + j + 3] * fir[j]
            signal[b] = s16(l1 shr 15)
            signal[b + 1] = s16(l2 shr 15)
            signal[b + 2] = s16(l3 shr 15)
            signal[b + 3] = s16(l4 shr 15)
        }
        x.copyInto(memHf3, 0, lg, lg + L_FIR)
    }

    // dtx_decoder_amr_wb.cpp

    private fun dtxReset() {
        sinceLastSid = 0
        trueSidPeriodInv = 1 shl 13
        logEn = 3500
        oldLogEn = 3500
        dtxSeeds[0] = RANDOM_INITSEED
        histPtr = 0
        for (i in 0 until M) {
            dtxIsf[i] = AmrWbTables.ISF_INIT[i].toInt()
            dtxIsfOld[i] = AmrWbTables.ISF_INIT[i].toInt()
        }
        for (k in 0 until DTX_HIST_SIZE) {
            for (i in 0 until M) isfHist[k * M + i] = AmrWbTables.ISF_INIT[i].toInt()
            logEnHist[k] = logEn
        }
        dtxHangoverCount = DTX_HANG_CONST
        decAnaElapsedCount = 32767
        sidFrame = 0
        validData = 0
        dtxHangoverAdded = 0
        dtxGlobalState = SPEECH
        dataUpdated = 0
        dtxSeeds[1] = RANDOM_INITSEED
        cnDith = 0
    }

    private fun dtxDec(newState: Int) {
        if (dtxHangoverAdded != 0 && sidFrame != 0) {
            var ptr = histPtr + 1
            if (ptr == DTX_HIST_SIZE) ptr = 0
            isfHist.copyInto(isfHist, ptr * M, histPtr * M, histPtr * M + M)
            logEnHist[ptr] = logEnHist[histPtr]
            logEn = 0
            val lIsf = IntArray(M)
            for (i in 0 until DTX_HIST_SIZE) {
                logEn = addInt16(logEn, logEnHist[i])
                for (j in 0 until M) lIsf[j] = addInt32(lIsf[j], isfHist[i * M + j])
            }
            logEn = logEn shr 1
            logEn = s16(logEn + 1024)
            if (logEn < 0) logEn = 0
            for (j in 0 until M) dtxIsf[j] = s16(lIsf[j] shr 3)
        }
        if (sidFrame != 0) {
            dtxIsf.copyInto(dtxIsfOld)
            oldLogEn = logEn
            if (validData != 0) {
                var tmpIntLength = sinceLastSid
                if (tmpIntLength > 32) tmpIntLength = 32
                trueSidPeriodInv = if (tmpIntLength >= 2) div16by16(1 shl 10, shlInt16(tmpIntLength, 10)) else 1 shl 14
                val sid = IntArray(5)
                sid[0] = serialParm(6)
                sid[1] = serialParm(6)
                sid[2] = serialParm(6)
                sid[3] = serialParm(5)
                sid[4] = serialParm(5)
                disfNs(sid, dtxIsf)
                val logEnIndex = serialParm(6)
                cnDith = serialParm1()
                logEn = shlInt16(logEnIndex, 15 - 6)
                logEn = multInt16(logEn, 12483)
                if (dataUpdated == 0 || dtxGlobalState == SPEECH) {
                    dtxIsf.copyInto(dtxIsfOld)
                    oldLogEn = logEn
                }
            }
        }
        if (sidFrame != 0 && validData != 0) sinceLastSid = 0
        var intFac = shlInt16(sinceLastSid, 10)
        intFac = multInt16(intFac, trueSidPeriodInv)
        if (intFac > 1024) intFac = 1024
        intFac = shlInt16(intFac, 4)
        val lLogEn = IntArray(1)
        lLogEn[0] = mul16(intFac, logEn)
        for (i in 0 until M) isf[i] = multInt16(intFac, dtxIsf[i])
        intFac = s16(16384 - intFac)
        lLogEn[0] = mac16(lLogEn[0], intFac, oldLogEn)
        for (i in 0 until M) {
            isf[i] = addInt16(isf[i], multInt16(intFac, dtxIsfOld[i]))
            isf[i] = shlInt16(isf[i], 1)
        }
        if (cnDith != 0) cnDithering(isf, lLogEn)
        var lLogEnInt = lLogEn[0] shr 9
        var logEnIntE = s16(lLogEnInt shr 16)
        val logEnIntM = s16(subInt32(lLogEnInt, logEnIntE shl 16) shr 1)
        logEnIntE = s16(logEnIntE + 15)
        var level32 = powerOf2(logEnIntE, logEnIntM)
        var exp0 = normalize(level32)
        level32 = level32 shl exp0
        exp0 = 15 - exp0
        val level = s16(level32 shr 16)
        for (i in 0 until L_FRAME) exc2[i] = noiseGen(dtxSeeds, 0) shr 4
        val e = IntArray(1)
        val fe = IntArray(2)
        fe[0] = dotProduct12(exc2, 0, exc2, 0, L_FRAME, e)
        fe[1] = e[0]
        oneOvSqrtNorm(fe)
        var gain = s16(fe[0] shr 16)
        gain = multInt16(level, gain)
        val exp = s16(fe[1] + exp0 + 4)
        for (i in 0 until L_FRAME) exc2[i] = shlInt16(multInt16(exc2[i], gain), exp)
        if (newState == DTX_MUTE) {
            var tmpIntLength = sinceLastSid
            if (tmpIntLength > 32) tmpIntLength = 32
            trueSidPeriodInv = div16by16(1 shl 10, shlInt16(tmpIntLength, 10))
            sinceLastSid = 0
            oldLogEn = logEn
            logEn = s16(logEn - 64)
            if (logEn < 0) logEn = 0
        }
        if (sidFrame != 0 && (validData != 0 || (validData == 0 && dtxHangoverAdded != 0))) {
            sinceLastSid = 0
            dataUpdated = 1
        }
    }

    private fun disfNs(indice: IntArray, isfQ: IntArray) {
        val t = AmrWbTables
        isfQ[0] = t.DICO1_ISF_NOISE[indice[0] shl 1].toInt()
        isfQ[1] = t.DICO1_ISF_NOISE[(indice[0] shl 1) + 1].toInt()
        for (i in 0 until 3) {
            isfQ[i + 2] = t.DICO2_ISF_NOISE[(indice[1] shl 1) + indice[1] + i].toInt()
            isfQ[i + 5] = t.DICO3_ISF_NOISE[(indice[2] shl 1) + indice[2] + i].toInt()
        }
        for (i in 0 until 4) {
            isfQ[i + 8] = t.DICO4_ISF_NOISE[(indice[3] shl 2) + i].toInt()
            isfQ[i + 12] = t.DICO5_ISF_NOISE[(indice[4] shl 2) + i].toInt()
        }
        for (i in 0 until ORDER) isfQ[i] = addInt16(isfQ[i], t.MEAN_ISF_NOISE[i].toInt())
        reorderIsf(isfQ, ISF_GAP, ORDER)
    }

    private fun cnDithering(isfIo: IntArray, lLogEn: IntArray) {
        var randDith = noiseGen(dtxSeeds, 1) shr 1
        var randDith2 = noiseGen(dtxSeeds, 1) shr 1
        randDith = s16(randDith + randDith2)
        lLogEn[0] = addInt32(lLogEn[0], mul16(randDith, GAIN_FACTOR))
        if (lLogEn[0] < 0) lLogEn[0] = 0
        var ditherFac = ISF_FACTOR_LOW
        randDith = noiseGen(dtxSeeds, 1) shr 1
        randDith2 = noiseGen(dtxSeeds, 1) shr 1
        randDith = s16(randDith + randDith2)
        var temp = addInt16(isfIo[0], multInt16R(randDith, ditherFac))
        isfIo[0] = if (temp < ISF_GAP) ISF_GAP else temp
        for (i in 1 until M - 1) {
            ditherFac = addInt16(ditherFac, ISF_FACTOR_STEP)
            randDith = noiseGen(dtxSeeds, 1) shr 1
            randDith2 = noiseGen(dtxSeeds, 1) shr 1
            randDith = s16(randDith + randDith2)
            temp = addInt16(isfIo[i], multInt16R(randDith, ditherFac))
            val temp1 = subInt16(temp, isfIo[i - 1])
            isfIo[i] = if (temp1 < ISF_DITH_GAP) s16(isfIo[i - 1] + ISF_DITH_GAP) else temp
        }
        if (isfIo[M - 2] > 16384) isfIo[M - 2] = 16384
    }

    private fun dtxActivityUpdate(isfIn: IntArray, exc: IntArray, excAt: Int) {
        histPtr++
        if (histPtr == DTX_HIST_SIZE) histPtr = 0
        isfIn.copyInto(isfHist, histPtr * M, 0, M)
        var frameEn = 0
        for (i in 0 until L_FRAME) frameEn = mac16(frameEn, exc[excAt + i], exc[excAt + i])
        frameEn = frameEn shr 1
        val ef = IntArray(2)
        log2(frameEn, ef)
        var logEnValue = shlInt16(ef[0], 7)
        logEnValue = s16(logEnValue + (ef[1] shr 8))
        logEnValue = s16(logEnValue - 1024)
        logEnHist[histPtr] = logEnValue
    }

    private fun rxDtxHandler(frameType: Int): Int {
        var newState: Int
        if (frameType == RX_SID_FIRST || frameType == RX_SID_UPDATE || frameType == RX_SID_BAD ||
            ((dtxGlobalState == DTX || dtxGlobalState == DTX_MUTE) &&
                (frameType == RX_NO_DATA || frameType == RX_SPEECH_BAD || frameType == RX_SPEECH_LOST))
        ) {
            newState = DTX
            if (dtxGlobalState == DTX_MUTE &&
                (frameType == RX_SID_BAD || frameType == RX_SID_FIRST || frameType == RX_SPEECH_LOST || frameType == RX_NO_DATA)
            ) {
                newState = DTX_MUTE
            }
            sinceLastSid = addInt16(sinceLastSid, 1)
            if (sinceLastSid > DTX_MAX_EMPTY_THRESH) newState = DTX_MUTE
        } else {
            newState = SPEECH
            sinceLastSid = 0
        }
        if (dataUpdated == 0 && frameType == RX_SID_UPDATE) decAnaElapsedCount = 0
        decAnaElapsedCount = addInt16(decAnaElapsedCount, 1)
        dtxHangoverAdded = 0
        val encState = if (frameType == RX_SID_FIRST || frameType == RX_SID_UPDATE || frameType == RX_SID_BAD ||
            frameType == RX_NO_DATA
        ) DTX else SPEECH
        if (encState == SPEECH) {
            dtxHangoverCount = DTX_HANG_CONST
        } else if (decAnaElapsedCount > DTX_ELAPSED_FRAMES_THRESH) {
            dtxHangoverAdded = 1
            decAnaElapsedCount = 0
            dtxHangoverCount = 0
        } else if (dtxHangoverCount == 0) {
            decAnaElapsedCount = 0
        } else {
            dtxHangoverCount--
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

    companion object {
        const val AMR_WB_PCM_FRAME = 320
        private const val KAMRWB_NB_BITS_MAX = 477
        private const val L_FRAME = 256
        private const val L_SUBFR16K = 80
        private const val L_SUBFR = 64
        private const val NB_SUBFR = 4
        private const val M = 16
        private const val M16K = 20
        private const val NC16K = M16K / 2
        private const val ORDER = 16
        private const val L_FILT16K = 15
        private const val L_FILT = 12
        private const val PIT_SHARP = 27853
        private const val PIT_MIN = 34
        private const val PIT_FR2 = 128
        private const val PIT_FR1_9B = 160
        private const val PIT_FR1_8B = 92
        private const val PIT_MAX = 231
        private const val L_INTERPOL = 16 + 1
        private const val PREEMPH_FAC = 22282
        private const val Q_MAX = 8
        private const val RANDOM_INITSEED = 21845
        private const val L_MEANBUF = 3
        private const val MODE_7K = 0
        private const val MODE_24K = 8
        private const val MRDTX = 9
        private const val MRSID = 9
        private const val EHF_MASK = 0x0008
        private const val NBBITS_7K = 132
        private const val NBBITS_9K = 177
        private const val NBBITS_12K = 253
        private const val NBBITS_14K = 285
        private const val NBBITS_16K = 317
        private const val NBBITS_18K = 365
        private const val NBBITS_20K = 397
        private const val NBBITS_24K = 477
        private val COMPRESSED = intArrayOf(132, 177, 253, 285, 317, 365, 397, 461, 477, 35)

        private const val RX_SPEECH_GOOD = 0
        private const val RX_SPEECH_PROBABLY_DEGRADED = 1
        private const val RX_SPEECH_LOST = 2
        private const val RX_SPEECH_BAD = 3
        private const val RX_SID_FIRST = 4
        private const val RX_SID_UPDATE = 5
        private const val RX_SID_BAD = 6
        private const val RX_NO_DATA = 7
        private const val SPEECH = 0
        private const val DTX = 1
        private const val DTX_MUTE = 2

        private const val DTX_MAX_EMPTY_THRESH = 50
        private const val DTX_HIST_SIZE = 8
        private const val DTX_ELAPSED_FRAMES_THRESH = 24 + 7 - 1
        private const val DTX_HANG_CONST = 7
        private const val ISF_GAP = 128
        private const val ISF_DITH_GAP = 448
        private const val ISF_FACTOR_LOW = 256
        private const val ISF_FACTOR_STEP = 2
        private const val GAIN_FACTOR = 75

        private const val MU = 10923
        private const val ALPHA = 29491
        private const val ONE_ALPHA = 32768 - ALPHA
        private const val MEAN_ENER = 30
        private const val L_LTPHIST = 5
        private const val ONE_PER_3 = 10923
        private const val ONE_PER_LTPHIST = 6554
        private const val PITCH_0_9 = 14746
        private const val PITCH_0_6 = 9830
        private const val INV_LENGTH = 2731
        private const val UP_SAMP = 4
        private const val L_INTERPOL2 = 16
        private const val NB_POS = 16
        private const val L_FIR = 30
        private const val FAC5 = 5
        private const val INV_FAC5 = 6554
        private const val NB_COEF_UP = 12
        private const val N_LOOP_COEF_UP = 4
        private const val DHF_PARMS_MAX = 32

        // Where dec_gain2_amr_wb keeps each part of its memory.
        private const val PAST_GAIN_PIT = 4
        private const val PAST_GAIN_CODE = 5
        private const val PREV_GC = 6
        private const val PBUF = 7
        private const val GBUF = 12
        private const val PBUF2 = 17

        private val SORTING: Array<ShortArray?> = arrayOf(
            AmrWbFrameTables.SORT_660, AmrWbFrameTables.SORT_885, AmrWbFrameTables.SORT_1265, AmrWbFrameTables.SORT_1425,
            AmrWbFrameTables.SORT_1585, AmrWbFrameTables.SORT_1825, AmrWbFrameTables.SORT_1985, AmrWbFrameTables.SORT_2305,
            AmrWbFrameTables.SORT_2385, AmrWbFrameTables.SORT_SID, null, null, null, null, null, null,
        )
        private val DHF: Array<ShortArray> = arrayOf(
            AmrWbFrameTables.DHF_7K, AmrWbFrameTables.DHF_9K, AmrWbFrameTables.DHF_12K, AmrWbFrameTables.DHF_14K,
            AmrWbFrameTables.DHF_16K, AmrWbFrameTables.DHF_18K, AmrWbFrameTables.DHF_20K, AmrWbFrameTables.DHF_23K,
            AmrWbFrameTables.DHF_24K,
        )
    }
}

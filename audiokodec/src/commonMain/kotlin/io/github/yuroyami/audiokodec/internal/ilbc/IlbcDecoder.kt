/*
 * Translated from WebRTC's iLBC decoder, Copyright (c) 2011 The WebRTC project
 * authors, under the BSD licence reproduced in NOTICE, as packaged by libilbc
 * 3.0.4. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.ilbc

import io.github.yuroyami.audiokodec.internal.ilbc.IlbcOps.absW32
import io.github.yuroyami.audiokodec.internal.ilbc.IlbcOps.crossCorrelation
import io.github.yuroyami.audiokodec.internal.ilbc.IlbcOps.divW32W16
import io.github.yuroyami.audiokodec.internal.ilbc.IlbcOps.dotProductWithScale
import io.github.yuroyami.audiokodec.internal.ilbc.IlbcOps.filterARFastQ12
import io.github.yuroyami.audiokodec.internal.ilbc.IlbcOps.filterMAFastQ12
import io.github.yuroyami.audiokodec.internal.ilbc.IlbcOps.maxAbsW16
import io.github.yuroyami.audiokodec.internal.ilbc.IlbcOps.maxAbsW32
import io.github.yuroyami.audiokodec.internal.ilbc.IlbcOps.maxIndexW32
import io.github.yuroyami.audiokodec.internal.ilbc.IlbcOps.normW32
import io.github.yuroyami.audiokodec.internal.ilbc.IlbcOps.s16
import io.github.yuroyami.audiokodec.internal.ilbc.IlbcOps.shiftW32
import io.github.yuroyami.audiokodec.internal.ilbc.IlbcOps.sizeInBits
import io.github.yuroyami.audiokodec.internal.ilbc.IlbcOps.sqrtFloor
import io.github.yuroyami.audiokodec.internal.ilbc.IlbcTables as T

/**
 * Decodes iLBC frames, 20 ms of 8 kHz speech in 38 bytes or 30 ms in 50, as
 * WebRTC's fixed-point decoder does with its enhancer on, which is how
 * `WebRtcIlbcfix_DecoderInit` sets it up.
 *
 * Every sample is the reference's 16-bit integer. A frame whose last bit
 * marks it empty, or whose start state index is impossible, is concealed as
 * the reference conceals it, from the frames before.
 */
internal class IlbcDecoder(val mode: Int) {
    init {
        require(mode == 20 || mode == 30) { "iLBC frames are 20 or 30 ms, not $mode" }
    }

    val blockl: Int = if (mode == 30) BLOCKL_30MS else BLOCKL_20MS
    private val nsub = if (mode == 30) 6 else 4
    private val lpcN = if (mode == 30) 2 else 1
    val noOfBytes: Int = if (mode == 30) 50 else 38
    private val noOfWords = noOfBytes / 2
    private val stateShortLen = if (mode == 30) 58 else 57

    // The decoder instance of defines.h.
    private val syntMem = IntArray(ORDER)
    private val lsfdeqold = IntArray(ORDER)
    private var lastLag = 0
    private var consPLICount = 0
    private var prevEnhPl = 0
    private var perSquare = 0
    private var prevScale = 0
    private var prevPLI = 0
    private var prevLag = 0
    private val prevLpc = IntArray(ORDER + 1)
    private val prevResidual = IntArray(NSUB_MAX * SUBL)
    private var seed = 0
    private val oldSyntdenum = IntArray((ORDER + 1) * NSUB_MAX)
    private val hpimemx = IntArray(2)
    private val hpimemy = IntArray(4)
    private val enhBuf = IntArray(ENH_BUFL + 3)
    private val enhPeriod = IntArray(ENH_NBLOCKS_TOT)

    // The parameters of one frame, iLBC_bits.
    private val lsf = IntArray(6)
    private val cbIndex = IntArray(15)
    private val gainIndex = IntArray(15)
    private var idxForMax = 0
    private var stateFirst = 0
    private val idxVec = IntArray(58)
    private var startIdx = 0
    private val words = IntArray(25)

    // Working buffers, the stack arrays of the C functions.
    private val decresidual = IntArray(BLOCKL_MAX)
    private val plcResidual = IntArray(BLOCKL_MAX + ORDER)
    private val syntdenum = IntArray(NSUB_MAX * (ORDER + 1))
    private val plcLpc = IntArray(ORDER + 1)
    private val lsfdeq = IntArray(ORDER * 2)
    private val lp = IntArray(ORDER + 1)
    private val lsftmp = IntArray(ORDER)
    private val lsp = IntArray(ORDER)
    private val f = Array(2) { IntArray(6) }
    private val gain = IntArray(3)
    private val cbvec = Array(3) { IntArray(SUBL) }
    private val tempbuff2 = IntArray(SUBL + 5)
    private val cbVecTmp = IntArray(4)
    private val numerator = IntArray(ORDER + 1)
    private val sampleValVec = IntArray(2 * 58 + ORDER)
    private val sampleMaVec = IntArray(2 * 58 + ORDER)
    private val randvec = IntArray(BLOCKL_MAX)
    private val downsampled = IntArray((BLOCKL_MAX + 120) shr 1)
    private val corr32 = IntArray(50)
    private val corrmax = IntArray(3)
    private val corr16 = IntArray(3)
    private val en16 = IntArray(3)
    private val totsh = IntArray(3)
    private val lagmax = IntArray(3)
    private val surround = IntArray(ENH_BLOCKL)
    private val lagBlock = IntArray(2 * ENH_HL + 1)
    private val blockStartPos = IntArray(2 * ENH_HL + 1)
    private val plocs2 = IntArray(ENH_NBLOCKS_TOT)
    private val corrVecUps = IntArray(ENH_CORRDIM * 4)
    private val corrVecTemp = IntArray(ENH_CORRDIM)
    private val vect = IntArray(ENH_VECTL)
    private val corrVec = IntArray(ENH_CORRDIM)
    private val filt = IntArray(7)

    init {
        reset()
    }

    /** `WebRtcIlbcfix_InitDecode`, with the enhancer on. */
    fun reset() {
        T.LSF_MEAN.copyInto(lsfdeqold)
        syntMem.fill(0)
        oldSyntdenum.fill(0)
        for (i in 0 until NSUB_MAX) oldSyntdenum[i * (ORDER + 1)] = 4096
        lastLag = 20
        consPLICount = 0
        prevPLI = 0
        perSquare = 0
        prevLag = 120
        prevLpc[0] = 4096
        prevLpc.fill(0, 1, ORDER + 1)
        prevResidual.fill(0, 0, BLOCKL_MAX)
        seed = 777
        hpimemx.fill(0)
        hpimemy.fill(0)
        enhBuf.fill(0)
        enhPeriod.fill(160)
        prevEnhPl = 0
    }

    /**
     * Decodes the frame of [noOfBytes] bytes at [offset] into [blockl]
     * samples at [outOffset], and returns false when the frame's codebook
     * indices cannot be decoded, after which the decoder has been reset, as
     * the reference resets it.
     */
    fun decode(frame: ByteArray, offset: Int, out: ShortArray, outOffset: Int): Boolean {
        for (k in 0 until noOfWords) {
            words[k] = ((frame[offset + 2 * k].toInt() and 0xFF) shl 8) or (frame[offset + 2 * k + 1].toInt() and 0xFF)
        }
        var good = true
        val lastBit = unpackBits()
        if (startIdx < 1) good = false
        if (mode == 20 && startIdx > 3) good = false
        if (mode == 30 && startIdx > 5) good = false
        if (lastBit == 1) good = false
        if (good) {
            indexConvDec()
            simpleLsfDeQ()
            lsfCheck()
            decoderInterpolateLsp()
            if (!decodeResidual()) {
                reset()
                return false
            }
            doThePlc(0, (ORDER + 1) * (nsub - 1), lastLag)
            plcResidual.copyInto(decresidual, 0, 0, blockl)
        } else {
            doThePlc(1, 0, lastLag)
            plcResidual.copyInto(decresidual, 0, 0, blockl)
            for (i in 0 until nsub) plcLpc.copyInto(syntdenum, i * (ORDER + 1), 0, ORDER + 1)
        }

        // The enhancer, then synthesis filtering into data, plcResidual from ORDER on.
        val data = ORDER
        lastLag = enhancerInterface(plcResidual, data)
        syntMem.copyInto(plcResidual, data - ORDER)
        if (mode == 20) {
            // The enhancer delays the signal by 40 samples.
            filterARFastQ12(plcResidual, data, plcResidual, data, oldSyntdenum, (nsub - 1) * (ORDER + 1), ORDER + 1, SUBL)
            for (i in 1 until nsub) {
                filterARFastQ12(plcResidual, data + i * SUBL, plcResidual, data + i * SUBL, syntdenum, (i - 1) * (ORDER + 1), ORDER + 1, SUBL)
            }
        } else {
            // The enhancer delays the signal by 80 samples.
            for (i in 0 until 2) {
                filterARFastQ12(plcResidual, data + i * SUBL, plcResidual, data + i * SUBL, oldSyntdenum, (i + 4) * (ORDER + 1), ORDER + 1, SUBL)
            }
            for (i in 2 until nsub) {
                filterARFastQ12(plcResidual, data + i * SUBL, plcResidual, data + i * SUBL, syntdenum, (i - 2) * (ORDER + 1), ORDER + 1, SUBL)
            }
        }
        plcResidual.copyInto(syntMem, 0, data + blockl - ORDER, data + blockl)
        hpOutput(plcResidual, data, blockl)
        for (i in 0 until blockl) out[outOffset + i] = plcResidual[data + i].toShort()
        syntdenum.copyInto(oldSyntdenum, 0, 0, nsub * (ORDER + 1))
        prevEnhPl = if (good) 0 else 1
        return true
    }

    /**
     * Whether [decode] would refuse the frame at [offset], which happens only
     * when a good frame's codebook index for the vector next to the start
     * state lies past every codebook section. Checking first lets a caller
     * stop before the decoder resets itself part way through a run.
     */
    fun refuses(frame: ByteArray, offset: Int): Boolean {
        for (k in 0 until noOfWords) {
            words[k] = ((frame[offset + 2 * k].toInt() and 0xFF) shl 8) or (frame[offset + 2 * k + 1].toInt() and 0xFF)
        }
        val lastBit = unpackBits()
        if (startIdx < 1 || startIdx > (if (mode == 20) 3 else 5) || lastBit == 1) return false
        // getCbVec with cbveclen = diff and lMem = ST_MEM_L_TBL: past its first and filtered sections.
        val diff = STATE_LEN - stateShortLen
        val limit = 2 * (ST_MEM_L_TBL - diff + 1)
        return (0 until 3).any { cbIndex[it] >= limit }
    }

    // unpack_bits.c

    private fun w(p: Int): Int = words[p]

    private fun unpackBits(): Int {
        var p = 0
        var t: Int
        lsf[0] = w(p) shr 10
        lsf[1] = (w(p) shr 3) and 0x7F
        lsf[2] = (w(p) and 0x7) shl 4
        p++
        lsf[2] = lsf[2] or ((w(p) shr 12) and 0xF)
        if (mode == 20) {
            startIdx = (w(p) shr 10) and 0x3
            stateFirst = (w(p) shr 9) and 0x1
            idxForMax = (w(p) shr 3) and 0x3F
            cbIndex[0] = (w(p) and 0x7) shl 4
            p++
            cbIndex[0] = cbIndex[0] or ((w(p) shr 12) and 0xE)
            gainIndex[0] = (w(p) shr 8) and 0x18
            gainIndex[1] = (w(p) shr 7) and 0x8
            cbIndex[3] = (w(p) shr 2) and 0xFE
            gainIndex[3] = (w(p) shl 2) and 0x10
            gainIndex[4] = (w(p) shl 2) and 0x8
            gainIndex[6] = (w(p) shl 4) and 0x10
        } else {
            lsf[3] = (w(p) shr 6) and 0x3F
            lsf[4] = (w(p) shl 1) and 0x7E
            p++
            lsf[4] = lsf[4] or ((w(p) shr 15) and 0x1)
            lsf[5] = (w(p) shr 8) and 0x7F
            startIdx = (w(p) shr 5) and 0x7
            stateFirst = (w(p) shr 4) and 0x1
            idxForMax = (w(p) shl 2) and 0x3C
            p++
            idxForMax = idxForMax or ((w(p) shr 14) and 0x3)
            cbIndex[0] = (w(p) shr 7) and 0x78
            gainIndex[0] = (w(p) shr 5) and 0x10
            gainIndex[1] = (w(p) shr 5) and 0x8
            cbIndex[3] = (w(p)) and 0xFC
            gainIndex[3] = (w(p) shl 3) and 0x10
            gainIndex[4] = (w(p) shl 3) and 0x8
        }
        // Class 2 bits.
        p++
        t = 0
        for (k in 0 until 3) {
            for (i in 15 downTo 0) {
                idxVec[t] = (((w(p) shr i) shl 2) and 0x4)
                t++
            }
            p++
        }
        if (mode == 20) {
            for (i in 15 downTo 7) {
                idxVec[t] = (((w(p) shr i) shl 2) and 0x4)
                t++
            }
            gainIndex[1] = gainIndex[1] or ((w(p) shr 4) and 0x4)
            gainIndex[3] = gainIndex[3] or ((w(p) shr 2) and 0xC)
            gainIndex[4] = gainIndex[4] or ((w(p) shr 1) and 0x4)
            gainIndex[6] = gainIndex[6] or ((w(p) shl 1) and 0x8)
            gainIndex[7] = (w(p) shl 2) and 0xC
        } else {
            for (i in 15 downTo 6) {
                idxVec[t] = (((w(p) shr i) shl 2) and 0x4)
                t++
            }
            cbIndex[0] = cbIndex[0] or ((w(p) shr 3) and 0x6)
            gainIndex[0] = gainIndex[0] or ((w(p)) and 0x8)
            gainIndex[1] = gainIndex[1] or ((w(p)) and 0x4)
            cbIndex[3] = cbIndex[3] or ((w(p)) and 0x2)
            cbIndex[6] = (w(p) shl 7) and 0x80
            p++
            cbIndex[6] = cbIndex[6] or ((w(p) shr 9) and 0x7E)
            cbIndex[9] = (w(p) shr 2) and 0xFE
            cbIndex[12] = (w(p) shl 5) and 0xE0
            p++
            cbIndex[12] = cbIndex[12] or ((w(p) shr 11) and 0x1E)
            gainIndex[3] = gainIndex[3] or ((w(p) shr 8) and 0xC)
            gainIndex[4] = gainIndex[4] or ((w(p) shr 7) and 0x6)
            gainIndex[6] = (w(p) shr 3) and 0x18
            gainIndex[7] = (w(p) shr 2) and 0xC
            gainIndex[9] = (w(p) shl 1) and 0x10
            gainIndex[10] = (w(p) shl 1) and 0x8
            gainIndex[12] = (w(p) shl 3) and 0x10
            gainIndex[13] = (w(p) shl 3) and 0x8
        }
        p++
        // Class 3 bits.
        t = 0
        for (k in 0 until 7) {
            for (i in 14 downTo 0 step 2) {
                idxVec[t] = idxVec[t] or ((w(p) shr i) and 0x3)
                t++
            }
            p++
        }
        if (mode == 20) {
            idxVec[56] = idxVec[56] or ((w(p) shr 14) and 0x3)
            cbIndex[0] = cbIndex[0] or ((w(p) shr 13) and 0x1)
            cbIndex[1] = (w(p) shr 6) and 0x7F
            cbIndex[2] = (w(p) shl 1) and 0x7E
            p++
            cbIndex[2] = cbIndex[2] or ((w(p) shr 15) and 0x1)
            gainIndex[0] = gainIndex[0] or ((w(p) shr 12) and 0x7)
            gainIndex[1] = gainIndex[1] or ((w(p) shr 10) and 0x3)
            gainIndex[2] = (w(p) shr 7) and 0x7
            cbIndex[3] = cbIndex[3] or ((w(p) shr 6) and 0x1)
            cbIndex[4] = (w(p) shl 1) and 0x7E
            p++
            cbIndex[4] = cbIndex[4] or ((w(p) shr 15) and 0x1)
            cbIndex[5] = (w(p) shr 8) and 0x7F
            cbIndex[6] = (w(p)) and 0xFF
            p++
            cbIndex[7] = w(p) shr 8
            cbIndex[8] = w(p) and 0xFF
            p++
            gainIndex[3] = gainIndex[3] or ((w(p) shr 14) and 0x3)
            gainIndex[4] = gainIndex[4] or ((w(p) shr 12) and 0x3)
            gainIndex[5] = (w(p) shr 9) and 0x7
            gainIndex[6] = gainIndex[6] or ((w(p) shr 6) and 0x7)
            gainIndex[7] = gainIndex[7] or ((w(p) shr 4) and 0x3)
            gainIndex[8] = (w(p) shr 1) and 0x7
        } else {
            idxVec[56] = idxVec[56] or ((w(p) shr 14) and 0x3)
            idxVec[57] = idxVec[57] or ((w(p) shr 12) and 0x3)
            cbIndex[0] = cbIndex[0] or ((w(p) shr 11) and 1)
            cbIndex[1] = (w(p) shr 4) and 0x7F
            cbIndex[2] = (w(p) shl 3) and 0x78
            p++
            cbIndex[2] = cbIndex[2] or ((w(p) shr 13) and 0x7)
            gainIndex[0] = gainIndex[0] or ((w(p) shr 10) and 0x7)
            gainIndex[1] = gainIndex[1] or ((w(p) shr 8) and 0x3)
            gainIndex[2] = (w(p) shr 5) and 0x7
            cbIndex[3] = cbIndex[3] or ((w(p) shr 4) and 0x1)
            cbIndex[4] = (w(p) shl 3) and 0x78
            p++
            cbIndex[4] = cbIndex[4] or ((w(p) shr 13) and 0x7)
            cbIndex[5] = (w(p) shr 6) and 0x7F
            cbIndex[6] = cbIndex[6] or ((w(p) shr 5) and 0x1)
            cbIndex[7] = (w(p) shl 3) and 0xF8
            p++
            cbIndex[7] = cbIndex[7] or ((w(p) shr 13) and 0x7)
            cbIndex[8] = (w(p) shr 5) and 0xFF
            cbIndex[9] = cbIndex[9] or ((w(p) shr 4) and 0x1)
            cbIndex[10] = (w(p) shl 4) and 0xF0
            p++
            cbIndex[10] = cbIndex[10] or ((w(p) shr 12) and 0xF)
            cbIndex[11] = (w(p) shr 4) and 0xFF
            cbIndex[12] = cbIndex[12] or ((w(p) shr 3) and 0x1)
            cbIndex[13] = (w(p) shl 5) and 0xE0
            p++
            cbIndex[13] = cbIndex[13] or ((w(p) shr 11) and 0x1F)
            cbIndex[14] = (w(p) shr 3) and 0xFF
            gainIndex[3] = gainIndex[3] or ((w(p) shr 1) and 0x3)
            gainIndex[4] = gainIndex[4] or ((w(p) and 0x1))
            p++
            gainIndex[5] = (w(p) shr 13) and 0x7
            gainIndex[6] = gainIndex[6] or ((w(p) shr 10) and 0x7)
            gainIndex[7] = gainIndex[7] or ((w(p) shr 8) and 0x3)
            gainIndex[8] = (w(p) shr 5) and 0x7
            gainIndex[9] = gainIndex[9] or ((w(p) shr 1) and 0xF)
            gainIndex[10] = gainIndex[10] or ((w(p) shl 2) and 0x4)
            p++
            gainIndex[10] = gainIndex[10] or ((w(p) shr 14) and 0x3)
            gainIndex[11] = (w(p) shr 11) and 0x7
            gainIndex[12] = gainIndex[12] or ((w(p) shr 7) and 0xF)
            gainIndex[13] = gainIndex[13] or ((w(p) shr 4) and 0x7)
            gainIndex[14] = (w(p) shr 1) and 0x7
        }
        // The last bit marks an empty frame.
        return w(p) and 0x1
    }

    // index_conv_dec.c

    private fun indexConvDec() {
        for (k in 4 until 6) {
            if (cbIndex[k] in 44 until 108) {
                cbIndex[k] += 64
            } else if (cbIndex[k] in 108 until 128) {
                cbIndex[k] += 128
            }
        }
    }

    // simple_lsf_dequant.c and lsf_check.c

    private fun simpleLsfDeQ() {
        for (n in 0 until lpcN) {
            var pos = 0
            var cbPos = 0
            for (i in 0 until 3) {
                val dim = T.LSF_DIM_CB[i]
                for (j in 0 until dim) lsfdeq[n * ORDER + pos + j] = T.LSF_CB[cbPos + lsf[n * 3 + i] * dim + j]
                pos += dim
                cbPos += T.LSF_SIZE_CB[i] * dim
            }
        }
    }

    private fun lsfCheck() {
        for (n in 0 until 2) {
            for (m in 0 until lpcN) {
                for (k in 0 until ORDER - 1) {
                    val pos = m * ORDER + k
                    if (lsfdeq[pos + 1] - lsfdeq[pos] < 319) {
                        if (lsfdeq[pos + 1] < lsfdeq[pos]) {
                            lsfdeq[pos + 1] = s16(lsfdeq[pos] + 160)
                            lsfdeq[pos] = s16(lsfdeq[pos + 1] - 160)
                        } else {
                            lsfdeq[pos] = s16(lsfdeq[pos] - 160)
                            lsfdeq[pos + 1] = s16(lsfdeq[pos + 1] + 160)
                        }
                    }
                    if (lsfdeq[pos] < 82) lsfdeq[pos] = 82
                    if (lsfdeq[pos] > 25723) lsfdeq[pos] = 25723
                }
            }
        }
    }

    // decoder_interpolate_lsf.c, with lsf_interpolate_to_poly_dec.c and interpolate.c. The
    // weighting filters it also computes are not used by the decoder, so they are left out.

    private fun decoderInterpolateLsp() {
        if (mode == 30) {
            lspInterpolate2PolyDec(lsfdeqold, 0, lsfdeq, 0, T.LSF_WEIGHT_30MS[0])
            lp.copyInto(syntdenum, 0)
            for (i in 1 until 6) {
                lspInterpolate2PolyDec(lsfdeq, 0, lsfdeq, ORDER, T.LSF_WEIGHT_30MS[i])
                lp.copyInto(syntdenum, i * (ORDER + 1))
            }
            lsfdeq.copyInto(lsfdeqold, 0, ORDER, 2 * ORDER)
        } else {
            for (i in 0 until nsub) {
                lspInterpolate2PolyDec(lsfdeqold, 0, lsfdeq, 0, T.LSF_WEIGHT_20MS[i])
                lp.copyInto(syntdenum, i * (ORDER + 1))
            }
            lsfdeq.copyInto(lsfdeqold, 0, 0, ORDER)
        }
    }

    private fun lspInterpolate2PolyDec(lsf1: IntArray, at1: Int, lsf2: IntArray, at2: Int, coef: Int) {
        val invcoef = 16384 - coef
        for (i in 0 until ORDER) lsftmp[i] = s16((coef * lsf1[at1 + i] + invcoef * lsf2[at2 + i] + 8192) shr 14)
        lsf2Poly()
    }

    // lsf_to_poly.c, lsf_to_lsp.c and get_lsp_poly.c

    private fun lsf2Poly() {
        for (i in 0 until ORDER) {
            val freq = s16((lsftmp[i] * 20861) shr 15)
            var k = freq shr 8
            val diff = freq and 0x00ff
            if (k > 63) k = 63
            val tmp = T.COS_DERIVATIVE[k] * diff
            lsp[i] = s16(T.COS[k] + s16(tmp shr 12))
        }
        getLspPoly(0, f[0])
        getLspPoly(1, f[1])
        for (i in 5 downTo 1) {
            f[0][i] += f[0][i - 1]
            f[1][i] -= f[1][i - 1]
        }
        lp[0] = 4096
        for (i in 1..5) {
            lp[i] = s16((f[0][i] + f[1][i] + 4096) shr 13)
            lp[11 - i] = s16((f[0][i] - f[1][i] + 4096) shr 13)
        }
    }

    private fun getLspPoly(first: Int, fp: IntArray) {
        var l = first
        fp[0] = 16777216
        fp[1] = lsp[l] * -1024
        var p = 2
        l += 2
        for (i in 2..5) {
            fp[p] = fp[p - 2]
            for (j in i downTo 2) {
                val high = s16(fp[p - 1] shr 16)
                val low = s16((fp[p - 1] and 0xffff) shr 1)
                val tmp = 4 * high * lsp[l] + 4 * ((low * lsp[l]) shr 15)
                fp[p] += fp[p - 2]
                fp[p] -= tmp
                p--
            }
            fp[p] -= lsp[l] * (1 shl 10)
            p += i
            l += 2
        }
    }

    // decode_residual.c. As in the reference, the reversed residual is built in the
    // enhancer's buffer and the codebook memory in the concealment residual.

    private fun decodeResidual(): Boolean {
        val reverse = enhBuf
        val memVec = prevResidual
        val mem = CB_HALFFILTERLEN
        val diff = STATE_LEN - stateShortLen
        val startPos = if (stateFirst == 1) (startIdx - 1) * SUBL else (startIdx - 1) * SUBL + diff

        stateConstruct(idxForMax, (startIdx - 1) * (ORDER + 1), decresidual, startPos, stateShortLen)
        if (stateFirst == 1) {
            memVec.fill(0, mem, mem + CB_MEML - stateShortLen)
            decresidual.copyInto(memVec, mem + CB_MEML - stateShortLen, startPos, startPos + stateShortLen)
            if (!cbConstruct(decresidual, startPos + stateShortLen, 0, memVec, mem + CB_MEML - ST_MEM_L_TBL, ST_MEM_L_TBL, diff)) return false
        } else {
            memCpyReversed(memVec, mem + CB_MEML - 1, decresidual, startPos, stateShortLen)
            memVec.fill(0, mem, mem + CB_MEML - stateShortLen)
            if (!cbConstruct(reverse, 0, 0, memVec, mem + CB_MEML - ST_MEM_L_TBL, ST_MEM_L_TBL, diff)) return false
            memCpyReversed(decresidual, startPos - 1, reverse, 0, diff)
        }

        var subcount = 1
        if (nsub > startIdx + 1) {
            memVec.fill(0, mem, mem + CB_MEML - STATE_LEN)
            decresidual.copyInto(memVec, mem + CB_MEML - STATE_LEN, (startIdx - 1) * SUBL, (startIdx - 1) * SUBL + STATE_LEN)
            val nfor = nsub - startIdx - 1
            for (subframe in 0 until nfor) {
                val at = (startIdx + 1 + subframe) * SUBL
                if (!cbConstruct(decresidual, at, subcount * 3, memVec, mem, MEM_LF_TBL, SUBL)) return false
                memVec.copyInto(memVec, mem, mem + SUBL, mem + CB_MEML)
                decresidual.copyInto(memVec, mem + CB_MEML - SUBL, at, at + SUBL)
                subcount++
            }
        }
        if (startIdx > 1) {
            val memlGotten = minOf(SUBL * (nsub + 1 - startIdx), CB_MEML)
            memCpyReversed(memVec, mem + CB_MEML - 1, decresidual, (startIdx - 1) * SUBL, memlGotten)
            memVec.fill(0, mem, mem + CB_MEML - memlGotten)
            val nback = startIdx - 1
            for (subframe in 0 until nback) {
                if (!cbConstruct(reverse, subframe * SUBL, subcount * 3, memVec, mem, MEM_LF_TBL, SUBL)) return false
                memVec.copyInto(memVec, mem, mem + SUBL, mem + CB_MEML)
                reverse.copyInto(memVec, mem + CB_MEML - SUBL, subframe * SUBL, subframe * SUBL + SUBL)
                subcount++
            }
            memCpyReversed(decresidual, SUBL * nback - 1, reverse, 0, SUBL * nback)
        }
        return true
    }

    /** `WebRtcSpl_MemCpyReversedOrder`: [length] values from [source] written backwards from [destAt]. */
    private fun memCpyReversed(dest: IntArray, destAt: Int, source: IntArray, sourceAt: Int, length: Int) {
        for (j in 0 until length) dest[destAt - j] = source[sourceAt + j]
    }

    // state_construct.c

    private fun stateConstruct(idxForMax: Int, denumAt: Int, out: IntArray, outAt: Int, len: Int) {
        for (k in 0..ORDER) numerator[k] = syntdenum[denumAt + ORDER - k]
        val maxVal = T.FRG_QUANT_MOD[idxForMax]
        val sampleVal = ORDER
        val (round, shift) = when {
            idxForMax < 37 -> 2097152 to 22
            idxForMax < 59 -> 262144 to 19
            else -> 65536 to 17
        }
        for (k in 0 until len) {
            sampleValVec[sampleVal + k] = s16((maxVal * T.STATE_SQ3[idxVec[len - 1 - k]] + round) shr shift)
        }
        sampleValVec.fill(0, sampleVal + len, sampleVal + 2 * len)
        sampleValVec.fill(0, 0, ORDER)
        filterMAFastQ12(sampleValVec, sampleVal, sampleMaVec, ORDER, numerator, 0, ORDER + 1, len + ORDER)
        sampleMaVec.fill(0, ORDER + len + ORDER, ORDER + len + ORDER + len - ORDER)
        filterARFastQ12(sampleMaVec, ORDER, sampleValVec, sampleVal, syntdenum, denumAt, ORDER + 1, 2 * len)
        for (k in 0 until len) {
            out[outAt + k] = s16(sampleValVec[sampleVal + len - 1 - k] + sampleValVec[sampleVal + 2 * len - 1 - k])
        }
    }

    // cb_construct.c, gain_dequant.c, get_cd_vec.c and create_augmented_vec.c

    private fun cbConstruct(decvector: IntArray, decAt: Int, indexAt: Int, mem: IntArray, memAt: Int, lMem: Int, veclen: Int): Boolean {
        gain[0] = gainDequant(gainIndex[indexAt], 16384, 0)
        gain[1] = gainDequant(gainIndex[indexAt + 1], gain[0], 1)
        gain[2] = gainDequant(gainIndex[indexAt + 2], gain[1], 2)
        for (stage in 0 until 3) {
            if (!getCbVec(cbvec[stage], mem, memAt, cbIndex[indexAt + stage], lMem, veclen)) return false
        }
        for (j in 0 until veclen) {
            val a32 = gain[0] * cbvec[0][j] + gain[1] * cbvec[1][j] + gain[2] * cbvec[2][j]
            decvector[decAt + j] = s16((a32 + 8192) shr 14)
        }
        return true
    }

    private fun gainDequant(index: Int, maxIn: Int, stage: Int): Int {
        // WEBRTC_SPL_ABS_W16 into an int16_t, where the absolute value of -32768 stays -32768.
        var scale = s16(kotlin.math.abs(maxIn))
        if (scale < 1638) scale = 1638
        val table = when (stage) {
            0 -> T.GAIN_SQ5
            1 -> T.GAIN_SQ4
            else -> T.GAIN_SQ3
        }
        return s16((scale * table[index] + 8192) shr 14)
    }

    private fun getCbVec(out: IntArray, mem: IntArray, memAt: Int, index: Int, lMem: Int, cbveclen: Int): Boolean {
        var baseSize = lMem - cbveclen + 1
        if (cbveclen == SUBL) baseSize += cbveclen / 2
        if (index < lMem - cbveclen + 1) {
            val k = index + cbveclen
            mem.copyInto(out, 0, memAt + lMem - k, memAt + lMem - k + cbveclen)
        } else if (index < baseSize) {
            val k = 2 * (index - (lMem - cbveclen + 1)) + cbveclen
            createAugmentedVec(k / 2, mem, memAt + lMem, out)
        } else if (index - baseSize < lMem - cbveclen + 1) {
            val memIndTest = lMem - (index - baseSize + cbveclen)
            mem.fill(0, memAt - CB_HALFFILTERLEN, memAt)
            mem.fill(0, memAt + lMem, memAt + lMem + CB_HALFFILTERLEN)
            filterMAFastQ12(mem, memAt + memIndTest + 4, out, 0, T.CB_FILTERS_REV, 0, CB_FILTERLEN, cbveclen)
        } else {
            // A codebook index the encoder cannot produce leaves the reference
            // part way through an update it cannot finish.
            if (cbveclen < SUBL) return false
            val memIndTest = lMem - cbveclen - CB_FILTERLEN
            mem.fill(0, memAt + lMem, memAt + lMem + CB_HALFFILTERLEN)
            filterMAFastQ12(mem, memAt + memIndTest + 7, tempbuff2, 0, T.CB_FILTERS_REV, 0, CB_FILTERLEN, cbveclen + 5)
            val lag = (cbveclen shl 1) - 20 + index - baseSize - lMem - 1
            createAugmentedVec(lag, tempbuff2, SUBL + 5, out)
        }
        return true
    }

    private fun createAugmentedVec(index: Int, buffer: IntArray, bufferEnd: Int, cbVec: IntArray) {
        val interpLen = minOf(index, 4)
        val ilow = index - interpLen
        buffer.copyInto(cbVec, 0, bufferEnd - index, bufferEnd)
        val ppo = bufferEnd - interpLen
        val ppi = bufferEnd - index - interpLen
        for (k in 0 until interpLen) cbVec[ilow + k] = s16((buffer[ppi + k] * T.ALPHA[k]) shr 15)
        for (k in 0 until interpLen) cbVecTmp[k] = s16((buffer[ppo + k] * T.ALPHA[interpLen - 1 - k]) shr 15)
        for (k in 0 until interpLen) cbVec[ilow + k] = s16(cbVec[ilow + k] + cbVecTmp[k])
        val second = minOf(SUBL - index, index)
        buffer.copyInto(cbVec, index, bufferEnd - index, bufferEnd - index + second)
    }

    // do_plc.c and comp_corr.c. [lpcAt] locates the frame's last LPC set in syntdenum.

    private fun doThePlc(pli: Int, lpcAt: Int, inlag: Int) {
        if (pli == 1) {
            consPLICount += 1
            var lag: Int
            var maxPerSquare: Int
            if (prevPLI != 1) {
                val max = maxAbsW16(prevResidual, 0, blockl)
                var scale3 = (sizeInBits(max) shl 1) - 25
                if (scale3 < 0) scale3 = 0
                prevScale = scale3
                lag = inlag - 3
                // Unsigned in the reference, so a lag past the frame leaves the length at 60.
                val room = blockl - (inlag + 3)
                val corrLen = if (room < 0) 60 else minOf(60, room)
                var cross = compCorrCross(lag, corrLen, scale3)
                var ener = compCorrEner
                var shiftMax = sizeInBits(absW32(cross)) - 15
                var crossSquareMax = s16((s16(shiftW32(cross, -shiftMax)) * s16(shiftW32(cross, -shiftMax))) shr 15)
                var enerComp = 0
                for (j in inlag - 2..inlag + 3) {
                    val crossComp = compCorrCross(j, corrLen, scale3)
                    enerComp = compCorrEner
                    val shift1 = sizeInBits(absW32(crossComp)) - 15
                    val crossSquare = s16((s16(shiftW32(crossComp, -shift1)) * s16(shiftW32(crossComp, -shift1))) shr 15)
                    val shift2 = sizeInBits(ener) - 15
                    val measure = s16(shiftW32(ener, -shift2)) * crossSquare
                    val shift3 = sizeInBits(enerComp) - 15
                    val maxMeasure = s16(shiftW32(enerComp, -shift3)) * crossSquareMax
                    val tmp1: Int
                    val tmp2: Int
                    if (2 * shiftMax + shift3 > 2 * shift1 + shift2) {
                        tmp1 = minOf(31, 2 * shiftMax + shift3 - 2 * shift1 - shift2)
                        tmp2 = 0
                    } else {
                        tmp1 = 0
                        tmp2 = minOf(31, 2 * shift1 + shift2 - 2 * shiftMax - shift3)
                    }
                    if ((measure shr tmp1) > (maxMeasure shr tmp2)) {
                        lag = j
                        crossSquareMax = crossSquare
                        cross = crossComp
                        shiftMax = shift1
                        ener = enerComp
                    }
                }
                val tmp2W32 = dotProductWithScale(prevResidual, blockl - corrLen, prevResidual, blockl - corrLen, corrLen, scale3)
                if (tmp2W32 > 0 && enerComp > 0) {
                    val scale1 = s16(normW32(tmp2W32) - 16)
                    val tmp1 = s16(shiftW32(tmp2W32, scale1))
                    val scale2 = s16(normW32(ener) - 16)
                    val tmp2 = s16(shiftW32(ener, scale2))
                    val denom = s16((tmp1 * tmp2) shr 16)
                    val totscale = s16(scale1 + scale2 - 1)
                    val c1 = s16(shiftW32(cross, totscale shr 1))
                    val c2 = s16(shiftW32(cross, totscale - (totscale shr 1)))
                    maxPerSquare = s16(divW32W16(c1 * c2, denom))
                } else {
                    maxPerSquare = 0
                }
            } else {
                lag = prevLag
                maxPerSquare = perSquare
            }
            // The reference tests the smallest threshold first, so only the first branch can be taken.
            var useGain = 32767
            if (consPLICount * blockl > 320) useGain = 29491
            val pitchfact = when {
                maxPerSquare > 7868 -> 32767
                maxPerSquare > 839 -> {
                    var ind = 5
                    while (maxPerSquare < T.PLC_PER_SQR[ind] && ind > 0) ind--
                    val tmp = T.PLC_PITCH_FACT[ind] + ((T.PLC_PF_SLOPE[ind] * (maxPerSquare - T.PLC_PER_SQR[ind])) shr 11)
                    s16(minOf(tmp, 32767))
                }
                else -> 0
            }
            val useLag = if (lag < 80) 2 * lag else lag
            val noiseEnergyThreshold = blockl * 900
            var energy = 0
            for (i in 0 until blockl) {
                seed = s16(seed * 31821 + 13849)
                val randlag = 53 + (seed and 63)
                randvec[i] = if (randlag > i) prevResidual[blockl + i - randlag] else prevResidual[i - randlag]
                plcResidual[i] = if (useLag > i) prevResidual[blockl + i - useLag] else plcResidual[i - useLag]
                val totGain = when {
                    i < 80 -> useGain
                    i < 160 -> s16((31130 * useGain) shr 15)
                    else -> s16((29491 * useGain) shr 15)
                }
                plcResidual[i] = s16((totGain * ((pitchfact * plcResidual[i] + (32767 - pitchfact) * randvec[i] + 16384) shr 15)) shr 15)
                if (energy < noiseEnergyThreshold) energy += plcResidual[i] * plcResidual[i]
            }
            if (energy < noiseEnergyThreshold) randvec.copyInto(plcResidual, 0, 0, blockl)
            prevLpc.copyInto(plcLpc)
            prevLag = lag
            perSquare = maxPerSquare
        } else {
            decresidual.copyInto(plcResidual, 0, 0, blockl)
            syntdenum.copyInto(plcLpc, 0, lpcAt, lpcAt + ORDER + 1)
            consPLICount = 0
        }
        prevPLI = pli
        plcLpc.copyInto(prevLpc)
        plcResidual.copyInto(prevResidual, 0, 0, blockl)
    }

    private var compCorrEner = 0

    /** `WebRtcIlbcfix_CompCorr` on the previous residual: returns the correlation and leaves the energy in [compCorrEner]. */
    private fun compCorrCross(lag: Int, sRange: Int, scale: Int): Int {
        val w = blockl - sRange - lag
        var corr = dotProductWithScale(prevResidual, blockl - sRange, prevResidual, w, sRange, scale)
        var ener = dotProductWithScale(prevResidual, w, prevResidual, w, sRange, scale)
        if (ener == 0) {
            corr = 0
            ener = 1
        }
        compCorrEner = ener
        return corr
    }

    // hp_output.c

    private fun hpOutput(signal: IntArray, at: Int, len: Int) {
        val ba = T.HP_OUT_COEFS
        val y = hpimemy
        val x = hpimemx
        for (i in 0 until len) {
            var tmp = y[1] * ba[3]
            tmp += y[3] * ba[4]
            tmp = tmp shr 15
            tmp += y[0] * ba[3]
            tmp += y[2] * ba[4]
            tmp *= 2
            tmp += signal[at + i] * ba[0]
            tmp += x[0] * ba[1]
            tmp += x[1] * ba[2]
            x[1] = x[0]
            x[0] = signal[at + i]
            val rounded = (tmp + 1024).coerceIn(-67108864, 67108863)
            signal[at + i] = s16(rounded shr 11)
            y[2] = y[0]
            y[3] = y[1]
            tmp = when {
                tmp > 268435455 -> Int.MAX_VALUE
                tmp < -268435456 -> Int.MIN_VALUE
                else -> tmp * 8
            }
            y[0] = s16(tmp shr 16)
            y[1] = s16((tmp and 0xffff) shr 1)
        }
    }

    // enhancer_interface.c. Its branch for a previous enhancer state of 2 is left out:
    // only WebRTC's NetEq concealment entry point sets that state.

    private fun enhancerInterface(out: IntArray, outAt: Int): Int {
        var lag = 20
        var tlag = 20
        val inLen = blockl + 120
        val inArr = decresidual
        enhBuf.copyInto(enhBuf, 0, blockl, ENH_BUFL)
        inArr.copyInto(enhBuf, ENH_BUFL - blockl, 0, blockl)
        val plcBlockl: Int
        val newBlocks: Int
        val startPos: Int
        if (mode == 30) {
            plcBlockl = ENH_BLOCKL
            newBlocks = 3
            startPos = 320
        } else {
            plcBlockl = 40
            newBlocks = 2
            startPos = 440
        }
        enhPeriod.copyInto(enhPeriod, 0, newBlocks, ENH_NBLOCKS_TOT)
        IlbcOps.downsampleFast(enhBuf, ENH_BUFL - inLen, downsampled, inLen / 2, T.LP_FILT_COEFS, 7, 2, 3)

        for (iblock in 0 until newBlocks) {
            val target = 60 + iblock * ENH_BLOCKL_HALF
            val regressor = target - 10
            val max16 = maxAbsW16(downsampled, regressor - 50, ENH_BLOCKL_HALF + 50 - 1)
            val shifts = maxOf(0, sizeInBits(max16 * max16) - 25)
            crossCorrelation(corr32, downsampled, target, downsampled, regressor, ENH_BLOCKL_HALF, 50, shifts, -1)
            for (i in 0 until 2) {
                lagmax[i] = maxIndexW32(corr32, 0, 50)
                corrmax[i] = corr32[lagmax[i]]
                val start = maxOf(2, lagmax[i]) - 2
                val stop = minOf(47, lagmax[i]) + 2
                for (k in start..stop) corr32[k] = 0
            }
            lagmax[2] = maxIndexW32(corr32, 0, 50)
            corrmax[2] = corr32[lagmax[2]]
            for (i in 0 until 3) {
                val corrSh = 15 - sizeInBits(corrmax[i])
                val ener = dotProductWithScale(downsampled, regressor - lagmax[i], downsampled, regressor - lagmax[i], ENH_BLOCKL_HALF, shifts)
                val enerSh = 15 - sizeInBits(ener)
                corr16[i] = s16(shiftW32(corrmax[i], corrSh))
                corr16[i] = s16((corr16[i] * corr16[i]) shr 16)
                en16[i] = s16(shiftW32(ener, enerSh))
                totsh[i] = s16(enerSh - 2 * corrSh)
            }
            var ind = 0
            for (i in 1 until 3) {
                if (totsh[ind] > totsh[i]) {
                    val sh = minOf(31, totsh[ind] - totsh[i])
                    if (corr16[ind] * en16[i] < (corr16[i] * en16[ind]) shr sh) ind = i
                } else {
                    val sh = minOf(31, totsh[i] - totsh[ind])
                    if ((corr16[ind] * en16[i]) shr sh < corr16[i] * en16[ind]) ind = i
                }
            }
            lag = lagmax[ind] + 10
            enhPeriod[ENH_NBLOCKS_TOT - newBlocks + iblock] = lag * 8
            if (prevEnhPl == 1) {
                if (iblock == 0) tlag = lag * 2
            } else {
                if (iblock == 1) tlag = lag * 2
            }
            lag *= 2
        }

        if (prevEnhPl == 1) {
            // The best lag of the new frame, to interpolate backwards into the concealed one.
            val plcPred = downsampled
            val regressor = tlag - 1
            val max16 = maxAbsW16(inArr, regressor, plcBlockl + 3 - 1)
            val maxVal = plcBlockl.toLong() * max16 * max16
            val factor = (maxVal shr 31).toInt()
            val shifts = if (factor == 0) 0 else 31 - normW32(factor)
            crossCorrelation(corr32, inArr, 0, inArr, regressor, plcBlockl, 3, shifts, 1)
            lag = maxIndexW32(corr32, 0, 3)
            lag += tlag - 1
            if (lag > plcBlockl) {
                inArr.copyInto(plcPred, 0, lag - plcBlockl, lag)
            } else {
                inArr.copyInto(plcPred, plcBlockl - lag, 0, lag)
                enhBuf.copyInto(plcPred, 0, ENH_BUFL - blockl - plcBlockl + lag, ENH_BUFL - blockl)
            }
            var max = maxAbsW16(enhBuf, ENH_BUFL - blockl - plcBlockl, plcBlockl)
            val maxPred = maxAbsW16(plcPred, 0, plcBlockl)
            max = maxOf(max, maxPred)
            val scale = maxOf(s16(22 - normW32(max)), 0)
            var tmp2 = dotProductWithScale(enhBuf, ENH_BUFL - blockl - plcBlockl, enhBuf, ENH_BUFL - blockl - plcBlockl, plcBlockl, scale)
            var tmp1 = dotProductWithScale(plcPred, 0, plcPred, 0, plcBlockl, scale)
            if (tmp1 > 0 && (tmp1 shr 2) > tmp2) {
                val scale1 = s16(normW32(tmp1))
                tmp1 = shiftW32(tmp1, scale1 - 16)
                tmp2 = shiftW32(tmp2, scale1)
                val enChange = s16(divW32W16(tmp2, s16(tmp1)))
                val sqrtEnChange = s16(sqrtFloor(enChange shl 14))
                for (i in 0 until plcBlockl - 16) plcPred[i] = s16((plcPred[i] * sqrtEnChange) shr 14)
                val inc = s16(2048 - (sqrtEnChange shr 3))
                var win = 0
                for (i in plcBlockl - 16 until plcBlockl) {
                    plcPred[i] = s16((plcPred[i] * (sqrtEnChange + (win shr 1))) shr 14)
                    win = s16(win + inc)
                }
            }
            val inc = if (plcBlockl == 40) 400 else 202
            var win = 0
            var p = ENH_BUFL - 1 - blockl
            for (i in 0 until plcBlockl) {
                win = s16(win + inc)
                enhBuf[p] = s16((enhBuf[p] * win) shr 14)
                enhBuf[p] = s16(enhBuf[p] + s16(((16384 - win) * plcPred[plcBlockl - 1 - i]) shr 14))
                p--
            }
        }

        for (iblock in 0 until newBlocks) {
            enhancer(out, outAt + iblock * ENH_BLOCKL, iblock * ENH_BLOCKL + startPos)
        }
        return lag
    }

    // enhancer.c, get_sync_seq.c, nearest_neighbor.c, refiner.c, my_corr.c and enh_upsample.c

    private fun enhancer(out: IntArray, outAt: Int, centerStartPos: Int) {
        surround.fill(0)
        getSyncSeq(centerStartPos)
        smooth(out, outAt, centerStartPos)
    }

    private fun getSyncSeq(centerStartPos: Int) {
        val hl = ENH_HL
        val centerEndPos = centerStartPos + ENH_BLOCKL - 1
        lagBlock[hl] = nearestNeighbor(ENH_PLOCS_ARRAY, 2 * (centerStartPos + centerEndPos))
        blockStartPos[hl] = 4 * centerStartPos
        var q = hl
        while (q > 0) {
            val qq = q - 1
            val periodQ = enhPeriod[lagBlock[q]]
            if (blockStartPos[q] < periodQ + 4 * ENH_OVERHANG) break
            blockStartPos[qq] = blockStartPos[q] - periodQ
            var value = blockStartPos[qq] + 4 * ENH_BLOCKL_HALF
            value = if (value > periodQ) value - periodQ else 0
            lagBlock[qq] = nearestNeighbor(ENH_PLOCS_ARRAY, value)
            blockStartPos[qq] = refiner(centerStartPos, blockStartPos[qq], T.ENH_WT[qq])
            q--
        }
        for (i in 0 until ENH_NBLOCKS_TOT) plocs2[i] = T.ENH_PLOCS[i] - enhPeriod[i]
        for (qn in hl + 1..2 * hl) {
            lagBlock[qn] = nearestNeighbor(plocs2, blockStartPos[qn - 1] + 4 * ENH_BLOCKL_HALF)
            blockStartPos[qn] = blockStartPos[qn - 1] + enhPeriod[lagBlock[qn]]
            if (blockStartPos[qn] + 4 * (ENH_BLOCKL + ENH_OVERHANG) < 4 * ENH_BUFL) {
                blockStartPos[qn] = refiner(centerStartPos, blockStartPos[qn], T.ENH_WT[2 * hl - qn])
            }
        }
    }

    /** The index of the value in [array] nearest [value], the first of equals, with C's unsigned arithmetic. */
    private fun nearestNeighbor(array: IntArray, value: Int): Int {
        var index = 0
        var minDiff = Long.MAX_VALUE
        val v = value.toLong() and 0xFFFFFFFFL
        for (i in 0 until ENH_NBLOCKS_TOT) {
            val a = array[i].toLong() and 0xFFFFFFFFL
            val diff = if (a < v) v - a else a - v
            if (diff < minDiff) {
                index = i
                minDiff = diff
            }
        }
        return index
    }

    /** `WebRtcIlbcfix_Refiner` over the enhancer buffer: returns the updated start position and adds to [surround]. */
    private fun refiner(centerStartPos: Int, estSegPos: Int, gain: Int): Int {
        val idata = enhBuf
        val idatal = ENH_BUFL
        val estSegPosRounded = (estSegPos - 2) shr 2
        val searchSegStartPos = if (estSegPosRounded < ENH_SLOP) 0 else estSegPosRounded - ENH_SLOP
        var searchSegEndPos = estSegPosRounded + ENH_SLOP
        if (searchSegEndPos + ENH_BLOCKL >= idatal) searchSegEndPos = idatal - ENH_BLOCKL - 1
        val corrdim = searchSegEndPos + 1 - searchSegStartPos

        myCorr(corrVecTemp, idata, searchSegStartPos, corrdim + ENH_BLOCKL - 1, idata, centerStartPos, ENH_BLOCKL)
        val maxtemp = maxAbsW32(corrVecTemp, 0, corrdim)
        val scalefact = sizeInBits(maxtemp) - 15
        for (i in 0 until corrdim) corrVec[i] = s16(if (scalefact > 0) corrVecTemp[i] shr scalefact else corrVecTemp[i])
        for (i in corrdim until ENH_CORRDIM) corrVec[i] = 0
        enhUpsample()
        val tloc = maxIndexW32(corrVecUps, 0, 4 * corrdim)
        val updStartPos = searchSegStartPos * 4 + tloc + 4
        val tloc2 = (tloc + 3) shr 2

        if (ENH_FL0 > searchSegStartPos + tloc2) {
            val st = ENH_FL0 - searchSegStartPos - tloc2
            vect.fill(0, 0, st)
            idata.copyInto(vect, st, 0, ENH_VECTL - st)
        } else {
            val st = searchSegStartPos + tloc2 - ENH_FL0
            if (st + ENH_VECTL > idatal) {
                val en = st + ENH_VECTL - idatal
                idata.copyInto(vect, 0, st, st + ENH_VECTL - en)
                vect.fill(0, ENH_VECTL - en, ENH_VECTL)
            } else {
                idata.copyInto(vect, 0, st, st + ENH_VECTL)
            }
        }
        val row = (tloc2 * 4 - tloc) * 7
        for (i in 0 until 7) filt[6 - i] = T.ENH_POLY_PHASER[row + i]
        filterMAFastQ12(vect, 6, vect, 0, filt, 0, 7, ENH_BLOCKL)
        for (i in 0 until ENH_BLOCKL) surround[i] = s16(surround[i] + s16((vect[i] * gain + 32768) shr 16))
        return updStartPos
    }

    private fun myCorr(corr: IntArray, seq1: IntArray, seq1At: Int, dim1: Int, seq2: IntArray, seq2At: Int, dim2: Int) {
        val max1 = (maxAbsW16(seq1, seq1At, dim1) + 1).toLong()
        val max2 = (maxAbsW16(seq2, seq2At, dim2) + 1).toLong()
        var rightShift = (64 - 31) - ((max1 * max2) * dim2).countLeadingZeroBits()
        if (rightShift < 0) rightShift = 0
        crossCorrelation(corr, seq2, seq2At, seq1, seq1At, dim2, dim1 - dim2 + 1, rightShift, 1)
    }

    /** `WebRtcIlbcfix_EnhUpsample` of [corrVec] into [corrVecUps]. */
    private fun enhUpsample() {
        val pp = T.ENH_POLY_PHASER
        val seq = corrVec
        for (j in 0 until 4) {
            val row = j * 7
            var at = j
            corrVecUps[at] = seq[2] * pp[row + 1] + seq[1] * pp[row + 2] + seq[0] * pp[row + 3]
            at += 4
            corrVecUps[at] = seq[3] * pp[row + 1] + seq[2] * pp[row + 2] + seq[1] * pp[row + 3] + seq[0] * pp[row + 4]
            at += 4
            corrVecUps[at] = seq[4] * pp[row + 1] + seq[3] * pp[row + 2] + seq[2] * pp[row + 3] + seq[1] * pp[row + 4] + seq[0] * pp[row + 5]
        }
        for (j in 0 until 4) {
            val row = j * 7
            var at = 12 + j
            corrVecUps[at] = seq[4] * pp[row + 2] + seq[3] * pp[row + 3] + seq[2] * pp[row + 4] + seq[1] * pp[row + 5]
            at += 4
            corrVecUps[at] = seq[4] * pp[row + 3] + seq[3] * pp[row + 4] + seq[2] * pp[row + 5]
        }
    }

    // smooth.c and smooth_out_data.c

    private fun smooth(odata: IntArray, odataAt: Int, currentAt: Int) {
        val current = enhBuf
        val max1 = (maxAbsW16(current, currentAt, ENH_BLOCKL) + 1).toLong()
        val max2 = (maxAbsW16(surround, 0, ENH_BLOCKL) + 1).toLong()
        val max12 = maxOf(max1, max2)
        var scale = (64 - 31) - ((max12 * max12) * ENH_BLOCKL).countLeadingZeroBits()
        scale = maxOf(0, scale)
        var w00 = dotProductWithScale(current, currentAt, current, currentAt, ENH_BLOCKL, scale)
        var w11 = dotProductWithScale(surround, 0, surround, 0, ENH_BLOCKL, scale)
        val w10 = dotProductWithScale(surround, 0, current, currentAt, ENH_BLOCKL, scale)
        if (w00 < 0) w00 = Int.MAX_VALUE
        if (w11 < 0) w11 = Int.MAX_VALUE
        val bitsw00 = sizeInBits(w00)
        val bitsw11 = sizeInBits(w11)
        val bitsw10 = sizeInBits(absW32(w10))
        var scale1 = 31 - bitsw00
        var scale2 = 15 - bitsw11
        if (scale2 > scale1 - 16) scale2 = scale1 - 16 else scale1 = scale2 + 16
        var w00prim = w00 shl scale1
        val w11prim = s16(shiftW32(w11, scale2))
        val c = if (w11prim > 64) {
            val endiff = divW32W16(w00prim, w11prim) shl 6
            s16(sqrtFloor(endiff))
        } else {
            1
        }
        // A first try without the power constraint.
        for (i in 0 until ENH_BLOCKL) odata[odataAt + i] = s16((c * surround[i] + 1024) shr 11)
        var errs = 0
        for (i in 0 until ENH_BLOCKL) {
            val err = s16((current[currentAt + i] - odata[odataAt + i]) shr 3)
            errs += err * err
        }
        val crit = if (6 - scale + scale1 > 31) 0 else shiftW32(ENH_A0 * (w00prim shr 14), -(6 - scale + scale1))
        if (errs <= crit) return

        if (w00 < 1) w00 = 1
        scale1 = bitsw00 - 15
        scale2 = bitsw11 - 15
        scale = if (scale2 > scale1) scale2 else scale1
        val w11w00 = s16(shiftW32(w11, -scale)) * s16(shiftW32(w00, -scale))
        val w10w10 = s16(shiftW32(w10, -scale)) * s16(shiftW32(w10, -scale))
        val w00w00 = s16(shiftW32(w00, -scale)) * s16(shiftW32(w00, -scale))
        val denom = if (w00w00 > 65536) {
            val endiff = maxOf(0, w11w00 - w10w10)
            divW32W16(endiff, s16(w00w00 shr 16))
        } else {
            65536
        }
        var a: Int
        val b: Int
        if (denom > 7) {
            scale = sizeInBits(denom) - 15
            val denomW16: Int
            val num: Int
            if (scale > 0) {
                denomW16 = s16(denom shr scale)
                num = ENH_A0_MINUS_A0A0DIV4 shr scale
            } else {
                denomW16 = s16(denom)
                num = ENH_A0_MINUS_A0A0DIV4
            }
            a = s16(sqrtFloor(divW32W16(num, denomW16)))
            scale1 = 31 - bitsw10
            scale2 = 21 - scale1
            var w10prim = if (w10 == 0) 0 else w10 * (1 shl scale1)
            w00prim = shiftW32(w00, -scale2)
            scale = bitsw00 - scale2 - 15
            if (scale > 0) {
                w10prim = w10prim shr scale
                w00prim = w00prim shr scale
            }
            if (w00prim > 0 && w10prim > 0) {
                val w11DivW00 = divW32W16(w10prim, s16(w00prim))
                val bW32 = if (sizeInBits(w11DivW00) + sizeInBits(a) > 31) {
                    0
                } else {
                    1073741824 - ENH_A0DIV2 - a * w11DivW00
                }
                b = s16(bW32 shr 16)
            } else {
                a = 0
                b = 16384
            }
        } else {
            a = 0
            b = 16384
        }
        for (i in 0 until ENH_BLOCKL) {
            odata[odataAt + i] = s16(s16((a * surround[i]) shr 9) + s16((b * current[currentAt + i]) shr 14))
        }
    }

    private companion object {
        const val ORDER = 10
        const val SUBL = 40
        const val NSUB_MAX = 6
        const val BLOCKL_20MS = 160
        const val BLOCKL_30MS = 240
        const val BLOCKL_MAX = 240
        const val STATE_LEN = 80
        const val CB_MEML = 147
        const val CB_FILTERLEN = 8
        const val CB_HALFFILTERLEN = 4
        const val ST_MEM_L_TBL = 85
        const val MEM_LF_TBL = 147
        const val ENH_BLOCKL = 80
        const val ENH_BLOCKL_HALF = 40
        const val ENH_HL = 3
        const val ENH_SLOP = 2
        const val ENH_OVERHANG = 2
        const val ENH_FL0 = 3
        const val ENH_VECTL = ENH_BLOCKL + 2 * ENH_FL0
        const val ENH_CORRDIM = 2 * ENH_SLOP + 1
        const val ENH_NBLOCKS_TOT = 8
        const val ENH_BUFL = ENH_NBLOCKS_TOT * ENH_BLOCKL
        const val ENH_A0 = 819
        const val ENH_A0_MINUS_A0A0DIV4 = 848256041
        const val ENH_A0DIV2 = 26843546

        val ENH_PLOCS_ARRAY: IntArray = T.ENH_PLOCS
    }
}

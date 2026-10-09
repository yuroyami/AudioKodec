/*
 * Translated from libspeex 1.2.1, which is Copyright 2002-2008 Xiph.org
 * Foundation, Jean-Marc Valin and others and licensed under the BSD 3-Clause
 * licence, reproduced in NOTICE. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.speex

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * One layer of the Speex decoder: `nb_decode` for narrowband, or
 * `sb_decode` for a band added above another layer.
 *
 * [decode] returns 0 for a frame, -1 where the packet holds no more frames,
 * and -2 for a stream the reference calls corrupted, as the reference does.
 * Samples are on the reference's scale, where full scale is 32768.
 */
internal sealed class SpeexLayer {
    abstract val frameSize: Int
    abstract val lookahead: Int

    /** Where the layer above saves this layer's innovation, interleaved two to one. */
    var innovationSave: FloatArray? = null
    var innovationSaveAt = 0

    abstract fun decode(bits: SpeexBits, out: FloatArray, outAt: Int): Int
    abstract fun piGain(out: FloatArray)
    abstract fun excitationRms(out: FloatArray)
    abstract fun reset()

    /** Whether the narrowband layer is in discontinuous transmission, `SPEEX_GET_DTX_STATUS`. */
    abstract val dtxStatus: Boolean

    /** The in-band stereo request, or null when the stream is mono. */
    var stereo: SpeexStereo? = null
}

/** The narrowband CELP decoder, `nb_celp.c`. */
internal class SpeexNarrowband(private val wideband: Boolean) : SpeexLayer() {
    override val frameSize = FRAME
    override val lookahead = SUBFRAME

    private val excBuf = FloatArray(FRAME + 2 * PITCH_END + SUBFRAME + 12)
    private val oldQlsp = FloatArray(ORDER)
    private val interpQlpc = FloatArray(ORDER)
    private val memSp = FloatArray(ORDER)
    private val memHp = FloatArray(2)
    private val piGain = FloatArray(SUBFRAMES)
    private var first = true
    private var submode = 5
    private var lastPitch = 40
    private var lastPitchGain = 0f
    private val seed = intArrayOf(1000)
    private var vocM1 = 0f
    private var vocM2 = 0f
    private var vocMean = 0f
    private var vocOffset = 0
    private var dtx = false
    override val dtxStatus: Boolean get() = dtx

    private val qlsp = FloatArray(ORDER)
    private val interpQlsp = FloatArray(ORDER)
    private val ak = FloatArray(ORDER)
    private val innov = FloatArray(SUBFRAME)
    private val innov2 = FloatArray(SUBFRAME)
    private val exc32 = FloatArray(SUBFRAME)
    private val pitchGain = FloatArray(3)
    private val pitchOut = IntArray(1)

    override fun reset() {
        excBuf.fill(0f)
        oldQlsp.fill(0f)
        interpQlpc.fill(0f)
        memSp.fill(0f)
        memHp.fill(0f)
        first = true
        submode = 5
        lastPitch = 40
        lastPitchGain = 0f
        seed[0] = 1000
        vocM1 = 0f
        vocM2 = 0f
        vocMean = 0f
        vocOffset = 0
        dtx = false
    }

    override fun piGain(out: FloatArray) {
        piGain.copyInto(out)
    }

    override fun excitationRms(out: FloatArray) {
        for (i in 0 until SUBFRAMES) out[i] = SpeexDsp.rms(excBuf, EXC + i * SUBFRAME, SUBFRAME)
    }

    override fun decode(bits: SpeexBits, out: FloatArray, outAt: Int): Int {
        // Find the next narrowband frame: skip wideband layers and in-band requests.
        var m: Int
        do {
            if (bits.remaining < 5) return -1
            if (bits.unpack(1) != 0) {
                bits.advance(WB_SKIP[bits.unpack(3)] - 4)
                if (bits.remaining < 5) return -1
                if (bits.unpack(1) != 0) {
                    bits.advance(WB_SKIP[bits.unpack(3)] - 4)
                    if (bits.unpack(1) != 0) return -2
                }
            }
            if (bits.remaining < 4) return -1
            m = bits.unpack(4)
            when {
                m == 15 -> return -1
                m == 14 -> inBand(bits)
                m == 13 -> {
                    val size = bits.unpack(4)
                    bits.advance(5 + 8 * size)
                }
                m > 8 -> return -2
            }
        } while (m > 8)
        submode = m

        excBuf.copyInto(excBuf, 0, FRAME, FRAME + 2 * PITCH_END + SUBFRAME + 12)

        val mode = SpeexModes.NARROWBAND[submode]
        if (mode == null) {
            val lpc = FloatArray(ORDER)
            SpeexDsp.bwLpc(0.93f, interpQlpc, lpc, ORDER)
            val innovGain = SpeexDsp.rms(excBuf, EXC, FRAME)
            for (i in 0 until FRAME) excBuf[EXC + i] = SpeexDsp.random(innovGain, seed)
            first = true
            SpeexDsp.iir(excBuf, EXC, lpc, out, outAt, FRAME, ORDER, memSp)
            return 0
        }

        when (mode.lsp) {
            SpeexLsp.NB -> lspUnquantNb(bits)
            else -> lspUnquantLbr(bits)
        }
        if (first) qlsp.copyInto(oldQlsp)

        var olPitch = 0
        if (mode.lbrPitch != -1) olPitch = PITCH_START + bits.unpack(7)
        var olPitchCoef = 0f
        if (mode.forcedPitchGain) olPitchCoef = (0.066667 * bits.unpack(4)).toFloat()
        val olGain = exp(bits.unpack(5) / 3.5).toFloat()

        if (submode == 1) dtx = bits.unpack(4) == 15
        if (submode > 1) dtx = false

        var bestPitch = 40
        var bestPitchGain = 0f
        var pitchAverage = 0f

        for (sub in 0 until SUBFRAMES) {
            val offset = SUBFRAME * sub
            val exc = EXC + offset
            excBuf.fill(0f, exc, exc + SUBFRAME)

            var pitMin: Int
            var pitMax: Int
            if (mode.lbrPitch != -1) {
                val margin = mode.lbrPitch
                if (margin != 0) {
                    pitMin = maxOf(olPitch - margin + 1, PITCH_START)
                    pitMax = minOf(olPitch + margin, PITCH_END)
                } else {
                    pitMin = olPitch
                    pitMax = olPitch
                }
            } else {
                pitMin = PITCH_START
                pitMax = PITCH_END
            }
            if (mode.pitch == SpeexPitch.FORCED) {
                forcedPitchUnquant(exc, pitMin, olPitchCoef)
            } else {
                pitchUnquant3tap(exc, pitMin, mode.ltp!!, bits)
            }
            SpeexDsp.sanitize(exc32, 0, -32000f, 32000f, SUBFRAME)
            val pitch = pitchOut[0]
            val g0 = pitchGain[0]
            val g2 = pitchGain[2]
            // gain_3tap_to_1tap mixes the float gains with -.5 in double.
            val tmp = (kotlin.math.abs(pitchGain[1]).toDouble() + (if (g0 > 0) g0.toDouble() else -.5 * g0) + (if (g2 > 0) g2.toDouble() else -.5 * g2)).toFloat()
            pitchAverage += tmp
            if ((tmp > bestPitchGain && kotlin.math.abs(2 * bestPitch - pitch) >= 3 && kotlin.math.abs(3 * bestPitch - pitch) >= 4 && kotlin.math.abs(4 * bestPitch - pitch) >= 5) ||
                (tmp > .6 * bestPitchGain && (kotlin.math.abs(bestPitch - 2 * pitch) < 3 || kotlin.math.abs(bestPitch - 3 * pitch) < 4 || kotlin.math.abs(bestPitch - 4 * pitch) < 5)) ||
                (.67 * tmp > bestPitchGain && (kotlin.math.abs(2 * bestPitch - pitch) < 3 || kotlin.math.abs(3 * bestPitch - pitch) < 4 || kotlin.math.abs(4 * bestPitch - pitch) < 5))
            ) {
                bestPitch = pitch
                if (tmp > bestPitchGain) bestPitchGain = tmp
            }

            innov.fill(0f)
            val energy = when (mode.subframeGain) {
                3 -> SpeexTables.GAIN_SCALE3[bits.unpack(3)] * olGain
                1 -> SpeexTables.GAIN_SCALE1[bits.unpack(1)] * olGain
                else -> olGain
            }
            innovationUnquant(mode, innov, bits)
            for (i in 0 until SUBFRAME) innov[i] = energy * innov[i]
            if (mode.doubleCodebook) {
                innov2.fill(0f)
                innovationUnquant(mode, innov2, bits)
                val scale = 0.454545f * energy
                for (i in 0 until SUBFRAME) innov2[i] = scale * innov2[i]
                for (i in 0 until SUBFRAME) innov[i] = innov[i] + innov2[i]
            }
            for (i in 0 until SUBFRAME) excBuf[exc + i] = exc32[i] + innov[i]
            innovationSave?.let { save -> for (i in 0 until SUBFRAME) save[innovationSaveAt + offset + i] = innov[i] }

            if (submode == 1) {
                var g = olPitchCoef
                g = 1.5f * (g - .2f)
                if (g < 0) g = 0f
                if (g > 1f) g = 1f
                excBuf.fill(0f, exc, exc + SUBFRAME)
                while (vocOffset < SUBFRAME) {
                    if (vocOffset >= 0) excBuf[exc + vocOffset] = sqrt((2 * olPitch).toDouble()).toFloat() * (g * olGain)
                    vocOffset += olPitch
                }
                vocOffset -= SUBFRAME
                for (i in 0 until SUBFRAME) {
                    val exci = excBuf[exc + i]
                    excBuf[exc + i] = (.7f * excBuf[exc + i] + .3f * vocM1) + ((1f - .85f * g) * innov[i] - (.15f * g) * vocM2)
                    vocM1 = exci
                    vocM2 = innov[i]
                    vocMean = .8f * vocMean + .2f * excBuf[exc + i]
                    excBuf[exc + i] -= vocMean
                }
            }
        }

        if (mode.combGain > 0) {
            SpeexDsp.multicomb(excBuf, EXC - SUBFRAME, out, outAt, 2 * SUBFRAME, bestPitch, 40, mode.combGain)
            SpeexDsp.multicomb(excBuf, EXC + SUBFRAME, out, outAt + 2 * SUBFRAME, 2 * SUBFRAME, bestPitch, 40, mode.combGain)
        } else {
            excBuf.copyInto(out, outAt, EXC - SUBFRAME, EXC - SUBFRAME + FRAME)
        }

        for (sub in 0 until SUBFRAMES) {
            val sp = outAt + SUBFRAME * sub
            SpeexDsp.lspInterpolate(oldQlsp, qlsp, interpQlsp, ORDER, sub, SUBFRAMES, 0.002f)
            SpeexDsp.lspToLpc(interpQlsp, ak, ORDER)
            var pi = 1f
            var i = 0
            while (i < ORDER) {
                pi += ak[i + 1] - ak[i]
                i += 2
            }
            piGain[sub] = pi
            SpeexDsp.iir(out, sp, interpQlpc, out, sp, SUBFRAME, ORDER, memSp)
            ak.copyInto(interpQlpc)
        }
        SpeexDsp.highpass(out, outAt, FRAME, if (wideband) 3 else 1, memHp)

        qlsp.copyInto(oldQlsp)
        first = false
        lastPitch = bestPitch
        lastPitchGain = (.25 * pitchAverage).toFloat()
        return 0
    }

    /** `speex_inband_handler`, with only the stereo request registered, as speexdec registers it. */
    private fun inBand(bits: SpeexBits) {
        val id = bits.unpack(4)
        val stereo = stereo
        if (id == STEREO_REQUEST && stereo != null) {
            stereo.request(bits)
            return
        }
        bits.advance(
            when {
                id < 2 -> 1
                id < 8 -> 4
                id < 10 -> 8
                id < 12 -> 16
                id < 14 -> 32
                else -> 64
            },
        )
    }

    private fun lspUnquantNb(bits: SpeexBits) {
        for (i in 0 until ORDER) qlsp[i] = (.25 * i + .25).toFloat()
        var id = bits.unpack(6)
        for (i in 0 until 10) qlsp[i] = (qlsp[i] + 0.0039062 * SpeexTables.LSP_NB[id * 10 + i]).toFloat()
        id = bits.unpack(6)
        for (i in 0 until 5) qlsp[i] = (qlsp[i] + 0.0019531 * SpeexTables.LSP_NB_LOW1[id * 5 + i]).toFloat()
        id = bits.unpack(6)
        for (i in 0 until 5) qlsp[i] = (qlsp[i] + 0.00097656 * SpeexTables.LSP_NB_LOW2[id * 5 + i]).toFloat()
        id = bits.unpack(6)
        for (i in 0 until 5) qlsp[i + 5] = (qlsp[i + 5] + 0.0019531 * SpeexTables.LSP_NB_HIGH1[id * 5 + i]).toFloat()
        id = bits.unpack(6)
        for (i in 0 until 5) qlsp[i + 5] = (qlsp[i + 5] + 0.00097656 * SpeexTables.LSP_NB_HIGH2[id * 5 + i]).toFloat()
    }

    private fun lspUnquantLbr(bits: SpeexBits) {
        for (i in 0 until ORDER) qlsp[i] = (.25 * i + .25).toFloat()
        var id = bits.unpack(6)
        for (i in 0 until 10) qlsp[i] = (qlsp[i] + 0.0039062 * SpeexTables.LSP_NB[id * 10 + i]).toFloat()
        id = bits.unpack(6)
        for (i in 0 until 5) qlsp[i] = (qlsp[i] + 0.0019531 * SpeexTables.LSP_NB_LOW1[id * 5 + i]).toFloat()
        id = bits.unpack(6)
        for (i in 0 until 5) qlsp[i + 5] = (qlsp[i + 5] + 0.0019531 * SpeexTables.LSP_NB_HIGH1[id * 5 + i]).toFloat()
    }

    /** `pitch_unquant_3tap`: three taps of the past excitation around the pitch. */
    private fun pitchUnquant3tap(exc: Int, start: Int, params: SpeexLtpParams, bits: SpeexBits) {
        val pitch = bits.unpack(params.pitchBits) + start
        val index = bits.unpack(params.gainBits)
        for (k in 0 until 3) pitchGain[k] = (0.015625 * params.gains[index * 4 + k] + .5).toFloat()
        pitchOut[0] = pitch
        exc32.fill(0f)
        for (i in 0 until 3) {
            val pp = pitch + 1 - i
            val gain = pitchGain[2 - i]
            val tmp1 = minOf(SUBFRAME, pp)
            for (j in 0 until tmp1) exc32[j] = exc32[j] + gain * excBuf[exc + j - pp]
            val tmp3 = minOf(SUBFRAME, pp + pitch)
            for (j in tmp1 until tmp3) exc32[j] = exc32[j] + gain * excBuf[exc + j - pp - pitch]
        }
    }

    /** `forced_pitch_unquant`: the excitation repeated at the open-loop pitch with a fixed gain. */
    private fun forcedPitchUnquant(exc: Int, start: Int, coef: Float) {
        var pitchCoef = coef
        if (pitchCoef > .99) pitchCoef = (.99).toFloat()
        for (i in 0 until SUBFRAME) {
            exc32[i] = excBuf[exc + i - start] * pitchCoef
            excBuf[exc + i] = exc32[i]
        }
        pitchOut[0] = start
        pitchGain[0] = 0f
        pitchGain[2] = 0f
        pitchGain[1] = pitchCoef
    }

    private fun innovationUnquant(mode: SpeexSubmode, out: FloatArray, bits: SpeexBits) {
        when (mode.innovation) {
            SpeexInnovation.SPLIT -> splitUnquant(mode.split!!, out, SUBFRAME, bits)
            SpeexInnovation.NOISE -> for (i in 0 until SUBFRAME) out[i] = SpeexDsp.random(1f, seed)
            SpeexInnovation.NONE -> Unit
        }
    }

    companion object {
        const val FRAME = 160
        const val SUBFRAME = 40
        const val SUBFRAMES = 4
        const val ORDER = 10
        const val PITCH_START = 17
        const val PITCH_END = 144
        const val STEREO_REQUEST = 9

        /** Where the frame starts in the excitation buffer, after the history pitch prediction reads. */
        private const val EXC = 2 * PITCH_END + SUBFRAME + 6

        /** `wb_skip_table`: the bits of a wideband layer of each sub-mode, which narrowband skips. */
        private val WB_SKIP = intArrayOf(0, 36, 112, 192, 352, 0, 0, 0)

        /** `split_cb_shape_sign_unquant`: sub-vectors from a shape codebook, each optionally negated. */
        fun splitUnquant(params: SpeexSplitParams, out: FloatArray, length: Int, bits: SpeexBits) {
            val signs = IntArray(params.subvects)
            val indices = IntArray(params.subvects)
            for (i in 0 until params.subvects) {
                signs[i] = if (params.haveSign) bits.unpack(1) else 0
                indices[i] = bits.unpack(params.shapeBits)
            }
            for (i in 0 until params.subvects) {
                val s = if (signs[i] != 0) -1f else 1f
                for (j in 0 until params.subvectSize) {
                    val at = params.subvectSize * i + j
                    out[at] = (out[at] + s * 0.03125 * params.shapes[indices[i] * params.subvectSize + j]).toFloat()
                }
            }
        }
    }
}

/** The sub-band CELP decoder of `sb_celp.c`: a high band above a [low] layer, joined by a QMF. */
internal class SpeexSubband(private val low: SpeexLayer, private val ultra: Boolean) : SpeexLayer() {
    private val bandSize = low.frameSize
    private val subframeSize = if (ultra) 80 else 40
    private val subframes = bandSize / subframeSize
    override val frameSize = 2 * bandSize
    override val lookahead = 2 * low.lookahead

    private val submodes = if (ultra) SpeexModes.ULTRA_WIDEBAND else SpeexModes.WIDEBAND
    private val foldingGain = if (ultra) 0.7f else 0.9f
    private var submode = if (ultra) 1 else 3
    private var first = true
    private val g0Mem = FloatArray(QMF_ORDER)
    private val g1Mem = FloatArray(QMF_ORDER)
    private val excBuf = FloatArray(subframeSize)
    private val oldQlsp = FloatArray(ORDER)
    private val interpQlpc = FloatArray(ORDER)
    private val piGain = FloatArray(subframes)
    private val excRms = FloatArray(subframes)
    private val memSp = FloatArray(2 * ORDER)
    private val seed = intArrayOf(1000)
    private var lastEnergy = 0f
    override val dtxStatus: Boolean get() = low.dtxStatus

    private val qlsp = FloatArray(ORDER)
    private val interpQlsp = FloatArray(ORDER)
    private val ak = FloatArray(ORDER)
    private val exc = FloatArray(subframeSize)
    private val innov2 = FloatArray(subframeSize)
    private val lowPiGain = FloatArray(subframes)
    private val lowExcRms = FloatArray(subframes)

    override fun reset() {
        low.reset()
        submode = if (ultra) 1 else 3
        first = true
        g0Mem.fill(0f)
        g1Mem.fill(0f)
        excBuf.fill(0f)
        oldQlsp.fill(0f)
        interpQlpc.fill(0f)
        memSp.fill(0f)
        seed[0] = 1000
        lastEnergy = 0f
    }

    override fun piGain(out: FloatArray) {
        piGain.copyInto(out)
    }

    override fun excitationRms(out: FloatArray) {
        excRms.copyInto(out)
    }

    override fun decode(bits: SpeexBits, out: FloatArray, outAt: Int): Int {
        // The low layer leaves its innovation in the upper half, where the high band goes.
        val high = outAt + bandSize
        low.innovationSave = out
        low.innovationSaveAt = high
        val ret = low.decode(bits, out, outAt)
        if (ret != 0) return ret

        val wide = if (bits.remaining > 0) bits.peek() else 0
        if (wide != 0) {
            bits.unpack(1)
            submode = bits.unpack(3)
        } else {
            submode = 0
        }
        if (submode != 0 && submodes[submode] == null) return -2

        val mode = submodes[submode]
        if (mode == null) {
            if (low.dtxStatus) {
                // sb_decode_lost for a frame in discontinuous transmission: comfort noise.
                first = true
                for (i in 0 until bandSize) out[high + i] = SpeexDsp.random(lastEnergy, seed)
                SpeexDsp.iir(out, high, interpQlpc, out, high, bandSize, ORDER, memSp)
                SpeexDsp.qmfSynth(out, outAt, high, SpeexTables.QMF, out, outAt, frameSize, QMF_ORDER, g0Mem, g1Mem)
                return 0
            }
            for (i in 0 until bandSize) out[high + i] = 1e-15f
            first = true
            SpeexDsp.iir(out, high, interpQlpc, out, high, bandSize, ORDER, memSp)
            SpeexDsp.qmfSynth(out, outAt, high, SpeexTables.QMF, out, outAt, frameSize, QMF_ORDER, g0Mem, g1Mem)
            return 0
        }

        low.piGain(lowPiGain)
        low.excitationRms(lowExcRms)
        for (i in 0 until ORDER) qlsp[i] = (.3125 * i + .75).toFloat()
        var id = bits.unpack(6)
        for (i in 0 until ORDER) qlsp[i] = (qlsp[i] + 0.0039062 * SpeexTables.LSP_HIGH[id * ORDER + i]).toFloat()
        id = bits.unpack(6)
        for (i in 0 until ORDER) qlsp[i] = (qlsp[i] + 0.0019531 * SpeexTables.LSP_HIGH2[id * ORDER + i]).toFloat()
        if (first) qlsp.copyInto(oldQlsp)

        var energySum = 0f
        for (sub in 0 until subframes) {
            val offset = subframeSize * sub
            val sp = high + offset
            innovationSave?.fill(0f, innovationSaveAt + 2 * offset, innovationSaveAt + 2 * offset + 2 * subframeSize)

            SpeexDsp.lspInterpolate(oldQlsp, qlsp, interpQlsp, ORDER, sub, subframes, (.05).toFloat())
            SpeexDsp.lspToLpc(interpQlsp, ak, ORDER)
            var pi = 1f
            var rh = 1f
            var i = 0
            while (i < ORDER) {
                rh += ak[i + 1] - ak[i]
                pi += ak[i] + ak[i + 1]
                i += 2
            }
            piGain[sub] = pi
            val rl = lowPiGain[sub]
            val filterRatio = ((rl + .01) / (rh + .01)).toFloat()

            exc.fill(0f)
            if (mode.innovation == SpeexInnovation.NONE) {
                val quant = bits.unpack(5)
                var g = exp((.125f * (quant - 10).toFloat()).toDouble()).toFloat()
                g /= filterRatio
                var k = 0
                while (k < subframeSize) {
                    exc[k] = (foldingGain * out[high + offset + k]) * g
                    exc[k + 1] = -((foldingGain * out[high + offset + k + 1]) * g)
                    k += 2
                }
            } else {
                val qgc = bits.unpack(4)
                val el = lowExcRms[sub]
                var gc = (0.87360 * SpeexTables.HIGH_GAINS[qgc]).toFloat()
                if (subframeSize == 80) gc = 1.4142f * gc
                val scale = (gc * el) / filterRatio
                SpeexNarrowband.splitUnquant(mode.split!!, exc, subframeSize, bits)
                for (k in 0 until subframeSize) exc[k] = scale * exc[k]
                if (mode.doubleCodebook) {
                    innov2.fill(0f)
                    SpeexNarrowband.splitUnquant(mode.split, innov2, subframeSize, bits)
                    val scale2 = 0.4f * scale
                    for (k in 0 until subframeSize) innov2[k] = scale2 * innov2[k]
                    for (k in 0 until subframeSize) exc[k] = exc[k] + innov2[k]
                }
            }
            innovationSave?.let { save -> for (k in 0 until subframeSize) save[innovationSaveAt + 2 * offset + 2 * k] = exc[k] }

            // The reference filters the previous subframe's excitation here.
            SpeexDsp.iir(excBuf, 0, interpQlpc, out, sp, subframeSize, ORDER, memSp)
            exc.copyInto(excBuf)
            ak.copyInto(interpQlpc)
            excRms[sub] = SpeexDsp.rms(excBuf, 0, subframeSize)
            energySum += excRms[sub] * excRms[sub] / subframes.toFloat()
        }
        lastEnergy = sqrt(energySum.toDouble()).toFloat()
        SpeexDsp.qmfSynth(out, outAt, high, SpeexTables.QMF, out, outAt, frameSize, QMF_ORDER, g0Mem, g1Mem)
        qlsp.copyInto(oldQlsp)
        first = false
        return 0
    }

    private companion object {
        const val ORDER = 8
        const val QMF_ORDER = 64
    }
}

/** The in-band intensity stereo of `stereo.c`, decoded in float. */
internal class SpeexStereo {
    private var balance = 1.0f
    private var energyRatio = .5f
    private var smoothLeft = 1f
    private var smoothRight = 1f

    fun reset() {
        balance = 1.0f
        energyRatio = .5f
        smoothLeft = 1f
        smoothRight = 1f
    }

    /** `speex_std_stereo_request_handler`. */
    fun request(bits: SpeexBits) {
        val sign = if (bits.unpack(1) != 0) -1f else 1f
        val dexp = bits.unpack(5).toFloat()
        balance = exp(sign * .25 * dexp).toFloat()
        energyRatio = SpeexTables.STEREO_RATIOS[bits.unpack(2)]
    }

    /** `speex_decode_stereo`: [frameSize] mono samples at the start of [data] spread to interleaved stereo. */
    fun decode(data: FloatArray, at: Int, frameSize: Int) {
        val right = 1f / sqrt(energyRatio * (1.0 + balance)).toFloat()
        val left = sqrt(balance.toDouble()).toFloat() * right
        for (i in frameSize - 1 downTo 0) {
            val tmp = data[at + i]
            smoothLeft = smoothLeft * SMOOTH_KEEP + left * SMOOTH_TAKE
            smoothRight = smoothRight * SMOOTH_KEEP + right * SMOOTH_TAKE
            data[at + 2 * i] = smoothLeft * tmp
            data[at + 2 * i + 1] = smoothRight * tmp
        }
    }

    private companion object {
        // Double literals that MULT16_16 casts to float.
        val SMOOTH_KEEP = (0.98).toFloat()
        val SMOOTH_TAKE = (0.02).toFloat()
    }
}

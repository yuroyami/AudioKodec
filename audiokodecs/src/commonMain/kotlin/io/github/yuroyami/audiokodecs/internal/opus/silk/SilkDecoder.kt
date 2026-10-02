package io.github.yuroyami.audiokodecs.internal.opus.silk

import io.github.yuroyami.audiokodecs.CorruptStreamException
import io.github.yuroyami.audiokodecs.internal.opus.RangeDecoder

/**
 * What the Opus layer tells the SILK decoder about each call, and what it
 * reads back; `silk_DecControlStruct`.
 */
internal class SilkDecoderControl {
    /** Channels the caller wants out; `nChannelsAPI`. */
    var channelsApi: Int = 1

    /** Channels the stream codes; `nChannelsInternal`. */
    var channelsInternal: Int = 1

    /** Output sample rate; `API_sampleRate`. */
    var apiSampleRate: Int = 48_000

    /** SILK's own sample rate, 8, 12 or 16 kHz; `internalSampleRate`. */
    var internalSampleRate: Int = 16_000

    /** Duration of the Opus frame being decoded, 10, 20, 40 or 60 ms; `payloadSize_ms`. */
    var payloadSizeMs: Int = 20

    /**
     * Output: the pitch lag of the last frame in samples at 48 kHz, or 0 when it
     * was not voiced; `prevPitchLag`. The Opus layer uses it to steer CELT's
     * concealment.
     */
    var prevPitchLag: Int = 0
}

/**
 * The SILK decoder: Opus's speech codec, used alone at the lower bitrates and
 * under CELT in hybrid mode.
 *
 * Each [decode] call produces one 10 or 20 ms SILK frame. An Opus frame of 40
 * or 60 ms carries two or three of them, so the Opus layer calls this once per
 * SILK frame, saying with `newPacketFlag` which call starts a new Opus frame;
 * the flags that cover the whole Opus frame are read at that first call.
 *
 * Stereo is coded as mid and side, with the side predicted from the mid and
 * sometimes left out entirely, and is turned back into left and right after
 * decoding. Lost frames are concealed, and a frame can also be rebuilt from the
 * low-bitrate copy (LBRR) the encoder may have put in the following packet.
 *
 * A bit-exact port of libopus 1.5.2's `silk/dec_API.c` and everything it calls,
 * in the configuration without deep concealment, OSCE or DRED. SILK is fixed
 * point in every libopus build, so this is integer arithmetic throughout.
 */
internal class SilkDecoder {

    private val channels = arrayOf(SilkChannelDecoder(), SilkChannelDecoder())

    // stereo_dec_state.
    private val predPrevQ13 = IntArray(2)
    private val sMid = IntArray(2)
    private val sSide = IntArray(2)

    private var nChannelsApi = 0
    private var nChannelsInternal = 0
    private var prevDecodeOnlyMiddle = 0

    // Per-call scratch: the mid and side (later left and right) signals with
    // two samples of history in front, the resampled channel, the predictors
    // and the pulses of skipped redundant frames.
    private val work = arrayOf(IntArray(MAX_FRAME_LENGTH + 2), IntArray(MAX_FRAME_LENGTH + 2))
    private val resampled = IntArray(MAX_FRAME_LENGTH * 3)
    private val msPredQ13 = IntArray(2)
    private val skippedPulses = IntArray(MAX_FRAME_LENGTH)

    init {
        reset()
    }

    /** `silk_ResetDecoder` and `silk_InitDecoder`, which are the same in this build. */
    fun reset() {
        for (channel in channels) channel.reset()
        predPrevQ13.fill(0)
        sMid.fill(0)
        sSide.fill(0)
        prevDecodeOnlyMiddle = 0
    }

    /**
     * Translates `silk_Decode`: decodes one SILK frame into [out] from
     * [outOffset], as 16-bit samples at the control's output rate,
     * interleaved when it asks for two channels, and returns the samples
     * written per channel.
     *
     * [lostFlag] is [FLAG_DECODE_NORMAL], [FLAG_PACKET_LOST] to conceal a
     * frame that never arrived, for which [range] is null, or
     * [FLAG_DECODE_LBRR] to rebuild the previous frame from the redundant
     * copy at the front of this packet. A setting the reference would reject
     * with an error code throws [CorruptStreamException].
     */
    fun decode(
        control: SilkDecoderControl,
        lostFlag: Int,
        newPacketFlag: Boolean,
        range: RangeDecoder?,
        out: IntArray,
        outOffset: Int,
    ): Int {
        require(range != null || lostFlag == FLAG_PACKET_LOST) { "only a lost frame can be decoded without data" }
        val apiRate = control.apiSampleRate
        if (apiRate != 8000 && apiRate != 12000 && apiRate != 16000 && apiRate != 24000 && apiRate != 48000) {
            throw CorruptStreamException("SILK cannot produce audio at $apiRate Hz")
        }
        val internalChannels = control.channelsInternal
        val mid = channels[0]
        val side = channels[1]
        var decodeOnlyMiddle = 0
        msPredQ13.fill(0)

        if (newPacketFlag) {
            for (n in 0 until internalChannels) channels[n].nFramesDecoded = 0
        }

        // A stream that turns stereo starts the side channel from nothing.
        if (internalChannels > nChannelsInternal) {
            side.reset()
        }

        val stereoToMono = internalChannels == 1 && nChannelsInternal == 2 &&
            control.internalSampleRate == 1000 * mid.fsKHz

        if (mid.nFramesDecoded == 0) {
            for (n in 0 until internalChannels) {
                val channel = channels[n]
                when (control.payloadSizeMs) {
                    // Zero means a lost packet of unknown length, taken as 10 ms.
                    0, 10 -> {
                        channel.nFramesPerPacket = 1
                        channel.nbSubfr = 2
                    }
                    20 -> {
                        channel.nFramesPerPacket = 1
                        channel.nbSubfr = 4
                    }
                    40 -> {
                        channel.nFramesPerPacket = 2
                        channel.nbSubfr = 4
                    }
                    60 -> {
                        channel.nFramesPerPacket = 3
                        channel.nbSubfr = 4
                    }
                    else -> throw CorruptStreamException("a SILK frame cannot last ${control.payloadSizeMs} ms")
                }
                val fsKHz = (control.internalSampleRate shr 10) + 1
                if (fsKHz != 8 && fsKHz != 12 && fsKHz != 16) {
                    throw CorruptStreamException("SILK cannot run at ${control.internalSampleRate} Hz")
                }
                if (!channel.setFs(fsKHz, apiRate)) {
                    throw CorruptStreamException("SILK cannot resample $fsKHz kHz to $apiRate Hz")
                }
            }
        }

        if (control.channelsApi == 2 && internalChannels == 2 && (nChannelsApi == 1 || nChannelsInternal == 1)) {
            predPrevQ13.fill(0)
            sSide.fill(0)
            side.resampler.copyFrom(mid.resampler)
        }
        nChannelsApi = control.channelsApi
        nChannelsInternal = internalChannels

        if (lostFlag != FLAG_PACKET_LOST && mid.nFramesDecoded == 0) {
            val decoder = range!!
            // The first call for a packet reads, for every channel, which of
            // its frames are active and which carry a redundant copy.
            for (n in 0 until internalChannels) {
                val channel = channels[n]
                for (i in 0 until channel.nFramesPerPacket) {
                    channel.vadFlags[i] = if (decoder.decodeBitLogp(1)) 1 else 0
                }
                channel.lbrrFlag = if (decoder.decodeBitLogp(1)) 1 else 0
            }
            for (n in 0 until internalChannels) {
                val channel = channels[n]
                channel.lbrrFlags.fill(0)
                if (channel.lbrrFlag != 0) {
                    if (channel.nFramesPerPacket == 1) {
                        channel.lbrrFlags[0] = 1
                    } else {
                        val table = if (channel.nFramesPerPacket == 2) {
                            SilkTables.LBRR_FLAGS_2_ICDF
                        } else {
                            SilkTables.LBRR_FLAGS_3_ICDF
                        }
                        val symbol = decoder.decodeIcdf(table, 8) + 1
                        for (i in 0 until channel.nFramesPerPacket) {
                            channel.lbrrFlags[i] = (symbol shr i) and 1
                        }
                    }
                }
            }

            if (lostFlag == FLAG_DECODE_NORMAL) {
                // The redundant frames come first in the packet; decoding
                // normally means reading past them.
                for (i in 0 until mid.nFramesPerPacket) {
                    for (n in 0 until internalChannels) {
                        val channel = channels[n]
                        if (channel.lbrrFlags[i] == 0) continue
                        if (internalChannels == 2 && n == 0) {
                            stereoDecodePred(decoder, msPredQ13)
                            if (side.lbrrFlags[i] == 0) {
                                decodeOnlyMiddle = decoder.decodeIcdf(SilkTables.STEREO_ONLY_CODE_MID_ICDF, 8)
                            }
                        }
                        val condCoding = if (i > 0 && channel.lbrrFlags[i - 1] != 0) {
                            CODE_CONDITIONALLY
                        } else {
                            CODE_INDEPENDENTLY
                        }
                        channel.decodeIndices(decoder, i, true, condCoding)
                        channel.decodePulses(
                            decoder,
                            skippedPulses,
                            channel.indices.signalType,
                            channel.indices.quantOffsetType,
                            channel.frameLength,
                        )
                    }
                }
            }
        }

        // The stereo predictors, unless this frame is being concealed.
        if (internalChannels == 2) {
            val frame = mid.nFramesDecoded
            if (lostFlag == FLAG_DECODE_NORMAL ||
                (lostFlag == FLAG_DECODE_LBRR && mid.lbrrFlags[frame] == 1)
            ) {
                stereoDecodePred(range!!, msPredQ13)
                decodeOnlyMiddle = if ((lostFlag == FLAG_DECODE_NORMAL && side.vadFlags[frame] == 0) ||
                    (lostFlag == FLAG_DECODE_LBRR && side.lbrrFlags[frame] == 0)
                ) {
                    range.decodeIcdf(SilkTables.STEREO_ONLY_CODE_MID_ICDF, 8)
                } else {
                    0
                }
            } else {
                msPredQ13[0] = predPrevQ13[0]
                msPredQ13[1] = predPrevQ13[1]
            }
        }

        // The side channel's first frame after mid-only frames starts from a
        // clean state, since nothing it remembers is current.
        if (internalChannels == 2 && decodeOnlyMiddle == 0 && prevDecodeOnlyMiddle == 1) {
            side.outBuf.fill(0)
            side.sLpcQ14Buf.fill(0)
            side.lagPrev = 100
            side.lastGainIndex = 10
            side.prevSignalType = TYPE_NO_VOICE_ACTIVITY
            side.firstFrameAfterReset = 1
        }

        val hasSide = if (lostFlag == FLAG_DECODE_NORMAL) {
            decodeOnlyMiddle == 0
        } else {
            prevDecodeOnlyMiddle == 0 ||
                (internalChannels == 2 && lostFlag == FLAG_DECODE_LBRR && side.lbrrFlags[side.nFramesDecoded] == 1)
        }

        var decodedSamples = 0
        for (n in 0 until internalChannels) {
            val channel = channels[n]
            if (n == 0 || hasSide) {
                val frameIndex = mid.nFramesDecoded - n
                val condCoding = when {
                    frameIndex <= 0 -> CODE_INDEPENDENTLY
                    lostFlag == FLAG_DECODE_LBRR ->
                        if (channel.lbrrFlags[frameIndex - 1] != 0) CODE_CONDITIONALLY else CODE_INDEPENDENTLY
                    // Skipping side frames leaves the side's pitch history
                    // intact, so it needs no extra scaling.
                    n > 0 && prevDecodeOnlyMiddle != 0 -> CODE_INDEPENDENTLY_NO_LTP_SCALING
                    else -> CODE_CONDITIONALLY
                }
                decodedSamples = channel.decodeFrame(range, work[n], 2, lostFlag, condCoding)
            } else {
                work[n].fill(0, 2, 2 + decodedSamples)
            }
            channel.nFramesDecoded++
        }

        if (control.channelsApi == 2 && internalChannels == 2) {
            stereoMsToLr(work[0], work[1], msPredQ13, mid.fsKHz, decodedSamples)
        } else {
            // Keep the one-sample delay the stereo path has, so that switching
            // between mono and stereo does not shift the signal.
            sMid.copyInto(work[0], 0, 0, 2)
            work[0].copyInto(sMid, 0, decodedSamples, decodedSamples + 2)
        }

        val samplesOut = decodedSamples * apiRate / (mid.fsKHz * 1000)

        val stereoOut = control.channelsApi == 2
        for (n in 0 until minOf(control.channelsApi, internalChannels)) {
            if (stereoOut) {
                channels[n].resampler.resample(resampled, 0, work[n], 1, decodedSamples)
                for (i in 0 until samplesOut) out[outOffset + n + 2 * i] = resampled[i]
            } else {
                channels[n].resampler.resample(out, outOffset, work[n], 1, decodedSamples)
            }
        }

        // Stereo out from a mono stream.
        if (stereoOut && internalChannels == 1) {
            if (stereoToMono) {
                // The stream has just turned mono; run the right channel's
                // resampler too, so its state is current if stereo returns.
                side.resampler.resample(resampled, 0, work[0], 1, decodedSamples)
                for (i in 0 until samplesOut) out[outOffset + 1 + 2 * i] = resampled[i]
            } else {
                for (i in 0 until samplesOut) out[outOffset + 1 + 2 * i] = out[outOffset + 2 * i]
            }
        }

        control.prevPitchLag = if (mid.prevSignalType == TYPE_VOICED) {
            mid.lagPrev * PITCH_LAG_TO_48K[(mid.fsKHz - 8) shr 2]
        } else {
            0
        }

        if (lostFlag == FLAG_PACKET_LOST) {
            // Without this the gain would jump back up after a loss in the
            // middle of a fade.
            for (i in 0 until nChannelsInternal) channels[i].lastGainIndex = 10
        } else {
            prevDecodeOnlyMiddle = decodeOnlyMiddle
        }
        return samplesOut
    }

    /**
     * Translates `silk_stereo_MS_to_LR`: adds the predicted part back to the
     * side signal, interpolating the predictors over the first 8 ms, and
     * converts mid and side to left and right. Both signals start two samples
     * early, with history from the previous frame.
     */
    private fun stereoMsToLr(x1: IntArray, x2: IntArray, predQ13: IntArray, fsKHz: Int, frameLength: Int) {
        sMid.copyInto(x1, 0, 0, 2)
        sSide.copyInto(x2, 0, 0, 2)
        x1.copyInto(sMid, 0, frameLength, frameLength + 2)
        x2.copyInto(sSide, 0, frameLength, frameLength + 2)

        var pred0Q13 = predPrevQ13[0]
        var pred1Q13 = predPrevQ13[1]
        val interpolationLength = STEREO_INTERP_LEN_MS * fsKHz
        val denomQ16 = (1 shl 16) / interpolationLength
        val delta0Q13 = rshiftRound(smulbb(predQ13[0] - predPrevQ13[0], denomQ16), 16)
        val delta1Q13 = rshiftRound(smulbb(predQ13[1] - predPrevQ13[1], denomQ16), 16)
        for (n in 0 until interpolationLength) {
            pred0Q13 += delta0Q13
            pred1Q13 += delta1Q13
            x2[n + 1] = predictSide(x1, x2, n, pred0Q13, pred1Q13)
        }
        pred0Q13 = predQ13[0]
        pred1Q13 = predQ13[1]
        for (n in interpolationLength until frameLength) {
            x2[n + 1] = predictSide(x1, x2, n, pred0Q13, pred1Q13)
        }
        predPrevQ13[0] = predQ13[0].toShort().toInt()
        predPrevQ13[1] = predQ13[1].toShort().toInt()

        for (n in 0 until frameLength) {
            val sum = x1[n + 1] + x2[n + 1]
            val difference = x1[n + 1] - x2[n + 1]
            x1[n + 1] = sat16(sum)
            x2[n + 1] = sat16(difference)
        }
    }

    /**
     * One side sample with its prediction added: from a lowpassed mid through
     * the first predictor, and from the mid itself through the second.
     */
    private fun predictSide(x1: IntArray, x2: IntArray, n: Int, pred0Q13: Int, pred1Q13: Int): Int {
        var sum = (x1[n] + x1[n + 2] + (x1[n + 1] shl 1)) shl 9
        sum = smlawb(x2[n + 1] shl 8, sum, pred0Q13)
        sum = smlawb(sum, x1[n + 1] shl 11, pred1Q13)
        return sat16(rshiftRound(sum, 8))
    }

    private companion object {
        const val STEREO_INTERP_LEN_MS = 8

        /** `SILK_FIX_CONST(0.5 / STEREO_QUANT_SUB_STEPS, 16)`. */
        const val STEREO_HALF_SUB_STEP_Q16 = 6554

        /** Samples at 48 kHz per sample at 8, 12 and 16 kHz. */
        val PITCH_LAG_TO_48K = intArrayOf(6, 4, 3)

        /**
         * Translates `silk_stereo_decode_pred`: the two stereo predictors, each
         * a coarse level from a joint symbol and a fine step within it.
         */
        fun stereoDecodePred(range: RangeDecoder, predQ13: IntArray) {
            val joint = range.decodeIcdf(SilkTables.STEREO_PRED_JOINT_ICDF, 8)
            val coarse0 = joint / 5
            val coarse1 = joint - 5 * coarse0
            val level0 = range.decodeIcdf(SilkTables.UNIFORM3_ICDF, 8)
            val step0 = range.decodeIcdf(SilkTables.UNIFORM5_ICDF, 8)
            val level1 = range.decodeIcdf(SilkTables.UNIFORM3_ICDF, 8)
            val step1 = range.decodeIcdf(SilkTables.UNIFORM5_ICDF, 8)
            predQ13[0] = dequantPredictor(level0 + 3 * coarse0, step0)
            predQ13[1] = dequantPredictor(level1 + 3 * coarse1, step1)
            // Coded as the first minus the second, which is how it is applied.
            predQ13[0] -= predQ13[1]
        }

        private fun dequantPredictor(level: Int, step: Int): Int {
            val lowQ13 = SilkTables.STEREO_PRED_QUANT_Q13[level]
            val stepQ13 = smulwb(SilkTables.STEREO_PRED_QUANT_Q13[level + 1] - lowQ13, STEREO_HALF_SUB_STEP_Q16)
            return smlabb(lowQ13, stepQ13, 2 * step + 1)
        }
    }
}

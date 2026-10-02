package io.github.yuroyami.audiokodecs.internal.opus

import io.github.yuroyami.audiokodecs.CorruptStreamException
import io.github.yuroyami.audiokodecs.internal.opus.celt.CeltDecoder
import io.github.yuroyami.audiokodecs.internal.opus.celt.CeltMath
import io.github.yuroyami.audiokodecs.internal.opus.celt.CeltWindowTable
import io.github.yuroyami.audiokodecs.internal.opus.silk.SilkDecoder
import io.github.yuroyami.audiokodecs.internal.opus.silk.SilkDecoderControl

/**
 * Decodes one Opus stream of one or two channels: the layer that chooses
 * between SILK, CELT and both for each frame, and hides the joins.
 *
 * Opus can change mode between any two packets, and a change would click if
 * the two codecs were simply switched. So a packet that changes mode may carry
 * a short redundant CELT frame, which is cross-faded across the join, and when
 * it does not the decoder conceals a few milliseconds of the old mode to fade
 * from instead. Lost packets are concealed by whichever codec was last in use.
 *
 * Translated from libopus `opus_decoder.c` and `opus_pcm_soft_clip` in
 * `opus.c`, float build, without the deep packet-loss concealment.
 */
internal class OpusDecoder(
    /** Output rate: 48, 24, 16, 12 or 8 kHz. */
    val sampleRate: Int,
    /** Output channels, one or two. */
    val channels: Int,
) {
    init {
        require(sampleRate == 48_000 || sampleRate == 24_000 || sampleRate == 16_000 ||
            sampleRate == 12_000 || sampleRate == 8_000) { "Opus decodes at 8, 12, 16, 24 or 48 kHz, not $sampleRate" }
        require(channels == 1 || channels == 2) { "an Opus decoder has one or two channels, not $channels" }
    }

    private val silk = SilkDecoder()
    private val celt = CeltDecoder(channels, sampleRate)
    private val control = SilkDecoderControl().also {
        it.apiSampleRate = sampleRate
        it.channelsApi = channels
    }

    /** Output gain in Q8 decibels, from an Ogg Opus header, which is applied to every sample. */
    var gain: Int = 0

    private var streamChannels = channels
    private var bandwidth = 0
    private var mode = 0
    private var prevMode = 0
    private var frameSize = sampleRate / 400
    private var prevRedundancy = false
    private val softClipMemory = FloatArray(2)

    // Scratch for one frame: SILK's 16-bit output, which lasts at most
    // 60 ms, and the 5 ms of a redundant frame or of concealment to fade
    // across a change of mode. A concealment decoded for a change never
    // itself starts another change, so one of each is enough.
    private val silkBuffer = IntArray(sampleRate * 3 / 50 * channels)
    private val transitionBuffer = FloatArray(sampleRate / 200 * channels)
    private val redundantBuffer = FloatArray(sampleRate / 200 * channels)

    /** The range decoder's final state for the last packet, which the encoder reports too. */
    var finalRange: Long = 0
        private set

    /** Samples per channel the last call decoded or concealed. */
    var lastPacketDuration: Int = 0
        private set

    /** `OPUS_RESET_STATE`. */
    fun reset() {
        streamChannels = channels
        bandwidth = 0
        mode = 0
        prevMode = 0
        frameSize = sampleRate / 400
        prevRedundancy = false
        softClipMemory.fill(0f)
        finalRange = 0
        lastPacketDuration = 0
        celt.reset()
        silk.reset()
    }

    /**
     * `opus_decode_native`: decodes the packet of [length] bytes at [offset],
     * or conceals one when [data] is null or [length] is zero, into [pcm] as
     * interleaved float samples. [frameSize] is the room in [pcm] per channel,
     * and for concealment the duration to conceal. [decodeFec] decodes the
     * redundant copy of the previous packet a SILK packet may carry instead
     * of the packet itself. [softClip] bends samples beyond full scale back
     * inside it, as the reference does when it decodes to 16-bit samples, and
     * a [selfDelimited] packet is one stream of a multistream packet. Returns
     * the samples written per channel.
     */
    fun decode(
        data: ByteArray?,
        offset: Int,
        length: Int,
        pcm: FloatArray,
        frameSize: Int,
        decodeFec: Boolean,
        softClip: Boolean = false,
        selfDelimited: Boolean = false,
    ): Int {
        if ((decodeFec || length == 0 || data == null) && frameSize % (sampleRate / 400) != 0) {
            throw IllegalArgumentException("concealment covers whole 2.5 ms steps, not $frameSize samples")
        }
        if (length == 0 || data == null) {
            var count = 0
            do {
                count += decodeFrame(null, 0, 0, pcm, count * channels, frameSize - count, false)
            } while (count < frameSize)
            lastPacketDuration = count
            return count
        }
        val packet = OpusPacket.parse(data, offset, length, selfDelimited)
        val packetMode = when (packet.mode) {
            OpusPacket.Mode.SILK -> MODE_SILK_ONLY
            OpusPacket.Mode.HYBRID -> MODE_HYBRID
            OpusPacket.Mode.CELT -> MODE_CELT_ONLY
        }
        val packetBandwidth = BANDWIDTH_NARROW + packet.bandwidth.ordinal
        val packetFrameSize = packet.frameSize * sampleRate / 48_000
        val packetChannels = packet.channels
        if (decodeFec) {
            if (frameSize < packetFrameSize || packetMode == MODE_CELT_ONLY || mode == MODE_CELT_ONLY) {
                return decode(null, 0, 0, pcm, frameSize, false, softClip)
            }
            val durationCopy = lastPacketDuration
            if (frameSize - packetFrameSize != 0) {
                try {
                    decode(null, 0, 0, pcm, frameSize - packetFrameSize, false, softClip)
                } catch (failure: CorruptStreamException) {
                    lastPacketDuration = durationCopy
                    throw failure
                }
            }
            mode = packetMode
            bandwidth = packetBandwidth
            this.frameSize = packetFrameSize
            streamChannels = packetChannels
            val first = packet.frames[0]
            decodeFrame(
                data, first.first, first.last - first.first + 1, pcm,
                channels * (frameSize - packetFrameSize), packetFrameSize, true,
            )
            lastPacketDuration = frameSize
            return frameSize
        }
        if (packet.frames.size * packetFrameSize > frameSize) {
            throw IllegalArgumentException("a $frameSize-sample buffer cannot hold this packet")
        }
        mode = packetMode
        bandwidth = packetBandwidth
        this.frameSize = packetFrameSize
        streamChannels = packetChannels
        var samples = 0
        for (frame in packet.frames) {
            samples += decodeFrame(
                data, frame.first, frame.last - frame.first + 1, pcm,
                samples * channels, frameSize - samples, false,
            )
        }
        lastPacketDuration = samples
        if (softClip) softClip(pcm, samples) else softClipMemory.fill(0f)
        return samples
    }

    /** `opus_decode_frame`. */
    private fun decodeFrame(
        dataIn: ByteArray?,
        dataOffset: Int,
        lengthIn: Int,
        pcm: FloatArray,
        pcmOffset: Int,
        frameSizeIn: Int,
        decodeFec: Boolean,
    ): Int {
        var data = dataIn
        var length = lengthIn
        var frameSize = frameSizeIn
        val f20 = sampleRate / 50
        val f10 = f20 shr 1
        val f5 = f10 shr 1
        val f2p5 = f5 shr 1
        if (frameSize < f2p5) throw IllegalArgumentException("an Opus frame is at least 2.5 ms")
        frameSize = minOf(frameSize, sampleRate / 25 * 3)
        if (length <= 1) {
            data = null
            frameSize = minOf(frameSize, this.frameSize)
        }
        var audioSize: Int
        val mode: Int
        val bandwidth: Int
        var range: RangeDecoder? = null
        if (data != null) {
            audioSize = this.frameSize
            mode = this.mode
            bandwidth = this.bandwidth
            range = RangeDecoder(data, dataOffset, length)
        } else {
            audioSize = frameSize
            mode = if (prevRedundancy) MODE_CELT_ONLY else prevMode
            bandwidth = 0
            if (mode == 0) {
                // Nothing has been decoded yet, so there is nothing to
                // conceal from.
                pcm.fill(0f, pcmOffset, pcmOffset + audioSize * channels)
                return audioSize
            }
            if (audioSize > f20) {
                var at = pcmOffset
                do {
                    val done = decodeFrame(null, 0, 0, pcm, at, minOf(audioSize, f20), false)
                    at += done * channels
                    audioSize -= done
                } while (audioSize > 0)
                return frameSize
            } else if (audioSize < f20) {
                if (audioSize > f10) {
                    audioSize = f10
                } else if (mode != MODE_SILK_ONLY && audioSize > f5 && audioSize < f10) {
                    audioSize = f5
                }
            }
        }

        var transition = false
        var pcmTransition: FloatArray? = null
        if (data != null && prevMode > 0 && (
                (mode == MODE_CELT_ONLY && prevMode != MODE_CELT_ONLY && !prevRedundancy) ||
                    (mode != MODE_CELT_ONLY && prevMode == MODE_CELT_ONLY)
                )
        ) {
            transition = true
        }
        if (transition && mode == MODE_CELT_ONLY) {
            pcmTransition = transitionBuffer
            decodeFrame(null, 0, 0, pcmTransition, 0, minOf(f5, audioSize), false)
        }
        if (audioSize > frameSize) throw IllegalArgumentException("the frame does not fit the buffer it was given")
        frameSize = audioSize

        if (mode != MODE_CELT_ONLY) {
            if (prevMode == MODE_CELT_ONLY) silk.reset()
            control.payloadSizeMs = maxOf(10, 1000 * audioSize / sampleRate)
            if (data != null) {
                control.channelsInternal = streamChannels
                control.internalSampleRate = if (mode == MODE_SILK_ONLY) {
                    when (bandwidth) {
                        BANDWIDTH_NARROW -> 8_000
                        BANDWIDTH_MEDIUM -> 12_000
                        else -> 16_000
                    }
                } else {
                    16_000
                }
            }
            val lostFlag = if (data == null) 1 else if (decodeFec) 2 else 0
            var decoded = 0
            do {
                val first = decoded == 0
                val got = try {
                    silk.decode(control, lostFlag, first, range, silkBuffer, decoded * channels)
                } catch (failure: CorruptStreamException) {
                    // Concealment and redundancy may fail quietly into
                    // silence; a real packet may not.
                    if (lostFlag == 0) throw failure
                    silkBuffer.fill(0, decoded * channels, minOf(silkBuffer.size, (decoded + frameSize) * channels))
                    frameSize
                }
                decoded += got
            } while (decoded < frameSize)
        }

        var startBand = 0
        var redundancy = false
        var redundancyBytes = 0
        var celtToSilk = false
        if (!decodeFec && mode != MODE_CELT_ONLY && data != null &&
            range!!.tell() + 17 + 20 * (if (mode == MODE_HYBRID) 1 else 0) <= 8 * length
        ) {
            redundancy = if (mode == MODE_HYBRID) range.decodeBitLogp(12) else true
            if (redundancy) {
                celtToSilk = range.decodeBitLogp(1)
                redundancyBytes = if (mode == MODE_HYBRID) {
                    range.decodeUniform(256) + 2
                } else {
                    length - ((range.tell() + 7) shr 3)
                }
                length -= redundancyBytes
                if (length * 8 < range.tell()) {
                    length = 0
                    redundancyBytes = 0
                    redundancy = false
                }
                range.shrinkStorage(redundancyBytes)
            }
        }
        if (mode != MODE_CELT_ONLY) startBand = 17
        if (redundancy) transition = false
        if (transition && mode != MODE_CELT_ONLY) {
            pcmTransition = transitionBuffer
            decodeFrame(null, 0, 0, pcmTransition, 0, minOf(f5, audioSize), false)
        }
        if (bandwidth != 0) {
            celt.endBand = when (bandwidth) {
                BANDWIDTH_NARROW -> 13
                BANDWIDTH_MEDIUM, BANDWIDTH_WIDE -> 17
                BANDWIDTH_SUPER_WIDE -> 19
                else -> 21
            }
        }
        celt.streamChannels = streamChannels
        val redundantAudio = if (redundancy) redundantBuffer else null
        var redundantRange = 0L
        if (redundancy && celtToSilk) {
            celt.startBand = 0
            celt.decode(data, dataOffset + length, redundancyBytes, redundantAudio!!, 0, f5, null)
            redundantRange = celt.finalRange
        }
        celt.startBand = startBand
        if (mode != MODE_SILK_ONLY) {
            val celtFrameSize = minOf(f20, frameSize)
            if (mode != prevMode && prevMode > 0 && !prevRedundancy) celt.reset()
            celt.decode(if (decodeFec) null else data, dataOffset, length, pcm, pcmOffset, celtFrameSize, range)
        } else {
            pcm.fill(0f, pcmOffset, pcmOffset + frameSize * channels)
            if (prevMode == MODE_HYBRID && !(redundancy && celtToSilk && prevRedundancy)) {
                // A hybrid frame leaves CELT's overlap running; decoding a
                // silent frame lets it ring out rather than stop dead.
                celt.startBand = 0
                celt.decode(SILENCE, 0, 2, pcm, pcmOffset, f2p5, null)
            }
        }
        if (mode != MODE_CELT_ONLY) {
            for (i in 0 until frameSize * channels) {
                pcm[pcmOffset + i] = pcm[pcmOffset + i] + ((1f / 32768f) * silkBuffer[i])
            }
        }
        val window = CeltWindowTable.WINDOW
        if (redundancy && !celtToSilk) {
            celt.reset()
            celt.startBand = 0
            celt.decode(data, dataOffset + length, redundancyBytes, redundantAudio!!, 0, f5, null)
            redundantRange = celt.finalRange
            smoothFade(
                pcm, pcmOffset + channels * (frameSize - f2p5), redundantAudio, channels * f2p5,
                pcm, pcmOffset + channels * (frameSize - f2p5), f2p5, window,
            )
        }
        if (redundancy && celtToSilk && (prevMode != MODE_SILK_ONLY || prevRedundancy)) {
            for (c in 0 until channels) {
                for (i in 0 until f2p5) pcm[pcmOffset + channels * i + c] = redundantAudio!![channels * i + c]
            }
            smoothFade(
                redundantAudio!!, channels * f2p5, pcm, pcmOffset + channels * f2p5,
                pcm, pcmOffset + channels * f2p5, f2p5, window,
            )
        }
        if (transition) {
            val fromTransition = pcmTransition!!
            if (audioSize >= f5) {
                for (i in 0 until channels * f2p5) pcm[pcmOffset + i] = fromTransition[i]
                smoothFade(
                    fromTransition, channels * f2p5, pcm, pcmOffset + channels * f2p5,
                    pcm, pcmOffset + channels * f2p5, f2p5, window,
                )
            } else {
                smoothFade(fromTransition, 0, pcm, pcmOffset, pcm, pcmOffset, f2p5, window)
            }
        }
        if (gain != 0) {
            val scale = CeltMath.exp2(6.48814081e-4f * gain)
            for (i in 0 until frameSize * channels) pcm[pcmOffset + i] = pcm[pcmOffset + i] * scale
        }
        finalRange = if (length <= 1) 0 else range!!.finalRange xor redundantRange
        prevMode = mode
        prevRedundancy = redundancy && !celtToSilk
        return audioSize
    }

    /**
     * `smooth_fade`: a squared-window cross-fade of [overlap] samples from
     * [in1] to [in2] into [out], which may be either of them.
     */
    private fun smoothFade(
        in1: FloatArray,
        in1Offset: Int,
        in2: FloatArray,
        in2Offset: Int,
        out: FloatArray,
        outOffset: Int,
        overlap: Int,
        window: FloatArray,
    ) {
        val inc = 48_000 / sampleRate
        for (c in 0 until channels) {
            for (i in 0 until overlap) {
                val w = window[i * inc] * window[i * inc]
                val at = i * channels + c
                out[outOffset + at] = w * in2[in2Offset + at] + (1f - w) * in1[in1Offset + at]
            }
        }
    }

    /**
     * `opus_pcm_soft_clip`: bends samples beyond full scale back inside it
     * smoothly instead of clipping them, carrying the bend across calls.
     */
    private fun softClip(x: FloatArray, n: Int) {
        val c = channels
        if (n < 1) return
        for (i in 0 until n * c) x[i] = maxOf(-2f, minOf(2f, x[i]))
        for (ch in 0 until c) {
            var a = softClipMemory[ch]
            for (i in 0 until n) {
                val at = i * c + ch
                if (x[at] * a >= 0) break
                x[at] = x[at] + a * x[at] * x[at]
            }
            var curr = 0
            val x0 = x[ch]
            while (true) {
                var i = curr
                while (i < n) {
                    if (x[i * c + ch] > 1 || x[i * c + ch] < -1) break
                    i++
                }
                if (i == n) {
                    a = 0f
                    break
                }
                var peakPos = i
                var start = i
                var end = i
                var maxval = kotlin.math.abs(x[i * c + ch])
                while (start > 0 && x[i * c + ch] * x[(start - 1) * c + ch] >= 0) start--
                while (end < n && x[i * c + ch] * x[end * c + ch] >= 0) {
                    if (kotlin.math.abs(x[end * c + ch]) > maxval) {
                        maxval = kotlin.math.abs(x[end * c + ch])
                        peakPos = end
                    }
                    end++
                }
                val special = start == 0 && x[i * c + ch] * x[ch] >= 0
                a = (maxval - 1) / (maxval * maxval)
                a += a * 2.4e-7f
                if (x[i * c + ch] > 0) a = -a
                for (k in start until end) x[k * c + ch] = x[k * c + ch] + a * x[k * c + ch] * x[k * c + ch]
                if (special && peakPos >= 2) {
                    var offsetValue = x0 - x[ch]
                    val delta = offsetValue / peakPos
                    for (k in curr until peakPos) {
                        offsetValue -= delta
                        x[k * c + ch] += offsetValue
                        x[k * c + ch] = maxOf(-1f, minOf(1f, x[k * c + ch]))
                    }
                }
                curr = end
                if (curr == n) break
            }
            softClipMemory[ch] = a
        }
    }

    companion object {
        const val MODE_SILK_ONLY: Int = 1000
        const val MODE_HYBRID: Int = 1001
        const val MODE_CELT_ONLY: Int = 1002
        private const val BANDWIDTH_NARROW = 1101
        private const val BANDWIDTH_MEDIUM = 1102
        private const val BANDWIDTH_WIDE = 1103
        private const val BANDWIDTH_SUPER_WIDE = 1104
        private val SILENCE = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
    }
}

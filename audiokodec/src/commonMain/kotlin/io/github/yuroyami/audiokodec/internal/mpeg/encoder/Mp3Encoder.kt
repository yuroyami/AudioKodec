package io.github.yuroyami.audiokodec.internal.mpeg.encoder

import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.AudioSink
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.PcmSource
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.bits.BitWriterMsb
import io.github.yuroyami.audiokodec.internal.mpeg.Mp3Tables
import io.github.yuroyami.audiokodec.internal.mpeg.MpegVbrHeader
import io.github.yuroyami.audiokodec.internal.tag.Id3v2Writer
import io.github.yuroyami.audiokodec.readChecked
import io.github.yuroyami.audiokodec.tag.TagBundle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow

/**
 * Writes an MPEG-1 Layer III stream.
 *
 * Audio goes through the pipeline Annex C of ISO/IEC 11172-3 lays out: the
 * analysis filterbank splits it into thirty-two subbands, psychoacoustic
 * model 2 works out what noise each band can hide and whether a granule
 * needs short blocks, the MDCT turns each granule into 576 spectral values,
 * the quantisation loops fit them into the bits a granule may use, and the
 * frame is assembled with its data placed wherever the bit reservoir has room.
 *
 * At a constant bitrate every frame has the same size and the reservoir lets a
 * hard granule borrow what an easy one left over. At a variable bitrate each
 * granule is quantised to exactly the noise the model allows, scaled by the
 * quality setting, and each frame takes the smallest bitrate whose size, with
 * the reservoir, holds what its granules came to.
 *
 * Joint stereo codes the sum and difference of the channels instead of the
 * channels when that needs fewer bits, decided frame by frame. Intensity
 * stereo is not used.
 *
 * The first frame is a silent one holding a Xing header, `Info` for constant
 * bitrate and `Xing` for variable, and the LAME extension carrying the
 * encoder delay and the end padding. A decoder that honours them, which this
 * library's does and ffmpeg's does, gives back exactly the samples that went
 * in, with no silence at either end. The header's counts and table of contents
 * are only known at the end, so they are patched when the sink allows it.
 * When it does not, the header holds what is known in advance: everything,
 * for a constant bitrate source whose length is known; the frame count and
 * gapless numbers for a variable bitrate one; and only the encoder delay
 * when the length is not known either.
 */
internal class Mp3Encoder(
    private val format: AudioFormat,
    /** Bits per second in thousands for a constant bitrate, or 0 for variable. */
    private val bitrateKbps: Int,
    /** 0 (best) to 9 for a variable bitrate. */
    private val quality: Int,
    private val jointStereo: Boolean,
) {
    private val sampleRate = format.sampleRate
    private val channels = format.channels
    private val variable = bitrateKbps == 0

    init {
        val rateIndex = SAMPLE_RATES.indexOf(sampleRate)
        if (rateIndex < 0) {
            throw UnsupportedFeatureException(
                "MP3 encoding at $sampleRate Hz",
                "AudioKodec encodes MPEG-1 Layer III, which is defined at 32000, 44100 and 48000 Hz. " +
                    "The MPEG-2 and MPEG-2.5 rates are not supported; resample to one of the three first.",
            )
        }
        if (channels !in 1..2) {
            throw UnsupportedFeatureException(
                "MP3 encoding of $channels channels",
                "MP3 carries one or two channels, not $channels; mix the source down first.",
            )
        }
        require(format.channelLayout == ChannelLayout.canonical(channels)) {
            "MP3 stores ${if (channels == 1) "mono" else "left and right"} and cannot preserve " +
                "channel layout ${format.channelLayout}"
        }
        if (!variable) {
            require(bitrateKbps in BITRATES) {
                "an MPEG-1 Layer III bitrate is one of ${BITRATES.drop(1).joinToString()} kbit/s, not $bitrateKbps"
            }
        } else {
            require(quality in 0..9) { "the MP3 VBR quality is 0 to 9, not $quality" }
        }
    }

    private val rateIndex = SAMPLE_RATES.indexOf(sampleRate)
    private val sideInfoBytes = if (channels == 1) 17 else 32
    private val mode = when {
        channels == 1 -> MODE_MONO
        jointStereo -> MODE_JOINT
        else -> MODE_STEREO
    }

    private val analysis = Mp3AnalysisFilter(channels)
    private val transform = Mp3ForwardTransform(channels)
    private val psycho = Mp3Psychoacoustics(sampleRate, channels)
    private val quantisers = Array(channels) { Mp3Quantiser(sampleRate) }
    private val longBands = Mp3Tables.longBands(sampleRate)
    private val shortBands = Mp3Tables.shortBands(sampleRate)

    /** Input samples from 1728 before the newest granule to its end, per channel. */
    private val pcm = Array(channels) { DoubleArray(BUFFER) }
    private val readBuffer = FloatArray(576 * channels)

    /** Subband samples of the last two granules read, by granule parity. */
    private val subbands = Array(2) { Array(channels) { DoubleArray(576) } }
    private val results = Array(2) { Array(channels) { Mp3Psychoacoustics.Result() } }
    private val wantShort = BooleanArray(2)
    private var previousType = Mp3ForwardTransform.NORMAL

    /** The two granules of the frame being assembled. */
    private val spectrum = Array(2) { Array(channels) { DoubleArray(576) } }
    private val types = IntArray(2)
    private val longRatio = Array(2) { Array(channels) { DoubleArray(22) } }
    private val shortRatio = Array(2) { Array(channels) { Array(3) { DoubleArray(13) } } }
    private val coded = Array(2) { Array(channels) { Mp3CodedGranule() } }
    private val scratch = DoubleArray(576)

    private val lowpass = lowpassGains()
    private val maskingScale = if (variable) 10.0.pow(VBR_MASKING_DB[quality] / 10.0) else 1.0

    private lateinit var output: FrameOutput

    fun encode(pcmSource: PcmSource, tags: TagBundle?, sink: AudioSink) {
        val declared = pcmSource.frameCount
        val id3 = tags?.takeUnless { it.isEmpty }?.let { Id3v2Writer.build(it, major = 4, padding = 1024) }
        id3?.let { sink.write(it) }

        output = FrameOutput(sink)
        val headerAt = sink.position
        val known = declared?.let { totalFramesFor(it) }
        // Written now with what is known, and patched at the end when possible.
        val header = output.infoFrame(known, if (known != null) declared else null)
        sink.write(header)

        var inputFrames = 0L
        var ended = false
        var totalBlocks = -1L
        var granule = 0L
        var blocksDone = 0L
        while (true) {
            if (!ended) {
                val got = readGranule(pcmSource)
                inputFrames += got
                if (got < 576) ended = true
            } else {
                shiftIn(0)
            }
            if (ended && totalBlocks < 0) totalBlocks = 2 * totalFramesFor(inputFrames)

            val slot = (granule and 1).toInt()
            for (channel in 0 until channels) {
                val into = subbands[slot][channel]
                for (step in 0 until 18) analysis.step(pcm[channel], BUFFER - 576 + step * 32, channel, into, step, 18)
                psycho.analyse(pcm[channel], PSYCHO_CENTRE, channel, results[slot][channel])
            }
            wantShort[slot] = results[slot].any { it.attack }
            if (granule >= 1) {
                processBlock(granule - 1)
                blocksDone++
                if (totalBlocks in 0..blocksDone) break
            }
            granule++
        }
        output.finish()

        if (declared != null && declared != inputFrames) {
            throw AudioKodecException(
                "the source promised $declared frames and produced $inputFrames; " +
                    "source frameCount must describe the complete stream",
            )
        }
        if (sink.canPatch) {
            sink.patchAt(headerAt, output.infoFrame(totalBlocks / 2, inputFrames, final = true))
        }
    }

    /** Audio frames needed for [inputFrames] samples, the delay and the decoder's own delay. */
    private fun totalFramesFor(inputFrames: Long): Long = (inputFrames + TOTAL_DELAY + 1151) / 1152

    /** Reads up to one granule into the end of the buffer and returns how many frames came. */
    private fun readGranule(source: PcmSource): Int {
        var filled = 0
        while (filled < 576) {
            val got = source.readChecked(readBuffer, filled * channels, 576 - filled)
            if (got == 0) break
            filled += got
        }
        shiftIn(filled)
        return filled
    }

    private fun shiftIn(frames: Int) {
        for (channel in 0 until channels) {
            val buffer = pcm[channel]
            buffer.copyInto(buffer, 0, 576, BUFFER)
            for (i in 0 until 576) {
                val value = if (i < frames) readBuffer[i * channels + channel] else 0f
                buffer[BUFFER - 576 + i] = if (value.isNaN()) 0.0 else value.toDouble().coerceIn(-1.0, 1.0)
            }
        }
    }

    /** Transforms block [block], whose type is now known, and codes a frame when two are ready. */
    private fun processBlock(block: Long) {
        val slot = (block and 1).toInt()
        val type = when (previousType) {
            Mp3ForwardTransform.START, Mp3ForwardTransform.SHORT ->
                if (wantShort[slot] || wantShort[1 - slot]) Mp3ForwardTransform.SHORT else Mp3ForwardTransform.STOP
            else -> if (wantShort[1 - slot]) Mp3ForwardTransform.START else Mp3ForwardTransform.NORMAL
        }
        previousType = type
        types[slot] = type
        for (channel in 0 until channels) {
            val out = spectrum[slot][channel]
            if (type == Mp3ForwardTransform.SHORT) {
                transform.transform(subbands[slot][channel], channel, type, scratch)
                for (subband in 0 until 32) {
                    for (window in 0 until 3) {
                        for (k in 0 until 6) scratch[subband * 18 + window * 6 + k] *= lowpass.short[subband * 6 + k]
                    }
                }
                Mp3ForwardTransform.toBitstreamOrder(scratch, shortBands, out)
            } else {
                transform.transform(subbands[slot][channel], channel, type, out)
                for (i in 0 until 576) out[i] *= lowpass.long[i]
            }
            val result = results[slot][channel]
            result.longRatio.copyInto(longRatio[slot][channel])
            for (window in 0 until 3) result.shortRatio[window].copyInto(shortRatio[slot][channel][window])
        }
        if (slot == 1) encodeFrame()
    }

    private val allowed = Array(2) { Array(channels) { DoubleArray(39) } }
    private val energies = Array(2) { Array(channels) { DoubleArray(39) } }
    private val demand = Array(2) { DoubleArray(channels) }

    private fun encodeFrame() {
        for (gr in 0 until 2) {
            for (channel in 0 until channels) measure(gr, channel)
        }
        var modeExtension = 0
        if (mode == MODE_JOINT && prefersMidSide()) {
            modeExtension = 2
            for (gr in 0 until 2) {
                val left = spectrum[gr][0]
                val right = spectrum[gr][1]
                for (i in 0 until 576) {
                    val mid = (left[i] + right[i]) * ROOT_HALF
                    val side = (left[i] - right[i]) * ROOT_HALF
                    left[i] = mid
                    right[i] = side
                }
                val bands = if (types[gr] == Mp3ForwardTransform.SHORT) 39 else 22
                for (band in 0 until bands) {
                    val shared = minOf(allowed[gr][0][band], allowed[gr][1][band])
                    allowed[gr][0][band] = shared
                    allowed[gr][1][band] = shared
                }
                for (channel in 0 until 2) {
                    bandEnergies(gr, channel)
                    demand[gr][channel] = entropy(gr, channel)
                }
            }
        }

        val frame = if (variable) codeVariable() else codeConstant()
        output.writeFrame(frame, modeExtension)
    }

    /** Band energies, allowed noise and demand of one granule of one channel. */
    private fun measure(gr: Int, channel: Int) {
        bandEnergies(gr, channel)
        val energy = energies[gr][channel]
        val into = allowed[gr][channel]
        val last = lastAllowed[channel]
        if (types[gr] == Mp3ForwardTransform.SHORT) {
            for (band in 0 until 13) {
                var before = last[band]
                for (window in 0 until 3) {
                    val index = band * 3 + window
                    val own = energy[index] * shortRatio[gr][channel][window][band] * maskingScale
                    // Pre-echo control across windows: a window's noise is
                    // spread over all of it, so a window an onset falls in
                    // may carry little more noise than the quiet window
                    // before it could.
                    into[index] = minOf(own, PRE_ECHO_RATIO * before)
                    before = own
                }
                last[band] = before
            }
        } else {
            for (band in 0 until 22) into[band] = energy[band] * longRatio[gr][channel][band] * maskingScale
            // The same noise per line, gathered into short bands, is what
            // the first window of a following short block is held to.
            for (band in 0 until 13) {
                var sum = 0.0
                for (line in shortBands[band] * 3 until shortBands[band + 1] * 3) {
                    val long = longBandOfLine[line]
                    sum += into[long] / (longBands[long + 1] - longBands[long])
                }
                last[band] = sum
            }
        }
        demand[gr][channel] = entropy(gr, channel)
    }

    /** Each channel's demand on an ordinary granule, smoothed over the last few. */
    private val usualDemand = DoubleArray(2)

    /** Each channel's allowed noise at the end of its last granule, per short band. */
    private val lastAllowed = Array(channels) { DoubleArray(13) { Double.MAX_VALUE / 4 } }

    private val longBandOfLine = IntArray(576) { line -> (0 until 22).first { line < longBands[it + 1] } }

    private fun bandEnergies(gr: Int, channel: Int) {
        val values = spectrum[gr][channel]
        val into = energies[gr][channel]
        if (types[gr] == Mp3ForwardTransform.SHORT) {
            var i = 0
            for (band in 0 until 13) {
                val width = shortBands[band + 1] - shortBands[band]
                for (window in 0 until 3) {
                    var sum = 0.0
                    repeat(width) {
                        sum += values[i] * values[i]
                        i++
                    }
                    into[band * 3 + window] = sum
                }
            }
        } else {
            for (band in 0 until 22) {
                var sum = 0.0
                for (i in longBands[band] until longBands[band + 1]) sum += values[i] * values[i]
                into[band] = sum
            }
        }
    }

    /**
     * Perceptual entropy measured in the MDCT domain: about how many bits it
     * takes to keep every band's noise at what is allowed there.
     */
    private fun entropy(gr: Int, channel: Int): Double {
        val energy = energies[gr][channel]
        val limit = allowed[gr][channel]
        var bits = 0.0
        if (types[gr] == Mp3ForwardTransform.SHORT) {
            for (band in 0 until 13) {
                val width = shortBands[band + 1] - shortBands[band]
                for (window in 0 until 3) {
                    val index = band * 3 + window
                    if (energy[index] > limit[index]) bits += width * 0.5 * ln(energy[index] / limit[index]) / LN2
                }
            }
        } else {
            for (band in 0 until 22) {
                val width = longBands[band + 1] - longBands[band]
                if (energy[band] > limit[band]) bits += width * 0.5 * ln(energy[band] / limit[band]) / LN2
            }
        }
        return bits
    }

    /**
     * Whether coding the frame's sum and difference needs fewer bits than
     * coding left and right, by the same entropy estimate, with the allowed
     * noise of both the sum and the difference set to the smaller of the two
     * channels': the decoder's left and right each get half of both noises.
     */
    private fun prefersMidSide(): Boolean {
        var separate = 0.0
        var joint = 0.0
        for (gr in 0 until 2) {
            val short = types[gr] == Mp3ForwardTransform.SHORT
            val bands = if (short) 39 else 22
            val left = spectrum[gr][0]
            val right = spectrum[gr][1]
            for (band in 0 until bands) {
                val (from, to) = bandRange(short, band)
                var mid = 0.0
                var side = 0.0
                for (i in from until to) {
                    val m = left[i] + right[i]
                    val s = left[i] - right[i]
                    mid += m * m
                    side += s * s
                }
                mid *= 0.5
                side *= 0.5
                val width = (to - from) * 0.5
                val shared = minOf(allowed[gr][0][band], allowed[gr][1][band])
                separate += width * (log2Plus(energies[gr][0][band] / allowed[gr][0][band]) +
                    log2Plus(energies[gr][1][band] / allowed[gr][1][band]))
                joint += width * (log2Plus(mid / shared) + log2Plus(side / shared))
            }
        }
        return joint < separate
    }

    private fun log2Plus(ratio: Double): Double = if (ratio > 1) ln(ratio) / LN2 else 0.0

    private fun bandRange(short: Boolean, band: Int): Pair<Int, Int> {
        if (!short) return longBands[band] to longBands[band + 1]
        val b = band / 3
        val window = band % 3
        val width = shortBands[b + 1] - shortBands[b]
        val from = shortBands[b] * 3 + window * width
        return from to from + width
    }

    /** Codes the frame at the constant bitrate, sharing the reservoir as Annex C does. */
    private fun codeConstant(): Mp3Frame {
        val index = BITRATES.indexOf(bitrateKbps)
        val padded = output.nextPadding(bitrateKbps)
        val frameBytes = frameBytes(index, padded)
        val slotBits = (frameBytes - 4 - sideInfoBytes) * 8
        val begin = output.beginFor(frameBytes)
        var reservoir = begin * 8
        val reservoirMax = reservoirMaxBits(frameBytes)
        val mean = slotBits / (2 * channels)
        for (gr in 0 until 2) {
            for (channel in 0 until channels) {
                reservoir += mean
                var target = mean
                val carried = reservoir - mean
                val wanted = demand[gr][channel]
                val usual = usualDemand[channel].takeIf { it > 0 } ?: wanted
                // Annex C: a granule that wants more than usual draws on the
                // reservoir, up to most of what is in it, and a short block
                // always does; while the reservoir is low, every granule
                // leaves a little of its share in it for the next one that
                // needs more; and whatever would overflow it is spent now.
                val more = maxOf((wanted - usual) * DEMAND_SCALE, if (types[gr] == Mp3ForwardTransform.SHORT) mean.toDouble() else 0.0)
                if (more > 0) target += minOf(more.toInt(), carried * 6 / 10)
                val fill = reservoirMax * 8 / 10
                if (carried < fill) target -= minOf(mean / SAVE_DIVISOR, fill - carried)
                if (carried > fill) target += carried - fill
                usualDemand[channel] = usual + (wanted - usual) * DEMAND_SMOOTHING
                target = target.coerceIn(0, minOf(MAX_GRANULE_BITS, reservoir))
                val code = coded[gr][channel]
                quantisers[channel].prepare(spectrum[gr][channel], types[gr], allowed[gr][channel])
                quantisers[channel].quantiseWithinBudget(target, code)
                reservoir -= code.part2And3Bits
            }
        }
        val scfsi = selectScalefactorsShared()
        return Mp3Frame(index, padded, frameBytes, begin, coded, scfsi, types)
    }

    /** Codes the frame at the bitrate its granules need. */
    private fun codeVariable(): Mp3Frame {
        for (gr in 0 until 2) {
            for (channel in 0 until channels) {
                quantisers[channel].prepare(spectrum[gr][channel], types[gr], allowed[gr][channel])
                quantisers[channel].quantiseToTarget(MAX_GRANULE_BITS, coded[gr][channel])
            }
        }
        var scfsi = selectScalefactorsShared()
        var total = 0
        for (gr in 0 until 2) for (channel in 0 until channels) total += coded[gr][channel].part2And3Bits
        for (index in 1 until BITRATES.size) {
            val frameBytes = frameBytes(index, false)
            val begin = output.beginFor(frameBytes)
            if (begin * 8 + (frameBytes - 4 - sideInfoBytes) * 8 >= total) {
                return Mp3Frame(index, false, frameBytes, begin, coded, scfsi, types)
            }
        }
        // Not even the largest frame holds it: fall back to the rate loops,
        // sharing what that frame offers in proportion to what each granule
        // wanted.
        val index = BITRATES.size - 1
        val frameBytes = frameBytes(index, false)
        val begin = output.beginFor(frameBytes)
        var available = begin * 8 + (frameBytes - 4 - sideInfoBytes) * 8
        var wanted = total
        for (gr in 0 until 2) {
            for (channel in 0 until channels) {
                val code = coded[gr][channel]
                val share = (available.toLong() * code.part2And3Bits / maxOf(1, wanted)).toInt()
                wanted -= code.part2And3Bits
                quantisers[channel].prepare(spectrum[gr][channel], types[gr], allowed[gr][channel])
                quantisers[channel].quantiseWithinBudget(share.coerceAtMost(MAX_GRANULE_BITS), code)
                available -= code.part2And3Bits
            }
        }
        scfsi = selectScalefactorsShared()
        return Mp3Frame(index, false, frameBytes, begin, coded, scfsi, types)
    }

    /**
     * MPEG-1's scalefactor selection information: a group of the second
     * granule's scalefactors that equals the first granule's is not sent
     * again. Only long blocks keep their scalefactors between granules.
     */
    private fun selectScalefactorsShared(): Array<BooleanArray> {
        val scfsi = Array(channels) { BooleanArray(4) }
        if (types[0] == Mp3ForwardTransform.SHORT || types[1] == Mp3ForwardTransform.SHORT) return scfsi
        for (channel in 0 until channels) {
            val first = coded[0][channel].scalefactors
            val second = coded[1][channel]
            var any = false
            for (group in 0 until 4) {
                var same = true
                for (band in Mp3Quantiser.GROUP_STARTS[group] until Mp3Quantiser.GROUP_STARTS[group + 1]) {
                    if (first[band] != second.scalefactors[band]) same = false
                }
                scfsi[channel][group] = same
                any = any || same
            }
            if (any) {
                val transmitted = BooleanArray(4) { !scfsi[channel][it] }
                quantisers[channel].recountScalefactors(second, transmitted)
            }
        }
        return scfsi
    }

    private fun frameBytes(index: Int, padded: Boolean): Int =
        144_000 * BITRATES[index] / sampleRate + if (padded) 1 else 0

    /**
     * The largest reservoir a frame of [frameBytes] can draw on: 511 bytes, the
     * most `main_data_begin` can say, and no more than leaves the frame and
     * the reservoir within the 7680-bit buffer a decoder is required to have.
     */
    private fun reservoirMaxBits(frameBytes: Int): Int = minOf(511 * 8, maxOf(0, 7680 - frameBytes * 8))

    private class Lowpass(val long: DoubleArray, val short: DoubleArray)

    /** Per-line gains of a gentle low-pass at the cutoff the bitrate can afford. */
    private fun lowpassGains(): Lowpass {
        val cutoff = minOf(cutoffHz(), sampleRate * 0.5)
        val start = cutoff * 0.94
        fun gain(hz: Double): Double = when {
            hz <= start -> 1.0
            hz >= cutoff -> 0.0
            else -> 0.5 + 0.5 * cos(PI * (hz - start) / (cutoff - start))
        }
        return Lowpass(
            DoubleArray(576) { gain((it + 0.5) * sampleRate / 1152.0) },
            DoubleArray(192) { gain((it + 0.5) * sampleRate / 384.0) },
        )
    }

    /** The low-pass cutoff: what the bits per channel can carry without audible holes. */
    private fun cutoffHz(): Double {
        if (variable) return VBR_CUTOFF[quality]
        val perChannel = if (channels == 1) bitrateKbps * 1.5 else bitrateKbps / 2.0
        return when {
            perChannel >= 160 -> 20_000.0
            perChannel >= 128 -> 19_500.0
            perChannel >= 96 -> 19_000.0
            perChannel >= 80 -> 17_500.0
            perChannel >= 64 -> 16_500.0
            perChannel >= 56 -> 15_500.0
            perChannel >= 48 -> 14_500.0
            perChannel >= 40 -> 13_000.0
            perChannel >= 32 -> 11_000.0
            perChannel >= 24 -> 9_000.0
            else -> 7_000.0
        }
    }

    /**
     * Frame layout: the bit reservoir's placement of main data into frames,
     * writing frames once no later frame can reach back into them, and the
     * bookkeeping the Xing header needs.
     */
    private inner class FrameOutput(private val sink: AudioSink) {

        private val pending = ArrayDeque<Pending>()
        private var streamEnd = 0L
        private var reservoirBytes = 0
        private var paddingRest = 0
        private val data = BitWriterMsb(4096)
        private val side = BitWriterMsb(64)

        var audioFrames = 0L
            private set
        var audioBytes = 0L
            private set
        var minimumIndex = Int.MAX_VALUE
            private set
        private var musicCrc = 0

        /** Every [stride]th audio frame's offset from the first audio frame, for the table of contents. */
        private var offsets = LongArray(512)
        private var offsetCount = 0
        private var stride = 1L

        /** Whether the next frame of a constant bitrate stream at [kbps] needs its padding byte. */
        fun nextPadding(kbps: Int): Boolean {
            paddingRest += (144_000 * kbps) % sampleRate
            if (paddingRest >= sampleRate) {
                paddingRest -= sampleRate
                return true
            }
            return false
        }

        /** The `main_data_begin` a frame of [frameBytes] would get. */
        fun beginFor(frameBytes: Int): Int = minOf(reservoirBytes, reservoirMaxBits(frameBytes) / 8)

        fun writeFrame(frame: Mp3Frame, modeExtension: Int) {
            data.reset()
            for (gr in 0 until 2) {
                for (channel in 0 until channels) {
                    val start = data.bitLength
                    writeGranule(data, frame.granules[gr][channel], gr, frame.scfsi[channel])
                    check(data.bitLength - start == frame.granules[gr][channel].part2And3Bits.toLong()) {
                        "granule $gr channel $channel wrote ${data.bitLength - start} bits, " +
                            "counted ${frame.granules[gr][channel].part2And3Bits}"
                    }
                }
            }
            data.alignToByte()
            val mainBytes = data.byteLength
            val slot = frame.bytes - 4 - sideInfoBytes
            check(mainBytes <= frame.begin + slot) { "main data overruns the reservoir" }

            val bytes = ByteArray(frame.bytes)
            writeHeader(bytes, frame.bitrateIndex, frame.padded, modeExtension)
            side.reset()
            writeSideInfo(side, frame)
            side.toByteArray().copyInto(bytes, 4)

            // The data starts begin bytes back in the slots of earlier frames.
            val frameSlotStart = streamEnd
            pending.addLast(Pending(bytes, frameSlotStart))
            streamEnd += slot
            val at = frameSlotStart - frame.begin
            val main = data.rawBuffer()
            for (frameAt in pending) {
                val from = maxOf(at, frameAt.slotStart)
                val to = minOf(at + mainBytes, frameAt.slotStart + frameAt.bytes.size - 4 - sideInfoBytes)
                if (from < to) {
                    main.copyInto(
                        frameAt.bytes, 4 + sideInfoBytes + (from - frameAt.slotStart).toInt(),
                        (from - at).toInt(), (to - at).toInt(),
                    )
                }
            }
            reservoirBytes = (streamEnd - at - mainBytes).toInt()
            minimumIndex = minOf(minimumIndex, frame.bitrateIndex)
            flush(streamEnd - minOf(reservoirBytes, 511))
        }

        /** Writes every pending frame whose slot ends at or before [limit]. */
        private fun flush(limit: Long) {
            while (pending.isNotEmpty()) {
                val first = pending.first()
                val slot = first.bytes.size - 4 - sideInfoBytes
                if (first.slotStart + slot > limit) break
                pending.removeFirst()
                emit(first.bytes)
            }
        }

        private fun emit(bytes: ByteArray) {
            if (audioFrames % stride == 0L) {
                if (offsetCount == offsets.size) {
                    for (i in 0 until offsetCount / 2) offsets[i] = offsets[i * 2]
                    offsetCount /= 2
                    stride *= 2
                }
                if (audioFrames % stride == 0L) offsets[offsetCount++] = audioBytes
            }
            sink.write(bytes)
            musicCrc = crc16(bytes, 0, bytes.size, musicCrc)
            audioFrames++
            audioBytes += bytes.size
        }

        fun finish() {
            flush(Long.MAX_VALUE)
        }

        private fun writeHeader(bytes: ByteArray, bitrateIndex: Int, padded: Boolean, modeExtension: Int) {
            bytes[0] = 0xFF.toByte()
            // MPEG-1, Layer III, no CRC.
            bytes[1] = 0xFB.toByte()
            bytes[2] = ((bitrateIndex shl 4) or (rateIndex shl 2) or (if (padded) 2 else 0)).toByte()
            bytes[3] = ((mode shl 6) or (modeExtension shl 4) or 0x4).toByte()
        }

        private fun writeSideInfo(w: BitWriterMsb, frame: Mp3Frame) {
            w.writeBits(frame.begin, 9)
            w.writeBits(0, if (channels == 1) 5 else 3)
            for (channel in 0 until channels) {
                for (group in 0 until 4) w.writeBit(if (frame.scfsi[channel][group]) 1 else 0)
            }
            for (gr in 0 until 2) {
                for (channel in 0 until channels) {
                    val g = frame.granules[gr][channel]
                    w.writeBits(g.part2And3Bits, 12)
                    w.writeBits(g.bigValues, 9)
                    w.writeBits(g.globalGain, 8)
                    w.writeBits(g.scalefacCompress, 4)
                    if (g.blockType != Mp3ForwardTransform.NORMAL) {
                        w.writeBit(1)
                        w.writeBits(g.blockType, 2)
                        w.writeBit(0)
                        w.writeBits(g.tableSelect[0], 5)
                        w.writeBits(g.tableSelect[1], 5)
                        for (window in 0 until 3) w.writeBits(0, 3)
                    } else {
                        w.writeBit(0)
                        for (region in 0 until 3) w.writeBits(g.tableSelect[region], 5)
                        w.writeBits(g.region0Count, 4)
                        w.writeBits(g.region1Count, 3)
                    }
                    w.writeBit(if (g.preflag) 1 else 0)
                    w.writeBit(g.scalefacScale)
                    w.writeBit(g.count1Table)
                }
            }
        }

        private fun writeGranule(w: BitWriterMsb, g: Mp3CodedGranule, gr: Int, scfsi: BooleanArray) {
            val slen1 = Mp3Tables.SLEN1[g.scalefacCompress]
            val slen2 = Mp3Tables.SLEN2[g.scalefacCompress]
            val sf = g.scalefactors
            if (g.blockType == Mp3ForwardTransform.SHORT) {
                for (band in 0 until 12) {
                    val length = if (band < 6) slen1 else slen2
                    for (window in 0 until 3) if (length > 0) w.writeBits(sf[band * 3 + window], length)
                }
            } else {
                for (group in 0 until 4) {
                    if (gr == 1 && scfsi[group]) continue
                    val length = if (group < 2) slen1 else slen2
                    if (length == 0) continue
                    for (band in Mp3Quantiser.GROUP_STARTS[group] until Mp3Quantiser.GROUP_STARTS[group + 1]) {
                        w.writeBits(sf[band], length)
                    }
                }
            }
            val big = g.bigValues * 2
            val first: Int
            val second: Int
            if (g.blockType != Mp3ForwardTransform.NORMAL) {
                first = minOf(if (g.blockType == Mp3ForwardTransform.SHORT) shortBands[3] * 3 else longBands[8], big)
                second = big
            } else {
                first = minOf(longBands[minOf(g.region0Count + 1, 22)], big)
                second = minOf(longBands[minOf(g.region0Count + g.region1Count + 2, 22)], big)
            }
            Mp3HuffmanEncoder.writePairs(w, g.values, 0, first, g.tableSelect[0])
            Mp3HuffmanEncoder.writePairs(w, g.values, first, second, g.tableSelect[1])
            Mp3HuffmanEncoder.writePairs(w, g.values, second, big, g.tableSelect[2])
            Mp3HuffmanEncoder.writeQuads(w, g.values, big, g.count1End, g.count1Table)
        }

        /**
         * The silent first frame with the Xing header and LAME extension.
         *
         * [frames] and [samples] are the audio frame and input sample counts
         * when known. With [final], the byte count, table of contents and
         * checksums are filled in from what was written.
         */
        fun infoFrame(frames: Long?, samples: Long?, final: Boolean = false): ByteArray {
            val needed = 4 + sideInfoBytes + 120 + 36
            var index = if (variable) 1 else BITRATES.indexOf(bitrateKbps)
            while (frameBytes(index, false) < needed) index++
            val size = frameBytes(index, false)
            val bytes = ByteArray(size)
            writeHeader(bytes, index, false, 0)
            var at = 4 + sideInfoBytes
            (if (variable) "Xing" else "Info").forEach { bytes[at++] = it.code.toByte() }

            val bytesKnown = final || (!variable && frames != null)
            val flags = (if (frames != null) 1 else 0) or (if (bytesKnown) 2 or 4 else 0) or 8
            at = putInt(bytes, at, flags)
            if (frames != null) at = putInt(bytes, at, frames.toInt())
            val totalBytes = when {
                final -> size + audioBytes
                bytesKnown -> size + constantBytes(frames!!)
                else -> 0L
            }
            if (bytesKnown) {
                at = putInt(bytes, at, totalBytes.toInt())
                val toc = if (final) tableOfContents(size, totalBytes) else constantTable(frames!!, size, totalBytes)
                toc.copyInto(bytes, at)
                at += 100
            }
            at = putInt(bytes, at, if (variable) 100 - 10 * quality else 100)

            // The LAME extension: the encoder's name, then its settings, then
            // the gapless numbers twenty-one bytes in.
            val tag = at
            ENCODER_NAME.forEachIndexed { i, c -> bytes[tag + i] = c.code.toByte() }
            bytes[tag + 9] = ((1 shl 4) or (if (variable) 0 else 1)).toByte()
            bytes[tag + 10] = (cutoffHz() / 100).toInt().coerceIn(0, 255).toByte()
            bytes[tag + 20] = (if (variable) BITRATES[minOf(minimumIndex, BITRATES.size - 1).coerceAtLeast(1)] else bitrateKbps)
                .coerceAtMost(255).toByte()
            val delay = ENCODER_DELAY
            val padding = if (frames != null && samples != null) frames * 1152 - delay - samples else 0L
            val packed = (delay shl 12) or padding.toInt().coerceIn(0, 4095)
            bytes[tag + 21] = (packed ushr 16).toByte()
            bytes[tag + 22] = (packed ushr 8).toByte()
            bytes[tag + 23] = packed.toByte()
            bytes[tag + 24] = (when (sampleRate) {
                32_000 -> 0
                44_100 -> 1
                else -> 2
            } shl 6).toByte()
            if (totalBytes > 0) putInt(bytes, tag + 28, totalBytes.toInt())
            if (final) {
                bytes[tag + 32] = (musicCrc ushr 8).toByte()
                bytes[tag + 33] = musicCrc.toByte()
            }
            val crc = crc16(bytes, 0, tag + 34, 0)
            bytes[tag + 34] = (crc ushr 8).toByte()
            bytes[tag + 35] = crc.toByte()
            return bytes
        }

        /** Bytes [frames] constant bitrate frames take, padding included. */
        private fun constantBytes(frames: Long): Long {
            val index = BITRATES.indexOf(bitrateKbps)
            val base = frameBytes(index, false).toLong()
            val padded = frames * ((144_000L * bitrateKbps) % sampleRate) / sampleRate
            return frames * base + padded
        }

        private fun constantTable(frames: Long, infoSize: Int, total: Long): ByteArray {
            val audio = total - infoSize
            return ByteArray(100) { i ->
                if (i == 0) 0 else ((infoSize + audio * i / 100) * 256 / total).coerceAtMost(255).toByte()
            }
        }

        private fun tableOfContents(infoSize: Int, total: Long): ByteArray {
            val frames = audioFrames
            return ByteArray(100) { i ->
                if (i == 0 || frames == 0L) return@ByteArray 0
                val target = frames * i / 100
                val sampled = (target / stride).toInt().coerceAtMost(offsetCount - 1)
                val known = offsets[sampled]
                val average = if (frames > 0) audioBytes.toDouble() / frames else 0.0
                val offset = infoSize + known + ((target - sampled * stride) * average).toLong()
                (offset * 256 / total).coerceIn(0, 255).toByte()
            }
        }

        private fun putInt(bytes: ByteArray, at: Int, value: Int): Int {
            bytes[at] = (value ushr 24).toByte()
            bytes[at + 1] = (value ushr 16).toByte()
            bytes[at + 2] = (value ushr 8).toByte()
            bytes[at + 3] = value.toByte()
            return at + 4
        }
    }

    companion object {
        /** MPEG-1 Layer III bitrates, by bitrate index; 0 is free format. */
        val BITRATES: IntArray = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)

        private val SAMPLE_RATES = intArrayOf(44_100, 48_000, 32_000)

        /**
         * Samples the analysis and synthesis filterbanks and the MDCT's
         * overlap delay the signal by, end to end.
         */
        const val TOTAL_DELAY: Int = Mp3AnalysisFilter.DELAY + 576

        /** What the LAME extension states: the total less the decoder's own part. */
        const val ENCODER_DELAY: Int = TOTAL_DELAY - MpegVbrHeader.DECODER_DELAY

        private const val ENCODER_NAME = "LAMEAKdec"

        private const val MODE_STEREO = 0
        private const val MODE_JOINT = 1
        private const val MODE_MONO = 3

        private const val BUFFER = 576 * 4

        /**
         * Where in the buffer the newest granule's MDCT block is centred: the
         * block straddles the previous granule and this one, and the analysis
         * filterbank delays everything by about 240 samples on top.
         */
        private const val PSYCHO_CENTRE = BUFFER - 576 - 240
        private const val MAX_GRANULE_BITS = 4095
        private const val DEMAND_SCALE = 2.0
        private const val DEMAND_SMOOTHING = 0.1
        private const val SAVE_DIVISOR = 16

        /** How far a short window's noise may rise above the window before's. */
        private const val PRE_ECHO_RATIO = 2.0
        private val ROOT_HALF = kotlin.math.sqrt(0.5)
        private val LN2 = ln(2.0)

        /** Variable bitrate: how far above the model's threshold noise may sit, by quality, in dB. */
        private val VBR_MASKING_DB = doubleArrayOf(-8.0, -6.5, -5.0, -3.5, -2.0, -0.5, 1.0, 3.0, 5.0, 7.5)

        /** Variable bitrate low-pass by quality. */
        private val VBR_CUTOFF = doubleArrayOf(
            20_000.0, 19_500.0, 19_000.0, 19_000.0, 18_500.0, 18_000.0, 17_000.0, 16_000.0, 15_000.0, 13_500.0,
        )

        /** CRC-16 with polynomial 0x8005, bit-reflected, as the LAME extension uses. */
        fun crc16(bytes: ByteArray, from: Int, to: Int, start: Int): Int {
            var crc = start
            for (i in from until to) {
                crc = crc xor (bytes[i].toInt() and 0xFF)
                repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
            }
            return crc and 0xFFFF
        }
    }
}

/** One frame, coded and ready for its main data to be placed. */
private class Mp3Frame(
    val bitrateIndex: Int,
    val padded: Boolean,
    val bytes: Int,
    val begin: Int,
    val granules: Array<Array<Mp3CodedGranule>>,
    val scfsi: Array<BooleanArray>,
    val types: IntArray,
)

/** A frame written except for main data that later frames may still place in its slot. */
private class Pending(val bytes: ByteArray, val slotStart: Long)

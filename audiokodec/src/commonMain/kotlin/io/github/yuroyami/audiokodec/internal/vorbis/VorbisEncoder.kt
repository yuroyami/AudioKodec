package io.github.yuroyami.audiokodec.internal.vorbis

import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.AudioSink
import io.github.yuroyami.audiokodec.PcmSource
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.bits.BitWriterLsb
import io.github.yuroyami.audiokodec.internal.dsp.Fft
import io.github.yuroyami.audiokodec.internal.dsp.ForwardMdct
import io.github.yuroyami.audiokodec.internal.ogg.OggPageWriter
import io.github.yuroyami.audiokodec.internal.psy.Hearing
import io.github.yuroyami.audiokodec.internal.psy.MaskingModel
import io.github.yuroyami.audiokodec.internal.psy.TransientDetector
import io.github.yuroyami.audiokodec.internal.tag.VorbisComment
import io.github.yuroyami.audiokodec.readChecked
import io.github.yuroyami.audiokodec.tag.TagBundle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Encodes float PCM into Ogg Vorbis at a chosen quality.
 *
 * Each block of samples goes through the same steps a Vorbis decoder undoes,
 * in reverse:
 *
 * 1. A transient detector picks the block size, long for steady sound and
 *    short around onsets, so that coding noise cannot spread ahead of an
 *    attack.
 * 2. The block is windowed with exactly the decoder's window and transformed
 *    with the forward MDCT that mirrors its inverse.
 * 3. A masking model estimates how much noise each frequency can hide. The
 *    quality setting scales that, and the result becomes the floor: the
 *    quantiser's step size, drawn as the line segments floor 1 can express.
 * 4. Each coefficient divided by the floor is rounded to an integer, the
 *    residue. Left and right are coupled losslessly as magnitude and angle,
 *    and the residue is coded in partitions by codebooks chosen for each.
 * 5. The packets go into Ogg pages whose granule positions count exactly the
 *    samples that went in.
 *
 * The first block is centred on the first sample, so the decoder's first
 * output sample is the input's first; and blocks continue until one is
 * centred at or after the last sample, whose granule position then states the
 * true length, so a decoder drops the padding at the end.
 *
 * Written from the Vorbis I specification. No encoder source was used.
 */
internal class VorbisEncoder(
    private val format: AudioFormat,
    /** The quality on oggenc's scale, 0 to 10. */
    quality: Float,
) {
    init {
        if (format.channels !in 1..MAX_CHANNELS) {
            throw UnsupportedFeatureException(
                "Vorbis encoding of ${format.channels} channels",
                "AudioKodec writes Vorbis with 1 to $MAX_CHANNELS channels, not ${format.channels}",
            )
        }
        if (format.sampleRate !in MIN_RATE..MAX_RATE) {
            throw UnsupportedFeatureException(
                "Vorbis encoding at ${format.sampleRate} Hz",
                "AudioKodec writes Vorbis at $MIN_RATE to $MAX_RATE Hz, not ${format.sampleRate}",
            )
        }
        val expected = VorbisDecoder.layoutFor(format.channels)
        if (format.channelLayout != expected) {
            throw UnsupportedFeatureException(
                "Vorbis encoding of channel layout ${format.channelLayout}",
                "Vorbis fixes ${format.channels} channels to $expected and cannot carry ${format.channelLayout}",
            )
        }
        require(quality in 0f..10f) { "the Vorbis quality is 0 to 10, not $quality" }
    }

    private val channels = format.channels
    private val rate = format.sampleRate
    private val setup = VorbisEncoderSetup(channels, rate)
    private val config = setup.decoderConfig
    private val shortBlock = setup.shortBlock
    private val longBlock = setup.longBlock

    /** Which of the stream's channels each of AudioKodec's channel slots goes to. */
    private val channelOrder = VorbisDecoder.orderFor(channels)

    private val tuning = Tuning(quality)

    private val shortAnalysis = BlockAnalysis(shortBlock, setup.shortFloor)
    private val longAnalysis = BlockAnalysis(longBlock, setup.longFloor)

    fun encode(pcm: PcmSource, tags: TagBundle?, sink: AudioSink) {
        val declared = pcm.frameCount
        require(declared == null || declared >= 0) { "source frameCount must not be negative, was $declared" }
        val comment = commentHeader(tags)
        val pages = OggPageWriter(sink)
        pages.writePacket(setup.identificationHeader(), 0, flush = true)
        pages.writePacket(comment, 0)
        pages.writePacket(setup.setupHeader(), 0, flush = true)

        val input = Input(pcm)
        var size = if (input.steadyFrom(-longBlock / 4L)) longBlock else shortBlock
        var ownedFrom = -size / 4L
        var previousLong = size == longBlock
        while (true) {
            val nextFrom = ownedFrom + size / 2
            val nextSize = if (input.steadyFrom(nextFrom)) longBlock else shortBlock
            val centre = ownedFrom + size / 4
            val start = centre - size / 2
            val packet = encodeBlock(input, start, size, previousLong, nextSize == longBlock)
            val total = input.totalFrames
            if (total == null || centre < total) {
                // The first packet, which decodes to nothing, gets a page of
                // its own unless it is also the last. Some decoders, ffmpeg
                // among them, read the length of a file whose audio fits on
                // one page from the block sizes rather than from the final
                // granule position, and so return the padding as well; a
                // second page sets them right.
                pages.writePacket(packet, centre, flush = centre == 0L)
            } else {
                // The block centred at or past the end is the last, and its
                // granule position is the true length, which trims the rest.
                pages.writePacket(packet, total, endOfStream = true)
                if (declared != null && declared != total) {
                    throw AudioKodecException(
                        "the source promised $declared frames and produced $total; " +
                            "source frameCount must describe the complete stream",
                    )
                }
                return
            }
            previousLong = size == longBlock
            size = nextSize
            ownedFrom = nextFrom
            input.release(nextFrom - longBlock)
        }
    }

    private fun commentHeader(tags: TagBundle?): ByteArray {
        // The vendor names the program that encoded the audio, so a tag
        // bundle read from another Vorbis file does not get to keep its own.
        val body = VorbisComment.build(tags ?: TagBundle.EMPTY, preservedVendor = VENDOR.encodeToByteArray())
        val packet = ByteArray(body.size + 8)
        packet[0] = 3
        "vorbis".forEachIndexed { i, c -> packet[1 + i] = c.code.toByte() }
        body.copyInto(packet, 7)
        packet[packet.size - 1] = 1
        return packet
    }

    // Per channel, for the block being coded.
    private val quantised = Array(channels) { IntArray(longBlock / 2) }
    private val floorValues = Array(channels) { IntArray(MAX_FLOOR_POINTS) }
    private val used = BooleanArray(channels)
    private val skip = BooleanArray(channels)
    private val floorState = VorbisFloor.FloorState(MAX_FLOOR_POINTS)
    private val writer = BitWriterLsb(16 * 1024)

    // Per channel, what the blocks before this one allowed.
    private val previousThreshold = Array(channels) { FloatArray(longBlock / 2) }
    private val previousWasLong = BooleanArray(channels)
    private val havePrevious = BooleanArray(channels)
    private val lastLongThreshold = Array(channels) { FloatArray(longBlock / 2) }
    private val lastLongPower = Array(channels) { FloatArray(longBlock / 2) }
    private val haveLong = BooleanArray(channels)

    private fun encodeBlock(input: Input, start: Long, n: Int, previousLong: Boolean, nextLong: Boolean): ByteArray {
        val long = n == longBlock
        val analysis = if (long) longAnalysis else shortAnalysis
        val window = config.windowFor(long, previousLong, nextLong)
        val half = n / 2
        val floor = config.floors[if (long) 1 else 0]
        val onsetInside = !long && input.onsetWithin(start + shortBlock / 8, start + n)
        val ratio = longBlock / shortBlock
        for (c in 0 until channels) {
            analysis.analyse(input.samples(c), input.indexOf(start), window)
            val threshold = analysis.threshold
            val previous = previousThreshold[c]
            val lastLong = lastLongThreshold[c]
            if (long) {
                threshold.copyInto(lastLong, 0, 0, half)
                analysis.power.copyInto(lastLongPower[c], 0, 0, half)
                haveLong[c] = true
            } else {
                for (k in 0 until half) {
                    // A short block sees low frequencies through bins eight
                    // times wider than a long one, and would let noise into
                    // the gaps between low notes that the long block before
                    // it could see were empty, or below the lowest note, where
                    // what a short bin holds is only leakage from above. The
                    // long block's least threshold over the same width, and a
                    // share of the power it found there, keep them empty.
                    if (haveLong[c] && (k + 0.5) * rate / n < SHORT_LOW_FREQUENCY_HZ) {
                        var least = Float.MAX_VALUE
                        var energy = 0f
                        for (j in k * ratio until (k + 1) * ratio) {
                            least = minOf(least, lastLong[j])
                            energy += lastLongPower[c][j]
                        }
                        val cap = minOf(least * ratio, energy * SHORT_LOW_NOISE)
                        if (cap < threshold[k]) threshold[k] = cap
                    }
                    // Noise in a block that holds an onset spreads to the part
                    // before the onset as well, where only the quieter block
                    // before it masks anything.
                    if (onsetInside && havePrevious[c]) {
                        val cap = PRE_ECHO_RISE * if (previousWasLong[c]) {
                            var sum = 0f
                            for (j in k * ratio until (k + 1) * ratio) sum += previous[j]
                            sum
                        } else {
                            previous[k]
                        }
                        if (cap < threshold[k]) threshold[k] = cap
                    }
                }
            }
            threshold.copyInto(previous, 0, 0, half)
            previousWasLong[c] = long
            havePrevious[c] = true
            used[c] = analysis.code(floor, floorValues[c], quantised[c])
        }

        // A coupled pair is decoded together if either side has anything, and
        // a side whose floor is unused comes out silent whatever its residue,
        // so its residue is written as zeros.
        for (c in 0 until channels) skip[c] = !used[c]
        for ((magnitude, angle) in setup.couplings) {
            if (!used[magnitude] && !used[angle]) continue
            skip[magnitude] = false
            skip[angle] = false
            couple(quantised[magnitude], quantised[angle], half)
        }

        writer.reset()
        writer.writeBits(0, 1)
        writer.writeBits(if (long) 1 else 0, 1)
        if (long) {
            writer.writeBit(previousLong)
            writer.writeBit(nextLong)
        }
        for (c in 0 until channels) {
            if (used[c]) {
                analysis.floor.write(writer, floorValues[c], setup.floorBook)
            } else {
                writer.writeBits(0, 1)
            }
        }
        val residue = if (long) setup.longResidue else setup.shortResidue
        residue.write(writer, quantised, channels, skip, half)
        return writer.toByteArray()
    }

    /**
     * Square polar coupling, the inverse of section 1.3.3's decoupling: the
     * larger of the two in magnitude becomes the magnitude, and the angle is
     * the difference, signed so the decoder can tell which side was larger.
     * On integers it loses nothing.
     */
    private fun couple(first: IntArray, second: IntArray, half: Int) {
        for (i in 0 until half) {
            val left = first[i]
            val right = second[i]
            val leftLarger = (if (left < 0) -left else left) > (if (right < 0) -right else right)
            if (leftLarger) {
                first[i] = left
                second[i] = if (left > 0) left - right else right - left
            } else {
                first[i] = right
                second[i] = if (right > 0) left - right else right - left
            }
        }
    }

    /**
     * The analysis and quantisation for one block size: transform, power
     * spectrum, masking threshold, floor and residue.
     */
    private inner class BlockAnalysis(val n: Int, val floor: VorbisFloorEncoder) {
        private val half = n / 2
        private val mdct = ForwardMdct(n)
        private val fft = Fft(n, forward = true)
        private val binHz = rate.toDouble() / n
        private val masking = MaskingModel(
            half,
            { (it + 0.5) * binHz },
            minimumSnrDb = ::minimumSnrDb,
        )

        // Turning the window's samples by half a bin before an ordinary FFT
        // gives the odd-frequency DFT, whose bins sit where the MDCT's do.
        private val turnCos = FloatArray(n) { cos(PI * it / n).toFloat() }
        private val turnSin = FloatArray(n) { -sin(PI * it / n).toFloat() }

        private val windowed = FloatArray(n)
        private val re = FloatArray(n)
        private val im = FloatArray(n)
        private val coefficients = FloatArray(half)
        /** The power spectrum of the last block [analyse] took, in the MDCT's units. */
        val power = FloatArray(half)
        /** The masked threshold of the last block [analyse] took, per bin. */
        val threshold = FloatArray(half)
        private val target = FloatArray(half)
        private val lowest = FloatArray(half)
        private val curve = FloatArray(half)
        private val scale = 4f / n

        /** Bins from here up are not coded at all. */
        private val cutoff = (tuning.lowpassHz / binHz).toInt().coerceIn(1, half)

        /** How far above the masked threshold the quality setting puts the noise in each bin. */
        private val noiseGain = DoubleArray(half) {
            val bark = Hearing.bark((it + 0.5) * binHz)
            val db = tuning.noiseOffsetDb + tuning.tiltDbPerBark * maxOf(0.0, bark - Tuning.TILT_FROM_BARK)
            10.0.pow(db / 10.0)
        }

        /** The absolute threshold of hearing in each bin, in [power]'s units. */
        private val absolute = FloatArray(half)

        init {
            // A full-scale sine centred in one bin, measured exactly as the
            // blocks are, fixes where 0 dBFS lands; the threshold curve hangs
            // from that by an assumed playback level.
            val window = config.windowFor(n == longBlock, true, true)
            val bin = half / 4
            for (i in 0 until n) windowed[i] = (sin(2 * PI * (bin + 0.5) * i / n) * window[i]).toFloat()
            spectrum()
            val fullScale = power[bin].toDouble()
            for (k in 0 until half) {
                val hz = ((k + 0.5) * binHz).coerceIn(ABSOLUTE_THRESHOLD_LOW_HZ, ABSOLUTE_THRESHOLD_HIGH_HZ)
                val db = Hearing.absoluteThresholdDb(hz) - FULL_SCALE_SPL
                absolute[k] = (fullScale * 10.0.pow(db / 10.0)).toFloat()
            }
        }

        /** Fills [power] from [windowed]: the odd-frequency DFT, scaled to the MDCT's expected power. */
        private fun spectrum() {
            for (i in 0 until n) {
                re[i] = windowed[i] * turnCos[i]
                im[i] = windowed[i] * turnSin[i]
            }
            fft.transform(re, im)
            val factor = scale * scale / 2f
            for (k in 0 until half) power[k] = (re[k] * re[k] + im[k] * im[k]) * factor
        }

        /**
         * Transforms one channel's block, `samples[from until from + n]`, and
         * fills [threshold] with the noise each bin could hide, which the
         * caller may lower before [code] uses it.
         */
        fun analyse(samples: FloatArray, from: Int, window: FloatArray) {
            for (i in 0 until n) windowed[i] = samples[from + i] * window[i]
            mdct.transform(windowed, 0, coefficients, scale)
            spectrum()
            masking.threshold(power, threshold, absolute)
        }

        /**
         * Codes the block [analyse] took: fills [coded] with its floor and
         * [out] with its residue, and returns false when nothing in it
         * survives quantisation, so the channel can be marked silent.
         */
        fun code(decoderFloor: VorbisFloor, coded: IntArray, out: IntArray): Boolean {
            // The step whose rounding noise, a twelfth of its square, sits
            // where the quality setting puts the noise.
            for (k in 0 until cutoff) {
                val step = sqrt(12.0 * threshold[k] * noiseGain[k])
                target[k] = (255.0 + ln(maxOf(step, 1e-12)) / FLOOR_DB_STEP).coerceIn(0.0, 254.0).toFloat()
                val magnitude = abs(coefficients[k]).toDouble()
                lowest[k] = (255.0 + ln(maxOf(magnitude, 1e-12)) / FLOOR_DB_STEP - FLOOR_BELOW_SIGNAL).toFloat()
            }
            // Above the lowpass nothing is coded, so the floor there only has
            // to cost as little as possible: flat, its points on the line.
            for (k in cutoff until half) {
                target[k] = target[cutoff - 1]
                lowest[k] = 0f
            }
            floor.fit(target, lowest, coded)
            for (i in 0 until floor.xList.size) floorState.y[i] = coded[i]
            decoderFloor.synthesise(floorState, curve, half)

            var any = false
            for (k in 0 until half) {
                if (k >= cutoff) {
                    out[k] = 0
                    continue
                }
                val ratio = coefficients[k] / curve[k]
                // Rounding leans towards zero. A value on the edge between
                // two levels is cheaper as the smaller, and the energy that
                // costs is less audible than the excess the larger adds.
                var value = if (ratio >= 0) {
                    (ratio + 0.5f - ROUNDING_BIAS).toInt()
                } else {
                    -((-ratio + 0.5f - ROUNDING_BIAS).toInt())
                }
                if (value > LARGEST_VALUE) value = LARGEST_VALUE
                if (value < -LARGEST_VALUE) value = -LARGEST_VALUE
                out[k] = value
                if (value != 0) any = true
            }
            return any
        }
    }

    /**
     * The samples, per channel and in the stream's channel order, from a
     * little before the block being coded to a little past the furthest the
     * block switching has looked ahead.
     *
     * Before the first sample and after the last the signal is silence. The
     * transient detector runs over every sub-block as it arrives.
     */
    private inner class Input(private val pcm: PcmSource) {
        private val capacity = 4 * longBlock + READ_FRAMES
        private val buffers = Array(channels) { FloatArray(capacity) }

        /** The absolute frame index of each buffer's first entry. */
        private var bufferStart = -2L * longBlock

        /** The absolute frame index just past the last frame held. */
        private var bufferEnd = 0L

        private val readBuffer = FloatArray(READ_FRAMES * channels)
        private var ended = false
        private var real = 0L

        /** Frames the source produced, once it has ended. */
        val totalFrames: Long? get() = if (ended) real else null

        private val subBlock = shortBlock / 4
        private val detector = TransientDetector(channels, rate, subBlock, ratio = TRANSIENT_RATIO)
        private val onsets = BooleanArray(ONSET_RING)
        private var analysed = 0L
        private var released = bufferStart

        fun samples(channel: Int): FloatArray = buffers[channel]

        /** The index in [samples] of absolute frame [position], making sure the block from there is held. */
        fun indexOf(position: Long): Int {
            fill(position + longBlock)
            return (position - bufferStart).toInt()
        }

        /** True when an onset falls in `from until to`. */
        fun onsetWithin(from: Long, to: Long): Boolean {
            fill(to + subBlock)
            var s = maxOf(0L, from / subBlock)
            while (s * subBlock < to) {
                if (onsets[(s % ONSET_RING).toInt()]) return true
                s++
            }
            return false
        }

        /** Lets frames before [position] go. */
        fun release(position: Long) {
            if (position > released) released = position
        }

        /**
         * True when a long block owning the region from [ownedFrom] would
         * hold no onset: none in that region or under either of its slopes
         * as they narrow next to short blocks.
         */
        fun steadyFrom(ownedFrom: Long): Boolean {
            val from = ownedFrom - shortBlock / 4
            val to = ownedFrom + longBlock / 2 + shortBlock / 4
            fill(to + subBlock)
            var s = maxOf(0L, from / subBlock - 1)
            while (s * subBlock < to) {
                if ((s + 1) * subBlock > from && onsets[(s % ONSET_RING).toInt()]) return false
                s++
            }
            return true
        }

        private fun fill(until: Long) {
            while (bufferEnd < until) {
                if (bufferEnd - bufferStart + READ_FRAMES > capacity) compact()
                var got = 0
                if (!ended) {
                    got = pcm.readChecked(readBuffer, 0, READ_FRAMES)
                    if (got == 0) ended = true
                }
                val at = (bufferEnd - bufferStart).toInt()
                if (got > 0) {
                    for (frame in 0 until got) {
                        for (slot in 0 until channels) {
                            var value = readBuffer[frame * channels + slot]
                            // Nothing outside a sane range carries meaning, and
                            // a NaN would poison every block it touched.
                            if (value.isNaN()) value = 0f
                            if (value > SAMPLE_LIMIT) value = SAMPLE_LIMIT
                            if (value < -SAMPLE_LIMIT) value = -SAMPLE_LIMIT
                            buffers[channelOrder[slot]][at + frame] = value
                        }
                    }
                    real += got
                    bufferEnd += got
                } else {
                    for (c in 0 until channels) buffers[c].fill(0f, at, at + READ_FRAMES)
                    bufferEnd += READ_FRAMES
                }
                while (analysed + subBlock <= bufferEnd) {
                    val onset = detector.next(buffers, (analysed - bufferStart).toInt())
                    onsets[((analysed / subBlock) % ONSET_RING).toInt()] = onset
                    analysed += subBlock
                }
            }
        }

        private fun compact() {
            val keep = minOf(released, analysed) - bufferStart
            if (keep <= 0) throw IllegalStateException("the Vorbis input buffer is too small")
            val shift = keep.toInt()
            val length = (bufferEnd - bufferStart).toInt()
            for (c in 0 until channels) buffers[c].copyInto(buffers[c], 0, shift, length)
            bufferStart += shift
        }
    }

    /**
     * What the quality setting changes, interpolated between the whole
     * steps of three tables.
     *
     * The tables were set by measuring the encoder on the music-like signal
     * of the oracle suite, so that the bitrate climbs steadily with quality
     * and the measured error falls with it, from about oggenc's quality 0
     * at the bottom to well past its quality 8 at the top.
     */
    private class Tuning(quality: Float) {
        private val q = quality.toDouble()

        /** Noise relative to the masked threshold, in dB; negative is quieter. */
        val noiseOffsetDb: Double = interpolate(OFFSET)

        /**
         * Extra noise allowed for every Bark above [TILT_FROM_BARK], in dB.
         * The top octave costs the most bits, as the widest stretch of
         * spectrum, and hides noise the best.
         */
        val tiltDbPerBark: Double = interpolate(TILT)

        /** Nothing above this is coded. */
        val lowpassHz: Double = interpolate(LOWPASS)

        private fun interpolate(table: DoubleArray): Double {
            val low = q.toInt().coerceIn(0, table.size - 2)
            val t = q - low
            return table[low] * (1 - t) + table[low + 1] * t
        }

        companion object {
            /** Noise over the masked threshold by whole quality step, in dB. */
            private val OFFSET = doubleArrayOf(4.0, 2.5, 1.0, -0.2, -1.5, -2.8, -4.0, -5.3, -6.8, -8.8, -12.0)

            /** Extra noise per Bark above [TILT_FROM_BARK] by whole quality step, in dB. */
            private val TILT = doubleArrayOf(2.5, 2.5, 2.0, 2.0, 2.0, 1.75, 1.5, 1.25, 1.0, 0.5, 0.0)

            /** Where the tilt starts, about 7.7 kHz. */
            const val TILT_FROM_BARK = 21.0

            /** Lowpass corner by whole quality step. At 10, nothing a supported rate carries is cut. */
            private val LOWPASS = doubleArrayOf(
                15_000.0, 16_500.0, 18_000.0, 19_000.0, 20_000.0, 20_000.0,
                20_500.0, 20_500.0, 21_000.0, 22_000.0, 96_000.0,
            )
        }
    }

    companion object {
        /** What the comment header names as the encoder. */
        const val VENDOR = "AudioKodec"

        const val MAX_CHANNELS = 8
        const val MIN_RATE = 8_000
        const val MAX_RATE = 192_000

        /** The quality an unspecified encode uses. */
        const val DEFAULT_QUALITY = 4f

        /**
         * The least signal-to-noise ratio a tonal partition is held to, in
         * dB: 24 at 0 Bark, falling 2 dB a Bark to nothing above about 1.7
         * kHz. See [MaskingModel] for why low notes need it.
         */
        private fun minimumSnrDb(bark: Double): Double = maxOf(0.0, 24.0 - 2.0 * bark)

        /** The energy rise, as a power ratio, that switches to short blocks: 9 dB. */
        private const val TRANSIENT_RATIO = 8.0

        /** How far rounding leans towards zero, in residue steps. */
        private const val ROUNDING_BIAS = 0.2f

        private const val READ_FRAMES = 1024
        private const val ONSET_RING = 512
        private const val MAX_FLOOR_POINTS = 65
        private const val SAMPLE_LIMIT = 8f

        /**
         * The largest residue value a channel is quantised to: half of what
         * the residue classes can code, because coupling a pair codes the
         * difference of two values, which can be twice either.
         */
        private const val LARGEST_VALUE = VorbisEncoderSetup.MAX_RESIDUE / 2

        /** Below this, a short block's threshold is held to the last long block's. */
        private const val SHORT_LOW_FREQUENCY_HZ = 1_000.0

        /**
         * The most noise a short block may put at low frequencies, as a share
         * of what the last long block found there: what leaks into a short
         * block's lowest bins from the notes above them is not there to mask
         * anything.
         */
        private const val SHORT_LOW_NOISE = 0.01f

        /** How far a block holding an onset may raise the noise over the block before, as a power ratio. */
        private const val PRE_ECHO_RISE = 2f

        /**
         * How far below a coefficient its floor may be drawn, in the floor's
         * own steps: 100 of them, 55 dB, a residue of about 550.
         */
        private const val FLOOR_BELOW_SIGNAL = 100.0

        /** The natural logarithm of one step of the floor's amplitude table, about 0.55 dB. */
        private const val FLOOR_DB_STEP = 0.062_962_6

        /** The level, in dB SPL, a full-scale sine is assumed to play at. */
        private const val FULL_SCALE_SPL = 96.0

        /**
         * Outside these the absolute threshold is held flat. The real one
         * climbs steeply at both ends, but how steeply varies from one
         * listener and one loudspeaker to the next far more than in between,
         * and noise let in under it near 0 Hz turns into a slow wander of the
         * waveform that a level meter, if not an ear, plainly shows.
         */
        private const val ABSOLUTE_THRESHOLD_LOW_HZ = 150.0
        private const val ABSOLUTE_THRESHOLD_HIGH_HZ = 10_000.0
    }
}

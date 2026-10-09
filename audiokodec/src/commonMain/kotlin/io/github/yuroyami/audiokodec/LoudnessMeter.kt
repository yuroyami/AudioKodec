package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.ReplayGain
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Measures loudness as EBU R 128 defines it, from ITU-R BS.1770: integrated
 * loudness, momentary and short-term loudness, loudness range, and sample
 * and true peak, a block at a time.
 *
 * Push interleaved float PCM with [add] and read the figures at any point.
 * The measurement is a translation of libebur128 1.2.6, in its double
 * precision, with every gating block kept rather than its histogram, so the
 * integrated loudness and the loudness range are exact rather than rounded to
 * a tenth of a unit. The one departure is the true peak: libebur128 brings
 * its own 49-tap interpolator, where this meter oversamples with the sinc
 * filter of AudioKodec's [Resampler], four times below 96 kHz and twice
 * below 192 kHz, as libebur128 does. It uses speexdsp's quality 7 for that,
 * 128 taps rather than the 256 of quality 10, at half the cost: on sums of
 * tones up to 45% of the sample rate its peaks came within 0.00003 dB of
 * quality 10's, where quality 6 fell 0.014 dB short and quality 3, a filter
 * as short as the example in BS.1770's Annex 2, 0.55 dB.
 *
 * Channels are weighted by their position in [layout]: the side and back
 * surrounds by 1.41, the low-frequency channel not at all, and every other
 * channel, unknown positions included, by 1.
 *
 * Memory grows by one number per 100 ms of audio, for the gating blocks,
 * and by one per second for the loudness range; everything else is
 * allocated once.
 */
public class LoudnessMeter(
    public val sampleRate: Int,
    public val layout: ChannelLayout,
    /**
     * Whether to measure the true peak. Oversampling for it is most of the
     * meter's work, about ten times the rest, so a caller who needs only
     * the loudness can leave it out; [replayGain] then gives the sample
     * peak instead.
     */
    public val measureTruePeak: Boolean = true,
) {
    /** A meter for [channels] channels in the canonical layout for that count. */
    public constructor(sampleRate: Int, channels: Int, measureTruePeak: Boolean = true) :
        this(sampleRate, ChannelLayout.canonical(channels), measureTruePeak)

    private val channels = layout.count
    private val weights = DoubleArray(channels) { c ->
        when (layout.channels[c]) {
            AudioChannel.LOW_FREQUENCY -> 0.0
            AudioChannel.SIDE_LEFT, AudioChannel.SIDE_RIGHT, AudioChannel.BACK_LEFT, AudioChannel.BACK_RIGHT -> 1.41
            else -> 1.0
        }
    }
    private val samplesIn100ms: Int
    private val b = DoubleArray(5)
    private val a = DoubleArray(5)
    private val v = Array(channels) { DoubleArray(5) }

    /** Filtered audio, three seconds of it, as a ring. */
    private val audio: DoubleArray
    private val audioFrames: Int
    private var audioIndex = 0
    private var neededFrames: Int
    private var shortTermCounter = 0

    private var blocks = DoubleArray(64)
    private var blockCount = 0
    private var shortTermBlocks = DoubleArray(16)
    private var shortTermCount = 0

    private val samplePeaks = DoubleArray(channels)
    private val truePeaks = DoubleArray(channels)
    private val oversampler: Resampler?
    private val oversampled: FloatArray
    private var finished = false

    init {
        require(sampleRate in 16..MAX_RATE) { "sampleRate must be in 16..$MAX_RATE, was $sampleRate" }
        require(channels <= MAX_CHANNELS) { "a loudness meter takes at most $MAX_CHANNELS channels, not $channels" }
        samplesIn100ms = (sampleRate + 5) / 10
        var frames = (sampleRate.toLong() * WINDOW_MS / 1000).toInt()
        if (frames % samplesIn100ms != 0) frames = frames + samplesIn100ms - frames % samplesIn100ms
        audioFrames = frames
        audio = DoubleArray(audioFrames * channels)
        neededFrames = samplesIn100ms * 4

        // The pre-filter, a high shelf, then the high-pass of BS.1770, as one biquad pair.
        var f0 = 1681.974450955533
        val g = 3.999843853973347
        var q = 0.7071752369554196
        var k = tan(PI * f0 / sampleRate)
        val vh = 10.0.pow(g / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val pb = DoubleArray(3)
        val pa = doubleArrayOf(1.0, 0.0, 0.0)
        val rb = doubleArrayOf(1.0, -2.0, 1.0)
        val ra = doubleArrayOf(1.0, 0.0, 0.0)
        val a0 = 1.0 + k / q + k * k
        pb[0] = (vh + vb * k / q + k * k) / a0
        pb[1] = 2.0 * (k * k - vh) / a0
        pb[2] = (vh - vb * k / q + k * k) / a0
        pa[1] = 2.0 * (k * k - 1.0) / a0
        pa[2] = (1.0 - k / q + k * k) / a0
        f0 = 38.13547087602444
        q = 0.5003270373238773
        k = tan(PI * f0 / sampleRate)
        ra[1] = 2.0 * (k * k - 1.0) / (1.0 + k / q + k * k)
        ra[2] = (1.0 - k / q + k * k) / (1.0 + k / q + k * k)
        b[0] = pb[0] * rb[0]
        b[1] = pb[0] * rb[1] + pb[1] * rb[0]
        b[2] = pb[0] * rb[2] + pb[1] * rb[1] + pb[2] * rb[0]
        b[3] = pb[1] * rb[2] + pb[2] * rb[1]
        b[4] = pb[2] * rb[2]
        a[0] = pa[0] * ra[0]
        a[1] = pa[0] * ra[1] + pa[1] * ra[0]
        a[2] = pa[0] * ra[2] + pa[1] * ra[1] + pa[2] * ra[0]
        a[3] = pa[1] * ra[2] + pa[2] * ra[1]
        a[4] = pa[2] * ra[2]

        val factor = when {
            sampleRate < 96_000 -> 4
            sampleRate < 192_000 -> 2
            else -> 1
        }
        oversampler = if (factor > 1 && measureTruePeak) Resampler.oversampler(sampleRate, factor, channels) else null
        oversampled = FloatArray(if (oversampler != null) oversampler.maxOutputFrames(CHUNK) * channels else 0)
    }

    /** Adds [frameCount] frames of interleaved [samples], from frame [offset]. */
    public fun add(samples: FloatArray, offset: Int = 0, frameCount: Int = samples.size / channels - offset) {
        check(!finished) { "this meter was finished" }
        require(offset >= 0 && frameCount >= 0) { "offset and frameCount must not be negative" }
        require(offset.toLong() + frameCount <= samples.size.toLong() / channels) {
            "samples holds ${samples.size / channels} frames, short of ${offset.toLong() + frameCount}"
        }
        peaks(samples, offset, frameCount)
        var at = offset
        var frames = frameCount
        while (frames > 0) {
            if (frames >= neededFrames) {
                filter(samples, at, neededFrames)
                at += neededFrames
                frames -= neededFrames
                audioIndex += neededFrames * channels
                addBlock(gatingEnergy(samplesIn100ms * 4))
                shortTermCounter += neededFrames
                if (shortTermCounter == samplesIn100ms * 30) {
                    val energy = gatingEnergy(samplesIn100ms * 30)
                    if (energy >= ABSOLUTE_GATE) addShortTermBlock(energy)
                    shortTermCounter = samplesIn100ms * 20
                }
                neededFrames = samplesIn100ms
                if (audioIndex == audioFrames * channels) audioIndex = 0
            } else {
                filter(samples, at, frames)
                audioIndex += frames * channels
                shortTermCounter += frames
                neededFrames -= frames
                frames = 0
            }
        }
    }

    /**
     * Ends the measurement: the oversampler for the true peak trails the
     * input by a few milliseconds, and this lets it reach the end. Nothing
     * can be added afterwards.
     */
    public fun finish() {
        check(!finished) { "this meter was already finished" }
        finished = true
        val resampler = oversampler ?: return
        val tail = resampler.pendingOutputFrames
        val out = if (tail * channels <= oversampled.size) oversampled else FloatArray(tail * channels)
        val written = resampler.flush(out)
        for (i in 0 until written * channels) {
            val value = abs(out[i].toDouble())
            val c = i % channels
            if (value > truePeaks[c]) truePeaks[c] = value
        }
    }

    /**
     * The integrated loudness of everything added, in LUFS, gated as BS.1770
     * gates it; negative infinity when nothing rose above the absolute gate.
     */
    public val integratedLoudness: Double
        get() {
            if (blockCount == 0) return Double.NEGATIVE_INFINITY
            var sum = 0.0
            for (i in 0 until blockCount) sum += blocks[i]
            val relative = sum / blockCount * RELATIVE_GATE
            var gated = 0.0
            var count = 0
            for (i in 0 until blockCount) {
                if (blocks[i] >= relative) {
                    count++
                    gated += blocks[i]
                }
            }
            if (count == 0) return Double.NEGATIVE_INFINITY
            return energyToLoudness(gated / count)
        }

    /** The loudness of the last 400 ms, in LUFS. */
    public val momentaryLoudness: Double get() = windowLoudness(samplesIn100ms * 4)

    /** The loudness of the last three seconds, in LUFS. */
    public val shortTermLoudness: Double get() = windowLoudness(samplesIn100ms * 30)

    /** The loudness range of everything added, in LU, as EBU Tech 3342 defines it. */
    public val loudnessRange: Double
        get() {
            if (shortTermCount == 0) return 0.0
            val sorted = shortTermBlocks.copyOf(shortTermCount).also { it.sort() }
            var power = 0.0
            for (e in sorted) power += e
            power /= sorted.size
            val integrated = MINUS_TWENTY_DB * power
            var start = 0
            while (start < sorted.size && sorted[start] < integrated) start++
            val size = sorted.size - start
            if (size == 0) return 0.0
            val high = sorted[start + ((size - 1) * 0.95 + 0.5).toInt()]
            val low = sorted[start + ((size - 1) * 0.1 + 0.5).toInt()]
            return energyToLoudness(high) - energyToLoudness(low)
        }

    /** The largest absolute sample of [channel] so far, where 1 is full scale. */
    public fun samplePeak(channel: Int): Double = samplePeaks[channel]

    /**
     * The largest absolute value of [channel]'s waveform between its samples
     * as well as at them, found by oversampling, where 1 is full scale. Until
     * [finish], the last few milliseconds added are not yet included.
     *
     * @throws IllegalStateException when the meter does not [measureTruePeak].
     */
    public fun truePeak(channel: Int): Double {
        check(measureTruePeak) { "this meter does not measure the true peak" }
        return maxOf(truePeaks[channel], samplePeaks[channel])
    }

    /** The true peak when the meter measures it, and the sample peak when it does not. */
    private fun peak(channel: Int): Double = if (measureTruePeak) truePeak(channel) else samplePeak(channel)

    /**
     * The track's ReplayGain 2 values: the gain that brings it to -18 LUFS,
     * and its true peak, or its sample peak when the meter does not
     * [measureTruePeak]. Null when nothing rose above the absolute gate.
     */
    public fun replayGain(): ReplayGain? {
        val loudness = integratedLoudness
        if (!loudness.isFinite()) return null
        return ReplayGain(trackGainDb = REPLAYGAIN_REFERENCE - loudness, trackPeak = (0 until channels).maxOf { peak(it) })
    }

    private fun windowLoudness(frames: Int): Double {
        val energy = gatingEnergy(frames)
        return if (energy <= 0.0) Double.NEGATIVE_INFINITY else energyToLoudness(energy)
    }

    private fun peaks(samples: FloatArray, offset: Int, frameCount: Int) {
        var i = offset * channels
        for (f in 0 until frameCount) {
            for (c in 0 until channels) {
                val value = abs(samples[i++].toDouble())
                if (value > samplePeaks[c]) samplePeaks[c] = value
            }
        }
        val resampler = oversampler ?: return
        var at = offset
        var left = frameCount
        while (left > 0) {
            val take = minOf(left, CHUNK)
            val written = resampler.process(samples, at, take, oversampled, 0)
            recordTruePeaks(written)
            at += take
            left -= take
        }
    }

    private fun recordTruePeaks(frames: Int) {
        var i = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val value = abs(oversampled[i++].toDouble())
                if (value > truePeaks[c]) truePeaks[c] = value
            }
        }
    }

    /** The BS.1770 pre-filter, writing into the ring of filtered audio. */
    private fun filter(samples: FloatArray, offset: Int, frames: Int) {
        for (c in 0 until channels) {
            if (weights[c] == 0.0) continue
            val state = v[c]
            var i = (offset) * channels + c
            var o = audioIndex + c
            for (f in 0 until frames) {
                state[0] = samples[i].toDouble() - a[1] * state[1] - a[2] * state[2] - a[3] * state[3] - a[4] * state[4]
                audio[o] = b[0] * state[0] + b[1] * state[1] + b[2] * state[2] + b[3] * state[3] + b[4] * state[4]
                state[4] = state[3]
                state[3] = state[2]
                state[2] = state[1]
                state[1] = state[0]
                i += channels
                o += channels
            }
            // libebur128 flushes subnormal state to zero, as its processor does.
            for (j in 1..4) if (abs(state[j]) < MIN_NORMAL) state[j] = 0.0
        }
    }

    /** The weighted mean square of the last [frames] frames of filtered audio. */
    private fun gatingEnergy(frames: Int): Double {
        var sum = 0.0
        val index = audioIndex / channels
        for (c in 0 until channels) {
            if (weights[c] == 0.0) continue
            var channelSum = 0.0
            if (index < frames) {
                for (i in 0 until index) channelSum += audio[i * channels + c] * audio[i * channels + c]
                for (i in audioFrames - (frames - index) until audioFrames) channelSum += audio[i * channels + c] * audio[i * channels + c]
            } else {
                for (i in index - frames until index) channelSum += audio[i * channels + c] * audio[i * channels + c]
            }
            if (weights[c] != 1.0) channelSum *= weights[c]
            sum += channelSum
        }
        return sum / frames
    }

    private fun addBlock(energy: Double) {
        if (energy < ABSOLUTE_GATE) return
        if (blockCount == blocks.size) blocks = blocks.copyOf(blocks.size * 2)
        blocks[blockCount++] = energy
    }

    private fun addShortTermBlock(energy: Double) {
        if (shortTermCount == shortTermBlocks.size) shortTermBlocks = shortTermBlocks.copyOf(shortTermBlocks.size * 2)
        shortTermBlocks[shortTermCount++] = energy
    }

    public companion object {
        private const val MAX_RATE = 2_822_400
        private const val MAX_CHANNELS = 64
        private const val WINDOW_MS = 3_000
        private const val CHUNK = 1_024
        private const val MIN_NORMAL = 2.2250738585072014E-308
        private const val REPLAYGAIN_REFERENCE = -18.0
        private val RELATIVE_GATE = 10.0.pow(-10.0 / 10.0)
        private val MINUS_TWENTY_DB = 10.0.pow(-20.0 / 10.0)
        private val ABSOLUTE_GATE = 10.0.pow((-70.0 + 0.691) / 10.0)

        private fun energyToLoudness(energy: Double): Double = 10 * (kotlin.math.ln(energy) / kotlin.math.ln(10.0)) - 0.691

        /**
         * The album's ReplayGain 2 values from the meters of its tracks: the
         * gain that brings the album, gated as one programme, to -18 LUFS,
         * and the largest peak, true or sample as each meter measures it,
         * alongside each track's own values.
         */
        public fun albumReplayGain(tracks: List<LoudnessMeter>): List<ReplayGain?> {
            var sum = 0.0
            var count = 0
            for (t in tracks) for (i in 0 until t.blockCount) {
                sum += t.blocks[i]
                count++
            }
            if (count == 0) return tracks.map { it.replayGain() }
            val relative = sum / count * RELATIVE_GATE
            var gated = 0.0
            var above = 0
            for (t in tracks) for (i in 0 until t.blockCount) {
                if (t.blocks[i] >= relative) {
                    above++
                    gated += t.blocks[i]
                }
            }
            val albumGain = if (above == 0) null else REPLAYGAIN_REFERENCE - energyToLoudness(gated / above)
            val albumPeak = tracks.maxOf { t -> (0 until t.channels).maxOfOrNull { t.peak(it) } ?: 0.0 }
            return tracks.map { t ->
                val track = t.replayGain()
                ReplayGain(
                    trackGainDb = track?.trackGainDb,
                    trackPeak = track?.trackPeak,
                    albumGainDb = albumGain,
                    albumPeak = albumPeak,
                )
            }
        }
    }
}

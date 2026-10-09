package io.github.yuroyami.audiokodec

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** What [Levels.scan] found over a range of frames. */
public class LevelScan internal constructor(
    /** The largest absolute sample value, or 0 for an empty range. */
    public val peak: Float,
    /** The first frame where [peak] occurs, or -1 for an empty range. */
    public val peakFrame: Int,
    /** The channel of that frame where it occurs, or -1. */
    public val peakChannel: Int,
    /** The root mean square of every sample in the range, all channels together. */
    public val rms: Double,
    /** How many samples reach the clipping level or beyond it. */
    public val clippedSamples: Int,
    /** The first frame holding a clipped sample, or -1 when none does. */
    public val firstClippedFrame: Int,
)

/** Where the sound in a range starts and stops, between silence at either end. */
public class SilenceBounds internal constructor(
    /** The first frame with a sample above the threshold. */
    public val soundStart: Int,
    /** One past the last frame with a sample above the threshold. */
    public val soundEnd: Int,
) {
    /** True when no frame rises above the threshold, so the range is silence throughout. */
    public val isSilent: Boolean get() = soundStart >= soundEnd
}

/** The shape of a fade. */
public enum class FadeCurve {
    /** The gain rises in a straight line from 0 to 1, so it is 0.5, -6 dB, halfway. */
    LINEAR,

    /**
     * The gain rises in a straight line in decibels, from -60 dB to 0 dB, so
     * it is -30 dB halfway and sounds even to the ear; the first frame of a
     * fade in, and the last of a fade out, are silent.
     */
    LOGARITHMIC,
}

/**
 * Measuring and shaping interleaved float PCM in place: levels, silence at
 * the ends, gain and fades.
 *
 * Every function works on a range of frames, [Int] positions into the
 * array, and the ones that change samples change them in the array given.
 */
public object Levels {

    /**
     * Scans [frameCount] frames of [samples] from frame [from] for the peak,
     * the root mean square, and samples at or beyond [clipLevel], which a
     * fixed-point format would have to clip.
     */
    public fun scan(
        samples: FloatArray,
        channels: Int,
        from: Int = 0,
        frameCount: Int = samples.size / channels - from,
        clipLevel: Float = 1f,
    ): LevelScan {
        checkRange(samples, channels, from, frameCount)
        var peak = 0f
        var peakFrame = -1
        var peakChannel = -1
        var squares = 0.0
        var clipped = 0
        var firstClipped = -1
        var i = from * channels
        for (f in from until from + frameCount) {
            for (c in 0 until channels) {
                val v = samples[i++]
                val a = abs(v)
                if (a > peak || peakFrame < 0) {
                    peak = a
                    peakFrame = f
                    peakChannel = c
                }
                squares += v.toDouble() * v
                if (a >= clipLevel) {
                    clipped++
                    if (firstClipped < 0) firstClipped = f
                }
            }
        }
        val count = frameCount.toLong() * channels
        return LevelScan(peak, peakFrame, peakChannel, if (count == 0L) 0.0 else sqrt(squares / count), clipped, firstClipped)
    }

    /**
     * Finds the silence at the ends of [frameCount] frames of [samples] from
     * [from]: a frame is sound when any of its samples rises above
     * [thresholdDb], relative to full scale, and silence otherwise.
     */
    public fun findSilence(
        samples: FloatArray,
        channels: Int,
        thresholdDb: Double = -60.0,
        from: Int = 0,
        frameCount: Int = samples.size / channels - from,
    ): SilenceBounds {
        checkRange(samples, channels, from, frameCount)
        val threshold = 10.0.pow(thresholdDb / 20)
        fun sound(f: Int): Boolean {
            for (c in 0 until channels) if (abs(samples[f * channels + c]) > threshold) return true
            return false
        }
        var start = from
        while (start < from + frameCount && !sound(start)) start++
        if (start == from + frameCount) return SilenceBounds(start, start)
        var end = from + frameCount
        while (!sound(end - 1)) end--
        return SilenceBounds(start, end)
    }

    /** Multiplies [frameCount] frames of [samples] from [from] by [gainDb] decibels, in place, and returns [samples]. */
    public fun applyGain(
        samples: FloatArray,
        channels: Int,
        gainDb: Double,
        from: Int = 0,
        frameCount: Int = samples.size / channels - from,
    ): FloatArray {
        checkRange(samples, channels, from, frameCount)
        val gain = 10.0.pow(gainDb / 20)
        for (i in from * channels until (from + frameCount) * channels) samples[i] = (samples[i] * gain).toFloat()
        return samples
    }

    /**
     * Fades [frameCount] frames of [samples] in from silence, from [from],
     * in place, and returns [samples]. Frame k of the fade is scaled by the
     * [curve]'s gain at k / [frameCount], so the fade reaches full level on
     * the frame after it.
     */
    public fun fadeIn(
        samples: FloatArray,
        channels: Int,
        from: Int,
        frameCount: Int,
        curve: FadeCurve = FadeCurve.LINEAR,
    ): FloatArray {
        checkRange(samples, channels, from, frameCount)
        for (k in 0 until frameCount) scaleFrame(samples, channels, from + k, gain(curve, k.toDouble() / frameCount))
        return samples
    }

    /**
     * Fades [frameCount] frames of [samples] out to silence, from [from], in
     * place, and returns [samples]: the mirror image of [fadeIn], so the
     * last frame of the fade is silent.
     */
    public fun fadeOut(
        samples: FloatArray,
        channels: Int,
        from: Int,
        frameCount: Int,
        curve: FadeCurve = FadeCurve.LINEAR,
    ): FloatArray {
        checkRange(samples, channels, from, frameCount)
        for (k in 0 until frameCount) {
            scaleFrame(samples, channels, from + k, gain(curve, (frameCount - 1 - k).toDouble() / frameCount))
        }
        return samples
    }

    /** The [curve]'s gain at [t], from 0 to 1 along the fade. */
    private fun gain(curve: FadeCurve, t: Double): Double = when (curve) {
        FadeCurve.LINEAR -> t
        FadeCurve.LOGARITHMIC -> if (t <= 0.0) 0.0 else 10.0.pow(FLOOR_DB * (1 - t) / 20)
    }

    private fun scaleFrame(samples: FloatArray, channels: Int, frame: Int, gain: Double) {
        val base = frame * channels
        for (c in 0 until channels) samples[base + c] = (samples[base + c] * gain).toFloat()
    }

    private fun checkRange(samples: FloatArray, channels: Int, from: Int, frameCount: Int) {
        require(channels > 0) { "channels must be positive, was $channels" }
        require(from >= 0 && frameCount >= 0) { "from and frameCount must not be negative" }
        require(from.toLong() + frameCount <= samples.size.toLong() / channels) {
            "samples holds ${samples.size / channels} frames, short of ${from.toLong() + frameCount}"
        }
    }

    private const val FLOOR_DB = -60.0
}

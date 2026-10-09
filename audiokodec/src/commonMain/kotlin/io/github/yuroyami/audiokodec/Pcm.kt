package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.dsp.ChannelMixing
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Noise added when samples are reduced to fewer bits.
 *
 * Rounding on its own correlates its error with the signal, which is heard as
 * distortion on quiet passages rather than as hiss. Adding a small amount of
 * noise before rounding decorrelates it. It is worth doing when you go down to
 * 16 bits or fewer, and pointless in the other direction.
 */
public sealed interface Dither {

    /** No noise. Float input is rounded; integer input discards low bits on depth reduction. */
    public data object None : Dither

    /**
     * Triangular probability density noise, one least significant bit in
     * either direction (two least significant bits peak to peak).
     *
     * This is the usual choice for going to 16 bits. The generator is a
     * deterministic function of [seed], so the same input and seed give the
     * same output on every platform, which is what makes the result testable.
     */
    public class Tpdf(private val seed: Int = 0x5EED) : Dither {
        private var state: Int = normalisedSeed()

        internal fun fresh(): Tpdf = Tpdf(seed)

        private fun normalisedSeed(): Int = if (seed == 0) 1 else seed

        /** The next noise value, in -1.0 .. 1.0 of one least significant bit. */
        internal fun nextSample(): Float {
            // Two independent uniform values summed give a triangular
            // distribution. xorshift32 is cheap, has a long enough period for
            // audio, and behaves identically on every target.
            val a = nextUniform()
            val b = nextUniform()
            return a - b
        }

        private fun nextUniform(): Float {
            var x = state
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            state = x
            // 24 bits into 0.0 .. 1.0, so the sum of two spans -1.0 .. 1.0.
            return (x ushr 8).toFloat() / 16_777_216f
        }
    }
}

/**
 * Conversions between the Float32 form AudioKodec decodes to and the integer
 * forms files store.
 *
 * The scale is `2^(bits-1)` in both directions, so full negative maps to
 * exactly -1.0 and every 8-, 16- and 24-bit sample survives a round trip
 * unchanged. 32-bit integers do not: Float32 carries 24 bits of mantissa, and
 * the bottom eight bits are lost. Read [IntPcmAccess] instead when that
 * matters.
 */
public object Pcm {

    /**
     * The divisor that maps [bitDepth] integers onto -1.0 .. 1.0.
     *
     * Throws [IllegalArgumentException] for a depth outside 1 through 32.
     */
    public fun scaleOf(bitDepth: Int): Double {
        require(bitDepth in 1..32) { "bitDepth must be 1..32, was $bitDepth" }
        return (1L shl (bitDepth - 1)).toDouble()
    }

    /** Lowest value a signed [bitDepth] sample can hold. */
    public fun minValueOf(bitDepth: Int): Int {
        require(bitDepth in 1..32) { "bitDepth must be 1..32, was $bitDepth" }
        return -(1L shl (bitDepth - 1)).toInt()
    }

    /** Highest value a signed [bitDepth] sample can hold. */
    public fun maxValueOf(bitDepth: Int): Int {
        require(bitDepth in 1..32) { "bitDepth must be 1..32, was $bitDepth" }
        return ((1L shl (bitDepth - 1)) - 1).toInt()
    }

    /**
     * Converts right-justified signed integers to Float32.
     *
     * [into] is filled and returned when it is long enough, otherwise a new
     * array is allocated.
     */
    public fun floatFromInt(
        samples: IntArray,
        bitDepth: Int,
        count: Int = samples.size,
        into: FloatArray? = null,
    ): FloatArray {
        require(count in 0..samples.size) {
            "count must be 0..${samples.size}, was $count"
        }
        val out = if (into != null && into.size >= count) into else FloatArray(count)
        val scale = (1.0 / scaleOf(bitDepth)).toFloat()
        for (i in 0 until count) out[i] = samples[i] * scale
        return out
    }

    /**
     * Converts Float32 to right-justified signed integers of [bitDepth],
     * clamping anything outside -1.0 .. 1.0.
     *
     * Values are rounded to nearest, with ties going towards positive
     * infinity. Clamping happens here rather than in the decoders, because a
     * lossy codec can overshoot
     * the nominal range legitimately and only the step that must fit the value
     * into a fixed number of bits has to care.
     */
    public fun intFromFloat(
        samples: FloatArray,
        bitDepth: Int,
        count: Int = samples.size,
        into: IntArray? = null,
        dither: Dither = Dither.None,
    ): IntArray {
        require(count in 0..samples.size) {
            "count must be 0..${samples.size}, was $count"
        }
        val out = if (into != null && into.size >= count) into else IntArray(count)
        val scale = scaleOf(bitDepth)
        val min = minValueOf(bitDepth)
        val max = maxValueOf(bitDepth)
        if (bitDepth == 32) {
            // 32-bit needs the whole Int range, which does not survive a
            // Float multiply, so this one path works in Double.
            for (i in 0 until count) {
                val v = (samples[i].toDouble() + ditherAmount(dither, bitDepth)) * scale
                val r = if (v.isNaN()) 0L else v.roundToLong()
                out[i] = when {
                    r < min.toLong() -> min
                    r > max.toLong() -> max
                    else -> r.toInt()
                }
            }
            return out
        }
        val scaleF = scale.toFloat()
        for (i in 0 until count) {
            val v = (samples[i] + ditherAmount(dither, bitDepth)) * scaleF
            val r = if (v.isNaN()) 0 else v.roundToInt()
            out[i] = if (r < min) min else if (r > max) max else r
        }
        return out
    }

    private fun ditherAmount(dither: Dither, bitDepth: Int): Float = when (dither) {
        Dither.None -> 0f
        // One least significant bit in either direction at the target depth.
        is Dither.Tpdf -> dither.nextSample() * (1.0 / scaleOf(bitDepth)).toFloat()
    }

    /**
     * Writes [planes], one array per channel, as one interleaved array.
     *
     * Every plane must hold at least [frameCount] samples.
     */
    public fun interleave(
        planes: Array<FloatArray>,
        frameCount: Int,
        into: FloatArray? = null,
    ): FloatArray {
        val channels = planes.size
        require(channels > 0) { "planes must not be empty" }
        val needed = checkedSampleCount(frameCount, channels)
        val aliasesInput = into != null && planes.any { it === into }
        val out = if (into != null && into.size >= needed && !aliasesInput) {
            into
        } else {
            FloatArray(needed)
        }
        for (c in 0 until channels) {
            val plane = planes[c]
            require(plane.size >= frameCount) {
                "plane $c holds ${plane.size} samples, which is short of $frameCount"
            }
            var o = c
            for (f in 0 until frameCount) {
                out[o] = plane[f]
                o += channels
            }
        }
        return out
    }

    /** Splits interleaved [samples] into one array per channel. */
    public fun deinterleave(
        samples: FloatArray,
        channels: Int,
        frameCount: Int,
    ): Array<FloatArray> {
        require(channels > 0) { "channels must be positive, was $channels" }
        val needed = checkedSampleCount(frameCount, channels)
        require(samples.size >= needed) {
            "samples holds ${samples.size} entries, which is short of the " +
                "$needed that $frameCount frames of $channels channels need"
        }
        return Array(channels) { c ->
            val plane = FloatArray(frameCount)
            var i = c
            for (f in 0 until frameCount) {
                plane[f] = samples[i]
                i += channels
            }
            plane
        }
    }

    /**
     * Reorders interleaved samples from [from] to [to].
     *
     * Both layouts must name the same channels; only the order may differ.
     * This is how decoders whose codec uses its own channel order produce the
     * canonical order, and it is a no-op when the two already agree.
     */
    public fun remapChannels(
        samples: FloatArray,
        frameCount: Int,
        from: ChannelLayout,
        to: ChannelLayout,
        into: FloatArray? = null,
    ): FloatArray {
        require(from.count == to.count) {
            "cannot remap ${from.count} channels onto ${to.count}"
        }
        val channels = from.count
        val needed = checkedSampleCount(frameCount, channels)
        require(samples.size >= needed) {
            "samples holds ${samples.size} entries, which is short of $needed"
        }
        if (from == to) return samples
        val map = IntArray(channels)
        val taken = BooleanArray(channels)
        for (target in 0 until channels) {
            val wanted = to.channels[target]
            var found = -1
            for (source in 0 until channels) {
                if (!taken[source] && from.channels[source] == wanted) {
                    found = source
                    break
                }
            }
            require(found >= 0) { "$from has no channel to supply $wanted" }
            taken[found] = true
            map[target] = found
        }
        val out = if (into != null && into.size >= needed && into !== samples) {
            into
        } else {
            FloatArray(needed)
        }
        for (f in 0 until frameCount) {
            val base = f * channels
            for (target in 0 until channels) {
                out[base + target] = samples[base + map[target]]
            }
        }
        return out
    }

    /**
     * Converts a whole buffer of interleaved [samples] from [inputRate] to
     * [outputRate], returning exactly `ceil(frames * outputRate / inputRate)`
     * frames, aligned with the input.
     *
     * This allocates its result; to convert a stream a block at a time
     * without allocating, use a [Resampler].
     */
    public fun resample(
        samples: FloatArray,
        channels: Int,
        inputRate: Int,
        outputRate: Int,
        quality: ResampleQuality = ResampleQuality.SINC,
    ): FloatArray {
        val resampler = Resampler(inputRate, outputRate, channels, quality)
        require(samples.size % channels == 0) { "samples holds ${samples.size} entries, not a whole number of $channels-channel frames" }
        val frames = samples.size / channels
        val out = FloatArray(resampler.maxOutputFrames(frames) * channels)
        val written = resampler.process(samples, 0, frames, out, 0)
        val total = written + resampler.flush(out, written)
        return if (total * channels == out.size) out else out.copyOf(total * channels)
    }

    /**
     * The coefficients [mixChannels] uses to carry [from] into [to], by
     * output channel and then input channel.
     *
     * The downmix is ITU-R BS.775's: centre and surrounds into the front
     * pair at -3 dB, the low-frequency channel left out, and for mono the
     * front pair at -3 dB, the centre whole and the surrounds at -6 dB. A
     * channel the output has keeps its place, so nothing is invented when
     * mixing up: stereo into 5.1 fills the front pair alone, and mono into
     * stereo puts the centre on both sides at -3 dB. Side and back pairs,
     * a back centre, the front pair either side of centre and the top front
     * pair follow the rules ffmpeg applies; see the mixing guide.
     *
     * @throws UnsupportedFeatureException when a channel has no defined
     *   place in [to], such as one of unknown position or a height channel
     *   other than the top front pair, rather than guessing at one.
     */
    public fun mixMatrix(from: ChannelLayout, to: ChannelLayout): Array<FloatArray> =
        ChannelMixing.matrix(from, to).map { row -> FloatArray(row.size) { row[it].toFloat() } }.toTypedArray()

    /**
     * Mixes interleaved [samples] in [from] into [to], with the coefficients
     * [mixMatrix] gives. Nothing is clipped: a downmix of loud channels can
     * pass full scale, as it does in ffmpeg.
     */
    public fun mixChannels(
        samples: FloatArray,
        frameCount: Int,
        from: ChannelLayout,
        to: ChannelLayout,
        into: FloatArray? = null,
    ): FloatArray {
        val needed = checkedSampleCount(frameCount, from.count)
        require(samples.size >= needed) { "samples holds ${samples.size} entries, which is short of $needed" }
        val outNeeded = checkedSampleCount(frameCount, to.count)
        val out = if (into != null && into !== samples && into.size >= outNeeded) into else FloatArray(outNeeded)
        val matrix = ChannelMixing.matrix(from, to)
        val inputs = from.count
        for (f in 0 until frameCount) {
            val base = f * inputs
            for (o in matrix.indices) {
                val row = matrix[o]
                var sum = 0.0
                for (i in 0 until inputs) if (row[i] != 0.0) sum += row[i] * samples[base + i]
                out[f * to.count + o] = sum.toFloat()
            }
        }
        return out
    }

    /**
     * Multiplies each channel of interleaved [samples] by its own linear
     * gain in [gains], in place, and returns [samples].
     */
    public fun applyChannelGains(samples: FloatArray, frameCount: Int, gains: FloatArray): FloatArray {
        val channels = gains.size
        require(channels > 0) { "gains must not be empty" }
        val needed = checkedSampleCount(frameCount, channels)
        require(samples.size >= needed) { "samples holds ${samples.size} entries, which is short of $needed" }
        for (i in 0 until needed) samples[i] *= gains[i % channels]
        return samples
    }

    /**
     * Turns interleaved left and right [samples] into mid and side: mid is
     * half their sum and side half their difference. [midSideToStereo]
     * undoes it exactly for audio of up to 23 bits, whose sums a float holds
     * exactly, and to within float rounding otherwise.
     */
    public fun stereoToMidSide(samples: FloatArray, frameCount: Int, into: FloatArray? = null): FloatArray {
        val out = pairOutput(samples, frameCount, into)
        for (f in 0 until frameCount) {
            val l = samples[2 * f].toDouble()
            val r = samples[2 * f + 1].toDouble()
            out[2 * f] = ((l + r) * 0.5).toFloat()
            out[2 * f + 1] = ((l - r) * 0.5).toFloat()
        }
        return out
    }

    /** Turns interleaved mid and side [samples] back into left and right: their sum and their difference. */
    public fun midSideToStereo(samples: FloatArray, frameCount: Int, into: FloatArray? = null): FloatArray {
        val out = pairOutput(samples, frameCount, into)
        for (f in 0 until frameCount) {
            val m = samples[2 * f].toDouble()
            val s = samples[2 * f + 1].toDouble()
            out[2 * f] = (m + s).toFloat()
            out[2 * f + 1] = (m - s).toFloat()
        }
        return out
    }

    private fun pairOutput(samples: FloatArray, frameCount: Int, into: FloatArray?): FloatArray {
        val needed = checkedSampleCount(frameCount, 2)
        require(samples.size >= needed) { "samples holds ${samples.size} entries, which is short of $needed" }
        return if (into != null && into.size >= needed) into else FloatArray(needed)
    }

    /** Clamps every sample into -1.0 .. 1.0, in place. */
    public fun clampInPlace(samples: FloatArray, count: Int = samples.size): FloatArray {
        require(count in 0..samples.size) {
            "count must be 0..${samples.size}, was $count"
        }
        for (i in 0 until count) {
            val v = samples[i]
            samples[i] = when {
                v.isNaN() -> 0f
                v < -1f -> -1f
                v > 1f -> 1f
                else -> v
            }
        }
        return samples
    }

    private fun checkedSampleCount(frameCount: Int, channels: Int): Int {
        require(frameCount >= 0) { "frameCount must not be negative, was $frameCount" }
        val count = frameCount.toLong() * channels
        require(count <= Int.MAX_VALUE) {
            "$frameCount frames of $channels channels need $count entries, above the array limit"
        }
        return count.toInt()
    }
}

/** Fresh state for one independent encode operation. */
internal fun Dither.fresh(): Dither = when (this) {
    Dither.None -> Dither.None
    is Dither.Tpdf -> fresh()
}

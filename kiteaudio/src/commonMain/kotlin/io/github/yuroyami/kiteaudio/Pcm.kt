package io.github.yuroyami.kiteaudio

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

    /** Round only. The right choice when the depth is not being reduced. */
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
 * Conversions between the Float32 form KiteAudio decodes to and the integer
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

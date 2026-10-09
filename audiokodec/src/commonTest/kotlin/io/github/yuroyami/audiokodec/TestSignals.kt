package io.github.yuroyami.audiokodec

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.test.assertTrue

/**
 * Signal generation and comparison for the tests.
 *
 * Round-trip tests only mean something when the input is already on the grid
 * the format stores. A float that cannot be represented at 16 bits will differ
 * after a 16-bit round trip no matter how correct the codec is, so
 * [quantise] puts the signal on the grid first and the test then demands
 * exactness.
 */
internal object TestSignals {

    /** Interleaved sine waves, one per channel, each a different frequency, from the public generator. */
    fun sine(frames: Int, channels: Int, sampleRate: Int = 44_100, amplitude: Float = 0.8f): FloatArray =
        Pcm.interleave(Array(channels) { c -> Signals.sine(frames, 1, sampleRate, 220.0 * (c + 1), amplitude) }, frames)

    /**
     * A signal that visits the extremes, which is where clamping and sign
     * handling break.
     */
    fun extremes(channels: Int): FloatArray {
        val values = floatArrayOf(0f, 1f, -1f, 0.5f, -0.5f, 0.999f, -0.999f, 0.001f, -0.001f)
        val out = FloatArray(values.size * channels)
        for (f in values.indices) {
            for (c in 0 until channels) {
                out[f * channels + c] = values[f]
            }
        }
        return out
    }

    /** A ramp that makes an off-by-one in the frame index obvious. */
    fun ramp(frames: Int, channels: Int): FloatArray {
        val out = FloatArray(frames * channels)
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                out[f * channels + c] = ((f % 1000) / 1000f - 0.5f) + c * 0.001f
            }
        }
        return out
    }

    /**
     * Snaps every sample onto the grid a [bitDepth] integer format stores, so
     * a round trip through that format has to come back unchanged.
     */
    fun quantise(samples: FloatArray, bitDepth: Int): FloatArray {
        val scale = Pcm.scaleOf(bitDepth).toFloat()
        val min = Pcm.minValueOf(bitDepth).toFloat()
        val max = Pcm.maxValueOf(bitDepth).toFloat()
        return FloatArray(samples.size) { i ->
            val v = round(samples[i] * scale).coerceIn(min, max)
            v / scale
        }
    }

    /** Fails when any pair of samples differs at all. */
    fun assertExact(expected: FloatArray, actual: FloatArray, message: String = "") {
        assertTrue(
            expected.size == actual.size,
            "$message: expected ${expected.size} samples, found ${actual.size}",
        )
        for (i in expected.indices) {
            if (expected[i] != actual[i]) {
                fail("$message: sample $i is ${actual[i]}, expected ${expected[i]}")
            }
        }
    }

    /** Fails when any pair of samples differs by more than [tolerance]. */
    fun assertClose(
        expected: FloatArray,
        actual: FloatArray,
        tolerance: Float,
        message: String = "",
    ) {
        assertTrue(
            expected.size == actual.size,
            "$message: expected ${expected.size} samples, found ${actual.size}",
        )
        var worst = 0f
        var worstAt = -1
        for (i in expected.indices) {
            val difference = abs(expected[i] - actual[i])
            if (difference > worst) {
                worst = difference
                worstAt = i
            }
        }
        assertTrue(
            worst <= tolerance,
            "$message: sample $worstAt differs by $worst, which is over $tolerance " +
                "(expected ${expected.getOrNull(worstAt)}, found ${actual.getOrNull(worstAt)})",
        )
    }

    /** Root mean square of the difference between two signals. */
    fun rmsError(expected: FloatArray, actual: FloatArray): Float {
        require(expected.size == actual.size) {
            "cannot compare ${expected.size} samples with ${actual.size}"
        }
        if (expected.isEmpty()) return 0f
        var sum = 0.0
        for (i in expected.indices) {
            val d = (expected[i] - actual[i]).toDouble()
            sum += d * d
        }
        return sqrt(sum / expected.size).toFloat()
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}

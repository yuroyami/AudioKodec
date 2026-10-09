package io.github.yuroyami.audiokodec

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Test signals as interleaved float PCM: sine, square, noise and silence, the
 * same in every channel.
 */
public object Signals {

    /** [frames] frames of a sine of [frequency] Hz at [amplitude], starting at [phase] radians. */
    public fun sine(
        frames: Int,
        channels: Int,
        sampleRate: Int,
        frequency: Double,
        amplitude: Float = 1f,
        phase: Double = 0.0,
    ): FloatArray = generate(frames, channels, sampleRate) { f ->
        (amplitude * sin(2.0 * PI * frequency * f / sampleRate + phase)).toFloat()
    }

    /**
     * [frames] frames of a square wave of [frequency] Hz at [amplitude]: the
     * first half of each period high, the second low, so it starts high.
     */
    public fun square(
        frames: Int,
        channels: Int,
        sampleRate: Int,
        frequency: Double,
        amplitude: Float = 1f,
    ): FloatArray = generate(frames, channels, sampleRate) { f ->
        val cycle = frequency * f / sampleRate
        if (cycle - kotlin.math.floor(cycle) < 0.5) amplitude else -amplitude
    }

    /**
     * [frames] frames of white noise, uniform between -[amplitude] and
     * [amplitude], independent in each channel, the same for the same [seed].
     */
    public fun whiteNoise(frames: Int, channels: Int, amplitude: Float = 1f, seed: Int = 0): FloatArray {
        val out = allocate(frames, channels)
        val random = Random(seed)
        for (i in out.indices) out[i] = (random.nextFloat() * 2f - 1f) * amplitude
        return out
    }

    /** [frames] frames of silence. */
    public fun silence(frames: Int, channels: Int): FloatArray = allocate(frames, channels)

    /** A level in decibels relative to full scale, as a linear amplitude. */
    public fun dbToLinear(db: Double): Double = 10.0.pow(db / 20)

    private inline fun generate(frames: Int, channels: Int, sampleRate: Int, value: (Int) -> Float): FloatArray {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        val out = allocate(frames, channels)
        for (f in 0 until frames) {
            val v = value(f)
            for (c in 0 until channels) out[f * channels + c] = v
        }
        return out
    }

    private fun allocate(frames: Int, channels: Int): FloatArray {
        require(frames >= 0) { "frames must not be negative, was $frames" }
        require(channels > 0) { "channels must be positive, was $channels" }
        require(frames.toLong() * channels <= Int.MAX_VALUE) { "$frames frames of $channels channels do not fit in one array" }
        return FloatArray(frames * channels)
    }
}

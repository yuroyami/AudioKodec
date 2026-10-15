package io.github.yuroyami.audiokodec

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The music-like signal every lossy encoder in this library is measured on.
 *
 * A chord of three notes with eleven harmonics each, drifting slightly
 * between the channels; a sine sweep from 100 Hz to 19 kHz; a noise bed with a
 * pink-ish tilt; and a burst of decaying noise every half second, which is
 * what tests a coder's handling of transients. Seeded, so every run and every
 * encoder sees the same samples.
 */
internal object Mp3TestSignals {

    /** Interleaved stereo at [rate] for [seconds], within -1 to 1. */
    fun music(rate: Int, seconds: Double, seed: Long = 1): FloatArray {
        val n = (rate * seconds).toInt()
        val random = java.util.Random(seed)
        val left = DoubleArray(n)
        val right = DoubleArray(n)
        for ((f0, amplitude) in listOf(220.0 to 0.08, 277.18 to 0.06, 329.63 to 0.06)) {
            for (h in 1 until 12) {
                val f = f0 * h
                if (f > 20_000) break
                for (i in 0 until n) {
                    val t = i.toDouble() / rate
                    left[i] += amplitude / h * sin(2 * PI * f * t + h)
                    right[i] += amplitude / h * sin(2 * PI * f * t * 1.001 + 2 * h)
                }
            }
        }
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val sweep = 0.05 * sin(2 * PI * (100 * t + (19_000.0 - 100) / (2 * seconds) * t * t))
            left[i] += sweep
            right[i] += 0.5 * sweep
        }
        val white = DoubleArray(n) { random.nextGaussian() }
        val walk = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            sum += white[i]
            walk[i] = sum
        }
        // The walk less its 400-sample moving average: a tilt towards the low
        // end without the walk's drift.
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + walk[i]
        for (i in 0 until n) {
            val from = maxOf(0, i - 200)
            val to = minOf(n, i + 200)
            val average = (prefix[to] - prefix[from]) / 400.0
            val noise = 0.02 * white[i] + 0.002 * (walk[i] - average)
            left[i] += noise
            right[i] += 0.7 * noise + 0.01 * random.nextGaussian()
        }
        var k = 0
        while (true) {
            val start = ((0.25 + 0.5 * k) * rate).toInt()
            if (start >= n) break
            for (j in 0 until minOf(2_000, n - start)) {
                val burst = 0.4 * exp(-j / 200.0) * random.nextGaussian()
                left[start + j] += burst
                right[start + j] += 0.8 * burst
            }
            k++
        }
        return FloatArray(2 * n) { i ->
            val value = if (i % 2 == 0) left[i / 2] else right[i / 2]
            value.coerceIn(-1.0, 1.0).toFloat()
        }
    }
}

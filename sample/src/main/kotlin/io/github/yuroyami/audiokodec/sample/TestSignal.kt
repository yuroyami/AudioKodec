package io.github.yuroyami.audiokodec.sample

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * A deterministic stereo signal with the kinds of content music has, so that
 * encoders have something to spend bits on: a vibrato fundamental with
 * harmonics, a slow sweep, bursts of noise and decaying clicks. The two
 * channels differ in phase, pitch and noise, so joint stereo has something to
 * decide.
 */
internal object TestSignal {

    /** [seconds] of interleaved stereo at [rate] Hz, in the range -1 to 1. */
    fun stereo(seconds: Int, rate: Int): FloatArray {
        val frames = seconds * rate
        val samples = FloatArray(frames * 2)
        var state = 0x2545F491
        fun noise(): Double {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return state / 2147483648.0
        }
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            for (c in 0 until 2) {
                val pitch = 220.0 * (1 + 0.06 * c)
                val vibrato = 2.0 * sin(2 * PI * 5.5 * t)
                var v = 0.0
                for (h in 1..6) v += sin(2 * PI * pitch * h * t + vibrato * h + c) / (h * 1.4)
                v *= 0.22
                v += 0.12 * sin(2 * PI * (500 + 1500 * (0.5 + 0.5 * sin(2 * PI * 0.07 * t))) * t + 0.5 * c)
                if ((t % 0.9) < 0.12) v += 0.18 * noise()
                v += 0.45 * exp(-((t + 0.03 * c) % 0.37) * 300) * sin(2 * PI * 2400 * t)
                v += 0.01 * noise()
                samples[2 * i + c] = v.toFloat().coerceIn(-0.98f, 0.98f)
            }
        }
        return samples
    }
}

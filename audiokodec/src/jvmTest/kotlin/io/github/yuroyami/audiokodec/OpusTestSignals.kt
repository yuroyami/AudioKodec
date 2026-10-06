package io.github.yuroyami.audiokodec

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Test signals for the Opus oracle suites, as 16-bit little-endian PCM at
 * 48 kHz, which is what `opus_demo` reads.
 *
 * The encoder chooses between its speech and music codecs by listening, so
 * what the signal sounds like decides which half of the decoder a test
 * reaches.
 */
internal object OpusTestSignals {

    /**
     * Something the encoder treats as music: steady tones, a chirp, noise,
     * sharp clicks that force short blocks, and channels that differ.
     */
    fun music(channels: Int, seconds: Double, amplitude: Int = 20_000): ByteArray {
        val rate = 48_000
        val frames = (rate * seconds).toInt()
        val buffer = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        var seed = 12345
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            seed = seed * 1_103_515_245 + 12_345
            val noise = ((seed ushr 16) and 0x7FFF) / 32768.0 - 0.5
            val click = if (i % 9_600 < 24) 0.8 else 0.0
            val chirp = sin(2 * PI * (200 + 4_000 * t / seconds) * t)
            for (c in 0 until channels) {
                val tone = sin(2 * PI * (440.0 * (c + 1)) * t)
                val value = 0.3 * tone + 0.2 * chirp + 0.1 * noise + click * (if (c == 0) 1 else -1)
                buffer.putShort((value.coerceIn(-1.0, 1.0) * amplitude).toInt().toShort())
            }
        }
        return buffer.array()
    }

    /**
     * Something the encoder treats as speech: a pulse train at a gliding
     * pitch through three formant resonators, broken by hiss like a
     * fricative and by pauses, with a slower pitch on the second channel so
     * that the stereo predictor has work to do.
     */
    fun speech(channels: Int, seconds: Double): ByteArray {
        val rate = 48_000
        val frames = (rate * seconds).toInt()
        val buffer = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        val formants = doubleArrayOf(700.0, 1_200.0, 2_600.0)
        val state = Array(channels) { Array(formants.size) { DoubleArray(2) } }
        val phase = DoubleArray(channels)
        var seed = 12345
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            seed = seed * 1_103_515_245 + 12_345
            val noise = ((seed ushr 16) and 0x7FFF) / 16384.0 - 1.0
            // Syllables of a quarter of a second: voiced, then a fricative,
            // then a short pause.
            val within = t % 0.25
            val voiced = within < 0.17
            val hiss = within in 0.17..0.21
            for (c in 0 until channels) {
                val pitch = (110.0 + 60.0 * sin(2 * PI * 0.7 * t)) / (1 + 0.15 * c)
                phase[c] += pitch / rate
                var excitation = 0.0
                if (voiced && phase[c] >= 1.0) excitation = 1.0
                if (phase[c] >= 1.0) phase[c] -= 1.0
                if (hiss) excitation = 0.15 * noise
                var sample = 0.0
                for ((k, frequency) in formants.withIndex()) {
                    val r = exp(-PI * (80.0 + 40.0 * k) / rate)
                    val a1 = 2 * r * cos(2 * PI * frequency / rate)
                    val a2 = -r * r
                    val y = excitation + a1 * state[c][k][0] + a2 * state[c][k][1]
                    state[c][k][1] = state[c][k][0]
                    state[c][k][0] = y
                    sample += y / (k + 1)
                }
                buffer.putShort((sample * 1_500).coerceIn(-32_000.0, 32_000.0).toInt().toShort())
            }
        }
        return buffer.array()
    }

    /** Speech and music in turns of [turn] seconds, which makes the encoder change codec back and forth. */
    fun speechAndMusic(channels: Int, seconds: Double, turn: Double): ByteArray {
        val speech = speech(channels, seconds)
        val music = music(channels, seconds, amplitude = 12_000)
        val turnBytes = (48_000 * turn).toInt() * channels * 2
        val out = speech.copyOf()
        var at = turnBytes
        while (at < out.size) {
            music.copyInto(out, at, at, minOf(out.size, at + turnBytes))
            at += 2 * turnBytes
        }
        return out
    }
}

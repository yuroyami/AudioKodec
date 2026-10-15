package io.github.yuroyami.audiokodec.docs

// --8<-- [start:imports]
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.Pcm
import io.github.yuroyami.audiokodec.Resampler
// --8<-- [end:imports]
import kotlin.test.Test
import kotlin.test.assertEquals

class ResampleSample {
    private val bytes = Fixtures.tone

    @Test
    fun changeTheSampleRate() {
        // --8<-- [start:resample]
        val decoded = AudioKodec.decode(bytes)
        val at48k = Pcm.resample(decoded.samples, decoded.format.channels, decoded.format.sampleRate, 48_000)
        // --8<-- [end:resample]

        // Exactly ceil(frames * 48000 / 44100) frames come out.
        assertEquals(96_000 * 2, at48k.size)
    }

    @Test
    fun changeTheSampleRateWhileStreaming() {
        val heard = ArrayList<Float>()
        fun play(samples: FloatArray, frames: Int) {
            for (i in 0 until frames * 2) heard += samples[i]
        }
        val source = bytes

        // --8<-- [start:resample-stream]
        val resampler = Resampler(inputRate = 44_100, outputRate = 48_000, channels = 2)
        val out = FloatArray(resampler.maxOutputFrames(4_096) * 2)

        AudioKodec.open(source, DecodeOptions(reuseBuffers = true)).use { decoder ->
            while (true) {
                val frame = decoder.nextFrame() ?: break
                var at = 0
                while (at < frame.frameCount) {
                    val take = minOf(4_096, frame.frameCount - at)
                    val written = resampler.process(frame.samples, at, take, out, 0)
                    play(out, written)
                    at += take
                }
            }
        }
        val tail = FloatArray(resampler.pendingOutputFrames * 2)
        play(tail, resampler.flush(tail))
        // --8<-- [end:resample-stream]

        // Streamed, the conversion gives what the whole-buffer one gives.
        val whole = Pcm.resample(AudioKodec.decode(bytes).samples, 2, 44_100, 48_000)
        assertEquals(whole.size, heard.size)
        for (i in whole.indices) assertEquals(whole[i], heard[i], 1e-6f)
    }
}

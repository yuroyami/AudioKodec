package io.github.yuroyami.audiokodec.docs

// --8<-- [start:imports]
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.WaveformReducer
// --8<-- [end:imports]
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveformSample {

    @Test
    fun drawAWaveform() {
        val source = Fixtures.tone

        // --8<-- [start:waveform]
        val info = AudioKodec.probe(source)
        val perBucket = 1_024
        val buckets = WaveformReducer.bucketCount(info.durationFrames ?: 0, perBucket).toInt()
        val channels = info.format.channels
        val min = FloatArray(buckets * channels)
        val max = FloatArray(buckets * channels)
        val rms = FloatArray(buckets * channels)

        val reducer = WaveformReducer(channels, perBucket)
        var pulled = 0
        AudioKodec.open(source, DecodeOptions(reuseBuffers = true)).use { decoder ->
            while (true) {
                val frame = decoder.nextFrame() ?: break
                reducer.push(frame.samples, 0, frame.frameCount)
                pulled += reducer.pull(min, max, rms, pulled)
            }
        }
        reducer.finish()
        pulled += reducer.pull(min, max, rms, pulled)
        // --8<-- [end:waveform]

        assertEquals(ceil(88_200 / 1024.0).toInt(), buckets)
        assertEquals(buckets, pulled)
        // A tone at 0.1 peaks at 0.1 and has an RMS of 0.1 / sqrt(2) in every full bucket.
        assertTrue(max.all { abs(it - 0.1f) < 0.002f }, max.joinToString())
        assertTrue(rms.take(10).all { abs(it - 0.0707f) < 0.001f })
    }
}

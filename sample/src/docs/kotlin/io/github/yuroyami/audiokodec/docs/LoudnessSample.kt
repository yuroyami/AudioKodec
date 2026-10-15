package io.github.yuroyami.audiokodec.docs

// --8<-- [start:imports]
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.LoudnessMeter
// --8<-- [end:imports]
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LoudnessSample {
    private val bytes = Fixtures.tone

    @Test
    fun measureLoudnessAndTagIt() {
        // --8<-- [start:loudness]
        val decoded = AudioKodec.decode(bytes)
        val meter = LoudnessMeter(decoded.format.sampleRate, decoded.format.channelLayout)
        meter.add(decoded.samples)
        meter.finish()
        println(meter.integratedLoudness)   // LUFS
        println(meter.loudnessRange)        // LU

        val tags = AudioKodec.readTags(bytes).edit { setReplayGain(meter.replayGain()) }
        val retagged = AudioKodec.writeTags(bytes, tags)
        // --8<-- [end:loudness]

        // A 1 kHz tone at -20 dBFS in both channels measures -20 LUFS.
        assertEquals(-20.0, meter.integratedLoudness, 0.1)
        val gain = assertNotNull(AudioKodec.readTags(retagged).replayGain?.trackGainDb)
        // ReplayGain 2 aims at -18 LUFS, so a -20 LUFS track gains 2 dB.
        assertEquals(2.0, gain, 0.1)
    }

    @Test
    fun measureLoudnessWhileStreaming() {
        // --8<-- [start:loudness-stream]
        val meter = AudioKodec.open(bytes, DecodeOptions(reuseBuffers = true)).use { decoder ->
            val meter = LoudnessMeter(decoder.format.sampleRate, decoder.format.channelLayout)
            while (true) {
                val block = decoder.nextFrame() ?: break
                meter.add(block.samples, 0, block.frameCount)
            }
            meter.finish()
            meter
        }
        // --8<-- [end:loudness-stream]

        assertEquals(-20.0, meter.integratedLoudness, 0.1)
    }
}

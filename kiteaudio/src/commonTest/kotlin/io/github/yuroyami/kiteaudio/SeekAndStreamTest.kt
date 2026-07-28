package io.github.yuroyami.kiteaudio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeekAndStreamTest {

    private val format = AudioFormat(44_100, 2)
    private val frames = 5_000
    private val original = TestSignals.quantise(TestSignals.sine(frames, 2), 16)
    private val file = KiteAudio.encode(original, format, EncodeTarget.wav(16))

    @Test
    fun readingBlockByBlockGivesTheSameSamplesAsReadingItAllAtOnce() {
        val collected = FloatArray(frames * 2)
        var written = 0
        KiteAudio.open(file).use { decoder ->
            while (true) {
                val block = decoder.nextFrame() ?: break
                block.samples.copyInto(collected, written, 0, block.sampleCount)
                written += block.sampleCount
            }
        }
        assertEquals(frames * 2, written)
        TestSignals.assertExact(original, collected)
    }

    @Test
    fun aSeekLandsExactlyOnTheFrameItWasGiven() {
        KiteAudio.open(file).use { decoder ->
            for (target in longArrayOf(0, 1, 999, 1_000, 2_500, 4_999)) {
                val reached = decoder.seek(target)
                assertEquals(target, reached, "seek to $target")
                assertEquals(target, decoder.positionFrames)
                val block = assertNotNull(decoder.nextFrame(), "no audio after seeking to $target")
                assertEquals(target, block.positionFrames)
                for (c in 0 until 2) {
                    assertEquals(
                        original[(target.toInt() * 2) + c],
                        block[0, c],
                        "channel $c of the frame at $target",
                    )
                }
            }
        }
    }

    @Test
    fun seekingPastTheEndStopsAtTheEnd() {
        KiteAudio.open(file).use { decoder ->
            val reached = decoder.seek(1_000_000)
            assertEquals(frames.toLong(), reached)
            assertNull(decoder.nextFrame())
        }
    }

    @Test
    fun seekingBackwardsWorksAsWellAsForwards() {
        KiteAudio.open(file).use { decoder ->
            decoder.seek(4_000)
            decoder.nextFrame()
            decoder.seek(10)
            val block = assertNotNull(decoder.nextFrame())
            assertEquals(10L, block.positionFrames)
            assertEquals(original[20], block[0, 0])
        }
    }

    @Test
    fun everythingAfterASeekMatchesTheSameStretchOfAFullRead() {
        val target = 1_234
        KiteAudio.open(file).use { decoder ->
            decoder.seek(target.toLong())
            val rest = FloatArray((frames - target) * 2)
            var written = 0
            while (true) {
                val block = decoder.nextFrame() ?: break
                block.samples.copyInto(rest, written, 0, block.sampleCount)
                written += block.sampleCount
            }
            assertEquals((frames - target) * 2, written)
            TestSignals.assertExact(original.copyOfRange(target * 2, frames * 2), rest)
        }
    }

    @Test
    fun seekingInsideAnAdpcmBlockIsStillSampleAccurate() {
        // ADPCM restarts its predictor once per block, so landing on an
        // arbitrary frame means decoding the block and dropping the front of
        // it. This is the test that says the dropping is right.
        val adpcm = KiteAudio.encode(
            TestSignals.sine(4_000, 1, amplitude = 0.6f), AudioFormat(44_100, 1),
            EncodeTarget.wavImaAdpcm(512),
        )
        val whole = KiteAudio.decode(adpcm)
        KiteAudio.open(adpcm).use { decoder ->
            for (target in longArrayOf(0, 1, 505, 700, 1_010, 3_999)) {
                decoder.seek(target)
                val block = assertNotNull(decoder.nextFrame(), "no audio after seeking to $target")
                assertEquals(target, block.positionFrames)
                assertEquals(
                    whole.samples[target.toInt()],
                    block[0, 0],
                    "the frame at $target differs from the same frame of a full decode",
                )
            }
        }
    }

    @Test
    fun reusedBuffersHoldTheSameAudioAsFreshOnes() {
        val fresh = ArrayList<Float>()
        KiteAudio.open(file).use { decoder ->
            while (true) {
                val block = decoder.nextFrame() ?: break
                for (i in 0 until block.sampleCount) fresh.add(block.samples[i])
            }
        }
        val reused = ArrayList<Float>()
        KiteAudio.open(file, DecodeOptions(reuseBuffers = true)).use { decoder ->
            while (true) {
                val block = decoder.nextFrame() ?: break
                for (i in 0 until block.sampleCount) reused.add(block.samples[i])
            }
        }
        assertEquals(fresh.size, reused.size)
        for (i in fresh.indices) {
            assertEquals(fresh[i], reused[i], "sample $i differs between buffer strategies")
        }
    }

    @Test
    fun aClosedDecoderRefusesToKeepWorking() {
        val decoder = KiteAudio.open(file)
        decoder.close()
        var threw = false
        try {
            decoder.nextFrame()
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw, "a closed decoder should not decode")
    }

    @Test
    fun theDecoderReportsWhatTheHeaderSaidWithoutASecondParse() {
        KiteAudio.open(file).use { decoder ->
            assertEquals(AudioContainer.WAV, decoder.info.container)
            assertEquals(frames.toLong(), decoder.durationFrames)
            assertEquals(16, decoder.info.bitDepth)
            assertEquals(format, decoder.format)
        }
    }
}

package io.github.yuroyami.audiokodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DecodedAudioEncodingTest {
    @Test
    fun aDecodedValueCanBeEncodedRepeatedlyIntoSeveralFormats() {
        for (depth in intArrayOf(16, 24)) {
            for (channels in 1..2) {
                for (frames in intArrayOf(0, 1, 4_097)) {
                    val decoded = fixture(frames, channels, depth)
                    for (target in listOf(EncodeTarget.wav(depth), EncodeTarget.aiff(depth), EncodeTarget.au(depth), EncodeTarget.flac(depth))) {
                        val first = AudioKodec.encode(decoded, target)
                        assertContentEquals(first, AudioKodec.encode(decoded, target))
                        assertAudio(decoded, first)
                    }
                }
            }
        }
    }

    @Test
    fun encodingUsesTheWholeValueAndPreservesTheDirectReadCursor() {
        val decoded = fixture()
        val prefix = FloatArray(6)
        assertEquals(3, decoded.read(prefix, 0, 3))
        assertContentEquals(decoded.samples.copyOf(6), prefix)
        assertAudio(decoded, AudioKodec.encode(decoded, EncodeTarget.wav(16)))
        assertAudio(decoded, AudioKodec.encode(decoded, EncodeTarget.flac(16)))
        val following = FloatArray(4)
        assertEquals(2, decoded.read(following, 0, 2))
        assertContentEquals(decoded.samples.copyOfRange(6, 10), following)

        decoded.rewind()
        val all = FloatArray(decoded.samples.size)
        assertEquals(decoded.frames, decoded.read(all, 0, decoded.frames))
        assertContentEquals(decoded.samples, all)
        assertEquals(0, decoded.read(prefix, 0, 3))
        assertAudio(decoded, AudioKodec.encode(decoded, EncodeTarget.aiff(16)))
        assertEquals(0, decoded.read(prefix, 0, 3))
        decoded.rewind()
        assertEquals(3, decoded.read(prefix, 0, 3))
        assertContentEquals(decoded.samples.copyOf(6), prefix)
    }

    @Test
    fun aFailedWriteLeavesTheValueAvailableForRetryAndDirectReading() {
        val decoded = fixture()
        val prefix = FloatArray(2)
        assertEquals(1, decoded.read(prefix, 0, 1))
        val output = ByteArrayAudioSink()
        var payloadWrites = 0
        val sink = object : AudioSink by output {
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                if (count > 1_024 && ++payloadWrites == 2) throw AudioKodecException("output failed during audio")
                output.write(bytes, offset, count)
            }
        }
        val failure = assertFailsWith<AudioKodecException> {
            AudioKodec.encode(decoded, EncodeTarget.wav(16), sink)
        }
        assertEquals("output failed during audio", failure.message)
        assertEquals(2, payloadWrites)
        assertTrue(output.position > 1_024, "the failure must follow a written audio block")
        assertEquals(1, decoded.read(prefix, 0, 1))
        assertContentEquals(decoded.samples.copyOfRange(2, 4), prefix)
        assertAudio(decoded, AudioKodec.encode(decoded, EncodeTarget.wav(16)))
        assertAudio(decoded, AudioKodec.encode(decoded, EncodeTarget.flac(16)))
    }

    @Test
    fun encodingsStartedDuringAnotherEncodingKeepIndependentReaders() {
        val decoded = fixture()
        val output = ByteArrayAudioSink()
        var nested: ByteArray? = null
        var startNested = true
        val sink = object : AudioSink by output {
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                output.write(bytes, offset, count)
                if (startNested && count > 1_024) {
                    startNested = false
                    nested = AudioKodec.encode(decoded, EncodeTarget.flac(16))
                }
            }
        }
        AudioKodec.encode(decoded, EncodeTarget.wav(16), sink)
        assertAudio(decoded, output.toByteArray())
        assertAudio(decoded, assertNotNull(nested))
        val prefix = FloatArray(2)
        assertEquals(1, decoded.read(prefix, 0, 1))
        assertContentEquals(decoded.samples.copyOf(2), prefix)
    }

    @Test
    fun eachEncodingReadsTheCurrentSamplesRatherThanACachedFile() {
        val decoded = fixture(frames = 3)
        val first = AudioKodec.encode(decoded, EncodeTarget.wav(16))
        decoded.samples[1] = -0.5f
        val second = AudioKodec.encode(decoded, EncodeTarget.wav(16))
        assertTrue(!first.contentEquals(second))
        assertAudio(decoded, second)
    }

    @Test
    fun ordinaryStreamingSourcesStillConsumeTheirOwnCursor() {
        val decoded = fixture(frames = 3)
        val stream = decoded.samples.asPcmSource(decoded.format)
        assertAudio(decoded, AudioKodec.encode(stream, EncodeTarget.wav(16)))
        assertEquals(0, stream.read(FloatArray(2), 0, 1))
        assertFailsWith<AudioKodecException> { AudioKodec.encode(stream, EncodeTarget.wav(16)) }
    }

    private fun fixture(frames: Int = 9_001, channels: Int = 2, depth: Int = 16): DecodedAudio {
        val samples = TestSignals.quantise(TestSignals.sine(frames, channels, amplitude = 0.7f), depth)
        return AudioKodec.decode(AudioKodec.encode(samples, AudioFormat(44_100, channels), EncodeTarget.wav(depth)))
    }

    private fun assertAudio(expected: DecodedAudio, encoded: ByteArray) {
        val actual = AudioKodec.decode(encoded)
        assertEquals(expected.format, actual.format)
        assertEquals(expected.frames, actual.frames)
        assertContentEquals(expected.samples, actual.samples)
    }
}

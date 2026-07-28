package io.github.yuroyami.kiteaudio.io

import io.github.yuroyami.kiteaudio.AudioFormat
import io.github.yuroyami.kiteaudio.EncodeTarget
import io.github.yuroyami.kiteaudio.KiteAudio
import io.github.yuroyami.kiteaudio.KiteAudioException
import io.github.yuroyami.kiteaudio.asPcmSource
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KiteAudioIoTest {

    private val format = AudioFormat(44_100, 2)
    private val samples = FloatArray(1_000 * 2) { (it % 200) / 400f - 0.25f }

    @Test
    fun aBufferReadsBackAsAnAudioSource() {
        val file = KiteAudio.encode(samples, format, EncodeTarget.wav(16))
        val buffer = Buffer().apply { write(file) }
        val decoded = buffer.readAudioSource().use { KiteAudio.decode(it) }
        assertEquals(1_000, decoded.frames)
        assertEquals(44_100, decoded.format.sampleRate)
    }

    @Test
    fun aSourceLargerThanTheLimitIsRefusedRatherThanLoaded() {
        val buffer = Buffer().apply { write(ByteArray(200_000)) }
        assertFailsWith<KiteAudioException> {
            buffer.readAudioSource(maxBytes = 100_000)
        }
    }

    @Test
    fun negativeSourceLimitAndInvalidSinkWindowsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            Buffer().readAudioSource(maxBytes = -1)
        }

        val sink = Buffer().asAudioSink()
        assertFailsWith<IllegalArgumentException> {
            sink.write(ByteArray(4), offset = 3, count = 2)
        }
        sink.close()
    }

    @Test
    fun anEncoderCanWriteStraightIntoASink() {
        val buffer = Buffer()
        buffer.asAudioSink().use { sink ->
            KiteAudio.encode(samples.asPcmSource(format), EncodeTarget.wav(16), sink)
        }
        val written = buffer.readByteArray()
        assertTrue(written.isNotEmpty(), "nothing was written to the sink")
        val decoded = KiteAudio.decode(written)
        assertEquals(1_000, decoded.frames)
        assertEquals(2, decoded.format.channels)
    }

    @Test
    fun aSinkThatCannotBePatchedNeedsTheFrameCountUpFront() {
        val buffer = Buffer()
        val unknownLength = object : io.github.yuroyami.kiteaudio.PcmSource {
            private var left = 100
            override val format: AudioFormat get() = this@KiteAudioIoTest.format
            override val frameCount: Long? get() = null
            override fun read(into: FloatArray, offset: Int, frames: Int): Int {
                if (left <= 0) return 0
                val n = minOf(frames, left)
                left -= n
                return n
            }
        }
        buffer.asAudioSink().use { sink ->
            assertFailsWith<KiteAudioException> {
                KiteAudio.encode(unknownLength, EncodeTarget.wav(16), sink)
            }
        }
    }

    @Test
    fun aStreamedFileMatchesOneBuiltInMemory() {
        val inMemory = KiteAudio.encode(samples, format, EncodeTarget.wav(24))
        val buffer = Buffer()
        buffer.asAudioSink().use { sink ->
            KiteAudio.encode(samples.asPcmSource(format), EncodeTarget.wav(24), sink)
        }
        val streamed = buffer.readByteArray()
        // The in-memory writer patches its length fields at the end and the
        // streaming one writes them from the known frame count, so the two
        // files have to come out identical.
        assertEquals(inMemory.size, streamed.size, "the two files are different lengths")
        for (i in inMemory.indices) {
            assertEquals(inMemory[i], streamed[i], "byte $i differs between the two writers")
        }
    }
}

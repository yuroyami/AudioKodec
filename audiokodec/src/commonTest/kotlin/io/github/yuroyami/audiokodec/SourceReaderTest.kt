package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.io.SourceReader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class SourceReaderTest {

    private class InterruptedSource(
        bytes: ByteArray,
        private val failAt: Long,
        private val maximumRead: Int,
        private val interrupt: (Int) -> Int,
    ) : AudioSource {
        private val inner = bytes.asAudioSource()
        private var interrupted = false
        override val size: Long get() = inner.size

        override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int {
            if (!interrupted && position == failAt) {
                interrupted = true
                return interrupt(count)
            }
            return inner.readAt(position, into, offset, minOf(count, maximumRead))
        }

        override fun close() = inner.close()
    }

    @Test
    fun aSourceFailureCannotLeaveAnOverwrittenWindowReadable() {
        val bytes = ByteArray(48) { it.toByte() }
        val failure = IllegalStateException("source temporarily unavailable")
        val source = InterruptedSource(bytes, 20, 4) { throw failure }
        val reader = SourceReader(source, DecodeOptions.DEFAULT, bufferSize = 16)
        assertEquals(0, reader.readU8())
        reader.seek(16)
        assertSame(failure, assertFailsWith<IllegalStateException> { reader.readU8() })
        assertEquals(16L, reader.position)
        reader.seek(0)
        assertContentEquals(bytes.copyOfRange(0, 8), reader.readBytes(8))
        reader.seek(16)
        assertContentEquals(bytes.copyOfRange(16, 24), reader.readBytes(8))
    }

    @Test
    fun anInvalidReadCountCannotLeaveAnOverwrittenWindowReadable() {
        val bytes = ByteArray(48) { it.toByte() }
        for (invalidCount in listOf<(Int) -> Int>({ 0 }, { -2 }, { it + 1 })) {
            val source = InterruptedSource(bytes, 20, 4, invalidCount)
            val reader = SourceReader(source, DecodeOptions.DEFAULT, bufferSize = 16)
            assertEquals(0, reader.readU8())
            reader.seek(16)
            assertFailsWith<AudioKodecException> { reader.readU8() }
            assertEquals(16L, reader.position)
            reader.seek(0)
            assertContentEquals(bytes.copyOfRange(0, 8), reader.readBytes(8))
            reader.seek(16)
            assertContentEquals(bytes.copyOfRange(16, 24), reader.readBytes(8))
        }
    }

    @Test
    fun wavSamplesRemainExactAfterASourceFailureAndASeek() {
        val format = AudioFormat(44_100, 1)
        val samples = TestSignals.quantise(TestSignals.sine(100_000, 1), 16)
        val wav = AudioKodec.encode(samples, format, EncodeTarget.wav(16))
        val expected = AudioKodec.decode(wav).samples
        val failure = IllegalStateException("source temporarily unavailable")
        val source = InterruptedSource(wav, 65_536 + 1_000L, 1_000) { throw failure }
        AudioKodec.open(source).use { decoder ->
            assertSame(failure, assertFailsWith<IllegalStateException> {
                while (decoder.nextFrame() != null) { /* Read until the interrupted refill. */ }
            })
            assertEquals(0L, decoder.seek(0))
            var frames = 0
            while (frames < samples.size) {
                val block = assertNotNull(decoder.nextFrame())
                assertEquals(frames.toLong(), block.positionFrames)
                for (i in 0 until block.sampleCount) {
                    assertEquals(expected[frames + i], block.samples[i], "sample ${frames + i}")
                }
                frames += block.frameCount
            }
            assertEquals(samples.size, frames)
        }
    }
}

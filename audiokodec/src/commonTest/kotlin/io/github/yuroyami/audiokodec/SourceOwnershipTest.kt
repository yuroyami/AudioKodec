package io.github.yuroyami.audiokodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Who closes a source, and how many times.
 *
 * The owning entry points take the source on the call, so a failure while
 * opening must not leave a file or network handle open behind it, and a
 * success must not close it twice.
 */
class SourceOwnershipTest {

    /** Serves [bytes] and counts how often it is closed. */
    private class CountingSource(private val bytes: ByteArray) : AudioSource {
        var closes = 0
        private val inner = bytes.asAudioSource()
        override val size: Long get() = bytes.size.toLong()
        override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int =
            inner.readAt(position, into, offset, count)
        override fun close() {
            closes++
        }
    }

    private val wav = AudioKodec.encode(FloatArray(64), AudioFormat(8_000, 1), EncodeTarget.wav())

    private val failures: List<Pair<String, ByteArray>> = listOf(
        "no format at all" to ByteArray(4),
        "a recognised but truncated header" to wav.copyOf(20),
        "a supported container holding an unsupported coding" to wav.copyOf().also {
            val fmt = it.indexOfBytes("fmt ".encodeToByteArray())
            it[fmt + 8] = 0x55 // MPEG Layer III inside WAV.
            it[fmt + 9] = 0
        },
    )

    @Test
    fun aFailedOpenClosesItsSourceOnce() {
        for ((what, bytes) in failures) {
            val source = CountingSource(bytes)
            assertFailsWith<AudioKodecException>(what) {
                AudioKodec.open(source).use { it.nextFrame() }
            }
            assertEquals(1, source.closes, what)
        }
    }

    @Test
    fun wholeFileDecodingClosesItsSourceOnceWhetherOrNotItSucceeds() {
        for ((what, bytes) in failures) {
            val source = CountingSource(bytes)
            assertFailsWith<AudioKodecException>(what) { AudioKodec.decode(source) }
            assertEquals(1, source.closes, what)
        }
        val source = CountingSource(wav)
        AudioKodec.decode(source)
        assertEquals(1, source.closes)
    }

    @Test
    fun aSuccessfulOpenHandsTheSourceToTheDecoder() {
        val source = CountingSource(wav)
        val decoder = AudioKodec.open(source)
        assertEquals(0, source.closes)
        decoder.close()
        assertEquals(1, source.closes)
    }

    @Test
    fun probingNeverClosesTheSource() {
        for ((_, bytes) in failures) {
            val source = CountingSource(bytes)
            try {
                AudioKodec.probe(source)
            } catch (_: AudioKodecException) {
                // Only the close count matters here.
            }
            assertEquals(0, source.closes)
        }
    }
}

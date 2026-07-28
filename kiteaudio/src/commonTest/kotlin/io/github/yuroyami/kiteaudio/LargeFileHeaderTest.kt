package io.github.yuroyami.kiteaudio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The header a WAV over 4 GiB gets.
 *
 * Writing four gibibytes of silence to prove this would take minutes, so the
 * test stops the encoder once the header is out and inspects what was
 * written. That is the part the size decision lives in; everything after it is
 * the same sample loop every other file uses.
 */
class LargeFileHeaderTest {

    private class HeaderOnlySink(private val stopAfter: Int) : AudioSink {
        val bytes = ByteArray(stopAfter)
        var written = 0
            private set

        override val canPatch: Boolean get() = true

        override val position: Long get() = written.toLong()

        override fun write(bytes: ByteArray, offset: Int, count: Int) {
            val room = this.bytes.size - written
            if (room > 0) {
                val n = if (count < room) count else room
                bytes.copyInto(this.bytes, written, offset, offset + n)
            }
            written += count
            if (written >= stopAfter) throw StopWriting()
        }

        override fun patchAt(position: Long, bytes: ByteArray, offset: Int, count: Int) {
            bytes.copyInto(this.bytes, position.toInt(), offset, offset + count)
        }

        override fun close() {
            // Nothing to release.
        }
    }

    private class StopWriting : RuntimeException("the test has seen enough")

    /** A source that promises a great deal of silence and delivers it slowly. */
    private class SilenceSource(
        override val format: AudioFormat,
        override val frameCount: Long,
    ) : PcmSource {
        private var produced = 0L

        override fun read(into: FloatArray, offset: Int, frames: Int): Int {
            val left = frameCount - produced
            if (left <= 0) return 0
            val n = if (frames.toLong() < left) frames else left.toInt()
            into.fill(0f, offset, offset + n * format.channels)
            produced += n
            return n
        }
    }

    @Test
    fun aFileThatWillPassFourGibibytesIsWrittenAsRf64() {
        val format = AudioFormat(48_000, 2)
        // Just over 4 GiB of 24-bit stereo.
        val frames = (4L * 1024 * 1024 * 1024) / 6 + 1_000
        val sink = HeaderOnlySink(stopAfter = 128)
        try {
            KiteAudio.encode(SilenceSource(format, frames), EncodeTarget.wav(24), sink)
        } catch (_: StopWriting) {
            // Expected: the header is all this test needs.
        }

        val header = sink.bytes
        assertEquals("RF64", header.text(0, 4), "the outer identifier")
        // The 32-bit size field is the placeholder that sends a reader to ds64.
        assertEquals(0xFFFF_FFFFL, header.u32Le(4), "the RIFF size field")
        assertEquals("WAVE", header.text(8, 4))
        assertEquals("ds64", header.text(12, 4), "the reserved slot holds the 64-bit sizes")
        assertEquals(28L, header.u32Le(16), "the ds64 body size")

        val expectedData = frames * 6
        assertEquals(expectedData, header.i64Le(28), "the 64-bit data size")
        assertEquals(frames, header.i64Le(36), "the frame count")
        // The 64-bit form size counts everything after the size field.
        assertTrue(header.i64Le(20) > expectedData, "the 64-bit form size")

        assertEquals("fmt ", header.text(48, 4), "the format chunk follows the slot")
    }

    @Test
    fun aFileThatStaysUnderFourGibibytesKeepsThePlainHeader() {
        val format = AudioFormat(48_000, 2)
        val file = KiteAudio.encode(
            TestSignals.quantise(TestSignals.sine(100, 2), 16), format, EncodeTarget.wav(16),
        )
        assertEquals("RIFF", file.text(0, 4))
        assertEquals("WAVE", file.text(8, 4))
        // The same 36 bytes are reserved, holding padding this time, so a
        // streamed file and a patched one come out identical.
        assertEquals("JUNK", file.text(12, 4))
        assertEquals(28L, file.u32Le(16))
        assertEquals("fmt ", file.text(48, 4))
    }
}

private fun ByteArray.text(at: Int, length: Int): String =
    CharArray(length) { (this[at + it].toInt() and 0xFF).toChar() }.concatToString()

private fun ByteArray.u32Le(at: Int): Long {
    var value = 0L
    for (i in 3 downTo 0) value = (value shl 8) or (this[at + i].toLong() and 0xFF)
    return value
}

private fun ByteArray.i64Le(at: Int): Long {
    var value = 0L
    for (i in 7 downTo 0) value = (value shl 8) or (this[at + i].toLong() and 0xFF)
    return value
}

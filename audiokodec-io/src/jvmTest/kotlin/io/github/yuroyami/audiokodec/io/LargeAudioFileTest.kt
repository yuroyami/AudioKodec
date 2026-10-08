package io.github.yuroyami.audiokodec.io

import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.AudioSource
import io.github.yuroyami.audiokodec.EncodeTarget
import io.github.yuroyami.audiokodec.IntPcmAccess
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LargeAudioFileTest {
    @Test
    fun aWaveLargerThanTheBufferingCeilingOpensAndSeeksWithBoundedReads() {
        val file = Files.createTempFile("audiokodec-large-wave-", ".wav").toFile()
        try {
            val format = AudioFormat(48_000, 2)
            val tailFrames = 8_193
            val frames = 512L * 1024 * 1024 / 8 + tailFrames
            val samples = IntArray(tailFrames * 2) { it * 1_664_525 + 1_013_904_223 }
            samples[0] = Int.MIN_VALUE
            samples[1] = Int.MAX_VALUE
            val empty = AudioKodec.encode(IntArray(0), format, 32, EncodeTarget.wav(32))
            val at = AudioKodec.probe(empty).dataOffset
            val size = at + frames * 8
            RandomAccessFile(file, "rw").use { handle ->
                handle.write(empty)
                handle.setLength(size)
                putU32(handle, 4, size - 8)
                putU32(handle, at - 4, frames * 8)
                handle.seek(at + (frames - tailFrames) * 8)
                for (sample in samples) repeat(4) { byte -> handle.write(sample ushr (8 * byte)) }
            }
            val source = Path(file.path).openAudioSource()
            assertTrue(source.size > 512L * 1024 * 1024)
            var readBytes = 0L
            val counted = object : AudioSource by source {
                override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int {
                    val got = source.readAt(position, into, offset, count)
                    if (got > 0) readBytes += got
                    return got
                }
            }
            AudioKodec.open(counted).use { decoder ->
                assertEquals(frames, decoder.durationFrames)
                assertEquals(frames - tailFrames, decoder.seek(frames - tailFrames))
                val integer = assertIs<IntPcmAccess>(decoder)
                val actual = IntArray(samples.size)
                var written = 0
                while (true) {
                    val block = integer.nextIntFrame() ?: break
                    block.samples.copyInto(actual, written, 0, block.sampleCount)
                    written += block.sampleCount
                }
                assertEquals(samples.size, written)
                assertContentEquals(samples, actual)
                assertEquals(frames, decoder.seek(frames))
                assertNull(integer.nextIntFrame())
                assertEquals(0L, decoder.seek(0))
                val first = assertNotNull(integer.nextIntFrame())
                assertTrue(first.samples.take(first.sampleCount).all { it == 0 })
            }
            assertTrue(readBytes < 256L * 1024, "opening and seeking read $readBytes bytes from the $size-byte recording")
            assertEquals(size, file.length(), "read-only access changed the recording")
        } finally {
            file.delete()
        }
    }

    @Test
    fun positionsBeyondFourGiBAreNotTruncated() {
        val file = Files.createTempFile("audiokodec-wide-position-", ".raw").toFile()
        try {
            val at = 5L * 1024 * 1024 * 1024 + 17
            val expected = ByteArray(101) { (it * 37).toByte() }
            RandomAccessFile(file, "rw").use { handle ->
                handle.setLength(at + expected.size)
                handle.seek(at)
                handle.write(expected)
            }
            Path(file.path).openAudioSource().use { source ->
                assertEquals(at + expected.size, source.size)
                val bytes = ByteArray(expected.size)
                assertEquals(expected.size, source.readAt(at, bytes, 0, bytes.size))
                assertContentEquals(expected, bytes)
            }
        } finally {
            file.delete()
        }
    }

    private fun putU32(file: RandomAccessFile, at: Long, value: Long) {
        file.seek(at)
        repeat(4) { file.write((value ushr (8 * it)).toInt()) }
    }
}

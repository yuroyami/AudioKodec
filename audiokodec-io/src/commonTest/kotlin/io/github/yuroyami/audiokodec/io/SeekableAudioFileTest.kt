package io.github.yuroyami.audiokodec.io

import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.EncodeTarget
import io.github.yuroyami.audiokodec.IntPcmAccess
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeekableAudioFileTest {
    @Test
    fun absoluteReadsAreBoundedAndKeepOffsetsAndEofCorrect() = withFile { path ->
        val bytes = ByteArray(131_103) { (it * 37).toByte() }
        write(path, bytes)
        val source = path.openAudioSource()
        assertEquals(bytes.size.toLong(), source.size)
        val output = ByteArray(100_002) { -1 }
        assertEquals(65_536, source.readAt(17, output, 2, 100_000))
        assertContentEquals(bytes.copyOfRange(17, 17 + 65_536), output.copyOfRange(2, 2 + 65_536))
        assertEquals((-1).toByte(), output[1])
        assertEquals((-1).toByte(), output[65_538])
        assertEquals(5, source.readAt(source.size - 5, output, 3, 10))
        assertContentEquals(bytes.takeLast(5).toByteArray(), output.copyOfRange(3, 8))
        assertEquals(-1, source.readAt(source.size, output, 0, 1))
        assertEquals(-1, source.readAt(Long.MAX_VALUE, output, 0, 1))
        assertEquals(0, source.readAt(Long.MAX_VALUE, output, 0, 0))
        assertFailsWith<IllegalArgumentException> { source.readAt(-1, output, 0, 1) }
        assertFailsWith<IllegalArgumentException> { source.readAt(0, output, -1, 1) }
        assertFailsWith<IllegalArgumentException> { source.readAt(0, output, 0, -1) }
        assertFailsWith<IllegalArgumentException> { source.readAt(0, output, 1, Int.MAX_VALUE) }
        source.close()
        source.close()
        assertFailsWith<IllegalStateException> { source.readAt(0, output, 0, 1) }
        assertContentEquals(bytes, read(path))
        path.openAudioSource().use { assertEquals(1, it.readAt(0, output, 0, 1)) }
    }

    @Test
    fun allWritableLosslessContainersSeekLikeSequentialArrayDecodes() = withFile { path ->
        val frames = 4_101
        val format = AudioFormat(48_000, 2)
        val samples = IntArray(frames * 2) { (it * 1_664_525 + 1_013_904_223) shr 8 }
        for (target in listOf(EncodeTarget.wav(24), EncodeTarget.aiff(24), EncodeTarget.au(24), EncodeTarget.flac(24))) {
            val bytes = AudioKodec.encode(samples, format, 24, target)
            write(path, bytes)
            AudioKodec.open(path.openAudioSource()).use { decoder ->
                assertEquals(format, decoder.format)
                assertEquals(frames.toLong(), decoder.durationFrames)
                val integer = assertIs<IntPcmAccess>(decoder)
                for (at in listOf(0, 1, 4_097, 2_053, frames, 17)) {
                    assertEquals(at.toLong(), decoder.seek(at.toLong()))
                    val block = integer.nextIntFrame()
                    if (at == frames) assertNull(block)
                    else {
                        assertNotNull(block)
                        assertEquals(at.toLong(), block.positionFrames)
                        assertContentEquals(samples.copyOfRange(at * 2, at * 2 + block.sampleCount), block.samples.copyOf(block.sampleCount))
                    }
                }
            }
            assertContentEquals(AudioKodec.decode(bytes).samples, AudioKodec.decode(path.openAudioSource()).samples)
        }
    }

    @Test
    fun probeAndTagsKeepTheSourceAvailableAndDecoderCloseReleasesIt() = withFile { path ->
        val samples = intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE, 16_777_217, -16_777_217)
        val tags = TagBundle(mapOf(TagField.TITLE to listOf("Recording 日本語")))
        val bytes = AudioKodec.encode(samples, AudioFormat(48_000, 1), 32, EncodeTarget.wav(32), tags)
        write(path, bytes)
        val source = path.openAudioSource()
        assertEquals(4L, AudioKodec.probe(source).durationFrames)
        assertEquals(tags.fields, AudioKodec.readTags(source).fields)
        AudioKodec.open(source).use { decoder ->
            val block = assertNotNull(assertIs<IntPcmAccess>(decoder).nextIntFrame())
            assertContentEquals(samples, block.samples.copyOf(block.sampleCount))
        }
        assertFailsWith<IllegalStateException> { source.readAt(0, ByteArray(1), 0, 1) }
        source.close()
        assertContentEquals(bytes, read(path))
    }

    @Test
    fun truncatingAnOpenedFileGivesShortReadsAndTypedDecodeFailure() = withFile { path ->
        val bytes = AudioKodec.encode(IntArray(64), AudioFormat(8_000, 1), 32, EncodeTarget.wav(32))
        write(path, bytes)
        val source = path.openAudioSource()
        write(path, bytes.copyOf(8))
        assertEquals(bytes.size.toLong(), source.size, "the size is the opened handle's snapshot")
        assertEquals(8, source.readAt(0, ByteArray(32), 0, 32))
        assertEquals(-1, source.readAt(8, ByteArray(32), 0, 32))
        assertFailsWith<AudioKodecException> { AudioKodec.open(source) }
        assertFailsWith<IllegalStateException> { source.readAt(0, ByteArray(1), 0, 1) }
    }

    @Test
    fun missingFilesDirectoriesAndNullPathsAreRefused() = withFile { path ->
        assertFailsWith<IOException> { path.openAudioSource() }
        assertFailsWith<IOException> { assertNotNull(path.parent).openAudioSource() }
        assertFailsWith<IllegalArgumentException> { Path("null\u0000suffix").openAudioSource() }
        write(path, byteArrayOf())
        path.openAudioSource().use { source ->
            assertEquals(0L, source.size)
            assertEquals(-1, source.readAt(0, ByteArray(1), 0, 1))
            assertEquals(0, source.readAt(0, ByteArray(0), 0, 0))
        }
    }

    private fun write(path: Path, bytes: ByteArray) {
        SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
    }
    private fun read(path: Path): ByteArray = SystemFileSystem.source(path).buffered().use { it.readByteArray() }

    private inline fun withFile(block: (Path) -> Unit) {
        val directory = Path(".audiokodec-seekable-東京-${Random.nextLong().toULong().toString(16)}")
        SystemFileSystem.createDirectories(directory, mustCreate = true)
        val path = Path(directory, "recording.wav")
        try { block(path) } finally {
            for (child in SystemFileSystem.list(directory)) SystemFileSystem.delete(child)
            SystemFileSystem.delete(directory)
        }
    }
}

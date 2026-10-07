package io.github.yuroyami.audiokodec.io

import io.github.yuroyami.audiokodec.AudioChannel
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.EncodeTarget
import io.github.yuroyami.audiokodec.PcmSource
import io.github.yuroyami.audiokodec.asPcmSource
import kotlinx.io.buffered
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioFileTest {
    private val oldBytes = ByteArray(4_096) { (it * 37).toByte() }
    private val format = AudioFormat(44_100, 2)
    private val samples = FloatArray(4_000) { (it % 100) / 200f - 0.25f }

    @Test
    fun unusedAndInvalidSinksPreserveAnExistingFile() = withDirectory { directory ->
        val path = Path(directory, "existing.wav")
        write(path, oldBytes)
        val sink = path.asAudioSink()
        assertEquals(0L, sink.position)
        assertFalse(sink.canPatch)
        assertContentEquals(oldBytes, read(path), "constructing a sink changed the file")
        sink.write(ByteArray(0), 0, 0)
        assertFailsWith<IllegalArgumentException> { sink.write(ByteArray(4), 3, 2) }
        assertFailsWith<IllegalArgumentException> { sink.write(ByteArray(4), 0, -1) }
        assertContentEquals(oldBytes, read(path), "a rejected write changed the file")
        sink.close()
        sink.close()
        assertContentEquals(oldBytes, read(path), "closing an unused sink changed the file")
        assertFailsWith<IllegalStateException> { sink.write(byteArrayOf(1), 0, 1) }

        val absent = Path(directory, "absent.wav")
        absent.asAudioSink().use { it.write(ByteArray(0), 0, 0) }
        assertFalse(SystemFileSystem.exists(absent))
    }

    @Test
    fun rejectedLayoutsDoNotTruncateTheDestination() = withDirectory { directory ->
        val path = Path(directory, "existing.wav")
        val layout = ChannelLayout.of(AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_LEFT)
        val custom = AudioFormat(44_100, layout)
        for (target in listOf(EncodeTarget.au(), EncodeTarget.aiff(), EncodeTarget.flac())) {
            write(path, oldBytes)
            path.asAudioSink().use { sink ->
                assertFailsWith<IllegalArgumentException>(target.toString()) {
                    AudioKodec.encode(samples.asPcmSource(custom), target, sink)
                }
            }
            assertContentEquals(oldBytes, read(path), target.toString())
        }
    }

    @Test
    fun anUnknownFrameCountDoesNotTruncateTheDestination() = withDirectory { directory ->
        val path = Path(directory, "existing.wav")
        write(path, oldBytes)
        val pcm = object : PcmSource {
            override val format: AudioFormat = this@AudioFileTest.format
            override val frameCount: Long? = null
            override fun read(into: FloatArray, offset: Int, frames: Int): Int = error("validation must precede reading")
        }
        path.asAudioSink().use { sink ->
            assertFailsWith<AudioKodecException> { AudioKodec.encode(pcm, EncodeTarget.wav(), sink) }
        }
        assertContentEquals(oldBytes, read(path))
    }

    @Test
    fun aSuccessfulDirectEncodeReplacesTheFile() = withDirectory { directory ->
        val path = Path(directory, "existing.wav")
        write(path, oldBytes)
        path.asAudioSink().use { sink ->
            AudioKodec.encode(samples.asPcmSource(format), EncodeTarget.wav(), sink)
            assertTrue(sink.position > 0)
        }
        assertContentEquals(AudioKodec.encode(samples, format, EncodeTarget.wav()), read(path))
    }

    @Test
    fun atomicEncodesReplaceOnlyAfterTheOutputIsComplete() = withDirectory { directory ->
        val path = Path(directory, "existing.wav")
        for (existing in listOf(false, true)) {
            if (existing) write(path, oldBytes)
            path.writeAudioAtomically { sink ->
                AudioKodec.encode(samples.asPcmSource(format), EncodeTarget.wav(), sink)
                if (existing) assertContentEquals(oldBytes, read(path))
                else assertFalse(SystemFileSystem.exists(path))
            }
            assertContentEquals(AudioKodec.encode(samples, format, EncodeTarget.wav()), read(path))
            assertEquals(listOf(path.name), SystemFileSystem.list(directory).map { it.name })
        }
    }

    @Test
    fun failedAtomicEncodesPreserveTheOriginalAndRemoveTemporaryFiles() = withDirectory { directory ->
        val path = Path(directory, "existing.wav")
        write(path, oldBytes)
        val pcm = object : PcmSource {
            override val format: AudioFormat = this@AudioFileTest.format
            override val frameCount: Long = 40_000
            private var produced = 0
            override fun read(into: FloatArray, offset: Int, frames: Int): Int {
                if (produced >= 20_000) throw IllegalStateException("source failed after partial output")
                val count = minOf(frames, 20_000 - produced)
                into.fill(0f, offset, offset + count * format.channels)
                produced += count
                return count
            }
        }
        val failure = assertFailsWith<IllegalStateException> {
            path.writeAudioAtomically { AudioKodec.encode(pcm, EncodeTarget.wav(), it) }
        }
        assertEquals("source failed after partial output", failure.message)
        assertContentEquals(oldBytes, read(path))
        assertEquals(listOf(path.name), SystemFileSystem.list(directory).map { it.name })
        val custom = AudioFormat(44_100, ChannelLayout.of(AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_LEFT))
        assertFailsWith<IllegalArgumentException> {
            path.writeAudioAtomically { AudioKodec.encode(samples.asPcmSource(custom), EncodeTarget.au(), it) }
        }
        assertContentEquals(oldBytes, read(path))
        assertEquals(listOf(path.name), SystemFileSystem.list(directory).map { it.name })
    }

    @Test
    fun failedAtomicReplacementPreservesTheDestinationDirectory() = withDirectory { directory ->
        val destination = Path(directory, "existing")
        SystemFileSystem.createDirectories(destination)
        val child = Path(destination, "retained")
        write(child, oldBytes)
        assertFailsWith<IOException> {
            destination.writeAudioAtomically { AudioKodec.encode(samples.asPcmSource(format), EncodeTarget.wav(), it) }
        }
        assertContentEquals(oldBytes, read(child))
        assertEquals(listOf(destination.name), SystemFileSystem.list(directory).map { it.name })
    }

    @Test
    fun atomicWritesRequireAnExistingParent() = withDirectory { directory ->
        val missing = Path(directory, "missing", "out.wav")
        assertFailsWith<IOException> { missing.writeAudioAtomically { error("the parent must be checked first") } }
        assertEquals(emptyList(), SystemFileSystem.list(directory).toList())
    }

    private fun read(path: Path): ByteArray = SystemFileSystem.source(path).buffered().use { it.readByteArray() }

    private fun write(path: Path, bytes: ByteArray) {
        SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
    }

    private inline fun withDirectory(block: (Path) -> Unit) {
        val directory = Path(".audiokodec-io-test-${Random.nextLong().toULong().toString(16)}")
        SystemFileSystem.createDirectories(directory, mustCreate = true)
        try {
            block(directory)
        } finally {
            remove(directory)
        }
    }

    private fun remove(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            for (child in SystemFileSystem.list(path)) remove(child)
        }
        SystemFileSystem.delete(path, mustExist = false)
    }
}

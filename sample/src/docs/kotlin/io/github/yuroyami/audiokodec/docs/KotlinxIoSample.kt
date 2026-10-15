package io.github.yuroyami.audiokodec.docs

// --8<-- [start:imports]
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.EncodeTarget
import io.github.yuroyami.audiokodec.asPcmSource
import io.github.yuroyami.audiokodec.io.openAudioSource
import io.github.yuroyami.audiokodec.io.readAudioSource
import io.github.yuroyami.audiokodec.io.writeAudioAtomically
import io.github.yuroyami.audiokodec.tag.TagField
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlin.math.abs
// --8<-- [end:imports]
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

// --8<-- [start:describe]
/** The format and title of the file at [path], read without decoding any audio. */
fun describeFile(path: Path): String =
    path.openAudioSource().use { source ->
        // probe and readTags read from the source but do not close it; use { } does.
        val info = AudioKodec.probe(source)
        val title = AudioKodec.readTags(source)[TagField.TITLE]
        "$title: ${info.codec.displayName}, ${info.durationSeconds} s"
    }
// --8<-- [end:describe]

// --8<-- [start:decode]
/** The highest absolute sample in the file at [path], streamed so that any length fits in memory. */
fun peakOf(path: Path): Float {
    var peak = 0f
    // The decoder owns the source and closes it, file handle included.
    AudioKodec.open(path.openAudioSource(), DecodeOptions(reuseBuffers = true)).use { decoder ->
        while (true) {
            val block = decoder.nextFrame() ?: break
            for (i in 0 until block.sampleCount) peak = maxOf(peak, abs(block.samples[i]))
        }
    }
    return peak
}
// --8<-- [end:decode]

// --8<-- [start:write]
/** Writes [samples] to [path] as FLAC. The file is replaced only once the encode has succeeded. */
fun writeFlac(path: Path, samples: FloatArray, format: AudioFormat) {
    path.writeAudioAtomically { sink ->
        AudioKodec.encode(samples.asPcmSource(format), EncodeTarget.flac(16), sink)
    }
}
// --8<-- [end:write]

// --8<-- [start:retitle]
/** Sets the title of the file at [path], copying its audio unchanged. */
fun retitle(path: Path, title: String) {
    path.writeAudioAtomically { sink ->
        // The source is closed before the new file replaces the old one.
        path.openAudioSource().use { source ->
            val tags = AudioKodec.readTags(source).edit { set(TagField.TITLE, title) }
            AudioKodec.writeTags(source, tags, sink)
        }
    }
}
// --8<-- [end:retitle]

class KotlinxIoSample {
    private val directory = Path(Fixtures.directory.path)

    @Test
    fun writeReadAndRetitleAFile() {
        val path = Path(directory, "song.flac")
        writeFlac(path, Fixtures.samples, Fixtures.format)
        assertEquals("null: FLAC, 2.0 s", describeFile(path))
        retitle(path, "Retitled")
        assertEquals("Retitled: FLAC, 2.0 s", describeFile(path))
        assertEquals(Fixtures.samples.maxOf { abs(it) }, peakOf(path), 1f / 32768)
        // Nothing but the file itself is left in the directory.
        assertEquals(listOf("song.flac"), SystemFileSystem.list(directory).map { it.name }.filter { it.startsWith("song") })
    }

    @Test
    fun bytesFromAnywhereGoThroughABuffer() {
        val bytes = Fixtures.flac

        // --8<-- [start:buffer]
        // In a browser there is no file system: put the downloaded bytes in a Buffer.
        val buffer = Buffer().apply { write(bytes) }
        val audio = AudioKodec.decode(buffer.readAudioSource())
        // --8<-- [end:buffer]

        assertContentEquals(AudioKodec.decode(bytes).samples, audio.samples)
    }
}

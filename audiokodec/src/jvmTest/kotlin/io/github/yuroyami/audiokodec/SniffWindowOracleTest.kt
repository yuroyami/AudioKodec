package io.github.yuroyami.audiokodec

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Public executable interfaces provide the tags, encoded audio and independent format names. */
class SniffWindowOracleTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "audiokodec-sniff-window").also { it.mkdirs() }

    @Test
    fun realFilesBehindIndependentTagsRequireVisibleContainerHeaders() {
        val python = python()
        for ((extension, codec, expected) in listOf(
            Triple("wav", "pcm_s16le", AudioContainer.WAV), Triple("flac", "flac", AudioContainer.FLAC),
            Triple("aiff", "pcm_s16be", AudioContainer.AIFF), Triple("mp3", "libmp3lame", AudioContainer.MPEG_AUDIO),
            Triple("ogg", "libvorbis", AudioContainer.OGG), Triple("caf", "pcm_s16le", AudioContainer.CAF),
        )) {
            val original = File(directory, "source.$extension")
            run(listOf(tool("ffmpeg"), "-v", "error", "-y", "-f", "lavfi", "-i",
                "sine=frequency=440:sample_rate=44100:duration=0.1", "-c:a", codec, original.path))
            val originalSamples = AudioKodec.decode(original.readBytes()).samples
            val independentSamples = decode(original)
            for (major in 3..4) {
                for (padding in listOf(1000, 70_000)) {
                    val tag = tag(python, major, padding)
                    val file = File(directory, "tagged-$major-$padding.$extension").also { it.writeBytes(tag + original.readBytes()) }
                    val bytes = file.readBytes()
                    assertEquals(extension, run(listOf(tool("ffprobe"), "-v", "error", "-show_entries", "format=format_name",
                        "-of", "default=noprint_wrappers=1:nokey=1", file.path)).trim())
                    assertEquals(expected, AudioKodec.sniff(bytes))
                    if (padding >= AudioKodec.SNIFF_HEADER_BYTES) assertNull(AudioKodec.sniff(bytes.copyOf(AudioKodec.SNIFF_HEADER_BYTES)))
                    else assertEquals(expected, AudioKodec.sniff(bytes.copyOf(minOf(bytes.size, AudioKodec.SNIFF_HEADER_BYTES))))
                    assertEquals(expected, AudioKodec.probe(bytes).container)
                    assertContentEquals(originalSamples, AudioKodec.decode(bytes).samples)
                    assertContentEquals(independentSamples, decode(file))
                }
            }
        }
    }

    @Test
    fun independentStandaloneTagsDoNotEstablishAnAudioFormat() {
        val python = python()
        for (major in 3..4) {
            val bytes = tag(python, major, 70_000)
            val standalone = File(directory, "standalone-$major.id3").also { it.writeBytes(bytes) }
            assertNull(AudioKodec.sniff(bytes))
            assertNull(AudioKodec.sniff(bytes.copyOf(AudioKodec.SNIFF_HEADER_BYTES)))
            val process = ProcessBuilder(tool("ffprobe"), "-v", "error", "-show_entries", "format=format_name", standalone.path)
                .redirectErrorStream(true).start()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.exitValue() != 0, "standalone metadata unexpectedly identified audio: $output")
        }
    }

    private fun tag(python: String, major: Int, padding: Int): ByteArray {
        val file = File(directory, "prefix-$major-$padding.id3")
        run(listOf(python, "-c", "from io import BytesIO; from mutagen.id3 import ID3,TIT2; import sys; " +
            "p,v,n=sys.argv[1:]; t=ID3(); t.add(TIT2(encoding=1,text=['Hidden 日本語'])); f=BytesIO(); " +
            "t.save(f,v2_version=int(v),padding=lambda info:int(n)); open(p,'wb').write(f.getvalue())", file.path, "$major", "$padding"))
        return file.readBytes()
    }

    private fun decode(file: File): ByteArray {
        val output = File(directory, "${file.name}.pcm")
        // Include MPEG priming and padding so metadata placement cannot change the decoded interval.
        run(listOf(tool("ffmpeg"), "-v", "error", "-y", "-flags2", "+skip_manual", "-i", file.path,
            "-map", "0:a:0", "-f", "f32le", output.path))
        return output.readBytes()
    }

    private fun python(): String {
        val configured = System.getenv("AUDIOKODEC_MUTAGEN_PYTHON")
        val python = configured ?: tool("python3")
        val process = ProcessBuilder(python, "-c", "import mutagen.id3").redirectErrorStream(true).start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        if (configured != null) assertEquals(0, process.exitValue(), output)
        else assumeTrue("Mutagen is required for sniff window checks: $output", process.exitValue() == 0)
        return python
    }

    private fun tool(name: String): String {
        val file = System.getenv("PATH")?.split(File.pathSeparator)?.map { File(it, name) }?.firstOrNull { it.canExecute() }
        assumeTrue("$name is required for sniff window checks", file != null)
        return file!!.path
    }

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "${command.first()} did not finish")
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.exitValue(), output)
        return output
    }
}

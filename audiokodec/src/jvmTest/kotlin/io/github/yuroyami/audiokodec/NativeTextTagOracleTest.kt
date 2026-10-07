package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs public tag-reader interfaces without using their implementation source. */
class NativeTextTagOracleTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "audiokodec-native-text-oracle").also { it.mkdirs() }

    @Test
    fun exiftoolReadsEveryNativeCommentBeforeAndAfterRepeatedEdits() {
        val ffmpeg = tool("ffmpeg")
        val exiftool = tool("exiftool")
        for (extension in listOf("wav", "rf64", "aiff", "aifc")) {
            val source = independentAudio(ffmpeg, extension)
            val original = NativeTextListFixtures.withComments(source.readBytes())
            source.writeBytes(original)
            val native = if (extension == "wav" || extension == "rf64") TagFormat.RIFF_INFO else TagFormat.AIFF_TEXT
            val key = if (native == TagFormat.RIFF_INFO) "-RIFF:Comment" else "-AIFF:Annotation"
            assertEquals(NativeTextListFixtures.comments, lines(exiftool, key, source))
            assertEquals(NativeTextListFixtures.comments, AudioKodec.readTags(original).all(TagField.COMMENT))
            for (formats in listOf(setOf(native), setOf(native, TagFormat.ID3V2_4))) {
                var bytes = original
                val output = File(directory, "edited-${formats.size}.$extension")
                repeat(3) { edit ->
                    bytes = AudioKodec.writeTags(bytes, AudioKodec.readTags(bytes).edit { set(TagField.TITLE, "Edit $edit") },
                        TagWriteOptions(formats = formats, padding = 0))
                    output.writeBytes(bytes)
                    assertEquals(NativeTextListFixtures.comments, lines(exiftool, key, output))
                    assertEquals(NativeTextListFixtures.comments, AudioKodec.readTags(bytes).all(TagField.COMMENT))
                    assertContentEquals(NativeTextListFixtures.payload(original), NativeTextListFixtures.payload(bytes))
                }
                bytes = AudioKodec.writeTags(bytes, AudioKodec.readTags(bytes).edit { remove(TagField.COMMENT) },
                    TagWriteOptions(formats = formats, padding = 0))
                output.writeBytes(bytes)
                assertEquals(emptyList(), lines(exiftool, key, output))
                assertTrue(AudioKodec.readTags(bytes).all(TagField.COMMENT).isEmpty())
                assertContentEquals(NativeTextListFixtures.payload(original), NativeTextListFixtures.payload(bytes))
            }
        }
        for (target in listOf(EncodeTarget.wav(), EncodeTarget.aiff(), EncodeTarget.aiffFloat())) {
            val extension = if (target.container == AudioContainer.WAV) "wav" else "aiff"
            val source = AudioKodec.encode(NativeTextListFixtures.samples, NativeTextListFixtures.format, target)
            val bytes = AudioKodec.encode(NativeTextListFixtures.samples, NativeTextListFixtures.format, target,
                TagBundle(mapOf(TagField.COMMENT to NativeTextListFixtures.comments)))
            val file = File(directory, "fresh-${target.codec}.$extension").also { it.writeBytes(bytes) }
            val key = if (extension == "wav") "-RIFF:Comment" else "-AIFF:Annotation"
            assertEquals(NativeTextListFixtures.comments, lines(exiftool, key, file))
            assertContentEquals(NativeTextListFixtures.payload(source), NativeTextListFixtures.payload(bytes))
        }
    }

    @Test
    fun mutagenKeepsAiffSingletonListsWhileExiftoolSeesOnlyOneNativeChunk() {
        val ffmpeg = tool("ffmpeg")
        val exiftool = tool("exiftool")
        val configured = System.getenv("AUDIOKODEC_MUTAGEN_PYTHON")
        val python = configured ?: tool("python3")
        val check = ProcessBuilder(python, "-c", "import mutagen.aiff").redirectErrorStream(true).start()
        assertTrue(check.waitFor(30, TimeUnit.SECONDS))
        val output = check.inputStream.bufferedReader().readText()
        if (configured != null) assertEquals(0, check.exitValue(), output)
        else assumeTrue("Mutagen is required for AIFF singleton lists: $output", check.exitValue() == 0)
        val values = listOf("first", "Second 日本語", "third")
        val fields = listOf(TagField.TITLE, TagField.ARTIST, TagField.COPYRIGHT).associateWith { values }
        for (extension in listOf("aiff", "aifc")) {
            val source = independentAudio(ffmpeg, extension)
            val audio = NativeTextListFixtures.payload(source.readBytes())
            for (fromMutagen in listOf(false, true)) {
                val file = File(directory, "singletons-$fromMutagen.$extension").also { it.writeBytes(source.readBytes()) }
                if (fromMutagen) run(listOf(python, "-c", SINGLETONS, file.path, "write"))
                else file.writeBytes(AudioKodec.writeTags(file.readBytes(), TagBundle(fields)))
                repeat(3) {
                    val before = file.readBytes()
                    val tags = AudioKodec.readTags(before)
                    assertEquals(fields, tags.fields)
                    file.writeBytes(AudioKodec.writeTags(before, tags))
                    run(listOf(python, "-c", SINGLETONS, file.path, "check"))
                    for (key in listOf("-AIFF:Name", "-AIFF:Author", "-AIFF:Copyright")) {
                        assertEquals(listOf("first"), lines(exiftool, key, file))
                    }
                    assertContentEquals(audio, NativeTextListFixtures.payload(file.readBytes()))
                }
            }
        }
    }

    private fun independentAudio(ffmpeg: String, extension: String): File {
        val file = File(directory, "source.$extension")
        val codec = when (extension) { "wav", "rf64" -> "pcm_s16le"; "aifc" -> "pcm_f32be"; else -> "pcm_s16be" }
        val format = if (extension == "wav" || extension == "rf64") "wav" else "aiff"
        val rf64 = if (extension == "rf64") listOf("-rf64", "always") else emptyList()
        run(listOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
            "sine=frequency=400:sample_rate=8000:duration=0.01", "-c:a", codec, "-f", format) + rf64 + file.path)
        return file
    }

    private fun lines(exiftool: String, key: String, file: File): List<String> =
        run(listOf(exiftool, "-a", "-s3", key, file.path)).lineSequence().filter { it.isNotEmpty() }.toList()

    private fun tool(name: String): String {
        val found = System.getenv("PATH")?.split(File.pathSeparator)?.map { File(it, name) }?.firstOrNull { it.canExecute() }
        assumeTrue("$name is required for native text metadata checks", found != null)
        return found!!.path
    }

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "${command.first()} did not finish")
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.exitValue(), output)
        return output
    }

    companion object {
        private val SINGLETONS = """
import sys
from mutagen.aiff import AIFF
from mutagen.id3 import TIT2, TPE1, TCOP
file, mode = sys.argv[1:]
audio = AIFF(file)
values = ['first', 'Second 日本語', 'third']
if mode == 'write':
    if audio.tags is None:
        audio.add_tags()
    for cls in (TIT2, TPE1, TCOP):
        audio.tags.add(cls(encoding=3, text=values))
    audio.save(v2_version=4)
else:
    for key in ('TIT2', 'TPE1', 'TCOP'):
        frames = audio.tags.getall(key)
        assert len(frames) == 1, (key, frames)
        assert list(frames[0].text) == values, (key, frames[0].text)
""".trimIndent()
    }
}

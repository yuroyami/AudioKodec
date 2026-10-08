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

/** Public Mutagen APIs check date meaning without using implementation source. */
class DateIdentityOracleTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "audiokodec-date-identity").also { it.mkdirs() }

    @Test
    fun independentId3DatesKeepTheirMeaningAcrossEditsAndLegacyOutput() {
        val python = python()
        val source = audio("mp3")
        for (version in 3..4) {
            val fields = mapOf(
                TagField.DATE to listOf(if (version == 3) "2000" else "2000-01-02T03:04:05"),
                TagField.RELEASE_DATE to listOf("2026-10-06T12:34:56"),
                TagField.ORIGINAL_DATE to listOf("1990"),
            )
            val options = TagWriteOptions(id3Version = version, padding = 0)
            for (fromMutagen in listOf(false, true)) {
                val file = File(directory, "dates-$version-$fromMutagen.mp3").also { it.writeBytes(source.readBytes()) }
                if (fromMutagen) run(listOf(python, "-c", ID3_DATES, file.path, "write", "$version", "none"))
                else file.writeBytes(AudioKodec.writeTags(file.readBytes(), TagBundle(fields), options))
                val audio = decode(file)
                repeat(3) {
                    val read = AudioKodec.readTags(file.readBytes())
                    assertEquals(fields, read.fields)
                    file.writeBytes(AudioKodec.writeTags(file.readBytes(), read, options))
                    run(listOf(python, "-c", ID3_DATES, file.path, "check", "$version", "none"))
                    assertContentEquals(audio, decode(file))
                    assertContentEquals(mpegPayload(source.readBytes()), mpegPayload(file.readBytes()))
                }
                val original = file.readBytes()
                for (field in fields.keys) {
                    file.writeBytes(AudioKodec.writeTags(original, AudioKodec.readTags(original).edit { remove(field) }, options))
                    run(listOf(python, "-c", ID3_DATES, file.path, "check", "$version", field.name))
                    assertEquals(fields - field, AudioKodec.readTags(file.readBytes()).fields)
                    assertContentEquals(audio, decode(file))
                    assertContentEquals(mpegPayload(source.readBytes()), mpegPayload(file.readBytes()))
                }
            }
        }
    }

    @Test
    fun flacAndOpusReleaseDatesAgreeInBothDirections() {
        val python = python()
        val fields = mapOf(TagField.DATE to listOf("2000-01-02"), TagField.RELEASE_DATE to listOf("2026-10-06"))
        for (kind in listOf("flac", "opus")) {
            val source = audio(kind)
            for (fromMutagen in listOf(false, true)) {
                val file = File(directory, "dates-$kind-$fromMutagen.$kind").also { it.writeBytes(source.readBytes()) }
                if (fromMutagen) run(listOf(python, "-c", NATIVE_DATES, file.path, kind, "write"))
                else file.writeBytes(AudioKodec.writeTags(file.readBytes(), TagBundle(fields)))
                val before = file.readBytes()
                val samples = decode(file)
                repeat(3) {
                    val read = AudioKodec.readTags(file.readBytes())
                    assertEquals(fields, read.fields)
                    file.writeBytes(AudioKodec.writeTags(file.readBytes(), read))
                    run(listOf(python, "-c", NATIVE_DATES, file.path, kind, "check"))
                    assertContentEquals(samples, decode(file))
                    if (kind == "flac") assertContentEquals(TagStressFixtures.flacAudio(before), TagStressFixtures.flacAudio(file.readBytes()))
                }
            }
        }
    }

    private fun audio(kind: String): File = File(directory, "source.$kind").also { file ->
        val codec = when (kind) { "mp3" -> "libmp3lame"; "opus" -> "libopus"; else -> "flac" }
        run(listOf(tool("ffmpeg"), "-v", "error", "-y", "-f", "lavfi", "-i",
            "sine=frequency=440:sample_rate=48000:duration=0.1", "-c:a", codec, file.path))
    }

    private fun decode(file: File): ByteArray {
        val output = File(directory, "${file.name}.pcm")
        // Compare MP3 priming and padding too, so ffmpeg's automatic trimming
        // does not change the sample interval when the metadata layout changes.
        val flags = if (file.extension == "mp3") listOf("-flags2", "+skip_manual") else emptyList()
        run(listOf(tool("ffmpeg"), "-v", "error", "-y") + flags +
            listOf("-i", file.path, "-map", "0:a:0", "-f", "f32le", output.path))
        return output.readBytes()
    }

    private fun mpegPayload(bytes: ByteArray): ByteArray {
        assertEquals("ID3", bytes.decodeToString(0, 3))
        var length = 0
        for (index in 6..9) {
            val value = bytes[index].toInt() and 255
            assertTrue(value < 128)
            length = (length shl 7) or value
        }
        val start = 10 + length
        assertEquals(255, bytes[start].toInt() and 255)
        return bytes.copyOfRange(start, bytes.size)
    }

    private fun python(): String {
        val configured = System.getenv("AUDIOKODEC_MUTAGEN_PYTHON")
        val python = configured ?: tool("python3")
        val process = ProcessBuilder(python, "-c", "import mutagen.id3, mutagen.flac, mutagen.oggopus")
            .redirectErrorStream(true).oracleStart()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        if (configured != null) assertEquals(0, process.exitValue(), output)
        else assumeTrue("Mutagen is required for date identity checks: $output", process.exitValue() == 0)
        return python
    }

    private fun tool(name: String): String {
        val file = System.getenv("PATH")?.split(File.pathSeparator)?.map { File(it, name) }?.firstOrNull { it.canExecute() }
        assumeTrue("$name is required for date identity checks", file != null)
        return file!!.path
    }

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "${command.first()} did not finish")
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.exitValue(), output)
        return output
    }

    companion object {
        private val ID3_DATES = """
import sys
from mutagen.id3 import ID3, TDRC, TDRL, TDOR, TYER, TORY, TXXX
path, mode, version, removed = sys.argv[1:]
version = int(version)
recording = '2000' if version == 3 else '2000-01-02T03:04:05'
release = '2026-10-06T12:34:56'
if mode == 'write':
    tag = ID3()
    if version == 3:
        tag.add(TYER(encoding=0, text=[recording]))
        tag.add(TORY(encoding=0, text=['1990']))
        tag.add(TXXX(encoding=1, desc='RELEASEDATE', text=[release]))
    else:
        tag.add(TDRC(encoding=3, text=[recording]))
        tag.add(TDOR(encoding=3, text=['1990']))
        tag.add(TDRL(encoding=3, text=[release]))
    tag.save(path, v2_version=version)
else:
    tag = ID3(path, translate=False)
    names = [('DATE', 'TYER' if version == 3 else 'TDRC', recording),
             ('ORIGINAL_DATE', 'TORY' if version == 3 else 'TDOR', '1990'),
             ('RELEASE_DATE', 'TXXX:RELEASEDATE' if version == 3 else 'TDRL', release)]
    for field, key, value in names:
        actual = [str(x) for x in tag[key].text] if key in tag else []
        # Timestamp objects display a space between date and time.
        if key in ('TDRC', 'TDRL', 'TDOR'): actual = [x.replace(' ', 'T') for x in actual]
        assert actual == ([] if removed == field else [value]), (field, actual)
    if version == 3: assert not tag.getall('TDRL')
    else: assert not tag.getall('TXXX:RELEASEDATE')
""".trimIndent()
        private val NATIVE_DATES = """
import sys
from mutagen.flac import FLAC
from mutagen.oggopus import OggOpus
path, kind, mode = sys.argv[1:]
tag = (FLAC if kind == 'flac' else OggOpus)(path)
fields = {'DATE': ['2000-01-02'], 'RELEASEDATE': ['2026-10-06']}
if mode == 'write':
    tag.clear()
    for key, values in fields.items(): tag[key] = values
    tag.save()
else:
    for key, values in fields.items(): assert list(tag[key]) == values, (key, tag[key])
""".trimIndent()
    }
}

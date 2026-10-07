package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Uses public Mutagen APIs and FFprobe; no implementation source is read. */
class DateConversionOracleTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "audiokodec-date-conversion").also { it.mkdirs() }

    @Test
    fun supportedDatesAgreeWithIndependentReadersAndWriters() {
        val python = python()
        val source = audio()
        val samples = decode(source)
        val payload = mpegPayload(source.readBytes())
        val values = listOf("2020", "2020-02-29", "2000-02-29T00:00", "2026-12-31T23:59")
        for ((index, value) in values.withIndex()) {
            val fields = mapOf(TagField.DATE to listOf(value), TagField.ORIGINAL_DATE to listOf("1990"),
                TagField.RELEASE_DATE to listOf("2026-10-06T12:34:56"))
            for (fromMutagen in listOf(false, true)) {
                val file = File(directory, "date-$index-$fromMutagen.mp3").also { it.writeBytes(source.readBytes()) }
                if (fromMutagen) run(listOf(python, "-c", DATES, file.path, "write", "3", value))
                else file.writeBytes(AudioKodec.writeTags(file.readBytes(), TagBundle(fields), TagWriteOptions(id3Version = 3)))
                assertEquals(fields, AudioKodec.readTags(file.readBytes()).fields)
                for (version in listOf(3, 4, 3, 4)) {
                    val tags = AudioKodec.readTags(file.readBytes()).edit { set(TagField.TITLE, "Unrelated edit") }
                    file.writeBytes(AudioKodec.writeTags(file.readBytes(), tags, TagWriteOptions(id3Version = version, padding = 0)))
                    run(listOf(python, "-c", DATES, file.path, "check", "$version", value))
                    // FFprobe exposes TDRC and TDRL under the same date key.
                    // Mutagen checks their identities; compare every FFprobe value here.
                    assertEquals((if (version == 4) listOf(value, "2026-10-06T12:34:56") else listOf(value)).sorted(),
                        probeDates(file).sorted())
                    assertEquals(fields, AudioKodec.readTags(file.readBytes()).fields - TagField.TITLE)
                    assertContentEquals(payload, mpegPayload(file.readBytes()))
                    assertContentEquals(samples, decode(file))
                }
            }
        }
    }

    @Test
    fun versionTwoGroupsUpgradeWithoutInventingSeconds() {
        val python = python()
        val source = audio()
        val payload = mpegPayload(source.readBytes())
        val samples = decode(source)
        val tag = DateConversionFixtures.stream(2,
            listOf("TYE" to "2020", "TDA" to "0102", "TIM" to "1230", "TOR" to "1990"), 1)
        val file = File(directory, "version-two.mp3").also { it.writeBytes(tag.copyOf(tag.size - 32) + payload) }
        assertEquals(listOf("2020-02-01T12:30"), probeDates(file))
        run(listOf(python, "-c", "from mutagen.id3 import ID3; import sys; t=ID3(sys.argv[1]); " +
            "v=t['TDRC'].text[0]; assert (v.year,v.month,v.day,v.hour,v.minute)==(2020,2,1,12,30)", file.path))
        for (version in listOf(4, 3, 4)) {
            val tags = AudioKodec.readTags(file.readBytes())
            assertEquals(listOf("2020-02-01T12:30"), tags.all(TagField.DATE))
            file.writeBytes(AudioKodec.writeTags(file.readBytes(), tags, TagWriteOptions(id3Version = version, padding = 0)))
            run(listOf(python, "-c", DATES, file.path, "check-without-release", "$version", "2020-02-01T12:30"))
            assertEquals(listOf("2020-02-01T12:30"), probeDates(file))
            assertContentEquals(payload, mpegPayload(file.readBytes()))
            assertContentEquals(samples, decode(file))
        }
    }

    @Test
    fun independentFullPrecisionTagsRefuseLossyLegacyOutput() {
        val python = python()
        val source = audio()
        val file = File(directory, "precision.mp3")
        for ((field, value) in listOf(TagField.DATE to "2020-02", TagField.DATE to "2020-02-01T12",
            TagField.DATE to "2020-02-01T12:30:00", TagField.ORIGINAL_DATE to "1990-02-03")) {
            file.writeBytes(source.readBytes())
            run(listOf(python, "-c", "from mutagen.id3 import ID3,TDRC,TDOR; import sys; " +
                "p,f,v=sys.argv[1:]; t=ID3(); t.add((TDRC if f=='DATE' else TDOR)(encoding=3,text=[v])); t.save(p)",
                file.path, field.name, value))
            val before = file.readBytes()
            val tags = AudioKodec.readTags(before)
            assertEquals(listOf(value), tags.all(field))
            val sink = ByteArrayAudioSink()
            val error = assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags(before.asAudioSource(), tags, sink, TagWriteOptions(id3Version = 3))
            }
            assertTrue(error.message.orEmpty().contains(field.name))
            assertEquals(0L, sink.position)
            assertContentEquals(before, file.readBytes())
        }
    }

    private fun audio(): File = File(directory, "source.mp3").also { file ->
        run(listOf(tool("ffmpeg"), "-v", "error", "-y", "-f", "lavfi", "-i",
            "sine=frequency=440:sample_rate=44100:duration=0.1", "-c:a", "libmp3lame", "-write_id3v1", "0", file.path))
    }

    private fun probeDates(file: File): List<String> = run(listOf(tool("ffprobe"), "-v", "error", "-show_entries",
        "format_tags", "-of", "default=noprint_wrappers=1", file.path)).lineSequence()
        .filter { it.startsWith("TAG:date=") }.map { it.removePrefix("TAG:date=").replace(' ', 'T') }.toList()

    private fun decode(file: File): ByteArray {
        val output = File(directory, "${file.name}.pcm")
        // Include MPEG priming and padding, so metadata placement cannot change the interval.
        run(listOf(tool("ffmpeg"), "-v", "error", "-y", "-flags2", "+skip_manual", "-i", file.path,
            "-map", "0:a:0", "-f", "f32le", output.path))
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
        assertEquals(255, bytes[10 + length].toInt() and 255)
        return bytes.copyOfRange(10 + length, bytes.size)
    }

    private fun python(): String {
        val configured = System.getenv("AUDIOKODEC_MUTAGEN_PYTHON")
        val python = configured ?: tool("python3")
        val process = ProcessBuilder(python, "-c", "import mutagen.id3").redirectErrorStream(true).start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        if (configured != null) assertEquals(0, process.exitValue(), output)
        else assumeTrue("Mutagen is required for date conversion checks: $output", process.exitValue() == 0)
        return python
    }

    private fun tool(name: String): String {
        val file = System.getenv("PATH")?.split(File.pathSeparator)?.map { File(it, name) }?.firstOrNull { it.canExecute() }
        assumeTrue("$name is required for date conversion checks", file != null)
        return file!!.path
    }

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "${command.first()} did not finish")
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.exitValue(), output)
        return output
    }

    companion object {
        private val DATES = """
import sys
from mutagen.id3 import ID3, TYER, TDAT, TIME, TORY, TXXX
path, mode, version, value = sys.argv[1:]
version = int(version)
release = '2026-10-06T12:34:56'
if mode == 'write':
    tag = ID3()
    tag.add(TYER(encoding=0, text=[value[:4]]))
    if len(value) >= 10: tag.add(TDAT(encoding=0, text=[value[8:10] + value[5:7]]))
    if len(value) >= 16: tag.add(TIME(encoding=0, text=[value[11:13] + value[14:16]]))
    tag.add(TORY(encoding=0, text=['1990']))
    tag.add(TXXX(encoding=1, desc='RELEASEDATE', text=[release]))
    tag.save(path, v2_version=3)
else:
    tag = ID3(path, translate=False)
    def values(key):
        return [str(x).replace(' ', 'T') for x in tag[key].text] if key in tag else []
    assert tag.version[1] == version
    if version == 3:
        assert values('TYER') == [value[:4]]
        assert values('TDAT') == ([value[8:10] + value[5:7]] if len(value) >= 10 else [])
        assert values('TIME') == ([value[11:13] + value[14:16]] if len(value) >= 16 else [])
        assert values('TORY') == ['1990']
        assert not values('TDRC') and not values('TDOR')
        release_key = 'TXXX:RELEASEDATE'
    else:
        assert values('TDRC') == [value], values('TDRC')
        assert values('TDOR') == ['1990']
        for key in ('TYER','TDAT','TIME','TRDA','TORY'): assert not values(key), key
        release_key = 'TDRL'
    assert values(release_key) == ([] if mode == 'check-without-release' else [release])
""".trimIndent()
    }
}

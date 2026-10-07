package io.github.yuroyami.audiokodec

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

/** Mutagen's public chapter API writes and independently reads every embedded identity. */
class ChapterSubframeOracleTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "audiokodec-chapter-subframes").also { it.mkdirs() }

    @Test
    fun independentChaptersSurviveRepeatedEditsAndVersionConversions() {
        val python = python()
        val source = audio()
        val payload = mpegPayload(source.readBytes())
        val samples = decode(source)
        for (version in 3..4) {
            val file = File(directory, "chapter-$version.mp3").also { it.writeBytes(source.readBytes()) }
            run(listOf(python, "-c", CHAPTERS, file.path, "write", "$version", "original"))
            val expected = ChapterSubframeFixtures.nested(file.readBytes())
            repeat(3) { edit ->
                val tags = AudioKodec.readTags(file.readBytes()).edit { set(TagField.TITLE, "Unrelated $edit") }
                file.writeBytes(AudioKodec.writeTags(file.readBytes(), tags, TagWriteOptions(id3Version = version, padding = 0)))
                assertContentEquals(expected, ChapterSubframeFixtures.nested(file.readBytes()))
                run(listOf(python, "-c", CHAPTERS, file.path, "check", "$version", "original"))
            }
            for (target in listOf(4, 3, 4, 3)) {
                file.writeBytes(AudioKodec.writeTags(file.readBytes(), AudioKodec.readTags(file.readBytes()), TagWriteOptions(id3Version = target, padding = 0)))
                run(listOf(python, "-c", CHAPTERS, file.path, "check", "$target", "original"))
                assertContentEquals(payload, mpegPayload(file.readBytes()))
                assertContentEquals(samples, decode(file))
            }
        }
    }

    @Test
    fun copiedModelledChangesKeepIndependentExtraIdentities() {
        val python = python()
        val source = audio()
        val payload = mpegPayload(source.readBytes())
        val samples = decode(source)
        for (version in 3..4) {
            val file = File(directory, "chapter-edit-$version.mp3").also { it.writeBytes(source.readBytes()) }
            run(listOf(python, "-c", CHAPTERS, file.path, "write", "$version", "original"))
            val tags = AudioKodec.readTags(file.readBytes())
            val chapter = tags.chapters.single().copy(id = "renamed", startMillis = 10, endMillis = 80, title = "Edited 日本語", pictures = emptyList())
            file.writeBytes(AudioKodec.writeTags(file.readBytes(), tags.edit { setChapters(listOf(chapter)) }, TagWriteOptions(id3Version = version, padding = 0)))
            run(listOf(python, "-c", CHAPTERS, file.path, "check", "$version", "edited"))
            assertContentEquals(payload, mpegPayload(file.readBytes()))
            assertContentEquals(samples, decode(file))
        }
    }

    @Test
    fun independentListsAndDatePrecisionRefuseLossyOutput() {
        val python = python()
        val source = audio()
        val file = File(directory, "chapter-refusal.mp3")
        for ((id, constructor) in listOf("TXXX" to "TXXX(encoding=3,desc='Extra',text=['first','second'])",
            "TDRC" to "TDRC(encoding=3,text=['2020-02-01T12:30:00'])")) {
            file.writeBytes(source.readBytes())
            run(listOf(python, "-c", "from mutagen.id3 import ID3,CHAP,TIT2,TXXX,TDRC; import sys; t=ID3(); " +
                "t.add(CHAP(element_id='c1',start_time=0,end_time=100,sub_frames=[TIT2(encoding=0,text=['Chapter']),$constructor])); t.save(sys.argv[1])", file.path))
            val before = file.readBytes()
            val sink = ByteArrayAudioSink()
            val error = assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags(before.asAudioSource(), AudioKodec.readTags(before), sink, TagWriteOptions(id3Version = 3))
            }
            assertTrue(error.message.orEmpty().contains("c1"))
            assertTrue(error.message.orEmpty().contains(id))
            assertEquals(0L, sink.position)
            assertContentEquals(before, file.readBytes())
        }
    }

    private fun audio(): File = File(directory, "source.mp3").also { file ->
        run(listOf(tool("ffmpeg"), "-v", "error", "-y", "-f", "lavfi", "-i",
            "sine=frequency=440:sample_rate=44100:duration=0.1", "-c:a", "libmp3lame", "-write_id3v1", "0", file.path))
    }

    private fun decode(file: File): ByteArray {
        val output = File(directory, "${file.name}.pcm")
        // Include priming and padding so metadata placement cannot change the decoded interval.
        run(listOf(tool("ffmpeg"), "-v", "error", "-y", "-flags2", "+skip_manual", "-i", file.path,
            "-map", "0:a:0", "-f", "f32le", output.path))
        return output.readBytes()
    }

    private fun mpegPayload(bytes: ByteArray): ByteArray = bytes.copyOfRange(ChapterSubframeFixtures.tagEnd(bytes), bytes.size)

    private fun python(): String {
        val configured = System.getenv("AUDIOKODEC_MUTAGEN_PYTHON")
        val python = configured ?: tool("python3")
        val process = ProcessBuilder(python, "-c", "import mutagen.id3").redirectErrorStream(true).start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        if (configured != null) assertEquals(0, process.exitValue(), output)
        else assumeTrue("Mutagen is required for chapter subframe checks: $output", process.exitValue() == 0)
        return python
    }

    private fun tool(name: String): String {
        val file = System.getenv("PATH")?.split(File.pathSeparator)?.map { File(it, name) }?.firstOrNull { it.canExecute() }
        assumeTrue("$name is required for chapter subframe checks", file != null)
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
        private val CHAPTERS = """
import sys
from mutagen.id3 import ID3, CHAP, TIT2, TIT3, WXXX, PRIV, COMM, USLT, TXXX, APIC, GEOB, USER
path, mode, version, state = sys.argv[1:]
version = int(version)
if mode == 'write':
    tag = ID3()
    tag.add(CHAP(element_id='c1',start_time=0,end_time=100,sub_frames=[
        TIT2(encoding=3,text=['Chapter']), TIT3(encoding=3,text=['Subtitle 日本語']),
        WXXX(encoding=3,desc='Link 日本語',url='https://example.org/chapter'),
        PRIV(owner='example',data=b'\x01\xff\xe0'), COMM(encoding=3,lang='eng',desc='Comment 日本語',text=['Comment value 日本語']),
        USLT(encoding=3,lang='eng',desc='Lyrics 日本語',text='Lyrics value 日本語'),
        TXXX(encoding=3,desc='Extra 日本語',text=['Value 日本語']),
        APIC(encoding=3,mime='image/png',type=3,desc='Cover 日本語',data=b'\x89PNG\r\n\x1a\n'),
        GEOB(encoding=3,mime='application/octet-stream',filename='File 日本語',desc='Object 日本語',data=b'\x00\xff\xe0'),
        USER(encoding=3,lang='eng',text='Terms 日本語')]))
    if version == 3: tag.update_to_v23()
    tag.save(path,v2_version=version)
else:
    tag = ID3(path,translate=False)
    assert tag.version[1] == version
    chapter = tag['CHAP:' + ('renamed' if state == 'edited' else 'c1')]
    assert (chapter.start_time,chapter.end_time) == ((10,80) if state == 'edited' else (0,100))
    nested = chapter.sub_frames
    assert nested['TIT2'].text == [('Edited 日本語' if state == 'edited' else 'Chapter')]
    assert nested['TIT3'].text == ['Subtitle 日本語']
    assert nested['WXXX:Link 日本語'].url == 'https://example.org/chapter'
    private = nested.getall('PRIV')
    assert len(private) == 1 and private[0].owner == 'example' and private[0].data == b'\x01\xff\xe0'
    comment = nested.getall('COMM')[0]
    assert comment.lang == 'eng' and comment.desc == 'Comment 日本語' and comment.text == ['Comment value 日本語']
    lyrics = nested.getall('USLT')[0]
    assert lyrics.lang == 'eng' and lyrics.desc == 'Lyrics 日本語' and lyrics.text == 'Lyrics value 日本語'
    assert nested['TXXX:Extra 日本語'].text == ['Value 日本語']
    pictures = nested.getall('APIC')
    assert len(pictures) == (0 if state == 'edited' else 1)
    if pictures:
        assert pictures[0].mime == 'image/png' and pictures[0].type == 3 and pictures[0].desc == 'Cover 日本語'
        assert pictures[0].data == b'\x89PNG\r\n\x1a\n'
    obj = nested.getall('GEOB')[0]
    assert obj.mime == 'application/octet-stream' and obj.filename == 'File 日本語' and obj.desc == 'Object 日本語'
    assert obj.data == b'\x00\xff\xe0'
    assert nested.getall('USER')[0].lang == 'eng' and nested.getall('USER')[0].text == 'Terms 日本語'
    assert len(nested) == (9 if state == 'edited' else 10), list(nested)
""".trimIndent()
    }
}

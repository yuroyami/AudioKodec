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

/** Executes Mutagen's public API as a tag oracle; no implementation source is used. */
class MutagenTagOracleTest {
    private val recording = "ebf79ba5-085e-48d2-9eb8-2d992fbf0f6d"
    private val second = "0383dadf-2a4e-4d10-a46a-e9e041da8eb3"
    private val identifiers = mapOf(
        TagField.MUSICBRAINZ_ARTIST_ID to "MusicBrainz Artist Id",
        TagField.MUSICBRAINZ_RELEASE_ID to "MusicBrainz Album Id",
        TagField.MUSICBRAINZ_RELEASE_ARTIST_ID to "MusicBrainz Album Artist Id",
        TagField.MUSICBRAINZ_RELEASE_GROUP_ID to "MusicBrainz Release Group Id",
        TagField.MUSICBRAINZ_TRACK_ID to "MusicBrainz Release Track Id",
    )

    private val python: String by lazy {
        val configured = System.getenv("AUDIOKODEC_MUTAGEN_PYTHON")
        val executable = configured ?: findOnPath("python3")
        assumeTrue("Python with Mutagen is required for the ID3 oracle", executable != null)
        val process = ProcessBuilder(executable!!, "-c", "import mutagen.id3").redirectErrorStream(true).start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "checking Mutagen did not finish")
        val output = process.inputStream.bufferedReader().readText()
        if (configured != null) assertEquals(0, process.exitValue(), output)
        else assumeTrue("Install Mutagen or set AUDIOKODEC_MUTAGEN_PYTHON: $output", process.exitValue() == 0)
        executable
    }

    @Test
    fun musicBrainzIdentifiersAgreeInBothDirectionsAndAfterARewrite() {
        val python = python
        for (version in 3..4) {
            val file = plainMp3("musicbrainz-$version")
            val originalAudio = file.readBytes()
            run(listOf(python, "-c", WRITE_IDENTIFIERS, file.path, version.toString()))
            val read = AudioKodec.readTags(file.readBytes())
            assertEquals(recording, read[TagField.MUSICBRAINZ_RECORDING_ID])
            for (field in identifiers.keys) assertEquals(second, read[field])
            val rewritten = AudioKodec.writeTags(file.readBytes(), read.edit { set(TagField.TITLE, "Edited 日本語") }, TagWriteOptions(id3Version = version))
            assertContentEquals(originalAudio, rewritten.takeLast(originalAudio.size).toByteArray())
            file.writeBytes(rewritten)
            run(listOf(python, "-c", CHECK_IDENTIFIERS, file.path))

            val fields = identifiers.keys.associateWith { listOf(second) } +
                (TagField.MUSICBRAINZ_RECORDING_ID to listOf(recording))
            file.writeBytes(AudioKodec.writeTags(file.readBytes(), TagBundle(fields), TagWriteOptions(id3Version = version)))
            run(listOf(python, "-c", CHECK_IDENTIFIERS, file.path))
            assertContentEquals(originalAudio, file.readBytes().takeLast(originalAudio.size).toByteArray())
        }
    }

    @Test
    fun multiValueFramesHaveOneIdentityAndKeepEverySupportedValue() {
        val python = python
        val artists = listOf("Alpha", "Björk 日本語")
        for (version in 3..4) {
            val file = plainMp3("lists-$version")
            val originalAudio = file.readBytes()
            val tags = TagBundle(mapOf(TagField.ARTIST to artists, TagField.COMPOSER to artists, TagField.LYRICIST to artists))
            file.writeBytes(AudioKodec.writeTags(originalAudio, tags, TagWriteOptions(id3Version = version)))
            run(listOf(python, "-c", CHECK_NAMED_LISTS, file.path, version.toString()))
            assertEquals(tags.fields, AudioKodec.readTags(file.readBytes()).fields)
            assertContentEquals(originalAudio, file.readBytes().takeLast(originalAudio.size).toByteArray())
        }
        val file = plainMp3("user-lists")
        val originalAudio = file.readBytes()
        val fields = mapOf(TagField.CATALOG_NUMBER to artists, TagField.MUSICBRAINZ_ARTIST_ID to listOf(recording, second))
        file.writeBytes(AudioKodec.writeTags(originalAudio, TagBundle(fields), TagWriteOptions(id3Version = 4)))
        run(listOf(python, "-c", CHECK_USER_LISTS, file.path))
        assertEquals(fields, AudioKodec.readTags(file.readBytes()).fields)
        assertContentEquals(originalAudio, file.readBytes().takeLast(originalAudio.size).toByteArray())
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.writeTags(originalAudio, TagBundle(fields), TagWriteOptions(id3Version = 3))
        }
    }

    @Test
    fun mutagensMultiValueUserTextAndOtherOwnerIdsSurviveAnUnrelatedEdit() {
        val python = python
        for (encoding in intArrayOf(1, 3)) {
            val file = plainMp3("raw-lists-$encoding")
            val originalAudio = file.readBytes()
            run(listOf(python, "-c", WRITE_RAW_LISTS, file.path, encoding.toString()))
            val tags = AudioKodec.readTags(file.readBytes())
            assertEquals(listOf("first", "日本語 second"), tags.raw.blocks.flatMap { it.entries }.filter { it.key == "TXXX" }.map { it.text })
            file.writeBytes(AudioKodec.writeTags(file.readBytes(), tags.edit { set(TagField.TITLE, "Changed") }, TagWriteOptions(id3Version = 4)))
            run(listOf(python, "-c", CHECK_RAW_LISTS, file.path))
            assertContentEquals(originalAudio, file.readBytes().takeLast(originalAudio.size).toByteArray())
        }
    }

    private fun plainMp3(name: String): File {
        val ffmpeg = findOnPath("ffmpeg")
        assumeTrue("ffmpeg is required for the ID3 oracle's audio fixture", ffmpeg != null)
        val file = File(directory, "$name.mp3")
        run(listOf(ffmpeg!!, "-y", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=0.1", "-c:a", "libmp3lame", "-write_xing", "0", "-id3v2_version", "0", "-write_id3v1", "0", file.path))
        return file
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("${command.first()} did not finish")
        }
        assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
    }

    private fun findOnPath(name: String): String? = System.getenv("PATH")?.split(File.pathSeparator)
        ?.map { File(it, name) }?.firstOrNull { it.canExecute() }?.absolutePath

    private companion object {
        val directory = File(System.getProperty("java.io.tmpdir"), "audiokodec-mutagen-oracle").also { it.mkdirs() }
        const val WRITE_IDENTIFIERS = """
import sys
from mutagen.id3 import ID3, UFID, TXXX
t = ID3()
t.add(UFID(owner='http://musicbrainz.org', data=b'ebf79ba5-085e-48d2-9eb8-2d992fbf0f6d'))
for desc in ['MusicBrainz Artist Id', 'MusicBrainz Album Id', 'MusicBrainz Album Artist Id', 'MusicBrainz Release Group Id', 'MusicBrainz Release Track Id']:
    t.add(TXXX(encoding=3, desc=desc, text=['0383dadf-2a4e-4d10-a46a-e9e041da8eb3']))
t.save(sys.argv[1], v2_version=int(sys.argv[2]), v1=0)
"""
        const val CHECK_IDENTIFIERS = """
import sys
from mutagen.id3 import ID3
t = ID3(sys.argv[1])
u = t.getall('UFID')
assert len(u) == 1 and u[0].owner == 'http://musicbrainz.org'
assert u[0].data == b'ebf79ba5-085e-48d2-9eb8-2d992fbf0f6d'
expected = ['MusicBrainz Artist Id', 'MusicBrainz Album Id', 'MusicBrainz Album Artist Id', 'MusicBrainz Release Group Id', 'MusicBrainz Release Track Id']
assert {f.desc for f in t.getall('TXXX')} == set(expected)
for desc in expected:
    assert t['TXXX:' + desc].text == ['0383dadf-2a4e-4d10-a46a-e9e041da8eb3']
"""
        const val CHECK_NAMED_LISTS = """
import sys
from mutagen.id3 import ID3
t = ID3(sys.argv[1])
expected = ['Alpha/Björk 日本語'] if sys.argv[2] == '3' else ['Alpha', 'Björk 日本語']
for key in ['TPE1', 'TCOM', 'TEXT']:
    assert len(t.getall(key)) == 1 and t[key].text == expected, (key, t[key].text)
"""
        const val CHECK_USER_LISTS = """
import sys
from mutagen.id3 import ID3
t = ID3(sys.argv[1])
assert len(t.getall('TXXX')) == 2
assert t['TXXX:CATALOGNUMBER'].text == ['Alpha', 'Björk 日本語']
assert t['TXXX:MusicBrainz Artist Id'].text == ['ebf79ba5-085e-48d2-9eb8-2d992fbf0f6d', '0383dadf-2a4e-4d10-a46a-e9e041da8eb3']
"""
        const val WRITE_RAW_LISTS = """
import sys
from mutagen.id3 import ID3, TXXX, UFID
t = ID3()
t.add(TXXX(encoding=int(sys.argv[2]), desc='Private description', text=['first', '日本語 second']))
t.add(UFID(owner='https://example.org', data=bytes([255, 0, 1])))
t.save(sys.argv[1], v2_version=4, v1=0)
"""
        const val CHECK_RAW_LISTS = """
import sys
from mutagen.id3 import ID3
t = ID3(sys.argv[1])
assert len(t.getall('TXXX')) == 1
assert t['TXXX:Private description'].text == ['first', '日本語 second']
assert t['UFID:https://example.org'].data == bytes([255, 0, 1])
"""
    }
}

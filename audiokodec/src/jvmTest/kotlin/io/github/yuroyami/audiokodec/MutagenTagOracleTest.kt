package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.RawTagBlock
import io.github.yuroyami.audiokodec.tag.RawTags
import io.github.yuroyami.audiokodec.tag.TagFormat
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

    @Test
    fun genreReferencesAndEscapesAgreeInBothDirectionsAndAfterAnEdit() {
        val python = python
        val cases = mapOf(
            "(51)(39)" to listOf("Techno-Industrial", "Noise"),
            "(51)(39)Eurodisco" to listOf("Techno-Industrial", "Noise", "Eurodisco"),
            "(4)Eurodisco" to listOf("Disco", "Eurodisco"),
            "(17)Rock" to listOf("Rock"),
            "(RX)(CR)" to listOf("Remix", "Cover"),
            "((I Love You)" to listOf("(I Love You)"),
            "(55)((I think...)" to listOf("Dream", "(I think...)"),
        )
        for (version in 3..4) {
            val file = plainMp3("genres-$version")
            val originalAudio = file.readBytes()
            for ((stored, expected) in cases) {
                file.writeBytes(originalAudio)
                run(listOf(python, "-c", WRITE_GENRE, file.path, version.toString(), stored))
                run(listOf(python, "-c", CHECK_GENRES, file.path) + expected)
                val tags = AudioKodec.readTags(file.readBytes())
                assertEquals(expected, tags.all(TagField.GENRE))
                file.writeBytes(AudioKodec.writeTags(file.readBytes(), tags.edit { set(TagField.TITLE, "Edited") }, TagWriteOptions(id3Version = version)))
                run(listOf(python, "-c", CHECK_GENRES, file.path) + expected)
                assertEquals(expected, AudioKodec.readTags(file.readBytes()).all(TagField.GENRE))
                assertContentEquals(originalAudio, file.readBytes().takeLast(originalAudio.size).toByteArray())

                file.writeBytes(AudioKodec.writeTags(originalAudio, TagBundle(mapOf(TagField.GENRE to expected)), TagWriteOptions(id3Version = version)))
                run(listOf(python, "-c", CHECK_GENRES, file.path) + expected)
                assertContentEquals(originalAudio, file.readBytes().takeLast(originalAudio.size).toByteArray())
            }
        }
    }

    @Test
    fun version24CustomGenreListsKeepTheirOrderUnicodeAndLiteralParentheses() {
        val python = python
        val file = plainMp3("custom-genres")
        val originalAudio = file.readBytes()
        val expected = listOf("日本語 genre", "Rock", "(51)", "((Literal)")
        file.writeBytes(AudioKodec.writeTags(originalAudio, TagBundle(mapOf(TagField.GENRE to expected)), TagWriteOptions(id3Version = 4)))
        run(listOf(python, "-c", CHECK_STORED_GENRES, file.path) + expected)
        assertEquals(expected, AudioKodec.readTags(file.readBytes()).all(TagField.GENRE))
        assertContentEquals(originalAudio, file.readBytes().takeLast(originalAudio.size).toByteArray())
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.writeTags(originalAudio, TagBundle(mapOf(TagField.GENRE to expected)), TagWriteOptions(id3Version = 3))
        }
        val numericRefinement = listOf("Rock", "17")
        file.writeBytes(AudioKodec.writeTags(originalAudio, TagBundle(mapOf(TagField.GENRE to numericRefinement)), TagWriteOptions(id3Version = 3)))
        run(listOf(python, "-c", CHECK_STORED_GENRES, file.path) + numericRefinement)
        assertEquals(numericRefinement, AudioKodec.readTags(file.readBytes()).all(TagField.GENRE))
        assertContentEquals(originalAudio, file.readBytes().takeLast(originalAudio.size).toByteArray())
    }

    @Test
    fun manyCommentAndLyricIdentitiesSurviveEditsInBothVersions() {
        val python = python
        val count = 512
        for (version in 3..4) {
            val file = plainMp3("many-identities-$version")
            val originalAudio = file.readBytes()
            run(listOf(python, "-c", WRITE_MANY_IDENTITIES, file.path, version.toString(), count.toString()))
            val tags = AudioKodec.readTags(file.readBytes())
            assertEquals(count + 3, tags.all(TagField.COMMENT).size)
            assertEquals(count, tags.all(TagField.LYRICS).size)
            val edited = tags.edit {
                repeat(count) { add(TagField.COMMENT, "new-comment-$it"); add(TagField.LYRICS, "new-lyric-$it") }
                set(TagField.TITLE, "Edited")
            }
            file.writeBytes(AudioKodec.writeTags(file.readBytes(), edited, TagWriteOptions(id3Version = version)))
            run(listOf(python, "-c", CHECK_MANY_IDENTITIES, file.path, count.toString()))
            val after = AudioKodec.readTags(file.readBytes())
            assertEquals(edited.all(TagField.COMMENT), after.all(TagField.COMMENT))
            assertEquals(edited.all(TagField.LYRICS), after.all(TagField.LYRICS))
            assertContentEquals(originalAudio, file.readBytes().takeLast(originalAudio.size).toByteArray())
        }
    }

    @Test
    fun mappedAndCustomTextListsAgreeWithMutagenInEveryEncoding() {
        val python = python
        for (encoding in Id3TextListFixtures.encodings) {
            val file = plainMp3("text-lists-${encoding.code}-${encoding.bigEndian}")
            val originalAudio = file.readBytes()
            val independent = Id3TextListFixtures.tag(encoding) + originalAudio
            file.writeBytes(independent)
            run(listOf(python, "-c", CHECK_TEXT_LIST_MATRIX, file.path, encoding.code.toString(), "Original"))
            val tags = AudioKodec.readTags(independent)
            val rewritten = AudioKodec.writeTags(independent, tags.edit { set(TagField.TITLE, "Changed") }, TagWriteOptions(id3Version = 4, padding = 0))
            file.writeBytes(rewritten)
            run(listOf(python, "-c", CHECK_TEXT_LIST_MATRIX, file.path, encoding.code.toString(), "Changed"))
            assertContentEquals(originalAudio, rewritten.takeLast(originalAudio.size).toByteArray())

            // Mutagen writes encoding 1 in little-endian; the independent
            // fixture above checks its reading of both BOM byte orders.
            if (encoding.code == 1 && encoding.bigEndian) continue
            file.writeBytes(originalAudio)
            run(listOf(python, "-c", WRITE_TEXT_LIST_MATRIX, file.path, encoding.code.toString()))
            val fromMutagen = file.readBytes()
            val read = AudioKodec.readTags(fromMutagen)
            assertEquals(Id3TextListFixtures.artists(encoding), read.all(TagField.ARTIST))
            assertEquals(Id3TextListFixtures.identifiers, read.all(TagField.MUSICBRAINZ_ARTIST_ID))
            val raw = read.raw.blocks.flatMap { it.entries }
            assertEquals(Id3TextListFixtures.originalArtists(encoding), raw.filter { it.key == "TOPE" }.map { it.text })
            assertEquals(Id3TextListFixtures.customValues(encoding), raw.filter { it.key == "TXXX" && it.description == Id3TextListFixtures.description(encoding) }.map { it.text })
            assertEquals(Id3TextListFixtures.emptyDescriptionValues, raw.filter { it.key == "TXXX" && it.description == "" }.map { it.text })
            val edited = AudioKodec.writeTags(fromMutagen, read.edit { set(TagField.TITLE, "Changed") }, TagWriteOptions(id3Version = 4, padding = 0))
            file.writeBytes(edited)
            run(listOf(python, "-c", CHECK_TEXT_LIST_MATRIX, file.path, encoding.code.toString(), "Changed"))
            assertContentEquals(originalAudio, edited.takeLast(originalAudio.size).toByteArray())
        }
    }

    @Test
    fun emptyRawTextValuesAgreeWithMutagenAfterAnUnrelatedEdit() {
        val python = python
        val file = plainMp3("empty-text-values")
        val originalAudio = file.readBytes()
        for (encoding in Id3TextListFixtures.encodings) {
            for (values in Id3EmptyValueFixtures.valuesFor(encoding)) {
                val independent = Id3EmptyValueFixtures.tag(encoding, values) + originalAudio
                file.writeBytes(independent)
                run(listOf(python, "-c", CHECK_EMPTY_VALUES, file.path) + values)
                val tags = AudioKodec.readTags(independent)
                val rewritten = AudioKodec.writeTags(independent, tags.edit { set(TagField.TITLE, "Changed") }, TagWriteOptions(id3Version = 4, padding = 0))
                file.writeBytes(rewritten)
                run(listOf(python, "-c", CHECK_EMPTY_VALUES, file.path) + values)
                assertContentEquals(originalAudio, rewritten.takeLast(originalAudio.size).toByteArray())

                // Mutagen omits a frame containing exactly one empty value.
                // Independent bytes above cover its reader for that case.
                if (values == listOf("") || encoding.code == 1 && encoding.bigEndian) continue
                file.writeBytes(originalAudio)
                run(listOf(python, "-c", WRITE_EMPTY_VALUES, file.path, encoding.code.toString()) + values)
                val fromMutagen = file.readBytes()
                val read = AudioKodec.readTags(fromMutagen)
                for (key in listOf("TOPE", "TXXX")) {
                    assertEquals(values, read.raw.blocks.flatMap { it.entries }.filter { it.key == key }.map { it.text })
                }
                val edited = AudioKodec.writeTags(fromMutagen, read.edit { set(TagField.TITLE, "Changed") }, TagWriteOptions(id3Version = 4, padding = 0))
                file.writeBytes(edited)
                run(listOf(python, "-c", CHECK_EMPTY_VALUES, file.path) + values)
                assertContentEquals(originalAudio, edited.takeLast(originalAudio.size).toByteArray())
            }
        }
        for (values in Id3EmptyValueFixtures.lists + Id3EmptyValueFixtures.unicodeLists) {
            val read = AudioKodec.readTags(Id3EmptyValueFixtures.tag(Id3TextListFixtures.Encoding(1), values) + originalAudio)
            val entries = read.raw.blocks.flatMap { it.entries }.filter { it.key == "TOPE" }
            val tags = TagBundle(emptyMap(), raw = RawTags(listOf(RawTagBlock(TagFormat.ID3V2_4, 0, 0, entries))))
            val written = AudioKodec.writeTags(originalAudio, tags, TagWriteOptions(id3Version = 3, padding = 0))
            file.writeBytes(written)
            run(listOf(python, "-c", CHECK_EMPTY_SLASH_VALUES, file.path) + values)
            assertContentEquals(originalAudio, written.takeLast(originalAudio.size).toByteArray())
        }
        val unicodeEmpty = Id3EmptyValueFixtures.tag(Id3TextListFixtures.Encoding(1), listOf(""), customDescription = "Private 日本語") + originalAudio
        val unicodeTags = AudioKodec.readTags(unicodeEmpty)
        for (version in 3..4) {
            val written = AudioKodec.writeTags(unicodeEmpty, unicodeTags.edit { set(TagField.TITLE, "Changed") }, TagWriteOptions(id3Version = version, padding = 0))
            file.writeBytes(written)
            run(listOf(python, "-c", CHECK_UNICODE_EMPTY, file.path))
            assertContentEquals(originalAudio, written.takeLast(originalAudio.size).toByteArray())
        }
    }

    @Test
    fun commentAndLyricWhitespaceAgreesInBothDirectionsAfterEdits() {
        val python = python
        for (inputVersion in 3..4) {
            val file = plainMp3("whitespace-$inputVersion")
            val originalAudio = file.readBytes()
            run(listOf(python, "-c", WRITE_WHITESPACE, file.path, inputVersion.toString()))
            val original = file.readBytes()
            val tags = AudioKodec.readTags(original)
            assertEquals(setOf("\t bonjour \r\n", "\u00A0 padded \u00A0", "gleich\n", " \t\r\n "), tags.all(TagField.COMMENT).toSet())
            assertEquals(setOf(" 日本語\n", "la la\n", "\tla la\r\n"), tags.all(TagField.LYRICS).toSet())
            fun identities(bundle: TagBundle) = bundle.raw.blocks.flatMap { it.entries }
                .filter { it.key == "COMM" || it.key == "USLT" }
                .map { listOf(it.key, it.language, it.description, it.text) }.toSet()
            assertEquals(9, identities(tags).size)
            for (outputVersion in 3..4) {
                val options = TagWriteOptions(id3Version = outputVersion, padding = 0)
                val edited = AudioKodec.writeTags(original, tags.edit { set(TagField.TITLE, "Changed") }, options)
                file.writeBytes(edited)
                run(listOf(python, "-c", CHECK_WHITESPACE, file.path, "preserved"))
                val after = AudioKodec.readTags(edited)
                assertEquals(tags.all(TagField.COMMENT), after.all(TagField.COMMENT))
                assertEquals(tags.all(TagField.LYRICS), after.all(TagField.LYRICS))
                assertEquals(identities(tags), identities(after))
                assertContentEquals(originalAudio, edited.takeLast(originalAudio.size).toByteArray())

                val replaced = AudioKodec.writeTags(original, tags.edit {
                    set(TagField.COMMENT, "bonjour")
                    set(TagField.LYRICS, "la la")
                }, options)
                file.writeBytes(replaced)
                run(listOf(python, "-c", CHECK_WHITESPACE, file.path, "replaced"))
                assertContentEquals(originalAudio, replaced.takeLast(originalAudio.size).toByteArray())

                val fields = mapOf(
                    TagField.COMMENT to listOf(" fresh \n", "\t"),
                    TagField.LYRICS to listOf("日本語\n", "\n\n"),
                )
                val fresh = AudioKodec.writeTags(originalAudio, TagBundle(fields), options)
                file.writeBytes(fresh)
                run(listOf(python, "-c", CHECK_WHITESPACE, file.path, "fresh"))
                assertEquals(fields, AudioKodec.readTags(fresh).fields)
                assertContentEquals(originalAudio, fresh.takeLast(originalAudio.size).toByteArray())
            }
        }
    }

    @Test
    fun largeFlacArtistListsAndDuplicateControlsAgreeWithMutagen() {
        val python = python
        val count = 64_000
        for (repeated in listOf(false, true)) {
            val comments = List(count) { "ARTIST=" + if (repeated) "same" else "artist-$it" }
            val file = File(directory, "many-artists-$repeated.flac")
            file.writeBytes(TagStressFixtures.flac(comments))
            run(listOf(python, "-c", CHECK_FLAC_ARTISTS, file.path, count.toString(), repeated.toString()))
            val expected = if (repeated) listOf("same") else List(count) { "artist-$it" }
            val tags = AudioKodec.readTags(file.readBytes())
            assertEquals(expected, tags.all(TagField.ARTIST))
            assertEquals(count, tags.raw.blocks.single().entries.count { it.key == "ARTIST" })
        }
    }

    @Test
    fun unknownFlacCommentsAgreeWithMutagenAndMetaflacAcrossRepeatedEdits() {
        val python = python
        val metaflac = findOnPath("metaflac")
        assumeTrue("metaflac is required for the FLAC comment oracle", metaflac != null)
        val comments = listOf("PRIVATE=one", "Private=one", "PRIVATE=two", "OTHER=x=y 日本語", "PRIVATE=one", "VENDOR=user", "PRIVATE=", "replaygain_private=state", "R128_PRIVATE=")
        val file = File(directory, "private-comments.flac")
        for (vendor in listOf("oracle vendor", "")) {
            file.writeBytes(TagStressFixtures.flac(comments, vendor))
            val originalAudio = TagStressFixtures.flacAudio(file.readBytes())
            run(listOf(python, "-c", CHECK_PRIVATE_FLAC, file.path, vendor, ""))
            repeat(3) { edit ->
                val title = "Edit $edit"
                val written = AudioKodec.writeTags(file.readBytes(), AudioKodec.readTags(file.readBytes()).edit { set(TagField.TITLE, title) })
                file.writeBytes(written)
                run(listOf(python, "-c", CHECK_PRIVATE_FLAC, file.path, vendor, title))
                assertEquals((listOf("TITLE=$title") + comments).joinToString("\n", postfix = "\n"), run(listOf(metaflac!!, "--export-tags-to=-", file.path)))
                assertContentEquals(originalAudio, TagStressFixtures.flacAudio(written))
            }
            file.writeBytes(TagStressFixtures.flac(emptyList()))
            run(listOf(python, "-c", WRITE_PRIVATE_FLAC, file.path, vendor))
            val fromMutagen = file.readBytes()
            val tags = AudioKodec.readTags(fromMutagen)
            val rewritten = AudioKodec.writeTags(fromMutagen, tags.edit { set(TagField.TITLE, "Changed") })
            file.writeBytes(rewritten)
            run(listOf(python, "-c", CHECK_PRIVATE_FLAC, file.path, vendor, "Changed"))
            assertContentEquals(TagStressFixtures.flacAudio(fromMutagen), TagStressFixtures.flacAudio(rewritten))
        }
    }

    private fun plainMp3(name: String): File {
        val ffmpeg = findOnPath("ffmpeg")
        assumeTrue("ffmpeg is required for the ID3 oracle's audio fixture", ffmpeg != null)
        val file = File(directory, "$name.mp3")
        run(listOf(ffmpeg!!, "-y", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=0.1", "-c:a", "libmp3lame", "-write_xing", "0", "-id3v2_version", "0", "-write_id3v1", "0", file.path))
        return file
    }

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("${command.first()} did not finish")
        }
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.exitValue(), output)
        return output
    }

    private fun findOnPath(name: String): String? = System.getenv("PATH")?.split(File.pathSeparator)
        ?.map { File(it, name) }?.firstOrNull { it.canExecute() }?.absolutePath

    private companion object {
        const val CHECK_PRIVATE_FLAC = """
import sys
from mutagen.flac import FLAC
tag = FLAC(sys.argv[1]).tags
expected = [('PRIVATE', 'one'), ('Private', 'one'), ('PRIVATE', 'two'), ('OTHER', 'x=y 日本語'), ('PRIVATE', 'one'), ('VENDOR', 'user'), ('PRIVATE', ''), ('replaygain_private', 'state'), ('R128_PRIVATE', '')]
if sys.argv[3]: expected.insert(0, ('TITLE', sys.argv[3]))
assert list(tag) == expected, (list(tag), expected)
assert tag.vendor == sys.argv[2], (tag.vendor, sys.argv[2])
"""
        const val WRITE_PRIVATE_FLAC = """
import sys
from mutagen.flac import FLAC
file = FLAC(sys.argv[1])
file.tags.clear()
file.tags.extend([('PRIVATE', 'one'), ('Private', 'one'), ('PRIVATE', 'two'), ('OTHER', 'x=y 日本語'), ('PRIVATE', 'one'), ('VENDOR', 'user'), ('PRIVATE', ''), ('replaygain_private', 'state'), ('R128_PRIVATE', '')])
file.tags.vendor = sys.argv[2]
file.save()
"""
        const val CHECK_UNICODE_EMPTY = """
import sys
from mutagen.id3 import ID3
tag = ID3(sys.argv[1])
assert tag['TXXX:Private 日本語'].text == [''], tag['TXXX:Private 日本語'].text
assert tag['TOPE'].text == [''], tag['TOPE'].text
"""
        const val CHECK_EMPTY_SLASH_VALUES = """
import sys
from mutagen.id3 import ID3
actual = ID3(sys.argv[1])['TOPE'].text
expected = ['/'.join(sys.argv[2:])]
assert actual == expected, (actual, expected)
"""
        const val CHECK_EMPTY_VALUES = """
import sys
from mutagen.id3 import ID3
tag = ID3(sys.argv[1])
expected = sys.argv[2:]
assert tag['TOPE'].text == expected, (tag['TOPE'].text, expected)
assert tag['TXXX:Private empty values'].text == expected, (tag['TXXX:Private empty values'].text, expected)
"""
        const val WRITE_EMPTY_VALUES = """
import sys
from mutagen.id3 import ID3, TOPE, TXXX
tag = ID3()
encoding = int(sys.argv[2])
values = sys.argv[3:]
tag.add(TOPE(encoding=encoding, text=values))
tag.add(TXXX(encoding=encoding, desc='Private empty values', text=values))
tag.save(sys.argv[1], v2_version=4, padding=lambda info: 0)
"""

        val directory = File(System.getProperty("java.io.tmpdir"), "audiokodec-mutagen-oracle").also { it.mkdirs() }
        const val WRITE_TEXT_LIST_MATRIX = """
import sys
from mutagen.id3 import ID3, TIT2, TPE1, TOPE, TXXX
encoding = int(sys.argv[2])
artists = ['first', 'Björk' if encoding == 0 else 'AĀ 日本語', 'third']
custom = ['x', 'ÉA' if encoding == 0 else 'ĀA 日本語', 'x', 'z']
desc = 'Private description' if encoding == 0 else 'Private 日本語'
t = ID3()
t.add(TIT2(encoding=encoding, text=['Original']))
t.add(TPE1(encoding=encoding, text=artists))
t.add(TOPE(encoding=encoding, text=artists + ['first']))
t.add(TXXX(encoding=encoding, desc='MusicBrainz Artist Id', text=['id-one', 'id-two']))
t.add(TXXX(encoding=encoding, desc=desc, text=custom))
t.add(TXXX(encoding=encoding, desc='', text=['empty-first', 'empty-second']))
t.save(sys.argv[1], v2_version=4, v1=0)
"""
        const val CHECK_TEXT_LIST_MATRIX = """
import sys
from mutagen.id3 import ID3
encoding = int(sys.argv[2])
artists = ['first', 'Björk' if encoding == 0 else 'AĀ 日本語', 'third']
custom = ['x', 'ÉA' if encoding == 0 else 'ĀA 日本語', 'x', 'z']
desc = 'Private description' if encoding == 0 else 'Private 日本語'
t = ID3(sys.argv[1])
assert t['TIT2'].text == [sys.argv[3]]
assert len(t.getall('TPE1')) == 1
assert t['TPE1'].text == artists
assert len(t.getall('TOPE')) == 1
assert t['TOPE'].text == artists + ['first']
assert len(t.getall('TXXX')) == 3
assert t['TXXX:MusicBrainz Artist Id'].text == ['id-one', 'id-two']
assert t['TXXX:' + desc].text == custom
assert t['TXXX:'].text == ['empty-first', 'empty-second']
"""
        const val WRITE_WHITESPACE = """
import sys
from mutagen.id3 import ID3, COMM, USLT
t = ID3()
comments = [('fra', 'review', '\t bonjour \r\n'), ('eng', 'padding', '\u00a0 padded \u00a0'),
            ('deu', 'a', 'gleich\n'), ('deu', 'b', 'gleich\n'), ('ita', 'blank', ' \t\r\n ')]
lyrics = [('jpn', 'verse', ' 日本語\n'), ('eng', 'verse', 'la la\n'),
          ('fra', 'verse', 'la la\n'), ('deu', 'verse', '\tla la\r\n')]
for lang, desc, text in comments:
    t.add(COMM(encoding=3, lang=lang, desc=desc, text=[text]))
for lang, desc, text in lyrics:
    t.add(USLT(encoding=3, lang=lang, desc=desc, text=text))
if sys.argv[2] == '3':
    t.update_to_v23()
t.save(sys.argv[1], v2_version=int(sys.argv[2]), v1=0)
"""
        const val CHECK_WHITESPACE = """
import sys
from mutagen.id3 import ID3
t = ID3(sys.argv[1])
mode = sys.argv[2]
if mode == 'preserved':
    comments = [('fra', 'review', '\t bonjour \r\n'), ('eng', 'padding', '\u00a0 padded \u00a0'),
                ('deu', 'a', 'gleich\n'), ('deu', 'b', 'gleich\n'), ('ita', 'blank', ' \t\r\n ')]
    lyrics = [('jpn', 'verse', ' 日本語\n'), ('eng', 'verse', 'la la\n'),
              ('fra', 'verse', 'la la\n'), ('deu', 'verse', '\tla la\r\n')]
    assert t['TIT2'].text == ['Changed']
elif mode == 'replaced':
    comments = [('xxx', '', 'bonjour')]
    lyrics = [('xxx', '', 'la la')]
else:
    assert mode == 'fresh'
    comments = [('xxx', '', ' fresh \n'), ('xxx', '2', '\t')]
    lyrics = [('xxx', '', '日本語\n'), ('xxx', '2', '\n\n')]
assert len(t.getall('COMM')) == len(comments)
assert len(t.getall('USLT')) == len(lyrics)
for lang, desc, text in comments:
    assert t['COMM:' + desc + ':' + lang].text == [text]
for lang, desc, text in lyrics:
    assert t['USLT:' + desc + ':' + lang].text == text
"""
        const val WRITE_MANY_IDENTITIES = """
import sys
from mutagen.id3 import ID3, COMM, USLT
t = ID3()
n = int(sys.argv[3])
for i in range(n):
    desc = ''.join('Aa' if i & (1 << bit) == 0 else 'BB' for bit in range(16))
    t.add(COMM(encoding=3, lang='eng', desc=desc, text=['original-comment-' + str(i)]))
    t.add(USLT(encoding=3, lang='eng', desc=desc, text='original-lyric-' + str(i)))
t.add(COMM(encoding=3, lang='fra', desc='shared', text=['original-comment-0']))
for desc, text in [('', 'saved blank'), ('2', 'saved two'), ('4', 'saved four')]:
    t.add(COMM(encoding=3, lang='xxx', desc=desc, text=[text]))
if sys.argv[2] == '3':
    t.update_to_v23()
t.save(sys.argv[1], v2_version=int(sys.argv[2]), v1=0)
"""
        const val CHECK_MANY_IDENTITIES = """
import sys
from mutagen.id3 import ID3
t = ID3(sys.argv[1])
n = int(sys.argv[2])
assert len(t.getall('COMM')) == 2 * n + 4
assert len(t.getall('USLT')) == 2 * n
for i in range(n):
    desc = ''.join('Aa' if i & (1 << bit) == 0 else 'BB' for bit in range(16))
    assert t['COMM:' + desc + ':eng'].text == ['original-comment-' + str(i)]
    assert t['USLT:' + desc + ':eng'].text == 'original-lyric-' + str(i)
    comment_desc = '3' if i == 0 else str(i + 4)
    lyric_desc = '' if i == 0 else str(i + 1)
    assert t['COMM:' + comment_desc + ':xxx'].text == ['new-comment-' + str(i)]
    assert t['USLT:' + lyric_desc + ':xxx'].text == 'new-lyric-' + str(i)
assert t['COMM:shared:fra'].text == ['original-comment-0']
for desc, text in [('', 'saved blank'), ('2', 'saved two'), ('4', 'saved four')]:
    assert t['COMM:' + desc + ':xxx'].text == [text]
"""
        const val CHECK_FLAC_ARTISTS = """
import sys
from mutagen.flac import FLAC
values = FLAC(sys.argv[1])['artist']
n = int(sys.argv[2])
expected = ['same'] * n if sys.argv[3] == 'true' else ['artist-' + str(i) for i in range(n)]
assert values == expected
"""
        const val WRITE_GENRE = """
import sys
from mutagen.id3 import ID3, TCON
t = ID3()
t.add(TCON(encoding=1, text=[sys.argv[3]]))
t.save(sys.argv[1], v2_version=int(sys.argv[2]), v1=0)
"""
        const val CHECK_GENRES = """
import sys
from mutagen.id3 import ID3
t = ID3(sys.argv[1])
assert len(t.getall('TCON')) == 1
assert t['TCON'].genres == sys.argv[2:], (t['TCON'].text, t['TCON'].genres, sys.argv[2:])
"""
        // Default loading normalises TCON text first, so calling genres can
        // interpret a literal "(51)" twice. Disable that conversion when
        // checking the stored escape grammar independently.
        const val CHECK_STORED_GENRES = """
import sys
from mutagen.id3 import ID3
t = ID3(sys.argv[1], translate=False)
assert len(t.getall('TCON')) == 1
assert t['TCON'].genres == sys.argv[2:], (t['TCON'].text, t['TCON'].genres, sys.argv[2:])
"""
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

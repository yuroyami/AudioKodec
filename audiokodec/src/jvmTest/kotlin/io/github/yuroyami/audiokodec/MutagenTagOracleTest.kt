package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.RawTagBlock
import io.github.yuroyami.audiokodec.tag.RawTags
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import io.github.yuroyami.audiokodec.tag.TagPicture
import io.github.yuroyami.audiokodec.tag.TagChapter
import io.github.yuroyami.audiokodec.tag.PictureType
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
    fun itunesPlaybackCommentsSurviveUserCommentEditsInBothDirections() {
        val python = python
        val privateText = "000000F0 000000F0"
        for (inputVersion in 3..4) {
            val file = plainMp3("itunes-comments-$inputVersion")
            val audio = file.readBytes()
            run(listOf(python, "-c", ITUNES_COMMENTS, file.path, "write", inputVersion.toString()))
            val original = file.readBytes()
            val before = AudioKodec.readTags(original)
            assertEquals(setOf("old note", "named note"), before.all(TagField.COMMENT).toSet())
            for (outputVersion in 3..4) {
                val edits = listOf(
                    "preserved" to before.edit { set(TagField.TITLE, "Changed") },
                    "replaced" to before.edit { set(TagField.COMMENT, privateText) },
                    "removed" to before.edit { remove(TagField.COMMENT) },
                )
                for ((mode, edited) in edits) {
                    val written = AudioKodec.writeTags(original, edited, TagWriteOptions(id3Version = outputVersion, padding = 0))
                    file.writeBytes(written)
                    run(listOf(python, "-c", ITUNES_COMMENTS, file.path, mode))
                    assertEquals(edited.all(TagField.COMMENT), AudioKodec.readTags(written).all(TagField.COMMENT))
                    assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
                    // A subsequent unrelated rewrite must keep both scopes stable.
                    val again = AudioKodec.writeTags(written, AudioKodec.readTags(written).edit {
                        set(TagField.TITLE, "Again")
                    }, TagWriteOptions(id3Version = outputVersion, padding = 0))
                    file.writeBytes(again)
                    run(listOf(python, "-c", ITUNES_COMMENTS, file.path, mode))
                    assertContentEquals(audio, again.takeLast(audio.size).toByteArray())
                }
            }
        }
    }

    @Test
    fun allStandardApePictureKeysAgreeWithReferenceItemsInBothDirections() {
        val python = python
        val file = plainMp3("all-ape-picture-roles")
        val audio = file.readBytes()
        val pictures = PictureType.entries.map { type ->
            TagPicture(type, "image/jpeg", "role-${type.ordinal}.jpg", byteArrayOf(-1, -40, 0, type.ordinal.toByte()))
        }
        val options = TagWriteOptions(formats = setOf(TagFormat.APEV2), padding = 0)
        file.writeBytes(AudioKodec.writeTags(audio, TagBundle(emptyMap(), pictures), options))
        run(listOf(python, "-c", ALL_APE_ROLES, file.path, "check"))
        run(listOf(python, "-c", ALL_APE_ROLES, file.path, "write"))
        val read = AudioKodec.readTags(file.readBytes())
        assertEquals(PictureType.entries.toSet(), read.pictures.map { it.type }.toSet())
        for (picture in read.pictures) assertContentEquals(pictures[picture.type.ordinal].bytes, picture.bytes)
        file.writeBytes(AudioKodec.writeTags(file.readBytes(), read.edit { set(TagField.TITLE, "Changed") }, options))
        run(listOf(python, "-c", ALL_APE_ROLES, file.path, "check"))
        assertContentEquals(audio, file.readBytes().copyOfRange(0, audio.size))
    }

    @Test
    fun leadingId3AndRealFlacOrOpusKeepTheSamePictureIdentities() {
        val python = python
        val ffmpeg = findOnPath("ffmpeg")
        assumeTrue("ffmpeg is required for native artwork fixtures", ffmpeg != null)
        val pictures = listOf(
            TagPicture(mimeType = "image/jpeg", bytes = byteArrayOf(-1, -40, 0, 1)),
            TagPicture(mimeType = "image/jpeg", bytes = byteArrayOf(-1, -40, 0, 2)),
        )
        for (kind in listOf("flac", "opus")) {
            val file = File(directory, "native-pictures.$kind")
            run(listOf(ffmpeg!!, "-y", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=0.1", "-c:a", if (kind == "flac") "flac" else "libopus", file.path))
            val original = file.readBytes()
            val native = AudioKodec.writeTags(original, TagBundle(emptyMap(), pictures))
            file.writeBytes(PictureTagFixtures.id3(pictures, 4) + native)
            repeat(4) { edit ->
                val read = AudioKodec.readTags(file.readBytes())
                assertEquals(2, read.pictures.size)
                file.writeBytes(AudioKodec.writeTags(file.readBytes(), read.edit { set(TagField.TITLE, "Edit $edit") }, TagWriteOptions(padding = 0)))
                val bytes = file.readBytes()
                val after = AudioKodec.readTags(bytes)
                assertEquals(listOf("", " [1]"), after.pictures.map { it.description })
                val nativeBytes = bytes.copyOfRange(after.raw.blocks.first().size.toInt(), bytes.size)
                val stripped = File(directory, "stripped-pictures.$kind").also { it.writeBytes(nativeBytes) }
                run(listOf(python, "-c", CHECK_NATIVE_PICTURES, file.path, stripped.path, kind))
                if (kind == "flac") assertContentEquals(TagStressFixtures.flacAudio(original), TagStressFixtures.flacAudio(nativeBytes))
                else run(listOf(python, "-c", CHECK_OPUS_AUDIO, File(directory, "original.opus").also { it.writeBytes(original) }.path, stripped.path))
            }
        }
    }

    @Test
    fun mixedPicturesAndChapterIdentitiesAgreeAfterRepeatedEdits() {
        val python = python
        val pictures = listOf(
            TagPicture(mimeType = "image/jpeg", bytes = byteArrayOf(-1, -40, 0, 1)),
            TagPicture(mimeType = "image/jpeg", bytes = byteArrayOf(-1, -40, 0, 2)),
            TagPicture(PictureType.BACK_COVER, "image/jpeg", bytes = byteArrayOf(-1, -40, 0, 1)),
            TagPicture(PictureType.ARTIST, "image/jpeg", bytes = byteArrayOf(-1, -40, 0, 1)),
            TagPicture(mimeType = "image/jpeg", description = "caption", bytes = byteArrayOf(-1, -40, 0, 1)),
        )
        for (version in 3..4) {
            val file = plainMp3("pictures-$version")
            val audio = file.readBytes()
            val formats = setOf(if (version == 3) TagFormat.ID3V2_3 else TagFormat.ID3V2_4, TagFormat.APEV2)
            file.writeBytes(AudioKodec.writeTags(audio, TagBundle(emptyMap(), pictures,
                chapters = listOf(TagChapter("one", 0, pictures = pictures))), TagWriteOptions(formats = formats, padding = 0)))
            var size = -1
            repeat(5) { edit ->
                run(listOf(python, "-c", CHECK_PICTURES, file.path))
                val bytes = file.readBytes()
                val read = AudioKodec.readTags(bytes)
                assertEquals(5, read.pictures.size)
                val at = read.raw.blocks.first().size.toInt()
                assertContentEquals(audio, bytes.copyOfRange(at, at + audio.size))
                file.writeBytes(AudioKodec.writeTags(bytes, read.edit { set(TagField.TITLE, "Edit $edit") }, TagWriteOptions(id3Version = version, padding = 0)))
                if (edit == 0) size = file.length().toInt() else assertEquals(size.toLong(), file.length())
            }
        }
    }

    @Test
    fun independentlyWrittenMixedCopiesMergeWithoutLosingApePictureRoles() {
        val python = python
        for (version in 3..4) {
            val file = plainMp3("reference-pictures-$version")
            val audio = file.readBytes()
            run(listOf(python, "-c", WRITE_PICTURES, file.path, version.toString()))
            val read = AudioKodec.readTags(file.readBytes())
            assertEquals(listOf(PictureType.FRONT_COVER, PictureType.ARTIST, PictureType.BACK_COVER), read.pictures.map { it.type })
            assertEquals(listOf("cover", "portrait", "back.jpg"), read.pictures.map { it.description })
            repeat(3) { edit ->
                val tags = AudioKodec.readTags(file.readBytes())
                file.writeBytes(AudioKodec.writeTags(file.readBytes(), tags.edit { set(TagField.TITLE, "Edit $edit") }, TagWriteOptions(id3Version = version, padding = 0)))
                run(listOf(python, "-c", CHECK_REFERENCE_PICTURES, file.path))
                val bytes = file.readBytes()
                val at = AudioKodec.readTags(bytes).raw.blocks.first().size.toInt()
                assertContentEquals(audio, bytes.copyOfRange(at, at + audio.size))
            }
        }
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

    @Test
    fun unmodelledId3LoudnessNamesAgreeWithMutagenInBothDirections() {
        val python = python
        for (version in 3..4) {
            val file = plainMp3("private-id3-loudness-$version")
            val originalAudio = file.readBytes()
            for (fromMutagen in listOf(false, true)) {
                file.writeBytes(originalAudio)
                if (fromMutagen) run(listOf(python, "-c", WRITE_PRIVATE_ID3_LOUDNESS, file.path, version.toString()))
                else file.writeBytes(LoudnessTagFixtures.id3(version, 1, lists = version == 4, owner = true) + originalAudio)
                val source = if (fromMutagen) "mutagen" else "independent"
                run(listOf(python, "-c", CHECK_PRIVATE_ID3_LOUDNESS, file.path, version.toString(), "before", source))
                val tags = AudioKodec.readTags(file.readBytes())
                file.writeBytes(AudioKodec.writeTags(file.readBytes(), tags.edit {
                    set(TagField.TITLE, "Changed")
                    setReplayGain(null)
                }, TagWriteOptions(id3Version = version)))
                run(listOf(python, "-c", CHECK_PRIVATE_ID3_LOUDNESS, file.path, version.toString(), "after", source))
                assertContentEquals(originalAudio, file.readBytes().takeLast(originalAudio.size).toByteArray())
            }
        }
    }

    @Test
    fun unmodelledApeLoudnessNamesAndEmptyListsAgreeWithMutagenInBothDirections() {
        val python = python
        val file = plainMp3("private-ape-loudness")
        val originalAudio = file.readBytes()
        for (fromMutagen in listOf(false, true)) {
            file.writeBytes(originalAudio)
            if (fromMutagen) run(listOf(python, "-c", WRITE_PRIVATE_APE_LOUDNESS, file.path))
            else file.writeBytes(originalAudio + LoudnessTagFixtures.ape(2, lists = true))
            run(listOf(python, "-c", CHECK_PRIVATE_APE_LOUDNESS, file.path, "before"))
            file.writeBytes(AudioKodec.writeTags(file.readBytes(), AudioKodec.readTags(file.readBytes()).edit {
                set(TagField.TITLE, "Changed")
                setReplayGain(null)
            }, TagWriteOptions(formats = setOf(TagFormat.APEV2))))
            run(listOf(python, "-c", CHECK_PRIVATE_APE_LOUDNESS, file.path, "after"))
            assertContentEquals(originalAudio, file.readBytes().take(originalAudio.size).toByteArray())
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
        const val ITUNES_COMMENTS = """
import sys
from mutagen.id3 import ID3, COMM
reserved = [('eng', 'iTunNORM', '000000F0 000000F0'), ('eng', 'iTunSMPB', ' 00000000 00000840 000001F4 0000000000001000'), ('fra', 'itunnorm', 'private note'), ('deu', 'ITUNfuture', '  future 日本語\n')]
ordinary = [('eng', '', 'old note'), ('fra', 'review', 'named note')]
mode = sys.argv[2]
if mode == 'write':
    tag = ID3()
    for lang, desc, text in reserved + ordinary:
        tag.add(COMM(encoding=1, lang=lang, desc=desc, text=[text]))
    if sys.argv[3] == '3': tag.update_to_v23()
    tag.save(sys.argv[1], v2_version=int(sys.argv[3]))
else:
    frames = ID3(sys.argv[1]).getall('COMM')
    actual = sorted((f.lang, f.desc, str(f.text[0])) for f in frames)
    user = ordinary if mode == 'preserved' else [('xxx', '', '000000F0 000000F0')] if mode == 'replaced' else []
    assert actual == sorted(reserved + user), (mode, actual)
    assert len(frames) == len(reserved) + len(user)
"""
        const val ALL_APE_ROLES = """
import sys
from mutagen.apev2 import APEv2
names = ['Other', 'Png Icon', 'Icon', 'Front', 'Back', 'Leaflet', 'Media', 'Lead Artist', 'Artist', 'Conductor', 'Band', 'Composer', 'Lyricist', 'Recording Location', 'During Recording', 'During Performance', 'Video Capture', 'Fish', 'Illustration', 'Band Logotype', 'Publisher Logotype']
keys = ['Cover Art (' + name + ')' for name in names]
tag = APEv2(sys.argv[1])
if sys.argv[2] == 'write':
    tag.clear()
    for i, key in enumerate(keys): tag[key.lower()] = ('role-' + str(i) + '.jpg').encode() + b'\0' + bytes([255, 216, 0, i])
    tag.save(sys.argv[1])
else:
    assert sorted(k for k in tag if k.startswith('Cover Art')) == sorted(keys)
    for i, key in enumerate(keys): assert bytes(tag[key]) == ('role-' + str(i) + '.jpg').encode() + b'\0' + bytes([255, 216, 0, i])
"""
        const val CHECK_NATIVE_PICTURES = """
import sys, base64
from mutagen.id3 import ID3
from mutagen.flac import FLAC, Picture
from mutagen.oggopus import OggOpus
id3 = ID3(sys.argv[1]).getall('APIC')
native = FLAC(sys.argv[2]).pictures if sys.argv[3] == 'flac' else [Picture(base64.b64decode(x)) for x in OggOpus(sys.argv[2])['metadata_block_picture']]
expected = [(3, '', 'ffd80001'), (3, ' [1]', 'ffd80002')]
for pictures in [id3, native]:
    assert sorted((int(p.type), p.desc, p.data.hex()) for p in pictures) == expected
"""
        const val CHECK_OPUS_AUDIO = """
import sys, struct
def packets(path):
    data = open(path, 'rb').read(); at = 0; partial = b''; result = []
    while at < len(data):
        assert data[at:at+4] == b'OggS'
        count = data[at+26]; lacing = data[at+27:at+27+count]; payload = at+27+count
        granule = struct.unpack_from('<Q', data, at+6)[0]
        for length in lacing:
            partial += data[payload:payload+length]; payload += length
            if length < 255: result.append(partial); partial = b''
        at = payload
    assert not partial
    return result, granule
before, before_granule = packets(sys.argv[1]); after, after_granule = packets(sys.argv[2])
assert before[0] == after[0] and before[2:] == after[2:] and before_granule == after_granule
"""
        const val CHECK_PICTURES = """
import sys
from mutagen.id3 import ID3
from mutagen.apev2 import APEv2
tag = ID3(sys.argv[1])
expected = sorted([(3, 'ffd80001'), (3, 'ffd80002'), (4, 'ffd80001'), (8, 'ffd80001'), (3, 'ffd80001')])
for pictures in [tag.getall('APIC'), tag['CHAP:one'].sub_frames.getall('APIC')]:
    assert len(pictures) == 5, pictures
    assert sorted((int(p.type), p.data.hex()) for p in pictures) == expected
    assert len({p.desc for p in pictures}) == 5
ape = APEv2(sys.argv[1])
keys = ['Cover Art (Front)', 'Cover Art (Back)', 'Cover Art (Artist)']
assert sorted(k for k in ape if k.startswith('Cover Art')) == sorted(keys)
for key in keys: assert bytes(ape[key]).split(b'\0', 1)[1].hex() == 'ffd80001'
"""
        const val WRITE_PICTURES = """
import sys
from mutagen.id3 import ID3, APIC
from mutagen.apev2 import APEv2
image = bytes.fromhex('ffd80001')
tag = ID3()
tag.add(APIC(mime='image/jpeg', type=3, desc='cover', data=image))
tag.add(APIC(mime='image/jpeg', type=8, desc='portrait', data=image))
tag.save(sys.argv[1], v2_version=int(sys.argv[2]))
ape = APEv2()
for key in ['Front', 'Artist', 'Back']: ape['Cover Art (' + key + ')'] = b'back.jpg\0' + image
ape.save(sys.argv[1])
"""
        const val CHECK_REFERENCE_PICTURES = """
import sys
from mutagen.id3 import ID3
from mutagen.apev2 import APEv2
tag = ID3(sys.argv[1])
pictures = tag.getall('APIC')
assert sorted((int(p.type), p.desc, p.data.hex()) for p in pictures) == [(3, 'cover', 'ffd80001'), (4, 'back.jpg', 'ffd80001'), (8, 'portrait', 'ffd80001')]
ape = APEv2(sys.argv[1])
for key in ['Front', 'Artist', 'Back']:
    assert bytes(ape['Cover Art (' + key + ')']).split(b'\0', 1)[1].hex() == 'ffd80001'
"""
        const val WRITE_PRIVATE_ID3_LOUDNESS = """
import sys
from mutagen.id3 import ID3, TXXX, UFID
tag = ID3()
entries = [('replaygain_track_gain', '-3.0 dB'), ('REPLAYGAIN_TRACK_PEAK', '0.9'), ('ReplayGain_Album_Gain', '-4.0 dB'), ('REPLAYGAIN_ALBUM_PEAK', '0.8'), ('R128_TRACK_GAIN', '256'), ('r128_album_gain', '512'), ('REPLAYGAIN_REFERENCE_LOUDNESS', '89.0 dB'), ('replaygain_track_range', '6.0 dB'), ('replaygain_private', 'state')]
for key, value in entries: tag.add(TXXX(encoding=1, desc=key, text=[value]))
tag.add(UFID(owner='REPLAYGAIN_TRACK_GAIN', data=b'\x00\xff\x80\x07'))
if sys.argv[2] == '4': tag.add(TXXX(encoding=1, desc='REPLAYGAIN_PRIVATE_LIST', text=['', 'Björk 日本語', '', 'same', 'same', '']))
# Mutagen omits a wholly empty TXXX on save, so that case uses independent bytes.
tag.save(sys.argv[1], v2_version=int(sys.argv[2]))
"""
        const val CHECK_PRIVATE_ID3_LOUDNESS = """
import sys
from mutagen.id3 import ID3
tag = ID3(sys.argv[1])
expected = {'REPLAYGAIN_REFERENCE_LOUDNESS': ['89.0 dB'], 'replaygain_track_range': ['6.0 dB'], 'replaygain_private': ['state']}
assert tag['UFID:REPLAYGAIN_TRACK_GAIN'].data == b'\x00\xff\x80\x07'
for key, values in expected.items():
    assert tag['TXXX:' + key].text == values, (key, tag.get('TXXX:' + key))
if sys.argv[4] == 'independent': assert tag['TXXX:R128_PRIVATE'].text == ['']
if sys.argv[2] == '4':
    assert tag['TXXX:REPLAYGAIN_PRIVATE_LIST'].text == ['', 'Björk 日本語', '', 'same', 'same', '']
if sys.argv[3] == 'after':
    modelled = {'REPLAYGAIN_TRACK_GAIN', 'REPLAYGAIN_TRACK_PEAK', 'REPLAYGAIN_ALBUM_GAIN', 'REPLAYGAIN_ALBUM_PEAK', 'R128_TRACK_GAIN', 'R128_ALBUM_GAIN'}
    assert not any(frame.desc.upper() in modelled for frame in tag.getall('TXXX'))
    assert tag['TIT2'].text == ['Changed']
"""
        const val WRITE_PRIVATE_APE_LOUDNESS = """
import sys
from mutagen.apev2 import APEv2
tag = APEv2()
entries = [('replaygain_track_gain', '-3.0 dB'), ('REPLAYGAIN_TRACK_PEAK', '0.9'), ('ReplayGain_Album_Gain', '-4.0 dB'), ('REPLAYGAIN_ALBUM_PEAK', '0.8'), ('R128_TRACK_GAIN', '256'), ('r128_album_gain', '512'), ('REPLAYGAIN_REFERENCE_LOUDNESS', '89.0 dB'), ('replaygain_track_range', '6.0 dB'), ('R128_PRIVATE', ''), ('replaygain_private', 'state')]
for key, value in entries: tag[key] = value
tag['REPLAYGAIN_PRIVATE_LIST'] = ['', 'Björk 日本語', '', 'same', 'same', '']
tag['R128_PRIVATE_BYTES'] = b'\x00\xff\x80\x07'
tag.save(sys.argv[1])
"""
        const val CHECK_PRIVATE_APE_LOUDNESS = """
import sys
from mutagen.apev2 import APEv2
tag = APEv2(sys.argv[1])
expected = {'REPLAYGAIN_REFERENCE_LOUDNESS': ['89.0 dB'], 'replaygain_track_range': ['6.0 dB'], 'R128_PRIVATE': [''], 'replaygain_private': ['state'], 'REPLAYGAIN_PRIVATE_LIST': ['', 'Björk 日本語', '', 'same', 'same', '']}
for key, values in expected.items():
    assert list(tag[key]) == values, (key, list(tag[key]))
assert all(key in tag.keys() for key in expected), list(tag.keys())
assert bytes(tag['R128_PRIVATE_BYTES']) == b'\x00\xff\x80\x07'
if sys.argv[2] == 'after':
    modelled = {'REPLAYGAIN_TRACK_GAIN', 'REPLAYGAIN_TRACK_PEAK', 'REPLAYGAIN_ALBUM_GAIN', 'REPLAYGAIN_ALBUM_PEAK', 'R128_TRACK_GAIN', 'R128_ALBUM_GAIN'}
    assert not any(key.upper() in modelled for key in tag)
    assert list(tag['Title']) == ['Changed']
"""
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

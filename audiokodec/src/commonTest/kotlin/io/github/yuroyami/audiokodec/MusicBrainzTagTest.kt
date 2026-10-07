package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MusicBrainzTagTest {
    private val owner = "http://musicbrainz.org"
    private val recording = "ebf79ba5-085e-48d2-9eb8-2d992fbf0f6d"
    private val replacement = "0383dadf-2a4e-4d10-a46a-e9e041da8eb3"
    private val audio = ByteArray(31) { (it * 7).toByte() }

    @Test
    fun recordingIdentifiersAreReadFromAllId3VersionsAndTheLegacyAlias() {
        for (version in 2..4) {
            val unique = if (version == 2) "UFI" else "UFID"
            val text = if (version == 2) "TXX" else "TXXX"
            for ((id, body) in listOf(
                unique to "$owner\u0000$recording".encodeToByteArray(),
                text to byteArrayOf(0) + "MUSICBRAINZ_RECORDING_ID\u0000$recording".encodeToByteArray(),
            )) {
                val file = fixture(version, id to body) + audio
                assertEquals(recording, AudioKodec.readTags(file)[TagField.MUSICBRAINZ_RECORDING_ID], "v2.$version $id")
                val rewritten = AudioKodec.writeTags(file, AudioKodec.readTags(file), TagWriteOptions(id3Version = 4, padding = 0))
                val entries = AudioKodec.readTags(rewritten).raw.blocks.flatMap { it.entries }
                assertEquals(1, entries.count { it.key == "UFID" && it.description == owner })
                assertTrue(entries.none { it.key == "TXXX" && it.description == "MUSICBRAINZ_RECORDING_ID" })
                assertContentEquals(audio, rewritten.takeLast(audio.size).toByteArray())
            }
        }
    }

    @Test
    fun allMusicBrainzFieldsUsePicardsExactId3Identities() {
        val descriptions = mapOf(
            TagField.MUSICBRAINZ_ARTIST_ID to "MusicBrainz Artist Id",
            TagField.MUSICBRAINZ_RELEASE_ID to "MusicBrainz Album Id",
            TagField.MUSICBRAINZ_RELEASE_ARTIST_ID to "MusicBrainz Album Artist Id",
            TagField.MUSICBRAINZ_RELEASE_GROUP_ID to "MusicBrainz Release Group Id",
            TagField.MUSICBRAINZ_TRACK_ID to "MusicBrainz Release Track Id",
        )
        val fields = descriptions.keys.associateWith { listOf(replacement) } +
            (TagField.MUSICBRAINZ_RECORDING_ID to listOf(recording))
        for (version in 3..4) {
            val file = AudioKodec.writeTags(audio, TagBundle(fields), TagWriteOptions(id3Version = version, padding = 0))
            val tags = AudioKodec.readTags(file)
            assertEquals(fields, tags.fields)
            val entries = tags.raw.blocks.flatMap { it.entries }
            val identifier = entries.single { it.key == "UFID" }
            assertEquals(owner, identifier.description)
            assertContentEquals(recording.encodeToByteArray(), identifier.binary)
            assertEquals(descriptions.values.toSet(), entries.filter { it.key == "TXXX" }.map { it.description }.toSet())
            assertContentEquals(audio, file.takeLast(audio.size).toByteArray())
        }
    }

    @Test
    fun replacingOrRemovingTheRecordingIdPreservesOtherOwnersAndAudio() {
        val other = byteArrayOf(0, 1, 0xFF.toByte(), 2)
        val original = fixture(4,
            "UFID" to "$owner\u0000$recording".encodeToByteArray(),
            "UFID" to "https://example.org\u0000".encodeToByteArray() + other,
        ) + audio
        for (version in 3..4) {
            for (remove in listOf(false, true)) {
                val edited = AudioKodec.readTags(original).edit {
                    set(TagField.TITLE, "New title")
                    if (remove) remove(TagField.MUSICBRAINZ_RECORDING_ID) else set(TagField.MUSICBRAINZ_RECORDING_ID, replacement)
                }
                val result = AudioKodec.writeTags(original, edited, TagWriteOptions(id3Version = version, padding = 0))
                val read = AudioKodec.readTags(result)
                assertEquals(if (remove) null else replacement, read[TagField.MUSICBRAINZ_RECORDING_ID])
                val entries = read.raw.blocks.flatMap { it.entries }
                assertEquals(if (remove) 0 else 1, entries.count { it.key == "UFID" && it.description == owner })
                assertContentEquals(other, entries.single { it.key == "UFID" && it.description == "https://example.org" }.binary)
                assertContentEquals(audio, result.takeLast(audio.size).toByteArray())
            }
        }
    }

    @Test
    fun malformedRecordingIdentifiersStayRawAndCanBeSupersededExplicitly() {
        for (value in listOf(byteArrayOf(), byteArrayOf(0, 1), byteArrayOf(0xFF.toByte()), "x".repeat(65).encodeToByteArray())) {
            val original = fixture(4, "UFID" to "$owner\u0000".encodeToByteArray() + value) + audio
            val tags = AudioKodec.readTags(original)
            assertEquals(null, tags[TagField.MUSICBRAINZ_RECORDING_ID])
            val unchanged = AudioKodec.writeTags(original, tags.edit { set(TagField.TITLE, "Edited") })
            assertContentEquals(value, AudioKodec.readTags(unchanged).raw.blocks.flatMap { it.entries }.single { it.key == "UFID" }.binary)
            val changed = AudioKodec.writeTags(original, tags.edit { set(TagField.MUSICBRAINZ_RECORDING_ID, replacement) })
            val entries = AudioKodec.readTags(changed).raw.blocks.flatMap { it.entries }
            assertEquals(1, entries.count { it.key == "UFID" && it.description == owner })
            assertEquals(replacement, AudioKodec.readTags(changed)[TagField.MUSICBRAINZ_RECORDING_ID])
            assertContentEquals(audio, changed.takeLast(audio.size).toByteArray())
        }
        val otherOwner = fixture(4, "UFID" to "https://musicbrainz.org\u0000$recording".encodeToByteArray())
        assertEquals(null, AudioKodec.readTags(otherOwner)[TagField.MUSICBRAINZ_RECORDING_ID])
    }

    @Test
    fun recordingWritesRefuseUnrepresentableValuesBeforeOutput() {
        val invalid = listOf(listOf(recording, replacement), listOf(""), listOf("x".repeat(65)), listOf("é"), listOf("a\u0000b"), listOf("a b"))
        for (version in 3..4) {
            for (values in invalid) {
                val sink = ByteArrayAudioSink()
                assertFailsWith<UnsupportedFeatureException> {
                    AudioKodec.writeTags(audio.asAudioSource(), TagBundle(mapOf(TagField.MUSICBRAINZ_RECORDING_ID to values)), sink, TagWriteOptions(id3Version = version))
                }
                assertEquals(0L, sink.position)
            }
            for (values in listOf(listOf(recording, recording), listOf("x".repeat(64)))) {
                val written = AudioKodec.writeTags(audio, TagBundle(mapOf(TagField.MUSICBRAINZ_RECORDING_ID to values)), TagWriteOptions(id3Version = version))
                assertEquals(values.first(), AudioKodec.readTags(written)[TagField.MUSICBRAINZ_RECORDING_ID])
                assertEquals(1, AudioKodec.readTags(written).raw.blocks.flatMap { it.entries }.count { it.key == "UFID" })
            }
        }
    }

    @Test
    fun anOpaqueOwnerCannotBeSilentlyDeletedOrCombinedWithANewIdentifier() {
        val opaque = fixture(4, "UFID" to byteArrayOf(7, 1, 2, 3)).also { it[19] = 4 }
        val original = opaque + audio
        val tags = AudioKodec.readTags(original)
        val kept = AudioKodec.writeTags(original, tags.edit { set(TagField.TITLE, "Title") }, TagWriteOptions(id3Version = 4))
        assertContentEquals(byteArrayOf(7, 1, 2, 3), AudioKodec.readTags(kept).raw.blocks.flatMap { it.entries }.single { it.key == "UFID" }.binary)
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.writeTags(original, tags.edit { set(TagField.MUSICBRAINZ_RECORDING_ID, recording) }, TagWriteOptions(id3Version = 4))
        }
    }

    private fun fixture(version: Int, vararg frames: Pair<String, ByteArray>): ByteArray {
        var body = byteArrayOf()
        for ((id, value) in frames) {
            val size = when (version) {
                2 -> byteArrayOf((value.size shr 16).toByte(), (value.size shr 8).toByte(), value.size.toByte())
                3 -> byteArrayOf((value.size shr 24).toByte(), (value.size shr 16).toByte(), (value.size shr 8).toByte(), value.size.toByte())
                else -> syncsafe(value.size)
            }
            body += id.encodeToByteArray() + size + (if (version == 2) byteArrayOf() else byteArrayOf(0, 0)) + value
        }
        return "ID3".encodeToByteArray() + byteArrayOf(version.toByte(), 0, 0) + syncsafe(body.size) + body
    }

    private fun syncsafe(size: Int): ByteArray = ByteArray(4) { at -> ((size ushr ((3 - at) * 7)) and 127).toByte() }
}

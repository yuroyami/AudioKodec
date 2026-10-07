package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Id3MultiValueTest {
    private val audio = ByteArray(21) { 0x55 }

    @Test
    fun version24StoresSeveralUserTextValuesInOneFrame() {
        val values = listOf("catalogue A", "日本語 catalogue B", "third/part")
        val written = AudioKodec.writeTags(audio, TagBundle(mapOf(TagField.CATALOG_NUMBER to values)), TagWriteOptions(id3Version = 4, padding = 0))
        assertEquals(listOf("TXXX"), frameIds(written))
        assertEquals(values, AudioKodec.readTags(written).all(TagField.CATALOG_NUMBER))
        assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
    }

    @Test
    fun version23UsesTheDefinedArtistComposerAndLyricistSlashLists() {
        val fields = mapOf(TagField.ARTIST to listOf("Alpha", "Björk 日本語"), TagField.COMPOSER to listOf("First", "Second"), TagField.LYRICIST to listOf("One", "Two"))
        val written = AudioKodec.writeTags(audio, TagBundle(fields), TagWriteOptions(id3Version = 3, padding = 0))
        assertEquals(listOf("TPE1", "TCOM", "TEXT"), frameIds(written))
        assertEquals(fields, AudioKodec.readTags(written).fields)
        assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
    }

    @Test
    fun version23RefusesAmbiguousOrUnsupportedListsBeforeWriting() {
        for ((field, values) in listOf(
            TagField.ARTIST to listOf("AC/DC"),
            TagField.COMPOSER to listOf("First/Second", "Third"),
            TagField.TITLE to listOf("One", "Two"),
            TagField.CATALOG_NUMBER to listOf("One", "Two"),
            TagField.MUSICBRAINZ_ARTIST_ID to listOf("One", "Two"),
        )) {
            val sink = ByteArrayAudioSink()
            assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags(audio.asAudioSource(), TagBundle(mapOf(field to values)), sink, TagWriteOptions(id3Version = 3))
            }
            assertEquals(0L, sink.position)
            val version24 = AudioKodec.writeTags(audio, TagBundle(mapOf(field to values)), TagWriteOptions(id3Version = 4))
            assertEquals(values, AudioKodec.readTags(version24).all(field))
        }
    }

    @Test
    fun repeatedScalarValuesDoNotCreateDuplicateVersion23Identities() {
        for (field in listOf(TagField.TITLE, TagField.CATALOG_NUMBER)) {
            val written = AudioKodec.writeTags(audio, TagBundle(mapOf(field to listOf("same", "same"))), TagWriteOptions(id3Version = 3, padding = 0))
            assertEquals(1, frameIds(written).size)
            assertEquals(listOf("same"), AudioKodec.readTags(written).all(field))
        }
    }

    @Test
    fun unknownUserTextValuesAreReadAndRewrittenWithOneIdentity() {
        val values = listOf("first", "second")
        val body = byteArrayOf(3) + "Private description\u0000first\u0000second".encodeToByteArray()
        val header = "TXXX".encodeToByteArray() + syncsafe(body.size) + byteArrayOf(0, 0)
        val original = "ID3".encodeToByteArray() + byteArrayOf(4, 0, 0) + syncsafe(header.size + body.size) + header + body + audio
        val tags = AudioKodec.readTags(original)
        assertEquals(values, tags.raw.blocks.flatMap { it.entries }.map { it.text })
        val written = AudioKodec.writeTags(original, tags, TagWriteOptions(id3Version = 4, padding = 0))
        assertEquals(listOf("TXXX"), frameIds(written))
        assertEquals(values, AudioKodec.readTags(written).raw.blocks.flatMap { it.entries }.map { it.text })
        assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
        assertFailsWith<UnsupportedFeatureException> { AudioKodec.writeTags(original, tags, TagWriteOptions(id3Version = 3)) }
    }

    @Test
    fun originalArtistAndLyricistListsKeepTheirRawValuesAndSingleFrames() {
        val parts = listOf("One", "Two")
        var body = byteArrayOf()
        for (key in listOf("TOPE", "TOLY")) {
            val text = byteArrayOf(0) + parts.joinToString("/").encodeToByteArray()
            body += key.encodeToByteArray() + byteArrayOf(0, 0, 0, text.size.toByte(), 0, 0) + text
        }
        val original = "ID3".encodeToByteArray() + byteArrayOf(3, 0, 0) + syncsafe(body.size) + body + audio
        val tags = AudioKodec.readTags(original)
        for (key in listOf("TOPE", "TOLY")) {
            assertEquals(parts, tags.raw.blocks.flatMap { it.entries }.filter { it.key == key }.map { it.text })
        }
        val written = AudioKodec.writeTags(original, tags, TagWriteOptions(id3Version = 3, padding = 0))
        assertEquals(listOf("TOPE", "TOLY"), frameIds(written))
        assertContentEquals(original, written)
    }

    @Test
    fun opaqueUserTextDescriptionsArePreservedOrExplicitlyRefused() {
        val payload = byteArrayOf(7, 1, 2, 3)
        val frame = "TXXX".encodeToByteArray() + syncsafe(payload.size) + byteArrayOf(0, 4) + payload
        val original = "ID3".encodeToByteArray() + byteArrayOf(4, 0, 0) + syncsafe(frame.size) + frame + audio
        val tags = AudioKodec.readTags(original)
        val kept = AudioKodec.writeTags(original, tags.edit { set(TagField.TITLE, "Title") })
        assertContentEquals(payload, AudioKodec.readTags(kept).raw.blocks.flatMap { it.entries }.single { it.key == "TXXX" }.binary)
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.writeTags(original, tags.edit { set(TagField.CATALOG_NUMBER, "catalogue") })
        }
    }

    @Test
    fun anEmptyUnknownUserTextValueRetainsItsIdentity() {
        val body = byteArrayOf(3) + "Private empty value\u0000".encodeToByteArray()
        val frame = "TXXX".encodeToByteArray() + syncsafe(body.size) + byteArrayOf(0, 0) + body
        val original = "ID3".encodeToByteArray() + byteArrayOf(4, 0, 0) + syncsafe(frame.size) + frame + audio
        val tags = AudioKodec.readTags(original)
        assertEquals("", tags.raw.blocks.flatMap { it.entries }.single().text)
        val written = AudioKodec.writeTags(original, tags, TagWriteOptions(id3Version = 4, padding = 0))
        assertEquals(listOf("TXXX"), frameIds(written))
        val entry = AudioKodec.readTags(written).raw.blocks.flatMap { it.entries }.single()
        assertEquals("Private empty value", entry.description)
        assertEquals("", entry.text)
        assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
    }

    private fun frameIds(bytes: ByteArray): List<String> {
        val ids = mutableListOf<String>()
        var at = 10
        val end = 10 + (0..3).fold(0) { size, i -> (size shl 7) or (bytes[6 + i].toInt() and 127) }
        while (at + 10 <= end && bytes[at].toInt() != 0) {
            ids.add(bytes.decodeToString(at, at + 4))
            val shift = if (bytes[3].toInt() == 4) 7 else 8
            val mask = if (shift == 7) 127 else 255
            val size = (0..3).fold(0) { size, i -> (size shl shift) or (bytes[at + 4 + i].toInt() and mask) }
            at += 10 + size
        }
        return ids
    }

    private fun syncsafe(size: Int): ByteArray = ByteArray(4) { at -> ((size ushr ((3 - at) * 7)) and 127).toByte() }
}

package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import io.github.yuroyami.audiokodec.tag.RawTagBlock
import io.github.yuroyami.audiokodec.tag.RawTagEntry
import io.github.yuroyami.audiokodec.tag.RawTags
import io.github.yuroyami.audiokodec.tag.TagFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Id3MultiValueTest {
    private val audio = ByteArray(21) { 0x55 }

    @Test
    fun mappedAndCustomVersion24ListsSurviveAnUnrelatedEditInEveryEncoding() {
        for (encoding in Id3TextListFixtures.encodings) {
            for (terminated in listOf(false, true)) {
                val original = Id3TextListFixtures.tag(encoding, terminated) + audio
                val before = AudioKodec.readTags(original)
                assertTextLists(before, encoding)
                assertEquals("Original", before[TagField.TITLE])
                val written = AudioKodec.writeTags(original, before.edit { set(TagField.TITLE, "Changed") }, TagWriteOptions(id3Version = 4, padding = 0))
                val after = AudioKodec.readTags(written)
                assertTextLists(after, encoding)
                assertEquals("Changed", after[TagField.TITLE])
                val ids = frameIds(written)
                assertEquals(6, ids.size)
                assertEquals(3, ids.count { it == "TXXX" })
                for (key in listOf("TIT2", "TPE1", "TOPE")) assertEquals(1, ids.count { it == key })
                assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
            }
        }
    }

    @Test
    fun unmodelledRepeatedValuesKeepTheirMultiplicityAndRefuseAnUnsupportedDowngrade() {
        val entries = listOf(
            RawTagEntry("TXXX", text = "same", description = "Private duplicates"),
            RawTagEntry("TXXX", text = "same", description = "Private duplicates"),
        )
        val tags = TagBundle(mapOf(TagField.TITLE to listOf("Title")), raw = RawTags(listOf(RawTagBlock(TagFormat.ID3V2_4, 0, 0, entries))))
        val written = AudioKodec.writeTags(audio, tags, TagWriteOptions(id3Version = 4, padding = 0))
        val after = AudioKodec.readTags(written)
        assertEquals(listOf("same", "same"), after.raw.blocks.flatMap { it.entries }.filter { it.key == "TXXX" }.map { it.text })
        val sink = ByteArrayAudioSink()
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.writeTags(written.asAudioSource(), after, sink, TagWriteOptions(id3Version = 3, padding = 0))
        }
        assertEquals(0L, sink.position)
        assertContentEquals(audio, written.takeLast(audio.size).toByteArray())

        // A v2.3 original-artist slash list can keep repeated raw values.
        val values = listOf("One", "Two", "One")
        val artists = TagBundle(mapOf(TagField.TITLE to listOf("Title")), raw = RawTags(listOf(
            RawTagBlock(TagFormat.ID3V2_4, 0, 0, values.map { RawTagEntry("TOPE", text = it) }),
        )))
        val version23 = AudioKodec.writeTags(audio, artists, TagWriteOptions(id3Version = 3, padding = 0))
        assertEquals(values, AudioKodec.readTags(version23).raw.blocks.flatMap { it.entries }.filter { it.key == "TOPE" }.map { it.text })
        assertContentEquals(audio, version23.takeLast(audio.size).toByteArray())
    }

    @Test
    fun downgradingReadVersion24UserTextListsIsRefusedBeforeWriting() {
        for (encoding in Id3TextListFixtures.encodings) {
            val original = Id3TextListFixtures.tag(encoding) + audio
            val snapshot = original.copyOf()
            val tags = AudioKodec.readTags(original).edit { set(TagField.TITLE, "Changed") }
            val sink = ByteArrayAudioSink()
            val failure = assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags(original.asAudioSource(), tags, sink, TagWriteOptions(id3Version = 3, padding = 0))
            }
            assertTrue(failure.message.orEmpty().contains("TXXX"))
            assertEquals(0L, sink.position)
            assertContentEquals(snapshot, original)
        }
    }

    private fun assertTextLists(tags: TagBundle, encoding: Id3TextListFixtures.Encoding) {
        assertEquals(Id3TextListFixtures.artists(encoding), tags.all(TagField.ARTIST), encoding.toString())
        assertEquals(Id3TextListFixtures.identifiers, tags.all(TagField.MUSICBRAINZ_ARTIST_ID), encoding.toString())
        val raw = tags.raw.blocks.flatMap { it.entries }
        assertEquals(Id3TextListFixtures.originalArtists(encoding), raw.filter { it.key == "TOPE" }.map { it.text })
        for ((description, values) in listOf(
            "MusicBrainz Artist Id" to Id3TextListFixtures.identifiers,
            Id3TextListFixtures.description(encoding) to Id3TextListFixtures.customValues(encoding),
            "" to Id3TextListFixtures.emptyDescriptionValues,
        )) assertEquals(values, raw.filter { it.key == "TXXX" && it.description == description }.map { it.text }, encoding.toString())
    }

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

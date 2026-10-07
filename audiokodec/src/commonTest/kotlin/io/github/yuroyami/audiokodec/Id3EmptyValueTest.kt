package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.RawTagBlock
import io.github.yuroyami.audiokodec.tag.RawTagEntry
import io.github.yuroyami.audiokodec.tag.RawTags
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Id3EmptyValueTest {
    private val audio = ByteArray(21) { 0x55 }

    @Test
    fun emptyValuesSurviveReadingAndAnUnrelatedEditInEveryEncoding() {
        for (encoding in Id3TextListFixtures.encodings) {
            for (values in Id3EmptyValueFixtures.valuesFor(encoding)) {
                // Without a terminator, a BOM still makes the final empty string explicit.
                val endings = if (encoding.code == 1 || values.last().isNotEmpty()) listOf(false, true) else listOf(true)
                for (terminated in endings) {
                    val original = Id3EmptyValueFixtures.tag(encoding, values, terminated) + audio
                    val before = AudioKodec.readTags(original)
                    assertValues(before, values)
                    val written = AudioKodec.writeTags(original, before.edit { set(TagField.TITLE, "Changed") }, TagWriteOptions(id3Version = 4, padding = 0))
                    assertValues(AudioKodec.readTags(written), values)
                    assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
                }
            }
        }
    }

    @Test
    fun writingRawListsRetainsTheirFinalEmptyValues() {
        for (values in Id3EmptyValueFixtures.lists + Id3EmptyValueFixtures.unicodeLists) {
            val written = AudioKodec.writeTags(audio, rawTags(values), TagWriteOptions(id3Version = 4, padding = 0))
            assertValues(AudioKodec.readTags(written), values)
            assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
        }
    }

    @Test
    fun namedVersion23SlashListsKeepEmptyPiecesAndOtherListsRefuseDowngrade() {
        for (values in Id3EmptyValueFixtures.lists + Id3EmptyValueFixtures.unicodeLists) {
            for (key in listOf("TOPE", "TOLY")) {
                val tags = rawTags(values, key)
                val written = AudioKodec.writeTags(audio, tags, TagWriteOptions(id3Version = 3, padding = 0))
                assertEquals(values, rawValues(AudioKodec.readTags(written), key))
                assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
            }
            if (values.size > 1) {
                for (key in listOf("TXXX", "TOAL")) {
                    val sink = ByteArrayAudioSink()
                    assertFailsWith<UnsupportedFeatureException> {
                        AudioKodec.writeTags(audio.asAudioSource(), rawTags(values, key), sink, TagWriteOptions(id3Version = 3, padding = 0))
                    }
                    assertEquals(0L, sink.position)
                }
            }
        }
    }

    @Test
    fun aUnicodeDescriptionKeepsOneEmptyValueInBothWritableVersions() {
        for (version in 3..4) {
            val written = AudioKodec.writeTags(audio, rawTags(listOf(""), "TXXX", "Private 日本語"), TagWriteOptions(id3Version = version, padding = 0))
            val entry = AudioKodec.readTags(written).raw.blocks.flatMap { it.entries }.single()
            assertEquals("Private 日本語", entry.description)
            assertEquals("", entry.text)
            assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
        }
    }

    @Test
    fun mergedFieldsOmitEmptyValuesWhileTheRawViewRetainsThem() {
        val original = Id3EmptyValueFixtures.singleFrame("TPE1", byteArrayOf(3, 0, 65, 0, 0)) + audio
        val tags = AudioKodec.readTags(original)
        assertEquals(listOf("A"), tags.all(TagField.ARTIST))
        assertEquals(listOf("", "A", ""), rawValues(tags, "TPE1"))
    }

    @Test
    fun malformedTextDoesNotBecomeAValidEmptyRawValue() {
        for ((encoding, malformed) in listOf(
            3 to byteArrayOf(0xC0.toByte(), 0x80.toByte()),
            3 to byteArrayOf(0xE2.toByte(), 0x82.toByte()),
            1 to byteArrayOf(0xFF.toByte()),
            1 to byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41),
            2 to byteArrayOf(0x41),
        )) {
            for (key in listOf("TOPE", "TXXX")) {
                val prefix = if (key == "TXXX") ByteArray(if (encoding == 1 || encoding == 2) 2 else 1) else byteArrayOf()
                val original = Id3EmptyValueFixtures.singleFrame(key, byteArrayOf(encoding.toByte()) + prefix + malformed) + audio
                for (version in 3..4) {
                    val versioned = original.copyOf().also { it[3] = version.toByte() }
                    assertEquals(emptyList(), rawValues(AudioKodec.readTags(versioned), key))
                }
            }
        }
        val invalidDescription = Id3EmptyValueFixtures.singleFrame("TXXX", byteArrayOf(3, 0xC0.toByte(), 0x80.toByte(), 0) + "value".encodeToByteArray()) + audio
        assertEquals(emptyList(), rawValues(AudioKodec.readTags(invalidDescription), "TXXX"))
    }

    private fun assertValues(tags: TagBundle, values: List<String>) {
        for (key in listOf("TOPE", "TXXX")) assertEquals(values, rawValues(tags, key), key)
    }

    private fun rawValues(tags: TagBundle, key: String): List<String?> =
        tags.raw.blocks.flatMap { it.entries }.filter { it.key == key }.map { it.text }

    private fun rawTags(values: List<String>, key: String? = null, description: String = Id3EmptyValueFixtures.description): TagBundle {
        val keys = if (key == null) listOf("TOPE", "TXXX") else listOf(key)
        val entries = keys.flatMap { id -> values.map { RawTagEntry(id, text = it, description = if (id == "TXXX") description else null) } }
        return TagBundle(emptyMap(), raw = RawTags(listOf(RawTagBlock(TagFormat.ID3V2_4, 0, 0, entries))))
    }
}

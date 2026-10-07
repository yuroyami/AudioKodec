package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.tag.TagAssembler
import io.github.yuroyami.audiokodec.tag.RawTagBlock
import io.github.yuroyami.audiokodec.tag.RawTagEntry
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TagTextPreservationTest {
    private val audio = ByteArray(32) { 0x55 }

    @Test
    fun unrelatedEditsKeepExactTextAndEveryIdentityAcrossVersionsAndEncodings() {
        for (inputVersion in 2..4) {
            val encodings = if (inputVersion == 4) 0..3 else 0..1
            for (encoding in encodings) {
                val entries = entries(encoding != 0)
                val original = fixture(inputVersion, encoding, entries) + audio
                val before = AudioKodec.readTags(original)
                assertText(entries, before)
                assertEquals("Old", before[TagField.TITLE])
                for (outputVersion in 3..4) {
                    val label = "ID3v2.$inputVersion encoding $encoding to ID3v2.$outputVersion"
                    val written = AudioKodec.writeTags(
                        original,
                        before.edit { set(TagField.TITLE, "Changed") },
                        TagWriteOptions(id3Version = outputVersion, padding = 0),
                    )
                    val after = AudioKodec.readTags(written)
                    assertText(entries, after)
                    assertEquals(identities(before), identities(after), label)
                    assertEquals("Changed", after[TagField.TITLE], label)
                    assertContentEquals(audio, written.takeLast(audio.size).toByteArray(), label)
                }
            }
        }
    }

    @Test
    fun replacingRemovingAndAddingFreeFormTextAppliesTheExactRequestedValues() {
        val original = fixture(4, 3, entries(true)) + audio
        val before = AudioKodec.readTags(original)
        for (version in 3..4) {
            val options = TagWriteOptions(id3Version = version, padding = 0)
            val replaced = AudioKodec.writeTags(original, before.edit {
                set(TagField.COMMENT, listOf("gleich\n", "bonjour", "new\n"))
                set(TagField.LYRICS, "la la")
            }, options)
            val after = AudioKodec.readTags(replaced)
            assertEquals(listOf("gleich\n", "bonjour", "new\n"), after.all(TagField.COMMENT))
            assertEquals(listOf("la la"), after.all(TagField.LYRICS))
            assertEquals(listOf(
                listOf("COMM", "deu", "a", "gleich\n"),
                listOf("COMM", "deu", "b", "gleich\n"),
                listOf("COMM", "xxx", "", "bonjour"),
                listOf("COMM", "xxx", "2", "new\n"),
                listOf("USLT", "xxx", "", "la la"),
            ), identities(after))
            assertContentEquals(audio, replaced.takeLast(audio.size).toByteArray())

            val removed = AudioKodec.writeTags(original, before.edit {
                remove(TagField.COMMENT)
                set(TagField.LYRICS, emptyList())
            }, options)
            assertTrue(identities(AudioKodec.readTags(removed)).isEmpty())
            assertContentEquals(audio, removed.takeLast(audio.size).toByteArray())

            val added = AudioKodec.writeTags(original, before.edit {
                add(TagField.COMMENT, " \t ")
                add(TagField.LYRICS, "\n\n")
            }, options)
            val addedTags = AudioKodec.readTags(added)
            assertEquals(before.all(TagField.COMMENT) + " \t ", addedTags.all(TagField.COMMENT))
            assertEquals(before.all(TagField.LYRICS) + "\n\n", addedTags.all(TagField.LYRICS))
            assertEquals(identities(before), identities(addedTags).filter { it[1] != "xxx" })
            assertContentEquals(audio, added.takeLast(audio.size).toByteArray())
        }
    }

    @Test
    fun everyMappedFreeFormFieldKeepsWhitespaceAndExactDuplicateSemantics() {
        data class Keys(val format: TagFormat, val title: String?, val comment: String?, val lyrics: String?)
        val formats = listOf(
            Keys(TagFormat.ID3V2_2, "TT2", "COM", "ULT"),
            Keys(TagFormat.ID3V2_3, "TIT2", "COMM", "USLT"),
            Keys(TagFormat.ID3V2_4, "TIT2", "COMM", "USLT"),
            Keys(TagFormat.VORBIS_COMMENT, "TITLE", "COMMENT", "LYRICS"),
            Keys(TagFormat.APEV1, "TITLE", "COMMENT", "LYRICS"),
            Keys(TagFormat.APEV2, "TITLE", "COMMENT", "LYRICS"),
            Keys(TagFormat.MP4_ILST, "©nam", "©cmt", "©lyr"),
            Keys(TagFormat.RIFF_INFO, "INAM", "ICMT", null),
            Keys(TagFormat.AIFF_TEXT, "NAME", "ANNO", null),
            Keys(TagFormat.ID3V1, "TITLE", "COMMENT", null),
        )
        val values = listOf(" value\n", "value\n", " value\n", " \t\r\n ")
        for (keys in formats) {
            val raw = buildList {
                keys.title?.let { add(RawTagEntry(it, text = " Title ")) }
                for (key in listOfNotNull(keys.comment, keys.lyrics)) {
                    for (value in values) add(RawTagEntry(key, text = value))
                }
            }
            val tags = TagAssembler.assemble(listOf(RawTagBlock(keys.format, 0, 0, raw)))
            keys.title?.let { assertEquals("Title", tags[TagField.TITLE], keys.format.displayName) }
            keys.comment?.let { assertEquals(values.distinct(), tags.all(TagField.COMMENT), keys.format.displayName) }
            keys.lyrics?.let { assertEquals(values.distinct(), tags.all(TagField.LYRICS), keys.format.displayName) }
            assertEquals(raw, tags.raw.blocks.single().entries)
        }

        // An explicitly stated whitespace-only higher-precedence value wins.
        val higher = RawTagBlock(TagFormat.ID3V2_4, 0, 0, listOf(RawTagEntry("COMM", text = "\n")))
        val lower = RawTagBlock(TagFormat.APEV2, 0, 0, listOf(RawTagEntry("COMMENT", text = "stale")))
        assertEquals(listOf("\n"), TagAssembler.assemble(listOf(lower, higher)).all(TagField.COMMENT))
    }

    @Test
    fun newTextRoundTripsExactlyThroughId3ApeAndFlacAndAnUnrelatedEdit() {
        val fields = mapOf(
            TagField.COMMENT to listOf(" comment \r\n", "\t\n "),
            TagField.LYRICS to listOf(" 日本語\n", "\n\n"),
        )
        val tags = TagBundle(fields)
        val plainFlac = TagStressFixtures.flac(emptyList())
        val cases = listOf(
            audio to TagWriteOptions(id3Version = 3, padding = 0),
            audio to TagWriteOptions(id3Version = 4, padding = 0),
            audio to TagWriteOptions(formats = setOf(TagFormat.APEV2)),
            plainFlac to TagWriteOptions.DEFAULT,
        )
        for ((original, options) in cases) {
            val written = AudioKodec.writeTags(original, tags, options)
            val before = AudioKodec.readTags(written)
            assertEquals(fields, before.fields)
            val edited = AudioKodec.writeTags(written, before.edit { set(TagField.TITLE, "Changed") }, options)
            val after = AudioKodec.readTags(edited)
            for ((field, values) in fields) assertEquals(values, after.all(field))
            if (original === plainFlac) {
                assertContentEquals(flacPayload(original), flacPayload(written))
                assertContentEquals(flacPayload(original), flacPayload(edited))
                assertContentEquals(AudioKodec.decode(original).samples, AudioKodec.decode(edited).samples)
            } else if (TagFormat.APEV2 in options.formats.orEmpty()) {
                // APE is appended after the audio, while ID3 precedes it.
                assertEquals(audio.size.toLong(), before.raw.blocks.single().offset)
                assertEquals(audio.size.toLong(), after.raw.blocks.single().offset)
                assertContentEquals(audio, written.copyOfRange(0, audio.size))
                assertContentEquals(audio, edited.copyOfRange(0, audio.size))
            } else assertContentEquals(audio, edited.takeLast(audio.size).toByteArray())
        }
    }

    private data class Entry(val key: String, val language: String, val description: String, val text: String)

    private fun entries(unicode: Boolean): List<Entry> = listOf(
        Entry("COMM", "fra", "review", "\t bonjour \r\n"),
        Entry("COMM", "eng", "padding", "\u00A0 padded \u00A0"),
        Entry("COMM", "deu", "a", "gleich\n"),
        Entry("COMM", "deu", "b", "gleich\n"),
        Entry("COMM", "ita", "blank", " \t\r\n "),
        Entry("USLT", "jpn", "verse", if (unicode) " 日本語\n" else "la la\n"),
        Entry("USLT", "eng", "verse", "la la\n"),
        Entry("USLT", "fra", "verse", "\tla la\r\n"),
        Entry("USLT", "deu", "verse", "\nla la\n"),
    )

    private fun assertText(entries: List<Entry>, tags: TagBundle) {
        for ((key, field) in listOf("COMM" to TagField.COMMENT, "USLT" to TagField.LYRICS)) {
            assertEquals(entries.filter { it.key == key }.map { it.text }.distinct(), tags.all(field))
        }
    }

    private fun identities(tags: TagBundle): List<List<String?>> = tags.raw.blocks.flatMap { it.entries }
        .filter { it.key in listOf("COMM", "COM", "USLT", "ULT") }
        .map { listOf(if (it.key == "COM") "COMM" else if (it.key == "ULT") "USLT" else it.key, it.language, it.description, it.text) }

    private fun flacPayload(bytes: ByteArray): ByteArray {
        var offset = 4
        while (true) {
            val last = bytes[offset].toInt() and 128 != 0
            val length = ((bytes[offset + 1].toInt() and 255) shl 16) or
                ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
            offset += 4 + length
            if (last) return bytes.copyOfRange(offset, bytes.size)
        }
    }

    /** Independent ID3 layouts from the published frame specifications. */
    private fun fixture(version: Int, encoding: Int, entries: List<Entry>): ByteArray {
        fun text(value: String): ByteArray = when (encoding) {
            0 -> ByteArray(value.length) { value[it].code.toByte() }
            1 -> byteArrayOf(-1, -2) + ByteArray(value.length * 2) {
                (value[it / 2].code ushr if (it % 2 == 0) 0 else 8).toByte()
            }
            2 -> ByteArray(value.length * 2) {
                (value[it / 2].code ushr if (it % 2 == 0) 8 else 0).toByte()
            }
            else -> value.encodeToByteArray()
        }
        fun frame(id: String, payload: ByteArray): ByteArray = id.encodeToByteArray() + when (version) {
            2 -> ByteArray(3) { (payload.size ushr (16 - it * 8)).toByte() }
            3 -> ByteArray(4) { (payload.size ushr (24 - it * 8)).toByte() } + byteArrayOf(0, 0)
            else -> syncsafe(payload.size) + byteArrayOf(0, 0)
        } + payload
        var body = frame(if (version == 2) "TT2" else "TIT2", byteArrayOf(0) + " Old ".encodeToByteArray())
        val terminator = ByteArray(if (encoding == 1 || encoding == 2) 2 else 1)
        for (entry in entries) {
            val id = if (version == 2) if (entry.key == "COMM") "COM" else "ULT" else entry.key
            body += frame(id, byteArrayOf(encoding.toByte()) + entry.language.encodeToByteArray() +
                text(entry.description) + terminator + text(entry.text))
        }
        return "ID3".encodeToByteArray() + byteArrayOf(version.toByte(), 0, 0) + syncsafe(body.size) + body
    }

    private fun syncsafe(value: Int): ByteArray = ByteArray(4) { ((value ushr (21 - it * 7)) and 0x7F).toByte() }
}

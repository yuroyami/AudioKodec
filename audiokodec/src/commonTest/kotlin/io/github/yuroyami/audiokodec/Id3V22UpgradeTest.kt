package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Id3V22UpgradeTest {
    private val audio = ByteArray(41) { (it * 19).toByte() }

    @Test
    fun standardUnmodelledTextAndBinaryFramesUpgradeWithoutBlockingEdits() {
        val originals = listOf(
            Id3V22UpgradeFixtures.text("TT2", "Old"),
            Id3V22UpgradeFixtures.text("TLE", "12345"),
            Id3V22UpgradeFixtures.text("TDY", "250"),
            Id3V22UpgradeFixtures.text("TFT", "MPG/3"),
            Id3V22UpgradeFixtures.text("TOF", "original.wav"),
            "CNT" to byteArrayOf(0, 0, 1, 0),
            "BUF" to byteArrayOf(0, 0x20, 0, 0, 0, 0, 0, 0),
            "GEO" to (byteArrayOf(0) + "application/octet-stream\u0000file.bin\u0000blob\u0000data".encodeToByteArray()),
        )
        val original = Id3V22UpgradeFixtures.tag(originals) + audio
        for (major in 3..4) {
            var written = original
            repeat(3) { edit ->
                val tags = AudioKodec.readTags(written).edit { set(TagField.TITLE, "Edit $edit") }
                written = AudioKodec.writeTags(written, tags, TagWriteOptions(id3Version = major, padding = 0))
                assertEquals(major, written[3].toInt())
                val after = AudioKodec.readTags(written)
                assertEquals("Edit $edit", after[TagField.TITLE])
                val entries = after.raw.blocks.flatMap { it.entries }.associateBy { it.key }
                for ((key, value) in listOf("TLEN" to "12345", "TDLY" to "250", "TFLT" to "MPG/3", "TOFN" to "original.wav")) {
                    assertEquals(value, entries[key]?.text, "ID3v2.$major $key")
                }
                for ((old, key) in listOf("CNT" to "PCNT", "BUF" to "RBUF", "GEO" to "GEOB")) {
                    assertContentEquals(originals.single { it.first == old }.second, entries[key]?.binary, key)
                }
                assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
            }
        }
    }

    @Test
    fun linksPeopleAndUserUrlsKeepTheirVersionDependentStructure() {
        val people = byteArrayOf(0) + "producer\u0000Alice\u0000guitar\u0000Bob\u0000".encodeToByteArray()
        val url = "https://example.org/tag\u0000extra".encodeToByteArray()
        val userUrl = byteArrayOf(0) + "Private site\u0000https://example.org/private".encodeToByteArray()
        val original = Id3V22UpgradeFixtures.tag(listOf(
            Id3V22UpgradeFixtures.text("TT2", "Old"), "IPL" to people,
            "LNK" to ("TLE".encodeToByteArray() + url), "WXX" to userUrl,
        )) + audio
        for (major in 3..4) {
            val written = AudioKodec.writeTags(original, AudioKodec.readTags(original).edit {
                set(TagField.TITLE, "Changed")
            }, TagWriteOptions(id3Version = major, padding = 0))
            val stored = Id3V22UpgradeFixtures.frames(written)
            assertContentEquals(people, stored[if (major == 3) "IPLS" else "TIPL"])
            assertContentEquals("TLEN".encodeToByteArray() + url, stored["LINK"])
            val entry = AudioKodec.readTags(written).raw.blocks.flatMap { it.entries }.single { it.key == "WXXX" }
            assertEquals("Private site", entry.description)
            assertEquals("https://example.org/private", entry.text)
            assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
        }
    }

    @Test
    fun unmappableFramesAndLinksAreOmittedWhileTheDefaultRemainsVersion24() {
        val original = Id3V22UpgradeFixtures.tag(listOf(
            Id3V22UpgradeFixtures.text("TT2", "Old"), Id3V22UpgradeFixtures.text("TLE", "12345"),
            "CRM" to "owner\u0000encrypted metadata\u0000opaque".encodeToByteArray(),
            "ZZZ" to byteArrayOf(1, 2, 3), "LNK" to "ZZZhttps://example.org/\u0000".encodeToByteArray(),
        )) + audio
        for (options in listOf(TagWriteOptions.DEFAULT, TagWriteOptions(formats = setOf(TagFormat.ID3V2_3)))) {
            val written = AudioKodec.writeTags(original, AudioKodec.readTags(original).edit {
                set(TagField.TITLE, "Changed")
            }, options)
            assertEquals(if (options.formats == null) 4 else 3, written[3].toInt())
            assertEquals(setOf("TIT2", "TLEN"), Id3V22UpgradeFixtures.frames(written).keys)
            assertEquals("Changed", AudioKodec.readTags(written)[TagField.TITLE])
            assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
        }
    }

    @Test
    fun legacySlashListsConvertToTheRequestedVersionWithoutLosingMembers() {
        val original = Id3V22UpgradeFixtures.tag(listOf("TT2", "TP1", "TCM", "TXT", "TOA", "TOL").map {
            Id3V22UpgradeFixtures.text(it, if (it == "TT2") "Old" else "Alice/Bob/Alice")
        }) + audio
        val before = AudioKodec.readTags(original)
        for (field in listOf(TagField.ARTIST, TagField.COMPOSER, TagField.LYRICIST)) {
            assertEquals(listOf("Alice", "Bob"), before.all(field))
        }
        for (major in 3..4) {
            val written = AudioKodec.writeTags(original, before.edit { set(TagField.TITLE, "Changed") }, TagWriteOptions(id3Version = major))
            val after = AudioKodec.readTags(written)
            for (field in listOf(TagField.ARTIST, TagField.COMPOSER, TagField.LYRICIST)) {
                assertEquals(before.all(field), after.all(field))
            }
            for (key in listOf("TOPE", "TOLY")) {
                assertEquals(listOf("Alice", "Bob", "Alice"), after.raw.blocks.flatMap { it.entries }.filter { it.key == key }.map { it.text })
            }
            assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
        }
    }
}

internal object Id3V22UpgradeFixtures {
    fun text(key: String, value: String): Pair<String, ByteArray> = key to (byteArrayOf(0) + value.encodeToByteArray())

    fun tag(entries: List<Pair<String, ByteArray>>): ByteArray {
        val body = entries.fold(ByteArray(0)) { bytes, (key, value) ->
            bytes + key.encodeToByteArray() + ByteArray(3) { (value.size ushr (8 * (2 - it))).toByte() } + value
        }
        return "ID3".encodeToByteArray() + byteArrayOf(2, 0, 0) +
            ByteArray(4) { ((body.size ushr (7 * (3 - it))) and 127).toByte() } + body
    }

    /** Read the output headers without relying on the production tag parser. */
    fun frames(bytes: ByteArray): Map<String, ByteArray> {
        val major = bytes[3].toInt()
        val end = 10 + size(bytes, 6, 7)
        val result = LinkedHashMap<String, ByteArray>()
        var at = 10
        while (at + 10 <= end && bytes[at] != 0.toByte()) {
            val count = size(bytes, at + 4, if (major == 4) 7 else 8)
            assertTrue(at + 10 + count <= end, "the emitted frame stays inside the tag")
            result[bytes.decodeToString(at, at + 4)] = bytes.copyOfRange(at + 10, at + 10 + count)
            at += 10 + count
        }
        return result
    }

    private fun size(bytes: ByteArray, at: Int, shift: Int): Int = (0..3).fold(0) { value, index ->
        (value shl shift) or (bytes[at + index].toInt() and 255)
    }
}

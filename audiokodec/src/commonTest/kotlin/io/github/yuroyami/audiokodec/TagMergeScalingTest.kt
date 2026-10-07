package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.tag.Base64
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
import kotlin.test.assertFailsWith

class TagMergeScalingTest {
    private val audio = ByteArray(21) { 0x55 }

    @Test
    fun publicFlacReadsKeepFirstSeenOrderWithManyCollidingAndRepeatedValues() {
        val values = List(4_096) { TagStressFixtures.collidingText((it * 73) and 4095) }
        val comments = values.flatMap { listOf("ARTIST=$it", "artist=$it") }
        val file = TagStressFixtures.flac(comments)
        val tags = AudioKodec.readTags(file)
        assertEquals(values, tags.all(TagField.ARTIST))
        assertEquals(comments.size + 1, tags.raw.blocks.single().entries.size)
        assertEquals(16, AudioKodec.decode(file).frames)
    }

    @Test
    fun equalPrecedenceBlocksKeepTheirOrderWhileLowerBlocksFillOnlyMissingFields() {
        val lower = block(TagFormat.APEV2, listOf(RawTagEntry("ARTIST", text = "stale"), RawTagEntry("ALBUM", text = "older album")))
        val first = block(TagFormat.ID3V2_4, listOf(
            RawTagEntry("TPE1", text = " first "), RawTagEntry("TPE1", text = "same"),
            RawTagEntry("TRCK", text = "3/12"), RawTagEntry("TCON", text = "(4)Eurodisco"),
        ))
        val second = block(TagFormat.ID3V2_3, listOf(
            RawTagEntry("TPE1", text = "same"), RawTagEntry("TPE1", text = "second"),
            RawTagEntry("TRCK", text = "3/12"), RawTagEntry("TCON", text = "Eurodisco"),
        ))
        val tags = TagAssembler.assemble(listOf(lower, first, second))
        assertEquals(listOf("first", "same", "second"), tags.all(TagField.ARTIST))
        assertEquals(listOf("Disco", "Eurodisco"), tags.all(TagField.GENRE))
        assertEquals(listOf("3"), tags.all(TagField.TRACK_NUMBER))
        assertEquals(listOf("12"), tags.all(TagField.TRACK_TOTAL))
        assertEquals("older album", tags[TagField.ALBUM])
        assertEquals(listOf(lower, first, second), tags.raw.blocks)
    }

    @Test
    fun manyLegacyPicturesUseTheFirstMimeEntryOrTheExistingSniffFallback() {
        val png = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)
        val encoded = Base64.encode(png)
        val pictures = List(4_096) { RawTagEntry("COVERART", text = encoded) }
        for (mime in listOf("image/private", null)) {
            val entries = pictures + listOf(RawTagEntry("coverartmime", text = mime), RawTagEntry("COVERARTMIME", text = "image/later"))
            val tags = TagAssembler.assemble(listOf(block(TagFormat.VORBIS_COMMENT, entries)))
            assertEquals(pictures.size, tags.pictures.size)
            for (picture in tags.pictures) {
                assertEquals(mime ?: "image/png", picture.mimeType)
                assertContentEquals(png, picture.bytes)
            }
        }
        val missing = TagAssembler.assemble(listOf(block(TagFormat.VORBIS_COMMENT, pictures)))
        assertEquals(pictures.size, missing.pictures.size)
        assertEquals("image/png", missing.pictures.first().mimeType)
    }

    @Test
    fun manyNewCommentsAndLyricsHaveTheSameFirstUnusedIdentitySequence() {
        val values = List(2_048) { "new-$it" }
        for (version in 3..4) {
            val tags = TagBundle(mapOf(TagField.COMMENT to values, TagField.LYRICS to values))
            val written = AudioKodec.writeTags(audio, tags, TagWriteOptions(id3Version = version, padding = 0))
            val read = AudioKodec.readTags(written)
            assertEquals(values, read.all(TagField.COMMENT))
            assertEquals(values, read.all(TagField.LYRICS))
            for (key in listOf("COMM", "USLT")) {
                val entries = read.raw.blocks.single().entries.filter { it.key == key }
                assertEquals(values.size, entries.size)
                assertEquals(List(values.size) { if (it == 0) "" else (it + 1).toString() }, entries.map { it.description })
                assertEquals(setOf("xxx"), entries.map { it.language }.toSet())
            }
            assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
        }
    }

    @Test
    fun originalIdentitiesAndDescriptionHolesSurviveManyValueRewrites() {
        val originals = List(512) { index ->
            RawTagEntry("COMM", text = "original-$index", language = "eng", description = TagStressFixtures.collidingText(index))
        } + listOf(
            RawTagEntry("COMM", text = "original-0", language = "fra", description = "shared"),
            RawTagEntry("COMM", text = "saved blank", language = "xxx", description = ""),
            RawTagEntry("COMM", text = "saved two", language = "xxx", description = "2"),
            RawTagEntry("COMM", text = "saved four", language = "xxx", description = "4"),
        )
        val original = commentFixture(originals)
        val before = AudioKodec.readTags(original)
        val newValues = List(512) { "new-$it" }
        for (version in 3..4) {
            val written = AudioKodec.writeTags(original, before.edit { newValues.forEach { add(TagField.COMMENT, it) } }, TagWriteOptions(id3Version = version, padding = 0))
            val entries = AudioKodec.readTags(written).raw.blocks.single().entries.filter { it.key == "COMM" }
            assertEquals(originals.map { Triple(it.language, it.description, it.text) }, entries.take(originals.size).map { Triple(it.language, it.description, it.text) })
            assertEquals(newValues, entries.drop(originals.size).map { it.text })
            assertEquals(List(512) { if (it == 0) "3" else (it + 4).toString() }, entries.drop(originals.size).map { it.description })
            assertEquals(entries.size, entries.map { it.language to it.description }.toSet().size)
            assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
        }
    }

    @Test
    fun aMergedFieldUsesTheExistingAllocationLimitAcrossBlocks() {
        val first = block(TagFormat.ID3V2_4, listOf(RawTagEntry("TPE1", text = "first"), RawTagEntry("TPE1", text = "second")))
        val duplicate = block(TagFormat.ID3V2_3, listOf(RawTagEntry("TPE1", text = "first"), RawTagEntry("TPE1", text = " second ")))
        val extra = block(TagFormat.ID3V2_3, listOf(RawTagEntry("TPE1", text = "third")))
        val options = DecodeOptions(maxAllocationBytes = 128)
        assertEquals(listOf("first", "second"), TagAssembler.assemble(listOf(first, duplicate), options).all(TagField.ARTIST))
        assertFailsWith<AllocationGuardException> { TagAssembler.assemble(listOf(first, extra), options) }
        assertEquals(listOf("first", "second", "third"), TagAssembler.assemble(listOf(first, extra), options.copy(maxAllocationBytes = 192)).all(TagField.ARTIST))
    }

    private fun block(format: TagFormat, entries: List<RawTagEntry>) = RawTagBlock(format, 0, 0, entries)

    private fun commentFixture(entries: List<RawTagEntry>): ByteArray {
        val frames = ByteArrayAudioSink()
        for (entry in entries) {
            val payload = byteArrayOf(0) + entry.language!!.encodeToByteArray() + entry.description!!.encodeToByteArray() + byteArrayOf(0) + entry.text!!.encodeToByteArray()
            frames.write("COMM".encodeToByteArray())
            frames.write(syncsafe(payload.size))
            frames.write(byteArrayOf(0, 0))
            frames.write(payload)
        }
        val body = frames.toByteArray()
        return "ID3".encodeToByteArray() + byteArrayOf(4, 0, 0) + syncsafe(body.size) + body + audio
    }

    private fun syncsafe(value: Int): ByteArray = ByteArray(4) { ((value ushr ((3 - it) * 7)) and 127).toByte() }
}

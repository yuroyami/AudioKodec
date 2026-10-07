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
import kotlin.test.assertNull

class ItunesCommentTagTest {
    private val audio = ByteArray(32) { 0x55 }

    @Test
    fun reservedOnlyCommentsStayPrivateThroughEditsAcrossVersionsAndEncodings() {
        for (input in 2..4) {
            for (encoding in if (input == 4) 0..3 else 0..1) {
                val original = ItunesCommentFixtures.tag(input, encoding) + audio
                val tags = AudioKodec.readTags(original)
                assertNull(tags[TagField.COMMENT], "input=$input encoding=$encoding")
                assertEquals(ItunesCommentFixtures.reserved, comments(tags))
                for (output in 3..4) {
                    val changes = listOf(
                        tags.edit { set(TagField.TITLE, "Changed") } to emptyList(),
                        tags.edit { set(TagField.COMMENT, "my note") } to listOf("my note"),
                        tags.edit { remove(TagField.COMMENT) } to emptyList(),
                    )
                    for ((edited, expected) in changes) {
                        val written = AudioKodec.writeTags(original, edited, TagWriteOptions(id3Version = output, padding = 0))
                        val after = AudioKodec.readTags(written)
                        assertEquals(expected, after.all(TagField.COMMENT))
                        assertEquals(ItunesCommentFixtures.reserved, comments(after).filter { it.description.startsWith("iTun", true) })
                        assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
                    }
                }
            }
        }
    }

    @Test
    fun namedUserCommentsStayEditableWithoutClaimingPrivateTextMatches() {
        val ordinary = listOf(
            ItunesCommentFixtures.Comment("eng", "", "old note"),
            ItunesCommentFixtures.Comment("fra", "review", "named note"),
            ItunesCommentFixtures.Comment("deu", "my iTunNORM", "another note"),
        )
        for (input in 2..4) {
            val original = ItunesCommentFixtures.tag(input, 1, ordinary + ItunesCommentFixtures.reserved) + audio
            val before = AudioKodec.readTags(original)
            assertEquals(ordinary.map { it.text }, before.all(TagField.COMMENT))
            for (output in 3..4) {
                val options = TagWriteOptions(id3Version = output, padding = 0)
                val samePrivateText = ItunesCommentFixtures.reserved.first().text
                val edits = listOf(
                    before.edit { set(TagField.COMMENT, samePrivateText) } to listOf(samePrivateText),
                    before.edit { add(TagField.COMMENT, samePrivateText) } to (ordinary.map { it.text } + samePrivateText),
                    before.edit { remove(TagField.COMMENT) } to emptyList(),
                )
                for ((edited, expected) in edits) {
                    val bytes = AudioKodec.writeTags(original, edited, options)
                    val after = AudioKodec.readTags(bytes)
                    assertEquals(expected, after.all(TagField.COMMENT))
                    val frames = comments(after)
                    assertEquals(ItunesCommentFixtures.reserved, frames.filter { it.description.startsWith("iTun", true) })
                    assertEquals(expected, frames.filterNot { it.description.startsWith("iTun", true) }.map { it.text })
                    assertEquals(frames.size, frames.map { it.language to it.description }.distinct().size)
                    assertContentEquals(audio, bytes.takeLast(audio.size).toByteArray())
                }
            }
        }
    }

    @Test
    fun reservedDescriptionsDoNotClaimLowerPrecedenceCommentsOrOtherFrameKinds() {
        val id3 = RawTagBlock(TagFormat.ID3V2_4, 0, 0, listOf(
            RawTagEntry("COMM", text = "private", language = "eng", description = "ITUNcustom"),
            RawTagEntry("USLT", text = "lyric", language = "eng", description = "iTunNORM"),
            RawTagEntry("TXXX", text = "custom", description = "iTunNORM"),
        ))
        val ape = RawTagBlock(TagFormat.APEV2, 0, 0, listOf(RawTagEntry("COMMENT", text = "user note")))
        val tags = TagAssembler.assemble(listOf(ape, id3))
        assertEquals(listOf("user note"), tags.all(TagField.COMMENT))
        assertEquals(listOf("lyric"), tags.all(TagField.LYRICS))
        assertEquals(id3.entries, tags.raw.blocks.last().entries)
        val bytes = AudioKodec.writeTags(audio, tags, TagWriteOptions(padding = 0))
        val after = AudioKodec.readTags(bytes)
        assertEquals(listOf("user note"), after.all(TagField.COMMENT))
        assertEquals(listOf("lyric"), after.all(TagField.LYRICS))
        assertEquals("custom", after.raw.blocks.flatMap { it.entries }.single { it.key == "TXXX" }.text)
        assertContentEquals(audio, bytes.takeLast(audio.size).toByteArray())
    }

    private fun comments(tags: TagBundle): List<ItunesCommentFixtures.Comment> = tags.raw.blocks.flatMap { it.entries }
        .filter { it.key == "COMM" || it.key == "COM" }
        .map { ItunesCommentFixtures.Comment(it.language!!, it.description!!, it.text!!) }
}

/** Independent comment frames from the published ID3 layouts. */
internal object ItunesCommentFixtures {
    data class Comment(val language: String, val description: String, val text: String)

    val reserved = listOf(
        Comment("eng", "iTunNORM", "000000F0 000000F0"),
        Comment("eng", "iTunSMPB", " 00000000 00000840 000001F4 0000000000001000"),
        Comment("fra", "itunnorm", "private note"),
        Comment("deu", "ITUNfuture", "  future value\n"),
    )

    fun tag(version: Int, encoding: Int, comments: List<Comment> = reserved): ByteArray {
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
        fun syncsafe(value: Int) = ByteArray(4) { ((value ushr (21 - it * 7)) and 127).toByte() }
        val terminator = ByteArray(if (encoding == 1 || encoding == 2) 2 else 1)
        var frames = byteArrayOf()
        for (comment in comments) {
            val body = byteArrayOf(encoding.toByte()) + comment.language.encodeToByteArray() +
                text(comment.description) + terminator + text(comment.text)
            val header = when (version) {
                2 -> "COM".encodeToByteArray() + ByteArray(3) { (body.size ushr (16 - it * 8)).toByte() }
                3 -> "COMM".encodeToByteArray() + ByteArray(4) { (body.size ushr (24 - it * 8)).toByte() } + byteArrayOf(0, 0)
                else -> "COMM".encodeToByteArray() + syncsafe(body.size) + byteArrayOf(0, 0)
            }
            frames += header + body
        }
        return "ID3".encodeToByteArray() + byteArrayOf(version.toByte(), 0, 0) + syncsafe(frames.size) + frames
    }
}

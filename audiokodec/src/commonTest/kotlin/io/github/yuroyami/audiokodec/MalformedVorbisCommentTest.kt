package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.tag.VorbisComment
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.ReplayGain
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MalformedVorbisCommentTest {
    @Test
    fun invalidFieldsRemainRawWithoutDiscardingReadableCommentsOrAudio() {
        val body = TagStressFixtures.commentBody(listOf("TITLE=Seed", "ARTIST=Readable", "X-OPAQUE=Seed", "METADATA_BLOCK_PICTURE=Seed"))
        for (needle in listOf("TITLE=Seed", "X-OPAQUE=Seed", "METADATA_BLOCK_PICTURE=Seed")) corrupt(body, needle)
        for (bytes in files(body)) {
            val tags = AudioKodec.readTags(bytes)
            assertNull(tags[TagField.TITLE])
            assertEquals("Readable", tags[TagField.ARTIST])
            for (key in listOf("TITLE", "X-OPAQUE", "METADATA_BLOCK_PICTURE")) {
                val entry = tags.raw.blocks.flatMap { it.entries }.first { it.key == key }
                assertNull(entry.text)
                assertContentEquals(("$key=Seed".encodeToByteArray()).also { it[it.lastIndex] = 0xE9.toByte() }, entry.binary)
            }
            if (AudioKodec.probe(bytes).codec != AudioCodec.VORBIS) {
                for (tolerant in listOf(false, true)) {
                    val decoded = AudioKodec.decode(bytes, DecodeOptions(tolerant = tolerant))
                    assertEquals(true, decoded.frames > 0)
                }
            }
            val rewritten = AudioKodec.writeTags(bytes, tags.edit { set(TagField.ARTIST, "Edited") })
            val after = AudioKodec.readTags(rewritten)
            assertEquals("Edited", after[TagField.ARTIST])
            for (key in listOf("TITLE", "X-OPAQUE", "METADATA_BLOCK_PICTURE")) {
                assertContentEquals(tags.raw.blocks.flatMap { it.entries }.first { it.key == key }.binary,
                    after.raw.blocks.flatMap { it.entries }.first { it.key == key }.binary)
            }
            if (AudioKodec.probe(bytes).codec != AudioCodec.VORBIS) {
                TestSignals.assertExact(AudioKodec.decode(bytes).samples, AudioKodec.decode(rewritten).samples)
            }
        }
    }

    @Test
    fun anUnreadableVendorRetainsItsBytesAndDoesNotPoisonOtherFields() {
        val body = TagStressFixtures.commentBody(listOf("TITLE=Seed", "ARTIST=Readable"))
        body[4] = 0xE9.toByte()
        for (bytes in files(body)) {
            val tags = AudioKodec.readTags(bytes)
            assertEquals("Seed", tags[TagField.TITLE])
            assertEquals("Readable", tags[TagField.ARTIST])
            val vendor = tags.raw.blocks.flatMap { it.entries }.first { it.key == "VENDOR" }
            assertNull(vendor.text)
            assertContentEquals(body.copyOfRange(4, 12), vendor.binary)
            val rewritten = AudioKodec.writeTags(bytes, tags.edit { set(TagField.TITLE, "Edited") })
            assertEquals("Edited", AudioKodec.readTags(rewritten)[TagField.TITLE])
            assertContentEquals(vendor.binary, AudioKodec.readTags(rewritten).raw.blocks.flatMap { it.entries }.first { it.key == "VENDOR" }.binary)
            if (AudioKodec.probe(bytes).codec != AudioCodec.VORBIS) {
                TestSignals.assertExact(AudioKodec.decode(bytes).samples, AudioKodec.decode(rewritten).samples)
            }
        }
    }

    @Test
    fun explicitFieldEditsReplaceOrRemoveTheUnreadableNativeComment() {
        val body = TagStressFixtures.commentBody(listOf("TITLE=Seed", "ARTIST=Readable"))
        corrupt(body, "TITLE=Seed")
        for (bytes in files(body)) {
            val tags = AudioKodec.readTags(bytes)
            val edits = listOf(
                tags.edit { set(TagField.TITLE, "Repaired") },
                tags.edit { add(TagField.TITLE, "Repaired") },
                tags.edit { remove(TagField.TITLE) },
                tags.edit { set(TagField.TITLE, emptyList()) },
            )
            for ((index, edited) in edits.withIndex()) {
                val after = AudioKodec.readTags(AudioKodec.writeTags(bytes, edited))
                assertEquals(if (index < 2) "Repaired" else null, after[TagField.TITLE])
                assertEquals("Readable", after[TagField.ARTIST])
                for (entry in after.raw.blocks.flatMap { it.entries }.filter { it.key == "TITLE" }) assertNull(entry.binary)
            }
        }
    }

    @Test
    fun explicitLoudnessArtworkAndClearEditsDiscardTheirUnreadableComments() {
        val body = TagStressFixtures.commentBody(listOf("TITLE=Readable", "R128_TRACK_GAIN=Seed", "METADATA_BLOCK_PICTURE=Seed"))
        corrupt(body, "R128_TRACK_GAIN=Seed")
        corrupt(body, "METADATA_BLOCK_PICTURE=Seed")
        for (bytes in files(body)) {
            val tags = AudioKodec.readTags(bytes)
            assertNull(tags.replayGain)
            assertEquals(0, tags.pictures.size)
            val gain = AudioKodec.readTags(AudioKodec.writeTags(bytes, tags.edit {
                setReplayGain(ReplayGain(trackGainDb = 1.0, isR128 = true))
            }))
            assertEquals(1.0, assertNotNull(gain.replayGain).trackGainDb)
            for (entry in gain.raw.blocks.flatMap { it.entries }.filter { it.key == "R128_TRACK_GAIN" }) assertNull(entry.binary)
            val withoutGain = AudioKodec.readTags(AudioKodec.writeTags(bytes, tags.edit { setReplayGain(null) }))
            assertEquals(0, withoutGain.raw.blocks.flatMap { it.entries }.count { it.key == "R128_TRACK_GAIN" })
            val withoutArtwork = AudioKodec.readTags(AudioKodec.writeTags(bytes, tags.edit { removePictures() }))
            assertEquals(0, withoutArtwork.raw.blocks.flatMap { it.entries }.count { it.key == "METADATA_BLOCK_PICTURE" })
            val cleared = AudioKodec.readTags(AudioKodec.writeTags(bytes, tags.edit { clear() }))
            assertEquals(0, cleared.fields.size)
            assertEquals(0, cleared.raw.blocks.flatMap { it.entries }.count { it.key != "VENDOR" })
        }
    }

    @Test
    fun malformedKeysAreOpaqueAndOriginalByteRetentionIsGuarded() {
        val body = TagStressFixtures.commentBody(listOf("X=Seed"))
        body[20] = 0xE9.toByte() // The first comment's key.
        val parsed = assertNotNull(VorbisComment.parse(body, 0, body.size, 0, body.size.toLong(), DecodeOptions.DEFAULT))
        assertNotNull(parsed.entries.last().binary)
        val large = TagStressFixtures.commentBody(listOf("TITLE=" + "x".repeat(2_048)))
        large[large.lastIndex] = 0xE9.toByte()
        assertFailsWith<AllocationGuardException> {
            VorbisComment.parse(large, 0, large.size, 0, large.size.toLong(), DecodeOptions(maxAllocationBytes = 1_024))
        }
        val longKey = TagStressFixtures.commentBody(listOf("X".repeat(800) + "=Seed"))
        longKey[longKey.lastIndex] = 0xE9.toByte()
        assertFailsWith<AllocationGuardException> {
            VorbisComment.parse(longKey, 0, longKey.size, 0, longKey.size.toLong(), DecodeOptions(maxAllocationBytes = 1_024))
        }
        assertNull(VorbisComment.parse(body.copyOf(5), 0, 5, 0, 5, DecodeOptions.DEFAULT))
    }

    private fun files(body: ByteArray): List<ByteArray> {
        val native = TagStressFixtures.flac(emptyList())
        val header = byteArrayOf(0x84.toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte())
        return listOf(native.copyOf(42) + header + body + TagStressFixtures.flacAudio(native)) +
            listOf(AudioCodec.VORBIS, AudioCodec.OPUS, AudioCodec.FLAC).map { OggTagReadFixtures.file(it, body, audioPages = 1).bytes }
    }

    private fun corrupt(bytes: ByteArray, value: String) {
        val needle = value.encodeToByteArray()
        val at = (0..bytes.size - needle.size).first { start -> needle.indices.all { bytes[start + it] == needle[it] } }
        bytes[at + needle.lastIndex] = 0xE9.toByte()
    }
}

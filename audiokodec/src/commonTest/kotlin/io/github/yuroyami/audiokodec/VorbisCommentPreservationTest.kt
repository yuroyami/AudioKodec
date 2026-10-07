package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.RawTagBlock
import io.github.yuroyami.audiokodec.tag.RawTagEntry
import io.github.yuroyami.audiokodec.tag.RawTags
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class VorbisCommentPreservationTest {
    @Test
    fun repeatedEditsRetainUnknownCommentsAndTheSeparateVendorHeader() {
        val comments = listOf("PRIVATE=one", "Private=one", "PRIVATE=two", "OTHER=x=y 日本語", "PRIVATE=one", "VENDOR=user", "PRIVATE=")
        for (vendor in listOf("tag-test", "")) {
            var file = TagStressFixtures.flac(comments, vendor)
            val audio = TagStressFixtures.flacAudio(file)
            val raw = AudioKodec.readTags(file).raw.blocks.single().entries.drop(1)
            assertEquals(comments, raw.map { "${it.key}=${it.text}" })
            repeat(3) { edit ->
                file = AudioKodec.writeTags(file, AudioKodec.readTags(file).edit { set(TagField.TITLE, "Edit $edit") })
                val written = TagStressFixtures.flacComments(file)
                assertEquals(vendor, written.first)
                assertEquals(listOf("TITLE=Edit $edit") + comments, written.second)
                assertContentEquals(audio, TagStressFixtures.flacAudio(file))
            }
        }
    }

    @Test
    fun callerConstructedVendorCommentsAreNotMistakenForTheHeader() {
        val comments = listOf("VENDOR=tag-test", "VENDOR=tag-test", "vendor=", "PRIVATE=same", "PRIVATE=same")
        var file = TagStressFixtures.flac(emptyList())
        val entries = comments.map { RawTagEntry(it.substringBefore('='), text = it.substringAfter('=')) }
        val tags = TagBundle(emptyMap(), raw = RawTags(listOf(RawTagBlock(TagFormat.VORBIS_COMMENT, 0, 0, entries))))
        file = AudioKodec.writeTags(file, tags)
        assertEquals("tag-test" to comments, TagStressFixtures.flacComments(file))
        // Rebuilding the block around the same entries must keep provenance.
        val read = AudioKodec.readTags(file)
        val copied = TagBundle(emptyMap(), raw = RawTags(read.raw.blocks.map { RawTagBlock(it.format, it.offset, it.size, it.entries) }))
        file = AudioKodec.writeTags(file, copied)
        assertEquals("tag-test" to comments, TagStressFixtures.flacComments(file))
    }

    @Test
    fun knownFieldEditsAndRemovalDoNotReintroduceOldRawValues() {
        val file = TagStressFixtures.flac(listOf("artist=Old", "title=Old", "PRIVATE=", "PRIVATE=same", "PRIVATE=same", "COVERART=invalid", "COVERARTMIME=image/private"))
        val tags = AudioKodec.readTags(file).edit { remove(TagField.ARTIST); set(TagField.TITLE, "New") }
        val written = AudioKodec.writeTags(file, tags)
        assertEquals(listOf("TITLE=New", "PRIVATE=", "PRIVATE=same", "PRIVATE=same"), TagStressFixtures.flacComments(written).second)
        assertContentEquals(TagStressFixtures.flacAudio(file), TagStressFixtures.flacAudio(written))
    }

    @Test
    fun unmodelledLoudnessNamesRemainRawWhenModelledGainIsRemoved() {
        val unknown = listOf("replaygain_private=state", "REPLAYGAIN_REFERENCE_LOUDNESS=89.0 dB", "R128_PRIVATE=state", "R128_PRIVATE=")
        val known = listOf("REPLAYGAIN_TRACK_GAIN=-3 dB", "replaygain_track_peak=0.9", "REPLAYGAIN_ALBUM_GAIN=-4 dB", "replaygain_album_peak=0.8", "R128_TRACK_GAIN=256", "r128_album_gain=512")
        val file = TagStressFixtures.flac(unknown + known)
        val tags = AudioKodec.readTags(file).edit { set(TagField.TITLE, "New"); setReplayGain(null) }
        val written = AudioKodec.writeTags(file, tags)
        assertEquals(listOf("TITLE=New") + unknown, TagStressFixtures.flacComments(written).second)
        assertContentEquals(TagStressFixtures.flacAudio(file), TagStressFixtures.flacAudio(written))
    }

    @Test
    fun largeListsKeepOrderMultiplicityAndAudioDespiteEqualHashes() {
        for (comments in listOf(
            List(4_096) { "CUSTOM$it=value" },
            List(4_096) { "PRIVATE=${TagStressFixtures.collidingText(it)}" },
            List(4_096) { "PRIVATE=same" },
        )) {
            val file = TagStressFixtures.flac(comments)
            val written = AudioKodec.writeTags(file, AudioKodec.readTags(file).edit { set(TagField.TITLE, "Changed") })
            assertEquals(listOf("TITLE=Changed") + comments, TagStressFixtures.flacComments(written).second)
            assertContentEquals(TagStressFixtures.flacAudio(file), TagStressFixtures.flacAudio(written))
        }
    }
}

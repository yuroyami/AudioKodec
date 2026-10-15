package io.github.yuroyami.audiokodec.docs

// --8<-- [start:imports]
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
// --8<-- [end:imports]
import io.github.yuroyami.audiokodec.EncodeTarget
import io.github.yuroyami.audiokodec.tag.TagFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditTagsSample {

    private fun editAndCheck(bytes: ByteArray) {
        // --8<-- [start:edit]
        val edited = AudioKodec.readTags(bytes).edit {
            set(TagField.TITLE, "New title")
            set(TagField.ARTIST, listOf("First Artist", "Guest Artist"))
            set(TagField.TRACK_NUMBER, 4)
            remove(TagField.COMMENT)
        }
        val updated: ByteArray = AudioKodec.writeTags(bytes, edited)
        // --8<-- [end:edit]

        val tags = AudioKodec.readTags(updated)
        assertEquals("New title", tags[TagField.TITLE])
        assertEquals(listOf("First Artist", "Guest Artist"), tags.all(TagField.ARTIST))
        assertEquals(4, tags.number(TagField.TRACK_NUMBER))
        assertNull(tags[TagField.COMMENT])
        // The audio is copied byte for byte.
        assertContentEquals(Fixtures.payload(bytes), Fixtures.payload(updated))
    }

    @Test
    fun editTagsInFlacWavAndMp4() {
        editAndCheck(Fixtures.flac)
        editAndCheck(AudioKodec.encode(Fixtures.samples, Fixtures.format, EncodeTarget.wav(16), Fixtures.tags))
        editAndCheck(AudioKodec.encode(Fixtures.samples, Fixtures.format, EncodeTarget.alac(16), Fixtures.tags))
    }

    @Test
    fun editTagsInMp3AndOgg() {
        editAndCheck(Fixtures.ffmpegEncode(Fixtures.samples, "edit.mp3", "-c:a", "libmp3lame", "-metadata", "comment=from ffmpeg"))
        editAndCheck(Fixtures.ffmpegEncode(Fixtures.samples, "edit.ogg", "-c:a", "libvorbis", "-metadata", "comment=from ffmpeg"))
    }

    @Test
    fun writeOptions() {
        val bytes = Fixtures.ffmpegEncode(Fixtures.samples, "options.mp3", "-c:a", "libmp3lame")
        val edited = AudioKodec.readTags(bytes).edit { set(TagField.TITLE, "For an old player") }

        // --8<-- [start:options]
        val updated = AudioKodec.writeTags(
            bytes,
            edited,
            TagWriteOptions(
                id3Version = 3,    // ID3v2.3, for players that predate ID3v2.4
                padding = 4096,    // room for the next edit to reuse
                keepId3v1 = true,  // update an existing ID3v1 block; never create one
            ),
        )
        // --8<-- [end:options]

        val tags = AudioKodec.readTags(updated)
        assertEquals(listOf(TagFormat.ID3V2_3), tags.raw.formats)
        assertEquals("For an old player", tags[TagField.TITLE])
        assertContentEquals(Fixtures.payload(bytes), Fixtures.payload(updated))
    }
}

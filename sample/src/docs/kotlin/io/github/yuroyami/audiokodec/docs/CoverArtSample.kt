package io.github.yuroyami.audiokodec.docs

// --8<-- [start:imports]
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.EncodeTarget
import io.github.yuroyami.audiokodec.tag.PictureType
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagPicture
// --8<-- [end:imports]
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CoverArtSample {
    private val printed = ArrayList<String>()

    private fun println(line: String) {
        printed += line
    }

    private fun roundTrip(bytes: ByteArray) {
        val png = Fixtures.png

        // --8<-- [start:write]
        val withCover = AudioKodec.readTags(bytes).edit {
            removePictures(PictureType.FRONT_COVER)
            addPicture(TagPicture(type = PictureType.FRONT_COVER, mimeType = "image/png", bytes = png))
        }
        val updated = AudioKodec.writeTags(bytes, withCover)
        // --8<-- [end:write]

        var shown: ByteArray? = null
        fun showImage(image: ByteArray, mimeType: String) {
            assertEquals("image/png", mimeType)
            shown = image
        }

        // --8<-- [start:read]
        val tags = AudioKodec.readTags(updated)

        // The front cover if there is one, otherwise the first picture, otherwise null.
        val cover = tags.cover
        if (cover != null) {
            // The image file as it was stored. Decode it with whatever your platform uses.
            showImage(cover.bytes, cover.mimeType)
        }

        for (picture in tags.pictures) {
            println("${picture.type}: ${picture.mimeType}, ${picture.bytes.size} bytes")
        }
        // --8<-- [end:read]

        assertContentEquals(png, shown)
        assertEquals(listOf("FRONT_COVER: image/png, ${png.size} bytes"), printed.takeLast(1))
        assertEquals(1, tags.pictures.size)
        assertContentEquals(Fixtures.payload(bytes), Fixtures.payload(updated))
    }

    @Test
    fun coverArtInFlacWavAndMp4() {
        roundTrip(Fixtures.flac)
        roundTrip(AudioKodec.encode(Fixtures.samples, Fixtures.format, EncodeTarget.wav(16)))
        roundTrip(AudioKodec.encode(Fixtures.samples, Fixtures.format, EncodeTarget.alac(16)))
    }

    @Test
    fun coverArtInMp3() {
        roundTrip(Fixtures.ffmpegEncode(Fixtures.samples, "cover.mp3", "-c:a", "libmp3lame"))
    }

    @Test
    fun coverArtWhileEncoding() {
        val samples = Fixtures.samples
        val png = Fixtures.png

        // --8<-- [start:encode]
        val tags = TagBundle.EMPTY.edit {
            set(TagField.TITLE, "With a cover")
            addPicture(TagPicture(type = PictureType.FRONT_COVER, mimeType = "image/png", bytes = png))
        }
        val flac = AudioKodec.encode(samples, AudioFormat(44_100, 2), EncodeTarget.flac(16), tags)
        // --8<-- [end:encode]

        assertContentEquals(png, AudioKodec.readTags(flac).cover?.bytes)
    }
}

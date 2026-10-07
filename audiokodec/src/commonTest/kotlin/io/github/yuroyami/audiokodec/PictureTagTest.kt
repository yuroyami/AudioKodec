package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.tag.TagAssembler
import io.github.yuroyami.audiokodec.tag.PictureType
import io.github.yuroyami.audiokodec.tag.RawTagBlock
import io.github.yuroyami.audiokodec.tag.RawTagEntry
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagChapter
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagPicture
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PictureTagTest {
    private val audio = ByteArray(32) { 0x55 }
    private fun picture(value: Int, type: PictureType = PictureType.FRONT_COVER, description: String = "") =
        TagPicture(type, "image/jpeg", description, byteArrayOf(-1, -40, 0, value.toByte()))

    @Test
    fun mixedArtworkAndFileSizeStayStableAcrossRepeatedTitleEdits() {
        for (version in 3..4) {
            var bytes = AudioKodec.writeTags(audio, TagBundle(emptyMap(), listOf(picture(1))), mixed(version))
            var editedSize = -1
            repeat(8) { edit ->
                val tags = AudioKodec.readTags(bytes)
                assertEquals(1, tags.pictures.size, "version=$version edit=$edit")
                assertContentEquals(picture(1).bytes, tags.cover!!.bytes)
                val blocks = tags.raw.blocks
                assertEquals(2, blocks.size)
                val audioAt = blocks.first().size.toInt()
                assertContentEquals(audio, bytes.copyOfRange(audioAt, audioAt + audio.size))
                bytes = AudioKodec.writeTags(bytes, tags.edit { set(TagField.TITLE, "Edit $edit") }, TagWriteOptions(id3Version = version, padding = 0))
                if (edit == 0) editedSize = bytes.size else assertEquals(editedSize, bytes.size)
            }
        }
    }

    @Test
    fun distinctImagesRolesAndCaptionsSurviveMixedWritesWithChapterArtwork() {
        val pictures = listOf(picture(1), picture(2), picture(1, PictureType.BACK_COVER), picture(1, PictureType.ARTIST), picture(1, description = "caption"))
        for (version in 3..4) {
            var bytes = AudioKodec.writeTags(audio, TagBundle(emptyMap(), pictures,
                chapters = listOf(TagChapter("one", 0, pictures = pictures))), mixed(version))
            repeat(5) { edit ->
                val read = AudioKodec.readTags(bytes)
                assertEquals(pictures.size, read.pictures.size)
                assertEquals(pictures.size, read.chapters.single().pictures.size)
                for (actual in listOf(read.pictures, read.chapters.single().pictures)) {
                    assertEquals(pictures.map { it.type }, actual.map { it.type })
                    pictures.zip(actual).forEach { (expected, stored) -> assertContentEquals(expected.bytes, stored.bytes) }
                    assertEquals(actual.size, actual.map { it.description }.distinct().size)
                }
                bytes = AudioKodec.writeTags(bytes, read.edit { set(TagField.TITLE, "Edit $edit") }, TagWriteOptions(id3Version = version, padding = 0))
            }
        }
    }

    @Test
    fun leadingId3AndNativeArtworkUseTheSameDisambiguatedCaptions() {
        val pictures = listOf(picture(1), picture(2))
        val tags = TagBundle(emptyMap(), pictures)
        for (version in 3..4) {
            for (native in listOf(TagStressFixtures.flac(emptyList()), R128TagFixtures.opus())) {
                val withPictures = AudioKodec.writeTags(native, tags)
                val id3 = PictureTagFixtures.id3(pictures, version)
                var bytes = id3 + withPictures
                // The source has caption collisions across formats. After one
                // edit every emitted representation must use the same names.
                repeat(5) { edit ->
                    val read = AudioKodec.readTags(bytes)
                    bytes = AudioKodec.writeTags(bytes, read.edit { set(TagField.TITLE, "Edit $edit") }, TagWriteOptions(id3Version = version, padding = 0))
                    val after = AudioKodec.readTags(bytes)
                    assertEquals(2, after.pictures.size)
                    assertEquals(listOf("", " [1]"), after.pictures.map { it.description })
                    assertTrue(after.raw.blocks.any { it.format == TagFormat.VORBIS_COMMENT })
                    val nativeBytes = bytes.copyOfRange(after.raw.blocks.first().size.toInt(), bytes.size)
                    if (native[0] == 'f'.code.toByte()) assertContentEquals(TagStressFixtures.flacAudio(native), TagStressFixtures.flacAudio(nativeBytes))
                    else assertContentEquals(R128TagFixtures.audioPacket, nativeBytes.takeLast(R128TagFixtures.audioPacket.size).toByteArray())
                }
            }
        }
    }

    @Test
    fun duplicateInputsCollapseAndGeneratedDescriptionsDoNotStealLaterCaptions() {
        val pictures = listOf(picture(1), picture(1), picture(2), picture(3, description = " [1]"))
        for (version in 3..4) {
            val read = AudioKodec.readTags(AudioKodec.writeTags(audio, TagBundle(emptyMap(), pictures), TagWriteOptions(id3Version = version, padding = 0)))
            assertEquals(3, read.pictures.size)
            assertEquals(listOf("", " [2]", " [1]"), read.pictures.map { it.description })
        }
    }

    @Test
    fun apeOnlyRefusesArtworkItCannotRepresentBeforeWritingAnything() {
        val cases = listOf(listOf(picture(1), picture(2)), listOf(picture(1, PictureType.BACK_COVER), picture(2, PictureType.BACK_COVER)))
        for (pictures in cases) {
            val sink = ByteArrayAudioSink()
            assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags(audio.asAudioSource(), TagBundle(emptyMap(), pictures), sink,
                    TagWriteOptions(formats = setOf(TagFormat.APEV2), padding = 0))
            }
            assertEquals(0L, sink.position)
        }
        val pictures = listOf(picture(1), picture(2, PictureType.BACK_COVER))
        val bytes = AudioKodec.writeTags(audio, TagBundle(emptyMap(), pictures), TagWriteOptions(formats = setOf(TagFormat.APEV2), padding = 0))
        assertEquals(2, AudioKodec.readTags(bytes).pictures.size)
        assertContentEquals(audio, bytes.copyOfRange(0, audio.size))
    }

    @Test
    fun apeArtworkRetainsEveryStandardPictureRole() {
        val pictures = PictureType.entries.map { picture(it.ordinal, it) }
        val bytes = AudioKodec.writeTags(audio, TagBundle(emptyMap(), pictures), TagWriteOptions(formats = setOf(TagFormat.APEV2), padding = 0))
        val read = AudioKodec.readTags(bytes)
        assertEquals(pictures.map { it.type }, read.pictures.map { it.type })
        pictures.zip(read.pictures).forEach { (expected, actual) -> assertContentEquals(expected.bytes, actual.bytes) }
    }

    @Test
    fun mergeUsesOnlyMetadataRepresentedByBothFormatsAndKeepsRichDimensions() {
        val id3 = RawTagBlock(TagFormat.ID3V2_4, 0, 0, listOf(RawTagEntry("APIC", text = "image/jpeg", binary = picture(1).bytes, language = "3", description = "cover")))
        val full = RawTagBlock(TagFormat.VORBIS_COMMENT, 0, 0, listOf(RawTagEntry("METADATA_BLOCK_PICTURE", binary = PictureTagFixtures.flacPicture("cover", 640))))
        val ape = RawTagBlock(TagFormat.APEV2, 0, 0, listOf(RawTagEntry("Cover Art (Front)", binary = "cover\u0000".encodeToByteArray() + picture(1).bytes)))
        val result = TagAssembler.assemble(listOf(ape, full, id3))
        assertEquals(1, result.pictures.size)
        assertEquals(640, result.pictures.single().width)
        assertEquals("cover", result.pictures.single().description)
        assertEquals(3, result.raw.blocks.size)
        val distinct = RawTagBlock(TagFormat.VORBIS_COMMENT, 0, 0, listOf(RawTagEntry("METADATA_BLOCK_PICTURE", binary = PictureTagFixtures.flacPicture("different", 640))))
        assertEquals(2, TagAssembler.assemble(listOf(id3, distinct)).pictures.size)
    }

    @Test
    fun legacyVorbisAndMp4CopiesDoNotEraseDistinctRichArtwork() {
        val image = picture(1).bytes
        val rich = RawTagBlock(TagFormat.VORBIS_COMMENT, 0, 0, listOf(
            RawTagEntry("COVERART", text = "/9gAAQ=="),
            RawTagEntry("COVERARTMIME", text = "image/jpeg"),
            RawTagEntry("METADATA_BLOCK_PICTURE", binary = PictureTagFixtures.flacPicture("cover", 640)),
        ))
        val mp4 = RawTagBlock(TagFormat.MP4_ILST, 0, 0, listOf(RawTagEntry("covr", text = "image/jpeg", binary = image)))
        val result = TagAssembler.assemble(listOf(mp4, rich))
        assertEquals(1, result.pictures.size)
        assertEquals("cover", result.pictures.single().description)
        assertEquals(640, result.pictures.single().width)
        val artist = RawTagBlock(TagFormat.APEV2, 0, 0, listOf(RawTagEntry("Cover Art (Artist)", binary = "portrait.jpg\u0000".encodeToByteArray() + image)))
        val typed = TagAssembler.assemble(listOf(mp4, artist))
        assertEquals(PictureType.ARTIST, typed.pictures.single().type)
        assertEquals("portrait.jpg", typed.pictures.single().description)
    }

    @Test
    fun iconAndNullMetadataRefusalsLeaveTheSinkUntouched() {
        for (type in listOf(PictureType.FILE_ICON, PictureType.OTHER_FILE_ICON)) {
            for (version in 3..4) {
                val sink = ByteArrayAudioSink()
                assertFailsWith<UnsupportedFeatureException> {
                    AudioKodec.writeTags(audio.asAudioSource(), TagBundle(emptyMap(), listOf(picture(1, type), picture(2, type))), sink, TagWriteOptions(id3Version = version))
                }
                assertEquals(0L, sink.position)
            }
        }
        for (format in listOf(TagFormat.ID3V2_3, TagFormat.ID3V2_4, TagFormat.APEV2)) {
            val sink = ByteArrayAudioSink()
            assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags(audio.asAudioSource(), TagBundle(emptyMap(), listOf(picture(1, description = "caption\u0000suffix"))), sink, TagWriteOptions(formats = setOf(format)))
            }
            assertEquals(0L, sink.position)
        }
        val sink = ByteArrayAudioSink()
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.writeTags(audio.asAudioSource(), TagBundle(emptyMap(), chapters = listOf(TagChapter("one", 0, pictures = listOf(picture(1))))), sink, TagWriteOptions(formats = setOf(TagFormat.APEV2)))
        }
        assertEquals(0L, sink.position)
    }

    @Test
    fun version23CaptionSuffixesFitWithoutSplittingSurrogatePairs() {
        val caption = "A".repeat(59) + "😀" + "B".repeat(3)
        val bytes = AudioKodec.writeTags(audio, TagBundle(emptyMap(), listOf(picture(1, description = caption), picture(2, description = caption))), TagWriteOptions(id3Version = 3))
        val descriptions = AudioKodec.readTags(bytes).pictures.map { it.description }
        assertEquals(listOf(caption, "A".repeat(59) + " [1]"), descriptions)
    }

    @Test
    fun artworkIndexHandlesCollidingBytesAndHasAnAllocationCeiling() {
        val entries = (0 until 512).map { value ->
            RawTagEntry("APIC", text = "image/jpeg", binary = byteArrayOf(0, value.toByte(), (value ushr 8).toByte()), language = "3", description = "image-$value")
        }
        val block = RawTagBlock(TagFormat.ID3V2_4, 0, 0, entries + entries)
        assertEquals(512, TagAssembler.assemble(listOf(block)).pictures.size)
        assertFailsWith<AllocationGuardException> { TagAssembler.assemble(listOf(block), DecodeOptions(maxAllocationBytes = 2_000)) }
        val collision = listOf(byteArrayOf(0, 31), byteArrayOf(1, 0))
        assertEquals(collision[0].contentHashCode(), collision[1].contentHashCode())
        val both = RawTagBlock(TagFormat.ID3V2_4, 0, 0, collision.map { RawTagEntry("APIC", text = "image/jpeg", binary = it, language = "3", description = "") })
        assertEquals(2, TagAssembler.assemble(listOf(both)).pictures.size)
    }

    private fun mixed(version: Int) = TagWriteOptions(formats = setOf(if (version == 3) TagFormat.ID3V2_3 else TagFormat.ID3V2_4, TagFormat.APEV2), padding = 0)
}

internal object PictureTagFixtures {
    fun flac(): ByteArray {
        val source = TagStressFixtures.flac(emptyList())
        val audio = TagStressFixtures.flacAudio(source)
        val metadata = source.copyOfRange(0, source.size - audio.size).also { it[42] = 4 }
        val picture = flacPicture("cover", 640)
        val header = byteArrayOf(0x86.toByte(), (picture.size ushr 16).toByte(), (picture.size ushr 8).toByte(), picture.size.toByte())
        return metadata + header + picture + audio
    }

    fun id3(pictures: List<TagPicture>, version: Int): ByteArray {
        fun size(value: Int) = ByteArray(4) { index ->
            if (version == 4) ((value ushr (21 - index * 7)) and 127).toByte() else (value ushr (24 - index * 8)).toByte()
        }
        var frames = byteArrayOf()
        for (picture in pictures) {
            val body = byteArrayOf(0) + picture.mimeType.encodeToByteArray() + byteArrayOf(0, picture.type.ordinal.toByte()) + picture.description.encodeToByteArray() + byteArrayOf(0) + picture.bytes
            frames += "APIC".encodeToByteArray() + size(body.size) + byteArrayOf(0, 0) + body
        }
        val tagSize = ByteArray(4) { ((frames.size ushr (21 - it * 7)) and 127).toByte() }
        return "ID3".encodeToByteArray() + byteArrayOf(version.toByte(), 0, 0) + tagSize + frames
    }

    fun flacPicture(description: String, width: Int): ByteArray {
        fun u32(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
        val mime = "image/jpeg".encodeToByteArray()
        val text = description.encodeToByteArray()
        return u32(3) + u32(mime.size) + mime + u32(text.size) + text + u32(width) + u32(480) + u32(24) + u32(0) + u32(4) + byteArrayOf(-1, -40, 0, 1)
    }
}

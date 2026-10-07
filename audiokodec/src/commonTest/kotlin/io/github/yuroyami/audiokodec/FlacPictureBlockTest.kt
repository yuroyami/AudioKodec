package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.PictureType
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagPicture
import io.github.yuroyami.audiokodec.internal.flac.FlacMetadata
import io.github.yuroyami.audiokodec.internal.tag.FlacPicture
import io.github.yuroyami.audiokodec.internal.tag.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlacPictureBlockTest {
    @Test
    fun encodingStoresEachPictureInItsOwnNativeBlock() {
        val pictures = FlacPictureBlockFixtures.pictures()
        val encoded = AudioKodec.encode(FloatArray(64), AudioFormat(8_000, 1), EncodeTarget.flac(),
            TagBundle(mapOf(TagField.TITLE to listOf("Native artwork")), pictures))
        val blocks = FlacPictureBlockFixtures.blocks(encoded)
        assertEquals(listOf(0, 4, 6, 6, 1), blocks.map { it.type })
        assertEquals(listOf(false, false, false, false, true), blocks.map { it.last })
        val comment = blocks.single { it.type == 4 }
        assertFalse(encoded.copyOfRange(comment.bodyAt, comment.bodyAt + comment.size).decodeToString().contains("METADATA_BLOCK_PICTURE"))
        val read = AudioKodec.readTags(encoded)
        assertEquals("Native artwork", read[TagField.TITLE])
        assertEquals(pictures.size, read.pictures.size)
        pictures.zip(read.pictures).forEach { (expected, actual) -> FlacPictureBlockFixtures.assertPicture(expected, actual) }
    }

    @Test
    fun aPictureThatFitsANativeBlockIsNotLimitedByBase64Expansion() {
        val picture = TagPicture(mimeType = "image/jpeg", bytes = ByteArray(13 * 1024 * 1024))
        val encoded = AudioKodec.encode(FloatArray(16), AudioFormat(8_000, 1), EncodeTarget.flac(),
            TagBundle(emptyMap(), listOf(picture)))
        val blocks = FlacPictureBlockFixtures.blocks(encoded)
        assertEquals(32 + picture.mimeType.length + picture.bytes.size, blocks.single { it.type == 6 }.size)
        assertTrue(blocks.single { it.type == 4 }.size < 128)
    }

    @Test
    fun absentOrEmptyArtworkDoesNotAddPictureBlocks() {
        for (tags in listOf(null, TagBundle(emptyMap()), TagBundle(mapOf(TagField.TITLE to listOf("Text"))))) {
            val encoded = AudioKodec.encode(FloatArray(16), AudioFormat(8_000, 1), EncodeTarget.flac(), tags)
            val blocks = FlacPictureBlockFixtures.blocks(encoded)
            assertEquals(if (tags == null) listOf(0, 1) else listOf(0, 4, 1), blocks.map { it.type })
            assertEquals(1, blocks.count { it.last })
            assertTrue(blocks.last().last)
            assertTrue(AudioKodec.readTags(encoded).pictures.isEmpty())
        }
    }

    @Test
    fun theSharedLimitCountsHeadersAndUtf8StringsAsWellAsImageBytes() {
        val picture = FlacPictureBlockFixtures.pictures().first()
        val size = 32 + picture.mimeType.encodeToByteArray().size +
            picture.description.encodeToByteArray().size + picture.bytes.size
        assertEquals(size, FlacPicture.build(picture, size).size)
        assertFailsWith<AudioKodecException> { FlacPicture.build(picture, size - 1) }
        assertContentEquals(FlacPicture.build(picture), FlacPicture.build(picture, size))
    }

    @Test
    fun anOversizedLaterPictureLeavesBothOutputPathsUntouched() {
        val mime = "image/jpeg"
        val oversized = TagPicture(mimeType = mime, bytes = ByteArray(FlacMetadata.MAX_BODY_BYTES - 32 - mime.length + 1))
        val tags = TagBundle(emptyMap(), FlacPictureBlockFixtures.pictures() + oversized)
        val plain = AudioKodec.encode(FloatArray(16), AudioFormat(8_000, 1), EncodeTarget.flac())
        val source = FloatArray(16).asPcmSource(AudioFormat(8_000, 1))
        var reads = 0
        val counted = object : PcmSource by source {
            override fun read(into: FloatArray, offset: Int, frames: Int): Int {
                reads++
                return source.read(into, offset, frames)
            }
        }
        val encodingSink = ByteArrayAudioSink()
        val writingSink = ByteArrayAudioSink()
        assertFailsWith<AudioKodecException> { AudioKodec.encode(counted, EncodeTarget.flac(), encodingSink, tags) }
        assertEquals(0L, encodingSink.position)
        assertEquals(0, reads)
        assertFailsWith<AudioKodecException> { AudioKodec.writeTags(plain.asAudioSource(), tags, writingSink) }
        assertEquals(0L, writingSink.position)
    }

    @Test
    fun nativePictureBlocksWorkWithoutPatchingOrAKnownSourceLength() {
        val format = AudioFormat(44_100, 2)
        val samples = TestSignals.quantise(TestSignals.sine(137, 2), 16)
        val tags = TagBundle(emptyMap(), FlacPictureBlockFixtures.pictures())
        for (known in listOf(true, false)) {
            val source = samples.asPcmSource(format)
            val pcm = object : PcmSource by source {
                override val frameCount: Long? = if (known) source.frameCount else null
            }
            val sink = FlacPictureBlockFixtures.ForwardSink()
            AudioKodec.encode(pcm, EncodeTarget.flac(), sink, tags)
            val bytes = sink.bytes()
            assertEquals(listOf(0, 4, 6, 6, 1), FlacPictureBlockFixtures.blocks(bytes).map { it.type })
            assertEquals(if (known) 137L else null, AudioKodec.probe(bytes).durationFrames)
            TestSignals.assertExact(samples, AudioKodec.decode(bytes).samples)
            tags.pictures.zip(AudioKodec.readTags(bytes).pictures).forEach { (expected, actual) ->
                FlacPictureBlockFixtures.assertPicture(expected, actual)
            }
        }
    }

    @Test
    fun nativePicturesPreserveAudioDurationSeekingAndRepeatedTagEdits() {
        val samples = TestSignals.quantise(TestSignals.sine(5_003, 2), 24)
        val format = AudioFormat(44_100, 2)
        val target = EncodeTarget.flac(24)
        val plain = AudioKodec.encode(samples, format, target)
        val pictures = FlacPictureBlockFixtures.pictures()
        var bytes = AudioKodec.encode(samples, format, target, TagBundle(mapOf(TagField.TITLE to listOf("First")), pictures))
        val originalAudio = FlacPictureBlockFixtures.audio(plain)
        repeat(4) { edit ->
            assertContentEquals(originalAudio, FlacPictureBlockFixtures.audio(bytes))
            assertEquals(5_003L, AudioKodec.probe(bytes).durationFrames)
            assertEquals(true, AudioKodec.verifyFlacMd5(bytes))
            TestSignals.assertExact(samples, AudioKodec.decode(bytes).samples)
            AudioKodec.open(bytes).use { decoder ->
                for (frame in listOf(0L, 37L, 4_095L, 4_096L, 5_002L)) {
                    decoder.seek(frame)
                    val block = decoder.nextFrame()!!
                    assertTrue(block.frameCount > 0)
                    assertContentEquals(samples.copyOfRange(frame.toInt() * 2, frame.toInt() * 2 + 2), block.samples.copyOfRange(0, 2))
                }
            }
            val read = AudioKodec.readTags(bytes)
            assertEquals(pictures.size, read.pictures.size)
            pictures.zip(read.pictures).forEach { (expected, actual) -> FlacPictureBlockFixtures.assertPicture(expected, actual) }
            bytes = AudioKodec.writeTags(bytes, read.edit { set(TagField.TITLE, "Edit $edit") })
            assertEquals(listOf(0, 4, 6, 6, 1), FlacPictureBlockFixtures.blocks(bytes).map { it.type })
        }
    }

    @Test
    fun encodingAnOlderBase64ArtworkBundleProducesOnlyNativePictureBlocks() {
        val picture = FlacPictureBlockFixtures.pictures().first()
        val legacy = TagStressFixtures.flac(listOf("TITLE=Older", "METADATA_BLOCK_PICTURE=" + Base64.encode(FlacPicture.build(picture))))
        val tags = AudioKodec.readTags(legacy)
        val encoded = AudioKodec.encode(FloatArray(16), AudioFormat(8_000, 1), EncodeTarget.flac(), tags)
        val blocks = FlacPictureBlockFixtures.blocks(encoded)
        assertEquals(listOf(0, 4, 6, 1), blocks.map { it.type })
        val comment = blocks.single { it.type == 4 }
        assertFalse(encoded.copyOfRange(comment.bodyAt, comment.bodyAt + comment.size).decodeToString().contains("METADATA_BLOCK_PICTURE"))
        assertEquals("Older", AudioKodec.readTags(encoded)[TagField.TITLE])
        FlacPictureBlockFixtures.assertPicture(picture, AudioKodec.readTags(encoded).pictures.single())
    }
}

internal object FlacPictureBlockFixtures {
    class Block(val type: Int, val bodyAt: Int, val size: Int, val last: Boolean)

    class ForwardSink : AudioSink {
        private val sink = ByteArrayAudioSink()
        override val canPatch: Boolean = false
        override val position: Long get() = sink.position
        override fun write(bytes: ByteArray, offset: Int, count: Int) = sink.write(bytes, offset, count)
        override fun patchAt(position: Long, bytes: ByteArray, offset: Int, count: Int) {
            error("a forward sink must not be patched")
        }
        override fun close() = Unit
        fun bytes(): ByteArray = sink.toByteArray()
    }

    fun audio(bytes: ByteArray): ByteArray {
        val last = blocks(bytes).last()
        return bytes.copyOfRange(last.bodyAt + last.size, bytes.size)
    }

    fun blocks(bytes: ByteArray): List<Block> {
        assertEquals("fLaC", bytes.copyOfRange(0, 4).decodeToString())
        val blocks = ArrayList<Block>()
        var at = 4
        while (true) {
            assertTrue(at <= bytes.size - 4)
            val size = ((bytes[at + 1].toInt() and 255) shl 16) or
                ((bytes[at + 2].toInt() and 255) shl 8) or (bytes[at + 3].toInt() and 255)
            val last = bytes[at].toInt() and 128 != 0
            blocks.add(Block(bytes[at].toInt() and 127, at + 4, size, last))
            at += 4 + size
            assertTrue(at <= bytes.size)
            if (last) return blocks
        }
    }

    fun pictures(): List<TagPicture> = listOf(
        TagPicture(PictureType.FRONT_COVER, "image/png", "Cover 日本語", byteArrayOf(1, 2, -1, -2), 23, 17, 24, 0),
        TagPicture(PictureType.BACK_COVER, "image/jpeg", "Back", byteArrayOf(-1, -40, 1, 2), 12, 9, 8, 16),
    )

    fun assertPicture(expected: TagPicture, actual: TagPicture) {
        assertEquals(expected.type, actual.type)
        assertEquals(expected.mimeType, actual.mimeType)
        assertEquals(expected.description, actual.description)
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        assertEquals(expected.colourDepth, actual.colourDepth)
        assertEquals(expected.colourCount, actual.colourCount)
        assertContentEquals(expected.bytes, actual.bytes)
    }
}

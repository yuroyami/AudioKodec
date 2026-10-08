package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DamagedAudioTagReadTest {
    @Test
    fun intactOggCommentsSurviveDamagedAudioInEitherMode() {
        for (codec in listOf(AudioCodec.VORBIS, AudioCodec.OPUS, AudioCodec.FLAC)) {
            val file = OggTagReadFixtures.file(codec)
            val damaged = file.bytes.copyOf().also { it[file.audioStart + 28] = (it[file.audioStart + 28].toInt() xor 1).toByte() }
            val truncated = file.bytes.copyOf(file.bytes.size - 1)
            for (bytes in listOf(file.bytes, damaged, truncated)) {
                for (tolerant in listOf(false, true)) {
                    val tags = AudioKodec.readTags(bytes, DecodeOptions(tolerant = tolerant))
                    assertEquals("Still readable", tags[TagField.TITLE], "$codec, tolerant=$tolerant")
                    assertEquals("An artist", tags[TagField.ARTIST], "$codec, tolerant=$tolerant")
                }
            }
            assertFailsWith<AudioKodecException> { AudioKodec.probe(damaged) }
            assertFailsWith<AudioKodecException> { AudioKodec.probe(truncated) }
        }
    }

    @Test
    fun readingVorbisCommentsDoesNotRequireTheLaterSetupPacket() {
        val file = OggTagReadFixtures.file(AudioCodec.VORBIS)
        val onlyComments = file.bytes.copyOf(file.setupStart)
        assertEquals("Still readable", AudioKodec.readTags(onlyComments)[TagField.TITLE])
        assertFailsWith<TruncatedStreamException> { AudioKodec.probe(onlyComments) }
    }

    @Test
    fun readingTagsDoesNotScanTheOggAudioPayload() {
        val file = OggTagReadFixtures.file(AudioCodec.VORBIS, audioPages = 80)
        for (knownSize in listOf(false, true)) {
            val source = CountingSource(file.bytes, knownSize)
            assertEquals("Still readable", AudioKodec.readTags(source)[TagField.TITLE])
            assertTrue(source.transferred < 3 * 64 * 1_024, "tag read transferred ${source.transferred} bytes")
        }
    }

    @Test
    fun flacTagReadsSkipUnusableSeekTableBodiesAndTheirIndexAllocation() {
        val original = TagStressFixtures.flac(listOf("TITLE=Still readable", "ARTIST=An artist"))
        for (body in listOf(ByteArray(1), ByteArray(18 * 128))) {
            val seek = byteArrayOf(3, (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body
            val bytes = original.copyOfRange(0, 42) + seek + original.copyOfRange(42, original.size)
            val tags = AudioKodec.readTags(bytes, DecodeOptions(maxAllocationBytes = 256))
            assertEquals("Still readable", tags[TagField.TITLE])
            assertEquals("An artist", tags[TagField.ARTIST])
        }
    }

    private class CountingSource(bytes: ByteArray, knownSize: Boolean) : AudioSource {
        private val delegate = bytes.asAudioSource()
        override val size: Long = if (knownSize) bytes.size.toLong() else -1L
        var transferred = 0L
        override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int =
            delegate.readAt(position, into, offset, count).also { if (it > 0) transferred += it }
        override fun close() = Unit
    }
}

internal object OggTagReadFixtures {
    class File(val bytes: ByteArray, val audioStart: Int, val setupStart: Int)

    fun file(codec: AudioCodec, comments: ByteArray = TagStressFixtures.commentBody(listOf("TITLE=Still readable", "ARTIST=An artist")), audioPages: Int = 2): File {
        val native = TagStressFixtures.flac(emptyList())
        val identification = when (codec) {
            AudioCodec.OPUS -> ByteArray(19).also {
                "OpusHead".encodeToByteArray().copyInto(it)
                it[8] = 1
                it[9] = 1
            }
            AudioCodec.VORBIS -> ByteArray(30).also {
                it[0] = 1
                "vorbis".encodeToByteArray().copyInto(it, 1)
                it[11] = 1
                repeat(4) { byte -> it[12 + byte] = (44_100 ushr (8 * byte)).toByte() }
                it[28] = 0xB8.toByte()
                it[29] = 1
            }
            AudioCodec.FLAC -> byteArrayOf(0x7F) + "FLAC".encodeToByteArray() + byteArrayOf(1, 0, 0, 1) + native.copyOf(42)
            else -> error("no fixture for $codec")
        }
        val comment = when (codec) {
            AudioCodec.OPUS -> "OpusTags".encodeToByteArray() + comments
            AudioCodec.VORBIS -> byteArrayOf(3) + "vorbis".encodeToByteArray() + comments + byteArrayOf(1)
            else -> byteArrayOf(0x84.toByte(), (comments.size ushr 16).toByte(), (comments.size ushr 8).toByte(), comments.size.toByte()) + comments
        }
        val sink = ByteArrayAudioSink()
        var sequence = 0
        fun page(granule: Long, flags: Int, packet: ByteArray) {
            sink.write(OggBuilder().page(1, sequence++, granule, flags, listOf(packet)).build())
        }
        page(0, 2, identification)
        page(0, 0, comment)
        val setupStart = sink.position.toInt()
        if (codec == AudioCodec.VORBIS) page(0, 0, byteArrayOf(5) + "vorbis".encodeToByteArray())
        val audioStart = sink.position.toInt()
        val audio = when (codec) {
            AudioCodec.OPUS -> byteArrayOf(0xF8.toByte(), 0xFF.toByte(), 0xFE.toByte())
            AudioCodec.FLAC -> TagStressFixtures.flacAudio(native)
            else -> ByteArray(32_000)
        }
        val framesPerPage = if (codec == AudioCodec.FLAC) 16L else 960L
        repeat(audioPages) { page((it + 1) * framesPerPage, if (it + 1 == audioPages) 4 else 0, audio) }
        return File(sink.toByteArray(), audioStart, setupStart)
    }
}

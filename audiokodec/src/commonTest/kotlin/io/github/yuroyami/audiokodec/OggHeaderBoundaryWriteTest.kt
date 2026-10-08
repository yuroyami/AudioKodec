package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.ogg.OggReader
import io.github.yuroyami.audiokodec.internal.tag.Id3v2Writer
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OggHeaderBoundaryWriteTest {
    @Test
    fun anUnsafeHeaderBoundaryIsRefusedBeforeAnyOutputInEitherMode() {
        val shared = OggSharedHeaderFixtures.merge(OggSharedHeaderFixtures.opus())
        val changed = TagBundle(mapOf(TagField.TITLE to listOf("Changed")))
        val prefix = Id3v2Writer.build(TagBundle(mapOf(TagField.TITLE to listOf("External"))), 4, 0)
        for (prefixed in listOf(false, true)) {
            val bytes = if (prefixed) prefix + shared else shared
            for (knownSize in listOf(false, true)) {
                for (tolerant in listOf(false, true)) {
                    val source = object : AudioSource {
                        private val delegate = bytes.asAudioSource()
                        override val size: Long = if (knownSize) bytes.size.toLong() else -1
                        override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int =
                            delegate.readAt(position, into, offset, count)
                        override fun close() = Unit
                    }
                    val sink = ByteArrayAudioSink()
                    val error = if (tolerant) {
                        assertFailsWith<UnsupportedFeatureException> {
                            AudioKodec.writeTags(source, changed, sink, decodeOptions = DecodeOptions(tolerant = true))
                        }
                    } else {
                        assertFailsWith<CorruptStreamException> { AudioKodec.writeTags(source, changed, sink) }
                    }
                    assertTrue(error.message.orEmpty().contains("header"))
                    assertEquals(0, sink.toByteArray().size, "prefixed=$prefixed knownSize=$knownSize tolerant=$tolerant")
                }
            }
        }
    }

    @Test
    fun aCanonicalBoundaryKeepsAllAudioPacketsAcrossATolerantRewrite() {
        val bytes = OggSharedHeaderFixtures.opus()
        val sink = ByteArrayAudioSink()
        AudioKodec.writeTags(bytes.asAudioSource(), TagBundle(mapOf(TagField.TITLE to listOf("Changed"))),
            sink, decodeOptions = DecodeOptions(tolerant = true))
        val rewritten = sink.toByteArray()
        assertEquals("Changed", AudioKodec.readTags(rewritten)[TagField.TITLE])
        val before = OggSharedHeaderFixtures.audioPackets(bytes)
        val after = OggSharedHeaderFixtures.audioPackets(rewritten)
        assertEquals(3, before.size)
        assertEquals(before.size, after.size)
        for (index in before.indices) assertContentEquals(before[index], after[index])
        assertEquals(2_880, AudioKodec.decode(rewritten).frames)
    }
}

/** RFC 3533 page assembly and an RFC 7845 Opus header/audio boundary mutation. */
internal object OggSharedHeaderFixtures {
    fun opus(): ByteArray {
        val head = "OpusHead".encodeToByteArray() + byteArrayOf(1, 1, 0, 0) +
            byteArrayOf(0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)
        val tags = "OpusTags".encodeToByteArray() + TagStressFixtures.commentBody(listOf("TITLE=Seed"))
        val packet = R128TagFixtures.audioPacket
        return OggBuilder()
            .page(31, 0, 0, 2, listOf(head))
            .page(31, 1, 0, 0, listOf(tags))
            .page(31, 2, 960, 0, listOf(packet))
            .page(31, 3, 2_880, 4, listOf(packet, packet))
            .build()
    }

    fun merge(bytes: ByteArray): ByteArray {
        val pages = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            val segments = bytes[offset + 26].toInt() and 255
            val size = 27 + segments + (0 until segments).sumOf { bytes[offset + 27 + it].toInt() and 255 }
            pages += bytes.copyOfRange(offset, offset + size)
            offset += size
        }
        require(pages.size >= 3)
        val header = pages[1]
        val audio = pages[2]
        val headerSegments = header[26].toInt() and 255
        val audioSegments = audio[26].toInt() and 255
        require(headerSegments + audioSegments <= 255)
        val merged = header.copyOfRange(0, 27) +
            header.copyOfRange(27, 27 + headerSegments) + audio.copyOfRange(27, 27 + audioSegments) +
            header.copyOfRange(27 + headerSegments, header.size) + audio.copyOfRange(27 + audioSegments, audio.size)
        merged[26] = (headerSegments + audioSegments).toByte()
        merged[5] = ((header[5].toInt() and 3) or (audio[5].toInt() and 4)).toByte()
        audio.copyInto(merged, 6, 6, 14)
        checksum(merged)
        val output = ByteArrayAudioSink()
        output.write(pages[0])
        output.write(merged)
        for (index in 3 until pages.size) {
            val page = pages[index]
            repeat(4) { page[18 + it] = ((index - 1) ushr (it * 8)).toByte() }
            checksum(page)
            output.write(page)
        }
        return output.toByteArray()
    }

    fun audioPackets(bytes: ByteArray): List<ByteArray> {
        val options = DecodeOptions(tolerant = true)
        val ogg = OggReader(SourceReader(bytes.asAudioSource(), options), options)
        val reader = ogg.packets(ogg.readStreamHeads().first().serialNumber, 0)
        val packets = buildList {
            while (true) {
                val packet = reader.next() ?: break
                add(packet.bytes.copyOf(packet.length))
            }
        }
        return packets.drop(2)
    }

    private fun checksum(page: ByteArray) {
        page.fill(0, 22, 26)
        var crc = 0
        for (byte in page) {
            crc = crc xor ((byte.toInt() and 255) shl 24)
            repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04C11DB7 else 0 }
        }
        repeat(4) { page[22 + it] = (crc ushr (it * 8)).toByte() }
    }
}

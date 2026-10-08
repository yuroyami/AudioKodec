package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.ogg.OggReader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OggPageDamageTest {
    @Test
    fun tolerantPlaybackRetainsOnlyThePrefixBeforePageDamage() {
        val original = OggDamageFixtures.opus()
        val baseline = AudioKodec.decode(original).samples
        for (kind in OggDamageFixtures.kinds) {
            val bytes = OggDamageFixtures.damage(original, 5, kind)
            for (knownSize in listOf(false, true)) {
                for (reuse in listOf(false, true)) {
                    val options = DecodeOptions(tolerant = true, reuseBuffers = reuse)
                    AudioKodec.open(OggDamageFixtures.source(bytes, knownSize), options).use { decoder ->
                        assertEquals(2_880L, decoder.durationFrames, kind)
                        var count = 0L
                        while (true) {
                            val block = decoder.nextFrame() ?: break
                            assertEquals(count, block.positionFrames, kind)
                            assertContentEquals(baseline.copyOfRange(count.toInt(), count.toInt() + block.frameCount),
                                block.samples.copyOf(block.sampleCount), kind)
                            count += block.frameCount
                        }
                        assertEquals(2_880L, count, kind)
                        repeat(2) { assertNull(decoder.nextFrame()) }
                        assertEquals(960L, decoder.seek(960))
                        assertEquals(960L, assertNotNull(decoder.nextFrame()).positionFrames)
                    }
                }
            }
            assertFailsWith<AudioKodecException> { AudioKodec.decode(bytes) }
        }
    }

    @Test
    fun damageBeforeAnyUsableAudioStillHasATypedFailure() {
        for (kind in OggDamageFixtures.kinds) {
            val bytes = OggDamageFixtures.damage(OggDamageFixtures.opus(), 2, kind)
            for (knownSize in listOf(false, true)) {
                assertFailsWith<AudioKodecException>(kind) {
                    AudioKodec.decode(OggDamageFixtures.source(bytes, knownSize), DecodeOptions(tolerant = true))
                }
            }
        }
    }

    @Test
    fun aBrokenContinuationCannotJoinAnUnfinishedPacketToLaterAudio() {
        for (kind in listOf("checksum", "missing", "continuation")) {
            val bytes = OggBuilder()
                .rawPage(91, 0, -1, 2, intArrayOf(1, 255), byteArrayOf(7) + ByteArray(255) { 8 })
                .rawPage(91, 1, 30, 1, intArrayOf(1, 1), byteArrayOf(9, 10))
                .page(91, 2, 40, 4, listOf(byteArrayOf(11)))
                .build()
            val damaged = OggDamageFixtures.damage(bytes, 1, kind)
            val options = DecodeOptions(tolerant = true)
            val packets = OggReader(SourceReader(damaged.asAudioSource(), options), options).packets(91, 0)
            val first = assertNotNull(packets.next())
            assertContentEquals(byteArrayOf(7), first.bytes.copyOf(first.length))
            repeat(3) { assertNull(packets.next(), kind) }
        }
    }
}

/** Independent RFC 3533 page mutations shared by the common and FFmpeg tests. */
internal object OggDamageFixtures {
    val kinds = listOf("checksum", "missing", "continuation")

    fun opus(): ByteArray {
        val head = "OpusHead".encodeToByteArray() + byteArrayOf(1, 1, 0, 0) +
            byteArrayOf(0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)
        val tags = "OpusTags".encodeToByteArray() + TagStressFixtures.commentBody(listOf("TITLE=Prefix"))
        val builder = OggBuilder().page(31, 0, 0, 2, listOf(head)).page(31, 1, 0, 0, listOf(tags))
        repeat(8) { builder.page(31, it + 2, (it + 1) * 960L, if (it == 7) 4 else 0,
            listOf(R128TagFixtures.audioPacket)) }
        return builder.build()
    }

    fun pages(bytes: ByteArray): List<IntRange> = buildList {
        var at = 0
        while (at < bytes.size) {
            val count = bytes[at + 26].toInt() and 255
            val size = 27 + count + (0 until count).sumOf { bytes[at + 27 + it].toInt() and 255 }
            add(at until at + size)
            at += size
        }
    }

    fun granule(bytes: ByteArray, page: IntRange): Long {
        var value = 0L
        repeat(8) { value = value or ((bytes[page.first + 6 + it].toLong() and 255) shl (8 * it)) }
        return value
    }

    fun damage(original: ByteArray, page: Int, kind: String): ByteArray {
        val range = pages(original)[page]
        if (kind == "missing") return original.copyOfRange(0, range.first) +
            original.copyOfRange(range.last + 1, original.size)
        return original.copyOf().also { bytes ->
            if (kind == "checksum") bytes[range.last] = (bytes[range.last].toInt() xor 1).toByte()
            else {
                bytes[range.first + 5] = (bytes[range.first + 5].toInt() xor 1).toByte()
                bytes.fill(0, range.first + 22, range.first + 26)
                var crc = 0
                for (at in range) {
                    crc = crc xor ((bytes[at].toInt() and 255) shl 24)
                    repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04C11DB7 else 0 }
                }
                repeat(4) { bytes[range.first + 22 + it] = (crc ushr (8 * it)).toByte() }
            }
        }
    }

    fun source(bytes: ByteArray, knownSize: Boolean): AudioSource = object : AudioSource {
        private val delegate = bytes.asAudioSource()
        override val size: Long = if (knownSize) bytes.size.toLong() else -1
        override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int =
            delegate.readAt(position, into, offset, count)
        override fun close() = Unit
    }
}

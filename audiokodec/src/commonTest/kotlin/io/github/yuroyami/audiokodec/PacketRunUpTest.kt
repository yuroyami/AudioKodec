package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.PacketCodec
import io.github.yuroyami.audiokodec.internal.PacketIndex
import io.github.yuroyami.audiokodec.internal.PacketStreamDecoder
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PacketRunUpTest {
    @Test
    fun openingAnEditUsesTheSameRunUpAsSeekingToZero() {
        for (trim in listOf(7L, 27L, 47L)) for (unknown in listOf(false, true)) {
            val codec = StatefulPackets(20)
            decoder(codec, trim, unknown).use { decoder ->
                assertEquals(emptyList(), codec.decoded)
                val first = assertNotNull(decoder.nextFrame())
                assertEquals(0L, first.positionFrames)
                assertEquals(3, first.frameCount)
                assertEquals(if (trim == 47L) listOf(3, 4, 5) else (1..(trim / 10 + 1).toInt()).toList(), codec.decoded)
                assertEquals(0L, decoder.seek(0))
                val sought = assertNotNull(decoder.nextFrame())
                assertEquals(first.frameCount, sought.frameCount)
                assertContentEquals(first.samples, sought.samples)
            }
        }
    }

    @Test
    fun independentPacketsStillStartAtTheFirstAudiblePacket() {
        val codec = StatefulPackets(0)
        decoder(codec, 47, false, byteArrayOf(-1, -1, -1, -1, 5, 6)).use { decoder ->
            assertNotNull(decoder.nextFrame())
            assertEquals(listOf(5), codec.decoded)
            assertEquals(0L, decoder.seek(0))
            assertNotNull(decoder.nextFrame())
            assertEquals(listOf(5), codec.decoded)
        }
    }

    @Test
    fun integerPacketOutputDropsTheRunUpToo() {
        val codec = StatefulPackets(20, exact = true)
        decoder(codec, 47, true).use { decoder ->
            val access = decoder as IntPcmAccess
            val first = assertNotNull(access.nextIntFrame())
            assertEquals(0L, first.positionFrames)
            assertEquals(3, first.frameCount)
            assertEquals(listOf(3, 4, 5), codec.decoded)
            assertEquals(0L, decoder.seek(0))
            assertContentEquals(first.samples, assertNotNull(access.nextIntFrame()).samples)
        }
    }

    private fun decoder(codec: StatefulPackets, trim: Long, unknown: Boolean,
        bytes: ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6)): PacketStreamDecoder {
        val options = DecodeOptions.DEFAULT
        val source = MpegTrailerFixtures.source(bytes, unknown)
        val index = PacketIndex(LongArray(6) { it.toLong() }, IntArray(6) { 1 }, LongArray(7) { it * 10L })
        val info = AudioInfo(AudioContainer.MP4, AudioCodec.PCM_INTEGER, codec.format, 60 - trim,
            codec.storedBitDepth, null, false, 0, 6)
        return PacketStreamDecoder.of(SourceReader(source, options), index, codec, info, trim, 60 - trim, options)
    }

    /** Packet state makes omitted run-up change output, as a predictive codec does. */
    private class StatefulPackets(override val preRollFrames: Long, exact: Boolean = false) : PacketCodec {
        override val format = AudioFormat(48_000, 1)
        override val storedBitDepth: Int? = if (exact) 16 else null
        override val maxFramesPerPacket = 10
        val decoded = ArrayList<Int>()
        private var state = 0
        private fun consume(packet: ByteArray): Int {
            val value = packet[0].toInt() and 255
            if (value == 255) throw CorruptStreamException("a damaged packet")
            decoded += value
            state = (state * 3 + value * 100) / 4
            return state
        }
        override fun decode(packet: ByteArray, length: Int, out: FloatArray): Int {
            out.fill(consume(packet) / 32_768f, 0, 10)
            return 10
        }
        override fun decodeInt(packet: ByteArray, length: Int, out: IntArray): Int {
            out.fill(consume(packet), 0, 10)
            return 10
        }
        override fun reset() {
            state = 0
            decoded.clear()
        }
    }
}

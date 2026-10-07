package io.github.yuroyami.audiokodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class CafImaTest {
    @Test
    fun codedAndUnspecifiedWidthsUseTheSamePackets() {
        for (channels in 1..2) {
            val original = file(channels, 0)
            val expected = AudioKodec.decode(original).samples
            for (width in intArrayOf(0, 4)) {
                val bytes = file(channels, width)
                assertEquals(192L, AudioKodec.probe(bytes).durationFrames)
                assertContentEquals(expected, AudioKodec.decode(bytes).samples)
                AudioKodec.open(bytes).use { decoder ->
                    assertEquals(192L, decoder.durationFrames)
                    for (target in longArrayOf(0, 1, 63, 64, 127, 128, 191, 192)) {
                        assertEquals(target, decoder.seek(target))
                        val frame = decoder.nextFrame()
                        if (target == 192L) {
                            assertEquals(null, frame)
                        } else {
                            assertNotNull(frame)
                            assertEquals(target, frame.positionFrames)
                            for (channel in 0 until channels) {
                                assertEquals(expected[target.toInt() * channels + channel], frame.samples[channel])
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun acceptingCodedWidthKeepsThePacketGeometryChecks() {
        for (channels in 1..2) {
            val original = file(channels, 4)
            val mutations = listOf(
                32 to intArrayOf(1, 2, 4, -1), // Format flags.
                36 to intArrayOf(0, 33 * channels, 35 * channels, -1), // Bytes per packet.
                40 to intArrayOf(0, 1, 63, 65, -1), // Frames per packet.
                48 to intArrayOf(1, 8, 16, 32, -1), // Bits per channel.
            )
            for ((at, values) in mutations) {
                for (value in values) {
                    val bytes = original.copyOf().also { putBigEndian(it, at, value.toLong(), 4) }
                    for (tolerant in listOf(false, true)) {
                        val options = DecodeOptions(tolerant = tolerant)
                        assertFailsWith<CorruptStreamException>("$channels channels, field $at, value $value") {
                            AudioKodec.probe(bytes, options)
                        }
                        assertFailsWith<CorruptStreamException> { AudioKodec.open(bytes, options).close() }
                    }
                }
            }
        }
    }

    private fun file(channels: Int, width: Int): ByteArray {
        val dataBytes = 3 * channels * 34
        return ByteArray(68 + dataBytes).also { bytes ->
            "caff".encodeToByteArray().copyInto(bytes)
            bytes[5] = 1
            "desc".encodeToByteArray().copyInto(bytes, 8)
            putBigEndian(bytes, 12, 32, 8)
            putBigEndian(bytes, 20, 44_100.0.toRawBits(), 8)
            "ima4".encodeToByteArray().copyInto(bytes, 28)
            putBigEndian(bytes, 36, 34L * channels, 4)
            putBigEndian(bytes, 40, 64, 4)
            putBigEndian(bytes, 44, channels.toLong(), 4)
            putBigEndian(bytes, 48, width.toLong(), 4)
            "data".encodeToByteArray().copyInto(bytes, 52)
            putBigEndian(bytes, 56, 4L + dataBytes, 8)
            for (at in 0 until dataBytes) {
                bytes[68 + at] = if (at % 34 < 2) 0 else if (at / 34 % channels == 0) 0x18 else 0x81.toByte()
            }
        }
    }

    private fun putBigEndian(bytes: ByteArray, at: Int, value: Long, size: Int) {
        for (i in 0 until size) bytes[at + i] = (value ushr ((size - 1 - i) * 8)).toByte()
    }
}

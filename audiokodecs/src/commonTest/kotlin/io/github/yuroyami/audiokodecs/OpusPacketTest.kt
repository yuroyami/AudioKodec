package io.github.yuroyami.audiokodecs

import io.github.yuroyami.audiokodecs.internal.opus.OpusPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checks how an Opus packet says what is inside it.
 *
 * The first byte of a packet decides which codec runs, how wide a band it
 * decodes and how long the frame is, and the rest of the header says how many
 * frames follow and where each one starts. All of it is arithmetic on a few
 * bytes, so it can be checked exactly rather than approximately, and a packet
 * that lies about its own lengths has to be refused rather than read past.
 */
class OpusPacketTest {

    private fun packet(config: Int, stereo: Boolean, code: Int, vararg rest: Int): ByteArray {
        val toc = (config shl 3) or (if (stereo) 4 else 0) or code
        val out = ByteArray(1 + rest.size)
        out[0] = toc.toByte()
        for (i in rest.indices) out[i + 1] = rest[i].toByte()
        return out
    }

    /**
     * The configuration number decides everything about how a frame decodes.
     *
     * The thirty-two of them are laid out so that the codec, the bandwidth and
     * the frame length each vary over a contiguous run, which is what lets a
     * stream change any of the three between packets.
     */
    @Test
    fun theConfigurationNumberNamesTheModeBandwidthAndLength() {
        // Speech coding, three bandwidths, four lengths each.
        for (config in 0 until 12) {
            val parsed = OpusPacket.parse(packet(config, false, 0, 1, 2, 3), 0, 4)
            assertEquals(OpusPacket.Mode.SILK, parsed.mode, "config $config: mode")
            val expected = intArrayOf(480, 960, 1920, 2880)[config and 3]
            assertEquals(expected, parsed.frameSize, "config $config: frame size")
        }
        // Both codecs together, only the two middle lengths.
        for (config in 12 until 16) {
            val parsed = OpusPacket.parse(packet(config, false, 0, 1, 2, 3), 0, 4)
            assertEquals(OpusPacket.Mode.HYBRID, parsed.mode, "config $config: mode")
            assertEquals(intArrayOf(480, 960)[config and 1], parsed.frameSize, "config $config")
        }
        // Music coding, four bandwidths, and shorter frames than speech gets.
        for (config in 16 until 32) {
            val parsed = OpusPacket.parse(packet(config, false, 0, 1, 2, 3), 0, 4)
            assertEquals(OpusPacket.Mode.CELT, parsed.mode, "config $config: mode")
            val expected = intArrayOf(120, 240, 480, 960)[config and 3]
            assertEquals(expected, parsed.frameSize, "config $config: frame size")
        }

        assertEquals(OpusPacket.Bandwidth.NARROW, OpusPacket.parse(packet(0, false, 0, 1), 0, 2).bandwidth)
        assertEquals(OpusPacket.Bandwidth.MEDIUM, OpusPacket.parse(packet(4, false, 0, 1), 0, 2).bandwidth)
        assertEquals(OpusPacket.Bandwidth.WIDE, OpusPacket.parse(packet(8, false, 0, 1), 0, 2).bandwidth)
        assertEquals(OpusPacket.Bandwidth.SUPER_WIDE, OpusPacket.parse(packet(12, false, 0, 1), 0, 2).bandwidth)
        assertEquals(OpusPacket.Bandwidth.FULL, OpusPacket.parse(packet(14, false, 0, 1), 0, 2).bandwidth)
        assertEquals(OpusPacket.Bandwidth.FULL, OpusPacket.parse(packet(31, false, 0, 1), 0, 2).bandwidth)

        assertTrue(OpusPacket.parse(packet(0, true, 0, 1), 0, 2).stereo, "the stereo bit")
        assertEquals(2, OpusPacket.parse(packet(0, true, 0, 1), 0, 2).channels)
        assertEquals(1, OpusPacket.parse(packet(0, false, 0, 1), 0, 2).channels)
    }

    /** One frame, and the whole rest of the packet is it. */
    @Test
    fun oneFrameTakesTheWholePacket() {
        val parsed = OpusPacket.parse(packet(16, false, 0, 9, 9, 9, 9), 0, 5)
        assertEquals(1, parsed.frames.size)
        assertEquals(1 until 5, parsed.frames[0])
        assertEquals(120, parsed.samples)
    }

    /**
     * Two frames of the same size state no length at all.
     *
     * What is left simply divides in two, which is why an odd number of bytes
     * is not a short frame but a corrupt packet.
     */
    @Test
    fun twoEqualFramesSplitWhatIsLeft() {
        val parsed = OpusPacket.parse(packet(16, false, 1, 1, 2, 3, 4), 0, 5)
        assertEquals(2, parsed.frames.size)
        assertEquals(1 until 3, parsed.frames[0])
        assertEquals(3 until 5, parsed.frames[1])
        assertEquals(240, parsed.samples)

        assertFailsWith<CorruptStreamException>("an odd number of bytes cannot be halved") {
            OpusPacket.parse(packet(16, false, 1, 1, 2, 3), 0, 4)
        }
    }

    /** Two frames of different sizes, where the first states its length. */
    @Test
    fun twoUnequalFramesStateTheFirstLength() {
        val parsed = OpusPacket.parse(packet(16, false, 2, 3, 1, 2, 3, 4, 5), 0, 7)
        assertEquals(2, parsed.frames.size)
        assertEquals(2 until 5, parsed.frames[0])
        assertEquals(5 until 7, parsed.frames[1])
    }

    /**
     * A length of 252 or more takes two bytes.
     *
     * The second byte counts fours, so the pair reaches 1275, which is the
     * largest frame the format allows.
     */
    @Test
    fun longFrameLengthsTakeTwoBytes() {
        val body = ByteArray(400) { 7 }
        val header = packet(16, false, 2, 252, 37)
        val bytes = header + body
        // 252 + 37 * 4 = 400.
        val parsed = OpusPacket.parse(bytes, 0, bytes.size)
        assertEquals(400, parsed.frames[0].last - parsed.frames[0].first + 1)
        assertEquals(0, parsed.frames[1].last - parsed.frames[1].first + 1)
    }

    /** Several frames of the same size, with the count in its own byte. */
    @Test
    fun manyEqualFramesShareOneCount() {
        // Four frames, no length fields, no padding.
        val bytes = packet(16, false, 3, 4) + ByteArray(8) { (it + 1).toByte() }
        val parsed = OpusPacket.parse(bytes, 0, bytes.size)
        assertEquals(4, parsed.frames.size)
        for (i in 0 until 4) {
            assertEquals(2 + i * 2 until 2 + (i + 1) * 2, parsed.frames[i], "frame $i")
        }
        assertEquals(480, parsed.samples)
    }

    /** Several frames of different sizes, all but the last stating a length. */
    @Test
    fun manyUnequalFramesEachStateTheirLength() {
        // Three frames of 2, 3 and whatever is left.
        val bytes = packet(16, false, 3, 0x80 or 3, 2, 3) + ByteArray(9) { 1 }
        val parsed = OpusPacket.parse(bytes, 0, bytes.size)
        assertEquals(3, parsed.frames.size)
        assertEquals(4 until 6, parsed.frames[0])
        assertEquals(6 until 9, parsed.frames[1])
        assertEquals(9 until 13, parsed.frames[2])
    }

    /**
     * Padding is stated in as many bytes as it takes and is not audio.
     *
     * A byte of 255 means "254 more, and keep reading", which is what lets a
     * packet be padded to any length at all.
     */
    @Test
    fun paddingIsCountedAndExcluded() {
        // Two equal frames plus three bytes of padding.
        val bytes = packet(16, false, 3, 0x40 or 2, 3) + ByteArray(4) { 1 } + ByteArray(3) { 0 }
        val parsed = OpusPacket.parse(bytes, 0, bytes.size)
        assertEquals(3, parsed.paddingBytes)
        assertEquals(2, parsed.frames.size)
        assertEquals(3 until 5, parsed.frames[0])
        assertEquals(5 until 7, parsed.frames[1])

        // A 255 byte carries 254 and says another byte follows.
        val big = packet(16, false, 3, 0x40 or 1, 255, 6) + ByteArray(2) { 1 } + ByteArray(260) { 0 }
        val parsedBig = OpusPacket.parse(big, 0, big.size)
        assertEquals(260, parsedBig.paddingBytes)
        assertEquals(1, parsedBig.frames.size)
        assertEquals(4 until 6, parsedBig.frames[0])
    }

    /** A packet that lies about its own sizes is refused, not read past. */
    @Test
    fun impossiblePacketsAreRefused() {
        assertFailsWith<CorruptStreamException>("an empty packet") {
            OpusPacket.parse(ByteArray(0), 0, 0)
        }
        assertFailsWith<CorruptStreamException>("no frames at all") {
            OpusPacket.parse(packet(16, false, 3, 0), 0, 2)
        }
        assertFailsWith<CorruptStreamException>("a frame longer than the packet") {
            OpusPacket.parse(packet(16, false, 2, 200, 1, 2), 0, 4)
        }
        assertFailsWith<CorruptStreamException>("more padding than the packet holds") {
            OpusPacket.parse(packet(16, false, 3, 0x40 or 1, 200), 0, 3)
        }
        assertFailsWith<CorruptStreamException>("frames that do not divide evenly") {
            OpusPacket.parse(packet(16, false, 3, 3) + ByteArray(4) { 1 }, 0, 6)
        }
        // Sixty milliseconds of speech, forty-eight times over, is far past the
        // 120 milliseconds a packet may hold.
        assertFailsWith<CorruptStreamException>("more than 120 milliseconds") {
            OpusPacket.parse(packet(3, false, 3, 48) + ByteArray(48) { 1 }, 0, 50)
        }
    }

    /** Sixty milliseconds is the longest frame, and a packet holds 120. */
    @Test
    fun aPacketHoldsAtMostAHundredAndTwentyMilliseconds() {
        // Two 60 ms frames is exactly the limit and is allowed.
        val bytes = packet(3, false, 3, 2) + ByteArray(4) { 1 }
        val parsed = OpusPacket.parse(bytes, 0, bytes.size)
        assertEquals(OpusPacket.MAX_SAMPLES, parsed.samples)
        assertEquals(2880 * 2, parsed.samples)
    }
}

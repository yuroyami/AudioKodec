package io.github.yuroyami.audiokodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CafAlacTest {
    @Test
    fun alacStateHonoursAllocationLimitsBeforeProbeOrOpen() {
        for ((frameLength, channels, limit) in listOf(Triple(4_096, 2, 16_384L), Triple(8_192, 1, 40_960L))) {
            val (bytes, _) = file(channels, frameLength = frameLength)
            for (tolerant in listOf(false, true)) {
                val options = DecodeOptions(tolerant = tolerant, maxAllocationBytes = limit)
                assertFailsWith<AllocationGuardException> { AudioKodec.probe(bytes, options) }
                assertFailsWith<AllocationGuardException> { AudioKodec.open(bytes, options).close() }
            }
            assertEquals(channels, AudioKodec.probe(bytes).format.channels)
            assertEquals(81, AudioKodec.decode(bytes).frames)
        }
        val (small, _) = file(1)
        val options = DecodeOptions(maxAllocationBytes = 1_024)
        assertEquals(1, AudioKodec.probe(small, options).format.channels)
        assertEquals(81, AudioKodec.decode(small, options).frames)
    }

    @Test
    fun aShortFinalPacketCorrectsTheDecodedLengthInBothModes() {
        for (channels in 1..2) {
            for (frames in listOf(intArrayOf(17), intArrayOf(64, 1), intArrayOf(64, 17), intArrayOf(64, 64))) {
                val (bytes, samples) = file(channels, frames)
                val actual = frames.sum()
                assertEquals(frames.size * 64L, AudioKodec.probe(bytes).durationFrames)
                for (tolerant in listOf(false, true)) {
                    val options = DecodeOptions(tolerant = tolerant)
                    val decoded = AudioKodec.decode(bytes, options)
                    assertEquals(actual, decoded.frames)
                    assertContentEquals(FloatArray(samples.size) { samples[it] / 32768f }, decoded.samples)
                    AudioKodec.open(bytes, options).use { decoder ->
                        assertEquals(actual.toLong(), decoder.durationFrames)
                        assertEquals(actual.toLong(), decoder.info.durationFrames)
                        var at = 0
                        while (true) {
                            val block = (decoder as IntPcmAccess).nextIntFrame() ?: break
                            assertEquals(at / channels.toLong(), block.positionFrames)
                            assertContentEquals(samples.copyOfRange(at, at + block.frameCount * channels), block.samples)
                            at += block.frameCount * channels
                        }
                        assertEquals(samples.size, at)
                        for (target in listOf(0, actual - 1, actual, actual + 1)) {
                            assertEquals(minOf(target, actual).toLong(), decoder.seek(target.toLong()))
                            val block = decoder.nextFrame()
                            if (target >= actual) {
                                assertNull(block)
                            } else {
                                assertNotNull(block)
                                assertEquals(samples[target * channels] / 32768f, block.samples[0])
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun appleStylePrimingAndRemainderStillTrimTheActualAudio() {
        val (bytes, samples) = file(2, priming = 5, remainder = 50, validFrames = 73)
        assertEquals(73L, AudioKodec.probe(bytes).durationFrames)
        val expected = samples.copyOfRange(10, 156)
        assertContentEquals(FloatArray(expected.size) { expected[it] / 32768f }, AudioKodec.decode(bytes).samples)
        AudioKodec.open(bytes).use { decoder ->
            assertEquals(73L, decoder.durationFrames)
            for (target in longArrayOf(0, 1, 58, 59, 72, 73)) {
                assertEquals(target, decoder.seek(target))
                val block = (decoder as IntPcmAccess).nextIntFrame()
                if (target == 73L) {
                    assertNull(block)
                } else {
                    assertNotNull(block)
                    assertEquals(target, block.positionFrames)
                    assertEquals(expected[target.toInt() * 2], block.samples[0])
                }
            }
        }
    }

    @Test
    fun missingAndDamagedFinalPacketsAreStillRejectedStrictly() {
        val (bytes, _) = file(2)
        for (missing in listOf(1, 8, 42)) {
            assertFailsWith<TruncatedStreamException> { AudioKodec.decode(bytes.copyOf(bytes.size - missing)) }
        }
        val (damaged, samples) = file(2, malformedLast = true)
        assertFailsWith<CorruptStreamException> { AudioKodec.decode(damaged) }
        val salvaged = AudioKodec.decode(damaged, DecodeOptions(tolerant = true))
        assertEquals(64, salvaged.frames)
        assertContentEquals(FloatArray(128) { samples[it] / 32768f }, salvaged.samples)
    }

    /** CAF packet tables with independently assembled, verbatim ALAC elements. */
    private fun file(
        channels: Int,
        frames: IntArray = intArrayOf(64, 17),
        priming: Int = 0,
        remainder: Int = 0,
        validFrames: Int = frames.size * 64 - priming - remainder,
        malformedLast: Boolean = false,
        frameLength: Int = 64,
    ): Pair<ByteArray, IntArray> {
        val samples = IntArray(frames.sum() * channels) { (it * 173 % 8192) - 4096 }
        var sample = 0
        val packets = frames.mapIndexed { index, count ->
            val values = samples.copyOfRange(sample, sample + count * channels)
            sample += values.size
            packet(channels, values, if (malformedLast && index == frames.lastIndex) 65 else count)
        }
        val description = be(44_100.0.toRawBits(), 8) + ascii("alac") + be(1, 4) +
            be(0, 4) + be(64, 4) + be(channels.toLong(), 4) + be(0, 4)
        val cookie = be(frameLength.toLong(), 4) + byteArrayOf(0, 16, 40, 10, 14, channels.toByte()) +
            be(255, 2) + be(0, 4) + be(0, 4) + be(44_100, 4)
        val table = be(packets.size.toLong(), 8) + be(validFrames.toLong(), 8) +
            be(priming.toLong(), 4) + be(remainder.toLong(), 4) +
            packets.fold(ByteArray(0)) { bytes, packet -> bytes + varint(packet.size) }
        val payload = packets.fold(ByteArray(0)) { bytes, packet -> bytes + packet }
        return (ascii("caff") + be(1, 2) + be(0, 2) + chunk("desc", description) +
            chunk("kuki", cookie) + chunk("pakt", table) + chunk("data", be(0, 4) + payload)) to samples
    }

    private fun packet(channels: Int, samples: IntArray, frames: Int): ByteArray {
        val bytes = ByteArray((58 + samples.size * 16 + 7) / 8)
        var bit = 0
        fun put(value: Int, count: Int) {
            for (shift in count - 1 downTo 0) {
                if (value ushr shift and 1 != 0) bytes[bit / 8] = (bytes[bit / 8].toInt() or (1 shl (7 - bit % 8))).toByte()
                bit++
            }
        }
        put(channels - 1, 3) // Single channel or channel pair.
        put(0, 4) // Element instance.
        put(0, 12) // Reserved.
        put(9, 4) // Explicit frame count, verbatim samples.
        put(frames, 32)
        for (sample in samples) put(sample, 16)
        put(7, 3) // End element, followed by byte alignment.
        return bytes
    }

    private fun varint(value: Int): ByteArray = if (value < 128) byteArrayOf(value.toByte()) else
        byteArrayOf(((value ushr 7) or 128).toByte(), (value and 127).toByte())

    private fun chunk(type: String, body: ByteArray): ByteArray = ascii(type) + be(body.size.toLong(), 8) + body
    private fun ascii(value: String): ByteArray = value.encodeToByteArray()
    private fun be(value: Long, count: Int): ByteArray = ByteArray(count) { (value ushr (8 * (count - 1 - it))).toByte() }
}

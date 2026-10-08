package io.github.yuroyami.audiokodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MpegTolerantFrameTest {
    @Test
    fun tolerantDecodingKeepsOnlyTheCompletePrefix() {
        for (case in MpegTolerantFrameFixtures.cases()) {
            val expected = AudioKodec.decode(case.prefix())
            assertEquals(case.frames * 3, expected.frames, case.name)
            assertEquals(case.frames * 4, AudioKodec.decode(case.prefix() + case.good).frames, case.name)
            val damaged = case.prefix() + case.damaged + case.good
            val options = DecodeOptions(tolerant = true)
            val recovered = AudioKodec.decode(damaged, options)
            assertContentEquals(expected.samples, recovered.samples, case.name)
            AudioKodec.open(damaged, options).use { decoder ->
                repeat(3) { index ->
                    val block = assertNotNull(decoder.nextFrame(), case.name)
                    assertEquals(index * case.frames.toLong(), block.positionFrames, case.name)
                    assertContentEquals(
                        expected.samples.copyOfRange(index * case.frames * case.channels, (index + 1) * case.frames * case.channels),
                        block.samples,
                        case.name,
                    )
                }
                repeat(3) { assertNull(decoder.nextFrame(), case.name) }
                assertEquals(expected.frames.toLong(), decoder.positionFrames, case.name)
                assertEquals(0L, decoder.seek(0))
                val first = assertNotNull(decoder.nextFrame(), case.name)
                assertContentEquals(expected.samples.copyOfRange(0, first.frameCount * case.channels), first.samples, case.name)
            }
        }
    }

    @Test
    fun strictDecodingRetainsTheTypedFailure() {
        for (case in MpegTolerantFrameFixtures.cases()) {
            AudioKodec.open(case.prefix() + case.damaged).use { decoder ->
                repeat(3) { assertNotNull(decoder.nextFrame(), case.name) }
                assertDamage(case) { decoder.nextFrame() }
                assertEquals(3L * case.frames, decoder.positionFrames, case.name)
            }
        }
    }

    @Test
    fun tolerantDecodingStillFailsWhenTheFirstFrameIsDamaged() {
        for (case in MpegTolerantFrameFixtures.cases()) {
            val bytes = case.damaged + case.good + case.good
            AudioKodec.open(bytes, DecodeOptions(tolerant = true)).use { decoder ->
                assertDamage(case) { decoder.nextFrame() }
                assertEquals(0L, decoder.positionFrames, case.name)
            }
        }
    }

    private fun assertDamage(case: MpegTolerantFrameFixtures.Case, action: () -> Unit) {
        if (case.truncated) assertFailsWith<TruncatedStreamException>(case.name, action)
        else assertFailsWith<CorruptStreamException>(case.name, action)
    }
}

internal object MpegTolerantFrameFixtures {
    class Case(
        val name: String,
        val good: ByteArray,
        val damaged: ByteArray,
        val frames: Int,
        val channels: Int,
        val truncated: Boolean = true,
    ) {
        fun prefix(): ByteArray = good + good + good
    }

    fun cases(): List<Case> {
        // MPEG-1, 44.1 kHz, mono: 32 kbit/s Layer I and 64 kbit/s Layer II.
        val layer1 = ByteArray(32).also { byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x10, 0xC0.toByte()).copyInto(it) }
        val layer2 = ByteArray(208).also { byteArrayOf(0xFF.toByte(), 0xFD.toByte(), 0x40, 0xC0.toByte()).copyInto(it) }
        val overallocated1 = layer1.copyOf().also { it.fill(0xEE.toByte(), 4, 20) }
        val overallocated2 = layer2.copyOf().also {
            // The first three subbands each request 65535 levels. Their 1840
            // allocation, scale and sample bits cannot fit in 1632 payload bits.
            it[4] = 0xFF.toByte()
            it[5] = 0xF0.toByte()
        }
        val layer3 = Mp3GranuleBoundaryFixtures.frame()
        val cutScalefactors = layer3.copyOf().also {
            // Three granule-channels use 3047 bits, leaving one bit for a final
            // granule whose scalefac_compress=14 needs a four-bit first read.
            putBits(it, 32 + 20 + 2 * 59, 1015, 12)
            putBits(it, 32 + 20 + 3 * 59, 1, 12)
            putBits(it, 32 + 20 + 3 * 59 + 12 + 9 + 8, 14, 4)
        }
        return listOf(
            Case("layer1", layer1, overallocated1, 384, 1),
            Case("layer2", layer2, overallocated2, 1152, 1),
            Case("layer3_scalefactors", layer3, cutScalefactors, 1152, 2),
            Case("layer3_main_data", layer3, Mp3GranuleBoundaryFixtures.frame(lastGranuleBits = 8), 1152, 2, truncated = false),
        )
    }

    private fun putBits(bytes: ByteArray, from: Int, value: Int, count: Int) {
        for (index in 0 until count) {
            val at = from + index
            val mask = 1 shl (7 - at % 8)
            val bit = value ushr (count - 1 - index) and 1
            bytes[at / 8] = ((bytes[at / 8].toInt() and mask.inv()) or (if (bit != 0) mask else 0)).toByte()
        }
    }
}

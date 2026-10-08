package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.bits.BitWriterMsb
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Mp3GranuleBoundaryTest {
    @Test
    fun anEmptyFinalGranuleAtTheEndOfMainDataKeepsTheWholeFrame() {
        val exact = Mp3GranuleBoundaryFixtures.file(padded = false)
        val padded = Mp3GranuleBoundaryFixtures.file(padded = true)
        val control = AudioKodec.decode(padded)
        assertEquals(12L * 1152, AudioKodec.probe(exact).durationFrames)
        assertEquals(12 * 1152, control.frames)
        assertTrue(control.samples.any { it != 0f }, "the fixture must exercise synthesis")
        for (tolerant in listOf(false, true)) {
            val decoded = AudioKodec.decode(exact, DecodeOptions(tolerant = tolerant))
            assertEquals(control.frames, decoded.frames)
            assertContentEquals(control.samples, decoded.samples)
        }
        AudioKodec.open(exact).use { decoder ->
            val target = 5L * 1152 + 17
            assertEquals(target, decoder.seek(target))
            val frame = assertNotNull(decoder.nextFrame())
            assertContentEquals(
                control.samples.copyOfRange(target.toInt() * 2, (target.toInt() + frame.frameCount) * 2),
                frame.samples,
            )
        }
    }

    @Test
    fun aMissingFinalGranuleIsRejectedBeforeSynthesisAdvances() {
        val good = Mp3GranuleBoundaryFixtures.frame()
        val damaged = Mp3GranuleBoundaryFixtures.frame(lastGranuleBits = 8)
        val control = AudioKodec.decode(good + good)
        AudioKodec.open(good + damaged + good).use { decoder ->
            assertNotNull(decoder.nextFrame())
            val error = assertFailsWith<CorruptStreamException> { decoder.nextFrame() }
            assertTrue(error.message.orEmpty().contains("main data"))
            assertEquals(1152L, decoder.positionFrames)
            val following = assertNotNull(decoder.nextFrame())
            assertContentEquals(control.samples.copyOfRange(1152 * 2, control.samples.size), following.samples)
        }
    }
}

/** ISO/IEC 11172-3 side information and count1 table B, without a bit reservoir. */
internal object Mp3GranuleBoundaryFixtures {
    fun file(padded: Boolean): ByteArray {
        val frame = frame(padded)
        return ByteArray(frame.size * 12).also { bytes ->
            repeat(12) { frame.copyInto(bytes, it * frame.size) }
        }
    }

    fun frame(padded: Boolean = false, lastGranuleBits: Int = 0): ByteArray {
        // MPEG-1 Layer III stereo, 128 kbit/s, 44.1 kHz, no checksum.
        val frame = ByteArray(if (padded) 418 else 417)
        byteArrayOf(0xFF.toByte(), 0xFB.toByte(), (if (padded) 0x92 else 0x90).toByte(), 0).copyInto(frame)
        val side = BitWriterMsb(32)
        side.writeBits(0, 9) // main_data_begin
        side.writeBits(0, 3) // private bits
        side.writeBits(0, 8) // scalefactor selection for both channels
        repeat(4) { granuleChannel ->
            side.writeBits(if (granuleChannel == 3) lastGranuleBits else 1016, 12)
            side.writeBits(0, 9) // big_values
            side.writeBits(178, 8) // scale the coherent signal by 2^-8 to keep its peak below 1
            side.writeBits(0, 4) // no scalefactor bits
            side.writeBits(0, 1) // long block
            side.writeBits(0, 15) // big-value Huffman tables
            side.writeBits(0, 4) // region0_count
            side.writeBits(0, 3) // region1_count
            side.writeBits(0, 1) // preflag
            side.writeBits(0, 1) // scalefac_scale
            side.writeBits(1, 1) // count1 table B
        }
        side.toByteArray().copyInto(frame, 4)
        // Each zero byte codes four +1 values with table B: four code bits
        // and four positive signs. Three lots of 127 bytes fill all 381 main
        // data bytes. The fourth granule-channel needs no bits at the end.
        return frame
    }
}

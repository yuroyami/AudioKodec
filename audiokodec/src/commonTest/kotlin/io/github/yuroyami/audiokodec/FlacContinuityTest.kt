package io.github.yuroyami.audiokodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FlacContinuityTest {
    @Test
    fun tolerantReadsStopAtInvalidNumberingAndDeclaredTotals() {
        for (bytes in damagedAfterFirstFrame()) {
            for (integers in listOf(false, true)) {
                AudioKodec.open(bytes, DecodeOptions(tolerant = true)).use { decoder ->
                    assertFirstFrame(decoder, integers)
                    repeat(3) { assertNull(nextCount(decoder, integers)) }
                    assertEquals(4096L, decoder.positionFrames)
                    assertEquals(0L, decoder.seek(0))
                    assertFirstFrame(decoder, integers)
                    assertNull(nextCount(decoder, integers))
                }
            }
            val decoded = AudioKodec.decode(bytes, DecodeOptions(tolerant = true))
            assertEquals(4096, decoded.frames)
            assertContentEquals(FloatArray(4096) { 0.125f }, decoded.samples)
        }
    }

    @Test
    fun strictReadsRetainTheTypedCorruptionError() {
        for (bytes in damagedAfterFirstFrame()) {
            for (integers in listOf(false, true)) {
                AudioKodec.open(bytes).use { decoder ->
                    assertFirstFrame(decoder, integers)
                    assertFailsWith<CorruptStreamException> { nextCount(decoder, integers) }
                    assertEquals(4096L, decoder.positionFrames)
                }
            }
        }
    }

    @Test
    fun invalidFirstFramesStillFailInTolerantMode() {
        val valid = FlacContinuityFixtures.valid()
        for (bytes in listOf(
            FlacContinuityFixtures.renumber(valid, 0, 3),
            FlacContinuityFixtures.withTotal(valid, 2048),
        )) {
            for (integers in listOf(false, true)) {
                AudioKodec.open(bytes, DecodeOptions(tolerant = true)).use { decoder ->
                    assertFailsWith<CorruptStreamException> { nextCount(decoder, integers) }
                    assertEquals(0L, decoder.positionFrames)
                }
            }
        }
    }

    private fun damagedAfterFirstFrame(): List<ByteArray> {
        val valid = FlacContinuityFixtures.valid()
        return listOf(
            FlacContinuityFixtures.renumber(valid, 1, 0),
            FlacContinuityFixtures.renumber(valid, 1, 3),
            FlacContinuityFixtures.withTotal(valid, 6000),
        )
    }

    private fun assertFirstFrame(decoder: AudioDecoder, integers: Boolean) {
        if (integers) {
            val block = (decoder as IntPcmAccess).nextIntFrame()
            assertNotNull(block)
            assertEquals(4096, block.frameCount)
            assertEquals(0L, block.positionFrames)
            assertContentEquals(IntArray(4096) { 4096 }, block.samples)
        } else {
            val block = decoder.nextFrame()
            assertNotNull(block)
            assertEquals(4096, block.frameCount)
            assertEquals(0L, block.positionFrames)
            assertContentEquals(FloatArray(4096) { 0.125f }, block.samples)
        }
    }

    private fun nextCount(decoder: AudioDecoder, integers: Boolean): Int? =
        if (integers) (decoder as IntPcmAccess).nextIntFrame()?.frameCount else decoder.nextFrame()?.frameCount
}

internal object FlacContinuityFixtures {
    fun samples(): FloatArray = FloatArray(8192) { if (it < 4096) 0.125f else -0.25f }

    fun valid(): ByteArray = AudioKodec.encode(samples(), AudioFormat(44_100, 1), EncodeTarget.flac(16))

    /** The fixture uses two 4096-sample constant mono frames, each eleven bytes. */
    fun renumber(original: ByteArray, frame: Int, number: Int): ByteArray = original.copyOf().also { bytes ->
        val first = AudioKodec.probe(bytes).dataOffset.toInt()
        assertEquals(first + 22, bytes.size)
        val at = first + frame * 11
        assertEquals(frame, bytes[at + 4].toInt())
        bytes[at + 4] = number.toByte()
        bytes[at + 5] = crc(bytes, at, at + 5, 8, 7).toByte()
        val checksum = crc(bytes, at, at + 9, 16, 0x8005)
        bytes[at + 9] = (checksum ushr 8).toByte()
        bytes[at + 10] = checksum.toByte()
    }

    fun withTotal(original: ByteArray, total: Int): ByteArray = original.copyOf().also { bytes ->
        // STREAMINFO's final 36 packed bits hold the total sample count.
        bytes[21] = (bytes[21].toInt() and 0xF0).toByte()
        for (i in 0..3) bytes[22 + i] = (total ushr (24 - 8 * i)).toByte()
    }

    /** FLAC's published CRC polynomials, independent of the production helper. */
    private fun crc(bytes: ByteArray, from: Int, until: Int, width: Int, polynomial: Int): Int {
        var value = 0
        val mask = (1 shl width) - 1
        for (at in from until until) {
            value = value xor ((bytes[at].toInt() and 255) shl (width - 8))
            repeat(8) {
                value = (if (value and (1 shl (width - 1)) != 0) (value shl 1) xor polynomial else value shl 1) and mask
            }
        }
        return value
    }
}

package io.github.yuroyami.audiokodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlacStreamCopyTrimTest {
    @Test
    fun tolerantReadsRebaseBothNumberingStrategiesAndIgnoreStaleTotals() {
        for ((original, frames) in listOf(FlacTrimFixtures.fixed() to 4_096,
            FlacTrimFixtures.fixed(withSeekTable = true) to 4_096, FlacTrimFixtures.variable() to 2_048)) {
            for (statedTotal in listOf(0, 1, 100_000)) {
                val bytes = FlacContinuityFixtures.withTotal(original, statedTotal)
                for (knownSize in listOf(false, true)) {
                    val options = DecodeOptions(tolerant = true)
                    val source = OggDamageFixtures.source(bytes, knownSize)
                    assertEquals(frames.toLong(), AudioKodec.probe(source, options).durationFrames)
                    assertNull(AudioKodec.verifyFlacMd5(bytes, options))
                    val decoded = AudioKodec.decode(bytes, options)
                    assertEquals(frames, decoded.frames)
                    assertContentEquals(FloatArray(frames) { -0.25f }, decoded.samples)
                    AudioKodec.open(OggDamageFixtures.source(bytes, knownSize), options).use { decoder ->
                        val integers = decoder as IntPcmAccess
                        val first = assertNotNull(integers.nextIntFrame())
                        assertEquals(0L, first.positionFrames)
                        assertContentEquals(IntArray(first.frameCount) { -8192 }, first.samples)
                        for (target in listOf(0L, 1L, frames / 2L, frames - 1L, frames.toLong())) {
                            assertEquals(target, decoder.seek(target))
                            val block = integers.nextIntFrame()
                            if (target == frames.toLong()) assertNull(block)
                            else {
                                assertNotNull(block)
                                assertEquals(target, block.positionFrames)
                                assertContentEquals(IntArray(block.frameCount) { -8192 }, block.samples)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun strictPlaybackNamesANonzeroStreamOrigin() {
        for (bytes in listOf(FlacTrimFixtures.fixed(), FlacTrimFixtures.variable())) {
            val error = assertFailsWith<CorruptStreamException> { AudioKodec.decode(bytes) }
            assertTrue(error.message.orEmpty().contains("stream starts at sample"), error.message)
        }
    }
}

/** RFC 9639 fixed-frame and variable-sample origins with original STREAMINFO. */
internal object FlacTrimFixtures {
    fun fixed(withSeekTable: Boolean = false): ByteArray {
        val base = FlacContinuityFixtures.valid()
        val original = if (withSeekTable) FlacSeekTableFixtures.validTable(base) else base
        val audio = AudioKodec.probe(original).dataOffset.toInt()
        return original.copyOf(audio) + original.copyOfRange(audio + 11, original.size)
    }

    fun variable(): ByteArray {
        val header = FlacContinuityFixtures.valid().copyOf(42)
        header[4] = 0x80.toByte()
        for (at in 8..11) header[at] = if (at % 2 == 0) 4 else 0
        header.fill(0, 12, 18) // No encoded frame size bound.
        return FlacContinuityFixtures.withTotal(header, 10_240) + frame(8_192) + frame(9_216)
    }

    private fun frame(number: Int): ByteArray {
        val prefix = byteArrayOf(0xFF.toByte(), 0xF9.toByte(), 0xA9.toByte(), 8,
            (0xE0 or (number ushr 12)).toByte(), (0x80 or ((number ushr 6) and 63)).toByte(),
            (0x80 or (number and 63)).toByte())
        val body = prefix + byteArrayOf(crc(prefix, 8, 7).toByte(), 0, 0xE0.toByte(), 0)
        val crc16 = crc(body, 16, 0x8005)
        return body + byteArrayOf((crc16 ushr 8).toByte(), crc16.toByte())
    }

    private fun crc(bytes: ByteArray, width: Int, polynomial: Int): Int {
        var value = 0
        val mask = (1 shl width) - 1
        for (byte in bytes) {
            value = value xor ((byte.toInt() and 255) shl (width - 8))
            repeat(8) { value = ((value shl 1) xor if (value and (1 shl (width - 1)) != 0) polynomial else 0) and mask }
        }
        return value
    }
}

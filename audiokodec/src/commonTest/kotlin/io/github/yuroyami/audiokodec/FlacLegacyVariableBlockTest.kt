package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.bits.BitWriterMsb
import io.github.yuroyami.audiokodec.internal.flac.FlacCrc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FlacLegacyVariableBlockTest {
    @Test
    fun legacySampleNumbersDecodeAndSeekWithEitherSourceSize() {
        val bytes = file(legacy = true)
        val expected = samples(legacy = true)
        for (tolerant in listOf(false, true)) {
            for (knownSize in listOf(false, true)) {
                val options = DecodeOptions(tolerant = tolerant)
                assertEquals(expected.size.toLong(), AudioKodec.probe(OggDamageFixtures.source(bytes, knownSize), options).durationFrames)
                TestSignals.assertExact(expected, AudioKodec.decode(OggDamageFixtures.source(bytes, knownSize), options).samples)
                AudioKodec.open(OggDamageFixtures.source(bytes, knownSize), options).use { decoder ->
                    for (target in listOf(143L, 0L, 95L, 64L, 31L, 96L)) {
                        assertEquals(target, decoder.seek(target))
                        val block = assertNotNull(decoder.nextFrame())
                        assertEquals(target, block.positionFrames)
                        TestSignals.assertExact(expected.copyOfRange(target.toInt(), target.toInt() + block.frameCount),
                            block.samples.copyOf(block.sampleCount))
                    }
                }
            }
        }
        assertNull(AudioKodec.verifyFlacMd5(bytes))
    }

    @Test
    fun modernFixedFramesWithUnequalMetadataBoundsKeepTheirFrameNumbers() {
        val bytes = file(legacy = false)
        val expected = samples(legacy = false)
        TestSignals.assertExact(expected, AudioKodec.decode(bytes).samples)
        for (knownSize in listOf(false, true)) {
            AudioKodec.open(OggDamageFixtures.source(bytes, knownSize)).use { decoder ->
                for (target in listOf(159L, 0L, 128L, 64L)) {
                    assertEquals(target, decoder.seek(target))
                    val block = assertNotNull(decoder.nextFrame())
                    TestSignals.assertExact(expected.copyOfRange(target.toInt(), target.toInt() + block.frameCount),
                        block.samples.copyOf(block.sampleCount))
                }
            }
        }
    }

    @Test
    fun damagedLegacyPositionsStillStopAtTheIntactPrefix() {
        val bytes = file(legacy = true, finalNumber = 97)
        assertFailsWith<CorruptStreamException> { AudioKodec.decode(bytes) }
        TestSignals.assertExact(samples(legacy = true).copyOf(96),
            AudioKodec.decode(bytes, DecodeOptions(tolerant = true)).samples)
    }

    @Test
    fun aLegacyStreamCopyCutUsesItsSampleOrigin() {
        val original = file(legacy = true)
        // Constant frames here occupy twelve bytes after STREAMINFO.
        val cut = original.copyOfRange(0, 42) + original.copyOfRange(54, original.size)
        val expected = samples(legacy = true).copyOfRange(64, 144)
        assertFailsWith<CorruptStreamException> { AudioKodec.decode(cut) }
        val options = DecodeOptions(tolerant = true)
        assertEquals(80L, AudioKodec.probe(cut, options).durationFrames)
        TestSignals.assertExact(expected, AudioKodec.decode(cut, options).samples)
        AudioKodec.open(cut, options).use { decoder ->
            for (target in listOf(79L, 0L, 32L, 16L)) {
                assertEquals(target, decoder.seek(target))
                val block = assertNotNull(decoder.nextFrame())
                TestSignals.assertExact(expected.copyOfRange(target.toInt(), target.toInt() + block.frameCount),
                    block.samples.copyOf(block.sampleCount))
            }
        }
    }

    private fun samples(legacy: Boolean): FloatArray {
        val lengths = if (legacy) intArrayOf(64, 32, 48) else intArrayOf(64, 64, 32)
        return FloatArray(lengths.sum()) { index ->
            when {
                index < lengths[0] -> 0.25f
                index < lengths[0] + lengths[1] -> -0.125f
                else -> 0.375f
            }
        }
    }

    /** Three constant subframes, written independently from the FLAC encoder. */
    private fun file(legacy: Boolean, finalNumber: Int? = null): ByteArray {
        val lengths = if (legacy) intArrayOf(64, 32, 48) else intArrayOf(64, 64, 32)
        val numbers = if (legacy) intArrayOf(0, 64, finalNumber ?: 96) else intArrayOf(0, 1, 2)
        val values = intArrayOf(8192, -4096, 12288)
        val metadata = BitWriterMsb()
        metadata.writeBits(32, 16)
        metadata.writeBits(64, 16)
        metadata.writeBits(0, 24)
        metadata.writeBits(0, 24)
        metadata.writeBits(8000, 20)
        metadata.writeBits(0, 3)
        metadata.writeBits(15, 5)
        metadata.writeBits(0, 4)
        metadata.writeBits(lengths.sum(), 32)
        repeat(16) { metadata.writeBits(0, 8) }
        var bytes = "fLaC".encodeToByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + metadata.toByteArray()
        for (index in lengths.indices) {
            val header = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x64, 0x08,
                numbers[index].toByte(), (lengths[index] - 1).toByte())
            val body = header + byteArrayOf(FlacCrc.crc8(header, 0, header.size).toByte(), 0,
                (values[index] ushr 8).toByte(), values[index].toByte())
            val crc = FlacCrc.crc16(body, 0, body.size)
            bytes += body + byteArrayOf((crc ushr 8).toByte(), crc.toByte())
        }
        return bytes
    }
}

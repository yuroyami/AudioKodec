package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.bits.BitWriterMsb
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Mp3SwitchedBlockTest {
    @Test
    fun eightKhzMixedBlocksKeepShortWindowsInsideTheLongScalefactorRegion() {
        for ((mixedLine, shortLine) in listOf(36 to 28, 40 to 37, 47 to 47, 60 to 52, 71 to 71)) {
            val mixed = AudioKodec.decode(Mp3SwitchedBlockFixtures.file(8_000,
                Mp3SwitchedBlockFixtures.Shape.MIXED, true, listOf(mixedLine)))
            val short = AudioKodec.decode(Mp3SwitchedBlockFixtures.file(8_000,
                Mp3SwitchedBlockFixtures.Shape.SHORT, true, listOf(shortLine)))
            assertContentEquals(short.samples, mixed.samples, "mixed line $mixedLine")
        }
    }

    @Test
    fun switchedBlocksKeepTheirDurationAndExactSeekTail() {
        for (rate in listOf(44_100, 22_050, 8_000)) {
            for (shape in Mp3SwitchedBlockFixtures.Shape.entries) {
                val bytes = Mp3SwitchedBlockFixtures.file(rate, shape)
                val decoded = AudioKodec.decode(bytes)
                val framesPerPacket = if (rate == 44_100) 1_152 else 576
                assertEquals(12 * framesPerPacket, decoded.frames, "$rate $shape")
                assertEquals(decoded.frames.toLong(), AudioKodec.probe(bytes).durationFrames)
                assertTrue(decoded.samples.any { it != 0f }, "$rate $shape must exercise synthesis")
                AudioKodec.open(bytes).use { decoder ->
                    val target = 5L * framesPerPacket + 37
                    assertEquals(target, decoder.seek(target))
                    val frame = assertNotNull(decoder.nextFrame())
                    assertContentEquals(
                        decoded.samples.copyOfRange(target.toInt(), target.toInt() + frame.frameCount),
                        frame.samples,
                        "$rate $shape seek",
                    )
                }
            }
        }
    }
}

/**
 * Reservoir-free mono packets using ISO Layer III side information and the
 * published table 1 codes: 1 for (0, 0), 01 for (1, 0), and 001 for (0, 1),
 * with a positive sign bit after each nonzero value.
 * Header and boundary constants are independent of the decoder's tables.
 */
internal object Mp3SwitchedBlockFixtures {
    enum class Shape(val blockType: Int, val mixed: Boolean = false) {
        NORMAL(0), START(1), SHORT(2), MIXED(2, true), STOP(3),
    }

    fun file(
        rate: Int,
        shape: Shape,
        sameTable: Boolean = false,
        impulses: List<Int>? = null,
        scaled: Boolean = false,
    ): ByteArray {
        val rates = listOf(44_100, 48_000, 32_000, 22_050, 24_000, 16_000, 11_025, 12_000, 8_000)
        val index = rates.indexOf(rate)
        require(index >= 0)
        val version1 = index < 3
        val header = byteArrayOf(
            0xFF.toByte(),
            (if (version1) 0xFB else if (index < 6) 0xF3 else 0xE3).toByte(),
            (0x90 or ((index % 3) shl 2)).toByte(),
            0xC0.toByte(),
        )
        val frameSize = (if (version1) 144 * 128_000 else 72 * 80_000) / rate
        val boundary = when {
            shape.blockType == 2 -> if (rate == 8_000) 72 else 36
            version1 -> 36
            rate == 8_000 -> 108
            else -> 54
        }
        val main = BitWriterMsb()
        val scalefactorBits = if (!scaled) 0 else when {
            shape.blockType != 2 -> 21
            !shape.mixed -> 36
            version1 -> 35
            else -> 33
        }
        val coefficients = impulses ?: if (sameTable) listOf(20, 40, 60) else
            listOf(boundary, boundary + 12, boundary + 24)
        val granuleBits = (if (sameTable) 288 else 288 - boundary / 2) +
            coefficients.sumOf { if (it % 2 == 0) 2 else 3 } + scalefactorBits
        repeat(if (version1) 2 else 1) {
            repeat(scalefactorBits) { main.writeBit(if (it % 3 == 1) 1 else 0) }
            for (line in 0 until 576 step 2) {
                if (!sameTable && line < boundary) continue // Table 0 consumes no bits.
                if (line in coefficients) {
                    main.writeBits(1, 2)
                    main.writeBit(0)
                } else if (line + 1 in coefficients) {
                    main.writeBits(1, 3)
                    main.writeBit(0)
                } else {
                    main.writeBit(1)
                }
            }
        }
        val side = BitWriterMsb()
        side.writeBits(0, if (version1) 9 else 8) // main_data_begin
        side.writeBits(0, if (version1) 5 else 1) // private bits
        if (version1) side.writeBits(0, 4) // scfsi
        repeat(if (version1) 2 else 1) {
            side.writeBits(granuleBits, 12)
            side.writeBits(288, 9)
            side.writeBits(202, 8) // Two bits of headroom for synthesis.
            side.writeBits(if (!scaled) 0 else if (version1) 5 else 101, if (version1) 4 else 9)
            side.writeBit(if (shape.blockType == 0) 0 else 1)
            if (shape.blockType == 0) {
                side.writeBits(if (sameTable) 1 else 0, 5)
                side.writeBits(1, 5)
                side.writeBits(1, 5)
                side.writeBits(7, 4)
                side.writeBits(0, 3)
            } else {
                side.writeBits(shape.blockType, 2)
                side.writeBit(if (shape.mixed) 1 else 0)
                side.writeBits(if (sameTable) 1 else 0, 5)
                side.writeBits(1, 5)
                side.writeBits(0, 9) // subblock_gain
            }
            if (version1) side.writeBit(0) // preflag
            side.writeBit(0) // scalefac_scale
            side.writeBit(0) // count1 table A
        }
        side.alignToByte()
        main.alignToByte()
        val sideBytes = side.toByteArray()
        val mainBytes = main.toByteArray()
        require(header.size + sideBytes.size + mainBytes.size <= frameSize)
        val frame = ByteArray(frameSize)
        header.copyInto(frame)
        sideBytes.copyInto(frame, header.size)
        mainBytes.copyInto(frame, header.size + sideBytes.size)
        return ByteArray(frameSize * 12).also { bytes ->
            repeat(12) { frame.copyInto(bytes, it * frameSize) }
        }
    }
}

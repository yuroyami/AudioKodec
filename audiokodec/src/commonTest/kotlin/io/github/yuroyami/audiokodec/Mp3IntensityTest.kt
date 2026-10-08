package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.bits.BitWriterMsb
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Mp3IntensityTest {
    @Test
    fun theUncodedHighestBandInheritsTheEqualStereoSplit() {
        for (shape in Mp3IntensityFixtures.Shape.entries) {
            val unsplit = AudioKodec.decode(Mp3IntensityFixtures.file(shape = shape, position = 7))
            val split = AudioKodec.decode(Mp3IntensityFixtures.file(shape = shape, position = 3))
            assertEquals(12 * 1_152, split.frames)
            assertTrue(unsplit.samples.any { abs(it) > 0.01f }, "$shape must exercise synthesis")
            for (frame in 0 until split.frames) {
                // The 50% gain has only final Float rounding to account for.
                val expected = unsplit.samples[frame * 2] * 0.5f
                assertTrue(abs(expected - split.samples[frame * 2]) < 1e-6f, "$shape left frame $frame")
                assertTrue(abs(expected - split.samples[frame * 2 + 1]) < 1e-6f, "$shape right frame $frame")
            }
        }
    }

    @Test
    fun anIllegalTransmittedPositionKeepsItsBandOutOfIntensityStereo() {
        for (rate in listOf(44_100, 48_000, 32_000)) {
            for (shape in Mp3IntensityFixtures.Shape.entries) {
                val line = Mp3IntensityFixtures.penultimateLine(rate, shape)
                val decoded = AudioKodec.decode(Mp3IntensityFixtures.file(rate, shape,
                    position = 7, leftLine = line))
                assertTrue(decoded.samples.any { abs(it) > 0.01f }, "$rate $shape")
                assertTrue((0 until decoded.frames).all { decoded.samples[it * 2 + 1] == 0f }, "$rate $shape")
            }
        }
    }
}

/**
 * Joint-stereo packets from published MPEG headers, side information and table
 * 1 codewords. No reservoir, Xing header or encoder trim is present. MPEG-1
 * compression 9 and MPEG-2 intensity compression 172 code two-bit positions
 * in every transmitted band. MPEG-1 positions 4 to 7 need three-bit
 * compression 13; position 7 disables intensity stereo.
 */
internal object Mp3IntensityFixtures {
    enum class Shape { LONG, SHORT, MIXED }

    private val rates = listOf(44_100, 48_000, 32_000, 22_050, 24_000, 16_000, 11_025, 12_000, 8_000)

    fun penultimateLine(rate: Int, shape: Shape): Int {
        val index = rates.indexOf(rate)
        require(index >= 0)
        return if (shape == Shape.LONG) {
            listOf(342, 330, 448, 464, 464, 464, 464, 464, 572)[index]
        } else {
            listOf(318, 300, 414, 396, 408, 402, 402, 402, 492)[index]
        }
    }

    fun file(
        rate: Int = 44_100,
        shape: Shape = Shape.LONG,
        position: Int = 3,
        midSide: Boolean = false,
        intensityScale: Int = 0,
        leftLine: Int? = null,
        rightLine: Int? = null,
    ): ByteArray {
        val index = rates.indexOf(rate)
        require(index >= 0)
        val version1 = index < 3
        require(position in 0..(if (version1) 7 else 3))
        require(intensityScale in 0..1)
        val top = if (shape == Shape.LONG) {
            listOf(500, 384, 550, 522, 540, 522, 522, 522, 574)[index]
        } else {
            listOf(408, 378, 540, 522, 540, 522, 522, 522, 498)[index]
        }
        val left = leftLine ?: top
        val leftPairs = 288
        val rightPairs = if (rightLine == null) 0 else rightLine / 2 + 1
        val leftBits = leftPairs + if (left % 2 == 0) 2 else 3
        val rightBits = if (rightLine == null) 0 else rightPairs + if (rightLine % 2 == 0) 2 else 3
        val bands = when (shape) {
            Shape.LONG -> 21
            Shape.SHORT -> 36
            Shape.MIXED -> if (version1) 35 else 33
        }
        val positionBits = if (version1 && position >= 4) 3 else 2
        val compression = if (version1) (if (position >= 4) 13 else 9) else 172 or intensityScale
        val header = byteArrayOf(0xFF.toByte(),
            (if (version1) 0xFB else if (index < 6) 0xF3 else 0xE3).toByte(),
            (0x90 or ((index % 3) shl 2)).toByte(),
            (if (midSide) 0x70 else 0x50).toByte())
        val side = BitWriterMsb()
        side.writeBits(0, if (version1) 9 else 8)
        side.writeBits(0, if (version1) 3 else 2)
        if (version1) side.writeBits(0, 8)
        val main = BitWriterMsb()
        repeat(if (version1) 2 else 1) {
            repeat(2) { channel ->
                val isRight = channel == 1
                side.writeBits(if (isRight) bands * positionBits + rightBits else leftBits, 12)
                side.writeBits(if (isRight) rightPairs else leftPairs, 9)
                side.writeBits(202, 8)
                side.writeBits(if (isRight) compression else 0, if (version1) 4 else 9)
                side.writeBit(if (shape == Shape.LONG) 0 else 1)
                if (shape == Shape.LONG) {
                    repeat(3) { side.writeBits(1, 5) }
                    side.writeBits(0, 4)
                    side.writeBits(0, 3)
                } else {
                    side.writeBits(2, 2)
                    side.writeBit(if (shape == Shape.MIXED) 1 else 0)
                    repeat(2) { side.writeBits(1, 5) }
                    side.writeBits(0, 9)
                }
                if (version1) side.writeBit(0)
                side.writeBit(0)
                side.writeBit(0)
                if (isRight) repeat(bands) { main.writeBits(position, positionBits) }
                writePairs(main, if (isRight) rightPairs else leftPairs, if (isRight) rightLine else left)
            }
        }
        side.alignToByte()
        main.alignToByte()
        val frameSize = (if (version1) 144 * 128_000 else 72 * 80_000) / rate
        val frame = ByteArray(frameSize)
        val sideBytes = side.toByteArray()
        val mainBytes = main.toByteArray()
        require(header.size + sideBytes.size + mainBytes.size <= frameSize)
        header.copyInto(frame)
        sideBytes.copyInto(frame, header.size)
        mainBytes.copyInto(frame, header.size + sideBytes.size)
        return ByteArray(frameSize * 12).also { bytes -> repeat(12) { frame.copyInto(bytes, it * frameSize) } }
    }

    private fun writePairs(bits: BitWriterMsb, pairs: Int, line: Int?) {
        repeat(pairs) { pair ->
            if (line != null && pair == line / 2) {
                bits.writeBits(1, if (line % 2 == 0) 2 else 3)
                bits.writeBit(0)
            } else {
                bits.writeBit(1)
            }
        }
    }
}

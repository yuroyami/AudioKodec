package io.github.yuroyami.kiteaudio

import io.github.yuroyami.kiteaudio.internal.bits.BitReaderLsb
import io.github.yuroyami.kiteaudio.internal.bits.BitReaderMsb
import io.github.yuroyami.kiteaudio.internal.bits.BitWriterMsb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BitReaderTest {

    @Test
    fun theMostSignificantBitComesOutFirst() {
        // 1011 0001
        val reader = BitReaderMsb(byteArrayOf(0xB1.toByte()))
        assertEquals(1, reader.readBit())
        assertEquals(0, reader.readBit())
        assertEquals(1, reader.readBit())
        assertEquals(1, reader.readBit())
        assertEquals(0, reader.readBit())
        assertEquals(0, reader.readBit())
        assertEquals(0, reader.readBit())
        assertEquals(1, reader.readBit())
    }

    @Test
    fun theLeastSignificantBitComesOutFirstInTheOtherOrder() {
        val reader = BitReaderLsb(byteArrayOf(0xB1.toByte()))
        assertEquals(1, reader.readBit())
        assertEquals(0, reader.readBit())
        assertEquals(0, reader.readBit())
        assertEquals(0, reader.readBit())
        assertEquals(1, reader.readBit())
        assertEquals(1, reader.readBit())
        assertEquals(0, reader.readBit())
        assertEquals(1, reader.readBit())
    }

    @Test
    fun aFieldThatCrossesAByteBoundaryIsStillOneNumber() {
        // 1010 1010 1100 1100, read as 4 then 8 then 4 bits.
        val reader = BitReaderMsb(byteArrayOf(0xAA.toByte(), 0xCC.toByte()))
        assertEquals(0xA, reader.readBits(4))
        assertEquals(0xAC, reader.readBits(8))
        assertEquals(0xC, reader.readBits(4))
    }

    @Test
    fun aFullWidthFieldKeepsItsTopBit() {
        val reader = BitReaderMsb(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        assertEquals(-1, reader.readBits(32))
    }

    @Test
    fun signedFieldsAreExtendedFromTheirOwnWidth() {
        // 1111 0111: as a 4-bit signed value that is -1, then 0111 is 7.
        val reader = BitReaderMsb(byteArrayOf(0xF7.toByte()))
        assertEquals(-1, reader.readSignedBits(4))
        assertEquals(7, reader.readSignedBits(4))
    }

    @Test
    fun aSixtyFourBitFieldSurvivesTheTripThroughLong() {
        val bytes = byteArrayOf(
            0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte(), 0xDE.toByte(), 0xF0.toByte(),
        )
        val reader = BitReaderMsb(bytes)
        assertEquals(0x123456789ABCDEF0uL.toLong(), reader.readBitsLong(64))
    }

    @Test
    fun unaryCountingStopsAtTheFirstOne() {
        // 0000 1 000, so four zeros then the terminator.
        val reader = BitReaderMsb(byteArrayOf(0x08))
        assertEquals(4, reader.readUnary())
        assertEquals(5, reader.bitPosition)
    }

    @Test
    fun aligningMovesToTheNextByteAndSaysHowFar() {
        val reader = BitReaderMsb(byteArrayOf(0xFF.toByte(), 0x00))
        reader.readBits(3)
        assertEquals(5, reader.alignToByte())
        assertTrue(reader.isByteAligned)
        assertEquals(1, reader.bytePosition)
        assertEquals(0, reader.alignToByte())
    }

    @Test
    fun peekingLeavesThePositionAlone() {
        val reader = BitReaderMsb(byteArrayOf(0xAB.toByte()))
        assertEquals(0xA, reader.peekBits(4))
        assertEquals(0, reader.bitPosition)
        assertEquals(0xA, reader.readBits(4))
        assertEquals(4, reader.bitPosition)
    }

    @Test
    fun readingPastTheEndIsReportedAsTruncation() {
        val reader = BitReaderMsb(byteArrayOf(0xFF.toByte()))
        reader.readBits(8)
        assertFailsWith<TruncatedStreamException> { reader.readBit() }
        assertFailsWith<TruncatedStreamException> { BitReaderMsb(ByteArray(1)).readBits(9) }
        assertFailsWith<TruncatedStreamException> { BitReaderLsb(ByteArray(1)).readBits(9) }
    }

    @Test
    fun theTwoOrdersDisagreeOnTheSameBytes() {
        val bytes = byteArrayOf(0x12, 0x34)
        assertEquals(0x1234, BitReaderMsb(bytes).readBits(16))
        // The other order reads the low bits of the first byte first, which
        // rebuilds the number the other way round.
        assertEquals(0x3412, BitReaderLsb(bytes).readBits(16))
    }

    @Test
    fun readingBitsOneAtATimeMatchesReadingThemTogether() {
        val bytes = ByteArray(64) { (it * 37 + 11).toByte() }
        for (width in 1..24) {
            val together = BitReaderMsb(bytes)
            val separately = BitReaderMsb(bytes)
            var read = 0
            while (read + width <= together.bitLength) {
                val whole = together.readBits(width)
                var pieces = 0
                repeat(width) { pieces = (pieces shl 1) or separately.readBit() }
                assertEquals(whole, pieces, "width $width at bit $read")
                read += width
            }
        }
    }

    @Test
    fun bitWindowsAndWriterRangesRejectInvalidArguments() {
        assertFailsWith<IllegalArgumentException> {
            BitReaderMsb(ByteArray(4), offset = 3, length = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            BitReaderLsb(ByteArray(4), offset = -1, length = 1)
        }

        val writer = BitWriterMsb()
        assertFailsWith<IllegalArgumentException> { writer.writeUnary(-1) }
        assertFailsWith<IllegalArgumentException> {
            writer.writeBytes(ByteArray(4), from = 3, to = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            BitWriterMsb(initialCapacity = -1)
        }
    }
}

package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.bits.BitReaderLsb
import io.github.yuroyami.audiokodec.internal.bits.BitReaderMsb
import io.github.yuroyami.audiokodec.internal.bits.BitWriterMsb
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
    fun everyWidthAtEveryAlignmentMatchesReadingBitByBitInsideARegion() {
        // A region in the middle of a larger array: the fast path must stop at the region's end, not the array's.
        val bytes = ByteArray(40) { (it * 151 + 29).toByte() }
        val offset = 5
        val length = 23
        for (width in 1..32) {
            for (start in 0 until 16) {
                val whole = BitReaderMsb(bytes, offset, length)
                val bitwise = BitReaderMsb(bytes, offset, length)
                whole.seekBits(start)
                bitwise.seekBits(start)
                var position = start
                while (position + width <= whole.bitLength) {
                    var expected = 0
                    repeat(width) { expected = (expected shl 1) or bitwise.readBit() }
                    assertEquals(expected, whole.readBits(width), "width $width from bit $start, at $position")
                    assertEquals(bitwise.bitPosition, whole.bitPosition)
                    position += width
                }
                assertFailsWith<TruncatedStreamException> { whole.readBits(width) }
            }
        }
    }

    @Test
    fun unaryCountsAgreeWithCountingBitByBitAcrossBytesAndAtTheEnd() {
        for (zeros in listOf(0, 1, 6, 7, 8, 9, 15, 16, 17, 23, 24, 40)) {
            for (start in 0 until 8) {
                val total = start + zeros + 1 + 9
                val bits = BooleanArray(total + 8) { false }
                bits[start + zeros] = true
                val bytes = ByteArray((bits.size + 7) / 8)
                for (i in bits.indices) if (bits[i]) bytes[i / 8] = (bytes[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
                val reader = BitReaderMsb(bytes)
                reader.seekBits(start)
                assertEquals(zeros, reader.readUnary(), "$zeros zeros from bit $start")
                assertEquals(start + zeros + 1, reader.bitPosition)
            }
        }
        // No one bit before the region ends.
        val silent = BitReaderMsb(ByteArray(3))
        silent.seekBits(5)
        assertFailsWith<TruncatedStreamException> { silent.readUnary() }
        val inside = BitReaderMsb(byteArrayOf(0, 0, 0, 0x80.toByte()), 0, 3)
        assertFailsWith<TruncatedStreamException> { inside.readUnary() }
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

package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.vorbis.VorbisBitReader
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class VorbisBitReaderTest {

    /** [count] bits from bit [at], lowest first, reading zero past the packet's end. */
    private fun expected(bytes: ByteArray, length: Int, at: Int, count: Int): Int {
        var value = 0
        for (i in 0 until count) {
            val index = at + i
            val bit = if (index >= length * 8) 0 else (bytes[index ushr 3].toInt() ushr (index and 7)) and 1
            value = value or (bit shl i)
        }
        return value
    }

    @Test
    fun readsAndPeeksMatchTheBitsTakenOneByOne() {
        val random = Random(3)
        repeat(200) {
            val bytes = ByteArray(40).also { random.nextBytes(it) }
            // The packet is shorter than its buffer, so no read may look past its length.
            val length = random.nextInt(0, bytes.size + 1)
            val reader = VorbisBitReader(bytes, length)
            var at = 0
            var ended = false
            while (at < length * 8 || !ended) {
                val count = random.nextInt(0, 33)
                assertEquals(expected(bytes, length, at, count), reader.peekBits(count), "peek $count at $at of ${length * 8}")
                assertEquals(expected(bytes, length, at, count), reader.readBits(count), "read $count at $at of ${length * 8}")
                ended = ended || at + count > length * 8
                at = minOf(at + count, length * 8)
                assertEquals(at, reader.bitPosition, "position")
                assertEquals(ended, reader.endOfPacket, "end of packet after $count bits")
            }
        }
    }
}

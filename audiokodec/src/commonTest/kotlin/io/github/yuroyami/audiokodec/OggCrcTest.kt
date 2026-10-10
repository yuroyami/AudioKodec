package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.ogg.OggCrc
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class OggCrcTest {

    /** The checksum one bit at a time, straight from its definition. */
    private fun reference(bytes: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor ((bytes[i].toInt() and 0xFF) shl 24)
            repeat(8) {
                crc = if (crc and 0x8000_0000.toInt() != 0) (crc shl 1) xor 0x04C1_1DB7 else crc shl 1
            }
        }
        return crc
    }

    @Test
    fun eightBytesAtATimeGiveTheSameChecksum() {
        val bytes = ByteArray(300).also { Random(7).nextBytes(it) }
        for (from in 0..9) {
            for (to in from..bytes.size step 7) {
                assertEquals(reference(bytes, from, to), OggCrc.of(bytes, from, to), "bytes $from until $to")
            }
        }
    }

    @Test
    fun aPageChecksumReadsItsOwnFieldAsZero() {
        val random = Random(11)
        repeat(50) {
            val page = ByteArray(random.nextInt(27, 400)).also { random.nextBytes(it) }
            val zeroed = page.copyOf().also { for (i in 22 until 26) it[i] = 0 }
            assertEquals(reference(zeroed, 0, zeroed.size), OggCrc.ofPage(page, 0, page.size, 22))
        }
    }

    @Test
    fun aHeaderAndAPayloadCheckLikeOnePage() {
        val random = Random(13)
        repeat(50) {
            val header = ByteArray(random.nextInt(27, 60)).also { random.nextBytes(it) }
            val payload = ByteArray(random.nextInt(0, 300)).also { random.nextBytes(it) }
            val whole = header + payload
            assertEquals(
                OggCrc.ofPage(whole, 0, whole.size, 22),
                OggCrc.update(OggCrc.ofPage(header, 0, header.size, 22), payload, 0, payload.size),
            )
        }
    }
}

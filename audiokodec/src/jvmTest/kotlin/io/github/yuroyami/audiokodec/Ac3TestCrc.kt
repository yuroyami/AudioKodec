package io.github.yuroyami.audiokodec

/**
 * The two CRCs of an AC-3 syncframe, ATSC A/52:2012 section 7.10.1, for the
 * tests that write or alter frames.
 *
 * crc1 is chosen so that the register is zero at the 5/8 point. The register
 * is linear in the bits it is fed, so the crc1 that zeroes it is found from
 * the register's response to each of crc1's sixteen bits. crc2 is then the
 * register over the rest of the syncframe, which leaves it zero at the end.
 */
internal object Ac3TestCrc {

    /** The register of x^16 + x^15 + x^2 + 1 over [from] until [to], from zero, most significant bit first. */
    fun register(bytes: ByteArray, from: Int, to: Int): Int {
        var register = 0
        for (i in from until to) {
            register = register xor ((bytes[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                register = if (register and 0x8000 != 0) (register shl 1) xor 0x8005 else register shl 1
                register = register and 0xFFFF
            }
        }
        return register
    }

    /** Rewrites crc1 and crc2 of the [length]-byte syncframe at [at] in [bytes] so that both check. */
    fun repair(bytes: ByteArray, at: Int, length: Int) {
        val words = length / 2
        val fiveEighths = 2 * ((words shr 1) + (words shr 3))
        fun atFiveEighths(crc1: Int): Int {
            bytes[at + 2] = (crc1 ushr 8).toByte()
            bytes[at + 3] = crc1.toByte()
            return register(bytes, at + 2, at + fiveEighths)
        }
        val base = atFiveEighths(0)
        val response = IntArray(16) { atFiveEighths(1 shl it) xor base }
        val crc1 = (0 until 65_536).first { candidate ->
            var value = base
            for (i in 0 until 16) if (candidate and (1 shl i) != 0) value = value xor response[i]
            value == 0
        }
        atFiveEighths(crc1)
        val crc2 = register(bytes, at + 2, at + length - 2)
        bytes[at + length - 2] = (crc2 ushr 8).toByte()
        bytes[at + length - 1] = crc2.toByte()
        check(register(bytes, at + 2, at + length) == 0) { "the repaired syncframe's CRC" }
    }
}

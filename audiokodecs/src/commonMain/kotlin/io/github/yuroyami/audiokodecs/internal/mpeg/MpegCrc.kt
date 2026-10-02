package io.github.yuroyami.audiokodecs.internal.mpeg

import io.github.yuroyami.audiokodecs.CorruptStreamException
import io.github.yuroyami.audiokodecs.DecodeOptions

/**
 * The checksum a protected MPEG audio frame carries.
 *
 * ISO/IEC 11172-3 defines it as CRC-16 with the polynomial
 * x^16 + x^15 + x^2 + 1, a register starting at all ones, and the bits taken
 * most significant first. It covers the last sixteen bits of the frame header
 * and then whatever each layer protects: the bit allocation in Layer I, the
 * bit allocation and scalefactor selection in Layer II, and the side
 * information in Layer III. The stored value sits in the two bytes after the
 * header, and the protected bits start right after it.
 */
internal object MpegCrc {

    /** The checksum of [frame]'s header and its first [protectedBits] bits of payload. */
    fun compute(frame: ByteArray, protectedBits: Int): Int {
        var crc = 0xFFFF
        fun feed(bit: Int) {
            val top = (crc ushr 15) and 1
            crc = (crc shl 1) and 0xFFFF
            if (top xor bit != 0) crc = crc xor 0x8005
        }
        for (byte in 2..3) {
            val value = frame[byte].toInt() and 0xFF
            for (shift in 7 downTo 0) feed((value ushr shift) and 1)
        }
        for (index in 0 until protectedBits) {
            val value = frame[6 + (index ushr 3)].toInt() and 0xFF
            feed((value ushr (7 - (index and 7))) and 1)
        }
        return crc
    }

    /** The checksum [frame] states. */
    fun stored(frame: ByteArray): Int =
        ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)

    /**
     * Checks a protected frame.
     *
     * A mismatch is a typed failure in strict mode. Tolerant mode decodes the
     * frame as stored, as players do: what the checksum can see damaged is
     * often the checksum itself, and silence would be a worse guess.
     * [alsoAcceptBits], when not negative, is a second protected length whose
     * checksum is accepted as well, for the one place where readings of the
     * standard differ.
     */
    fun verify(
        frame: ByteArray,
        protectedBits: Int,
        options: DecodeOptions,
        frameOffset: Long,
        alsoAcceptBits: Int = -1,
    ) {
        if (options.tolerant) return
        val expected = stored(frame)
        val actual = compute(frame, protectedBits)
        if (expected != actual && (alsoAcceptBits < 0 || expected != compute(frame, alsoAcceptBits))) {
            throw CorruptStreamException(
                "an MPEG frame's checksum is 0x${expected.toString(16)} but its protected " +
                    "bits sum to 0x${actual.toString(16)}",
                frameOffset,
            )
        }
    }
}

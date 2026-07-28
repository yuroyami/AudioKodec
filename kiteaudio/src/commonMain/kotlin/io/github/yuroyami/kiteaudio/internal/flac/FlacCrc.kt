package io.github.yuroyami.kiteaudio.internal.flac

/**
 * The two checksums FLAC uses.
 *
 * A frame header ends with an eight-bit checksum over the header, and the
 * frame ends with a sixteen-bit checksum over everything including that
 * header. Together they make a frame boundary something a reader can verify
 * rather than assume, which is what makes seeking by scanning safe.
 */
internal object FlacCrc {

    /** Table for the polynomial x^8 + x^2 + x + 1, which is the header checksum. */
    private val CRC8: IntArray = IntArray(256) { index ->
        var value = index
        repeat(8) {
            value = if (value and 0x80 != 0) {
                ((value shl 1) xor 0x07) and 0xFF
            } else {
                (value shl 1) and 0xFF
            }
        }
        value
    }

    /** Table for the polynomial x^16 + x^15 + x^2 + 1, which is the frame checksum. */
    private val CRC16: IntArray = IntArray(256) { index ->
        var value = index shl 8
        repeat(8) {
            value = if (value and 0x8000 != 0) {
                ((value shl 1) xor 0x8005) and 0xFFFF
            } else {
                (value shl 1) and 0xFFFF
            }
        }
        value
    }

    fun crc8(bytes: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = CRC8[crc xor (bytes[i].toInt() and 0xFF)]
        }
        return crc
    }

    fun crc16(bytes: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = ((crc shl 8) xor CRC16[((crc shr 8) xor (bytes[i].toInt() and 0xFF)) and 0xFF]) and
                0xFFFF
        }
        return crc
    }
}

/**
 * MD5, as specified in RFC 1321.
 *
 * FLAC stores the MD5 of the unencoded audio in its stream information block.
 * That makes a decoder self-checking against the encoder that wrote the file:
 * if the decoded samples hash to the stored value, the decoding is right, and
 * the check works on files this library never produced.
 *
 * This is here for that verification and for nothing else. It is not offered
 * as a hash function, and MD5 must not be used where collision resistance
 * matters.
 */
internal class Md5 {

    private var a = 0x67452301
    private var b = 0xEFCDAB89.toInt()
    private var c = 0x98BADCFE.toInt()
    private var d = 0x10325476

    private val block = ByteArray(64)
    private var blockLength = 0
    private var totalBytes = 0L

    fun update(bytes: ByteArray, from: Int = 0, to: Int = bytes.size) {
        var position = from
        while (position < to) {
            val take = minOf(64 - blockLength, to - position)
            bytes.copyInto(block, blockLength, position, position + take)
            blockLength += take
            position += take
            totalBytes += take
            if (blockLength == 64) {
                transform(block)
                blockLength = 0
            }
        }
    }

    fun digest(): ByteArray {
        val bitLength = totalBytes * 8
        // Pad with a single one bit, then zeros, then the length.
        update(byteArrayOf(0x80.toByte()))
        totalBytes -= 1
        while (blockLength != 56) {
            update(byteArrayOf(0))
            totalBytes -= 1
        }
        val lengthBytes = ByteArray(8)
        for (i in 0 until 8) lengthBytes[i] = (bitLength ushr (8 * i)).toByte()
        update(lengthBytes)

        val out = ByteArray(16)
        writeLittleEndian(out, 0, a)
        writeLittleEndian(out, 4, b)
        writeLittleEndian(out, 8, c)
        writeLittleEndian(out, 12, d)
        return out
    }

    private fun writeLittleEndian(out: ByteArray, at: Int, value: Int) {
        out[at] = value.toByte()
        out[at + 1] = (value ushr 8).toByte()
        out[at + 2] = (value ushr 16).toByte()
        out[at + 3] = (value ushr 24).toByte()
    }

    private fun transform(chunk: ByteArray) {
        val m = IntArray(16)
        for (i in 0 until 16) {
            m[i] = (chunk[i * 4].toInt() and 0xFF) or
                ((chunk[i * 4 + 1].toInt() and 0xFF) shl 8) or
                ((chunk[i * 4 + 2].toInt() and 0xFF) shl 16) or
                ((chunk[i * 4 + 3].toInt() and 0xFF) shl 24)
        }

        var aa = a
        var bb = b
        var cc = c
        var dd = d

        for (i in 0 until 64) {
            val f: Int
            val g: Int
            when (i / 16) {
                0 -> {
                    f = (bb and cc) or (bb.inv() and dd)
                    g = i
                }
                1 -> {
                    f = (dd and bb) or (dd.inv() and cc)
                    g = (5 * i + 1) % 16
                }
                2 -> {
                    f = bb xor cc xor dd
                    g = (3 * i + 5) % 16
                }
                else -> {
                    f = cc xor (bb or dd.inv())
                    g = (7 * i) % 16
                }
            }
            val temp = dd
            dd = cc
            cc = bb
            val sum = aa + f + SINE[i] + m[g]
            bb += (sum shl SHIFTS[i]) or (sum ushr (32 - SHIFTS[i]))
            aa = temp
        }

        a += aa
        b += bb
        c += cc
        d += dd
    }

    private companion object {
        val SHIFTS = intArrayOf(
            7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
        )

        /** The constants are `floor(abs(sin(i + 1)) * 2^32)`, from RFC 1321. */
        val SINE = intArrayOf(
            -0x28955B88, -0x173848AA, 0x242070DB, -0x3E423112,
            -0x0A83F051, 0x4787C62A, -0x57CFB9ED, -0x02B96AFF,
            0x698098D8, -0x74BB0851, -0x0000A44F, -0x76A32842,
            0x6B901122, -0x02678E6D, -0x5986BC72, 0x49B40821,
            -0x09E1DA9E, -0x3FBF4CC0, 0x265E5A51, -0x16493856,
            -0x29D0EFA3, 0x02441453, -0x275E197F, -0x182C0438,
            0x21E1CDE6, -0x3CC8F82A, -0x0B2AF279, 0x455A14ED,
            -0x561C16FB, -0x03105C08, 0x676F02D9, -0x72D5B376,
            -0x0005C6BE, -0x788E097F, 0x6D9D6122, -0x021AC7F4,
            -0x5B4115BC, 0x4BDECFA9, -0x0944B4A0, -0x41404390,
            0x289B7EC6, -0x155ED806, -0x2B10CF7B, 0x04881D05,
            -0x262B2FC7, -0x1924661B, 0x1FA27CF8, -0x3B53A99B,
            -0x0BD6DDBC, 0x432AFF97, -0x546BDC59, -0x036C5FC7,
            0x655B59C3, -0x70F3336E, -0x00100B83, -0x7A7BA22F,
            0x6FA87E4F, -0x01D31920, -0x5CFEBCEC, 0x4E0811A1,
            -0x08AC817E, -0x42C50DCB, 0x2AD7D2BB, -0x14792C6F,
        )
    }
}

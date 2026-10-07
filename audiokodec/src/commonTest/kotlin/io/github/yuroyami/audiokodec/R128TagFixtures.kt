package io.github.yuroyami.audiokodec

/** Independent counted comments and Ogg framing for RFC 7845 gain cases. */
internal object R128TagFixtures {
    val valid = listOf(
        "0" to 0, "+0" to 0, "-0" to 0,
        "000000" to 0, "-00000" to 0, "+00000" to 0,
        "32767" to 32767, "-32768" to -32768, "+32767" to 32767,
        "001024" to 1024, "-01024" to -1024,
    )
    val invalid = listOf(
        "32768", "-32769", "2147483647", "-2147483648",
        "0000000", "+000000", "-000000", " 0", "0 ", "\t0", "0\n",
        "+", "-", "", "١", "１", "−1", "0 dB", "1.0", "1e2",
    )
    val invalidGains = listOf(
        -128.001, 128.0, 1000.0, -1000.0, 1.0e300, -1.0e300,
        Double.fromBits((-128.0).toBits() + 1),
        Double.fromBits(127.99609375.toBits() + 1),
    )
    val rounded = listOf(
        -128.0 to -32768, 127.99609375 to 32767,
        0.0 to 0, 1.0 / 256 to 1, -1.0 / 256 to -1,
        0.5 / 256 to 1, -0.5 / 256 to 0,
        -1.5 / 256 to -1, 1.5 / 256 to 2,
    )
    val audioPacket = byteArrayOf(0xF8.toByte(), 0xFF.toByte(), 0xFE.toByte())

    fun opus(comments: List<String> = emptyList()): ByteArray {
        val head = "OpusHead".encodeToByteArray() + byteArrayOf(1, 1, 0, 0) + u32(48_000) + byteArrayOf(0, 0, 0)
        val tags = "OpusTags".encodeToByteArray() + TagStressFixtures.commentBody(comments)
        return page(head, 0, 2, 0) + page(tags, 1, 0, 0) + page(audioPacket, 2, 4, 960)
    }

    fun id3(key: String, value: String): ByteArray {
        val body = byteArrayOf(3) + key.encodeToByteArray() + byteArrayOf(0) + value.encodeToByteArray() + byteArrayOf(0)
        val frame = "TXXX".encodeToByteArray() + syncsafe(body.size) + byteArrayOf(0, 0) + body
        return "ID3".encodeToByteArray() + byteArrayOf(4, 0, 0) + syncsafe(frame.size) + frame + ByteArray(32) { 0x55 }
    }

    private fun page(packet: ByteArray, sequence: Int, flags: Int, granule: Long): ByteArray {
        val lacing = ByteArray(packet.size / 255 + 1) { if (it == packet.size / 255) (packet.size % 255).toByte() else -1 }
        val header = "OggS".encodeToByteArray() + byteArrayOf(0, flags.toByte()) +
            ByteArray(8) { (granule ushr (it * 8)).toByte() } + u32(89) + u32(sequence) + ByteArray(4) + byteArrayOf(lacing.size.toByte())
        val bytes = header + lacing + packet
        var crc = 0
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 255) shl 24)
            repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04C11DB7 else 0 }
        }
        u32(crc).copyInto(bytes, 22)
        return bytes
    }

    private fun u32(value: Int): ByteArray = ByteArray(4) { (value ushr (it * 8)).toByte() }
    private fun syncsafe(value: Int): ByteArray = ByteArray(4) { ((value ushr (21 - it * 7)) and 127).toByte() }
}

package io.github.yuroyami.audiokodec

internal object TagStressFixtures {
    fun flac(comments: List<String>): ByteArray {
        val original = AudioKodec.encode(FloatArray(16), AudioFormat(44_100, 1), EncodeTarget.flac())
        var audioAt = 4
        while (true) {
            val last = original[audioAt].toInt() and 128 != 0
            val length = ((original[audioAt + 1].toInt() and 255) shl 16) or
                ((original[audioAt + 2].toInt() and 255) shl 8) or (original[audioAt + 3].toInt() and 255)
            audioAt += 4 + length
            if (last) break
        }
        val body = ByteArrayAudioSink()
        fun little(value: Int) = body.write(ByteArray(4) { (value ushr (it * 8)).toByte() })
        val vendor = "tag-test".encodeToByteArray()
        little(vendor.size)
        body.write(vendor)
        little(comments.size)
        for (comment in comments) {
            val bytes = comment.encodeToByteArray()
            little(bytes.size)
            body.write(bytes)
        }
        val bytes = body.toByteArray()
        val header = byteArrayOf(0x84.toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte())
        val streamInfo = original.copyOfRange(0, 42).also { it[4] = 0 }
        return streamInfo + header + bytes + original.copyOfRange(audioAt, original.size)
    }

    fun collidingText(value: Int, pairs: Int = 16): String = buildString {
        repeat(pairs) { bit -> append(if (value and (1 shl bit) == 0) "Aa" else "BB") }
    }
}

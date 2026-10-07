package io.github.yuroyami.audiokodec

internal object TagStressFixtures {
    fun flac(comments: List<String>, vendor: String = "tag-test"): ByteArray {
        val original = AudioKodec.encode(FloatArray(16), AudioFormat(44_100, 1), EncodeTarget.flac())
        var audioAt = 4
        while (true) {
            val last = original[audioAt].toInt() and 128 != 0
            val length = ((original[audioAt + 1].toInt() and 255) shl 16) or
                ((original[audioAt + 2].toInt() and 255) shl 8) or (original[audioAt + 3].toInt() and 255)
            audioAt += 4 + length
            if (last) break
        }
        val bytes = commentBody(comments, vendor)
        val header = byteArrayOf(0x84.toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte())
        val streamInfo = original.copyOfRange(0, 42).also { it[4] = 0 }
        return streamInfo + header + bytes + original.copyOfRange(audioAt, original.size)
    }

    fun commentBody(comments: List<String>, vendor: String = "tag-test"): ByteArray {
        val body = ByteArrayAudioSink()
        fun little(value: Int) = body.write(ByteArray(4) { (value ushr (it * 8)).toByte() })
        val vendorBytes = vendor.encodeToByteArray()
        little(vendorBytes.size)
        body.write(vendorBytes)
        little(comments.size)
        for (comment in comments) {
            val bytes = comment.encodeToByteArray()
            little(bytes.size)
            body.write(bytes)
        }
        return body.toByteArray()
    }

    fun flacAudio(bytes: ByteArray): ByteArray = bytes.copyOfRange(flacBlocks(bytes).last().second, bytes.size)

    fun flacComments(bytes: ByteArray): Pair<String, List<String>> {
        val block = flacBlocks(bytes).first { bytes[it.first].toInt() and 127 == 4 }
        return comments(bytes.copyOfRange(block.first + 4, block.second))
    }

    fun comments(body: ByteArray): Pair<String, List<String>> {
        var position = 0
        fun little(): Int {
            val value = (0..3).fold(0) { result, i -> result or ((body[position + i].toInt() and 255) shl (8 * i)) }
            position += 4
            return value
        }
        fun text(): String {
            val size = little()
            val value = body.decodeToString(position, position + size)
            position += size
            return value
        }
        val vendor = text()
        val count = little()
        return vendor to List(count) { text() }
    }

    private fun flacBlocks(bytes: ByteArray): List<Pair<Int, Int>> {
        val blocks = mutableListOf<Pair<Int, Int>>()
        var position = 4
        do {
            val start = position
            val last = bytes[position].toInt() and 128 != 0
            val length = ((bytes[position + 1].toInt() and 255) shl 16) or
                ((bytes[position + 2].toInt() and 255) shl 8) or (bytes[position + 3].toInt() and 255)
            position += 4 + length
            blocks.add(start to position)
        } while (!last)
        return blocks
    }

    fun collidingText(value: Int, pairs: Int = 16): String = buildString {
        repeat(pairs) { bit -> append(if (value and (1 shl bit) == 0) "Aa" else "BB") }
    }
}

package io.github.yuroyami.audiokodec

/** Independent empty-string layouts from the ID3v2.4 frame and structure specifications. */
internal object Id3EmptyValueFixtures {
    const val description = "Private empty values"
    val lists = listOf(listOf(""), listOf("", ""), listOf("A", ""), listOf("A", "", ""), listOf("", "A"), listOf("A", "", "B"))

    val unicodeLists = listOf(listOf("日本語", ""), listOf("日本語", "", ""))

    fun valuesFor(encoding: Id3TextListFixtures.Encoding): List<List<String>> =
        lists + if (encoding.code == 0) emptyList() else unicodeLists

    fun tag(
        encoding: Id3TextListFixtures.Encoding,
        values: List<String>,
        finalTerminator: Boolean = true,
        customDescription: String = description,
    ): ByteArray {
        val terminator = ByteArray(if (encoding.code == 1 || encoding.code == 2) 2 else 1)
        var encoded = byteArrayOf()
        for ((index, value) in values.withIndex()) {
            if (index != 0) encoded += terminator
            encoded += text(value, encoding)
        }
        if (finalTerminator) encoded += terminator
        val frames = frame("TOPE", byteArrayOf(encoding.code.toByte()) + encoded) +
            frame("TXXX", byteArrayOf(encoding.code.toByte()) + text(customDescription, encoding) + terminator + encoded)
        return header(frames)
    }

    fun singleFrame(key: String, body: ByteArray): ByteArray = header(frame(key, body))

    private fun text(value: String, encoding: Id3TextListFixtures.Encoding): ByteArray = when (encoding.code) {
        0 -> ByteArray(value.length) { value[it].code.toByte() }
        1, 2 -> {
            val units = ByteArray(value.length * 2) {
                val high = if (encoding.bigEndian) it % 2 == 0 else it % 2 != 0
                (value[it / 2].code ushr if (high) 8 else 0).toByte()
            }
            if (encoding.code == 2) units
            else (if (encoding.bigEndian) byteArrayOf(-2, -1) else byteArrayOf(-1, -2)) + units
        }
        else -> value.encodeToByteArray()
    }

    private fun frame(key: String, body: ByteArray): ByteArray =
        key.encodeToByteArray() + syncsafe(body.size) + byteArrayOf(0, 0) + body

    private fun header(frames: ByteArray): ByteArray =
        "ID3".encodeToByteArray() + byteArrayOf(4, 0, 0) + syncsafe(frames.size) + frames

    private fun syncsafe(value: Int): ByteArray = ByteArray(4) { ((value ushr (21 - it * 7)) and 127).toByte() }
}

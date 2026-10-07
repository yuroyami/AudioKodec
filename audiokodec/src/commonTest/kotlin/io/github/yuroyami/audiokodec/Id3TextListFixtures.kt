package io.github.yuroyami.audiokodec

/** Independent ID3v2.4 layouts from sections 4.2 and 4.2.6 of the frame specification. */
internal object Id3TextListFixtures {
    data class Encoding(val code: Int, val bigEndian: Boolean = false)

    val encodings = listOf(Encoding(0), Encoding(1), Encoding(1, true), Encoding(2, true), Encoding(3))
    val identifiers = listOf("id-one", "id-two")
    val emptyDescriptionValues = listOf("empty-first", "empty-second")

    fun artists(encoding: Encoding): List<String> =
        listOf("first", if (encoding.code == 0) "Björk" else "AĀ 日本語", "third")

    fun customValues(encoding: Encoding): List<String> =
        listOf("x", if (encoding.code == 0) "ÉA" else "ĀA 日本語", "x", "z")

    fun originalArtists(encoding: Encoding): List<String> = artists(encoding) + "first"

    fun description(encoding: Encoding): String =
        if (encoding.code == 0) "Private description" else "Private 日本語"

    fun tag(encoding: Encoding, finalTerminator: Boolean = false): ByteArray {
        val terminator = ByteArray(if (encoding.code == 1 || encoding.code == 2) 2 else 1)
        fun text(value: String): ByteArray = when (encoding.code) {
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
        fun frame(key: String, values: List<String>, description: String? = null): ByteArray {
            var payload = byteArrayOf(encoding.code.toByte())
            if (description != null) payload += text(description) + terminator
            for ((index, value) in values.withIndex()) {
                if (index != 0) payload += terminator
                payload += text(value)
            }
            if (finalTerminator) payload += terminator
            return key.encodeToByteArray() + syncsafe(payload.size) + byteArrayOf(0, 0) + payload
        }
        val body = frame("TIT2", listOf("Original")) +
            frame("TPE1", artists(encoding)) +
            frame("TOPE", originalArtists(encoding)) +
            frame("TXXX", identifiers, "MusicBrainz Artist Id") +
            frame("TXXX", customValues(encoding), description(encoding)) +
            frame("TXXX", emptyDescriptionValues, "")
        return "ID3".encodeToByteArray() + byteArrayOf(4, 0, 0) + syncsafe(body.size) + body
    }

    private fun syncsafe(value: Int): ByteArray = ByteArray(4) { ((value ushr (21 - it * 7)) and 127).toByte() }
}

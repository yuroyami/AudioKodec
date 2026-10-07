package io.github.yuroyami.audiokodec

/** Independent ID3 and APE layouts for loudness metadata outside the gain model. */
internal object LoudnessTagFixtures {
    val modelled = listOf(
        "replaygain_track_gain" to "-3.0 dB",
        "REPLAYGAIN_TRACK_PEAK" to "0.9",
        "ReplayGain_Album_Gain" to "-4.0 dB",
        "REPLAYGAIN_ALBUM_PEAK" to "0.8",
        "R128_TRACK_GAIN" to "256",
        "r128_album_gain" to "512",
    )
    val unmodelled = listOf(
        "REPLAYGAIN_REFERENCE_LOUDNESS" to "89.0 dB",
        "replaygain_track_range" to "6.0 dB",
        "R128_PRIVATE" to "",
        "replaygain_private" to "state",
    )
    val repeated = listOf("", "Björk 日本語", "", "same", "same", "")
    val binary = byteArrayOf(0, -1, -128, 7)
    const val binaryOwner = "REPLAYGAIN_TRACK_GAIN"

    fun id3(version: Int, encoding: Int, lists: Boolean = false, owner: Boolean = false): ByteArray {
        val entries = (modelled + unmodelled).map { it.first to listOf(it.second) } +
            if (lists) listOf("REPLAYGAIN_PRIVATE_LIST" to repeated) else emptyList()
        var frames = entries.fold(byteArrayOf()) { bytes, (description, values) ->
            val terminator = ByteArray(if (encoding == 1 || encoding == 2) 2 else 1)
            var valueBytes = byteArrayOf()
            for ((index, value) in values.withIndex()) {
                if (index > 0) valueBytes += terminator
                valueBytes += text(value, encoding)
            }
            if (values.last().isEmpty()) valueBytes += terminator
            val body = byteArrayOf(encoding.toByte()) + text(description, encoding) + terminator + valueBytes
            val key = if (version == 2) "TXX" else "TXXX"
            val size = when (version) {
                2 -> ByteArray(3) { (body.size ushr (16 - it * 8)).toByte() }
                3 -> ByteArray(4) { (body.size ushr (24 - it * 8)).toByte() }
                else -> syncsafe(body.size)
            }
            bytes + key.encodeToByteArray() + size +
                (if (version == 2) byteArrayOf() else byteArrayOf(0, 0)) + body
        }
        if (owner) {
            val body = binaryOwner.encodeToByteArray() + byteArrayOf(0) + binary
            frames += if (version == 2) {
                "UFI".encodeToByteArray() + ByteArray(3) { (body.size ushr (16 - it * 8)).toByte() } + body
            } else {
                "UFID".encodeToByteArray() +
                    (if (version == 3) ByteArray(4) { (body.size ushr (24 - it * 8)).toByte() } else syncsafe(body.size)) +
                    byteArrayOf(0, 0) + body
            }
        }
        return "ID3".encodeToByteArray() + byteArrayOf(version.toByte(), 0, 0) + syncsafe(frames.size) + frames
    }

    fun ape(version: Int, lists: Boolean = false): ByteArray {
        val textItems = (modelled + unmodelled).map { it.first to it.second } +
            if (lists) listOf("REPLAYGAIN_PRIVATE_LIST" to repeated.joinToString("\u0000")) else emptyList()
        val items = textItems.map { (key, value) ->
            Triple(key, if (version == 1) text(value, 0) else value.encodeToByteArray(), 0)
        } + if (version == 2) listOf(Triple("R128_PRIVATE_BYTES", binary, 2)) else emptyList()
        val body = items.fold(byteArrayOf()) { bytes, (key, value, flags) ->
            bytes + u32(value.size) + u32(flags) + key.encodeToByteArray() + byteArrayOf(0) + value
        }
        fun preamble(flags: Int): ByteArray = "APETAGEX".encodeToByteArray() +
            u32(version * 1000) + u32(body.size + 32) + u32(items.size) + u32(flags) + ByteArray(8)
        return (if (version == 2) preamble((1 shl 31) or (1 shl 29)) else byteArrayOf()) +
            body + preamble(if (version == 2) 1 shl 31 else 0)
    }

    private fun text(value: String, encoding: Int): ByteArray = when (encoding) {
        0 -> ByteArray(value.length) { value[it].code.toByte() }
        1, 2 -> {
            val units = ByteArray(value.length * 2) { (value[it / 2].code ushr if (it % 2 == 0) 8 else 0).toByte() }
            (if (encoding == 1) byteArrayOf(-2, -1) else byteArrayOf()) + units
        }
        else -> value.encodeToByteArray()
    }

    private fun u32(value: Int): ByteArray = ByteArray(4) { (value ushr (it * 8)).toByte() }
    private fun syncsafe(value: Int): ByteArray = ByteArray(4) { ((value ushr (21 - it * 7)) and 127).toByte() }
}

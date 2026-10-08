package io.github.yuroyami.audiokodec.internal.tag

import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagFormat

/** EBU Tech 3285, version 2.0, sections 2.3 and 2.4. */
internal object BextTagWriter {
    private val widths = linkedMapOf("Description" to (0 to 256), "Originator" to (256 to 32),
        "OriginatorReference" to (288 to 32), "OriginationDate" to (320 to 10), "OriginationTime" to (330 to 8))
    private val keys = widths.keys + setOf("TimeReference", "Version", "CodingHistory", "Extension")

    /** A selected format with no supplied raw block removes the old chunk. */
    fun build(tags: TagBundle, options: DecodeOptions): ByteArray? {
        val blocks = tags.raw.blocksOf(TagFormat.BWF_BEXT)
        if (blocks.isEmpty()) return null
        if (blocks.size != 1) throw UnsupportedFeatureException("rewriting several Broadcast Wave chunks",
            "supply one BWF_BEXT raw block with the fields to write")
        val values = LinkedHashMap<String, String>()
        var extension: ByteArray? = null
        for (entry in blocks.single().entries) {
            if (entry.key !in keys) throw UnsupportedFeatureException("Broadcast Wave entry ${entry.key}")
            if (entry.key == "Extension") {
                if (extension != null || entry.binary?.size != 264 || entry.text != null) {
                    throw UnsupportedFeatureException("a Broadcast Wave extension must be one 264-byte binary entry")
                }
                extension = entry.binary
            } else {
                val text = entry.text ?: throw UnsupportedFeatureException("Broadcast Wave ${entry.key} must be text")
                if (entry.binary != null || values.put(entry.key, text) != null) {
                    throw UnsupportedFeatureException("Broadcast Wave ${entry.key} must have one text value")
                }
            }
        }
        val history = values["CodingHistory"].orEmpty()
        val appendEnding = history.isNotEmpty() && !history.endsWith("\r\n")
        val bodySize = 602L + history.length + if (appendEnding) 2 else 0
        val size = 8L + bodySize + bodySize % 2
        options.checkAllocation(size, "writing a Broadcast Wave chunk")
        if (size > Int.MAX_VALUE) throw AudioKodecException("a Broadcast Wave chunk is too large to represent")
        ascii(history, "CodingHistory")
        for (index in history.indices) {
            if (history[index] == '\r' && history.getOrNull(index + 1) != '\n' ||
                history[index] == '\n' && history.getOrNull(index - 1) != '\r'
            ) throw UnsupportedFeatureException("Broadcast Wave CodingHistory requires CRLF line endings")
        }
        val bytes = ByteArray(size.toInt())
        "bext".encodeToByteArray().copyInto(bytes)
        for (index in 0..3) bytes[4 + index] = (bodySize ushr (8 * index)).toByte()
        extension?.copyInto(bytes, 8 + 338)
        for ((key, location) in widths) {
            val (offset, width) = location
            val text = values[key].orEmpty().take(width)
            ascii(text, key)
            for (index in text.indices) bytes[8 + offset + index] = text[index].code.toByte()
        }
        values["TimeReference"]?.let { text ->
            val value = text.toULongOrNull() ?: throw UnsupportedFeatureException(
                "Broadcast Wave TimeReference must be an unsigned 64-bit decimal sample count")
            for (index in 0..7) bytes[8 + 338 + index] = (value shr (8 * index)).toByte()
        }
        val storedVersion = (bytes[8 + 346].toInt() and 255) or ((bytes[8 + 347].toInt() and 255) shl 8)
        val version = values["Version"]?.toIntOrNull() ?: if ("Version" in values) {
            throw UnsupportedFeatureException("Broadcast Wave Version must be 0, 1 or 2")
        } else storedVersion
        if (version !in 0..2) throw UnsupportedFeatureException("Broadcast Wave version $version")
        if (extension != null && version != storedVersion) throw UnsupportedFeatureException(
            "changing a stored Broadcast Wave version", "retain its version and extension together, or supply a new block")
        bytes[8 + 346] = version.toByte()
        bytes[8 + 347] = 0
        if (extension == null && version == 2) {
            // EBU Tech 3285 uses 0x7FFF for unknown loudness measurements.
            for (offset in 412 until 422 step 2) {
                bytes[8 + offset] = 0xFF.toByte()
                bytes[8 + offset + 1] = 0x7F
            }
        }
        for (index in history.indices) bytes[8 + 602 + index] = history[index].code.toByte()
        if (appendEnding) {
            bytes[8 + 602 + history.length] = 13
            bytes[8 + 603 + history.length] = 10
        }
        return bytes
    }

    fun requireNoModelledFields(tags: TagBundle) {
        val missing = tags.fields.filterValues { values -> values.any { it.isNotEmpty() } }.keys.map { it.name } +
            listOfNotNull("pictures".takeIf { tags.pictures.isNotEmpty() }, "chapters".takeIf { tags.chapters.isNotEmpty() },
                "ReplayGain".takeIf { tags.replayGain != null })
        if (missing.isNotEmpty()) throw UnsupportedFeatureException(
            "Broadcast Wave cannot retain ${missing.joinToString()}",
            "include RIFF_INFO or ID3v2 to retain the modelled metadata alongside BWF_BEXT")
    }

    private fun ascii(text: String, key: String) {
        if (text.any { it.code !in 1..127 }) throw UnsupportedFeatureException(
            "Broadcast Wave $key requires ASCII without embedded nulls")
    }
}

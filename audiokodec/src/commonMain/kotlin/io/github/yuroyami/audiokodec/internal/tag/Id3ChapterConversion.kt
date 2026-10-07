package io.github.yuroyami.audiokodec.internal.tag

import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.tag.RawTagBlock
import io.github.yuroyami.audiokodec.tag.RawTagEntry
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat

/** Converts embedded frames from the published frame layouts, without guessing unknown dialects. */
internal object Id3ChapterConversion {
    private val commonText = setOf("TALB", "TBPM", "TCOM", "TCOP", "TDLY", "TENC", "TEXT", "TFLT", "TIT1", "TIT2", "TIT3",
        "TKEY", "TLAN", "TLEN", "TMED", "TOAL", "TOFN", "TOLY", "TOPE", "TOWN", "TPE1", "TPE2", "TPE3", "TPE4", "TPOS",
        "TPUB", "TRCK", "TRSN", "TRSO", "TSRC", "TSSE")
    private val commonBinary = setOf("AENC", "ENCR", "ETCO", "GRID", "MCDI", "MLLT", "PCNT", "POPM", "POSS", "PRIV", "RBUF", "RVRB", "SYTC", "UFID")
    private val commonUrls = setOf("WCOM", "WCOP", "WOAF", "WOAR", "WOAS", "WORS", "WPAY", "WPUB")
    private val legacyDate = setOf("TYER", "TDAT", "TIME")

    fun convert(id: String, source: Id3ChapterSource, frames: List<Id3ChapterSource.Frame>, major: Int): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        val dates = frames.filter { it.id in legacyDate }
        var datesWritten = false
        for (stored in frames) {
            try {
                if (stored.opaque(source.major)) refuse("opaque or reserved frame flags")
                val payload = source.payload(stored)
                val converted = if (source.major == 3 && stored.id in legacyDate) {
                    if (datesWritten) continue
                    datesWritten = true
                    if (dates.any { it.opaque(3) || it.flags != stored.flags || source.payload(it).group != payload.group }) {
                        refuse("incompatible legacy date flags or group identities")
                    }
                    if (dates.map { it.id }.distinct().size != dates.size) refuse("repeated legacy date components")
                    val entries = dates.map { child ->
                        val values = Cursor(source.payload(child).bytes, source).values(1, 3)
                        RawTagEntry(child.id, text = values.singleOrNull() ?: refuse("ambiguous legacy date component"))
                    }
                    val combined = Id3Dates.combined(RawTagBlock(TagFormat.ID3V2_3, 0, 0, entries))
                        ?: refuse("unresolved legacy date precision")
                    listOf(Id3v2Writer.textFrame("TDRC", listOf(combined), major))
                } else convertPayload(stored.id, payload.bytes, source, major)
                for (encoded in converted) {
                    source.options.checkAllocation(encoded.size.toLong() + 5L, "converting chapter subframe '${stored.id}'")
                    val newId = encoded.decodeToString(0, 4)
                    val body = encoded.copyOfRange(10, encoded.size)
                    var flags = if (source.major == 3) (stored.flags and 0xE000) ushr 1 else (stored.flags and 0x7000) shl 1
                    // A read-only frame whose contents change must lose the bit.
                    if (newId != stored.id || !body.contentEquals(payload.bytes)) flags = flags and (if (major == 4) 0x1000 else 0x2000).inv()
                    val grouped = payload.group?.let { byteArrayOf(it.toByte()) } ?: byteArrayOf()
                    if (payload.group != null) flags = flags or (if (major == 4) 0x40 else 0x20)
                    // The length indicator and unsynchronisation are storage transforms;
                    // the clear payload does not need either in the destination version.
                    result.add(Id3v2Writer.frame(newId, grouped + body, major, flags))
                }
            } catch (error: UnsupportedFeatureException) {
                throw UnsupportedFeatureException(
                    "converting chapter '$id' subframe '${stored.id}' from ID3v2.${source.major} to ID3v2.$major",
                    "Chapter '$id', subframe '${stored.id}': ${error.message}. Retain ID3v2.${source.major} or replace the chapter explicitly.",
                )
            }
        }
        return result
    }

    /** Also validates identities before replacing readable title or picture frames. */
    fun validateEditable(source: Id3ChapterSource, stored: Id3ChapterSource.Frame) {
        if (stored.opaque(source.major)) refuse("unreadable '${stored.id}' identity")
        convertPayload(stored.id, source.payload(stored).bytes, source, source.major)
    }

    private fun convertPayload(id: String, bytes: ByteArray, source: Id3ChapterSource, major: Int): List<ByteArray> {
        if (id in commonBinary) return listOf(Id3v2Writer.frame(id, bytes, major))
        if (id in commonUrls) {
            val cursor = Cursor(bytes, source, encoded = false)
            val url = cursor.text(0, TagText.LATIN1)
            cursor.finish()
            return listOf(Id3v2Writer.rawFrame(id, RawTagEntry(id, text = url), major)!!)
        }
        val cursor = Cursor(bytes, source)
        when {
            id in commonText -> {
                val decoded = cursor.values(1, source.major)
                val values = if (TagMapping.id3UsesSlashList(id, source.major)) decoded.flatMap { it.split('/') } else decoded
                return listOf(Id3v2Writer.textFrame(id, Id3v2Writer.textValues(id, values, major, preserveDuplicates = true), major))
            }
            id == "TXXX" -> {
                val description = cursor.terminated(1)
                val values = cursor.values(cursor.position, source.major)
                return listOf(Id3v2Writer.userTextFrame(description, values, major, preserveDuplicates = true))
            }
            id == "TCON" -> {
                val values = cursor.values(1, source.major)
                val resolved = values.flatMap { Id3Genres.resolveAll(it) }
                if (resolved.isEmpty() || resolved.distinct().size != resolved.size) refuse("ambiguous or repeated genre values")
                return listOf(Id3v2Writer.textFrame(id, Id3Genres.storedValues(resolved, major), major))
            }
            id == "TDRC" || id == "TDOR" -> {
                if (source.major != 4 || major != 3) refuse("timestamp in a legacy frame dialect")
                val field = if (id == "TDRC") TagField.DATE else TagField.ORIGINAL_DATE
                val values = cursor.values(1, source.major)
                if (values.size != 1) refuse("multiple date values")
                return Id3Dates.legacyFields(field, values).map { (key, value) ->
                    Id3v2Writer.textFrame(key, listOf(value), major)
                }
            }
            id == "TORY" -> {
                if (source.major != 3 || major != 4) refuse("legacy original year in a timestamp frame dialect")
                val values = cursor.values(1, source.major)
                Id3Dates.legacyFields(TagField.ORIGINAL_DATE, values)
                return listOf(Id3v2Writer.textFrame("TDOR", values, major))
            }
            id == "WXXX" -> {
                val description = cursor.terminated(1)
                val url = cursor.text(cursor.position, TagText.LATIN1)
                cursor.finish()
                return listOf(Id3v2Writer.rawFrame(id, RawTagEntry(id, text = url, description = description), major)!!)
            }
            id == "COMM" || id == "USLT" -> {
                val language = cursor.take(1, 3).decodeToString()
                if (language.any { it !in 'a'..'z' && it !in 'A'..'Z' }) refuse("invalid language identity")
                val description = cursor.terminated(4)
                val value = cursor.text(cursor.position)
                cursor.finish()
                return listOf(Id3v2Writer.commentFrame(id, value, language, description, major))
            }
            id == "APIC" || id == "GEOB" -> {
                val mime = cursor.terminated(1, TagText.LATIN1)
                val role = if (id == "APIC") cursor.take(cursor.position, 1) else byteArrayOf()
                val filename = if (id == "GEOB") cursor.terminated(cursor.position) else null
                val description = cursor.terminated(cursor.position)
                val tail = bytes.copyOfRange(cursor.position, bytes.size)
                if (id == "APIC" && (role[0].toInt() and 255 > 20 || tail.isEmpty())) refuse("unreadable picture identity or data")
                val encoding = Id3v2Writer.encodingFor(listOfNotNull(filename, description), major)
                val payload = byteArrayOf(encoding.toByte()) + TagText.encodeLatin1(mime) + byteArrayOf(0) + role +
                    (filename?.let { terminated(it, encoding) } ?: byteArrayOf()) + terminated(description, encoding) + tail
                return listOf(Id3v2Writer.frame(id, payload, major))
            }
            id == "USER" -> {
                val language = cursor.take(1, 3)
                val value = cursor.text(4)
                cursor.finish()
                val encoding = Id3v2Writer.encodingFor(listOf(value), major)
                return listOf(Id3v2Writer.frame(id, byteArrayOf(encoding.toByte()) + language + Id3v2Writer.encodeText(value, encoding), major))
            }
            else -> refuse("no lossless conversion for this frame layout")
        }
    }

    private fun terminated(value: String, encoding: Int): ByteArray =
        Id3v2Writer.encodeText(value, encoding) + ByteArray(TagText.terminatorSize(encoding))

    private fun refuse(detail: String): Nothing = throw UnsupportedFeatureException(detail)

    /** Strict conversion cannot replace malformed text with an empty or shortened string. */
    private class Cursor(private val bytes: ByteArray, private val source: Id3ChapterSource, encoded: Boolean = true) {
        val encoding = if (encoded) bytes.firstOrNull()?.toInt()?.and(255) ?: refuse("missing text encoding") else TagText.LATIN1
        var position = if (encoded) 1 else 0
            private set

        init {
            if (encoding !in 0..(if (source.major == 4) 3 else 1)) refuse("unsupported text encoding $encoding")
        }

        fun take(at: Int, length: Int): ByteArray {
            if (at < 0 || at > bytes.size - length) throw CorruptStreamException("truncated chapter subframe data")
            position = at + length
            return bytes.copyOfRange(at, position)
        }

        fun terminated(at: Int, encoding: Int = this.encoding): String {
            val end = TagText.findTerminator(bytes, at, bytes.size, encoding)
            val value = decode(at, end, encoding)
            position = end + TagText.terminatorSize(encoding)
            if (position > bytes.size) refuse("missing string terminator")
            return value
        }

        fun text(at: Int, encoding: Int = this.encoding): String {
            if (at > bytes.size) refuse("truncated text")
            val end = TagText.findTerminator(bytes, at, bytes.size, encoding)
            val value = decode(at, end, encoding)
            position = if (end < bytes.size) end + TagText.terminatorSize(encoding) else end
            return value
        }

        fun finish() {
            if (position != bytes.size) refuse("uninterpreted bytes after text")
        }

        fun values(at: Int, major: Int): List<String> {
            position = at
            val values = ArrayList<String>()
            do {
                source.options.checkAllocation((values.size.toLong() + 1L) * 64L, "converting chapter text values")
                values.add(text(position))
            } while (major == 4 && position < bytes.size)
            finish()
            return values
        }

        private fun decode(at: Int, end: Int, encoding: Int): String {
            if (at < 0 || at > end || end > bytes.size) refuse("truncated text")
            val length = end - at
            if (encoding == TagText.UTF8) return TagText.decodeUtf8OrNull(bytes, at, length, source.options) ?: refuse("malformed UTF-8")
            if (encoding == TagText.UTF16_BOM || encoding == TagText.UTF16_BE) {
                if (length % 2 != 0) refuse("truncated UTF-16 code unit")
                if (encoding == TagText.UTF16_BOM && length > 0 &&
                    !(length >= 2 && ((bytes[at].toInt() == -1 && bytes[at + 1].toInt() == -2) ||
                        (bytes[at].toInt() == -2 && bytes[at + 1].toInt() == -1)))) refuse("missing UTF-16 byte order mark")
            }
            val value = TagText.decode(bytes, at, length, encoding, source.options)
            // UTF-16 decoding preserves code units; reject unpaired surrogates here.
            var index = 0
            while (index < value.length) {
                val char = value[index++]
                if (char in '\uD800'..'\uDBFF') {
                    if (index == value.length || value[index++] !in '\uDC00'..'\uDFFF') refuse("unpaired UTF-16 surrogate")
                } else if (char in '\uDC00'..'\uDFFF') refuse("unpaired UTF-16 surrogate")
            }
            return value
        }
    }
}

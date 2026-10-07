package io.github.yuroyami.audiokodec.internal.tag

import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.UnsupportedFeatureException

/** Native text compatibility and authoritative RIFF CSET code pages. */
internal object NativeTextEncoding {
    // Microsoft's published Windows-1252 mapping. Undefined positions stay
    // undefined rather than becoming control characters or replacements.
    private val windows1252 = intArrayOf(
        0x20AC, -1, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
        0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, -1, 0x017D, -1,
        -1, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
        0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, -1, 0x017E, 0x0178,
    )

    fun requireSupported(codePage: Int?) {
        if (codePage != null && codePage != 0 && codePage != 28591 && codePage != 1252 && codePage != 65001) {
            throw UnsupportedFeatureException("RIFF CSET code page $codePage")
        }
    }

    /** A missing declaration permits strict UTF-8, then legacy Latin-1. */
    fun decode(bytes: ByteArray, codePage: Int?, options: DecodeOptions): String {
        requireSupported(codePage)
        val end = TagText.findTerminator(bytes, 0, bytes.size, TagText.LATIN1)
        return when (codePage) {
            null -> TagText.decodeUtf8OrNull(bytes, 0, end, options)
                ?: TagText.decodeLatin1(bytes, 0, end, options)
            65001 -> TagText.decodeUtf8OrNull(bytes, 0, end, options)
                ?: throw CorruptStreamException("invalid UTF-8 in RIFF INFO declared as CSET 65001")
            1252 -> {
                options.checkAllocation(end.toLong() * 2L, "decoding Windows-1252 native text")
                CharArray(end) { index ->
                    val byte = bytes[index].toInt() and 255
                    val code = if (byte in 0x80..0x9F) windows1252[byte - 0x80] else byte
                    if (code < 0) throw CorruptStreamException("undefined Windows-1252 byte in RIFF INFO")
                    code.toChar()
                }.concatToString()
            }
            else -> TagText.decodeLatin1(bytes, 0, end, options)
        }
    }

    fun fits(value: String, codePage: Int?): Boolean {
        requireSupported(codePage)
        if (!validUnicode(value)) throw UnsupportedFeatureException("invalid Unicode in native tag text")
        return when (codePage) {
            0, 28591 -> TagText.isLatin1(value)
            1252 -> value.all { windows1252Byte(it.code) >= 0 }
            else -> validUnicode(value)
        }
    }

    fun encode(value: String, codePage: Int?): ByteArray {
        if (!fits(value, codePage)) {
            throw UnsupportedFeatureException("text outside RIFF CSET code page ${codePage ?: 65001}")
        }
        return when (codePage) {
            0, 28591 -> TagText.encodeLatin1(value)
            1252 -> ByteArray(value.length) { windows1252Byte(value[it].code).toByte() }
            else -> value.encodeToByteArray()
        }
    }

    private fun windows1252Byte(code: Int): Int = when {
        code in 0..0x7F || code in 0xA0..0xFF -> code
        else -> windows1252.indexOf(code).takeIf { it >= 0 }?.plus(0x80) ?: -1
    }

    private fun validUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val code = value[index++].code
            if (code in 0xD800..0xDBFF) {
                if (index == value.length || value[index++].code !in 0xDC00..0xDFFF) return false
            } else if (code in 0xDC00..0xDFFF) return false
        }
        return true
    }
}

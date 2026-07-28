package io.github.yuroyami.kiteaudio.internal.tag

import io.github.yuroyami.kiteaudio.DecodeOptions

/**
 * The text encodings tag formats use, and the conversions between them.
 *
 * Kotlin's standard library converts UTF-8 and nothing else, so the other
 * three are done here. They are not exotic: ID3v2 uses all four, and getting
 * the byte order mark wrong on the UTF-16 one is the classic way to write a
 * file that other players show as a row of unrelated characters.
 */
internal object TagText {

    const val LATIN1: Int = 0
    const val UTF16_BOM: Int = 1
    const val UTF16_BE: Int = 2
    const val UTF8: Int = 3

    /**
     * Decodes [bytes] in the ID3v2 encoding [encoding].
     *
     * Decoding stops at a terminator when there is one, because a writer that
     * includes one and a writer that does not are both common, and neither
     * meant the text to end with a null.
     */
    fun decode(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        encoding: Int,
        options: DecodeOptions? = null,
    ): String {
        if (length <= 0) return ""
        return when (encoding) {
            LATIN1 -> decodeLatin1(bytes, offset, length, options)
            UTF16_BOM -> decodeUtf16(
                bytes,
                offset,
                length,
                defaultBigEndian = true,
                options = options,
            )
            UTF16_BE -> decodeUtf16(
                bytes,
                offset,
                length,
                defaultBigEndian = true,
                allowBom = false,
                options = options,
            )
            UTF8 -> {
                val end = findTerminator(bytes, offset, offset + length, UTF8)
                decodeUtf8OrNull(bytes, offset, end - offset, options) ?: ""
            }
            else -> decodeLatin1(bytes, offset, length, options)
        }
    }

    /**
     * Strictly decodes one UTF-8 string.
     *
     * [ByteArray.decodeToString] replaces malformed input, which is convenient
     * for display text but wrong for a length-delimited tag field: accepting an
     * overlong sequence or a surrogate changes the value that was actually on
     * disk. Tag parsers use this helper when malformed UTF-8 invalidates a
     * field or block.
     */
    fun decodeUtf8OrNull(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        options: DecodeOptions? = null,
    ): String? {
        if (offset < 0 || length < 0 || offset > bytes.size - length) return null
        val end = offset + length
        if (!isValidUtf8(bytes, offset, end)) return null
        checkTextAllocation(options, length, "decoding UTF-8 tag text")
        return bytes.decodeToString(offset, end)
    }

    /**
     * Decodes ISO-8859-1, where every byte is one character.
     *
     * Decoding stops at a null byte and at nothing else. Text that is padded
     * rather than terminated, which is what ID3v1 does, is trimmed by its own
     * reader: a space is a legitimate character here, and ending a string at
     * the first one would cut every title down to its first word.
     */
    fun decodeLatin1(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        options: DecodeOptions? = null,
    ): String {
        var count = 0
        while (count < length && bytes[offset + count].toInt() != 0) count++
        checkTextAllocation(options, count, "decoding Latin-1 tag text")
        val chars = CharArray(count)
        for (i in 0 until count) {
            chars[i] = (bytes[offset + i].toInt() and 0xFF).toChar()
        }
        return chars.concatToString()
    }

    /**
     * Decodes UTF-16, honouring a byte order mark when there is one.
     *
     * Text without a mark is read as big-endian, which is what the ID3v2
     * specification says the markless encoding is. Text with one uses what the
     * mark says, whatever the encoding byte claimed.
     */
    fun decodeUtf16(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        defaultBigEndian: Boolean,
        allowBom: Boolean = true,
        options: DecodeOptions? = null,
    ): String {
        var start = offset
        var remaining = length
        var bigEndian = defaultBigEndian
        if (allowBom && remaining >= 2) {
            val first = bytes[start].toInt() and 0xFF
            val second = bytes[start + 1].toInt() and 0xFF
            if (first == 0xFF && second == 0xFE) {
                bigEndian = false
                start += 2
                remaining -= 2
            } else if (first == 0xFE && second == 0xFF) {
                bigEndian = true
                start += 2
                remaining -= 2
            }
        }
        val availableUnits = remaining / 2
        var units = 0
        while (units < availableUnits) {
            val a = bytes[start + units * 2].toInt() and 0xFF
            val b = bytes[start + units * 2 + 1].toInt() and 0xFF
            if ((a or b) == 0) break
            units++
        }
        checkTextAllocation(options, units, "decoding UTF-16 tag text")
        val chars = CharArray(units)
        for (i in 0 until units) {
            val a = bytes[start + i * 2].toInt() and 0xFF
            val b = bytes[start + i * 2 + 1].toInt() and 0xFF
            val unit = if (bigEndian) (a shl 8) or b else (b shl 8) or a
            chars[i] = unit.toChar()
        }
        return chars.concatToString()
    }

    /** Encodes [text] as ISO-8859-1, replacing anything outside it with '?'. */
    fun encodeLatin1(text: String): ByteArray {
        val out = ByteArray(text.length)
        for (i in text.indices) {
            val code = text[i].code
            out[i] = if (code in 0..255) code.toByte() else '?'.code.toByte()
        }
        return out
    }

    /** Encodes [text] as UTF-16 little-endian with a byte order mark. */
    fun encodeUtf16WithBom(text: String): ByteArray {
        val out = ByteArray(2 + text.length * 2)
        out[0] = 0xFF.toByte()
        out[1] = 0xFE.toByte()
        for (i in text.indices) {
            val code = text[i].code
            out[2 + i * 2] = code.toByte()
            out[3 + i * 2] = (code ushr 8).toByte()
        }
        return out
    }

    /** True when every character of [text] fits in ISO-8859-1. */
    fun isLatin1(text: String): Boolean {
        for (c in text) {
            if (c.code > 255) return false
        }
        return true
    }

    /**
     * Finds the terminator that ends a string in [encoding], starting at
     * [from].
     *
     * Returns the offset of the terminator, or [end] when there is none. The
     * two-byte encodings terminate on an aligned pair of zero bytes, and
     * looking for a single zero would cut a character in half.
     */
    fun findTerminator(bytes: ByteArray, from: Int, end: Int, encoding: Int): Int {
        if (encoding == UTF16_BOM || encoding == UTF16_BE) {
            var i = from
            while (i + 1 < end) {
                if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0) return i
                i += 2
            }
            return end
        }
        var i = from
        while (i < end) {
            if (bytes[i].toInt() == 0) return i
            i++
        }
        return end
    }

    /** Bytes one terminator occupies in [encoding]. */
    fun terminatorSize(encoding: Int): Int =
        if (encoding == UTF16_BOM || encoding == UTF16_BE) 2 else 1

    private fun checkTextAllocation(
        options: DecodeOptions?,
        codeUnits: Int,
        what: String,
    ) {
        options?.checkAllocation(codeUnits.toLong() * 2L, what)
    }

    private fun isValidUtf8(bytes: ByteArray, from: Int, to: Int): Boolean {
        var position = from
        while (position < to) {
            val first = bytes[position].toInt() and 0xFF
            when {
                first <= 0x7F -> position++

                first in 0xC2..0xDF -> {
                    if (position + 1 >= to || !isContinuation(bytes[position + 1])) return false
                    position += 2
                }

                first == 0xE0 -> {
                    if (position + 2 >= to) return false
                    val second = bytes[position + 1].toInt() and 0xFF
                    if (second !in 0xA0..0xBF || !isContinuation(bytes[position + 2])) return false
                    position += 3
                }

                first in 0xE1..0xEC || first in 0xEE..0xEF -> {
                    if (position + 2 >= to ||
                        !isContinuation(bytes[position + 1]) ||
                        !isContinuation(bytes[position + 2])
                    ) {
                        return false
                    }
                    position += 3
                }

                first == 0xED -> {
                    if (position + 2 >= to) return false
                    val second = bytes[position + 1].toInt() and 0xFF
                    if (second !in 0x80..0x9F || !isContinuation(bytes[position + 2])) return false
                    position += 3
                }

                first == 0xF0 -> {
                    if (position + 3 >= to) return false
                    val second = bytes[position + 1].toInt() and 0xFF
                    if (second !in 0x90..0xBF ||
                        !isContinuation(bytes[position + 2]) ||
                        !isContinuation(bytes[position + 3])
                    ) {
                        return false
                    }
                    position += 4
                }

                first in 0xF1..0xF3 -> {
                    if (position + 3 >= to ||
                        !isContinuation(bytes[position + 1]) ||
                        !isContinuation(bytes[position + 2]) ||
                        !isContinuation(bytes[position + 3])
                    ) {
                        return false
                    }
                    position += 4
                }

                first == 0xF4 -> {
                    if (position + 3 >= to) return false
                    val second = bytes[position + 1].toInt() and 0xFF
                    if (second !in 0x80..0x8F ||
                        !isContinuation(bytes[position + 2]) ||
                        !isContinuation(bytes[position + 3])
                    ) {
                        return false
                    }
                    position += 4
                }

                else -> return false
            }
        }
        return true
    }

    private fun isContinuation(byte: Byte): Boolean =
        (byte.toInt() and 0xC0) == 0x80
}

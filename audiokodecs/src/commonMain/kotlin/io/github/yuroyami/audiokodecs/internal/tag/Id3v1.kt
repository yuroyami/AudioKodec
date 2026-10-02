package io.github.yuroyami.audiokodecs.internal.tag

import io.github.yuroyami.audiokodecs.tag.RawTagBlock
import io.github.yuroyami.audiokodecs.tag.RawTagEntry
import io.github.yuroyami.audiokodecs.tag.TagFormat

/**
 * ID3v1 and ID3v1.1: 128 bytes at the end of a file.
 *
 * Every field is a fixed length and padded with spaces or nulls, so a title
 * of more than thirty characters simply cannot be stored. Version 1.1 took
 * the last two bytes of the comment to hold a track number, which is the only
 * difference between them and is detected rather than declared.
 *
 * AudioKodecs reads these and updates one that already exists. It never creates
 * one, because writing a truncated copy of a tag that is already in the file
 * in full helps nobody.
 */
internal object Id3v1 {

    const val SIZE: Int = 128

    /** True when a 128-byte block starting at [at] is an ID3v1 tag. */
    fun isTagAt(bytes: ByteArray, at: Int): Boolean {
        if (at < 0 || at + SIZE > bytes.size) return false
        return bytes[at].toInt() == 'T'.code &&
            bytes[at + 1].toInt() == 'A'.code &&
            bytes[at + 2].toInt() == 'G'.code
    }

    /** Reads the block at [at], or returns null when there is not one there. */
    fun read(bytes: ByteArray, at: Int): RawTagBlock? {
        if (!isTagAt(bytes, at)) return null
        val entries = ArrayList<RawTagEntry>()

        fun field(key: String, offset: Int, length: Int) {
            val value = trimmed(bytes, at + offset, length)
            if (value.isNotEmpty()) entries.add(RawTagEntry(key, text = value))
        }

        field("TITLE", 3, 30)
        field("ARTIST", 33, 30)
        field("ALBUM", 63, 30)
        field("YEAR", 93, 4)

        // Version 1.1 ends the comment two bytes early: a zero and then the
        // track number. A comment whose 29th byte is not zero is a version 1
        // comment that fills all thirty.
        val hasTrack = bytes[at + 125].toInt() == 0 && bytes[at + 126].toInt() != 0
        if (hasTrack) {
            field("COMMENT", 97, 28)
            entries.add(RawTagEntry("TRACK", text = (bytes[at + 126].toInt() and 0xFF).toString()))
        } else {
            field("COMMENT", 97, 30)
        }

        val genreCode = bytes[at + 127].toInt() and 0xFF
        Id3Genres.nameOf(genreCode)?.let {
            entries.add(RawTagEntry("GENRE", text = it))
        }

        return RawTagBlock(TagFormat.ID3V1, at.toLong(), SIZE.toLong(), entries)
    }

    /**
     * Writes a 128-byte block.
     *
     * Values are cut to the length each field has, which loses information.
     * That is the format, not the writer: this exists so a file that already
     * had an ID3v1 block keeps a block that agrees with the real tag.
     */
    fun write(
        title: String?,
        artist: String?,
        album: String?,
        year: String?,
        comment: String?,
        track: Int?,
        genre: String?,
    ): ByteArray {
        val out = ByteArray(SIZE)
        out[0] = 'T'.code.toByte()
        out[1] = 'A'.code.toByte()
        out[2] = 'G'.code.toByte()
        put(out, 3, 30, title)
        put(out, 33, 30, artist)
        put(out, 63, 30, album)
        put(out, 93, 4, year)
        if (track != null && track in 1..255) {
            put(out, 97, 28, comment)
            out[125] = 0
            out[126] = track.toByte()
        } else {
            put(out, 97, 30, comment)
        }
        // 255 is the code for "no genre", which is what an unknown name gets.
        val code = genre?.let { Id3Genres.codeOf(Id3Genres.resolve(it)) } ?: -1
        out[127] = (if (code >= 0) code else 255).toByte()
        return out
    }

    private fun put(out: ByteArray, at: Int, length: Int, value: String?) {
        if (value.isNullOrEmpty()) return
        val encoded = TagText.encodeLatin1(value)
        val n = if (encoded.size < length) encoded.size else length
        encoded.copyInto(out, at, 0, n)
    }

    /**
     * Reads a fixed-length field, dropping the padding.
     *
     * A field may be padded with spaces, with nulls, or with a null followed
     * by whatever was in the buffer, which is why the trailing junk after the
     * first null goes as well.
     */
    private fun trimmed(bytes: ByteArray, at: Int, length: Int): String {
        var end = at + length
        for (i in at until at + length) {
            if (bytes[i].toInt() == 0) {
                end = i
                break
            }
        }
        var trimmedEnd = end
        while (trimmedEnd > at && (bytes[trimmedEnd - 1].toInt() and 0xFF) == ' '.code) {
            trimmedEnd--
        }
        return TagText.decodeLatin1(bytes, at, trimmedEnd - at)
    }
}

/**
 * Lyrics3v2, a block that sits between the audio and an ID3v1 tag.
 *
 * Almost nothing writes it now, and it matters anyway: a reader that does not
 * know it is there gets the offset of everything else at the end of the file
 * wrong. It is read for its content and, more importantly, for its length.
 */
internal object Lyrics3 {

    /**
     * Finds the block that ends at [end], or returns null.
     *
     * The format is written to be found from the back: the last nine bytes
     * are `LYRICS200`, and the six before them are the size as decimal digits.
     */
    fun findEndingAt(bytes: ByteArray, end: Int): RawTagBlock? {
        if (end < 15) return null
        if (!matches(bytes, end - 9, "LYRICS200")) return null
        var size = 0
        for (i in end - 15 until end - 9) {
            val digit = bytes[i].toInt() - '0'.code
            if (digit !in 0..9) return null
            size = size * 10 + digit
        }
        // The stated size covers the body and the eleven-byte opening keyword,
        // and excludes the fifteen-byte trailer.
        val total = size + 15
        val start = end - total
        if (start < 0) return null
        if (!matches(bytes, start, "LYRICSBEGIN")) return null

        val entries = ArrayList<RawTagEntry>()
        var position = start + 11
        val bodyEnd = end - 15
        while (position + 8 <= bodyEnd) {
            // A field identifier is three capital letters. Anything else means
            // the fields that follow cannot be trusted either.
            for (i in position until position + 3) {
                if (bytes[i].toInt() !in 'A'.code..'Z'.code) return partial(start, total, entries)
            }
            val key = TagText.decodeLatin1(bytes, position, 3)
            var fieldSize = 0
            for (i in position + 3 until position + 8) {
                val digit = bytes[i].toInt() - '0'.code
                if (digit !in 0..9) return partial(start, total, entries)
                fieldSize = fieldSize * 10 + digit
            }
            val valueStart = position + 8
            if (valueStart + fieldSize > bodyEnd) break
            entries.add(
                RawTagEntry(key, text = TagText.decodeLatin1(bytes, valueStart, fieldSize)),
            )
            position = valueStart + fieldSize
        }
        return partial(start, total, entries)
    }

    private fun partial(start: Int, total: Int, entries: List<RawTagEntry>) =
        RawTagBlock(TagFormat.LYRICS3, start.toLong(), total.toLong(), entries)

    private fun matches(bytes: ByteArray, at: Int, text: String): Boolean {
        if (at < 0 || at + text.length > bytes.size) return false
        for (i in text.indices) {
            if ((bytes[at + i].toInt() and 0xFF) != text[i].code) return false
        }
        return true
    }
}

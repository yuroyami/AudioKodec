package io.github.yuroyami.audiokodec.internal.tag

import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.tag.RawTagBlock
import io.github.yuroyami.audiokodec.tag.RawTagEntry
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import kotlin.math.roundToInt

/**
 * Vorbis comments, the tag format Ogg and FLAC share.
 *
 * The layout is refreshingly plain: a vendor string, a count, and then that
 * many `KEY=value` strings, all UTF-8 and all length-prefixed. The lengths are
 * little-endian, which is the one surprise in a format whose neighbours are
 * big-endian.
 *
 * Keys are case insensitive by specification and are written in capitals by
 * convention. A key may appear more than once, which is how two artists are
 * stored.
 */
internal object VorbisComment {

    /**
     * Parses a comment block.
     *
     * [offset] and [size] describe where the block sits in the file, and only
     * affect what the returned block reports about itself.
     */
    fun parse(
        bytes: ByteArray,
        from: Int,
        to: Int,
        offset: Long,
        size: Long,
        options: DecodeOptions,
    ): RawTagBlock? {
        if (from < 0 || to < from || to > bytes.size) return null
        if (to - from < 8) return null
        var position = from

        fun readLength(): Int {
            val value = (bytes[position].toInt() and 0xFF) or
                ((bytes[position + 1].toInt() and 0xFF) shl 8) or
                ((bytes[position + 2].toInt() and 0xFF) shl 16) or
                ((bytes[position + 3].toInt() and 0xFF) shl 24)
            position += 4
            return value
        }

        val vendorLength = readLength()
        if (vendorLength < 0 || vendorLength > to - position) return null
        val vendor = TagText.decodeUtf8OrNull(bytes, position, vendorLength, options) ?: return null
        position += vendorLength

        if (position > to - 4) return null
        val count = readLength()
        if (count < 0 || count > (to - position) / 4) return null

        val entries = ArrayList<RawTagEntry>()
        var retainedEntries = 0L
        fun rememberEntry() {
            retainedEntries++
            options.checkAllocation(
                retainedEntries * 64L,
                "remembering Vorbis comment entries",
            )
        }
        if (vendor.isNotEmpty()) {
            // The vendor string is where the encoder names itself, which is
            // the same fact other formats keep in an encoder field.
            rememberEntry()
            entries.add(RawTagEntry("VENDOR", text = vendor))
        }
        var read = 0
        while (read < count) {
            if (position > to - 4) return null
            val length = readLength()
            if (length < 0 || length > to - position) return null
            val text = TagText.decodeUtf8OrNull(bytes, position, length, options) ?: return null
            position += length
            read++
            val separator = text.indexOf('=')
            if (separator <= 0) continue
            if (!validFieldName(text, separator)) continue
            val key = text.substring(0, separator).uppercase()
            val value = text.substring(separator + 1)
            if (value.isNotEmpty()) {
                rememberEntry()
                entries.add(RawTagEntry(key, text = value))
            }
        }
        return RawTagBlock(TagFormat.VORBIS_COMMENT, offset, size, entries)
    }

    private fun validFieldName(text: String, end: Int): Boolean {
        for (index in 0 until end) {
            val code = text[index].code
            if (code !in 0x20..0x7D || code == '='.code) return false
        }
        return true
    }

    /**
     * Returns the first byte after the declared vendor and comment list.
     *
     * Some Ogg mappings permit opaque padding after the list. Tag rewriting
     * preserves that suffix, so it needs the structural boundary without
     * interpreting the fields a second time.
     */
    fun contentEnd(bytes: ByteArray, from: Int, to: Int): Int? {
        if (from < 0 || to < from || to > bytes.size || to - from < 8) return null
        var position = from

        fun readLength(): Int {
            val value = (bytes[position].toInt() and 0xFF) or
                ((bytes[position + 1].toInt() and 0xFF) shl 8) or
                ((bytes[position + 2].toInt() and 0xFF) shl 16) or
                ((bytes[position + 3].toInt() and 0xFF) shl 24)
            position += 4
            return value
        }

        val vendorLength = readLength()
        if (vendorLength < 0 || vendorLength > to - position) return null
        position += vendorLength
        if (position > to - 4) return null
        val count = readLength()
        if (count < 0 || count > (to - position) / 4) return null
        repeat(count) {
            if (position > to - 4) return null
            val length = readLength()
            if (length < 0 || length > to - position) return null
            position += length
        }
        return position
    }

    /**
     * Builds a comment block body, without any container framing.
     *
     * Pictures are written as `METADATA_BLOCK_PICTURE`, which is the FLAC
     * picture structure in base64. That is how Ogg carries cover art, and a
     * FLAC file that writes its pictures as proper blocks passes an empty
     * [includePictures] instead.
     */
    fun build(
        tags: TagBundle,
        vendor: String = DEFAULT_VENDOR,
        includePictures: Boolean = true,
    ): ByteArray {
        val items = ArrayList<String>()

        for ((field, values) in tags.fields) {
            val key = TagMapping.vorbisKeyFor(field) ?: continue
            for (value in values) {
                if (value.isEmpty()) continue
                items.add("$key=$value")
            }
        }

        tags.replayGain?.let { gain ->
            if (gain.isR128) {
                gain.trackGainDb?.let {
                    items.add("R128_TRACK_GAIN=${(it * 256).roundToInt()}")
                }
                gain.albumGainDb?.let {
                    items.add("R128_ALBUM_GAIN=${(it * 256).roundToInt()}")
                }
                // The Opus convention has no peak fields. A peak is a sample
                // value rather than a level against a reference, so the
                // ReplayGain field states it correctly alongside R128 gains.
                gain.trackPeak?.let { items.add("REPLAYGAIN_TRACK_PEAK=$it") }
                gain.albumPeak?.let { items.add("REPLAYGAIN_ALBUM_PEAK=$it") }
            } else {
                gain.trackGainDb?.let {
                    items.add("REPLAYGAIN_TRACK_GAIN=${Id3v2Writer.formatDecibels(it)}")
                }
                gain.trackPeak?.let { items.add("REPLAYGAIN_TRACK_PEAK=$it") }
                gain.albumGainDb?.let {
                    items.add("REPLAYGAIN_ALBUM_GAIN=${Id3v2Writer.formatDecibels(it)}")
                }
                gain.albumPeak?.let { items.add("REPLAYGAIN_ALBUM_PEAK=$it") }
            }
        }

        if (includePictures) {
            for (picture in tags.pictures) {
                items.add("METADATA_BLOCK_PICTURE=" + Base64.encode(FlacPicture.build(picture)))
            }
        }

        // Keep comments outside AudioKodec's field model. They are often where
        // encoder-specific identifiers and application state live.
        for (block in tags.raw.blocksOf(io.github.yuroyami.audiokodec.tag.TagFormat.VORBIS_COMMENT)) {
            for (entry in block.entries) {
                val key = entry.key.uppercase()
                if (TagMapping.fieldFor(block.format, entry) != null) continue
                if (!validFieldName(entry.key, entry.key.length)) continue
                if (key == "METADATA_BLOCK_PICTURE" || key == "COVERART" || key == "COVERARTMIME") continue
                if (key.startsWith("REPLAYGAIN_") || key.startsWith("R128_")) continue
                val value = entry.text ?: continue
                val item = "${entry.key}=$value"
                if (item !in items) items.add(item)
            }
        }

        val vendorBytes = vendor.encodeToByteArray()
        val itemBytes = items.map { it.encodeToByteArray() }
        var size = 8L + vendorBytes.size
        for (item in itemBytes) {
            size += 4L + item.size
            if (size > Int.MAX_VALUE) {
                throw AudioKodecException("a Vorbis comment is too large for a Kotlin array")
            }
        }

        val out = ByteArray(size.toInt())
        var position = 0
        fun writeLength(value: Int) {
            out[position] = value.toByte()
            out[position + 1] = (value ushr 8).toByte()
            out[position + 2] = (value ushr 16).toByte()
            out[position + 3] = (value ushr 24).toByte()
            position += 4
        }
        writeLength(vendorBytes.size)
        vendorBytes.copyInto(out, position)
        position += vendorBytes.size
        writeLength(itemBytes.size)
        for (item in itemBytes) {
            writeLength(item.size)
            item.copyInto(out, position)
            position += item.size
        }
        return out
    }

    /**
     * Which fields go into a comment block and which need somewhere else.
     *
     * Everything AudioKodec models has a Vorbis key except a plain URL, which
     * has no agreed spelling. This is here so a writer can tell whether it is
     * about to drop something.
     */
    fun canCarry(field: TagField): Boolean = TagMapping.vorbisKeyFor(field) != null

    const val DEFAULT_VENDOR: String = "AudioKodec"
}

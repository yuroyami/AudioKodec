package io.github.yuroyami.audiokodec.internal.tag

import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
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
 * Keys are case insensitive by specification. Modelled keys are written in
 * capitals by convention; raw entries keep their stored spelling. A key may
 * appear more than once, which is how two artists are stored.
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
        val vendor = TagText.decodeUtf8OrNull(bytes, position, vendorLength, options)
        val vendorBytes = if (vendor == null) {
            options.checkAllocation(vendorLength.toLong(), "retaining an unreadable Vorbis vendor")
            bytes.copyOfRange(position, position + vendorLength)
        } else null
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
        // The header is separate from user comments, including comments
        // whose key is also VENDOR. Retain even an empty header string.
        rememberEntry()
        entries.add(RawTagEntry("VENDOR", text = vendor, binary = vendorBytes).markVorbisVendorHeader())
        var read = 0
        while (read < count) {
            if (position > to - 4) return null
            val length = readLength()
            if (length < 0 || length > to - position) return null
            val start = position
            val text = TagText.decodeUtf8OrNull(bytes, start, length, options)
            position += length
            read++
            if (text == null) {
                rememberEntry()
                options.checkAllocation(length.toLong(), "retaining an unreadable Vorbis comment")
                entries.add(RawTagEntry(opaqueKey(bytes, start, length, options),
                    binary = bytes.copyOfRange(start, position)).markOpaqueVorbisComment())
                continue
            }
            val separator = text.indexOf('=')
            if (separator <= 0) continue
            if (!validFieldName(text, separator)) continue
            val key = text.substring(0, separator)
            val value = text.substring(separator + 1)
            rememberEntry()
            entries.add(RawTagEntry(key, text = value))
        }
        return RawTagBlock(TagFormat.VORBIS_COMMENT, offset, size, entries)
    }

    /** Recover only a valid ASCII field name, without guessing the charset. */
    private fun opaqueKey(bytes: ByteArray, from: Int, length: Int, options: DecodeOptions): String {
        for (index in 0 until length) {
            val code = bytes[from + index].toInt() and 0xFF
            if (code == '='.code) {
                return if (index > 0) TagText.decodeUtf8OrNull(bytes, from, index, options) ?: "<invalid comment>" else "<invalid comment>"
            }
            if (code !in 0x20..0x7D) break
        }
        return "<invalid comment>"
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

    private fun formatR128Gain(name: String, gainDb: Double): String {
        if (!gainDb.isFinite() || gainDb < R128_MIN_Q78 / 256.0 || gainDb > R128_MAX_Q78 / 256.0) {
            throw UnsupportedFeatureException(
                "representing $name in signed 16-bit Q7.8",
                "$name requires -128.0 to 127.99609375 dB, inclusive; requested $gainDb dB",
            )
        }
        return (gainDb * 256.0).roundToInt().toString()
    }

    /**
     * Builds a comment block body, without any container framing.
     *
     * Pictures are written as `METADATA_BLOCK_PICTURE`, which is the FLAC
     * picture structure in base64. That is how Ogg carries cover art, and a
     * FLAC file that writes its pictures as proper blocks passes false for
     * [includePictures] instead.
     */
    fun build(
        tags: TagBundle,
        vendor: String = DEFAULT_VENDOR,
        includePictures: Boolean = true,
        chaptersPreservedElsewhere: Boolean = false,
        preservedVendor: ByteArray? = null,
    ): ByteArray {
        requireRepresentable(tags, chaptersPreservedElsewhere)
        val items = ArrayList<ByteArray>()
        fun addItem(value: String) { items.add(value.encodeToByteArray()) }

        for ((field, values) in tags.fields) {
            val key = TagMapping.vorbisKeyFor(field) ?: throw UnsupportedFeatureException(
                "${field.name} in Vorbis comments",
            )
            for (value in values) {
                if (value.isEmpty()) continue
                addItem("$key=$value")
            }
        }

        tags.replayGain?.let { gain ->
            if (gain.isR128) {
                gain.trackGainDb?.let {
                    addItem("R128_TRACK_GAIN=${formatR128Gain("R128_TRACK_GAIN", it)}")
                }
                gain.albumGainDb?.let {
                    addItem("R128_ALBUM_GAIN=${formatR128Gain("R128_ALBUM_GAIN", it)}")
                }
                // The Opus convention has no peak fields. A peak is a sample
                // value rather than a level against a reference, so the
                // ReplayGain field states it correctly alongside R128 gains.
                gain.trackPeak?.let { addItem("REPLAYGAIN_TRACK_PEAK=$it") }
                gain.albumPeak?.let { addItem("REPLAYGAIN_ALBUM_PEAK=$it") }
            } else {
                gain.trackGainDb?.let {
                    addItem("REPLAYGAIN_TRACK_GAIN=${Id3v2Writer.formatDecibels(it)}")
                }
                gain.trackPeak?.let { addItem("REPLAYGAIN_TRACK_PEAK=$it") }
                gain.albumGainDb?.let {
                    addItem("REPLAYGAIN_ALBUM_GAIN=${Id3v2Writer.formatDecibels(it)}")
                }
                gain.albumPeak?.let { addItem("REPLAYGAIN_ALBUM_PEAK=$it") }
            }
        }

        if (includePictures) {
            for (picture in tags.pictures) {
                addItem("METADATA_BLOCK_PICTURE=" + Base64.encode(FlacPicture.build(picture)))
            }
        }

        // Keep comments outside AudioKodec's field model. They are often where
        // encoder-specific identifiers and application state live.
        for (block in tags.raw.blocksOf(io.github.yuroyami.audiokodec.tag.TagFormat.VORBIS_COMMENT)) {
            for (entry in block.entries) {
                if (entry.isVorbisVendorHeader) continue
                if (entry.isOpaqueVorbisComment) {
                    entry.binary?.let { items.add(it) }
                    continue
                }
                val key = entry.key.uppercase()
                if (TagMapping.fieldFor(block.format, entry) != null) continue
                if (!validFieldName(entry.key, entry.key.length)) continue
                if (key == "METADATA_BLOCK_PICTURE" || key == "COVERART" || key == "COVERARTMIME") continue
                if (isModelledLoudnessName(entry.key)) continue
                val value = entry.text ?: continue
                // Repeated comments are stored data, so no membership
                // check belongs here. Appending retains order in linear work.
                addItem("${entry.key}=$value")
            }
        }

        val vendorBytes = preservedVendor ?: tags.raw.blocksOf(TagFormat.VORBIS_COMMENT)
            .asSequence().flatMap { it.entries.asSequence() }
            .firstOrNull { it.isVorbisVendorHeader }?.binary ?: vendor.encodeToByteArray()
        val itemBytes = items
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

    /** Checks native limits before a surrounding ID3 block can reach the sink. */
    fun requireRepresentable(tags: TagBundle, chaptersPreservedElsewhere: Boolean) {
        if (!chaptersPreservedElsewhere && tags.chapters.isNotEmpty()) {
            throw UnsupportedFeatureException("chapters in Vorbis comments without ID3")
        }
        val missing = tags.fields.filter { (field, values) ->
            values.any { it.isNotEmpty() } && !canCarry(field)
        }.keys
        if (missing.isNotEmpty()) {
            throw UnsupportedFeatureException("${missing.joinToString()} in Vorbis comments")
        }
        tags.replayGain?.takeIf { it.isR128 }?.let { gain ->
            gain.trackGainDb?.let { formatR128Gain("R128_TRACK_GAIN", it) }
            gain.albumGainDb?.let { formatR128Gain("R128_ALBUM_GAIN", it) }
        }
    }

    /**
     * Which fields go into a comment block and which need somewhere else.
     *
     * Modelled text fields have conventional keys, including WEBSITE for URL.
     * Chapters require another carrier.
     */
    fun canCarry(field: TagField): Boolean = TagMapping.vorbisKeyFor(field) != null

    const val DEFAULT_VENDOR: String = "AudioKodec"
}

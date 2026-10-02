package io.github.yuroyami.audiokodecs.internal.tag

import io.github.yuroyami.audiokodecs.AudioKodecsException
import io.github.yuroyami.audiokodecs.DecodeOptions
import io.github.yuroyami.audiokodecs.tag.PictureType
import io.github.yuroyami.audiokodecs.tag.RawTagBlock
import io.github.yuroyami.audiokodecs.tag.RawTagEntry
import io.github.yuroyami.audiokodecs.tag.RawTags
import io.github.yuroyami.audiokodecs.tag.ReplayGain
import io.github.yuroyami.audiokodecs.tag.TagBundle
import io.github.yuroyami.audiokodecs.tag.TagChapter
import io.github.yuroyami.audiokodecs.tag.TagField
import io.github.yuroyami.audiokodecs.tag.TagFormat
import io.github.yuroyami.audiokodecs.tag.TagPicture

/**
 * Turns the tag blocks found in a file into one merged view.
 *
 * A file with more than one block is the normal case rather than the odd one:
 * an MP3 written by two programs a decade apart usually has an ID3v2 block at
 * the front and an ID3v1 block at the end that disagrees with it. The rule is
 * the one every player uses, which is that the richer format wins, and it is
 * expressed as [TagFormat.precedence].
 */
internal object TagAssembler {

    fun assemble(
        blocks: List<RawTagBlock>,
        options: DecodeOptions = DecodeOptions.DEFAULT,
        /** Chapters the container keeps outside its tag blocks, as MP4 does. */
        containerChapters: List<TagChapter> = emptyList(),
    ): TagBundle {
        if (blocks.isEmpty() && containerChapters.isEmpty()) return TagBundle.EMPTY

        val fields = LinkedHashMap<TagField, MutableList<String>>()
        val claimedBy = HashMap<TagField, Int>()
        val pictures = ArrayList<TagPicture>()
        val chapters = ArrayList<TagChapter>(containerChapters)
        val gain = ReplayGainReader()

        // Highest precedence first, so the first block to state a field is the
        // one that keeps it. A stable sort keeps file order among equals,
        // which matters when a file somehow has two blocks of one format.
        val ordered = blocks.sortedByDescending { it.format.precedence }

        for (block in ordered) {
            val precedence = block.format.precedence
            for (entry in block.entries) {
                gain.consider(block.format, entry)

                val pair = TagMapping.numberAndTotalFor(block.format, entry.key)
                if (pair != null) {
                    val text = entry.text ?: continue
                    val (numberField, totalField) = pair
                    val slash = text.indexOf('/')
                    val number = if (slash >= 0) text.substring(0, slash) else text
                    val total = if (slash >= 0) text.substring(slash + 1) else null
                    addValue(fields, claimedBy, numberField, number.trim(), precedence)
                    if (!total.isNullOrBlank()) {
                        addValue(fields, claimedBy, totalField, total.trim(), precedence)
                    }
                    continue
                }

                val field = TagMapping.fieldFor(block.format, entry) ?: continue
                var value = entry.text ?: continue
                if (field == TagField.GENRE) value = Id3Genres.resolve(value)
                if (field == TagField.COMPILATION) value = normaliseFlag(value)
                addValue(fields, claimedBy, field, value.trim(), precedence)
            }

            when (block.format) {
                TagFormat.ID3V2_2, TagFormat.ID3V2_3, TagFormat.ID3V2_4 -> {
                    pictures.addAll(Id3v2Reader.picturesOf(block))
                    chapters.addAll(Id3v2Reader.chaptersOf(block, options))
                }
                TagFormat.VORBIS_COMMENT -> pictures.addAll(vorbisPicturesOf(block, options))
                TagFormat.APEV1, TagFormat.APEV2 -> pictures.addAll(apePicturesOf(block, options))
                TagFormat.MP4_ILST -> pictures.addAll(mp4PicturesOf(block))
                else -> Unit
            }
        }

        val replayGain = gain.build()
        return TagBundle(
            fields = fields.mapValues { it.value.toList() },
            pictures = pictures,
            chapters = chapters.sortedBy { it.startMillis },
            replayGain = if (replayGain.isEmpty) null else replayGain,
            raw = RawTags(blocks),
        )
    }

    /**
     * Adds a value unless a higher-precedence block already claimed the field.
     *
     * A second value from the same block is an extra value, because formats
     * that allow two artists mean both. A value from a lower block is a stale
     * copy and is dropped.
     */
    private fun addValue(
        fields: MutableMap<TagField, MutableList<String>>,
        claimedBy: MutableMap<TagField, Int>,
        field: TagField,
        value: String,
        precedence: Int,
    ) {
        if (value.isEmpty()) return
        val owner = claimedBy[field]
        if (owner != null && owner > precedence) return
        if (owner == null) {
            claimedBy[field] = precedence
            fields[field] = mutableListOf(value)
            return
        }
        val existing = fields.getOrPut(field) { mutableListOf() }
        if (existing.none { it == value }) existing.add(value)
    }

    /** Turns the several spellings of a yes or no field into `1` or `0`. */
    private fun normaliseFlag(value: String): String {
        val trimmed = value.trim()
        return when (trimmed.lowercase()) {
            "1", "true", "yes", "on" -> "1"
            "0", "false", "no", "off", "" -> "0"
            else -> trimmed
        }
    }

    /**
     * Reads the pictures a Vorbis comment carries.
     *
     * Vorbis has no picture field, so one was bolted on: a `METADATA_BLOCK_PICTURE`
     * comment holds a base64 copy of a FLAC picture block. FLAC files carry
     * the same structure directly, without the base64 step.
     */
    private fun vorbisPicturesOf(
        block: RawTagBlock,
        options: DecodeOptions,
    ): List<TagPicture> {
        val pictures = ArrayList<TagPicture>()
        for (entry in block.entries) {
            val key = entry.key.uppercase()
            if (key == "METADATA_BLOCK_PICTURE") {
                // A FLAC file carries the structure in a metadata block, so
                // the reader hands it over as bytes. Ogg has no such block and
                // wraps the same structure in base64 inside a comment. Both
                // arrive here, and which one this is depends on which field is
                // filled in.
                val binary = entry.binary
                val decoded = if (binary != null) {
                    binary
                } else {
                    Base64.decodeOrNull(entry.text ?: continue, options) ?: continue
                }
                FlacPicture.parse(decoded, options)?.let { pictures.add(it) }
            } else if (key == "COVERART") {
                // An older convention: base64 of the image itself, with the
                // media type in a separate comment.
                val text = entry.text ?: continue
                val decoded = Base64.decodeOrNull(text, options) ?: continue
                val mime = block.entries.firstOrNull { it.key.equals("COVERARTMIME", true) }?.text
                pictures.add(
                    TagPicture(
                        type = PictureType.FRONT_COVER,
                        mimeType = mime ?: sniffImageType(decoded),
                        bytes = decoded,
                    ),
                )
            }
        }
        return pictures
    }

    /** APE stores a picture as a description, a null, and then the image. */
    private fun apePicturesOf(
        block: RawTagBlock,
        options: DecodeOptions,
    ): List<TagPicture> {
        val pictures = ArrayList<TagPicture>()
        for (entry in block.entries) {
            if (!entry.key.startsWith("Cover Art", ignoreCase = true)) continue
            val data = entry.binary ?: continue
            var split = -1
            for (i in data.indices) {
                if (data[i].toInt() == 0) {
                    split = i
                    break
                }
            }
            val description = if (split > 0) {
                TagText.decodeUtf8OrNull(data, 0, split, options)
                    ?: TagText.decodeLatin1(data, 0, split, options)
            } else {
                ""
            }
            val image = if (split >= 0) {
                val imageSize = data.size - split - 1
                options.checkAllocation(imageSize.toLong(), "an APE cover-art image")
                data.copyOfRange(split + 1, data.size)
            } else {
                data
            }
            if (image.isEmpty()) continue
            pictures.add(
                TagPicture(
                    type = if (entry.key.contains("Back", true)) {
                        PictureType.BACK_COVER
                    } else {
                        PictureType.FRONT_COVER
                    },
                    mimeType = sniffImageType(image),
                    description = description,
                    bytes = image,
                ),
            )
        }
        return pictures
    }

    /** MP4 keeps cover art in a `covr` atom, whose type byte names the format. */
    private fun mp4PicturesOf(block: RawTagBlock): List<TagPicture> {
        val pictures = ArrayList<TagPicture>()
        for (entry in block.entries) {
            if (entry.key != "covr") continue
            val data = entry.binary ?: continue
            if (data.isEmpty()) continue
            pictures.add(
                TagPicture(
                    type = PictureType.FRONT_COVER,
                    mimeType = entry.text ?: sniffImageType(data),
                    bytes = data,
                ),
            )
        }
        return pictures
    }

    /**
     * Names an image from its first bytes.
     *
     * Some tag formats store a picture without saying what it is. Rather than
     * claim JPEG and be wrong, this looks.
     */
    fun sniffImageType(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xD8
        ) {
            return "image/jpeg"
        }
        if (bytes.size >= 8 &&
            (bytes[0].toInt() and 0xFF) == 0x89 &&
            bytes[1].toInt() == 'P'.code
        ) {
            return "image/png"
        }
        if (bytes.size >= 3 && bytes[0].toInt() == 'G'.code && bytes[1].toInt() == 'I'.code) {
            return "image/gif"
        }
        if (bytes.size >= 12 && bytes[0].toInt() == 'R'.code && bytes[8].toInt() == 'W'.code) {
            return "image/webp"
        }
        return "application/octet-stream"
    }
}

/**
 * Collects loudness information across the tag blocks of a file.
 *
 * The two conventions do not mix. ReplayGain 2 writes decibels against
 * -18 LUFS in text fields, and Opus writes a whole number of Q7.8 decibels
 * against -23 LUFS in `R128_TRACK_GAIN`. Reading one as the other is a 5 dB
 * error, so each gain remembers which reference it was found in.
 *
 * Blocks arrive highest precedence first, and the first block to state a
 * value keeps it, exactly as for ordinary fields. Two blocks of equal
 * precedence are taken in file order, so the earlier one wins.
 */
internal class ReplayGainReader {
    private var trackGain: Double? = null
    private var trackGainIsR128 = false
    private var trackPeak: Double? = null
    private var albumGain: Double? = null
    private var albumGainIsR128 = false
    private var albumPeak: Double? = null

    fun consider(format: TagFormat, entry: RawTagEntry) {
        val key = when (format) {
            TagFormat.ID3V2_2, TagFormat.ID3V2_3, TagFormat.ID3V2_4 ->
                entry.description?.uppercase() ?: return
            else -> entry.key.uppercase()
        }
        val text = entry.text ?: return
        when (key) {
            "REPLAYGAIN_TRACK_GAIN" -> if (trackGain == null) {
                trackGain = parseDecibels(text)
            }
            "REPLAYGAIN_TRACK_PEAK" -> if (trackPeak == null) trackPeak = parsePeak(text)
            "REPLAYGAIN_ALBUM_GAIN" -> if (albumGain == null) {
                albumGain = parseDecibels(text)
            }
            "REPLAYGAIN_ALBUM_PEAK" -> if (albumPeak == null) albumPeak = parsePeak(text)
            // The Opus fields hold a whole number of 256ths of a decibel.
            "R128_TRACK_GAIN" -> if (trackGain == null) {
                trackGain = parseQ78(text)
                trackGainIsR128 = trackGain != null
            }
            "R128_ALBUM_GAIN" -> if (albumGain == null) {
                albumGain = parseQ78(text)
                albumGainIsR128 = albumGain != null
            }
        }
    }

    /**
     * The values found, against one reference.
     *
     * When the track and album gains were found in different conventions the
     * Opus one is converted to the ReplayGain reference, rather than one flag
     * being made to describe two values it is only true of one of.
     */
    fun build(): ReplayGain {
        val track = trackGain
        val album = albumGain
        val mixed = track != null && album != null && trackGainIsR128 != albumGainIsR128
        if (mixed) {
            return ReplayGain(
                trackGainDb = if (trackGainIsR128) track!! + R128_TO_REPLAYGAIN_DB else track,
                trackPeak = trackPeak,
                albumGainDb = if (albumGainIsR128) album!! + R128_TO_REPLAYGAIN_DB else album,
                albumPeak = albumPeak,
                isR128 = false,
            )
        }
        return ReplayGain(
            trackGainDb = track,
            trackPeak = trackPeak,
            albumGainDb = album,
            albumPeak = albumPeak,
            isR128 = (track != null && trackGainIsR128) || (album != null && albumGainIsR128),
        )
    }

    /** Reads `-7.35 dB`, `+2.1`, or anything of that shape. */
    private fun parseDecibels(text: String): Double? {
        val cleaned = text.trim().removeSuffix("dB").removeSuffix("DB").removeSuffix("db").trim()
        return cleaned.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun parsePeak(text: String): Double? =
        text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

    private fun parseQ78(text: String): Double? = text.trim().toIntOrNull()?.let { it / 256.0 }
}

/**
 * Decibels to add to a gain measured against the Opus reference of -23 LUFS
 * to state it against ReplayGain's -18 LUFS. A quieter target needs less
 * gain, so the ReplayGain value is the larger by the 5 dB between them.
 */
internal const val R128_TO_REPLAYGAIN_DB: Double = 5.0

/**
 * [this] restated against the ReplayGain reference, for a tag format that has
 * no way to say a gain is relative to -23 LUFS. Peaks are sample values and do
 * not depend on the reference.
 */
internal fun ReplayGain.inReplayGainReference(): ReplayGain = if (!isR128) {
    this
} else {
    ReplayGain(
        trackGainDb = trackGainDb?.plus(R128_TO_REPLAYGAIN_DB),
        trackPeak = trackPeak,
        albumGainDb = albumGainDb?.plus(R128_TO_REPLAYGAIN_DB),
        albumPeak = albumPeak,
        isR128 = false,
    )
}

/** Base64, which the picture comment of a Vorbis tag is wrapped in. */
internal object Base64 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val REVERSE: IntArray = IntArray(128) { -1 }.also { table ->
        for (i in ALPHABET.indices) table[ALPHABET[i].code] = i
    }

    fun encode(bytes: ByteArray): String {
        val encodedSize = ((bytes.size.toLong() + 2) / 3) * 4
        if (encodedSize > Int.MAX_VALUE) {
            throw AudioKodecsException("base64 output is too large for a Kotlin string")
        }
        val out = StringBuilder(encodedSize.toInt())
        var i = 0
        while (i + 2 < bytes.size) {
            val value = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(value shr 18) and 0x3F])
            out.append(ALPHABET[(value shr 12) and 0x3F])
            out.append(ALPHABET[(value shr 6) and 0x3F])
            out.append(ALPHABET[value and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val value = (bytes[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(value shr 18) and 0x3F])
                out.append(ALPHABET[(value shr 12) and 0x3F])
                out.append("==")
            }
            2 -> {
                val value = ((bytes[i].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(value shr 18) and 0x3F])
                out.append(ALPHABET[(value shr 12) and 0x3F])
                out.append(ALPHABET[(value shr 6) and 0x3F])
                out.append('=')
            }
        }
        return out.toString()
    }

    /** Decodes [text], or returns null when it is not valid base64. */
    fun decodeOrNull(
        text: String,
        options: DecodeOptions = DecodeOptions.DEFAULT,
    ): ByteArray? {
        var symbolCount = 0
        var padding = 0
        var paddingStarted = false
        for (c in text) {
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue
            symbolCount++
            if (c == '=') {
                paddingStarted = true
                padding++
                if (padding > 2) return null
            } else {
                if (paddingStarted || c.code >= 128 || REVERSE[c.code] < 0) return null
            }
        }
        if (symbolCount == 0) return ByteArray(0)
        if (padding > 0 && symbolCount % 4 != 0) return null
        val dataSymbols = symbolCount - padding
        if (dataSymbols % 4 == 1) return null
        val requiredPadding = when (dataSymbols % 4) {
            2 -> 2
            3 -> 1
            else -> 0
        }
        if (padding > 0 && padding != requiredPadding) return null

        val decodedCapacity = dataSymbols.toLong() * 6L / 8L
        if (decodedCapacity > Int.MAX_VALUE) return null
        options.checkAllocation(decodedCapacity, "a base64-decoded tag value")
        val out = ByteArray(decodedCapacity.toInt())
        var written = 0
        var accumulator = 0
        var bits = 0
        for (c in text) {
            if (c == '=' || c == ' ' || c == '\t' || c == '\n' || c == '\r') continue
            val value = REVERSE[c.code]
            accumulator = (accumulator shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                if (written >= out.size) return null
                out[written++] = (accumulator shr bits).toByte()
            }
        }
        if (written != out.size) return null
        if (bits > 0 && accumulator and ((1 shl bits) - 1) != 0) return null
        return out
    }
}

/**
 * The picture structure FLAC defines, which a Vorbis comment also carries in
 * base64.
 */
internal object FlacPicture {

    fun parse(
        bytes: ByteArray,
        options: DecodeOptions = DecodeOptions.DEFAULT,
    ): TagPicture? {
        if (bytes.size < 32) return null
        var position = 0
        fun readInt(): Int {
            val value = ((bytes[position].toInt() and 0xFF) shl 24) or
                ((bytes[position + 1].toInt() and 0xFF) shl 16) or
                ((bytes[position + 2].toInt() and 0xFF) shl 8) or
                (bytes[position + 3].toInt() and 0xFF)
            position += 4
            return value
        }

        val type = readInt()
        val mimeLength = readInt()
        if (mimeLength < 0 || mimeLength > bytes.size - position) return null
        val mime = TagText.decodeUtf8OrNull(bytes, position, mimeLength, options) ?: return null
        if (mime.isEmpty() || mime.any { it.code !in 0x20..0x7E }) return null
        position += mimeLength
        if (position > bytes.size - 4) return null
        val descriptionLength = readInt()
        if (descriptionLength < 0 || descriptionLength > bytes.size - position) return null
        val description = TagText.decodeUtf8OrNull(
            bytes,
            position,
            descriptionLength,
            options,
        ) ?: return null
        position += descriptionLength
        if (position > bytes.size - 20) return null
        val width = readInt()
        val height = readInt()
        val depth = readInt()
        val colours = readInt()
        val dataLength = readInt()
        if (width < 0 || height < 0 || depth < 0 || colours < 0) return null
        if (dataLength < 0 || dataLength != bytes.size - position) return null
        options.checkAllocation(dataLength.toLong(), "a FLAC picture image")

        return TagPicture(
            type = PictureType.fromCode(type),
            mimeType = mime,
            description = description,
            bytes = bytes.copyOfRange(position, position + dataLength),
            width = width,
            height = height,
            colourDepth = depth,
            colourCount = colours,
        )
    }

    /** Builds the structure, which is what writing a picture to FLAC needs. */
    fun build(picture: TagPicture): ByteArray {
        val mime = picture.mimeType.encodeToByteArray()
        require(mime.isNotEmpty() && mime.all { (it.toInt() and 0xFF) in 0x20..0x7E }) {
            "a FLAC picture MIME type must be nonempty printable ASCII"
        }
        val description = picture.description.encodeToByteArray()
        val size = 32L + mime.size + description.size + picture.bytes.size
        if (size > Int.MAX_VALUE) {
            throw AudioKodecsException("a FLAC picture is too large for a Kotlin array")
        }
        val out = ByteArray(size.toInt())
        var position = 0
        fun writeInt(value: Int) {
            out[position] = (value ushr 24).toByte()
            out[position + 1] = (value ushr 16).toByte()
            out[position + 2] = (value ushr 8).toByte()
            out[position + 3] = value.toByte()
            position += 4
        }
        writeInt(picture.type.ordinal)
        writeInt(mime.size)
        mime.copyInto(out, position)
        position += mime.size
        writeInt(description.size)
        description.copyInto(out, position)
        position += description.size
        writeInt(picture.width)
        writeInt(picture.height)
        writeInt(picture.colourDepth)
        writeInt(picture.colourCount)
        writeInt(picture.bytes.size)
        picture.bytes.copyInto(out, position)
        return out
    }
}

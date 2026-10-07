package io.github.yuroyami.audiokodec.internal.tag

import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.tag.RawTagBlock
import io.github.yuroyami.audiokodec.tag.RawTagEntry
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat

/**
 * APEv1 and APEv2, the tag format Monkey's Audio brought and WavPack,
 * Musepack and some MP3 files adopted.
 *
 * The design is the opposite of ID3v1: keys are free text, values are UTF-8,
 * and nothing is a fixed length. A block has a footer, and version 2 blocks
 * usually have a matching header as well, which is what lets one sit at the
 * front of a file as well as the back.
 */
internal object ApeTag {

    /** Bytes in the header, and in the footer, which have the same layout. */
    const val FOOTER_SIZE: Int = 32

    private const val FLAG_HAS_HEADER = 1 shl 31
    private const val FLAG_HAS_NO_FOOTER = 1 shl 30
    private const val FLAG_IS_HEADER = 1 shl 29
    private const val RESERVED_GLOBAL_FLAGS = 0x1FFF_FFFE
    private const val ALLOWED_ITEM_FLAGS = 0x7

    private val prohibitedKeys = setOf("ID3", "TAG", "OGGS", "MP+")

    /** Value kinds, from the two bits the item flags spend on them. */
    private const val KIND_TEXT = 0
    private const val KIND_BINARY = 1
    private const val KIND_LOCATOR = 2

    /**
     * Finds a block whose footer ends at [end], or returns null.
     *
     * APE blocks at the end of a file are found this way, working backwards
     * from the footer, because nothing points at them from the front.
     */
    fun findEndingAt(
        bytes: ByteArray,
        end: Int,
        options: DecodeOptions,
    ): RawTagBlock? {
        val footerStart = end - FOOTER_SIZE
        if (footerStart < 0) return null
        if (!isPreamble(bytes, footerStart)) return null

        val version = readUInt32Le(bytes, footerStart + 8)
        val bodySize = readUInt32Le(bytes, footerStart + 12)
        val itemCount = readUInt32Le(bytes, footerStart + 16)
        val flags = readUInt32Le(bytes, footerStart + 20)
        if (version != 1000 && version != 2000) return null
        if (bodySize < FOOTER_SIZE) return null
        if (itemCount < 0) return null
        if (version == 2000 &&
            !validGlobalPreamble(bytes, footerStart, flags, header = false)
        ) {
            return null
        }

        // The stated size counts the footer and excludes the header, which is
        // the one asymmetry in an otherwise tidy format.
        val hasHeader = version == 2000 && flags and FLAG_HAS_HEADER != 0
        if (hasHeader && bodySize > Int.MAX_VALUE - FOOTER_SIZE) return null
        val total = bodySize + if (hasHeader) FOOTER_SIZE else 0
        val start = end - total
        if (start < 0) return null

        val itemsStart = start + if (hasHeader) FOOTER_SIZE else 0
        if (hasHeader && !matchingHeader(
                bytes,
                start,
                version,
                bodySize,
                itemCount,
            )
        ) {
            return null
        }
        val itemsEnd = footerStart
        return parse(
            bytes,
            itemsStart,
            itemsEnd,
            version,
            start,
            total,
            itemCount,
            options,
        )
    }

    /**
     * Reads a block that starts at [at] with a header, or returns null.
     *
     * A version 2 block may sit at the front of a file, in which case it has
     * a header whose flags say so.
     */
    fun readStartingAt(
        bytes: ByteArray,
        at: Int,
        options: DecodeOptions,
    ): RawTagBlock? {
        if (!isPreamble(bytes, at)) return null
        val version = readUInt32Le(bytes, at + 8)
        val bodySize = readUInt32Le(bytes, at + 12)
        val itemCount = readUInt32Le(bytes, at + 16)
        val flags = readUInt32Le(bytes, at + 20)
        if (version != 2000 || itemCount < 0) return null
        if (!validGlobalPreamble(bytes, at, flags, header = true)) return null
        if (flags and FLAG_HAS_HEADER == 0) return null
        if (bodySize < FOOTER_SIZE) return null
        if (bodySize > Int.MAX_VALUE - FOOTER_SIZE) return null
        val total = bodySize + FOOTER_SIZE
        if (at < 0 || total > bytes.size - at) return null
        val footerStart = at + total - FOOTER_SIZE
        if (!matchingFooter(bytes, footerStart, version, bodySize, itemCount)) return null
        return parse(
            bytes,
            at + FOOTER_SIZE,
            footerStart,
            version,
            at,
            total,
            itemCount,
            options,
        )
    }

    private fun parse(
        bytes: ByteArray,
        from: Int,
        to: Int,
        version: Int,
        blockStart: Int,
        blockSize: Int,
        itemCount: Int,
        options: DecodeOptions,
    ): RawTagBlock? {
        // An item needs two size fields, a two-byte key and its terminator,
        // even when its value is empty. Reject an impossible count before a
        // hostile footer can turn a tiny block into a very long repeat loop.
        if (from < 0 || to < from || itemCount > (to - from) / 11) return null
        val entries = ArrayList<RawTagEntry>()
        var position = from
        var retainedEntries = 0L
        fun rememberEntry() {
            retainedEntries++
            options.checkAllocation(
                retainedEntries * 64L,
                "remembering APE tag entries",
            )
        }
        repeat(itemCount) {
            if (position > to - 8) return null
            val valueSize = readUInt32Le(bytes, position)
            val itemFlags = readUInt32Le(bytes, position + 4)
            if (version >= 2000 && itemFlags and ALLOWED_ITEM_FLAGS.inv() != 0) return null
            var keyEnd = position + 8
            while (keyEnd < to && bytes[keyEnd].toInt() != 0) keyEnd++
            if (keyEnd >= to) return null
            val keyLength = keyEnd - position - 8
            val key = TagText.decodeLatin1(
                bytes,
                position + 8,
                keyLength,
                options,
            )
            if (!validKey(key)) return null
            val valueStart = keyEnd + 1
            if (valueSize < 0 || valueSize > to - valueStart) return null

            val kind = if (version >= 2000) (itemFlags ushr 1) and 0x3 else KIND_TEXT
            when (kind) {
                KIND_BINARY -> {
                    options.checkAllocation(valueSize.toLong(), "an APE binary tag value")
                    rememberEntry()
                    entries.add(
                        RawTagEntry(
                            key,
                            binary = bytes.copyOfRange(valueStart, valueStart + valueSize),
                        ),
                    )
                }
                KIND_TEXT, KIND_LOCATOR -> {
                    // A text item may hold several values separated by nulls,
                    // which is how APE stores two artists.
                    var start = valueStart
                    val valueEnd = valueStart + valueSize
                    while (start <= valueEnd) {
                        var stop = start
                        while (stop < valueEnd && bytes[stop].toInt() != 0) stop++
                        val text = if (version >= 2000) {
                            TagText.decodeUtf8OrNull(
                                bytes,
                                start,
                                stop - start,
                                options,
                            ) ?: return null
                        } else {
                            TagText.decodeLatin1(bytes, start, stop - start, options)
                        }
                        rememberEntry()
                        entries.add(RawTagEntry(key, text = text))
                        if (stop == valueEnd) break
                        start = stop + 1
                    }
                }
                else -> return null
            }
            position = valueStart + valueSize
        }
        if (position != to) return null
        val format = if (version >= 2000) TagFormat.APEV2 else TagFormat.APEV1
        return RawTagBlock(format, blockStart.toLong(), blockSize.toLong(), entries)
    }

    private fun matchingHeader(
        bytes: ByteArray,
        at: Int,
        version: Int,
        size: Int,
        count: Int,
    ): Boolean {
        if (!isPreamble(bytes, at)) return false
        val flags = readUInt32Le(bytes, at + 20)
        return readUInt32Le(bytes, at + 8) == version &&
            readUInt32Le(bytes, at + 12) == size &&
            readUInt32Le(bytes, at + 16) == count &&
            validGlobalPreamble(bytes, at, flags, header = true) &&
            flags and FLAG_HAS_HEADER != 0
    }

    private fun matchingFooter(
        bytes: ByteArray,
        at: Int,
        version: Int,
        size: Int,
        count: Int,
    ): Boolean {
        if (!isPreamble(bytes, at)) return false
        val flags = readUInt32Le(bytes, at + 20)
        return readUInt32Le(bytes, at + 8) == version &&
            readUInt32Le(bytes, at + 12) == size &&
            readUInt32Le(bytes, at + 16) == count &&
            validGlobalPreamble(bytes, at, flags, header = false) &&
            flags and FLAG_HAS_HEADER != 0
    }

    /**
     * Builds a block, with a header as well as a footer.
     *
     * Version 2 is always written. Version 1 has no header, no binary values
     * and no declared encoding, and there is no reason to produce one now.
     */
    fun write(items: List<Pair<String, ByteArray>>, binaryKeys: Set<String> = emptySet()): ByteArray {
        var bodySize = 0L
        for ((key, value) in items) {
            require(validKey(key)) {
                "an APE item key must be 2..255 printable ASCII characters, was '$key'"
            }
            bodySize += 9L + key.length + value.size
            if (bodySize > Int.MAX_VALUE - FOOTER_SIZE * 2L) {
                throw AudioKodecException("an APE tag is too large for a Kotlin array")
            }
        }
        val total = FOOTER_SIZE * 2 + bodySize.toInt()
        val out = ByteArray(total)

        // The header, whose flags mark it as one.
        writePreamble(
            out,
            0,
            bodySize.toInt() + FOOTER_SIZE,
            items.size,
            FLAG_HAS_HEADER or FLAG_IS_HEADER,
        )

        var position = FOOTER_SIZE
        for ((key, value) in items) {
            writeUInt32Le(out, position, value.size)
            val kind = if (key in binaryKeys) KIND_BINARY else KIND_TEXT
            writeUInt32Le(out, position + 4, kind shl 1)
            position += 8
            val keyBytes = TagText.encodeLatin1(key)
            keyBytes.copyInto(out, position)
            position += keyBytes.size
            out[position++] = 0
            value.copyInto(out, position)
            position += value.size
        }

        // The footer, whose stated size counts itself and the body.
        writePreamble(
            out,
            position,
            bodySize.toInt() + FOOTER_SIZE,
            items.size,
            FLAG_HAS_HEADER,
        )
        return out
    }

    /** Builds an APEv2 block, retaining entries outside AudioKodec's model. */
    fun build(tags: TagBundle, picturesPreservedElsewhere: Boolean = false): ByteArray {
        if (!picturesPreservedElsewhere && tags.chapters.any { it.pictures.isNotEmpty() }) {
            throw UnsupportedFeatureException("chapter-local artwork in APE")
        }
        val text = LinkedHashMap<String, MutableList<String>>()
        val binary = LinkedHashMap<String, ByteArray>()

        fun addText(key: String, value: String) {
            if (value.isNotEmpty()) text.getOrPut(key) { ArrayList() }.add(value)
        }

        for ((field, values) in tags.fields) {
            if (values.isEmpty()) continue
            when (field) {
                TagField.TRACK_TOTAL, TagField.DISC_TOTAL -> Unit
                TagField.TRACK_NUMBER -> addText(
                    "Track",
                    joinWithTotal(values.first(), tags[TagField.TRACK_TOTAL]),
                )
                TagField.DISC_NUMBER -> addText(
                    "Disc",
                    joinWithTotal(values.first(), tags[TagField.DISC_TOTAL]),
                )
                else -> {
                    val key = TagMapping.apeKeyFor(field)
                        ?: TagMapping.vorbisKeyFor(field)
                        ?: field.name
                    for (value in values) addText(key, value)
                }
            }
        }

        // APE has no Opus loudness convention either.
        tags.replayGain?.inReplayGainReference()?.let { gain ->
            gain.trackGainDb?.let { addText("REPLAYGAIN_TRACK_GAIN", Id3v2Writer.formatDecibels(it)) }
            gain.trackPeak?.let { addText("REPLAYGAIN_TRACK_PEAK", it.toString()) }
            gain.albumGainDb?.let { addText("REPLAYGAIN_ALBUM_GAIN", Id3v2Writer.formatDecibels(it)) }
            gain.albumPeak?.let { addText("REPLAYGAIN_ALBUM_PEAK", it.toString()) }
        }

        val unique = TagPictureMerge()
        for (picture in tags.pictures) unique.add(picture)
        for (picture in unique.pictures) {
            val key = ApePictureKeys.key(picture.type)
            if (key in binary) {
                if (picturesPreservedElsewhere) continue
                throw UnsupportedFeatureException(
                    "multiple APE pictures under $key",
                    "APE permits each cover-art key only once. Include ID3v2 to retain every picture.",
                )
            }
            if ('\u0000' in picture.description) {
                throw UnsupportedFeatureException("null characters in an APE picture filename")
            }
            val description = picture.description.encodeToByteArray()
            val size = description.size.toLong() + 1 + picture.bytes.size
            if (size > Int.MAX_VALUE) {
                throw AudioKodecException("an APE picture is too large for a Kotlin array")
            }
            val value = ByteArray(size.toInt())
            description.copyInto(value, 0)
            picture.bytes.copyInto(value, description.size + 1)
            binary[key] = value
        }

        for (block in tags.raw.blocks) {
            if (block.format != TagFormat.APEV1 && block.format != TagFormat.APEV2) continue
            for (entry in block.entries) {
                if (TagMapping.fieldFor(block.format, entry) != null) continue
                if (TagMapping.numberAndTotalFor(block.format, entry.key) != null) continue
                val upper = entry.key.uppercase()
                if (upper.startsWith("COVER ART") || isModelledLoudnessName(entry.key)) {
                    continue
                }
                if (entry.binary != null) {
                    if (entry.key !in binary) binary[entry.key] = entry.binary
                } else {
                    // Raw values retain empty pieces and repetitions. Empty
                    // merged fields still follow addText's omission rule.
                    entry.text?.let { text.getOrPut(entry.key) { ArrayList() }.add(it) }
                }
            }
        }

        val items = ArrayList<Pair<String, ByteArray>>()
        for ((key, values) in text) items.add(key to joinUtf8(values))
        for ((key, value) in binary) items.add(key to value)
        return write(items, binary.keys)
    }

    private fun joinWithTotal(number: String, total: String?): String =
        if (total.isNullOrBlank()) number else "$number/$total"

    private fun joinUtf8(values: List<String>): ByteArray {
        val encoded = values.map { it.encodeToByteArray() }
        var size = (encoded.size - 1).coerceAtLeast(0).toLong()
        for (value in encoded) {
            size += value.size
            if (size > Int.MAX_VALUE) {
                throw AudioKodecException("an APE text item is too large for a Kotlin array")
            }
        }
        val out = ByteArray(size.toInt())
        var position = 0
        for (index in encoded.indices) {
            if (index > 0) position++
            encoded[index].copyInto(out, position)
            position += encoded[index].size
        }
        return out
    }

    private fun writePreamble(out: ByteArray, at: Int, size: Int, count: Int, flags: Int) {
        val magic = "APETAGEX"
        for (i in magic.indices) out[at + i] = magic[i].code.toByte()
        writeUInt32Le(out, at + 8, 2000)
        writeUInt32Le(out, at + 12, size)
        writeUInt32Le(out, at + 16, count)
        writeUInt32Le(out, at + 20, flags)
        // The last eight bytes are reserved and must be zero.
    }

    private fun isPreamble(bytes: ByteArray, at: Int): Boolean {
        if (at < 0 || at > bytes.size - FOOTER_SIZE) return false
        val magic = "APETAGEX"
        for (i in magic.indices) {
            if ((bytes[at + i].toInt() and 0xFF) != magic[i].code) return false
        }
        return true
    }

    private fun validGlobalPreamble(
        bytes: ByteArray,
        at: Int,
        flags: Int,
        header: Boolean,
    ): Boolean {
        if (flags and RESERVED_GLOBAL_FLAGS != 0) return false
        if (flags and FLAG_HAS_NO_FOOTER != 0) return false
        if ((flags and FLAG_IS_HEADER != 0) != header) return false
        for (index in at + 24 until at + FOOTER_SIZE) {
            if (bytes[index].toInt() != 0) return false
        }
        return true
    }

    private fun validKey(key: String): Boolean {
        if (key.length !in 2..255 || key.any { it.code !in 0x20..0x7E }) return false
        return key.uppercase() !in prohibitedKeys
    }

    private fun readUInt32Le(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun writeUInt32Le(out: ByteArray, at: Int, value: Int) {
        out[at] = value.toByte()
        out[at + 1] = (value ushr 8).toByte()
        out[at + 2] = (value ushr 16).toByte()
        out[at + 3] = (value ushr 24).toByte()
    }
}

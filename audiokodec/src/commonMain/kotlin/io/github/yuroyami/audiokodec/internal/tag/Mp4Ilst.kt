package io.github.yuroyami.audiokodec.internal.tag

import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.mp4.Mp4Box
import io.github.yuroyami.audiokodec.tag.PictureType
import io.github.yuroyami.audiokodec.tag.RawTagBlock
import io.github.yuroyami.audiokodec.tag.RawTagEntry
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat

/**
 * The item list Apple stores inside an MP4 file's `moov/udta/meta/ilst` box.
 *
 * Every item is a box named after what it holds, such as `©nam` for the
 * title, and holds one or more `data` boxes. A `data` box opens with a
 * well-known type, which says whether the value is UTF-8 text, a big-endian
 * integer, an image, or bytes whose meaning depends on the item, and a locale,
 * which writers leave at zero. A freeform item is named `----` and carries a
 * `mean` and a `name` box ahead of its data, which is how fields without an
 * atom of their own are stored.
 *
 * The reader gives a freeform item the key `----:mean:name`. An item whose
 * structure it cannot follow is kept whole, so that a rewrite returns it
 * unchanged rather than losing it.
 *
 * Written from Apple's QuickTime File Format specification, which defines
 * the metadata item list and the well-known data types.
 */
internal object Mp4Ilst {

    const val TYPE_IMPLICIT: Int = 0
    const val TYPE_UTF8: Int = 1
    const val TYPE_UTF16: Int = 2
    const val TYPE_UTF8_SORT: Int = 4
    const val TYPE_UTF16_SORT: Int = 5
    const val TYPE_GIF: Int = 12
    const val TYPE_JPEG: Int = 13
    const val TYPE_PNG: Int = 14
    const val TYPE_SIGNED: Int = 21
    const val TYPE_UNSIGNED: Int = 22
    const val TYPE_BMP: Int = 27

    private const val FREEFORM = "----"

    /**
     * Parses the `ilst` box [ilst], whose offsets are positions in [bytes].
     *
     * [base] is where [bytes] sits in the file, so the block can report where
     * the box is. A list whose item boxes overrun it is damage, and raises
     * [CorruptStreamException].
     */
    fun parse(
        bytes: ByteArray,
        ilst: Mp4Box,
        base: Long,
        options: DecodeOptions,
    ): RawTagBlock {
        val entries = ArrayList<RawTagEntry>()
        fun remember(entry: RawTagEntry) {
            options.checkAllocation((entries.size.toLong() + 1L) * 64L, "remembering MP4 metadata items")
            entries.add(entry)
        }
        for (item in Mp4Box.children(bytes, ilst.bodyOffset.toInt(), ilst.end.toInt(), base)) {
            val bodyStart = item.bodyOffset.toInt()
            val bodyEnd = item.end.toInt()
            val parsed = parseItem(item.type, bytes, bodyStart, bodyEnd, base, options)
            if (parsed != null) {
                for (entry in parsed) remember(entry)
            } else {
                options.checkAllocation((bodyEnd - bodyStart).toLong(), "retaining an unreadable MP4 metadata item")
                remember(RawTagEntry(item.type, binary = bytes.copyOfRange(bodyStart, bodyEnd)).markOpaqueMp4Item())
            }
        }
        return RawTagBlock(TagFormat.MP4_ILST, base + ilst.offset, ilst.size, entries)
    }

    /** The entries one item holds, or null when its structure is not the documented one. */
    private fun parseItem(
        type: String,
        bytes: ByteArray,
        from: Int,
        to: Int,
        base: Long,
        options: DecodeOptions,
    ): List<RawTagEntry>? {
        val children = try {
            Mp4Box.children(bytes, from, to, base)
        } catch (_: AudioKodecException) {
            return null
        }
        var mean: String? = null
        var name: String? = null
        val values = ArrayList<RawTagEntry>()
        for (child in children) {
            val start = child.bodyOffset.toInt()
            val length = child.bodySize.toInt()
            when (child.type) {
                "mean", "name" -> {
                    // Both are full boxes: a version and flags, then UTF-8.
                    if (type != FREEFORM || values.isNotEmpty() || length < 4) return null
                    val text = TagText.decodeUtf8OrNull(bytes, start + 4, length - 4, options) ?: return null
                    if (child.type == "mean") {
                        if (mean != null || text.isEmpty() || ':' in text) return null
                        mean = text
                    } else {
                        if (name != null) return null
                        name = text
                    }
                }
                "data" -> {
                    if (length < 8 || bytes[start].toInt() != 0) return null
                    val key = if (type == FREEFORM) {
                        "$FREEFORM:${mean ?: return null}:${name ?: return null}"
                    } else {
                        type
                    }
                    val dataType = ((bytes[start + 1].toInt() and 0xFF) shl 16) or Mp4Box.readU16(bytes, start + 2)
                    values += valueOf(key, dataType, bytes, start + 8, length - 8, options) ?: return null
                }
                else -> return null
            }
        }
        return values.ifEmpty { null }
    }

    private fun valueOf(
        key: String,
        type: Int,
        bytes: ByteArray,
        at: Int,
        length: Int,
        options: DecodeOptions,
    ): RawTagEntry? {
        fun copy(): ByteArray {
            options.checkAllocation(length.toLong(), "an MP4 metadata value")
            return bytes.copyOfRange(at, at + length)
        }
        val entry = when (type) {
            TYPE_UTF8, TYPE_UTF8_SORT ->
                RawTagEntry(key, text = TagText.decodeUtf8OrNull(bytes, at, length, options) ?: return null)
            TYPE_UTF16, TYPE_UTF16_SORT -> {
                if (length % 2 != 0) return null
                RawTagEntry(key, text = TagText.decodeUtf16(bytes, at, length, defaultBigEndian = true, allowBom = false, options = options))
            }
            TYPE_SIGNED, TYPE_UNSIGNED -> RawTagEntry(key, text = integerText(bytes, at, length, type == TYPE_SIGNED), binary = copy())
            TYPE_GIF, TYPE_JPEG, TYPE_PNG, TYPE_BMP -> RawTagEntry(key, text = mimeOf(type), binary = copy())
            TYPE_IMPLICIT -> when (key) {
                "trkn", "disk" -> RawTagEntry(key, text = pairText(bytes, at, length), binary = copy())
                "gnre" -> {
                    val code = if (length == 2) Mp4Box.readU16(bytes, at) else 0
                    RawTagEntry(key, text = Id3Genres.nameOf(code - 1), binary = copy())
                }
                // Some writers leave these two integers without a type.
                "tmpo", "cpil" -> RawTagEntry(key, text = integerText(bytes, at, length, signed = false), binary = copy())
                else -> RawTagEntry(key, binary = copy())
            }
            else -> RawTagEntry(key, binary = copy())
        }
        return entry.withMp4DataType(type)
    }

    /** A big-endian integer of one, two, three, four or eight bytes, as decimal text. */
    private fun integerText(bytes: ByteArray, at: Int, length: Int, signed: Boolean): String? {
        if (length !in 1..4 && length != 8) return null
        var value = 0UL
        for (i in 0 until length) value = (value shl 8) or (bytes[at + i].toULong() and 0xFFUL)
        if (!signed) return value.toString()
        val shift = 64 - 8 * length
        return ((value shl shift).toLong() shr shift).toString()
    }

    /**
     * A track or disc position. Two reserved bytes, the number and the total,
     * each a 16-bit value where zero means not stated, read as `3/12`, `3` or
     * `/12` so that the shared reader can split it the way it splits ID3's
     * `TRCK`.
     */
    private fun pairText(bytes: ByteArray, at: Int, length: Int): String? {
        if (length < 4) return null
        val number = Mp4Box.readU16(bytes, at + 2)
        val total = if (length >= 6) Mp4Box.readU16(bytes, at + 4) else 0
        return when {
            number > 0 && total > 0 -> "$number/$total"
            number > 0 -> "$number"
            total > 0 -> "/$total"
            else -> null
        }
    }

    private fun mimeOf(type: Int): String = when (type) {
        TYPE_GIF -> "image/gif"
        TYPE_JPEG -> "image/jpeg"
        TYPE_PNG -> "image/png"
        else -> "image/bmp"
    }

    private fun imageTypeOf(mime: String): Int? = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> TYPE_JPEG
        "image/png" -> TYPE_PNG
        "image/gif" -> TYPE_GIF
        "image/bmp" -> TYPE_BMP
        else -> null
    }

    /**
     * Checks that [tags] fits an item list before anything is written.
     *
     * Every modelled field has a home, through a freeform item when no atom
     * holds it, so only artwork can fail. The cover atom stores an image and
     * its format, and nothing about what it depicts.
     */
    fun requireRepresentable(tags: TagBundle) {
        for (picture in tags.pictures) {
            if (picture.type != PictureType.FRONT_COVER || picture.description.isNotEmpty()) {
                throw UnsupportedFeatureException(
                    "${picture.type} artwork with a description in MP4",
                    "MP4 cover art holds front covers only, without a description; " +
                        "this picture is ${picture.type}" +
                        if (picture.description.isNotEmpty()) " described as \"${picture.description}\"" else "",
                )
            }
            if (imageTypeOf(picture.mimeType) == null) {
                throw UnsupportedFeatureException(
                    "${picture.mimeType} cover art in MP4",
                    "MP4 cover art is JPEG, PNG, GIF or BMP; this picture is ${picture.mimeType}",
                )
            }
        }
    }

    /**
     * Builds the body of an `ilst` box for [tags], or returns null when there
     * is nothing to store.
     *
     * A field is written under the atom the file already used for it, so a
     * file that keeps its description in `ldes` keeps it there. Otherwise the
     * standard atom is used, and a freeform item for a field without one. The
     * integer atoms take only a value they can hold exactly; anything else
     * goes into a freeform item instead of being changed to fit. Items the
     * library does not model are copied from the raw view as they were read.
     */
    fun build(tags: TagBundle): ByteArray? {
        requireRepresentable(tags)
        val items = ArrayList<ByteArray>()
        val preferred = preferredKeys(tags)
        val handled = HashSet<TagField>()

        fun text(key: String, values: List<String>) {
            val data = values.filter { it.isNotEmpty() }.map { data(TYPE_UTF8, it.encodeToByteArray()) }
            if (data.isNotEmpty()) items += item(key, data)
        }
        fun freeform(field: TagField, values: List<String>) =
            text(freeformKey(TagMapping.MP4_FREEFORM_MEAN, TagMapping.mp4FreeformNameFor(field)), values)

        fun pair(atom: String, numberField: TagField, totalField: TagField, valueBytes: Int) {
            handled += numberField
            handled += totalField
            val numbers = tags.all(numberField).filter { it.isNotEmpty() }
            val totals = tags.all(totalField).filter { it.isNotEmpty() }
            val number = if (numbers.size <= 1) numbers.firstOrNull()?.let(::position) else null
            val total = if (totals.size <= 1) totals.firstOrNull()?.let(::position) else null
            if ((numbers.isEmpty() || number != null) && (totals.isEmpty() || total != null)) {
                if (number == null && total == null) return
                val value = ByteArray(valueBytes)
                writeU16(value, 2, number ?: 0)
                writeU16(value, 4, total ?: 0)
                items += item(atom, listOf(data(TYPE_IMPLICIT, value)))
            } else {
                freeform(numberField, numbers)
                freeform(totalField, totals)
            }
        }

        for ((field, values) in tags.fields) {
            if (field in handled) continue
            when (field) {
                TagField.TRACK_NUMBER, TagField.TRACK_TOTAL ->
                    pair("trkn", TagField.TRACK_NUMBER, TagField.TRACK_TOTAL, 8)
                TagField.DISC_NUMBER, TagField.DISC_TOTAL ->
                    pair("disk", TagField.DISC_NUMBER, TagField.DISC_TOTAL, 6)
                TagField.BPM -> {
                    val tempo = values.singleOrNull()?.toIntOrNull()?.takeIf { it in 0..0xFFFF && it.toString() == values.single() }
                    if (tempo != null) {
                        items += item("tmpo", listOf(data(TYPE_SIGNED, byteArrayOf((tempo shr 8).toByte(), tempo.toByte()))))
                    } else {
                        freeform(field, values)
                    }
                }
                TagField.COMPILATION -> {
                    val flag = values.singleOrNull()?.takeIf { it == "0" || it == "1" }
                    if (flag != null) {
                        items += item("cpil", listOf(data(TYPE_SIGNED, byteArrayOf(if (flag == "1") 1 else 0))))
                    } else {
                        freeform(field, values)
                    }
                }
                else -> {
                    val key = preferred[field] ?: TagMapping.mp4AtomFor(field)
                    if (key != null) text(key, values) else freeform(field, values)
                }
            }
        }

        // ReplayGain has no reference field in MP4, so an Opus gain is
        // restated against the ReplayGain reference, as ID3 does.
        tags.replayGain?.inReplayGainReference()?.let { gain ->
            fun loudness(name: String, value: String) =
                text(freeformKey(TagMapping.MP4_FREEFORM_MEAN, name), listOf(value))
            gain.trackGainDb?.let { loudness("replaygain_track_gain", Id3v2Writer.formatDecibels(it)) }
            gain.trackPeak?.let { loudness("replaygain_track_peak", "$it") }
            gain.albumGainDb?.let { loudness("replaygain_album_gain", Id3v2Writer.formatDecibels(it)) }
            gain.albumPeak?.let { loudness("replaygain_album_peak", "$it") }
        }

        if (tags.pictures.isNotEmpty()) {
            items += item("covr", tags.pictures.map { data(imageTypeOf(it.mimeType)!!, it.bytes) })
        }

        preserveUnmodelled(tags, items)
        if (items.isEmpty()) return null
        return concatenate(items, "an MP4 item list")
    }

    /**
     * Copies the items AudioKodec does not model, such as iTunes' own
     * gapless and normalisation values and its purchase records.
     *
     * Consecutive values under one key were one item with several `data`
     * boxes, and stay one. An item that could not be read is copied whole,
     * unless the bundle now states the field its atom would hold.
     */
    private fun preserveUnmodelled(tags: TagBundle, items: MutableList<ByteArray>) {
        var pendingKey: String? = null
        val pending = ArrayList<ByteArray>()
        fun flush() {
            val key = pendingKey ?: return
            items += item(key, pending.toList())
            pending.clear()
            pendingKey = null
        }
        for (block in tags.raw.blocksOf(TagFormat.MP4_ILST)) {
            for (entry in block.entries) {
                if (entry.isOpaqueMp4Item) {
                    flush()
                    if (!replacedByBundle(entry.key, tags)) {
                        items += box(checkedAtom(entry.key), entry.binary ?: ByteArray(0))
                    }
                    continue
                }
                if (isModelled(entry)) continue
                val value = entry.binary?.let { data(entry.mp4DataType ?: TYPE_IMPLICIT, it) }
                    ?: entry.text?.let { data(TYPE_UTF8, it.encodeToByteArray()) }
                    ?: continue
                if (entry.key != pendingKey) {
                    flush()
                    checkedKey(entry.key)
                    pendingKey = entry.key
                }
                pending += value
            }
            flush()
        }
    }

    private fun isModelled(entry: RawTagEntry): Boolean {
        if (entry.key == "covr") return true
        if (TagMapping.fieldFor(TagFormat.MP4_ILST, entry) != null) return true
        if (TagMapping.numberAndTotalFor(TagFormat.MP4_ILST, entry.key) != null) return true
        val name = TagMapping.mp4FreeformName(entry.key)
        return name != null && isModelledLoudnessName(name)
    }

    private fun replacedByBundle(atom: String, tags: TagBundle): Boolean {
        if (atom == "covr") return tags.pictures.isNotEmpty()
        val pair = TagMapping.numberAndTotalFor(TagFormat.MP4_ILST, atom)
        if (pair != null) return pair.first in tags.fields || pair.second in tags.fields
        val field = TagMapping.fieldFor(TagFormat.MP4_ILST, RawTagEntry(atom)) ?: return false
        return field in tags.fields
    }

    /** Fields already stored under a key the file chose keep that key. */
    private fun preferredKeys(tags: TagBundle): Map<TagField, String> {
        val preferred = HashMap<TagField, String>()
        for (block in tags.raw.blocksOf(TagFormat.MP4_ILST)) {
            for (entry in block.entries) {
                if (entry.isOpaqueMp4Item || entry.text == null) continue
                // A rewrite stores fields as text, so an atom that held a
                // binary value, such as the numbered genre, is not reused.
                if (entry.binary != null) continue
                val field = TagMapping.fieldFor(TagFormat.MP4_ILST, entry) ?: continue
                if (field !in preferred) preferred[field] = entry.key
            }
        }
        return preferred
    }

    private fun position(text: String): Int? =
        text.toIntOrNull()?.takeIf { it in 1..0xFFFF && it.toString() == text }

    private fun freeformKey(mean: String, name: String): String = "$FREEFORM:$mean:$name"

    /** Checks a raw key names an item this format can store. */
    private fun checkedKey(key: String) {
        if (key.startsWith("$FREEFORM:")) {
            val mean = TagMapping.mp4FreeformMean(key)
            if (mean.isNullOrEmpty()) {
                throw UnsupportedFeatureException(
                    "the MP4 item key \"$key\"",
                    "a freeform MP4 item key is ----:mean:name, with a mean that holds no colon",
                )
            }
        } else {
            checkedAtom(key)
        }
    }

    private fun checkedAtom(key: String): String {
        if (key.length != 4 || key.any { it.code > 0xFF }) {
            throw UnsupportedFeatureException(
                "the MP4 item key \"$key\"",
                "an MP4 item is named by four Latin-1 characters, or is a ----:mean:name freeform item",
            )
        }
        return key
    }

    private fun item(key: String, data: List<ByteArray>): ByteArray {
        val name = TagMapping.mp4FreeformName(key)
        if (name == null) return box(checkedAtom(key), concatenate(data, "an MP4 item"))
        val mean = TagMapping.mp4FreeformMean(key)!!
        val parts = ArrayList<ByteArray>(data.size + 2)
        parts += box("mean", ByteArray(4) + mean.encodeToByteArray())
        parts += box("name", ByteArray(4) + name.encodeToByteArray())
        parts += data
        return box(FREEFORM, concatenate(parts, "an MP4 freeform item"))
    }

    private fun data(type: Int, value: ByteArray): ByteArray {
        val head = ByteArray(8)
        head[1] = (type shr 16).toByte()
        head[2] = (type shr 8).toByte()
        head[3] = type.toByte()
        return box("data", head, value)
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        var size = 8L
        for (part in parts) size += part.size
        if (size > Int.MAX_VALUE) throw AudioKodecException("the MP4 '$type' box is too large for a Kotlin array")
        val out = ByteArray(size.toInt())
        writeU32(out, 0, size)
        for (i in 0 until 4) out[4 + i] = type[i].code.toByte()
        var at = 8
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    private fun concatenate(parts: List<ByteArray>, what: String): ByteArray {
        var size = 0L
        for (part in parts) {
            size += part.size
            if (size > Int.MAX_VALUE - 16) throw AudioKodecException("$what is too large for a Kotlin array")
        }
        val out = ByteArray(size.toInt())
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    private fun writeU16(into: ByteArray, at: Int, value: Int) {
        into[at] = (value shr 8).toByte()
        into[at + 1] = value.toByte()
    }

    private fun writeU32(into: ByteArray, at: Int, value: Long) {
        into[at] = (value shr 24).toByte()
        into[at + 1] = (value shr 16).toByte()
        into[at + 2] = (value shr 8).toByte()
        into[at + 3] = value.toByte()
    }
}

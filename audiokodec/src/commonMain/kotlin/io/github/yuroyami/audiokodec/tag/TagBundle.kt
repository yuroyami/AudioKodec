package io.github.yuroyami.audiokodec.tag

import io.github.yuroyami.audiokodec.internal.tag.Id3ChapterSource
import io.github.yuroyami.audiokodec.internal.tag.Id3Dates
import io.github.yuroyami.audiokodec.internal.tag.TagMapping
import io.github.yuroyami.audiokodec.internal.tag.isModelledLoudnessName

/**
 * A tag format, meaning the way a block of metadata is laid out.
 *
 * One file can carry several. An MP3 with an ID3v2 block at the front, an APE
 * tag before its ID3v1 block, and an ID3v1 block at the end is unusual but
 * entirely legal, and a reader has to decide which one wins. [TagBundle]
 * applies [precedence] for that.
 */
public enum class TagFormat(
    /** A short name for an error message or a UI label. */
    public val displayName: String,
    /**
     * Which block wins when two formats disagree, higher first.
     *
     * The order is the one every established player uses: ID3v2 beats APE,
     * APE beats ID3v1. ID3v1 is last because its fields are fixed-length and
     * truncated, so it is almost always the stale copy.
     */
    public val precedence: Int,
) {
    /** ID3v2.2, the oldest version still found in the wild. */
    ID3V2_2("ID3v2.2", 100),

    /** ID3v2.3, the most widely written version. */
    ID3V2_3("ID3v2.3", 100),

    /** ID3v2.4, the current version. */
    ID3V2_4("ID3v2.4", 100),

    /** APEv2, which WavPack, Musepack and Monkey's Audio use, and MP3 sometimes. */
    APEV2("APEv2", 80),

    /** APEv1, which has no header and no binary values. */
    APEV1("APEv1", 70),

    /** Vorbis comments, used by Ogg and by FLAC. */
    VORBIS_COMMENT("Vorbis comment", 90),

    /** The metadata atoms of an MP4 file. */
    MP4_ILST("MP4 ilst", 90),

    /** The `LIST INFO` chunk of a RIFF file. */
    RIFF_INFO("RIFF INFO", 60),

    /** The Broadcast Wave `bext` chunk. */
    BWF_BEXT("Broadcast Wave bext", 50),

    /** The text chunks of an AIFF file. */
    AIFF_TEXT("AIFF text chunks", 60),

    /** ID3v1 or ID3v1.1, 128 fixed bytes at the end of a file. */
    ID3V1("ID3v1", 10),

    /** Lyrics3v2, which sits between the audio and an ID3v1 block. */
    LYRICS3("Lyrics3v2", 20),
}

/** What a picture in a tag depicts. The numbering is ID3v2's. */
public enum class PictureType {
    OTHER,
    FILE_ICON,
    OTHER_FILE_ICON,
    FRONT_COVER,
    BACK_COVER,
    LEAFLET_PAGE,
    MEDIA,
    LEAD_ARTIST,
    ARTIST,
    CONDUCTOR,
    BAND,
    COMPOSER,
    LYRICIST,
    RECORDING_LOCATION,
    DURING_RECORDING,
    DURING_PERFORMANCE,
    SCREEN_CAPTURE,
    BRIGHT_COLOURED_FISH,
    ILLUSTRATION,
    BAND_LOGO,
    PUBLISHER_LOGO,
    ;

    public companion object {
        /** The type a stored code names, or [OTHER] when the code is unknown. */
        public fun fromCode(code: Int): PictureType =
            entries.getOrNull(code) ?: OTHER
    }
}

/**
 * An image carried inside a tag.
 *
 * The bytes are the image file exactly as it was stored. AudioKodec does not
 * decode it, and does not need to: pass them to ImageKodec, or to whatever the
 * platform uses.
 */
public class TagPicture(
    /** What the picture depicts. */
    public val type: PictureType = PictureType.FRONT_COVER,
    /** Media type, such as `image/jpeg`. */
    public val mimeType: String,
    /** Caption, which is usually empty. */
    public val description: String = "",
    /** The image file, unchanged. */
    public val bytes: ByteArray,
    /** Width in pixels when the format states it, otherwise 0. */
    public val width: Int = 0,
    /** Height in pixels when the format states it, otherwise 0. */
    public val height: Int = 0,
    /** Bits per pixel when the format states it, otherwise 0. */
    public val colourDepth: Int = 0,
    /** Palette size for indexed images when the format states it, otherwise 0. */
    public val colourCount: Int = 0,
) {
    init {
        require(width >= 0) { "picture width must not be negative, was $width" }
        require(height >= 0) { "picture height must not be negative, was $height" }
        require(colourDepth >= 0) {
            "picture colour depth must not be negative, was $colourDepth"
        }
        require(colourCount >= 0) {
            "picture colour count must not be negative, was $colourCount"
        }
    }

    override fun toString(): String =
        "TagPicture($type, $mimeType, ${bytes.size} bytes)"
}

/**
 * One chapter of a long recording.
 *
 * Audiobooks and podcasts carry these. ID3v2 stores them in `CHAP` frames and
 * MP4 in a chapter track or a `chpl` atom, and both end up here.
 */
public class TagChapter(
    /** Identifier the file used, which is unique within the file. */
    public val id: String,
    /** Start time in milliseconds from the beginning. */
    public val startMillis: Long,
    /** End time in milliseconds, or -1 when the file does not state one. */
    public val endMillis: Long = -1,
    /** Chapter title. */
    public val title: String? = null,
    /** Artwork attached to this chapter. */
    pictures: List<TagPicture> = emptyList(),
) {
    /** Artwork attached to this chapter. */
    public val pictures: List<TagPicture> = pictures.toList()

    init {
        require(id.isNotEmpty()) { "a chapter id must not be empty" }
        require(startMillis >= 0) {
            "chapter start must not be negative, was $startMillis"
        }
        require(endMillis == -1L || endMillis >= startMillis) {
            "chapter end must be -1 or at least its start, was $endMillis after $startMillis"
        }
    }

    /** Stored subframes remain attached when the chapter is reused or copied. */
    internal var id3Source: Id3ChapterSource? = null
        private set

    internal fun withId3Source(source: Id3ChapterSource?): TagChapter {
        id3Source = source
        return this
    }

    /**
     * Copies this chapter, retaining stored ID3 subframes that are not modelled here.
     *
     * A changed title or picture list replaces its readable subframes.
     * Unreadable identities raise [io.github.yuroyami.audiokodec.UnsupportedFeatureException].
     * Construct a new [TagChapter] to replace all chapter metadata deliberately.
     */
    public fun copy(
        id: String = this.id,
        startMillis: Long = this.startMillis,
        endMillis: Long = this.endMillis,
        title: String? = this.title,
        pictures: List<TagPicture> = this.pictures,
    ): TagChapter = TagChapter(id, startMillis, endMillis, title, pictures).withId3Source(id3Source)

    override fun toString(): String = "TagChapter($id, $startMillis..$endMillis, $title)"
}

/**
 * Loudness information, in whichever of the two conventions the file used.
 *
 * ReplayGain 2 writes decibel values relative to -18 LUFS. Opus writes a
 * different quantity in `R128_TRACK_GAIN`: a whole number of Q7.8 decibels
 * relative to -23 LUFS. This class holds both as decibels and remembers which
 * reference they used, because applying one as though it were the other is a
 * 5 dB mistake.
 */
public class ReplayGain(
    /** Track gain in decibels, or null when the file does not state one. */
    public val trackGainDb: Double? = null,
    /** Track peak as a sample value, where 1.0 is full scale. */
    public val trackPeak: Double? = null,
    /** Album gain in decibels, or null when the file does not state one. */
    public val albumGainDb: Double? = null,
    /** Album peak as a sample value. */
    public val albumPeak: Double? = null,
    /** True when the values came from Opus, which references -23 LUFS. */
    public val isR128: Boolean = false,
) {
    init {
        require(trackGainDb == null || trackGainDb.isFinite()) {
            "track gain must be finite"
        }
        require(albumGainDb == null || albumGainDb.isFinite()) {
            "album gain must be finite"
        }
        require(trackPeak == null || (trackPeak.isFinite() && trackPeak >= 0.0)) {
            "track peak must be finite and nonnegative"
        }
        require(albumPeak == null || (albumPeak.isFinite() && albumPeak >= 0.0)) {
            "album peak must be finite and nonnegative"
        }
    }

    /** True when nothing was found. */
    public val isEmpty: Boolean
        get() = trackGainDb == null && albumGainDb == null &&
            trackPeak == null && albumPeak == null

    override fun toString(): String =
        "ReplayGain(track=${trackGainDb}dB, album=${albumGainDb}dB, r128=$isR128)"
}

/** One entry exactly as a tag block stored it. */
public class RawTagEntry(
    /** The key the file used, such as `TPE2`, `ALBUMARTIST` or `aART`. */
    public val key: String,
    /** The value as text, or null when it is binary. */
    public val text: String? = null,
    /**
     * The value as bytes, for entries that are not text. An unreadable Vorbis
     * comment retains its complete stored `KEY=value` bytes here; an
     * unreadable vendor header retains just its vendor bytes.
     */
    public val binary: ByteArray? = null,
    /**
     * Language code for the formats that carry one, such as ID3v2's comment
     * and lyric frames.
     */
    public val language: String? = null,
    /**
     * The description that distinguishes two entries with the same key, which
     * ID3v2 comments and `TXXX` frames need.
     */
    public val description: String? = null,
    /**
     * The two flag bytes of an ID3v2 frame kept exactly as stored because it
     * is compressed or encrypted, or null for every other entry.
     *
     * [binary] then holds the frame's stored payload, including the extra
     * bytes its flags announce, and nothing of it is interpreted. Writing the
     * tag back in the same ID3 version keeps the frame unchanged.
     */
    public val id3FrameFlags: Int? = null,
) {
    init {
        require(key.isNotEmpty()) { "a raw tag entry key must not be empty" }
        require(id3FrameFlags == null || id3FrameFlags in 0..0xFFFF) {
            "ID3 frame flags are two bytes, was $id3FrameFlags"
        }
    }

    /** Parser provenance keeps the vendor header distinct from a user comment. */
    internal var isVorbisVendorHeader: Boolean = false
        private set

    internal fun markVorbisVendorHeader(): RawTagEntry {
        isVorbisVendorHeader = true
        return this
    }

    /** Unreadable UTF-8 is retained without pretending it is modelled text. */
    internal var isOpaqueVorbisComment: Boolean = false
        private set

    internal fun markOpaqueVorbisComment(): RawTagEntry {
        isOpaqueVorbisComment = true
        return this
    }

    /** The well-known MP4 data type a stored `ilst` value declared, so a rewrite keeps it. */
    internal var mp4DataType: Int? = null
        private set

    internal fun withMp4DataType(type: Int): RawTagEntry {
        mp4DataType = type
        return this
    }

    /**
     * An MP4 metadata item whose structure was not understood. [binary] then
     * holds the item's complete body, which a rewrite copies unchanged.
     */
    internal var isOpaqueMp4Item: Boolean = false
        private set

    internal fun markOpaqueMp4Item(): RawTagEntry {
        isOpaqueMp4Item = true
        return this
    }

    override fun toString(): String = "RawTagEntry($key=${text ?: "${binary?.size ?: 0} bytes"})"
}

/** One tag block found in a file, with its entries as they were stored. */
public class RawTagBlock(
    /** Which format the block is in. */
    public val format: TagFormat,
    /** Byte offset of the block within the file. */
    public val offset: Long,
    /** Length of the block in bytes, including its own header and footer. */
    public val size: Long,
    /** Entries in the order they appeared. */
    entries: List<RawTagEntry>,
) {
    /** Entries in the order they appeared. */
    public val entries: List<RawTagEntry> = entries.toList()

    init {
        require(offset >= 0) { "a raw tag offset must not be negative, was $offset" }
        require(size >= 0) { "a raw tag size must not be negative, was $size" }
    }

    override fun toString(): String =
        "RawTagBlock(${format.displayName} at $offset, $size bytes, ${entries.size} entries)"
}

/** Every tag block a file holds, in the order they appear. */
public class RawTags(
    blocks: List<RawTagBlock>,
) {
    /** Every tag block, in file order. */
    public val blocks: List<RawTagBlock> = blocks.toList()

    /** The blocks in [format], if any. */
    public fun blocksOf(format: TagFormat): List<RawTagBlock> =
        blocks.filter { it.format == format }

    /** The formats present, highest precedence first. */
    public val formats: List<TagFormat>
        get() = blocks.map { it.format }.distinct().sortedByDescending { it.precedence }

    public companion object {
        public val EMPTY: RawTags = RawTags(emptyList())
    }
}

/**
 * Everything a file says about itself.
 *
 * The named fields are the merged view: when a file carries several tag
 * blocks, the one with the highest [TagFormat.precedence] wins for any field
 * they both hold. [raw] keeps every block as it was stored, so nothing is lost
 * by the merge.
 */
public class TagBundle(
    /** Merged fields, each of which can hold several values. */
    fields: Map<TagField, List<String>>,
    /** Distinct artwork across all blocks, with equivalent stored copies merged. */
    pictures: List<TagPicture> = emptyList(),
    /** Chapters, in the order they start. */
    chapters: List<TagChapter> = emptyList(),
    /** Loudness information, or null when none was found. */
    public val replayGain: ReplayGain? = null,
    /** Every block as it was stored. */
    public val raw: RawTags = RawTags.EMPTY,
) {
    /** Merged fields, each of which can hold several values. */
    public val fields: Map<TagField, List<String>> =
        fields
            .filterValues { it.isNotEmpty() }
            .mapValuesTo(LinkedHashMap()) { it.value.toList() }

    /** Distinct artwork across all blocks, with equivalent stored copies merged. */
    public val pictures: List<TagPicture> = pictures.toList()

    /** Chapters, in the order they start. */
    public val chapters: List<TagChapter> = chapters.toList()

    /** True when no modelled field, picture, chapter or loudness value was found. */
    public val isEmpty: Boolean
        get() = fields.isEmpty() && pictures.isEmpty() && chapters.isEmpty() &&
            (replayGain == null || replayGain.isEmpty)

    /** The first value of [field], or null when the file does not state it. */
    public operator fun get(field: TagField): String? = fields[field]?.firstOrNull()

    /** Every value of [field], which is empty when the file does not state it. */
    public fun all(field: TagField): List<String> = fields[field] ?: emptyList()

    /** The first value of [field] as a number, or null when it is absent or not one. */
    public fun number(field: TagField): Int? = get(field)?.trim()?.toIntOrNull()

    /** The front cover if there is one, otherwise the first picture, otherwise null. */
    public val cover: TagPicture?
        get() = pictures.firstOrNull { it.type == PictureType.FRONT_COVER } ?: pictures.firstOrNull()

    /**
     * A copy with the changes [block] describes.
     *
     * ```kotlin
     * val edited = tags.edit {
     *     set(TagField.TITLE, "New title")
     *     remove(TagField.COMMENT)
     * }
     * ```
     *
     * The result keeps [raw], so writing it back can preserve the entries
     * AudioKodec does not model. Editing a field replaces every value it had.
     * Editing [TagField.DATE] also removes its stored ID3 date components,
     * including incomplete and opaque values, so old components cannot return.
     * [TagEditor.clear] also removes the raw blocks.
     */
    public fun edit(block: TagEditor.() -> Unit): TagBundle {
        val editor = TagEditor(this)
        editor.block()
        return editor.build()
    }

    override fun toString(): String = buildString {
        append("TagBundle(")
        append(fields.size).append(" fields")
        if (pictures.isNotEmpty()) append(", ").append(pictures.size).append(" pictures")
        if (chapters.isNotEmpty()) append(", ").append(chapters.size).append(" chapters")
        if (raw.blocks.isNotEmpty()) {
            append(", from ").append(raw.formats.joinToString(" + ") { it.displayName })
        }
        append(")")
    }

    public companion object {
        /** No tags at all. */
        public val EMPTY: TagBundle = TagBundle(emptyMap())
    }
}

/** Builds a changed copy of a [TagBundle]. See [TagBundle.edit]. */
public class TagEditor internal constructor(private val original: TagBundle) {

    private val fields: MutableMap<TagField, MutableList<String>> =
        original.fields.mapValuesTo(LinkedHashMap()) { it.value.toMutableList() }
    private val pictures: MutableList<TagPicture> = original.pictures.toMutableList()
    private val chapters: MutableList<TagChapter> = original.chapters.toMutableList()
    private var replayGain: ReplayGain? = original.replayGain
    private var raw: RawTags = original.raw

    /** Replaces every value of [field] with [value]. */
    public fun set(field: TagField, value: String) {
        discardOpaqueCommentsFor(field)
        if (field == TagField.DATE) raw = Id3Dates.withoutPrimaryEntries(raw)
        fields[field] = mutableListOf(value)
    }

    /** Replaces every value of [field] with [values]. */
    public fun set(field: TagField, values: List<String>) {
        discardOpaqueCommentsFor(field)
        if (field == TagField.DATE) raw = Id3Dates.withoutPrimaryEntries(raw)
        if (values.isEmpty()) fields.remove(field) else fields[field] = values.toMutableList()
    }

    /** Replaces every value of [field] with [value] written as text. */
    public fun set(field: TagField, value: Int) {
        set(field, value.toString())
    }

    /** Adds another value to [field], keeping the ones already there. */
    public fun add(field: TagField, value: String) {
        discardOpaqueCommentsFor(field)
        if (field == TagField.DATE) raw = Id3Dates.withoutPrimaryEntries(raw)
        fields.getOrPut(field) { mutableListOf() }.add(value)
    }

    /** Removes [field] entirely. */
    public fun remove(field: TagField) {
        discardOpaqueCommentsFor(field)
        if (field == TagField.DATE) raw = Id3Dates.withoutPrimaryEntries(raw)
        fields.remove(field)
    }

    /** Removes every field, picture, chapter, loudness value and raw tag block. */
    public fun clear() {
        fields.clear()
        pictures.clear()
        chapters.clear()
        replayGain = null
        raw = RawTags.EMPTY
    }

    /** Adds a picture. */
    public fun addPicture(picture: TagPicture) {
        pictures.add(picture)
    }

    /** Removes every picture, or every picture of [type] when one is given. */
    public fun removePictures(type: PictureType? = null) {
        if (type == null) discardOpaqueComments {
            it.key.uppercase() in listOf("METADATA_BLOCK_PICTURE", "COVERART", "COVERARTMIME")
        }
        if (type == null) pictures.clear() else pictures.removeAll { it.type == type }
    }

    /** Replaces the chapters. */
    public fun setChapters(value: List<TagChapter>) {
        chapters.clear()
        chapters.addAll(value)
    }

    /** Sets the loudness information, or clears it with null. */
    public fun setReplayGain(value: ReplayGain?) {
        discardOpaqueComments { isModelledLoudnessName(it.key) }
        replayGain = value
    }

    private fun discardOpaqueCommentsFor(field: TagField) = discardOpaqueComments { entry ->
        TagMapping.fieldFor(TagFormat.VORBIS_COMMENT, entry) == field ||
            TagMapping.numberAndTotalFor(TagFormat.VORBIS_COMMENT, entry.key)?.let {
                field == it.first || field == it.second
            } == true
    }

    private fun discardOpaqueComments(matches: (RawTagEntry) -> Boolean) {
        if (raw.blocks.none { block -> block.format == TagFormat.VORBIS_COMMENT &&
                block.entries.any { it.isOpaqueVorbisComment && matches(it) }
            }) return
        raw = RawTags(raw.blocks.map { block ->
            if (block.format != TagFormat.VORBIS_COMMENT ||
                block.entries.none { it.isOpaqueVorbisComment && matches(it) }
            ) block else RawTagBlock(block.format, block.offset, block.size,
                block.entries.filterNot { it.isOpaqueVorbisComment && matches(it) })
        })
    }

    internal fun build(): TagBundle = TagBundle(
        fields = fields.filterValues { it.isNotEmpty() }.mapValues { it.value.toList() },
        pictures = pictures.toList(),
        chapters = chapters.toList(),
        replayGain = replayGain,
        raw = raw,
    )
}

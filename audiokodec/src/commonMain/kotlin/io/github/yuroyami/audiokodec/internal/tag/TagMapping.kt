package io.github.yuroyami.audiokodec.internal.tag

import io.github.yuroyami.audiokodec.tag.RawTagEntry
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat

/**
 * Which native key means which [TagField].
 *
 * Every tag format spells the same facts differently, and several spell them
 * more than one way. This is the whole translation, in one place, so that
 * adding a format means adding a table rather than threading a new case
 * through the readers.
 */
internal object TagMapping {
    const val MUSICBRAINZ_OWNER: String = "http://musicbrainz.org"

    fun isRecordingIdentifier(value: String): Boolean =
        value.length in 1..64 && value.all { it in '!'..'~' }

    private fun uniqueFileField(entry: RawTagEntry): TagField? {
        if (entry.description != MUSICBRAINZ_OWNER || entry.id3FrameFlags != null) return null
        val bytes = entry.binary
        val valid = if (bytes != null) {
            bytes.size in 1..64 && bytes.all { (it.toInt() and 255) in 33..126 }
        } else {
            entry.text?.let(::isRecordingIdentifier) == true
        }
        return if (valid) TagField.MUSICBRAINZ_RECORDING_ID else null
    }

    /** These ID3v2.2/v2.3 people fields define slash-separated lists. */
    fun id3UsesSlashList(key: String, major: Int): Boolean = major in 2..3 && when (
        if (major == 2) Id3v22Frames.identifier(key, 3) else key
    ) {
        "TPE1", "TCOM", "TEXT", "TOPE", "TOLY" -> true
        else -> false
    }

    /** ID3v2.3 and 2.4 frame identifiers. */
    private val ID3V2_4: Map<String, TagField> = mapOf(
        "TIT2" to TagField.TITLE,
        "TIT1" to TagField.GROUPING,
        "TIT3" to TagField.DESCRIPTION,
        "TPE1" to TagField.ARTIST,
        "TPE2" to TagField.ALBUM_ARTIST,
        "TPE3" to TagField.CONDUCTOR,
        "TPE4" to TagField.REMIXER,
        "TALB" to TagField.ALBUM,
        "TCOM" to TagField.COMPOSER,
        "TEXT" to TagField.LYRICIST,
        "TCON" to TagField.GENRE,
        "TYER" to TagField.DATE,
        "TDRC" to TagField.DATE,
        "TDRL" to TagField.RELEASE_DATE,
        "TDOR" to TagField.ORIGINAL_DATE,
        "TORY" to TagField.ORIGINAL_DATE,
        "TBPM" to TagField.BPM,
        "TKEY" to TagField.KEY,
        "TSRC" to TagField.ISRC,
        "TCOP" to TagField.COPYRIGHT,
        "TENC" to TagField.ENCODED_BY,
        "TSSE" to TagField.ENCODER,
        "TPUB" to TagField.PUBLISHER,
        "TLAN" to TagField.LANGUAGE,
        "TMOO" to TagField.MOOD,
        "TMED" to TagField.MEDIA,
        "TCMP" to TagField.COMPILATION,
        "TSOT" to TagField.SORT_TITLE,
        "TSOP" to TagField.SORT_ARTIST,
        "TSOA" to TagField.SORT_ALBUM,
        "TSO2" to TagField.SORT_ALBUM_ARTIST,
        "TSOC" to TagField.SORT_COMPOSER,
        "GRP1" to TagField.GROUPING,
        "COMM" to TagField.COMMENT,
        "USLT" to TagField.LYRICS,
        "WOAR" to TagField.URL,
    )

    /** ID3v2.2 frame identifiers, which are the same fields three letters wide. */
    private val ID3V2_2: Map<String, TagField> = mapOf(
        "TT2" to TagField.TITLE,
        "TT1" to TagField.GROUPING,
        "TT3" to TagField.DESCRIPTION,
        "TP1" to TagField.ARTIST,
        "TP2" to TagField.ALBUM_ARTIST,
        "TP3" to TagField.CONDUCTOR,
        "TP4" to TagField.REMIXER,
        "TAL" to TagField.ALBUM,
        "TCM" to TagField.COMPOSER,
        "TXT" to TagField.LYRICIST,
        "TCO" to TagField.GENRE,
        "TYE" to TagField.DATE,
        "TOR" to TagField.ORIGINAL_DATE,
        "TBP" to TagField.BPM,
        "TKE" to TagField.KEY,
        "TRC" to TagField.ISRC,
        "TCR" to TagField.COPYRIGHT,
        "TEN" to TagField.ENCODED_BY,
        "TSS" to TagField.ENCODER,
        "TPB" to TagField.PUBLISHER,
        "TLA" to TagField.LANGUAGE,
        "TMT" to TagField.MEDIA,
        "TCP" to TagField.COMPILATION,
        "COM" to TagField.COMMENT,
        "ULT" to TagField.LYRICS,
        "WAR" to TagField.URL,
    )

    /** Frames whose value is a number and a total joined by a slash. */
    private val NUMBER_AND_TOTAL: Map<String, Pair<TagField, TagField>> = mapOf(
        "TRCK" to (TagField.TRACK_NUMBER to TagField.TRACK_TOTAL),
        "TRK" to (TagField.TRACK_NUMBER to TagField.TRACK_TOTAL),
        "TPOS" to (TagField.DISC_NUMBER to TagField.DISC_TOTAL),
        "TPA" to (TagField.DISC_NUMBER to TagField.DISC_TOTAL),
    )

    /** Vorbis comment keys, which are also what FLAC uses. */
    private val VORBIS: Map<String, TagField> = mapOf(
        "TITLE" to TagField.TITLE,
        "ARTIST" to TagField.ARTIST,
        "ALBUM" to TagField.ALBUM,
        "ALBUMARTIST" to TagField.ALBUM_ARTIST,
        "ALBUM ARTIST" to TagField.ALBUM_ARTIST,
        "TRACKNUMBER" to TagField.TRACK_NUMBER,
        "TRACKTOTAL" to TagField.TRACK_TOTAL,
        "TOTALTRACKS" to TagField.TRACK_TOTAL,
        "DISCNUMBER" to TagField.DISC_NUMBER,
        "DISCTOTAL" to TagField.DISC_TOTAL,
        "TOTALDISCS" to TagField.DISC_TOTAL,
        "DATE" to TagField.DATE,
        "YEAR" to TagField.DATE,
        "ORIGINALDATE" to TagField.ORIGINAL_DATE,
        "RELEASEDATE" to TagField.RELEASE_DATE,
        "GENRE" to TagField.GENRE,
        "COMPOSER" to TagField.COMPOSER,
        "PERFORMER" to TagField.PERFORMER,
        "CONDUCTOR" to TagField.CONDUCTOR,
        "LYRICIST" to TagField.LYRICIST,
        "REMIXER" to TagField.REMIXER,
        "COMMENT" to TagField.COMMENT,
        "DESCRIPTION" to TagField.DESCRIPTION,
        "LYRICS" to TagField.LYRICS,
        "UNSYNCEDLYRICS" to TagField.LYRICS,
        "WEBSITE" to TagField.URL,
        "URL" to TagField.URL,
        "GROUPING" to TagField.GROUPING,
        "BPM" to TagField.BPM,
        "KEY" to TagField.KEY,
        "INITIALKEY" to TagField.KEY,
        "ISRC" to TagField.ISRC,
        "COPYRIGHT" to TagField.COPYRIGHT,
        "ENCODER" to TagField.ENCODER,
        "ENCODEDBY" to TagField.ENCODED_BY,
        "ENCODED-BY" to TagField.ENCODED_BY,
        "ENCODED_BY" to TagField.ENCODED_BY,
        "ENCODING" to TagField.ENCODER,
        "ENCODERSETTINGS" to TagField.ENCODING_SETTINGS,
        "PUBLISHER" to TagField.PUBLISHER,
        "LABEL" to TagField.PUBLISHER,
        "ORGANIZATION" to TagField.PUBLISHER,
        "LANGUAGE" to TagField.LANGUAGE,
        "MOOD" to TagField.MOOD,
        "MEDIA" to TagField.MEDIA,
        "CATALOGNUMBER" to TagField.CATALOG_NUMBER,
        "BARCODE" to TagField.BARCODE,
        "COMPILATION" to TagField.COMPILATION,
        "TITLESORT" to TagField.SORT_TITLE,
        "ARTISTSORT" to TagField.SORT_ARTIST,
        "ALBUMSORT" to TagField.SORT_ALBUM,
        "ALBUMARTISTSORT" to TagField.SORT_ALBUM_ARTIST,
        "COMPOSERSORT" to TagField.SORT_COMPOSER,
        "MUSICBRAINZ_TRACKID" to TagField.MUSICBRAINZ_RECORDING_ID,
        "MUSICBRAINZ_RELEASETRACKID" to TagField.MUSICBRAINZ_TRACK_ID,
        "MUSICBRAINZ_ALBUMID" to TagField.MUSICBRAINZ_RELEASE_ID,
        "MUSICBRAINZ_RELEASEGROUPID" to TagField.MUSICBRAINZ_RELEASE_GROUP_ID,
        "MUSICBRAINZ_ARTISTID" to TagField.MUSICBRAINZ_ARTIST_ID,
        "MUSICBRAINZ_ALBUMARTISTID" to TagField.MUSICBRAINZ_RELEASE_ARTIST_ID,
        "ACOUSTID_ID" to TagField.ACOUSTID_ID,
        "ISRC" to TagField.ISRC,
    )

    /** APE keys, which are written as words with capitals. */
    private val APE: Map<String, TagField> = VORBIS + mapOf(
        "TITLE" to TagField.TITLE,
        "ARTIST" to TagField.ARTIST,
        "ALBUM" to TagField.ALBUM,
        "ALBUM ARTIST" to TagField.ALBUM_ARTIST,
        "ALBUMARTIST" to TagField.ALBUM_ARTIST,
        "YEAR" to TagField.DATE,
        "GENRE" to TagField.GENRE,
        "COMPOSER" to TagField.COMPOSER,
        "CONDUCTOR" to TagField.CONDUCTOR,
        "PERFORMER" to TagField.PERFORMER,
        "COMMENT" to TagField.COMMENT,
        "COPYRIGHT" to TagField.COPYRIGHT,
        "PUBLISHER" to TagField.PUBLISHER,
        "LABEL" to TagField.PUBLISHER,
        "ISRC" to TagField.ISRC,
        "LANGUAGE" to TagField.LANGUAGE,
        "MEDIA" to TagField.MEDIA,
        "CATALOG" to TagField.CATALOG_NUMBER,
        "CATALOGNUMBER" to TagField.CATALOG_NUMBER,
        "BARCODE" to TagField.BARCODE,
        "COMPILATION" to TagField.COMPILATION,
        "LYRICS" to TagField.LYRICS,
        "BPM" to TagField.BPM,
        "WEBLINK" to TagField.URL,
        "TOOL NAME" to TagField.ENCODER,
        "TOOL VERSION" to TagField.ENCODING_SETTINGS,
    )

    /** The `LIST INFO` keys of a RIFF file. */
    private val RIFF_INFO: Map<String, TagField> = mapOf(
        "INAM" to TagField.TITLE,
        "IART" to TagField.ARTIST,
        "IPRD" to TagField.ALBUM,
        "ICRD" to TagField.DATE,
        "IGNR" to TagField.GENRE,
        "ICMT" to TagField.COMMENT,
        "ITRK" to TagField.TRACK_NUMBER,
        "IPRT" to TagField.TRACK_NUMBER,
        "ICOP" to TagField.COPYRIGHT,
        "ISFT" to TagField.ENCODER,
        "IENC" to TagField.ENCODED_BY,
        "ITCH" to TagField.ENCODED_BY,
        "IWRI" to TagField.LYRICIST,
        "IMUS" to TagField.COMPOSER,
        "IENG" to TagField.PERFORMER,
        "ILNG" to TagField.LANGUAGE,
        "ISRF" to TagField.MEDIA,
        "ISBJ" to TagField.DESCRIPTION,
    )

    /** The text chunks of an AIFF file. */
    private val AIFF_TEXT: Map<String, TagField> = mapOf(
        "NAME" to TagField.TITLE,
        "AUTH" to TagField.ARTIST,
        "(c) " to TagField.COPYRIGHT,
        "ANNO" to TagField.COMMENT,
    )

    /** ID3v1's four named fields, which the reader emits under these keys. */
    private val ID3V1: Map<String, TagField> = mapOf(
        "TITLE" to TagField.TITLE,
        "ARTIST" to TagField.ARTIST,
        "ALBUM" to TagField.ALBUM,
        "YEAR" to TagField.DATE,
        "COMMENT" to TagField.COMMENT,
        "GENRE" to TagField.GENRE,
        "TRACK" to TagField.TRACK_NUMBER,
    )

    /** Descriptions of `TXXX` frames that carry a field with no frame of its own. */
    private val USER_TEXT: Map<String, TagField> = mapOf(
        "ENCODERSETTINGS" to TagField.ENCODING_SETTINGS,
        "ENCODING_SETTINGS" to TagField.ENCODING_SETTINGS,
        "MUSICBRAINZ_RECORDING_ID" to TagField.MUSICBRAINZ_RECORDING_ID,
        "MUSICBRAINZ ARTIST ID" to TagField.MUSICBRAINZ_ARTIST_ID,
        "MUSICBRAINZ ALBUM ID" to TagField.MUSICBRAINZ_RELEASE_ID,
        "MUSICBRAINZ ALBUM ARTIST ID" to TagField.MUSICBRAINZ_RELEASE_ARTIST_ID,
        "MUSICBRAINZ RELEASE GROUP ID" to TagField.MUSICBRAINZ_RELEASE_GROUP_ID,
        "MUSICBRAINZ RELEASE TRACK ID" to TagField.MUSICBRAINZ_TRACK_ID,
        "ACOUSTID ID" to TagField.ACOUSTID_ID,
        "CATALOGNUMBER" to TagField.CATALOG_NUMBER,
        "BARCODE" to TagField.BARCODE,
        "LABEL" to TagField.PUBLISHER,
        "ALBUMARTISTSORT" to TagField.SORT_ALBUM_ARTIST,
        "COMPILATION" to TagField.COMPILATION,
        "ORIGINALDATE" to TagField.ORIGINAL_DATE,
        "RELEASEDATE" to TagField.RELEASE_DATE,
        "MOOD" to TagField.MOOD,
        "ISRC" to TagField.ISRC,
    )

    /**
     * The field an entry carries, or null when it is one AudioKodec does not
     * model.
     *
     * An unmapped entry is not lost: it stays in the raw view under its own
     * key, which is what a caller reaching for something unusual reads.
     */
    fun fieldFor(format: TagFormat, entry: RawTagEntry): TagField? {
        val key = entry.key
        if ((format == TagFormat.ID3V2_2 || format == TagFormat.ID3V2_3 || format == TagFormat.ID3V2_4) &&
            isReservedComment(entry)
        ) return null
        return when (format) {
            TagFormat.ID3V2_2 -> {
                when (key) {
                    "TXX" -> userTextField(entry)
                    "UFI" -> uniqueFileField(entry)
                    else -> ID3V2_2[key]
                }
            }
            TagFormat.ID3V2_3, TagFormat.ID3V2_4 -> {
                when (key) {
                    "TXXX" -> userTextField(entry)
                    "UFID" -> uniqueFileField(entry)
                    else -> ID3V2_4[key]
                }
            }
            TagFormat.VORBIS_COMMENT -> VORBIS[key.uppercase()]
            TagFormat.APEV1, TagFormat.APEV2 -> APE[key.uppercase()]
            TagFormat.RIFF_INFO -> RIFF_INFO[key]
            TagFormat.AIFF_TEXT -> AIFF_TEXT[key]
            TagFormat.ID3V1 -> ID3V1[key]
            TagFormat.MP4_ILST -> {
                if (entry.isOpaqueMp4Item) return null
                val name = mp4FreeformName(key)
                if (name != null) mp4FreeformField(name) else MP4_ILST[key]
            }
            else -> null
        }
    }

    /** iTunes stores playback data in comments under this reserved family. */
    fun isReservedComment(entry: RawTagEntry): Boolean =
        (entry.key == "COM" || entry.key == "COMM") &&
            entry.description?.startsWith("iTun", ignoreCase = true) == true

    /** MP4 metadata atom names. */
    private val MP4_ILST: Map<String, TagField> = mapOf(
        "©nam" to TagField.TITLE,
        "©ART" to TagField.ARTIST,
        "©alb" to TagField.ALBUM,
        "aART" to TagField.ALBUM_ARTIST,
        "©day" to TagField.DATE,
        "©gen" to TagField.GENRE,
        "gnre" to TagField.GENRE,
        "©cmt" to TagField.COMMENT,
        "©wrt" to TagField.COMPOSER,
        "©lyr" to TagField.LYRICS,
        "©too" to TagField.ENCODER,
        "©grp" to TagField.GROUPING,
        "cprt" to TagField.COPYRIGHT,
        "tmpo" to TagField.BPM,
        "cpil" to TagField.COMPILATION,
        "soal" to TagField.SORT_ALBUM,
        "soar" to TagField.SORT_ARTIST,
        "soaa" to TagField.SORT_ALBUM_ARTIST,
        "sonm" to TagField.SORT_TITLE,
        "soco" to TagField.SORT_COMPOSER,
        "desc" to TagField.DESCRIPTION,
        "ldes" to TagField.DESCRIPTION,
        "catg" to TagField.GROUPING,
        "©con" to TagField.CONDUCTOR,
    )

    /** The mean every conventional freeform MP4 item uses. */
    const val MP4_FREEFORM_MEAN: String = "com.apple.iTunes"

    /**
     * The name of a freeform MP4 item, from the key the reader gives it, or
     * null when [key] is not a freeform item.
     *
     * A freeform item is stored as a `----` atom holding a `mean` and a
     * `name`, and is read under the key `----:mean:name`. The mean is a
     * reverse domain name and holds no colon; the name may.
     */
    fun mp4FreeformName(key: String): String? {
        if (!key.startsWith("----:")) return null
        val separator = key.indexOf(':', 5)
        if (separator < 0) return null
        return key.substring(separator + 1)
    }

    /** The mean of a freeform MP4 item key, or null when it is not one. */
    fun mp4FreeformMean(key: String): String? {
        if (!key.startsWith("----:")) return null
        val separator = key.indexOf(':', 5)
        if (separator < 0) return null
        return key.substring(5, separator)
    }

    /**
     * The field a freeform MP4 item carries, from its name.
     *
     * Taggers name these after the Vorbis comment or the ID3 `TXXX`
     * description of the same field, and MusicBrainz Picard writes its
     * recording identifier as `MusicBrainz Track Id`, which in a `TXXX`
     * would be a different identifier.
     */
    private fun mp4FreeformField(name: String): TagField? {
        val upper = name.trim().uppercase()
        return MP4_FREEFORM[upper] ?: USER_TEXT[upper] ?: VORBIS[upper]
    }

    private val MP4_FREEFORM: Map<String, TagField> = mapOf(
        "MUSICBRAINZ TRACK ID" to TagField.MUSICBRAINZ_RECORDING_ID,
        "MUSICBRAINZ RELEASE TRACK ID" to TagField.MUSICBRAINZ_TRACK_ID,
    )

    /**
     * The standard MP4 atom that holds [field] as text, or null when the
     * field has none and is written as a freeform item.
     *
     * The numeric atoms, `trkn`, `disk`, `tmpo` and `cpil`, are not here:
     * they hold binary integers, so the writer decides whether a value fits.
     */
    fun mp4AtomFor(field: TagField): String? = when (field) {
        TagField.TITLE -> "©nam"
        TagField.ARTIST -> "©ART"
        TagField.ALBUM -> "©alb"
        TagField.ALBUM_ARTIST -> "aART"
        TagField.DATE -> "©day"
        TagField.GENRE -> "©gen"
        TagField.COMMENT -> "©cmt"
        TagField.COMPOSER -> "©wrt"
        TagField.LYRICS -> "©lyr"
        TagField.ENCODER -> "©too"
        TagField.GROUPING -> "©grp"
        TagField.COPYRIGHT -> "cprt"
        TagField.DESCRIPTION -> "desc"
        TagField.SORT_TITLE -> "sonm"
        TagField.SORT_ARTIST -> "soar"
        TagField.SORT_ALBUM -> "soal"
        TagField.SORT_ALBUM_ARTIST -> "soaa"
        TagField.SORT_COMPOSER -> "soco"
        else -> null
    }

    /** The freeform item name that holds [field] when no standard atom does. */
    fun mp4FreeformNameFor(field: TagField): String = when (field) {
        TagField.MUSICBRAINZ_RECORDING_ID -> "MusicBrainz Track Id"
        TagField.MUSICBRAINZ_TRACK_ID -> "MusicBrainz Release Track Id"
        TagField.MUSICBRAINZ_RELEASE_ID -> "MusicBrainz Album Id"
        TagField.MUSICBRAINZ_RELEASE_GROUP_ID -> "MusicBrainz Release Group Id"
        TagField.MUSICBRAINZ_ARTIST_ID -> "MusicBrainz Artist Id"
        TagField.MUSICBRAINZ_RELEASE_ARTIST_ID -> "MusicBrainz Album Artist Id"
        TagField.ACOUSTID_ID -> "Acoustid Id"
        TagField.PUBLISHER -> "LABEL"
        TagField.KEY -> "initialkey"
        else -> vorbisKeyFor(field) ?: field.name
    }

    /**
     * The field a `TXXX` frame carries, from its description.
     *
     * Two conventions are in use. Some programs write a fixed set of
     * descriptions for the fields ID3v2 has no frame for, which is the table
     * above. Others, ffmpeg among them, write any metadata key they were given
     * as a `TXXX` whose description is the key, using the same names Vorbis
     * comments use. A comment written that way is a `TXXX` called `comment`
     * rather than a `COMM` frame, and a reader that only knew the first
     * convention would silently lose it.
     */
    private fun userTextField(entry: RawTagEntry): TagField? {
        val description = entry.description?.trim()?.uppercase() ?: return null
        return USER_TEXT[description] ?: VORBIS[description]
    }

    /**
     * The pair of fields a value like `3/12` fills, or null when the key is
     * not one of those.
     */
    fun numberAndTotalFor(format: TagFormat, key: String): Pair<TagField, TagField>? {
        return when (format) {
            TagFormat.ID3V2_2, TagFormat.ID3V2_3, TagFormat.ID3V2_4 -> NUMBER_AND_TOTAL[key]
            TagFormat.APEV1, TagFormat.APEV2 -> when (key.uppercase()) {
                "TRACK" -> TagField.TRACK_NUMBER to TagField.TRACK_TOTAL
                "DISC" -> TagField.DISC_NUMBER to TagField.DISC_TOTAL
                else -> null
            }
            TagFormat.VORBIS_COMMENT -> when (key.uppercase()) {
                "TRACKNUMBER" -> TagField.TRACK_NUMBER to TagField.TRACK_TOTAL
                "DISCNUMBER" -> TagField.DISC_NUMBER to TagField.DISC_TOTAL
                else -> null
            }
            TagFormat.MP4_ILST -> when (key) {
                "trkn" -> TagField.TRACK_NUMBER to TagField.TRACK_TOTAL
                "disk" -> TagField.DISC_NUMBER to TagField.DISC_TOTAL
                else -> null
            }
            else -> null
        }
    }

    /**
     * The ID3v2.3 or 2.4 frame that holds [field], or null when the field has
     * no frame of its own and belongs in a `TXXX`.
     *
     * This cannot be the read table turned around. Several native spellings
     * may fill one field. The primary date uses `TDRC` in version 2.4 and
     * `TYER` in version 2.3; the explicit release date uses its own `TDRL`
     * frame or a `TXXX:RELEASEDATE` value in version 2.3.
     */
    fun id3FrameFor(field: TagField, major: Int): String? = when (field) {
        TagField.TITLE -> "TIT2"
        TagField.ARTIST -> "TPE1"
        TagField.ALBUM -> "TALB"
        TagField.ALBUM_ARTIST -> "TPE2"
        TagField.TRACK_NUMBER -> "TRCK"
        TagField.DISC_NUMBER -> "TPOS"
        TagField.DATE -> if (major >= 4) "TDRC" else "TYER"
        TagField.ORIGINAL_DATE -> if (major >= 4) "TDOR" else "TORY"
        TagField.RELEASE_DATE -> if (major >= 4) "TDRL" else null
        TagField.GENRE -> "TCON"
        TagField.COMPOSER -> "TCOM"
        TagField.CONDUCTOR -> "TPE3"
        TagField.REMIXER -> "TPE4"
        TagField.LYRICIST -> "TEXT"
        TagField.GROUPING -> "TIT1"
        TagField.DESCRIPTION -> "TIT3"
        TagField.BPM -> "TBPM"
        TagField.KEY -> "TKEY"
        TagField.ISRC -> "TSRC"
        TagField.COPYRIGHT -> "TCOP"
        TagField.ENCODER -> "TSSE"
        TagField.ENCODED_BY -> "TENC"
        TagField.PUBLISHER -> "TPUB"
        TagField.LANGUAGE -> "TLAN"
        TagField.MEDIA -> "TMED"
        TagField.COMPILATION -> "TCMP"
        TagField.SORT_TITLE -> "TSOT"
        TagField.SORT_ARTIST -> "TSOP"
        TagField.SORT_ALBUM -> "TSOA"
        TagField.SORT_ALBUM_ARTIST -> "TSO2"
        TagField.SORT_COMPOSER -> "TSOC"
        TagField.COMMENT -> "COMM"
        TagField.LYRICS -> "USLT"
        TagField.URL -> "WOAR"
        // Version 2.4 added a mood frame. In 2.3 it goes in a TXXX.
        TagField.MOOD -> if (major >= 4) "TMOO" else null
        // Everything else has no frame of its own and is written as a TXXX,
        // or is the total half of a pair and is written into the slash form.
        else -> null
    }

    /** The `TXXX` description that holds [field], or null. */
    fun userTextDescriptionFor(field: TagField): String? {
        when (field) {
            TagField.RELEASE_DATE -> return "RELEASEDATE"
            TagField.MUSICBRAINZ_ARTIST_ID -> return "MusicBrainz Artist Id"
            TagField.MUSICBRAINZ_RELEASE_ID -> return "MusicBrainz Album Id"
            TagField.MUSICBRAINZ_RELEASE_ARTIST_ID -> return "MusicBrainz Album Artist Id"
            TagField.MUSICBRAINZ_RELEASE_GROUP_ID -> return "MusicBrainz Release Group Id"
            TagField.MUSICBRAINZ_TRACK_ID -> return "MusicBrainz Release Track Id"
            else -> Unit
        }
        for ((description, mapped) in USER_TEXT) {
            if (mapped == field) return description
        }
        return null
    }

    /** The preferred APE key for [field], or null when APE has no convention. */
    fun apeKeyFor(field: TagField): String? {
        if (field == TagField.URL) return "Weblink"
        for ((key, mapped) in APE) {
            if (mapped == field) return key
        }
        return null
    }

    /** The Vorbis comment key for [field], or null. */
    fun vorbisKeyFor(field: TagField): String? = when (field) {
        TagField.ALBUM_ARTIST -> "ALBUMARTIST"
        TagField.TRACK_NUMBER -> "TRACKNUMBER"
        TagField.TRACK_TOTAL -> "TRACKTOTAL"
        TagField.DISC_NUMBER -> "DISCNUMBER"
        TagField.DISC_TOTAL -> "DISCTOTAL"
        TagField.ENCODER -> "ENCODER"
        TagField.ENCODED_BY -> "ENCODEDBY"
        TagField.PUBLISHER -> "PUBLISHER"
        TagField.SORT_TITLE -> "TITLESORT"
        TagField.SORT_ARTIST -> "ARTISTSORT"
        TagField.SORT_ALBUM -> "ALBUMSORT"
        TagField.SORT_ALBUM_ARTIST -> "ALBUMARTISTSORT"
        TagField.SORT_COMPOSER -> "COMPOSERSORT"
        TagField.MUSICBRAINZ_RECORDING_ID -> "MUSICBRAINZ_TRACKID"
        TagField.MUSICBRAINZ_TRACK_ID -> "MUSICBRAINZ_RELEASETRACKID"
        TagField.MUSICBRAINZ_RELEASE_ID -> "MUSICBRAINZ_ALBUMID"
        TagField.MUSICBRAINZ_RELEASE_GROUP_ID -> "MUSICBRAINZ_RELEASEGROUPID"
        TagField.MUSICBRAINZ_ARTIST_ID -> "MUSICBRAINZ_ARTISTID"
        TagField.MUSICBRAINZ_RELEASE_ARTIST_ID -> "MUSICBRAINZ_ALBUMARTISTID"
        TagField.ACOUSTID_ID -> "ACOUSTID_ID"
        TagField.ORIGINAL_DATE -> "ORIGINALDATE"
        TagField.RELEASE_DATE -> "RELEASEDATE"
        TagField.CATALOG_NUMBER -> "CATALOGNUMBER"
        TagField.ENCODING_SETTINGS -> "ENCODERSETTINGS"
        TagField.URL -> "WEBSITE"
        else -> field.name
    }

    /**
     * The RIFF `INFO` key for [field], or null when RIFF has no place for it.
     *
     * Written out rather than derived from the read table, because two keys
     * read as a track number and only one of them should be written.
     */
    fun riffInfoKeyFor(field: TagField): String? = when (field) {
        TagField.TITLE -> "INAM"
        TagField.ARTIST -> "IART"
        TagField.ALBUM -> "IPRD"
        TagField.DATE -> "ICRD"
        TagField.GENRE -> "IGNR"
        TagField.COMMENT -> "ICMT"
        TagField.TRACK_NUMBER -> "ITRK"
        TagField.COPYRIGHT -> "ICOP"
        TagField.ENCODER -> "ISFT"
        TagField.ENCODED_BY -> "IENC"
        TagField.LYRICIST -> "IWRI"
        TagField.COMPOSER -> "IMUS"
        TagField.PERFORMER -> "IENG"
        TagField.LANGUAGE -> "ILNG"
        TagField.MEDIA -> "ISRF"
        TagField.DESCRIPTION -> "ISBJ"
        else -> null
    }

    /** The AIFF text chunk for [field], or null. */
    fun aiffChunkFor(field: TagField): String? = when (field) {
        TagField.TITLE -> "NAME"
        TagField.ARTIST -> "AUTH"
        TagField.COPYRIGHT -> "(c) "
        TagField.COMMENT -> "ANNO"
        else -> null
    }
}

package io.github.yuroyami.audiokodec.tag

/**
 * A piece of information about a recording, named in a way that does not
 * depend on the tag format it came from.
 *
 * The same fact is spelled differently everywhere: the album artist is
 * `TPE2` in ID3v2, `ALBUMARTIST` in a Vorbis comment, `aART` in an MP4 file
 * and `Album Artist` in an APE tag. Reading gives you this list, and the
 * original spelling stays available through [TagBundle.raw].
 *
 * A field can hold more than one value. Files with two artists or three
 * genres are common, and flattening them into one string loses information
 * that some formats keep.
 */
public enum class TagField {
    /** Track title. */
    TITLE,

    /** Performing artist. */
    ARTIST,

    /** Album title. */
    ALBUM,

    /** Artist the album is filed under, which a compilation needs. */
    ALBUM_ARTIST,

    /** Track number within the disc. */
    TRACK_NUMBER,

    /** How many tracks the disc holds. */
    TRACK_TOTAL,

    /** Disc number within the set. */
    DISC_NUMBER,

    /** How many discs the set holds. */
    DISC_TOTAL,

    /**
     * Release date, as complete as the file states it.
     *
     * ID3v2.4 and Vorbis comments allow a full date, ID3v2.3 stores a
     * four-digit year in `TYER`, and both end up here in the form the file
     * used.
     */
    DATE,

    /** Date the recording was made, when it differs from the release date. */
    ORIGINAL_DATE,

    /**
     * Genre names, including separate ID3 references and refinements.
     *
     * Use [TagBundle.all] to retain every genre. The first-value accessor
     * returns the first reference when the file also states a refinement.
     */
    GENRE,

    /** Composer. */
    COMPOSER,

    /** Performer, where the format separates this from the artist. */
    PERFORMER,

    /** Conductor. */
    CONDUCTOR,

    /** Writer of the words. */
    LYRICIST,

    /** Remixer or arranger. */
    REMIXER,

    /** Free comment. Surrounding whitespace is retained. */
    COMMENT,

    /** Unsynchronised lyrics. Surrounding whitespace and line endings are retained. */
    LYRICS,

    /** Work or content group the track belongs to. */
    GROUPING,

    /** Beats per minute. */
    BPM,

    /** Musical key. */
    KEY,

    /** International Standard Recording Code. */
    ISRC,

    /** Copyright statement. */
    COPYRIGHT,

    /** Program that produced the file. */
    ENCODER,

    /** Settings that program used. */
    ENCODING_SETTINGS,

    /** Publisher or record label. */
    PUBLISHER,

    /** Language of the words. */
    LANGUAGE,

    /** Mood. */
    MOOD,

    /** Physical medium the recording came from. */
    MEDIA,

    /** Catalogue number. */
    CATALOG_NUMBER,

    /** Barcode. */
    BARCODE,

    /** Set when the album is a compilation of different artists. */
    COMPILATION,

    /** Title to sort by, when it differs from the title. */
    SORT_TITLE,

    /** Artist to sort by. */
    SORT_ARTIST,

    /** Album to sort by. */
    SORT_ALBUM,

    /** Album artist to sort by. */
    SORT_ALBUM_ARTIST,

    /** Composer to sort by. */
    SORT_COMPOSER,

    /**
     * MusicBrainz recording identifier.
     *
     * ID3 stores one identifier in `UFID` with owner `http://musicbrainz.org`.
     * Writing ID3 requires 1 to 64 ASCII bytes without whitespace or controls.
     * Several distinct identifiers cannot be represented under that owner.
     */
    MUSICBRAINZ_RECORDING_ID,

    /** MusicBrainz track identifier, which is release specific. */
    MUSICBRAINZ_TRACK_ID,

    /** MusicBrainz release identifier. */
    MUSICBRAINZ_RELEASE_ID,

    /** MusicBrainz release group identifier. */
    MUSICBRAINZ_RELEASE_GROUP_ID,

    /** MusicBrainz artist identifier. */
    MUSICBRAINZ_ARTIST_ID,

    /** MusicBrainz release artist identifier. */
    MUSICBRAINZ_RELEASE_ARTIST_ID,

    /** AcoustID fingerprint identifier. */
    ACOUSTID_ID,

    /** Website of the artist or the release. */
    URL,

    /** Podcast or audiobook description. */
    DESCRIPTION,
    ;

    /**
     * True when a field is a number that should be read as one.
     *
     * Files write these as text anyway, and some write `3/12` where the total
     * belongs in its own field, so a reader has to know which is which.
     */
    public val isNumeric: Boolean
        get() = this == TRACK_NUMBER || this == TRACK_TOTAL ||
            this == DISC_NUMBER || this == DISC_TOTAL || this == BPM
}

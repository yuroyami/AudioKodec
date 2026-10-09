package io.github.yuroyami.audiokodec.tag

/**
 * How to write tags back into a file.
 *
 * The defaults suit almost every caller: ID3v2.4 with a kilobyte of padding,
 * an existing ID3v1 block kept in step, and the container's own metadata
 * chunks used where it has them.
 */
public class TagWriteOptions(
    /**
     * Which ID3v2 version to write, 3 or 4.
     *
     * Version 4 is the current one and handles UTF-8, multiple values in a
     * frame and full dates. Version 3 is worth choosing when a file has to
     * play on hardware that predates 2000, which never learned version 4.
     * Version 3 uses slash lists for artists, composers and lyricists.
     * Genres use known references followed by at most one free-text
     * refinement. Other orders or several refinements require version 4.
     * Other text fields and user-text frames require one distinct value.
     * A literal slash in a slash-list value or an unsupported list raises
     * [io.github.yuroyami.audiokodec.UnsupportedFeatureException]. Use version 4
     * to preserve those values separately.
     */
    public val id3Version: Int = 4,

    /**
     * Bytes of empty space to leave after the frames.
     *
     * Padding lets the next program that edits the tags write a longer value
     * without moving the audio, which turns a rewrite of the whole file into
     * a write of a few hundred bytes. Zero is right when the file is being
     * written once and never edited.
     */
    public val padding: Int = 1024,

    /**
     * Whether to keep an ID3v1 block that the file already has.
     *
     * AudioKodec never creates one. A file that has one gets it updated so the
     * two tags agree, unless this is false, in which case it is dropped.
     */
    public val keepId3v1: Boolean = true,

    /**
     * Which tag formats to write, or null to use what suits the container.
     *
     * The default writes an ID3v2 block for bare codec streams, a `LIST INFO`
     * chunk for RIFF files, and text chunks for AIFF. When those native
     * chunks cannot retain the requested metadata, it also writes ID3.
     * Undeclared RIFF INFO uses UTF-8; an existing supported CSET controls
     * its encoding during edits. AIFF native text uses ASCII. Unicode beyond
     * those limits uses ID3 or is refused by a native-only selection.
     * An existing external ID3 block is reused; otherwise it adds a native
     * ID3 chunk. Native-only selections name the unrepresentable fields,
     * lists, pictures, chapters or ReplayGain and refuse before output.
     * FLAC and Ogg use Vorbis comments and pictures. Their chapter output,
     * and APE chapter output, requires an existing or selected ID3 carrier.
     * MP4 uses its item list, [TagFormat.MP4_ILST], with front-cover art and
     * changed chapters written as a Nero `chpl` list; an empty set removes
     * the item list.
     * A non-null set selects every writable format to keep. For example, use
     * `setOf(RIFF_INFO, ID3V2_4)` to put both kinds in a WAV file, or an empty
     * set to remove its native text and ID3 chunks. Broadcast Wave is opt-in:
     * include [TagFormat.BWF_BEXT] to add or rewrite its raw fields, or remove
     * it when the bundle supplies no BWF block. Other selections preserve an
     * existing `bext` chunk byte-for-byte. Fixed text is ASCII and truncated
     * to its field width; coding history uses CRLF endings. Lyrics3 is preserved.
     *
     * When the set names ID3v2.3 or ID3v2.4, that choice takes precedence over
     * [id3Version].
     */
    formats: Set<TagFormat>? = null,
) {
    /** Which tag formats to write, or null to use what suits the container. */
    public val formats: Set<TagFormat>? = formats?.toSet()

    init {
        require(id3Version == 3 || id3Version == 4) {
            "AudioKodec writes ID3v2.3 or ID3v2.4, not 2.$id3Version"
        }
        require(padding >= 0) { "padding must not be negative, was $padding" }
        require(
            formats == null ||
                TagFormat.ID3V2_3 !in formats ||
                TagFormat.ID3V2_4 !in formats,
        ) {
            "formats cannot request both ID3v2.3 and ID3v2.4"
        }
    }

    public companion object {
        /** The defaults. */
        public val DEFAULT: TagWriteOptions = TagWriteOptions()
    }
}

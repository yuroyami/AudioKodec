package io.github.yuroyami.audiokodecs.tag

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
     * AudioKodecs never creates one. A file that has one gets it updated so the
     * two tags agree, unless this is false, in which case it is dropped.
     */
    public val keepId3v1: Boolean = true,

    /**
     * Which tag formats to write, or null to use what suits the container.
     *
     * The default writes an ID3v2 block for bare codec streams, a `LIST INFO`
     * chunk for RIFF files, and text chunks for AIFF. A non-null set is the
     * complete selection of writable formats. For example, use
     * `setOf(RIFF_INFO, ID3V2_4)` to put both kinds in a WAV file, or an empty
     * set to remove its writable tag chunks. Read-only blocks such as `bext`
     * and Lyrics3 are preserved.
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
            "AudioKodecs writes ID3v2.3 or ID3v2.4, not 2.$id3Version"
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

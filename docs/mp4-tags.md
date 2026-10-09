# MP4 metadata

An `.m4a`, `.m4b` or `.mp4` file keeps its tags in an item list inside the
`moov` box, at `moov/udta/meta/ilst`. AudioKodec reads that list into the same
`TagField` set every other format uses, and writes it back without touching the
audio.

```kotlin
val tags = AudioKodec.readTags(m4aBytes)
println(tags[TagField.TITLE])
val edited = tags.edit {
    set(TagField.TITLE, "New title")
    addPicture(TagPicture(PictureType.FRONT_COVER, "image/jpeg", bytes = cover))
}
val updated = AudioKodec.writeTags(m4aBytes, edited)
```

## Where each field goes

Fields with an atom of their own use it:

| Field | Atom | Field | Atom |
|---|---|---|---|
| `TITLE` | `©nam` | `GROUPING` | `©grp` |
| `ARTIST` | `©ART` | `COPYRIGHT` | `cprt` |
| `ALBUM` | `©alb` | `DESCRIPTION` | `desc` |
| `ALBUM_ARTIST` | `aART` | `SORT_TITLE` | `sonm` |
| `DATE` | `©day` | `SORT_ARTIST` | `soar` |
| `GENRE` | `©gen` | `SORT_ALBUM` | `soal` |
| `COMMENT` | `©cmt` | `SORT_ALBUM_ARTIST` | `soaa` |
| `COMPOSER` | `©wrt` | `SORT_COMPOSER` | `soco` |
| `LYRICS` | `©lyr` | `ENCODER` | `©too` |

`TRACK_NUMBER` and `TRACK_TOTAL` share `trkn`, and `DISC_NUMBER` and
`DISC_TOTAL` share `disk`. `BPM` uses `tmpo` and `COMPILATION` uses `cpil`.
These four atoms hold binary integers. A value they cannot hold exactly, such
as track `A1`, BPM `120.5`, a zero position or several values, goes into a
freeform item instead of being changed to fit.

Every other field goes into a freeform `----` item with the mean
`com.apple.iTunes`, named the way MusicBrainz Picard and foobar2000 name them:
`MusicBrainz Track Id` for the recording identifier, `MusicBrainz Album Id`,
`LABEL` for the publisher, `initialkey`, `ISRC`, `CATALOGNUMBER`, `BARCODE`,
and the Vorbis comment name for the rest. ReplayGain is stored in
`replaygain_track_gain` and its three companions. MP4 has no way to say that a
gain is measured against -23 LUFS, so Opus R128 gains are restated against the
ReplayGain reference when written.

A field the file already stored under another atom keeps that atom. A file
that keeps its description in `ldes` keeps it there.

Reading accepts what other writers produce: several `data` boxes in one item,
which become several values, UTF-16 text, the numbered `gnre` genre, and
integer atoms written without a type. The raw view gives a freeform item the
key `----:mean:name`, such as `----:com.apple.iTunes:iTunSMPB`.

## What is kept as it was

Items AudioKodec does not model, such as iTunes' gapless `iTunSMPB` value, its
normalisation data or a store identifier, are written back with their stored
data types. An item whose structure the reader cannot follow is kept whole and
written back byte for byte, unless the edit sets the field its atom would hold.

## Artwork

`covr` stores an image and its format, JPEG, PNG, GIF or BMP, and nothing about
what it depicts. Pictures read from MP4 are front covers. Writing a picture of
another type, one with a description, or one in another format raises
`UnsupportedFeatureException` before any output.

## Chapters

Chapters the file already has are written back exactly as stored, whether they
came from a QuickTime chapter track or a Nero `chpl` list. Changed chapters are
written as a `chpl` list, which stores a start and a title of up to 255 bytes
for each of up to 255 chapters. Each chapter ends where the next one starts and
the last one ends with the file, so a chapter whose end says otherwise is
refused, as is chapter artwork. A file whose chapters are in a chapter track
refuses changes to them rather than ending up with two lists that disagree.

## Where the audio ends up

Only the `moov` box is rebuilt. Every byte before and after it is copied
unchanged.

When the new box fits in the old one, together with any `free` boxes directly
after it, the remainder becomes padding and nothing moves. When it does not
fit, the box grows with `TagWriteOptions.padding` bytes of padding for the next
edit. Whatever follows it moves, and every chunk offset in every track that
points past it is moved to match.

Two kinds of file cannot grow this way. A 32-bit chunk offset table cannot
address audio moved past 4 GiB, and a fragmented file whose fragments state
absolute positions, or which carries a fragment random access index, would be
left pointing at the old place. Both raise `UnsupportedFeatureException`; an
edit that fits the space the file already has still succeeds. Fragments that
count their data from their own `moof` box, which is what current muxers
write, move intact.

## Choosing what to write

The default writes the item list. `TagWriteOptions(formats = emptySet())`
removes it, and with it the `meta` and `udta` boxes when nothing else is left in
them. MP4 accepts no other tag format.

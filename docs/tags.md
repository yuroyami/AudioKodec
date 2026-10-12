# Tags

Tag reading works on any file, including the ones AudioKodec cannot decode.
A tag block does not need a decoder.

```kotlin
val tags = AudioKodec.readTags(bytes)
println(tags[TagField.TITLE])
println(tags.all(TagField.ARTIST))    // a file may name more than one
println(tags.number(TagField.TRACK_NUMBER))
```

## Which tag formats

Tags are read from ID3v2.2, 2.3 and 2.4, ID3v1 and v1.1, APEv1 and APEv2, Vorbis
comments in both FLAC and Ogg, FLAC picture blocks, Lyrics3v2, the `LIST INFO` and
`bext` chunks of a WAV, the text chunks of an AIFF, an `id3 ` chunk inside either, the
item list of an MP4 file, and the tags and chapters of a Matroska or WebM file.

Writing produces ID3v2.3 or 2.4, Vorbis comments and pictures in FLAC and Ogg, MP4
items and cover art, an updated ID3v1 block where the file already had one, and the
metadata chunks of RIFF and AIFF. Matroska tags are read only. [Format support](support.md#tags)
has the full table.

Ogg tag reads stop after the comment packets and any FLAC picture metadata.
Later setup or audio damage does not discard intact tags. Native FLAC tag
reads skip seek-table contents and do not allocate a playback index. The tag
blocks themselves must still be readable; tag reading remains best-effort
when their structure or containing page checksum is damaged.

## Where the values come from

A file often carries more than one tag block, and they disagree. `TagBundle`
merges them so that the richer format wins:

| Format | Beaten by |
|---|---|
| ID3v2 | nothing |
| Vorbis comments, MP4 items, Matroska tags | ID3v2 |
| APEv2 | the above |
| APEv1 | APEv2 |
| RIFF `INFO`, AIFF text chunks | the above |
| Lyrics3v2 | the above |
| ID3v1 | everything |

A field that only the losing block states is still taken from it, so nothing
is dropped for being in the wrong place.

Broadcast Wave's fixed fields are available in `TagBundle.raw`. Select
`TagFormat.BWF_BEXT` to write them explicitly in WAV, RF64 or BW64, with fixed
ASCII widths, an unsigned time reference and retained extension bytes.
See [Broadcast Wave metadata](broadcast-wave.md) for adding, editing and removing
the chunk. Ordinary edits preserve it byte-for-byte.

Everything stays available exactly as it was stored:

```kotlin
println(tags.raw.formats.map { it.displayName }) // [ID3v2.4, ID3v1]
for (block in tags.raw.blocks) {
    println("${block.format.displayName} at ${block.offset}, ${block.size} bytes")
    for (entry in block.entries) println("  ${entry.key} = ${entry.text}")
}
```

When one field has many values, the merge removes identical values and
preserves their first-seen order. Membership lookup uses a balanced index,
so colliding string hashes do not change its comparison bound. Existing
allocation limits still apply. Reading time also depends on file size and
text length.

`COMMENT` and `LYRICS` retain surrounding whitespace, including tabs and line
endings. Texts that differ by whitespace remain separate values. Other ordinary
text fields trim surrounding whitespace. Zero-length values are omitted.

## Editing

Editing rewrites the tag blocks and copies the audio across unchanged. It never
decodes or re-encodes, so it is safe on a lossy file.

```kotlin
val edited = AudioKodec.readTags(bytes).edit {
    set(TagField.TITLE, "New title")
    set(TagField.TRACK_NUMBER, 4)
    set(TagField.TRACK_TOTAL, 11)
    remove(TagField.COMMENT)
    addPicture(TagPicture(mimeType = "image/jpeg", bytes = coverBytes))
}
val updated = AudioKodec.writeTags(bytes, edited)
```

To discard every entry in the bundle, including private entries and unknown
fields, write the result of `tags.edit { clear() }`. Fields you set after
`clear()` make a fresh tag.

This produces a new file rather than editing one in place. Write it to a
temporary file and move that over the original, which is what makes the
operation safe against a crash halfway through.

An unrelated ID3 edit preserves each comment or lyric's exact text, language
and description. Original frames with identical text retain their distinct
language and description pairs. Changing whitespace is an explicit text change.
Replacement text with no exact original match receives the language `xxx` and
an empty description, or a numbered description when that identity is occupied.
Removing the field removes its modeled frames.

ID3 comments whose description starts with `iTun`, ignoring case, carry
reserved iTunes data. They remain in `tags.raw` and are excluded from
`TagField.COMMENT`. Adding, replacing or removing a user comment preserves
their text, language and description, including normalization (`iTunNORM`)
and gapless playback (`iTunSMPB`) values. Other described comments remain
editable user comments. A reserved description on lyrics or a custom text
frame does not change that frame's meaning.

`TagWriteOptions` controls the details:

```kotlin
AudioKodec.writeTags(
    bytes,
    edited,
    TagWriteOptions(
        id3Version = 3,        // for hardware that predates ID3v2.4
        padding = 4096,        // room for the next edit to reuse
        keepId3v1 = true,      // update an existing block; never create one
    ),
)
```

## Primary and explicit release dates

`TagField.DATE` retains the existing primary date convention. In ID3v2.4 it
maps to the recording timestamp `TDRC`. Music taggers also use that frame
for their main release date. An explicit `TDRL` timestamp has a separate
field, `RELEASE_DATE`, so an unrelated edit keeps both meanings.

| Field | ID3v2.4 | ID3v2.3 | Vorbis and APE |
|---|---|---|---|
| `DATE` | `TDRC` | `TYER` + optional `TDAT` and `TIME` | `DATE`, or APE `YEAR` on input |
| `RELEASE_DATE` | `TDRL` | `TXXX:RELEASEDATE` | `RELEASEDATE` |
| `ORIGINAL_DATE` | `TDOR` | `TORY` | `ORIGINALDATE` |

Set or remove either date field independently. Equal timestamp strings stay
in both fields when the file states both meanings. ID3v2.4 permits separate
values in each frame; ID3v2.3 requires one distinct user-text release value.
A release-date list in version 2.3 is named and refused before output.

## Converting legacy dates

Reading `TYER=2020`, `TDAT=0102` and `TIME=1230` returns
`DATE=2020-02-01T12:30`. `TDAT` stores day before month. The timestamp keeps
the stated minute precision; the reader does not add seconds. ID3v2.2
`TYE`/`TDA`/`TIM` groups follow the same rule.

A default rewrite emits one ID3v2.4 `TDRC` value and consumes the split
legacy frames. Writing version 2.3 splits a representable date into its
native components. It never stores a full timestamp in `TYER` or `TORY`.

| Requested `DATE` precision | ID3v2.3 output |
|---|---|
| `2020` | `TYER=2020` |
| `2020-02-01` | `TYER=2020`, `TDAT=0102` |
| `2020-02-01T12:30` | `TYER=2020`, `TDAT=0102`, `TIME=1230` |
| Month only, hour only, seconds, offsets, date ranges or multiple distinct dates | Named refusal before output |

`ORIGINAL_DATE` requires one four-digit year for version 2.3. Full original
release dates need version 2.4. Calendar dates and times must be valid;
the writer checks leap years and the native hour/minute ranges.
`RELEASE_DATE` uses its independent user-text value in version 2.3, which
can retain its timestamp precision.

Incomplete or conflicting legacy groups stay in `raw`. A time without a
calendar date cannot become a complete timestamp. Free-form `TRDA` notes
also stay raw because their wording cannot define one unambiguous date.
Use version 2.3 to retain such auxiliary information where representable.
An upgrade that would lose it fails by name before writing any bytes.

Explicitly setting, adding or removing `DATE` clears its old stored ID3
components, including orphan and opaque frames. This also clears free-form
recording-date notes. The resulting values define the replacement date;
`ORIGINAL_DATE` and `RELEASE_DATE` remain independent. Other field edits
retain the raw components until they can be converted or preserved safely.

## Metadata that needs a richer carrier

Default edits retain modeled metadata by adding an ID3 chunk to WAV, RF64,
AIFF or AIFF-C when native text cannot express it. This covers fields such as
album artist, totals, BPM and URL, pictures, chapters, ReplayGain, Unicode
text outside a declared RIFF code page, non-ASCII AIFF text and native
singleton lists. An existing external ID3
block is updated instead of adding another carrier. Compatible native values
are still written for readers using INFO or AIFF text.

An explicit native-only selection raises `UnsupportedFeatureException` naming
all the modeled fields and components it cannot retain, before writing bytes.
Include ID3 or leave `formats` at its default to keep those values. An explicitly
empty selection still removes writable metadata intentionally. Ogg requires
its comment header and refuses an empty format selection.

Vorbis comments store URLs under `WEBSITE`; APE writes `Weblink` and reads
`WEBSITE`, `URL` and `Weblink`. Track and disc totals without a number use
`TRACKTOTAL` and `DISCTOTAL`, including ID3 user-text frames. A single total
accompanies each number in the conventional number/total field. Several
numbers and totals use separate lists so no relationship is guessed.
ID3v2.3 refuses number or total lists it cannot represent; use version 4.

FLAC, Ogg and APE chapter writing requires another complete ID3 carrier in the
same file. Without one, chapters are named and refused. Fresh FLAC encoding
also refuses chapters before output. Native chapter writing remains unsupported.
ID3 rejects chapter times beyond its unsigned 32-bit range, an explicit end
of `4294967295` reserved for an unknown end, and identifiers it cannot encode.
ID3 URL frames require Latin-1; escape a Unicode URL before writing it.
Null characters in ID3 or APE modeled text are refused because they would
become value separators.

## Native text encodings

RIFF INFO reads honor a file-wide `CSET` declaration before or after the INFO list.
These code pages are supported:

| CSET code page | Encoding |
|---|---|
| `0`, `28591` | ISO-8859-1, also called Latin-1 |
| `1252` | Windows-1252, including its defined punctuation and currency symbols |
| `65001` | UTF-8 |

Without `CSET`, reads accept valid UTF-8, then fall back to Latin-1.
This accepts FFmpeg's undeclared UTF-8 and older native text files.
Some byte sequences are valid in both encodings and have different meanings.
Undeclared text cannot resolve that ambiguity; valid UTF-8 takes precedence.
An explicit Latin-1 declaration always prevents UTF-8 guessing.

New undeclared INFO output uses UTF-8, matching FFmpeg's native output.
A rewrite retains an existing `CSET` and encodes INFO text in its declared code page.
Other chunks retain their bytes and declaration.
Readers such as ExifTool need a UTF-8 charset hint for undeclared Unicode INFO.
Explicit native-only output can retain Unicode without ID3 when the INFO encoding permits it.
Modeled values outside a declared legacy code page need ID3 or produce a named refusal.
Unknown INFO text has no ID3 projection and is refused if the declared encoding cannot retain it.
These refusals happen before any output, including an external ID3 prefix.

AIFF and AIFF-C native output uses the specification's ASCII text encoding.
Non-ASCII values use ID3; explicit native-only output refuses them.
Reads also accept valid UTF-8 and legacy Latin-1 native text for compatibility.
Fresh encoding uses the same native rules and includes complete ID3 metadata.

Unsupported RIFF code pages raise `UnsupportedFeatureException` when INFO text needs decoding or writing.
Malformed or conflicting `CSET`, invalid declared UTF-8 and undefined Windows-1252 bytes raise `CorruptStreamException`.
Native text does not replace unrepresentable characters with question marks.

## Multiple values in native text chunks

WAV and RF64 write each non-empty modeled RIFF INFO value in a separate
subchunk, preserving the field's value order. AIFF and AIFF-C write each
comment in a separate `ANNO` chunk. An empty first value does not hide later
non-empty values.

AIFF permits only one native title (`NAME`), author (`AUTH`) and copyright
(`(c) `) chunk. Several distinct values need an ID3 carrier. The default
rewrite adds an ID3 chunk when none exists, keeping the complete list there.
The native chunk holds the first non-empty compatible value for readers using native text.
Fresh encoding uses the same native chunk rules and already includes ID3.

An explicit `setOf(TagFormat.AIFF_TEXT)` selection refuses a singleton list
before writing output. Include `TagFormat.ID3V2_4`, or select it alone, to retain
the complete list. ID3v2.3 still refuses lists it cannot represent.
Repeated copies of the same singleton value need no fallback.

## Encoding software and credits

`TagField.ENCODER` identifies the encoding software or hardware.
`ENCODED_BY` identifies the person or organization that encoded the file.
`ENCODING_SETTINGS` holds separately stated settings.

| Field | ID3v2.3/v2.4 | Vorbis/APE | RIFF INFO |
|---|---|---|---|
| `ENCODER` | `TSSE` | `ENCODER` | `ISFT` |
| `ENCODED_BY` | `TENC` | `ENCODEDBY` | `IENC` |
| `ENCODING_SETTINGS` | `TXXX:ENCODERSETTINGS` | `ENCODERSETTINGS` | needs an ID3 chunk |

ID3v2.2 input uses `TSS`, `TEN` and `TXX:ENCODERSETTINGS` respectively.
A combined encoder/settings string in `TSSE` stays intact in `ENCODER`.
The format supplies no delimiter that can reliably separate those facts.
Supplying both software and explicit settings writes separate frames,
preserving each value without concatenation.

Vorbis and APE also recognize `ENCODED-BY` and `ENCODED_BY` as human
credits, and `ENCODING` as a combined encoder/settings value. RIFF input
recognizes `ITCH` as another human-credit convention.
The default adds ID3 alongside RIFF INFO when explicit settings need it.

Reading follows the meaning of the native frame. It does not guess from the
text whether a value names a person or a program.

## Unknown Vorbis comments

FLAC and Ogg keep unmodeled Vorbis comments in their stored order, including
repeated entries, key spelling and empty values. Rewriting those comments
performs work proportional to their count and text bytes. The merged fields
still omit empty values and combine repeated modeled values as described above.

The vendor header is separate from user comments. The raw view exposes it as
its first `VENDOR` entry, with internal provenance that survives ordinary edits
and reconstruction of a block around the same entries. It is never emitted as
a user comment. Actual user comments named `VENDOR` remain intact, including
caller-constructed entries with the same text as the header. FLAC and Ogg tag
rewrites preserve the source header vendor, including an empty string.

Invalid UTF-8 in one comment or the vendor header does not prevent audio
decoding in either mode. Readable comments remain available. An unreadable
comment has `text = null` in the raw view and its complete stored `KEY=value`
bytes in `binary`; a readable ASCII key is retained, otherwise the key is
`<invalid comment>`. An unreadable vendor stores just its vendor bytes there.
Ordinary edits preserve those bytes. Explicit `TagEditor.set`, `add` or
`remove` calls for a modeled field replace or remove its unreadable entries.
`clear()` removes raw comments, while the source vendor header survives a
rewrite. Structural lengths and allocation limits still apply.

Modeled fields, pictures and the six recognized ReplayGain/R128 names are
rebuilt from the edited bundle. Removing those values does not reintroduce
their old raw entries. Unmodeled names sharing the loudness prefixes remain
raw comments.

## ID3 values and MusicBrainz identifiers

ID3v2.4 stores each text field or user-text description in one frame, with
null-separated values. ID3v2.3 defines slash lists for artists, composers,
lyricists, original artists and original lyricists. Genres use the reference
grammar described below. Other v2.3 text fields and user-text frames require
one distinct value. Writing an unsupported list
or a literal slash in a slash-list value raises `UnsupportedFeatureException`.
Use `TagWriteOptions(id3Version = 4)` to preserve those values separately.

Read mapped user-text lists with `tags.all(field)`. The raw view has one entry
per decoded value, with the same frame key and description. Unmodeled raw
lists retain their order, repeated values and valid empty strings through an
unrelated edit. A single final terminator ends the preceding value; an empty
last value has its own terminator. Malformed UTF-8 and UTF-16 text segments
with an odd byte count are skipped. The merged fields omit empty values.
All four v2.4 text encodings are supported, including both UTF-16 BOM byte orders.
V2.3 slash-list frames retain raw repetitions and empty slash-separated pieces.
Other raw lists need v2.4,
even when every item has the same text; an unsupported downgrade is refused
before writing.

Read every genre with `tags.all(TagField.GENRE)`. ID3 references and refinements
remain separate values in file order. For example, `(51)(39)Eurodisco` becomes
`Techno-Industrial`, `Noise`, `Eurodisco`; `(4)Eurodisco` keeps both `Disco` and
`Eurodisco`. Repeated identical values are deduplicated. Unknown syntax stays
literal, including the remaining text after an unknown reference. The raw
view retains the stored spelling. Other tag formats keep their free-text
genres without interpreting ID3 codes.

Supply genre names when writing, rather than stored codes such as `17` or
`RX`. V2.3 stores known names as references, followed by at most one free-text
refinement. It refuses multiple refinements or a list that would need
reordering. V2.4 stores separate values. Standalone literals that coincide
with recognized numeric codes, `RX` or `CR` are ambiguous and refused.
A numeric refinement after known v2.3 references remains literal.

The reader accepts the older bracket grammar in v2.4 tags for compatibility.
Writing a literal leading parenthesis uses the older `((` escape in both
versions, matching readers such as Mutagen. A v2.4 reader that treats every
parenthesised string as plain text may display the extra parenthesis.
Mutagen's default loading normalizes genre text before `genres` parses it.
For literals resembling codes, such as `(51)`, use `ID3(path, translate=False)`
when inspecting the original genre grammar; default conversion can interpret
that literal again.

MusicBrainz identifiers use Picard's ID3 identities:

| Field | Frame or description |
|---|---|
| `MUSICBRAINZ_RECORDING_ID` | `UFID` with owner `http://musicbrainz.org` |
| `MUSICBRAINZ_ARTIST_ID` | `TXXX:MusicBrainz Artist Id` |
| `MUSICBRAINZ_RELEASE_ID` | `TXXX:MusicBrainz Album Id` |
| `MUSICBRAINZ_RELEASE_ARTIST_ID` | `TXXX:MusicBrainz Album Artist Id` |
| `MUSICBRAINZ_RELEASE_GROUP_ID` | `TXXX:MusicBrainz Release Group Id` |
| `MUSICBRAINZ_TRACK_ID` | `TXXX:MusicBrainz Release Track Id` |

The reader also accepts the old `TXXX:MUSICBRAINZ_RECORDING_ID` description.
Rewriting it produces the standard `UFID`. A recording identifier must be
1 to 64 ASCII bytes without whitespace or controls, and ID3 can hold only
one distinct value under that owner. Unrelated owners' binary identifiers
are preserved. Malformed MusicBrainz identifiers remain in the raw view;
setting the named field replaces that owner's old entry.

ID3v2.2 tags are upgraded to v2.4 by default, or v2.3 when requested. Standard
three-character identifiers are mapped to their four-character equivalents,
including `TLE` to `TLEN`, user URLs, binary frames and linked-frame identifiers.
Legacy artist, composer and lyricist slash lists retain their separate members.
The old encrypted meta-frame `CRM` and unknown three-character extensions have
no safe equivalent and are omitted during upgrade; other fields and the audio
remain editable. Existing four-character unknown frames retain their payloads.

Compressed or encrypted frames keep their payloads and flags across edits
within the same ID3 version. Frames marked for removal when the tag changes
are discarded as requested by their flags. Other opaque frames cannot be
converted to another version or combined with a rewritten frame of the same
identifier, including `PRIV`, `APIC`, `COMM`, `UFID` and `TXXX`. The writer
raises `UnsupportedFeatureException` before emitting output, so an unreadable
identity cannot be silently replaced. Discarding such metadata requires
explicitly removing its raw entry or clearing the tags before rebuilding them.

## Editing ID3 chapters

Parsed ID3 chapters retain their stored subframes independently of `TagBundle.raw`.
An unrelated edit in the same ID3 version preserves their order, payloads and flags.
This includes subtitles, URLs, comments, private data and unknown frames.
A subframe marked for discard when the tag changes is removed as its flag requires.

Use `TagChapter.copy` to edit the modeled values while retaining other subframes:

```kotlin
val tags = AudioKodec.readTags(bytes)
val first = tags.chapters.first().copy(title = "A new title", startMillis = 1500)
val edited = tags.edit {
    setChapters(listOf(first) + tags.chapters.drop(1))
}
val written = AudioKodec.writeTags(bytes, edited, TagWriteOptions(id3Version = 4))
```

A changed title or picture list replaces its readable `TIT2` or `APIC` frames.
Unchanged chapter pictures keep their stored captions and sequence.
New and edited artwork follows the picture identity rules below.
Unreadable title or picture metadata raises `UnsupportedFeatureException` when those values change.
Construct a new `TagChapter` to replace the complete chapter, including unreadable and unknown subframes.
`setChapters(emptyList())` removes every chapter. `clear()` also removes retained raw metadata.
Neither operation restores old `CHAP` frames from the raw bundle.

Changing ID3 version rebuilds subframe headers and translates status and group flags.
It removes storage-only unsynchronization and data-length prefixes after validating and decoding them.
A read-only bit is cleared when converted contents change.
Supported conversions cover ordinary text and URL frames, `TXXX`, `WXXX`, `COMM`, `USLT`,
`APIC`, `GEOB`, `USER` and the version-independent binary frame layouts such as `PRIV` and `UFID`.
Valid `TYER`/`TDAT`/`TIME` groups and `TORY` convert to `TDRC` and `TDOR`, and back,
within the date precision described above.

Compressed, encrypted, reserved-flag and unknown layouts cannot be converted safely.
Unsupported text lists, unresolved dates and precision beyond the destination also raise
`UnsupportedFeatureException`, naming the chapter and subframe before output.
Retain the source ID3 version to preserve these frames, or replace the complete chapter deliberately.
Malformed retained headers, parent boundaries and length prefixes raise `CorruptStreamException`.
Chapter parsing limits nesting to sixteen levels and honors `DecodeOptions.maxAllocationBytes`.

## Pictures

```kotlin
tags.cover?.let { showCover(it.bytes, it.mimeType) }
for (picture in tags.pictures) {
    println("${picture.type}: ${picture.mimeType}, ${picture.bytes.size} bytes")
}
```

The bytes are the image file exactly as it was stored. AudioKodec does not
decode it, and does not need to: pass them to ImageKodec, or to whatever the
platform uses.

Native FLAC encoding and tag edits store artwork in separate `PICTURE` blocks.
Each block retains the image bytes, role, MIME type, UTF-8 description and
informational dimensions. Vorbis comments carry text and gain fields without
duplicate `METADATA_BLOCK_PICTURE` values.

A native FLAC picture body can contain at most 16,777,215 bytes. This includes
32 bytes of length and property fields, the MIME type, the UTF-8 description
and the image data. Encoding and tag edits check every picture before writing
output. An oversized picture raises `AudioKodecException` and leaves the sink
untouched; encoding also leaves the PCM source unread. The encoder then writes
the supplied image bytes directly to the sink without another full image copy.

`tags.pictures` merges equivalent copies across tag blocks. Images with
different bytes, roles or captions in a format that carries captions remain
distinct. Rich picture structures take priority over legacy `COVERART`, MP4
`covr` and APE copies. Matching FLAC artwork can supply dimensions absent
from ID3. The raw blocks retain every stored entry. Chapter pictures stay
with their chapter and use a separate identity scope.

ID3 requires unique picture descriptions. New and edited pictures collapse exact duplicates
and appends deterministic ` [1]`, ` [2]` suffixes when distinct pictures share
a description, reserving existing descriptions first. For v2.3, a suffixed caption is shortened to fit its 64-character limit
without splitting a UTF-16 pair. Every block in a mixed write receives the
same resulting captions. Read the resulting bundle to obtain those
descriptions; subsequent edits keep them stable.
ID3 permits only one picture of each file-icon type. Two distinct pictures
of one such type raise `UnsupportedFeatureException`.

APE uses a separate standard `Cover Art` key for each picture role. Its
binary prefix is a filename, exposed as `TagPicture.description`, rather
than an ID3 caption; that prefix does not distinguish equivalent copies in
the merged artwork set. A mixed write retains additional pictures of one
role in ID3 or native FLAC/Ogg artwork and mirrors the first in APE. An
APE-only write requesting multiple distinct pictures under one role key
raises `UnsupportedFeatureException` before writing to the sink. Include
ID3v2 when every picture must be retained. Null characters in ID3 picture
metadata or an APE filename cannot be represented and are refused. APE-only
writes also refuse chapter-local artwork, which needs ID3.

## Loudness

Two conventions exist and they are five decibels apart. ReplayGain 2 measures
against -18 LUFS; Opus writes `R128_TRACK_GAIN` against -23 LUFS. AudioKodec
reports both as decibels and says which was found, so applying one as the other
is not a mistake you can make by accident.

```kotlin
val gain = tags.replayGain
val trackGain = gain?.trackGainDb
if (gain != null && trackGain != null) {
    // An R128 gain aims at -23 LUFS. Add 5 dB to play it at the -18 LUFS of ReplayGain 2.
    val gainDb = if (gain.isR128) trackGain + 5.0 else trackGain
    Levels.applyGain(audio.samples, audio.format.channels, gainDb)
}
```

ID3, APE and Vorbis writers rebuild only these six names from `replayGain`:
`REPLAYGAIN_TRACK_GAIN`, `REPLAYGAIN_TRACK_PEAK`, `REPLAYGAIN_ALBUM_GAIN`,
`REPLAYGAIN_ALBUM_PEAK`, `R128_TRACK_GAIN` and `R128_ALBUM_GAIN`.
Matching ignores case. ID3 uses the user-text description; APE and Vorbis use
the item name. Removing `replayGain` removes these names without restoring
their old raw values.

Other names, such as `REPLAYGAIN_REFERENCE_LOUDNESS` and
`REPLAYGAIN_TRACK_RANGE`, remain raw metadata through unrelated edits.
ID3 retains their descriptions and supported value lists. APE retains key
spelling, binary payloads, repeated values and empty pieces in text lists.
Empty raw values remain accessible even though merged fields omit them.
An empty ID3v2.3 user-text value uses UTF-16 with a byte order mark for readers
that omit the same value in Latin-1.

Stored R128 gains must contain ASCII decimal digits, with an optional leading
sign and at most six characters. Leading zeros are permitted; whitespace and
other characters are not. Values outside -32768 to 32767 remain in the raw
view without supplying applicable gain information. A later valid entry can
still supply the gain.

Writing an R128 gain requires -128.0 to 127.99609375 dB, inclusive. A request
outside that range raises `UnsupportedFeatureException` before tag output.
Values inside the range round to the nearest 1/256 dB step, with ties towards
the positive value. The range check happens before rounding. Ordinary
ReplayGain text and reference conversion into ID3 or APE keep their existing
range.

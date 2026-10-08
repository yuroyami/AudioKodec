# AudioKodec

Audio containers, codecs and tags written in Kotlin for Kotlin Multiplatform.
Read and write FLAC, WAV, AIFF and AU; decode FLAC, Vorbis, every MPEG audio
layer and the uncompressed family to Float32 PCM; edit tags without re-encoding
the audio; and run the same code on every target.

This release covers MPEG Layers I, II and III, FLAC (natively and inside Ogg),
Vorbis, the uncompressed family, the Ogg container and tags. The
[format status](https://github.com/yuroyami/AudioKodec/blob/main/PORTING_STATUS.md)
page is the full list of what works today and what is coming.

## Install

0.1.0 is unreleased and is not yet published to Maven Central. The snippet
below shows the planned coordinates for that release.

Both JVM modules target Java 21 and require a Java 21 or later runtime.
Building the repository requires a JDK 21 toolchain; use the included Gradle wrapper.

```kotlin
dependencies {
    implementation("io.github.yuroyami:audiokodec:0.1.0")
    // Optional, for reading and writing through kotlinx-io:
    implementation("io.github.yuroyami:audiokodec-io:0.1.0")
}
```

## Decode a file

```kotlin
val audio = AudioKodec.decode(bytes)
println("${audio.format.sampleRate} Hz, ${audio.format.channels} channels")
println("${audio.durationSeconds} seconds")
```

`audio.samples` is interleaved Float32, nominally between -1.0 and 1.0. A
lossy coding can go slightly outside that range, and AudioKodec does not clamp
it, because clamping belongs at the point where samples become integers again.

## Read a header without decoding

```kotlin
val info = AudioKodec.probe(bytes)
println(info.container.displayName)
println(info.codec.displayName)
println(info.durationSeconds)
```

`probe` does not decode samples. Its I/O depends on the format: Ogg walks
pages to the stream end and reads their payloads to verify checksums; MPEG
without a stored Xing or VBRI frame count scans frame headers across the file
for an exact duration. Both can read the whole file, so a folder scan is not
bounded to a fixed header window. Other formats read their metadata and index
tables. Opening Ogg repeats the page walk before the first decoded frame.

CAF ALAC packet tables from some encoders, including ffmpeg, count a short
final packet as a full one. `probe` reports that metadata duration. `open`
decodes the final packet once and corrects the decoder's duration and `info`
before playback, so complete files end cleanly and seeking uses the actual
length. Damaged or missing packets still fail in strict mode.

AIFF-C QuickTime IMA (`ima4`) stores its length as groups of compressed
packets. AudioKodec reports the decoded length, with 64 frames per group.
Both Apple's and ffmpeg's files are accepted: IMA sample widths may be 0, 4
or 16, and A-law and mu-law widths may be 8 or 16. A short IMA payload still
raises `TruncatedStreamException` in strict mode; tolerant mode returns only
the complete packet groups available.

The AIFF-C integer types `23ni` and `42ni` are little-endian, with widths of
32 and 24 bits respectively. Their compression type determines the width,
including when the `COMM` sample size disagrees.

CAF IMA accepts a compressed sample width of 0 or 4. Its packet description
must still specify flags 0, 34 bytes per channel and 64 frames per packet.

## Name a format

`sniff` names 29 formats from the bytes supplied, including formats without a
decoder. Check `isDecodable` before choosing AudioKodec to read one:

```kotlin
val container = AudioKodec.sniff(firstBytes)
if (container?.isDecodable == true) { /* AudioKodec can read it */ }
```

A null result means the bytes do not identify a format. This includes an
incomplete leading ID3 tag, a tag without a following audio signature, an
invalid tag header and unrecognised following bytes. ID3 metadata alone does
not establish MPEG audio; a visible MPEG frame header is needed.

`AudioKodec.SNIFF_HEADER_BYTES` is a recommended initial window of 64 KiB,
not a guarantee. Consecutive leading tags, including their footers, and the
following signature must fit. Supply more bytes when the result is null, or
use `probe` or `open` with the complete source: these walk valid leading tags
before reading the audio header.

## Stream and seek

Whole-file decoding allocates the whole file, so use the streaming decoder for
anything long. Seeking is sample accurate: the decoder lands on the frame you
asked for, not on the block containing it.

```kotlin
AudioKodec.open(source).use { decoder ->
    decoder.seek(decoder.durationFrames!! / 2)
    while (true) {
        val block = decoder.nextFrame() ?: break
        play(block.samples, block.frameCount)
    }
}
```

All decoders reject negative seek targets with `IllegalArgumentException`.
A seek past a known duration clamps to that duration. An unknown-duration
stream retains the requested seek position; a following read returns null
if the position is past the available audio. Seeking does not require a
separate scan solely to discover an unknown end.

To read it as a `Flow`:

```kotlin
fun AudioDecoder.frames(): Flow<AudioFrame> = flow {
    use { while (true) emit(it.nextFrame() ?: break) }
}
```

Nothing in the decoder suspends, so it also works on a plain thread and on
targets that have neither threads nor coroutines.

### Buffer reuse

`DecodeOptions(reuseBuffers = true)` makes the decoder hand back the same
array every time, which removes almost all allocation from a decode loop. Each
returned block then stays valid only until the next call. Copy anything you
keep.

## Write a file

```kotlin
val wav = AudioKodec.encode(samples, AudioFormat(44_100, 2), EncodeTarget.wav(16))
```

To convert one decoded value into several formats, reuse it directly:

```kotlin
val decoded = AudioKodec.decode(bytes)
val wav = AudioKodec.encode(decoded, EncodeTarget.wav(16))
val flac = AudioKodec.encode(decoded, EncodeTarget.flac(16))
```

Each encoding reads the complete `DecodedAudio.samples` array from the
beginning through an independent reader. It preserves the cursor used by
direct `DecodedAudio.read` calls. Use `decoded.rewind()` to reset that direct
cursor, including after reaching the end. No rewind is needed for encoding
or for retrying after an encoding fails.

Other `PcmSource` implementations are consumed through their own cursor.
Create a fresh source for each encoding operation. The sample array is not
copied, so avoid changing it while an encoding runs.

The targets available are FLAC, integer PCM and IEEE float in WAV, AIFF and AU,
A-law and mu-law in all three, and IMA or Microsoft ADPCM in WAV.

Reducing the depth of a float or a deeper integer source is where dither
belongs. It replaces the distortion of plain rounding with a small amount of
noise, which is what you want at 16 bits and below:

```kotlin
AudioKodec.encode(samples, format, EncodeTarget.wav(16, dither = Dither.Tpdf()))
```

Files above 4 GiB are written as RF64 automatically. AudioKodec reserves the
space for the larger size fields in every WAV it writes, and uses it when the
file turns out to need it.

## Precision and lossless round trips

Decoding produces Float32, which carries 8-, 16- and 24-bit audio exactly. It
cannot carry 32-bit integers exactly, because a Float32 has 24 bits of
mantissa. Decoders for lossless codings therefore also implement
`IntPcmAccess`:

```kotlin
AudioKodec.open(bytes).use { decoder ->
    if (decoder is IntPcmAccess) {
        val block = decoder.nextIntFrame()   // exactly what the file holds
    }
}
```

Use one or the other on a given decoder, not both. They advance the same
cursor.

## FLAC

FLAC decodes bit-exactly, and encodes to files the reference decoder accepts.

```kotlin
val flac = AudioKodec.encode(samples, format, EncodeTarget.flac(bitDepth = 16, effort = 5))
```

`effort` runs from 0 to 8. It changes how hard the encoder looks for a good
predictor and how finely it partitions the residual, trading time against size
the way the reference encoder's numbered levels do. It never changes what comes
back out: the format is lossless at every setting.

### Verifying a file

Every FLAC file carries an MD5 of its own unencoded audio, written by whichever
program encoded it. That makes a decoder checkable against a third party:

```kotlin
when (AudioKodec.verifyFlacMd5(bytes)) {
    true -> println("the audio matches the hash the encoder stored")
    false -> println("this file has been damaged")
    null -> println("this file states no hash")
}
```

It costs a full decode, so it belongs in a library-integrity pass rather than
in front of every playback.

### Seeking

FLAC frames vary in size, so a position cannot be turned into a byte offset by
multiplying. AudioKodec uses the seek table when the file has one and scans frame
headers when it does not. Both checksums in every frame are verified during that
scan, so a byte sequence that happens to look like a frame sync is rejected
rather than decoded as audio.

Set `DecodeOptions(allowSlowSeek = false)` to make a seek that would need a scan
throw instead of taking its time.

### Editing a FLAC file's tags

Editing rewrites the metadata blocks and copies the audio frames across
untouched. Seek tables, cue sheets and application blocks the file carried are
kept as they were, and padding is regenerated so the next edit need not move the
audio again.

The stream MD5 still matching after an edit is the proof that the audio was not
touched, and it is what the tests check.

## Ogg

Ogg is a container, not a codec, so what a file holds depends on the mapping
inside it. AudioKodec reads the container in full: pages and their checksums,
packets reassembled across page boundaries, files carrying several logical
streams at once, and files chained one after another.

| Mapping | Headers and tags | Audio |
|---|---|---|
| Vorbis | read | decodes, with seeking |
| FLAC | read | decodes, with seeking |
| Opus | read | decodes, with seeking |
| Speex | read | not yet |

```kotlin
val info = AudioKodec.probe(oggBytes)
println(info.codec.displayName)     // Opus
println(info.durationSeconds)       // 213.5
```

The length comes from the granule position on the last page, which is the only
place an Ogg file records it. For Opus the pre-skip is subtracted, because those
samples are an artefact of the encoder and are not part of the recording, and
so is the start of a stream cut out of a longer one. Decoding drops the same
samples: the pre-skip at the front and, at the back, whatever the last page's
granule position says lies past the end of the recording.

Opus always decodes at 48 kHz, whatever rate the header says the material was
recorded at, and applies the header's output gain. Samples are floats and are
not clipped, so a loud decode can reach past full scale where a 16-bit decoder
would have bent it back.

`open` on a mapping whose codec is not implemented throws
`UnsupportedFormatException` naming the codec, rather than failing in a way that
leaves a caller guessing.

### Vorbis

Vorbis is a transform codec: each packet is one overlapping block of spectrum,
described as a coarse curve with fine detail coded on top of it. Decoding one
means reading both, undoing the stereo coupling, multiplying them together,
running an inverse transform, applying a window and adding the result onto the
tail of the block before.

Two consequences of that reach the API. Blocks vary in size within one file,
because an encoder switches to short blocks at a transient, so the frame count
of what `nextFrame` returns varies too. And a seek cannot begin at an arbitrary
packet, because a block with no predecessor has no left half; AudioKodec restarts
on a page boundary before the target and discards up to it, so a seek is still
sample accurate.

```kotlin
AudioKodec.open(oggBytes).use { decoder ->
    decoder.seek(48_000)                    // one second in, at 48 kHz
    val block = decoder.nextFrame()
    println(block?.positionFrames)          // 48000
}
```

Everything an encoder emits is covered: floor type 1, all three residue types,
channel coupling, block switching, and up to eight channels, which come out in
AudioKodec's channel order rather than Vorbis's own. Vorbis puts the centre
channel second, between the two front channels, where WAV and everything derived
from it puts it third.

Floor type 0, which describes the curve as line spectral pairs instead of line
segments, is parsed and then refused by name. No encoder has emitted it since
the format's 2001 beta releases, which also means there is no way to produce a
file to test an implementation of it against, and shipping a decoder nothing
could check is worse than a clear refusal.

### Rewriting Ogg tags

A new comment is almost never the same length as the old one, and an Ogg page
cannot simply grow: its size is fixed by its segment table, its checksum covers
the whole page, and the pages after it are numbered consecutively. So the header
pages are rebuilt and the later pages renumbered, with every audio packet copied
across untouched.

A file interleaving several logical streams, such as a video with a soundtrack,
is refused rather than repaginated. Rebuilding the interleaving is a job worth
doing properly or not at all.

## MP4

MP4 is a container, and the same reader covers its relatives: M4A, M4B, 3GP
and QuickTime `.mov`. The audio track's packets are found from its sample
tables wherever the `moov` box sits, before the audio or after it, and in
fragmented files from each fragment's own table.

QuickTime files do not need an `ftyp` brand atom. Detection recognises `moov`
and `mdat`, and can skip leading `free`, `skip`, `wide` and `uuid` atoms. It
checks at most 128 atom headers. For byte-array `sniff`, the identifying header
must fit in the supplied window; `probe` and `open` can jump over larger padding.

| Coding | Audio |
|---|---|
| Opus | decodes, with seeking |
| FLAC | decodes, with seeking, exactly |
| Uncompressed PCM, float, A-law and mu-law | decodes, with seeking, exactly |
| ALAC | decodes, with seeking, exactly |
| AAC, MP3, AC-3, AMR | named, not yet decoded |

```kotlin
val info = AudioKodec.probe(m4aBytes)
println(info.codec.displayName)                      // AAC
val chapters = AudioKodec.readTags(m4bBytes).chapters
```

The length is what the track's edit list says plays, which is how an encoder
leaves out its priming samples, and decoding drops the same samples. An edit
list that plays the media straight through is followed; one that skips or
repeats parts of it is refused, and in tolerant mode only its first part is
played. A file without an edit list plays all of its media, except Opus,
whose pre-skip is dropped either way.

Chapters come from a QuickTime chapter track, with start and end times, or
failing that from Nero's `chpl` list, which states only starts: the last
chapter's end is reported as -1.

## MPEG audio

An MP3 has no file header. It is a run of frames, each one carrying its own
bitrate, sample rate and channel mode, with tag blocks in front of and behind
the audio that are not part of it. Reading such a file means finding the frames.

```kotlin
val info = AudioKodec.probe(mp3Bytes)
println(info.durationSeconds)     // exact, not estimated
println(info.dataOffset)          // where the audio begins, past the tags
```

Three things make the length exact. A constant bitrate file divides out. A
variable bitrate one almost always carries its frame count in a `Xing` or
`VBRI` block hidden inside a silent first frame, which is counted as
bookkeeping rather than as audio. And where LAME recorded its gapless numbers,
the samples of silence it added at each end come off, along with the 529
samples of delay every MPEG decoder's own filter introduces.

A sync word is eleven set bits, which turns up in ordinary audio data every
couple of kilobytes. A candidate frame is therefore only believed once the
frames that should follow it do. Without that a decoder starts mid-frame and
plays noise, or finds its first frame inside a cover image.

Strict MPEG decoding accepts non-audio bytes shorter than a frame header and
zero padding after the final complete frame. ID3v1, enhanced ID3v1 and Lyrics3
trailers end the frame scan even when the source cannot report its size.
Playback stops before reading beyond a stored duration. A complete frame
header whose declared payload is cut short still raises `TruncatedStreamException`.

Files using the free format, whose frames state no length at all, are named and
refused rather than measured by guesswork.

All three layers decode. Layer III, usually called MP3, can draw a frame's
audio data from a bit reservoir extending into earlier frames. Seeking starts
far enough before the target to reconstruct that state, then discards samples
until it lands on the exact requested frame.

```kotlin
AudioKodec.open(mp3Bytes).use { decoder ->
    decoder.seek(44_100)                 // one second in, to the sample
    val block = decoder.nextFrame()      // 1152 frames of Float32
}
```

Oracle tests compare all three layers with ffmpeg's floating-point decoders;
the maximum sample difference must be below 1e-4 for Layers I and III and
below 1e-5 for Layer II.

## Tags

Tag reading works on any file, including the ones AudioKodec cannot decode yet.
A tag block does not need a decoder.

```kotlin
val tags = AudioKodec.readTags(bytes)
println(tags[TagField.TITLE])
println(tags.all(TagField.ARTIST))    // a file may name more than one
println(tags.number(TagField.TRACK_NUMBER))
```

### Where the values come from

A file often carries more than one tag block, and they disagree. `TagBundle`
merges them so that the richer format wins:

| Format | Beaten by |
|---|---|
| ID3v2 | nothing |
| Vorbis comments | ID3v2 |
| APEv2 | the above |
| APEv1 | APEv2 |
| RIFF `INFO`, AIFF text chunks | the above |
| Broadcast Wave `bext` | the above |
| Lyrics3v2 | the above |
| ID3v1 | everything |

A field that only the losing block states is still taken from it, so nothing
is dropped for being in the wrong place. Everything stays available exactly as
it was stored:

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

### Editing

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

This produces a new file rather than editing one in place. Write it to a
temporary file and move that over the original, which is what makes the
operation safe against a crash halfway through.

An unrelated ID3 edit preserves each comment or lyric's exact text, language
and description. Original frames with identical text retain their distinct
language and description pairs. Changing whitespace is an explicit text change.
Replacement text with no exact original match receives the language `xxx` and
an empty description, or a numbered description when that identity is occupied.
Removing the field removes its modelled frames.

ID3 comments whose description starts with `iTun`, ignoring case, carry
reserved iTunes data. They remain in `tags.raw` and are excluded from
`TagField.COMMENT`. Adding, replacing or removing a user comment preserves
their text, language and description, including normalisation (`iTunNORM`)
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

### Primary and explicit release dates

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

`RELEASE_DATE` is appended after the existing enum entries. Earlier versions
combined `TDRC` and `TDRL` into `DATE`. Read the source tags again when
migrating a cached merged date list, because the list alone cannot recover
the original frame identities.

### Converting legacy dates

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

### Metadata that needs a richer carrier

Default edits retain modelled metadata by adding an ID3 chunk to WAV, RF64,
AIFF or AIFF-C when native text cannot express it. This covers fields such as
album artist, totals, BPM and URL, pictures, chapters, ReplayGain, Unicode
text outside a declared RIFF code page, non-ASCII AIFF text and native
singleton lists. An existing external ID3
block is updated instead of adding another carrier. Compatible native values
are still written for readers using INFO or AIFF text.

An explicit native-only selection raises `UnsupportedFeatureException` naming
all the modelled fields and components it cannot retain, before writing bytes.
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
Null characters in ID3 or APE modelled text are refused because they would
become value separators.

### Native text encodings

RIFF INFO reads honour a file-wide `CSET` declaration before or after the INFO list.
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
Modelled values outside a declared legacy code page need ID3 or produce a named refusal.
Unknown INFO text has no ID3 projection and is refused if the declared encoding cannot retain it.
These refusals happen before any output, including an external ID3 prefix.

AIFF and AIFF-C native output uses the specification's ASCII text encoding.
Non-ASCII values use ID3; explicit native-only output refuses them.
Reads also accept valid UTF-8 and legacy Latin-1 native text for compatibility.
Fresh encoding uses the same native rules and includes complete ID3 metadata.

Unsupported RIFF code pages raise `UnsupportedFeatureException` when INFO text needs decoding or writing.
Malformed or conflicting `CSET`, invalid declared UTF-8 and undefined Windows-1252 bytes raise `CorruptStreamException`.
Native text does not replace unrepresentable characters with question marks.

### Multiple values in native text chunks

WAV and RF64 write each non-empty modelled RIFF INFO value in a separate
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

### Encoding software and credits

`TagField.ENCODER` identifies the encoding software or hardware.
`ENCODED_BY` identifies the person or organisation that encoded the file.
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

Vorbis and APE also recognise `ENCODED-BY` and `ENCODED_BY` as human
credits, and `ENCODING` as a combined encoder/settings value. RIFF input
recognises `ITCH` as another human-credit convention.
The default adds ID3 alongside RIFF INFO when explicit settings need it.

Earlier versions exposed ID3 `TENC` as `ENCODER` and `TSSE` as
`ENCODING_SETTINGS`. Read the source tags again when migrating stored
merged bundles so those values receive their corrected fields.
Files written with the earlier mappings may already contain a software
name in `TENC`. Move that known value from `ENCODED_BY` to `ENCODER`
when repairing such a file. Reading follows the native frame's meaning
and does not guess from the text whether a value names a person or program.

### Unknown Vorbis comments

FLAC and Ogg keep unmodelled Vorbis comments in their stored order, including
repeated entries, key spelling and empty values. Rewriting those comments
performs work proportional to their count and text bytes. The merged fields
still omit empty values and combine repeated modelled values as described above.

The vendor header is separate from user comments. The raw view exposes it as
its first `VENDOR` entry, with internal provenance that survives ordinary edits
and reconstruction of a block around the same entries. It is never emitted as
a user comment. Actual user comments named `VENDOR` remain intact, including
caller-constructed entries with the same text as the header. FLAC and Ogg tag
rewrites preserve the source header vendor, including an empty string.

Modelled fields, pictures and the six recognised ReplayGain/R128 names are
rebuilt from the edited bundle. Removing those values does not reintroduce
their old raw entries. Unmodelled names sharing the loudness prefixes remain
raw comments.

### ID3 values and MusicBrainz identifiers

ID3v2.4 stores each text field or user-text description in one frame, with
null-separated values. ID3v2.3 defines slash lists for artists, composers,
lyricists, original artists and original lyricists. Genres use the reference
grammar described below. Other v2.3 text fields and user-text frames require
one distinct value. Writing an unsupported list
or a literal slash in a slash-list value raises `UnsupportedFeatureException`.
Use `TagWriteOptions(id3Version = 4)` to preserve those values separately.

Read mapped user-text lists with `tags.all(field)`. The raw view has one entry
per decoded value, with the same frame key and description. Unmodelled raw
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
with recognised numeric codes, `RX` or `CR` are ambiguous and refused.
A numeric refinement after known v2.3 references remains literal.

The reader accepts the older bracket grammar in v2.4 tags for compatibility.
Writing a literal leading parenthesis uses the older `((` escape in both
versions, matching readers such as Mutagen. A v2.4 reader that treats every
parenthesised string as plain text may display the extra parenthesis.
Mutagen's default loading normalises genre text before `genres` parses it.
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

Compressed or encrypted frames keep their payloads and flags across edits
within the same ID3 version. Frames marked for removal when the tag changes
are discarded as requested by their flags. Other opaque frames cannot be
converted to another version or combined with a rewritten frame of the same
identifier, including `PRIV`, `APIC`, `COMM`, `UFID` and `TXXX`. The writer
raises `UnsupportedFeatureException` before emitting output, so an unreadable
identity cannot be silently replaced. Discarding such metadata requires
explicitly removing its raw entry or clearing the tags before rebuilding them.

### Editing ID3 chapters

Parsed ID3 chapters retain their stored subframes independently of `TagBundle.raw`.
An unrelated edit in the same ID3 version preserves their order, payloads and flags.
This includes subtitles, URLs, comments, private data and unknown frames.
A subframe marked for discard when the tag changes is removed as its flag requires.

Use `TagChapter.copy` to edit the modelled values while retaining other subframes:

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
It removes storage-only unsynchronisation and data-length prefixes after validating and decoding them.
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
Chapter parsing limits nesting to sixteen levels and honours `DecodeOptions.maxAllocationBytes`.

### Pictures

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

### Loudness

Two conventions exist and they are five decibels apart. ReplayGain 2 measures
against -18 LUFS; Opus writes `R128_TRACK_GAIN` against -23 LUFS. AudioKodec
reports both as decibels and says which was found, so applying one as the other
is not a mistake you can make by accident.

```kotlin
tags.replayGain?.let { gain ->
    val reference = if (gain.isR128) -23.0 else -18.0
    applyGain(gain.trackGainDb, reference)
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

## Channel layouts

An `AudioFormat` carries the speaker position of every channel, in the order
the samples are interleaved. Files that state their layout, such as a WAV with
a channel mask, keep it through decoding, and AudioKodec writes the mask back
out for files with more than two channels.

```kotlin
val format = AudioFormat(48_000, ChannelLayout.SURROUND_5_1)
println(format.channelLayout.channels)  // [FRONT_LEFT, FRONT_RIGHT, ...]
```

`Pcm.remapChannels` reorders samples between two layouts that name the same
channels.

## Handling bad input

Every failure AudioKodec reports is an `AudioKodecException`:

| Exception | Meaning |
|---|---|
| `UnsupportedFormatException` | The format was recognised and has no decoder here |
| `UnsupportedFeatureException` | The format is supported and this corner of it is not |
| `CorruptStreamException` | The file contradicts its own format |
| `TruncatedStreamException` | The file ends in the middle of something |
| `AllocationGuardException` | A header asked for more memory than the options allow |

Nothing else escapes a decoder, whatever the input.

A truncated file throws by default. To salvage what is there instead:

```kotlin
AudioKodec.decode(bytes, DecodeOptions(tolerant = true))
```

For FLAC, tolerant decoding stops at the first damaged frame after valid
output, including inconsistent frame numbering or a frame extending beyond
the declared sample total. Float and integer reads retain the same complete
prefix. Damage before any valid output still throws.

MPEG Layers I, II and III also stop tolerant playback after the last complete
frame when decoding encounters damaged frame contents. Later frames are not
spliced across that boundary, and a damaged first frame still throws.

`DecodeOptions.maxAllocationBytes` caps what any single allocation may be, and
defaults to one gibibyte. The check happens before the allocation, not after
it fails.

ALAC packet scratch storage and predictor state are checked during probe and
open. Retained RIFF INFO and AIFF text entries are charged at an estimated
64 bytes per entry, including entries spread across separate INFO lists.
Empty native text values do not consume retained-entry space. These checks
bound individual buffers and collections, rather than total process memory.

## Reading and writing files

The core never touches the filesystem, which is what lets it build for every
Kotlin target. `audiokodec-io` adds the bridge:

```kotlin
val info = Path("song.wav").readAudioSource().use { AudioKodec.probe(it) }

Path("out.wav").writeAudioAtomically { sink ->
    AudioKodec.encode(samples.asPcmSource(format), EncodeTarget.wav(24), sink)
}
```

`writeAudioAtomically` reserves a staging directory beside the destination,
writes the temporary file, closes it and then uses kotlinx-io's atomic move to
replace the destination. Its parent directory must exist. A failure while
writing, closing or replacing preserves the old file, and temporary output is
removed. Cleanup errors are attached to the original failure. A cleanup error
after a successful replacement is reported with the new file already in place.
Atomicity follows the platform filesystem; WASI cannot verify that guarantee.

For direct streaming output, `Path.asAudioSink()` opens the file on its first
valid, non-empty write. Creating or closing an unused sink leaves it untouched.
Once writing begins, a later failure can leave partial output, so use the
atomic helper when replacing a file that must survive an unsuccessful encode.

Reading loads the file into memory, because decoding needs random access and
kotlinx-io offers sequential streams only. A sink cannot seek backwards
either, so encoding to one needs a `PcmSource` whose frame count is known in
advance.

For a large file, implement `AudioSource` over a seekable handle of your own.
It has three members, and the platform APIs behind it differ enough that
AudioKodec does not try to wrap them all.

## Playing the result

AudioKodec decodes and does not play. The result is Float32 PCM, which every
platform sink accepts:

| Platform | Sink | Notes |
|---|---|---|
| Android | `AudioTrack` | `ENCODING_PCM_FLOAT`, or convert with `Pcm.intFromFloat` for older devices |
| Apple | `AVAudioEngine` | `AVAudioPCMBuffer` with a non-interleaved or interleaved float format |
| JVM desktop | `javax.sound.sampled` | Convert to 16-bit with `Pcm.intFromFloat` first |
| Browser | Web Audio | `AudioBuffer.copyToChannel` takes float channels, so split with `Pcm.deinterleave` |

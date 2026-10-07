# AudioKodec

Audio containers, codecs and tags written in Kotlin for Kotlin Multiplatform:
read and write FLAC, WAV, AIFF and AU; decode FLAC, Vorbis, every MPEG audio
layer and the uncompressed family to Float32 PCM; edit tags without re-encoding
the audio; and run the same code on every target.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Targets](https://img.shields.io/badge/targets-22-blue)](#targets)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

**[Documentation](docs/)** · a guide per task, plus the format support table.

AudioKodec is at an early version. Audio covers MPEG Layers I, II and III,
Vorbis, FLAC both native and inside Ogg, and the uncompressed family. Tag
reading and editing covers ID3, APE, Vorbis comments and the metadata chunks of
RIFF, AIFF and Ogg, on any file. [What it reads and writes](#formats) is the whole list,
[PORTING_STATUS.md](PORTING_STATUS.md) has the detail behind it, and the
[open issues](https://github.com/yuroyami/AudioKodec/issues) are what comes next.
Nothing below is aspirational: every format in the tables has tests, and most
are checked against ffmpeg.

## Why

Kotlin Multiplatform has no library that decodes audio in common code. The
existing choices wrap each platform's own player, which gives you playback and
not samples, and gives you nothing at all on JS or WebAssembly. AudioKodec
decodes in `commonMain` with no dependency beyond `kotlin-stdlib`, so the same
call works on Android, iOS, the JVM, Linux, Windows, watchOS, in a browser and
in a WASI runtime.

## Install

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
// audio.samples is interleaved Float32, nominally -1.0 to 1.0
```

## Read a header without decoding

`probe` reads headers and nothing else, so scanning a folder costs a few
kilobytes per file rather than a full decode.

```kotlin
val info = AudioKodec.probe(bytes)
println(info.container.displayName)   // WAV
println(info.codec.displayName)       // PCM
println(info.durationFrames)          // 4410000
println(info.bitDepth)                // 16
```

## Stream and seek

Whole-file decoding allocates the whole file. For anything long, pull blocks
instead. Seeking is sample accurate: the decoder lands on the frame you asked
for, not on the block that contains it.

```kotlin
AudioKodec.open(source).use { decoder ->
    decoder.seek(decoder.durationFrames!! / 2)
    while (true) {
        val block = decoder.nextFrame() ?: break
        play(block.samples, block.frameCount)
    }
}
```

`DecodeOptions(reuseBuffers = true)` removes almost all allocation from that
loop, at the cost of each block staying valid only until the next one.

## Write a file

```kotlin
val wav = AudioKodec.encode(samples, AudioFormat(44_100, 2), EncodeTarget.wav(16))
val small = AudioKodec.encode(samples, format, EncodeTarget.wavImaAdpcm())
val exact = AudioKodec.encode(samples, format, EncodeTarget.wavFloat(32))
```

Decode once to write several formats from the same samples:

```kotlin
val decoded = AudioKodec.decode(bytes)
val wav = AudioKodec.encode(decoded, EncodeTarget.wav(16))
val flac = AudioKodec.encode(decoded, EncodeTarget.flac(16))
```

Encoding a `DecodedAudio` reads all its current samples from the beginning
and preserves its direct-read cursor. Direct `read` calls advance that cursor;
call `rewind()` to repeat direct reading. Other `PcmSource` implementations
are consumed by encoding and need a fresh source for another operation.
The sample array is shared, so avoid changing it while an encoding runs.

Reducing the depth of a float or a deeper integer source is where dither
belongs:

```kotlin
AudioKodec.encode(samples, format, EncodeTarget.wav(16, dither = Dither.Tpdf()))
```

The source channel layout is never silently relabelled. WAV PCM and float
preserve any positional layout that follows WAV's speaker-bit order, including
custom mono and stereo masks. AIFF and AU store only a channel count, compressed
WAV has no mask in this writer, and FLAC fixes the layout for each channel
count; encoding rejects a non-representable layout before writing any bytes.

Passing a `TagBundle` to `encode` writes it with the audio. WAV and AIFF use
their native text chunks plus ID3v2.4 for Unicode and richer fields, while FLAC
uses Vorbis comments and picture blocks. AU has no structured tag format and
rejects a non-empty bundle instead of silently dropping it.

FLAC encoding and tag edits store artwork in native `PICTURE` blocks.
See the [artwork guide](https://yuroyami.github.io/AudioKodec/#pictures) for
picture properties and size limits.

## FLAC

FLAC decodes bit-exactly and encodes to files the reference decoder accepts.

```kotlin
val flac = AudioKodec.encode(samples, format, EncodeTarget.flac(bitDepth = 16, effort = 5))
val audio = AudioKodec.decode(flac)
```

`effort` runs from 0 to 8 and trades encoding time against size, the same way
the reference encoder's numbered levels do. It never changes what comes back
out, because the format is lossless at every setting.

Every FLAC file carries an MD5 of its own audio, which makes a decoder
checkable against whichever encoder wrote the file:

```kotlin
when (AudioKodec.verifyFlacMd5(bytes)) {
    true -> println("the audio matches the hash the encoder stored")
    false -> println("this file has been damaged")
    null -> println("this file states no hash")
}
```

Seeking works with or without a seek table. Without one the frames are scanned,
and both checksums in every frame are verified, so a false sync word is rejected
rather than decoded as audio.

## Ogg

The Ogg container is read in full: pages and their checksums, packets
reassembled across pages, files carrying several logical streams at once, and
files chained one after another.

Vorbis, Opus and FLAC inside Ogg all decode, seeking included. Speex is
identified and its headers, length and tags are read; its audio does not
decode yet, and `open` says so by name rather than failing obscurely.

Opus decodes the whole format: the SILK speech codec, the CELT music codec, the
hybrid of the two, the joins between them, concealment of lost packets, and
every channel mapping, from mono through 7.1 surround to ambisonics. The
pre-skip and end trim are applied, so the samples come out where they went in
and one file follows another without a gap. Every layer is checked against
libopus, sample for sample.

## MP4

MP4, M4A, M4B, 3GP and QuickTime files are read: the audio track's packets
from its sample tables, wherever the `moov` box sits and in fragmented files
too, the edit list that says which part of the track plays, and chapters from
either a chapter track or a Nero `chpl` list. Every packet is found where
ffmpeg finds it.

ALAC, Opus, FLAC and QuickTime's uncompressed codings decode, seeking
included. AAC, MP3, AC-3 and AMR are named, with their length and chapters, and
`open` refuses them by name.

## Apple Lossless

ALAC decodes from MP4 and from CAF, at 16, 20, 24 and 32 bits and with one to
eight channels, reordered from ALAC's own speaker order into AudioKodec's, and
exact integers are available through `IntPcmAccess`. It is translated from
Apple's Apache-2.0 reference, and every file that reference encodes decodes to
exactly the samples that went in.

Vorbis decodes the whole format: both floor and residue kinds an encoder emits,
channel coupling, block switching, and up to eight channels reordered into
AudioKodec's own channel order rather than Vorbis's. It is checked against
libvorbis, sample for sample, at every quality level and every channel count.
The one thing left out is floor type 0, which no encoder has produced since the
format's 2001 beta releases; a file using it is named and refused rather than
guessed at.

```kotlin
val info = AudioKodec.probe(oggBytes)
println(info.codec.displayName)      // Opus
println(info.durationSeconds)        // 213.5
println(AudioKodec.readTags(oggBytes)[TagField.TITLE])
```

Tags can be rewritten in any single-stream Ogg file. The header pages are
rebuilt and the pages after them renumbered, with the audio packets copied
untouched.

## MPEG audio

MP3 and its two older layers have no file header at all: a file is a run of
frames, each stating its own bitrate and sample rate, with tag blocks in front
and behind that are not audio. AudioKodec reads that layer in full and decodes
all three layers.

```kotlin
val info = AudioKodec.probe(mp3Bytes)
println(info.codec.displayName)      // MP3
println(info.durationSeconds)        // 213.5, with the encoder's padding off
println(info.isVariableBitrate)      // true
```

The length is exact rather than estimated. A constant bitrate file divides out,
a variable bitrate one carries its frame count in a `Xing` or `VBRI` block
inside a silent first frame, and where LAME wrote its gapless numbers the
samples of silence it added at each end are taken off, so one track follows
another with no gap.

A sync word turns up in ordinary audio data every couple of kilobytes, so a
candidate frame is only believed once the frames after it agree with it. Files
using the free format, whose frames state no length, are named and refused.

All three layers decode. Layer III is the one people mean by MP3, and it is the
awkward one: a frame's audio is not inside that frame but scattered up to 511
bytes earlier in a shared pool of bits, which is what lets a hard passage spend
more than one frame can hold. Seeking accounts for it by decoding a few frames
before the target and discarding them, so a seek lands on the sample it was
asked for rather than near it.

```kotlin
AudioKodec.open(mp3Bytes).use { decoder ->
    decoder.seek(44_100)                 // one second in, to the sample
    val block = decoder.nextFrame()      // 1152 frames of Float32
}
```

Measured against ffmpeg's own decoders, the output agrees to about one part in a
million for Layer III and to the same for Layers I and II. Note that ffmpeg
ships two decoders for the older layers and picks its fixed point one by
default; the comparison above is against its float decoder, because its two
disagree with each other by far more than either disagrees with AudioKodec.

## Read and edit tags

Tags work on any file, including the ones AudioKodec cannot decode. A tag block
does not need a decoder, so an MP3 or a FLAC has its tags read today.

```kotlin
val tags = AudioKodec.readTags(bytes)
println(tags[TagField.TITLE])
println(tags[TagField.ARTIST])
println(tags.number(TagField.TRACK_NUMBER))
tags.cover?.let { showCover(it.bytes, it.mimeType) }
```

A file with several tag blocks gets them merged, with the richer format
winning any field the two disagree on. Everything stays available as it was
stored:

```kotlin
println(tags.raw.formats)                      // [ID3v2.4, ID3v1]
println(tags.raw.blocksOf(TagFormat.ID3V1))    // the older block, untouched
```

Editing rewrites the tag blocks and copies the audio across byte for byte, so
it is safe on a lossy file that would lose quality if it were decoded and
encoded again:

```kotlin
val edited = tags.edit {
    set(TagField.TITLE, "New title")
    set(TagField.TRACK_NUMBER, 4)
    remove(TagField.COMMENT)
}
val updated = AudioKodec.writeTags(bytes, edited)
```

To discard all entries in the tag bundle, including private entries and
unknown fields, use `tags.edit { clear() }` and write the result. You can set
new fields after `clear()` to create a fresh tag without preserving previous entries.

Tags are read from ID3v2.2, 2.3 and 2.4, ID3v1 and v1.1, APEv1 and APEv2,
Vorbis comments in both FLAC and Ogg, FLAC picture blocks, Lyrics3v2, the
`LIST INFO` and `bext` chunks of a WAV, the text chunks of an AIFF, and an
`id3 ` chunk inside either. Writing produces ID3v2.3 or 2.4, Vorbis comments and
pictures in FLAC and Ogg, an updated ID3v1 block where the file already had one,
and the metadata chunks of RIFF and AIFF. MP4 atoms arrive with the MP4
container.

Undeclared RIFF INFO uses UTF-8, and edits honour supported `CSET` declarations.
AIFF native output uses ASCII; Unicode output uses ID3. Native reads also accept
UTF-8 and legacy Latin-1 text. Unsupported declarations and unrepresentable native-only
requests produce named errors. See [native text encodings](docs/index.md#native-text-encodings).

Default edits add ID3 to WAV, RF64 and AIFF when their native text cannot
retain the requested metadata. Explicit native-only selections name the
unrepresentable fields and components and refuse before output. URLs use
`WEBSITE` in Vorbis comments and `Weblink` in APE. Standalone track and disc
totals are retained without inventing a number. FLAC, Ogg and APE refuse
chapters unless another complete ID3 carrier is being written in the file.
See [the tag writing rules](docs/index.md#metadata-that-needs-a-richer-carrier).

Parsed ID3 chapters retain unmodelled subframes across unrelated edits.
Use `TagChapter.copy` to change a title, timing or pictures while keeping that metadata.
Version conversion names and refuses subframes it cannot preserve safely before output.
See [editing ID3 chapters](docs/index.md#editing-id3-chapters).

The primary `DATE` field retains ID3 `TDRC`. An explicit `TDRL` release
timestamp uses `RELEASE_DATE`, so editing or removing either date leaves the
other intact. Split legacy dates retain their month, day and minute precision
when upgraded. Version 2.3 refuses date precision its native fields cannot
represent. See [date fields](docs/index.md#primary-and-explicit-release-dates)
for native keys and migration from previously merged date lists.

MusicBrainz recording IDs use `UFID:http://musicbrainz.org` in ID3; the other
MusicBrainz IDs use Picard's `TXXX` descriptions. ID3v2.4 stores multiple text
values in one frame. ID3v2.3 uses slash lists for artists, composers and
lyricists, and refuses lists or literal slashes it cannot preserve
unambiguously. Use `TagWriteOptions(id3Version = 4)` for those values.
ID3 genre references and refinements become separate `all(TagField.GENRE)`
values. V2.3 writes known genre references followed by one optional free-text
refinement; v2.4 supports separate free-text genres. See the docs for genre
escaping and reserved-code limits.

## Name a format

The sniffer names 29 formats, including those AudioKodec cannot decode. That is
deliberate: a file it cannot read is one you can route somewhere else, and
guessing from the extension is worse.

```kotlin
val container = AudioKodec.sniff(firstBytes)   // AudioContainer.FLAC
if (container?.isDecodable == true) { /* AudioKodec can read it */ }
```

The result is null when the supplied bytes do not identify a format, including
when leading ID3 metadata hides the audio header. ID3 alone does not identify
MPEG audio. `SNIFF_HEADER_BYTES` recommends an initial 64 KiB window; larger
tags can require more bytes. Supply more bytes or use `probe` with a complete
source. See [sniffing windows](docs/index.md#name-a-format).

## Formats

### Read and write

| Container | Codings |
|---|---|
| FLAC | FLAC, 8 to 32-bit on read and 8, 16 or 24-bit on write |
| Ogg | Vorbis, Opus and FLAC. Speex has its headers, length and tags read, and its audio is not decoded yet |
| WAV (RIFF), including WAVE_FORMAT_EXTENSIBLE | PCM 8/16/24/32-bit, IEEE float 32/64-bit, A-law, mu-law, IMA ADPCM, Microsoft ADPCM |
| AIFF and AIFF-C | PCM 8/16/24/32-bit big-endian, A-law, mu-law, and on write IEEE float 32/64-bit |
| AU | PCM 8/16/24/32-bit big-endian, IEEE float 32/64-bit, A-law, mu-law |

RF64 and BW64 are read, and written automatically when a file passes 4 GiB.

On reading, the AIFF-C compression types understood are `NONE`, `twos`,
`sowt`, `raw `, `in24`, `in32`, `23ni`, `42ni`, `fl32`, `fl64`, `alaw`, `ulaw`
and `ima4`. The reversed integer types are little-endian: `23ni` is 32-bit
and `42ni` is 24-bit.

### Read only

| Container | Codings |
|---|---|
| Wave64 | PCM 8/16/24/32-bit, IEEE float 32/64-bit, A-law, mu-law |
| CAF | PCM 8/16/24/32-bit in both byte orders, IEEE float 32/64-bit, A-law, mu-law, QuickTime IMA ADPCM, ALAC 16/20/24/32-bit |
| MPEG audio | Layers I, II and III (MP1, MP2 and MP3), with gapless trimming and sample-accurate seeking |
| MP4, M4A, M4B, 3GP and QuickTime | ALAC 16/20/24/32-bit, Opus, FLAC, PCM 8/16/24/32-bit in both byte orders, IEEE float 32/64-bit, A-law, mu-law. AAC, MP3, AC-3 and AMR are named and not decoded yet |

### Named but not decoded

AAC in both ADTS and ADIF framing, Matroska and WebM, Monkey's
Audio, WavPack, Musepack, True Audio, DSF, DSDIFF, AMR, AC-3, DTS, ASF and WMA,
MPEG-TS, MIDI, Shorten, OptimFROG and TAK.

Sniffing is not decoding. `probe` and `open` throw
`UnsupportedFormatException` for all of these, and the message names the
format.

## Precision

Decoding produces Float32, which carries 8-, 16- and 24-bit audio exactly. It
cannot carry 32-bit integers exactly, because a Float32 has 24 bits of
mantissa. A decoder for a lossless coding therefore also implements
`IntPcmAccess`, which hands back the stored integers untouched:

```kotlin
AudioKodec.open(bytes).use { decoder ->
    if (decoder is IntPcmAccess) {
        val block = decoder.nextIntFrame()   // exactly what the file holds
    }
}
```

## Malformed input

Every failure is an `AudioKodecException`: `UnsupportedFormatException`,
`UnsupportedFeatureException`, `CorruptStreamException`,
`TruncatedStreamException` or `AllocationGuardException`. Nothing else escapes
a decoder, whatever the input, and a fuzz suite holds that claim up.

A truncated file throws by default and can be salvaged instead:

```kotlin
AudioKodec.decode(bytes, DecodeOptions(tolerant = true))
```

`DecodeOptions.maxAllocationBytes` caps what a header can make AudioKodec
allocate. It defaults to one gibibyte, and the guard fires before the
allocation is attempted rather than after it fails.

## Targets

`audiokodec` builds for 22 targets: Android (minSdk 21), `jvm`, `iosArm64`,
`iosSimulatorArm64`, `iosX64`, `macosArm64`, `tvosArm64`,
`tvosSimulatorArm64`, `watchosArm32`, `watchosArm64`, `watchosDeviceArm64`,
`watchosSimulatorArm64`, `androidNativeArm32`, `androidNativeArm64`,
`androidNativeX64`, `androidNativeX86`, `linuxX64`, `linuxArm64`, `mingwX64`,
`js` (browser and Node), `wasmJs` (browser and Node), `wasmWasi` (Node). There
is no `macosX64`, following Kotlin's deprecation of Intel-Apple targets.

`audiokodec-io` builds for the same set without `js`, which is what kotlinx-io
publishes.

## Reading and writing files

The core never touches the filesystem, so it ports everywhere. `audiokodec-io`
adds the bridge to kotlinx-io:

```kotlin
val info = Path("song.wav").readAudioSource().use { AudioKodec.probe(it) }
Path("out.wav").writeAudioAtomically { sink ->
    AudioKodec.encode(samples.asPcmSource(format), EncodeTarget.wav(24), sink)
}
```

Reading loads the file into memory, because decoding needs random access and
kotlinx-io offers sequential streams only. For large files, implement
`AudioSource` over a seekable handle of your own: it has three members.

`writeAudioAtomically` writes to a temporary file beside the destination and
replaces it after the write and close succeed, using kotlinx-io's atomic move.
The parent directory must exist and an encoder needs a known frame count.
Atomicity depends on the filesystem; WASI cannot verify that guarantee.
`Path.asAudioSink()` opens on its first valid, non-empty write. An unused sink
preserves the file, while a failure after writing begins can leave partial
output.

## License

Apache-2.0. See [LICENSE](LICENSE).

Every format here was implemented from its published specification.
[NOTICE](NOTICE) lists those specifications, and
[reference/REFERENCES.md](reference/REFERENCES.md) records what may and may
not be used as the library grows.

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[ImageKodec](https://github.com/yuroyami/ImageKodec),
[KitePDF](https://github.com/yuroyami/KitePDF),
[ArchiveKodec](https://github.com/yuroyami/ArchiveKodec),
[KiteQR](https://github.com/yuroyami/KiteQR).

# AudioKodec

Audio containers, codecs and tags written in Kotlin for Kotlin Multiplatform:
read and write FLAC, WAV, AIFF and AU; decode FLAC, Vorbis, every MPEG audio
layer and the uncompressed family to Float32 PCM; edit tags without re-encoding
the audio; and run the same code on every target.

<sub>AI tools wrote much of the code, tests and documentation in this project, under the
direction and review of its author.</sub>

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
// audio.samples is interleaved Float32, nominally -1.0 to 1.0
```

## Inspect a file

`probe` reads metadata and index tables. Its work depends on the format: Ogg
walks the pages through the stream end, reading payloads to check their
checksums. MPEG without a stored Xing or VBRI frame count scans frame headers
across the file for an exact duration. These paths can read the whole file;
opening repeats the same page walk before playback. Tolerant native FLAC
inspection also decodes frames when a nonzero first frame identifies a
stream-copy cut, so its duration describes the remaining complete audio.

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

FLAC can store an MD5 of its audio, which makes a decoder checkable against
whichever encoder wrote the file:

```kotlin
when (AudioKodec.verifyFlacMd5(bytes)) {
    true -> println("the audio matches the hash the encoder stored")
    false -> println("this file has been damaged")
    null -> println("there is no hash for the complete audio in this file")
}
```

Use `DecodeOptions(tolerant = true)` to read a FLAC stream-copy cut whose
frame numbers retain a nonzero origin. Positions and seeks start at zero;
duration covers the remaining valid frames. Its original MD5 is unavailable.

Seeking works with or without a seek table. For a source of known size, an
unindexed seek bisects CRC-8-checked frame headers and verifies the target
frame's CRC-16 before playback. Unknown-size sources and ambiguous embedded
headers use a verified forward frame walk. A seek to the known end reads no audio.

## Ogg

The Ogg container is read in full: pages and their checksums, packets
reassembled across pages, files carrying several logical streams at once, and
files chained one after another.

Vorbis, Opus, FLAC and Speex inside Ogg all decode, seeking included.

Tags can be rewritten in any of them, including a file that interleaves
several logical streams, whose other streams are copied byte for byte.

[Compatible chained links](docs/ogg-chains.md) play in sequence, with each
link's own decoder state and trimming. Duration and seek positions cover the
complete recording. A change in sample rate or channel layout is refused
before playback starts.

Positive stream origins are subtracted from length, and positions and seeks
start at zero. A Vorbis stream whose only usable granule mixes a positive
origin with end trim is refused with `UnsupportedFeatureException`.

Opus decodes the whole format: the SILK speech codec, the CELT music codec, the
hybrid of the two, the joins between them, and every channel mapping, from
mono through 7.1 surround to ambisonics. Its codec core supports lost-packet
concealment and recovery from redundant packet data. Ogg playback in tolerant
mode stops at the first damaged page or packet boundary and reports the
complete prefix's length. The pre-skip and end trim are applied, so the samples come out where they went in
and one file follows another without a gap. Every layer is checked against
libopus, sample for sample.

## MP4

MP4, M4A, M4B, 3GP and QuickTime files are read: the audio track's packets
from its sample tables, wherever the `moov` box sits and in fragmented files
too, the edit list that says which part of the track plays, and chapters from
either a chapter track or a Nero `chpl` list. Without an edit list that trims
the track, the iTunes `iTunSMPB` gapless record is applied. Every packet is
found where ffmpeg finds it.

Packet ranges are checked against media-data payloads before decoding, and
external data references are named and refused.

ALAC, Opus, FLAC, MPEG Layers I, II and III, and QuickTime's uncompressed codings
decode, seeking included. [MPEG tracks](docs/mpeg-in-mp4.md) share the bare-stream
frame decoder and apply the MP4 edit list. AAC-LC decodes as described [below](#aac), AC-3 as described [below](#ac-3), and AMR as
described [below](#amr).

The iTunes item list inside `moov` is read and written, cover art included,
whatever the coding. A rewrite rebuilds only the `moov` box and copies the
audio byte for byte. See [MP4 metadata](docs/mp4-tags.md).

## Matroska and WebM

Matroska and WebM files decode from their first audio track: Opus, Vorbis,
FLAC, MPEG audio, AAC-LC, AC-3 and PCM, through the same decoders as in their own
containers. The length is counted frame by frame, with Opus's pre-skip and
end trim applied from CodecDelay and DiscardPadding, and agrees with ffmpeg's
decode of the same file; seeking is exact. Live files of unknown size, all
three lacing schemes, and zlib and header-stripped frames are read. Tags map
from the Matroska tag names and chapters come from the first edition. See
[Matroska and WebM](docs/matroska.md).

## Apple Lossless

ALAC decodes from MP4 and from CAF, at 16, 20, 24 and 32 bits and with one to
eight channels, reordered from ALAC's own speaker order into AudioKodec's, and
exact integers are available through `IntPcmAccess`. It is translated from
Apple's Apache-2.0 reference. Independent comparisons require the exact input
integers at 16, 24 and 32 bits with one to eight channels, and at 20 bits with
mono, stereo and 5.1 audio.

Vorbis decodes floor types 0 and 1 and all three residue kinds,
channel coupling, block switching, and up to eight channels reordered into
AudioKodec's own channel order rather than Vorbis's. It is checked against
libvorbis at qualities 0, 4 and 9 in mono and stereo, plus a 5.1 comparison,
with a maximum sample difference below 1e-4.
[Floor type 0](docs/vorbis-floor-zero.md), used by the early beta encoders,
is checked through synthetic streams, independent decoders and the
specification's curve equations.

```kotlin
val info = AudioKodec.probe(oggBytes)
println(info.codec.displayName)      // Opus
println(info.durationSeconds)        // 213.5
println(AudioKodec.readTags(oggBytes)[TagField.TITLE])
```

Tags can be rewritten in a single-stream Ogg file whose final header packet
ends its page. The header pages are rebuilt and the pages after them renumbered,
with the audio packets copied untouched. A shared header/audio page is refused
before any output, including in tolerant mode.

## AAC

AAC-LC decodes from MP4 and from raw ADTS streams, at every sampling rate the
standard tabulates, from 7.35 to 96 kHz, and with any number of channels a
fixed configuration or a program configuration describes. Fixed configurations
come out in AudioKodec's speaker order. Every tool of the low complexity profile is
implemented: mid/side and intensity stereo, noise substitution, temporal noise
shaping, pulses, and block switching between sine and Kaiser-Bessel windows.
It is written from ISO/IEC 14496-3 in floating point, as the standard
describes it. The MP4 edit list trims the encoder's delay and padding, and
seeking runs one block ahead of its target, which makes it sample-exact.

Wherever the spectrum is coded, ffmpeg's decoder agrees with AudioKodec's to
within float rounding. Their largest difference is below 2e-6 and the signal
to difference ratio is above 120 dB. These comparisons cover ffmpeg's encoder
at every rate table, mono to 7.1, and program configurations, in both ADTS and
MP4. Noise substitution draws random numbers the standard leaves open, so
there the energy of every block is compared instead.

HE-AAC, whose spectral band replication rebuilds the upper half of the
spectrum, is refused by name rather than played as its muffled, half-rate
core. The refusal covers explicit signalling in the MP4 configuration and
implicit signalling in the stream. AAC Main, SSR and LTP, the 960-sample
frame and coupling channel elements are refused by name too. See
[AAC](docs/aac.md).

## AC-3

AC-3, Dolby Digital, decodes from raw `.ac3` streams, from MP4 and QuickTime
tracks and from Matroska tracks, at 32, 44.1 and 48 kHz, in every coding mode
from dual mono to 3/2 with the low frequency channel. It is written from ATSC
A/52:2012 in floating point, as the standard describes it, with the bit
allocation in the integer arithmetic the standard gives it. Exponent
strategies, delta bit allocation, channel coupling with its phase flags,
rematrixing, block switching channel by channel and the dynamic range word
are all implemented. Channels come out in AudioKodec's speaker order, nothing
is mixed down, and the stream's length is its syncframes counted. Seeking runs
one syncframe ahead of its target, which makes it sample-exact.

ffmpeg's encoder writes none of block switching, delta bit allocation, phase
flags or dual mono, so the test suite also decodes frames of random choices of
every element and compares them with ffmpeg's decode: with the 24-bit
fixed-point rounding of ffmpeg's decoder reproduced, the two agree to float
rounding, and on encoder-written files without it they differ by under
1.5e-5. Dither is the one place the standard leaves open: a mantissa given no
bits is replaced by noise, so a file that sets the dither flags differs from
ffmpeg's decode by noise of the same energy. Enhanced AC-3, which shares the
sync word, is refused by name. See [AC-3](docs/ac3.md).

## WavPack

WavPack decodes in every mode its encoder has, from fast to very high with
the extra filter searches, at 8 to 32 bits including 20-bit audio and
32-bit float. Multichannel audio comes from its runs of mono and stereo
blocks into AudioKodec's speaker order, by the file's channel mask. Lossless
files give exact integers through `IntPcmAccess`, and every block's checksum
is verified. Hybrid files decode to their lossy stream; the correction file
that would make them lossless is not read. Blocks stand alone, so seeking is
exact and needs no run-up. The decoder is translated from the reference
library, WavPack 5.9.0, BSD-licensed.

Every file the reference encoder writes in the test suite decodes to exactly
the integers that went in, and to exactly what `wvunpack` gives. The suite
covers each mode and depth, mono to 7.1, false stereo and silence, and
ffmpeg's own WavPack files. Hybrid files must match `wvunpack` integer for
integer too. DSD audio inside WavPack is refused by name, and files from
before version 4.0, which begin as WAV files, are not read. See
[WavPack](docs/wavpack.md).

## Monkey's Audio

Monkey's Audio decodes at every compression level, fast, normal, high,
extra high and insane, at 8, 16, 24 and 32 bits and as 32-bit float, with
one to eight channels except seven. Lossless integer files give exact
integers through `IntPcmAccess`, and every frame's checksum is verified.
Frames stand alone, so seeking is exact and needs no run-up. The decoder is
translated from the Monkey's Audio SDK 13.27, BSD-licensed.

Every file the SDK's own `mac` tool writes in the test suite decodes to
exactly the integers that went in, and to exactly what `mac -d` gives, at
every level and depth. Files from encoders older than version 3.99, from
2004, are refused by name, as are seven channels, which the SDK cannot decode
either. See [Monkey's Audio](docs/ape.md).

## Musepack

Musepack decodes in its current stream format, version 8, which its encoder
has written since 2009: every quality from the lowest to the highest, mono
and stereo, mid/side coding and noise substitution, with the exact length the
stream header states, so the encoder's delay and its padding are gone.
Seeking lands on exactly the samples a continuous decode gives, with one
exception: noise substitution draws from a generator that runs through the
whole stream, so noise after a seek is different noise. The decoder is
translated from libmpcdec, BSD-licensed, and works in its single-precision
arithmetic. On the JVM every sample is the float the reference decoder
gives, which the test suite checks on files of every quality. Stream
version 7 and older are refused by name. See [Musepack](docs/musepack.md).

## Speex

Speex inside Ogg decodes in all three of its modes, narrowband,
wideband and ultra-wideband, at every quality, with variable bitrate,
discontinuous transmission, several frames to a packet, and in-band stereo.
It decodes as `speexdec` does, with the perceptual enhancer on, and to the
same length, trimmed at both ends exactly as `speexdec` trims. The decoder is
translated from libspeex 1.2.1, BSD-licensed, in its floating-point build's
arithmetic, and on the JVM every sample is the float libspeex gives, which
the test suite checks in every mode. Seeking restarts the decoder a short
run-up before the target, so the samples after a seek converge on a
continuous decode rather than equalling it; a seek to the start is exact.
See [Speex](docs/speex.md).

## AMR

AMR narrowband and AMR wideband decode at every rate each defines, from
`.amr` and `.awb` files, including the multichannel variant, and from the 3GP
and MP4 files phones record voice memos and MMS attachments in.
Discontinuous transmission is decoded into comfort noise, frames that carry
no data are concealed, and AMR-WB's decoder homing frames reset it. The
decoders are translated from opencore-amr, Apache-licensed, and keep its
fixed-point arithmetic, so on every target every sample is exactly the
integer opencore-amr gives, which the test suite checks at every rate.
Seeking decodes a half-second run-up before the target and converges on a
continuous decode; a seek to the start is exact. See [AMR](docs/amr.md).

## GSM 06.10

GSM 06.10, the original mobile telephone speech coding, decodes from WAV
files in Microsoft's two-frame blocks and from QuickTime and MP4 files in its
plain frames. The decoder is translated from libgsm and keeps its fixed-point
arithmetic, so every sample is exactly the integer libgsm gives, on every
target. Seeking decodes a half-second run-up and converges on a continuous
decode; a seek to the start is exact. See [GSM 06.10](docs/gsm.md).

## iLBC

iLBC, the Internet Low Bitrate Codec of older voice over IP systems, decodes
in its 20 and 30 ms frame sizes from `.lbc` files in the storage format of
RFC 3952 and from CAF and QuickTime files. The decoder is translated from
WebRTC's and keeps its fixed-point arithmetic and its enhancer, so every
sample is exactly the integer WebRTC gives, on every target, and frames a
sender marked empty are concealed as WebRTC conceals them. Seeking is exact.
See [iLBC](docs/ilbc.md).

## G.722 and G.726

G.722 wideband and G.726 narrowband ADPCM, the ITU telephony codings, decode
from WAV and AU files. Both are written from the Recommendations, in their
integer arithmetic, and reproduce every output word of the ITU's own test
sequences; G.722 files ffmpeg writes decode to exactly ffmpeg's samples in all
three modes. ffmpeg's G.726 does not follow the Recommendation, so its
samples differ, badly at 40 kbit/s. Seeking is exact. See
[G.722 and G.726](docs/g722-g726.md).

## DSD

DSD, the one-bit signal of the Super Audio CD, is read from DSF and DSDIFF
files and converted to PCM at a thirty-second of its rate, 88.2 kHz from
DSD64, through three linear-phase decimating filters that pass the audio band
flat within 2e-5 dB and keep anything from aliasing into it. There is no one
correct conversion; the filter is stated in full, and ffmpeg's own conversion
agrees tone by tone within the difference between the two filters. Channels
come out in AudioKodec's order, the ID3v2 tags of both containers are read,
and seeking is exact. DST-compressed DSDIFF is refused by name. See
[DSD](docs/dsd.md).

## True Audio

True Audio decodes at 8 to 24 bits, including 20-bit audio, with any number
of channels and at any sample rate, with exact integers through
`IntPcmAccess`. The header, the seek table and every frame are checked
against their CRCs. Frames stand alone, so seeking is exact and needs no
run-up. The decoder is translated from the format authors' BSD-licensed
decoder for hardware players.

Every file the authors' `tta` tool and ffmpeg write in the test suite
decodes to exactly the integers that went in, and to exactly what `tta -d`
and ffmpeg give. Password-protected files are refused by name. See
[True Audio](docs/tta.md).

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

The length comes from a stored `Xing`, `Info` or `VBRI` frame count, or a walk
of complete frame headers when that count is absent. The silent metadata
frame is excluded, and LAME's encoder delay and padding are removed from the
playable duration.

A valid `Xing` or `Info` table also supports seeking with `allowSlowSeek = false`.
Its byte hints are refined into exact frame positions before decoder history
is primed. A far seek reads the remaining frame headers around the hint rather
than the whole preceding audio. See [MPEG seeking](docs/index.md#mpeg-audio)
for refinement costs, optional counts and invalid-table handling.

A sync word turns up in ordinary audio data every couple of kilobytes, so a
candidate frame is only believed once the frames after it agree with it.
[Free-format audio](docs/mpeg-free-format.md), whose headers omit the bitrate,
uses confirmed frame spacing and each frame's padding bit. All three layers
decode and report the implied bitrate.

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

The oracle tests compare with ffmpeg's floating-point decoders. They enforce
a maximum sample difference below 1e-4 for Layers I and III, and below 1e-5
for Layer II. FFmpeg also ships fixed-point decoders for the older layers;
these comparisons use its float decoders.
Free-format comparisons use libmpg123's public float API because the installed
FFmpeg rejects these files, with the same bounds and exact duration checks.

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
println(tags.raw.formats.map { it.displayName }) // [ID3v2.4, ID3v1]
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
`id3 ` chunk inside either, the item list of an MP4 file, and the tags and
chapters of a Matroska or WebM file. Writing produces
ID3v2.3 or 2.4, Vorbis comments and pictures in FLAC and Ogg, MP4 items and
cover art, an updated ID3v1 block where the file already had one, and the
metadata chunks of RIFF and AIFF. MP4 chapters are read from a chapter track or
a Nero `chpl` list, and changed chapters are written as `chpl`. See
[MP4 metadata](docs/mp4-tags.md).

Broadcast Wave writing is opt-in through `TagFormat.BWF_BEXT`. Its fixed fields
and unsigned 64-bit time reference use the raw tag view; ordinary edits preserve
the existing chunk. See [Broadcast Wave metadata](docs/broadcast-wave.md).

ID3v2.2 input upgrades to v2.4 by default, or v2.3 when requested. Standard
frame equivalents are retained; `CRM` and unknown three-character extensions
without a safe equivalent are omitted during upgrade. See [ID3 editing rules](docs/index.md#tags).

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

The sniffer names 30 formats, including those AudioKodec cannot decode. That is
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

Complete-source APIs also recover MPEG audio after leading junk or a partial
frame by confirming frames within the first 512 KiB.

## Formats

### Read and write

| Container | Codings |
|---|---|
| FLAC | FLAC, 8 to 32-bit on read and 8, 16 or 24-bit on write |
| WAV (RIFF), including WAVE_FORMAT_EXTENSIBLE | PCM 8/16/24/32-bit, IEEE float 32/64-bit, A-law, mu-law, IMA ADPCM, Microsoft ADPCM; GSM 06.10, G.722 and G.726 are read |
| AIFF and AIFF-C | PCM 8/16/24/32-bit big-endian, A-law, mu-law, and on write IEEE float 32/64-bit |
| AU | PCM 8/16/24/32-bit big-endian, IEEE float 32/64-bit, A-law, mu-law; G.722 and G.726 (Sun's G.721 and G.723) are read |
| MP4 and M4A | ALAC 16/20/24/32-bit with one to eight channels, written with packets identical to Apple's reference encoder. Other MP4 codings are read only; see below |

RF64 and BW64 are read, and written automatically when a file passes 4 GiB.

On reading, the AIFF-C compression types understood are `NONE`, `twos`,
`sowt`, `raw `, `in24`, `in32`, `23ni`, `42ni`, `fl32`, `fl64`, `alaw`, `ulaw`
and `ima4`. The reversed integer types are little-endian: `23ni` is 32-bit
and `42ni` is 24-bit.

### Read only

| Container | Codings |
|---|---|
| Ogg | Vorbis, Opus, FLAC and Speex, with up to 256 simultaneous logical streams. Tags can be rewritten; audio encoding is not implemented |
| Wave64 | PCM 8/16/24/32-bit, IEEE float 32/64-bit, A-law, mu-law |
| CAF | PCM 8/16/24/32-bit in both byte orders, IEEE float 32/64-bit, A-law, mu-law, QuickTime IMA ADPCM, ALAC 16/20/24/32-bit, iLBC |
| MPEG audio | Layers I, II and III (MP1, MP2 and MP3), with gapless trimming and sample-accurate seeking |
| Matroska and WebM | Opus, Vorbis, FLAC, MPEG Layers I, II and III, AAC-LC, AC-3, PCM 8 to 32-bit in both byte orders, IEEE float 32/64-bit, from the first audio track, with zlib and header-stripped frames. Tags and chapters are read |
| MP4, M4A, M4B, 3GP and QuickTime | AAC-LC, ALAC 16/20/24/32-bit, Opus, FLAC, MPEG Layers I, II and III, PCM 8/16/24/32-bit in both byte orders, IEEE float 32/64-bit, A-law, mu-law. AMR-NB and AMR-WB, GSM 06.10, iLBC, AC-3. Tags can be rewritten; of these codings only ALAC is encoded |
| ADTS | AAC-LC, with sample-accurate seeking |
| AC-3 | Every coding mode from dual mono to 3/2 with the low frequency channel, at 32, 44.1 and 48 kHz, with the exact length and sample-accurate seeking. Enhanced AC-3 is refused by name |
| WavPack | Lossless at 8 to 32 bits and 32-bit float, exact integers through `IntPcmAccess`, mono to multichannel; hybrid lossy without its correction file. APEv2 tags are read |
| Monkey's Audio | Every compression level, 8 to 32 bits and 32-bit float, exact integers through `IntPcmAccess`, one to eight channels except seven, from version 3.99 on. APEv2 tags are read |
| True Audio | 8 to 24 bits, exact integers through `IntPcmAccess`, any number of channels. ID3 and APEv2 tags are read |
| Musepack | Stream version 8, mono and stereo, at 32 to 48 kHz, with the exact length its header states. APEv2 tags are read |
| AMR-NB and AMR-WB | Every rate, single-channel and multichannel, with comfort noise and concealment, exactly as opencore-amr decodes them |
| iLBC | 20 and 30 ms frames in the storage format of RFC 3952, exactly as WebRTC's iLBC decodes them |
| DSF and DSDIFF | DSD at any rate, converted to PCM at a thirty-second of it, one to six channels in DSF and any number in DSDIFF. ID3v2 tags are read. DST compression is refused |

### Probed but not decoded

Raw AAC in ADIF framing. `probe` reports the sample rate, channels and
bitrate; ADIF states no frame boundaries, so its length is unknown without
decoding the whole stream. `open` refuses it by name.

### Named but not decoded

DTS, Enhanced AC-3, ASF and WMA, MPEG-TS, MIDI, Shorten, OptimFROG and TAK.

Sniffing is not decoding. `probe` and `open` throw
`UnsupportedFormatException` for all of these, and the message names the
format.

## Change the sample rate

`Resampler` converts float PCM from one rate to another a block at a time,
with no allocation after it is built, and `Pcm.resample` converts a whole
buffer. The default mode is speexdsp's windowed sinc resampler at its
highest quality, translated sample for sample: on a 1 kHz tone it adds
distortion and noise at least 134.8 dB below it. A straight-line mode trades
that for speed, for drawing previews. The output is aligned with the input,
with no delay. See [Change the sample rate](docs/resample.md).

```kotlin
val at48k = Pcm.resample(decoded.samples, channels = 2, inputRate = 44_100, outputRate = 48_000)
```

## Mix channels

`Pcm.mixChannels` converts float PCM between channel layouts: 5.1 down to
stereo or mono with the coefficients of ITU-R BS.775, which is what ffmpeg
gives too, and mono or stereo up without inventing anything. A conversion
with no defined answer is refused by name. Per-channel gain and mid-side
conversion sit beside it. See [Mix channels](docs/mix.md).

```kotlin
val stereo = Pcm.mixChannels(samples, frames, ChannelLayout.SURROUND_5_1, ChannelLayout.STEREO)
```

## Measure loudness

`LoudnessMeter` measures loudness as EBU R 128 defines it: integrated,
momentary and short-term loudness, loudness range, and sample and true peak,
a block at a time. It is a translation of libebur128 and matches it, and it
produces ReplayGain values the tag writers take, so a library scan and its
tag writes are one step. See [Measure loudness](docs/loudness.md).

```kotlin
val meter = LoudnessMeter(format.sampleRate, format.channelLayout)
meter.add(samples)
meter.finish()
val tags = AudioKodec.readTags(bytes).edit { setReplayGain(meter.replayGain()) }
```

## Draw a waveform

`WaveformReducer` reduces decoded audio to the minimum, maximum and root
mean square of each channel per bucket of frames, a block at a time, so a
long file streams through in bounded memory, and says how many buckets a
file gives before any audio is read. See [Draw a waveform](docs/waveform.md).

## Levels, fades and test signals

`Levels` scans a range for its peak, RMS and clipped samples, finds the
silence at its ends, applies a gain and fades in and out along a linear or
a logarithmic curve, and `Signals` generates sine, square, noise and
silence. See [Levels, fades and test signals](docs/levels.md).

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

Encode those samples through `IntPcmSource` or the integer-array overload:

```kotlin
val exact = AudioKodec.encode(integerSamples, format, bitDepth = 32, target = EncodeTarget.aiff(32))
```

Integer WAV, AIFF and AU preserve all 32 bits at equal depth; FLAC accepts
8, 16 and 24-bit targets. See [integer PCM encoding](docs/integer-pcm.md)
for streaming input, depth changes and dither rules.

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

`audiokodec` builds for 22 targets: Android (minSdk 21), `jvm` (Java 21 or later), `iosArm64`,
`iosSimulatorArm64`, `iosX64`, `macosArm64`, `tvosArm64`,
`tvosSimulatorArm64`, `watchosArm32`, `watchosArm64`, `watchosDeviceArm64`,
`watchosSimulatorArm64`, `androidNativeArm32`, `androidNativeArm64`,
`androidNativeX64`, `androidNativeX86`, `linuxX64`, `linuxArm64`, `mingwX64`,
`js` (browser and Node), `wasmJs` (browser and Node), `wasmWasi` (Node). There
is no `macosX64`, following Kotlin's deprecation of Intel-Apple targets.

`audiokodec-io` builds for the same targets, including JavaScript. The
`RawSource`/`RawSink` and `Buffer` bridges work in browsers and Node. Path and
filesystem operations require a host filesystem, such as Node's local files;
they are unavailable in a browser.

## Reading and writing files

The core never touches the filesystem, so it ports everywhere. `audiokodec-io`
adds the bridge to kotlinx-io:

```kotlin
val info = Path("song.wav").openAudioSource().use { AudioKodec.probe(it) }
Path("out.wav").writeAudioAtomically { sink ->
    AudioKodec.encode(samples.asPcmSource(format), EncodeTarget.wav(24), sink)
}
```

`Path.openAudioSource()` reads through a seekable handle on JVM/Android,
Native, Node.js 20.16 or later, and WASI with a preopened directory.
Memory stays bounded independently of file length. `Path.readAudioSource()`
and `RawSource.readAudioSource()` keep their whole-file buffering behaviour
and 512 MiB default ceiling. Browsers use that buffering path for downloaded
or user-selected bytes. See [seekable file input](docs/file-input.md).

`writeAudioAtomically` writes to a temporary file beside the destination and
replaces it after the write and close succeed, using kotlinx-io's atomic move.
The parent directory must exist and an encoder needs a known frame count.
Atomicity depends on the filesystem; WASI cannot verify that guarantee.
`Path.asAudioSink()` opens on its first valid, non-empty write. An unused sink
preserves the file, while a failure after writing begins can leave partial
output.

## License

Apache-2.0. See [LICENSE](LICENSE).

Most formats were implemented from their published specifications. The Opus
decoder is translated from libopus under BSD-3-Clause, and the ALAC decoder
from Apple's reference under Apache-2.0. [NOTICE](NOTICE) includes their
attribution and licence terms, and distributions carry it with [LICENSE](LICENSE).
[reference/REFERENCES.md](reference/REFERENCES.md) records what may and may
not be used as the library grows.

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[ImageKodec](https://github.com/yuroyami/ImageKodec),
[KitePDF](https://github.com/yuroyami/KitePDF),
[ArchiveKodec](https://github.com/yuroyami/ArchiveKodec),
[KiteQR](https://github.com/yuroyami/KiteQR).

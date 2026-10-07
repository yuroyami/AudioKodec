# AudioKodec format status

Updated 2026-07-28. This file lists the support level for every format and
feature, and what comes next. For install instructions, targets and limits,
read the [README](README.md).

## Test and target summary

The complete common and JVM suites run green, including the optional
`audiokodec-io` bridge and external-tool oracle suites. The common tests also
run green on JS (Node), wasmJs (Node), wasmWasi (Node), and Kotlin/Native on
macOS arm64 and the iOS simulator. CI additionally executes the Linux x64 and
MinGW x64 native suites on their required hosts.

The oracle suites compare against ffmpeg, the reference FLAC tools and Apple's
`afconvert`, and skip themselves when those are not installed, so continuous
integration installs them rather than leaving a skipped test to read like a
passing one. Within them:

- Integer PCM at every depth, IEEE float, AIFF, AU, A-law, mu-law and
  Microsoft ADPCM all decode **bit-identically to ffmpeg**. IMA ADPCM does
  not, for a reason recorded under [Known differences](#known-differences).
- Tags written by ffmpeg into real MP3 files are read back field for field,
  and tags AudioKodec writes are read back by `ffprobe`, in both ID3v2.3 and
  ID3v2.4 and including text outside ISO-8859-1.
- Tagging an MP3 is checked to leave the decoded audio identical sample for
  sample, by decoding the file with ffmpeg before and after.
- FLAC files written by the reference encoder decode **bit-exactly** at 8, 16
  and 24 bits, 1, 2 and 6 channels, and every compression level from 0 to 8.
- FLAC files AudioKodec writes pass `flac --test`, which verifies every frame
  checksum and the stream MD5, and `flac --decode` returns the samples that
  went in.
- `DecodedAudio` can be encoded repeatedly through independent readers while
  preserving its direct-read cursor. Common regressions cover partial reads,
  failed writes and overlapping operations. Repeated mono and stereo FLAC
  encodings at 16 and 24 bits pass reference decoding with exact samples.
- CAF files from `afconvert` decode bit-exactly across its uncompressed data
  formats in both byte orders.
- Ogg FLAC files from the reference encoder decode **bit-exactly**, seek to the
  sample, and survive a tag rewrite that the reference decoder then accepts.
- The headers and comments of Ogg Vorbis, Ogg Opus and Ogg Speex files from
  ffmpeg are read, including the length of each.
- Opus decodes **bit-exactly** against libopus 1.5.2 built without its SIMD
  kernels: CELT, SILK, hybrid frames, changes of mode, bandwidth and channel
  count, concealment and recovery from redundancy, at every output rate, and
  every multistream mapping family wrapped in Ogg with its pre-skip, start
  offset and end trim. Ogg Opus files from ffmpeg decode to within 2e-7 of
  ffmpeg's own libopus decode, with each 5.1 and 7.1 channel in its place.
- Opus passes the official RFC 8251 conformance vectors, all twelve, at every
  output rate in mono and stereo, scored by the RFC's own measure, with the
  same quality figures libopus gets: 97.4 % in mono and 99.9 % in stereo on
  average at 48 kHz.
- Vorbis files from libvorbis decode to within a ten-thousandth of what the
  reference decoder produces, at qualities 0, 4 and 9, in mono, stereo and 5.1,
  at 8 kHz, 44.1 kHz and 48 kHz, on a signal full of transients that makes the
  encoder switch block sizes, and on files as short as a single frame.
- The inverse MDCT is checked against the transform's own definition computed
  directly, at seven block sizes, rather than only against another decoder.
- MPEG audio frame headers, durations and data offsets agree with `ffprobe`
  across five sample rates, three bitrates and both channel counts, and a LAME
  variable bitrate file reports the length that went into the encoder.
- Vorbis codebooks are built bit by bit to check the rules every real file
  happens to satisfy: an incomplete tree, an overfull one, a single-entry book
  read with either bit, a book ending on a 32-bit codeword, and a book
  declaring more lookup values than the stream holds.

`audiokodec` builds for 22 targets and `audiokodec-io` for 21. The README lists
them.

## Containers

| Container | Read | Write | Notes |
|---|---|---|---|
| **WAV (RIFF)** | ✅ | ✅ | Chunk walk with odd-size padding, `fmt `, `data`, `fact` |
| | ✅ | ✅ | WAVE_FORMAT_EXTENSIBLE, including the channel mask and the subformat GUID |
| | ✅ | ✅ | Written as extensible above two channels and for custom representable mono/stereo layouts, so the speaker layout survives; layouts a target cannot represent are rejected before output |
| | ✅ | ✅ | `LIST INFO`, `bext` and an `id3 ` chunk are read; `LIST INFO` is written |
| **RF64 and BW64** | ✅ | ✅ | `ds64` RIFF/data sizes, sample count and additional oversized-chunk entries. A 36-byte slot is always reserved after `WAVE`, so a file that passes 4 GiB is upgraded in place; existing table entries survive tag edits |
| **Wave64** | ✅ | ❌ | GUID chunk identifiers, 64-bit sizes, 8-byte padding |
| **AIFF** | ✅ | ✅ | `COMM`, `SSND` with its offset and block size, 80-bit extended sample rate |
| **AIFF-C** | ✅ | ✅ | `FVER`, compression type and name. IMA duration converts packet groups to decoded frames; G.711 and IMA accept the widths observed in Apple and ffmpeg files, with length, sample and seek comparisons for mono and stereo. Reversed PCM types use `23ni` for 32-bit and `42ni` for 24-bit little-endian integers, with exact integer and seeking regressions. Apple reads `23ni` directly; its equivalent `in24` output checks the 24-bit payload but does not establish support for `42ni`. Written when the coding is not plain PCM |
| **AU** | ✅ | ✅ | Six-field header. A data size of `0xFFFFFFFF` reads to end of input |
| **FLAC** | ✅ | ✅ | Metadata block chain, stream information, seek table, cue sheet and application blocks carried across a tag edit, stream MD5 verification |
| **CAF** | ✅ | ❌ | 64-bit chunk sizes, the description chunk, Apple's named channel-layout tags reordered from their stored speaker order into the canonical one, and packet-table valid/priming/remainder frame counts; codec priming and remainder are excluded from decode and seek output. ALAC is read through its magic cookie and its packet table of variable-size packets. Above two channels a file without a layout chunk has unknown speakers, because CAF assumes no order. Bitmap, explicit-description and unmapped layouts are named and refused rather than relabelled |
| **Ogg** | ✅ | tags only | Pages, checksums, packet reassembly across pages, several logical streams at once, chained streams, granule positions. The Vorbis, Opus, FLAC and Speex mappings are all identified and their headers and comments read; Vorbis, Opus and FLAC decode |
| **MPEG audio** | ✅ | ❌ | Frame headers for MPEG-1, 2 and 2.5 in all three layers, with every reserved combination rejected. A candidate sync is confirmed against the frames that follow it. `Xing`, `Info` and `VBRI` headers, LAME gapless delay and padding, tag blocks at both ends excluded from the audio. Exact durations. All three layers decode, with sample-accurate seeking. A protected frame's checksum is verified in strict mode, against ffmpeg's own checker, and decoded as stored in tolerant mode |
| **MP4, M4A, M4B, 3GP, QuickTime** | ✅ | ❌ | The box tree, with 32 and 64-bit sizes, a `moov` before or after the audio, and fragmented files read from their `moof` boxes with `trex` and `tfhd` defaults. Sample tables `stsz`, `stz2`, `stco`, `co64`, `stsc` and `stts` expanded into packets, every one at the offset and size ffprobe lists. PCM coalescing reserves index space for the resulting read packets and aggregates constant runs. Fragment offsets are checked, with defaults resolved by track. PCM and G.711 remain constant-rate across short read packets and fragment boundaries, with probe and decode metadata checked on ffmpeg files. Edit lists that play the media straight through, including an encoder's priming, with empty edits passed over; one that skips or repeats media is refused strictly. Chapters from a QuickTime chapter track or Nero's `chpl` list. ALAC, Opus, FLAC and the uncompressed QuickTime and ISO codings decode, with `chan` layouts reordered; AAC, MP3, AC-3 and AMR are named and refused |
| **Raw PCM** | ✅ | ✅ | Through `AudioSource` and `PcmSource` with a caller-supplied `AudioFormat` |
| Everything else | sniff only | ❌ | See [Sniffing](#sniffing) |

## Codings

| Coding | Decode | Encode | Notes |
|---|---|---|---|
| **PCM integer** | ✅ | ✅ | 8, 16, 24 and 32-bit, little and big-endian, signed and unsigned |
| | ✅ | ✅ | 8-bit is unsigned in WAV and signed in AIFF and AU, as each format requires |
| | ✅ | ✅ | Exact through `IntPcmAccess` at every depth, including 32-bit |
| **PCM float** | ✅ | ✅ | 32 and 64-bit IEEE 754, little and big-endian |
| **A-law** | ✅ | ✅ | ITU-T G.711. Bit-identical to ffmpeg in both directions |
| **mu-law** | ✅ | ✅ | ITU-T G.711. Bit-identical to ffmpeg in both directions |
| **IMA ADPCM (WAV)** | ✅ | ✅ | Block header per channel, four-byte groups, low nibble first |
| **IMA ADPCM (QuickTime `ima4`)** | ✅ | ❌ | 34-byte packets of 64 frames, one packet per channel. CAF accepts Apple's unspecified width and ffmpeg's four-bit width while requiring the same flags and packet geometry; ffmpeg mono and stereo files have sample, length and seek comparisons |
| **Microsoft ADPCM** | ✅ | ✅ | Seven standard coefficient sets, plus a table carried by the file. Exact ffmpeg comparisons cover every standard coefficient set, signed prediction rounding and independent stereo predictors |
| **FLAC** | ✅ | ✅ | Constant, verbatim, fixed and linear predictors up to order 32; Rice and Rice2 residuals with escaped partitions; all four stereo arrangements; wasted-bits subframes; 8 to 32-bit decode and 8, 16 and 24-bit encode. Decodes inside Ogg as well as natively |
| **Vorbis** | ✅ | ❌ | Floor type 1, residue types 0, 1 and 2, channel coupling, block switching, up to eight channels reordered into AudioKodec's channel order. Floor type 0 is parsed and refused: no encoder has emitted it since the format's 2001 betas, and none exists to make a file to test one against |
| **Opus** | ✅ | ❌ | SILK, CELT and hybrid frames, mode and bandwidth changes with their redundant frames, concealment and recovery from in-band redundancy, mapping families 0, 1, 2, 3 and 255 with surround reordered into AudioKodec's channel order. Pre-skip, start offset, end trim and output gain applied; seeking with 80 ms of run-up. Bit-exact against libopus 1.5.2 |
| Speex | ❌ | ❌ | Its Ogg carriage is done: headers, length and comments are read. The codec itself is #18 |
| **ALAC** | ✅ | ❌ | Translated from Apple's Apache-2.0 reference: adaptive Golomb residues, the adaptive predictor in every order, mixed stereo pairs, shifted-off low bytes at 24 and 32 bits, uncompressed escape frames, one to eight channels reordered from ALAC's order. Every file Apple's `alacconvert` encodes at 16, 24 and 32 bits with one to eight channels decodes to exactly its input, and every ffmpeg file in MP4 to exactly ffmpeg's decode. 20-bit decoding follows the reference, but neither tool here writes a 20-bit file to check it against. Encoding is #11 |
| WavPack, APE, TTA | ❌ | ❌ | #14, #15 and #16 |
| MP3 (Layer III) | ✅ | ❌ | MPEG-1, 2 and 2.5. Bit reservoir, all four block shapes including mixed, mid-side and intensity stereo for both versions, alias reduction, sample-accurate seeking. Agrees with ffmpeg to about 1e-6 |
| MP2 (Layer II) | ✅ | ❌ | All five bit allocation tables, grouped quantisation, scalefactor selection, joint stereo. Agrees with ffmpeg's float decoder to about 1e-6 |
| MP1 (Layer I) | ✅ | ❌ | Agrees with ffmpeg's float decoder to about 1e-6 |
| AAC, AC-3, and the rest | ❌ | ❌ | #13 and #42 |

## Tags

Tags are read from any file, including formats AudioKodec cannot decode. A tag
block does not need a decoder, so unsupported audio can still have its metadata
inspected and edited.

| Tag format | Read | Write | Notes |
|---|---|---|---|
| **ID3v2.4** | ✅ | ✅ | Text frames, multi-value frames, `TXXX`, `COMM`, `USLT`, `APIC`, `UFID`, `CHAP`, all four text encodings, frame-level unsynchronisation, extended header, appended blocks |
| **ID3v2.3** | ✅ | ✅ | The same set, with the version's own flag bits, size form and frame differences. Named artist/composer/lyricist lists use slashes; unsupported multi-value fields and literal slashes in list values are refused rather than written with duplicate identities |
| **ID3v2.2** | ✅ | upgraded | Three-character frames and three-byte sizes. Writing produces a 2.3 block, which is what every other tagger does |
| **ID3v1 and v1.1** | ✅ | update only | Never created. An existing block is kept in step with the real tag, and the 1.1 track number is detected rather than assumed |
| **APEv2** | ✅ | ✅ | Header and footer, multi-value text items, binary items, cover art. Found at either end of a file |
| **APEv1** | ✅ | upgraded | Footer only. Writing produces a v2 block |
| **RIFF INFO** | ✅ | ✅ | `LIST INFO` inside a WAV |
| **Broadcast Wave `bext`** | ✅ | ❌ | Description, originator, reference, date, time and coding history |
| **AIFF text chunks** | ✅ | ✅ | `NAME`, `AUTH`, `(c) `, `ANNO` |
| **ID3v2 in a RIFF or AIFF chunk** | ✅ | on request | An `id3 ` or `ID3 ` chunk. Writing one is asked for through `TagWriteOptions.formats` |
| **Lyrics3v2** | ✅ | ❌ | Read for its content and, more importantly, for its length: a reader that does not know it is there gets every other trailing offset wrong |
| **Vorbis comments** | ✅ | ✅ | In FLAC, and in Ogg for every mapping it carries |
| **FLAC metadata blocks** | ✅ | ✅ | Comment and picture blocks are rewritten; seek tables, cue sheets and application blocks are carried across untouched; padding is regenerated so the next edit need not move the audio |
| **Ogg comment headers** | ✅ | ✅ | Vorbis, Opus, FLAC and Speex. Writing rebuilds the header pages and renumbers the pages after them, leaving the audio packets untouched. A file interleaving several logical streams is refused rather than repaginated |
| MP4 `ilst` | ❌ | ❌ | #8, once the container in #7 is read |
| Matroska tags | ❌ | ❌ | #41 |

### The merged view

A file with several blocks is normal rather than odd. An MP3 written by two
programs a decade apart usually has an ID3v2 block at the front and an ID3v1
block at the end that disagrees with it. `TagBundle` merges them: the format
with the highest precedence wins any field they both hold, and a field only one
of them states is taken from that one. The order is ID3v2, then Vorbis and MP4,
then APEv2, then APEv1, then RIFF and AIFF chunks, then `bext`, then Lyrics3,
then ID3v1.

`TagBundle.raw` keeps every block exactly as it was stored, with the original
keys, so nothing is lost by the merge and an unusual entry is still reachable.

### Other tag features

| Feature | Status |
|---|---|
| Numbered genres, including `(17)`, `(17)Rock`, `17` and `RX` | ✅ |
| `3/12` split into a number and a total, and joined again on write | ✅ |
| Pictures from ID3v2, APE, `METADATA_BLOCK_PICTURE` and `COVERART` | ✅ |
| Media type sniffed when a format stores a picture without naming one | ✅ |
| Chapters from ID3v2 `CHAP`, with their nested titles and artwork | ✅ |
| ReplayGain 2 and the Opus R128 convention, kept apart | ✅ |
| Audio payload byte-identical after a tag write | ✅ checked with a full decode comparison |
| Padding after the frames, so the next edit need not move the audio | ✅ |
| Compressed and encrypted ID3v2 frames kept as stored across an edit in the same version, and a version change that would lose them refused | ✅ |
| Encoding chosen to be the narrowest that fits, for old-player compatibility | ✅ |

## PCM model

| Feature | Status |
|---|---|
| Float32 interleaved as the interchange form | ✅ |
| Integer view for lossless codings, through `IntPcmAccess` | ✅ |
| Conversion between Float32 and 8/16/24/32-bit integers | ✅ exact for 8, 16 and 24 |
| TPDF dither, deterministic from a seed | ✅ |
| Interleave and split | ✅ |
| Channel remapping between layouts | ✅ |
| WAV channel mask to layout and back | ✅ |
| Resampling, mixdown, loudness, peaks | ❌ #25, #26, #27 and #28 |

## Decoding

| Feature | Status |
|---|---|
| Pull-based block decoding, no coroutine dependency | ✅ |
| Sample-accurate seeking, including inside an ADPCM block | ✅ |
| Duration from the header, without decoding | ✅ |
| Frame count from a `fact` chunk, so padded ADPCM blocks do not add frames | ✅ |
| Buffer reuse for allocation-free steady state | ✅ |
| Tolerant mode for truncated input | ✅ |
| Allocation ceiling checked before every buffer is sized | ✅ |
| Read size scaled down to fit a small allocation ceiling | ✅ |

## Sniffing

29 formats are named: WAV, RF64, Wave64, AIFF, AU, CAF, FLAC, Ogg, MPEG audio,
AAC in ADTS and ADIF framing, MP4, Matroska, Monkey's Audio, WavPack,
Musepack, True Audio, DSF, DSDIFF, AMR-NB, AMR-WB, AC-3, DTS, ASF, MPEG-TS,
MIDI, Shorten, OptimFROG and TAK.

Consecutive ID3v2 blocks in front of a file are skipped so the format behind
them is found. When a tag runs past the bytes given to the sniffer, the answer
is MPEG audio, which is what such a file almost always is.

Nine container identifiers have decoder paths: WAV, RF64, Wave64, AIFF, AU,
FLAC, CAF, Ogg and MPEG audio. Ogg decodes the Vorbis and FLAC mappings and
refuses unsupported mappings by codec name. A unit test fixes that set in code
so it cannot drift accidentally.

**File output through kotlinx-io.** `Path.asAudioSink` opens on the first valid non-empty write, so unused sinks and encodes rejected before output preserve an existing file. `Path.writeAudioAtomically` writes a temporary file, closes it and replaces the destination through the platform atomic move. Shared filesystem tests cover successful replacement, rejected layouts, unknown lengths, partial-write failures and cleanup. The parent must exist; atomicity follows the filesystem, with the WASI guarantee unverifiable.

## Known differences

**IMA ADPCM against ffmpeg.** The IMA specification builds the difference from
three separately truncated shifts of the step size. ffmpeg computes
`((2 * magnitude + 1) * step) >> 3` instead, and says in its own source that it
is replacing the reference implementation's series of jumps with a multiply.
The two agree in exact arithmetic and differ by the truncation, and because
the predictor is a running sum, the difference accumulates. Measured over half
a second it reaches about 44 parts in 32768, or roughly -57 dBFS. AudioKodec
follows the specification, which is also what libsndfile and Microsoft's
documentation describe. The oracle test allows that difference and nothing
larger.

**ADPCM frame counts against ffmpeg.** The last ADPCM block in a WAV file is
padded with silence, and the `fact` chunk states how many frames were real.
AudioKodec stops there. ffmpeg decodes the padding as well, so it returns up to
one block more than the file declares. AudioKodec's behaviour is what gapless
playback needs.

**Vorbis frame counts against ffmpeg.** An Ogg page states how many samples of
final output exist once it has been decoded, and the last page of a file is
what says how long the file is. The extra samples an encoder pads its final
block with are not part of the recording, and AudioKodec drops them, which is
what makes one file follow another with no gap. ffmpeg's raw output does not
always agree: on some files it keeps that padding, and on a Vorbis stream whose
audio fits in a single Ogg page it drops 128 real samples from the end instead.
So the Vorbis oracle checks the length against what went into the encoder,
which libvorbis preserves exactly, and uses ffmpeg for the sample values.

**Two conventions for `TXXX` frames.** ID3v2 has no frame for a great many
fields, so they go in a user text frame whose description names them. Some
programs use a fixed set of descriptions for that. Others, ffmpeg among them,
write whatever metadata key they were given, using the names Vorbis comments
use, which means a comment can arrive as a `TXXX` called `comment` rather than
as a `COMM` frame. AudioKodec understands both, because reading only the first
loses fields from files that are perfectly ordinary.

**ID3 identities and MusicBrainz IDs.** Recording IDs are mapped to the
MusicBrainz-owner `UFID`, including reads of v2.2 `UFI` and the old
`MUSICBRAINZ_RECORDING_ID` user-text alias. Other MusicBrainz IDs use Picard's
exact `TXXX` descriptions. V2.4 user-text lists use one frame and retain all
values. V2.3 named slash lists are supported; other multi-value text writes
are explicitly refused. Mutagen 1.47.0 checks both directions, both versions,
Unicode and unchanged MP3 audio bytes, including raw user-text lists and
unrelated binary identifier owners.

## Where the code went its own way

Settled decisions, kept here so nobody has to work out the reasons again.

1. **`AudioSource` and `AudioSink`, not `AudioKodecSource` and
   `AudioKodecSink`.** The plan used both naming styles in different sections.
   The short names match `AudioFormat`, `AudioFrame` and `AudioDecoder`, and
   the framework prefix that Kotlin/Native adds keeps Objective-C interop
   unambiguous.
2. **`positionFrames`, not `positionSamples`.** The value counts frames, and a
   sample is one channel of one frame.
3. **`IntPcmAccess.nextIntFrame()`, not `readIntFrame()`.** It matches
   `AudioDecoder.nextFrame()`, which is the call it parallels.
4. **`audiokodec-io` reads a file into memory rather than streaming it.**
   kotlinx-io has no random-access file handle, and decoding needs one. The
   functions are named `readAudioSource` so the cost is visible, and the
   README points a caller with a large file at implementing `AudioSource` over
   a seekable handle instead.
5. **The `:sample` application is not in this release.** The plan lists it in
   the scaffold. It is a demonstration rather than part of the library's
   portable codec and container surface, so it remains a separate deliverable.

## What comes next

Vorbis closes the larger half of the lossy work, and it leaves behind an
inverse MDCT and a complex FFT that Opus, AAC and AC-3 all need, so the next
transform codec starts further along than this one did.

MPEG audio is done, all three layers. The framing layer answers what a caller
usually wants from an MP3 without decoding anything, and the codec behind it now
decodes to within about a millionth of ffmpeg.

Two things about it are worth writing down because they cost real time. First,
the constant tables are large blocks of published data and a single wrong digit
in one of them is silent: the audio still comes out, slightly wrong. Every table
here was agreed across several independent renderings before it was used, and
that caught three separate defects in widely copied sources. Second, the
boundary between the Huffman regions of a granule that switched windows is
usually written as the constant 36. That is correct for MPEG-1 and wrong for
MPEG-2, where a start or stop block needs 54 and only a short block needs 36. It
is the kind of mistake that shows up nowhere except on the few frames either
side of a transient.

Opus is done, and it was the largest single port here because it is two codecs
in one. Both are translated from libopus and agree with it to the last bit:
CELT in floating point, as the reference's default build has it, and SILK in
fixed point, as every build has it. One thing about it is worth writing down.
libopus's own SIMD kernels sum in a different order, and concealment feeds the
last bits of that through a long filter, so the default build drifts from its
own plain build by tens of steps in concealed frames. The plain build is the one
to compare a port against. Speex stays further out, at #18.

MP4 and ALAC, #6, are under way: the container reads and ALAC decodes. The tags
inside an MP4, its gapless trim and the ALAC encoder remain, and the first of
those closes the last tag gap.

Four small leftovers, all refused today rather than done wrong: writing the
Broadcast Wave `bext` chunk (#43), writing tags into an Ogg file that
interleaves several logical streams (#44), Vorbis floor type 0 (#45), and free
format MPEG audio (#46).

This is past the point at which the library is worth publishing. WAV, AIFF, AU, Wave64,
CAF, FLAC, Vorbis, Opus, Ogg and the tag set are a coherent product, and `0.1.0` can
go to Maven Central without the README having to apologise for anything.

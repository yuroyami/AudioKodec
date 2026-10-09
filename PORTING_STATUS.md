# AudioKodec format status

Updated 2026-10-09. This file lists the support level for every format and
feature, and what comes next. For install instructions, targets and limits,
read the [README](README.md).

## Test and target summary

Decoder seek targets must be non-negative. Invalid targets are rejected before
reading or changing playback state. Known durations clamp requests past their
end; unknown durations retain the requested logical position until the next
read determines whether audio is available.

Ogg page checksum verification and MPEG
duration scans without a stored frame count can read the whole file. Ogg
opening repeats that page walk; folder scans must account for this I/O.
Tolerant native FLAC inspection decodes remaining frames for a stream-copy cut
with a nonzero first-frame origin. Positions and seeks are rebased, duration
comes from complete consecutive frames and the original MD5 is unavailable.
Native FLAC also recognises pre-2007 variable-block sample numbering by
verifying the first frame and its successor's header when STREAMINFO bounds
are unequal. This inspection decodes one frame; modern fixed-block streams
with a short final block retain frame numbering.

The common and JVM suites include the optional `audiokodec-io` bridge and
external-tool comparisons. Common tests also target JS (Node), wasmJs (Node),
wasmWasi (Node), Linux x64, macOS arm64, the iOS simulator and MinGW x64.
Execution on one host does not establish results on another.

The shared hostile-input sweep covers all ten decodable containers through
27 short fixtures, including every Ogg codec, Vorbis floor 0, chained Ogg FLAC, all three MPEG
layers in both ordinary and free format, CAF
ALAC/IMA and fragmented MP4. It tests every truncation boundary, all eight
individual bit flips and an inverted byte at every position, and 100 random
bodies per fixture behind its format signature. Strict and tolerant probing,
streaming and complete decoding use a one-mebibyte allocation ceiling.
Streaming checks positive progress and bounded block counts. A new decoder
container must supply a fixture before the coverage check can pass.

The I/O bridge also publishes JavaScript. Its Node suite includes file
operations, while the Chromium suites exercise the buffer and raw stream
bridge and named file-host refusals. Browser tasks exclude the file-operation
suites because local filesystem handles are unavailable in that host.
`Path.openAudioSource` reads through bounded, read-only handles on JVM/Android,
Native, Node and WASI. Common cases cover byte offsets, EOF, source ownership,
tags, truncation and lossless seeks; JVM sparse-file cases cover a WAV above
512 MiB and positions past 4 GiB. The buffering helpers retain their names
and ceiling.

All published archives carry the root `LICENSE` and `NOTICE` under `META-INF`,
including source archives, native/JS/Wasm KLIBs and Android AARs. JS library
distributions carry both files at their root; Apple frameworks carry them in
`Resources`. The notice attributes the translated Opus and ALAC components and
includes libopus's complete BSD-3-Clause terms. Every publication POM lists
Apache-2.0 and the BSD-3-Clause component licence. CI checks the built files
against the canonical texts with `check-distribution-notices.py`.

The twenty findings from the original [code-quality audit](https://github.com/yuroyami/AudioKodec/issues/68),
issues #48 through #67, have been remediated and closed. The local Linux JVM
validation after the final fix passed 742 core tests and 14 I/O tests, with
five explicit macOS-only skips. Later audit follow-ups remain tracked in their
own issues; completion of this group does not qualify untested hosts.

Missing oracle tools and unavailable encoder cases produce explicit JUnit
skips. CI is configured to install ffmpeg, FLAC, LAME, ExifTool, Mutagen and
libopusfile and libmpg123 and to build the pinned Opus and Apple ALAC reference executables.
The Linux and macOS jobs run the JVM comparisons and reject missing suite
reports, failed tests and unexpected skips. Linux permits only the five
`afconvert` comparisons that require macOS; macOS permits no skips. The Opus
vectors are checksum-verified before the tests run. Hosted validation remains
subject to runner availability, including account billing. Within the suites:

- The root fixture generator records exact process arguments, executable
  versions and SHA-256, file and consumed-pipe hashes, and owned recipe source
  fingerprints. A second recording can verify byte-for-byte reproduction.
  CI publishes Linux and macOS recordings; the last local tool identities are
  committed in `test-data/fixture-tools.json`. FFmpeg and FLAC Ogg serials are fixed
  for deterministic fixtures, and published corpora retain pinned retrieval
  manifests without adding large binary files to the repository.

- All 86 published IETF CELLAR FLAC vectors at commit
  `aa7b0c6cf32994c106ae517a08134c28a96ff5b2` run individually: 69 exact integer
  PCM and seek comparisons with FLAC 1.5.0, checking stored MD5 and duration
  where present; six typed refusals
  for changing stream shapes or absent metadata; eleven strict/tolerant safety
  cases. The subset includes the legacy Flake 0.11 variable-block format,
  non-byte-aligned depths, 32-bit predictors and large metadata. A committed
  SHA-256 manifest verifies all inputs; missing configured vectors fail.

- Integer PCM at every depth, IEEE float, AIFF, AU, A-law, mu-law and
  Microsoft ADPCM all decode **bit-identically to ffmpeg**. IMA ADPCM does
  not, for a reason recorded under [Known differences](#known-differences).
- Tags written by ffmpeg into real MP3 files are read back field for field,
  and tags AudioKodec writes are read back by `ffprobe`, in both ID3v2.3 and
  ID3v2.4 and including text outside ISO-8859-1.
- Xing/Info tables guide sample-accurate MPEG seeking with slow seeking off.
  Quantised hints use verified suffix counts, while nearer walked positions
  retain their existing index. Common fixtures cover all three layers, all
  MPEG versions, omitted byte counts, padding, malformed hints and unsigned
  byte lengths. FFmpeg VBR files at 44.1, 22.05 and 11.025 kHz retain exact
  playback after seeks and refused refinements; far seeks read 21-36 KB from
  files of 122-131 KB.
- Tagging an MP3 is checked to leave the decoded audio identical sample for
  sample, by decoding the file with ffmpeg before and after.
- Layer III frames whose empty final granule starts exactly at the end of
  their main data retain every sample, checked against a padded control and
  ffmpeg's float decoder. Missing granule data is rejected before synthesis.
- Strict MPEG trailer handling accepts ID3v1, enhanced ID3v1 and Lyrics3 with
  known or unknown source sizes, short non-audio tails and zero padding.
  Playback stops at a stored duration; missing declared frame payloads remain
  truncation errors. Common-target regressions and FFmpeg comparisons cover
  all three layers.
- Tolerant MPEG decoding retains complete output before damaged frame
  contents in every layer, with the retained prefix checked against ffmpeg.
  Strict decoding and damage before any output retain their typed failures.
- QuickTime PCM without an `ftyp` atom is identified and decoded bit-exactly
  against ffmpeg, with the movie header before or after the audio. Common
  tests cover leading padding, bounded atom detection and malformed sizes.
- FLAC files written by the reference encoder decode **bit-exactly** at 8, 16
  and 24 bits, 1, 2 and 6 channels, and every compression level from 0 to 8.
- Native FLAC frame reads start with a small prefix and grow only when the
  actual frame needs more bytes. An overstated maximum does not demand a
  large initial allocation or repeated full-file reads; source-byte counters,
  scanning seeks and reference decoding cover the largest stated maximum.
- Unusable FLAC seek points discard the optional table, preserving decoding,
  scanning seeks and tags in both modes. Common and reference-encoder tests
  cover repeated, descending and invalid points, with valid placeholder
  controls; structural size errors and allocation guards remain failures.
- Tolerant FLAC decoding stops after the valid prefix when a later frame's
  number or declared range is inconsistent, for both float and integer reads.
  Strict decoding and damage before the first valid frame still fail.
- Whole-file decoding treats a declared duration as a capped allocation hint.
  WAV and FLAC regressions recover truncated audio in tolerant mode, report
  truncation in strict mode, and still reject actual output beyond the limit,
  with known and unknown source sizes.
- FLAC files AudioKodec writes pass `flac --test`, which verifies every frame
  checksum and the stream MD5, and `flac --decode` returns the samples that
  went in.
- `DecodedAudio` can be encoded repeatedly through independent readers while
  preserving its direct-read cursor. Common regressions cover partial reads,
  failed writes and overlapping operations. Repeated mono and stereo FLAC
  encodings at 16 and 24 bits pass reference decoding with exact samples.
- CAF files from `afconvert` decode bit-exactly across its uncompressed data
  formats in both byte orders.
- Tolerant CAF and Wave64 playback recovers complete blocks from a shortened
  audio payload. Required headers and declared container boundaries remain
  checked; common short-read tests and FFmpeg PCM comparisons cover recovery,
  strict truncation failures and files with no complete audio frame.
- CAF ALAC files from ffmpeg decode bit-exactly at 16 and 24 bits in mono
  and stereo, including a short final packet whose packet table overstates
  its length. Opening resolves that duration; seeking and strict EOF agree.
- Ogg FLAC files from the reference encoder decode **bit-exactly**, seek to the
  sample, and survive a tag rewrite that the reference decoder then accepts.
- Compatible chained Ogg links decode in sequence with one global timeline.
  Each link applies its own headers, granule origin, pre-skip and end trim.
  libopusfile checks complete Opus chains in mono, stereo and 5.1, including
  changed header gain and trimming. FFmpeg checks Vorbis setup changes and
  exact 16/24/32-bit FLAC chains. Common tests cover mixed codecs and FLAC
  depths, empty links, multiplexed group boundaries, source ownership, seeks,
  allocation limits, first-link tag edits and damaged later links. Output
  format changes are named and refused before playback.
- Positive granule origins in Ogg Vorbis and FLAC retain the correct duration,
  end trim, samples and logical seek positions. Common fixtures and shifted
  FFmpeg recordings cover both modes and source sizes. A positive Vorbis origin
  sharing its only usable granule with end trim is named and refused.
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
- Vorbis floor 0 follows the published LSP vector accumulation and odd/even
  curve equations, including partial final vectors and 63-bit unsigned
  amplitudes. Direct products cover orders 1 through 255, both parities,
  multiple Bark maps and floor rates, and the 2015 Bark erratum. Complete
  synthetic streams compare with FFmpeg's float decoder within the existing
  `1e-4` peak bound; RMS is below `1e-5`, or `2e-5` at order 255. Type-two
  codebooks use FFmpeg's libvorbis wrapper at its 16-bit precision, with the
  same peak bound and RMS below `3e-5`. Common tests cover silent floors,
  truncated packets, reserved selectors, scalar-book refusal, allocation
  guards, oversized vectors, tag payload identity, source sizes and seeking.
- MPEG audio frame headers, durations and data offsets agree with `ffprobe`
  across five sample rates, three bitrates and both channel counts, and a LAME
  variable bitrate file reports the length that went into the encoder.
- Free-format MPEG measures confirmed unpadded spacing, honours one-byte or
  four-byte padding slots and rejects changing spacing. LAME recordings at
  all nine rates in mono/stereo agree with libmpg123's public float API in
  duration and PCM within the existing MPEG bounds. Nonzero Layer I/II
  comparisons cover MPEG-1 and low-rate Layer II, including the MPEG-2.5
  extension. CRCs, missing Xing metadata, large frames, unknown source sizes,
  embedded tags, exact tag payloads, allocation guards and sample seeks have
  regressions. Xing refinement supports far seeks with slow seeking disabled;
  MP4 packet sizes also supply free-format lengths and preserve gapless PCM.
- Hand-built MP3 comparisons with FFmpeg cover all nine sample rates, the four
  switched block shapes and normal-block controls, with varied scalefactors.
  They check the mixed-block Huffman boundary and the short windows inside the
  wider long-scalefactor region at 8 kHz. Common tests also check duration,
  exact seek output and equivalent mixed and pure short-block signals.
- MP3 intensity stereo's uncoded highest bands agree with FFmpeg across all
  nine rates in long, short and mixed blocks, with both stereo modes and both
  MPEG-2 gain ladders. Separate MPEG-1 controls check that illegal positions
  continue to disable intensity stereo.
- MP3 short-window intensity decisions use separate right-channel spectral
  boundaries. Pure and mixed blocks, mid-side combinations and both MPEG-2
  gain ladders agree with FFmpeg at all nine rates. Explicitly coded zeros do
  not disable intensity; changing window decisions preserve exact seek output.
- Vorbis codebooks are built bit by bit to check the rules every real file
  happens to satisfy: an incomplete tree, an overfull one, a single-entry book
  read with either bit, a book ending on a 32-bit codeword, and a book
  declaring more lookup values than the stream holds.

`audiokodec` and `audiokodec-io` both build for 22 targets. The README lists
them.

## Containers

| Container | Read | Write | Notes |
|---|---|---|---|
| **WAV (RIFF)** | ✅ | ✅ | Chunk walk with odd-size padding, `fmt `, `data`, `fact` |
| | ✅ | ✅ | WAVE_FORMAT_EXTENSIBLE, including the channel mask and the subformat GUID |
| | ✅ | ✅ | Written as extensible above two channels and for custom representable mono/stereo layouts, so the speaker layout survives; layouts a target cannot represent are rejected before output |
| | ✅ | ✅ | `LIST INFO`, `bext` and an `id3 ` chunk are read and written; Broadcast Wave output is explicit |
| **RF64 and BW64** | ✅ | ✅ | `ds64` RIFF/data sizes, sample count and additional oversized-chunk entries. Indexed FIFO size lookup is shared by probing, tag reading and rewriting, with index storage charged to the allocation guard. A 36-byte slot is always reserved after `WAVE`, so a file that passes 4 GiB is upgraded in place; existing table entries survive tag edits in their original order |
| **Wave64** | ✅ | ❌ | GUID chunk identifiers, 64-bit sizes, 8-byte padding |
| **AIFF** | ✅ | ✅ | `COMM`, `SSND` with its offset and block size, 80-bit extended sample rate |
| **AIFF-C** | ✅ | ✅ | `FVER`, compression type and name. IMA duration converts packet groups to decoded frames; G.711 and IMA accept the widths observed in Apple and ffmpeg files, with length, sample and seek comparisons for mono and stereo. Reversed PCM types use `23ni` for 32-bit and `42ni` for 24-bit little-endian integers, with exact integer and seeking regressions. Apple reads `23ni` directly; its equivalent `in24` output checks the 24-bit payload but does not establish support for `42ni`. Written when the coding is not plain PCM |
| **AU** | ✅ | ✅ | Six-field header. A data size of `0xFFFFFFFF` reads to end of input |
| **FLAC** | ✅ | ✅ | Metadata block chain, stream information, seek table, cue sheet and application blocks carried across a tag edit, stream MD5 verification. Tag reads skip playback seek-table contents so an unusable index cannot hide intact comments |
| **CAF** | ✅ | ❌ | 64-bit chunk sizes, the description chunk, Apple's named channel-layout tags reordered from their stored speaker order into the canonical one, and packet-table valid/priming/remainder frame counts; codec priming and remainder are excluded from decode and seek output. ALAC is read through its magic cookie and its packet table of variable-size packets. Above two channels a file without a layout chunk has unknown speakers, because CAF assumes no order. Bitmap, explicit-description and unmapped layouts are named and refused rather than relabelled |
| **Ogg** | ✅ | tags only | Pages, checksums, packet reassembly across pages, up to 256 simultaneous logical streams, chained streams, granule positions. Stream serials are indexed and retained headers count towards the allocation guard. The Vorbis, Opus, FLAC and Speex mappings are all identified, their headers and comments read, and their audio decoded. Probe and decode share the mapping's speaker layout, including seven-channel surround and unknown speakers for ambisonic and unspecified Opus mappings. Tolerant playback stops at damaged pages or packet boundaries, with duration and seeks limited to the complete prefix. Tag reads stop at metadata packets and retain intact tags despite later setup or audio damage |
| **MPEG audio** | ✅ | ❌ | Frame headers for MPEG-1, 2 and 2.5 in all three layers, with every reserved combination rejected. A candidate sync is confirmed against the frames that follow it. `Xing`, `Info` and `VBRI` headers, LAME gapless delay and padding, tag blocks at both ends excluded from the audio. Embedded ID3v2.2/v2.3/v2.4 tags are skipped by their declared size, including v2.4 footers, without allocating their bodies or counting embedded sync patterns as audio. Exact durations. All three layers decode, with sample-accurate seeking. A protected frame's checksum is verified in strict mode, against ffmpeg's own checker, and decoded as stored in tolerant mode |
| **MP4, M4A, M4B, 3GP, QuickTime** | ✅ | ALAC | The box tree, with 32 and 64-bit sizes, a `moov` before or after the audio, and fragmented files read from their `moof` boxes with `trex` and `tfhd` defaults. Sample tables `stsz`, `stz2`, `stco`, `co64`, `stsc` and `stts` expanded into packets, every one at the offset and size ffprobe lists. PCM coalescing reserves index space for the resulting read packets and aggregates constant runs. Fragment offsets are checked, with defaults resolved by track. PCM and G.711 remain constant-rate across short read packets and fragment boundaries, with probe and decode metadata checked on ffmpeg files. Edit lists that play the media straight through, including an encoder's priming, with empty edits passed over; one that skips or repeats media is refused strictly. Without a trimming edit list, the iTunes `iTunSMPB` record's delay, padding and length are applied, with seeking relative to the trimmed start; checked against ALAC files ffmpeg encodes, and on macOS against `afconvert`. Chapters from a QuickTime chapter track or Nero's `chpl` list. AAC-LC, ALAC, Opus, FLAC, all three MPEG layers and the uncompressed QuickTime and ISO codings decode, with `chan` layouts reordered; AMR decodes through the AMR decoders; AC-3 decodes through the AC-3 decoder from an `ac-3` entry, its first syncframe giving the layout, and Enhanced AC-3 is named and refused |
| **Matroska and WebM** | ✅ | ❌ | Written from RFC 9559 and RFC 8794: EBML headers and variable-length integers, Segments and Clusters of unknown size ending where the RFC says, CRC-32 and Void skipped, SimpleBlocks and BlockGroups with Xiph, EBML and fixed lacing, and zlib and header-stripping content encodings, the inflate written from RFC 1950 and 1951. The first audio track decodes through the decoder its codec ID names: Opus, Vorbis, FLAC, MPEG Layers I to III, AAC-LC, AC-3 and PCM. Every frame is counted from its own header, CodecDelay trims the start and the last Block's DiscardPadding the end; MP3 and AAC priming, for which Matroska has no field, plays, as ffmpeg plays it. For each coding, ffmpeg's copy of a file into Matroska or WebM decodes to the length ffmpeg decodes and holds the native file's samples whole, and a live WebM written to a pipe decodes as the same audio written to a file. Encrypted tracks and other compression algorithms are refused by name; Enhanced AC-3 is named and refused |
| **ADTS** | ✅ | ❌ | Frame headers with every reserved field combination rejected, a sync believed only when the next three frames agree, junk and embedded ID3 crossed as for MPEG audio, one to four raw data blocks per frame with or without the checksum, fixed channel configurations and a leading program configuration element. The exact length counts every block; ffmpeg's files at 8 to 96 kHz with one, two and six channels, and behind ID3v2, agree with ffprobe's rate, channels, packet count and every packet offset. Each frame is a packet of the AAC decoder, indexed for sample-exact seeking |
| **AC-3** | ✅ | ❌ | Raw syncframes, as `.ac3` files: the sync word believed only when the three syncframes after it agree, a leading ID3v2 tag skipped, junk between syncframes crossed, every sampling rate and frame size code of the standard, the bit rate and layout of the first syncframe, and the exact length from the frames counted at 1536 per channel. Syncframes of Enhanced AC-3, which shares the sync word, are named and refused. Each syncframe is a packet of the AC-3 decoder, indexed for sample-exact seeking |
| **ADIF** | probe | ❌ | The header, its constant or variable bitrate and the first program configuration's rate and channels. The raw data carries no frame lengths, so the duration is unknown without decoding |
| **DSF** | ✅ | ❌ | Written from Sony's DSF specification 1.01: the `DSD `, `fmt ` and `data` chunks, blocks of each channel in turn with the least significant bit first, the stated sample count with the last block's padding excluded, the seven channel types reordered into the canonical order, and the ID3v2 block the header points to. Durations, rates and channel counts agree with ffprobe's |
| **DSDIFF** | ✅ | ❌ | Written from Philips's DSDIFF specification 1.5: the `FRM8` form with 64-bit sizes and padding, `PROP` with `FS  `, `CHNL` and `CMPR`, and the `DSD ` chunk of interleaved bytes, most significant bit first; named channels reordered into the canonical order, and the `ID3 ` chunk writers add. DST compression is refused by name |
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
| **FLAC** | ✅ | ✅ | Constant, verbatim, fixed and linear predictors up to order 32; Rice and Rice2 residuals with escaped partitions; all four stereo arrangements; wasted-bits subframes; 8 to 32-bit decode and 8, 16 and 24-bit encode. Decodes inside Ogg as well as natively. Native unindexed seeks bisect checked headers for known-size sources, decode and retain only the target frame, and resolve ambiguous embedded headers with a verified forward walk |
| **Vorbis** | ✅ | ❌ | Floor types 0 and 1, residue types 0, 1 and 2, channel coupling, block switching, up to eight channels reordered into AudioKodec's channel order. Synthetic floor-zero streams have independent decoder and direct-equation comparisons, including both vector lookup types and maximum order |
| **Opus** | ✅ | ❌ | SILK, CELT and hybrid frames, mode and bandwidth changes with their redundant frames, concealment and recovery from in-band redundancy, mapping families 0, 1, 2, 3 and 255 with surround reordered into AudioKodec's channel order. Pre-skip, start offset, end trim and output gain applied; seeking with 80 ms of run-up. Bit-exact against libopus 1.5.2 |
| Speex | ✅ | ❌ | Translated from libspeex 1.2.1, BSD 3-Clause, in its floating-point build's single-precision arithmetic: narrowband, wideband and ultra-wideband, every submode of each, the split-codebook and 3-tap pitch decoding, the vocoder modes, the subband decoders with folding and their QMF synthesis, discontinuous transmission with comfort noise, the perceptual enhancer and in-band stereo. Every file `speexenc` 1.2.1 writes in the oracle suite, at every narrowband quality and across the wideband and ultra-wideband ones, with variable and average bitrate, voice activity detection, several frames to a packet and stereo, decodes on the JVM to exactly the floats of libspeex built without SSE, and to exactly `speexdec`'s length; other targets are within 1e-3, which Kotlin/JS, keeping floats in double precision, needs. Timing follows `speexdec`, including a single-page stream's. Seeking converges after a 25-frame run-up; a seek to the start is exact. Bitstream versions other than 4 are refused by name; packet loss concealment is not used |
| **ALAC** | ✅ | ✅ | Translated from Apple's Apache-2.0 reference: adaptive Golomb residues, the adaptive predictor in every order, mixed stereo pairs, shifted-off low bytes at 24 and 32 bits, uncompressed escape frames, one to eight channels reordered from ALAC's order. Every file Apple's `alacconvert` encodes at 16, 24 and 32 bits with one to eight channels decodes to exactly its input, and every ffmpeg file in MP4 to exactly ffmpeg's decode. At 20 bits, a C++ harness calls Apple's `ALACEncoder` directly: mono, stereo and 5.1 require exact input integers, including extrema, low bits, prediction and escape frames, short final packets and seeking. Encoding is translated from the same reference in its default searching mode, with the coefficients and working buffers it carries between packets: every packet is byte-identical to `alacconvert`'s at 16, 24 and 32 bits with one to eight channels, and to `ALACEncoder`'s at 20 bits with one, two, three, six and eight, verbatim escapes and short final packets included. The `.m4a` files decode exactly through ffmpeg, AudioKodec and `alacconvert`, and on macOS `afconvert` |
| **WavPack** | ✅ | ❌ | Translated from WavPack 5.9.0. Blocks found as the reference finds them, with sub-block layout and block checksums verified; runs of mono and stereo blocks assembled into multichannel audio by the channel mask, including the older form ffmpeg writes. The adaptive entropy coder in lossless and hybrid forms, every decorrelation term, joint and false stereo, extended integers above 24 bits, shifted and padded low bits, and floats with their extension stream; decoded-sample checksums verified. Every file the reference encoder writes in each mode, at 8, 16, 20, 24 and 32-bit integer and 32-bit float, mono to 7.1, decodes to exactly the input and exactly what `wvunpack` gives; hybrid files match `wvunpack` integer for integer, and ffmpeg's files at every compression level do too. Seeking is exact. DSD is refused by name; the correction file and pre-4.0 files are out of scope |
| **Monkey's Audio** | ✅ | ❌ | Translated from the Monkey's Audio SDK 13.27. Descriptor, header and seek table of version 3.99 and later, including seek tables past 4 GiB; the range coder, the adaptive predictors and every neural-network filter stage of all five compression levels, in the 16-bit and 32-bit forms the SDK picks by depth, with its interim retry for 24-bit frames; mid and side pairs, special silent and mono frames, and the float transform. Frame checksums verified. Every file `mac` writes at every level, at 8, 16, 24 and 32-bit integer and 32-bit float, with one to six and eight channels, decodes to exactly the input and exactly what `mac -d` gives. Seeking is exact. Seven channels are refused, as the SDK refuses them; files before 3.99 are out of scope |
| **True Audio** | ✅ | ❌ | Translated from the format authors' TTA Hardware Players Library 1.2, BSD 3-Clause. The `TTA1` header, seek table and frames with their CRCs; the adaptive Rice codes, the eight-tap adaptive hybrid filter, the fixed first-order predictor and the inter-channel decorrelation. Every file the authors' `tta` tool (libtta 2.3) writes at 16, 20 and 24 bits with one to six channels, and every file ffmpeg writes at 8, 16 and 24 bits with up to eight, at sample rates from 8 to 96 kHz, decodes to exactly the input and exactly what `tta -d` and ffmpeg give. Seeking is exact. Password-protected files are refused by name |
| **Musepack** | ✅ | ❌ | Stream version 8, translated from libmpcdec r495, BSD 3-Clause, in its single-precision arithmetic: the stream header and its exact length, audio packets of 2^n frames with key frames, the canonical Huffman and enumerative codes of band counts, quantisers, scalefactor selection and differential scalefactors, mid/side flags, every quantiser from noise substitution to 15 bits, requantisation and the 32-band synthesis filter. Every file `mpcenc` r495 writes at qualities 0 to 10, at 32, 44.1 and 48 kHz, mono and stereo, with each mid/side mode and with noise substitution, decodes to the reference's floats bit for bit on the JVM; other targets are within 1e-5. Seeking matches a continuous decode except for substituted noise. Stream version 7 and older are refused by name |
| MP3 (Layer III) | ✅ | ❌ | MPEG-1, 2 and 2.5. Bit reservoir, all four block shapes including mixed, mid-side and intensity stereo for both versions, alias reduction, sample-accurate seeking. FFmpeg float comparisons enforce a maximum sample difference below 1e-4 |
| MP2 (Layer II) | ✅ | ❌ | All five bit allocation tables, grouped quantisation, scalefactor selection, joint stereo. FFmpeg float comparisons enforce a maximum sample difference below 1e-5 |
| MP1 (Layer I) | ✅ | ❌ | FFmpeg float comparisons enforce a maximum sample difference below 1e-4 |
| **AAC-LC** | ✅ | ❌ | Written from ISO/IEC 14496-3:2009 in floating point. Long and eight short windows with sine and Kaiser-Bessel shapes and every transition, window grouping, all eleven spectral codebooks with escapes and pulses, mid/side and intensity stereo, noise substitution, temporal noise shaping, channel configurations 1 to 7 reordered into AudioKodec's order, and program configurations. Every table extracted from the standard by script and agreed with the Apache-2.0 PacketVideo decoder. Against ffmpeg's encoder and decoder at 7.35 to 96 kHz, mono to 7.1, in ADTS and MP4, the largest sample difference is below 2e-6 and the signal to difference ratio above 120 dB; with noise substitution, every block's energy agrees within 0.5 dB. Seeking is sample-exact. HE-AAC (implicit and both explicit signallings), Main, SSR, LTP, 960-sample frames and coupling channels are refused by name. ADIF is probed only |
| **GSM 06.10** | ✅ | ❌ | Translated from libgsm 1.0.22 in its 16-bit fixed-point arithmetic: RPE decoding, long-term and short-term synthesis and the post-processing, from Microsoft's 65-byte two-frame blocks in WAV (format tag 0x0031, with the `fact` length) and from plain 33-byte frames in QuickTime and MP4 (`agsm`). Every file libgsm's encoder writes in the oracle suite, and every WAV file ffmpeg writes with it, decodes to exactly libgsm's samples on every target. Seeking converges after a half-second run-up; a seek to the start is exact. Raw `.gsm` files and AIFF-C `GSM ` are not read |
| **iLBC** | ✅ | ❌ | Translated from WebRTC's iLBC as libilbc 3.0.4 packages it, BSD 3-Clause, in its fixed-point arithmetic: bit unpacking, LSF dequantisation, checking and interpolation, the start state, the three-stage codebook reconstruction forwards and backwards in time, the enhancer with its pitch search, refinement and smoothing, the synthesis and high-pass output filters, and the concealment WebRTC applies to frames marked empty or with an impossible start state. Every table extracted from WebRTC's `constants.c` by script. Both frame sizes from the storage format of RFC 3952 (`#!iLBC20`, `#!iLBC30`), CAF (`ilbc`, the frame size from the packet size) and QuickTime (`ilbc`, by packet length as WebRTC's own decode call decides). Every file libilbc's encoder writes in the oracle suite, the same frames in the CAF and QuickTime files ffmpeg writes, and random damage, decode to exactly libilbc's samples; the one codebook index libilbc refuses is refused at the same frame. Seeking is exact, by decoding from the start |
| **G.726** | ✅ | ❌ | Written from section 4 of Recommendation G.726 (12/90) in its integer arithmetic, at 16, 24, 32 and 40 kbit/s, with the quantized difference signal as a 16-bit signed magnitude at every rate: the inverse quantizer, scale factor adaptation, adaptation speed control, the sixth- and second-order predictors with their limits, and the tone and transition detectors. Every table extracted from the Recommendation by script. With the Recommendation's log PCM output stage added in the test, every output word of all 80 of the ITU's Appendix II test sequences matches, A-law and mu-law, from reset and after homing. Read from WAV (0x0045, most significant bit first, as ffmpeg packs it) and AU (Sun's encodings 23, 25 and 26, least significant bit first), including ffmpeg's short last block. Output is the reconstructed signal limited to 14 bits. ffmpeg 6.1's G.726 does not follow the Recommendation, so its samples are no oracle; at 40 kbit/s its files do not decode well with a conforming decoder. Seeking is exact, by decoding from the start |
| **G.722** | ✅ | ❌ | Written from sections 4 to 6 of Recommendation G.722 (11/88): both sub-band ADPCM decoders in the Recommendation's 16-bit saturating arithmetic, all three modes, and the receive QMF. Every output word of the ITU's Appendix II test sequences matches, in every mode and both sub-bands. The QMF, which the Recommendation leaves partly open, keeps one bit more than its 15-bit output, as ffmpeg's does, and every G.722 file ffmpeg writes decodes to exactly ffmpeg's samples, in WAV (0x028F) and AU (encoding 24). The mode is chosen with `DecodeOptions.g722Mode`. Seeking is exact, by decoding from the start |
| **DSD** | ✅ | ❌ | Converted to PCM at a thirty-second of the DSD rate by three linear-phase decimating stages of AudioKodec's own design, Kaiser-windowed sincs of 79, 27 and 67 taps generated and checked by `design-dsd-filters.py`, the first applied a byte at a time through tables: flat within 2e-5 dB to fs/128, at least 119 dB down for everything that would alias into the output band, centred with no delay, so seeking is exact. ffmpeg's single-stage conversion to fs/8 agrees tone by tone within 0.002 dB in level and 0.001 rad in phase once its 40.5-bit delay is taken out, the bound set by its filter's 0.0016 dB droop at 22.05 kHz |
| **AMR-NB and AMR-WB** | ✅ | ❌ | Translated from opencore-amr 0.1.6, Apache 2.0, in its fixed-point arithmetic, with each codec's own basic operations: every rate of each, the storage format of RFC 4867 single-channel and multichannel with channels reordered from RFC 3551's order, and `samr` and `sawb` tracks in 3GP and MP4. Discontinuous transmission with comfort noise and dithering, concealment of frames without data and of lost AMR-WB frames, the post filter, AMR-WB's high band and its decoder homing frames. Every file opencore's AMR encoder and vo-amrwbenc write in the oracle suite, at every rate, with and without discontinuous transmission, with frames removed, clipped, and in two to six channels, decodes to exactly opencore-amr's samples, on every target. Seeking converges after a half-second run-up; a seek to the start is exact. Reserved frame types are refused |
| **AC-3** | ✅ | ❌ | Written from ATSC A/52:2012 in floating point, with the bit allocation in the integer arithmetic of its section 7.2: all coding modes and the low frequency channel at 32, 44.1 and 48 kHz, exponents in D15, D25 and D45 with reuse, delta bit allocation, mantissas in every size with the shared groups of the smallest, channel coupling with master coordinates and phase flags, rematrixing, block switching channel by channel with the 256-point transform pair, dynamic range words, dither flags with a generator restarted per syncframe, and CRC verification that `tolerant` relaxes. ffmpeg's encoder files at every layout, rate and bit rate agree with ffmpeg to 1.5e-5 and exactly (2e-6) once ffmpeg's 24-bit fixed-point coefficients are reproduced in a test-only mode; 192 random frames that use every element agree the same way. ffmpeg departs from the standard in three places, recorded in [AC-3](docs/ac3.md), and the tests are built to stay out of them. Enhanced AC-3 is refused by name |
| DTS, Enhanced AC-3 and the rest | ❌ | ❌ | Named by the sniffer |

## Tags

Tags are read from any file, including formats AudioKodec cannot decode. A tag
block does not need a decoder, so unsupported audio can still have its metadata
inspected and edited.

| Tag format | Read | Write | Notes |
|---|---|---|---|
| **ID3v2.4** | ✅ | ✅ | Text frames, multi-value frames, `TXXX`, `COMM`, `USLT`, `APIC`, `UFID`, `CHAP`, all four text encodings, frame-level unsynchronisation, extended header, appended blocks |
| **ID3v2.3** | ✅ | ✅ | The same set, with the version's own flag bits, size form and frame differences. Named artist/composer/lyricist lists use slashes; genres use references and an optional refinement; unsupported multi-value fields and literal slashes in list values are refused rather than written with duplicate identities |
| **ID3v2.2** | ✅ | upgraded | Three-character frames and three-byte sizes. Writing defaults to 2.4; callers can request 2.3. Standard frame identifiers, linked identifiers and people lists are upgraded, including unmodelled text, URLs and binary payloads. `CRM` and unknown three-character extensions without a safe equivalent are omitted so they do not block other edits |
| **ID3v1 and v1.1** | ✅ | update only | Never created. An existing block is kept in step with the real tag, and the 1.1 track number is detected rather than assumed |
| **APEv2** | ✅ | ✅ | Header and footer, multi-value text items, binary items, cover art. Found at either end of a file |
| **APEv1** | ✅ | upgraded | Footer only. Writing produces a v2 block |
| **RIFF INFO** | ✅ | ✅ | UTF-8 `LIST INFO` inside WAV/RF64; supported CSET declarations retain their encoding |
| **Broadcast Wave `bext`** | ✅ | ✅ | Explicit `BWF_BEXT` output; fixed ASCII widths, unsigned 64-bit time reference, CRLF coding history, retained version/UMID/loudness extension. FFmpeg and ExifTool read-back and unchanged PCM comparisons |
| **AIFF text chunks** | ✅ | ✅ | ASCII `NAME`, `AUTH`, `(c) `, `ANNO`; compatible UTF-8/Latin-1 reads; Unicode output uses ID3 |
| **ID3v2 in a RIFF or AIFF chunk** | ✅ | ✅ | An `id3 ` or `ID3 ` chunk, selected explicitly or added by default when native text cannot retain the requested metadata |
| **Lyrics3v2** | ✅ | ❌ | Read for its content and, more importantly, for its length: a reader that does not know it is there gets every other trailing offset wrong |
| **Vorbis comments** | ✅ | ✅ | In FLAC, and in Ogg for every mapping it carries |
| **FLAC metadata blocks** | ✅ | ✅ | Comment and picture blocks are rewritten; seek tables, cue sheets and application blocks are carried across untouched; padding is regenerated so the next edit need not move the audio |
| **Ogg comment headers** | ✅ | ✅ | Vorbis, Opus, FLAC and Speex. Writing rebuilds the header pages and renumbers the pages after them, leaving the audio packets untouched. In a file that interleaves several logical streams, the first audio stream's headers are rebuilt in place and every other stream's pages are copied byte for byte in their original order, checked with libogg and ffmpeg on files ffmpeg wrote. A final header sharing its page with audio is refused before output, including in tolerant mode |
| **MP4 `ilst`** | ✅ | ✅ | Standard atoms, several values in one item, UTF-16 text, `trkn`, `disk`, `tmpo`, `cpil` and the numbered `gnre`, freeform `----` items with the conventional names, ReplayGain, and JPEG, PNG, GIF and BMP front covers. Unmodelled and unreadable items are carried across unchanged. Only `moov` is rebuilt: padding absorbs a size change, and growth moves every chunk offset with the audio. Changed chapters are written as Nero `chpl`; chapter tracks are kept but not edited. ffmpeg writes and AudioKodec reads, AudioKodec writes and ffprobe reads, with unchanged PCM |
| **Matroska tags** | ✅ | ❌ | SimpleTags of the segment and of the audio track, nested ones flattened, mapped by the tagging specification's names over the Vorbis comment names they share; the segment's Info Title as `TITLE` when no tag gives one. Chapters of the first edition, hidden ones skipped. ffmpeg writes and AudioKodec reads. Writing is refused before any output |

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

Retained RIFF INFO and AIFF text entries honour the allocation limit, including
separate INFO lists in one form. ALAC predictor and packet scratch buffers are
checked before allocation during probe and open, and MP4 opening reuses the
codec constructed while resolving its format.

Merged values retain first-seen order through a balanced membership index.
Lookup and insertion use O(log n) comparisons, including colliding string
hashes. New merged values are charged to the existing allocation limit before
index insertion. Comment and lyric rewrites use the same index for text and identity
matching. New descriptions advance through unused numbers once, and legacy
cover-art MIME lookup occurs once per block. Comparison-count tests cover
ordered, reversed, permuted and colliding values on JVM, macOS ARM64, JS,
WasmJS and WASI.
Large FLAC value lists and ID3 identities are checked with Mutagen; the JVM
scaling test records timings without a machine-dependent pass threshold.

Unknown Vorbis comment rewrites append raw entries directly, with work
proportional to count and text bytes. They retain order, repetitions, key
spelling and empty values. The separate vendor header is marked internally;
real `VENDOR` comments remain user comments, and FLAC/Ogg rewrites preserve the
source header vendor. Readable modelled ReplayGain/R128 entries are rebuilt,
while unmodelled loudness names survive. Common tests cover
repeated edits, caller-created entries, gain removal, colliding hashes and
unchanged audio. Mutagen and `metaflac` check native FLAC in both directions;
the JVM probe records 8,000 to 64,000-entry rewrite timings without a latency
threshold.

Invalid UTF-8 in a Vorbis comment or vendor header does not stop strict or
tolerant audio decoding. Readable fields are recovered individually, and
unreadable entries retain their stored bytes in the raw view. Unrelated edits
preserve them; explicit field edits replace or remove unreadable comments.
Original-byte retention and field-name decoding respect allocation limits.
FFmpeg-generated Vorbis, Opus, Ogg FLAC and native FLAC files retain identical
decoded samples across metadata mutations and repairs.

Text entries in ID3, APE and Vorbis share one exclusion rule for the six modelled loudness
names. Other names retain their original key or ID3 description. APE raw text
retains leading, interior and trailing empty pieces, with each entry charged
to the allocation guard. Invalid UTF-8 still rejects an APEv2 block.
Common tests cover legal ID3 encodings, v2.2 inputs, v2.3/v2.4 output, APEv1
upgrades, repeated edits, gain removal, empty values, repetitions, binary
items and unchanged audio. Mutagen checks ID3v2.3/v2.4 and APEv2 in both
directions. Truncation and mutation sweeps cover these independent layouts.

R128 gain parsing enforces RFC 7845's signed 16-bit range and six-character
ASCII syntax. Invalid values remain raw and do not supply applicable gain;
the first later valid value can still supply it. R128 writers check the dB
range before quantisation and refuse unrepresentable requests before output.
Common tests cover both gains, signed endpoints, leading zeros, malformed
syntax, embedded nulls, fallback, rounding ties, adjacent floating values
outside each endpoint, large finite values, byte-identical audio and untouched
sinks. Truncation and mutation sweeps cover Ogg, FLAC, APE and ID3 layouts.
libopusfile 0.12's public gain helpers check conforming values, overflow and
rewritten real Opus files. The stricter RFC syntax takes precedence over its
acceptance of empty/sign-only values, overlong zeros and embedded-null suffixes.

Artwork merging compares the image bytes and properties both representations
carry, considering rich structures first. It retains distinct image bytes,
roles and rich captions, supplies absent dimensions from matching FLAC
pictures, and keeps chapter artwork separate. ID3 descriptions are made unique
before every block in a mixed write receives the same edited artwork. APE
uses all standard role keys and cannot overwrite an earlier picture silently.
Unrepresentable APE-only artwork and distinct ID3 icons are refused before
output. Common tests cover repeated edits, stable size, caption collisions,
all APE roles, hash collisions, allocation bounds and untouched sinks.
Mutagen checks mixed ID3/APE, chapter identities, all APE role keys, and real
FLAC/Opus files with leading ID3. Audio bytes and Opus packets remain exact.
Truncation and mutation sweeps cover independent artwork layouts.

Native FLAC encoding writes each supplied image as a separate PICTURE block.
Encoding and tag edits check the complete 24-bit body limit before output.
The encoder streams image bytes directly after checking all picture metadata.
Common cases cover both sink modes, unknown frame counts, size boundaries,
repeated edits, seeking, duration, exact PCM and malformed metadata. Metaflac
imports supply independent block bodies and its exports reproduce the image
bytes. The reference FLAC and FFmpeg decoders check all supported sample depths.
A 13 MiB image also checks streaming allocations and an exact metaflac export.

### Other tag features

| Feature | Status |
|---|---|
| ID3 genre reference runs, refinements, `((` escapes, bare codes, `RX` and `CR` | ✅ separate merged values; unknown syntax stays literal |
| `3/12` split into a number and a total, and joined again on write | ✅ |
| Comment and lyric whitespace retained in the merged view; unchanged ID3 text keeps every distinct language/description pair | ✅ v2.2/v2.3/v2.4 inputs and v2.3/v2.4 output, with all legal text encodings; Mutagen checks v2.3/v2.4 |
| Reserved iTunes `COM`/`COMM` frames kept separate from editable user comments | ✅ case-insensitive `iTun` description family retained across comment edits and version conversion; common encoding/hostile cases and Mutagen on real MP3 files |
| Native text charset handling | ✅ UTF-8 compatibility reads, authoritative RIFF CSET 0/28591/1252/65001, ASCII AIFF output, named refusals before output; FFmpeg, FFprobe, ExifTool, Python codecs and hostile cases |
| Multiple native text values retained during encoding and edits | ✅ repeated RIFF INFO and AIFF annotations; singular AIFF title/author/copyright with ID3 fallback or named refusal; WAV/RF64/AIFF/AIFF-C, ExifTool, Mutagen and hostile cases |
| Unrepresentable modelled metadata retained or named and refused before output | ✅ default RIFF/AIFF ID3 fallback, native-only field/component refusals, Vorbis WEBSITE and APE Weblink, standalone and multiple totals, explicit removal and external ID3 carriers; chapter output requires ID3; common boundary/mutation cases and Mutagen with exact encoded and decoded audio checks |
| Distinct primary and explicit release dates | ✅ `DATE`/`TDRC` kept separate from appended `RELEASE_DATE`/`TDRL`; legacy `TXXX:RELEASEDATE` and native `RELEASEDATE`; repeated independent edits/removals, all ID3 text encodings, hostile cases and Mutagen in both directions |
| ID3 legacy date precision and version conversion | ✅ v2.2/v2.3 year/day/month/time groups merged without invented seconds; v2.3 native split output for whole dates and minute precision, original-release years, calendar checks and named precision refusals; incomplete/free-form raw preservation or refusal, explicit primary edits, hostile sweeps, Mutagen, FFprobe and exact MPEG audio checks |
| Encoding software, human credit and explicit settings kept separate | ✅ ID3 `TSSE`/`TENC`/`TXXX:ENCODERSETTINGS`, native Vorbis/APE and RIFF conventions; combined strings retained intact; common version/edit/hostile cases, Mutagen and FFprobe |
| Pictures from ID3v2, APE, `METADATA_BLOCK_PICTURE` and `COVERART` | ✅ |
| Native FLAC PICTURE blocks during encoding and tag edits | ✅ complete body limit checked before output; direct image streaming during encoding; metaflac import/export, reference FLAC and FFmpeg checks |
| Equivalent artwork copies merged without growth on repeated edits | ✅ distinct image bytes, roles and rich captions retained; chapter scope kept separate |
| Unique ID3 picture descriptions and all standard APE picture-role keys | ✅ additional APE images need a richer block; unrepresentable APE-only requests refused before output |
| Media type sniffed when a format stores a picture without naming one | ✅ |
| Chapters from ID3v2 `CHAP`, with their nested titles and artwork | ✅ stored subframes retained per chapter; copy edits preserve extras; nested flags and bounds shared with the top-level parser; safe version conversion and named refusals; Mutagen chapters with exact MPEG and decoded-audio checks |
| ReplayGain 2 and the Opus R128 convention, kept apart | ✅ |
| Audio payload byte-identical after a tag write | ✅ checked with a full decode comparison |
| Padding after the frames, so the next edit need not move the audio | ✅ |
| Compressed and encrypted ID3v2 frames kept as stored across an edit in the same version, with retention flags honoured; incompatible version changes and collisions with rewritten frames refused before output | ✅ |
| Encoding chosen to be the narrowest that fits, for old-player compatibility | ✅ |

## PCM model

| Feature | Status |
|---|---|
| Float32 interleaved as the interchange form | ✅ |
| Integer view for lossless codings, through `IntPcmAccess` | ✅ |
| Exact integer encoder input through `IntPcmSource` and `IntArray` | ✅ integer WAV/AIFF/AU at 8/16/24/32 bits and FLAC at 8/16/24; explicit source depth, shifts and optional TPDF reduction; FFmpeg and reference FLAC comparisons, 32-bit transcodes, short reads and invalid-source coverage |
| Conversion between Float32 and 8/16/24/32-bit integers | ✅ exact for 8, 16 and 24 |
| TPDF dither, deterministic from a seed | ✅ |
| Interleave and split | ✅ |
| Channel remapping between layouts | ✅ |
| WAV channel mask to layout and back | ✅ |
| Sample rate conversion, streaming and allocation-free | ✅ windowed sinc translated from speexdsp 1.2.1 at quality 10, sample for sample against its float build at twelve rate pairs; distortion and noise on a 1 kHz tone between -134.8 and -169.3 dB, against -150.5 to -161.4 dB for ffmpeg's soxr at 28 bits, with level and phase agreeing within speexdsp's passband ripple. A straight-line mode for previews |
| Mixing between channel layouts | ✅ ITU-R BS.775-3 Table 2 for every 3/2 downmix, ffmpeg's rules for side, back centre, front centre pair and top front positions, refusal of a conversion with no defined answer; 24 conversions agree with ffmpeg's float mixing sample for sample. Per-channel gain and mid-side, exact up to 23 bits |
| Loudness measurement and ReplayGain | ✅ translated from libebur128 1.2.6: integrated, momentary and short-term loudness and loudness range equal to libebur128's within double rounding, integrated loudness within 0.1 LU of ffmpeg's `ebur128`, EBU Tech 3341 cases 1, 2, 3 and 6 and Tech 3342 case 1; true peak by sinc oversampling. ReplayGain 2 track and album values compose with the tag writers |
| Waveform peaks | ✅ per-bucket minimum, maximum and RMS per channel, streaming in bounded memory, bit-identical whatever the block sizes, with the bucket count known before decoding |
| Levels, fades and test signals | ✅ peak, RMS and clipping scans with frame positions, silence at the ends to the frame, gain in decibels, linear and logarithmic fades, and sine, square, noise and silence generators, which the test suite's own sine now builds on |

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

## Decode speed

`./gradlew :sample:benchmark` decodes thirty seconds of a fixed stereo test
signal in each coding through the streaming decoder with buffer reuse on, after
three discarded runs, and reports the median of seven as a multiple of real
time. FLAC, ALAC and WAV are encoded by the library; every other input is
written by ffmpeg, used as a program, so the numbers are for ffmpeg 6.1.1's
encoders and a machine without ffmpeg measures fewer codings. `--enforce`
exits with status 1 when a floor is missed.

The library committed to floors of 100 times real time for MPEG audio, 200 for
FLAC, 50 for Opus and 100 for the resampler, on a laptop-class machine in a
release build. They are sanity floors, there to catch an accidental quadratic
loop, not marketing numbers, so these figures stay out of the README until they
have been measured on continuous integration hardware. The run below is a
shared four-processor Intel Xeon at 2.8 GHz under Java 21, which is slower than
a laptop.

| Coding | Times real time | Floor |
|---|---|---|
| WAV 16-bit PCM | 2400x | none |
| FLAC 16-bit | 245x | 200x |
| ALAC 16-bit | 243x | none |
| MPEG Layer II, 192 kbit/s | 249x | 100x |
| MPEG Layer III, 128 kbit/s | 115x | 100x |
| MPEG Layer III, 320 kbit/s | 118x | 100x |
| Opus, 96 kbit/s | 260x | 50x |
| Opus, 32 kbit/s | 338x | 50x |
| AAC-LC, 128 kbit/s in MP4 | 381x | none |
| Vorbis, quality 4 | 355x | none |
| AC-3, 192 kbit/s | 326x | none |
| AC-3 5.1, 448 kbit/s | 141x | none |
| WavPack | 195x | none |
| True Audio | 179x | none |
| Resampler, linear, 44.1 to 48 kHz | 1255x | 100x |
| Resampler, sinc, 44.1 to 48 kHz and 48 to 44.1 kHz | 11x | 100x, **missed** |
| Resampler, sinc, 44.1 to 96 kHz | 6x | 100x, **missed** |

The first run found three misses, which is what the benchmark is for. FLAC
decoded at 145 times real time and Layer III at 88 to 96, both short of their
floors. Part of the cost was in code every decoder shares: the MSB-first bit
reader read a multi-bit field a byte at a time and a unary code a bit at a time,
and the polyphase filter and the long-block inverse MDCT of MPEG audio summed
each output in one running total, so every addition waited for the one before.
A four-byte load for fields of up to 25 bits, a byte-wise unary count and
several accumulators in those sums brought FLAC to 245 times and Layer III to
115 and 118, without changing what any file decodes to.

The sinc resampler is the miss that stays. It is speexdsp's quality 10 filter,
sample for sample: 256 taps, and above 32 distinct fractional positions it
interpolates between four neighbouring table rows with four double
accumulators, which is a thousand multiply-adds for every output sample of every
channel. The sum is in double precision, in the reference's order, and
reordering it could change the last bit of a sample; the JVM does not vectorise it, and Kotlin
Multiplatform has no portable SIMD to write it in. Profiling put the whole of
the time in that loop. A floor of 100 is out of reach for this filter without
changing its arithmetic or offering a cheaper filter, and the
linear mode clears the floor by an order of magnitude. Converting an hour of
stereo takes about five minutes at 11 times real time.

## Sniffing

29 formats are named: WAV, RF64, Wave64, AIFF, AU, CAF, FLAC, Ogg, MPEG audio,
AAC in ADTS and ADIF framing, MP4, Matroska, Monkey's Audio, WavPack,
Musepack, True Audio, DSF, DSDIFF, AMR-NB, AMR-WB, AC-3, DTS, ASF, MPEG-TS,
MIDI, Shorten, OptimFROG and TAK.

Consecutive valid ID3v2 blocks in front of a file are skipped so a visible
signature can identify the format behind them. Incomplete tags or signatures,
invalid tag headers, tag-only data and unrecognised following bytes return
null. ID3 alone does not establish MPEG audio. The recommended 64 KiB window
can be enlarged, or `probe` and `open` can walk valid leading tags from a
complete source. Common tests cover tag, footer and stacked-tag boundaries;
Mutagen-written tags and FFprobe check complete files independently.

Source-based detection also searches the first 512 KiB for confirmed MPEG
frames after leading junk or a partial frame, once container signatures have
been ruled out. Unknown input retains a bounded search; byte-array sniffing
still reports only a visible leading signature.

Ten container identifiers have decoder paths: WAV, RF64, Wave64, AIFF, AU,
FLAC, CAF, Ogg, MPEG audio and MP4. Ogg decodes the Vorbis, Opus and FLAC
mappings and refuses unsupported mappings by codec name. `SnifferTest` checks
the declared container set; that set alone does not verify every coding or
documentation claim.

MP4 MPEG tracks share the owned bare-stream frame decoders. `.mp3` and `mp4a`
object types `0x69`/`0x6B` support Layers I, II and III, one complete MPEG frame
per indexed packet. MP3 uses ten frames of preroll, Layers I and II two, for
sample-accurate seeks and initial edits. Unavailable reservoir audio advances
preroll time but is refused inside the audible recording. CRC handling matches
the bare path. Edit lists supply start and end trims; no second LAME delay is
applied. FFmpeg copy muxes can lose the original end-padding flag, leaving
that coded tail in the MP4 timeline. [Caller guidance](docs/mpeg-in-mp4.md)
explains the resulting duration. Common malformed packets and shared hostile
sweeps cover the new path; FFmpeg float decoders provide independent samples.

Opening an MP4 Opus edit uses the same 80 ms packet run-up as seeking. Its
discarded output does not change playable positions or duration. Stateful
packet regressions cover initial float and integer output, and FFmpeg-generated
mono and stereo Opus edits check the opening samples and edit alignment. Opus
run-up approximates continuous decoder state; seeking is not claimed bit-exact.

MP4 packet and chapter ranges are validated against indexed `mdat` payloads,
including fragment runs, multiple payloads, 64-bit headers and final zero-sized
boxes. Header, metadata and cross-boundary offsets are corruption in both modes.
Selected local URL and alias references are supported; external and resource-fork
references are refused. Description and reference indices must resolve. The
range index is allocation-guarded, and physical truncation retains the existing
packet recovery policy. Sparse multi-gigabyte PCM sources check bounded indexing
work; real FFmpeg PCM files check valid samples and damaged offsets.

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
values. Unmodelled raw lists preserve order, repetitions and valid empty
strings; malformed UTF-8 and UTF-16 text segments with an odd byte count are
skipped. Independent fixtures and Mutagen cover all four encodings, both
UTF-16 BOM byte orders,
mapped and custom descriptions, ordinary text controls and unrelated edits.
V2.3 raw slash lists retain repetitions and empty pieces; other raw lists are
refused before writing. Empty-value layouts include optional final termination
and BOM-only final empties, with exact audio payload checks.
V2.3 named slash lists and genre reference runs are supported; other multi-value text writes
are explicitly refused. Mutagen 1.47.0 checks both directions, both versions,
Unicode and unchanged MP3 audio bytes, including raw user-text lists and
unrelated binary identifier owners.

**ID3 genres.** V2.2, v2.3 and legacy-style v2.4 references are read as
separate genres, retaining refinements and deduplicating repeated names.
Unknown syntax stays literal. V2.3 writes a known reference run followed by
one optional free-text refinement; other orders or several refinements are
refused. V2.4 writes separate values. Literal leading parentheses use the
older escape in both versions for compatibility with readers such as Mutagen.
Strict plain-text v2.4 readers may display the extra parenthesis. Recognised
standalone numeric-code and RX/CR literals are refused. Eight common tests
cover grammar, malformed input, non-ID3 controls and exact audio bytes;
Mutagen checks both writable versions and both directions. Literal code-like
parentheses are checked with `translate=False`, because default conversion
normalises text before `genres` and can interpret the literal again.

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
4. **Separate buffered and seekable file inputs.** `readAudioSource` keeps its
   whole-file buffering behaviour and 512 MiB default ceiling.
   `Path.openAudioSource` uses platform read-only handles with bounded memory;
   browsers can use the existing sequential buffer bridge.
5. **The `:sample` application is not in this release.** The plan lists it in
   the scaffold. It is a demonstration rather than part of the library's
   portable codec and container surface, so it remains a separate deliverable.

## What comes next

Vorbis closes the larger half of the lossy work, and it leaves behind an
inverse MDCT and a complex FFT that Opus, AAC and AC-3 all need, so the next
transform codec starts further along than this one did.

MPEG audio decodes all three layers. The framing layer reports headers and
duration without decoding samples. FFmpeg float comparisons enforce maximum
sample differences below 1e-4 for Layers I and III and below 1e-5 for Layer II;
remaining conformance findings are tracked separately.

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
to compare a port against. Speex, #18, is done the same way, against libspeex's
plain float build: Ubuntu's `speexdec` is built with SSE and rounds differently.

MP4 and ALAC, #6, are done: the container reads, ALAC decodes and encodes with
packets identical to Apple's reference encoder, the item list inside an MP4
reads and writes, which closes the last tag gap, and the iTunes gapless trim is
applied.

Writing tags into an Ogg file that interleaves several logical streams is done,
#44. Vorbis floor type 0 and free-format MPEG audio now decode, with their
independent comparisons and malformed-input coverage recorded above.

This is past the point at which the library is worth publishing. WAV, AIFF, AU, Wave64,
CAF, FLAC, Vorbis, Opus, Ogg and the tag set are a coherent product, and `0.1.0` can
go to Maven Central without the README having to apologise for anything.

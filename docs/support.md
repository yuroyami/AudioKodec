# Format support

This page lists the support for every container, coding and tag format, and how each one is
checked. [Formats](formats.md) describes the same formats in prose.

## How support is checked

Every format in these tables has tests, and most of them compare AudioKodec with a reference
decoder or tool:

- Integer PCM at every depth, IEEE float, AIFF, AU, A-law, mu-law and Microsoft ADPCM decode
  bit-identically to ffmpeg. IMA ADPCM differs for a reason under
  [Known differences](#known-differences).
- All 86 IETF CELLAR FLAC test vectors run one by one. 69 match FLAC 1.5.0 exactly in samples
  and seeks, six are refused with a typed error, and eleven are strict and tolerant safety cases.
- Opus decodes bit-exactly against libopus 1.5.2 built without its SIMD kernels. The SIMD build
  sums in another order, so in concealed frames it drifts from the plain build. Opus also passes
  all twelve RFC 8251 conformance vectors.
- Vorbis decodes to within a ten-thousandth of libvorbis.
- MPEG audio agrees with ffmpeg's floating-point decoders to within 1e-4 a sample for Layers I
  and III, and to within 1e-5 for Layer II.
- The codecs translated from a reference implementation decode to that reference's samples. The
  [Codings](#codings) table names each reference and the bound on the targets where a result is
  not exact.

The JVM suites run these comparisons. They need external tools, which
`.github/scripts/prepare-codec-oracles.sh` builds. A missing tool skips its test. CI fails on
any skip, except that its Linux job allows the eight comparisons that need Apple's `afconvert`.

A hostile-input sweep covers every decodable container. It cuts each fixture at every byte,
flips every bit, inverts every byte and tries 100 random bodies behind the format signature,
all under a 1 MiB allocation ceiling. A new container must supply a fixture, or the coverage
check fails.

The common tests also run on JS, wasmJs and wasmWasi under Node, Linux x64, macOS arm64, the
iOS simulator and MinGW x64.

## Containers

| Container | Read | Write | Notes |
|---|---|---|---|
| **WAV (RIFF)** | ✅ | ✅ | Chunk walk with odd-size padding, `fmt `, `data`, `fact` |
| | ✅ | ✅ | WAVE_FORMAT_EXTENSIBLE, including the channel mask and the subformat GUID |
| | ✅ | ✅ | Written as extensible above two channels and for a custom mono or stereo layout, so the speaker layout survives. A layout that the target cannot represent is refused before output |
| | ✅ | ✅ | `LIST INFO`, `bext` and an `id3 ` chunk are read and written. Broadcast Wave output is explicit |
| **RF64 and BW64** | ✅ | ✅ | `ds64` RIFF and data sizes, the sample count and extra entries for oversized chunks. A 36-byte slot is always reserved after `WAVE`, so a file that passes 4 GiB is upgraded in place. Existing table entries survive a tag edit in their original order |
| **Wave64** | ✅ | ❌ | GUID chunk identifiers, 64-bit sizes, 8-byte padding |
| **AIFF** | ✅ | ✅ | `COMM`, `SSND` with its offset and block size, 80-bit extended sample rate |
| **AIFF-C** | ✅ | ✅ | `FVER`, compression type and name. The IMA duration converts packet groups to decoded frames. The reversed integer types are little-endian: `23ni` is 32-bit and `42ni` is 24-bit. Written when the coding is not plain PCM |
| **AU** | ✅ | ✅ | Six-field header. A data size of `0xFFFFFFFF` reads to the end of the input |
| **FLAC** | ✅ | ✅ | Metadata block chain, stream information, seek table, cue sheet and application blocks carried across a tag edit, stream MD5 verification. A tag read skips the seek table, so an unusable index cannot hide intact comments |
| **CAF** | ✅ | ❌ | 64-bit chunk sizes, the description chunk, Apple's named channel layouts reordered into the canonical order, and the valid, priming and remainder frame counts of the packet table. Priming and remainder are left out of decoding and seeking. ALAC is read through its magic cookie and its packet table. Above two channels, a file without a layout chunk has unknown speakers, because CAF assumes no order. Bitmap, explicit-description and unmapped layouts are named and refused |
| **Ogg** | ✅ | tags only | Pages, checksums, packets reassembled across pages, up to 256 simultaneous logical streams, chained streams, granule positions. The Vorbis, Opus, FLAC and Speex mappings are identified, and their headers, comments and audio are read. Tolerant playback stops at a damaged page or packet, with the duration and seeks limited to the complete prefix. A tag read stops at the metadata packets, so it keeps intact tags despite later damage |
| **MPEG audio** | ✅ | ❌ | Frame headers for MPEG-1, 2 and 2.5 in all three layers, with every reserved combination rejected. A candidate sync is confirmed against the frames that follow it. `Xing`, `Info` and `VBRI` headers, LAME gapless delay and padding, tag blocks at both ends left out of the audio. Embedded ID3v2 tags are skipped by their declared size. Exact durations and sample-accurate seeking. A protected frame's checksum is verified in strict mode and decoded as stored in tolerant mode |
| **MP4, M4A, M4B, 3GP, QuickTime** | ✅ | ALAC | The box tree with 32 and 64-bit sizes, a `moov` before or after the audio, and fragmented files read from their `moof` boxes. Sample tables expanded into packets at the offset and size that ffprobe lists. Edit lists that play the media straight through, with empty edits passed over. One that skips or repeats media is refused in strict mode. Without a trimming edit list, the iTunes `iTunSMPB` record's delay, padding and length are applied. Chapters from a QuickTime chapter track or Nero's `chpl` list. AAC-LC, ALAC, Opus, FLAC, all three MPEG layers, AMR, AC-3 and the uncompressed QuickTime and ISO codings decode, with `chan` layouts reordered. Enhanced AC-3 is named and refused |
| **Matroska and WebM** | ✅ | ❌ | Written from RFC 9559 and RFC 8794: EBML headers, Segments and Clusters of unknown size, SimpleBlocks and BlockGroups with Xiph, EBML and fixed lacing, and zlib and header-stripping content encodings. The first audio track decodes through the decoder that its codec ID names: Opus, Vorbis, FLAC, MPEG Layers I to III, AAC-LC, AC-3 and PCM. CodecDelay trims the start and the last Block's DiscardPadding trims the end. For each coding, ffmpeg's copy of a file into Matroska decodes to the samples of the native file. Encrypted tracks and other compression algorithms are refused by name |
| **ADTS** | ✅ | ❌ | Frame headers with every reserved field combination rejected, a sync believed only when the next three frames agree, junk and embedded ID3 crossed as for MPEG audio, one to four raw data blocks per frame with or without the checksum. The exact length counts every block. Each frame is indexed for sample-exact seeking |
| **AC-3** | ✅ | ❌ | Raw syncframes as `.ac3` files. The sync word is believed only when the three syncframes after it agree. A leading ID3v2 tag is skipped and junk between syncframes is crossed. The exact length comes from the frames counted, at 1536 per channel. Enhanced AC-3, which shares the sync word, is named and refused |
| **ADIF** | probe | ❌ | The header, its bitrate and the rate and channels of the first program configuration. The raw data carries no frame lengths, so the duration is unknown without decoding |
| **DSF** | ✅ | ❌ | Written from Sony's DSF specification 1.01: the `DSD `, `fmt ` and `data` chunks, the stated sample count without the last block's padding, the seven channel types reordered into the canonical order, and the ID3v2 block that the header points to |
| **DSDIFF** | ✅ | ❌ | Written from Philips's DSDIFF specification 1.5: the `FRM8` form with 64-bit sizes, `PROP` with `FS  `, `CHNL` and `CMPR`, and the `DSD ` chunk. Named channels are reordered into the canonical order, and the `ID3 ` chunk is read. DST compression is refused by name |
| **Raw PCM** | ✅ | ✅ | Through `AudioSource` and `PcmSource` with an `AudioFormat` that the caller supplies |
| Everything else | sniff only | ❌ | See [Sniffing](#sniffing) |

## Codings

| Coding | Decode | Encode | Notes |
|---|---|---|---|
| **PCM integer** | ✅ | ✅ | 8, 16, 24 and 32-bit, little and big-endian, signed and unsigned. 8-bit is unsigned in WAV and signed in AIFF and AU, as each format requires. Exact through `IntPcmAccess` at every depth |
| **PCM float** | ✅ | ✅ | 32 and 64-bit IEEE 754, little and big-endian |
| **A-law** | ✅ | ✅ | ITU-T G.711. Bit-identical to ffmpeg in both directions |
| **mu-law** | ✅ | ✅ | ITU-T G.711. Bit-identical to ffmpeg in both directions |
| **IMA ADPCM (WAV)** | ✅ | ✅ | Block header per channel, four-byte groups, low nibble first |
| **IMA ADPCM (QuickTime `ima4`)** | ✅ | ❌ | 34-byte packets of 64 frames, one packet per channel |
| **Microsoft ADPCM** | ✅ | ✅ | The seven standard coefficient sets, plus a table that the file carries |
| **FLAC** | ✅ | ✅ | Constant, verbatim, fixed and linear predictors up to order 32, Rice and Rice2 residuals with escaped partitions, all four stereo arrangements, wasted-bits subframes. 4 to 32-bit decode and 8, 16 and 24-bit encode. Decodes inside Ogg as well as natively |
| **Vorbis** | ✅ | ❌ | Floor types 0 and 1, residue types 0, 1 and 2, channel coupling, block switching, up to eight channels reordered into AudioKodec's channel order |
| **Opus** | ✅ | ❌ | SILK, CELT and hybrid frames, mode and bandwidth changes, concealment and recovery from in-band redundancy, mapping families 0, 1, 2, 3 and 255 with surround reordered into AudioKodec's order. Pre-skip, start offset, end trim and output gain applied. Seeking with 80 ms of run-up |
| **Speex** | ✅ | ❌ | Translated from libspeex 1.2.1 in the single-precision arithmetic of its float build: narrowband, wideband and ultra-wideband with every submode, discontinuous transmission with comfort noise, the perceptual enhancer and in-band stereo. Every file from `speexenc` 1.2.1 in the test suite decodes on the JVM to exactly the floats of libspeex built without SSE, and on other targets to within 1e-3. Seeking converges after a 25-frame run-up. Bitstream versions other than 4 are refused by name. Packet loss concealment is not used |
| **ALAC** | ✅ | ✅ | Translated from Apple's Apache-2.0 reference: every predictor order, mixed stereo pairs, shifted-off low bytes at 24 and 32 bits, escape frames, one to eight channels reordered from ALAC's order. Every file from Apple's `alacconvert` at 16, 24 and 32 bits decodes to exactly its input, and 20-bit files are checked against Apple's `ALACEncoder` directly. The encoder writes packets that are byte-identical to the reference's |
| **WavPack** | ✅ | ✅ | Translated from WavPack 5.9.0: lossless and hybrid modes, every decorrelation term, joint and false stereo, integers above 24 bits, shifted low bits and floats, with block and sample checksums verified. Every file the reference encoder writes, at 8 to 32-bit integer and 32-bit float from mono to 7.1, decodes to exactly what `wvunpack` gives. DSD is refused by name. The correction file and files before 4.0 are not read. The encoder writes lossless 8, 16, 24 and 32-bit integers with one to eight channels in the fast, normal, high and very high modes, and its files are byte-identical to those of `wavpack -m --no-threads` for the same raw samples. `wvunpack` verifies their checksums and MD5 sums and decodes them to exactly their input. Hybrid lossy and float encoding are not offered |
| **Monkey's Audio** | ✅ | ❌ | Translated from the Monkey's Audio SDK 13.27: version 3.99 and later, every compression level, mid and side pairs and the float transform, with frame checksums verified. Every file `mac` writes, at 8 to 32-bit integer and 32-bit float, decodes to exactly what `mac -d` gives. Seven channels are refused, as the SDK refuses them |
| **True Audio** | ✅ | ❌ | Translated from the TTA Hardware Players Library 1.2: the `TTA1` header, seek table and frames with their CRCs. Files from the `tta` tool and from ffmpeg decode to exactly their input. Password-protected files are refused by name |
| **Musepack** | ✅ | ❌ | Stream version 8, translated from libmpcdec r495 in its single-precision arithmetic, with every quantizer from noise substitution to 15 bits. Every file `mpcenc` r495 writes decodes on the JVM to the reference's floats bit for bit, and on other targets to within 1e-5. Seeking matches a continuous decode except for substituted noise. Stream version 7 and older are refused by name |
| **MP3 (Layer III)** | ✅ | ❌ | MPEG-1, 2 and 2.5. Bit reservoir, all four block shapes including mixed, mid-side and intensity stereo, alias reduction, sample-accurate seeking |
| **MP2 (Layer II)** | ✅ | ❌ | All five bit allocation tables, grouped quantization, scalefactor selection, joint stereo |
| **MP1 (Layer I)** | ✅ | ❌ | |
| **AAC-LC** | ✅ | ❌ | Written from ISO/IEC 14496-3:2009 in floating point, with every tool of the low complexity profile and channel configurations 1 to 7 reordered into AudioKodec's order. Against ffmpeg at 7.35 to 96 kHz and mono to 7.1, the largest sample difference is below 2e-6. HE-AAC, Main, SSR, LTP, 960-sample frames and coupling channels are refused by name. ADIF is probed only |
| **GSM 06.10** | ✅ | ❌ | Translated from libgsm 1.0.22 in its 16-bit fixed-point arithmetic, from Microsoft's 65-byte blocks in WAV and plain 33-byte frames in QuickTime and MP4. Decodes to exactly libgsm's samples on every target. Seeking converges after a half-second run-up. Raw `.gsm` files and AIFF-C `GSM ` are not read |
| **iLBC** | ✅ | ❌ | Translated from WebRTC's iLBC as libilbc 3.0.4 packages it, in its fixed-point arithmetic, with the enhancer and the concealment of empty frames. Both frame sizes from the storage format of RFC 3952, CAF and QuickTime. Decodes to exactly libilbc's samples. Seeking is exact |
| **G.726** | ✅ | ❌ | Written from Recommendation G.726 (12/90) at 16, 24, 32 and 40 kbit/s. Every output word of all 80 ITU test sequences matches. Read from WAV and AU. ffmpeg's G.726 does not follow the Recommendation, so its samples are not a reference |
| **G.722** | ✅ | ❌ | Written from Recommendation G.722 (11/88), in all three modes. Every output word of the ITU test sequences matches, and every G.722 file that ffmpeg writes decodes to exactly ffmpeg's samples. Read from WAV and AU. `DecodeOptions.g722Mode` chooses the mode |
| **DSD** | ✅ | ❌ | Converted to PCM at a thirty-second of the DSD rate by three linear-phase decimating stages, generated and checked by `design-dsd-filters.py`. The first stage is applied ten bytes of bits at a time through tables. Flat within 2e-5 dB to fs/128, at least 119 dB down for everything that would alias into the output band, and centered with no delay, so seeking is exact |
| **AMR-NB and AMR-WB** | ✅ | ❌ | Translated from opencore-amr 0.1.6 in its fixed-point arithmetic: every rate, single-channel and multichannel files in the storage format of RFC 4867, and `samr` and `sawb` tracks in 3GP and MP4. Decodes to exactly opencore-amr's samples on every target. Seeking converges after a half-second run-up. Reserved frame types are refused |
| **AC-3** | ✅ | ❌ | Written from ATSC A/52:2012 in floating point: every coding mode and the low frequency channel at 32, 44.1 and 48 kHz, coupling, rematrixing, block switching and CRC verification that `tolerant` relaxes. Agrees with ffmpeg to 1.5e-5. ffmpeg departs from the standard in three places, which [AC-3](ac3.md) describes. Enhanced AC-3 is refused by name |
| DTS, Enhanced AC-3 and the rest | ❌ | ❌ | Named by the sniffer |

## Tags

Tags are read from any file, including a format that AudioKodec cannot decode. A tag block does
not need a decoder, so the metadata of unsupported audio can still be read and edited.
[Tags](tags.md) describes the API.

| Tag format | Read | Write | Notes |
|---|---|---|---|
| **ID3v2.4** | ✅ | ✅ | Text frames, multi-value frames, `TXXX`, `COMM`, `USLT`, `APIC`, `UFID`, `CHAP`, all four text encodings, frame-level unsynchronization, extended header, appended blocks |
| **ID3v2.3** | ✅ | ✅ | The same set, with the version's own flag bits, size form and frame differences. Artist, composer and lyricist lists use slashes. A multi-value field that v2.3 cannot hold is refused |
| **ID3v2.2** | ✅ | upgraded | Three-character frames and three-byte sizes. Writing defaults to v2.4, and a caller can ask for v2.3. A frame without a safe equivalent, such as `CRM`, is left out |
| **ID3v1 and v1.1** | ✅ | update only | Never created. An existing block is kept in step with the real tag |
| **APEv2** | ✅ | ✅ | Header and footer, multi-value text items, binary items, cover art. Found at either end of a file. The tag of a WavPack file, written after its blocks |
| **APEv1** | ✅ | upgraded | Footer only. Writing produces a v2 block |
| **RIFF INFO** | ✅ | ✅ | UTF-8 `LIST INFO` inside WAV and RF64. A supported CSET declaration keeps its encoding |
| **Broadcast Wave `bext`** | ✅ | ✅ | Explicit `BWF_BEXT` output, fixed ASCII widths, unsigned 64-bit time reference, CRLF coding history, and the version, UMID and loudness extension kept |
| **AIFF text chunks** | ✅ | ✅ | ASCII `NAME`, `AUTH`, `(c) `, `ANNO`. Unicode output goes to ID3 |
| **ID3v2 in a RIFF or AIFF chunk** | ✅ | ✅ | An `id3 ` or `ID3 ` chunk, chosen explicitly or added by default when native text cannot hold the requested metadata |
| **Lyrics3v2** | ✅ | ❌ | Read for its content and for its length, because a reader that misses it gets every other trailing offset wrong |
| **Vorbis comments** | ✅ | ✅ | In FLAC, and in Ogg for every mapping |
| **FLAC metadata blocks** | ✅ | ✅ | Comment and picture blocks are rewritten. Seek tables, cue sheets and application blocks are carried across untouched. Padding is regenerated, so the next edit does not need to move the audio |
| **Ogg comment headers** | ✅ | ✅ | Vorbis, Opus, FLAC and Speex. Writing rebuilds the header pages and renumbers the pages after them, with the audio packets untouched. In a file that interleaves several logical streams, the other streams' pages are copied byte for byte |
| **MP4 `ilst`** | ✅ | ✅ | Standard atoms, several values in one item, `trkn`, `disk`, `tmpo`, `cpil`, `gnre`, freeform `----` items, ReplayGain, and JPEG, PNG, GIF and BMP covers. Unknown items are carried across. Only `moov` is rebuilt. Changed chapters are written as Nero `chpl` |
| **Matroska tags** | ✅ | ❌ | SimpleTags of the segment and of the audio track, mapped by the names of the tagging specification. Chapters of the first edition. Writing is refused before any output |

### The merged view

A file with several tag blocks is normal. An MP3 written by two programs a decade apart often
has an ID3v2 block at the front and an ID3v1 block at the end that disagrees with it.
`TagBundle` merges them. The format with the highest precedence wins any field that both blocks
hold, and a field that only one block states comes from that block. The order is ID3v2, then
Vorbis, MP4 and Matroska, then APEv2, then APEv1, then RIFF and AIFF chunks, then `bext`, then Lyrics3,
then ID3v1.

`TagBundle.raw` keeps every block exactly as it was stored, with the original keys. So the merge
loses nothing, and an unusual entry is still reachable.

### Damaged and unusual values

- Invalid UTF-8 in a Vorbis comment or vendor header does not stop decoding. Readable fields
  are recovered one by one, and an unreadable entry keeps its stored bytes in the raw view.
- A rewrite keeps the order, repetitions, key spelling and empty values of unknown Vorbis
  comments.
- An R128 gain follows the signed 16-bit range and the six-character syntax of RFC 7845. An
  invalid value stays raw and supplies no gain. A writer refuses a gain that it cannot represent
  before any output.
- Artwork that two tag formats both carry is merged when the image bytes and properties match,
  so a repeated edit does not grow the file.
- FLAC encoding writes each picture as its own PICTURE block, and checks the size limit of the
  block before any output.

### Other tag features

| Feature | Status |
|---|---|
| ID3 genre reference runs, refinements and `((` escapes | ✅ read as separate values. Unknown syntax stays literal |
| `3/12` split into a number and a total, and joined again on write | ✅ |
| Every distinct language and description pair of comments and lyrics kept | ✅ |
| Reserved iTunes `COMM` frames kept apart from editable user comments | ✅ |
| Native text charsets: RIFF CSET 0, 28591, 1252 and 65001, ASCII AIFF output | ✅ a value that cannot be written is named and refused before output |
| Repeated RIFF INFO entries and AIFF annotations kept | ✅ |
| Metadata that a native format cannot hold | ✅ written to an ID3 chunk by default, or named and refused before output |
| Primary and explicit release dates kept apart | ✅ `DATE` and `TDRC` apart from `RELEASE_DATE` and `TDRL` |
| ID3 legacy date precision and version conversion | ✅ no invented seconds. A precision that the target cannot hold is refused by name |
| Encoding software, human credit and encoder settings kept apart | ✅ |
| Pictures from ID3v2, APE, `METADATA_BLOCK_PICTURE` and `COVERART` | ✅ |
| Media type sniffed when a format stores a picture without naming one | ✅ |
| Chapters from ID3v2 `CHAP`, with their nested titles and artwork | ✅ |
| ReplayGain 2 and the Opus R128 convention, kept apart | ✅ |
| Audio payload byte-identical after a tag write | ✅ checked with a full decode comparison |
| Padding after the frames, so the next edit does not need to move the audio | ✅ |
| Compressed and encrypted ID3v2 frames kept as stored across an edit in the same version | ✅ |
| The narrowest text encoding that fits, for older players | ✅ |

## PCM model

| Feature | Status |
|---|---|
| Float32 interleaved as the interchange form | ✅ |
| Integer view for lossless codings, through `IntPcmAccess` | ✅ |
| Exact integer encoder input through `IntPcmSource` and `IntArray` | ✅ integer WAV, AIFF and AU at 8, 16, 24 and 32 bits, and FLAC at 8, 16 and 24 bits |
| Conversion between Float32 and 8, 16, 24 and 32-bit integers | ✅ exact for 8, 16 and 24 |
| TPDF dither, deterministic from a seed | ✅ |
| Interleave and split | ✅ |
| Channel remapping between layouts | ✅ |
| WAV channel mask to layout and back | ✅ |
| Sample rate conversion, streaming and allocation-free | ✅ windowed sinc translated from speexdsp 1.2.1 at quality 10, sample for sample against its float build. A straight-line mode for previews |
| Mixing between channel layouts | ✅ ITU-R BS.775-3 for every 3/2 downmix and ffmpeg's rules for the other positions. 24 conversions agree with ffmpeg's float mixing sample for sample |
| Loudness measurement and ReplayGain | ✅ translated from libebur128 1.2.6. Integrated loudness is within 0.1 LU of ffmpeg's `ebur128` filter |
| Waveform peaks | ✅ per-bucket minimum, maximum and RMS per channel, in bounded memory, with the bucket count known before decoding |
| Levels, fades and test signals | ✅ |

## Decoding

| Feature | Status |
|---|---|
| Pull-based block decoding, with no coroutine dependency | ✅ |
| Sample-accurate seeking, including inside an ADPCM block | ✅ |
| Duration from the header, without decoding | ✅ |
| Frame count from a `fact` chunk, so padded ADPCM blocks do not add frames | ✅ |
| Buffer reuse for an allocation-free steady state | ✅ |
| Tolerant mode for truncated input | ✅ |
| Allocation ceiling checked before every buffer is sized | ✅ |

## Decode speed

`./gradlew :sample:benchmark` decodes thirty seconds of a fixed stereo test signal in each
coding. It uses the streaming decoder with buffer reuse, discards three runs, and reports the
median of seven as a multiple of real time. AudioKodec encodes the FLAC, ALAC and WAV inputs,
and ffmpeg encodes the others, so a machine without ffmpeg measures fewer codings. `--enforce`
exits with status 1 when a coding misses its floor.

The floors catch an accidental slow path, such as a quadratic loop. They are 100 times real time
for MPEG audio, 200 for FLAC, 50 for Opus and 100 for the resampler. The run below is from one
machine, a shared four-processor Intel Xeon at 2.8 GHz under Java 21, with ffmpeg 6.1.1's
encoders.

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

The sinc resampler misses its floor. It is speexdsp's quality 10 filter, sample for sample,
which takes about a thousand multiply-adds for every output sample of every channel. The sum runs
in double precision in the reference's order, so a reordered sum could change the last bit of a
sample. The JVM does not vectorize it, and Kotlin Multiplatform has no portable SIMD. An hour of
stereo takes about five minutes to convert. The linear mode clears the floor by an order of
magnitude.

## Sniffing

The sniffer names 30 formats: WAV, RF64, Wave64, AIFF, AU, CAF, FLAC, Ogg, MPEG audio, AAC in
ADTS and ADIF framing, MP4, Matroska, Monkey's Audio, WavPack, Musepack, True Audio, DSF, DSDIFF,
AMR-NB, AMR-WB, iLBC, AC-3, DTS, ASF, MPEG-TS, MIDI, Shorten, OptimFROG and TAK.

22 of them decode: WAV, RF64, Wave64, AIFF, AU, FLAC, CAF, Ogg, MPEG audio, MP4, ADTS, WavPack,
Monkey's Audio, True Audio, Musepack, AMR-NB, AMR-WB, iLBC, DSF, DSDIFF, Matroska and AC-3. A
unit test keeps this list and the code in step.

Valid ID3v2 blocks in front of a file are skipped, so the signature behind them can identify the
format. An incomplete tag or signature, an invalid tag header and unknown following bytes all
return null. ID3 alone does not make a file MPEG audio. The recommended window is 64 KiB, and
`probe` and `open` can walk past valid leading tags in a complete source.

Detection from a source also searches the first 512 KiB for confirmed MPEG frames after leading
junk or a partial frame, once no container signature matches. Sniffing a byte array reports only
a visible leading signature.

## Known differences

**IMA ADPCM against ffmpeg.** The IMA specification builds the difference from three separately
truncated shifts of the step size. ffmpeg computes `((2 * magnitude + 1) * step) >> 3` instead,
and its source says that it replaces the reference's series of jumps with a multiply. The two
agree in exact arithmetic and differ by the truncation. The predictor is a running sum, so the
difference accumulates. Over half a second it reaches about 44 parts in 32768, or roughly
-57 dBFS. AudioKodec follows the specification, which libsndfile and Microsoft's documentation
also describe.

**ADPCM frame counts against ffmpeg.** The last ADPCM block in a WAV file is padded with silence,
and the `fact` chunk states how many frames are real. AudioKodec stops there. ffmpeg decodes the
padding as well, so it returns up to one block more than the file declares. Gapless playback
needs AudioKodec's behavior.

**Vorbis frame counts against ffmpeg.** The last page of an Ogg file states how long the file is.
The samples that an encoder pads its final block with are not part of the recording, and
AudioKodec drops them, so one file follows another with no gap. ffmpeg's raw output does not
always agree. On some files it keeps that padding, and on a Vorbis stream whose audio fits in a
single Ogg page it drops 128 real samples from the end instead.

**Two conventions for `TXXX` frames.** ID3v2 has no frame for many fields, so they go in a user
text frame whose description names them. Some programs use a fixed set of descriptions. Others,
ffmpeg among them, use the metadata key they were given, so a comment can arrive as a `TXXX`
called `comment` instead of a `COMM` frame. AudioKodec reads both.

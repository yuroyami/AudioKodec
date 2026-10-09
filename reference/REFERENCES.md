# References

What AudioKodec was written from, and what it may be written from next.

Nothing listed here is redistributed. Specifications are read; implementations
are either studied under a permissive licence or left unopened. The
`reference/` folder is ignored by git apart from this file, so a working copy
can hold checkouts without them ever reaching the repository.

## Rules

1. A source tree under GPL, LGPL or any other copyleft licence is never
   opened, never read, and never used as a guide. The list of those is below
   so the boundary is explicit rather than remembered.
2. A permissively licensed source tree may be ported from. Record the exact
   commit and its licence text here before writing a line.
3. A specification may always be implemented from. That is the preferred route
   even when a permissive implementation exists, because it produces code that
   reads like Kotlin instead of like translated C.
4. A tool may be used as a test oracle whatever its licence, because running a
   program is not deriving from it. Its source stays unopened.

## Specifications used in this release

| Format | Specification |
|---|---|
| RIFF WAVE | Microsoft Multimedia Programming Interface and Data Specification 1.0 |
| WAVE_FORMAT_EXTENSIBLE | Microsoft multichannel WAVE documentation, `KSDATAFORMAT_SUBTYPE` GUIDs |
| RF64 and BW64 | EBU Tech 3306 (RF64), EBU Tech 3285 (Broadcast Wave Format) |
| Broadcast Wave writing | [EBU Tech 3285 version 2.0, May 2011](https://tech.ebu.ch/docs/tech/tech3285.pdf), sections 2.3 and 2.4: fixed ASCII fields, little-endian time reference, versioned extension and unknown-loudness sentinel. Implemented from the specification; FFmpeg and ExifTool are executable oracles only |
| Wave64 | Sony Pictures Digital Wave64 specification |
| AIFF | [Apple Audio Interchange File Format 1.3](https://blog.zamzar.com/wp-content/uploads/2014/10/aiffspecs.pdf), including the text chunk cardinalities on pages 23 and 24 |
| AIFF-C | Apple AIFF-C specification, including the compression type registry; [Inside Macintosh: Common Chunks](https://dev.os9.ca/techpubs/mac/Sound/Sound-82.html) for the compressed sample width field. Writer conventions are checked with `afconvert` and ffmpeg |
| AU | Sun and NeXT audio file format documentation |
| A-law and mu-law | ITU-T Recommendation G.711 |
| G.722 | ITU-T Recommendation G.722 (11/88), sections 4 to 6; see [G.722 and G.726](#g722-and-g726) |
| G.726 | ITU-T Recommendation G.726 (12/90), section 4; see [G.722 and G.726](#g722-and-g726) |
| DSF | Sony DSF File Format Specification, version 1.01; see [DSD](#dsd) |
| DSDIFF | Philips DSDIFF File Format Specification, version 1.5; see [DSD](#dsd) |
| Channel mixing | Recommendation ITU-R BS.775-3, Annex 4, Table 2; see [Channel mixing](#channel-mixing) |
| Loudness | ITU-R BS.1770 through libebur128, with the test signals of EBU Tech 3341 and 3342; see [Loudness](#loudness) |
| IMA and DVI ADPCM | IMA Digital Audio Focus and Technical Working Group, Recommended Practices for Enhancing Digital Audio Compatibility, plus Microsoft's IMA ADPCM WAVE type documentation |
| Microsoft ADPCM | [Microsoft Multimedia Data Standards Update, 15 April 1994](https://www.mmsp.ece.mcgill.ca/Documents/AudioFormats/WAVE/Docs/RIFFNEW.pdf), pages 22 to 26 |
| QuickTime IMA (`ima4`) | Apple QuickTime File Format documentation |
| QuickTime PCM and G.711 | [Apple Sound sample data](https://developer.apple.com/documentation/quicktime-file-format/sound_sample_data), for fixed-width sample layouts |
| CAF | [Apple Core Audio Format specification](https://developer.apple.com/library/archive/documentation/MusicAudio/Reference/CAFSpec/CAF_spec/CAF_spec.html), including compressed descriptions and the IMA4 packet geometry |
| MP4 and QuickTime containers | Apple QuickTime File Format documentation; [ISO/IEC 14496-12:2005](https://studylib.net/doc/26307294/mp4), clauses 8.31, 8.35 and 8.36 for per-track fragment defaults and data offsets |
| MP4 sample ownership | [Apple data references](https://developer.apple.com/documentation/quicktime-file-format/Media_data_reference_atom/Data_references), for self-reference flags and alias/URL entries. Packet ranges are checked against declared media-data payloads; external files and resource-fork references are refused |
| FLAC | [RFC 9639](https://www.rfc-editor.org/rfc/rfc9639.html), including sections 8.1 and 8.8 for the metadata body limit and native PICTURE layout |
| Ogg | RFC 3533, plus the Ogg mapping documents for Vorbis, Opus, FLAC and Speex. Section 4's complete BOS/EOS groups and RFC 7845 section 7.2's per-link Opus pre-skip underpin chained playback; `OggChainOracleTest` checks independent libopusfile output and duration, FFmpeg's two-tone reproducer, changed Vorbis setups and exact 16/24/32-bit FLAC chains (#87). Serial reuse across complete boundaries is an explicit compatibility extension, not permission in RFC 3533. The shared final header/audio page regression in `OggHeaderBoundaryWriteTest` assembles and checksums pages from these specifications; `OggOracleTest` independently verifies all 56 packets of an FFmpeg-generated Opus file and byte-identical FFmpeg PCM after that boundary mutation. Tag writing refuses this layout before output (#92). `OggPageDamageTest` and the same oracle suite verify complete-prefix playback for checksum damage, missing pages and broken continuations against FFmpeg-generated Opus, Vorbis and FLAC recordings (#104) |
| R128 gain tags | [RFC 7845 section 5.2.1](https://www.rfc-editor.org/rfc/rfc7845#section-5.2.1), for the signed 16-bit range, six-character ASCII grammar and gain reference |
| Vorbis | Xiph.Org Vorbis I specification; [comment specification](https://xiph.org/vorbis/doc/v-comment.html) for counted vectors, repeated names and the separate vendor header. The Vorbis I Ogg mapping's nonzero initial PCM granule and RFC 9639's Ogg FLAC granules underpin `OggGranuleOffsetTest`; FFmpeg-generated recordings with independently shifted page positions retain identical FFmpeg PCM, and the oracle suite checks logical duration and seeks (#103) |
| Vorbis floor 0 | [Vorbis I specification section 6.2](https://xiph.org/vorbis/doc/Vorbis_I_spec.html#x1-920006.2), including vector accumulation, extra final vector values, unsigned amplitude and reserved book selectors. Section 6.2.3 and its 2015 Bark-scale erratum define the odd/even products and frequency mapping. Implemented directly from this text, with no implementation source used. `VorbisFloor0Fixtures` assembles complete streams; direct double products independently check synthesis, FFmpeg's native float decoder checks lookup type one, and its public libvorbis wrapper checks lookup type two at 16-bit precision (#45) |
| MPEG audio framing | ISO/IEC 11172-3 and 13818-3 frame headers, plus the published Xing, VBRI and LAME header layouts |
| Free-format MPEG | ISO/IEC 11172-3 and 13818-3 free bitrate-index syntax, slot padding and constant frame spacing. MPEG-1 free-format Layer II uses Table 3-B.2a; the measured bitrate does not select a declared-rate table. The owned decoder is reused with measured or indexed packet lengths. LAME's public `--freeformat` executable interface generates MP3; [libmpg123 initialisation](https://mpg123.org/api/group__mpg123__init.shtml), [input](https://mpg123.org/api/group__mpg123__input.shtml), [output formats](https://mpg123.org/api/group__mpg123__output.shtml) and [seeking](https://mpg123.org/api/group__mpg123__seek.shtml) document the independent float PCM and exact-length API. Neither tool's implementation source is read. FFmpeg rejects the high-rate free-format fixtures in the qualified environment, so it is not claimed as their decoder oracle (#46) |
| AAC-LC | ISO/IEC 14496-3:2009, subpart 4 (4.4.2 syntax, 4.5.2 semantics, 4.6.1 to 4.6.3 quantisation and noiseless coding, 4.6.8 joint stereo, 4.6.9 noise shaping, 4.6.11 filter bank, 4.6.13 noise substitution, Annex 4.A codebooks, Tables 4.129 to 4.141 band offsets, 4.156 and 4.157 noise shaping limits), and subpart 1 (1.6.2.1 AudioSpecificConfig, 1.6.5.2 SBR signalling, Table 1.19 channel configurations, Annex 1.A ADTS). Implemented from the standard in floating point. Every constant table was extracted from the text by script, never typed, and agreed against the PacketVideo decoder below; ffmpeg is an executable oracle only |
| MPEG audio in MP4 | ISO/IEC 14496-12 sample tables and edit lists, MPEG frame syntax from ISO/IEC 11172-3 and 13818-3, and the [MP4 registration authority's object types](https://mp4ra.org/registered-types/object-types) (`0x69` and `0x6B`). The existing owned frame decoder is shared; no additional implementation source is ported. FFmpeg and LAME are executable oracles only |
| Format magic numbers | The published headers of each format the sniffer names |
| Sniffing past ID3 metadata | [ID3v2.4 structure](https://id3.org/id3v2.4.0-structure) sections 3 and 5 for headers, synchsafe lengths, footers and metadata placement. The [public Mutagen ID3 API](https://mutagen.readthedocs.io/en/latest/api/id3.html) supplies independent tags and [FFprobe](https://ffmpeg.org/ffprobe.html) names complete files; no implementation source is read |
| File output | [kotlinx-io atomic move](https://kotlinlang.org/api/kotlinx-io/kotlinx-io-core/kotlinx.io.files/-file-system/atomic-move.html); [Node.js WASI directory options](https://nodejs.org/api/wasi.html#new-wasioptions) for filesystem tests |
| Seekable file input | Public [Java RandomAccessFile API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/RandomAccessFile.html), [POSIX pread](https://pubs.opengroup.org/onlinepubs/9799919799/functions/pread.html) and fstat, Microsoft's `_wopen`, `_fstat64` and `_lseeki64` CRT APIs, and [Node filesystem APIs](https://nodejs.org/api/fs.html) with `process.getBuiltinModule`. The [WASI snapshot preview1 specification](https://github.com/WebAssembly/WASI/blob/snapshot-01/phases/snapshot/docs.md) defines preopens, rights, positional reads and filestat memory layouts; implemented from that ABI using Kotlin's public scoped-memory API. No filesystem implementation source is ported |
| Native text charsets | [RIFF CSET definition](https://www.robotplanet.dk/audio/wav_meta_data/riff_mci.pdf), page 2-16; AIFF 1.3 text chunk definition; [Microsoft code page identifiers](https://learn.microsoft.com/en-us/windows/win32/intl/code-page-identifiers) and [published Windows-1252 mapping data](https://www.unicode.org/Public/MAPPINGS/VENDORS/MICSFT/WINDOWS/CP1252.TXT). Executable FFmpeg, ExifTool and Python charset codec checks use only public interfaces |
| Tag membership | [NIST AVL tree definition](https://xlinux.nist.gov/dads/HTML/avltree.html), for bounded comparison-based lookup and insertion; implemented directly, with no implementation source used |
| ID3 genres | [ID3v2.2](https://id3.org/id3v2-00) and [ID3v2.3](https://id3.org/id3v2.3.0) section 4.2.1; [ID3v2.4 frames](https://id3.org/id3v2.4.0-frames) section 4.2.3 |
| ID3v2.2 upgrades | The frame catalogues and payload definitions in [ID3v2.2](https://id3.org/id3v2-00) and [ID3v2.3](https://id3.org/id3v2.3.0), plus the [ID3v2.4 replacements](https://id3.org/id3v2.4.0-changes). Identifier mapping, slash lists and linked identifiers are implemented from those specifications. Mutagen's public ID3 API independently reads both the input and the upgraded output |
| ID3 identities and MusicBrainz | [ID3v2.3](https://id3.org/id3v2.3.0) sections 4.1 and 4.2; [ID3v2.4 frames](https://id3.org/id3v2.4.0-frames) sections 4.1 and 4.2; [Picard tag mapping](https://picard-docs.musicbrainz.org/en/v3.0/_static/MusicBrainz_Picard_Tag_Map.html) |
| ID3 date precision | ID3v2.2/v2.3 year, date, time and recording-date definitions; [ID3v2.4 timestamp grammar](https://id3.org/id3v2.4.0-structure) section 4 and [deprecated frame replacements](https://id3.org/id3v2.4.0-changes) section 4. Implemented from the specifications; Mutagen's public API and FFprobe check native split/merge behaviour without using implementation source |
| Encoding software and credits | ID3v2.2/v2.3/v2.4 `TSS`/`TSSE` and `TEN`/`TENC` definitions; [Picard tag mapping](https://picard-docs.musicbrainz.org/en/v3.0/_static/MusicBrainz_Picard_Tag_Map.html) and [TagLib property mapping](https://taglib.org/api/p_propertymapping.html) for native aliases. Separate explicit settings use an ID3 user-text description instead of guessing how to split a combined encoder string |
| ID3 text lists | [ID3v2.4 frames](https://id3.org/id3v2.4.0-frames) sections 4.2 and 4.2.6, with the [structure specification](https://id3.org/id3v2.4.0-structure) for text encodings, byte orders and terminators |
| ID3 chapter preservation | [Chapter addendum](https://id3.org/id3v2-chapters-1.0), with the v2.3/v2.4 frame layouts, structure flags and deprecated-frame changes. Embedded frame parsing and conversion follow the specifications. [Mutagen chapter API](https://mutagen.readthedocs.io/en/latest/user/id3.html#chapter-extension) is used only as an executable oracle |
| ID3 free-form text | [ID3v2.2](https://id3.org/id3v2-00) and [ID3v2.3](https://id3.org/id3v2.3.0) sections 4.9 and 4.11; [ID3v2.4 frames](https://id3.org/id3v2.4.0-frames) sections 4.8 and 4.10, for comment and lyric text and identities. Reserved iTunes descriptions are kept outside editable user comments, checked through the public Mutagen API and FFprobe |
| Artwork identity and APE roles | [ID3v2.3 APIC](https://id3.org/id3v2.3.0) section 4.15 and [ID3v2.4 APIC](https://id3.org/id3v2.4.0-frames) section 4.14; [APE item key uniqueness](https://wiki.hydrogenaudio.org/index.php?title=APE_Tag_Item) and [picture-role conventions](https://wiki.hydrogenaudio.org/index.php?title=Tag_Mapping#Ape_pictures) |

## Test oracles

Run as programs, never read as source.

| Tool | Used for |
|---|---|
| ffmpeg | Cross-checking every decoder in this release, and reading back what the encoders write. `IntegerEncodingOracleTest` compares exact 32-bit WAV/AIFF/AU transcodes, every integer target depth and channel count, and independent PCM depth reduction; the reference FLAC executable also verifies the integer-input files' samples and MD5 |
| flac and metaflac | FLAC, natively and inside Ogg, in both directions; ordered raw comments through native FLAC rewrites; [public picture imports and exports](https://xiph.org/flac/documentation_tools_metaflac.html) compare native PICTURE bodies and exact image bytes. `FlacStreamCopyTrimTest` uses RFC 9639 frame and sample numbering; `FlacOracleTest` compares a real FFmpeg stream-copy cut and a variable-sample fixture against FFmpeg PCM, duration and seeks (#115). `FlacBinarySeekTest` measures source bytes at 10%, 50% and 90% of an unindexed recording, and exercises CRC-8-valid fake headers embedded in RFC 9639 verbatim samples; the oracle suite repeats the read bounds on reference FLAC output and verifies the embedded-header PCM with FFmpeg (#116) |
| ExifTool 13.55 | Public command-line interface with duplicate extraction (`-a`) and format groups for RIFF INFO and AIFF text chunk lists, including native singleton limits. Used as an executable oracle; implementation source stays unopened |
| afconvert (macOS) | CAF and AIFF-C, including direct `23ni` integer comparisons. Apple does not accept `42ni`; equivalent `in24` files check its PCM payload only. On macOS it also decodes the `.m4a` files AudioKodec's ALAC encoder writes, encodes the files that check the iTunes gapless record, and encodes and decodes the AAC files that check AudioKodec's AAC decoder against Apple's |
| wavpack and wvunpack | WavPack, built from the commit the port follows, in every encoding mode at 8 to 32 bits, floats, mono to 7.1 and hybrid lossy; lossless files must decode to the exact input integers, and every file to exactly what `wvunpack --raw` writes. ffmpeg's own WavPack encoder supplies files in the older multichannel form |
| mac | Monkey's Audio, built from the SDK the port follows, at every compression level from fast to insane, 8, 16, 24 and 32-bit integers and 32-bit float, one to eight channels except seven; every file must decode to exactly the input integers and to exactly what `mac -d` writes |
| tta (libtta 2.3) | True Audio, the format authors' encoder and decoder, built from `libtta-c-2.3.tar.gz` (SourceForge, SHA-256 `68ba6db2d494dcd4aa6832a40952b88a3d50ca1174ab0ef1e091db16992303c7`). LGPL, so it is built and run as a program and its source is never opened. Its files at 16, 20 and 24 bits with one to six channels, and ffmpeg's at 8, 16 and 24 bits with up to eight, must decode to exactly the input integers, to exactly what `tta -d` writes and to exactly what ffmpeg decodes. It also writes the password-protected files that must be refused by name |
| mpcenc and libmpcdec r495 | Musepack: `mpcenc` from the operating system's `musepack-tools` package writes the files, and `mpc_float_oracle.c`, a few lines over libmpcdec's public API compiled against the r495 sources, writes the floats the reference decodes them to. `mpcdec` itself writes only 16-bit samples. Every sample must be the same float, at qualities 0 to 10, at 32, 44.1 and 48 kHz, mono and stereo, with each mid/side mode and with noise substitution. The encoder is LGPL and only run |
| speexenc, speexdec and libspeex 1.2.1 | Speex: `speexenc` from the operating system's `speex` package writes the files, in all three modes, at every narrowband quality and across the others, with variable and average bitrate, voice activity detection with discontinuous transmission, several frames to a packet, and stereo. `speex_float_oracle.c`, a few lines over libogg and libspeex's public API compiled against the 1.2.1 sources without SSE, writes the floats the reference decodes them to, which every sample must equal. `speexdec` itself writes 16-bit samples from a build with SSE, so it gives only the length, which must be the same |
| opencore-amr 0.1.6 and vo-amrwbenc 0.1.3 | AMR-NB and AMR-WB: `amr_oracle.c` over opencore-amr's public interfaces, built from the pinned sources under `reference/amr`, encodes AMR files with opencore's encoder and decodes every AMR and AMR-WB file to its 16-bit samples; `amrwb_encoder.c` encodes AMR-WB files with vo-amrwbenc, built likewise. Every rate of each codec, with and without discontinuous transmission, silence, clipping, AMR-WB decoder homing frames, frames removed and lost, two to six channels, and the same frames in 3GP files ffmpeg writes, must decode to exactly the reference's integers. The encoders are only run |
| libgsm 1.0.22 | GSM 06.10: `gsm_oracle.c`, a few lines over libgsm's public interface built from the pinned sources under `reference/gsm`, encodes speech, silence, clipping and noise in both layouts and decodes them; the test wraps Microsoft's blocks in WAV files and ffmpeg wraps the plain frames in QuickTime files. WAV files ffmpeg writes through its own libgsm encoder are checked too where ffmpeg has it. Every sample must be libgsm's integer |
| libilbc 3.0.4 | iLBC: `ilbc_oracle.c`, a few lines over libilbc's public interface built from the pinned sources under `reference/ilbc`, encodes speech, silence, clipping, noise and bursts in both frame sizes and decodes them; the test wraps the frames in the storage format of RFC 3952 and ffmpeg copies them into CAF and QuickTime files. Every sample must be libilbc's integer, also for runs of random frames, which exercise concealment, and for frames altered to state the codebook index libilbc refuses, which must be refused at the same frame |
| libogg, for multiplexed Ogg tag writing | `ogg_packets.c` over libogg's public interface lists every packet of every logical stream with its granule position, length and hash, and fails on a bad page checksum or a hole in any stream. `OggMultiplexTagOracleTest` writes tags into files ffmpeg made with two interleaved streams and requires every packet but the rewritten comment back unchanged and in order, and the same samples from AudioKodec and from ffmpeg. libogg's source is not read |
| libebur128 1.2.6 and ffmpeg's `ebur128` | Loudness: `ebur128_oracle.c` over libebur128's public interface, built from the pinned sources under `reference/ebur128`, measures tones and programmes at 32 to 96 kHz in mono, stereo and 5.1, whose integrated, momentary and short-term loudness, loudness range and sample peaks `LoudnessMeter` must equal within double rounding, fed in blocks of random size. ffmpeg's `ebur128` filter, an independent implementation, must agree on integrated loudness within 0.1 LU. Only run |
| ffmpeg's channel mixing | `ChannelMixOracleTest` mixes a different tone in every channel through 24 layout conversions, from 7.1, 6.1, 5.1, 5.0, quad, 3.0 and stereo down, and mono and stereo up, which `Pcm.mixChannels` must match sample for sample within float rounding. Only run |
| speexdsp 1.2.1 and ffmpeg's soxr | Resampling: `resample_oracle.c` over speexdsp's resampler, built from the pinned sources under `reference/speexdsp` in its float build without SIMD or fused multiply-adds, converts tones and noise at twelve rate pairs, mono and stereo, which `Resampler` must reproduce sample for sample however its input is split into blocks. ffmpeg's `aresample` with soxr at 28 bits converts the same tones, whose level and phase must agree within speexdsp's passband ripple; it is only run |
| ffmpeg and ffprobe, for DSD | DSF and DSDIFF files the test writes from a sigma delta modulator: ffprobe's rate, channels and duration, and ffmpeg's conversion to PCM, tone by tone in level and phase, within the bound its filter's difference from AudioKodec's sets; the suite also measures ffmpeg's filter itself, from single flipped bits, to confirm its delay. ffmpeg's source is not read |
| ITU-T G.726 and G.722 Appendix II test sequences | G.726: the code words and A-law and mu-law output words of all 80 sequences, at every rate, from reset and after homing, which `G726ConformanceTest` reproduces with the Recommendation's log PCM output stage added on top of the decoder. G.722: the sub-band decoders' output words for the encoder's output on tones, d.c., noise and overflow and for an artificial sequence, in every mode, which `G722ConformanceTest` reproduces with the QMF bypassed. Test data, fetched by CI and never committed |
| ffmpeg's G.722 and G.726 | G.722: every sample of the WAV and AU files ffmpeg writes, in all three modes through `-bits_per_codeword`, must equal ffmpeg's. G.726: ffmpeg's encoder writes the WAV and AU files, whose code words must decode to exactly the samples of the decoder `G726ConformanceTest` checks; ffmpeg's own G.726 decoder does not follow the Recommendation, so at 16 to 32 kbit/s its samples only bound the result, and at 40 kbit/s not even that |
| alacconvert and ALACEncoder API | ALAC, built from Apple's reference at the commit the port follows; the direct API checks 20-bit audio. Both also check that AudioKodec's encoder writes the reference's packets byte for byte, and `alacconvert` decodes AudioKodec's packets and magic cookie moved into its own CAF layout |
| libopusfile 0.12 | [Public gain helpers and tag parsing API](https://opus-codec.org/docs/opusfile_api-0.12/group__header__info.html) and [OpusTags layout](https://opus-codec.org/docs/opusfile_api-0.12/structOpusTags.html), called through Python ctypes for conforming R128 values, overflow and rewritten Opus tags. The public [open/close](https://opus-codec.org/docs/opusfile_api-0.12/group__stream__open__close.html), [decoding](https://opus-codec.org/docs/opusfile_api-0.12/group__stream__decoding.html) and [stream information](https://opus-codec.org/docs/opusfile_api-0.12/group__stream__info.html) APIs independently check chained Opus duration and float PCM, including surround order, per-link pre-skip, end trim and header gain (#87). RFC syntax takes precedence over the helper's malformed-value acceptance. No implementation source is read |
| Mutagen 1.47.0 | ID3 `UFID` owners, case-sensitive MusicBrainz `TXXX` descriptions, multi-value text frames including empty values and v2.3 slash pieces, `TCON.genres`, large FLAC value lists, ordered unknown FLAC comments and vendor headers, comment/lyric text and identities, and unmodelled ID3/APE loudness names. Mixed artwork tests also check APIC identities, chapter-local APIC frames and standard APE picture-role keys in both directions through the public APIs. The [public APE API](https://mutagen.readthedocs.io/en/latest/api/ape.html) checks empty text pieces, repeated values and binary items. Used as an executable oracle; no implementation source is read |

## Permissively licensed implementations for the work still to come

None of these has been opened yet. Each entry has to be checked against the
exact commit that gets ported, and its licence recorded here at that point.

| Reference | Licence to verify | For |
|---|---|---|
| minimp3 | CC0 | MP3, cross-check only so far |
| libvorbis | BSD-3-Clause | Vorbis encoding, if that is ever wanted. The decoder in this release was written from the specification and never opened one |
| libopus | BSD-3-Clause | Opus |
| libFLAC | BSD-3-Clause | FLAC, alongside RFC 9639 |
| Apple ALAC | Apache-2.0 | ALAC, both directions |
| libebur128 | MIT | Loudness measurement |
| libgsm | MIT-style | GSM 06.10 |

## Layer III constant tables

The MPEG Layer III Huffman tables are published data rather than anyone's
implementation, and four independent renderings of them were compared before
any were used. All 4994 entries across all 34 tables agree, every table's Kraft
sum is exactly 1, and no codeword is a prefix of another.

| Rendering | Licence | Form |
|---|---|---|
| ISO/MPEG dist10 `tables/huffcode` | ISO/MPEG reference software, reproducing ISO/IEC 11172-3 Annex B Table 3-B.7 | The canonical `x y hlen hcod` listing |
| ISO/MPEG dist10 `tables/huffdec` | as above | The same tables as decode trees |
| PDMP3 | Unlicense, public domain | Decode trees as C arrays |
| minimp3 | CC0, public domain | Multi-level flat lookup tables |

Only dist10's data files were read, not its C code. The extracted tables and
the scripts that verified them are in `reference/mp3-wip/`, which git ignores.

The remaining Layer III constants were gathered the same way and compared
across eight renderings: the 512-entry synthesis window, the scalefactor band
boundaries for all nine sample rates in both block shapes, the pre-emphasis
table, the alias coefficients and the scalefactor length tables.

| Rendering | Licence |
|---|---|
| minimp3 | CC0 |
| PDMP3 | Unlicense |
| puremp3 | CC0 |
| go-mp3 | Apache-2.0 |
| jsmpeg, whose window comes from kjmp2 | MIT, window public domain |
| AOSP PVMP3 | Apache-2.0 |
| SerenityOS LibAudio | BSD-2-Clause |
| dr_mp3 | public domain |

Three cautions for anyone re-treading this ground, all of them source bugs
rather than genuine ambiguity about the standard:

- PDMP3 as published has a wrong offset for count1 table B, pointing into the
  middle of table 24. Its tree data is correct; its index into that data is not.
- puremp3's MPEG-2 scalefactor band tables are a byte-identical copy of its own
  MPEG-1 ones, so three sample rates are wrong. Its MPEG-2.5 block is fine.
  Two sources from the same lineage would not have caught this.
- SerenityOS lists the eighth alias coefficient as -0.0032 where three other
  sources agree on -0.0037.

No PDF of ISO/IEC 11172-3 reachable online carries Annex B: every mirror is
front matter or main body stopping before the annexes. The annex survives as a
Word document inside an old zip of the standard, and a copy is in
`reference/iso/` for anyone continuing this work. MPEG-2.5 was never in an ISO
document at all, so its tables can only come from code.

Two things about the synthesis window, Table 3-B.3, worth knowing before
diffing it against anything:

- Every value is an exact multiple of 1/65536. Storing the numerators as
  integers avoids all decimal-parsing drift; the peak is D[256] = 75038/65536.
- At D[196] and D[316] the exact value is 3776/65536, which is a tie at nine
  decimal places. The standard prints 0.057617187 and every code copy prints
  0.057617188. Same number; only the rounding differs. Those two entries will
  flag in a naive diff and neither side is wrong.

The standard prints the window's signs explicitly, with the early entries
negative, and says nothing anywhere about a sign convention. Use them as
printed.

A note on the ISO/MPEG dist10 reference software, which several of the
renderings above were checked against. Its own licence is not permissive, so
only its bare numeric data files were read, never a line of its code. Those
files are verbatim the tables the standard prints, which rule 3 above allows.
Anyone uncomfortable with even that has three permissively licensed copies of
the same window to work from: pdmp3 and OpenMP3 under the Unlicense, and
puremp3 under MIT or CC0.

## MPEG Layer I and Layer II tables

The five bit allocation tables, the 63 scalefactors, the seventeen quantisation
classes, the scalefactor selection rules and the joint stereo bound were each
checked against at least two independent renderings, most against four or more.

The primary source turned out to be the same archived Word copy of ISO/IEC
11172-3 already in `reference/iso/`: its Annex B carries Tables 3-B.1 through
3-B.5 in full, so the MPEG-1 tables came from the standard rather than from
code. Cross-checks came from ETSI TS 103 466, ETSI ETS 300 401 and ETSI EN
300 401, which reproduce the Layer II algorithm normatively and are freely
published, and from the CC0 and Unlicense implementations already listed above.

Three arithmetic self-checks fell out of the data and all pass. Every allocation
row holds exactly `2^nbal` entries. For all four MPEG-1 tables, the sum of nbal
plus twice sblimit equals the protected-bit count Table 3-B.5 prints separately:
88+54=142, 94+60=154, 26+16=42, 38+24=62. And the MPEG-2 table's nbal sums to
the 75 its own prose states.

Things that look like transcription errors and are not:

- Subbands 3 to 10 of the two wide tables jump from 8191 levels straight to
  65535, skipping 16383 and 32767. The narrow rows jump from 31, and the
  narrowest from 5.
- The MPEG-2 table stops at 16383 levels, never 65535, and its second group
  skips seven levels where its first group has them.
- The standard prints two of the seventeen C constants truncated rather than
  rounded: 1.77777777777 where correct rounding gives ...778, and 1.06666666666
  where it gives ...667. This is the same class of tie as D[196] above. AudioKodec
  computes the dequantisation rather than transcribing it, which sidesteps both.

One source disagreement found, recorded because it is exactly the failure mode
this file exists to prevent. In the MPEG-1 low rate tables, subbands 0 and 1 at
allocation code 15: six sources including the standard itself say 32767, while
kjmp2 says 65535, and jsmpeg carries the same value because it is a port of
kjmp2 rather than an independent reading. 32767 is correct. Note also that
kjmp2 is zlib licensed rather than public domain, and that its dequantiser is a
deliberate approximation its own README owns.

## minimp3 cross-check

The switched-window boundaries and MPEG-2.5 mixed-block geometry were checked
against [minimp3 commit ea99364f61c14656440e8d77e9c233ccf3124633](https://github.com/lieff/minimp3/tree/ea99364f61c14656440e8d77e9c233ccf3124633).
Only the relevant decoder routines are studied; no implementation is translated.
The exact commit's `LICENSE` declares CC0-1.0, reproduced below.

```text
CC0 1.0 Universal

Statement of Purpose

The laws of most jurisdictions throughout the world automatically confer
exclusive Copyright and Related Rights (defined below) upon the creator and
subsequent owner(s) (each and all, an "owner") of an original work of
authorship and/or a database (each, a "Work").

Certain owners wish to permanently relinquish those rights to a Work for the
purpose of contributing to a commons of creative, cultural and scientific
works ("Commons") that the public can reliably and without fear of later
claims of infringement build upon, modify, incorporate in other works, reuse
and redistribute as freely as possible in any form whatsoever and for any
purposes, including without limitation commercial purposes. These owners may
contribute to the Commons to promote the ideal of a free culture and the
further production of creative, cultural and scientific works, or to gain
reputation or greater distribution for their Work in part through the use and
efforts of others.

For these and/or other purposes and motivations, and without any expectation
of additional consideration or compensation, the person associating CC0 with a
Work (the "Affirmer"), to the extent that he or she is an owner of Copyright
and Related Rights in the Work, voluntarily elects to apply CC0 to the Work
and publicly distribute the Work under its terms, with knowledge of his or her
Copyright and Related Rights in the Work and the meaning and intended legal
effect of CC0 on those rights.

1. Copyright and Related Rights. A Work made available under CC0 may be
protected by copyright and related or neighboring rights ("Copyright and
Related Rights"). Copyright and Related Rights include, but are not limited
to, the following:

  i. the right to reproduce, adapt, distribute, perform, display, communicate,
  and translate a Work;

  ii. moral rights retained by the original author(s) and/or performer(s);

  iii. publicity and privacy rights pertaining to a person's image or likeness
  depicted in a Work;

  iv. rights protecting against unfair competition in regards to a Work,
  subject to the limitations in paragraph 4(a), below;

  v. rights protecting the extraction, dissemination, use and reuse of data in
  a Work;

  vi. database rights (such as those arising under Directive 96/9/EC of the
  European Parliament and of the Council of 11 March 1996 on the legal
  protection of databases, and under any national implementation thereof,
  including any amended or successor version of such directive); and

  vii. other similar, equivalent or corresponding rights throughout the world
  based on applicable law or treaty, and any national implementations thereof.

2. Waiver. To the greatest extent permitted by, but not in contravention of,
applicable law, Affirmer hereby overtly, fully, permanently, irrevocably and
unconditionally waives, abandons, and surrenders all of Affirmer's Copyright
and Related Rights and associated claims and causes of action, whether now
known or unknown (including existing as well as future claims and causes of
action), in the Work (i) in all territories worldwide, (ii) for the maximum
duration provided by applicable law or treaty (including future time
extensions), (iii) in any current or future medium and for any number of
copies, and (iv) for any purpose whatsoever, including without limitation
commercial, advertising or promotional purposes (the "Waiver"). Affirmer makes
the Waiver for the benefit of each member of the public at large and to the
detriment of Affirmer's heirs and successors, fully intending that such Waiver
shall not be subject to revocation, rescission, cancellation, termination, or
any other legal or equitable action to disrupt the quiet enjoyment of the Work
by the public as contemplated by Affirmer's express Statement of Purpose.

3. Public License Fallback. Should any part of the Waiver for any reason be
judged legally invalid or ineffective under applicable law, then the Waiver
shall be preserved to the maximum extent permitted taking into account
Affirmer's express Statement of Purpose. In addition, to the extent the Waiver
is so judged Affirmer hereby grants to each affected person a royalty-free,
non transferable, non sublicensable, non exclusive, irrevocable and
unconditional license to exercise Affirmer's Copyright and Related Rights in
the Work (i) in all territories worldwide, (ii) for the maximum duration
provided by applicable law or treaty (including future time extensions), (iii)
in any current or future medium and for any number of copies, and (iv) for any
purpose whatsoever, including without limitation commercial, advertising or
promotional purposes (the "License"). The License shall be deemed effective as
of the date CC0 was applied by Affirmer to the Work. Should any part of the
License for any reason be judged legally invalid or ineffective under
applicable law, such partial invalidity or ineffectiveness shall not
invalidate the remainder of the License, and in such case Affirmer hereby
affirms that he or she will not (i) exercise any of his or her remaining
Copyright and Related Rights in the Work or (ii) assert any associated claims
and causes of action with respect to the Work, in either case contrary to
Affirmer's express Statement of Purpose.

4. Limitations and Disclaimers.

  a. No trademark or patent rights held by Affirmer are waived, abandoned,
  surrendered, licensed or otherwise affected by this document.

  b. Affirmer offers the Work as-is and makes no representations or warranties
  of any kind concerning the Work, express, implied, statutory or otherwise,
  including without limitation warranties of title, merchantability, fitness
  for a particular purpose, non infringement, or the absence of latent or
  other defects, accuracy, or the present or absence of errors, whether or not
  discoverable, all to the greatest extent permissible under applicable law.

  c. Affirmer disclaims responsibility for clearing rights of other persons
  that may apply to the Work or any use thereof, including without limitation
  any person's Copyright and Related Rights in the Work. Further, Affirmer
  disclaims responsibility for obtaining any necessary consents, permissions
  or other rights required for any use of the Work.

  d. Affirmer understands and acknowledges that Creative Commons is not a
  party to this document and has no duty or obligation with respect to this
  CC0 or use of the Work.

For more information, please see
<http://creativecommons.org/publicdomain/zero/1.0/>
```

## NLayer cross-check

The low-rate mixed-block packing is checked against the MIT-licensed
[NLayer commit 046c7ce422970f8f0f0bc205d6c6341bcb7debf1](https://github.com/naudio/NLayer/tree/046c7ce422970f8f0f0bc205d6c6341bcb7debf1).
Its exact `LICENSE` text is reproduced below before studying the decoder.

```text
MIT License

Copyright (c) 2018 Mark Heath, Andrew Ward & Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## MPEG Layer III, the region boundary

Recorded because it is silent and cost real debugging time. The boundary between
the first and second Huffman regions of a switched granule is often written as
the constant 36. MPEG-1 uses it for every switched shape. MPEG-2 uses the eighth
long band for start and stop blocks, giving 54, and three short bands across
three windows for short blocks, giving 36. The latter includes mixed blocks:
counting their low long bands and then short windows incorrectly gives 48.
MPEG-2.5 at 8 kHz has wider bands, giving 108 and 72 respectively. Its mixed
block still uses two long transform subbands. Long scalefactors cover 72 lines,
but lines 36 to 71 already contain short windows in frequency order, with the
three windows interleaved. They need conversion to AudioKodec's internal
six-values-per-window layout, rather than four long transforms.

`Mp3SwitchedBlockFixtures` builds reservoir-free packets from published side
information and table 1 codewords. Comparisons with FFmpeg's public `mp3float`
decoder cover all nine rates, all four switched shapes and normal-block controls,
with different Huffman tables, same-table controls and varied scalefactors.
Isolated coefficients check every line of the 8 kHz transition region. A local
call to libmpg123's public floating-point output API independently agrees with
FFmpeg there, within 1e-6. The pinned CC0 minimp3 reference's four long transforms
and mixed Huffman table walk disagree with those executable controls; its code
is not used as the authority. NLayer was also checked, without translation.
No copyleft implementation source is read.

## MPEG Layer III, intensity stereo

The uncoded highest long and short bands inherit the preceding intensity
position. An inherited MPEG-1 position of 7 continues to disable intensity.

The MPEG side information and published Huffman codewords generate 270 highest
band comparisons and 18 MPEG-1 illegal coded-position controls against FFmpeg's public
`mp3float` decoder, covering all nine rates, long, short and mixed blocks,
mid-side enabled and disabled, and both MPEG-2 gain ladders. The 44.1 kHz long
case places its coefficient at line 500, reproducing issue #78. The original
ISO/IEC 11172-3 and 13818-3 stereo arrangement remains the model; no source is
translated. A local call to libmpg123's public float output API also checks the
MPEG-2 highest-band value when the preceding two-bit position is 3. It
agrees with FFmpeg to below 1e-6; minimp3's propagation of its illegal marker
to the uncoded band is not followed.

Short blocks find the last nonzero right-channel band independently for each
window, before stereo processing writes new right-channel values. The mixed
block's long-scalefactor region retains its coupled boundary, including the
8 kHz part that uses short transforms. Coded trailing zeros are not evidence
of right-channel spectrum. Another 183 FFmpeg comparisons cover independent
windows, mixed blocks, mid-side combinations, both MPEG-2 gain ladders, explicit
right-channel zeros and highest bands next to real right-channel data.

## Opus

Specifications: RFC 6716 (the codec) and RFC 7845 (Ogg encapsulation). Both are
freely published and may be implemented from without qualification.

RFC 6716 also carries the complete reference implementation in its body, under a
BSD-style licence stated in the RFC itself, and the same code is published as
libopus under BSD-3-Clause. That is permissive and not on the never-opened list,
so it may be ported from. Where AudioKodec follows it rather than the prose, the
comment says so.

The range decoder is the one place where following the reference exactly matters
more than usual. Its renormalisation reads a byte, keeps a partial one, and
combines them with a shift that falls out of two constants the RFC derives
rather than states: with 32-bit codes and 8-bit symbols the extra is
`(32 - 2) % 8 + 1`, which is 7. Deriving it in the code rather than writing 7 in
is what stops it silently becoming wrong if the widths ever change.

### The libopus port

The decoder is a translation of libopus, recorded here before the first line
was written, as rule 2 asks.

| Field | Value |
|---|---|
| Repository | https://github.com/xiph/opus |
| Tag | v1.5.2 |
| Commit | `ddbe48383984d56acd9e1ab6a090c54ca6b735a6` |
| Licence | BSD-3-Clause, the `COPYING` file at that commit |
| Files | `celt/` and `silk/` decoder sources, `src/opus_decoder.c`, `src/opus_multistream_decoder.c`, `src/opus_projection_decoder.c`, `src/mapping_matrix.c` |
| Not ported | Every encoder source, the `dnn/` tree (deep PLC, DRED and OSCE), and the architecture-specific `x86/`, `arm/` and `mips/` variants |

The licence text, which a binary release of AudioKodec has to carry because
the port is a derivative of it:

```
Copyright 2001-2023 Xiph.Org, Skype Limited, Octasic,
                    Jean-Marc Valin, Timothy B. Terriberry,
                    CSIRO, Gregory Maxwell, Mark Borgerding,
                    Erik de Castro Lopo, Mozilla, Amazon

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

- Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.

- Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.

- Neither the name of Internet Society, IETF or IETF Trust, nor the
names of specific contributors, may be used to endorse or promote
products derived from this software without specific prior written
permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

The same file ends by noting that Opus is subject to royalty-free patent
licences from Xiph.Org, Microsoft and Broadcom, published as IETF IPR
disclosures 1524, 1914 and 1526. Those grants cover implementations of the
format, this one included, and are the caller's to read, not the code's.

Arithmetic follows the default float build, as AGENTS.md asks of every port:
CELT is ported in `Float` from its float path, and SILK, whose decoder is
fixed point in every build, in `Int` and `Long` with its own 32-bit wrapping
where the C relies on it.

Concentus, the managed-language translation of libopus, was not used. Both
layers are translated from libopus itself, so no licence beyond the one above
applies to them.

The test oracles for this work are the reference programs built from the same
commit and run: `opus_demo`, `opus_compare`, and a small command line over the
multistream and projection API written for these tests
(`audiokodec/src/jvmTest/resources/opus_multistream_oracle.c`).

`opus_compare.c` is also translated, into the test suite as `OpusCompare.kt`,
because its measure is part of the specification rather than a tool around it:
RFC 6716 defines conformance by it. It is the same commit and the same licence
as above. The translation evaluates the spectrum with an FFT where the C sums
each bin directly, which changes only the rounding, and scores identically to
the program on the vectors.

The vectors it scores are the RFC 8251 set:

| Field | Value |
|---|---|
| Archive | https://opus-codec.org/static/testvectors/opus_testvectors-rfc8251.tar.gz |
| SHA-256 | `6b26a22f9ba87b2b836906a9bb7afec5f8e54d49553b1200382520ee6fedfa55` |
| Licence | None stated in the archive |
| Committed | No: no licence travels with them, and the references run to about 120 MB |

`OpusConformanceTest` uses an unpacked copy in `reference/opus_newvectors`, or
the archive in `reference/` or the build directory, or downloads the archive
there and checks its digest, and skips itself when none of that is possible.

## ALAC

There is no published specification of Apple Lossless beyond its source code,
which Apple released under the Apache License 2.0, the licence AudioKodec
ships under. The decoder is a translation of it, recorded here before the
first line was written.

| Field | Value |
|---|---|
| Repository | https://github.com/macosforge/alac |
| Commit | `c38887c5c5e64a4b31108733bd79ca9b2496d987` |
| Licence | Apache-2.0, the `LICENSE` file at that commit and the header of every source file |
| Files | `codec/ALACDecoder.cpp`, `ag_dec.c`, `dp_dec.c`, `matrix_dec.c`, `ALACBitUtilities.c` and their headers, and `ALACMagicCookieDescription.txt` for the configuration and channel orders. `codec/ALACEncoder.h` and `codec/ALACAudioTypes.h` describe the oracle's public API |
| Not ported | The encoder, until the decoder passes; the `convert-utility` command line |

The `codec` folder also holds a copy of the Apple Public Source License 2.0,
left from the code's first release. Every source file states Apache-2.0 in its
own header, and so does the repository, so that is the licence that applies.
Apache-2.0 asks a redistribution to keep the copyright notices, which the
ported files do in their headers, and to state changes, which is what a
translation into Kotlin is.

The integer arithmetic is the reference's, 32-bit and wrapping where it
wraps. Two places in the C rely on behaviour the language leaves undefined:
a shift of `1` by `-1` when a predictor's denominator shift is zero, and
shifts by 32 or more when a stream's mixing shift is that large. Both are
reproduced as x86 executes them, which masks the count to its low five bits,
and which is also what Kotlin's shifts do.

The test oracle is `alacconvert`, built from the same commit and run, which
encodes WAV to ALAC in CAF and decodes it back.

For 20-bit audio, `audiokodec/src/jvmTest/resources/alac_20bit_oracle.cpp`
links the same pinned `libalac.a` and calls its public `ALACEncoder` API.
It supplies high-aligned 20-bit PCM in 24-bit slots and wraps the returned
packets and opaque magic cookie in CAF, bypassing `alacconvert`'s faulty
20-bit WAV reader. Mono, stereo and 5.1 tone and full-range noise fixtures
exercise prediction and escape coding. The JVM tests require exact input
integers through `IntPcmAccess`, including extrema, low bits, short final
packets and packet-boundary seeks. The 5.1 expectation uses the channel
order declared in Apple's public header independently of the Kotlin decoder.

## WavPack

WavPack's format is documented in prose (`doc/WavPack5FileFormat.pdf`) for
its blocks and metadata, and by its source for everything inside them. The
decoder is a translation of the reference library's decoding half, recorded
here before the first line was written.

| Field | Value |
|---|---|
| Repository | https://github.com/dbry/WavPack |
| Tag | `5.9.0`, commit `5803634a030e2a11dba602ba057b89cc34486c67` |
| Licence | BSD 3-Clause, the `COPYING` file at that commit, reproduced in `NOTICE` |
| Files | `src/unpack.c`, `src/read_words.c`, `src/entropy_utils.c`, `src/decorr_utils.c`, `src/unpack_floats.c`, the block and metadata handling in `src/open_utils.c` and `src/unpack_utils.c`, `src/wavpack_local.h`, `include/wavpack.h`, and `doc/WavPack5FileFormat.pdf` |
| Not ported | Pre-4.0 files (`unpack3.c`), DSD (`unpack_dsd.c`), the correction file of hybrid lossless mode, the encoder, and the assembly versions of the decorrelation loops, which compute the same as the C |

The arithmetic is the reference's: 32-bit and wrapping where it wraps, with
its unsigned quantities kept in signed integers and compared and divided
unsigned. The logarithm and exponent tables were extracted from
`entropy_utils.c` by script. Where the reference reads past the end of a
damaged bitstream it starts again from the beginning and lets the block
checksum catch the result; the translation reads zeros there, which the
checksum catches the same way, and a block the reference would mute is an
error instead.

## Musepack

The decoder is a translation of libmpcdec, recorded here before the first
line was written. Musepack's last source release is SVN revision 475; the
decoder follows revision 495, which Debian and Ubuntu ship and whose
`mpcenc` the oracle runs. Beyond comments, the two revisions' decoders
differ only in the stream version 7 header reader, which is not ported, and
in the demuxer's handling of a frame that yields no samples after a seek.

| Field | Value |
|---|---|
| Download | http://archive.ubuntu.com/ubuntu/pool/universe/libm/libmpc/libmpc_0.1~r495.orig.tar.gz |
| Version | SVN revision 495, SHA-256 `5182d7a30eec1b338cafc9c1706d663a6c8dc217b1d90187054512074f662202`, which Ubuntu's `libmpc_0.1~r495-2build1.dsc` also states |
| Licence | BSD 3-Clause, `libmpcdec/COPYING` and the header of every file read, reproduced in `NOTICE` |
| Files | `libmpcdec/mpc_decoder.c`, `mpc_demux.c`, `streaminfo.c`, `mpc_bits_reader.c` and `.h`, `huffman.c` and `.h`, `requant.c` and `.h`, `synth_filter.c`, `mpcdec_math.h`, `decoder.h`, `internal.h`, and `include/mpc/mpcdec.h`, `reader.h` and `streaminfo.h` |
| Never opened | `include/mpc/mpc_types.h`, `mpcmath.h`, `minimax.h` and `datatypes.h`, which are LGPL, and everything outside `libmpcdec` |
| Not ported | Stream versions 7 and older, the fixed-point build, replay gain applied to the output, and chapters |

Two values came from outside the files read. The scale of the float output
is set in `mpc_types.h`, which is LGPL; the decoder's samples match the
reference's only at a scale of 2^-15 per unit, which is what the port uses.
The CRC of the stream header is computed by `common/crc32.c`, which is not
part of libmpcdec and was not read; it is the standard CRC-32, which every
file the encoder writes passes.

The tables were extracted from `huffman.c` and `synth_filter.c` by
`reference/mpc/gen_kotlin.py`, so none was typed. Every canonical Huffman
table fills its code space exactly and maps every code to a symbol inside
its symbol table. The synthesis window `Di_opt` is the MPEG-1 window that
the MPEG decoder already uses, entry for entry, and the decoder takes it
from there. The tables a formula defines are computed rather than copied,
after the script showed that each formula reproduces the C table: the
binomials of the enumerative coder and their code lengths, the bundled
quantiser triples, the parity table. One entry differs: `Cnk_lost`, which
counts the short codes of a combination's rank, is declared with 512
entries and initialised with 511, so its entry for 16 of 32 is 0 rather than
472661434. The entry is used when 16 of 32 bands are coded mid and side, so
the decoder keeps the reference's 0.

## Speex

The decoder is a translation of libspeex and speexdec, recorded here before
the first line was written.

| Field | Value |
|---|---|
| Source | https://github.com/xiph/speex.git, tag Speex-1.2.1, commit `5dceaaf3e23ee7fd17c80cb5f02a838fd6c18e01` |
| Licence | BSD 3-Clause, `COPYING` and the header of every file read, reproduced in `NOTICE` |
| Files | In `libspeex`: `bits.c`, `nb_celp.c` and `.h`, `sb_celp.c` and `.h`, `modes.c`, `modes_wb.c` and `modes.h`, `filters.c` and `.h`, `lsp.c` and `.h`, `quant_lsp.c` and `.h`, `ltp.c` and `.h`, `cb_search.c` and `.h`, `stereo.c`, `speex.c`, `speex_callbacks.c`, `speex_header.c`, `arch.h`, `math_approx.h`, `stack_alloc.h` and `os_support.h`, and the table files `exc_*_table.c`, `gain_table.c`, `gain_table_lbr.c`, `hexc_table.c`, `hexc_10_32_table.c`, `high_lsp_tables.c` and `lsp_tables_nb.c`; `include/speex/*.h`; and `src/speexdec.c` for how the tool sets up the decoder and trims its output |
| Never opened | `libspeex/bfin.h`, `libspeex/fftwrap.c`, `libspeex/quant_lsp_bfin.h` and `src/getopt*`, which mention the GPL, and everything else outside the decoder |
| Not ported | The encoder, the fixed-point build, the SSE and Blackfin kernels, packet loss concealment, the jitter buffer and speexdsp |

The floating-point build is the one translated, without SSE, which changes
the order of some sums: Ubuntu's `speexdec` is built with SSE and its output
differs from the plain build's by up to 0.62 of a 16-bit step, so the oracle
is the plain build, compiled from these sources. Where C promotes a sum to
double because of a double constant, the translation computes in double too
and rounds where C stores to a float.

The tables were extracted from the table files and from `nb_celp.c`,
`sb_celp.c`, `filters.c` and `stereo.c` by `reference/speex/gen_kotlin.py`, so none was typed; the script checks every
table's length against the size its declaration states, and writes float
constants with the precision C gives them, single where the literal has an
`f` and rounded from double where it has none.

`speexdec` works page by page, and the decoder reproduces its timing rather
than the granule positions alone: a stream whose first audio page is not its
last loses the encoder's delay and the decoder's lookahead at the start and
is the final granule position long; a stream of one audio page loses nothing
at the start and runs on for the decoder's lookahead; and either ends where
the frames run out. Every file in the oracle suite is exactly as long as
`speexdec`'s output.

## AMR

The decoders are translations of opencore-amr, recorded here before the
first line was written. opencore-amr packages the AMR and AMR-WB decoders of
Android's PacketVideo OpenCORE, which are the 3GPP fixed-point reference
decoders reworked for speed, under the Apache License 2.0; PacketVideo
obtained the 3GPP Organizational Partners' permission to distribute the
derived files under it, as each file's header states.

| Field | Value |
|---|---|
| Source | https://git.code.sf.net/p/opencore-amr/code, tag v0.1.6, commit `7dba8c32238418ce0b316a852b2224df586ca896` |
| Licence | Apache License 2.0, `LICENSE` and the header of every file read; the parts of `opencore/NOTICE` that apply to the decoders are reproduced in `NOTICE` |
| Files | `amrnb/wrapper.cpp` and `amrwb/wrapper.cpp`, which say how a frame in the storage format reaches each decoder; in `opencore/codecs_v2/audio/gsm_amr/amr_nb`, the decoder's sources under `dec/src` that `amrnb/Makefile.am` builds, and under `common` the sources and headers they call; in `amr_wb/dec`, the sources `amrwb/Makefile.am` builds and their headers |
| Never opened | Nothing in the tree mentions a copyleft licence. The encoders, the ARM assembly variants of the basic operations and the IF2 and ETS frame formats were not read beyond seeing that they are not used |
| Not ported | The encoders, the frame formats other than RFC 4867's storage format, and the quality bit, which opencore-amr does not consult either |

The basic operations are translated from the portable C forms the x86 build
compiles, `basic_op_c_equivalent.h` and its neighbours for AMR and
`pvamrwbdecoder_basic_op_cequivalent.h` for AMR-WB. The two codecs' sets
differ in how they saturate and shift, and AMR-WB's `norm_s` goes through
`normalize_amr_wb`, so each decoder has its own. AMR's overflow flag is set
by the basic operations and read in one place, after a synthesis filter that
never sets it, so it is left out.

The tables were extracted from the sources by `reference/amr/gen_kotlin.py`,
so none was typed. The script resolves each table's declared size with the
definitions in scope for its own file and codec, AMR and AMR-WB defining
some names differently, and checks every table's length against it. One
table, `dfh_M14k`, is declared one entry longer than its initialiser, and
the script fills that entry with the zero C gives it.

The test oracles are opencore-amr's own decoders, through its public
interfaces in `amr_oracle.c`, and two encoders that are only run: opencore's
AMR encoder, and vo-amrwbenc for AMR-WB, from the same project at
https://git.code.sf.net/p/opencore-amr/vo-amrwbenc, tag v0.1.3, commit
`080af7540562e4adfbd3312837fe5fc6771c4cd0`, Apache License 2.0.

## GSM 06.10

The decoder is a translation of libgsm, recorded here before the first line
was written.

| Field | Value |
|---|---|
| Download | https://www.quut.com/gsm/gsm-1.0.22.tar.gz |
| Version | 1.0.22, SHA-256 `f0072e91f6bb85a878b2f6dbf4a0b7c850c4deb8049d554c65340b3bf69df0ac` |
| Licence | The authors' permission in `COPYRIGHT`: use, copying, modification and distribution for any purpose, provided the notice stays and the authors are not held responsible. Reproduced in `NOTICE` |
| Files | `src/decode.c`, `gsm_decode.c`, `rpe.c`, `long_term.c` and `short_term.c` for their decoding halves, `add.c`, `table.c`, `gsm_create.c`, and `inc/private.h` and `inc/gsm.h` |
| Not ported | The encoder, the `toast` tool, and the floating-point shortcuts `FAST` and `USE_FLOAT_MUL` select, which the library's own build leaves off |

The library is built with `SASR`, so a right shift of a negative value is
arithmetic, as Kotlin's is, and with `WAV49`, which compiles in Microsoft's
layout. That layout stores the same fields in the same order as the plain
one, least significant bit first and two frames to a 65-byte block, so both
are read by one field reader rather than by the reference's two unrolled
unpackers.

## iLBC

The decoder is a translation of WebRTC's fixed-point iLBC decoder, recorded
here before the first line was written. libilbc packages WebRTC's iLBC on its
own, with the signal processing functions it calls.

| Field | Value |
|---|---|
| Source | https://github.com/TimothyGu/libilbc, tag v3.0.4, commit `cd064edf2c6c104a4e1fd87b34fd24cfa6dbe401`, with its `abseil-cpp` submodule at `1bae23e32ba1f1af7c7d1488a69a351ec96dc98d` for the headers the build needs |
| Licence | BSD 3-Clause, `COPYING`, with Google's additional patent grant in `PATENTS`; both reproduced in `NOTICE`. `common_audio/third_party/spl_sqrt_floor` is Wilco Dijkstra's public domain square root, as its header and `LICENSE` record |
| Files | In `modules/audio_coding/codecs/ilbc`, `ilbc.c` for the decoder's interface, `defines.h`, `constants.c`, and every file the decoder reaches: `decode.c`, `unpack_bits.c`, `swap_bytes.c`, `index_conv_dec.c`, `simple_lsf_dequant.c`, `lsf_check.c`, `decoder_interpolate_lsf.c`, `lsf_interpolate_to_poly_dec.c`, `interpolate.c`, `lsf_to_poly.c`, `lsf_to_lsp.c`, `get_lsp_poly.c`, `bw_expand.c`, `decode_residual.c`, `state_construct.c`, `cb_construct.c`, `gain_dequant.c`, `get_cd_vec.c`, `create_augmented_vec.c`, `do_plc.c`, `comp_corr.c`, `hp_output.c`, `xcorr_coef.c`, `init_decode.c`, `enhancer_interface.c`, `enhancer.c`, `get_sync_seq.c`, `nearest_neighbor.c`, `refiner.c`, `my_corr.c`, `enh_upsample.c`, `smooth.c` and `smooth_out_data.c`; in `common_audio/signal_processing`, the portable C forms of the functions those call and `include/signal_processing_library.h` and `spl_inl.h` for its macros; and `spl_sqrt_floor.c` |
| Never opened | Nothing in the tree mentions a copyleft licence. abseil, Apache 2.0, supplies headers to the oracle build only and was not read. ffmpeg's own iLBC decoder, which does not give WebRTC's samples, was only run |
| Not ported | The encoder, the decoder without its enhancer, which `WebRtcIlbcfix_DecoderInit` never selects, and the branch for a previous enhancer state of 2, which only WebRTC's NetEq concealment entry point sets |

The decoder's set of functions was found by linking a decode-only program
over the library with unused sections removed, so nothing it reaches was
missed. The tables were extracted from `constants.c` by
`reference/ilbc/gen_kotlin.py`, which checks each table's length against its
declared size, so none was typed.

## G.722 and G.726

Both decoders are written from the Recommendations, with no implementation
read. The ITU's reference code for them is part of the Software Tool Library,
whose licence does not allow an Apache-2.0 port, and it was never opened.

| Field | Value |
|---|---|
| G.726 | ITU-T Recommendation G.726 (12/90), `T-REC-G.726-199012-I`, SHA-256 `0b2900da45a774526a5a4ff9aad5f277b5727ab1d9831a038e3d66fef07e86f1` |
| G.722 | ITU-T Recommendation G.722 (11/88), `T-REC-G.722-198811-S`, SHA-256 `01e93c977dd3d9c5e2c77c0b878bb7e97291f49a34ae5031778e73c104dd1d56` |
| G.726 test sequences | Appendix II (03/91), `T-REC-G.726-199103-I!AppII!SOFT-ZST-E`, SHA-256 `2af43f18c4a95e57ef137721d85988627973e8d5c0ee325cf30f755b70c46688` |
| G.722 test sequences | Appendix II (03/87), `T-REC-G.722-198703-S!AppII!ZPF-E`, SHA-256 `b1691633a13e134e59b703059ec55d2935e5053f07a60032cd3b23834d228731` |
| Never opened | The Software Tool Library (ITU-T G.191) and its G.722 and G.726 modules, spandsp, and ffmpeg's source |

Every constant table was extracted from the Recommendations' text by
`reference/g72x/gen_tables.py`, which checks each against a property it
must have: G.722's inverse quantizer outputs lie inside their decision
intervals, its 5-bit and 4-bit outputs are the 6-bit quantizer's decision
levels, its log-to-linear table is 2048 times 2 to the power i/32, and every
G.726 output level is the same for a code word and its one's complement.
G.726's scale factor multipliers were also read off the page images.

G.726's quantized difference signal is carried as a 16-bit signed magnitude
at every rate, which 40 kbit/s requires and the other rates permit; the
transition detector's threshold follows that choice. G.722's receive QMF is
left partly open by the Recommendation and untested by its sequences; it
accumulates exactly, truncates, and keeps one bit more than the 15 of XOUT,
which makes its 16-bit output equal ffmpeg's.

Two details of the log PCM output stage, which the conformance test adds
and the decoder does not have, are settled by the sequences rather than the
text: COMPRESS takes a wrapped SR of -32768 as a magnitude of zero, as its
`(65536 - SR) & 32767` says, and for A-law a negative magnitude that falls
exactly on a decision value belongs to the interval below it, as Table 15
shows.

## DSD

Both container readers are written from the format specifications, as
dsd-guide.com publishes them; the conversion to PCM is AudioKodec's own
design, so no implementation was read.

| Field | Value |
|---|---|
| DSF | Sony, DSF File Format Specification, version 1.01, https://dsd-guide.com/sites/default/files/white-papers/DSFFileFormatSpec_E.pdf, SHA-256 `2c154f3e82ea835a8023d08308adc26344b0fcec627c14e585640e0111740da4` |
| DSDIFF | Philips, Direct Stream Digital Interchange File Format, version 1.5 (2004-04-27), https://dsd-guide.com/sites/default/files/white-papers/DSDIFF_1.5_Spec.pdf, SHA-256 `fba0c3053c81f7af93eb8bb3ea70b6f78bb1188578a844c054988af2ff2f0b01` |
| Never opened | ffmpeg's DSD reader and converter, and every other DSD implementation |

The three filter stages are Kaiser-windowed sincs designed by
`design-dsd-filters.py` at the repository's root, which states the design,
checks each stage's passband ripple and the attenuation of every band that
would alias, and prints the taps as Kotlin; they are pasted unedited. The
`ID3 ` chunk in DSDIFF is not in the specification but is what every writer
of DSDIFF tags uses.

## Channel mixing

The downmix coefficients come from the Recommendation; no implementation was
read.

| Field | Value |
|---|---|
| Specification | Recommendation ITU-R BS.775-3 (08/2012), Annex 4, Table 2, `R-REC-BS.775-3-201208-S`, SHA-256 `61d3baba8cab389e69cd41c86e97054d82127281af5e1f5b5edeb7633ac8e3c1` |
| Never opened | ffmpeg's source, whose mixing for positions BS.775 does not cover was measured by running it |

BS.775 covers 3/2 material without a low-frequency channel. The rules for
the side, back centre, front centre pair and top front positions were
measured from ffmpeg's float mixing with a different tone in every input
channel, and the oracle suite holds AudioKodec to them.

## Loudness

The loudness meter is a translation of libebur128, recorded here before the
first line was written.

| Field | Value |
|---|---|
| Source | https://github.com/jiixyj/libebur128, tag v1.2.6, commit `67b33abe1558160ed76ada1322329b0e9e058b02` |
| Licence | MIT, in `COPYING`, reproduced in `NOTICE` |
| Files | `ebur128/ebur128.c` and `ebur128/ebur128.h`; `ebur128/queue/sys/queue.h`, BSD, only to build the oracle |
| Never opened | Nothing in the tree mentions a copyleft licence. ffmpeg's `ebur128` filter was only run |
| Not ported | The histogram mode, the change of parameters mid-stream, the integer sample paths, and the 49-tap true peak interpolator, replaced by AudioKodec's own resampler |

The figures the tests expect come from EBU Tech 3341 (2023), Table 1, cases
1, 2, 3 and 6, SHA-256 `dd5a7b0b611024e616b86a42c89eed0ded33f3129afc1994204885216fa1619e`,
and EBU Tech 3342 (2023), Table 1, case 1, SHA-256
`a12cb59fa4a8258d400bc931c7a79326a83a27c1cfd7b38220bc85ba5cfa89d8`.

## Resampling

The sinc resampler is a translation of speexdsp's, recorded here before the
first line was written. speexdsp is the signal processing half of Speex, and
its resampler stands on its own in one file.

| Field | Value |
|---|---|
| Source | https://github.com/xiph/speexdsp, tag SpeexDSP-1.2.1, commit `1b28a0f61bc31162979e1f26f3981fc3637095c8` |
| Licence | BSD 3-Clause, in `COPYING`, which is the same text as libspeex's, and the notice at the head of `resample.c`; both reproduced in `NOTICE` |
| Files | `libspeexdsp/resample.c`, in its floating-point build without SIMD, with `libspeexdsp/arch.h` for the float forms of its arithmetic macros and `include/speex/speex_resampler.h` for its interface |
| Never opened | `libspeexdsp/fftwrap.c` and `configure.ac`, which mention the GPL as a build option, and everything else outside the resampler |
| Not translated | The fixed-point build, the SSE and NEON inner loops, and the handling of a rate or quality change mid-stream; none changes a sample of a stream at a fixed rate |

libsamplerate, BSD 2-Clause since 2016, was the other candidate. speexdsp was
chosen because its resampler is one self-contained file that computes its
filter per stream, where libsamplerate's best converter carries large
precomputed coefficient tables, and because the Speex decoder already
translates code from the same authors under the same licence. The
straight-line mode is AudioKodec's own and follows no reference.

## True Audio

The True Audio format page on the authors' site could not be reached while
the decoder was written, and libtta, their current library, is LGPL, so it
was never opened. The decoder is a translation of the authors' earlier
decoder for hardware players instead, which is BSD-licensed and decodes the
same `TTA1` format, recorded here before the first line was written.

| Field | Value |
|---|---|
| Download | https://sourceforge.net/projects/tta/files/tta/ttalib-hwplayer/ttalib-hwplayer-1.2.tgz/download |
| Version | 1.2, SHA-256 `2028573a43c91acf1a6048d1804f60e9a6d28f493cbcc0fed99895fdc88cf26f`, the value SourceForge publishes for the file |
| Licence | BSD 3-Clause, in the header of every source file and the `README`, reproduced in `NOTICE` |
| Files | `ttadec.c`, `ttadec.h`, `filter.h` and `ttalib.h` |
| Not ported | The player's own limits, which reject sample rates outside a fixed list and more than eight channels, and its ID3 reading, which AudioKodec's tag readers already do |

The arithmetic is the reference's: 32-bit and wrapping in the filter, with
the Rice sums unsigned. Where the reference would read past the end of a
damaged frame, or mute a sample out of range, the translation reports the
frame as damaged; the frame CRC is checked before decoding, as the
reference checks it after. The reference predates 20-bit files, and its
range check would mute them; the format codes a 20-bit sample as the 24-bit
value the WAV file held, which `tta -d` and ffmpeg both give back, so the
check is made against the whole bytes instead.

## Monkey's Audio

Monkey's Audio has no format document beyond its SDK, so the decoder is a
translation of the SDK's decoding half, recorded here before the first line
was written.

| Field | Value |
|---|---|
| Download | https://monkeysaudio.com/files/MAC_1327_SDK.zip, linked from https://monkeysaudio.com/developers.html |
| Version | 13.27, SHA-256 `c47c6b36f6a7bd50d990f2eb36a70915c0074a7b9634be396c94464905e76686` |
| Licence | BSD 3-Clause, `License.txt` in the archive, reproduced in `NOTICE` |
| Files | `Source/MACLib/APEHeader.cpp`, `APEInfo.cpp`, `APEDecompress.cpp`, `APEDecompressCore.cpp`, `UnBitArrayBase.cpp`, `UnBitArray.cpp`, `NewPredictor.cpp`, `NNFilter.cpp`, `NNFilterGeneric.cpp`, `ScaledFirstOrderFilter.h`, `Prepare.cpp`, `FloatTransform.cpp` and the CRC in `Source/Shared` |
| Not ported | Files from before version 3.99 (`Source/MACLib/Old`), the encoder, MD5 checking of the whole file, and the vectorised filter loops, which compute the same as the generic one |

The arithmetic is the SDK's: 32-bit and wrapping in the predictors and
filters, with the 64-bit predictor kept for 32-bit audio where the SDK keeps
it, and the 16-bit filter variant used where the SDK uses it, including the
interim mode it retries a 24-bit frame in when the first decode fails its
checksum. Where the SDK reads past the end of a damaged frame, the
translation reads zeros, and the frame checksum reports the result.

## AAC constant tables

The AAC decoder is written from ISO/IEC 14496-3:2009, not ported. Its tables
were extracted from the standard's text by script, so that none was typed by
hand, and then agreed entry by entry with a second rendering: the AAC decoder
PacketVideo contributed to the Android Open Source Project, which is
Apache-2.0. Only its tables and table lookups were read; its decoding was
not used as a guide.

| Field | Value |
|---|---|
| Repository | https://github.com/aosp-mirror/platform_frameworks_base |
| Tag | `android-2.3.7_r1`, commit `3f2821425f1ab6eddb76a8725e3f2c3edb5d8b07` |
| Path | `media/libstagefright/codecs/aacdec` |
| Licence | Apache-2.0, stated in the header of every file |
| Agreed | All 1362 codewords of the twelve Huffman codebooks, by compiling its codeword lookup functions in a harness and running every codeword through them; all thirteen band offset tables against `sfb.cpp`; the noise shaping band limits against `get_tns.cpp`; and the computed sine and Kaiser-Bessel windows against its Q15 tables in `window_tables_fxp.cpp`, to within one step of their rounding |

The extraction found no disagreement. Table 4.141 is identical to Table
4.139, and one array serves both. Table 4.157 lists no noise shaping limits
for 7.35 kHz, which uses the 8 kHz tables throughout, and takes 8 kHz's.

## Never opened

These are copyleft or otherwise incompatible with an Apache-2.0 release. The
list is here to be checked against, not to be worked around.

ffmpeg and libav source, libmad, mpg123, LAME, Shine, TwoLAME, FAAD2,
FDK-AAC, liba52, libdca, taglib, mutagen, jaudiotagger, JLayer, FluidSynth,
libopenmpt, the sox source tree, spandsp, and the ITU-T Software Tool
Library.

If a search result shows code from any of them, stop at the licence header.


## FLAC decoder conformance corpus (CC0-1.0)

Martijn van Beurden's FLAC decoder testbench, published by the IETF CELLAR working group at
https://github.com/ietf-wg-cellar/flac-test-files. Pinned commit:
`aa7b0c6cf32994c106ae517a08134c28a96ff5b2`. Test data only; the 86 FLAC vectors
and their documentation are covered by the repository's CC0 waiver.
`test-data/flac-conformance/manifest.tsv` records every byte length and SHA-256.
Two small vectors are committed; the complete corpus is downloaded on demand
and verified against that manifest. Complete upstream licence text:

```text
Creative Commons Legal Code

CC0 1.0 Universal

    CREATIVE COMMONS CORPORATION IS NOT A LAW FIRM AND DOES NOT PROVIDE
    LEGAL SERVICES. DISTRIBUTION OF THIS DOCUMENT DOES NOT CREATE AN
    ATTORNEY-CLIENT RELATIONSHIP. CREATIVE COMMONS PROVIDES THIS
    INFORMATION ON AN "AS-IS" BASIS. CREATIVE COMMONS MAKES NO WARRANTIES
    REGARDING THE USE OF THIS DOCUMENT OR THE INFORMATION OR WORKS
    PROVIDED HEREUNDER, AND DISCLAIMS LIABILITY FOR DAMAGES RESULTING FROM
    THE USE OF THIS DOCUMENT OR THE INFORMATION OR WORKS PROVIDED
    HEREUNDER.

Statement of Purpose

The laws of most jurisdictions throughout the world automatically confer
exclusive Copyright and Related Rights (defined below) upon the creator
and subsequent owner(s) (each and all, an "owner") of an original work of
authorship and/or a database (each, a "Work").

Certain owners wish to permanently relinquish those rights to a Work for
the purpose of contributing to a commons of creative, cultural and
scientific works ("Commons") that the public can reliably and without fear
of later claims of infringement build upon, modify, incorporate in other
works, reuse and redistribute as freely as possible in any form whatsoever
and for any purposes, including without limitation commercial purposes.
These owners may contribute to the Commons to promote the ideal of a free
culture and the further production of creative, cultural and scientific
works, or to gain reputation or greater distribution for their Work in
part through the use and efforts of others.

For these and/or other purposes and motivations, and without any
expectation of additional consideration or compensation, the person
associating CC0 with a Work (the "Affirmer"), to the extent that he or she
is an owner of Copyright and Related Rights in the Work, voluntarily
elects to apply CC0 to the Work and publicly distribute the Work under its
terms, with knowledge of his or her Copyright and Related Rights in the
Work and the meaning and intended legal effect of CC0 on those rights.

1. Copyright and Related Rights. A Work made available under CC0 may be
protected by copyright and related or neighboring rights ("Copyright and
Related Rights"). Copyright and Related Rights include, but are not
limited to, the following:

  i. the right to reproduce, adapt, distribute, perform, display,
     communicate, and translate a Work;
 ii. moral rights retained by the original author(s) and/or performer(s);
iii. publicity and privacy rights pertaining to a person's image or
     likeness depicted in a Work;
 iv. rights protecting against unfair competition in regards to a Work,
     subject to the limitations in paragraph 4(a), below;
  v. rights protecting the extraction, dissemination, use and reuse of data
     in a Work;
 vi. database rights (such as those arising under Directive 96/9/EC of the
     European Parliament and of the Council of 11 March 1996 on the legal
     protection of databases, and under any national implementation
     thereof, including any amended or successor version of such
     directive); and
vii. other similar, equivalent or corresponding rights throughout the
     world based on applicable law or treaty, and any national
     implementations thereof.

2. Waiver. To the greatest extent permitted by, but not in contravention
of, applicable law, Affirmer hereby overtly, fully, permanently,
irrevocably and unconditionally waives, abandons, and surrenders all of
Affirmer's Copyright and Related Rights and associated claims and causes
of action, whether now known or unknown (including existing as well as
future claims and causes of action), in the Work (i) in all territories
worldwide, (ii) for the maximum duration provided by applicable law or
treaty (including future time extensions), (iii) in any current or future
medium and for any number of copies, and (iv) for any purpose whatsoever,
including without limitation commercial, advertising or promotional
purposes (the "Waiver"). Affirmer makes the Waiver for the benefit of each
member of the public at large and to the detriment of Affirmer's heirs and
successors, fully intending that such Waiver shall not be subject to
revocation, rescission, cancellation, termination, or any other legal or
equitable action to disrupt the quiet enjoyment of the Work by the public
as contemplated by Affirmer's express Statement of Purpose.

3. Public License Fallback. Should any part of the Waiver for any reason
be judged legally invalid or ineffective under applicable law, then the
Waiver shall be preserved to the maximum extent permitted taking into
account Affirmer's express Statement of Purpose. In addition, to the
extent the Waiver is so judged Affirmer hereby grants to each affected
person a royalty-free, non transferable, non sublicensable, non exclusive,
irrevocable and unconditional license to exercise Affirmer's Copyright and
Related Rights in the Work (i) in all territories worldwide, (ii) for the
maximum duration provided by applicable law or treaty (including future
time extensions), (iii) in any current or future medium and for any number
of copies, and (iv) for any purpose whatsoever, including without
limitation commercial, advertising or promotional purposes (the
"License"). The License shall be deemed effective as of the date CC0 was
applied by Affirmer to the Work. Should any part of the License for any
reason be judged legally invalid or ineffective under applicable law, such
partial invalidity or ineffectiveness shall not invalidate the remainder
of the License, and in such case Affirmer hereby affirms that he or she
will not (i) exercise any of his or her remaining Copyright and Related
Rights in the Work or (ii) assert any associated claims and causes of
action with respect to the Work, in either case contrary to Affirmer's
express Statement of Purpose.

4. Limitations and Disclaimers.

 a. No trademark or patent rights held by Affirmer are waived, abandoned,
    surrendered, licensed or otherwise affected by this document.
 b. Affirmer offers the Work as-is and makes no representations or
    warranties of any kind concerning the Work, express, implied,
    statutory or otherwise, including without limitation warranties of
    title, merchantability, fitness for a particular purpose, non
    infringement, or the absence of latent or other defects, accuracy, or
    the present or absence of errors, whether or not discoverable, all to
    the greatest extent permissible under applicable law.
 c. Affirmer disclaims responsibility for clearing rights of other persons
    that may apply to the Work or any use thereof, including without
    limitation any person's Copyright and Related Rights in the Work.
    Further, Affirmer disclaims responsibility for obtaining any necessary
    consents, permissions or other rights required for any use of the
    Work.
 d. Affirmer understands and acknowledges that Creative Commons is not a
    party to this document and has no duty or obligation with respect to
    this CC0 or use of the Work.

```

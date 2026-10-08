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
| Wave64 | Sony Pictures Digital Wave64 specification |
| AIFF | [Apple Audio Interchange File Format 1.3](https://blog.zamzar.com/wp-content/uploads/2014/10/aiffspecs.pdf), including the text chunk cardinalities on pages 23 and 24 |
| AIFF-C | Apple AIFF-C specification, including the compression type registry; [Inside Macintosh: Common Chunks](https://dev.os9.ca/techpubs/mac/Sound/Sound-82.html) for the compressed sample width field. Writer conventions are checked with `afconvert` and ffmpeg |
| AU | Sun and NeXT audio file format documentation |
| A-law and mu-law | ITU-T Recommendation G.711 |
| IMA and DVI ADPCM | IMA Digital Audio Focus and Technical Working Group, Recommended Practices for Enhancing Digital Audio Compatibility, plus Microsoft's IMA ADPCM WAVE type documentation |
| Microsoft ADPCM | [Microsoft Multimedia Data Standards Update, 15 April 1994](https://www.mmsp.ece.mcgill.ca/Documents/AudioFormats/WAVE/Docs/RIFFNEW.pdf), pages 22 to 26 |
| QuickTime IMA (`ima4`) | Apple QuickTime File Format documentation |
| QuickTime PCM and G.711 | [Apple Sound sample data](https://developer.apple.com/documentation/quicktime-file-format/sound_sample_data), for fixed-width sample layouts |
| CAF | [Apple Core Audio Format specification](https://developer.apple.com/library/archive/documentation/MusicAudio/Reference/CAFSpec/CAF_spec/CAF_spec.html), including compressed descriptions and the IMA4 packet geometry |
| MP4 and QuickTime containers | Apple QuickTime File Format documentation; [ISO/IEC 14496-12:2005](https://studylib.net/doc/26307294/mp4), clauses 8.31, 8.35 and 8.36 for per-track fragment defaults and data offsets |
| MP4 sample ownership | [Apple data references](https://developer.apple.com/documentation/quicktime-file-format/Media_data_reference_atom/Data_references), for self-reference flags and alias/URL entries. Packet ranges are checked against declared media-data payloads; external files and resource-fork references are refused |
| FLAC | [RFC 9639](https://www.rfc-editor.org/rfc/rfc9639.html), including sections 8.1 and 8.8 for the metadata body limit and native PICTURE layout |
| Ogg | RFC 3533, plus the Ogg mapping documents for Vorbis, Opus, FLAC and Speex |
| R128 gain tags | [RFC 7845 section 5.2.1](https://www.rfc-editor.org/rfc/rfc7845#section-5.2.1), for the signed 16-bit range, six-character ASCII grammar and gain reference |
| Vorbis | Xiph.Org Vorbis I specification; [comment specification](https://xiph.org/vorbis/doc/v-comment.html) for counted vectors, repeated names and the separate vendor header |
| MPEG audio framing | ISO/IEC 11172-3 and 13818-3 frame headers, plus the published Xing, VBRI and LAME header layouts |
| Format magic numbers | The published headers of each format the sniffer names |
| Sniffing past ID3 metadata | [ID3v2.4 structure](https://id3.org/id3v2.4.0-structure) sections 3 and 5 for headers, synchsafe lengths, footers and metadata placement. The [public Mutagen ID3 API](https://mutagen.readthedocs.io/en/latest/api/id3.html) supplies independent tags and [FFprobe](https://ffmpeg.org/ffprobe.html) names complete files; no implementation source is read |
| File output | [kotlinx-io atomic move](https://kotlinlang.org/api/kotlinx-io/kotlinx-io-core/kotlinx.io.files/-file-system/atomic-move.html); [Node.js WASI directory options](https://nodejs.org/api/wasi.html#new-wasioptions) for filesystem tests |
| Native text charsets | [RIFF CSET definition](https://www.robotplanet.dk/audio/wav_meta_data/riff_mci.pdf), page 2-16; AIFF 1.3 text chunk definition; [Microsoft code page identifiers](https://learn.microsoft.com/en-us/windows/win32/intl/code-page-identifiers) and [published Windows-1252 mapping data](https://www.unicode.org/Public/MAPPINGS/VENDORS/MICSFT/WINDOWS/CP1252.TXT). Executable FFmpeg, ExifTool and Python charset codec checks use only public interfaces |
| Tag membership | [NIST AVL tree definition](https://xlinux.nist.gov/dads/HTML/avltree.html), for bounded comparison-based lookup and insertion; implemented directly, with no implementation source used |
| ID3 genres | [ID3v2.2](https://id3.org/id3v2-00) and [ID3v2.3](https://id3.org/id3v2.3.0) section 4.2.1; [ID3v2.4 frames](https://id3.org/id3v2.4.0-frames) section 4.2.3 |
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
| ffmpeg | Cross-checking every decoder in this release, and reading back what the encoders write |
| flac and metaflac | FLAC, natively and inside Ogg, in both directions; ordered raw comments through native FLAC rewrites; [public picture imports and exports](https://xiph.org/flac/documentation_tools_metaflac.html) compare native PICTURE bodies and exact image bytes |
| ExifTool 13.55 | Public command-line interface with duplicate extraction (`-a`) and format groups for RIFF INFO and AIFF text chunk lists, including native singleton limits. Used as an executable oracle; implementation source stays unopened |
| afconvert (macOS) | CAF and AIFF-C, including direct `23ni` integer comparisons. Apple does not accept `42ni`; equivalent `in24` files check its PCM payload only. Also reserved for the ALAC work |
| alacconvert and ALACEncoder API | ALAC, built from Apple's reference at the commit the port follows; the direct API checks 20-bit audio |
| libopusfile 0.12 | [Public gain helpers and tag parsing API](https://opus-codec.org/docs/opusfile_api-0.12/group__header__info.html) and [OpusTags layout](https://opus-codec.org/docs/opusfile_api-0.12/structOpusTags.html), called through Python ctypes for conforming R128 values, overflow and rewritten Opus tags. RFC syntax takes precedence over the helper's malformed-value acceptance. No implementation source is read |
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
| WavPack | BSD-3-Clause | WavPack |
| opencore-amr | Apache-2.0 | AMR-NB and AMR-WB |
| libebur128 | MIT | Loudness measurement |
| libsamplerate | BSD-2-Clause | Resampling |
| speexdsp | BSD-3-Clause | Resampling, as an alternative |
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

## MPEG Layer III, the region boundary

Recorded because it is silent and cost real debugging time. The boundary between
the first and second Huffman regions of a granule that switched windows is
almost always written as the constant 36. That is right for MPEG-1 at all three
of its sample rates and for all three block shapes, which is why the constant
survives. It is wrong for MPEG-2: a start or stop block there needs the eighth
long band, which is 54, and only a short block needs 36. The real rule is that
region 0 covers `region0_count + 1` scalefactor bands of whatever layout the
granule itself uses, counting a short band once per window.

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

## Never opened

These are copyleft or otherwise incompatible with an Apache-2.0 release. The
list is here to be checked against, not to be worked around.

ffmpeg and libav source, libmad, mpg123, LAME, Shine, TwoLAME, FAAD2,
FDK-AAC, liba52, libdca, taglib, mutagen, jaudiotagger, JLayer, FluidSynth,
libopenmpt, and the sox source tree.

If a search result shows code from any of them, stop at the licence header.

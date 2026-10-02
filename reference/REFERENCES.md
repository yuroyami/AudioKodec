# References

What AudioKodecs was written from, and what it may be written from next.

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
| AIFF | Apple Audio Interchange File Format 1.3 |
| AIFF-C | Apple AIFF-C specification, including the compression type registry |
| AU | Sun and NeXT audio file format documentation |
| A-law and mu-law | ITU-T Recommendation G.711 |
| IMA and DVI ADPCM | IMA Digital Audio Focus and Technical Working Group, Recommended Practices for Enhancing Digital Audio Compatibility, plus Microsoft's IMA ADPCM WAVE type documentation |
| Microsoft ADPCM | Microsoft ADPCM WAVE type documentation |
| QuickTime IMA (`ima4`) | Apple QuickTime File Format documentation |
| FLAC | RFC 9639 |
| Ogg | RFC 3533, plus the Ogg mapping documents for Vorbis, Opus, FLAC and Speex |
| Vorbis | Xiph.Org Vorbis I specification |
| MPEG audio framing | ISO/IEC 11172-3 and 13818-3 frame headers, plus the published Xing, VBRI and LAME header layouts |
| Format magic numbers | The published headers of each format the sniffer names |

## Test oracles

Run as programs, never read as source.

| Tool | Used for |
|---|---|
| ffmpeg | Cross-checking every decoder in this release, and reading back what the encoders write |
| flac and metaflac | FLAC, natively and inside Ogg, in both directions |
| afconvert (macOS) | CAF, and reserved for the ALAC work |

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
  where it gives ...667. This is the same class of tie as D[196] above. AudioKodecs
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
so it may be ported from. Where AudioKodecs follows it rather than the prose, the
comment says so.

The range decoder is the one place where following the reference exactly matters
more than usual. Its renormalisation reads a byte, keeps a partial one, and
combines them with a shift that falls out of two constants the RFC derives
rather than states: with 32-bit codes and 8-bit symbols the extra is
`(32 - 2) % 8 + 1`, which is 7. Deriving it in the code rather than writing 7 in
is what stops it silently becoming wrong if the widths ever change.

## Never opened

These are copyleft or otherwise incompatible with an Apache-2.0 release. The
list is here to be checked against, not to be worked around.

ffmpeg and libav source, libmad, mpg123, LAME, Shine, TwoLAME, FAAD2,
FDK-AAC, liba52, libdca, taglib, mutagen, jaudiotagger, JLayer, FluidSynth,
libopenmpt, and the sox source tree.

If a search result shows code from any of them, stop at the licence header.

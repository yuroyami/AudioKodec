# KiteAudio PLANMAXXING

This document is the complete build plan for KiteAudio. It is written for the
agent that will implement the library. Follow it top to bottom. Decisions made
here are settled; do not reopen them unless the code proves them wrong, and if
you must deviate, record the deviation and the reason in PORTING_STATUS.md.

Updated 2026-07-26. Written against Kotlin 2.4.10, AGP 9.2.1, Dokka 2.2.0,
vanniktech publish 0.36.0 (see `../KiteVersions/versions.master.toml`).

---

## 1. What KiteAudio is

Audio codecs, containers, tags and PCM tools written in Kotlin for Kotlin
Multiplatform. Decode MP3, FLAC, Vorbis, Opus, ALAC, WAV and more from
`commonMain`, read and write the tags in them, and process the PCM, with the
same code on every target.

The one-sentence identity: **KiteAudio is to audio what KiteImage is to
images.** Pure-Kotlin, stdlib-only, spec-faithful, paranoid about malformed
input, tested against reference implementations.

### Positioning inside the family

| Library | Covers | Does not cover |
|---|---|---|
| KiteAudio (this) | Pure-Kotlin decode/encode/tags/PCM on all KMP targets including JVM, JS, WASM | Hardware playback, video, capture |
| KiteCodec | FFmpeg via cinterop on Kotlin/Native targets: video, every codec FFmpeg has | JVM, JS, WASM; pure-common code |
| Player wrappers (kodio, KMedia, gadulka, third-party) | Thin platform playback APIs | Codecs, tags, sample access |

KiteAudio deliberately does NOT ship a player in v1. The playback-wrapper niche
is crowded; the codec/tags niche is empty. Output is PCM plus metadata. A thin
`kiteaudio-play` sink module is a possible later artifact (section 12), never a
v1 goal.

### Who adopts it and why

- Music library apps: `probe()` + tags read/write on every target. No KMP
  alternative to jaudiotagger/taglib exists today.
- Games and audio tools: decode OGG/FLAC/WAV to PCM in common code, waveform
  peaks, resampling, loudness.
- Anything on JS/WASM/JVM that today shells out to ffmpeg for a 3-minute MP3.
- Voice apps: Opus/Speex/AMR decode in common code.

---

## 2. Family rules that bind this repo

Read these before writing anything:

1. `../KITE.md`. Documentation rules. Binding for README, docs pages, KDoc.
   Key points: no em dashes anywhere in docs (rewrite, do not substitute), no
   porting-journey narration in public docs, provenance gets one mention plus
   License section, every claim must be true of committed code.
2. `../_kite-docs/STYLE.md`. The section-by-section README template.
3. `../KiteImage/`. The structural template for this repo. When unsure how to
   lay out gradle, CI, docs, dokka, ABI dumps, or the fuzz suite, copy what
   KiteImage does.
4. `../KiteVersions/versions.master.toml`. Version numbers come from here via
   the sync script. Declare the same keys in `gradle/libs.versions.toml` that
   the family uses (kotlin, agp, dokka, vanniktechPublish, and the test-only
   ones you need).

Hard rules from the repo owner, non-negotiable:

- Never create a git branch. Work on the default branch.
- Never add a Co-Authored-By trailer to commits.
- No em dashes in any file, including this repo's internal docs and comments.
- Do not fabricate capabilities in README/POM. `POM_DESCRIPTION` must describe
  only what is implemented at publish time.
- Latest dependency versions are fine, including alpha/beta, matching the
  master toml.

---

## 3. Legal discipline: reference sources

This is the most important section of the plan. KiteAudio ships under
Apache-2.0. That constrains which source trees you may port from, which you
may only study, and which you must never open.

### 3.1 The three categories

**PORT (permissive: may translate code directly, credit in NOTICE):**

| Reference | License | Used for |
|---|---|---|
| minimp3 (lieff) | CC0 | MP3 (MPEG-1/2/2.5 Layer III) decode |
| stb_vorbis | public domain / MIT dual | Vorbis decode |
| libopus (xiph) | BSD-3 | Opus decode (SILK + CELT), later encode |
| Concentus (Java/C# libopus translation) | BSD-style (verify text at port time) | Porting aid for Opus; proves JVM-language portability |
| libFLAC (xiph) | BSD-3 | FLAC decode/encode; RFC 9639 is the primary spec |
| Apple alac (github.com/macosforge/alac or apple mirror) | Apache-2.0 | ALAC decode AND encode |
| WavPack (dbry) | BSD-3 | WavPack decode |
| libmpcdec / musepack SV8 | BSD-3 | Musepack decode |
| Speex (xiph) | BSD-3 | Speex decode |
| speexdsp resampler | BSD-3 | Resampler (alternative to libsamplerate) |
| libsamplerate | BSD-2 (since 2016) | Windowed-sinc resampler |
| libebur128 | MIT | EBU R128 loudness / ReplayGain 2 |
| opencore-amr | Apache-2.0 | AMR-NB and AMR-WB decode |
| WebRTC iLBC | BSD-3 (verify) | iLBC decode |
| libgsm (Jutta Degener, "toast") | MIT-style | GSM 6.10 |
| kjmp2 (Martin Fiedler) | believed CC0/public-domain style (verify) | MP2 (Layer II) decode |
| Monkey's Audio SDK | BSD-3-style since v10.18 (verify current text) | APE decode |
| TinySoundFont | MIT | SF2 synth, optional MIDI artifact only |
| IBXM / micromod (Martin Cameron) | BSD-style (verify) | Tracker formats, optional artifact only |

"Verify" means: before porting a single line, open the LICENSE file in the
exact version you port from, confirm it is permissive, and record commit hash
plus license in `reference/REFERENCES.md`, same as KiteImage does.

**STUDY-FORBIDDEN (GPL/LGPL/proprietary: never open these source trees):**

ffmpeg/libav (any part), libmad, mpg123, LAME, Shine, TwoLAME, FAAD2, FDK-AAC,
liba52, libdca, taglib, mutagen, jaudiotagger, JLayer, JavaLayer, FluidSynth,
libopenmpt, sox source. If a snippet from any of these appears in search
results while you work, do not read past the license header. Binaries of
ffmpeg/sox/flac ARE allowed as test oracles (section 9); their source is not.

**SPEC-ONLY (write clean-room from the published specification):**

| Format | Specification | Why clean-room |
|---|---|---|
| AAC-LC decode | ISO/IEC 13818-7, ISO/IEC 14496-3 (plus public summaries) | No permissive reference decoder exists |
| AC-3 decode | ATSC A/52 (free download) | liba52 is GPL |
| All tag formats | id3.org (ID3v2.2/2.3/2.4), Xiph VorbisComment spec, APEv2 spec (hydrogenaudio wiki), iTunes/MP4 ilst public documentation, EBU Tech 3285 (BWF bext) | taglib/mutagen/jaudiotagger are all copyleft; tags are simple enough that spec-first is also just the better method |
| Containers: MP4/ISO-BMFF | Apple QuickTime File Format docs (free), public box references | ISO 14496-12 text is paywalled; QTFF is free and sufficient |
| Matroska/WebM | RFC 8794 (EBML), RFC 9559 (Matroska) | Fully open specs |
| Ogg | RFC 3533, RFC 7845 (Opus mapping), Vorbis I spec | Open |
| FLAC | RFC 9639 | Open (libFLAC also portable) |
| WAV/RF64/W64/AIFF/AU/CAF | Microsoft/EBU/Apple/Sun public docs | Open |
| DSF/DFF (DSD) | Sony DSF spec, Philips DSDIFF spec (public) | Open |

### 3.2 Patent notes (decode side)

- MP3: all patents expired April 2017. Fully clear.
- MP2/MP1, GSM, G.711/722/726, ADPCM: ancient, clear.
- Vorbis, Opus, FLAC, Speex, WavPack, Musepack, ALAC: royalty-free by design
  or by grant. Clear.
- AC-3: patents expired 2017. Clear. E-AC-3 has a longer tail: parse and name
  it, do not decode it yet.
- AMR: essential patents from 1999-2001 have lapsed; opencore-amr has shipped
  under Apache-2.0 since 2009 without incident.
- AAC-LC: the core LC coding tools date to 1997-1999 and the relevant patents
  have lapsed; the remaining Via LA pool activity concerns HE-AAC (SBR/PS) and
  newer profiles. Ship AAC-LC decode. Do NOT implement SBR/PS (HE-AAC) in v1;
  when an HE-AAC file is met, decoding only the LC core and returning that
  audio is WRONG behavior (it sounds muffled and half-rate); instead detect
  SBR and throw `UnsupportedFeatureException("HE-AAC (SBR)")` so nobody
  mistakes core-only output for correct output. Revisit SBR around 2027.
- No AAC, MP3, or any other encoder ships before the corresponding decode is
  conformance-tested; encoders have their own notes in section 8.

NOTICE file: model it exactly on `../KiteImage/NOTICE`. Every ported
reference, every spec-only clean-room claim, every excluded format with the
reason (mirroring "AVIF and HEIC are deliberately excluded" language).
Excluded here: WMA (no permissive reference, no open spec), TAK and OptimFROG
(closed source), Shorten (restrictive license history, dead format). E-AC-3
and DTS decode are "named by the sniffer, not decoded", the same pattern
KiteArchive uses for xz/zstd/7z today.

---

## 4. Repo scaffold

Copy the skeleton from KiteImage, renamed. Checklist:

- `settings.gradle.kts`: `rootProject.name = "KiteAudio-KMP"`, typesafe project
  accessors, foojay toolchain resolver, same repositories. Modules with the
  same comment style KiteImage uses.
- `gradle.properties`: copy KiteImage's wholesale, then set
  `group=io.github.yuroyami`, `version=0.1.0`, adjust heap if the conformance
  suite needs it. Keep Dokka V2 flags.
- `gradle/libs.versions.toml`: declare only keys that exist in
  `versions.master.toml` plus test-only additions; the sync script will keep
  them fresh.
- Gradle wrapper: copy from KiteImage (same version the family runs).
- Root `build.gradle.kts`: same aggregation (dokka html at `build/dokka/html`).
- CI: `.github/workflows/ci.yml` modeled on KiteImage's; JVM full suite, JS +
  wasmJs + wasmWasi on Node, macosArm64 and linuxX64 native tests, ABI check,
  api dump check, publish job wiring last.
- `LICENSE` Apache-2.0, `NOTICE` (section 3), `SECURITY.md`, `CONTRIBUTING.md`,
  `CHANGELOG.md` seeded at 0.1.0-dev.
- `reference/REFERENCES.md` plus one subfolder note per ported reference
  (commit hash, license, what was taken), mirroring `../KiteImage/reference/`.
  Do not vendor the reference sources themselves.
- `PORTING_STATUS.md`: the living status table, same voice as KiteImage's
  (test counts, target list, per-format feature table with checkmarks).
- Docs: `mkdocs.yml` from `../_kite-docs/mkdocs-template.yml`, then run
  `../_kite-docs/sync.sh KiteAudio` to receive the theme. `docs/index.md` plus
  one guide per task (section 11).
- `dokka-templates/` arrives via the same sync.

### Modules

```
:kiteaudio        pure-Kotlin core. stdlib only. Everything in sections 5-8.
:kiteaudio-io     kotlinx-io bridge: file/RawSource adapters for KiteAudioSource.
                  Same precedent as :kitearchive-io (which has no JS target).
:sample           desktop-first Compose app: drop a file, see format, tags,
                  waveform, hear it via javax.sound on JVM. ./gradlew :sample:run
```

One core artifact, not a codec-per-artifact split. Rationale: Kotlin/Native
and JS/WASM strip dead code, Android has R8, and the family precedent
(KiteImage, KitePDF) is a single core. Revisit only if the compiled core
exceeds roughly 4 MB of klib per target after Tier 3 lands.

Do NOT add `:kiteaudio-compose`, `:kiteaudio-play`, `:kiteaudio-tracker`,
`:kiteaudio-midi` in v1. They are listed in section 12 as future artifacts.

### Core module build script

Copy `../KiteImage/kiteimage/build.gradle.kts` with these edits: namespace
`io.github.yuroyami.kiteaudio`, framework baseName `KiteAudio`, same 22-target
matrix (android, jvm, iosArm64/SimulatorArm64/X64, macosArm64, tvos pair,
watchos quad, androidNative quad, linuxX64/Arm64, mingwX64, js browser+node,
wasmJs browser+node, wasmWasi node), `explicitApi()`, `abiValidation`,
`jvmToolchain(21)`, empty `commonMain.dependencies` with the same "stdlib
only" comment, Mocha timeout bump for the fuzz suite, test heap sized so the
bomb guards fire before the allocator dies.

---

## 5. Core model and public API

Package root: `io.github.yuroyami.kiteaudio`. Everything below is `public`;
internals live under `io.github.yuroyami.kiteaudio.internal` and stay
`internal`.

### 5.1 Input abstraction

Audio needs random access (MP4 `moov` can trail the file; seeking needs it)
and files are large, so `ByteArray`-only is not enough, but `ByteArray` must
stay the zero-dependency happy path.

```kotlin
/** Random-access byte source. Implementations must be safe for sequential
 *  single-threaded use; KiteAudio never calls concurrently. */
public interface KiteAudioSource : AutoCloseable {
    public val size: Long
    /** Reads up to [count] bytes at absolute [position] into [into] at [offset].
     *  Returns bytes read, or -1 at end. */
    public fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int
    override fun close()
}

public fun ByteArray.asAudioSource(): KiteAudioSource
```

`:kiteaudio-io` adds `Path.asAudioSource()` / `RawSource` bridging on the
targets kotlinx-io supports. Core never touches the filesystem.

### 5.2 PCM model

Canonical interchange is **Float32, interleaved, range [-1.0, 1.0]**. Every
platform sink speaks float (WebAudio, AVAudioEngine, Oboe, javax.sound via
conversion), and float sidesteps 24-bit packing pain. Lossless bit-exactness
is preserved through a secondary integer view.

```kotlin
public class AudioFormat(
    public val sampleRate: Int,          // 8000..768000 accepted
    public val channels: Int,            // 1..64, bomb guard above
    public val channelLayout: ChannelLayout, // SMPTE/WAV canonical order
)

/** One decoded chunk. Frame size is codec-natural (1152 for MP3, up to 4096
 *  for FLAC, 960*x for Opus). */
public class AudioFrame(
    public val format: AudioFormat,
    public val samples: FloatArray,      // interleaved, length = frames * channels
    public val frameCount: Int,
    public val positionSamples: Long,    // absolute position of first frame
)

/** Implemented by lossless decoders in addition to float output. */
public interface IntPcmAccess {
    public val bitDepth: Int             // as stored: 16, 24, 32
    public fun readIntFrame(): IntAudioFrame? // raw right-justified signed values,
                                              // exactly as stored (no left-justify)
}
```

Channel order rule: the canonical order is WAV/SMPTE
(FL FR FC LFE BL BR ...). Vorbis and Opus multichannel use their own orders;
decoders MUST remap to canonical before emitting. This is a classic silent bug;
write channel-order tests with a 5.1 vector per codec.

Conversions shipped in core: float <-> i16/i24/i32/u8, interleaved <->
planar, with optional TPDF dither on float -> int. These are pure functions on
arrays, no allocation surprises, documented.

### 5.3 Facade

Mirror KiteImage's object facade so family users feel at home:

```kotlin
public object KiteAudio {
    /** Names the container/codec from leading bytes. Never throws. */
    public fun sniff(header: ByteArray): AudioContainer?

    /** Header-only inspection: format, duration, sample rate, channels,
     *  bitrate, encoder, tags. Bounded I/O (reads tag blocks + headers +
     *  sample tables, never the audio payload). */
    public fun probe(source: KiteAudioSource): AudioInfo

    /** Whole-file convenience decode. Applies gapless trims. Bomb-guarded. */
    public fun decode(bytes: ByteArray, options: DecodeOptions = DecodeOptions()): DecodedAudio

    /** Streaming decoder for real use. */
    public fun open(source: KiteAudioSource, options: DecodeOptions = DecodeOptions()): AudioDecoder

    /** Tag reading without decode. */
    public fun readTags(source: KiteAudioSource): TagBundle

    /** In-place-safe tag writing: rewrites tag blocks, never re-encodes audio.
     *  Returns the new file bytes via the writer, audio payload byte-identical. */
    public fun writeTags(source: KiteAudioSource, edit: TagEdit, sink: KiteAudioSink)

    /** Encoding. */
    public fun encode(pcm: PcmSource, target: EncodeTarget, sink: KiteAudioSink)
}
```

### 5.4 Streaming decoder

Pull model, synchronous, no coroutine dependency (callers wrap it themselves;
document the one-liner with `flow { }` in the guide).

```kotlin
public interface AudioDecoder : AutoCloseable {
    public val format: AudioFormat
    public val durationSamples: Long?    // null when truly unknowable (raw streams)
    public val tags: TagBundle
    /** Next chunk or null at end of stream. */
    public fun nextFrame(): AudioFrame?
    /** Sample-accurate seek. Lands exactly on [sampleIndex]; internally seeks
     *  the container to at-or-before and discards decoded samples up to it.
     *  Returns actual position (== sampleIndex except past-EOF clamps). */
    public fun seek(sampleIndex: Long): Long
}
```

Seek machinery per format: FLAC SEEKTABLE plus binary search, Ogg granulepos
bisection, MP3 Xing/VBRI TOC with CBR arithmetic fallback and a
worst-case linear scan behind `DecodeOptions.allowSlowSeek`, MP4 stts/stsc/
stco sample tables, WAV/AIFF arithmetic, Matroska cues.

Gapless is a correctness contract, not a feature flag: decoders emit exactly
the encoded duration. MP3 honors LAME delay/padding, AAC honors iTunSMPB,
Opus honors pre-skip and end trimming per RFC 7845, Vorbis honors final-page
granule truncation. Test: encode 3 exact sine periods with each oracle
encoder, decode, assert sample count and phase continuity when concatenating.

### 5.5 Options, errors, guards

```kotlin
public class DecodeOptions(
    public val tolerant: Boolean = false,   // truncated input: emit what exists,
                                            // then TruncatedStreamException from
                                            // nextFrame() only if nothing decoded
    public val reuseBuffers: Boolean = false, // zero-alloc steady state; frames
                                              // become invalid on next pull
    public val allowSlowSeek: Boolean = true,
    public val maxChannels: Int = 64,
    public val maxSampleRate: Int = 768_000,
    public val maxAllocationBytes: Long = 1L shl 30, // family-standard 1 GiB bomb line
)
```

Exception hierarchy under `KiteAudioException`: `UnsupportedFormatException`
(sniffed but not decodable, names the format), `UnsupportedFeatureException`
(decodable format, unimplemented corner, names the corner),
`CorruptStreamException`, `TruncatedStreamException`, `AllocationGuardException`.
Every throw site names byte offsets. The fuzz suite (section 9) enforces that
no input produces anything outside this hierarchy, and nothing hangs.

### 5.6 Tag model

Unified view plus raw access, because every tag library that only offers one
of the two gets forked:

```kotlin
public class TagBundle(
    public val fields: Map<TagField, List<String>>, // TITLE, ARTIST, ALBUM, TRACK, DISC,
                                                    // ALBUMARTIST, DATE, GENRE, COMPOSER,
                                                    // COMMENT, LYRICS, MUSICBRAINZ ids,
                                                    // REPLAYGAIN_* , R128_* , ...
    public val pictures: List<TagPicture>,          // type, mime, bytes; APIC/covr/METADATA_BLOCK_PICTURE
    public val chapters: List<TagChapter>,          // start/end ms, title, art
    public val raw: RawTags,                        // per-format frames/atoms/comments, lossless
)
```

Round-trip rule: read-then-write with no edit must be byte-identical for the
tag block, and ALWAYS byte-identical for the audio payload. ID3v2.4 unsync,
UTF-16 BOM order, padding preservation, and MP4 atom size rewriting are where
tag libraries historically corrupt files; each gets a regression vector.

---

## 6. Format matrix

Legend: D = decode, E = encode, P = parse/demux only, T = tags.
Tier = build order (section 10). Every row lands with fuzz coverage plus the
oracle test named in section 9.

### 6.1 Containers

| Container | Support | Tier | Notes |
|---|---|---|---|
| WAV/RIFF (incl. WAVE_FORMAT_EXTENSIBLE) | D+E+T | 0 | PCM 8/16/24/32, f32/f64, A-law, mu-law, IMA/MS ADPCM inside |
| RF64/BW64 | D+E | 0 | >4 GiB WAV; bext chunk in T tier 1 |
| W64 (Sony Wave64) | D | 0 | rare but trivial once RIFF exists |
| AIFF / AIFF-C | D+E+T | 0 | big-endian PCM, `sowt`, fl32, A/mu-law, IMA4 |
| AU/SND (Sun) | D+E | 0 | trivial; mu-law heritage format |
| CAF (Apple) | D | 2 | chunks map onto same codec set; LPCM + ALAC + (later) AAC inside |
| Raw PCM | D+E | 0 | explicit `AudioFormat` supplied by caller |
| Ogg | D | 3 | page/packet layer, CRC, chaining, granulepos; carries Vorbis, Opus, FLAC, Speex |
| MP3 stream (with ID3/APE wrappers) | D+T | 3 | free-format frames rejected by name |
| ADTS / ADIF (raw AAC) | P, D with tier 5 AAC | 5 | |
| MP4 / M4A / M4B / 3GP (ISO-BMFF) | P+T, D for tracks we decode | 4 | sample tables, edts, fragmented MP4 read (moof) as stretch inside the tier |
| Matroska / WebM | P+T | 6 | EBML per RFC 8794, Matroska per RFC 9559; extract Opus/Vorbis/FLAC/MP3/AC-3/PCM tracks; zlib ContentCompression via inflate vendored from the family lineage |
| DSF / DFF (DSD) | P, D via decimation | 7 | DSD64/128 to PCM with the documented multistage FIR; niche but unowned |
| ASF/WMA, MPEG-TS, FLV | sniff-only | n/a | named by sniffer, never decoded; NOTICE explains |

### 6.2 Codecs, decode

| Codec | Tier | Reference (license) | Conformance oracle |
|---|---|---|---|
| PCM int/float all widths | 0 | spec | sox/ffmpeg bit-exact |
| G.711 mu-law/A-law | 0 | ITU G.711 tables | bit-exact tables |
| IMA ADPCM, MS ADPCM | 0 | public specs | ffmpeg bit-exact |
| G.722, G.726 | 7 | ITU reference (check license) or spec | ffmpeg |
| GSM 6.10 | 7 | libgsm (MIT-style) | libgsm binary |
| FLAC (native + Ogg) | 2 | RFC 9639 + libFLAC (BSD-3) | IETF cellar conformance corpus, `flac -d` bit-exact |
| ALAC | 4 | Apple alac (Apache-2.0) | afconvert/ffmpeg bit-exact |
| WavPack | 7 | WavPack (BSD-3) | wvunpack bit-exact (lossless mode; hybrid lossy = tolerance) |
| Monkey's Audio (APE) | 7 | MA SDK (BSD-3-style, verify) | official decoder bit-exact; normal+high profiles first, extra-high stretch |
| TTA | 7 | spec + reference (verify license; clean-room viable, format is simple) | ffmpeg bit-exact |
| Musepack SV8 | 7 | libmpcdec (BSD-3) | mpcdec tolerance |
| MP3 (L3, MPEG-1/2/2.5) | 3 | minimp3 (CC0) | ISO-style compliance: RMS error bound vs ffmpeg decode; gapless vectors |
| MP2 (+ MP1) | 3 | kjmp2 (verify) + spec | ffmpeg tolerance |
| Vorbis | 3 | stb_vorbis (PD/MIT) | oggdec tolerance + chaining/multiplexed vectors |
| Opus | 3 | libopus (BSD-3), Concentus as map | RFC 6716/8251 official test vectors with opus_compare metric; THE flagship port |
| Speex | 7 | speex (BSD-3) | speexdec tolerance |
| AMR-NB / AMR-WB | 7 | opencore-amr (Apache-2.0) | 3GPP test sequences where redistributable, else opencore binary |
| iLBC | 7 | WebRTC (BSD-3, verify) | reference vectors |
| AAC-LC | 5 | SPEC-ONLY (13818-7 / 14496-3) | ffmpeg/afconvert tolerance; SBR detection throws UnsupportedFeature |
| AC-3 | 8 | SPEC-ONLY (ATSC A/52) | ffmpeg tolerance; E-AC-3 sniff-only |
| DSD (DSF/DFF) | 7 | spec | ffmpeg tolerance after decimation |

### 6.3 Codecs, encode

Decode-first policy: an encoder ships only after its decoder passes
conformance, because the decoder is the encoder's first test harness.

| Encoder | Tier | Notes |
|---|---|---|
| WAV/AIFF/AU PCM all widths, RF64 | 0 | trivial, ships day one |
| G.711, IMA/MS ADPCM | 0 | |
| FLAC | 2 | fixed + LPC up to level ~5 semantics first; verify with `flac -t` and decode-back bit-exact; exhaustive/apodization parity later |
| ALAC | 4 | reference encoder is Apache-2.0, port it whole |
| WavPack | 9 | lossless mode only |
| Vorbis | 9 | libvorbis (BSD-3) psy model port; big; quality vs oracle via spectral comparison, not bit-exact |
| Opus | 9 | libopus encode path; biggest encode item; CELT-only mode first (music), SILK later |
| MP3 | 9 | SPEC-ONLY clean-room (LAME and Shine are LGPL and unreadable for us). Patents expired so legally fine, but this is a large psychoacoustics project; do not start before Vorbis encode has shipped and taught us the shape |
| AAC any profile | never in this plan | patent tail on encode side plus no permissive reference; KiteCodec covers it on native |
| Speex/AMR encode | skip | dead formats for authoring; decode-only is the need |

### 6.4 Tags (all tier 1, read+write unless noted)

| Tag system | Where | Notes |
|---|---|---|
| ID3v2.2 / 2.3 / 2.4 | MP3, AIFF, WAV, DSF, anywhere | full frame set incl. APIC, USLT, SYLT, CHAP/CTOC, TXXX, unsync, extended header, footer; v2.2 write = upgrade to v2.3 on save (industry norm, document it) |
| ID3v1 / v1.1 | MP3 tail | read; write only to update an existing one; never create (document) |
| Vorbis comments | Ogg (Vorbis/Opus/FLAC/Speex), native FLAC | incl. METADATA_BLOCK_PICTURE base64, R128_TRACK_GAIN / R128_ALBUM_GAIN for Opus per RFC 7845 |
| FLAC metadata blocks | FLAC | STREAMINFO, SEEKTABLE, CUESHEET, PICTURE, APPLICATION, padding management on rewrite |
| APEv2 (+v1 read) | APE, WavPack, Musepack, MP3 tail | tag precedence rules on MP3 (ID3v2 > APE > ID3v1) documented and tested |
| MP4 ilst | M4A/M4B/MP4 | standard atoms + `----` freeform + covr + chpl/tref chapters + iTunSMPB read |
| RIFF INFO + BWF bext | WAV | broadcast fields; iXML read-only stretch |
| AIFF NAME/AUTH/ANNO/(c) | AIFF | |
| Matroska tags + chapters | MKA/WebM | read in tier 6; write is a stretch (EBML resize is fiddly) |
| Lyrics3 | MP3 tail | read-only, exists purely so the byte math around ID3v1/APE stays correct |
| ReplayGain / R128 conventions | cross-format | one typed API over the per-format spellings |

Tag writing strategy: rewrite-in-full to a sink (5.3), preserving padding
where the format has it (ID3v2 padding, FLAC PADDING block) so in-place
same-size rewrites are possible for the io module later. Never touch audio
bytes; assert this with checksums in every write test.

### 6.5 PCM/DSP toolbox (tier 8, all pure functions or small stateful classes)

- Resampler: windowed-sinc port of libsamplerate's best quality plus a cheap
  linear mode; polyphase like speexdsp acceptable alternative; document THD+N
  measured against oracle.
- Channel ops: any-to-any mixdown/upmix with standard matrices, per-channel
  gain, mid/side.
- Dither: TPDF at 16-bit and 8-bit reduction.
- Loudness: EBU R128 integrated/short-term/momentary/LRA/true-peak (4x
  oversampled), libebur128 port; emits ReplayGain 2 values. Oracle: libebur128
  binary or ffmpeg ebur128 filter within 0.1 LU.
- Waveform peaks: min/max/RMS per bucket for UI rendering, incremental API so
  a 2-hour file streams through it.
- Silence/peak/RMS/clipping scan, simple fades, gain.
- Generators: sine/square/noise/silence (test infrastructure that ships,
  useful for consumers too).

---

## 7. Sniffer

Same contract as KiteArchive/KiteImage: `sniff(header)` names everything it
can from magic bytes (including formats KiteAudio will never decode, e.g. WMA)
and decoding support is a separate question answered by `probe`/`open`
throwing `UnsupportedFormatException`. The sniffer never throws. ID3v2
prefixes must be skipped transparently when sniffing the underlying stream
(an MP3 with a 200 KB ID3v2+APIC header still sniffs as MP3; so does a
tag-prefixed FLAC).

---

## 8. Numerical discipline

- Float math: Kotlin `Float`/`Double` on all targets; no expect/actual math.
  Where reference code uses fixed-point (minimp3 has float mode: use it;
  opencore-amr is fixed-point: keep it integer), keep the reference's mode to
  keep conformance comparable.
- Bit readers: one MSB-first and one LSB-first implementation in
  `internal.bits`, exhaustively unit-tested, shared by every codec. The Opus
  range decoder and the Ogg packet assembler are their own classes.
- No `expect`/`actual` in the core at all if achievable (KiteImage manages);
  wasmWasi and JS have no threads, so everything is synchronous and
  single-threaded by design.
- Performance targets (JVM, M-series laptop, release): MP3 decode >= 100x
  realtime, FLAC >= 200x, Opus >= 50x, resampler >= 100x. These are sanity
  floors to catch accidental O(n^2), not marketing numbers; measure in a
  `:sample` benchmark task, record in PORTING_STATUS.md, never in README until
  measured on CI-class hardware.

---

## 9. Testing strategy

Model: KiteImage's regime, upgraded for audio.

1. **Committed vectors.** `src/commonTest/resources` equivalents (KMP: use a
   generated `object` embedding ByteArrays, the family pattern for
   target-portable vectors; check how KiteImage embeds its stb vectors and do
   the same). Small: seconds of audio, every channel/rate/depth permutation
   that matters. A `test-vectors/generate.sh` records the exact oracle
   commands (ffmpeg/sox/flac/opusenc/lame versions pinned) that produced every
   committed file. Repo weight budget: keep committed vectors under ~25 MB
   total; prefer 0.5 s clips at 44.1 kHz.
2. **Official conformance.** Opus: the RFC 6716/8251 vector set with the
   opus_compare quality threshold. FLAC: the IETF cellar conformance files.
   These are the two formats with real official suites; commit the subsets
   whose licenses allow it, download-on-demand the rest with skip-when-absent.
3. **Oracle binaries, skip-when-absent** (KiteImage's dwebp pattern): ffmpeg,
   flac, opusdec, oggdec, wvunpack, mpcdec, mediainfo/ffprobe for tag
   cross-reads. Lossless: bit-exact. Lossy: per-codec numeric bound (RMS and
   max-abs against oracle float decode; bounds recorded per codec in the test,
   with the rationale).
4. **Round-trips.** encode->decode bit-exact for every lossless encoder;
   tag write->read equality; tag rewrite preserves audio checksum; seek(x)
   then sequential-read equals full sequential read from x (per codec,
   multiple x including frame boundaries and 0 and EOF).
5. **Fuzz suite.** Same architecture as KiteImage's: header mutations,
   truncation at every interesting boundary, random-garbage-after-valid-magic,
   dictionary-guided flips on length/count fields. Invariants: typed
   exceptions only, bounded time (Mocha 600 s ceiling exists for a reason),
   allocation guard fires before OOM, `tolerant` mode never loops forever.
   Every codec lands WITH its fuzz corpus in the same PR/commit.
6. **Cross-target.** Common tests green on JVM, JS Node, wasmJs Node, wasmWasi
   Node, macosArm64, linuxX64 in CI, mirroring the KiteImage summary sentence
   in PORTING_STATUS.md ("N tests on the JVM; the M common tests also run
   green on ...").
7. **Gapless/duration truth table.** One test class per container asserting
   exact `durationSamples` and first/last-sample values for vectors produced
   by mainstream encoders (LAME CBR/VBR, iTunes AAC->skipped until tier 5,
   opusenc, oggenc, flac, afconvert ALAC).

---

## 10. Build order

Each tier ends with: all tests green on all CI targets, fuzz corpus in, ABI
dump updated, PORTING_STATUS.md updated, docs guide written for what shipped.
Do not start tier N+1 with tier N red. Within a tier, the listed order is the
dependency order.

- **Tier 0, skeleton + PCM.** Repo scaffold (section 4). Bit readers. Source
  abstraction. Sniffer for everything in 6.1. WAV/RF64/W64 + AIFF + AU + raw
  PCM decode/encode with all PCM widths, G.711, IMA/MS ADPCM. Sample-format
  conversion matrix. `probe` for these. The architecture is proven here:
  facade, decoder pull loop, options, exceptions, fuzz harness, vector
  embedding, CI, docs pipeline all exist by end of tier 0.
- **Tier 1, tags.** The full 6.4 table minus Matroska. This tier alone makes
  the library adoptable (nothing else in KMP does this); it is deliberately
  early. Ship `readTags`/`writeTags` + `TagBundle` + precedence rules +
  byte-preservation guarantees.
- **Tier 2, FLAC.** Native FLAC decode, Ogg-FLAC once Ogg lands in tier 3
  (leave a named seam), SEEKTABLE seeking, FLAC encode (levels 0-5
  semantics), CAF container parse. First publishable milestone: after tier 2,
  cut `0.1.0` to Maven Central (tags + FLAC + WAV family is a real product).
- **Tier 3, mainstream lossy decode.** Ogg layer. Vorbis decode. MP3/MP2/MP1
  decode with Xing/VBRI/LAME gapless + seeking. Opus decode (SILK, CELT,
  hybrid, multistream/surround per RFC 7845). Ogg-FLAC seam closes. This is
  the longest tier; Opus alone is a multi-week port. `0.2.0`.
- **Tier 4, Apple lane.** MP4/ISO-BMFF demux (incl. M4B chapters, 3GP), ALAC
  decode+encode, iTunSMPB gapless read, MP4 ilst write. `0.3.0`.
- **Tier 5, AAC-LC.** Clean-room decode, ADTS/ADIF + MP4 carriage, SBR
  detection-and-refuse. The riskiest single item in the plan (spec-only,
  no permissive reference): timebox the first spike to establish feasibility;
  if it stalls, re-scope to "parse + route to platform decoders via a
  documented seam" and move on, KiteCodec exists. `0.4.0`.
- **Tier 6, Matroska/WebM.** Demux + tags/chapters read.
- **Tier 7, the long tail.** WavPack, APE, TTA, Musepack, Speex, AMR-NB/WB,
  iLBC, GSM 6.10, G.722/726, DSF/DFF+DSD decimation.
- **Tier 8, DSP toolbox + AC-3.** Section 6.5 in full; AC-3 clean-room decode.
- **Tier 9, encoders round two.** WavPack, Vorbis, Opus, then MP3 clean-room,
  in that order, each gated on demand signals (issues, stars, family need).

`0.x` versioning with honest feature tables beats a padded `1.0`. `1.0.0`
target: tiers 0-6 complete plus zero known conformance failures.

---

## 11. Documentation plan

Per `../KITE.md` and `../_kite-docs/STYLE.md`, with the docs site synced from
the family template. Structure:

- README: identity sentence, badges (Central, CI, Kotlin, targets, license),
  docs link in the first screen, install, three quickstarts (decode to PCM,
  read/write tags, probe a library folder), format support table (generated
  claim-checked against the sniffer/decoder registry in a unit test so README
  and code cannot drift), targets, limits, license + NOTICE pointer, family
  footer links.
- One docs guide per task: decode a file; stream and seek; gapless playback
  math; read tags; edit tags safely; embed art; encode FLAC/ALAC; loudness
  scan and ReplayGain; waveform peaks for UI; resample; use with kotlinx-io;
  feed platform sinks (short per-platform snippets: AVAudioEngine, AudioTrack,
  javax.sound, WebAudio, without shipping wrapper code).
- PORTING_STATUS.md as the living truth table, KiteImage voice.
- KDoc on every public symbol (explicitApi forces the visibility; STYLE.md
  governs the prose). Dokka aggregated to `build/dokka/html`, published with
  the mkdocs site like the rest of the family.
- POM_DESCRIPTION at 0.1.0: "Audio decoding, encoding and tag editing for
  Kotlin Multiplatform: FLAC, WAV, AIFF and full ID3v2, Vorbis comment, APE
  and MP4 tag support in pure Kotlin." Update it at every minor when the
  claim set grows; it must never lead the code.

---

## 12. Future artifacts (documented, not built)

- `:kiteaudio-play`: thin per-platform PCM sinks (AVAudioEngine, AudioTrack/
  Oboe-less AudioTrack, javax.sound, WebAudio worklet). Only if the ecosystem
  still lacks a good one when tiers 0-6 are done.
- `:kiteaudio-compose`: waveform/seekbar composables fed by the peaks API.
- `:kiteaudio-tracker`: MOD/XM/S3M/IT via IBXM-style port. Games love it,
  zero patent risk, self-contained.
- `:kiteaudio-midi`: SMF parse + SF2 + TinySoundFont-style synth to PCM.
- Network audio (ICY/Shoutcast metadata, HLS audio) is a non-goal for the
  library, not just for v1; it belongs in the app layer above (Ktor), and the
  seam it needs (push bytes into a streaming decoder) already exists via
  `KiteAudioSource`.

---

## 13. Definition of done, per format and overall

A format row in PORTING_STATUS.md flips to done when:

1. Conformance/oracle tests pass on all six CI targets.
2. Fuzz corpus runs clean (typed exceptions, bounded time and allocation).
3. Seek + duration + gapless contracts tested where applicable.
4. Tags round-trip where applicable.
5. KDoc + docs guide section updated; README table regenerated.
6. ABI dump committed; no public API leaked by accident.

The library is "continuing the Kite legacy" when a stranger can read the
README in two minutes, add one dependency, decode an MP3 with tags into
Float32 PCM on wasmJs, and nothing in the docs lied to them.

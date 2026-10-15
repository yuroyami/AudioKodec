# Decoding

AudioKodec decodes every format to interleaved Float32 PCM. A whole file decodes in one call,
and a long one streams a block at a time. Every function takes the encoded bytes as a
`ByteArray` or as an `AudioSource`, which `audiokodec-io` makes from a file
(see [Files and kotlinx-io](files.md)).

## Decode a file

```kotlin
--8<-- "DecodeSample.kt:imports"

--8<-- "DecodeSample.kt:decode"
```

`audio.samples` is interleaved Float32, nominally between -1.0 and 1.0. A
lossy coding can go slightly outside that range, and AudioKodec does not clamp
it, because clamping belongs at the point where samples become integers again.

## Read a header without decoding

```kotlin
--8<-- "DecodeSample.kt:probe"
```

`probe` reads metadata and index tables. Its I/O depends on the format: Ogg walks
pages to the stream end and reads their payloads to verify checksums; MPEG
without a stored Xing or VBRI frame count scans frame headers across the file
for an exact duration. Both can read the whole file, so a folder scan is not
bounded to a fixed header window. Other formats read their metadata and index
tables. Opening Ogg repeats the page walk before the first decoded frame.
Tolerant native FLAC inspection decodes frames when the first frame retains
a nonzero origin, deriving the complete duration of a stream-copy cut.
Older FLAC variable-block files can store sample numbers with the blocking
strategy bit clear. When unequal STREAMINFO bounds make the numbering
ambiguous, inspection verifies the first frame and the following header.
That single-frame decode distinguishes legacy sample numbers from modern
fixed-block frame numbers, including streams with a short final block.

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

Raw AAC in ADTS framing decodes, as [AAC](aac.md) describes. ADTS puts a
header on every frame and no index anywhere, so `probe` walks the frame
headers, without reading the payloads, and reports the exact length: 1024
frames for every raw data block. A sync word is believed only when the next
three frames agree with it, and junk between frames is crossed as for MPEG
audio. The rate is the one the headers state; a stream with implicit spectral
band replication would play at twice that rate, and `open` refuses it by name.
A channel configuration of zero takes the channel count from the program
configuration that leads the first frame, with the speakers left unnamed.
ADIF has one header and no frame lengths, so its duration is `null`, and
`open` refuses it with `UnsupportedFormatException`.

## Name a format

`sniff` names 30 formats from the bytes supplied, including formats without a
decoder. A file that AudioKodec cannot read is one that you can route somewhere
else, which beats a guess from the file extension. Check `isDecodable` before
choosing AudioKodec to read one:

```kotlin
--8<-- "GuideSamples.kt:sniff"
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

When no container signature is recognized, `probe` and `open` also search the
first 512 KiB for confirmed MPEG frames. This accepts leading junk, zero padding
and captures beginning partway through a frame. Byte-array `sniff` still needs
a leading signature within its supplied window.

## Stream and seek

Whole-file decoding allocates the whole file, so use the streaming decoder for
anything long. Seeking is sample accurate: the decoder lands on the frame you
asked for, not on the block containing it.

```kotlin
--8<-- "StreamSample.kt:stream"
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

## Precision and lossless round trips

Decoding produces Float32, which carries 8-, 16- and 24-bit audio exactly. It
cannot carry 32-bit integers exactly, because a Float32 has 24 bits of
mantissa. Decoders for lossless codings therefore also implement
`IntPcmAccess`:

```kotlin
--8<-- "GuideSamples.kt:int-access"
```

Use one or the other on a given decoder, not both. They advance the same
cursor.

`IntPcmSource` and the integer-array encode overload consume those exact
signed, right-justified values without Float32:

```kotlin
--8<-- "GuideSamples.kt:int-encode"
```

Integer WAV, AIFF and AU preserve every bit at equal depths, including 32.
FLAC supports integer input with 8, 16 and 24-bit targets. Increasing depth
shifts left; decreasing it without dither uses an arithmetic right shift.
TPDF depth reduction uses Float64 scaling, seeded noise and nearest-even
rounding. See [integer PCM encoding](integer-pcm.md) for source contracts,
supported targets and examples.

## Channel layouts

An `AudioFormat` carries the speaker position of every channel, in the order
the samples are interleaved. Files that state their layout, such as a WAV with
a channel mask, keep it through decoding, and AudioKodec writes the mask back
out for files with more than two channels.

```kotlin
--8<-- "GuideSamples.kt:layout"
```

`Pcm.remapChannels` reorders samples between two layouts that name the same
channels. `Pcm.mixChannels` converts between layouts that do not, mixing down
with the coefficients of ITU-R BS.775 and refusing a conversion with no
defined answer; `Pcm.applyChannelGains` and the mid-side pair sit beside it.
See [Mix channels](mix.md).

## Handling bad input

Every failure that the input causes is an `AudioKodecException`. A wrong
argument, such as a negative seek target, throws `IllegalArgumentException`
instead, and a failure inside a source or sink that you supplied keeps its own
type.

| Exception | Meaning |
|---|---|
| `UnsupportedFormatException` | The format was recognized and has no decoder here |
| `UnsupportedFeatureException` | The format is supported and this corner of it is not |
| `CorruptStreamException` | The file contradicts its own format |
| `TruncatedStreamException` | The file ends in the middle of something |
| `AllocationGuardException` | A header asked for more memory than the options allow |

Nothing else escapes a decoder because of the input, whatever the input.

A test suite of hostile input checks every supported container using short
valid files. It visits every truncation boundary and every byte with all
eight individual bit flips and a full-byte inversion, then randomises bodies
behind valid signatures. Probing, streaming and complete decoding run in both
modes under an allocation ceiling, and streaming must keep advancing.

A truncated file throws by default. To salvage what is there instead:

```kotlin
--8<-- "GuideSamples.kt:tolerant"
```

For FLAC, tolerant decoding stops at the first damaged frame after valid
output, including inconsistent frame numbering or a frame extending beyond
the declared sample total. Float and integer reads retain the same complete
prefix. Damage before any valid output still throws.

MPEG Layers I, II and III also stop tolerant playback after the last complete
frame when decoding encounters damaged frame contents. Later frames are not
spliced across that boundary, and a damaged first frame still throws.

Ogg Vorbis, Opus and FLAC stop at the first damaged page, missing page or
inconsistent packet continuation. Duration and seeking cover the intact
prefix, and no later packets are moved earlier on the timeline. Damage before
any usable audio still throws. The Opus codec core's concealment and redundant
packet recovery are tested directly; Ogg playback follows this prefix policy.

`DecodeOptions.maxAllocationBytes` caps what any single allocation may be, and
defaults to one gibibyte. The check happens before the allocation, not after
it fails.

ALAC packet scratch storage and predictor state are checked during probe and
open. Retained RIFF INFO and AIFF text entries are charged at an estimated
64 bytes per entry, including entries spread across separate INFO lists.
Empty native text values do not consume retained-entry space. These checks
bound individual buffers and collections, rather than total process memory.

## Playing the result

AudioKodec decodes and does not play. The result is Float32 PCM, which every
platform sink accepts. [Play decoded audio](tasks/play-audio.md) has the code for each:

| Platform | Sink | Notes |
|---|---|---|
| Android | `AudioTrack` | `ENCODING_PCM_FLOAT`, or convert with `Pcm.intFromFloat` for older devices |
| Apple | `AVAudioEngine` | `AVAudioPCMBuffer` with a non-interleaved or interleaved float format |
| JVM desktop | `javax.sound.sampled` | Convert to 16-bit with `Pcm.intFromFloat` first |
| Browser | Web Audio | `AudioBuffer.copyToChannel` takes float channels, so split with `Pcm.deinterleave` |

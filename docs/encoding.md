# Encoding

AudioKodec writes FLAC, WavPack, WAV, AIFF, AU, Apple Lossless in an `.m4a` file, and Vorbis
and Opus in Ogg. An encoder takes Float32 samples with an `AudioFormat`, a `DecodedAudio`, or
any `PcmSource`, and writes to a byte array or to a sink.

## Write a file

```kotlin
--8<-- "GuideSamples.kt:encode-wav"
```

To convert one decoded value into several formats, reuse it directly:

```kotlin
--8<-- "GuideSamples.kt:encode-decoded"
```

Each encoding reads the complete `DecodedAudio.samples` array from the
beginning through an independent reader. It preserves the cursor used by
direct `DecodedAudio.read` calls. Use `decoded.rewind()` to reset that direct
cursor, including after reaching the end. No rewind is needed for encoding
or for retrying after an encoding fails.

Other `PcmSource` implementations are consumed through their own cursor.
Create a fresh source for each encoding operation. The sample array is not
copied, so avoid changing it while an encoding runs.

The targets available are FLAC, lossless WavPack, integer PCM and IEEE float in WAV,
AIFF and AU, A-law and mu-law in all three, IMA or Microsoft ADPCM in WAV, Apple
Lossless in an `.m4a` file, and [Vorbis](#vorbis) and [Opus](#opus) in Ogg.

```kotlin
--8<-- "GuideSamples.kt:encode-alac"
```

ALAC is written at 16, 20, 24 or 32 bits with Apple's 4096-frame packets, and
the packets are the ones Apple's reference encoder writes for the same
samples. ALAC stores up to eight channels in its own speaker order: one, two,
three, five and six channels in their canonical layouts map onto it, and four,
seven or eight channels need a `ChannelLayout` naming ALAC's speakers, center
first. Any other layout is refused rather than played from the wrong speakers.
The `moov` box follows the audio, so the sink must support patching; byte
arrays and files do. Tags and cover art go into the iTunes item list, and
chapters into a Nero `chpl` list, as [MP4 metadata](mp4-tags.md) describes.

Reducing the depth of a float or a deeper integer source is where dither
belongs. It replaces the distortion of plain rounding with a small amount of
noise, which is what you want at 16 bits and below:

```kotlin
--8<-- "GuideSamples.kt:encode-dither"
```

Files above 4 GiB are written as RF64 automatically. AudioKodec reserves the
space for the larger size fields in every WAV it writes, and uses it when the
file turns out to need it.

RF64 and BW64 size tables match repeated chunk IDs in their stored order.
Tag edits preserve the surviving table entries in that order, together with
the original opaque chunks and audio bytes. Table entries and their lookup
storage count towards `DecodeOptions.maxAllocationBytes`.

## Channel layouts

The channel layout of the source is never silently relabeled. WAV PCM and float keep
any positional layout that follows the speaker-bit order of WAV, including custom mono
and stereo masks. AIFF and AU store only a channel count, compressed WAV has no mask in
this writer, and FLAC and Vorbis fix the layout for each channel count. WavPack keeps the
speakers of a WAV mask in the mask's order, followed by any unassigned channels. Encoding
refuses a layout that the target cannot represent before it writes any bytes.

## Tags and artwork

Pass a `TagBundle` to `encode` and the tags go into the file with the audio. WAV and
AIFF use their native text chunks, plus ID3v2.4 for Unicode and richer fields. FLAC
uses Vorbis comments and native `PICTURE` blocks, whose limits [Tags](tags.md#pictures)
describes. WavPack uses an APEv2 tag after the audio. Vorbis and Opus use Vorbis
comments too, Opus in its OpusTags header, with pictures in `METADATA_BLOCK_PICTURE`
comments. AU has no structured tag format, so it refuses a bundle that is not empty
instead of dropping it.

## FLAC

```kotlin
--8<-- "GuideSamples.kt:encode-flac"
```

`effort` runs from 0 to 8. It trades encoding time against size, as the numbered
levels of the reference encoder do, and it never changes the decoded audio, because
the format is lossless at every setting. See [Formats](formats.md#flac) for
verification and seeking.

## WavPack

```kotlin
val wv = AudioKodec.encode(samples, format, EncodeTarget.wavPack(bitDepth = 24, effort = 2))
```

`effort` runs from 0 to 3 and picks the reference encoder's fast, normal, high and very
high modes, `wavpack -f`, the default, `-h` and `-hh`. The encoder writes 8, 16, 24 and
32-bit lossless files with one to eight channels, and for the same samples writes the
same bytes as `wavpack -m --no-threads`. When the frame count is not known in advance the
file states an unknown length, which is corrected at the end if the sink can be patched.
See [WavPack](wavpack.md#encode).

## Vorbis

```kotlin
val ogg = AudioKodec.encode(samples, AudioFormat(48_000, 2), EncodeTarget.vorbis(quality = 4f))
```

Vorbis is lossy. The decoded audio is close to the input, not equal to it, and
nothing about it is bit-exact. `quality` runs from 0 to 10 on the same scale as
oggenc's `-q`, fractions included, and defaults to 4. The encoder aims at a
quality rather than a bitrate, so the size follows the material: quiet or
simple audio takes far less than the figures below, and silence takes almost
nothing.

The encoder writes one to eight channels at 8 to 192 kHz. Vorbis fixes a speaker
layout for each channel count, the one `AudioFormat(rate, channels)` gives, except
that seven channels need the 6.1 layout spelled out; the samples are reordered into
Vorbis's own channel order on the way in, and decoders reorder them back.
Integer input, other layouts and other channel counts are refused by name before
anything is written.

It reads the source once, from start to end, and never goes back to patch what it
has written, so it streams to any sink, and a source does not need to know its
length in advance. Decoders return exactly the frames that went in: the first
block is centered on the first sample, and the last page's granule position
trims the padding of the final block. libvorbisfile, ffmpeg and AudioKodec all
decode the exact length, including files shorter than one block. The serial
number is fixed, so the same input always produces the same bytes. Tags go into
the comment header with `AudioKodec` as the vendor string, and a bundle with
chapters is refused, because Vorbis comments have no standard place for them.

### How it works

The encoder is written from the Vorbis I specification. Each block goes through
the steps a decoder undoes:

1. A transient detector compares the energy of short pieces of the signal, 64
   samples at rates up to 48 kHz, octave by octave above 1.5 kHz, with the
   pieces before. A rise of 9 dB in any octave switches to short blocks around
   it, so coding noise cannot spread in front of an attack.
2. The block is windowed with the decoder's own window and transformed with a
   forward MDCT that is the exact mirror of the decoder's inverse.
3. A masking model in the style of Johnston's estimates how much noise each
   frequency hides, from the block's spectrum, its tonality, and spreading on
   the Bark scale. Tonal low notes are held to a minimum signal-to-noise ratio,
   and short blocks borrow the frequency resolution of the last long block
   below 1 kHz. The quality setting moves the noise relative to that threshold.
4. The noise level becomes the floor, a line of up to 34 points on a
   logarithmic scale, and each coefficient divided by the floor is rounded to an
   integer. Stereo pairs are coupled as magnitude and angle, which loses nothing
   on integers, and the integers are coded in partitions with codebooks the
   encoder designs and ships in the setup header.

### Measured quality

Lossy quality is measured with the internal weighted error of `opus_compare`,
the measure RFC 6716 defines for Opus, translated in the test suite. It compares
a decode with the original in 21 bands after letting the original mask the
difference the way hearing does; lower is better. The test signal is ten seconds
of seeded, music-like 48 kHz stereo: a chord of four notes with six harmonics
each, a sweep from 50 Hz to 19 kHz, a noise bed, and a burst of decaying noise
every half second. Each file is decoded by libvorbisfile, the library oggdec
uses, and both sides are rounded to 16 bits.

| Encoder | Bitrate | Error |
|---|---|---|
| AudioKodec, quality 1 | 83 kbit/s | 0.578 |
| AudioKodec, quality 3 | 124 kbit/s | 0.440 |
| AudioKodec, quality 4 (default) | 145 kbit/s | 0.379 |
| AudioKodec, quality 5 | 171 kbit/s | 0.315 |
| AudioKodec, quality 7 | 224 kbit/s | 0.218 |
| libvorbis, quality 0 | 66 kbit/s | 0.575 |
| libvorbis, quality 3 | 112 kbit/s | 0.461 |
| libvorbis, quality 6 | 206 kbit/s | 0.197 |
| The input low-passed at 15 kHz, not coded | | 0.405 |
| The input low-passed at 10 kHz, not coded | | 0.508 |

The libvorbis rows are ffmpeg's `libvorbis` encoder on the same signal, decoded
the same way. The
bounds `VorbisEncodeOracleTest` holds the encoder to were fixed before it was
written: at the default quality at most 160 kbit/s and an error of 0.50, and at
quality 7, the highest it tests, at most 256 kbit/s and 0.35. The error has to
fall and the bitrate rise at every step of quality. A 1 kHz sine at -6 dBFS
comes back with a signal-to-noise ratio of 34.5 dB at the default quality,
where the test asks for 30.

## Opus

```kotlin
--8<-- "GuideSamples.kt:encode-opus"
```

The encoder writes the music mode of Opus: CELT on its own, at full bandwidth (20 kHz),
mono or stereo, in 20 ms frames or, with `frameMillis = 10`, 10 ms ones. It is a
translation of libopus 1.5.2's CELT encoder at its highest complexity, with transient
detection and short blocks, the pitch prefilter, time-frequency analysis, dynamic
allocation, and mid/side and intensity stereo chosen as libopus chooses them. Driven the
way libopus's CELT-only mode drives it, libopus writes the same packets byte for byte.

| Option | Effect |
|---|---|
| `bitrate` | The target in bits per second, 6,000 to 510,000. The default is 128,000 |
| `vbr = true` (default) | Each packet's size follows how hard the frame is to code, and the long-run average follows the target, as `opusenc` and ffmpeg do by default |
| `constrainedVbr = true` | Never more than about one frame's worth of bits ahead of the target, libopus's library default, for streaming over a link of that rate. The default is `false` |
| `vbr = false` | Every packet is the same size, `bitrate * frameMillis / 8000` bytes |
| `frameMillis` | 20 (default) or 10 |

Opus always runs at 48 kHz. Input at another rate is converted first with the library's
[resampler](resample.md), and the identification header records the original rate. A
decoder returns the 48 kHz signal, which has `ceil(frames * 48000 / rate)` frames.

The file is gapless. The identification header's pre-skip is the encoder's delay, 120
samples, and the last page's granule position marks where the input ended, so any decoder
that applies both, as RFC 7845 requires, returns exactly as many frames as went in,
aligned with them: a click goes in and comes out on the same sample. The pages are written
as they fill, so the sink is never patched and the source's length need not be known.

Not written: the speech (SILK) and hybrid modes, more than two channels, which need the
multistream mapping and are refused with `UnsupportedFeatureException`, and the tonality
analysis with which libopus narrows the bandwidth and the stereo image at low rates.
Integer sources are refused; pass Float32.

The measurements below use a seeded ten-second stereo test signal: a three-note chord
with harmonics, a sweep from 50 Hz to 19 kHz, a noise bed, and a decaying burst of noise
every half second. The files are decoded by libopus, aligned only by their pre-skip, and
scored with `opus_compare`, the measure the Opus specification defines, as an internal
weighted error, lower being better. The last column is libopus's own encoder in its
CELT-only mode with constrained VBR, which also runs its tonality analysis; AudioKodec
with constrained VBR is never more than 0.05 worse than it, and better at 64 kbit/s.

| Rate | VBR (default) | Constrained VBR | libopus, constrained VBR |
|---|---|---|---|
| 64 kbit/s | 0.572 | 0.552 | 0.604 |
| 96 kbit/s | 0.568 | 0.575 | 0.575 |
| 128 kbit/s | 0.367 | 0.394 | 0.394 |
| 192 kbit/s | 0.115 | 0.115 | 0.104 |
| 256 kbit/s | 0.100 | 0.098 | 0.098 |
| 64 kbit/s mono | 0.559 | 0.724 | 0.728 |

`OpusEncodeOracleTest` holds the default to bounds fixed before the encoder was written:
an error of at most 0.70 at 64 kbit/s, 0.40 at 128 and 0.30 at 256 in stereo, 0.60 in mono
at 64, an average rate within 10% of the target, and an error that does not rise with the
rate. The average rate is within 6% of the target with VBR and within 2% with
constrained VBR. With constrained VBR the error does not fall from 64 to 96 kbit/s, and
the mono error at 64 kbit/s is 0.72, both as with libopus. A 1 kHz sine at -6 dBFS comes
back at 41 dB signal to noise at 128 kbit/s, and silence stays below 1e-30.

## Exact integers

`IntPcmSource` and an integer-array overload of `encode` take the stored integers of a
lossless decode without a trip through Float32. See [Decoding](decoding.md#precision-and-lossless-round-trips)
and [integer PCM encoding](integer-pcm.md).

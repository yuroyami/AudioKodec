# Encoding

AudioKodec writes FLAC, WAV, AIFF, AU, Apple Lossless in an `.m4a` file, and Opus in an
Ogg file. An encoder takes Float32 samples with an `AudioFormat`, a `DecodedAudio`, or any
`PcmSource`, and writes to a byte array or to a sink.

## Write a file

```kotlin
val wav = AudioKodec.encode(samples, AudioFormat(44_100, 2), EncodeTarget.wav(16))
```

To convert one decoded value into several formats, reuse it directly:

```kotlin
val decoded = AudioKodec.decode(bytes)
val wav = AudioKodec.encode(decoded, EncodeTarget.wav(16))
val flac = AudioKodec.encode(decoded, EncodeTarget.flac(16))
```

Each encoding reads the complete `DecodedAudio.samples` array from the
beginning through an independent reader. It preserves the cursor used by
direct `DecodedAudio.read` calls. Use `decoded.rewind()` to reset that direct
cursor, including after reaching the end. No rewind is needed for encoding
or for retrying after an encoding fails.

Other `PcmSource` implementations are consumed through their own cursor.
Create a fresh source for each encoding operation. The sample array is not
copied, so avoid changing it while an encoding runs.

The targets available are FLAC, integer PCM and IEEE float in WAV, AIFF and AU,
A-law and mu-law in all three, IMA or Microsoft ADPCM in WAV, Apple
Lossless in an `.m4a` file, and [Opus](#opus) in an Ogg file.

```kotlin
val m4a = AudioKodec.encode(decoded, EncodeTarget.alac(24), tags = tags)
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
AudioKodec.encode(samples, format, EncodeTarget.wav(16, dither = Dither.Tpdf()))
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
this writer, and FLAC fixes the layout for each channel count. Encoding refuses a layout
that the target cannot represent before it writes any bytes.

## Tags and artwork

Pass a `TagBundle` to `encode` and the tags go into the file with the audio. WAV and
AIFF use their native text chunks, plus ID3v2.4 for Unicode and richer fields. FLAC
uses Vorbis comments and native `PICTURE` blocks, whose limits [Tags](tags.md#pictures)
describes. Ogg Opus uses Vorbis comments in its OpusTags header, with pictures as
`METADATA_BLOCK_PICTURE`. AU has no structured tag format, so it refuses a bundle that is
not empty instead of dropping it.

## FLAC

```kotlin
val flac = AudioKodec.encode(samples, format, EncodeTarget.flac(bitDepth = 16, effort = 5))
```

`effort` runs from 0 to 8. It trades encoding time against size, as the numbered
levels of the reference encoder do, and it never changes the decoded audio, because
the format is lossless at every setting. See [Formats](formats.md#flac) for
verification and seeking.

## Opus

```kotlin
val opus = AudioKodec.encode(decoded, EncodeTarget.opus(bitrate = 128_000), tags = tags)
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
| `vbr = true` (default) | Each packet's size follows how hard the frame is to code, around the target |
| `constrainedVbr = true` (default) | Never more than about one frame's worth of bits ahead of the target, libopus's default. `false` lets bits move freely between loud and quiet passages, as `opusenc` and ffmpeg do by default |
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
CELT-only mode with constrained VBR, which also runs its tonality analysis; AudioKodec is
never more than 0.05 worse than it, and better at 64 kbit/s.

| Rate | Constrained VBR (default) | VBR | libopus, constrained VBR |
|---|---|---|---|
| 64 kbit/s | 0.552 | 0.572 | 0.604 |
| 96 kbit/s | 0.575 | 0.568 | 0.575 |
| 128 kbit/s | 0.394 | 0.367 | 0.394 |
| 192 kbit/s | 0.115 | 0.115 | 0.104 |
| 256 kbit/s | 0.098 | 0.100 | 0.098 |
| 64 kbit/s mono | 0.724 | 0.559 | 0.728 |

The average rate is within 2% of the target with constrained VBR and within 6% with VBR.
A 1 kHz sine at -6 dBFS comes back at 41 dB signal to noise at 128 kbit/s, and silence
stays below 1e-30. On this signal the score does not improve between 64 and 96 kbit/s
with constrained VBR, and the mono score at 64 kbit/s is high, both as with libopus.

## Exact integers

`IntPcmSource` and an integer-array overload of `encode` take the stored integers of a
lossless decode without a trip through Float32. See [Decoding](decoding.md#precision-and-lossless-round-trips)
and [integer PCM encoding](integer-pcm.md).

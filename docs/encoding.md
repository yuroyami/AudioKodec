# Encoding

AudioKodec writes FLAC, WavPack, WAV, AIFF, AU, Apple Lossless in an `.m4a` file, Vorbis
and Opus in Ogg, and MP3. An encoder takes Float32 samples with an `AudioFormat`, a `DecodedAudio`, or
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
Lossless in an `.m4a` file, [Vorbis](#vorbis) and [Opus](#opus) in Ogg, and [MP3](#mp3).

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
comments. MP3 gets an ID3v2.4 tag at the front, with 1024 bytes of padding so that a later
edit can usually rewrite it in place. AU has no structured tag format, so it refuses a bundle that is not empty
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

## MP3

```kotlin
--8<-- "GuideSamples.kt:encode-mp3"
```

The encoder writes MPEG-1 Layer III, mono or stereo, at 32, 44.1 or 48 kHz. It is written
from ISO/IEC 11172-3 alone, without reference to any MP3 encoder's source. Other sample
rates, including the MPEG-2 and MPEG-2.5 ones, and more than two channels are refused with
`UnsupportedFeatureException` before anything is written; resample or mix down first.
Integer sources are refused; pass Float32. Like any lossy coding it does not return the
input exactly.

| Target | Effect |
|---|---|
| `mp3(bitrate)` | A constant bitrate, one of 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256 and 320 kbit/s, given in bits per second. The default is 192,000. Every frame has the same size, give or take the padding byte that 44.1 kHz needs |
| `mp3Vbr(quality)` | A variable bitrate for a quality from 0, the best and largest, to 9, the smallest. The default is 3. Each frame takes the smallest bitrate that holds what its granules need |
| `jointStereo = true` (default) | Each frame of a stereo source codes the sum and difference of the channels when that needs fewer bits, and the channels themselves otherwise |
| `jointStereo = false` | The two channels are always coded apart |

Intensity stereo is not used, and neither are mixed blocks.

### Gapless playback

The first frame is a silent one holding a Xing header, `Info` for a constant bitrate and
`Xing` for a variable one, with the frame count, the byte count and a 100-entry seek table,
followed by the extension LAME introduced, which states the encoder delay and the padding
at the end. The encoder delays the signal by 528 samples, the analysis filterbank's 481
and the MDCT's 576 less the 529 every decoder adds on its own, and pads the end out to a
whole frame. A decoder that applies the two numbers returns exactly as many samples as went
in, lined up with them: AudioKodec, ffmpeg and libmpg123 all do. Like those of LAME, the
files' container duration that ffprobe reports counts whole frames, delay and padding
included; the decoded length is the exact one.

The extension's nine-byte encoder name is `LAMEAKdec`. ffmpeg only reads the gapless
numbers when that name starts with `LAME` (or with its own `Lavc` or `Lavf`), so the first
four bytes say which layout the block follows and the other five say that AudioKodec wrote
it.

The counts and the seek table are only known at the end. When the sink can be patched, the
first frame is rewritten then. When it cannot and the source states its frame count, the
header is computed in advance: in full at a constant bitrate, and without the byte count
and seek table at a variable one. When neither is possible, the header states only the
delay, so the decode starts on the first sample but keeps up to one frame of padding,
silence, at the end.

### How it works

Each granule of 576 samples goes through the stages Annex C of the standard describes:

1. The analysis filterbank splits the signal into 32 subbands. Its window is the decoder's
   synthesis window divided by 32, and a test runs it into the decoder's own synthesis
   filter and gets the input back.
2. Psychoacoustic model 2 of Annex D looks at the granule through a 1024-point FFT, and
   through three 256-point FFTs for a short block. It measures each part of the spectrum's
   tonality from how well its magnitude and phase are predicted from the two transforms
   before, spreads the energy across critical bands, and sets the noise each scalefactor
   band may carry: 29 dB below a tone and 6 dB below noise, with pre-echo control against
   the granules before and the threshold in quiet. Its partition tables are computed from
   the Bark scale instead of copied from the standard.
3. An onset detector compares the high-frequency energy of 64-sample pieces of the signal
   with the pieces before. A rise of 10 dB switches the granule to three short blocks,
   with start and stop blocks around them. Annex D switches on a high perceptual entropy
   instead, which also fires on dense but steady passages and misses quiet onsets.
4. The MDCT, with the window of each block type, the frequency inversion of odd subbands
   and the alias reduction butterflies, turns each subband's samples into spectral lines.
   A test runs it into the decoder's inverse for every block type and transition.
5. Each frame chooses between left and right and sum and difference by an estimate of the
   bits each needs.
6. At a constant bitrate, each granule's share of the frame and of the bit reservoir
   follows its perceptual entropy: one that needs more than usual, or a short block, draws
   on the reservoir, and the others leave a little in it. Annex C's two loops then
   quantize the granule within that budget: the inner loop finds the finest global step
   that fits, and the outer loop makes the step finer in every scalefactor band whose
   noise is above what the model allows. The allowed noise is first scaled to the level
   the budget can reach, so the outer loop has a goal it can meet.
7. At a variable bitrate, each scalefactor band is quantized to the coarsest step that
   keeps its noise within what the model allows, moved by the quality setting, and the
   frame takes the smallest bitrate that holds the result.
8. Huffman coding picks the cheapest of the tables sized for each region's largest value,
   tries every division into regions, and picks the cheaper of the two tables for the values of 1 and below.
   Scalefactors take the narrowest fields that hold them, pre-emphasis is used when it
   saves bits, and a second granule's scalefactors that equal the first's are not sent
   again.

The `main_data_begin` field never reaches before the start of the stream or more than 511
bytes back, and a frame and the reservoir it draws on never exceed the 7680 bits a decoder
is required to buffer, so at 320 kbit/s there is no reservoir.

### Measured quality

The test signal is the same kind as for Vorbis and Opus: ten seconds of seeded,
music-like 48 kHz stereo, with a chord of three notes with harmonics, a sweep to 19 kHz, a
noise bed and a decaying burst of noise every half second. Each file is decoded by ffmpeg's
float decoder and scored with `opus_compare`'s internal weighted error, lower being better.
The LAME rows are ffmpeg's `libmp3lame` encoder on the same signal, decoded the same way.

| Encoder | Bitrate | Error | LAME at the same setting |
|---|---|---|---|
| CBR 96 kbit/s | 96 kbit/s | 0.564 | 0.537 |
| CBR 128 kbit/s | 128 kbit/s | 0.445 | 0.464 |
| CBR 192 kbit/s (default) | 193 kbit/s | 0.271 | 0.286 |
| CBR 320 kbit/s | 321 kbit/s | 0.169 | 0.128 |
| VBR quality 0 | 247 kbit/s | 0.178 | |
| VBR quality 1 | 222 kbit/s | 0.225 | |
| VBR quality 2 | 199 kbit/s | 0.263 | |
| VBR quality 3 (default) | 179 kbit/s | 0.271 | |
| VBR quality 4 | 159 kbit/s | 0.308 | LAME `-V 4`, 158 kbit/s: 0.384 |
| VBR quality 5 | 139 kbit/s | 0.342 | |
| VBR quality 6 | 121 kbit/s | 0.384 | |
| VBR quality 7 | 105 kbit/s | 0.474 | |
| VBR quality 8 | 77 kbit/s | 0.572 | |
| VBR quality 9 | 49 kbit/s | 0.651 | |

`Mp3EncodeOracleTest` holds the encoder to bounds fixed before it was written: an error of
at most 0.45 at 192 kbit/s and 0.60 at 128, and at the default variable quality at most
0.45 at no more than 224 kbit/s on average. The error must not rise from 96 to 128, 192
and 320 kbit/s. Whole numbers of sine periods, including fewer samples than a frame and
lengths that are not a multiple of 1152, decode to exactly their length in AudioKodec and
ffmpeg, with no lag, and libmpg123 reports and decodes the exact length of music. A 1 kHz sine at -6 dBFS comes back at 70 dB signal to
noise at 192 kbit/s, where the test asks for 30, and silence stays below 1e-4. ffmpeg
decodes every file without a message at its error level, and AudioKodec and libmpg123
agree with it to within 1e-4 a sample, the bound `MpegOracleTest` uses for LAME's files.

On the JVM the encoder runs at about 15 to 25 times real time for stereo at a constant
bitrate and about 40 times at a variable one, on one core of a shared machine.

## Exact integers

`IntPcmSource` and an integer-array overload of `encode` take the stored integers of a
lossless decode without a trip through Float32. See [Decoding](decoding.md#precision-and-lossless-round-trips)
and [integer PCM encoding](integer-pcm.md).

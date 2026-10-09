# Convert DSD

DSD, Direct Stream Digital, is the one-bit signal of the Super Audio CD: a
single bit per channel at 2.8224 MHz (DSD64) or a multiple of it, whose
density of ones follows the waveform. It is stored in Sony's DSF files and
Philips's DSDIFF files, and AudioKodec reads both and converts the bits to
PCM.

```kotlin
val info = AudioKodec.probe(dsfBytes)
println(info.container)              // DSF, or DFF for DSDIFF
println(info.codec)                  // DSD
println(info.format.sampleRate)      // 88200 for DSD64

val samples = AudioKodec.decode(dsfBytes).samples
```

## The rate that comes out

There is no single correct PCM rendering of a DSD stream: the conversion is a
lowpass filter and a choice of output rate, and every converter makes its
own. AudioKodec's output is at a thirty-second of the DSD rate, and
`AudioFormat` reports it as such:

| Input | DSD rate | PCM rate |
|---|---|---|
| DSD64 | 2.8224 MHz | 88.2 kHz |
| DSD128 | 5.6448 MHz | 176.4 kHz |
| DSD256 | 11.2896 MHz | 352.8 kHz |
| DSD512 | 22.5792 MHz | 705.6 kHz |

Higher rates convert as well, once `DecodeOptions.maxSampleRate` allows the
rate that results; the default ceiling, 768 kHz, stops at DSD512.

## The filter

Each bit counts as +1 or -1, and three decimating lowpass stages follow,
each a Kaiser-windowed sinc of odd length with unit gain at zero frequency:

| Stage | Rate | Taps |
|---|---|---|
| 1 | fs to fs/8 | 79, applied ten bytes of bits at a time through tables |
| 2 | fs/8 to fs/16 | 27 |
| 3 | fs/16 to fs/32 | 67 |

Together they pass 0 to fs/128, 22.05 kHz from DSD64, flat within 2e-5 dB.
Every stage attenuates by at least 119 dB whatever would fold into the
output band when it decimates, and the last attenuates everything from
fs/64, the output's Nyquist frequency, upwards, so nothing aliases. Between
fs/128 and fs/64 the response falls away: that band holds the noise a DSD
modulator pushes out of the audio band, which a PCM signal at full scale
would otherwise carry.

The filters are linear phase and the cascade is centred, so output sample k
is the signal at bit 32k, with no delay. The taps come from
`design-dsd-filters.py` in the repository, which checks each stage's
response before printing it.

## How it compares with ffmpeg

ffmpeg converts DSD differently: one 96-tap filter from fs to fs/8, 352.8
kHz from DSD64, which delays the signal by 40.5 bits. The two cannot be
compared sample by sample, so the oracle suite compares them tone by tone.
It writes DSF and DSDIFF files from a sigma delta modulator, decodes them
with both, and measures each tone's level and phase in both outputs, with
ffmpeg's delay taken out of its phase; the suite measures that delay too,
from the difference a single flipped bit makes.

The bound is the filters' difference in the audio band. ffmpeg's falls by
0.0016 dB at 22.05 kHz, and AudioKodec's stays within 2e-5 dB, so levels must
agree within 0.002 dB, and phases within the 0.001 rad that a level
difference that small allows. In practice a 1 kHz tone agrees within 0.00003
dB and 2e-8 rad, and a 19 kHz tone within 0.001 dB and 2e-6 rad.

## Channels

DSF's seven channel types and DSDIFF's named channels are put into
AudioKodec's speaker order; DSDIFF's surrounds, `LS` and `RS`, are the
surround pair of AudioKodec's 5.1 layout. A DSDIFF file that names a
channel AudioKodec does not know, or the same speaker twice, keeps the
file's order with an unknown layout.

## Seeking

Every output sample depends only on the 671 bits either side of its own, so
a seek reads just those and lands on exactly the samples a continuous decode
gives.

## Tags

The ID3v2 block a DSF file's header points to, and the `ID3 ` chunk DSDIFF
writers add, are read like any other ID3v2 block. DSDIFF's own comment and
edited master chunks are not read. Tags cannot be written to either format.

## What is refused

DSDIFF files compressed with DST, the lossless coding of most Super Audio CD
masters, are refused by name with `UnsupportedFeatureException`, and so is
any other compression a DSDIFF file states, and a DSF version or format
other than the one defined.

## Damaged files

| Case | Result |
|---|---|
| A file that ends before its sound data does | `TruncatedStreamException`, or in tolerant mode the audio up to the last complete DSF block group or DSDIFF byte group |
| A DSF channel type outside the seven defined, or a channel count that disagrees with it | `CorruptStreamException` |
| A DSD rate that is not a multiple of 32, or a PCM rate past `maxSampleRate` | `CorruptStreamException` |
| A DSDIFF file with no sound data, or with its sound data before its channels or rate | `CorruptStreamException` |
| A chunk that runs past the end of the DSDIFF form | `CorruptStreamException` |

# Decode AC-3

AC-3, Dolby Digital, decodes from raw `.ac3` streams, from MP4 and QuickTime
tracks, and from Matroska tracks, through the same API as every other coding.

```kotlin
val info = AudioKodec.probe(ac3Bytes)
println(info.codec)                  // AC3
println(info.format.channelLayout)   // FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, LOW_FREQUENCY, SIDE_LEFT, SIDE_RIGHT
println(info.bitrate)                // 448000

AudioKodec.open(ac3Bytes).use { decoder ->
    decoder.seek(decoder.format.sampleRate * 30L)
    while (true) {
        val block = decoder.nextFrame() ?: break
        consume(block.samples, block.sampleCount)
    }
}
```

## What is decoded

ATSC A/52:2012, the bit stream syntax of its section 5 and the decoding of its
section 7, in full:

- the three sampling rates, 48, 44.1 and 32 kHz, and every frame size, so
  32 to 640 kbit/s;
- every coding mode, from dual mono to 3/2, with or without the low frequency
  effects channel, so one to six channels;
- exponents in all three strategies, D15, D25 and D45, with reuse;
- the parametric bit allocation of section 7.2 in the integer arithmetic the
  standard gives it, including delta bit allocation, fast and slow leak, the
  coupling channel's own start, and the low frequency channel's special case;
- mantissas of every size, with the groups of three, three and two that the
  smallest quantisers share across channels;
- channel coupling, with coordinates, master coordinates, bands that share a
  coordinate, and the phase flags of stereo;
- rematrixing, in the bands below coupling;
- block switching, with each channel switching on its own;
- the dynamic range gain word of every block, which is applied;
- the alternate bit stream syntax (`bsid` 6), whose extra fields the decoder
  has no use for, and every field of the bit stream information, which is read
  and skipped: language codes, production information, time codes, additional
  information and the skip field.

Its output is every channel the stream carries. Nothing is mixed down, and
neither the dialogue level (`dialnorm`) nor the heavy compression word
(`compr`) is applied: they change the level a listener hears, and a
player is the one to say which it wants. The dynamic range word is the one
the standard says to apply by default, and so it is.

## Samples and ffmpeg

The standard defines decoding in real arithmetic and AudioKodec decodes in
float, so a file decodes to the same samples ffmpeg gives to within the
rounding of ffmpeg's decoder, which holds each coefficient as a 24-bit
fixed-point integer. That rounding cuts a coefficient to a multiple of 2^-23
when it is dequantised, when its coupling coordinate is read, and when it is
decoupled, and it leaves a difference of up to 1.5e-5 on the encoder-written
files the test suite compares, 96 dB below full scale. The test suite also
reproduces that rounding exactly in a test-only mode of the decoder, and
then the two decoders agree to float rounding on every file and on frames that
use every element of the format, which shows that the syntax, bit allocation,
coupling, rematrixing and transforms are the same.

Where the two disagree otherwise, AudioKodec follows the standard and says so:

| | The standard | ffmpeg |
|---|---|---|
| Dither | A mantissa given no bits is replaced by noise when its channel's dither flag is set, and the standard leaves the noise open | Its own generator. The same flags give different noise with the same energy. AudioKodec's restarts with every syncframe, so a decode is repeatable and a seek exact |
| Coarse SNR offset zero | Every channel gets no bits only when every fine offset is zero too | Each channel whose own fine offset is zero gets none |
| Delta bit allocation | A segment may reach the last of the 50 bands | A segment that reaches it is refused |
| Block switching | Each channel has its own flag and its own overlap with the block before | In a block where the channels switch differently, one channel is overlapped with the tail of channel 0's previous block |

Two readings are not the standard's to settle and follow ffmpeg:

- The coupling channel's delta bit allocation counts its bands from the first
  one the coupling channel covers. The standard says "the first bit allocation
  band of the coupling channel", and a 5-bit offset could not otherwise reach
  most coupling bands.
- In a dual mono stream, which carries two dynamic range words, the low
  frequency channel takes the second.

## Length, delay and seeking

A syncframe holds six blocks of 256 samples per channel, 1536 in all, and
states its own size, so the length is the syncframes counted. AC-3 has no
priming and no padding of its own; an MP4 edit list or a Matroska
`CodecDelay` that trims a file is applied as it is for every other coding.

A syncframe's first block overlaps the last block of the one before and
nothing else carries over, so a seek decodes the syncframe before its target
and throws it away. That makes a seek sample-exact.

A raw stream may begin with an ID3v2 tag, which is skipped. Bytes between
syncframes are skipped too, and a sync word is believed only when the three
syncframes after it agree with it, so a `0B 77` inside the audio is not taken
for one. A stream that stops in the middle of a syncframe throws
`TruncatedStreamException`, or ends before it when `DecodeOptions.tolerant`
is set.

## Damage

Every syncframe ends with a CRC over the whole frame. A syncframe that fails
it throws `CorruptStreamException`. With `tolerant` set the check is skipped
and the frame is decoded as stored, so a damaged CRC field costs nothing; a
frame whose contents cannot be decoded ends the stream there, and nothing is
thrown.

## What is refused

| Stream | Result |
|---|---|
| Enhanced AC-3 (`bsid` 11 to 16), which shares AC-3's sync word, in a raw stream, MP4 or Matroska | `UnsupportedFormatException` naming Enhanced AC-3. Plain AC-3 decodes |
| `bsid` 9 and 10 | `UnsupportedFeatureException`: the standard tells decoders built to it not to decode them |
| A syncframe header with a reserved sampling rate or frame size code | `CorruptStreamException` |

`probe` reports the sampling rate, the channels, the bit rate of the first
syncframe and the exact length, for plain AC-3 only.

## Channel order

Dolby Digital stores its channels as left, centre, right, left surround,
right surround and the low frequency channel. They come out in AudioKodec's
order: front left, front right, centre, low frequency, then the surround
pair. A single surround channel (3/1 and 2/1) is reported as the back centre,
and a surround pair as the side pair, as ffmpeg labels them.

A test writes a different tone into each channel of every layout and finds
each tone in the speaker it was recorded for.

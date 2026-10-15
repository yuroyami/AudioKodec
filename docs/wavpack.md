# WavPack

WavPack files decode through the same API as every other format, and a
lossless file gives its exact integers through `IntPcmAccess`. AudioKodec
also writes lossless WavPack, as [Encode](#encode) describes.

```kotlin
val info = AudioKodec.probe(wvBytes)
println(info.codec)        // WAVPACK
println(info.bitDepth)     // 24

AudioKodec.open(wvBytes).use { decoder ->
    val exact = decoder as? IntPcmAccess
    while (true) {
        val block = exact?.nextIntFrame() ?: break
        consume(block.samples, block.sampleCount)
    }
}
```

## What is decoded

WavPack 4 and 5 files, stream versions 0x402 to 0x410:

- every encoding mode, fast, normal, high and very high, with or without the
  extra filter searches, which differ only in what the encoder chose and
  decode the same way;
- integers of 1 to 32 bits in one to four bytes, including depths that do
  not fill their bytes such as 20 bits, and the extended forms the encoder
  uses above 24 bits;
- 32-bit floats, scaled to -1 to 1 when the encoder recorded another range;
- mono, stereo, joint stereo and false stereo, which stores one channel and
  plays it in two;
- multichannel audio, carried in runs of mono and stereo blocks, put in
  AudioKodec's speaker order by the file's channel mask, with channels the
  mask leaves unassigned reported as unnamed speakers;
- hybrid files, decoded to their lossy stream.

Every block's checksum, of its bytes when the file has one and of its decoded
samples always, is verified, so damage that the format can detect is never
played. The decoder is a translation of the reference library, WavPack 5.9.0,
and works in its integer arithmetic, so it gives the reference's samples
exactly.

## Seeking

WavPack blocks are independent: each carries the decoder state it starts
from. Seeking goes straight to the block that holds the target and decodes
from there, with no run-up, and lands on exactly the samples a continuous
decode gives. Opening a file walks its block headers once, without decoding
them, to find where every block is.

## Integers and floats

`IntPcmAccess` gives right-justified integers at the file's bit depth: a
20-bit file gives values from -524288 to 524287. Float output divides by the
full scale of the bytes the samples are stored in, so a 20-bit sample and the
same sample in a 24-bit file give the same float.

Floating-point files and hybrid files have no exact integers, so their
decoders do not offer `IntPcmAccess`.

## Hybrid files

A hybrid file is lossy on its own; a separate correction file, `.wvc`, holds
what makes it lossless. AudioKodec reads only the `.wv` file and decodes the
lossy audio, exactly as the reference decoder does without the correction
file. `AudioInfo.bitDepth` reports the depth the encoder started from.

## Tags

WavPack files carry APEv2 tags, and sometimes ID3v1, at the end. They are
read like any other file's, through `AudioKodec.readTags`, and written by
`AudioKodec.writeTags` as an APEv2 tag after the blocks, which are copied
byte for byte. An ID3v1 block is kept up to date when the file has one, and
so is an ID3v2 block in front of the blocks; neither is created unless
`TagWriteOptions.formats` asks for ID3v2.

## Encode

```kotlin
val wv = AudioKodec.encode(samples, AudioFormat(44_100, 2), EncodeTarget.wavPack(bitDepth = 16))
val smaller = AudioKodec.encode(decoded, EncodeTarget.wavPack(bitDepth = 24, effort = 3), tags = tags)
```

The encoder writes lossless WavPack. It is a translation of the reference
library, WavPack 5.9.0, in its integer arithmetic, and it makes the choices
the reference's `wavpack` program makes. For the same samples it writes the
same file, byte for byte, as `wavpack -m --no-threads` given them as raw PCM:
the oracle tests compare the two in every mode, at every depth, from mono to
eight channels, at standard and odd sample rates, with silence, identical
channels, full-scale noise, unused low bits, files shorter than a block and
files of many blocks. `wvunpack` verifies every file's checksums and MD5 sum
and decodes it to exactly the integers that went in, and so does AudioKodec.

`effort` picks one of the reference's four modes:

| `effort` | `wavpack` option | Filter terms | Block length at 44.1 kHz stereo |
|---|---|---|---|
| 0 | `-f`, fast | 2 | 11025 frames |
| 1 (default) | none, normal | 5 | 11025 frames |
| 2 | `-h`, high | 10 | 22050 frames |
| 3 | `-hh`, very high | 16 | 22050 frames |

Each mode uses a longer filter than the one before it, which usually makes a
smaller file, and none changes what comes back out. The extra filter
searches that `wavpack` selects with `-x` are not offered.

What is written:

- integers of 8, 16, 24 or 32 bits. A float source, or an integer source of
  another depth, is converted to the target depth first, with `dither` if you
  ask for it, as for the other lossless targets. 32-bit audio carries its low
  bits in the extension stream the format provides for depths above 24;
- one to eight channels. WavPack keeps the speakers of a WAV channel mask
  first, in the mask's order, then any unassigned channels, and pairs front,
  back, side and other left and right speakers into stereo streams the way
  the reference does. A layout in any other order is refused before output,
  and so is a mono or stereo layout with unassigned speakers, which would
  read back as the canonical one;
- any sample rate, with the rates outside WavPack's table stored in a
  metadata item of their own;
- a RIFF header in the first block, made as the reference library makes it,
  so `wvunpack` restores a WAV file;
- the MD5 sum of the audio in a block of its own after the last block, as
  `wavpack -m` stores it;
- an APEv2 tag after the blocks when the `TagBundle` is not empty. Cover art
  goes into the tag; chapters are refused, because APE has nowhere to put
  them.

The first block states the file's length. When the source's `frameCount` is
known it is written there from the start. When it is not, the length is
written as unknown, the value the reference uses, and corrected at the end
together with the RIFF header if the sink can be patched. A file whose sink
could not be patched keeps the unknown length; AudioKodec and `wvunpack`
both find the real length by reading the blocks.

| Case | Result |
|---|---|
| A source with no frames | `AudioKodecException`; the reference refuses a length of zero too |
| A source that produces a different number of frames from its `frameCount` | `AudioKodecException` after the audio is written |
| A layout WavPack cannot carry, or more than eight channels | `IllegalArgumentException` before output |
| Chapters in the tags | `UnsupportedFeatureException` before output |
| Bit depths other than 8, 16, 24 and 32, or an `effort` outside 0 to 3 | `IllegalArgumentException` from `EncodeTarget.wavPack` |

Hybrid lossy mode and its correction file are not written, and neither are
32-bit float files: lossless integers are what people choose WavPack for.

## What the decoder refuses

| Case | Result |
|---|---|
| DSD audio stored in WavPack | `UnsupportedFeatureException` naming "WavPack DSD audio" |
| Files from before WavPack 4.0, which begin with a RIFF header | read as the WAV files they look like, and not decoded |
| A block whose checksum fails, or whose metadata the reference would reject | `CorruptStreamException`, or in tolerant mode the end of the stream |
| A run of blocks cut off by the end of the file | `TruncatedStreamException`, or in tolerant mode the end of the stream |

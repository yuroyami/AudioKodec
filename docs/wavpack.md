# Decode WavPack

WavPack files decode through the same API as every other format, and a
lossless file gives its exact integers through `IntPcmAccess`.

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
read like any other file's, through `AudioKodec.readTags`.

## What is refused

| Case | Result |
|---|---|
| DSD audio stored in WavPack | `UnsupportedFeatureException` naming "WavPack DSD audio" |
| Files from before WavPack 4.0, which begin with a RIFF header | read as the WAV files they look like, and not decoded |
| A block whose checksum fails, or whose metadata the reference would reject | `CorruptStreamException`, or in tolerant mode the end of the stream |
| A run of blocks cut off by the end of the file | `TruncatedStreamException`, or in tolerant mode the end of the stream |

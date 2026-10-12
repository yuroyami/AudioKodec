# Decode True Audio

True Audio files decode through the same API as every other format, and
give their exact integers through `IntPcmAccess`.

```kotlin
val info = AudioKodec.probe(ttaBytes)
println(info.container)    // TTA
println(info.bitDepth)     // 16

AudioKodec.open(ttaBytes).use { decoder ->
    val exact = decoder as IntPcmAccess
    while (true) {
        val block = exact.nextIntFrame() ?: break
        consume(block.samples, block.sampleCount)
    }
}
```

## What is decoded

Files in the `TTA1` format with integer samples, the format True Audio's own
encoder and ffmpeg's write:

- 1 to 24 bits, including depths that do not fill their bytes such as 20
  bits;
- any number of channels, in the order the WAV file the encoder read had
  them, which AudioKodec reports as the default layout for the count;
- any sample rate, with frames of 256/245 of a second, so 46080 samples at
  44.1 kHz.

The header, the seek table and every frame carry a CRC-32, and all three are
checked, so damage the format can detect is never played. The decoder is a
translation of the format authors' TTA Hardware Players Library 1.2 and
works in its integer arithmetic, so it gives the reference's samples
exactly.

## Seeking

Every True Audio frame starts its filters afresh. Seeking goes straight to
the frame that holds the target, through the seek table at the start of the
file, and decodes from there with no run-up, landing on exactly the samples
a continuous decode gives.

## Integers and floats

`IntPcmAccess` gives signed, right-justified integers at the file's bit
depth: 8-bit audio comes out from -128 to 127, and 20-bit audio from -524288
to 524287. True Audio codes a 20-bit sample as the 24-bit value the WAV file
held, with its low four bits clear; AudioKodec shifts them off, as it does
for a 20-bit WAV file. Float output divides by the full scale of the bit
depth.

## Tags

True Audio files carry ID3v2 tags at the start and APEv2 or ID3v1 tags at
the end. They are read like any other file's, through `AudioKodec.readTags`.

## What is refused

| Case | Result |
|---|---|
| A file encrypted with a password, format 2 | `UnsupportedFeatureException` naming "password-protected True Audio" |
| A file stating floating-point samples, format 3, which no encoder writes | `UnsupportedFeatureException` naming "floating-point True Audio" |
| A header or seek table whose checksum fails | `CorruptStreamException` |
| A frame whose checksum fails, or whose codes do not fit it | `CorruptStreamException`, or in tolerant mode the end of the stream |
| A frame cut off by the end of the file | `TruncatedStreamException`, or in tolerant mode the end of the stream |

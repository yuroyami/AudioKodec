# Decode Monkey's Audio

Monkey's Audio files decode through the same API as every other format, and
an integer file gives its exact integers through `IntPcmAccess`.

```kotlin
val info = AudioKodec.probe(apeBytes)
println(info.container)    // APE
println(info.bitDepth)     // 16

AudioKodec.open(apeBytes).use { decoder ->
    val exact = decoder as? IntPcmAccess
    while (true) {
        val block = exact?.nextIntFrame() ?: break
        consume(block.samples, block.sampleCount)
    }
}
```

## What is decoded

Files written by version 3.99 of the encoder, from 2004, and every version
since:

- every compression level, fast, normal, high, extra high and insane, each
  with the prediction filters the encoder used for it;
- integers of 8, 16, 24 and 32 bits, and 32-bit floats;
- mono, stereo and multichannel audio up to 32 channels, except seven, put
  in AudioKodec's speaker order by the channel mask of the WAV header the
  file stores, or in the default order when it stores none;
- the frames the encoder marks as silent, or as stereo whose channels are
  identical.

Every frame's checksum is verified, so damage the format can detect is
never played. The decoder is a translation of the Monkey's Audio SDK 13.27
and works in its integer arithmetic, so it gives the SDK's samples exactly.
Where the SDK decodes a 24-bit frame a second time in its interim mode
because the first attempt fails its checksum, so does AudioKodec.

## Seeking

Every Monkey's Audio frame starts its predictors and range coder afresh.
Seeking goes straight to the frame that holds the target, through the seek
table at the start of the file, and decodes from there with no run-up,
landing on exactly the samples a continuous decode gives.

## Integers and floats

`IntPcmAccess` gives signed integers at the file's bit depth; 8-bit audio,
stored unsigned in WAV, comes out from -128 to 127. Float output divides by
the full scale of that depth.

Floating-point files have no exact integers, so their decoders do not offer
`IntPcmAccess`. Their samples come out as the floats that went in.

## Tags

Monkey's Audio files carry APEv2 tags, and sometimes ID3v1, at the end. They
are read like any other file's, through `AudioKodec.readTags`.

## What is refused

| Case | Result |
|---|---|
| A file from before version 3.99 | `UnsupportedFeatureException` naming "Monkey's Audio files from before version 3.99" |
| A file with seven channels, which the SDK refuses too | `UnsupportedFeatureException` naming "Monkey's Audio with seven channels" |
| A frame whose checksum fails, or a header or seek table that cannot be right | `CorruptStreamException`, or in tolerant mode the end of the stream |
| A frame cut off by the end of the file | `TruncatedStreamException`, or in tolerant mode the end of the stream |

The MD5 digest of the whole file, which the encoder stores, is not checked:
it covers every frame, so checking it would mean reading the file to its end
before the first sample. Each frame's own checksum is checked instead.

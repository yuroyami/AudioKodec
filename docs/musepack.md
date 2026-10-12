# Decode Musepack

Musepack files decode through the same API as every other format.

```kotlin
val info = AudioKodec.probe(mpcBytes)
println(info.container)       // MUSEPACK
println(info.durationFrames)  // the exact length the stream header states

AudioKodec.open(mpcBytes).use { decoder ->
    while (true) {
        val block = decoder.nextFrame() ?: break
        play(block.samples, block.frameCount)
    }
}
```

## What is decoded

Stream version 8, the format Musepack's encoder has written since 2009:

- every quality setting, from the lowest to the highest;
- mono and stereo, with or without mid/side coding;
- noise substitution, which replaces quiet bands with noise of the same
  level;
- sample rates of 32, 37.8, 44.1 and 48 kHz.

The stream header states how many samples the recording has and how much
silence the encoder put before it. AudioKodec removes that silence and the
synthesis filter's delay of 481 samples, and stops at the stated length, so
`AudioInfo.durationFrames` is exact and the decoded audio lines up with what
went into the encoder.

The decoder is a translation of libmpcdec, the reference decoder, and works
in its single-precision arithmetic in the same order. On the JVM every
sample is the float the reference gives. JavaScript keeps floats in double
precision, so there samples can differ from the reference's in their last
few bits, by less than 1e-5 of full scale.

## Seeking

A Musepack stream is a run of audio packets, each starting with a key frame
that the rest of the packet is coded against. Seeking decodes from the key
frame of the packet that holds the frame before the target, so that at
least one frame of run-up fills the synthesis filter with the same history
a continuous decode gives it, and lands on the same samples.

The one exception is noise substitution. Its noise comes from a generator
that runs through the whole stream, as the reference's does, and after a
seek the generator starts again, so a band filled with noise is filled with
different noise of the same level.

## Tags

Musepack files carry APEv2 tags at the end, and sometimes ID3v2 at the
start. They are read like any other file's, through `AudioKodec.readTags`.
The replay gain values in the stream header are not applied to the samples.

## What is refused

| Case | Result |
|---|---|
| Stream version 7 and older, which begin `MP+` or have no magic number | `UnsupportedFeatureException` naming "Musepack stream version 7", or not recognized as Musepack at all |
| A stream header whose checksum fails, or that states an impossible format | `CorruptStreamException` |
| An audio packet whose frames do not fit it | `CorruptStreamException`, or in tolerant mode the end of the stream |
| A stream that ends before the samples its header promises | `TruncatedStreamException`, or in tolerant mode the end of the stream |

Musepack's audio packets carry no checksum, so damage inside one that still
decodes is not detected, by AudioKodec or by the reference.

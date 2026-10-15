# Encode FLAC

**Depends on** `io.github.yuroyami:audiokodec:0.1.0`. See [Install](../index.md#install).

FLAC is lossless: once the samples are at the bit depth you choose, the file decodes to
exactly those samples. Give `encode` interleaved Float32 samples, their format and a
target:

```kotlin
--8<-- "EncodeFlacSample.kt:imports"

--8<-- "EncodeFlacSample.kt:encode"
```

- `bitDepth` is 8, 16 or 24. Float samples are rounded to it, which is the only lossy step.
- `effort` runs from 0 to 8, default 5, and trades encoding time for size. It never
  changes the decoded audio.
- Tags are optional; they are stored as Vorbis comments, and pictures as `PICTURE` blocks.

FLAC also stores an MD5 of the audio, so a file can be checked against itself:

```kotlin
--8<-- "EncodeFlacSample.kt:verify"
```

## Convert another file

A decoded file can go straight back into `encode`, with its tags:

```kotlin
--8<-- "EncodeFlacSample.kt:convert"
```

Dither is worth adding whenever the source is deeper than the target, as when a 24-bit or
float recording goes to 16 bits.

To write to a file instead of a byte array, see [Use kotlinx-io](kotlinx-io.md). The
[Encoding guide](../encoding.md) covers WAV, AIFF, AU and Apple Lossless as well.

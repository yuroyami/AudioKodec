# Stream and seek through a long file

**Depends on** `io.github.yuroyami:audiokodec:0.1.0`. See [Install](../index.md#install).

A decoder hands back one block of samples at a time, so a file of any length plays in the
memory of a few blocks. `seek` moves it to an exact frame.

```kotlin
--8<-- "StreamSample.kt:imports"

--8<-- "StreamSample.kt:stream"
```

- A *frame* is one sample for every channel. Positions and lengths count frames, so one
  second is `sampleRate` frames.
- `seek` is sample accurate: the next block starts at the frame you asked for, not at the
  start of the block that contains it. When the length is known, a target past the end is
  clamped to the end.
- Block sizes are the codec's own and vary between formats. Do not assume a fixed size.
- `DecodeOptions(reuseBuffers = true)` makes the decoder return the same array every time,
  which removes almost all allocation from the loop. Each block is then valid only until
  the next `nextFrame()` call.
- `use { }` closes the decoder, and the decoder closes its source.

To read a file from disk this way, open it with `audiokodec-io`: see
[Use kotlinx-io](kotlinx-io.md).

## Read part of a file

This function seeks to `start` and stops decoding once it has the frames it returns:

```kotlin
--8<-- "StreamSample.kt:window"
```

The [Decoding guide](../decoding.md#stream-and-seek) says more about seeking.

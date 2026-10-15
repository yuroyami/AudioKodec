# Draw a waveform

**Depends on** `io.github.yuroyami:audiokodec:0.1.0`. See [Install](index.md#install).

Drawing a waveform means reducing millions of samples to a few thousand
buckets, each with the lowest and highest sample and the root mean square
of its frames. `WaveformReducer` does that a block at a time, so a two hour
file streams through in bounded memory.

```kotlin
--8<-- "WaveformSample.kt:imports"

--8<-- "WaveformSample.kt:waveform"
```

`bucketCount` says how many buckets a stream of a given length reduces to
before any audio is read, so a canvas can be sized first. Each bucket covers
`framesPerBucket` frames; the last one may hold fewer, and its root mean
square is taken over the frames it holds. Figures are per channel, by bucket
then channel.

Only the bucket being filled and the finished buckets not yet pulled are
held. Pulling after every block, as above, keeps that to a few buckets, and
once the holding space has grown to fit the largest block, nothing more is
allocated.

The buckets do not depend on how the audio was split into blocks: one
block or many give identical figures.

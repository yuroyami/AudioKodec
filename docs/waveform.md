# Draw a waveform

Drawing a waveform means reducing millions of samples to a few thousand
buckets, each with the lowest and highest sample and the root mean square
of its frames. `WaveformReducer` does that a block at a time, so a two hour
file streams through in bounded memory.

```kotlin
val info = AudioKodec.probe(source)
val perBucket = 1_024
val buckets = WaveformReducer.bucketCount(info.durationFrames ?: 0, perBucket).toInt()
val channels = info.format.channels
val min = FloatArray(buckets * channels)
val max = FloatArray(buckets * channels)
val rms = FloatArray(buckets * channels)

val reducer = WaveformReducer(channels, perBucket)
var pulled = 0
AudioKodec.open(source, DecodeOptions(reuseBuffers = true)).use { decoder ->
    while (true) {
        val frame = decoder.nextFrame() ?: break
        reducer.push(frame.samples, 0, frame.frameCount)
        pulled += reducer.pull(min, max, rms, pulled)
    }
}
reducer.finish()
pulled += reducer.pull(min, max, rms, pulled)
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

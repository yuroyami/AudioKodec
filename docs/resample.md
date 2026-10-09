# Change the sample rate

A decoded file comes out at the rate it was encoded at, and an output
device usually wants one fixed rate. `Resampler` converts interleaved float
PCM from one rate to another, a block at a time, and `Pcm.resample` converts
a whole buffer at once.

```kotlin
val decoded = AudioKodec.decode(bytes)
val at48k = Pcm.resample(decoded.samples, decoded.format.channels, decoded.format.sampleRate, 48_000)
```

## Streaming

A `Resampler` takes blocks of any size and writes what each block allows.
The filter looks a little ahead, so the output trails the input by half its
length, and `flush` writes the last frames at the end of the stream:

```kotlin
val resampler = Resampler(inputRate = 44_100, outputRate = 48_000, channels = 2)
val out = FloatArray(resampler.maxOutputFrames(4_096) * 2)

AudioKodec.open(source, DecodeOptions(reuseBuffers = true)).use { decoder ->
    while (true) {
        val frame = decoder.nextFrame() ?: break
        var at = 0
        while (at < frame.frameCount) {
            val take = minOf(4_096, frame.frameCount - at)
            val written = resampler.process(frame.samples, at, take, out, 0)
            play(out, written)
            at += take
        }
    }
}
val tail = FloatArray(resampler.pendingOutputFrames * 2)
play(tail, resampler.flush(tail))
```

Nothing is allocated after the resampler is built: the filter is computed
once, and every block passes through one fixed buffer. With an output array
reused as above, a whole file converts without allocating.

The output is aligned with the input. Output frame k is the signal at input
time k times `inputRate / outputRate`, with no delay, and a stream of n
frames converts to exactly `ceil(n * outputRate / inputRate)`. The signal is
taken as zero outside the stream. `reset` starts another stream at the same
rates.

## Quality

| Mode | What it does | Measured on a 1 kHz tone |
|---|---|---|
| `ResampleQuality.SINC`, the default | speexdsp's windowed sinc resampler at its highest quality, 10 | -134.8 dB or lower |
| `ResampleQuality.LINEAR` | Straight-line interpolation between neighbouring samples | -59.4 to -63.9 dB between 44.1, 48 and 96 kHz; -31.2 dB from 8 to 48 kHz |

The figure is the distortion and noise the conversion adds, relative to the
tone: a sine at -6 dBFS is converted, a sine of its frequency is fitted to
the result, and whatever the fit leaves is counted. The sinc figure is the
worst across conversions between 8, 16, 22.05, 32, 44.1, 48, 88.2 and 96
kHz; at 40% of the lower rate it is -129.1 dB at worst.

The sinc mode is a translation of speexdsp 1.2.1's resampler in its float
build, sample for sample: a Kaiser-windowed sinc of 256 taps when the rate
goes up, and proportionally more when it goes down, cut off at 97.5% of the
lower Nyquist frequency. Against ffmpeg's soxr resampler at 28 bits, the
highest quality ffmpeg offers, its tones agree in phase and within 0.002 dB
in level; the difference is speexdsp's passband ripple near the band edge.

The linear mode filters nothing. Lowering the rate folds everything above
the new Nyquist frequency back into the band, and raising it leaves images
above the old one. It suits drawing a waveform preview, not listening.

Equal rates pass the samples through unchanged in either mode.

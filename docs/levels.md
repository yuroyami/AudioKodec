# Levels, fades and test signals

The small operations every audio tool needs: measure a range, find the
silence at its ends, apply a gain, fade in and out, and generate a test
signal. All of them work on interleaved float PCM.

## Measure

```kotlin
val scan = Levels.scan(samples, channels)
println(scan.peak)              // the largest absolute sample
println(scan.peakFrame)         // where it is
println(scan.rms)               // over every sample in the range
println(scan.clippedSamples)    // samples at or beyond full scale
```

`Levels.findSilence` finds where the sound starts and stops: a frame is
sound when any of its samples rises above a threshold, -60 dBFS unless
another is given, and the result gives the first sound frame and one past
the last, ready to trim with `copyOfRange`.

```kotlin
val bounds = Levels.findSilence(samples, channels)
val trimmed = samples.copyOfRange(bounds.soundStart * channels, bounds.soundEnd * channels)
```

`scan`, `findSilence` and `applyGain` take a range of frames, `from` and
`frameCount`, and default to the whole array. `fadeIn` and `fadeOut` need an
explicit range.

## Change

`Levels.applyGain` multiplies a range by a gain in decibels, in place.
`Levels.fadeIn` and `Levels.fadeOut` fade a range in from silence or out to
it, in place, along one of two curves:

| Curve | Gain along the fade | Halfway |
|---|---|---|
| `FadeCurve.LINEAR` | A straight line from 0 to 1 | 0.5, -6 dB |
| `FadeCurve.LOGARITHMIC` | A straight line in decibels from -60 dB to 0 dB, which sounds even | -30 dB |

Frame k of a fade in of n frames is scaled by the curve at k / n, so its
first frame is silent and full level arrives on the frame after it; a fade
out is the mirror image, ending on a silent frame.

## Generate

`Signals` makes test signals, the same in every channel: `sine`, `square`,
`whiteNoise`, uniform and repeatable from a seed, and `silence`, each at a
given length, channel count and, where it matters, sample rate.

```kotlin
val tone = Signals.sine(frames = 48_000, channels = 2, sampleRate = 48_000, frequency = 1_000.0, amplitude = 0.5f)
```

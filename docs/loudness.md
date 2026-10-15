# Measure loudness

**Depends on** `io.github.yuroyami:audiokodec:0.1.0`. See [Install](index.md#install).

`LoudnessMeter` measures loudness as EBU R 128 defines it, from
Recommendation ITU-R BS.1770: the integrated loudness of a whole program,
the momentary and short-term loudness of its last 400 ms and three seconds,
its loudness range, and the sample and true peak of each channel. It turns
the result into ReplayGain values in the form the tag writers take, so a
scan and a tag write are one operation.

```kotlin
--8<-- "LoudnessSample.kt:imports"

--8<-- "LoudnessSample.kt:loudness"
```

A meter takes blocks of any size, and reads at any point give the figures
so far, so a long file can be measured as it streams:

```kotlin
--8<-- "LoudnessSample.kt:loudness-stream"
```

`finish` lets the oversampler for the true peak reach the end of the
stream, a few milliseconds behind the input; nothing can be added after it.

Oversampling for the true peak is most of a meter's work, about ten times
the rest. A caller who needs only the loudness, to sort a library or apply a
gain, can leave it out with `LoudnessMeter(rate, layout, measureTruePeak =
false)`; `replayGain()` then gives the sample peak.

## ReplayGain

`replayGain()` gives the ReplayGain 2 values for one track: the gain that
brings it to -18 LUFS, and its true peak. `LoudnessMeter.albumReplayGain`
takes the meters of an album's tracks and adds the album gain, from the
tracks' gating blocks pooled and gated as one program, as ReplayGain 2
defines it, and the album's largest true peak.

## How it measures

The meter is a translation of libebur128 1.2.6, MIT licensed, in its double
precision: the K-weighting pre-filter, 400 ms gating blocks every 100 ms,
the absolute gate at -70 LUFS and the relative gate 10 LU below the
ungated mean, and the loudness range of EBU Tech 3342 from three-second
blocks every second, between the 10th and 95th percentiles above a relative
gate 20 LU down. Every gating block is kept, rather than libebur128's
optional histogram, so the figures are exact rather than rounded to a tenth.

Channels are weighted by position: the side and back surrounds by 1.41, the
low-frequency channel not at all, and every other channel, unknown
positions included, by 1.

The true peak is the largest absolute value of the waveform between the
samples as well as at them, found by oversampling four times below 96 kHz
and twice below 192 kHz, as libebur128 does, but with the windowed sinc of
AudioKodec's own [Resampler](resample.md) rather than libebur128's short
interpolator. It runs at speexdsp's quality 7, 128 taps, which on sums of
tones up to 45% of the sample rate came within 0.00003 dB of the peaks of
quality 10 at half the cost.

Every figure except the true peak matches libebur128's own, within double
rounding, in the oracle suite, and the integrated loudness is within a
tenth of a unit of ffmpeg's `ebur128` filter.

# Mix channels

A 5.1 file played on headphones has to become stereo, and a mono recording
sent to a stereo device has to become two channels. `Pcm.mixChannels`
converts interleaved float PCM from one channel layout to another with the
standard coefficients, and `Pcm.mixMatrix` shows them.

```kotlin
val decoded = AudioKodec.decode(bytes)
val stereo = Pcm.mixChannels(
    decoded.samples, decoded.frames, decoded.format.channelLayout, ChannelLayout.STEREO,
)
```

## The coefficients

Mixing down from the 3/2 layout follows Table 2 of Recommendation ITU-R
BS.775-3 exactly:

| Into | Output | L | R | C | LS | RS |
|---|---|---|---|---|---|---|
| 1/0, mono | C | 0.7071 | 0.7071 | 1 | 0.5 | 0.5 |
| 2/0, stereo | L | 1 | 0 | 0.7071 | 0.7071 | 0 |
| | R | 0 | 1 | 0.7071 | 0 | 0.7071 |
| 3/0 | L | 1 | 0 | 0 | 0.7071 | 0 |
| | R | 0 | 1 | 0 | 0 | 0.7071 |
| | C | 0 | 0 | 1 | 0 | 0 |
| 2/1 | L | 1 | 0 | 0.7071 | 0 | 0 |
| | R | 0 | 1 | 0.7071 | 0 | 0 |
| | S | 0 | 0 | 0 | 0.7071 | 0.7071 |
| 3/1 | L, R and C | each whole | | | 0 | 0 |
| | S | 0 | 0 | 0 | 0.7071 | 0.7071 |
| 2/2, quad | L | 1 | 0 | 0.7071 | 0 | 0 |
| | R | 0 | 1 | 0.7071 | 0 | 0 |
| | LS and RS | 0 | 0 | 0 | each whole | |

The surround pair is the back pair, `BACK_LEFT` and `BACK_RIGHT`, in
AudioKodec's 5.1, and the single surround S is `BACK_CENTER`.

BS.775 describes a 3/2 system without a low-frequency channel, and the
low-frequency channel is left out of a mix that has none, as ffmpeg leaves
it out.

A channel the output has keeps its place at unity, so mixing up invents
nothing: stereo into 5.1 fills the front pair and leaves the rest silent,
and mono into stereo puts the center on both sides at -3 dB, by the same rule
that carries any center into a front pair.

Some positions are beyond BS.775. They follow what ffmpeg does, measured
rather than read:

| Channel | Where it goes when the output lacks it |
|---|---|
| A side pair or a back pair | The other pair, whole when the input has only one of them, and at -3 dB for the second when it has both, as 7.1 into 5.1 |
| Back center | The two surrounds at -3 dB each, else the front pair at -6 dB each, else the center at -6 dB |
| Front left and right of center | The front pair, whole, else the center at -3 dB |
| Top front left and right | The front pair at -3 dB, else the center at -6 dB |

Every coefficient of these mixes agrees with ffmpeg's float mixing in the
oracle suite. Nothing is normalized or clipped, as in ffmpeg: a downmix of
loud channels can pass full scale, and `Pcm.clampInPlace` or a gain can
bring it back.

## What is refused

A conversion that has no defined answer throws
`UnsupportedFeatureException` rather than guessing at one: a channel of
unknown position, unless the layout is mixed into itself, a height channel
other than the top front pair, and a channel with nowhere to go, such as
stereo into a layout with only a low-frequency channel.

## Gain and mid-side

`Pcm.applyChannelGains` multiplies each channel by its own linear gain, in
place. `Pcm.stereoToMidSide` turns left and right into mid and side, half
their sum and half their difference, and `Pcm.midSideToStereo` turns them
back. The round trip is exact for audio of up to 23 bits, whose sums a float
holds exactly, and within float rounding otherwise.

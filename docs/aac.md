# Decode AAC

AAC-LC decodes from MP4, M4A and QuickTime tracks and from raw ADTS streams,
through the same API as every other coding.

```kotlin
val info = AudioKodec.probe(m4aBytes)
println(info.codec)                  // AAC
println(info.format.channelLayout)   // ChannelLayout(FRONT_LEFT, FRONT_RIGHT)

AudioKodec.open(m4aBytes).use { decoder ->
    decoder.seek(decoder.format.sampleRate * 30L)
    while (true) {
        val block = decoder.nextFrame() ?: break
        consume(block.samples, block.sampleCount)
    }
}
```

## What is decoded

The low complexity profile, object type 2, in full:

- every sampling rate the standard tabulates, from 7.35 to 96 kHz, and any
  other rate a configuration states, mapped to its tables as Table 4.82 says;
- one long window or eight short ones, with the sine and Kaiser-Bessel derived
  window shapes and every transition between them;
- mid/side stereo, intensity stereo in and out of phase, perceptual noise
  substitution, temporal noise shaping, and the pulse escape;
- channel configurations 1 to 7, reordered into AudioKodec's speaker order,
  and program configuration elements, whose channels come out in the order the
  stream carries them and are reported as unnamed speakers.

The decoder works in floating point, as the standard describes it, so a file
decodes to the same samples ffmpeg's decoder gives to within float rounding.
Noise substitution is the exception: the standard leaves its random numbers
open, so two decoders agree on the noise's energy in every band and not on
its samples. AudioKodec's generator restarts with every seek and every new
decoder, so a file always decodes to the same samples.

## Delay, padding and seeking

An AAC encoder starts its output one block late and pads the end to a whole
block. An MP4 edit list, or the iTunes `iTunSMPB` record when there is no
edit list, says how much of each to remove, and both are applied, so a
decoded file is as long as its source. ADTS has nowhere to say it, so a raw
stream decodes to every block it holds, 1024 frames each.

Every block's second half overlaps the next, so seeking decodes one block
before its target and throws that block away. That makes a seek
sample-exact: the samples after a seek are the ones a continuous decode
gives at the same position.

## Channel order

Configurations 1 to 7 name their speakers in ISO/IEC 14496-3, Table 1.19, and
the decoder reorders them into AudioKodec's order: configuration 6 carries
center, front pair, back pair and LFE, and comes out as front left, front right,
center, LFE, back left and back right.

Configuration 7 carries center, the left and right center pair, the outside
front pair, the surround pair and the LFE. ffmpeg 6.1 decodes it as plain 7.1,
with the first pair at the front and the second at the sides, which disagrees
with the standard; AudioKodec follows the standard, which is also how Apple's
AAC 7.1 layouts read.

## What is refused

Each of these throws `UnsupportedFeatureException`, whose `feature` names it:

| Feature | Why |
|---|---|
| HE-AAC and HE-AAC v2 | Spectral band replication rebuilds the upper half of the spectrum from the lower. Decoding only the AAC-LC core would give muffled audio at half the sample rate, with nothing in the samples to say so. It is refused when the configuration signals it, in either of its two forms, and when the first block carries its data without saying so |
| AAC Main, AAC SSR, AAC LTP | Other object types, each with a tool the low complexity profile lacks |
| AAC with 960-sample frames | The frame length used by digital radio |
| AAC coupling channel elements | A channel mixed into others; no common encoder writes one |

`probe` still reports these streams. For HE-AAC signaled in an MP4
configuration it reports the output rate a full decoder would give.

ADIF, the single-header framing with no frame lengths, is probed and not
decoded.

## Damage

A block whose data does not parse, uses a reserved value, or carries a
different number of channels from the stream throws `CorruptStreamException`
at the packet's offset, or in tolerant mode ends the stream after the last
good block. Noise energies and intensity positions are held to the range
scalefactors reach, so damaged data cannot produce samples that are not
numbers.

# Decode AMR

AMR narrowband and AMR wideband decode through the same API as every other
format, from `.amr` and `.awb` files and from the 3GP and MP4 files phones
record voice memos and MMS attachments in.

```kotlin
val info = AudioKodec.probe(amrBytes)
println(info.container)       // AMR_NB, AMR_WB, or MP4 for a .3gp file
println(info.format)          // 8 kHz for AMR, 16 kHz for AMR-WB

AudioKodec.open(amrBytes).use { decoder ->
    while (true) {
        val block = decoder.nextFrame() ?: break
        play(block.samples, block.frameCount)
    }
}
```

## What is decoded

- AMR narrowband at all eight of its rates, from 4.75 to 12.2 kbit/s, with
  160 samples at 8 kHz in every 20 ms frame.
- AMR wideband at all nine of its rates, from 6.6 to 23.85 kbit/s, with 320
  samples at 16 kHz in every frame, including the band above 6.4 kHz that
  the decoder fills with shaped noise.
- Discontinuous transmission: silence descriptor frames, from which the
  decoder makes comfort noise, and frames that carry no data, which it fills
  in from the frames before them.
- The AMR-WB decoder homing frames of 3GPP TS 26.173, which reset the decoder.
- The storage format of RFC 4867 section 5, single-channel and multichannel,
  and AMR in MP4 and 3GP (`samr` and `sawb` sample entries).

The decoders are translations of opencore-amr 0.1.6, which ships the 3GPP
reference decoders' fixed-point arithmetic under the Apache License 2.0, and
keep that arithmetic. Every sample is the 16-bit integer opencore-amr gives,
on every target, divided by 32768. As in opencore-amr, AMR samples keep 13
significant bits and AMR-WB samples 14.

## Multichannel files

A multichannel AMR file stores one frame per channel for each 20 ms, in the
speaker order of RFC 3551 section 4.1. AudioKodec reorders them into its own
channel order: a four-channel file's left, center, right and surround come out
as front left, front right, front center and back center, and a six-channel
file's six as front left, front right, front center, front left of center,
front right of center and back center. A file of more than six channels, which
RFC 3551 gives no speakers for, comes out in file order with unknown speakers.

## Seeking

AMR's decoders carry their filters, gain predictors and comfort noise from
frame to frame. Seeking decodes from 25 frames, half a second, before the
target and discards them, so the samples after a seek converge on a
continuous decode rather than equalling it; a seek to the start is exact.

## Damaged and reserved frames

The storage format marks a frame's quality with a bit in its header. Like
opencore-amr, and like ffmpeg's wrapper of it, AudioKodec does not consult
it: every frame with speech bits is decoded. A frame of type 15, which
carries no data, is concealed, as is an AMR-WB frame of type 14, a lost
frame.

| Case | Result |
|---|---|
| A frame type the storage format reserves, or a narrowband frame of another system's comfort noise, which opencore-amr does not decode | `CorruptStreamException`, or in tolerant mode the end of the stream |
| A file that ends part way through a frame, or a multichannel file part way through a block | `TruncatedStreamException`, or in tolerant mode the end of the stream |
| A multichannel file that states no channels | `CorruptStreamException` |

AMR frames carry no checksum, so damage inside a frame that still decodes is
not detected, by AudioKodec or by the reference.

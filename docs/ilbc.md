# Decode iLBC

iLBC, the Internet Low Bitrate Codec, is the speech coding of many older
voice over IP systems. It codes 8 kHz speech in frames of 20 ms, 38 bytes
each, or 30 ms, 50 bytes each, and every frame can be decoded without the
one before it, which is what made it robust to lost packets.

```kotlin
val info = AudioKodec.probe(lbcBytes)
println(info.container)   // ILBC, or CAF or MP4
println(info.codec)       // ILBC

val samples = AudioKodec.decode(lbcBytes).samples   // 8 kHz, one channel
```

## Where it is read

| Container | How the frame size is known |
|---|---|
| The storage format of RFC 3952, `.lbc` | The magic number, `#!iLBC20` or `#!iLBC30` and a line feed |
| CAF, coding `ilbc` | The packet size, 38 or 50 bytes |
| QuickTime and MP4, sample entry `ilbc` | Each sample's length, as WebRTC's own decode call decides it |

ffmpeg writes CAF files that state 160 frames a packet for 30 ms frames as
well as for 20 ms ones, which its own reader then takes at face value; the
packet size is the reliable part, so AudioKodec goes by it. A QuickTime
sample of one, two or three frames of the size in use is decoded as such;
one of the other size starts the decoder afresh in that mode, as WebRTC
does.

## What is decoded

The decoder is a translation of WebRTC's fixed-point iLBC decoder, as
libilbc 3.0.4 packages it under its BSD license, with the enhancer that
WebRTC turns on. Every sample is exactly the 16-bit integer WebRTC gives,
on every target, divided by 32768.

A frame whose last bit is set is one the sender marked empty, and a frame
whose start state index is impossible is damaged; WebRTC conceals both from
the frames before them, repeating the pitch of the last good frame mixed
with noise, and AudioKodec does exactly the same. Concealment of frames that
are missing altogether is not offered: no container here can say a frame is
missing.

## Seeking

Each frame's coded parameters stand alone, but the synthesis filter, the
high-pass output filter and the enhancer carry state from frame to frame. A
decoder started part way through settles to within one step of a continuous
one after a few frames, but not onto it. So a seek decodes from the start of
the stream, and lands on exactly the samples a continuous decode gives; in a
storage format or CAF file, a seek forward from the current position decodes
onward instead. With `DecodeOptions(allowSlowSeek = false)`, a seek in such a
file that would have to decode from the start throws
`UnsupportedFeatureException`.

## Damaged files

| Case | Result |
|---|---|
| A 20 ms frame whose codebook index for the vector next to the start state lies past every codebook section, which WebRTC refuses | `CorruptStreamException` at that frame, or in tolerant mode the end of the stream |
| A file that ends part way through a frame | `TruncatedStreamException`, or in tolerant mode the end of the stream |
| A CAF packet size that is neither 38 nor 50 bytes, or iLBC stated in more than one channel | `CorruptStreamException` or `UnsupportedFeatureException` |
| A QuickTime sample that is not one to three frames of either size | `CorruptStreamException` |

In the 30 ms mode every codebook index lies inside a section, so no 30 ms
frame is refused. Like the reference, AudioKodec cannot tell other damage
inside a frame from speech, and decodes it.

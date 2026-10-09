# Decode Speex

Speex inside Ogg decodes through the same API as every other format.

```kotlin
val info = AudioKodec.probe(spxBytes)
println(info.container)       // OGG
println(info.codec)           // SPEEX
println(info.durationFrames)  // the samples speexdec writes for this file

AudioKodec.open(spxBytes).use { decoder ->
    while (true) {
        val block = decoder.nextFrame() ?: break
        play(block.samples, block.frameCount)
    }
}
```

## What is decoded

The bitstream every Speex release since 1.0 writes, version 4:

- narrowband at 8 kHz, wideband at 16 kHz and ultra-wideband at 32 kHz;
- every quality, constant and variable bitrate, average bitrate, and voice
  activity detection with discontinuous transmission, where silent stretches
  are sent as comfort noise;
- several frames to a packet;
- mono, and stereo coded as a mono signal with in-band stereo parameters.

The decoder is a translation of libspeex 1.2.1, the reference library, and
decodes as `speexdec` does: with the perceptual enhancer on, and with the
in-band stereo parameters applied when the header says two channels. It works
in the floating-point build's single-precision arithmetic in the same order,
so on the JVM every sample is the float libspeex gives, divided by 32768.
JavaScript keeps floats in double precision, and Speex runs its samples
through recursive filters, so there samples can differ from the reference's
by up to 1e-3 of full scale.

## Length

The Ogg granule positions say where a Speex stream ends, but not quite where
it starts: the first page's packets decode to more samples than its granule
position counts, and the difference is the encoder's delay. AudioKodec trims
the start and the end exactly as `speexdec` does, so the samples and
`AudioInfo.durationFrames` are those `speexdec` writes for the same file.

That includes two of its habits. A stream whose audio fits on one Ogg page is
not trimmed at the start, and runs on for the decoder's own delay past the
final granule position. And a stream ends where its frames run out, which can
be a little short of the length the granule position asks for, because the
encoder's last frame covers only its own delay.

## Seeking

Speex carries its filters and its excitation history from frame to frame, so
no frame decodes on its own. Seeking restarts the decoder 25 frames before
the target and discards them. The samples after a seek converge on those of a
continuous decode within that run-up rather than equalling them; a seek to
the start is exact.

## Tags

The Speex comment header is read and rewritten like any other Ogg file's,
through `AudioKodec.readTags` and the tag writers.

## What is refused

| Case | Result |
|---|---|
| A header that states a bitstream version other than 4, or a header version above 1 | `UnsupportedFeatureException` naming "Speex bitstream version" and the number |
| A header that names a mode other than narrowband, wideband or ultra-wideband | `CorruptStreamException` |
| A frame that states a mode that does not exist, or reads past the end of its packet | `CorruptStreamException`, or in tolerant mode the end of the stream |
| A stream that ends before the samples its granule positions promise | `TruncatedStreamException`, or in tolerant mode the end of the stream |

Speex packets carry no checksum of their own; the Ogg page checksum is what
catches damage. Packet loss concealment, which libspeex runs when a caller
reports a missing packet, is not used: a missing page in an Ogg file is
handled as for every other Ogg mapping.

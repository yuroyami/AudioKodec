# Encoding

AudioKodec writes FLAC, WAV, AIFF, AU and Apple Lossless in an `.m4a` file. An encoder
takes Float32 samples with an `AudioFormat`, a `DecodedAudio`, or any `PcmSource`, and
writes to a byte array or to a sink.

## Write a file

```kotlin
--8<-- "GuideSamples.kt:encode-wav"
```

To convert one decoded value into several formats, reuse it directly:

```kotlin
--8<-- "GuideSamples.kt:encode-decoded"
```

Each encoding reads the complete `DecodedAudio.samples` array from the
beginning through an independent reader. It preserves the cursor used by
direct `DecodedAudio.read` calls. Use `decoded.rewind()` to reset that direct
cursor, including after reaching the end. No rewind is needed for encoding
or for retrying after an encoding fails.

Other `PcmSource` implementations are consumed through their own cursor.
Create a fresh source for each encoding operation. The sample array is not
copied, so avoid changing it while an encoding runs.

The targets available are FLAC, integer PCM and IEEE float in WAV, AIFF and AU,
A-law and mu-law in all three, IMA or Microsoft ADPCM in WAV, and Apple
Lossless in an `.m4a` file.

```kotlin
--8<-- "GuideSamples.kt:encode-alac"
```

ALAC is written at 16, 20, 24 or 32 bits with Apple's 4096-frame packets, and
the packets are the ones Apple's reference encoder writes for the same
samples. ALAC stores up to eight channels in its own speaker order: one, two,
three, five and six channels in their canonical layouts map onto it, and four,
seven or eight channels need a `ChannelLayout` naming ALAC's speakers, center
first. Any other layout is refused rather than played from the wrong speakers.
The `moov` box follows the audio, so the sink must support patching; byte
arrays and files do. Tags and cover art go into the iTunes item list, and
chapters into a Nero `chpl` list, as [MP4 metadata](mp4-tags.md) describes.

Reducing the depth of a float or a deeper integer source is where dither
belongs. It replaces the distortion of plain rounding with a small amount of
noise, which is what you want at 16 bits and below:

```kotlin
--8<-- "GuideSamples.kt:encode-dither"
```

Files above 4 GiB are written as RF64 automatically. AudioKodec reserves the
space for the larger size fields in every WAV it writes, and uses it when the
file turns out to need it.

RF64 and BW64 size tables match repeated chunk IDs in their stored order.
Tag edits preserve the surviving table entries in that order, together with
the original opaque chunks and audio bytes. Table entries and their lookup
storage count towards `DecodeOptions.maxAllocationBytes`.

## Channel layouts

The channel layout of the source is never silently relabeled. WAV PCM and float keep
any positional layout that follows the speaker-bit order of WAV, including custom mono
and stereo masks. AIFF and AU store only a channel count, compressed WAV has no mask in
this writer, and FLAC fixes the layout for each channel count. Encoding refuses a layout
that the target cannot represent before it writes any bytes.

## Tags and artwork

Pass a `TagBundle` to `encode` and the tags go into the file with the audio. WAV and
AIFF use their native text chunks, plus ID3v2.4 for Unicode and richer fields. FLAC
uses Vorbis comments and native `PICTURE` blocks, whose limits [Tags](tags.md#pictures)
describes. AU has no structured tag format, so it refuses a bundle that is not empty
instead of dropping it.

## FLAC

```kotlin
--8<-- "GuideSamples.kt:encode-flac"
```

`effort` runs from 0 to 8. It trades encoding time against size, as the numbered
levels of the reference encoder do, and it never changes the decoded audio, because
the format is lossless at every setting. See [Formats](formats.md#flac) for
verification and seeking.

## Exact integers

`IntPcmSource` and an integer-array overload of `encode` take the stored integers of a
lossless decode without a trip through Float32. See [Decoding](decoding.md#precision-and-lossless-round-trips)
and [integer PCM encoding](integer-pcm.md).

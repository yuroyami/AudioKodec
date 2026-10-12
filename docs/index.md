# AudioKodec

Audio containers, codecs and tags written in Kotlin for Kotlin Multiplatform. AudioKodec decodes
22 containers to Float32 PCM, writes FLAC, WAV, AIFF, AU and Apple Lossless, and edits tags
without touching the audio. The same code runs on every target.

## Install

0.1.0 is not published to Maven Central yet. These are the coordinates it will use:

```kotlin
dependencies {
    implementation("io.github.yuroyami:audiokodec:0.1.0")
    // Optional, for files through kotlinx-io:
    implementation("io.github.yuroyami:audiokodec-io:0.1.0")
}
```

The JVM artifacts need Java 21 or newer.

## Quick start

```kotlin
val audio = AudioKodec.decode(bytes)
println("${audio.format.sampleRate} Hz, ${audio.format.channels} channels")

val tags = AudioKodec.readTags(bytes)
println(tags[TagField.TITLE])
```

## Guides

- [Decoding](decoding.md): decode a file, read a header, stream and seek, handle bad input
  and play the result.
- [Encoding](encoding.md): write FLAC, WAV, AIFF, AU and Apple Lossless, with tags and artwork.
- [Exact integer PCM](integer-pcm.md): encode integer samples without a round trip through
  floats.
- [Tags](tags.md): read and edit every tag format, with pictures, chapters and loudness values.
- [MP4 metadata](mp4-tags.md) and [Broadcast Wave metadata](broadcast-wave.md): the details of
  those two carriers.
- [Files and kotlinx-io](files.md) and [Seekable file input](file-input.md): read from and write
  to files with the `audiokodec-io` module.

## Processing

- [Change the sample rate](resample.md)
- [Mix channels](mix.md)
- [Measure loudness](loudness.md)
- [Draw a waveform](waveform.md)
- [Levels, fades and test signals](levels.md)

## Formats

[Formats](formats.md) lists what AudioKodec reads and writes and describes each container
and coding. [Format support](support.md) has the full support tables and how each format is
checked. Several codings have their own page:
[AAC](aac.md), [AC-3](ac3.md), [AMR](amr.md), [DSD](dsd.md),
[G.722 and G.726](g722-g726.md), [GSM 06.10](gsm.md), [iLBC](ilbc.md),
[Matroska and WebM](matroska.md), [Monkey's Audio](ape.md),
[MPEG audio in MP4](mpeg-in-mp4.md), [free-format MPEG audio](mpeg-free-format.md),
[Musepack](musepack.md), [chained Ogg](ogg-chains.md), [Speex](speex.md),
[True Audio](tta.md), [Vorbis floor zero](vorbis-floor-zero.md) and [WavPack](wavpack.md).

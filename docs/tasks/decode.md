# Decode a file to PCM

**Depends on** `io.github.yuroyami:audiokodec:0.1.0`. See [Install](../index.md#install).

Give AudioKodec the bytes of an audio file and it gives back the samples, whatever the
format: WAV, FLAC, MP3, AAC, Ogg Vorbis, Opus and the [rest](../formats.md).

```kotlin
--8<-- "DecodeSample.kt:imports"

--8<-- "DecodeSample.kt:decode"
```

The samples are 32-bit floats, interleaved: frame 0 holds one sample for each channel,
then frame 1, and so on. Full scale is -1.0 to 1.0. A lossy file can go slightly past that,
and AudioKodec leaves it there; [`Pcm.intFromFloat`](play-audio.md) clamps when you convert
to integers.

`decode` keeps the whole file in memory. That is fine for a song and wrong for an hour of
audio, which is over a gigabyte as floats. For anything long, [stream it](stream-and-seek.md).

## Read the header first

`probe` reads the format, length and coding without decoding any audio:

```kotlin
--8<-- "DecodeSample.kt:probe"
```

## When it fails

Every failure caused by the file is an `AudioKodecException`. The most common one,
`UnsupportedFormatException`, means the format is one AudioKodec can name but not decode,
such as DTS, or not audio at all.

```kotlin
--8<-- "DecodeSample.kt:describe"
```

## Next

- A file on disk: [Use kotlinx-io](kotlinx-io.md).
- Hear it: [Play decoded audio](play-audio.md).
- Everything else about decoding: the [Decoding guide](../decoding.md).

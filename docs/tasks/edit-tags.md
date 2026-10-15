# Edit tags without touching the audio

**Depends on** `io.github.yuroyami:audiokodec:0.1.0`. See [Install](../index.md#install).

`writeTags` writes a new copy of a file with new tags and the audio copied across byte for
byte. Nothing is decoded or re-encoded, so it is safe on MP3, AAC and every other lossy
format.

```kotlin
--8<-- "EditTagsSample.kt:imports"

--8<-- "EditTagsSample.kt:edit"
```

- `edit { }` starts from the tags the file has, so everything you do not change is kept,
  including entries AudioKodec has no `TagField` for.
- `set` replaces every value a field had; `add` appends one; `remove` drops the field.
- The documentation tests check that the audio bytes of FLAC, WAV, M4A, MP3 and Ogg Vorbis
  files are identical before and after the edit.

Tags are written as ID3v2 in MP3, as Vorbis comments in FLAC and Ogg, as items in MP4 and
M4A, and into the metadata chunks of WAV and AIFF. An ID3v1 block is kept up to date where
the file already has one. Matroska tags are read only. The
[Tags guide](../tags.md#which-tag-formats) lists every format.

## Write the file safely

`writeTags` returns a new file rather than changing one in place. Write it to a temporary
file and move that over the original, so that a crash halfway through leaves the original
intact. With `audiokodec-io`, `writeAudioAtomically` does exactly that:

```kotlin
--8<-- "KotlinxIoSample.kt:retitle"
```

The imports are on [Use kotlinx-io](kotlinx-io.md).

## Options

`TagWriteOptions` chooses the ID3 version, the padding left for the next edit, and whether
an ID3v1 block is kept up to date:

```kotlin
--8<-- "EditTagsSample.kt:options"
```

## Next

- Pictures: [Read and write cover art](cover-art.md).
- Dates, chapters, loudness values and per-format details: the [Tags guide](../tags.md).

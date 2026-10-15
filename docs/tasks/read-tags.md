# Read tags

**Depends on** `io.github.yuroyami:audiokodec:0.1.0`. See [Install](../index.md#install).

`readTags` returns the title, artist, album and every other field a file states, from
whichever tag format it uses: ID3 in MP3, WAV and AIFF, Vorbis comments in FLAC and Ogg, MP4
items in M4A, APE tags, Matroska tags and the native chunks of WAV and AIFF.

```kotlin
--8<-- "ReadTagsSample.kt:imports"

--8<-- "ReadTagsSample.kt:read"
```

- A field can hold several values, such as two artists. `tags[field]` gives the first and
  `tags.all(field)` gives them all.
- `TagField` names the fields in one vocabulary for every format, so `TagField.ARTIST` is
  `TPE1` in ID3, `ARTIST` in a Vorbis comment and `©ART` in MP4.
- Reading tags decodes no audio, so it works on files whose codec AudioKodec cannot decode.
- A file with no tags gives an empty bundle, not an exception.

## The blocks as stored

A file can carry several tag blocks, such as ID3v2 at the front and ID3v1 at the end.
`tags.fields` merges them, the richer format winning where they disagree. `tags.raw` keeps
every block exactly as it was stored, including entries AudioKodec has no `TagField` for:

```kotlin
--8<-- "ReadTagsSample.kt:raw"
```

## Next

- Change them: [Edit tags](edit-tags.md).
- Show the cover: [Read and write cover art](cover-art.md).
- Precedence, dates, chapters, loudness values and every format's details: the
  [Tags guide](../tags.md).

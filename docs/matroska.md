# Read Matroska and WebM

Matroska (`.mka`, `.mkv`) and its WebM subset (`.webm`) are EBML documents:
a tree of elements, each an ID and a size written as variable-length
integers. AudioKodec reads the tree from RFC 9559 and RFC 8794, finds the
first audio track, and decodes it through the decoder its codec ID names.

```kotlin
val info = AudioKodec.probe(mkaBytes)
println(info.container)              // MATROSKA, for WebM too
println(info.codec)                  // OPUS, VORBIS, FLAC, MP3, AAC, ...

val decoded = AudioKodec.decode(mkaBytes)
val tags = AudioKodec.readTags(mkaBytes)
println(tags[TagField.TITLE])
println(tags.chapters)
```

## Codings

| Codec ID | Decoded as |
|---|---|
| `A_OPUS` | Opus, from its `OpusHead` in CodecPrivate, any channel mapping |
| `A_VORBIS` | Vorbis, from the three Xiph-laced headers in CodecPrivate |
| `A_FLAC` | FLAC, from the `fLaC` stream and STREAMINFO in CodecPrivate |
| `A_MPEG/L1`, `A_MPEG/L2`, `A_MPEG/L3` | MPEG audio, each frame counted from its own header |
| `A_AAC`, `A_AAC/...` | AAC-LC, from the AudioSpecificConfig in CodecPrivate. Spectral band replication is refused by name, as everywhere |
| `A_PCM/INT/LIT`, `A_PCM/INT/BIG`, `A_PCM/FLOAT/IEEE` | Linear PCM at the track's BitDepth, channels in WAV's default order |
| `A_AC3` | AC-3, each syncframe counted as 1536 samples per channel. See [AC-3](ac3.md) |
| `A_EAC3` | Named as Enhanced AC-3 and refused |

Any other codec ID is reported as such, and `open` refuses it by name;
`probe`, tags and chapters still work.

## The timeline

Matroska times each Block in TimestampScale units, a millisecond by default,
which cannot place a sample. So AudioKodec builds the timeline from the
frames themselves: each frame's sample count comes from its own header, the
way each codec's own container would count it, and the totals give the exact
length and the index that seeking uses.

Two elements trim the ends, which is how Matroska carries Opus's pre-skip and
end trim:

- the track's CodecDelay, in nanoseconds, is taken from the start;
- the last Block's DiscardPadding, in nanoseconds, is taken from the end.

Matroska has no field for any other encoder delay. An MP3 or AAC stream
copied in from its own container keeps the priming and padding its old
container trimmed, and ffmpeg plays those frames from the `.mka` too.
AudioKodec does the same, so its length matches ffmpeg's decode of the same
file, and the audio of the original appears in it whole, sample for sample.

The segment's Duration is used only when no frame can be counted, for a
coding AudioKodec names but cannot decode.

## Structure

- Elements of unknown size, as live writers leave Segments and Clusters,
  end at the first element that cannot be their child.
- SimpleBlocks and Blocks inside BlockGroups, with all three lacing schemes:
  Xiph, EBML and fixed-size.
- CRC-32 and Void elements are skipped.
- Content encodings: zlib compression (algorithm 0), undone by AudioKodec's
  own inflate written from RFC 1950 and RFC 1951, and header stripping
  (algorithm 3), whose bytes are put back before each frame. Encrypted
  tracks and the other compression algorithms are refused by name.
- Only the first audio track is read.

## Tags and chapters

Tags are read from the Tags element at segment level and those targeted at
the audio track, and mapped through the names of the Matroska tagging
specification over the Vorbis comment names they mostly share:
`LEAD_PERFORMER`, `ALBUM_ARTIST`, `PART_NUMBER`, `TOTAL_PARTS`,
`DATE_RELEASED`, `DATE_RECORDED` and the rest. The segment's Info Title
becomes `TITLE` when no tag gives one. Writing Matroska tags is not
supported.

Chapters come from the first edition: each ChapterAtom's start, end and
first display string. Hidden chapters are skipped. A chapter without an end
ends where the next begins, or at the end of the segment.

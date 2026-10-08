# Broadcast Wave metadata

WAV, RF64 and BW64 can carry a `bext` chunk. Select `TagFormat.BWF_BEXT`
explicitly to add or change it. Ordinary tag edits preserve the entire existing
chunk. Audio samples and their encoded payload are copied unchanged.

Broadcast fields live in `TagBundle.raw`, separately from the merged music
fields. A block contains these text entries:

| Key | Stored value |
|---|---|
| `Description` | ASCII, at most 256 bytes |
| `Originator` | ASCII, at most 32 bytes |
| `OriginatorReference` | ASCII, at most 32 bytes |
| `OriginationDate` | ASCII, at most 10 bytes, usually `YYYY-MM-DD` |
| `OriginationTime` | ASCII, at most 8 bytes, usually `HH:MM:SS` |
| `TimeReference` | Unsigned 64-bit decimal sample count since midnight |
| `Version` | `0`, `1` or `2` |
| `CodingHistory` | ASCII lines with CRLF endings |

Fixed text is truncated to its field width. Shorter values have zero padding;
spaces within the retained text survive. Unicode and embedded nulls in the
stored text are refused. A final CRLF is added to non-empty coding history
when needed; other line breaks must already be CRLF. Missing time reference
and version default to zero in a new block. Version 2 uses the specified
unknown-loudness sentinel when no stored extension is supplied.

```kotlin
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.tag.*

val values = mapOf(
    "Description" to "Interview recording",
    "Originator" to "Station",
    "OriginatorReference" to "GB-STATION-20261008-001",
    "OriginationDate" to "2026-10-08",
    "OriginationTime" to "12:34:56",
    "TimeReference" to "4294967297",
    "CodingHistory" to "A=PCM,F=48000,W=24,M=stereo\r\n",
)
val broadcast = RawTagBlock(
    TagFormat.BWF_BEXT, offset = 0, size = 0,
    entries = values.map { RawTagEntry(it.key, text = it.value) },
)
val tags = TagBundle(emptyMap(), raw = RawTags(listOf(broadcast)))
val rewritten = AudioKodec.writeTags(
    originalWav, tags,
    TagWriteOptions(formats = setOf(TagFormat.BWF_BEXT)),
)
```

For an existing chunk, keep its raw entries and replace the field to edit:

```kotlin
val original = AudioKodec.readTags(originalWav)
val block = original.raw.blocksOf(TagFormat.BWF_BEXT).single()
val entries = block.entries.filter { it.key != "Description" } +
    RawTagEntry("Description", text = "Corrected description")
val updatedBlock = RawTagBlock(TagFormat.BWF_BEXT, 0, 0, entries)
val updated = TagBundle(
    original.fields, original.pictures, original.chapters, original.replayGain,
    RawTags(original.raw.blocks.filter { it.format != TagFormat.BWF_BEXT } + updatedBlock),
)
val result = AudioKodec.writeTags(
    originalWav, updated,
    TagWriteOptions(formats = setOf(TagFormat.BWF_BEXT, TagFormat.RIFF_INFO, TagFormat.ID3V2_4)),
)
```

The reader supplies an `Extension` binary entry of 264 bytes. Keep it to retain
the stored time reference, version, UMID, loudness and reserved bytes. Explicit
time-reference text replaces its eight bytes. Retain the extension and version
together; changing a stored version requires a new block with appropriate
extension data. Multiple chunks or duplicate field entries are refused during
an explicit rewrite. Unsupported versions can remain untouched in ordinary
edits.

Select `RIFF_INFO` or ID3 alongside `BWF_BEXT` when the bundle also carries music
fields, pictures, chapters or ReplayGain; BWF-only output refuses metadata it
cannot retain. To remove an existing Broadcast Wave chunk, select `BWF_BEXT`
and supply a bundle with no BWF raw block. Other format selections preserve
it, including an empty selection. Validation happens before output is written.

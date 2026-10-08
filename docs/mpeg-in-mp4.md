# Decode MPEG audio inside MP4

Use the same API as for an MP3 file. MP4, M4A and QuickTime tracks carrying
MPEG Layers I, II or III decode to interleaved Float32 samples.

```kotlin
val info = AudioKodec.probe(mp4Bytes)
println(info.codec) // MP3, MP2 or MP1, from the first MPEG frame

AudioKodec.open(mp4Bytes).use { decoder ->
    decoder.seek(decoder.format.sampleRate * 30L)
    while (true) {
        val block = decoder.nextFrame() ?: break
        consume(block.samples, block.sampleCount)
    }
}
```

The reader accepts a `.mp3` sample entry and an `mp4a` entry whose `esds`
object type is `0x69` or `0x6B`. Each indexed sample must contain one complete
MPEG frame. MPEG-1, MPEG-2 and the MPEG-2.5 extension use the same frame decoder
as bare MPEG files. Bitrate, padding and stereo mode may change between
packets; the layer, version, rate and channel count must stay consistent.
Free-format audio is currently named and refused.

Ordinary and fragmented MP4 files use their sample tables for seeking. MP3
opening and seeking decode up to ten preceding MPEG frames to restore the
bit reservoir and transform history. Layers I and II decode two preceding
frames to restore their synthesis history. Output before the requested
position is discarded, including preroll packets whose reservoir reaches
before the landing. Missing reservoir data inside the playable recording
is corruption. `allowSlowSeek = false` still permits these indexed seeks.

The edit list supplies the start trim and playable duration. AudioKodec does
not apply a second LAME delay or an implicit 529-sample decoder trim to an MP4
track. Without an edit list, its entire coded timeline plays. A file whose
edit list preserves the original MP3's gapless limits decodes to the same
samples as that MP3.

FFmpeg's `-c:a copy` mux preserves an MP3's start skip but can discard its final
discard-padding flag. Such an MP4 includes the additional coded tail in its
timeline, and its duration and output can be longer than the original MP3.
AudioKodec follows the MP4's stored limits. An end trim cannot be recovered
from a gapless header that the muxer removed.

Strict mode validates protected MPEG frame CRCs and rejects damaged packets.
Tolerant mode ignores CRC mismatches, as bare MPEG decoding does, and retains
the complete audible prefix when a later packet is corrupt or truncated.
It refuses damage before any audible output. The byte offset in a packet
error refers to the packet's position in the MP4 file.

The JVM oracle copies FFmpeg-encoded MPEG packets into MP4 and compares them
with FFmpeg's float decoders. MP3 and Layer I use a maximum sample difference
of `1e-4`; Layer II uses `1e-5`. All three require RMS error below `1e-5`.
Exact end edits are checked with FFprobe; FFmpeg's sample comparison is bounded
at that end because its decoder can emit the entire final coded packet.
Common tests require exact samples across bare and indexed decoding, repeated
seeks, edited starts and reservoir preroll. MP4 MPEG also participates in the
shared truncation, byte mutation and random-body sweeps.

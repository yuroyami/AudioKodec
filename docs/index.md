# AudioKodec

Audio containers, codecs and tags written in Kotlin for Kotlin Multiplatform.
Read and write FLAC, WAV, AIFF and AU; decode FLAC, Vorbis, every MPEG audio
layer and the uncompressed family to Float32 PCM; edit tags without re-encoding
the audio; and run the same code on every target.

This release covers MPEG Layers I, II and III, FLAC (natively and inside Ogg),
Vorbis, the uncompressed family, the Ogg container and tags. The
[format status](https://github.com/yuroyami/AudioKodec/blob/main/PORTING_STATUS.md)
page is the full list of what works today and what is coming.

## Install

```kotlin
dependencies {
    implementation("io.github.yuroyami:audiokodec:0.1.0")
    // Optional, for reading and writing through kotlinx-io:
    implementation("io.github.yuroyami:audiokodec-io:0.1.0")
}
```

## Decode a file

```kotlin
val audio = AudioKodec.decode(bytes)
println("${audio.format.sampleRate} Hz, ${audio.format.channels} channels")
println("${audio.durationSeconds} seconds")
```

`audio.samples` is interleaved Float32, nominally between -1.0 and 1.0. A
lossy coding can go slightly outside that range, and AudioKodec does not clamp
it, because clamping belongs at the point where samples become integers again.

## Read a header without decoding

```kotlin
val info = AudioKodec.probe(bytes)
println(info.container.displayName)
println(info.codec.displayName)
println(info.durationSeconds)
```

`probe` reads headers only. Scanning a folder of music costs a few kilobytes
of reads per file rather than a full decode of each one.

AIFF-C QuickTime IMA (`ima4`) stores its length as groups of compressed
packets. AudioKodec reports the decoded length, with 64 frames per group.
Both Apple's and ffmpeg's files are accepted: IMA sample widths may be 0, 4
or 16, and A-law and mu-law widths may be 8 or 16. A short IMA payload still
raises `TruncatedStreamException` in strict mode; tolerant mode returns only
the complete packet groups available.

## Stream and seek

Whole-file decoding allocates the whole file, so use the streaming decoder for
anything long. Seeking is sample accurate: the decoder lands on the frame you
asked for, not on the block containing it.

```kotlin
AudioKodec.open(source).use { decoder ->
    decoder.seek(decoder.durationFrames!! / 2)
    while (true) {
        val block = decoder.nextFrame() ?: break
        play(block.samples, block.frameCount)
    }
}
```

To read it as a `Flow`:

```kotlin
fun AudioDecoder.frames(): Flow<AudioFrame> = flow {
    use { while (true) emit(it.nextFrame() ?: break) }
}
```

Nothing in the decoder suspends, so it also works on a plain thread and on
targets that have neither threads nor coroutines.

### Buffer reuse

`DecodeOptions(reuseBuffers = true)` makes the decoder hand back the same
array every time, which removes almost all allocation from a decode loop. Each
returned block then stays valid only until the next call. Copy anything you
keep.

## Write a file

```kotlin
val wav = AudioKodec.encode(samples, AudioFormat(44_100, 2), EncodeTarget.wav(16))
```

The targets available are FLAC, integer PCM and IEEE float in WAV, AIFF and AU,
A-law and mu-law in all three, and IMA or Microsoft ADPCM in WAV.

Reducing the depth of a float or a deeper integer source is where dither
belongs. It replaces the distortion of plain rounding with a small amount of
noise, which is what you want at 16 bits and below:

```kotlin
AudioKodec.encode(samples, format, EncodeTarget.wav(16, dither = Dither.Tpdf()))
```

Files above 4 GiB are written as RF64 automatically. AudioKodec reserves the
space for the larger size fields in every WAV it writes, and uses it when the
file turns out to need it.

## Precision and lossless round trips

Decoding produces Float32, which carries 8-, 16- and 24-bit audio exactly. It
cannot carry 32-bit integers exactly, because a Float32 has 24 bits of
mantissa. Decoders for lossless codings therefore also implement
`IntPcmAccess`:

```kotlin
AudioKodec.open(bytes).use { decoder ->
    if (decoder is IntPcmAccess) {
        val block = decoder.nextIntFrame()   // exactly what the file holds
    }
}
```

Use one or the other on a given decoder, not both. They advance the same
cursor.

## FLAC

FLAC decodes bit-exactly, and encodes to files the reference decoder accepts.

```kotlin
val flac = AudioKodec.encode(samples, format, EncodeTarget.flac(bitDepth = 16, effort = 5))
```

`effort` runs from 0 to 8. It changes how hard the encoder looks for a good
predictor and how finely it partitions the residual, trading time against size
the way the reference encoder's numbered levels do. It never changes what comes
back out: the format is lossless at every setting.

### Verifying a file

Every FLAC file carries an MD5 of its own unencoded audio, written by whichever
program encoded it. That makes a decoder checkable against a third party:

```kotlin
when (AudioKodec.verifyFlacMd5(bytes)) {
    true -> println("the audio matches the hash the encoder stored")
    false -> println("this file has been damaged")
    null -> println("this file states no hash")
}
```

It costs a full decode, so it belongs in a library-integrity pass rather than
in front of every playback.

### Seeking

FLAC frames vary in size, so a position cannot be turned into a byte offset by
multiplying. AudioKodec uses the seek table when the file has one and scans frame
headers when it does not. Both checksums in every frame are verified during that
scan, so a byte sequence that happens to look like a frame sync is rejected
rather than decoded as audio.

Set `DecodeOptions(allowSlowSeek = false)` to make a seek that would need a scan
throw instead of taking its time.

### Editing a FLAC file's tags

Editing rewrites the metadata blocks and copies the audio frames across
untouched. Seek tables, cue sheets and application blocks the file carried are
kept as they were, and padding is regenerated so the next edit need not move the
audio again.

The stream MD5 still matching after an edit is the proof that the audio was not
touched, and it is what the tests check.

## Ogg

Ogg is a container, not a codec, so what a file holds depends on the mapping
inside it. AudioKodec reads the container in full: pages and their checksums,
packets reassembled across page boundaries, files carrying several logical
streams at once, and files chained one after another.

| Mapping | Headers and tags | Audio |
|---|---|---|
| Vorbis | read | decodes, with seeking |
| FLAC | read | decodes, with seeking |
| Opus | read | decodes, with seeking |
| Speex | read | not yet |

```kotlin
val info = AudioKodec.probe(oggBytes)
println(info.codec.displayName)     // Opus
println(info.durationSeconds)       // 213.5
```

The length comes from the granule position on the last page, which is the only
place an Ogg file records it. For Opus the pre-skip is subtracted, because those
samples are an artefact of the encoder and are not part of the recording, and
so is the start of a stream cut out of a longer one. Decoding drops the same
samples: the pre-skip at the front and, at the back, whatever the last page's
granule position says lies past the end of the recording.

Opus always decodes at 48 kHz, whatever rate the header says the material was
recorded at, and applies the header's output gain. Samples are floats and are
not clipped, so a loud decode can reach past full scale where a 16-bit decoder
would have bent it back.

`open` on a mapping whose codec is not implemented throws
`UnsupportedFormatException` naming the codec, rather than failing in a way that
leaves a caller guessing.

### Vorbis

Vorbis is a transform codec: each packet is one overlapping block of spectrum,
described as a coarse curve with fine detail coded on top of it. Decoding one
means reading both, undoing the stereo coupling, multiplying them together,
running an inverse transform, applying a window and adding the result onto the
tail of the block before.

Two consequences of that reach the API. Blocks vary in size within one file,
because an encoder switches to short blocks at a transient, so the frame count
of what `nextFrame` returns varies too. And a seek cannot begin at an arbitrary
packet, because a block with no predecessor has no left half; AudioKodec restarts
on a page boundary before the target and discards up to it, so a seek is still
sample accurate.

```kotlin
AudioKodec.open(oggBytes).use { decoder ->
    decoder.seek(48_000)                    // one second in, at 48 kHz
    val block = decoder.nextFrame()
    println(block?.positionFrames)          // 48000
}
```

Everything an encoder emits is covered: floor type 1, all three residue types,
channel coupling, block switching, and up to eight channels, which come out in
AudioKodec's channel order rather than Vorbis's own. Vorbis puts the centre
channel second, between the two front channels, where WAV and everything derived
from it puts it third.

Floor type 0, which describes the curve as line spectral pairs instead of line
segments, is parsed and then refused by name. No encoder has emitted it since
the format's 2001 beta releases, which also means there is no way to produce a
file to test an implementation of it against, and shipping a decoder nothing
could check is worse than a clear refusal.

### Rewriting Ogg tags

A new comment is almost never the same length as the old one, and an Ogg page
cannot simply grow: its size is fixed by its segment table, its checksum covers
the whole page, and the pages after it are numbered consecutively. So the header
pages are rebuilt and the later pages renumbered, with every audio packet copied
across untouched.

A file interleaving several logical streams, such as a video with a soundtrack,
is refused rather than repaginated. Rebuilding the interleaving is a job worth
doing properly or not at all.

## MP4

MP4 is a container, and the same reader covers its relatives: M4A, M4B, 3GP
and QuickTime `.mov`. The audio track's packets are found from its sample
tables wherever the `moov` box sits, before the audio or after it, and in
fragmented files from each fragment's own table.

| Coding | Audio |
|---|---|
| Opus | decodes, with seeking |
| FLAC | decodes, with seeking, exactly |
| Uncompressed PCM, float, A-law and mu-law | decodes, with seeking, exactly |
| ALAC | decodes, with seeking, exactly |
| AAC, MP3, AC-3, AMR | named, not yet decoded |

```kotlin
val info = AudioKodec.probe(m4aBytes)
println(info.codec.displayName)                      // AAC
val chapters = AudioKodec.readTags(m4bBytes).chapters
```

The length is what the track's edit list says plays, which is how an encoder
leaves out its priming samples, and decoding drops the same samples. An edit
list that plays the media straight through is followed; one that skips or
repeats parts of it is refused, and in tolerant mode only its first part is
played. A file without an edit list plays all of its media, except Opus,
whose pre-skip is dropped either way.

Chapters come from a QuickTime chapter track, with start and end times, or
failing that from Nero's `chpl` list, which states only starts: the last
chapter's end is reported as -1.

## MPEG audio

An MP3 has no file header. It is a run of frames, each one carrying its own
bitrate, sample rate and channel mode, with tag blocks in front of and behind
the audio that are not part of it. Reading such a file means finding the frames.

```kotlin
val info = AudioKodec.probe(mp3Bytes)
println(info.durationSeconds)     // exact, not estimated
println(info.dataOffset)          // where the audio begins, past the tags
```

Three things make the length exact. A constant bitrate file divides out. A
variable bitrate one almost always carries its frame count in a `Xing` or
`VBRI` block hidden inside a silent first frame, which is counted as
bookkeeping rather than as audio. And where LAME recorded its gapless numbers,
the samples of silence it added at each end come off, along with the 529
samples of delay every MPEG decoder's own filter introduces.

A sync word is eleven set bits, which turns up in ordinary audio data every
couple of kilobytes. A candidate frame is therefore only believed once the
frames that should follow it do. Without that a decoder starts mid-frame and
plays noise, or finds its first frame inside a cover image.

Files using the free format, whose frames state no length at all, are named and
refused rather than measured by guesswork.

All three layers decode. Layer III, usually called MP3, can draw a frame's
audio data from a bit reservoir extending into earlier frames. Seeking starts
far enough before the target to reconstruct that state, then discards samples
until it lands on the exact requested frame.

```kotlin
AudioKodec.open(mp3Bytes).use { decoder ->
    decoder.seek(44_100)                 // one second in, to the sample
    val block = decoder.nextFrame()      // 1152 frames of Float32
}
```

Oracle tests compare all three layers with ffmpeg's floating-point decoders;
their output agrees to about one part in a million.

## Tags

Tag reading works on any file, including the ones AudioKodec cannot decode yet.
A tag block does not need a decoder.

```kotlin
val tags = AudioKodec.readTags(bytes)
println(tags[TagField.TITLE])
println(tags.all(TagField.ARTIST))    // a file may name more than one
println(tags.number(TagField.TRACK_NUMBER))
```

### Where the values come from

A file often carries more than one tag block, and they disagree. `TagBundle`
merges them so that the richer format wins:

| Format | Beaten by |
|---|---|
| ID3v2 | nothing |
| Vorbis comments, MP4 atoms | ID3v2 |
| APEv2 | the above |
| APEv1 | APEv2 |
| RIFF `INFO`, AIFF text chunks | the above |
| Broadcast Wave `bext` | the above |
| Lyrics3v2 | the above |
| ID3v1 | everything |

A field that only the losing block states is still taken from it, so nothing
is dropped for being in the wrong place. Everything stays available exactly as
it was stored:

```kotlin
println(tags.raw.formats)                    // [ID3v2.4, ID3v1]
for (block in tags.raw.blocks) {
    println("${block.format.displayName} at ${block.offset}, ${block.size} bytes")
    for (entry in block.entries) println("  ${entry.key} = ${entry.text}")
}
```

### Editing

Editing rewrites the tag blocks and copies the audio across unchanged. It never
decodes or re-encodes, so it is safe on a lossy file.

```kotlin
val edited = AudioKodec.readTags(bytes).edit {
    set(TagField.TITLE, "New title")
    set(TagField.TRACK_NUMBER, 4)
    set(TagField.TRACK_TOTAL, 11)
    remove(TagField.COMMENT)
    addPicture(TagPicture(mimeType = "image/jpeg", bytes = coverBytes))
}
val updated = AudioKodec.writeTags(bytes, edited)
```

This produces a new file rather than editing one in place. Write it to a
temporary file and move that over the original, which is what makes the
operation safe against a crash halfway through.

`TagWriteOptions` controls the details:

```kotlin
AudioKodec.writeTags(
    bytes,
    edited,
    TagWriteOptions(
        id3Version = 3,        // for hardware that predates ID3v2.4
        padding = 4096,        // room for the next edit to reuse
        keepId3v1 = true,      // update an existing block; never create one
    ),
)
```

### Pictures

```kotlin
tags.cover?.let { showCover(it.bytes, it.mimeType) }
for (picture in tags.pictures) {
    println("${picture.type}: ${picture.mimeType}, ${picture.bytes.size} bytes")
}
```

The bytes are the image file exactly as it was stored. AudioKodec does not
decode it, and does not need to: pass them to ImageKodec, or to whatever the
platform uses.

### Loudness

Two conventions exist and they are five decibels apart. ReplayGain 2 measures
against -18 LUFS; Opus writes `R128_TRACK_GAIN` against -23 LUFS. AudioKodec
reports both as decibels and says which was found, so applying one as the other
is not a mistake you can make by accident.

```kotlin
tags.replayGain?.let { gain ->
    val reference = if (gain.isR128) -23.0 else -18.0
    applyGain(gain.trackGainDb, reference)
}
```

## Channel layouts

An `AudioFormat` carries the speaker position of every channel, in the order
the samples are interleaved. Files that state their layout, such as a WAV with
a channel mask, keep it through decoding, and AudioKodec writes the mask back
out for files with more than two channels.

```kotlin
val format = AudioFormat(48_000, ChannelLayout.SURROUND_5_1)
println(format.channelLayout.channels)  // [FRONT_LEFT, FRONT_RIGHT, ...]
```

`Pcm.remapChannels` reorders samples between two layouts that name the same
channels.

## Handling bad input

Every failure AudioKodec reports is an `AudioKodecException`:

| Exception | Meaning |
|---|---|
| `UnsupportedFormatException` | The format was recognised and has no decoder here |
| `UnsupportedFeatureException` | The format is supported and this corner of it is not |
| `CorruptStreamException` | The file contradicts its own format |
| `TruncatedStreamException` | The file ends in the middle of something |
| `AllocationGuardException` | A header asked for more memory than the options allow |

Nothing else escapes a decoder, whatever the input.

A truncated file throws by default. To salvage what is there instead:

```kotlin
AudioKodec.decode(bytes, DecodeOptions(tolerant = true))
```

`DecodeOptions.maxAllocationBytes` caps what any single allocation may be, and
defaults to one gibibyte. The check happens before the allocation, not after
it fails.

## Reading and writing files

The core never touches the filesystem, which is what lets it build for every
Kotlin target. `audiokodec-io` adds the bridge:

```kotlin
val info = Path("song.wav").readAudioSource().use { AudioKodec.probe(it) }

Path("out.wav").asAudioSink().use { sink ->
    AudioKodec.encode(samples.asPcmSource(format), EncodeTarget.wav(24), sink)
}
```

Reading loads the file into memory, because decoding needs random access and
kotlinx-io offers sequential streams only. A sink cannot seek backwards
either, so encoding to one needs a `PcmSource` whose frame count is known in
advance.

For a large file, implement `AudioSource` over a seekable handle of your own.
It has three members, and the platform APIs behind it differ enough that
AudioKodec does not try to wrap them all.

## Playing the result

AudioKodec decodes and does not play. The result is Float32 PCM, which every
platform sink accepts:

| Platform | Sink | Notes |
|---|---|---|
| Android | `AudioTrack` | `ENCODING_PCM_FLOAT`, or convert with `Pcm.intFromFloat` for older devices |
| Apple | `AVAudioEngine` | `AVAudioPCMBuffer` with a non-interleaved or interleaved float format |
| JVM desktop | `javax.sound.sampled` | Convert to 16-bit with `Pcm.intFromFloat` first |
| Browser | Web Audio | `AudioBuffer.copyToChannel` takes float channels, so split with `Pcm.deinterleave` |

# KiteAudio

Audio containers, codecs and tags written in Kotlin for Kotlin Multiplatform:
read and write FLAC, WAV, AIFF and AU; decode FLAC, Vorbis, every MPEG audio
layer and the uncompressed family to Float32 PCM; edit tags without re-encoding
the audio; and run the same code on every target.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Targets](https://img.shields.io/badge/targets-22-blue)](#targets)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

**[Documentation](docs/)** · a guide per task, plus the format support table.

KiteAudio is at an early version. Audio covers MPEG Layers I, II and III,
Vorbis, FLAC both native and inside Ogg, and the uncompressed family. Tag
reading and editing covers ID3, APE, Vorbis comments and the metadata chunks of
RIFF, AIFF and Ogg, on any file. [What it reads and writes](#formats) is the whole list,
[PORTING_STATUS.md](PORTING_STATUS.md) has the detail behind it, and the
[open issues](https://github.com/yuroyami/KiteAudio/issues) are what comes next.
Nothing below is aspirational: every format in the tables has tests, and most
are checked against ffmpeg.

## Why

Kotlin Multiplatform has no library that decodes audio in common code. The
existing choices wrap each platform's own player, which gives you playback and
not samples, and gives you nothing at all on JS or WebAssembly. KiteAudio
decodes in `commonMain` with no dependency beyond `kotlin-stdlib`, so the same
call works on Android, iOS, the JVM, Linux, Windows, watchOS, in a browser and
in a WASI runtime.

## Install

```kotlin
dependencies {
    implementation("io.github.yuroyami:kiteaudio:0.1.0")
    // Optional, for reading and writing through kotlinx-io:
    implementation("io.github.yuroyami:kiteaudio-io:0.1.0")
}
```

## Decode a file

```kotlin
val audio = KiteAudio.decode(bytes)
println("${audio.format.sampleRate} Hz, ${audio.format.channels} channels")
println("${audio.durationSeconds} seconds")
// audio.samples is interleaved Float32, nominally -1.0 to 1.0
```

## Read a header without decoding

`probe` reads headers and nothing else, so scanning a folder costs a few
kilobytes per file rather than a full decode.

```kotlin
val info = KiteAudio.probe(bytes)
println(info.container.displayName)   // WAV
println(info.codec.displayName)       // PCM
println(info.durationFrames)          // 4410000
println(info.bitDepth)                // 16
```

## Stream and seek

Whole-file decoding allocates the whole file. For anything long, pull blocks
instead. Seeking is sample accurate: the decoder lands on the frame you asked
for, not on the block that contains it.

```kotlin
KiteAudio.open(source).use { decoder ->
    decoder.seek(decoder.durationFrames!! / 2)
    while (true) {
        val block = decoder.nextFrame() ?: break
        play(block.samples, block.frameCount)
    }
}
```

`DecodeOptions(reuseBuffers = true)` removes almost all allocation from that
loop, at the cost of each block staying valid only until the next one.

## Write a file

```kotlin
val wav = KiteAudio.encode(samples, AudioFormat(44_100, 2), EncodeTarget.wav(16))
val small = KiteAudio.encode(samples, format, EncodeTarget.wavImaAdpcm())
val exact = KiteAudio.encode(samples, format, EncodeTarget.wavFloat(32))
```

Reducing the depth of a float or a deeper integer source is where dither
belongs:

```kotlin
KiteAudio.encode(samples, format, EncodeTarget.wav(16, dither = Dither.Tpdf()))
```

The source channel layout is never silently relabelled. WAV PCM and float
preserve any positional layout that follows WAV's speaker-bit order, including
custom mono and stereo masks. AIFF and AU store only a channel count, compressed
WAV has no mask in this writer, and FLAC fixes the layout for each channel
count; encoding rejects a non-representable layout before writing any bytes.

Passing a `TagBundle` to `encode` writes it with the audio. WAV and AIFF use
their native text chunks plus ID3v2.4 for Unicode and richer fields, while FLAC
uses Vorbis comments and picture blocks. AU has no structured tag format and
rejects a non-empty bundle instead of silently dropping it.

## FLAC

FLAC decodes bit-exactly and encodes to files the reference decoder accepts.

```kotlin
val flac = KiteAudio.encode(samples, format, EncodeTarget.flac(bitDepth = 16, effort = 5))
val audio = KiteAudio.decode(flac)
```

`effort` runs from 0 to 8 and trades encoding time against size, the same way
the reference encoder's numbered levels do. It never changes what comes back
out, because the format is lossless at every setting.

Every FLAC file carries an MD5 of its own audio, which makes a decoder
checkable against whichever encoder wrote the file:

```kotlin
when (KiteAudio.verifyFlacMd5(bytes)) {
    true -> println("the audio matches the hash the encoder stored")
    false -> println("this file has been damaged")
    null -> println("this file states no hash")
}
```

Seeking works with or without a seek table. Without one the frames are scanned,
and both checksums in every frame are verified, so a false sync word is rejected
rather than decoded as audio.

## Ogg

The Ogg container is read in full: pages and their checksums, packets
reassembled across pages, files carrying several logical streams at once, and
files chained one after another.

Vorbis and FLAC inside Ogg both decode, seeking included. Opus and Speex are
identified and their headers, length and tags are read; their audio does not
decode yet, and `open` says so by name rather than failing obscurely.

Vorbis decodes the whole format: both floor and residue kinds an encoder emits,
channel coupling, block switching, and up to eight channels reordered into
KiteAudio's own channel order rather than Vorbis's. It is checked against
libvorbis, sample for sample, at every quality level and every channel count.
The one thing left out is floor type 0, which no encoder has produced since the
format's 2001 beta releases; a file using it is named and refused rather than
guessed at.

```kotlin
val info = KiteAudio.probe(oggBytes)
println(info.codec.displayName)      // Opus
println(info.durationSeconds)        // 213.5
println(KiteAudio.readTags(oggBytes)[TagField.TITLE])
```

Tags can be rewritten in any single-stream Ogg file. The header pages are
rebuilt and the pages after them renumbered, with the audio packets copied
untouched.

## MPEG audio

MP3 and its two older layers have no file header at all: a file is a run of
frames, each stating its own bitrate and sample rate, with tag blocks in front
and behind that are not audio. KiteAudio reads that layer in full and decodes
all three layers.

```kotlin
val info = KiteAudio.probe(mp3Bytes)
println(info.codec.displayName)      // MP3
println(info.durationSeconds)        // 213.5, with the encoder's padding off
println(info.isVariableBitrate)      // true
```

The length is exact rather than estimated. A constant bitrate file divides out,
a variable bitrate one carries its frame count in a `Xing` or `VBRI` block
inside a silent first frame, and where LAME wrote its gapless numbers the
samples of silence it added at each end are taken off, so one track follows
another with no gap.

A sync word turns up in ordinary audio data every couple of kilobytes, so a
candidate frame is only believed once the frames after it agree with it. Files
using the free format, whose frames state no length, are named and refused.

All three layers decode. Layer III is the one people mean by MP3, and it is the
awkward one: a frame's audio is not inside that frame but scattered up to 511
bytes earlier in a shared pool of bits, which is what lets a hard passage spend
more than one frame can hold. Seeking accounts for it by decoding a few frames
before the target and discarding them, so a seek lands on the sample it was
asked for rather than near it.

```kotlin
KiteAudio.open(mp3Bytes).use { decoder ->
    decoder.seek(44_100)                 // one second in, to the sample
    val block = decoder.nextFrame()      // 1152 frames of Float32
}
```

Measured against ffmpeg's own decoders, the output agrees to about one part in a
million for Layer III and to the same for Layers I and II. Note that ffmpeg
ships two decoders for the older layers and picks its fixed point one by
default; the comparison above is against its float decoder, because its two
disagree with each other by far more than either disagrees with KiteAudio.

## Read and edit tags

Tags work on any file, including the ones KiteAudio cannot decode. A tag block
does not need a decoder, so an MP3 or a FLAC has its tags read today.

```kotlin
val tags = KiteAudio.readTags(bytes)
println(tags[TagField.TITLE])
println(tags[TagField.ARTIST])
println(tags.number(TagField.TRACK_NUMBER))
tags.cover?.let { showCover(it.bytes, it.mimeType) }
```

A file with several tag blocks gets them merged, with the richer format
winning any field the two disagree on. Everything stays available as it was
stored:

```kotlin
println(tags.raw.formats)                      // [ID3v2.4, ID3v1]
println(tags.raw.blocksOf(TagFormat.ID3V1))    // the older block, untouched
```

Editing rewrites the tag blocks and copies the audio across byte for byte, so
it is safe on a lossy file that would lose quality if it were decoded and
encoded again:

```kotlin
val edited = tags.edit {
    set(TagField.TITLE, "New title")
    set(TagField.TRACK_NUMBER, 4)
    remove(TagField.COMMENT)
}
val updated = KiteAudio.writeTags(bytes, edited)
```

Tags are read from ID3v2.2, 2.3 and 2.4, ID3v1 and v1.1, APEv1 and APEv2,
Vorbis comments in both FLAC and Ogg, FLAC picture blocks, Lyrics3v2, the
`LIST INFO` and `bext` chunks of a WAV, the text chunks of an AIFF, and an
`id3 ` chunk inside either. Writing produces ID3v2.3 or 2.4, Vorbis comments and
pictures in FLAC and Ogg, an updated ID3v1 block where the file already had one,
and the metadata chunks of RIFF and AIFF. MP4 atoms arrive with the MP4
container.

## Name a format

The sniffer names 29 formats, including those KiteAudio cannot decode. That is
deliberate: a file it cannot read is one you can route somewhere else, and
guessing from the extension is worse.

```kotlin
val container = KiteAudio.sniff(firstBytes)   // AudioContainer.FLAC
if (container?.isDecodable == true) { /* KiteAudio can read it */ }
```

## Formats

### Read and write

| Container | Codings |
|---|---|
| FLAC | FLAC, 8 to 32-bit on read and 8, 16 or 24-bit on write |
| Ogg | Vorbis and FLAC. Opus and Speex have their headers, length and tags read, and their audio is not decoded yet |
| WAV (RIFF), including WAVE_FORMAT_EXTENSIBLE | PCM 8/16/24/32-bit, IEEE float 32/64-bit, A-law, mu-law, IMA ADPCM, Microsoft ADPCM |
| AIFF and AIFF-C | PCM 8/16/24/32-bit big-endian, A-law, mu-law, and on write IEEE float 32/64-bit |
| AU | PCM 8/16/24/32-bit big-endian, IEEE float 32/64-bit, A-law, mu-law |

RF64 and BW64 are read, and written automatically when a file passes 4 GiB.

On reading, the AIFF-C compression types understood are `NONE`, `twos`,
`sowt`, `raw `, `in24`, `in32`, `23ni`, `42ni`, `fl32`, `fl64`, `alaw`, `ulaw`
and `ima4`.

### Read only

| Container | Codings |
|---|---|
| Wave64 | PCM 8/16/24/32-bit, IEEE float 32/64-bit, A-law, mu-law |
| CAF | PCM 8/16/24/32-bit in both byte orders, IEEE float 32/64-bit, A-law, mu-law, QuickTime IMA ADPCM |
| MPEG audio | Layers I, II and III (MP1, MP2 and MP3), with gapless trimming and sample-accurate seeking |

### Named but not decoded

AAC in both ADTS and ADIF framing, MP4 and M4A, Matroska and WebM, Monkey's
Audio, WavPack, Musepack, True Audio, DSF, DSDIFF, AMR, AC-3, DTS, ASF and WMA,
MPEG-TS, MIDI, Shorten, OptimFROG and TAK.

Sniffing is not decoding. `probe` and `open` throw
`UnsupportedFormatException` for all of these, and the message names the
format.

## Precision

Decoding produces Float32, which carries 8-, 16- and 24-bit audio exactly. It
cannot carry 32-bit integers exactly, because a Float32 has 24 bits of
mantissa. A decoder for a lossless coding therefore also implements
`IntPcmAccess`, which hands back the stored integers untouched:

```kotlin
KiteAudio.open(bytes).use { decoder ->
    if (decoder is IntPcmAccess) {
        val block = decoder.nextIntFrame()   // exactly what the file holds
    }
}
```

## Malformed input

Every failure is a `KiteAudioException`: `UnsupportedFormatException`,
`UnsupportedFeatureException`, `CorruptStreamException`,
`TruncatedStreamException` or `AllocationGuardException`. Nothing else escapes
a decoder, whatever the input, and a fuzz suite holds that claim up.

A truncated file throws by default and can be salvaged instead:

```kotlin
KiteAudio.decode(bytes, DecodeOptions(tolerant = true))
```

`DecodeOptions.maxAllocationBytes` caps what a header can make KiteAudio
allocate. It defaults to one gibibyte, and the guard fires before the
allocation is attempted rather than after it fails.

## Targets

`kiteaudio` builds for 22 targets: Android (minSdk 21), `jvm`, `iosArm64`,
`iosSimulatorArm64`, `iosX64`, `macosArm64`, `tvosArm64`,
`tvosSimulatorArm64`, `watchosArm32`, `watchosArm64`, `watchosDeviceArm64`,
`watchosSimulatorArm64`, `androidNativeArm32`, `androidNativeArm64`,
`androidNativeX64`, `androidNativeX86`, `linuxX64`, `linuxArm64`, `mingwX64`,
`js` (browser and Node), `wasmJs` (browser and Node), `wasmWasi` (Node). There
is no `macosX64`, following Kotlin's deprecation of Intel-Apple targets.

`kiteaudio-io` builds for the same set without `js`, which is what kotlinx-io
publishes.

## Reading and writing files

The core never touches the filesystem, so it ports everywhere. `kiteaudio-io`
adds the bridge to kotlinx-io:

```kotlin
val info = Path("song.wav").readAudioSource().use { KiteAudio.probe(it) }
Path("out.wav").asAudioSink().use { sink ->
    KiteAudio.encode(samples.asPcmSource(format), EncodeTarget.wav(24), sink)
}
```

Reading loads the file into memory, because decoding needs random access and
kotlinx-io offers sequential streams only. For large files, implement
`AudioSource` over a seekable handle of your own: it has three members.

## License

Apache-2.0. See [LICENSE](LICENSE).

Every format here was implemented from its published specification.
[NOTICE](NOTICE) lists those specifications, and
[reference/REFERENCES.md](reference/REFERENCES.md) records what may and may
not be used as the library grows.

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[KiteImage](https://github.com/yuroyami/KiteImage),
[KitePDF](https://github.com/yuroyami/KitePDF),
[KiteArchive](https://github.com/yuroyami/KiteArchive),
[KiteQR](https://github.com/yuroyami/KiteQR).

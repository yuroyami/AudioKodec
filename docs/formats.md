# Formats

This page lists what AudioKodec reads and writes, then describes each container and
coding. The codings with their own page link to it. [Format support](support.md) has the
complete support tables and how each format is checked against a reference decoder.

## Read and write

| Container | Codings |
|---|---|
| FLAC | FLAC, 4 to 32-bit on read and 8, 16 or 24-bit on write |
| WAV (RIFF), including WAVE_FORMAT_EXTENSIBLE | PCM 8/16/24/32-bit, IEEE float 32/64-bit, A-law, mu-law, IMA ADPCM, Microsoft ADPCM; GSM 06.10, G.722 and G.726 are read only |
| AIFF and AIFF-C | PCM 8/16/24/32-bit big-endian, A-law, mu-law, and on write IEEE float 32/64-bit |
| AU | PCM 8/16/24/32-bit big-endian, IEEE float 32/64-bit, A-law, mu-law; G.722 and G.726 (Sun's G.721 and G.723) are read only |
| MP4 and M4A | ALAC 16/20/24/32-bit with one to eight channels, written with packets identical to those of Apple's reference encoder. The other MP4 codings are read only |

RF64 and BW64 are read, and a WAV is written as RF64 automatically when it passes 4 GiB.

On reading, the AIFF-C compression types understood are `NONE`, `twos`, `sowt`, `raw `,
`in24`, `in32`, `23ni`, `42ni`, `fl32`, `fl64`, `alaw`, `ulaw` and `ima4`. The reversed
integer types are little-endian: `23ni` is 32-bit and `42ni` is 24-bit.

## Read only

| Container | Codings |
|---|---|
| Ogg | Vorbis, Opus, FLAC and Speex, with up to 256 simultaneous logical streams. Tags can be rewritten |
| Wave64 | PCM 8/16/24/32-bit, IEEE float 32/64-bit, A-law, mu-law |
| CAF | PCM 8/16/24/32-bit in both byte orders, IEEE float 32/64-bit, A-law, mu-law, QuickTime IMA ADPCM, ALAC 16/20/24/32-bit, iLBC |
| MPEG audio | Layers I, II and III (MP1, MP2 and MP3), with gapless trimming and sample-accurate seeking |
| [Matroska and WebM](matroska.md) | Opus, Vorbis, FLAC, MPEG Layers I, II and III, AAC-LC, AC-3, PCM 8 to 32-bit in both byte orders, IEEE float 32/64-bit, from the first audio track. Tags and chapters are read |
| MP4, M4A, M4B, 3GP and QuickTime | AAC-LC, ALAC 16/20/24/32-bit, Opus, FLAC, MPEG Layers I, II and III, PCM 8/16/24/32-bit in both byte orders, IEEE float 32/64-bit, A-law, mu-law, AMR-NB and AMR-WB, GSM 06.10, iLBC, AC-3. Tags can be rewritten |
| ADTS | [AAC-LC](aac.md), with sample-accurate seeking |
| [AC-3](ac3.md) | Every coding mode from dual mono to 3/2 with the low frequency channel, at 32, 44.1 and 48 kHz. Enhanced AC-3 is refused by name |
| [WavPack](wavpack.md) | Integers of 1 to 32 bits and 32-bit float, exact integers through `IntPcmAccess`, mono to multichannel; hybrid files without their correction file. APEv2 tags are read |
| [Monkey's Audio](ape.md) | Every compression level, 8 to 32 bits and 32-bit float, exact integers through `IntPcmAccess`, one to 32 channels except seven, from version 3.99 on. APEv2 tags are read |
| [True Audio](tta.md) | Up to 24 bits, exact integers through `IntPcmAccess`, any number of channels. ID3 and APEv2 tags are read |
| [Musepack](musepack.md) | Stream version 8, mono and stereo, at 32 to 48 kHz, with the exact length its header states. APEv2 tags are read |
| [AMR-NB and AMR-WB](amr.md) | Every rate, single-channel and multichannel, with comfort noise and concealment, exactly as opencore-amr decodes them |
| [iLBC](ilbc.md) | 20 and 30 ms frames in the storage format of RFC 3952, exactly as the iLBC decoder of WebRTC decodes them |
| [DSF and DSDIFF](dsd.md) | DSD at any rate, converted to PCM at a thirty-second of it, one to six channels in DSF and any number in DSDIFF. ID3v2 tags are read. DST compression is refused |

[Speex](speex.md), [GSM 06.10](gsm.md) and [G.722 and G.726](g722-g726.md) have their own
pages too.

## Probed but not decoded

Raw AAC in ADIF framing. `probe` reports the sample rate, the channels and the bitrate.
ADIF states no frame boundaries, so its length is unknown without decoding the whole
stream, and `open` refuses it by name.

## Named but not decoded

The sniffer names 30 formats: the 22 containers in the tables above, ADIF, and DTS, ASF and
WMA, MPEG-TS, MIDI, Shorten, OptimFROG and TAK. `probe` and `open` throw
`UnsupportedFormatException` for the last seven, and the message names the format. Enhanced
AC-3 shares the sync word of AC-3, and `open` refuses it by name.

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

FLAC can store an MD5 of its unencoded audio, written by whichever program
encoded it. That makes a decoder checkable against a third party:

```kotlin
when (AudioKodec.verifyFlacMd5(bytes)) {
    true -> println("the audio matches the hash the encoder stored")
    false -> println("this file has been damaged")
    null -> println("there is no hash for the complete audio in this file")
}
```

It costs a full decode, so it belongs in a library-integrity pass rather than
in front of every playback.

### Reading a stream-copy cut

Tools such as `ffmpeg -ss T -i input.flac -c copy cut.flac` may retain original
frame numbers, sample totals, seek points and MD5. Strict playback names the
nonzero first-frame origin as corruption. With `DecodeOptions(tolerant = true)`,
AudioKodec derives duration from the remaining complete, consecutive frames,
starts positions at zero and rebases every seek. Inspection reads those frames
too; it costs a decode. Original seek-table offsets are ignored, and MD5
verification returns `null` because the stored hash describes absent audio.

### Seeking

FLAC frames vary in size, so a position cannot be turned into a byte offset by
multiplying. AudioKodec uses a useful seek-table point when available. Otherwise,
a source of known size is bisected using frame-header CRC-8 and coded positions.
Only the target frame is decoded, with its CRC-16 and sample range verified
before playback. That decoded frame is retained for the next read. A seek to
the known end returns immediately without reading audio.

Unknown-size sources use a forward walk that fully verifies each preceding
frame. The same walk resolves ambiguous CRC-8-valid headers embedded in sample
data, so a false sync cannot change the samples returned at a requested position.

Set `DecodeOptions(allowSlowSeek = false)` to make an unindexed seek
throw instead of taking its time.

### Editing a FLAC file's tags

Editing rewrites the metadata blocks and copies the audio frames across
untouched. Seek tables, cue sheets and application blocks the file carried are
kept as they were, and padding is regenerated so the next edit need not move the
audio again.

The stream MD5 still matches after an edit, which proves that the audio was not
touched.

## Ogg

Ogg is a container, not a codec, so what a file holds depends on the mapping
inside it. AudioKodec reads the container in full: pages and their checksums,
packets reassembled across page boundaries, files carrying several logical
streams at once, and files chained one after another.

[Chained playback](ogg-chains.md) includes every compatible Vorbis, Opus or
FLAC link. Each applies its own headers, pre-skip and end trim; durations and
seek positions count the complete recording. A changed output rate or layout
is refused before playback, and tags refer to the first link.

Opening a file accepts up to 256 simultaneous logical streams. A larger set
is refused with `UnsupportedFeatureException`; tolerant mode keeps this limit.
Stream headers and their serial-number index also respect
`DecodeOptions.maxAllocationBytes`. Tag reads remain best-effort when the Ogg
headers cannot be read.

| Mapping | Headers and tags | Audio |
|---|---|---|
| Vorbis | read | decodes, with seeking |
| FLAC | read | decodes, with seeking |
| Opus | read | decodes, with seeking |
| Speex | read | decodes, with seeking that converges after a run-up |

```kotlin
val info = AudioKodec.probe(oggBytes)
println(info.codec.displayName)     // Opus
println(info.durationSeconds)       // 213.5
```

Length comes from the last granule minus the stream's start offset. Opus,
Vorbis and FLAC derive that offset from the first usable audio page and the
completed samples in its packets. Positions and seeks count from zero even
when stored granules begin above zero. Opus also subtracts pre-skip, and Vorbis
counts overlapping windows with no output from its priming packet. These
length calculations read packet headers and modes without decoding samples.

Vorbis start timing and end trim cannot be separated when the first usable
granule also ends the stream. A positive origin in that layout is named and
refused with `UnsupportedFeatureException` in either mode. Ordinary single-page
Vorbis recordings retain their zero origin and end trimming.

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

Both floor types decode, along with all three residue types,
channel coupling, block switching, and up to eight channels, which come out in
AudioKodec's channel order rather than Vorbis's own. Vorbis puts the center
channel second, between the two front channels, where WAV and everything derived
from it puts it third.

[Floor type 0](vorbis-floor-zero.md) describes the curve as line spectral pairs
instead of line segments. Its vector decoding and Bark-mapped response follow
the specification. It agrees with FFmpeg and libvorbis on synthetic streams,
including both vector lookup types and setups that change between chained links.

### Opus

Opus decodes the whole format: the SILK speech codec, the CELT music codec, the hybrid of
the two, the joins between them, and every channel mapping, from mono through 7.1 surround
to ambisonics. Its codec core supports lost-packet concealment and recovery from redundant
packet data. The pre-skip and the end trim are applied, so the samples come out where they
went in, and one file follows another without a gap. Every layer is checked against
libopus, sample for sample.

### Rewriting Ogg tags

A new comment is almost never the same length as the old one, and an Ogg page
cannot simply grow: its size is fixed by its segment table, its checksum covers
the whole page, and the pages after it are numbered consecutively. So the header
pages are rebuilt and the later pages renumbered, with every audio packet copied
across untouched.

A final header packet sharing its page with audio is refused before any output.
Strict mode reports the invalid header boundary as `CorruptStreamException`;
tolerant mode reports `UnsupportedFeatureException` because copying only the
later pages would lose audio. An external ID3 prefix is also left unwritten.

In a file interleaving several logical streams, such as a video with a
soundtrack or two soundtracks, the first audio stream's tags are rewritten:
its header pages are rebuilt where they stood, its audio pages are renumbered
in place, and every page of the other streams is copied byte for byte, so the
interleaving and every granule position stay as they were.

## MP4

MP4 is a container, and the same reader covers its relatives: M4A, M4B, 3GP
and QuickTime `.mov`. The audio track's packets are found from its sample
tables wherever the `moov` box sits, before the audio or after it, and in
fragmented files from each fragment's own table.

Every indexed packet must fit wholly inside one declared `mdat` payload.
Offsets into atom headers, metadata or gaps are corruption in either mode.
The selected data reference must describe a local URL or alias; external files
and resource-fork references are named and refused. Legacy self-contained
files without `dref` retain implicit reference 1. Packet reads still detect
physical truncation, including in a final zero-sized `mdat`, so tolerant mode
can retain the complete audio prefix.

QuickTime files do not need an `ftyp` brand atom. Detection recognizes `moov`
and `mdat`, and can skip leading `free`, `skip`, `wide` and `uuid` atoms. It
checks at most 128 atom headers. For byte-array `sniff`, the identifying header
must fit in the supplied window; `probe` and `open` can jump over larger padding.

| Coding | Audio |
|---|---|
| Opus | decodes, with seeking |
| FLAC | decodes, with seeking, exactly |
| Uncompressed PCM, float, A-law and mu-law | decodes, with seeking, exactly |
| ALAC | decodes, with seeking, exactly; encodes |
| MPEG Layers I, II and III | decodes, with seeking and edit-list trimming |
| AAC-LC | decodes, with seeking and edit-list trimming |
| AMR-NB, AMR-WB | decodes, with seeking that converges after a run-up, exactly |
| AC-3 | decodes, with seeking |

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

iTunes also records the encoder delay, the end padding and the real length in
an `iTunSMPB` item among the tags. When the file has no edit list, or one that
plays every frame, decoding applies that record: the length is the stated one,
the delay is dropped, and seeking counts from the first real frame. An edit
list that trims anything takes precedence, and a record that cannot be read or
starts past the last frame is ignored.

Opening an Opus edit decodes up to 80 ms of preceding packets, as seeking does,
and discards their output before reporting playable frame zero. This run-up
restores packet state; it approximates a continuous decode rather than promising
bit-exact Opus seeking. Independent codecs start at the first audible packet.

AAC-LC decodes every tool of its profile, from the floating-point process the
standard describes, and agrees with ffmpeg's decoder to within float rounding.
Seeking decodes one block before its target, which makes it sample-exact.
HE-AAC is refused by name, rather than played as its half-rate core. See
[AAC](aac.md).

[MPEG tracks](mpeg-in-mp4.md) use the same frame decoding as bare MPEG files.
MP3 restores its bit reservoir and synthesis history with up to ten preceding
frames; Layers I and II use two. Opening an edited start needs the same run-up.
The edit list supplies gapless limits, without applying a second LAME trim.
FFmpeg copy muxes can retain end padding when they discard the original MP3's
final padding flag; playback follows the resulting MP4 timeline.

Chapters come from a QuickTime chapter track, with start and end times, or
failing that from Nero's `chpl` list, which states only starts: the last
chapter's end is reported as -1.

Tags live in the item list inside `moov`, and writing them rebuilds only that
box. See [MP4 metadata](mp4-tags.md) for where each field goes, the artwork
the cover atom can hold, and when the audio moves.

## Matroska and WebM

Matroska and WebM files decode from their first audio track: Opus, Vorbis,
FLAC, MPEG audio, AAC-LC, AC-3 and PCM. The length is counted from the frames
themselves, with CodecDelay and DiscardPadding trimming the ends, and agrees
with ffmpeg's decode of the same file; seeking is exact. Elements of unknown
size, all three lacing schemes, and zlib and header-stripped frames are read,
as are tags and the chapters of the first edition. Tags cannot be written.
See [Matroska and WebM](matroska.md).

## AAC

AAC-LC decodes from MP4 and from raw ADTS streams, at every sampling rate the standard
tabulates, with any number of channels that a fixed configuration or a program
configuration describes. Every tool of the low complexity profile is implemented, and
the output agrees with ffmpeg's decoder to within float rounding. HE-AAC, AAC Main, SSR
and LTP are refused by name. See [AAC](aac.md).

## Apple Lossless

ALAC decodes from MP4 and from CAF, at 16, 20, 24 and 32 bits and with one to eight
channels, reordered from the speaker order of ALAC into AudioKodec's. Exact integers are
available through `IntPcmAccess`. The decoder and the encoder are translated from Apple's
Apache-2.0 reference, and the encoder writes the same packets as that reference.

## AC-3

AC-3, Dolby Digital, decodes from raw `.ac3` streams, from MP4 and QuickTime
tracks and from Matroska tracks, in every coding mode from dual mono to 3/2
with the low frequency channel, at 32, 44.1 and 48 kHz. The length is the
syncframes counted, channels come out in AudioKodec's order with nothing mixed
down, and seeking runs one syncframe ahead of its target, which makes it
sample-exact. A syncframe that fails its CRC throws `CorruptStreamException`
unless `tolerant` is set. Enhanced AC-3, which shares AC-3's sync word, is
refused by name. See [AC-3](ac3.md).

## WavPack

WavPack files decode in every mode the encoder has, at 1 to 32 bits and as
32-bit floats, with exact integers through `IntPcmAccess` for lossless files.
Multichannel audio comes out in AudioKodec's speaker order by the file's
channel mask. Every block's checksum is verified, and seeking is exact
because blocks stand alone. Hybrid files decode to their lossy stream,
without the correction file. APEv2 tags are read as for any file. See
[WavPack](wavpack.md).

## Monkey's Audio

Monkey's Audio files decode at every compression level, at 8 to 32 bits and
as 32-bit floats, with exact integers through `IntPcmAccess` for integer
files. Every frame's checksum is verified, and seeking is exact because
frames stand alone. Files from before version 3.99 and files with seven
channels are refused by name. APEv2 tags are read as for any file. See
[Monkey's Audio](ape.md).

## Musepack

Musepack files in stream version 8 decode to the exact length their header
states, mono or stereo. Seeking is exact, except that noise substitution
draws new noise after a seek. Stream version 7 and older are refused by
name. APEv2 tags are read as for any file. See [Musepack](musepack.md).

## Speex

Speex inside Ogg decodes in narrowband, wideband and ultra-wideband, mono or
in-band stereo, to the samples and the length `speexdec` gives. Seeking
restarts the decoder 25 frames before the target, so the samples after a
seek converge on a continuous decode; a seek to the start is exact. Bitstream
versions other than 4 are refused by name. See [Speex](speex.md).

## AMR

AMR narrowband and wideband files decode at every rate, single-channel or
multichannel, and so do AMR tracks in 3GP and MP4 files, to exactly the
samples opencore-amr gives. Seeking decodes a half-second run-up before the
target; a seek to the start is exact. Reserved frame types are refused. See
[AMR](amr.md).

## GSM 06.10

GSM 06.10 decodes from WAV files, in Microsoft's 65-byte blocks, and from
QuickTime and MP4 files, in the plain 33-byte frames, to exactly the samples
libgsm gives. Seeking decodes a half-second run-up before the target. See
[GSM 06.10](gsm.md).

## iLBC

iLBC decodes in both frame sizes from `.lbc` files, CAF and QuickTime, to
exactly the samples WebRTC's decoder gives, with its enhancer and its
concealment of frames marked empty. Seeking is exact. See [iLBC](ilbc.md).

## G.722 and G.726

G.722 and G.726 decode from WAV and AU files, written from the ITU
Recommendations and checked against every one of the ITU's test sequences.
G.722 files ffmpeg writes decode to exactly ffmpeg's samples; ffmpeg's G.726
does not follow the Recommendation. Seeking is exact. See
[G.722 and G.726](g722-g726.md).

## DSD

DSD decodes from DSF and DSDIFF files, converted to PCM at a thirty-second of
its rate through a stated three-stage filter that agrees with ffmpeg's
conversion tone by tone within the two filters' difference. Seeking is exact,
and DST compression is refused by name. See [DSD](dsd.md).

## True Audio

True Audio files decode at up to 24 bits with exact integers through
`IntPcmAccess`, and their header, seek table and frames are checked against
their CRCs. Seeking is exact because frames stand alone. Password-protected
files are refused by name. ID3 and APEv2 tags are read as for any file. See
[True Audio](tta.md).

## MPEG audio

An MP3 has no file header. It is a run of frames, each one carrying its own
bitrate, sample rate and channel mode, with tag blocks in front of and behind
the audio that are not part of it. Reading such a file means finding the frames.

```kotlin
val info = AudioKodec.probe(mp3Bytes)
println(info.durationSeconds)     // exact, not estimated
println(info.dataOffset)          // where the audio begins, past the tags
```

The stored frame count in a `Xing`, `Info` or `VBRI` block gives the length when
present. Otherwise AudioKodec counts complete frame headers, including bitrate
changes and padding. The silent metadata frame is excluded from audio. LAME's
stored encoder delay and padding shorten the playable duration; playback also
accounts for the decoder filter's 529-sample delay when dropping the front.

Seeking uses a valid `Xing` or `Info` table of contents, including with
`DecodeOptions(allowSlowSeek = false)`. The table's quantized byte hints are
refined by counting complete frames from the hint to the stream end, preserving
sample-accurate output. The decoder then primes its bit reservoir and filter
history before returning the requested sample. A nearer index built during
playback is used when it costs fewer headers. Far seeks avoid reading the
whole preceding audio, but refinement can read the suffix beyond the target.
Sources whose size is unknown still require a header walk during opening.

An omitted byte count uses the observed stream extent. Trailing zero padding
is verified and excluded from the frame count. Backwards or out-of-range
hints are ignored; a table that cannot be refined falls back to the walked
index when slow seeking is allowed, or produces `UnsupportedFeatureException`.
A failed refinement preserves playback state.

A sync word is eleven set bits, which turns up in ordinary audio data every
couple of kilobytes. A candidate frame is therefore only believed once the
frames that should follow it do. Without that a decoder starts mid-frame and
plays noise, or finds its first frame inside a cover image.

Strict MPEG decoding accepts non-audio bytes shorter than a frame header and
zero padding after the final complete frame. ID3v1, enhanced ID3v1 and Lyrics3
trailers end the frame scan even when the source cannot report its size.
Playback stops before reading beyond a stored duration. A complete frame
header whose declared payload is cut short still raises `TruncatedStreamException`.

Embedded ID3v2.2, v2.3 and v2.4 tags between frames are skipped by their declared
size, including a v2.4 footer. Their bodies are neither allocated nor searched
for audio, so a large cover image does not consume the 64 KiB limit for scanning
unrecognised bytes. A recognized tag extending past the input raises
`TruncatedStreamException` in strict mode; tolerant mode retains the complete
audio prefix. Stored `Xing` and `VBRI` frame counts remain authoritative, so
joining separately indexed files does not extend the reported duration.

[Free-format MPEG](mpeg-free-format.md) measures constant unpadded frame size
from confirming headers. Its padding bit still applies per frame. Opening
walks the complete recording to check that spacing and determine duration,
including when a Xing count is present. Single frames without enough evidence
to determine their size raise a typed error.

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

All three layers agree with ffmpeg's floating-point decoders to within 1e-4 a
sample for Layers I and III, and to within 1e-5 for Layer II.

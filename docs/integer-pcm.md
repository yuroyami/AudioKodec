# Encoding exact integer PCM

`IntPcmSource` and the `IntArray` encode overload preserve samples without
converting them through Float32. Use them for lossless integer transcodes,
including 32-bit samples whose low bits Float32 cannot represent.

```kotlin
import io.github.yuroyami.audiokodec.*

val samples = intArrayOf(16777217, 1073741825, Int.MAX_VALUE,
    -16777217, -1073741825, Int.MIN_VALUE)
val format = AudioFormat(48_000, 1)
val wave = AudioKodec.encode(samples, format, bitDepth = 32, target = EncodeTarget.wav(32))
val aiff = AudioKodec.encode(samples, format, bitDepth = 32, target = EncodeTarget.aiff(32))
val au = AudioKodec.encode(samples, format, bitDepth = 32, target = EncodeTarget.au(32))
```

Input values are signed, right-justified and sign-extended, matching
`IntAudioFrame.samples`. A 16-bit input has values between -32768 and 32767;
it is not scaled up to 32 bits. This convention also applies to 8-bit WAV,
whose unsigned storage representation is handled by the writer.

The source depth must be stated explicitly and can be any value from 1 to
32. Target depths are 8, 16, 24 or 32 for integer WAV, AIFF and AU, and 8,
16 or 24 for FLAC. Integer input to float, G.711 or ADPCM targets is refused
before output or source consumption. The Float32 overloads continue to
support those targets.

| Depth change | Behavior |
|---|---|
| Equal depths | Every integer bit is preserved |
| Increasing depth | Shift left by the depth difference; new low bits are zero |
| Reducing depth, `Dither.None` | Arithmetic right shift; low bits are discarded, rounding towards negative infinity |
| Reducing depth, `Dither.Tpdf` | Scale using Float64, add seeded target-step noise, round to nearest with ties to even, and clip to the target range |

Dither has no effect at equal or increasing depths. Without dither, for
example, 16 to 24 bits multiplies by 256 and 32 to 24 bits divides by 256
with an arithmetic shift. A negative input of -1 becomes -1 on that
reduction, while a positive input of 1 becomes 0. Reducing depth loses the
discarded precision before a lossless coding such as FLAC begins.

## Arrays and streams

Array overloads create a fresh reader for each call. To encode to an
`AudioSink`, wrap the array explicitly:

```kotlin
val source = samples.asIntPcmSource(format, bitDepth = 32)
AudioKodec.encode(source, EncodeTarget.aiff(32), sink)
```

`asIntPcmSource` does not copy the array. Its optional `frameCount` can limit
the prefix to encode. For example, an `IntAudioFrame` can have a reusable
buffer longer than its valid samples:

```kotlin
val source = block.samples.asIntPcmSource(block.format, block.bitDepth, block.frameCount)
val encodedBlock = AudioKodec.encode(source, EncodeTarget.wav(block.bitDepth))
```

Choose a supported target width when a decoded block's depth is not one of
8, 16, 24 or 32. Increasing it preserves its precision at the corresponding
signal level.

For a complete streaming transcode, implement `IntPcmSource.read` to pull
blocks from `IntPcmAccess.nextIntFrame`, retaining a cursor within each
decoded block when an encoder requests fewer frames. Copy only that block's
valid `sampleCount` entries. Do not mix float and integer decoder reads;
they share the decoder's cursor.

A source is consumed. Create a fresh source for another encoding. Short
nonzero reads are allowed; zero means end of input. Declared frame counts
must match the frames actually supplied. Unpatchable WAV, AIFF and AU sinks
need the count before writing. FLAC can stream with unknown totals and
leave its MD5 unset when the sink cannot be patched.

Values outside the declared signed range and invalid read counts produce
`AudioKodecException`. Such source errors can occur after a header has been
written, so discard partial output after a failed encode. Invalid source
depths, unsupported target codings, incompatible channel layouts and AU
tags are checked before output. Metadata uses the same carriers as float
encoding: WAV and AIFF native text plus ID3, and FLAC native comments and
pictures.

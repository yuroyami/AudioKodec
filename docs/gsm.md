# Decode GSM 06.10

GSM 06.10, the original full-rate mobile telephone speech coding, decodes
from the two places it still turns up: WAV files, where Microsoft packs two
frames into each 65-byte block under format tag 0x0031, and QuickTime and
MP4 files, whose `agsm` sample entry carries the plain 33-byte frames.

```kotlin
val info = AudioKodec.probe(wavBytes)
println(info.codec)   // GSM

val samples = AudioKodec.decode(wavBytes).samples   // 8 kHz, one channel
```

## What is decoded

Every frame of 160 samples, in either layout, as libgsm 1.0.22, the
reference implementation by Jutta Degener and Carsten Bormann, decodes it.
The decoder is a translation of libgsm and keeps its 16-bit fixed-point
arithmetic, so on every target every sample is exactly the integer libgsm
gives, divided by 32768; as in libgsm, samples keep 13 significant bits.

A WAV file's `fact` chunk states how many samples the recording has, and the
padding the encoder added to fill the last block is dropped.

## Seeking

GSM carries its filters and pitch history from frame to frame, so no frame
decodes on its own. Seeking decodes from half a second before the target and
discards it, so the samples after a seek converge on those of a continuous
decode rather than equalling them; a seek to the start is exact.

## What is not read

Raw `.gsm` files of plain frames have no header to recognize them by, and
are not identified. AIFF-C files compressed as `GSM ` are refused by name,
since no writer at hand produces them to check how they count their frames.

| Case | Result |
|---|---|
| A WAV file stating GSM 06.10 in more than one channel | `UnsupportedFeatureException` |
| A WAV file whose samples per block are not 320 | `CorruptStreamException` |
| A plain frame without the 0xD signature nibble | `CorruptStreamException`, or in tolerant mode the end of the stream |

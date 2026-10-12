# Decode free-format MPEG audio

Use `AudioKodec.probe`, `open` or `decode` as for ordinary MP1, MP2 and MP3
files. MPEG-1, MPEG-2 and the MPEG-2.5 extension accept bitrate index zero.
`AudioInfo.bitrate` reports the implied rate, rounded to the nearest kbit/s;
slot rounding prevents recovering an encoder's exact original setting.

```kotlin
val info = AudioKodec.probe(freeFormatBytes)
println(info.bitrate)
AudioKodec.open(freeFormatBytes).use { decoder ->
    decoder.seek(decoder.format.sampleRate.toLong())
    val block = decoder.nextFrame()
}
```

Opening measures unpadded frame size from headers with matching version,
layer, sample rate and channel count. Two equal intervals confirm the size;
two complete frames can also establish it when the recording ends there.
Layer I padding adds a four-byte slot; Layers II and III add one byte.
Recognized embedded ID3 tags are skipped and do not enter the measured size.
Sync-shaped payload words must pass the same following-header check.

The search for a confirming boundary is limited to 512 KiB. A lone raw frame
does not supply enough evidence, and raises an `AudioKodecException` rather
than inventing a length. Opening walks the recording even when Xing metadata
stores a frame count, validating constant spacing and deriving the duration
from complete frames. This can read the whole file. Known and unknown source
sizes are supported. Frame allocations obey `DecodeOptions.maxAllocationBytes`.

Strict mode rejects later changes of spacing or bitrate-index kind and reports
a truncated final payload. Tolerant mode retains the complete prefix. Normal
terminal zero padding and recognized trailers are accepted. Protected frame
CRCs follow the same strict and tolerant policy as ordinary MPEG audio.

LAME gapless delay and padding and the silent Xing frame are handled as usual.
A valid Xing table permits sample-accurate far seeks with
`allowSlowSeek = false`. Without an index, that option permits only bounded
preroll and previously indexed positions; it refuses a required long walk
before changing playback state. Tag edits retain the coded audio bytes.

[MP4 MPEG tracks](mpeg-in-mp4.md) already carry packet lengths, so one complete
free-format frame per packet needs no initial spacing search. Unpadded size
must remain constant. MP4 edit lists supply trimming, and indexed seeking
works with slow seeking disabled.

LAME creates the external MP3 fixtures; libmpg123's public float API supplies
independent PCM and exact duration. Comparisons use maximum sample error
`1e-4` for Layers I/III and `1e-5` for Layer II, with RMS below `1e-5`.
The qualified FFmpeg version rejects these free-format MP3 recordings.
At MPEG-2.5 rates the oracle fixtures use 320 kbps to fit libmpg123's own
frame-size limit; other rates use 640 kbps. Common tests separately cover
larger frames. No oracle implementation source is read.

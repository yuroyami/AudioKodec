# Play chained Ogg recordings

Concatenating complete Ogg recordings produces sequential links. AudioKodec
plays every compatible Vorbis, Opus or FLAC link through one `AudioDecoder`.
The next link starts after the previous link's last audible frame. Each link
uses its own headers, decoder state, granule origin, pre-skip and end trim.

```kotlin
val bytes = firstRecording + secondRecording
AudioKodec.open(bytes).use { decoder ->
    println(decoder.durationFrames) // Sum of the playable link durations.
    decoder.seek(24_000)            // Position in the complete recording.
    while (true) {
        val frame = decoder.nextFrame() ?: break
        consume(frame.samples, frame.sampleCount, frame.positionFrames)
    }
}
```

Supply `consume` with your playback or processing function. Positions and
seek targets count sample frames across the whole chain, after each link's
trimming. A known duration clamps seeks past the end. If a link's duration
is unknown, the complete duration is also unknown. Seeking beyond that link's
start requires a forward decode, deferred until the next frame is requested.
`DecodeOptions(allowSlowSeek = false)` refuses that seek before changing the
position. Seeks within links retain the codec's existing preroll behaviour.

The sample rate, channel count and channel layout must agree across links.
A change throws `UnsupportedFeatureException` naming
`chained Ogg output format changes` during probing or opening, including in
tolerant mode. Compatible codecs may differ between links; `AudioInfo.codec`
is `UNKNOWN` for such a chain. Its fixed float output format still applies.
Uniform FLAC chains expose `IntPcmAccess` when every link has the same bit
depth, including 32-bit samples. Mixed FLAC depths use float output and report
no single bit depth.

Opening walks the links before playback. Retained headers respect
`maxAllocationBytes`; expanded Vorbis setup is kept only for the active link.
For a multiplexed link, AudioKodec selects its first recognised audio stream.
Every stream in the preceding group must reach EOS before a new group starts.
RFC 3533 requires distinct serial numbers throughout a chained file.
AudioKodec additionally accepts serial reuse across complete BOS/EOS boundaries
for compatibility with concatenated recordings.

`probe` and `readTags` leave a supplied source open. The decoder owns it and
closes it once when the decoder closes; advancing or seeking between links
does not close the shared source. Strict decoding reports damaged links.
Tolerant decoding keeps the complete prefix and stops at the first damaged
link or packet; it does not skip ahead to later recordings.

The tag API reads and edits the first link's metadata. Rewriting its comments
preserves every later link byte for byte and leaves the combined PCM intact.
Multiplexed tag writing remains unsupported.

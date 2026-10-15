# Get gapless playback right

**Depends on** `io.github.yuroyami:audiokodec:0.1.0`. See [Install](../index.md#install).

A live album or a DJ mix should play from one track into the next with nothing between
them. Two things can break that. Lossy encoders add silence: a few hundred to a few thousand
frames of priming at the start and padding to a whole block at the end. And a player can add
its own gap by closing and reopening the output between tracks.

AudioKodec handles the first. When a file says how much of its start and end is codec
padding, decoding leaves that out, so each track decodes to exactly the frames that were
encoded. Your part is the second: play every frame it returns, add nothing, trim nothing,
and keep one output open from track to track.

```kotlin
--8<-- "GaplessSample.kt:imports"

--8<-- "GaplessSample.kt:playlist"
```

`output` stands for whatever writes to your audio device; see
[Play decoded audio](play-audio.md). Open the device once, before the first track, and
close it after the last.

## Which files carry it

| File | What AudioKodec trims |
|---|---|
| MP3 with a LAME header | The encoder delay and padding the LAME header states, and the decoder's own delay |
| AAC in MP4 or M4A | Whatever the edit list leaves out, or the delay and padding of the iTunes `iTunSMPB` record when no edit list trims |
| Opus, in Ogg, WebM or Matroska | The pre-skip and the end trim |
| Ogg Vorbis | The padding of the last block, from the last page's position |
| FLAC, WAV, AIFF, ALAC and the other lossless codings | Nothing: they store the exact length |
| Raw AAC in ADTS | Nothing can be trimmed: ADTS has nowhere to say it, so it decodes to whole 1024-frame blocks |

The documentation tests cut one signal into three tracks at arbitrary frames and check that
each decodes to exactly its own length as FLAC, as MP3 from LAME, as Opus and as Vorbis, and
that AAC in MP4 decodes to the length its edit list states.

## Tracks at different rates

One output plays one sample rate. If a track differs from the rest, convert it with
[Change the sample rate](../resample.md) before it reaches the output. Reopening the output
at the new rate works, but it is no longer gapless.

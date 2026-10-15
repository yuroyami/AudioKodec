# Tasks

One short page per job, each ending in code you can paste. Every code sample on these pages
is included from a test that compiles against the published API and runs in the build, so
what you read is what was run.

| I want to | Page |
|---|---|
| Turn a file into samples | [Decode a file to PCM](decode.md) |
| Play or process a long file without loading it, or start partway in | [Stream and seek](stream-and-seek.md) |
| Play an album with no gap or click between tracks | [Get gapless playback right](gapless.md) |
| Show a file's title, artist and other tags | [Read tags](read-tags.md) |
| Change tags without re-encoding | [Edit tags](edit-tags.md) |
| Show or replace the cover | [Read and write cover art](cover-art.md) |
| Write a lossless file | [Encode FLAC](encode-flac.md) |
| Read and write files on disk | [Use kotlinx-io](kotlinx-io.md) |
| Hear the result on Android, Apple platforms, the JVM or a browser | [Play decoded audio](play-audio.md) |
| Normalize volume or write ReplayGain tags | [Measure loudness](../loudness.md) |
| Draw an overview of a file | [Draw a waveform](../waveform.md) |
| Match an output device's rate | [Change the sample rate](../resample.md) |

In the samples, `bytes` is a whole audio file in a `ByteArray`, `samples` is interleaved
Float32 audio, and `path` is a kotlinx-io `Path` to a file.

The pages assume you have never used AudioKodec. The [guides](../decoding.md) go further
into each subject.

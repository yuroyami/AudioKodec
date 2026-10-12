# Module audiokodec-io

The optional kotlinx-io bridge. Reads a `Path` or a `RawSource` into an
`AudioSource`, and writes an encoder's output to a `RawSink` or a `Path`.
`Path.openAudioSource()` instead uses a read-only, seekable file handle with
bounded memory, including recordings above the buffering helper's ceiling.

The core never touches the filesystem, which is what lets it build for every
Kotlin target. This module adds the file plumbing, and it builds for the same
22 targets as the core.

The JavaScript variant supports the buffer and raw stream bridges in browsers
and Node. Path and filesystem helpers need a host filesystem and work on Node;
they are unavailable in a browser. Seekable handles work on the JVM, Android,
Native, Node.js 20.16 or later, and WASI with a preopened directory.

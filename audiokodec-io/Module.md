# Module audiokodec-io

The optional kotlinx-io bridge. Reads a `Path` or a `RawSource` into an
`AudioSource`, and writes an encoder's output to a `RawSink` or a `Path`.

The core never touches the filesystem, which is what lets it build for every
Kotlin target. This module adds the file plumbing for the targets kotlinx-io
publishes.

The JavaScript variant supports the buffer and raw stream bridges in browsers
and Node. Path and filesystem helpers need a host filesystem and work on Node;
they are unavailable in a browser.

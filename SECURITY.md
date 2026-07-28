# Security

## Reporting

Report a vulnerability through GitHub's private advisory form on this
repository. Please do not open a public issue for one.

## What counts

KiteAudio parses files that arrive from anywhere, so the interesting failures
are the ones a crafted file can cause:

- A crash, hang or unbounded allocation from any input.
- Any throwable that is not a `KiteAudioException`, which the library promises
  never to let escape.
- A read outside the bounds of a buffer.

A file that fails to decode with a `KiteAudioException` is working as
intended, not a vulnerability.

## What the library guarantees

- Every failure is a `KiteAudioException`.
- Every buffer sized from a value in the file is checked against
  `DecodeOptions.maxAllocationBytes` before it is allocated.
- No decoder reads outside the audio payload the container declares.
- The core has no dependency beyond `kotlin-stdlib`, no filesystem access and
  no network access.

# Security

## Reporting

Report a vulnerability through GitHub's private advisory form on this
repository. Please do not open a public issue for one.

## What counts

AudioKodec parses files that arrive from anywhere, so the interesting failures
are the ones a crafted file can cause:

- A crash, hang or unbounded allocation from any input.
- A throwable other than an `AudioKodecException` that the input data causes.
- A read outside the bounds of a buffer.

A file that fails to decode with an `AudioKodecException` is working as
intended, not a vulnerability.

## What the library guarantees

- Every failure that the input data causes is an `AudioKodecException`. A wrong
  argument throws `IllegalArgumentException`, and a failure inside a source or
  sink that the caller supplied keeps its own type.
- Every buffer sized from a value in the file is checked against
  `DecodeOptions.maxAllocationBytes` before it is allocated.
- No decoder reads outside the audio payload the container declares.
- The core has no dependency beyond `kotlin-stdlib`, no filesystem access and
  no network access.

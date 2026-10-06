# Contributing

## Before writing code

Read [reference/REFERENCES.md](reference/REFERENCES.md). It lists the source
trees this project may be written from and the ones it must never open. That
boundary is what keeps the Apache-2.0 licence honest, and a contribution that
crosses it cannot be accepted whatever its quality.

Prefer implementing from a specification over porting from an implementation,
even a permissive one. The result reads like Kotlin instead of like translated
C.

## What a format needs before it is merged

1. Tests that compare against another implementation, not against
   hand-written expectations. Lossless codings must match bit for bit. Lossy
   ones need a stated numeric bound and a comment explaining the number.
2. Malformed-input coverage: truncation at every interesting boundary, and
   mutation of the header fields. No input may produce a throwable outside
   `AudioKodecException`, and none may hang. A new container goes into the
   shared sweep in `HostileInputTest` rather than getting its own hand-written
   cases.
3. Seeking and duration tests where the format supports them.
4. Tag round-trips where the format carries tags, with the audio payload
   proved byte-identical across a tag write.
5. An entry in [PORTING_STATUS.md](PORTING_STATUS.md), and a README update if
   the README's claims change.
6. A page in `docs/` for anything a caller would have to guess at.
7. `./gradlew updateLegacyAbi` run and the result committed.

A decoder ships before its encoder, always. The decoder is the encoder's first
test harness, and an encoder whose output nothing can read is untestable.

## Order of work

Finish what is started before starting the next thing. A group of related
issues is a label, and the tracking issue inside it, where one exists, holds
the order and the hazards. Do not open work in the next group while the
current one has a failing test on any target.

The library uses `0.x` versions with an honest feature table. `1.0.0` waits for
the container and codec set the README lists to be complete, with no known
conformance failure open against any of it.

## Dependencies

Latest versions are fine, including alpha and beta ones, as long as every
target still builds and the suite still passes on all of them.

## Style

[KITE.md](../KITE.md) in the workspace root governs the documentation, and it
is binding. The two rules that catch people out: no em dashes anywhere, and no
claim that the committed code does not support.

Code follows the official Kotlin style. Spelling is British throughout the
repository.

## Running the tests

```
./gradlew :audiokodec:jvmTest
./gradlew :audiokodec:jsNodeTest :audiokodec:wasmJsNodeTest :audiokodec:wasmWasiNodeTest
./gradlew :audiokodec:macosArm64Test
```

The oracle suites compare against three outside tools: `ffmpeg` and `ffprobe`
for most formats, the reference `flac` tools for FLAC, and Apple's `afconvert`
for Core Audio Format files on macOS. Each suite skips itself when its tool is
missing, so a green run on a machine without them is proving less than it
looks. Install all three before trusting one.

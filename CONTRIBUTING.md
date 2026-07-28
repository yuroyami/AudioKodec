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
   `KiteAudioException`, and none may hang.
3. Seeking and duration tests where the format supports them.
4. An entry in [PORTING_STATUS.md](PORTING_STATUS.md), and a README update if
   the README's claims change.
5. `./gradlew updateLegacyAbi` run and the result committed.

## Style

[KITE.md](../KITE.md) in the workspace root governs the documentation, and it
is binding. The two rules that catch people out: no em dashes anywhere, and no
claim that the committed code does not support.

Code follows the official Kotlin style. Spelling is British throughout the
repository.

## Running the tests

```
./gradlew :kiteaudio:jvmTest
./gradlew :kiteaudio:jsNodeTest :kiteaudio:wasmJsNodeTest :kiteaudio:wasmWasiNodeTest
./gradlew :kiteaudio:macosArm64Test
```

The oracle suite needs ffmpeg on the path and skips itself without it. Install
it before trusting a green run.

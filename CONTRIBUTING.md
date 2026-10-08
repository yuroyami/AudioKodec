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

The oracle suites compare against outside tools: `ffmpeg` and `ffprobe`
for most formats, the reference `flac` tools for FLAC, and Apple's `afconvert`
for Core Audio Format files on macOS. Missing tools produce explicit JUnit
skips, which must be reported separately from comparisons that ran. CI prepares
the Linux and macOS dependencies with `.github/scripts/prepare-codec-oracles.sh`
and checks JVM reports with `.github/scripts/check-oracle-results.py`. Linux
permits the five macOS-only `afconvert` skips; the macOS job runs them as well.

The ID3 identity and mixed-artwork suites also run Mutagen through Python. It reports an actual
test skip when the module is unavailable. Use an isolated interpreter:

```sh
python3 -m venv /tmp/audiokodec-tag-oracle
/tmp/audiokodec-tag-oracle/bin/python -m pip install mutagen==1.47.0
AUDIOKODEC_MUTAGEN_PYTHON=/tmp/audiokodec-tag-oracle/bin/python ./gradlew :audiokodec:jvmTest
```

Mutagen is a test executable only. Its implementation source must stay
unopened under the same boundary as every other copyleft oracle.

The native text suite also needs ExifTool on `PATH` to read every repeated
RIFF INFO and AIFF text chunk. It reports an actual skip when ExifTool is
missing. On macOS, install it with `brew install exiftool`. Only its public
command-line interface is used; implementation source stays unopened.

The R128 suite also calls libopusfile's documented tag and gain API through
Python. Install libopusfile or set `AUDIOKODEC_OPUSFILE_LIBRARY` to its shared
library. A missing library produces an actual test skip; a configured library
that cannot load fails the test. On macOS:

```sh
brew install opusfile
AUDIOKODEC_OPUSFILE_LIBRARY="$(brew --prefix opusfile)/lib/libopusfile.0.dylib" \
AUDIOKODEC_MUTAGEN_PYTHON=/tmp/audiokodec-tag-oracle/bin/python ./gradlew :audiokodec:jvmTest
```

Only its public API is used. No implementation source is read.

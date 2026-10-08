# Published FLAC conformance files

The FLAC decoder testbench is published by the IETF CELLAR working group at
https://github.com/ietf-wg-cellar/flac-test-files. The corpus is pinned to commit
`aa7b0c6cf32994c106ae517a08134c28a96ff5b2`; its 86 FLAC files and documentation
are licensed under CC0-1.0 by Martijn van Beurden. The complete licence is in
`LICENSE.txt` and `reference/REFERENCES.md`.

`manifest.tsv` records every file's size and SHA-256. Two small vectors are
committed: a legal Rice escape with zero residual bits, and a faulty metadata
length. The complete corpus occupies about 310 MB, including an 87 MB metadata
stress file, so the remaining files are downloaded into the ignored build cache:

```sh
python3 .github/scripts/prepare-flac-conformance.py
./gradlew :audiokodec:jvmTest --tests '*FlacConformanceTest*'
```

Use `--destination PATH` and `AUDIOKODEC_FLAC_CONFORMANCE_DIR=PATH` to reuse a
cache elsewhere. `--verify-only` checks a prepared cache without network access.
Tests verify each vector before using it. An explicitly configured incomplete
or changed cache fails; unconfigured missing vectors produce individual JUnit
skips. Both committed vectors remain available without downloading the rest.

The subset and supported uncommon vectors compare exact integer PCM against
the reference `flac` executable, including non-byte-aligned depths and 32-bit
values. Duration, stored MD5 and seeking are checked as applicable. The six
uncommon vectors that change stream shape or omit FLAC metadata must produce a
typed refusal. The eleven faulty vectors exercise bounded progress and typed
failures in strict and tolerant modes. These outcomes are reported separately
from successful PCM comparisons.

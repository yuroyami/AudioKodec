#!/usr/bin/env python3
"""Fetch the pinned, CC0-licensed FLAC corpus and verify every file."""

import argparse
import concurrent.futures
import hashlib
import os
from pathlib import Path, PurePosixPath
import shutil
import tempfile
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
COMMITTED = ROOT / "test-data/flac-conformance"
COMMIT = "aa7b0c6cf32994c106ae517a08134c28a96ff5b2"
BASE = f"https://raw.githubusercontent.com/ietf-wg-cellar/flac-test-files/{COMMIT}/"


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def manifest():
    text = (COMMITTED / "manifest.tsv").read_text(encoding="utf-8")
    if f"# commit: {COMMIT}\n" not in text:
        raise ValueError("the corpus manifest and downloader pins disagree")
    entries = []
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        name, length, digest = line.split("\t")
        path = PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts or "\\" in name:
            raise ValueError(f"invalid corpus path: {name}")
        if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
            raise ValueError(f"invalid SHA-256: {name}")
        entries.append((name, int(length), digest))
    if len({name for name, _, _ in entries}) != len(entries):
        raise ValueError("duplicate corpus paths")
    return entries


def matches(path, length, digest):
    return path.is_file() and path.stat().st_size == length and sha256(path) == digest


def prepare(entry, destination, verify_only):
    name, length, digest = entry
    target = destination / name
    if matches(target, length, digest):
        return
    if verify_only:
        raise ValueError(f"missing or changed corpus file: {target}")
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=target.parent, suffix=".part", delete=False) as stream:
        partial = Path(stream.name)
        try:
            committed = COMMITTED / name
            if matches(committed, length, digest):
                with committed.open("rb") as source:
                    shutil.copyfileobj(source, stream)
            else:
                url = BASE + urllib.parse.quote(name, safe="/")
                with urllib.request.urlopen(url, timeout=30) as source:
                    shutil.copyfileobj(source, stream)
            stream.flush()
            if not matches(partial, length, digest):
                raise ValueError(f"downloaded corpus checksum mismatch: {name}")
            stream.close()
            partial.replace(target)
        finally:
            partial.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path, default=ROOT / "audiokodec/build/flac-conformance")
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--jobs", type=int, default=6)
    args = parser.parse_args()
    if not 1 <= args.jobs <= 16:
        parser.error("--jobs must be between 1 and 16")
    destination = args.destination.resolve()
    entries = manifest()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as executor:
        list(executor.map(lambda entry: prepare(entry, destination, args.verify_only), entries))
    vectors = sum(name.endswith(".flac") for name, _, _ in entries)
    print(f"Verified {vectors} FLAC vectors at {COMMIT}: {destination}")
    if os.environ.get("GITHUB_ENV"):
        with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as output:
            output.write(f"AUDIOKODEC_FLAC_CONFORMANCE_DIR={destination}\n")


if __name__ == "__main__":
    main()

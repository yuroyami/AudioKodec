"""Verify attribution in built distributions, including source archives and POMs."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--framework", action="append", default=[], type=Path)
parser.add_argument("--package", action="append", default=[], type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
canonical = {name: (root / name).read_bytes() for name in ("LICENSE", "NOTICE")}
assert b"Copyright 2001-2023 Xiph.Org" in canonical["NOTICE"]
assert b"Copyright (c) 2011 Apple Inc." in canonical["NOTICE"]
failures = []
checked = 0


def verify(path, read):
    global checked
    for name, expected in canonical.items():
        try:
            actual = read(name)
        except (KeyError, FileNotFoundError):
            failures.append(f"{path}: missing {name}")
        else:
            if actual != expected:
                failures.append(f"{path}: {name} differs from the canonical text")
    checked += 1


for module in ("audiokodec", "audiokodec-io"):
    build = root / module / "build"
    archives = sorted((build / "libs").glob("*.jar")) + sorted((build / "libs").glob("*.klib"))
    archives += sorted((build / "outputs" / "aar").glob("*.aar"))
    if not archives:
        failures.append(f"{module}: no built archives to verify")
    for path in archives:
        with zipfile.ZipFile(path) as archive:
            verify(path, lambda name: archive.read(f"META-INF/{name}"))
    poms = sorted((build / "publications").glob("*/pom-default.xml"))
    if not poms:
        failures.append(f"{module}: no generated POMs to verify")
    for path in poms:
        pom = ET.parse(path).getroot()
        urls = [element.text for element in pom.findall("{*}licenses/{*}license/{*}url")]
        if not any("apache.org/licenses/LICENSE-2.0" in (url or "") for url in urls):
            failures.append(f"{path}: missing the Apache-2.0 licence")
        if not any("bsd-3-clause" in (url or "").lower() for url in urls):
            failures.append(f"{path}: missing the libopus BSD-3-Clause licence")
        checked += 1

for path in args.framework:
    verify(path, lambda name: (path / "Resources" / name).read_bytes())
for path in args.package:
    verify(path, lambda name: (path / name).read_bytes())
if failures:
    raise SystemExit("Distribution attribution failed:\n" + "\n".join(failures))
print(f"Distribution attribution verified in {checked} archives, packages, frameworks and POMs")

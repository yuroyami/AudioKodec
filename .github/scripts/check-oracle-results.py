"""Reject missing comparisons and unexpected skips in a full JVM CI run."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

reports = Path("audiokodec/build/test-results/jvmTest")
required = {
    "AacDecodeOracleTest", "AdtsOracleTest", "AiffCOracleTest", "AlacOracleTest", "AlacEncodeOracleTest", "BextOracleTest", "CafOracleTest", "FfmpegOracleTest",
    "FfmpegTagOracleTest", "FlacEncodeOracleTest", "FlacOracleTest", "FlacConformanceTest",
    "IntegerEncodingOracleTest", "Mp4OracleTest", "Mp4GaplessOracleTest", "Mp4TagOracleTest", "MpegMp4OracleTest", "MpegCrcOracleTest", "MpegFreeFormatOracleTest", "MpegOracleTest", "OggOpusDecodeTest",
    "OggOracleTest", "OggChainOracleTest", "OpusConformanceTest", "OpusDecodeOracleTest",
    "OpusJoinOracleTest", "OpusMultistreamOracleTest", "OpusOracleTest",
    "SilkDecodeOracleTest", "VorbisOracleTest", "WavPackOracleTest", "ApeOracleTest", "TtaOracleTest", "MusepackOracleTest", "SpeexOracleTest", "AmrOracleTest", "GsmOracleTest", "VorbisFloor0OracleTest", "MutagenTagOracleTest",
    "NativeTextTagOracleTest", "NativeTextEncodingOracleTest", "R128TagOracleTest",
}
apple_only = {
    ("AiffCOracleTest", "reversedIntegerTypesAgreeWithApplesIntegerReader"),
    ("AiffCOracleTest", "appleFilesPreserveLengthSamplesAndSeeking"),
    ("CafOracleTest", "theUncompressedCafFormatsDecodeExactly"),
    ("CafOracleTest", "theCompandingLawsInCafDecodeWithinTheirAccuracy"),
    ("CafOracleTest", "quickTimeAdpcmInsideCafDecodes"),
    ("Mp4GaplessOracleTest", "appleAfconvertFilesDecodeToExactlyWhatWentIn"),
    ("AlacEncodeOracleTest", "applesAfconvertDecodesExactlyWhatWentIn"),
    ("AacDecodeOracleTest", "appleEncodedFilesDecodeAsAppleDecodesThem"),
}
seen = set()
failures = []
passed = skipped = 0
for report in reports.glob("TEST-*.xml"):
    suite = ET.parse(report).getroot()
    name = suite.attrib["name"].split(".")[-1].split("[")[0]
    if name in required:
        seen.add(name)
        if not any(all(case.find(outcome) is None for outcome in ("skipped", "failure", "error"))
                   for case in suite.findall("testcase")):
            failures.append(f"{name}: no comparisons ran")
    if name == "FlacConformanceTest":
        manifest = Path("test-data/flac-conformance/manifest.tsv")
        vectors = [line.split("\t")[0] for line in manifest.read_text().splitlines()
                   if line and not line.startswith("#") and line.split("\t")[0].endswith(".flac")]
        cases = [case.attrib["name"] for case in suite.findall("testcase")]
        for vector in vectors:
            if not any(f"[{vector}]" in case for case in cases):
                failures.append(f"FlacConformanceTest: missing published vector {vector}")
    for case in suite.findall("testcase"):
        method = case.attrib["name"].split("[")[0]
        if case.find("skipped") is not None:
            skipped += 1
            if sys.platform == "darwin" or (name, method) not in apple_only:
                failures.append(f"{name}.{method}: unexpected skip")
        elif case.find("failure") is not None or case.find("error") is not None:
            failures.append(f"{name}.{method}: failed")
        else:
            passed += 1
failures += [f"{name}: missing test report" for name in sorted(required - seen)]
if failures:
    raise SystemExit("Oracle coverage failed:\n" + "\n".join(failures))
print(f"JVM coverage verified: {passed} passed, {skipped} explicit platform skips; all required oracle suites reported")

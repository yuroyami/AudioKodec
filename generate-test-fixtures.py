#!/usr/bin/env python3
"""Regenerate JVM fixtures and record their executable and source provenance."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import urllib.parse
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent


def checksum(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n")


def source_files():
    """Owned recipes only. Never traverse external implementation source trees."""
    files = [ROOT / "generate-test-fixtures.py", ROOT / "gradlew", ROOT / "gradlew.bat",
             ROOT / "gradle.properties", ROOT / "settings.gradle.kts", ROOT / "build.gradle.kts",
             ROOT / "reference/REFERENCES.md"]
    for directory in ("audiokodec/src", "audiokodec-io/src", ".github/scripts", "gradle", "test-data"):
        files.extend(path for path in (ROOT / directory).rglob("*") if path.is_file() and "__pycache__" not in path.parts)
    files.extend(ROOT.glob("*/build.gradle.kts"))
    return {str(path.relative_to(ROOT)): {"bytes": path.stat().st_size, "sha256": checksum(path)}
            for path in sorted(set(files)) if path.is_file()}


def check_process_coverage():
    unrecorded = []
    for source in (ROOT / "audiokodec/src/jvmTest").rglob("*.kt"):
        if source.name != "OracleProcess.kt" and re.search(r"\.start\s*\(\s*\)", source.read_text()):
            unrecorded.append(str(source.relative_to(ROOT)))
    if unrecorded:
        raise SystemExit("Unrecorded test processes; use oracleStart(): " + ", ".join(unrecorded))


def corpus_inputs():
    manifest = ROOT / "test-data/flac-conformance/manifest.tsv"
    lines = manifest.read_text().splitlines()
    commit = next(line.split(": ", 1)[1] for line in lines if line.startswith("# commit:"))
    files = []
    for line in lines:
        if not line or line.startswith("#"):
            continue
        name, size, digest = line.split("\t")
        files.append({"path": name, "bytes": int(size), "sha256": digest,
                      "url": f"https://raw.githubusercontent.com/ietf-wg-cellar/flac-test-files/{commit}/" + urllib.parse.quote(name, safe="/")})
    return {"flac": {"commit": commit, "manifest": str(manifest.relative_to(ROOT)),
                     "manifestSha256": checksum(manifest), "files": files,
                     "reproduceArgv": ["python3", ".github/scripts/prepare-flac-conformance.py"]},
            "opus": {"url": "https://opus-codec.org/static/testvectors/opus_testvectors-rfc8251.tar.gz",
                     "sha256": "6b26a22f9ba87b2b836906a9bb7afec5f8e54d49553b1200382520ee6fedfa55",
                     "reproduceArgv": ["bash", ".github/scripts/prepare-codec-oracles.sh"]}}


def reference_builds():
    builds = {}
    for name in ("libopus", "alac"):
        directory = ROOT / "reference" / name
        if directory.is_dir():
            result = subprocess.run(["git", "-C", str(directory), "rev-parse", "HEAD"],
                                    text=True, capture_output=True)
            if result.returncode == 0:
                builds[name] = {"commit": result.stdout.strip(),
                                "sourceChanges": subprocess.check_output(["git", "-C", str(directory), "diff", "--name-only"], text=True).splitlines()}
    return builds


def summarise(output):
    starts, finishes, pipes = {}, {}, {}
    for journal in sorted(output.glob("processes-*.jsonl")):
        for line in journal.read_text().splitlines():
            record = json.loads(line)
            key = record["id"]
            if record["event"] == "start":
                if key in starts:
                    raise ValueError(f"duplicate process start: {key}")
                starts[key] = record
            elif record["event"] in ("finish", "startFailure"):
                if key in finishes:
                    raise ValueError(f"duplicate process completion: {key}")
                finishes[key] = record
            elif record["event"] == "pipe":
                pipes.setdefault(key, []).append(record)
            else:
                raise ValueError(f"unknown process event: {record['event']}")
    if not starts:
        raise ValueError("no oracle processes were recorded; tests did not regenerate fixtures")
    if set(starts) != set(finishes) or not set(pipes) <= set(starts):
        raise ValueError("missing process starts or completions in the recording")
    invocations, tools = [], {}
    for key, start in starts.items():
        invocation = {**start, **finishes[key], "pipes": pipes.get(key, [])}
        invocation.pop("event")
        invocations.append(invocation)
        tool = start["tool"]
        tools[(tool["path"], tool["sha256"], tool["version"])] = tool
    save(output / "commands.json", invocations)
    save(output / "tools.json", sorted(tools.values(), key=lambda tool: tool["path"]))
    reports = []
    for module in ("audiokodec", "audiokodec-io"):
        for file in sorted((output / "test-results" / module).glob("TEST-*.xml")):
            suite = ET.parse(file).getroot()
            reports.append({"suite": suite.attrib["name"], **{key: int(suite.get(key, "0"))
                            for key in ("tests", "failures", "errors", "skipped")}})
    if not reports or not any(report["tests"] > report["skipped"] for report in reports):
        raise ValueError("no completed test cases were reported")
    result = {"invocations": len(invocations), "tools": len(tools), "testReports": reports,
              "generatedFiles": sum(len(call.get("filesAfter", [])) for call in invocations)}
    save(output / "summary.json", result)
    print(f"Recorded {len(invocations)} oracle invocations from {len(tools)} tools in {output}")
    return invocations, list(tools.values()), result


def normalise_calls(calls, run):
    """Replace checkout/cache paths and fresh temporary names, never file bytes."""
    replacements = sorted(((str(ROOT), "${ROOT}"), (run["temporaryDirectory"], "${TMP}"),
                           (run["flacDirectory"], "${FLAC_CORPUS}")), key=lambda item: -len(item[0]))

    def path(value):
        for prefix, token in replacements:
            value = value.replace(prefix, token)
        return re.sub(r"(?<=/)audiokodec-[^/]*-\d+(?=/)",
                      lambda match: match[0].rsplit("-", 1)[0] + "-${FRESH}", value)

    normalised = []
    for call in calls:
        tool = call["tool"]
        # Compiler outputs embed platform build details; their command and
        # source inputs are recorded, but compare audio/data outputs here.
        before = {entry["path"]: entry for entry in call["filesBefore"]}
        audio = []
        inputs = []
        compiled_output = None
        if Path(tool["path"]).name in ("cc", "c++", "gcc", "clang", "clang++") and "-o" in call["argv"]:
            candidate = Path(call["argv"][call["argv"].index("-o") + 1])
            compiled_output = str(candidate if candidate.is_absolute() else Path(call["workingDirectory"]) / candidate)

        def data_file(entry):
            return entry["path"] != compiled_output and Path(entry["path"]).suffix.lower() != ".log"

        for entry in call["filesBefore"]:
            if data_file(entry):
                inputs.append({**entry, "path": path(entry["path"])})
        for entry in call.get("filesAfter", []):
            if data_file(entry):
                if before.get(entry["path"]) != entry:
                    audio.append({**entry, "path": path(entry["path"])})
        normalised.append({"argv": [path(argument) for argument in call["argv"]],
                           "toolSha256": tool["sha256"], "toolVersion": tool["version"],
                           "inputs": inputs, "outputs": audio})
    return sorted(normalised, key=lambda call: json.dumps(call, sort_keys=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "audiokodec/build/fixture-provenance")
    parser.add_argument("--tests", action="append", help="JVM test filter, repeatable; default runs both full JVM suites")
    parser.add_argument("--summarise-only", action="store_true", help="validate and summarise an existing recording")
    parser.add_argument("--compare", type=Path, help="require the same tool identities, commands and generated audio hashes as another recording")
    parser.add_argument("--write-tool-lock", type=Path, help="save the versions and binary hashes checked in this run")
    args = parser.parse_args()
    output = args.output.resolve()
    check_process_coverage()
    if not args.summarise_only:
        if output.exists() and any(output.iterdir()):
            raise SystemExit(f"Recording directory must be empty to avoid mixing runs: {output}")
        output.mkdir(parents=True, exist_ok=True)
        environment = os.environ.copy()
        environment["AUDIOKODEC_FIXTURE_RECORDING_DIR"] = str(output)
        temporary = output / "temporary"
        temporary.mkdir()
        flac_directory = Path(environment.get("AUDIOKODEC_FLAC_CONFORMANCE_DIR", ROOT / "audiokodec/build/flac-conformance")).resolve()
        environment["AUDIOKODEC_FLAC_CONFORMANCE_DIR"] = str(flac_directory)
        subprocess.run([sys.executable, str(ROOT / ".github/scripts/prepare-flac-conformance.py"),
                        "--destination", str(flac_directory)], cwd=ROOT, check=True)
        argv = [str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")), ":audiokodec:jvmTest", "--rerun"]
        for pattern in args.tests or []:
            argv.extend(["--tests", pattern])
        if not args.tests:
            argv.extend([":audiokodec-io:jvmTest", "--rerun"])
        # Test JVMs need their own tmpdir; Gradle's launcher property alone
        # does not configure forked Test workers.
        argv.extend([f"-PaudiokodecFixtureTmp={temporary}", "--stacktrace"])
        run = {"schema": 1, "gitCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
               "platform": platform.platform(), "python": sys.version, "sourceFiles": source_files(),
               "argv": argv, "temporaryDirectory": str(temporary), "flacDirectory": str(flac_directory),
               "referenceBuildRecipe": ".github/scripts/prepare-codec-oracles.sh",
               "referenceBuilds": reference_builds(), "corpora": corpus_inputs()}
        for variable, key in (("AUDIOKODEC_OPUSFILE_LIBRARY", "opusfileLibrary"),
                              ("AUDIOKODEC_MPG123_LIBRARY", "mpg123Library")):
            configured_library = environment.get(variable)
            if configured_library and Path(configured_library).is_file():
                library = Path(configured_library)
                run[key] = {"path": str(library), "bytes": library.stat().st_size, "sha256": checksum(library)}
        if sys.platform == "darwin":
            run["macOSBuild"] = subprocess.check_output(["sw_vers", "-buildVersion"], text=True).strip()
        save(output / "run.json", run)
        completed = subprocess.run(argv, cwd=ROOT, env=environment)
        run["gradleExitCode"] = completed.returncode
        run["finishedAt"] = datetime.now(timezone.utc).isoformat()
        for module in (["audiokodec"] if args.tests else ["audiokodec", "audiokodec-io"]):
            destination = output / "test-results" / module
            destination.mkdir(parents=True)
            for report in (ROOT / module / "build/test-results/jvmTest").glob("TEST-*.xml"):
                shutil.copyfile(report, destination / report.name)
        if not args.tests:
            run["coverageExitCode"] = subprocess.run([sys.executable, str(ROOT / ".github/scripts/check-oracle-results.py")], cwd=ROOT).returncode
        save(output / "run.json", run)
    else:
        run = json.loads((output / "run.json").read_text())
    calls, tools, summary = summarise(output)
    if run["gradleExitCode"] != 0 or run.get("coverageExitCode", 0) != 0 or any(report["failures"] + report["errors"] for report in summary["testReports"]):
        raise SystemExit("Fixture qualification failed; see the recorded commands and test reports")
    if args.compare:
        prior = args.compare.resolve()
        previous_calls = json.loads((prior / "commands.json").read_text())
        previous_run = json.loads((prior / "run.json").read_text())
        for key in ("opusfileLibrary", "mpg123Library"):
            if run.get(key, {}).get("sha256") != previous_run.get(key, {}).get("sha256"):
                raise SystemExit(f"Fixture reproduction uses a different {key} binary")
        if normalise_calls(calls, run) != normalise_calls(previous_calls, previous_run):
            save(output / "comparison-current.json", normalise_calls(calls, run))
            save(output / "comparison-previous.json", normalise_calls(previous_calls, previous_run))
            raise SystemExit("Fixture reproduction differs; inspect comparison-current.json and comparison-previous.json")
        print("Exact commands, tool identities and generated audio hashes reproduced")
    if args.write_tool_lock:
        portable_tools = []
        for tool in tools:
            name = Path(tool["path"]).name
            portable_tools.append({**tool, "path": name,
                                   "versionArgv": [name] + tool["versionArgv"][1:] if tool["versionArgv"] else []})
        save(args.write_tool_lock, {"platform": run["platform"], "macOSBuild": run.get("macOSBuild"),
                                  "qualifiedAt": run["finishedAt"],
                                  "generatorRuntimes": [json.loads(file.read_text()) for file in sorted(output.glob("runtime-*.json"))],
                                  "tools": portable_tools, "testCount": sum(report["tests"] for report in summary["testReports"]),
                                  "skipped": sum(report["skipped"] for report in summary["testReports"]),
                                  "referenceBuilds": run["referenceBuilds"],
                                  "opusfileLibrarySha256": run.get("opusfileLibrary", {}).get("sha256"),
                                  "mpg123LibrarySha256": run.get("mpg123Library", {}).get("sha256"),
                                  "referenceBuildRecipeSha256": run["sourceFiles"][".github/scripts/prepare-codec-oracles.sh"]["sha256"]})


if __name__ == "__main__":
    main()

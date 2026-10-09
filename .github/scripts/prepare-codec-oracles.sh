#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."
for executable in ffmpeg ffprobe flac metaflac lame exiftool cmake cc c++ make python3 curl git; do
    command -v "$executable" >/dev/null
done
if [[ "$(uname -s)" == Darwin ]]; then
    command -v afconvert >/dev/null
    export AUDIOKODEC_OPUSFILE_LIBRARY="$(brew --prefix opusfile)/lib/libopusfile.0.dylib"
    export AUDIOKODEC_MPG123_LIBRARY="$(brew --prefix mpg123)/lib/libmpg123.0.dylib"
else
    AUDIOKODEC_MPG123_LIBRARY=$(python3 - <<'PY'
import ctypes.util, pathlib, subprocess
name = ctypes.util.find_library('mpg123')
assert name, 'libmpg123 is required for free-format MPEG checks'
for line in subprocess.check_output(['ldconfig', '-p'], text=True).splitlines():
    if line.strip().startswith(name + ' '):
        print(pathlib.Path(line.split('=>', 1)[1].strip()).resolve())
        break
else: raise SystemExit('libmpg123 must have a resolvable binary path')
PY
)
    export AUDIOKODEC_MPG123_LIBRARY
fi

oracle_venv=${AUDIOKODEC_ORACLE_VENV:-${RUNNER_TEMP:-/tmp}/audiokodec-tag-oracle}
python3 -m venv "$oracle_venv"
"$oracle_venv/bin/python" -m pip install --disable-pip-version-check mutagen==1.47.0
export AUDIOKODEC_MUTAGEN_PYTHON="$oracle_venv/bin/python"
"$AUDIOKODEC_MUTAGEN_PYTHON" - <<'PY'
import ctypes, ctypes.util, os, mutagen
library = os.environ.get('AUDIOKODEC_OPUSFILE_LIBRARY') or ctypes.util.find_library('opusfile')
assert library, 'libopusfile is required for R128 oracle checks'
ctypes.CDLL(library)
ctypes.CDLL(os.environ['AUDIOKODEC_MPG123_LIBRARY'])
assert mutagen.version_string == '1.47.0'
print('Mutagen, libopusfile and libmpg123 are available')
PY
if [[ -n "${GITHUB_ENV:-}" ]]; then
    printf 'AUDIOKODEC_MUTAGEN_PYTHON=%s\n' "$AUDIOKODEC_MUTAGEN_PYTHON" >> "$GITHUB_ENV"
    if [[ -n "${AUDIOKODEC_OPUSFILE_LIBRARY:-}" ]]; then
        printf 'AUDIOKODEC_OPUSFILE_LIBRARY=%s\n' "$AUDIOKODEC_OPUSFILE_LIBRARY" >> "$GITHUB_ENV"
    fi
    printf 'AUDIOKODEC_MPG123_LIBRARY=%s\n' "$AUDIOKODEC_MPG123_LIBRARY" >> "$GITHUB_ENV"
fi

# Only the permissively licensed references pinned in REFERENCES.md are built.
# Existing checkouts must already match the pin and contain no source edits.
reference_checkout() {
    local directory=$1 repository=$2 commit=$3
    if [[ ! -e "$directory" ]]; then
        git clone "$repository" "$directory"
        git -C "$directory" checkout --detach "$commit"
    fi
    test "$(git -C "$directory" rev-parse HEAD)" = "$commit"
    git -C "$directory" diff --exit-code
    git -C "$directory" diff --cached --exit-code
}

reference_checkout reference/libopus https://github.com/xiph/opus.git ddbe48383984d56acd9e1ab6a090c54ca6b735a6
cmake -S reference/libopus -B reference/libopus/build-plain \
    -DOPUS_BUILD_PROGRAMS=ON -DCMAKE_BUILD_TYPE=Release -DOPUS_DRED=OFF -DOPUS_OSCE=OFF \
    -DOPUS_DISABLE_INTRINSICS=ON -DOPUS_X86_PRESUME_SSE=OFF -DOPUS_X86_PRESUME_SSE2=OFF \
    -DOPUS_X86_MAY_HAVE_SSE=OFF -DOPUS_X86_MAY_HAVE_SSE2=OFF -DOPUS_X86_MAY_HAVE_SSE4_1=OFF \
    -DOPUS_X86_MAY_HAVE_AVX2=OFF
cmake --build reference/libopus/build-plain --target opus_demo opus_compare -j4

reference_checkout reference/alac https://github.com/macosforge/alac.git c38887c5c5e64a4b31108733bd79ca9b2496d987
make -C reference/alac/codec -j4
c++ -O3 -I reference/alac/codec reference/alac/convert-utility/main.cpp \
    reference/alac/convert-utility/CAFFileALAC.cpp reference/alac/codec/libalac.a \
    -o reference/alac/convert-utility/alacconvert

reference_checkout reference/wavpack/src https://github.com/dbry/WavPack.git 5803634a030e2a11dba602ba057b89cc34486c67
cmake -S reference/wavpack/src -B reference/wavpack/build -DCMAKE_BUILD_TYPE=Release \
    -DWAVPACK_BUILD_DOCS=OFF -DWAVPACK_ENABLE_LIBCRYPTO=OFF -DBUILD_TESTING=OFF
cmake --build reference/wavpack/build --target wavpackapp wvunpack -j4

vectors=reference/opus_testvectors-rfc8251.tar.gz
if [[ ! -f "$vectors" ]]; then
    curl --fail --show-error --location --retry 2 --max-time 180 \
        https://opus-codec.org/static/testvectors/opus_testvectors-rfc8251.tar.gz -o "$vectors.part"
fi
python3 - "$vectors" <<'PY'
import hashlib, pathlib, sys
target = pathlib.Path(sys.argv[1])
download = target if target.is_file() else pathlib.Path(str(target) + '.part')
with download.open('rb') as stream:
    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
assert digest == '6b26a22f9ba87b2b836906a9bb7afec5f8e54d49553b1200382520ee6fedfa55', 'Opus vector checksum mismatch'
if download != target:
    download.rename(target)
print('Opus vector checksum verified')
PY

python3 .github/scripts/prepare-flac-conformance.py

# Seekable file input

`audiokodec-io` can open a file through a read-only, seekable handle. It does
not load the recording into memory and has no whole-file byte ceiling:

```kotlin
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.io.openAudioSource
import kotlinx.io.files.Path

AudioKodec.open(Path("recording.wav").openAudioSource()).use { decoder ->
    decoder.seek(10L * decoder.format.sampleRate)
    val block = decoder.nextFrame()
}
```

Closing the decoder closes its source and file handle. For probing or tags,
which do not take ownership, close the source yourself:

```kotlin
Path("recording.flac").openAudioSource().use { source ->
    val info = AudioKodec.probe(source)
    val tags = AudioKodec.readTags(source)
}
```

The handle captures its byte length when opened. Keep the file unchanged
while it is in use. Reads can be short and are capped at 64 KiB per call;
extra storage stays bounded independently of file length. Positions and
lengths are 64-bit, including Android Native's 32-bit targets and Windows.
Ordinary files and symlinks to ordinary files are accepted; directories
and other file types are refused. Opening failures use `kotlinx.io.IOException`;
read failures use `AudioKodecException`. Closing twice has no effect.

| Target or host | Backend |
|---|---|
| JVM and Android | `RandomAccessFile`, read-only |
| Linux and Apple Native | POSIX descriptor, `fstat` and `pread` |
| Android Native | Large-file `open64`, `fstat64` and `pread64` |
| Windows Native | Unicode `_wopen`, `_fstat64`, `_lseeki64` and binary `_read` |
| JavaScript and Wasm JS in Node.js 20.16+ | Synchronous Node filesystem calls; BigInt positions |
| Wasm WASI | Preview1 preopened directory, read-only rights and `fd_pread` |
| Browsers | Named `UnsupportedFeatureException`; buffer user-selected or downloaded bytes |

`Path.readAudioSource()` and `RawSource.readAudioSource()` retain their
whole-file buffering behavior and default 512 MiB ceiling. They remain
useful for small files, sequential streams and browser `Buffer` input.
Neither name changes, and opening a file does not silently fall back to
buffering.

## WASI directories

WASI grants access through preopened directories, advertised from descriptor
3. Paths use guest directory names. The longest matching preopen is selected;
a relative path can use a `.` preopen, and a `/` preopen can supply root-relative
paths. A path outside those directory grants fails before reading.

For Node's WASI launcher, configure its `preopens` option, for example:

```javascript
const wasi = new WASI({
    version: "preview1",
    preopens: { ".": "/host/recordings", "/library": "/host/library" },
});
```

The source requests only read, seek, tell and file-status rights. Closing an
audio source closes its opened file, leaving the preopened directory handles
available to later operations.

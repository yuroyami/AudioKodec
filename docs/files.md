# Files and kotlinx-io

The core module never touches the filesystem, which lets it build for every Kotlin target.
`audiokodec-io` adds the bridge to kotlinx-io, with browser and Node variants for JavaScript.

```kotlin
dependencies {
    implementation("io.github.yuroyami:audiokodec-io:0.1.0")
}
```

`Buffer`, `RawSource` and `RawSink` work on every host. The path helpers need a host
filesystem, and a browser does not expose one through kotlinx-io. For browser input, put the
downloaded or user-selected bytes into a `Buffer` and call `readAudioSource()`.

## Read and write a file

```kotlin
val info = Path("song.wav").openAudioSource().use { AudioKodec.probe(it) }

Path("out.wav").writeAudioAtomically { sink ->
    AudioKodec.encode(samples.asPcmSource(format), EncodeTarget.wav(24), sink)
}
```

## Seekable input

`Path.openAudioSource()` opens a seekable read-only handle on the JVM, Android, Native,
Node.js 20.16 or later, and WASI with a matching preopened directory. It reads at most 64 KiB
at a time, and its temporary storage stays bounded whatever the file size. The decoder takes
ownership of the source and closes the handle with it. [Seekable file input](file-input.md)
describes each backend and the WASI setup.

`Path.readAudioSource()` and `RawSource.readAudioSource()` load the complete contents into
memory, up to 512 MiB by default. Browser input takes this path.

## Safe replacement

`writeAudioAtomically` writes to a temporary file in a staging directory beside the
destination. It closes that file and then replaces the destination with the atomic move of
kotlinx-io. The parent directory must exist.

- A failure while writing, closing or replacing keeps the old file and removes the temporary
  output.
- A cleanup error is attached to the original failure.
- A cleanup error after a successful replacement is reported with the new file already in
  place.

Atomicity follows the platform filesystem. WASI cannot verify that guarantee.

## Streaming output

`Path.asAudioSink()` opens the file on its first non-empty write, so creating or closing an
unused sink leaves the file untouched. Once writing begins, a later failure can leave partial
output. Use `writeAudioAtomically` to replace a file that must survive a failed encode.

The kotlinx-io sink cannot go back and patch a length. WAV, AIFF and AU output through it
therefore needs a float or integer PCM source whose frame count is known.

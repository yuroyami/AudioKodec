# Use the library with kotlinx-io

**Depends on** `io.github.yuroyami:audiokodec:0.1.0` and
`io.github.yuroyami:audiokodec-io:0.1.0`. See [Install](../index.md#install).

The core library reads from and writes to its own small interfaces, `AudioSource` and
`AudioSink`, and never touches the file system. `audiokodec-io` connects them to
kotlinx-io: open a `Path` as a source, or write a file through a sink.

```kotlin
--8<-- "KotlinxIoSample.kt:imports"
```

## Read a file

`openAudioSource()` opens the file for random access and reads only what is asked of it, so
a file of any size works. `probe` and `readTags` leave the source open for you to close:

```kotlin
--8<-- "KotlinxIoSample.kt:describe"
```

A decoder takes ownership of its source and closes it, and the file, when it is closed:

```kotlin
--8<-- "KotlinxIoSample.kt:decode"
```

## Write a file

`writeAudioAtomically` writes to a temporary file beside the destination and moves it into
place only once the block has finished, so a failed encode never leaves half a file:

```kotlin
--8<-- "KotlinxIoSample.kt:write"
```

Editing tags in place follows the same pattern:

```kotlin
--8<-- "KotlinxIoSample.kt:retitle"
```

## Bytes from elsewhere

A browser has no file system for kotlinx-io. Put the bytes you downloaded or the user chose
into a `Buffer`, and read that. This holds the whole file in memory, up to 512 MiB by
default:

```kotlin
--8<-- "KotlinxIoSample.kt:buffer"
```

`openAudioSource()` works on the JVM, Android, native targets, Node.js 20.16 or later and
WASI. [Files and kotlinx-io](../files.md) and [Seekable file input](../file-input.md) have
the details for each.

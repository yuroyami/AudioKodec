# Read and write cover art

**Depends on** `io.github.yuroyami:audiokodec:0.1.0`. See [Install](../index.md#install).

Pictures are part of the tags: `readTags` returns them and `writeTags` stores them. A
picture is the image file exactly as it was stored, JPEG or PNG usually, with its MIME type
and its role, such as front cover or artist. AudioKodec does not decode images; hand the
bytes to whatever your platform uses, such as `BitmapFactory` on Android, `UIImage` on iOS
or `ImageIO` on the JVM.

## Read the cover

```kotlin
--8<-- "CoverArtSample.kt:imports"

--8<-- "CoverArtSample.kt:read"
```

## Replace the cover

```kotlin
--8<-- "CoverArtSample.kt:write"
```

`writeTags` copies the audio unchanged, as on [Edit tags](edit-tags.md). The documentation
tests write and read back a cover in FLAC, WAV, M4A and MP3 files.

## Add a cover while encoding

```kotlin
--8<-- "CoverArtSample.kt:encode"
```

## Limits

- FLAC stores each picture in its own `PICTURE` block, which must stay under 16 MiB.
- ID3 needs every picture's description to be unique, so AudioKodec numbers clashing ones,
  and allows only one picture of each file icon type.

The [Tags guide](../tags.md#pictures) has the details for each format.

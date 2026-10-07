package io.github.yuroyami.audiokodec.io

import io.github.yuroyami.audiokodec.AudioSink
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random

/**
 * Writes [block]'s audio output into a temporary file, closes it, and replaces
 * the file at [this] only when the write and close both succeed.
 *
 * The destination's parent directory must exist. A staging directory is
 * reserved beside it, so the replacement stays on the same filesystem and
 * cannot overwrite an existing temporary file. The sink moves forwards only;
 * encoding therefore needs a known frame count. [block] may also write tags.
 *
 * Replacement uses kotlinx-io's `FileSystem.atomicMove`, without a copy and
 * delete fallback. Atomicity follows the platform filesystem's guarantees;
 * WASI cannot verify whether the underlying filesystem supports atomic moves.
 * A write, close or replacement failure preserves the previous destination.
 * Cleanup errors are suppressed onto that failure. If cleanup fails after a
 * successful replacement, it is reported without reverting the new file.
 */
public fun Path.writeAudioAtomically(block: (AudioSink) -> Unit) {
    val directory = parent ?: Path(".")
    if (SystemFileSystem.metadataOrNull(directory)?.isDirectory != true) {
        throw IOException("the destination's parent directory does not exist: $directory")
    }
    val unique = Random.nextLong().toULong().toString(16) + "-" + Random.nextLong().toULong().toString(16)
    val staging = Path(directory, ".audiokodec-$unique")
    // Exclusive directory creation reserves this name before a file is opened.
    SystemFileSystem.createDirectories(staging, mustCreate = true)
    val temporary = Path(staging, "audio")
    var failure: Throwable? = null
    try {
        SystemFileSystem.sink(temporary).asAudioSink().use(block)
        SystemFileSystem.atomicMove(temporary, this)
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        var cleanupFailure = failure
        for (path in listOf(temporary, staging)) {
            try {
                SystemFileSystem.delete(path, mustExist = false)
            } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error
                else cleanupFailure.addSuppressed(error)
            }
        }
        if (failure == null && cleanupFailure != null) {
            throw IOException("the audio file was replaced, but staging cleanup failed", cleanupFailure)
        }
    }
}

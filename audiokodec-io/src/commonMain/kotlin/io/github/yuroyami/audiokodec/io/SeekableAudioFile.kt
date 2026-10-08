package io.github.yuroyami.audiokodec.io

import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.AudioSource
import kotlinx.io.IOException
import kotlinx.io.files.Path

/**
 * Opens a read-only, seekable file without loading its contents into memory.
 *
 * Supported on JVM/Android, Native, Node.js and WASI with a matching preopened
 * directory. Browsers throw [io.github.yuroyami.audiokodec.UnsupportedFeatureException];
 * use [readAudioSource] for downloaded or user-selected bytes there.
 *
 * [AudioSource.size] is captured from the opened handle. Keep the file unchanged
 * while reading it. Reads are capped at 64 KiB and temporary storage stays
 * bounded independently of file length. Closing the source releases the handle; [io.github.yuroyami.audiokodec.AudioKodec.open]
 * takes ownership and closes it with the decoder. Opening failures throw
 * [IOException]; read failures throw [AudioKodecException].
 */
public fun Path.openAudioSource(): AudioSource {
    require('\u0000' !in toString()) { "a file path must not contain a null character" }
    val handle = openFileReadHandle(this)
    if (handle.size < 0) {
        val failure = IOException("the file size cannot be represented as a signed 64-bit count: $this")
        try { handle.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
        throw failure
    }
    return SeekableFileAudioSource(handle)
}

internal interface FileReadHandle {
    val size: Long
    fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int
    fun close()
}

internal expect fun openFileReadHandle(path: Path): FileReadHandle

private class SeekableFileAudioSource(private val handle: FileReadHandle) : AudioSource {
    override val size: Long = handle.size
    private var closed = false

    override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int {
        check(!closed) { "this audio file source is closed" }
        require(position >= 0) { "position must not be negative, was $position" }
        require(offset >= 0 && count >= 0 && offset <= into.size - count) {
            "offset=$offset count=$count is out of bounds for a ${into.size}-byte array"
        }
        if (count == 0) return 0
        if (position >= size) return -1
        val requested = minOf(count.toLong(), size - position, 64L * 1024).toInt()
        val got = try {
            handle.readAt(position, into, offset, requested)
        } catch (failure: Throwable) {
            throw AudioKodecException("reading the audio file at byte $position failed", failure)
        }
        if (got !in -1..requested) throw AudioKodecException("the file handle returned an invalid $got-byte read")
        return if (got == 0) -1 else got
    }

    override fun close() {
        if (closed) return
        closed = true
        handle.close()
    }
}

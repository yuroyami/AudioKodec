package io.github.yuroyami.audiokodec.io

import kotlinx.io.IOException
import kotlinx.io.files.Path

internal actual fun openFileReadHandle(path: Path): FileReadHandle {
    val descriptor = openNativeReadOnly(path.toString())
    if (descriptor < 0) throw IOException("could not open the audio file: $path")
    return try {
        val length = nativeFileSize(descriptor)
        if (length < 0) throw IOException("not a readable regular audio file: $path")
        object : FileReadHandle {
            override val size: Long = length
            override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int {
                val got = readNativeFile(descriptor, position, into, offset, count)
                if (got < 0) throw IOException("reading the audio file failed: $path")
                return got
            }
            override fun close() {
                if (closeNativeFile(descriptor) != 0) throw IOException("closing the audio file failed: $path")
            }
        }
    } catch (failure: Throwable) {
        if (closeNativeFile(descriptor) != 0) failure.addSuppressed(IOException("closing the audio file failed: $path"))
        throw failure
    }
}

internal expect fun openNativeReadOnly(path: String): Int
internal expect fun nativeFileSize(descriptor: Int): Long
internal expect fun readNativeFile(descriptor: Int, position: Long, into: ByteArray, offset: Int, count: Int): Int
internal expect fun closeNativeFile(descriptor: Int): Int

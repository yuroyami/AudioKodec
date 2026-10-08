package io.github.yuroyami.audiokodec.io

import java.io.File
import java.io.RandomAccessFile
import kotlinx.io.IOException
import kotlinx.io.files.Path

internal actual fun openFileReadHandle(path: Path): FileReadHandle {
    val file = File(path.toString())
    if (!file.isFile) throw IOException("not a regular audio file: $path")
    val handle = RandomAccessFile(file, "r")
    return try {
        val length = handle.length()
        object : FileReadHandle {
            override val size: Long = length
            override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int {
                handle.seek(position)
                return handle.read(into, offset, count)
            }
            override fun close() { handle.close() }
        }
    } catch (failure: Throwable) {
        try { handle.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
        throw failure
    }
}

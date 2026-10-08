package io.github.yuroyami.audiokodec.io

import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import kotlinx.io.IOException
import kotlinx.io.files.Path

// getBuiltinModule works in both CommonJS and ES modules, without a browser import.
private fun nodeFileSystem(): dynamic = js("typeof process !== 'undefined' && typeof process.getBuiltinModule === 'function' ? process.getBuiltinModule('fs') : null")
private fun filePosition(value: String): dynamic = js("BigInt(value)")

internal actual fun openFileReadHandle(path: Path): FileReadHandle {
    val fs = nodeFileSystem() ?: throw UnsupportedFeatureException(
        "seekable files in this JavaScript host", "use Node.js 20.16 or later, or buffer browser input with readAudioSource")
    val descriptor = try {
        val flags = (fs.constants.O_RDONLY as Int) or (fs.constants.O_NONBLOCK as? Int ?: 0)
        fs.openSync(path.toString(), flags) as Int
    } catch (failure: Throwable) {
        throw IOException("could not open the audio file: $path", failure)
    }
    return try {
        val metadata = fs.fstatSync(descriptor, js("({ bigint: true })"))
        if (metadata.isFile() != true) throw IOException("not a regular audio file: $path")
        val length = (metadata.size.toString() as String).toLongOrNull() ?: -1L
        object : FileReadHandle {
            override val size: Long = length
            override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int =
                fs.readSync(descriptor, into, offset, count, filePosition(position.toString())) as Int
            override fun close() { fs.closeSync(descriptor) }
        }
    } catch (failure: Throwable) {
        try { fs.closeSync(descriptor) } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
        throw IOException("could not inspect the audio file: $path", failure)
    }
}

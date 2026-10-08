package io.github.yuroyami.audiokodec.io

import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlin.js.JsAny

@JsFun("() => typeof process !== 'undefined' && typeof process.getBuiltinModule === 'function' ? process.getBuiltinModule('fs') : null")
private external fun nodeFileSystem(): JsAny?
@JsFun("(fs, path) => fs.openSync(path, fs.constants.O_RDONLY | fs.constants.O_NONBLOCK)")
private external fun nodeOpen(fs: JsAny, path: String): Int
@JsFun("(fs, fd) => fs.fstatSync(fd).isFile()")
private external fun nodeIsFile(fs: JsAny, fd: Int): Boolean
@JsFun("(fs, fd) => fs.fstatSync(fd, { bigint: true }).size.toString()")
private external fun nodeSize(fs: JsAny, fd: Int): String
@JsFun("(fs, fd) => { fs.closeSync(fd); }")
private external fun nodeClose(fs: JsAny, fd: Int)
@JsFun("(fs, fd, position, count) => { const bytes = new Uint8Array(count); const n = fs.readSync(fd, bytes, 0, count, BigInt(position)); let text = ''; for (let i = 0; i < n; i += 4096) text += String.fromCharCode(...bytes.subarray(i, Math.min(i + 4096, n))); return text; }")
private external fun nodeRead(fs: JsAny, fd: Int, position: String, count: Int): String

internal actual fun openFileReadHandle(path: Path): FileReadHandle {
    val fs = nodeFileSystem() ?: throw UnsupportedFeatureException(
        "seekable files in this WebAssembly JavaScript host", "use Node.js 20.16 or later, or buffer browser input with readAudioSource")
    val descriptor = try {
        nodeOpen(fs, path.toString())
    } catch (failure: Throwable) {
        throw IOException("could not open the audio file: $path", failure)
    }
    return try {
        if (!nodeIsFile(fs, descriptor)) throw IOException("not a regular audio file: $path")
        val length = nodeSize(fs, descriptor).toLongOrNull() ?: -1L
        object : FileReadHandle {
            override val size: Long = length
            override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int {
                val data = nodeRead(fs, descriptor, position.toString(), count)
                for (index in data.indices) into[offset + index] = data[index].code.toByte()
                return data.length
            }
            override fun close() { nodeClose(fs, descriptor) }
        }
    } catch (failure: Throwable) {
        try { nodeClose(fs, descriptor) } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
        throw IOException("could not inspect the audio file: $path", failure)
    }
}

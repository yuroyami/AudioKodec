@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class, kotlin.wasm.ExperimentalWasmInterop::class)

package io.github.yuroyami.audiokodec.io

import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.withScopedMemoryAllocator

/** WASI snapshot preview1's documented imports and memory layouts. */
internal actual fun openFileReadHandle(path: Path): FileReadHandle {
    val (directory, relative) = findPreopenedDirectory(path.toString())
    val descriptor = withScopedMemoryAllocator { allocator ->
        val encoded = relative.encodeToByteArray()
        val name = allocator.allocate(encoded.size)
        for (index in encoded.indices) (name + index).storeByte(encoded[index])
        val opened = allocator.allocate(4)
        // FD_READ, FD_SEEK, FD_TELL and FD_FILESTAT_GET, with no write rights.
        val rights = (1L shl 1) or (1L shl 2) or (1L shl 5) or (1L shl 21)
        checkWasi(pathOpen(directory, 1, name.address.toInt(), encoded.size, 0, rights, 0, 4, opened.address.toInt()), "opening $path")
        opened.loadInt()
    }
    return try {
        val length = withScopedMemoryAllocator { allocator ->
            val stat = allocator.allocate(64)
            checkWasi(fdFilestatGet(descriptor, stat.address.toInt()), "inspecting $path")
            if ((stat + 16).loadByte().toInt() != 4) throw IOException("not a regular audio file: $path")
            (stat + 32).loadLong()
        }
        object : FileReadHandle {
            override val size: Long = length
            override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int =
                withScopedMemoryAllocator { allocator ->
                    val data = allocator.allocate(count)
                    val vector = allocator.allocate(8)
                    vector.storeInt(data.address.toInt())
                    (vector + 4).storeInt(count)
                    val result = allocator.allocate(4)
                    checkWasi(fdPread(descriptor, vector.address.toInt(), 1, position, result.address.toInt()), "reading $path")
                    val got = result.loadInt()
                    if (got !in 0..count) throw IOException("WASI returned an invalid $got-byte read")
                    for (index in 0 until got) into[offset + index] = (data + index).loadByte()
                    got
                }
            override fun close() { checkWasi(fdClose(descriptor), "closing $path") }
        }
    } catch (failure: Throwable) {
        try { checkWasi(fdClose(descriptor), "closing $path") } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
        throw failure
    }
}

private fun findPreopenedDirectory(path: String): Pair<Int, String> {
    var best: Pair<Int, String>? = null
    var bestLength = -1
    // WASI runtimes advertise preopens consecutively, starting after stdin/out/err.
    var descriptor = 3
    while (true) {
        val name = withScopedMemoryAllocator { allocator ->
            val stat = allocator.allocate(8)
            val error = fdPrestatGet(descriptor, stat.address.toInt())
            if (error == 8) return@withScopedMemoryAllocator null // BADF ends the preopen list.
            checkWasi(error, "finding preopened directories")
            if (stat.loadByte().toInt() != 0) throw IOException("WASI advertised an unsupported preopen type")
            val length = (stat + 4).loadInt()
            if (length !in 0..65_536) throw IOException("WASI advertised an invalid directory-name length")
            val text = allocator.allocate(length)
            checkWasi(fdPrestatDirName(descriptor, text.address.toInt(), length), "reading a preopened directory name")
            ByteArray(length) { (text + it).loadByte() }.decodeToString()
        } ?: break
        val prefix = if (name == "/") name else name.trimEnd('/')
        val relative = when {
            prefix == "." && !path.startsWith('/') -> path.removePrefix("./")
            prefix == "/" -> path.removePrefix("/")
            path == prefix -> "."
            path.startsWith("$prefix/") -> path.substring(prefix.length + 1)
            else -> null
        }
        val length = if (prefix == ".") 0 else prefix.length
        if (relative != null && length > bestLength) {
            best = descriptor to relative
            bestLength = length
        }
        if (descriptor == Int.MAX_VALUE) throw IOException("WASI's preopen descriptor list overflows")
        descriptor++
    }
    return best ?: throw IOException("no WASI preopened directory grants access to $path")
}

private fun checkWasi(error: Int, operation: String) {
    if (error != 0) throw IOException("WASI errno $error while $operation")
}

@WasmImport("wasi_snapshot_preview1", "path_open")
private external fun pathOpen(fd: Int, dirFlags: Int, path: Int, length: Int, flags: Int, rights: Long, inheriting: Long, fdFlags: Int, result: Int): Int
@WasmImport("wasi_snapshot_preview1", "fd_filestat_get")
private external fun fdFilestatGet(fd: Int, result: Int): Int
@WasmImport("wasi_snapshot_preview1", "fd_pread")
private external fun fdPread(fd: Int, vectors: Int, count: Int, offset: Long, result: Int): Int
@WasmImport("wasi_snapshot_preview1", "fd_close")
private external fun fdClose(fd: Int): Int
@WasmImport("wasi_snapshot_preview1", "fd_prestat_get")
private external fun fdPrestatGet(fd: Int, result: Int): Int
@WasmImport("wasi_snapshot_preview1", "fd_prestat_dir_name")
private external fun fdPrestatDirName(fd: Int, path: Int, length: Int): Int

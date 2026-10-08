@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.audiokodec.io

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.wcstr
import platform.posix.SEEK_SET
import platform.posix._O_BINARY
import platform.posix._O_RDONLY
import platform.posix._S_IFMT
import platform.posix._S_IFREG
import platform.posix._close
import platform.posix._fstat64
import platform.posix._lseeki64
import platform.posix._read
import platform.posix._stat64
import platform.posix._wopen

internal actual fun openNativeReadOnly(path: String): Int = memScoped { _wopen(path.wcstr.ptr, _O_RDONLY or _O_BINARY) }
internal actual fun nativeFileSize(descriptor: Int): Long = memScoped {
    val metadata = alloc<_stat64>()
    if (_fstat64(descriptor, metadata.ptr) != 0 || metadata.st_mode.toInt() and _S_IFMT != _S_IFREG) -1L
    else metadata.st_size
}
internal actual fun closeNativeFile(descriptor: Int): Int = _close(descriptor)
internal actual fun readNativeFile(descriptor: Int, position: Long, into: ByteArray, offset: Int, count: Int): Int {
    if (_lseeki64(descriptor, position, SEEK_SET) < 0) return -1
    return into.usePinned { _read(descriptor, it.addressOf(offset), count.toUInt()) }
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.audiokodec.io

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.O_CLOEXEC
import platform.posix.O_NONBLOCK
import platform.posix.O_RDONLY
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.errno
import platform.posix.fstat
import platform.posix.open
import platform.posix.pread
import platform.posix.stat

internal actual fun openNativeReadOnly(path: String): Int = open(path, O_RDONLY or O_NONBLOCK or O_CLOEXEC)
internal actual fun nativeFileSize(descriptor: Int): Long = memScoped {
    val metadata = alloc<stat>()
    if (fstat(descriptor, metadata.ptr) != 0 || metadata.st_mode.toInt() and S_IFMT != S_IFREG) -1L
    else metadata.st_size.toLong()
}
internal actual fun closeNativeFile(descriptor: Int): Int = close(descriptor)
internal actual fun readNativeFile(descriptor: Int, position: Long, into: ByteArray, offset: Int, count: Int): Int =
    into.usePinned { pinned ->
        var got: Long
        do { got = pread(descriptor, pinned.addressOf(offset), count.convert(), position) } while (got < 0 && errno == EINTR)
        got.toInt()
    }

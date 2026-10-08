package io.github.yuroyami.audiokodec.io

import io.github.yuroyami.audiokodec.AudioSink
import io.github.yuroyami.audiokodec.AudioSource
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.asAudioSource
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Bridges AudioKodec to kotlinx-io.
 *
 * [readAudioSource] buffers a sequential source or a whole file.
 * [openAudioSource] opens a seekable file handle and keeps reads bounded
 * regardless of file size. Writing streams forwards through [AudioSink].
 */

/**
 * Reads the whole of [this] into memory and returns it as an [AudioSource].
 *
 * The entire file is held in memory, so [maxBytes] is there to stop an
 * unexpectedly large file from becoming an unexpectedly large allocation. The
 * source is closed once it has been read.
 */
public fun RawSource.readAudioSource(maxBytes: Long = 512L * 1024 * 1024): AudioSource {
    require(maxBytes >= 0) { "maxBytes must not be negative, was $maxBytes" }
    val bytes = buffered().use { source ->
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = source.readAtMostTo(buffer, 64 * 1024)
            if (read < 0L) break
            if (read == 0L) {
                throw AudioKodecException(
                    "the source returned zero bytes for a non-empty read request",
                )
            }
            if (read > maxBytes - total) {
                throw AudioKodecException(
                    "this source holds more than $maxBytes bytes. Raise maxBytes, or " +
                        "use Path.openAudioSource for file input without whole-file buffering.",
                )
            }
            if (read > Int.MAX_VALUE.toLong() - total) {
                throw AudioKodecException(
                    "this source is too large to hold in one Kotlin ByteArray. " +
                        "Use Path.openAudioSource for seekable file input instead.",
                )
            }
            total += read
        }
        buffer.readByteArray()
    }
    return bytes.asAudioSource()
}

/**
 * Reads the file at [this] into memory and returns it as an [AudioSource].
 *
 * The default ceiling is 512 MiB. Use [openAudioSource] for a seekable file
 * handle whose memory use is independent of file size.
 */
public fun Path.readAudioSource(maxBytes: Long = 512L * 1024 * 1024): AudioSource =
    SystemFileSystem.source(this).readAudioSource(maxBytes)

/**
 * Writes an encoder's output into [this].
 *
 * The returned sink cannot be patched, because a kotlinx-io sink only moves
 * forwards. Encoding to it therefore needs a
 * [io.github.yuroyami.audiokodec.PcmSource] whose frame count is known in
 * advance, so that the header can be written correctly the first time.
 * Closing the returned sink closes [this].
 */
public fun RawSink.asAudioSink(): AudioSink = RawSinkAudioSink(this)

/**
 * Writes an encoder's output to the file at [this].
 *
 * The first valid, non-empty write opens the file and replaces its contents.
 * Creating or closing an unused sink leaves the file untouched. Failures
 * after writing begins may leave partial output; use [writeAudioAtomically]
 * when an existing file must survive a failed encode.
 */
public fun Path.asAudioSink(): AudioSink = RawSinkAudioSink(null) { SystemFileSystem.sink(this) }

private class RawSinkAudioSink(
    private var sink: RawSink?,
    private val openSink: (() -> RawSink)? = null,
) : AudioSink {

    private val buffer = Buffer()
    private var written = 0L
    private var closed = false

    override val canPatch: Boolean get() = false

    override val position: Long get() = written

    override fun write(bytes: ByteArray, offset: Int, count: Int) {
        check(!closed) { "this sink is closed" }
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count) {
            "offset=$offset count=$count is out of bounds for a ${bytes.size}-byte array"
        }
        check(count.toLong() <= Long.MAX_VALUE - written) {
            "the sink position would overflow 64 bits"
        }
        if (count == 0) return
        val destination = sink ?: checkNotNull(openSink).invoke().also { sink = it }
        buffer.write(bytes, offset, offset + count)
        written += count
        if (buffer.size >= 64 * 1024) destination.write(buffer, buffer.size)
    }

    override fun patchAt(position: Long, bytes: ByteArray, offset: Int, count: Int) {
        throw UnsupportedOperationException(
            "a kotlinx-io sink only moves forwards, so it cannot be patched",
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        val destination = sink ?: return
        try {
            if (buffer.size > 0) destination.write(buffer, buffer.size)
            destination.flush()
        } finally {
            destination.close()
        }
    }
}

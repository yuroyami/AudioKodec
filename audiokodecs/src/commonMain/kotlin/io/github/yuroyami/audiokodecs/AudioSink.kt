package io.github.yuroyami.audiokodecs

/**
 * A sink for bytes an encoder or a tag writer produces.
 *
 * Encoders write forwards only, but some containers need to correct a length
 * field once the payload is known: RIFF stores the size of a chunk before its
 * contents, and an encoder does not know that size until it has finished.
 * [patchAt] exists for exactly that, and a sink that cannot seek backwards can
 * report so with [canPatch].
 *
 * When [canPatch] is false, encoding needs a [PcmSource.frameCount] known in
 * advance so every length can be written correctly the first time. An
 * in-memory or seekable file sink should return true.
 */
public interface AudioSink : AutoCloseable {

    /** True when [patchAt] may be called. */
    public val canPatch: Boolean

    /** Bytes written so far. */
    public val position: Long

    /** Appends [count] bytes from [bytes] starting at [offset]. */
    public fun write(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset)

    /**
     * Overwrites bytes already written, at absolute [position].
     *
     * Only valid when [canPatch] is true, and only for a region that has
     * already been written. It never extends the output.
     */
    public fun patchAt(position: Long, bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset)

    override fun close()
}

/**
 * An [AudioSink] that collects everything in memory.
 *
 * Call [toByteArray] once encoding is finished. The sink grows geometrically,
 * so writing a large file costs one copy per doubling.
 */
public class ByteArrayAudioSink(initialCapacity: Int = 8 * 1024) : AudioSink {

    init {
        require(initialCapacity >= 0) {
            "initialCapacity must not be negative, was $initialCapacity"
        }
    }

    private var buffer = ByteArray(if (initialCapacity < 16) 16 else initialCapacity)
    private var length = 0

    override val canPatch: Boolean get() = true

    override val position: Long get() = length.toLong()

    override fun write(bytes: ByteArray, offset: Int, count: Int) {
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count) {
            "offset=$offset count=$count is out of bounds for a ${bytes.size}-byte array"
        }
        if (count > Int.MAX_VALUE - length) {
            throw IllegalStateException("a ByteArrayAudioSink cannot exceed ${Int.MAX_VALUE} bytes")
        }
        ensure(length + count)
        bytes.copyInto(buffer, length, offset, offset + count)
        length += count
    }

    override fun patchAt(position: Long, bytes: ByteArray, offset: Int, count: Int) {
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count) {
            "offset=$offset count=$count is out of bounds for a ${bytes.size}-byte array"
        }
        require(position >= 0 && position <= length.toLong() - count) {
            "patch at $position..${position + count} is outside the $length bytes written"
        }
        bytes.copyInto(buffer, position.toInt(), offset, offset + count)
    }

    /** The bytes written so far, as a fresh array. */
    public fun toByteArray(): ByteArray = buffer.copyOf(length)

    override fun close() {
        // Nothing to release.
    }

    private fun ensure(needed: Int) {
        if (needed <= buffer.size) return
        var next = buffer.size
        while (next < needed) {
            next = if (next > Int.MAX_VALUE / 2) Int.MAX_VALUE else next * 2
        }
        buffer = buffer.copyOf(next)
    }
}

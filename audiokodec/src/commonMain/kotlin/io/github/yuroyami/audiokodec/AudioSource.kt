package io.github.yuroyami.audiokodec

/**
 * A random-access source of bytes.
 *
 * Audio files need random access. An MP4 can carry its sample table after the
 * audio, a FLAC seek table points at absolute offsets, and seeking in any
 * format means jumping backwards. A sequential stream cannot serve that, so
 * this is the interface every AudioKodec reader takes.
 *
 * Implementations are used from one thread at a time. AudioKodec never calls
 * into a source concurrently, and never calls after [AudioSource.close].
 *
 * For an in-memory file, use [asAudioSource]. For a file on disk, use the
 * `audiokodec-io` module.
 */
public interface AudioSource : AutoCloseable {

    /** Total size in bytes, or -1 when the length is not known in advance. */
    public val size: Long

    /**
     * Reads up to [count] bytes starting at absolute [position] into [into] at
     * [offset].
     *
     * Returns the number of bytes read, which may be fewer than [count], or -1
     * at end of input. A return of 0 is only valid when [count] is 0.
     */
    public fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int

    override fun close()
}

/**
 * Reads this array as an [AudioSource]. Nothing is copied, and
 * [AudioSource.close] does nothing.
 */
public fun ByteArray.asAudioSource(): AudioSource = ByteArrayAudioSource(this)

private class ByteArrayAudioSource(private val bytes: ByteArray) : AudioSource {

    override val size: Long get() = bytes.size.toLong()

    override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int {
        require(position >= 0) { "position must not be negative, was $position" }
        require(offset >= 0 && count >= 0 && offset <= into.size - count) {
            "offset=$offset count=$count is out of bounds for a ${into.size}-byte array"
        }
        if (count == 0) return 0
        if (position >= bytes.size) return -1
        val available = (bytes.size - position).toInt()
        val n = if (count < available) count else available
        bytes.copyInto(into, offset, position.toInt(), position.toInt() + n)
        return n
    }

    override fun close() {
        // Nothing to release.
    }
}

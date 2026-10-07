package io.github.yuroyami.audiokodec

/**
 * A stream of Float32 PCM going into an encoder.
 *
 * This is the mirror of [AudioDecoder]: pull based, synchronous, and free of
 * any assumption about where the samples come from. A decoder, an array in
 * memory or a generator all satisfy it.
 *
 * Encoding consumes a stream. Create a fresh source before encoding it again.
 * [DecodedAudio] supplies an independent reader for each encoding operation.
 */
public interface PcmSource {

    /** Sample rate and channel layout of the samples this produces. */
    public val format: AudioFormat

    /**
     * Frames this will produce in total, or null when that is not known in
     * advance.
     *
     * Knowing it lets an encoder write its length fields as it goes, which is
     * what makes encoding to a sink that cannot seek backwards possible.
     */
    public val frameCount: Long?

    /**
     * Fills [into] with up to [frames] frames of interleaved samples starting
     * at [offset], and returns how many frames were written.
     *
     * A return of 0 means end of stream. A short return that is not 0 is
     * allowed and does not mean end of stream.
     */
    public fun read(into: FloatArray, offset: Int, frames: Int): Int
}

/**
 * Reads an array of interleaved samples as a [PcmSource].
 *
 * The array is not copied. Changing it while an encoder is running changes
 * what gets encoded.
 */
public fun FloatArray.asPcmSource(
    format: AudioFormat,
    frameCount: Int = size / format.channels,
): PcmSource {
    require(frameCount >= 0) { "frameCount must not be negative, was $frameCount" }
    val needed = frameCount.toLong() * format.channels
    require(needed <= size.toLong()) {
        "samples holds $size entries, which is short of the $needed that " +
            "$frameCount frames of ${format.channels} channels need"
    }
    return FloatArrayPcmSource(this, format, frameCount)
}

private class FloatArrayPcmSource(
    private val samples: FloatArray,
    override val format: AudioFormat,
    private val frames: Int,
) : PcmSource {

    private var position = 0

    override val frameCount: Long get() = frames.toLong()

    override fun read(into: FloatArray, offset: Int, frames: Int): Int {
        checkPcmReadBounds(into, offset, frames, format.channels)
        if (frames == 0) return 0
        val available = this.frames - position
        if (available <= 0) return 0
        val n = if (frames < available) frames else available
        val channels = format.channels
        samples.copyInto(
            destination = into,
            destinationOffset = offset,
            startIndex = position * channels,
            endIndex = (position + n) * channels,
        )
        position += n
        return n
    }
}

/**
 * A whole file decoded into memory.
 *
 * [AudioKodec.decode] returns this. It is also a [PcmSource], so decoding one
 * format and encoding another is two calls.
 *
 * [AudioKodec.encode] reads the complete samples from the beginning through
 * an independent reader. Encoding can be repeated and preserves the cursor
 * used by direct [read] calls. Direct reading advances that cursor;
 * [rewind] resets it.
 *
 * The sample array is not copied for encoding. Changing it during an
 * encoding operation changes the samples that operation reads.
 */
public class DecodedAudio(
    /** Sample rate and channel layout. */
    public override val format: AudioFormat,
    /** Interleaved samples. Its length is `frames * format.channels`. */
    public val samples: FloatArray,
    /** Frames decoded. */
    public val frames: Int,
    /** What the header said, for the file this came from. */
    public val info: AudioInfo,
) : PcmSource {

    private var position = 0

    init {
        require(frames >= 0) { "frames must not be negative, was $frames" }
        val needed = frames.toLong() * format.channels
        require(needed == samples.size.toLong()) {
            "samples holds ${samples.size} entries, but $frames frames of " +
                "${format.channels} channels need exactly $needed"
        }
        require(info.format == format) {
            "info describes ${info.format}, but the decoded samples use $format"
        }
    }

    override val frameCount: Long get() = frames.toLong()

    /** Length in seconds. */
    public val durationSeconds: Double get() = frames.toDouble() / format.sampleRate

    override fun read(into: FloatArray, offset: Int, frames: Int): Int {
        checkPcmReadBounds(into, offset, frames, format.channels)
        if (frames == 0) return 0
        val available = this.frames - position
        if (available <= 0) return 0
        val n = if (frames < available) frames else available
        val channels = format.channels
        samples.copyInto(into, offset, position * channels, (position + n) * channels)
        position += n
        return n
    }

    /** Resets the direct-read cursor. Encoding already starts at the beginning. */
    public fun rewind() {
        position = 0
    }
}

private fun checkPcmReadBounds(
    into: FloatArray,
    offset: Int,
    frames: Int,
    channels: Int,
) {
    require(offset >= 0) { "offset must not be negative, was $offset" }
    require(frames >= 0) { "frames must not be negative, was $frames" }
    val entries = frames.toLong() * channels
    require(entries <= into.size.toLong() - offset) {
        "offset=$offset and $frames frames of $channels channels do not fit in a " +
            "${into.size}-entry array"
    }
}

/**
 * Calls a user-supplied source and enforces the count its contract permits.
 *
 * Encoders use this rather than trusting a bad implementation to advance
 * their fill cursor beyond the destination buffer.
 */
internal fun PcmSource.readChecked(into: FloatArray, offset: Int, frames: Int): Int {
    val got = read(into, offset, frames)
    if (got !in 0..frames) {
        throw AudioKodecException(
            "PcmSource.read returned $got frames when it was asked for at most $frames",
        )
    }
    return got
}

package io.github.yuroyami.audiokodec

/**
 * A stream of exact signed integer PCM going into an encoder.
 *
 * Samples are interleaved, right-justified and sign-extended, just as in
 * [IntAudioFrame]. A 16-bit source produces values in `-32768 .. 32767`.
 * [bitDepth] describes those values, independently of the target depth.
 * Encoding consumes the source; create a fresh reader to encode it again.
 *
 * Integer input supports integer WAV, AIFF and AU, and 8/16/24-bit FLAC.
 * Equal depths preserve every bit. Increasing depth shifts left and fills
 * the low bits with zero. Without dither, reducing depth uses an arithmetic
 * right shift, discarding low bits and rounding towards negative infinity.
 * [Dither.Tpdf] instead adds noise at the target depth and rounds to nearest,
 * with ties to even and clipping to the target range. Dither is applied only
 * when reducing depth. No sample passes through Float32.
 */
public interface IntPcmSource {

    /** Sample rate and channel layout of these samples. */
    public val format: AudioFormat

    /** Significant signed bits per input sample, from 1 through 32. */
    public val bitDepth: Int

    /** Total frames, or null when unknown. Unpatchable WAV, AIFF and AU sinks need this count. */
    public val frameCount: Long?

    /**
     * Fills [into] with up to [frames] frames from sample-entry [offset] and
     * returns the number of frames written. Zero means end of stream; a
     * short nonzero return does not. Values must fit the signed [bitDepth].
     */
    public fun read(into: IntArray, offset: Int, frames: Int): Int
}

/**
 * Reads interleaved signed samples without copying the array.
 *
 * [bitDepth] describes the input range, even for unsigned 8-bit WAV output.
 * Changing the array during encoding changes the samples that reader sees.
 */
public fun IntArray.asIntPcmSource(
    format: AudioFormat,
    bitDepth: Int,
    frameCount: Int = size / format.channels,
): IntPcmSource {
    require(bitDepth in 1..32) { "bitDepth must be 1..32, was $bitDepth" }
    require(frameCount >= 0) { "frameCount must not be negative, was $frameCount" }
    val needed = frameCount.toLong() * format.channels
    require(needed <= size.toLong()) {
        "samples holds $size entries, which is short of the $needed that " +
            "$frameCount frames of ${format.channels} channels need"
    }
    return IntArrayPcmSource(this, format, bitDepth, frameCount)
}

private class IntArrayPcmSource(
    private val samples: IntArray,
    override val format: AudioFormat,
    override val bitDepth: Int,
    private val frames: Int,
) : IntPcmSource {
    private var position = 0

    override val frameCount: Long get() = frames.toLong()

    override fun read(into: IntArray, offset: Int, frames: Int): Int {
        require(offset >= 0) { "offset must not be negative, was $offset" }
        require(frames >= 0) { "frames must not be negative, was $frames" }
        val entries = frames.toLong() * format.channels
        require(entries <= into.size.toLong() - offset) {
            "offset=$offset and $frames frames of ${format.channels} channels do not fit in a " +
                "${into.size}-entry array"
        }
        val count = minOf(frames, this.frames - position)
        samples.copyInto(into, offset, position * format.channels, (position + count) * format.channels)
        position += count
        return count
    }
}

/** Checks a user-supplied reader before an encoder advances its fill cursor. */
internal fun IntPcmSource.readChecked(into: IntArray, offset: Int, frames: Int): Int {
    val got = read(into, offset, frames)
    if (got !in 0..frames) {
        throw AudioKodecException(
            "IntPcmSource.read returned $got frames when it was asked for at most $frames",
        )
    }
    return got
}

package io.github.yuroyami.kiteaudio

/**
 * One block of decoded audio.
 *
 * Samples are Float32, interleaved, nominally in `-1.0 .. 1.0`. A decoder does
 * not clamp: a lossy codec can legitimately overshoot that range, and clamping
 * on the way out would destroy information a limiter upstream of the speakers
 * still wants. Clamp at the point where you convert to integers, which
 * [Pcm] does for you.
 *
 * The block size is whatever the codec produces naturally. MPEG Layer III
 * uses 576 or 1152 frames depending on its version, FLAC uses up to 65,535,
 * and uncompressed formats are returned in fixed-size chunks.
 */
public class AudioFrame(
    /** Sample rate and channel layout of these samples. */
    public val format: AudioFormat,
    /**
     * Interleaved samples. The valid region is the first
     * `frameCount * format.channels` entries; the array may be longer.
     *
     * With [DecodeOptions.reuseBuffers] set, this array belongs to the decoder
     * and its contents change on the next call to
     * [AudioDecoder.nextFrame]. Copy what you keep.
     */
    public val samples: FloatArray,
    /** Number of frames, where one frame is one sample for every channel. */
    public val frameCount: Int,
    /** Absolute position of the first frame, counted in frames from the start. */
    public val positionFrames: Long,
) {
    init {
        require(frameCount >= 0) { "frameCount must not be negative, was $frameCount" }
        require(positionFrames >= 0) {
            "positionFrames must not be negative, was $positionFrames"
        }
        val needed = frameCount.toLong() * format.channels
        require(needed <= samples.size.toLong()) {
            "samples holds ${samples.size} entries, which is short of the " +
                "$needed that $frameCount frames of " +
                "${format.channels} channels need"
        }
    }

    /** Number of valid entries in [samples]. */
    public val sampleCount: Int get() = (frameCount.toLong() * format.channels).toInt()

    /** Reads one sample. [frame] counts from the start of this block. */
    public operator fun get(frame: Int, channel: Int): Float {
        if (frame !in 0 until frameCount) {
            throw IndexOutOfBoundsException("frame $frame is outside 0 until $frameCount")
        }
        if (channel !in 0 until format.channels) {
            throw IndexOutOfBoundsException(
                "channel $channel is outside 0 until ${format.channels}",
            )
        }
        return samples[frame * format.channels + channel]
    }

    /** A copy holding exactly [sampleCount] entries, safe to keep. */
    public fun copySamples(): FloatArray = samples.copyOf(sampleCount)
}

/**
 * One block of decoded audio in its stored integer form.
 *
 * Float32 has a 24-bit mantissa, so it carries 16- and 24-bit audio exactly
 * but loses the bottom bits of 32-bit audio. Lossless decoders therefore also
 * offer this view, and a caller that must prove a bit-exact round trip reads
 * it instead of [AudioFrame].
 */
public class IntAudioFrame(
    /** Sample rate and channel layout of these samples. */
    public val format: AudioFormat,
    /**
     * Interleaved samples, right-justified and sign-extended into `Int`,
     * exactly as the file stores them. A 16-bit file yields values in
     * `-32768 .. 32767`, not values scaled up to 32 bits.
     */
    public val samples: IntArray,
    /** Number of frames. */
    public val frameCount: Int,
    /** Absolute position of the first frame, counted in frames from the start. */
    public val positionFrames: Long,
    /** Stored bits per sample, from 1 through 32. */
    public val bitDepth: Int,
) {
    init {
        require(frameCount >= 0) { "frameCount must not be negative, was $frameCount" }
        require(positionFrames >= 0) {
            "positionFrames must not be negative, was $positionFrames"
        }
        require(bitDepth in 1..32) { "bitDepth must be 1..32, was $bitDepth" }
        val needed = frameCount.toLong() * format.channels
        require(needed <= samples.size.toLong()) {
            "samples holds ${samples.size} entries, which is short of the " +
                "$needed that $frameCount frames of ${format.channels} channels need"
        }
    }

    /** Number of valid entries in [samples]. */
    public val sampleCount: Int get() = (frameCount.toLong() * format.channels).toInt()
}

/**
 * Implemented by decoders that can hand back the stored integer samples as
 * well as Float32.
 *
 * Only lossless paths implement it. The current integer PCM and FLAC decoders
 * do; a lossy codec such as ADPCM or MPEG audio has no stored integer form to
 * be exact about and does not implement this interface.
 */
public interface IntPcmAccess {

    /** Stored bits per sample, from 1 through 32. */
    public val bitDepth: Int

    /**
     * Reads the next block as stored integers, or returns null at end of
     * stream.
     *
     * Use either this or [AudioDecoder.nextFrame] on a given decoder, not
     * both. They advance the same cursor, and interleaving the two calls
     * would read each block into only one of the two forms.
     */
    public fun nextIntFrame(): IntAudioFrame?
}

package io.github.yuroyami.audiokodec

/**
 * A decoder that hands back one block of PCM at a time.
 *
 * The model is pull, not push: call [nextFrame] until it returns null. Nothing
 * here is suspending, so the decoder works the same on a thread, inside a
 * coroutine, or on a platform that has neither. To read it as a `Flow`, wrap
 * it:
 *
 * ```kotlin
 * fun AudioDecoder.frames(): Flow<AudioFrame> = flow {
 *     use { while (true) emit(it.nextFrame() ?: break) }
 * }
 * ```
 *
 * A decoder holds the source open. Close it when you are done, or use
 * `use { }`.
 */
public interface AudioDecoder : AutoCloseable {

    /** Sample rate and channel layout of the audio this decoder produces. */
    public val format: AudioFormat

    /**
     * Length in frames, or null when the stream does not say and finding out
     * would mean decoding all of it.
     */
    public val durationFrames: Long?

    /**
     * Information about the opened stream. CAF ALAC durations include a check
     * of the final packet and can be shorter than [AudioKodec.probe] reports.
     */
    public val info: AudioInfo

    /** Position of the next frame [nextFrame] will return, counted in frames. */
    public val positionFrames: Long

    /**
     * Decodes the next block, or returns null at end of stream.
     *
     * The block size is the codec's own, so it varies between formats and can
     * vary within one file. Do not assume a fixed size.
     */
    public fun nextFrame(): AudioFrame?

    /**
     * Moves to [frameIndex], counted in frames from the start of the stream.
     *
     * The seek is sample accurate. Internally AudioKodec seeks the container to
     * a point at or before the target and discards decoded samples up to it,
     * so the next [nextFrame] starts exactly at the frame you asked for.
     *
     * Returns the position actually reached, which differs from [frameIndex]
     * only when the request was past the end of the stream, where the result
     * is clamped to the end.
     *
     * Throws [UnsupportedFeatureException] when the stream has no seek table
     * and [DecodeOptions.allowSlowSeek] is off.
     */
    public fun seek(frameIndex: Long): Long

    override fun close()
}

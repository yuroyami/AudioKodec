package io.github.yuroyami.audiokodec

/**
 * What a file says about its encoded audio, without decoding samples.
 *
 * [AudioKodec.probe] fills this from headers, tags and container indexes. A
 * format with no global index, notably unindexed MPEG audio, may require a
 * lightweight scan of frame or page headers across the file. The compressed
 * sample payload is never decoded.
 */
public class AudioInfo(
    /** The container the file is wrapped in. */
    public val container: AudioContainer,
    /** The coding of the samples inside it. */
    public val codec: AudioCodec,
    /** Sample rate and channel layout. */
    public val format: AudioFormat,
    /**
     * Length in frames, or null when the header does not state it and finding
     * out would mean decoding.
     */
    public val durationFrames: Long?,
    /**
     * Stored bits per sample, or null for codecs where the idea does not
     * apply.
     *
     * A lossy codec has no stored bit depth. Some report the depth of the
     * audio that went in, which is a property of the recording, not of the
     * file, and AudioKodec does not repeat that as fact.
     */
    public val bitDepth: Int?,
    /** Average bits per second, or null when the header does not state it. */
    public val bitrate: Int?,
    /** True when the bitrate varies across the file. */
    public val isVariableBitrate: Boolean,
    /** First byte of the audio payload. */
    public val dataOffset: Long,
    /** Length of the audio payload in bytes, or -1 when it is not stated. */
    public val dataSize: Long,
) {
    init {
        require(durationFrames == null || durationFrames >= 0) {
            "durationFrames must be nonnegative or null, was $durationFrames"
        }
        require(bitDepth == null || bitDepth in 1..32) {
            "bitDepth must be 1..32 or null, was $bitDepth"
        }
        require(bitrate == null || bitrate >= 0) {
            "bitrate must be nonnegative or null, was $bitrate"
        }
        require(dataOffset >= 0) { "dataOffset must not be negative, was $dataOffset" }
        require(dataSize >= -1) { "dataSize must be -1 or nonnegative, was $dataSize" }
    }

    /** Length in seconds, or null when [durationFrames] is null. */
    public val durationSeconds: Double?
        get() = durationFrames?.let { it.toDouble() / format.sampleRate }

    override fun toString(): String = buildString {
        append(container.displayName)
        append(" / ").append(codec.displayName)
        append(", ").append(format.sampleRate).append(" Hz")
        append(", ").append(format.channels).append(" ch")
        bitDepth?.let { append(", ").append(it).append("-bit") }
        durationSeconds?.let {
            append(", ")
            append(((it * 1000).toLong() / 1000.0))
            append(" s")
        }
    }
}

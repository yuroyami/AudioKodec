package io.github.yuroyami.audiokodecs.internal.codec

/**
 * Turns the encoded bytes of a block-structured codec into PCM.
 *
 * A block is the smallest self-contained unit the codec has. For linear PCM
 * that is one frame; for ADPCM it is a block whose header restarts the
 * predictor. Because every block stands on its own, a container that knows the
 * block size can seek to any block without decoding what came before, which is
 * why the codecs in this file are expressed this way.
 */
internal interface SampleCodec {

    /** Encoded bytes one block occupies. */
    val blockBytes: Int

    /** Frames one block decodes to, where a frame is one sample per channel. */
    val framesPerBlock: Int

    /** Channels the codec was configured for. */
    val channels: Int

    /**
     * Stored bits per sample, or null when the codec has no integer form that
     * is exact. Lossy codings report null even though they decode to
     * integers, because those integers are not what went in.
     */
    val storedBitDepth: Int?

    /**
     * Decodes [blocks] blocks starting at [srcOffset] into interleaved
     * Float32 at [outOffset], and returns the frames written.
     *
     * [out] must hold `blocks * framesPerBlock * channels` samples from
     * [outOffset]. The caller sizes it, because the caller is the one that
     * knows how many frames are actually wanted.
     */
    fun decode(
        src: ByteArray,
        srcOffset: Int,
        blocks: Int,
        out: FloatArray,
        outOffset: Int,
    ): Int

    /**
     * Decodes into right-justified integers instead, when
     * [storedBitDepth] is not null.
     *
     * Throws [UnsupportedOperationException] for codecs without an exact
     * integer form. Callers reach this only through
     * [io.github.yuroyami.audiokodecs.IntPcmAccess], which is implemented only
     * where the form exists.
     */
    fun decodeInt(
        src: ByteArray,
        srcOffset: Int,
        blocks: Int,
        out: IntArray,
        outOffset: Int,
    ): Int
}

/** A codec that can also turn PCM back into its encoded form. */
internal interface SampleEncoder {

    /** Encoded bytes one block occupies. */
    val blockBytes: Int

    /** Frames one block holds. */
    val framesPerBlock: Int

    /** Channels the encoder was configured for. */
    val channels: Int

    /**
     * Encodes exactly [frames] frames of interleaved Float32 from [srcOffset]
     * into [out] at [outOffset], and returns the bytes written.
     *
     * [frames] may be less than [framesPerBlock] for the last block, which the
     * encoder pads with silence. Every other block must be full.
     */
    fun encode(
        src: FloatArray,
        srcOffset: Int,
        frames: Int,
        out: ByteArray,
        outOffset: Int,
    ): Int
}

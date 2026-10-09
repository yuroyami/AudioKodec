package io.github.yuroyami.audiokodec.internal.codec

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
     * Blocks to decode and throw away before a seek target, for a codec that
     * carries state from block to block; zero for one whose blocks stand
     * alone. [FROM_START] asks for every block before the target, for a
     * codec whose state is never sure to settle, so that a seek lands on
     * exactly the samples a continuous decode gives.
     */
    val preRollBlocks: Int get() = 0

    /**
     * Frames a last block cut short to [bytes] bytes still holds whole, for a
     * coding whose writers end a stream after its last whole sample rather
     * than at the end of a block; zero for one whose blocks are always whole.
     */
    fun framesInShortBlock(bytes: Int): Int = 0

    /** Forgets the state carried from block to block, before decoding from somewhere new. */
    fun reset() = Unit

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
     * [io.github.yuroyami.audiokodec.IntPcmAccess], which is implemented only
     * where the form exists.
     */
    fun decodeInt(
        src: ByteArray,
        srcOffset: Int,
        blocks: Int,
        out: IntArray,
        outOffset: Int,
    ): Int

    companion object {
        /** The [preRollBlocks] of a codec that must decode a stream from its start. */
        const val FROM_START: Int = Int.MAX_VALUE
    }
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

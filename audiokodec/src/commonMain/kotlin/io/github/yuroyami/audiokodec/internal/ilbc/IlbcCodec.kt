package io.github.yuroyami.audiokodec.internal.ilbc

import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.internal.codec.SampleCodec

/**
 * iLBC as a block coding, one frame to a block: 38 bytes of 160 samples in
 * the 20 ms mode, or 50 bytes of 240 in the 30 ms one, at 8 kHz.
 *
 * The decoder's synthesis and output filters carry their state from frame to
 * frame, and a decoder started part way through settles to within a step of
 * a continuous one but not onto it. A seek therefore decodes from the start,
 * [SampleCodec.FROM_START], and lands exactly.
 */
internal class IlbcCodec(mode: Int) : SampleCodec {
    private val decoder = IlbcDecoder(mode)
    private val pcm = ShortArray(decoder.blockl)

    override val channels: Int = 1
    override val blockBytes: Int = decoder.noOfBytes
    override val framesPerBlock: Int = decoder.blockl
    override val storedBitDepth: Int? = null
    override val preRollBlocks: Int = SampleCodec.FROM_START

    override fun reset() = decoder.reset()

    override fun decode(src: ByteArray, srcOffset: Int, blocks: Int, out: FloatArray, outOffset: Int): Int {
        // Every frame is checked before any is decoded, so a refused frame leaves
        // the decoder's history where it was and the caller can retry up to it.
        for (block in 0 until blocks) {
            val at = srcOffset + block * blockBytes
            if (decoder.refuses(src, at)) throw CorruptStreamException(REFUSED, at.toLong())
        }
        var output = outOffset
        for (block in 0 until blocks) {
            decoder.decode(src, srcOffset + block * blockBytes, pcm, 0)
            for (i in 0 until framesPerBlock) out[output + i] = pcm[i] * SCALE
            output += framesPerBlock
        }
        return blocks * framesPerBlock
    }

    override fun decodeInt(src: ByteArray, srcOffset: Int, blocks: Int, out: IntArray, outOffset: Int): Int =
        throw UnsupportedOperationException("iLBC has no exact integer form")

    internal companion object {
        const val REFUSED = "an iLBC frame states a codebook index past every codebook section"
        const val SCALE = 1f / 32768f
    }
}

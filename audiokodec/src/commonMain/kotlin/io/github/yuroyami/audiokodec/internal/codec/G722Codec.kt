package io.github.yuroyami.audiokodec.internal.codec

/**
 * G.722 as a block coding: each octet decodes to two samples at 16 kHz.
 *
 * The decoder's state runs through the whole stream, so a seek decodes from
 * the start, [SampleCodec.FROM_START], and lands on exactly the samples a
 * continuous decode gives.
 */
internal class G722Codec(mode: Int) : SampleCodec {
    private val decoder = G722Decoder(mode)
    private val pcm = ShortArray(2)

    override val channels: Int = 1
    override val blockBytes: Int = 1
    override val framesPerBlock: Int = 2
    override val storedBitDepth: Int? = null
    override val preRollBlocks: Int = SampleCodec.FROM_START

    override fun reset() = decoder.reset()

    override fun decode(src: ByteArray, srcOffset: Int, blocks: Int, out: FloatArray, outOffset: Int): Int {
        var output = outOffset
        for (i in 0 until blocks) {
            decoder.decode(src[srcOffset + i].toInt() and 0xFF, pcm, 0)
            out[output++] = pcm[0] * SCALE
            out[output++] = pcm[1] * SCALE
        }
        return blocks * 2
    }

    override fun decodeInt(src: ByteArray, srcOffset: Int, blocks: Int, out: IntArray, outOffset: Int): Int =
        throw UnsupportedOperationException("G.722 has no exact integer form")

    private companion object {
        const val SCALE = 1f / 32768f
    }
}

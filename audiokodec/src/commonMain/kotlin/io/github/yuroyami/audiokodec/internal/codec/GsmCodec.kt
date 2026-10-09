package io.github.yuroyami.audiokodec.internal.codec

import io.github.yuroyami.audiokodec.CorruptStreamException

/**
 * GSM 06.10 as a block coding, in either of its frame layouts: the plain
 * 33-byte frame of 160 samples, or Microsoft's 65-byte block of two frames
 * that WAV files carry, under format tag 0x0031.
 *
 * Unlike the ADPCM codings, GSM carries its filters and pitch history from
 * one frame to the next, so blocks must be decoded in order. A seek restarts
 * the decoder [preRollBlocks] blocks before its target and throws those
 * samples away, so the samples after a seek converge on those of a
 * continuous decode rather than equalling them.
 */
internal class GsmCodec(private val wav49: Boolean) : SampleCodec {
    private val decoder = GsmDecoder()
    private val pcm = ShortArray(320)

    override val channels: Int = 1
    override val blockBytes: Int = if (wav49) 65 else 33
    override val framesPerBlock: Int = if (wav49) 320 else 160
    override val storedBitDepth: Int? = null

    /** About half a second of run-up, which the decoder's history settles well within. */
    override val preRollBlocks: Int = if (wav49) 12 else 25

    override fun reset() = decoder.reset()

    override fun decode(src: ByteArray, srcOffset: Int, blocks: Int, out: FloatArray, outOffset: Int): Int {
        // Every frame is checked before any is decoded, so a damaged frame leaves
        // the decoder's history where it was and the caller can retry up to it.
        if (!wav49) {
            for (block in 0 until blocks) {
                val at = srcOffset + block * blockBytes
                if (((src[at].toInt() shr 4) and 0x0F) != 0xD) {
                    throw CorruptStreamException("a GSM 06.10 frame lacks its signature", at.toLong())
                }
            }
        }
        var output = outOffset
        for (block in 0 until blocks) {
            val at = srcOffset + block * blockBytes
            if (wav49) decoder.decodeWav49(src, at, pcm, 0) else decoder.decodePlain(src, at, pcm, 0)
            for (i in 0 until framesPerBlock) out[output + i] = pcm[i] * SCALE
            output += framesPerBlock
        }
        return blocks * framesPerBlock
    }

    override fun decodeInt(src: ByteArray, srcOffset: Int, blocks: Int, out: IntArray, outOffset: Int): Int =
        throw UnsupportedOperationException("GSM 06.10 has no exact integer form")

    private companion object {
        const val SCALE = 1f / 32768f
    }
}

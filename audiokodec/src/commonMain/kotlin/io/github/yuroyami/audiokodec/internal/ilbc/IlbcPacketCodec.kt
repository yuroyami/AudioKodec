package io.github.yuroyami.audiokodec.internal.ilbc

import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.internal.PacketCodec

/**
 * iLBC in QuickTime and MP4 files, under the `ilbc` sample entry, which does
 * not state the frame size. As WebRTC's `WebRtcIlbcfix_Decode` does, each
 * packet's length decides it: one to three frames of the size in use, or
 * else of the other size, which starts the decoder afresh in that mode.
 */
internal class IlbcPacketCodec(
    override val format: AudioFormat,
    override val maxFramesPerPacket: Int,
    initialMode: Int,
) : PacketCodec {
    private var decoder = IlbcDecoder(initialMode)
    private val pcm = ShortArray(240)

    override val storedBitDepth: Int? = null

    /** The decoder's filters never settle exactly, so a seek decodes from the first packet. */
    override val preRollFrames: Long = Long.MAX_VALUE

    override fun decode(packet: ByteArray, length: Int, out: FloatArray): Int {
        if (!fits(decoder.noOfBytes, length)) {
            val other = if (decoder.mode == 20) 30 else 20
            if (!fits(if (other == 30) 50 else 38, length)) {
                throw CorruptStreamException("an iLBC packet of $length bytes is not one to three frames of either size")
            }
            decoder = IlbcDecoder(other)
        }
        val frames = length / decoder.noOfBytes
        if (frames * decoder.blockl > maxFramesPerPacket) throw CorruptStreamException("an iLBC packet holds more frames than it can")
        for (n in 0 until frames) {
            if (decoder.refuses(packet, n * decoder.noOfBytes)) throw CorruptStreamException(IlbcCodec.REFUSED)
        }
        for (n in 0 until frames) {
            decoder.decode(packet, n * decoder.noOfBytes, pcm, 0)
            for (i in 0 until decoder.blockl) out[n * decoder.blockl + i] = pcm[i] * IlbcCodec.SCALE
        }
        return frames * decoder.blockl
    }

    override fun reset() = decoder.reset()

    private fun fits(frameBytes: Int, length: Int): Boolean = length == frameBytes || length == 2 * frameBytes || length == 3 * frameBytes
}

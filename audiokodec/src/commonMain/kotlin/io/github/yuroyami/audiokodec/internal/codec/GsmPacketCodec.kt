package io.github.yuroyami.audiokodec.internal.codec

import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.internal.PacketCodec

/**
 * GSM 06.10 in QuickTime and MP4 files, under the `agsm` sample entry: each
 * packet is one or more plain 33-byte frames of 160 samples.
 */
internal class GsmPacketCodec(
    override val format: AudioFormat,
    override val maxFramesPerPacket: Int,
) : PacketCodec {
    private val decoder = GsmDecoder()
    private val pcm = ShortArray(FRAME_SAMPLES)

    override val storedBitDepth: Int? = null

    /** Half a second of run-up before a seek target, as for GSM in WAV. */
    override val preRollFrames: Long = 25L * FRAME_SAMPLES

    override fun decode(packet: ByteArray, length: Int, out: FloatArray): Int {
        if (length % FRAME_BYTES != 0) throw CorruptStreamException("a GSM 06.10 packet of $length bytes is not a whole number of frames")
        var produced = 0
        for (at in 0 until length step FRAME_BYTES) {
            if (produced + FRAME_SAMPLES > maxFramesPerPacket) throw CorruptStreamException("a GSM 06.10 packet holds more frames than it can")
            if (!decoder.decodePlain(packet, at, pcm, 0)) throw CorruptStreamException("a GSM 06.10 frame lacks its signature")
            for (i in 0 until FRAME_SAMPLES) out[produced + i] = pcm[i] * SCALE
            produced += FRAME_SAMPLES
        }
        return produced
    }

    override fun reset() = decoder.reset()

    companion object {
        const val FRAME_BYTES = 33
        const val FRAME_SAMPLES = 160
        private const val SCALE = 1f / 32768f
    }
}

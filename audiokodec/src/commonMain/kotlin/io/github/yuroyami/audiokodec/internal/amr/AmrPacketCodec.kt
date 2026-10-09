package io.github.yuroyami.audiokodec.internal.amr

import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.internal.PacketCodec

/**
 * Decodes packets of AMR or AMR-WB frames in the storage format of RFC 4867
 * section 5, each a header byte and its speech bits.
 *
 * In an AMR file a packet is a block of one frame per channel; in an MP4
 * track it is a sample of one or more frames of its single channel. Either
 * way frames go to the channels in turn, each channel with its own decoder,
 * and [placement] puts each channel where AudioKodec's order has it.
 */
internal class AmrPacketCodec(
    private val wide: Boolean,
    override val format: AudioFormat,
    private val placement: IntArray,
    override val maxFramesPerPacket: Int,
) : PacketCodec {
    private val channels = format.channels
    private val samplesPerFrame = samplesPerFrame(wide)
    private var narrow = Array(if (wide) 0 else channels) { AmrNbDecoder() }
    private var wideband = Array(if (wide) channels else 0) { AmrWbDecoder() }
    private val pcm = ShortArray(samplesPerFrame)

    override val storedBitDepth: Int? = null
    override val preRollFrames: Long = PRE_ROLL_FRAMES.toLong() * samplesPerFrame

    override fun decode(packet: ByteArray, length: Int, out: FloatArray): Int {
        var at = 0
        var produced = 0
        var channel = 0
        while (at < length) {
            val size = frameBytes(wide, packet[at].toInt())
            if (size < 0) throw CorruptStreamException("an AMR frame states a frame type the storage format reserves")
            if (at + size > length) throw CorruptStreamException("an AMR frame runs past the end of its packet")
            if (produced + samplesPerFrame > maxFramesPerPacket) throw CorruptStreamException("an AMR packet holds more frames than it can")
            try {
                if (wide) wideband[channel].decode(packet, at, pcm, 0) else narrow[channel].decode(packet, at, pcm, 0)
            } catch (_: IndexOutOfBoundsException) {
                // The reference would read past one of its tables here, which only damaged bits can lead to.
                throw CorruptStreamException("an AMR frame's parameters lead outside the decoder's tables")
            }
            val base = produced * channels + placement[channel]
            for (i in 0 until samplesPerFrame) out[base + i * channels] = pcm[i] * SCALE
            at += size
            if (++channel == channels) {
                channel = 0
                produced += samplesPerFrame
            }
        }
        if (channel != 0) throw CorruptStreamException("an AMR block holds fewer frames than the stream has channels")
        return produced
    }

    override fun reset() {
        narrow = Array(narrow.size) { AmrNbDecoder() }
        wideband = Array(wideband.size) { AmrWbDecoder() }
    }

    companion object {
        private const val SCALE = 1f / 32768f

        /** 20 ms frames decoded and thrown away before a seek target: half a second. */
        const val PRE_ROLL_FRAMES = 25

        /** Speech bytes after the header byte, by frame type, as the reference sizes them; -1 is reserved. */
        private val NB_SIZES = intArrayOf(12, 13, 15, 17, 19, 20, 26, 31, 5, -1, -1, -1, -1, -1, -1, 0)
        private val WB_SIZES = intArrayOf(17, 23, 32, 36, 40, 46, 50, 58, 60, 5, -1, -1, -1, -1, 0, 0)

        fun samplesPerFrame(wide: Boolean): Int = if (wide) AmrWbDecoder.AMR_WB_PCM_FRAME else AmrNbDecoder.L_FRAME

        /** The bytes of the frame whose header is [header], header included, or -1 for a reserved frame type. */
        fun frameBytes(wide: Boolean, header: Int): Int {
            val size = (if (wide) WB_SIZES else NB_SIZES)[(header shr 3) and 0x0f]
            return if (size < 0) -1 else size + 1
        }
    }
}

package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.opus.OpusDecoder
import io.github.yuroyami.audiokodec.internal.opus.OpusPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.round
import kotlin.test.assertEquals

/**
 * Decodes an `opus_demo` bit stream the way `opus_demo -d` does, so that the
 * two outputs can be compared sample for sample.
 *
 * The stream is a 4-byte big-endian length, the encoder's 4-byte big-endian
 * final range, and the packet, over and over; a length of zero is a lost
 * packet. `opus_demo` decodes to 16-bit samples, which libopus soft clips,
 * and it handles a loss only once the next packet arrives: each lost packet
 * is concealed for as long as the last decoded one lasted, except that the
 * last of a run is rebuilt from the redundant copy the arriving packet
 * carries, when it carries one. A loss at the very end is never concealed.
 */
internal object OpusDemoDecoding {

    /**
     * Returns the 16-bit samples, interleaved. Every packet that arrived has
     * its final range checked against the encoder's, which is more than
     * `opus_demo` checks: it leaves out the first packet and those straight
     * after a loss.
     */
    fun decode(stream: ByteArray, rate: Int, channels: Int, what: String): ShortArray {
        val decoder = OpusDecoder(rate, channels)
        val pcm = FloatArray(OpusPacket.MAX_SAMPLES * channels)
        val input = ByteBuffer.wrap(stream).order(ByteOrder.BIG_ENDIAN)
        var out = ShortArray(1 shl 16)
        var size = 0
        fun emit(samples: Int) {
            val needed = size + samples * channels
            if (needed > out.size) out = out.copyOf(maxOf(needed, out.size * 2))
            for (i in 0 until samples * channels) out[size + i] = toInt16(pcm[i])
            size = needed
        }
        var index = 0
        var lost = 0
        while (input.remaining() >= 8) {
            val length = input.int
            val expectedRange = input.int.toLong() and 0xFFFF_FFFFL
            val packet = ByteArray(length).also { input.get(it) }
            index++
            if (length == 0) {
                lost++
                continue
            }
            val redundant = OpusPacket.parse(packet, 0, length).hasLbrr(packet)
            for (gap in 0 until lost) {
                val duration = decoder.lastPacketDuration
                emit(
                    if (gap == lost - 1 && redundant) {
                        decoder.decode(packet, 0, length, pcm, duration, decodeFec = true, softClip = true)
                    } else {
                        decoder.decode(null, 0, 0, pcm, duration, decodeFec = false, softClip = true)
                    },
                )
            }
            emit(decoder.decode(packet, 0, length, pcm, OpusPacket.MAX_SAMPLES, decodeFec = false, softClip = true))
            if (expectedRange != 0L) {
                assertEquals(expectedRange, decoder.finalRange, "$what: final range of packet ${index - 1}")
            }
            lost = 0
        }
        return out.copyOf(size)
    }

    /** `FLOAT2INT16`: scale, clamp, and round half to even as `lrintf` does. */
    fun toInt16(x: Float): Short {
        val scaled = (x * 32768f).coerceIn(-32768f, 32767f)
        return round(scaled.toDouble()).toInt().toShort()
    }
}

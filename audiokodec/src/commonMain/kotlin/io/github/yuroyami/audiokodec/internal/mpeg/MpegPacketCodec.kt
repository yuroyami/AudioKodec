package io.github.yuroyami.audiokodec.internal.mpeg

import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.PacketCodec

/** One complete MPEG audio frame per indexed packet, retaining synthesis and reservoir state. */
internal class MpegPacketCodec(
    override val format: AudioFormat,
    private val first: MpegFrameHeader?,
    private val options: DecodeOptions,
) : PacketCodec {
    override val storedBitDepth: Int? = null
    override val maxFramesPerPacket: Int = first?.samplesPerFrame ?: 1152
    override val preRollFrames: Long = when (first?.layer) {
        1, 2 -> 2L * maxFramesPerPacket
        else -> 10L * maxFramesPerPacket
    }

    private var mp3: Mp3FrameDecoder? = null
    private var layer12: Layer12FrameDecoder? = null

    override fun decode(packet: ByteArray, length: Int, out: FloatArray): Int {
        val header = headerOf(packet, length)
        if (header.sampleRate != format.sampleRate || header.channels != format.channels ||
            first != null && !header.matches(first)
        ) {
            throw CorruptStreamException("an MPEG packet changes the track's layer, version, sample rate or channels")
        }
        options.checkAllocation(1024L * Double.SIZE_BYTES, "decoding an MPEG synthesis history")
        if (header.layer == 3) {
            val decoder = mp3 ?: run {
                Mp3FrameDecoder(format.channels, options).also { mp3 = it }
            }
            return decoder.decode(packet, header, out, 0)
        }
        val decoder = layer12 ?: run {
            Layer12FrameDecoder(format.channels, header.layer, options).also { layer12 = it }
        }
        return decoder.decode(packet, header, out, 0)
    }

    override fun reset() {
        mp3?.reset()
        layer12?.reset()
    }

    companion object {
        fun headerOf(packet: ByteArray, length: Int): MpegFrameHeader {
            if (length < 4) throw CorruptStreamException("an MPEG packet is shorter than its frame header")
            val header = MpegFrameHeader.parse(packet, 0) ?: run {
                if (MpegFrameHeader.isFreeFormat(packet, 0)) {
                    throw UnsupportedFeatureException("free-format MPEG audio", "the packet's header states no bitrate")
                }
                throw CorruptStreamException("an MPEG packet has no valid frame header")
            }
            if (header.frameBytes != length) {
                throw CorruptStreamException("an MPEG packet holds $length bytes for a ${header.frameBytes}-byte frame")
            }
            return header
        }
    }
}

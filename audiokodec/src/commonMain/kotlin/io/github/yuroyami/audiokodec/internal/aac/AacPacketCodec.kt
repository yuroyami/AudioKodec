package io.github.yuroyami.audiokodec.internal.aac

import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.internal.PacketCodec
import io.github.yuroyami.audiokodec.internal.PacketIndex
import io.github.yuroyami.audiokodec.internal.PacketStreamDecoder
import io.github.yuroyami.audiokodec.internal.bits.BitReaderMsb
import io.github.yuroyami.audiokodec.internal.container.AacConfig
import io.github.yuroyami.audiokodec.internal.container.AdtsHeader
import io.github.yuroyami.audiokodec.internal.container.AdtsStream
import io.github.yuroyami.audiokodec.internal.io.SourceReader

/**
 * AAC-LC packets for [PacketStreamDecoder]: one raw data block per packet
 * from MP4, or one whole ADTS frame, header and all, holding one to four.
 */
internal class AacPacketCodec(
    config: AacStreamConfig,
    /** The fixed header every frame repeats, for ADTS; null for bare blocks. */
    private val adts: AdtsHeader?,
) : PacketCodec {

    init {
        config.requireDecodable()
    }

    private val decoder = AacDecoder(config)

    override val format: AudioFormat = AudioFormat(config.sampleRate, config.layout)
    override val storedBitDepth: Int? = null
    override val maxFramesPerPacket: Int = AacConfig.FRAMES_PER_BLOCK * if (adts != null) 4 else 1

    /** A block's second half is completed by the next block, so one block settles the decoder. */
    override val preRollFrames: Long = AacConfig.FRAMES_PER_BLOCK.toLong()

    override fun decode(packet: ByteArray, length: Int, out: FloatArray): Int {
        if (adts == null) {
            decoder.decodeBlock(BitReaderMsb(packet, 0, length), out, 0)
            return AacConfig.FRAMES_PER_BLOCK
        }
        val header = AdtsHeader.parse(packet, 0)
        if (header == null || !header.matches(adts) || header.frameBytes != length) {
            throw CorruptStreamException("an ADTS frame header changed or was damaged")
        }
        var at = header.headerBytes
        for (block in 0 until header.rawBlocks) {
            if (at >= length) throw CorruptStreamException("an ADTS frame ends before its raw data blocks do")
            val bits = BitReaderMsb(packet, at, length - at)
            decoder.decodeBlock(bits, out, block * AacConfig.FRAMES_PER_BLOCK)
            at += bits.bytePosition
            // With more than one block, a protected frame checks each block separately.
            if (header.hasCrc && header.rawBlocks > 1) at += 2
        }
        return header.rawBlocks * AacConfig.FRAMES_PER_BLOCK
    }

    override fun reset() = decoder.reset()

    /**
     * Decodes [packet] once and forgets it, so that a stream which turns out
     * to need a tool AudioKodec refuses is refused when it is opened rather
     * than part way through. Damage is left for decoding to report.
     */
    fun screen(packet: ByteArray, length: Int) {
        try {
            decode(packet, length, FloatArray(maxFramesPerPacket * format.channels))
        } catch (_: CorruptStreamException) {
            // Reported, with its offset, when the packet is decoded for real.
        }
        reset()
    }

    companion object {
        /** A decoder over an ADTS stream, each frame a packet. */
        fun openAdts(reader: SourceReader, options: DecodeOptions): AudioDecoder {
            val stream = AdtsStream.parse(reader, options)
            val first = stream.first
            val config = AacStreamConfig(
                objectType = first.profile + 1,
                sampleRate = first.sampleRate,
                channelConfiguration = first.channelConfiguration,
                channels = stream.channels,
            )
            val codec = AacPacketCodec(config, first)
            val count = stream.frameCount
            options.checkAllocation(count * 20, "indexing $count ADTS frames")
            val offsets = LongArray(count.toInt())
            val sizes = IntArray(count.toInt())
            val starts = LongArray(count.toInt() + 1)
            var i = 0
            stream.frames(reader) { frame ->
                offsets[i] = frame.offset
                sizes[i] = frame.header.frameBytes
                starts[i + 1] = starts[i] + frame.header.rawBlocks * AacConfig.FRAMES_PER_BLOCK
                i++
                i < offsets.size
            }
            if (i != offsets.size) throw CorruptStreamException("the ADTS stream changed while it was being read")
            val index = PacketIndex(offsets, sizes, starts)
            if (index.count > 0) {
                val packet = ByteArray(sizes[0])
                reader.seek(offsets[0])
                reader.readFully(packet)
                codec.screen(packet, packet.size)
            }
            val info = stream.info(reader)
            return PacketStreamDecoder.of(reader, index, codec, info, 0L, index.totalFrames, options)
        }
    }
}

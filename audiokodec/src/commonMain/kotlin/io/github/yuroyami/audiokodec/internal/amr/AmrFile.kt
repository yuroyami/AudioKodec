package io.github.yuroyami.audiokodec.internal.amr

import io.github.yuroyami.audiokodec.AudioChannel
import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioInfo
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.internal.PacketIndex
import io.github.yuroyami.audiokodec.internal.PacketStreamDecoder
import io.github.yuroyami.audiokodec.internal.io.SourceReader

/**
 * An AMR or AMR-WB file in the storage format of RFC 4867 section 5: a magic
 * line naming the codec, for the multichannel variant a word naming the
 * channel count, and then the frames, each a header byte whose frame type
 * gives its size followed by its speech bits. A multichannel file stores one
 * frame per channel for each 20 ms, in the channel order of RFC 3551
 * section 4.1.
 *
 * The frames carry no index, so opening a file walks every frame header once.
 * Each 20 ms block of frames is a packet. AMR's decoders carry their filters,
 * gain predictors and comfort noise from frame to frame, so a seek decodes
 * from [AmrPacketCodec.PRE_ROLL_FRAMES] blocks before its target.
 */
internal class AmrFile private constructor(
    val info: AudioInfo,
    private val index: PacketIndex,
    private val wide: Boolean,
    /** For each channel in the file, its position in AudioKodec's order. */
    private val placement: IntArray,
) {
    fun decoder(reader: SourceReader, options: DecodeOptions): AudioDecoder {
        val samples = AmrPacketCodec.samplesPerFrame(wide)
        options.checkAllocation(samples.toLong() * info.format.channels * 4, "decoding an AMR block")
        val codec = AmrPacketCodec(wide, info.format, placement, samples)
        return PacketStreamDecoder.of(reader, index, codec, info, 0L, index.totalFrames, options)
    }

    companion object {
        private const val NB_MAGIC = "#!AMR\n"
        private const val WB_MAGIC = "#!AMR-WB\n"
        private const val NB_MC_MAGIC = "#!AMR_MC1.0\n"
        private const val WB_MC_MAGIC = "#!AMR-WB_MC1.0\n"

        fun open(reader: SourceReader, options: DecodeOptions): AmrFile {
            reader.seek(0)
            val head = ByteArray(15)
            val got = reader.readSome(head, 0, head.size).coerceAtLeast(0)
            val text = head.decodeToString(0, got)
            val wide: Boolean
            val multichannel: Boolean
            var position: Long
            when {
                text.startsWith(NB_MC_MAGIC) -> { wide = false; multichannel = true; position = NB_MC_MAGIC.length.toLong() }
                text.startsWith(WB_MC_MAGIC) -> { wide = true; multichannel = true; position = WB_MC_MAGIC.length.toLong() }
                text.startsWith(WB_MAGIC) -> { wide = true; multichannel = false; position = WB_MAGIC.length.toLong() }
                text.startsWith(NB_MAGIC) -> { wide = false; multichannel = false; position = NB_MAGIC.length.toLong() }
                else -> throw CorruptStreamException("an AMR file does not begin with an AMR magic line", reader.absoluteOffset(0))
            }
            var channels = 1
            if (multichannel) {
                reader.seek(position)
                if (reader.size in 0 until position + 4) {
                    throw TruncatedStreamException("an AMR file ends inside its channel description", reader.absoluteOffset(position))
                }
                channels = (reader.readI32Be() and 0x0f)
                if (channels == 0) throw CorruptStreamException("an AMR file's channel description states no channels", reader.absoluteOffset(position))
                position += 4
            }
            val dataStart = position

            // Walk the frame headers, a block of one frame per channel at a time.
            var offsets = LongArray(1024)
            var lengths = IntArray(1024)
            var blocks = 0
            val buffer = ByteArray(64 * 1024)
            var bufferStart = position
            var bufferEnd = position
            fun byteAt(at: Long): Int {
                if (at >= bufferEnd || at < bufferStart) {
                    reader.seek(at)
                    val n = reader.readSome(buffer, 0, buffer.size)
                    if (n <= 0) return -1
                    bufferStart = at
                    bufferEnd = at + n
                }
                return buffer[(at - bufferStart).toInt()].toInt() and 0xff
            }
            var failure: Exception? = null
            walk@ while (true) {
                val blockStart = position
                if (byteAt(position) < 0) break
                for (c in 0 until channels) {
                    val header = byteAt(position)
                    if (header < 0) {
                        failure = TruncatedStreamException("an AMR file ends part way through a block of frames", reader.absoluteOffset(position))
                        break@walk
                    }
                    val size = AmrPacketCodec.frameBytes(wide, header)
                    if ((header and 0x80) != 0 || size < 0) {
                        failure = CorruptStreamException(
                            if (size < 0) "an AMR frame states frame type ${(header shr 3) and 0x0f}, which the storage format reserves"
                            else "an AMR frame header sets the bit the storage format keeps clear",
                            reader.absoluteOffset(position),
                        )
                        break@walk
                    }
                    if (size > 1 && byteAt(position + size - 1) < 0) {
                        failure = TruncatedStreamException("an AMR file ends part way through a frame", reader.absoluteOffset(position))
                        break@walk
                    }
                    position += size
                }
                if (blocks == offsets.size) {
                    options.checkAllocation(blocks * 2L * 12, "indexing an AMR file")
                    offsets = offsets.copyOf(blocks * 2)
                    lengths = lengths.copyOf(blocks * 2)
                }
                offsets[blocks] = blockStart
                lengths[blocks] = (position - blockStart).toInt()
                blocks++
            }
            if (failure != null && (!options.tolerant || blocks == 0)) throw failure

            val samples = AmrPacketCodec.samplesPerFrame(wide)
            val starts = LongArray(blocks + 1) { it.toLong() * samples }
            val packetIndex = PacketIndex(offsets.copyOf(blocks), lengths.copyOf(blocks), starts)
            val rate = if (wide) 16_000 else 8_000
            val order = speakers(channels)
            val layout = ChannelLayout.of(order.sortedBy { if (it == AudioChannel.UNKNOWN) Int.MAX_VALUE else it.ordinal })
            val placement = if (order.contains(AudioChannel.UNKNOWN)) IntArray(channels) { it } else IntArray(channels) { layout.channels.indexOf(order[it]) }
            val frames = starts[blocks]
            val audioBytes = if (blocks == 0) 0L else offsets[blocks - 1] + lengths[blocks - 1] - dataStart
            val info = AudioInfo(
                container = if (wide) AudioContainer.AMR_WB else AudioContainer.AMR_NB,
                codec = if (wide) AudioCodec.AMR_WB else AudioCodec.AMR_NB,
                format = AudioFormat(rate, if (order.contains(AudioChannel.UNKNOWN)) ChannelLayout.unknown(channels) else layout),
                durationFrames = frames,
                bitDepth = null,
                bitrate = if (frames > 0) (audioBytes.toDouble() * 8 * rate / frames).toInt() else null,
                isVariableBitrate = true,
                dataOffset = reader.absoluteOffset(dataStart),
                dataSize = audioBytes,
            )
            return AmrFile(info, packetIndex, wide, placement)
        }

        /** The speakers of RFC 3551 section 4.1, in the file's order. */
        private fun speakers(channels: Int): List<AudioChannel> = when (channels) {
            1 -> listOf(AudioChannel.FRONT_CENTER)
            2 -> listOf(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT)
            3 -> listOf(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_CENTER)
            4 -> listOf(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_CENTER, AudioChannel.FRONT_RIGHT, AudioChannel.BACK_CENTER)
            5 -> listOf(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_CENTER, AudioChannel.BACK_LEFT, AudioChannel.BACK_RIGHT)
            6 -> listOf(
                AudioChannel.FRONT_LEFT, AudioChannel.FRONT_LEFT_OF_CENTER, AudioChannel.FRONT_CENTER,
                AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_RIGHT_OF_CENTER, AudioChannel.BACK_CENTER,
            )
            else -> List(channels) { AudioChannel.UNKNOWN }
        }
    }
}

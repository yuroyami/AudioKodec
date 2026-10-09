/*
 * Translated from the TTA Hardware Players Library 1.2, which is Copyright
 * (c) 2004 True Audio Software and licensed under the BSD 3-Clause licence,
 * reproduced in NOTICE. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.tta

import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioInfo
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.PacketCodec
import io.github.yuroyami.audiokodec.internal.PacketIndex
import io.github.yuroyami.audiokodec.internal.PacketStreamDecoder
import io.github.yuroyami.audiokodec.internal.io.SourceReader

/**
 * A True Audio file: its 22-byte `TTA1` header, the seek table after it,
 * and the frames the seek table measures, as `open_tta_file` and
 * `player_init` read them in the reference.
 *
 * Every frame is 256/245 of a second of audio, except the last, and starts
 * its decoder afresh, so a frame is a packet that decodes alone and seeking
 * needs no run-up.
 */
internal class TtaFile private constructor(
    val info: AudioInfo,
    private val index: PacketIndex,
    private val frameLength: Int,
    private val bitsPerSample: Int,
) {
    fun decoder(reader: SourceReader, options: DecodeOptions): AudioDecoder {
        options.checkAllocation(frameLength.toLong() * info.format.channels * 4, "decoding a True Audio frame")
        return PacketStreamDecoder.of(reader, index, Codec(info.format), info, 0L, index.totalFrames, options)
    }

    private inner class Codec(override val format: AudioFormat) : PacketCodec {
        private val channels = format.channels
        private val decoder = TtaFrameDecoder(channels, bitsPerSample)
        private val samples = IntArray(frameLength * channels)
        private val scale = 1f / (1 shl (bitsPerSample - 1)).toFloat()

        // Depths short of whole bytes are coded high-aligned, as WAV stores them,
        // and given right-justified, as AudioKodec's WAV reader gives them.
        private val padding = (bitsPerSample + 7) / 8 * 8 - bitsPerSample
        private var frame = 0

        override val storedBitDepth: Int = bitsPerSample
        override val maxFramesPerPacket: Int = frameLength
        override val preRollFrames: Long = 0

        override fun beginPacket(index: Int) {
            frame = index
        }

        private fun decodeFrame(packet: ByteArray, length: Int): Int {
            val blocks = (index.starts[frame + 1] - index.starts[frame]).toInt()
            decoder.decode(packet, length, blocks, samples)
            return blocks
        }

        override fun decode(packet: ByteArray, length: Int, out: FloatArray): Int {
            val blocks = decodeFrame(packet, length)
            for (i in 0 until blocks * channels) out[i] = (samples[i] shr padding) * scale
            return blocks
        }

        override fun decodeInt(packet: ByteArray, length: Int, out: IntArray): Int {
            val blocks = decodeFrame(packet, length)
            for (i in 0 until blocks * channels) out[i] = samples[i] shr padding
            return blocks
        }

        override fun reset() = Unit
    }

    companion object {
        private const val HEADER_BYTES = 22
        private const val FORMAT_PCM = 1
        private const val FORMAT_ENCRYPTED = 2
        private const val FORMAT_FLOAT = 3

        private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        private fun u32(b: ByteArray, at: Int): Long = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)

        fun open(reader: SourceReader, options: DecodeOptions): TtaFile {
            val size = reader.size
            val header = ByteArray(HEADER_BYTES)
            reader.seek(0)
            reader.readFully(header)
            if (header.decodeToString(0, 4) != "TTA1") throw CorruptStreamException("a True Audio file does not begin with TTA1", reader.absoluteOffset(0))
            val at = reader.absoluteOffset(0)
            if (TtaFrameDecoder.crc32(header, HEADER_BYTES - 4) != u32(header, HEADER_BYTES - 4).toInt()) {
                throw CorruptStreamException("the True Audio header fails its checksum", at)
            }
            val format = u16(header, 4)
            val channels = u16(header, 6)
            val bits = u16(header, 8)
            val rate = u32(header, 10)
            val length = u32(header, 14)
            when (format) {
                FORMAT_PCM -> Unit
                FORMAT_ENCRYPTED -> throw UnsupportedFeatureException(
                    "password-protected True Audio",
                    "this True Audio file is encrypted with a password, which AudioKodec does not take",
                )
                FORMAT_FLOAT -> throw UnsupportedFeatureException(
                    "floating-point True Audio",
                    "this True Audio file states floating-point samples, which the reference does not decode either",
                )
                else -> throw CorruptStreamException("a True Audio file states format $format", at)
            }
            if (channels == 0) throw CorruptStreamException("a True Audio file states no channels", at)
            if (bits !in 1..24) throw CorruptStreamException("a True Audio file states $bits bits per sample", at)
            if (rate !in 1..Int.MAX_VALUE.toLong()) throw CorruptStreamException("a True Audio file states a sample rate of $rate", at)

            // FRAME_TIME is 256/245 of a second; the reference's double gives the same
            // whole number as this exact division for every rate.
            val frameLength = 256L * rate / 245
            if (frameLength !in 1..Int.MAX_VALUE.toLong() / 8 || frameLength * channels > Int.MAX_VALUE / 4) {
                throw CorruptStreamException("a True Audio file's frames would hold $frameLength samples per channel", at)
            }
            val lastLength = length % frameLength
            val frames = (length / frameLength + if (lastLength != 0L) 1 else 0).toInt()

            val tableBytes = (frames + 1L) * 4
            if (size >= 0 && HEADER_BYTES + tableBytes > size) {
                throw TruncatedStreamException("the True Audio seek table runs past the end of the file", reader.absoluteOffset(HEADER_BYTES.toLong()))
            }
            options.checkAllocation(tableBytes, "reading a True Audio seek table")
            val table = ByteArray(tableBytes.toInt())
            reader.seek(HEADER_BYTES.toLong())
            reader.readFully(table)
            if (TtaFrameDecoder.crc32(table, table.size - 4) != u32(table, table.size - 4).toInt()) {
                throw CorruptStreamException("the True Audio seek table fails its checksum", reader.absoluteOffset(HEADER_BYTES.toLong()))
            }

            val offsets = LongArray(frames)
            val sizes = IntArray(frames)
            var kept = frames
            var position = HEADER_BYTES + tableBytes
            for (f in 0 until frames) {
                val bytes = u32(table, f * 4)
                if (bytes < 4 || bytes > Int.MAX_VALUE / 2) {
                    throw CorruptStreamException("True Audio frame $f states $bytes bytes", reader.absoluteOffset(HEADER_BYTES + f * 4L))
                }
                if (size >= 0 && position + bytes > size) {
                    if (!options.tolerant) {
                        throw TruncatedStreamException("True Audio frame $f ends past the end of the file", reader.absoluteOffset(position))
                    }
                    kept = f
                    break
                }
                offsets[f] = position
                sizes[f] = bytes.toInt()
                position += bytes
            }

            val starts = LongArray(kept + 1) { f -> minOf(f * frameLength, length) }
            val index = PacketIndex(offsets.copyOf(kept), sizes.copyOf(kept), starts)
            val play = starts[kept]
            val audioBytes = if (kept == 0) 0L else offsets[kept - 1] + sizes[kept - 1] - (HEADER_BYTES + tableBytes)
            val info = AudioInfo(
                container = AudioContainer.TTA,
                codec = AudioCodec.TTA,
                format = AudioFormat(rate.toInt(), ChannelLayout.canonical(channels)),
                durationFrames = play,
                bitDepth = bits,
                bitrate = if (play > 0) (audioBytes.toDouble() * 8 * rate / play).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt() else null,
                isVariableBitrate = true,
                dataOffset = reader.absoluteOffset(HEADER_BYTES + tableBytes),
                dataSize = audioBytes,
            )
            return TtaFile(info, index, frameLength.toInt(), bits)
        }
    }
}

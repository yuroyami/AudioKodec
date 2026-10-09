/*
 * Translated from libmpcdec r495, which is Copyright (c) 2005-2009 The Musepack
 * Development Team and licensed under the BSD 3-Clause licence, reproduced in
 * NOTICE. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.musepack

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
 * A Musepack SV8 stream: its `MPCK` magic, the blocks before the audio, of
 * which the stream header `SH` matters, and the audio packets `AP` up to the
 * stream end `SE`, as `mpc_demux` reads them.
 *
 * Each audio packet holds 2^n frames of 1152 samples, and its first frame is
 * a key frame that every later one in the packet is coded against, so a
 * packet decodes alone. The synthesis filter carries history from packet to
 * packet, which a frame of run-up settles.
 */
internal class MusepackFile private constructor(
    val info: AudioInfo,
    private val index: PacketIndex,
    private val header: Header,
    private val play: Long,
) {
    fun decoder(reader: SourceReader, options: DecodeOptions): AudioDecoder {
        val samples = header.blockFrames.toLong() * FRAME_LENGTH * header.channels
        options.checkAllocation(samples * 4, "decoding a Musepack audio packet")
        return PacketStreamDecoder.of(reader, index, Codec(info.format), info, header.skip, play, options)
    }

    private class Header(
        val samples: Long,
        val silence: Long,
        val rate: Int,
        val maxBand: Int,
        val channels: Int,
        val midSide: Boolean,
        val blockFrames: Int,
    ) {
        /** Decoded samples before the recording: the synthesis filter's delay and the encoder's leading silence. */
        val skip: Long get() = SYNTH_DELAY + silence
        val play: Long get() = samples - silence

        /** Frames the reference decodes, which run on until the delayed signal is out. */
        val frames: Long get() = (samples + SYNTH_DELAY + FRAME_LENGTH - 1) / FRAME_LENGTH
    }

    private inner class Codec(override val format: AudioFormat) : PacketCodec {
        private val decoder = MusepackFrameDecoder(header.channels, header.maxBand, header.midSide)
        private var packet = 0

        override val storedBitDepth: Int? = null
        override val maxFramesPerPacket: Int = header.blockFrames * FRAME_LENGTH
        override val preRollFrames: Long = FRAME_LENGTH.toLong()

        override fun beginPacket(index: Int) {
            packet = index
        }

        override fun decode(packet: ByteArray, length: Int, out: FloatArray): Int {
            val first = this.packet.toLong() * header.blockFrames
            val frames = minOf(header.blockFrames.toLong(), header.frames - first).toInt()
            if (frames <= 0) return 0
            val bits = MusepackBits(packet, 0, length)
            for (f in 0 until frames) {
                // The reference does not synthesise a frame that lies wholly in
                // what it skips at the start, so the filter starts from silence
                // after it; this does the same.
                val synthesise = (first + f) * FRAME_LENGTH + FRAME_LENGTH + SYNTH_DELAY > header.skip
                val at = f * FRAME_LENGTH * header.channels
                if (!synthesise) out.fill(0f, at, at + FRAME_LENGTH * header.channels)
                decoder.decode(bits, f == 0, synthesise, out, at)
            }
            if (frames == header.blockFrames && bits.remaining > 7) {
                throw CorruptStreamException("a Musepack audio packet holds more than its frames")
            }
            return frames * FRAME_LENGTH
        }

        override fun reset() = decoder.reset()
    }

    companion object {
        const val FRAME_LENGTH = 1152
        const val SYNTH_DELAY = 481

        /** `DEMUX_BUFFER_SIZE` less the longest block header: the most a block before the audio may hold. */
        private const val MAX_HEADER_BLOCK = 65536 - 4352 - 11

        private val SAMPLE_RATES = intArrayOf(44100, 48000, 37800, 32000)

        fun open(reader: SourceReader, options: DecodeOptions): MusepackFile {
            val size = reader.size
            val magic = ByteArray(4)
            reader.seek(0)
            reader.readFully(magic)
            val at = reader.absoluteOffset(0)
            if (magic[0] == 'M'.code.toByte() && magic[1] == 'P'.code.toByte() && magic[2] == '+'.code.toByte()) {
                throw UnsupportedFeatureException(
                    "Musepack stream version ${magic[3].toInt() and 15}",
                    "this Musepack file is stream version ${magic[3].toInt() and 15}; AudioKodec decodes stream version 8, which encoders have written since 2009",
                )
            }
            if (magic.decodeToString() != "MPCK") throw CorruptStreamException("a Musepack file does not begin with MPCK", at)

            var position = 4L
            var header: Header? = null
            val offsets = ArrayList<Long>()
            val sizes = ArrayList<Int>()
            while (true) {
                val block = readBlockHeader(reader, position, size, options) ?: break
                val (key, headerBytes, payload) = block
                val payloadStart = position + headerBytes
                if (key == "AP" && header == null) throw CorruptStreamException("a Musepack stream has audio before its stream header", reader.absoluteOffset(position))
                if (header == null && key != "AP" && payload > MAX_HEADER_BLOCK) {
                    throw CorruptStreamException("a Musepack $key block states $payload bytes", reader.absoluteOffset(position))
                }
                if (key == "SE") break
                if (size >= 0 && payloadStart + payload > size) {
                    if (!options.tolerant) throw TruncatedStreamException("a Musepack $key block runs past the end of the file", reader.absoluteOffset(position))
                    break
                }
                when (key) {
                    "SH" -> {
                        val bytes = ByteArray(payload.toInt())
                        reader.seek(payloadStart)
                        reader.readFully(bytes)
                        header = parseStreamHeader(bytes, reader.absoluteOffset(payloadStart))
                    }
                    "AP" -> {
                        if (payload > Int.MAX_VALUE / 2) throw CorruptStreamException("a Musepack audio packet states $payload bytes", reader.absoluteOffset(position))
                        offsets += payloadStart
                        sizes += payload.toInt()
                    }
                }
                position = payloadStart + payload
            }
            // A stream that stops without its end block is complete if its packets
            // hold every frame the header promises; the packet decoder says so if not.
            val stream = header ?: throw CorruptStreamException("a Musepack stream has no stream header", at)

            val blockSamples = stream.blockFrames.toLong() * FRAME_LENGTH
            val coded = stream.frames * FRAME_LENGTH
            val count = offsets.size
            val starts = LongArray(count + 1) { minOf(it * blockSamples, coded) }
            val index = PacketIndex(offsets.toLongArray(), sizes.toIntArray(), starts)
            val play = if (options.tolerant) minOf(stream.play, (index.totalFrames - stream.skip).coerceAtLeast(0)) else stream.play
            val audioBytes = if (count == 0) 0L else offsets[count - 1] + sizes[count - 1] - offsets[0]
            val info = AudioInfo(
                container = AudioContainer.MUSEPACK,
                codec = AudioCodec.MUSEPACK,
                format = AudioFormat(stream.rate, ChannelLayout.canonical(stream.channels)),
                durationFrames = play,
                bitDepth = null,
                bitrate = if (play > 0) (audioBytes.toDouble() * 8 * stream.rate / play).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt() else null,
                isVariableBitrate = true,
                dataOffset = reader.absoluteOffset(if (count > 0) offsets[0] else position),
                dataSize = audioBytes,
            )
            return MusepackFile(info, index, stream, play)
        }

        /**
         * `mpc_bits_get_block`: a two-letter key and a size that counts itself
         * and the key, or null at the end of the file.
         */
        private fun readBlockHeader(reader: SourceReader, position: Long, size: Long, options: DecodeOptions): Triple<String, Int, Long>? {
            if (size >= 0 && position >= size) return null
            val bytes = ByteArray(12)
            reader.seek(position)
            val got = reader.readSome(bytes, 0, bytes.size)
            if (got == 0) return null
            if (got < 3) {
                if (options.tolerant) return null
                throw TruncatedStreamException("a Musepack block header is cut off", reader.absoluteOffset(position))
            }
            val k0 = bytes[0].toInt() and 0xFF
            val k1 = bytes[1].toInt() and 0xFF
            if (k0 !in 'A'.code..'Z'.code || k1 !in 'A'.code..'Z'.code) {
                throw CorruptStreamException("a Musepack block has no valid key", reader.absoluteOffset(position))
            }
            var blockSize = 0L
            var used = 2
            while (true) {
                if (used >= got) {
                    if (options.tolerant) return null
                    throw TruncatedStreamException("a Musepack block header is cut off", reader.absoluteOffset(position))
                }
                if (used >= 11) throw CorruptStreamException("a Musepack block states an impossible size", reader.absoluteOffset(position))
                val b = bytes[used++].toInt() and 0xFF
                blockSize = (blockSize shl 7) or (b and 0x7F).toLong()
                if (b and 0x80 == 0) break
            }
            if (blockSize < 0) throw CorruptStreamException("a Musepack block states an impossible size", reader.absoluteOffset(position))
            // A size smaller than the header is taken as it stands, as the reference takes it.
            val payload = if (blockSize >= used) blockSize - used else blockSize
            return Triple("${k0.toChar()}${k1.toChar()}", used, payload)
        }

        /** `streaminfo_read_header_sv8` and `check_streaminfo`. */
        private fun parseStreamHeader(bytes: ByteArray, at: Long): Header {
            if (bytes.size < 4) throw CorruptStreamException("the Musepack stream header is too short", at)
            val stored = ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
            if (crc32(bytes, 4, bytes.size - 4) != stored) throw CorruptStreamException("the Musepack stream header fails its checksum", at)
            val bits = MusepackBits(bytes, 4, bytes.size)
            val version = bits.read(8)
            if (version != 8) {
                throw UnsupportedFeatureException("Musepack stream version $version", "this Musepack stream states version $version; AudioKodec decodes stream version 8")
            }
            val samples = readSize(bits)
            val silence = readSize(bits)
            val rateIndex = bits.read(3)
            val maxBand = bits.read(5) + 1
            val channels = bits.read(4) + 1
            val midSide = bits.read(1) != 0
            val blockPower = bits.read(3) * 2
            val rate = if (rateIndex < SAMPLE_RATES.size) SAMPLE_RATES[rateIndex] else 0
            if (maxBand >= 32 || channels > 2 || rate == 0) {
                throw CorruptStreamException("the Musepack stream header states $channels channels, $maxBand bands and rate code $rateIndex", at)
            }
            if (samples < 0 || silence < 0 || silence > samples || samples > Long.MAX_VALUE / 4) {
                throw CorruptStreamException("the Musepack stream header states $samples samples after $silence of silence", at)
            }
            return Header(samples, silence, rate, maxBand, channels, midSide, 1 shl blockPower)
        }

        /** `mpc_bits_get_size`: seven bits a byte, most significant first, while the top bit is set. */
        private fun readSize(bits: MusepackBits): Long {
            var value = 0L
            var count = 0
            while (true) {
                val b = bits.read(8)
                value = (value shl 7) or (b and 0x7F).toLong()
                if (++count > 9) return -1
                if (b and 0x80 == 0) return value
            }
        }

        private val CRC_TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }

        private fun crc32(data: ByteArray, from: Int, length: Int): Int {
            var crc = -1
            for (i in from until from + length) crc = (crc ushr 8) xor CRC_TABLE[(crc xor data[i].toInt()) and 0xFF]
            return crc.inv()
        }
    }
}

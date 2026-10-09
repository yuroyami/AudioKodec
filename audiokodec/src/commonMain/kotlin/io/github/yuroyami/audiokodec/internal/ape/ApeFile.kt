/*
 * Translated from the Monkey's Audio SDK 13.27, which is Copyright 2000-2026
 * Matthew T. Ashland and licensed under the BSD 3-Clause licence, reproduced
 * in NOTICE. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.ape

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
 * A Monkey's Audio file: its descriptor, header and seek table, `CAPEHeader`
 * in the reference, and the frames they locate.
 *
 * Every frame starts its predictors and range coder afresh, so a frame is a
 * packet that decodes alone, and seeking needs no run-up. Frames are read
 * from four bytes before their start at most, so that their 32-bit words
 * line up with the first frame's, and four bytes past their end, as the
 * reference reads them.
 */
internal class ApeFile private constructor(
    val info: AudioInfo,
    private val index: PacketIndex,
    private val skipBytes: IntArray,
    private val frameBytes: IntArray,
    private val blocksPerFrame: Int,
    private val level: Int,
    private val bitsPerSample: Int,
    private val isFloat: Boolean,
) {
    fun decoder(reader: SourceReader, options: DecodeOptions): AudioDecoder {
        options.checkAllocation(blocksPerFrame.toLong() * info.format.channels * 4, "decoding a Monkey's Audio frame")
        val codec = Codec(info.format, if (isFloat) null else bitsPerSample)
        return PacketStreamDecoder.of(reader, index, codec, info, 0L, index.totalFrames, options)
    }

    private inner class Codec(
        override val format: AudioFormat,
        override val storedBitDepth: Int?,
    ) : PacketCodec {
        private val channels = format.channels
        private val decoder = ApeFrameDecoder(level, bitsPerSample, channels)
        private val samples = IntArray(blocksPerFrame * channels)
        private val scale = 1f / (1L shl (bitsPerSample - 1)).toFloat()
        private var frame = 0

        override val maxFramesPerPacket: Int = blocksPerFrame
        override val preRollFrames: Long = 0

        override fun beginPacket(index: Int) {
            frame = index
        }

        private fun decodeFrame(packet: ByteArray, length: Int): Int {
            val blocks = (index.starts[frame + 1] - index.starts[frame]).toInt()
            val logical = skipBytes[frame] + frameBytes[frame] + 4
            decoder.decode(packet, length, logical, skipBytes[frame], blocks, samples)
            return blocks
        }

        override fun decode(packet: ByteArray, length: Int, out: FloatArray): Int {
            val blocks = decodeFrame(packet, length)
            val count = blocks * channels
            if (storedBitDepth == null) {
                for (i in 0 until count) out[i] = Float.fromBits(ApeFrameDecoder.floatBits(samples[i]))
            } else {
                for (i in 0 until count) out[i] = samples[i] * scale
            }
            return blocks
        }

        override fun decodeInt(packet: ByteArray, length: Int, out: IntArray): Int {
            val blocks = decodeFrame(packet, length)
            samples.copyInto(out, 0, 0, blocks * channels)
            return blocks
        }

        override fun reset() = Unit
    }

    companion object {
        private const val SEARCH_BYTES = 1024 * 1024

        private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        private fun u32(b: ByteArray, at: Int): Long =
            (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)

        fun open(reader: SourceReader, options: DecodeOptions): ApeFile {
            val size = reader.size
            val junk = findDescriptor(reader)
            val common = ByteArray(8)
            reader.seek(junk)
            reader.readFully(common)
            val version = u16(common, 4)
            if (version < 3990) {
                throw UnsupportedFeatureException(
                    "Monkey's Audio files from before version 3.99",
                    "this Monkey's Audio file was written by version ${version / 1000}.${version % 1000 / 10} of the encoder; " +
                        "AudioKodec decodes the format of version 3.99, from 2004, and later",
                )
            }
            val descriptor = ByteArray(52)
            reader.seek(junk)
            reader.readFully(descriptor)
            val descriptorBytes = u32(descriptor, 8)
            val headerBytes = u32(descriptor, 12)
            val seekTableBytes = u32(descriptor, 16)
            val headerDataBytes = u32(descriptor, 20)
            val frameDataBytes = u32(descriptor, 24) or (u32(descriptor, 28) shl 32)
            if (descriptorBytes < 52 || headerBytes < 24) throw CorruptStreamException("the Monkey's Audio descriptor states impossible sizes", reader.absoluteOffset(junk))
            val header = ByteArray(24)
            reader.seek(junk + descriptorBytes)
            reader.readFully(header)
            val level = u16(header, 0)
            val flags = u16(header, 2)
            val blocksPerFrame = u32(header, 4)
            val finalFrameBlocks = u32(header, 8)
            val totalFrames = u32(header, 12)
            val bits = u16(header, 16)
            val channels = u16(header, 18)
            val rate = u32(header, 20)
            val at = reader.absoluteOffset(junk + descriptorBytes)
            if (level !in setOf(1000, 2000, 3000, 4000, 5000)) throw CorruptStreamException("Monkey's Audio compression level $level does not exist", at)
            if (bits !in setOf(8, 16, 24, 32)) throw CorruptStreamException("a Monkey's Audio file states $bits bits per sample", at)
            if (channels !in 1..32) throw CorruptStreamException("a Monkey's Audio file states $channels channels", at)
            if (channels == 7) {
                // The encoder's channel preparation skips the seventh channel's
                // bytes without consuming them, so these files are damaged as
                // they are written; the reference decoder fails their checksums too.
                throw UnsupportedFeatureException(
                    "Monkey's Audio with seven channels",
                    "Monkey's Audio writes seven-channel files that even its own decoder cannot read back",
                )
            }
            val maxBlocks = if (level >= 5000) 10_000_000L else 1_000_000L
            if (blocksPerFrame <= 0 || blocksPerFrame > maxBlocks || finalFrameBlocks > blocksPerFrame || (totalFrames > 0 && finalFrameBlocks == 0L)) {
                throw CorruptStreamException("the Monkey's Audio frame sizes are impossible", at)
            }
            if (rate !in 1..Int.MAX_VALUE.toLong()) throw CorruptStreamException("a Monkey's Audio file states a sample rate of $rate", at)
            options.checkStreamShape(rate.toInt(), channels, at)
            val isFloat = flags and FLAG_FLOATING_POINT != 0
            if (isFloat && bits != 32) throw CorruptStreamException("a Monkey's Audio file states $bits-bit floating-point samples", at)

            // The seek table: 32-bit offsets of every frame, which may wrap past 4 GiB.
            val elements = seekTableBytes / 4
            if (elements < totalFrames) throw CorruptStreamException("the Monkey's Audio seek table has fewer entries than the file has frames", at)
            if (totalFrames > Int.MAX_VALUE / 8) throw CorruptStreamException("a Monkey's Audio file states $totalFrames frames", at)
            val frames = totalFrames.toInt()
            options.checkAllocation(frames.toLong() * 32, "indexing $frames Monkey's Audio frames")
            val table = ByteArray(frames * 4)
            reader.seek(junk + descriptorBytes + headerBytes)
            reader.readFully(table)
            val seek = LongArray(frames)
            var add = 0L
            var previous = 0L
            for (f in 0 until frames) {
                val current = u32(table, f * 4)
                if (current < previous) add += 1L shl 32
                seek[f] = junk + add + current
                previous = current
            }

            // The stored WAV header, which may name the speakers.
            val headerData = if (flags and FLAG_CREATE_WAV_HEADER == 0 && headerDataBytes in 1..(16L * 1024 * 1024)) {
                options.checkAllocation(headerDataBytes, "reading a Monkey's Audio file's stored header")
                ByteArray(headerDataBytes.toInt()).also {
                    reader.seek(junk + descriptorBytes + headerBytes + seekTableBytes)
                    reader.readFully(it)
                }
            } else {
                null
            }
            val layout = headerData?.let { waveMask(it) }?.let { ChannelLayout.fromWaveMask(it, channels) } ?: ChannelLayout.canonical(channels)

            val dataStart = junk + descriptorBytes + headerBytes + seekTableBytes + headerDataBytes
            val dataEnd = dataStart + frameDataBytes
            val offsets = LongArray(frames)
            val sizes = IntArray(frames)
            val skip = IntArray(frames)
            val lengths = IntArray(frames)
            var kept = frames
            for (f in 0 until frames) {
                val end = if (f + 1 < frames) seek[f + 1] else dataEnd
                val bytes = end - seek[f]
                if (seek[f] < dataStart || bytes <= 0 || bytes > Int.MAX_VALUE / 2) {
                    throw CorruptStreamException("Monkey's Audio frame $f has an impossible place in the file", reader.absoluteOffset(seek[0]))
                }
                if (size >= 0 && end > size) {
                    if (!options.tolerant) {
                        throw TruncatedStreamException("Monkey's Audio frame $f ends past the end of the file", reader.absoluteOffset(seek[f]))
                    }
                    kept = f
                    break
                }
                val remainder = ((seek[f] - seek[0]) % 4).toInt()
                skip[f] = remainder
                lengths[f] = bytes.toInt()
                offsets[f] = seek[f] - remainder
                // The reference reads four bytes past each frame. They belong to the next
                // frame, or to nothing after the last one, where the logical length pads
                // them with zeros instead.
                val wanted = bytes + remainder + if (f == frames - 1) 0 else 4
                sizes[f] = (if (size >= 0) minOf(bytes + remainder + 4, size - offsets[f]) else wanted).toInt()
            }
            val totalBlocks = if (frames == 0) 0L else (frames - 1L) * blocksPerFrame + finalFrameBlocks
            val starts = LongArray(kept + 1) { f -> minOf(f.toLong() * blocksPerFrame, totalBlocks) }
            val index = PacketIndex(offsets.copyOf(kept), sizes.copyOf(kept), starts)
            val play = starts[kept]
            val audioBytes = if (kept == 0) 0L else (if (kept < frames) seek[kept] else dataEnd) - seek[0]
            val format = AudioFormat(rate.toInt(), layout)
            val info = AudioInfo(
                container = AudioContainer.APE,
                codec = AudioCodec.APE,
                format = format,
                durationFrames = play,
                bitDepth = bits,
                bitrate = if (play > 0) (audioBytes.toDouble() * 8 * rate / play).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt() else null,
                isVariableBitrate = true,
                dataOffset = reader.absoluteOffset(if (frames > 0) seek[0] else dataStart),
                dataSize = audioBytes,
            )
            return ApeFile(info, index, skip, lengths, blocksPerFrame.toInt(), level, bits, isFloat)
        }

        /** Where the descriptor starts: at the start, or within the first megabyte, as `FindDescriptor` looks. */
        private fun findDescriptor(reader: SourceReader): Long {
            val window = ByteArray(64 * 1024)
            var at = 0L
            while (at <= SEARCH_BYTES) {
                reader.seek(at)
                val got = reader.readSome(window, 0, window.size)
                if (got < 4) break
                for (i in 0..got - 4) {
                    if (window[i] == 'M'.code.toByte() && window[i + 1] == 'A'.code.toByte() && window[i + 2] == 'C'.code.toByte() &&
                        (window[i + 3] == ' '.code.toByte() || window[i + 3] == 'F'.code.toByte())
                    ) {
                        return at + i
                    }
                }
                at += got - 3
            }
            throw CorruptStreamException("no Monkey's Audio descriptor was found", 0)
        }

        /** The channel mask of a stored WAVE_FORMAT_EXTENSIBLE header, or null. */
        private fun waveMask(header: ByteArray): Int? {
            if (header.size < 12 || header.decodeToString(0, 4) != "RIFF" && header.decodeToString(0, 4) != "RF64") return null
            var at = 12
            while (at + 8 <= header.size) {
                val id = header.decodeToString(at, at + 4)
                val length = u32(header, at + 4)
                if (id == "fmt ") {
                    if (length < 40 || at + 8 + 24 > header.size) return null
                    if (u16(header, at + 8) != 0xFFFE) return null
                    return u32(header, at + 8 + 20).toInt()
                }
                if (length > header.size) return null
                at += 8 + length.toInt() + (length.toInt() and 1)
            }
            return null
        }

        private const val FLAG_CREATE_WAV_HEADER = 1 shl 5
        private const val FLAG_FLOATING_POINT = 1 shl 12
    }
}

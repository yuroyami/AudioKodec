/*
 * Translated from WavPack 5.9.0, commit 5803634a030e2a11dba602ba057b89cc34486c67,
 * which is Copyright (c) 1998 - 2025 David Bryant and licensed under the
 * BSD 3-Clause licence, reproduced in NOTICE. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.wavpack

import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioInfo
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.internal.PacketCodec
import io.github.yuroyami.audiokodec.internal.PacketIndex
import io.github.yuroyami.audiokodec.internal.PacketStreamDecoder
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import kotlin.math.pow

/** The 32-byte header every WavPack block starts with. */
internal class WavPackHeader(
    /** Bytes of the whole block, header included: `ckSize + 8`. */
    val blockBytes: Int,
    val version: Int,
    /** The first sample of the block, from the start of the file's audio. */
    val blockIndex: Long,
    /** Samples in the whole file, or -1 when unknown; meaningful in the first block. */
    val totalSamples: Long,
    /** Samples in this block, 0 for a block of metadata only. */
    val blockSamples: Int,
    val flags: Int,
    val crc: Int,
) {
    val isInitial: Boolean get() = flags and WavPackStream.INITIAL_BLOCK != 0
    val isFinal: Boolean get() = flags and WavPackStream.FINAL_BLOCK != 0

    /** Channels this block carries: one for mono, two for stereo or false stereo. */
    val channels: Int get() = if (flags and WavPackStream.MONO_FLAG != 0) 1 else 2

    companion object {
        const val SIZE: Int = 32

        private fun u32(bytes: ByteArray, at: Int): Long =
            (bytes[at].toLong() and 0xFF) or ((bytes[at + 1].toLong() and 0xFF) shl 8) or
                ((bytes[at + 2].toLong() and 0xFF) shl 16) or ((bytes[at + 3].toLong() and 0xFF) shl 24)

        /**
         * The header at [at] in [bytes], or null when the 32 bytes there fail
         * the reference's own test of a header, `read_next_header`: an even
         * size from 24 bytes to 16 MiB, a stream version from 0x402 to 0x410,
         * and fewer than 196608 samples.
         */
        fun parse(bytes: ByteArray, at: Int): WavPackHeader? {
            if (bytes.size - at < SIZE) return null
            fun u(i: Int) = bytes[at + i].toInt() and 0xFF
            if (u(0) != 'w'.code || u(1) != 'v'.code || u(2) != 'p'.code || u(3) != 'k'.code) return null
            if (u(4) and 1 != 0 || u(6) >= 16 || u(7) != 0 || (u(6) == 0 && u(5) == 0 && u(4) < 24)) return null
            if (u(9) != 4 || u(8) < 2 || u(8) > 0x10 || u(22) >= 3 || u(23) != 0) return null
            val size = u32(bytes, at + 4).toInt() + 8
            val totalLow = u32(bytes, at + 12)
            val totalHigh = u(11).toLong()
            val total = if (totalLow == 0xFFFF_FFFFL) -1L else totalLow + (totalHigh shl 32) - totalHigh
            return WavPackHeader(
                blockBytes = size,
                version = u(8) or (u(9) shl 8),
                blockIndex = u32(bytes, at + 16) + (u(10).toLong() shl 32),
                totalSamples = total,
                blockSamples = u32(bytes, at + 20).toInt(),
                flags = u32(bytes, at + 24).toInt(),
                crc = u32(bytes, at + 28).toInt(),
            )
        }

        /**
         * True when the block's metadata items exactly fill it and, when the
         * block carries a checksum, the checksum matches:
         * `WavpackVerifySingleBlock`.
         */
        fun verify(block: ByteArray, offset: Int, header: WavPackHeader): Boolean {
            var remaining = header.blockBytes - SIZE
            var at = offset + SIZE
            var checksumPassed = false
            while (remaining >= 2) {
                val id = block[at++].toInt() and 0xFF
                var length = (block[at++].toInt() and 0xFF) shl 1
                remaining -= 2
                if (id and WavPackStream.ID_LARGE != 0) {
                    if (remaining < 2) return false
                    length += ((block[at++].toInt() and 0xFF) shl 9) + ((block[at++].toInt() and 0xFF) shl 17)
                    remaining -= 2
                }
                if (remaining < length) return false
                if (id and WavPackStream.ID_UNIQUE == WavPackStream.ID_BLOCK_CHECKSUM) {
                    if (id and WavPackStream.ID_ODD_SIZE != 0 || length < 2 || length > 4) return false
                    // A running sum over the block's 16-bit words up to this item.
                    val words = (at - 2 - offset) shr 1
                    var sum = -1
                    for (w in 0 until words) {
                        sum = sum * 3 + ((block[offset + 2 * w].toInt() and 0xFF) or ((block[offset + 2 * w + 1].toInt() and 0xFF) shl 8))
                    }
                    if (length == 4) {
                        for (b in 0 until 4) if (block[at + b].toInt() and 0xFF != (sum ushr (8 * b)) and 0xFF) return false
                    } else {
                        sum = sum xor (sum ushr 16)
                        for (b in 0 until 2) if (block[at + b].toInt() and 0xFF != (sum ushr (8 * b)) and 0xFF) return false
                    }
                    checksumPassed = true
                }
                remaining -= length
                at += length
            }
            return remaining == 0 && (header.flags and WavPackStream.HAS_CHECKSUM == 0 || checksumPassed)
        }
    }
}

/**
 * A WavPack file: where its blocks are, what its first audio block says
 * about the stream, and how its blocks group into the packets
 * [PacketStreamDecoder] reads.
 *
 * Multichannel audio is split into mono and stereo blocks that follow one
 * another from an initial block to a final one, each carrying the same
 * samples of different channels, so one such run is one packet. Blocks
 * stand alone, so seeking needs no run-up.
 */
internal class WavPackFile private constructor(
    val info: AudioInfo,
    private val index: PacketIndex,
    private val format: AudioFormat,
    private val bitsPerSample: Int,
    private val bytesPerSample: Int,
    private val isFloat: Boolean,
    private val lossy: Boolean,
    private val normExponent: Int,
    private val maxBlockSamples: Int,
    private val playFrames: Long,
) {
    fun decoder(reader: SourceReader, options: DecodeOptions): AudioDecoder {
        val codec = Codec(format, if (isFloat || lossy) null else bitsPerSample, maxBlockSamples, bytesPerSample, isFloat, normExponent, options)
        return PacketStreamDecoder.of(reader, index, codec, info, 0L, playFrames, options)
    }

    /** Decodes one run of blocks, from an initial block to a final one. */
    private class Codec(
        override val format: AudioFormat,
        override val storedBitDepth: Int?,
        override val maxFramesPerPacket: Int,
        bytesPerSample: Int,
        private val isFloat: Boolean,
        normExponent: Int,
        options: DecodeOptions,
    ) : PacketCodec {
        private val channels = format.channels
        private val stream = WavPackStream()
        private val scratch: IntArray
        private val all: IntArray
        private val shift = (if (storedBitDepth != null) bytesPerSample * 8 - storedBitDepth else 0)
        private val intScale = 1f / (1L shl (bytesPerSample * 8 - 1)).toFloat()
        private val floatScale = 2.0.pow(127 - normExponent).toFloat()

        init {
            options.checkAllocation(maxFramesPerPacket.toLong() * (channels + 2) * 4, "decoding a WavPack block")
            scratch = IntArray(maxFramesPerPacket * 2)
            all = IntArray(maxFramesPerPacket * channels)
        }

        override val preRollFrames: Long = 0

        private fun decodeAll(packet: ByteArray, length: Int): Int {
            var at = 0
            var offset = 0
            var frames = -1
            var first = true
            while (offset < channels) {
                val header = WavPackHeader.parse(packet, at)
                    ?: throw CorruptStreamException("a WavPack block header in a run is damaged")
                if (header.blockBytes > length - at) throw CorruptStreamException("a WavPack block runs past its run")
                if (first != header.isInitial) throw CorruptStreamException("a WavPack run does not start with its initial block")
                if (frames < 0) frames = header.blockSamples
                if (header.blockSamples != frames || frames > maxFramesPerPacket) {
                    throw CorruptStreamException("the WavPack blocks of one run disagree about their length")
                }
                if (!WavPackHeader.verify(packet, at, header)) throw CorruptStreamException("a WavPack block fails its block checksum")
                stream.initialise(packet, at, header, channels, null)
                stream.unpack(scratch, frames, header.crc)
                val blockChannels = header.channels
                // A stereo block with room for only one more channel gives its left.
                val used = if (offset == channels - 1) 1 else blockChannels
                for (f in 0 until frames) {
                    val base = f * channels + offset
                    if (blockChannels == 1) {
                        all[base] = scratch[f]
                    } else {
                        all[base] = scratch[2 * f]
                        if (used == 2) all[base + 1] = scratch[2 * f + 1]
                    }
                }
                offset += used
                at += header.blockBytes
                first = false
                if (header.isFinal) break
            }
            if (offset != channels) throw CorruptStreamException("a WavPack run carries $offset of the file's $channels channels")
            return frames
        }

        override fun decode(packet: ByteArray, length: Int, out: FloatArray): Int {
            val frames = decodeAll(packet, length)
            val count = frames * channels
            if (isFloat) {
                for (i in 0 until count) out[i] = Float.fromBits(all[i]) * floatScale
            } else {
                for (i in 0 until count) out[i] = all[i] * intScale
            }
            return frames
        }

        override fun decodeInt(packet: ByteArray, length: Int, out: IntArray): Int {
            val frames = decodeAll(packet, length)
            for (i in 0 until frames * channels) out[i] = all[i] shr shift
            return frames
        }

        override fun reset() = Unit
    }

    companion object {
        private val SAMPLE_RATES = intArrayOf(6000, 8000, 9600, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000, 192000)

        /** The reference gives up looking for the next block after a megabyte of other data. */
        private const val SEARCH_BYTES = 1024 * 1024

        fun open(reader: SourceReader, options: DecodeOptions): WavPackFile {
            val size = reader.size
            val headerBytes = ByteArray(WavPackHeader.SIZE)

            /** The next header at or after [from], and where it is, or null at the end. */
            fun nextHeader(from: Long): Pair<Long, WavPackHeader>? {
                var at = from
                val window = ByteArray(64 * 1024)
                var skipped = 0L
                while (skipped <= SEARCH_BYTES) {
                    reader.seek(at)
                    val got = reader.readSome(window, 0, window.size)
                    if (got < WavPackHeader.SIZE) return null
                    for (i in 0..got - WavPackHeader.SIZE) {
                        WavPackHeader.parse(window, i)?.let { return (at + i) to it }
                    }
                    val advance = got - WavPackHeader.SIZE + 1
                    at += advance
                    skipped += advance
                }
                return null
            }

            // Walk the blocks, grouping each initial-to-final run into a packet.
            val offsets = ArrayList<Long>()
            val sizes = ArrayList<Int>()
            val starts = ArrayList<Long>()
            var first: Pair<Long, WavPackHeader>? = null
            var initialIndex = 0L
            var expected = 0L
            var position = 0L
            var maxBlockSamples = 0
            var runStart = -1L
            var runBytes = 0L
            var runSamples = 0
            var runIndex = 0L
            while (true) {
                val (at, header) = nextHeader(position) ?: break
                if (size >= 0 && header.blockBytes > size - at) {
                    if (!options.tolerant && first != null) {
                        throw TruncatedStreamException("the final WavPack block declares ${header.blockBytes} bytes but the file ends first", reader.absoluteOffset(at))
                    }
                    break
                }
                position = at + header.blockBytes
                if (header.blockSamples == 0) continue
                if (first == null) {
                    first = at to header
                    initialIndex = header.blockIndex
                }
                if (header.isInitial) {
                    if (runStart >= 0 && !options.tolerant) {
                        throw CorruptStreamException("a WavPack run ends without its final block", reader.absoluteOffset(runStart))
                    }
                    runStart = at
                    runBytes = 0
                    runSamples = header.blockSamples
                    runIndex = header.blockIndex - initialIndex
                } else if (runStart < 0 || at != runStart + runBytes) {
                    if (!options.tolerant) throw CorruptStreamException("a WavPack block continues no run", reader.absoluteOffset(at))
                    runStart = -1
                    continue
                }
                runBytes += header.blockBytes
                if (header.isFinal) {
                    if (runIndex != expected && !options.tolerant) {
                        throw CorruptStreamException("WavPack block ${runIndex} follows the samples before $expected", reader.absoluteOffset(runStart))
                    }
                    if (runBytes > Int.MAX_VALUE) throw CorruptStreamException("a WavPack run is too large", reader.absoluteOffset(runStart))
                    offsets += runStart
                    sizes += runBytes.toInt()
                    starts += expected
                    expected += runSamples
                    if (runSamples > maxBlockSamples) maxBlockSamples = runSamples
                    runStart = -1
                }
            }
            if (first == null) throw CorruptStreamException("this WavPack file holds no block of audio", 0)
            if (runStart >= 0 && !options.tolerant) {
                throw TruncatedStreamException("the file ends inside a run of WavPack blocks", reader.absoluteOffset(runStart))
            }
            starts += expected
            val (firstAt, firstHeader) = first

            // The first audio block states the stream's shape.
            if (firstHeader.blockBytes > Int.MAX_VALUE / 2) throw CorruptStreamException("the first WavPack block is too large", reader.absoluteOffset(firstAt))
            options.checkAllocation(firstHeader.blockBytes.toLong(), "reading a WavPack block")
            val block = ByteArray(firstHeader.blockBytes)
            reader.seek(firstAt)
            reader.readFully(block)
            if (!WavPackHeader.verify(block, 0, firstHeader)) {
                throw CorruptStreamException("the first WavPack block fails its block checksum", reader.absoluteOffset(firstAt))
            }
            val facts = WavPackStream.FileFacts()
            val stream = WavPackStream()
            try {
                stream.initialise(block, 0, firstHeader, 0, facts)
            } catch (failure: CorruptStreamException) {
                throw CorruptStreamException(failure.message ?: "the first WavPack block is damaged", reader.absoluteOffset(firstAt))
            }
            val flags = firstHeader.flags
            val channels = if (facts.channels != 0) facts.channels else firstHeader.channels
            val mask = if (facts.channels != 0) facts.channelMask else 0x5 - channels
            val rate = when {
                facts.sampleRate != 0 -> facts.sampleRate
                flags and WavPackStream.SRATE_MASK == WavPackStream.SRATE_MASK -> 44100
                else -> SAMPLE_RATES[(flags and WavPackStream.SRATE_MASK) ushr WavPackStream.SRATE_LSB]
            }
            options.checkStreamShape(rate, channels, reader.absoluteOffset(firstAt))
            val bytesPerSample = (flags and WavPackStream.BYTES_STORED) + 1
            val isFloat = flags and WavPackStream.FLOAT_DATA != 0
            val bits = if (isFloat) 32 else bytesPerSample * 8 - ((flags and WavPackStream.SHIFT_MASK) ushr WavPackStream.SHIFT_LSB)
            if (bits < 1) throw CorruptStreamException("a WavPack block shifts away every bit of its samples", reader.absoluteOffset(firstAt))
            val lossy = flags and WavPackStream.HYBRID_FLAG != 0
            // A mask of zero leaves the speakers unassigned, which beyond
            // stereo is not something to guess.
            val layout = if (mask == 0 && channels > 2) ChannelLayout.unknown(channels) else ChannelLayout.fromWaveMask(mask, channels)

            // The first block states the length; the blocks say what is really there.
            val stated = if (initialIndex == 0L) firstHeader.totalSamples else -1L
            var play = expected
            if (stated >= 0) {
                if (stated > expected && !options.tolerant) {
                    throw TruncatedStreamException("the WavPack file promises $stated samples and its blocks hold $expected", reader.absoluteOffset(firstAt))
                }
                play = minOf(stated, expected)
            }
            val firstOffset = offsets.firstOrNull() ?: firstAt
            val lastEnd = if (offsets.isEmpty()) firstAt else offsets.last() + sizes.last()
            val dataSize = lastEnd - firstOffset
            val info = AudioInfo(
                container = AudioContainer.WAVPACK,
                codec = AudioCodec.WAVPACK,
                format = AudioFormat(rate, layout),
                durationFrames = play,
                bitDepth = if (isFloat) 32 else bits,
                bitrate = if (play > 0) (dataSize.toDouble() * 8 * rate / play).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt() else null,
                isVariableBitrate = true,
                dataOffset = reader.absoluteOffset(firstOffset),
                dataSize = dataSize,
            )
            val index = PacketIndex(offsets.toLongArray(), sizes.toIntArray(), starts.toLongArray())
            return WavPackFile(
                info, index, info.format, bits, bytesPerSample, isFloat, lossy,
                if (isFloat) stream.normExponent else 0, maxOf(maxBlockSamples, 1), play,
            )
        }
    }
}

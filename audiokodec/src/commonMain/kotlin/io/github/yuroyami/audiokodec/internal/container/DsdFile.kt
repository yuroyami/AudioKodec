package io.github.yuroyami.audiokodec.internal.container

import io.github.yuroyami.audiokodec.AudioChannel
import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioFrame
import io.github.yuroyami.audiokodec.AudioInfo
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.dsp.DsdConverter
import io.github.yuroyami.audiokodec.internal.io.SourceReader

/**
 * A DSD stream in a DSF or DSDIFF file: where its bits are and how they are
 * laid out, which is all the two containers differ in.
 *
 * DSF, Sony's format, stores each channel in blocks of a fixed size, 4096
 * bytes, one block per channel in turn, least significant bit first in time,
 * and states the number of samples per channel. DSDIFF, Philips's, stores
 * one byte per channel in turn, most significant bit first, in its `DSD `
 * chunk.
 */
internal class DsdFile private constructor(
    val info: AudioInfo,
    private val dsdRate: Int,
    private val totalBits: Long,
    /** Bytes per channel block in DSF, or 0 for DSDIFF's byte interleaving. */
    private val blockSize: Int,
    private val lsbFirst: Boolean,
    /** Where each of the file's channels goes in AudioKodec's order. */
    private val placement: IntArray,
) {
    fun decoder(reader: SourceReader, options: DecodeOptions): AudioDecoder = Decoder(reader, options)

    private inner class Decoder(private val reader: SourceReader, private val options: DecodeOptions) : AudioDecoder {
        override val info: AudioInfo = this@DsdFile.info
        private val channels = info.format.channels
        private val totalBytes = (totalBits + 7) / 8
        private val converter = DsdConverter()
        private val windows = Array(channels) { ByteArray(0) }
        private var group = ByteArray(0)
        private var buffer: FloatArray? = null
        private var closed = false

        override val format: AudioFormat = info.format
        override val durationFrames: Long? = info.durationFrames
        override var positionFrames: Long = 0L
            private set

        override fun nextFrame(): AudioFrame? {
            check(!closed) { "this decoder is closed" }
            val total = durationFrames ?: 0L
            if (positionFrames >= total) return null
            val count = minOf(FRAMES_PER_READ.toLong(), total - positionFrames).toInt()
            val first = DsdConverter.firstByte(positionFrames).coerceAtLeast(0L)
            val end = DsdConverter.endByte(positionFrames, count).coerceAtMost(totalBytes)
            val span = (end - first).toInt().coerceAtLeast(0)
            for (c in 0 until channels) {
                if (windows[c].size < span) {
                    options.checkAllocation(span.toLong() * channels, "reading DSD bytes")
                    windows[c] = ByteArray(span)
                }
            }
            if (span > 0) fill(first, end)
            val samples = count * channels
            val out = buffer?.takeIf { options.reuseBuffers && it.size >= samples } ?: run {
                options.checkAllocation(samples.toLong() * 4, "decoding $count DSD frames")
                FloatArray(samples).also { if (options.reuseBuffers) buffer = it }
            }
            for (c in 0 until channels) {
                converter.convert(windows[c], first, totalBits, positionFrames, count, out, placement[c], channels)
            }
            val frame = AudioFrame(format = format, samples = out, frameCount = count, positionFrames = positionFrames)
            positionFrames += count
            return frame
        }

        /** Reads bytes [first] to [end] of every channel into [windows], with the bits in time order from the top. */
        private fun fill(first: Long, end: Long) {
            if (blockSize == 0) {
                // DSDIFF: one byte of each channel in turn.
                val bytes = ((end - first) * channels).toInt()
                if (group.size < bytes) group = ByteArray(bytes)
                read(info.dataOffset + first * channels, group, bytes)
                for (i in 0 until (end - first).toInt()) {
                    for (c in 0 until channels) windows[c][i] = group[i * channels + c]
                }
            } else {
                // DSF: a block of each channel in turn.
                val groupBytes = blockSize * channels
                if (group.size < groupBytes) {
                    options.checkAllocation(groupBytes.toLong(), "reading a group of DSF blocks")
                    group = ByteArray(groupBytes)
                }
                var block = first / blockSize
                while (block * blockSize < end) {
                    read(info.dataOffset + block * groupBytes, group, groupBytes)
                    val from = maxOf(first, block * blockSize)
                    val to = minOf(end, (block + 1) * blockSize)
                    for (c in 0 until channels) {
                        val source = c * blockSize + (from - block * blockSize).toInt()
                        group.copyInto(windows[c], (from - first).toInt(), source, source + (to - from).toInt())
                    }
                    block++
                }
                if (lsbFirst) {
                    for (c in 0 until channels) {
                        val window = windows[c]
                        for (i in 0 until (end - first).toInt()) window[i] = REVERSED[window[i].toInt() and 0xFF]
                    }
                }
            }
        }

        private fun read(at: Long, into: ByteArray, count: Int) {
            reader.seek(at)
            val got = reader.readSome(into, 0, count)
            // The last DSF block can stop where its channel's data does; what is missing is unused.
            if (got < count) into.fill(0, got, count)
        }

        override fun seek(frameIndex: Long): Long {
            check(!closed) { "this decoder is closed" }
            require(frameIndex >= 0) { "frameIndex must not be negative, was $frameIndex" }
            positionFrames = frameIndex.coerceAtMost(durationFrames ?: 0L)
            return positionFrames
        }

        override fun close() {
            if (closed) return
            closed = true
            buffer = null
            reader.source.close()
        }
    }

    companion object {
        private const val FRAMES_PER_READ = 4096

        private val REVERSED = ByteArray(256) { byte ->
            var r = 0
            for (i in 0 until 8) if ((byte shr i) and 1 == 1) r = r or (0x80 shr i)
            r.toByte()
        }

        fun open(reader: SourceReader, options: DecodeOptions, container: AudioContainer): DsdFile =
            if (container == AudioContainer.DSF) openDsf(reader, options) else openDff(reader, options)

        private fun openDsf(reader: SourceReader, options: DecodeOptions): DsdFile {
            reader.seek(0)
            reader.expectFourCc("DSD ", "DSF header")
            val dsdSize = reader.readI64Le()
            if (dsdSize != 28L) throw CorruptStreamException("a DSF file's DSD chunk is 28 bytes, this one says $dsdSize", 4)
            reader.readI64Le() // Total file size, which the data chunk's own size makes redundant.
            reader.readI64Le() // Where the ID3v2 metadata is, which the tag reader finds.
            reader.expectFourCc("fmt ", "DSF format chunk")
            val fmtSize = reader.readI64Le()
            if (fmtSize < 52) throw CorruptStreamException("a DSF format chunk is 52 bytes, this one says $fmtSize", 32)
            val version = reader.readU32Le()
            if (version != 1L) throw UnsupportedFeatureException("DSF version $version", "this DSF file states format version $version; version 1 is the one defined")
            val formatId = reader.readU32Le()
            if (formatId != 0L) throw UnsupportedFeatureException("DSF format $formatId", "this DSF file states format $formatId; only raw DSD, format 0, is defined")
            val channelType = reader.readU32Le().toInt()
            val channels = reader.readU32Le().toInt()
            val rate = reader.readI32Le()
            val bitsPerSample = reader.readU32Le().toInt()
            val sampleCount = reader.readI64Le()
            val blockSize = reader.readU32Le()
            if (bitsPerSample != 1 && bitsPerSample != 8) throw CorruptStreamException("DSF samples are 1 or 8 bits, this file says $bitsPerSample", 56)
            if (blockSize !in 1L..1_048_576L) throw CorruptStreamException("a DSF block of $blockSize bytes per channel is not a sensible size", 64)
            // A sample count past 2^56 would overflow the byte arithmetic below, and no file holds one.
            if (sampleCount !in 0L..(1L shl 56)) throw CorruptStreamException("a DSF file states $sampleCount samples per channel", 56)
            val speakers = DSF_LAYOUTS[channelType]
                ?: throw CorruptStreamException("DSF channel type $channelType is not one of the seven defined", 40)
            if (speakers.size != channels) {
                throw CorruptStreamException("DSF channel type $channelType has ${speakers.size} channels, the file says $channels", 44)
            }
            checkRate(rate, channels, options)

            reader.seek(12 + 8 + 8 + fmtSize)
            reader.expectFourCc("data", "DSF data chunk")
            val dataSize = reader.readI64Le() - 12
            if (dataSize < 0) throw CorruptStreamException("a DSF data chunk states a size smaller than its own header", reader.position - 8)
            val dataOffset = reader.position
            val totalBytes = (sampleCount + 7) / 8
            val blocks = (totalBytes + blockSize - 1) / blockSize
            // The last block of each channel is padded, but a writer may stop after the last one's data.
            val needed = if (blocks == 0L) 0L else (blocks - 1) * blockSize * channels + (channels - 1) * blockSize + (totalBytes - (blocks - 1) * blockSize)
            var bits = sampleCount
            val available = minOf(dataSize, if (reader.size >= 0) reader.size - dataOffset else dataSize)
            if (available < needed) {
                if (!options.tolerant) throw TruncatedStreamException("a DSF file's data ends after $available of its $needed bytes", dataOffset + available)
                // Every channel has whole blocks up to the last group that is complete.
                bits = (available / (blockSize * channels)) * blockSize * 8
            }
            return build(AudioContainer.DSF, rate, speakers, bits, blockSize.toInt(), bitsPerSample == 1, reader.absoluteOffset(dataOffset), minOf(dataSize, available), options)
        }

        private fun openDff(reader: SourceReader, options: DecodeOptions): DsdFile {
            reader.seek(0)
            reader.expectFourCc("FRM8", "DSDIFF header")
            val formSize = reader.readI64Be()
            reader.expectFourCc("DSD ", "DSDIFF form type")
            val formEnd = 12 + formSize
            var rate = -1
            var ids: List<String>? = null
            var compression: String? = null
            var at = 16L
            while (true) {
                if (reader.size in 0..at || at + 12 > formEnd) throw CorruptStreamException("a DSDIFF file has no DSD sound data chunk", at)
                reader.seek(at)
                val id = reader.readFourCc()
                val size = reader.readI64Be()
                if (size < 0) throw CorruptStreamException("a DSDIFF chunk '$id' states a negative size", at)
                val body = at + 12
                // Only the sound data may run past the form, which a truncated file's does.
                if (id != "DSD " && size > formEnd - body) throw CorruptStreamException("a DSDIFF chunk '$id' runs past the end of its form", at)
                when (id) {
                    "PROP" -> {
                        reader.expectFourCc("SND ", "DSDIFF property type")
                        var p = body + 4
                        while (p + 12 <= body + size) {
                            reader.seek(p)
                            val local = reader.readFourCc()
                            val localSize = reader.readI64Be()
                            if (localSize < 0 || localSize > body + size - p - 12) {
                                throw CorruptStreamException("a DSDIFF property chunk '$local' runs past the end of its parent", p)
                            }
                            when (local) {
                                "FS  " -> rate = reader.readI32Be()
                                "CHNL" -> {
                                    val count = reader.readU16Be()
                                    if (count < 1 || localSize < 2 + 4L * count) throw CorruptStreamException("a DSDIFF channels chunk states $count channels in $localSize bytes", p)
                                    ids = List(count) { reader.readFourCc() }
                                }
                                "CMPR" -> compression = reader.readFourCc()
                            }
                            p += 12 + localSize + (localSize and 1)
                        }
                    }
                    "DST " -> throw UnsupportedFeatureException(
                        "DST-compressed DSDIFF",
                        "this DSDIFF file holds DST-compressed DSD, which AudioKodec cannot decode",
                    )
                    "DSD " -> {
                        val channelIds = ids ?: throw CorruptStreamException("a DSDIFF file's sound data comes before its channels", at)
                        if (rate <= 0) throw CorruptStreamException("a DSDIFF file states no sample rate before its sound data", at)
                        if (compression != null && compression != "DSD ") {
                            throw UnsupportedFeatureException("DSDIFF compression '$compression'", "this DSDIFF file is compressed as '$compression', which AudioKodec cannot decode")
                        }
                        val channels = channelIds.size
                        checkRate(rate, channels, options)
                        if (size % channels != 0L) throw CorruptStreamException("a DSDIFF sound data chunk of $size bytes does not divide among $channels channels", at)
                        var bytes = size
                        val available = if (reader.size >= 0) reader.size - body else size
                        if (available < size) {
                            if (!options.tolerant) throw TruncatedStreamException("a DSDIFF file's sound data ends after $available of its $size bytes", body + available)
                            bytes = available - available % channels
                        }
                        val speakers = channelIds.map { DFF_SPEAKERS[it] ?: AudioChannel.UNKNOWN }
                        return build(AudioContainer.DFF, rate, speakers, bytes / channels * 8, 0, false, reader.absoluteOffset(body), bytes, options)
                    }
                }
                at = body + size + (size and 1)
            }
        }

        private fun checkRate(rate: Int, channels: Int, options: DecodeOptions) {
            if (rate <= 0 || rate % DsdConverter.DECIMATION != 0) {
                throw CorruptStreamException("a DSD rate of $rate Hz is not a multiple of ${DsdConverter.DECIMATION}", -1)
            }
            options.checkStreamShape(rate / DsdConverter.DECIMATION, channels)
        }

        private fun build(
            container: AudioContainer, rate: Int, speakers: List<AudioChannel>, bits: Long,
            blockSize: Int, lsbFirst: Boolean, dataOffset: Long, dataBytes: Long, options: DecodeOptions,
        ): DsdFile {
            // Speakers it can name go into AudioKodec's order; otherwise the file's order stands.
            val named = speakers.none { it == AudioChannel.UNKNOWN } && speakers.toSet().size == speakers.size
            val layout = if (named) ChannelLayout.of(speakers.sortedBy { it.ordinal }) else ChannelLayout.unknown(speakers.size)
            val placement = IntArray(speakers.size) { if (named) layout.channels.indexOf(speakers[it]) else it }
            val frames = (bits + DsdConverter.DECIMATION - 1) / DsdConverter.DECIMATION
            val info = AudioInfo(
                container = container,
                codec = AudioCodec.DSD,
                format = AudioFormat(rate / DsdConverter.DECIMATION, layout),
                durationFrames = frames,
                bitDepth = null,
                bitrate = (rate.toLong() * speakers.size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                isVariableBitrate = false,
                dataOffset = dataOffset,
                dataSize = dataBytes,
            )
            return DsdFile(info, rate, bits, blockSize, lsbFirst, placement)
        }

        /** DSF's channel types 1 to 7, in the file's channel order. */
        private val DSF_LAYOUTS = mapOf(
            1 to listOf(AudioChannel.FRONT_CENTER),
            2 to listOf(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT),
            3 to listOf(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_CENTER),
            4 to listOf(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.BACK_LEFT, AudioChannel.BACK_RIGHT),
            5 to listOf(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_CENTER, AudioChannel.LOW_FREQUENCY),
            6 to listOf(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_CENTER, AudioChannel.BACK_LEFT, AudioChannel.BACK_RIGHT),
            7 to listOf(
                AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_CENTER,
                AudioChannel.LOW_FREQUENCY, AudioChannel.BACK_LEFT, AudioChannel.BACK_RIGHT,
            ),
        )

        /** DSDIFF's predefined channel IDs; its surrounds, laid out after ITU-R BS.775, are the surround pair of the canonical 5.1. */
        private val DFF_SPEAKERS = mapOf(
            "SLFT" to AudioChannel.FRONT_LEFT,
            "SRGT" to AudioChannel.FRONT_RIGHT,
            "MLFT" to AudioChannel.FRONT_LEFT,
            "MRGT" to AudioChannel.FRONT_RIGHT,
            "C   " to AudioChannel.FRONT_CENTER,
            "LFE " to AudioChannel.LOW_FREQUENCY,
            "LS  " to AudioChannel.BACK_LEFT,
            "RS  " to AudioChannel.BACK_RIGHT,
        )
    }
}

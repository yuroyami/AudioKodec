package io.github.yuroyami.audiokodec.internal.container

import io.github.yuroyami.audiokodec.AudioChannel
import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioInfo
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.bits.BitReaderMsb
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.mpeg.MpegStream

/**
 * The parts of an AAC configuration both raw framings carry: the sampling
 * frequency table, the fixed channel configurations, and the program
 * configuration element that describes any other arrangement.
 *
 * Written from ISO/IEC 14496-3, subpart 1 and subpart 4, and ISO/IEC 13818-7.
 */
internal object AacConfig {

    /** `sampling_frequency_index` 0 to 12; 13 and 14 are reserved, and 15 is an escape ADTS and ADIF never use. */
    val SAMPLE_RATES: IntArray = intArrayOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350)

    /** Frames one raw data block decodes to. */
    const val FRAMES_PER_BLOCK: Int = 1024

    /**
     * The speakers a `channelConfiguration` of 1 to 7 drives, in the order the
     * elements carry them: centre first, then pairs, then the LFE.
     */
    fun storedOrder(configuration: Int): List<AudioChannel>? {
        val c = AudioChannel.FRONT_CENTER
        val l = AudioChannel.FRONT_LEFT
        val r = AudioChannel.FRONT_RIGHT
        val ls = AudioChannel.BACK_LEFT
        val rs = AudioChannel.BACK_RIGHT
        val lfe = AudioChannel.LOW_FREQUENCY
        return when (configuration) {
            1 -> listOf(c)
            2 -> listOf(l, r)
            3 -> listOf(c, l, r)
            4 -> listOf(c, l, r, AudioChannel.BACK_CENTER)
            5 -> listOf(c, l, r, ls, rs)
            6 -> listOf(c, l, r, ls, rs, lfe)
            7 -> listOf(c, AudioChannel.FRONT_LEFT_OF_CENTER, AudioChannel.FRONT_RIGHT_OF_CENTER, l, r, ls, rs, lfe)
            else -> null
        }
    }

    /** The layout a decoder reports for a fixed configuration: the same speakers in AudioKodec's order. */
    fun layoutFor(configuration: Int): ChannelLayout? =
        storedOrder(configuration)?.let { ChannelLayout.of(it.sortedBy { channel -> channel.ordinal }) }

    /** What a `program_config_element` says about the stream's shape. */
    class ProgramConfig(val objectType: Int, val sampleRateIndex: Int, val channels: Int)

    /**
     * Reads a `program_config_element` from [bits], whose byte alignment is
     * counted from the start of [bits]' region, as the element's own
     * `byte_alignment()` is.
     */
    fun readProgramConfig(bits: BitReaderMsb): ProgramConfig {
        bits.skipBits(4) // element_instance_tag
        val objectType = bits.readBits(2)
        val rateIndex = bits.readBits(4)
        val front = bits.readBits(4)
        val side = bits.readBits(4)
        val back = bits.readBits(4)
        val lfe = bits.readBits(2)
        val assoc = bits.readBits(3)
        val cc = bits.readBits(4)
        if (bits.readBit() == 1) bits.skipBits(4) // mono_mixdown_element_number
        if (bits.readBit() == 1) bits.skipBits(4) // stereo_mixdown_element_number
        if (bits.readBit() == 1) bits.skipBits(3) // matrix_mixdown_idx, pseudo_surround_enable
        var channels = 0
        repeat(front + side + back) {
            channels += if (bits.readBit() == 1) 2 else 1
            bits.skipBits(4)
        }
        bits.skipBits(4 * lfe)
        channels += lfe
        bits.skipBits(4 * assoc)
        bits.skipBits(5 * cc)
        bits.alignToByte()
        bits.skipBits(8 * bits.readBits(8)) // comment_field_data
        return ProgramConfig(objectType, rateIndex, channels)
    }

    fun rateOf(index: Int, offset: Long): Int =
        SAMPLE_RATES.getOrNull(index) ?: throw CorruptStreamException("AAC sampling frequency index $index is reserved", offset)
}

/**
 * One ADTS frame header: the fixed part, which every frame of a stream
 * repeats, and the variable part, which states this frame's length.
 */
internal class AdtsHeader(
    /** The `ID` bit: MPEG-2 rather than MPEG-4. */
    val mpeg2: Boolean,
    val hasCrc: Boolean,
    /** `profile_ObjectType`: the audio object type less one. */
    val profile: Int,
    val sampleRateIndex: Int,
    val channelConfiguration: Int,
    /** Bytes of the whole frame, header included. */
    val frameBytes: Int,
    val bufferFullness: Int,
    /** Raw data blocks in the frame, each 1024 frames of audio. */
    val rawBlocks: Int,
) {
    /** The header, with the block positions and checksum a protected frame adds. */
    val headerBytes: Int get() = if (hasCrc) 7 + 2 * (rawBlocks - 1) + 2 else 7

    val sampleRate: Int get() = AacConfig.SAMPLE_RATES[sampleRateIndex]

    /** True when [other] has the same fixed header, as every frame of one stream must. */
    fun matches(other: AdtsHeader): Boolean =
        mpeg2 == other.mpeg2 && hasCrc == other.hasCrc && profile == other.profile &&
            sampleRateIndex == other.sampleRateIndex && channelConfiguration == other.channelConfiguration

    companion object {
        const val SIZE: Int = 7

        /** The header at [at] in [bytes], or null when the seven bytes there are not one. */
        fun parse(bytes: ByteArray, at: Int): AdtsHeader? {
            if (bytes.size - at < SIZE) return null
            fun u(i: Int) = bytes[at + i].toInt() and 0xFF
            if (u(0) != 0xFF || u(1) and 0xF0 != 0xF0) return null
            if (u(1) and 0x06 != 0) return null // layer is always 0
            val mpeg2 = u(1) and 0x08 != 0
            val hasCrc = u(1) and 0x01 == 0
            val profile = u(2) ushr 6
            val rateIndex = (u(2) ushr 2) and 0x0F
            if (rateIndex >= AacConfig.SAMPLE_RATES.size) return null
            // MPEG-2 AAC defines three profiles; the fourth value is reserved.
            if (mpeg2 && profile == 3) return null
            val channelConfiguration = ((u(2) and 0x01) shl 2) or (u(3) ushr 6)
            val frameBytes = ((u(3) and 0x03) shl 11) or (u(4) shl 3) or (u(5) ushr 5)
            val bufferFullness = ((u(5) and 0x1F) shl 6) or (u(6) ushr 2)
            val rawBlocks = (u(6) and 0x03) + 1
            val header = AdtsHeader(mpeg2, hasCrc, profile, rateIndex, channelConfiguration, frameBytes, bufferFullness, rawBlocks)
            return if (frameBytes > header.headerBytes) header else null
        }
    }
}

/**
 * Finds the frames of an ADTS stream and works out its shape, as
 * [MpegStream] does for MPEG audio.
 *
 * ADTS puts a seven or nine-byte header on every frame and no index anywhere,
 * so a candidate sync word is believed only when the frames it leads to agree
 * with it, and the length is the frames counted, each holding one to four
 * blocks of 1024 frames of audio. Nothing is decoded.
 *
 * Written from ISO/IEC 14496-3, subpart 1, annex 1.A, and ISO/IEC 13818-7.
 */
internal class AdtsStream(
    /** Byte offset of the first frame. */
    val audioOffset: Long,
    /** Byte offset just past the last complete frame. */
    val audioEnd: Long,
    val first: AdtsHeader,
    val frameCount: Long,
    val blockCount: Long,
    val channels: Int,
    val layout: ChannelLayout,
) {
    fun info(reader: SourceReader): AudioInfo {
        val rate = first.sampleRate
        val frames = blockCount * AacConfig.FRAMES_PER_BLOCK
        val bytes = audioEnd - audioOffset
        val bitrate = if (frames > 0) (bytes.toDouble() * 8 * rate / frames).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt() else null
        return AudioInfo(
            container = AudioContainer.ADTS,
            codec = AudioCodec.AAC,
            format = AudioFormat(rate, layout),
            durationFrames = frames,
            bitDepth = null,
            bitrate = bitrate,
            // ADTS signals a variable bitrate by an all-ones buffer fullness.
            isVariableBitrate = first.bufferFullness == 0x7FF,
            dataOffset = reader.absoluteOffset(audioOffset),
            dataSize = bytes,
        )
    }

    /** One frame: where it is and what its header says. */
    class Frame(val offset: Long, val header: AdtsHeader) {
        /** Where the raw data blocks start. */
        val payloadOffset: Long get() = offset + header.headerBytes
        val payloadBytes: Int get() = header.frameBytes - header.headerBytes
    }

    /**
     * Visits every frame in order, the same ones [parse] counted, for a
     * decoder to read its packets from. [visit] returns false to stop.
     */
    fun frames(reader: SourceReader, visit: (Frame) -> Boolean) {
        walk(reader, audioOffset, audioEnd, first) { offset, header -> visit(Frame(offset, header)) }
    }

    companion object {
        private const val SEARCH_BYTES = 512 * 1024
        private const val CONFIRMATIONS = 3
        private const val RESYNC_BYTES = 64 * 1024

        fun parse(reader: SourceReader, options: DecodeOptions): AdtsStream {
            val size = reader.size
            val start = MpegStream.skipLeadingTags(reader)
            val declaredEnd = if (size < 0) Long.MAX_VALUE else MpegStream.trailingTagStart(reader, size)
            val (offset, first) = findFirstFrame(reader, start, declaredEnd)
                ?: throw CorruptStreamException("no ADTS frame was found in the first ${SEARCH_BYTES / 1024} kilobytes of this file", start)

            val fixed = AacConfig.layoutFor(first.channelConfiguration)
            val channels = fixed?.channels?.size ?: programConfigChannels(reader, offset, first)
            options.checkStreamShape(first.sampleRate, channels, offset)
            // A program configuration names speaker positions loosely, by
            // front, side and back groups, so its speakers stay unnamed.
            val layout = fixed ?: ChannelLayout.unknown(channels)

            var frames = 0L
            var blocks = 0L
            var end = offset
            walk(reader, offset, declaredEnd, first) { at, header ->
                frames++
                blocks += header.rawBlocks
                end = at + header.frameBytes
                true
            }
            return AdtsStream(offset, end, first, frames, blocks, channels, layout)
        }

        /**
         * A channel configuration of zero leaves the arrangement to a program
         * configuration element, which leads the first frame's raw data.
         */
        private fun programConfigChannels(reader: SourceReader, offset: Long, header: AdtsHeader): Int {
            val payload = ByteArray(header.frameBytes - header.headerBytes)
            reader.seek(offset + header.headerBytes)
            reader.readFully(payload)
            val bits = BitReaderMsb(payload)
            val element = try {
                if (bits.readBits(3) != ID_PCE) null else AacConfig.readProgramConfig(bits)
            } catch (_: TruncatedStreamException) {
                throw CorruptStreamException("the first ADTS frame's program configuration is cut short", offset)
            } ?: throw UnsupportedFeatureException(
                "ADTS channel configuration 0 without a leading program configuration",
                "this ADTS stream leaves its channel arrangement to the decoder's defaults, which AudioKodec does not guess",
            )
            if (element.channels == 0) throw CorruptStreamException("the ADTS program configuration names no channels", offset)
            if (element.sampleRateIndex != header.sampleRateIndex) {
                throw CorruptStreamException("the ADTS program configuration and frame header disagree about the sampling rate", offset)
            }
            return element.channels
        }

        /**
         * Walks complete frames from [from] that share [shape]'s fixed
         * header, crossing junk the way [MpegStream] does: a frame found after
         * a gap has to be confirmed, and a gap longer than 64 KiB ends a
         * tolerant walk and fails a strict one. A final frame that ends past
         * the data is truncation.
         */
        private fun walk(reader: SourceReader, from: Long, to: Long, shape: AdtsHeader, visit: (Long, AdtsHeader) -> Boolean) {
            val bytes = ByteArray(AdtsHeader.SIZE)
            var at = from
            var resynchronising = false
            var skipped = 0
            var count = 0L
            while (at <= to && to - at >= AdtsHeader.SIZE) {
                reader.seek(at)
                if (reader.readSome(bytes, 0, bytes.size) < bytes.size) break
                if (MpegStream.isTrailingTag(reader, at, bytes)) break
                val tagEnd = try {
                    MpegStream.embeddedTagEnd(reader, at, bytes, to)
                } catch (failure: TruncatedStreamException) {
                    if (!reader.options.tolerant || count == 0L) throw failure
                    break
                }
                if (tagEnd != null) {
                    at = tagEnd
                    resynchronising = true
                    continue
                }
                val header = AdtsHeader.parse(bytes, 0)
                val accepted = header != null && header.matches(shape) && (!resynchronising || confirms(reader, at, header, to))
                if (!accepted) {
                    at++
                    resynchronising = true
                    if (++skipped > RESYNC_BYTES) {
                        if (!reader.options.tolerant) throw CorruptStreamException("no ADTS frame was found in $RESYNC_BYTES bytes", at)
                        break
                    }
                    continue
                }
                if (header!!.frameBytes.toLong() > to - at || !MpegStream.framePayloadExists(reader, at, header.frameBytes)) {
                    if (!reader.options.tolerant) {
                        throw TruncatedStreamException("the final ADTS frame declares ${header.frameBytes} bytes but ends early", at)
                    }
                    break
                }
                count++
                if (!visit(at, header)) return
                at += header.frameBytes
                resynchronising = false
                skipped = 0
            }
        }

        /** The first frame at or after [from] whose successors agree with it. */
        private fun findFirstFrame(reader: SourceReader, from: Long, to: Long): Pair<Long, AdtsHeader>? {
            val window = ByteArray(64 * 1024)
            var at = from
            var scanned = 0L
            while (at < to && scanned < SEARCH_BYTES) {
                reader.seek(at)
                val got = reader.readSome(window, 0, window.size)
                if (got < AdtsHeader.SIZE) break
                for (index in 0..got - AdtsHeader.SIZE) {
                    val header = AdtsHeader.parse(window, index) ?: continue
                    if (confirms(reader, at + index, header, to)) return (at + index) to header
                }
                scanned += got
                at += (got - AdtsHeader.SIZE + 1).coerceAtLeast(1)
            }
            return null
        }

        /** True when the frames after [offset] line up with [header], three times or to the end. */
        private fun confirms(reader: SourceReader, offset: Long, header: AdtsHeader, to: Long): Boolean {
            val probe = ByteArray(AdtsHeader.SIZE)
            var at = offset
            var current = header
            repeat(CONFIRMATIONS) {
                if (at > to || current.frameBytes.toLong() > to - at) return false
                if (!MpegStream.framePayloadExists(reader, at, current.frameBytes)) return false
                val next = at + current.frameBytes
                if (next > to || to - next < AdtsHeader.SIZE) return true
                reader.seek(next)
                if (reader.readSome(probe, 0, probe.size) < probe.size) return true
                if (MpegStream.isTrailingTag(reader, next, probe)) return true
                val following = AdtsHeader.parse(probe, 0) ?: return false
                if (!following.matches(header)) return false
                at = next
                current = following
            }
            return true
        }

        private const val ID_PCE = 5
    }
}

/**
 * The single header ADIF puts at the front of a raw AAC stream: whether the
 * bitrate is constant, the bitrate, and one or more program configurations,
 * the first of which gives the sampling rate and channel count. The raw data
 * blocks that follow carry no lengths, so how long the stream is cannot be
 * known without decoding it, and is reported as unknown.
 */
internal object AdifReader {

    private const val HEADER_WINDOW = 16 * 1024

    fun info(reader: SourceReader, options: DecodeOptions): AudioInfo {
        val size = reader.size
        val window = ByteArray(if (size in 0 until HEADER_WINDOW) size.toInt() else HEADER_WINDOW)
        reader.seek(0)
        val got = reader.readSome(window, 0, window.size)
        val bits = BitReaderMsb(window, 0, got)
        val bitrate: Int
        val constant: Boolean
        val first: AacConfig.ProgramConfig
        try {
            if (bits.readBits(32) != 0x41444946) throw CorruptStreamException("an ADIF stream opens with 'ADIF'", 0)
            if (bits.readBit() == 1) bits.skipBits(72) // copyright_id
            bits.skipBits(2) // original_copy, home
            constant = bits.readBit() == 0
            bitrate = bits.readBits(23)
            val programs = bits.readBits(4) + 1
            var config: AacConfig.ProgramConfig? = null
            repeat(programs) {
                if (constant) bits.skipBits(20) // adif_buffer_fullness
                val element = AacConfig.readProgramConfig(bits)
                if (config == null) config = element
            }
            first = config!!
        } catch (_: TruncatedStreamException) {
            throw TruncatedStreamException("the ADIF header is cut short", 0)
        }
        val rate = AacConfig.rateOf(first.sampleRateIndex, 0)
        if (first.channels == 0) throw CorruptStreamException("the ADIF program configuration names no channels", 0)
        options.checkStreamShape(rate, first.channels, 0)
        val dataOffset = ((bits.bitPosition + 7) / 8).toLong()
        val end = if (size < 0) -1L else MpegStream.trailingTagStart(reader, size)
        return AudioInfo(
            container = AudioContainer.ADIF,
            codec = AudioCodec.AAC,
            format = AudioFormat(rate, ChannelLayout.unknown(first.channels)),
            durationFrames = null,
            bitDepth = null,
            bitrate = bitrate.takeIf { it > 0 },
            isVariableBitrate = !constant,
            dataOffset = reader.absoluteOffset(dataOffset),
            dataSize = if (end < 0) -1L else (end - dataOffset).coerceAtLeast(0L),
        )
    }
}

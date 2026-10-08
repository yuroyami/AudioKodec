package io.github.yuroyami.audiokodec.internal.ogg

import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioFrame
import io.github.yuroyami.audiokodec.AudioInfo
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.IntAudioFrame
import io.github.yuroyami.audiokodec.IntPcmAccess
import io.github.yuroyami.audiokodec.Pcm
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.internal.flac.FlacFrameReader
import io.github.yuroyami.audiokodec.internal.flac.FlacMetadata
import io.github.yuroyami.audiokodec.internal.io.SourceReader

/**
 * Decodes FLAC carried inside Ogg.
 *
 * The audio is the same as in a native FLAC file, one frame per Ogg packet, so
 * the frame decoder is shared. What differs is everything around it: the stream
 * information arrives wrapped in the mapping's own preamble, the frames arrive
 * as packets rather than as a run of bytes, and a position is found through the
 * granule positions the pages carry rather than through a FLAC seek table.
 *
 * Written from RFC 3533 and the Ogg FLAC mapping.
 */
internal class OggFlacDecoder(
    private val reader: SourceReader,
    private val parsed: OggAnalyser.Parsed,
    private val options: DecodeOptions,
) : AudioDecoder, IntPcmAccess {

    private val stream = parsed.audio
        ?: throw CorruptStreamException("this Ogg file carries no FLAC stream", 0)

    private val streamInfo: FlacMetadata.StreamInfo = readStreamInfo()

    private val frames = FlacFrameReader(reader, streamInfo, options)
    private val ogg = OggReader(reader, options)

    override val format: AudioFormat = stream.format

    override val bitDepth: Int = streamInfo.bitsPerSample

    override val durationFrames: Long? = parsed.durationFrames
        ?: streamInfo.totalSamples.takeIf { it > 0 }

    override val info: AudioInfo = AudioInfo(
        container = AudioContainer.OGG,
        codec = AudioCodec.FLAC,
        format = format,
        durationFrames = durationFrames,
        bitDepth = streamInfo.bitsPerSample,
        bitrate = bitrateOf(),
        isVariableBitrate = true,
        dataOffset = reader.absoluteOffset(parsed.audioStartOffset),
        dataSize = (parsed.streamEndOffset - parsed.audioStartOffset).coerceAtLeast(0L),
    )

    override var positionFrames: Long = 0L
        private set

    private var packets: OggReader.PacketReader =
        ogg.packets(stream.serialNumber, parsed.audioStartOffset)

    /** Frames to drop from the front of the next packet after a seek. */
    private var pendingSkip: Long = 0

    private var closed = false
    private var exhausted = false
    private var decodedAny = false

    private var floatBuffer: FloatArray? = null
    private var intBuffer: IntArray? = null

    /**
     * Rebuilds the stream information block from the mapping's first packet.
     *
     * The packet is nine bytes of preamble, then `fLaC`, then a real metadata
     * block. Handing the block to the ordinary FLAC parser keeps one reading of
     * it rather than two.
     */
    private fun readStreamInfo(): FlacMetadata.StreamInfo {
        val identification = stream.identification
        if (identification.size < 51) {
            throw CorruptStreamException(
                "the Ogg FLAC identification packet is ${identification.size} bytes, " +
                    "which is too short to hold stream information",
                stream.firstPageOffset,
            )
        }
        // Nine bytes of mapping preamble, four of 'fLaC', four of block header.
        return FlacMetadata.parseStreamInfoBody(
            identification,
            at = 13 + 4,
            options = options,
            offset = stream.firstPageOffset,
        )
    }

    override fun nextFrame(): AudioFrame? {
        val block = nextBlock() ?: return null
        val channels = format.channels
        val usable = block.usableFrames
        val samples = usable * channels
        val out = floatBufferOf(samples)
        val scale = (1.0 / scaleOf(block.frame.bitsPerSample)).toFloat()
        var index = 0
        for (frame in block.from until block.from + usable) {
            for (channel in 0 until channels) {
                out[index++] = block.frame.channels[channel][frame] * scale
            }
        }
        val start = positionFrames
        positionFrames += usable
        return AudioFrame(
            format = format,
            samples = if (options.reuseBuffers) out else out.copyOf(samples),
            frameCount = usable,
            positionFrames = start,
        )
    }

    override fun nextIntFrame(): IntAudioFrame? {
        val block = nextBlock() ?: return null
        val channels = format.channels
        val usable = block.usableFrames
        val samples = usable * channels
        val out = intBufferOf(samples)
        var index = 0
        for (frame in block.from until block.from + usable) {
            for (channel in 0 until channels) {
                out[index++] = block.frame.channels[channel][frame]
            }
        }
        val start = positionFrames
        positionFrames += usable
        return IntAudioFrame(
            format = format,
            samples = if (options.reuseBuffers) out else out.copyOf(samples),
            frameCount = usable,
            positionFrames = start,
            bitDepth = block.frame.bitsPerSample,
        )
    }

    private class Block(val frame: FlacFrameReader.Frame, val from: Int, val usableFrames: Int)

    private fun nextBlock(): Block? {
        check(!closed) { "this decoder is closed" }
        if (exhausted) return null
        val total = durationFrames
        if (total != null && positionFrames >= total) return null

        while (true) {
            val packet = packets.next()
            if (packet == null) {
                exhausted = true
                if (total != null && positionFrames < total &&
                    (!options.tolerant || !decodedAny)
                ) {
                    throw TruncatedStreamException(
                        "the stream declares $total frames and ends after $positionFrames",
                        reader.size,
                    )
                }
                return null
            }
            // A packet that is not a frame is a metadata block that arrived
            // after the headers, which the mapping allows. Skipping it is
            // right; treating it as audio would not be.
            if (packet.length < 5) continue
            val sync = ((packet.bytes[0].toInt() and 0xFF) shl 8) or
                (packet.bytes[1].toInt() and 0xFF)
            if (sync and 0xFFFC != 0xFFF8) continue

            val frame = try {
                frames.decodePacket(packet.bytes, packet.length, parsed.audioStartOffset)
            } catch (e: TruncatedStreamException) {
                exhausted = true
                if (options.tolerant && decodedAny) return null
                throw e
            } catch (e: CorruptStreamException) {
                exhausted = true
                if (options.tolerant && decodedAny) return null
                throw e
            } ?: continue

            // A seek lands on a page boundary, and a page holds several frames,
            // so the frames to discard can span more than one packet. What is
            // left over has to survive into the next packet rather than being
            // dropped, or the decoder resumes a whole block early.
            val skip = minOf(pendingSkip, frame.blockSize.toLong()).toInt()
            pendingSkip -= skip.toLong()
            var usable = frame.blockSize - skip
            if (total != null) {
                val left = total - positionFrames
                if (left < usable) usable = left.toInt()
            }
            if (usable <= 0) continue
            decodedAny = true
            return Block(frame, skip, usable)
        }
    }

    override fun seek(frameIndex: Long): Long {
        check(!closed) { "this decoder is closed" }
        require(frameIndex >= 0) { "frameIndex must not be negative, was $frameIndex" }
        val total = durationFrames
        val target = if (total != null) frameIndex.coerceAtMost(total) else frameIndex
        exhausted = false

        if (target == 0L) {
            packets = ogg.packets(stream.serialNumber, parsed.audioStartOffset)
            pendingSkip = 0L
            positionFrames = 0
            return 0
        }

        val landing = OggSeek.pageAtOrBefore(
            ogg = ogg,
            serialNumber = stream.serialNumber,
            from = parsed.audioStartOffset,
            to = parsed.streamEndOffset,
            target = target,
            options = options,
        )
        packets = ogg.packets(stream.serialNumber, landing.pageOffset)
        pendingSkip = (target - landing.startFrame).coerceAtLeast(0L)
        positionFrames = target
        return target
    }

    override fun close() {
        if (closed) return
        closed = true
        floatBuffer = null
        intBuffer = null
        reader.source.close()
    }

    private fun scaleOf(bits: Int): Double = (1L shl (bits - 1)).toDouble()

    private fun bitrateOf(): Int? {
        val total = durationFrames ?: return null
        if (total <= 0 || reader.size <= 0) return null
        val seconds = total.toDouble() / streamInfo.sampleRate
        if (seconds <= 0) return null
        val encodedBytes = parsed.streamEndOffset - parsed.audioStartOffset
        return (encodedBytes.toDouble() * 8.0 / seconds)
            .coerceIn(0.0, Int.MAX_VALUE.toDouble())
            .toInt()
    }

    private fun floatBufferOf(samples: Int): FloatArray {
        val existing = floatBuffer
        if (existing != null && existing.size >= samples) return existing
        options.checkAllocation(samples.toLong() * 4, "decoding $samples samples")
        val fresh = FloatArray(samples)
        floatBuffer = fresh
        return fresh
    }

    private fun intBufferOf(samples: Int): IntArray {
        val existing = intBuffer
        if (existing != null && existing.size >= samples) return existing
        options.checkAllocation(samples.toLong() * 4, "decoding $samples samples")
        val fresh = IntArray(samples)
        intBuffer = fresh
        return fresh
    }
}

/**
 * Finding a position in an Ogg stream.
 *
 * Ogg records positions only as the granule position of each page, and a page's
 * granule position is where the last packet finishing on it ends. So the page
 * to resume from is the one after the last page whose granule is at or below the
 * target, and the position to resume at is that page's granule.
 *
 * Pages vary in size, so the search is a bisection over byte offsets rather than
 * arithmetic. That is what every Ogg reader does, and it is why the page
 * checksum matters: a bisection lands in the middle of a page and has to find
 * the next real one.
 */
internal object OggSeek {

    class Landing(
        /** Offset of the first byte after the page that was landed on. */
        val pageOffset: Long,
        /** Granule position of that page, which is where its output ends. */
        val startFrame: Long,
        /** Offset of the page itself, for a decoder that has to re-read it. */
        val pageStart: Long,
    )

    fun pageAtOrBefore(
        ogg: OggReader,
        serialNumber: Int,
        from: Long,
        to: Long,
        target: Long,
        options: DecodeOptions,
        /** Subtracted from every granule position, which Opus needs. */
        granuleOffset: Long = 0L,
        /**
         * When true, only a page that no packet continues onto counts as a
         * landing.
         *
         * A codec whose blocks overlap has to restart on a page whose first
         * packet also starts there, because the tail of a packet carried over
         * from the page before is not something it can begin reading.
         */
        requireFreshStart: Boolean = false,
    ): Landing {
        require(from >= 0) { "the Ogg seek start must not be negative, was $from" }
        require(target >= 0) { "the Ogg seek target must not be negative, was $target" }
        require(granuleOffset >= 0) {
            "the Ogg granule offset must not be negative, was $granuleOffset"
        }
        var low = from
        var high = if (to > from) to else from
        var best = Landing(from, 0L, from)

        // Every iteration either moves low past a real page or cuts high in
        // half, so this converges across the full Long address space without
        // an arbitrary file-size ceiling.
        while (high - low > 65_536) {
            val middle = low + (high - low) / 2
            val page = findEligiblePage(
                ogg,
                middle,
                high,
                serialNumber,
                requireFreshStart,
            )
            if (page == null) {
                // Nothing useful in the upper half, so look lower.
                high = middle
                continue
            }
            val granule = adjustedGranule(page, granuleOffset)
            if (granule <= target) {
                val pageEnd = pageEnd(page)
                best = Landing(pageEnd, granule, page.offset)
                low = pageEnd
            } else {
                high = page.offset
            }
        }

        // Walk forward from the best guess to the last page that still starts
        // at or before the target. The bisection gets close; this is exact.
        var at = best.pageOffset
        var landing = best
        while (true) {
            if (to in 1..at) break
            val page = try {
                ogg.pageAt(at)
            } catch (e: CorruptStreamException) {
                if (!options.tolerant) throw e
                ogg.findPageFrom(checkedAdvance(at, 4), to)
            } ?: break
            at = pageEnd(page)
            if (page.serialNumber != serialNumber || !page.endsAPacket) continue
            if (requireFreshStart && page.isContinued) continue
            if (page.granulePosition < 0) continue
            val granule = adjustedGranule(page, granuleOffset)
            if (granule > target) break
            landing = Landing(at, granule, page.offset)
        }
        return landing
    }

    private fun findEligiblePage(
        ogg: OggReader,
        from: Long,
        to: Long,
        serialNumber: Int,
        requireFreshStart: Boolean,
    ): OggPage? {
        var at = from
        while (at < to) {
            val page = ogg.findPageFrom(at, to) ?: return null
            if (page.serialNumber == serialNumber &&
                page.endsAPacket &&
                (!requireFreshStart || !page.isContinued) &&
                page.granulePosition >= 0
            ) {
                return page
            }
            at = pageEnd(page)
        }
        return null
    }

    private fun adjustedGranule(page: OggPage, offset: Long): Long =
        (page.granulePosition - offset).coerceAtLeast(0L)

    private fun pageEnd(page: OggPage): Long =
        checkedAdvance(page.offset, page.byteLength)

    private fun checkedAdvance(at: Long, count: Int): Long {
        if (count < 0 || at > Long.MAX_VALUE - count) {
            throw CorruptStreamException("an Ogg page offset overflows 64 bits", at)
        }
        return at + count
    }
}

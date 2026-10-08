package io.github.yuroyami.audiokodec.internal.vorbis

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
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.internal.dsp.InverseMdct
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.ogg.OggAnalyser
import io.github.yuroyami.audiokodec.internal.ogg.OggReader
import io.github.yuroyami.audiokodec.internal.ogg.OggSeek

/**
 * Decodes Vorbis audio carried in Ogg.
 *
 * A Vorbis packet is one overlapping block of spectrum. Decoding it means
 * reading a coarse curve, reading the fine detail on top of it, undoing the
 * stereo coupling, multiplying the two back together, running an inverse
 * transform, applying a window and adding the result to the tail of the block
 * before. Only the middle of each block ever leaves the decoder, because the
 * ends are half of an overlap that is only complete once the neighbour arrives.
 *
 * That last point is the source of most of what looks odd here. The first
 * packet of a stream produces nothing at all. A packet's output is not its own
 * samples but the region between its predecessor's centre and its own. And a
 * seek cannot start at an arbitrary packet, because a block with no predecessor
 * has no left half.
 *
 * Written from the Vorbis I specification.
 */
internal class VorbisDecoder(
    private val reader: SourceReader,
    private val parsed: OggAnalyser.Parsed,
    private val options: DecodeOptions,
) : AudioDecoder {

    private val stream = parsed.audio
        ?: throw CorruptStreamException("this Ogg file carries no Vorbis stream", 0)

    private val identification: VorbisIdentification =
        VorbisConfig.parseIdentification(headerPacket(0), options, stream.firstPageOffset)

    private val config: VorbisConfig = parsed.vorbisConfig ?: VorbisConfig.parseSetup(
        headerPacket(2), identification, options, stream.firstPageOffset,
    )

    private val channels = identification.channels
    private val longBlock = identification.longBlock
    private val shortBlock = identification.shortBlock

    override val format: AudioFormat = stream.format

    override val durationFrames: Long? = parsed.durationFrames

    override val info: AudioInfo = AudioInfo(
        container = AudioContainer.OGG,
        codec = AudioCodec.VORBIS,
        format = format,
        durationFrames = durationFrames,
        // Vorbis is not a fixed-depth format: it codes a spectrum, not samples,
        // so there is no stored bit depth to report.
        bitDepth = null,
        bitrate = bitrateOf(),
        isVariableBitrate = true,
        dataOffset = reader.absoluteOffset(parsed.audioStartOffset),
        dataSize = (parsed.streamEndOffset - parsed.audioStartOffset).coerceAtLeast(0L),
    )

    override var positionFrames: Long = 0L
        private set

    private val ogg = OggReader(reader, options)
    private var packets: OggReader.PacketReader =
        ogg.packets(stream.serialNumber, parsed.audioStartOffset, parsed.streamEndOffset)

    private val bits = VorbisBitReader(ByteArray(0), 0)
    private val shortMdct = InverseMdct(shortBlock)
    private val longMdct = InverseMdct(longBlock)

    /** One block of spectrum, then of time, per channel. */
    private val blocks = Array(channels) {
        // Three buffers of this size exist per channel, plus the interleaved
        // output. A file is free to declare eight channels of 8192-sample
        // blocks, so the ceiling is checked rather than assumed reasonable.
        options.checkAllocation(
            channels.toLong() * longBlock * 4 * 3,
            "decoding $channels channels of $longBlock-sample Vorbis blocks",
        )
        FloatArray(longBlock)
    }

    /** The part of the last block that reaches past its own centre. */
    private val tails = Array(channels) { FloatArray(longBlock / 2) }

    private val curve = FloatArray(longBlock / 2)
    private val floorStates = Array(channels) { VorbisFloor.FloorState(MAX_FLOOR_POINTS) }
    private val floorForChannel = arrayOfNulls<VorbisFloor>(channels)
    private val noResidue = BooleanArray(channels)

    /**
     * Whether each channel's floor said it had nothing this packet.
     *
     * This is kept apart from [noResidue] on purpose. Coupling clears
     * [noResidue] for both sides of a pair, because a coupled pair is decoded
     * together whatever either side said; but a channel whose floor was unused
     * still has no curve to multiply by, and its output is silence. Reading the
     * cleared flag instead would give it the curve left over from the packet
     * before, which is one channel of a stereo pair leaking into the other.
     */
    private val floorUnused = BooleanArray(channels)
    private val submapChannels = Array(channels) { blocks[it] }
    private val submapSkip = BooleanArray(channels)
    private val residueScratch = VorbisResidue.Scratch()

    /** Vorbis puts the centre channel second; AudioKodec puts it third. */
    private val channelOrder = orderFor(channels)

    private var previousBlockSize = 0
    private var hasPrevious = false

    /** Samples still to be thrown away from the front of the output. */
    private var pendingDiscard = 0L

    /**
     * True after a seek, until a page states where the decoding has got to.
     *
     * A seek restarts decoding on a page boundary, and where that lands in the
     * recording is not knowable from the packets alone: it is the granule
     * position of the page they finish on that says. So the output up to that
     * point is held back rather than guessed at.
     */
    private var awaitingAnchor = false

    /** The frame a seek asked for, which is where output resumes. */
    private var seekTarget = 0L

    /** Frames one channel's part of [output] can hold, which is its longest block. */
    private val stride = longBlock / 2

    private var output = FloatArray(0)
    private var interleaved = FloatArray(0)
    private var closed = false
    private var exhausted = false
    private var decodedAny = false

    init {
        pendingDiscard = frontTrim()
    }

    /**
     * How many samples to throw away from the very front of the stream.
     *
     * A stream cut out of a longer one starts partway into a block. It says so
     * by giving its first page a granule position lower than the number of
     * samples that page decodes to, and the difference is what to drop.
     * Ignoring it shifts the whole file.
     *
     * The answer is not always on the first page, because a page that no packet
     * finishes any output on states nothing useful. So pages are walked until
     * one does.
     *
     * The last page is not one of them. Its granule position falls short for a
     * different reason, which is that an encoder pads the end of what it is
     * given up to a whole block, and that shortfall belongs at the end rather
     * than at the front. A file small enough to fit in one page therefore never
     * reports a front trim, which is the right way round: reading a tail as a
     * front would shift a short file by a few dozen samples, while missing the
     * front trim of a one-page cut stream costs nothing anyone has.
     */
    private fun frontTrim(): Long {
        var previous = -1
        var produced = 0L
        var pendingSize = 0
        var pendingLength = 0
        var started = false
        var at = parsed.audioStartOffset

        while (at < parsed.streamEndOffset) {
            val page = try {
                ogg.pageAt(at)
            } catch (failure: AudioKodecException) {
                if (options.tolerant) return 0L
                throw failure
            } ?: return 0L
            if (page.offset > Long.MAX_VALUE - page.byteLength) {
                throw CorruptStreamException("an Ogg page end overflows 64-bit offsets", page.offset)
            }
            val next = page.offset + page.byteLength
            if (next <= at) {
                throw CorruptStreamException("an Ogg page does not advance", page.offset)
            }
            at = next
            if (page.serialNumber != stream.serialNumber) continue

            var position = 0
            for (segment in page.segments) {
                if (!started) {
                    pendingSize = blockSizeAt(page.payload, position)
                    pendingLength = 0
                    started = true
                }
                position += segment
                pendingLength += segment
                if (segment == 255) continue
                if (pendingLength > 0 && pendingSize > 0) {
                    if (previous > 0) produced += (previous + pendingSize) / 4
                    previous = pendingSize
                }
                started = false
            }
            if (page.isLast) return 0L
            if (page.endsAPacket && produced > 0) {
                val trim = produced - (page.granulePosition - parsed.startGranule)
                return if (trim > 0) trim else 0L
            }
        }
        return 0L
    }

    private fun headerPacket(index: Int): ByteArray {
        val packets = parsed.headerPackets
        if (index >= packets.size) {
            throw CorruptStreamException(
                "this Vorbis stream has ${packets.size} header packets and needs three",
                stream.firstPageOffset,
            )
        }
        return packets[index]
    }

    override fun nextFrame(): AudioFrame? {
        check(!closed) { "this decoder is closed" }
        if (exhausted) return null
        val total = durationFrames

        while (true) {
            if (total != null && positionFrames >= total) return null
            val packet = packets.next()
            if (packet == null) {
                exhausted = true
                if (!decodedAny) (packets.failure ?: parsed.terminalFailure)?.let { throw it }
                if (total != null && positionFrames < total &&
                    (!options.tolerant || !decodedAny)
                ) {
                    throw TruncatedStreamException(
                        "the stream declares $total frames and ends after $positionFrames",
                        parsed.streamEndOffset,
                    )
                }
                return null
            }
            if (packet.length == 0) continue

            val produced = try {
                decodePacket(packet.bytes, packet.length)
            } catch (e: CorruptStreamException) {
                exhausted = true
                if (options.tolerant && decodedAny) return null
                throw e
            }
            var from = 0
            var count = produced
            if (awaitingAnchor) {
                // Nothing decoded since the seek can be placed until a packet
                // finishes a page, because that is the only thing in an Ogg
                // file that states a position. The packet that does may itself
                // have produced nothing, which is what the first packet after a
                // fresh start always does, so this is checked before the count.
                if (packet.granule < 0) continue
                awaitingAnchor = false
                val end = packet.granule - parsed.startGranule
                if (seekTarget >= end) {
                    pendingDiscard = seekTarget - end
                    continue
                }
                from = (seekTarget - (end - produced)).coerceAtLeast(0L).toInt()
                count = produced - from
            }
            if (count <= 0) continue
            if (pendingDiscard > 0) {
                val drop = if (pendingDiscard < count) pendingDiscard.toInt() else count
                pendingDiscard -= drop
                from += drop
                count -= drop
                if (count <= 0) continue
            }
            if (total != null) {
                val left = total - positionFrames
                if (left < count) count = left.toInt()
                if (count <= 0) return null
            }

            val samples = count * channels
            val interleaved = interleavedOf(samples)
            var write = 0
            for (frame in from until from + count) {
                for (channel in 0 until channels) {
                    interleaved[write++] = output[channelOrder[channel] * stride + frame]
                }
            }
            val start = positionFrames
            positionFrames += count
            decodedAny = true
            return AudioFrame(
                format = format,
                samples = if (options.reuseBuffers) interleaved else interleaved.copyOf(samples),
                frameCount = count,
                positionFrames = start,
            )
        }
    }

    /**
     * Decodes one packet and returns how many frames it produced.
     *
     * The frames land in [output], one channel after another with
     * `BLOCK_STRIDE` between them, in the stream's own channel order.
     */
    private fun decodePacket(bytes: ByteArray, length: Int): Int {
        bits.reset(bytes, length)
        if (bits.readBits(1) != 0) return 0

        val modeNumber = bits.readBits(config.modeBits)
        if (bits.endOfPacket || modeNumber >= config.modes.size) {
            throw CorruptStreamException(
                "a Vorbis packet names mode $modeNumber of ${config.modes.size}",
                parsed.audioStartOffset,
            )
        }
        val mode = config.modes[modeNumber]
        val n = if (mode.longBlock) longBlock else shortBlock
        var previousLong = true
        var nextLong = true
        if (mode.longBlock) {
            previousLong = bits.readBits(1) == 1
            nextLong = bits.readBits(1) == 1
        }
        val half = n / 2
        val mapping = config.mappings[mode.mapping]

        for (channel in 0 until channels) {
            val floor = config.floors[mapping.submapFloor[mapping.mux[channel]]]
            floorForChannel[channel] = floor
            val decoded = floor.decode(bits, config.codebooks, floorStates[channel])
            floorUnused[channel] = !decoded
            noResidue[channel] = !decoded
        }

        // A coupled pair is decoded together, so if either side has anything to
        // say then both are decoded.
        for (step in mapping.couplingMagnitude.indices) {
            val magnitude = mapping.couplingMagnitude[step]
            val angle = mapping.couplingAngle[step]
            if (!noResidue[magnitude] || !noResidue[angle]) {
                noResidue[magnitude] = false
                noResidue[angle] = false
            }
        }

        for (channel in 0 until channels) {
            val block = blocks[channel]
            for (i in 0 until half) block[i] = 0f
        }

        for (submap in 0 until mapping.submaps) {
            var used = 0
            for (channel in 0 until channels) {
                if (mapping.mux[channel] != submap) continue
                submapChannels[used] = blocks[channel]
                submapSkip[used] = noResidue[channel]
                used++
            }
            if (used == 0) continue
            config.residues[mapping.submapResidue[submap]].decode(
                reader = bits,
                codebooks = config.codebooks,
                channels = submapChannels,
                used = used,
                skip = submapSkip,
                half = half,
                scratch = residueScratch,
            )
        }

        // Undo the coupling, last step first, because each step was applied to
        // the result of the one before it.
        for (step in mapping.couplingMagnitude.indices.reversed()) {
            val magnitudes = blocks[mapping.couplingMagnitude[step]]
            val angles = blocks[mapping.couplingAngle[step]]
            for (i in 0 until half) {
                val magnitude = magnitudes[i]
                val angle = angles[i]
                val newMagnitude: Float
                val newAngle: Float
                if (magnitude > 0) {
                    if (angle > 0) {
                        newMagnitude = magnitude
                        newAngle = magnitude - angle
                    } else {
                        newAngle = magnitude
                        newMagnitude = magnitude + angle
                    }
                } else {
                    if (angle > 0) {
                        newMagnitude = magnitude
                        newAngle = magnitude + angle
                    } else {
                        newAngle = magnitude
                        newMagnitude = magnitude - angle
                    }
                }
                magnitudes[i] = newMagnitude
                angles[i] = newAngle
            }
        }

        val window = config.windowFor(mode.longBlock, previousLong, nextLong)
        val mdct = if (mode.longBlock) longMdct else shortMdct

        for (channel in 0 until channels) {
            val block = blocks[channel]
            if (floorUnused[channel]) {
                for (i in 0 until n) block[i] = 0f
                continue
            }
            floorForChannel[channel]!!.synthesise(floorStates[channel], curve, half)
            for (i in 0 until half) block[i] *= curve[i]
            mdct.transform(block, block, MDCT_SCALE)
            for (i in 0 until n) block[i] *= window[i]
        }

        return overlapAdd(n)
    }

    /**
     * Adds this block onto the tail of the one before and hands back what is
     * now complete.
     *
     * The samples that leave the decoder are the ones between the previous
     * block's centre and this one's. Everything after this block's centre is
     * kept, because its other half has not arrived yet.
     */
    private fun overlapAdd(n: Int): Int {
        if (!hasPrevious) {
            for (channel in 0 until channels) {
                val block = blocks[channel]
                val tail = tails[channel]
                for (i in 0 until n / 2) tail[i] = block[n / 2 + i]
                for (i in n / 2 until tail.size) tail[i] = 0f
            }
            previousBlockSize = n
            hasPrevious = true
            return 0
        }

        val previous = previousBlockSize
        val count = previous / 4 + n / 4
        val shift = previous / 4 - n / 4
        val needed = channels * stride
        if (output.size < needed) {
            options.checkAllocation(needed.toLong() * 4, "decoding a Vorbis block")
            output = FloatArray(needed)
        }

        for (channel in 0 until channels) {
            val block = blocks[channel]
            val tail = tails[channel]
            val base = channel * stride
            for (i in 0 until count) {
                var value = if (i < previous / 2) tail[i] else 0f
                val at = i - shift
                if (at in 0 until n) value += block[at]
                output[base + i] = value
            }
            // What is left of the previous block past this block's centre has
            // to be carried forward as well, which happens whenever a long
            // block is followed by a short one.
            for (i in 0 until n / 2) {
                var value = block[n / 2 + i]
                val old = count + i
                if (old < previous / 2) value += tail[old]
                tail[i] = value
            }
            for (i in n / 2 until tail.size) tail[i] = 0f
        }

        previousBlockSize = n
        return count
    }

    override fun seek(frameIndex: Long): Long {
        check(!closed) { "this decoder is closed" }
        require(frameIndex >= 0) { "frameIndex must not be negative, was $frameIndex" }
        val total = durationFrames
        val target = if (total != null) frameIndex.coerceAtMost(total) else frameIndex
        exhausted = false
        hasPrevious = false
        previousBlockSize = 0

        awaitingAnchor = false
        seekTarget = target

        val landing = if (target == 0L) {
            null
        } else {
            OggSeek.pageAtOrBefore(
                ogg = ogg,
                serialNumber = stream.serialNumber,
                from = parsed.audioStartOffset,
                to = parsed.streamEndOffset,
                target = target,
                options = options,
                granuleOffset = parsed.startGranule,
                requireFreshStart = true,
            )
        }

        // A target inside the first page has no page before it to be measured
        // from, so it is reached by decoding from the beginning and counting.
        // That is also the only place the front trim applies.
        if (landing == null || landing.pageStart <= parsed.audioStartOffset) {
            packets = ogg.packets(stream.serialNumber, parsed.audioStartOffset, parsed.streamEndOffset)
            pendingDiscard = frontTrim() + target
            positionFrames = target
            return target
        }

        packets = ogg.packets(stream.serialNumber, landing.pageStart, parsed.streamEndOffset)
        pendingDiscard = 0
        awaitingAnchor = true
        positionFrames = target
        return target
    }

    /** The block size of the packet beginning at [at], read from its mode number. */
    private fun blockSizeAt(payload: ByteArray, at: Int): Int {
        if (at >= payload.size) return 0
        bits.reset(payload, payload.size)
        bits.skipBits(at * 8)
        if (bits.readBits(1) != 0) return 0
        val mode = bits.readBits(config.modeBits)
        if (bits.endOfPacket || mode >= config.modes.size) return 0
        return if (config.modes[mode].longBlock) longBlock else shortBlock
    }

    override fun close() {
        if (closed) return
        closed = true
        output = FloatArray(0)
        interleaved = FloatArray(0)
        reader.source.close()
    }

    private fun interleavedOf(samples: Int): FloatArray {
        val existing = interleaved
        if (existing.size >= samples) return existing
        options.checkAllocation(samples.toLong() * 4, "decoding $samples samples")
        val fresh = FloatArray(samples)
        interleaved = fresh
        return fresh
    }

    /**
     * The average bitrate, measured rather than taken from the header.
     *
     * A Vorbis header states the bitrate its encoder aimed at, which for a
     * quality-targeted encode is often nothing like what came out. Dividing the
     * audio by its length is the honest answer, and the stated figure is only
     * used when the length is unknown. This is also what `probe` reports, so
     * the two agree.
     */
    private fun bitrateOf(): Int? {
        val total = durationFrames
        val encodedBytes = (parsed.streamEndOffset - parsed.audioStartOffset).coerceAtLeast(0L)
        if (total != null && total > 0 && encodedBytes > 0) {
            val seconds = total.toDouble() / identification.sampleRate
            if (seconds > 0) {
                return (encodedBytes.toDouble() * 8.0 / seconds)
                    .coerceIn(0.0, Int.MAX_VALUE.toDouble())
                    .toInt()
            }
        }
        return identification.bitrateNominal.takeIf { it > 0 }
    }

    companion object {

        /**
         * What the inverse transform's output is multiplied by.
         *
         * A transform pair has to be normalised somewhere, and Vorbis puts the
         * whole of it in the encoder: the coefficients in the file are already
         * scaled so that the plain unnormalised inverse transform lands in the
         * range a sample lives in. Measured against the reference decoder, this
         * is exactly one for every block size, and a wrong value here shows up
         * as output quieter or louder by a power of two.
         */
        private const val MDCT_SCALE = 1f

        /** The most points a floor 1 curve may have, from the specification. */
        private const val MAX_FLOOR_POINTS = 65

        /**
         * The speaker layout Vorbis assigns to a channel count.
         *
         * The specification fixes these, and the result is stated in AudioKodec's
         * own order rather than Vorbis's, because [orderFor] reorders the
         * samples to match.
         */
        fun layoutFor(channels: Int): ChannelLayout = when (channels) {
            1 -> ChannelLayout.MONO
            2 -> ChannelLayout.STEREO
            3 -> ChannelLayout.SURROUND_3_0
            4 -> ChannelLayout.QUAD
            5 -> ChannelLayout.SURROUND_5_0
            6 -> ChannelLayout.SURROUND_5_1
            7 -> ChannelLayout.of(
                AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_CENTER,
                AudioChannel.LOW_FREQUENCY, AudioChannel.BACK_CENTER,
                AudioChannel.SIDE_LEFT, AudioChannel.SIDE_RIGHT,
            )
            8 -> ChannelLayout.SURROUND_7_1
            else -> ChannelLayout.unknown(channels)
        }

        /**
         * Which Vorbis channel belongs in each of AudioKodec's channel slots.
         *
         * Vorbis interleaves surround audio with the centre channel second,
         * between the two front channels, where WAV and everything derived from
         * it puts it third. Getting this wrong is not subtle: dialogue comes out
         * of a side speaker.
         */
        fun orderFor(channels: Int): IntArray = when (channels) {
            3 -> intArrayOf(0, 2, 1)
            5 -> intArrayOf(0, 2, 1, 3, 4)
            6 -> intArrayOf(0, 2, 1, 5, 3, 4)
            7 -> intArrayOf(0, 2, 1, 6, 5, 3, 4)
            8 -> intArrayOf(0, 2, 1, 7, 5, 6, 3, 4)
            else -> IntArray(channels) { it }
        }
    }
}

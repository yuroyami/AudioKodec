package io.github.yuroyami.audiokodec.internal.mpeg

import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioFrame
import io.github.yuroyami.audiokodec.AudioInfo
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.bits.BitReaderMsb
import io.github.yuroyami.audiokodec.internal.io.SourceReader

/**
 * Decodes MPEG Layer I and Layer II.
 *
 * These are the two layers Layer III was built on top of, and next to it they
 * are simple: a frame carries its own audio with no borrowing from its
 * neighbours, and the audio is subband samples rather than a spectrum, so there
 * is no transform to invert. What a frame holds is how many bits each of the
 * thirty-two subbands was given, how loud each is, and then the samples
 * themselves.
 *
 * The two differ in how much they pack into a frame and in how the allocation
 * is coded. Layer I gives every subband four bits to name a width and codes 384
 * samples. Layer II names a width from a table chosen by the bitrate, lets a
 * band's loudness be stated once instead of three times, packs the smallest
 * three widths three samples to a codeword, and codes 1152.
 *
 * Written from ISO/IEC 11172-3 and 13818-3.
 */
internal class Layer12Decoder(
    private val reader: SourceReader,
    private val stream: MpegStream,
    override val info: AudioInfo,
    private val options: DecodeOptions,
) : AudioDecoder {

    private val header0 = stream.first

    override val format: AudioFormat = AudioFormat(
        header0.sampleRate,
        ChannelLayout.canonical(header0.channels),
    )

    override val durationFrames: Long? = stream.durationFrames

    override var positionFrames: Long = 0L
        private set

    private val channels = header0.channels
    private val layer = header0.layer

    private val filter = PolyphaseFilter(channels)

    /** Levels each subband is quantised to, or zero when it carries nothing. */
    private val levels = Array(channels) { IntArray(32) }

    /** The scalefactor each of the three parts of a frame uses. */
    private val scalefactors = Array(channels) { Array(32) { IntArray(3) } }

    /** One filterbank step's worth of subband samples, three at a time. */
    private val work = Array(channels) { DoubleArray(3 * 32) }

    private val frameBytes = ByteArray(MAX_FRAME_BYTES)
    private var output = FloatArray(header0.samplesPerFrame * channels)

    private var at: Long = stream.audioOffset
    private var frameNumber: Long = 0L
    private val index = ArrayList<Long>().apply { add(stream.audioOffset) }

    private var pendingTrim: Int = stream.startTrim
    private var finished = false
    private var priming = 0
    private var decodedAny = false
    private var closed = false

    override fun nextFrame(): AudioFrame? {
        checkOpen()
        while (true) {
            if (finished || durationFrames?.let { positionFrames >= it } == true) {
                finished = true
                return null
            }
            val header = readFrame()
            if (header == null) {
                finished = true
                return null
            }

            val produced = try {
                decodeFrame(header)
            } catch (failure: TruncatedStreamException) {
                if (!options.tolerant || !decodedAny) throw failure
                finished = true
                return null
            } catch (failure: CorruptStreamException) {
                if (!options.tolerant || !decodedAny) throw failure
                finished = true
                return null
            }
            if (priming > 0) {
                priming--
                continue
            }
            if (produced <= 0) continue

            var first = 0
            var count = produced
            if (pendingTrim > 0) {
                val drop = if (pendingTrim < count) pendingTrim else count
                first += drop
                count -= drop
                pendingTrim -= drop
                if (count <= 0) continue
            }

            val total = durationFrames
            if (total != null) {
                val left = total - positionFrames
                if (left <= 0L) {
                    finished = true
                    return null
                }
                if (left < count) count = left.toInt()
            }

            val position = positionFrames
            positionFrames += count
            decodedAny = true
            val samples = count * channels
            val start = first * channels
            val emitted = when {
                start != 0 -> output.copyOfRange(start, start + samples)
                options.reuseBuffers -> output
                else -> output.copyOf(samples)
            }
            return AudioFrame(format, emitted, count, position)
        }
    }

    /** Reads the next frame's bytes, stepping over anything that is not one. */
    private fun readFrame(): MpegFrameHeader? {
        var scanned = 0L
        while (at + 4 <= stream.audioEnd) {
            reader.seek(at)
            val headerBytes = reader.readSome(frameBytes, 0, 4)
            if (headerBytes < 4 || MpegStream.isTrailingTag(reader, at, frameBytes)) return null
            val header = MpegFrameHeader.parse(frameBytes, 0)
            if (header == null || !header.matches(header0)) {
                at++
                scanned++
                if (scanned > RESYNC_BYTES) {
                    if (!options.tolerant || !decodedAny) {
                        throw CorruptStreamException(
                            "no MPEG audio frame was found in $RESYNC_BYTES bytes",
                            at,
                        )
                    }
                    return null
                }
                continue
            }
            val length = header.frameBytes
            if (length < 4 || length > frameBytes.size) {
                throw CorruptStreamException("an MPEG frame declares an invalid $length-byte size", at)
            }
            if (length.toLong() > stream.audioEnd - at) {
                if (!options.tolerant || !decodedAny) {
                    throw io.github.yuroyami.audiokodec.TruncatedStreamException(
                        "the final MPEG frame declares $length bytes but the stream ends early",
                        at,
                    )
                }
                return null
            }
            reader.seek(at)
            val got = reader.readSome(frameBytes, 0, length)
            if (got < length) {
                if (!options.tolerant || !decodedAny) {
                    throw io.github.yuroyami.audiokodec.TruncatedStreamException(
                        "the final MPEG frame ends after $got of $length bytes",
                        at,
                    )
                }
                return null
            }
            remember(frameNumber, at)
            at += length
            frameNumber++
            return header
        }
        return null
    }

    /** Decodes one frame into [output], returning the sample frames it made. */
    private fun decodeFrame(header: MpegFrameHeader): Int {
        val start = 4 + if (header.hasCrc) 2 else 0
        val bits = BitReaderMsb(frameBytes, start, header.frameBytes - start)
        val samplesPerFrame = header.samplesPerFrame
        val needed = samplesPerFrame * channels
        if (output.size < needed) output = FloatArray(needed)

        return if (layer == 1) decodeLayer1(header, bits) else decodeLayer2(header, bits)
    }

    /**
     * Layer I: one width per subband, one loudness per subband, then twelve
     * samples for each of them in turn.
     */
    private fun decodeLayer1(header: MpegFrameHeader, bits: BitReaderMsb): Int {
        val bound = Layer12Tables.boundOf(header, 32)

        for (sb in 0 until 32) {
            if (sb < bound) {
                for (ch in 0 until channels) levels[ch][sb] = layer1LevelsFrom(bits.readBits(4))
            } else {
                // Above the bound one width covers both channels, because only
                // one set of samples is sent for them.
                val shared = layer1LevelsFrom(bits.readBits(4))
                for (ch in 0 until channels) levels[ch][sb] = shared
            }
        }

        checkCrc(header, bits)

        for (sb in 0 until 32) {
            for (ch in 0 until channels) {
                if (levels[ch][sb] == 0) continue
                val index = bits.readBits(6)
                for (part in 0 until 3) scalefactors[ch][sb][part] = index
            }
        }

        var write = 0
        for (slot in 0 until 12) {
            for (ch in 0 until channels) work[ch].fill(0.0, 0, 32)
            for (sb in 0 until 32) {
                if (sb < bound) {
                    for (ch in 0 until channels) {
                        val count = levels[ch][sb]
                        if (count == 0) continue
                        work[ch][sb] = sample(bits.readBits(Layer12Tables.widthOf(count)), count) *
                            Layer12Tables.SCALEFACTORS[scalefactors[ch][sb][0]]
                    }
                } else {
                    val count = levels[0][sb]
                    if (count == 0) continue
                    val value = sample(bits.readBits(Layer12Tables.widthOf(count)), count)
                    for (ch in 0 until channels) {
                        work[ch][sb] = value * Layer12Tables.SCALEFACTORS[scalefactors[ch][sb][0]]
                    }
                }
            }
            for (ch in 0 until channels) {
                filter.step(work[ch], 0, 1, ch, output, write + ch, channels)
            }
            write += 32 * channels
        }
        return 384
    }

    /**
     * Checks a protected frame once its protected bits, which end where the
     * reader now is, have been read.
     *
     * Layer I joint stereo is read two ways. The allocation actually sent is
     * shorter above the bound, and that is what the standard protects; ffmpeg
     * checks four bits for every subband of every channel instead, as though
     * there were no bound. With no encoder to settle which one files carry,
     * either is accepted, which leaves a damaged frame almost as unlikely to
     * pass as before.
     */
    private fun checkCrc(header: MpegFrameHeader, bits: BitReaderMsb) {
        if (!header.hasCrc) return
        val boundless = if (layer == 1 && header.channelMode == MpegFrameHeader.MODE_JOINT) 32 * channels * 4 else -1
        MpegCrc.verify(frameBytes, bits.bitPosition, options, at - header.frameBytes, boundless)
    }

    private fun layer1LevelsFrom(code: Int): Int {
        val width = Layer12Tables.layer1Width(code)
        // Fifteen is not an allocation. Treating the subband as empty keeps the
        // rest of the frame in step, which rejecting it would not.
        if (width <= 0) return 0
        return Layer12Tables.layer1Levels(code)
    }

    /**
     * Layer II: a width from the table the bitrate chose, up to three loudnesses
     * per subband with a code saying which of the frame's three parts each
     * covers, then twelve groups of three samples.
     */
    private fun decodeLayer2(header: MpegFrameHeader, bits: BitReaderMsb): Int {
        val table = Layer12Tables.tableFor(header)
        val sblimit = table.sblimit
        val bound = Layer12Tables.boundOf(header, sblimit)

        for (ch in 0 until channels) levels[ch].fill(0)
        for (sb in 0 until sblimit) {
            val width = table.nbal[sb]
            if (width == 0) continue
            if (sb < bound) {
                for (ch in 0 until channels) levels[ch][sb] = table.levels[sb][bits.readBits(width)]
            } else {
                val shared = table.levels[sb][bits.readBits(width)]
                for (ch in 0 until channels) levels[ch][sb] = shared
            }
        }

        // Which of the frame's three parts share a loudness. Reading this for
        // every allocated subband of both channels comes before any of the
        // values themselves.
        val selection = Array(channels) { IntArray(sblimit) }
        for (sb in 0 until sblimit) {
            for (ch in 0 until channels) {
                if (levels[ch][sb] != 0) selection[ch][sb] = bits.readBits(2)
            }
        }
        checkCrc(header, bits)

        for (sb in 0 until sblimit) {
            for (ch in 0 until channels) {
                if (levels[ch][sb] == 0) continue
                val parts = scalefactors[ch][sb]
                when (selection[ch][sb]) {
                    0 -> {
                        parts[0] = bits.readBits(6)
                        parts[1] = bits.readBits(6)
                        parts[2] = bits.readBits(6)
                    }
                    1 -> {
                        val shared = bits.readBits(6)
                        parts[0] = shared
                        parts[1] = shared
                        parts[2] = bits.readBits(6)
                    }
                    2 -> {
                        val shared = bits.readBits(6)
                        parts[0] = shared
                        parts[1] = shared
                        parts[2] = shared
                    }
                    else -> {
                        parts[0] = bits.readBits(6)
                        val shared = bits.readBits(6)
                        parts[1] = shared
                        parts[2] = shared
                    }
                }
            }
        }

        val codes = IntArray(3)
        var write = 0
        for (granule in 0 until 12) {
            // Twelve granules of three samples each, in three parts of four
            // granules, and each part has its own loudness.
            val part = granule / 4
            for (ch in 0 until channels) work[ch].fill(0.0)

            for (sb in 0 until sblimit) {
                if (sb < bound) {
                    for (ch in 0 until channels) {
                        val count = levels[ch][sb]
                        if (count == 0) continue
                        readTriple(bits, count, codes)
                        val gain = Layer12Tables.SCALEFACTORS[scalefactors[ch][sb][part]]
                        for (s in 0 until 3) work[ch][s * 32 + sb] = sample(codes[s], count) * gain
                    }
                } else {
                    val count = levels[0][sb]
                    if (count == 0) continue
                    readTriple(bits, count, codes)
                    for (ch in 0 until channels) {
                        val gain = Layer12Tables.SCALEFACTORS[scalefactors[ch][sb][part]]
                        for (s in 0 until 3) work[ch][s * 32 + sb] = sample(codes[s], count) * gain
                    }
                }
            }

            for (s in 0 until 3) {
                for (ch in 0 until channels) {
                    filter.step(work[ch], s * 32, 1, ch, output, write + ch, channels)
                }
                write += 32 * channels
            }
        }
        return 1152
    }

    /**
     * Reads three samples, which for the three smallest widths arrive as one
     * codeword holding all of them.
     */
    private fun readTriple(bits: BitReaderMsb, count: Int, into: IntArray) {
        if (Layer12Tables.isGrouped(count)) {
            var packed = bits.readBits(Layer12Tables.codeBits(count))
            for (s in 0 until 3) {
                into[s] = packed % count
                packed /= count
            }
            return
        }
        val width = Layer12Tables.widthOf(count)
        for (s in 0 until 3) into[s] = bits.readBits(width)
    }

    private fun sample(code: Int, count: Int): Double = Layer12Tables.dequantise(code, count)

    override fun seek(frameIndex: Long): Long {
        checkOpen()
        val total = durationFrames
        val target = when {
            frameIndex < 0L -> 0L
            total != null && frameIndex > total -> total
            else -> frameIndex
        }

        val samplesPerFrame = header0.samplesPerFrame.toLong()
        val absolute = target + stream.startTrim
        val wanted = absolute / samplesPerFrame
        val within = (absolute % samplesPerFrame).toInt()

        // A frame carries its own audio, so only the filterbank's history has
        // to be filled again, and that takes far less than a whole frame.
        val primeFrom = (wanted - PRIME_FRAMES).coerceAtLeast(0L)
        // Frames are found by counting from the nearest one already seen. A
        // landing inside the part of the stream already indexed costs a few
        // headers; one beyond it means walking the stream, which the caller
        // may have ruled out.
        if (!options.allowSlowSeek && primeFrom / INDEX_STRIDE >= index.size) {
            throw UnsupportedFeatureException(
                "seeking an MPEG stream past what has been indexed",
                "this stream has no seek table reaching frame $wanted, and slow seeking is off",
            )
        }

        filter.reset()
        at = frameOffsetOf(primeFrom)
        frameNumber = primeFrom
        finished = false
        priming = (wanted - primeFrom).toInt()
        pendingTrim = within
        positionFrames = target
        return target
    }

    private fun remember(number: Long, offset: Long) {
        if (number % INDEX_STRIDE != 0L) return
        if ((number / INDEX_STRIDE).toInt() == index.size) index.add(offset)
    }

    /** Where a frame starts, counted from the nearest one already seen. */
    private fun frameOffsetOf(target: Long): Long {
        if (target <= 0L) return stream.audioOffset
        val slot = ((target / INDEX_STRIDE).toInt()).coerceAtMost(index.size - 1)
        var offset = index[slot]
        var counted = slot.toLong() * INDEX_STRIDE

        val probe = ByteArray(4)
        while (counted < target && offset + 4 <= stream.audioEnd) {
            reader.seek(offset)
            if (reader.readSome(probe, 0, 4) < 4) break
            val header = MpegFrameHeader.parse(probe, 0)
            if (header == null || !header.matches(header0)) {
                offset++
                continue
            }
            remember(counted, offset)
            offset += header.frameBytes
            counted++
        }
        remember(counted, offset)
        return offset.coerceAtMost(stream.audioEnd)
    }

    override fun close() {
        if (closed) return
        closed = true
        reader.source.close()
    }

    private fun checkOpen() {
        check(!closed) { "this decoder is closed" }
    }

    private companion object {
        const val MAX_FRAME_BYTES = 2048
        const val RESYNC_BYTES = 64L * 1024L
        const val INDEX_STRIDE = 32L

        /** Frames decoded and discarded after a seek, to fill the filterbank. */
        const val PRIME_FRAMES = 2L
    }
}

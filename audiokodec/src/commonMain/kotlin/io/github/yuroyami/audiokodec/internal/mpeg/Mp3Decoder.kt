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
import io.github.yuroyami.audiokodec.UnsupportedFormatException
import io.github.yuroyami.audiokodec.internal.bits.BitReaderMsb
import io.github.yuroyami.audiokodec.internal.io.SourceReader

/**
 * Decodes an MPEG Layer III stream.
 *
 * The frame loop is most of what is unusual about this format. A frame's audio
 * is not inside that frame: the encoder writes it wherever there was room, up
 * to 511 bytes earlier, and each frame says how far back to look. So decoding
 * is two passes over the same bytes, one that collects frames into a pool and
 * one that reads granules out of it, and a frame that arrives before the pool
 * holds what it points at cannot be decoded at all.
 *
 * That is also why seeking here is not exact by itself. Landing on a frame and
 * decoding it gives the wrong answer twice over: the pool is empty, and the
 * overlap the transform carries between granules is missing. Both are fixed the
 * same way, by decoding a few frames before the target and throwing them away.
 *
 * Written from ISO/IEC 11172-3 and 13818-3.
 */
internal class Mp3Decoder(
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

    /** One per channel, kept between granules so scalefactors can carry over. */
    private val granules = Array(channels) { Mp3Granule() }
    private val synthesis = Mp3Synthesis(channels)
    private val reservoir = Mp3Reservoir()

    /** One granule of subband samples per channel, before the filterbank. */
    private val timeDomain = Array(channels) { DoubleArray(576) }

    private val frameBytes = ByteArray(MAX_FRAME_BYTES)
    private val mainData = ByteArray(MAX_MAIN_DATA)
    private var output = FloatArray(header0.samplesPerFrame * channels)

    /** Where in the file the next frame starts. */
    private var at: Long = stream.audioOffset

    /** Which frame that is, counted from the first one. */
    private var frameNumber: Long = 0L

    /**
     * Where every so many frames begins, filled in as frames are read.
     *
     * A constant bitrate file does not have constant frame lengths. When the
     * bitrate does not divide the sample rate the encoder pads about one frame
     * in three by a single byte, so multiplying a frame number by a length
     * drifts, and by a minute in it is out by hundreds of bytes. Frames have to
     * be counted, and this is what stops every seek counting them again.
     */
    private val index = ArrayList<Long>().apply { add(stream.audioOffset) }
    private val xingSeek = MpegXingSeek(reader, stream)

    /** Samples still to be dropped from the front of what comes out. */
    private var pendingTrim: Int = stream.startTrim

    /** True once the file, or the stated length, has run out. */
    private var finished = false

    /**
     * Frames to decode and throw away before output is trusted again.
     *
     * After a seek, both the bit reservoir and the transform's overlap are
     * empty, and neither is recoverable from the frame that was landed on.
     */
    private var priming = 0
    private var decodedAny = false
    private var closed = false

    init {
        if (header0.layer != 3) {
            throw UnsupportedFormatException(
                info.container,
                "this is ${header0.description}, and AudioKodec decodes Layer III only " +
                    "for now. Its length, its tags and everything else in probe work.",
            )
        }
    }

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
            // Priming counts frames read, not frames that came to anything.
            // The first frame after a seek reaches back into a pool that is
            // still empty and produces nothing, and counting only the ones that
            // produced would quietly swallow a frame of real audio.
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

            // The stated length is the encoder's, and the last frame usually
            // runs past it. What is past it is padding, not audio.
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
            return AudioFrame(
                format = format,
                samples = emitted,
                frameCount = count,
                positionFrames = position,
            )
        }
    }

    /**
     * Reads the next frame's bytes into [frameBytes].
     *
     * A stream can change shape partway through, and a byte or two of rubbish
     * between frames is common enough that resynchronising has to be ordinary
     * rather than an error.
     */
    private fun readFrame(): MpegFrameHeader? {
        var scanned = 0L
        while (at + 4 <= stream.audioEnd) {
            reader.seek(at)
            val got = reader.readSome(frameBytes, 0, 4)
            if (got < 4 || MpegStream.isTrailingTag(reader, at, frameBytes)) return null
            val tagEnd = try {
                MpegStream.embeddedTagEnd(reader, at, frameBytes, stream.audioEnd)
            } catch (failure: TruncatedStreamException) {
                if (!options.tolerant || !decodedAny) throw failure
                return null
            }
            if (tagEnd != null) {
                at = tagEnd
                continue
            }
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
            val frameGot = reader.readSome(frameBytes, 0, length)
            if (frameGot < length) {
                if (!options.tolerant || !decodedAny) {
                    throw io.github.yuroyami.audiokodec.TruncatedStreamException(
                        "the final MPEG frame ends after $frameGot of $length bytes",
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

    /**
     * Decodes one frame into [output] and returns how many sample frames it
     * produced, which is zero when the frame's data was not there to decode.
     */
    private fun decodeFrame(header: MpegFrameHeader): Int {
        val sideInfoAt = 4 + if (header.hasCrc) 2 else 0
        val mainStart = sideInfoAt + header.sideInfoBytes
        val mainLength = header.frameBytes - mainStart
        if (mainLength <= 0) return 0
        if (header.hasCrc) {
            MpegCrc.verify(frameBytes, header.sideInfoBytes * 8, options, at - header.frameBytes)
        }

        val sideInfo = Mp3SideInfo.parse(frameBytes, sideInfoAt, header)

        val assembled = reservoir.assemble(
            sideInfo.mainDataBegin,
            frameBytes,
            mainStart,
            mainLength,
            mainData,
        )
        reservoir.append(frameBytes, mainStart, mainLength)
        // The pool did not hold what this frame points back at, which is what
        // the first frames of a file and the first frame after a seek look
        // like. There is nothing to decode, and the next frame may well be
        // fine, so this is not an error.
        if (assembled == null) {
            synthesis.reset()
            return 0
        }

        val bits = BitReaderMsb(mainData, 0, assembled)
        // Validate the whole frame before advancing either channel's overlap
        // or filterbank. An empty granule may start exactly at the end of the
        // main data; only a granule which actually needs missing bits is bad.
        val requiredBits = sideInfo.granules.sumOf { granule -> granule.sumOf { it.part2And3Length } }
        if (requiredBits > bits.bitLength) {
            throw CorruptStreamException(
                "the MPEG frame needs $requiredBits main data bits but only ${bits.bitLength} are available",
                at - header.frameBytes,
            )
        }
        val granuleCount = sideInfo.granules.size
        val samplesPerFrame = header.samplesPerFrame
        val needed = samplesPerFrame * channels
        if (output.size < needed) output = FloatArray(needed)

        for (g in 0 until granuleCount) {
            for (ch in 0 until channels) {
                val granule = granules[ch]
                val info = sideInfo.granules[g][ch]
                val start = bits.bitPosition

                if (header.version == 1) {
                    granule.readScalefactorsVersion1(bits, info, sideInfo.scfsi[ch], g)
                } else {
                    // The right channel of an intensity stereo pair codes
                    // positions rather than loudnesses, and packs them
                    // differently.
                    val intensityRight = ch == 1 &&
                        header.channelMode == MpegFrameHeader.MODE_JOINT &&
                        header.modeExtension and 0x1 != 0
                    granule.readScalefactorsVersion2(bits, info, header, intensityRight)
                }

                val used = bits.bitPosition - start
                val left = (info.part2And3Length - used).coerceAtLeast(0)
                granule.readSpectrum(bits, info, header, left)
            }

            if (channels == 2) {
                synthesis.applyStereo(granules[0], granules[1], header, sideInfo.granules[g][0])
            }

            for (ch in 0 until channels) {
                val granule = granules[ch]
                val info = sideInfo.granules[g][ch]
                synthesis.reorder(granule, info, header)
                synthesis.reduceAlias(granule, info)
                synthesis.inverseTransform(granule, info, ch, timeDomain[ch])
                synthesis.synthesise(
                    timeDomain[ch],
                    ch,
                    output,
                    outOffset = g * 576 * channels + ch,
                    stride = channels,
                )
            }
        }
        return samplesPerFrame
    }

    override fun seek(frameIndex: Long): Long {
        checkOpen()
        require(frameIndex >= 0) { "frameIndex must not be negative, was $frameIndex" }
        val total = durationFrames
        val target = when {
            total != null && frameIndex > total -> total
            else -> frameIndex
        }

        val samplesPerFrame = header0.samplesPerFrame.toLong()
        // A caller counts from the first sample that survives trimming, and the
        // stream counts from the first sample a frame decodes to.
        val absolute = target + stream.startTrim
        val wanted = absolute / samplesPerFrame
        val within = (absolute % samplesPerFrame).toInt()

        // Back up far enough that the reservoir and the transform's overlap are
        // full again by the time the wanted frame arrives.
        val primeFrom = (wanted - PRIME_FRAMES).coerceAtLeast(0L)
        // Frames are found by counting from the nearest one already seen. A
        // landing inside the part of the stream already indexed costs a few
        // headers; one beyond it means walking the stream, which the caller
        // may have ruled out.
        if (!options.allowSlowSeek && primeFrom / INDEX_STRIDE >= index.size && !xingSeek.usable) {
            throw UnsupportedFeatureException(
                "seeking an MPEG stream past what has been indexed",
                "this stream has no seek table reaching frame $wanted, and slow seeking is off",
            )
        }

        val landingOffset = frameOffsetOf(primeFrom)
        reservoir.clear()
        synthesis.reset()
        for (granule in granules) granule.spectrum.fill(0.0)

        at = landingOffset
        frameNumber = primeFrom
        finished = false
        priming = (wanted - primeFrom).toInt()
        pendingTrim = within
        positionFrames = target
        return target
    }

    /** Records where a frame began, for every [INDEX_STRIDE]th one. */
    private fun remember(number: Long, offset: Long) {
        if (number % INDEX_STRIDE != 0L) return
        val slot = (number / INDEX_STRIDE).toInt()
        if (slot == index.size) index.add(offset)
    }

    /**
     * Where a frame starts, counted in whole frames from the first one.
     *
     * Frames are counted rather than multiplied, because their lengths vary in
     * every file: a variable bitrate stream by design, and a constant one by a
     * byte whenever the encoder pads. The counting starts from the nearest
     * frame already seen, so a second seek into a part of the file that has
     * been played is nearly free.
     */
    private fun frameOffsetOf(target: Long): Long {
        if (target <= 0L) return stream.audioOffset
        val slot = ((target / INDEX_STRIDE).toInt()).coerceAtMost(index.size - 1)
        var offset = index[slot]
        var counted = slot.toLong() * INDEX_STRIDE

        // A verified TOC suffix is cheaper for a distant target. Retain the
        // walked prefix for nearer targets and already indexed positions.
        val suffixDistance = minOf(stream.frameCount - target, xingSeek.cachedDistance(target) ?: Long.MAX_VALUE)
        if (xingSeek.usable && target - counted > suffixDistance) {
            xingSeek.find(target)?.let { return it.offset }
            if (!options.allowSlowSeek) {
                throw UnsupportedFeatureException("an unusable Xing seek table",
                    "the Xing hint could not be refined into a complete frame sequence")
            }
        }

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
        /** The largest a frame can be, which is Layer II at 384 kbps and 32 kHz. */
        const val MAX_FRAME_BYTES = 2048

        /** A frame's own data plus the most it may reach back for. */
        const val MAX_MAIN_DATA = 511 + MAX_FRAME_BYTES

        /** Frames decoded and discarded before a seek's output is trusted. */
        const val PRIME_FRAMES = 10L

        /** How far to look for the next frame before giving up. */
        const val RESYNC_BYTES = 64L * 1024L

        /** How often a frame's position is worth remembering. */
        const val INDEX_STRIDE = 32L
    }
}

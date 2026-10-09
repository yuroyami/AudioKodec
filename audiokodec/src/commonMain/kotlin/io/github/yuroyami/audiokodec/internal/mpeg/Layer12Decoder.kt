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

    private val frames = Layer12FrameDecoder(channels, layer, options)

    private val frameBytes = ByteArray(maxOf(MAX_FRAME_BYTES, header0.frameBytes + header0.paddingSlot).also {
        options.checkAllocation(it.toLong(), "buffering an MPEG frame")
    })
    private var output = FloatArray(header0.samplesPerFrame * channels)

    private var at: Long = stream.audioOffset
    private var frameNumber: Long = 0L
    private val index = ArrayList<Long>().apply { add(stream.audioOffset) }
    private val xingSeek = MpegXingSeek(reader, stream)

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
                frames.decode(frameBytes, header, output, at - header.frameBytes)
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
            val tagEnd = try {
                MpegStream.embeddedTagEnd(reader, at, frameBytes, stream.audioEnd)
            } catch (failure: io.github.yuroyami.audiokodec.TruncatedStreamException) {
                if (!options.tolerant || !decodedAny) throw failure
                return null
            }
            if (tagEnd != null) {
                at = tagEnd
                continue
            }
            val header = MpegFrameHeader.parse(frameBytes, 0, header0.freeFormatFrameBytes)
            if (header == null || !header.matches(header0)) {
                if (header0.isFreeFormat && scanned == 0L) {
                    if (!options.tolerant || !decodedAny) throw CorruptStreamException("a free-format MPEG frame changes its measured spacing", at)
                    return null
                }
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

    override fun seek(frameIndex: Long): Long {
        checkOpen()
        require(frameIndex >= 0) { "frameIndex must not be negative, was $frameIndex" }
        val total = durationFrames
        val target = when {
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
        if (!options.allowSlowSeek && primeFrom / INDEX_STRIDE >= index.size && !xingSeek.usable) {
            throw UnsupportedFeatureException(
                "seeking an MPEG stream past what has been indexed",
                "this stream has no seek table reaching frame $wanted, and slow seeking is off",
            )
        }

        val landingOffset = frameOffsetOf(primeFrom)
        frames.reset()
        at = landingOffset
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
            val header = MpegFrameHeader.parse(probe, 0, header0.freeFormatFrameBytes)
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

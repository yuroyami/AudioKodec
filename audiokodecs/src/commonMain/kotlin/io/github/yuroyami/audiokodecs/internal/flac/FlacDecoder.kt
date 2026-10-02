package io.github.yuroyami.audiokodecs.internal.flac

import io.github.yuroyami.audiokodecs.AudioChannel
import io.github.yuroyami.audiokodecs.AudioCodec
import io.github.yuroyami.audiokodecs.AudioContainer
import io.github.yuroyami.audiokodecs.AudioDecoder
import io.github.yuroyami.audiokodecs.AudioFormat
import io.github.yuroyami.audiokodecs.AudioFrame
import io.github.yuroyami.audiokodecs.AudioInfo
import io.github.yuroyami.audiokodecs.ChannelLayout
import io.github.yuroyami.audiokodecs.CorruptStreamException
import io.github.yuroyami.audiokodecs.DecodeOptions
import io.github.yuroyami.audiokodecs.IntAudioFrame
import io.github.yuroyami.audiokodecs.IntPcmAccess
import io.github.yuroyami.audiokodecs.Pcm
import io.github.yuroyami.audiokodecs.TruncatedStreamException
import io.github.yuroyami.audiokodecs.UnsupportedFeatureException
import io.github.yuroyami.audiokodecs.internal.io.SourceReader

/**
 * A decoder over the frames of a FLAC stream.
 *
 * FLAC frames vary in size, so a position cannot be turned into a byte offset
 * by multiplying. Seeking therefore uses the seek table when the file has one
 * and a binary search over frame headers when it does not, which is why
 * [FlacFrameReader] verifies both checksums: a false sync word has to be
 * rejected rather than decoded.
 */
internal class FlacDecoder(
    private val reader: SourceReader,
    private val parsed: FlacMetadata.Parsed,
    private val options: DecodeOptions,
) : AudioDecoder, IntPcmAccess {

    private val streamInfo = parsed.streamInfo
    private val frames = FlacFrameReader(reader, streamInfo, options)

    override val format: AudioFormat = AudioFormat(
        streamInfo.sampleRate,
        layoutFor(streamInfo.channels),
    )

    override val bitDepth: Int = streamInfo.bitsPerSample

    override val durationFrames: Long? =
        if (streamInfo.totalSamples > 0) streamInfo.totalSamples else null

    override val info: AudioInfo = AudioInfo(
        container = AudioContainer.FLAC,
        codec = AudioCodec.FLAC,
        format = format,
        durationFrames = durationFrames,
        bitDepth = streamInfo.bitsPerSample,
        bitrate = bitrateOf(),
        isVariableBitrate = true,
        dataOffset = reader.absoluteOffset(parsed.audioOffset),
        dataSize = if (reader.size > 0) reader.size - parsed.audioOffset else -1L,
    )

    override var positionFrames: Long = 0L
        private set

    /** Byte offset of the next frame to decode. */
    private var nextFrameOffset: Long = parsed.audioOffset

    /** Frames to drop from the front of the next decoded block after a seek. */
    private var pendingSkip: Long = 0

    /** Coded sample position expected from the next frame header. */
    private var nextCodedPosition: Long = 0L

    private var closed = false
    private var exhausted = false
    private var decodedAny = false

    private var floatBuffer: FloatArray? = null
    private var intBuffer: IntArray? = null

    /** Set when the caller asked for the stored MD5 to be checked as it goes. */
    private var md5: Md5? = null

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

    /**
     * Decodes the next frame and works out which of it the caller wants.
     *
     * A seek lands on a frame boundary at or before the target, so the front of
     * the first frame after a seek is discarded here.
     */
    private fun nextBlock(): Block? {
        check(!closed) { "this decoder is closed" }
        if (exhausted) return null
        val total = durationFrames
        if (total != null && positionFrames >= total) return null

        while (true) {
            val frame = try {
                frames.decodeAt(nextFrameOffset)
            } catch (e: TruncatedStreamException) {
                exhausted = true
                if (options.tolerant && decodedAny) return null
                throw e
            } catch (e: CorruptStreamException) {
                exhausted = true
                if (options.tolerant && decodedAny) return null
                throw e
            }
            if (frame == null) {
                exhausted = true
                // A stream that states how long it is and then stops early is
                // truncated, even when it stopped neatly on a frame boundary.
                // Ending quietly here would report a short file as a whole one.
                if (total != null && positionFrames < total &&
                    (!options.tolerant || !decodedAny)
                ) {
                    throw TruncatedStreamException(
                        "the stream declares $total frames and ends after $positionFrames",
                        nextFrameOffset,
                    )
                }
                return null
            }

            val statedStart = firstSampleOf(frame)
            if (statedStart != nextCodedPosition) {
                throw CorruptStreamException(
                    "a FLAC frame starts at sample $statedStart, expected $nextCodedPosition",
                    nextFrameOffset,
                )
            }
            if (statedStart > Long.MAX_VALUE - frame.blockSize) {
                throw CorruptStreamException(
                    "a FLAC frame's sample range overflows 64 bits",
                    nextFrameOffset,
                )
            }
            val codedEnd = statedStart + frame.blockSize
            if (total != null && codedEnd > total) {
                throw CorruptStreamException(
                    "a FLAC frame ends at sample $codedEnd, past STREAMINFO's total of $total",
                    nextFrameOffset,
                )
            }
            nextCodedPosition = codedEnd

            if (nextFrameOffset > Long.MAX_VALUE - frame.byteLength) {
                throw CorruptStreamException(
                    "a FLAC frame offset overflows 64 bits",
                    nextFrameOffset,
                )
            }
            nextFrameOffset += frame.byteLength

            val skip = minOf(pendingSkip, frame.blockSize.toLong()).toInt()
            pendingSkip -= skip
            var usable = frame.blockSize - skip
            if (total != null) {
                val left = total - positionFrames
                if (left < usable) usable = left.toInt()
            }
            if (usable <= 0) continue

            md5?.let { hash ->
                // The stored MD5 is over the unencoded samples in file order,
                // in as many bytes as the bit depth needs.
                feedMd5(hash, frame, skip, usable)
            }

            decodedAny = true
            return Block(frame, skip, usable)
        }
    }

    override fun seek(frameIndex: Long): Long {
        check(!closed) { "this decoder is closed" }
        require(frameIndex >= 0) { "frameIndex must not be negative, was $frameIndex" }
        val total = durationFrames
        val target = if (total != null) frameIndex.coerceAtMost(total) else frameIndex

        // Checking the MD5 only means anything over a whole stream, so a seek
        // abandons it rather than reporting a failure the seek caused.
        md5 = null
        exhausted = false

        if (target == 0L) {
            nextFrameOffset = parsed.audioOffset
            pendingSkip = 0L
            nextCodedPosition = 0L
            positionFrames = 0
            return 0
        }

        val landing = findFrameAtOrBefore(target)
        nextFrameOffset = landing.byteOffset
        pendingSkip = (target - landing.firstSample).coerceAtLeast(0L)
        nextCodedPosition = landing.firstSample
        positionFrames = target
        return target
    }

    private class Landing(val byteOffset: Long, val firstSample: Long)

    /**
     * Finds a frame that starts at or before [target].
     *
     * The seek table is used when it has a useful point. Otherwise the frames
     * are scanned, which is what the format leaves a reader to do, and
     * [DecodeOptions.allowSlowSeek] is the caller's control over whether that
     * is acceptable.
     */
    private fun findFrameAtOrBefore(target: Long): Landing {
        var best = Landing(parsed.audioOffset, 0L)
        for (point in parsed.seekPoints) {
            if (point.firstSample <= target && point.firstSample >= best.firstSample) {
                if (parsed.audioOffset > Long.MAX_VALUE - point.byteOffset) {
                    throw CorruptStreamException(
                        "a FLAC seek point overflows the source offset",
                        parsed.audioOffset,
                    )
                }
                val byteOffset = parsed.audioOffset + point.byteOffset
                if (reader.size >= 0 && byteOffset >= reader.size) {
                    if (!options.tolerant) {
                        throw CorruptStreamException(
                            "a FLAC seek point lands past the end of the file",
                            byteOffset,
                        )
                    }
                    continue
                }
                best = Landing(byteOffset, point.firstSample)
            }
        }
        // A seek table entry inside the block we want is as close as we need.
        if (best.firstSample > 0 && target - best.firstSample < streamInfo.maximumBlockSize) {
            return best
        }
        if (!options.allowSlowSeek) {
            if (best.firstSample > 0 || target < streamInfo.maximumBlockSize) return best
            throw UnsupportedFeatureException(
                "seeking a FLAC stream without a useful seek table",
                "this file has no seek point near frame $target, and slow seeking is off",
            )
        }
        return scanForward(best, target)
    }

    /**
     * Walks frames forward from [from] until the one holding [target].
     *
     * Every frame states where it starts, so this reads headers rather than
     * decoding audio. A fixed-size stream states a frame number instead of a
     * sample number, which is multiplied out by the block size.
     */
    private fun scanForward(from: Landing, target: Long): Landing {
        var offset = from.byteOffset
        var landing = from
        val size = reader.size
        while (true) {
            if (size in 1..offset) break
            val frame = try {
                frames.decodeAt(offset)
            } catch (e: TruncatedStreamException) {
                if (!options.tolerant) throw e
                break
            } catch (e: CorruptStreamException) {
                if (!options.tolerant) throw e
                break
            } ?: break

            val start = when {
                frame.sampleNumber >= 0 -> frame.sampleNumber
                // A fixed-size stream states a frame number, and the sample it
                // starts at is that number times the stream's block size. It
                // is emphatically not times this frame's block size: the last
                // frame of a stream is short, and using its own size would
                // place it thousands of samples too early.
                frame.frameNumber >= 0 -> firstSampleOf(frame)
                else -> landing.firstSample
            }
            if (start > target) break
            landing = Landing(offset, start)
            if (start > Long.MAX_VALUE - frame.blockSize || start + frame.blockSize > target) break
            if (frame.byteLength <= 0 || offset > Long.MAX_VALUE - frame.byteLength) {
                throw CorruptStreamException("a FLAC frame offset does not advance", offset)
            }
            offset += frame.byteLength
        }
        return landing
    }

    /**
     * The block size a fixed-size stream uses.
     *
     * The stream information block says so by giving the same minimum and
     * maximum. When it does not, the stream is variable-size and its frames
     * carry sample numbers rather than frame numbers, so this is never
     * consulted.
     */
    private fun firstSampleOf(frame: FlacFrameReader.Frame): Long {
        if (frame.sampleNumber >= 0) return frame.sampleNumber
        if (frame.frameNumber < 0) {
            throw CorruptStreamException("a FLAC frame states neither a frame nor sample number")
        }
        val fixedBlockSize = streamInfo.maximumBlockSize.toLong()
        if (frame.frameNumber > Long.MAX_VALUE / fixedBlockSize) {
            throw CorruptStreamException("a FLAC frame number overflows its sample position")
        }
        return frame.frameNumber * fixedBlockSize
    }

    /**
     * Decodes the whole stream and compares it against the MD5 the encoder
     * stored.
     *
     * This is the strongest check a FLAC decoder can make, because the value
     * was computed by whichever encoder wrote the file. Returns null when the
     * file states no MD5.
     */
    fun verifyMd5(): Boolean? {
        if (!streamInfo.hasMd5) return null
        seek(0)
        val hash = Md5()
        md5 = hash
        try {
            while (nextIntFrame() != null) {
                // The samples are fed to the hash as they are decoded.
            }
        } finally {
            md5 = null
        }
        val digest = hash.digest()
        return digest.contentEquals(streamInfo.md5)
    }

    private fun feedMd5(hash: Md5, frame: FlacFrameReader.Frame, from: Int, count: Int) {
        val bytesPerSample = (frame.bitsPerSample + 7) / 8
        val channels = format.channels
        val scratch = ByteArray(count * channels * bytesPerSample)
        var index = 0
        for (position in from until from + count) {
            for (channel in 0 until channels) {
                val value = frame.channels[channel][position]
                for (byte in 0 until bytesPerSample) {
                    scratch[index++] = (value ushr (8 * byte)).toByte()
                }
            }
        }
        hash.update(scratch)
    }

    override fun close() {
        if (closed) return
        closed = true
        floatBuffer = null
        intBuffer = null
        md5 = null
        reader.source.close()
    }

    private fun scaleOf(bits: Int): Double = (1L shl (bits - 1)).toDouble()

    private fun bitrateOf(): Int? {
        val total = durationFrames ?: return null
        if (total <= 0 || reader.size <= 0) return null
        val seconds = total.toDouble() / streamInfo.sampleRate
        if (seconds <= 0) return null
        return ((reader.size - parsed.audioOffset).toDouble() * 8.0 / seconds)
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

    companion object {
        /**
         * The speaker layout FLAC assigns to a channel count.
         *
         * FLAC fixes these in its specification rather than storing a mask, so
         * a six-channel FLAC file always means the same six speakers.
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
    }
}

package io.github.yuroyami.audiokodecs.internal

import io.github.yuroyami.audiokodecs.AudioDecoder
import io.github.yuroyami.audiokodecs.AudioFormat
import io.github.yuroyami.audiokodecs.AudioFrame
import io.github.yuroyami.audiokodecs.AudioInfo
import io.github.yuroyami.audiokodecs.CorruptStreamException
import io.github.yuroyami.audiokodecs.DecodeOptions
import io.github.yuroyami.audiokodecs.IntAudioFrame
import io.github.yuroyami.audiokodecs.IntPcmAccess
import io.github.yuroyami.audiokodecs.TruncatedStreamException
import io.github.yuroyami.audiokodecs.internal.codec.SampleCodec
import io.github.yuroyami.audiokodecs.internal.io.SourceReader

/**
 * The decoder every block-structured container shares.
 *
 * WAV, AIFF, AU and Wave64 differ in how their headers are written and agree
 * on what follows: a run of equally sized blocks, each independent of the
 * last. Once a container parser has found the audio and picked a codec, this
 * class does the rest, including seeking, which is arithmetic when every block
 * is the same size.
 *
 * Truncation is handled here rather than in each parser. A file whose last
 * block is incomplete decodes up to the last whole block, and whether the
 * short tail is an error depends on [DecodeOptions.tolerant].
 */
internal open class BlockStreamDecoder(
    private val reader: SourceReader,
    private val codec: SampleCodec,
    override val format: AudioFormat,
    override val info: AudioInfo,
    /** First byte of the audio payload. */
    private val dataOffset: Long,
    /** Length of the audio payload in bytes, or -1 to read to end of input. */
    private val dataBytes: Long,
    /**
     * Frames the container says the stream holds, or null to work it out from
     * the payload size.
     *
     * The two differ for codecs whose last block is padded. ADPCM pads to a
     * whole block, and only the container knows how many frames were real.
     */
    declaredFrames: Long?,
    /** Frames at the head of the coded payload that are not part of playback. */
    private val trimStartFrames: Long,
    private val options: DecodeOptions,
) : AudioDecoder {

    private val blockBytes: Int = codec.blockBytes
    private val framesPerBlock: Int = codec.framesPerBlock
    private val channels: Int = format.channels

    /** Whole blocks the payload holds. */
    private val blockCount: Long = if (dataBytes >= 0) {
        dataBytes / blockBytes
    } else {
        val sourceSize = reader.size
        if (sourceSize < 0) Long.MAX_VALUE else (sourceSize - dataOffset).coerceAtLeast(0L) / blockBytes
    }

    private val rawCapacityFrames: Long = when {
        blockCount == Long.MAX_VALUE -> Long.MAX_VALUE
        blockCount > Long.MAX_VALUE / framesPerBlock -> Long.MAX_VALUE
        else -> blockCount * framesPerBlock
    }

    private val capacityFrames: Long = when {
        rawCapacityFrames == Long.MAX_VALUE -> Long.MAX_VALUE
        trimStartFrames <= 0L -> rawCapacityFrames
        else -> (rawCapacityFrames - trimStartFrames).coerceAtLeast(0L)
    }

    private val totalFrames: Long

    init {
        if (dataBytes < -1) {
            throw CorruptStreamException(
                "audio payload size $dataBytes is invalid",
                dataOffset,
            )
        }
        if (declaredFrames != null && declaredFrames < 0) {
            throw CorruptStreamException(
                "the container declares a negative frame count of $declaredFrames",
                dataOffset,
            )
        }
        if (trimStartFrames < 0) {
            throw CorruptStreamException(
                "the container declares a negative priming count of $trimStartFrames",
                dataOffset,
            )
        }
        if (rawCapacityFrames != Long.MAX_VALUE && trimStartFrames > rawCapacityFrames) {
            throw TruncatedStreamException(
                "the container trims $trimStartFrames priming frames from a payload that " +
                    "holds only $rawCapacityFrames",
                dataOffset + dataBytes.coerceAtLeast(0L),
            )
        }
        if (dataBytes >= 0 && dataBytes % blockBytes != 0L && !options.tolerant) {
            throw TruncatedStreamException(
                "the audio payload ends ${dataBytes % blockBytes} bytes into a " +
                    "$blockBytes-byte codec block",
                dataOffset + dataBytes - (dataBytes % blockBytes),
            )
        }
        if (declaredFrames != null && declaredFrames > capacityFrames && !options.tolerant) {
            throw TruncatedStreamException(
                "the container declares $declaredFrames frames, but its payload can hold " +
                    "only $capacityFrames",
                dataOffset + dataBytes.coerceAtLeast(0L),
            )
        }
        totalFrames = when {
            declaredFrames != null -> minOf(declaredFrames, capacityFrames)
            else -> capacityFrames
        }
    }

    /**
     * Blocks decoded per call, aiming at roughly 8192 frames.
     *
     * The allocation ceiling is there to stop a hostile header from making a
     * huge allocation, not to stop a legitimate file from streaming, so the
     * read size shrinks to fit inside it. When even one block will not fit,
     * this stays at one and the guard fires on the buffer instead, which is
     * the honest outcome.
     */
    private val blocksPerRead: Int = run {
        val wanted = (8192 / framesPerBlock).coerceIn(1, 4096)
        val bytesPerBlock = blockBytes.toLong() + framesPerBlock.toLong() * channels * 4
        val affordable = if (bytesPerBlock <= 0) {
            wanted.toLong()
        } else {
            options.maxAllocationBytes / bytesPerBlock
        }
        when {
            affordable < 1 -> 1
            affordable < wanted -> affordable.toInt()
            else -> wanted
        }
    }

    private var nextBlock: Long = trimStartFrames / framesPerBlock

    /** Frames to drop from the front of the next decoded block after a seek. */
    private var pendingSkip: Int = (trimStartFrames % framesPerBlock).toInt()

    private var closed = false

    /** Set once tolerant decoding has stopped at a corrupt block. */
    private var corruptionReached = false

    private val encoded: ByteArray = run {
        val bytes = blockBytes.toLong() * blocksPerRead
        options.checkAllocation(bytes, "reading $blocksPerRead encoded audio blocks")
        ByteArray(bytes.toInt())
    }
    private var floatBuffer: FloatArray? = null
    private var intBuffer: IntArray? = null
    private var decodedAny = false

    override val durationFrames: Long?
        get() = if (totalFrames == Long.MAX_VALUE) null else totalFrames

    final override var positionFrames: Long = 0L
        private set

    override fun nextFrame(): AudioFrame? {
        val read = readBlocks() ?: return null
        val buffer = floatBufferOf(read.blocks * framesPerBlock * channels)
        val blocks = decodeCheckingBlocks(read) { first, count ->
            codec.decode(encoded, first * blockBytes, count, buffer, first * framesPerBlock * channels)
        }
        if (blocks == 0) return null
        val decodedFrames = blocks * framesPerBlock
        return finishFloat(buffer, decodedFrames, minOf(read.limitFrames, decodedFrames))
    }

    /**
     * Decodes the run of blocks [read] holds, through [decodeRun], and returns
     * how many decoded cleanly.
     *
     * A codec that finds a block corrupt throws. Strictly, that failure is
     * passed on with the position of the bad byte in the source rather than
     * in the read buffer. Tolerantly, every complete block before the bad one
     * is kept and the stream ends there, unless nothing usable was ever
     * decoded, in which case there is nothing to be tolerant about and the
     * failure is thrown after all.
     */
    private inline fun decodeCheckingBlocks(read: ReadResult, decodeRun: (first: Int, count: Int) -> Unit): Int {
        try {
            decodeRun(0, read.blocks)
            return read.blocks
        } catch (failure: CorruptStreamException) {
            if (!options.tolerant) throw locate(failure, read.at)
        }
        var good = 0
        var failure: CorruptStreamException? = null
        while (good < read.blocks) {
            try {
                decodeRun(good, 1)
            } catch (corrupt: CorruptStreamException) {
                failure = corrupt
                break
            }
            good++
        }
        corruptionReached = true
        if (good == 0 && !decodedAny && failure != null) throw locate(failure, read.at)
        return good
    }

    /** [failure] with its offset moved from the read buffer to the source. */
    private fun locate(failure: CorruptStreamException, bufferStart: Long): CorruptStreamException {
        if (failure.offset < 0) return failure
        val message = failure.message.orEmpty().removeSuffix(" (at byte ${failure.offset})")
        return CorruptStreamException(message, reader.absoluteOffset(bufferStart) + failure.offset)
    }

    /** Reads and decodes the next run of blocks as stored integers. */
    protected fun nextIntFrameInternal(): IntAudioFrame? {
        val bitDepth = codec.storedBitDepth
            ?: throw UnsupportedOperationException("this codec has no exact integer form")
        val read = readBlocks() ?: return null
        val buffer = intBufferOf(read.blocks * framesPerBlock * channels)
        val blocks = decodeCheckingBlocks(read) { first, count ->
            codec.decodeInt(encoded, first * blockBytes, count, buffer, first * framesPerBlock * channels)
        }
        if (blocks == 0) return null
        val decodedFrames = blocks * framesPerBlock
        val skip = pendingSkip
        pendingSkip = 0
        val usable = (decodedFrames - skip).coerceAtMost(read.limitFrames)
        if (usable <= 0) return null
        if (skip > 0) {
            buffer.copyInto(buffer, 0, skip * channels, (skip + usable) * channels)
        }
        val start = positionFrames
        positionFrames += usable
        decodedAny = true
        return IntAudioFrame(
            format = format,
            samples = if (options.reuseBuffers) buffer else buffer.copyOf(usable * channels),
            frameCount = usable,
            positionFrames = start,
            bitDepth = bitDepth,
        )
    }

    private fun finishFloat(buffer: FloatArray, decodedFrames: Int, limitFrames: Int): AudioFrame? {
        val skip = pendingSkip
        pendingSkip = 0
        val usable = (decodedFrames - skip).coerceAtMost(limitFrames)
        if (usable <= 0) return null
        if (skip > 0) {
            buffer.copyInto(buffer, 0, skip * channels, (skip + usable) * channels)
        }
        val start = positionFrames
        positionFrames += usable
        decodedAny = true
        return AudioFrame(
            format = format,
            samples = if (options.reuseBuffers) buffer else buffer.copyOf(usable * channels),
            frameCount = usable,
            positionFrames = start,
        )
    }

    /**
     * Reads the next run of encoded blocks into [encoded].
     *
     * Returns null at end of stream. [ReadResult.limitFrames] is how many
     * frames of the decoded run are inside the declared duration, which
     * matters for the padded last block of a codec like ADPCM.
     */
    private fun readBlocks(): ReadResult? {
        checkOpen()
        if (corruptionReached) return null
        if (nextBlock >= blockCount) return null
        if (totalFrames != Long.MAX_VALUE && positionFrames >= totalFrames) return null

        val wanted = minOf(blocksPerRead.toLong(), blockCount - nextBlock).toInt()
        if (wanted <= 0) return null
        if (nextBlock > Long.MAX_VALUE / blockBytes) {
            throw CorruptStreamException(
                "an audio block offset overflows 64 bits",
                dataOffset,
            )
        }
        val relative = nextBlock * blockBytes
        if (dataOffset > Long.MAX_VALUE - relative) {
            throw CorruptStreamException(
                "an audio block offset overflows 64 bits",
                dataOffset,
            )
        }
        val at = dataOffset + relative
        reader.seek(at)
        val wantedBytes = wanted * blockBytes
        val got = reader.readSome(encoded, 0, wantedBytes)
        val whole = got / blockBytes
        if (whole <= 0) {
            if (got == 0 && dataBytes < 0) {
                return null
            }
            if (!options.tolerant || !decodedAny) {
                throw TruncatedStreamException(
                    "the audio payload ends after $got bytes where a $blockBytes-byte block was expected",
                    at,
                )
            }
            return null
        }
        val cleanUnknownEnd = dataBytes < 0 && got % blockBytes == 0
        if (got < wantedBytes && !cleanUnknownEnd && !options.tolerant) {
            val partial = got % blockBytes
            val message = if (partial != 0) {
                "the audio payload ends inside a block, $partial bytes into it"
            } else {
                "the audio payload ends after $whole complete blocks; $wanted were expected"
            }
            throw TruncatedStreamException(message, at + whole.toLong() * blockBytes)
        }
        nextBlock += whole
        val decodedFrames = whole.toLong() * framesPerBlock
        val limit = if (totalFrames == Long.MAX_VALUE) {
            decodedFrames
        } else {
            (totalFrames - positionFrames).coerceAtLeast(0L)
        }
        return ReadResult(whole, minOf(decodedFrames, limit).toInt(), at)
    }

    override fun seek(frameIndex: Long): Long {
        checkOpen()
        require(frameIndex >= 0) { "frameIndex must not be negative, was $frameIndex" }
        val target = if (totalFrames == Long.MAX_VALUE) frameIndex else frameIndex.coerceAtMost(totalFrames)
        require(target <= Long.MAX_VALUE - trimStartFrames) {
            "frameIndex $frameIndex plus $trimStartFrames priming frames overflows 64 bits"
        }
        val codedTarget = trimStartFrames + target
        nextBlock = codedTarget / framesPerBlock
        pendingSkip = (codedTarget % framesPerBlock).toInt()
        // A corrupt block ended the stream where it was; elsewhere may be fine.
        corruptionReached = false
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

    private fun checkOpen() {
        check(!closed) { "this decoder is closed" }
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

    private class ReadResult(
        val blocks: Int,
        val limitFrames: Int,
        /** Where the first block of the run starts, relative to the reader. */
        val at: Long,
    )
}

/**
 * The same decoder for codecs that also have an exact integer form.
 *
 * Whether a decoder implements [IntPcmAccess] is how a caller finds out
 * whether bit-exact integers are available, so it has to be a different type
 * rather than a method that sometimes throws.
 */
internal class IntBlockStreamDecoder(
    reader: SourceReader,
    codec: SampleCodec,
    format: AudioFormat,
    info: AudioInfo,
    dataOffset: Long,
    dataBytes: Long,
    declaredFrames: Long?,
    trimStartFrames: Long,
    options: DecodeOptions,
) : BlockStreamDecoder(
    reader,
    codec,
    format,
    info,
    dataOffset,
    dataBytes,
    declaredFrames,
    trimStartFrames,
    options,
),
    IntPcmAccess {

    override val bitDepth: Int = codec.storedBitDepth
        ?: error("IntBlockStreamDecoder needs a codec with an integer form")

    override fun nextIntFrame(): IntAudioFrame? = nextIntFrameInternal()
}

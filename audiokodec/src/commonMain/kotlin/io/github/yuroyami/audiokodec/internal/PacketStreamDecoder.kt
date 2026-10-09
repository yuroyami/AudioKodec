package io.github.yuroyami.audiokodec.internal

import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioFrame
import io.github.yuroyami.audiokodec.AudioInfo
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.IntAudioFrame
import io.github.yuroyami.audiokodec.IntPcmAccess
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.internal.io.SourceReader

/**
 * Where each packet of a coded stream is, and where its output starts.
 *
 * Containers that index their packets, MP4 with its sample tables and CAF
 * with its packet table, describe a stream this way. Positions are counted in
 * coded frames from the first packet, before any trimming, and [starts] has
 * one more entry than there are packets, so that the last one is the end of
 * the stream.
 */
internal class PacketIndex(
    /** Offset of each packet, relative to the reader the decoder is given. */
    val offsets: LongArray,
    val sizes: IntArray,
    val starts: LongArray,
) {
    init {
        require(offsets.size == sizes.size && starts.size == sizes.size + 1) { "a packet index's arrays disagree in length" }
    }

    val count: Int get() = sizes.size

    /** Coded frames the packets add up to. */
    val totalFrames: Long get() = starts[count]

    /** The largest packet, which is the size of buffer reading needs. */
    val largestPacket: Int = sizes.maxOrNull() ?: 0

    /**
     * The packet whose output contains [frame], or the last packet for a
     * frame at or past the end. Packets that yield nothing share their start
     * with the packet after them, and the first of them is the answer: a
     * Vorbis stream's first packet yields nothing but primes the overlap the
     * next one needs.
     */
    fun packetAt(frame: Long): Int {
        var low = 0
        var high = count - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (starts[middle] <= frame) low = middle else high = middle - 1
        }
        while (low > 0 && starts[low - 1] == starts[low]) low--
        return low
    }
}

/**
 * Decodes one packet at a time, for [PacketStreamDecoder].
 *
 * Samples come out interleaved in AudioKodec's channel order, so a codec
 * whose own order differs reorders them itself.
 */
internal interface PacketCodec {
    val format: AudioFormat

    /** Stored bits per sample, or null when the codec has no exact integer form. */
    val storedBitDepth: Int?

    /** The most frames one packet can decode to, which sizes the output buffers. */
    val maxFramesPerPacket: Int

    /**
     * Frames to decode before the recording's start or a seek target and
     * throw away, so that state carried between packets has settled by the
     * target. Zero for a codec whose packets stand alone.
     */
    val preRollFrames: Long

    /**
     * Decodes [length] bytes of [packet] into [out] and returns the frames written.
     * Zero means unavailable coded audio, which must lie wholly before the
     * playable target. It still advances the indexed timeline during preroll.
     */
    fun decode(packet: ByteArray, length: Int, out: FloatArray): Int

    /** Decodes into right-justified integers, when [storedBitDepth] is not null. */
    fun decodeInt(packet: ByteArray, length: Int, out: IntArray): Int =
        throw UnsupportedOperationException("this codec has no exact integer form")

    /** Forgets everything carried between packets, before decoding from somewhere new. */
    fun reset()

    /**
     * Says which packet of the index the next [decode] call reads, for a
     * codec whose packets differ in ways their bytes do not state.
     */
    fun beginPacket(index: Int) = Unit
}

/**
 * The decoder every packet-indexed container shares.
 *
 * Once a container has found its packets and picked a codec, this reads the
 * packets in order, trims the frames the container says are not part of the
 * recording from both ends, and seeks by looking a target up in the index and
 * decoding forward from far enough before it for the codec to settle.
 */
internal open class PacketStreamDecoder(
    private val reader: SourceReader,
    private val index: PacketIndex,
    private val codec: PacketCodec,
    override val info: AudioInfo,
    /** Coded frames before the recording starts: encoder priming, or an edit list's start. */
    private val trimStartFrames: Long,
    /** Frames of the recording after both ends are trimmed. */
    private val playFrames: Long,
    private val options: DecodeOptions,
) : AudioDecoder {

    override val format: AudioFormat = codec.format
    private val channels = format.channels

    init {
        if (trimStartFrames < 0 || playFrames < 0) {
            throw CorruptStreamException("the container trims a negative number of frames")
        }
        if (playFrames > 0 && trimStartFrames > index.totalFrames - playFrames && !options.tolerant) {
            throw TruncatedStreamException(
                "the container promises ${trimStartFrames + playFrames} frames and its packets hold ${index.totalFrames}",
            )
        }
    }

    override val durationFrames: Long = playFrames

    final override var positionFrames: Long = 0L
        private set

    private var nextPacket = packetFor(trimStartFrames)

    /** Decoded frames still to throw away: the trimmed start at first, a seek's run-up after one. */
    private var pendingSkip: Long = if (index.count == 0) 0 else trimStartFrames - index.starts[nextPacket]

    private val packet: ByteArray = run {
        options.checkAllocation(index.largestPacket.toLong(), "reading a ${index.largestPacket}-byte packet")
        ByteArray(index.largestPacket)
    }
    private var floatBuffer: FloatArray? = null
    private var intBuffer: IntArray? = null
    private var closed = false
    private var ended = false
    private var decodedAny = false

    override fun nextFrame(): AudioFrame? {
        while (true) {
            val read = readPacket() ?: return null
            val buffer = floatBufferOf(codec.maxFramesPerPacket * channels)
            val produced = decodeChecking(read) { codec.decode(packet, it, buffer) } ?: return null
            val (from, count) = keep(produced) ?: continue
            if (from > 0) buffer.copyInto(buffer, 0, from * channels, (from + count) * channels)
            val start = positionFrames
            positionFrames += count
            decodedAny = true
            return AudioFrame(
                format = format,
                samples = if (options.reuseBuffers) buffer else buffer.copyOf(count * channels),
                frameCount = count,
                positionFrames = start,
            )
        }
    }

    /** Reads and decodes the next packet as stored integers. */
    protected fun nextIntFrameInternal(bitDepth: Int): IntAudioFrame? {
        while (true) {
            val read = readPacket() ?: return null
            val buffer = intBufferOf(codec.maxFramesPerPacket * channels)
            val produced = decodeChecking(read) { codec.decodeInt(packet, it, buffer) } ?: return null
            val (from, count) = keep(produced) ?: continue
            if (from > 0) buffer.copyInto(buffer, 0, from * channels, (from + count) * channels)
            val start = positionFrames
            positionFrames += count
            decodedAny = true
            return IntAudioFrame(
                format = format,
                samples = if (options.reuseBuffers) buffer else buffer.copyOf(count * channels),
                frameCount = count,
                positionFrames = start,
                bitDepth = bitDepth,
            )
        }
    }

    /** Which of [produced] decoded frames to hand out, after skipping and trimming, or null for none. */
    private fun keep(produced: Int): Pair<Int, Int>? {
        var from = 0
        var count = produced
        if (pendingSkip > 0) {
            val drop = minOf(pendingSkip, count.toLong()).toInt()
            pendingSkip -= drop
            from = drop
            count -= drop
        }
        val left = playFrames - positionFrames
        if (left < count) count = left.toInt()
        return if (count > 0) from to count else null
    }

    /** The next packet's length, read into [packet], or null at the end. */
    private fun readPacket(): Int? {
        check(!closed) { "this decoder is closed" }
        if (ended || positionFrames >= playFrames) return null
        if (nextPacket >= index.count) {
            ended = true
            if (!options.tolerant || !decodedAny) {
                throw TruncatedStreamException(
                    "the stream promises $playFrames frames and its packets ended after $positionFrames",
                )
            }
            return null
        }
        val at = index.offsets[nextPacket]
        val size = index.sizes[nextPacket]
        reader.seek(at)
        val got = reader.readSome(packet, 0, size)
        if (got < size) {
            ended = true
            if (!options.tolerant || !decodedAny) {
                throw TruncatedStreamException("packet $nextPacket of $size bytes is cut off after $got", reader.absoluteOffset(at))
            }
            return null
        }
        codec.beginPacket(nextPacket)
        nextPacket++
        return size
    }

    /**
     * Runs [decode], whose damage strictly is an error at the packet's offset
     * and tolerantly ends the stream, unless nothing has been decoded yet.
     */
    private inline fun decodeChecking(length: Int, decode: (Int) -> Int): Int? {
        try {
            val produced = decode(length)
            if (produced == 0) {
                // An MPEG preroll packet can lack data from before the seek.
                // It still occupies coded time, and none of it may be audible.
                val unavailable = index.starts[nextPacket] - index.starts[nextPacket - 1]
                if (unavailable > pendingSkip) {
                    throw CorruptStreamException("a packet yields no frames inside the playable recording")
                }
                pendingSkip -= unavailable
            }
            return produced
        } catch (failure: CorruptStreamException) {
            ended = true
            if (options.tolerant && decodedAny) return null
            val at = reader.absoluteOffset(index.offsets[nextPacket - 1])
            throw CorruptStreamException(
                (failure.message ?: "a damaged packet").removeSuffix(" (at byte ${failure.offset})"),
                at,
            )
        }
    }

    override fun seek(frameIndex: Long): Long {
        check(!closed) { "this decoder is closed" }
        require(frameIndex >= 0) { "frameIndex must not be negative, was $frameIndex" }
        val target = frameIndex.coerceAtMost(playFrames)
        codec.reset()
        ended = false
        positionFrames = target
        if (index.count == 0) return target
        val coded = trimStartFrames + target
        nextPacket = packetFor(coded)
        pendingSkip = coded - index.starts[nextPacket]
        return target
    }

    /** Opening an edit and seeking to its start need the same packet state. */
    private fun packetFor(coded: Long): Int = if (index.count == 0) 0 else
        index.packetAt((coded - codec.preRollFrames).coerceAtLeast(0L))

    override fun close() {
        if (closed) return
        closed = true
        floatBuffer = null
        intBuffer = null
        reader.source.close()
    }

    private fun floatBufferOf(samples: Int): FloatArray {
        floatBuffer?.let { if (it.size >= samples) return it }
        options.checkAllocation(samples.toLong() * 4, "decoding $samples samples")
        return FloatArray(samples).also { floatBuffer = it }
    }

    private fun intBufferOf(samples: Int): IntArray {
        intBuffer?.let { if (it.size >= samples) return it }
        options.checkAllocation(samples.toLong() * 4, "decoding $samples samples")
        return IntArray(samples).also { intBuffer = it }
    }

    companion object {
        /** A decoder over [codec], with exact integers available when the codec has them. */
        fun of(
            reader: SourceReader,
            index: PacketIndex,
            codec: PacketCodec,
            info: AudioInfo,
            trimStartFrames: Long,
            playFrames: Long,
            options: DecodeOptions,
        ): PacketStreamDecoder {
            val depth = codec.storedBitDepth
            return if (depth != null) {
                IntPacketStreamDecoder(reader, index, codec, info, trimStartFrames, playFrames, options, depth)
            } else {
                PacketStreamDecoder(reader, index, codec, info, trimStartFrames, playFrames, options)
            }
        }
    }
}

/**
 * The same decoder for codecs with an exact integer form, which is how a
 * caller learns that bit-exact integers are available.
 */
internal class IntPacketStreamDecoder(
    reader: SourceReader,
    index: PacketIndex,
    codec: PacketCodec,
    info: AudioInfo,
    trimStartFrames: Long,
    playFrames: Long,
    options: DecodeOptions,
    override val bitDepth: Int,
) : PacketStreamDecoder(reader, index, codec, info, trimStartFrames, playFrames, options), IntPcmAccess {
    override fun nextIntFrame(): IntAudioFrame? = nextIntFrameInternal(bitDepth)
}

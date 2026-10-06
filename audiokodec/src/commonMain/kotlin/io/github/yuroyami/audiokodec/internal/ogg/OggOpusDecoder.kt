package io.github.yuroyami.audiokodec.internal.ogg

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
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.opus.OpusMultistreamDecoder
import io.github.yuroyami.audiokodec.internal.opus.OpusPacket
import io.github.yuroyami.audiokodec.internal.vorbis.VorbisDecoder

/**
 * Decodes Opus audio carried in Ogg, as RFC 7845 lays it out.
 *
 * The packets are plain Opus, so most of the work is the bookkeeping around
 * them. An encoder starts every stream with a few milliseconds it needed and
 * the listener must not hear, and the header says how many: that pre-skip is
 * dropped. The last page's granule position says where the audio really
 * ends, which is usually inside the last packet: the rest is dropped too, so
 * that one file follows another without a gap. The header's output gain is
 * applied, and a surround stream's channels, which Opus keeps in the Vorbis
 * order, are put into AudioKodec's.
 *
 * Opus always decodes at 48 kHz, whatever rate the material was recorded at.
 */
internal class OggOpusDecoder(
    private val reader: SourceReader,
    private val parsed: OggAnalyser.Parsed,
    private val options: DecodeOptions,
) : AudioDecoder {

    private val stream = parsed.audio
        ?: throw CorruptStreamException("this Ogg file carries no Opus stream", 0)

    private val head = stream.identification
    private val channels = stream.channels
    private val family = head[18].toInt() and 0xFF

    private val decoder: OpusMultistreamDecoder = when (family) {
        0 -> OpusMultistreamDecoder.withMapping(SAMPLE_RATE, channels, 1, channels - 1, IntArray(channels) { it })
        3 -> {
            val coded = (head[19].toInt() and 0xFF) + (head[20].toInt() and 0xFF)
            OpusMultistreamDecoder.withDemixingMatrix(
                SAMPLE_RATE, channels, head[19].toInt() and 0xFF, head[20].toInt() and 0xFF,
                IntArray(channels * coded) { (head[21 + 2 * it].toInt() and 0xFF) or (head[22 + 2 * it].toInt() shl 8) },
            )
        }
        else -> OpusMultistreamDecoder.withMapping(
            SAMPLE_RATE, channels, head[19].toInt() and 0xFF, head[20].toInt() and 0xFF,
            IntArray(channels) { head[21 + it].toInt() and 0xFF },
        )
    }.also {
        // RFC 7845 asks for the header's gain to be applied by default, and
        // gives it in Q7.8 decibels, which is the unit the decoder takes.
        it.gain = (head[16].toInt() and 0xFF) or (head[17].toInt() shl 8)
    }

    /**
     * Which decoded channel belongs in each of AudioKodec's channel slots.
     *
     * Family 1 is the Vorbis speaker order, which puts the centre second;
     * the others either have one or two channels or name no speakers at all.
     */
    private val channelOrder = if (family == 1) VorbisDecoder.orderFor(channels) else IntArray(channels) { it }

    override val format: AudioFormat = AudioFormat(
        SAMPLE_RATE,
        when (family) {
            0 -> if (channels == 1) ChannelLayout.MONO else ChannelLayout.STEREO
            1 -> VorbisDecoder.layoutFor(channels)
            // Ambisonic channels are components of a sound field, not
            // speakers, and family 255 leaves their meaning to the
            // application.
            else -> ChannelLayout.unknown(channels)
        },
    )

    override val durationFrames: Long? = parsed.durationFrames

    override val info: AudioInfo = AudioInfo(
        container = AudioContainer.OGG,
        codec = AudioCodec.OPUS,
        format = format,
        durationFrames = durationFrames,
        // Opus codes a spectrum and a speech model, not samples.
        bitDepth = null,
        bitrate = bitrateOf(),
        isVariableBitrate = true,
        dataOffset = reader.absoluteOffset(parsed.audioStartOffset),
        dataSize = (parsed.streamEndOffset - parsed.audioStartOffset).coerceAtLeast(0L),
    )

    override var positionFrames: Long = 0L
        private set

    /** The granule position of the first frame of output: the stream's start plus its pre-skip. */
    private val granuleBase = parsed.startGranule + stream.preSkip

    private val ogg = OggReader(reader, options)
    private var packets: OggReader.PacketReader = ogg.packets(stream.serialNumber, parsed.audioStartOffset)

    private val decoded = run {
        options.checkAllocation(
            OpusPacket.MAX_SAMPLES.toLong() * channels * 4 * 2,
            "decoding $channels channels of Opus",
        )
        FloatArray(OpusPacket.MAX_SAMPLES * channels)
    }
    private var interleaved = FloatArray(0)

    /** Decoded samples still to be thrown away, the pre-skip at first and a seek's run-up after one. */
    private var pendingDiscard = stream.preSkip.toLong()

    private var closed = false
    private var exhausted = false
    private var decodedAny = false

    override fun nextFrame(): AudioFrame? {
        check(!closed) { "this decoder is closed" }
        if (exhausted) return null
        val total = durationFrames
        while (true) {
            if (total != null && positionFrames >= total) return null
            val packet = packets.next()
            if (packet == null) {
                exhausted = true
                if (total != null && positionFrames < total && (!options.tolerant || !decodedAny)) {
                    throw TruncatedStreamException(
                        "the stream declares $total frames and ends after $positionFrames",
                        parsed.streamEndOffset,
                    )
                }
                return null
            }
            // An empty packet holds nothing to decode, not even a frame
            // length to conceal for.
            if (packet.length == 0) continue

            val produced = try {
                decoder.decode(packet.bytes, 0, packet.length, decoded, OpusPacket.MAX_SAMPLES)
            } catch (failure: CorruptStreamException) {
                exhausted = true
                if (options.tolerant && decodedAny) return null
                throw CorruptStreamException(failure.message ?: "a damaged Opus packet", packet.startOffset)
            }
            var from = 0
            var count = produced
            if (pendingDiscard > 0) {
                val drop = if (pendingDiscard < count) pendingDiscard.toInt() else count
                pendingDiscard -= drop
                from += drop
                count -= drop
            }
            if (total != null) {
                val left = total - positionFrames
                if (left < count) count = left.toInt()
            }
            if (count <= 0) continue

            val samples = count * channels
            val out = interleavedOf(samples)
            var write = 0
            for (frame in from until from + count) {
                val base = frame * channels
                for (slot in 0 until channels) out[write++] = decoded[base + channelOrder[slot]]
            }
            val start = positionFrames
            positionFrames += count
            decodedAny = true
            return AudioFrame(
                format = format,
                samples = if (options.reuseBuffers) out else out.copyOf(samples),
                frameCount = count,
                positionFrames = start,
            )
        }
    }

    /**
     * Moves to [frameIndex] by restarting the decoder 80 ms or more before it.
     *
     * Opus carries state from packet to packet, so decoding cannot simply
     * resume at the target: it starts earlier and throws away what comes out
     * until the target, by which point the state has converged. RFC 7845
     * section 6.1 recommends at least 80 ms of this run-up. The restart is on
     * a page that ends with a whole packet, whose granule position is exactly
     * where the next packet's output begins.
     */
    override fun seek(frameIndex: Long): Long {
        check(!closed) { "this decoder is closed" }
        require(frameIndex >= 0) { "frameIndex must not be negative, was $frameIndex" }
        val total = durationFrames
        val target = if (total != null) frameIndex.coerceAtMost(total) else frameIndex
        exhausted = false
        decoder.reset()
        positionFrames = target

        val runUpTarget = target + granuleBase - PRE_ROLL
        val landing = if (runUpTarget <= parsed.startGranule) {
            null
        } else {
            OggSeek.pageAtOrBefore(
                ogg = ogg,
                serialNumber = stream.serialNumber,
                from = parsed.audioStartOffset,
                to = parsed.streamEndOffset,
                target = runUpTarget,
                options = options,
            )
        }
        if (landing == null || landing.pageOffset <= parsed.audioStartOffset) {
            packets = ogg.packets(stream.serialNumber, parsed.audioStartOffset)
            pendingDiscard = stream.preSkip + target
            return target
        }
        packets = ogg.packets(stream.serialNumber, landing.pageOffset)
        pendingDiscard = target + granuleBase - landing.startFrame
        return target
    }

    override fun close() {
        if (closed) return
        closed = true
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

    /** The average bitrate, measured from the audio's size and length. */
    private fun bitrateOf(): Int? {
        val total = durationFrames ?: return null
        val encodedBytes = (parsed.streamEndOffset - parsed.audioStartOffset).coerceAtLeast(0L)
        if (total <= 0 || encodedBytes <= 0) return null
        return (encodedBytes.toDouble() * 8.0 * SAMPLE_RATE / total)
            .coerceIn(0.0, Int.MAX_VALUE.toDouble())
            .toInt()
    }

    private companion object {
        const val SAMPLE_RATE = 48_000

        /** 80 ms at 48 kHz, the run-up RFC 7845 recommends before a seek target. */
        const val PRE_ROLL = 3_840L
    }
}

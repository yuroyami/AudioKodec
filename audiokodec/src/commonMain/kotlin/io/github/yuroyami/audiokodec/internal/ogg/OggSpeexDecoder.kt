package io.github.yuroyami.audiokodec.internal.ogg

import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioFrame
import io.github.yuroyami.audiokodec.AudioInfo
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.speex.SpeexPacketDecoder

/**
 * Decodes Speex audio carried in Ogg, with speexdec's timing.
 *
 * The header names the mode, narrowband, wideband or ultra-wideband, and how
 * many frames each packet holds. The granule positions say where the audio
 * ends. Where the audio starts comes from the first audio page, as speexdec
 * reckons it: the samples that page's packets decode to less the samples its
 * granule position accounts for are the encoder's delay, and the decoder's
 * own delay comes on top, so both are dropped from the start.
 */
internal class OggSpeexDecoder(
    private val reader: SourceReader,
    private val parsed: OggAnalyser.Parsed,
    private val options: DecodeOptions,
) : AudioDecoder {

    private val stream = parsed.audio
        ?: throw CorruptStreamException("this Ogg file carries no Speex stream", 0)

    private val head = stream.identification

    private val decoder: SpeexPacketDecoder = run {
        val versionId = u32(head, 28)
        val mode = u32(head, 40)
        val bitstreamVersion = u32(head, 44)
        if (mode !in 0L..2L) throw CorruptStreamException("the Speex header names mode $mode", stream.firstPageOffset)
        if (versionId > 1 || bitstreamVersion != 4L) {
            throw UnsupportedFeatureException(
                "Speex bitstream version $bitstreamVersion",
                "this Speex stream states header version $versionId and bitstream version $bitstreamVersion; " +
                    "AudioKodec decodes the bitstream Speex 1.0 and later write, version 4, as speexdec does",
            )
        }
        val frames = u32(head, 64).toInt()
        val channels = if (stream.channels == 1) 1 else 2
        options.checkAllocation(640L * maxOf(frames, 1) * channels * 4 * 2, "decoding a Speex packet")
        SpeexPacketDecoder(mode.toInt(), channels, maxOf(frames, 1))
    }

    private val channels = decoder.channels

    override val format: AudioFormat = AudioFormat(stream.sampleRate, channels)

    override val durationFrames: Long? = parsed.durationFrames

    override val info: AudioInfo = AudioInfo(
        container = AudioContainer.OGG,
        codec = AudioCodec.SPEEX,
        format = format,
        durationFrames = durationFrames,
        // Speex codes a speech model, not samples.
        bitDepth = null,
        bitrate = bitrateOf(),
        isVariableBitrate = true,
        dataOffset = reader.absoluteOffset(parsed.audioStartOffset),
        dataSize = (parsed.streamEndOffset - parsed.audioStartOffset).coerceAtLeast(0L),
    )

    override var positionFrames: Long = 0L
        private set

    private val ogg = OggReader(reader, options)
    private var packets: OggReader.PacketReader = ogg.packets(stream.serialNumber, parsed.audioStartOffset, parsed.streamEndOffset)

    /** speexdec's timing, which the analyser worked out from the first and last pages. */
    private val timing = parsed.speexTiming
    private val granuleToDecoded = timing?.granuleToDecoded ?: 0L
    private val startTrim = timing?.startTrim ?: 0L

    private val decoded = FloatArray(decoder.maxSamples * channels)
    private var interleaved = FloatArray(0)
    private var pendingDiscard = startTrim
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
                if (!decodedAny) (packets.failure ?: parsed.terminalFailure)?.let { throw it }
                if (total != null && positionFrames < total && (!options.tolerant || !decodedAny)) {
                    throw TruncatedStreamException(
                        "the stream declares $total frames and ends after $positionFrames",
                        parsed.streamEndOffset,
                    )
                }
                return null
            }
            if (packet.length == 0) continue
            val produced = try {
                decoder.decode(packet.bytes, 0, packet.length, decoded)
            } catch (failure: CorruptStreamException) {
                exhausted = true
                if (options.tolerant && decodedAny) return null
                throw CorruptStreamException(failure.message ?: "a damaged Speex packet", packet.startOffset)
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
            decoded.copyInto(out, 0, from * channels, (from + count) * channels)
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
     * Moves to [frameIndex] by restarting the decoder [PRE_ROLL_FRAMES] Speex
     * frames before it. Speex carries its filters and its excitation history
     * from frame to frame, so the samples after a seek converge on those of a
     * continuous decode rather than equalling them; a seek to the start is
     * exact.
     */
    override fun seek(frameIndex: Long): Long {
        check(!closed) { "this decoder is closed" }
        require(frameIndex >= 0) { "frameIndex must not be negative, was $frameIndex" }
        val total = durationFrames
        val target = if (total != null) frameIndex.coerceAtMost(total) else frameIndex
        exhausted = false
        decoder.reset()
        positionFrames = target

        // The decoded position of the target, and the granule position the run-up starts from.
        val decodedTarget = target + startTrim
        val runUp = decodedTarget - granuleToDecoded - PRE_ROLL_FRAMES.toLong() * decoder.frameSize
        val landing = if (runUp <= 0) {
            null
        } else {
            OggSeek.pageAtOrBefore(
                ogg = ogg,
                serialNumber = stream.serialNumber,
                from = parsed.audioStartOffset,
                to = parsed.streamEndOffset,
                target = runUp,
                options = options,
                requireFreshStart = true,
            )
        }
        if (landing == null || landing.pageOffset <= parsed.audioStartOffset) {
            packets = ogg.packets(stream.serialNumber, parsed.audioStartOffset, parsed.streamEndOffset)
            pendingDiscard = decodedTarget
            return target
        }
        packets = ogg.packets(stream.serialNumber, landing.pageOffset, parsed.streamEndOffset)
        pendingDiscard = decodedTarget - (landing.startFrame + granuleToDecoded)
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

    private fun bitrateOf(): Int? {
        val total = durationFrames ?: return null
        val encodedBytes = (parsed.streamEndOffset - parsed.audioStartOffset).coerceAtLeast(0L)
        if (total <= 0 || encodedBytes <= 0) return null
        return (encodedBytes.toDouble() * 8.0 * stream.sampleRate / total)
            .coerceIn(0.0, Int.MAX_VALUE.toDouble())
            .toInt()
    }

    private companion object {
        fun u32(bytes: ByteArray, at: Int): Long =
            (bytes[at].toLong() and 0xFF) or ((bytes[at + 1].toLong() and 0xFF) shl 8) or
                ((bytes[at + 2].toLong() and 0xFF) shl 16) or ((bytes[at + 3].toLong() and 0xFF) shl 24)

        /** Frames decoded and thrown away before a seek target. */
        const val PRE_ROLL_FRAMES = 25
    }
}

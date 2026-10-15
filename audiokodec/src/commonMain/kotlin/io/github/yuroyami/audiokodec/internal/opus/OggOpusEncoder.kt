package io.github.yuroyami.audiokodec.internal.opus

import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioSink
import io.github.yuroyami.audiokodec.BitrateMode
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.PcmSource
import io.github.yuroyami.audiokodec.Resampler
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.ogg.OggPacketWriter
import io.github.yuroyami.audiokodec.internal.tag.VorbisComment
import io.github.yuroyami.audiokodec.readChecked
import io.github.yuroyami.audiokodec.tag.TagBundle

/**
 * Writes an Ogg Opus file, as RFC 7845 lays it out, from a [PcmSource].
 *
 * The identification header comes first on a page of its own, then the
 * comment header, then the audio, whose pages count 48 kHz samples in their
 * granule positions. The decoder's output trails the input by
 * [OpusEncoder.LOOKAHEAD] samples, which the header states as the pre-skip;
 * the encoder pads the input with silence until the last frame covers the
 * whole input plus that delay, and the last page's granule position marks
 * where the input ended. A player that applies both gets back exactly as
 * many samples as went in, aligned with them.
 *
 * Opus always codes at 48 kHz. Input at another rate goes through the
 * library's [Resampler] first, so the decoded length is the input's at 48
 * kHz, and the header records the original rate for players that convert
 * back.
 *
 * The source is read a block at a time and the pages written as they fill,
 * so memory does not grow with the length and the sink is never patched.
 */
internal class OggOpusEncoder(
    private val format: AudioFormat,
    private val bitrate: Int,
    private val mode: BitrateMode,
    private val frameSize: Int,
) {
    init {
        val channels = format.channels
        if (channels > 2) {
            throw UnsupportedFeatureException(
                "Opus encoding of $channels channels",
                "AudioKodec encodes mono and stereo Opus; more channels need the multistream mapping, " +
                    "which it does not write yet. Mix down to stereo first, or choose FLAC",
            )
        }
        require(format.channelLayout == ChannelLayout.canonical(channels)) {
            "an Opus stream of $channels channels is ${ChannelLayout.canonical(channels)}, " +
                "and cannot carry layout ${format.channelLayout}"
        }
    }

    fun encode(pcm: PcmSource, tags: TagBundle?, sink: AudioSink) {
        val channels = format.channels
        // The vendor string names the encoder, so a source's own is not kept.
        val comment = VorbisComment.build(
            tags ?: TagBundle.EMPTY,
            preservedVendor = VorbisComment.DEFAULT_VENDOR.encodeToByteArray(),
        )
        val pages = OggPacketWriter(sink, SERIAL)
        val head = identificationHeader(channels, format.sampleRate)
        pages.writePacket(head, 0, head.size, 0)
        pages.flushPage()
        val tagPacket = ByteArray(8 + comment.size)
        "OpusTags".forEachIndexed { i, c -> tagPacket[i] = c.code.toByte() }
        comment.copyInto(tagPacket, 8)
        pages.writePacket(tagPacket, 0, tagPacket.size, 0)
        pages.flushPage()

        val encoder = OpusEncoder(
            channels, bitrate, vbr = mode != BitrateMode.CONSTANT, frameSize = frameSize,
            constrainedVbr = mode == BitrateMode.CONSTRAINED_VARIABLE,
        )
        val frame = FloatArray(frameSize * channels)
        var filled = 0
        val packet = ByteArray(OpusEncoder.MAX_PACKET)
        var packets = 0L
        var samples48 = 0L

        fun emit(granule: Long) {
            val length = encoder.encode(frame, 0, packet)
            // Audio packets never straddle pages, and a page holds about a
            // second, so a player can start anywhere without much to discard.
            if (pages.packetsOnPage >= PACKETS_PER_PAGE * 960 / frameSize ||
                pages.segmentsFor(length) > pages.freeSegments
            ) {
                pages.flushPage()
            }
            pages.writePacket(packet, 0, length, granule)
            packets++
        }

        fun push(samples: FloatArray, count: Int) {
            var at = 0
            while (at < count) {
                val take = minOf(count - at, frameSize - filled)
                samples.copyInto(frame, filled * channels, at * channels, (at + take) * channels)
                filled += take
                at += take
                samples48 += take
                if (filled == frameSize) {
                    // A frame completed while input continues is never the last.
                    emit((packets + 1) * frameSize)
                    filled = 0
                }
            }
        }

        val resampler = if (format.sampleRate == RATE) null else Resampler(format.sampleRate, RATE, channels)
        // A low input rate reads smaller blocks, so a block never converts to
        // much more than a full one at 48 kHz.
        val blockFrames = if (resampler == null) BLOCK else (BLOCK.toLong() * format.sampleRate / RATE).toInt().coerceIn(1, BLOCK)
        val block = FloatArray(blockFrames * channels)
        val resampled = if (resampler != null) FloatArray(resampler.maxOutputFrames(blockFrames) * channels) else block
        while (true) {
            val got = pcm.readChecked(block, 0, blockFrames)
            if (got == 0) break
            if (resampler != null) {
                push(resampled, resampler.process(block, 0, got, resampled, 0))
            } else {
                push(block, got)
            }
        }
        if (resampler != null) {
            val tail = FloatArray(resampler.pendingOutputFrames * channels)
            push(tail, resampler.flush(tail))
        }

        // Silence until the frames cover the input and the encoder's delay.
        val end = samples48 + OpusEncoder.LOOKAHEAD
        while (packets * frameSize < end) {
            frame.fill(0f, filled * channels, frameSize * channels)
            filled = 0
            val granule = minOf((packets + 1) * frameSize, end)
            emit(granule)
        }
        pages.finish()
    }

    /** `OpusHead`: version 1, the channels, the pre-skip, the input rate, no gain, mapping family 0. */
    private fun identificationHeader(channels: Int, inputRate: Int): ByteArray {
        val head = ByteArray(19)
        "OpusHead".forEachIndexed { i, c -> head[i] = c.code.toByte() }
        head[8] = 1
        head[9] = channels.toByte()
        head[10] = OpusEncoder.LOOKAHEAD.toByte()
        head[11] = (OpusEncoder.LOOKAHEAD shr 8).toByte()
        for (i in 0 until 4) head[12 + i] = (inputRate ushr (8 * i)).toByte()
        head[16] = 0
        head[17] = 0
        head[18] = 0
        return head
    }

    private companion object {
        const val RATE = 48_000
        const val BLOCK = 4_096
        const val PACKETS_PER_PAGE = 50

        /**
         * The stream's serial number. RFC 3533 asks for one unique within the
         * file, which a file of one stream has whatever the value, and a
         * fixed one makes the output the same every time.
         */
        const val SERIAL = 0x4F707573
    }
}

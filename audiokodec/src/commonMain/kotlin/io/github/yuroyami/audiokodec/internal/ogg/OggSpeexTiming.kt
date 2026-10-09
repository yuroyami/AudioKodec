package io.github.yuroyami.audiokodec.internal.ogg

import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.internal.speex.SpeexPacketDecoder

/**
 * Where a Speex stream's audio starts and how long it is, as speexdec has it.
 *
 * speexdec works page by page. On a page whose packets decode to more
 * samples than its granule position advanced, it drops the difference, plus
 * the decoder's own lookahead, from the start of the page's first packet;
 * speexenc's first audio page is such a page, by the encoder's lookahead. On
 * the last page it ends the last packet where the granule position says,
 * plus the decoder's lookahead. Followed through, the output of a stream
 * whose first audio page is not its last is the final granule position long,
 * and starts after both lookaheads; a stream of one audio page drops nothing
 * at the start and is the final granule position plus the decoder's
 * lookahead long. Either way the output is cut short where the frames run
 * out, which is where speexenc's frames, which cover only the encoder's
 * lookahead, can leave up to the decoder's lookahead missing.
 */
internal object OggSpeexTiming {

    class Timing(
        /** Decoded samples dropped from the start. */
        val startTrim: Long,
        /** Decoded samples before a page's end, less its granule position. */
        val granuleToDecoded: Long,
        /** The samples speexdec writes. */
        val duration: Long,
    )

    /** speexdec's timing, or null when the header is not one this decoder takes. */
    fun of(
        ogg: OggReader,
        stream: OggAnalyser.Stream,
        audioStart: Long,
        streamEnd: Long,
        lastGranule: Long,
        options: DecodeOptions,
    ): Timing? {
        val head = stream.identification
        val mode = u32(head, 40)
        if (mode !in 0L..2L || u32(head, 28) > 1 || u32(head, 44) != 4L) return null
        val frames = maxOf(u32(head, 64).toInt(), 1)
        val decoder = SpeexPacketDecoder(mode.toInt(), if (stream.channels == 1) 1 else 2, frames)
        val la = decoder.lookahead.toLong()

        val first = ogg.pageAt(audioStart) ?: return null
        if (first.serialNumber != stream.serialNumber) return null
        val offset = if (first.granulePosition > 0) first.segments.count { it < 255 }.toLong() * decoder.maxSamples - first.granulePosition else 0L
        val single = first.isLast

        // Every packet but the last holds its frames; the last may end early with a terminator.
        var count = 0L
        var lastPacket: ByteArray? = null
        val packets = ogg.packets(stream.serialNumber, audioStart, streamEnd)
        while (true) {
            val packet = packets.next() ?: break
            count++
            lastPacket = packet.bytes.copyOf(packet.length)
        }
        val lastFrames = lastPacket?.let {
            try {
                decoder.countSamples(it)
            } catch (_: AudioKodecException) {
                0
            }
        } ?: 0
        val decoded = if (count == 0L) 0L else (count - 1) * decoder.maxSamples + lastFrames

        val trim = if (!single && offset > 0) offset + la else 0L
        val available = (decoded - trim).coerceAtLeast(0L)
        val duration = when {
            lastGranule <= 0 -> available
            single -> minOf(lastGranule + la, available)
            else -> minOf(lastGranule + la + offset - trim, available)
        }
        return Timing(trim, offset, duration.coerceAtLeast(0L))
    }

    private fun u32(bytes: ByteArray, at: Int): Long =
        (bytes[at].toLong() and 0xFF) or ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or ((bytes[at + 3].toLong() and 0xFF) shl 24)
}

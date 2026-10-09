/*
 * Translated from libspeex 1.2.1 and speexdec, which are Copyright 2002-2008
 * Xiph.org Foundation, Jean-Marc Valin and others and licensed under the BSD
 * 3-Clause licence, reproduced in NOTICE. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.speex

import io.github.yuroyami.audiokodec.CorruptStreamException

/**
 * Decodes Speex packets the way speexdec does: a decoder for the header's
 * mode with the perceptual enhancer on, the in-band stereo request handled
 * when the stream has two channels, and up to [framesPerPacket] frames from
 * each packet, stopping early where the packet says it holds no more.
 */
internal class SpeexPacketDecoder(private val mode: Int, val channels: Int, val framesPerPacket: Int) {
    private val narrowband: SpeexNarrowband
    private val decoder: SpeexLayer

    init {
        require(mode in 0..2 && channels in 1..2 && framesPerPacket >= 1)
        narrowband = SpeexNarrowband(wideband = mode > 0)
        decoder = when (mode) {
            0 -> narrowband
            1 -> SpeexSubband(narrowband, ultra = false)
            else -> SpeexSubband(SpeexSubband(narrowband, ultra = false), ultra = true)
        }
    }

    private val stereo = if (channels == 2) SpeexStereo().also { narrowband.stereo = it } else null
    private val bits = SpeexBits()

    /** Samples per channel in one frame: 160, 320 or 640. */
    val frameSize: Int = decoder.frameSize

    /** `SPEEX_GET_LOOKAHEAD`: the decoder's own delay. */
    val lookahead: Int = decoder.lookahead

    /** The most samples per channel one packet decodes to. */
    val maxSamples: Int = frameSize * framesPerPacket

    /** One frame, on the reference's scale, with room to spread it to stereo. */
    private val frame = FloatArray(frameSize * 2)

    fun reset() {
        decoder.reset()
        stereo?.reset()
    }

    /**
     * Decodes one packet into [out], interleaved and scaled to full scale 1,
     * and returns the samples per channel. Where the reference stops on a
     * corrupted frame or a read past the end of the packet, this throws.
     */
    fun decode(packet: ByteArray, offset: Int, length: Int, out: FloatArray): Int {
        bits.readFrom(packet, offset, length)
        var written = 0
        for (j in 0 until framesPerPacket) {
            val ret = decoder.decode(bits, frame, 0)
            if (ret == -1) break
            if (ret == -2) throw CorruptStreamException("a Speex frame states a mode that does not exist")
            if (bits.remaining < 0) throw CorruptStreamException("a Speex frame reads past the end of its packet")
            stereo?.decode(frame, 0, frameSize)
            val count = frameSize * channels
            for (i in 0 until count) out[written * channels + i] = frame[i] * SCALE
            written += frameSize
        }
        return written
    }

    /**
     * The samples per channel [packet] decodes to, read with a fresh decoder:
     * where each frame ends depends only on the bits, not on the decoder's
     * state, so this needs no history.
     */
    fun countSamples(packet: ByteArray): Int {
        val counter = SpeexPacketDecoder(mode, 1, framesPerPacket)
        counter.bits.readFrom(packet, 0, packet.size)
        var frames = 0
        for (j in 0 until framesPerPacket) {
            val ret = counter.decoder.decode(counter.bits, counter.frame, 0)
            if (ret != 0 || counter.bits.remaining < 0) break
            frames++
        }
        return frames * frameSize
    }

    private companion object {
        /** Speex decodes to the scale of 16-bit samples. */
        const val SCALE = 1f / 32768f
    }
}

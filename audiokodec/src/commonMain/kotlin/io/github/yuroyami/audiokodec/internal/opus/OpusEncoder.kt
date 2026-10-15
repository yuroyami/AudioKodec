package io.github.yuroyami.audiokodec.internal.opus

import io.github.yuroyami.audiokodec.internal.opus.celt.CeltEncoder

/**
 * Encodes Opus packets in the CELT-only mode, fullband, one frame each.
 *
 * This is the Opus layer libopus wraps around its CELT encoder when the
 * application is `OPUS_APPLICATION_RESTRICTED_LOWDELAY`: it removes DC from
 * the input, tells CELT the rate, and puts the table-of-contents byte in
 * front of what CELT writes. With variable bit rate CELT is given the target
 * and room for the largest packet; with constant bit rate it is given
 * exactly the bytes the rate allows each frame, so every packet is the same
 * size.
 *
 * Translated from the CELT-only paths of libopus `src/opus_encoder.c`, float
 * build (see `reference/REFERENCES.md`). The speech and hybrid modes, the
 * tonality analysis that steers libopus's mode and bandwidth choices, and
 * the stereo narrowing it applies below about 32 kbit/s are not part of it:
 * the bandwidth is always fullband and stereo always codes both channels.
 */
internal class OpusEncoder(
    /** Channels, one or two. */
    val channels: Int,
    /** The target bit rate in bits per second. */
    private val bitrate: Int,
    /** Whether packet sizes vary with the signal around [bitrate]. */
    private val vbr: Boolean,
    /** Samples per channel in a frame at 48 kHz: 120, 240, 480 or 960. */
    val frameSize: Int = 960,
    /** Whether variable bit rate stays within a buffer of one frame. */
    constrainedVbr: Boolean = true,
) {
    private val celt = CeltEncoder(channels, complexity = 10)
    private val hpMem = FloatArray(4)
    private val buffer = FloatArray(frameSize * channels)

    /** The range coder's final state for the last packet, which the decoder reports too. */
    val finalRange: Long get() = celt.finalRange

    /** The bytes every packet takes at a constant rate, or 0 with a variable one. */
    private val cbrBytes: Int

    init {
        require(channels == 1 || channels == 2) { "an Opus stream codes one or two channels, not $channels" }
        require(frameSize == 120 || frameSize == 240 || frameSize == 480 || frameSize == 960) {
            "CELT frames are 120, 240, 480 or 960 samples, not $frameSize"
        }
        celt.vbr = vbr
        celt.constrainedVbr = constrainedVbr
        celt.lsbDepth = 24
        if (vbr) {
            celt.bitrate = minOf(bitrate, 260_000 * channels)
            cbrBytes = 0
        } else {
            // Twelve times the frame rate keeps the division exact.
            val frameRate12 = 12 * 48_000 / frameSize
            cbrBytes = minOf((12 * bitrate / 8 + frameRate12 / 2) / frameRate12, MAX_PACKET)
            celt.bitrate = CeltEncoder.BITRATE_MAX
        }
    }

    /**
     * The table-of-contents byte: CELT-only, fullband, this frame size, one
     * frame in the packet, and whether it is stereo.
     */
    val toc: Int = run {
        var period = 0
        var frameRate = 48_000 / frameSize
        while (frameRate < 400) {
            frameRate = frameRate shl 1
            period++
        }
        // Fullband is three steps above mediumband, where CELT's bandwidths start.
        0x80 or (3 shl 5) or (period shl 3) or (if (channels == 2) 1 shl 2 else 0)
    }

    /**
     * Codes one frame of [frameSize] samples per channel from [pcm] at
     * [pcmOffset], interleaved and scaled to plus or minus one, into [out],
     * which must hold [MAX_PACKET] bytes. Returns the packet's length.
     */
    fun encode(pcm: FloatArray, pcmOffset: Int, out: ByteArray): Int {
        dcReject(pcm, pcmOffset)
        // Non-finite or absurd input would turn into NaNs further down, so
        // such a frame is coded as silence and the filter's memory forgotten.
        val energy = innerProduct(buffer, buffer.size)
        if (!(energy < 1e9f)) {
            buffer.fill(0f)
            hpMem.fill(0f)
        }
        val maxBytes = if (vbr) MAX_PACKET else maxOf(1, cbrBytes)
        val payload = ByteArray(maxBytes - 1)
        val length = celt.encode(buffer, frameSize, payload, maxBytes - 1)
        out[0] = toc.toByte()
        payload.copyInto(out, 1, 0, length)
        return length + 1
    }

    /** `dc_reject`: a 3 Hz high-pass, which is all libopus does to music before CELT. */
    private fun dcReject(pcm: FloatArray, offset: Int) {
        val coef = 6.3f * CUTOFF_HZ / 48_000
        val coef2 = 1 - coef
        if (channels == 2) {
            var m0 = hpMem[0]
            var m2 = hpMem[2]
            for (i in 0 until frameSize) {
                val x0 = pcm[offset + 2 * i]
                val x1 = pcm[offset + 2 * i + 1]
                val out0 = x0 - m0
                val out1 = x1 - m2
                m0 = coef * x0 + VERY_SMALL + coef2 * m0
                m2 = coef * x1 + VERY_SMALL + coef2 * m2
                buffer[2 * i] = out0
                buffer[2 * i + 1] = out1
            }
            hpMem[0] = m0
            hpMem[2] = m2
        } else {
            var m0 = hpMem[0]
            for (i in 0 until frameSize) {
                val x = pcm[offset + i]
                val y = x - m0
                m0 = coef * x + VERY_SMALL + coef2 * m0
                buffer[i] = y
            }
            hpMem[0] = m0
        }
    }

    private fun innerProduct(x: FloatArray, n: Int): Float {
        var sum = 0f
        for (i in 0 until n) sum += x[i] * x[i]
        return sum
    }

    companion object {
        /** The largest Opus packet of one frame, as libopus caps it. */
        const val MAX_PACKET: Int = 1276

        /**
         * Samples at 48 kHz the decoder's output lags the input by: CELT's
         * window overlap, 2.5 ms, which is the stream's pre-skip.
         */
        const val LOOKAHEAD: Int = 120

        private const val CUTOFF_HZ = 3
        private const val VERY_SMALL = 1e-30f
    }
}

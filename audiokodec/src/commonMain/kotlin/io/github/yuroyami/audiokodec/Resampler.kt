package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.dsp.SpeexResampler

/** How [Resampler] converts between sample rates. */
public enum class ResampleQuality {
    /**
     * A windowed sinc filter, for audio that will be listened to: speexdsp's
     * resampler at its highest quality, 10. A 1 kHz tone converted between
     * the common rates keeps its distortion and noise 134.8 dB or more below
     * it; see [Resampler] for how that was measured.
     */
    SINC,

    /**
     * Straight-line interpolation between neighbouring samples, for speed
     * when quality matters less, as in drawing a preview. It filters
     * nothing, so lowering the rate folds everything above the new Nyquist
     * frequency back into the band, and raising it leaves images of the
     * signal above the old one.
     */
    LINEAR,
}

/**
 * Converts interleaved float PCM from one sample rate to another, a block at
 * a time.
 *
 * Feed blocks of any size to [process], then call [flush] once at the end of
 * the stream for the samples still held back. The output is aligned with the
 * input, with no delay: output frame k is the signal at input time k times
 * `inputRate / outputRate`, and the stream converts to exactly
 * `ceil(inputFrames * outputRate / inputRate)` frames in all. Outside the
 * stream the signal is taken as zero.
 *
 * Nothing is allocated after construction: the filter is computed once and
 * every block passes through a fixed buffer, so a caller who reuses its
 * output array, sized with [maxOutputFrames], converts a whole file without
 * allocating, as `DecodeOptions.reuseBuffers` decodes one.
 *
 * With [ResampleQuality.SINC] the samples are exactly those of speexdsp
 * 1.2.1's float build at quality 10, whose filter is a Kaiser-windowed sinc
 * of 256 taps when raising the rate, and proportionally more when lowering
 * it, cut off at 97.5% of the lower Nyquist frequency. Measured on a 1 kHz
 * sine at -6 dBFS, with a sine of its frequency fitted to the result and
 * whatever the fit leaves counted as distortion and noise, every conversion
 * between 8, 16, 22.05, 32, 44.1, 48, 88.2 and 96 kHz in the oracle suite
 * measured between -134.8 dB (16 to 44.1 kHz) and -169.3 dB (48 to 8 kHz),
 * and a tone at 40% of the lower rate -129.1 dB at worst (44.1 to 48 kHz).
 * ffmpeg's soxr resampler at 28 bits measured between -150.5 and -161.4 dB;
 * the two agree in phase, and in level within the 0.001 dB of speexdsp's
 * passband ripple.
 *
 * Equal rates pass the samples through unchanged.
 */
public class Resampler internal constructor(
    public val inputRate: Int,
    public val outputRate: Int,
    public val channels: Int,
    public val quality: ResampleQuality,
    /** speexdsp's quality level for [ResampleQuality.SINC], 0 to 10. */
    sincQuality: Int,
) {
    /** A resampler from [inputRate] to [outputRate] for [channels] interleaved channels. */
    public constructor(
        inputRate: Int,
        outputRate: Int,
        channels: Int,
        quality: ResampleQuality = ResampleQuality.SINC,
    ) : this(inputRate, outputRate, channels, quality, SINC_QUALITY)

    private val speex: SpeexResampler?
    private val num: Int
    private val den: Int
    private val taps: Int
    private val intAdvance: Int
    private val fracAdvance: Int

    /** History of [taps] - 1 frames, then up to [CHUNK] new ones, interleaved. */
    private val buffer: FloatArray
    private var filled = 0
    private val state = IntArray(2)
    private val channelState = IntArray(2)
    private var inputFrames = 0L
    private var outputFrames = 0L
    private var flushed = false

    init {
        require(inputRate in 1..MAX_RATE) { "inputRate must be in 1..$MAX_RATE, was $inputRate" }
        require(outputRate in 1..MAX_RATE) { "outputRate must be in 1..$MAX_RATE, was $outputRate" }
        require(channels in 1..MAX_CHANNELS) { "channels must be in 1..$MAX_CHANNELS, was $channels" }
        speex = if (quality == ResampleQuality.SINC && inputRate != outputRate) {
            SpeexResampler(inputRate, outputRate, sincQuality)
        } else {
            null
        }
        if (speex != null) {
            num = speex.numRate
            den = speex.denRate
            taps = speex.filterLength
        } else {
            val g = gcd(inputRate, outputRate)
            num = inputRate / g
            den = outputRate / g
            taps = 2
        }
        intAdvance = num / den
        fracAdvance = num % den
        buffer = FloatArray((taps - 1 + CHUNK) * channels)
        reset()
    }

    /**
     * The most frames one call to [process] with [inputFrames] frames can
     * write.
     */
    public fun maxOutputFrames(inputFrames: Int): Int {
        require(inputFrames >= 0) { "inputFrames must not be negative, was $inputFrames" }
        val frames = (inputFrames.toLong() * den + num - 1) / num + 1
        require(frames * channels <= Int.MAX_VALUE) { "$inputFrames frames would convert to more than an array holds" }
        return frames.toInt()
    }

    /** How many frames [flush] will write, given the input so far. */
    public val pendingOutputFrames: Int
        get() = (totalOutputFrames(inputFrames) - outputFrames).toInt()

    /**
     * Converts [frameCount] frames of [input], starting at frame
     * [inputOffset], into [output] from frame [outputOffset], and returns how
     * many frames it wrote.
     *
     * [output] must have room for [maxOutputFrames] of [frameCount]. The
     * output trails the input by half the filter, so the last frames of a
     * stream come out of [flush].
     */
    public fun process(
        input: FloatArray,
        inputOffset: Int,
        frameCount: Int,
        output: FloatArray,
        outputOffset: Int,
    ): Int {
        check(!flushed) { "this resampler was flushed; reset it to start another stream" }
        require(inputOffset >= 0 && frameCount >= 0) { "inputOffset and frameCount must not be negative" }
        require(inputOffset.toLong() + frameCount <= input.size.toLong() / channels) {
            "input holds ${input.size / channels} frames, short of ${inputOffset.toLong() + frameCount}"
        }
        val room = maxOutputFrames(frameCount)
        require(outputOffset >= 0 && outputOffset.toLong() + room <= output.size.toLong() / channels) {
            "output must have room for $room frames from frame $outputOffset"
        }
        if (speex == null && inputRate == outputRate) {
            input.copyInto(output, outputOffset * channels, inputOffset * channels, (inputOffset + frameCount) * channels)
            inputFrames += frameCount
            outputFrames += frameCount
            return frameCount
        }
        var written = 0
        var from = inputOffset
        var remaining = frameCount
        while (remaining > 0) {
            val take = minOf(remaining, CHUNK - filled)
            input.copyInto(buffer, (taps - 1 + filled) * channels, from * channels, (from + take) * channels)
            filled += take
            from += take
            remaining -= take
            inputFrames += take
            written += run(output, outputOffset + written, Int.MAX_VALUE)
        }
        return written
    }

    /**
     * Ends the stream: writes the [pendingOutputFrames] frames still held
     * back into [output] from frame [outputOffset], and returns how many.
     */
    public fun flush(output: FloatArray, outputOffset: Int = 0): Int {
        check(!flushed) { "this resampler was already flushed" }
        val pending = pendingOutputFrames
        require(outputOffset >= 0 && outputOffset.toLong() + pending <= output.size.toLong() / channels) {
            "output must have room for $pending frames from frame $outputOffset"
        }
        flushed = true
        var written = 0
        // Zeros past the end, as many as the filter reaches ahead, and no more frames than the stream converts to.
        var zeros = taps / 2
        while (written < pending) {
            val take = minOf(zeros, CHUNK - filled)
            buffer.fill(0f, (taps - 1 + filled) * channels, (taps - 1 + filled + take) * channels)
            filled += take
            zeros -= take
            written += run(output, outputOffset + written, pending - written)
            if (zeros == 0) break
        }
        check(written == pending) { "the resampler wrote $written of its $pending last frames" }
        return written
    }

    /** Forgets the stream so far, ready for another at the same rates. */
    public fun reset() {
        buffer.fill(0f)
        filled = 0
        // The first output's filter is centred on the first input frame.
        state[0] = taps / 2
        state[1] = 0
        inputFrames = 0
        outputFrames = 0
        flushed = false
    }

    /** Produces every output the buffered input allows, up to [limit], then drops the input no output needs again. */
    private fun run(output: FloatArray, outputOffset: Int, limit: Int): Int {
        var produced = 0
        for (c in 0 until channels) {
            state.copyInto(channelState)
            produced = if (speex != null) {
                speex.run(buffer, c, filled, channels, channelState, output, outputOffset * channels + c, channels, limit)
            } else {
                linear(c, output, outputOffset * channels + c, limit)
            }
        }
        channelState.copyInto(state)
        outputFrames += produced
        val consumed = minOf(state[0], filled)
        state[0] -= consumed
        buffer.copyInto(buffer, 0, consumed * channels, (taps - 1 + filled) * channels)
        filled -= consumed
        return produced
    }

    private fun linear(c: Int, out: FloatArray, outAt: Int, limit: Int): Int {
        var last = channelState[0]
        var frac = channelState[1]
        var produced = 0
        var o = outAt
        while (last < filled && produced < limit) {
            val a = buffer[last * channels + c]
            val b = buffer[(last + 1) * channels + c]
            out[o] = (a + (b - a) * (frac.toDouble() / den)).toFloat()
            o += channels
            produced++
            last += intAdvance
            frac += fracAdvance
            if (frac >= den) {
                frac -= den
                last++
            }
        }
        channelState[0] = last
        channelState[1] = frac
        return produced
    }

    private fun totalOutputFrames(input: Long): Long = (input * den + num - 1) / num

    internal companion object {
        private const val CHUNK = 1024
        private const val SINC_QUALITY = 10
        private const val MAX_RATE = 1 shl 24
        private const val MAX_CHANNELS = 256

        /**
         * An oversampler by a whole [factor], for finding peaks between
         * samples: speexdsp's quality 7, whose 128 taps find the same peaks
         * as quality 10's 256 to within 0.00003 dB at half the cost.
         */
        fun oversampler(rate: Int, factor: Int, channels: Int): Resampler =
            Resampler(rate, rate * factor, channels, ResampleQuality.SINC, 7)

        private fun gcd(a: Int, b: Int): Int {
            var x = a
            var y = b
            while (y != 0) {
                val t = x
                x = y
                y = t % y
            }
            return x
        }
    }
}

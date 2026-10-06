package io.github.yuroyami.audiokodec.internal.mpeg

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Turns a granule's spectrum back into sound.
 *
 * Everything after requantisation happens here, in the order the format
 * requires: the two channels are separated, a short granule's values are put
 * back into frequency order, the aliasing the encoder introduced on purpose is
 * cancelled, each subband is transformed back into time, and thirty-two
 * subbands are then summed into thirty-two samples by the synthesis filterbank.
 *
 * The filterbank is the expensive part and the reason MPEG audio decoders are
 * written the way they are: it is a five-hundred-and-twelve-tap window over a
 * running history, evaluated once per output sample.
 *
 * Written from ISO/IEC 11172-3.
 */
internal class Mp3Synthesis(private val channels: Int) {

    /** The tail of each channel's previous granule, waiting to be overlapped. */
    private val overlap = Array(channels) { DoubleArray(576) }

    /** The filterbank, which is the same one the other two layers use. */
    private val filter = PolyphaseFilter(channels)

    private val scratch = DoubleArray(576)
    private val block = DoubleArray(36)

    fun reset() {
        for (channel in 0 until channels) overlap[channel].fill(0.0)
        filter.reset()
    }

    /**
     * Applies mid-side and intensity stereo to a pair of granules.
     *
     * Joint stereo codes the sum and difference of the two channels rather than
     * the channels themselves, because the two are usually alike. Intensity
     * stereo goes further: above the point where the right channel stopped
     * carrying anything of its own, only one channel is coded, and the right
     * channel's scalefactors become positions saying how to divide it between
     * the speakers.
     *
     * The two are frequency-disjoint, never layered. A band gets one or the
     * other, and a band whose position is the illegal one falls back to
     * mid-side.
     *
     * This runs before the reordering below, so a short granule's bands are
     * still contiguous here, which is what makes finding a band's lines a
     * matter of arithmetic rather than a search.
     */
    fun applyStereo(
        left: Mp3Granule,
        right: Mp3Granule,
        header: MpegFrameHeader,
        sideInfo: Mp3SideInfo.Granule,
    ) {
        if (header.channelMode != MpegFrameHeader.MODE_JOINT) return
        val midSide = header.modeExtension and 0x2 != 0
        val intensity = header.modeExtension and 0x1 != 0
        if (!midSide && !intensity) return

        if (!intensity) {
            midSideRange(left, right, 0, 576)
            return
        }

        // Where the right channel ran out is where intensity begins, and it is
        // rounded up: a band that straddles the point is not intensity coded.
        val zeroStart = right.nonZero
        val lsf = header.version != 1

        if (!sideInfo.isShort) {
            val bands = Mp3Tables.longBands(header.sampleRate)
            for (band in 0 until 22) {
                // Only 21 positions are transmitted, so the top band has none
                // and takes the same path as an illegal one.
                val position = if (band < 21) right.longScalefactors[band] else ILLEGAL
                stereoBand(
                    left, right, bands[band], bands[band + 1].coerceAtMost(576),
                    intensity = bands[band] >= zeroStart,
                    position = position, lsf = lsf, scale = right.intensityScale,
                    midSide = midSide,
                )
            }
            return
        }

        val shortBands = Mp3Tables.shortBands(header.sampleRate)
        if (sideInfo.isMixed) {
            val longBands = Mp3Tables.longBands(header.sampleRate)
            for (band in 0 until Mp3Tables.mixedLongBands(header.sampleRate)) {
                stereoBand(
                    left, right, longBands[band], longBands[band + 1].coerceAtMost(576),
                    intensity = longBands[band] >= zeroStart,
                    position = right.longScalefactors[band], lsf = lsf,
                    scale = right.intensityScale, midSide = midSide,
                )
            }
        }
        val first = if (sideInfo.isMixed) Mp3Tables.MIXED_SHORT_START else 0
        for (band in first until 13) {
            val base = shortBands[band] * 3
            val width = shortBands[band + 1] - shortBands[band]
            for (window in 0 until 3) {
                val from = base + window * width
                val position = if (band < 12) right.shortScalefactors[band][window] else ILLEGAL
                stereoBand(
                    left, right, from, (from + width).coerceAtMost(576),
                    // The band as a whole is what is tested, but each window
                    // carries its own position and can decline separately.
                    intensity = base >= zeroStart,
                    position = position, lsf = lsf, scale = right.intensityScale,
                    midSide = midSide,
                )
            }
        }
    }

    /** Handles one band, or one window of one band, of a joint stereo pair. */
    private fun stereoBand(
        left: Mp3Granule,
        right: Mp3Granule,
        from: Int,
        to: Int,
        intensity: Boolean,
        position: Int,
        lsf: Boolean,
        scale: Int,
        midSide: Boolean,
    ) {
        if (from >= to) return
        if (intensity && gainsOf(position, lsf, scale)) {
            val gainL = gainLeft
            val gainR = gainRight
            for (i in from until to) {
                // Both outputs come from the value that was there before
                // either was written.
                val value = left.spectrum[i]
                left.spectrum[i] = value * gainL
                right.spectrum[i] = value * gainR
            }
            return
        }
        if (midSide) midSideRange(left, right, from, to)
    }

    private fun midSideRange(left: Mp3Granule, right: Mp3Granule, from: Int, to: Int) {
        val root = 1.0 / sqrt(2.0)
        for (i in from until to) {
            val mid = left.spectrum[i]
            val side = right.spectrum[i]
            left.spectrum[i] = (mid + side) * root
            right.spectrum[i] = (mid - side) * root
        }
    }

    private var gainLeft = 0.0
    private var gainRight = 0.0

    /**
     * Works out the two gains a position stands for, or reports that it stands
     * for nothing.
     *
     * The two versions of the format disagree about what a position means.
     * MPEG-1 reads it as an angle between the speakers, seven steps of a
     * tangent from hard right to hard left, with an eighth value meaning the
     * band was not intensity coded after all. MPEG-2 reads it as an alternating
     * ladder instead: odd positions turn the left channel down and even ones
     * turn the right down, by a factor that doubles in exponent every two
     * steps, with a choice of two step sizes.
     */
    private fun gainsOf(position: Int, lsf: Boolean, scale: Int): Boolean {
        if (position < 0) return false
        if (!lsf) {
            if (position >= 7) return false
            if (position == 6) {
                // The tangent is infinite here, so the limit is written in
                // rather than computed.
                gainLeft = 1.0
                gainRight = 0.0
                return true
            }
            val ratio = tan(position * PI / 12.0)
            gainLeft = ratio / (1.0 + ratio)
            gainRight = 1.0 / (1.0 + ratio)
            return true
        }
        if (position >= LSF_GAIN.size * 2) return false
        if (position == 0) {
            gainLeft = 1.0
            gainRight = 1.0
            return true
        }
        val step = ((position + 1) ushr 1) shl scale
        val gain = LSF_GAIN[step.coerceAtMost(LSF_GAIN.size - 1)]
        if (position and 1 != 0) {
            gainLeft = gain
            gainRight = 1.0
        } else {
            gainLeft = 1.0
            gainRight = gain
        }
        return true
    }

    /**
     * Puts a short granule's values back into frequency order.
     *
     * A short block is three windows in a row, and the encoder writes them one
     * scalefactor band at a time so that the three copies of a band sit
     * together. The transform wants them the other way round: each subband's
     * eighteen values as six for window zero, then six for window one, then six
     * for window two.
     */
    fun reorder(granule: Mp3Granule, sideInfo: Mp3SideInfo.Granule, header: MpegFrameHeader) {
        if (!sideInfo.isShort) return
        val bands = Mp3Tables.shortBands(header.sampleRate)
        val startBand = if (sideInfo.isMixed) 3 else 0

        granule.spectrum.copyInto(scratch)
        var source = bands[startBand] * 3
        for (band in startBand until 13) {
            for (window in 0 until 3) {
                for (line in bands[band] until bands[band + 1]) {
                    // Line n of a window belongs to subband n / 6, at position
                    // n % 6 of that window's six within it.
                    val target = (line / 6) * 18 + window * 6 + (line % 6)
                    granule.spectrum[target] = scratch[source]
                    source++
                }
            }
        }
    }

    /**
     * Cancels the aliasing the encoder's own filterbank introduced.
     *
     * The analysis filterbank's bands overlap, so each one carries a mirrored
     * copy of its neighbour. The encoder leaves it in and the decoder folds it
     * back out, eight sample pairs either side of every subband boundary.
     */
    fun reduceAlias(granule: Mp3Granule, sideInfo: Mp3SideInfo.Granule) {
        if (sideInfo.isShort && !sideInfo.isMixed) return
        // A mixed block is long in its lowest two subbands only, so only the
        // boundary between those two is aliased.
        val boundaries = if (sideInfo.isMixed) 1 else 31
        val spectrum = granule.spectrum
        for (boundary in 0 until boundaries) {
            val centre = 18 * (boundary + 1)
            for (i in 0 until 8) {
                val lower = centre - 1 - i
                val upper = centre + i
                val a = spectrum[lower]
                val b = spectrum[upper]
                spectrum[lower] = a * Mp3Tables.ALIAS_CS[i] - b * Mp3Tables.ALIAS_CA[i]
                spectrum[upper] = b * Mp3Tables.ALIAS_CS[i] + a * Mp3Tables.ALIAS_CA[i]
            }
        }
    }

    /**
     * Transforms every subband back into time and overlaps it with the last.
     *
     * Each subband holds eighteen values that become thirty-six samples, of
     * which the first eighteen complete the previous granule and the last
     * eighteen wait for the next. A short block is three six-value transforms
     * staggered inside the same thirty-six.
     */
    fun inverseTransform(
        granule: Mp3Granule,
        sideInfo: Mp3SideInfo.Granule,
        channel: Int,
        into: DoubleArray,
    ) {
        val previous = overlap[channel]
        for (subband in 0 until 32) {
            val offset = subband * 18
            val short = sideInfo.isShort && !(sideInfo.isMixed && subband < 2)
            if (short) {
                shortTransform(granule.spectrum, offset, block)
            } else {
                val window = when {
                    !sideInfo.windowSwitching -> 0
                    sideInfo.blockType == 1 -> 1
                    sideInfo.blockType == 3 -> 3
                    // The lower subbands of a mixed block use the plain window.
                    else -> 0
                }
                longTransform(granule.spectrum, offset, window, block)
            }
            for (i in 0 until 18) {
                into[offset + i] = block[i] + previous[offset + i]
                previous[offset + i] = block[i + 18]
            }
            // Every other subband comes out of the analysis filterbank with its
            // spectrum flipped, and the format leaves it to the decoder to put
            // back.
            if (subband and 1 != 0) {
                for (i in 1 until 18 step 2) into[offset + i] = -into[offset + i]
            }
        }
    }

    private fun longTransform(spectrum: DoubleArray, offset: Int, window: Int, out: DoubleArray) {
        for (i in 0 until 36) {
            var sum = 0.0
            for (k in 0 until 18) {
                sum += spectrum[offset + k] * COS36[i][k]
            }
            out[i] = sum * WINDOWS[window][i]
        }
    }

    private fun shortTransform(spectrum: DoubleArray, offset: Int, out: DoubleArray) {
        out.fill(0.0)
        for (window in 0 until 3) {
            val from = offset + window * 6
            for (i in 0 until 12) {
                var sum = 0.0
                for (k in 0 until 6) {
                    sum += spectrum[from + k] * COS12[i][k]
                }
                out[6 + window * 6 + i] += sum * SHORT_WINDOW[i]
            }
        }
    }

    /**
     * Runs a granule's eighteen steps through the synthesis filterbank.
     *
     * The filterbank itself is the same one Layers I and II use and lives in
     * [PolyphaseFilter]. All that is different here is the layout: Layer III
     * holds a subband's eighteen values together, so one step reads across the
     * granule rather than along it.
     */
    fun synthesise(input: DoubleArray, channel: Int, out: FloatArray, outOffset: Int, stride: Int) {
        var write = outOffset
        for (step in 0 until 18) {
            filter.step(input, step, 18, channel, out, write, stride)
            write += 32 * stride
        }
    }

    private companion object {

        /** A position that names no gain, so the band falls back to mid-side. */
        const val ILLEGAL = -1

        /**
         * MPEG-2's intensity ladder, a quarter power of two per step.
         *
         * The two step sizes the format offers are the same ladder read at
         * every entry or at every second one, which is what shifting the index
         * by the scale bit does.
         */
        val LSF_GAIN = DoubleArray(33) { 2.0.pow(-it / 4.0) }

        /** The long transform's kernel, eighteen coefficients into thirty-six. */
        val COS36 = Array(36) { i ->
            DoubleArray(18) { k -> cos(PI / 72.0 * (2 * i + 1 + 18) * (2 * k + 1)) }
        }

        /** The short transform's kernel, six into twelve. */
        val COS12 = Array(12) { i ->
            DoubleArray(6) { k -> cos(PI / 24.0 * (2 * i + 1 + 6) * (2 * k + 1)) }
        }

        val SHORT_WINDOW = DoubleArray(12) { sin(PI / 12.0 * (it + 0.5)) }

        /**
         * The long block windows, indexed by block type.
         *
         * A block next to a short one narrows the slope on that side, in the
         * same way and for the same reason every lapped transform does. Index 2
         * is block type 2, which is the short block and never reaches here; it
         * is filled with the plain window so the array stays square.
         */
        val WINDOWS = Array(4) { type ->
            DoubleArray(36) { i ->
                when (type) {
                    1 -> when {
                        i < 18 -> sin(PI / 36.0 * (i + 0.5))
                        i < 24 -> 1.0
                        i < 30 -> sin(PI / 12.0 * (i - 18 + 0.5))
                        else -> 0.0
                    }
                    3 -> when {
                        i < 6 -> 0.0
                        i < 12 -> sin(PI / 12.0 * (i - 6 + 0.5))
                        i < 18 -> 1.0
                        else -> sin(PI / 36.0 * (i + 0.5))
                    }
                    else -> sin(PI / 36.0 * (i + 0.5))
                }
            }
        }
    }
}

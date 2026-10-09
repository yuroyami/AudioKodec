package io.github.yuroyami.audiokodec.internal.ac3

/**
 * The parametric bit allocation of ATSC A/52:2012 section 7.2.2: from a
 * channel's exponents and a handful of parameters, how many bits each of its
 * mantissas has. Encoder and decoder must agree on it exactly, so the standard
 * gives it in integers, and this follows it step for step.
 *
 * One instance serves any number of channels, one after another; it keeps
 * only scratch space.
 */
internal class Ac3BitAllocator(sampleRateCode: Int) {

    private val hearing = when (sampleRateCode) {
        0 -> Ac3Tables.HEARING_48000
        1 -> Ac3Tables.HEARING_44100
        else -> Ac3Tables.HEARING_32000
    }

    private val psd = IntArray(256)
    private val bandPsd = IntArray(50)
    private val excite = IntArray(50)
    private val mask = IntArray(50)

    /**
     * Writes into [bap] the pointers for the bins [start] until [end] of a
     * channel with [exponents].
     *
     * The five codes are those of the bit stream (`sdcycod`, `fdcycod`,
     * `sgaincod`, `dbpbcod`, `floorcod`) and [fastGainCode] is the channel's
     * `fgaincod`. [snrOffset] is the channel's `snroffset` of section
     * 7.2.2.1. [fastLeak] and [slowLeak] start the coupling channel's
     * excitation and are used by it alone. The delta bit allocation is
     * [deltaSegments] segments of [deltaOffsets], [deltaLengths] and
     * [deltaCodes], or none for zero. Their bands count from [deltaFirstBand]:
     * band 0 for a full bandwidth channel, and for the coupling channel the
     * first band it covers, which is how sections 5.4.3.51 and 7.2.2.6 read
     * together and how ffmpeg decodes it.
     */
    fun allocate(
        exponents: IntArray,
        start: Int,
        end: Int,
        bap: IntArray,
        slowDecayCode: Int,
        fastDecayCode: Int,
        slowGainCode: Int,
        dbPerBitCode: Int,
        floorCode: Int,
        fastGainCode: Int,
        snrOffset: Int,
        fastLeak: Int,
        slowLeak: Int,
        deltaSegments: Int,
        deltaOffsets: IntArray,
        deltaLengths: IntArray,
        deltaCodes: IntArray,
        deltaFirstBand: Int = 0,
    ) {
        val sdecay = Ac3Tables.SLOW_DECAY[slowDecayCode]
        val fdecay = Ac3Tables.FAST_DECAY[fastDecayCode]
        val sgain = Ac3Tables.SLOW_GAIN[slowGainCode]
        val dbknee = Ac3Tables.DB_PER_BIT[dbPerBitCode]
        val floor = Ac3Tables.FLOOR[floorCode]
        val fgain = Ac3Tables.FAST_GAIN[fastGainCode]

        // Exponents into a log power spectral density.
        for (bin in start until end) psd[bin] = 3072 - (exponents[bin] shl 7)

        // Integrated over each band by log-addition.
        var j = start
        var k = Ac3Tables.MASK[start]
        var lastbin: Int
        do {
            lastbin = minOf(Ac3Tables.BAND_START[k + 1], end)
            bandPsd[k] = psd[j]
            j++
            while (j < lastbin) {
                bandPsd[k] = logAdd(bandPsd[k], psd[j])
                j++
            }
            k++
        } while (end > lastbin)

        // The excitation function.
        val bndstrt = Ac3Tables.MASK[start]
        val bndend = Ac3Tables.MASK[end - 1] + 1
        var begin: Int
        var fastleak: Int
        var slowleak: Int
        if (bndstrt == 0) {
            var lowcomp = 0
            lowcomp = lowComp(lowcomp, bandPsd[0], bandPsd[1], 0)
            excite[0] = bandPsd[0] - fgain - lowcomp
            lowcomp = lowComp(lowcomp, bandPsd[1], bandPsd[2], 1)
            excite[1] = bandPsd[1] - fgain - lowcomp
            begin = 7
            fastleak = 0
            slowleak = 0
            for (bin in 2 until 7) {
                // The LFE channel ends at its seventh band, which has no neighbour to compare with.
                val lastLfeBand = bndend == 7 && bin == 6
                if (!lastLfeBand) lowcomp = lowComp(lowcomp, bandPsd[bin], bandPsd[bin + 1], bin)
                fastleak = bandPsd[bin] - fgain
                slowleak = bandPsd[bin] - sgain
                excite[bin] = fastleak - lowcomp
                if (!lastLfeBand && bandPsd[bin] <= bandPsd[bin + 1]) {
                    begin = bin + 1
                    break
                }
            }
            for (bin in begin until minOf(bndend, 22)) {
                val lastLfeBand = bndend == 7 && bin == 6
                if (!lastLfeBand) lowcomp = lowComp(lowcomp, bandPsd[bin], bandPsd[bin + 1], bin)
                fastleak = maxOf(fastleak - fdecay, bandPsd[bin] - fgain)
                slowleak = maxOf(slowleak - sdecay, bandPsd[bin] - sgain)
                excite[bin] = maxOf(fastleak - lowcomp, slowleak)
            }
            begin = 22
        } else {
            begin = bndstrt
            fastleak = fastLeak
            slowleak = slowLeak
        }
        for (bin in begin until bndend) {
            fastleak = maxOf(fastleak - fdecay, bandPsd[bin] - fgain)
            slowleak = maxOf(slowleak - sdecay, bandPsd[bin] - sgain)
            excite[bin] = maxOf(fastleak, slowleak)
        }

        // The masking curve.
        for (bin in bndstrt until bndend) {
            if (bandPsd[bin] < dbknee) excite[bin] += (dbknee - bandPsd[bin]) shr 2
            mask[bin] = maxOf(excite[bin], hearing[bin])
        }

        // Delta bit allocation: the encoder's adjustments to the curve, in steps of 6 dB.
        var band = deltaFirstBand
        for (seg in 0 until deltaSegments) {
            band += deltaOffsets[seg]
            val code = deltaCodes[seg]
            val delta = (if (code >= 4) code - 3 else code - 4) shl 7
            repeat(deltaLengths[seg]) {
                mask[band] += delta
                band++
            }
        }

        // The allocation itself.
        var i = start
        j = Ac3Tables.MASK[start]
        do {
            lastbin = minOf(Ac3Tables.BAND_START[j + 1], end)
            var m = mask[j] - snrOffset - floor
            if (m < 0) m = 0
            m = (m and 0x1FE0) + floor
            while (i < lastbin) {
                val address = ((psd[i] - m) shr 5).coerceIn(0, 63)
                bap[i] = Ac3Tables.BAP[address]
                i++
            }
            j++
        } while (end > lastbin)
    }

    private fun logAdd(a: Int, b: Int): Int {
        val c = a - b
        val address = minOf(kotlin.math.abs(c) shr 1, 255)
        return if (c >= 0) a + Ac3Tables.LOG_ADD[address] else b + Ac3Tables.LOG_ADD[address]
    }

    private fun lowComp(a: Int, b0: Int, b1: Int, bin: Int): Int = when {
        bin < 7 -> when {
            b0 + 256 == b1 -> 384
            b0 > b1 -> maxOf(0, a - 64)
            else -> a
        }
        bin < 20 -> when {
            b0 + 256 == b1 -> 320
            b0 > b1 -> maxOf(0, a - 64)
            else -> a
        }
        else -> maxOf(0, a - 128)
    }
}

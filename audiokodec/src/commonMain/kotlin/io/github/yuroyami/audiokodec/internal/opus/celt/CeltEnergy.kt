package io.github.yuroyami.audiokodec.internal.opus.celt

import io.github.yuroyami.audiokodec.internal.opus.RangeDecoder
import io.github.yuroyami.audiokodec.internal.opus.RangeEncoder
import kotlin.math.abs
import kotlin.math.floor

/**
 * CELT's band energies, which carry the spectral envelope.
 *
 * Each band's energy is sent in three passes. A coarse value in 6 dB steps is
 * predicted from the previous frame and the band below and coded with a
 * Laplace-shaped model; a fine refinement takes the bits the allocation gives
 * it; and whatever bits are left over at the end of the frame refine the
 * energies once more, in priority order.
 *
 * Translated from libopus `quant_bands.c` and `laplace.c`, both the
 * decoder's side and the encoder's. Energies are in base-2 logarithm units,
 * relative to [E_MEANS].
 */
internal object CeltEnergy {

    /** `eMeans`: the average energy of each band, subtracted before coding. */
    val E_MEANS: FloatArray = floatArrayOf(
        6.437500f, 6.250000f, 5.750000f, 5.312500f, 5.062500f,
        4.812500f, 4.500000f, 4.375000f, 4.875000f, 4.687500f,
        4.562500f, 4.437500f, 4.875000f, 4.625000f, 4.312500f,
        4.500000f, 4.375000f, 4.625000f, 4.750000f, 4.437500f,
        3.750000f, 3.750000f, 3.750000f, 3.750000f, 3.750000f,
    )

    /** Prediction from the previous frame for each block size: 0.9, 0.8, 0.65 and 0.5. */
    private val PRED_COEF = floatArrayOf(
        (29440 / 32768.0).toFloat(), (26112 / 32768.0).toFloat(), (21248 / 32768.0).toFloat(), (16384 / 32768.0).toFloat(),
    )

    /** Prediction from the band below for each block size. */
    private val BETA_COEF = floatArrayOf(
        (30147 / 32768.0).toFloat(), (22282 / 32768.0).toFloat(), (12124 / 32768.0).toFloat(), (6554 / 32768.0).toFloat(),
    )

    /** Prediction from the band below in an intra frame. */
    private val BETA_INTRA = (4915 / 32768.0).toFloat()

    private val SMALL_ENERGY_ICDF = intArrayOf(2, 1, 0)

    private const val NB_E_BANDS = 21
    private const val MAX_FINE_BITS = 8

    /** `unquant_coarse_energy`. */
    fun unquantiseCoarse(
        start: Int,
        end: Int,
        oldBands: FloatArray,
        intra: Boolean,
        range: RangeDecoder,
        channels: Int,
        lm: Int,
    ) {
        val model = CeltEnergyTables.E_PROB_MODEL
        val modelStart = (lm * 2 + if (intra) 1 else 0) * 42
        val prev = floatArrayOf(0f, 0f)
        val coef: Float
        val beta: Float
        if (intra) {
            coef = 0f
            beta = BETA_INTRA
        } else {
            beta = BETA_COEF[lm]
            coef = PRED_COEF[lm]
        }
        val budget = range.storage * 8
        for (i in start until end) {
            for (c in 0 until channels) {
                val tell = range.tell()
                val qi = when {
                    budget - tell >= 15 -> {
                        val pi = 2 * minOf(i, 20)
                        laplaceDecode(range, model[modelStart + pi] shl 7, model[modelStart + pi + 1] shl 6)
                    }
                    budget - tell >= 2 -> {
                        val q = range.decodeIcdf(SMALL_ENERGY_ICDF, 2)
                        (q shr 1) xor -(q and 1)
                    }
                    budget - tell >= 1 -> if (range.decodeBitLogp(1)) -1 else 0
                    else -> -1
                }
                val q = qi.toFloat()
                val at = i + c * NB_E_BANDS
                oldBands[at] = maxOf(-9f, oldBands[at])
                val tmp = coef * oldBands[at] + prev[c] + q
                oldBands[at] = tmp
                prev[c] = prev[c] + q - beta * q
            }
        }
    }

    /** `unquant_fine_energy`. */
    fun unquantiseFine(start: Int, end: Int, oldBands: FloatArray, fineQuant: IntArray, range: RangeDecoder, channels: Int) {
        for (i in start until end) {
            if (fineQuant[i] <= 0) continue
            for (c in 0 until channels) {
                val q2 = range.decodeRawBits(fineQuant[i])
                val offset = (q2 + 0.5f) * (1 shl (14 - fineQuant[i])) * (1f / 16384) - 0.5f
                oldBands[i + c * NB_E_BANDS] += offset
            }
        }
    }

    /** `unquant_energy_finalise`: spends the frame's last bits on energy precision. */
    fun unquantiseFinalise(
        start: Int,
        end: Int,
        oldBands: FloatArray,
        fineQuant: IntArray,
        finePriority: IntArray,
        bitsLeftIn: Int,
        range: RangeDecoder,
        channels: Int,
    ) {
        var bitsLeft = bitsLeftIn
        for (priority in 0 until 2) {
            var i = start
            while (i < end && bitsLeft >= channels) {
                if (fineQuant[i] >= MAX_FINE_BITS || finePriority[i] != priority) {
                    i++
                    continue
                }
                for (c in 0 until channels) {
                    val q2 = range.decodeRawBits(1)
                    val offset = (q2 - 0.5f) * (1 shl (14 - fineQuant[i] - 1)) * (1f / 16384)
                    oldBands[i + c * NB_E_BANDS] += offset
                    bitsLeft--
                }
                i++
            }
        }
    }

    /** `amp2Log2`: band amplitudes [bandE] to log energies relative to [E_MEANS]. */
    fun amplitudeToLog(effEnd: Int, end: Int, bandE: FloatArray, bandLogE: FloatArray, channels: Int) {
        for (c in 0 until channels) {
            for (i in 0 until effEnd) {
                bandLogE[i + c * NB_E_BANDS] = CeltMath.log2(bandE[i + c * NB_E_BANDS]) - E_MEANS[i]
            }
            for (i in effEnd until end) bandLogE[c * NB_E_BANDS + i] = -14f
        }
    }

    /** `loss_distortion`: how far the new energies are from the old, capped. */
    private fun lossDistortion(eBands: FloatArray, oldEBands: FloatArray, start: Int, end: Int, channels: Int): Float {
        var dist = 0f
        for (c in 0 until channels) {
            for (i in start until end) {
                val d = eBands[i + c * NB_E_BANDS] - oldEBands[i + c * NB_E_BANDS]
                dist += d * d
            }
        }
        return if (200f < dist) 200f else dist
    }

    /**
     * `quant_coarse_energy`: codes [eBands] to 6 dB steps, predicted from the
     * band below and, unless the frame is coded intra, from the previous
     * frame in [oldEBands], which receives the quantised energies. [error]
     * receives what the coarse step left over. With [twoPass] both
     * predictions are tried and the cheaper kept, as the reference does from
     * complexity 4. Returns the new value of the `delayedIntra` state.
     */
    fun quantiseCoarse(
        start: Int,
        end: Int,
        effEnd: Int,
        eBands: FloatArray,
        oldEBands: FloatArray,
        budget: Int,
        error: FloatArray,
        range: RangeEncoder,
        channels: Int,
        lm: Int,
        nbAvailableBytes: Int,
        forceIntra: Boolean,
        delayedIntra: Float,
        twoPassIn: Boolean,
    ): Float {
        var twoPass = twoPassIn
        var intra = forceIntra ||
            (!twoPass && delayedIntra > 2 * channels * (end - start) && nbAvailableBytes > (end - start) * channels)
        // The packet loss rate is always zero here, which zeroes this bias.
        val intraBias = 0
        val newDistortion = lossDistortion(eBands, oldEBands, start, effEnd, channels)
        val tell = range.tell()
        if (tell + 3 > budget) {
            twoPass = false
            intra = false
        }
        var maxDecay = 16f
        if (end - start > 10) {
            val limit = 0.125f * nbAvailableBytes
            if (limit < maxDecay) maxDecay = limit
        }
        val startState = RangeEncoder.Snapshot().also { range.saveTo(it) }
        val oldIntra = oldEBands.copyOf(channels * NB_E_BANDS)
        val errorIntra = FloatArray(channels * NB_E_BANDS)
        val model = CeltEnergyTables.E_PROB_MODEL
        var badness1 = 0
        if (twoPass || intra) {
            badness1 = quantiseCoarsePass(
                start, end, eBands, oldIntra, budget, tell, model, (lm * 2 + 1) * 42,
                errorIntra, range, channels, lm, true, maxDecay,
            )
        }
        if (!intra) {
            val tellIntra = range.tellFraction()
            val intraState = RangeEncoder.Snapshot().also { range.saveTo(it) }
            val startBytes = startState.rangeBytes
            val intraBytes = intraState.rangeBytes
            val intraBits = range.buffer.copyOfRange(startBytes, intraBytes)
            range.restoreFrom(startState)
            val badness2 = quantiseCoarsePass(
                start, end, eBands, oldEBands, budget, tell, model, (lm * 2) * 42,
                error, range, channels, lm, false, maxDecay,
            )
            if (twoPass && (badness1 < badness2 || (badness1 == badness2 && range.tellFraction() + intraBias > tellIntra))) {
                range.restoreFrom(intraState)
                intraBits.copyInto(range.buffer, startBytes)
                oldIntra.copyInto(oldEBands)
                errorIntra.copyInto(error)
                intra = true
            }
        } else {
            oldIntra.copyInto(oldEBands)
            errorIntra.copyInto(error)
        }
        return if (intra) newDistortion else PRED_COEF[lm] * PRED_COEF[lm] * delayedIntra + newDistortion
    }

    /** `quant_coarse_energy_impl`: one pass of [quantiseCoarse], returning how much it had to compromise. */
    private fun quantiseCoarsePass(
        start: Int,
        end: Int,
        eBands: FloatArray,
        oldEBands: FloatArray,
        budget: Int,
        tellIn: Int,
        model: IntArray,
        modelStart: Int,
        error: FloatArray,
        range: RangeEncoder,
        channels: Int,
        lm: Int,
        intra: Boolean,
        maxDecay: Float,
    ): Int {
        var badness = 0
        val prev = floatArrayOf(0f, 0f)
        if (tellIn + 3 <= budget) range.encodeBitLogp(intra, 3)
        val coef: Float
        val beta: Float
        if (intra) {
            coef = 0f
            beta = BETA_INTRA
        } else {
            beta = BETA_COEF[lm]
            coef = PRED_COEF[lm]
        }
        for (i in start until end) {
            for (c in 0 until channels) {
                val at = i + c * NB_E_BANDS
                val x = eBands[at]
                val oldE = if (-9f > oldEBands[at]) -9f else oldEBands[at]
                val f = x - coef * oldE - prev[c]
                // Rounding to the nearest integer here matters.
                var qi = floor(0.5f + f).toInt()
                val decayBound = (if (-28f > oldEBands[at]) -28f else oldEBands[at]) - maxDecay
                // Keep the energy from falling too fast, as in a band of one bin.
                if (qi < 0 && x < decayBound) {
                    qi += (decayBound - x).toInt()
                    if (qi > 0) qi = 0
                }
                val qi0 = qi
                val tell = range.tell()
                val bitsLeft = budget - tell - 3 * channels * (end - i)
                if (i != start && bitsLeft < 30) {
                    if (bitsLeft < 24) qi = minOf(1, qi)
                    if (bitsLeft < 16) qi = maxOf(-1, qi)
                }
                if (budget - tell >= 15) {
                    val pi = 2 * minOf(i, 20)
                    qi = laplaceEncode(range, qi, model[modelStart + pi] shl 7, model[modelStart + pi + 1] shl 6)
                } else if (budget - tell >= 2) {
                    qi = maxOf(-1, minOf(qi, 1))
                    range.encodeIcdf((2 * qi) xor -(if (qi < 0) 1 else 0), SMALL_ENERGY_ICDF, 2)
                } else if (budget - tell >= 1) {
                    qi = minOf(0, qi)
                    range.encodeBitLogp(qi != 0, 1)
                } else {
                    qi = -1
                }
                error[at] = f - qi
                badness += abs(qi0 - qi)
                val q = qi.toFloat()
                oldEBands[at] = coef * oldE + prev[c] + q
                prev[c] = prev[c] + q - beta * q
            }
        }
        return badness
    }

    /** `quant_fine_energy`: refines each band's energy by the bits the allocation gave it. */
    fun quantiseFine(
        start: Int,
        end: Int,
        oldBands: FloatArray,
        error: FloatArray,
        fineQuant: IntArray,
        range: RangeEncoder,
        channels: Int,
    ) {
        for (i in start until end) {
            if (fineQuant[i] <= 0) continue
            val frac = 1 shl fineQuant[i]
            for (c in 0 until channels) {
                val at = i + c * NB_E_BANDS
                var q2 = floor((error[at] + 0.5f) * frac).toInt()
                if (q2 > frac - 1) q2 = frac - 1
                if (q2 < 0) q2 = 0
                range.encodeRawBits(q2, fineQuant[i])
                val offset = (q2 + 0.5f) * (1 shl (14 - fineQuant[i])) * (1f / 16384) - 0.5f
                oldBands[at] += offset
                error[at] -= offset
            }
        }
    }

    /** `quant_energy_finalise`: spends the frame's last bits on energy precision. */
    fun quantiseFinalise(
        start: Int,
        end: Int,
        oldBands: FloatArray,
        error: FloatArray,
        fineQuant: IntArray,
        finePriority: IntArray,
        bitsLeftIn: Int,
        range: RangeEncoder,
        channels: Int,
    ) {
        var bitsLeft = bitsLeftIn
        for (priority in 0 until 2) {
            var i = start
            while (i < end && bitsLeft >= channels) {
                if (fineQuant[i] >= MAX_FINE_BITS || finePriority[i] != priority) {
                    i++
                    continue
                }
                for (c in 0 until channels) {
                    val at = i + c * NB_E_BANDS
                    val q2 = if (error[at] < 0) 0 else 1
                    range.encodeRawBits(q2, 1)
                    val offset = (q2 - 0.5f) * (1 shl (14 - fineQuant[i] - 1)) * (1f / 16384)
                    oldBands[at] += offset
                    error[at] -= offset
                    bitsLeft--
                }
                i++
            }
        }
    }

    /**
     * `ec_laplace_encode`: [value] under the two-sided geometric model with
     * zero probability [fsIn] and [decay], both in 1/32768. A value too far
     * out for the model is clamped, and the value actually coded returned.
     */
    fun laplaceEncode(range: RangeEncoder, value: Int, fsIn: Int, decay: Int): Int {
        var coded = value
        var fs = fsIn
        var fl = 0
        if (coded != 0) {
            val s = -(if (coded < 0) 1 else 0)
            val v = (coded + s) xor s
            fl = fs
            fs = laplaceFreq1(fs, decay)
            var i = 1
            while (fs > 0 && i < v) {
                fs *= 2
                fl += fs + 2 * LAPLACE_MINP
                fs = (fs * decay) shr 15
                i++
            }
            if (fs == 0) {
                // Beyond the decaying part every value has the minimum probability.
                var ndiMax = (32768 - fl + LAPLACE_MINP - 1) shr LAPLACE_LOG_MINP
                ndiMax = (ndiMax - s) shr 1
                val di = minOf(v - i, ndiMax - 1)
                fl += (2 * di + 1 + s) * LAPLACE_MINP
                fs = minOf(LAPLACE_MINP, 32768 - fl)
                coded = (i + di + s) xor s
            } else {
                fs += LAPLACE_MINP
                fl += fs and s.inv()
            }
        }
        range.encodeBin(fl, fl + fs, 15)
        return coded
    }

    private const val LAPLACE_LOG_MINP = 0
    private const val LAPLACE_MINP = 1 shl LAPLACE_LOG_MINP
    private const val LAPLACE_NMIN = 16

    private fun laplaceFreq1(fs0: Int, decay: Int): Int {
        val ft = 32768 - LAPLACE_MINP * (2 * LAPLACE_NMIN) - fs0
        return ft * (16384 - decay) ushr 15
    }

    /** `ec_laplace_decode`: a signed integer from a two-sided geometric model. */
    fun laplaceDecode(range: RangeDecoder, fsIn: Int, decay: Int): Int {
        var fs = fsIn
        var value = 0
        val fm = range.decodeBin(15)
        var fl = 0
        if (fm >= fs) {
            value++
            fl = fs
            fs = laplaceFreq1(fs, decay) + LAPLACE_MINP
            while (fs > LAPLACE_MINP && fm >= fl + 2 * fs) {
                fs *= 2
                fl += fs
                fs = ((fs - 2 * LAPLACE_MINP) * decay) shr 15
                fs += LAPLACE_MINP
                value++
            }
            if (fs <= LAPLACE_MINP) {
                val di = (fm - fl) shr (LAPLACE_LOG_MINP + 1)
                value += di
                fl += 2 * di * LAPLACE_MINP
            }
            if (fm < fl + fs) value = -value else fl += fs
        }
        range.update(fl, minOf(fl + fs, 32768), 32768)
        return value
    }
}

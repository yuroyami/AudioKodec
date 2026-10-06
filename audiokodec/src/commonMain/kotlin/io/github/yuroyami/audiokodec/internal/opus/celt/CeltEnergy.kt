package io.github.yuroyami.audiokodec.internal.opus.celt

import io.github.yuroyami.audiokodec.internal.opus.RangeDecoder

/**
 * CELT's band energies, which carry the spectral envelope.
 *
 * Each band's energy is sent in three passes. A coarse value in 6 dB steps is
 * predicted from the previous frame and the band below and coded with a
 * Laplace-shaped model; a fine refinement takes the bits the allocation gives
 * it; and whatever bits are left over at the end of the frame refine the
 * energies once more, in priority order.
 *
 * Translated from libopus `quant_bands.c` and `laplace.c`. Energies are in
 * base-2 logarithm units, relative to [E_MEANS].
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

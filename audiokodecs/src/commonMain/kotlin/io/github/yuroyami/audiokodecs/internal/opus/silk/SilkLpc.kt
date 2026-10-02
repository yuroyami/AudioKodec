package io.github.yuroyami.audiokodecs.internal.opus.silk

/*
 * The short-term predictor: from the coded line spectral frequencies to the
 * filter coefficients the synthesis filter runs on.
 *
 * SILK sends its spectral envelope as normalised line spectral frequencies
 * (NLSFs), a representation that survives quantisation gracefully: any sorted,
 * well-spaced set of NLSFs is a stable filter. The decoder rebuilds the vector
 * from a two-stage codebook, forces it sorted and spaced, converts it to
 * prediction coefficients, and then checks the coefficients for stability
 * once more, because the fixed-point conversion can push a borderline filter
 * over the edge.
 */

/** NLSF codebook, `silk_NLSF_CB_struct`, holding only what decoding reads. */
internal class SilkNlsfCodebook(
    val nVectors: Int,
    val order: Int,
    val quantStepSizeQ16: Int,
    val cb1NlsfQ8: IntArray,
    val cb1WeightQ9: IntArray,
    val cb1Icdf: IntArray,
    val predQ8: IntArray,
    val ecSel: IntArray,
    val ecIcdf: IntArray,
    val deltaMinQ15: IntArray,
) {
    companion object {
        /** `silk_NLSF_CB_NB_MB`, for 8 and 12 kHz, order 10. */
        val NB_MB = SilkNlsfCodebook(
            nVectors = 32,
            order = 10,
            quantStepSizeQ16 = 11796,
            cb1NlsfQ8 = SilkTables.NLSF_CB1_NB_MB_Q8,
            cb1WeightQ9 = SilkTables.NLSF_CB1_WGHT_NB_MB_Q9,
            cb1Icdf = SilkTables.NLSF_CB1_ICDF_NB_MB,
            predQ8 = SilkTables.NLSF_PRED_NB_MB_Q8,
            ecSel = SilkTables.NLSF_CB2_SELECT_NB_MB,
            ecIcdf = SilkTables.NLSF_CB2_ICDF_NB_MB,
            deltaMinQ15 = SilkTables.NLSF_DELTA_MIN_NB_MB_Q15,
        )

        /** `silk_NLSF_CB_WB`, for 16 kHz, order 16. */
        val WB = SilkNlsfCodebook(
            nVectors = 32,
            order = 16,
            quantStepSizeQ16 = 9830,
            cb1NlsfQ8 = SilkTables.NLSF_CB1_WB_Q8,
            cb1WeightQ9 = SilkTables.NLSF_CB1_WGHT_WB_Q9,
            cb1Icdf = SilkTables.NLSF_CB1_ICDF_WB,
            predQ8 = SilkTables.NLSF_PRED_WB_Q8,
            ecSel = SilkTables.NLSF_CB2_SELECT_WB,
            ecIcdf = SilkTables.NLSF_CB2_ICDF_WB,
            deltaMinQ15 = SilkTables.NLSF_DELTA_MIN_WB_Q15,
        )
    }
}

private const val MAX_LPC_STABILIZE_ITERATIONS = 16
private const val NLSF_QUANT_MAX_AMPLITUDE = 4

/** `SILK_FIX_CONST(NLSF_QUANT_LEVEL_ADJ, 10)`. */
private const val NLSF_QUANT_LEVEL_ADJ_Q10 = 102

/**
 * Translates `silk_NLSF_unpack`: for the first-stage vector [cb1Index], which
 * residual table each coefficient is coded with ([ecIx], as an offset into the
 * codebook's `ecIcdf`) and which backward predictor applies to it ([predQ8]).
 */
internal fun nlsfUnpack(ecIx: IntArray, predQ8: IntArray, codebook: SilkNlsfCodebook, cb1Index: Int) {
    val order = codebook.order
    var selector = cb1Index * order / 2
    var i = 0
    while (i < order) {
        val entry = codebook.ecSel[selector++]
        ecIx[i] = smulbb((entry shr 1) and 7, 2 * NLSF_QUANT_MAX_AMPLITUDE + 1)
        predQ8[i] = codebook.predQ8[i + (entry and 1) * (order - 1)]
        ecIx[i + 1] = smulbb((entry shr 5) and 7, 2 * NLSF_QUANT_MAX_AMPLITUDE + 1)
        predQ8[i + 1] = codebook.predQ8[i + ((entry shr 4) and 1) * (order - 1) + 1]
        i += 2
    }
}

/**
 * Translates `silk_NLSF_decode`: rebuilds the NLSF vector in Q15 from the
 * codebook path in [indices] (the first-stage index, then one residual index
 * per coefficient), and leaves it stabilised.
 */
internal fun nlsfDecode(nlsfQ15: IntArray, indices: IntArray, codebook: SilkNlsfCodebook) {
    val order = codebook.order
    val ecIx = IntArray(order)
    val predQ8 = IntArray(order)
    nlsfUnpack(ecIx, predQ8, codebook, indices[0])

    // The residuals are predicted backwards, from the highest coefficient
    // down, each from the one above it.
    val residualQ10 = IntArray(order)
    var outQ10 = 0
    for (i in order - 1 downTo 0) {
        val predictionQ10 = smulbb(outQ10, predQ8[i]) shr 8
        outQ10 = indices[i + 1] shl 10
        if (outQ10 > 0) {
            outQ10 -= NLSF_QUANT_LEVEL_ADJ_Q10
        } else if (outQ10 < 0) {
            outQ10 += NLSF_QUANT_LEVEL_ADJ_Q10
        }
        outQ10 = smlawb(predictionQ10, outQ10, codebook.quantStepSizeQ16)
        residualQ10[i] = outQ10.toShort().toInt()
    }

    // Residuals are weighted by the inverse square root of how sensitive each
    // coefficient is, so undo that and add the first-stage vector.
    val base = indices[0] * order
    for (i in 0 until order) {
        val value = (residualQ10[i] shl 14) / codebook.cb1WeightQ9[base + i] +
            (codebook.cb1NlsfQ8[base + i] shl 7)
        nlsfQ15[i] = limit(value, 0, 32767)
    }

    nlsfStabilize(nlsfQ15, codebook.deltaMinQ15, order)
}

/**
 * Translates `silk_NLSF_stabilize`: moves the NLSFs apart until each is at
 * least the minimum distance from its neighbours and from both ends, touching
 * the vector as little as possible.
 */
internal fun nlsfStabilize(nlsfQ15: IntArray, deltaMinQ15: IntArray, length: Int) {
    var loops = 0
    while (loops < MAX_STABILIZE_LOOPS) {
        // Find the tightest spacing.
        var minDiffQ15 = nlsfQ15[0] - deltaMinQ15[0]
        var worst = 0
        for (i in 1 until length) {
            val diffQ15 = nlsfQ15[i] - (nlsfQ15[i - 1] + deltaMinQ15[i])
            if (diffQ15 < minDiffQ15) {
                minDiffQ15 = diffQ15
                worst = i
            }
        }
        val lastDiffQ15 = (1 shl 15) - (nlsfQ15[length - 1] + deltaMinQ15[length])
        if (lastDiffQ15 < minDiffQ15) {
            minDiffQ15 = lastDiffQ15
            worst = length
        }

        if (minDiffQ15 >= 0) return

        when (worst) {
            0 -> nlsfQ15[0] = deltaMinQ15[0]
            length -> nlsfQ15[length - 1] = ((1 shl 15) - deltaMinQ15[length]).toShort().toInt()
            else -> {
                // Spread the pair about its centre, keeping the centre where
                // there is room for every other coefficient on either side.
                var minCenterQ15 = 0
                for (k in 0 until worst) minCenterQ15 += deltaMinQ15[k]
                minCenterQ15 += deltaMinQ15[worst] shr 1

                var maxCenterQ15 = 1 shl 15
                for (k in length downTo worst + 1) maxCenterQ15 -= deltaMinQ15[k]
                maxCenterQ15 -= deltaMinQ15[worst] shr 1

                val centreQ15 = limit(
                    rshiftRound(nlsfQ15[worst - 1] + nlsfQ15[worst], 1),
                    minCenterQ15,
                    maxCenterQ15,
                ).toShort().toInt()
                nlsfQ15[worst - 1] = (centreQ15 - (deltaMinQ15[worst] shr 1)).toShort().toInt()
                nlsfQ15[worst] = (nlsfQ15[worst - 1] + deltaMinQ15[worst]).toShort().toInt()
            }
        }
        loops++
    }

    // The gentle method did not converge. Sort, then push the values apart in
    // one sweep up and one sweep down, which always succeeds.
    for (i in 1 until length) {
        val value = nlsfQ15[i]
        var j = i - 1
        while (j >= 0 && value < nlsfQ15[j]) {
            nlsfQ15[j + 1] = nlsfQ15[j]
            j--
        }
        nlsfQ15[j + 1] = value
    }
    nlsfQ15[0] = maxOf(nlsfQ15[0], deltaMinQ15[0])
    for (i in 1 until length) {
        nlsfQ15[i] = maxOf(nlsfQ15[i], sat16(nlsfQ15[i - 1] + deltaMinQ15[i]))
    }
    nlsfQ15[length - 1] = minOf(nlsfQ15[length - 1], (1 shl 15) - deltaMinQ15[length]).toShort().toInt()
    for (i in length - 2 downTo 0) {
        nlsfQ15[i] = minOf(nlsfQ15[i], nlsfQ15[i + 1] - deltaMinQ15[i + 1]).toShort().toInt()
    }
}

private const val MAX_STABILIZE_LOOPS = 20

/** Q domain of the polynomials in [nlsfToLpc]. */
private const val NLSF2A_QA = 16

/**
 * The order in which [nlsfToLpc] interleaves the cosines. The reference found
 * it the most accurate in fixed point; any other order gives other bits.
 */
private val ORDERING_16 = intArrayOf(0, 15, 8, 7, 4, 11, 12, 3, 2, 13, 10, 5, 6, 9, 14, 1)
private val ORDERING_10 = intArrayOf(0, 9, 6, 3, 4, 5, 8, 1, 2, 7)

/** Translates `silk_NLSF2A_find_poly`: one of the two polynomials whose roots are the cosines. */
private fun nlsfFindPoly(out: IntArray, cLsf: IntArray, start: Int, halfOrder: Int) {
    out[0] = 1 shl NLSF2A_QA
    out[1] = -cLsf[start]
    for (k in 1 until halfOrder) {
        val cosine = cLsf[start + 2 * k]
        out[k + 1] = (out[k - 1] shl 1) - rshiftRound64(cosine.toLong() * out[k], NLSF2A_QA).toInt()
        for (n in k downTo 2) {
            out[n] += out[n - 2] - rshiftRound64(cosine.toLong() * out[n - 1], NLSF2A_QA).toInt()
        }
        out[1] -= cosine
    }
}

/**
 * Translates `silk_NLSF2A`: converts [order] NLSFs in Q15 to prediction
 * coefficients in Q12, written to [aQ12], bandwidth-expanding them until they
 * pass [lpcInversePredGain].
 */
internal fun nlsfToLpc(aQ12: IntArray, nlsf: IntArray, order: Int) {
    val ordering = if (order == 16) ORDERING_16 else ORDERING_10
    val cosLsfQA = IntArray(order)
    for (k in 0 until order) {
        // A piecewise linear interpolation of the cosine table.
        val fInt = nlsf[k] shr (15 - 7)
        val fFrac = nlsf[k] - (fInt shl (15 - 7))
        val cosValue = SilkTables.LSF_COS_TAB_FIX_Q12[fInt]
        val delta = SilkTables.LSF_COS_TAB_FIX_Q12[fInt + 1] - cosValue
        cosLsfQA[ordering[k]] = rshiftRound((cosValue shl 8) + delta * fFrac, 20 - NLSF2A_QA)
    }

    val halfOrder = order shr 1
    val p = IntArray(halfOrder + 1)
    val q = IntArray(halfOrder + 1)
    nlsfFindPoly(p, cosLsfQA, 0, halfOrder)
    nlsfFindPoly(q, cosLsfQA, 1, halfOrder)

    val a32QA1 = IntArray(order)
    for (k in 0 until halfOrder) {
        val pSum = p[k + 1] + p[k]
        val qDifference = q[k + 1] - q[k]
        a32QA1[k] = -qDifference - pSum
        a32QA1[order - k - 1] = qDifference - pSum
    }

    lpcFit(aQ12, a32QA1, 12, NLSF2A_QA + 1, order)

    var i = 0
    while (lpcInversePredGain(aQ12, order) == 0 && i < MAX_LPC_STABILIZE_ITERATIONS) {
        // Too close to unstable: widen the bandwidth of the full-precision
        // coefficients a little more each time and measure again.
        bwexpander32(a32QA1, order, 65536 - (2 shl i))
        for (k in 0 until order) {
            aQ12[k] = rshiftRound(a32QA1[k], NLSF2A_QA + 1 - 12).toShort().toInt()
        }
        i++
    }
}

/**
 * Translates `silk_LPC_fit`: converts 32-bit coefficients in Q[qIn] to 16-bit
 * ones in Q[qOut], bandwidth-expanding first so that none of them wraps.
 */
internal fun lpcFit(aQOut: IntArray, aQIn: IntArray, qOut: Int, qIn: Int, order: Int) {
    var iteration = 0
    var index = 0
    while (iteration < 10) {
        var maxAbs = 0
        for (k in 0 until order) {
            val absValue = silkAbs(aQIn[k])
            if (absValue > maxAbs) {
                maxAbs = absValue
                index = k
            }
        }
        maxAbs = rshiftRound(maxAbs, qIn - qOut)
        if (maxAbs > INT16_MAX) {
            // (Int.MAX_VALUE shr 14) + INT16_MAX.
            maxAbs = minOf(maxAbs, 163838)
            val chirpQ16 = 65470 - ((maxAbs - INT16_MAX) shl 14) / ((maxAbs * (index + 1)) shr 2)
            bwexpander32(aQIn, order, chirpQ16)
        } else {
            break
        }
        iteration++
    }

    if (iteration == 10) {
        // Still too large after the last attempt, so clip.
        for (k in 0 until order) {
            aQOut[k] = sat16(rshiftRound(aQIn[k], qIn - qOut))
            aQIn[k] = aQOut[k] shl (qIn - qOut)
        }
    } else {
        for (k in 0 until order) {
            aQOut[k] = rshiftRound(aQIn[k], qIn - qOut).toShort().toInt()
        }
    }
}

/**
 * Translates `silk_bwexpander`: scales coefficient `i` by `chirp^(i+1)`, pulling
 * the poles towards the origin. Rounds rather than truncating, because the
 * truncating multiply's bias can leave a filter unstable.
 */
internal fun bwexpander(ar: IntArray, order: Int, chirpQ16Start: Int) {
    var chirpQ16 = chirpQ16Start
    val chirpMinusOneQ16 = chirpQ16 - 65536
    for (i in 0 until order - 1) {
        ar[i] = rshiftRound(chirpQ16 * ar[i], 16).toShort().toInt()
        chirpQ16 += rshiftRound(chirpQ16 * chirpMinusOneQ16, 16)
    }
    ar[order - 1] = rshiftRound(chirpQ16 * ar[order - 1], 16).toShort().toInt()
}

/** Translates `silk_bwexpander_32`, [bwexpander] on 32-bit coefficients. */
internal fun bwexpander32(ar: IntArray, order: Int, chirpQ16Start: Int) {
    var chirpQ16 = chirpQ16Start
    val chirpMinusOneQ16 = chirpQ16 - 65536
    for (i in 0 until order - 1) {
        ar[i] = smulww(chirpQ16, ar[i])
        chirpQ16 += rshiftRound(chirpQ16 * chirpMinusOneQ16, 16)
    }
    ar[order - 1] = smulww(chirpQ16, ar[order - 1])
}

/** Q domain of [lpcInversePredGain]'s working coefficients. */
private const val INV_PRED_QA = 24

/** `SILK_FIX_CONST(0.99975, 24)`: reflection coefficients beyond this count as unstable. */
private const val A_LIMIT = 16773022

/** `SILK_FIX_CONST(1.0f / MAX_PREDICTION_POWER_GAIN, 30)`: the largest prediction gain allowed. */
private const val MIN_INV_GAIN_Q30 = 107374

/**
 * Translates `silk_LPC_inverse_pred_gain_c`: runs the Levinson recursion
 * backwards to find the reflection coefficients of the [order] coefficients in
 * [aQ12]. Returns the inverse of the prediction gain in Q30, or 0 when the
 * filter is unstable or its gain is too high to trust.
 */
internal fun lpcInversePredGain(aQ12: IntArray, order: Int): Int {
    val aQA = IntArray(order)
    var dcResponse = 0
    for (k in 0 until order) {
        dcResponse += aQ12[k]
        aQA[k] = aQ12[k] shl (INV_PRED_QA - 12)
    }
    // A DC gain this large is unstable without the full calculation.
    if (dcResponse >= 4096) return 0

    var invGainQ30 = 1 shl 30
    var k = order - 1
    while (k > 0) {
        if (aQA[k] > A_LIMIT || aQA[k] < -A_LIMIT) return 0

        val rcQ31 = -(aQA[k] shl (31 - INV_PRED_QA))
        val rcMult1Q30 = (1 shl 30) - smmul(rcQ31, rcQ31)
        invGainQ30 = smmul(invGainQ30, rcMult1Q30) shl 2
        if (invGainQ30 < MIN_INV_GAIN_Q30) return 0

        val mult2Q = 32 - clz32(silkAbs(rcMult1Q30))
        val rcMult2 = inverse32VarQ(rcMult1Q30, mult2Q + 30)

        for (n in 0 until ((k + 1) shr 1)) {
            val tmp1 = aQA[n]
            val tmp2 = aQA[k - n - 1]
            var tmp64 = rshiftRound64(
                subSat32(tmp1, rshiftRound64(tmp2.toLong() * rcQ31, 31).toInt()).toLong() * rcMult2,
                mult2Q,
            )
            if (tmp64 > Int.MAX_VALUE || tmp64 < Int.MIN_VALUE) return 0
            aQA[n] = tmp64.toInt()
            tmp64 = rshiftRound64(
                subSat32(tmp2, rshiftRound64(tmp1.toLong() * rcQ31, 31).toInt()).toLong() * rcMult2,
                mult2Q,
            )
            if (tmp64 > Int.MAX_VALUE || tmp64 < Int.MIN_VALUE) return 0
            aQA[k - n - 1] = tmp64.toInt()
        }
        k--
    }

    if (aQA[0] > A_LIMIT || aQA[0] < -A_LIMIT) return 0
    val rcQ31 = -(aQA[0] shl (31 - INV_PRED_QA))
    val rcMult1Q30 = (1 shl 30) - smmul(rcQ31, rcQ31)
    invGainQ30 = smmul(invGainQ30, rcMult1Q30) shl 2
    if (invGainQ30 < MIN_INV_GAIN_Q30) return 0
    return invGainQ30
}

/**
 * Translates `silk_LPC_analysis_filter`: the prediction error of [input] under
 * the order [order] predictor [b] (Q12), written to [out] for [length] samples
 * from each offset. The first [order] outputs are zero, since they have no
 * full history.
 */
internal fun lpcAnalysisFilter(
    out: IntArray,
    outOffset: Int,
    input: IntArray,
    inOffset: Int,
    b: IntArray,
    length: Int,
    order: Int,
) {
    for (ix in order until length) {
        val last = inOffset + ix - 1
        // The sum may wrap; only invalid streams make it, and then two wraps
        // cancel, as in the reference.
        var outQ12 = smulbb(input[last], b[0])
        for (j in 1 until order) {
            outQ12 = smlabb(outQ12, input[last - j], b[j])
        }
        outQ12 = (input[last + 1] shl 12) - outQ12
        out[outOffset + ix] = sat16(rshiftRound(outQ12, 12))
    }
    for (j in 0 until order) out[outOffset + j] = 0
}

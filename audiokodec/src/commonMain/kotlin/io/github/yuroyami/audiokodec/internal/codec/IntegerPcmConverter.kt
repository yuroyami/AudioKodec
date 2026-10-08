package io.github.yuroyami.audiokodec.internal.codec

import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.Dither
import kotlin.math.round

/** Converts signed, right-justified samples without a Float32 intermediate. */
internal class IntegerPcmConverter(
    private val sourceDepth: Int,
    targetDepth: Int,
    private val dither: Dither,
) {
    init {
        require(sourceDepth in 1..32) { "IntPcmSource.bitDepth must be 1..32, was $sourceDepth" }
        require(targetDepth in 1..32) { "target bitDepth must be 1..32, was $targetDepth" }
    }

    private val shift = targetDepth - sourceDepth
    private val sourceMinimum = -(1L shl (sourceDepth - 1))
    private val sourceMaximum = (1L shl (sourceDepth - 1)) - 1
    private val targetMinimum = -(1L shl (targetDepth - 1))
    private val targetMaximum = (1L shl (targetDepth - 1)) - 1
    private val divisor = if (shift < 0) (1L shl -shift).toDouble() else 1.0

    fun convertInPlace(samples: IntArray, entries: Int) {
        for (index in 0 until entries) {
            val value = samples[index]
            if (value.toLong() !in sourceMinimum..sourceMaximum) {
                throw AudioKodecException(
                    "IntPcmSource produced $value outside the signed $sourceDepth-bit range " +
                        "$sourceMinimum..$sourceMaximum",
                )
            }
            samples[index] = when {
                shift >= 0 -> value shl shift
                dither is Dither.Tpdf -> round(value.toDouble() / divisor + dither.nextSample().toDouble())
                    .coerceIn(targetMinimum.toDouble(), targetMaximum.toDouble()).toInt()
                else -> value shr -shift
            }
        }
    }
}

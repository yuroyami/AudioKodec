/*
 * Translated from libspeex 1.2.1, which is Copyright 2002-2008 Xiph.org
 * Foundation, Jean-Marc Valin and others and licensed under the BSD 3-Clause
 * licence, reproduced in NOTICE. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.speex

/** `ltp_params`: a pitch gain codebook and the bits of its index and of the pitch. */
internal class SpeexLtpParams(val gains: ByteArray, val gainBits: Int, val pitchBits: Int)

/** `split_cb_params`: a shape codebook of sub-vectors, and whether each has a sign bit. */
internal class SpeexSplitParams(val subvectSize: Int, val subvects: Int, val shapes: ByteArray, val shapeBits: Int, val haveSign: Boolean)

internal enum class SpeexLsp { NB, LBR, HIGH }

internal enum class SpeexPitch { THREE_TAP, FORCED, NONE }

internal enum class SpeexInnovation { SPLIT, NOISE, NONE }

/** `SpeexSubmode`, the decoding half of it. */
internal class SpeexSubmode(
    val lbrPitch: Int,
    val forcedPitchGain: Boolean,
    val subframeGain: Int,
    val doubleCodebook: Boolean,
    val lsp: SpeexLsp,
    val pitch: SpeexPitch,
    val ltp: SpeexLtpParams?,
    val innovation: SpeexInnovation,
    val split: SpeexSplitParams?,
    val combGain: Float,
)

/** The narrowband sub-modes of `modes.c` and the wideband ones of `modes_wb.c`. */
internal object SpeexModes {
    private val LTP_NB = SpeexLtpParams(SpeexTables.GAIN_NB, 7, 7)
    private val LTP_VLBR = SpeexLtpParams(SpeexTables.GAIN_LBR, 5, 0)
    private val LTP_LBR = SpeexLtpParams(SpeexTables.GAIN_LBR, 5, 7)
    private val LTP_MED = SpeexLtpParams(SpeexTables.GAIN_LBR, 5, 7)

    private val SPLIT_VLBR = SpeexSplitParams(10, 4, SpeexTables.EXC_10_16, 4, false)
    private val SPLIT_ULBR = SpeexSplitParams(20, 2, SpeexTables.EXC_20_32, 5, false)
    private val SPLIT_LBR = SpeexSplitParams(10, 4, SpeexTables.EXC_10_32, 5, false)
    private val SPLIT_NB = SpeexSplitParams(5, 8, SpeexTables.EXC_5_64, 6, false)
    private val SPLIT_MED = SpeexSplitParams(8, 5, SpeexTables.EXC_8_128, 7, false)
    private val SPLIT_SB = SpeexSplitParams(5, 8, SpeexTables.EXC_5_256, 8, false)
    private val SPLIT_HIGH = SpeexSplitParams(8, 5, SpeexTables.HEXC, 7, true)
    private val SPLIT_HIGH_LBR = SpeexSplitParams(10, 4, SpeexTables.HEXC_10_32, 5, false)

    // The comb gains are written as double literals and stored as floats.
    val NARROWBAND: Array<SpeexSubmode?> = arrayOf(
        null,
        SpeexSubmode(0, true, 0, false, SpeexLsp.LBR, SpeexPitch.FORCED, null, SpeexInnovation.NOISE, null, (-1.0).toFloat()),
        SpeexSubmode(0, false, 0, false, SpeexLsp.LBR, SpeexPitch.THREE_TAP, LTP_VLBR, SpeexInnovation.SPLIT, SPLIT_VLBR, (.6).toFloat()),
        SpeexSubmode(-1, false, 1, false, SpeexLsp.LBR, SpeexPitch.THREE_TAP, LTP_LBR, SpeexInnovation.SPLIT, SPLIT_LBR, (.55).toFloat()),
        SpeexSubmode(-1, false, 1, false, SpeexLsp.LBR, SpeexPitch.THREE_TAP, LTP_MED, SpeexInnovation.SPLIT, SPLIT_MED, (.45).toFloat()),
        SpeexSubmode(-1, false, 3, false, SpeexLsp.NB, SpeexPitch.THREE_TAP, LTP_NB, SpeexInnovation.SPLIT, SPLIT_NB, (.25).toFloat()),
        SpeexSubmode(-1, false, 3, false, SpeexLsp.NB, SpeexPitch.THREE_TAP, LTP_NB, SpeexInnovation.SPLIT, SPLIT_SB, (.15).toFloat()),
        SpeexSubmode(-1, false, 3, true, SpeexLsp.NB, SpeexPitch.THREE_TAP, LTP_NB, SpeexInnovation.SPLIT, SPLIT_NB, (.05).toFloat()),
        SpeexSubmode(0, true, 0, false, SpeexLsp.LBR, SpeexPitch.FORCED, null, SpeexInnovation.SPLIT, SPLIT_ULBR, (.5).toFloat()),
        null, null, null, null, null, null, null,
    )

    private val WB1 = SpeexSubmode(0, false, 1, false, SpeexLsp.HIGH, SpeexPitch.NONE, null, SpeexInnovation.NONE, null, -1f)
    private val WB2 = SpeexSubmode(0, false, 1, false, SpeexLsp.HIGH, SpeexPitch.NONE, null, SpeexInnovation.SPLIT, SPLIT_HIGH_LBR, -1f)
    private val WB3 = SpeexSubmode(0, false, 1, false, SpeexLsp.HIGH, SpeexPitch.NONE, null, SpeexInnovation.SPLIT, SPLIT_HIGH, -1f)
    private val WB4 = SpeexSubmode(0, false, 1, true, SpeexLsp.HIGH, SpeexPitch.NONE, null, SpeexInnovation.SPLIT, SPLIT_HIGH, -1f)

    val WIDEBAND: Array<SpeexSubmode?> = arrayOf(null, WB1, WB2, WB3, WB4, null, null, null)
    val ULTRA_WIDEBAND: Array<SpeexSubmode?> = arrayOf(null, WB1, null, null, null, null, null, null)
}

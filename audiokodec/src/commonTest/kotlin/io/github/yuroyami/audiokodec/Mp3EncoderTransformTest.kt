package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.mpeg.Mp3Granule
import io.github.yuroyami.audiokodec.internal.mpeg.Mp3SideInfo
import io.github.yuroyami.audiokodec.internal.mpeg.Mp3Synthesis
import io.github.yuroyami.audiokodec.internal.mpeg.Mp3Tables
import io.github.yuroyami.audiokodec.internal.mpeg.MpegFrameHeader
import io.github.yuroyami.audiokodec.internal.mpeg.PolyphaseFilter
import io.github.yuroyami.audiokodec.internal.mpeg.encoder.Mp3AnalysisFilter
import io.github.yuroyami.audiokodec.internal.mpeg.encoder.Mp3ForwardTransform
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The MP3 encoder's two transforms, each run into the decoder's inverse of it
 * with no quantisation in between, must give back what went in.
 *
 * These are the foundation the rest of the encoder stands on. The analysis
 * window is derived from the decoder's synthesis window rather than typed, and
 * the forward MDCT, its windows, the frequency inversion and the alias
 * reduction butterflies are checked against the decoder's own inverse path
 * for every block type and every transition between them.
 */
class Mp3EncoderTransformTest {

    @Test
    fun analysisIntoSynthesisRecoversTheInputAfterTheFilterbankDelay() {
        val random = Random(7)
        val length = 32 * 200
        val input = DoubleArray(length) { i ->
            0.5 * sin(i * 0.013) + 0.3 * sin(i * 1.7) + 0.2 * (random.nextDouble() * 2 - 1)
        }
        val analysis = Mp3AnalysisFilter(1)
        val synthesis = PolyphaseFilter(1)
        val subbands = DoubleArray(32)
        val output = FloatArray(length)
        for (block in 0 until length / 32) {
            analysis.step(input, block * 32, 0, subbands, 0, 1)
            synthesis.step(subbands, 0, 1, 0, output, block * 32, 1)
        }
        val delay = Mp3AnalysisFilter.DELAY
        var worst = 0.0
        for (i in 0 until length - delay) worst = maxOf(worst, abs(output[i + delay] - input[i]))
        // The prototype filter is a near-perfect-reconstruction design, not
        // an exact one, so what is left is its own small ripple on top of
        // the float output's rounding.
        println("MP3 analysis into synthesis: worst difference $worst after $delay samples")
        assertTrue(worst < 1e-4, "analysis into synthesis differs from the input by $worst")
        var silentHead = 0.0
        for (i in 0 until delay - 1) silentHead = maxOf(silentHead, abs(output[i].toDouble()))
        assertTrue(silentHead < 1e-3, "output before the delay is $silentHead")
    }

    @Test
    fun forwardTransformIntoTheDecodersInverseRecoversTheSubbandsForEveryBlockType() {
        // Every legal transition: long, start, short, short, stop, long, start,
        // short, stop, start, short, stop, long.
        val sequence = intArrayOf(0, 0, 1, 2, 2, 3, 0, 1, 2, 3, 1, 2, 3, 0, 0)
        val random = Random(11)
        val granules = Array(sequence.size) { DoubleArray(576) { random.nextDouble() * 2 - 1 } }
        val header = MpegFrameHeader(
            version = 1, layer = 3, hasCrc = false, bitrateKbps = 128, sampleRate = 44_100,
            padded = false, privateBit = false, channelMode = MpegFrameHeader.MODE_SINGLE,
            modeExtension = 0, copyrighted = false, original = false, emphasis = 0,
        )
        val forward = Mp3ForwardTransform(1)
        val synthesis = Mp3Synthesis(1)
        val granule = Mp3Granule()
        val transformed = DoubleArray(576)
        val recovered = DoubleArray(576)
        var worst = 0.0
        for (g in sequence.indices) {
            val type = sequence[g]
            forward.transform(granules[g], 0, type, transformed)
            if (type == Mp3ForwardTransform.SHORT) {
                Mp3ForwardTransform.toBitstreamOrder(transformed, Mp3Tables.shortBands(44_100), granule.spectrum)
            } else {
                transformed.copyInto(granule.spectrum)
            }
            val info = sideInfo(type)
            synthesis.reorder(granule, info, header)
            synthesis.reduceAlias(granule, info)
            synthesis.inverseTransform(granule, info, 0, recovered)
            // The inverse completes the previous granule, so it lags one.
            if (g == 0) continue
            for (i in 0 until 576) worst = maxOf(worst, abs(recovered[i] - granules[g - 1][i]))
        }
        println("MP3 forward MDCT into the decoder's inverse: worst difference $worst")
        assertTrue(worst < 1e-12, "the forward and inverse transforms differ by $worst")
    }

    private fun sideInfo(blockType: Int) = Mp3SideInfo.Granule(
        part2And3Length = 0, bigValues = 0, globalGain = 0, scalefacCompress = 0,
        windowSwitching = blockType != 0, blockType = blockType, mixedBlock = false,
        tableSelect = IntArray(3), subblockGain = IntArray(3), region0Count = 0, region1Count = 0,
        preflag = false, scalefacScale = 0, count1TableSelect = 0,
    )
}

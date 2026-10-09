package io.github.yuroyami.audiokodec

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checks [Resampler] on every target: lengths, streaming, alignment and
 * quality. The JVM oracle suite checks the samples against speexdsp's own.
 */
class ResamplerTest {

    @Test
    fun aStreamConvertsToExactlyTheLengthItsDurationGives() {
        for (quality in ResampleQuality.entries) {
            for ((from, to) in listOf(44_100 to 48_000, 48_000 to 44_100, 8_000 to 48_000, 48_000 to 8_000, 22_050 to 44_100, 44_100 to 44_100)) {
                for (frames in listOf(0, 1, 2, 7, 100, 4_410, 10_001)) {
                    val out = Pcm.resample(FloatArray(frames * 2), 2, from, to, quality)
                    val expected = (frames.toLong() * to + from - 1) / from
                    assertEquals(expected * 2, out.size.toLong(), "$quality, $from to $to Hz, $frames frames")
                }
            }
        }
    }

    @Test
    fun blocksOfAnySizeGiveTheSameSamplesAsOneCall() {
        val random = Random(11)
        for (quality in ResampleQuality.entries) {
            for ((from, to) in listOf(44_100 to 48_000, 48_000 to 44_100, 96_000 to 44_100)) {
                val input = FloatArray(30_000) { (random.nextFloat() - 0.5f) * 0.8f }
                val whole = Pcm.resample(input, 3, from, to, quality)
                val resampler = Resampler(from, to, 3, quality)
                val block = FloatArray(resampler.maxOutputFrames(700) * 3)
                val pieces = ArrayList<Float>()
                var at = 0
                while (at < 10_000) {
                    val take = minOf(10_000 - at, random.nextInt(701))
                    val written = resampler.process(input, at, take, block, 0)
                    for (i in 0 until written * 3) pieces.add(block[i])
                    at += take
                }
                val tail = FloatArray(resampler.pendingOutputFrames * 3)
                assertEquals(resampler.pendingOutputFrames, resampler.flush(tail))
                for (v in tail) pieces.add(v)
                assertContentEquals(whole, pieces.toFloatArray(), "$quality, $from to $to Hz")
            }
        }
    }

    @Test
    fun theOutputIsAlignedWithTheInput() {
        // A tone's phase survives the conversion: output frame k is the input at time k / outputRate.
        for (quality in ResampleQuality.entries) {
            for ((from, to) in listOf(44_100 to 48_000, 48_000 to 44_100, 8_000 to 48_000)) {
                val input = FloatArray(from) { (0.5 * sin(2 * PI * 440.0 * it / from)).toFloat() }
                val out = Pcm.resample(input, 1, from, to, quality)
                for (k in out.size / 4 until out.size * 3 / 4 step 97) {
                    val expected = 0.5 * sin(2 * PI * 440.0 * k / to)
                    assertEquals(expected, out[k].toDouble(), if (quality == ResampleQuality.SINC) 1e-5 else 1e-2, "$quality, $from to $to Hz, frame $k")
                }
            }
        }
    }

    @Test
    fun theSincModeKeepsDistortionFarBelowATone() {
        for ((from, to) in listOf(44_100 to 48_000, 48_000 to 44_100, 48_000 to 96_000, 96_000 to 44_100)) {
            val input = FloatArray(from / 2) { (0.5 * sin(2 * PI * 1_000.0 * it / from)).toFloat() }
            val measured = distortionDb(Pcm.resample(input, 1, from, to), to.toDouble(), 1_000.0)
            assertTrue(measured < -125.0, "$from to $to Hz: $measured dB")
        }
    }

    @Test
    fun theLinearModeIsExactOnAStraightLineAndCoarseOnATone() {
        // Straight-line interpolation reproduces a straight line, away from the end where the signal drops to zero.
        val ramp = FloatArray(1_000) { it / 1_000f }
        val out = Pcm.resample(ramp, 1, 44_100, 48_000, ResampleQuality.LINEAR)
        for (k in 0 until out.size - 2) assertEquals(k * 44_100.0 / 48_000 / 1_000, out[k].toDouble(), 1e-6, "frame $k")
        // On a 1 kHz tone from 44.1 to 48 kHz it measures -62.4 dB: fine for a preview, not for listening.
        val input = FloatArray(44_100) { (0.5 * sin(2 * PI * 1_000.0 * it / 44_100)).toFloat() }
        val measured = distortionDb(Pcm.resample(input, 1, 44_100, 48_000, ResampleQuality.LINEAR), 48_000.0, 1_000.0)
        assertTrue(measured < -60.0, "$measured dB")
    }

    @Test
    fun equalRatesPassTheSamplesThroughUnchanged() {
        val input = FloatArray(999) { (it % 17) / 17f - 0.5f }
        for (quality in ResampleQuality.entries) assertContentEquals(input, Pcm.resample(input, 3, 48_000, 48_000, quality))
    }

    @Test
    fun misuseIsRefused() {
        assertFailsWith<IllegalArgumentException> { Resampler(0, 48_000, 1) }
        assertFailsWith<IllegalArgumentException> { Resampler(44_100, 48_000, 0) }
        val resampler = Resampler(44_100, 48_000, 2)
        // Room for fewer frames than the call can write.
        assertFailsWith<IllegalArgumentException> { resampler.process(FloatArray(200), 0, 100, FloatArray(200), 0) }
        // Input that does not hold the frames named.
        assertFailsWith<IllegalArgumentException> { resampler.process(FloatArray(10), 0, 100, FloatArray(1_000), 0) }
        resampler.flush(FloatArray(resampler.pendingOutputFrames * 2))
        assertFailsWith<IllegalStateException> { resampler.process(FloatArray(2), 0, 1, FloatArray(10), 0) }
        resampler.reset()
        resampler.process(FloatArray(2), 0, 1, FloatArray(10), 0)
    }

    /** Fits a sine of [f] Hz to the middle half of [y] and returns what it leaves, relative to it, in dB. */
    private fun distortionDb(y: FloatArray, rate: Double, f: Double): Double {
        val start = y.size / 4
        val end = 3 * y.size / 4
        var ss = 0.0
        var cc = 0.0
        var sc = 0.0
        var ys = 0.0
        var yc = 0.0
        for (k in start until end) {
            val s = sin(2 * PI * f * k / rate)
            val c = cos(2 * PI * f * k / rate)
            ss += s * s
            cc += c * c
            sc += s * c
            ys += y[k] * s
            yc += y[k] * c
        }
        val det = ss * cc - sc * sc
        val a = (ys * cc - yc * sc) / det
        val b = (yc * ss - ys * sc) / det
        var residual = 0.0
        var signal = 0.0
        for (k in start until end) {
            val model = a * sin(2 * PI * f * k / rate) + b * cos(2 * PI * f * k / rate)
            residual += (y[k] - model) * (y[k] - model)
            signal += model * model
        }
        return 10 * log10(residual / signal)
    }

}

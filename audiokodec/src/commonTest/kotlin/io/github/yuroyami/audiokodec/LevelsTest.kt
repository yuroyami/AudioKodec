package io.github.yuroyami.audiokodec

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checks [Levels] and [Signals] on every target, against signals whose
 * answers are known. Computed values are compared within float rounding,
 * since Kotlin/JS keeps a Float in double precision until it is stored.
 */
class LevelsTest {

    @Test
    fun theSilenceScanFindsTheExactBoundaries() {
        // 1,234 frames of silence, a tone, then 567 frames of silence, in stereo.
        val tone = Signals.sine(10_000, 2, 48_000, 440.0, amplitude = 0.5f, phase = PI / 2)
        val samples = Signals.silence(1_234, 2) + tone + Signals.silence(567, 2)
        val bounds = Levels.findSilence(samples, 2)
        assertEquals(1_234, bounds.soundStart)
        assertEquals(1_234 + 10_000, bounds.soundEnd)
        // Noise below the threshold counts as silence.
        val hiss = Signals.whiteNoise(5_000, 1, amplitude = 0.0009f, seed = 2)
        assertTrue(Levels.findSilence(hiss, 1).isSilent)
        assertTrue(!Levels.findSilence(hiss, 1, thresholdDb = -70.0).isSilent)
    }

    @Test
    fun aGainMovesThePeakByTheAmountAsked() {
        val samples = Signals.sine(4_800, 2, 48_000, 1_000.0, amplitude = 0.25f)
        val before = Levels.scan(samples, 2).peak
        Levels.applyGain(samples, 2, 6.0)
        val after = Levels.scan(samples, 2).peak
        assertEquals(6.0, 20 * log10(after.toDouble() / before), 1e-5)
        // Only the range named is touched.
        val part = FloatArray(10) { 1f }
        Levels.applyGain(part, 1, -20.0, from = 3, frameCount = 4)
        for (i in part.indices) assertEquals(if (i in 3..6) 0.1 else 1.0, part[i].toDouble(), 1e-7, "sample $i")
    }

    @Test
    fun theScanReportsPeakRmsAndClipping() {
        val samples = Signals.sine(48_000, 1, 48_000, 1_000.0, amplitude = 0.5f)
        samples[30_001] = -1.2f
        samples[40_000] = 1f
        val scan = Levels.scan(samples, 1)
        assertEquals(1.2, scan.peak.toDouble(), 1e-6)
        assertEquals(30_001, scan.peakFrame)
        assertEquals(2, scan.clippedSamples)
        assertEquals(30_001, scan.firstClippedFrame)
        val clean = Levels.scan(Signals.sine(48_000, 2, 48_000, 1_000.0, amplitude = 0.5f), 2)
        assertEquals(0.5 / sqrt(2.0), clean.rms, 1e-6)
        assertEquals(0, clean.clippedSamples)
        assertEquals(-1, clean.firstClippedFrame)
    }

    @Test
    fun fadesFollowTheirCurvesAtTheMidpointAndEnds() {
        for (curve in FadeCurve.entries) {
            val fadeIn = Levels.fadeIn(FloatArray(1_000) { 1f }, 1, 0, 1_000, curve)
            val fadeOut = Levels.fadeOut(FloatArray(1_000) { 1f }, 1, 0, 1_000, curve)
            assertEquals(0f, fadeIn[0], "$curve fade in starts silent")
            assertEquals(0f, fadeOut[999], "$curve fade out ends silent")
            val half = if (curve == FadeCurve.LINEAR) 0.5 else 10.0.pow(-30.0 / 20)
            assertEquals(half, fadeIn[500].toDouble(), 1e-6, "$curve fade in at its midpoint")
            assertEquals(half, fadeOut[499].toDouble(), 1e-6, "$curve fade out at its midpoint")
            // The fade out is the fade in reversed.
            assertContentEquals(fadeIn, fadeOut.reversedArray(), "$curve")
            for (k in 1 until 1_000) assertTrue(fadeIn[k] > fadeIn[k - 1], "$curve rises at $k")
        }
    }

    @Test
    fun theGeneratorsMakeWhatTheySay() {
        val square = Signals.square(100, 1, 1_000, 100.0, amplitude = 0.5f)
        assertContentEquals(FloatArray(5) { 0.5f }, square.copyOfRange(0, 5))
        assertContentEquals(FloatArray(5) { -0.5f }, square.copyOfRange(5, 10))
        val sine = Signals.sine(48, 2, 48_000, 1_000.0)
        for (k in 0 until 48) {
            assertEquals(sin(2 * PI * k / 48), sine[2 * k].toDouble(), 1e-7)
            assertEquals(sine[2 * k], sine[2 * k + 1])
        }
        assertContentEquals(Signals.whiteNoise(100, 2, seed = 7), Signals.whiteNoise(100, 2, seed = 7))
        assertTrue(Signals.whiteNoise(10_000, 1, amplitude = 0.25f).all { it >= -0.25f && it <= 0.25f })
        assertContentEquals(FloatArray(6), Signals.silence(3, 2))
    }

    @Test
    fun rangesOutsideTheArrayAreRefused() {
        assertFailsWith<IllegalArgumentException> { Levels.scan(FloatArray(10), 2, from = 4, frameCount = 2) }
        assertFailsWith<IllegalArgumentException> { Levels.fadeIn(FloatArray(10), 1, 5, 6) }
        assertFailsWith<IllegalArgumentException> { Signals.sine(-1, 1, 48_000, 1.0) }
    }

}

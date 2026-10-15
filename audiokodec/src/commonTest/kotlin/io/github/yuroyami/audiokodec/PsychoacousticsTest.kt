package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.psy.Hearing
import io.github.yuroyami.audiokodec.internal.psy.MaskingModel
import io.github.yuroyami.audiokodec.internal.psy.Tonality
import io.github.yuroyami.audiokodec.internal.psy.TransientDetector
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the psychoacoustic toolkit against the published values its curves
 * are fitted to, and its models against what they are for: a tone masks less
 * than noise of the same power, and a burst after quiet is an onset while
 * steady sound is not.
 */
class PsychoacousticsTest {

    @Test
    fun theScalesMatchTheirPublishedValues() {
        // Zwicker and Terhardt: about 8.5 Bark at 1 kHz and 24 at 15 kHz.
        assertEquals(8.51, Hearing.bark(1_000.0), 0.01)
        assertEquals(23.9, Hearing.bark(15_000.0), 0.1)
        // Glasberg and Moore: an ERB of about 133 Hz at 1 kHz, 15.6 ERBs below it.
        assertEquals(132.6, Hearing.erb(1_000.0), 0.1)
        assertEquals(15.6, Hearing.erbRate(1_000.0), 0.05)
        var previous = -1.0
        for (hz in 20..20_000 step 20) {
            val bark = Hearing.bark(hz.toDouble())
            assertTrue(bark > previous, "the Bark scale rises at $hz Hz")
            previous = bark
        }
    }

    @Test
    fun theAbsoluteThresholdIsLowestWhereTheEarIsMostSensitive() {
        var lowest = Double.MAX_VALUE
        var at = 0.0
        for (hz in 20..20_000 step 10) {
            val db = Hearing.absoluteThresholdDb(hz.toDouble())
            if (db < lowest) {
                lowest = db
                at = hz.toDouble()
            }
        }
        assertTrue(at in 3_000.0..4_000.0, "the lowest threshold is at $at Hz")
        assertTrue(lowest < 0.0, "the lowest threshold is $lowest dB SPL")
        assertTrue(Hearing.absoluteThresholdDb(50.0) > 35.0)
        assertTrue(Hearing.absoluteThresholdDb(16_000.0) > 40.0)
    }

    @Test
    fun maskingSpreadsFurtherUpwardsThanDownwards() {
        assertEquals(0.0, Hearing.linearSpreadingDb(0.0), 1e-9)
        assertEquals(-10.0, Hearing.linearSpreadingDb(1.0), 1e-9)
        assertEquals(-27.0, Hearing.linearSpreadingDb(-1.0), 1e-9)
        assertEquals(0.0, Hearing.spreadingDb(0.0), 0.01)
        assertTrue(Hearing.spreadingDb(2.0) > Hearing.spreadingDb(-2.0))
    }

    @Test
    fun aToneMasksLessThanNoiseOfTheSamePower() {
        val bins = 512
        val binHz = 24_000.0 / bins
        val model = MaskingModel(bins, { (it + 0.5) * binHz })
        val tone = FloatArray(bins) { 1e-12f }
        tone[100] = 1f
        var state = 7
        val noise = FloatArray(bins) {
            state = state * 1_103_515_245 + 12_345
            val u = ((state ushr 8) and 0xFFFF) / 65_536.0 + 1e-6
            (-kotlin.math.ln(u)).toFloat()
        }
        // The noise has the tone's power in each partition around bin 100.
        val noiseThreshold = FloatArray(bins)
        val toneThreshold = FloatArray(bins)
        model.threshold(noise, noiseThreshold)
        val noiseRatio = 10 * log10(noise[100] / noiseThreshold[100].toDouble())
        model.threshold(tone, toneThreshold)
        val partition = model.partitionOf(100)
        assertTrue(model.tonality[partition] > 0.9, "a lone tone reads as tonal: ${model.tonality[partition]}")
        val toneRatio = 10 * log10(1.0 / toneThreshold[100])
        assertTrue(toneRatio > noiseRatio + 10, "a tone masks $toneRatio dB down, noise $noiseRatio dB down")
        assertTrue(Tonality.flatnessDb(noise, 0, bins) > -6.0)
        assertTrue(Tonality.flatnessDb(tone, 90, 110) < -30.0)
    }

    @Test
    fun aBurstAfterQuietIsAnOnsetAndSteadySoundIsNot() {
        val rate = 48_000
        val sub = 64
        val frames = sub * 200
        val samples = FloatArray(frames)
        var state = 99
        for (i in 0 until frames) {
            state = state * 1_103_515_245 + 12_345
            val noise = ((state ushr 8) and 0xFFFF) / 32_768f - 1f
            val tone = 0.5f * sin(2 * PI * 440.0 * i / rate).toFloat()
            // Steady tone and quiet noise, then a loud burst at sub-block 150.
            samples[i] = tone + noise * if (i >= sub * 150) 0.3f else 0.003f
        }
        val detector = TransientDetector(1, rate, sub)
        val onsets = (0 until 200).filter { detector.next(arrayOf(samples), it * sub) }
        // The burst stays far above the average of the quiet before it for a
        // sub-block or two after it starts, and nothing else rises at all.
        assertEquals(150, onsets.first(), "onsets at $onsets")
        assertTrue(onsets.all { it in 150..152 }, "onsets at $onsets")
    }
}

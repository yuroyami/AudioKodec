package io.github.yuroyami.audiokodec

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checks [LoudnessMeter] on every target against the signals of EBU Tech
 * 3341, whose loudness is known, and against the cases that tell its figures
 * apart.
 */
class LoudnessTest {

    private fun tone(rate: Int, channels: Int, seconds: Double, db: Double, f: Double = 1_000.0) =
        FloatArray((rate * seconds).toInt() * channels) { i -> (10.0.pow(db / 20) * sin(2 * PI * f * (i / channels) / rate)).toFloat() }

    @Test
    fun aTechnicalReferenceToneMeasuresAtItsLevel() {
        // EBU Tech 3341, case 1 and 2: a 1 kHz sine in both channels at -23 and -33 dBFS reads -23 and -33 LUFS.
        for (db in listOf(-23.0, -33.0)) {
            for (rate in listOf(44_100, 48_000)) {
                val meter = LoudnessMeter(rate, 2, measureTruePeak = false)
                meter.add(tone(rate, 2, 6.0, db))
                assertEquals(db, meter.integratedLoudness, 0.1, "$db dBFS at $rate Hz")
                assertEquals(db, meter.momentaryLoudness, 0.1)
                assertEquals(db, meter.shortTermLoudness, 0.1)
                assertEquals(0.0, meter.loudnessRange, 0.1)
            }
        }
    }

    @Test
    fun theGatesLeaveOutSilenceAndQuietPassages() {
        // EBU Tech 3341, case 3, shortened: -36 dBFS, then -23 for six times as long, then -36 again reads -23 LUFS,
        // the quiet ends gated away.
        val rate = 48_000
        val meter = LoudnessMeter(rate, 2, measureTruePeak = false)
        meter.add(tone(rate, 2, 2.0, -36.0))
        meter.add(tone(rate, 2, 12.0, -23.0))
        meter.add(tone(rate, 2, 2.0, -36.0))
        assertEquals(-23.0, meter.integratedLoudness, 0.1)
        // Silence alone is below the absolute gate.
        val silent = LoudnessMeter(rate, 1)
        assertFailsWith<IllegalStateException> { LoudnessMeter(rate, 1, measureTruePeak = false).truePeak(0) }
        silent.add(FloatArray(rate * 2))
        assertEquals(Double.NEGATIVE_INFINITY, silent.integratedLoudness)
        assertNull(silent.replayGain())
    }

    @Test
    fun surroundChannelsAreWeightedAsBs1770Weighs() {
        // EBU Tech 3341, case 6: 1 kHz in 5.0 at -28 dBFS in L and R, -24 in C and -30 in Ls and Rs reads -23 LUFS.
        val rate = 48_000
        val levels = doubleArrayOf(-28.0, -28.0, -24.0, -30.0, -30.0)
        val samples = FloatArray(rate * 6 * 5) { i -> (10.0.pow(levels[i % 5] / 20) * sin(2 * PI * 1_000.0 * (i / 5) / rate)).toFloat() }
        val meter = LoudnessMeter(rate, ChannelLayout.SURROUND_5_0, measureTruePeak = false)
        meter.add(samples)
        assertEquals(-23.0, meter.integratedLoudness, 0.1)
    }

    @Test
    fun theLoudnessRangeOfTwoLevelsIsTheirDifference() {
        // EBU Tech 3342, case 1: 20 s at -20 dBFS then 20 s at -30 reads a range of 10 LU.
        val rate = 48_000
        val meter = LoudnessMeter(rate, 2, measureTruePeak = false)
        meter.add(tone(rate, 2, 20.0, -20.0))
        meter.add(tone(rate, 2, 20.0, -30.0))
        assertEquals(10.0, meter.loudnessRange, 0.1)
    }

    @Test
    fun replayGainBringsATrackToMinusEighteen() {
        val rate = 44_100
        val loud = LoudnessMeter(rate, 2).also { it.add(tone(rate, 2, 10.0, -13.0)); it.finish() }
        val quiet = LoudnessMeter(rate, 2).also { it.add(tone(rate, 2, 10.0, -33.0)); it.finish() }
        val gain = loud.replayGain()!!
        assertEquals(-18.0 - loud.integratedLoudness, gain.trackGainDb!!, 1e-9)
        assertEquals(-5.0, gain.trackGainDb!!, 0.1)
        assertEquals(10.0.pow(-13.0 / 20), gain.trackPeak!!, 0.002)
        val album = LoudnessMeter.albumReplayGain(listOf(loud, quiet))
        // The album is gated as one programme: the quiet track falls below the relative gate.
        assertEquals(-5.0, album[0]!!.albumGainDb!!, 0.1)
        assertEquals(album[0]!!.albumGainDb, album[1]!!.albumGainDb)
        assertEquals(15.0, album[1]!!.trackGainDb!!, 0.1)
        assertEquals(gain.trackPeak, album[1]!!.albumPeak)
    }

    @Test
    fun aScanAndATagWriteComposeIntoOne() {
        val format = AudioFormat(44_100, 2)
        val flac = AudioKodec.encode(TestSignals.quantise(tone(44_100, 2, 5.0, -20.0), 16), format, EncodeTarget.flac(16))
        val decoded = AudioKodec.decode(flac)
        val meter = LoudnessMeter(44_100, decoded.format.channelLayout)
        meter.add(decoded.samples)
        meter.finish()
        val measured = meter.replayGain()!!
        val written = AudioKodec.writeTags(flac, AudioKodec.readTags(flac).edit { setReplayGain(measured) })
        val read = AudioKodec.readTags(written).replayGain!!
        assertEquals(measured.trackGainDb!!, read.trackGainDb!!, 0.01)
        assertEquals(measured.trackPeak!!, read.trackPeak!!, 1e-6)
    }

    @Test
    fun aPeakBetweenSamplesIsFoundOnlyByTheTruePeak() {
        // A quarter of the sample rate, a quarter period out of phase: every sample lands at 0.7071 of the crest.
        // It fades in and out over 100 ms, so that no abrupt edge adds an overshoot of its own.
        val rate = 48_000
        val fade = rate / 10
        val meter = LoudnessMeter(rate, 1)
        meter.add(
            FloatArray(rate) {
                val edge = minOf(it, rate - 1 - it)
                val envelope = if (edge >= fade) 1.0 else sin(PI / 2 * edge / fade).let { s -> s * s }
                (0.9 * envelope * sin(2 * PI * it / 4 + PI / 4)).toFloat()
            },
        )
        meter.finish()
        assertEquals(0.9 * 0.70710678, meter.samplePeak(0), 1e-6)
        assertTrue(meter.truePeak(0) > 0.89, "true peak ${meter.truePeak(0)}")
        assertEquals(0.9, meter.truePeak(0), 0.001)
    }
}

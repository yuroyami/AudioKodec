package io.github.yuroyami.audiokodec.sample

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkTest {

    @Test
    fun theMedianOfAnOddCountIsTheMiddleAndOfAnEvenCountTheMeanOfTheMiddleTwo() {
        assertEquals(3.0, median(listOf(9.0, 1.0, 3.0)))
        assertEquals(2.5, median(listOf(4.0, 1.0, 3.0, 2.0)))
        assertEquals(7.0, median(listOf(7.0)))
    }

    @Test
    fun aRunBelowItsFloorIsMissedAndOneAboveIsNot() {
        val slow = Result("slow", 100.0, audioSeconds = 30.0, medianSeconds = 0.5, bestSeconds = 0.4)
        val fast = Result("fast", 100.0, audioSeconds = 30.0, medianSeconds = 0.25, bestSeconds = 0.2)
        val unbound = Result("free", null, audioSeconds = 30.0, medianSeconds = 30.0, bestSeconds = 30.0)
        assertEquals(60.0, slow.multiple)
        assertTrue(slow.missed)
        assertFalse(fast.missed)
        assertFalse(unbound.missed)
        val rows = table(listOf(slow, fast, unbound)).lines()
        assertTrue(rows.any { it.startsWith("| slow ") && it.endsWith("100x MISSED |") }, rows.joinToString("\n"))
        assertTrue(rows.any { it.startsWith("| fast ") && it.endsWith("100x |") })
        assertTrue(rows.any { it.startsWith("| free ") && it.endsWith("none |") })
    }

    @Test
    fun timingDiscardsTheWarmUpsAndRunsTheMeasuredOnesEachOnce() {
        var calls = 0
        val (median, best) = time(warmups = 2, runs = 5) { calls++ }
        assertEquals(7, calls)
        assertTrue(best <= median)
    }

    @Test
    fun theTestSignalIsTheSameEveryTimeAndStaysInRange() {
        val first = TestSignal.stereo(1, 8_000)
        assertEquals(16_000, first.size)
        assertContentEquals(first, TestSignal.stereo(1, 8_000))
        assertTrue(first.all { it in -0.98f..0.98f })
        // The channels differ, which joint stereo needs to have something to decide.
        assertTrue((0 until 8_000).any { first[2 * it] != first[2 * it + 1] })
    }

    @Test
    fun decodingAFileReportsEveryFrame() {
        val format = io.github.yuroyami.audiokodec.AudioFormat(8_000, 2)
        val wav = io.github.yuroyami.audiokodec.AudioKodec.encode(
            TestSignal.stereo(2, 8_000), format, io.github.yuroyami.audiokodec.EncodeTarget.wav(16),
        )
        assertEquals(16_000L, decodeAll(wav))
    }
}

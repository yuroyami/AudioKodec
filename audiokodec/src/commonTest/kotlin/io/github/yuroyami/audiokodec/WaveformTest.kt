package io.github.yuroyami.audiokodec

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Checks [WaveformReducer] on every target, against signals whose buckets are known. */
class WaveformTest {

    @Test
    fun aRampReducesToTheBucketsItsShapeGives() {
        // A ramp of 0, 1, 2 ... in the first channel and its negative in the second, ten frames a bucket.
        val frames = 95
        val samples = FloatArray(frames * 2) { i -> if (i % 2 == 0) (i / 2).toFloat() else -(i / 2).toFloat() }
        val (min, max, rms) = reduce(samples, 2, 10, listOf(frames))
        assertEquals(10L, WaveformReducer.bucketCount(frames.toLong(), 10))
        assertEquals(20, min.size)
        for (b in 0 until 10) {
            val first = 10 * b
            val last = minOf(first + 9, frames - 1)
            val n = last - first + 1
            val meanSquare = (first..last).sumOf { it.toDouble() * it } / n
            assertEquals(first.toFloat(), min[2 * b])
            assertEquals(last.toFloat(), max[2 * b])
            assertEquals(-last.toFloat(), min[2 * b + 1])
            assertEquals(-first.toFloat(), max[2 * b + 1])
            // Within float rounding: Kotlin/JS keeps a Float's value in double precision until it is stored.
            assertEquals(sqrt(meanSquare), rms[2 * b].toDouble(), 1e-5)
            assertEquals(sqrt(meanSquare), rms[2 * b + 1].toDouble(), 1e-5)
        }
    }

    @Test
    fun aToneBurstShowsWhereItStartsAndStops() {
        // Silence, a full-scale sine of whole periods across buckets 3 to 6, then silence.
        val perBucket = 480
        val samples = FloatArray(perBucket * 10) { k ->
            if (k in 3 * perBucket until 7 * perBucket) sin(2 * PI * k / 48).toFloat() else 0f
        }
        val (min, max, rms) = reduce(samples, 1, perBucket, listOf(samples.size))
        for (b in 0 until 10) {
            if (b in 3..6) {
                assertEquals(-1f, min[b], 1e-6f)
                assertEquals(1f, max[b], 1e-6f)
                assertEquals(sqrt(0.5).toFloat(), rms[b], 1e-6f)
            } else {
                assertEquals(0f, min[b])
                assertEquals(0f, max[b])
                assertEquals(0f, rms[b])
            }
        }
    }

    @Test
    fun manySmallBlocksGiveExactlyTheBucketsOfOne() {
        val random = Random(3)
        val samples = FloatArray(3 * 10_007) { random.nextFloat() * 2 - 1 }
        val whole = reduce(samples, 3, 441, listOf(10_007))
        val sizes = ArrayList<Int>()
        var left = 10_007
        while (left > 0) {
            val n = minOf(left, random.nextInt(0, 900))
            sizes.add(n)
            left -= n
        }
        val pieces = reduce(samples, 3, 441, sizes)
        assertContentEquals(whole.first, pieces.first)
        assertContentEquals(whole.second, pieces.second)
        assertContentEquals(whole.third, pieces.third)
    }

    @Test
    fun misuseIsRefused() {
        assertFailsWith<IllegalArgumentException> { WaveformReducer(0, 10) }
        assertFailsWith<IllegalArgumentException> { WaveformReducer(2, 0) }
        val reducer = WaveformReducer(2, 10)
        assertFailsWith<IllegalArgumentException> { reducer.push(FloatArray(10), 0, 6) }
        reducer.finish()
        assertFailsWith<IllegalStateException> { reducer.push(FloatArray(2)) }
        assertEquals(0, reducer.availableBuckets)
    }

    /** Pushes [samples] in blocks of [sizes] frames, pulling as it goes, and returns every bucket. */
    private fun reduce(samples: FloatArray, channels: Int, perBucket: Int, sizes: List<Int>): Triple<FloatArray, FloatArray, FloatArray> {
        val frames = samples.size / channels
        val count = WaveformReducer.bucketCount(frames.toLong(), perBucket).toInt()
        val min = FloatArray(count * channels)
        val max = FloatArray(count * channels)
        val rms = FloatArray(count * channels)
        val reducer = WaveformReducer(channels, perBucket)
        var at = 0
        var pulled = 0
        for (n in sizes) {
            reducer.push(samples, at, n)
            at += n
            pulled += reducer.pull(min, max, rms, pulled)
        }
        reducer.finish()
        pulled += reducer.pull(min, max, rms, pulled)
        assertEquals(count, pulled)
        return Triple(min, max, rms)
    }
}

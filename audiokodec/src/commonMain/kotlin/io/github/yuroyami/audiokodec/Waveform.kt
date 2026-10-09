package io.github.yuroyami.audiokodec

import kotlin.math.sqrt

/**
 * Reduces a stream of interleaved float PCM to the per-bucket minimum,
 * maximum and root mean square a waveform is drawn from, a block at a time.
 *
 * Push decoded blocks in with [push], take finished buckets out with [pull],
 * and call [finish] at the end of the stream for the last, partly filled
 * bucket. Each bucket covers [framesPerBucket] frames and is reported per
 * channel. Memory stays bounded however long the stream: only the bucket
 * being filled and the finished ones not yet pulled are held, and once the
 * holding space has grown to fit the largest block pushed, nothing more is
 * allocated.
 *
 * The figures for a bucket do not depend on how the audio was split into
 * blocks: one block or many give identical buckets.
 */
public class WaveformReducer(
    public val channels: Int,
    public val framesPerBucket: Int,
) {
    init {
        require(channels in 1..MAX_CHANNELS) { "channels must be in 1..$MAX_CHANNELS, was $channels" }
        require(framesPerBucket > 0) { "framesPerBucket must be positive, was $framesPerBucket" }
    }

    private val min = FloatArray(channels)
    private val max = FloatArray(channels)
    private val squares = DoubleArray(channels)
    private var filled = 0

    /** Finished buckets not yet pulled: minima, maxima and RMS, each by bucket then channel. */
    private var readyMin = FloatArray(0)
    private var readyMax = FloatArray(0)
    private var readyRms = FloatArray(0)
    private var ready = 0
    private var finished = false

    /** How many finished buckets [pull] can take now. */
    public val availableBuckets: Int get() = ready

    init {
        clearBucket()
    }

    /** Adds [frameCount] frames of interleaved [samples], from frame [offset]. */
    public fun push(samples: FloatArray, offset: Int = 0, frameCount: Int = samples.size / channels - offset) {
        check(!finished) { "this reducer was finished" }
        require(offset >= 0 && frameCount >= 0) { "offset and frameCount must not be negative" }
        require(offset.toLong() + frameCount <= samples.size.toLong() / channels) {
            "samples holds ${samples.size / channels} frames, short of ${offset.toLong() + frameCount}"
        }
        // Room for every bucket this block can finish.
        reserve(ready + (filled + frameCount) / framesPerBucket)
        var i = offset * channels
        for (f in 0 until frameCount) {
            for (c in 0 until channels) {
                val v = samples[i++]
                if (v < min[c]) min[c] = v
                if (v > max[c]) max[c] = v
                squares[c] += v.toDouble() * v
            }
            if (++filled == framesPerBucket) emit()
        }
    }

    /**
     * Ends the stream: a partly filled bucket becomes the last one, its RMS
     * taken over the frames it holds.
     */
    public fun finish() {
        check(!finished) { "this reducer was already finished" }
        finished = true
        if (filled > 0) {
            reserve(ready + 1)
            emit()
        }
    }

    /**
     * Moves up to [maxBuckets] finished buckets into [min], [max] and [rms],
     * each by bucket then channel from bucket [offset], and returns how many.
     */
    public fun pull(min: FloatArray, max: FloatArray, rms: FloatArray, offset: Int = 0, maxBuckets: Int = Int.MAX_VALUE): Int {
        require(offset >= 0 && maxBuckets >= 0) { "offset and maxBuckets must not be negative" }
        val room = minOf(min.size, max.size, rms.size) / channels - offset
        val count = minOf(ready, maxBuckets, maxOf(room, 0))
        val n = count * channels
        readyMin.copyInto(min, offset * channels, 0, n)
        readyMax.copyInto(max, offset * channels, 0, n)
        readyRms.copyInto(rms, offset * channels, 0, n)
        readyMin.copyInto(readyMin, 0, n, ready * channels)
        readyMax.copyInto(readyMax, 0, n, ready * channels)
        readyRms.copyInto(readyRms, 0, n, ready * channels)
        ready -= count
        return count
    }

    /** Forgets the stream so far, ready for another. */
    public fun reset() {
        clearBucket()
        ready = 0
        finished = false
    }

    private fun emit() {
        val at = ready * channels
        for (c in 0 until channels) {
            readyMin[at + c] = min[c]
            readyMax[at + c] = max[c]
            readyRms[at + c] = sqrt(squares[c] / filled).toFloat()
        }
        ready++
        clearBucket()
    }

    private fun clearBucket() {
        min.fill(Float.POSITIVE_INFINITY)
        max.fill(Float.NEGATIVE_INFINITY)
        squares.fill(0.0)
        filled = 0
    }

    private fun reserve(buckets: Int) {
        val needed = buckets.toLong() * channels
        if (needed <= readyMin.size) return
        require(needed <= Int.MAX_VALUE) { "too many buckets are waiting to be pulled" }
        val size = maxOf(needed, readyMin.size * 2L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        readyMin = readyMin.copyOf(size)
        readyMax = readyMax.copyOf(size)
        readyRms = readyRms.copyOf(size)
    }

    public companion object {
        private const val MAX_CHANNELS = 4096

        /**
         * How many buckets a stream of [totalFrames] frames reduces to, the
         * last one partly filled, so a canvas can be sized before any audio
         * is read. [AudioInfo.durationFrames] gives the frame count of a file.
         */
        public fun bucketCount(totalFrames: Long, framesPerBucket: Int): Long {
            require(totalFrames >= 0) { "totalFrames must not be negative, was $totalFrames" }
            require(framesPerBucket > 0) { "framesPerBucket must be positive, was $framesPerBucket" }
            return (totalFrames + framesPerBucket - 1) / framesPerBucket
        }
    }
}

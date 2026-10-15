package io.github.yuroyami.audiokodec.sample

import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.WaveformReducer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The figures a waveform is drawn from: for each of [buckets] columns and
 * each channel, the lowest and highest sample and the root mean square.
 * Arrays run by bucket, then channel, as [WaveformReducer] gives them.
 */
internal class Peaks(
    val channels: Int,
    val buckets: Int,
    val min: FloatArray,
    val max: FloatArray,
    val rms: FloatArray,
    /** Frames decoded, which is the length the waveform spans. */
    val totalFrames: Long,
) {
    init {
        require(min.size == buckets * channels && max.size == min.size && rms.size == min.size)
    }
}

/**
 * Decodes all of [decoder] once and reduces it to at most [width] buckets.
 *
 * The decoder streams, so memory stays bounded by [width] whatever the length
 * of the file. When the duration is known, each bucket covers an equal share
 * of it; when it is not, the audio is first reduced at a tenth of a second a
 * bucket and those buckets are then merged down to [width]. [cancelled] is
 * checked between blocks, and a cancelled reduction returns what it has so
 * far. The decoder is not closed.
 */
internal fun waveformPeaks(decoder: AudioDecoder, width: Int, cancelled: () -> Boolean = { false }): Peaks {
    require(width > 0) { "width must be positive, was $width" }
    val channels = decoder.format.channels
    val declared = decoder.durationFrames
    val perBucket = if (declared != null && declared > 0) {
        ((declared + width - 1) / width).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    } else {
        maxOf(1, decoder.format.sampleRate / 10)
    }
    val reducer = WaveformReducer(channels, perBucket)
    var capacity = if (declared != null) WaveformReducer.bucketCount(declared, perBucket).toInt().coerceAtLeast(16) else 1024
    var lo = FloatArray(capacity * channels)
    var hi = FloatArray(capacity * channels)
    var rms = FloatArray(capacity * channels)
    var count = 0
    var frames = 0L

    fun drain() {
        while (reducer.availableBuckets > 0) {
            if (count == capacity) {
                // The file ran longer than it said, or it said nothing.
                capacity *= 2
                lo = lo.copyOf(capacity * channels)
                hi = hi.copyOf(capacity * channels)
                rms = rms.copyOf(capacity * channels)
            }
            count += reducer.pull(lo, hi, rms, count)
        }
    }

    while (!cancelled()) {
        val block = decoder.nextFrame() ?: break
        reducer.push(block.samples, 0, block.frameCount)
        frames += block.frameCount
        drain()
    }
    reducer.finish()
    drain()
    return merge(channels, count, lo, hi, rms, width, frames)
}

/** Folds [count] buckets into at most [width], each output bucket taking whole input buckets. */
private fun merge(channels: Int, count: Int, lo: FloatArray, hi: FloatArray, rms: FloatArray, width: Int, frames: Long): Peaks {
    if (count <= width) {
        val n = count * channels
        return Peaks(channels, count, lo.copyOf(n), hi.copyOf(n), rms.copyOf(n), frames)
    }
    val group = (count + width - 1) / width
    val out = (count + group - 1) / group
    val mLo = FloatArray(out * channels) { Float.POSITIVE_INFINITY }
    val mHi = FloatArray(out * channels) { Float.NEGATIVE_INFINITY }
    val squares = DoubleArray(out * channels)
    val members = IntArray(out)
    for (b in 0 until count) {
        val o = b / group
        members[o]++
        for (c in 0 until channels) {
            val i = b * channels + c
            val j = o * channels + c
            mLo[j] = min(mLo[j], lo[i])
            mHi[j] = max(mHi[j], hi[i])
            squares[j] += rms[i].toDouble() * rms[i]
        }
    }
    // Every merged bucket but the last holds equal shares, so the mean of the squares is the RMS of the frames.
    val mRms = FloatArray(out * channels) { sqrt(squares[it] / members[it / channels]).toFloat() }
    return Peaks(channels, out, mLo, mHi, mRms, frames)
}

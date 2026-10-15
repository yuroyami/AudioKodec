package io.github.yuroyami.audiokodec.internal.psy

import kotlin.math.ln
import kotlin.math.pow

/**
 * Estimates how much noise each bin of a power spectrum could hide.
 *
 * This is the classic model of Johnston (1988), in the form most perceptual
 * coders since have used:
 *
 * 1. The bins are grouped into partitions a fraction of a Bark wide, so that
 *    each group is about as wide as the ear's own resolution.
 * 2. Each partition is a masker. How well it masks depends on whether it is
 *    tonal or noisy, which [Tonality] estimates from how flat the spectrum
 *    around it is: noise hides noise a few dB below itself, while a pure tone
 *    hides only noise far below it.
 * 3. Each masker's energy is spread over its neighbours with a spreading
 *    function, [Hearing.linearSpreadingDb] unless the caller chooses
 *    another, and the contributions are added.
 * 4. The sum is divided back down so that a flat spectrum masks at the level
 *    the offsets say, per bin, and the absolute threshold sets a minimum.
 *
 * The result, per bin, is a noise power below which a coder's error should be
 * inaudible, in the same units as the spectrum it was given. Nothing here
 * knows about any one codec: a codec supplies the spectrum, the bins'
 * frequencies and its own idea of the absolute threshold in its own units,
 * and decides itself how close to the threshold to work.
 */
internal class MaskingModel(
    /** Bins in each spectrum given to [threshold]. */
    val bins: Int,
    /** The centre frequency of each bin, in Hz. */
    binHz: (Int) -> Double,
    /** Width of one partition, in Bark. */
    partitionBark: Double = 1.0 / 3.0,
    /** How far below a tonal masker noise is hidden: this many dB... */
    private val toneOffsetDb: Double = 14.5,
    /** ...plus this many dB for every Bark of the masker's frequency. */
    private val toneOffsetPerBarkDb: Double = 1.0,
    /** How far below a noise masker noise is hidden, in dB. */
    private val noiseOffsetDb: Double = 5.5,
    /** Contributions spread further than this many dB down are ignored. */
    spreadFloorDb: Double = -60.0,
    /** How far, in dB, a masker reaches a given number of Bark above it (negative for below). */
    spreading: (Double) -> Double = { Hearing.linearSpreadingDb(it) },
    /**
     * The least signal-to-noise ratio, in dB, a tonal partition at the given
     * Bark is held to whatever its neighbours mask, like the `minval` table
     * of the MPEG-1 psychoacoustic model 2, and scaled down for a partition
     * that is partly noise. Low notes need it: the model's partitions there
     * are wider than the spacing of the notes, so it cannot see the gaps
     * between them, and the ear can.
     */
    minimumSnrDb: (Double) -> Double = { 0.0 },
) {
    init {
        require(bins > 0) { "a masking model needs at least one bin" }
    }

    private val partitionStart: IntArray
    private val partitionEnd: IntArray
    private val partitionBarks: DoubleArray

    init {
        val starts = ArrayList<Int>()
        val barks = ArrayList<Double>()
        var start = 0
        while (start < bins) {
            val startBark = Hearing.bark(binHz(start))
            var end = start + 1
            while (end < bins && Hearing.bark(binHz(end)) - startBark < partitionBark) end++
            starts.add(start)
            barks.add(Hearing.bark(binHz((start + end - 1) / 2)))
            start = end
        }
        partitionStart = starts.toIntArray()
        partitionEnd = IntArray(starts.size) { if (it + 1 < starts.size) starts[it + 1] else bins }
        partitionBarks = barks.toDoubleArray()
    }

    /** How many partitions the bins were grouped into. */
    val partitions: Int get() = partitionStart.size

    // spread[p * partitions + j] is the linear weight masker j gives maskee p,
    // or zero below the floor; normalisation[p] is the sum a flat spectrum
    // would collect at p, which the result is divided by.
    private val spread = FloatArray(partitions * partitions)
    private val normalisation = DoubleArray(partitions)
    private val spreadFrom = IntArray(partitions)
    private val spreadTo = IntArray(partitions)

    init {
        for (p in 0 until partitions) {
            var sum = 0.0
            var from = partitions
            var to = 0
            for (j in 0 until partitions) {
                val db = spreading(partitionBarks[p] - partitionBarks[j])
                if (db < spreadFloorDb) continue
                val weight = 10.0.pow(db / 10.0)
                spread[p * partitions + j] = weight.toFloat()
                sum += weight
                if (j < from) from = j
                to = j + 1
            }
            normalisation[p] = sum
            spreadFrom[p] = from
            spreadTo[p] = to
        }
    }

    private val minimumSnr = DoubleArray(partitions) { minimumSnrDb(partitionBarks[it]) }

    private val energy = DoubleArray(partitions)
    private val masker = DoubleArray(partitions)

    /** The tonality of each partition in the last spectrum given to [threshold], 0 for noise and 1 for a tone. */
    val tonality: DoubleArray = DoubleArray(partitions)

    /** The partition each bin belongs to. */
    fun partitionOf(bin: Int): Int {
        var low = 0
        var high = partitions - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (partitionStart[middle] <= bin) low = middle else high = middle - 1
        }
        return low
    }

    /**
     * Fills [out] with the masked threshold of [power] per bin, never below
     * [absolute] where that is given.
     */
    fun threshold(power: FloatArray, out: FloatArray, absolute: FloatArray? = null) {
        require(power.size >= bins && out.size >= bins) { "the spectra need $bins bins" }
        for (p in 0 until partitions) {
            var sum = 0.0
            for (k in partitionStart[p] until partitionEnd[p]) sum += power[k]
            energy[p] = sum
            val width = partitionEnd[p] - partitionStart[p]
            val reach = maxOf(TONALITY_REACH, width)
            val centre = (partitionStart[p] + partitionEnd[p]) / 2
            val flatness = Tonality.flatnessDb(
                power,
                maxOf(0, centre - reach),
                minOf(bins, centre + reach + 1),
            )
            val tone = Tonality.fromFlatness(flatness)
            tonality[p] = tone
            val offset = tone * (toneOffsetDb + toneOffsetPerBarkDb * partitionBarks[p]) +
                (1.0 - tone) * noiseOffsetDb
            masker[p] = sum * 10.0.pow(-offset / 10.0)
        }
        for (p in 0 until partitions) {
            var sum = 0.0
            val row = p * partitions
            for (j in spreadFrom[p] until spreadTo[p]) sum += masker[j] * spread[row + j]
            val width = partitionEnd[p] - partitionStart[p]
            val ceiling = energy[p] * 10.0.pow(-tonality[p] * minimumSnr[p] / 10.0)
            val perBin = minOf(sum / normalisation[p], ceiling) / width
            for (k in partitionStart[p] until partitionEnd[p]) {
                val floor = absolute?.get(k) ?: 0f
                out[k] = maxOf(perBin.toFloat(), floor)
            }
        }
    }

    private companion object {
        /** Bins either side of a partition's centre that its tonality is judged over, at least. */
        const val TONALITY_REACH = 4
    }
}

/**
 * Judges whether a stretch of spectrum is a tone or noise.
 *
 * The measure is spectral flatness: the geometric mean of the power over the
 * arithmetic mean, Johnston's choice. Noise spreads its power over every bin
 * and scores near one, a pure tone puts it all in a couple of bins and scores
 * near zero.
 */
internal object Tonality {

    /** The spectral flatness of `power[from until to]`, in dB, never above zero. */
    fun flatnessDb(power: FloatArray, from: Int, to: Int): Double {
        if (to <= from) return 0.0
        var logSum = 0.0
        var sum = 0.0
        for (k in from until to) {
            val value = power[k].toDouble() + TINY
            logSum += ln(value)
            sum += value
        }
        val count = (to - from).toDouble()
        val geometric = logSum / count
        val arithmetic = ln(sum / count)
        return minOf(0.0, 10.0 * (geometric - arithmetic) / ln(10.0))
    }

    /**
     * Maps a flatness in dB to a tonality between 0, noise, and 1, a tone.
     *
     * The flatness of real noise measured over a few bins is not 0 dB but a
     * few dB below, because the power in each bin scatters; [noiseDb] is
     * where noise lands, and [toneDb] the flatness from which on a stretch is
     * treated as wholly tonal.
     */
    fun fromFlatness(flatnessDb: Double, noiseDb: Double = -3.0, toneDb: Double = -30.0): Double =
        ((flatnessDb - noiseDb) / (toneDb - noiseDb)).coerceIn(0.0, 1.0)

    private const val TINY = 1e-30
}

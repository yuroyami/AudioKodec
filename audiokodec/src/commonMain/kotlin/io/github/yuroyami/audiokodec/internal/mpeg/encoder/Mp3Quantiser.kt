package io.github.yuroyami.audiokodec.internal.mpeg.encoder

import io.github.yuroyami.audiokodec.internal.mpeg.Mp3Tables
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow

/**
 * Everything the side information and main data say about one granule of one
 * channel, as the quantiser leaves it.
 */
internal class Mp3CodedGranule {
    /** The quantised spectrum, signed, in bitstream order. */
    val values = IntArray(576)

    /**
     * Scalefactors: long bands 0 to 21 at their own index, or short band
     * `b` window `w` at `b * 3 + w`.
     */
    val scalefactors = IntArray(39)

    var blockType = 0
    var globalGain = 210
    var scalefacScale = 0
    var preflag = false
    var scalefacCompress = 0
    var bigValues = 0
    var count1End = 0
    var count1Table = 0
    val tableSelect = IntArray(3)
    var region0Count = 0
    var region1Count = 0

    /** Bits of scalefactors, which depend on the scalefactor selection bits too. */
    var part2Bits = 0

    /** Bits of Huffman coded spectrum. */
    var part3Bits = 0

    val part2And3Bits: Int get() = part2Bits + part3Bits

    fun copyFrom(other: Mp3CodedGranule) {
        other.values.copyInto(values)
        other.scalefactors.copyInto(scalefactors)
        blockType = other.blockType
        globalGain = other.globalGain
        scalefacScale = other.scalefacScale
        preflag = other.preflag
        scalefacCompress = other.scalefacCompress
        bigValues = other.bigValues
        count1End = other.count1End
        count1Table = other.count1Table
        other.tableSelect.copyInto(tableSelect)
        region0Count = other.region0Count
        region1Count = other.region1Count
        part2Bits = other.part2Bits
        part3Bits = other.part3Bits
    }
}

/**
 * Quantises a granule's spectrum within a budget of bits, as Annex C of
 * ISO/IEC 11172-3 describes.
 *
 * Layer III quantises every spectral value with the same power law, raising
 * it to three quarters and rounding, and controls where the noise goes with
 * one global step size and a scalefactor per band that makes that band's step
 * finer. Annex C finds both with two nested loops.
 *
 * The inner loop, the rate loop, finds the finest global step whose Huffman
 * coded spectrum fits the bits available. The outer loop, the distortion
 * loop, then measures each band's noise against what the psychoacoustic model
 * allows there, makes the step finer in every band that is over, and runs the
 * inner loop again, which coarsens the global step to pay for it. It stops
 * when no band is over, when every band has been amplified (which is the same
 * as having amplified none), or when a scalefactor would outgrow its field;
 * the best pass seen is the one kept. Before giving up on a scalefactor that
 * has grown too large, the loop doubles every scalefactor's step with
 * `scalefac_scale` and carries on.
 *
 * The rate loop counts bits with a fixed division into Huffman regions; once
 * the loops are done, [finish] chooses the division and the tables that cost
 * least, which only ever gives bits back.
 *
 * For variable bitrate there is no budget to fill, so [quantiseToTarget]
 * instead finds for every band the coarsest step whose noise stays within
 * what is allowed, which is the distortion loop's goal reached directly, and
 * derives the global step and scalefactors from those.
 */
internal class Mp3Quantiser(sampleRate: Int) {

    private val longBands = Mp3Tables.longBands(sampleRate)
    private val shortBands = Mp3Tables.shortBands(sampleRate)

    private val magnitude = DoubleArray(576)
    private val magnitude34 = DoubleArray(576)
    private val signs = BooleanArray(576)

    /** Coding bands: long bands, or short band and window pairs. */
    private var bands = 0
    private val bandStart = IntArray(39)
    private val bandEnd = IntArray(39)
    private val bandLimit = IntArray(39)
    private val bandPretab = IntArray(39)
    private val allowed = DoubleArray(39)
    private val noise = DoubleArray(39)

    private var blockType = 0
    private val trial = Mp3CodedGranule()
    private val best = Mp3CodedGranule()

    /**
     * Sets up a granule: its spectrum [xr] in bitstream order, its block type,
     * and the noise each band may carry, [allowedNoise] in the coding band
     * order described at [Mp3CodedGranule.scalefactors].
     */
    fun prepare(xr: DoubleArray, type: Int, allowedNoise: DoubleArray) {
        blockType = type
        for (i in 0 until 576) {
            val value = xr[i]
            signs[i] = value < 0
            val m = abs(value)
            magnitude[i] = m
            magnitude34[i] = if (m == 0.0) 0.0 else m.pow(0.75)
        }
        if (type == Mp3ForwardTransform.SHORT) {
            bands = 39
            for (band in 0 until 13) {
                val width = shortBands[band + 1] - shortBands[band]
                for (window in 0 until 3) {
                    val index = band * 3 + window
                    bandStart[index] = shortBands[band] * 3 + window * width
                    bandEnd[index] = bandStart[index] + width
                    bandLimit[index] = when {
                        band < 6 -> 15
                        band < 12 -> 7
                        else -> 0
                    }
                    bandPretab[index] = 0
                }
            }
        } else {
            bands = 22
            for (band in 0 until 22) {
                bandStart[band] = longBands[band]
                bandEnd[band] = longBands[band + 1]
                bandLimit[band] = when {
                    band < 11 -> 15
                    band < 21 -> 7
                    else -> 0
                }
                bandPretab[band] = Mp3Tables.PRETAB[band]
            }
        }
        for (band in 0 until bands) {
            allowed[band] = maxOf(allowedNoise[band], 1e-30)
            var largest = 0.0
            for (i in bandStart[band] until bandEnd[band]) largest = maxOf(largest, magnitude34[i])
            // The finest step that still codes the largest value: from
            // largest * 2^(-3q/16) < MAX_VALUE.
            val bound = if (largest > 0) {
                ceil(-16.0 / 3.0 * log2((Mp3HuffmanEncoder.MAX_VALUE - ROUNDING) / largest)).toInt() + 1
            } else {
                MIN_STEP
            }
            finestStep[band] = bound.coerceIn(MIN_STEP, MAX_STEP)
            lastCoarsest[band] = (finestStep[band] + MAX_STEP) / 2
        }
    }

    private val finestStep = IntArray(39)
    private val lastCoarsest = IntArray(39)

    /**
     * Runs Annex C's two loops for a granule of at most [maxBits] bits,
     * scalefactors included, and leaves the result in [out].
     *
     * The distortion loop needs a goal it can reach. What the model allows
     * may be far more than the budget affords, in which case every band is
     * over and amplifying them all changes nothing, or far less, in which
     * case nothing is over and the noise is spread flat. So the allowed noise
     * is first scaled, by bisection on a level in decibels, to the point where
     * quantising every band directly to it just fits the budget, and the loops
     * then work towards that.
     */
    fun quantiseWithinBudget(maxBits: Int, out: Mp3CodedGranule) {
        allowed.copyInto(original, 0, 0, bands)
        // The level that fits is rarely far from the last granule's, so the
        // search brackets it from there before bisecting.
        var low: Double
        var high: Double
        var step = FIT_FIRST_STEP_DB
        if (directAt(lastFit) <= maxBits) {
            high = lastFit
            low = high - step
            while (low > FIT_LOW_DB && directAt(low) <= maxBits) {
                high = low
                step *= 2
                low = high - step
            }
        } else {
            low = lastFit
            high = low + step
            while (high < FIT_HIGH_DB && directAt(high) > maxBits) {
                low = high
                step *= 2
                high = low + step
            }
        }
        high = high.coerceAtMost(FIT_HIGH_DB)
        while (high - low > FIT_RESOLUTION_DB) {
            val middle = (low + high) * 0.5
            if (directAt(middle) <= maxBits) high = middle else low = middle
        }
        lastFit = high
        // The direct solution at this level is where the rate loop starts.
        val startGain = if (directAt(high) <= maxBits) best.globalGain else START_GAIN

        val granule = trial
        reset(granule)
        var bestScore = Double.MAX_VALUE
        var globalGain = innerLoop(granule, maxBits, startGain)
        for (iteration in 0 until MAX_OUTER_ITERATIONS) {
            granule.globalGain = globalGain
            quantise(granule)
            // Scalefactors can outgrow the budget on their own, and such a
            // pass is never one to keep. The first pass, with none, always
            // fits: at the coarsest global step everything is zero.
            val fits = countBits(granule) + granule.part2Bits <= maxBits
            val over = measureNoise(granule)
            val score = overNoise()
            if (fits && (iteration == 0 || score < bestScore)) {
                bestScore = score
                best.copyFrom(granule)
            }
            if (over == 0 || !fits) break
            if (!amplify(granule)) break
            globalGain = innerLoop(granule, maxBits, globalGain)
        }
        out.copyFrom(best)
        finish(out)
        for (band in 0 until bands) allowed[band] = original[band]
    }

    private val original = DoubleArray(39)

    /** The level, in dB, the last granule's allowed noise was scaled to. */
    private var lastFit = 0.0

    /** Bits the direct quantisation needs with the allowed noise scaled by [db]. */
    private fun directAt(db: Double): Int {
        val scale = 10.0.pow(db / 10.0)
        for (band in 0 until bands) allowed[band] = original[band] * scale
        return direct()
    }

    /**
     * Quantises every band to the coarsest step whose noise is within what is
     * allowed, then shrinks the result into [maxBits] if it does not fit.
     * Leaves the result in [out].
     */
    fun quantiseToTarget(maxBits: Int, out: Mp3CodedGranule) {
        val bestBits = direct(UNSCALED_SLACK)
        if (bestBits > maxBits) {
            // Too many bits even for the largest frame: keep the shape and
            // coarsen everything until it fits.
            val granule = trial
            granule.copyFrom(best)
            granule.globalGain = innerLoop(granule, maxBits, granule.globalGain)
            quantise(granule)
            best.copyFrom(granule)
        }
        out.copyFrom(best)
        finish(out)
    }

    private val coarsest = IntArray(39)

    /**
     * Quantises every band directly to the coarsest step its allowed noise
     * permits, trying both scalefactor steps and pre-emphasis, and leaves the
     * cheapest in [best]. Returns its bits.
     */
    private fun direct(unscaledSlack: Int = 0): Int {
        for (band in 0 until bands) coarsest[band] = coarsestStep(band)
        var bestBits = Int.MAX_VALUE
        val scales = intArrayOf(0, 1)
        val preflags = if (blockType == Mp3ForwardTransform.SHORT) booleanArrayOf(false) else booleanArrayOf(false, true)
        for (scale in scales) {
            for (pre in preflags) {
                val granule = trial
                reset(granule)
                granule.scalefacScale = scale
                granule.preflag = pre
                val step = if (scale == 0) 2 else 4
                // The global step is the coarsest any band wants, unless a
                // band cannot be made fine enough from there within its
                // scalefactor's range, or has no scalefactor at all.
                var global = Int.MIN_VALUE
                for (band in 0 until bands) global = maxOf(global, coarsest[band])
                for (band in 0 until bands) {
                    val pretab = if (pre) bandPretab[band] else 0
                    val slack = if (bandLimit[band] == 0) unscaledSlack else 0
                    global = minOf(global, coarsest[band] + step * (bandLimit[band] + pretab) + slack)
                }
                global = global.coerceIn(-210, 45)
                for (band in 0 until bands) {
                    val pretab = if (pre) bandPretab[band] else 0
                    val needed = ceilDiv(global - coarsest[band], step) - pretab
                    granule.scalefactors[band] = needed.coerceIn(0, bandLimit[band])
                }
                granule.globalGain = global + 210
                // Rounding can still overflow the largest codable value.
                while (!quantise(granule) && granule.globalGain < 255) granule.globalGain++
                countBits(granule)
                scalefactorBits(granule)
                val bits = granule.part2And3Bits
                if (bits < bestBits) {
                    bestBits = bits
                    best.copyFrom(granule)
                }
            }
        }
        return bestBits
    }

    private fun reset(granule: Mp3CodedGranule) {
        granule.blockType = blockType
        granule.scalefactors.fill(0)
        granule.scalefacScale = 0
        granule.preflag = false
        granule.globalGain = START_GAIN
    }

    /**
     * Annex C's rate loop: the finest global gain whose spectrum fits in what
     * is left of [maxBits] after the scalefactors.
     */
    private fun innerLoop(granule: Mp3CodedGranule, maxBits: Int, startGain: Int): Int {
        scalefactorBits(granule)
        val budget = maxBits - granule.part2Bits
        fun fits(gain: Int): Boolean {
            granule.globalGain = gain
            if (!quantise(granule)) return false
            return countBits(granule) <= budget
        }
        // Gallop from the starting point to bracket the answer, then bisect.
        var low: Int
        var high: Int
        if (fits(startGain)) {
            high = startGain
            var step = 1
            low = startGain - step
            while (low >= 0 && fits(low)) {
                high = low
                step *= 2
                low = high - step
            }
            low = maxOf(low, -1)
        } else {
            low = startGain
            var step = 1
            high = startGain + step
            while (high < 255 && !fits(high)) {
                low = high
                step *= 2
                high = low + step
            }
            if (high >= 255) {
                high = 255
            }
        }
        while (high - low > 1) {
            val middle = (low + high) ushr 1
            if (fits(middle)) high = middle else low = middle
        }
        return high
    }

    /**
     * Quantises the prepared spectrum with [granule]'s gains, returning false
     * when a value would be too large to code.
     */
    private fun quantise(granule: Mp3CodedGranule): Boolean {
        val values = granule.values
        for (band in 0 until bands) {
            val q = effectiveStep(granule, band)
            val factor = STEP34[q + STEP_OFFSET]
            val limit = Mp3HuffmanEncoder.MAX_VALUE
            for (i in bandStart[band] until bandEnd[band]) {
                val scaled = magnitude34[i] * factor + ROUNDING
                if (scaled >= limit + 1) return false
                val value = scaled.toInt()
                values[i] = if (signs[i]) -value else value
            }
        }
        return true
    }

    /** Quarter powers of two a band's values are divided by: its step. */
    private fun effectiveStep(granule: Mp3CodedGranule, band: Int): Int {
        val multiplier = if (granule.scalefacScale == 0) 2 else 4
        val pretab = if (granule.preflag) bandPretab[band] else 0
        return granule.globalGain - 210 - multiplier * (granule.scalefactors[band] + pretab)
    }

    /** Measures each band's noise into [noise] and returns how many exceed what is allowed. */
    private fun measureNoise(granule: Mp3CodedGranule): Int {
        var over = 0
        val values = granule.values
        for (band in 0 until bands) {
            val step = STEP[effectiveStep(granule, band) + STEP_OFFSET]
            var sum = 0.0
            for (i in bandStart[band] until bandEnd[band]) {
                val v = values[i]
                val restored = POWER43[if (v < 0) -v else v] * step
                val d = magnitude[i] - restored
                sum += d * d
            }
            noise[band] = sum
            if (sum > allowed[band]) over++
        }
        return over
    }

    /** How far, in total, the bands are over what is allowed: a sum of logarithms. */
    private fun overNoise(): Double {
        var total = 0.0
        var worst = 0.0
        for (band in 0 until bands) {
            val ratio = noise[band] / allowed[band]
            if (ratio > 1) {
                val excess = ln(ratio)
                total += excess
                worst = maxOf(worst, excess)
            }
        }
        return total + worst
    }

    /**
     * Makes the step finer in every band whose noise is over. Returns false
     * when Annex C says to stop: every band amplified, or a scalefactor past
     * its field even at the coarser scalefactor step.
     */
    private fun amplify(granule: Mp3CodedGranule): Boolean {
        val sf = granule.scalefactors
        for (band in 0 until bands) {
            if (bandLimit[band] == 0) continue
            if (noise[band] > allowed[band]) sf[band]++
        }
        var all = true
        var tooLarge = false
        for (band in 0 until bands) {
            if (bandLimit[band] == 0) continue
            if (sf[band] == 0) all = false
            if (sf[band] > bandLimit[band]) tooLarge = true
        }
        if (all) return false
        if (tooLarge) {
            if (granule.scalefacScale == 1) return false
            // Double the step of every scalefactor, rounding up so that no
            // band ends up coarser than it was asked to be.
            granule.scalefacScale = 1
            for (band in 0 until bands) sf[band] = (sf[band] + 1) / 2
            for (band in 0 until bands) if (sf[band] > bandLimit[band]) return false
        }
        return true
    }

    /**
     * The coarsest step, in quarter powers of two, at which [band]'s noise
     * stays within what is allowed.
     */
    private fun coarsestStep(band: Int): Int {
        val limit = allowed[band]
        if (noiseAt(band, MAX_STEP) <= limit) return MAX_STEP
        // No finer than the step at which the band's largest value is still
        // codable; the search takes that one as good enough.
        val finest = finestStep[band]
        // Start where the last search for this band ended, which during the
        // fit to a budget is rarely more than a few steps away.
        var low: Int
        var high: Int
        val guess = lastCoarsest[band].coerceIn(finest, MAX_STEP - 1)
        if (noiseAt(band, guess) <= limit) {
            low = guess
            var step = 1
            high = guess + step
            while (high < MAX_STEP && noiseAt(band, high) <= limit) {
                low = high
                step *= 2
                high = minOf(low + step, MAX_STEP)
            }
        } else {
            high = guess
            var step = 1
            low = guess - step
            while (low > finest && noiseAt(band, low) > limit) {
                high = low
                step *= 2
                low = maxOf(high - step, finest)
            }
            low = maxOf(low, finest)
        }
        while (high - low > 1) {
            val middle = (low + high) shr 1
            if (noiseAt(band, middle) <= limit) low = middle else high = middle
        }
        lastCoarsest[band] = low
        return low
    }

    /** The noise of [band] quantised at step [q] with no scalefactor. */
    private fun noiseAt(band: Int, q: Int): Double {
        val factor = STEP34[q + STEP_OFFSET]
        val step = STEP[q + STEP_OFFSET]
        var sum = 0.0
        for (i in bandStart[band] until bandEnd[band]) {
            val value = (magnitude34[i] * factor + ROUNDING).toInt()
            val d = magnitude[i] - POWER43[value.coerceAtMost(Mp3HuffmanEncoder.MAX_VALUE)] * step
            sum += d * d
        }
        return sum
    }

    /**
     * Chooses the Huffman regions and tables that cost least, the count1
     * table, and the smallest scalefactor field widths. Only ever lowers the
     * bit count.
     */
    fun finish(granule: Mp3CodedGranule) {
        quantise(granule)
        if (granule.blockType != Mp3ForwardTransform.SHORT && !granule.preflag) {
            // Pre-emphasis is free when every upper band already holds at
            // least its pre-emphasis amount: the same steps result, from
            // smaller scalefactors.
            var usable = true
            for (band in 11 until 21) if (granule.scalefactors[band] < Mp3Tables.PRETAB[band]) usable = false
            if (usable) {
                for (band in 11 until 21) granule.scalefactors[band] -= Mp3Tables.PRETAB[band]
                granule.preflag = true
            }
        }
        countBits(granule, optimise = true)
        scalefactorBits(granule)
    }

    /**
     * Counts the Huffman bits of [granule]'s values and fills in its regions
     * and tables. With [optimise], every division into regions is tried;
     * otherwise a fixed proportion is used, which is what the rate loop needs
     * many times over.
     */
    private fun countBits(granule: Mp3CodedGranule, optimise: Boolean = false): Int {
        val values = granule.values
        var end = 576
        while (end > 0 && values[end - 1] == 0 && values[end - 2] == 0) end -= 2
        var big = end
        while (big >= 4 &&
            abs(values[big - 1]) <= 1 && abs(values[big - 2]) <= 1 &&
            abs(values[big - 3]) <= 1 && abs(values[big - 4]) <= 1
        ) {
            big -= 4
        }
        granule.bigValues = big / 2
        granule.count1End = end

        val quadA = Mp3HuffmanEncoder.quadBits(values, big, end, 0)
        val quadB = Mp3HuffmanEncoder.quadBits(values, big, end, 1)
        granule.count1Table = if (quadB < quadA) 1 else 0
        var bits = minOf(quadA, quadB)

        if (granule.blockType != Mp3ForwardTransform.NORMAL) {
            // A switched granule has two regions, the first ending at a fixed
            // place: three short bands across three windows for a short block,
            // eight long bands for a start or stop block.
            val split = if (granule.blockType == Mp3ForwardTransform.SHORT) shortBands[3] * 3 else longBands[8]
            val boundary = minOf(split, big)
            val first = Mp3HuffmanEncoder.bestTable(values, 0, boundary)
            val second = Mp3HuffmanEncoder.bestTable(values, boundary, big)
            granule.tableSelect[0] = first and 63
            granule.tableSelect[1] = second and 63
            granule.tableSelect[2] = 0
            granule.region0Count = if (granule.blockType == Mp3ForwardTransform.SHORT) 8 else 7
            granule.region1Count = 20 - granule.region0Count
            bits += (first ushr 6) + (second ushr 6)
            granule.part3Bits = bits
            return bits
        }

        if (optimise) {
            bits += bestRegions(granule, big)
        } else {
            // About a third of the big values in each region, in band terms.
            var first = 1
            while (first < 16 && longBands[first] < big / 4) first++
            var second = first + 1
            while (second < first + 8 && second < 22 && longBands[second] < big / 2) second++
            bits += regionCost(granule, big, first - 1, second - first - 1)
        }
        granule.part3Bits = bits
        return bits
    }

    /** Bits of the three big value regions for the given counts, filling the tables in. */
    private fun regionCost(granule: Mp3CodedGranule, big: Int, region0: Int, region1: Int): Int {
        val values = granule.values
        val start1 = minOf(longBands[minOf(region0 + 1, 22)], big)
        val start2 = minOf(longBands[minOf(region0 + region1 + 2, 22)], big)
        val a = Mp3HuffmanEncoder.bestTable(values, 0, start1)
        val b = Mp3HuffmanEncoder.bestTable(values, start1, start2)
        val c = Mp3HuffmanEncoder.bestTable(values, start2, big)
        granule.region0Count = region0
        granule.region1Count = region1
        granule.tableSelect[0] = a and 63
        granule.tableSelect[1] = b and 63
        granule.tableSelect[2] = c and 63
        return (a ushr 6) + (b ushr 6) + (c ushr 6)
    }

    private val regionCache = IntArray(23 * 23)

    /** Tries every region division and keeps the cheapest. Returns its bits. */
    private fun bestRegions(granule: Mp3CodedGranule, big: Int): Int {
        val values = granule.values
        // Region costs by start and end band, each region's table chosen alone.
        regionCache.fill(-1)
        fun cost(from: Int, to: Int): Int {
            val key = from * 23 + to
            val cached = regionCache[key]
            if (cached >= 0) return cached
            val packed = Mp3HuffmanEncoder.bestTable(
                values, minOf(longBands[from], big), minOf(longBands[to], big),
            )
            regionCache[key] = packed
            return packed
        }
        var bestBits = Int.MAX_VALUE
        var best0 = 0
        var best1 = 0
        for (region0 in 0 until 16) {
            val start1 = region0 + 1
            if (start1 > 22) break
            val a = cost(0, start1) ushr 6
            for (region1 in 0 until 8) {
                val start2 = minOf(region0 + region1 + 2, 22)
                val bits = a + (cost(start1, start2) ushr 6) + (cost(start2, 22) ushr 6)
                if (bits < bestBits) {
                    bestBits = bits
                    best0 = region0
                    best1 = region1
                }
                if (longBands[start2] >= big) break
            }
            if (longBands[start1] >= big) break
        }
        return regionCost(granule, big, best0, best1)
    }

    /**
     * The scalefactor field widths: the cheapest `scalefac_compress` whose
     * widths hold every scalefactor, and the bits they take.
     */
    private fun scalefactorBits(granule: Mp3CodedGranule, transmitted: BooleanArray? = null) {
        val sf = granule.scalefactors
        var max1 = 0
        var max2 = 0
        if (granule.blockType == Mp3ForwardTransform.SHORT) {
            for (band in 0 until 6) for (w in 0 until 3) max1 = maxOf(max1, sf[band * 3 + w])
            for (band in 6 until 12) for (w in 0 until 3) max2 = maxOf(max2, sf[band * 3 + w])
        } else {
            for (group in 0 until 4) {
                if (transmitted != null && !transmitted[group]) continue
                for (band in GROUP_STARTS[group] until GROUP_STARTS[group + 1]) {
                    if (group < 2) max1 = maxOf(max1, sf[band]) else max2 = maxOf(max2, sf[band])
                }
            }
        }
        var bestCompress = 0
        var bestBits = Int.MAX_VALUE
        for (compress in 0 until 16) {
            val slen1 = Mp3Tables.SLEN1[compress]
            val slen2 = Mp3Tables.SLEN2[compress]
            if (max1 >= (1 shl slen1) || max2 >= (1 shl slen2)) continue
            val bits = if (granule.blockType == Mp3ForwardTransform.SHORT) {
                18 * slen1 + 18 * slen2
            } else {
                var sum = 0
                for (group in 0 until 4) {
                    if (transmitted != null && !transmitted[group]) continue
                    val count = GROUP_STARTS[group + 1] - GROUP_STARTS[group]
                    sum += count * if (group < 2) slen1 else slen2
                }
                sum
            }
            if (bits < bestBits) {
                bestBits = bits
                bestCompress = compress
            }
        }
        granule.scalefacCompress = bestCompress
        granule.part2Bits = if (bestBits == Int.MAX_VALUE) 0 else bestBits
    }

    /**
     * Recounts the scalefactor bits of a second granule whose groups marked
     * false in [transmitted] are carried over from the first.
     */
    fun recountScalefactors(granule: Mp3CodedGranule, transmitted: BooleanArray) {
        scalefactorBits(granule, transmitted)
    }

    companion object {
        /** Where MPEG-1's four scalefactor groups start and end. */
        val GROUP_STARTS: IntArray = intArrayOf(0, 6, 11, 16, 21)

        private const val START_GAIN = 180

        /**
         * Quarter steps of extra noise the band without a scalefactor may
         * carry at a variable bitrate. That band, above 16 kHz at every
         * MPEG-1 rate, can only be made finer by making every band finer, so
         * holding it strictly to its allowed noise costs bits everywhere; two
         * steps of six decibels' slack there brought the variable bitrate on
         * the test music down by a fifth at a lower measured error.
         */
        private const val UNSCALED_SLACK = 8
        private const val FIT_LOW_DB = -40.0
        private const val FIT_HIGH_DB = 50.0
        private const val FIT_FIRST_STEP_DB = 2.0
        private const val FIT_RESOLUTION_DB = 0.25
        private const val MAX_OUTER_ITERATIONS = 60
        private const val MIN_STEP = -210 - 4 * 18 - 8 * 7
        private const val MAX_STEP = 45
        private const val STEP_OFFSET = -MIN_STEP + 8

        /** Annex C's rounding, `nint(x - 0.0946)`. */
        private const val ROUNDING = 0.5 - 0.0946

        /** `2^(-3q/16)`: what a value raised to 3/4 is multiplied by at step q. */
        private val STEP34 = DoubleArray(STEP_OFFSET + MAX_STEP + 8) { 2.0.pow(-3.0 * (it - STEP_OFFSET) / 16.0) }

        /** `2^(q/4)`: the step itself. */
        private val STEP = DoubleArray(STEP_OFFSET + MAX_STEP + 8) { 2.0.pow((it - STEP_OFFSET) / 4.0) }

        private val POWER43 = DoubleArray(Mp3HuffmanEncoder.MAX_VALUE + 2) { it.toDouble().pow(4.0 / 3.0) }

        private fun ceilDiv(a: Int, b: Int): Int = floor(a.toDouble() / b).toInt().let { if (it * b < a) it + 1 else it }
    }
}

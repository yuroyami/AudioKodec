package io.github.yuroyami.audiokodec.internal.vorbis

import io.github.yuroyami.audiokodec.internal.bits.BitWriterLsb
import io.github.yuroyami.audiokodec.internal.psy.Hearing
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Fits and writes a floor 1 curve for one block size.
 *
 * Floor 1 is a list of points with straight lines between them, drawn on a
 * logarithmic amplitude scale, and the decoder multiplies the residue by it.
 * The encoder uses it as the quantiser's step size: a coefficient is coded as
 * how many floor heights it is, so where the floor sits is where the coding
 * noise sits.
 *
 * The points are spread evenly on the Bark scale, never closer than
 * [minimumSpacing] bins, and coded in an order that halves the gaps: each new
 * point is predicted from the line between the two already coded on either
 * side of it, and only its difference from that line is written. A point the
 * line already predicts well costs one short codeword and is not drawn at all.
 *
 * Written from the Vorbis I specification, section 7.
 */
internal class VorbisFloorEncoder(
    /** Half the block size: the floor covers bins `0 until half`. */
    val half: Int,
    sampleRate: Int,
    /**
     * Points besides the two ends, at most 31 times [classDimensions]: the
     * format allows 31 partitions. Fewer are kept when the spacing leaves no
     * room, and a remainder short of a whole partition is dropped.
     */
    interiorPoints: Int,
    /** Points per partition, all partitions sharing one class. */
    val classDimensions: Int,
    minimumSpacing: Int,
) {
    init {
        require(half and (half - 1) == 0 && half >= 32) { "a floor covers a power of two of bins" }
        require(classDimensions in 1..8) { "a floor 1 class has 1 to 8 dimensions" }
        require(interiorPoints in 0..31 * classDimensions) { "a floor 1 has at most 31 partitions" }
    }

    /** The x of every point in the order they are coded: 0, [half], then the rest. */
    val xList: IntArray

    init {
        val binHz = sampleRate.toDouble() / (2.0 * half)
        val positions = spreadOnBark(half, binHz, interiorPoints, minimumSpacing)
        val count = positions.size - positions.size % classDimensions
        val interior = positions.copyOf(count)
        xList = IntArray(count + 2)
        xList[1] = half
        val order = codingOrder(interior)
        for (i in order.indices) xList[2 + i] = interior[order[i]]
    }

    val partitions: Int get() = (xList.size - 2) / classDimensions

    private val sortedOrder = xList.indices.sortedBy { xList[it] }.toIntArray()
    private val lowNeighbour = IntArray(xList.size)
    private val highNeighbour = IntArray(xList.size)

    init {
        for (i in 2 until xList.size) {
            var low = 0
            var high = 1
            for (j in 0 until i) {
                if (xList[j] < xList[i] && xList[j] > xList[low]) low = j
                if (xList[j] > xList[i] && xList[j] < xList[high]) high = j
            }
            lowNeighbour[i] = low
            highNeighbour[i] = high
        }
    }

    private val wanted = IntArray(xList.size)
    private val finalY = IntArray(xList.size)

    /**
     * Chooses the floor for [target], the wanted curve per bin in the
     * floor's own units (0 to 255, about 0.55 dB apart), and fills [coded]
     * with the values the bitstream carries: the two end heights, then each
     * point's correction.
     *
     * [lowest] is the least height the drawn curve may have in each bin, in
     * the same units. A line drawn between two points that sit low on either
     * side of a loud tone would pass far beneath it, and the residue would
     * have to count the tone in thousands of floor heights; the points are
     * raised until no bin is that far above its floor.
     */
    fun fit(target: FloatArray, lowest: FloatArray, coded: IntArray) {
        val sorted = sortedOrder
        for (s in sorted.indices) {
            val i = sorted[s]
            val x = xList[i]
            val left = if (s > 0) xList[sorted[s - 1]] else 0
            val right = if (s + 1 < sorted.size) xList[sorted[s + 1]] else half
            // A triangle reaching to the neighbouring points, which is what
            // the drawn lines will spread this point's height over. The
            // average leans towards the lowest of the wanted heights under
            // it, because a floor above what was wanted lets in more noise
            // than was allowed, while one below only costs bits.
            var sum = 0.0
            var weights = 0.0
            val from = maxOf(0, left)
            val to = minOf(half - 1, right)
            var least = Float.MAX_VALUE
            for (k in from..to) least = minOf(least, target[k])
            for (k in from..to) {
                val w = if (k <= x) {
                    if (x == left) 1.0 else (k - left + 1).toDouble() / (x - left + 1)
                } else {
                    (right - k + 1).toDouble() / (right - x + 1)
                }
                sum += w * exp(-(target[k] - least) / SOFTNESS)
                weights += w
            }
            val height = if (weights > 0) least - SOFTNESS * ln(sum / weights) else target[minOf(x, half - 1)].toDouble()
            wanted[i] = (height / MULTIPLIER + 0.5).toInt().coerceIn(0, RANGE - 1)
        }
        raiseTo(lowest)

        coded[0] = wanted[0]
        coded[1] = wanted[1]
        finalY[0] = wanted[0]
        finalY[1] = wanted[1]
        for (i in 2 until xList.size) {
            val low = lowNeighbour[i]
            val high = highNeighbour[i]
            val predicted = renderPoint(xList[low], finalY[low], xList[high], finalY[high], xList[i])
            val value = wanted[i]
            if (abs(value - predicted) <= TOLERANCE) {
                coded[i] = 0
                finalY[i] = predicted
                continue
            }
            val highRoom = RANGE - predicted
            val lowRoom = predicted
            val room = 2 * minOf(highRoom, lowRoom)
            coded[i] = if (value >= predicted) {
                val step = value - predicted
                if (2 * step < room) 2 * step else step + lowRoom
            } else {
                val step = predicted - value
                if (2 * step - 1 < room) 2 * step - 1 else highRoom + predicted - 1 - value
            }
            finalY[i] = value
        }
    }

    /**
     * Raises the points of [wanted] until the straight lines between them,
     * in order of x, are nowhere below [lowest]. Lifting both ends of a
     * segment by its worst shortfall lifts the whole segment by as much,
     * and a few rounds settle the points two segments share.
     */
    private fun raiseTo(lowest: FloatArray) {
        repeat(RAISE_ROUNDS) {
            var changed = false
            for (s in 0 until sortedOrder.size - 1) {
                val a = sortedOrder[s]
                val b = sortedOrder[s + 1]
                val x0 = xList[a]
                val x1 = xList[b]
                val y0 = wanted[a] * MULTIPLIER.toFloat()
                val y1 = wanted[b] * MULTIPLIER.toFloat()
                var shortfall = 0f
                for (k in x0 until minOf(x1, half)) {
                    val drawn = y0 + (y1 - y0) * (k - x0) / (x1 - x0)
                    shortfall = maxOf(shortfall, lowest[k] - drawn)
                }
                if (shortfall > 0f) {
                    val steps = (shortfall / MULTIPLIER).toInt() + 1
                    wanted[a] = minOf(RANGE - 1, wanted[a] + steps)
                    wanted[b] = minOf(RANGE - 1, wanted[b] + steps)
                    changed = true
                }
            }
            if (!changed) return
        }
    }

    /** Writes a fitted floor, [coded] as [fit] left it. */
    fun write(writer: BitWriterLsb, coded: IntArray, book: VorbisEncodeBook) {
        writer.writeBits(1, 1)
        val bits = VorbisCodebook.ilog(RANGE - 1)
        writer.writeBits(coded[0], bits)
        writer.writeBits(coded[1], bits)
        for (i in 2 until xList.size) book.write(writer, coded[i])
    }

    /** Writes this floor's part of the setup header, section 7.2.2, coding every value with [book]. */
    fun writeHeader(writer: BitWriterLsb, book: Int) {
        writer.writeBits(partitions, 5)
        repeat(partitions) { writer.writeBits(0, 4) }
        writer.writeBits(classDimensions - 1, 3)
        writer.writeBits(0, 2) // no subclasses, so no master book
        writer.writeBits(book + 1, 8)
        writer.writeBits(MULTIPLIER - 1, 2)
        val rangeBits = VorbisCodebook.ilog(half - 1)
        writer.writeBits(rangeBits, 4)
        for (i in 2 until xList.size) writer.writeBits(xList[i], rangeBits)
    }

    /** Exactly the decoder's prediction, section 9.2.6. */
    private fun renderPoint(x0: Int, y0: Int, x1: Int, y1: Int, x: Int): Int {
        val dy = y1 - y0
        val adx = x1 - x0
        val offset = abs(dy) * (x - x0) / adx
        return if (dy < 0) y0 - offset else y0 + offset
    }

    companion object {
        /** Each coded height step is two of the curve's 0.55 dB steps. */
        const val MULTIPLIER = 2

        /** Heights a point can take with [MULTIPLIER] two. */
        const val RANGE = 128

        /**
         * How sharply a point's height leans to the lowest wanted under it,
         * in the curve's 0.55 dB steps: an average of exponentials this many
         * steps wide.
         */
        private const val SOFTNESS = 4.0

        /** Rounds of raising points over a tone before the fit gives up. */
        private const val RAISE_ROUNDS = 4

        /** A point within this many steps of its prediction is left on the line. */
        private const val TOLERANCE = 1

        /**
         * Picks [count] bins evenly spaced in Bark between the ends, then
         * pushes apart any closer than [spacing], which happens at low
         * frequencies where a Bark is only a few bins wide.
         */
        private fun spreadOnBark(half: Int, binHz: Double, count: Int, spacing: Int): IntArray {
            val top = Hearing.bark(half * binHz)
            val chosen = ArrayList<Int>()
            var last = 0
            for (i in 1..count) {
                val goal = top * i / (count + 1)
                var bin = last + spacing
                while (bin < half && Hearing.bark((bin + 0.5) * binHz) < goal) bin++
                if (bin >= half - spacing / 2) break
                chosen.add(bin)
                last = bin
            }
            return chosen.toIntArray()
        }

        /** Breadth-first halving: the middle point, then the middles of each half, and so on. */
        private fun codingOrder(sorted: IntArray): IntArray {
            val order = ArrayList<Int>()
            var spans = listOf(0 until sorted.size)
            while (spans.isNotEmpty()) {
                val next = ArrayList<IntRange>()
                for (span in spans) {
                    if (span.isEmpty()) continue
                    val middle = (span.first + span.last) / 2
                    order.add(middle)
                    next.add(span.first until middle)
                    next.add(middle + 1..span.last)
                }
                spans = next
            }
            return order.toIntArray()
        }
    }
}

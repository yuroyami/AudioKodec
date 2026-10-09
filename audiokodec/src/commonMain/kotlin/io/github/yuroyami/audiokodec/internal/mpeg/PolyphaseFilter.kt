package io.github.yuroyami.audiokodec.internal.mpeg

import kotlin.math.PI
import kotlin.math.cos

/**
 * The synthesis filterbank all three MPEG audio layers share.
 *
 * Thirty-two subband values go in and thirty-two samples come out, and the
 * running history is what makes it a filter rather than a transform: each
 * output sample is a weighted sum over the last sixteen blocks, which is what
 * gives the subbands their sharp edges. It is also the expensive part of every
 * MPEG audio decoder, and the reason they are written the way they are.
 *
 * The three layers differ only in how many times they call it per frame and in
 * what they did to the subband values first. Layer I calls it twelve times,
 * Layer II thirty-six, and Layer III eighteen per granule.
 *
 * Written from ISO/IEC 11172-3 clause 2.4.3.2, which Layers II and III both
 * refer back to rather than restating.
 */
internal class PolyphaseFilter(private val channels: Int) {

    private val history = Array(channels) { DoubleArray(1024) }
    private val historyAt = IntArray(channels)
    private val slot = DoubleArray(32)

    fun reset() {
        for (channel in 0 until channels) {
            history[channel].fill(0.0)
            historyAt[channel] = 0
        }
    }

    /**
     * Runs one step of the filterbank.
     *
     * [input] is read at `inOffset + subband * inStride`, which lets a caller
     * hand over either thirty-two values in a row or one value from each of
     * thirty-two blocks without copying first. The samples are written at
     * `outOffset + i * outStride`, so an interleaved buffer needs no copy
     * either.
     */
    fun step(
        input: DoubleArray,
        inOffset: Int,
        inStride: Int,
        channel: Int,
        out: FloatArray,
        outOffset: Int,
        outStride: Int,
    ) {
        for (subband in 0 until 32) slot[subband] = input[inOffset + subband * inStride]

        // Every sum below runs in two or four accumulators instead of one. A single running sum makes each
        // addition wait for the one before, and these sums are the cost of the whole decoder; the order of
        // the additions is not part of the standard's definition, and the difference is far below a float.
        val buffer = history[channel]
        val matrix = MATRIX
        val at = (historyAt[channel] - 64) and 1023
        for (i in 0 until 64) {
            val row = i shl 5
            var s0 = 0.0
            var s1 = 0.0
            var s2 = 0.0
            var s3 = 0.0
            var k = 0
            while (k < 32) {
                s0 += slot[k] * matrix[row + k]
                s1 += slot[k + 1] * matrix[row + k + 1]
                s2 += slot[k + 2] * matrix[row + k + 2]
                s3 += slot[k + 3] * matrix[row + k + 3]
                k += 4
            }
            buffer[(at + i) and 1023] = (s0 + s1) + (s2 + s3)
        }

        // The window runs over sixteen half-blocks of the history, taken from
        // alternating halves of each pair of blocks. That shuffle is what the
        // standard writes as building U from V.
        val window = Mp3Tables.SYNTHESIS_WINDOW
        var write = outOffset
        for (j in 0 until 32) {
            var even = 0.0
            var odd = 0.0
            for (m in 0 until 8) {
                val base = at + (m shl 7)
                even += buffer[(base + j) and 1023] * window[j + 64 * m]
                odd += buffer[(base + 96 + j) and 1023] * window[j + 32 + 64 * m]
            }
            out[write] = (even + odd).toFloat()
            write += outStride
        }
        historyAt[channel] = at
    }

    private companion object {
        /** The matrixing step, thirty-two inputs into sixty-four. */
        val MATRIX = DoubleArray(64 * 32) { n -> cos((16 + n / 32) * (2 * (n % 32) + 1) * PI / 64.0) }
    }
}

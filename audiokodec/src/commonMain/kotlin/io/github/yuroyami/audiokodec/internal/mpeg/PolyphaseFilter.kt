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

        val buffer = history[channel]
        var at = (historyAt[channel] - 64) and 1023
        for (i in 0 until 64) {
            var sum = 0.0
            for (k in 0 until 32) sum += slot[k] * MATRIX[i][k]
            buffer[(at + i) and 1023] = sum
        }

        // The window runs over sixteen half-blocks of the history, taken from
        // alternating halves of each pair of blocks. That shuffle is what the
        // standard writes as building U from V.
        var write = outOffset
        for (j in 0 until 32) {
            var sum = 0.0
            for (m in 0 until 8) {
                val base = at + (m shl 7)
                sum += buffer[(base + j) and 1023] * Mp3Tables.SYNTHESIS_WINDOW[j + 64 * m]
                sum += buffer[(base + 96 + j) and 1023] * Mp3Tables.SYNTHESIS_WINDOW[j + 32 + 64 * m]
            }
            out[write] = sum.toFloat()
            write += outStride
        }
        historyAt[channel] = at
    }

    private companion object {
        /** The matrixing step, thirty-two inputs into sixty-four. */
        val MATRIX = Array(64) { i ->
            DoubleArray(32) { k -> cos((16 + i) * (2 * k + 1) * PI / 64.0) }
        }
    }
}

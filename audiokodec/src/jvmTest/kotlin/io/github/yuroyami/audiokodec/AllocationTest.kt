package io.github.yuroyami.audiokodec

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Measures what whole-file decoding allocates, against the size of what it
 * returns.
 *
 * A buffer that grows by one block at a time still returns the right samples,
 * so no correctness test notices it. It shows up only as gigabytes of
 * temporary arrays, which is what this counts.
 */
class AllocationTest {

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @Test
    fun wholeFileDecodingAllocatesAConstantMultipleOfItsResult() {
        val format = AudioFormat(48_000, 1)
        val warmUp = AudioKodec.encode(FloatArray(20_000), format, EncodeTarget.wav())
        repeat(10) { AudioKodec.decode(warmUp) }
        for (frames in intArrayOf(2_097_152, 4_194_304)) {
            val bytes = AudioKodec.encode(FloatArray(frames), format, EncodeTarget.wav())
            val thread = Thread.currentThread().threadId()
            val before = threads.getThreadAllocatedBytes(thread)
            val decoded = AudioKodec.decode(bytes)
            val allocated = threads.getThreadAllocatedBytes(thread) - before
            assertEquals(frames, decoded.frames)
            val result = frames.toLong() * 4
            // Doubling from a megasample costs at most twice the result in
            // copies, plus the result itself and the decoder's own blocks.
            // The defect this guards against measured 96 and 481 times.
            assertTrue(
                allocated < result * 6,
                "decoding $frames frames allocated $allocated bytes for a $result-byte result",
            )
        }
    }
}

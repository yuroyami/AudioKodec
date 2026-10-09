package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.ac3.Ac3Frame
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks that each channel of an AC-3 stream switches between its long and
 * short transforms on its own, which no other decoder is a reference for.
 *
 * Section 7.9 gives every full bandwidth channel its own `blksw` flag and its
 * own overlap from the block before, and nothing in the decoding before the
 * inverse transform depends on the flag: the exponents, the bit allocation,
 * the mantissas, coupling and rematrixing all work on the same coefficients
 * either way. A channel of a block in which the channels switch differently
 * must therefore sound exactly as it does in a stream where every channel
 * makes its own channel's choice. ffmpeg's decoders do not do this: in such a
 * block they overlap one channel with the tail of channel 0's previous block,
 * so [Ac3RandomOracleTest] writes only blocks whose channels all switch alike.
 *
 * Each stream here comes in as many variants as it has channels, built from
 * the same random choices and differing only in the flags, and every channel of
 * the mixed stream is compared with its own variant.
 */
class Ac3BlockSwitchingTest {

    @Test
    fun eachChannelFollowsItsOwnFlagsWhateverTheOthersDo() {
        val shapes = listOf(
            Shape(0, 2, false), Shape(1, 3, true), Shape(2, 7, true), Shape(0, 6, false), Shape(1, 0, true), Shape(2, 4, false),
        )
        var mixedBlocks = 0
        for (shape in shapes) {
            for (seed in 1L..3L) {
                val channels = FULL_BANDWIDTH[shape.codingMode]
                val scripts = List(FRAMES) { mixedScript(Random(seed * 100 + it), channels) }
                mixedBlocks += scripts.sumOf { script -> script.count { block -> block.toSet().size > 1 } }
                val mixed = stream(shape, seed, scripts)
                val decoded = AudioKodec.decode(mixed)
                val frame = Ac3Frame.parse(mixed, 0)!!
                for (c in 0 until channels) {
                    val own = stream(shape, seed, scripts.map { script -> script.map { block -> block[c].toString().repeat(channels) } })
                    val reference = AudioKodec.decode(own)
                    assertEquals(decoded.frames, reference.frames, "$shape seed $seed channel $c: length")
                    val column = frame.layout.channels.indexOf(frame.storedOrder[c])
                    val width = decoded.format.channels
                    for (f in 0 until decoded.frames) {
                        assertEquals(
                            reference.samples[f * width + column], decoded.samples[f * width + column],
                            "$shape seed $seed: channel $c, sample $f differs from the stream where every channel switches like it does",
                        )
                    }
                }
            }
        }
        assertTrue(mixedBlocks > 50, "only $mixedBlocks blocks switched channels differently")
    }

    @Test
    fun switchingChangesTheSamples() {
        val shape = Shape(0, 2, false)
        val allLong = AudioKodec.decode(stream(shape, 5, List(FRAMES) { List(6) { "LL" } }))
        val allShort = AudioKodec.decode(stream(shape, 5, List(FRAMES) { List(6) { "SS" } }))
        assertTrue(allLong.samples.indices.any { allLong.samples[it] != allShort.samples[it] }, "the flags change nothing")
    }

    private class Shape(val sampleRateCode: Int, val codingMode: Int, val hasLfe: Boolean) {
        override fun toString() = "rate code $sampleRateCode, mode $codingMode${if (hasLfe) " with LFE" else ""}"
    }

    /** Six blocks of one letter per channel, S for the short transform and L for the long, mixed more often than not. */
    private fun mixedScript(random: Random, channels: Int): List<String> =
        List(6) { String(CharArray(channels) { if (random.nextInt(3) == 0) 'S' else 'L' }) }

    private fun stream(shape: Shape, seed: Long, scripts: List<List<String>>): ByteArray =
        scripts.withIndex().map { (i, script) ->
            Ac3FrameWriter(seed * 1000 + i).frame(
                shape.sampleRateCode, shape.codingMode, shape.hasLfe, minimumSizeCode = 20,
                options = Ac3FrameWriter.Options(switchScript = script),
            )
        }.fold(ByteArray(0)) { a, b -> a + b }

    private companion object {
        /** Three syncframes, so that a channel's overlap crosses from one to the next. */
        const val FRAMES = 3

        /** Full bandwidth channels by coding mode. */
        val FULL_BANDWIDTH = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5)
    }
}

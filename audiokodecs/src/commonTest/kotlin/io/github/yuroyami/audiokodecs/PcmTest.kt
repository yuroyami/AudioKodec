package io.github.yuroyami.audiokodecs

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PcmTest {

    @Test
    fun everyIntegerSurvivesTheTripThroughFloatAt8Bits() {
        val values = IntArray(256) { it - 128 }
        val floats = Pcm.floatFromInt(values, 8)
        val back = Pcm.intFromFloat(floats, 8)
        assertContentEquals(values, back)
    }

    @Test
    fun everyIntegerSurvivesTheTripThroughFloatAt16Bits() {
        val values = IntArray(65_536) { it - 32_768 }
        val floats = Pcm.floatFromInt(values, 16)
        val back = Pcm.intFromFloat(floats, 16)
        assertContentEquals(values, back)
    }

    @Test
    fun the24BitExtremesSurviveTheTripThroughFloat() {
        // Every 24-bit value would be 16 million comparisons, so this checks
        // the ends and a spread through the middle, which is where a scale
        // error would show.
        val values = IntArray(4096) { i ->
            when (i) {
                0 -> -8_388_608
                1 -> 8_388_607
                2 -> 0
                3 -> -1
                else -> (i - 2048) * 4093
            }
        }
        val floats = Pcm.floatFromInt(values, 24)
        val back = Pcm.intFromFloat(floats, 24)
        assertContentEquals(values, back)
    }

    @Test
    fun fullScaleNegativeMapsToExactlyMinusOne() {
        assertEquals(-1f, Pcm.floatFromInt(intArrayOf(-32_768), 16)[0])
        assertEquals(-1f, Pcm.floatFromInt(intArrayOf(-128), 8)[0])
        assertEquals(-1f, Pcm.floatFromInt(intArrayOf(-8_388_608), 24)[0])
    }

    @Test
    fun samplesOutsideTheNominalRangeAreClamped() {
        val out = Pcm.intFromFloat(floatArrayOf(2f, -2f, 1f, -1f), 16)
        assertContentEquals(intArrayOf(32_767, -32_768, 32_767, -32_768), out)
    }

    @Test
    fun notANumberBecomesSilenceRatherThanNoise() {
        val out = Pcm.intFromFloat(floatArrayOf(Float.NaN), 16)
        assertEquals(0, out[0])
    }

    @Test
    fun interleavingAndSplittingAreInverses() {
        val planes = arrayOf(
            floatArrayOf(1f, 2f, 3f, 4f),
            floatArrayOf(-1f, -2f, -3f, -4f),
            floatArrayOf(0.5f, 0.25f, 0.125f, 0f),
        )
        val interleaved = Pcm.interleave(planes, 4)
        assertContentEquals(
            floatArrayOf(1f, -1f, 0.5f, 2f, -2f, 0.25f, 3f, -3f, 0.125f, 4f, -4f, 0f),
            interleaved,
        )
        val split = Pcm.deinterleave(interleaved, 3, 4)
        for (c in planes.indices) {
            assertContentEquals(planes[c], split[c], "channel $c did not survive the trip")
        }
    }

    @Test
    fun remappingChannelsMovesTheRightSamples() {
        val from = ChannelLayout.of(
            AudioChannel.FRONT_CENTER, AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT,
        )
        val to = ChannelLayout.SURROUND_3_0
        // One frame: centre, left, right.
        val samples = floatArrayOf(0.3f, 0.1f, 0.2f)
        val out = Pcm.remapChannels(samples, 1, from, to)
        assertContentEquals(floatArrayOf(0.1f, 0.2f, 0.3f), out)
    }

    @Test
    fun remappingBetweenIdenticalLayoutsChangesNothing() {
        val samples = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        val out = Pcm.remapChannels(samples, 2, ChannelLayout.STEREO, ChannelLayout.STEREO)
        assertContentEquals(samples, out)
    }

    @Test
    fun ditherIsRepeatableFromTheSameSeed() {
        val input = FloatArray(1000) { (it % 7) * 0.01f }
        val first = Pcm.intFromFloat(input, 16, dither = Dither.Tpdf(seed = 42))
        val second = Pcm.intFromFloat(input, 16, dither = Dither.Tpdf(seed = 42))
        assertContentEquals(first, second)
    }

    @Test
    fun ditherStaysWithinOneStepOfThePlainRounding() {
        val input = FloatArray(1000) { (it - 500) / 1000f }
        val plain = Pcm.intFromFloat(input, 16)
        val dithered = Pcm.intFromFloat(input, 16, dither = Dither.Tpdf(seed = 7))
        for (i in plain.indices) {
            val difference = kotlin.math.abs(plain[i] - dithered[i])
            assertTrue(difference <= 1, "dither moved sample $i by $difference steps")
        }
    }

    @Test
    fun anUnsupportedBitDepthIsRejected() {
        assertFailsWith<IllegalArgumentException> { Pcm.scaleOf(0) }
        assertFailsWith<IllegalArgumentException> { Pcm.scaleOf(33) }
    }

    @Test
    fun aWaveChannelMaskBecomesTheLayoutItNames() {
        // Front left, front right, front centre, low frequency, back left,
        // back right.
        val layout = ChannelLayout.fromWaveMask(0x3F, 6)
        assertEquals(ChannelLayout.SURROUND_5_1, layout)
        assertEquals(0x3F, layout.waveMask)
    }

    @Test
    fun aMaskThatNamesTooFewChannelsLeavesTheRestUnknown() {
        val layout = ChannelLayout.fromWaveMask(0x3, 4)
        assertEquals(
            listOf(
                AudioChannel.FRONT_LEFT,
                AudioChannel.FRONT_RIGHT,
                AudioChannel.UNKNOWN,
                AudioChannel.UNKNOWN,
            ),
            layout.channels,
        )
        assertEquals(0, layout.waveMask)
    }
}

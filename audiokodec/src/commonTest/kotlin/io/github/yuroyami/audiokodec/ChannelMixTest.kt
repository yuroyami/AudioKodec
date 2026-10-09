package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.AudioChannel.BACK_CENTER
import io.github.yuroyami.audiokodec.AudioChannel.FRONT_CENTER
import io.github.yuroyami.audiokodec.AudioChannel.FRONT_LEFT
import io.github.yuroyami.audiokodec.AudioChannel.FRONT_RIGHT
import io.github.yuroyami.audiokodec.AudioChannel.LOW_FREQUENCY
import io.github.yuroyami.audiokodec.AudioChannel.SIDE_LEFT
import io.github.yuroyami.audiokodec.AudioChannel.SIDE_RIGHT
import io.github.yuroyami.audiokodec.AudioChannel.TOP_CENTER
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Checks channel mixing, per-channel gain and mid-side conversion on every target. */
class ChannelMixTest {

    @Test
    fun eachToneOfA51DownmixLandsAtTheLevelBs775Gives() {
        val rate = 48_000
        val tones = doubleArrayOf(500.0, 700.0, 1_100.0, 1_300.0, 1_700.0, 1_900.0)
        val frames = rate / 2
        val input = FloatArray(frames * 6) { i -> (0.1 * sin(2 * PI * tones[i % 6] * (i / 6) / rate)).toFloat() }
        val h = sqrt(0.5)
        // FL, FR, FC, LFE, BL, BR into left, right and mono.
        val expected = mapOf(
            ChannelLayout.STEREO to listOf(doubleArrayOf(1.0, 0.0, h, 0.0, h, 0.0), doubleArrayOf(0.0, 1.0, h, 0.0, 0.0, h)),
            ChannelLayout.MONO to listOf(doubleArrayOf(h, h, 1.0, 0.0, 0.5, 0.5)),
        )
        for ((layout, rows) in expected) {
            val out = Pcm.mixChannels(input, frames, ChannelLayout.SURROUND_5_1, layout)
            for ((o, row) in rows.withIndex()) {
                for ((c, gain) in row.withIndex()) {
                    val level = level(out, layout.count, o, tones[c], rate) / 0.1
                    assertEquals(gain, level, 1e-4, "$layout output $o, input ${ChannelLayout.SURROUND_5_1.channels[c]}")
                }
            }
        }
    }

    @Test
    fun theMatrixFollowsTheStatedRules() {
        val h = sqrt(0.5).toFloat()
        fun check(from: ChannelLayout, to: ChannelLayout, vararg rows: FloatArray) {
            val matrix = Pcm.mixMatrix(from, to)
            assertEquals(rows.size, matrix.size)
            for (o in rows.indices) {
                for (i in rows[o].indices) assertEquals(rows[o][i], matrix[o][i], 1e-6f, "$from to $to, [$o][$i]")
            }
        }
        // Every row of BS.775-3 Table 2, from 3/2: L, R, C, LS, RS.
        val five = ChannelLayout.SURROUND_5_0
        check(five, ChannelLayout.SURROUND_3_0, floatArrayOf(1f, 0f, 0f, h, 0f), floatArrayOf(0f, 1f, 0f, 0f, h), floatArrayOf(0f, 0f, 1f, 0f, 0f))
        check(
            five, ChannelLayout.of(FRONT_LEFT, FRONT_RIGHT, BACK_CENTER),
            floatArrayOf(1f, 0f, h, 0f, 0f), floatArrayOf(0f, 1f, h, 0f, 0f), floatArrayOf(0f, 0f, 0f, h, h),
        )
        check(
            five, ChannelLayout.of(FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, BACK_CENTER),
            floatArrayOf(1f, 0f, 0f, 0f, 0f), floatArrayOf(0f, 1f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 1f, 0f, 0f), floatArrayOf(0f, 0f, 0f, h, h),
        )
        check(
            five, ChannelLayout.QUAD,
            floatArrayOf(1f, 0f, h, 0f, 0f), floatArrayOf(0f, 1f, h, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f, 0f), floatArrayOf(0f, 0f, 0f, 0f, 1f),
        )
        check(five, ChannelLayout.STEREO, floatArrayOf(1f, 0f, h, h, 0f), floatArrayOf(0f, 1f, h, 0f, h))
        check(five, ChannelLayout.MONO, floatArrayOf(h, h, 1f, 0.5f, 0.5f))
        // Mono up to stereo at -3 dB a side; stereo up to 5.1 fills the front pair alone.
        check(ChannelLayout.MONO, ChannelLayout.STEREO, floatArrayOf(h), floatArrayOf(h))
        check(
            ChannelLayout.STEREO, ChannelLayout.SURROUND_5_1,
            floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(0f, 0f), floatArrayOf(0f, 0f), floatArrayOf(0f, 0f), floatArrayOf(0f, 0f),
        )
        // 7.1 into 5.1: the sides join the backs at -3 dB.
        check(
            ChannelLayout.SURROUND_7_1, ChannelLayout.SURROUND_5_1,
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f),
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f),
            floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, h, 0f), floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 0f, h),
        )
        // 6.1 into 5.1: the back centre splits between the backs, and the sides take their place whole.
        val sixOne = ChannelLayout.of(FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, LOW_FREQUENCY, BACK_CENTER, SIDE_LEFT, SIDE_RIGHT)
        check(
            sixOne, ChannelLayout.SURROUND_5_1,
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f), floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 0f),
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f),
            floatArrayOf(0f, 0f, 0f, 0f, h, 1f, 0f), floatArrayOf(0f, 0f, 0f, 0f, h, 0f, 1f),
        )
        check(sixOne, ChannelLayout.MONO, floatArrayOf(h, h, 1f, 0f, 0.5f, 0.5f, 0.5f))
    }

    @Test
    fun aConversionWithNoDefinedAnswerIsRefused() {
        assertFailsWith<UnsupportedFeatureException> { Pcm.mixMatrix(ChannelLayout.unknown(2), ChannelLayout.STEREO) }
        assertFailsWith<UnsupportedFeatureException> { Pcm.mixMatrix(ChannelLayout.of(FRONT_LEFT, FRONT_RIGHT, TOP_CENTER), ChannelLayout.STEREO) }
        assertFailsWith<UnsupportedFeatureException> { Pcm.mixMatrix(ChannelLayout.STEREO, ChannelLayout.of(LOW_FREQUENCY)) }
        // A layout of unknown positions still mixes into itself.
        val same = FloatArray(6) { it.toFloat() }
        assertContentEquals(same, Pcm.mixChannels(same, 3, ChannelLayout.unknown(2), ChannelLayout.unknown(2)))
    }

    @Test
    fun midSideRoundTripsAudioOfUpToTwentyThreeBitsExactly() {
        val random = Random(5)
        for (bits in listOf(16, 23)) {
            val scale = (1 shl (bits - 1)).toFloat()
            val input = FloatArray(20_000) { (random.nextInt(-(1 shl (bits - 1)), 1 shl (bits - 1))) / scale }
            val ms = Pcm.stereoToMidSide(input, 10_000)
            assertContentEquals(input, Pcm.midSideToStereo(ms, 10_000), "$bits-bit")
            // In place as well.
            val copy = input.copyOf()
            Pcm.midSideToStereo(Pcm.stereoToMidSide(copy, 10_000, into = copy), 10_000, into = copy)
            assertContentEquals(input, copy, "$bits-bit, in place")
        }
        // A left-only signal has equal mid and side.
        val ms = Pcm.stereoToMidSide(floatArrayOf(0.5f, 0f), 1)
        assertContentEquals(floatArrayOf(0.25f, 0.25f), ms)
    }

    @Test
    fun channelGainsScaleEachChannelOnItsOwn() {
        val samples = FloatArray(9) { 1f }
        Pcm.applyChannelGains(samples, 3, floatArrayOf(0.5f, 2f, 0f))
        assertContentEquals(floatArrayOf(0.5f, 2f, 0f, 0.5f, 2f, 0f, 0.5f, 2f, 0f), samples)
    }

    /** The amplitude of the tone at [f] Hz in [channel], over whole seconds' worth of samples. */
    private fun level(samples: FloatArray, channels: Int, channel: Int, f: Double, rate: Int): Double {
        val frames = samples.size / channels
        var re = 0.0
        var im = 0.0
        for (k in 0 until frames) {
            val v = samples[k * channels + channel]
            re += v * cos(2 * PI * f * k / rate)
            im += v * sin(2 * PI * f * k / rate)
        }
        return 2 * hypot(re, im) / frames
    }
}

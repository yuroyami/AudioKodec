package io.github.yuroyami.audiokodec.internal.dsp

import io.github.yuroyami.audiokodec.AudioChannel
import io.github.yuroyami.audiokodec.AudioChannel.BACK_CENTER
import io.github.yuroyami.audiokodec.AudioChannel.BACK_LEFT
import io.github.yuroyami.audiokodec.AudioChannel.BACK_RIGHT
import io.github.yuroyami.audiokodec.AudioChannel.FRONT_CENTER
import io.github.yuroyami.audiokodec.AudioChannel.FRONT_LEFT
import io.github.yuroyami.audiokodec.AudioChannel.FRONT_LEFT_OF_CENTER
import io.github.yuroyami.audiokodec.AudioChannel.FRONT_RIGHT
import io.github.yuroyami.audiokodec.AudioChannel.FRONT_RIGHT_OF_CENTER
import io.github.yuroyami.audiokodec.AudioChannel.LOW_FREQUENCY
import io.github.yuroyami.audiokodec.AudioChannel.SIDE_LEFT
import io.github.yuroyami.audiokodec.AudioChannel.SIDE_RIGHT
import io.github.yuroyami.audiokodec.AudioChannel.TOP_FRONT_LEFT
import io.github.yuroyami.audiokodec.AudioChannel.TOP_FRONT_RIGHT
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.UnsupportedFeatureException

/**
 * The coefficients that carry each channel of one layout into another.
 *
 * The downmix is the one of ITU-R BS.775: the centre goes to the front pair
 * at -3 dB, each surround to its own side's front at -3 dB, and for mono the
 * front pair at -3 dB each, the centre whole and each surround at -6 dB. The
 * low-frequency channel is left out, as BS.775 leaves it. A channel the
 * output has keeps its place at unity, so nothing is upmixed by invention:
 * stereo into 5.1 fills the front pair and leaves the rest silent. Mono into
 * stereo puts the centre on both sides at -3 dB, by the same rule as the
 * centre of any layout.
 *
 * Beyond BS.775, which names only the front, centre and surround channels:
 * a side pair and a back pair stand in for each other, whole when the input
 * has only one of them and at -3 dB for the second when it has both; a back
 * centre goes to the two surrounds at -3 dB each, or to the front pair at -6
 * dB each; the front left and right of centre join their side's front; and
 * the top front pair joins the front pair at -3 dB. Every one of these is
 * what ffmpeg's own mixing does, measured rather than read.
 *
 * Anything else, a channel whose position is unknown or one with nowhere to
 * go, has no defined answer, and the conversion is refused by name.
 */
internal object ChannelMixing {
    private const val MINUS_3_DB = 0.70710678118654752
    private const val MINUS_6_DB = 0.5

    /** Coefficients by output channel, then input channel. */
    fun matrix(from: ChannelLayout, to: ChannelLayout): Array<DoubleArray> {
        if (from == to) return Array(to.count) { o -> DoubleArray(from.count) { if (it == o) 1.0 else 0.0 } }
        if (!from.isPositional || !to.isPositional) {
            throw UnsupportedFeatureException(
                "mixing channels of unknown position",
                "a layout with channels of unknown position can be mixed only into itself, not from $from into $to",
            )
        }
        val out = to.channels
        val input = from.channels
        val matrix = Array(out.size) { DoubleArray(input.size) }
        fun has(c: AudioChannel) = c in out
        fun put(i: Int, c: AudioChannel, gain: Double) {
            matrix[out.indexOf(c)][i] += gain
        }
        fun pair(i: Int, left: AudioChannel, right: AudioChannel, gain: Double) {
            put(i, left, gain)
            put(i, right, gain)
        }
        fun refuse(c: AudioChannel): Nothing = throw UnsupportedFeatureException(
            "mixing $c into $to",
            "there is no defined way to carry $c into $to",
        )
        /** Sends a channel on its [side] of the front, or into the centre for a layout without a front pair. */
        fun front(i: Int, c: AudioChannel, side: AudioChannel, gain: Double) {
            when {
                has(side) -> put(i, side, gain)
                has(FRONT_CENTER) -> put(i, FRONT_CENTER, gain * MINUS_3_DB)
                else -> refuse(c)
            }
        }
        val inputHasBack = BACK_LEFT in input || BACK_RIGHT in input
        val inputHasSide = SIDE_LEFT in input || SIDE_RIGHT in input
        for ((i, c) in input.withIndex()) {
            if (has(c)) {
                put(i, c, 1.0)
                continue
            }
            when (c) {
                LOW_FREQUENCY -> Unit
                FRONT_CENTER -> if (has(FRONT_LEFT) && has(FRONT_RIGHT)) pair(i, FRONT_LEFT, FRONT_RIGHT, MINUS_3_DB) else refuse(c)
                FRONT_LEFT, FRONT_RIGHT ->
                    if (has(FRONT_CENTER)) put(i, FRONT_CENTER, MINUS_3_DB) else refuse(c)
                FRONT_LEFT_OF_CENTER -> front(i, c, FRONT_LEFT, 1.0)
                FRONT_RIGHT_OF_CENTER -> front(i, c, FRONT_RIGHT, 1.0)
                TOP_FRONT_LEFT -> front(i, c, FRONT_LEFT, MINUS_3_DB)
                TOP_FRONT_RIGHT -> front(i, c, FRONT_RIGHT, MINUS_3_DB)
                BACK_LEFT, BACK_RIGHT, SIDE_LEFT, SIDE_RIGHT -> {
                    val left = c == BACK_LEFT || c == SIDE_LEFT
                    val back = c == BACK_LEFT || c == BACK_RIGHT
                    val other = when (c) {
                        BACK_LEFT -> SIDE_LEFT
                        BACK_RIGHT -> SIDE_RIGHT
                        SIDE_LEFT -> BACK_LEFT
                        else -> BACK_RIGHT
                    }
                    when {
                        has(other) -> put(i, other, if ((if (back) inputHasSide else inputHasBack)) MINUS_3_DB else 1.0)
                        has(BACK_CENTER) -> put(i, BACK_CENTER, MINUS_3_DB)
                        else -> front(i, c, if (left) FRONT_LEFT else FRONT_RIGHT, MINUS_3_DB)
                    }
                }
                BACK_CENTER -> when {
                    has(BACK_LEFT) && has(BACK_RIGHT) -> pair(i, BACK_LEFT, BACK_RIGHT, MINUS_3_DB)
                    has(SIDE_LEFT) && has(SIDE_RIGHT) -> pair(i, SIDE_LEFT, SIDE_RIGHT, MINUS_3_DB)
                    has(FRONT_LEFT) && has(FRONT_RIGHT) -> pair(i, FRONT_LEFT, FRONT_RIGHT, MINUS_6_DB)
                    has(FRONT_CENTER) -> put(i, FRONT_CENTER, MINUS_6_DB)
                    else -> refuse(c)
                }
                else -> refuse(c)
            }
        }
        return matrix
    }
}

package io.github.yuroyami.audiokodec

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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks [Pcm.mixChannels] against ffmpeg's own channel mixing, which
 * converts float audio between layouts with the same coefficients and no
 * normalisation. A different tone in every input channel makes each
 * coefficient visible; the two outputs must agree sample for sample within
 * float rounding.
 */
class ChannelMixOracleTest {

    @Test
    fun everyMixAgreesWithFfmpegs() {
        val ffmpeg = findOnPath("ffmpeg")
        assumeTrue("ffmpeg is required for the channel mixing oracle", ffmpeg != null)
        val sixOne = ChannelLayout.of(FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, LOW_FREQUENCY, BACK_CENTER, SIDE_LEFT, SIDE_RIGHT)
        val fiveOneSide = ChannelLayout.of(FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, LOW_FREQUENCY, SIDE_LEFT, SIDE_RIGHT)
        val cases = listOf(
            ChannelLayout.SURROUND_5_1 to ChannelLayout.STEREO,
            ChannelLayout.SURROUND_5_1 to ChannelLayout.MONO,
            ChannelLayout.SURROUND_5_1 to ChannelLayout.QUAD,
            ChannelLayout.SURROUND_5_0 to ChannelLayout.STEREO,
            ChannelLayout.SURROUND_3_0 to ChannelLayout.STEREO,
            ChannelLayout.QUAD to ChannelLayout.STEREO,
            ChannelLayout.QUAD to ChannelLayout.MONO,
            ChannelLayout.SURROUND_7_1 to ChannelLayout.STEREO,
            ChannelLayout.SURROUND_7_1 to ChannelLayout.SURROUND_5_1,
            fiveOneSide to ChannelLayout.STEREO,
            fiveOneSide to ChannelLayout.SURROUND_5_1,
            sixOne to ChannelLayout.SURROUND_5_1,
            sixOne to ChannelLayout.STEREO,
            sixOne to ChannelLayout.MONO,
            ChannelLayout.MONO to ChannelLayout.STEREO,
            ChannelLayout.STEREO to ChannelLayout.MONO,
            ChannelLayout.STEREO to ChannelLayout.SURROUND_5_1,
            ChannelLayout.MONO to ChannelLayout.SURROUND_5_1,
            ChannelLayout.of(FRONT_LEFT, FRONT_RIGHT, FRONT_LEFT_OF_CENTER, FRONT_RIGHT_OF_CENTER) to ChannelLayout.STEREO,
            ChannelLayout.of(FRONT_LEFT, FRONT_RIGHT, FRONT_LEFT_OF_CENTER, FRONT_RIGHT_OF_CENTER) to ChannelLayout.MONO,
            ChannelLayout.of(FRONT_LEFT, FRONT_RIGHT, TOP_FRONT_LEFT, TOP_FRONT_RIGHT) to ChannelLayout.STEREO,
            ChannelLayout.of(FRONT_LEFT, FRONT_RIGHT, BACK_CENTER) to ChannelLayout.STEREO,
            ChannelLayout.of(FRONT_LEFT, FRONT_RIGHT, BACK_CENTER) to ChannelLayout.QUAD,
            ChannelLayout.of(FRONT_LEFT, FRONT_RIGHT, SIDE_LEFT, SIDE_RIGHT) to ChannelLayout.of(FRONT_LEFT, FRONT_RIGHT, BACK_CENTER),
        )
        val rate = 48_000
        val frames = 4_800
        for ((from, to) in cases) {
            val input = FloatArray(frames * from.count) { i ->
                (0.1 * sin(2 * PI * (300.0 + 200 * (i % from.count)) * (i / from.count) / rate)).toFloat()
            }
            val ours = Pcm.mixChannels(input, frames, from, to)
            val source = File(workDirectory, "in.f32").also { it.writeBytes(f32(input)) }
            val output = File(workDirectory, "out.f32")
            run(
                listOf(
                    ffmpeg!!, "-y", "-v", "error", "-f", "f32le", "-ar", "$rate", "-ch_layout", ffmpegLayout(from), "-i", source.path,
                    "-af", "aformat=channel_layouts=${ffmpegLayout(to)}", "-f", "f32le", output.path,
                ),
            )
            val theirs = floats(output.readBytes())
            assertEquals(ours.size, theirs.size, "$from to $to: length")
            var worst = 0.0
            for (i in ours.indices) worst = maxOf(worst, abs(ours[i].toDouble() - theirs[i]))
            assertTrue(worst < 1e-6, "$from to $to: samples differ by up to $worst")
        }
    }

    private fun ffmpegLayout(layout: ChannelLayout): String = layout.channels.joinToString("+") {
        when (it) {
            FRONT_LEFT -> "FL"
            FRONT_RIGHT -> "FR"
            FRONT_CENTER -> "FC"
            LOW_FREQUENCY -> "LFE"
            BACK_LEFT -> "BL"
            BACK_RIGHT -> "BR"
            FRONT_LEFT_OF_CENTER -> "FLC"
            FRONT_RIGHT_OF_CENTER -> "FRC"
            BACK_CENTER -> "BC"
            SIDE_LEFT -> "SL"
            SIDE_RIGHT -> "SR"
            TOP_FRONT_LEFT -> "TFL"
            TOP_FRONT_RIGHT -> "TFR"
            else -> error("no ffmpeg name for $it")
        }
    }

    private fun f32(samples: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(samples)
        return buffer.array()
    }

    private fun floats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS), "${command.first()} did not finish")
        if (process.exitValue() != 0) throw AssertionError("${File(command.first()).name} failed:\n${output.take(2000)}")
    }

    private companion object {
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-mix-oracle").also { it.mkdirs() }

        fun findOnPath(tool: String): String? {
            val path = System.getenv("PATH") ?: return null
            for (directory in path.split(File.pathSeparator)) {
                val candidate = File(directory, tool)
                if (candidate.canExecute()) return candidate.absolutePath
            }
            return null
        }
    }
}

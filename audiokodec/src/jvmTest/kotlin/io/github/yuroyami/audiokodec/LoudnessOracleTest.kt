package io.github.yuroyami.audiokodec

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks [LoudnessMeter] against libebur128 1.2.6, built from the pinned
 * sources under `reference/ebur128`, and against ffmpeg's `ebur128` filter.
 *
 * Against libebur128 the figures must agree to within double rounding: the
 * meter is a translation of it. Against ffmpeg, an independent
 * implementation that reports integrated loudness to a tenth of a unit, they
 * must agree within a tenth.
 */
class LoudnessOracleTest {

    private class Case(val name: String, val rate: Int, val layout: ChannelLayout, val samples: FloatArray)

    private val cases: List<Case> by lazy {
        val random = Random(9)
        fun tone(rate: Int, channels: Int, seconds: Double, db: Double, f: Double) = FloatArray((rate * seconds).toInt() * channels) { i ->
            (10.0.pow(db / 20) * sin(2 * PI * f * (i / channels) / rate)).toFloat()
        }
        // Loud and quiet passages, which the gates and the loudness range must tell apart.
        fun programme(rate: Int, channels: Int): FloatArray {
            val frames = rate * 20
            return FloatArray(frames * channels) { i ->
                val k = i / channels
                val level = if ((k / (rate * 2)) % 3 == 0) -40.0 else -18.0 + 6 * sin(2 * PI * k / (rate * 7.0))
                (10.0.pow(level / 20) * (sin(2 * PI * (220.0 + 110 * (i % channels)) * k / rate) + 0.3 * (random.nextDouble() - 0.5))).toFloat()
            }
        }
        listOf(
            Case("1 kHz at -23 dBFS, stereo, 48 kHz", 48_000, ChannelLayout.STEREO, tone(48_000, 2, 20.0, -23.0, 1_000.0)),
            Case("1 kHz at -33 dBFS, stereo, 44.1 kHz", 44_100, ChannelLayout.STEREO, tone(44_100, 2, 20.0, -33.0, 1_000.0)),
            Case("a programme, stereo, 44.1 kHz", 44_100, ChannelLayout.STEREO, programme(44_100, 2)),
            Case("a programme, mono, 32 kHz", 32_000, ChannelLayout.MONO, programme(32_000, 1)),
            Case("a programme, 5.1, 48 kHz", 48_000, ChannelLayout.SURROUND_5_1, programme(48_000, 6)),
            Case("a programme, stereo, 96 kHz", 96_000, ChannelLayout.STEREO, programme(96_000, 2)),
        )
    }

    @Test
    fun everyFigureIsLibebur128s() {
        val binary = reference()
        for (case in cases) {
            val channels = case.layout.count
            val meter = LoudnessMeter(case.rate, case.layout)
            // Blocks of uneven size, which the gating must not notice.
            var at = 0
            val frames = case.samples.size / channels
            val random = Random(1)
            while (at < frames) {
                val take = minOf(frames - at, 1 + random.nextInt(9_000))
                meter.add(case.samples, at, take)
                at += take
            }
            val expected = referenceMeasure(binary, case.samples, channels, case.rate)
            fun same(what: String, reference: Double, actual: Double) =
                assertTrue(abs(reference - actual) <= 1e-9 * maxOf(1.0, abs(reference)), "${case.name}: $what is $actual, libebur128's $reference")
            same("integrated loudness", expected[0], meter.integratedLoudness)
            same("loudness range", expected[1], meter.loudnessRange)
            same("momentary loudness", expected[2], meter.momentaryLoudness)
            same("short-term loudness", expected[3], meter.shortTermLoudness)
            for (c in 0 until channels) same("sample peak $c", expected[4 + c], meter.samplePeak(c))
        }
    }

    @Test
    fun integratedLoudnessIsWithinATenthOfFfmpegs() {
        val ffmpeg = findOnPath("ffmpeg")
        assumeTrue("ffmpeg is required for the loudness oracle", ffmpeg != null)
        for (case in cases) {
            val meter = LoudnessMeter(case.rate, case.layout).also { it.add(case.samples) }
            val source = File(workDirectory, "in.f32").also { it.writeBytes(f32(case.samples)) }
            val layout = if (case.layout == ChannelLayout.SURROUND_5_1) "5.1" else if (case.layout.count == 1) "mono" else "stereo"
            val output = run(
                listOf(
                    ffmpeg!!, "-hide_banner", "-nostats", "-f", "f32le", "-ar", "${case.rate}", "-ch_layout", layout, "-i", source.path,
                    "-af", "ebur128=framelog=quiet", "-f", "null", "-",
                ),
            )
            val integrated = Regex("""I:\s+(-?[0-9.]+) LUFS""").findAll(output).last().groupValues[1].toDouble()
            assertEquals(integrated, meter.integratedLoudness, 0.1, case.name)
        }
    }

    // Helpers.

    private fun reference(): String {
        val dir = File(System.getProperty("user.dir"))
        val root = listOf(dir, dir.parentFile).map { File(it, "reference/ebur128") }.firstOrNull { File(it, "ebur128/ebur128.c").isFile }
        assumeTrue("the libebur128 1.2.6 sources are required, under reference/ebur128", root != null)
        val binary = File(workDirectory, "ebur128_oracle")
        if (!binary.canExecute()) {
            val harness = File(workDirectory, "ebur128_oracle.c")
            harness.writeBytes(LoudnessOracleTest::class.java.getResourceAsStream("/ebur128_oracle.c")!!.readBytes())
            run(
                listOf(
                    "cc", "-O2", "-ffp-contract=off", "-I", File(root, "ebur128").path, "-I", File(root, "ebur128/queue").path,
                    "-o", binary.path, harness.path, File(root, "ebur128/ebur128.c").path, "-lm",
                ),
            )
        }
        return binary.path
    }

    private fun referenceMeasure(binary: String, samples: FloatArray, channels: Int, rate: Int): DoubleArray {
        val source = File(workDirectory, "measure.f32").also { it.writeBytes(f32(samples)) }
        val process = ProcessBuilder(binary, "$channels", "$rate", "4096").redirectInput(source).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS), "libebur128 did not finish")
        assertEquals(0, process.exitValue(), "libebur128 failed")
        return output.lines().filter { it.isNotBlank() }.map { if (it == "-inf") Double.NEGATIVE_INFINITY else it.toDouble() }.toDoubleArray()
    }

    private fun f32(samples: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(samples)
        return buffer.array()
    }

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS), "${command.first()} did not finish")
        if (process.exitValue() != 0) {
            val errors = output.lines().filter { it.contains("error", ignoreCase = true) }.take(20).joinToString("\n")
            throw AssertionError("${File(command.first()).name} failed:\n$errors")
        }
        return output
    }

    private companion object {
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-loudness-oracle").also { it.mkdirs() }

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

package io.github.yuroyami.audiokodec

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks [Resampler] against the reference it translates, speexdsp 1.2.1's
 * resampler built from the pinned sources under `reference/speexdsp`, and
 * against ffmpeg's soxr resampler at 28 bits, its highest quality.
 *
 * The figures in the bounds were measured this way: a sine at -6 dBFS is
 * converted, a sine of its frequency is fitted to the middle half of the
 * result by least squares, and whatever the fit leaves is counted as
 * distortion and noise, relative to the fitted sine's power. At 1 kHz the
 * sinc mode measured between -134.8 dB (16 to 44.1 kHz) and -169.3 dB (48 to
 * 8 kHz) across the pairs below, and soxr between -150.5 and -161.4 dB; at
 * 40% of the lower rate the sinc mode measured -129.1 dB at worst (44.1 to
 * 48 kHz). The bounds leave a few dB above the worst of those.
 */
class ResamplerOracleTest {

    @Test
    fun everySampleIsTheReferencesOwn() {
        val binary = reference()
        val random = Random(7)
        for ((from, to) in PAIRS) {
            for (channels in listOf(1, 2)) {
                val frames = from / 2 + 123
                val input = FloatArray(frames * channels) { i ->
                    val k = i / channels
                    (0.4 * sin(2 * PI * 997.0 * k / from + i % channels) + 0.2 * (random.nextFloat() - 0.5f)).toFloat()
                }
                val expected = referenceResample(binary, input, channels, from, to)
                val actual = streamed(input, channels, from, to, random)
                assertEquals(expected.size, actual.size, "$from to $to Hz, $channels channels: length")
                for (i in expected.indices) {
                    if (expected[i] != actual[i]) {
                        throw AssertionError("$from to $to Hz, $channels channels: sample $i is ${actual[i]}, the reference's is ${expected[i]}")
                    }
                }
            }
        }
    }

    @Test
    fun distortionStaysUnderTheMeasuredFigures() {
        for ((from, to) in PAIRS) {
            for ((f, bound) in listOf(1_000.0 to -130.0, 0.4 * minOf(from, to) to -125.0)) {
                val output = Pcm.resample(tone(from, f), 1, from, to)
                val measured = fit(output, to.toDouble(), f).distortionDb
                assertTrue(measured < bound, "$from to $to Hz at $f Hz: distortion and noise at $measured dB, not under $bound dB")
            }
        }
    }

    @Test
    fun theOutputAgreesWithFfmpegsSoxrWithinThePassbandRipple() {
        val ffmpeg = findOnPath("ffmpeg")
        assumeTrue("ffmpeg is required for the resampler oracle", ffmpeg != null)
        for ((from, to) in PAIRS) {
            for (f in listOf(1_000.0, 0.4 * minOf(from, to))) {
                val input = tone(from, f)
                val ours = Pcm.resample(input, 1, from, to)
                val theirs = soxr(ffmpeg!!, input, from, to)
                assertEquals(theirs.size, ours.size, "$from to $to Hz: length")
                val a = fit(ours, to.toDouble(), f)
                val b = fit(theirs, to.toDouble(), f)
                assertTrue(b.distortionDb < -145.0, "$from to $to Hz at $f Hz: soxr measured ${b.distortionDb} dB")
                // speexdsp's passband ripple reaches 0.001 dB at 80% of the Nyquist frequency.
                val level = 20 * log10(a.amplitude / b.amplitude)
                assertTrue(abs(level) < 0.002, "$from to $to Hz at $f Hz: levels differ by $level dB")
                assertTrue(abs(a.phase - b.phase) < 1e-6, "$from to $to Hz at $f Hz: phases differ by ${a.phase - b.phase} rad")
            }
        }
    }

    // Helpers.

    /** Converts [input] through a [Resampler] in blocks of random sizes, reusing one output array. */
    private fun streamed(input: FloatArray, channels: Int, from: Int, to: Int, random: Random): FloatArray {
        val resampler = Resampler(from, to, channels)
        val frames = input.size / channels
        val result = ArrayList<Float>()
        val block = FloatArray(resampler.maxOutputFrames(4_096) * channels)
        var at = 0
        while (at < frames) {
            val take = minOf(frames - at, 1 + random.nextInt(4_096))
            val written = resampler.process(input, at, take, block, 0)
            for (i in 0 until written * channels) result.add(block[i])
            at += take
        }
        val tail = FloatArray(resampler.pendingOutputFrames * channels)
        resampler.flush(tail)
        for (v in tail) result.add(v)
        return result.toFloatArray()
    }

    private fun tone(rate: Int, f: Double): FloatArray = FloatArray(rate) { (0.5 * sin(2 * PI * f * it / rate)).toFloat() }

    private class Fit(val distortionDb: Double, val amplitude: Double, val phase: Double)

    /** Fits a sine of [f] Hz to the middle half of [y] by least squares. */
    private fun fit(y: FloatArray, rate: Double, f: Double): Fit {
        val start = y.size / 4
        val end = 3 * y.size / 4
        var ss = 0.0
        var cc = 0.0
        var sc = 0.0
        var ys = 0.0
        var yc = 0.0
        for (k in start until end) {
            val s = sin(2 * PI * f * k / rate)
            val c = cos(2 * PI * f * k / rate)
            ss += s * s
            cc += c * c
            sc += s * c
            ys += y[k] * s
            yc += y[k] * c
        }
        val det = ss * cc - sc * sc
        val a = (ys * cc - yc * sc) / det
        val b = (yc * ss - ys * sc) / det
        var residual = 0.0
        var signal = 0.0
        for (k in start until end) {
            val model = a * sin(2 * PI * f * k / rate) + b * cos(2 * PI * f * k / rate)
            residual += (y[k] - model) * (y[k] - model)
            signal += model * model
        }
        return Fit(10 * log10(residual / signal), hypot(a, b), atan2(b, a))
    }

    private fun soxr(ffmpeg: String, input: FloatArray, from: Int, to: Int): FloatArray {
        val source = File(workDirectory, "soxr_$from.f32").also { it.writeBytes(f32(input)) }
        val output = File(workDirectory, "soxr_${from}_$to.f32")
        run(
            listOf(
                ffmpeg, "-y", "-v", "error", "-f", "f32le", "-ar", "$from", "-ac", "1", "-i", source.path,
                "-af", "aresample=resampler=soxr:precision=28", "-ar", "$to", "-f", "f32le", output.path,
            ),
        )
        return floats(output.readBytes())
    }

    private fun reference(): String {
        val dir = File(System.getProperty("user.dir"))
        val root = listOf(dir, dir.parentFile).map { File(it, "reference/speexdsp") }.firstOrNull { File(it, "libspeexdsp/resample.c").isFile }
        assumeTrue("the speexdsp 1.2.1 sources are required, under reference/speexdsp", root != null)
        val binary = File(workDirectory, "resample_oracle")
        if (!binary.canExecute()) {
            val harness = File(workDirectory, "resample_oracle.c")
            harness.writeBytes(ResamplerOracleTest::class.java.getResourceAsStream("/resample_oracle.c")!!.readBytes())
            // The float build, standalone, with no SIMD and no fused multiply-adds, which would change the sums.
            run(
                listOf(
                    "cc", "-O2", "-ffp-contract=off", "-DOUTSIDE_SPEEX", "-DFLOATING_POINT", "-DRANDOM_PREFIX=speex",
                    "-I", File(root, "include/speex").path, "-I", File(root, "include").path, "-I", File(root, "libspeexdsp").path,
                    "-o", binary.path, harness.path, File(root, "libspeexdsp/resample.c").path, "-lm",
                ),
            )
        }
        return binary.path
    }

    private fun referenceResample(binary: String, input: FloatArray, channels: Int, from: Int, to: Int): FloatArray {
        val source = File(workDirectory, "in.f32").also { it.writeBytes(f32(input)) }
        val output = File(workDirectory, "out.f32")
        val process = ProcessBuilder(binary, "$channels", "$from", "$to", "10", "1000")
            .redirectInput(source).redirectOutput(output).oracleStart()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS), "the reference resampler did not finish")
        assertEquals(0, process.exitValue(), "the reference resampler failed")
        return floats(output.readBytes())
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
        if (process.exitValue() != 0) {
            // A compiler's output can quote the sources it reads, so only the lines it reports as errors are shown.
            val errors = output.lines().filter { it.contains("error", ignoreCase = true) }.take(20).joinToString("\n")
            throw AssertionError("${File(command.first()).name} failed:\n$errors")
        }
    }

    private companion object {
        val PAIRS = listOf(
            44_100 to 48_000, 48_000 to 44_100, 48_000 to 96_000, 96_000 to 48_000, 44_100 to 96_000, 96_000 to 44_100,
            8_000 to 48_000, 48_000 to 8_000, 16_000 to 44_100, 22_050 to 44_100, 32_000 to 48_000, 88_200 to 44_100,
        )
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-resampler-oracle").also { it.mkdirs() }

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

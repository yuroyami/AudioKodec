package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagField
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks the Musepack decoder against libmpcdec r495, the code it is
 * translated from, on files Musepack's own encoder, `mpcenc`, writes.
 *
 * The `mpcdec` tool writes only 16-bit samples, so the reference here is
 * `mpc_float_oracle.c`, a few lines over libmpcdec's public API that write
 * the decoder's own floats, compiled against the r495 sources under
 * `reference/mpc` (see `reference/REFERENCES.md`).
 *
 * The bound is zero: every sample must be the same float. The port keeps the
 * reference's single-precision arithmetic, its constants and the order of
 * every addition, and the JVM rounds each float operation as C does on x86-64
 * without fused multiply-adds, so there is nothing to tolerate. A difference
 * of one unit in the last place would mean an operation was reordered.
 */
class MusepackOracleTest {

    @Test
    fun everyQualityDecodesToExactlyTheReferenceFloats() {
        val tools = tools()
        val cases = mutableListOf<Case>()
        for (quality in listOf(0, 2, 3, 4, 5, 6, 7, 8, 10)) cases += Case(quality, 2, 44_100)
        for (rate in listOf(32_000, 48_000)) cases += Case(5, 2, rate)
        cases += Case(5, 1, 44_100)
        cases += Case(3, 1, 48_000)
        for (ms in listOf(0, 1, 3)) cases += Case(5, 2, 44_100, extra = listOf("--ms", "$ms"))
        cases += Case(5, 2, 44_100, extra = listOf("--pns", "0.5"))
        cases += Case(2, 2, 44_100, extra = listOf("--pns", "1.0"))
        cases += Case(5, 2, 44_100, silent = true)
        cases += Case(5, 2, 44_100, frames = 1)
        cases += Case(5, 2, 44_100, frames = 1700)
        cases += Case(5, 2, 44_100, loud = true)
        for (case in cases) {
            val file = encode(tools, case)
            val bytes = file.readBytes()
            val info = AudioKodec.probe(bytes)
            assertEquals(AudioContainer.MUSEPACK, info.container, case.name)
            assertEquals(AudioCodec.MUSEPACK, info.codec, case.name)
            assertEquals(case.channels, info.format.channels, case.name)
            assertEquals(case.rate, info.format.sampleRate, case.name)
            assertEquals(case.frames.toLong(), info.durationFrames, case.name)
            val reference = referenceDecode(tools, file, case)
            val decoded = AudioKodec.decode(bytes).samples
            assertEquals(reference.size, decoded.size, "${case.name}: length")
            for (i in reference.indices) {
                if (reference[i].toRawBits() != decoded[i].toRawBits()) {
                    throw AssertionError("${case.name}: sample $i is ${decoded[i]}, not ${reference[i]}")
                }
            }
        }
    }

    @Test
    fun seekingLandsOnTheSamplesAContinuousDecodeGives() {
        val tools = tools()
        val case = Case(5, 2, 44_100, frames = 400_000)
        val bytes = encode(tools, case).readBytes()
        val whole = AudioKodec.decode(bytes).samples
        for (target in listOf(0L, 1L, 1151L, 1152L, 9000L, 147_455L, 147_456L, 300_000L, 399_999L)) {
            AudioKodec.open(bytes).use { decoder ->
                assertEquals(target, decoder.seek(target))
                val frame = decoder.nextFrame()!!
                for (i in 0 until minOf(frame.frameCount, 300) * 2) {
                    assertEquals(whole[(target * 2).toInt() + i], frame.samples[i], "seek to $target, sample $i")
                }
            }
        }
    }

    @Test
    fun tagsAndTruncationAreHandled() {
        val tools = tools()
        val case = Case(5, 2, 44_100, extra = listOf("--tag", "Title=Musepack oracle"))
        val bytes = encode(tools, case).readBytes()
        assertEquals("Musepack oracle", AudioKodec.readTags(bytes)[TagField.TITLE])
        assertEquals(case.frames, AudioKodec.decode(bytes).frames.toInt())
        assertFailsWith<TruncatedStreamException> { AudioKodec.decode(bytes.copyOf(bytes.size / 2)) }
    }

    // Helpers.

    private class Tools(val mpcenc: String, val oracle: String)

    private class Case(
        val quality: Int,
        val channels: Int,
        val rate: Int,
        val extra: List<String> = emptyList(),
        val silent: Boolean = false,
        val loud: Boolean = false,
        val frames: Int = 100_000,
    ) {
        val name = "quality $quality, $channels ch, $rate Hz${if (extra.isEmpty()) "" else ", " + extra.joinToString(" ")}" +
            "${if (silent) ", silent" else ""}${if (loud) ", loud" else ""}, $frames frames"
        val stem = name.replace(Regex("[^A-Za-z0-9]+"), "_")
    }

    private fun tools(): Tools {
        val ffmpeg = findOnPath("ffmpeg")
        assumeTrue("ffmpeg is required for the Musepack oracle", ffmpeg != null)
        val mpcenc = findOnPath("mpcenc")
        assumeTrue("Musepack's mpcenc is required for the Musepack oracle", mpcenc != null)
        val dir = File(System.getProperty("user.dir"))
        val sources = listOf(dir, dir.parentFile).map { File(it, "reference/mpc/r495/musepack_src_r495") }.firstOrNull { File(it, "libmpcdec").isDirectory }
        assumeTrue("the libmpcdec r495 sources are required, under reference/mpc", sources != null)
        val source = File(workDirectory, "mpc_float_oracle.c")
        source.writeBytes(MusepackOracleTest::class.java.getResourceAsStream("/mpc_float_oracle.c")!!.readBytes())
        val binary = File(workDirectory, "mpc_float_oracle")
        val inputs = File(sources!!, "libmpcdec").listFiles()!!.filter { it.name.endsWith(".c") }.map { it.path } +
            File(sources, "common/crc32.c").path
        run(listOf("cc", "-O2", "-fcommon", "-I", File(sources, "include").path, "-o", binary.path, source.path) + inputs + "-lm")
        return Tools(mpcenc!!, binary.path)
    }

    /** Encodes the test signal for [case] with mpcenc. */
    private fun encode(tools: Tools, case: Case): File {
        val wav = File(workDirectory, "${case.stem}.wav")
        val expressions = (0 until case.channels).map { c ->
            when {
                case.silent -> "0"
                case.loud -> "0.99*sin(2*PI*${110 * (c + 1)}*t)+0.3*(random(${c + 1})-0.5)"
                else -> "0.3*sin(2*PI*${220 * (c + 2)}*t)+0.2*sin(2*PI*(100+${700 + 150 * c}*t)*t)+0.15*(random(${c + 1})-0.5)" +
                    "+0.3*lt(mod(t+${0.03 * c}\\,0.31)\\,0.004)"
            }
        }
        run(
            listOf(
                findOnPath("ffmpeg")!!, "-y", "-v", "error", "-f", "lavfi",
                "-i", "aevalsrc=${expressions.joinToString("|")}:s=${case.rate}:d=${"%.9f".format(java.util.Locale.ROOT, (case.frames + 0.5) / case.rate)}",
                "-c:a", "pcm_s16le", "-map_metadata", "-1", "-fflags", "+bitexact", wav.path,
            ),
        )
        val file = File(workDirectory, "${case.stem}.mpc")
        file.delete()
        run(listOf(tools.mpcenc, "--silent", "--overwrite", "--quality", "${case.quality}") + case.extra + listOf(wav.path, file.path))
        return file
    }

    /** What libmpcdec decodes [file] to, as floats. */
    private fun referenceDecode(tools: Tools, file: File, case: Case): FloatArray {
        val out = File(workDirectory, "${case.stem}.f32")
        run(listOf(tools.oracle, file.path, out.path))
        val buffer = ByteBuffer.wrap(out.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS), "${command.first()} did not finish")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed:\n$output")
    }

    private companion object {
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-mpc-oracle").also { it.mkdirs() }

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

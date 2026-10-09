package io.github.yuroyami.audiokodec

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks the Speex decoder against libspeex 1.2.1, the code it is translated
 * from, on files speexenc writes.
 *
 * speexdec rounds to 16-bit samples, so the reference here is
 * `speex_float_oracle.c`, which drives libspeex's float API as speexdec
 * drives its integer one, compiled against the 1.2.1 sources under
 * `reference/speex` (see `reference/REFERENCES.md`) without SSE, and writes
 * every decoded frame untrimmed. The test trims it as speexdec would, from
 * the first audio page and the last granule position, which also checks the
 * decoder's trimming.
 *
 * The bound is zero: every sample must be the reference's float divided by
 * 32768, which is exact. The port keeps the float build's arithmetic,
 * including the places where a double constant makes C compute in double,
 * and the order of every sum.
 */
class SpeexOracleTest {

    @Test
    fun everyModeDecodesToExactlyTheReferenceFloats() {
        val tools = tools()
        val cases = mutableListOf<Case>()
        for (quality in 0..10) cases += Case("n", 8_000, 1, listOf("--quality", "$quality"))
        for (quality in listOf(0, 2, 4, 6, 8, 10)) cases += Case("w", 16_000, 1, listOf("--quality", "$quality"))
        for (quality in listOf(0, 4, 8, 10)) cases += Case("u", 32_000, 1, listOf("--quality", "$quality"))
        cases += Case("n", 8_000, 1, listOf("--vbr", "--quality", "6"))
        cases += Case("w", 16_000, 1, listOf("--vbr", "--quality", "4"))
        cases += Case("w", 16_000, 1, listOf("--abr", "20000"))
        cases += Case("n", 8_000, 1, listOf("--vad", "--dtx", "--quality", "4"))
        cases += Case("w", 16_000, 1, listOf("--vbr", "--vad", "--dtx"))
        cases += Case("n", 8_000, 1, listOf("--nframes", "3"))
        cases += Case("w", 16_000, 1, listOf("--nframes", "4", "--comp", "10"))
        cases += Case("n", 8_000, 2, listOf("--quality", "8"))
        cases += Case("w", 16_000, 2, listOf("--quality", "6"))
        cases += Case("u", 32_000, 2, listOf("--quality", "8"))
        cases += Case("n", 8_000, 1, listOf("--quality", "8"), silent = true)
        // Long enough to span several Ogg pages, where speexdec trims the start.
        cases += Case("n", 8_000, 1, listOf("--quality", "5"), seconds = 14.0)
        cases += Case("w", 16_000, 2, listOf("--quality", "7"), seconds = 9.0)
        cases += Case("u", 32_000, 1, listOf("--quality", "6"), seconds = 9.0)
        cases += Case("n", 8_000, 1, listOf("--nframes", "2", "--vbr"), seconds = 17.3)
        for (case in cases) {
            val file = encode(tools, case)
            val bytes = file.readBytes()
            val reference = referenceDecode(tools, file)
            val info = AudioKodec.probe(bytes)
            assertEquals(AudioCodec.SPEEX, info.codec, case.name)
            assertEquals(case.channels, info.format.channels, case.name)
            assertEquals(case.rate, info.format.sampleRate, case.name)
            // speexdec's own output gives the length, whatever its build's rounding.
            val speexdecFrames = speexdecFrames(file, case.channels)
            assertEquals(speexdecFrames, info.durationFrames, "${case.name}: speexdec's length")
            val decoded = AudioKodec.decode(bytes).samples
            assertEquals((speexdecFrames * case.channels).toInt(), decoded.size, "${case.name}: length")
            val trim = if (!reference.firstIsLast && reference.firstDeficit > 0) reference.firstDeficit + reference.lookahead else 0
            var worst = 0f
            var worstAt = -1
            for (i in decoded.indices) {
                val expected = reference.samples[(trim * case.channels).toInt() + i] / 32768f
                val diff = abs(expected - decoded[i])
                if (diff > worst) {
                    worst = diff
                    worstAt = i
                }
            }
            if (worst != 0f) throw AssertionError("${case.name}: sample $worstAt differs by $worst")
        }
    }

    @Test
    fun aSeekConvergesOnTheContinuousDecodeWithinItsRunUp() {
        val tools = tools()
        val case = Case("w", 16_000, 1, listOf("--quality", "8"), seconds = 6.0)
        val bytes = encode(tools, case).readBytes()
        val whole = AudioKodec.decode(bytes).samples
        for (target in listOf(0L, 1L, 20_000L, 47_999L, 80_000L)) {
            AudioKodec.open(bytes).use { decoder ->
                assertEquals(target, decoder.seek(target))
                val frame = decoder.nextFrame()!!
                var worst = 0f
                for (i in 0 until minOf(frame.frameCount, 300)) worst = maxOf(worst, abs(whole[target.toInt() + i] - frame.samples[i]))
                if (target == 0L) assertEquals(0f, worst, "a seek to the start is exact")
                assertTrue(worst < 1e-3f, "seek to $target: within $worst of the continuous decode")
            }
        }
    }

    // Helpers.

    private class Tools(val speexenc: String, val oracle: String)

    private class Case(
        val band: String,
        val rate: Int,
        val channels: Int,
        val args: List<String>,
        val silent: Boolean = false,
        val seconds: Double = 2.3,
    ) {
        val name = "-$band ${args.joinToString(" ")}, $channels ch${if (silent) ", silent" else ""}"
        val stem = name.replace(Regex("[^A-Za-z0-9]+"), "_") + "_$seconds"
    }

    private class Reference(val samples: FloatArray, val lookahead: Long, val firstDeficit: Long, val finalGranule: Long, val firstIsLast: Boolean)

    private fun tools(): Tools {
        assumeTrue("ffmpeg is required for the Speex oracle", findOnPath("ffmpeg") != null)
        val speexenc = findOnPath("speexenc")
        assumeTrue("speexenc is required for the Speex oracle", speexenc != null)
        val dir = File(System.getProperty("user.dir"))
        val sources = listOf(dir, dir.parentFile).map { File(it, "reference/speex/src") }.firstOrNull { File(it, "libspeex").isDirectory }
        assumeTrue("the libspeex 1.2.1 sources are required, under reference/speex", sources != null)
        val include = File(workDirectory, "include/speex").also { it.mkdirs() }
        File(include, "speex_config_types.h").writeText(
            "#ifndef __SPEEX_TYPES_H__\n#define __SPEEX_TYPES_H__\n#include <stdint.h>\n" +
                "typedef int16_t spx_int16_t;\ntypedef uint16_t spx_uint16_t;\ntypedef int32_t spx_int32_t;\ntypedef uint32_t spx_uint32_t;\n#endif\n",
        )
        val source = File(workDirectory, "speex_float_oracle.c")
        source.writeBytes(SpeexOracleTest::class.java.getResourceAsStream("/speex_float_oracle.c")!!.readBytes())
        val binary = File(workDirectory, "speex_float_oracle")
        val files = listOf(
            "bits", "cb_search", "exc_10_16_table", "exc_10_32_table", "exc_20_32_table", "exc_5_256_table", "exc_5_64_table",
            "exc_8_128_table", "filters", "gain_table", "gain_table_lbr", "hexc_10_32_table", "hexc_table", "high_lsp_tables",
            "lpc", "lsp", "lsp_tables_nb", "ltp", "modes", "modes_wb", "nb_celp", "quant_lsp", "sb_celp", "smallft", "speex",
            "speex_callbacks", "speex_header", "stereo", "vbr", "vq", "window",
        ).map { File(sources!!, "libspeex/$it.c").path }
        // libogg where the compiler does not look by itself, as under Homebrew on Apple silicon.
        val ogg = listOf("/opt/homebrew", "/usr/local").filter { File(it, "include/ogg/ogg.h").isFile }
            .flatMap { listOf("-I", "$it/include", "-L", "$it/lib") }
        // Generic C, as the float build is without --enable-sse, and the Ogg framing from libogg.
        run(
            listOf(
                "cc", "-O2", "-DFLOATING_POINT", "-DEXPORT=", "-DUSE_SMALLFT",
                "-I", include.path, "-I", include.parentFile.path, "-I", File(sources!!, "include").path, "-I", File(sources, "libspeex").path,
                "-o", binary.path, source.path,
            ) + ogg + files + listOf("-logg", "-lm"),
        )
        return Tools(speexenc!!, binary.path)
    }

    private fun encode(tools: Tools, case: Case): File {
        val wav = File(workDirectory, "${case.stem}.wav")
        val expressions = (0 until case.channels).map { c ->
            if (case.silent) "0" else
                "0.3*sin(2*PI*${180 + 60 * c}*t*(1+0.3*sin(2*PI*3*t)))*(0.6+0.4*sin(2*PI*0.7*t))+0.05*(random(${c + 1})-0.5)" +
                    "+0.2*lt(mod(t+${0.05 * c}\\,0.5)\\,0.1)*sin(2*PI*${900 + 200 * c}*t)"
        }
        run(
            listOf(
                findOnPath("ffmpeg")!!, "-y", "-v", "error", "-f", "lavfi",
                "-i", "aevalsrc=${expressions.joinToString("|")}:s=${case.rate}:d=${case.seconds}",
                "-c:a", "pcm_s16le", "-map_metadata", "-1", "-fflags", "+bitexact", wav.path,
            ),
        )
        val file = File(workDirectory, "${case.stem}.spx")
        file.delete()
        run(listOf(tools.speexenc, "--quiet", "-${case.band}") + case.args + listOf(wav.path, file.path))
        return file
    }

    private fun referenceDecode(tools: Tools, file: File): Reference {
        val out = File(workDirectory, file.nameWithoutExtension + ".f32")
        val process = ProcessBuilder(tools.oracle, file.path, out.path).redirectErrorStream(true).oracleStart()
        val line = process.inputStream.bufferedReader().readText().trim()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0, "the Speex oracle failed: $line")
        val fields = line.split(" ").map { it.toLong() }
        val buffer = ByteBuffer.wrap(out.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return Reference(FloatArray(buffer.remaining()).also { buffer.get(it) }, fields[3], fields[4], fields[5], fields[6] == 1L)
    }

    /** The frames speexdec writes for [file]. */
    private fun speexdecFrames(file: File, channels: Int): Long {
        val wav = File(workDirectory, file.nameWithoutExtension + ".speexdec.wav")
        run(listOf(findOnPath("speexdec")!!, file.path, wav.path))
        val bytes = wav.readBytes()
        var at = 12
        while (String(bytes, at, 4) != "data") at += 8 + ByteBuffer.wrap(bytes, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return (ByteBuffer.wrap(bytes, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int / 2 / channels).toLong()
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS), "${command.first()} did not finish")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed:\n$output")
    }

    private companion object {
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-speex-oracle").also { it.mkdirs() }

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

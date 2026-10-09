package io.github.yuroyami.audiokodec

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks the AMR and AMR-WB decoders against opencore-amr 0.1.6, the code
 * they are translated from.
 *
 * `amr_oracle.c` is a few lines over opencore-amr's public interfaces,
 * compiled against the pinned sources under `reference/amr` (see
 * `reference/REFERENCES.md`): it encodes AMR files with opencore's encoder
 * and decodes AMR and AMR-WB files with its decoders. `amrwb_encoder.c` does
 * the same for AMR-WB with vo-amrwbenc, from the same project, since
 * opencore-amr has no wideband encoder. Both encoders are only run.
 *
 * The bound is zero: both sides are fixed point, so every sample must be the
 * reference's 16-bit integer exactly.
 */
class AmrOracleTest {

    @Test
    fun everyNarrowbandModeDecodesToExactlyTheReferenceSamples() {
        val tools = tools()
        for (mode in 0..7) {
            for (dtx in listOf(false, true)) {
                check(tools, encodeNarrow(tools, mode, dtx, Signal.SPEECH, 3.1), "AMR mode $mode${if (dtx) ", DTX" else ""}")
            }
        }
        check(tools, encodeNarrow(tools, 7, true, Signal.SILENCE, 2.0), "AMR, silence with DTX")
        check(tools, encodeNarrow(tools, 0, true, Signal.BURSTS, 12.0), "AMR mode 0, bursts with DTX")
        check(tools, encodeNarrow(tools, 7, false, Signal.LOUD, 2.0), "AMR mode 7, clipping")
        check(tools, encodeNarrow(tools, 4, true, Signal.SPEECH, 0.013), "AMR, one frame")
    }

    @Test
    fun everyWidebandModeDecodesToExactlyTheReferenceSamples() {
        val tools = tools()
        for (mode in 0..8) {
            for (dtx in listOf(false, true)) {
                check(tools, encodeWide(tools, mode, dtx, Signal.SPEECH, 3.1), "AMR-WB mode $mode${if (dtx) ", DTX" else ""}")
            }
        }
        check(tools, encodeWide(tools, 8, true, Signal.SILENCE, 2.0), "AMR-WB, silence with DTX")
        check(tools, encodeWide(tools, 2, true, Signal.BURSTS, 12.0), "AMR-WB mode 2, bursts with DTX")
        check(tools, encodeWide(tools, 8, false, Signal.LOUD, 2.0), "AMR-WB mode 8, clipping")
        check(tools, encodeWide(tools, 0, false, Signal.HOMING, 1.0), "AMR-WB, homing frames")
    }

    @Test
    fun framesWithNoDataAreConcealedAsTheReferenceConcealsThem() {
        val tools = tools()
        for (mode in listOf(0, 4, 7)) {
            val file = encodeNarrow(tools, mode, false, Signal.SPEECH, 3.1)
            check(tools, replaceFrames(file, wide = false, every = 7, header = 0x7C), "AMR mode $mode with frames missing")
        }
        for (mode in listOf(0, 2, 8)) {
            val file = encodeWide(tools, mode, false, Signal.SPEECH, 3.1)
            check(tools, replaceFrames(file, wide = true, every = 7, header = 0x7C), "AMR-WB mode $mode with frames missing")
            check(tools, replaceFrames(file, wide = true, every = 5, header = 0x74), "AMR-WB mode $mode with frames lost")
        }
    }

    @Test
    fun multichannelFilesDecodeEachChannelInAudioKodecsOrder() {
        val tools = tools()
        for (wide in listOf(false, true)) {
            val sources = (0 until 6).map { c ->
                val file = if (wide) encodeWide(tools, 2 + c % 3, c % 2 == 1, Signal.SPEECH, 1.5, c) else encodeNarrow(tools, 2 + c % 5, c % 2 == 1, Signal.SPEECH, 1.5, c)
                frames(file.readBytes(), wide)
            }
            val references = (0 until 6).map { c ->
                val mono = File(workDirectory, "mc_${if (wide) "wb" else "nb"}_$c.${if (wide) "awb" else "amr"}")
                mono.writeBytes(magic(wide, false) + sources[c].reduce(ByteArray::plus))
                referenceDecode(tools, mono)
            }
            // RFC 3551 section 4.1 orders each count's speakers; AudioKodec's order follows WAVE.
            val orders = mapOf(
                2 to listOf(0, 1),
                3 to listOf(0, 1, 2),
                4 to listOf(0, 2, 1, 3),
                5 to listOf(0, 1, 2, 3, 4),
                6 to listOf(0, 3, 2, 1, 4, 5),
            )
            for ((channels, order) in orders) {
                val bytes = ArrayList<ByteArray>()
                bytes += magic(wide, true)
                bytes += byteArrayOf(0, 0, 0, channels.toByte())
                for (f in sources[0].indices) for (c in 0 until channels) bytes += sources[c][f]
                val file = bytes.reduce(ByteArray::plus)
                val what = "${if (wide) "AMR-WB" else "AMR"}, $channels channels"
                val info = AudioKodec.probe(file)
                assertEquals(channels, info.format.channels, what)
                val decoded = AudioKodec.decode(file).samples
                val perChannel = references[0].size
                assertEquals(perChannel * channels, decoded.size, "$what: length")
                for (i in 0 until perChannel) {
                    for (c in 0 until channels) {
                        val expected = references[c][i]
                        val actual = decoded[i * channels + order[c]] * 32768f
                        if (expected.toFloat() != actual) throw AssertionError("$what: channel $c, sample $i is $actual, not $expected")
                    }
                }
            }
        }
    }

    @Test
    fun amrInThreeGppFilesDecodesAsItsStorageFormat() {
        val tools = tools()
        assumeTrue("ffmpeg is required to write 3GP files", findOnPath("ffmpeg") != null)
        for ((wide, file) in listOf(false to encodeNarrow(tools, 5, true, Signal.BURSTS, 6.0), true to encodeWide(tools, 4, true, Signal.BURSTS, 6.0))) {
            val threeGp = File(workDirectory, file.nameWithoutExtension + ".3gp")
            run(listOf(findOnPath("ffmpeg")!!, "-y", "-v", "error", "-i", file.path, "-c", "copy", threeGp.path))
            val bytes = threeGp.readBytes()
            val info = AudioKodec.probe(bytes)
            assertEquals(AudioContainer.MP4, info.container)
            assertEquals(if (wide) AudioCodec.AMR_WB else AudioCodec.AMR_NB, info.codec)
            val reference = referenceDecode(tools, file)
            val decoded = AudioKodec.decode(bytes).samples
            assertEquals(reference.size, decoded.size, "3GP ${info.codec}: length")
            for (i in reference.indices) {
                if (reference[i].toFloat() != decoded[i] * 32768f) throw AssertionError("3GP ${info.codec}: sample $i")
            }
        }
    }

    @Test
    fun seeksConvergeOnTheContinuousDecodeWithinTheirRunUp() {
        val tools = tools()
        for (wide in listOf(false, true)) {
            val bytes = (if (wide) encodeWide(tools, 2, false, Signal.SPEECH, 6.0) else encodeNarrow(tools, 7, false, Signal.SPEECH, 6.0)).readBytes()
            val whole = AudioKodec.decode(bytes).samples
            for (target in listOf(0L, 1L, 5_000L, 23_456L, 40_000L)) {
                AudioKodec.open(bytes).use { decoder ->
                    assertEquals(target, decoder.seek(target))
                    val frame = decoder.nextFrame()!!
                    for (i in 0 until frame.frameCount) {
                        val diff = kotlin.math.abs(whole[target.toInt() + i] - frame.samples[i])
                        if (target == 0L) assertEquals(0f, diff, "a seek to the start is exact")
                        assertTrue(diff < 0.02f, "seek to $target, sample $i: $diff")
                    }
                }
            }
        }
    }

    // Helpers.

    private class Tools(val oracle: String, val wideEncoder: String)

    private enum class Signal { SPEECH, SILENCE, BURSTS, LOUD, HOMING }

    private fun check(tools: Tools, file: File, what: String) {
        val bytes = file.readBytes()
        val wide = bytes.decodeToString(0, 8) == "#!AMR-WB"
        val info = AudioKodec.probe(bytes)
        assertEquals(if (wide) AudioContainer.AMR_WB else AudioContainer.AMR_NB, info.container, what)
        assertEquals(if (wide) 16_000 else 8_000, info.format.sampleRate, what)
        val reference = referenceDecode(tools, file)
        assertEquals(reference.size.toLong(), info.durationFrames, "$what: length")
        val decoded = AudioKodec.decode(bytes).samples
        assertEquals(reference.size, decoded.size, "$what: decoded length")
        for (i in reference.indices) {
            val actual = decoded[i] * 32768f
            if (reference[i].toFloat() != actual) throw AssertionError("$what: sample $i is $actual, not ${reference[i]}")
        }
    }

    private fun tools(): Tools {
        assumeTrue("ffmpeg is required for the AMR oracle", findOnPath("ffmpeg") != null)
        val dir = File(System.getProperty("user.dir"))
        val root = listOf(dir, dir.parentFile).map { File(it, "reference/amr") }.firstOrNull { File(it, "src/amrnb/wrapper.cpp").isFile }
        assumeTrue("the opencore-amr 0.1.6 sources are required, under reference/amr", root != null)
        assumeTrue("the vo-amrwbenc 0.1.3 sources are required, under reference/amr", File(root, "vo-amrwbenc/wrapper.c").isFile)
        val oracle = File(workDirectory, "amr_oracle")
        val wideEncoder = File(workDirectory, "amrwb_encoder")
        if (!oracle.canExecute()) buildOracle(root!!, oracle)
        if (!wideEncoder.canExecute()) buildWideEncoder(root!!, wideEncoder)
        return Tools(oracle.path, wideEncoder.path)
    }

    /** opencore-amr, built from the files its own makefiles list. */
    private fun buildOracle(root: File, binary: File) {
        val src = File(root, "src")
        val base = "opencore/codecs_v2/audio/gsm_amr"
        val sources = mutableListOf<String>()
        for ((library, dirs) in listOf(
            "amrnb" to mapOf("DEC_SRC_DIR" to "$base/amr_nb/dec/src", "ENC_SRC_DIR" to "$base/amr_nb/enc/src", "COMMON_SRC_DIR" to "$base/amr_nb/common/src"),
            "amrwb" to mapOf("DEC_SRC_DIR" to "$base/amr_wb/dec/src"),
        )) {
            val makefile = File(src, "$library/Makefile.am").readText()
            val list = makefile.substring(makefile.indexOf("libopencore_${library}_la_SOURCES ="))
            for (line in list.lines().filter { !it.trimStart().startsWith("#") }) {
                for (m in Regex("""\$\((\w+)\)/(\w+)\.cpp""").findAll(line)) sources += File(src, "${dirs.getValue(m.groupValues[1])}/${m.groupValues[2]}.cpp").path
            }
            sources += File(src, "$library/wrapper.cpp").path
        }
        val objects = File(workDirectory, "objects").also { it.mkdirs() }
        val compiled = sources.distinct().map { source ->
            val wide = source.contains("amr_wb") || source.endsWith("amrwb/wrapper.cpp")
            val includes = if (wide) {
                listOf("oscl", "$base/amr_wb/dec/src", "$base/amr_wb/dec/include", "$base/common/dec/include")
            } else {
                listOf("oscl", "$base/amr_nb/dec/src", "$base/amr_nb/common/include", "$base/amr_nb/dec/include", "$base/common/dec/include", "$base/amr_nb/enc/src")
            }
            val output = File(objects, source.removePrefix(src.path).replace(Regex("[^A-Za-z0-9]+"), "_") + ".o")
            run(listOf("c++", "-O2", "-c") + includes.flatMap { listOf("-I", File(src, it).path) } + listOf("-o", output.path, source))
            output.path
        }
        val harness = File(workDirectory, "amr_oracle.c")
        harness.writeBytes(AmrOracleTest::class.java.getResourceAsStream("/amr_oracle.c")!!.readBytes())
        run(
            listOf("c++", "-O2", "-x", "c++", "-I", File(src, "amrnb").path, "-I", File(src, "amrwb").path, "-o", binary.path, harness.path, "-x", "none") + compiled,
        )
    }

    /** vo-amrwbenc, built from the files its makefile lists. */
    private fun buildWideEncoder(root: File, binary: File) {
        val src = File(root, "vo-amrwbenc")
        val makefile = File(src, "Makefile.am").readText()
        val list = makefile.substring(makefile.indexOf("libvo_amrwbenc_la_SOURCES ="))
        val sources = Regex("""\$\((\w+)\)/(\w+)\.c""").findAll(list).map { m ->
            File(src, (if (m.groupValues[1] == "VO_COMMON") "common/" else "amrwbenc/src/") + m.groupValues[2] + ".c").path
        }.toList()
        val harness = File(workDirectory, "amrwb_encoder.c")
        harness.writeBytes(AmrOracleTest::class.java.getResourceAsStream("/amrwb_encoder.c")!!.readBytes())
        run(
            listOf(
                "cc", "-O2", "-I", File(src, "amrwbenc/inc").path, "-I", File(src, "common/include").path, "-I", src.path,
                "-o", binary.path, harness.path, File(src, "wrapper.c").path,
            ) + sources + "-lm",
        )
    }

    private fun pcm(signal: Signal, rate: Int, seconds: Double, name: String, variant: Int = 0): File {
        val raw = File(workDirectory, "$name.raw")
        if (signal == Signal.HOMING) {
            // The encoder homing frame is every sample 0x0008; between speech, it resets the encoder.
            val speech = pcm(Signal.SPEECH, rate, seconds, "${name}_speech").readBytes()
            val frame = rate / 50 * 2
            val homing = ByteArray(frame * 3) { if (it % 2 == 0) 8 else 0 }
            raw.writeBytes(speech.copyOf(frame * 10) + homing + speech.copyOfRange(frame * 10, speech.size) + homing)
            return raw
        }
        val expression = when (signal) {
            Signal.SILENCE -> "0"
            Signal.LOUD -> "1.2*sin(2*PI*300*t)+0.6*sin(2*PI*1750*t)"
            Signal.BURSTS -> "lt(mod(t\\,3)\\,1.2)*(0.3*sin(2*PI*(160+${variant * 40})*t*(1+0.2*sin(2*PI*4*t)))+0.05*(random(1)-0.5))+0.001*(random(2)-0.5)"
            else -> "0.3*sin(2*PI*(160+${variant * 40})*t*(1+0.3*sin(2*PI*3*t)))*(0.6+0.4*sin(2*PI*0.7*t))+0.05*(random(1)-0.5)" +
                "+0.2*lt(mod(t\\,0.5)\\,0.1)*sin(2*PI*(900+${variant * 150})*t)"
        }
        run(
            listOf(
                findOnPath("ffmpeg")!!, "-y", "-v", "error", "-f", "lavfi", "-i", "aevalsrc=$expression:s=$rate:d=$seconds",
                "-f", "s16le", "-acodec", "pcm_s16le", raw.path,
            ),
        )
        return raw
    }

    private fun encodeNarrow(tools: Tools, mode: Int, dtx: Boolean, signal: Signal, seconds: Double, variant: Int = 0): File {
        val name = "nb_${mode}_${dtx}_${signal}_${variant}_$seconds"
        val file = File(workDirectory, "$name.amr")
        run(listOf(tools.oracle, "encode", "$mode", if (dtx) "1" else "0", pcm(signal, 8_000, seconds, name, variant).path, file.path))
        return file
    }

    private fun encodeWide(tools: Tools, mode: Int, dtx: Boolean, signal: Signal, seconds: Double, variant: Int = 0): File {
        val name = "wb_${mode}_${dtx}_${signal}_${variant}_$seconds"
        val file = File(workDirectory, "$name.awb")
        run(listOf(tools.wideEncoder, "$mode", if (dtx) "1" else "0", pcm(signal, 16_000, seconds, name, variant).path, file.path))
        return file
    }

    private fun magic(wide: Boolean, multichannel: Boolean): ByteArray =
        (if (wide) (if (multichannel) "#!AMR-WB_MC1.0\n" else "#!AMR-WB\n") else (if (multichannel) "#!AMR_MC1.0\n" else "#!AMR\n")).encodeToByteArray()

    /** The frames of a single-channel file, each with its header byte. */
    private fun frames(bytes: ByteArray, wide: Boolean): List<ByteArray> {
        val sizes = if (wide) intArrayOf(17, 23, 32, 36, 40, 46, 50, 58, 60, 5, 0, 0, 0, 0, 0, 0) else intArrayOf(12, 13, 15, 17, 19, 20, 26, 31, 5, 6, 5, 5, 0, 0, 0, 0)
        var at = magic(wide, false).size
        val out = ArrayList<ByteArray>()
        while (at < bytes.size) {
            val size = 1 + sizes[(bytes[at].toInt() shr 3) and 0x0f]
            out += bytes.copyOfRange(at, at + size)
            at += size
        }
        return out
    }

    /** A copy of [file] with every [every]th frame replaced by a bare header. */
    private fun replaceFrames(file: File, wide: Boolean, every: Int, header: Int): File {
        val frames = frames(file.readBytes(), wide).mapIndexed { i, f -> if (i % every == every - 1) byteArrayOf(header.toByte()) else f }
        val out = File(workDirectory, "${file.nameWithoutExtension}_${every}_$header.${file.extension}")
        out.writeBytes((listOf(magic(wide, false)) + frames).reduce(ByteArray::plus))
        return out
    }

    private fun referenceDecode(tools: Tools, file: File): ShortArray {
        val out = File(workDirectory, file.name + ".pcm")
        run(listOf(tools.oracle, "decode", file.path, out.path))
        val buffer = ByteBuffer.wrap(out.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(600, TimeUnit.SECONDS), "${command.first()} did not finish")
        if (process.exitValue() != 0) {
            // A compiler's output can quote the sources it reads, so only the lines it reports as errors are shown.
            val errors = output.lines().filter { it.contains("error", ignoreCase = true) }.take(20).joinToString("\n")
            throw AssertionError("${File(command.first()).name} failed:\n$errors")
        }
    }

    private companion object {
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-amr-oracle").also { it.mkdirs() }

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

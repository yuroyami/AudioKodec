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
 * Checks the GSM 06.10 decoder against libgsm 1.0.22, the code it is
 * translated from.
 *
 * `gsm_oracle.c`, a few lines over libgsm's public interface compiled
 * against the pinned sources under `reference/gsm` (see
 * `reference/REFERENCES.md`), encodes the frames, in Microsoft's layout for
 * the WAV files this test writes around them and in the plain layout for the
 * QuickTime files ffmpeg wraps them in, and decodes the same frames.
 *
 * The bound is zero: both sides are fixed point, so every sample must be the
 * reference's 16-bit integer exactly.
 */
class GsmOracleTest {

    @Test
    fun wavFilesDecodeToExactlyTheReferenceSamples() {
        val tools = tools()
        for ((name, expression) in signals) {
            for (seconds in listOf(0.04, 0.05, 3.3)) {
                val frames = (seconds * 8000).toLong()
                val payload = encode(tools, "encode-wav49", expression, "${name}_$seconds", seconds)
                val bytes = wav(payload.readBytes(), frames)
                val info = AudioKodec.probe(bytes)
                assertEquals(AudioContainer.WAV, info.container, name)
                assertEquals(AudioCodec.GSM, info.codec, name)
                assertEquals(8_000, info.format.sampleRate, name)
                // The fact chunk says how many samples the last block's padding leaves.
                assertEquals(frames, info.durationFrames, "$name, $seconds s: length")
                compare(referenceDecode(tools, "wav49", payload), AudioKodec.decode(bytes).samples, frames.toInt(), "$name WAV, $seconds s")
            }
        }
    }

    @Test
    fun wavFilesFfmpegWritesDecodeToExactlyTheReferenceSamples() {
        val tools = tools()
        val ffmpeg = findOnPath("ffmpeg")!!
        val encoders = ProcessBuilder(ffmpeg, "-hide_banner", "-encoders").redirectErrorStream(true).oracleStart()
            .inputStream.bufferedReader().readText()
        assumeTrue("ffmpeg's libgsm_ms encoder is required for ffmpeg's own WAV files", encoders.contains("libgsm_ms"))
        for ((name, expression) in signals) {
            val wav = File(workDirectory, "${name}_ffmpeg.wav")
            run(
                listOf(
                    ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "aevalsrc=$expression:s=8000:d=1.37",
                    "-c:a", "libgsm_ms", "-map_metadata", "-1", "-fflags", "+bitexact", wav.path,
                ),
            )
            val bytes = wav.readBytes()
            var at = 12
            while (String(bytes, at, 4) != "data") at += 8 + ByteBuffer.wrap(bytes, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val size = ByteBuffer.wrap(bytes, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val payload = File(workDirectory, "${name}_ffmpeg.wav49").also { it.writeBytes(bytes.copyOfRange(at + 8, at + 8 + size)) }
            assertEquals(10_960L, AudioKodec.probe(bytes).durationFrames, "$name: ffmpeg's fact chunk")
            compare(referenceDecode(tools, "wav49", payload), AudioKodec.decode(bytes).samples, 10_960, "$name, ffmpeg's WAV")
        }
    }

    @Test
    fun quickTimeFilesDecodeToExactlyTheReferenceSamples() {
        val tools = tools()
        for ((name, expression) in signals) {
            val raw = encode(tools, "encode-plain", expression, name, 2.1)
            val mov = File(workDirectory, "$name.mov")
            run(listOf(findOnPath("ffmpeg")!!, "-y", "-v", "error", "-f", "gsm", "-i", raw.path, "-c", "copy", mov.path))
            val reference = referenceDecode(tools, "plain", raw)
            val bytes = mov.readBytes()
            val info = AudioKodec.probe(bytes)
            assertEquals(AudioCodec.GSM, info.codec, name)
            compare(reference, AudioKodec.decode(bytes).samples, reference.size, "$name QuickTime")
        }
    }

    @Test
    fun aSeekConvergesOnTheContinuousDecodeWithinItsRunUp() {
        val bytes = wav(encode(tools(), "encode-wav49", signals.first().second, "seek", 6.0).readBytes(), 48_000)
        val whole = AudioKodec.decode(bytes).samples
        for (target in listOf(0L, 1L, 319L, 320L, 9_000L, 30_001L, 47_000L)) {
            AudioKodec.open(bytes).use { decoder ->
                assertEquals(target, decoder.seek(target))
                val frame = decoder.nextFrame()!!
                for (i in 0 until minOf(frame.frameCount, 1000)) {
                    val diff = abs(whole[target.toInt() + i] - frame.samples[i])
                    if (target == 0L) assertEquals(0f, diff, "a seek to the start is exact")
                    assertTrue(diff < 0.01f, "seek to $target, sample $i: $diff")
                }
            }
        }
    }

    // Helpers.

    private val signals = listOf(
        "speech" to "0.3*sin(2*PI*180*t*(1+0.3*sin(2*PI*3*t)))*(0.6+0.4*sin(2*PI*0.7*t))+0.05*(random(1)-0.5)+0.2*lt(mod(t\\,0.5)\\,0.1)*sin(2*PI*900*t)",
        "silence" to "0",
        "loud" to "1.3*sin(2*PI*300*t)+0.7*sin(2*PI*1700*t)",
        "noise" to "0.9*(random(3)-0.5)",
    )

    private fun compare(reference: ShortArray, decoded: FloatArray, length: Int, what: String) {
        assertEquals(length, decoded.size, "$what: length")
        for (i in 0 until length) {
            val actual = decoded[i] * 32768f
            if (reference[i].toFloat() != actual) throw AssertionError("$what: sample $i is $actual, not ${reference[i]}")
        }
    }

    private fun tools(): String {
        assumeTrue("ffmpeg is required for the GSM oracle", findOnPath("ffmpeg") != null)
        val dir = File(System.getProperty("user.dir"))
        val root = listOf(dir, dir.parentFile).map { File(it, "reference/gsm/src/gsm-1.0-pl22") }.firstOrNull { File(it, "src/decode.c").isFile }
        assumeTrue("the libgsm 1.0.22 sources are required, under reference/gsm", root != null)
        val binary = File(workDirectory, "gsm_oracle")
        if (!binary.canExecute()) {
            val harness = File(workDirectory, "gsm_oracle.c")
            harness.writeBytes(GsmOracleTest::class.java.getResourceAsStream("/gsm_oracle.c")!!.readBytes())
            val sources = listOf(
                "add", "decode", "long_term", "lpc", "preprocess", "rpe", "gsm_destroy", "gsm_decode", "gsm_encode", "code",
                "gsm_create", "gsm_option", "short_term", "table",
            ).map { File(root, "src/$it.c").path }
            // The flags of libgsm's own makefile: arithmetic shifts, and Microsoft's layout compiled in.
            run(listOf("cc", "-O2", "-DSASR", "-DWAV49", "-DNeedFunctionPrototypes=1", "-I", File(root, "inc").path, "-o", binary.path, harness.path) + sources)
        }
        return binary.path
    }

    /** [seconds] of [expression] at 8 kHz, encoded by libgsm in [mode]. */
    private fun encode(binary: String, mode: String, expression: String, name: String, seconds: Double): File {
        val pcm = File(workDirectory, "$name.raw")
        run(
            listOf(
                findOnPath("ffmpeg")!!, "-y", "-v", "error", "-f", "lavfi", "-i", "aevalsrc=$expression:s=8000:d=$seconds",
                "-f", "s16le", "-acodec", "pcm_s16le", pcm.path,
            ),
        )
        val out = File(workDirectory, "$name.$mode")
        run(listOf(binary, mode, pcm.path, out.path))
        return out
    }

    /** A WAV file of Microsoft's GSM 06.10 blocks, as Windows writes one: format tag 0x0031, 320 samples a block, and a fact chunk. */
    private fun wav(payload: ByteArray, frames: Long): ByteArray {
        // A chunk of odd length is followed by a pad byte, which the form's length counts.
        val pad = payload.size and 1
        val out = ByteBuffer.allocate(12 + 28 + 12 + 8 + payload.size + pad).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(4 + 28 + 12 + 8 + payload.size + pad).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(20).putShort(0x31).putShort(1).putInt(8000).putInt(1625).putShort(65).putShort(0)
        out.putShort(2).putShort(320)
        out.put("fact".toByteArray()).putInt(4).putInt(frames.toInt())
        out.put("data".toByteArray()).putInt(payload.size).put(payload)
        return out.array()
    }

    private fun referenceDecode(binary: String, layout: String, file: File): ShortArray {
        val out = File(workDirectory, file.name + ".pcm")
        run(listOf(binary, layout, file.path, out.path))
        val buffer = ByteBuffer.wrap(out.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buffer.remaining()).also { buffer.get(it) }
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
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-gsm-oracle").also { it.mkdirs() }

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

package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.ilbc.IlbcDecoder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks the iLBC decoder against libilbc 3.0.4, WebRTC's iLBC, the code it is
 * translated from.
 *
 * `ilbc_oracle.c`, a few lines over libilbc's public interface built from the
 * pinned sources under `reference/ilbc` (see `reference/REFERENCES.md`),
 * encodes the frames and decodes them. The test wraps them in the storage
 * format of RFC 3952, and ffmpeg copies them into CAF and QuickTime files.
 *
 * The bound is zero: both sides are fixed point, so every sample must be the
 * reference's 16-bit integer exactly.
 */
class IlbcOracleTest {

    @Test
    fun storageFilesDecodeToExactlyTheReferenceSamples() {
        val tool = tool()
        for ((name, expression) in signals) {
            for (mode in listOf(20, 30)) {
                val frames = encode(tool, mode, expression, "${name}_$mode", 3.07)
                val bytes = storage(mode, frames)
                val info = AudioKodec.probe(bytes)
                assertEquals(AudioContainer.ILBC, info.container)
                assertEquals(AudioCodec.ILBC, info.codec)
                assertEquals(8_000, info.format.sampleRate)
                assertEquals(1, info.format.channels)
                val reference = referenceDecode(tool, mode, frames, "${name}_$mode")
                assertEquals(reference.size.toLong(), info.durationFrames, "$name, $mode ms: length")
                compare(reference, AudioKodec.decode(bytes).samples, "$name, $mode ms")
            }
        }
    }

    @Test
    fun cafAndQuickTimeFilesFfmpegWritesDecodeToExactlyTheReferenceSamples() {
        val tool = tool()
        for ((name, expression) in signals.take(2)) {
            for (mode in listOf(20, 30)) {
                val frames = encode(tool, mode, expression, "${name}_${mode}_wrapped", 2.0)
                val reference = referenceDecode(tool, mode, frames, "${name}_${mode}_wrapped")
                val lbc = File(workDirectory, "${name}_$mode.lbc").also { it.writeBytes(storage(mode, frames)) }
                for (extension in listOf("caf", "mov")) {
                    val wrapped = File(workDirectory, "${name}_$mode.$extension")
                    run(listOf(ffmpeg(), "-y", "-v", "error", "-i", lbc.path, "-c", "copy", wrapped.path))
                    val bytes = wrapped.readBytes()
                    val info = AudioKodec.probe(bytes)
                    assertEquals(AudioCodec.ILBC, info.codec, "$name $extension")
                    compare(reference, AudioKodec.decode(bytes).samples, "$name, $mode ms, $extension")
                }
            }
        }
    }

    /**
     * Random frames: most have an impossible start state index or the empty
     * frame bit, which both decoders conceal from the frames before, and some
     * state a codebook index that both refuse.
     */
    @Test
    fun randomFramesDecodeAsTheReferenceDecodesThem() {
        val tool = tool()
        val random = Random(20)
        var concealed = 0
        var refused = 0
        for (mode in listOf(20, 30)) {
            val frameBytes = if (mode == 30) 50 else 38
            val good = encode(tool, mode, signals.first().second, "random_$mode", 4.0)
            repeat(150) { trial ->
                // Real frames with a run of damaged ones, so concealment follows real speech.
                val frames = good.copyOf()
                val count = frames.size / frameBytes
                val start = random.nextInt(count)
                for (n in start until minOf(count, start + random.nextInt(1, 8))) {
                    for (i in 0 until frameBytes) frames[n * frameBytes + i] = random.nextInt(256).toByte()
                    // Every other one marked empty, the way a sender marks a frame it lost.
                    if (n % 2 == 0) frames[n * frameBytes + frameBytes - 1] = (frames[n * frameBytes + frameBytes - 1].toInt() or 1).toByte()
                }
                val reference = referenceDecode(tool, mode, frames, "random_${mode}_$trial")
                val bytes = storage(mode, frames)
                if (reference.size == count * (mode * 8)) {
                    concealed++
                    compare(reference, AudioKodec.decode(bytes).samples, "$mode ms, trial $trial")
                } else {
                    // The reference refused a frame: strictly that is an error, tolerantly the frames before it.
                    refused++
                    assertFailsWith<CorruptStreamException> { AudioKodec.decode(bytes) }
                    if (reference.isEmpty()) {
                        assertFailsWith<CorruptStreamException> { AudioKodec.decode(bytes, DecodeOptions(tolerant = true)) }
                    } else {
                        compare(reference, AudioKodec.decode(bytes, DecodeOptions(tolerant = true)).samples, "$mode ms, trial $trial, tolerant")
                    }
                }
            }
        }
        assertTrue(concealed > 100 && refused > 0, "the trials should cover both outcomes: $concealed concealed, $refused refused")
    }

    /**
     * Frames altered until their codebook index for the vector next to the
     * start state lies past every codebook section, the one index both
     * decoders refuse, between frames of real speech. Only 20 ms frames can
     * state one: in the 30 ms mode those indices have seven bits and every
     * value lies inside a section.
     */
    @Test
    fun aRefusedFrameEndsTheStreamWhereTheReferenceStops() {
        val tool = tool()
        val random = Random(7)
        for (mode in listOf(20)) {
            val frameBytes = if (mode == 30) 50 else 38
            val good = encode(tool, mode, signals.first().second, "refuse_$mode", 2.0)
            val count = good.size / frameBytes
            val probe = IlbcDecoder(mode)
            repeat(6) { trial ->
                val frames = good.copyOf()
                val at = random.nextInt(1, count)
                val frame = frames.copyOfRange(at * frameBytes, (at + 1) * frameBytes)
                while (!probe.refuses(frame, 0)) frame[random.nextInt(frameBytes)] = (frame[random.nextInt(frameBytes)].toInt() xor (1 shl random.nextInt(8))).toByte()
                frame.copyInto(frames, at * frameBytes)
                val reference = referenceDecode(tool, mode, frames, "refuse_${mode}_$trial")
                assertEquals(at * mode * 8, reference.size, "$mode ms, trial $trial: the reference stops at frame $at")
                val bytes = storage(mode, frames)
                val failure = assertFailsWith<CorruptStreamException> { AudioKodec.decode(bytes) }
                assertEquals(9L + at * frameBytes, failure.offset, "$mode ms, trial $trial: the refused frame's offset")
                compare(reference, AudioKodec.decode(bytes, DecodeOptions(tolerant = true)).samples, "$mode ms, trial $trial, tolerant")
            }
        }
    }

    @Test
    fun aSeekLandsOnExactlyTheContinuousDecode() {
        val tool = tool()
        for (mode in listOf(20, 30)) {
            val frames = encode(tool, mode, signals.first().second, "seek_$mode", 6.0)
            val lbc = File(workDirectory, "seek_$mode.lbc").also { it.writeBytes(storage(mode, frames)) }
            val mov = File(workDirectory, "seek_$mode.mov")
            run(listOf(ffmpeg(), "-y", "-v", "error", "-i", lbc.path, "-c", "copy", mov.path))
            for (bytes in listOf(lbc.readBytes(), mov.readBytes())) {
                val whole = AudioKodec.decode(bytes).samples
                AudioKodec.open(bytes).use { decoder ->
                    for (target in listOf(30_001L, 7L, 0L, 12_345L, 12_400L, 47_000L, 239L)) {
                        assertEquals(target, decoder.seek(target))
                        val frame = decoder.nextFrame()!!
                        assertEquals(target, frame.positionFrames)
                        for (i in 0 until frame.frameCount) {
                            if (whole[target.toInt() + i] != frame.samples[i]) throw AssertionError("$mode ms, seek to $target: sample $i differs")
                        }
                    }
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
        "bursts" to "0.6*sin(2*PI*240*t)*lt(mod(t\\,0.37)\\,0.11)",
    )

    private fun storage(mode: Int, frames: ByteArray): ByteArray = "#!iLBC$mode\n".encodeToByteArray() + frames

    private fun compare(reference: ShortArray, decoded: FloatArray, what: String) {
        assertEquals(reference.size, decoded.size, "$what: length")
        for (i in reference.indices) {
            val actual = decoded[i] * 32768f
            if (reference[i].toFloat() != actual) throw AssertionError("$what: sample $i is $actual, not ${reference[i]}")
        }
    }

    private fun tool(): String {
        assumeTrue("ffmpeg is required for the iLBC oracle", findOnPath("ffmpeg") != null)
        val dir = File(System.getProperty("user.dir"))
        val root = listOf(dir, dir.parentFile).map { File(it, "reference/ilbc/src") }.firstOrNull { File(it, "modules/audio_coding/codecs/ilbc/decode.c").isFile }
        assumeTrue("the libilbc 3.0.4 sources are required, under reference/ilbc", root != null)
        val binary = File(workDirectory, "ilbc_oracle")
        if (!binary.canExecute()) {
            val build = File(workDirectory, "build").also { it.mkdirs() }
            run(listOf("cmake", "-S", root!!.path, "-B", build.path, "-DCMAKE_BUILD_TYPE=Release"))
            run(listOf("cmake", "--build", build.path, "--target", "ilbc", "-j", "4"))
            val harness = File(workDirectory, "ilbc_oracle.c")
            harness.writeBytes(IlbcOracleTest::class.java.getResourceAsStream("/ilbc_oracle.c")!!.readBytes())
            run(
                listOf(
                    "cc", "-O2", "-I", root.path, "-I", File(root, "modules/audio_coding/codecs/ilbc").path, "-I", build.path,
                    "-o", binary.path, harness.path, "-L", build.path, "-lilbc", "-Wl,-rpath," + build.path,
                ),
            )
        }
        return binary.path
    }

    /** [seconds] of [expression] at 8 kHz, encoded by libilbc into frames of [mode] ms. */
    private fun encode(tool: String, mode: Int, expression: String, name: String, seconds: Double): ByteArray {
        val pcm = File(workDirectory, "$name.raw")
        run(
            listOf(
                ffmpeg(), "-y", "-v", "error", "-f", "lavfi", "-i", "aevalsrc=$expression:s=8000:d=$seconds",
                "-f", "s16le", "-acodec", "pcm_s16le", pcm.path,
            ),
        )
        val out = File(workDirectory, "$name.frames")
        run(listOf(tool, "encode", "$mode", pcm.path, out.path))
        return out.readBytes()
    }

    private fun referenceDecode(tool: String, mode: Int, frames: ByteArray, name: String): ShortArray {
        val input = File(workDirectory, "$name.in").also { it.writeBytes(frames) }
        val out = File(workDirectory, "$name.pcm")
        run(listOf(tool, "decode", "$mode", input.path, out.path))
        val buffer = ByteBuffer.wrap(out.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun ffmpeg(): String = findOnPath("ffmpeg")!!

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
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-ilbc-oracle").also { it.mkdirs() }

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

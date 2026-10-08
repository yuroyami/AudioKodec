package io.github.yuroyami.audiokodec

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Checks AIFF-C sample interpretations against independent converters. */
class AiffCOracleTest {
    @Test
    fun reversedIntegerTypesAgreeWithApplesIntegerReader() {
        val afconvert = findOnPath("afconvert") ?: return skip("afconvert")
        for (depth in intArrayOf(24, 32)) {
            val values = if (depth == 32) {
                intArrayOf(0x01020304, -0x11223344, 0x7F000001, Int.MIN_VALUE, Int.MAX_VALUE, -1, 0)
            } else {
                intArrayOf(0x010203, -0x112233, 0x7F0001, -8_388_608, 8_388_607, -1, 0)
            }
            for (channels in 1..2) {
                val samples = IntArray(values.size * channels) { values[(it / channels + it % channels) % values.size] }
                val alias = if (depth == 32) "23ni" else "42ni"
                val bytes = integerFile(alias, depth, channels, samples, littleEndian = true)
                val input = File(workDirectory, "integer-$depth-$channels.aifc")
                // Apple reads 23ni directly. It does not recognise 42ni, so
                // its standard in24 interpretation checks the same 24-bit
                // audio without claiming tool support for the reversed alias.
                input.writeBytes(if (depth == 32) bytes else integerFile("in24", depth, channels, samples, littleEndian = false))
                val output = File(workDirectory, "integer-$depth-$channels.wav")
                output.delete()
                run(listOf(afconvert, "-f", "WAVE", "-d", "LEI32", input.path, output.path))
                val wave = output.readBytes()
                var at = 12
                while (String(wave, at, 4, Charsets.US_ASCII) != "data") {
                    val size = ByteBuffer.wrap(wave, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    at += 8 + size + (size and 1)
                    assertTrue(at + 8 <= wave.size, "Apple's WAVE file has no data chunk")
                }
                val size = ByteBuffer.wrap(wave, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val buffer = ByteBuffer.wrap(wave, at + 8, size).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                val reference = IntArray(buffer.remaining()) { buffer.get() shr (32 - depth) }
                assertContentEquals(samples, reference, "Apple's $depth-bit integers")
                AudioKodec.open(bytes).use { decoder ->
                    val integers = assertIs<IntPcmAccess>(decoder)
                    assertEquals(depth, integers.bitDepth)
                    val frame = assertNotNull(integers.nextIntFrame())
                    assertEquals(values.size, frame.frameCount)
                    assertContentEquals(reference, frame.samples.copyOf(reference.size), "$alias, $channels channels")
                }
            }
        }
    }

    private fun integerFile(compression: String, depth: Int, channels: Int, samples: IntArray, littleEndian: Boolean): ByteArray {
        val chunks = ByteArrayOutputStream()
        DataOutputStream(chunks).use { out ->
            out.writeBytes("FVER")
            out.writeInt(4)
            out.writeInt(0xA2805140.toInt())
            out.writeBytes("COMM")
            out.writeInt(24)
            out.writeShort(channels)
            out.writeInt(samples.size / channels)
            out.writeShort(depth)
            // 8000 as a normalised 80-bit extended float.
            out.writeShort(16_383 + 12)
            out.writeLong(8_000L shl 51)
            out.writeBytes(compression)
            out.writeShort(0)
            out.writeBytes("SSND")
            val dataSize = samples.size * (depth / 8)
            out.writeInt(8 + dataSize)
            out.writeLong(0)
            for (sample in samples) {
                for (byte in 0 until depth / 8) {
                    val shift = if (littleEndian) byte * 8 else depth - 8 - byte * 8
                    out.writeByte(sample ushr shift)
                }
            }
            if (dataSize and 1 != 0) out.writeByte(0)
        }
        val result = ByteArrayOutputStream()
        DataOutputStream(result).use { out ->
            out.writeBytes("FORM")
            out.writeInt(4 + chunks.size())
            out.writeBytes("AIFC")
            out.write(chunks.toByteArray())
        }
        return result.toByteArray()
    }

    @Test
    fun ffmpegFilesPreserveLengthSamplesAndSeeking() {
        val ffmpeg = findOnPath("ffmpeg") ?: return skip("ffmpeg")
        compareWriter(ffmpeg, ffmpeg, apple = false)
    }

    @Test
    fun appleFilesPreserveLengthSamplesAndSeeking() {
        val ffmpeg = findOnPath("ffmpeg") ?: return skip("ffmpeg")
        val afconvert = findOnPath("afconvert") ?: return skip("afconvert")
        compareWriter(ffmpeg, afconvert, apple = true)
    }

    private fun compareWriter(ffmpeg: String, writer: String, apple: Boolean) {
        for ((coding, encoder) in listOf("ulaw" to "pcm_mulaw", "alaw" to "pcm_alaw", "ima4" to "adpcm_ima_qt")) {
            for (channels in 1..2) {
                val name = "${if (apple) "apple" else "ffmpeg"}-$coding-$channels"
                val input = File(workDirectory, "input-$channels.wav")
                val samples = FloatArray(22_050 * channels) { i ->
                    (0.5 * sin(2 * Math.PI * (440 + (i % channels) * 110) * (i / channels) / 44_100)).toFloat()
                }
                input.writeBytes(AudioKodec.encode(samples, AudioFormat(44_100, channels), EncodeTarget.wav(16)))
                val file = File(workDirectory, "$name.aifc")
                file.delete()
                if (apple) {
                    run(listOf(writer, "-f", "AIFC", "-d", coding, input.path, file.path))
                } else {
                    run(listOf(writer, "-y", "-v", "error", "-i", input.path, "-c:a", encoder, "-f", "aiff", file.path))
                }
                val raw = File(workDirectory, "$name.f32")
                run(listOf(ffmpeg, "-y", "-v", "error", "-i", file.path, "-f", "f32le", raw.path))
                val floats = ByteBuffer.wrap(raw.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val reference = FloatArray(floats.remaining()).also { floats.get(it) }
                val bytes = file.readBytes()
                val comm = commonChunk(bytes)
                val storedCount = ByteBuffer.wrap(bytes, comm + 10, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFF_FFFFL
                val storedWidth = ByteBuffer.wrap(bytes, comm + 14, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
                assertEquals(if (coding == "ima4") 345L else 22_050L, storedCount, "$name: COMM count")
                assertEquals(if (coding == "ima4") if (apple) 0 else 4 else 8, storedWidth, "$name: writer width")
                val expectedFrames = reference.size / channels
                assertEquals(if (coding == "ima4") 22_080 else 22_050, expectedFrames)
                val info = AudioKodec.probe(bytes)
                assertEquals(expectedFrames.toLong(), info.durationFrames, "$name: duration")
                val decoded = AudioKodec.decode(bytes)
                assertEquals(expectedFrames, decoded.frames, "$name: decoded length")
                var worst = 0f
                for (i in reference.indices) worst = maxOf(worst, abs(reference[i] - decoded.samples[i]))
                // IMA retains the reference's separate integer shifts. The
                // existing oracle bound allows 128 steps for ffmpeg's rounding
                // difference; G.711 has the same expansion table on both sides.
                val tolerance = if (coding == "ima4") 128f / 32_768 else 0f
                assertTrue(worst <= tolerance, "$name: worst sample difference $worst exceeds $tolerance")

                for (width in if (coding == "ima4") intArrayOf(0, 4, 16) else intArrayOf(8, 16)) {
                    val variant = bytes.copyOf().also { it[comm + 14] = 0; it[comm + 15] = width.toByte() }
                    assertContentEquals(decoded.samples, AudioKodec.decode(variant).samples, "$name: width $width changed the samples")
                }
                AudioKodec.open(bytes).use { decoder ->
                    assertEquals(expectedFrames.toLong(), decoder.durationFrames)
                    for (target in longArrayOf(0, 1, 63, 64, 127, 128, 8_191, 8_192, expectedFrames - 1L, expectedFrames.toLong())) {
                        assertEquals(target, decoder.seek(target), "$name: seek")
                        val frame = decoder.nextFrame()
                        if (target == expectedFrames.toLong()) {
                            assertEquals(null, frame)
                        } else {
                            assertNotNull(frame)
                            assertEquals(target, frame.positionFrames)
                            for (c in 0 until channels) assertEquals(decoded.samples[target.toInt() * channels + c], frame.samples[c], "$name: sought channel $c")
                        }
                    }
                }
            }
        }
    }

    private fun commonChunk(bytes: ByteArray): Int {
        var at = 12
        while (at + 8 <= bytes.size) {
            val size = ByteBuffer.wrap(bytes, at + 4, 4).order(ByteOrder.BIG_ENDIAN).int
            if (String(bytes, at, 4, Charsets.US_ASCII) == "COMM") return at
            at += 8 + size + (size and 1)
        }
        error("the converter wrote no COMM chunk")
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "${command.first()} did not finish")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed:\n$output")
    }

    private fun skip(tool: String) {
        org.junit.Assume.assumeTrue("$tool is not on the path, so its AIFF-C comparison did not run", false)
    }

    private companion object {
        val workDirectory = File(System.getProperty("java.io.tmpdir"), "audiokodec-aifc-oracle").also { it.mkdirs() }

        fun findOnPath(tool: String): String? = System.getenv("PATH")?.split(File.pathSeparator)
            ?.map { File(it, tool) }?.firstOrNull { it.canExecute() }?.absolutePath
    }
}

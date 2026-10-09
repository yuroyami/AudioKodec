package io.github.yuroyami.audiokodec

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** Independent executable decoders, with no implementation source used. */
class VorbisFloor0OracleTest {
    @Test
    fun oddEvenAndMaximumOrdersMatchTheIndependentFloatDecoder() = inDirectory { directory ->
        for (order in listOf(1, 2, 3, 4, 10, 255)) for (channels in 1..2) for (block in listOf(64, 128, 256)) {
            val bytes = VorbisFloor0Fixtures.file(order = order, channels = channels, block = block,
                floorRate = if (block == 128) 48000 else 8000, barkSize = if (block == 256) 63 else 32)
            val reference = reference(directory, bytes, "vorbis", sixteenBit = false)
            val actual = AudioKodec.decode(bytes)
            assertEquals(5 * block / 2, actual.frames)
            assertEquals(actual.frames.toLong(), actual.info.durationFrames)
            assertEquals(reference.size, actual.samples.size)
            TestSignals.assertClose(reference, actual.samples, 1e-4f, "floor 0 $order/$channels/$block")
            val rms = sqrt(reference.indices.sumOf {
                val difference = actual.samples[it].toDouble() - reference[it]
                difference * difference
            } / reference.size)
            // The maximum order multiplies 255 cosine factors. A reference
            // accumulates more numerical differences than the shorter curves.
            // The common test independently checks the double specification products.
            // Keep the existing Vorbis peak bound and permit 2e-5 RMS there.
            val rmsBound = if (order == 255) 2e-5 else 1e-5
            assertTrue(rms < rmsBound, "floor 0 $order/$channels/$block: RMS $rms")
        }
    }

    @Test
    fun vectorLookupTypeTwoMatchesLibvorbisAtItsSixteenBitOutputPrecision() = inDirectory { directory ->
        for (order in listOf(1, 3, 4, 10, 31)) for (channels in 1..2) {
            val bytes = VorbisFloor0Fixtures.file(order = order, lookup = 2, channels = channels, block = 128)
            // FFmpeg's native Vorbis decoder rejects lookup type two.
            // Its public libvorbis wrapper supports it and emits signed 16-bit
            // samples. Compare at that range, allowing quantisation as well as
            // the existing 1e-4 floating synthesis bound.
            val reference = reference(directory, bytes, "libvorbis", sixteenBit = true)
            val actual = AudioKodec.decode(bytes)
            assertEquals(320, actual.frames)
            assertEquals(reference.size, actual.samples.size)
            val comparable = FloatArray(actual.samples.size) { actual.samples[it].coerceIn(-1f, 32767f / 32768) }
            TestSignals.assertClose(reference, comparable, 1e-4f, "lookup type two $order/$channels")
            val rms = sqrt(reference.indices.sumOf { (comparable[it].toDouble() - reference[it]).let { value -> value * value } } / reference.size)
            assertTrue(rms < 3e-5, "16-bit libvorbis comparison RMS $rms")
        }
    }

    @Test
    fun chainedFloorZeroSetupsRetainEachLinksReferenceSamples() = inDirectory { directory ->
        val first = VorbisFloor0Fixtures.file(order = 3, block = 64)
        val second = VorbisFloor0Fixtures.file(order = 10, block = 128)
        val expected = reference(directory, first, "vorbis", false) + reference(directory, second, "vorbis", false)
        val actual = AudioKodec.decode(first + second)
        assertEquals(480, actual.frames)
        assertEquals(480L, actual.info.durationFrames)
        TestSignals.assertClose(expected, actual.samples, 1e-4f, "changed floor-zero chain")
    }

    private fun reference(directory: File, bytes: ByteArray, decoder: String, sixteenBit: Boolean): FloatArray {
        val input = File(directory, "floor.ogg").also { it.writeBytes(bytes) }
        val output = File(directory, "reference.pcm")
        val log = File(directory, "ffmpeg.log")
        val ffmpeg = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "ffmpeg") }
            .firstOrNull { it.isFile && it.canExecute() }
        assumeTrue("FFmpeg is required for the Vorbis floor-zero comparison", ffmpeg != null)
        val process = ProcessBuilder(ffmpeg!!.path, "-v", "error", "-y", "-c:a", decoder, "-i", input.path,
            "-f", if (sixteenBit) "s16le" else "f32le", output.path)
            .redirectErrorStream(true).redirectOutput(log).oracleStart()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            throw AssertionError("FFmpeg did not finish")
        }
        assertEquals(0, process.exitValue(), log.readText())
        val buffer = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return if (sixteenBit) FloatArray(buffer.remaining() / 2) { buffer.short / 32768f }
            else FloatArray(buffer.remaining() / 4) { buffer.float }.also { assertTrue(it.all(Float::isFinite)) }
    }

    private fun inDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("audiokodec-vorbis-floor0-").toFile()
        try { test(directory) } finally { directory.deleteRecursively() }
    }
}

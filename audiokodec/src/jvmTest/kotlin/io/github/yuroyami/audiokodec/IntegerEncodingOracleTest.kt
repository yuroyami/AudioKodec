package io.github.yuroyami.audiokodec

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** Compares exact integer input through public executable interfaces only. */
class IntegerEncodingOracleTest {
    @Test
    fun aReference32BitWaveSurvivesAiffAuAndWaveTranscodes() {
        val ffmpeg = tool("ffmpeg")
        val directory = Files.createTempDirectory("audiokodec-integer-transcode-").toFile()
        try {
            val format = AudioFormat(48_000, 2)
            val expected = IntegerEncodingFixtures.samples(32, 4_099, 2)
            val input = File(directory, "input.s32").also { it.writeBytes(littleEndian(expected, 4)) }
            val original = File(directory, "original.wav")
            run(listOf(ffmpeg, "-v", "error", "-f", "s32le", "-ar", "48000", "-ac", "2", "-i", input.path,
                "-c:a", "pcm_s32le", original.path))
            assertContentEquals(expected, IntegerEncodingFixtures.read(original.readBytes()))
            var bytes = original.readBytes()
            for ((index, target) in listOf(EncodeTarget.aiff(32), EncodeTarget.au(32), EncodeTarget.wav(32)).withIndex()) {
                bytes = AudioKodec.encode(IntegerEncodingFixtures.read(bytes), format, 32, target)
                val file = File(directory, "step-$index.${extension(target)}").also { it.writeBytes(bytes) }
                val decoded = File(directory, "step-$index.s32")
                run(listOf(ffmpeg, "-v", "error", "-i", file.path, "-c:a", "pcm_s32le", "-f", "s32le", decoded.path))
                assertContentEquals(input.readBytes(), decoded.readBytes(), "$target: independent 32-bit PCM")
                assertContentEquals(expected, IntegerEncodingFixtures.read(bytes))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun independentDecodersReadEveryIntegerTargetDepthAndChannelCountExactly() {
        val ffmpeg = tool("ffmpeg")
        val flac = tool("flac")
        val directory = Files.createTempDirectory("audiokodec-integer-oracle-").toFile()
        try {
            for (depth in listOf(8, 16, 24, 32)) {
                for (channels in listOf(1, 2, 6)) {
                    val samples = IntegerEncodingFixtures.samples(depth, 1_003, channels)
                    val targets = listOf(EncodeTarget.wav(depth), EncodeTarget.aiff(depth), EncodeTarget.au(depth)) +
                        if (depth < 32) listOf(EncodeTarget.flac(depth)) else emptyList()
                    val expected = littleEndian(samples.map { it shl (32 - depth) }.toIntArray(), 4)
                    for (target in targets) {
                        val file = File(directory, "written-$depth-$channels.${extension(target)}")
                        file.writeBytes(AudioKodec.encode(samples, AudioFormat(48_000, channels), depth, target))
                        val decoded = File(directory, "decoded-$depth-$channels-${extension(target)}.s32")
                        run(listOf(ffmpeg, "-v", "error", "-i", file.path, "-c:a", "pcm_s32le", "-f", "s32le", decoded.path))
                        assertContentEquals(expected, decoded.readBytes(), "$target, $channels channels")
                        if (target.container == AudioContainer.FLAC) {
                            run(listOf(flac, "--silent", "--test", file.path))
                            val native = File(directory, "native-$depth-$channels.raw")
                            run(listOf(flac, "--silent", "--decode", "--force-raw-format", "--endian=little", "--sign=signed",
                                "-o", native.path, file.path))
                            assertContentEquals(littleEndian(samples, depth / 8), native.readBytes(), "reference FLAC integers and MD5")
                        }
                    }
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun integerDepthReductionAgreesWithReferencePcmConversion() {
        val ffmpeg = tool("ffmpeg")
        val directory = Files.createTempDirectory("audiokodec-integer-depth-").toFile()
        try {
            val samples = IntegerEncodingFixtures.samples(32, 1_013, 2)
            val raw = File(directory, "input.s32").also { it.writeBytes(littleEndian(samples, 4)) }
            for (depth in listOf(8, 16, 24)) {
                for (target in listOf(EncodeTarget.wav(depth), EncodeTarget.aiff(depth), EncodeTarget.au(depth))) {
                    val signed = if (depth == 8) {
                        if (target.container == AudioContainer.WAV) "pcm_u8" else "pcm_s8"
                    } else "pcm_s$depth" + if (target.container == AudioContainer.WAV) "le" else "be"
                    val reference = File(directory, "reference-$depth.${extension(target)}")
                    run(listOf(ffmpeg, "-v", "error", "-f", "s32le", "-ar", "48000", "-ac", "2", "-i", raw.path,
                        "-c:a", signed, reference.path))
                    val ours = AudioKodec.encode(samples, AudioFormat(48_000, 2), 32, target)
                    assertContentEquals(IntegerEncodingFixtures.read(reference.readBytes()), IntegerEncodingFixtures.read(ours),
                        "32 to $target: independent depth conversion")
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun littleEndian(samples: IntArray, bytes: Int): ByteArray = ByteArray(samples.size * bytes).also { output ->
        for (index in samples.indices) for (byte in 0 until bytes) output[index * bytes + byte] = (samples[index] ushr (8 * byte)).toByte()
    }

    private fun extension(target: EncodeTarget): String = when (target.container) {
        AudioContainer.WAV -> "wav"
        AudioContainer.AIFF -> "aiff"
        AudioContainer.AU -> "au"
        else -> "flac"
    }

    private fun tool(name: String): String {
        val found = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }
        assumeTrue("$name is required for integer encoding comparisons", found != null)
        return found!!.path
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "${command.first()} did not finish")
        assertEquals(0, process.exitValue(), output)
    }
}

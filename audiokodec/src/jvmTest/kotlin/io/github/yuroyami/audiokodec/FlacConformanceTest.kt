package io.github.yuroyami.audiokodec

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assume
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Published IETF CELLAR vectors, with one reported test per file. */
@RunWith(Parameterized::class)
class FlacConformanceTest(private val path: String, private val length: Long, private val checksum: String) {
    private val options = DecodeOptions(reuseBuffers = true, maxAllocationBytes = 128L shl 20)

    @Test
    fun publishedVectorHasTheDocumentedOutcome() {
        val configured = System.getenv("AUDIOKODEC_FLAC_CONFORMANCE_DIR")
        val file = if (configured != null) {
            File(configured, path).also { assertTrue(it.isFile, "configured corpus file is missing: $it") }
        } else {
            listOf(File(root, "audiokodec/build/flac-conformance/$path"), File(data, path))
                .firstOrNull { it.isFile }
        }
        Assume.assumeTrue("published FLAC vector is unavailable: $path; run prepare-flac-conformance.py", file != null)
        val bytes = assertNotNull(file).readBytes()
        assertEquals(length, bytes.size.toLong(), "$path: byte length")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(checksum, hash, "$path: pinned vector checksum")
        val number = path.substringAfter('/').substringBefore(" - ").toInt()
        when {
            path.startsWith("faulty/") -> {
                for (tolerant in listOf(false, true)) {
                    for (knownSize in listOf(false, true)) {
                        try {
                            consume(bytes, options.copy(tolerant = tolerant), knownSize)
                        } catch (_: AudioKodecException) {
                            // The corpus permits clean decoding or a typed
                            // failure. It forbids crashes and endless loops.
                        }
                    }
                }
                println("FLAC corpus safety: $path")
            }
            path.startsWith("uncommon/") && number in setOf(1, 2, 3, 4, 10, 11) -> {
                for (knownSize in listOf(false, true)) {
                    assertFailsWith<AudioKodecException>(path) { consume(bytes, options, knownSize) }
                }
                println("FLAC corpus typed refusal: $path")
            }
            else -> compareReference(bytes)
        }
    }

    private fun compareReference(bytes: ByteArray) {
        val flac = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "flac") }.firstOrNull { it.isFile && it.canExecute() }
        Assume.assumeTrue("the flac reference executable is required for $path", flac != null)
        val directory = java.nio.file.Files.createTempDirectory("audiokodec-flac-corpus-").toFile()
        try {
            val input = File(directory, "vector.flac").also { it.writeBytes(bytes) }
            val output = File(directory, "reference.wav")
            val log = File(directory, "reference.log")
            val process = ProcessBuilder(assertNotNull(flac).path, "--silent", "--decode", "--force",
                "-o", output.path, input.path).redirectErrorStream(true).redirectOutput(log).start()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            assertTrue(finished, "$path: the reference decoder did not finish")
            assertEquals(0, process.exitValue(), "$path: ${log.readText()}")
            val reference = output.readBytes()
            val info = AudioKodec.probe(bytes, options)
            var frames = 0L
            AudioKodec.open(reference, options).use { expected ->
                AudioKodec.open(bytes, options).use { actual ->
                    assertEquals(expected.format.sampleRate, actual.format.sampleRate, path)
                    assertEquals(expected.format.channels, actual.format.channels, path)
                    val samples = IntegerSamples(expected)
                    val integers = assertIs<IntPcmAccess>(actual)
                    while (true) {
                        val block = integers.nextIntFrame() ?: break
                        assertTrue(block.frameCount > 0, "$path: empty PCM block")
                        assertEquals(frames, block.positionFrames, "$path: continuous position")
                        compareBlock(block, samples)
                        frames += block.frameCount
                    }
                    assertNull(samples.next(), "$path: incomplete reference PCM")
                }
            }
            info.durationFrames?.let { assertEquals(frames, it, "$path: declared duration") }
            val md5Present = bytes.copyOfRange(26, 42).any { it != 0.toByte() }
            assertEquals(if (md5Present) true else null, AudioKodec.verifyFlacMd5(bytes, options), "$path: stored MD5")
            for (knownSize in listOf(false, true)) {
                AudioKodec.open(OggDamageFixtures.source(bytes, knownSize), options).use { actual ->
                    val integers = assertIs<IntPcmAccess>(actual)
                    for (target in listOf(frames - 1, 0L, frames / 3, 1L).distinct()) {
                        assertEquals(target, actual.seek(target), "$path: seek with knownSize=$knownSize")
                        val block = assertNotNull(integers.nextIntFrame(), "$path: audio after seek")
                        assertEquals(target, block.positionFrames, path)
                        AudioKodec.open(reference, options).use { expected ->
                            assertEquals(target, expected.seek(target), path)
                            compareBlock(block, IntegerSamples(expected))
                        }
                    }
                    assertEquals(frames, actual.seek(frames), "$path: seek to EOF")
                    assertNull(integers.nextIntFrame(), "$path: EOF after seek")
                }
            }
            println("FLAC corpus exact PCM: $path ($frames frames, ${info.bitDepth} bits)")
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun compareBlock(block: IntAudioFrame, expected: IntegerSamples) {
        for (index in 0 until block.sampleCount) {
            val actual = block.samples[index] shl (32 - block.bitDepth)
            val reference = expected.next()
            if (actual != reference) {
                fail("$path: sample ${block.positionFrames * block.format.channels + index}: $actual != $reference")
            }
        }
    }

    private fun consume(bytes: ByteArray, options: DecodeOptions, knownSize: Boolean) {
        AudioKodec.open(OggDamageFixtures.source(bytes, knownSize), options).use { decoder ->
            var position = 0L
            // Every encoded frame occupies at least one source byte. This
            // bound detects a decoder returning output without consuming input.
            repeat(bytes.size.coerceAtLeast(1)) {
                val block = decoder.nextFrame() ?: return
                assertTrue(block.frameCount > 0, "$path: decoder returned an empty block")
                assertEquals(position, block.positionFrames, "$path: decoder did not advance")
                position += block.frameCount
            }
            fail("$path: decoder did not finish within a source-sized block bound")
        }
    }

    /** Normalise stored PCM into signed 32-bit values without passing through Float. */
    private class IntegerSamples(decoder: AudioDecoder) {
        private val integers = assertIs<IntPcmAccess>(decoder)
        private var block: IntAudioFrame? = null
        private var index = 0

        fun next(): Int? {
            var current = block
            if (current == null || index == current.sampleCount) {
                current = integers.nextIntFrame() ?: return null
                block = current
                index = 0
                assertTrue(current.sampleCount > 0)
            }
            return current.samples[index++] shl (32 - current.bitDepth)
        }
    }

    companion object {
        private val root: File = listOf(File(System.getProperty("user.dir")), File(System.getProperty("user.dir")).parentFile)
            .first { File(it, "test-data/flac-conformance/manifest.tsv").isFile }
        private val data = File(root, "test-data/flac-conformance")

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun vectors(): List<Array<Any>> = File(data, "manifest.tsv").readLines()
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .map { it.split('\t') }
            .filter { it[0].endsWith(".flac") }
            .map { arrayOf(it[0], it[1].toLong(), it[2]) }
    }
}

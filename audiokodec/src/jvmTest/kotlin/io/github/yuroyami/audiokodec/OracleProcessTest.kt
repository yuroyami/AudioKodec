package io.github.yuroyami.audiokodec

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class OracleProcessTest {
    private fun python(): String {
        val executable = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "python3") }.firstOrNull { it.canExecute() }
        assumeTrue("Python is required for process provenance checks", executable != null)
        return executable!!.path
    }

    @Test
    fun recordsExactArgumentsPipeBytesAndFileChangesBeforeTheCallerDeletesThem() {
        val directory = Files.createTempDirectory("audiokodec-provenance-test-").toFile()
        try {
            val file = File(directory, "quotes-\"-unicode-日本語.bin")
            val original = byteArrayOf(0, 1, 2, -1)
            file.writeBytes(original)
            val incoming = byteArrayOf(0, 10, 13, 34, 92, -128, -1)
            val script = "import pathlib,sys; p=pathlib.Path(sys.argv[1]); data=sys.stdin.buffer.read(); " +
                "p.write_bytes(data); sys.stdout.buffer.write(data); sys.stderr.write('separate error\\n')"
            val process = OracleRecording.start(ProcessBuilder(python(), "-c", script, file.path), directory)
            process.outputStream.use { it.write(incoming); it.flush() }
            assertTrue(process.inputStream.readBytes().contentEquals(incoming))
            assertEquals("separate error\n", process.errorStream.bufferedReader().readText())
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.waitFor())
            assertEquals(0, process.exitValue())
            assertFalse(process.isAlive)
            assertEquals(process.pid(), process.toHandle().pid())
            file.delete()
            val records = directory.listFiles()!!.single { it.extension == "jsonl" }.readLines()
            assertEquals(1, records.count { it.contains("\"event\":\"start\"") })
            assertEquals(1, records.count { it.contains("\"event\":\"finish\"") })
            assertEquals(3, records.count { it.contains("\"event\":\"pipe\"") })
            assertTrue(records.first().contains("\\\"-unicode-\\u65e5\\u672c\\u8a9e"), "JSON escapes exact path")
            assertTrue(records.first().contains(hash(original)), "input fingerprint")
            assertTrue(records.single { it.contains("\"event\":\"finish\"") }.contains(hash(incoming)), "output fingerprint")
            for (name in listOf("stdin", "stdout")) {
                assertTrue(records.single { it.contains("\"stream\":\"$name\"") }.contains(hash(incoming)))
            }
            assertTrue(records.first().contains("Python "))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aTimeoutIsNotReportedAsCompletionAndTerminationRemainsAvailable() {
        val directory = Files.createTempDirectory("audiokodec-provenance-timeout-").toFile()
        try {
            // The child blocks on stdin, giving a deterministic timeout
            // without relying on scheduler delays or a sleeping process.
            val process = OracleRecording.start(ProcessBuilder(python(), "-c", "import sys; sys.stdin.buffer.read()"), directory)
            assertFalse(process.waitFor(0, TimeUnit.NANOSECONDS))
            assertFalse(directory.listFiles()!!.single { it.extension == "jsonl" }.readText().contains("\"event\":\"finish\""))
            process.destroyForcibly()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(1, directory.listFiles()!!.single { it.extension == "jsonl" }.readLines()
                .count { it.contains("\"event\":\"finish\"") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ordinaryRunsKeepTheOriginalProcessAndDoNotProduceARecording() {
        val builder = ProcessBuilder(python(), "-c", "print('ordinary')")
        val process = OracleRecording.start(builder, null)
        assertEquals("ordinary\n", process.inputStream.bufferedReader().readText())
        assertEquals(0, process.waitFor())
        assertFalse(process.javaClass.name.contains("RecordedProcess"))
    }

    @Test
    fun ffmpegOggFixturesRepeatByteForByte() {
        val ffmpeg = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "ffmpeg") }.firstOrNull { it.canExecute() }
        assumeTrue("FFmpeg is required for deterministic fixture checks", ffmpeg != null)
        val directory = Files.createTempDirectory("audiokodec-provenance-ogg-").toFile()
        try {
            for (codec in listOf("libvorbis", "libopus", "flac")) {
                val hashes = (0..1).map { index ->
                    val file = File(directory, "$codec-$index.ogg")
                    val command = listOf(ffmpeg!!.path, "-v", "error", "-f", "lavfi", "-i",
                        "sine=frequency=440:sample_rate=48000:duration=0.02", "-c:a", codec, file.path)
                    val builder = if (index == 0) ProcessBuilder(*command.toTypedArray())
                        else ProcessBuilder(java.util.Collections.unmodifiableList(command))
                    val process = builder.redirectErrorStream(true).oracleStart()
                    val output = process.inputStream.bufferedReader().readText()
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                    assertEquals(0, process.exitValue(), output)
                    OracleRecording.sha256(file)
                }
                assertEquals(hashes[0], hashes[1], "$codec: Ogg serials must be reproducible")
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun referenceFlacOggFixturesRepeatByteForByte() {
        val flac = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "flac") }.firstOrNull { it.canExecute() }
        assumeTrue("FLAC is required for deterministic fixture checks", flac != null)
        val directory = Files.createTempDirectory("audiokodec-provenance-flac-").toFile()
        try {
            val input = File(directory, "input.wav").also {
                it.writeBytes(AudioKodec.encode(FloatArray(64) { index -> (index - 32) / 32768f },
                    AudioFormat(8_000, 1), EncodeTarget.wav(16)))
            }
            val hashes = (0..1).map { index ->
                val output = File(directory, "output-$index.ogg")
                val process = ProcessBuilder(listOf(flac!!.path, "--silent", "--ogg", "-o", output.path, input.path))
                    .redirectErrorStream(true).oracleStart()
                val message = process.inputStream.bufferedReader().readText()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue(), message)
                OracleRecording.sha256(output)
            }
            assertEquals(hashes[0], hashes[1], "reference FLAC serials must be reproducible")
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

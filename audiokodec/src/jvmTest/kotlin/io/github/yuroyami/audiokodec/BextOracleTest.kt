package io.github.yuroyami.audiokodec

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** Public executable interfaces only; no implementation source is used. */
class BextOracleTest {
    private fun tool(name: String): String {
        val found = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }
        assumeTrue("$name is required for Broadcast Wave comparisons", found != null)
        return found!!.path
    }

    @Test
    fun ffprobeAndExiftoolReadEveryWrittenFieldAndDecodeUnchangedPcm() {
        val ffmpeg = tool("ffmpeg")
        val ffprobe = tool("ffprobe")
        val exiftool = tool("exiftool")
        val directory = Files.createTempDirectory("audiokodec-bext-oracle-").toFile()
        try {
            val original = File(directory, "original.wav").also {
                run(listOf(ffmpeg, "-v", "error", "-f", "lavfi", "-i",
                    "sine=frequency=400:sample_rate=8000:duration=0.02", "-c:a", "pcm_s16le", it.path))
            }
            val values = BextFixtures.values + ("Description" to "D".repeat(300)) + ("Originator" to "O".repeat(40))
            val file = File(directory, "broadcast.wav")
            file.writeBytes(AudioKodec.writeTags(original.readBytes(), BextFixtures.tags(values), BextFixtures.options))
            val expected = values + ("Description" to "D".repeat(256)) + ("Originator" to "O".repeat(32))
            assertEquals(expected, BextFixtures.text(file.readBytes()))
            for ((field, key) in mapOf("Description" to "comment", "Originator" to "encoded_by",
                "OriginatorReference" to "originator_reference", "OriginationDate" to "date",
                "OriginationTime" to "creation_time", "TimeReference" to "time_reference")) {
                assertEquals(expected.getValue(field), run(listOf(ffprobe, "-v", "error", "-show_entries",
                    "format_tags=$key", "-of", "default=noprint_wrappers=1:nokey=1", file.path)).trimEnd())
            }
            for (field in listOf("Description", "Originator", "OriginatorReference", "TimeReference", "CodingHistory")) {
                assertEquals(expected.getValue(field), run(listOf(exiftool, "-b", "-$field", file.path)))
            }
            assertEquals("2026:10:08 12:34:56", run(listOf(exiftool, "-b", "-DateTimeOriginal", file.path)))
            fun pcm(input: File, name: String): ByteArray {
                val raw = File(directory, name)
                run(listOf(ffmpeg, "-v", "error", "-i", input.path, "-f", "f32le", raw.path))
                return raw.readBytes()
            }
            assertContentEquals(pcm(original, "before.f32"), pcm(file, "after.f32"))
            assertContentEquals(BextFixtures.payload(original.readBytes()), BextFixtures.payload(file.readBytes()))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun editsReferenceGeneratedBroadcastMetadataAndKeepsAudioAndHistory() {
        val ffmpeg = tool("ffmpeg")
        val exiftool = tool("exiftool")
        val directory = Files.createTempDirectory("audiokodec-bext-reference-").toFile()
        try {
            val file = File(directory, "reference.wav")
            run(listOf(ffmpeg, "-v", "error", "-f", "lavfi", "-i",
                "sine=frequency=500:sample_rate=8000:duration=0.02", "-c:a", "pcm_s16le", "-write_bext", "1",
                "-metadata", "description=Reference description", "-metadata", "originator=Reference station",
                "-metadata", "originator_reference=REFERENCE-001", "-metadata", "origination_date=2026-10-08",
                "-metadata", "origination_time=12:34:56", "-metadata", "time_reference=4294967297",
                "-metadata", "coding_history=A=PCM,F=8000,W=16,M=mono\r\n", file.path))
            val original = file.readBytes()
            assertEquals("Reference description", BextFixtures.text(original)["Description"])
            assertEquals("Reference station", BextFixtures.text(original)["Originator"])
            assertEquals("2026-10-08", BextFixtures.text(original)["OriginationDate"])
            assertEquals("12:34:56", BextFixtures.text(original)["OriginationTime"])
            assertEquals("4294967297", BextFixtures.text(original)["TimeReference"])
            val read = AudioKodec.readTags(original)
            val entries = read.raw.blocksOf(io.github.yuroyami.audiokodec.tag.TagFormat.BWF_BEXT).single().entries
                .map { if (it.key == "Description") io.github.yuroyami.audiokodec.tag.RawTagEntry(it.key, text = "Edited") else it }
            val edited = io.github.yuroyami.audiokodec.tag.TagBundle(emptyMap(), raw = io.github.yuroyami.audiokodec.tag.RawTags(
                listOf(io.github.yuroyami.audiokodec.tag.RawTagBlock(io.github.yuroyami.audiokodec.tag.TagFormat.BWF_BEXT, 0, 0, entries))))
            file.writeBytes(AudioKodec.writeTags(original, edited, BextFixtures.options))
            assertEquals("Edited", run(listOf(exiftool, "-b", "-Description", file.path)))
            assertEquals("Reference station", run(listOf(exiftool, "-b", "-Originator", file.path)))
            assertEquals("4294967297", run(listOf(exiftool, "-b", "-TimeReference", file.path)))
            assertEquals("A=PCM,F=8000,W=16,M=mono\r\n", run(listOf(exiftool, "-b", "-CodingHistory", file.path)))
            assertContentEquals(BextFixtures.payload(original), BextFixtures.payload(file.readBytes()))
            assertContentEquals(AudioKodec.decode(original).samples, AudioKodec.decode(file.readBytes()).samples)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "${command.first()} did not finish")
        assertEquals(0, process.exitValue(), output)
        return output
    }
}

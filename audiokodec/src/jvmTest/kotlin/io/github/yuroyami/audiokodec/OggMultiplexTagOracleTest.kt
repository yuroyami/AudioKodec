package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagField
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Writes tags into Ogg files that ffmpeg made with two logical streams
 * interleaved, and checks the result three ways: libogg reads every page of
 * it with no checksum failure and no hole in any stream, every packet of
 * every stream comes back unchanged and in the same order but the one comment
 * packet that was rewritten, with the streams' audio interleaved as before, and the audio decodes to the same samples, in
 * AudioKodec and in ffmpeg.
 *
 * libogg is used through its public interface only, by `ogg_packets.c`.
 */
class OggMultiplexTagOracleTest {

    @Test
    fun writingTagsKeepsEveryOtherPacketInItsPlace() {
        val ffmpeg = findOnPath("ffmpeg")
        assumeTrue("ffmpeg is required for the multiplexed Ogg oracle", ffmpeg != null)
        val lister = lister()
        val cases = listOf(
            "two Vorbis streams" to listOf("-c:a", "libvorbis"),
            "Opus and Vorbis" to listOf("-c:a:0", "libopus", "-c:a:1", "libvorbis"),
            "Vorbis and FLAC" to listOf("-c:a:0", "libvorbis", "-c:a:1", "flac"),
        )
        for ((name, codecs) in cases) {
            val original = File(workDirectory, "two.ogg")
            original.delete()
            run(
                listOf(
                    ffmpeg!!, "-hide_banner", "-v", "error", "-y",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=3",
                    "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000:duration=3",
                    "-map", "0", "-map", "1",
                ) + codecs + original.path,
            )
            val before = original.readBytes()
            assertTrue(packets(lister, original).map { it.serial }.distinct().size == 2, "$name: two streams")
            // A comment far larger than the old one, so the header pages change size and count.
            val title = ("A title " + "long enough to need its own page ".repeat(400)).trim()
            val after = AudioKodec.writeTags(before, AudioKodec.readTags(before).edit { set(TagField.TITLE, title) })
            assertEquals(title, AudioKodec.readTags(after)[TagField.TITLE], name)

            val rewritten = File(workDirectory, "two-tagged.ogg").also { it.writeBytes(after) }
            val old = packets(lister, original)
            val new = packets(lister, rewritten)
            val audioSerial = old.first().serial
            // Every packet of each stream but the rewritten comment header, in the order libogg delivers them.
            fun kept(list: List<Packet>) = list.filterNot { it.serial == audioSerial && it.index == 1L }.groupBy { it.serial }
            assertEquals(kept(old), kept(new), "$name: packets")
            // The audio packets of the two streams still interleave as they did.
            fun audio(list: List<Packet>) = list.filter { it.granule > 0 }.map { it.serial to it.index }
            assertEquals(audio(old), audio(new), "$name: interleaving")

            assertContentEquals(AudioKodec.decode(before).samples, AudioKodec.decode(after).samples, name)
            for (stream in 0..1) {
                assertContentEquals(decodeWithFfmpeg(ffmpeg, original, stream), decodeWithFfmpeg(ffmpeg, rewritten, stream), "$name, stream $stream in ffmpeg")
            }
        }
    }

    // Helpers.

    private data class Packet(val serial: Long, val index: Long, val granule: Long, val bytes: Long, val hash: String)

    private fun packets(binary: String, file: File): List<Packet> {
        val process = ProcessBuilder(binary, file.path).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "libogg did not finish")
        assertEquals(0, process.exitValue(), "libogg found a damaged page or a hole in ${file.name}")
        return output.lines().filter { it.isNotBlank() }.map { line ->
            val f = line.split(' ')
            Packet(f[0].toLong(), f[1].toLong(), f[2].toLong(), f[3].toLong(), f[4])
        }
    }

    private fun decodeWithFfmpeg(ffmpeg: String, file: File, stream: Int): ByteArray {
        val out = File(workDirectory, "decoded-$stream.f32")
        run(listOf(ffmpeg, "-hide_banner", "-v", "error", "-y", "-i", file.path, "-map", "0:a:$stream", "-f", "f32le", out.path))
        return out.readBytes()
    }

    private fun lister(): String {
        assumeTrue("libogg's headers are required for the multiplexed Ogg oracle", File("/usr/include/ogg/ogg.h").isFile)
        val binary = File(workDirectory, "ogg_packets")
        if (!binary.canExecute()) {
            val source = File(workDirectory, "ogg_packets.c")
            source.writeBytes(OggMultiplexTagOracleTest::class.java.getResourceAsStream("/ogg_packets.c")!!.readBytes())
            run(listOf("cc", "-O2", "-o", binary.path, source.path, "-logg"))
        }
        return binary.path
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS), "${command.first()} did not finish")
        if (process.exitValue() != 0) throw AssertionError("${File(command.first()).name} failed:\n${output.take(2000)}")
    }

    private companion object {
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-ogg-multiplex").also { it.mkdirs() }

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

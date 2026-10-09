package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.container.AdtsStream
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks the ADTS frame walker against ffprobe on files ffmpeg's AAC encoder
 * writes, the way the MPEG oracle checks MPEG audio: at sample rates from
 * 8 to 96 kHz, with one, two and six channels, and behind an ID3v2 tag.
 * Probing must report ffprobe's sample rate and channel count, a length of
 * 1024 frames for each packet ffprobe lists, and every frame where ffprobe
 * says its packet starts.
 *
 * Every test skips itself when ffmpeg or ffprobe is missing.
 */
class AdtsOracleTest {

    @Test
    fun probingAgreesWithFfprobeOnEveryFrame() {
        val ffmpeg = findOnPath("ffmpeg")
        val ffprobe = findOnPath("ffprobe")
        assumeTrue("ffmpeg and ffprobe are required for the ADTS oracle", ffmpeg != null && ffprobe != null)
        val cases = listOf(8_000 to 1, 16_000 to 2, 22_050 to 1, 32_000 to 2, 44_100 to 2, 48_000 to 6, 96_000 to 2) +
            listOf(44_100 to 1)
        for ((index, case) in cases.withIndex()) {
            val (rate, channels) = case
            val tagged = index == cases.lastIndex
            val file = temp("adts_${rate}_$channels${if (tagged) "_id3" else ""}.aac")
            run(
                listOf(
                    ffmpeg!!, "-y", "-v", "error", "-f", "lavfi",
                    "-i", "sine=frequency=${300 + index * 50}:sample_rate=$rate:duration=1.3",
                    "-ac", "$channels", "-c:a", "aac", "-b:a", "${32 * channels}k",
                ) + (if (tagged) listOf("-write_id3v2", "1", "-metadata", "title=ADTS") else emptyList()) + file.path,
            )
            val what = "$rate Hz, $channels ch${if (tagged) ", ID3" else ""}"
            val stream = capture(listOf(ffprobe!!, "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=sample_rate,channels", "-of", "csv=p=0", file.path)).trim().split(",")
            val positions = capture(listOf(ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries", "packet=pos", "-of", "csv=p=0", file.path))
                .lines().map { it.trim() }.filter { it.isNotEmpty() }.map { it.toLong() }

            val bytes = file.readBytes()
            val info = AudioKodec.probe(bytes)
            assertEquals(AudioContainer.ADTS, info.container, what)
            assertEquals(stream[0].toInt(), info.format.sampleRate, "$what: rate")
            assertEquals(stream[1].toInt(), info.format.channels, "$what: channels")
            assertEquals(positions.size * 1024L, info.durationFrames, "$what: length")
            assertEquals(positions.first(), info.dataOffset, "$what: first frame")

            val reader = SourceReader(bytes.asAudioSource(), DecodeOptions.DEFAULT)
            val offsets = ArrayList<Long>()
            AdtsStream.parse(reader, DecodeOptions.DEFAULT).frames(reader) { offsets += it.offset; true }
            assertEquals(positions, offsets, "$what: frame offsets")
            if (tagged) {
                assertTrue(positions.first() > 0, "$what: the tag comes first")
                assertEquals("ADTS", AudioKodec.readTags(bytes)[io.github.yuroyami.audiokodec.tag.TagField.TITLE], "$what: the tag reads")
            }
        }
    }

    private fun run(command: List<String>) {
        capture(command)
    }

    private fun capture(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "${command.first()} did not finish")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed:\n$output")
        return output
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private companion object {
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-adts-oracle").also { it.mkdirs() }

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

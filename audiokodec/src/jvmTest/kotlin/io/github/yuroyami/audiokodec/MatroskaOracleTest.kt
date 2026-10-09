package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagField
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks Matroska and WebM reading against ffmpeg: the same audio in its own
 * container and copied into Matroska by ffmpeg must decode to the same
 * samples, probing must agree with ffprobe, and tags and chapters ffmpeg
 * writes must read back field for field.
 */
class MatroskaOracleTest {

    private class Case(val name: String, val native: String, val encode: List<String>, val codec: AudioCodec, val container: String = "mka")

    private val cases = listOf(
        Case("Opus", "opus", listOf("-c:a", "libopus", "-b:a", "96k"), AudioCodec.OPUS, "webm"),
        Case("Opus 5.1", "opus", listOf("-c:a", "libopus", "-ac", "6", "-b:a", "192k"), AudioCodec.OPUS),
        Case("Vorbis", "ogg", listOf("-c:a", "libvorbis", "-q:a", "4"), AudioCodec.VORBIS, "webm"),
        Case("FLAC", "flac", listOf("-c:a", "flac", "-sample_fmt", "s16"), AudioCodec.FLAC),
        Case("FLAC 24-bit", "flac", listOf("-c:a", "flac", "-sample_fmt", "s32", "-bits_per_raw_sample", "24"), AudioCodec.FLAC),
        Case("MP3", "mp3", listOf("-c:a", "libmp3lame", "-b:a", "128k"), AudioCodec.MP3),
        Case("AAC", "m4a", listOf("-c:a", "aac", "-b:a", "128k"), AudioCodec.AAC),
        Case("PCM 16-bit", "wav", listOf("-c:a", "pcm_s16le"), AudioCodec.PCM_INTEGER),
        Case("PCM 24-bit", "wav", listOf("-c:a", "pcm_s24le"), AudioCodec.PCM_INTEGER),
        Case("PCM float", "wav", listOf("-c:a", "pcm_f32le"), AudioCodec.PCM_FLOAT),
    )

    @Test
    fun eachCodingDecodesAsFromItsOwnContainerAndProbesAsFfprobeDoes() {
        val ffmpeg = tool("ffmpeg")
        val ffprobe = tool("ffprobe")
        for (case in cases) {
            val native = File(workDirectory, "native.${case.native}")
            val channels = if (case.name.contains("5.1")) "-ac 6" else "-ac 2"
            run(
                listOf(ffmpeg, "-hide_banner", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=3", "-af", "pan=${if (channels == "-ac 6") "5.1|c0=c0|c1=0.8*c0|c2=0.6*c0|c3=0.1*c0|c4=0.4*c0|c5=0.3*c0" else "stereo|c0=c0|c1=0.5*c0"}") +
                    case.encode + native.path,
            )
            val mka = File(workDirectory, "copied.${case.container}")
            run(listOf(ffmpeg, "-hide_banner", "-v", "error", "-y", "-i", native.path, "-c", "copy", mka.path))
            val ours = AudioKodec.decode(mka.readBytes())
            assertEquals(case.codec, ours.info.codec, case.name)
            assertEquals(AudioContainer.MATROSKA, ours.info.container, case.name)
            val reference = AudioKodec.decode(native.readBytes())
            assertEquals(reference.format.sampleRate, ours.format.sampleRate, case.name)
            assertEquals(reference.format.channelLayout, ours.format.channelLayout, case.name)
            // Matroska carries Opus's pre-skip and a final Block's padding, but has no field
            // for an encoder delay such as LAME's or AAC's priming, so a copy keeps those
            // frames, and ffmpeg plays them too. The length must be what ffmpeg decodes from
            // the same file, and the native container's audio must appear in it whole.
            val ffmpegFrames = decodedFrames(ffmpeg, mka, ours.format.channels)
            assertEquals(ffmpegFrames, ours.frames.toLong(), "${case.name}: length against ffmpeg")
            val channelCount = ours.format.channels
            val delay = (0..ours.frames - reference.frames).firstOrNull { offset ->
                (0 until minOf(256, reference.samples.size)).all { ours.samples[offset * channelCount + it] == reference.samples[it] }
            }
            assertTrue(delay != null, "${case.name}: the native audio does not appear in the Matroska decode")
            assertContentEquals(
                reference.samples,
                ours.samples.copyOfRange(delay!! * channelCount, delay * channelCount + reference.samples.size),
                "${case.name}: samples",
            )
            if (ours.frames != reference.frames) {
                println("${case.name}: Matroska keeps ${ours.frames - reference.frames} frames the native container trims, $delay of them at the start")
            }

            val probe = run(listOf(ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=sample_rate,channels:format=duration", "-of", "default=nw=1", mka.path))
            val fields = probe.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            val info = AudioKodec.probe(mka.readBytes())
            assertEquals(fields.getValue("sample_rate").toInt(), info.format.sampleRate, case.name)
            assertEquals(fields.getValue("channels").toInt(), info.format.channels, case.name)
            assertEquals(ours.frames.toLong(), info.durationFrames, "${case.name}: probed length")
            // ffprobe reports the segment's stated duration, to the millisecond, which counts
            // pre-skip, priming and padding differently for each codec: from 8 ms under the
            // decoded length for AAC to 8 ms over it for Opus in these files. AudioKodec counts
            // the frames that play, which the length check above holds to ffmpeg's own decode,
            // so the stated duration need only agree to within a packet.
            val seconds = info.durationFrames!!.toDouble() / info.format.sampleRate
            val stated = fields.getValue("duration").toDouble()
            assertTrue(abs(seconds - stated) < 0.025, "${case.name}: ${seconds}s against ffprobe's ${stated}s")
        }
    }

    @Test
    fun liveWebmWrittenToAPipeDecodesAsTheSameAudioWrittenToAFile() {
        // Written to a pipe, ffmpeg cannot go back to fill in the Segment's size, as a live
        // writer cannot; the samples must not depend on it.
        val ffmpeg = tool("ffmpeg")
        val source = listOf(ffmpeg, "-hide_banner", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=12", "-c:a", "libopus", "-b:a", "64k")
        val file = File(workDirectory, "seekable.webm")
        run(source + file.path)
        val live = File(workDirectory, "live.webm")
        val process = ProcessBuilder(source + listOf("-live", "1", "-cluster_time_limit", "1000", "-f", "webm", "pipe:1"))
            .redirectOutput(live).redirectError(ProcessBuilder.Redirect.DISCARD).oracleStart()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS) && process.exitValue() == 0, "ffmpeg did not write the live file")
        val expected = AudioKodec.decode(file.readBytes())
        val ours = AudioKodec.decode(live.readBytes())
        assertEquals(decodedFrames(ffmpeg, live, 1), ours.frames.toLong(), "length against ffmpeg")
        assertContentEquals(expected.samples, ours.samples)
    }

    @Test
    fun tagsAndChaptersFfmpegWritesReadBack() {
        val ffmpeg = tool("ffmpeg")
        val metadata = File(workDirectory, "chapters.txt")
        metadata.writeText(
            """
            ;FFMETADATA1
            title=A Title
            artist=An Artist
            album=An Album
            album_artist=Various
            genre=Test
            date=2004
            track=3
            comment=Some words
            [CHAPTER]
            TIMEBASE=1/1000
            START=0
            END=1000
            title=First
            [CHAPTER]
            TIMEBASE=1/1000
            START=1000
            END=3000
            title=Second
            """.trimIndent() + "\n",
        )
        val mka = File(workDirectory, "tagged.mka")
        run(
            listOf(
                ffmpeg, "-hide_banner", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=3",
                "-i", metadata.path, "-map_metadata", "1", "-map_chapters", "1", "-c:a", "libopus", mka.path,
            ),
        )
        val tags = AudioKodec.readTags(mka.readBytes())
        assertEquals("A Title", tags[TagField.TITLE])
        assertEquals("An Artist", tags[TagField.ARTIST])
        assertEquals("An Album", tags[TagField.ALBUM])
        assertEquals("Various", tags[TagField.ALBUM_ARTIST])
        assertEquals("Test", tags[TagField.GENRE])
        assertEquals("2004", tags[TagField.DATE])
        assertEquals("3", tags[TagField.TRACK_NUMBER])
        assertEquals("Some words", tags[TagField.COMMENT])
        assertEquals(listOf("First", "Second"), tags.chapters.map { it.title })
        assertEquals(listOf(0L, 1_000L), tags.chapters.map { it.startMillis })
        assertEquals(listOf(1_000L, 3_000L), tags.chapters.map { it.endMillis })
    }

    // Helpers.

    private fun decodedFrames(ffmpeg: String, file: File, channels: Int): Long {
        val out = File(workDirectory, "ffmpeg.f32")
        run(listOf(ffmpeg, "-hide_banner", "-v", "error", "-y", "-i", file.path, "-f", "f32le", out.path))
        return out.length() / 4 / channels
    }

    private fun tool(name: String): String {
        val found = findOnPath(name)
        assumeTrue("$name is required for the Matroska oracle", found != null)
        return found!!
    }

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS), "${command.first()} did not finish")
        if (process.exitValue() != 0) throw AssertionError("${File(command.first()).name} failed:\n${output.take(2000)}")
        return output
    }

    private companion object {
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-matroska-oracle").also { it.mkdirs() }

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

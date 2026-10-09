package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.PictureType
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagPicture
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Cross-checks the MP4 item list reader and writer against ffmpeg, on ALAC
 * files ffmpeg writes.
 *
 * ffmpeg writes tags and cover art and AudioKodec reads them; AudioKodec
 * writes them and ffprobe reads them, including text outside ISO-8859-1 and
 * a cover image. Both file layouts are covered: `moov` after the audio, as
 * ffmpeg writes by default, and before it, where a larger box moves the audio
 * and every chunk offset has to move with it. ffmpeg and AudioKodec must both
 * decode identical samples before and after each write.
 *
 * Every test skips itself when ffmpeg or ffprobe is missing.
 */
class Mp4TagOracleTest {

    private val ffmpeg: String? by lazy { findOnPath("ffmpeg") }
    private val ffprobe: String? by lazy { findOnPath("ffprobe") }

    @Test
    fun audioKodecReadsTheTagsAndCoverFfmpegWrites() {
        val ffmpeg = ffmpeg
        val ffprobe = ffprobe
        assumeTrue("ffmpeg and ffprobe are required for the MP4 tag oracle", ffmpeg != null && ffprobe != null)
        val cover = cover(ffmpeg!!)
        val file = temp("written-by-ffmpeg.m4a")
        // A metadata file is UTF-8 whatever the locale, where arguments
        // outside ASCII depend on how the JVM encodes a command line.
        val metadata = temp("metadata.txt")
        metadata.writeText(";FFMETADATA1\n" + METADATA.joinToString("") { (key, value) -> "$key=$value\n" }, Charsets.UTF_8)
        run(
            listOf(
                ffmpeg, "-y", "-v", "error",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=0.5",
                "-i", cover.path, "-f", "ffmetadata", "-i", metadata.path,
                "-map", "0", "-map", "1", "-map_metadata", "2",
                "-c:a", "alac", "-c:v", "copy", "-disposition:v", "attached_pic", file.path,
            ),
        )
        val reference = probeTags(ffprobe!!, file)
        val tags = AudioKodec.readTags(file.readBytes())
        assertEquals(reference["title"], tags[TagField.TITLE])
        assertEquals("Björk 日本語", tags[TagField.TITLE])
        assertEquals("Ólafur Arnalds", tags[TagField.ARTIST])
        assertEquals("Oracle Album Artist", tags[TagField.ALBUM_ARTIST])
        assertEquals("Oracle Album", tags[TagField.ALBUM])
        assertEquals("2026", tags[TagField.DATE])
        assertEquals("Ambient", tags[TagField.GENRE])
        assertEquals("Oracle comment", tags[TagField.COMMENT])
        assertEquals("Oracle Composer", tags[TagField.COMPOSER])
        assertEquals("Oracle lyrics", tags[TagField.LYRICS])
        assertEquals("Oracle Grouping", tags[TagField.GROUPING])
        assertEquals("Oracle Copyright", tags[TagField.COPYRIGHT])
        assertEquals(reference["encoder"], tags[TagField.ENCODER])
        assertEquals("3", tags[TagField.TRACK_NUMBER])
        assertEquals("12", tags[TagField.TRACK_TOTAL])
        assertEquals("1", tags[TagField.DISC_NUMBER])
        assertEquals("2", tags[TagField.DISC_TOTAL])
        assertEquals("1", tags[TagField.COMPILATION])
        assertEquals(listOf("Oracle description", "Oracle synopsis"), tags.all(TagField.DESCRIPTION))
        assertTrue(TagFormat.MP4_ILST in tags.raw.formats)
        val picture = tags.cover
        assertEquals("image/png", picture?.mimeType)
        assertContentEquals(cover.readBytes(), picture?.bytes)
    }

    @Test
    fun ffprobeReadsTheTagsAndCoverAudioKodecWrites() {
        val ffmpeg = ffmpeg
        val ffprobe = ffprobe
        assumeTrue("ffmpeg and ffprobe are required for the MP4 tag oracle", ffmpeg != null && ffprobe != null)
        val cover = cover(ffmpeg!!).readBytes()
        for (faststart in listOf(false, true)) {
            val plain = temp("plain-$faststart.m4a")
            run(
                listOf(ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=330:duration=0.7", "-c:a", "alac") +
                    (if (faststart) listOf("-movflags", "+faststart") else emptyList()) + plain.path,
            )
            val original = plain.readBytes()
            val edited = AudioKodec.readTags(original).edit {
                set(TagField.TITLE, "Björk 日本語")
                set(TagField.ARTIST, "Ólafur Arnalds")
                set(TagField.ALBUM, "AudioKodec Album")
                set(TagField.ALBUM_ARTIST, "AudioKodec Album Artist")
                set(TagField.DATE, "2025-11-02")
                set(TagField.GENRE, "Électronique")
                set(TagField.COMMENT, "Ünïcode comment")
                set(TagField.COMPOSER, "AudioKodec Composer")
                set(TagField.LYRICS, "Line one\nLine two")
                set(TagField.GROUPING, "AudioKodec Grouping")
                set(TagField.COPYRIGHT, "© 2025")
                set(TagField.DESCRIPTION, "AudioKodec description")
                set(TagField.TRACK_NUMBER, 4)
                set(TagField.TRACK_TOTAL, 10)
                set(TagField.DISC_NUMBER, 2)
                set(TagField.DISC_TOTAL, 3)
                set(TagField.COMPILATION, "1")
                set(TagField.MUSICBRAINZ_RELEASE_ID, "b84ee12a-09ef-421b-82de-0441a926375b")
                addPicture(TagPicture(PictureType.FRONT_COVER, "image/png", bytes = cover))
            }
            val tagged = temp("tagged-$faststart.m4a")
            tagged.writeBytes(AudioKodec.writeTags(original, edited))

            val reported = probeTags(ffprobe!!, tagged)
            assertEquals("Björk 日本語", reported["title"], "faststart $faststart")
            assertEquals("Ólafur Arnalds", reported["artist"])
            assertEquals("AudioKodec Album", reported["album"])
            assertEquals("AudioKodec Album Artist", reported["album_artist"])
            assertEquals("2025-11-02", reported["date"])
            assertEquals("Électronique", reported["genre"])
            assertEquals("Ünïcode comment", reported["comment"])
            assertEquals("AudioKodec Composer", reported["composer"])
            assertEquals("AudioKodec Grouping", reported["grouping"])
            assertEquals("© 2025", reported["copyright"])
            assertEquals("AudioKodec description", reported["description"])
            assertEquals("4/10", reported["track"])
            assertEquals("2/3", reported["disc"])
            assertEquals("1", reported["compilation"])
            assertEquals("b84ee12a-09ef-421b-82de-0441a926375b", reported["musicbrainz album id"])

            val extracted = temp("extracted-$faststart.png")
            run(listOf(ffmpeg, "-y", "-v", "error", "-i", tagged.path, "-map", "0:v", "-c", "copy", "-f", "image2", extracted.path))
            assertContentEquals(cover, extracted.readBytes(), "ffmpeg extracts the cover unchanged")

            assertContentEquals(pcm(ffmpeg, plain), pcm(ffmpeg, tagged), "ffmpeg decodes the same audio, faststart $faststart")
            assertContentEquals(AudioKodec.decode(original).samples, AudioKodec.decode(tagged.readBytes()).samples)
            assertEquals(edited.fields, AudioKodec.readTags(tagged.readBytes()).fields)

            // A second edit that shrinks the tags moves nothing.
            val shrunk = AudioKodec.writeTags(tagged.readBytes(), AudioKodec.readTags(tagged.readBytes()).edit { removePictures() })
            assertEquals(tagged.length(), shrunk.size.toLong())
            val shrunkFile = temp("shrunk-$faststart.m4a").also { it.writeBytes(shrunk) }
            assertContentEquals(pcm(ffmpeg, plain), pcm(ffmpeg, shrunkFile))
            assertEquals("Björk 日本語", probeTags(ffprobe, shrunkFile)["title"])
        }
    }

    private fun cover(ffmpeg: String): File {
        val file = temp("cover.png")
        if (!file.exists()) {
            run(listOf(ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "color=c=0x3366cc:s=16x16", "-frames:v", "1", file.path))
        }
        return file
    }

    private fun pcm(ffmpeg: String, file: File): ByteArray {
        val raw = temp(file.name + ".s32")
        run(listOf(ffmpeg, "-y", "-v", "error", "-i", file.path, "-map", "0:a", "-f", "s32le", raw.path))
        return raw.readBytes()
    }

    private fun probeTags(ffprobe: String, file: File): Map<String, String> {
        val output = capture(
            listOf(
                ffprobe, "-hide_banner", "-loglevel", "error",
                "-show_entries", "format_tags", "-of", "default=noprint_wrappers=1",
                file.absolutePath,
            ),
        )
        val tags = HashMap<String, String>()
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("TAG:")) continue
            val separator = trimmed.indexOf('=')
            if (separator <= 4) continue
            tags[trimmed.substring(4, separator).lowercase()] = trimmed.substring(separator + 1)
        }
        return tags
    }

    private fun run(command: List<String>) {
        capture(command)
    }

    private fun capture(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        assertTrue(finished, "${command.first()} did not finish within two minutes")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed:\n$output")
        return output
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private companion object {
        val METADATA = listOf(
            "title" to "Björk 日本語",
            "artist" to "Ólafur Arnalds",
            "album_artist" to "Oracle Album Artist",
            "album" to "Oracle Album",
            "date" to "2026",
            "genre" to "Ambient",
            "comment" to "Oracle comment",
            "composer" to "Oracle Composer",
            "lyrics" to "Oracle lyrics",
            "grouping" to "Oracle Grouping",
            "copyright" to "Oracle Copyright",
            "track" to "3/12",
            "disc" to "1/2",
            "compilation" to "1",
            "description" to "Oracle description",
            "synopsis" to "Oracle synopsis",
        )

        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-mp4-tag-oracle").also { it.mkdirs() }

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

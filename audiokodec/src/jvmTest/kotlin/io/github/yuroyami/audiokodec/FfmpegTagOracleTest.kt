package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
import org.junit.Assume.assumeTrue

/**
 * Cross-checks the tag reader and writer against ffmpeg, on real MP3 files.
 *
 * Tags written by our own writer and read by our own reader prove only that
 * the two agree. These tests write with ffmpeg and read with AudioKodec, then
 * write with AudioKodec and read with ffprobe, so both directions are checked
 * against somebody else's idea of the format.
 *
 * Every test skips itself when ffmpeg is missing.
 */
class FfmpegTagOracleTest {

    private val ffmpeg: String? by lazy { findOnPath("ffmpeg") }
    private val ffprobe: String? by lazy { findOnPath("ffprobe") }

    @Test
    fun softwareAndEncodingCreditsHaveTheSameMeaningAcrossContainers() {
        val ffmpeg = ffmpeg
        val ffprobe = ffprobe
        assumeTrue("ffmpeg and ffprobe are required for encoding metadata", ffmpeg != null && ffprobe != null)
        for ((extension, codec) in listOf("mp3" to "libmp3lame", "flac" to "flac", "wav" to "pcm_s16le")) {
            val file = temp("encoder-meaning.$extension")
            run(listOf(ffmpeg!!, "-y", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=0.1", "-c:a", codec, "-metadata", "encoded_by=Some Person", file.path))
            val original = file.readBytes()
            val reference = probeTags(ffprobe!!, file)
            val tags = AudioKodec.readTags(original)
            assertEquals(reference["encoder"], tags[TagField.ENCODER], extension)
            assertEquals("Some Person", tags[TagField.ENCODED_BY], extension)
            assertEquals(emptyList(), tags.all(TagField.ENCODING_SETTINGS), extension)
            val edited = tags.edit {
                set(TagField.ENCODER, "Custom Codec 2.0")
                set(TagField.ENCODED_BY, "Another Person")
                set(TagField.ENCODING_SETTINGS, "quality=7")
            }
            val options = if (extension == "wav") {
                TagWriteOptions(formats = setOf(TagFormat.RIFF_INFO, TagFormat.ID3V2_4), padding = 0)
            } else TagWriteOptions(padding = 0)
            val written = AudioKodec.writeTags(original, edited, options)
            file.writeBytes(written)
            val after = probeTags(ffprobe, file)
            assertEquals("Custom Codec 2.0", after["encoder"], extension)
            assertEquals("Another Person", after["encoded_by"] ?: after["encodedby"], extension)
            assertEquals("quality=7", after["encodersettings"], extension)
            assertEquals(edited.fields, AudioKodec.readTags(written).fields)
            when (extension) {
                "mp3" -> {
                    val beforeAt = tags.raw.blocks.first().size.toInt()
                    val afterAt = AudioKodec.readTags(written).raw.blocks.first().size.toInt()
                    assertContentEquals(original.copyOfRange(beforeAt, original.size), written.copyOfRange(afterAt, written.size))
                }
                "flac" -> assertContentEquals(TagStressFixtures.flacAudio(original), TagStressFixtures.flacAudio(written))
                else -> assertContentEquals(EncoderTagFixtures.waveAudio(original), EncoderTagFixtures.waveAudio(written))
            }
        }
    }

    @Test
    fun audioKodecReadsTheTagsFfmpegWrites() {
        val ffmpeg = ffmpeg ?: return skip()
        val file = temp("written-by-ffmpeg.mp3")
        makeMp3(
            ffmpeg, file,
            "title" to "Oracle Title",
            "artist" to "Oracle Artist",
            "album" to "Oracle Album",
            "album_artist" to "Oracle Album Artist",
            "track" to "5/9",
            "disc" to "1/2",
            "date" to "2026",
            "genre" to "Ambient",
            "composer" to "Oracle Composer",
            "comment" to "Oracle comment",
            "publisher" to "Oracle Records",
        )

        val tags = AudioKodec.readTags(file.readBytes())
        assertEquals("Oracle Title", tags[TagField.TITLE])
        assertEquals("Oracle Artist", tags[TagField.ARTIST])
        assertEquals("Oracle Album", tags[TagField.ALBUM])
        assertEquals("Oracle Album Artist", tags[TagField.ALBUM_ARTIST])
        assertEquals("5", tags[TagField.TRACK_NUMBER])
        assertEquals("9", tags[TagField.TRACK_TOTAL])
        assertEquals("1", tags[TagField.DISC_NUMBER])
        assertEquals("2", tags[TagField.DISC_TOTAL])
        assertEquals("2026", tags[TagField.DATE])
        assertEquals("Ambient", tags[TagField.GENRE])
        assertEquals("Oracle Composer", tags[TagField.COMPOSER])
        assertEquals("Oracle comment", tags[TagField.COMMENT])
        assertEquals("Oracle Records", tags[TagField.PUBLISHER])
        assertTrue(
            tags.raw.formats.any { it == TagFormat.ID3V2_4 || it == TagFormat.ID3V2_3 },
            "ffmpeg writes an ID3v2 block, so one should have been found",
        )
    }

    @Test
    fun textOutsideLatin1SurvivesTheTripThroughFfmpeg() {
        val ffmpeg = ffmpeg ?: return skip()
        val file = temp("unicode-by-ffmpeg.mp3")
        makeMp3(
            ffmpeg, file,
            "title" to "Björk 日本語",
            "artist" to "Ólafur Arnalds",
        )
        val tags = AudioKodec.readTags(file.readBytes())
        assertEquals("Björk 日本語", tags[TagField.TITLE])
        assertEquals("Ólafur Arnalds", tags[TagField.ARTIST])
    }

    @Test
    fun ffprobeReadsTheTagsAudioKodecWrites() {
        val ffmpeg = ffmpeg ?: return skip()
        val ffprobe = ffprobe ?: return skip()
        val plain = temp("untagged.mp3")
        makeMp3(ffmpeg, plain)

        for (version in intArrayOf(3, 4)) {
            val tagged = temp("tagged-by-audiokodec-v$version.mp3")
            tagged.writeBytes(
                AudioKodec.writeTags(
                    plain.readBytes(),
                    AudioKodec.readTags(plain.readBytes()).edit {
                        set(TagField.TITLE, "AudioKodec Title")
                        set(TagField.ARTIST, "AudioKodec Artist")
                        set(TagField.ALBUM, "AudioKodec Album")
                        set(TagField.TRACK_NUMBER, 4)
                        set(TagField.TRACK_TOTAL, 11)
                        set(TagField.GENRE, "Ambient")
                        set(TagField.DATE, "2026")
                        set(TagField.COMMENT, "Round trip")
                    },
                    TagWriteOptions(id3Version = version),
                ),
            )

            val reported = probeTags(ffprobe, tagged)
            assertEquals("AudioKodec Title", reported["title"], "ID3v2.$version title")
            assertEquals("AudioKodec Artist", reported["artist"], "ID3v2.$version artist")
            assertEquals("AudioKodec Album", reported["album"], "ID3v2.$version album")
            assertEquals("4/11", reported["track"], "ID3v2.$version track")
            assertEquals("Ambient", reported["genre"], "ID3v2.$version genre")
            assertEquals("Round trip", reported["comment"], "ID3v2.$version comment")
        }
    }

    @Test
    fun ffprobeReadsTextOutsideLatin1ThatAudioKodecWrote() {
        val ffmpeg = ffmpeg ?: return skip()
        val ffprobe = ffprobe ?: return skip()
        val plain = temp("untagged-unicode.mp3")
        makeMp3(ffmpeg, plain)

        for (version in intArrayOf(3, 4)) {
            val tagged = temp("unicode-by-audiokodec-v$version.mp3")
            tagged.writeBytes(
                AudioKodec.writeTags(
                    plain.readBytes(),
                    TagBundleOf(TagField.TITLE to "Sigur Rós 日本語", TagField.ARTIST to "Björk"),
                    TagWriteOptions(id3Version = version),
                ),
            )
            val reported = probeTags(ffprobe, tagged)
            assertEquals("Sigur Rós 日本語", reported["title"], "ID3v2.$version")
            assertEquals("Björk", reported["artist"], "ID3v2.$version")
        }
    }

    @Test
    fun taggingAnMp3LeavesEveryAudioByteWhereItWas() {
        val ffmpeg = ffmpeg ?: return skip()
        val plain = temp("audio-preserved.mp3")
        makeMp3(ffmpeg, plain, "title" to "Before")

        val before = decodeWithFfmpeg(ffmpeg, plain)
        val tagged = temp("audio-preserved-tagged.mp3")
        tagged.writeBytes(
            AudioKodec.writeTags(
                plain.readBytes(),
                TagBundleOf(
                    TagField.TITLE to "After, with a much longer title than before",
                    TagField.COMMENT to "x".repeat(4000),
                ),
            ),
        )
        val after = decodeWithFfmpeg(ffmpeg, tagged)

        // Decoding the same MPEG frames has to give the same samples. If the
        // writer had cut into the audio, this is where it would show.
        assertEquals(before.size, after.size, "the audio length changed")
        for (i in before.indices) {
            if (before[i] != after[i]) {
                throw AssertionError("sample $i changed from ${before[i]} to ${after[i]}")
            }
        }
    }

    @Test
    fun coverArtWrittenByFfmpegIsFoundAndKept() {
        val ffmpeg = ffmpeg ?: return skip()
        val cover = temp("cover.png")
        run(
            listOf(
                ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=red:s=64x64:d=1",
                "-frames:v", "1", cover.absolutePath,
            ),
        )
        val audio = temp("with-cover-source.mp3")
        makeMp3(ffmpeg, audio, "title" to "Has art")
        val withCover = temp("with-cover.mp3")
        run(
            listOf(
                ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-i", audio.absolutePath, "-i", cover.absolutePath,
                "-map", "0:a", "-map", "1:v", "-c", "copy",
                "-id3v2_version", "3",
                "-metadata:s:v", "title=Album cover",
                "-metadata:s:v", "comment=Cover (front)",
                withCover.absolutePath,
            ),
        )

        val tags = AudioKodec.readTags(withCover.readBytes())
        val picture = assertNotNull(tags.cover, "the cover ffmpeg attached was not found")
        assertEquals("image/png", picture.mimeType)
        assertTrue(picture.bytes.size > 100, "the picture is suspiciously small")
        assertEquals(0x89, picture.bytes[0].toInt() and 0xFF, "the bytes should be a PNG")

        // And it survives a rewrite.
        val rewritten = AudioKodec.writeTags(
            withCover.readBytes(),
            tags.edit { set(TagField.TITLE, "Still has art") },
        )
        val again = AudioKodec.readTags(rewritten)
        assertEquals("Still has art", again[TagField.TITLE])
        val keptPicture = assertNotNull(again.cover, "the cover was lost in the rewrite")
        assertEquals(picture.bytes.size, keptPicture.bytes.size)
    }

    @Test
    fun anId3v1BlockWrittenByFfmpegIsReadAndUpdated() {
        val ffmpeg = ffmpeg ?: return skip()
        val file = temp("with-id3v1.mp3")
        run(
            listOf(
                ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=0.4",
                "-write_id3v1", "1",
                "-metadata", "title=Has both",
                "-metadata", "artist=Both Artist",
                file.absolutePath,
            ),
        )
        val tags = AudioKodec.readTags(file.readBytes())
        assertTrue(
            tags.raw.blocks.any { it.format == TagFormat.ID3V1 },
            "ffmpeg was asked for an ID3v1 block and one should have been found",
        )
        assertEquals("Has both", tags[TagField.TITLE])

        val updated = AudioKodec.writeTags(
            file.readBytes(),
            tags.edit { set(TagField.TITLE, "Updated") },
        )
        val readBack = AudioKodec.readTags(updated)
        val v1 = readBack.raw.blocksOf(TagFormat.ID3V1).firstOrNull()
        assertNotNull(v1, "the ID3v1 block should have been kept")
        assertEquals("Updated", v1.entries.first { it.key == "TITLE" }.text)
    }

    @Test
    fun ffprobeReadsTheRiffInfoAudioKodecWritesIntoAWave() {
        val ffprobe = ffprobe ?: return skip()
        val format = AudioFormat(44_100, 1)
        val file = temp("tagged.wav")
        file.writeBytes(
            AudioKodec.writeTags(
                AudioKodec.encode(
                    TestSignals.quantise(TestSignals.sine(4_410, 1), 16),
                    format,
                    EncodeTarget.wav(16),
                ),
                TagBundleOf(
                    TagField.TITLE to "Wave Title",
                    TagField.ARTIST to "Wave Artist",
                    TagField.COMMENT to "Wave comment",
                ),
            ),
        )
        val reported = probeTags(ffprobe, file)
        assertEquals("Wave Title", reported["title"])
        assertEquals("Wave Artist", reported["artist"])
        assertEquals("Wave comment", reported["comment"])
    }

    // Running the tools

    private fun makeMp3(ffmpeg: String, file: File, vararg metadata: Pair<String, String>) {
        val args = mutableListOf(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=0.4",
        )
        for ((key, value) in metadata) {
            args += listOf("-metadata", "$key=$value")
        }
        args += listOf("-codec:a", "libmp3lame", "-b:a", "64k", file.absolutePath)
        run(args)
        assertTrue(file.length() > 0, "ffmpeg produced no MP3")
    }

    /** Reads the tags ffprobe reports, lowercased by key. */
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

    private fun decodeWithFfmpeg(ffmpeg: String, file: File): FloatArray {
        val raw = temp(file.name + ".f32")
        raw.delete()
        run(
            listOf(
                ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-i", file.absolutePath,
                "-f", "f32le", "-acodec", "pcm_f32le", raw.absolutePath,
            ),
        )
        val bytes = raw.readBytes()
        val buffer = java.nio.ByteBuffer.wrap(bytes)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun run(command: List<String>) {
        capture(command)
    }

    private fun capture(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        assertTrue(finished, "${command.first()} did not finish within two minutes")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed:\n$output")
        return output
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private fun skip() {
        org.junit.Assume.assumeTrue("ffmpeg is not on the path, so the tag oracle comparison did not run", false)
    }

    private companion object {
        val workDirectory: File = File(
            System.getProperty("java.io.tmpdir"), "audiokodec-tag-oracle",
        ).also { it.mkdirs() }

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

/** A bundle from a handful of fields, which is all these tests need. */
@Suppress("FunctionName")
internal fun TagBundleOf(
    vararg fields: Pair<TagField, String>,
): io.github.yuroyami.audiokodec.tag.TagBundle =
    io.github.yuroyami.audiokodec.tag.TagBundle(
        fields.associate { it.first to listOf(it.second) },
    )

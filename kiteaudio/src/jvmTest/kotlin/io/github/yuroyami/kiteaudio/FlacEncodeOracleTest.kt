package io.github.yuroyami.kiteaudio

import io.github.yuroyami.kiteaudio.tag.TagBundle
import io.github.yuroyami.kiteaudio.tag.TagField
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the FLAC encoder against the reference decoder.
 *
 * Two things have to be true of a lossless encoder, and they are different
 * claims. The file has to be valid, which `flac --test` answers by checking
 * every frame's checksums and the stream's MD5. And it has to hold the audio
 * that went in, which `flac --decode` answers by giving the samples back.
 *
 * Every test skips itself when the `flac` binary is missing.
 */
class FlacEncodeOracleTest {

    private val flac: String? by lazy { findOnPath("flac") }

    @Test
    fun theReferenceDecoderAcceptsWhatKiteAudioWrites() {
        val flac = flac ?: return skip()
        for (depth in intArrayOf(8, 16, 24)) {
            for (channels in intArrayOf(1, 2, 6)) {
                for (effort in intArrayOf(0, 5, 8)) {
                    val what = "$depth-bit, $channels channels, effort $effort"
                    val frames = 9_000
                    val original = TestSignals.quantise(
                        TestSignals.sine(frames, channels, amplitude = 0.8f), depth,
                    )
                    val file = temp("written_${depth}_${channels}_$effort.flac")
                    file.writeBytes(
                        KiteAudio.encode(
                            original,
                            AudioFormat(44_100, channels),
                            EncodeTarget.flac(depth, effort),
                        ),
                    )
                    // The reference decoder verifies both frame checksums and
                    // the stream MD5, so a pass here means the file is valid
                    // and the MD5 we wrote describes the audio we wrote.
                    run(listOf(flac, "--silent", "--test", file.absolutePath), what)
                }
            }
        }
    }

    @Test
    fun theReferenceDecoderGivesBackTheSamplesThatWentIn() {
        val flac = flac ?: return skip()
        for (depth in intArrayOf(8, 16, 24)) {
            for (channels in intArrayOf(1, 2)) {
                val what = "$depth-bit, $channels channels"
                val frames = 7_000
                val original = TestSignals.quantise(
                    TestSignals.sine(frames, channels, amplitude = 0.85f), depth,
                )
                val encoded = temp("roundtrip_${depth}_$channels.flac")
                encoded.writeBytes(
                    KiteAudio.encode(
                        original, AudioFormat(44_100, channels), EncodeTarget.flac(depth, 5),
                    ),
                )
                val back = temp("roundtrip_${depth}_$channels.wav")
                back.delete()
                run(
                    listOf(
                        flac, "--silent", "--force", "--decode",
                        "-o", back.absolutePath, encoded.absolutePath,
                    ),
                    what,
                )
                val decoded = KiteAudio.decode(back.readBytes())
                assertEquals(frames, decoded.frames, "$what: frame count")
                TestSignals.assertExact(original, decoded.samples, what)
            }
        }
    }

    @Test
    fun whatKiteAudioWritesItCanReadBackItself() {
        // No oracle needed for this one, and it is worth having on every
        // target rather than only where the reference decoder is installed.
        for (depth in intArrayOf(8, 16, 24)) {
            for (channels in intArrayOf(1, 2, 6)) {
                val what = "$depth-bit, $channels channels"
                val original = TestSignals.quantise(
                    TestSignals.sine(5_000, channels, amplitude = 0.7f), depth,
                )
                val encoded = KiteAudio.encode(
                    original, AudioFormat(48_000, channels), EncodeTarget.flac(depth, 5),
                )
                val decoded = KiteAudio.decode(encoded)
                assertEquals(5_000, decoded.frames, "$what: frame count")
                TestSignals.assertExact(original, decoded.samples, what)
                assertEquals(true, KiteAudio.verifyFlacMd5(encoded), "$what: our own MD5")
            }
        }
    }

    @Test
    fun theEncoderActuallyCompresses() {
        // A sine wave is what a predictor handles best, so this is a floor
        // rather than a boast. If a change makes the encoder fall back to
        // storing samples whole, this is what notices.
        val frames = 20_000
        val original = TestSignals.quantise(TestSignals.sine(frames, 2), 16)
        val raw = frames * 2 * 2
        for (effort in intArrayOf(0, 5, 8)) {
            val encoded = KiteAudio.encode(
                original, AudioFormat(44_100, 2), EncodeTarget.flac(16, effort),
            )
            val ratio = encoded.size.toDouble() / raw
            assertTrue(
                ratio < 0.75,
                "effort $effort compressed to ${(ratio * 100).toInt()}% of raw, which is too little",
            )
        }
    }

    @Test
    fun higherEffortIsNotLargerThanLowerEffort() {
        val original = TestSignals.quantise(TestSignals.sine(20_000, 2, amplitude = 0.9f), 16)
        val cheap = KiteAudio.encode(original, AudioFormat(44_100, 2), EncodeTarget.flac(16, 0))
        val dear = KiteAudio.encode(original, AudioFormat(44_100, 2), EncodeTarget.flac(16, 8))
        assertTrue(
            dear.size <= cheap.size,
            "effort 8 produced ${dear.size} bytes where effort 0 produced ${cheap.size}",
        )
    }

    @Test
    fun silenceAndFullScaleAreBothWrittenCorrectly() {
        val flac = flac ?: return skip()
        val cases = mapOf(
            "silence" to FloatArray(8_000),
            "square" to FloatArray(8_000) { if ((it / 50) % 2 == 0) 0.99f else -0.99f },
            "alternating" to FloatArray(8_000) { if (it % 2 == 0) 1f else -1f },
        )
        for ((name, samples) in cases) {
            val original = TestSignals.quantise(samples, 16)
            val encoded = temp("$name-written.flac")
            encoded.writeBytes(
                KiteAudio.encode(original, AudioFormat(44_100, 1), EncodeTarget.flac(16, 8)),
            )
            run(listOf(flac, "--silent", "--test", encoded.absolutePath), name)
            TestSignals.assertExact(original, KiteAudio.decode(encoded.readBytes()).samples, name)
        }
    }

    @Test
    fun aVeryShortFileIsStillValid() {
        val flac = flac ?: return skip()
        // A file shorter than one block exercises the explicit block size in
        // the frame header, which the common sizes never reach.
        for (frames in intArrayOf(1, 2, 17, 191, 193, 4_095, 4_097)) {
            val original = TestSignals.quantise(TestSignals.sine(frames, 1), 16)
            val encoded = temp("short-$frames.flac")
            encoded.writeBytes(
                KiteAudio.encode(original, AudioFormat(44_100, 1), EncodeTarget.flac(16, 5)),
            )
            run(listOf(flac, "--silent", "--test", encoded.absolutePath), "$frames frames")
            val decoded = KiteAudio.decode(encoded.readBytes())
            assertEquals(frames, decoded.frames, "$frames frames: count")
            TestSignals.assertExact(original, decoded.samples, "$frames frames")
        }
    }

    @Test
    fun anOddSampleRateSurvives() {
        val flac = flac ?: return skip()
        // Rates outside the coded list go in the frame header explicitly.
        for (rate in intArrayOf(8_000, 11_025, 37_800, 44_100, 64_000, 192_000)) {
            val original = TestSignals.quantise(TestSignals.sine(5_000, 1), 16)
            val encoded = temp("rate-$rate.flac")
            encoded.writeBytes(
                KiteAudio.encode(original, AudioFormat(rate, 1), EncodeTarget.flac(16, 5)),
            )
            run(listOf(flac, "--silent", "--test", encoded.absolutePath), "$rate Hz")
            assertEquals(rate, KiteAudio.probe(encoded.readBytes()).format.sampleRate, "$rate Hz")
        }
    }

    @Test
    fun tagsWrittenWithTheAudioAreReadBackByBothSides() {
        val flac = flac ?: return skip()
        val tags = TagBundle(
            mapOf(
                TagField.TITLE to listOf("Encoded Title"),
                TagField.ARTIST to listOf("Encoded Artist"),
                TagField.ALBUM to listOf("Encoded Album"),
                TagField.TRACK_NUMBER to listOf("2"),
                TagField.GENRE to listOf("Ambient"),
            ),
        )
        val encoded = temp("with-tags.flac")
        encoded.writeBytes(
            KiteAudio.encode(
                TestSignals.quantise(TestSignals.sine(4_000, 2), 16),
                AudioFormat(44_100, 2),
                EncodeTarget.flac(16, 5),
                tags,
            ),
        )
        run(listOf(flac, "--silent", "--test", encoded.absolutePath), "tagged file")

        val read = KiteAudio.readTags(encoded.readBytes())
        assertEquals("Encoded Title", read[TagField.TITLE])
        assertEquals("Encoded Artist", read[TagField.ARTIST])
        assertEquals("Encoded Album", read[TagField.ALBUM])
        assertEquals("2", read[TagField.TRACK_NUMBER])
        assertEquals("Ambient", read[TagField.GENRE])

        // And the reference metadata tool agrees the comments are there.
        val metaflac = findOnPath("metaflac")
        if (metaflac != null) {
            val listed = capture(
                listOf(metaflac, "--list", "--block-type=VORBIS_COMMENT", encoded.absolutePath),
            )
            assertTrue(
                listed.contains("Encoded Title"),
                "the reference tool did not see the title:\n$listed",
            )
            assertTrue(
                listed.contains("Encoded Artist"),
                "the reference tool did not see the artist:\n$listed",
            )
        }
    }

    @Test
    fun editingTheTagsOfAFlacFileLeavesTheAudioUntouched() {
        val flac = flac ?: return skip()
        val original = TestSignals.quantise(TestSignals.sine(9_000, 2), 16)
        val first = temp("edit-before.flac")
        first.writeBytes(
            KiteAudio.encode(
                original,
                AudioFormat(44_100, 2),
                EncodeTarget.flac(16, 5),
                TagBundle(mapOf(TagField.TITLE to listOf("Before"))),
            ),
        )

        val edited = KiteAudio.writeTags(
            first.readBytes(),
            KiteAudio.readTags(first.readBytes()).edit {
                set(TagField.TITLE, "After, which is a good deal longer than before")
                set(TagField.ARTIST, "Someone")
            },
        )
        val second = temp("edit-after.flac")
        second.writeBytes(edited)

        // The file is still valid, which means the stream MD5 still describes
        // the audio: proof that the audio came through untouched.
        run(listOf(flac, "--silent", "--test", second.absolutePath), "after editing")
        assertEquals(true, KiteAudio.verifyFlacMd5(edited), "the stored MD5 after editing")
        TestSignals.assertExact(
            original, KiteAudio.decode(edited).samples, "audio changed while tagging",
        )

        val read = KiteAudio.readTags(edited)
        assertEquals("After, which is a good deal longer than before", read[TagField.TITLE])
        assertEquals("Someone", read[TagField.ARTIST])
    }

    @Test
    fun aPictureCanBeAddedToAFlacFile() {
        val flac = flac ?: return skip()
        val image = ByteArray(3_000) { ((it * 17) and 0xFF).toByte() }
        image[0] = 0x89.toByte()
        image[1] = 'P'.code.toByte()
        val plain = KiteAudio.encode(
            TestSignals.quantise(TestSignals.sine(3_000, 1), 16),
            AudioFormat(44_100, 1),
            EncodeTarget.flac(16, 5),
        )
        val withPicture = KiteAudio.writeTags(
            plain,
            KiteAudio.readTags(plain).edit {
                set(TagField.TITLE, "Has art")
                addPicture(
                    io.github.yuroyami.kiteaudio.tag.TagPicture(
                        mimeType = "image/png",
                        description = "Cover",
                        bytes = image,
                        width = 64,
                        height = 64,
                    ),
                )
            },
        )
        val file = temp("flac-with-picture.flac")
        file.writeBytes(withPicture)
        run(listOf(flac, "--silent", "--test", file.absolutePath), "with a picture")

        val read = KiteAudio.readTags(withPicture)
        assertEquals("Has art", read[TagField.TITLE])
        val cover = read.cover
        assertTrue(cover != null, "the picture was not stored")
        assertEquals("image/png", cover.mimeType)
        assertEquals(64, cover.width)
        assertEquals(image.size, cover.bytes.size)
    }

    private fun run(command: List<String>, what: String) {
        val output = capture(command, what)
        if (output.isNotBlank() && output.contains("ERROR", ignoreCase = true)) {
            throw AssertionError("$what: ${command.first()} reported a problem:\n$output")
        }
    }

    private fun capture(command: List<String>, what: String = ""): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(180, TimeUnit.SECONDS)
        assertTrue(finished, "${command.first()} did not finish within three minutes")
        assertEquals(
            0, process.exitValue(),
            "$what: ${command.joinToString(" ")} failed:\n$output",
        )
        return output
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private fun skip() {
        println("the flac binary is not on the path, so the encoder was not checked against it")
    }

    private companion object {
        val workDirectory: File = File(
            System.getProperty("java.io.tmpdir"), "kiteaudio-flac-encode",
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

package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-checks the FLAC decoder against the reference encoder.
 *
 * FLAC is lossless, so there is a stronger check available here than for any
 * lossy codec: the samples that come out have to be the samples that went in,
 * bit for bit, and the file itself carries an MD5 of them that the reference
 * encoder computed. Both are checked.
 *
 * Every test skips itself when the `flac` binary is missing.
 */
class FlacOracleTest {

    private val flac: String? by lazy { findOnPath("flac") }

    @Test
    fun oversizedFrameSizeHintsPreserveTheReferenceDecodedAudio() {
        val flac = flac ?: return skip()
        val bytes = FlacReadCostFixtures.file(0xFF_FFFF)
        val encoded = temp("oversized-frame-hint.flac")
        encoded.writeBytes(bytes)
        val reference = temp("oversized-frame-hint.wav")
        run(listOf(flac, "--silent", "--force", "--decode", "-o", reference.path, encoded.path))
        val expected = AudioKodec.decode(reference.readBytes()).samples
        assertEquals(128_000, expected.size)
        assertContentEquals(expected, AudioKodec.decode(bytes).samples)
    }

    @Test
    fun unusableSeekTablesKeepReferenceEncodedSamplesAndTags() {
        val flac = flac ?: return skip()
        val samples = TestSignals.quantise(TestSignals.sine(9_000, 1), 16)
        val wav = temp("seek-table.wav")
        wav.writeBytes(AudioKodec.encode(samples, AudioFormat(44_100, 1), EncodeTarget.wav(16)))
        val encoded = temp("seek-table.flac")
        run(listOf(flac, "--silent", "--force", "--no-padding", "--no-seektable",
            "-T", "TITLE=Reference title", "-o", encoded.path, wav.path))
        for (bytes in FlacSeekTableFixtures.invalidTables(encoded.readBytes())) {
            for (tolerant in listOf(false, true)) {
                val options = DecodeOptions(tolerant = tolerant)
                TestSignals.assertExact(samples, AudioKodec.decode(bytes, options).samples)
                assertEquals(true, AudioKodec.verifyFlacMd5(bytes, options))
                assertEquals("Reference title", AudioKodec.readTags(bytes, options)[TagField.TITLE])
            }
        }
    }

    @Test
    fun tolerantContinuityFailureKeepsTheReferenceEncodedPrefix() {
        val flac = flac ?: return org.junit.Assume.assumeTrue("flac is required", false)
        val source = temp("continuity.wav")
        source.writeBytes(AudioKodec.encode(FlacContinuityFixtures.samples(), AudioFormat(44_100, 1), EncodeTarget.wav(16)))
        val encoded = temp("continuity.flac")
        run(listOf(flac, "--silent", "--force", "--no-padding", "--no-seektable", "--blocksize=4096",
            "-o", encoded.path, source.path))
        val valid = encoded.readBytes()
        assertEquals(true, AudioKodec.verifyFlacMd5(valid))
        assertContentEquals(FlacContinuityFixtures.samples(), AudioKodec.decode(valid).samples)
        for (bytes in listOf(
            FlacContinuityFixtures.renumber(valid, 1, 3),
            FlacContinuityFixtures.withTotal(valid, 6000),
        )) {
            assertFailsWith<CorruptStreamException> { AudioKodec.decode(bytes) }
            val recovered = AudioKodec.decode(bytes, DecodeOptions(tolerant = true))
            assertContentEquals(FlacContinuityFixtures.samples().copyOf(4096), recovered.samples)
            AudioKodec.open(bytes, DecodeOptions(tolerant = true)).use { decoder ->
                val block = (decoder as IntPcmAccess).nextIntFrame()
                assertNotNull(block)
                assertContentEquals(IntArray(4096) { 4096 }, block.samples)
                assertNull(decoder.nextIntFrame())
            }
        }
    }

    @Test
    fun decodingIsBitExactAtEveryDepthAndChannelCount() {
        val flac = flac ?: return skip()
        for (depth in intArrayOf(8, 16, 24)) {
            for (channels in intArrayOf(1, 2, 6)) {
                val what = "$depth-bit, $channels channels"
                val frames = 5_000
                val original = TestSignals.quantise(
                    TestSignals.sine(frames, channels, amplitude = 0.75f), depth,
                )
                val wav = temp("source_${depth}_$channels.wav")
                wav.writeBytes(
                    AudioKodec.encode(original, AudioFormat(44_100, channels), EncodeTarget.wav(depth)),
                )
                val encoded = temp("source_${depth}_$channels.flac")
                encoded.delete()
                run(listOf(flac, "--silent", "--force", "-5", "-o", encoded.absolutePath, wav.absolutePath))

                val decoded = AudioKodec.decode(encoded.readBytes())
                assertEquals(frames, decoded.frames, "$what: frame count")
                assertEquals(channels, decoded.format.channels, "$what: channels")
                assertEquals(44_100, decoded.format.sampleRate, "$what: sample rate")
                TestSignals.assertExact(original, decoded.samples, what)

                val info = AudioKodec.probe(encoded.readBytes())
                assertEquals(AudioContainer.FLAC, info.container, "$what: container")
                assertEquals(AudioCodec.FLAC, info.codec, "$what: codec")
                assertEquals(depth, info.bitDepth, "$what: bit depth")
                assertEquals(frames.toLong(), info.durationFrames, "$what: duration")
            }
        }
    }

    @Test
    fun thirtyTwoBitDecorrelatedStereoUsesWideIntermediates() {
        val flac = flac ?: return skip()
        val frames = 5_000
        val left = IntArray(frames) { index ->
            when (index and 3) {
                0 -> Int.MAX_VALUE - index
                1 -> Int.MIN_VALUE + index
                else -> index * 791_791
            }
        }
        // Keep the channels highly correlated so the reference encoder picks
        // a side-channel assignment, while exercising both integer extremes.
        val right = IntArray(frames) { index -> left[index] - (index and 15) }
        val raw = ByteArray(frames * 2 * 4)
        var at = 0
        for (frame in 0 until frames) {
            for (sample in intArrayOf(left[frame], right[frame])) {
                raw[at++] = sample.toByte()
                raw[at++] = (sample ushr 8).toByte()
                raw[at++] = (sample ushr 16).toByte()
                raw[at++] = (sample ushr 24).toByte()
            }
        }
        val source = temp("wide-side.raw")
        source.writeBytes(raw)
        val encoded = temp("wide-side.flac")
        encoded.delete()
        run(
            listOf(
                flac, "--silent", "--force", "--force-raw-format",
                "--endian=little", "--sign=signed", "--channels=2",
                "--bps=32", "--sample-rate=44100",
                "-8", "-o", encoded.absolutePath, source.absolutePath,
            ),
        )

        val bytes = encoded.readBytes()
        val dataOffset = AudioKodec.probe(bytes).dataOffset.toInt()
        val channelAssignment = (bytes[dataOffset + 3].toInt() ushr 4) and 0x0F
        assertTrue(
            channelAssignment in 8..10,
            "reference encoder used channel assignment $channelAssignment, not decorrelation",
        )

        AudioKodec.open(bytes).use { decoder ->
            val integers = assertIs<IntPcmAccess>(decoder)
            var position = 0
            while (true) {
                val block = integers.nextIntFrame() ?: break
                for (frame in 0 until block.frameCount) {
                    assertEquals(left[position], block.samples[frame * 2], "left $position")
                    assertEquals(right[position], block.samples[frame * 2 + 1], "right $position")
                    position++
                }
            }
            assertEquals(frames, position)
        }
        assertEquals(true, AudioKodec.verifyFlacMd5(bytes))
    }

    @Test
    fun theIntegerViewMatchesWhatWentIn() {
        val flac = flac ?: return skip()
        // 24-bit is where the float path would start losing bits if the
        // integer view were not there, so it is the one worth proving.
        val frames = 3_000
        val values = IntArray(frames) { ((it * 3_571) % 16_777_216) - 8_388_608 }
        val asFloat = Pcm.floatFromInt(values, 24)
        val wav = temp("integer-view.wav")
        wav.writeBytes(AudioKodec.encode(asFloat, AudioFormat(48_000, 1), EncodeTarget.wav(24)))
        val encoded = temp("integer-view.flac")
        encoded.delete()
        run(listOf(flac, "--silent", "--force", "-8", "-o", encoded.absolutePath, wav.absolutePath))

        AudioKodec.open(encoded.readBytes()).use { decoder ->
            val access = assertIs<IntPcmAccess>(decoder, "a FLAC decoder exposes integers")
            assertEquals(24, access.bitDepth)
            var read = 0
            while (true) {
                val block = access.nextIntFrame() ?: break
                for (i in 0 until block.frameCount) {
                    assertEquals(values[read + i], block.samples[i], "sample ${read + i}")
                }
                read += block.frameCount
            }
            assertEquals(frames, read)
        }
    }

    @Test
    fun theMd5TheEncoderStoredMatchesWhatWeDecode() {
        val flac = flac ?: return skip()
        // This is the strongest check available: the value was computed by the
        // reference encoder over the original samples, and it is compared
        // against a hash of what this decoder produced.
        for (level in intArrayOf(0, 5, 8)) {
            for (channels in intArrayOf(1, 2)) {
                val what = "level $level, $channels channels"
                val original = TestSignals.quantise(
                    TestSignals.sine(8_000, channels, amplitude = 0.9f), 16,
                )
                val wav = temp("md5_${level}_$channels.wav")
                wav.writeBytes(
                    AudioKodec.encode(original, AudioFormat(44_100, channels), EncodeTarget.wav(16)),
                )
                val encoded = temp("md5_${level}_$channels.flac")
                encoded.delete()
                run(
                    listOf(
                        flac, "--silent", "--force", "-$level",
                        "-o", encoded.absolutePath, wav.absolutePath,
                    ),
                )
                val verified = AudioKodec.verifyFlacMd5(encoded.readBytes())
                assertEquals(true, verified, "$what: the stored MD5 did not match")
            }
        }
    }

    @Test
    fun aFileWithNoSeekTableStillSeeksAccurately() {
        val flac = flac ?: return skip()
        val frames = 20_000
        val original = TestSignals.quantise(TestSignals.sine(frames, 2), 16)
        val wav = temp("seek.wav")
        wav.writeBytes(AudioKodec.encode(original, AudioFormat(44_100, 2), EncodeTarget.wav(16)))

        for (seekTable in listOf(true, false)) {
            val encoded = temp("seek-${if (seekTable) "with" else "without"}.flac")
            encoded.delete()
            val args = mutableListOf(flac, "--silent", "--force", "-5")
            if (!seekTable) args += "--no-seektable"
            args += listOf("-o", encoded.absolutePath, wav.absolutePath)
            run(args)

            val what = if (seekTable) "with a seek table" else "without a seek table"
            AudioKodec.open(encoded.readBytes()).use { decoder ->
                for (target in longArrayOf(0, 1, 4_095, 4_096, 9_999, 19_999)) {
                    val reached = decoder.seek(target)
                    assertEquals(target, reached, "$what: seek to $target")
                    val block = assertNotNull(decoder.nextFrame(), "$what: nothing after $target")
                    assertEquals(target, block.positionFrames, "$what: reported position")
                    for (channel in 0 until 2) {
                        assertEquals(
                            original[target.toInt() * 2 + channel],
                            block[0, channel],
                            "$what: channel $channel of frame $target",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun everythingAfterASeekMatchesTheSameStretchOfAFullDecode() {
        val flac = flac ?: return skip()
        val frames = 15_000
        val original = TestSignals.quantise(TestSignals.sine(frames, 1), 16)
        val wav = temp("seek-tail.wav")
        wav.writeBytes(AudioKodec.encode(original, AudioFormat(44_100, 1), EncodeTarget.wav(16)))
        val encoded = temp("seek-tail.flac")
        encoded.delete()
        run(listOf(flac, "--silent", "--force", "-5", "-o", encoded.absolutePath, wav.absolutePath))

        val target = 7_777
        AudioKodec.open(encoded.readBytes()).use { decoder ->
            decoder.seek(target.toLong())
            val rest = ArrayList<Float>()
            while (true) {
                val block = decoder.nextFrame() ?: break
                for (i in 0 until block.sampleCount) rest.add(block.samples[i])
            }
            assertEquals(frames - target, rest.size)
            for (i in rest.indices) {
                assertEquals(original[target + i], rest[i], "sample ${target + i} after seeking")
            }
        }
    }

    @Test
    fun theCompressionLevelDoesNotChangeWhatComesOut() {
        val flac = flac ?: return skip()
        // Every level uses a different mixture of predictors and partitioning,
        // so running all of them exercises paths a single level would not.
        val original = TestSignals.quantise(TestSignals.sine(6_000, 2, amplitude = 0.95f), 16)
        val wav = temp("levels.wav")
        wav.writeBytes(AudioKodec.encode(original, AudioFormat(44_100, 2), EncodeTarget.wav(16)))
        for (level in 0..8) {
            val encoded = temp("level$level.flac")
            encoded.delete()
            run(
                listOf(
                    flac, "--silent", "--force", "-$level",
                    "-o", encoded.absolutePath, wav.absolutePath,
                ),
            )
            val decoded = AudioKodec.decode(encoded.readBytes())
            TestSignals.assertExact(original, decoded.samples, "compression level $level")
        }
    }

    @Test
    fun silenceAndFullScaleBothDecodeCorrectly() {
        val flac = flac ?: return skip()
        // Silence becomes constant subframes and full scale stresses the
        // widest residuals, so the two extremes exercise opposite paths.
        val cases = mapOf(
            "silence" to FloatArray(4_000),
            "extremes" to FloatArray(4_000) { if (it % 2 == 0) 1f else -1f },
        )
        for ((name, samples) in cases) {
            val quantised = TestSignals.quantise(samples, 16)
            val wav = temp("$name.wav")
            wav.writeBytes(AudioKodec.encode(quantised, AudioFormat(44_100, 1), EncodeTarget.wav(16)))
            val encoded = temp("$name.flac")
            encoded.delete()
            run(listOf(flac, "--silent", "--force", "-8", "-o", encoded.absolutePath, wav.absolutePath))
            val decoded = AudioKodec.decode(encoded.readBytes())
            TestSignals.assertExact(quantised, decoded.samples, name)
            assertEquals(true, AudioKodec.verifyFlacMd5(encoded.readBytes()), "$name: MD5")
        }
    }

    @Test
    fun theVorbisCommentsFlacWritesAreRead() {
        val flac = flac ?: return skip()
        val wav = temp("tagged-source.wav")
        wav.writeBytes(
            AudioKodec.encode(
                TestSignals.quantise(TestSignals.sine(2_000, 2), 16),
                AudioFormat(44_100, 2),
                EncodeTarget.wav(16),
            ),
        )
        val encoded = temp("tagged.flac")
        encoded.delete()
        run(
            listOf(
                flac, "--silent", "--force", "-5",
                "--tag=TITLE=FLAC Title",
                "--tag=ARTIST=FLAC Artist",
                "--tag=ALBUM=FLAC Album",
                "--tag=ALBUMARTIST=FLAC Album Artist",
                "--tag=TRACKNUMBER=6",
                "--tag=TRACKTOTAL=14",
                "--tag=DATE=2026-07-26",
                "--tag=GENRE=Ambient",
                "--tag=REPLAYGAIN_TRACK_GAIN=-7.35 dB",
                "-o", encoded.absolutePath, wav.absolutePath,
            ),
        )

        val tags = AudioKodec.readTags(encoded.readBytes())
        assertEquals("FLAC Title", tags[TagField.TITLE])
        assertEquals("FLAC Artist", tags[TagField.ARTIST])
        assertEquals("FLAC Album", tags[TagField.ALBUM])
        assertEquals("FLAC Album Artist", tags[TagField.ALBUM_ARTIST])
        assertEquals("6", tags[TagField.TRACK_NUMBER])
        assertEquals("14", tags[TagField.TRACK_TOTAL])
        assertEquals("2026-07-26", tags[TagField.DATE])
        assertEquals("Ambient", tags[TagField.GENRE])
        assertEquals(TagFormat.VORBIS_COMMENT, tags.raw.blocks.first().format)
        val gain = assertNotNull(tags.replayGain)
        assertEquals(-7.35, gain.trackGainDb!!, 0.005)
        assertTrue(!gain.isR128)
    }

    @Test
    fun aPictureInAFlacMetadataBlockIsFound() {
        val flac = flac ?: return skip()
        val ffmpeg = findOnPath("ffmpeg") ?: return skip()
        val cover = temp("flac-cover.png")
        run(
            listOf(
                ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=blue:s=48x48:d=1",
                "-frames:v", "1", cover.absolutePath,
            ),
        )
        val wav = temp("picture-source.wav")
        wav.writeBytes(
            AudioKodec.encode(
                TestSignals.quantise(TestSignals.sine(1_000, 1), 16),
                AudioFormat(44_100, 1),
                EncodeTarget.wav(16),
            ),
        )
        val encoded = temp("with-picture.flac")
        encoded.delete()
        run(
            listOf(
                flac, "--silent", "--force", "-5",
                "--picture=${cover.absolutePath}",
                "-o", encoded.absolutePath, wav.absolutePath,
            ),
        )

        val tags = AudioKodec.readTags(encoded.readBytes())
        val picture = assertNotNull(tags.cover, "the picture block was not found")
        assertEquals("image/png", picture.mimeType)
        assertEquals(48, picture.width)
        assertEquals(48, picture.height)
        assertEquals(0x89, picture.bytes[0].toInt() and 0xFF, "the bytes should be a PNG")
    }

    // Running the tool

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(180, TimeUnit.SECONDS)
        assertTrue(finished, "${command.first()} did not finish within three minutes")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed:\n$output")
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private fun skip() {
        org.junit.Assume.assumeTrue("the flac binary is not on the path, so the FLAC oracle comparison did not run", false)
    }

    private companion object {
        val workDirectory: File = File(
            System.getProperty("java.io.tmpdir"), "audiokodec-flac-oracle",
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

package io.github.yuroyami.audiokodec

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cross-checks the CAF reader against Apple's own converter.
 *
 * CAF is Apple's format, so `afconvert` is the authority on what one looks
 * like. It ships with macOS, which is where these tests run; elsewhere they
 * skip themselves.
 */
class CafOracleTest {

    private val afconvert: String? by lazy { findOnPath("afconvert") }
    private val ffmpeg: String? by lazy { findOnPath("ffmpeg") }

    @Test
    fun ffmpegAlacWithAPartialFinalPacketDecodesAndSeeksExactly() {
        val ffmpeg = ffmpeg ?: return org.junit.Assume.assumeTrue("ffmpeg is required", false)
        for (channels in 1..2) {
            for (depth in intArrayOf(16, 24)) {
                val what = "CAF ALAC, $channels channels, $depth-bit"
                val frames = 26_460
                val samples = TestSignals.quantise(TestSignals.sine(frames, channels), depth)
                val input = temp("alac_${channels}_$depth.wav")
                input.writeBytes(AudioKodec.encode(samples, AudioFormat(44_100, channels), EncodeTarget.wav(depth)))
                val caf = temp("alac_${channels}_$depth.caf")
                run(listOf(ffmpeg, "-y", "-v", "error", "-i", input.path, "-c:a", "alac", caf.path), what)
                val reference = temp("alac_${channels}_$depth.f32")
                run(listOf(ffmpeg, "-y", "-v", "error", "-i", caf.path, "-f", "f32le", reference.path), what)
                val buffer = ByteBuffer.wrap(reference.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val expected = FloatArray(buffer.remaining()).also { buffer.get(it) }
                assertEquals(frames * channels, expected.size, what)
                val bytes = caf.readBytes()
                for (tolerant in listOf(false, true)) {
                    val options = DecodeOptions(tolerant = tolerant)
                    val decoded = AudioKodec.decode(bytes, options)
                    assertEquals(frames, decoded.frames, what)
                    // Every 16-bit and 24-bit PCM value is exact in Float32.
                    assertContentEquals(expected, decoded.samples, what)
                    AudioKodec.open(bytes, options).use { decoder ->
                        assertEquals(frames.toLong(), decoder.durationFrames, what)
                        assertEquals(frames.toLong(), decoder.info.durationFrames, what)
                        for (target in longArrayOf(0, 4_095, 4_096, 24_575, 24_576, 26_459, 26_460)) {
                            assertEquals(target, decoder.seek(target), what)
                            val block = decoder.nextFrame()
                            if (target == frames.toLong()) {
                                assertEquals(null, block, what)
                            } else {
                                assertNotNull(block, what)
                                assertEquals(target, block.positionFrames, what)
                                for (i in 0 until block.frameCount * channels) {
                                    assertEquals(expected[target.toInt() * channels + i], block.samples[i], what)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun theUncompressedCafFormatsDecodeExactly() {
        val afconvert = afconvert ?: return skip()
        // Every one of these is lossless, so the samples have to come back
        // unchanged. The pairs are the CAF data format and the depth it means.
        val cases = listOf(
            Triple("BEI16", 16, false),
            Triple("LEI16", 16, false),
            Triple("BEI24", 24, false),
            Triple("LEI24", 24, false),
            Triple("BEI32", 32, false),
            Triple("BEF32", 32, true),
        )
        for ((dataFormat, depth, isFloat) in cases) {
            for (channels in intArrayOf(1, 2)) {
                val what = "$dataFormat, $channels channels"
                val frames = 4_000
                val original = if (isFloat) {
                    TestSignals.sine(frames, channels, amplitude = 0.75f)
                } else {
                    TestSignals.quantise(
                        TestSignals.sine(frames, channels, amplitude = 0.75f),
                        // 32-bit integers do not survive the float path, so the
                        // source is put on the 24-bit grid and stored wider.
                        if (depth == 32) 24 else depth,
                    )
                }
                val wav = temp("caf_source_${dataFormat}_$channels.wav")
                wav.writeBytes(
                    AudioKodec.encode(
                        original,
                        AudioFormat(44_100, channels),
                        if (isFloat) EncodeTarget.wavFloat(32) else EncodeTarget.wav(
                            if (depth == 32) 24 else depth,
                        ),
                    ),
                )
                val caf = temp("caf_${dataFormat}_$channels.caf")
                caf.delete()
                run(
                    listOf(
                        afconvert, "-f", "caff", "-d", dataFormat,
                        wav.absolutePath, caf.absolutePath,
                    ),
                    what,
                )

                val info = AudioKodec.probe(caf.readBytes())
                assertEquals(AudioContainer.CAF, info.container, "$what: container")
                assertEquals(44_100, info.format.sampleRate, "$what: sample rate")
                assertEquals(channels, info.format.channels, "$what: channels")

                val decoded = AudioKodec.decode(caf.readBytes())
                assertEquals(frames, decoded.frames, "$what: frame count")
                TestSignals.assertExact(original, decoded.samples, what)
            }
        }
    }

    @Test
    fun theCompandingLawsInCafDecodeWithinTheirAccuracy() {
        val afconvert = afconvert ?: return skip()
        val original = TestSignals.sine(2_000, 1, sampleRate = 8_000, amplitude = 0.7f)
        val wav = temp("caf_law_source.wav")
        wav.writeBytes(AudioKodec.encode(original, AudioFormat(8_000, 1), EncodeTarget.wav(16)))
        for (dataFormat in listOf("ulaw", "alaw")) {
            val caf = temp("caf_$dataFormat.caf")
            caf.delete()
            run(
                listOf(
                    afconvert, "-f", "caff", "-d", dataFormat,
                    wav.absolutePath, caf.absolutePath,
                ),
                dataFormat,
            )
            val decoded = AudioKodec.decode(caf.readBytes())
            assertEquals(
                if (dataFormat == "ulaw") AudioCodec.MU_LAW else AudioCodec.A_LAW,
                decoded.info.codec,
                dataFormat,
            )
            val error = TestSignals.rmsError(original, decoded.samples)
            assertTrue(error < 0.01f, "$dataFormat had an RMS error of $error")
        }
    }

    @Test
    fun quickTimeAdpcmInsideCafDecodes() {
        val afconvert = afconvert ?: return skip()
        val original = TestSignals.sine(4_096, 1, amplitude = 0.6f)
        val wav = temp("caf_ima_source.wav")
        wav.writeBytes(AudioKodec.encode(original, AudioFormat(44_100, 1), EncodeTarget.wav(16)))
        val caf = temp("caf_ima4.caf")
        caf.delete()
        run(
            listOf(afconvert, "-f", "caff", "-d", "ima4", wav.absolutePath, caf.absolutePath),
            "ima4",
        )
        val info = AudioKodec.probe(caf.readBytes())
        assertEquals(AudioCodec.IMA_ADPCM, info.codec)
        val decoded = AudioKodec.decode(caf.readBytes())
        // The frame count is rounded up to whole packets of 64 frames, so the
        // decoded length can exceed the source by less than one packet.
        assertTrue(
            decoded.frames >= 4_096 && decoded.frames < 4_096 + 64,
            "decoded ${decoded.frames} frames of 4096",
        )
        val error = TestSignals.rmsError(
            original, decoded.samples.copyOf(4_096),
        )
        assertTrue(error < 0.03f, "IMA ADPCM in CAF had an RMS error of $error")
    }

    @Test
    fun ffmpegAndAudioKodecAgreeOnACafFile() {
        val ffmpeg = ffmpeg ?: return skip()
        val original = TestSignals.quantise(TestSignals.sine(3_000, 2), 16)
        val wav = temp("caf_ffmpeg_source.wav")
        wav.writeBytes(AudioKodec.encode(original, AudioFormat(48_000, 2), EncodeTarget.wav(16)))
        val caf = temp("caf_ffmpeg.caf")
        caf.delete()
        run(
            listOf(
                ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-i", wav.absolutePath, "-c:a", "pcm_s16be", "-f", "caf", caf.absolutePath,
            ),
            "ffmpeg CAF",
        )
        val decoded = AudioKodec.decode(caf.readBytes())
        TestSignals.assertExact(original, decoded.samples, "a CAF file written by ffmpeg")
    }

    @Test
    fun ffmpegImaFilesPreserveSamplesLengthAndSeeking() {
        val ffmpeg = ffmpeg ?: return skip()
        for (channels in 1..2) {
            val what = "ffmpeg IMA CAF, $channels channels"
            val samples = FloatArray(26_460 * channels) { i ->
                (0.5 * kotlin.math.sin(2 * Math.PI * (440 + (i % channels) * 110) * (i / channels) / 44_100)).toFloat()
            }
            val wav = temp("caf_ffmpeg_ima_source_$channels.wav")
            wav.writeBytes(AudioKodec.encode(samples, AudioFormat(44_100, channels), EncodeTarget.wav(16)))
            val caf = temp("caf_ffmpeg_ima_$channels.caf")
            run(listOf(ffmpeg, "-v", "error", "-y", "-i", wav.path, "-c:a", "adpcm_ima_qt", "-f", "caf", caf.path), what)
            val raw = temp("caf_ffmpeg_ima_$channels.f32")
            run(listOf(ffmpeg, "-v", "error", "-y", "-i", caf.path, "-f", "f32le", raw.path), what)
            val floats = ByteBuffer.wrap(raw.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val reference = FloatArray(floats.remaining()).also { floats.get(it) }
            val bytes = caf.readBytes()
            assertEquals(4, ByteBuffer.wrap(bytes, 48, 4).order(ByteOrder.BIG_ENDIAN).int, "$what: coded width")
            val decoded = AudioKodec.decode(bytes)
            assertEquals(26_496, decoded.frames, what)
            assertEquals(reference.size, decoded.samples.size, what)
            assertEquals(26_496L, AudioKodec.probe(bytes).durationFrames, what)
            var worst = 0f
            for (i in reference.indices) worst = maxOf(worst, abs(reference[i] - decoded.samples[i]))
            // Keep the specification's separate integer shifts. The existing
            // IMA oracle bound covers ffmpeg's accumulated rounding difference.
            assertTrue(worst <= 128f / 32_768, "$what: worst sample difference $worst")
            val unspecified = bytes.copyOf().also { for (at in 48..51) it[at] = 0 }
            assertContentEquals(decoded.samples, AudioKodec.decode(unspecified).samples, what)
            AudioKodec.open(bytes).use { decoder ->
                assertEquals(26_496L, decoder.durationFrames, what)
                for (target in longArrayOf(0, 1, 63, 64, 127, 128, 8_191, 8_192, 26_495, 26_496)) {
                    assertEquals(target, decoder.seek(target), what)
                    val frame = decoder.nextFrame()
                    if (target == 26_496L) {
                        assertEquals(null, frame)
                    } else {
                        assertNotNull(frame)
                        assertEquals(target, frame.positionFrames, what)
                        for (channel in 0 until channels) {
                            assertEquals(decoded.samples[target.toInt() * channels + channel], frame.samples[channel], what)
                        }
                    }
                }
            }
        }
    }

    private fun run(command: List<String>, what: String) {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        assertTrue(finished, "${command.first()} did not finish within two minutes")
        assertEquals(
            0, process.exitValue(),
            "$what: ${command.joinToString(" ")} failed:\n$output",
        )
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private fun skip() {
        org.junit.Assume.assumeTrue("the CAF oracle tool is not on the path, so the comparison did not run", false)
    }

    private companion object {
        val workDirectory: File = File(
            System.getProperty("java.io.tmpdir"), "audiokodec-caf-oracle",
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

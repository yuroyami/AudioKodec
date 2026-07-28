package io.github.yuroyami.kiteaudio

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
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
                    KiteAudio.encode(
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

                val info = KiteAudio.probe(caf.readBytes())
                assertEquals(AudioContainer.CAF, info.container, "$what: container")
                assertEquals(44_100, info.format.sampleRate, "$what: sample rate")
                assertEquals(channels, info.format.channels, "$what: channels")

                val decoded = KiteAudio.decode(caf.readBytes())
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
        wav.writeBytes(KiteAudio.encode(original, AudioFormat(8_000, 1), EncodeTarget.wav(16)))
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
            val decoded = KiteAudio.decode(caf.readBytes())
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
        wav.writeBytes(KiteAudio.encode(original, AudioFormat(44_100, 1), EncodeTarget.wav(16)))
        val caf = temp("caf_ima4.caf")
        caf.delete()
        run(
            listOf(afconvert, "-f", "caff", "-d", "ima4", wav.absolutePath, caf.absolutePath),
            "ima4",
        )
        val info = KiteAudio.probe(caf.readBytes())
        assertEquals(AudioCodec.IMA_ADPCM, info.codec)
        val decoded = KiteAudio.decode(caf.readBytes())
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
    fun ffmpegAndKiteAudioAgreeOnACafFile() {
        val ffmpeg = ffmpeg ?: return skip()
        val original = TestSignals.quantise(TestSignals.sine(3_000, 2), 16)
        val wav = temp("caf_ffmpeg_source.wav")
        wav.writeBytes(KiteAudio.encode(original, AudioFormat(48_000, 2), EncodeTarget.wav(16)))
        val caf = temp("caf_ffmpeg.caf")
        caf.delete()
        run(
            listOf(
                ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-i", wav.absolutePath, "-c:a", "pcm_s16be", "-f", "caf", caf.absolutePath,
            ),
            "ffmpeg CAF",
        )
        val decoded = KiteAudio.decode(caf.readBytes())
        TestSignals.assertExact(original, decoded.samples, "a CAF file written by ffmpeg")
    }

    private fun run(command: List<String>, what: String) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
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
        println("the CAF oracle tool is not on the path, so the comparison did not run")
    }

    private companion object {
        val workDirectory: File = File(
            System.getProperty("java.io.tmpdir"), "kiteaudio-caf-oracle",
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

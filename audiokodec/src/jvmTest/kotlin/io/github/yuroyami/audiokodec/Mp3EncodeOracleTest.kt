package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.mpeg.MpegFrameHeader
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks the MP3 encoder's files against ffmpeg, libmpg123 and AudioKodec.
 *
 * The encoder is lossy, so the measure is the one every lossy encoder in this
 * library is held to: libopus's `opus_compare` internal weighted error, from
 * `OpusCompare`, of ffmpeg's float decode against the original, on a seeded
 * music-like signal at 48 kHz in stereo. The bounds were fixed before the
 * encoder was written, from LAME's scores on the same signal: 0.29 at
 * 192 kbit/s and 0.43 at 128 kbit/s, against bounds of 0.45 and 0.60. The
 * error must also not rise as the bitrate does.
 *
 * Every file must decode in ffmpeg's float decoder without a single message
 * at its error level, to exactly as many samples as went in, lined up with
 * the input; and AudioKodec's decoder must agree with ffmpeg's within the
 * 1e-4 that `MpegOracleTest` allows between them on LAME's files. libmpg123,
 * where installed, is a third decoder held to the same.
 *
 * Every comparison skips itself when its tool is missing.
 */
class Mp3EncodeOracleTest {

    private val ffmpeg: String? by lazy { findOnPath("ffmpeg") }
    private val ffprobe: String? by lazy { findOnPath("ffprobe") }

    @Test
    fun musicMeetsTheBoundsAtEveryBitrateAndImprovesWithIt() {
        val ffmpeg = requireTool(ffmpeg)
        val music = Mp3TestSignals.music(48_000, 10.0)
        val reference = OpusCompare.Reference(toShorts(music), 2)
        val errors = ArrayList<Double>()
        for (bitrate in listOf(96, 128, 192, 320)) {
            val start = System.nanoTime()
            val bytes = AudioKodec.encode(music, AudioFormat(48_000, 2), EncodeTarget.mp3(bitrate * 1_000))
            val speed = 10.0 / ((System.nanoTime() - start) / 1e9)
            val file = temp("music_$bitrate.mp3").also { it.writeBytes(bytes) }
            assertFrameSizes(bytes, bitrate, 48_000)
            val decoded = decodeWithFfmpeg(ffmpeg, file, 2)
            assertEquals(music.size, decoded.size, "$bitrate kbit/s: ffmpeg's decoded length")
            val error = OpusCompare.compare(reference, toShorts(decoded), 48_000).error
            errors += error
            println("MP3 CBR $bitrate kbit/s, music: opus_compare error ${"%.3f".format(error)}, " +
                "${bytes.size * 8 / 10_000} kbit/s, encoded at ${"%.1f".format(speed)}x real time")
            assertDecodersAgree(bytes, decoded, "$bitrate kbit/s")
            assertProbe(file, 48_000, 2, music.size / 2)
            when (bitrate) {
                128 -> assertTrue(error <= 0.60, "128 kbit/s: error $error is above 0.60")
                192 -> assertTrue(error <= 0.45, "192 kbit/s: error $error is above 0.45")
            }
        }
        for (i in 1 until errors.size) {
            assertTrue(errors[i] <= errors[i - 1], "the error rose with the bitrate: $errors")
        }
    }

    @Test
    fun variableBitrateAtTheDefaultQualityMeetsItsBound() {
        val ffmpeg = requireTool(ffmpeg)
        val music = Mp3TestSignals.music(48_000, 10.0)
        val reference = OpusCompare.Reference(toShorts(music), 2)
        val start = System.nanoTime()
        val bytes = AudioKodec.encode(music, AudioFormat(48_000, 2), EncodeTarget.mp3Vbr())
        val speed = 10.0 / ((System.nanoTime() - start) / 1e9)
        val file = temp("music_vbr.mp3").also { it.writeBytes(bytes) }
        val decoded = decodeWithFfmpeg(ffmpeg, file, 2)
        assertEquals(music.size, decoded.size, "VBR: ffmpeg's decoded length")
        val error = OpusCompare.compare(reference, toShorts(decoded), 48_000).error
        val kbps = bytes.size * 8 / 10_000.0
        println("MP3 VBR default quality, music: opus_compare error ${"%.3f".format(error)}, " +
            "${"%.1f".format(kbps)} kbit/s, encoded at ${"%.1f".format(speed)}x real time")
        assertDecodersAgree(bytes, decoded, "VBR")
        assertProbe(file, 48_000, 2, music.size / 2)
        assertTrue(error <= 0.45, "VBR: error $error is above 0.45")
        assertTrue(kbps <= 224, "VBR: $kbps kbit/s is above 224")
        val info = AudioKodec.probe(bytes)
        assertTrue(info.isVariableBitrate, "a VBR file must say it is variable bitrate")
    }

    @Test
    fun wholeSinePeriodsComeBackAtExactlyTheirLengthLinedUp() {
        val ffmpeg = requireTool(ffmpeg)
        // Rate, frequency, periods and channels: 100 periods at 44.1 kHz;
        // fewer samples than a frame; exactly one frame; and lengths that are
        // not a multiple of 1152.
        val cases = listOf(
            Triple(44_100, 441.0, 100) to 1, Triple(48_000, 1_000.0, 7) to 2,
            Triple(48_000, 375.0, 9) to 2, Triple(32_000, 250.0, 37) to 1,
            Triple(44_100, 147.0, 32) to 2, Triple(48_000, 480.0, 1_000) to 2,
        )
        for ((shape, channels) in cases) {
            val (rate, frequency, periods) = shape
            val frames = (rate / frequency * periods).roundToInt()
            val input = FloatArray(frames * channels) { i ->
                (0.5 * sin(2 * PI * frequency * (i / channels) / rate)).toFloat()
            }
            for (target in listOf(EncodeTarget.mp3(192_000), EncodeTarget.mp3Vbr())) {
                val what = "$periods periods of $frequency Hz at $rate Hz, $channels ch, $target"
                val bytes = AudioKodec.encode(input, AudioFormat(rate, channels), target)
                val file = temp("sine_${rate}_${frequency.toInt()}_${periods}_${target.hashCode()}.mp3")
                file.writeBytes(bytes)
                val ours = AudioKodec.decode(bytes)
                assertEquals(frames, ours.frames, "$what: AudioKodec's length")
                assertEquals(frames.toLong(), ours.info.durationFrames, "$what: AudioKodec's stated length")
                val theirs = decodeWithFfmpeg(ffmpeg, file, channels)
                assertEquals(input.size, theirs.size, "$what: ffmpeg's length")
                assertDecodersAgree(bytes, theirs, what)
                assertEquals(0, bestLag(input, ours.samples, channels), "$what: lag")
                if (frames >= 2 * 1152) assertProbe(file, rate, channels, frames)
            }
        }
    }

    @Test
    fun aSineKeepsItsLevelAndSilenceStaysSilent() {
        val ffmpeg = requireTool(ffmpeg)
        val frames = 96_000
        val sine = FloatArray(frames * 2) { i -> (0.5 * sin(2 * PI * 1_000.0 * (i / 2) / 48_000)).toFloat() }
        val file = temp("sine_1k.mp3").also { it.writeBytes(AudioKodec.encode(sine, AudioFormat(48_000, 2), EncodeTarget.mp3(192_000))) }
        for ((name, decoded) in listOf("AudioKodec" to AudioKodec.decode(file.readBytes()).samples, "ffmpeg" to decodeWithFfmpeg(ffmpeg, file, 2))) {
            var signal = 0.0
            var noise = 0.0
            for (i in sine.indices) {
                signal += sine[i].toDouble() * sine[i]
                val d = decoded[i] - sine[i].toDouble()
                noise += d * d
            }
            val snr = 10 * log10(signal / noise)
            println("MP3 192 kbit/s, 1 kHz at -6 dBFS through $name: SNR ${"%.1f".format(snr)} dB")
            assertTrue(snr >= 30, "$name: SNR $snr dB is below 30")
        }

        for (target in listOf(EncodeTarget.mp3(128_000), EncodeTarget.mp3Vbr())) {
            val silence = FloatArray(48_000 * 2)
            val bytes = AudioKodec.encode(silence, AudioFormat(48_000, 2), target)
            val quiet = temp("silence.mp3").also { it.writeBytes(bytes) }
            for (decoded in listOf(AudioKodec.decode(bytes).samples, decodeWithFfmpeg(ffmpeg, quiet, 2))) {
                assertEquals(silence.size, decoded.size)
                val peak = decoded.maxOf { abs(it) }
                assertTrue(peak < 1e-4f, "$target: silence decoded with a peak of $peak")
            }
        }
    }

    @Test
    fun otherRatesAndMonoDecodeEverywhere() {
        val ffmpeg = requireTool(ffmpeg)
        for ((rate, channels) in listOf(44_100 to 2, 32_000 to 2, 48_000 to 1, 44_100 to 1, 32_000 to 1)) {
            val stereo = Mp3TestSignals.music(rate, 3.0, seed = rate.toLong())
            val input = if (channels == 2) stereo else FloatArray(stereo.size / 2) { (stereo[2 * it] + stereo[2 * it + 1]) * 0.5f }
            for (target in listOf(EncodeTarget.mp3(128_000), EncodeTarget.mp3(64_000), EncodeTarget.mp3Vbr(), EncodeTarget.mp3(160_000, jointStereo = false))) {
                val what = "$rate Hz, $channels ch, $target"
                val bytes = AudioKodec.encode(input, AudioFormat(rate, channels), target)
                val file = temp("rates_${rate}_${channels}_${target.hashCode()}.mp3").also { it.writeBytes(bytes) }
                if (target.bitrateMode == BitrateMode.CONSTANT) {
                    assertFrameSizes(bytes, target.bitrate / 1_000, rate)
                }
                val decoded = decodeWithFfmpeg(ffmpeg, file, channels)
                assertEquals(input.size, decoded.size, "$what: ffmpeg's length")
                assertDecodersAgree(bytes, decoded, what)
                assertProbe(file, rate, channels, input.size / channels)
                var signal = 0.0
                var noise = 0.0
                for (i in input.indices) {
                    signal += input[i].toDouble() * input[i]
                    val d = decoded[i] - input[i].toDouble()
                    noise += d * d
                }
                val snr = 10 * log10(signal / noise)
                println("MP3 $what: SNR ${"%.1f".format(snr)} dB, ${bytes.size * 8 / 3_000} kbit/s")
                assertTrue(snr > 6, "$what: SNR $snr dB")
            }
        }
    }

    @Test
    fun libmpg123DecodesTheFilesToTheSameSamples() {
        val python = System.getenv("AUDIOKODEC_MUTAGEN_PYTHON") ?: findOnPath("python3")
        assumeTrue("python3 is required to call libmpg123", python != null)
        val music = Mp3TestSignals.music(44_100, 4.0, seed = 3)
        for (target in listOf(EncodeTarget.mp3(192_000), EncodeTarget.mp3Vbr())) {
            val bytes = AudioKodec.encode(music, AudioFormat(44_100, 2), target)
            val file = temp("mpg123_${target.hashCode()}.mp3").also { it.writeBytes(bytes) }
            val output = temp("mpg123_${target.hashCode()}.f32")
            val process = ProcessBuilder(listOf(python!!, "-c", MPG123_BRIDGE, file.path, output.path, "44100", "2"))
                .redirectErrorStream(true).oracleStart()
            val text = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "libmpg123 did not finish")
            if (process.exitValue() == 2 && System.getenv("AUDIOKODEC_MPG123_LIBRARY") == null) {
                assumeTrue("libmpg123 is required for the third decoder", false)
            }
            assertEquals(0, process.exitValue(), text)
            val length = text.trim().split(' ')[2].toLong()
            assertEquals(music.size / 2L, length, "$target: libmpg123's gapless length")
            val theirs = readFloats(output)
            assertEquals(music.size, theirs.size, "$target: libmpg123's decoded length")
            assertDecodersAgree(bytes, theirs, "$target through libmpg123")
        }
    }

    // Helpers.

    /** The lag, within eight samples either way, at which [decoded] best matches [input]. */
    private fun bestLag(input: FloatArray, decoded: FloatArray, channels: Int): Int {
        var best = 0
        var bestError = Double.MAX_VALUE
        val frames = input.size / channels
        for (lag in -8..8) {
            var sum = 0.0
            for (f in 8 until frames - 8) {
                for (c in 0 until channels) {
                    val d = decoded[(f + lag) * channels + c] - input[f * channels + c].toDouble()
                    sum += d * d
                }
            }
            if (sum < bestError) {
                bestError = sum
                best = lag
            }
        }
        return best
    }

    /** Every audio frame after the Info frame is the stated bitrate's size, give or take its padding byte. */
    private fun assertFrameSizes(bytes: ByteArray, bitrate: Int, rate: Int) {
        var offset = 0
        var index = 0
        while (offset + 4 <= bytes.size) {
            val header = MpegFrameHeader.parse(bytes, offset) ?: throw AssertionError("no frame header at $offset")
            if (index > 0) {
                assertEquals(bitrate, header.bitrateKbps, "frame $index's bitrate")
                assertEquals(144_000 * bitrate / rate + if (header.padded) 1 else 0, header.frameBytes, "frame $index's size")
            }
            offset += header.frameBytes
            index++
        }
        assertEquals(bytes.size, offset, "the frames run to the end of the file")
    }

    private fun assertDecodersAgree(bytes: ByteArray, reference: FloatArray, what: String) {
        val ours = AudioKodec.decode(bytes).samples
        assertEquals(reference.size, ours.size, "$what: AudioKodec's length")
        assertTrue(ours.all { it.isFinite() }, "$what: AudioKodec decoded a NaN")
        var worst = 0f
        for (i in ours.indices) worst = maxOf(worst, abs(ours[i] - reference[i]))
        assertTrue(worst <= 1e-4f, "$what: AudioKodec and the reference differ by $worst")
    }

    /**
     * ffprobe must state the rate and channels, and a duration that is the
     * whole frames the stream holds: like LAME's own files, the container
     * duration ffprobe gives counts the encoder delay and padding, which the
     * decode then trims. The start time it states is the delay to trim.
     */
    private fun assertProbe(file: File, rate: Int, channels: Int, frames: Int) {
        val ffprobe = ffprobe ?: return
        val process = ProcessBuilder(listOf(ffprobe, "-v", "error", "-show_entries",
            "stream=sample_rate,channels,start_time:format=duration", "-of", "default=noprint_wrappers=1", file.path))
            .redirectErrorStream(true).oracleStart()
        val text = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        val fields = text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(rate.toString(), fields["sample_rate"], "${file.name}: ffprobe's rate")
        assertEquals(channels.toString(), fields["channels"], "${file.name}: ffprobe's channels")
        val mpegFrames = (frames + 1_057 + 1_151) / 1_152
        val duration = fields.getValue("duration").toDouble()
        assertEquals(mpegFrames * 1_152.0 / rate, duration, 1e-3, "${file.name}: ffprobe's duration")
        assertEquals(1_057.0 / rate, fields.getValue("start_time").toDouble(), 1e-5, "${file.name}: ffprobe's start")
    }

    /** ffmpeg's float decode, native rate and channels, with nothing at all on its error output. */
    private fun decodeWithFfmpeg(ffmpeg: String, file: File, channels: Int): FloatArray {
        val raw = File(file.path + ".ff.f32")
        val errors = File(file.path + ".ff.txt")
        raw.delete()
        val process = ProcessBuilder(listOf(ffmpeg, "-nostdin", "-y", "-v", "error", "-c:a", "mp3float",
            "-i", file.path, "-f", "f32le", "-acodec", "pcm_f32le", "-ac", "$channels", raw.path))
            .redirectError(errors).redirectOutput(ProcessBuilder.Redirect.DISCARD).oracleStart()
        assertTrue(process.waitFor(180, TimeUnit.SECONDS), "ffmpeg did not finish")
        val messages = errors.readText()
        assertEquals(0, process.exitValue(), messages)
        assertEquals("", messages.trim(), "${file.name}: ffmpeg reported problems")
        return readFloats(raw)
    }

    private fun readFloats(file: File): FloatArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()) { buffer.get() }
    }

    private fun toShorts(samples: FloatArray): ShortArray =
        ShortArray(samples.size) { (samples[it] * 32_768.0).roundToInt().coerceIn(-32_768, 32_767).toShort() }

    private fun requireTool(path: String?): String {
        assumeTrue("ffmpeg is required to decode the files the MP3 encoder writes", path != null)
        return path!!
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private companion object {
        val workDirectory: File = Files.createTempDirectory("audiokodec-mp3-encode-oracle").toFile()

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

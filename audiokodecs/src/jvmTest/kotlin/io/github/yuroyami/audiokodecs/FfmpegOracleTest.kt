package io.github.yuroyami.audiokodecs

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-checks the decoders against ffmpeg.
 *
 * A round-trip test only proves the reader and the writer agree with each
 * other. These tests prove they agree with somebody else, which is what
 * catches a byte order or a scale factor that is wrong in both directions at
 * once.
 *
 * Every test here skips itself when ffmpeg is not on the path. A skipped test
 * reads like a passing one, so continuous integration installs ffmpeg rather
 * than leaving this to chance.
 */
class FfmpegOracleTest {

    private val ffmpeg: String? by lazy { findOnPath("ffmpeg") }

    @Test
    fun ffmpegAndAudioKodecsDecodeTheSameWaveFilesIdentically() {
        val ffmpeg = ffmpeg ?: return skip()
        for (codec in listOf("pcm_u8", "pcm_s16le", "pcm_s24le", "pcm_f32le")) {
            for (channels in intArrayOf(1, 2)) {
                val file = temp("oracle_$codec$channels.wav")
                generate(ffmpeg, file, codec, channels, seconds = 0.25)
                val theirs = decodeWithFfmpeg(ffmpeg, file)
                val ours = AudioKodecs.decode(file.readBytes())
                assertEquals(
                    theirs.size, ours.samples.size,
                    "$codec with $channels channels: sample count",
                )
                TestSignals.assertExact(theirs, ours.samples, "$codec with $channels channels")
            }
        }
    }

    @Test
    fun ffmpegAndAudioKodecsDecodeTheSameAiffAndAuFilesIdentically() {
        val ffmpeg = ffmpeg ?: return skip()
        val cases = listOf(
            Triple("oracle.aiff", "pcm_s16be", 2),
            Triple("oracle24.aiff", "pcm_s24be", 1),
            Triple("oracle.au", "pcm_s16be", 2),
        )
        for ((name, codec, channels) in cases) {
            val file = temp(name)
            generate(ffmpeg, file, codec, channels, seconds = 0.25)
            val theirs = decodeWithFfmpeg(ffmpeg, file)
            val ours = AudioKodecs.decode(file.readBytes())
            TestSignals.assertExact(theirs, ours.samples, "$name as $codec")
        }
    }

    @Test
    fun theTwoG711LawsMatchFfmpegExactly() {
        val ffmpeg = ffmpeg ?: return skip()
        for (codec in listOf("pcm_mulaw", "pcm_alaw")) {
            val file = temp("oracle_$codec.wav")
            generate(ffmpeg, file, codec, channels = 1, seconds = 0.25, sampleRate = 8_000)
            val theirs = decodeWithFfmpeg(ffmpeg, file)
            val ours = AudioKodecs.decode(file.readBytes())
            // Both sides expand the same 256-entry table, so anything other
            // than an exact match means one of the two tables is wrong.
            TestSignals.assertExact(theirs, ours.samples, codec)
        }
    }

    @Test
    fun adpcmDecodingMatchesFfmpegSampleForSample() {
        val ffmpeg = ffmpeg ?: return skip()
        for (codec in listOf("adpcm_ima_wav", "adpcm_ms")) {
            for (channels in intArrayOf(1, 2)) {
                val what = "$codec with $channels channels"
                val file = temp("oracle_$codec$channels.wav")
                generate(ffmpeg, file, codec, channels, seconds = 0.5)
                val theirs = decodeWithFfmpeg(ffmpeg, file)
                val ours = AudioKodecs.decode(file.readBytes())

                // The last ADPCM block is padded with silence, and the 'fact'
                // chunk is where the file states how many frames were real.
                // AudioKodecs stops there; ffmpeg decodes the padding as well.
                // Both decodings are the same audio, so the comparison runs
                // over the part that is genuinely in the file.
                val declared = AudioKodecs.probe(file.readBytes()).durationFrames
                assertEquals(
                    declared, ours.frames.toLong(),
                    "$what: AudioKodecs should stop at the frame count the file declares",
                )
                assertTrue(
                    theirs.size >= ours.samples.size,
                    "$what: ffmpeg returned fewer samples than the file declares",
                )
                assertTrue(
                    theirs.size - ours.samples.size < blockFrames(file) * channels,
                    "$what: the two decoders differ by more than one block of padding",
                )
                compareAdpcm(codec, what, theirs.copyOf(ours.samples.size), ours.samples)
            }
        }
    }

    /**
     * Compares an ADPCM decoding against ffmpeg's.
     *
     * Microsoft ADPCM has to match bit for bit. IMA does not, and the reason
     * is worth knowing. The IMA specification builds the difference by adding
     * three separately truncated shifts of the step size:
     *
     * ```
     * difference = step >> 3
     * if (bit2) difference += step
     * if (bit1) difference += step >> 1
     * if (bit0) difference += step >> 2
     * ```
     *
     * ffmpeg computes `((2 * magnitude + 1) * step) >> 3` instead, and says
     * in its own source that it is replacing the reference implementation's
     * series of jumps with a multiply. The two agree in exact arithmetic and
     * differ by the truncation of each shift, and because the predictor is a
     * running sum, that difference accumulates a little.
     *
     * AudioKodecs follows the specification, which is also what libsndfile and
     * Microsoft's own documentation describe. The measured divergence over
     * half a second is about 44 parts in 32768, which is roughly -57 dBFS.
     * The bound here is loose enough to allow that and far too tight to allow
     * a real defect: a wrong table, nibble order or channel interleave moves
     * samples by thousands of steps, not tens.
     */
    private fun compareAdpcm(codec: String, what: String, theirs: FloatArray, ours: FloatArray) {
        if (codec == "adpcm_ms") {
            TestSignals.assertExact(theirs, ours, what)
            return
        }
        var worst = 0f
        for (i in ours.indices) {
            val difference = abs(theirs[i] - ours[i])
            if (difference > worst) worst = difference
        }
        val worstSteps = worst * 32_768f
        assertTrue(
            worstSteps <= 128f,
            "$what: the decoders differ by $worstSteps steps, which is more than the " +
                "known difference between the IMA reference formula and ffmpeg's multiply",
        )
    }

    /** Frames per block, read back out of the file's own format chunk. */
    private fun blockFrames(file: File): Int {
        val bytes = file.readBytes()
        var i = 12
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.ISO_8859_1)
            val size = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "fmt " && size >= 20) {
                return ByteBuffer.wrap(bytes, i + 8 + 18, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            }
            i += 8 + size + (size and 1)
        }
        return 1
    }

    @Test
    fun ffmpegReadsBackWhatAudioKodecsWrites() {
        val ffmpeg = ffmpeg ?: return skip()
        val format = AudioFormat(44_100, 2)
        val original = TestSignals.quantise(TestSignals.sine(4_000, 2), 16)
        val targets = listOf(
            "wav16.wav" to EncodeTarget.wav(16),
            "wav24.wav" to EncodeTarget.wav(24),
            "wav8.wav" to EncodeTarget.wav(8),
            "wavf32.wav" to EncodeTarget.wavFloat(32),
            "out.aiff" to EncodeTarget.aiff(16),
            "out24.aiff" to EncodeTarget.aiff(24),
            "out.au" to EncodeTarget.au(16),
        )
        for ((name, target) in targets) {
            val quantised = when (target.codec) {
                AudioCodec.PCM_INTEGER -> TestSignals.quantise(original, target.bitDepth)
                else -> original
            }
            val file = temp(name)
            file.writeBytes(AudioKodecs.encode(quantised, format, target))
            val theirs = decodeWithFfmpeg(ffmpeg, file)
            TestSignals.assertExact(quantised, theirs, "$name read back by ffmpeg")
        }
    }

    @Test
    fun ffmpegAcceptsTheAdpcmAudioKodecsWrites() {
        val ffmpeg = ffmpeg ?: return skip()
        val format = AudioFormat(44_100, 1)
        val original = TestSignals.sine(8_000, 1, amplitude = 0.6f)
        for ((name, target, codec) in listOf(
            Triple("ima.wav", EncodeTarget.wavImaAdpcm(512), "adpcm_ima_wav"),
            Triple("ms.wav", EncodeTarget.wavMsAdpcm(512), "adpcm_ms"),
        )) {
            val file = temp(name)
            file.writeBytes(AudioKodecs.encode(original, format, target))
            val theirs = decodeWithFfmpeg(ffmpeg, file)
            val ours = AudioKodecs.decode(file.readBytes())
            assertEquals(8_000, ours.frames, "$name: AudioKodecs should return what it encoded")
            // ffmpeg decodes the padding in the last block as well, so the
            // comparison runs over the frames the file declares. Inside that
            // range the two decoders have to agree exactly.
            assertTrue(
                theirs.size >= ours.samples.size,
                "$name: ffmpeg returned fewer samples than were encoded",
            )
            compareAdpcm(codec, "$name decoded by ffmpeg", theirs.copyOf(ours.samples.size), ours.samples)
            // And the coding has to be good enough to be worth using.
            val error = TestSignals.rmsError(original, ours.samples)
            assertTrue(error < 0.02f, "$name has an RMS error of $error against the source")
        }
    }

    @Test
    fun ffmpegAgreesWithProbeAboutDurationAndRate() {
        val ffmpeg = ffmpeg ?: return skip()
        val file = temp("probe.wav")
        generate(ffmpeg, file, "pcm_s16le", channels = 2, seconds = 1.5, sampleRate = 48_000)
        val info = AudioKodecs.probe(file.readBytes())
        assertEquals(48_000, info.format.sampleRate)
        assertEquals(2, info.format.channels)
        assertEquals(72_000L, info.durationFrames)
        assertTrue(abs(info.durationSeconds!! - 1.5) < 1e-9)
    }

    // Running ffmpeg

    private fun generate(
        ffmpeg: String,
        file: File,
        codec: String,
        channels: Int,
        seconds: Double,
        sampleRate: Int = 44_100,
    ) {
        file.delete()
        // A sine plus noise exercises the codecs harder than a pure tone,
        // which any predictor would follow too easily.
        val filter = "sine=frequency=440:sample_rate=$sampleRate:duration=$seconds"
        val args = mutableListOf(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", filter,
        )
        if (channels > 1) {
            args += listOf("-af", "pan=${channelLayoutName(channels)}|" + panSpec(channels))
        }
        args += listOf("-acodec", codec, file.absolutePath)
        run(args)
        assertTrue(file.length() > 0, "ffmpeg produced no file for $codec")
    }

    private fun channelLayoutName(channels: Int): String = when (channels) {
        1 -> "mono"
        2 -> "stereo"
        6 -> "5.1"
        else -> "${channels}c"
    }

    private fun panSpec(channels: Int): String =
        (0 until channels).joinToString("|") { "c$it=${1.0 / (it + 1)}*c0" }

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
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        assertTrue(finished, "${command.first()} did not finish within two minutes")
        assertEquals(
            0, process.exitValue(),
            "${command.joinToString(" ")} failed:\n$output",
        )
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private fun skip() {
        println("ffmpeg is not on the path, so the oracle comparison did not run")
    }

    private companion object {
        val workDirectory: File = File(
            System.getProperty("java.io.tmpdir"), "audiokodecs-oracle",
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

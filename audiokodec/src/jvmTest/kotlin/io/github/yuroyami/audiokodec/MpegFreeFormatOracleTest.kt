package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.mpeg.MpegFrameHeader
import io.github.yuroyami.audiokodec.internal.mpeg.MpegStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** LAME and libmpg123 are public executable/API oracles; their source is never read. */
class MpegFreeFormatOracleTest {
    @Test
    fun lameFreeFormatMatchesIndependentFloatPcmAtEveryRateAndChannelCount() = inDirectory { directory ->
        for (rate in listOf(48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000)) {
            for (channels in 1..2) {
                val file = encode(directory, rate, channels, "gapless-$rate-$channels")
                val decoded = compare(directory, file, rate, channels)
                assertEquals(rate / 2 + 37, decoded.frames, "each link retains the input's gapless duration")
                assertEquals(freeBitrate(rate) * 1000, decoded.info.bitrate)
                assertEquals(false, decoded.info.isVariableBitrate)
                AudioKodec.open(file.readBytes()).use { decoder ->
                    for (target in listOf(decoded.frames / 2L + 13, 0L, decoded.frames - 1L, 1L)) {
                        assertEquals(target, decoder.seek(target))
                        val frame = assertNotNull(decoder.nextFrame())
                        assertEquals(target, frame.positionFrames)
                        TestSignals.assertClose(decoded.samples.copyOfRange(target.toInt() * channels,
                            target.toInt() * channels + frame.sampleCount), frame.samples.copyOf(frame.sampleCount),
                            1e-4f, "free-format seek at $rate Hz")
                    }
                }
            }
        }
    }

    @Test
    fun missingXingAndProtectedFramesKeepTheirIndependentDurationAndPcm() = inDirectory { directory ->
        for (rate in listOf(48_000, 16_000, 8_000)) {
            for (protected in listOf(false, true)) {
                val file = encode(directory, rate, 2, "unindexed-$rate-$protected", "-t", *if (protected) arrayOf("-p") else emptyArray())
                val decoded = compare(directory, file, rate, 2)
                for (known in listOf(false, true)) {
                    val fromSource = AudioKodec.decode(MpegXingFixtures.CountingSource(file.readBytes(), known))
                    assertContentEquals(decoded.samples, fromSource.samples)
                    assertEquals(decoded.info.durationFrames, fromSource.info.durationFrames)
                }
                if (protected) {
                    val damaged = file.readBytes().also { it[decoded.info.dataOffset.toInt() + 4] =
                        (it[decoded.info.dataOffset.toInt() + 4].toInt() xor 1).toByte() }
                    assertFailsWith<CorruptStreamException> { AudioKodec.decode(damaged) }
                    assertContentEquals(decoded.samples, AudioKodec.decode(damaged, DecodeOptions(tolerant = true)).samples)
                }
            }
        }
    }

    @Test
    fun nonzeroLayerOneAndLayerTwoFreeFormatAgreeWithTheReference() = inDirectory { directory ->
        val one = File(directory, "layer-one.mp1").also { it.writeBytes(MpegMp4Fixtures.layerOne(12, 44_100)) }
        val freeOne = File(directory, "layer-one-free.mp1").also { it.writeBytes(makeFree(one.readBytes())) }
        compare(directory, freeOne, 44_100, 1)
        for (rate in listOf(48_000, 44_100, 32_000, 24_000, 22_050, 16_000)) for (channels in 1..2) {
            val input = signal(directory, rate, channels, "layer-two-$rate-$channels")
            val file = File(directory, "layer-two-$rate-$channels.mp2")
            // These declared-rate encodings use Table 3-B.2a in MPEG-1.
            // Free format uses that table irrespective of the measured rate;
            // simply relabelling a low-rate table C/D file would be invalid.
            run(listOf(tool("ffmpeg"), "-v", "error", "-y", "-i", input.path, "-c:a", "mp2",
                "-b:a", "${64 * channels}k", file.path), directory)
            val free = File(directory, "layer-two-free-$rate-$channels.mp2").also { it.writeBytes(makeFree(file.readBytes())) }
            compare(directory, free, rate, channels)
            if (rate == 16_000) {
                val extension = File(directory, "layer-two-extension-$channels.mp2")
                    .also { it.writeBytes(makeFree(file.readBytes(), halfRate = true)) }
                compare(directory, extension, 8_000, channels)
            }
        }
    }

    @Test
    fun packetIndexedFreeFormatPreservesGaplessSamplesAndSeeking() = inDirectory { directory ->
        val file = encode(directory, 32_000, 2, "packet-free")
        val bytes = file.readBytes()
        val options = DecodeOptions.DEFAULT
        val parsed = MpegStream.parse(SourceReader(bytes.asAudioSource(), options), options)
        var at = parsed.audioOffset.toInt()
        val packets = ArrayList<ByteArray>()
        while (at < parsed.audioEnd) {
            val header = assertNotNull(MpegFrameHeader.parse(bytes, at, parsed.first.freeFormatFrameBytes))
            packets += bytes.copyOfRange(at, at + header.frameBytes)
            at += header.frameBytes
        }
        val expected = compare(directory, file, 32_000, 2)
        val mp4 = MpegMp4Fixtures.file(packets, edit = parsed.startTrim.toLong() to expected.frames.toLong())
        val actual = AudioKodec.decode(mp4)
        assertEquals(expected.frames, actual.frames)
        assertContentEquals(expected.samples, actual.samples)
        AudioKodec.open(mp4, DecodeOptions(allowSlowSeek = false)).use { decoder ->
            for (target in listOf(expected.frames - 500L, 17L, expected.frames / 2L)) {
                assertEquals(target, decoder.seek(target))
                val frame = assertNotNull(decoder.nextFrame())
                assertEquals(target, frame.positionFrames)
                TestSignals.assertClose(expected.samples.copyOfRange(target.toInt() * 2, target.toInt() * 2 + frame.sampleCount),
                    frame.samples.copyOf(frame.sampleCount), 1e-4f, "free-format MP4 seek")
            }
        }
    }

    @Test
    fun aFreeFormatXingTableSupportsFarSeeksWithoutSlowScanning() = inDirectory { directory ->
        val file = encode(directory, 32_000, 2, "long-free", frames = 96_037)
        val expected = compare(directory, file, 32_000, 2)
        AudioKodec.open(file.readBytes(), DecodeOptions(allowSlowSeek = false)).use { decoder ->
            for (target in listOf(70_019L, 15_137L, 95_701L, 0L, 51_003L)) {
                assertEquals(target, decoder.seek(target))
                val frame = assertNotNull(decoder.nextFrame())
                assertEquals(target, frame.positionFrames)
                TestSignals.assertClose(expected.samples.copyOfRange(target.toInt() * 2,
                    target.toInt() * 2 + frame.sampleCount), frame.samples.copyOf(frame.sampleCount),
                    1e-4f, "indexed free-format seek at $target")
            }
        }
    }

    private fun compare(directory: File, file: File, rate: Int, channels: Int): DecodedAudio {
        val python = System.getenv("AUDIOKODEC_MUTAGEN_PYTHON") ?: tool("python3")
        val output = File(directory, file.name + ".f32")
        val result = run(listOf(python, "-c", MPG123_BRIDGE, file.path, output.path, rate.toString(), channels.toString()),
            directory, allowMissing = true)
        if (result.first == 2 && System.getenv("AUDIOKODEC_MPG123_LIBRARY") == null) {
            assumeTrue("libmpg123 is required for free-format MPEG comparisons", false)
        }
        assertEquals(0, result.first, "${file.name}: ${result.second}")
        val fields = result.second.trim().split(' ').map { it.toLong() }
        assertEquals(rate.toLong(), fields[0])
        assertEquals(channels.toLong(), fields[1])
        val buffer = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val reference = FloatArray(buffer.remaining()) { buffer.get() }
        val decoded = AudioKodec.decode(file.readBytes())
        assertEquals(fields[2], decoded.frames.toLong(), file.name)
        assertEquals(fields[2], decoded.info.durationFrames, file.name)
        assertEquals(reference.size, decoded.samples.size, file.name)
        // Existing MPEG bounds cover different floating transform/synthesis
        // arithmetic: peak 1e-5 for Layer II, 1e-4 otherwise, RMS below 1e-5.
        val bound = if (decoded.info.codec == AudioCodec.MP2) 1e-5f else 1e-4f
        TestSignals.assertClose(reference, decoded.samples, bound, file.name)
        val rms = sqrt(reference.indices.sumOf { index ->
            val difference = decoded.samples[index].toDouble() - reference[index]
            difference * difference
        } / reference.size)
        assertTrue(rms < 1e-5, "${file.name}: RMS error $rms")
        return decoded
    }

    private fun encode(directory: File, rate: Int, channels: Int, name: String, vararg extra: String,
                       frames: Int = rate / 2 + 37): File {
        val input = signal(directory, rate, channels, name, frames)
        val output = File(directory, "$name.mp3")
        run(listOf(tool("lame"), "--silent", "--noreplaygain", "--freeformat", "-b", freeBitrate(rate).toString(), "--resample",
            (rate / 1000.0).toString(), "-m", if (channels == 1) "m" else "s") + extra + listOf(input.path, output.path), directory)
        return output
    }

    // The reference rejects free-format frames above its own size limit at
    // MPEG-2.5 rates. Use 320 kbps there, and 640 kbps at the other rates.
    private fun freeBitrate(rate: Int): Int = if (rate < 16_000) 320 else 640

    private fun signal(directory: File, rate: Int, channels: Int, name: String, frames: Int = rate / 2 + 37): File {
        val samples = FloatArray(frames * channels) { index ->
            val frame = index / channels
            val t = frame / rate.toDouble()
            val frequency = 311 + (index % channels) * 503
            (0.35 * kotlin.math.sin(2 * Math.PI * (frequency * t + 83 * t * t)) +
                if (frame % 997 < 3) 0.1 else 0.0).toFloat()
        }
        return File(directory, "$name.wav").also {
            it.writeBytes(AudioKodec.encode(samples, AudioFormat(rate, channels), EncodeTarget.wav(16)))
        }
    }

    private fun makeFree(original: ByteArray, halfRate: Boolean = false): ByteArray = original.copyOf().also { bytes ->
        var at = 0
        while (at < bytes.size) {
            val header = assertNotNull(MpegFrameHeader.parse(original, at))
            bytes[at + 2] = (bytes[at + 2].toInt() and 15).toByte()
            if (halfRate) bytes[at + 1] = (bytes[at + 1].toInt() and 0xE7).toByte()
            at += header.frameBytes
        }
    }

    private fun tool(name: String): String {
        val found = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }
        assumeTrue("$name is required for the free-format MPEG comparison", found != null)
        return found!!.path
    }

    private fun run(command: List<String>, directory: File, allowMissing: Boolean = false): Pair<Int, String> {
        val log = File(directory, "oracle.log")
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).oracleStart()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            throw AssertionError("${command.first()} did not finish")
        }
        val output = log.readText()
        if (!allowMissing) assertEquals(0, process.exitValue(), output)
        return process.exitValue() to output
    }

    private fun inDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("audiokodec-mpeg-free-oracle-").toFile()
        try { test(directory) } finally { directory.deleteRecursively() }
    }
}

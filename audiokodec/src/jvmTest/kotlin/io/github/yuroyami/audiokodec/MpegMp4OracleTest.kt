package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.mp4.Mp4Reader
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** MPEG packets copied by FFmpeg, compared with its public float decoders. */
class MpegMp4OracleTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "audiokodec-mpeg-mp4-oracle").also { it.mkdirs() }
    private var commands = 0

    @Test
    fun copyMuxingWithoutXingKeepsTheBareSamplesAtEveryRate() {
        val ffmpeg = tool("ffmpeg")
        for (rate in listOf(44_100, 48_000, 32_000, 22_050, 24_000, 16_000, 11_025, 12_000, 8_000)) {
            for (channels in 1..2) {
                val name = "raw_${rate}_$channels"
                val bare = encode(ffmpeg, name, rate, channels, xing = false, variable = channels == 2)
                val mux = mux(ffmpeg, bare, name, rate)
                val expected = AudioKodec.decode(bare.readBytes())
                val ours = AudioKodec.decode(mux.readBytes())
                assertEquals(expected.format, ours.format, name)
                assertContentEquals(expected.samples, ours.samples, name)
                compare(ffmpeg, mux, ours.samples, "mp3float")
            }
        }
    }

    @Test
    fun gaplessEditListsTrimCopiedPacketsWithoutApplyingLameDelayAgain() {
        val ffmpeg = tool("ffmpeg")
        val ffprobe = tool("ffprobe")
        for (rate in listOf(44_100, 22_050, 8_000)) {
            for (channels in 1..2) {
                val name = "gapless_${rate}_$channels"
                val bare = encode(ffmpeg, name, rate, channels, xing = true, variable = channels == 2)
                val expected = AudioKodec.decode(bare.readBytes())
                val mux = mux(ffmpeg, bare, name, rate)
                val bytes = mux.readBytes()
                val whole = AudioKodec.decode(bytes)
                val track = Mp4Reader.parse(SourceReader(bytes.asAudioSource(), DecodeOptions.DEFAULT), DecodeOptions.DEFAULT).audio
                assertTrue(track.editStart > 0)
                // FFmpeg's copy mux preserves the start skip but drops the
                // MP3 demuxer's final discard-padding flag. That extra coded
                // tail belongs to the resulting MP4 timeline in both decoders.
                assertTrue(whole.frames >= expected.frames)
                assertContentEquals(expected.samples, whole.samples.copyOf(expected.samples.size), name)
                compare(ffmpeg, mux, whole.samples, "mp3float")
                val packets = track.offsets.indices.map { i ->
                    bytes.copyOfRange(track.offsets[i].toInt(), track.offsets[i].toInt() + track.sizes[i])
                }
                val exact = file("${name}_exact.mp4").also {
                    it.writeBytes(MpegMp4Fixtures.file(packets, "mp4a", if (rate >= 32_000) 0x6B else 0x69,
                        edit = track.editStart to expected.frames.toLong()))
                }
                assertContentEquals(expected.samples, AudioKodec.decode(exact.readBytes()).samples, "$name exact edit")
                val duration = run(listOf(ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=duration_ts",
                    "-of", "default=noprint_wrappers=1:nokey=1", exact.path)).trim().toLong()
                assertEquals(expected.frames.toLong(), duration, "$name edit duration")
                // FFmpeg reports that edit end but can decode the whole final
                // packet. Bound its float output at the independently known
                // original duration to compare the exact playable recording.
                compare(ffmpeg, exact, expected.samples, "mp3float", endFrame = expected.frames)
            }
        }
    }

    @Test
    fun indexedSeeksMatchContinuousDecodingWithTheReservoirEnabled() {
        val ffmpeg = tool("ffmpeg")
        for (rate in listOf(44_100, 22_050, 8_000)) {
            val name = "seek_$rate"
            val bare = encode(ffmpeg, name, rate, 2, xing = true, variable = true, seconds = 3)
            val mux = mux(ffmpeg, bare, name, rate, fastStart = true)
            val bytes = mux.readBytes()
            val whole = AudioKodec.decode(bytes)
            for (reuse in listOf(false, true)) {
                AudioKodec.open(bytes, DecodeOptions(allowSlowSeek = false, reuseBuffers = reuse)).use { decoder ->
                    for (target in listOf(whole.frames * 3 / 4, 1, whole.frames - 1, 0, whole.frames / 2, whole.frames, 17)) {
                        assertEquals(target.toLong(), decoder.seek(target.toLong()))
                        val block = decoder.nextFrame()
                        if (target == whole.frames) assertEquals(null, block)
                        else {
                            val audio = assertNotNull(block)
                            assertContentEquals(whole.samples.copyOfRange(target * 2, (target + audio.frameCount) * 2),
                                audio.samples.copyOf(audio.sampleCount), "$rate Hz at $target")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun fragmentedTracksRetainTheirCodedTimelineAndReferenceSamples() {
        val ffmpeg = tool("ffmpeg")
        for (rate in listOf(44_100, 8_000)) {
            val name = "fragmented_$rate"
            val bare = encode(ffmpeg, name, rate, 2, xing = false, variable = true)
            val mux = file("$name.mp4")
            run(listOf(ffmpeg, "-v", "error", "-y", "-i", bare.path, "-c:a", "copy", "-strict", "-1",
                "-movflags", "frag_keyframe+empty_moov+omit_tfhd_offset", "-frag_duration", "100000", mux.path))
            val expected = AudioKodec.decode(bare.readBytes())
            val ours = AudioKodec.decode(mux.readBytes())
            assertContentEquals(expected.samples, ours.samples)
            compare(ffmpeg, mux, ours.samples, "mp3float")
            AudioKodec.open(mux.readBytes()).use { decoder ->
                val target = ours.frames / 2
                decoder.seek(target.toLong())
                val block = assertNotNull(decoder.nextFrame())
                assertContentEquals(ours.samples.copyOfRange(target * 2, (target + block.frameCount) * 2), block.samples)
            }
        }
    }

    @Test
    fun layersOneAndTwoMatchTheIndependentFloatDecoders() {
        val ffmpeg = tool("ffmpeg")
        val layerOne = file("layer_one.mp4").also {
            it.writeBytes(MpegMp4Fixtures.file(MpegMp4Fixtures.packets(MpegMp4Fixtures.layerOne(60, 44_100)), "mp4a"))
        }
        val one = AudioKodec.decode(layerOne.readBytes())
        assertEquals(AudioCodec.MP1, one.info.codec)
        assertTrue(one.samples.any { it != 0f })
        compare(ffmpeg, layerOne, one.samples, "mp1float")
        val wav = wave("layer_two", 44_100, 2, 1)
        val bare = file("layer_two.mp2")
        run(listOf(ffmpeg, "-v", "error", "-y", "-i", wav.path, "-c:a", "mp2", "-b:a", "192k", bare.path))
        val mux = mux(ffmpeg, bare, "layer_two", 44_100)
        val two = AudioKodec.decode(mux.readBytes())
        assertEquals(AudioCodec.MP2, two.info.codec)
        assertContentEquals(AudioKodec.decode(bare.readBytes()).samples, two.samples)
        compare(ffmpeg, mux, two.samples, "mp2float")
    }

    @Test
    fun protectedPacketsKeepCrcChecksAndTolerantBehaviour() {
        val ffmpeg = tool("ffmpeg")
        val lame = tool("lame")
        val wav = wave("protected", 44_100, 2, 1)
        val bare = file("protected.mp3")
        run(listOf(lame, "--silent", "-p", "-V", "3", wav.path, bare.path))
        val mux = mux(ffmpeg, bare, "protected", 44_100)
        val bytes = mux.readBytes()
        val ours = AudioKodec.decode(bytes)
        compare(ffmpeg, mux, ours.samples, "mp3float")
        val track = Mp4Reader.parse(SourceReader(bytes.asAudioSource(), DecodeOptions.DEFAULT), DecodeOptions.DEFAULT).audio
        val broken = bytes.copyOf()
        val packet = track.offsets[20].toInt()
        assertEquals(0, broken[packet + 1].toInt() and 1)
        broken[packet + 4] = (broken[packet + 4].toInt() xor 1).toByte()
        val failure = assertFailsWith<CorruptStreamException> { AudioKodec.decode(broken) }
        assertEquals(packet.toLong(), failure.offset)
        assertContentEquals(ours.samples, AudioKodec.decode(broken, DecodeOptions(tolerant = true)).samples)
    }

    private fun wave(name: String, rate: Int, channels: Int, seconds: Int): File {
        val samples = FloatArray(rate * seconds * channels) { i ->
            val t = (i / channels).toDouble() / rate
            val channel = i % channels
            (0.31 * sin(2 * Math.PI * (317 + channel * 193) * t) +
                0.07 * sin(2 * Math.PI * (900 + 40 * t) * t) + if (i / channels % 7919 < 24) 0.18 else 0.0).toFloat()
        }
        return file("$name.wav").also { it.writeBytes(AudioKodec.encode(samples, AudioFormat(rate, channels), EncodeTarget.wav(16))) }
    }

    private fun encode(ffmpeg: String, name: String, rate: Int, channels: Int, xing: Boolean, variable: Boolean, seconds: Int = 1): File {
        val wav = wave(name, rate, channels, seconds)
        val result = file("$name.mp3")
        val coding = if (variable) listOf("-q:a", "2") else listOf("-b:a", if (rate >= 32_000) "128k" else "32k")
        run(listOf(ffmpeg, "-v", "error", "-y", "-i", wav.path, "-c:a", "libmp3lame") + coding +
            listOf("-write_xing", if (xing) "1" else "0", result.path))
        return result
    }

    private fun mux(ffmpeg: String, bare: File, name: String, rate: Int, fastStart: Boolean = false): File {
        val result = file("$name.mp4")
        run(listOf(ffmpeg, "-v", "error", "-y", "-i", bare.path, "-c:a", "copy", "-strict", "-1", "-movie_timescale", "$rate") +
            (if (fastStart) listOf("-movflags", "+faststart") else emptyList()) + result.path)
        return result
    }

    private fun compare(ffmpeg: String, input: File, expected: FloatArray, decoder: String, endFrame: Int? = null) {
        val raw = file("${input.name}.f32")
        run(listOf(ffmpeg, "-v", "error", "-y", "-c:a", decoder, "-i", input.path) +
            (endFrame?.let { listOf("-af", "atrim=end_sample=$it") } ?: emptyList()) + listOf("-f", "f32le", raw.path))
        val floats = ByteBuffer.wrap(raw.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val reference = FloatArray(floats.remaining()).also { floats.get(it) }
        assertEquals(expected.size, reference.size, input.name)
        // MpegOracleTest permits max 1e-4 and RMS 1e-5 for MP3 synthesis
        // arithmetic order. Layer II's existing maximum allowance is 1e-5.
        val bound = if (decoder == "mp2float") 1e-5f else 1e-4f
        TestSignals.assertClose(reference, expected, bound, input.name)
        val rms = kotlin.math.sqrt(reference.indices.sumOf { val d = (reference[it] - expected[it]).toDouble(); d * d } / reference.size)
        assertTrue(rms < 1e-5, "${input.name}: RMS $rms")
    }

    private fun run(command: List<String>): String {
        val log = file("command-${commands++}.log")
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).oracleStart()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            throw AssertionError("oracle timed out: $command")
        }
        val output = log.readText()
        assertEquals(0, process.exitValue(), "$command\n$output")
        return output
    }

    private fun file(name: String): File = File(directory, name)
    private fun tool(name: String): String {
        val executable = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }
        org.junit.Assume.assumeTrue("$name is required for the MPEG MP4 oracle", executable != null)
        return assertNotNull(executable).path
    }
}

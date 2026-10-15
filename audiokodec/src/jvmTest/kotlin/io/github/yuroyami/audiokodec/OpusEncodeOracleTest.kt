package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.opus.OpusEncoder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks the Opus encoder against libopus, and its files against three
 * decoders.
 *
 * The encoder is a translation of libopus's CELT encoder, so the first bar is
 * identity: driven as its CELT-only mode drives it, libopus's
 * `celt_encode_with_ec` must write the same packets byte for byte, with the
 * same final range, for mono and stereo, variable, constrained and constant
 * rates, 10 and 20 ms frames, silence, clicks and clipping.
 *
 * Then the Ogg Opus files themselves. `opus_encode_oracle.c`, a few lines
 * over libogg's and libopus's public interfaces built from the pinned
 * sources, reads every page, checks the headers and granule positions,
 * decodes every packet at 48 kHz and applies the pre-skip and end trim as
 * RFC 7845 says. Every packet must decode, be CELT-only, fullband and of the
 * chosen frame size, and the header's pre-skip must be the encoder's delay.
 * ffmpeg's own Opus decoder and its libopus decoder must agree on the length,
 * and so must AudioKodec's decoder, whose samples must equal the reference
 * decoder's exactly, as the decoder's own oracle tests require.
 *
 * Quality is scored by `OpusCompare`, the translation of `opus_compare`,
 * against the input, aligned by the stated pre-skip and nothing else, on a
 * seeded ten-second stereo signal of a harmonic chord, a sweep to 19 kHz, a
 * noise bed and decaying noise bursts every half second. libopus 1.5.2,
 * through ffmpeg with its default unconstrained VBR, scores an internal
 * weighted error of 0.25 at 128 kbit/s and 0.54 at 64 kbit/s on such a
 * signal. The bounds hold at the encoder's default, which is the same
 * unconstrained VBR: 0.40, 0.70 and 0.30 at 128, 64 and 256 kbit/s, 0.60 for
 * mono at 64 kbit/s, and an error that does not rise with the rate.
 * Constrained VBR is held to libopus's own constrained VBR on the same
 * signal instead.
 *
 * Every comparison skips itself when the tool it needs is missing.
 */
class OpusEncodeOracleTest {

    private val oracle: File? by lazy { buildOracle() }
    private val ffmpeg: String? by lazy { findOnPath("ffmpeg") }

    @Test
    fun packetsMatchLibopusCeltEncoderByteForByte() {
        val oracle = requireOracle()
        data class Case(
            val channels: Int,
            val bitrate: Int,
            val vbr: Boolean,
            val constrained: Boolean = true,
            val frameSize: Int = 960,
            val harsh: Boolean = false,
        )
        val cases = listOf(
            Case(2, 128_000, vbr = true),
            Case(1, 64_000, vbr = true),
            Case(2, 64_000, vbr = false),
            Case(2, 256_000, vbr = true),
            Case(2, 96_000, vbr = true, constrained = false),
            Case(2, 24_000, vbr = true),
            Case(2, 96_000, vbr = true, frameSize = 480),
            Case(1, 48_000, vbr = false, frameSize = 480),
            Case(2, 128_000, vbr = true, harsh = true),
            Case(1, 32_000, vbr = true, harsh = true),
        )
        for (case in cases) {
            val what = case.toString()
            val frames = 48_000 * 3
            val pcm = if (case.harsh) harsh(frames, case.channels) else music(frames, case.channels)
            val input = temp("celt_${case.channels}.f32").also { writeFloats(it, pcm) }
            val reference = temp("celt_reference.bit")
            run(
                listOf(
                    oracle.path, "celt", "${case.channels}", "${case.bitrate}", if (case.vbr) "1" else "0",
                    if (case.constrained) "1" else "0", "${case.frameSize}", input.path, reference.path,
                ),
            )
            val stream = ByteBuffer.wrap(reference.readBytes()).order(ByteOrder.BIG_ENDIAN)
            val encoder = OpusEncoder(case.channels, case.bitrate, case.vbr, case.frameSize, case.constrained)
            val packet = ByteArray(OpusEncoder.MAX_PACKET)
            val frame = FloatArray(case.frameSize * case.channels)
            var index = 0
            while (stream.remaining() >= 8) {
                val length = stream.int
                val range = stream.int.toLong() and 0xFFFF_FFFFL
                val expected = ByteArray(length).also { stream.get(it) }
                frame.fill(0f)
                val at = index * frame.size
                pcm.copyInto(frame, 0, at, minOf(pcm.size, at + frame.size))
                val written = encoder.encode(frame, 0, packet)
                assertEquals(length, written, "$what: length of packet $index")
                for (i in 1 until length) {
                    if (packet[i] != expected[i]) throw AssertionError("$what: packet $index differs from libopus at byte $i")
                }
                assertEquals(range, encoder.finalRange, "$what: final range of packet $index")
                index++
            }
            assertEquals((frames + case.frameSize - 1) / case.frameSize, index, "$what: packets")
        }
    }

    /**
     * Scores five rates in stereo at the default settings against the
     * bounds, which must also not get worse as the rate rises. Every rate is
     * measured and printed before any bound is checked.
     */
    @Test
    fun stereoQualityAtTheDefaultMeetsItsBounds() {
        val oracle = requireOracle()
        val frames = 48_000 * 10
        val input = music(frames, 2)
        val reference = OpusCompare.Reference(toInt16(input), 2)
        val bounds = mapOf(64_000 to 0.70, 128_000 to 0.40, 256_000 to 0.30)
        val failures = ArrayList<String>()
        var previous = Double.MAX_VALUE
        for (bitrate in intArrayOf(64_000, 96_000, 128_000, 192_000, 256_000)) {
            val what = "stereo, $bitrate bit/s VBR"
            val file = encode(input, 2, EncodeTarget.opus(bitrate))
            val decoded = decodeWithReference(oracle, file, what)
            checkStream(decoded, channels = 2, frameMillis = 20, what)
            assertEquals(frames, decoded.samples.size / 2, "$what: decoded length")
            val average = decoded.packets.sumOf { it.bytes } * 8.0 / (frames / 48_000.0)
            val result = OpusCompare.compare(reference, toInt16(decoded.samples), 48_000)
            println(
                "$what: average %.0f bit/s over the packets (%.0f with Ogg framing), ".format(
                    average, file.size * 8.0 / (frames / 48_000.0),
                ) + result,
            )
            if (abs(average - bitrate) > 0.10 * bitrate) failures += "$what: average bit rate $average"
            bounds[bitrate]?.let { bound -> if (result.error > bound) failures += "$what: error ${result.error} above $bound" }
            if (result.error > previous) failures += "$what: error ${result.error} rose from $previous"
            previous = result.error
            assertAudioKodecAgrees(file, decoded.samples, what)
            if (bitrate == 128_000) checkWithOpusCompareProgram(input, decoded.samples, result, what)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun monoAtSixtyFourKilobitsAtTheDefaultMeetsItsBound() {
        val oracle = requireOracle()
        val frames = 48_000 * 10
        val input = music(frames, 1)
        val what = "mono, 64000 bit/s VBR"
        val file = encode(input, 1, EncodeTarget.opus(64_000))
        val decoded = decodeWithReference(oracle, file, what)
        checkStream(decoded, channels = 1, frameMillis = 20, what)
        assertEquals(frames, decoded.samples.size, "$what: decoded length")
        assertAudioKodecAgrees(file, decoded.samples, what)
        // OpusCompare scores a mono decode against the fold-down of a stereo
        // reference, which for the same signal in both channels is the input.
        val stereo = FloatArray(frames * 2) { input[it / 2] }
        val result = OpusCompare.compare(toInt16(stereo), toInt16(decoded.samples), 1, 48_000)
        val average = decoded.packets.sumOf { it.bytes } * 8.0 / (frames / 48_000.0)
        println("$what: average %.0f bit/s, ".format(average) + result)
        assertTrue(abs(average - 64_000) <= 6_400, "$what: average bit rate $average")
        assertTrue(result.error <= 0.60, "$what: error ${result.error} above 0.60")
    }

    /**
     * Scores libopus's own encoder, `opus_demo` in its CELT-only
     * restricted-lowdelay mode with constrained VBR, on the same 16-bit
     * signal, which AudioKodec must match within 0.05. libopus also runs its
     * tonality analysis there, which AudioKodec leaves out, so the two
     * streams are not identical and the scores differ a little either way.
     */
    @Test
    fun constrainedVbrScoresAsLibopusDoesOnTheSameSignal() {
        val oracle = requireOracle()
        val demo = libopus()?.let { File(it, "build-plain/opus_demo") }?.takeIf { it.canExecute() }
        assumeTrue("opus_demo has not been built under reference/libopus", demo != null)
        val frames = 48_000 * 10
        for ((channels, bitrate) in listOf(2 to 64_000, 2 to 96_000, 2 to 128_000, 2 to 192_000, 2 to 256_000, 1 to 64_000)) {
            val what = "$channels ch, $bitrate bit/s constrained VBR"
            val pcm16 = toInt16(music(frames, channels))
            val input = FloatArray(pcm16.size) { pcm16[it] / 32768f }
            val stereo = if (channels == 2) pcm16 else ShortArray(frames * 2) { pcm16[it / 2] }
            val reference = OpusCompare.Reference(stereo, channels)
            val raw = temp("demo_input.sw").also { writeShorts(it, pcm16 + ShortArray(960 * channels)) }
            val bits = temp("demo.bit")
            val output = temp("demo_output.sw")
            run(listOf(demo!!.path, "-e", "restricted-lowdelay", "48000", "$channels", "$bitrate", "-cvbr", raw.path, bits.path))
            run(listOf(demo.path, "-d", "48000", "$channels", bits.path, output.path))
            val decoded = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val theirs = ShortArray(frames * channels) { decoded.get(OpusEncoder.LOOKAHEAD * channels + it) }
            val libopus = OpusCompare.compare(reference, theirs, 48_000)
            val mine = decodeWithReference(oracle, encode(input, channels, EncodeTarget.opus(bitrate, constrainedVbr = true)), what)
            val audioKodec = OpusCompare.compare(reference, toInt16(mine.samples), 48_000)
            println("$what: libopus error %.4f, AudioKodec error %.4f".format(libopus.error, audioKodec.error))
            assertTrue(audioKodec.error <= libopus.error + 0.05, "$what: AudioKodec ${audioKodec.error}, libopus ${libopus.error}")
        }
    }

    @Test
    fun constantBitrateWritesEveryPacketTheSameSize() {
        val oracle = requireOracle()
        val frames = 48_000 * 4
        val input = music(frames, 2)
        for ((bitrate, frameMillis) in listOf(64_000 to 20, 128_000 to 20, 96_000 to 10)) {
            val what = "stereo, $bitrate bit/s CBR, $frameMillis ms"
            val file = encode(input, 2, EncodeTarget.opus(bitrate, vbr = false, frameMillis = frameMillis))
            val decoded = decodeWithReference(oracle, file, what)
            checkStream(decoded, channels = 2, frameMillis = frameMillis, what)
            val sizes = decoded.packets.map { it.bytes }.toSet()
            assertEquals(1, sizes.size, "$what: packet sizes $sizes")
            val expected = bitrate * frameMillis / 8_000
            assertEquals(expected, sizes.single(), "$what: packet size")
            assertEquals(frames, decoded.samples.size / 2, "$what: decoded length")
            assertAudioKodecAgrees(file, decoded.samples, what)
        }
    }

    @Test
    fun aSineKeepsItsShapeAndSilenceStaysSilent() {
        val oracle = requireOracle()
        val frames = 48_000 * 3
        val amplitude = 0.5f
        val sine = FloatArray(frames * 2) { amplitude * sin(2 * PI * 1_000.0 * (it / 2) / 48_000).toFloat() }
        val decoded = decodeWithReference(oracle, encode(sine, 2, EncodeTarget.opus(128_000)), "1 kHz sine")
        var signal = 0.0
        var noise = 0.0
        // The first and last frames hold the codec's start-up and the cut.
        for (i in 960 * 2 until (frames - 960) * 2) {
            val error = decoded.samples[i] - sine[i]
            signal += sine[i].toDouble() * sine[i]
            noise += error.toDouble() * error
        }
        val snr = 10 * log10(signal / noise)
        println("1 kHz sine at -6 dBFS, 128 kbit/s: SNR %.2f dB".format(snr))
        assertTrue(snr >= 25.0, "1 kHz sine: SNR $snr dB")

        for (channels in 1..2) {
            val silence = FloatArray(48_000 * 2 * channels)
            val what = "silence, $channels ch"
            val quiet = decodeWithReference(oracle, encode(silence, channels, EncodeTarget.opus(128_000)), what)
            val peak = quiet.samples.maxOf { abs(it) }
            println("$what: peak $peak, packets of ${quiet.packets.map { it.bytes }.toSet()} bytes")
            assertTrue(peak < 1e-4f, "$what: peak $peak")
            assertEquals(silence.size, quiet.samples.size, "$what: decoded length")
        }
    }

    @Test
    fun everyDecoderGetsBackExactlyTheFramesThatWentInAlignedWithThem() {
        val oracle = requireOracle()
        val ffmpeg = ffmpeg
        assumeTrue("ffmpeg is required to decode the files AudioKodec writes", ffmpeg != null)
        for (channels in 1..2) {
            for (frames in intArrayOf(1, 100, 959, 960, 961, 48_000 + 333, 96_000)) {
                val what = "$channels ch, $frames frames"
                val input = music(frames, channels)
                val file = encode(input, channels, EncodeTarget.opus(96_000))
                val decoded = decodeWithReference(oracle, file, what)
                checkStream(decoded, channels, frameMillis = 20, what)
                assertEquals(frames.toLong(), decoded.end - decoded.preSkip, "$what: final granule less pre-skip")
                assertEquals(frames, decoded.samples.size / channels, "$what: reference decoder")
                for (codec in listOf("opus", "libopus")) {
                    val raw = temp("ffmpeg_$codec.f32")
                    val opusFile = temp("gapless.opus").also { it.writeBytes(file) }
                    run(listOf(ffmpeg!!, "-y", "-v", "error", "-c:a", codec, "-i", opusFile.path, "-f", "f32le", raw.path))
                    assertEquals(frames * channels * 4L, raw.length(), "$what: ffmpeg's $codec decoder length")
                }
                assertAudioKodecAgrees(file, decoded.samples, what)
            }
        }

        // A click in silence must come out where it went in.
        for (channels in 1..2) {
            for (position in intArrayOf(5_000, 30_017, 47_999)) {
                val what = "$channels ch click at $position"
                val input = FloatArray(72_000 * channels)
                for (c in 0 until channels) input[position * channels + c] = 0.8f
                val decoded = decodeWithReference(oracle, encode(input, channels, EncodeTarget.opus(128_000)), what)
                var peakAt = 0
                for (i in decoded.samples.indices) {
                    if (abs(decoded.samples[i]) > abs(decoded.samples[peakAt])) peakAt = i
                }
                println("$what: decoded peak at ${peakAt / channels}, ${decoded.samples[peakAt]}")
                assertTrue(abs(peakAt / channels - position) <= 1, "$what: decoded peak at ${peakAt / channels}")
            }
        }
    }

    @Test
    fun otherRatesAreResampledAndTheHeaderKeepsTheOriginal() {
        val oracle = requireOracle()
        for ((rate, frames) in listOf(44_100 to 44_100 * 2 + 17, 16_000 to 7_777)) {
            val what = "$rate Hz input"
            val samples = FloatArray(frames * 2) { 0.3f * sin(2 * PI * 440.0 * (it / 2) / rate).toFloat() }
            val file = AudioKodec.encode(samples, AudioFormat(rate, ChannelLayout.STEREO), EncodeTarget.opus(128_000))
            val decoded = decodeWithReference(oracle, file, what)
            assertEquals(rate, decoded.inputRate, "$what: OpusHead input rate")
            val expected = ((frames.toLong() * 48_000 + rate - 1) / rate).toInt()
            assertEquals(expected, decoded.samples.size / 2, "$what: decoded length at 48 kHz")
            assertAudioKodecAgrees(file, decoded.samples, what)
        }
    }

    private class Packet(val bytes: Int, val toc: Int, val samples: Int)

    private class Decoded(
        val samples: FloatArray,
        val packets: List<Packet>,
        val version: Int,
        val channels: Int,
        val preSkip: Int,
        val inputRate: Int,
        val gain: Int,
        val vendor: String,
        val end: Long,
    )

    /** Runs the reference decoder over [file] and reads back what it found. */
    private fun decodeWithReference(oracle: File, file: ByteArray, what: String): Decoded {
        val opus = temp("encoded.opus").also { it.writeBytes(file) }
        val out = temp("decoded.f32")
        val process = ProcessBuilder(oracle.path, "decode", opus.path, out.path).redirectErrorStream(true).oracleStart()
        val report = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS), "$what: the reference decoder did not finish")
        assertEquals(0, process.exitValue(), "$what: the reference decoder refused the file:\n$report")
        val packets = ArrayList<Packet>()
        var head = emptyList<String>()
        var vendor = ""
        var end = -1L
        for (line in report.lines()) {
            val fields = line.split(' ')
            when (fields[0]) {
                "head" -> head = fields
                "tags" -> vendor = line.removePrefix("tags ")
                "packet" -> packets += Packet(fields[1].toInt(), fields[2].toInt(), fields[3].toInt())
                "end" -> end = fields[1].toLong()
            }
        }
        val floats = ByteBuffer.wrap(out.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val samples = FloatArray(floats.remaining()).also { floats.get(it) }
        return Decoded(
            samples, packets, head[1].toInt(), head[2].toInt(), head[3].toInt(), head[4].toInt(), head[5].toInt(),
            vendor, end,
        )
    }

    /** The headers and every packet's table of contents. */
    private fun checkStream(decoded: Decoded, channels: Int, frameMillis: Int, what: String) {
        assertEquals(1, decoded.version, "$what: OpusHead version")
        assertEquals(channels, decoded.channels, "$what: OpusHead channels")
        assertEquals(OpusEncoder.LOOKAHEAD, decoded.preSkip, "$what: OpusHead pre-skip")
        assertEquals(48_000, decoded.inputRate, "$what: OpusHead input rate")
        assertEquals(0, decoded.gain, "$what: OpusHead output gain")
        assertEquals("AudioKodec", decoded.vendor, "$what: OpusTags vendor")
        // Configuration 31 is CELT-only fullband 20 ms, 30 the same at 10 ms.
        val config = if (frameMillis == 20) 31 else 30
        for ((index, packet) in decoded.packets.withIndex()) {
            assertEquals(config, packet.toc shr 3, "$what: configuration of packet $index")
            assertEquals(if (channels == 2) 1 else 0, (packet.toc shr 2) and 1, "$what: stereo flag of packet $index")
            assertEquals(0, packet.toc and 3, "$what: frame count code of packet $index")
            assertEquals(48 * frameMillis, packet.samples, "$what: samples in packet $index")
        }
    }

    /** AudioKodec's own decoder must give exactly the reference decoder's samples. */
    private fun assertAudioKodecAgrees(file: ByteArray, expected: FloatArray, what: String) {
        val decoded = AudioKodec.decode(file)
        assertEquals(AudioContainer.OGG, decoded.info.container, "$what: container")
        assertEquals(AudioCodec.OPUS, decoded.info.codec, "$what: codec")
        assertEquals(expected.size, decoded.samples.size, "$what: AudioKodec's decoded length")
        val first = expected.indices.firstOrNull { expected[it] != decoded.samples[it] }
        assertEquals(null, first, "$what: first sample where AudioKodec's decoder differs from libopus's")
    }

    /** The `opus_compare` program must score the decode as its translation does. */
    private fun checkWithOpusCompareProgram(input: FloatArray, decoded: FloatArray, result: OpusCompare.Result, what: String) {
        val program = libopus()?.let { File(it, "build-plain/opus_compare") }?.takeIf { it.canExecute() } ?: return
        val referenceFile = temp("compare_reference.sw").also { writeShorts(it, toInt16(input)) }
        val decodedFile = temp("compare_decoded.sw").also { writeShorts(it, toInt16(decoded)) }
        val process = ProcessBuilder(program.path, "-s", referenceFile.path, decodedFile.path).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS), "opus_compare did not finish")
        val error = Regex("[Ii]nternal weighted error is ([0-9.]+)").find(output)?.groupValues?.get(1)?.toDouble()
            ?: throw AssertionError("$what: opus_compare said:\n$output")
        println("$what: opus_compare program: ${output.trim()}")
        assertTrue(abs(error - result.error) < 1e-3, "$what: opus_compare $error, OpusCompare ${result.error}")
    }

    private fun encode(samples: FloatArray, channels: Int, target: EncodeTarget): ByteArray =
        AudioKodec.encode(samples, AudioFormat(48_000, ChannelLayout.canonical(channels)), target)

    private fun requireOracle(): File {
        val oracle = oracle
        assumeTrue("the libopus and libogg reference builds or a C compiler are not present", oracle != null)
        return oracle!!
    }

    private fun libopus(): File? {
        val dir = File(System.getProperty("user.dir"))
        return listOf(dir, dir.parentFile).map { File(it, "reference/libopus") }
            .firstOrNull { File(it, "build-plain/libopus.a").isFile }
    }

    /** Compiles the oracle against the reference builds, or returns null when any is missing. */
    private fun buildOracle(): File? {
        val dir = File(System.getProperty("user.dir"))
        val libopus = libopus() ?: return null
        val libogg = listOf(dir, dir.parentFile).map { File(it, "reference/libogg/install") }
            .firstOrNull { File(it, "lib/libogg.a").isFile && File(it, "include/ogg/ogg.h").isFile } ?: return null
        val source = temp("opus_encode_oracle.c")
        val resource = javaClass.getResourceAsStream("/opus_encode_oracle.c") ?: return null
        source.writeBytes(resource.use { it.readBytes() })
        val binary = temp("opus_encode_oracle")
        val compile = try {
            ProcessBuilder(
                "cc", "-O1", "-I", File(libopus, "include").path, "-I", File(libopus, "celt").path,
                "-I", File(libogg, "include").path, "-o", binary.path, source.path,
                File(libopus, "build-plain/libopus.a").path, File(libogg, "lib/libogg.a").path, "-lm",
            ).redirectErrorStream(true).oracleStart()
        } catch (failure: java.io.IOException) {
            return null
        }
        val output = compile.inputStream.bufferedReader().readText()
        assertTrue(compile.waitFor(120, TimeUnit.SECONDS), "the oracle did not compile in time")
        assertEquals(0, compile.exitValue(), "the oracle did not compile:\n$output")
        return binary
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS), "${command.first()} did not finish")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed:\n$output")
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private companion object {
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-opus-encode-oracle").also { it.mkdirs() }

        /**
         * The music-like test signal: a three-note chord with four harmonics
         * each, a logarithmic sweep from 50 Hz to 19 kHz over ten seconds, a
         * noise bed, and a burst of noise every half second that decays in
         * 30 ms, louder on the left. Seeded, so every run is the same.
         */
        fun music(frames: Int, channels: Int): FloatArray {
            val out = FloatArray(frames * channels)
            var seed = 0x2468ACE1
            val chord = doubleArrayOf(220.0, 277.18, 329.63)
            val sweepRatio = 19_000.0 / 50.0
            for (i in 0 until frames) {
                val t = i / 48_000.0
                seed = seed * 1_103_515_245 + 12_345
                val noise = ((seed ushr 8) and 0xFFFF) / 32_768.0 - 1.0
                val burst = 0.3 * exp(-((i % 24_000) / 48_000.0) / 0.03) * noise
                val sweep = 0.05 * sin(2 * PI * 50.0 * 10.0 * (sweepRatio.pow(t / 10.0) - 1) / ln(sweepRatio))
                for (c in 0 until channels) {
                    var value = 0.0
                    for ((k, f) in chord.withIndex()) {
                        for (h in 1..4) value += 0.06 / h * sin(2 * PI * f * h * t + c * 0.7 + k)
                    }
                    value += sweep + 0.02 * noise + burst * (if (c == 0) 1.0 else 0.6)
                    out[i * channels + c] = value.toFloat()
                }
            }
            return out
        }

        /** Silence, then clicks, then a square wave past full scale, then the music with the channels inverted. */
        fun harsh(frames: Int, channels: Int): FloatArray {
            val out = music(frames, channels)
            for (i in 0 until frames) {
                for (c in 0 until channels) {
                    out[i * channels + c] = when {
                        i < 30_000 -> 0f
                        i < 60_000 -> if (i % 4_800 < 3) 0.9f else 0f
                        i < 90_000 -> if ((i / 40) % 2 == 0) 1.5f else -1.5f
                        else -> out[i * channels + c] * (if (c == 0) 1f else -1f)
                    }
                }
            }
            return out
        }

        fun toInt16(samples: FloatArray): ShortArray = ShortArray(samples.size) { OpusDemoDecoding.toInt16(samples[it]) }

        fun writeFloats(file: File, samples: FloatArray) {
            val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (value in samples) buffer.putFloat(value)
            file.writeBytes(buffer.array())
        }

        fun writeShorts(file: File, samples: ShortArray) {
            val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (value in samples) buffer.putShort(value)
            file.writeBytes(buffer.array())
        }

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

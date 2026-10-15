package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks the Vorbis encoder against the reference decoder, libvorbisfile.
 *
 * libvorbisfile is what oggdec and every Xiph tool decodes with, so it is
 * both the "does the file open at all" check and the decoder whose output is
 * measured. `vorbisfile_oracle.c` calls its public API, compiled against
 * libogg 1.3.5 and libvorbis 1.3.7 as built under `reference/` (see
 * `reference/REFERENCES.md`), and writes the floats it decodes. ffmpeg's own
 * Vorbis decoder and AudioKodec's must agree on every file's length.
 *
 * A lossy encoder cannot be checked sample for sample, so quality is the
 * measure RFC 6716 defines for Opus, [OpusCompare]'s internal weighted
 * error, of libvorbisfile's decode against the input, both as 16-bit
 * samples, on a seeded ten-second stereo signal built like music. The bounds
 * were fixed before the encoder was written, from what libvorbis and libopus
 * score on a signal of the same kind (`docs/encoding.md`): at the default
 * quality at most 160 kbit/s and an error of 0.50, about libvorbis at
 * quality 3 to 4, and at the highest quality tested at most 256 kbit/s and
 * 0.35, which is better than a plain 15 kHz low-pass of the input scores.
 * The decoders are aligned by the encoder's stated delay, which is none.
 *
 * Every test skips itself when the oracle cannot be built or ffmpeg is
 * missing.
 */
class VorbisEncodeOracleTest {

    @Test
    fun musicMeetsTheStatedBoundsAtEveryQuality() {
        val tools = tools()
        val rate = 48_000
        val music = music(rate, 10.0)
        val frames = music.size / 2
        val reference = OpusCompare.Reference(sixteenBit(music), 2)
        val results = ArrayList<Triple<Float, Double, Double>>()
        for (quality in QUALITIES) {
            val what = "music at quality $quality"
            val file = temp("music_q$quality.ogg")
            file.writeBytes(AudioKodec.encode(music, AudioFormat(rate, 2), EncodeTarget.vorbis(quality)))
            val decoded = referenceDecode(tools, file, 2, rate, frames.toLong(), what)
            assertLengths(tools, file, frames, 2, what)
            assertAgreesWithAudioKodec(file, decoded, what)
            val kbps = file.length() * 8.0 / (frames.toDouble() / rate) / 1000.0
            val error = OpusCompare.compare(reference, sixteenBit(decoded), rate).error
            println("Vorbis $what: %.1f kbit/s, opus_compare internal weighted error %.4f".format(kbps, error))
            results.add(Triple(quality, kbps, error))
        }
        val default = results.first { it.first == DEFAULT_QUALITY }
        assertTrue(default.second <= 160.0, "the default quality uses ${default.second} kbit/s, over 160")
        assertTrue(default.third <= 0.50, "the default quality scores an error of ${default.third}, over 0.50")
        val highest = results.last()
        assertTrue(highest.second <= 256.0, "quality ${highest.first} uses ${highest.second} kbit/s, over 256")
        assertTrue(highest.third <= 0.35, "quality ${highest.first} scores an error of ${highest.third}, over 0.35")
        for ((lower, higher) in results.zipWithNext()) {
            assertTrue(higher.second > lower.second, "quality ${higher.first} is not larger than ${lower.first}")
            assertTrue(higher.third <= lower.third, "quality ${higher.first} scores worse than ${lower.first}")
        }
    }

    @Test
    fun aSineComesBackCleanAtTheDefaultQuality() {
        val tools = tools()
        val rate = 48_000
        val frames = rate * 2
        // A 1 kHz sine at -6 dBFS, the same in both channels.
        val sine = FloatArray(frames * 2) { (0.5 * sin(2 * PI * 1_000.0 * (it / 2) / rate)).toFloat() }
        val file = temp("sine.ogg")
        file.writeBytes(AudioKodec.encode(sine, AudioFormat(rate, 2), EncodeTarget.vorbis()))
        val decoded = referenceDecode(tools, file, 2, rate, frames.toLong(), "sine")
        assertLengths(tools, file, frames, 2, "sine")
        assertAgreesWithAudioKodec(file, decoded, "sine")
        val snr = snrDb(sine, decoded)
        println("Vorbis 1 kHz sine at -6 dBFS, default quality: SNR %.1f dB, %d bytes".format(snr, file.length()))
        assertTrue(snr >= 30.0, "the sine came back with an SNR of $snr dB, under 30")
    }

    @Test
    fun silenceStaysSilentAndSmall() {
        val tools = tools()
        val rate = 44_100
        val frames = rate * 5
        val file = temp("silence.ogg")
        val bytes = AudioKodec.encode(FloatArray(frames * 2), AudioFormat(rate, 2), EncodeTarget.vorbis())
        file.writeBytes(bytes)
        val decoded = referenceDecode(tools, file, 2, rate, frames.toLong(), "silence")
        assertLengths(tools, file, frames, 2, "silence")
        assertAgreesWithAudioKodec(file, decoded, "silence")
        val peak = decoded.maxOf { abs(it) }
        val audioBytes = bytes.size - headerBytes(bytes)
        println("Vorbis silence: peak $peak, ${bytes.size} bytes of which $audioBytes are audio pages")
        assertTrue(peak < 1e-4f, "silence came back with a peak of $peak")
        // Five seconds of silence is about 220 packets of a byte each.
        assertTrue(audioBytes < 2_000, "five seconds of silence took $audioBytes bytes of audio pages")
    }

    @Test
    fun everyShapeDecodesToExactlyItsLength() {
        val tools = tools()
        val cases = listOf(
            Shape(48_000, 1, 48_000 * 3),
            Shape(44_100, 2, 44_100 * 3),
            Shape(22_050, 2, 22_050 * 3),
            Shape(22_050, 1, 33_333),
            Shape(8_000, 1, 16_001),
            Shape(96_000, 2, 96_000),
            // Shorter than one long block, down to a single frame.
            Shape(44_100, 2, 2_000),
            Shape(48_000, 1, 500),
            Shape(48_000, 2, 1),
            // A length that is a multiple of no block size.
            Shape(44_100, 2, 123_457),
        )
        for (shape in cases) {
            val what = "${shape.channels} ch at ${shape.rate} Hz, ${shape.frames} frames"
            val signal = tones(shape.rate, shape.channels, shape.frames)
            val file = temp("shape_${shape.rate}_${shape.channels}_${shape.frames}.ogg")
            file.writeBytes(AudioKodec.encode(signal, AudioFormat(shape.rate, shape.channels), EncodeTarget.vorbis()))
            val decoded = referenceDecode(tools, file, shape.channels, shape.rate, shape.frames.toLong(), what)
            assertLengths(tools, file, shape.frames, shape.channels, what)
            assertAgreesWithAudioKodec(file, decoded, what)
            if (shape.frames >= shape.rate) {
                val snr = snrDb(signal, decoded)
                println("Vorbis $what: SNR %.1f dB, %d bytes".format(snr, file.length()))
                assertTrue(snr > 15.0, "$what: SNR $snr dB")
            }
        }
    }

    /**
     * A different tone in every channel, read back by libvorbisfile in the
     * Vorbis channel order the specification fixes, and by ffmpeg in its own
     * order, which for these layouts is AudioKodec's.
     */
    @Test
    fun surroundChannelsLandOnTheirSpeakers() {
        val tools = tools()
        val rate = 48_000
        val frames = rate
        for (channels in 3..8) {
            val layout = io.github.yuroyami.audiokodec.internal.vorbis.VorbisDecoder.layoutFor(channels)
            val what = "$channels channels"
            val frequencies = DoubleArray(channels) { if (layout.channels[it] == AudioChannel.LOW_FREQUENCY) 80.0 else 300.0 + 200.0 * it }
            val signal = FloatArray(frames * channels)
            for (f in 0 until frames) {
                for (c in 0 until channels) signal[f * channels + c] = (0.3 * sin(2 * PI * frequencies[c] * f / rate)).toFloat()
            }
            val file = temp("surround_$channels.ogg")
            file.writeBytes(AudioKodec.encode(signal, AudioFormat(rate, layout), EncodeTarget.vorbis()))

            // Vorbis I specification, section 4.3.9: the stream's own order.
            val vorbisOrder = io.github.yuroyami.audiokodec.internal.vorbis.VorbisDecoder.orderFor(channels)
            val decoded = referenceDecode(tools, file, channels, rate, frames.toLong(), what)
            for (slot in 0 until channels) {
                val found = dominant(decoded, channels, vorbisOrder[slot], rate)
                assertEquals(frequencies[slot], found, 1.0, "$what: libvorbisfile channel ${vorbisOrder[slot]} carries slot $slot")
            }

            val wav = temp("surround_$channels.wav")
            wav.delete()
            run(listOf(tools.ffmpeg, "-v", "error", "-i", file.path, "-c:a", "pcm_f32le", wav.path), what)
            val viaFfmpeg = AudioKodec.decode(wav.readBytes())
            assertEquals(frames, viaFfmpeg.frames, "$what: ffmpeg's length")
            if (channels in listOf(3, 4, 5, 6, 8)) {
                for (slot in 0 until channels) {
                    val found = dominant(viaFfmpeg.samples, channels, slot, rate)
                    assertEquals(frequencies[slot], found, 1.0, "$what: ffmpeg's channel $slot")
                }
            }
            assertAgreesWithAudioKodec(file, decoded, what)
        }
    }

    @Test
    fun tagsReachTheCommentHeader() {
        val tools = tools()
        val tags = TagBundle(mapOf(TagField.TITLE to listOf("Encoded by AudioKodec"), TagField.ARTIST to listOf("Ünïcödé")))
        val file = temp("tagged.ogg")
        file.writeBytes(AudioKodec.encode(tones(44_100, 2, 44_100), AudioFormat(44_100, 2), EncodeTarget.vorbis(), tags))
        assertLengths(tools, file, 44_100, 2, "tagged")
        assertAgreesWithAudioKodec(file, referenceDecode(tools, file, 2, 44_100, 44_100, "tagged"), "tagged")
        val probe = ProcessBuilder(
            findOnPath("ffprobe") ?: return, "-v", "error", "-show_entries", "stream_tags:format_tags", "-of", "default=nw=1", file.path,
        ).redirectErrorStream(true).oracleStart()
        val text = probe.inputStream.bufferedReader().readText()
        assertTrue(probe.waitFor(60, TimeUnit.SECONDS) && probe.exitValue() == 0, text)
        assertTrue("TAG:TITLE=Encoded by AudioKodec" in text, text)
        assertTrue("TAG:ARTIST=Ünïcödé" in text, text)
        assertTrue("AudioKodec" in String(file.readBytes(), Charsets.ISO_8859_1), "the vendor string")
    }

    // Helpers.

    private class Tools(val oracle: String, val ffmpeg: String)

    private class Shape(val rate: Int, val channels: Int, val frames: Int)

    private fun tools(): Tools {
        val ffmpeg = findOnPath("ffmpeg")
        assumeTrue("ffmpeg is required for the Vorbis encoder oracle", ffmpeg != null)
        val dir = File(System.getProperty("user.dir"))
        val root = listOf(dir, dir.parentFile).firstOrNull {
            File(it, "reference/libvorbis/build/lib/libvorbisfile.a").isFile &&
                File(it, "reference/libogg/install/lib/libogg.a").isFile
        }
        assumeTrue("libogg and libvorbis must be built under reference/, see .github/scripts/prepare-codec-oracles.sh", root != null)
        val binary = File(workDirectory, "vorbisfile_oracle")
        synchronized(VorbisEncodeOracleTest::class.java) {
            if (!binary.canExecute()) {
                val source = File(workDirectory, "vorbisfile_oracle.c")
                source.writeBytes(VorbisEncodeOracleTest::class.java.getResourceAsStream("/vorbisfile_oracle.c")!!.readBytes())
                val vorbis = File(root!!, "reference/libvorbis")
                val ogg = File(root, "reference/libogg/install")
                run(
                    listOf(
                        "cc", "-O2", "-I", File(vorbis, "include").path, "-I", File(ogg, "include").path,
                        "-o", binary.path, source.path,
                        File(vorbis, "build/lib/libvorbisfile.a").path, File(vorbis, "build/lib/libvorbis.a").path,
                        File(ogg, "lib/libogg.a").path, "-lm",
                    ),
                    "building the libvorbisfile oracle",
                )
            }
        }
        return Tools(binary.path, ffmpeg!!)
    }

    /** libvorbisfile's decode of [file], which must open, decode cleanly and be exactly [frames] long. */
    private fun referenceDecode(tools: Tools, file: File, channels: Int, rate: Int, frames: Long, what: String): FloatArray {
        val out = File(workDirectory, file.nameWithoutExtension + ".f32")
        val process = ProcessBuilder(tools.oracle, file.path, out.path).redirectErrorStream(true).oracleStart()
        val line = process.inputStream.bufferedReader().readText().trim()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "$what: libvorbisfile did not finish")
        assertEquals(0, process.exitValue(), "$what: libvorbisfile refused the file: $line")
        val fields = line.split(" ").map { it.toLong() }
        assertEquals(channels.toLong(), fields[0], "$what: libvorbisfile's channel count")
        assertEquals(rate.toLong(), fields[1], "$what: libvorbisfile's sample rate")
        assertEquals(frames, fields[2], "$what: libvorbisfile's ov_pcm_total")
        assertEquals(frames, fields[3], "$what: frames libvorbisfile decoded")
        val buffer = ByteBuffer.wrap(out.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    /** ffmpeg and AudioKodec read exactly [frames] frames too. */
    private fun assertLengths(tools: Tools, file: File, frames: Int, channels: Int, what: String) {
        val raw = temp(file.nameWithoutExtension + ".ffmpeg.f32")
        raw.delete()
        run(listOf(tools.ffmpeg, "-v", "error", "-i", file.path, "-f", "f32le", "-c:a", "pcm_f32le", raw.path), what)
        assertEquals(frames.toLong() * channels * 4, raw.length(), "$what: ffmpeg's length")
        val info = AudioKodec.probe(file.readBytes())
        assertEquals(AudioCodec.VORBIS, info.codec, what)
        assertEquals(frames.toLong(), info.durationFrames, "$what: AudioKodec's stated length")
    }

    /**
     * AudioKodec's own decoder agrees with libvorbisfile within the tolerance
     * [VorbisOracleTest] holds it to, once libvorbisfile's channels, in the
     * stream's order, are put in AudioKodec's.
     */
    private fun assertAgreesWithAudioKodec(file: File, reference: FloatArray, what: String) {
        val decoded = AudioKodec.decode(file.readBytes())
        val channels = decoded.format.channels
        val order = io.github.yuroyami.audiokodec.internal.vorbis.VorbisDecoder.orderFor(channels)
        assertEquals(reference.size, decoded.samples.size, "$what: AudioKodec's decoded length")
        var worst = 0f
        for (i in reference.indices) {
            val expected = reference[i - i % channels + order[i % channels]]
            worst = maxOf(worst, abs(expected - decoded.samples[i]))
        }
        assertTrue(worst < 1e-4f, "$what: AudioKodec and libvorbisfile differ by $worst")
    }

    /** Bytes of the identification, comment and setup pages: everything before the first audio page. */
    private fun headerBytes(bytes: ByteArray): Int {
        var at = 0
        var packets = 0
        while (packets < 3) {
            val segments = bytes[at + 26].toInt() and 0xFF
            var size = 27 + segments
            for (i in 0 until segments) {
                val lace = bytes[at + 27 + i].toInt() and 0xFF
                size += lace
                if (lace < 255) packets++
            }
            at += size
        }
        return at
    }

    private fun run(command: List<String>, what: String) {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS), "$what: ${command.first()} did not finish")
        assertEquals(0, process.exitValue(), "$what: ${command.joinToString(" ")} failed:\n$output")
    }

    private fun temp(name: String): File = File(workDirectory, name)

    companion object {
        /** The qualities measured, the default among them and the highest last. */
        private val QUALITIES = floatArrayOf(1f, 3f, 4f, 5f, 7f)
        private const val DEFAULT_QUALITY = 4f

        private val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-vorbis-encode-oracle").also { it.mkdirs() }

        private fun findOnPath(tool: String): String? {
            val path = System.getenv("PATH") ?: return null
            for (directory in path.split(File.pathSeparator)) {
                val candidate = File(directory, tool)
                if (candidate.canExecute()) return candidate.absolutePath
            }
            return null
        }

        /**
         * A seeded stereo signal built like music: a chord of four notes with
         * six harmonics each, a logarithmic sweep from 50 Hz to 19 kHz, a
         * noise bed, and a burst of decaying noise every half second. The
         * channels differ in phase and in their noise.
         */
        fun music(rate: Int, seconds: Double): FloatArray {
            val frames = (rate * seconds).toInt()
            val noise = DoubleArray(frames * 2)
            var seed = 0x2545_F491L
            for (i in noise.indices) {
                seed = (seed * 1_103_515_245L + 12_345L) and 0xFFFF_FFFFL
                noise[i] = (seed ushr 8) / 16_777_216.0 * 2.0 - 1.0
            }
            val notes = doubleArrayOf(220.0, 277.18, 329.63, 440.0)
            val sweepFrom = 50.0
            val sweepTo = 19_000.0
            val out = FloatArray(frames * 2)
            for (c in 0 until 2) {
                for (i in 0 until frames) {
                    val t = i.toDouble() / rate
                    var chord = 0.0
                    for ((k, f) in notes.withIndex()) {
                        for (h in 1..6) {
                            if (f * h < rate / 2.0) chord += sin(2 * PI * f * h * t + 0.7 * k + 0.3 * h + 0.5 * c) / h
                        }
                    }
                    val growth = ln(sweepTo / sweepFrom)
                    val phase = 2 * PI * sweepFrom * seconds / growth * (exp(t / seconds * growth) - 1)
                    val sweep = 0.08 * sin(phase + c)
                    val bed = 0.015 * noise[i * 2 + c]
                    val burst = 0.2 * exp(-(t % 0.5) / 0.03) * noise[i * 2 + (c + 1) % 2]
                    out[i * 2 + c] = (0.06 * chord + sweep + bed + burst).toFloat()
                }
            }
            return out
        }

        /** A steady tone per channel at a few hundred Hz, with a quieter octave above it. */
        fun tones(rate: Int, channels: Int, frames: Int): FloatArray = FloatArray(frames * channels) {
            val f = it / channels
            val c = it % channels
            val hz = 330.0 + 110.0 * c
            (0.4 * sin(2 * PI * hz * f / rate) + 0.1 * sin(2 * PI * 2 * hz * f / rate + 1)).toFloat()
        }

        fun sixteenBit(samples: FloatArray): ShortArray =
            ShortArray(samples.size) { (samples[it] * 32_768f).roundToInt().coerceIn(-32_768, 32_767).toShort() }

        fun snrDb(original: FloatArray, decoded: FloatArray): Double {
            var signal = 0.0
            var noise = 0.0
            for (i in original.indices) {
                signal += original[i].toDouble() * original[i]
                val d = decoded[i] - original[i].toDouble()
                noise += d * d
            }
            return 10 * log10(signal / noise)
        }

        /** The frequency, to the nearest 10 Hz from 50 Hz to 3 kHz, with the most power in [channel]. */
        fun dominant(samples: FloatArray, channels: Int, channel: Int, rate: Int): Double {
            val frames = samples.size / channels
            var best = 0.0
            var bestPower = -1.0
            var hz = 50.0
            while (hz <= 3_000.0) {
                // Goertzel's single-bin DFT.
                val w = 2 * PI * hz / rate
                val coefficient = 2 * cos(w)
                var s1 = 0.0
                var s2 = 0.0
                for (f in 0 until frames) {
                    val s = samples[f * channels + channel] + coefficient * s1 - s2
                    s2 = s1
                    s1 = s
                }
                val power = s1 * s1 + s2 * s2 - coefficient * s1 * s2
                if (power > bestPower) {
                    bestPower = power
                    best = hz
                }
                hz += 10.0
            }
            return best
        }
    }
}

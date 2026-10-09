package io.github.yuroyami.audiokodec

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks the AAC-LC decoder against ffmpeg's, on files ffmpeg's encoder
 * writes with every tool it has: mid/side and intensity stereo, temporal
 * noise shaping, noise substitution, block switching, pulses and escapes,
 * and program configurations, at every sampling frequency table, in ADTS
 * and in MP4.
 *
 * Both decoders work in floating point from the same standard, so wherever
 * the spectrum is coded their samples agree to within float rounding.
 * Noise substitution draws random numbers the standard leaves open, so with
 * it on the energy of every block is compared instead.
 *
 * Every test skips itself when ffmpeg is missing; the Apple test needs
 * `afconvert`, which only macOS has.
 */
class AacDecodeOracleTest {

    @Test
    fun codedSpectraAgreeWithFfmpegToFloatRounding() {
        val ffmpeg = ffmpeg()
        val cases = listOf(
            Case(7_350, 1), Case(8_000, 1), Case(11_025, 2), Case(16_000, 2), Case(22_050, 2), Case(24_000, 1),
            Case(32_000, 2), Case(44_100, 2), Case(48_000, 2, listOf("-aac_is", "0", "-aac_ms", "1")),
            Case(64_000, 1), Case(88_200, 2), Case(96_000, 2), Case(44_100, 2, listOf("-aac_tns", "0")),
            Case(44_100, 2, bitrate = 320), Case(48_000, 6), Case(48_000, 6, listOf("-aac_pce", "1")),
            Case(32_000, 3), Case(32_000, 4), Case(32_000, 5), Case(44_100, 8),
        )
        for (case in cases) {
            for (extension in listOf("aac", "m4a")) {
                val what = "$case in $extension"
                val file = temp("coded_${case.rate}_${case.channels}_${case.extra.joinToString("")}_${case.bitrate}.$extension")
                encode(ffmpeg, file, case.rate, case.channels, listOf("-aac_pns", "0", "-b:a", "${case.bitrate * case.channels}k") + case.extra)
                val ours = AudioKodec.decode(file.readBytes())
                assertEquals(case.rate, ours.format.sampleRate, what)
                assertEquals(case.channels, ours.format.channels, what)
                val theirs = mapFfmpegChannels(decodeWithFfmpeg(ffmpeg, file), case.channels)
                val frames = ours.samples.size / case.channels
                if (extension == "aac") {
                    // ADTS states no delay, so both decoders give every block.
                    assertEquals(theirs.size / case.channels, frames, "$what: length")
                } else {
                    // The edit list trims the encoder's delay and padding;
                    // ffmpeg 6.1 trims only the delay.
                    assertEquals((case.rate * SECONDS).toInt(), frames, "$what: length")
                }
                val (worst, snr) = compare(ours.samples, theirs, case.channels, frames)
                assertTrue(worst < 2e-6 && snr > 120.0, "$what: largest difference $worst, ${snr.toInt()} dB")
            }
        }
    }

    @Test
    fun noiseSubstitutionHasFfmpegsEnergyInEveryBlock() {
        val ffmpeg = ffmpeg()
        for (rate in listOf(44_100, 16_000)) {
            val file = temp("pns_$rate.aac")
            run(
                listOf(
                    ffmpeg, "-y", "-v", "error", "-f", "lavfi",
                    "-i", "anoisesrc=d=4:c=pink:r=$rate:a=0.3,pan=stereo|c0=c0|c1=0.7*c0",
                    "-c:a", "aac", "-b:a", "48k", file.path,
                ),
            )
            val ours = AudioKodec.decode(file.readBytes()).samples
            val theirs = decodeWithFfmpeg(ffmpeg, file)
            assertEquals(theirs.size, ours.size, "$rate Hz: length")
            // The random numbers differ, so the samples do too: this is
            // what shows that noise really was substituted.
            assertTrue(compare(ours, theirs, 2, ours.size / 2).second < 40.0, "$rate Hz: no noise was substituted")
            for (block in 0 until ours.size / 2048) {
                for (c in 0 until 2) {
                    var a = 0.0
                    var b = 0.0
                    for (f in block * 1024 until block * 1024 + 1024) {
                        a += ours[f * 2 + c].toDouble().let { it * it }
                        b += theirs[f * 2 + c].toDouble().let { it * it }
                    }
                    if (b < 1e-6) continue
                    val difference = abs(10 * log10(a / b))
                    assertTrue(difference < 0.5, "$rate Hz, block $block, channel $c: energy differs by $difference dB")
                }
            }
        }
    }

    @Test
    fun everyChannelComesFromTheSpeakerItWasRecordedFor() {
        val ffmpeg = ffmpeg()
        // A different tone in every channel, the LFE's low enough to pass its
        // band limit. ffmpeg reads them in its own layout order, which for
        // these layouts is AudioKodec's too.
        for ((channels, layout) in listOf(3 to "3.0", 4 to "4.0", 5 to "5.0", 6 to "5.1")) {
            val tones = if (channels == 6) listOf(300, 500, 700, 60, 1100, 1300) else listOf(300, 500, 700, 900, 1100).take(channels)
            val generator = "aevalsrc=${tones.joinToString("|") { "0.4*sin(2*PI*$it*t)" }}:s=48000:d=2:c=$layout"
            val source = temp("tones_$channels.f32")
            val file = temp("tones_$channels.m4a")
            run(listOf(ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", generator, "-f", "f32le", "-c:a", "pcm_f32le", source.path))
            run(listOf(ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", generator, "-c:a", "aac", "-b:a", "${96 * channels}k", file.path))
            val original = floats(source)
            val decoded = AudioKodec.decode(file.readBytes())
            assertEquals(original.size, decoded.samples.size, "$channels channels: the edit list trims to the original")
            for (c in 0 until channels) {
                val best = (0 until channels).maxBy { d -> correlation(decoded.samples, original, channels, c, d) }
                assertEquals(c, best, "$channels channels: channel $c (${decoded.format.channelLayout.channels[c]})")
            }
        }
    }

    @Test
    fun seekingLandsOnTheSamplesAContinuousDecodeGives() {
        val ffmpeg = ffmpeg()
        for (extension in listOf("aac", "m4a")) {
            val file = temp("seek.$extension")
            encode(ffmpeg, file, 44_100, 2, listOf("-aac_pns", "0", "-b:a", "128k"))
            val bytes = file.readBytes()
            val whole = AudioKodec.decode(bytes).samples
            for (target in listOf(0L, 1L, 1023L, 1024L, 50_000L, 99_999L)) {
                AudioKodec.open(bytes).use { decoder ->
                    assertEquals(target, decoder.seek(target))
                    val frame = decoder.nextFrame()!!
                    val count = minOf(frame.frameCount, 500)
                    for (i in 0 until count * 2) {
                        assertEquals(whole[(target * 2).toInt() + i], frame.samples[i], "$extension: seek to $target, sample $i")
                    }
                }
            }
        }
    }

    @Test
    fun otherProfilesAreRefusedByName() {
        val ffmpeg = ffmpeg()
        for ((profile, name) in listOf("aac_main" to "AAC Main", "aac_ltp" to "AAC LTP")) {
            for (extension in listOf("aac", "m4a")) {
                val file = temp("$profile.$extension")
                encode(ffmpeg, file, 44_100, 2, listOf("-profile:a", profile, "-strict", "-2"))
                assertEquals(AudioCodec.AAC, AudioKodec.probe(file.readBytes()).codec)
                val failure = assertFailsWith<UnsupportedFeatureException>("$profile in $extension") { AudioKodec.open(file.readBytes()) }
                assertEquals(name, failure.feature)
            }
        }
    }

    @Test
    fun appleEncodedFilesDecodeAsAppleDecodesThem() {
        val ffmpeg = ffmpeg()
        val afconvert = findOnPath("afconvert")
        assumeTrue("afconvert is only on macOS", afconvert != null)
        val wave = temp("apple_source.wav")
        val m4a = temp("apple.m4a")
        val back = temp("apple_decoded.wav")
        run(listOf(ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", signal(44_100, 2, SECONDS), "-c:a", "pcm_s16le", wave.path))
        run(listOf(afconvert!!, "-f", "m4af", "-d", "aac", "-b", "128000", wave.path, m4a.path))
        run(listOf(afconvert, "-f", "WAVE", "-d", "LEF32", m4a.path, back.path))
        val ours = AudioKodec.decode(m4a.readBytes()).samples
        val theirs = AudioKodec.decode(back.readBytes()).samples
        // Apple's decoder is not ours, so only a close match is expected, at
        // whatever alignment afconvert writes its output with.
        val best = listOf(0, 1024, -1024, 2112, -2112, 2048, -2048).maxOf { shift -> compareShifted(ours, theirs, 2, shift) }
        assertTrue(best > 60.0, "the best alignment agrees to ${best.toInt()} dB")
    }

    // Helpers.

    private class Case(val rate: Int, val channels: Int, val extra: List<String> = emptyList(), val bitrate: Int = 64) {
        override fun toString() = "$rate Hz, $channels ch${if (extra.isEmpty()) "" else " $extra"}, $bitrate kb/s"
    }

    /**
     * ffmpeg 6.1 decodes channel configuration 7 as 7.1 with its first channel
     * pair at the front and its second at the sides; ISO/IEC 14496-3, Table
     * 1.19, puts the left and right centre pair first and the outside pair
     * second, which is the order AudioKodec follows. Elsewhere the orders agree.
     */
    private fun mapFfmpegChannels(samples: FloatArray, channels: Int): FloatArray {
        if (channels != 8) return samples
        // AudioKodec: FL FR FC LFE BL BR FLC FRC. ffmpeg: FL FR FC LFE BL BR SL SR.
        val from = intArrayOf(6, 7, 2, 3, 4, 5, 0, 1)
        return FloatArray(samples.size) { samples[it - it % 8 + from[it % 8]] }
    }

    private fun compare(ours: FloatArray, theirs: FloatArray, channels: Int, frames: Int): Pair<Double, Double> {
        var worst = 0.0
        var noise = 0.0
        var signal = 0.0
        for (i in 0 until frames * channels) {
            val a = ours[i].toDouble()
            val b = theirs[i].toDouble()
            worst = max(worst, abs(a - b))
            noise += (a - b) * (a - b)
            signal += b * b
        }
        return worst to if (noise == 0.0) 300.0 else 10 * log10(signal / noise)
    }

    private fun compareShifted(ours: FloatArray, theirs: FloatArray, channels: Int, shift: Int): Double {
        var noise = 0.0
        var signal = 0.0
        val frames = minOf(ours.size, theirs.size) / channels - abs(shift)
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val a = ours[(f + maxOf(shift, 0)) * channels + c].toDouble()
                val b = theirs[(f + maxOf(-shift, 0)) * channels + c].toDouble()
                noise += (a - b) * (a - b)
                signal += b * b
            }
        }
        return 10 * log10(signal / noise)
    }

    private fun correlation(a: FloatArray, b: FloatArray, channels: Int, ca: Int, cb: Int): Double {
        var sum = 0.0
        for (f in 0 until minOf(a.size, b.size) / channels) sum += a[f * channels + ca].toDouble() * b[f * channels + cb]
        return sum
    }

    private fun encode(ffmpeg: String, file: File, rate: Int, channels: Int, extra: List<String>) {
        run(
            listOf(ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", signal(rate, channels, SECONDS), "-c:a", "aac") + extra + file.path,
        )
    }

    /**
     * Tones, a sweep, bursts of noise and clicks, different in every channel,
     * so that blocks switch, noise shaping engages, and the stereo tools have
     * something to share.
     */
    private fun signal(rate: Int, channels: Int, seconds: Double): String {
        val expressions = (0 until channels).map { c ->
            val tone = 220 * (c + 2)
            "0.25*sin(2*PI*$tone*t)+0.15*sin(2*PI*(500+${1500 + 300 * c}*t)*t)" +
                "+0.3*(random(${c + 1})-0.5)*lt(mod(t\\,0.6)\\,0.1)" +
                "+0.6*lt(mod(t+${0.05 * c}\\,0.37)\\,0.002)"
        }
        return "aevalsrc=${expressions.joinToString("|")}:s=$rate:d=$seconds"
    }

    private fun decodeWithFfmpeg(ffmpeg: String, file: File): FloatArray {
        val raw = temp(file.nameWithoutExtension + "_${file.extension}.f32")
        run(listOf(ffmpeg, "-y", "-v", "error", "-i", file.path, "-f", "f32le", "-c:a", "pcm_f32le", raw.path))
        return floats(raw)
    }

    private fun floats(file: File): FloatArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun ffmpeg(): String {
        val ffmpeg = findOnPath("ffmpeg")
        assumeTrue("ffmpeg is required for the AAC oracle", ffmpeg != null)
        return ffmpeg!!
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "${command.first()} did not finish")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed:\n$output")
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private companion object {
        const val SECONDS = 3.0

        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-aac-oracle").also { it.mkdirs() }

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

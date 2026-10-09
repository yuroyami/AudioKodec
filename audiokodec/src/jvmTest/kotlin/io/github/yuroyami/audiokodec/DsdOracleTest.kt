package io.github.yuroyami.audiokodec

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks DSF and DSDIFF reading against ffprobe and AudioKodec's DSD to PCM
 * conversion against ffmpeg's.
 *
 * Nothing writes DSD here, so the test writes its own: a sigma delta
 * modulator turns a few tones into bits, which go into DSF and DSDIFF files
 * built by hand.
 *
 * The two conversions differ by design. ffmpeg filters once, from fs to fs/8,
 * with a 96-tap filter that delays the signal by 40.5 bits; AudioKodec filters
 * in three stages down to fs/32, centred, so with no delay. Their outputs are
 * therefore compared where both are defined, tone by tone: each tone's level
 * and phase in both, with ffmpeg's delay taken out of its phase. The test
 * first confirms that delay, by decoding files that each differ from a base
 * file in one bit, whose outputs differ by ffmpeg's filter itself.
 *
 * The bound is the filters' difference in the audio band: ffmpeg's falls by
 * 0.0016 dB at 22.05 kHz and AudioKodec's stays within 2e-5 dB, so tone
 * levels may differ by up to 0.002 dB, and phases by what a level difference
 * that small allows, under 0.001 rad.
 */
class DsdOracleTest {

    @Test
    fun ffmpegsConverterIsALinearFilterDelayingByFortyAndAHalfBits() {
        val ffmpeg = ffmpeg()
        val bits = BooleanArray(65_536) { it % 2 == 0 }
        val base = decodeWithFfmpeg(ffmpeg, dsf(listOf(bits)), "impulse_base")
        // ffmpeg's output is at fs/8, so each bit reaches one phase of its filter: flip eight in turn.
        var weight = 0.0
        var moment = 0.0
        for (phase in 0 until 8) {
            val at = 30_000 + phase
            val flipped = bits.copyOf().also { it[at] = !it[at] }
            val response = decodeWithFfmpeg(ffmpeg, dsf(listOf(flipped)), "impulse_$phase")
            val sign = if (flipped[at]) 1.0 else -1.0
            for (j in base.indices) {
                val tap = (response[j] - base[j]) * sign / 2
                weight += tap
                moment += tap * (8 * j - at)
            }
        }
        assertEquals(1.0, weight, 1e-5, "ffmpeg's filter has unit gain")
        assertEquals(FFMPEG_DELAY, moment / weight, 1e-4, "ffmpeg's filter is centred 40.5 bits late")
    }

    @Test
    fun toneLevelsAndPhasesAgreeWithFfmpegsWithinTheFiltersDifference() {
        val ffmpeg = ffmpeg()
        val seconds = 0.5
        val n = (FS * seconds).toInt()
        val tones = listOf(1_000.0 to 0.25, 5_000.0 to 0.1, 12_000.0 to 0.05, 19_000.0 to 0.02)
        val left = modulate(n) { t -> tones.sumOf { (f, a) -> a * sin(2 * PI * f * t) } }
        val right = modulate(n) { t -> 0.3 * sin(2 * PI * 3_000.0 * t + 1.0) }
        for ((name, file) in listOf("dsf" to dsf(listOf(left, right)), "dff" to dff(listOf(left, right)))) {
            val ours = AudioKodec.decode(file)
            assertEquals(88_200, ours.format.sampleRate)
            val theirs = decodeWithFfmpeg(ffmpeg, file, "tones_$name", channels = 2)
            for ((channel, list) in listOf(0 to tones, 1 to listOf(3_000.0 to 0.3))) {
                for ((f, _) in list) {
                    val a = tone(ours.samples, 2, channel, 88_200.0, f, 0.0)
                    val b = tone(theirs, 2, channel, 352_800.0, f, FFMPEG_DELAY / FS)
                    val levelDb = 20 * log10(a.first / b.first)
                    val phase = abs(wrap(a.second - b.second))
                    assertTrue(abs(levelDb) < 0.002, "$name, channel $channel, $f Hz: levels differ by $levelDb dB")
                    assertTrue(phase < 0.001, "$name, channel $channel, $f Hz: phases differ by $phase rad")
                }
            }
        }
    }

    @Test
    fun probeAgreesWithFfprobe() {
        val ffprobe = findOnPath("ffprobe")
        assumeTrue("ffprobe is required", ffprobe != null)
        // A sample count that fills the last DSF block, which ffprobe counts whole.
        val n = 4096 * 8 * 7
        val channels = List(2) { c -> modulate(n) { t -> 0.2 * sin(2 * PI * (440.0 + 100 * c) * t) } }
        for ((name, file) in listOf("dsf" to dsf(channels), "dff" to dff(channels))) {
            val path = File(workDirectory, "probe.$name").also { it.writeBytes(file) }
            val output = run(listOf(ffprobe!!, "-v", "error", "-show_entries", "stream=sample_rate,channels,duration", "-of", "default=nw=1", path.path))
            val fields = output.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            val info = AudioKodec.probe(file)
            assertEquals(fields.getValue("channels").toInt(), info.format.channels, name)
            // ffmpeg's converter stops at fs/8; AudioKodec's goes on to fs/32.
            assertEquals(fields.getValue("sample_rate").toInt(), info.format.sampleRate * 4, name)
            assertEquals(fields.getValue("duration").toDouble(), info.durationFrames!!.toDouble() / info.format.sampleRate, 1e-6, name)
        }
    }

    // Helpers.

    /** A second-order sigma delta modulator: plain, stable at these levels, and enough to keep its noise far below the tones. */
    private fun modulate(n: Int, signal: (Double) -> Double): BooleanArray {
        val out = BooleanArray(n)
        var i1 = 0.0
        var i2 = 0.0
        var y = 0.0
        for (k in 0 until n) {
            i1 += signal(k / FS) - y
            i2 += i1 - y
            y = if (i2 >= 0) 1.0 else -1.0
            out[k] = y > 0
        }
        return out
    }

    private fun dsf(channels: List<BooleanArray>): ByteArray {
        val n = channels[0].size
        val bytesPerChannel = (n + 7) / 8
        val blocks = (bytesPerChannel + 4095) / 4096
        val data = ByteArray(blocks * 4096 * channels.size)
        for ((c, bits) in channels.withIndex()) {
            for (k in 0 until n) {
                if (!bits[k]) continue
                val byte = k / 8
                val at = (byte / 4096) * 4096 * channels.size + c * 4096 + byte % 4096
                data[at] = (data[at].toInt() or (1 shl (k % 8))).toByte()
            }
        }
        val out = ByteBuffer.allocate(28 + 52 + 12 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("DSD ".toByteArray()).putLong(28).putLong(28L + 52 + 12 + data.size).putLong(0)
        out.put("fmt ".toByteArray()).putLong(52).putInt(1).putInt(0).putInt(if (channels.size == 2) 2 else 1).putInt(channels.size)
        out.putInt(FS.toInt()).putInt(1).putLong(n.toLong()).putInt(4096).putInt(0)
        out.put("data".toByteArray()).putLong(12L + data.size).put(data)
        return out.array()
    }

    private fun dff(channels: List<BooleanArray>): ByteArray {
        val n = channels[0].size
        val data = ByteArray(n / 8 * channels.size)
        for ((c, bits) in channels.withIndex()) {
            for (k in 0 until n / 8 * 8) if (bits[k]) {
                val at = (k / 8) * channels.size + c
                data[at] = (data[at].toInt() or (0x80 ushr (k % 8))).toByte()
            }
        }
        val ids = if (channels.size == 2) listOf("SLFT", "SRGT") else listOf("C   ")
        val chnl = 2 + 4 * ids.size
        val prop = 4 + (12 + 4) + (12 + chnl) + (12 + 4 + 1 + 14 + 1)
        val body = 4 + (12 + 4) + (12 + prop) + (12 + data.size)
        val out = ByteBuffer.allocate(12 + body).order(ByteOrder.BIG_ENDIAN)
        out.put("FRM8".toByteArray()).putLong(body.toLong()).put("DSD ".toByteArray())
        out.put("FVER".toByteArray()).putLong(4).putInt(0x01050000)
        out.put("PROP".toByteArray()).putLong(prop.toLong()).put("SND ".toByteArray())
        out.put("FS  ".toByteArray()).putLong(4).putInt(FS.toInt())
        out.put("CHNL".toByteArray()).putLong(chnl.toLong()).putShort(ids.size.toShort())
        for (id in ids) out.put(id.toByteArray())
        out.put("CMPR".toByteArray()).putLong(4 + 1 + 14 + 1).put("DSD ".toByteArray()).put(14).put("not compressed".toByteArray()).put(0)
        out.put("DSD ".toByteArray()).putLong(data.size.toLong()).put(data)
        return out.array()
    }

    /** The level and phase of the tone at [f] Hz in [channel], measured over whole periods from the middle of the signal. */
    private fun tone(samples: FloatArray, channels: Int, channel: Int, rate: Double, f: Double, delay: Double): Pair<Double, Double> {
        val frames = samples.size / channels
        val start = frames / 4
        val length = ((frames / 2) / (rate / f)).toInt() * rate / f
        var re = 0.0
        var im = 0.0
        val count = length.toInt()
        for (i in 0 until count) {
            val t = (start + i) / rate - delay
            val v = samples[(start + i) * channels + channel]
            re += v * cos(2 * PI * f * t)
            im += v * sin(2 * PI * f * t)
        }
        return hypot(re, im) * 2 / count to atan2(im, re)
    }

    private fun wrap(angle: Double): Double {
        var a = angle
        while (a > PI) a -= 2 * PI
        while (a < -PI) a += 2 * PI
        return a
    }

    private fun decodeWithFfmpeg(ffmpeg: String, file: ByteArray, name: String, channels: Int = 1): FloatArray {
        val input = File(workDirectory, "$name.dsd").also { it.writeBytes(file) }
        val output = File(workDirectory, "$name.f32")
        run(listOf(ffmpeg, "-y", "-v", "error", "-i", input.path, "-ac", "$channels", "-f", "f32le", output.path))
        val buffer = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun ffmpeg(): String {
        val ffmpeg = findOnPath("ffmpeg")
        assumeTrue("ffmpeg is required for the DSD oracle", ffmpeg != null)
        return ffmpeg!!
    }

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS), "${command.first()} did not finish")
        if (process.exitValue() != 0) throw AssertionError("${File(command.first()).name} failed:\n${output.take(2000)}")
        return output
    }

    private companion object {
        const val FS = 2_822_400.0
        const val FFMPEG_DELAY = 40.5
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-dsd-oracle").also { it.mkdirs() }

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

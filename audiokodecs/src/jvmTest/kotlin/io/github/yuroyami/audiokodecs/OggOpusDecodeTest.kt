package io.github.yuroyami.audiokodecs

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks Ogg Opus files from end to end: what [AudioKodecs.open] makes of
 * files ffmpeg wrote with libopus.
 *
 * The codec itself is checked to the bit elsewhere, against the libopus
 * version it was ported from. Here the comparison is with ffmpeg's own
 * decoding through the libopus installed beside it, which may be another
 * version and is built with SIMD kernels, so the samples agree closely rather
 * than exactly. What has to agree exactly is everything around the codec:
 * how many samples come out, where they start, which channel each is in, and
 * the header's gain.
 *
 * The tests skip themselves when ffmpeg is missing.
 */
class OggOpusDecodeTest {

    private val ffmpeg: String? by lazy { findOnPath("ffmpeg") }

    @Test
    fun filesDecodeAsLibopusDecodesThem() {
        val ffmpeg = ffmpeg ?: return skip()
        val cases = listOf(
            Triple("speech_to_music", 2, "48k"),
            Triple("speech_mono", 1, "16k"),
            Triple("music_stereo", 2, "128k"),
            Triple("music_51", 6, "256k"),
            Triple("music_71", 8, "320k"),
        )
        for ((name, channels, bitrate) in cases) {
            val pcm = when {
                name.startsWith("speech_to") -> OpusTestSignals.speech(channels, 1.5) + OpusTestSignals.music(channels, 1.5, 12_000)
                name.startsWith("speech") -> OpusTestSignals.speech(channels, 3.0)
                else -> OpusTestSignals.music(channels, 3.0)
            }
            val opus = encode(ffmpeg, name, pcm, channels, "-b:a", bitrate)
            compareWithFfmpeg(ffmpeg, opus, channels, name)
        }
    }

    @Test
    fun theHeaderGainIsApplied() {
        val ffmpeg = ffmpeg ?: return skip()
        val opus = encode(ffmpeg, "gain", OpusTestSignals.music(2, 2.0, 8_000), 2, "-b:a", "96k")
        // Six decibels down, in the header's Q7.8 decibels, with the page's
        // checksum recomputed so that the file is still valid.
        val louder = temp("gain_minus_6db.opus")
        louder.writeBytes(withOpusGain(opus.readBytes(), -6 * 256))
        val plain = decodeAll(opus.readBytes())
        val quiet = decodeAll(louder.readBytes())
        val ratio = rms(quiet) / rms(plain)
        assertTrue(abs(ratio - 0.501) < 0.002, "a -6 dB header gain scaled the output by $ratio")
        compareWithFfmpeg(ffmpeg, louder, 2, "with a header gain")
    }

    @Test
    fun surroundChannelsComeOutOfTheirOwnSpeakers() {
        val ffmpeg = ffmpeg ?: return skip()
        // A tone per channel, in AudioKodecs's order, which for 5.1 is front
        // left, front right, centre, low frequency, back left and back right.
        for ((layout, tones) in listOf(
            ChannelLayout.SURROUND_5_1 to doubleArrayOf(300.0, 500.0, 700.0, 90.0, 1_100.0, 1_300.0),
            ChannelLayout.SURROUND_7_1 to doubleArrayOf(300.0, 500.0, 700.0, 90.0, 1_100.0, 1_300.0, 1_700.0, 1_900.0),
        )) {
            val channels = tones.size
            val rate = 48_000
            val frames = rate * 2
            val samples = FloatArray(frames * channels) { i ->
                (0.4 * sin(2 * PI * tones[i % channels] * (i / channels) / rate)).toFloat()
            }
            val wav = temp("tones_$channels.wav")
            wav.writeBytes(AudioKodecs.encode(samples, AudioFormat(rate, layout), EncodeTarget.wav(16)))
            val opus = temp("tones_$channels.opus")
            run(listOf(ffmpeg, "-y", "-v", "quiet", "-i", wav.path, "-c:a", "libopus", "-b:a", "384k", opus.path))

            val decoder = AudioKodecs.open(opus.readBytes())
            assertEquals(layout, decoder.format.channelLayout, "$channels channels: layout")
            val decoded = decodeAll(decoder)
            for (c in 0 until channels) {
                val strongest = tones.indices.maxBy { goertzel(decoded, channels, c, tones[it], rate) }
                assertEquals(c, strongest, "$channels channels: channel $c carries the tone meant for channel $strongest")
            }
        }
    }

    @Test
    fun aWholeNumberOfPeriodsComesBackWholeAndInPlace() {
        val ffmpeg = ffmpeg ?: return skip()
        // A second of a 1 kHz tone is exactly a thousand periods, so two
        // copies end to end are one unbroken tone.
        val rate = 48_000
        val frames = rate
        val tone = FloatArray(frames * 2) { i -> (0.5 * sin(2 * PI * 1_000.0 * (i / 2) / rate)).toFloat() }
        val wav = temp("periods.wav")
        wav.writeBytes(AudioKodecs.encode(tone, AudioFormat(rate, 2), EncodeTarget.wav(16)))
        val opus = temp("periods.opus")
        run(listOf(ffmpeg, "-y", "-v", "quiet", "-i", wav.path, "-c:a", "libopus", "-b:a", "128k", opus.path))

        val decoded = decodeAll(opus.readBytes())
        assertEquals(frames * 2, decoded.size, "the decoded length in samples")
        // Placed where it belongs, the decoded tone lines up with the one
        // that went in: a shift of even one sample costs more than the codec
        // does.
        val shift = (-8..8).minBy { lag -> error(decoded, tone, lag, from = 2_000, until = frames - 2_000) }
        assertEquals(0, shift, "the decoded tone is shifted against the original")
        // And played twice, the join is as clean as the middle.
        val twice = decoded + decoded
        val joinError = error(twice, tone + tone, 0, from = frames - 480, until = frames + 480)
        val middleError = error(twice, tone + tone, 0, from = frames / 2 - 480, until = frames / 2 + 480)
        assertTrue(
            joinError < 4 * middleError + 1e-3,
            "the join of two decodes is rougher than the middle: $joinError against $middleError",
        )
    }

    @Test
    fun seekingLandsWhereDecodingFromTheStartWould() {
        val ffmpeg = ffmpeg ?: return skip()
        for ((name, channels) in listOf("seek_stereo" to 2, "seek_51" to 6)) {
            val opus = encode(ffmpeg, name, OpusTestSignals.music(channels, 6.0), channels, "-b:a", "${64 * channels}k")
            val bytes = opus.readBytes()
            val whole = decodeAll(bytes)
            val total = whole.size / channels
            AudioKodecs.open(bytes).use { decoder ->
                for (target in longArrayOf(0, 100, 3_000, 3_839, 3_840, 48_000, 100_003, 200_000, total - 1_000L)) {
                    assertEquals(target, decoder.seek(target), "$name: seek to $target")
                    assertEquals(target, decoder.positionFrames, "$name: position after seeking to $target")
                    val got = ArrayList<Float>()
                    while (got.size < WINDOW * channels) {
                        val frame = decoder.nextFrame() ?: break
                        assertEquals(target + got.size / channels, frame.positionFrames, "$name: frame position after seeking to $target")
                        for (i in 0 until frame.frameCount * channels) got += frame.samples[i]
                    }
                    val frames = minOf(WINDOW.toLong(), total - target).toInt()
                    assertTrue(got.size >= frames * channels, "$name: only ${got.size / channels} frames after seeking to $target")
                    fun worst(lag: Int, from: Int, until: Int): Float {
                        var worst = 0f
                        for (i in from * channels until until * channels) {
                            val at = (target + lag) * channels + i
                            if (at in whole.indices) worst = maxOf(worst, abs(got[i] - whole[at.toInt()]))
                        }
                        return worst
                    }
                    // The output lines up with a decode from the start, to the
                    // sample: shifting it either way is far worse.
                    val aligned = worst(0, 0, frames)
                    assertTrue(
                        aligned * 4 < minOf(worst(-1, 0, frames), worst(1, 0, frames)),
                        "$name: after seeking to $target the output does not line up with a decode from the start",
                    )
                    // The decoder restarts before the target, so its state
                    // only converges on the one a decode from the start has;
                    // a little further on the two agree closely.
                    if (frames == WINDOW) {
                        val settled = worst(0, WINDOW - 2_400, WINDOW)
                        assertTrue(settled < SEEK_TOLERANCE, "$name: 150 ms after seeking to $target the output still differs by $settled")
                    }
                }
                assertEquals(total.toLong(), decoder.seek(total + 10_000L), "$name: a seek past the end stops at the end")
                assertEquals(null, decoder.nextFrame(), "$name: nothing follows the end")
            }
        }
    }

    /** Decodes [opus] with AudioKodecs and with ffmpeg and compares the two. */
    private fun compareWithFfmpeg(ffmpeg: String, opus: File, channels: Int, what: String) {
        val reference = temp(opus.name + ".f32")
        // Asked for float, libopus does not soft clip, which is what
        // AudioKodecs gives too: samples beyond full scale are left for the
        // caller to deal with rather than bent back inside it.
        run(
            listOf(
                ffmpeg, "-y", "-v", "quiet", "-request_sample_fmt", "flt", "-c:a", "libopus", "-i", opus.path,
                "-f", "f32le", reference.path,
            ),
        )
        val theirs = ByteBuffer.wrap(reference.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val mine = decodeAll(opus.readBytes())
        assertEquals(theirs.remaining() / channels, mine.size / channels, "$what: decoded length in frames")
        var worst = 0f
        for (i in mine.indices) worst = maxOf(worst, abs(mine[i] - theirs[i]))
        assertTrue(worst < FFMPEG_TOLERANCE, "$what: the decode differs from libopus's by up to $worst")
    }

    private fun encode(ffmpeg: String, name: String, pcm: ByteArray, channels: Int, vararg options: String): File {
        val raw = temp("$name.pcm").also { it.writeBytes(pcm) }
        val opus = temp("$name.opus")
        run(
            listOf(
                ffmpeg, "-y", "-v", "quiet", "-f", "s16le", "-ar", "48000", "-ac", "$channels", "-i", raw.path,
                "-c:a", "libopus", *options, opus.path,
            ),
        )
        return opus
    }

    private fun decodeAll(bytes: ByteArray): FloatArray = AudioKodecs.open(bytes).use { decodeAll(it) }

    private fun decodeAll(decoder: AudioDecoder): FloatArray {
        val out = ArrayList<FloatArray>()
        var size = 0
        while (true) {
            val frame = decoder.nextFrame() ?: break
            val samples = frame.samples.copyOf(frame.frameCount * decoder.format.channels)
            out += samples
            size += samples.size
        }
        val all = FloatArray(size)
        var at = 0
        for (part in out) {
            part.copyInto(all, at)
            at += part.size
        }
        return all
    }

    /** Root mean square error between [a] shifted by [lag] and [b], over frames [from] until [until], both stereo. */
    private fun error(a: FloatArray, b: FloatArray, lag: Int, from: Int, until: Int): Double {
        var sum = 0.0
        var count = 0
        for (frame in from until until) {
            for (c in 0 until 2) {
                val i = (frame + lag) * 2 + c
                if (i < 0 || i >= a.size) continue
                val d = a[i] - b[frame * 2 + c]
                sum += d * d
                count++
            }
        }
        return sqrt(sum / count)
    }

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    /** The strength of [frequency] in one channel of interleaved [samples]. */
    private fun goertzel(samples: FloatArray, channels: Int, channel: Int, frequency: Double, rate: Int): Double {
        val coefficient = 2 * cos(2 * PI * frequency / rate)
        var s1 = 0.0
        var s2 = 0.0
        for (frame in 0 until samples.size / channels) {
            val s = samples[frame * channels + channel] + coefficient * s1 - s2
            s2 = s1
            s1 = s
        }
        return s1 * s1 + s2 * s2 - coefficient * s1 * s2
    }

    /** [file] with the OpusHead output gain set to [gainQ8], and the first page's checksum redone. */
    private fun withOpusGain(file: ByteArray, gainQ8: Int): ByteArray {
        val out = file.copyOf()
        val segments = out[26].toInt() and 0xFF
        val payload = 27 + segments
        check(String(out, payload, 8, Charsets.US_ASCII) == "OpusHead")
        out[payload + 16] = gainQ8.toByte()
        out[payload + 17] = (gainQ8 shr 8).toByte()
        val pageLength = payload + (0 until segments).sumOf { out[27 + it].toInt() and 0xFF }
        for (i in 22 until 26) out[i] = 0
        val crc = oggCrc(out, pageLength)
        for (i in 0 until 4) out[22 + i] = (crc ushr (8 * i)).toByte()
        return out
    }

    private fun oggCrc(bytes: ByteArray, length: Int): Int {
        var crc = 0
        for (i in 0 until length) {
            crc = crc xor ((bytes[i].toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc and Int.MIN_VALUE != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(180, TimeUnit.SECONDS), "${command.first()} did not finish")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed:\n$output")
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private fun skip() {
        println("ffmpeg is not on the path, so the Ogg Opus comparison did not run")
    }

    private companion object {
        /**
         * How far a sample may stray from ffmpeg's decode, whose libopus is
         * a different version built with SIMD kernels: a third of one step of
         * 16-bit audio.
         */
        const val FFMPEG_TOLERANCE = 1e-5f

        /** Frames read after each seek: 200 ms. */
        const val WINDOW = 9_600

        /** How far a sample may stray from a decode from the start, 150 ms after a seek. */
        const val SEEK_TOLERANCE = 1e-3f

        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodecs-ogg-opus").also { it.mkdirs() }

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

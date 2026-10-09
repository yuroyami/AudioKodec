package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.ac3.Ac3Decoder
import io.github.yuroyami.audiokodec.internal.ac3.Ac3Frame
import io.github.yuroyami.audiokodec.internal.ac3.Ac3Probe
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks the AC-3 decoder against ffmpeg's, on files ffmpeg's encoder writes
 * at every channel arrangement it has, at the three sampling rates, from 32
 * to 640 kbit/s, with rematrixing, channel coupling and the transients that
 * switch blocks, in a raw stream, in MP4 and in Matroska.
 *
 * Two things separate any two decoders of AC-3, and neither is a defect in
 * either.
 *
 * Dither. A mantissa given no bits is replaced by random noise when its
 * channel's dither flag is set, and the standard leaves the noise open, so
 * two decoders differ wherever the flag is set. ffmpeg's encoder sets it, so
 * the tests that compare samples first clear every dither flag in the file
 * and repair the two CRCs, which makes the decode deterministic. A separate
 * test compares the energy of files whose flags are set.
 *
 * Fixed point. The standard defines decoding in real arithmetic. ffmpeg's
 * float decoder holds each coefficient as a 24-bit fixed-point integer, so
 * every coefficient is cut down to a multiple of 2^-23 when it is
 * dequantised and again when it is decoupled; measured on every file below,
 * reproducing exactly that rounding makes AudioKodec agree with ffmpeg to
 * float rounding, and nothing else does. AudioKodec decodes without the
 * rounding, since it is more exact, so its samples differ from ffmpeg's by
 * what the rounding does to them.
 *
 * So each comparison is made twice. With the rounding reproduced through
 * [Ac3Probe], the two decoders must agree to [EXACT_BOUND]: that shows the
 * bit allocation, mantissas, coupling, rematrixing, block switching and
 * transform are the same. Without it, the public decode must stay within
 * [ROUNDING_BOUND] of ffmpeg's.
 *
 * The least that bound can be is what the rounding can do to a sample. Each
 * of the at most 253 coefficients of a block moves by less than 2^-23 for
 * each rounding, by up to nine times that where a coupling coordinate scales
 * the first one, and the synthesis weights a coefficient by no more than a
 * window and a cosine, one at most, over two overlapping blocks and the
 * final factor of two. A sample therefore cannot move by more than
 * 2 * 2 * 253 * 9 * 2^-23, about 1e-3, which is the ceiling. The roundings
 * all lean the same way but the weights alternate in sign, so the sum does
 * not reach it: across the files below the largest difference is 1.5e-5, at
 * 640 kbit/s where the most coefficients are coded, and the bound asserted
 * is three times that.
 *
 * Every test skips itself when ffmpeg is missing.
 */
class Ac3DecodeOracleTest {

    @Test
    fun codedSpectraAgreeWithFfmpegOnceItsFixedPointIsReproduced() {
        val ffmpeg = ffmpeg()
        val cases = listOf(
            Case("mono", 1, 48_000, 96), Case("mono", 1, 44_100, 64), Case("mono", 1, 32_000, 32), Case("mono", 1, 48_000, 640),
            Case("stereo", 2, 48_000, 192), Case("stereo", 2, 44_100, 128), Case("stereo", 2, 32_000, 96),
            Case("stereo", 2, 48_000, 448, listOf("-stereo_rematrixing", "0")),
            Case("stereo", 2, 48_000, 96, listOf("-channel_coupling", "1")),
            Case("3.0", 3, 48_000, 192), Case("2.1", 3, 48_000, 192), Case("quad", 4, 48_000, 256),
            Case("4.0", 4, 44_100, 256), Case("3.1", 4, 48_000, 256), Case("5.0", 5, 48_000, 320),
            Case("5.1", 6, 48_000, 448), Case("5.1", 6, 44_100, 384), Case("5.1", 6, 32_000, 640),
            Case("5.1", 6, 48_000, 192, listOf("-channel_coupling", "1")),
            Case("5.1", 6, 48_000, 256, listOf("-channel_coupling", "1", "-cpl_start_band", "3")),
        )
        for (case in cases) {
            val raw = temp("coded_${case.name}.ac3")
            encode(ffmpeg, raw, case)
            val cleared = clearDither(raw.readBytes())
            val clearedRaw = temp("cleared_${case.name}.ac3")
            clearedRaw.writeBytes(cleared)

            val theirs = decodeWithFfmpeg(ffmpeg, clearedRaw)
            val exact = Ac3Exact.decode(cleared)
            assertEquals(theirs.size, exact.size, "$case: length")
            val (exactWorst, exactSnr) = compare(exact, theirs)
            assertTrue(exactWorst < EXACT_BOUND, "$case: with ffmpeg's rounding reproduced, the largest difference is $exactWorst, ${exactSnr.toInt()} dB")

            val ours = AudioKodec.decode(cleared)
            assertEquals(AudioCodec.AC3, ours.info.codec, "$case")
            assertEquals(case.rate, ours.format.sampleRate, "$case")
            assertEquals(case.channels, ours.format.channels, "$case")
            assertEquals(theirs.size, ours.samples.size, "$case: length")
            val (worst, snr) = compare(ours.samples, theirs)
            assertTrue(worst < ROUNDING_BOUND, "$case: largest difference $worst, ${snr.toInt()} dB")

            // The same syncframes in other containers decode to the same samples, bit for bit.
            // ffmpeg's MP4 muxer writes an edit list that ends playback a few frames early,
            // to the millisecond, so the MP4 file holds a prefix of them, less than a syncframe short.
            for (extension in listOf("mka", "mp4")) {
                val copy = temp("cleared_${case.name}.$extension")
                run(listOf(ffmpeg, "-y", "-v", "error", "-i", clearedRaw.path, "-c", "copy", copy.path))
                val inContainer = AudioKodec.decode(copy.readBytes())
                assertEquals(AudioCodec.AC3, inContainer.info.codec, "$case in $extension")
                if (extension == "mka") {
                    assertContentEquals(ours.samples, inContainer.samples, "$case in $extension")
                } else {
                    val missing = ours.samples.size - inContainer.samples.size
                    assertTrue(missing in 0 until 1536 * case.channels, "$case in $extension: $missing samples short")
                    assertContentEquals(ours.samples.copyOf(inContainer.samples.size), inContainer.samples, "$case in $extension")
                }
            }
        }
    }

    @Test
    fun filesWhoseDitherFlagsAreSetHaveFfmpegsEnergyInEveryBlock() {
        val ffmpeg = ffmpeg()
        for (case in listOf(Case("stereo", 2, 48_000, 96), Case("5.1", 6, 48_000, 256))) {
            val file = temp("dither_${case.name}.ac3")
            encode(ffmpeg, file, case, noise = true)
            val ours = AudioKodec.decode(file.readBytes())
            val theirs = decodeWithFfmpeg(ffmpeg, file)
            assertEquals(theirs.size, ours.samples.size, "$case: length")
            // The noise differs, so the samples do too: this is what shows the flags were set and honoured.
            assertTrue(compare(ours.samples, theirs).second < 60.0, "$case: no dither was applied")
            val channels = case.channels
            for (block in 0 until ours.frames / 1536) {
                for (c in 0 until channels) {
                    var a = 0.0
                    var b = 0.0
                    for (f in block * 1536 until block * 1536 + 1536) {
                        a += ours.samples[f * channels + c].toDouble().let { it * it }
                        b += theirs[f * channels + c].toDouble().let { it * it }
                    }
                    if (b < 1e-6) continue
                    val difference = abs(10 * log10(a / b))
                    assertTrue(difference < 0.5, "$case, syncframe $block, channel $c: energy differs by $difference dB")
                }
            }
        }
    }

    @Test
    fun everyChannelComesFromTheSpeakerItWasRecordedFor() {
        val ffmpeg = ffmpeg()
        // A different tone in every channel, the LFE's low enough to pass its band limit. The
        // layouts list their speakers in AudioKodec's order, so channel c carries tone c. The
        // tone is read from the zero crossings, since the encoder's delay puts a signal's phase
        // anywhere against the source.
        for ((layout, channels) in listOf("3.0" to 3, "4.0" to 4, "quad" to 4, "5.0" to 5, "5.1" to 6, "3.1" to 4, "2.1" to 3)) {
            val tones = listOf(300, 500, 700, 900, 1100, 1300).take(channels).toMutableList()
            if (layout.endsWith(".1")) tones[if (layout == "5.1") 3 else channels - 1] = 60
            val generator = "aevalsrc=${tones.joinToString("|") { "0.4*sin(2*PI*$it*t)" }}:s=48000:d=2:c=$layout"
            val file = temp("tones_$layout.ac3")
            run(listOf(ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", generator, "-c:a", "ac3", "-b:a", "448k", file.path))
            val decoded = AudioKodec.decode(file.readBytes())
            assertEquals(channels, decoded.format.channels, layout)
            for (c in 0 until channels) {
                var crossings = 0
                for (f in 24_000 until 72_000) {
                    if (decoded.samples[(f - 1) * channels + c] < 0f && decoded.samples[f * channels + c] >= 0f) crossings++
                }
                assertEquals(tones[c].toDouble(), crossings.toDouble(), 2.0, "$layout: channel $c (${decoded.format.channelLayout.channels[c]})")
            }
        }
    }

    @Test
    fun theLengthIsEverySyncframeAndSeekingLandsOnTheContinuousDecode() {
        val ffmpeg = ffmpeg()
        val raw = temp("seek.ac3")
        encode(ffmpeg, raw, Case("5.1", 6, 48_000, 384))
        for (extension in listOf("ac3", "mka", "mp4")) {
            val file = temp("seek.$extension")
            if (extension != "ac3") run(listOf(ffmpeg, "-y", "-v", "error", "-i", raw.path, "-c", "copy", file.path))
            val bytes = file.readBytes()
            val info = AudioKodec.probe(bytes)
            val whole = AudioKodec.decode(bytes).samples
            assertEquals(info.durationFrames, (whole.size / 6).toLong(), "$extension: probed length")
            assertEquals(0L, info.durationFrames!! % 1536, "$extension: whole syncframes")
            for (target in listOf(0L, 1L, 1535L, 1536L, 50_000L, info.durationFrames!! - 1)) {
                AudioKodec.open(bytes).use { decoder ->
                    assertEquals(target, decoder.seek(target))
                    val frame = decoder.nextFrame()!!
                    val count = minOf(frame.frameCount, 500)
                    for (i in 0 until count * 6) {
                        assertEquals(whole[(target * 6).toInt() + i], frame.samples[i], "$extension: seek to $target, sample $i")
                    }
                }
            }
        }
    }

    @Test
    fun enhancedAc3IsRefusedByName() {
        val ffmpeg = ffmpeg()
        for (extension in listOf("eac3", "mka", "mp4")) {
            val file = temp("enhanced.$extension")
            run(listOf(ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1", "-c:a", "eac3", file.path))
            val failure = assertFailsWith<UnsupportedFormatException>(extension) { AudioKodec.open(file.readBytes()) }
            assertTrue("Enhanced AC-3" in failure.message.orEmpty(), "$extension: ${failure.message}")
        }
    }

    // Helpers.

    private class Case(val layout: String, val channels: Int, val rate: Int, val kbps: Int, val extra: List<String> = emptyList()) {
        val name: String get() = "${layout}_${rate}_${kbps}_${extra.joinToString("").replace("-", "")}"
        override fun toString() = "$layout at $rate Hz, $kbps kb/s${if (extra.isEmpty()) "" else " $extra"}"
    }

    /**
     * Clears every dither flag of every block and repairs the two CRCs.
     *
     * crc1 is chosen so that the register is zero at the 5/8 point. The
     * register is linear in the bits it is fed, so the crc1 that zeroes it
     * is found from the register's response to each of crc1's sixteen bits.
     * crc2 is then the register over the rest of the syncframe, which
     * leaves it zero at the end.
     */
    private fun clearDither(bytes: ByteArray): ByteArray {
        val out = bytes.copyOf()
        var at = 0
        while (at + Ac3Frame.HEADER_BYTES <= bytes.size) {
            val frame = Ac3Frame.parse(bytes, at) ?: break
            val length = frame.frameBytes
            val positions = ArrayList<Pair<Int, Int>>()
            Ac3Decoder(frame, tolerant = false, probe = Ac3Probe(onDitherFlags = { position, count -> positions.add(position to count) }))
                .decode(bytes, at, length, FloatArray(1536 * frame.channels))
            for ((position, count) in positions) {
                for (k in 0 until count) {
                    val bit = position + k
                    out[at + (bit ushr 3)] = (out[at + (bit ushr 3)].toInt() and (0x80 ushr (bit and 7)).inv()).toByte()
                }
            }
            val words = length / 2
            val fiveEighths = 2 * ((words shr 1) + (words shr 3))
            fun register(crc1: Int): Int {
                out[at + 2] = (crc1 ushr 8).toByte()
                out[at + 3] = crc1.toByte()
                return crc(out, at + 2, at + fiveEighths)
            }
            val base = register(0)
            val response = IntArray(16) { register(1 shl it) xor base }
            val crc1 = (0 until 65_536).first { candidate ->
                var register = base
                for (i in 0 until 16) if (candidate and (1 shl i) != 0) register = register xor response[i]
                register == 0
            }
            register(crc1)
            val crc2 = crc(out, at + 2, at + length - 2)
            out[at + length - 2] = (crc2 ushr 8).toByte()
            out[at + length - 1] = crc2.toByte()
            assertEquals(0, crc(out, at + 2, at + length), "the repaired syncframe's CRC")
            at += length
        }
        return out
    }

    /** The register of x^16 + x^15 + x^2 + 1 over [from] until [to], from zero, most significant bit first. */
    private fun crc(bytes: ByteArray, from: Int, to: Int): Int {
        var register = 0
        for (i in from until to) {
            register = register xor ((bytes[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                register = if (register and 0x8000 != 0) (register shl 1) xor 0x8005 else register shl 1
                register = register and 0xFFFF
            }
        }
        return register
    }

    private fun compare(ours: FloatArray, theirs: FloatArray): Pair<Double, Double> {
        var worst = 0.0
        var noise = 0.0
        var signal = 0.0
        for (i in theirs.indices) {
            val a = ours[i].toDouble()
            val b = theirs[i].toDouble()
            worst = max(worst, abs(a - b))
            noise += (a - b) * (a - b)
            signal += b * b
        }
        return worst to if (noise == 0.0) 300.0 else 10 * log10(signal / noise)
    }

    private fun encode(ffmpeg: String, file: File, case: Case, noise: Boolean = false) {
        run(
            listOf(ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", signal(case.rate, case.layout, case.channels, noise), "-c:a", "ac3", "-b:a", "${case.kbps}k") +
                case.extra + file.path,
        )
    }

    /**
     * Tones, a sweep and clicks, different in every channel, so that blocks
     * switch and stereo has something to share. With [noise], bursts of noise
     * too, which leave mantissas no bits.
     */
    private fun signal(rate: Int, layout: String, channels: Int, noise: Boolean): String {
        val expressions = (0 until channels).map { c ->
            val tone = 220 * (c + 2)
            "0.25*sin(2*PI*$tone*t)+0.15*sin(2*PI*(500+${1500 + 300 * c}*t)*t)" +
                (if (noise) "+0.3*(random(${c + 1})-0.5)*lt(mod(t\\,0.6)\\,0.1)" else "") +
                "+0.6*lt(mod(t+${0.05 * c}\\,0.37)\\,0.002)"
        }
        return "aevalsrc=${expressions.joinToString("|")}:s=$rate:d=$SECONDS:c=$layout"
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
        assumeTrue("ffmpeg is required for the AC-3 oracle", ffmpeg != null)
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

        /** Float rounding: the two decoders sum the same few hundred terms in different orders. */
        const val EXACT_BOUND = 1e-6

        /** Three times the largest difference measured between the plain decode and ffmpeg's, 1.5e-5; the class comment works out the ceiling. */
        const val ROUNDING_BOUND = 5e-5

        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-ac3-oracle").also { it.mkdirs() }

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

package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.codec.G726Codec
import io.github.yuroyami.audiokodec.internal.codec.G726Decoder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks G.722 and G.726 in the WAV and AU files ffmpeg writes.
 *
 * G.722 decodes to exactly ffmpeg's samples, in all three modes. ffmpeg's
 * G.726 does not follow the Recommendation (see `docs/g722-g726.md`), so its
 * samples are no oracle for G.726: the decoder itself is checked against the
 * ITU's test sequences by [G726ConformanceTest], and here each file must
 * decode to the samples of that decoder run over the code words as ffmpeg
 * packs them, unpacked independently. At 16 to 32 kbit/s, where ffmpeg's
 * departures are small, the decode must also stay close to ffmpeg's, which a
 * wrong bit order or rate would not.
 */
class G72xOracleTest {

    @Test
    fun g722FilesDecodeToExactlyFfmpegsSamplesInEveryMode() {
        val ffmpeg = ffmpeg()
        for ((name, expression) in signals) {
            for (container in listOf("wav", "au")) {
                val file = File(workDirectory, "${name}_g722.$container")
                run(listOf(ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "aevalsrc=$expression:s=16000:d=1.2345", "-c:a", "adpcm_g722", file.path))
                val bytes = file.readBytes()
                val info = AudioKodec.probe(bytes)
                assertEquals(AudioCodec.G722, info.codec, "$name $container")
                assertEquals(16_000, info.format.sampleRate, "$name $container")
                for ((mode, codeword) in listOf(1 to 8, 2 to 7, 3 to 6)) {
                    val reference = ffmpegDecode(ffmpeg, file, listOf("-bits_per_codeword", "$codeword"))
                    val decoded = AudioKodec.decode(bytes, DecodeOptions(g722Mode = mode)).samples
                    compare(reference, decoded, "$name $container, mode $mode")
                }
            }
        }
    }

    @Test
    fun g726WavFilesDecodeToTheirCodeWordsInFfmpegsPacking() {
        val ffmpeg = ffmpeg()
        for ((name, expression) in signals) {
            for (bits in 2..5) {
                // A length that leaves the last 24 and 40 kbit/s block part full.
                val file = File(workDirectory, "${name}_g726_$bits.wav")
                run(
                    listOf(
                        ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "aevalsrc=$expression:s=8000:d=1.2345",
                        "-c:a", "adpcm_g726", "-code_size", "$bits", file.path,
                    ),
                )
                val bytes = file.readBytes()
                val info = AudioKodec.probe(bytes)
                assertEquals(AudioCodec.G726, info.codec, "$name, $bits bits")
                assertEquals(9_876L, info.durationFrames, "$name, $bits bits: the fact chunk's length")
                val decoded = AudioKodec.decode(bytes).samples
                assertEquals(9_876, decoded.size)
                val expected = decodeCodes(unpack(dataChunk(bytes, "data", littleEndian = true), bits, lsbFirst = false), bits)
                compareExactly(expected.copyOf(9_876), decoded, "$name WAV, $bits bits")
                if (bits < 5 && name in agreeing) {
                    val agreement = snr(ffmpegDecode(ffmpeg, file, emptyList()), decoded)
                    assertTrue(agreement > 15.0, "$name WAV, $bits bits: ${"%.1f".format(agreement)} dB from ffmpeg's decode")
                }
            }
        }
    }

    @Test
    fun g726AuFilesDecodeToTheirCodeWordsInSunsPacking() {
        val ffmpeg = ffmpeg()
        for ((name, expression) in signals) {
            // ffmpeg labels every rate encoding 23, which is Sun's 32 kbit/s G.721; the
            // other rates' encodings, 25 and 26, are written here around ffmpeg's code words.
            for ((bits, encoding) in listOf(4 to 23, 3 to 25, 5 to 26)) {
                val raw = File(workDirectory, "${name}_g726le_$bits.raw")
                run(
                    listOf(
                        ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "aevalsrc=$expression:s=8000:d=1.2",
                        "-c:a", "adpcm_g726le", "-code_size", "$bits", "-f", "g726le", raw.path,
                    ),
                )
                val payload = raw.readBytes()
                val bytes = au(payload, encoding, 8_000)
                if (bits == 4) {
                    val written = File(workDirectory, "${name}_g726le.au")
                    run(listOf(ffmpeg, "-y", "-v", "error", "-f", "g726le", "-code_size", "4", "-sample_rate", "8000", "-i", raw.path, "-c", "copy", written.path))
                    assertEquals(AudioCodec.G726, AudioKodec.probe(written.readBytes()).codec)
                    compareExactly(AudioKodec.decode(written.readBytes()).samples, AudioKodec.decode(bytes).samples, "$name, ffmpeg's AU file")
                }
                val info = AudioKodec.probe(bytes)
                assertEquals(AudioCodec.G726, info.codec, "$name, encoding $encoding")
                val decoded = AudioKodec.decode(bytes).samples
                compareExactly(decodeCodes(unpack(payload, bits, lsbFirst = true), bits), decoded, "$name AU, encoding $encoding")
                if (bits < 5 && name in agreeing) {
                    val reference = ffmpegDecode(ffmpeg, raw, emptyList(), listOf("-f", "g726le", "-code_size", "$bits", "-sample_rate", "8000"))
                    val agreement = snr(reference, decoded)
                    assertTrue(agreement > 15.0, "$name AU, $bits bits: ${"%.1f".format(agreement)} dB from ffmpeg's decode")
                }
            }
        }
    }

    @Test
    fun theItuSequencesDecodeThroughBothContainers() {
        val dir = File(System.getProperty("user.dir"))
        val root = listOf(dir, dir.parentFile).map { File(it, "reference/g72x/g726-appii") }.firstOrNull { File(it, "DISK1/INPUT/I32").isFile }
        assumeTrue("the G.726 Appendix II test sequences are required, under reference/g72x/g726-appii", root != null)
        for ((bits, encoding) in listOf(2 to 0, 3 to 25, 4 to 23, 5 to 26)) {
            val rate = bits * 8
            val hex = File(root, "DISK1/RESET/$rate/RN${rate}FM.I").readText().filter { it.isLetterOrDigit() }
            val codes = IntArray(hex.length / 2 - 1) { hex.substring(2 * it, 2 * it + 2).toInt(16) }
            val expected = decodeCodes(codes, bits)
            compareExactly(expected, AudioKodec.decode(g726Wav(pack(codes, bits, lsbFirst = false), bits, codes.size)).samples, "$rate kbit/s WAV")
            if (encoding != 0) {
                compareExactly(expected, AudioKodec.decode(au(pack(codes, bits, lsbFirst = true), encoding, 8_000)).samples, "$rate kbit/s AU")
            }
        }
    }

    @Test
    fun aSeekLandsOnExactlyTheContinuousDecode() {
        val ffmpeg = ffmpeg()
        val files = buildList {
            for (bits in 2..5) {
                val file = File(workDirectory, "seek_g726_$bits.wav")
                run(listOf(ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "aevalsrc=${signals.first().second}:s=8000:d=3", "-c:a", "adpcm_g726", "-code_size", "$bits", file.path))
                add(file.readBytes())
            }
            val file = File(workDirectory, "seek_g722.wav")
            run(listOf(ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "aevalsrc=${signals.first().second}:s=16000:d=3", "-c:a", "adpcm_g722", file.path))
            add(file.readBytes())
        }
        for (bytes in files) {
            val whole = AudioKodec.decode(bytes).samples
            for (reuse in listOf(false, true)) {
                AudioKodec.open(bytes, DecodeOptions(reuseBuffers = reuse)).use { decoder ->
                    // Backwards, forwards from where the decoder is, and inside a block of eight code words.
                    for (target in listOf(20_001L, 3L, 0L, 9_000L, 9_005L, 23_997L, 5L, 17_003L)) {
                        assertEquals(target, decoder.seek(target))
                        var at = target.toInt()
                        repeat(3) {
                            val frame = decoder.nextFrame() ?: return@repeat
                            assertEquals(at.toLong(), frame.positionFrames)
                            for (i in 0 until frame.frameCount) {
                                if (whole[at + i] != frame.samples[i]) throw AssertionError("seek to $target: sample ${at + i} differs")
                            }
                            at += frame.frameCount
                        }
                    }
                }
            }
            // Without slow seeking, only a seek the decoder can reach by reading on is allowed.
            AudioKodec.open(bytes, DecodeOptions(allowSlowSeek = false)).use { decoder ->
                assertEquals(9_000L, decoder.seek(9_000L))
                assertEquals(whole[9_000], decoder.nextFrame()!!.samples[0])
                assertFailsWith<UnsupportedFeatureException> { decoder.seek(100L) }
                assertEquals(0L, decoder.seek(0L))
                assertEquals(whole[0], decoder.nextFrame()!!.samples[0])
            }
        }
    }

    // Helpers.

    /**
     * The signals on which ffmpeg's G.726 stays close to the Recommendation.
     * Its departures compound under overload, so the clipped signal is left
     * out, and silence has no level to measure against.
     */
    private val agreeing = setOf("speech", "noise", "tones")

    private val signals = listOf(
        "speech" to "0.3*sin(2*PI*180*t*(1+0.3*sin(2*PI*3*t)))*(0.6+0.4*sin(2*PI*0.7*t))+0.05*(random(1)-0.5)+0.2*lt(mod(t\\,0.5)\\,0.1)*sin(2*PI*900*t)",
        "silence" to "0",
        "loud" to "1.3*sin(2*PI*300*t)+0.7*sin(2*PI*1700*t)",
        "noise" to "0.9*(random(3)-0.5)",
        // Two tones in turn, held long enough for the tone detector and its transition reset.
        "tones" to "0.5*sin(2*PI*1000*t)*lt(mod(t\\,0.6)\\,0.3)+0.5*sin(2*PI*2200*t)*gte(mod(t\\,0.6)\\,0.3)",
    )

    private fun decodeCodes(codes: IntArray, bits: Int): FloatArray {
        val decoder = G726Decoder(bits)
        return FloatArray(codes.size) { G726Codec.uniform(decoder.decode(codes[it])) / 8192f }
    }

    /** Code words from bytes: a byte's first at its most significant bit, or at its least. */
    private fun unpack(bytes: ByteArray, bits: Int, lsbFirst: Boolean): IntArray {
        val out = ArrayList<Int>()
        for (index in 0 until bytes.size * 8 / bits) {
            var code = 0
            for (k in 0 until bits) {
                val bit = index * bits + k
                val byte = bytes[bit / 8].toInt() and 0xFF
                code = if (lsbFirst) code or (((byte shr (bit % 8)) and 1) shl k) else (code shl 1) or ((byte shr (7 - bit % 8)) and 1)
            }
            out += code
        }
        return out.toIntArray()
    }

    private fun pack(codes: IntArray, bits: Int, lsbFirst: Boolean): ByteArray {
        val out = ByteArray((codes.size * bits + 7) / 8)
        for ((index, code) in codes.withIndex()) {
            for (k in 0 until bits) {
                val bit = index * bits + k
                val value = if (lsbFirst) (code shr k) and 1 else (code shr (bits - 1 - k)) and 1
                val shift = if (lsbFirst) bit % 8 else 7 - bit % 8
                out[bit / 8] = (out[bit / 8].toInt() or (value shl shift)).toByte()
            }
        }
        return out
    }

    private fun g726Wav(payload: ByteArray, bits: Int, frames: Int): ByteArray {
        val pad = payload.size and 1
        val out = ByteBuffer.allocate(12 + 26 + 12 + 8 + payload.size + pad).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(4 + 26 + 12 + 8 + payload.size + pad).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(18).putShort(0x45).putShort(1).putInt(8000).putInt(1000 * bits)
        out.putShort((if (bits % 2 == 0) 1 else bits).toShort()).putShort(bits.toShort()).putShort(0)
        out.put("fact".toByteArray()).putInt(4).putInt(frames)
        out.put("data".toByteArray()).putInt(payload.size).put(payload)
        return out.array()
    }

    private fun au(payload: ByteArray, encoding: Int, rate: Int): ByteArray =
        ByteBuffer.allocate(24 + payload.size)
            .put(".snd".toByteArray()).putInt(24).putInt(payload.size).putInt(encoding).putInt(rate).putInt(1)
            .put(payload).array()

    private fun dataChunk(bytes: ByteArray, id: String, littleEndian: Boolean): ByteArray {
        var at = 12
        val order = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        while (String(bytes, at, 4) != id) at += 8 + ByteBuffer.wrap(bytes, at + 4, 4).order(order).int
        val size = ByteBuffer.wrap(bytes, at + 4, 4).order(order).int
        return bytes.copyOfRange(at + 8, at + 8 + size)
    }

    private fun compare(reference: ShortArray, decoded: FloatArray, what: String) {
        assertEquals(reference.size, decoded.size, "$what: length")
        for (i in reference.indices) {
            if (reference[i] / 32768f != decoded[i]) throw AssertionError("$what: sample $i is ${decoded[i] * 32768f}, not ${reference[i]}")
        }
    }

    private fun compareExactly(expected: FloatArray, decoded: FloatArray, what: String) {
        assertEquals(expected.size, decoded.size, "$what: length")
        for (i in expected.indices) {
            if (expected[i] != decoded[i]) throw AssertionError("$what: sample $i is ${decoded[i]}, not ${expected[i]}")
        }
    }

    /** How far [decoded] is from [reference], as a signal to difference ratio in decibels. */
    private fun snr(reference: ShortArray, decoded: FloatArray): Double {
        var signal = 0.0
        var noise = 0.0
        // ffmpeg ignores a WAV file's fact chunk and decodes the padding of a short last block.
        for (i in decoded.indices) {
            val r = reference[i].toDouble()
            val d = r - (decoded[i] * 32768.0).coerceIn(-32768.0, 32767.0)
            signal += r * r
            noise += d * d
        }
        return 10 * log10(signal / noise.coerceAtLeast(1.0))
    }

    private fun ffmpegDecode(ffmpeg: String, file: File, decoderOptions: List<String>, inputOptions: List<String> = emptyList()): ShortArray {
        val out = File(workDirectory, file.name + ".pcm")
        run(listOf(ffmpeg, "-y", "-v", "error") + decoderOptions + inputOptions + listOf("-i", file.path, "-f", "s16le", out.path))
        val buffer = ByteBuffer.wrap(out.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun ffmpeg(): String {
        val ffmpeg = findOnPath("ffmpeg")
        assumeTrue("ffmpeg is required for the G.722 and G.726 oracle", ffmpeg != null)
        return ffmpeg!!
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS), "${command.first()} did not finish")
        if (process.exitValue() != 0) throw AssertionError("${File(command.first()).name} failed:\n${output.take(2000)}")
    }

    private companion object {
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-g72x-oracle").also { it.mkdirs() }

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

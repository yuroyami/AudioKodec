package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagField
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks the True Audio decoder against two encoders and two decoders: the
 * format authors' own `tta` tool, libtta 2.3, built under `reference/tta`
 * (see `reference/REFERENCES.md`) or found on the path, and ffmpeg.
 *
 * True Audio is lossless, so every file either encoder writes, at 8, 16 and
 * 24 bits with one to eight channels and at sample rates that give frames
 * of different lengths, must decode to exactly the integers that went in,
 * exactly what `tta -d` writes and exactly what ffmpeg decodes it to.
 */
class TtaOracleTest {

    @Test
    fun everyDepthAndChannelCountDecodesToExactlyWhatWentIn() {
        val tta = tta()
        val cases = mutableListOf<Case>()
        for (bits in listOf(8, 16, 24)) for (channels in listOf(1, 2)) cases += Case(bits, channels)
        for (channels in listOf(3, 4, 5, 6, 8)) cases += Case(16, channels)
        cases += Case(24, 6)
        cases += Case(20, 2)
        cases += Case(20, 1, extremes = true)
        for (rate in listOf(8_000, 22_050, 48_000, 96_000)) cases += Case(16, 2, rate)
        cases += Case(16, 2, silent = true)
        cases += Case(16, 2, extremes = true)
        cases += Case(24, 2, extremes = true)
        cases += Case(8, 2, extremes = true)
        for (case in cases) {
            val input = signal(case)
            val wav = temp("${case.stem}.wav")
            wav.writeBytes(wave(input, case))
            // The reference tool takes 16 to 24 bits and up to six channels; ffmpeg's
            // encoder takes 8, 16 and 24 bits and up to eight.
            val encoders = listOfNotNull(
                "tta".takeIf { case.bits >= 16 && case.channels <= 6 },
                "ffmpeg".takeIf { case.bits % 8 == 0 },
            )
            for (encoder in encoders) {
                val file = temp("${case.stem}_$encoder.tta")
                file.delete()
                if (encoder == "tta") {
                    run(listOf(tta, "-e", wav.path, file.path))
                } else {
                    run(listOf(ffmpeg(), "-y", "-v", "error", "-i", wav.path, "-c:a", "tta", file.path))
                }
                val name = "${case.name}, encoded by $encoder"
                val bytes = file.readBytes()
                val info = AudioKodec.probe(bytes)
                assertEquals(AudioContainer.TTA, info.container, name)
                assertEquals(AudioCodec.TTA, info.codec, name)
                assertEquals(case.channels, info.format.channels, name)
                assertEquals(case.rate, info.format.sampleRate, name)
                assertEquals(case.frames.toLong(), info.durationFrames, name)
                assertEquals(case.bits, info.bitDepth, name)
                val ints = decodeInts(bytes)
                assertSame(input, ints, "$name, AudioKodec against the input")
                // Both decoders give the integers as WAV stores them, high-aligned.
                val stored = IntArray(input.size) { input[it] shl case.padding }
                // The reference tool decodes only what it can encode.
                if (case.bits >= 16 && case.channels <= 6) assertSame(stored, ttaDecode(tta, file, case), "$name, tta -d against the input")
                assertSame(stored, ffmpegDecode(file, case), "$name, ffmpeg against the input")
                val floats = AudioKodec.decode(bytes).samples
                val scale = 1.0f / (1 shl (case.bits - 1))
                for (i in input.indices) if (floats[i] != input[i] * scale) throw AssertionError("$name: float sample $i")
            }
        }
    }

    @Test
    fun seekingLandsOnTheSamplesAContinuousDecodeGives() {
        val tta = tta()
        val case = Case(16, 2, frames = 400_000)
        val wav = temp("${case.stem}.wav")
        wav.writeBytes(wave(signal(case), case))
        val file = temp("${case.stem}.tta")
        file.delete()
        run(listOf(tta, "-e", wav.path, file.path))
        val bytes = file.readBytes()
        val whole = AudioKodec.decode(bytes).samples
        // A frame at 44.1 kHz is 46080 samples.
        for (target in listOf(0L, 1L, 46_079L, 46_080L, 46_081L, 300_000L, 399_999L)) {
            AudioKodec.open(bytes).use { decoder ->
                assertEquals(target, decoder.seek(target))
                val frame = decoder.nextFrame()!!
                for (i in 0 until minOf(frame.frameCount, 300) * 2) {
                    assertEquals(whole[(target * 2).toInt() + i], frame.samples[i], "seek to $target, sample $i")
                }
            }
        }
    }

    @Test
    fun tagsDamageAndPasswordsAreHandled() {
        val tta = tta()
        val case = Case(16, 2)
        val wav = temp("${case.stem}.wav")
        wav.writeBytes(wave(signal(case), case))

        val tagged = temp("${case.stem}_tagged.tta")
        tagged.delete()
        run(listOf(ffmpeg(), "-y", "-v", "error", "-i", wav.path, "-c:a", "tta", "-metadata", "title=True oracle", tagged.path))
        val bytes = tagged.readBytes()
        assertEquals("True oracle", AudioKodec.readTags(bytes)[TagField.TITLE])
        assertEquals(case.frames, AudioKodec.decode(bytes).frames.toInt())

        val damaged = bytes.copyOf()
        damaged[damaged.size / 2] = (damaged[damaged.size / 2].toInt() xor 0x10).toByte()
        assertFailsWith<CorruptStreamException> { AudioKodec.decode(damaged) }

        val locked = temp("${case.stem}_locked.tta")
        locked.delete()
        run(listOf(tta, "-ep", "secret", wav.path, locked.path))
        val failure = assertFailsWith<UnsupportedFeatureException> { AudioKodec.open(locked.readBytes()) }
        assertEquals("password-protected True Audio", failure.feature)
    }

    // Helpers.

    private class Case(
        val bits: Int,
        val channels: Int,
        val rate: Int = 44_100,
        val silent: Boolean = false,
        val extremes: Boolean = false,
        val frames: Int = 100_000,
    ) {
        val name = "$bits bits, $channels ch, $rate Hz${if (silent) ", silent" else ""}${if (extremes) ", extremes" else ""}"
        val stem = name.replace(Regex("[^A-Za-z0-9]+"), "_") + "_$frames"
        val bytes = (bits + 7) / 8
        val padding = bytes * 8 - bits
    }

    /** The test signal for [case] as integers at its depth, from ffmpeg's signal generator. */
    private fun signal(case: Case): IntArray {
        val raw = temp("${case.stem}.s32")
        val expressions = (0 until case.channels).map { c ->
            when {
                case.silent -> "0"
                case.extremes -> "if(lt(mod(t*${50 + 10 * c}\\,1)\\,0.5)\\,1\\,-1)*gt(mod(t\\,0.2)\\,0.1)+0.2*(random(${c + 1})-0.5)*lte(mod(t\\,0.2)\\,0.1)"
                else -> "0.3*sin(2*PI*${220 * (c + 2)}*t)+0.2*sin(2*PI*(100+${700 + 150 * c}*t)*t)+0.15*(random(${c + 1})-0.5)" +
                    "+0.3*lt(mod(t+${0.03 * c}\\,0.31)\\,0.004)"
            }
        }
        run(listOf(ffmpeg(), "-y", "-v", "error", "-f", "lavfi", "-i", "aevalsrc=${expressions.joinToString("|")}:s=${case.rate}:d=${case.frames.toDouble() / case.rate}", "-f", "s32le", "-c:a", "pcm_s32le", raw.path))
        val buffer = ByteBuffer.wrap(raw.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val full = IntArray(buffer.remaining()).also { buffer.get(it) }
        assertEquals(case.frames * case.channels, full.size, "${case.name}: signal length")
        return IntArray(full.size) { i -> full[i] shr (32 - case.bits) }
    }

    /** A WAV file of [samples]; above two channels, WAVE_FORMAT_EXTENSIBLE with the standard mask. */
    private fun wave(samples: IntArray, case: Case): ByteArray {
        val bytes = case.bytes
        val extensible = case.channels > 2
        val data = ByteBuffer.allocate(samples.size * bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            val value = sample shl case.padding
            when (bytes) {
                1 -> data.put((value + 128).toByte())
                2 -> data.putShort(value.toShort())
                else -> { data.put(value.toByte()); data.put((value shr 8).toByte()); data.put((value shr 16).toByte()) }
            }
        }
        val fmtSize = if (extensible) 40 else 16
        val out = ByteBuffer.allocate(12 + 8 + fmtSize + 8 + data.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(out.capacity() - 8).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(fmtSize)
        out.putShort((if (extensible) 0xFFFE else 1).toShort()).putShort(case.channels.toShort()).putInt(case.rate)
            .putInt(case.rate * case.channels * bytes).putShort((case.channels * bytes).toShort())
            .putShort((if (extensible) bytes * 8 else case.bits).toShort())
        if (extensible) {
            val mask = mapOf(3 to 0x7, 4 to 0x33, 5 to 0x37, 6 to 0x3F, 8 to 0x63F)[case.channels] ?: 0
            out.putShort(22).putShort(case.bits.toShort()).putInt(mask)
            // KSDATAFORMAT_SUBTYPE_PCM.
            out.putInt(1).put(byteArrayOf(0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71))
        }
        out.put("data".toByteArray()).putInt(data.capacity()).put(data.array())
        return out.array()
    }

    /** What `tta -d` decodes [file] to. */
    private fun ttaDecode(tta: String, file: File, case: Case): IntArray {
        val out = temp(file.nameWithoutExtension + ".tta_out.wav")
        out.delete()
        run(listOf(tta, "-d", file.path, out.path))
        return waveSamples(out.readBytes(), case)
    }

    /** What ffmpeg decodes [file] to, at the file's own depth. */
    private fun ffmpegDecode(file: File, case: Case): IntArray {
        val out = temp(file.nameWithoutExtension + ".ffmpeg_out.wav")
        val codec = when (case.bits) { 8 -> "pcm_u8"; 16 -> "pcm_s16le"; else -> "pcm_s24le" }
        run(listOf(ffmpeg(), "-y", "-v", "error", "-i", file.path, "-c:a", codec, "-rf64", "never", out.path))
        return waveSamples(out.readBytes(), case)
    }

    private fun waveSamples(bytes: ByteArray, case: Case): IntArray {
        var at = 12
        while (String(bytes, at, 4) != "data") at += 8 + ByteBuffer.wrap(bytes, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val size = ByteBuffer.wrap(bytes, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val per = case.bytes
        val start = at + 8
        return IntArray(size / per) { i ->
            val p = start + i * per
            when (per) {
                1 -> (bytes[p].toInt() and 0xFF) - 128
                2 -> (bytes[p].toInt() and 0xFF) or (bytes[p + 1].toInt() shl 8)
                else -> (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p + 2].toInt() shl 16)
            }
        }
    }

    private fun assertSame(expected: IntArray, actual: IntArray, what: String) {
        assertEquals(expected.size, actual.size, "$what: length")
        for (i in expected.indices) if (expected[i] != actual[i]) throw AssertionError("$what: sample $i is ${actual[i]}, not ${expected[i]}")
    }

    private fun decodeInts(bytes: ByteArray): IntArray {
        AudioKodec.open(bytes).use { decoder ->
            val access = decoder as IntPcmAccess
            val out = ArrayList<Int>()
            while (true) {
                val frame = access.nextIntFrame() ?: break
                for (i in 0 until frame.frameCount * decoder.format.channels) out += frame.samples[i]
            }
            return out.toIntArray()
        }
    }

    private fun ffmpeg(): String = findOnPath("ffmpeg")!!

    private fun tta(): String {
        assumeTrue("ffmpeg is required for the True Audio oracle", findOnPath("ffmpeg") != null)
        val dir = File(System.getProperty("user.dir"))
        val tta = listOf(dir, dir.parentFile).map { File(it, "reference/tta/libtta/build/console/tta") }.firstOrNull { it.canExecute() }?.absolutePath
            ?: findOnPath("tta")
        assumeTrue("the True Audio tool tta is required, built under reference/tta", tta != null)
        return tta!!
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS), "${command.first()} did not finish")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed:\n$output")
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private companion object {
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-tta-oracle").also { it.mkdirs() }

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

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
 * Checks the Monkey's Audio decoder against the official `mac` tool, built
 * from the SDK version the port follows (see `reference/REFERENCES.md`) in
 * `reference/ape/build`, or found on the path.
 *
 * Every compression level is covered, fast, normal, high, extra high and
 * insane, at 8, 16, 24 and 32-bit integer and 32-bit float, mono to 7.1,
 * with silence and identical channels for the encoder's special frames.
 * Monkey's Audio is lossless, so every file must decode to exactly the
 * integers that went in, and exactly what `mac -d` writes.
 */
class ApeOracleTest {

    @Test
    fun everyLevelDecodesToExactlyWhatWentIn() {
        val mac = mac()
        val cases = mutableListOf<Case>()
        for (level in listOf(1000, 2000, 3000, 4000, 5000)) cases += Case(level, 2, Depth.S16)
        for (depth in Depth.entries) for (level in listOf(2000, 4000)) cases += Case(level, 2, depth)
        for (channels in listOf(1, 3, 4, 5, 6, 8)) cases += Case(3000, channels, Depth.S16)
        cases += Case(3000, 6, Depth.S24)
        cases += Case(2000, 1, Depth.S24)
        cases += Case(2000, 2, Depth.S16, silent = true)
        cases += Case(2000, 2, Depth.S16, identical = true)
        for (case in cases) {
            val (input, ape) = encode(mac, case)
            val bytes = ape.readBytes()
            val info = AudioKodec.probe(bytes)
            assertEquals(AudioContainer.APE, info.container, case.name)
            assertEquals(case.channels, info.format.channels, case.name)
            assertEquals(RATE, info.format.sampleRate, case.name)
            assertEquals(FRAMES.toLong(), info.durationFrames, case.name)
            assertEquals(case.depth.bits, info.bitDepth, case.name)
            val reference = macDecode(mac, ape, case)
            assertTrue(input.contentEquals(reference), "${case.name}: mac -d gives back what went in")
            if (case.depth == Depth.F32) {
                val decoded = AudioKodec.decode(bytes).samples
                for (i in input.indices) assertEquals(Float.fromBits(input[i]), decoded[i], "${case.name}: sample $i")
            } else {
                val ints = decodeInts(bytes)
                assertEquals(input.size, ints.size, "${case.name}: length")
                for (i in input.indices) if (input[i] != ints[i]) throw AssertionError("${case.name}: sample $i is ${ints[i]}, not ${input[i]}")
            }
            if (case.channels == 6) assertEquals(ChannelLayout.SURROUND_5_1, info.format.channelLayout, case.name)
        }
    }

    @Test
    fun sevenChannelFilesAreRefusedBecauseTheEncoderDamagesThem() {
        val mac = mac()
        val wav = temp("seven.wav")
        val ape = temp("seven.ape")
        val back = temp("seven_back.wav")
        ape.delete()
        back.delete()
        run(listOf(findOnPath("ffmpeg")!!, "-y", "-v", "error", "-f", "lavfi", "-i", "sine=440:d=0.5", "-ac", "7", "-c:a", "pcm_s16le", wav.path))
        run(listOf(mac, wav.path, ape.path, "-c2000"))
        // The reference fails its own seven-channel file's checksums.
        val process = ProcessBuilder(mac, ape.path, back.path, "-d").redirectErrorStream(true).oracleStart()
        process.inputStream.readBytes()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() != 0, "mac decodes seven channels after all")
        val failure = assertFailsWith<UnsupportedFeatureException> { AudioKodec.open(ape.readBytes()) }
        assertEquals("Monkey's Audio with seven channels", failure.feature)
    }

    @Test
    fun seekingLandsOnTheSamplesAContinuousDecodeGives() {
        val mac = mac()
        val (_, ape) = encode(mac, Case(3000, 2, Depth.S16), frames = 400_000)
        val bytes = ape.readBytes()
        val whole = AudioKodec.decode(bytes).samples
        for (target in listOf(0L, 1L, 294_911L, 294_912L, 300_000L, 399_999L)) {
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
    fun tagsAndDamageAreHandled() {
        val mac = mac()
        val (_, ape) = encode(mac, Case(2000, 2, Depth.S16))
        run(listOf(mac, ape.path, "-t", "Title=Monkey oracle|Artist=AudioKodec"))
        val bytes = ape.readBytes()
        assertEquals("Monkey oracle", AudioKodec.readTags(bytes)[TagField.TITLE])
        assertEquals(FRAMES, AudioKodec.decode(bytes).frames.toInt())
        val damaged = bytes.copyOf()
        damaged[damaged.size / 2] = (damaged[damaged.size / 2].toInt() xor 0x10).toByte()
        assertFailsWith<CorruptStreamException> { AudioKodec.decode(damaged) }
    }

    // Helpers.

    private enum class Depth(val bits: Int, val format: Int) {
        U8(8, 1), S16(16, 1), S24(24, 1), S32(32, 1), F32(32, 3),
    }

    private class Case(val level: Int, val channels: Int, val depth: Depth, val silent: Boolean = false, val identical: Boolean = false) {
        val name = "level $level, $channels ch, $depth${if (silent) ", silent" else ""}${if (identical) ", identical channels" else ""}"
    }

    private fun mac(): String {
        assumeTrue("ffmpeg is required for the Monkey's Audio oracle", findOnPath("ffmpeg") != null)
        val dir = File(System.getProperty("user.dir"))
        val mac = findOnPath("mac")
            ?: listOf(dir, dir.parentFile).map { File(it, "reference/ape/build/mac") }.firstOrNull { it.canExecute() }?.absolutePath
        assumeTrue("the Monkey's Audio tool mac is required, built under reference/ape", mac != null)
        return mac!!
    }

    /** Encodes the test signal for [case] and returns its samples as WAV integers, or float bits, with the `.ape` file. */
    private fun encode(mac: String, case: Case, frames: Int = FRAMES): Pair<IntArray, File> {
        val stem = case.name.replace(Regex("[^A-Za-z0-9]+"), "_") + "_$frames"
        val raw = temp("$stem.s32")
        val expressions = (0 until case.channels).map { c ->
            when {
                case.silent -> "0"
                case.identical -> "0.4*sin(2*PI*440*t)+0.1*(random(1)-0.5)"
                else -> "0.3*sin(2*PI*${220 * (c + 2)}*t)+0.2*sin(2*PI*(100+${700 + 150 * c}*t)*t)+0.15*(random(${c + 1})-0.5)" +
                    "+0.3*lt(mod(t+${0.03 * c}\\,0.31)\\,0.004)"
            }
        }
        run(listOf(findOnPath("ffmpeg")!!, "-y", "-v", "error", "-f", "lavfi", "-i", "aevalsrc=${expressions.joinToString("|")}:s=$RATE:d=${frames.toDouble() / RATE}", "-f", "s32le", "-c:a", "pcm_s32le", raw.path))
        val buffer = ByteBuffer.wrap(raw.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val full = IntArray(buffer.remaining()).also { buffer.get(it) }
        val stored = IntArray(full.size) { i ->
            when (case.depth) {
                Depth.U8 -> full[i] shr 24
                Depth.S16 -> full[i] shr 16
                Depth.S24 -> full[i] shr 8
                Depth.S32 -> full[i]
                Depth.F32 -> (full[i] / 2147483648.0).toFloat().toRawBits()
            }
        }
        val wav = temp("$stem.wav")
        wav.writeBytes(wave(stored, case))
        val ape = temp("$stem.ape")
        ape.delete()
        run(listOf(mac, wav.path, ape.path, "-c${case.level}"))
        return stored to ape
    }

    /** A WAV file of [samples]; above two channels, WAVE_FORMAT_EXTENSIBLE with the standard mask. */
    private fun wave(samples: IntArray, case: Case): ByteArray {
        val bytes = case.depth.bits / 8
        val extensible = case.channels > 2
        val data = ByteBuffer.allocate(samples.size * bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (value in samples) {
            when (bytes) {
                1 -> data.put((value + 128).toByte())
                2 -> data.putShort(value.toShort())
                3 -> { data.put(value.toByte()); data.put((value shr 8).toByte()); data.put((value shr 16).toByte()) }
                else -> data.putInt(value)
            }
        }
        val fmtSize = if (extensible) 40 else 16
        val out = ByteBuffer.allocate(12 + 8 + fmtSize + 8 + data.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(out.capacity() - 8).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(fmtSize)
        out.putShort((if (extensible) 0xFFFE else case.depth.format).toShort()).putShort(case.channels.toShort()).putInt(RATE)
            .putInt(RATE * case.channels * bytes).putShort((case.channels * bytes).toShort()).putShort(case.depth.bits.toShort())
        if (extensible) {
            val mask = mapOf(3 to 0x7, 4 to 0x33, 5 to 0x37, 6 to 0x3F, 8 to 0x63F)[case.channels] ?: 0
            out.putShort(22).putShort(case.depth.bits.toShort()).putInt(mask)
            // KSDATAFORMAT_SUBTYPE_PCM or _IEEE_FLOAT.
            out.putInt(case.depth.format).put(byteArrayOf(0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71))
        }
        out.put("data".toByteArray()).putInt(data.capacity()).put(data.array())
        return out.array()
    }

    /** What `mac -d` decodes [ape] to, as WAV integers or float bits. */
    private fun macDecode(mac: String, ape: File, case: Case): IntArray {
        val out = temp(ape.nameWithoutExtension + ".out.wav")
        out.delete()
        run(listOf(mac, ape.path, out.path, "-d"))
        val bytes = out.readBytes()
        var at = 12
        while (String(bytes, at, 4) != "data") at += 8 + ByteBuffer.wrap(bytes, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val size = ByteBuffer.wrap(bytes, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val per = case.depth.bits / 8
        val start = at + 8
        return IntArray(size / per) { i ->
            val p = start + i * per
            when (per) {
                1 -> (bytes[p].toInt() and 0xFF) - 128
                2 -> (bytes[p].toInt() and 0xFF) or (bytes[p + 1].toInt() shl 8)
                3 -> (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p + 2].toInt() shl 16)
                else -> ByteBuffer.wrap(bytes, p, 4).order(ByteOrder.LITTLE_ENDIAN).int
            }
        }
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

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS), "${command.first()} did not finish")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed:\n$output")
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private companion object {
        const val RATE = 44_100
        const val FRAMES = 100_000

        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-ape-oracle").also { it.mkdirs() }

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

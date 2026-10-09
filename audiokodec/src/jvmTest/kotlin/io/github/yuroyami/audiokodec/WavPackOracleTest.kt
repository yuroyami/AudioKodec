package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagField
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks the WavPack decoder against the reference `wavpack` and `wvunpack`,
 * built from the commit the port follows (see `reference/REFERENCES.md`) in
 * `reference/wavpack/build`, or found on the path.
 *
 * Every mode the encoder has is covered: fast, normal, high and very high,
 * the extra filter searches, forced joint and independent stereo, short
 * blocks, 8 to 32-bit integers, 20-bit audio in 24-bit containers, 32-bit
 * floats, mono to 7.1 and unassigned channels, false stereo and silence.
 * Lossless files must give exactly the integers that went in, and exactly
 * what `wvunpack` gives. Hybrid lossy files are integer arithmetic too, so
 * they must also give exactly `wvunpack`'s integers; against the original
 * they must stay within the noise their bitrate allows, stated per case.
 */
class WavPackOracleTest {

    @Test
    fun losslessFilesDecodeToExactlyWhatWentIn() {
        val tools = tools()
        val cases = listOf(
            Case("16-bit stereo, normal", 2, Depth.S16, emptyList()),
            Case("16-bit stereo, fast", 2, Depth.S16, listOf("-f")),
            Case("16-bit stereo, high", 2, Depth.S16, listOf("-h")),
            Case("16-bit stereo, very high, extra 6", 2, Depth.S16, listOf("-hh", "-x6")),
            Case("16-bit stereo, extra 3, left and right", 2, Depth.S16, listOf("-x3", "-j0")),
            Case("16-bit stereo, mid and side, short blocks", 2, Depth.S16, listOf("-j1", "--blocksize=500")),
            Case("16-bit mono, very high", 1, Depth.S16, listOf("-hh")),
            Case("8-bit stereo", 2, Depth.U8, emptyList()),
            Case("24-bit stereo, high", 2, Depth.S24, listOf("-h")),
            Case("20-bit stereo", 2, Depth.S20, emptyList()),
            Case("32-bit integer stereo", 2, Depth.S32, emptyList()),
            Case("32-bit integer stereo, optimised", 2, Depth.S32, listOf("--optimize-int32", "-hh")),
            Case("32-bit float stereo", 2, Depth.F32, emptyList()),
            Case("32-bit float mono, very high", 1, Depth.F32, listOf("-hh")),
            Case("16-bit 5.1", 6, Depth.S16, emptyList()),
            Case("24-bit 7.1, high", 8, Depth.S24, listOf("-h")),
            Case("16-bit three unassigned channels", 3, Depth.S16, listOf("--channel-order=...")),
            Case("16-bit false stereo", 2, Depth.S16, emptyList(), identicalChannels = true),
            Case("16-bit silence", 2, Depth.S16, emptyList(), silent = true),
        )
        for (case in cases) {
            val (input, wv) = encode(tools, case)
            val bytes = wv.readBytes()
            val info = AudioKodec.probe(bytes)
            assertEquals(AudioContainer.WAVPACK, info.container, case.name)
            assertEquals(AudioCodec.WAVPACK, info.codec, case.name)
            assertEquals(case.channels, info.format.channels, case.name)
            assertEquals(RATE, info.format.sampleRate, case.name)
            assertEquals(FRAMES.toLong(), info.durationFrames, case.name)
            assertEquals(case.depth.bits, info.bitDepth, case.name)
            val reference = wvunpack(tools, wv, case)
            assertEquals(input.size, reference.size, "${case.name}: wvunpack's length")
            assertTrue(input.contentEquals(reference), "${case.name}: wvunpack gives back what went in")
            if (case.depth == Depth.F32) {
                val decoded = AudioKodec.decode(bytes)
                assertEquals(FRAMES, decoded.frames.toInt(), case.name)
                for (i in input.indices) {
                    assertEquals(Float.fromBits(input[i]), decoded.samples[i], "${case.name}: sample $i")
                }
            } else {
                val ints = decodeInts(bytes)
                assertEquals(input.size, ints.size, "${case.name}: length")
                val shift = case.depth.container - case.depth.bits
                for (i in input.indices) {
                    if (input[i] != ints[i] shl shift) throw AssertionError("${case.name}: sample $i is ${ints[i] shl shift}, not ${input[i]}")
                }
                val floats = AudioKodec.decode(bytes).samples
                val scale = 1.0 / (1L shl (case.depth.container - 1))
                for (i in input.indices step 97) assertEquals((input[i] * scale).toFloat(), floats[i], "${case.name}: float sample $i")
            }
            if (case.channels == 6) {
                assertEquals(ChannelLayout.SURROUND_5_1, info.format.channelLayout, case.name)
            }
            if (case.channels == 3) {
                assertTrue(info.format.channelLayout.channels.all { it == AudioChannel.UNKNOWN }, case.name)
            }
        }
    }

    @Test
    fun hybridLossyFilesDecodeAsWvunpackDecodesThem() {
        val tools = tools()
        // Bitrates in bits per sample, and the largest error against the
        // original each one allows, as a fraction of full scale: the
        // encoder's error limit grows as the bitrate falls.
        val cases = listOf(
            Case("16-bit stereo at 4 bits", 2, Depth.S16, listOf("-b4"), lossyBound = 0.05),
            Case("16-bit stereo at 3 bits, no shaping", 2, Depth.S16, listOf("-b3", "-s0"), lossyBound = 0.1),
            Case("16-bit stereo at 6 bits, cross-channel", 2, Depth.S16, listOf("-b6", "--cross-decorr", "-h"), lossyBound = 0.02),
            Case("16-bit mono at 2.5 bits", 1, Depth.S16, listOf("-b2.5"), lossyBound = 0.2),
            Case("24-bit stereo at 5 bits, very high", 2, Depth.S24, listOf("-b5", "-hh"), lossyBound = 0.05),
            Case("16-bit 5.1 at 4 bits", 6, Depth.S16, listOf("-b4"), lossyBound = 0.05),
            Case("32-bit float stereo at 4 bits", 2, Depth.F32, listOf("-b4"), lossyBound = 0.05),
        )
        for (case in cases) {
            val (input, wv) = encode(tools, case)
            val bytes = wv.readBytes()
            val reference = wvunpack(tools, wv, case)
            val decoded = AudioKodec.decode(bytes)
            assertEquals(FRAMES, decoded.frames.toInt(), case.name)
            assertEquals(null, (AudioKodec.open(bytes).use { it as? IntPcmAccess }), "${case.name}: lossy audio has no exact integers")
            var worst = 0.0
            val scale = 1.0 / (1L shl (case.depth.container - 1))
            for (i in input.indices) {
                val expected = if (case.depth == Depth.F32) Float.fromBits(reference[i]) else (reference[i] * scale).toFloat()
                if (expected != decoded.samples[i]) throw AssertionError("${case.name}: sample $i is ${decoded.samples[i]} where wvunpack gives $expected")
                val original = if (case.depth == Depth.F32) Float.fromBits(input[i]).toDouble() else input[i] * scale
                worst = max(worst, abs(original - decoded.samples[i]))
            }
            assertTrue(worst < case.lossyBound, "${case.name}: the largest error is $worst of full scale")
        }
    }

    @Test
    fun ffmpegsEncoderAgreesWithWvunpack() {
        val tools = tools()
        // ffmpeg writes multichannel files in the older channel information
        // form, which the reference accepts and so does AudioKodec.
        for ((channels, codec) in listOf(2 to "pcm_s16le", 6 to "pcm_s16le", 8 to "pcm_s24le", 1 to "pcm_s32le")) {
            for (level in listOf("0", "8")) {
                val name = "ffmpeg_${channels}_${codec}_$level"
                val wv = temp("$name.wv")
                val expressions = (0 until channels).joinToString("|") { c -> "0.3*sin(2*PI*${300 + 120 * c}*t)+0.1*(random(${c + 1})-0.5)" }
                run(
                    listOf(
                        tools.ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "aevalsrc=$expressions:s=$RATE:d=1.5",
                        "-c:a", "wavpack", "-sample_fmt", if (codec == "pcm_s16le") "s16p" else "s32p",
                        "-compression_level", level, wv.path,
                    ),
                )
                val bytes = wv.readBytes()
                val raw = temp("$name.raw")
                run(listOf(tools.wvunpack, "-q", "-y", "--raw", wv.path, "-o", raw.path))
                val decoded = AudioKodec.decode(bytes)
                assertEquals(channels, decoded.format.channels, name)
                val reference = raw.readBytes()
                val depth = AudioKodec.probe(bytes).bitDepth!!
                val bytesPer = (depth + 7) / 8
                assertEquals(reference.size / bytesPer, decoded.samples.size, "$name: length")
                val scale = 1.0 / (1L shl (bytesPer * 8 - 1))
                for (i in decoded.samples.indices) {
                    var value = 0
                    for (b in 0 until bytesPer) value = value or ((reference[i * bytesPer + b].toInt() and 0xFF) shl (8 * b))
                    value = (value shl (32 - 8 * bytesPer)) shr (32 - 8 * bytesPer)
                    if ((value * scale).toFloat() != decoded.samples[i]) throw AssertionError("$name: sample $i differs from wvunpack")
                }
            }
        }
    }

    @Test
    fun dsdIsRefusedByName() {
        val tools = tools()
        val raw = temp("dsd.raw")
        raw.writeBytes(ByteArray(50_000) { (it * 37 + (it shr 3)).toByte() })
        val wv = temp("dsd.wv")
        run(listOf(tools.wavpack, "-q", "-y", "--raw-pcm=2822400,1,2", raw.path, "-o", wv.path))
        val failure = assertFailsWith<UnsupportedFeatureException> { AudioKodec.open(wv.readBytes()) }
        assertEquals("WavPack DSD audio", failure.feature)
    }

    @Test
    fun seekingLandsOnTheSamplesAContinuousDecodeGives() {
        val tools = tools()
        val (_, wv) = encode(tools, Case("seek", 2, Depth.S16, listOf("--blocksize=4000")))
        val bytes = wv.readBytes()
        val whole = AudioKodec.decode(bytes).samples
        for (target in listOf(0L, 1L, 3999L, 4000L, 23_456L, FRAMES - 1L)) {
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
        val tools = tools()
        val (_, wv) = encode(tools, Case("tagged", 2, Depth.S16, listOf("-w", "Title=WavPack oracle", "-w", "Artist=AudioKodec")))
        val bytes = wv.readBytes()
        assertEquals("WavPack oracle", AudioKodec.readTags(bytes)[TagField.TITLE])
        assertEquals(FRAMES.toLong(), AudioKodec.probe(bytes).durationFrames)
        // A flipped bit inside a block's audio fails its checksum.
        val damaged = bytes.copyOf()
        damaged[damaged.size / 2] = (damaged[damaged.size / 2].toInt() xor 0x10).toByte()
        assertFailsWith<CorruptStreamException> { AudioKodec.decode(damaged) }
        val tolerant = AudioKodec.decode(damaged, DecodeOptions(tolerant = true))
        assertTrue(tolerant.frames in 1 until FRAMES, "tolerant decoding keeps the blocks before the damage")
    }

    // Helpers.

    private enum class Depth(val bits: Int, val container: Int, val ffmpegCodec: String?) {
        U8(8, 8, "pcm_u8"),
        S16(16, 16, "pcm_s16le"),
        S20(20, 24, null),
        S24(24, 24, "pcm_s24le"),
        S32(32, 32, "pcm_s32le"),
        F32(32, 32, "pcm_f32le"),
    }

    private class Case(
        val name: String,
        val channels: Int,
        val depth: Depth,
        val options: List<String>,
        val identicalChannels: Boolean = false,
        val silent: Boolean = false,
        val lossyBound: Double = 0.0,
    )

    private class Tools(val wavpack: String, val wvunpack: String, val ffmpeg: String)

    private fun tools(): Tools {
        val ffmpeg = findOnPath("ffmpeg")
        val dir = File(System.getProperty("user.dir"))
        fun built(name: String) = findOnPath(name)
            ?: listOf(dir, dir.parentFile).map { File(it, "reference/wavpack/build/$name") }.firstOrNull { it.canExecute() }?.absolutePath
        val wavpack = built("wavpack")
        val wvunpack = built("wvunpack")
        assumeTrue("ffmpeg, wavpack and wvunpack are required for the WavPack oracle", ffmpeg != null && wavpack != null && wvunpack != null)
        return Tools(wavpack!!, wvunpack!!, ffmpeg!!)
    }

    /**
     * Encodes the test signal for [case] and returns its samples as stored
     * integers, container-scaled, or float bits, with the `.wv` file.
     */
    private fun encode(tools: Tools, case: Case): Pair<IntArray, File> {
        val stem = case.name.replace(Regex("[^A-Za-z0-9]+"), "_")
        val raw = temp("$stem.s32")
        val expressions = (0 until case.channels).map { c ->
            when {
                case.silent -> "0"
                case.identicalChannels -> "0.4*sin(2*PI*440*t)+0.1*(random(1)-0.5)"
                else -> "0.3*sin(2*PI*${220 * (c + 2)}*t)+0.2*sin(2*PI*(100+${700 + 150 * c}*t)*t)+0.15*(random(${c + 1})-0.5)" +
                    "+0.3*lt(mod(t+${0.03 * c}\\,0.31)\\,0.004)"
            }
        }
        run(listOf(tools.ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "aevalsrc=${expressions.joinToString("|")}:s=$RATE:d=${FRAMES.toDouble() / RATE}", "-f", "s32le", "-c:a", "pcm_s32le", raw.path))
        val full = ints(raw)
        // The stored values for each depth, held as the container's integers.
        val stored = IntArray(full.size) { i ->
            when (case.depth) {
                Depth.U8 -> full[i] shr 24
                Depth.S16 -> full[i] shr 16
                Depth.S20 -> (full[i] shr 12) shl 4
                Depth.S24 -> full[i] shr 8
                Depth.S32 -> full[i]
                Depth.F32 -> (full[i] / 2147483648.0).toFloat().toRawBits()
            }
        }
        val pcm = temp("$stem.pcm")
        val bytesPer = case.depth.container / 8
        val buffer = ByteBuffer.allocate(stored.size * bytesPer).order(ByteOrder.LITTLE_ENDIAN)
        for (value in stored) {
            when (bytesPer) {
                1 -> buffer.put((value + 128).toByte())
                2 -> buffer.putShort(value.toShort())
                3 -> {
                    buffer.put(value.toByte())
                    buffer.put((value shr 8).toByte())
                    buffer.put((value shr 16).toByte())
                }
                else -> buffer.putInt(value)
            }
        }
        pcm.writeBytes(buffer.array())
        val format = when (case.depth) {
            Depth.U8 -> "8u"
            Depth.F32 -> "32f"
            else -> "${case.depth.bits}s"
        }
        val wv = temp("$stem.wv")
        run(listOf(tools.wavpack, "-q", "-y", "--raw-pcm=$RATE,$format,${case.channels},le") + case.options + listOf(pcm.path, "-o", wv.path))
        return stored to wv
    }

    /** What `wvunpack` decodes [wv] to, as container-scaled integers or float bits. */
    private fun wvunpack(tools: Tools, wv: File, case: Case): IntArray {
        val out = temp(wv.nameWithoutExtension + ".out.raw")
        run(listOf(tools.wvunpack, "-q", "-y", "--raw", wv.path, "-o", out.path))
        val bytes = out.readBytes()
        val bytesPer = case.depth.container / 8
        return IntArray(bytes.size / bytesPer) { i ->
            val at = i * bytesPer
            when (bytesPer) {
                1 -> (bytes[at].toInt() and 0xFF) - 128
                2 -> ((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8))
                3 -> ((bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or (bytes[at + 2].toInt() shl 16))
                else -> ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
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

    private fun ints(file: File): IntArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        return IntArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "${command.first()} did not finish")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed:\n$output")
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private companion object {
        const val RATE = 44_100
        const val FRAMES = 100_000

        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-wavpack-oracle").also { it.mkdirs() }

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

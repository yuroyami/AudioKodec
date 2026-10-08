package io.github.yuroyami.audiokodec

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the ALAC decoder against Apple's reference encoder and decoder,
 * `alacconvert`, built from the commit the port follows (see
 * `reference/REFERENCES.md`) in `reference/alac/convert-utility`.
 *
 * ALAC is lossless, so the bar is exactness: every file `alacconvert`
 * encodes, at 16, 24 and 32 bits and with one to eight channels, has to
 * decode through `AudioKodec.open` to exactly the integers that went in.
 * `alacconvert` keeps the channels of its input in the order it finds them
 * and ALAC calls that order its own, centre first above two channels, so
 * the comparison maps each decoded channel back through ALAC's order. Mono
 * and stereo are also decoded back by `alacconvert` itself, which has to
 * agree. A separate C++ harness feeds 20-bit PCM directly to Apple's encoder,
 * avoiding the command-line tool's 20-bit WAV reader. Missing reference builds
 * produce explicit test skips.
 */
class AlacOracleTest {

    private val alacconvert: File? = File(System.getProperty("user.dir")).let { dir ->
        listOf(dir, dir.parentFile).map { File(it, "reference/alac/convert-utility/alacconvert") }.firstOrNull { it.canExecute() }
    }

    private val twentyBitOracle: File? by lazy { buildTwentyBitOracle() }

    @Test
    fun everyDepthAndChannelCountDecodesToWhatWentIn() {
        val tool = alacconvert ?: return skip()
        for (channels in 1..8) {
            for (bits in intArrayOf(16, 24, 32)) {
                val what = "$channels ch, $bits-bit"
                val rate = if (bits == 32) 48_000 else 44_100
                val input = temp("alac_${channels}_$bits.wav")
                val samples = writeWav(input, channels, bits, rate, frames = 20_000 + channels * 1_111)
                val caf = temp("alac_${channels}_$bits.caf")
                run(listOf(tool.path, input.path, caf.path))

                val bytes = caf.readBytes()
                val info = AudioKodec.probe(bytes)
                assertEquals(AudioCodec.ALAC, info.codec, "$what: codec")
                assertEquals(bits, info.bitDepth, "$what: bit depth")
                assertEquals(rate, info.format.sampleRate, "$what: rate")
                assertEquals(samples.size / channels.toLong(), info.durationFrames, "$what: length")

                val order = storedSourceOfOutput(channels)
                var frame = 0
                AudioKodec.open(bytes).use { decoder ->
                    val ints = decoder as IntPcmAccess
                    assertEquals(bits, ints.bitDepth, "$what: integer depth")
                    while (true) {
                        val block = ints.nextIntFrame() ?: break
                        for (i in 0 until block.frameCount) {
                            for (slot in 0 until channels) {
                                val expected = samples[(frame + i) * channels + order[slot]]
                                if (block.samples[i * channels + slot] != expected) {
                                    throw AssertionError("$what: frame ${frame + i}, channel $slot is ${block.samples[i * channels + slot]}, not $expected")
                                }
                            }
                        }
                        frame += block.frameCount
                    }
                }
                assertEquals(samples.size / channels, frame, "$what: frames decoded")

                if (channels <= 2) {
                    // Apple's tool decodes only mono and stereo back.
                    val back = temp("alac_${channels}_$bits.back.wav")
                    run(listOf(tool.path, caf.path, back.path))
                    assertTrue(readWavSamples(back).contentEquals(samples), "$what: alacconvert's own decode differs from the input")
                }
            }
        }
    }

    @Test
    fun seekingLandsOnTheSampleAsked() {
        val tool = alacconvert ?: return skip()
        val input = temp("alac_seek.wav")
        val samples = writeWav(input, 2, 24, 44_100, frames = 50_000)
        val caf = temp("alac_seek.caf")
        run(listOf(tool.path, input.path, caf.path))
        AudioKodec.open(caf.readBytes()).use { decoder ->
            val ints = decoder as IntPcmAccess
            for (target in longArrayOf(0, 1, 4_095, 4_096, 4_097, 33_333, 49_999)) {
                assertEquals(target, decoder.seek(target), "seek to $target")
                val block = ints.nextIntFrame() ?: throw AssertionError("nothing after seeking to $target")
                assertEquals(target, block.positionFrames, "position after seeking to $target")
                for (i in 0 until block.frameCount * 2) {
                    assertEquals(samples[target.toInt() * 2 + i], block.samples[i], "sample ${target * 2 + i} after seeking to $target")
                }
            }
        }
    }

    @Test
    fun twentyBitMonoStereoAndSurroundDecodeToTheOriginalIntegers() {
        val tool = twentyBitOracle ?: return skipTwentyBit()
        for (channels in intArrayOf(1, 2, 6)) {
            for (noise in listOf(false, true)) {
                val what = "$channels ch, 20-bit, ${if (noise) "noise" else "tones"}"
                val (bytes, samples) = encodeTwentyBit(tool, channels, noise, "decode")
                val info = AudioKodec.probe(bytes)
                val layout = when (channels) {
                    1 -> ChannelLayout.MONO
                    2 -> ChannelLayout.STEREO
                    else -> ChannelLayout.SURROUND_5_1
                }
                assertEquals(AudioContainer.CAF, info.container, "$what: container")
                assertEquals(AudioCodec.ALAC, info.codec, "$what: codec")
                assertEquals(20, info.bitDepth, "$what: depth")
                assertEquals(48_000, info.format.sampleRate, "$what: sample rate")
                assertEquals(layout, info.format.channelLayout, "$what: channel layout")
                assertEquals(samples.size / channels.toLong(), info.durationFrames, "$what: length")
                var frame = 0
                AudioKodec.open(bytes).use { decoder ->
                    assertEquals(info.format, decoder.info.format, "$what: probe and open formats")
                    assertEquals(info.durationFrames, decoder.info.durationFrames, "$what: probe and open lengths")
                    assertEquals(info.bitDepth, decoder.info.bitDepth, "$what: probe and open depths")
                    val ints = decoder as IntPcmAccess
                    assertEquals(20, ints.bitDepth, "$what: integer depth")
                    while (true) {
                        val block = ints.nextIntFrame() ?: break
                        assertTrue(block.frameCount > 0, "$what: a non-empty block")
                        assertEquals(frame.toLong(), block.positionFrames, "$what: block position")
                        checkTwentyBitSamples(block.samples, block.frameCount, frame, samples, channels, what)
                        frame += block.frameCount
                    }
                    assertEquals(samples.size / channels.toLong(), decoder.positionFrames, "$what: EOF position")
                }
                assertEquals(samples.size / channels, frame, "$what: includes the short final packet")
            }
        }
    }

    @Test
    fun twentyBitSeekingKeepsEveryLowBitAndChannel() {
        val tool = twentyBitOracle ?: return skipTwentyBit()
        for (channels in intArrayOf(1, 2, 6)) {
            for (noise in listOf(false, true)) {
                val what = "$channels ch, 20-bit, ${if (noise) "noise" else "tones"}"
                val (bytes, samples) = encodeTwentyBit(tool, channels, noise, "seek")
                val frames = samples.size / channels
                AudioKodec.open(bytes).use { decoder ->
                    val ints = decoder as IntPcmAccess
                    // Move backwards as well as across both sides of the 4096-frame boundaries.
                    for (target in intArrayOf(0, 1, 4095, 4096, 4097, 8191, 8192, 12287, 12288, frames - 1, 0)) {
                        assertEquals(target.toLong(), decoder.seek(target.toLong()), "$what: seek to $target")
                        val block = ints.nextIntFrame() ?: throw AssertionError("$what: nothing after seeking to $target")
                        assertEquals(target.toLong(), block.positionFrames, "$what: position after seeking")
                        checkTwentyBitSamples(block.samples, block.frameCount, target, samples, channels, "$what: seek to $target")
                    }
                    assertEquals(frames.toLong(), decoder.seek(frames.toLong()), "$what: seek to EOF")
                    assertEquals(null, ints.nextIntFrame(), "$what: nothing beyond the valid frames")
                }
            }
        }
    }

    private fun encodeTwentyBit(tool: File, channels: Int, noise: Boolean, purpose: String): Pair<ByteArray, IntArray> {
        val frames = 3 * 4096 + 137
        val extrema = intArrayOf(-524_288, 524_287, -1, 0, 1, 15, -15, 16, -16)
        var seed = 2024
        val samples = IntArray(frames * channels) { i ->
            val channel = i % channels
            val frame = i / channels
            seed = seed * 1_103_515_245 + 12_345
            when {
                frame % 4096 < extrema.size -> extrema[(frame % 4096 + channel) % extrema.size]
                noise -> (seed ushr 12) - 524_288
                else -> (0.42 * 524_287 * sin(2 * PI * (173.0 + 79.0 * channel) * frame / 48_000)).toInt() +
                    ((seed ushr 24) and 255) - 128
            }
        }
        val name = "alac20_${purpose}_${channels}_${if (noise) "noise" else "tones"}"
        val raw = temp("$name.s32le")
        val pcm = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) pcm.putInt(sample)
        raw.writeBytes(pcm.array())
        val caf = temp("$name.caf")
        run(listOf(tool.path, channels.toString(), raw.path, caf.path))
        val bytes = caf.readBytes()
        assertEquals(noise, firstElementEscapes(bytes), "$name: reaches the expected ALAC coding path")
        return bytes to samples
    }

    /** Verify that the independent encoder actually exercises prediction and escape coding. */
    private fun firstElementEscapes(caf: ByteArray): Boolean {
        val input = ByteBuffer.wrap(caf).order(ByteOrder.BIG_ENDIAN)
        var at = 8
        while (at + 12 <= caf.size) {
            val type = String(caf, at, 4, Charsets.US_ASCII)
            val size = input.getLong(at + 4).toInt()
            if (type == "data") {
                // After CAF's 4-byte edit count, ALAC's first element has a
                // 3-bit type, 4-bit instance, 12 reserved bits, then four flags.
                // The final flag is escape, bit 1 of the packet's third byte.
                assertTrue(size >= 7, "the reference packet contains its element header")
                return input.get(at + 18).toInt() and 2 != 0
            }
            at += 12 + size
        }
        throw AssertionError("the reference CAF has no data chunk")
    }

    private fun checkTwentyBitSamples(
        actual: IntArray, frames: Int, start: Int, expected: IntArray, channels: Int, what: String,
    ) {
        // Apple's public ALACChannelLayoutTags declares six stored channels as
        // C L R Ls Rs LFE. AudioKodec's public order is L R C LFE Ls Rs.
        val order = if (channels == 6) intArrayOf(1, 2, 0, 5, 3, 4) else IntArray(channels) { it }
        assertTrue(start + frames <= expected.size / channels, "$what: no padded samples")
        for (i in 0 until frames * channels) {
            val value = expected[(start + i / channels) * channels + order[i % channels]]
            if (actual[i] != value) {
                throw AssertionError("$what: frame ${start + i / channels}, channel ${i % channels} is ${actual[i]}, not $value")
            }
        }
    }

    private fun buildTwentyBitOracle(): File? {
        val dir = File(System.getProperty("user.dir"))
        val codec = listOf(dir, dir.parentFile).map { File(it, "reference/alac/codec") }
            .firstOrNull { File(it, "libalac.a").isFile } ?: return null
        val source = temp("alac_20bit_oracle.cpp")
        val resource = javaClass.getResourceAsStream("/alac_20bit_oracle.cpp")
            ?: throw AssertionError("the 20-bit ALAC oracle resource is missing")
        source.writeBytes(resource.use { it.readBytes() })
        val binary = temp("alac_20bit_oracle")
        val compile = try {
            ProcessBuilder(
                "c++", "-std=c++11", "-O1", "-Wno-multichar", "-I", codec.path,
                "-o", binary.path, source.path, File(codec, "libalac.a").path,
            ).redirectErrorStream(true).start()
        } catch (failure: java.io.IOException) {
            return null
        }
        val output = compile.inputStream.bufferedReader().readText()
        assertTrue(compile.waitFor(120, TimeUnit.SECONDS), "the 20-bit ALAC oracle did not compile in time")
        assertEquals(0, compile.exitValue(), "the 20-bit ALAC oracle did not compile:\n$output")
        return binary
    }

    private fun skipTwentyBit() {
        org.junit.Assume.assumeTrue("the Apple ALAC library or a C++ compiler is missing, so the 20-bit comparison did not run", false)
    }

    /** Which stored channel each of AudioKodec's channels comes from, for ALAC's order. */
    private fun storedSourceOfOutput(channels: Int): IntArray {
        val stored = io.github.yuroyami.audiokodec.internal.alac.AlacConfig.storedOrder(channels)
        val canonical = stored.sortedBy { it.ordinal }
        return IntArray(channels) { stored.indexOf(canonical[it]) }
    }

    /** Writes a WAV of a different tone per channel with some noise, and returns its samples. */
    private fun writeWav(file: File, channels: Int, bits: Int, rate: Int, frames: Int): IntArray {
        var seed = 77
        val samples = IntArray(frames * channels) { i ->
            val c = i % channels
            seed = seed * 1_103_515_245 + 12_345
            val noise = ((seed ushr 16) and 0x7FFF) / 32768.0 - 0.5
            val value = 0.45 * sin(2 * PI * (200.0 + 150.0 * c) * (i / channels) / rate) + 0.1 * noise
            (value * ((1L shl (bits - 1)) - 1)).toLong().toInt()
        }
        val bytesPer = bits / 8
        val data = ByteBuffer.allocate(samples.size * bytesPer).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) for (k in 0 until bytesPer) data.put((s ushr (8 * k)).toByte())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * bytesPer).putShort((channels * bytesPer).toShort()).putShort(bits.toShort())
        header.put("data".toByteArray()).putInt(data.capacity())
        file.writeBytes(header.array() + data.array())
        return samples
    }

    private fun readWavSamples(file: File): IntArray {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        var bits = 16
        while (at + 8 <= bytes.limit()) {
            val id = String(bytes.array(), at, 4, Charsets.US_ASCII)
            val size = bytes.getInt(at + 4)
            if (id == "fmt ") bits = bytes.getShort(at + 22).toInt()
            if (id == "data") {
                val per = bits / 8
                return IntArray(size / per) { i ->
                    var value = 0
                    for (k in 0 until per) value = value or ((bytes.get(at + 8 + i * per + k).toInt() and 0xFF) shl (8 * k))
                    (value shl (32 - bits)) shr (32 - bits)
                }
            }
            at += 8 + size + (size and 1)
        }
        throw AssertionError("${file.name} has no data chunk")
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "${command.first()} did not finish")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed:\n$output")
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private fun skip() {
        org.junit.Assume.assumeTrue("alacconvert has not been built under reference/alac, so the ALAC comparison did not run", false)
    }

    private companion object {
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-alac-oracle").also { it.mkdirs() }
    }
}

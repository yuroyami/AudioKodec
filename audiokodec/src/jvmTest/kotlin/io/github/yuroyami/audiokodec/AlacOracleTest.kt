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
 * agree. The test skips itself when the tool has not been built.
 */
class AlacOracleTest {

    private val alacconvert: File? = File(System.getProperty("user.dir")).let { dir ->
        listOf(dir, dir.parentFile).map { File(it, "reference/alac/convert-utility/alacconvert") }.firstOrNull { it.canExecute() }
    }

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

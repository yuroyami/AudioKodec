package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.opus.OpusMultistreamDecoder
import io.github.yuroyami.audiokodec.internal.opus.OpusPacket
import io.github.yuroyami.audiokodec.internal.vorbis.VorbisDecoder
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
 * Checks the Opus multistream decoder, which carries surround sound and
 * ambisonics, against libopus.
 *
 * `opus_demo` handles one stream of one or two channels only, so the oracle
 * here is `src/jvmTest/resources/opus_multistream_oracle.c`, a thin command
 * line over libopus's multistream and projection encoders and decoders. The
 * test compiles it against the reference build that `OpusDecodeOracleTest`
 * describes, encodes with it, and requires every packet's final range to
 * equal the encoder's and every decoded float to equal the reference
 * decoder's to the bit. Without the reference build or a C compiler the
 * tests skip themselves.
 */
class OpusMultistreamOracleTest {

    private val oracle: File? by lazy { buildOracle() }

    @Test
    fun surroundLayoutsMatchTheReference() {
        val oracle = oracle ?: return skip()
        // Family 1 is the Vorbis speaker order for one to eight channels,
        // family 0 the plain mono and stereo case, and family 255 any number
        // of channels with no stated meaning.
        val cases = listOf(
            Triple(0, 2, 96_000),
            Triple(1, 3, 128_000),
            Triple(1, 4, 160_000),
            Triple(1, 5, 64_000),
            Triple(1, 6, 256_000),
            Triple(1, 6, 96_000),
            Triple(1, 7, 224_000),
            Triple(1, 8, 320_000),
            Triple(255, 3, 96_000),
        )
        for ((family, channels, bitrate) in cases) {
            check(oracle, family, channels, bitrate, frame = 960, rates = intArrayOf(48_000, 24_000))
        }
    }

    @Test
    fun ambisonicsAndProjectionMatchTheReference() {
        val oracle = oracle ?: return skip()
        // Family 2 sends each ambisonic channel to its own stream; family 3
        // codes mixes of them and undoes the mixing with a matrix the header
        // carries.
        for ((family, channels) in listOf(2 to 4, 2 to 11, 3 to 4, 3 to 9, 3 to 16)) {
            check(oracle, family, channels, bitrate = 48_000 * channels / 2, frame = 960, rates = intArrayOf(48_000, 16_000))
        }
    }

    @Test
    fun lostPacketsAreConcealedInEveryStream() {
        val oracle = oracle ?: return skip()
        for ((family, channels) in listOf(1 to 6, 3 to 9)) {
            check(oracle, family, channels, bitrate = 32_000 * channels, frame = 480, rates = intArrayOf(48_000), lose = true)
        }
    }

    @Test
    fun oggOpusFilesOfEveryFamilyDecodeExactly() {
        val oracle = oracle ?: return skip()
        for ((family, channels) in listOf(1 to 6, 1 to 3, 1 to 8, 2 to 9, 3 to 9, 255 to 4)) {
            val what = "Ogg Opus, family $family, $channels channels"
            val input = temp("ogg_input_$channels.pcm")
            writeSignal(input, channels, seconds = 1.5)
            val encoded = temp("ogg_${family}_$channels.msb")
            run(listOf(oracle.path, "-e", "$family", "$channels", "${48_000 * channels}", "960", input.path, encoded.path))
            val reference = temp("ogg_${family}_$channels.f32")
            run(listOf(oracle.path, "-d", "48000", encoded.path, reference.path))
            val floats = ByteBuffer.wrap(reference.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val decodedFrames = floats.remaining() / channels

            // As if cut from ten seconds into a longer stream, with the
            // pre-skip libopus asks for and a few hundred samples of
            // padding at the end for the last page to cut off.
            val preSkip = 312
            val start = 480_000L
            val trim = 500
            val file = oggOpusFile(encoded, preSkip, start, endGranule = start + decodedFrames - trim)
            val decoder = AudioKodec.open(file)
            val expectedFrames = decodedFrames - trim - preSkip
            assertEquals(expectedFrames.toLong(), decoder.durationFrames, "$what: duration")
            val order = if (family == 1) VorbisDecoder.orderFor(channels) else IntArray(channels) { it }
            var frameIndex = 0
            while (true) {
                val frame = decoder.nextFrame() ?: break
                assertEquals(frameIndex.toLong(), frame.positionFrames, "$what: frame position")
                for (f in 0 until frame.frameCount) {
                    for (slot in 0 until channels) {
                        val expected = floats[(preSkip + frameIndex + f) * channels + order[slot]]
                        if (frame.samples[f * channels + slot] != expected) {
                            throw AssertionError("$what: frame ${frameIndex + f}, channel $slot differs from the reference decoder")
                        }
                    }
                }
                frameIndex += frame.frameCount
            }
            assertEquals(expectedFrames, frameIndex, "$what: frames decoded")
        }
    }

    /**
     * Wraps an oracle file's packets in Ogg Opus as RFC 7845 lays it out:
     * the identification header, the comment header, then pages of audio
     * whose granule positions count from [start], the last cut to [endGranule].
     */
    private fun oggOpusFile(encoded: File, preSkip: Int, start: Long, endGranule: Long): ByteArray {
        val input = ByteBuffer.wrap(encoded.readBytes()).order(ByteOrder.BIG_ENDIAN)
        input.position(4)
        val family = input.get().toInt() and 0xFF
        val channels = input.get().toInt() and 0xFF
        val streams = input.get().toInt() and 0xFF
        val coupled = input.get().toInt() and 0xFF
        val table = if (family == 3) {
            ByteArray(input.short.toInt() and 0xFFFF).also { input.get(it) }
        } else {
            ByteArray(channels).also { input.get(it) }
        }
        val head = java.io.ByteArrayOutputStream().apply {
            write("OpusHead".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(1, channels.toByte(), preSkip.toByte(), (preSkip shr 8).toByte()))
            write(byteArrayOf(0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, family.toByte()))
            write(byteArrayOf(streams.toByte(), coupled.toByte()))
            write(table)
        }.toByteArray()
        val tags = "OpusTags".toByteArray(Charsets.US_ASCII) + byteArrayOf(4, 0, 0, 0) + "test".toByteArray() + ByteArray(4)
        val packets = ArrayList<ByteArray>()
        while (input.remaining() >= 8) {
            val length = input.int
            input.int
            packets += ByteArray(length).also { input.get(it) }
        }
        val out = java.io.ByteArrayOutputStream()
        val serial = 0x0105
        var sequence = 0
        out.write(oggPage(serial, sequence++, 0, 0x02, listOf(head)))
        out.write(oggPage(serial, sequence++, 0, 0x00, listOf(tags)))
        var granule = start
        for (first in packets.indices step 5) {
            val group = packets.subList(first, minOf(packets.size, first + 5))
            for (packet in group) granule += OpusPacket.parse(packet, 0, packet.size, selfDelimited = streams > 1).samples
            val last = first + 5 >= packets.size
            out.write(oggPage(serial, sequence++, if (last) endGranule else granule, if (last) 0x04 else 0x00, group))
        }
        return out.toByteArray()
    }

    private fun oggPage(serial: Int, sequence: Int, granule: Long, flags: Int, packets: List<ByteArray>): ByteArray {
        val lacing = java.io.ByteArrayOutputStream()
        for (packet in packets) {
            var left = packet.size
            while (left >= 255) {
                lacing.write(255)
                left -= 255
            }
            lacing.write(left)
        }
        val segments = lacing.toByteArray()
        check(segments.size <= 255) { "too many segments for one page" }
        val header = ByteBuffer.allocate(27).order(ByteOrder.LITTLE_ENDIAN)
        header.put("OggS".toByteArray(Charsets.US_ASCII)).put(0).put(flags.toByte())
            .putLong(granule).putInt(serial).putInt(sequence).putInt(0).put(segments.size.toByte())
        val page = header.array() + segments + packets.reduce { a, b -> a + b }
        var crc = 0
        for (byte in page) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc and Int.MIN_VALUE != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        for (i in 0 until 4) page[22 + i] = (crc ushr (8 * i)).toByte()
        return page
    }

    private fun check(oracle: File, family: Int, channels: Int, bitrate: Int, frame: Int, rates: IntArray, lose: Boolean = false) {
        val what = "family $family, $channels channels, $bitrate bit/s"
        val input = temp("ms_input_$channels.pcm")
        writeSignal(input, channels, seconds = 1.5)
        val encoded = temp("ms_${family}_${channels}_$bitrate.msb")
        run(listOf(oracle.path, "-e", "$family", "$channels", "$bitrate", "$frame", input.path, encoded.path))
        val stream = if (lose) temp("ms_${family}_${channels}_lossy.msb").also { dropPackets(encoded, it) } else encoded
        for (rate in rates) {
            val expected = temp(stream.name + ".$rate.f32")
            run(listOf(oracle.path, "-d", "$rate", stream.path, expected.path))
            val mine = decode(stream, rate, "$what at $rate Hz")
            val theirs = expected.readBytes()
            assertEquals(theirs.size, mine.size, "$what at $rate Hz: decoded length")
            val first = theirs.indices.firstOrNull { theirs[it] != mine[it] }
            assertEquals(null, first?.let { it / 4 }, "$what at $rate Hz: first sample that differs from the reference decoder")
        }
    }

    /** Decodes an oracle file with [OpusMultistreamDecoder], as the oracle's decoder does. */
    private fun decode(file: File, rate: Int, what: String): ByteArray {
        val input = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4).also { input.get(it) }
        assertEquals("OPMS", String(magic, Charsets.US_ASCII))
        val family = input.get().toInt() and 0xFF
        val channels = input.get().toInt() and 0xFF
        val streams = input.get().toInt() and 0xFF
        val coupled = input.get().toInt() and 0xFF
        val decoder = if (family == 3) {
            val size = input.short.toInt() and 0xFFFF
            val bytes = ByteArray(size).also { input.get(it) }
            val matrix = IntArray(size / 2) { (bytes[2 * it].toInt() and 0xFF) or (bytes[2 * it + 1].toInt() shl 8) }
            OpusMultistreamDecoder.withDemixingMatrix(rate, channels, streams, coupled, matrix)
        } else {
            val mapping = IntArray(channels) { input.get().toInt() and 0xFF }
            OpusMultistreamDecoder.withMapping(rate, channels, streams, coupled, mapping)
        }
        val pcm = FloatArray(5760 * channels)
        val out = java.io.ByteArrayOutputStream()
        var last = 0
        var index = 0
        while (input.remaining() >= 8) {
            val length = input.int
            val range = input.int.toLong() and 0xFFFF_FFFFL
            val packet = ByteArray(length).also { input.get(it) }
            val samples = if (length == 0) {
                decoder.decode(null, 0, 0, pcm, last)
            } else {
                decoder.decode(packet, 0, length, pcm, 5760).also {
                    assertEquals(range, decoder.finalRange, "$what: final range of packet $index")
                }
            }
            last = samples
            val bytes = ByteBuffer.allocate(samples * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until samples * channels) bytes.putFloat(pcm[i])
            out.write(bytes.array())
            index++
        }
        return out.toByteArray()
    }

    /** Copies [from] to [to] with single packets and a run of five marked lost. */
    private fun dropPackets(from: File, to: File) {
        val input = ByteBuffer.wrap(from.readBytes()).order(ByteOrder.BIG_ENDIAN)
        val out = java.io.ByteArrayOutputStream()
        val head = ByteArray(8).also { input.get(it) }
        out.write(head)
        val family = head[4].toInt()
        val channels = head[5].toInt() and 0xFF
        if (family == 3) {
            val size = input.short.toInt() and 0xFFFF
            out.write(byteArrayOf((size shr 8).toByte(), size.toByte()))
            out.write(ByteArray(size).also { input.get(it) })
        } else {
            out.write(ByteArray(channels).also { input.get(it) })
        }
        var index = 0
        while (input.remaining() >= 8) {
            val length = input.int
            val range = input.int
            val packet = ByteArray(length).also { input.get(it) }
            val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            if (index % 7 == 3 || index in 40..44) {
                out.write(header.putInt(0).putInt(0).array())
            } else {
                out.write(header.putInt(length).putInt(range).array())
                out.write(packet)
            }
            index++
        }
        to.writeBytes(out.toByteArray())
    }

    /** A different tone in every channel, over noise, with clicks that force short blocks. */
    private fun writeSignal(file: File, channels: Int, seconds: Double) {
        val rate = 48_000
        val frames = (rate * seconds).toInt()
        val buffer = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        var seed = 2024
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            val click = if (i % 12_000 < 16) 0.5 else 0.0
            for (c in 0 until channels) {
                seed = seed * 1_103_515_245 + 12_345
                val noise = ((seed ushr 16) and 0x7FFF) / 32768.0 - 0.5
                val tone = sin(2 * PI * (220.0 + 110.0 * c) * t)
                val value = 0.3 * tone + 0.05 * noise + click
                buffer.putShort((value.coerceIn(-1.0, 1.0) * 20_000).toInt().toShort())
            }
        }
        file.writeBytes(buffer.array())
    }

    /** Compiles the oracle against the reference build, or returns null when either is missing. */
    private fun buildOracle(): File? {
        val dir = File(System.getProperty("user.dir"))
        val libopus = listOf(dir, dir.parentFile).map { File(it, "reference/libopus") }
            .firstOrNull { File(it, "build-plain/libopus.a").isFile } ?: return null
        val source = temp("opus_multistream_oracle.c")
        val resource = javaClass.getResourceAsStream("/opus_multistream_oracle.c") ?: return null
        source.writeBytes(resource.use { it.readBytes() })
        val binary = temp("opus_multistream_oracle")
        val compile = try {
            ProcessBuilder(
                "cc", "-O1", "-I", File(libopus, "include").path, "-o", binary.path, source.path,
                File(libopus, "build-plain/libopus.a").path, "-lm",
            ).redirectErrorStream(true).start()
        } catch (failure: java.io.IOException) {
            return null
        }
        val output = compile.inputStream.bufferedReader().readText()
        assertTrue(compile.waitFor(120, TimeUnit.SECONDS), "the oracle did not compile in time")
        assertEquals(0, compile.exitValue(), "the oracle did not compile:\n$output")
        return binary
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS), "${command.first()} did not finish")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed:\n$output")
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private fun skip() {
        org.junit.Assume.assumeTrue("the libopus reference build or a C compiler is not present, so the multistream comparison did not run", false)
    }

    private companion object {
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-opus-multistream").also { it.mkdirs() }
    }
}

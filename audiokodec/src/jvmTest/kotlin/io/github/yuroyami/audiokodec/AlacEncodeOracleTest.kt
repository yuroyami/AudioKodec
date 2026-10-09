package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.alac.AlacConfig
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.mp4.Mp4Reader
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks the ALAC encoder against Apple's reference, and its files against
 * three decoders.
 *
 * The encoder is a translation of Apple's, so the bar is identity: for the
 * same samples it has to write the same packets, byte for byte, as
 * `alacconvert` does at 16, 24 and 32 bits and as Apple's `ALACEncoder` does
 * at 20 bits through the C++ harness the decoder tests already use, with one
 * to eight channels and a short final packet. The signals mix tones, silence
 * long enough for the zero-run code, and full-scale noise that forces the
 * verbatim escape.
 *
 * The `.m4a` files AudioKodec writes must then decode to exactly what went in
 * through ffmpeg, through AudioKodec, and, once ffmpeg has copied the packets
 * into a CAF file, through `alacconvert`'s own decoder, which proves the
 * container and the coding separately. On macOS, Apple's `afconvert` must
 * also decode them exactly.
 *
 * Every comparison skips itself when the tool it needs is missing.
 */
class AlacEncodeOracleTest {

    private val alacconvert: File? = File(System.getProperty("user.dir")).let { dir ->
        listOf(dir, dir.parentFile).map { File(it, "reference/alac/convert-utility/alacconvert") }.firstOrNull { it.canExecute() }
    }
    private val ffmpeg: String? by lazy { findOnPath("ffmpeg") }

    @Test
    fun packetsMatchApplesEncoderAtSixteenTwentyFourAndThirtyTwoBits() {
        val tool = alacconvert
        assumeTrue("alacconvert has not been built under reference/alac", tool != null)
        for (channels in 1..8) {
            for (bits in intArrayOf(16, 24, 32)) {
                val what = "$channels ch, $bits-bit"
                val rate = if (bits == 32) 48_000 else 44_100
                val frames = 4 * 4096 + 1_234 + channels * 111
                // alacconvert gives each packet its raw size plus eight bytes,
                // and a verbatim packet of three or more elements needs more,
                // so the tool overruns its own heap on full-scale noise there.
                // The 20-bit harness checks those escapes instead.
                val fullScale = channels < 4
                val samples = signal(channels, bits, frames, seed = channels * 31 + bits, noiseShift = if (fullScale) 0 else 2)
                val wav = temp("ref_${channels}_$bits.wav")
                writeWav(wav, channels, bits, rate, samples)
                val caf = temp("ref_${channels}_$bits.caf")
                caf.delete()
                run(listOf(tool!!.path, wav.path, caf.path))
                val reference = cafPackets(caf.readBytes())

                val ours = AudioKodec.encode(samples, AudioFormat(rate, ChannelLayout.of(AlacConfig.storedOrder(channels))), bits, EncodeTarget.alac(bits))
                val packets = mp4Packets(ours)
                assertEquals(reference.size, packets.size, "$what: packet count")
                for (i in reference.indices) {
                    assertContentEquals(reference[i], packets[i], "$what: packet $i of ${reference.size}")
                }
                if (fullScale) assertTrue(reference.any { escapes(it) }, "$what: the noise reaches the verbatim escape")
                assertTrue(reference.any { !escapes(it) }, "$what: the tones are predicted")
            }
        }
    }

    @Test
    fun packetsMatchApplesEncoderAtTwentyBits() {
        val harness = twentyBitHarness()
        assumeTrue("the Apple ALAC library or a C++ compiler is missing", harness != null)
        for (channels in intArrayOf(1, 2, 3, 6, 8)) {
            val frames = 3 * 4096 + 777
            val samples = signal(channels, 20, frames, seed = 20 + channels)
            val raw = temp("ref20_$channels.s32le")
            raw.writeBytes(ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> samples.forEach { b.putInt(it) } }.array())
            val caf = temp("ref20_$channels.caf")
            caf.delete()
            run(listOf(harness!!.path, channels.toString(), raw.path, caf.path))
            val reference = cafPackets(caf.readBytes())
            val ours = mp4Packets(AudioKodec.encode(samples, AudioFormat(48_000, ChannelLayout.of(AlacConfig.storedOrder(channels))), 20, EncodeTarget.alac(20)))
            assertEquals(reference.size, ours.size, "$channels ch, 20-bit: packet count")
            for (i in reference.indices) assertContentEquals(reference[i], ours[i], "$channels ch, 20-bit: packet $i")
        }
    }

    @Test
    fun ffmpegAppleAndAudioKodecDecodeExactlyWhatWentIn() {
        val ffmpeg = ffmpeg
        assumeTrue("ffmpeg is required to read the files AudioKodec writes", ffmpeg != null)
        for ((layout, bits) in listOf(
            ChannelLayout.MONO to 16, ChannelLayout.STEREO to 16, ChannelLayout.STEREO to 24,
            ChannelLayout.STEREO to 32, ChannelLayout.MONO to 20, ChannelLayout.SURROUND_5_1 to 24,
        )) {
            val channels = layout.channels.size
            val what = "$layout, $bits-bit"
            val frames = 2 * 4096 + 999
            val samples = signal(channels, bits, frames, seed = bits * 7 + channels)
            val file = temp("ours_${channels}_$bits.m4a")
            file.writeBytes(AudioKodec.encode(samples, AudioFormat(44_100, layout), bits, EncodeTarget.alac(bits)))

            val probed = AudioKodec.probe(file.readBytes())
            assertEquals(AudioCodec.ALAC, probed.codec, what)
            assertEquals(frames.toLong(), probed.durationFrames, what)
            assertContentEquals(samples, decodeInts(file.readBytes()), "$what: AudioKodec")

            val raw = temp("ours_${channels}_$bits.s32")
            run(listOf(ffmpeg!!, "-y", "-v", "error", "-i", file.path, "-f", "s32le", raw.path))
            val buffer = ByteBuffer.wrap(raw.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val decoded = IntArray(buffer.remaining() / 4) { buffer.int shr (32 - bits) }
            assertContentEquals(samples, decoded, "$what: ffmpeg")

            if (channels <= 2 && bits != 20) {
                val tool = alacconvert ?: continue
                // Apple's tool reads only the plain CAF layout it writes
                // itself, so the packets and configuration are moved across
                // into that layout unchanged.
                val caf = temp("ours_${channels}_$bits.caf")
                caf.writeBytes(cafOf(file.readBytes(), bits, channels, frames))
                val back = temp("ours_${channels}_$bits.back.wav")
                back.delete()
                run(listOf(tool.path, caf.path, back.path))
                assertContentEquals(samples, readWavSamples(back), "$what: alacconvert")
            }
        }
    }

    @Test
    fun applesAfconvertDecodesExactlyWhatWentIn() {
        val afconvert = findOnPath("afconvert")
        assumeTrue("afconvert is required for Apple's own decoder, and exists only on macOS", afconvert != null)
        for ((layout, bits) in listOf(ChannelLayout.MONO to 16, ChannelLayout.STEREO to 16, ChannelLayout.STEREO to 24, ChannelLayout.SURROUND_5_1 to 24, ChannelLayout.STEREO to 32)) {
            val channels = layout.channels.size
            val samples = signal(channels, bits, 3 * 4096 + 555, seed = 99 + bits)
            val file = temp("afconvert_${channels}_$bits.m4a")
            file.writeBytes(AudioKodec.encode(samples, AudioFormat(44_100, layout), bits, EncodeTarget.alac(bits)))
            val back = temp("afconvert_${channels}_$bits.wav")
            back.delete()
            run(listOf(afconvert!!, "-f", "WAVE", "-d", "LEI$bits", file.path, back.path))
            // afconvert writes WAV in WAV's speaker order, which for these
            // layouts is AudioKodec's.
            assertContentEquals(samples, readWavSamples(back), "$layout, $bits-bit")
        }
    }

    // Signals.

    /**
     * Interleaved samples at [bits] bits, a packet of each kind at a time: a
     * different tone per channel, then silence, then full-scale noise, then
     * quieter noise over the tones. [noiseShift] quietens the noise packet.
     */
    private fun signal(channels: Int, bits: Int, frames: Int, seed: Int, noiseShift: Int = 0): IntArray {
        val peak = (1L shl (bits - 1)) - 1
        var state = seed
        return IntArray(frames * channels) { i ->
            val c = i % channels
            val f = i / channels
            state = state * 1_103_515_245 + 12_345
            val noise = (state ushr 1).toLong() * 2 - Int.MAX_VALUE.toLong()
            val tone = 0.4 * sin(2 * PI * (150.0 + 97.0 * c) * f / 44_100)
            when (f / 4_096) {
                0, 1 -> (tone * peak).toLong().toInt()
                2 -> 0
                3 -> (noise shr (32 - bits + noiseShift)).toInt()
                else -> ((tone * 0.5 + 0.01 * (noise.toDouble() / Int.MAX_VALUE)) * peak).toLong().toInt()
            }
        }
    }

    // Reading packets.

    private fun mp4Packets(file: ByteArray): List<ByteArray> {
        val reader = SourceReader(file.asAudioSource(), DecodeOptions.DEFAULT)
        val track = Mp4Reader.parse(reader, DecodeOptions.DEFAULT).audio
        return track.offsets.indices.map { i ->
            file.copyOfRange(track.offsets[i].toInt(), track.offsets[i].toInt() + track.sizes[i])
        }
    }

    /** The packets of a CAF file: its `data` chunk cut at the sizes its `pakt` table lists. */
    private fun cafPackets(caf: ByteArray): List<ByteArray> {
        val input = ByteBuffer.wrap(caf).order(ByteOrder.BIG_ENDIAN)
        var at = 8
        var data = -1
        var sizes: List<Int>? = null
        while (at + 12 <= caf.size) {
            val type = String(caf, at, 4, Charsets.US_ASCII)
            val size = input.getLong(at + 4).toInt()
            val body = at + 12
            when (type) {
                "data" -> data = body + 4
                "pakt" -> {
                    val count = input.getLong(body).toInt()
                    var p = body + 24
                    sizes = List(count) {
                        var value = 0
                        while (true) {
                            val b = caf[p++].toInt() and 0xFF
                            value = (value shl 7) or (b and 0x7F)
                            if (b and 0x80 == 0) break
                        }
                        value
                    }
                }
            }
            at = body + size
        }
        assertTrue(data >= 0 && sizes != null, "the reference CAF has its data and packet table")
        var offset = data
        return sizes!!.map { size -> caf.copyOfRange(offset, offset + size).also { offset += size } }
    }

    /**
     * A CAF file holding [m4a]'s packets and magic cookie unchanged, laid out
     * as `alacconvert` lays out its own: `desc`, `kuki`, `pakt`, `data`.
     */
    private fun cafOf(m4a: ByteArray, bits: Int, channels: Int, frames: Int): ByteArray {
        val reader = SourceReader(m4a.asAudioSource(), DecodeOptions.DEFAULT)
        val parsed = Mp4Reader.parse(reader, DecodeOptions.DEFAULT)
        val alacBox = parsed.audio.entry.boxes.getValue("alac")
        val cookie = alacBox.copyOfRange(4, alacBox.size)
        val packets = mp4Packets(m4a)
        fun chunk(type: String, body: ByteArray): ByteArray =
            type.toByteArray() + ByteBuffer.allocate(8).putLong(body.size.toLong()).array() + body
        val desc = ByteBuffer.allocate(32).putDouble(parsed.audio.entry.sampleRate.toDouble()).put("alac".toByteArray())
            .putInt(when (bits) { 16 -> 1; 20 -> 2; 24 -> 3; else -> 4 }).putInt(0).putInt(4096).putInt(channels).putInt(0).array()
        val sizes = java.io.ByteArrayOutputStream()
        for (packet in packets) {
            var size = packet.size
            val groups = ArrayList<Int>()
            do { groups += size and 0x7F; size = size ushr 7 } while (size > 0)
            for (k in groups.indices.reversed()) sizes.write(groups[k] or if (k > 0) 0x80 else 0)
        }
        val pakt = ByteBuffer.allocate(24).putLong(packets.size.toLong()).putLong(frames.toLong()).putInt(0)
            .putInt(packets.size * 4096 - frames).array() + sizes.toByteArray()
        val data = ByteArray(4) + packets.fold(ByteArray(0)) { a, b -> a + b }
        return "caff".toByteArray() + byteArrayOf(0, 1, 0, 0) + chunk("desc", desc) + chunk("kuki", cookie) +
            chunk("pakt", pakt) + chunk("data", data)
    }

    /** True when a packet's first element is stored verbatim. */
    private fun escapes(packet: ByteArray): Boolean = packet.size >= 3 && packet[2].toInt() and 2 != 0

    private fun decodeInts(file: ByteArray): IntArray {
        val out = ArrayList<Int>()
        AudioKodec.open(file).use { decoder ->
            val access = decoder as IntPcmAccess
            while (true) {
                val frame = access.nextIntFrame() ?: break
                for (i in 0 until frame.sampleCount) out += frame.samples[i]
            }
        }
        return out.toIntArray()
    }

    // Files.

    private fun writeWav(file: File, channels: Int, bits: Int, rate: Int, samples: IntArray) {
        val bytesPer = bits / 8
        val data = ByteBuffer.allocate(samples.size * bytesPer).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) for (k in 0 until bytesPer) data.put((s ushr (8 * k)).toByte())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * bytesPer).putShort((channels * bytesPer).toShort()).putShort(bits.toShort())
        header.put("data".toByteArray()).putInt(data.capacity())
        file.writeBytes(header.array() + data.array())
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

    /** Builds the harness that feeds raw integers straight to Apple's `ALACEncoder`. */
    private fun twentyBitHarness(): File? {
        val dir = File(System.getProperty("user.dir"))
        val codec = listOf(dir, dir.parentFile).map { File(it, "reference/alac/codec") }
            .firstOrNull { File(it, "libalac.a").isFile } ?: return null
        val source = temp("alac_20bit_oracle.cpp")
        source.writeBytes(javaClass.getResourceAsStream("/alac_20bit_oracle.cpp")!!.use { it.readBytes() })
        val binary = temp("alac_20bit_encoder_oracle")
        val compile = try {
            ProcessBuilder("c++", "-std=c++11", "-O1", "-Wno-multichar", "-I", codec.path, "-o", binary.path, source.path, File(codec, "libalac.a").path)
                .redirectErrorStream(true).oracleStart()
        } catch (_: java.io.IOException) {
            return null
        }
        val output = compile.inputStream.bufferedReader().readText()
        assertTrue(compile.waitFor(120, TimeUnit.SECONDS), "the 20-bit ALAC harness did not compile in time")
        assertEquals(0, compile.exitValue(), "the 20-bit ALAC harness did not compile:\n$output")
        return binary
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "${command.first()} did not finish")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed:\n$output")
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private companion object {
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-alac-encode-oracle").also { it.mkdirs() }

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

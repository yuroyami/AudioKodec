package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.opus.OpusPacket
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the layer that joins SILK and CELT into one Opus decoder against
 * the reference implementation, run as a program.
 *
 * The streams here are the ones that reach that layer's own work: hybrid
 * frames, where both codecs run at once; changes of codec, of bandwidth and
 * of channel count from one packet to the next, with and without the
 * redundant frames an encoder adds to smooth them; and lost packets, concealed
 * or rebuilt from the redundant copy the next packet carries. Every packet's
 * final range must equal the encoder's, and the output must equal
 * `opus_demo -d` sample for sample, decoding the way it does
 * ([OpusDemoDecoding]).
 *
 * The reference programs come from the libopus commit the port follows, in
 * `reference/libopus/build-plain`; `OpusDecodeOracleTest` says how to build
 * them. Without them the tests skip themselves.
 */
class OpusJoinOracleTest {

    private val build = File(System.getProperty("user.dir")).let { dir ->
        listOf(dir, dir.parentFile).map { File(it, "reference/libopus/build-plain") }.firstOrNull { File(it, "opus_demo").canExecute() }
    }

    @Test
    fun hybridFramesAndTheEncodersOwnModeChangesMatchTheReference() {
        val build = build ?: return skip()
        val streams = listOf(
            encode(build, "hybrid_fb", OpusTestSignals.speech(2, 3.0), 2, "audio", 40_000, "-bandwidth", "FB"),
            encode(build, "hybrid_swb", OpusTestSignals.speech(1, 3.0), 1, "audio", 24_000, "-bandwidth", "SWB", "-framesize", "10"),
            encode(build, "turns_voip", OpusTestSignals.speechAndMusic(2, 4.0, turn = 0.5), 2, "voip", 32_000),
            encode(build, "turns_audio", OpusTestSignals.speechAndMusic(1, 4.0, turn = 0.5), 1, "audio", 20_000, "-framesize", "10"),
        )
        for (bits in streams) {
            val modes = modesOf(bits)
            assertTrue(OpusPacket.Mode.HYBRID in modes, "${bits.name}: the encoder made no hybrid frames, only $modes")
            if (bits.name.startsWith("turns")) {
                assertTrue(OpusPacket.Mode.CELT in modes, "${bits.name}: the encoder never changed to CELT")
            }
        }
        // SILK changes its internal rate by way of CELT: the last frame at
        // the old bandwidth ends with a redundant CELT frame to fade out to,
        // and the first at the new one starts with another to fade in from,
        // so this stream reaches both kinds of redundant frame.
        val bandwidthChange = encode(
            build, "silk_bandwidth_change", OpusTestSignals.speech(1, 3.0), 1, "voip", 20_000,
            "-bandwidth", "WB", "-inbandfec", "-loss", "20",
        )
        val bandwidths = packetsOf(bandwidthChange).map { OpusPacket.parse(it, 0, it.size).bandwidth }.toSet()
        assertTrue(bandwidths.size > 1, "the SILK stream kept to $bandwidths throughout")
        for (bits in streams + bandwidthChange) {
            for (rate in RATES) {
                for (channels in intArrayOf(1, 2)) checkStream(build, bits, rate, channels)
            }
        }
    }

    @Test
    fun abruptChangesOfModeBandwidthAndChannelsMatchTheReference() {
        val build = build ?: return skip()
        // Pieces from separate encoders joined end to end, so that the
        // decoder meets every kind of change with nothing to smooth it,
        // which is what a stream spliced from two sources looks like.
        val pieces = listOf(
            encode(build, "piece_silk_nb", OpusTestSignals.speech(1, 0.6), 1, "voip", 12_000, "-bandwidth", "NB", "-framesize", "60"),
            encode(build, "piece_hybrid", OpusTestSignals.speech(2, 0.6), 2, "audio", 40_000, "-bandwidth", "FB"),
            encode(build, "piece_celt_short", OpusTestSignals.music(2, 0.6), 2, "restricted-lowdelay", 64_000, "-framesize", "2.5"),
            encode(build, "piece_silk_wb", OpusTestSignals.speech(2, 0.6), 2, "voip", 20_000, "-bandwidth", "WB"),
            encode(build, "piece_celt_mono", OpusTestSignals.music(1, 0.6), 1, "restricted-lowdelay", 32_000, "-framesize", "10"),
            encode(build, "piece_hybrid_swb", OpusTestSignals.speech(1, 0.6), 1, "audio", 24_000, "-bandwidth", "SWB", "-framesize", "10"),
            encode(build, "piece_silk_mb", OpusTestSignals.speech(2, 0.6), 2, "voip", 20_000, "-bandwidth", "MB", "-framesize", "40"),
            encode(build, "piece_celt_nb", OpusTestSignals.music(2, 0.6), 2, "restricted-lowdelay", 24_000, "-bandwidth", "NB", "-framesize", "5"),
        )
        val joined = temp("spliced.bit")
        joined.writeBytes(pieces.map { it.readBytes() }.reduce { a, b -> a + b })
        for (rate in RATES) {
            for (channels in intArrayOf(1, 2)) checkStream(build, joined, rate, channels)
        }
    }

    @Test
    fun lostPacketsAreConcealedOrRebuiltAsTheReferenceDoes() {
        val build = build ?: return skip()
        val streams = listOf(
            encode(
                build, "fec_silk", OpusTestSignals.speech(2, 3.0), 2, "voip", 32_000,
                "-bandwidth", "WB", "-inbandfec", "-loss", "20",
            ),
            encode(
                build, "fec_hybrid", OpusTestSignals.speech(2, 3.0), 2, "voip", 40_000,
                "-bandwidth", "FB", "-inbandfec", "-loss", "20",
            ),
            encode(
                build, "fec_silk_60", OpusTestSignals.speech(1, 3.0), 1, "voip", 24_000,
                "-bandwidth", "WB", "-framesize", "60", "-inbandfec", "-loss", "20",
            ),
            encode(build, "plc_turns", OpusTestSignals.speechAndMusic(2, 4.0, turn = 0.5), 2, "voip", 32_000),
        )
        for (bits in streams) {
            assertTrue(
                bits.name.startsWith("plc") || packetsOf(bits).count { OpusPacket.parse(it, 0, it.size).hasLbrr(it) } > 10,
                "${bits.name}: the encoder added no redundancy to rebuild from",
            )
            // Single losses, which redundancy can repair, and runs, which
            // only concealment can; never the first packet, before which
            // there is nothing to conceal from.
            val lossy = temp(bits.name.removeSuffix(".bit") + "_lossy.bit")
            lossy.writeBytes(dropPackets(bits.readBytes()) { it % 6 == 3 || it in 50..53 || it in 90..91 })
            for (rate in RATES) {
                for (channels in intArrayOf(1, 2)) checkStream(build, lossy, rate, channels)
            }
        }
    }

    private fun encode(build: File, name: String, pcm: ByteArray, channels: Int, application: String, bitrate: Int, vararg options: String): File {
        val input = temp("$name.pcm").also { it.writeBytes(pcm) }
        val bits = temp("$name.bit")
        run(
            listOf(
                File(build, "opus_demo").path, "-e", application, "48000", "$channels", "$bitrate", *options,
                input.path, bits.path,
            ),
        )
        return bits
    }

    /** Decodes [bits] as `opus_demo -d` does and compares the two, packet ranges and samples. */
    private fun checkStream(build: File, bits: File, rate: Int, channels: Int) {
        val what = "${bits.name} at $rate Hz to $channels ch"
        val samples = OpusDemoDecoding.decode(bits.readBytes(), rate, channels, what)
        val reference = temp(bits.name + ".$rate.$channels.pcm")
        run(listOf(File(build, "opus_demo").path, "-d", "$rate", "$channels", bits.path, reference.path))
        val theirs = ByteBuffer.wrap(reference.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(theirs.remaining() / 2, samples.size, "$what: decoded length")
        val first = samples.indices.firstOrNull { samples[it] != theirs.getShort(2 * it) }
        assertEquals(null, first?.let { it / channels }, "$what: first sample frame that differs from the reference decoder")
    }

    private fun modesOf(bits: File): Set<OpusPacket.Mode> =
        packetsOf(bits).filter { it.isNotEmpty() }.map { OpusPacket.parse(it, 0, it.size).mode }.toSet()

    private fun packetsOf(bits: File): List<ByteArray> {
        val stream = ByteBuffer.wrap(bits.readBytes()).order(ByteOrder.BIG_ENDIAN)
        val packets = ArrayList<ByteArray>()
        while (stream.remaining() >= 8) {
            val packet = ByteArray(stream.int)
            stream.int
            stream.get(packet)
            packets += packet
        }
        return packets
    }

    /** [stream] with every packet [lose] picks replaced by a lost one. */
    private fun dropPackets(stream: ByteArray, lose: (Int) -> Boolean): ByteArray {
        val input = ByteBuffer.wrap(stream).order(ByteOrder.BIG_ENDIAN)
        val out = java.io.ByteArrayOutputStream()
        var index = 0
        while (input.remaining() >= 8) {
            val length = input.int
            val range = input.int
            val packet = ByteArray(length).also { input.get(it) }
            val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            if (index > 0 && lose(index)) {
                out.write(header.putInt(0).putInt(0).array())
            } else {
                out.write(header.putInt(length).putInt(range).array())
                out.write(packet)
            }
            index++
        }
        return out.toByteArray()
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS), "${command.first()} did not finish")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed:\n$output")
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private fun skip() {
        println("the libopus reference build is not present, so the Opus join comparison did not run")
    }

    private companion object {
        val RATES = intArrayOf(48_000, 24_000, 16_000, 12_000, 8_000)
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-opus-join").also { it.mkdirs() }
    }
}

package io.github.yuroyami.audiokodecs

import io.github.yuroyami.audiokodecs.internal.opus.OpusPacket
import io.github.yuroyami.audiokodecs.internal.opus.RangeDecoder
import io.github.yuroyami.audiokodecs.internal.opus.silk.SilkDecoder
import io.github.yuroyami.audiokodecs.internal.opus.silk.SilkDecoderControl
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the SILK decoder against the reference implementation, run as a
 * program.
 *
 * SILK is fixed point in every libopus build, so there is no rounding to
 * allow for: every packet's final range must equal the one the encoder
 * recorded, and the decoded samples must equal the reference decoder's
 * exactly, at every output rate the resampler serves. `opus_compare`, the
 * quality measure RFC 6716 defines for judging a decoder, scores the 48 kHz
 * output as a second opinion.
 *
 * The reference programs come from the libopus commit the port follows, in
 * `reference/libopus/build-plain`; `OpusDecodeOracleTest` says how to build
 * them. That folder is not committed, so the tests skip themselves without
 * it, the way the other oracle suites skip without their tools.
 */
class SilkDecodeOracleTest {

    private val build = File(System.getProperty("user.dir")).let { dir ->
        listOf(dir, dir.parentFile).map { File(it, "reference/libopus/build-plain") }.firstOrNull { File(it, "opus_demo").canExecute() }
    }

    @Test
    fun silkOnlyStreamsMatchTheReferenceAtEveryRate() {
        val build = build ?: return skip()
        for (channels in intArrayOf(1, 2)) {
            val input = temp("silk_input_$channels.pcm")
            writeSpeechLikeSignal(input, channels, seconds = 3.0)
            for (bandwidth in listOf("NB", "MB", "WB")) {
                for (frameMs in listOf("10", "20", "40", "60", "120")) {
                    // In stereo the rate has to clear the point below which
                    // the encoder folds the input to mono, which is higher
                    // for short frames, and stay under the point above which
                    // it prefers CELT for this signal.
                    val bitrate = when {
                        channels == 1 -> 12_000
                        frameMs == "10" -> 24_000
                        else -> 20_000
                    }
                    val bits = temp("silk_${channels}_${bandwidth}_$frameMs.bit")
                    encode(build, input, bits, channels, bitrate, "-bandwidth", bandwidth, "-framesize", frameMs)
                    if (channels == 2) {
                        assertTrue(
                            packetsOf(bits).count { OpusPacket.parse(it, 0, it.size).stereo } > 0,
                            "$bandwidth, $frameMs ms: the encoder coded the stereo input as mono throughout",
                        )
                    }
                    for (rate in intArrayOf(8_000, 12_000, 16_000, 24_000, 48_000)) {
                        for (decodeChannels in intArrayOf(1, 2)) {
                            checkStream(
                                build, bits, rate, decodeChannels,
                                "SILK $channels ch, $bandwidth, $frameMs ms, decoded at $rate Hz to $decodeChannels ch",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun redundantCopiesForLossRecoveryAreSkippedWhenDecodingNormally() {
        val build = build ?: return skip()
        for (channels in intArrayOf(1, 2)) {
            val input = temp("silk_input_$channels.pcm")
            writeSpeechLikeSignal(input, channels, seconds = 3.0)
            for (frameMs in listOf("20", "60")) {
                val bits = temp("silk_lbrr_${channels}_$frameMs.bit")
                encode(
                    build, input, bits, channels, if (channels == 2) 32_000 else 24_000,
                    "-bandwidth", "WB", "-framesize", frameMs, "-inbandfec", "-loss", "20",
                )
                val packets = packetsOf(bits)
                assertTrue(
                    packets.count { OpusPacket.parse(it, 0, it.size).hasLbrr(it) } > packets.size / 2,
                    "$channels ch, $frameMs ms: the encoder was asked for redundancy but added little",
                )
                for (rate in intArrayOf(16_000, 48_000)) {
                    checkStream(build, bits, rate, channels, "SILK with redundancy, $channels ch, $frameMs ms, at $rate Hz")
                }
            }
        }
    }

    private fun encode(build: File, input: File, bits: File, channels: Int, bitrate: Int, vararg options: String) {
        run(
            listOf(File(build, "opus_demo").path, "-e", "voip", "48000", "$channels", "$bitrate", *options, input.path, bits.path),
        )
    }

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

    /**
     * Decodes [bits] with both decoders, checks every packet's final range
     * and every sample, and scores the 48 kHz audio with `opus_compare`.
     */
    private fun checkStream(build: File, bits: File, rate: Int, channels: Int, what: String) {
        val decoder = SilkOnlyDriver(rate, channels)
        val pcm = IntArray(OpusPacket.MAX_SAMPLES * channels)
        val out = java.io.ByteArrayOutputStream()
        val stream = ByteBuffer.wrap(bits.readBytes()).order(ByteOrder.BIG_ENDIAN)
        var packets = 0
        while (stream.remaining() >= 8) {
            val length = stream.int
            val expectedRange = stream.int.toLong() and 0xFFFF_FFFFL
            val packet = ByteArray(length)
            stream.get(packet)
            val samples = decoder.decode(packet, pcm, "$what, packet $packets")
            val bytes = ByteBuffer.allocate(samples * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until samples * channels) bytes.putShort(pcm[i].toShort())
            out.write(bytes.array())
            assertEquals(expectedRange, decoder.finalRange, "$what: final range of packet $packets")
            packets++
        }
        assertTrue(packets > 0, "$what: no packets")

        val ours = temp(bits.name + ".ours.pcm")
        ours.writeBytes(out.toByteArray())
        val exact = temp(bits.name + ".exact.pcm")
        run(listOf(File(build, "opus_demo").path, "-d", "$rate", "$channels", bits.path, exact.path))
        val theirs = exact.readBytes()
        val mine = ours.readBytes()
        assertEquals(theirs.size, mine.size, "$what: decoded length")
        val firstDifference = theirs.indices.firstOrNull { theirs[it] != mine[it] }
        assertEquals(null, firstDifference, "$what: first byte that differs from the reference decoder")

        if (rate != 48_000) return
        // opus_compare reads its reference as 48 kHz stereo, so a mono
        // decode is written out as two identical channels, whose fold-down
        // is the mono signal exactly.
        val reference = if (channels == 2) exact else temp(bits.name + ".ref.pcm").also { file ->
            val stereo = ByteArray(theirs.size * 2)
            for (i in 0 until theirs.size / 2) {
                stereo[4 * i] = theirs[2 * i]
                stereo[4 * i + 1] = theirs[2 * i + 1]
                stereo[4 * i + 2] = theirs[2 * i]
                stereo[4 * i + 3] = theirs[2 * i + 1]
            }
            file.writeBytes(stereo)
        }
        val compare = runCapturing(
            listOf(File(build, "opus_compare").path, *(if (channels == 2) arrayOf("-s") else arrayOf()), "-r", "$rate", reference.path, ours.path),
        )
        assertTrue(compare.contains("PASSES"), "$what: opus_compare says\n$compare")
    }

    /**
     * Something SILK will treat as speech: a pulse train at a gliding pitch
     * through three formant resonators, broken by hiss like a fricative and
     * by pauses, with a slower pitch and a delay on the second channel so
     * that the stereo predictor has work to do.
     */
    private fun writeSpeechLikeSignal(file: File, channels: Int, seconds: Double) {
        val rate = 48_000
        val frames = (rate * seconds).toInt()
        val buffer = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        val formants = doubleArrayOf(700.0, 1_200.0, 2_600.0)
        val state = Array(channels) { Array(formants.size) { DoubleArray(2) } }
        val phase = DoubleArray(channels)
        var seed = 12345
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            seed = seed * 1_103_515_245 + 12_345
            val noise = ((seed ushr 16) and 0x7FFF) / 16384.0 - 1.0
            // Syllables of a quarter of a second: voiced, then a fricative,
            // then a short pause.
            val within = t % 0.25
            val voiced = within < 0.17
            val hiss = within in 0.17..0.21
            for (c in 0 until channels) {
                val pitch = (110.0 + 60.0 * sin(2 * PI * 0.7 * t)) / (1 + 0.15 * c)
                phase[c] += pitch / rate
                var excitation = 0.0
                if (voiced && phase[c] >= 1.0) excitation = 1.0
                if (phase[c] >= 1.0) phase[c] -= 1.0
                if (hiss) excitation = 0.15 * noise
                var sample = 0.0
                for ((k, frequency) in formants.withIndex()) {
                    val r = exp(-PI * (80.0 + 40.0 * k) / rate)
                    val a1 = 2 * r * kotlin.math.cos(2 * PI * frequency / rate)
                    val a2 = -r * r
                    val y = excitation + a1 * state[c][k][0] + a2 * state[c][k][1]
                    state[c][k][1] = state[c][k][0]
                    state[c][k][0] = y
                    sample += y / (k + 1)
                }
                buffer.putShort((sample * 1_500).coerceIn(-32_000.0, 32_000.0).toInt().toShort())
            }
        }
        file.writeBytes(buffer.array())
    }

    private fun run(command: List<String>) {
        val output = runCapturing(command)
        assertTrue(lastExit == 0, "${command.joinToString(" ")} failed:\n$output")
    }

    private var lastExit = 0

    private fun runCapturing(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(300, TimeUnit.SECONDS), "${command.first()} did not finish")
        lastExit = process.exitValue()
        return output
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private fun skip() {
        println("the libopus reference build is not present, so the SILK oracle comparison did not run")
    }

    /**
     * What `opus_decode_frame` in libopus does for a stream that is SILK
     * only and never changes mode: per Opus frame, tell SILK the frame's
     * length, the coded channels and the internal rate the bandwidth implies,
     * then call it once per 10 or 20 ms SILK frame until the Opus frame is
     * covered. In this mode the reference converts SILK's 16-bit samples to
     * float and back without changing them, so they are compared as they are.
     */
    private class SilkOnlyDriver(private val rate: Int, private val channels: Int) {
        private val silk = SilkDecoder()
        private val control = SilkDecoderControl().also {
            it.channelsApi = channels
            it.apiSampleRate = rate
        }
        var finalRange = 0L
            private set

        fun decode(packet: ByteArray, pcm: IntArray, what: String): Int {
            val parsed = OpusPacket.parse(packet, 0, packet.size)
            check(parsed.mode == OpusPacket.Mode.SILK) { "$what: the stream is not SILK only" }
            control.channelsInternal = parsed.channels
            control.internalSampleRate = when (parsed.bandwidth) {
                OpusPacket.Bandwidth.NARROW -> 8_000
                OpusPacket.Bandwidth.MEDIUM -> 12_000
                else -> 16_000
            }
            val frameSize = parsed.frameSize * rate / 48_000
            control.payloadSizeMs = maxOf(10, 1000 * frameSize / rate)
            var samples = 0
            for (frame in parsed.frames) {
                val length = frame.last - frame.first + 1
                check(length > 1) { "$what: a frame with no SILK data" }
                val range = RangeDecoder(packet, frame.first, length)
                var decoded = 0
                do {
                    decoded += silk.decode(control, 0, decoded == 0, range, pcm, (samples + decoded) * channels)
                } while (decoded < frameSize)
                // Whatever SILK leaves unread, if it is enough to hold one,
                // is a CELT frame smoothing a change of mode or of SILK's
                // bandwidth, which a stream that keeps both does not carry.
                check(range.tell() + 17 > 8 * length) { "$what: a redundant CELT frame" }
                finalRange = range.finalRange
                samples += frameSize
            }
            return samples
        }
    }

    private companion object {
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodecs-silk-oracle").also { it.mkdirs() }
    }
}

package io.github.yuroyami.audiokodecs

import io.github.yuroyami.audiokodecs.internal.opus.OpusPacket
import io.github.yuroyami.audiokodecs.internal.opus.RangeDecoder
import io.github.yuroyami.audiokodecs.internal.opus.celt.CeltDecoder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the Opus decoder against the reference implementation, run as a
 * program.
 *
 * Two things are compared for every stream. The range decoder's final state
 * after each packet must equal the one the encoder recorded, which holds only
 * if every symbol in the packet was decoded exactly as it was coded. And the
 * decoded audio is scored by `opus_compare`, the quality measure RFC 6716
 * defines for judging a decoder, against what the reference decoder produces
 * from the same packets.
 *
 * The reference programs are built from the libopus commit the port follows,
 * with its SIMD kernels disabled, under `reference/libopus/build-plain`:
 *
 * ```
 * cmake .. -DOPUS_BUILD_PROGRAMS=ON -DCMAKE_BUILD_TYPE=Release -DOPUS_DRED=OFF -DOPUS_OSCE=OFF \
 *   -DOPUS_DISABLE_INTRINSICS=ON -DOPUS_X86_PRESUME_SSE=OFF -DOPUS_X86_PRESUME_SSE2=OFF \
 *   -DOPUS_X86_MAY_HAVE_SSE=OFF -DOPUS_X86_MAY_HAVE_SSE2=OFF -DOPUS_X86_MAY_HAVE_SSE4_1=OFF \
 *   -DOPUS_X86_MAY_HAVE_AVX2=OFF
 * make opus_demo opus_compare
 * ```
 *
 * The SIMD kernels sum in a different order, which changes the last bits of a
 * float, and packet-loss concealment feeds those bits through a 24th-order
 * filter, so the default build and its own plain build differ by tens of
 * steps in concealed frames. Against the plain build this decoder's output is
 * identical sample for sample, and the tests require exactly that, with
 * `opus_compare` as a second opinion. That folder is not committed, so the
 * tests skip themselves without it, the way the other oracle suites skip
 * without their tools.
 */
class OpusDecodeOracleTest {

    private val build = File(System.getProperty("user.dir")).let { dir ->
        listOf(dir, dir.parentFile).map { File(it, "reference/libopus/build-plain") }.firstOrNull { File(it, "opus_demo").canExecute() }
    }

    @Test
    fun celtOnlyStreamsDecodeToTheEncodersRangeAndPassOpusCompare() {
        val build = build ?: return skip()
        val input = temp("celt_input.pcm")
        writeTestSignal(input, channels = 2, seconds = 2.0)
        for (channels in intArrayOf(1, 2)) {
            for (frameMs in listOf("2.5", "5", "10", "20")) {
                for (bitrate in intArrayOf(24_000, 96_000)) {
                    val what = "CELT $channels ch, $frameMs ms, $bitrate bit/s"
                    val source = if (channels == 2) input else temp("celt_input_mono.pcm").also {
                        writeTestSignal(it, channels = 1, seconds = 2.0)
                    }
                    val bits = temp("celt_${channels}_${frameMs}_$bitrate.bit")
                    run(
                        listOf(
                            File(build, "opus_demo").path, "-e", "restricted-lowdelay", "48000", "$channels", "$bitrate",
                            "-framesize", frameMs, source.path, bits.path,
                        ),
                    )
                    checkStream(build, bits, 48_000, channels, what)
                }
            }
        }
    }

    @Test
    fun celtConcealmentAndEveryOutputRateMatchTheReference() {
        val build = build ?: return skip()
        val input = temp("celt_input.pcm")
        writeTestSignal(input, channels = 2, seconds = 2.0)
        val bits = temp("celt_plc_source.bit")
        run(
            listOf(
                File(build, "opus_demo").path, "-e", "restricted-lowdelay", "48000", "2", "64000",
                "-framesize", "10", input.path, bits.path,
            ),
        )
        // Lose single packets, which pitch-based concealment covers, and a
        // run of six, long enough to fall back to shaped noise.
        val lossy = temp("celt_plc.bit")
        lossy.writeBytes(dropPackets(bits.readBytes()) { it % 9 == 4 || it in 60..65 })
        for (rate in intArrayOf(48_000, 24_000, 16_000, 12_000, 8_000)) {
            for (channels in intArrayOf(1, 2)) {
                checkStream(build, lossy, rate, channels, "CELT with losses at $rate Hz, $channels ch")
            }
        }
    }

    /** [stream] with every packet [lose] picks replaced by a lost one. */
    private fun dropPackets(stream: ByteArray, lose: (Int) -> Boolean): ByteArray {
        val input = ByteBuffer.wrap(stream).order(ByteOrder.BIG_ENDIAN)
        val out = java.io.ByteArrayOutputStream()
        var index = 0
        while (input.remaining() >= 8) {
            val length = input.int
            val range = input.int
            val packet = ByteArray(length)
            input.get(packet)
            val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            if (lose(index)) {
                header.putInt(0).putInt(0)
                out.write(header.array())
            } else {
                header.putInt(length).putInt(range)
                out.write(header.array())
                out.write(packet)
            }
            index++
        }
        return out.toByteArray()
    }

    /**
     * Decodes [bits] with both decoders, checks every packet's final range,
     * and scores the audio with `opus_compare`.
     */
    private fun checkStream(build: File, bits: File, rate: Int, channels: Int, what: String) {
        // opus_compare always reads its reference as 48 kHz stereo, the form
        // the official vectors take, and folds it down itself for a mono or
        // lower-rate comparison. The reference is decoded with the channel
        // count under test, because a mono decoder conceals losses from its
        // own mono history and so legitimately differs from a stereo one, as
        // the official suite's separate mono references reflect. A mono
        // reference is written out as two identical channels, whose fold-down
        // is the mono signal exactly.
        val reference = temp(bits.name + ".ref.pcm")
        run(listOf(File(build, "opus_demo").path, "-d", "48000", "$channels", bits.path, reference.path))
        if (channels == 1) {
            val mono = reference.readBytes()
            val stereo = ByteArray(mono.size * 2)
            for (i in 0 until mono.size / 2) {
                stereo[4 * i] = mono[2 * i]
                stereo[4 * i + 1] = mono[2 * i + 1]
                stereo[4 * i + 2] = mono[2 * i]
                stereo[4 * i + 3] = mono[2 * i + 1]
            }
            reference.writeBytes(stereo)
        }

        val decoder = CeltOnlyDriver(rate, channels)
        val pcm = FloatArray(5760 * channels)
        val out = java.io.ByteArrayOutputStream()
        fun emit(samples: Int) {
            val bytes = ByteBuffer.allocate(samples * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until samples * channels) bytes.putShort(toInt16(pcm[i]))
            out.write(bytes.array())
        }
        val stream = ByteBuffer.wrap(bits.readBytes()).order(ByteOrder.BIG_ENDIAN)
        var packets = 0
        var lost = 0
        while (stream.remaining() >= 8) {
            val length = stream.int
            val expectedRange = stream.int.toLong() and 0xFFFF_FFFFL
            val packet = ByteArray(length)
            stream.get(packet)
            packets++
            // opus_demo conceals a loss only once the next packet arrives, and
            // never conceals losses at the end of the file. CELT frames carry
            // no in-band redundancy, so every lost frame is concealed.
            if (length == 0) {
                lost++
                continue
            }
            repeat(lost) { emit(decoder.conceal(pcm)) }
            lost = 0
            emit(decoder.decode(packet, pcm))
            assertEquals(expectedRange, decoder.finalRange, "$what: final range of packet ${packets - 1}")
        }
        assertTrue(packets > 0, "$what: no packets")
        val ours = temp(bits.name + ".ours.pcm")
        ours.writeBytes(out.toByteArray())
        assertEquals(reference.length() * channels * rate / (2 * 48_000), ours.length(), "$what: decoded length")
        val exact = temp(bits.name + ".exact.pcm")
        run(listOf(File(build, "opus_demo").path, "-d", "$rate", "$channels", bits.path, exact.path))
        val theirs = exact.readBytes()
        val mine = ours.readBytes()
        assertEquals(theirs.size, mine.size, "$what: decoded length against the reference at this rate")
        val firstDifference = theirs.indices.firstOrNull { theirs[it] != mine[it] }
        assertEquals(null, firstDifference, "$what: first byte that differs from the reference decoder")
        // opus_compare scores against a 48 kHz reference. Below that rate the
        // decoder keeps a band-limited history, so concealment there is a
        // different signal from concealment at 48 kHz, and the reference
        // decoder fails this comparison against itself; the exact comparison
        // above at the decoding rate is the check that applies.
        if (rate != 48_000) return
        val compare = runCapturing(
            listOf(File(build, "opus_compare").path, *(if (channels == 2) arrayOf("-s") else arrayOf()), "-r", "$rate", reference.path, ours.path),
        )
        assertTrue(compare.contains("PASSES"), "$what: opus_compare says\n$compare")
    }

    /** `FLOAT2INT16`: scale, clamp, and round half to even as `lrintf` does. */
    private fun toInt16(x: Float): Short {
        val scaled = (x * 32768f).coerceIn(-32768f, 32767f)
        return round(scaled.toDouble()).toInt().toShort()
    }

    /**
     * A signal that exercises every part of CELT: steady tones, a chirp,
     * noise, sharp clicks that force short blocks, and channels that differ.
     */
    private fun writeTestSignal(file: File, channels: Int, seconds: Double) {
        val rate = 48_000
        val frames = (rate * seconds).toInt()
        val buffer = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        var seed = 12345
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            seed = seed * 1_103_515_245 + 12_345
            val noise = ((seed ushr 16) and 0x7FFF) / 32768.0 - 0.5
            val click = if (i % 9_600 < 24) 0.8 else 0.0
            val chirp = sin(2 * PI * (200 + 4_000 * t / seconds) * t)
            for (c in 0 until channels) {
                val tone = sin(2 * PI * (440.0 * (c + 1)) * t)
                val value = 0.3 * tone + 0.2 * chirp + 0.1 * noise + click * (if (c == 0) 1 else -1)
                buffer.putShort((value.coerceIn(-1.0, 1.0) * 20_000).toInt().toShort())
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
        println("the libopus reference build is not present, so the Opus oracle comparison did not run")
    }

    /**
     * What `opus_decode_frame` in libopus does for a stream that is CELT
     * only and never changes mode: per frame, set the end band from the
     * bandwidth, start the range decoder on the frame, and decode; for a lost
     * packet, conceal as long as the last packet lasted. With no mode changes
     * there are no redundant frames or transitions to handle. The reference
     * program decodes to 16-bit samples, for which `opus_decode` soft clips
     * every decoded packet but not a concealed one, so this does the same.
     */
    private class CeltOnlyDriver(rate: Int, private val channels: Int) {
        private val celt = CeltDecoder(channels, rate)
        private val toOutput = rate / 48_000.0
        private var lastDuration = 0
        private val softClipMemory = FloatArray(2)
        var finalRange = 0L
            private set

        fun decode(packet: ByteArray, pcm: FloatArray): Int {
            val parsed = OpusPacket.parse(packet, 0, packet.size)
            check(parsed.mode == OpusPacket.Mode.CELT) { "the stream is not CELT only" }
            celt.endBand = when (parsed.bandwidth) {
                OpusPacket.Bandwidth.NARROW -> 13
                OpusPacket.Bandwidth.MEDIUM, OpusPacket.Bandwidth.WIDE -> 17
                OpusPacket.Bandwidth.SUPER_WIDE -> 19
                OpusPacket.Bandwidth.FULL -> 21
            }
            celt.streamChannels = parsed.channels
            celt.startBand = 0
            val frameSize = (parsed.frameSize * toOutput).toInt()
            var samples = 0
            for (frame in parsed.frames) {
                val length = frame.last - frame.first + 1
                if (length <= 1) {
                    celt.decode(null, 0, 0, pcm, samples * channels, frameSize, null)
                    finalRange = 0
                } else {
                    val range = RangeDecoder(packet, frame.first, length)
                    celt.decode(packet, frame.first, length, pcm, samples * channels, frameSize, range)
                    finalRange = range.finalRange
                }
                samples += frameSize
            }
            lastDuration = samples
            softClip(pcm, samples)
            return samples
        }

        fun conceal(pcm: FloatArray): Int {
            celt.decode(null, 0, 0, pcm, 0, lastDuration, null)
            return lastDuration
        }

        /**
         * `opus_pcm_soft_clip` from libopus `opus.c`: bends samples beyond
         * full scale back inside it smoothly instead of clipping them, and
         * carries the bend into the next call.
         */
        private fun softClip(x: FloatArray, n: Int) {
            val c = channels
            if (n < 1) return
            for (i in 0 until n * c) x[i] = maxOf(-2f, minOf(2f, x[i]))
            for (ch in 0 until c) {
                var a = softClipMemory[ch]
                for (i in 0 until n) {
                    val at = i * c + ch
                    if (x[at] * a >= 0) break
                    x[at] = x[at] + a * x[at] * x[at]
                }
                var curr = 0
                val x0 = x[ch]
                while (true) {
                    var i = curr
                    while (i < n) {
                        if (x[i * c + ch] > 1 || x[i * c + ch] < -1) break
                        i++
                    }
                    if (i == n) {
                        a = 0f
                        break
                    }
                    var peakPos = i
                    var start = i
                    var end = i
                    var maxval = abs(x[i * c + ch])
                    while (start > 0 && x[i * c + ch] * x[(start - 1) * c + ch] >= 0) start--
                    while (end < n && x[i * c + ch] * x[end * c + ch] >= 0) {
                        if (abs(x[end * c + ch]) > maxval) {
                            maxval = abs(x[end * c + ch])
                            peakPos = end
                        }
                        end++
                    }
                    val special = start == 0 && x[i * c + ch] * x[ch] >= 0
                    a = (maxval - 1) / (maxval * maxval)
                    a += a * 2.4e-7f
                    if (x[i * c + ch] > 0) a = -a
                    for (k in start until end) x[k * c + ch] = x[k * c + ch] + a * x[k * c + ch] * x[k * c + ch]
                    if (special && peakPos >= 2) {
                        var offsetValue = x0 - x[ch]
                        val delta = offsetValue / peakPos
                        for (k in curr until peakPos) {
                            offsetValue -= delta
                            x[k * c + ch] += offsetValue
                            x[k * c + ch] = maxOf(-1f, minOf(1f, x[k * c + ch]))
                        }
                    }
                    curr = end
                    if (curr == n) break
                }
                softClipMemory[ch] = a
            }
        }
    }

    private companion object {
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodecs-opus-oracle").also { it.mkdirs() }
    }
}

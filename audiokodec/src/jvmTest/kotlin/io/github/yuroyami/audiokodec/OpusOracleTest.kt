package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.ogg.OggReader
import io.github.yuroyami.audiokodec.internal.opus.OpusPacket
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks how Opus packets are read against files a real encoder wrote.
 *
 * The framing can be checked without a codec, and checked exactly. An Ogg page
 * records how many samples the stream has produced by the end of it, and every
 * packet on that page says how many it decodes to, so the two have to agree to
 * the sample. If the configuration table were wrong about a frame length, or
 * the frame packing were misread, the totals would drift apart immediately.
 *
 * The test skips itself when ffmpeg is missing.
 */
class OpusOracleTest {

    private val ffmpeg: String? by lazy { findOnPath("ffmpeg") }

    /**
     * Every packet's own sample count adds up to what the pages record.
     *
     * This covers several frame lengths on purpose, because the length is what
     * the configuration number mostly decides and the encoder picks it.
     */
    @Test
    fun packetLengthsAddUpToTheGranulePositions() {
        val ffmpeg = ffmpeg ?: return skip()
        for (duration in intArrayOf(10, 20, 60)) {
            for (channels in intArrayOf(1, 2)) {
                val what = "Opus, ${duration}ms frames, $channels channels"
                val opus = encode(ffmpeg, channels, duration)
                val bytes = opus.readBytes()

                val info = AudioKodec.probe(bytes)
                assertEquals(AudioCodec.OPUS, info.codec, "$what: codec")
                assertEquals(48_000, info.format.sampleRate, "$what: Opus always reports 48 kHz")

                val reader = OggReader(SourceReader(bytes.asAudioSource(), DecodeOptions.DEFAULT), DecodeOptions.DEFAULT)
                val head = reader.readStreamHeads().first()
                val packets = reader.packets(head.serialNumber, 0L)

                var counted = 0L
                var lastGranule = -1L
                var lastPacketSamples = 0
                var index = 0
                while (true) {
                    val packet = packets.next() ?: break
                    // The first two packets are the identification header and
                    // the comments, and neither is audio.
                    if (index++ < 2) continue
                    val parsed = OpusPacket.parse(packet.bytes, 0, packet.length)
                    assertEquals(
                        channels > 1, parsed.stereo,
                        "$what: packet $index disagrees with the file about stereo",
                    )
                    counted += parsed.samples
                    lastPacketSamples = parsed.samples
                    if (packet.granule >= 0) lastGranule = packet.granule
                }

                assertTrue(counted > 0, "$what: no audio packets were read")
                // The granule position counts samples at 48 kHz, pre-skip
                // included. The packets decode to at least that, and to less
                // than one packet more: the last page's granule is clamped to
                // where the audio really ends, so the final packet is part
                // padding and the difference is what the encoder threw away.
                assertTrue(
                    counted >= lastGranule && counted - lastGranule < lastPacketSamples,
                    "$what: the packets decode to $counted samples, the pages say $lastGranule, " +
                        "and the last packet holds only $lastPacketSamples",
                )
            }
        }
    }

    /**
     * A frame length the encoder was told to use is the one that comes back.
     *
     * This is the check that the configuration table's frame sizes are right
     * rather than merely self-consistent: ffmpeg is told 10, 20 or 60
     * milliseconds and the number read out of the first byte has to match.
     */
    @Test
    fun theFrameLengthIsTheOneTheEncoderWasAskedFor() {
        val ffmpeg = ffmpeg ?: return skip()
        for ((duration, expected) in mapOf(10 to 480, 20 to 960, 40 to 1920, 60 to 2880)) {
            val opus = encode(ffmpeg, 2, duration)
            val bytes = opus.readBytes()
            val reader = OggReader(SourceReader(bytes.asAudioSource(), DecodeOptions.DEFAULT), DecodeOptions.DEFAULT)
            val head = reader.readStreamHeads().first()
            val packets = reader.packets(head.serialNumber, 0L)

            var index = 0
            var checked = 0
            while (checked < 20) {
                val packet = packets.next() ?: break
                if (index++ < 2) continue
                val parsed = OpusPacket.parse(packet.bytes, 0, packet.length)
                assertEquals(
                    expected, parsed.samples,
                    "asked for ${duration}ms and packet $index decodes to ${parsed.samples} samples",
                )
                checked++
            }
            assertTrue(checked > 10, "${duration}ms: only $checked packets were checked")
        }
    }

    private fun encode(ffmpeg: String, channels: Int, frameMillis: Int): File {
        val rate = 48_000
        val name = "opus_${channels}_$frameMillis"
        val wav = temp("$name.wav")
        val frames = rate * 2
        val pcm = FloatArray(frames * channels)
        for (c in 0 until channels) {
            for (f in 0 until frames) {
                val t = f / rate.toDouble()
                pcm[f * channels + c] =
                    (0.6 * kotlin.math.sin(2.0 * Math.PI * (300.0 * (c + 1) * t + 1_500.0 * t * t))).toFloat()
            }
        }
        wav.writeBytes(AudioKodec.encode(pcm, AudioFormat(rate, channels), EncodeTarget.wav(16)))

        val out = temp("$name.opus")
        out.delete()
        run(
            listOf(
                ffmpeg, "-y", "-v", "quiet", "-i", wav.absolutePath,
                "-c:a", "libopus", "-b:a", "96k", "-frame_duration", "$frameMillis",
                out.absolutePath,
            ),
            "encoding $name",
        )
        return out
    }

    private fun run(command: List<String>, what: String) {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(180, TimeUnit.SECONDS), "${command.first()} did not finish")
        assertEquals(0, process.exitValue(), "$what failed:\n$output")
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private fun skip() {
        org.junit.Assume.assumeTrue("ffmpeg is not on the path, so the Opus comparison did not run", false)
    }

    private companion object {
        val workDirectory: File = File(
            System.getProperty("java.io.tmpdir"), "audiokodec-opus-oracle",
        ).also { it.mkdirs() }

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

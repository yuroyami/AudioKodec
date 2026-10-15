package io.github.yuroyami.audiokodec.docs

import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.EncodeTarget
import io.github.yuroyami.audiokodec.Signals
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import org.junit.Assume.assumeTrue
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.sin

/**
 * The inputs the documentation samples run on, all made here: a stereo
 * signal, files AudioKodec encodes from it, files ffmpeg encodes from it when
 * ffmpeg is on the path, and a small PNG. None of this is shown in the
 * documentation.
 */
internal object Fixtures {
    const val RATE = 44_100
    const val CHANNELS = 2

    val format = AudioFormat(RATE, CHANNELS)

    val directory: File by lazy { createTempDirectory("audiokodec-docs").toFile().apply { deleteOnExit() } }

    /** [frames] frames of a stereo chord, a different one in each channel, at [rate] Hz. */
    fun music(frames: Int, rate: Int = RATE): FloatArray {
        val out = FloatArray(frames * CHANNELS)
        for (f in 0 until frames) {
            val t = f.toDouble() / rate
            out[2 * f] = (0.3 * sin(2 * PI * 220 * t) + 0.2 * sin(2 * PI * 330 * t)).toFloat()
            out[2 * f + 1] = (0.3 * sin(2 * PI * 277 * t) + 0.2 * sin(2 * PI * 415 * t)).toFloat()
        }
        return out
    }

    /** Two seconds of [music]. */
    val samples: FloatArray by lazy { music(2 * RATE) }

    val tags: TagBundle by lazy {
        TagBundle.EMPTY.edit {
            set(TagField.TITLE, "Two Seconds")
            set(TagField.ARTIST, listOf("First Artist", "Second Artist"))
            set(TagField.ALBUM, "Test Signals")
            set(TagField.TRACK_NUMBER, 3)
            set(TagField.COMMENT, "made by the documentation tests")
        }
    }

    /** [samples] as a tagged 16-bit FLAC file. */
    val flac: ByteArray by lazy { AudioKodec.encode(samples, format, EncodeTarget.flac(16), tags) }

    /** Two seconds of a 1 kHz tone at -20 dBFS in both channels, as 16-bit FLAC: about -20 LUFS. */
    val tone: ByteArray by lazy {
        AudioKodec.encode(Signals.sine(2 * RATE, CHANNELS, RATE, 1000.0, amplitude = 0.1f), format, EncodeTarget.flac(16))
    }

    /** The audio payload of a file, which is what a tag edit must leave alone. */
    fun payload(bytes: ByteArray): ByteArray {
        val info = AudioKodec.probe(bytes)
        val end = if (info.dataSize >= 0) info.dataOffset + info.dataSize else bytes.size.toLong()
        return bytes.copyOfRange(info.dataOffset.toInt(), end.toInt())
    }

    /** A 64 by 64 PNG. */
    val png: ByteArray by lazy {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(0x3A, 0x2E, 0x8C)
        g.fillRect(0, 0, 64, 64)
        g.color = Color.WHITE
        g.fillOval(16, 16, 32, 32)
        g.dispose()
        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** The ffmpeg executable on the path; the calling test is skipped when there is none. */
    fun ffmpeg(): String {
        val found = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "ffmpeg") }
            .firstOrNull { it.canExecute() }
        assumeTrue("ffmpeg is not on PATH", found != null)
        return found!!.path
    }

    /**
     * Interleaved stereo [samples] at [rate] Hz, encoded by ffmpeg with
     * [arguments] into a file named [name], returned as bytes.
     */
    fun ffmpegEncode(samples: FloatArray, name: String, vararg arguments: String, rate: Int = RATE): ByteArray {
        val executable = ffmpeg()
        val raw = File(directory, "$name.f32")
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in samples) buffer.putFloat(v)
        raw.writeBytes(buffer.array())
        val out = File(directory, name)
        val command = listOf(executable, "-y", "-v", "error", "-f", "f32le", "-ar", "$rate", "-ac", "$CHANNELS", "-i", raw.path) +
            arguments + out.path
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { "ffmpeg failed: $log" }
        return out.readBytes()
    }
}

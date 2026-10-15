package io.github.yuroyami.audiokodec.sample

import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.EncodeTarget
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagPicture
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.sin

/** A file the viewer tests open, with what its summary must say. */
internal class SampleFile(
    val label: String,
    val file: File,
    val container: AudioContainer,
    val codec: AudioCodec,
    val sampleRate: Int,
    val channels: Int,
    /** Exact length in frames, or null when only [seconds] is known, as for a lossy coding. */
    val frames: Long?,
    val seconds: Double,
    /** The title the file was tagged with, or null for a file without tags. */
    val title: String? = null,
    /** The length the header states, when it is exact. */
    val headerFrames: Long? = frames,
)

/**
 * Files of every container AudioKodec decodes, made here: by AudioKodec's own
 * encoders, by ffmpeg used as a program, from the library's own tiny test
 * vectors for the codings nothing here can encode, and by hand for DSD.
 */
internal object ViewerFixtures {
    const val RATE = 44_100
    const val SECONDS = 2
    const val TITLE = "Sample viewer test"

    val directory: File by lazy { createTempDirectory("audiokodec-viewer").toFile().apply { deleteOnExit() } }

    val signal: FloatArray by lazy { TestSignal.stereo(SECONDS, RATE) }

    /** A 300 by 300 PNG, drawn here, for cover art. */
    val coverPng: ByteArray by lazy {
        val image = BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.paint = GradientPaint(0f, 0f, Color(0x3A, 0x2E, 0x8C), 300f, 300f, Color(0xE0, 0x6C, 0x3C))
        g.fillRect(0, 0, 300, 300)
        g.color = Color.WHITE
        g.fillOval(90, 90, 120, 120)
        g.dispose()
        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    val tags: TagBundle by lazy {
        TagBundle.EMPTY.edit {
            set(TagField.TITLE, TITLE)
            set(TagField.ARTIST, listOf("First Artist", "Second Artist"))
            set(TagField.ALBUM, "Test Signals")
            set(TagField.TRACK_NUMBER, 3)
            set(TagField.DATE, "2026")
            addPicture(TagPicture(mimeType = "image/png", bytes = coverPng, width = 300, height = 300))
        }
    }

    private fun write(name: String, bytes: ByteArray): File = File(directory, name).apply { writeBytes(bytes) }

    /** The containers AudioKodec writes itself, tagged where the container carries tags. */
    fun library(): List<SampleFile> {
        val format = AudioFormat(RATE, 2)
        val frames = (SECONDS * RATE).toLong()
        fun file(label: String, name: String, target: EncodeTarget, container: AudioContainer, codec: AudioCodec, tagged: Boolean = true) =
            SampleFile(
                label, write(name, AudioKodec.encode(signal, format, target, if (tagged) tags else null)),
                container, codec, RATE, 2, frames, SECONDS.toDouble(), if (tagged) TITLE else null,
            )
        return listOf(
            file("WAV", "library.wav", EncodeTarget.wav(16), AudioContainer.WAV, AudioCodec.PCM_INTEGER),
            file("AIFF", "library.aiff", EncodeTarget.aiff(24), AudioContainer.AIFF, AudioCodec.PCM_INTEGER),
            file("AU", "library.au", EncodeTarget.au(16), AudioContainer.AU, AudioCodec.PCM_INTEGER, tagged = false),
            file("FLAC", "library.flac", EncodeTarget.flac(16), AudioContainer.FLAC, AudioCodec.FLAC),
            file("ALAC in MP4", "library.m4a", EncodeTarget.alac(16), AudioContainer.MP4, AudioCodec.ALAC),
            SampleFile(
                "WAV 5.1", surroundWav(), AudioContainer.WAV, AudioCodec.PCM_INTEGER, 48_000, 6, 48_000, 1.0,
            ),
        )
    }

    /** One second of 5.1 at 48 kHz in WAV, with a different tone in every channel so that a wrong order would show. */
    fun surroundWav(): File {
        val rate = 48_000
        val tones = FloatArray(rate * 6)
        for (f in 0 until rate) for (c in 0 until 6) {
            tones[f * 6 + c] = (0.1 * (c + 1) * sin(2 * PI * (220.0 * (c + 1)) * f / rate)).toFloat()
        }
        return write("surround.wav", AudioKodec.encode(tones, AudioFormat(rate, ChannelLayout.SURROUND_5_1), EncodeTarget.wav(16)))
    }

    /** The containers ffmpeg writes. */
    fun ffmpeg(ffmpeg: Ffmpeg): List<SampleFile> {
        val raw = File(directory, "signal.f32")
        if (!raw.isFile) {
            val buffer = ByteBuffer.allocate(signal.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in signal) buffer.putFloat(v)
            raw.writeBytes(buffer.array())
        }
        val metadata = listOf("-metadata", "title=$TITLE", "-metadata", "artist=First Artist")
        val list = ArrayList<SampleFile>()
        fun add(
            label: String, name: String, container: AudioContainer, codec: AudioCodec, arguments: List<String>,
            rate: Int = RATE, channels: Int = 2, exact: Boolean = false, tagged: Boolean = true, headerExact: Boolean = exact,
        ) {
            val destination = File(directory, name)
            checkNotNull(ffmpeg.encode(raw, RATE, 2, destination, arguments + if (tagged) metadata else emptyList())) {
                "ffmpeg could not write $name"
            }
            val frames = if (exact) SECONDS.toLong() * rate else null
            list += SampleFile(
                label, destination, container, codec, rate, channels, frames, SECONDS.toDouble(), if (tagged) TITLE else null,
                if (headerExact) frames else null,
            )
        }
        add("RF64", "ffmpeg-rf64.wav", AudioContainer.RF64, AudioCodec.PCM_INTEGER, listOf("-c:a", "pcm_s24le", "-rf64", "always"), exact = true, tagged = false)
        add("Wave64", "ffmpeg.w64", AudioContainer.WAVE64, AudioCodec.PCM_INTEGER, listOf("-c:a", "pcm_s16le"), exact = true, tagged = false)
        // ffmpeg's packet table counts the short last packet as a whole one, which the header
        // then states as the length; the decoder checks that packet and stops at the real end.
        add("CAF", "ffmpeg.caf", AudioContainer.CAF, AudioCodec.ALAC, listOf("-c:a", "alac"), exact = true, tagged = false, headerExact = false)
        add("MP3", "ffmpeg.mp3", AudioContainer.MPEG_AUDIO, AudioCodec.MP3, listOf("-c:a", "libmp3lame", "-b:a", "192k"))
        add("MP2", "ffmpeg.mp2", AudioContainer.MPEG_AUDIO, AudioCodec.MP2, listOf("-c:a", "mp2", "-b:a", "192k"), tagged = false)
        add("Ogg Vorbis", "ffmpeg-vorbis.ogg", AudioContainer.OGG, AudioCodec.VORBIS, listOf("-c:a", "libvorbis", "-q:a", "4"))
        add("Ogg Opus", "ffmpeg.opus", AudioContainer.OGG, AudioCodec.OPUS, listOf("-c:a", "libopus", "-b:a", "96k"), rate = 48_000)
        add("Ogg FLAC", "ffmpeg-flac.ogg", AudioContainer.OGG, AudioCodec.FLAC, listOf("-c:a", "flac", "-f", "ogg"), exact = true)
        add(
            "Ogg Speex", "ffmpeg.spx", AudioContainer.OGG, AudioCodec.SPEEX,
            listOf("-ar", "16000", "-ac", "1", "-c:a", "libspeex", "-f", "ogg"), rate = 16_000, channels = 1,
        )
        add("AAC in MP4", "ffmpeg-aac.m4a", AudioContainer.MP4, AudioCodec.AAC, listOf("-c:a", "aac", "-b:a", "128k"))
        add("ADTS AAC", "ffmpeg.aac", AudioContainer.ADTS, AudioCodec.AAC, listOf("-c:a", "aac", "-b:a", "128k", "-f", "adts"), tagged = false)
        add("Matroska", "ffmpeg.mka", AudioContainer.MATROSKA, AudioCodec.FLAC, listOf("-c:a", "flac"), exact = true)
        add("WebM", "ffmpeg.webm", AudioContainer.MATROSKA, AudioCodec.OPUS, listOf("-c:a", "libopus", "-b:a", "96k"), rate = 48_000)
        add("WavPack", "ffmpeg.wv", AudioContainer.WAVPACK, AudioCodec.WAVPACK, listOf("-c:a", "wavpack"), exact = true)
        add("True Audio", "ffmpeg.tta", AudioContainer.TTA, AudioCodec.TTA, listOf("-c:a", "tta"), exact = true, tagged = false)
        add("AC-3", "ffmpeg.ac3", AudioContainer.AC3, AudioCodec.AC3, listOf("-c:a", "ac3", "-b:a", "192k"), tagged = false)
        return list
    }

    /** The codings nothing here encodes: the library's own tiny test vectors, and DSD built by hand. */
    fun vectors(): List<SampleFile> {
        fun vector(label: String, name: String, base64: String, container: AudioContainer, codec: AudioCodec, rate: Int, frames: Long) =
            SampleFile(label, write(name, Base64.getDecoder().decode(base64)), container, codec, rate, 1, frames, frames.toDouble() / rate)
        return listOf(
            vector("Monkey's Audio", "tiny.ape", APE, AudioContainer.APE, AudioCodec.APE, 44_100, 300),
            vector("Musepack", "tiny.mpc", MUSEPACK, AudioContainer.MUSEPACK, AudioCodec.MUSEPACK, 48_000, 1500),
            vector("AMR-NB", "tiny.amr", AMR_NB, AudioContainer.AMR_NB, AudioCodec.AMR_NB, 8_000, 1600),
            vector("iLBC", "tiny.lbc", ILBC, AudioContainer.ILBC, AudioCodec.ILBC, 8_000, 320),
            SampleFile("DSF", write("tone.dsf", dsf()), AudioContainer.DSF, AudioCodec.DSD, 88_200, 1, DSD_BITS / 32L, DSD_BITS / 2_822_400.0),
        )
    }

    /** A file AudioKodec names but has no decoder for: a MIDI file with one empty track. */
    fun midi(): File {
        val header = "MThd".toByteArray() + byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, 0, 0x60)
        val track = "MTrk".toByteArray() + byteArrayOf(0, 0, 0, 4, 0, 0xFF.toByte(), 0x2F, 0)
        return write("empty.mid", header + track)
    }

    /** A FLAC file cut off in the middle of its audio. */
    fun truncatedFlac(): File {
        val whole = AudioKodec.encode(signal, AudioFormat(RATE, 2), EncodeTarget.flac(16))
        return write("truncated.flac", whole.copyOf(whole.size / 2))
    }

    /** A fifth of a second of DSD64. */
    private const val DSD_BITS = 2_822_400 / 5

    /** A 1 kHz tone at half scale in mono DSD64, modulated by a second order loop, in a DSF file. */
    private fun dsf(): ByteArray {
        val block = 4096
        val blocks = (DSD_BITS / 8 + block - 1) / block
        val data = ByteArray(blocks * block)
        var i1 = 0.0
        var i2 = 0.0
        var y = 0.0
        for (k in 0 until DSD_BITS) {
            val x = 0.5 * sin(2 * PI * 1000.0 * k / 2_822_400)
            i1 += x - y
            i2 += i1 - y
            y = if (i2 >= 0) 1.0 else -1.0
            // DSF stores the earliest bit of each byte in its lowest bit.
            if (y > 0) data[k / 8] = (data[k / 8].toInt() or (1 shl (k % 8))).toByte()
        }
        val out = ByteBuffer.allocate(28 + 52 + 12 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("DSD ".toByteArray()).putLong(28).putLong(out.capacity().toLong()).putLong(0)
        out.put("fmt ".toByteArray()).putLong(52).putInt(1).putInt(0).putInt(1).putInt(1)
            .putInt(2_822_400).putInt(1).putLong(DSD_BITS.toLong()).putInt(block).putInt(0)
        out.put("data".toByteArray()).putLong(12L + data.size).put(data)
        return out.array()
    }

    // The library's own test vectors, copied from its common test suite.

    /** Monkey's Audio, 300 frames of 24-bit mono at 44.1 kHz (ApeTest.TINY_24). */
    private const val APE: String =
        "TUFDIJYP1jM0AAAAGAAAAAQAAAAsAAAA3AIAAAAAAAAAAAAA9nSDq1dtlw55riKk8s82tdAHAAAAIAEALAEAAAEAAAAYAAEARKwA" +
            "AHwAAABSSUZGqAMAAFdBVkVmbXQgEAAAAAEAAQBErAAAzAQCAAMAGABkYXRhhAMAAPJShGYA//8A/ww9AOQL6/+sgKOtCXGzDtk/" +
            "NeQ0qeGr3GtTcyXYBRLIJLP6hFUw/dRCCr1E8KYYcHRpOQKk73OLUE7xRmAtZjs01ZGqTSn7md+Mas9wnaqW+Z1OKpb5IAnXuYX7" +
            "xJ4QSg8kRFj4A228TXt1gEVH9UxBC4wwJVoqBET2LV523J7ZHEnQDCuS5gBE4fCBHXMjchDcWA8TyVYhADIB0baVmwuBaYD/Adn6" +
            "CN8O3KXD0+nSFHQ/nfTOjJyn0PlOhR1I1yXSVe187iADfjZxYj12Q/J0Wp43nwUZA53BkoPOfDQ0/pH4fTlbc+ZFeQva7tPryoQi" +
            "jCvVpEmcxRRrF8PhbFHdwuvWCRQi/vvCapoX4cso2ARiZPq0jX6DUfEQSNWnea3inOtJ+PY2HUSZfdZQRFGVNIqBOd68w0YgOX5S" +
            "rEpCriwJfBAcnFa0PQENYmnYVW0snIpYb0r/YSvwv5HgF2IhJrlsbM7Z4UzyzgLyY5GmyRQPbAKtTbMzzF728dpKS+3iwsgIbtXo" +
            "nky9YJzfbXJUFyGYfMoYfJByFpUgciFFyl5NwYH+oS2FkC7EIOY06vLOxRwEORz7kVgOII3S4EN72IxvOYg47BkcJc1mbisUxOkU" +
            "5TnNg8nhneiDOFZ0CNp5NsZJBmDTizCnmn2FyxXM2QNY68/KIGTB5iPrqPJ6T1F2pFIWneMo1NSc3mRUxqS/Q5kjdZKV3DP/qqL1" +
            "OgoGTPq1zrLW/p5dARDwMTo0MheYBgBZjmqt7HkwAIfvls4eoQMfW72YjvbGTnPqtf4v/EaffnpDnugcpANHTnza1EsKDqbmwWZD" +
            "zTVm/qJLQwnxRqg/9Prd/IfW/ZHQHXyYB5o/MHZFyO+U1E+G9YxkqvrvtAtpaMHxJkjQh5Y5d037aYEdpOwZ2M0uKt+xP/p0WiTU" +
            "4k4AJstZpMbYDOJGPx0GAtUexWbywnQX2g4HAAAAAA=="

    /** Musepack SV8, 1500 frames of mono at 48 kHz (MusepackTest.TINY). */
    private const val MUSEPACK: String =
        "TVBDS1NIDWVdGzgIi1wAJQtSRwwBAAAAAAAAAABFSQdRAR4BU08Igy0AAABBUIMl4A7WzA+qqiTB5CkTJhT8KfxTwKdRSqKohc6P" +
            "xqChhiR9XgyVmZtj2Tv9NM4nzqfR3NncaTv2v5L3uJiC+ujSBkAKldp68KJGNK9CJV9/f7odvhHcYdDCP7sGrxDoNUyhwPkC3bET" +
            "TgOtWzAEpmiOtWEeURRB+Swh3D+++z/5dp4JsJLqTwBMBGpzwBbxHqDwBcBFqTwBb5HqDsBb5F+++/yal138k1W4hKZjwe+UYU7D" +
            "IQVx2ZKxCSzkesgmHGoLVP332nPuu0M+KV0ICTkOhxnDwTaheQHI1BSfPkOPTAMdQJBqWPP41xOn5fBY7oJkptWQCf4yDx+QwfmQ" +
            "gUteLgrd+FV/2WU0IQqoRSyaKLcmNX6gUKZxUh+JYhCP2Zn/6gWYFCwCg6BQtAoagUHAKLjR6qA5yItwE5yP4Ba+QYMKIIthu3Th" +
            "u3EgjkcjkcjkcjkcjkcjmZSYxLRllHn/6bFdCd6/g6tf//1MGhKYMCUwaEpiS38Fb//+HTGgZAWCjzDfxn//CQVCQJihnXzP/Ghg" +
            "ESoFTSvmf/2Em3L/xz/gU1QGARLAU0UD"

    /** Ten AMR-NB frames at 7.4 kbit/s (AmrTest.TINY). */
    private const val AMR_NB: String =
        "IyFBTVIKJKtXh+7s+Okzh6nrTRpXdEQlUEAktVEH2U5m+nGxvsnA5CxelA7PkCSmY4ZSz8jsSxjnlbttGyC4HJfAJKYnBjcemT4J" +
            "ZnuYiJ0abQS+0LAktY4GNDvazr0/z+DYP9wZZNsf4CS1YY0/XLo+Dc2BRfev9+ri7U/QJPOBG92+fsEnl97QcEpvEhvy9EAk8Y5s" +
            "NY/6PuXYx0Rb3KPfbKJCQCR4TdcAH9PxedHKyLXtVqC0P2LQJCtN1SC//iwFfj6k2nAa38xBe9A="

    /** Two 20 ms iLBC frames (IlbcTest.TINY). */
    private const val ILBC: String =
        "IyFpTEJDMjAKoz2X3Uyk+N+Pg4FA8Y16O3w5FQ20YjQF7Re1+L0WaI0o/EVwE2KjPZvM1/eHhTwsFA+XBh2GREzyD/mVmeufcR2d" +
            "gUWXjGMfGAZQEg=="
}

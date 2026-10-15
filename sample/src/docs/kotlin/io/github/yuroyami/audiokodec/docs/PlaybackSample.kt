package io.github.yuroyami.audiokodec.docs

// --8<-- [start:imports-jvm]
import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.Pcm
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.AudioFormat as LineFormat
// --8<-- [end:imports-jvm]
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.EncodeTarget
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

// --8<-- [start:jvm]
/** Plays [bytes] on the default audio output. Returns false when there is no output for its format. */
fun playOnJvm(bytes: ByteArray): Boolean {
    AudioKodec.open(bytes, DecodeOptions(reuseBuffers = true)).use { decoder ->
        val format = LineFormat(decoder.format.sampleRate.toFloat(), 16, decoder.format.channels, true, false)
        if (!AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, format))) return false
        val line = AudioSystem.getSourceDataLine(format)
        line.open(format)
        line.start()
        writePcm16(decoder) { pcm, count -> line.write(pcm, 0, count) }
        line.drain()
        line.close()
        return true
    }
}

/** Decodes all of [decoder] to 16-bit signed little-endian PCM, handing each block to [write]. */
fun writePcm16(decoder: AudioDecoder, write: (bytes: ByteArray, count: Int) -> Unit) {
    var ints = IntArray(0)
    var bytes = ByteArray(0)
    while (true) {
        val block = decoder.nextFrame() ?: break
        val count = block.sampleCount
        if (ints.size < count) ints = IntArray(count)
        if (bytes.size < count * 2) bytes = ByteArray(count * 2)
        Pcm.intFromFloat(block.samples, 16, count, ints)  // clamps and rounds
        for (i in 0 until count) {
            bytes[2 * i] = ints[i].toByte()
            bytes[2 * i + 1] = (ints[i] shr 8).toByte()
        }
        write(bytes, count * 2)
    }
}
// --8<-- [end:jvm]

// --8<-- [start:android]
/** Interleaved Float32 as the 16-bit samples that `AudioTrack.write(ShortArray, ...)` takes. */
fun toShorts(samples: FloatArray, count: Int): ShortArray {
    val ints = Pcm.intFromFloat(samples, 16, count)  // clamps and rounds
    return ShortArray(count) { ints[it].toShort() }
}
// --8<-- [end:android]

// --8<-- [start:planar]
/** Interleaved Float32 as one array per channel, the layout AVAudioPCMBuffer and Web Audio use. */
fun toPlanes(samples: FloatArray, channels: Int, frames: Int): Array<FloatArray> =
    Pcm.deinterleave(samples, channels, frames)
// --8<-- [end:planar]

class PlaybackSample {
    private val bytes = AudioKodec.encode(
        floatArrayOf(0f, 0.5f, -0.5f, 1f, 1.5f, -2f, 0.25f, -0.25f),
        AudioFormat(44_100, 2),
        EncodeTarget.wavFloat(),
    )

    @Test
    fun javaSoundPlaysOrSaysThereIsNoOutput() {
        // This machine may have no audio device, in which case nothing is played and the answer is false.
        val played = playOnJvm(bytes)
        println(if (played) "played on the default output" else "no audio output for this format")
    }

    @Test
    fun theJavaSoundBytesAreLittleEndianShorts() {
        val out = ArrayList<Byte>()
        AudioKodec.open(bytes).use { decoder -> writePcm16(decoder) { b, n -> for (i in 0 until n) out += b[i] } }
        val shorts = (0 until out.size / 2).map { (out[2 * it].toInt() and 0xFF) or (out[2 * it + 1].toInt() shl 8) }
        assertEquals(listOf(0, 16384, -16384, 32767, 32767, -32768, 8192, -8192), shorts)
    }

    @Test
    fun androidGetsShorts() {
        val samples = floatArrayOf(0f, 0.5f, -1f, 2f)
        assertContentEquals(shortArrayOf(0, 16384, -32768, 32767), toShorts(samples, 4))
    }

    @Test
    fun appleAndTheBrowserGetOneArrayPerChannel() {
        val planes = toPlanes(floatArrayOf(1f, -1f, 2f, -2f, 3f, -3f), channels = 2, frames = 3)
        assertContentEquals(floatArrayOf(1f, 2f, 3f), planes[0])
        assertContentEquals(floatArrayOf(-1f, -2f, -3f), planes[1])
    }
}

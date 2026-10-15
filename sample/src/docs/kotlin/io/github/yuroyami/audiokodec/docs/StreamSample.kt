package io.github.yuroyami.audiokodec.docs

// --8<-- [start:imports]
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.DecodeOptions
// --8<-- [end:imports]
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class StreamSample {
    private val bytes = Fixtures.flac

    @Test
    fun streamFromASeekPoint() {
        val heard = ArrayList<Float>()
        fun play(samples: FloatArray, frames: Int) {
            for (i in 0 until frames * 2) heard += samples[i]
        }

        // --8<-- [start:stream]
        AudioKodec.open(bytes, DecodeOptions(reuseBuffers = true)).use { decoder ->
            println("${decoder.durationFrames} frames at ${decoder.format.sampleRate} Hz")

            // Jump to exactly one second in. The next block starts at that frame.
            decoder.seek(decoder.format.sampleRate * 1L)

            while (true) {
                val block = decoder.nextFrame() ?: break  // null at the end
                // Interleaved samples, valid until the next call. Copy what you keep.
                play(block.samples, block.frameCount)
            }
        }
        // --8<-- [end:stream]

        val whole = AudioKodec.decode(bytes).samples
        assertEquals(44_100 * 2, heard.size)
        assertContentEquals(whole.copyOfRange(44_100 * 2, whole.size), heard.toFloatArray())
    }

    @Test
    fun readAWindowOfAFile() {
        assertContentEquals(
            AudioKodec.decode(bytes).samples.copyOfRange(10_000 * 2, 15_000 * 2),
            readFrames(bytes, 10_000, 5_000),
        )
    }
}

// --8<-- [start:window]
/** [count] frames of [bytes] from frame [start], interleaved, decoding only what they need. */
fun readFrames(bytes: ByteArray, start: Long, count: Int): FloatArray =
    AudioKodec.open(bytes).use { decoder ->
        val channels = decoder.format.channels
        val out = FloatArray(count * channels)
        var filled = 0
        decoder.seek(start)
        while (filled < count) {
            val block = decoder.nextFrame() ?: break
            val take = minOf(block.frameCount, count - filled)
            block.samples.copyInto(out, filled * channels, 0, take * channels)
            filled += take
        }
        out.copyOf(filled * channels)
    }
// --8<-- [end:window]

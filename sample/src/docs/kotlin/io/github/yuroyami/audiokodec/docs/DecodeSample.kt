package io.github.yuroyami.audiokodec.docs

// --8<-- [start:imports]
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.UnsupportedFormatException
// --8<-- [end:imports]
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// --8<-- [start:describe]
/** What a file holds, or why it cannot be decoded. */
fun describe(bytes: ByteArray): String = try {
    val audio = AudioKodec.decode(bytes)
    "${audio.frames} frames at ${audio.format.sampleRate} Hz"
} catch (e: UnsupportedFormatException) {
    // A format AudioKodec names but has no decoder for, or bytes that are no format it knows.
    "not decodable: ${e.message}"
} catch (e: AudioKodecException) {
    // A damaged or truncated file, or a part of a format that is not supported.
    "cannot decode: ${e.message}"
}
// --8<-- [end:describe]

class DecodeSample {
    private val bytes = Fixtures.flac

    @Test
    fun decodeAFile() {
        // --8<-- [start:decode]
        val audio = AudioKodec.decode(bytes)

        val rate = audio.format.sampleRate       // frames per second, such as 44100
        val channels = audio.format.channels     // samples in each frame, such as 2
        val samples: FloatArray = audio.samples  // interleaved: left, right, left, right, ...

        // Channel c of frame f, nominally between -1.0 and 1.0:
        val f = 1000
        val c = 1
        val value = samples[f * channels + c]
        // --8<-- [end:decode]

        assertEquals(44_100, rate)
        assertEquals(2, channels)
        assertEquals(88_200, audio.frames)
        assertEquals(2.0, audio.durationSeconds)
        // 16-bit FLAC gives back the input to within one step.
        assertEquals(Fixtures.samples[f * 2 + c], value, 1f / 32768)
    }

    @Test
    fun readTheHeaderWithoutDecoding() {
        // --8<-- [start:probe]
        val info = AudioKodec.probe(bytes)
        println(info.container.displayName)  // FLAC
        println(info.codec.displayName)      // FLAC
        println(info.durationSeconds)        // 2.0
        // --8<-- [end:probe]
        assertEquals("FLAC", info.container.displayName)
        assertEquals(2.0, info.durationSeconds)
    }

    @Test
    fun failuresAreAudioKodecExceptions() {
        assertEquals("88200 frames at 44100 Hz", describe(bytes))
        assertTrue(describe("not audio at all, just text".encodeToByteArray()).startsWith("not decodable: "))
        assertTrue(describe(bytes.copyOf(bytes.size / 2)).startsWith("cannot decode: "))
    }
}

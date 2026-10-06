package io.github.yuroyami.audiokodec

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RIFF lets chunks come in any order, as long as a WAV's format precedes its
 * audio. Where a `fact` chunk sits must therefore not change what decodes.
 */
class WaveChunkOrderTest {

    /** Ten frames of IMA ADPCM, which pads its second block to eighteen. */
    private val original = AudioKodec.encode(
        FloatArray(10) { it / 20f },
        AudioFormat(8_000, 1),
        EncodeTarget.wavImaAdpcm(8),
    )

    private fun chunkAt(file: ByteArray, id: String): Int {
        var at = 12
        while (file.decodeToString(at, at + 4) != id) {
            val size = u32(file, at + 4)
            at += 8 + size + size % 2
        }
        return at
    }

    private fun u32(file: ByteArray, at: Int): Int =
        (file[at].toInt() and 0xFF) or ((file[at + 1].toInt() and 0xFF) shl 8) or
            ((file[at + 2].toInt() and 0xFF) shl 16) or ((file[at + 3].toInt() and 0xFF) shl 24)

    private fun setU32(file: ByteArray, at: Int, value: Int) {
        for (i in 0 until 4) file[at + i] = (value ushr (8 * i)).toByte()
    }

    /** The same file with its twelve-byte `fact` chunk moved to the end. */
    private val factLast: ByteArray = run {
        val at = chunkAt(original, "fact")
        original.copyOfRange(0, at) + original.copyOfRange(at + 12, original.size) +
            original.copyOfRange(at, at + 12)
    }

    @Test
    fun aFactChunkAfterTheAudioStillDecidesTheLength() {
        for ((what, file) in listOf("fact first" to original, "fact last" to factLast)) {
            assertEquals(10L, AudioKodec.probe(file).durationFrames, what)
            val decoded = AudioKodec.decode(file)
            assertEquals(10, decoded.frames, what)
            AudioKodec.open(file).use { decoder ->
                assertEquals(10L, decoder.seek(1_000), what)
                assertEquals(null, decoder.nextFrame(), what)
                assertEquals(9L, decoder.seek(9), what)
                assertEquals(1, decoder.nextFrame()?.frameCount, what)
            }
        }
        assertEquals(
            AudioKodec.decode(original).samples.toList(),
            AudioKodec.decode(factLast).samples.toList(),
        )
    }

    @Test
    fun damageAfterTheAudioDoesNotCostTheAudio() {
        // A chunk after the payload that claims to run past the RIFF form.
        val damaged = original + "junk".encodeToByteArray() + byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F)
        setU32(damaged, 4, damaged.size - 8)
        assertEquals(10, AudioKodec.decode(damaged).frames)

        // A second payload is not the audio; the first one is.
        val data = chunkAt(original, "data")
        val second = original + original.copyOfRange(data, data + 8 + u32(original, data + 4))
        setU32(second, 4, second.size - 8)
        assertEquals(AudioKodec.decode(original).samples.toList(), AudioKodec.decode(second).samples.toList())
    }
}

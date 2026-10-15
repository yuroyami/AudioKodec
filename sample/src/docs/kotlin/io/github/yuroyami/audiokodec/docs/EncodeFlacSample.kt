package io.github.yuroyami.audiokodec.docs

// --8<-- [start:imports]
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.Dither
import io.github.yuroyami.audiokodec.EncodeTarget
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
// --8<-- [end:imports]
import io.github.yuroyami.audiokodec.AudioContainer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EncodeFlacSample {

    @Test
    fun encodeSamples() {
        val samples = Fixtures.samples

        // --8<-- [start:encode]
        // Interleaved Float32 between -1.0 and 1.0: left, right, left, right, ...
        val format = AudioFormat(sampleRate = 44_100, channels = 2)
        val tags = TagBundle.EMPTY.edit { set(TagField.TITLE, "My recording") }

        val flac: ByteArray = AudioKodec.encode(samples, format, EncodeTarget.flac(bitDepth = 16), tags)
        // --8<-- [end:encode]

        val decoded = AudioKodec.decode(flac)
        assertEquals(format, decoded.format)
        assertEquals(samples.size, decoded.samples.size)
        for (i in samples.indices) assertEquals(samples[i], decoded.samples[i], 1f / 32768)
        assertEquals("My recording", AudioKodec.readTags(flac)[TagField.TITLE])

        // --8<-- [start:verify]
        // FLAC stores an MD5 of its audio, so a file can be checked against itself.
        val intact: Boolean? = AudioKodec.verifyFlacMd5(flac)  // true, or null if no MD5 is stored
        // --8<-- [end:verify]
        assertEquals(true, intact)
    }

    @Test
    fun convertAnotherFile() {
        val wav = AudioKodec.encode(Fixtures.samples, Fixtures.format, EncodeTarget.wav(24))

        // --8<-- [start:convert]
        val decoded = AudioKodec.decode(wav)
        val tags = AudioKodec.readTags(wav)

        // A lossless 24-bit copy, at the highest effort: smallest file, slowest encode.
        val archive = AudioKodec.encode(decoded, EncodeTarget.flac(bitDepth = 24, effort = 8), tags)

        // A 16-bit copy, with dither to replace the distortion of rounding by a little noise.
        val cd = AudioKodec.encode(decoded, EncodeTarget.flac(bitDepth = 16, dither = Dither.Tpdf()), tags)
        // --8<-- [end:convert]

        assertEquals(AudioContainer.FLAC, AudioKodec.probe(archive).container)
        assertEquals(24, AudioKodec.probe(archive).bitDepth)
        assertEquals(16, AudioKodec.probe(cd).bitDepth)
        assertTrue(AudioKodec.verifyFlacMd5(archive) == true && AudioKodec.verifyFlacMd5(cd) == true)
        // Lossless at 24 bits: the copy decodes to what the WAV decodes to.
        assertContentEquals(decoded.samples, AudioKodec.decode(archive).samples)
    }
}

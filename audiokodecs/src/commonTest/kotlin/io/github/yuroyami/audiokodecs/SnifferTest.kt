package io.github.yuroyami.audiokodecs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnifferTest {

    @Test
    fun theContainersThisBuildWritesSniffAsThemselves() {
        val format = AudioFormat(44_100, 1)
        val samples = TestSignals.quantise(TestSignals.sine(100, 1), 16)
        assertEquals(
            AudioContainer.WAV,
            AudioKodecs.sniff(AudioKodecs.encode(samples, format, EncodeTarget.wav(16))),
        )
        assertEquals(
            AudioContainer.AIFF,
            AudioKodecs.sniff(AudioKodecs.encode(samples, format, EncodeTarget.aiff(16))),
        )
        assertEquals(
            AudioContainer.AIFF,
            AudioKodecs.sniff(AudioKodecs.encode(samples, format, EncodeTarget.aiffFloat(32))),
        )
        assertEquals(
            AudioContainer.AU,
            AudioKodecs.sniff(AudioKodecs.encode(samples, format, EncodeTarget.au(16))),
        )
    }

    @Test
    fun everyFormatWithAMagicNumberIsNamedFromIt() {
        val cases = listOf(
            AudioContainer.FLAC to byteArrayOf('f'.b, 'L'.b, 'a'.b, 'C'.b, 0, 0, 0, 34),
            AudioContainer.OGG to byteArrayOf('O'.b, 'g'.b, 'g'.b, 'S'.b, 0, 2, 0, 0),
            AudioContainer.CAF to byteArrayOf('c'.b, 'a'.b, 'f'.b, 'f'.b, 0, 1, 0, 0),
            AudioContainer.APE to byteArrayOf('M'.b, 'A'.b, 'C'.b, ' '.b, 0, 0, 0, 0),
            AudioContainer.WAVPACK to byteArrayOf('w'.b, 'v'.b, 'p'.b, 'k'.b, 0, 0, 0, 0),
            AudioContainer.MUSEPACK to byteArrayOf('M'.b, 'P'.b, 'C'.b, 'K'.b, 0, 0, 0, 0),
            AudioContainer.TTA to byteArrayOf('T'.b, 'T'.b, 'A'.b, '1'.b, 0, 0, 0, 0),
            AudioContainer.DSF to byteArrayOf('D'.b, 'S'.b, 'D'.b, ' '.b, 0, 0, 0, 0),
            AudioContainer.DFF to byteArrayOf('F'.b, 'R'.b, 'M'.b, '8'.b, 0, 0, 0, 0),
            AudioContainer.MIDI to byteArrayOf('M'.b, 'T'.b, 'h'.b, 'd'.b, 0, 0, 0, 6),
            AudioContainer.SHORTEN to byteArrayOf('a'.b, 'j'.b, 'k'.b, 'g'.b, 2, 0, 0, 0),
            AudioContainer.TAK to byteArrayOf('t'.b, 'B'.b, 'a'.b, 'K'.b, 0, 0, 0, 0),
            AudioContainer.OPTIMFROG to byteArrayOf('O'.b, 'F'.b, 'R'.b, ' '.b, 0, 0, 0, 0),
            AudioContainer.MATROSKA to byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 1, 0, 0, 0),
            AudioContainer.AC3 to byteArrayOf(0x0B, 0x77, 0, 0, 0, 0, 0, 0),
            AudioContainer.DTS to byteArrayOf(0x7F, 0xFE.toByte(), 0x80.toByte(), 0x01, 0, 0, 0, 0),
        )
        for ((expected, header) in cases) {
            assertEquals(expected, AudioKodecs.sniff(header), "header for $expected")
        }
    }

    @Test
    fun isoBaseMediaIsRecognisedByItsBrandNotItsFirstBytes() {
        val header = ByteArray(16)
        header[3] = 24
        "ftypM4A ".forEachIndexed { i, c -> header[4 + i] = c.code.toByte() }
        assertEquals(AudioContainer.MP4, AudioKodecs.sniff(header))
    }

    @Test
    fun mpegAudioAndAacAreToldApartByTheirLayerBits() {
        // 0xFF 0xFB is MPEG-1 Layer III, the most common MP3 frame header.
        assertEquals(
            AudioContainer.MPEG_AUDIO,
            AudioKodecs.sniff(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)),
        )
        // 0xFF 0xF1 is ADTS, which sets all twelve sync bits and no layer.
        assertEquals(
            AudioContainer.ADTS,
            AudioKodecs.sniff(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte())),
        )
    }

    @Test
    fun aTagBlockInFrontOfTheAudioDoesNotHideTheFormat() {
        val format = AudioFormat(44_100, 1)
        val audio = AudioKodecs.encode(
            TestSignals.quantise(TestSignals.sine(50, 1), 16), format, EncodeTarget.wav(16),
        )
        // An ID3v2 block of 128 bytes, the way it sits in front of a file.
        val tagged = ByteArray(10 + 128 + audio.size)
        "ID3".forEachIndexed { i, c -> tagged[i] = c.code.toByte() }
        tagged[3] = 4
        tagged[4] = 0
        tagged[5] = 0
        tagged[6] = 0
        tagged[7] = 0
        tagged[8] = 1
        tagged[9] = 0 // 128 as four groups of seven bits.
        audio.copyInto(tagged, 10 + 128)
        assertEquals(AudioContainer.WAV, AudioKodecs.sniff(tagged))
    }

    @Test
    fun probeAndDecodeJumpPastAnId3BlockLargerThanTheSniffWindow() {
        val format = AudioFormat(48_000, 2)
        val samples = TestSignals.quantise(TestSignals.sine(2_000, 2), 16)
        val targets = listOf(EncodeTarget.wav(16), EncodeTarget.flac(16, 5))
        for (target in targets) {
            val audio = AudioKodecs.encode(samples, format, target)
            val originalInfo = AudioKodecs.probe(audio)
            val tagged = id3Prefix(70_000) + audio

            // A caller-supplied short sniff window cannot see through the tag,
            // but source-based APIs read its declared size and jump directly.
            assertEquals(AudioContainer.MPEG_AUDIO, AudioKodecs.sniff(tagged.copyOf(64)))
            val info = AudioKodecs.probe(tagged)
            assertEquals(originalInfo.container, info.container, target.toString())
            assertEquals(
                originalInfo.dataOffset + 70_010,
                info.dataOffset,
                "$target absolute data offset",
            )
            TestSignals.assertExact(samples, AudioKodecs.decode(tagged).samples, target.toString())
        }
    }

    @Test
    fun sourceDetectionHandlesStackedLeadingId3Blocks() {
        val samples = TestSignals.quantise(TestSignals.sine(100, 1), 16)
        val wave = AudioKodecs.encode(samples, AudioFormat(44_100, 1), EncodeTarget.wav())
        val tagged = id3Prefix(0) + id3Prefix(257) + wave
        assertEquals(AudioContainer.WAV, AudioKodecs.probe(tagged).container)
        TestSignals.assertExact(samples, AudioKodecs.decode(tagged).samples)
    }

    @Test
    fun byteArraySniffingLooksThroughArbitrarilyDeepStackedId3Blocks() {
        val wave = AudioKodecs.encode(
            FloatArray(20),
            AudioFormat(44_100, 1),
            EncodeTarget.wav(),
        )
        val prefix = id3Prefix(0)
        val tagged = ByteArray(prefix.size * 301 + wave.size)
        repeat(301) { prefix.copyInto(tagged, it * prefix.size) }
        wave.copyInto(tagged, prefix.size * 301)
        assertEquals(AudioContainer.WAV, AudioKodecs.sniff(tagged))
    }

    @Test
    fun anIdThreeBlockWithNothingAfterItIsCalledAnMp3() {
        // Almost every file that starts this way is an MP3, and probe reads
        // the real header afterwards, so this is the useful answer.
        val header = ByteArray(10)
        "ID3".forEachIndexed { i, c -> header[i] = c.code.toByte() }
        header[3] = 3
        header[8] = 0x7F
        header[9] = 0x7F
        assertEquals(AudioContainer.MPEG_AUDIO, AudioKodecs.sniff(header))
    }

    @Test
    fun nonsenseIsNotGivenAName() {
        assertNull(AudioKodecs.sniff(ByteArray(0)))
        assertNull(AudioKodecs.sniff(byteArrayOf(1, 2, 3)))
        assertNull(AudioKodecs.sniff(ByteArray(64) { (it * 7).toByte() }))
        assertNull(AudioKodecs.sniff("RIFFxxxxAVI ".encodeToByteArray()))
        assertNull(AudioKodecs.sniff("FORMxxxx8SVX".encodeToByteArray()))
    }

    @Test
    fun theSnifferNeverThrowsWhateverItIsGiven() {
        var state = 12_345
        repeat(20_000) {
            state = state * 1_103_515_245 + 12_345
            val size = ((state ushr 16) and 0x3F)
            val bytes = ByteArray(size) { i ->
                state = state * 1_103_515_245 + 12_345
                ((state ushr 16) and 0xFF).toByte()
            }
            AudioKodecs.sniff(bytes)
        }
    }

    @Test
    fun theReadmeAndTheCodeAgreeOnHowManyFormatsAreNamed() {
        // The README says the sniffer names 29 formats. Adding one to the
        // enum without touching the README would make that sentence wrong,
        // which is what this test is here to stop.
        assertEquals(29, AudioContainer.entries.size)
    }

    @Test
    fun theReadmeAndTheCodeAgreeOnWhichFormatsAreDecodable() {
        // The README's read and write tables name exactly these ten.
        assertEquals(
            setOf(
                AudioContainer.WAV,
                AudioContainer.RF64,
                AudioContainer.WAVE64,
                AudioContainer.AIFF,
                AudioContainer.AU,
                AudioContainer.FLAC,
                AudioContainer.CAF,
                AudioContainer.OGG,
                AudioContainer.MPEG_AUDIO,
                AudioContainer.MP4,
            ),
            AudioContainer.entries.filter { it.isDecodable }.toSet(),
        )
    }

    @Test
    fun whatTheSnifferNamesAndWhatItCanDecodeAreDifferentQuestions() {
        assertTrue(AudioContainer.WAV.isDecodable)
        assertTrue(AudioContainer.AIFF.isDecodable)
        assertTrue(AudioContainer.AU.isDecodable)
        assertTrue(AudioContainer.WAVE64.isDecodable)
        assertTrue(AudioContainer.RF64.isDecodable)
        assertTrue(AudioContainer.FLAC.isDecodable)
        assertTrue(AudioContainer.CAF.isDecodable)
        // Ogg decodes what it carries that AudioKodecs has a codec for, which is
        // Vorbis and FLAC. A mapping without one is refused by name, and that
        // is a question about the codec rather than about the container.
        assertTrue(AudioContainer.OGG.isDecodable)
        // MPEG audio decodes all three of its layers.
        assertTrue(AudioContainer.MPEG_AUDIO.isDecodable)
        // MP4, like Ogg, decodes the codings AudioKodecs has, and names and
        // refuses the rest.
        assertTrue(AudioContainer.MP4.isDecodable)
        assertTrue(!AudioContainer.MATROSKA.isDecodable)
    }
}

private val Char.b: Byte get() = code.toByte()

private fun id3Prefix(bodySize: Int): ByteArray {
    require(bodySize in 0..0x0FFF_FFFF)
    val out = ByteArray(10 + bodySize)
    out[0] = 'I'.b
    out[1] = 'D'.b
    out[2] = '3'.b
    out[3] = 4
    out[4] = 0
    out[5] = 0
    out[6] = (bodySize ushr 21).toByte()
    out[7] = ((bodySize ushr 14) and 0x7F).toByte()
    out[8] = ((bodySize ushr 7) and 0x7F).toByte()
    out[9] = (bodySize and 0x7F).toByte()
    return out
}

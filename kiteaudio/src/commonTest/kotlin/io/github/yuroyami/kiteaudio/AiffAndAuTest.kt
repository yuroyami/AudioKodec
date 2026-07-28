package io.github.yuroyami.kiteaudio

import io.github.yuroyami.kiteaudio.tag.TagBundle
import io.github.yuroyami.kiteaudio.tag.TagField
import io.github.yuroyami.kiteaudio.tag.TagFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AiffAndAuTest {

    @Test
    fun integerPcmSurvivesAnAiffRoundTripAtEveryDepth() {
        for (depth in intArrayOf(8, 16, 24)) {
            for (channels in intArrayOf(1, 2)) {
                val format = AudioFormat(44_100, channels)
                val original = TestSignals.quantise(TestSignals.sine(400, channels), depth)
                val file = KiteAudio.encode(original, format, EncodeTarget.aiff(depth))
                val decoded = KiteAudio.decode(file)
                assertEquals(400, decoded.frames, "${depth}-bit, $channels channels")
                TestSignals.assertExact(original, decoded.samples, "${depth}-bit AIFF")
            }
        }
    }

    @Test
    fun anAiffFileIsBigEndianWhereWaveIsLittle() {
        val format = AudioFormat(44_100, 1)
        // A single sample of 0x0100 tells the two byte orders apart.
        val samples = Pcm.floatFromInt(intArrayOf(256), 16)
        val aiff = KiteAudio.encode(samples, format, EncodeTarget.aiff(16))
        val wav = KiteAudio.encode(samples, format, EncodeTarget.wav(16))
        val aiffData = aiff.copyOfRange(aiff.size - 2, aiff.size)
        val wavData = wav.copyOfRange(wav.size - 2, wav.size)
        assertEquals(1, aiffData[0].toInt(), "AIFF stores the high byte first")
        assertEquals(0, aiffData[1].toInt())
        assertEquals(0, wavData[0].toInt(), "WAV stores the low byte first")
        assertEquals(1, wavData[1].toInt())
    }

    @Test
    fun theSampleRateSurvivesTheEightyBitFloatItIsStoredIn() {
        for (rate in intArrayOf(8_000, 11_025, 22_050, 44_100, 48_000, 96_000, 192_000)) {
            val format = AudioFormat(rate, 1)
            val file = KiteAudio.encode(FloatArray(10), format, EncodeTarget.aiff(16))
            assertEquals(rate, KiteAudio.probe(file).format.sampleRate, "rate $rate")
        }
    }

    @Test
    fun aiffHonoursNonByteAlignedSampleDepths() {
        val integers = IntArray(40) { ((it * 149) % 4096) - 2048 }
        val original = Pcm.floatFromInt(integers, 12)
        val file = KiteAudio.encode(original, AudioFormat(44_100, 1), EncodeTarget.aiff(16))
        val commAt = file.indexOfChunk("COMM")
        // The 12 significant bits are high-aligned in AIFF's two-byte field.
        file[commAt + 14] = 0
        file[commAt + 15] = 12

        assertEquals(12, KiteAudio.probe(file).bitDepth)
        KiteAudio.open(file).use { decoder ->
            val exact = assertIs<IntPcmAccess>(decoder)
            val frame = assertNotNull(exact.nextIntFrame())
            for (index in integers.indices) {
                assertEquals(integers[index], frame.samples[index], "sample $index")
            }
        }
        TestSignals.assertExact(original, KiteAudio.decode(file).samples)
    }

    @Test
    fun floatAiffIsWrittenAsAiffC() {
        val format = AudioFormat(48_000, 2)
        val original = TestSignals.sine(200, 2)
        val file = KiteAudio.encode(original, format, EncodeTarget.aiffFloat(32))
        assertEquals("AIFC", file.copyOfRange(8, 12).decodeToString())
        val decoded = KiteAudio.decode(file)
        TestSignals.assertExact(original, decoded.samples, "32-bit float AIFF-C")
        assertEquals(AudioCodec.PCM_FLOAT, decoded.info.codec)
    }

    @Test
    fun aiffCarriesTheTwoG711Laws() {
        val format = AudioFormat(8_000, 1)
        val original = TestSignals.sine(1_000, 1, sampleRate = 8_000, amplitude = 0.7f)
        for (target in listOf(EncodeTarget.aiffMuLaw(), EncodeTarget.aiffALaw())) {
            val file = KiteAudio.encode(original, format, target)
            assertEquals(null, KiteAudio.probe(file).bitDepth)
            KiteAudio.open(file).use { decoder ->
                assertFalse(decoder is IntPcmAccess, "$target exposed a lossy integer view")
            }
            val decoded = KiteAudio.decode(file)
            assertEquals(1_000, decoded.frames)
            val error = TestSignals.rmsError(original, decoded.samples)
            assertTrue(error < 0.01f, "$target had an RMS error of $error")
        }
    }

    @Test
    fun aiffCompressedHeadersMustStateTheirDecodedSampleWidth() {
        for (target in listOf(EncodeTarget.aiffMuLaw(), EncodeTarget.aiffALaw())) {
            val file = KiteAudio.encode(
                FloatArray(8),
                AudioFormat(8_000, 1),
                target,
            )
            val commAt = file.indexOfChunk("COMM")
            file[commAt + 14] = 0
            file[commAt + 15] = 8
            assertFailsWith<CorruptStreamException>(target.toString()) {
                KiteAudio.probe(file)
            }
        }
    }

    @Test
    fun integerPcmSurvivesAnAuRoundTripAtEveryDepth() {
        for (depth in intArrayOf(8, 16, 24)) {
            val format = AudioFormat(44_100, 2)
            val original = TestSignals.quantise(TestSignals.sine(300, 2), depth)
            val file = KiteAudio.encode(original, format, EncodeTarget.au(depth))
            val decoded = KiteAudio.decode(file)
            assertEquals(300, decoded.frames, "${depth}-bit AU")
            TestSignals.assertExact(original, decoded.samples, "${depth}-bit AU")
        }
    }

    @Test
    fun auCarriesMuLawWhichIsWhatTheFormatWasBuiltFor() {
        val format = AudioFormat(8_000, 1)
        val original = TestSignals.sine(800, 1, sampleRate = 8_000, amplitude = 0.7f)
        val file = KiteAudio.encode(original, format, EncodeTarget.auMuLaw())
        val info = KiteAudio.probe(file)
        assertEquals(AudioContainer.AU, info.container)
        assertEquals(AudioCodec.MU_LAW, info.codec)
        assertEquals(null, info.bitDepth)
        KiteAudio.open(file).use { decoder ->
            assertFalse(decoder is IntPcmAccess, "mu-law exposed a lossy integer view")
        }
        val decoded = KiteAudio.decode(file)
        assertTrue(TestSignals.rmsError(original, decoded.samples) < 0.01f)
    }

    @Test
    fun tagsGivenToTheAiffEncoderAreActuallyWritten() {
        val file = KiteAudio.encode(
            FloatArray(32),
            AudioFormat(44_100, 1),
            EncodeTarget.aiff(),
            TagBundle(
                mapOf(
                    TagField.TITLE to listOf("An AIFF title"),
                    TagField.LYRICS to listOf("Unicode lyrics: \u03bb"),
                ),
            ),
        )
        val read = KiteAudio.readTags(file)
        assertEquals("An AIFF title", read[TagField.TITLE])
        assertEquals("Unicode lyrics: \u03bb", read[TagField.LYRICS])
        assertTrue(TagFormat.AIFF_TEXT in read.raw.formats)
        assertTrue(TagFormat.ID3V2_4 in read.raw.formats)

        val edited = KiteAudio.writeTags(
            file,
            read.edit { set(TagField.TITLE, "Edited without losing ID3") },
        )
        val after = KiteAudio.readTags(edited)
        assertEquals("Edited without losing ID3", after[TagField.TITLE])
        assertEquals("Unicode lyrics: \u03bb", after[TagField.LYRICS])
        assertTrue(TagFormat.ID3V2_4 in after.raw.formats)
    }

    @Test
    fun auRejectsTagsBeforeWritingAnything() {
        val sink = ByteArrayAudioSink()
        assertFailsWith<UnsupportedFeatureException> {
            KiteAudio.encode(
                FloatArray(1).asPcmSource(AudioFormat(8_000, 1)),
                EncodeTarget.auMuLaw(),
                sink,
                TagBundle(mapOf(TagField.TITLE to listOf("No place to put this"))),
            )
        }
        assertEquals(0L, sink.position)
    }

    @Test
    fun anAuFileWithNoStatedLengthReadsToTheEnd() {
        val format = AudioFormat(44_100, 1)
        val original = TestSignals.quantise(TestSignals.sine(500, 1), 16)
        val file = KiteAudio.encode(original, format, EncodeTarget.au(16))
        // Put the marker for "length not known" back into the header, which
        // is what a program writing to a pipe leaves behind.
        for (i in 8..11) file[i] = 0xFF.toByte()
        val decoded = KiteAudio.decode(file)
        assertEquals(500, decoded.frames)
        TestSignals.assertExact(original, decoded.samples)
    }

    @Test
    fun everyContainerAgreesOnTheSameAudio() {
        val format = AudioFormat(44_100, 2)
        val original = TestSignals.quantise(TestSignals.sine(250, 2), 16)
        val files = listOf(
            "WAV" to KiteAudio.encode(original, format, EncodeTarget.wav(16)),
            "AIFF" to KiteAudio.encode(original, format, EncodeTarget.aiff(16)),
            "AU" to KiteAudio.encode(original, format, EncodeTarget.au(16)),
        )
        for ((name, file) in files) {
            val decoded = KiteAudio.decode(file)
            assertEquals(format.sampleRate, decoded.format.sampleRate, name)
            assertEquals(format.channels, decoded.format.channels, name)
            TestSignals.assertExact(original, decoded.samples, name)
        }
    }

    @Test
    fun countOnlyContainersRefuseToRelabelCustomSpeakerLayouts() {
        val format = AudioFormat(
            44_100,
            ChannelLayout.of(AudioChannel.FRONT_LEFT),
        )
        for (target in listOf(
            EncodeTarget.aiff(16),
            EncodeTarget.au(16),
            EncodeTarget.flac(16),
        )) {
            val sink = ByteArrayAudioSink()
            assertFailsWith<IllegalArgumentException>(target.toString()) {
                KiteAudio.encode(FloatArray(1).asPcmSource(format), target, sink)
            }
            assertEquals(0L, sink.position, "$target wrote before rejecting the layout")
        }
    }
}

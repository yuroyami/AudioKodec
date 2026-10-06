package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WaveTest {

    @Test
    fun reusingADitheredTargetIsDeterministic() {
        val format = AudioFormat(44_100, 2)
        val samples = TestSignals.sine(1_000, 2)
        val target = EncodeTarget.wav(16, Dither.Tpdf(seed = 1234))
        assertContentEquals(
            AudioKodec.encode(samples, format, target),
            AudioKodec.encode(samples, format, target),
        )
    }

    @Test
    fun integerPcmSurvivesAWaveRoundTripAtEveryDepth() {
        for (depth in intArrayOf(8, 16, 24)) {
            for (channels in intArrayOf(1, 2, 6)) {
                val format = AudioFormat(44_100, channels)
                val original = TestSignals.quantise(TestSignals.sine(500, channels), depth)
                val file = AudioKodec.encode(original, format, EncodeTarget.wav(depth))
                val decoded = AudioKodec.decode(file)
                assertEquals(500, decoded.frames, "${depth}-bit, $channels channels: frame count")
                assertEquals(format.sampleRate, decoded.format.sampleRate)
                assertEquals(channels, decoded.format.channels)
                TestSignals.assertExact(
                    original, decoded.samples, "${depth}-bit, $channels channels",
                )
            }
        }
    }

    @Test
    fun the32BitIntegerPathIsExactThroughTheIntegerView() {
        // Float32 has 24 bits of mantissa, so 32-bit samples cannot survive
        // the float path. The integer view is what a caller uses to prove a
        // lossless round trip, and this is the test that says so.
        val format = AudioFormat(48_000, 1)
        val values = IntArray(256) { (it - 128) * 16_777_216 }
        val asFloat = Pcm.floatFromInt(values, 32)
        val file = AudioKodec.encode(asFloat, format, EncodeTarget.wav(32))
        AudioKodec.open(file).use { decoder ->
            val access = assertIs<IntPcmAccess>(decoder, "a 32-bit WAV decoder exposes integers")
            assertEquals(32, access.bitDepth)
            val block = assertNotNull(access.nextIntFrame())
            assertEquals(256, block.frameCount)
            for (i in values.indices) {
                // The float that went in is what could be stored, so compare
                // against the same conversion the encoder performed.
                val expected = Pcm.intFromFloat(floatArrayOf(asFloat[i]), 32)[0]
                assertEquals(expected, block.samples[i], "sample $i")
            }
        }
    }

    @Test
    fun floatSamplesSurviveAWaveRoundTripUnchanged() {
        val format = AudioFormat(48_000, 2)
        val original = TestSignals.sine(300, 2)
        for (depth in intArrayOf(32, 64)) {
            val file = AudioKodec.encode(original, format, EncodeTarget.wavFloat(depth))
            val decoded = AudioKodec.decode(file)
            TestSignals.assertExact(original, decoded.samples, "${depth}-bit float")
            assertEquals(AudioCodec.PCM_FLOAT, decoded.info.codec)
        }
    }

    @Test
    fun aSixChannelFileCarriesItsSpeakerLayout() {
        val format = AudioFormat(48_000, ChannelLayout.SURROUND_5_1)
        val samples = TestSignals.quantise(TestSignals.ramp(64, 6), 16)
        val file = AudioKodec.encode(samples, format, EncodeTarget.wav(16))
        val info = AudioKodec.probe(file)
        assertEquals(ChannelLayout.SURROUND_5_1, info.format.channelLayout)
        assertEquals(6, info.format.channels)
    }

    @Test
    fun customMonoAndStereoSpeakerLayoutsSurviveThroughExtensibleWave() {
        val layouts = listOf(
            ChannelLayout.of(AudioChannel.FRONT_LEFT),
            ChannelLayout.of(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_CENTER),
        )
        for (layout in layouts) {
            val format = AudioFormat(48_000, layout)
            val file = AudioKodec.encode(
                TestSignals.quantise(TestSignals.ramp(16, layout.count), 16),
                format,
                EncodeTarget.wav(16),
            )
            val fmtAt = file.indexOfChunk("fmt ")
            val tag = (file[fmtAt + 8].toInt() and 0xFF) or
                ((file[fmtAt + 9].toInt() and 0xFF) shl 8)
            assertEquals(0xFFFE, tag, "$layout should use WAVE_FORMAT_EXTENSIBLE")
            assertEquals(layout, AudioKodec.probe(file).format.channelLayout)
        }
    }

    @Test
    fun tagsGivenToTheWaveEncoderAreActuallyWritten() {
        val tags = TagBundle(
            mapOf(
                TagField.TITLE to listOf("A native title"),
                TagField.LYRICS to listOf("Unicode lyrics: \u03bb"),
            ),
        )
        val file = AudioKodec.encode(
            FloatArray(32),
            AudioFormat(44_100, 1),
            EncodeTarget.wav(),
            tags,
        )
        val read = AudioKodec.readTags(file)
        assertEquals("A native title", read[TagField.TITLE])
        assertEquals("Unicode lyrics: \u03bb", read[TagField.LYRICS])
        assertTrue(TagFormat.RIFF_INFO in read.raw.formats)
        assertTrue(TagFormat.ID3V2_4 in read.raw.formats)

        val edited = AudioKodec.writeTags(
            file,
            read.edit { set(TagField.TITLE, "Edited without losing ID3") },
        )
        val after = AudioKodec.readTags(edited)
        assertEquals("Edited without losing ID3", after[TagField.TITLE])
        assertEquals("Unicode lyrics: \u03bb", after[TagField.LYRICS])
        assertTrue(TagFormat.ID3V2_4 in after.raw.formats)
    }

    @Test
    fun waveRefusesSpeakerLayoutsItsHeaderCannotTellTheTruthAbout() {
        val reversedStereo = AudioFormat(
            48_000,
            ChannelLayout.of(AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_LEFT),
        )
        assertFailsWith<IllegalArgumentException> {
            AudioKodec.encode(FloatArray(2), reversedStereo, EncodeTarget.wav(16))
        }

        val nonCanonicalCompressedMono = AudioFormat(
            8_000,
            ChannelLayout.of(AudioChannel.FRONT_LEFT),
        )
        assertFailsWith<IllegalArgumentException> {
            AudioKodec.encode(FloatArray(1), nonCanonicalCompressedMono, EncodeTarget.wavMuLaw())
        }
    }

    @Test
    fun probeReadsTheHeaderWithoutTouchingTheAudio() {
        val format = AudioFormat(22_050, 1)
        val samples = TestSignals.quantise(TestSignals.sine(22_050, 1), 16)
        val file = AudioKodec.encode(samples, format, EncodeTarget.wav(16))
        val info = AudioKodec.probe(file)
        assertEquals(AudioContainer.WAV, info.container)
        assertEquals(AudioCodec.PCM_INTEGER, info.codec)
        assertEquals(22_050, info.format.sampleRate)
        assertEquals(16, info.bitDepth)
        assertEquals(22_050L, info.durationFrames)
        assertEquals(1.0, info.durationSeconds)
        assertEquals(22_050L * 2, info.dataSize)
    }

    @Test
    fun muLawAndALawRoundTripWithinTheirOwnAccuracy() {
        val format = AudioFormat(8_000, 1)
        val original = TestSignals.sine(2_000, 1, sampleRate = 8_000, amplitude = 0.7f)
        for (target in listOf(EncodeTarget.wavMuLaw(), EncodeTarget.wavALaw())) {
            val file = AudioKodec.encode(original, format, target)
            assertEquals(null, AudioKodec.probe(file).bitDepth)
            AudioKodec.open(file).use { decoder ->
                assertFalse(decoder is IntPcmAccess, "$target exposed a lossy integer view")
            }
            val decoded = AudioKodec.decode(file)
            assertEquals(2_000, decoded.frames)
            // Eight logarithmic bits carry roughly 13 bits of dynamic range,
            // so the error is a fraction of a percent of full scale, not zero.
            val error = TestSignals.rmsError(original, decoded.samples)
            assertTrue(error < 0.01f, "$target had an RMS error of $error")
        }
    }

    @Test
    fun imaAdpcmRoundTripsCloselyEnoughForItsBitRate() {
        val format = AudioFormat(44_100, 1)
        val original = TestSignals.sine(4_000, 1, amplitude = 0.6f)
        val file = AudioKodec.encode(original, format, EncodeTarget.wavImaAdpcm(512))
        val info = AudioKodec.probe(file)
        assertEquals(AudioCodec.IMA_ADPCM, info.codec)
        assertEquals(4_000L, info.durationFrames)
        val decoded = AudioKodec.decode(file)
        assertEquals(4_000, decoded.frames)
        val error = TestSignals.rmsError(original, decoded.samples)
        assertTrue(error < 0.02f, "IMA ADPCM had an RMS error of $error")
    }

    @Test
    fun automaticImaBlockAlignmentIsValidForEveryCommonChannelCount() {
        for (channels in 1..8) {
            val format = AudioFormat(44_100, channels)
            val original = TestSignals.sine(80, channels, amplitude = 0.4f)
            val file = AudioKodec.encode(original, format, EncodeTarget.wavImaAdpcm())
            val info = AudioKodec.probe(file)
            assertEquals(channels, info.format.channels, "$channels channels")
            assertEquals(80L, info.durationFrames, "$channels channels")
            assertEquals(80, AudioKodec.decode(file).frames, "$channels channels")
        }
    }

    @Test
    fun microsoftAdpcmRoundTripsCloselyEnoughForItsBitRate() {
        val format = AudioFormat(44_100, 1)
        val original = TestSignals.sine(4_000, 1, amplitude = 0.6f)
        val file = AudioKodec.encode(original, format, EncodeTarget.wavMsAdpcm(512))
        val info = AudioKodec.probe(file)
        assertEquals(AudioCodec.MS_ADPCM, info.codec)
        assertEquals(4_000L, info.durationFrames)
        val decoded = AudioKodec.decode(file)
        assertEquals(4_000, decoded.frames)
        val error = TestSignals.rmsError(original, decoded.samples)
        assertTrue(error < 0.02f, "Microsoft ADPCM had an RMS error of $error")
    }

    @Test
    fun stereoAdpcmKeepsItsChannelsApart() {
        val format = AudioFormat(44_100, 2)
        // One channel is silent, so any channel mixing shows up immediately.
        val mono = TestSignals.sine(2_000, 1, amplitude = 0.6f)
        val original = FloatArray(2_000 * 2)
        for (f in 0 until 2_000) {
            original[f * 2] = mono[f]
        }
        val file = AudioKodec.encode(original, format, EncodeTarget.wavImaAdpcm(1024))
        val decoded = AudioKodec.decode(file)
        var worstOnTheSilentChannel = 0f
        for (f in 0 until 2_000) {
            val v = kotlin.math.abs(decoded.samples[f * 2 + 1])
            if (v > worstOnTheSilentChannel) worstOnTheSilentChannel = v
        }
        assertTrue(
            worstOnTheSilentChannel < 0.01f,
            "the silent channel picked up $worstOnTheSilentChannel of signal",
        )
    }

    @Test
    fun anExtensibleHeaderIsWrittenForMoreThanTwoChannels() {
        val format = AudioFormat(48_000, ChannelLayout.SURROUND_5_1)
        val samples = TestSignals.quantise(TestSignals.ramp(32, 6), 24)
        val file = AudioKodec.encode(samples, format, EncodeTarget.wav(24))
        // 0xFFFE little-endian, at the start of the format chunk.
        val fmtAt = file.indexOfChunk("fmt ")
        assertTrue(fmtAt > 0, "the file has no format chunk")
        val tag = (file[fmtAt + 8].toInt() and 0xFF) or ((file[fmtAt + 9].toInt() and 0xFF) shl 8)
        assertEquals(0xFFFE, tag, "a six-channel file needs the extensible header")
        TestSignals.assertExact(samples, AudioKodec.decode(file).samples)
    }

    @Test
    fun anExtensibleChannelMaskMustMatchTheChannelCount() {
        val format = AudioFormat(48_000, ChannelLayout.SURROUND_5_1)
        val file = AudioKodec.encode(
            FloatArray(6),
            format,
            EncodeTarget.wav(16),
        )
        val fmtAt = file.indexOfChunk("fmt ")
        assertTrue(fmtAt > 0, "the file has no format chunk")
        // dwChannelMask is 20 bytes into the extensible format body. Name
        // only front-left despite the six-channel format header.
        file[fmtAt + 28] = 1
        file[fmtAt + 29] = 0
        file[fmtAt + 30] = 0
        file[fmtAt + 31] = 0

        assertFailsWith<CorruptStreamException> { AudioKodec.probe(file) }
    }

    @Test
    fun extensiblePcmHonoursValidBitsInsideAWiderStorageWord() {
        val format = AudioFormat(48_000, 3)
        val integers = IntArray(60) { ((it * 977) % 16_777_216) - 8_388_608 }
        val original = Pcm.floatFromInt(integers, 24)
        val file = AudioKodec.encode(original, format, EncodeTarget.wav(32))
        val fmtAt = file.indexOfChunk("fmt ")
        // wValidBitsPerSample is two bytes after cbSize in the extensible body.
        file[fmtAt + 26] = 24
        file[fmtAt + 27] = 0

        assertEquals(24, AudioKodec.probe(file).bitDepth)
        AudioKodec.open(file).use { decoder ->
            val exact = assertIs<IntPcmAccess>(decoder)
            assertEquals(24, exact.bitDepth)
            val frame = assertNotNull(exact.nextIntFrame())
            for (index in integers.indices) {
                assertEquals(integers[index], frame.samples[index], "sample $index")
            }
        }
        TestSignals.assertExact(original, AudioKodec.decode(file).samples)
    }

    @Test
    fun aTruncatedFileIsRejectedUnlessToleranceIsAskedFor() {
        val format = AudioFormat(44_100, 2)
        val samples = TestSignals.quantise(TestSignals.sine(1_000, 2), 16)
        val file = AudioKodec.encode(samples, format, EncodeTarget.wav(16))
        val cut = file.copyOf(file.size - 501)

        assertFailsWith<TruncatedStreamException> {
            AudioKodec.decode(cut)
        }

        val salvaged = AudioKodec.decode(cut, DecodeOptions(tolerant = true))
        assertTrue(
            salvaged.frames in 800..1_000,
            "tolerant decoding salvaged ${salvaged.frames} frames of 1000",
        )
    }

    @Test
    fun anUnknownPayloadSizeAndUnknownSourceLengthEndCleanlyAtEof() {
        val format = AudioFormat(44_100, 2)
        val samples = TestSignals.quantise(TestSignals.sine(333, 2), 16)
        val file = AudioKodec.encode(samples, format, EncodeTarget.wav(16))
        val data = file.indexOfChunk("data")
        for (index in 4..7) file[data + index] = 0xFF.toByte()
        val source = object : AudioSource {
            private val delegate = file.asAudioSource()
            override val size: Long = -1
            override fun readAt(
                position: Long,
                into: ByteArray,
                offset: Int,
                count: Int,
            ): Int = delegate.readAt(position, into, offset, count)

            override fun close() = Unit
        }

        val decoded = AudioKodec.decode(source)
        assertEquals(333, decoded.frames)
        TestSignals.assertExact(samples, decoded.samples)
    }

    @Test
    fun anEmptyFileIsAValidFileWithNoAudio() {
        val format = AudioFormat(44_100, 2)
        val file = AudioKodec.encode(FloatArray(0), format, EncodeTarget.wav(16))
        val decoded = AudioKodec.decode(file)
        assertEquals(0, decoded.frames)
        assertEquals(0L, AudioKodec.probe(file).durationFrames)
    }

    @Test
    fun aFileWithNoFormatChunkIsNamedAsCorrupt() {
        val bytes = ByteArray(24)
        "RIFF".toByteArray().copyInto(bytes, 0)
        bytes[4] = 16
        "WAVE".toByteArray().copyInto(bytes, 8)
        "data".toByteArray().copyInto(bytes, 12)
        assertFailsWith<CorruptStreamException> { AudioKodec.probe(bytes) }
    }

    @Test
    fun anUnknownFormatTagIsNamedRatherThanGuessedAt() {
        val format = AudioFormat(44_100, 1)
        val file = AudioKodec.encode(FloatArray(100), format, EncodeTarget.wav(16))
        val fmtAt = file.indexOfChunk("fmt ")
        // 0x0055 is MPEG Layer III inside WAV, which this build cannot decode.
        file[fmtAt + 8] = 0x55
        file[fmtAt + 9] = 0x00
        val failure = assertFailsWith<UnsupportedFeatureException> { AudioKodec.probe(file) }
        assertTrue(
            failure.message!!.contains("55"),
            "the error should name the tag it could not decode: ${failure.message}",
        )
    }
}

/** Finds a RIFF chunk by name and returns the offset of its identifier. */
internal fun ByteArray.indexOfChunk(id: String): Int {
    val target = id.toByteArray()
    outer@ for (i in 12..size - 8) {
        for (j in 0 until 4) {
            if (this[i + j] != target[j]) continue@outer
        }
        return i
    }
    return -1
}

private fun String.toByteArray(): ByteArray = ByteArray(length) { this[it].code.toByte() }

package io.github.yuroyami.audiokodec

import java.io.File
import java.util.concurrent.TimeUnit
import io.github.yuroyami.audiokodec.internal.mpeg.MpegFrameHeader
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks the MPEG audio layer against ffmpeg and LAME.
 *
 * MPEG audio has no file header, so everything a caller wants to know about a
 * file comes from finding its frames and reading what an encoder left inside
 * the first one. These tests cover that: where the audio starts and stops, how
 * long it is, and whether the encoder's own padding is being taken off.
 *
 * Every test skips itself when its tool is missing.
 */
class MpegOracleTest {

    private val ffmpeg: String? by lazy { findOnPath("ffmpeg") }
    private val ffprobe: String? by lazy { findOnPath("ffprobe") }
    private val lame: String? by lazy { findOnPath("lame") }

    @Test
    fun illegalTransmittedIntensityPositionsMatchFfmpeg() {
        val ffmpeg = ffmpeg ?: return skip()
        for (rate in listOf(44_100, 48_000, 32_000)) {
            for (shape in Mp3IntensityFixtures.Shape.entries) {
                for (midSide in listOf(false, true)) {
                    val label = "$rate $shape midSide=$midSide"
                    val bytes = Mp3IntensityFixtures.file(rate, shape, position = 7,
                        midSide = midSide, leftLine = Mp3IntensityFixtures.penultimateLine(rate, shape))
                    val file = temp("illegal_intensity_${rate}_${shape}_$midSide.mp3")
                        .also { it.writeBytes(bytes) }
                    val reference = decodeWithFfmpeg(ffmpeg, file, rate, 2, "mp3float")
                    assertEquals(12 * 1_152 * 2, reference.size, label)
                    // The established MP3 bound allows only synthesis rounding.
                    TestSignals.assertClose(reference, AudioKodec.decode(bytes).samples, 1e-4f, label)
                }
            }
        }
    }

    @Test
    fun theHighestIntensityBandAndItsIllegalPositionMatchFfmpeg() {
        val ffmpeg = ffmpeg ?: return skip()
        val mismatches = mutableListOf<String>()
        for (rate in listOf(44_100, 48_000, 32_000, 22_050, 24_000, 16_000, 11_025, 12_000, 8_000)) {
            for (shape in Mp3IntensityFixtures.Shape.entries) {
                for (position in if (rate >= 32_000) listOf(3, 4, 7) else listOf(1, 2, 3)) {
                    for (midSide in listOf(false, true)) {
                        for (scale in if (rate >= 32_000) listOf(0) else listOf(0, 1)) {
                            val label = "$rate $shape position=$position midSide=$midSide scale=$scale"
                            val bytes = Mp3IntensityFixtures.file(rate, shape, position, midSide, scale)
                            val file = temp("intensity_${rate}_${shape}_${position}_${midSide}_$scale.mp3")
                                .also { it.writeBytes(bytes) }
                            val reference = decodeWithFfmpeg(ffmpeg, file, rate, 2, "mp3float")
                            val ours = AudioKodec.decode(bytes).samples
                            assertEquals(12 * (if (rate >= 32_000) 1_152 else 576) * 2, reference.size, label)
                            assertEquals(reference.size, ours.size, label)
                            // The existing maximum MP3 sample allowance permits
                            // floating-point synthesis orders to differ.
                            val worst = reference.indices.maxOf { abs(reference[it] - ours[it]) }
                            if (worst > 1e-4f) mismatches += "$label: maximum sample difference $worst"
                        }
                    }
                }
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun everyShortCoefficientInsideTheEightKhzLongScalefactorRegionMatchesFfmpeg() {
        val ffmpeg = ffmpeg ?: return skip()
        for (line in 36 until 72) {
            for (scaled in listOf(false, true)) {
                val bytes = Mp3SwitchedBlockFixtures.file(8_000,
                    Mp3SwitchedBlockFixtures.Shape.MIXED, true, listOf(line), scaled)
                val file = temp("geometry_${line}_$scaled.mp3").also { it.writeBytes(bytes) }
                val reference = decodeWithFfmpeg(ffmpeg, file, 8_000, 1, "mp3float")
                assertEquals(12 * 576, reference.size)
                // The same maximum sample allowance as the full MP3 oracle
                // permits the last bits of floating-point synthesis to differ.
                TestSignals.assertClose(reference, AudioKodec.decode(bytes).samples, 1e-4f,
                    "8 kHz mixed line $line scaled=$scaled")
            }
        }
    }

    @Test
    fun switchedWindowHuffmanBoundariesAndMixedGeometryMatchFfmpeg() {
        val ffmpeg = ffmpeg ?: return skip()
        val mismatches = mutableListOf<String>()
        for (rate in listOf(44_100, 48_000, 32_000, 22_050, 24_000, 16_000, 11_025, 12_000, 8_000)) {
            for (shape in Mp3SwitchedBlockFixtures.Shape.entries) {
                for (sameTable in listOf(false, true)) {
                    for (scaled in listOf(false, true)) {
                        val label = "$rate $shape sameTable=$sameTable scaled=$scaled"
                        val bytes = Mp3SwitchedBlockFixtures.file(rate, shape, sameTable, scaled = scaled)
                        val file = temp("switched_${rate}_${shape}_${sameTable}_$scaled.mp3")
                            .also { it.writeBytes(bytes) }
                        val reference = decodeWithFfmpeg(ffmpeg, file, rate, 1, "mp3float")
                        val decoded = AudioKodec.decode(bytes)
                        assertEquals(12 * (if (rate >= 32_000) 1_152 else 576), reference.size, label)
                        // This is the existing MP3 maximum per-sample allowance
                        // for differences in floating-point synthesis order.
                        assertEquals(reference.size, decoded.samples.size, label)
                        assertTrue(decoded.samples.all { it.isFinite() }, label)
                        val worst = reference.indices.maxOf { abs(reference[it] - decoded.samples[it]) }
                        if (worst > 1e-4f) mismatches += "$label: maximum sample difference $worst"
                    }
                }
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun leadingJunkKeepsAudioFromReferenceEncodersReadable() {
        val ffmpeg = ffmpeg ?: return skip()
        val wav = temp("leading-junk-source.wav")
        wav.writeBytes(AudioKodec.encode(sweep(32_000, 2, 44_100), AudioFormat(44_100, 2), EncodeTarget.wav(16)))
        for (layer in listOf(2, 3)) {
            val encoded = temp("leading-junk-source.mp$layer")
            val command = mutableListOf(ffmpeg, "-y", "-v", "quiet", "-i", wav.absolutePath,
                "-c:a", if (layer == 2) "mp2" else "libmp3lame", "-b:a", "128k")
            if (layer == 3) command += listOf("-write_xing", "0", "-id3v2_version", "0")
            command += encoded.absolutePath
            run(command, "encoding Layer $layer")
            val audio = encoded.readBytes()
            val header = assertNotNull(MpegFrameHeader.parse(audio, 0))
            val baseline = AudioKodec.decode(audio)
            val prefixes = listOf(byteArrayOf(1, 2, 3), ByteArray(1_000),
                audio.copyOfRange(header.frameBytes / 2, header.frameBytes))
            for ((index, prefix) in prefixes.withIndex()) {
                val file = temp("leading-junk-$index.mp$layer").also { it.writeBytes(prefix + audio) }
                val reference = decodeWithFfmpeg(ffmpeg, file, 44_100, 2, "mp${layer}float")
                val decoded = AudioKodec.decode(file.readBytes())
                // Float synthesis retains the established maximum per-sample
                // difference from FFmpeg for each MPEG layer.
                TestSignals.assertClose(reference, decoded.samples, if (layer == 2) 1e-5f else 1e-4f)
                TestSignals.assertExact(baseline.samples, decoded.samples)
                assertEquals(prefix.size.toLong(), AudioKodec.probe(file.readBytes()).dataOffset)
            }
        }
    }

    @Test
    fun largeEmbeddedTagsKeepTheReferenceAudioBeforeAndAfterTheJoin() {
        val ffmpeg = ffmpeg ?: return skip()
        val wav = temp("embedded-tag-source.wav")
        wav.writeBytes(AudioKodec.encode(sweep(32_000, 2, 44_100), AudioFormat(44_100, 2), EncodeTarget.wav(16)))
        for (layer in listOf(2, 3)) {
            val encoded = temp("embedded-tag-source.mp$layer")
            val command = mutableListOf(ffmpeg, "-y", "-v", "quiet", "-i", wav.absolutePath,
                "-c:a", if (layer == 2) "mp2" else "libmp3lame", "-b:a", "128k")
            if (layer == 3) command += listOf("-write_xing", "0", "-id3v2_version", "0")
            command += encoded.absolutePath
            run(command, "encoding Layer $layer")
            val link = encoded.readBytes()
            val plain = temp("embedded-tag-plain.mp$layer").also { it.writeBytes(link + link) }
            val reference = decodeWithFfmpeg(ffmpeg, plain, 44_100, 2, "mp${layer}float")
            val baseline = AudioKodec.decode(plain.readBytes())
            val bound = if (layer == 2) 1e-5f else 1e-4f
            // The float synthesis comparisons use the established per-layer
            // maximum error bounds, with every reference sample compared.
            TestSignals.assertClose(reference, baseline.samples, bound, "Layer $layer")
            for (version in 3..4) {
                val tagged = temp("embedded-tag-v$version.mp$layer").also {
                    it.writeBytes(link + MpegEmbeddedTagFixtures.tag(131_072, version, footer = version == 4) + link)
                }
                assertEquals(baseline.frames.toLong(), AudioKodec.probe(tagged.readBytes()).durationFrames)
                TestSignals.assertExact(baseline.samples, AudioKodec.decode(tagged.readBytes()).samples,
                    "Layer $layer, ID3v2.$version")
                val afterTag = decodeWithFfmpeg(ffmpeg, tagged, 44_100, 2, "mp${layer}float")
                // FFmpeg can drop one frame at an ID3 join. Its surviving tail
                // still independently confirms that the second link is read.
                assertTrue(afterTag.size >= reference.size - 1_152 * 2)
                TestSignals.assertClose(reference.takeLast(2_048).toFloatArray(),
                    afterTag.takeLast(2_048).toFloatArray(), bound, "Layer $layer: audio after the tag")
            }
        }
    }

    @Test
    fun strictTrailerHandlingKeepsTheAudioVerifiedByFfmpeg() {
        val ffmpeg = ffmpeg ?: return org.junit.Assume.assumeTrue("ffmpeg is required", false)
        for (case in MpegTolerantFrameFixtures.cases().take(3)) {
            val audio = MpegTrailerFixtures.audio(case)
            val file = temp("trailers_${case.name}.mp3").also { it.writeBytes(audio) }
            val codec = when (case.name) {
                "layer1" -> "mp1float"
                "layer2" -> "mp2float"
                else -> "mp3float"
            }
            val reference = decodeWithFfmpeg(ffmpeg, file, 44_100, case.channels, codec)
            val streams = if (case.name.startsWith("layer3")) listOf(audio, MpegTrailerFixtures.xing(case) + audio) else listOf(audio)
            for (stream in streams) {
                for (tail in MpegTrailerFixtures.tails(case)) {
                    for (unknown in listOf(false, true)) {
                        val decoded = AudioKodec.decode(MpegTrailerFixtures.source(stream + tail, unknown))
                        assertEquals(reference.size, decoded.samples.size, case.name)
                        val worst = reference.indices.maxOf { abs(reference[it] - decoded.samples[it]) }
                        // Floating-point synthesis order has the same allowance
                        // as the existing granule and tolerant-prefix comparisons.
                        assertTrue(worst < 1e-5f, "${case.name}: worst trailer sample difference $worst")
                    }
                }
            }
        }
    }

    @Test
    fun tolerantRecoveryKeepsThePrefixVerifiedByFfmpeg() {
        val ffmpeg = ffmpeg ?: return org.junit.Assume.assumeTrue("ffmpeg is required", false)
        for (case in MpegTolerantFrameFixtures.cases()) {
            val control = ByteArray(case.good.size * 12).also { bytes ->
                repeat(12) { case.good.copyInto(bytes, it * case.good.size) }
            }
            val file = temp("tolerant_${case.name}.mp3").also { it.writeBytes(control) }
            val codec = when (case.name) {
                "layer1" -> "mp1float"
                "layer2" -> "mp2float"
                else -> "mp3float"
            }
            val reference = decodeWithFfmpeg(ffmpeg, file, 44_100, case.channels, codec)
            assertEquals(12 * case.frames * case.channels, reference.size, case.name)
            val damaged = case.prefix() + case.damaged + case.good
            val recovered = AudioKodec.decode(damaged, DecodeOptions(tolerant = true))
            assertEquals(3 * case.frames, recovered.frames, case.name)
            val worst = recovered.samples.indices.maxOf { abs(reference[it] - recovered.samples[it]) }
            // The same bound as the Layer III granule oracle allows differences
            // in floating-point synthesis order. Layers I and II are silent here.
            assertTrue(worst < 1e-5f, "${case.name}: worst prefix sample difference $worst")
        }
    }

    @Test
    fun anEmptyFinalGranuleAtTheExactDataBoundaryMatchesFfmpeg() {
        val ffmpeg = ffmpeg ?: return org.junit.Assume.assumeTrue("ffmpeg is required", false)
        for (padded in listOf(false, true)) {
            val bytes = Mp3GranuleBoundaryFixtures.file(padded)
            val file = temp("empty_final_granule_$padded.mp3").also { it.writeBytes(bytes) }
            val reference = decodeWithFfmpeg(ffmpeg, file, 44_100, 2, "mp3float")
            assertEquals(12 * 1152 * 2, reference.size)
            val decoded = AudioKodec.decode(bytes)
            assertEquals(reference.size, decoded.samples.size)
            val worst = reference.indices.maxOf { abs(reference[it] - decoded.samples[it]) }
            // Different floating-point synthesis orders account for the last
            // few bits, as in the existing Layer III oracle comparisons.
            assertTrue(worst < 1e-5f, "padded=$padded: worst sample difference $worst")
        }
    }

    @Test
    fun mpegHeadersAgreeWithFfprobe() {
        val ffmpeg = ffmpeg ?: return skip()
        val ffprobe = ffprobe ?: return skip()
        for (rate in intArrayOf(32_000, 44_100, 48_000, 22_050, 16_000)) {
            for (channels in intArrayOf(1, 2)) {
                for (bitrate in intArrayOf(64, 128, 320)) {
                    if (rate < 32_000 && bitrate > 160) continue
                    val what = "MP3 $rate Hz, $channels channels, $bitrate kbps"
                    val frames = rate
                    val mp3 = encode(ffmpeg, frames, channels, rate, "-b:a", "${bitrate}k", "cbr_${rate}_${channels}_$bitrate")

                    val info = AudioKodec.probe(mp3.readBytes())
                    assertEquals(AudioContainer.MPEG_AUDIO, info.container, "$what: container")
                    assertEquals(AudioCodec.MP3, info.codec, "$what: codec")
                    assertEquals(rate, info.format.sampleRate, "$what: sample rate")
                    assertEquals(channels, info.format.channels, "$what: channels")

                    val probed = probeDuration(ffprobe, mp3)
                    val ours = info.durationFrames
                    assertNotNull(ours, "$what: duration")
                    // ffprobe reports the untrimmed length for a file whose
                    // gapless numbers it does not apply, so a frame either way
                    // is agreement.
                    val theirs = (probed * rate).toLong()
                    assertTrue(
                        abs(ours - theirs) <= 1152 * 2,
                        "$what: duration is $ours frames and ffprobe says $theirs",
                    )
                }
            }
        }
    }

    @Test
    fun aVariableBitrateFileReportsItsStoredFrameCount() {
        val lame = lame ?: return skip()
        val wav = temp("vbr.wav")
        wav.writeBytes(
            AudioKodec.encode(sweep(44_100 * 2, 2, 44_100), AudioFormat(44_100, 2), EncodeTarget.wav(16)),
        )
        val mp3 = temp("vbr.mp3")
        mp3.delete()
        run(listOf(lame, "--quiet", "-V", "2", wav.absolutePath, mp3.absolutePath), "LAME VBR")

        val info = AudioKodec.probe(mp3.readBytes())
        assertEquals(44_100, info.format.sampleRate, "sample rate")
        assertEquals(2, info.format.channels, "channels")
        assertTrue(info.isVariableBitrate, "LAME -V writes a variable bitrate file")
        val duration = assertNotNull(info.durationFrames, "duration")
        // LAME writes gapless information, so the length should come back as
        // what went in rather than as the padded frame count.
        assertTrue(
            abs(duration - 88_200L) <= 1_152,
            "a two second file decoded to $duration frames",
        )
    }

    /**
     * Tags in front of and behind the audio are not audio.
     *
     * A decoder that scans from byte zero finds a false sync inside a large
     * cover image sooner or later, and one that reads to the end of the file
     * decodes an ID3v1 block as a burst of noise.
     */
    @Test
    fun tagBlocksAreNotMistakenForAudio() {
        val lame = lame ?: return skip()
        val wav = temp("tagged.wav")
        wav.writeBytes(
            AudioKodec.encode(sweep(44_100, 1, 44_100), AudioFormat(44_100, 1), EncodeTarget.wav(16)),
        )
        val plain = temp("tagged-plain.mp3")
        plain.delete()
        run(listOf(lame, "--quiet", "-b", "128", wav.absolutePath, plain.absolutePath), "LAME")
        val plainInfo = AudioKodec.probe(plain.readBytes())

        // A large ID3v2 block in front, and an ID3v1 block behind.
        val picture = ByteArray(300_000) { ((it * 31) and 0xFF).toByte() }
        val tagged = AudioKodec.writeTags(
            plain.readBytes(),
            AudioKodec.readTags(plain.readBytes()).edit {
                set(io.github.yuroyami.audiokodec.tag.TagField.TITLE, "Tagged")
                addPicture(
                    io.github.yuroyami.audiokodec.tag.TagPicture(
                        mimeType = "image/jpeg",
                        description = "Cover",
                        bytes = picture,
                    ),
                )
            },
        )
        val taggedFile = temp("tagged.mp3")
        taggedFile.writeBytes(tagged)

        val info = AudioKodec.probe(tagged)
        assertEquals(plainInfo.format.sampleRate, info.format.sampleRate, "sample rate")
        assertEquals(plainInfo.durationFrames, info.durationFrames, "duration")
        assertTrue(
            info.dataOffset > 300_000,
            "the audio should start after the picture, and started at ${info.dataOffset}",
        )
    }

    /**
     * The decoded waveform has to be the one ffmpeg gets.
     *
     * Layer III is lossy, so the two decoders never agree bit for bit, but they
     * are decoding the same spectrum through the same filterbank and should
     * land within rounding of each other. A structural mistake, a wrong table
     * or a misplaced window shows up here as an error hundreds of times larger
     * than that, not as a subtle one.
     */
    @Test
    fun decodedAudioMatchesFfmpeg() {
        val ffmpeg = ffmpeg ?: return skip()
        for (rate in intArrayOf(44_100, 48_000, 32_000, 24_000, 16_000)) {
            for (channels in intArrayOf(1, 2)) {
                val what = "MP3 $rate Hz, $channels channels"
                val mp3 = encode(ffmpeg, rate, channels, rate, "-b:a", "128k", "dec_${rate}_$channels")

                val ours = AudioKodec.decode(mp3.readBytes())
                assertEquals(rate, ours.format.sampleRate, "$what: sample rate")
                assertEquals(channels, ours.format.channels, "$what: channels")

                val theirs = decodeWithFfmpeg(ffmpeg, mp3, rate, channels)
                assertEquals(
                    theirs.size / channels, ours.frames,
                    "$what: length differs from ffmpeg's",
                )
                val compared = minOf(ours.frames, theirs.size / channels)
                assertTrue(
                    compared > rate / 2,
                    "$what: only $compared frames to compare, ours has ${ours.frames} and " +
                        "ffmpeg has ${theirs.size / channels}",
                )

                var worst = 0.0
                var sumSquared = 0.0
                for (i in 0 until compared * channels) {
                    val delta = (ours.samples[i] - theirs[i]).toDouble()
                    if (abs(delta) > worst) worst = abs(delta)
                    sumSquared += delta * delta
                }
                val rms = kotlin.math.sqrt(sumSquared / (compared * channels))
                assertTrue(
                    worst < 1e-4 && rms < 1e-5,
                    "$what: worst sample differs by $worst and the RMS difference is $rms " +
                        "over $compared frames",
                )
            }
        }
    }

    /**
     * A variable bitrate file decodes to the same length as a constant one.
     *
     * LAME's own encoder is a different writer from ffmpeg's wrapper around it,
     * and a variable bitrate stream exercises the bit reservoir far harder: a
     * frame can be a quarter the size of the one before it and borrow the rest.
     */
    @Test
    fun aVariableBitrateFileDecodes() {
        val lame = lame ?: return skip()
        val ffmpeg = ffmpeg ?: return skip()
        val frames = 44_100 * 2
        val wav = temp("vbrdec.wav")
        wav.writeBytes(
            AudioKodec.encode(sweep(frames, 2, 44_100), AudioFormat(44_100, 2), EncodeTarget.wav(16)),
        )
        val mp3 = temp("vbrdec.mp3")
        mp3.delete()
        run(listOf(lame, "--quiet", "-V", "0", wav.absolutePath, mp3.absolutePath), "LAME VBR")

        val ours = AudioKodec.decode(mp3.readBytes())
        assertTrue(AudioKodec.probe(mp3.readBytes()).isVariableBitrate, "LAME -V 0 is variable")
        val theirs = decodeWithFfmpeg(ffmpeg, mp3, 44_100, 2)
        assertEquals(theirs.size / 2, ours.frames, "length differs from ffmpeg's")

        var worst = 0.0
        for (i in 0 until minOf(ours.samples.size, theirs.size)) {
            val delta = abs(ours.samples[i] - theirs[i]).toDouble()
            if (delta > worst) worst = delta
        }
        assertTrue(worst < 1e-4, "the worst sample differs by $worst")
    }

    /**
     * Seeking lands on the sample it was asked for.
     *
     * Layer III makes this harder than it sounds. A frame borrows bits from the
     * ones before it and the transform carries an overlap between granules, so
     * a decoder that simply jumps to a frame boundary produces a click and then
     * several frames of quietly wrong audio. The check is therefore not that
     * seeking returns something, but that it returns what a straight decode
     * from the beginning produced at the same place.
     */
    @Test
    fun seekingLandsOnTheRightSample() {
        val ffmpeg = ffmpeg ?: return skip()
        val rate = 44_100
        val mp3 = encode(ffmpeg, rate * 3, 2, rate, "-b:a", "192k", "seek")
        val whole = AudioKodec.decode(mp3.readBytes())

        AudioKodec.open(mp3.readBytes()).use { decoder ->
            for (target in longArrayOf(0, 1, 1151, 1152, 5_000, 44_100, 100_000, whole.frames - 2_000L)) {
                if (target < 0 || target >= whole.frames) continue
                val landed = decoder.seek(target)
                assertEquals(target, landed, "seek to $target reported landing elsewhere")

                val frame = decoder.nextFrame()
                assertNotNull(frame, "seek to $target then read gave nothing")
                assertEquals(target, frame.positionFrames, "seek to $target: reported position")

                val compare = minOf(frame.frameCount, 512)
                var worst = 0.0
                for (i in 0 until compare * 2) {
                    val delta = abs(frame.samples[i] - whole.samples[(target * 2 + i).toInt()])
                    if (delta > worst) worst = delta.toDouble()
                }
                // The reservoir and the overlap are refilled by decoding ahead,
                // so this is not "close enough", it is the same samples.
                assertTrue(
                    worst < 1e-5,
                    "seek to $target: the worst of $compare frames differs by $worst from a " +
                        "straight decode",
                )
            }
        }
    }

    /**
     * Layer II decodes to what ffmpeg gets.
     *
     * Layer II is a different decoder from Layer III sharing only the
     * filterbank, and its bit allocation table is chosen by the bitrate, so
     * this covers several bitrates on purpose: at 32 kbps a channel the format
     * switches to a table with eight subbands instead of twenty-seven, and a
     * decoder that picks the wrong one produces noise rather than a small
     * error.
     */
    @Test
    fun layerTwoDecodesLikeFfmpeg() {
        val ffmpeg = ffmpeg ?: return skip()
        for (rate in intArrayOf(44_100, 48_000, 32_000, 24_000, 16_000)) {
            // MPEG-2 stops at 160 kbps, and its one allocation table covers
            // every rate it does reach.
            val bitrates = if (rate < 32_000) intArrayOf(32, 64, 160) else intArrayOf(64, 128, 192)
            for (channels in intArrayOf(1, 2)) {
                for (bitrate in bitrates) {
                    val what = "MP2 $rate Hz, $channels channels, $bitrate kbps"
                    val mp2 = encodeWith(
                        ffmpeg, "mp2", rate, channels, "l2_${rate}_${channels}_$bitrate",
                        "-b:a", "${bitrate}k",
                    )

                    val info = AudioKodec.probe(mp2.readBytes())
                    assertEquals(AudioCodec.MP2, info.codec, "$what: codec")
                    assertEquals(rate, info.format.sampleRate, "$what: sample rate")

                    val ours = AudioKodec.decode(mp2.readBytes())
                    val theirs = decodeWithFfmpeg(ffmpeg, mp2, rate, channels, "mp2float")
                    val compared = minOf(ours.frames, theirs.size / channels)
                    assertTrue(compared > rate / 2, "$what: only $compared frames to compare")

                    var worst = 0.0
                    for (i in 0 until compared * channels) {
                        val delta = abs(ours.samples[i] - theirs[i]).toDouble()
                        if (delta > worst) worst = delta
                    }
                    assertTrue(worst < 1e-4, "$what: the worst sample differs by $worst")
                }
            }
        }
    }

    /**
     * Layer I decodes to what ffmpeg gets.
     *
     * No encoder for Layer I is still shipping anywhere, so the file is written
     * here: a frame's layout is simple enough to build by hand, and ffmpeg
     * still decodes it. That makes this a real comparison rather than a test of
     * AudioKodec against itself.
     */
    @Test
    fun layerOneDecodesLikeFfmpeg() {
        val ffmpeg = ffmpeg ?: return skip()
        val rate = 44_100
        val mp1 = temp("built.mp1")
        mp1.writeBytes(buildLayer1File(frames = 60, rate = rate))

        val info = AudioKodec.probe(mp1.readBytes())
        assertEquals(AudioCodec.MP1, info.codec, "codec")
        assertEquals(rate, info.format.sampleRate, "sample rate")
        assertEquals(1, info.format.channels, "channels")

        val ours = AudioKodec.decode(mp1.readBytes())
        val theirs = decodeWithFfmpeg(ffmpeg, mp1, rate, 1, "mp1float")
        val compared = minOf(ours.frames, theirs.size)
        assertTrue(compared >= 384 * 55, "only $compared frames to compare")

        var worst = 0.0
        for (i in 0 until compared) {
            val delta = abs(ours.samples[i] - theirs[i]).toDouble()
            if (delta > worst) worst = delta
        }
        assertTrue(worst < 1e-4, "the worst sample differs by $worst")
    }

    /**
     * Writes a mono Layer I file whose subband samples follow a fixed pattern.
     *
     * The point is not that it sounds like anything, but that every subband
     * carries a different allocation width and a different scalefactor, so a
     * decoder that mixes up a width, a scalefactor or the order they are read
     * in cannot agree with another decoder by luck.
     */
    private fun buildLayer1File(frames: Int, rate: Int): ByteArray {
        // 448 kbps, which is bitrate index 14 and leaves room for every
        // allocation width the test uses.
        val bitrateIndex = 14
        val bitrate = 448
        // Layer I counts its frames in four-byte slots.
        val frameBytes = (12 * bitrate * 1000 / rate) * 4
        val out = ByteArray(frameBytes * frames)
        for (f in 0 until frames) {
            val frame = ByteArray(frameBytes)
            // MPEG-1 Layer I, no CRC, 192 kbps, 44100 Hz, mono.
            frame[0] = 0xFF.toByte()
            frame[1] = 0xFF.toByte()
            frame[2] = ((bitrateIndex shl 4) or (0 shl 2)).toByte()
            frame[3] = (3 shl 6).toByte()

            val writer = io.github.yuroyami.audiokodec.internal.bits.BitWriterMsb()
            val widths = IntArray(32) { if (it < 16) (it % 14) + 1 else 0 }
            for (sb in 0 until 32) writer.writeBits(widths[sb], 4)
            for (sb in 0 until 32) {
                if (widths[sb] != 0) writer.writeBits((sb * 3 + f) % 63, 6)
            }
            for (slot in 0 until 12) {
                for (sb in 0 until 32) {
                    val width = widths[sb]
                    if (width == 0) continue
                    val bits = width + 1
                    val value = (slot * 37 + sb * 11 + f * 5) and ((1 shl bits) - 1)
                    writer.writeBits(value, bits)
                }
            }
            writer.alignToByte()
            val payload = writer.toByteArray()
            payload.copyInto(frame, 4, 0, minOf(payload.size, frameBytes - 4))
            frame.copyInto(out, f * frameBytes)
        }
        return out
    }

    private fun encodeWith(
        ffmpeg: String,
        codec: String,
        rate: Int,
        channels: Int,
        name: String,
        vararg options: String,
    ): File {
        val wav = temp("$name.wav")
        wav.writeBytes(
            AudioKodec.encode(sweep(rate, channels, rate), AudioFormat(rate, channels), EncodeTarget.wav(16)),
        )
        val out = temp("$name.${if (codec == "mp2") "mp2" else codec}")
        out.delete()
        val command = ArrayList<String>()
        command += listOf(ffmpeg, "-y", "-v", "quiet", "-i", wav.absolutePath, "-c:a", codec)
        command += options
        command += out.absolutePath
        run(command, "encoding $name")
        return out
    }

    /** Decodes to raw 32-bit float with ffmpeg, interleaved. */
    private fun decodeWithFfmpeg(
        ffmpeg: String,
        mp3: File,
        rate: Int,
        channels: Int,
        decoder: String? = null,
    ): FloatArray {
        val raw = File(mp3.absolutePath + ".f32")
        raw.delete()
        val command = ArrayList<String>()
        command += listOf(ffmpeg, "-y", "-v", "quiet")
        // ffmpeg ships two decoders for Layers I and II and picks the fixed
        // point one by default. Its own float decoder disagrees with it by
        // about 0.03, so the fixed point one is no use as a reference for
        // anything finer than that.
        if (decoder != null) command += listOf("-c:a", decoder)
        command += listOf(
            "-i", mp3.absolutePath,
            "-f", "f32le", "-acodec", "pcm_f32le", "-ar", "$rate", "-ac", "$channels",
            raw.absolutePath,
        )
        run(command, "decoding ${mp3.name}")
        val bytes = raw.readBytes()
        val out = FloatArray(bytes.size / 4)
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in out.indices) out[i] = buffer.getFloat(i * 4)
        return out
    }

    private fun sweep(frames: Int, channels: Int, rate: Int): FloatArray {
        val out = FloatArray(frames * channels)
        for (c in 0 until channels) {
            val start = 200.0 * (c + 1)
            for (f in 0 until frames) {
                val t = f / rate.toDouble()
                out[f * channels + c] =
                    (0.7 * kotlin.math.sin(2.0 * Math.PI * (start * t + 2_000.0 * t * t))).toFloat()
            }
        }
        return out
    }

    private fun encode(
        ffmpeg: String,
        frames: Int,
        channels: Int,
        rate: Int,
        vararg options: String,
    ): File {
        val name = options.last()
        val wav = temp("$name.wav")
        wav.writeBytes(
            AudioKodec.encode(sweep(frames, channels, rate), AudioFormat(rate, channels), EncodeTarget.wav(16)),
        )
        val mp3 = temp("$name.mp3")
        mp3.delete()
        val command = ArrayList<String>()
        command += listOf(ffmpeg, "-y", "-v", "quiet", "-i", wav.absolutePath, "-c:a", "libmp3lame")
        command += options.dropLast(1)
        command += mp3.absolutePath
        run(command, "encoding $name")
        return mp3
    }

    private fun probeDuration(ffprobe: String, file: File): Double {
        val process = ProcessBuilder(
            listOf(
                ffprobe, "-v", "quiet", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", file.absolutePath,
            ),
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(60, TimeUnit.SECONDS)
        return text.toDoubleOrNull() ?: 0.0
    }

    private fun run(command: List<String>, what: String) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(180, TimeUnit.SECONDS)
        assertTrue(finished, "${command.first()} did not finish within three minutes")
        assertEquals(0, process.exitValue(), "$what: ${command.joinToString(" ")} failed:\n$output")
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private fun skip() {
        org.junit.Assume.assumeTrue("an MPEG oracle tool is not on the path, so the comparison did not run", false)
    }

    private companion object {
        val workDirectory: File = File(
            System.getProperty("java.io.tmpdir"), "audiokodec-mpeg-oracle",
        ).also { it.mkdirs() }

        fun findOnPath(tool: String): String? {
            val path = System.getenv("PATH") ?: return null
            for (directory in path.split(File.pathSeparator)) {
                val candidate = File(directory, tool)
                if (candidate.canExecute()) return candidate.absolutePath
            }
            return null
        }
    }
}

package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.ogg.OggReader
import io.github.yuroyami.audiokodec.internal.ogg.OggCrc
import io.github.yuroyami.audiokodec.internal.tag.VorbisComment
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checks the Ogg container layer against files the reference tools produce.
 *
 * Ogg carries several codecs, and the container work is the same for all of
 * them: pages, checksums, packet reassembly, granule positions and the comment
 * header. These tests cover that layer across every mapping, and cover decoding
 * for the one mapping whose codec AudioKodec has, which is FLAC.
 *
 * Every test skips itself when its tool is missing.
 */
class OggOracleTest {

    private val ffmpeg: String? by lazy { findOnPath("ffmpeg") }
    private val flac: String? by lazy { findOnPath("flac") }

    @Test
    fun damagedReferencePagesCannotSpliceLaterAudioIntoThePrefix() {
        val ffmpeg = ffmpeg ?: return skip()
        for (encoder in listOf("libopus", "libvorbis", "flac")) {
            val file = temp("damaged_page_$encoder.ogg")
            run(listOf(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i",
                "aevalsrc=sin(2*PI*(400*t+200*t*t)):s=48000:d=4", "-c:a", encoder,
                "-page_duration", "250000", "-f", "ogg", file.absolutePath), encoder)
            val bytes = file.readBytes()
            val baseline = AudioKodec.decode(bytes).samples
            val raw = temp("damaged_page_$encoder.f32")
            run(listOf(ffmpeg, "-v", "error", "-y", "-i", file.absolutePath,
                "-f", "f32le", raw.absolutePath), encoder)
            val floats = ByteBuffer.wrap(raw.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val reference = FloatArray(floats.remaining() / 4) { floats.float }
            val pages = OggDamageFixtures.pages(bytes)
            val badIndex = pages.size / 2
            val previous = OggDamageFixtures.granule(bytes, pages[badIndex - 1]) -
                if (encoder == "libopus") 312 else 0
            assertTrue(previous in 48_000..144_000, encoder)
            for (kind in OggDamageFixtures.kinds) {
                val damaged = OggDamageFixtures.damage(bytes, badIndex, kind)
                assertFailsWith<AudioKodecException> { AudioKodec.decode(damaged) }
                for (knownSize in listOf(false, true)) {
                    val prefix = AudioKodec.decode(OggDamageFixtures.source(damaged, knownSize), DecodeOptions(tolerant = true))
                    assertEquals(previous, prefix.frames.toLong(), "$encoder, $kind")
                    assertEquals(previous, prefix.info.durationFrames, "$encoder, $kind")
                    TestSignals.assertExact(baseline.copyOf(prefix.samples.size), prefix.samples, "$encoder, $kind")
                    // Vorbis/Opus floating reconstruction agrees within 1e-4;
                    // FLAC is lossless and must match the external PCM exactly.
                    if (encoder == "flac") TestSignals.assertExact(reference.copyOf(prefix.samples.size), prefix.samples, encoder)
                    else TestSignals.assertClose(reference.copyOf(prefix.samples.size), prefix.samples, 1e-4f, encoder)
                    if (encoder == "flac") {
                        AudioKodec.open(OggDamageFixtures.source(damaged, knownSize), DecodeOptions(tolerant = true)).use { decoder ->
                            val integers = decoder as IntPcmAccess
                            val scale = (1L shl (integers.bitDepth - 1)).toDouble()
                            var count = 0
                            while (true) {
                                val block = integers.nextIntFrame() ?: break
                                assertEquals(count.toLong(), block.positionFrames)
                                repeat(block.sampleCount) { assertEquals((reference[count + it] * scale).toInt(), block.samples[it]) }
                                count += block.frameCount
                            }
                            assertEquals(previous, count.toLong())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun aSharedFinalHeaderPageCannotDeleteReferenceEncodedOpusPacketsDuringATagEdit() {
        val ffmpeg = ffmpeg ?: return skip()
        val original = temp("shared_header_original.opus")
        run(listOf(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i",
            "sine=frequency=440:sample_rate=48000:duration=1.1", "-c:a", "libopus",
            "-page_duration", "1000000", "-metadata", "title=Seed", original.absolutePath), "Opus")
        val bytes = original.readBytes()
        val shared = OggSharedHeaderFixtures.merge(bytes)
        val malformed = temp("shared_header_malformed.opus").also { it.writeBytes(shared) }
        assertEquals(56, OggSharedHeaderFixtures.audioPackets(shared).size)
        for (file in listOf(original, malformed)) {
            val pcm = temp("${file.nameWithoutExtension}.f32")
            run(listOf(ffmpeg, "-v", "error", "-y", "-i", file.absolutePath,
                "-f", "f32le", pcm.absolutePath), "decoding the complete Opus recording")
            assertEquals(52_800 * 4L, pcm.length(), file.name)
        }
        assertContentEquals(temp("shared_header_original.f32").readBytes(),
            temp("shared_header_malformed.f32").readBytes())
        val sink = ByteArrayAudioSink()
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.writeTags(shared.asAudioSource(),
                AudioKodec.readTags(shared).edit { set(TagField.TITLE, "Changed") },
                sink, decodeOptions = DecodeOptions(tolerant = true))
        }
        assertEquals(0, sink.toByteArray().size)
    }

    @Test
    fun malformedCommentTextDoesNotChangeReferenceEncodedAudio() {
        val ffmpeg = ffmpeg ?: return skip()
        for (encoder in listOf("libvorbis", "libopus", "flac", "native_flac")) {
            val file = temp("bad_utf8_$encoder.${if (encoder == "native_flac") "flac" else "ogg"}")
            run(listOf(
                ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                "sine=frequency=440:sample_rate=48000:duration=0.5", "-c:a",
                if (encoder == "native_flac") "flac" else encoder,
                "-metadata", "title=Seed", "-metadata", "artist=Readable", file.absolutePath,
            ), encoder)
            val original = file.readBytes()
            val raw = temp("bad_utf8_$encoder.f32")
            run(listOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", file.absolutePath,
                "-f", "f32le", raw.absolutePath), encoder)
            val floats = ByteBuffer.wrap(raw.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val reference = FloatArray(floats.remaining() / 4) { floats.float }
            val baseline = AudioKodec.decode(original).samples
            assertEquals(24_000, baseline.size, "$encoder: the full half-second recording")
            if (encoder.contains("flac")) TestSignals.assertExact(reference, baseline, encoder)
            // Floating-point reconstruction in Vorbis and Opus can differ
            // from FFmpeg. The existing codec oracle bound is 1e-4 here.
            else {
                // FFmpeg can omit the terminal Vorbis overlap. Compare every
                // reference sample; the full output must remain identical
                // across the metadata mutations and rewrites below.
                assertTrue(reference.size <= baseline.size, encoder)
                TestSignals.assertClose(reference, baseline.copyOf(reference.size), 1e-4f, encoder)
            }
            for (badVendor in listOf(false, true)) {
                val bytes = invalidCommentText(original, badVendor)
                for (tolerant in listOf(false, true)) {
                    TestSignals.assertExact(baseline, AudioKodec.decode(bytes, DecodeOptions(tolerant = tolerant)).samples,
                        "$encoder, badVendor=$badVendor, tolerant=$tolerant")
                }
                val tags = AudioKodec.readTags(bytes)
                assertEquals("Readable", tags[TagField.ARTIST], encoder)
                if (badVendor) assertEquals("Seed", tags[TagField.TITLE], encoder) else assertNull(tags[TagField.TITLE], encoder)
                val repaired = AudioKodec.writeTags(bytes, tags.edit { set(TagField.TITLE, "Repaired") })
                assertEquals("Repaired", AudioKodec.readTags(repaired)[TagField.TITLE], encoder)
                TestSignals.assertExact(baseline, AudioKodec.decode(repaired).samples, encoder)
            }
        }
    }

    private fun invalidCommentText(original: ByteArray, vendor: Boolean): ByteArray {
        val info = AudioKodec.probe(original)
        val block = AudioKodec.readTags(original).raw.blocksOf(TagFormat.VORBIS_COMMENT).first()
        val from = block.offset.toInt() + if (info.container != AudioContainer.OGG) 0 else when (info.codec) {
            AudioCodec.VORBIS -> 7
            AudioCodec.OPUS -> 8
            else -> 4
        }
        val end = assertNotNull(VorbisComment.contentEnd(original, from, original.size))
        val seed = "Seed".encodeToByteArray()
        val at = if (vendor) from + 4 else (from..end - seed.size).first { start ->
            seed.indices.all { original[start + it] == seed[it] }
        } + seed.lastIndex
        val bytes = original.copyOf().also { it[at] = 0xE9.toByte() }
        if (info.container == AudioContainer.OGG) {
            val reader = OggReader(SourceReader(original.asAudioSource(), DecodeOptions.DEFAULT), DecodeOptions.DEFAULT)
            var offset = 0L
            while (true) {
                val page = assertNotNull(reader.pageAt(offset))
                val next = offset + page.byteLength
                if (at < next) {
                    val crc = OggCrc.ofPage(bytes, offset.toInt(), next.toInt(), offset.toInt() + 22)
                    repeat(4) { bytes[offset.toInt() + 22 + it] = (crc ushr (8 * it)).toByte() }
                    break
                }
                offset = next
            }
        }
        return bytes
    }

    @Test
    fun referenceTagsRemainReadableWhenLaterAudioPagesAreDamaged() {
        val ffmpeg = ffmpeg ?: return skip()
        for (encoder in listOf("libvorbis", "libopus", "flac")) {
            val file = temp("damaged_tags_$encoder.ogg")
            run(listOf(
                ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                "sine=frequency=440:sample_rate=48000:duration=1.0", "-c:a", encoder,
                "-metadata", "title=Still readable", "-metadata", "artist=An artist", file.absolutePath,
            ), encoder)
            val original = file.readBytes()
            val info = AudioKodec.probe(original)
            val page = assertNotNull(OggReader(SourceReader(original.asAudioSource(), DecodeOptions.DEFAULT), DecodeOptions.DEFAULT).pageAt(info.dataOffset))
            val damaged = original.copyOf().also {
                val at = (page.offset + page.byteLength - 1).toInt()
                it[at] = (it[at].toInt() xor 1).toByte()
            }
            val cut = original.copyOf(original.size - 20)
            for (bytes in listOf(original, damaged, cut)) {
                val tags = AudioKodec.readTags(bytes)
                assertEquals("Still readable", tags[TagField.TITLE], encoder)
                assertEquals("An artist", tags[TagField.ARTIST], encoder)
            }
            assertFailsWith<AudioKodecException> { AudioKodec.probe(damaged) }
            assertFailsWith<AudioKodecException> { AudioKodec.probe(cut) }
        }
    }

    @Test
    fun referenceEncodersAgreeOnEverySurroundMappingLayout() {
        val ffmpeg = ffmpeg ?: return skip()
        val flac = flac ?: return skip()
        for (channels in 1..8) {
            val layout = if (channels == 7) {
                ChannelLayout.of(
                    AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_CENTER,
                    AudioChannel.LOW_FREQUENCY, AudioChannel.BACK_CENTER,
                    AudioChannel.SIDE_LEFT, AudioChannel.SIDE_RIGHT,
                )
            } else {
                ChannelLayout.canonical(channels)
            }
            val original = TestSignals.quantise(TestSignals.sine(4_096, channels), 16)
            val wav = temp("layout_$channels.wav")
            wav.writeBytes(AudioKodec.encode(original, AudioFormat(44_100, layout), EncodeTarget.wav(16)))
            for (encoder in listOf("libvorbis", "libopus", "flac")) {
                val what = "$encoder with $channels channels"
                val ogg = temp("layout_${encoder}_$channels.ogg")
                if (encoder == "flac") {
                    run(listOf(flac, "--silent", "--force", "--ogg", "-o", ogg.absolutePath, wav.absolutePath), what)
                } else {
                    run(listOf(
                        ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", wav.absolutePath,
                        "-c:a", encoder, ogg.absolutePath,
                    ), what)
                }
                val bytes = ogg.readBytes()
                val expected = AudioFormat(if (encoder == "libopus") 48_000 else 44_100, layout)
                assertEquals(expected, AudioKodec.probe(bytes).format, "$what: probe")
                AudioKodec.open(bytes).use { decoder ->
                    assertEquals(expected, decoder.format, "$what: open")
                    assertEquals(expected, decoder.info.format, "$what: info")
                    assertEquals(expected, assertNotNull(decoder.nextFrame()).format, "$what: frame")
                }
                val decoded = AudioKodec.decode(bytes)
                assertEquals(expected, decoded.format, "$what: decode")
                if (encoder == "flac") TestSignals.assertExact(original, decoded.samples, what)
            }
        }
    }

    @Test
    fun oggFlacDecodesBitExactly() {
        val flac = flac ?: return skip()
        for (depth in intArrayOf(8, 16, 24)) {
            for (channels in intArrayOf(1, 2)) {
                val what = "Ogg FLAC, $depth-bit, $channels channels"
                val frames = 12_000
                val original = TestSignals.quantise(
                    TestSignals.sine(frames, channels, amplitude = 0.8f), depth,
                )
                val wav = temp("oggflac_${depth}_$channels.wav")
                wav.writeBytes(
                    AudioKodec.encode(original, AudioFormat(44_100, channels), EncodeTarget.wav(depth)),
                )
                val ogg = temp("oggflac_${depth}_$channels.oga")
                ogg.delete()
                run(
                    listOf(
                        flac, "--silent", "--force", "--ogg", "-5",
                        "-o", ogg.absolutePath, wav.absolutePath,
                    ),
                    what,
                )

                assertEquals(AudioContainer.OGG, AudioKodec.sniff(ogg.readBytes()), "$what: sniff")
                val info = AudioKodec.probe(ogg.readBytes())
                assertEquals(AudioContainer.OGG, info.container, "$what: container")
                assertEquals(AudioCodec.FLAC, info.codec, "$what: codec")
                assertEquals(44_100, info.format.sampleRate, "$what: sample rate")
                assertEquals(channels, info.format.channels, "$what: channels")
                assertEquals(depth, info.bitDepth, "$what: bit depth")
                assertEquals(frames.toLong(), info.durationFrames, "$what: duration")

                val decoded = AudioKodec.decode(ogg.readBytes())
                assertEquals(frames, decoded.frames, "$what: frame count")
                TestSignals.assertExact(original, decoded.samples, what)
            }
        }
    }

    @Test
    fun theIntegerViewOfAnOggFlacFileIsExact() {
        val flac = flac ?: return skip()
        val values = IntArray(6_000) { ((it * 7_919) % 65_536) - 32_768 }
        val wav = temp("oggflac-int.wav")
        wav.writeBytes(
            AudioKodec.encode(
                Pcm.floatFromInt(values, 16), AudioFormat(48_000, 1), EncodeTarget.wav(16),
            ),
        )
        val ogg = temp("oggflac-int.oga")
        ogg.delete()
        run(
            listOf(flac, "--silent", "--force", "--ogg", "-8", "-o", ogg.absolutePath, wav.absolutePath),
            "integer view",
        )
        AudioKodec.open(ogg.readBytes()).use { decoder ->
            val access = assertIs<IntPcmAccess>(decoder, "an Ogg FLAC decoder exposes integers")
            var read = 0
            while (true) {
                val block = access.nextIntFrame() ?: break
                for (i in 0 until block.frameCount) {
                    assertEquals(values[read + i], block.samples[i], "sample ${read + i}")
                }
                read += block.frameCount
            }
            assertEquals(values.size, read)
        }
    }

    @Test
    fun seekingInsideAnOggFlacFileIsSampleAccurate() {
        val flac = flac ?: return skip()
        val frames = 40_000
        val original = TestSignals.quantise(TestSignals.sine(frames, 2), 16)
        val wav = temp("oggflac-seek.wav")
        wav.writeBytes(AudioKodec.encode(original, AudioFormat(44_100, 2), EncodeTarget.wav(16)))
        val ogg = temp("oggflac-seek.oga")
        ogg.delete()
        run(
            listOf(flac, "--silent", "--force", "--ogg", "-5", "-o", ogg.absolutePath, wav.absolutePath),
            "seeking",
        )

        AudioKodec.open(ogg.readBytes()).use { decoder ->
            for (target in longArrayOf(0, 1, 4_095, 4_096, 20_000, 39_999)) {
                val reached = decoder.seek(target)
                assertEquals(target, reached, "seek to $target")
                val block = assertNotNull(decoder.nextFrame(), "nothing after seeking to $target")
                assertEquals(target, block.positionFrames, "reported position after $target")
                for (channel in 0 until 2) {
                    assertEquals(
                        original[target.toInt() * 2 + channel],
                        block[0, channel],
                        "channel $channel of frame $target",
                    )
                }
            }
        }
    }

    @Test
    fun everythingAfterASeekMatchesAFullDecode() {
        val flac = flac ?: return skip()
        val frames = 30_000
        val original = TestSignals.quantise(TestSignals.sine(frames, 1), 16)
        val wav = temp("oggflac-tail.wav")
        wav.writeBytes(AudioKodec.encode(original, AudioFormat(44_100, 1), EncodeTarget.wav(16)))
        val ogg = temp("oggflac-tail.oga")
        ogg.delete()
        run(
            listOf(flac, "--silent", "--force", "--ogg", "-5", "-o", ogg.absolutePath, wav.absolutePath),
            "seek tail",
        )
        val target = 17_777
        AudioKodec.open(ogg.readBytes()).use { decoder ->
            decoder.seek(target.toLong())
            val rest = ArrayList<Float>()
            while (true) {
                val block = decoder.nextFrame() ?: break
                for (i in 0 until block.sampleCount) rest.add(block.samples[i])
            }
            assertEquals(frames - target, rest.size)
            for (i in rest.indices) {
                assertEquals(original[target + i], rest[i], "sample ${target + i} after seeking")
            }
        }
    }

    @Test
    fun theHeadersOfEveryOggMappingAreRead() {
        val ffmpeg = ffmpeg ?: return skip()
        // The container work is the same whatever the codec, so the headers of
        // the mappings whose codecs are not implemented still have to be read.
        val cases = listOf(
            Triple("libvorbis", AudioCodec.VORBIS, 44_100),
            Triple("libopus", AudioCodec.OPUS, 48_000),
            Triple("libspeex", AudioCodec.SPEEX, 16_000),
        )
        for ((encoder, codec, rate) in cases) {
            val ogg = temp("mapping_$encoder.ogg")
            ogg.delete()
            val made = tryRun(
                listOf(
                    ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=$rate:duration=1.0",
                    "-c:a", encoder, ogg.absolutePath,
                ),
            )
            org.junit.Assume.assumeTrue("ffmpeg must encode $encoder to check this mapping", made)

            val what = "the $encoder mapping"
            assertEquals(AudioContainer.OGG, AudioKodec.sniff(ogg.readBytes()), "$what: sniff")
            val info = AudioKodec.probe(ogg.readBytes())
            assertEquals(codec, info.codec, "$what: codec")
            assertEquals(rate, info.format.sampleRate, "$what: sample rate")
            assertEquals(1, info.format.channels, "$what: channels")
            // A second of audio, give or take the codec's own padding.
            val duration = assertNotNull(info.durationFrames, "$what: duration")
            val seconds = duration.toDouble() / info.format.sampleRate
            assertTrue(
                seconds > 0.9 && seconds < 1.1,
                "$what: reported $seconds seconds where one was encoded",
            )
        }
    }

    @Test
    fun theCommentsOfEveryOggMappingAreRead() {
        val ffmpeg = ffmpeg ?: return skip()
        for (encoder in listOf("libvorbis", "libopus")) {
            val ogg = temp("tags_$encoder.ogg")
            ogg.delete()
            val made = tryRun(
                listOf(
                    ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=0.5",
                    "-metadata", "title=Ogg Title",
                    "-metadata", "artist=Ogg Artist",
                    "-metadata", "album=Ogg Album",
                    "-metadata", "track=4",
                    "-metadata", "genre=Ambient",
                    "-c:a", encoder, ogg.absolutePath,
                ),
            )
            org.junit.Assume.assumeTrue("ffmpeg must encode $encoder to check its comments", made)

            val tags = AudioKodec.readTags(ogg.readBytes())
            assertEquals("Ogg Title", tags[TagField.TITLE], "$encoder title")
            assertEquals("Ogg Artist", tags[TagField.ARTIST], "$encoder artist")
            assertEquals("Ogg Album", tags[TagField.ALBUM], "$encoder album")
            assertEquals("4", tags[TagField.TRACK_NUMBER], "$encoder track")
            assertEquals("Ambient", tags[TagField.GENRE], "$encoder genre")
            assertEquals(
                TagFormat.VORBIS_COMMENT,
                tags.raw.blocks.first().format,
                "$encoder tag format",
            )
        }
    }

    @Test
    fun theCommentsOfAnOggFlacFileAreRead() {
        val flac = flac ?: return skip()
        val wav = temp("oggflac-tags-source.wav")
        wav.writeBytes(
            AudioKodec.encode(
                TestSignals.quantise(TestSignals.sine(3_000, 2), 16),
                AudioFormat(44_100, 2),
                EncodeTarget.wav(16),
            ),
        )
        val ogg = temp("oggflac-tags.oga")
        ogg.delete()
        run(
            listOf(
                flac, "--silent", "--force", "--ogg", "-5",
                "--tag=TITLE=Ogg FLAC Title",
                "--tag=ARTIST=Ogg FLAC Artist",
                "--tag=TRACKNUMBER=7",
                "-o", ogg.absolutePath, wav.absolutePath,
            ),
            "Ogg FLAC tags",
        )
        val tags = AudioKodec.readTags(ogg.readBytes())
        assertEquals("Ogg FLAC Title", tags[TagField.TITLE])
        assertEquals("Ogg FLAC Artist", tags[TagField.ARTIST])
        assertEquals("7", tags[TagField.TRACK_NUMBER])
    }

    @Test
    fun theAudioStreamOfAMultiplexedFileIsFound() {
        val ffmpeg = ffmpeg ?: return skip()
        // A file with video and audio interleaved: the pages alternate between
        // two logical streams, and the audio has to be picked out of them.
        val ogg = temp("multiplexed.ogv")
        ogg.delete()
        val made = tryRun(
            listOf(
                ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=size=64x64:rate=10:duration=1",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=1",
                "-c:v", "libtheora", "-c:a", "vorbis", "-strict", "-2",
                ogg.absolutePath,
            ),
        )
        if (!made) {
            println("this ffmpeg cannot encode Theora, so the multiplexed case was not checked")
            return
        }
        val info = AudioKodec.probe(ogg.readBytes())
        assertEquals(AudioCodec.VORBIS, info.codec, "the audio stream should be found")
        assertEquals(44_100, info.format.sampleRate)
    }

    @Test
    fun aChainedFileReadsItsFirstStreamAndSurvivesTagWriting() {
        val flac = flac ?: return skip()
        // Two Ogg files joined end to end is a legal chained stream. Reading
        // the first one is what every player does.
        val wav = temp("chain-source.wav")
        wav.writeBytes(
            AudioKodec.encode(
                TestSignals.quantise(TestSignals.sine(5_000, 1), 16),
                AudioFormat(44_100, 1),
                EncodeTarget.wav(16),
            ),
        )
        val one = temp("chain-one.oga")
        one.delete()
        run(
            listOf(flac, "--silent", "--force", "--ogg", "-5", "-o", one.absolutePath, wav.absolutePath),
            "chained",
        )
        val two = temp("chain-two.oga")
        two.delete()
        run(
            listOf(flac, "--silent", "--force", "--ogg", "-8", "-o", two.absolutePath, wav.absolutePath),
            "second chained stream",
        )
        val chained = temp("chained.oga")
        val secondBytes = two.readBytes()
        chained.writeBytes(one.readBytes() + secondBytes)

        val info = AudioKodec.probe(chained.readBytes())
        assertEquals(AudioCodec.FLAC, info.codec)
        val decoded = AudioKodec.decode(chained.readBytes())
        // The first stream's declared length is what bounds the decode.
        assertEquals(5_000, decoded.frames)

        val rewritten = AudioKodec.writeTags(
            chained.readBytes(),
            AudioKodec.readTags(chained.readBytes()).edit { set(TagField.TITLE, "Chained") },
        )
        assertTrue(
            rewritten.copyOfRange(rewritten.size - secondBytes.size, rewritten.size)
                .contentEquals(secondBytes),
            "the later chained stream changed or disappeared",
        )
        assertEquals("Chained", AudioKodec.readTags(rewritten)[TagField.TITLE])
    }

    @Test
    fun aDamagedPageIsReportedRatherThanDecoded() {
        val flac = flac ?: return skip()
        val wav = temp("damage-source.wav")
        wav.writeBytes(
            AudioKodec.encode(
                TestSignals.quantise(TestSignals.sine(12_000, 1), 16),
                AudioFormat(44_100, 1),
                EncodeTarget.wav(16),
            ),
        )
        val ogg = temp("damaged.oga")
        ogg.delete()
        run(
            listOf(flac, "--silent", "--force", "--ogg", "-5", "-o", ogg.absolutePath, wav.absolutePath),
            "damage",
        )
        val bytes = ogg.readBytes()
        val info = AudioKodec.probe(bytes)
        val at = (info.dataOffset + 400).toInt()
        bytes[at] = (bytes[at].toInt() xor 0x55).toByte()

        // The page checksum covers the payload, so a flipped bit in the audio
        // is caught by the container before the codec ever sees it.
        var failed = false
        try {
            AudioKodec.decode(bytes)
        } catch (_: AudioKodecException) {
            failed = true
        }
        assertTrue(failed, "a damaged page should be reported")
    }

    @Test
    fun tagsCanBeRewrittenAndTheAudioStillDecodes() {
        val flac = flac ?: return skip()
        val frames = 12_000
        val original = TestSignals.quantise(TestSignals.sine(frames, 2), 16)
        val wav = temp("ogg-edit-source.wav")
        wav.writeBytes(AudioKodec.encode(original, AudioFormat(44_100, 2), EncodeTarget.wav(16)))
        val ogg = temp("ogg-edit.oga")
        ogg.delete()
        run(
            listOf(
                flac, "--silent", "--force", "--ogg", "-5",
                "--tag=TITLE=Before", "-o", ogg.absolutePath, wav.absolutePath,
            ),
            "editing",
        )

        val edited = AudioKodec.writeTags(
            ogg.readBytes(),
            AudioKodec.readTags(ogg.readBytes()).edit {
                set(TagField.TITLE, "After, which is a great deal longer than the title before it")
                set(TagField.ARTIST, "Someone")
                set(TagField.ALBUM, "An album added later")
            },
        )
        val out = temp("ogg-edited.oga")
        out.writeBytes(edited)

        // The container has to still be valid: every page checksum, every
        // sequence number, and the audio unchanged.
        val tags = AudioKodec.readTags(edited)
        assertEquals(
            "After, which is a great deal longer than the title before it",
            tags[TagField.TITLE],
        )
        assertEquals("Someone", tags[TagField.ARTIST])
        assertEquals("An album added later", tags[TagField.ALBUM])

        val decoded = AudioKodec.decode(edited)
        assertEquals(frames, decoded.frames, "the audio length changed")
        TestSignals.assertExact(original, decoded.samples, "audio changed while tagging")

        // And the reference decoder agrees the file is still well formed.
        run(listOf(flac, "--silent", "--test", out.absolutePath), "the edited file")
    }

    @Test
    fun aCommentPacketLargerThanOneOggPageIsRepaginatedCorrectly() {
        val flac = flac ?: return skip()
        val frames = 4_000
        val original = TestSignals.quantise(TestSignals.sine(frames, 1), 16)
        val wav = temp("ogg-large-tag-source.wav")
        wav.writeBytes(
            AudioKodec.encode(original, AudioFormat(44_100, 1), EncodeTarget.wav(16)),
        )
        val source = temp("ogg-large-tag-source.oga")
        source.delete()
        run(
            listOf(
                flac, "--silent", "--force", "--ogg", "-5",
                "-o", source.absolutePath, wav.absolutePath,
            ),
            "large-tag source",
        )

        val longComment = "x".repeat(100_000)
        val edited = AudioKodec.writeTags(
            source.readBytes(),
            AudioKodec.readTags(source.readBytes()).edit {
                set(TagField.COMMENT, longComment)
            },
        )
        assertEquals(longComment, AudioKodec.readTags(edited)[TagField.COMMENT])
        TestSignals.assertExact(original, AudioKodec.decode(edited).samples, "large Ogg comment")

        val out = temp("ogg-large-tag-edited.oga")
        out.writeBytes(edited)
        run(listOf(flac, "--silent", "--test", out.absolutePath), "large repaginated comment")
    }

    @Test
    fun rewritingOggFlacPreservesAFinalCommentMetadataBit() {
        val flac = flac ?: return skip()
        val wav = temp("ogg-final-comment-source.wav")
        wav.writeBytes(
            AudioKodec.encode(
                TestSignals.quantise(TestSignals.sine(2_000, 1), 16),
                AudioFormat(44_100, 1),
                EncodeTarget.wav(16),
            ),
        )
        val source = temp("ogg-final-comment-source.oga")
        source.delete()
        run(
            listOf(
                flac, "--silent", "--force", "--ogg", "--no-padding", "--no-seektable",
                "--tag=TITLE=Before", "-o", source.absolutePath, wav.absolutePath,
            ),
            "final-comment source",
        )
        assertTrue(commentPacketIsFinal(source.readBytes()), "the source comment was not final")

        val edited = AudioKodec.writeTags(
            source.readBytes(),
            AudioKodec.readTags(source.readBytes()).edit { set(TagField.TITLE, "After") },
        )
        assertTrue(commentPacketIsFinal(edited), "rewriting cleared the final metadata bit")
        val out = temp("ogg-final-comment-edited.oga")
        out.writeBytes(edited)
        run(listOf(flac, "--silent", "--test", out.absolutePath), "final-comment edit")
    }

    @Test
    fun rewritingTheSameTagsTwiceIsStable() {
        val flac = flac ?: return skip()
        val wav = temp("ogg-stable-source.wav")
        wav.writeBytes(
            AudioKodec.encode(
                TestSignals.quantise(TestSignals.sine(6_000, 1), 16),
                AudioFormat(44_100, 1),
                EncodeTarget.wav(16),
            ),
        )
        val ogg = temp("ogg-stable.oga")
        ogg.delete()
        run(
            listOf(flac, "--silent", "--force", "--ogg", "-5", "-o", ogg.absolutePath, wav.absolutePath),
            "stability",
        )
        var current = AudioKodec.writeTags(
            ogg.readBytes(),
            AudioKodec.readTags(ogg.readBytes()).edit { set(TagField.TITLE, "Stable") },
        )
        val size = current.size
        repeat(3) {
            current = AudioKodec.writeTags(current, AudioKodec.readTags(current))
        }
        assertEquals(size, current.size, "rewriting the same tags should not change the size")
        assertEquals("Stable", AudioKodec.readTags(current)[TagField.TITLE])
        assertEquals(6_000, AudioKodec.decode(current).frames)
    }

    // Running the tools

    private fun run(command: List<String>, what: String) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(180, TimeUnit.SECONDS)
        assertTrue(finished, "${command.first()} did not finish within three minutes")
        assertEquals(
            0, process.exitValue(),
            "$what: ${command.joinToString(" ")} failed:\n$output",
        )
    }

    /** Runs a command that is allowed to fail, for optional encoders. */
    private fun tryRun(command: List<String>): Boolean {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText()
        if (!process.waitFor(180, TimeUnit.SECONDS)) return false
        return process.exitValue() == 0
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private fun commentPacketIsFinal(bytes: ByteArray): Boolean {
        val options = DecodeOptions.DEFAULT
        val source = bytes.asAudioSource()
        val reader = SourceReader(source, options)
        val ogg = OggReader(reader, options)
        val first = ogg.pageAt(0) ?: return false
        val packets = ogg.packets(first.serialNumber, 0)
        packets.next() ?: return false
        val comment = packets.next() ?: return false
        return comment.length > 0 && comment.bytes[0].toInt() and 0x80 != 0
    }

    private fun skip() {
        org.junit.Assume.assumeTrue("the Ogg oracle tool is not on the path, so the comparison did not run", false)
    }

    private companion object {
        val workDirectory: File = File(
            System.getProperty("java.io.tmpdir"), "audiokodec-ogg-oracle",
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

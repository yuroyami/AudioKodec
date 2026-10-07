package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.mp4.Mp4Reader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks the MP4 reader on every target, against files assembled here box by
 * box rather than written by AudioKodec, so that the reader is not only
 * agreeing with itself.
 *
 * The audio is uncompressed 16-bit QuickTime `sowt`, which decodes to exactly
 * the integers that went in, so the reader's placing of every packet can be
 * checked to the sample: with the `moov` box before the audio and after it,
 * with chunks separated by unrelated bytes, with an edit list, and as a
 * fragmented file. The oracle suite checks real encoders' files against
 * ffmpeg on the JVM.
 */
class Mp4Test {

    @Test
    fun sampleEntryChannelBoundsAreCheckedBeforeConstructingLayouts() {
        for (format in listOf("sowt", "mp4a")) {
            for (channels in intArrayOf(0, 65, 4_096, 4_097, 65_535)) {
                val file = build(3, 1, 3, format = format, sampleEntryChannels = channels)
                for (tolerant in listOf(false, true)) {
                    val options = DecodeOptions(tolerant = tolerant)
                    val operations = listOf<() -> Unit>(
                        { AudioKodec.probe(file, options) },
                        { AudioKodec.open(file, options).close() },
                        { AudioKodec.decode(file, options) },
                    )
                    for (operation in operations) {
                        val failure = assertFailsWith<CorruptStreamException>("$format: $channels channels", operation)
                        assertTrue(format in failure.message.orEmpty(), failure.message)
                        assertTrue(failure.offset >= 0)
                    }
                }
            }
        }
        for (channels in intArrayOf(1, 2, 64, 4_096)) {
            val file = build(3, channels, 3)
            val options = DecodeOptions(maxChannels = maxOf(64, channels))
            assertEquals(channels, AudioKodec.probe(file, options).format.channels)
            assertEquals(3, AudioKodec.decode(file, options).frames)
        }
    }

    @Test
    fun codecConfigurationCanSupplyTheResolvedStreamShape() {
        // AAC LC, 8 kHz, stereo, inside the ES and decoder configuration descriptors.
        val aac = fullBox("esds", 0, 0, byteArrayOf(3, 25, 0, 1, 0, 4, 17, 0x40, 0x15), ByteArray(11), byteArrayOf(5, 2, 0x15, 0x90.toByte(), 6, 1, 2))
        val alac = fullBox("alac", 0, 0, u32(64), byteArrayOf(0, 16, 40, 10, 14, 2), u16(255), u32(0), u32(0), u32(RATE))
        for ((format, configuration) in listOf("mp4a" to aac, "alac" to alac)) {
            val file = build(3, 2, 3, format = format, sampleEntryChannels = 0, sampleEntryRate = 0, sampleEntryBoxes = configuration)
            val info = AudioKodec.probe(file)
            assertEquals(2, info.format.channels)
            assertEquals(RATE, info.format.sampleRate)
            assertFailsWith<CorruptStreamException> { AudioKodec.probe(file, DecodeOptions(maxChannels = 1)) }
            assertFailsWith<CorruptStreamException> { AudioKodec.probe(file, DecodeOptions(maxSampleRate = RATE - 1)) }
        }
    }

    @Test
    fun unsignedEditDurationsAndConversionOverflowStayWithinTheErrorContract() {
        val durations = longArrayOf(0, 1, 1L shl 31, 0xFFFF_FFFFL, 1L shl 32, Long.MAX_VALUE, Long.MIN_VALUE, -1)
        for (version in 0..1) {
            for (duration in durations) {
                if (version == 0 && duration !in 0..0xFFFF_FFFFL) continue
                val file = build(16, 1, 16, edit = 0L to duration, editVersion = version)
                for (tolerant in listOf(false, true)) {
                    val options = DecodeOptions(tolerant = tolerant)
                    if (duration < 0 || duration == Long.MAX_VALUE) {
                        for (operation in listOf<() -> Unit>(
                            { AudioKodec.probe(file, options) },
                            { AudioKodec.open(file, options).close() },
                            { AudioKodec.decode(file, options) },
                        )) {
                            val failure = assertFailsWith<CorruptStreamException>("elst v$version duration $duration", operation)
                            assertTrue("elst" in failure.message.orEmpty())
                            assertTrue(failure.offset >= 0)
                        }
                    } else {
                        val frames = if (duration == 0L) 16L else minOf(16L, duration * 8)
                        assertEquals(frames, AudioKodec.probe(file, options).durationFrames)
                        assertEquals(frames.toInt(), AudioKodec.decode(file, options).frames)
                    }
                }
            }
        }
        // A large duration is valid when its conversion and end both fit.
        val representable = build(16, 1, 16, edit = 0L to Long.MAX_VALUE, editVersion = 1, movieTimescale = RATE)
        assertEquals(16L, AudioKodec.probe(representable).durationFrames)
        assertEquals(16, AudioKodec.decode(representable).frames)
        // A positive edit shorter than one sample does not use the zero sentinel.
        val subFrame = build(16, 1, 16, edit = 0L to 1L, editVersion = 1, movieTimescale = Int.MAX_VALUE)
        assertEquals(0L, AudioKodec.probe(subFrame).durationFrames)
        assertEquals(0, AudioKodec.decode(subFrame).frames)
        val overflowingEnd = build(16, 1, 16, edit = Long.MAX_VALUE to 1L, editVersion = 1, movieTimescale = RATE)
        for (tolerant in listOf(false, true)) {
            val failure = assertFailsWith<CorruptStreamException> { AudioKodec.probe(overflowingEnd, DecodeOptions(tolerant = tolerant)) }
            assertTrue("elst" in failure.message.orEmpty())
        }
    }

    @Test
    fun timeConversionKeepsLargeFractionalProductsExact() {
        // The intermediate products exceed signed 64-bit range, but the results fit.
        assertEquals(4_294_967_293L, Mp4Reader.rescale(4_294_967_293L, 4_294_967_295L, 4_294_967_294L))
        assertEquals(4_294_967_296L, Mp4Reader.rescale(4_294_967_295L, 4_294_967_295L, 4_294_967_294L))
        assertEquals(Long.MAX_VALUE, Mp4Reader.rescale(Long.MAX_VALUE, 8_000, 8_000))
        assertFailsWith<CorruptStreamException> { Mp4Reader.rescale(Long.MAX_VALUE, 8_000, 1_000) }
    }

    @Test
    fun opusCodedChannelCountsAreValidatedWhileReadingDops() {
        for ((streams, coupled) in listOf(128 to 127, 255 to 0, 128 to 128, 200 to 100, 255 to 255)) {
            val dops = box("dOps", byteArrayOf(0, 2), u16(0), u32(48_000), u16(0), byteArrayOf(255.toByte(), streams.toByte(), coupled.toByte(), 0, 1))
            val file = build(3, 2, 3, format = "Opus", sampleEntryBoxes = dops)
            for (tolerant in listOf(false, true)) {
                val options = DecodeOptions(tolerant = tolerant)
                if (streams + coupled <= 255) {
                    assertEquals(2, AudioKodec.probe(file, options).format.channels)
                    AudioKodec.open(file, options).close()
                } else {
                    for (operation in listOf<() -> Unit>(
                        { AudioKodec.probe(file, options) },
                        { AudioKodec.open(file, options).close() },
                        { AudioKodec.decode(file, options) },
                    )) {
                        val failure = assertFailsWith<CorruptStreamException>("dOps $streams/$coupled", operation)
                        assertTrue("dOps" in failure.message.orEmpty())
                    }
                }
            }
        }
    }

    @Test
    fun aZeroMovieTimescaleIsRejectedBeforeAnyEditArithmetic() {
        for (version in 0..1) {
            for (edit in listOf(0L to 125L, null)) {
                for (tolerant in listOf(false, true)) {
                    val options = DecodeOptions(tolerant = tolerant)
                    val valid = build(1_000, 1, 256, edit = edit, movieHeaderVersion = version)
                    assertEquals(1_000L, AudioKodec.probe(valid, options).durationFrames)
                    AudioKodec.open(valid, options).use { assertEquals(1_000L, it.durationFrames) }
                    assertEquals(1_000, AudioKodec.decode(valid, options).frames)

                    val damaged = build(1_000, 1, 256, edit = edit, movieTimescale = 0, movieHeaderVersion = version)
                    val operations = listOf<() -> Unit>(
                        { AudioKodec.probe(damaged, options) },
                        { AudioKodec.open(damaged, options).close() },
                        { AudioKodec.decode(damaged, options) },
                    )
                    for (operation in operations) {
                        val failure = assertFailsWith<CorruptStreamException>("mvhd v$version, edit=$edit, tolerant=$tolerant", operation)
                        assertTrue("mvhd" in failure.message.orEmpty())
                        assertTrue(failure.offset >= 0)
                    }
                }
            }
        }
    }

    @Test
    fun pcmPacketAndChannelScratchAllocationsAreGuardedBeforeConstruction() {
        val options = DecodeOptions(maxAllocationBytes = 4_096)
        for (frames in intArrayOf(750, 1_500)) {
            for (channelTag in listOf(null, (101 shl 16) or 2)) {
                val file = build(frames, 2, frames, channelTag = channelTag, singlePacket = true)
                if (frames == 750 && channelTag == null) {
                    AudioKodec.open(file, options).use { assertEquals(frames.toLong(), it.durationFrames) }
                } else {
                    val failure = assertFailsWith<AllocationGuardException>("$frames frames, chan=$channelTag") {
                        AudioKodec.open(file, options).close()
                    }
                    // The input packet is checked before any codec state. When
                    // it fits, the expanded channel scratch still needs a guard.
                    assertEquals(6_000L, failure.requested)
                    assertEquals(4_096L, failure.limit)
                }
                AudioKodec.open(file).use { assertEquals(frames.toLong(), it.durationFrames) }
            }
        }
    }

    @Test
    fun theSamplesComeBackWhereverTheIndexSits() {
        for (channels in intArrayOf(1, 2)) {
            for (moovFirst in listOf(true, false)) {
                for (gap in intArrayOf(0, 13)) {
                    val what = "$channels ch, moov ${if (moovFirst) "first" else "last"}, gap $gap"
                    val file = build(frames = 3_001, channels = channels, chunkFrames = 400, moovFirst = moovFirst, gap = gap)
                    val info = AudioKodec.probe(file)
                    assertEquals(AudioContainer.MP4, info.container, "$what: container")
                    assertEquals(AudioCodec.PCM_INTEGER, info.codec, "$what: codec")
                    assertEquals(RATE, info.format.sampleRate, "$what: rate")
                    assertEquals(channels, info.format.channels, "$what: channels")
                    assertEquals(3_001L, info.durationFrames, "$what: length")
                    assertEquals(16, info.bitDepth, "$what: bit depth")
                    assertSamples(file, channels, 0, 3_001, what)
                }
            }
        }
    }

    @Test
    fun anEditListChoosesWhichPartPlays() {
        // Start 250 frames in and play 2000, stated in the movie's
        // millisecond timescale as 2000 / 8 = 250 ms.
        val file = build(frames = 3_001, channels = 2, chunkFrames = 512, edit = 250L to 250L)
        assertEquals(2_000L, AudioKodec.probe(file).durationFrames, "length after the edit")
        assertSamples(file, 2, 250, 2_000, "edited")
    }

    @Test
    fun anEditListLongerThanTheMediaStopsAtTheMedia() {
        val file = build(frames = 1_000, channels = 1, chunkFrames = 300, edit = 0L to 900L)
        assertEquals(1_000L, AudioKodec.probe(file).durationFrames)
        assertSamples(file, 1, 0, 1_000, "an overlong edit")
    }

    @Test
    fun aFragmentedFileIsReadFromItsFragments() {
        val file = buildFragmented(frames = 2_500, channels = 2, fragmentFrames = 700)
        assertEquals(2_500L, AudioKodec.probe(file).durationFrames, "length")
        assertSamples(file, 2, 0, 2_500, "fragmented")
    }

    @Test
    fun seekingLandsOnTheSampleAsked() {
        val file = build(frames = 3_001, channels = 2, chunkFrames = 400, gap = 5)
        AudioKodec.open(file).use { decoder ->
            val ints = decoder as IntPcmAccess
            for (target in longArrayOf(0, 1, 399, 400, 401, 2_999, 3_000, 3_001)) {
                assertEquals(target, decoder.seek(target), "seek to $target")
                val frame = ints.nextIntFrame()
                if (target == 3_001L) {
                    assertEquals(null, frame, "nothing after the end")
                    continue
                }
                assertNotNull(frame, "a frame after seeking to $target")
                assertEquals(target, frame.positionFrames, "position after seeking to $target")
                for (i in 0 until frame.frameCount) {
                    for (c in 0 until 2) assertEquals(sample(target.toInt() + i, c), frame.samples[i * 2 + c], "frame ${target + i}, channel $c")
                }
            }
        }
    }

    @Test
    fun neroChaptersAreRead() {
        val chapters = listOf(0L to "Opening", 120L to "Middle", 250L to "")
        val file = build(frames = 3_001, channels = 1, chunkFrames = 1_000, chapters = chapters)
        val read = AudioKodec.readTags(file).chapters
        assertEquals(listOf(0L, 120L, 250L), read.map { it.startMillis })
        assertEquals(listOf(120L, 250L, -1L), read.map { it.endMillis })
        assertEquals(listOf("Opening", "Middle", null), read.map { it.title })
    }

    @Test
    fun aCodingWithoutADecoderIsNamedAndRefused() {
        val file = build(frames = 1_024, channels = 2, chunkFrames = 1_024, format = "mp4a")
        val info = AudioKodec.probe(file)
        assertEquals(AudioCodec.AAC, info.codec)
        val refusal = assertFailsWith<UnsupportedFormatException> { AudioKodec.open(file) }
        assertTrue("AAC" in refusal.message.orEmpty(), "the refusal names the coding: ${refusal.message}")
    }

    @Test
    fun aFileWithoutItsIndexSaysSo() {
        val file = box("ftyp", ascii("M4A "), u32(0)) + box("mdat", ByteArray(64))
        assertFailsWith<TruncatedStreamException> { AudioKodec.probe(file) }
    }

    @Test
    fun truncatedAndMutatedFilesFailOnlyWithTheLibrarysOwnExceptions() {
        for (file in listOf(
            build(frames = 600, channels = 2, chunkFrames = 128, moovFirst = true, gap = 3, chapters = listOf(0L to "A", 40L to "B")),
            build(frames = 600, channels = 2, chunkFrames = 128, moovFirst = false, edit = 10L to 30L),
            buildFragmented(frames = 600, channels = 2, fragmentFrames = 200),
        )) {
            for (keep in 0 until file.size step 5) {
                val cut = file.copyOf(keep)
                expectOnlyOwnFailures("truncated to $keep") { AudioKodec.decode(cut) }
                expectOnlyOwnFailures("truncated to $keep, tolerant") { AudioKodec.decode(cut, DecodeOptions(tolerant = true)) }
                expectOnlyOwnFailures("probing $keep") { AudioKodec.probe(cut) }
                expectOnlyOwnFailures("tags of $keep") { AudioKodec.readTags(cut) }
            }
            var state = 17
            repeat(1_500) {
                state = state * 1_103_515_245 + 12_345
                val at = ((state ushr 8) and 0x7FFF_FFFF) % file.size
                state = state * 1_103_515_245 + 12_345
                val mutated = file.copyOf().also { it[at] = (state ushr 16).toByte() }
                expectOnlyOwnFailures("byte $at changed") { AudioKodec.decode(mutated, DecodeOptions(tolerant = true)) }
                expectOnlyOwnFailures("tags with byte $at changed") { AudioKodec.readTags(mutated) }
            }
        }
    }

    // Checking.

    private fun sample(frame: Int, channel: Int): Int = ((frame * 37 + channel * 1_001) % 30_001) - 15_000

    private fun assertSamples(file: ByteArray, channels: Int, firstFrame: Int, frames: Int, what: String) {
        AudioKodec.open(file).use { decoder ->
            val ints = decoder as IntPcmAccess
            var at = 0
            while (true) {
                val frame = ints.nextIntFrame() ?: break
                assertEquals(at.toLong(), frame.positionFrames, "$what: position")
                for (i in 0 until frame.frameCount) {
                    for (c in 0 until channels) {
                        assertEquals(sample(firstFrame + at + i, c), frame.samples[i * channels + c], "$what: frame ${at + i}, channel $c")
                    }
                }
                at += frame.frameCount
            }
            assertEquals(frames, at, "$what: frames decoded")
        }
    }

    private inline fun expectOnlyOwnFailures(what: String, block: () -> Unit) {
        try {
            block()
        } catch (_: AudioKodecException) {
            // Every failure has to be one of the library's own.
        } catch (e: Throwable) {
            throw AssertionError("$what threw ${e::class.simpleName}: ${e.message}", e)
        }
    }

    // Building files.

    private fun pcm(from: Int, frames: Int, channels: Int): ByteArray {
        val out = ByteArray(frames * channels * 2)
        for (i in 0 until frames) {
            for (c in 0 until channels) {
                val value = sample(from + i, c)
                out[(i * channels + c) * 2] = value.toByte()
                out[(i * channels + c) * 2 + 1] = (value shr 8).toByte()
            }
        }
        return out
    }

    /**
     * A QuickTime file of [frames] frames of `sowt` audio in chunks of
     * [chunkFrames], with [gap] unrelated bytes between chunks, and the
     * `stsz` table stating one byte per frame as QuickTime's own writers do.
     */
    private fun build(
        frames: Int,
        channels: Int,
        chunkFrames: Int,
        moovFirst: Boolean = false,
        gap: Int = 0,
        edit: Pair<Long, Long>? = null,
        chapters: List<Pair<Long, String>> = emptyList(),
        format: String = "sowt",
        channelTag: Int? = null,
        singlePacket: Boolean = false,
        movieTimescale: Int = 1_000,
        movieHeaderVersion: Int = 0,
        sampleEntryChannels: Int = channels,
        sampleEntryRate: Int = RATE,
        sampleEntryBoxes: ByteArray = ByteArray(0),
        editVersion: Int = 0,
    ): ByteArray {
        val chunkCount = (frames + chunkFrames - 1) / chunkFrames
        require(!singlePacket || chunkCount == 1)
        val ftyp = box("ftyp", ascii("qt  "), u32(0), ascii("qt  "))
        fun payload(): Pair<ByteArray, IntArray> {
            val out = ArrayList<ByteArray>()
            val starts = IntArray(chunkCount)
            var size = 0
            for (k in 0 until chunkCount) {
                if (gap > 0) {
                    out += ByteArray(gap) { 0x5A }
                    size += gap
                }
                starts[k] = size
                val count = minOf(chunkFrames, frames - k * chunkFrames)
                out += pcm(k * chunkFrames, count, channels)
                size += count * channels * 2
            }
            return out.reduce { a, b -> a + b } to starts
        }
        val (data, starts) = payload()
        fun moov(dataAt: Int): ByteArray {
            val lastFrames = frames - (chunkCount - 1) * chunkFrames
            val stsc = if (lastFrames == chunkFrames || chunkCount == 1) {
                fullBox("stsc", 0, 0, u32(1), u32(1), u32(if (singlePacket) 1 else minOf(chunkFrames, frames)), u32(1))
            } else {
                fullBox("stsc", 0, 0, u32(2), u32(1), u32(chunkFrames), u32(1), u32(chunkCount), u32(lastFrames), u32(1))
            }
            val entry = box(
                format, ByteArray(6), u16(1), u16(0), u16(0), u32(0), u16(sampleEntryChannels), u16(16), u16(0), u16(0), u32(sampleEntryRate shl 16),
                channelTag?.let { fullBox("chan", 0, 0, u32(it), u32(0), u32(0)) } ?: ByteArray(0),
                sampleEntryBoxes,
            )
            val stbl = box(
                "stbl",
                fullBox("stsd", 0, 0, u32(1), entry),
                fullBox("stts", 0, 0, u32(1), u32(if (singlePacket) 1 else frames), u32(if (singlePacket) frames else 1)),
                stsc,
                if (singlePacket) fullBox("stsz", 0, 0, u32(0), u32(1), u32(data.size))
                else fullBox("stsz", 0, 0, u32(1), u32(frames)),
                fullBox("stco", 0, 0, u32(chunkCount), *starts.map { u32(dataAt + it) }.toTypedArray()),
            )
            val mdia = box(
                "mdia",
                fullBox("mdhd", 0, 0, u32(0), u32(0), u32(RATE), u32(frames), u16(0), u16(0)),
                fullBox("hdlr", 0, 0, u32(0), ascii("soun"), ByteArray(12), byteArrayOf(0)),
                box("minf", fullBox("smhd", 0, 0, u32(0)), stbl),
            )
            val edts = edit?.let { (start, duration) ->
                val timing = if (editVersion == 1) u64(duration) + u64(start) else u32(duration.toInt()) + u32(start.toInt())
                box("edts", fullBox("elst", editVersion, 0, u32(1), timing, u32(0x10000)))
            }
            val trak = box("trak", *listOfNotNull(fullBox("tkhd", 0, 7, u32(0), u32(0), u32(1), u32(0), u32(frames), ByteArray(60)), edts, mdia).toTypedArray())
            val udta = if (chapters.isEmpty()) null else box(
                "udta",
                fullBox(
                    "chpl", 1, 0, u32(0), byteArrayOf(chapters.size.toByte()),
                    *chapters.map { (millis, title) -> u64(millis * 10_000) + byteArrayOf(title.length.toByte()) + ascii(title) }.toTypedArray(),
                ),
            )
            val mvhd = if (movieHeaderVersion == 1) {
                fullBox("mvhd", 1, 0, u64(0), u64(0), u32(movieTimescale), u64(0), ByteArray(80))
            } else {
                fullBox("mvhd", 0, 0, u32(0), u32(0), u32(movieTimescale), u32(0), ByteArray(80))
            }
            return box("moov", *listOfNotNull(mvhd, trak, udta).toTypedArray())
        }
        return if (moovFirst) {
            val size = moov(0).size
            val dataAt = ftyp.size + size + 8
            ftyp + moov(dataAt) + box("mdat", data)
        } else {
            val dataAt = ftyp.size + 8
            ftyp + box("mdat", data) + moov(dataAt)
        }
    }

    /** A fragmented file: an empty `moov` with defaults in `trex`, then one `moof` and `mdat` per fragment. */
    private fun buildFragmented(frames: Int, channels: Int, fragmentFrames: Int): ByteArray {
        val frameBytes = channels * 2
        val entry = box("sowt", ByteArray(6), u16(1), u16(0), u16(0), u32(0), u16(channels), u16(16), u16(0), u16(0), u32(RATE shl 16))
        val stbl = box(
            "stbl",
            fullBox("stsd", 0, 0, u32(1), entry),
            fullBox("stts", 0, 0, u32(0)),
            fullBox("stsc", 0, 0, u32(0)),
            fullBox("stsz", 0, 0, u32(0), u32(0)),
            fullBox("stco", 0, 0, u32(0)),
        )
        val moov = box(
            "moov",
            fullBox("mvhd", 0, 0, u32(0), u32(0), u32(1000), u32(0), ByteArray(80)),
            box(
                "trak",
                fullBox("tkhd", 0, 7, u32(0), u32(0), u32(1), u32(0), u32(0), ByteArray(60)),
                box(
                    "mdia",
                    fullBox("mdhd", 0, 0, u32(0), u32(0), u32(RATE), u32(0), u16(0), u16(0)),
                    fullBox("hdlr", 0, 0, u32(0), ascii("soun"), ByteArray(12), byteArrayOf(0)),
                    box("minf", fullBox("smhd", 0, 0, u32(0)), stbl),
                ),
            ),
            box("mvex", fullBox("trex", 0, 0, u32(1), u32(1), u32(1), u32(frameBytes), u32(0))),
        )
        var out = box("ftyp", ascii("iso6"), u32(0), ascii("iso6")) + moov
        var at = 0
        var sequence = 1
        while (at < frames) {
            val count = minOf(fragmentFrames, frames - at)
            fun moof(dataOffset: Int) = box(
                "moof",
                fullBox("mfhd", 0, 0, u32(sequence)),
                box("traf", fullBox("tfhd", 0, 0x020000, u32(1)), fullBox("trun", 0, 0x1, u32(count), u32(dataOffset))),
            )
            val moofSize = moof(0).size
            out += moof(moofSize + 8) + box("mdat", pcm(at, count, channels))
            at += count
            sequence++
        }
        return out
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        return u32(body.size + 8) + ascii(type) + body
    }

    private fun fullBox(type: String, version: Int, flags: Int, vararg parts: ByteArray): ByteArray =
        box(type, byteArrayOf(version.toByte(), (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte()), *parts)

    private fun ascii(text: String): ByteArray = ByteArray(text.length) { text[it].code.toByte() }
    private fun u16(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())
    private fun u32(value: Int): ByteArray = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
    private fun u64(value: Long): ByteArray = u32((value ushr 32).toInt()) + u32(value.toInt())

    private companion object {
        const val RATE = 8_000
    }
}

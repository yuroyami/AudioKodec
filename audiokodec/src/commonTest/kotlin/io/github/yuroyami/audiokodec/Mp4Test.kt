package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.mp4.Mp4Reader
import io.github.yuroyami.audiokodec.internal.mp4.Mp4Box
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun chunkOffsetsMustKeepCompletePacketsInsideMediaData() {
        for (wide in listOf(false, true)) {
            val original = build(16, 1, 16, moovFirst = true, useCo64 = wide)
            val mdat = typeAt(original, "mdat") - 4
            val positions = listOf(0L, 8L, (typeAt(original, "moov") + 4).toLong(),
                mdat + 4L, mdat + 7L, original.size - 1L, original.size.toLong()) +
                if (wide) listOf(Long.MAX_VALUE - 1) else emptyList()
            for (position in positions) {
                val bytes = original.copyOf()
                if (wide) u64(position).copyInto(bytes, typeAt(bytes, "co64") + 12)
                else patchU32(bytes, "stco", 12, position.toInt())
                assertBadPacketRange(bytes)
            }
        }
        val original = build(16, 1, 16, moovFirst = true)
        val mdat = typeAt(original, "mdat") - 4
        val overflowing = original.copyOfRange(0, mdat) + u32(1) + ascii("mdat") + u64(Long.MAX_VALUE) + ByteArray(32)
        assertBadPacketRange(overflowing)
    }

    @Test
    fun fragmentOffsetsCannotPointAtHeadersOrCrossMediaDataEnds() {
        val original = buildFragmented(16, 1, 16)
        val moof = typeAt(original, "moof") - 4
        val mdat = typeAt(original, "mdat") - 4
        for (relative in listOf(-moof, 0, mdat - moof, mdat + 7 - moof, original.size - 1 - moof)) {
            val bytes = original.copyOf()
            patchU32(bytes, "trun", 12, relative)
            assertBadPacketRange(bytes)
        }
        val crossing = buildFragmented(32, 1, 16)
        patchU32(crossing, "trun", 8, 20)
        assertBadPacketRange(crossing)
    }

    @Test
    fun separateExtendedAndOpenEndedMediaDataKeepExactAudioAndSeeking() {
        val original = build(32, 1, 16, moovFirst = true)
        val mdat = typeAt(original, "mdat") - 4
        val stco = typeAt(original, "stco")
        val payload = original.copyOfRange(mdat + 8, original.size)
        val prefix = original.copyOfRange(0, mdat)
        val extendedPrefix = prefix.copyOf()
        for (at in listOf(stco + 12, stco + 16)) {
            u32(Mp4Box.readU32(original, at).toInt() + 8).copyInto(extendedPrefix, at)
        }
        val extended = extendedPrefix + u32(1) + ascii("mdat") + u64(payload.size + 16L) + payload
        val openEnded = original.copyOf().also { u32(0).copyInto(it, mdat) }
        val splitPrefix = prefix.copyOf()
        u32(Mp4Box.readU32(original, stco + 16).toInt() + 27).copyInto(splitPrefix, stco + 16)
        val split = splitPrefix + box("mdat", payload.copyOfRange(0, 32)) + box("free", ByteArray(11)) +
            box("mdat", payload.copyOfRange(32, payload.size))
        val expected = AudioKodec.decode(original).samples
        for (file in listOf(original, extended, openEnded, split)) for (unknown in listOf(false, true)) {
            assertEquals(32L, AudioKodec.probe(MpegTrailerFixtures.source(file, unknown)).durationFrames)
            assertContentEquals(expected, AudioKodec.decode(MpegTrailerFixtures.source(file, unknown)).samples)
            AudioKodec.open(MpegTrailerFixtures.source(file, unknown)).use { decoder ->
                assertEquals(17L, decoder.seek(17))
                val block = assertNotNull(decoder.nextFrame())
                assertContentEquals(expected.copyOfRange(17, 17 + block.frameCount), block.samples)
            }
        }
        val crossing = split.copyOf()
        patchU32(crossing, "stco", 12, mdat + 8 + 31)
        assertBadPacketRange(crossing)
    }

    @Test
    fun dataReferenceIndicesSelectLocalDataAndRefuseExternalData() {
        val local = fullBox("url ", 0, 1)
        val alias = fullBox("alis", 0, 1)
        val external = fullBox("url ", 0, 0, ascii("other.mov"), byteArrayOf(0))
        val references = fullBox("dref", 0, 0, u32(2), external, alias)
        val valid = build(16, 1, 16, dataReferences = references, dataReferenceIndex = 2)
        assertSamples(valid, 1, 0, 16, "selected local alias, unused external reference")
        assertSamples(build(16, 1, 16, dataReferences = fullBox("dref", 0, 0, u32(1), local)), 1, 0, 16, "local URL reference")
        for (tolerant in listOf(false, true)) {
            val options = DecodeOptions(tolerant = tolerant)
            val file = build(16, 1, 16, dataReferences = references)
            for (operation in listOf<() -> Unit>({ AudioKodec.probe(file, options) }, { AudioKodec.open(file, options).close() })) {
                val failure = assertFailsWith<UnsupportedFeatureException>(block = operation)
                assertTrue("external" in failure.message.orEmpty())
            }
            for (index in listOf(0, 3)) {
                assertFailsWith<CorruptStreamException> {
                    AudioKodec.probe(build(16, 1, 16, dataReferences = references, dataReferenceIndex = index), options)
                }
            }
            assertFailsWith<CorruptStreamException> { AudioKodec.probe(build(16, 1, 16, dataReferenceIndex = 2), options) }
        }
    }

    @Test
    fun packetDescriptionsMustResolveToTheSingleAudioDescription() {
        for (index in listOf(0, 2)) {
            val ordinary = build(16, 1, 16).also { patchU32(it, "stsc", 20, index) }
            val defaults = buildFragmented(16, 1, 16).also { patchU32(it, "trex", 12, index) }
            val override = buildFragmented(16, 1, 16, audioDescriptionIndex = index)
            for (file in listOf(ordinary, defaults, override)) for (tolerant in listOf(false, true)) {
                assertFailsWith<CorruptStreamException> { AudioKodec.probe(file, DecodeOptions(tolerant = tolerant)) }
            }
        }
        assertSamples(buildFragmented(16, 1, 16, audioDescriptionIndex = 1), 1, 0, 16, "valid fragment description override")
    }

    @Test
    fun mediaDataRegionIndexRespectsTheAllocationLimit() {
        val extra = ByteArray(200 * 8)
        repeat(200) { box("mdat").copyInto(extra, it * 8) }
        val file = build(16, 1, 16, moovFirst = true) + extra
        assertFailsWith<AllocationGuardException> { AudioKodec.probe(file, DecodeOptions(maxAllocationBytes = 1_024)) }
    }

    @Test
    fun mediaDataOwnershipChecksRetainTruncatedRecordingRecovery() {
        for (fragmented in listOf(false, true)) {
            val original = if (fragmented) buildFragmented(40_000, 1, 16_384) else build(40_000, 1, 16_384, moovFirst = true)
            val mdat = (0..original.size - 4).last { at -> (0..3).all { k -> original[at + k] == "mdat"[k].code.toByte() } } - 4
            val cutAt = mdat + 8 + if (fragmented) 100 else 65_536 + 100
            val expected = AudioKodec.decode(original).samples.copyOf(32_768)
            for (openEnded in listOf(false, true)) {
                val cut = original.copyOf(cutAt)
                if (openEnded) u32(0).copyInto(cut, mdat)
                for (unknown in listOf(false, true)) {
                    assertEquals(40_000L, AudioKodec.probe(MpegTrailerFixtures.source(cut, unknown)).durationFrames)
                    assertFailsWith<TruncatedStreamException> { AudioKodec.decode(MpegTrailerFixtures.source(cut, unknown)) }
                    assertContentEquals(expected, AudioKodec.decode(MpegTrailerFixtures.source(cut, unknown), DecodeOptions(tolerant = true)).samples)
                }
            }
        }
    }

    private fun typeAt(bytes: ByteArray, type: String): Int =
        (0..bytes.size - 4).first { at -> (0..3).all { k -> bytes[at + k] == type[k].code.toByte() } }

    private fun assertBadPacketRange(file: ByteArray) {
        for (unknown in listOf(false, true)) for (tolerant in listOf(false, true)) {
            val options = DecodeOptions(tolerant = tolerant)
            for (operation in listOf<() -> Unit>(
                { AudioKodec.probe(MpegTrailerFixtures.source(file, unknown), options) },
                { AudioKodec.open(MpegTrailerFixtures.source(file, unknown), options).close() },
                { AudioKodec.decode(MpegTrailerFixtures.source(file, unknown), options) },
            )) assertFailsWith<CorruptStreamException>(block = operation)
        }
    }

    @Test
    fun unbrandedQuickTimeFilesReachTheDecoderWithEitherMoviePosition() {
        for (moovFirst in listOf(false, true)) {
            val file = build(32, 2, 16, moovFirst = moovFirst)
            ascii("free").copyInto(file, 4)
            assertEquals(AudioContainer.MP4, AudioKodec.sniff(file))
            assertEquals(32L, AudioKodec.probe(file).durationFrames)
            assertSamples(file, 2, 0, 32, "unbranded QuickTime")
        }
    }

    @Test
    fun sourceDetectionJumpsPastLeadingPaddingWithoutReadingItsWholePayload() {
        val prefix = box("free", ByteArray(70_000))
        val file = build(32, 1, 16, leadingBoxes = prefix)
        assertEquals(AudioContainer.MP4, AudioKodec.sniff(file))
        assertEquals(null, AudioKodec.sniff(file.copyOf(64)))
        var transferred = 0
        val inner = file.asAudioSource()
        val source = object : AudioSource {
            override val size: Long get() = inner.size
            override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int =
                inner.readAt(position, into, offset, count).also { if (it > 0) transferred += it }
            override fun close() = Unit
        }
        assertEquals(32L, AudioKodec.probe(source, DecodeOptions(maxAllocationBytes = 4096)).durationFrames)
        assertTrue(transferred < 16_384, "probing transferred $transferred bytes of leading padding")
        assertSamples(file, 1, 0, 32, "QuickTime after padding")
    }

    @Test
    fun pcmReadPacketBoundariesDoNotMakeTheBitrateVariable() {
        for (channels in 1..2) {
            val files = listOf(
                build(32_768 / channels, channels, 32_768 / channels),
                build(40_000, channels, 40_000),
                build(40_000, channels, 9_000, gap = 13),
                buildFragmented(40_000, channels, 9_000),
            )
            for ((case, file) in files.withIndex()) {
                val info = AudioKodec.probe(file)
                assertFalse(info.isVariableBitrate, "$channels channels, case $case")
                assertEquals(RATE * channels * 16, info.bitrate)
                AudioKodec.open(file).use { assertFalse(it.info.isVariableBitrate) }
            }
            val parsed = Mp4Reader.parse(SourceReader(files[1].asAudioSource(), DecodeOptions.DEFAULT), DecodeOptions.DEFAULT)
            assertTrue(parsed.audio.sizes.any { it != parsed.audio.sizes[0] }, "the regression needs a shorter read packet")
        }
    }

    @Test
    fun compressedTracksCanStillHaveVariablePacketSizes() {
        for ((sizes, variable) in listOf(intArrayOf(2, 2) to false, intArrayOf(1, 3) to true)) {
            val file = build(2, 1, 2, format = "mp4a", sampleSizes = sizes)
            val info = AudioKodec.probe(file)
            assertEquals(AudioCodec.AAC, info.codec)
            assertEquals(variable, info.isVariableBitrate)
        }
    }

    @Test
    fun pcmIndexMemoryTracksCoalescedPackets() {
        val options = DecodeOptions(maxAllocationBytes = 1L shl 20)
        val files = listOf(
            build(240_000, 1, 240_000) to 8,
            build(240_000, 1, 50_000, gap = 13) to 10,
            buildFragmented(240_000, 1, 60_000) to 8,
        )
        for ((file, packets) in files) {
            val parsed = Mp4Reader.parse(SourceReader(file.asAudioSource(), options), options)
            assertEquals(packets, parsed.audio.sizes.size)
            assertTrue(parsed.audio.sizes.all { it in 1..65_536 })
            assertEquals(480_000L, parsed.audioBytes)
            assertEquals(240_000L, parsed.audio.times.last())
            assertEquals(240_000L, AudioKodec.probe(file, options).durationFrames)
            assertSamples(file, 1, 0, 240_000, "$packets coalesced packets", options)
            AudioKodec.open(file, options).use { decoder ->
                for (target in longArrayOf(0, 32_767, 32_768, 49_999, 50_000, 59_999, 60_000, 239_999)) {
                    assertEquals(target, decoder.seek(target))
                    val frame = (decoder as IntPcmAccess).nextIntFrame()
                    assertNotNull(frame)
                    assertEquals(target, frame.positionFrames)
                    assertEquals(sample(target.toInt(), 0), frame.samples[0])
                }
            }
        }
    }

    @Test
    fun pcmRunAggregationPreservesTimingChanges() {
        val file = build(240_000, 1, 50_000, gap = 13, timeRuns = listOf(0 to 99, 10_000 to 1, 10_000 to 2, 220_000 to 1))
        val options = DecodeOptions(maxAllocationBytes = 1L shl 20)
        val parsed = Mp4Reader.parse(SourceReader(file.asAudioSource(), options), options)
        assertEquals(10, parsed.audio.sizes.size)
        assertEquals(250_000L, parsed.audio.times.last())
        assertEquals(480_000L, parsed.audioBytes)
        val shortTimes = build(240_000, 1, 240_000, timeRuns = listOf(20_000 to 2))
        assertFailsWith<CorruptStreamException> { AudioKodec.probe(shortTimes, options) }
        assertEquals(480_000L, AudioKodec.probe(shortTimes, options.copy(tolerant = true)).durationFrames)
    }

    @Test
    fun pcmIndexGrowthRespectsTheAllocationLimit() {
        val options = DecodeOptions(maxAllocationBytes = 1_024)
        val fits = build(51, 1, 1, gap = 1)
        assertEquals(51L, AudioKodec.probe(fits, options).durationFrames)
        assertSamples(fits, 1, 0, 51, "51 packets at the index limit", options)
        val failure = assertFailsWith<AllocationGuardException> { AudioKodec.probe(build(100, 1, 1, gap = 1), options) }
        assertTrue("indexing" in failure.message.orEmpty())
        assertEquals(1_040L, failure.requested)
        assertEquals(1_024L, failure.limit)
    }

    @Test
    fun largeConstantPcmClaimsDoNotRequirePerFrameWork() {
        val ordinary = build(5, 1, 5, moovFirst = true)
        patchU32(ordinary, "stsz", 12, Int.MAX_VALUE)
        patchU32(ordinary, "stsc", 16, Int.MAX_VALUE)
        patchU32(ordinary, "stts", 12, Int.MAX_VALUE)
        val fragmented = buildFragmented(5, 1, 5)
        patchU32(fragmented, "trun", 8, Int.MAX_VALUE)
        val zeroSize = fragmented.copyOf()
        patchU32(zeroSize, "trex", 20, 0)
        val options = DecodeOptions(maxAllocationBytes = 2L shl 20)
        val sources = listOf(sparseMediaData(ordinary, Int.MAX_VALUE * 2L),
            sparseMediaData(fragmented, Int.MAX_VALUE * 2L), zeroSize.asAudioSource())
        for ((i, source) in sources.withIndex()) {
            val parsed = Mp4Reader.parse(SourceReader(source, options), options)
            assertEquals(Int.MAX_VALUE.toLong(), parsed.audio.times.last())
            assertEquals(if (i == 2) 1 else 65_536, parsed.audio.sizes.size)
            assertEquals(Int.MAX_VALUE.toLong(), AudioKodec.probe(source, options).durationFrames)
        }
        val overflowingTime = buildFragmented(10, 1, 5)
        patchU32(overflowingTime, "trun", 8, Int.MAX_VALUE)
        patchU32(overflowingTime, "trex", 20, 0)
        patchU32(overflowingTime, "trex", 16, -1)
        assertFailsWith<CorruptStreamException> { AudioKodec.probe(overflowingTime, options) }
    }

    /** A virtual zero-filled payload keeps multi-gigabyte indexing tests bounded. */
    private fun sparseMediaData(file: ByteArray, payloadBytes: Long): AudioSource {
        val mdat = typeAt(file, "mdat") - 4
        val shifted = file.copyOf()
        val fragment = (0..file.size - 4).any { at -> (0..3).all { k -> file[at + k] == "trun"[k].code.toByte() } }
        val type = if (fragment) "trun" else "stco"
        val value = Mp4Box.readU32(file, typeAt(file, type) + 12).toInt()
        patchU32(shifted, type, 12, value + 8)
        val prefix = shifted.copyOfRange(0, mdat) + u32(1) + ascii("mdat") + u64(payloadBytes + 16) +
            file.copyOfRange(mdat + 8, file.size)
        return object : AudioSource {
            override val size: Long = mdat + 16L + payloadBytes
            override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int {
                if (count == 0) return 0
                if (position >= size) return -1
                val got = minOf(count.toLong(), size - position).toInt()
                into.fill(0, offset, offset + got)
                if (position < prefix.size) {
                    val copied = minOf(got.toLong(), prefix.size - position).toInt()
                    prefix.copyInto(into, offset, position.toInt(), position.toInt() + copied)
                }
                return got
            }
            override fun close() = Unit
        }
    }

    @Test
    fun fragmentDefaultsMustResolveToExactlyOneTrack() {
        val missing = buildFragmented(5, 1, 5) { dataOffset ->
            box("traf", fullBox("tfhd", 0, 0x020000, u32(3)), fullBox("trun", 0, 1, u32(0), u32(dataOffset)))
        }
        val duplicate = buildFragmented(5, 1, 5) { dataOffset ->
            box("traf", fullBox("tfhd", 0, 0x020000, u32(2)), fullBox("trun", 0, 1, u32(0), u32(dataOffset)))
        }
        val trex = (0..duplicate.size - 4).filter { at -> (0..3).all { k -> duplicate[at + k] == "trex"[k].code.toByte() } }
        assertEquals(2, trex.size)
        u32(1).copyInto(duplicate, trex.last() + 8)
        for (file in listOf(missing, duplicate)) {
            for (tolerant in listOf(false, true)) {
                val failure = assertFailsWith<CorruptStreamException> { AudioKodec.probe(file, DecodeOptions(tolerant = tolerant)) }
                assertTrue("trex" in failure.message.orEmpty())
                assertTrue(failure.offset >= 0)
            }
        }
    }

    @Test
    fun maximumCountRunsOnOtherTracksAreSkippedWithoutWalkingSamples() {
        for (runs in intArrayOf(1, 16, 64)) {
            val file = buildFragmented(5, 1, 5, implicitAudioBase = true) { dataOffset ->
                box("traf", fullBox("tfhd", 0, 0x020000, u32(2)),
                    *Array(runs) { i -> fullBox("trun", 0, if (i == 0) 1 else 0, u32(Int.MAX_VALUE), if (i == 0) u32(dataOffset) else ByteArray(0)) })
            }
            for (tolerant in listOf(false, true)) {
                val options = DecodeOptions(tolerant = tolerant)
                assertEquals(5L, AudioKodec.probe(file, options).durationFrames)
                AudioKodec.open(file, options).close()
                AudioKodec.readTags(file, options)
                assertEquals(5, AudioKodec.decode(file, options).frames)
            }
            assertSamples(file, 1, 0, 5, "$runs maximum-count runs on another track")
        }
        // A large product fits on its own, but adding a second run overflows.
        val overflow = buildFragmented(5, 1, 5, otherTrexSize = -1) { dataOffset ->
            box("traf", fullBox("tfhd", 0, 0x020000, u32(2)),
                fullBox("trun", 0, 1, u32(Int.MAX_VALUE), u32(dataOffset)), fullBox("trun", 0, 0, u32(Int.MAX_VALUE)))
        }
        for (tolerant in listOf(false, true)) {
            val failure = assertFailsWith<CorruptStreamException> { AudioKodec.probe(overflow, DecodeOptions(tolerant = tolerant)) }
            assertTrue("trun" in failure.message.orEmpty())
        }
        // A declared per-sample field must still be backed by stored entries.
        val missing = buildFragmented(5, 1, 5) { dataOffset ->
            box("traf", fullBox("tfhd", 0, 0x020000, u32(2)), fullBox("trun", 0, 0x101, u32(Int.MAX_VALUE), u32(dataOffset)))
        }
        assertFailsWith<CorruptStreamException> { AudioKodec.probe(missing) }
    }

    @Test
    fun skippingAFragmentUsesItsOwnTrackDefaultsAndOverrides() {
        for (override in listOf(null, 10)) {
            for (fields in intArrayOf(0, 0x100, 0x400, 0x800, 0xD00)) {
                val sampleSize = override ?: 6
                val file = buildFragmented(5, 1, 5, otherTrexSize = 6, otherPayload = ByteArray(3 * sampleSize), implicitAudioBase = true) { dataOffset ->
                    fun run(count: Int, first: Boolean): ByteArray {
                        val entries = ByteArray(count * sampleFieldCount(fields) * 4)
                        return fullBox("trun", 0, fields or if (first) 1 else 0, u32(count), if (first) u32(dataOffset) else ByteArray(0), entries)
                    }
                    box("traf", fullBox("tfhd", 0, 0x020000 or if (override != null) 0x10 else 0, u32(2), if (override != null) u32(override) else ByteArray(0)), run(2, true), run(1, false))
                }
                for (tolerant in listOf(false, true)) {
                    val options = DecodeOptions(tolerant = tolerant)
                    assertEquals(5L, AudioKodec.probe(file, options).durationFrames)
                    assertEquals(5, AudioKodec.decode(file, options).frames)
                    AudioKodec.readTags(file, options)
                }
                assertSamples(file, 1, 0, 5, "other track size $sampleSize, fields $fields")
            }
        }
    }

    @Test
    fun skippingVariableSizeFragmentRunsKeepsTheFollowingAudioInPlace() {
        val file = buildFragmented(5, 1, 5, otherTrexSize = 6, otherPayload = ByteArray(11), implicitAudioBase = true) { dataOffset ->
            box("traf", fullBox("tfhd", 0, 0x020000, u32(2)), fullBox("trun", 0, 0xB01, u32(3), u32(dataOffset),
                u32(1), u32(3), u32(0), u32(2), u32(7), u32(0), u32(3), u32(1), u32(0)))
        }
        assertEquals(5L, AudioKodec.probe(file).durationFrames)
        assertSamples(file, 1, 0, 5, "variable-sized other track")
    }

    @Test
    fun fragmentOffsetArithmeticCannotWrap() {
        for ((base, relative, count) in listOf(
            Triple(-1L, null, 0), Triple(Long.MIN_VALUE, null, 0),
            Triple(Long.MAX_VALUE - 1, 2, 0), Triple(0L, -1, 0),
            Triple(Long.MAX_VALUE - 1, null, 2),
        )) {
            val file = buildFragmented(5, 1, 5, otherTrexSize = 1) {
                box("traf", fullBox("tfhd", 0, 1, u32(2), u64(base)),
                    fullBox("trun", 0, if (relative != null) 1 else 0, u32(count), if (relative != null) u32(relative) else ByteArray(0)))
            }
            for (tolerant in listOf(false, true)) {
                val options = DecodeOptions(tolerant = tolerant)
                for (operation in listOf<() -> Unit>(
                    { AudioKodec.probe(file, options) },
                    { AudioKodec.open(file, options).close() },
                    { AudioKodec.decode(file, options) },
                )) {
                    val failure = assertFailsWith<CorruptStreamException>("base $base, relative $relative, count $count", operation)
                    assertTrue("tfhd" in failure.message.orEmpty() || "trun" in failure.message.orEmpty())
                    assertTrue(failure.offset >= 0)
                }
                AudioKodec.readTags(file, options)
            }
        }
        val valid = buildFragmented(5, 1, 5, otherTrexSize = 1) {
            box("traf", fullBox("tfhd", 0, 1, u32(2), u64(Long.MAX_VALUE - 2)), fullBox("trun", 0, 1, u32(2), u32(-1)))
        }
        assertSamples(valid, 1, 0, 5, "representable high offset on another track")
    }

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
    fun alacStateHonoursAllocationLimitsBeforeProbeOrOpen() {
        fun file(frameLength: Int, channels: Int): ByteArray {
            val cookie = fullBox("alac", 0, 0, u32(frameLength),
                byteArrayOf(0, 16, 40, 10, 14, channels.toByte()), u16(255), u32(0), u32(0), u32(RATE))
            return build(3, channels, 3, format = "alac", sampleEntryBoxes = cookie)
        }
        // Isolate the interleaved scratch and the two-channel shift buffer.
        for ((frameLength, channels, limit) in listOf(Triple(4_096, 8, 65_536L), Triple(8_192, 1, 40_960L))) {
            val bytes = file(frameLength, channels)
            for (tolerant in listOf(false, true)) {
                val options = DecodeOptions(tolerant = tolerant, maxAllocationBytes = limit)
                assertFailsWith<AllocationGuardException> { AudioKodec.probe(bytes, options) }
                assertFailsWith<AllocationGuardException> { AudioKodec.open(bytes, options).close() }
            }
            assertEquals(channels, AudioKodec.probe(bytes).format.channels)
            AudioKodec.open(bytes).close()
        }
        // The limit is per allocation, rather than the sum of decoder state.
        val bytes = file(4_096, 1)
        val options = DecodeOptions(maxAllocationBytes = 40_960)
        assertEquals(1, AudioKodec.probe(bytes, options).format.channels)
        AudioKodec.open(bytes, options).close()
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

    private fun assertSamples(file: ByteArray, channels: Int, firstFrame: Int, frames: Int, what: String, options: DecodeOptions = DecodeOptions.DEFAULT) {
        AudioKodec.open(file, options).use { decoder ->
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
        timeRuns: List<Pair<Int, Int>>? = null,
        sampleSizes: IntArray? = null,
        leadingBoxes: ByteArray = ByteArray(0),
        useCo64: Boolean = false,
        dataReferences: ByteArray? = null,
        dataReferenceIndex: Int = 1,
    ): ByteArray {
        val chunkCount = (frames + chunkFrames - 1) / chunkFrames
        require(!singlePacket || chunkCount == 1)
        val ftyp = leadingBoxes + box("ftyp", ascii("qt  "), u32(0), ascii("qt  "))
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
                format, ByteArray(6), u16(dataReferenceIndex), u16(0), u16(0), u32(0), u16(sampleEntryChannels), u16(16), u16(0), u16(0), u32(sampleEntryRate shl 16),
                channelTag?.let { fullBox("chan", 0, 0, u32(it), u32(0), u32(0)) } ?: ByteArray(0),
                sampleEntryBoxes,
            )
            val stbl = box(
                "stbl",
                fullBox("stsd", 0, 0, u32(1), entry),
                fullBox("stts", 0, 0, u32(timeRuns?.size ?: 1),
                    *(timeRuns?.map { (count, delta) -> u32(count) + u32(delta) }
                        ?: listOf(u32(if (singlePacket) 1 else frames) + u32(if (singlePacket) frames else 1))).toTypedArray()),
                stsc,
                if (singlePacket) fullBox("stsz", 0, 0, u32(0), u32(1), u32(data.size))
                else if (sampleSizes != null) fullBox("stsz", 0, 0, u32(0), u32(sampleSizes.size), *sampleSizes.map(::u32).toTypedArray())
                else fullBox("stsz", 0, 0, u32(1), u32(frames)),
                if (useCo64) fullBox("co64", 0, 0, u32(chunkCount), *starts.map { u64(dataAt + it.toLong()) }.toTypedArray())
                else fullBox("stco", 0, 0, u32(chunkCount), *starts.map { u32(dataAt + it) }.toTypedArray()),
            )
            val mdia = box(
                "mdia",
                fullBox("mdhd", 0, 0, u32(0), u32(0), u32(RATE), u32(frames), u16(0), u16(0)),
                fullBox("hdlr", 0, 0, u32(0), ascii("soun"), ByteArray(12), byteArrayOf(0)),
                box("minf", fullBox("smhd", 0, 0, u32(0)), dataReferences?.let { box("dinf", it) } ?: ByteArray(0), stbl),
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
    private fun buildFragmented(
        frames: Int,
        channels: Int,
        fragmentFrames: Int,
        otherTrexSize: Int = 0,
        otherPayload: ByteArray = ByteArray(0),
        implicitAudioBase: Boolean = false,
        audioDescriptionIndex: Int? = null,
        otherTraf: ((Int) -> ByteArray)? = null,
    ): ByteArray {
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
            if (otherTraf != null) box("trak", fullBox("tkhd", 0, 7, u32(0), u32(0), u32(2), u32(0), u32(0), ByteArray(60)),
                box("mdia", fullBox("mdhd", 0, 0, u32(0), u32(0), u32(RATE), u32(0), u16(0), u16(0)),
                    fullBox("hdlr", 0, 0, u32(0), ascii("meta"), ByteArray(12), byteArrayOf(0)))) else ByteArray(0),
            box("mvex", fullBox("trex", 0, 0, u32(1), u32(1), u32(1), u32(frameBytes), u32(0)),
                if (otherTraf != null) fullBox("trex", 0, 0, u32(2), u32(1), u32(1), u32(otherTrexSize), u32(0)) else ByteArray(0)),
        )
        var out = box("ftyp", ascii("iso6"), u32(0), ascii("iso6")) + moov
        var at = 0
        var sequence = 1
        while (at < frames) {
            val count = minOf(fragmentFrames, frames - at)
            fun moof(dataOffset: Int) = box(
                "moof",
                fullBox("mfhd", 0, 0, u32(sequence)),
                otherTraf?.invoke(dataOffset) ?: ByteArray(0),
                box("traf", fullBox("tfhd", 0, (if (implicitAudioBase) 0 else 0x020000) or (if (audioDescriptionIndex == null) 0 else 2),
                    u32(1), audioDescriptionIndex?.let(::u32) ?: ByteArray(0)),
                    fullBox("trun", 0, if (implicitAudioBase) 0 else 1, u32(count), if (implicitAudioBase) ByteArray(0) else u32(dataOffset + otherPayload.size))),
            )
            val moofSize = moof(0).size
            out += moof(moofSize + 8) + box("mdat", otherPayload, pcm(at, count, channels))
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

    private fun sampleFieldCount(flags: Int): Int = listOf(0x100, 0x200, 0x400, 0x800).count { flags and it != 0 }

    private fun patchU32(bytes: ByteArray, type: String, afterType: Int, value: Int) {
        val positions = (0..bytes.size - 4).filter { at -> (0..3).all { k -> bytes[at + k] == type[k].code.toByte() } }
        assertTrue(positions.isNotEmpty(), "no $type box to change")
        for (at in positions) u32(value).copyInto(bytes, at + afterType)
    }

    private companion object {
        const val RATE = 8_000
    }
}

package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.bits.BitWriterMsb
import io.github.yuroyami.audiokodec.internal.flac.FlacCrc
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FLAC, without needing the reference tools.
 *
 * The oracle suites in `jvmTest` check against the reference encoder and
 * decoder, and only run where those are installed. These tests use AudioKodec's
 * own encoder as the source of files, so they run on every target, and they are
 * still meaningful: a lossless codec that cannot reproduce its own input is
 * broken, and the stored MD5 makes the check independent of the decoder that
 * produced it.
 */
class FlacTest {

    @Test
    fun aFlacFileSurvivesTheRoundTripAtEveryDepth() {
        for (depth in intArrayOf(8, 16, 24)) {
            for (channels in intArrayOf(1, 2, 6)) {
                val what = "$depth-bit, $channels channels"
                val original = TestSignals.quantise(
                    TestSignals.sine(4_000, channels, amplitude = 0.8f), depth,
                )
                val encoded = AudioKodec.encode(
                    original, AudioFormat(44_100, channels), EncodeTarget.flac(depth, 5),
                )
                val decoded = AudioKodec.decode(encoded)
                assertEquals(4_000, decoded.frames, "$what: frame count")
                assertEquals(channels, decoded.format.channels, "$what: channels")
                TestSignals.assertExact(original, decoded.samples, what)
            }
        }
    }

    @Test
    fun aSampleRateThatHasNoFrameHeaderCodeInheritsStreamInfoExactly() {
        val sampleRate = 384_000
        val original = TestSignals.quantise(
            TestSignals.sine(2_000, 2, sampleRate = sampleRate, amplitude = 0.7f),
            24,
        )
        val encoded = AudioKodec.encode(
            original,
            AudioFormat(sampleRate, 2),
            EncodeTarget.flac(24, 5),
        )

        val info = AudioKodec.probe(encoded)
        assertEquals(sampleRate, info.format.sampleRate)
        val decoded = AudioKodec.decode(encoded)
        assertEquals(sampleRate, decoded.format.sampleRate)
        TestSignals.assertExact(original, decoded.samples, "384 kHz FLAC")
    }

    @Test
    fun flacAppliesTheRequestedDitherBeforeEncoding() {
        // Values close to a 16-bit boundary make the seeded TPDF noise change
        // a predictable subset of samples.
        val samples = FloatArray(8_000) { ((it % 17) + 0.49f) / 32_768f }
        val plain = AudioKodec.encode(
            samples,
            AudioFormat(44_100, 1),
            EncodeTarget.flac(16, 5, Dither.None),
        )
        val dithered = AudioKodec.encode(
            samples,
            AudioFormat(44_100, 1),
            EncodeTarget.flac(16, 5, Dither.Tpdf(seed = 42)),
        )

        assertTrue(!plain.contentEquals(dithered), "dither did not affect the FLAC stream")
        assertTrue(
            !AudioKodec.decode(plain).samples.contentEquals(AudioKodec.decode(dithered).samples),
            "dither did not affect the quantised samples",
        )
    }

    @Test
    fun flacRefusesAChannelCountItsHeaderCannotRepresent() {
        assertFailsWith<IllegalArgumentException> {
            AudioKodec.encode(
                FloatArray(90),
                AudioFormat(44_100, 9),
                EncodeTarget.flac(),
            )
        }
    }

    @Test
    fun flacFrameBuffersRespectTheAllocationLimitBeforeTheyAreCreated() {
        val encoded = AudioKodec.encode(
            TestSignals.sine(4_096, 2),
            AudioFormat(44_100, 2),
            EncodeTarget.flac(),
        )
        assertFailsWith<AllocationGuardException> {
            AudioKodec.open(encoded, DecodeOptions(maxAllocationBytes = 1_024))
        }
    }

    @Test
    fun theStoredMd5DescribesTheAudioThatWasWritten() {
        val original = TestSignals.quantise(TestSignals.sine(6_000, 2, amplitude = 0.9f), 16)
        val encoded = AudioKodec.encode(original, AudioFormat(48_000, 2), EncodeTarget.flac(16, 5))
        assertEquals(true, AudioKodec.verifyFlacMd5(encoded))
    }

    @Test
    fun aCorruptedFrameIsNoticedByItsChecksum() {
        val original = TestSignals.quantise(TestSignals.sine(9_000, 1), 16)
        val encoded = AudioKodec.encode(original, AudioFormat(44_100, 1), EncodeTarget.flac(16, 5))
        val info = AudioKodec.probe(encoded)
        // Flip a bit well inside the audio, past the metadata.
        val at = (info.dataOffset + 200).toInt()
        val damaged = encoded.copyOf()
        damaged[at] = (damaged[at].toInt() xor 0x40).toByte()
        // Either the frame checksum or the header checksum catches it. Which
        // one depends on where the bit landed; that it is caught does not.
        assertFailsWith<CorruptStreamException> { AudioKodec.decode(damaged) }
    }

    @Test
    fun probeReadsAFlacHeaderWithoutDecoding() {
        val original = TestSignals.quantise(TestSignals.sine(44_100, 2), 24)
        val encoded = AudioKodec.encode(original, AudioFormat(44_100, 2), EncodeTarget.flac(24, 5))
        val info = AudioKodec.probe(encoded)
        assertEquals(AudioContainer.FLAC, info.container)
        assertEquals(AudioCodec.FLAC, info.codec)
        assertEquals(24, info.bitDepth)
        assertEquals(44_100L, info.durationFrames)
        assertEquals(1.0, info.durationSeconds)
        assertTrue(info.isVariableBitrate, "FLAC frames vary in size")
        assertTrue(info.bitrate!! > 0)
    }

    @Test
    fun theIntegerViewOfAFlacFileIsExact() {
        val values = IntArray(2_000) { ((it * 7_919) % 65_536) - 32_768 }
        val asFloat = Pcm.floatFromInt(values, 16)
        val encoded = AudioKodec.encode(asFloat, AudioFormat(44_100, 1), EncodeTarget.flac(16, 5))
        AudioKodec.open(encoded).use { decoder ->
            val access = assertIs<IntPcmAccess>(decoder, "a FLAC decoder exposes integers")
            assertEquals(16, access.bitDepth)
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
    fun frameBuffersFollowTheLargestFrameNotTheLengthOfTheFile() {
        var seed = 1234
        val samples = FloatArray(131_072) {
            seed = seed * 1_664_525 + 1_013_904_223
            (seed shr 16) / 32_768f
        }
        val stated = AudioKodec.encode(samples, AudioFormat(44_100, 1), EncodeTarget.flac(16, 0))
        // The same file with its maximum frame size left unstated, which the
        // format allows and which leaves the reader to find frame sizes out.
        val unstated = stated.copyOf().also { for (i in 15..17) it[i] = 0 }

        for ((what, bytes) in listOf("stated maximum" to stated, "unstated maximum" to unstated)) {
            // Each frame is small, so a small ceiling is enough to stream it,
            // however long the recording is.
            var streamed = 0
            AudioKodec.open(bytes, DecodeOptions(maxAllocationBytes = 65_536)).use { decoder ->
                while (true) streamed += (decoder.nextFrame() ?: break).frameCount
            }
            assertEquals(131_072, streamed, what)

            // Bytes read from the source stay proportional to the file.
            var transferred = 0L
            val inner = bytes.asAudioSource()
            val counting = object : AudioSource {
                override val size: Long get() = inner.size
                override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int =
                    inner.readAt(position, into, offset, count).also { if (it > 0) transferred += it }
                override fun close() = Unit
            }
            var frames = 0
            AudioKodec.open(counting).use { decoder ->
                transferred = 0
                while (true) frames += (decoder.nextFrame() ?: break).frameCount
            }
            assertEquals(131_072, frames, what)
            assertTrue(
                transferred < bytes.size * 4L,
                "$what: decoding a ${bytes.size}-byte file read $transferred bytes",
            )
        }
    }

    @Test
    fun aPredictorLongerThanItsBlockIsCorruptNotAnArrayOverrun() {
        // LPC order 32 in a 16-sample block, and fixed order 4 in a 2-sample
        // one. The warm-up samples alone would run past the block.
        for ((what, file) in listOf(
            "LPC order 32" to flacWithOversizedPredictor(blockSize = 16, subframeType = 63, warmUp = 32),
            "fixed order 4" to flacWithOversizedPredictor(blockSize = 2, subframeType = 12, warmUp = 4),
        )) {
            assertFailsWith<CorruptStreamException>(what) { AudioKodec.decode(file) }
            expectOnlyKiteFailures(what) { AudioKodec.decode(file, DecodeOptions(tolerant = true)) }
        }
    }

    @Test
    fun aResidualWiderThanThePcmSampleDoesNotOverflow() {
        // A fixed order-one predictor subtracts the preceding sample. Moving
        // from Int.MAX_VALUE to Int.MIN_VALUE therefore produces the valid
        // 33-bit residual -4_294_967_295 even though both reconstructed
        // samples fit exactly in signed 32-bit PCM.
        val encoded = flacWithWideOrderOneResidual()
        AudioKodec.open(encoded).use { decoder ->
            val access = assertIs<IntPcmAccess>(decoder)
            val block = assertNotNull(access.nextIntFrame())
            assertEquals(32, block.bitDepth)
            assertEquals(16, block.frameCount)
            assertEquals(Int.MAX_VALUE, block.samples[0])
            for (i in 1 until block.frameCount) {
                assertEquals(Int.MIN_VALUE, block.samples[i], "sample $i")
            }
            assertEquals(null, access.nextIntFrame())
        }
    }

    @Test
    fun seekingIntoAFlacFileIsSampleAccurate() {
        val frames = 15_000
        val original = TestSignals.quantise(TestSignals.sine(frames, 2), 16)
        val encoded = AudioKodec.encode(original, AudioFormat(44_100, 2), EncodeTarget.flac(16, 5))
        AudioKodec.open(encoded).use { decoder ->
            for (target in longArrayOf(0, 1, 4_095, 4_096, 8_191, 14_999)) {
                val reached = decoder.seek(target)
                assertEquals(target, reached, "seek to $target")
                val block = assertNotNull(decoder.nextFrame(), "nothing after seeking to $target")
                assertEquals(target, block.positionFrames)
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
    fun seekingPastTheEndOfAFlacFileStopsAtTheEnd() {
        val original = TestSignals.quantise(TestSignals.sine(5_000, 1), 16)
        val encoded = AudioKodec.encode(original, AudioFormat(44_100, 1), EncodeTarget.flac(16, 5))
        AudioKodec.open(encoded).use { decoder ->
            assertEquals(5_000L, decoder.seek(1_000_000))
            assertEquals(null, decoder.nextFrame())
        }
    }

    @Test
    fun tagsCanBeWrittenWithTheAudioAndReadBack() {
        val encoded = AudioKodec.encode(
            TestSignals.quantise(TestSignals.sine(2_000, 2), 16),
            AudioFormat(44_100, 2),
            EncodeTarget.flac(16, 5),
            TagBundle(
                mapOf(
                    TagField.TITLE to listOf("A FLAC Title"),
                    TagField.ARTIST to listOf("An Artist", "Another Artist"),
                    TagField.TRACK_NUMBER to listOf("3"),
                    TagField.TRACK_TOTAL to listOf("9"),
                ),
            ),
        )
        val tags = AudioKodec.readTags(encoded)
        assertEquals("A FLAC Title", tags[TagField.TITLE])
        // A Vorbis comment repeats the key for a second value, and both come
        // back.
        assertEquals(listOf("An Artist", "Another Artist"), tags.all(TagField.ARTIST))
        assertEquals("3", tags[TagField.TRACK_NUMBER])
        assertEquals("9", tags[TagField.TRACK_TOTAL])
        assertEquals(TagFormat.VORBIS_COMMENT, tags.raw.blocks.first().format)
    }

    @Test
    fun editingTheTagsOfAFlacFileKeepsTheAudioIdentical() {
        val original = TestSignals.quantise(TestSignals.sine(7_000, 2), 16)
        val before = AudioKodec.encode(
            original,
            AudioFormat(44_100, 2),
            EncodeTarget.flac(16, 5),
            TagBundle(mapOf(TagField.TITLE to listOf("Before"))),
        )
        val after = AudioKodec.writeTags(
            before,
            AudioKodec.readTags(before).edit {
                set(TagField.TITLE, "After")
                set(TagField.ALBUM, "An album added later")
            },
        )
        // The stream MD5 was written by the encoder over the original samples,
        // so it still matching is proof that editing the tags did not touch the
        // audio.
        assertEquals(true, AudioKodec.verifyFlacMd5(after), "the stored MD5 after editing")
        TestSignals.assertExact(original, AudioKodec.decode(after).samples)

        val tags = AudioKodec.readTags(after)
        assertEquals("After", tags[TagField.TITLE])
        assertEquals("An album added later", tags[TagField.ALBUM])
    }

    @Test
    fun rewritingTheSameTagsDoesNotGrowTheFile() {
        var current = AudioKodec.encode(
            TestSignals.quantise(TestSignals.sine(3_000, 1), 16),
            AudioFormat(44_100, 1),
            EncodeTarget.flac(16, 5),
            TagBundle(mapOf(TagField.TITLE to listOf("Stable"))),
        )
        current = AudioKodec.writeTags(current, AudioKodec.readTags(current))
        val size = current.size
        repeat(4) {
            current = AudioKodec.writeTags(current, AudioKodec.readTags(current))
        }
        assertEquals(size, current.size, "rewriting the same tags should not grow the file")
        assertEquals("Stable", AudioKodec.readTags(current)[TagField.TITLE])
    }

    @Test
    fun aSeekTableCarriedByTheFileIsKeptWhenTagsAreEdited() {
        // AudioKodec writes no seek table of its own, so this checks that a
        // block it does not produce is still carried across an edit.
        val encoded = AudioKodec.encode(
            TestSignals.quantise(TestSignals.sine(3_000, 1), 16),
            AudioFormat(44_100, 1),
            EncodeTarget.flac(16, 5),
        )
        val rewritten = AudioKodec.writeTags(
            encoded, TagBundle(mapOf(TagField.TITLE to listOf("Kept"))),
        )
        assertEquals(true, AudioKodec.verifyFlacMd5(rewritten))
    }

    @Test
    fun aTruncatedFlacFileIsReportedOrSalvaged() {
        val original = TestSignals.quantise(TestSignals.sine(12_000, 1), 16)
        val encoded = AudioKodec.encode(original, AudioFormat(44_100, 1), EncodeTarget.flac(16, 5))

        // The cut is measured from where the audio starts, not from the start
        // of the file, so that a known number of whole frames survives it.
        // Cutting blindly can land inside the first frame, in which case there
        // is genuinely nothing to salvage.
        val info = AudioKodec.probe(encoded)
        val audioBytes = encoded.size - info.dataOffset
        val cut = encoded.copyOf((info.dataOffset + audioBytes * 3 / 4).toInt())

        assertFailsWith<AudioKodecException> { AudioKodec.decode(cut) }

        val salvaged = AudioKodec.decode(cut, DecodeOptions(tolerant = true))
        assertTrue(
            salvaged.frames in 1 until 12_000,
            "tolerant decoding salvaged ${salvaged.frames} frames of 12000",
        )
        for (i in 0 until salvaged.frames) {
            assertEquals(original[i], salvaged.samples[i], "salvaged sample $i")
        }
    }

    @Test
    fun tolerantModeStillRejectsAFileWithNoSalvageableFrame() {
        val encoded = AudioKodec.encode(
            TestSignals.quantise(TestSignals.sine(200, 1), 16),
            AudioFormat(44_100, 1),
            EncodeTarget.flac(16, 5),
        )
        val corrupt = encoded.copyOf()
        corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 0x01).toByte()

        assertFailsWith<AudioKodecException> {
            AudioKodec.decode(corrupt, DecodeOptions(tolerant = true))
        }
    }

    @Test
    fun aFileWithNoStreamInformationIsRejected() {
        val bytes = "fLaC".encodeToByteArray() + ByteArray(20)
        assertFailsWith<CorruptStreamException> { AudioKodec.probe(bytes) }
    }

    @Test
    fun theMetadataChainRequiresExactStreamInfoAndALastBlock() {
        val encoded = AudioKodec.encode(
            FloatArray(32),
            AudioFormat(44_100, 1),
            EncodeTarget.flac(),
        )

        val wrongStreamInfoSize = encoded.copyOf()
        wrongStreamInfoSize[7] = 35
        assertFailsWith<CorruptStreamException> { AudioKodec.probe(wrongStreamInfoSize) }

        val noLastBlock = encoded.copyOf()
        val last = lastFlacMetadataHeader(noLastBlock)
        noLastBlock[last] = (noLastBlock[last].toInt() and 0x7F).toByte()
        assertFailsWith<AudioKodecException> { AudioKodec.probe(noLastBlock) }
    }

    @Test
    fun malformedAndOversizedSeekTablesAreRejectedBeforeAllocation() {
        val encoded = AudioKodec.encode(
            FloatArray(32),
            AudioFormat(44_100, 1),
            EncodeTarget.flac(),
        )
        val paddingAt = lastFlacMetadataHeader(encoded)

        val malformed = encoded.copyOf()
        malformed[paddingAt] = (0x80 or 3).toByte()
        assertFailsWith<CorruptStreamException> { AudioKodec.probe(malformed) }

        val guarded = encoded.copyOf()
        guarded[paddingAt] = (0x80 or 3).toByte()
        // 4086 bytes is 227 seek points and fits in the existing padding.
        guarded[paddingAt + 1] = 0
        guarded[paddingAt + 2] = 0x0F
        guarded[paddingAt + 3] = 0xF6.toByte()
        assertFailsWith<AllocationGuardException> {
            AudioKodec.probe(guarded, DecodeOptions(maxAllocationBytes = 1024))
        }
    }

    @Test
    fun everyTruncationOfAFlacFileIsHandled() {
        val encoded = AudioKodec.encode(
            TestSignals.quantise(TestSignals.sine(3_000, 2), 16),
            AudioFormat(44_100, 2),
            EncodeTarget.flac(16, 5),
        )
        for (length in 0 until encoded.size step 29) {
            val cut = encoded.copyOf(length)
            expectOnlyKiteFailures("truncated to $length bytes") {
                AudioKodec.decode(cut, DecodeOptions(tolerant = true))
            }
            expectOnlyKiteFailures("probing $length bytes") { AudioKodec.probe(cut) }
            expectOnlyKiteFailures("reading tags of $length bytes") { AudioKodec.readTags(cut) }
        }
    }

    @Test
    fun mutatingSingleBytesOfAFlacFileNeverBreaksTheDecoder() {
        val encoded = AudioKodec.encode(
            TestSignals.quantise(TestSignals.sine(2_000, 2), 16),
            AudioFormat(44_100, 2),
            EncodeTarget.flac(16, 8),
        )
        var state = 314_159
        repeat(3_000) {
            state = state * 1_103_515_245 + 12_345
            val at = ((state ushr 8) and 0x7FFF_FFFF) % encoded.size
            state = state * 1_103_515_245 + 12_345
            val mutated = encoded.copyOf()
            mutated[at] = ((state ushr 16) and 0xFF).toByte()
            expectOnlyKiteFailures("byte $at mutated") {
                AudioKodec.decode(mutated, DecodeOptions(tolerant = true))
            }
        }
    }

    @Test
    fun aStreamInformationBlockClaimingAbsurdValuesIsRejected() {
        val encoded = AudioKodec.encode(
            TestSignals.quantise(TestSignals.sine(1_000, 1), 16),
            AudioFormat(44_100, 1),
            EncodeTarget.flac(16, 5),
        )
        // The packed field holding the sample rate starts 14 bytes in: four of
        // magic, four of block header, then six of block sizes and frame sizes.
        val damaged = encoded.copyOf()
        damaged[18] = 0x00
        damaged[19] = 0x00
        damaged[20] = 0x00
        expectOnlyKiteFailures("a stream with no sample rate") { AudioKodec.probe(damaged) }
    }

    private inline fun expectOnlyKiteFailures(what: String, block: () -> Unit) {
        try {
            block()
        } catch (_: AudioKodecException) {
            // Expected: every failure has to be one of ours.
        } catch (e: Throwable) {
            throw AssertionError("$what threw ${e::class.simpleName}: ${e.message}", e)
        }
    }
}

private fun lastFlacMetadataHeader(bytes: ByteArray): Int {
    var at = 4
    while (true) {
        val first = bytes[at].toInt() and 0xFF
        val length = ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
        if (first and 0x80 != 0) return at
        at += 4 + length
    }
}

/**
 * A complete one-frame FLAC fixture whose first residual cannot fit in an
 * `Int`. Building it by hand prevents the regression from depending on which
 * predictor the encoder happens to choose.
 */
private fun flacWithWideOrderOneResidual(): ByteArray {
    val frame = BitWriterMsb(96)

    // Frame header: fixed-block stream, 16 samples, 44.1 kHz, mono, 32-bit,
    // frame number zero.
    frame.writeBits(0x3FFE, 14)
    frame.writeBit(0)
    frame.writeBit(0)
    frame.writeBits(6, 4)
    frame.writeBits(9, 4)
    frame.writeBits(0, 4)
    frame.writeBits(7, 3)
    frame.writeBit(0)
    frame.writeBits(0, 8)
    frame.writeBits(15, 8)
    val headerCrc = FlacCrc.crc8(frame.rawBuffer(), 0, frame.byteLength)
    frame.writeBits(headerCrc, 8)

    // Fixed predictor, order one. The warm-up is Int.MAX_VALUE. Rice2 with
    // parameter 30 represents -4_294_967_295 as the folded value 2^33 - 3.
    frame.writeBit(0)
    frame.writeBits(9, 6)
    frame.writeBit(0)
    frame.writeBits(Int.MAX_VALUE, 32)
    frame.writeBits(1, 2)
    frame.writeBits(0, 4)
    frame.writeBits(30, 5)
    frame.writeUnary(7)
    frame.writeBits(0x3FFF_FFFD, 30)
    repeat(14) {
        frame.writeUnary(0)
        frame.writeBits(0, 30)
    }
    frame.alignToByte()
    val frameCrc = FlacCrc.crc16(frame.rawBuffer(), 0, frame.byteLength)
    frame.writeBits(frameCrc, 16)

    val out = ByteArray(4 + 4 + 34 + frame.byteLength)
    "fLaC".encodeToByteArray().copyInto(out)
    out[4] = 0x80.toByte()
    out[7] = 34
    writeU16Be(out, 8, 16)
    writeU16Be(out, 10, 16)
    val packed = (44_100L shl 44) or (31L shl 36) or 16L
    writeU64Be(out, 18, packed)
    frame.rawBuffer().copyInto(out, 42, 0, frame.byteLength)
    return out
}

/**
 * A one-frame 16-bit mono FLAC file whose only subframe declares a predictor
 * with [warmUp] warm-up samples, written as zeros, in a block of [blockSize].
 */
private fun flacWithOversizedPredictor(blockSize: Int, subframeType: Int, warmUp: Int): ByteArray {
    val frame = BitWriterMsb(128)
    frame.writeBits(0x3FFE, 14)
    frame.writeBit(0)
    frame.writeBit(0)
    frame.writeBits(6, 4) // Block size stored as an 8-bit value minus one.
    frame.writeBits(9, 4) // 44.1 kHz.
    frame.writeBits(0, 4) // Mono.
    frame.writeBits(4, 3) // 16 bits.
    frame.writeBit(0)
    frame.writeBits(0, 8) // Frame number zero.
    frame.writeBits(blockSize - 1, 8)
    frame.writeBits(FlacCrc.crc8(frame.rawBuffer(), 0, frame.byteLength), 8)
    frame.writeBit(0)
    frame.writeBits(subframeType, 6)
    frame.writeBit(0)
    repeat(warmUp) { frame.writeBits(0, 16) }
    frame.alignToByte()
    frame.writeBits(FlacCrc.crc16(frame.rawBuffer(), 0, frame.byteLength), 16)

    val out = ByteArray(4 + 4 + 34 + frame.byteLength)
    "fLaC".encodeToByteArray().copyInto(out)
    out[4] = 0x80.toByte()
    out[7] = 34
    writeU16Be(out, 8, blockSize)
    writeU16Be(out, 10, blockSize)
    val packed = (44_100L shl 44) or (15L shl 36) or blockSize.toLong()
    writeU64Be(out, 18, packed)
    frame.rawBuffer().copyInto(out, 42, 0, frame.byteLength)
    return out
}

private fun writeU16Be(out: ByteArray, at: Int, value: Int) {
    out[at] = (value ushr 8).toByte()
    out[at + 1] = value.toByte()
}

private fun writeU64Be(out: ByteArray, at: Int, value: Long) {
    for (i in 0 until 8) out[at + i] = (value ushr (56 - i * 8)).toByte()
}

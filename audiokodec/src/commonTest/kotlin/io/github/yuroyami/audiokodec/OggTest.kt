package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.ogg.OggReader
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Ogg container layer, without needing the reference tools.
 *
 * The oracle suite in `jvmTest` checks against files the reference tools
 * produce and only runs where those are installed. These tests build Ogg files
 * here, byte by byte, so they run on every target. Building the files by hand
 * also tests something a round trip cannot: that the reader agrees with the
 * format rather than with a writer of ours.
 */
class OggTest {

    @Test
    fun aPageIsReadBackWithItsFieldsIntact() {
        val page = OggBuilder()
            .page(serial = 0x1234, sequence = 0, granule = 0, flags = 2, packets = listOf(vorbisId()))
            .build()
        // A page whose checksum is right is a page. This is the whole contract
        // of the container layer's first half.
        assertEquals(AudioContainer.OGG, AudioKodec.sniff(page))
    }

    @Test
    fun aPageWithAWrongChecksumIsRejected() {
        val builder = OggBuilder()
            .page(serial = 7, sequence = 0, granule = 0, flags = 2, packets = listOf(vorbisId()))
        val bytes = builder.build()
        // Flip a byte of the payload without recomputing the checksum.
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        assertFailsWith<AudioKodecException> { AudioKodec.probe(bytes) }
    }

    @Test
    fun theHeadersOfAVorbisStreamAreRead() {
        val bytes = OggBuilder()
            .page(serial = 1, sequence = 0, granule = 0, flags = 2, packets = listOf(vorbisId()))
            .page(
                serial = 1, sequence = 1, granule = 0, flags = 0,
                packets = listOf(vorbisComment("TITLE=Hand Built", "ARTIST=Nobody")),
            )
            .page(serial = 1, sequence = 2, granule = 0, flags = 0, packets = listOf(ByteArray(40)))
            .page(serial = 1, sequence = 3, granule = 22_050, flags = 4, packets = listOf(ByteArray(60)))
            .build()

        val info = AudioKodec.probe(bytes)
        assertEquals(AudioContainer.OGG, info.container)
        assertEquals(AudioCodec.VORBIS, info.codec)
        assertEquals(44_100, info.format.sampleRate)
        assertEquals(2, info.format.channels)
        assertEquals(22_050L, info.durationFrames)
        assertEquals(0.5, info.durationSeconds)

        val tags = AudioKodec.readTags(bytes)
        assertEquals("Hand Built", tags[TagField.TITLE])
        assertEquals("Nobody", tags[TagField.ARTIST])
        assertEquals(TagFormat.VORBIS_COMMENT, tags.raw.blocks.first().format)
        assertEquals(86L, tags.raw.blocks.first().offset)
    }

    @Test
    fun theHeadersOfAnOpusStreamAreRead() {
        val bytes = OggBuilder()
            .page(serial = 2, sequence = 0, granule = 0, flags = 2, packets = listOf(opusHead(preSkip = 312)))
            .page(
                serial = 2, sequence = 1, granule = 0, flags = 0,
                packets = listOf(opusTags("TITLE=Opus Title")),
            )
            // Fifty-one packets of 20 ms make 48 960 samples, which the
            // last page's granule position cuts to 48 312: the pre-skip and
            // a second of audio.
            .page(
                serial = 2, sequence = 2, granule = 48_312, flags = 4,
                packets = List(51) { byteArrayOf(0xF8.toByte(), 0) },
            )
            .build()

        val info = AudioKodec.probe(bytes)
        assertEquals(AudioCodec.OPUS, info.codec)
        // Opus always decodes at 48 kHz whatever it was recorded at.
        assertEquals(48_000, info.format.sampleRate)
        // The pre-skip is not audio, so it does not count towards the length.
        assertEquals(48_000L, info.durationFrames)
        assertEquals(1.0, info.durationSeconds)
        assertEquals("Opus Title", AudioKodec.readTags(bytes)[TagField.TITLE])
    }

    @Test
    fun repeatedOpusTagEditsRetainUnknownCommentsWithoutAddingVendorComments() {
        val comments = listOf("PRIVATE=one", "Private=one", "PRIVATE=one", "VENDOR=user", "PRIVATE=", "OTHER=x=y 日本語", "R128_PRIVATE=")
        val audio = byteArrayOf(1, 0x42, 0x24)
        var bytes = OggBuilder()
            .page(serial = 40, sequence = 0, granule = 0, flags = 2, packets = listOf(opusHead(312)))
            .page(serial = 40, sequence = 1, granule = 0, flags = 0, packets = listOf(opusTags(*comments.toTypedArray())))
            .page(serial = 40, sequence = 2, granule = 48_312, flags = 4, packets = listOf(audio))
            .build()
        repeat(3) { edit ->
            bytes = AudioKodec.writeTags(bytes, AudioKodec.readTags(bytes).edit { set(TagField.TITLE, "Edit $edit") })
            val packets = OggReader(SourceReader(bytes.asAudioSource(), DecodeOptions.DEFAULT), DecodeOptions.DEFAULT).packets(40, 0)
            assertNotNull(packets.next())
            val comment = assertNotNull(packets.next())
            assertEquals("AudioKodec tests" to (listOf("TITLE=Edit $edit") + comments), TagStressFixtures.comments(comment.bytes.copyOfRange(8, comment.length)))
            val writtenAudio = assertNotNull(packets.next())
            assertContentEquals(audio, writtenAudio.bytes.copyOfRange(0, writtenAudio.length))
            assertEquals(48_312L, writtenAudio.granule)
        }
    }

    @Test
    fun rewritingOpusTagsPreservesItsOpaqueSuffix() {
        val suffix = byteArrayOf(1, 0x42, 0x24, 0x7F)
        val comment = opusTags("TITLE=Before") + suffix
        val bytes = OggBuilder()
            .page(serial = 39, sequence = 0, granule = 0, flags = 2, packets = listOf(opusHead(312)))
            .page(serial = 39, sequence = 1, granule = 0, flags = 0, packets = listOf(comment))
            .page(serial = 39, sequence = 2, granule = 48_312, flags = 4, packets = listOf(byteArrayOf(1)))
            .build()

        val rewritten = AudioKodec.writeTags(
            bytes,
            AudioKodec.readTags(bytes).edit { set(TagField.TITLE, "After") },
        )
        val packets = OggReader(
            SourceReader(rewritten.asAudioSource(), DecodeOptions.DEFAULT),
            DecodeOptions.DEFAULT,
        ).packets(39, 0)
        assertNotNull(packets.next())
        val rewrittenComment = assertNotNull(packets.next())
        assertContentEquals(
            suffix,
            rewrittenComment.bytes.copyOfRange(rewrittenComment.length - suffix.size, rewrittenComment.length),
        )
        assertEquals("After", AudioKodec.readTags(rewritten)[TagField.TITLE])
    }

    @Test
    fun aPacketSpanningSeveralPagesIsReassembled() {
        // A comment longer than a page has to be put back together from its
        // parts before it can be read, which is the container layer's other
        // half.
        val longValue = "TITLE=" + "x".repeat(900)
        val comment = vorbisComment(longValue)
        val bytes = OggBuilder()
            .page(serial = 3, sequence = 0, granule = 0, flags = 2, packets = listOf(vorbisId()))
            .splitPacket(serial = 3, startSequence = 1, packet = comment, pieceSize = 255)
            .page(serial = 3, sequence = 5, granule = 0, flags = 0, packets = listOf(ByteArray(30)))
            .page(serial = 3, sequence = 6, granule = 1_000, flags = 4, packets = listOf(ByteArray(30)))
            .build()

        val tags = AudioKodec.readTags(bytes)
        assertEquals("x".repeat(900), tags[TagField.TITLE])
    }

    @Test
    fun theAudioStreamIsFoundAmongSeveralLogicalStreams() {
        // Two streams beginning at once: one this library does not know, and
        // one it does. The audio has to be the one that is reported.
        val bytes = OggBuilder()
            .page(serial = 10, sequence = 0, granule = 0, flags = 2, packets = listOf(theoraId()))
            .page(serial = 11, sequence = 0, granule = 0, flags = 2, packets = listOf(vorbisId()))
            .page(serial = 10, sequence = 1, granule = 0, flags = 0, packets = listOf(ByteArray(20)))
            .page(
                serial = 11, sequence = 1, granule = 0, flags = 0,
                packets = listOf(vorbisComment("TITLE=Soundtrack")),
            )
            .page(serial = 11, sequence = 2, granule = 0, flags = 0, packets = listOf(ByteArray(30)))
            .page(serial = 10, sequence = 2, granule = 100, flags = 0, packets = listOf(ByteArray(40)))
            .page(serial = 11, sequence = 3, granule = 44_100, flags = 4, packets = listOf(ByteArray(50)))
            .build()

        val info = AudioKodec.probe(bytes)
        assertEquals(AudioCodec.VORBIS, info.codec)
        assertEquals(44_100L, info.durationFrames)
        assertEquals("Soundtrack", AudioKodec.readTags(bytes)[TagField.TITLE])
    }

    @Test
    fun aStreamWithNoAudioIsNamedRatherThanGuessedAt() {
        val bytes = OggBuilder()
            .page(serial = 20, sequence = 0, granule = 0, flags = 2, packets = listOf(theoraId()))
            .page(serial = 20, sequence = 1, granule = 100, flags = 4, packets = listOf(ByteArray(20)))
            .build()
        val failure = assertFailsWith<UnsupportedFormatException> { AudioKodec.probe(bytes) }
        assertTrue(
            failure.message!!.contains("Theora"),
            "the error should name what it found: ${failure.message}",
        )
    }

    @Test
    fun aVorbisStreamHasItsHeadersAndTagsReadWithoutItsAudio() {
        val bytes = OggBuilder()
            .page(serial = 4, sequence = 0, granule = 0, flags = 2, packets = listOf(vorbisId()))
            .page(
                serial = 4, sequence = 1, granule = 0, flags = 0,
                packets = listOf(vorbisComment("TITLE=Only the headers are real")),
            )
            .page(serial = 4, sequence = 2, granule = 0, flags = 0, packets = listOf(ByteArray(30)))
            .page(serial = 4, sequence = 3, granule = 44_100, flags = 4, packets = listOf(ByteArray(40)))
            .build()

        // Reading a header costs no codec, which is why probe and readTags work
        // on a file whose setup header is the nonsense this one carries.
        assertEquals(44_100L, AudioKodec.probe(bytes).durationFrames)
        assertEquals(AudioCodec.VORBIS, AudioKodec.probe(bytes).codec)
        assertEquals("Only the headers are real", AudioKodec.readTags(bytes)[TagField.TITLE])

        // Decoding needs the setup header, and this one is a run of zeroes.
        assertFailsWith<CorruptStreamException> { AudioKodec.decode(bytes) }
    }

    @Test
    fun pictureCommentsAreFoundInAnOggFile() {
        // Ogg has no picture block, so cover art travels as a base64 comment.
        val image = ByteArray(600) { ((it * 13) and 0xFF).toByte() }
        image[0] = 0x89.toByte()
        image[1] = 'P'.code.toByte()
        val picture = io.github.yuroyami.audiokodec.tag.TagPicture(
            mimeType = "image/png",
            description = "Cover",
            bytes = image,
            width = 32,
            height = 32,
        )
        val encoded = flacPictureComment(picture)
        val bytes = OggBuilder()
            .page(serial = 5, sequence = 0, granule = 0, flags = 2, packets = listOf(vorbisId()))
            .splitPacket(
                serial = 5, startSequence = 1,
                packet = vorbisComment("TITLE=Has art", encoded), pieceSize = 255,
            )
            .page(serial = 5, sequence = 5, granule = 0, flags = 0, packets = listOf(ByteArray(30)))
            .page(serial = 5, sequence = 6, granule = 1_000, flags = 4, packets = listOf(ByteArray(30)))
            .build()

        val tags = AudioKodec.readTags(bytes)
        assertEquals("Has art", tags[TagField.TITLE])
        val cover = assertNotNull(tags.cover, "the picture comment was not read")
        assertEquals(1, tags.pictures.size, "the picture comment was assembled twice")
        assertEquals("image/png", cover.mimeType)
        assertEquals(32, cover.width)
        assertEquals(image.size, cover.bytes.size)
    }

    @Test
    fun anEmptyOrNonsensicalOggFileIsRejectedCleanly() {
        assertFailsWith<AudioKodecException> { AudioKodec.probe("OggS".encodeToByteArray()) }
        assertFailsWith<AudioKodecException> {
            AudioKodec.probe("OggS".encodeToByteArray() + ByteArray(50))
        }
    }

    @Test
    fun everyTruncationOfAnOggFileIsHandled() {
        val bytes = OggBuilder()
            .page(serial = 6, sequence = 0, granule = 0, flags = 2, packets = listOf(vorbisId()))
            .page(
                serial = 6, sequence = 1, granule = 0, flags = 0,
                packets = listOf(vorbisComment("TITLE=Truncate me")),
            )
            .page(serial = 6, sequence = 2, granule = 0, flags = 0, packets = listOf(ByteArray(40)))
            .page(serial = 6, sequence = 3, granule = 5_000, flags = 4, packets = listOf(ByteArray(90)))
            .build()

        for (length in 0 until bytes.size step 7) {
            val cut = bytes.copyOf(length)
            expectOnlyKiteFailures("probing $length bytes") { AudioKodec.probe(cut) }
            expectOnlyKiteFailures("reading tags of $length bytes") { AudioKodec.readTags(cut) }
            expectOnlyKiteFailures("sniffing $length bytes") { AudioKodec.sniff(cut) }
        }
    }

    @Test
    fun tagWritingRefusesAStreamWithoutAnEosPage() {
        val bytes = OggBuilder()
            .page(serial = 40, sequence = 0, granule = 0, flags = 2, packets = listOf(vorbisId()))
            .page(
                serial = 40,
                sequence = 1,
                granule = 0,
                flags = 0,
                packets = listOf(vorbisComment("TITLE=Unterminated")),
            )
            .page(serial = 40, sequence = 2, granule = 0, flags = 0, packets = listOf(ByteArray(40)))
            .page(serial = 40, sequence = 3, granule = 5_000, flags = 0, packets = listOf(ByteArray(90)))
            .build()

        assertFailsWith<TruncatedStreamException> { AudioKodec.probe(bytes) }
        assertEquals(
            5_000L,
            AudioKodec.probe(bytes, DecodeOptions(tolerant = true)).durationFrames,
        )
        val edited = AudioKodec.readTags(bytes, DecodeOptions(tolerant = true))
            .edit { set(TagField.TITLE, "Must not be written") }
        assertFailsWith<TruncatedStreamException> { AudioKodec.writeTags(bytes, edited) }
        assertFailsWith<TruncatedStreamException> {
            AudioKodec.writeTags(
                bytes.asAudioSource(),
                edited,
                ByteArrayAudioSink(),
                decodeOptions = DecodeOptions(tolerant = true),
            )
        }
    }

    @Test
    fun mutatingSingleBytesOfAnOggFileNeverBreaksTheReader() {
        val bytes = OggBuilder()
            .page(serial = 8, sequence = 0, granule = 0, flags = 2, packets = listOf(vorbisId()))
            .page(
                serial = 8, sequence = 1, granule = 0, flags = 0,
                packets = listOf(vorbisComment("TITLE=Mutate me", "ARTIST=Someone")),
            )
            .page(serial = 8, sequence = 2, granule = 0, flags = 0, packets = listOf(ByteArray(50)))
            .page(serial = 8, sequence = 3, granule = 9_000, flags = 4, packets = listOf(ByteArray(70)))
            .build()

        var state = 271_828
        repeat(4_000) {
            state = state * 1_103_515_245 + 12_345
            val at = ((state ushr 8) and 0x7FFF_FFFF) % bytes.size
            state = state * 1_103_515_245 + 12_345
            val mutated = bytes.copyOf()
            mutated[at] = ((state ushr 16) and 0xFF).toByte()
            expectOnlyKiteFailures("byte $at mutated") { AudioKodec.probe(mutated) }
            expectOnlyKiteFailures("byte $at mutated, reading tags") { AudioKodec.readTags(mutated) }
        }
    }

    @Test
    fun strictReadingDistinguishesCleanEofFromAPartialPage() {
        val complete = OggBuilder()
            .page(serial = 30, sequence = 0, granule = 0, flags = 2, packets = listOf(vorbisId()))
            .build()
        val reader = OggReader(
            SourceReader(complete.copyOf(12).asAudioSource(), DecodeOptions.DEFAULT),
            DecodeOptions.DEFAULT,
        )
        assertFailsWith<TruncatedStreamException> { reader.pageAt(0) }
        assertEquals(
            null,
            OggReader(
                SourceReader(complete.asAudioSource(), DecodeOptions.DEFAULT),
                DecodeOptions.DEFAULT,
            ).pageAt(complete.size.toLong()),
        )
    }

    @Test
    fun onlyTheFinalPacketOnAnEosPageIsMarkedLast() {
        val bytes = OggBuilder()
            .page(
                serial = 31,
                sequence = 0,
                granule = 20,
                flags = 2 or 4,
                packets = listOf(byteArrayOf(1), byteArrayOf(2)),
            )
            .build()
        val packets = OggReader(
            SourceReader(bytes.asAudioSource(), DecodeOptions.DEFAULT),
            DecodeOptions.DEFAULT,
        ).packets(31, 0)

        assertFalse(assertNotNull(packets.next()).isLast)
        assertTrue(assertNotNull(packets.next()).isLast)
        assertEquals(null, packets.next())
    }

    @Test
    fun startingOnAContinuedPageDiscardsOnlyTheLeadingFragment() {
        val wanted = byteArrayOf(9, 8, 7)
        val bytes = OggBuilder()
            .page(
                serial = 32,
                sequence = 7,
                granule = 10,
                flags = OggPageFlagContinued or 4,
                packets = listOf(byteArrayOf(1, 2), wanted),
            )
            .build()
        val packets = OggReader(
            SourceReader(bytes.asAudioSource(), DecodeOptions.DEFAULT),
            DecodeOptions.DEFAULT,
        ).packets(32, 0)

        val packet = assertNotNull(packets.next())
        assertContentEquals(wanted, packet.bytes.copyOf(packet.length))
        assertTrue(packet.isLast)
    }

    @Test
    fun aMissingContinuationFlagIsCorruption() {
        val bytes = OggBuilder()
            .rawPage(
                serial = 33,
                sequence = 0,
                granule = -1,
                flags = 0,
                lacing = intArrayOf(255),
                body = ByteArray(255),
            )
            .rawPage(
                serial = 33,
                sequence = 1,
                granule = 10,
                flags = 4,
                lacing = intArrayOf(1),
                body = byteArrayOf(1),
            )
            .build()
        val packets = OggReader(
            SourceReader(bytes.asAudioSource(), DecodeOptions.DEFAULT),
            DecodeOptions.DEFAULT,
        ).packets(33, 0)

        assertFailsWith<CorruptStreamException> { packets.next() }
    }

    @Test
    fun anEosPageCannotLeaveAPacketOpen() {
        val bytes = OggBuilder()
            .rawPage(
                serial = 34,
                sequence = 0,
                granule = -1,
                flags = 4,
                lacing = intArrayOf(255),
                body = ByteArray(255),
            )
            .build()
        val packets = OggReader(
            SourceReader(bytes.asAudioSource(), DecodeOptions.DEFAULT),
            DecodeOptions.DEFAULT,
        ).packets(34, 0)

        assertFailsWith<TruncatedStreamException> { packets.next() }
    }

    @Test
    fun aLaterChainReusingTheSerialDoesNotChangeDurationAndIsPreserved() {
        fun stream(title: String, duration: Long): ByteArray = OggBuilder()
            .page(serial = 35, sequence = 0, granule = 0, flags = 2, packets = listOf(vorbisId()))
            .page(
                serial = 35,
                sequence = 1,
                granule = 0,
                flags = 0,
                packets = listOf(vorbisComment("TITLE=$title")),
            )
            .page(serial = 35, sequence = 2, granule = 0, flags = 0, packets = listOf(ByteArray(30)))
            .page(serial = 35, sequence = 3, granule = duration, flags = 4, packets = listOf(ByteArray(20)))
            .build()

        val first = stream("First", 1_000)
        val second = stream("Second", 9_000)
        val chained = first + second
        assertEquals(10_000L, AudioKodec.probe(chained).durationFrames)

        val rewritten = AudioKodec.writeTags(
            chained,
            AudioKodec.readTags(chained).edit { set(TagField.TITLE, "Edited") },
        )
        assertContentEquals(second, rewritten.copyOfRange(rewritten.size - second.size, rewritten.size))
        assertEquals(10_000L, AudioKodec.probe(rewritten).durationFrames)
        assertEquals("Edited", AudioKodec.readTags(rewritten)[TagField.TITLE])
    }

    @Test
    fun oggFlacWithAnUnknownHeaderCountFollowsTheLastMetadataBit() {
        val comment = flacMetadata(
            type = 4,
            last = false,
            body = commentBody("TITLE=Unknown count"),
        )
        val padding = flacMetadata(type = 1, last = true, body = ByteArray(0))
        val bytes = OggBuilder()
            .page(serial = 36, sequence = 0, granule = 0, flags = 2, packets = listOf(oggFlacId(0)))
            .page(serial = 36, sequence = 1, granule = 0, flags = 0, packets = listOf(comment))
            .page(serial = 36, sequence = 2, granule = 0, flags = 0, packets = listOf(padding))
            .page(
                serial = 36,
                sequence = 3,
                granule = 100,
                flags = 4,
                packets = listOf(byteArrayOf(0xFF.toByte(), 0xF8.toByte())),
            )
            .build()

        assertEquals("Unknown count", AudioKodec.readTags(bytes)[TagField.TITLE])
        val rewritten = AudioKodec.writeTags(
            bytes,
            AudioKodec.readTags(bytes).edit { set(TagField.TITLE, "After") },
        )
        assertEquals("After", AudioKodec.readTags(rewritten)[TagField.TITLE])
        assertEquals(100L, AudioKodec.probe(rewritten).durationFrames)
    }

    @Test
    fun oggFlacRequiresTheCommentToBeItsFirstMetadataPacket() {
        val bytes = OggBuilder()
            .page(serial = 37, sequence = 0, granule = 0, flags = 2, packets = listOf(oggFlacId(1)))
            .page(
                serial = 37,
                sequence = 1,
                granule = 0,
                flags = 0,
                packets = listOf(flacMetadata(type = 1, last = true, body = ByteArray(0))),
            )
            .build()
        assertFailsWith<CorruptStreamException> { AudioKodec.probe(bytes) }
    }

    @Test
    fun rewritingOggFlacCanRemoveAndAddNativePicturePackets() {
        val originalPicture = io.github.yuroyami.audiokodec.tag.TagPicture(
            mimeType = "image/png",
            description = "Original",
            bytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 1, 2, 3),
            width = 16,
            height = 16,
        )
        val bytes = OggBuilder()
            .page(serial = 38, sequence = 0, granule = 0, flags = 2, packets = listOf(oggFlacId(2)))
            .page(
                serial = 38,
                sequence = 1,
                granule = 0,
                flags = 0,
                packets = listOf(
                    flacMetadata(type = 4, last = false, body = commentBody("TITLE=Pictures")),
                ),
            )
            .page(
                serial = 38,
                sequence = 2,
                granule = 0,
                flags = 0,
                packets = listOf(
                    flacMetadata(type = 6, last = true, body = buildFlacPicture(originalPicture)),
                ),
            )
            .page(
                serial = 38,
                sequence = 3,
                granule = 100,
                flags = 4,
                packets = listOf(byteArrayOf(0xFF.toByte(), 0xF8.toByte())),
            )
            .build()
        assertEquals(1, AudioKodec.readTags(bytes).pictures.size)

        val removed = AudioKodec.writeTags(
            bytes,
            AudioKodec.readTags(bytes).edit { removePictures() },
        )
        assertTrue(AudioKodec.readTags(removed).pictures.isEmpty())

        val replacement = io.github.yuroyami.audiokodec.tag.TagPicture(
            type = originalPicture.type,
            mimeType = originalPicture.mimeType,
            description = "Replacement",
            bytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 9, 8, 7),
            width = originalPicture.width,
            height = originalPicture.height,
            colourDepth = originalPicture.colourDepth,
            colourCount = originalPicture.colourCount,
        )
        val added = AudioKodec.writeTags(
            removed,
            AudioKodec.readTags(removed).edit { addPicture(replacement) },
        )
        val read = assertNotNull(AudioKodec.readTags(added).cover)
        assertEquals("Replacement", read.description)
        assertContentEquals(replacement.bytes, read.bytes)
        assertEquals(100L, AudioKodec.probe(added).durationFrames)
    }

    @Test
    fun aPageClaimingAnEnormousPayloadDoesNotAllocateIt() {
        // Every lacing value at its maximum asks for just under 64 KiB, which
        // is the largest a page can legally be. A guard tighter than that must
        // refuse it rather than allocate.
        val bytes = OggBuilder()
            .page(serial = 9, sequence = 0, granule = 0, flags = 2, packets = listOf(ByteArray(60_000)))
            .build()
        expectOnlyKiteFailures("a page of 60000 bytes under a small ceiling") {
            AudioKodec.probe(bytes, DecodeOptions(maxAllocationBytes = 4096))
        }
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

    // Building Ogg files by hand

    /** A Vorbis identification packet: 44100 Hz, two channels. */
    private fun vorbisId(): ByteArray {
        val out = ByteArray(30)
        out[0] = 0x01
        "vorbis".forEachIndexed { i, c -> out[1 + i] = c.code.toByte() }
        // Version is four zero bytes at offset 7.
        out[11] = 2
        writeIntLe(out, 12, 44_100)
        writeIntLe(out, 16, 0)
        writeIntLe(out, 20, 128_000)
        writeIntLe(out, 24, 0)
        out[28] = 0xB8.toByte()
        out[29] = 0x01
        return out
    }

    private fun vorbisComment(vararg items: String): ByteArray {
        val body = commentBody(*items)
        val out = ByteArray(7 + body.size + 1)
        out[0] = 0x03
        "vorbis".forEachIndexed { i, c -> out[1 + i] = c.code.toByte() }
        body.copyInto(out, 7)
        out[out.size - 1] = 1
        return out
    }

    private fun opusHead(preSkip: Int): ByteArray {
        val out = ByteArray(19)
        "OpusHead".forEachIndexed { i, c -> out[i] = c.code.toByte() }
        out[8] = 1
        out[9] = 2
        out[10] = preSkip.toByte()
        out[11] = (preSkip ushr 8).toByte()
        writeIntLe(out, 12, 48_000)
        return out
    }

    private fun opusTags(vararg items: String): ByteArray {
        val body = commentBody(*items)
        val out = ByteArray(8 + body.size)
        "OpusTags".forEachIndexed { i, c -> out[i] = c.code.toByte() }
        body.copyInto(out, 8)
        return out
    }

    private fun oggFlacId(headerPackets: Int): ByteArray {
        val out = ByteArray(51)
        out[0] = 0x7F
        "FLAC".forEachIndexed { i, c -> out[1 + i] = c.code.toByte() }
        out[5] = 1
        out[7] = (headerPackets ushr 8).toByte()
        out[8] = headerPackets.toByte()
        "fLaC".forEachIndexed { i, c -> out[9 + i] = c.code.toByte() }
        out[13] = 0
        out[16] = 34
        // STREAMINFO: valid block sizes and a packed 44.1 kHz, stereo, 16-bit
        // stream shape. The remaining fields may be unknown/zero.
        out[17] = 0x10
        out[18] = 0x00
        out[19] = 0x10
        out[20] = 0x00
        val packed = (44_100L shl 44) or (1L shl 41) or (15L shl 36) or 100L
        for (i in 0 until 8) out[27 + i] = (packed ushr (56 - i * 8)).toByte()
        return out
    }

    private fun flacMetadata(type: Int, last: Boolean, body: ByteArray): ByteArray {
        val out = ByteArray(4 + body.size)
        out[0] = (type or if (last) 0x80 else 0).toByte()
        out[1] = (body.size ushr 16).toByte()
        out[2] = (body.size ushr 8).toByte()
        out[3] = body.size.toByte()
        body.copyInto(out, 4)
        return out
    }

    private fun theoraId(): ByteArray {
        val out = ByteArray(42)
        out[0] = 0x80.toByte()
        "theora".forEachIndexed { i, c -> out[1 + i] = c.code.toByte() }
        return out
    }

    private fun flacPictureComment(picture: io.github.yuroyami.audiokodec.tag.TagPicture): String {
        // The picture structure FLAC defines, in base64, which is how Ogg
        // carries cover art.
        val structure = buildFlacPicture(picture)
        return "METADATA_BLOCK_PICTURE=" + base64(structure)
    }

    private fun buildFlacPicture(picture: io.github.yuroyami.audiokodec.tag.TagPicture): ByteArray {
        val mime = picture.mimeType.encodeToByteArray()
        val description = picture.description.encodeToByteArray()
        val out = ByteArray(32 + mime.size + description.size + picture.bytes.size)
        var at = 0
        fun writeIntBe(value: Int) {
            out[at] = (value ushr 24).toByte()
            out[at + 1] = (value ushr 16).toByte()
            out[at + 2] = (value ushr 8).toByte()
            out[at + 3] = value.toByte()
            at += 4
        }
        writeIntBe(picture.type.ordinal)
        writeIntBe(mime.size)
        mime.copyInto(out, at); at += mime.size
        writeIntBe(description.size)
        description.copyInto(out, at); at += description.size
        writeIntBe(picture.width)
        writeIntBe(picture.height)
        writeIntBe(picture.colourDepth)
        writeIntBe(picture.colourCount)
        writeIntBe(picture.bytes.size)
        picture.bytes.copyInto(out, at)
        return out
    }

    private fun base64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder()
        var i = 0
        while (i + 2 < bytes.size) {
            val v = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i + 2].toInt() and 0xFF)
            out.append(alphabet[(v shr 18) and 0x3F]).append(alphabet[(v shr 12) and 0x3F])
            out.append(alphabet[(v shr 6) and 0x3F]).append(alphabet[v and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val v = (bytes[i].toInt() and 0xFF) shl 16
                out.append(alphabet[(v shr 18) and 0x3F]).append(alphabet[(v shr 12) and 0x3F])
                out.append("==")
            }
            2 -> {
                val v = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(alphabet[(v shr 18) and 0x3F]).append(alphabet[(v shr 12) and 0x3F])
                out.append(alphabet[(v shr 6) and 0x3F]).append('=')
            }
        }
        return out.toString()
    }

    private fun commentBody(vararg items: String): ByteArray {
        val vendor = "AudioKodec tests".encodeToByteArray()
        val encoded = items.map { it.encodeToByteArray() }
        var size = 4 + vendor.size + 4
        for (item in encoded) size += 4 + item.size
        val out = ByteArray(size)
        var at = 0
        fun writeLength(value: Int) {
            out[at] = value.toByte()
            out[at + 1] = (value ushr 8).toByte()
            out[at + 2] = (value ushr 16).toByte()
            out[at + 3] = (value ushr 24).toByte()
            at += 4
        }
        writeLength(vendor.size)
        vendor.copyInto(out, at); at += vendor.size
        writeLength(encoded.size)
        for (item in encoded) {
            writeLength(item.size)
            item.copyInto(out, at); at += item.size
        }
        return out
    }

    private fun writeIntLe(out: ByteArray, at: Int, value: Int) {
        out[at] = value.toByte()
        out[at + 1] = (value ushr 8).toByte()
        out[at + 2] = (value ushr 16).toByte()
        out[at + 3] = (value ushr 24).toByte()
    }
}

/** Assembles Ogg pages with correct lacing and checksums. */
internal class OggBuilder {

    private val pages = ArrayList<ByteArray>()

    fun page(
        serial: Int,
        sequence: Int,
        granule: Long,
        flags: Int,
        packets: List<ByteArray>,
    ): OggBuilder {
        val lacing = ArrayList<Int>()
        var payloadSize = 0
        for (packet in packets) {
            var left = packet.size
            while (left >= 255) {
                lacing.add(255)
                left -= 255
            }
            lacing.add(left)
            payloadSize += packet.size
        }
        val body = ByteArray(payloadSize)
        var at = 0
        for (packet in packets) {
            packet.copyInto(body, at)
            at += packet.size
        }
        pages.add(assemble(serial, sequence, granule, flags, lacing.toIntArray(), body))
        return this
    }

    /** Splits one packet across pages, which is what a long comment needs. */
    fun splitPacket(
        serial: Int,
        startSequence: Int,
        packet: ByteArray,
        pieceSize: Int,
    ): OggBuilder {
        var offset = 0
        var sequence = startSequence
        while (offset < packet.size) {
            val remaining = packet.size - offset
            val take = minOf(pieceSize, remaining)
            val isFinal = offset + take >= packet.size
            // A page that continues a packet ends with a lacing value of 255,
            // and the page that finishes it ends with anything else.
            val lacing = if (isFinal) {
                var left = take
                val values = ArrayList<Int>()
                while (left >= 255) {
                    values.add(255)
                    left -= 255
                }
                values.add(left)
                values.toIntArray()
            } else {
                IntArray(take / 255) { 255 }
            }
            val used = if (isFinal) take else lacing.size * 255
            if (used == 0) break
            val body = packet.copyOfRange(offset, offset + used)
            pages.add(
                assemble(
                    serial,
                    sequence,
                    if (isFinal) 0L else -1L,
                    if (offset == 0) 0 else OggPageFlagContinued,
                    lacing,
                    body,
                ),
            )
            offset += used
            sequence++
        }
        return this
    }

    fun rawPage(
        serial: Int,
        sequence: Int,
        granule: Long,
        flags: Int,
        lacing: IntArray,
        body: ByteArray,
    ): OggBuilder {
        require(lacing.sum() == body.size)
        pages.add(assemble(serial, sequence, granule, flags, lacing, body))
        return this
    }

    fun build(): ByteArray {
        var size = 0
        for (page in pages) size += page.size
        val out = ByteArray(size)
        var at = 0
        for (page in pages) {
            page.copyInto(out, at)
            at += page.size
        }
        return out
    }

    private fun assemble(
        serial: Int,
        sequence: Int,
        granule: Long,
        flags: Int,
        lacing: IntArray,
        body: ByteArray,
    ): ByteArray {
        val out = ByteArray(27 + lacing.size + body.size)
        "OggS".forEachIndexed { i, c -> out[i] = c.code.toByte() }
        out[4] = 0
        out[5] = flags.toByte()
        for (i in 0 until 8) out[6 + i] = (granule ushr (8 * i)).toByte()
        for (i in 0 until 4) out[14 + i] = (serial ushr (8 * i)).toByte()
        for (i in 0 until 4) out[18 + i] = (sequence ushr (8 * i)).toByte()
        out[26] = lacing.size.toByte()
        for (i in lacing.indices) out[27 + i] = lacing[i].toByte()
        body.copyInto(out, 27 + lacing.size)

        val crc = crc32(out)
        for (i in 0 until 4) out[22 + i] = (crc ushr (8 * i)).toByte()
        return out
    }

    /** The Ogg variant: polynomial 0x04C11DB7, no reflection, no final xor. */
    private fun crc32(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 24)
            repeat(8) {
                crc = if (crc and 0x8000_0000.toInt() != 0) {
                    (crc shl 1) xor 0x04C1_1DB7
                } else {
                    crc shl 1
                }
            }
        }
        return crc
    }
}

private const val OggPageFlagContinued = 0x01

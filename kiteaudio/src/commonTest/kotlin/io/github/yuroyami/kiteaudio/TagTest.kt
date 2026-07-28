package io.github.yuroyami.kiteaudio

import io.github.yuroyami.kiteaudio.internal.tag.ApeTag
import io.github.yuroyami.kiteaudio.tag.PictureType
import io.github.yuroyami.kiteaudio.tag.ReplayGain
import io.github.yuroyami.kiteaudio.tag.TagBundle
import io.github.yuroyami.kiteaudio.tag.TagChapter
import io.github.yuroyami.kiteaudio.tag.TagField
import io.github.yuroyami.kiteaudio.tag.TagFormat
import io.github.yuroyami.kiteaudio.tag.TagPicture
import io.github.yuroyami.kiteaudio.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagTest {

    @Test
    fun id3v24GlobalUnsynchronisationAppliesToEveryFrame() {
        val storedText = byteArrayOf(
            0, 'A'.code.toByte(), 0xFF.toByte(), 0, 0xE1.toByte(), 'B'.code.toByte(),
        )
        val bodySize = 10 + storedText.size
        val tag = ByteArray(10 + bodySize)
        "ID3".forEachIndexed { index, char -> tag[index] = char.code.toByte() }
        tag[3] = 4
        tag[5] = 0x80.toByte()
        writeSyncsafe(tag, 6, bodySize)
        "TIT2".forEachIndexed { index, char -> tag[10 + index] = char.code.toByte() }
        writeSyncsafe(tag, 14, storedText.size)
        storedText.copyInto(tag, 20)

        assertEquals("A\u00FF\u00E1B", KiteAudio.readTags(tag)[TagField.TITLE])
    }

    @Test
    fun encryptedAndInvalidlyEncodedId3FramesAreNotReadAsPlainText() {
        fun tag(major: Int, flags: Int, frameBody: ByteArray): ByteArray {
            val bodySize = 10 + frameBody.size
            return ByteArray(10 + bodySize).also { out ->
                "ID3".forEachIndexed { index, char -> out[index] = char.code.toByte() }
                out[3] = major.toByte()
                writeSyncsafe(out, 6, bodySize)
                "TIT2".forEachIndexed { index, char -> out[10 + index] = char.code.toByte() }
                if (major == 4) {
                    writeSyncsafe(out, 14, frameBody.size)
                } else {
                    out[14] = (frameBody.size ushr 24).toByte()
                    out[15] = (frameBody.size ushr 16).toByte()
                    out[16] = (frameBody.size ushr 8).toByte()
                    out[17] = frameBody.size.toByte()
                }
                out[19] = flags.toByte()
                frameBody.copyInto(out, 20)
            }
        }

        // The first byte is the encryption-method identifier. What follows
        // happens to look like a valid Latin-1 text frame but is ciphertext.
        val encrypted = tag(4, 0x04, byteArrayOf(7, 0, 'N'.code.toByte(), 'o'.code.toByte()))
        assertNull(KiteAudio.readTags(encrypted)[TagField.TITLE])

        // UTF-8 (encoding 3) was introduced in ID3v2.4 and is reserved in 2.3.
        val invalidV23 = tag(3, 0, byteArrayOf(3, 'N'.code.toByte(), 'o'.code.toByte()))
        assertNull(KiteAudio.readTags(invalidV23)[TagField.TITLE])
    }

    @Test
    fun anId3FooterMustMirrorItsHeader() {
        val tag = ByteArray(20)
        "ID3".forEachIndexed { index, char -> tag[index] = char.code.toByte() }
        tag[3] = 4
        tag[5] = 0x10
        // The body size is zero, so the remaining ten bytes are the claimed
        // footer. Leaving them zero makes the mirrored signature invalid.
        assertTrue(KiteAudio.readTags(tag).raw.blocks.isEmpty())
    }

    @Test
    fun stackedLeadingId3BlocksAreAllRead() {
        val firstFile = taggedStream(
            TagBundle(mapOf(TagField.ARTIST to listOf("First block"))),
        )
        val firstSize = 10 + readSyncsafe(firstFile, 6)
        val secondFile = taggedStream(
            TagBundle(mapOf(TagField.TITLE to listOf("Second block"))),
        )
        val stacked = firstFile.copyOf(firstSize) + secondFile

        val tags = KiteAudio.readTags(stacked)
        assertEquals(2, tags.raw.blocksOf(TagFormat.ID3V2_4).size)
        assertEquals("First block", tags[TagField.ARTIST])
        assertEquals("Second block", tags[TagField.TITLE])
    }

    @Test
    fun aLongStackOfLeadingId3BlocksIsNotSilentlyCutOff() {
        val empty = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            4, 0, 0, 0, 0, 0, 0,
        )
        val titledFile = taggedStream(
            TagBundle(mapOf(TagField.TITLE to listOf("After the long stack"))),
        )
        val titledSize = 10 + readSyncsafe(titledFile, 6)
        val wave = KiteAudio.encode(
            FloatArray(1),
            AudioFormat(44_100, 1),
            EncodeTarget.wav(),
        )
        val prefixSize = 300 * empty.size + titledSize
        val file = ByteArray(prefixSize + wave.size)
        var position = 0
        repeat(300) {
            empty.copyInto(file, position)
            position += empty.size
        }
        titledFile.copyInto(file, position, endIndex = titledSize)
        wave.copyInto(file, prefixSize)

        val tags = KiteAudio.readTags(file)
        assertEquals(301, tags.raw.blocksOf(TagFormat.ID3V2_4).size)
        assertEquals("After the long stack", tags[TagField.TITLE])
        val info = KiteAudio.probe(file)
        assertEquals(AudioContainer.WAV, info.container)
        assertTrue(info.dataOffset >= prefixSize)
    }

    @Test
    fun moreThanThirtyTwoTrailingBlocksAreAllFound() {
        var file = ByteArray(64)
        repeat(40) { index ->
            file += Id3v1Bytes(
                title = "Title $index",
                artist = "",
                album = "",
                year = "",
                comment = "",
                track = null,
                genre = 255,
            )
        }
        assertEquals(40, KiteAudio.readTags(file).raw.blocksOf(TagFormat.ID3V1).size)
    }

    @Test
    fun leadingTagsCanBeReadWhenTheSourceDoesNotStateItsSize() {
        val file = taggedStream(
            TagBundle(mapOf(TagField.TITLE to listOf("Unknown-length source"))),
        )
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
        assertEquals("Unknown-length source", KiteAudio.readTags(source)[TagField.TITLE])
    }

    @Test
    fun anId3PrefixDoesNotHideMetadataInsideTheFollowingContainer() {
        val wave = KiteAudio.writeTags(
            KiteAudio.encode(
                TestSignals.sine(200, 1),
                AudioFormat(44_100, 1),
                EncodeTarget.wav(),
            ),
            TagBundle(mapOf(TagField.ALBUM to listOf("Inside RIFF"))),
        )
        val prefixFile = taggedStream(
            TagBundle(mapOf(TagField.TITLE to listOf("Leading ID3"))),
        )
        val prefixSize = 10 + readSyncsafe(prefixFile, 6)
        val combined = prefixFile.copyOf(prefixSize) + wave

        val tags = KiteAudio.readTags(combined)
        assertEquals("Leading ID3", tags[TagField.TITLE])
        assertEquals("Inside RIFF", tags[TagField.ALBUM])
        assertTrue(tags.raw.blocks.any { it.format == TagFormat.RIFF_INFO })
    }

    @Test
    fun rewritingAPrefixedContainerUpdatesBothTagsAndKeepsTheAudio() {
        val samples = TestSignals.quantise(TestSignals.sine(400, 1), 16)
        val wave = KiteAudio.writeTags(
            KiteAudio.encode(samples, AudioFormat(44_100, 1), EncodeTarget.wav()),
            TagBundle(mapOf(TagField.ALBUM to listOf("Before album"))),
        )
        val prefixFile = taggedStream(
            TagBundle(mapOf(TagField.TITLE to listOf("Before title"))),
        )
        val prefixSize = 10 + readSyncsafe(prefixFile, 6)
        val prefixed = prefixFile.copyOf(prefixSize) + wave

        val rewritten = KiteAudio.writeTags(
            prefixed,
            KiteAudio.readTags(prefixed).edit {
                set(TagField.TITLE, "After title")
                set(TagField.ALBUM, "After album")
            },
        )
        val tags = KiteAudio.readTags(rewritten)
        assertEquals("After title", tags[TagField.TITLE])
        assertEquals("After album", tags[TagField.ALBUM])
        assertTrue(tags.raw.blocks.any { it.format == TagFormat.ID3V2_4 })
        assertTrue(tags.raw.blocks.any { it.format == TagFormat.RIFF_INFO })
        TestSignals.assertExact(samples, KiteAudio.decode(rewritten).samples)
    }

    @Test
    fun aTrailingApeTagLargerThanTheOldTailWindowIsReadByItsDeclaredSize() {
        val blob = ByteArray(1_100_000) { ((it * 17) and 0xFF).toByte() }
        val ape = ApeTag.write(listOf("Large Binary" to blob), setOf("Large Binary"))
        val audio = ByteArray(300)
        audio[0] = 0xFF.toByte()
        audio[1] = 0xFB.toByte()
        val file = audio + ape

        val block = assertNotNull(
            KiteAudio.readTags(file).raw.blocks.firstOrNull { it.format == TagFormat.APEV2 },
        )
        assertEquals(blob.size, block.entries.single { it.key == "Large Binary" }.binary?.size)
        assertFailsWith<AllocationGuardException> {
            KiteAudio.readTags(file, DecodeOptions(maxAllocationBytes = 100_000))
        }
    }

    @Test
    fun apeReservedBitsBytesKeysAndMalformedUtf8AreRejected() {
        val original = ApeTag.write(listOf("Title" to "valid".encodeToByteArray()))

        val itemFlags = original.copyOf()
        itemFlags[ApeTag.FOOTER_SIZE + 4] = 0x08
        assertNull(ApeTag.findEndingAt(itemFlags, itemFlags.size, DecodeOptions.DEFAULT))

        val headerReserved = original.copyOf()
        headerReserved[24] = 1
        assertNull(ApeTag.findEndingAt(headerReserved, headerReserved.size, DecodeOptions.DEFAULT))

        val footerReserved = original.copyOf()
        footerReserved[footerReserved.size - 8] = 1
        assertNull(ApeTag.findEndingAt(footerReserved, footerReserved.size, DecodeOptions.DEFAULT))

        val malformedText = ApeTag.write(
            listOf("Title" to byteArrayOf(0xC0.toByte(), 0x80.toByte())),
        )
        assertNull(ApeTag.findEndingAt(malformedText, malformedText.size, DecodeOptions.DEFAULT))

        val impossibleCount = original.copyOf()
        for (index in 0 until 4) {
            val byte = if (index == 3) 0x7F else 0xFF
            impossibleCount[16 + index] = byte.toByte()
            impossibleCount[impossibleCount.size - 16 + index] = byte.toByte()
        }
        assertNull(ApeTag.findEndingAt(impossibleCount, impossibleCount.size, DecodeOptions.DEFAULT))

        assertFailsWith<IllegalArgumentException> {
            ApeTag.write(listOf("TAG" to "forbidden".encodeToByteArray()))
        }
    }

    // Reading

    @Test
    fun anId3v2Point4BlockReadsBackEveryFieldItHolds() {
        val original = TagBundle(
            fields = mapOf(
                TagField.TITLE to listOf("A Title"),
                TagField.ARTIST to listOf("An Artist"),
                TagField.ALBUM to listOf("An Album"),
                TagField.ALBUM_ARTIST to listOf("The Album Artist"),
                TagField.TRACK_NUMBER to listOf("3"),
                TagField.TRACK_TOTAL to listOf("12"),
                TagField.DISC_NUMBER to listOf("1"),
                TagField.DISC_TOTAL to listOf("2"),
                TagField.DATE to listOf("2026-07-26"),
                TagField.GENRE to listOf("Ambient"),
                TagField.COMPOSER to listOf("A Composer"),
                TagField.COMMENT to listOf("A comment"),
                TagField.BPM to listOf("128"),
                TagField.ISRC to listOf("GBAYE0000123"),
                TagField.CATALOG_NUMBER to listOf("CAT-001"),
            ),
        )
        val file = taggedStream(original)
        val read = KiteAudio.readTags(file)

        assertEquals("A Title", read[TagField.TITLE])
        assertEquals("An Artist", read[TagField.ARTIST])
        assertEquals("An Album", read[TagField.ALBUM])
        assertEquals("The Album Artist", read[TagField.ALBUM_ARTIST])
        assertEquals("3", read[TagField.TRACK_NUMBER])
        assertEquals("12", read[TagField.TRACK_TOTAL])
        assertEquals("1", read[TagField.DISC_NUMBER])
        assertEquals("2", read[TagField.DISC_TOTAL])
        assertEquals("2026-07-26", read[TagField.DATE])
        assertEquals("Ambient", read[TagField.GENRE])
        assertEquals("A Composer", read[TagField.COMPOSER])
        assertEquals("A comment", read[TagField.COMMENT])
        assertEquals(128, read.number(TagField.BPM))
        assertEquals("GBAYE0000123", read[TagField.ISRC])
        // A field with no frame of its own goes into a user text frame and
        // has to come back from one.
        assertEquals("CAT-001", read[TagField.CATALOG_NUMBER])
        assertEquals(TagFormat.ID3V2_4, read.raw.blocks.first().format)
    }

    @Test
    fun textOutsideLatin1SurvivesBothId3Versions() {
        val awkward = "Björk · Jóga 日本語 🎵"
        for (version in intArrayOf(3, 4)) {
            val tags = TagBundle(mapOf(TagField.TITLE to listOf(awkward)))
            val file = taggedStream(tags, version)
            val read = KiteAudio.readTags(file)
            assertEquals(awkward, read[TagField.TITLE], "ID3v2.$version")
        }
    }

    @Test
    fun latin1TextIsWrittenAsLatin1SoOldPlayersCanReadIt() {
        val tags = TagBundle(mapOf(TagField.TITLE to listOf("Plain Title")))
        val file = taggedStream(tags)
        // The first byte of a text frame body is the encoding, and zero is
        // ISO-8859-1. Finding it proves the writer did not reach for UTF-8
        // when it did not have to.
        val at = file.indexOfBytes("TIT2".encodeToByteArray())
        assertTrue(at > 0, "the title frame is missing")
        assertEquals(0, file[at + 10].toInt(), "plain text should be written as ISO-8859-1")
    }

    @Test
    fun aVersion24FrameCanHoldSeveralValues() {
        val tags = TagBundle(mapOf(TagField.ARTIST to listOf("First", "Second", "Third")))
        val read = KiteAudio.readTags(taggedStream(tags, 4))
        assertEquals(listOf("First", "Second", "Third"), read.all(TagField.ARTIST))
    }

    @Test
    fun aVersion23FileWritesOneFramePerValue() {
        val tags = TagBundle(mapOf(TagField.ARTIST to listOf("First", "Second")))
        val read = KiteAudio.readTags(taggedStream(tags, 3))
        assertEquals(listOf("First", "Second"), read.all(TagField.ARTIST))
    }

    @Test
    fun aPictureSurvivesBeingWrittenAndReadBack() {
        val image = ByteArray(2048) { (it * 31).toByte() }
        // A JPEG signature, so the type sniffing has something real to find.
        image[0] = 0xFF.toByte()
        image[1] = 0xD8.toByte()
        val tags = TagBundle(
            fields = mapOf(TagField.TITLE to listOf("With art")),
            pictures = listOf(
                TagPicture(
                    type = PictureType.FRONT_COVER,
                    mimeType = "image/jpeg",
                    description = "Cover",
                    bytes = image,
                ),
            ),
        )
        val read = KiteAudio.readTags(taggedStream(tags))
        val picture = assertNotNull(read.cover, "the picture did not survive")
        assertEquals("image/jpeg", picture.mimeType)
        assertEquals("Cover", picture.description)
        assertEquals(PictureType.FRONT_COVER, picture.type)
        assertContentEquals(image, picture.bytes)
    }

    @Test
    fun replayGainSurvivesTheTripThroughUserTextFrames() {
        val tags = TagBundle(
            fields = mapOf(TagField.TITLE to listOf("Loud")),
            replayGain = ReplayGain(
                trackGainDb = -7.35,
                trackPeak = 0.988,
                albumGainDb = -6.5,
                albumPeak = 1.0,
            ),
        )
        val read = KiteAudio.readTags(taggedStream(tags))
        val gain = assertNotNull(read.replayGain)
        assertEquals(-7.35, gain.trackGainDb!!, 0.005)
        assertEquals(0.988, gain.trackPeak!!, 0.0005)
        assertEquals(-6.5, gain.albumGainDb!!, 0.005)
        assertTrue(!gain.isR128, "these values are ReplayGain, not R128")
    }

    @Test
    fun theOpusLoudnessConventionIsNotConfusedWithReplayGain() {
        // R128_TRACK_GAIN holds 256ths of a decibel against a different
        // reference, and reading it as ReplayGain would be five decibels out.
        val block = vorbisLikeApe(listOf("R128_TRACK_GAIN" to "-1536"))
        val read = KiteAudio.readTags(block)
        val gain = assertNotNull(read.replayGain)
        assertEquals(-6.0, gain.trackGainDb!!, 0.001)
        assertTrue(gain.isR128, "an R128 value has to be marked as one")
    }

    @Test
    fun anId3v1BlockIsReadWhenThereIsNothingBetter() {
        val file = ByteArray(1000) + Id3v1Bytes(
            title = "Short Title",
            artist = "Short Artist",
            album = "Short Album",
            year = "1999",
            comment = "Hi",
            track = 7,
            genre = 17,
        )
        val read = KiteAudio.readTags(file)
        assertEquals("Short Title", read[TagField.TITLE])
        assertEquals("Short Artist", read[TagField.ARTIST])
        assertEquals("Short Album", read[TagField.ALBUM])
        assertEquals("1999", read[TagField.DATE])
        assertEquals("Hi", read[TagField.COMMENT])
        assertEquals(7, read.number(TagField.TRACK_NUMBER))
        assertEquals("Rock", read[TagField.GENRE])
    }

    @Test
    fun anId3v2BlockBeatsAnId3v1BlockThatDisagrees() {
        val modern = TagBundle(
            fields = mapOf(
                TagField.TITLE to listOf("The Real Title"),
                TagField.ARTIST to listOf("The Real Artist"),
            ),
        )
        val file = taggedStream(modern) + Id3v1Bytes(
            title = "Stale Title",
            artist = "Stale Artist",
            album = "Stale Album",
            year = "1990",
            comment = "",
            track = null,
            genre = 255,
        )
        val read = KiteAudio.readTags(file)
        assertEquals("The Real Title", read[TagField.TITLE])
        assertEquals("The Real Artist", read[TagField.ARTIST])
        // The older block still contributes what the newer one did not say.
        assertEquals("Stale Album", read[TagField.ALBUM])
        assertEquals(2, read.raw.blocks.size, "both blocks should be reported")
    }

    @Test
    fun theGenreTableIsUsedWhereverAFileRefersToIt() {
        val cases = mapOf(
            "(17)" to "Rock",
            "(17)Rock" to "Rock",
            "17" to "Rock",
            "Rock" to "Rock",
            "(4)Eurodisco" to "Eurodisco",
            "Some Unlisted Genre" to "Some Unlisted Genre",
            "(RX)" to "Remix",
        )
        for ((stored, expected) in cases) {
            val read = KiteAudio.readTags(taggedStream(TagBundle(mapOf(TagField.GENRE to listOf(stored)))))
            assertEquals(expected, read[TagField.GENRE], "genre '$stored'")
        }
    }

    @Test
    fun anEmptyFileHasNoTagsAndDoesNotFail() {
        assertTrue(KiteAudio.readTags(ByteArray(0)).isEmpty)
        assertTrue(KiteAudio.readTags(ByteArray(5000)).isEmpty)
    }

    // Writing

    @Test
    fun writingTagsIntoAWaveFileLeavesTheAudioUntouched() {
        val format = AudioFormat(44_100, 2)
        val samples = TestSignals.quantise(TestSignals.sine(2_000, 2), 16)
        val file = KiteAudio.encode(samples, format, EncodeTarget.wav(16))

        val tagged = KiteAudio.writeTags(
            file,
            TagBundle(
                mapOf(
                    TagField.TITLE to listOf("Tagged"),
                    TagField.ARTIST to listOf("Someone"),
                    TagField.GENRE to listOf("Ambient"),
                    TagField.COMMENT to listOf("Written by KiteAudio"),
                ),
            ),
        )

        // The decoded audio has to be identical, sample for sample.
        val before = KiteAudio.decode(file)
        val after = KiteAudio.decode(tagged)
        assertEquals(before.frames, after.frames)
        TestSignals.assertExact(before.samples, after.samples, "audio changed while tagging")

        val read = KiteAudio.readTags(tagged)
        assertEquals("Tagged", read[TagField.TITLE])
        assertEquals("Someone", read[TagField.ARTIST])
        assertEquals("Ambient", read[TagField.GENRE])
        assertEquals("Written by KiteAudio", read[TagField.COMMENT])
        assertEquals(TagFormat.RIFF_INFO, read.raw.blocks.first().format)
    }

    @Test
    fun anExplicitTagFormatSetIsTheCompleteWritableSelection() {
        val samples = TestSignals.quantise(TestSignals.sine(300, 1), 16)
        val file = KiteAudio.encode(samples, AudioFormat(44_100, 1), EncodeTarget.wav(16))
        val tags = TagBundle(mapOf(TagField.TITLE to listOf("Selected")))

        val id3Only = KiteAudio.writeTags(
            file,
            tags,
            TagWriteOptions(formats = setOf(TagFormat.ID3V2_3)),
        )
        val id3Blocks = KiteAudio.readTags(id3Only).raw.blocks
        assertTrue(id3Blocks.any { it.format == TagFormat.ID3V2_3 })
        assertTrue(id3Blocks.none { it.format == TagFormat.ID3V2_4 })
        assertTrue(id3Blocks.none { it.format == TagFormat.RIFF_INFO })

        val both = KiteAudio.writeTags(
            id3Only,
            tags,
            TagWriteOptions(
                formats = setOf(TagFormat.RIFF_INFO, TagFormat.ID3V2_4),
            ),
        )
        val bothBlocks = KiteAudio.readTags(both).raw.blocks
        assertTrue(bothBlocks.any { it.format == TagFormat.RIFF_INFO })
        assertTrue(bothBlocks.any { it.format == TagFormat.ID3V2_4 })
        assertTrue(bothBlocks.none { it.format == TagFormat.ID3V2_3 })

        val stripped = KiteAudio.writeTags(
            both,
            TagBundle.EMPTY,
            TagWriteOptions(formats = emptySet()),
        )
        assertTrue(KiteAudio.readTags(stripped).raw.blocks.isEmpty())
        TestSignals.assertExact(samples, KiteAudio.decode(stripped).samples)

        assertFailsWith<UnsupportedFeatureException> {
            KiteAudio.writeTags(
                file,
                tags,
                TagWriteOptions(formats = setOf(TagFormat.APEV2)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TagWriteOptions(
                formats = setOf(TagFormat.ID3V2_3, TagFormat.ID3V2_4),
            )
        }
    }

    @Test
    fun anExplicitEmptyFormatSetRemovesFlacTagsWithoutChangingAudio() {
        val samples = TestSignals.quantise(TestSignals.sine(300, 1), 16)
        val file = KiteAudio.encode(samples, AudioFormat(44_100, 1), EncodeTarget.flac(16, 5))
        val tagged = KiteAudio.writeTags(
            file,
            TagBundle(mapOf(TagField.TITLE to listOf("Remove me"))),
        )
        assertEquals("Remove me", KiteAudio.readTags(tagged)[TagField.TITLE])

        val stripped = KiteAudio.writeTags(
            tagged,
            TagBundle.EMPTY,
            TagWriteOptions(formats = emptySet()),
        )
        assertTrue(
            KiteAudio.readTags(stripped).raw.blocks.none {
                it.format == TagFormat.VORBIS_COMMENT
            },
        )
        TestSignals.assertExact(samples, KiteAudio.decode(stripped).samples)
    }

    @Test
    fun tagsCanBeReplacedWithoutTheFileGrowingEachTime() {
        val format = AudioFormat(44_100, 1)
        val file = KiteAudio.encode(
            TestSignals.quantise(TestSignals.sine(500, 1), 16), format, EncodeTarget.wav(16),
        )
        var current = KiteAudio.writeTags(file, TagBundle(mapOf(TagField.TITLE to listOf("One"))))
        val afterFirst = current.size
        repeat(5) {
            current = KiteAudio.writeTags(current, TagBundle(mapOf(TagField.TITLE to listOf("One"))))
        }
        assertEquals(afterFirst, current.size, "rewriting the same tags should not grow the file")
        assertEquals("One", KiteAudio.readTags(current)[TagField.TITLE])
    }

    @Test
    fun writingTagsIntoAnAiffFileLeavesTheAudioUntouched() {
        val format = AudioFormat(44_100, 2)
        val samples = TestSignals.quantise(TestSignals.sine(1_000, 2), 16)
        val file = KiteAudio.encode(samples, format, EncodeTarget.aiff(16))

        val tagged = KiteAudio.writeTags(
            file,
            TagBundle(
                mapOf(
                    TagField.TITLE to listOf("An AIFF"),
                    TagField.ARTIST to listOf("A Player"),
                    TagField.COPYRIGHT to listOf("Public domain"),
                ),
            ),
        )

        val before = KiteAudio.decode(file)
        val after = KiteAudio.decode(tagged)
        TestSignals.assertExact(before.samples, after.samples, "audio changed while tagging")

        val read = KiteAudio.readTags(tagged)
        assertEquals("An AIFF", read[TagField.TITLE])
        assertEquals("A Player", read[TagField.ARTIST])
        assertEquals("Public domain", read[TagField.COPYRIGHT])
    }

    @Test
    fun editingKeepsTheFieldsItWasNotToldToChange() {
        val file = taggedStream(
            TagBundle(
                mapOf(
                    TagField.TITLE to listOf("Before"),
                    TagField.ARTIST to listOf("Keep Me"),
                    TagField.COMMENT to listOf("Delete me"),
                ),
            ),
        )
        val edited = KiteAudio.readTags(file).edit {
            set(TagField.TITLE, "After")
            remove(TagField.COMMENT)
            set(TagField.ALBUM, "New Album")
        }
        val written = KiteAudio.writeTags(file, edited)
        val read = KiteAudio.readTags(written)
        assertEquals("After", read[TagField.TITLE])
        assertEquals("Keep Me", read[TagField.ARTIST])
        assertEquals("New Album", read[TagField.ALBUM])
        assertNull(read[TagField.COMMENT])
    }

    @Test
    fun editingKeepsAnUnknownId3Frame() {
        val file = taggedStream(TagBundle(mapOf(TagField.TITLE to listOf("Before"))))
        val payload = "private-owner\u0000private-value".encodeToByteArray()
        val tagEnd = 10 + readSyncsafe(file, 6)
        var position = 10
        while (position + 10 <= tagEnd && file[position].toInt() != 0) {
            position += 10 + readSyncsafe(file, position + 4)
        }
        assertTrue(position + 10 + payload.size <= tagEnd, "the test tag has no padding for PRIV")
        "PRIV".forEachIndexed { index, c -> file[position + index] = c.code.toByte() }
        writeSyncsafe(file, position + 4, payload.size)
        payload.copyInto(file, position + 10)

        val edited = KiteAudio.readTags(file).edit { set(TagField.TITLE, "After") }
        val written = KiteAudio.writeTags(file, edited)
        val privateFrame = assertNotNull(
            KiteAudio.readTags(written).raw.blocks
                .flatMap { it.entries }
                .firstOrNull { it.key == "PRIV" },
            "the private frame disappeared",
        )
        assertContentEquals(payload, privateFrame.binary)
    }

    @Test
    fun chaptersAreWrittenBackIntoId3() {
        val file = taggedStream(
            TagBundle(
                fields = mapOf(TagField.TITLE to listOf("With chapters")),
                chapters = listOf(
                    TagChapter("chapter-1", 1_250, 9_500, "Opening"),
                    TagChapter("chapter-2", 9_500, -1, "Ending"),
                ),
            ),
        )
        val chapters = KiteAudio.readTags(file).chapters
        assertEquals(2, chapters.size)
        assertEquals("chapter-1", chapters[0].id)
        assertEquals(1_250, chapters[0].startMillis)
        assertEquals(9_500, chapters[0].endMillis)
        assertEquals("Opening", chapters[0].title)
        assertEquals("Ending", chapters[1].title)
    }

    @Test
    fun editingAnApeTaggedStreamKeepsApeAndUnknownItems() {
        val audio = ByteArray(600) { ((it * 19 + 3) and 0xFF).toByte() }
        audio[0] = 0xFF.toByte()
        audio[1] = 0xFB.toByte()
        val file = audio + ApeBytes(
            listOf(
                "Title" to "Before".encodeToByteArray(),
                "Application State" to "keep-this".encodeToByteArray(),
            ),
        )
        val edited = KiteAudio.readTags(file).edit { set(TagField.TITLE, "After") }
        val written = KiteAudio.writeTags(file, edited)
        val tags = KiteAudio.readTags(written)

        assertEquals("After", tags[TagField.TITLE])
        val ape = assertNotNull(tags.raw.blocks.firstOrNull { it.format == TagFormat.APEV2 })
        assertEquals(
            "keep-this",
            ape.entries.firstOrNull { it.key == "Application State" }?.text,
            "the unmodelled APE item disappeared",
        )
    }

    @Test
    fun apeCoverDescriptionsRoundTripAsUtf8() {
        val image = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A,
        )
        val ape = ApeTag.build(
            TagBundle(
                fields = emptyMap(),
                pictures = listOf(
                    TagPicture(
                        mimeType = "image/png",
                        description = "Pochette \u20AC",
                        bytes = image,
                    ),
                ),
            ),
        )
        val tags = KiteAudio.readTags(ByteArray(200) + ape)
        val cover = assertNotNull(tags.cover)
        assertEquals("Pochette \u20AC", cover.description)
        assertContentEquals(image, cover.bytes)
    }

    @Test
    fun rewritingAPrefixedBareStreamDoesNotCopySeveralTrailingTagsAsAudio() {
        val audio = ByteArray(600) { ((it * 23 + 11) and 0xFF).toByte() }
        audio[0] = 0xFF.toByte()
        audio[1] = 0xF1.toByte() // ADTS, so container detection uses a prefixed source view.
        val prefixCarrier = taggedStream(TagBundle(mapOf(TagField.TITLE to listOf("Before"))))
        val prefixSize = 10 + readSyncsafe(prefixCarrier, 6)
        val prefix = prefixCarrier.copyOf(prefixSize)
        val lyrics = "LYRICSBEGINLYR00005hello000024LYRICS200".encodeToByteArray()
        val ape = ApeTag.write(
            listOf(
                "Title" to "Before".encodeToByteArray(),
                "Application State" to "keep-this".encodeToByteArray(),
            ),
        )
        val id3v1 = Id3v1Bytes("Before", "", "", "", "", null, 255)
        val original = prefix + audio + lyrics + ape + id3v1

        val written = KiteAudio.writeTags(
            original,
            KiteAudio.readTags(original).edit { set(TagField.TITLE, "After") },
        )
        val tags = KiteAudio.readTags(written)
        assertEquals("After", tags[TagField.TITLE])
        assertEquals(1, tags.raw.blocksOf(TagFormat.APEV2).size)
        assertEquals(1, tags.raw.blocksOf(TagFormat.LYRICS3).size)
        assertEquals(1, tags.raw.blocksOf(TagFormat.ID3V1).size)

        val leading = assertNotNull(
            tags.raw.blocks.firstOrNull {
                it.offset == 0L &&
                    (it.format == TagFormat.ID3V2_3 || it.format == TagFormat.ID3V2_4)
            },
        )
        val trailingStart = tags.raw.blocks
            .filter {
                it.format == TagFormat.APEV2 ||
                    it.format == TagFormat.LYRICS3 ||
                    it.format == TagFormat.ID3V1
            }
            .minOf { it.offset }
        val audioStart = (leading.offset + leading.size).toInt()
        assertEquals(audio.size.toLong(), trailingStart - audioStart)
        assertContentEquals(audio, written.copyOfRange(audioStart, trailingStart.toInt()))
    }

    @Test
    fun rewritingAContainerUpdatesLegacyTagsAppendedAfterItsDeclaredEnd() {
        val samples = TestSignals.quantise(TestSignals.sine(300, 1), 16)
        val wave = KiteAudio.encode(samples, AudioFormat(44_100, 1), EncodeTarget.wav())
        val id3Carrier = taggedStream(TagBundle(mapOf(TagField.TITLE to listOf("Before"))))
        val id3Size = 10 + readSyncsafe(id3Carrier, 6)
        val appendedId3 = ByteArray(id3Size + 10)
        id3Carrier.copyInto(appendedId3, endIndex = id3Size)
        appendedId3[5] = (appendedId3[5].toInt() or 0x10).toByte()
        appendedId3[id3Size] = '3'.code.toByte()
        appendedId3[id3Size + 1] = 'D'.code.toByte()
        appendedId3[id3Size + 2] = 'I'.code.toByte()
        for (index in 3 until 10) {
            appendedId3[id3Size + index] = appendedId3[index]
        }
        val lyrics = "LYRICSBEGINLYR00005hello000024LYRICS200".encodeToByteArray()
        val ape = ApeTag.write(
            listOf(
                "Title" to "Before".encodeToByteArray(),
                "Application State" to "keep-this".encodeToByteArray(),
            ),
        )
        val original = wave + appendedId3 + lyrics + ape +
            Id3v1Bytes("Before", "", "", "", "", null, 255)

        val written = KiteAudio.writeTags(
            original,
            KiteAudio.readTags(original).edit { set(TagField.TITLE, "After") },
        )
        val tags = KiteAudio.readTags(written)
        assertEquals("After", tags[TagField.TITLE])
        assertEquals(1, tags.raw.blocksOf(TagFormat.APEV2).size)
        assertEquals(1, tags.raw.blocksOf(TagFormat.LYRICS3).size)
        assertEquals(1, tags.raw.blocksOf(TagFormat.ID3V1).size)
        assertEquals(1, tags.raw.blocksOf(TagFormat.ID3V2_4).size)
        assertEquals(0L, tags.raw.blocksOf(TagFormat.ID3V2_4).single().offset)
        assertTrue(tags.raw.blocks.any { it.format == TagFormat.RIFF_INFO })
        assertEquals(
            "keep-this",
            tags.raw.blocksOf(TagFormat.APEV2).single().entries
                .first { it.key == "Application State" }.text,
        )
        TestSignals.assertExact(samples, KiteAudio.decode(written).samples)
    }

    @Test
    fun rewritingAChunkedContainerHonoursItsDeclaredEndAndPreservesAnUnknownSuffix() {
        val samples = TestSignals.quantise(TestSignals.sine(180, 1), 16)
        val wave = KiteAudio.encode(samples, AudioFormat(44_100, 1), EncodeTarget.wav())
        val suffix = byteArrayOf(9, 8, 7, 6, 5)
        val original = wave + suffix
        val written = KiteAudio.writeTags(
            original,
            TagBundle(mapOf(TagField.TITLE to listOf("With suffix"))),
        )

        val declaredEnd = (readU32Le(written, 4) + 8L).toInt()
        assertContentEquals(suffix, written.copyOfRange(declaredEnd, written.size))
        TestSignals.assertExact(samples, KiteAudio.decode(written).samples)

        val overstated = wave.copyOf()
        writeU32Le(overstated, 4, readU32Le(overstated, 4) + 100L)
        assertFailsWith<TruncatedStreamException> {
            KiteAudio.writeTags(overstated, TagBundle(emptyMap()))
        }

        val partialRiffChunk = wave + byteArrayOf('J'.code.toByte(), 'U'.code.toByte(), 'N'.code.toByte())
        writeU32Le(partialRiffChunk, 4, readU32Le(partialRiffChunk, 4) + 3L)
        assertFailsWith<TruncatedStreamException> {
            KiteAudio.writeTags(partialRiffChunk, TagBundle(emptyMap()))
        }

        val aiff = KiteAudio.encode(samples, AudioFormat(44_100, 1), EncodeTarget.aiff())
        val partialAiffChunk = aiff + byteArrayOf('J'.code.toByte(), 'U'.code.toByte(), 'N'.code.toByte())
        writeU32Be(partialAiffChunk, 4, readU32Be(partialAiffChunk, 4) + 3L)
        assertFailsWith<TruncatedStreamException> {
            KiteAudio.writeTags(partialAiffChunk, TagBundle(emptyMap()))
        }
    }

    @Test
    fun tagReadingDoesNotTreatBytesAfterAChunkedContainerAsEmbeddedChunks() {
        val samples = TestSignals.quantise(TestSignals.sine(80, 1), 16)
        val id3Carrier = taggedStream(
            TagBundle(mapOf(TagField.TITLE to listOf("Outside the form"))),
        )
        val id3Size = 10 + readSyncsafe(id3Carrier, 6)
        val id3 = id3Carrier.copyOf(id3Size)

        fun riffChunk(): ByteArray {
            val chunk = ByteArray(8 + id3.size + (id3.size and 1))
            "id3 ".forEachIndexed { index, char -> chunk[index] = char.code.toByte() }
            writeU32Le(chunk, 4, id3.size.toLong())
            id3.copyInto(chunk, 8)
            return chunk
        }

        fun aiffChunk(): ByteArray {
            val chunk = ByteArray(8 + id3.size + (id3.size and 1))
            "ID3 ".forEachIndexed { index, char -> chunk[index] = char.code.toByte() }
            writeU32Be(chunk, 4, id3.size.toLong())
            id3.copyInto(chunk, 8)
            return chunk
        }

        val wave = KiteAudio.encode(samples, AudioFormat(44_100, 1), EncodeTarget.wav())
        val waveTags = KiteAudio.readTags(wave + riffChunk())
        assertNull(waveTags[TagField.TITLE])
        assertTrue(waveTags.raw.blocksOf(TagFormat.ID3V2_4).isEmpty())

        val aiff = KiteAudio.encode(samples, AudioFormat(44_100, 1), EncodeTarget.aiff())
        val aiffTags = KiteAudio.readTags(aiff + aiffChunk())
        assertNull(aiffTags[TagField.TITLE])
        assertTrue(aiffTags.raw.blocksOf(TagFormat.ID3V2_4).isEmpty())
    }

    @Test
    fun rewritingFlacDoesNotAbsorbAnAppendedApeTagIntoItsAudio() {
        val samples = TestSignals.quantise(TestSignals.sine(300, 1), 16)
        val flac = KiteAudio.encode(samples, AudioFormat(44_100, 1), EncodeTarget.flac())
        val original = flac + ApeTag.write(
            listOf(
                "Title" to "Before".encodeToByteArray(),
                "Application State" to "keep-this".encodeToByteArray(),
            ),
        )

        val written = KiteAudio.writeTags(
            original,
            KiteAudio.readTags(original).edit { set(TagField.TITLE, "After") },
        )
        val tags = KiteAudio.readTags(written)
        assertEquals("After", tags[TagField.TITLE])
        assertEquals(1, tags.raw.blocksOf(TagFormat.APEV2).size)
        assertEquals(
            "keep-this",
            tags.raw.blocksOf(TagFormat.APEV2).single().entries
                .first { it.key == "Application State" }.text,
        )
        assertEquals(true, KiteAudio.verifyFlacMd5(written))
        TestSignals.assertExact(samples, KiteAudio.decode(written).samples)
    }

    @Test
    fun anExistingId3v1BlockIsKeptInStepAndNeverInvented() {
        val withoutV1 = taggedStream(TagBundle(mapOf(TagField.TITLE to listOf("No v1 here"))))
        val rewritten = KiteAudio.writeTags(withoutV1, KiteAudio.readTags(withoutV1))
        assertTrue(
            KiteAudio.readTags(rewritten).raw.blocks.none { it.format == TagFormat.ID3V1 },
            "an ID3v1 block should never be created",
        )

        val withV1 = withoutV1 + Id3v1Bytes("Old", "Old", "Old", "1999", "", null, 255)
        val updated = KiteAudio.writeTags(
            withV1,
            KiteAudio.readTags(withV1).edit { set(TagField.TITLE, "Fresh") },
        )
        val blocks = KiteAudio.readTags(updated).raw
        assertTrue(
            blocks.blocks.any { it.format == TagFormat.ID3V1 },
            "an ID3v1 block that was there should be kept",
        )
        val v1 = blocks.blocksOf(TagFormat.ID3V1).first()
        assertEquals(
            "Fresh",
            v1.entries.first { it.key == "TITLE" }.text,
            "the older block should agree with the newer one",
        )
    }

    @Test
    fun writingIntoAFormatWithNoWriterSaysSoRatherThanCorruptingIt() {
        // An MP4 file: KiteAudio names the format, and cannot yet write its
        // tags, because that needs the box layer.
        val mp4 = ByteArray(4) + "ftypM4A ".encodeToByteArray() + ByteArray(100)
        val failure = kotlin.runCatching {
            KiteAudio.writeTags(mp4, TagBundle(mapOf(TagField.TITLE to listOf("x"))))
        }.exceptionOrNull()
        assertTrue(
            failure is UnsupportedFeatureException,
            "expected a clear refusal, got ${failure?.let { it::class.simpleName }}",
        )
    }

    @Test
    fun theAudioOfATaggedStreamIsCopiedByteForByte() {
        val audio = ByteArray(5_000) { ((it * 37 + 11) and 0xFF).toByte() }
        // Make sure the audio cannot be mistaken for a tag block.
        audio[0] = 0xFF.toByte()
        audio[1] = 0xFB.toByte()
        val first = KiteAudio.writeTags(
            audio,
            TagBundle(mapOf(TagField.TITLE to listOf("First"))),
        )
        val second = KiteAudio.writeTags(
            first,
            TagBundle(mapOf(TagField.TITLE to listOf("Second and much longer"))),
        )
        val recovered = second.copyOfRange(second.size - audio.size, second.size)
        assertContentEquals(audio, recovered, "the audio was not copied unchanged")
    }

    // Helpers

    /**
     * Builds a file that is an ID3v2 block followed by something that is not
     * audio KiteAudio decodes, which is what an MP3 looks like to the tag
     * reader.
     */
    private fun taggedStream(tags: TagBundle, version: Int = 4): ByteArray {
        val body = ByteArray(600) { ((it * 17) and 0xFF).toByte() }
        body[0] = 0xFF.toByte()
        body[1] = 0xFB.toByte()
        return KiteAudio.writeTags(
            body,
            tags,
            TagWriteOptions(id3Version = version, padding = 64),
        )
    }

    /** An APE block on its own, which is the simplest carrier for a key test. */
    private fun vorbisLikeApe(items: List<Pair<String, String>>): ByteArray {
        val encoded = items.map { it.first to it.second.encodeToByteArray() }
        return ByteArray(200) + ApeBytes(encoded)
    }

    private fun readSyncsafe(bytes: ByteArray, at: Int): Int {
        var value = 0
        for (index in 0 until 4) value = (value shl 7) or (bytes[at + index].toInt() and 0x7F)
        return value
    }

    private fun readU32Le(bytes: ByteArray, at: Int): Long =
        (bytes[at].toLong() and 0xFF) or
            ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or
            ((bytes[at + 3].toLong() and 0xFF) shl 24)

    private fun writeU32Le(bytes: ByteArray, at: Int, value: Long) {
        for (index in 0 until 4) bytes[at + index] = (value ushr (index * 8)).toByte()
    }

    private fun readU32Be(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or
            ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or
            (bytes[at + 3].toLong() and 0xFF)

    private fun writeU32Be(bytes: ByteArray, at: Int, value: Long) {
        for (index in 0 until 4) bytes[at + index] = (value ushr ((3 - index) * 8)).toByte()
    }

    private fun writeSyncsafe(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = ((value ushr 21) and 0x7F).toByte()
        bytes[at + 1] = ((value ushr 14) and 0x7F).toByte()
        bytes[at + 2] = ((value ushr 7) and 0x7F).toByte()
        bytes[at + 3] = (value and 0x7F).toByte()
    }
}

/** Builds a 128-byte ID3v1 block for the tests. */
@Suppress("FunctionName")
internal fun Id3v1Bytes(
    title: String,
    artist: String,
    album: String,
    year: String,
    comment: String,
    track: Int?,
    genre: Int,
): ByteArray {
    val out = ByteArray(128)
    "TAG".forEachIndexed { i, c -> out[i] = c.code.toByte() }
    fun put(at: Int, length: Int, text: String) {
        for (i in 0 until minOf(length, text.length)) out[at + i] = text[i].code.toByte()
    }
    put(3, 30, title)
    put(33, 30, artist)
    put(63, 30, album)
    put(93, 4, year)
    if (track != null) {
        put(97, 28, comment)
        out[125] = 0
        out[126] = track.toByte()
    } else {
        put(97, 30, comment)
    }
    out[127] = genre.toByte()
    return out
}

/** Builds an APEv2 block for the tests. */
@Suppress("FunctionName")
internal fun ApeBytes(items: List<Pair<String, ByteArray>>): ByteArray {
    var body = 0
    for ((key, value) in items) body += 8 + key.length + 1 + value.size
    val out = ByteArray(64 + body)
    var position = 0

    fun u32(value: Int) {
        out[position++] = value.toByte()
        out[position++] = (value ushr 8).toByte()
        out[position++] = (value ushr 16).toByte()
        out[position++] = (value ushr 24).toByte()
    }

    fun preamble(flags: Int) {
        "APETAGEX".forEach { out[position++] = it.code.toByte() }
        u32(2000)
        u32(body + 32)
        u32(items.size)
        u32(flags)
        position += 8
    }

    preamble(flags = (1 shl 31) or (1 shl 29))
    for ((key, value) in items) {
        u32(value.size)
        u32(0)
        key.forEach { out[position++] = it.code.toByte() }
        out[position++] = 0
        value.copyInto(out, position)
        position += value.size
    }
    preamble(flags = 1 shl 31)
    return out
}

/** Finds a byte pattern, or returns -1. */
internal fun ByteArray.indexOfBytes(pattern: ByteArray): Int {
    outer@ for (i in 0..size - pattern.size) {
        for (j in pattern.indices) {
            if (this[i + j] != pattern[j]) continue@outer
        }
        return i
    }
    return -1
}

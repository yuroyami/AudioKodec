package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.ReplayGain
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagChapter
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagPicture
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TagRepresentabilityTest {
    @Test
    fun defaultChunkedWritesKeepEveryPartOfARichBundle() {
        for (source in listOf(NativeTextListFixtures.wave(), NativeTextListFixtures.rf64(), NativeTextListFixtures.aiff())) {
            var written = AudioKodec.writeTags(source, rich)
            repeat(3) { index ->
                val read = AudioKodec.readTags(written)
                assertRich(read)
                assertEquals(1, read.raw.blocksOf(TagFormat.ID3V2_4).size)
                assertContentEquals(NativeTextListFixtures.payload(source), NativeTextListFixtures.payload(written))
                written = AudioKodec.writeTags(written, read.edit { set(TagField.TITLE, "Edit $index") })
            }
        }
    }

    @Test
    fun aNativeOnlyRequestReportsEveryUnrepresentablePartBeforeOutput() {
        for ((source, format) in listOf(NativeTextListFixtures.wave() to TagFormat.RIFF_INFO,
            NativeTextListFixtures.aiff() to TagFormat.AIFF_TEXT)) {
            val sink = ByteArrayAudioSink()
            val error = assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags(source.asAudioSource(), rich, sink, TagWriteOptions(formats = setOf(format)))
            }
            assertEquals(0L, sink.position)
            for (name in listOf("ALBUM_ARTIST", "TRACK_TOTAL", "BPM", "URL", "pictures", "chapters", "ReplayGain")) {
                assertTrue(error.message.orEmpty().contains(name), error.message)
            }
        }
    }

    @Test
    fun nativeVorbisCommentsRetainWebsiteValues() {
        val tags = TagBundle(mapOf(TagField.URL to listOf("https://example.org/first", "https://example.org/second")))
        for (source in listOf(TagStressFixtures.flac(emptyList()), R128TagFixtures.opus())) {
            val written = AudioKodec.writeTags(source, tags)
            assertEquals(tags.fields, AudioKodec.readTags(written).fields)
        }
    }

    @Test
    fun totalsWithoutNumbersSurviveId3AndApeOutput() {
        val tags = TagBundle(mapOf(TagField.TRACK_TOTAL to listOf("12"), TagField.DISC_TOTAL to listOf("2")))
        for (format in listOf(TagFormat.ID3V2_3, TagFormat.ID3V2_4, TagFormat.APEV2)) {
            val written = AudioKodec.writeTags(ByteArray(32) { 0x55 }, tags, TagWriteOptions(formats = setOf(format), padding = 0))
            assertEquals(tags.fields, AudioKodec.readTags(written).fields)
        }
    }

    @Test
    fun unsupportedNativeChaptersAreNamedAndRefusedBeforeOutput() {
        val tags = TagBundle(emptyMap(), chapters = rich.chapters)
        for ((source, options) in listOf(TagStressFixtures.flac(emptyList()) to TagWriteOptions.DEFAULT,
            R128TagFixtures.opus() to TagWriteOptions.DEFAULT,
            ByteArray(32) { 0x55 } to TagWriteOptions(formats = setOf(TagFormat.APEV2)))) {
            val sink = ByteArrayAudioSink()
            val error = assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags(source.asAudioSource(), tags, sink, options)
            }
            assertEquals(0L, sink.position)
            assertTrue(error.message.orEmpty().contains("chapter", ignoreCase = true), error.message)
        }
    }

    @Test
    fun everyModelledFieldHasADefaultCarrier() {
        val fields = TagRepresentabilityFixtures.fields
        for (source in listOf(NativeTextListFixtures.wave(), NativeTextListFixtures.rf64(), NativeTextListFixtures.aiff(),
            TagStressFixtures.flac(emptyList()), R128TagFixtures.opus(), ByteArray(32) { 0x55 })) {
            var written = AudioKodec.writeTags(source, TagBundle(fields))
            repeat(2) {
                val read = AudioKodec.readTags(written)
                assertEquals(fields, read.fields)
                written = AudioKodec.writeTags(written, read)
            }
        }
    }

    @Test
    fun numberAndTotalListsRetainAllFactsWithoutGuessingPairs() {
        for (numbers in listOf(emptyList(), listOf("3"), listOf("3", "4"))) {
            for (totals in listOf(listOf("12"), listOf("12", "13"))) {
                val fields = buildMap {
                    if (numbers.isNotEmpty()) {
                        put(TagField.TRACK_NUMBER, numbers)
                        put(TagField.DISC_NUMBER, numbers)
                    }
                    put(TagField.TRACK_TOTAL, totals)
                    put(TagField.DISC_TOTAL, totals)
                }
                for (format in listOf(TagFormat.ID3V2_4, TagFormat.APEV2)) {
                    var written = AudioKodec.writeTags(ByteArray(32) { 0x55 }, TagBundle(fields),
                        TagWriteOptions(formats = setOf(format), padding = 0))
                    repeat(3) {
                        val read = AudioKodec.readTags(written)
                        assertEquals(fields, read.fields)
                        written = AudioKodec.writeTags(written, read, TagWriteOptions(formats = setOf(format), padding = 0))
                    }
                }
                if (numbers.size > 1 || totals.size > 1) {
                    val sink = ByteArrayAudioSink()
                    assertFailsWith<UnsupportedFeatureException> {
                        AudioKodec.writeTags(ByteArray(32) { 0x55 }.asAudioSource(), TagBundle(fields), sink,
                            TagWriteOptions(id3Version = 3))
                    }
                    assertEquals(0L, sink.position)
                }
            }
        }
    }

    @Test
    fun unicodeAndFreeCommentSpacesUseId3RatherThanLossyNativeText() {
        for (fields in listOf(mapOf(TagField.TITLE to listOf("日本語")),
            mapOf(TagField.COMMENT to listOf(" first ", "日本語\n")))) {
            for ((source, native) in listOf(NativeTextListFixtures.wave() to TagFormat.RIFF_INFO,
                NativeTextListFixtures.aiff() to TagFormat.AIFF_TEXT)) {
                val written = AudioKodec.writeTags(source, TagBundle(fields))
                val read = AudioKodec.readTags(written)
                assertEquals(fields, read.fields)
                assertTrue(read.raw.blocksOf(native).isEmpty())
                assertContentEquals(NativeTextListFixtures.payload(source), NativeTextListFixtures.payload(written))
                val sink = ByteArrayAudioSink()
                assertFailsWith<UnsupportedFeatureException> {
                    AudioKodec.writeTags(source.asAudioSource(), TagBundle(fields), sink, TagWriteOptions(formats = setOf(native)))
                }
                assertEquals(0L, sink.position)
            }
        }
    }

    @Test
    fun freshEncodingRetainsRichTagsOrRefusesUnsupportedChaptersBeforeOutput() {
        for (target in listOf(EncodeTarget.wav(), EncodeTarget.aiff(), EncodeTarget.aiffFloat())) {
            val plain = AudioKodec.encode(NativeTextListFixtures.samples, NativeTextListFixtures.format, target)
            val encoded = AudioKodec.encode(NativeTextListFixtures.samples, NativeTextListFixtures.format, target, rich)
            assertRich(AudioKodec.readTags(encoded))
            assertContentEquals(NativeTextListFixtures.payload(plain), NativeTextListFixtures.payload(encoded))
        }
        val sink = ByteArrayAudioSink()
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.encode(NativeTextListFixtures.samples.asPcmSource(NativeTextListFixtures.format),
                EncodeTarget.flac(), sink, rich)
        }
        assertEquals(0L, sink.position)
    }

    @Test
    fun nativeChapterRefusalRespectsAnExistingExternalId3Carrier() {
        val tagged = AudioKodec.writeTags(ByteArray(32) { 0x55 }, rich, TagWriteOptions(padding = 0))
        val length = 10 + (0..3).fold(0) { result, i -> (result shl 7) or (tagged[6 + i].toInt() and 127) }
        val id3 = tagged.copyOf(length)
        val appended = id3.copyOf(length + 10).also { bytes ->
            bytes[5] = 0x10
            bytes.copyInto(bytes, length, 0, 10)
            "3DI".encodeToByteArray().copyInto(bytes, length)
        }
        for (base in listOf(TagStressFixtures.flac(emptyList()), R128TagFixtures.opus())) {
            for (source in listOf(id3 + base, base + appended)) {
                assertRich(AudioKodec.readTags(source))
                var written = source
                repeat(2) {
                    written = AudioKodec.writeTags(written, AudioKodec.readTags(written))
                    assertRich(AudioKodec.readTags(written))
                    assertEquals(1, AudioKodec.readTags(written).raw.blocksOf(TagFormat.ID3V2_4).size)
                }
                val sink = ByteArrayAudioSink()
                assertFailsWith<UnsupportedFeatureException> {
                    AudioKodec.writeTags(source.asAudioSource(), rich, sink,
                        TagWriteOptions(formats = setOf(TagFormat.VORBIS_COMMENT)))
                }
                assertEquals(0L, sink.position)
            }
        }
    }

    @Test
    fun anEmptySelectionStillRemovesWritableTagsDeliberately() {
        for (source in listOf(NativeTextListFixtures.wave(), NativeTextListFixtures.rf64(), NativeTextListFixtures.aiff(),
            TagStressFixtures.flac(emptyList()), ByteArray(32) { 0x55 })) {
            val written = AudioKodec.writeTags(source, rich, TagWriteOptions(formats = emptySet()))
            assertTrue(AudioKodec.readTags(written).isEmpty)
        }
    }

    @Test
    fun unrepresentableId3TextAndChapterBoundariesFailBeforeAnExternalPrefix() {
        val prefix = AudioKodec.writeTags(ByteArray(32) { 0x55 }, TagBundle(mapOf(TagField.TITLE to listOf("Before"))),
            TagWriteOptions(padding = 0)).let { bytes ->
            val length = 10 + (0..3).fold(0) { result, i -> (result shl 7) or (bytes[6 + i].toInt() and 127) }
            bytes.copyOf(length)
        }
        val invalid = listOf(
            TagBundle(mapOf(TagField.URL to listOf("https://example.org/日本語"))),
            TagBundle(mapOf(TagField.TITLE to listOf("one\u0000two"))),
            TagBundle(emptyMap(), chapters = listOf(TagChapter("日本語", 0))),
            TagBundle(emptyMap(), chapters = listOf(TagChapter("one", 0x1_0000_0000L))),
            TagBundle(emptyMap(), chapters = listOf(TagChapter("one", 0, 0xFFFF_FFFFL))),
        )
        for (tags in invalid) {
            val sink = ByteArrayAudioSink()
            assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags((prefix + NativeTextListFixtures.wave()).asAudioSource(), tags, sink)
            }
            assertEquals(0L, sink.position)
        }
        val maximum = TagBundle(emptyMap(), chapters = listOf(TagChapter("last", 0xFFFF_FFFFL)))
        val written = AudioKodec.writeTags(NativeTextListFixtures.wave(), maximum)
        assertEquals(0xFFFF_FFFFL, AudioKodec.readTags(written).chapters.single().startMillis)
    }

    @Test
    fun nativeLoudnessLimitsAndNumberSeparatorsFailBeforeOutput() {
        val prefix = AudioKodec.writeTags(ByteArray(32) { 0x55 }, TagBundle(mapOf(TagField.TITLE to listOf("Before"))),
            TagWriteOptions(padding = 0)).let { bytes ->
            val length = 10 + (0..3).fold(0) { result, i -> (result shl 7) or (bytes[6 + i].toInt() and 127) }
            bytes.copyOf(length)
        }
        for (source in listOf(TagStressFixtures.flac(emptyList()), R128TagFixtures.opus())) {
            val sink = ByteArrayAudioSink()
            assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags((prefix + source).asAudioSource(),
                    TagBundle(emptyMap(), replayGain = ReplayGain(trackGainDb = 128.0, isR128 = true)), sink)
            }
            assertEquals(0L, sink.position)
        }
        for (format in listOf(TagFormat.ID3V2_3, TagFormat.ID3V2_4, TagFormat.APEV2)) {
            for (field in listOf(TagField.TRACK_NUMBER, TagField.DISC_NUMBER, TagField.COMMENT)) {
                val sink = ByteArrayAudioSink()
                val value = if (field == TagField.COMMENT) "first\u0000second" else "3/12"
                val error = assertFailsWith<UnsupportedFeatureException> {
                    AudioKodec.writeTags(ByteArray(32) { 0x55 }.asAudioSource(), TagBundle(mapOf(field to listOf(value))),
                        sink, TagWriteOptions(formats = setOf(format)))
                }
                assertTrue(error.message.orEmpty().contains(field.name))
                assertEquals(0L, sink.position)
            }
        }
        val urls = TagBundle(mapOf(TagField.URL to listOf("https://example.org/日本語")))
        val encoded = AudioKodec.encode(NativeTextListFixtures.samples, NativeTextListFixtures.format, EncodeTarget.flac(), urls)
        assertEquals(urls.fields, AudioKodec.readTags(encoded).fields)
    }

    private fun assertRich(read: TagBundle) {
        for ((field, values) in rich.fields) {
            if (field != TagField.TITLE) assertEquals(values, read.all(field), field.name)
        }
        assertEquals(1, read.pictures.size)
        assertContentEquals(rich.pictures.single().bytes, read.pictures.single().bytes)
        assertEquals(1, read.chapters.size)
        assertEquals("one", read.chapters.single().id)
        assertEquals(1_000L, read.chapters.single().endMillis)
        assertEquals("Opening", read.chapters.single().title)
        assertEquals(-3.0, read.replayGain?.trackGainDb)
        assertEquals(0.9, read.replayGain?.trackPeak)
    }

    private val rich = TagRepresentabilityFixtures.rich
}

internal object TagRepresentabilityFixtures {
    val fields = TagField.entries.associateWith { field -> listOf(when (field) {
        TagField.TRACK_NUMBER, TagField.DISC_NUMBER -> "3"
        TagField.TRACK_TOTAL, TagField.DISC_TOTAL -> "12"
        TagField.DATE, TagField.ORIGINAL_DATE -> "2026-10-07"
        TagField.BPM -> "128"
        TagField.COMPILATION -> "1"
        TagField.URL -> "https://example.org/artist"
        TagField.GENRE -> "Alternative"
        else -> "Value"
    }) }

    val rich = TagBundle(mapOf(
        TagField.TITLE to listOf("Title"), TagField.ALBUM_ARTIST to listOf("Album artist"),
        TagField.TRACK_NUMBER to listOf("3"), TagField.TRACK_TOTAL to listOf("12"),
        TagField.BPM to listOf("128"), TagField.URL to listOf("https://example.org/artist")),
        pictures = listOf(TagPicture(mimeType = "image/jpeg", bytes = byteArrayOf(-1, -40, 0, 1))),
        chapters = listOf(TagChapter("one", 0, 1_000, "Opening")),
        replayGain = ReplayGain(trackGainDb = -3.0, trackPeak = 0.9))
}

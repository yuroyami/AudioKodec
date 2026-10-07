package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagChapter
import io.github.yuroyami.audiokodec.tag.TagPicture
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNull

class ChapterSubframeTest {
    @Test
    fun unrelatedEditsPreserveEveryChapterSubframe() {
        for (major in 3..4) {
            val expected = ChapterSubframeFixtures.subframes(major)
            var source = ChapterSubframeFixtures.stream(major, expected)
            repeat(3) { edit ->
                source = AudioKodec.writeTags(source, AudioKodec.readTags(source).edit { set(TagField.TITLE, "Edit $edit") },
                    TagWriteOptions(id3Version = major, padding = 0))
                assertContentEquals(expected, ChapterSubframeFixtures.nested(source))
                assertContentEquals(ChapterSubframeFixtures.audio, source.copyOfRange(ChapterSubframeFixtures.tagEnd(source), source.size))
            }
        }
    }

    @Test
    fun aParsedChapterKeepsItsSubframesWithoutTheOriginalRawBundle() {
        val expected = ChapterSubframeFixtures.subframes(4)
        val source = ChapterSubframeFixtures.stream(4, expected)
        val read = AudioKodec.readTags(source)
        val written = AudioKodec.writeTags(source, TagBundle(mapOf(TagField.TITLE to listOf("Edit")), chapters = read.chapters),
            TagWriteOptions(padding = 0))
        assertContentEquals(expected, ChapterSubframeFixtures.nested(written))
        assertEquals("Chapter", AudioKodec.readTags(written).chapters.single().title)
    }

    @Test
    fun copiedChaptersReplaceModelledValuesAndRetainExtras() {
        for (major in 3..4) {
            val extras = ChapterSubframeFixtures.frame("TIT3", ChapterSubframeFixtures.text("Subtitle 日本語", major), major) +
                ChapterSubframeFixtures.frame("PRIV", byteArrayOf(0, 5), major)
            val source = ChapterSubframeFixtures.stream(major, ChapterSubframeFixtures.frame("TIT2", ChapterSubframeFixtures.text("Old", major), major) + extras)
            val tags = AudioKodec.readTags(source)
            val picture = TagPicture(mimeType = "image/png", description = "New 日本語", bytes = byteArrayOf(1, 2, 3))
            val chapter = tags.chapters.single().copy(id = "renamed", startMillis = 25, endMillis = 90, title = "New 日本語", pictures = listOf(picture))
            val written = AudioKodec.writeTags(source, tags.edit { setChapters(listOf(chapter)) }, TagWriteOptions(id3Version = major, padding = 0))
            val read = AudioKodec.readTags(written).chapters.single()
            assertEquals("renamed", read.id)
            assertEquals(25L, read.startMillis)
            assertEquals(90L, read.endMillis)
            assertEquals("New 日本語", read.title)
            assertContentEquals(picture.bytes, read.pictures.single().bytes)
            assertContentEquals(extras, ChapterSubframeFixtures.nested(written).takeLast(extras.size).toByteArray())
            val removed = AudioKodec.writeTags(written, AudioKodec.readTags(written).edit {
                setChapters(listOf(read.copy(title = null, pictures = emptyList())))
            }, TagWriteOptions(id3Version = major, padding = 0))
            assertContentEquals(extras, ChapterSubframeFixtures.nested(removed))
        }
    }

    @Test
    fun newChaptersAndExplicitRemovalDoNotResurrectStoredFrames() {
        val source = ChapterSubframeFixtures.stream(4, ChapterSubframeFixtures.subframes(4))
        val tags = AudioKodec.readTags(source)
        val replaced = AudioKodec.writeTags(source, tags.edit { setChapters(listOf(TagChapter("c1", 0, 100, "Replacement"))) }, TagWriteOptions(padding = 0))
        assertContentEquals(ChapterSubframeFixtures.frame("TIT2", byteArrayOf(0) + "Replacement".encodeToByteArray(), 4), ChapterSubframeFixtures.nested(replaced))
        for (edited in listOf(tags.edit { setChapters(emptyList()) }, tags.edit { clear() })) {
            assertTrue(AudioKodec.readTags(AudioKodec.writeTags(source, edited)).chapters.isEmpty())
        }
    }

    @Test
    fun opaqueNestedFramesStayOpaqueAndByteExact() {
        for (major in 3..4) {
            for (flag in if (major == 4) listOf(8, 4, 0x80) else listOf(0x80, 0x40, 1)) {
                val nested = ChapterSubframeFixtures.frame("TIT2", byteArrayOf(0) + "Not clear text".encodeToByteArray(), major, flag) +
                    ChapterSubframeFixtures.frame("APIC", byteArrayOf(0, 0, 3, 0, 1), major, flag)
                val source = ChapterSubframeFixtures.stream(major, nested)
                val tags = AudioKodec.readTags(source)
                assertNull(tags.chapters.single().title)
                assertTrue(tags.chapters.single().pictures.isEmpty())
                assertContentEquals(nested, ChapterSubframeFixtures.nested(AudioKodec.writeTags(source, tags, TagWriteOptions(id3Version = major, padding = 0))))
                for (chapter in listOf(tags.chapters.single().copy(title = "New"), tags.chapters.single().copy(pictures = listOf(TagPicture(mimeType = "image/png", bytes = byteArrayOf(9)))))) {
                    val sink = ByteArrayAudioSink()
                    val error = assertFailsWith<UnsupportedFeatureException> {
                        AudioKodec.writeTags(source.asAudioSource(), tags.edit { setChapters(listOf(chapter)) }, sink, TagWriteOptions(id3Version = major))
                    }
                    assertTrue(error.message.orEmpty().contains("c1"))
                    assertEquals(0L, sink.position)
                }
            }
        }
    }

    @Test
    fun discardOnTagAlterationAppliesInsideChapters() {
        for (major in 3..4) {
            val kept = ChapterSubframeFixtures.frame("TIT3", ChapterSubframeFixtures.text("Keep", major), major)
            val discarded = ChapterSubframeFixtures.frame("PRIV", byteArrayOf(0, 1), major, if (major == 4) 0x4000 else 0x8000)
            val source = ChapterSubframeFixtures.stream(major, discarded + kept)
            assertContentEquals(kept, ChapterSubframeFixtures.nested(AudioKodec.writeTags(source, AudioKodec.readTags(source), TagWriteOptions(id3Version = major, padding = 0))))
        }
    }

    @Test
    fun conversionPreservesKnownValuesAndRebuildsFrameSizes() {
        for (major in 3..4) {
            val target = if (major == 3) 4 else 3
            val nested = ChapterSubframeFixtures.subframes(major).dropLast(13).toByteArray() +
                ChapterSubframeFixtures.frame("COMM", byteArrayOf(if (major == 4) 3 else 1) + "eng".encodeToByteArray() +
                    ChapterSubframeFixtures.text("Comment 日本語", major).drop(1).toByteArray() + ByteArray(if (major == 4) 1 else 2) +
                    ChapterSubframeFixtures.text("Value 日本語", major).drop(1).toByteArray(), major)
            val source = ChapterSubframeFixtures.stream(major, nested)
            val converted = AudioKodec.writeTags(source, AudioKodec.readTags(source), TagWriteOptions(id3Version = target, padding = 0))
            assertEquals("Chapter", AudioKodec.readTags(converted).chapters.single().title)
            val roundTrip = AudioKodec.writeTags(converted, AudioKodec.readTags(converted), TagWriteOptions(id3Version = major, padding = 0))
            assertContentEquals(ChapterSubframeFixtures.audio, roundTrip.copyOfRange(ChapterSubframeFixtures.tagEnd(roundTrip), roundTrip.size))
            val frames = ChapterSubframeFixtures.frames(ChapterSubframeFixtures.nested(roundTrip), major)
            assertEquals(listOf("TIT2", "TIT3", "WXXX", "PRIV", "COMM"), frames.map { it.first })
            assertContentEquals(byteArrayOf(0, 1, -1, -32), frames[3].second.takeLast(4).toByteArray())
        }
    }

    @Test
    fun unsynchronisationIsUndoneBeforeFlagPrefixes() {
        // A group identity of FF followed by a zero length prefix needs stuffing.
        val clear = byteArrayOf(-1, 0, 0, 0, 6, 0) + "Title".encodeToByteArray()
        val stuffed = byteArrayOf(-1, 0) + clear.drop(1).toByteArray()
        val nested = ChapterSubframeFixtures.frame("TIT2", stuffed, 4, 0x3043)
        val source = ChapterSubframeFixtures.stream(4, nested)
        assertEquals("Title", AudioKodec.readTags(source).chapters.single().title)
        assertContentEquals(nested, ChapterSubframeFixtures.nested(AudioKodec.writeTags(source, AudioKodec.readTags(source), TagWriteOptions(padding = 0))))
        val converted = AudioKodec.writeTags(source, AudioKodec.readTags(source), TagWriteOptions(id3Version = 3, padding = 0))
        val child = ChapterSubframeFixtures.nested(converted)
        assertEquals(0x6020, ((child[8].toInt() and 255) shl 8) or (child[9].toInt() and 255))
        assertEquals(255, child[10].toInt() and 255)
        assertEquals("Title", AudioKodec.readTags(converted).chapters.single().title)
    }

    @Test
    fun conversionClearsReadOnlyWhenTextContentsChange() {
        val source = ChapterSubframeFixtures.stream(4, ChapterSubframeFixtures.frame("TIT3", ChapterSubframeFixtures.text("日本語", 4), 4, 0x1000))
        val converted = AudioKodec.writeTags(source, AudioKodec.readTags(source), TagWriteOptions(id3Version = 3, padding = 0))
        assertEquals(0, ChapterSubframeFixtures.nested(converted)[8].toInt())
    }

    @Test
    fun unrepresentableConversionsRefuseBeforeOutput() {
        for ((major, nested) in listOf(
            4 to ChapterSubframeFixtures.frame("ZZZZ", byteArrayOf(1, 2), 4),
            4 to ChapterSubframeFixtures.frame("TIT3", byteArrayOf(3) + "One".encodeToByteArray() + byteArrayOf(0) + "Two".encodeToByteArray(), 4),
            4 to ChapterSubframeFixtures.frame("TDRC", ChapterSubframeFixtures.text("2020-02-01T12:30:00", 4), 4),
            3 to ChapterSubframeFixtures.frame("TDAT", ChapterSubframeFixtures.text("0102", 3), 3),
            4 to ChapterSubframeFixtures.frame("TIT3", byteArrayOf(3, -64, -128), 4),
            4 to ChapterSubframeFixtures.frame("PRIV", byteArrayOf(1), 4, 8),
            4 to ChapterSubframeFixtures.frame("PRIV", byteArrayOf(1), 4, 0x80),
        )) {
            val source = ChapterSubframeFixtures.stream(major, nested)
            val sink = ByteArrayAudioSink()
            val error = assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags(source.asAudioSource(), AudioKodec.readTags(source), sink, TagWriteOptions(id3Version = if (major == 4) 3 else 4))
            }
            assertTrue(error.message.orEmpty().contains("c1"))
            assertTrue(error.message.orEmpty().contains(nested.decodeToString(0, 4)))
            assertEquals(0L, sink.position)
        }
    }

    @Test
    fun chapterDatesConvertWithoutLosingMinutePrecision() {
        val nested = listOf("TYER" to "2020", "TDAT" to "0102", "TIME" to "1230", "TORY" to "1990")
            .fold(byteArrayOf()) { bytes, (id, value) -> bytes + ChapterSubframeFixtures.frame(id, byteArrayOf(0) + value.encodeToByteArray(), 3) }
        val source = ChapterSubframeFixtures.stream(3, nested)
        val converted = AudioKodec.writeTags(source, AudioKodec.readTags(source), TagWriteOptions(padding = 0))
        val frames = ChapterSubframeFixtures.frames(ChapterSubframeFixtures.nested(converted), 4)
        assertEquals(listOf("TDRC", "TDOR"), frames.map { it.first })
        assertEquals("2020-02-01T12:30", frames[0].second.decodeToString(1))
        val restored = AudioKodec.writeTags(converted, AudioKodec.readTags(converted), TagWriteOptions(id3Version = 3, padding = 0))
        assertContentEquals(nested, ChapterSubframeFixtures.nested(restored))
    }

    @Test
    fun mutatingPictureBytesCannotReuseTheOriginalPayload() {
        val image = byteArrayOf(1, 2, 3)
        val extras = ChapterSubframeFixtures.frame("PRIV", byteArrayOf(0, 7), 4)
        val picture = ChapterSubframeFixtures.frame("APIC", byteArrayOf(0) + "image/png".encodeToByteArray() + byteArrayOf(0, 3, 0) + image, 4)
        val source = ChapterSubframeFixtures.stream(4, picture + extras)
        val tags = AudioKodec.readTags(source)
        tags.chapters.single().pictures.single().bytes[0] = 9
        val written = AudioKodec.writeTags(source, tags, TagWriteOptions(padding = 0))
        assertContentEquals(byteArrayOf(9, 2, 3), AudioKodec.readTags(written).chapters.single().pictures.single().bytes)
        assertContentEquals(extras, ChapterSubframeFixtures.nested(written).takeLast(extras.size).toByteArray())
    }

    @Test
    fun chapterSourceIsIndependentOfTheMutableRawView() {
        val expected = ChapterSubframeFixtures.subframes(4)
        val source = ChapterSubframeFixtures.stream(4, expected)
        val tags = AudioKodec.readTags(source)
        tags.raw.blocks.single().entries.single().binary!!.fill(0)
        assertContentEquals(expected, ChapterSubframeFixtures.nested(AudioKodec.writeTags(source, tags, TagWriteOptions(padding = 0))))
    }

    @Test
    fun duplicateOrderAndEmptyValuesSurviveUnrelatedEdits() {
        val nested = ChapterSubframeFixtures.frame("TXXX", byteArrayOf(3) + "Key".encodeToByteArray() + byteArrayOf(0) +
            "First".encodeToByteArray() + byteArrayOf(0) + "First".encodeToByteArray() + byteArrayOf(0, 0), 4) +
            ChapterSubframeFixtures.frame("PRIV", byteArrayOf(0, 1), 4) + ChapterSubframeFixtures.frame("PRIV", byteArrayOf(0, 1), 4)
        val source = ChapterSubframeFixtures.stream(4, nested)
        assertContentEquals(nested, ChapterSubframeFixtures.nested(AudioKodec.writeTags(source, AudioKodec.readTags(source), TagWriteOptions(padding = 0))))
        val sink = ByteArrayAudioSink()
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.writeTags(source.asAudioSource(), AudioKodec.readTags(source), sink, TagWriteOptions(id3Version = 3))
        }
        assertEquals(0L, sink.position)
    }

    @Test
    fun allValidTextEncodingsRemainReadableAcrossVersions() {
        for (major in 3..4) {
            for (encoding in 0..(if (major == 4) 3 else 1)) {
                val value = if (encoding == 0) "Café" else "Chapter 日本語"
                val text = when (encoding) {
                    0 -> byteArrayOf(0) + value.map { it.code.toByte() }.toByteArray()
                    1 -> ChapterSubframeFixtures.text(value, 3)
                    2 -> byteArrayOf(2) + value.flatMap { listOf((it.code ushr 8).toByte(), it.code.toByte()) }.toByteArray()
                    else -> ChapterSubframeFixtures.text(value, 4)
                }
                val nested = ChapterSubframeFixtures.frame("TIT2", text, major)
                val source = ChapterSubframeFixtures.stream(major, nested)
                val tags = AudioKodec.readTags(source)
                assertEquals(value, tags.chapters.single().title)
                assertContentEquals(nested, ChapterSubframeFixtures.nested(AudioKodec.writeTags(source, tags, TagWriteOptions(id3Version = major, padding = 0))))
                val converted = AudioKodec.writeTags(source, tags, TagWriteOptions(id3Version = if (major == 3) 4 else 3, padding = 0))
                assertEquals(value, AudioKodec.readTags(converted).chapters.single().title)
            }
        }
    }

    @Test
    fun unchangedChapterArtworkRetainsItsStoredSequence() {
        val picture = ChapterSubframeFixtures.frame("APIC", byteArrayOf(0) + "image/png".encodeToByteArray() + byteArrayOf(0, 3, 0, 1), 4)
        val extra = ChapterSubframeFixtures.frame("PRIV", byteArrayOf(0, 7), 4)
        val nested = picture + extra + picture
        val source = ChapterSubframeFixtures.stream(4, nested)
        assertContentEquals(nested, ChapterSubframeFixtures.nested(AudioKodec.writeTags(source, AudioKodec.readTags(source), TagWriteOptions(padding = 0))))
    }

    @Test
    fun legacySlashListsKeepTheirValuesDuringConversion() {
        val nested = ChapterSubframeFixtures.frame("TPE1", byteArrayOf(0) + "First/Second/First".encodeToByteArray(), 3)
        val source = ChapterSubframeFixtures.stream(3, nested)
        val converted = AudioKodec.writeTags(source, AudioKodec.readTags(source), TagWriteOptions(padding = 0))
        val payload = ChapterSubframeFixtures.frames(ChapterSubframeFixtures.nested(converted), 4).single().second
        assertContentEquals(byteArrayOf(0) + "First".encodeToByteArray() + byteArrayOf(0) + "Second".encodeToByteArray() + byteArrayOf(0) + "First".encodeToByteArray(), payload)
        val restored = AudioKodec.writeTags(converted, AudioKodec.readTags(converted), TagWriteOptions(id3Version = 3, padding = 0))
        assertContentEquals(nested, ChapterSubframeFixtures.nested(restored))
    }

    @Test
    fun invalidFlagPrefixesRefuseConversionBeforeOutput() {
        for ((flags, bytes) in listOf(0x40 to byteArrayOf(), 1 to byteArrayOf(0, 0, 0),
            1 to byteArrayOf(0, 0, 0, 4, 0, 1), 1 to byteArrayOf(-128, 0, 0, 2, 0, 1))) {
            val source = ChapterSubframeFixtures.stream(4, ChapterSubframeFixtures.frame("PRIV", bytes, 4, flags))
            val sink = ByteArrayAudioSink()
            assertFailsWith<CorruptStreamException> {
                AudioKodec.writeTags(source.asAudioSource(), AudioKodec.readTags(source), sink, TagWriteOptions(id3Version = 3))
            }
            assertEquals(0L, sink.position)
        }
    }

    @Test
    fun deeplyNestedChaptersStayBoundedAndAllocationGuarded() {
        var nested = ChapterSubframeFixtures.frame("TIT2", byteArrayOf(0, 65), 4)
        repeat(64) { depth ->
            val stream = ChapterSubframeFixtures.stream(4, nested, "c$depth")
            nested = stream.copyOfRange(10, ChapterSubframeFixtures.tagEnd(stream))
        }
        val source = ChapterSubframeFixtures.stream(4, nested)
        assertEquals(1, AudioKodec.readTags(source).chapters.size)
        assertContentEquals(nested, ChapterSubframeFixtures.nested(AudioKodec.writeTags(source, AudioKodec.readTags(source), TagWriteOptions(padding = 0))))
        assertFailsWith<AllocationGuardException> { AudioKodec.readTags(source, DecodeOptions(maxAllocationBytes = 128)) }
    }

    @Test
    fun malformedTailsCannotDisappearDuringAnEdit() {
        val valid = ChapterSubframeFixtures.frame("TIT2", byteArrayOf(0) + "Chapter".encodeToByteArray(), 4)
        for (tail in listOf(byteArrayOf(1), byteArrayOf(0, 1), ChapterSubframeFixtures.frame("PRIV", byteArrayOf(1), 4).dropLast(1).toByteArray())) {
            val source = ChapterSubframeFixtures.stream(4, valid + tail)
            val sink = ByteArrayAudioSink()
            assertFailsWith<CorruptStreamException> { AudioKodec.writeTags(source.asAudioSource(), AudioKodec.readTags(source), sink) }
            assertEquals(0L, sink.position)
        }
    }
}

internal object ChapterSubframeFixtures {
    val audio = ByteArray(64) { 0x55 }.also { it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte() }
    fun frame(id: String, body: ByteArray, major: Int, flags: Int = 0): ByteArray = ByteArray(10 + body.size).also {
        id.encodeToByteArray().copyInto(it)
        size(it, 4, body.size, major == 4)
        it[8] = (flags ushr 8).toByte(); it[9] = flags.toByte()
        body.copyInto(it, 10)
    }
    fun text(value: String, major: Int): ByteArray = if (major == 4) byteArrayOf(3) + value.encodeToByteArray() else {
        byteArrayOf(1, -1, -2) + value.flatMap { listOf(it.code.toByte(), (it.code ushr 8).toByte()) }.toByteArray()
    }
    fun subframes(major: Int): ByteArray = frame("TIT2", text("Chapter", major), major) +
        frame("TIT3", text("Subtitle 日本語", major), major) +
        frame("WXXX", text("Link 日本語", major) + (if (major == 4) byteArrayOf(0) else byteArrayOf(0, 0)) +
            "https://example.org/chapter".encodeToByteArray(), major) +
        frame("PRIV", "example".encodeToByteArray() + byteArrayOf(0, 1, -1, -32), major) +
        frame("ZZZZ", byteArrayOf(1, 2, 3), major)
    fun stream(major: Int, nested: ByteArray, id: String = "c1"): ByteArray {
        val body = id.encodeToByteArray() + byteArrayOf(0) + ByteArray(16) { if (it >= 8) -1 else 0 }.also { it[7] = 100 } + nested
        val frame = frame("CHAP", body, major)
        return ByteArray(10).also { "ID3".encodeToByteArray().copyInto(it); it[3] = major.toByte(); size(it, 6, frame.size, true) } + frame + audio
    }
    fun tagEnd(bytes: ByteArray): Int = 10 + readSize(bytes, 6, true)
    fun nested(bytes: ByteArray): ByteArray {
        val major = bytes[3].toInt()
        var at = 10
        while (at + 10 <= tagEnd(bytes)) {
            val size = readSize(bytes, at + 4, major == 4)
            if (bytes.copyOfRange(at, at + 4).decodeToString() == "CHAP") {
                var start = at + 10
                while (bytes[start].toInt() != 0) start++
                return bytes.copyOfRange(start + 17, at + 10 + size)
            }
            at += 10 + size
        }
        error("missing CHAP")
    }
    fun frames(bytes: ByteArray, major: Int): List<Pair<String, ByteArray>> {
        val frames = ArrayList<Pair<String, ByteArray>>()
        var at = 0
        while (at < bytes.size) {
            val length = readSize(bytes, at + 4, major == 4)
            frames.add(bytes.decodeToString(at, at + 4) to bytes.copyOfRange(at + 10, at + 10 + length))
            at += 10 + length
        }
        return frames
    }
    fun readSize(bytes: ByteArray, at: Int, safe: Boolean): Int = (0..3).fold(0) { value, index ->
        (value shl (if (safe) 7 else 8)) or (bytes[at + index].toInt() and (if (safe) 127 else 255))
    }
    fun size(bytes: ByteArray, at: Int, value: Int, safe: Boolean) {
        for (index in 0..3) bytes[at + index] = ((value ushr ((3 - index) * (if (safe) 7 else 8))) and (if (safe) 127 else 255)).toByte()
    }
}

package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.internal.tag.TagAssembler
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TagDateConversionTest {
    @Test
    fun versionThreeWritesOnlyThePrecisionItsNativeFieldsRepresent() {
        val written = AudioKodec.writeTags(DateConversionFixtures.audio,
            TagBundle(mapOf(TagField.DATE to listOf("2026-10-06T12:34"), TagField.ORIGINAL_DATE to listOf("1990"))),
            TagWriteOptions(id3Version = 3, padding = 0))
        val entries = AudioKodec.readTags(written).raw.blocks.single().entries
        assertEquals(listOf("2026"), entries.filter { it.key == "TYER" }.map { it.text })
        assertEquals(listOf("0610"), entries.filter { it.key == "TDAT" }.map { it.text })
        assertEquals(listOf("1234"), entries.filter { it.key == "TIME" }.map { it.text })
        assertEquals(listOf("1990"), entries.filter { it.key == "TORY" }.map { it.text })
    }

    @Test
    fun unrepresentablePrecisionIsRefusedBeforeOutput() {
        for ((field, value) in listOf(TagField.DATE to "2026-10-06T12:34:56", TagField.ORIGINAL_DATE to "2020-02-03")) {
            val sink = ByteArrayAudioSink()
            val error = assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags(DateConversionFixtures.audio.asAudioSource(),
                    TagBundle(mapOf(field to listOf(value))), sink, TagWriteOptions(id3Version = 3))
            }
            assertTrue(error.message.orEmpty().contains(field.name))
            assertEquals(0L, sink.position)
        }
    }

    @Test
    fun splitLegacyFramesExposeOneTimestampWithoutInventedSeconds() {
        val source = DateConversionFixtures.stream(3, listOf("TYER" to "2020", "TDAT" to "0102", "TIME" to "1230"))
        assertEquals(listOf("2020-02-01T12:30"), AudioKodec.readTags(source).all(TagField.DATE))
    }

    @Test
    fun defaultUpgradeConsumesTheAuxiliaryFrames() {
        val source = DateConversionFixtures.stream(3, listOf("TYER" to "2020", "TDAT" to "0102", "TIME" to "1230"))
        val written = AudioKodec.writeTags(source, AudioKodec.readTags(source).edit { set(TagField.TITLE, "Changed") },
            TagWriteOptions(padding = 0))
        val entries = AudioKodec.readTags(written).raw.blocks.single().entries
        assertEquals(listOf("2020-02-01T12:30"), entries.filter { it.key == "TDRC" }.map { it.text })
        assertTrue(entries.none { it.key in listOf("TYER", "TDAT", "TIME") })
        assertContentEquals(DateConversionFixtures.audio, written.copyOfRange(written.size - 32, written.size))
    }

    @Test
    fun everySupportedPrecisionKeepsItsExactMeaningAcrossRepeatedVersionChanges() {
        for (value in listOf("0000", "1990", "2020-02-29", "2000-02-29T00:00", "2026-12-31T23:59")) {
            var written = DateConversionFixtures.audio
            var tags = TagBundle(mapOf(TagField.DATE to listOf(value), TagField.ORIGINAL_DATE to listOf("1990"),
                TagField.RELEASE_DATE to listOf("2026-10-06T12:34:56")))
            for (version in listOf(3, 4, 3, 4, 3)) {
                written = AudioKodec.writeTags(written, tags, TagWriteOptions(id3Version = version, padding = 0))
                tags = AudioKodec.readTags(written)
                assertEquals(listOf(value), tags.all(TagField.DATE))
                assertEquals(listOf("1990"), tags.all(TagField.ORIGINAL_DATE))
                assertEquals(listOf("2026-10-06T12:34:56"), tags.all(TagField.RELEASE_DATE))
                assertContentEquals(DateConversionFixtures.audio, written.copyOfRange(written.size - 32, written.size))
                if (version == 4) assertTrue(tags.raw.blocks.single().entries.none { it.key in listOf("TYER", "TDAT", "TIME", "TORY") })
            }
        }
    }

    @Test
    fun legacyVersionsAndTextEncodingsCombineTheSameGroup() {
        for (version in 2..3) {
            val values = if (version == 2) listOf("TYE" to "2020", "TDA" to "0102", "TIM" to "1230", "TOR" to "1990")
                else listOf("TYER" to "2020", "TDAT" to "0102", "TIME" to "1230", "TORY" to "1990")
            for (encoding in 0..1) {
                val source = DateConversionFixtures.stream(version, values, encoding)
                val read = AudioKodec.readTags(source)
                assertEquals(listOf("2020-02-01T12:30"), read.all(TagField.DATE))
                assertEquals(listOf("1990"), read.all(TagField.ORIGINAL_DATE))
                for (target in 3..4) {
                    val written = AudioKodec.writeTags(source, read, TagWriteOptions(id3Version = target, padding = 0))
                    assertEquals(read.fields, AudioKodec.readTags(written).fields)
                    assertContentEquals(DateConversionFixtures.audio, written.copyOfRange(written.size - 32, written.size))
                }
            }
        }
    }

    @Test
    fun explicitPrimaryChangesDiscardAllOldComponentsAndKeepOtherDates() {
        val source = DateConversionFixtures.stream(3, listOf("TYER" to "2020", "TDAT" to "0102", "TIME" to "1230",
            "TRDA" to "4th-7th June", "TORY" to "1990"))
        for (replacement in listOf("2027", "2027-02-03", null)) {
            val tags = AudioKodec.readTags(source).edit {
                if (replacement == null) remove(TagField.DATE) else set(TagField.DATE, replacement)
            }
            for (version in 3..4) {
                val written = AudioKodec.writeTags(source, tags, TagWriteOptions(id3Version = version, padding = 0))
                val read = AudioKodec.readTags(written)
                assertEquals(replacement?.let { listOf(it) }.orEmpty(), read.all(TagField.DATE))
                assertEquals(listOf("1990"), read.all(TagField.ORIGINAL_DATE))
                assertTrue(read.raw.blocks.single().entries.none { it.key in listOf("TIME", "TRDA") })
                if (replacement == null || replacement.length == 4 || version == 4) {
                    assertTrue(read.raw.blocks.single().entries.none { it.key == "TDAT" })
                }
            }
        }
        // An explicit removal also removes orphan components with no modelled year.
        val orphan = DateConversionFixtures.stream(3, listOf("TDAT" to "0102", "TIME" to "1230"))
        val cleared = AudioKodec.writeTags(orphan, AudioKodec.readTags(orphan).edit { remove(TagField.DATE) })
        assertTrue(AudioKodec.readTags(cleared).raw.blocks.flatMap { it.entries }.none { it.key in listOf("TDAT", "TIME") })
    }

    @Test
    fun changingOnlyTheOriginalDateLeavesThePrimaryComponentsIntact() {
        val source = DateConversionFixtures.stream(3, listOf("TYER" to "2020", "TDAT" to "0102", "TIME" to "1230", "TORY" to "1990"))
        val written = AudioKodec.writeTags(source, AudioKodec.readTags(source).edit { remove(TagField.ORIGINAL_DATE) })
        val read = AudioKodec.readTags(written)
        assertEquals(listOf("2020-02-01T12:30"), read.all(TagField.DATE))
        assertEquals(emptyList(), read.all(TagField.ORIGINAL_DATE))
    }

    @Test
    fun incompleteInvalidAndFreeFormGroupsRemainRawOrRefuseConversion() {
        val groups = listOf(
            listOf("TYER" to "2020", "TIME" to "1230"),
            listOf("TDAT" to "0102"),
            listOf("TIME" to "1230"),
            listOf("TYER" to "2021", "TDAT" to "2902"),
            listOf("TYER" to "2020", "TDAT" to "0000"),
            listOf("TYER" to "2020", "TDAT" to "0102", "TIME" to "2400"),
            listOf("TYER" to "2020", "TDAT" to "0102", "TIME" to "1260"),
            listOf("TYER" to "2020", "TDAT" to "1st February"),
            listOf("TYER" to "2020", "TRDA" to "4th-7th June"),
        )
        for (values in groups) {
            val source = DateConversionFixtures.stream(3, values)
            val tags = AudioKodec.readTags(source).edit { set(TagField.TITLE, "Unrelated") }
            val kept = AudioKodec.writeTags(source, tags, TagWriteOptions(id3Version = 3, padding = 0))
            val read = AudioKodec.readTags(kept)
            for ((key, value) in values) assertEquals(listOf(value), read.raw.blocks.single().entries.filter { it.key == key }.map { it.text })
            val sink = ByteArrayAudioSink()
            val error = assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags(source.asAudioSource(), tags, sink)
            }
            assertTrue(error.message.orEmpty().contains("DATE"))
            assertEquals(0L, sink.position)
            assertContentEquals(DateConversionFixtures.audio, kept.copyOfRange(kept.size - 32, kept.size))
        }
    }

    @Test
    fun ambiguousLegacyListsAreNeverPairedIntoGuessedTimestamps() {
        for (values in listOf(
            listOf("TYER" to "2020", "TYER" to "2021", "TDAT" to "0102"),
            listOf("TYER" to "2020", "TDAT" to "0102", "TDAT" to "0203"),
            listOf("TYER" to "2020", "TDAT" to "0102", "TIME" to "1230", "TIME" to "1430"),
        )) {
            val source = DateConversionFixtures.stream(3, values)
            val tags = AudioKodec.readTags(source)
            assertTrue(tags.all(TagField.DATE).all { it.length == 4 })
            val sink = ByteArrayAudioSink()
            assertFailsWith<UnsupportedFeatureException> { AudioKodec.writeTags(source.asAudioSource(), tags, sink) }
            assertEquals(0L, sink.position)
        }
    }

    @Test
    fun separateBlocksKeepTheirOwnYearDayAndTimeAssociations() {
        val first = AudioKodec.readTags(DateConversionFixtures.stream(3, listOf("TYER" to "2020", "TDAT" to "0102", "TIME" to "1230")))
        val second = AudioKodec.readTags(DateConversionFixtures.stream(3, listOf("TYER" to "2021", "TDAT" to "0304", "TIME" to "1430")))
        val tags = TagAssembler.assemble(first.raw.blocks + second.raw.blocks)
        val expected = listOf("2020-02-01T12:30", "2021-04-03T14:30")
        assertEquals(expected, tags.all(TagField.DATE))
        val written = AudioKodec.writeTags(DateConversionFixtures.audio, tags)
        val read = AudioKodec.readTags(written)
        assertEquals(expected, read.all(TagField.DATE))
        assertTrue(read.raw.blocks.single().entries.none { it.key in listOf("TYER", "TDAT", "TIME") })
    }

    @Test
    fun allLegacyPrecisionAndCalendarRefusalsLeaveAnEmptySink() {
        val invalid = listOf("", "20", "10000", "２０２０", "2026-10", "2026-10-06T12", "2026-10-06T12:34:00",
            "2026-10-06T12:34Z", "2026-10-06T12:34+01:00", "2020/2021", "1900-02-29", "2026-04-31",
            "2026-00-01", "2026-13-01", "2026-01-00", "2026-01-32", "2026-10-06T24:00", "2026-10-06T12:60",
            "2026-1x-06", "2026-10-0x", "2026-10-06Txx:34", "2026-10-06T12:xx", "2026/10/06", "2026-10-06 12:34",
            "x".repeat(10_000))
        for (values in invalid.map { listOf(it) } + listOf(listOf("2020", "2021"))) {
            val sink = ByteArrayAudioSink()
            val error = assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags(DateConversionFixtures.audio.asAudioSource(), TagBundle(mapOf(TagField.DATE to values)), sink,
                    TagWriteOptions(id3Version = 3))
            }
            assertTrue(error.message.orEmpty().contains("DATE"))
            assertEquals(0L, sink.position)
        }
    }

    @Test
    fun supportedDatesWorkInEmbeddedAndExternalId3Carriers() {
        val fields = mapOf(TagField.DATE to listOf("2020-02-01T12:30"), TagField.ORIGINAL_DATE to listOf("1990"))
        val prefix = DateConversionFixtures.stream(3, listOf("TYER" to "1990")).let { it.copyOf(it.size - 32) }
        val sources = listOf(DateConversionFixtures.audio to false, NativeTextListFixtures.wave() to false,
            NativeTextListFixtures.rf64() to false, NativeTextListFixtures.aiff() to false,
            NativeTextListFixtures.aiff(EncodeTarget.aiffFloat()) to false,
            (prefix + TagStressFixtures.flac(emptyList())) to true, (prefix + R128TagFixtures.opus()) to true)
        for ((source, external) in sources) {
            var written = source
            var tags = TagBundle(fields)
            for (version in listOf(3, 4, 3)) {
                val format = if (version == 3) TagFormat.ID3V2_3 else TagFormat.ID3V2_4
                written = AudioKodec.writeTags(written, tags,
                    TagWriteOptions(formats = if (external) null else setOf(format), id3Version = version, padding = 0))
                tags = AudioKodec.readTags(written)
                assertEquals(fields, tags.fields)
            }
        }
    }

    @Test
    fun opaqueAuxiliaryDatesArePreservedOrExplicitlyReplaced() {
        val source = DateConversionFixtures.stream(3, listOf("TYER" to "2020", "TDAT" to "0102"))
        val at = source.decodeToString().indexOf("TDAT")
        source[at + 9] = 0x80.toByte()
        val tags = AudioKodec.readTags(source)
        assertEquals(listOf("2020"), tags.all(TagField.DATE))
        val stored = tags.raw.blocks.single().entries.single { it.key == "TDAT" }
        assertEquals(0x80, stored.id3FrameFlags)
        val kept = AudioKodec.readTags(AudioKodec.writeTags(source, tags, TagWriteOptions(id3Version = 3)))
            .raw.blocks.single().entries.single { it.key == "TDAT" }
        assertEquals(stored.id3FrameFlags, kept.id3FrameFlags)
        assertContentEquals(stored.binary, kept.binary)
        val sink = ByteArrayAudioSink()
        assertFailsWith<UnsupportedFeatureException> { AudioKodec.writeTags(source.asAudioSource(), tags, sink) }
        assertEquals(0L, sink.position)
        for (remove in listOf(false, true)) {
            val edited = tags.edit { if (remove) remove(TagField.DATE) else set(TagField.DATE, "2020") }
            val read = AudioKodec.readTags(AudioKodec.writeTags(source, edited))
            assertEquals(if (remove) emptyList() else listOf("2020"), read.all(TagField.DATE))
            assertTrue(read.raw.blocks.flatMap { it.entries }.none { it.key == "TDAT" })
        }
    }

    @Test
    fun replacementsConstructedWithRawTagsDoNotReviveOldAuxiliaryValues() {
        val source = DateConversionFixtures.stream(3, listOf("TYER" to "2020", "TDAT" to "0102", "TIME" to "1230", "TRDA" to "June"))
        val raw = AudioKodec.readTags(source).raw
        for (fields in listOf(mapOf(TagField.DATE to listOf("2027")), emptyMap())) {
            val tags = TagBundle(fields, raw = raw)
            val read = AudioKodec.readTags(AudioKodec.writeTags(source, tags))
            assertEquals(fields, read.fields)
            assertTrue(read.raw.blocks.flatMap { it.entries }.none { it.key in listOf("TYER", "TDAT", "TIME", "TRDA") })
        }
    }
}

internal object DateConversionFixtures {
    val audio = ByteArray(32) { 0x55 }

    fun stream(version: Int, values: List<Pair<String, String>>, encoding: Int = 0): ByteArray {
        val frames = values.map { (key, value) ->
            val text = when (encoding) {
                1 -> byteArrayOf(-1, -2) + value.flatMap { listOf(it.code.toByte(), (it.code ushr 8).toByte()) }.toByteArray()
                2 -> value.flatMap { listOf((it.code ushr 8).toByte(), it.code.toByte()) }.toByteArray()
                else -> value.encodeToByteArray()
            }
            val body = byteArrayOf(encoding.toByte()) + text
            key.encodeToByteArray() + when (version) {
                2 -> byteArrayOf((body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte())
                3 -> byteArrayOf(0, 0, (body.size ushr 8).toByte(), body.size.toByte(), 0, 0)
                else -> syncsafe(body.size) + byteArrayOf(0, 0)
            } + body
        }.fold(byteArrayOf()) { result, frame -> result + frame }
        return "ID3".encodeToByteArray() + byteArrayOf(version.toByte(), 0, 0) + syncsafe(frames.size) + frames + audio
    }

    private fun syncsafe(value: Int): ByteArray = ByteArray(4) { (value ushr ((3 - it) * 7) and 127).toByte() }
}

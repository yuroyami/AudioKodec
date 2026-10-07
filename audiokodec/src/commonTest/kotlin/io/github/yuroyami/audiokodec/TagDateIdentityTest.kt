package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TagDateIdentityTest {
    @Test
    fun unrelatedEditsKeepBothDateMeaningsInTheirNativeFrames() {
        val source = DateIdentityFixtures.stream(recording = "2000-01-02", release = "2026-10-06")
        var written = source
        repeat(3) { index ->
            written = AudioKodec.writeTags(written, AudioKodec.readTags(written).edit { set(TagField.TITLE, "Edit $index") },
                TagWriteOptions(padding = 0))
            assertEquals(listOf("2000-01-02"), DateIdentityFixtures.values(written, "TDRC"))
            assertEquals(listOf("2026-10-06"), DateIdentityFixtures.values(written, "TDRL"))
        }
    }

    @Test
    fun aReleaseOnlyTagNeverBecomesARecordingDate() {
        val source = DateIdentityFixtures.stream(release = "2026-10-06T12:30:45")
        val written = AudioKodec.writeTags(source, AudioKodec.readTags(source), TagWriteOptions(padding = 0))
        assertEquals(emptyList(), DateIdentityFixtures.values(written, "TDRC"))
        assertEquals(listOf("2026-10-06T12:30:45"), DateIdentityFixtures.values(written, "TDRL"))
    }

    @Test
    fun clearingThePrimaryDateKeepsTheSeparateReleaseDate() {
        val source = DateIdentityFixtures.stream(recording = "2000-01-02", release = "2026-10-06")
        val written = AudioKodec.writeTags(source, AudioKodec.readTags(source).edit { remove(TagField.DATE) },
            TagWriteOptions(padding = 0))
        assertEquals(emptyList(), DateIdentityFixtures.values(written, "TDRC"))
        assertEquals(listOf("2026-10-06"), DateIdentityFixtures.values(written, "TDRL"))
    }

    @Test
    fun bothTimestampsRemainSeparateEvenWhenTheirValuesMatch() {
        for (encoding in 0..3) {
            val source = DateIdentityFixtures.stream(recording = "2026-10-06", release = "2026-10-06", encoding = encoding)
            val fields = mapOf(TagField.DATE to listOf("2026-10-06"), TagField.RELEASE_DATE to listOf("2026-10-06"))
            val read = AudioKodec.readTags(source)
            assertEquals(fields, read.fields)
            assertEquals(fields, AudioKodec.readTags(AudioKodec.writeTags(source, read)).fields)
        }
    }

    @Test
    fun eachDateCanBeEditedOrRemovedIndependently() {
        val source = DateIdentityFixtures.stream(recording = "2000-01-02", release = "2026-10-06")
        for (field in listOf(TagField.DATE, TagField.RELEASE_DATE)) {
            for (replacement in listOf("2027-02-03T12:34:56", null)) {
                val edited = AudioKodec.readTags(source).edit {
                    if (replacement == null) remove(field) else set(field, replacement)
                }
                val written = AudioKodec.writeTags(source, edited, TagWriteOptions(padding = 0))
                val changed = replacement?.let { listOf(it) }.orEmpty()
                assertEquals(if (field == TagField.DATE) changed else listOf("2000-01-02"),
                    DateIdentityFixtures.values(written, "TDRC"))
                assertEquals(if (field == TagField.RELEASE_DATE) changed else listOf("2026-10-06"),
                    DateIdentityFixtures.values(written, "TDRL"))
                assertContentEquals(DateIdentityFixtures.audio, written.copyOfRange(written.size - 32, written.size))
            }
        }
    }

    @Test
    fun nativeAndLegacyCarriersRetainTheExplicitReleaseDate() {
        val fields = mapOf(TagField.DATE to listOf("2000"), TagField.RELEASE_DATE to listOf("2026-10-06T12:34:56"))
        val sources = listOf(NativeTextListFixtures.wave(), NativeTextListFixtures.rf64(), NativeTextListFixtures.aiff(),
            TagStressFixtures.flac(emptyList()), R128TagFixtures.opus(), DateIdentityFixtures.audio)
        for (source in sources) {
            var written = AudioKodec.writeTags(source, TagBundle(fields))
            repeat(3) {
                val read = AudioKodec.readTags(written)
                assertEquals(fields, read.fields)
                written = AudioKodec.writeTags(written, read)
            }
        }
        for (format in listOf(TagFormat.ID3V2_3, TagFormat.ID3V2_4, TagFormat.APEV2)) {
            val options = TagWriteOptions(formats = setOf(format), padding = 0)
            var written = AudioKodec.writeTags(DateIdentityFixtures.audio, TagBundle(fields), options)
            repeat(3) {
                val read = AudioKodec.readTags(written)
                assertEquals(fields, read.fields)
                assertContentEquals(DateIdentityFixtures.audio, written.copyOfRange(
                    if (format == TagFormat.APEV2) 0 else (read.raw.blocks.first().size).toInt(),
                    if (format == TagFormat.APEV2) DateIdentityFixtures.audio.size else written.size))
                written = AudioKodec.writeTags(written, read, options)
            }
            if (format == TagFormat.ID3V2_3) {
                val entries = AudioKodec.readTags(written).raw.blocks.single().entries
                assertEquals(listOf("2026-10-06T12:34:56"), entries.filter { it.key == "TXXX" && it.description == "RELEASEDATE" }.map { it.text })
                assertTrue(entries.none { it.key == "TDRL" })
            }
        }
    }

    @Test
    fun releaseListsUseV24AndNamedV23RefusalBeforeOutput() {
        val fields = mapOf(TagField.DATE to listOf("2000"), TagField.RELEASE_DATE to listOf("2026-10-06", "2027-02-03"))
        val written = AudioKodec.writeTags(DateIdentityFixtures.audio, TagBundle(fields))
        assertEquals(fields, AudioKodec.readTags(written).fields)
        val sink = ByteArrayAudioSink()
        val error = assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.writeTags(DateIdentityFixtures.audio.asAudioSource(), TagBundle(fields), sink, TagWriteOptions(id3Version = 3))
        }
        assertTrue(error.message.orEmpty().contains("RELEASEDATE"))
        assertEquals(0L, sink.position)
    }

    @Test
    fun freshEncodingRetainsBothDateFields() {
        val fields = mapOf(TagField.DATE to listOf("2000-01-02"), TagField.RELEASE_DATE to listOf("2026-10-06T12:34:56"))
        for (target in listOf(EncodeTarget.wav(), EncodeTarget.aiff(), EncodeTarget.aiffFloat(), EncodeTarget.flac())) {
            val plain = AudioKodec.encode(NativeTextListFixtures.samples, NativeTextListFixtures.format, target)
            val tagged = AudioKodec.encode(NativeTextListFixtures.samples, NativeTextListFixtures.format, target, TagBundle(fields))
            assertEquals(fields, AudioKodec.readTags(tagged).fields)
            if (target.container == AudioContainer.FLAC) {
                assertContentEquals(TagStressFixtures.flacAudio(plain), TagStressFixtures.flacAudio(tagged))
            } else {
                assertContentEquals(NativeTextListFixtures.payload(plain), NativeTextListFixtures.payload(tagged))
            }
        }
    }

    @Test
    fun releaseFieldIsAppendedAfterAllExistingOrdinals() {
        assertEquals(47, TagField.RELEASE_DATE.ordinal)
        assertEquals(8, TagField.DATE.ordinal)
        assertEquals(9, TagField.ORIGINAL_DATE.ordinal)
        assertEquals(46, TagField.ENCODED_BY.ordinal)
    }

}

internal object DateIdentityFixtures {
    val audio = ByteArray(32) { 0x55 }

    fun stream(recording: String? = null, release: String? = null, encoding: Int = 3): ByteArray {
        fun frame(key: String, value: String): ByteArray {
            val text = when (encoding) {
                1 -> byteArrayOf(-1, -2) + value.flatMap { listOf(it.code.toByte(), 0.toByte()) }.toByteArray()
                2 -> value.flatMap { listOf(0.toByte(), it.code.toByte()) }.toByteArray()
                else -> value.encodeToByteArray()
            }
            val body = byteArrayOf(encoding.toByte()) + text
            return key.encodeToByteArray() + syncsafe(body.size) + byteArrayOf(0, 0) + body
        }
        val frames = (recording?.let { frame("TDRC", it) } ?: byteArrayOf()) +
            (release?.let { frame("TDRL", it) } ?: byteArrayOf())
        return "ID3".encodeToByteArray() + byteArrayOf(4, 0, 0) + syncsafe(frames.size) + frames + audio
    }

    fun values(bytes: ByteArray, key: String): List<String> =
        AudioKodec.readTags(bytes).raw.blocksOf(TagFormat.ID3V2_4).flatMap { it.entries }
            .filter { it.key == key }.mapNotNull { it.text }

    private fun syncsafe(value: Int): ByteArray = ByteArray(4) { (value ushr ((3 - it) * 7) and 127).toByte() }
}

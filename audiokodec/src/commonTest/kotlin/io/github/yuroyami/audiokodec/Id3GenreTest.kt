package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.tag.TagAssembler
import io.github.yuroyami.audiokodec.tag.RawTagBlock
import io.github.yuroyami.audiokodec.tag.RawTagEntry
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Id3GenreTest {
    private val audio = ByteArray(21) { 0x55 }

    @Test
    fun leadingReferencesAndRefinementsKeepEveryDistinctGenreInFileOrder() {
        val cases = mapOf(
            "(17)" to listOf("Rock"),
            "(17)Rock" to listOf("Rock"),
            "(4)Eurodisco" to listOf("Disco", "Eurodisco"),
            "(51)(39)" to listOf("Techno-Industrial", "Noise"),
            "(51)(39)Eurodisco" to listOf("Techno-Industrial", "Noise", "Eurodisco"),
            "(RX)(CR)" to listOf("Remix", "Cover"),
            "(17)(17)Rock" to listOf("Rock"),
            "((I Love You)" to listOf("(I Love You)"),
            "(55)((I think...)" to listOf("Dream", "(I think...)"),
            "(17)17" to listOf("Rock", "17"),
        )
        for (version in 2..4) for ((stored, expected) in cases) {
            val tags = AudioKodec.readTags(fixture(version, stored))
            assertEquals(expected, tags.all(TagField.GENRE), "v2.$version '$stored'")
            assertEquals(stored, tags.raw.blocks.single().entries.single().text)
        }
    }

    @Test
    fun version24ListsAndCompatibilityCodesRemainSeparate() {
        val tags = AudioKodec.readTags(fixture(4, "51\u000039\u0000Eurodisco\u0000RX\u0000CR\u0000Noise"))
        assertEquals(listOf("Techno-Industrial", "Noise", "Eurodisco", "Remix", "Cover"), tags.all(TagField.GENRE))
        for (version in 2..4) {
            assertEquals(listOf("Rock"), AudioKodec.readTags(fixture(version, "17")).all(TagField.GENRE))
            assertEquals(listOf("Remix"), AudioKodec.readTags(fixture(version, "RX")).all(TagField.GENRE))
        }
    }

    @Test
    fun unknownAndMalformedSyntaxStaysLiteralWithoutDiscardingKnownPrefixes() {
        val cases = mapOf(
            "(999)(17)" to listOf("(999)(17)"),
            "(17)(999)" to listOf("Rock", "(999)"),
            "(foo)(17)" to listOf("(foo)(17)"),
            "(17)(broken" to listOf("Rock", "(broken"),
            "(+17)" to listOf("(+17)"),
            "+17" to listOf("+17"),
            "(17" to listOf("(17"),
            "()" to listOf("()"),
            "(999999999999999999999)" to listOf("(999999999999999999999)"),
        )
        for (version in 2..4) for ((stored, expected) in cases) {
            val original = fixture(version, stored)
            val tags = AudioKodec.readTags(original)
            assertEquals(expected, tags.all(TagField.GENRE))
            for (outputVersion in 3..4) {
                val rewritten = AudioKodec.writeTags(original, tags, TagWriteOptions(id3Version = outputVersion, padding = 0))
                assertEquals(expected, AudioKodec.readTags(rewritten).all(TagField.GENRE))
                assertContentEquals(audio, rewritten.takeLast(audio.size).toByteArray())
            }
        }
    }

    @Test
    fun nonId3GenresRemainFreeText() {
        for ((format, key) in listOf(
            TagFormat.VORBIS_COMMENT to "GENRE", TagFormat.APEV2 to "GENRE",
            TagFormat.RIFF_INFO to "IGNR", TagFormat.MP4_ILST to "©gen",
            TagFormat.ID3V1 to "GENRE",
        )) for (literal in listOf("17", "RX", "(51)(39)", "((I Love You)")) {
            val tags = TagAssembler.assemble(listOf(RawTagBlock(format, 0, 0, listOf(RawTagEntry(key, text = literal)))))
            assertEquals(listOf(literal), tags.all(TagField.GENRE), "$format '$literal'")
        }
    }

    @Test
    fun rewritesKeepAllGenresLiteralParenthesesAndExactAudioBytes() {
        for (version in 3..4) for (values in listOf(
            listOf("Techno-Industrial", "Noise", "Eurodisco"),
            listOf("Remix", "Cover"), listOf("Dream", "(I think...)"),
            listOf("(51)"), listOf("((Literal)"), listOf("日本語 genre"),
            listOf("Rock", "17"),
        )) {
            if (version == 4 && values.last() == "17") continue
            val written = AudioKodec.writeTags(audio, TagBundle(mapOf(TagField.GENRE to values)), TagWriteOptions(id3Version = version, padding = 0))
            val read = AudioKodec.readTags(written)
            assertEquals(values, read.all(TagField.GENRE), "v2.$version $values")
            val rewritten = AudioKodec.writeTags(written, read.edit { set(TagField.TITLE, "Changed") }, TagWriteOptions(id3Version = version, padding = 0))
            assertEquals(values, AudioKodec.readTags(rewritten).all(TagField.GENRE))
            assertContentEquals(audio, rewritten.takeLast(audio.size).toByteArray())
        }
    }

    @Test
    fun version23RefusesListsThatNeedSeveralRefinementsOrReorderingBeforeOutput() {
        for (values in listOf(listOf("Custom one", "Custom two"), listOf("Custom first", "Rock"))) {
            val sink = ByteArrayAudioSink()
            assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags(audio.asAudioSource(), TagBundle(mapOf(TagField.GENRE to values)), sink, TagWriteOptions(id3Version = 3))
            }
            assertEquals(0L, sink.position)
            val written = AudioKodec.writeTags(audio, TagBundle(mapOf(TagField.GENRE to values)), TagWriteOptions(id3Version = 4))
            assertEquals(values, AudioKodec.readTags(written).all(TagField.GENRE))
        }
    }

    @Test
    fun reservedStandaloneLiteralsAreRefusedBeforeOutput() {
        for (version in 3..4) for (literal in listOf("17", "RX", "CR", "017")) {
            val sink = ByteArrayAudioSink()
            assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags(audio.asAudioSource(), TagBundle(mapOf(TagField.GENRE to listOf(literal))), sink, TagWriteOptions(id3Version = version))
            }
            assertEquals(0L, sink.position)
        }
    }

    @Test
    fun longReferenceRunsAndTruncatedGrammarRemainBounded() {
        val repeated = "(17)".repeat(10_000) + "((Literal)"
        assertEquals(listOf("Rock", "(Literal)"), AudioKodec.readTags(fixture(3, repeated)).all(TagField.GENRE))
        for (stored in listOf("(51)(39)Eurodisco", "(55)((I think...)", "(17)(broken")) {
            for (length in stored.indices) {
                AudioKodec.readTags(fixture(3, stored.substring(0, length)))
            }
        }
    }

    // Construct the stored grammar directly, independently of the writer.
    private fun fixture(version: Int, stored: String): ByteArray {
        val payload = byteArrayOf((if (version == 4) 3 else 0).toByte()) + stored.encodeToByteArray()
        val frame = if (version == 2) "TCO".encodeToByteArray() + size(payload.size, 3, 8) + payload
            else "TCON".encodeToByteArray() + size(payload.size, 4, if (version == 4) 7 else 8) + byteArrayOf(0, 0) + payload
        return "ID3".encodeToByteArray() + byteArrayOf(version.toByte(), 0, 0) + size(frame.size, 4, 7) + frame + audio
    }

    private fun size(value: Int, bytes: Int, shift: Int): ByteArray = ByteArray(bytes) { at ->
        ((value ushr ((bytes - at - 1) * shift)) and ((1 shl shift) - 1)).toByte()
    }
}

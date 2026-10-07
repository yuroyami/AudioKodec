package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class TagScalingTest {
    @Test
    fun largePublicFlacRewritesRetainEveryUnknownCommentAndRecordScaling() {
        repeat(2) {
            val warm = TagStressFixtures.flac(List(8_000) { "CUSTOM$it=value" })
            AudioKodec.writeTags(warm, AudioKodec.readTags(warm).edit { set(TagField.TITLE, "Warm") })
        }
        val discrepancies = mutableListOf<String>()
        for (kind in listOf("keys", "values", "same", "collisions")) {
            for (count in listOf(8_000, 16_000, 32_000, 64_000)) {
                val comments = List(count) {
                    when (kind) {
                        "keys" -> "CUSTOM$it=value"
                        "values" -> "PRIVATE=value-$it"
                        "same" -> "PRIVATE=same"
                        else -> "PRIVATE=${TagStressFixtures.collidingText(it)}"
                    }
                }
                val file = TagStressFixtures.flac(comments)
                val tags = AudioKodec.readTags(file).edit { set(TagField.TITLE, "Changed") }
                val start = System.nanoTime()
                val written = AudioKodec.writeTags(file, tags)
                val elapsed = (System.nanoTime() - start) / 1_000_000.0
                val actual = TagStressFixtures.flacComments(written).second
                if (actual != listOf("TITLE=Changed") + comments) discrepancies.add("$kind:$count output=${actual.size}")
                assertContentEquals(TagStressFixtures.flacAudio(file), TagStressFixtures.flacAudio(written))
                println("tag-rewrite kind=$kind entries=$count bytes=${file.size} milliseconds=$elapsed")
            }
        }
        assertEquals(emptyList(), discrepancies)
    }

    @Test
    fun largePublicFlacTagReadsKeepEveryValueAndRecordScaling() {
        repeat(2) { AudioKodec.readTags(TagStressFixtures.flac(List(8_000) { "ARTIST=artist-$it" })) }
        for (kind in listOf("artist", "custom", "same")) for (count in listOf(8_000, 16_000, 32_000, 64_000)) {
            val values = List(count) { "artist-$it" }
            val comments = when (kind) {
                "artist" -> values.map { "ARTIST=$it" }
                "custom" -> values.map { "PRIVATE=$it" }
                else -> List(count) { "ARTIST=same" }
            }
            val file = TagStressFixtures.flac(comments)
            val start = System.nanoTime()
            val tags = AudioKodec.readTags(file)
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            when (kind) {
                "artist" -> assertEquals(values, tags.all(TagField.ARTIST))
                "custom" -> assertEquals(emptyList(), tags.all(TagField.ARTIST))
                else -> assertEquals(listOf("same"), tags.all(TagField.ARTIST))
            }
            assertEquals(count + 1, tags.raw.blocks.single().entries.size)
            println("tag-read kind=$kind entries=$count bytes=${file.size} milliseconds=$elapsed")
        }
    }
}

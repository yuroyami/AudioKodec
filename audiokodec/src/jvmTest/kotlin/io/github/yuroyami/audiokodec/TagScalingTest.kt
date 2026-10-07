package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagField
import kotlin.test.Test
import kotlin.test.assertEquals

class TagScalingTest {
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

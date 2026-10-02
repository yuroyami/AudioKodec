package io.github.yuroyami.audiokodecs

import io.github.yuroyami.audiokodecs.tag.TagBundle
import io.github.yuroyami.audiokodecs.tag.TagField
import io.github.yuroyami.audiokodecs.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tag writes that must keep what the file meant, not only what the merged
 * view shows.
 *
 * Each case here is a write that once succeeded and quietly changed the
 * meaning of an entry: a URL nobody could read back, a comment that lost its
 * language, a loudness value relabelled to another reference.
 */
class TagPreservationTest {

    private val stream = ByteArray(32) { 0x55 }

    @Test
    fun urlFramesHoldTheUrlWithNoEncodingByte() {
        val urls = listOf("https://example.com/artist", "https://example.org/other")
        val tags = TagBundle(mapOf(TagField.URL to urls))
        for (version in listOf(3, 4)) {
            val bytes = AudioKodecs.writeTags(stream, tags, TagWriteOptions(id3Version = version))
            val woar = bytes.indexOfBytes("WOAR".encodeToByteArray())
            // The body begins directly after the ten-byte frame header.
            assertEquals('h'.code.toByte(), bytes[woar + 10], "ID3v2.$version URL body")
            val read = AudioKodecs.readTags(bytes)
            assertEquals(urls, read.all(TagField.URL), "ID3v2.$version")
        }
    }
}

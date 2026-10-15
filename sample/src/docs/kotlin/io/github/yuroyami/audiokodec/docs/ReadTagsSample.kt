package io.github.yuroyami.audiokodec.docs

// --8<-- [start:imports]
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.tag.TagField
// --8<-- [end:imports]
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadTagsSample {
    private val bytes = Fixtures.flac
    private val printed = ArrayList<String>()

    private fun println(line: Any?) {
        printed += line.toString()
    }

    @Test
    fun readTags() {
        // --8<-- [start:read]
        val tags = AudioKodec.readTags(bytes)

        val title: String? = tags[TagField.TITLE]             // the first value, or null
        val artists: List<String> = tags.all(TagField.ARTIST) // every value, in order
        val track: Int? = tags.number(TagField.TRACK_NUMBER)  // a number, or null

        for ((field, values) in tags.fields) {
            println("$field: ${values.joinToString(" / ")}")
        }
        // --8<-- [end:read]

        assertEquals("Two Seconds", title)
        assertEquals(listOf("First Artist", "Second Artist"), artists)
        assertEquals(3, track)
        assertTrue("ARTIST: First Artist / Second Artist" in printed, "$printed")
    }

    @Test
    fun readTheBlocksAsStored() {
        val tags = AudioKodec.readTags(bytes)

        // --8<-- [start:raw]
        println(tags.raw.formats.map { it.displayName })  // [Vorbis comment]
        for (block in tags.raw.blocks) {
            println("${block.format.displayName} at ${block.offset}, ${block.size} bytes")
            for (entry in block.entries) println("  ${entry.key} = ${entry.text}")
        }
        // --8<-- [end:raw]

        assertEquals("[Vorbis comment]", printed.first())
        assertTrue("  TITLE = Two Seconds" in printed, "$printed")
    }

    @Test
    fun theSameCallReadsId3FromAnMp3() {
        val mp3 = Fixtures.ffmpegEncode(Fixtures.samples, "read.mp3", "-c:a", "libmp3lame", "-metadata", "title=From ffmpeg")
        assertEquals("From ffmpeg", AudioKodec.readTags(mp3)[TagField.TITLE])
    }
}

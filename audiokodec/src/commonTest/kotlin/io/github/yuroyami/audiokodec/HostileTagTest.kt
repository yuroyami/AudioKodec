package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the tag reader does with blocks that are wrong.
 *
 * Tag blocks are the least trustworthy bytes in any audio file. They are
 * written by decades of different programs, edited by more, and truncated by
 * transfers that went wrong. The reader has to survive all of it, and these
 * tests are what say so.
 */
class HostileTagTest {

    private val good: ByteArray = run {
        val audio = ByteArray(3_000) { ((it * 29 + 7) and 0xFF).toByte() }
        audio[0] = 0xFF.toByte()
        audio[1] = 0xFB.toByte()
        AudioKodec.writeTags(
            audio,
            TagBundle(
                mapOf(
                    TagField.TITLE to listOf("A Title"),
                    TagField.ARTIST to listOf("An Artist"),
                    TagField.ALBUM to listOf("An Album"),
                    TagField.COMMENT to listOf("A comment that is reasonably long"),
                    TagField.MUSICBRAINZ_RECORDING_ID to listOf("ebf79ba5-085e-48d2-9eb8-2d992fbf0f6d"),
                    TagField.CATALOG_NUMBER to listOf("first", "日本語 second"),
                    TagField.GENRE to listOf("Techno-Industrial", "Noise", "(Literal genre)"),
                ),
            ),
        )
    }

    @Test
    fun textListsInEveryEncodingKeepTypedFailuresUnderTruncationAndMutation() {
        for (encoding in Id3TextListFixtures.encodings) {
            val original = Id3TextListFixtures.tag(encoding)
            for (length in 0..original.size) {
                expectOnlyKiteFailures("text list $encoding truncated to $length bytes") {
                    AudioKodec.readTags(original.copyOf(length))
                }
            }
            for (at in original.indices) {
                for (value in byteArrayOf(0, 0x80.toByte(), 0xFF.toByte())) {
                    val mutated = original.copyOf()
                    mutated[at] = value
                    expectOnlyKiteFailures("text list $encoding byte $at set to $value") {
                        AudioKodec.readTags(mutated)
                    }
                }
            }
        }
    }

    @Test
    fun everyTruncationOfATaggedFileIsHandled() {
        for (length in 0 until good.size step 11) {
            expectOnlyKiteFailures("truncated to $length bytes") {
                AudioKodec.readTags(good.copyOf(length))
            }
        }
    }

    @Test
    fun mutatingSingleBytesOfTheTagBlockNeverBreaksTheReader() {
        var state = 24_680
        repeat(6_000) {
            state = state * 1_103_515_245 + 12_345
            // The tag block is at the front, so the mutations concentrate
            // there, where the parsing happens.
            val at = ((state ushr 8) and 0x7FFF_FFFF) % minOf(good.size, 1_500)
            state = state * 1_103_515_245 + 12_345
            val value = ((state ushr 16) and 0xFF).toByte()
            val mutated = good.copyOf()
            mutated[at] = value
            expectOnlyKiteFailures("byte $at set to $value") {
                AudioKodec.readTags(mutated)
            }
        }
    }

    @Test
    fun malformedLyrics3FieldsStayInsideTheTypedFailureContract() {
        fun lyrics3(body: ByteArray): ByteArray {
            val block = "LYRICSBEGIN".encodeToByteArray() + body
            return block + block.size.toString().padStart(6, '0').encodeToByteArray() +
                "LYRICS200".encodeToByteArray()
        }
        val audio = ByteArray(32) { 0x55 }

        // A key starting with a null once escaped as IllegalArgumentException.
        val nullKey = audio + lyrics3(byteArrayOf(0, 65, 66) + "00001X".encodeToByteArray())
        expectOnlyKiteFailures("a Lyrics3 key with a null") {
            assertTrue(AudioKodec.readTags(nullKey).raw.blocks.flatMap { it.entries }.isEmpty())
        }

        // Every byte of two well-formed fields, set to every value that could
        // matter: nulls, digits, letters, spaces and high bytes.
        val good = lyrics3("IND00002".encodeToByteArray() + "10".encodeToByteArray() +
            "INF00005".encodeToByteArray() + "Hello".encodeToByteArray())
        val values = byteArrayOf(0, ' '.code.toByte(), '0'.code.toByte(), '9'.code.toByte(),
            'A'.code.toByte(), 'z'.code.toByte(), 0x7F, 0x80.toByte(), 0xFF.toByte())
        for (at in good.indices) {
            for (value in values) {
                val mutated = good.copyOf()
                mutated[at] = value
                expectOnlyKiteFailures("Lyrics3 byte $at set to $value") {
                    AudioKodec.readTags(audio + mutated)
                }
            }
        }
    }

    @Test
    fun aFrameClaimingToBeLargerThanTheBlockIsIgnored() {
        val file = good.copyOf()
        val at = file.indexOfBytes("TIT2".encodeToByteArray())
        assertTrue(at > 0, "the title frame is missing")
        // A syncsafe size of 0x7F7F7F7F, which is far past the end.
        for (i in 4..7) file[at + i] = 0x7F
        expectOnlyKiteFailures("a frame claiming to be enormous") {
            val tags = AudioKodec.readTags(file)
            // The reader should stop at the bad frame rather than invent one.
            assertTrue(tags.all(TagField.TITLE).size <= 1)
        }
    }

    @Test
    fun aBlockHeaderClaimingAnEnormousSizeDoesNotAllocateIt() {
        val file = good.copyOf()
        // The tag size is four syncsafe bytes at offset six.
        for (i in 6..9) file[i] = 0x7F
        expectOnlyKiteFailures("a block claiming 256 MiB") {
            AudioKodec.readTags(file)
        }
    }

    @Test
    fun randomBytesBehindATagMagicNumberAreSurvivable() {
        var state = 13_579
        repeat(3_000) {
            val bytes = ByteArray(512)
            bytes[0] = 'I'.code.toByte()
            bytes[1] = 'D'.code.toByte()
            bytes[2] = '3'.code.toByte()
            bytes[3] = 4
            for (i in 4 until bytes.size) {
                state = state * 1_103_515_245 + 12_345
                bytes[i] = ((state ushr 16) and 0xFF).toByte()
            }
            expectOnlyKiteFailures("random bytes behind an ID3 header") {
                AudioKodec.readTags(bytes)
            }
        }
    }

    @Test
    fun aTrailerThatPointsBackwardsPastTheStartIsRejected() {
        // A Lyrics3 trailer whose stated size is larger than the file.
        val bytes = ByteArray(200)
        val trailer = "999999LYRICS200"
        for (i in trailer.indices) {
            bytes[bytes.size - trailer.length + i] = trailer[i].code.toByte()
        }
        expectOnlyKiteFailures("a Lyrics3 trailer pointing before the file") {
            AudioKodec.readTags(bytes)
        }
    }

    @Test
    fun anApeFooterClaimingAnAbsurdSizeIsRejected() {
        val bytes = ByteArray(300)
        val magic = "APETAGEX"
        val at = bytes.size - 32
        for (i in magic.indices) bytes[at + i] = magic[i].code.toByte()
        // Version 2000, and a body size of nearly two gigabytes.
        bytes[at + 8] = 0xD0.toByte()
        bytes[at + 9] = 0x07
        for (i in 12..15) bytes[at + i] = 0x7F
        expectOnlyKiteFailures("an APE footer claiming two gigabytes") {
            AudioKodec.readTags(bytes)
        }
    }

    @Test
    fun writingTagsIntoAMangledFileStillProducesAReadableFile() {
        var state = 97_531
        repeat(200) {
            state = state * 1_103_515_245 + 12_345
            val at = ((state ushr 8) and 0x7FFF_FFFF) % minOf(good.size, 1_000)
            val mutated = good.copyOf()
            mutated[at] = ((state ushr 16) and 0xFF).toByte()
            expectOnlyKiteFailures("rewriting a file mangled at byte $at") {
                val rewritten = AudioKodec.writeTags(
                    mutated,
                    TagBundle(mapOf(TagField.TITLE to listOf("Recovered"))),
                )
                val read = AudioKodec.readTags(rewritten)
                assertTrue(
                    read[TagField.TITLE] == "Recovered",
                    "the rewritten file should hold the new title",
                )
            }
        }
    }

    private inline fun expectOnlyKiteFailures(what: String, block: () -> Unit) {
        try {
            block()
        } catch (_: AudioKodecException) {
            // Expected: every failure has to be one of ours.
        } catch (e: Throwable) {
            throw AssertionError("$what threw ${e::class.simpleName}: ${e.message}", e)
        }
    }
}

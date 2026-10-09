package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.PictureType
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks reading and writing the iTunes item list of an MP4 file on every
 * target. Files are assembled box by box in [Mp4TagFixtures]; the oracle
 * suite checks the same paths against ffmpeg and ffprobe on the JVM.
 *
 * Every write is checked for the guarantee every tag writer keeps: the audio
 * comes back sample for sample, and the media data is copied byte for byte,
 * whether the `moov` box sits before it, so that a size change moves it, or
 * after it.
 */
class Mp4TagTest {

    private val mp4 = Mp4Test()

    @Test
    fun everyKindOfStoredValueIsRead() {
        val tags = AudioKodec.readTags(Mp4TagFixtures.tagged(mp4))
        assertEquals("Tïtle 日本", tags[TagField.TITLE])
        assertEquals(listOf("One", "Two"), tags.all(TagField.ARTIST))
        assertEquals("2021-05-04", tags[TagField.DATE])
        assertEquals("3", tags[TagField.TRACK_NUMBER])
        assertEquals("12", tags[TagField.TRACK_TOTAL])
        assertEquals("1", tags[TagField.DISC_NUMBER])
        assertEquals("2", tags[TagField.DISC_TOTAL])
        assertEquals("Rock", tags[TagField.GENRE], "gnre stores the ID3v1 genre code plus one")
        assertEquals("120", tags[TagField.BPM])
        assertEquals("1", tags[TagField.COMPILATION])
        assertEquals("Long ünïcode", tags[TagField.DESCRIPTION], "UTF-16 text")
        assertEquals("rec-id", tags[TagField.MUSICBRAINZ_RECORDING_ID], "freeform items map by name")
        assertEquals(-6.5, tags.replayGain?.trackGainDb)
        assertEquals(listOf("image/png", "image/jpeg"), tags.pictures.map { it.mimeType })
        assertContentEquals(Mp4TagFixtures.png, tags.pictures[0].bytes)
        assertTrue(tags.pictures.all { it.type == PictureType.FRONT_COVER })

        val block = tags.raw.blocksOf(TagFormat.MP4_ILST).single()
        val entries = block.entries.associateBy { it.key }
        assertEquals(Mp4TagFixtures.SMPB, entries["----:com.apple.iTunes:iTunSMPB"]?.text)
        assertEquals("1", entries["stik"]?.text, "an unmodelled integer reads as text")
        assertContentEquals(Mp4TagFixtures.opaqueItemBody, entries["rtng"]?.binary, "an unreadable item is kept whole")
    }

    @Test
    fun aRewriteKeepsEveryItemAndTheAudio() {
        for (moovFirst in listOf(true, false)) {
            val original = Mp4TagFixtures.tagged(mp4, moovFirst)
            val tags = AudioKodec.readTags(original)
            val edited = AudioKodec.writeTags(original, tags.edit { set(TagField.TITLE, "A much longer title than before, so the box grows") })
            assertSameAudio(original, edited, "moov first $moovFirst")
            val read = AudioKodec.readTags(edited)
            assertEquals("A much longer title than before, so the box grows", read[TagField.TITLE])
            for (field in tags.fields.keys - TagField.TITLE) {
                assertEquals(tags.all(field), read.all(field), "$field after a rewrite")
            }
            assertEquals(tags.replayGain?.trackGainDb, read.replayGain?.trackGainDb)
            assertEquals(tags.pictures.map { it.bytes.toList() }, read.pictures.map { it.bytes.toList() })
            val before = tags.raw.blocksOf(TagFormat.MP4_ILST).single().entries.filter { it.key in UNMODELLED }
            val after = read.raw.blocksOf(TagFormat.MP4_ILST).single().entries.filter { it.key in UNMODELLED }
            assertEquals(before.map { it.key to it.text }, after.map { it.key to it.text })
            assertEquals(before.map { it.binary?.toList() }, after.map { it.binary?.toList() })
        }
    }

    @Test
    fun aSmallerEditReusesThePaddingAndMovesNothing() {
        val original = Mp4TagFixtures.tagged(mp4, moovFirst = true)
        val tags = AudioKodec.readTags(original)
        val grown = AudioKodec.writeTags(original, tags.edit { set(TagField.COMMENT, "x".repeat(300)) })
        assertSameAudio(original, grown, "grown")
        val shrunk = AudioKodec.writeTags(grown, AudioKodec.readTags(grown).edit { set(TagField.COMMENT, "short") })
        assertEquals(grown.size, shrunk.size, "the freed bytes become padding")
        assertEquals(Mp4TagFixtures.find(grown, "mdat"), Mp4TagFixtures.find(shrunk, "mdat"), "the audio stays where it was")
        assertSameAudio(original, shrunk, "shrunk")
        val again = AudioKodec.writeTags(shrunk, AudioKodec.readTags(shrunk).edit { set(TagField.COMMENT, "x".repeat(300)) })
        assertEquals(shrunk.size, again.size, "growing back into the padding moves nothing")

        // Free space right after the box counts as space it may use.
        // The builder places the free box last inside moov; shortening moov
        // leaves it as the next top-level box without moving any audio.
        val withFree = mp4.build(frames = 600, channels = 2, chunkFrames = 128, moovFirst = true, moovExtra = Mp4TagFixtures.box("free", ByteArray(2_000)))
        val moov = Mp4TagFixtures.find(withFree, "moov") - 4
        val shortened = readU32(withFree, moov) - 2_008
        for (k in 0..3) withFree[moov + k] = (shortened shr (24 - 8 * k)).toByte()
        assertEquals(moov + shortened, Mp4TagFixtures.find(withFree, "free", moov) - 4)
        val written = AudioKodec.writeTags(withFree, TagBundle(mapOf(TagField.TITLE to listOf("Fits"))))
        assertEquals(withFree.size, written.size)
        assertEquals("Fits", AudioKodec.readTags(written)[TagField.TITLE])
        assertSameAudio(withFree, written, "absorbed free box")
    }

    @Test
    fun everyFieldRoundTrips() {
        val values = TagField.entries.associateWith { field ->
            when (field) {
                TagField.TRACK_NUMBER, TagField.DISC_NUMBER -> listOf("2")
                TagField.TRACK_TOTAL, TagField.DISC_TOTAL -> listOf("9")
                TagField.BPM -> listOf("128")
                TagField.COMPILATION -> listOf("1")
                else -> listOf("${field.name.lowercase()} ✓", "second ${field.name.lowercase()}")
            }
        }
        val written = AudioKodec.writeTags(mp4.build(600, 2, 128), TagBundle(values))
        val read = AudioKodec.readTags(written)
        for (field in TagField.entries) assertEquals(values[field], read.all(field), "$field")

        // Values the integer atoms cannot hold exactly go to freeform items.
        val odd = mapOf(
            TagField.TRACK_NUMBER to listOf("A1"),
            TagField.TRACK_TOTAL to listOf("9"),
            TagField.DISC_NUMBER to listOf("0"),
            TagField.BPM to listOf("120.5"),
            TagField.COMPILATION to listOf("yes please"),
        )
        val oddRead = AudioKodec.readTags(AudioKodec.writeTags(mp4.build(600, 2, 128), TagBundle(odd)))
        for ((field, value) in odd) assertEquals(value, oddRead.all(field), "$field")
    }

    @Test
    fun loudnessIsStatedAgainstTheReplayGainReference() {
        val gain = ReplayGain(trackGainDb = -8.0, trackPeak = 0.9, albumGainDb = -7.25, albumPeak = 0.95, isR128 = true)
        val read = AudioKodec.readTags(AudioKodec.writeTags(mp4.build(600, 2, 128), TagBundle(emptyMap(), replayGain = gain)))
        val stored = assertNotNull(read.replayGain)
        assertEquals(-3.0, stored.trackGainDb)
        assertEquals(-2.25, stored.albumGainDb)
        assertEquals(0.9, stored.trackPeak)
        assertEquals(false, stored.isR128)
    }

    @Test
    fun artworkTheCoverAtomCannotDescribeIsRefusedBeforeOutput() {
        val file = mp4.build(600, 2, 128)
        for (picture in listOf(
            TagPicture(PictureType.BACK_COVER, "image/png", bytes = Mp4TagFixtures.png),
            TagPicture(PictureType.FRONT_COVER, "image/png", "described", Mp4TagFixtures.png),
            TagPicture(PictureType.FRONT_COVER, "image/webp", bytes = Mp4TagFixtures.png),
        )) {
            val sink = ByteArrayAudioSink()
            assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags(file.asAudioSource(), TagBundle(emptyMap(), listOf(picture)), sink)
            }
            assertEquals(0L, sink.position, "nothing is written before a refusal")
        }
        val cover = TagPicture(PictureType.FRONT_COVER, "image/jpeg", bytes = Mp4TagFixtures.jpeg)
        val read = AudioKodec.readTags(AudioKodec.writeTags(file, TagBundle(emptyMap(), listOf(cover))))
        assertContentEquals(Mp4TagFixtures.jpeg, read.cover?.bytes)
        assertEquals("image/jpeg", read.cover?.mimeType)
    }

    @Test
    fun chaptersAreWrittenAsANeroList() {
        val file = mp4.build(frames = 3_001, channels = 1, chunkFrames = 1_000)
        val chapters = listOf(
            TagChapter("a", 0, 120, "Opening"),
            TagChapter("b", 120, -1, "Middle ✓"),
            TagChapter("c", 250, 375, "End"),
        )
        val written = AudioKodec.writeTags(file, TagBundle(emptyMap(), chapters = chapters))
        assertSameAudio(file, written, "chapters")
        val read = AudioKodec.readTags(written).chapters
        assertEquals(listOf(0L, 120L, 250L), read.map { it.startMillis })
        assertEquals(listOf(120L, 250L, -1L), read.map { it.endMillis }, "each chapter ends where the next starts")
        assertEquals(listOf("Opening", "Middle ✓", "End"), read.map { it.title })

        // Unchanged chapters are kept as stored, and clearing removes them.
        val kept = AudioKodec.writeTags(written, AudioKodec.readTags(written).edit { set(TagField.TITLE, "t") })
        assertEquals(read.map { it.startMillis to it.title }, AudioKodec.readTags(kept).chapters.map { it.startMillis to it.title })
        val cleared = AudioKodec.writeTags(written, AudioKodec.readTags(written).edit { clear() })
        assertTrue(AudioKodec.readTags(cleared).isEmpty)
        assertEquals(-1, Mp4TagFixtures.find(cleared, "chpl"))
        assertSameAudio(file, cleared, "cleared")

        val gap = listOf(TagChapter("a", 0, 100, "A"), TagChapter("b", 120, -1, "B"))
        assertFailsWith<UnsupportedFeatureException>("an end the next start contradicts") {
            AudioKodec.writeTags(file, TagBundle(emptyMap(), chapters = gap))
        }
    }

    @Test
    fun formatSelectionCanRemoveTheItemListAndRefusesOtherFormats() {
        val original = Mp4TagFixtures.tagged(mp4)
        val tags = AudioKodec.readTags(original)
        val removed = AudioKodec.writeTags(original, tags, TagWriteOptions(formats = emptySet()))
        assertTrue(AudioKodec.readTags(removed).raw.blocksOf(TagFormat.MP4_ILST).isEmpty())
        assertSameAudio(original, removed, "removed")
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.writeTags(original, tags, TagWriteOptions(formats = setOf(TagFormat.ID3V2_4)))
        }
        val cleared = AudioKodec.writeTags(original, tags.edit { clear() })
        assertTrue(AudioKodec.readTags(cleared).raw.blocksOf(TagFormat.MP4_ILST).isEmpty())
        assertEquals(-1, Mp4TagFixtures.find(cleared, "ilst"))
    }

    @Test
    fun aFragmentedFileGrowsWhenItsFragmentsCountFromThemselves() {
        val original = mp4.buildFragmented(frames = 600, channels = 2, fragmentFrames = 200)
        val written = AudioKodec.writeTags(original, TagBundle(mapOf(TagField.TITLE to listOf("Fragmented"))))
        assertEquals("Fragmented", AudioKodec.readTags(written)[TagField.TITLE])
        assertSameAudio(original, written, "fragmented")
    }

    @Test
    fun aFileWithoutUdtaGainsOneAndDropsItAgain() {
        val bare = mp4.build(frames = 600, channels = 2, chunkFrames = 128, moovFirst = true)
        val written = AudioKodec.writeTags(bare, TagBundle(mapOf(TagField.ALBUM to listOf("Album"))))
        assertEquals("Album", AudioKodec.readTags(written)[TagField.ALBUM])
        val emptied = AudioKodec.writeTags(written, TagBundle.EMPTY, TagWriteOptions(padding = 0))
        assertEquals(-1, Mp4TagFixtures.find(emptied, "udta"))
        assertSameAudio(bare, emptied, "emptied")
    }

    @Test
    fun damagedMetadataFailsOnlyWithTheLibrarysOwnExceptions() {
        val original = Mp4TagFixtures.tagged(mp4)
        val ilst = Mp4TagFixtures.find(original, "ilst") - 4
        val ilstEnd = ilst + readU32(original, ilst)
        for (length in 0..original.size step 3) {
            expectOnlyOwnFailures("truncated to $length") {
                val cut = original.copyOf(length)
                AudioKodec.writeTags(cut, AudioKodec.readTags(cut).edit { set(TagField.TITLE, "Recovered") })
            }
        }
        for (at in ilst until ilstEnd) {
            for (byte in byteArrayOf(0, 1, 0x7F, 0x80.toByte(), 0xFF.toByte())) {
                expectOnlyOwnFailures("item list byte $at set to $byte") {
                    val mutated = original.copyOf().also { it[at] = byte }
                    AudioKodec.writeTags(mutated, AudioKodec.readTags(mutated).edit { set(TagField.TITLE, "Recovered") })
                }
            }
        }
    }

    // Checking.

    private fun assertSameAudio(expected: ByteArray, actual: ByteArray, what: String) {
        assertContentEquals(ints(expected), ints(actual), "$what: decoded samples")
        val expectedMedia = media(expected)
        val actualMedia = media(actual)
        assertContentEquals(expectedMedia, actualMedia, "$what: media data bytes")
    }

    private fun ints(file: ByteArray): IntArray {
        val out = ArrayList<Int>()
        AudioKodec.open(file).use { decoder ->
            val access = decoder as IntPcmAccess
            while (true) {
                val frame = access.nextIntFrame() ?: break
                for (i in 0 until frame.sampleCount) out += frame.samples[i]
            }
        }
        return out.toIntArray()
    }

    /** Every `mdat` body in the file, joined. */
    private fun media(file: ByteArray): ByteArray {
        var out = ByteArray(0)
        var at = 0
        while (at + 8 <= file.size) {
            val size = readU32(file, at)
            if (size < 8 || at + size > file.size) break
            if (Mp4TagFixtures.find(file.copyOfRange(at + 4, at + 8), "mdat") == 0) out += file.copyOfRange(at + 8, at + size)
            at += size
        }
        return out
    }

    private fun readU32(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

    private inline fun expectOnlyOwnFailures(what: String, block: () -> Unit) {
        try {
            block()
        } catch (_: AudioKodecException) {
            // Every failure has to be one of the library's own.
        } catch (e: Throwable) {
            throw AssertionError("$what threw ${e::class.simpleName}: ${e.message}", e)
        }
    }

    private companion object {
        val UNMODELLED = setOf("----:com.apple.iTunes:iTunSMPB", "xid ", "stik", "rtng")
    }
}

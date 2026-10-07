package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LoudnessTagPreservationTest {
    private val audio = ByteArray(32) { 0x55 }

    @Test
    fun unknownId3LoudnessNamesSurviveEditsInEveryLegalTextEncoding() {
        for (input in 2..4) {
            for (encoding in if (input == 4) 0..3 else 0..1) {
                for (output in 3..4) {
                    var bytes = LoudnessTagFixtures.id3(input, encoding) + audio
                    assertEquals(LoudnessTagFixtures.unmodelled, unknownId3(AudioKodec.readTags(bytes)))
                    repeat(2) { edit ->
                        val before = AudioKodec.readTags(bytes)
                        assertNotNull(before.replayGain)
                        bytes = AudioKodec.writeTags(bytes, before.edit { set(TagField.TITLE, "Edit $edit") }, TagWriteOptions(id3Version = output, padding = 0))
                        val after = AudioKodec.readTags(bytes)
                        assertEquals(LoudnessTagFixtures.unmodelled, unknownId3(after))
                        assertGainValues(after)
                        assertContentEquals(audio, bytes.takeLast(audio.size).toByteArray())
                    }
                }
            }
        }
    }

    @Test
    fun anId3BinaryOwnerMatchingAGainNameRemainsBinaryMetadata() {
        for (input in 2..4) {
            for (output in 3..4) {
                val original = LoudnessTagFixtures.id3(input, 1, owner = true) + audio
                val written = AudioKodec.writeTags(original, AudioKodec.readTags(original).edit {
                    set(TagField.TITLE, "Changed")
                    setReplayGain(null)
                }, TagWriteOptions(id3Version = output, padding = 0))
                val owner = AudioKodec.readTags(written).raw.blocks.flatMap { it.entries }
                    .single { it.key == "UFID" && it.description == LoudnessTagFixtures.binaryOwner }
                assertContentEquals(LoudnessTagFixtures.binary, owner.binary)
                assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
            }
        }
    }

    @Test
    fun removingId3ModelledGainRetainsUnmodelledNamesAndEmptyValues() {
        for (version in 3..4) {
            val original = LoudnessTagFixtures.id3(version, 1) + audio
            val written = AudioKodec.writeTags(original, AudioKodec.readTags(original).edit {
                set(TagField.TITLE, "Changed")
                setReplayGain(null)
            }, TagWriteOptions(id3Version = version, padding = 0))
            val after = AudioKodec.readTags(written)
            assertEquals(LoudnessTagFixtures.unmodelled, unknownId3(after))
            assertNoModelledNames(after, id3 = true)
            assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
        }
    }

    @Test
    fun unknownId3LoudnessListsRetainEmptyPiecesDuplicatesAndUnicode() {
        val original = LoudnessTagFixtures.id3(4, 3, lists = true) + audio
        val written = AudioKodec.writeTags(original, AudioKodec.readTags(original).edit { set(TagField.TITLE, "Changed") })
        assertEquals(LoudnessTagFixtures.repeated, AudioKodec.readTags(written).raw.blocks.flatMap { it.entries }
            .filter { it.description == "REPLAYGAIN_PRIVATE_LIST" }.map { it.text })
        assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
    }

    @Test
    fun unknownApeLoudnessNamesSurviveEditsAndV1Upgrade() {
        for (version in 1..2) {
            var bytes = audio + LoudnessTagFixtures.ape(version)
            assertEquals(LoudnessTagFixtures.unmodelled, unknownApe(AudioKodec.readTags(bytes)))
            repeat(2) { edit ->
                val before = AudioKodec.readTags(bytes)
                assertNotNull(before.replayGain)
                bytes = AudioKodec.writeTags(bytes, before.edit { set(TagField.TITLE, "Edit $edit") }, apeOptions)
                val after = AudioKodec.readTags(bytes)
                assertEquals(LoudnessTagFixtures.unmodelled, unknownApe(after))
                assertGainValues(after)
                assertContentEquals(audio, bytes.take(audio.size).toByteArray())
                assertTrue(after.raw.blocks.all { it.format == TagFormat.APEV2 })
            }
        }
    }

    @Test
    fun removingApeModelledGainRetainsUnmodelledNamesAndBinaryItems() {
        val original = audio + LoudnessTagFixtures.ape(2)
        val written = AudioKodec.writeTags(original, AudioKodec.readTags(original).edit {
            set(TagField.TITLE, "Changed")
            setReplayGain(null)
        }, apeOptions)
        val after = AudioKodec.readTags(written)
        assertEquals(LoudnessTagFixtures.unmodelled, unknownApe(after))
        assertContentEquals(LoudnessTagFixtures.binary, after.raw.blocks.flatMap { it.entries }.single { it.key == "R128_PRIVATE_BYTES" }.binary)
        assertNoModelledNames(after, id3 = false)
        assertContentEquals(audio, written.take(audio.size).toByteArray())
    }

    @Test
    fun apeEmptyPiecesAreGuardedAndInvalidUtf8StillRejectsTheBlock() {
        val manyEmpty = audio + ApeBytes(listOf("REPLAYGAIN_PRIVATE" to ByteArray(32)))
        assertFailsWith<AllocationGuardException> {
            AudioKodec.readTags(manyEmpty, DecodeOptions(maxAllocationBytes = 1_000))
        }
        val invalid = audio + ApeBytes(listOf("R128_PRIVATE" to byteArrayOf(0, 0xC3.toByte())))
        assertTrue(AudioKodec.readTags(invalid).raw.blocks.isEmpty())
    }

    @Test
    fun unknownApeLoudnessListsRetainEmptyPiecesDuplicatesAndUnicode() {
        val original = audio + LoudnessTagFixtures.ape(2, lists = true)
        assertEquals(LoudnessTagFixtures.repeated, AudioKodec.readTags(original).raw.blocks.flatMap { it.entries }
            .filter { it.key == "REPLAYGAIN_PRIVATE_LIST" }.map { it.text })
        val written = AudioKodec.writeTags(original, AudioKodec.readTags(original).edit { set(TagField.TITLE, "Changed") }, apeOptions)
        assertEquals(LoudnessTagFixtures.repeated, AudioKodec.readTags(written).raw.blocks.flatMap { it.entries }
            .filter { it.key == "REPLAYGAIN_PRIVATE_LIST" }.map { it.text })
        assertContentEquals(audio, written.take(audio.size).toByteArray())
    }

    private fun unknownId3(tags: TagBundle): List<Pair<String, String?>> = tags.raw.blocks.flatMap { it.entries }
        .filter { entry -> LoudnessTagFixtures.unmodelled.any { it.first == entry.description } }
        .map { it.description!! to it.text }

    private fun unknownApe(tags: TagBundle): List<Pair<String, String?>> = tags.raw.blocks.flatMap { it.entries }
        .filter { entry -> LoudnessTagFixtures.unmodelled.any { it.first == entry.key } }
        .map { it.key to it.text }

    private fun assertNoModelledNames(tags: TagBundle, id3: Boolean) {
        val names = LoudnessTagFixtures.modelled.map { it.first.uppercase() }
        assertTrue(tags.raw.blocks.flatMap { it.entries }.none {
            (if (id3) it.description else it.key)?.uppercase() in names
        })
    }

    private fun assertGainValues(tags: TagBundle) {
        val gain = assertNotNull(tags.replayGain)
        assertEquals(-3.0, gain.trackGainDb)
        assertEquals(0.9, gain.trackPeak)
        assertEquals(-4.0, gain.albumGainDb)
        assertEquals(0.8, gain.albumPeak)
        assertEquals(false, gain.isR128)
    }

    private val apeOptions = TagWriteOptions(formats = setOf(TagFormat.APEV2), padding = 0)
}

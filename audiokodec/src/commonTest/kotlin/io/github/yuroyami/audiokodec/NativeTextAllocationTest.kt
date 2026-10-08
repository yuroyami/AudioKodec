package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeTextAllocationTest {
    @Test
    fun eachRetainedNativeTextEntryCountsTowardsTheLimit() {
        for (source in listOf(NativeTextListFixtures.wave(), NativeTextListFixtures.rf64(),
            NativeTextListFixtures.aiff(), NativeTextListFixtures.aiff(EncodeTarget.aiffFloat()))) {
            val big = source[0] == 'F'.code.toByte()
            val format = if (big) TagFormat.AIFF_TEXT else TagFormat.RIFF_INFO
            for (tolerant in listOf(false, true)) {
                val options = DecodeOptions(tolerant = tolerant, maxAllocationBytes = 1_024)
                val within = append(source, listOf(16))
                assertEquals(16, AudioKodec.readTags(within, options).raw.blocksOf(format).sumOf { it.entries.size })
                val over = append(source, listOf(17))
                assertEquals(17, AudioKodec.readTags(over).raw.blocksOf(format).sumOf { it.entries.size })
                assertFailsWith<AllocationGuardException> { AudioKodec.readTags(over, options) }
            }
        }
    }

    @Test
    fun separateRiffInfoListsShareTheEntryLimit() {
        for (source in listOf(NativeTextListFixtures.wave(), NativeTextListFixtures.rf64())) {
            val options = DecodeOptions(maxAllocationBytes = 1_024)
            val within = append(source, listOf(8, 8))
            assertEquals(16, AudioKodec.readTags(within, options).raw.blocksOf(TagFormat.RIFF_INFO).sumOf { it.entries.size })
            val over = append(source, listOf(8, 8, 1))
            assertEquals(17, AudioKodec.readTags(over).raw.blocksOf(TagFormat.RIFF_INFO).sumOf { it.entries.size })
            assertFailsWith<AllocationGuardException> { AudioKodec.readTags(over, options) }
        }
    }

    @Test
    fun emptyNativeValuesDoNotConsumeRetainedEntrySpace() {
        for (source in listOf(NativeTextListFixtures.wave(), NativeTextListFixtures.aiff())) {
            val bytes = append(source, listOf(40), empty = true)
            assertEquals(0, AudioKodec.readTags(bytes, DecodeOptions(maxAllocationBytes = 1_024)).raw.blocks.size)
        }
    }

    private fun append(source: ByteArray, counts: List<Int>, empty: Boolean = false): ByteArray {
        val big = source[0] == 'F'.code.toByte()
        val parts = counts.flatMap { count ->
            val entries = List(count) {
                NativeTextListFixtures.chunk(if (big) "ANNO" else "ICMT",
                    if (empty) byteArrayOf(0) else byteArrayOf('v'.code.toByte(), 0), big)
            }
            if (big) entries else listOf(NativeTextListFixtures.chunk("LIST",
                "INFO".encodeToByteArray() + entries.fold(ByteArray(0)) { bytes, entry -> bytes + entry }, false))
        }
        return NativeTextListFixtures.append(source, parts)
    }
}

package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.flac.FlacFrameReader
import io.github.yuroyami.audiokodec.internal.flac.FlacMetadata
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class FlacSeekTableTest {
    private val samples = TestSignals.quantise(TestSignals.sine(9_000, 1), 16)
    private val encoded = AudioKodec.encode(
        samples, AudioFormat(44_100, 1), EncodeTarget.flac(16),
        TagBundle(mapOf(TagField.TITLE to listOf("Still readable"))),
    )

    @Test
    fun invalidSeekPointContentsDoNotCostTheAudioOrTags() {
        for (bytes in FlacSeekTableFixtures.invalidTables(encoded)) {
            for (tolerant in listOf(false, true)) {
                val options = DecodeOptions(tolerant = tolerant)
                assertEquals(9_000L, AudioKodec.probe(bytes, options).durationFrames)
                TestSignals.assertExact(samples, AudioKodec.decode(bytes, options).samples)
                assertEquals(true, AudioKodec.verifyFlacMd5(bytes, options))
                assertEquals("Still readable", AudioKodec.readTags(bytes, options)[TagField.TITLE])
                AudioKodec.open(bytes, options).use { decoder ->
                    assertEquals(8_000L, decoder.seek(8_000))
                    assertEquals(8_000L, assertNotNull(decoder.nextFrame()).positionFrames)
                }
                AudioKodec.open(bytes, options.copy(allowSlowSeek = false)).use { decoder ->
                    assertFailsWith<UnsupportedFeatureException> { decoder.seek(8_000) }
                }
            }
        }
    }

    @Test
    fun aValidSeekTableIncludingAPlaceholderRemainsUsable() {
        val bytes = FlacSeekTableFixtures.validTable(encoded)
        AudioKodec.open(bytes, DecodeOptions(allowSlowSeek = false)).use { decoder ->
            assertEquals(8_000L, decoder.seek(8_000))
            val block = assertNotNull(decoder.nextFrame())
            assertEquals(8_000L, block.positionFrames)
            TestSignals.assertExact(samples.copyOfRange(8_000, 8_000 + block.frameCount), block.samples.copyOf(block.sampleCount))
        }
    }

    @Test
    fun tagsCanBeEditedWithoutChangingAudioBesideAnInvalidTable() {
        for (bytes in FlacSeekTableFixtures.invalidTables(encoded)) {
            val edited = AudioKodec.writeTags(bytes, AudioKodec.readTags(bytes).edit { set(TagField.TITLE, "Edited") })
            assertEquals("Edited", AudioKodec.readTags(edited)[TagField.TITLE])
            assertEquals(true, AudioKodec.verifyFlacMd5(edited))
            assertContentEquals(AudioKodec.decode(encoded).samples, AudioKodec.decode(edited).samples)
        }
    }
}

internal object FlacSeekTableFixtures {
    private data class Point(val sample: Long, val offset: Long, val frames: Int)

    fun validTable(bytes: ByteArray): ByteArray = insert(bytes, points(bytes) + Point(-1, 0, 0))

    fun invalidTables(bytes: ByteArray): List<ByteArray> {
        val valid = points(bytes)
        return listOf(
            insert(bytes, listOf(valid[0], valid[0])),
            insert(bytes, valid.reversed()),
            insert(bytes, listOf(valid[0], Point(-2, 0, 4096))),
            insert(bytes, listOf(valid[0], Point(valid[1].sample, -1, 4096))),
            insert(bytes, listOf(valid[0], Point(valid[1].sample, valid[1].offset, 0))),
        )
    }

    private fun points(bytes: ByteArray): List<Point> {
        val reader = SourceReader(bytes.asAudioSource(), DecodeOptions.DEFAULT)
        val metadata = FlacMetadata.parse(reader, DecodeOptions.DEFAULT)
        val first = assertNotNull(FlacFrameReader(reader, metadata.streamInfo, DecodeOptions.DEFAULT).decodeAt(metadata.audioOffset))
        return listOf(Point(0, 0, first.blockSize), Point(first.blockSize.toLong(), first.byteLength.toLong(), first.blockSize))
    }

    private fun insert(bytes: ByteArray, points: List<Point>): ByteArray {
        val table = ByteArray(4 + 18 * points.size)
        table[0] = 3
        val length = table.size - 4
        repeat(3) { table[1 + it] = (length ushr (16 - it * 8)).toByte() }
        for ((index, point) in points.withIndex()) {
            val at = 4 + index * 18
            repeat(8) {
                table[at + it] = (point.sample ushr (56 - it * 8)).toByte()
                table[at + 8 + it] = (point.offset ushr (56 - it * 8)).toByte()
            }
            table[at + 16] = (point.frames ushr 8).toByte()
            table[at + 17] = point.frames.toByte()
        }
        // Insert after STREAMINFO, retaining the original last-block flag.
        return bytes.copyOfRange(0, 42).also { it[4] = (it[4].toInt() and 0x7F).toByte() } +
            table + bytes.copyOfRange(42, bytes.size)
    }
}

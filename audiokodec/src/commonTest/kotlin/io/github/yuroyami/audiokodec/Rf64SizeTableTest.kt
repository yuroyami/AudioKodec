package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.io.SinkWriter
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Rf64SizeTableTest {
    @Test
    fun largeReversedTablesPreserveChunkBytesTagsAndAudio() {
        val chunks = List(32_768) { index ->
            Chunk("Q" + (0..2).map { (0x20 + ((index ushr (5 * it)) and 31)).toChar() }.joinToString(""),
                ByteArray(index % 4) { (index + it).toByte() })
        }
        for (id in listOf("RF64", "BW64")) {
            val original = file(chunks, chunks.reversed(), id)
            assertAudioAndTitle(original, "Original")
            val rewritten = AudioKodec.writeTags(original, title("Edited"))
            assertEquals(id, rewritten.decodeToString(0, 4))
            assertAudioAndTitle(rewritten, "Edited")
            // No table entry describes the regular INFO chunk, so the whole
            // original table and every opaque placeholder chunk must survive.
            val tableEnd = 48 + chunks.size * 12
            assertContentEquals(original.copyOfRange(44, tableEnd), rewritten.copyOfRange(44, tableEnd))
            val opaqueEnd = tableEnd + chunks.sumOf { 8 + it.payload.size + it.payload.size % 2 }
            assertContentEquals(original.copyOfRange(tableEnd, opaqueEnd), rewritten.copyOfRange(tableEnd, opaqueEnd))
        }
    }

    @Test
    fun duplicateIdsConsumeSizesInOrderAndOnlyDroppedMetadataLosesItsEntry() {
        val first = Chunk("JUNK", byteArrayOf(1))
        val second = Chunk("JUNK", byteArrayOf(2, 3, 4, 5, 6))
        val third = Chunk("JUNK", ByteArray(0))
        val other = Chunk("ABCD", byteArrayOf(7, 8, 9, 10))
        val info = Chunk("LIST", "INFO".encodeToByteArray() + "INAM".encodeToByteArray() + byteArrayOf(4, 0, 0, 0) + "Old\u0000".encodeToByteArray())
        val opaqueList = Chunk("LIST", "adtl".encodeToByteArray() + ByteArray(8))
        val chunks = listOf(first, info, second, other, opaqueList, third)
        val table = listOf(info, opaqueList, other, first, second, third)
        val original = file(chunks, table)
        assertAudioAndTitle(original, "Old")
        val rewritten = AudioKodec.writeTags(original, title("Edited"))
        assertAudioAndTitle(rewritten, "Edited")
        val expected = table.filter { it !== info }
        assertEquals(expected.size.toLong(), u32(rewritten, 44))
        val entries = ByteArrayAudioSink()
        val writer = SinkWriter(entries)
        for (chunk in expected) {
            writer.writeFourCc(chunk.id)
            writer.writeI64Le(chunk.payload.size.toLong())
        }
        assertContentEquals(entries.toByteArray(), rewritten.copyOfRange(48, 48 + expected.size * 12))
    }

    @Test
    fun allTableConsumersAccountForIndexStorageBeforeAllocating() {
        val bytes = file(List(3) { Chunk("JUNK", ByteArray(0)) })
        val options = DecodeOptions(maxAllocationBytes = 128)
        assertFailsWith<AllocationGuardException> { AudioKodec.probe(bytes, options) }
        assertFailsWith<AllocationGuardException> { AudioKodec.readTags(bytes, options) }
        val sink = ByteArrayAudioSink()
        assertFailsWith<AllocationGuardException> {
            AudioKodec.writeTags(bytes.asAudioSource(), title("Edited"), sink, decodeOptions = options)
        }
        assertEquals(0L, sink.position)
    }

    @Test
    fun missingTableEntriesFailBeforeRewritingOutput() {
        val chunks = listOf(Chunk("JUNK", byteArrayOf(1, 2)), Chunk("ABCD", byteArrayOf(3, 4)))
        val bytes = file(chunks, chunks.take(1))
        assertFailsWith<CorruptStreamException> { AudioKodec.probe(bytes) }
        val sink = ByteArrayAudioSink()
        assertFailsWith<CorruptStreamException> { AudioKodec.writeTags(bytes.asAudioSource(), title("Edited"), sink) }
        assertEquals(0L, sink.position)
    }

    private fun assertAudioAndTitle(bytes: ByteArray, title: String) {
        assertEquals(64L, AudioKodec.probe(bytes).durationFrames)
        assertEquals(title, AudioKodec.readTags(bytes)[TagField.TITLE])
        TestSignals.assertExact(samples, AudioKodec.decode(bytes).samples)
    }

    private fun title(value: String) = TagBundle(mapOf(TagField.TITLE to listOf(value)))

    private class Chunk(val id: String, val payload: ByteArray)

    private val samples = FloatArray(64) { (it - 32) / 32768f }

    private fun file(chunks: List<Chunk>, table: List<Chunk> = chunks, id: String = "RF64"): ByteArray {
        val wave = AudioKodec.writeTags(AudioKodec.encode(samples, AudioFormat(44_100, 1), EncodeTarget.wav(16)), title("Original"))
        val info = AudioKodec.probe(wave)
        repeat(4) { wave[info.dataOffset.toInt() - 4 + it] = 0xFF.toByte() }
        val sink = ByteArrayAudioSink()
        val writer = SinkWriter(sink)
        writer.writeFourCc(id)
        writer.writeU32Le(0xFFFF_FFFFL)
        writer.writeFourCc("WAVE")
        writer.writeFourCc("ds64")
        writer.writeU32Le(28L + table.size * 12L)
        writer.writeI64Le(0) // Filled once every chunk has been written.
        writer.writeI64Le(info.dataSize)
        writer.writeI64Le(samples.size.toLong())
        writer.writeU32Le(table.size.toLong())
        for (chunk in table) {
            writer.writeFourCc(chunk.id)
            writer.writeI64Le(chunk.payload.size.toLong())
        }
        for (chunk in chunks) {
            writer.writeFourCc(chunk.id)
            writer.writeU32Le(0xFFFF_FFFFL)
            sink.write(chunk.payload)
            if (chunk.payload.size % 2 == 1) writer.writeU8(0)
        }
        sink.write(wave, 12, wave.size - 12)
        val bytes = sink.toByteArray()
        repeat(8) { bytes[20 + it] = ((bytes.size - 8).toLong() ushr (8 * it)).toByte() }
        return bytes
    }

    private fun u32(bytes: ByteArray, at: Int): Long = (0..3).sumOf { (bytes[at + it].toLong() and 255L) shl (8 * it) }
}

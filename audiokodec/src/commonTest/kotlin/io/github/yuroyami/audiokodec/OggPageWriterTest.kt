package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.ogg.OggCrc
import io.github.yuroyami.audiokodec.internal.ogg.OggPage
import io.github.yuroyami.audiokodec.internal.ogg.OggPageWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the shared Ogg page writer against RFC 3533 by reading its pages
 * back by hand: capture pattern, flags, granule positions, serial and
 * sequence numbers, lacing, and a checksum computed independently of the
 * writer.
 */
class OggPageWriterTest {

    private class Page(val flags: Int, val granule: Long, val serial: Int, val sequence: Int, val lacing: IntArray, val body: ByteArray)

    private fun pages(bytes: ByteArray): List<Page> {
        val out = ArrayList<Page>()
        var at = 0
        while (at < bytes.size) {
            assertEquals("OggS", bytes.decodeToString(at, at + 4), "capture pattern at $at")
            assertEquals(0, bytes[at + 4].toInt(), "version")
            val flags = bytes[at + 5].toInt() and 0xFF
            var granule = 0L
            for (i in 7 downTo 0) granule = (granule shl 8) or (bytes[at + 6 + i].toLong() and 0xFF)
            fun int(from: Int): Int = (0 until 4).fold(0) { value, i -> value or ((bytes[from + i].toInt() and 0xFF) shl (8 * i)) }
            val segments = bytes[at + 26].toInt() and 0xFF
            val lacing = IntArray(segments) { bytes[at + 27 + it].toInt() and 0xFF }
            val size = 27 + segments + lacing.sum()
            assertEquals(int(at + 22), OggCrc.ofPage(bytes, at, at + size, at + 22), "checksum of the page at $at")
            out.add(Page(flags, granule, int(at + 14), int(at + 18), lacing, bytes.copyOfRange(at + 27 + segments, at + size)))
            at += size
        }
        return out
    }

    /** Joins the packets the pages carry, as a reader would. */
    private fun packets(pages: List<Page>): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var current = ByteArray(0)
        for (page in pages) {
            var offset = 0
            for (lace in page.lacing) {
                current += page.body.copyOfRange(offset, offset + lace)
                offset += lace
                if (lace < 255) {
                    out.add(current)
                    current = ByteArray(0)
                }
            }
        }
        assertEquals(0, current.size, "a packet was left open")
        return out
    }

    @Test
    fun packetsComeBackWholeAcrossPages() {
        val sink = ByteArrayAudioSink()
        val writer = OggPageWriter(sink, serialNumber = 0x1234_5678)
        val written = listOf(30, 0, 255, 510, 4_000, 9_000, 70_000, 1).mapIndexed { index, size ->
            ByteArray(size) { (it * 7 + index).toByte() }
        }
        written.forEachIndexed { index, packet ->
            writer.writePacket(packet, granule = 1_000L * index, flush = index == 0, endOfStream = index == written.lastIndex)
        }
        val pages = pages(sink.toByteArray())
        assertEquals(pages.size, writer.pagesWritten)
        assertEquals(written.map { it.toList() }, packets(pages).map { it.toList() })
        pages.forEachIndexed { index, page ->
            assertEquals(0x1234_5678, page.serial)
            assertEquals(index, page.sequence)
            assertEquals(index == 0, page.flags and OggPage.FLAG_FIRST != 0, "beginning of stream on page $index")
            assertEquals(index == pages.lastIndex, page.flags and OggPage.FLAG_LAST != 0, "end of stream on page $index")
            assertTrue(page.lacing.size <= 255)
        }
        // The first packet was flushed onto a page of its own.
        assertEquals(1, pages[0].lacing.size)
        assertEquals(0L, pages[0].granule)
        assertEquals(7_000L, pages.last().granule)
    }

    @Test
    fun aPageThatNoPacketFinishesStatesMinusOneUnlessToldOtherwise() {
        for (open in longArrayOf(-1L, 0L)) {
            val sink = ByteArrayAudioSink()
            val writer = OggPageWriter(sink, pageTarget = 1_000, openPageGranule = open)
            writer.writePacket(ByteArray(3_000), granule = 42, endOfStream = true)
            val pages = pages(sink.toByteArray())
            assertTrue(pages.size >= 3)
            for (page in pages.dropLast(1)) {
                assertEquals(open, page.granule)
                assertEquals(255, page.lacing.last())
            }
            assertEquals(42L, pages.last().granule)
            assertTrue(pages.drop(1).all { it.flags and OggPage.FLAG_CONTINUED != 0 }, "continuation flags")
        }
    }

    @Test
    fun aPacketThatFillsWholeSegmentsEndsWithAnEmptyOne() {
        val sink = ByteArrayAudioSink()
        val writer = OggPageWriter(sink)
        writer.writePacket(ByteArray(510) { 1 }, granule = 5, endOfStream = true)
        val page = pages(sink.toByteArray()).single()
        assertContentEquals(intArrayOf(255, 255, 0), page.lacing)
        assertEquals(OggPage.FLAG_FIRST or OggPage.FLAG_LAST, page.flags)
        assertEquals(OggPageWriter.DEFAULT_SERIAL, page.serial)
    }

    @Test
    fun copiedPagesKeepTheirContentUnderTheNextSequenceNumber() {
        val sink = ByteArrayAudioSink()
        val writer = OggPageWriter(sink)
        writer.writePacket(ByteArray(10), granule = 0, flush = true)
        writer.writePage(OggPage.FLAG_LAST, 960, intArrayOf(3), byteArrayOf(1, 2, 3))
        val pages = pages(sink.toByteArray())
        assertEquals(2, pages.size)
        assertEquals(1, pages[1].sequence)
        assertEquals(960L, pages[1].granule)
        assertContentEquals(byteArrayOf(1, 2, 3), pages[1].body)
    }
}

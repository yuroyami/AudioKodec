package io.github.yuroyami.audiokodec.internal.ogg

import io.github.yuroyami.audiokodec.AudioSink

/**
 * Writes one logical Ogg stream as pages, from packets, forwards only.
 *
 * Packets are laced into the page being built, a packet longer than a page
 * continues on the next one, and each page states the granule position of
 * the last packet that ends on it, or -1 when none does. The first page is
 * marked as the beginning of the stream and the one [finish] writes as its
 * end. Nothing is written back, so any sink will do, and no more than one
 * page is held at a time.
 *
 * Written from RFC 3533. The checksum is [OggCrc].
 */
internal class OggPacketWriter(
    private val sink: AudioSink,
    /** The stream's serial number, the same on every page. */
    private val serial: Int,
) {
    private val lacing = ByteArray(MAX_SEGMENTS)
    private var segments = 0
    private var body = ByteArray(8 * 1024)
    private var bodyLength = 0
    private var granule = -1L
    private var sequence = 0
    private var beginning = true
    private var continued = false
    private var packetOpen = false
    private var finished = false

    /** Packets that end on the page being built. */
    var packetsOnPage: Int = 0
        private set

    /** Lacing values a packet of [length] bytes takes. */
    fun segmentsFor(length: Int): Int = length / 255 + 1

    /** Lacing values still free on the page being built. */
    val freeSegments: Int get() = MAX_SEGMENTS - segments

    /**
     * Adds [length] bytes of [packet] from [offset], whose last sample has
     * granule position [granulePosition]. A page that fills up on the way is
     * written and the packet continues on the next.
     */
    fun writePacket(packet: ByteArray, offset: Int, length: Int, granulePosition: Long) {
        check(!finished) { "the stream has already ended" }
        var at = offset
        var remaining = length
        packetOpen = true
        while (true) {
            if (segments == MAX_SEGMENTS) emitPage(endOfStream = false)
            val take = if (remaining < 255) remaining else 255
            append(packet, at, take)
            at += take
            remaining -= take
            // A lacing value under 255 ends the packet, which is why a
            // packet whose length is a multiple of 255 ends with a zero.
            if (take < 255) break
        }
        packetOpen = false
        granule = granulePosition
        packetsOnPage++
    }

    /** Writes the page being built, if it holds anything. */
    fun flushPage() {
        if (segments > 0) emitPage(endOfStream = false)
    }

    /** Writes the page being built as the stream's last. */
    fun finish() {
        check(!finished) { "the stream has already ended" }
        emitPage(endOfStream = true)
        finished = true
    }

    private fun append(packet: ByteArray, offset: Int, length: Int) {
        lacing[segments++] = length.toByte()
        if (bodyLength + length > body.size) body = body.copyOf(maxOf(body.size * 2, bodyLength + length))
        packet.copyInto(body, bodyLength, offset, offset + length)
        bodyLength += length
    }

    private fun emitPage(endOfStream: Boolean) {
        val page = ByteArray(HEADER_SIZE + segments + bodyLength)
        "OggS".forEachIndexed { i, c -> page[i] = c.code.toByte() }
        page[4] = 0
        page[5] = ((if (continued) 0x01 else 0) or (if (beginning) 0x02 else 0) or (if (endOfStream) 0x04 else 0)).toByte()
        val pageGranule = if (packetsOnPage > 0) granule else -1L
        for (i in 0 until 8) page[6 + i] = (pageGranule ushr (8 * i)).toByte()
        for (i in 0 until 4) page[14 + i] = (serial ushr (8 * i)).toByte()
        for (i in 0 until 4) page[18 + i] = (sequence ushr (8 * i)).toByte()
        page[26] = segments.toByte()
        lacing.copyInto(page, HEADER_SIZE, 0, segments)
        body.copyInto(page, HEADER_SIZE + segments, 0, bodyLength)
        val crc = OggCrc.of(page, 0, page.size)
        for (i in 0 until 4) page[22 + i] = (crc ushr (8 * i)).toByte()
        sink.write(page)
        sequence++
        beginning = false
        // A page that ends inside a packet makes the next one a continuation.
        continued = packetOpen
        segments = 0
        bodyLength = 0
        packetsOnPage = 0
        granule = -1L
    }

    private companion object {
        const val HEADER_SIZE = 27
        const val MAX_SEGMENTS = 255
    }
}

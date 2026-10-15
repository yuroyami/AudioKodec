package io.github.yuroyami.audiokodec.internal.ogg

import io.github.yuroyami.audiokodec.AudioSink

/**
 * Writes one logical Ogg stream: packets in, checksummed pages out.
 *
 * A packet is cut into lacing segments of 255 bytes and a shorter last one,
 * which is how a reader finds where it ends. Segments fill a page until the
 * page holds [pageTarget] bytes or 255 segments, and the rest carry over to the
 * next page, flagged as a continuation. A packet that is an exact multiple of
 * 255 bytes ends with an empty segment, so the reader can tell it has ended.
 *
 * A page states the granule position of the last packet that finishes on it.
 * A page on which no packet finishes states [openPageGranule], which RFC 3533
 * sets at -1.
 *
 * The first page written carries the beginning-of-stream flag, and the page
 * that holds a packet written with `endOfStream` carries the end-of-stream
 * flag. The mappings for Vorbis, Opus, FLAC and Speex want each header to end
 * a page, so that audio starts on a fresh one; a packet written with `flush`
 * ends its page there.
 *
 * The serial number defaults to a fixed value rather than a random one, so
 * that encoding the same input twice gives the same bytes. A file that is
 * later multiplexed or chained with another stream needs distinct serials,
 * which is the caller's to choose.
 *
 * Written from RFC 3533.
 */
internal class OggPageWriter(
    private val sink: AudioSink,
    /** The logical stream's serial number. */
    val serialNumber: Int = DEFAULT_SERIAL,
    /** Payload bytes after which a page is closed, 1 to 65025. */
    private val pageTarget: Int = DEFAULT_PAGE_TARGET,
    /** The granule position of a page on which no packet finishes. */
    private val openPageGranule: Long = -1L,
) {
    init {
        require(pageTarget in 1..MAX_PAYLOAD) { "an Ogg page target is 1 to $MAX_PAYLOAD bytes, not $pageTarget" }
    }

    /** Sequence number the next page will carry, which is also how many have been written. */
    var pagesWritten: Int = 0
        private set

    private val lacing = IntArray(255)
    private var laceCount = 0
    private val body = ByteArray(MAX_PAYLOAD)
    private var bodyLength = 0

    /** True when the page being filled begins inside a packet that started on an earlier one. */
    private var continued = false

    /** The granule of the last packet finished on the page being filled, if any finished. */
    private var pageGranule = 0L
    private var pageEndsAPacket = false

    private var ended = false

    /**
     * Appends one packet that ends at [granule].
     *
     * With [flush], the page holding the packet's end is written straight
     * away instead of waiting to fill. With [endOfStream], it is written too,
     * flagged as the stream's last, and nothing may follow.
     */
    fun writePacket(
        packet: ByteArray,
        granule: Long,
        flush: Boolean = false,
        endOfStream: Boolean = false,
        from: Int = 0,
        to: Int = packet.size,
    ) {
        check(!ended) { "the Ogg stream has already ended" }
        require(from in 0..to && to <= packet.size) { "packet range $from until $to is out of bounds" }
        var at = from
        while (true) {
            val segment = minOf(255, to - at)
            lacing[laceCount++] = segment
            packet.copyInto(body, bodyLength, at, at + segment)
            bodyLength += segment
            at += segment
            val finished = segment < 255
            if (finished) {
                pageGranule = granule
                pageEndsAPacket = true
            }
            val full = laceCount == 255 || bodyLength >= pageTarget
            if (finished) {
                if (endOfStream) {
                    emitBuffered(last = true, nextContinued = false)
                    ended = true
                } else if (flush || full) {
                    emitBuffered(last = false, nextContinued = false)
                }
                return
            }
            if (full) emitBuffered(last = false, nextContinued = true)
        }
    }

    /** Writes whatever is waiting as a page of its own. Does nothing when nothing is. */
    fun flush() {
        if (laceCount > 0) emitBuffered(last = false, nextContinued = continuedAfterBuffer())
    }

    /**
     * Writes a page assembled elsewhere, such as one copied from another file,
     * under this stream's serial number and next sequence number.
     *
     * Nothing may be waiting from [writePacket], because it would land after
     * this page in the file but before it in the stream.
     */
    fun writePage(flags: Int, granule: Long, lacing: IntArray, body: ByteArray) {
        check(laceCount == 0) { "packets are waiting to be written before this page" }
        emit(flags, granule, lacing, lacing.size, body, body.size)
    }

    private fun continuedAfterBuffer(): Boolean = laceCount > 0 && lacing[laceCount - 1] == 255

    private fun emitBuffered(last: Boolean, nextContinued: Boolean) {
        val flags = (if (continued) OggPage.FLAG_CONTINUED else 0) or
            (if (pagesWritten == 0) OggPage.FLAG_FIRST else 0) or
            (if (last) OggPage.FLAG_LAST else 0)
        emit(flags, if (pageEndsAPacket) pageGranule else openPageGranule, lacing, laceCount, body, bodyLength)
        laceCount = 0
        bodyLength = 0
        pageEndsAPacket = false
        continued = nextContinued
    }

    private fun emit(flags: Int, granule: Long, lacing: IntArray, lacingCount: Int, body: ByteArray, bodyLength: Int) {
        val whole = ByteArray(OggPage.HEADER_BYTES + lacingCount + bodyLength)
        OggPage.CAPTURE.copyInto(whole, 0)
        whole[4] = 0
        whole[5] = flags.toByte()
        for (i in 0 until 8) whole[6 + i] = (granule ushr (8 * i)).toByte()
        for (i in 0 until 4) whole[14 + i] = (serialNumber ushr (8 * i)).toByte()
        for (i in 0 until 4) whole[18 + i] = (pagesWritten ushr (8 * i)).toByte()
        // The checksum field stays zero while the checksum is computed.
        whole[26] = lacingCount.toByte()
        for (i in 0 until lacingCount) whole[OggPage.HEADER_BYTES + i] = lacing[i].toByte()
        body.copyInto(whole, OggPage.HEADER_BYTES + lacingCount, 0, bodyLength)
        val crc = OggCrc.of(whole, 0, whole.size)
        for (i in 0 until 4) whole[22 + i] = (crc ushr (8 * i)).toByte()
        sink.write(whole)
        pagesWritten++
    }

    companion object {
        /** The serial number of a stream whose caller did not choose one. */
        const val DEFAULT_SERIAL: Int = 0x4155_4B44

        /** The page size an encoder aims for, as libogg does: big enough to amortise the header, small enough to seek well. */
        const val DEFAULT_PAGE_TARGET: Int = 4096

        /** The most payload one page can carry: 255 segments of 255 bytes. */
        const val MAX_PAYLOAD: Int = 255 * 255
    }
}

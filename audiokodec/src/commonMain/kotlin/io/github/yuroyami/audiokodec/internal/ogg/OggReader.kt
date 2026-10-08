package io.github.yuroyami.audiokodec.internal.ogg

import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.io.SourceReader

/**
 * Reads Ogg pages and reassembles the packets inside them.
 *
 * Two layers live here. Reading a page is a matter of a header, a segment
 * table and a checksum. Reassembling packets is the layer above: a packet ends
 * where a lacing value below 255 says it does, and until then it continues,
 * possibly onto the next page and the one after that.
 *
 * A file may carry several logical streams at once, which is how a video with
 * a soundtrack is stored, and it may carry them one after another, which is how
 * two recordings are joined. Both are handled: pages are filtered by serial
 * number, and a new stream beginning is reported rather than being mistaken for
 * corruption.
 *
 * Written from RFC 3533.
 */
internal class OggReader(
    private val reader: SourceReader,
    private val options: DecodeOptions,
) {
    private val header = ByteArray(OggPage.HEADER_BYTES + 255)

    /**
     * Reads the page starting at [at].
     *
     * Returns null at end of input. Throws [CorruptStreamException] when the
     * bytes are not a page or the checksum does not match, which is what makes
     * a byte offset found by searching safe to trust.
     */
    fun pageAt(at: Long): OggPage? {
        require(at >= 0) { "an Ogg page offset must not be negative, was $at" }
        reader.seek(at)
        val got = reader.readSome(header, 0, OggPage.HEADER_BYTES)
        if (got == 0) return null
        if (got < OggPage.HEADER_BYTES) {
            return truncatedPage(
                "the Ogg page header is cut short: wanted ${OggPage.HEADER_BYTES} bytes, found $got",
                at,
            )
        }

        for (i in 0 until 4) {
            if (header[i] != OggPage.CAPTURE[i]) {
                throw CorruptStreamException("no page capture pattern at this position", at)
            }
        }
        val version = header[4].toInt() and 0xFF
        if (version != 0) {
            throw CorruptStreamException("Ogg page version $version is not 0", at)
        }
        val flags = header[5].toInt() and 0xFF
        if (flags and 0xF8 != 0) {
            throw CorruptStreamException(
                "the Ogg page uses reserved header flags 0x${flags.toString(16)}",
                at + 5,
            )
        }
        val granule = readLong(header, 6)
        val serial = readInt(header, 14)
        val sequence = readInt(header, 18)
        val storedCrc = readInt(header, 22)
        val segmentCount = header[26].toInt() and 0xFF

        val segmentBytes = reader.readSome(header, OggPage.HEADER_BYTES, segmentCount)
        if (segmentBytes < segmentCount) {
            return truncatedPage(
                "the Ogg segment table is cut short: wanted $segmentCount bytes, found $segmentBytes",
                checkedOffset(at, OggPage.HEADER_BYTES),
            )
        }
        val segments = IntArray(segmentCount) {
            header[OggPage.HEADER_BYTES + it].toInt() and 0xFF
        }
        var payloadSize = 0
        for (segment in segments) payloadSize += segment

        val headerSize = OggPage.HEADER_BYTES + segmentCount
        val total = headerSize + payloadSize
        options.checkAllocation(total.toLong(), "reading an Ogg page of $total bytes")

        // The checksum covers the header and the payload together, so both are
        // gathered before it can be checked.
        val whole = ByteArray(total)
        header.copyInto(whole, 0, 0, headerSize)
        val payloadAt = checkedOffset(at, headerSize)
        reader.seek(payloadAt)
        val payloadBytes = reader.readSome(whole, headerSize, payloadSize)
        if (payloadBytes < payloadSize) {
            return truncatedPage(
                "the Ogg page payload is cut short: wanted $payloadSize bytes, found $payloadBytes",
                payloadAt,
            )
        }

        val actualCrc = OggCrc.ofPage(whole, 0, total, 22)
        if (actualCrc != storedCrc) {
            throw CorruptStreamException(
                "the page checksum is $storedCrc but the page hashes to $actualCrc", at,
            )
        }

        return OggPage(
            offset = at,
            flags = flags,
            granulePosition = granule,
            serialNumber = serial,
            sequenceNumber = sequence,
            segments = segments,
            payload = whole.copyOfRange(headerSize, total),
            byteLength = total,
        )
    }

    /**
     * Finds the first page at or after [from].
     *
     * A well-formed file has one at [from] exactly. Searching exists for the
     * files that are not: a stream cut out of a larger one, or one with junk in
     * front. The checksum is what makes the result trustworthy, because four
     * bytes spelling `OggS` turn up in ordinary audio often enough to matter.
     */
    fun findPageFrom(from: Long, limit: Long = Long.MAX_VALUE): OggPage? {
        val size = reader.size
        val end = if (size > 0) minOf(size, limit) else limit
        var at = from.coerceAtLeast(0L)
        val window = ByteArray(64 * 1024)

        while (at < end) {
            reader.seek(at)
            val got = reader.readSome(window, 0, window.size)
            if (got < 4) return null
            var index = 0
            while (index <= got - 4) {
                if (window[index] == OggPage.CAPTURE[0] &&
                    window[index + 1] == OggPage.CAPTURE[1] &&
                    window[index + 2] == OggPage.CAPTURE[2] &&
                    window[index + 3] == OggPage.CAPTURE[3]
                ) {
                    val candidate = at + index
                    val page = try {
                        pageAt(candidate)
                    } catch (_: CorruptStreamException) {
                        null
                    } catch (_: TruncatedStreamException) {
                        null
                    }
                    if (page != null) return page
                }
                index++
            }
            // Overlap by three bytes so a pattern split across two windows is
            // still found.
            val advance = (got - 3).coerceAtLeast(1)
            if (at > Long.MAX_VALUE - advance) return null
            at += advance
        }
        return null
    }

    /**
     * Finds the last page of the file, or of the logical stream [serial].
     *
     * This is how the length of an Ogg file is discovered: the granule position
     * of the last page is where the stream ends. The search works backwards
     * from the end, because that is where the answer is.
     */
    fun findLastPage(serial: Int? = null): OggPage? {
        val size = reader.size
        if (size <= 0) return null
        var windowEnd = size
        val windowSize = 64 * 1024

        // Chained and multiplexed files can end with megabytes belonging to a
        // different serial. Keep walking to the beginning instead of imposing
        // an arbitrary trailing-window limit on an otherwise valid stream.
        while (windowEnd > 0) {
            val windowStart = (windowEnd - windowSize).coerceAtLeast(0L)
            var best: OggPage? = null
            var at = windowStart
            while (at < windowEnd) {
                val page = findPageFrom(at, windowEnd) ?: break
                if (serial == null || page.serialNumber == serial) best = page
                if (page.offset == Long.MAX_VALUE) break
                at = page.offset + 1
            }
            if (best != null) return best
            if (windowStart == 0L) return null
            windowEnd = windowStart + 3
        }
        return null
    }

    /**
     * Walks the logical streams a file begins with.
     *
     * Every stream's first page carries the beginning-of-stream flag, and they
     * all come before any audio, so this reads pages until it meets one that is
     * not a beginning.
     */
    fun readStreamHeads(): List<OggPage> {
        val heads = ArrayList<OggPage>()
        val serials = HashSet<Int>()
        var retainedBytes = 0L
        var at = 0L
        while (true) {
            val page = try {
                pageAt(at)
            } catch (e: CorruptStreamException) {
                when {
                    heads.isEmpty() -> findPageFrom(at)
                    options.tolerant -> findPageFrom(checkedOffset(at, 4))
                    else -> throw e
                }
            } ?: break
            if (!page.isFirst) break
            if (page.serialNumber in serials) {
                throw CorruptStreamException(
                    "two beginning-of-stream pages use serial number ${page.serialNumber}",
                    page.offset,
                )
            }
            if (heads.size == MAX_STREAM_HEADS) {
                throw UnsupportedFeatureException("Ogg files with more than $MAX_STREAM_HEADS simultaneous logical streams")
            }
            // Retain the page, payload, lacing and both lookup collections.
            // The fixed allowance includes the serial index's entry overhead.
            retainedBytes += 96L + page.payload.size + page.segments.size * 4L
            options.checkAllocation(
                retainedBytes,
                "remembering Ogg logical stream headers",
            )
            serials.add(page.serialNumber)
            heads.add(page)
            at = checkedOffset(page.offset, page.byteLength)
        }
        return heads
    }

    /** Last usable page and the reason a tolerant walk stopped early. */
    class StreamEnd(val page: OggPage?, val failure: AudioKodecException? = null)

    /**
     * Finds the end page of the logical stream that begins at [from].
     *
     * A backwards serial-number search is not enough: a later chained stream
     * is allowed to reuse the same serial number. Walking to the first EOS page
     * keeps the duration of the first stream from accidentally including a
     * later recording.
     */
    fun findStreamEnd(serial: Int, from: Long): StreamEnd {
        var at = from.coerceAtLeast(0L)
        var last: OggPage? = null
        var lastSequence = 0
        var selectedPageSeen = false
        var packetOpen = false
        val size = reader.size
        fun stop(failure: AudioKodecException): StreamEnd {
            if (!options.tolerant) throw failure
            return StreamEnd(last, failure)
        }
        while (size < 0 || at < size) {
            val page = try {
                pageAt(at)
            } catch (e: CorruptStreamException) {
                return stop(e)
            } ?: break
            at = checkedOffset(page.offset, page.byteLength)
            if (page.serialNumber != serial) continue
            if (selectedPageSeen) {
                if (page.isFirst) {
                    return stop(CorruptStreamException(
                        "a second Ogg logical stream begins before the first one reaches EOS",
                        page.offset,
                    ))
                }
                if (page.sequenceNumber != lastSequence + 1) {
                    return stop(CorruptStreamException(
                        "Ogg page sequence jumps from $lastSequence to ${page.sequenceNumber}",
                        page.offset,
                    ))
                }
                if (page.isContinued != packetOpen) {
                    return stop(CorruptStreamException(
                        "an Ogg page's continuation flag disagrees with the preceding packet boundary",
                        page.offset,
                    ))
                }
            }
            selectedPageSeen = true
            lastSequence = page.sequenceNumber
            last = page
            if (page.segments.isNotEmpty()) packetOpen = page.segments.last() == 255
            if (page.isLast) {
                if (!page.endsAPacket && !options.tolerant) {
                    throw TruncatedStreamException(
                        "the Ogg logical stream reaches EOS in the middle of a packet",
                        page.offset,
                    )
                }
                return StreamEnd(page)
            }
        }
        if (last != null) {
            return stop(TruncatedStreamException(
                "the Ogg logical stream ends before an end-of-stream page",
                at,
            ))
        }
        return StreamEnd(null)
    }

    /** Reads packets out of the pages of one logical stream. */
    fun packets(serial: Int, from: Long, until: Long = Long.MAX_VALUE): PacketReader = PacketReader(serial, from, until)

    /**
     * Reassembles the packets of one logical stream.
     *
     * The reader keeps the part of a packet it has seen so far, so a packet
     * spanning three pages arrives whole. It also reports where each packet
     * ended, which is what a seek needs in order to resume cleanly.
     */
    inner class PacketReader(private val serial: Int, from: Long, private val until: Long) {

        private var nextPageOffset = from
        private var pending: ByteArray = ByteArray(0)
        private var pendingLength = 0
        private var pendingStartOffset = -1L

        /** Segment index within the current page. */
        private var segmentIndex = 0
        private var payloadOffset = 0
        private var currentPage: OggPage? = null
        private var selectedPageSeen = false
        private var lastSequence = 0
        private var eosPageLoaded = false
        private var discardingContinuation = false

        /** Granule position of the page the last returned packet finished on. */
        var lastGranule: Long = -1L
            private set

        /** True once the end-of-stream page has been consumed. */
        var atEnd: Boolean = false
            private set

        /** Damage that ended tolerant reading, without joining later packets across it. */
        var failure: AudioKodecException? = null
            private set

        /** Reads the next packet, or returns null at the end of the stream. */
        fun next(): Packet? {
            while (true) {
                var page = currentPage
                if (page == null || segmentIndex >= page.segments.size) {
                    page = advancePage() ?: run {
                        // A packet still open at the end of input is
                        // incomplete, and incomplete is not a packet.
                        val incomplete = pendingLength > 0 || discardingContinuation
                        val incompleteAt = pendingStartOffset.takeIf { it >= 0 } ?: nextPageOffset
                        clearPending()
                        discardingContinuation = false
                        if (incomplete && !options.tolerant) {
                            throw TruncatedStreamException(
                                "the Ogg logical stream ends in the middle of a packet",
                                incompleteAt,
                            )
                        }
                        return null
                    }
                }

                var complete = false
                while (segmentIndex < page.segments.size) {
                    val segment = page.segments[segmentIndex]
                    if (pendingStartOffset < 0) {
                        pendingStartOffset = payloadPosition(page, payloadOffset)
                    }
                    append(page.payload, payloadOffset, segment)
                    payloadOffset += segment
                    segmentIndex++
                    if (segment != 255) {
                        complete = true
                        break
                    }
                }

                if (complete) {
                    val bytes = pending
                    val length = pendingLength
                    val startOffset = pendingStartOffset
                    val endOffset = payloadPosition(page, payloadOffset)
                    val endsPage = segmentIndex >= page.segments.size
                    val pageEndOffset = checkedOffset(page.offset, page.byteLength)
                    clearPending()
                    // The granule position belongs to the page a packet ends
                    // on, so it is read after the packet is complete.
                    val granule = if (endsPage) {
                        page.granulePosition
                    } else {
                        -1L
                    }
                    if (endsPage && page.granulePosition >= 0) {
                        lastGranule = page.granulePosition
                    }
                    val isLast = endsPage && page.isLast
                    if (isLast) atEnd = true
                    return Packet(
                        bytes = bytes,
                        length = length,
                        granule = granule,
                        isLast = isLast,
                        startOffset = startOffset,
                        endOffset = endOffset,
                        endsPage = endsPage,
                        pageEndOffset = pageEndOffset,
                    )
                }
            }
        }

        private fun advancePage(): OggPage? {
            if (atEnd) return null
            if (eosPageLoaded) {
                atEnd = true
                return null
            }
            val size = reader.size
            while (true) {
                if (nextPageOffset >= until || size in 1..nextPageOffset) {
                    atEnd = true
                    return null
                }
                val page = try {
                    pageAt(nextPageOffset)
                } catch (e: CorruptStreamException) {
                    return stop(e)
                } ?: run {
                    atEnd = true
                    return null
                }

                nextPageOffset = checkedOffset(page.offset, page.byteLength)
                if (page.serialNumber != serial) {
                    // Another logical stream is multiplexed into this file, and
                    // its pages are simply not ours.
                    continue
                }

                val wasSeen = selectedPageSeen
                if (wasSeen && page.isFirst) {
                    return stop(CorruptStreamException(
                        "a second beginning-of-stream page appears before this logical stream ends",
                        page.offset,
                    ))
                }
                if (page.isFirst && page.isContinued) {
                    return stop(CorruptStreamException(
                        "an Ogg beginning-of-stream page is marked as a continuation",
                        page.offset,
                    ))
                }

                if (wasSeen && page.sequenceNumber != lastSequence + 1) {
                    return stop(CorruptStreamException(
                        "Ogg page sequence jumps from $lastSequence to ${page.sequenceNumber}",
                        page.offset,
                    ))
                }
                selectedPageSeen = true
                lastSequence = page.sequenceNumber

                val needsContinuation = pendingLength > 0 || discardingContinuation
                var discardFirstPacket = false
                if (needsContinuation && !page.isContinued) {
                    return stop(CorruptStreamException(
                        "an Ogg packet continues past a page whose successor is not marked continued",
                        page.offset,
                    ))
                } else if (!needsContinuation && page.isContinued) {
                    if (wasSeen) {
                        return stop(CorruptStreamException(
                            "an Ogg page is marked continued but no packet is open",
                            page.offset,
                        ))
                    }
                    // A seek may start inside a packet. Its leading fragment
                    // cannot be used, but subsequent complete packets can.
                    discardFirstPacket = true
                    discardingContinuation = true
                }

                currentPage = page
                segmentIndex = 0
                payloadOffset = 0
                eosPageLoaded = page.isLast

                if (discardFirstPacket || discardingContinuation) {
                    discardLeadingContinuation(page)
                }
                return page
            }
        }

        private fun stop(error: AudioKodecException): OggPage? {
            if (!options.tolerant) throw error
            failure = error
            atEnd = true
            clearPending()
            discardingContinuation = false
            return null
        }

        private fun discardLeadingContinuation(page: OggPage) {
            while (segmentIndex < page.segments.size) {
                val segment = page.segments[segmentIndex]
                payloadOffset += segment
                segmentIndex++
                if (segment != 255) {
                    discardingContinuation = false
                    return
                }
            }
        }

        private fun append(source: ByteArray, offset: Int, count: Int) {
            if (count == 0) return
            val needed = pendingLength.toLong() + count
            if (needed > Int.MAX_VALUE) {
                options.checkAllocation(needed, "reassembling an Ogg packet")
                throw CorruptStreamException("an Ogg packet is larger than an in-memory array")
            }
            if (needed > pending.size) {
                var next = if (pending.isEmpty()) 4096L else pending.size.toLong()
                while (next < needed) {
                    next = minOf(Int.MAX_VALUE.toLong(), next * 2)
                    if (next < needed && next == Int.MAX_VALUE.toLong()) break
                }
                if (next < needed) next = needed
                options.checkAllocation(next, "reassembling an Ogg packet")
                pending = pending.copyOf(next.toInt())
            }
            source.copyInto(pending, pendingLength, offset, offset + count)
            pendingLength += count
        }

        private fun clearPending() {
            pending = ByteArray(0)
            pendingLength = 0
            pendingStartOffset = -1L
        }

        private fun payloadPosition(page: OggPage, inPayload: Int): Long =
            checkedOffset(
                checkedOffset(page.offset, OggPage.HEADER_BYTES + page.segments.size),
                inPayload,
            )
    }

    /** One reassembled packet. */
    class Packet(
        val bytes: ByteArray,
        val length: Int,
        /** Granule position of the page this packet finished on, or -1. */
        val granule: Long,
        /** True when this packet ended on a page marked end of stream. */
        val isLast: Boolean,
        /** File offset of the packet's first payload byte. */
        val startOffset: Long,
        /** File offset immediately after its final payload byte. */
        val endOffset: Long,
        /** True when no later packet shares the page this one ended on. */
        val endsPage: Boolean,
        /** File offset immediately after the page this packet ended on. */
        val pageEndOffset: Long,
    )

    private fun truncatedPage(message: String, at: Long): OggPage? {
        if (options.tolerant) return null
        throw TruncatedStreamException(message, at)
    }

    private fun checkedOffset(at: Long, count: Int): Long {
        if (count < 0 || at > Long.MAX_VALUE - count) {
            throw CorruptStreamException("an Ogg page offset overflows 64 bits", at)
        }
        return at + count
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun readLong(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        }
        return value
    }

    private companion object {
        const val MAX_STREAM_HEADS = 256
    }
}

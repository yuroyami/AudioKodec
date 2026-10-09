package io.github.yuroyami.audiokodec.internal.mpeg

import io.github.yuroyami.audiokodec.internal.io.SourceReader

/**
 * Refines a Xing byte hint into a verified frame ordinal.
 *
 * The 100 TOC entries contain quantised byte positions.
 * Assigning their percentage directly to a frame number can move playback by
 * several frames. Count the complete suffix from the hint to the stored end,
 * then subtract it from the stored frame count. Walking from that verified
 * ordinal preserves sample accuracy and lets the decoder prime its history.
 */
internal class MpegXingSeek(private val reader: SourceReader, private val stream: MpegStream) {
    class Landing(val number: Long, val offset: Long)

    private val vbr = stream.vbr
    private val toc = vbr?.tableOfContents
    private val probe = ByteArray(4)
    private var cached: Landing? = null
    private val span = if (stream.vbrFrameOffset in 0..stream.audioEnd) {
        stream.audioEnd - stream.vbrFrameOffset
    } else -1L
    private val statedBytes = vbr?.byteCount ?: -1L
    private val byteCount = if (statedBytes > 0) statedBytes else span
    private val end = if (byteCount in 1..span) stream.vbrFrameOffset + byteCount else stream.audioEnd
    private var paddingVerified: Boolean? = null

    /** Hints outside the source range or with backwards positions are ignored. */
    val usable: Boolean = toc != null && toc.size == 100 && stream.frameCount > 0 &&
        stream.vbrFrameOffset >= 0 && stream.audioEnd > stream.audioOffset &&
        byteCount in 1..span && end > stream.audioOffset &&
        toc[0] == 0.toByte() && (toc[99].toInt() and 255) > 0 &&
        (1 until 100).all { (toc[it].toInt() and 255) >= (toc[it - 1].toInt() and 255) }

    fun cachedDistance(target: Long): Long? = cached?.takeIf { it.number <= target }?.let { target - it.number }

    fun find(target: Long): Landing? {
        if (!usable || target < 0 || target >= stream.frameCount || !verifyPadding(end)) return null
        if (target == 0L) return Landing(0, stream.audioOffset).also { cached = it }
        val previous = cached
        if (previous != null && previous.number <= target &&
            target - previous.number <= stream.frameCount - target
        ) {
            return walk(previous, target)?.takeIf { it.number == target }?.also { cached = it }
        }
        val table = toc ?: return null
        // Start one bucket earlier, leaving room for quantisation and decoder
        // pre-roll. Hints that land too late retreat towards the first bucket.
        var bucket = (target * 100 / stream.frameCount - 1).toInt().coerceIn(0, 99)
        var retreat = 1
        while (true) {
            // Bucket zero is the already known start, so reaching it leaves
            // the caller's normal prefix walk as the appropriate fallback.
            if (bucket == 0) return null
            val hint = (stream.vbrFrameOffset + (table[bucket].toInt() and 255) * byteCount / 256)
                .coerceAtLeast(stream.audioOffset)
            val found = MpegStream.findFirstFrame(reader, hint, end, stream.first)
            if (found != null && found.header.matches(stream.first)) {
                val suffix = walk(Landing(0, found.offset), Long.MAX_VALUE)
                if (suffix != null && suffix.offset == end) {
                    val number = stream.frameCount - suffix.number
                    if (number in 0..target) {
                        return walk(Landing(number, found.offset), target)
                            ?.takeIf { it.number == target }?.also { cached = it }
                    }
                }
            }
            bucket = (bucket - retreat).coerceAtLeast(0)
            retreat = (retreat * 2).coerceAtMost(100)
        }
    }

    /** Walks complete headers, skipping recognised ID3 blocks without counting them. */
    private fun walk(from: Landing, until: Long): Landing? {
        var offset = from.offset
        var number = from.number
        while (number < until && offset < end) {
            if (end - offset < 4) {
                return if (verifyPadding(offset)) Landing(number, end) else null
            }
            reader.seek(offset)
            if (reader.readSome(probe, 0, probe.size) < probe.size) return null
            val tagEnd = MpegStream.embeddedTagEnd(reader, offset, probe, end)
            if (tagEnd != null) {
                offset = tagEnd
                continue
            }
            val header = MpegFrameHeader.parse(probe, 0, stream.first.freeFormatFrameBytes) ?: return if (probe.all { it == 0.toByte() } &&
                verifyPadding(offset)
            ) Landing(number, end) else null
            if (!header.matches(stream.first) || header.frameBytes > end - offset ||
                number >= stream.frameCount
            ) return null
            offset += header.frameBytes
            number++
        }
        return Landing(number, offset)
    }

    /** A byte count can exclude trailing zero padding; every excluded byte is checked. */
    private fun verifyPadding(from: Long): Boolean {
        if (from == end) paddingVerified?.let { return it }
        var offset = from
        while (offset < stream.audioEnd) {
            val wanted = minOf(probe.size.toLong(), stream.audioEnd - offset).toInt()
            reader.seek(offset)
            if (reader.readSome(probe, 0, wanted) != wanted) return false
            for (index in 0 until wanted) if (probe[index] != 0.toByte()) return false
            offset += wanted
        }
        if (from == end) paddingVerified = true
        return true
    }
}

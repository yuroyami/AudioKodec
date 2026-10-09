package io.github.yuroyami.audiokodec.internal.container

import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.tag.RawTagEntry

/**
 * Reads the structure of a Matroska or WebM file, RFC 9559: the segment's
 * timing, its tracks, its tags and chapters, and the frames of one audio
 * track, placed in the file.
 *
 * A Matroska file is an EBML document whose one top-level element after the
 * header is the Segment. Its children come in any order and may repeat, the
 * Clusters holding the frames in Blocks, each Block naming its track and its
 * time relative to its Cluster. A Block can lace several frames together,
 * by three schemes that differ only in how they state the frames' sizes.
 */
internal object MatroskaReader {

    class Track(
        val number: Long,
        val uid: Long,
        val codecId: String,
        val codecPrivate: ByteArray?,
        val sampleRate: Double,
        val outputSampleRate: Double?,
        val channels: Int,
        val bitDepth: Int?,
        /** Nanoseconds the decoder's output starts before the track's first frame is meant to be heard. */
        val codecDelay: Long,
        /** Bytes to put back in front of every frame, from header stripping. */
        val strippedHeader: ByteArray?,
        /** True when every frame is zlib-compressed. */
        val zlib: Boolean,
    )

    /** The frames of the chosen track, in order, with where they are and when. */
    class Frames(
        val offsets: LongArray,
        val sizes: IntArray,
        /** Each frame's Block timestamp in nanoseconds; laced frames share their Block's. */
        val timestamps: LongArray,
        /** Nanoseconds the last frame's Block says to discard from its end. */
        val discardPadding: Long,
        val count: Int,
    )

    class Chapter(val startNs: Long, val endNs: Long?, val title: String?, val uid: Long)

    class File(
        val docType: String,
        val timestampScale: Long,
        /** The segment's stated duration in nanoseconds, or null. */
        val durationNs: Double?,
        val title: String?,
        val tracks: List<Track>,
        val audio: Track?,
        val frames: Frames?,
        /** Every SimpleTag that applies to the whole segment or to [audio]. */
        val tags: List<RawTagEntry>,
        val chapters: List<Chapter>,
    )

    private const val EBML = 0x1A45DFA3L
    private const val DOC_TYPE = 0x4282L
    private const val CRC32 = 0xBFL
    private const val VOID = 0xECL

    /**
     * Reads the file. With [withFrames] false the Clusters are skipped, which
     * is enough to probe it and read its tags.
     */
    fun read(reader: SourceReader, options: DecodeOptions, withFrames: Boolean): File {
        val ebml = Ebml.header(reader, 0) ?: throw TruncatedStreamException("a Matroska file is empty", 0)
        if (ebml.id != EBML || ebml.size == Ebml.UNKNOWN_SIZE) throw CorruptStreamException("a Matroska file does not begin with an EBML header", 0)
        var docType = "matroska"
        children(reader, ebml) { child ->
            if (child.id == DOC_TYPE) docType = Ebml.string(reader, child, 64)
            true
        }
        if (docType != "matroska" && docType != "webm") {
            throw UnsupportedFeatureException("EBML document type '$docType'", "this EBML file is a '$docType' document, not Matroska or WebM")
        }
        val segment = Ebml.header(reader, ebml.end) ?: throw TruncatedStreamException("a Matroska file has no Segment", ebml.end)
        if (segment.id != MatroskaIds.SEGMENT) throw CorruptStreamException("a Matroska file's top element is not a Segment", segment.offset)

        var scale = 1_000_000L
        var duration: Double? = null
        var title: String? = null
        val tracks = ArrayList<Track>()
        val tagBlocks = ArrayList<Pair<Long?, List<RawTagEntry>>>()
        val chapters = ArrayList<Chapter>()
        val clusters = ArrayList<Ebml.Header>()

        children(reader, segment) { child ->
            when (child.id) {
                MatroskaIds.INFO -> children(reader, child) { info ->
                    when (info.id) {
                        MatroskaIds.TIMESTAMP_SCALE -> scale = Ebml.unsigned(reader, info)
                        MatroskaIds.DURATION -> duration = Ebml.float(reader, info)
                        MatroskaIds.TITLE -> title = Ebml.string(reader, info, MAX_TEXT)
                    }
                    true
                }
                MatroskaIds.TRACKS -> children(reader, child) { entry ->
                    if (entry.id == MatroskaIds.TRACK_ENTRY) track(reader, entry, options)?.let { tracks.add(it) }
                    true
                }
                MatroskaIds.TAGS -> children(reader, child) { tag ->
                    if (tag.id == MatroskaIds.TAG) tagBlocks.add(tag(reader, tag))
                    true
                }
                MatroskaIds.CHAPTERS -> readChapters(reader, child, chapters)
                MatroskaIds.CLUSTER -> {
                    clusters.add(child)
                    // Without the frames, the first Cluster is as far as the
                    // header elements usually go; any after it are found by
                    // carrying on only when the size is known.
                    if (!withFrames && child.size == Ebml.UNKNOWN_SIZE) return@children false
                }
            }
            true
        }
        if (scale <= 0) throw CorruptStreamException("a Matroska TimestampScale of $scale", segment.offset)
        val audio = tracks.firstOrNull()
        val tags = ArrayList<RawTagEntry>()
        for ((target, entries) in tagBlocks) if (target == null || target == audio?.uid) tags.addAll(entries)
        val frames = if (withFrames && audio != null) frames(reader, clusters, audio, scale, options) else null
        return File(docType, scale, duration?.let { it * scale }, title, tracks, audio, frames, tags, chapters)
    }

    /**
     * Calls [visit] for each child of [parent] until it returns false,
     * skipping CRC-32 and Void elements. An element of unknown size, as a
     * live writer leaves a Segment or Cluster, ends where the data does or at
     * the first element that cannot be its child, RFC 8794 section 6.2.
     */
    private inline fun children(reader: SourceReader, parent: Ebml.Header, visit: (Ebml.Header) -> Boolean) {
        val unknown = parent.size == Ebml.UNKNOWN_SIZE
        var at = parent.dataOffset
        val end = if (unknown) (if (reader.size >= 0) reader.size else Long.MAX_VALUE) else parent.end
        while (at < end) {
            val child = Ebml.header(reader, at) ?: return
            if (unknown && ends(parent.id, child.id)) return
            if (child.size != Ebml.UNKNOWN_SIZE && child.size > end - child.dataOffset) {
                throw CorruptStreamException("a Matroska element runs past its parent", child.offset)
            }
            if (child.size == Ebml.UNKNOWN_SIZE) checkUnknownAllowed(parent, child)
            if (child.id != CRC32 && child.id != VOID && !visit(child)) return
            at = if (child.size == Ebml.UNKNOWN_SIZE) endOfUnknown(reader, child, end) else child.end
        }
    }

    /** Where an element of unknown size ends: at the first element that cannot be its child, or at [limit]. */
    private fun endOfUnknown(reader: SourceReader, element: Ebml.Header, limit: Long): Long {
        var at = element.dataOffset
        while (at < limit) {
            val child = Ebml.header(reader, at) ?: return limit
            if (ends(element.id, child.id)) return at
            if (child.size != Ebml.UNKNOWN_SIZE && child.size > limit - child.dataOffset) return limit
            if (child.size == Ebml.UNKNOWN_SIZE) checkUnknownAllowed(element, child)
            at = if (child.size == Ebml.UNKNOWN_SIZE) endOfUnknown(reader, child, limit) else child.end
        }
        return limit
    }

    /** Only a Cluster in the Segment may have an unknown size below the top, which also bounds the search for its end. */
    private fun checkUnknownAllowed(parent: Ebml.Header, child: Ebml.Header) {
        if (parent.id != MatroskaIds.SEGMENT || child.id != MatroskaIds.CLUSTER) {
            throw CorruptStreamException("a Matroska element of unknown size where none may have one", child.offset)
        }
    }

    /**
     * Whether an element [id] ends an element [parent] of unknown size. Only
     * the Segment and Cluster may have one; a Segment ends at another EBML
     * document, and anything else at a sibling of the Cluster or above.
     */
    private fun ends(parent: Long, id: Long): Boolean = when (id) {
        EBML, MatroskaIds.SEGMENT -> true
        MatroskaIds.SEEK_HEAD, MatroskaIds.INFO, MatroskaIds.TRACKS, MatroskaIds.CLUSTER, MatroskaIds.CUES,
        MatroskaIds.ATTACHMENTS, MatroskaIds.CHAPTERS, MatroskaIds.TAGS,
        -> parent != MatroskaIds.SEGMENT
        else -> false
    }

    private const val MAX_TEXT = 1 shl 20
    private const val MAX_PRIVATE = 1 shl 24

    /** Reads one TrackEntry, or returns null when it is not audio. */
    private fun track(reader: SourceReader, entry: Ebml.Header, options: DecodeOptions): Track? {
        var number = 0L
        var uid = 0L
        var type = 0L
        var codec = ""
        var private: ByteArray? = null
        var rate = 8000.0
        var outputRate: Double? = null
        var channels = 1L
        var depth: Int? = null
        var delay = 0L
        var stripped: ByteArray? = null
        var zlib = false
        children(reader, entry) { e ->
            when (e.id) {
                MatroskaIds.TRACK_NUMBER -> number = Ebml.unsigned(reader, e)
                MatroskaIds.TRACK_UID -> uid = Ebml.unsigned(reader, e)
                MatroskaIds.TRACK_TYPE -> type = Ebml.unsigned(reader, e)
                MatroskaIds.CODEC_ID -> codec = Ebml.string(reader, e, 256)
                MatroskaIds.CODEC_PRIVATE -> {
                    options.checkAllocation(e.size, "reading a Matroska CodecPrivate")
                    private = Ebml.bytes(reader, e, MAX_PRIVATE)
                }
                MatroskaIds.CODEC_DELAY -> delay = Ebml.unsigned(reader, e)
                MatroskaIds.AUDIO -> children(reader, e) { a ->
                    when (a.id) {
                        MatroskaIds.SAMPLING_FREQUENCY -> rate = Ebml.float(reader, a)
                        MatroskaIds.OUTPUT_SAMPLING_FREQUENCY -> outputRate = Ebml.float(reader, a)
                        MatroskaIds.CHANNELS -> channels = Ebml.unsigned(reader, a)
                        MatroskaIds.BIT_DEPTH -> depth = Ebml.unsigned(reader, a).toInt()
                    }
                    true
                }
                MatroskaIds.CONTENT_ENCODINGS -> children(reader, e) { encoding ->
                    if (encoding.id == MatroskaIds.CONTENT_ENCODING) {
                        var scope = 1L
                        var kind = 0L
                        var algorithm = -1L
                        var settings: ByteArray? = null
                        children(reader, encoding) { c ->
                            when (c.id) {
                                MatroskaIds.CONTENT_ENCODING_SCOPE -> scope = Ebml.unsigned(reader, c)
                                MatroskaIds.CONTENT_ENCODING_TYPE -> kind = Ebml.unsigned(reader, c)
                                MatroskaIds.CONTENT_ENCRYPTION -> kind = 1
                                MatroskaIds.CONTENT_COMPRESSION -> {
                                    algorithm = 0
                                    children(reader, c) { cc ->
                                        when (cc.id) {
                                            MatroskaIds.CONTENT_COMP_ALGO -> algorithm = Ebml.unsigned(reader, cc)
                                            MatroskaIds.CONTENT_COMP_SETTINGS -> settings = Ebml.bytes(reader, cc, 1 shl 16)
                                        }
                                        true
                                    }
                                }
                            }
                            true
                        }
                        if (kind == 1L) throw UnsupportedFeatureException("encrypted Matroska tracks", "this Matroska track is encrypted")
                        if (scope and 1L != 0L) {
                            when (algorithm) {
                                0L -> zlib = true
                                3L -> stripped = settings ?: ByteArray(0)
                                else -> throw UnsupportedFeatureException(
                                    "Matroska content compression $algorithm",
                                    "this Matroska track's frames are compressed by algorithm $algorithm, which AudioKodec cannot undo",
                                )
                            }
                        }
                    }
                    true
                }
            }
            true
        }
        if (type != 2L) return null
        if (channels !in 1L..4096L) throw CorruptStreamException("a Matroska audio track of $channels channels", entry.offset)
        if (!(rate > 0.0) || rate > Int.MAX_VALUE) throw CorruptStreamException("a Matroska audio track at $rate Hz", entry.offset)
        return Track(number, uid, codec, private, rate, outputRate, channels.toInt(), depth, delay, stripped, zlib)
    }

    /** Reads one Tag: the track UID it targets, or null for the segment, and its SimpleTags. */
    private fun tag(reader: SourceReader, tag: Ebml.Header): Pair<Long?, List<RawTagEntry>> {
        var target: Long? = null
        val entries = ArrayList<RawTagEntry>()
        children(reader, tag) { e ->
            when (e.id) {
                MatroskaIds.TARGETS -> children(reader, e) { t ->
                    if (t.id == MatroskaIds.TAG_TRACK_UID) target = Ebml.unsigned(reader, t).takeIf { it != 0L }
                    true
                }
                MatroskaIds.SIMPLE_TAG -> simpleTag(reader, e, entries)
            }
            true
        }
        return target to entries
    }

    private fun simpleTag(reader: SourceReader, element: Ebml.Header, into: MutableList<RawTagEntry>) {
        var name: String? = null
        var value: String? = null
        children(reader, element) { e ->
            when (e.id) {
                MatroskaIds.TAG_NAME -> name = Ebml.string(reader, e, MAX_TEXT)
                MatroskaIds.TAG_STRING -> value = Ebml.string(reader, e, MAX_TEXT)
                MatroskaIds.SIMPLE_TAG -> simpleTag(reader, e, into)
            }
            true
        }
        val key = name ?: return
        if (value != null) into.add(RawTagEntry(key, text = value))
    }

    private fun readChapters(reader: SourceReader, element: Ebml.Header, into: MutableList<Chapter>) {
        var edition = 0
        children(reader, element) { entry ->
            if (entry.id == MatroskaIds.EDITION_ENTRY) {
                // The first edition is the default unless another says so; only it is read.
                if (edition++ == 0) {
                    children(reader, entry) { atom ->
                        if (atom.id == MatroskaIds.CHAPTER_ATOM) chapter(reader, atom)?.let { into.add(it) }
                        true
                    }
                }
            }
            true
        }
    }

    private fun chapter(reader: SourceReader, atom: Ebml.Header): Chapter? {
        var start = -1L
        var end: Long? = null
        var title: String? = null
        var uid = 0L
        var hidden = false
        children(reader, atom) { e ->
            when (e.id) {
                MatroskaIds.CHAPTER_UID -> uid = Ebml.unsigned(reader, e)
                MatroskaIds.CHAPTER_TIME_START -> start = Ebml.unsigned(reader, e)
                MatroskaIds.CHAPTER_TIME_END -> end = Ebml.unsigned(reader, e)
                MatroskaIds.CHAPTER_FLAG_HIDDEN -> hidden = Ebml.unsigned(reader, e) != 0L
                MatroskaIds.CHAPTER_DISPLAY -> if (title == null) {
                    children(reader, e) { d ->
                        if (d.id == MatroskaIds.CHAP_STRING) title = Ebml.string(reader, d, MAX_TEXT)
                        true
                    }
                }
            }
            true
        }
        if (start < 0 || hidden) return null
        return Chapter(start, end, title, uid)
    }

    /** Places every frame of [track] in the file, from the Clusters' Blocks. */
    private fun frames(reader: SourceReader, clusters: List<Ebml.Header>, track: Track, scale: Long, options: DecodeOptions): Frames {
        val list = FrameList(options)
        var discard = 0L
        for (cluster in clusters) {
            var base = 0L
            children(reader, cluster) { e ->
                when (e.id) {
                    MatroskaIds.TIMESTAMP -> base = Ebml.unsigned(reader, e)
                    MatroskaIds.SIMPLE_BLOCK -> block(reader, e, track, base, scale, list)
                    MatroskaIds.BLOCK_GROUP -> {
                        var mine = false
                        var padding = 0L
                        children(reader, e) { g ->
                            when (g.id) {
                                MatroskaIds.BLOCK -> mine = block(reader, g, track, base, scale, list)
                                MatroskaIds.DISCARD_PADDING -> padding = Ebml.unsigned(reader, g).let { v ->
                                    // A signed integer of the element's own width.
                                    val bits = (g.size * 8).toInt()
                                    if (bits in 1..63 && v and (1L shl (bits - 1)) != 0L) v - (1L shl bits) else v
                                }
                            }
                            true
                        }
                        if (mine) discard = padding
                    }
                }
                true
            }
        }
        return list.done(discard)
    }

    /** Adds the frames of one Block when it belongs to [track], and says whether it did. */
    private fun block(reader: SourceReader, element: Ebml.Header, track: Track, base: Long, scale: Long, list: FrameList): Boolean {
        if (element.size < 4) throw CorruptStreamException("a Matroska Block of ${element.size} bytes", element.offset)
        reader.seek(element.dataOffset)
        val head = reader.readBytes(minOf(element.size, 8L + 255L * 9).toInt())
        val (number, numberLength) = Ebml.vint(head, 0, head.size) ?: throw CorruptStreamException("a Matroska Block's track number is cut short", element.offset)
        if (number != track.number) return false
        var at = numberLength
        val relative = ((head[at].toInt() shl 8) or (head[at + 1].toInt() and 0xFF)).toShort().toLong()
        val flags = head[at + 2].toInt() and 0xFF
        at += 3
        val time = (base + relative) * scale
        val lacing = (flags ushr 1) and 3
        val dataEnd = element.size
        if (lacing == 0) {
            list.add(element.dataOffset + at, dataEnd - at, time, element.offset)
            return true
        }
        val count = (head[at].toInt() and 0xFF) + 1
        at++
        val sizes = LongArray(count)
        when (lacing) {
            1 -> for (i in 0 until count - 1) {
                // Xiph lacing: each size as a run of 255s and a final byte below 255.
                var size = 0L
                while (true) {
                    if (at >= head.size) throw CorruptStreamException("a Matroska Block's lacing is cut short", element.offset)
                    val b = head[at++].toInt() and 0xFF
                    size += b
                    if (b != 255) break
                }
                sizes[i] = size
            }
            3 -> for (i in 0 until count - 1) {
                // EBML lacing: the first size as a VINT, each after as a signed difference.
                val (raw, length) = Ebml.vint(head, at, head.size) ?: throw CorruptStreamException("a Matroska Block's lacing is cut short", element.offset)
                at += length
                sizes[i] = if (i == 0) raw else sizes[i - 1] + raw - ((1L shl (7 * length - 1)) - 1)
            }
            else -> {
                val total = dataEnd - at
                if (total % count != 0L) throw CorruptStreamException("a fixed-laced Matroska Block of $total bytes in $count frames", element.offset)
                for (i in 0 until count - 1) sizes[i] = total / count
            }
        }
        var used = 0L
        for (i in 0 until count - 1) {
            if (sizes[i] < 0) throw CorruptStreamException("a Matroska Block states a negative frame size", element.offset)
            used += sizes[i]
        }
        sizes[count - 1] = dataEnd - at - used
        if (sizes[count - 1] < 0) throw CorruptStreamException("a Matroska Block's frames are larger than the Block", element.offset)
        var offset = element.dataOffset + at
        for (size in sizes) {
            list.add(offset, size, time, element.offset)
            offset += size
        }
        return true
    }

    private class FrameList(private val options: DecodeOptions) {
        var offsets = LongArray(256)
        var sizes = IntArray(256)
        var times = LongArray(256)
        var count = 0

        fun add(offset: Long, size: Long, time: Long, at: Long) {
            if (size > Int.MAX_VALUE) throw CorruptStreamException("a Matroska frame of $size bytes", at)
            if (count == offsets.size) {
                options.checkAllocation(count * 2L * 20L, "indexing Matroska frames")
                offsets = offsets.copyOf(count * 2)
                sizes = sizes.copyOf(count * 2)
                times = times.copyOf(count * 2)
            }
            offsets[count] = offset
            sizes[count] = size.toInt()
            times[count] = time
            count++
        }

        fun done(discard: Long) = Frames(offsets.copyOf(count), sizes.copyOf(count), times.copyOf(count), discard, count)
    }
}

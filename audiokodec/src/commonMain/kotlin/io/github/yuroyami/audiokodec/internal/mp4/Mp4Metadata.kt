package io.github.yuroyami.audiokodec.internal.mp4

import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.AudioSink
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.tag.Mp4Ilst
import io.github.yuroyami.audiokodec.tag.RawTagBlock
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagChapter

/**
 * Finds and rewrites the metadata an MP4 file keeps in its `moov` box: the
 * iTunes item list at `moov/udta/meta/ilst`, and Nero's chapter list at
 * `moov/udta/chpl`.
 *
 * A rewrite replaces only the `moov` box. Every byte before and after it is
 * copied unchanged, so the audio is never touched. When the new box is
 * larger than the old one together with any free space after it, the boxes
 * that follow move, and every chunk offset in the file's tracks that points
 * past the old box moves with them. When it is not larger, the difference
 * becomes padding in a `free` box, so nothing moves at all, and the padding
 * is there for the next edit.
 *
 * Written from Apple's QuickTime File Format specification and the box
 * definitions in ISO/IEC 14496-12.
 */
internal object Mp4Metadata {

    private const val UINT32_MAX = 0xFFFF_FFFFL
    private const val COPY_CHUNK = 1 shl 16
    private const val MAX_TOP_LEVEL_BOXES = 1 shl 20

    /**
     * The file's item list, or null when it has none. A file whose boxes
     * cannot be followed has none as far as tags are concerned; the caller
     * treats a thrown [AudioKodecException] the same way.
     */
    fun readIlst(reader: SourceReader): RawTagBlock? {
        val top = topLevel(reader, if (reader.size >= 0) reader.size else Long.MAX_VALUE)
        val moov = top.singleOrNull { it.type == "moov" } ?: return null
        val body = readBody(reader, moov)
        val base = moov.bodyOffset
        val udta = Mp4Box.children(body, 0, body.size, base).firstOrNull { it.type == "udta" } ?: return null
        val meta = ItunesMeta.find(body, udta, base) ?: return null
        val ilst = meta.children.firstOrNull { it.type == "ilst" } ?: return null
        return Mp4Ilst.parse(body, ilst, base, reader.options)
    }

    /**
     * The gapless record iTunes keeps in its `iTunSMPB` freeform item: the
     * encoder delay before the recording starts, the padding after it ends,
     * and the recording's length, all in decoded frames.
     */
    class ItunesGapless(val delay: Long, val padding: Long, val frames: Long)

    /**
     * The `iTunSMPB` record in the item list of a `moov` box whose children
     * are [children], or null when there is none or it cannot be read. A
     * damaged item list costs the trim, never the audio.
     */
    fun itunesGapless(body: ByteArray, children: List<Mp4Box>, base: Long, options: DecodeOptions): ItunesGapless? = try {
        children.firstOrNull { it.type == "udta" }
            ?.let { ItunesMeta.find(body, it, base) }
            ?.children?.firstOrNull { it.type == "ilst" }
            ?.let { Mp4Ilst.parse(body, it, base, options) }
            ?.entries?.firstOrNull { it.key.equals(SMPB_KEY, ignoreCase = true) && it.text != null }
            ?.let { parseSmpb(it.text!!) }
    } catch (_: AudioKodecException) {
        null
    }

    private const val SMPB_KEY = "----:com.apple.iTunes:iTunSMPB"

    /**
     * Reads the space-separated hexadecimal fields of `iTunSMPB`: a reserved
     * word, the delay, the padding and a 64-bit frame count, followed by
     * words that are not used here.
     */
    internal fun parseSmpb(text: String): ItunesGapless? {
        val words = text.trim().split(' ', '\t').filter { it.isNotEmpty() }
        if (words.size < 4) return null
        fun hex(word: String, digits: Int): Long? {
            if (word.length > digits || word.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
            return word.toULongOrNull(16)?.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()
        }
        val delay = hex(words[1], 8) ?: return null
        val padding = hex(words[2], 8) ?: return null
        val frames = hex(words[3], 16) ?: return null
        return ItunesGapless(delay, padding, frames)
    }

    /** A rewrite worked out in full, so that a refusal comes before any output. */
    class Prepared internal constructor(
        private val reader: SourceReader,
        private val moovStart: Long,
        private val moov: Piece,
        private val topPadding: Piece?,
        private val resumeAt: Long,
        private val end: Long,
    ) {
        fun writeTo(sink: AudioSink) {
            copy(reader, sink, 0, moovStart)
            moov.writeTo(sink)
            topPadding?.writeTo(sink)
            copy(reader, sink, resumeAt, end)
        }
    }

    /**
     * Works out the file [tags] produce from [reader], up to [sourceEnd], or
     * to the end of the source when that is negative.
     *
     * [includeTags] false removes the item list. [padding] is the free space
     * left behind the items when the box has to grow.
     */
    fun prepare(
        reader: SourceReader,
        tags: TagBundle,
        includeTags: Boolean,
        padding: Int,
        sourceEnd: Long,
    ): Prepared {
        val options = reader.options
        val end = if (sourceEnd >= 0) sourceEnd else Long.MAX_VALUE
        val top = topLevel(reader, end)
        val moovs = top.filter { it.type == "moov" }
        if (moovs.size > 1) throw CorruptStreamException("the file holds more than one 'moov' box", moovs[1].offset)
        val moov = moovs.singleOrNull() ?: throw TruncatedStreamException(
            "the file has no complete 'moov' box, which is where an MP4 file keeps its tags",
        )
        val body = readBody(reader, moov)
        val base = moov.bodyOffset
        val children = Mp4Box.children(body, 0, body.size, base)

        val items = if (includeTags) Mp4Ilst.build(tags) else null
        val chapters = chapterPlan(reader, tags, body, children, base, options)
        val layout = Layout(body, children, base, items, chapters)

        // Free space directly after the box is part of what it may use.
        var spanEnd = moov.end
        val after = top.indexOf(moov) + 1
        for (box in top.subList(after, top.size)) {
            if ((box.type != "free" && box.type != "skip") || box.extendsToEnd || box.offset != spanEnd) break
            spanEnd = box.end
        }
        val oldSpan = spanEnd - moov.offset
        val core = layout.size(null)
        val left = oldSpan - core
        val paddingTotal: Long
        val delta: Long
        if (left == 0L || left in 8L..UINT32_MAX) {
            paddingTotal = left
            delta = 0L
        } else {
            paddingTotal = if (padding > 0) padding + 8L else 0L
            delta = core + paddingTotal - oldSpan
        }
        if (delta != 0L) {
            checkFragmentsCanMove(reader, top.subList(after, top.size))
            shiftChunkOffsets(body, children, base, from = spanEnd, delta = delta)
        }
        val paddingBox = if (paddingTotal > 0) Piece.free(paddingTotal) else null
        val (moovPiece, topPadding) = layout.build(paddingBox)
        return Prepared(reader, moov.offset, moovPiece, topPadding, spanEnd, end)
    }

    // The metadata box.

    /** The `meta` box of a `udta` box that holds iTunes metadata. */
    private class ItunesMeta(
        val box: Mp4Box,
        /** ISO `meta` is a full box; QuickTime's original form has no version and flags. */
        val prefix: Int,
        val children: List<Mp4Box>,
    ) {
        companion object {
            /** The first `meta` whose handler is `mdir`, or which names none. */
            fun find(body: ByteArray, udta: Mp4Box, base: Long): ItunesMeta? {
                for (meta in Mp4Box.children(body, udta.bodyOffset.toInt(), udta.end.toInt(), base)) {
                    if (meta.type != "meta") continue
                    val start = meta.bodyOffset.toInt()
                    val prefix = if (meta.bodySize >= 8 && Mp4Box.typeOf(body, start + 4) == "hdlr") 0 else 4
                    if (meta.bodySize < prefix) continue
                    val children = Mp4Box.children(body, start + prefix, meta.end.toInt(), base)
                    val hdlr = children.firstOrNull { it.type == "hdlr" }
                    if (hdlr != null) {
                        if (hdlr.bodySize < 12) throw CorruptStreamException("a metadata 'hdlr' box is cut short", base + hdlr.offset)
                        if (Mp4Box.typeOf(body, hdlr.bodyOffset.toInt() + 8) != "mdir") continue
                    }
                    return ItunesMeta(meta, prefix, children)
                }
                return null
            }
        }
    }

    /** What happens to the chapter list. */
    private sealed class ChapterPlan {
        object Keep : ChapterPlan()
        class Replace(val chpl: ByteArray?) : ChapterPlan()
    }

    /**
     * Chapters the file already holds, unchanged, stay exactly as stored,
     * in a chapter track or a `chpl` box. Changed chapters are written as a
     * `chpl` box, which stores a start and a title for each; a file whose
     * chapters are in a chapter track keeps them there, so changing them is
     * refused rather than leaving two disagreeing lists.
     */
    private fun chapterPlan(
        reader: SourceReader,
        tags: TagBundle,
        body: ByteArray,
        children: List<Mp4Box>,
        base: Long,
        options: DecodeOptions,
    ): ChapterPlan {
        val stored = Mp4Container.chapters(reader, options)
        val unchanged = stored.size == tags.chapters.size && stored.indices.all { i ->
            val a = stored[i]
            val b = tags.chapters[i]
            a.startMillis == b.startMillis && a.endMillis == b.endMillis && a.title == b.title && b.pictures.isEmpty()
        }
        if (unchanged) return ChapterPlan.Keep
        val chapterTrack = children.any { trak ->
            trak.type == "trak" && Mp4Box.children(body, trak.bodyOffset.toInt(), trak.end.toInt(), base).any { tref ->
                tref.type == "tref" && Mp4Box.children(body, tref.bodyOffset.toInt(), tref.end.toInt(), base).any { it.type == "chap" }
            }
        }
        if (chapterTrack) {
            throw UnsupportedFeatureException(
                "changing the chapters of an MP4 chapter track",
                "this file keeps its chapters in a QuickTime chapter track, which AudioKodec reads but does not rewrite; " +
                    "keep the chapters as they were read",
            )
        }
        return ChapterPlan.Replace(if (tags.chapters.isEmpty()) null else neroChapters(tags.chapters))
    }

    /**
     * Nero's `chpl` box: version 1, a reserved word, a one-byte count, and
     * for each chapter its start in units of 100 nanoseconds and a title of
     * up to 255 bytes. Each chapter ends where the next one starts, and the
     * last one at the end of the file, so an end that says otherwise cannot
     * be stored.
     */
    fun neroChapters(chapters: List<TagChapter>): ByteArray {
        fun refuse(detail: String): Nothing =
            throw UnsupportedFeatureException("these chapters in an MP4 'chpl' box", detail)
        if (chapters.size > 255) refuse("a 'chpl' box holds at most 255 chapters, and there are ${chapters.size}")
        val titles = chapters.map { (it.title ?: "").encodeToByteArray() }
        for ((i, chapter) in chapters.withIndex()) {
            if (chapter.pictures.isNotEmpty()) refuse("chapter ${chapter.id} has artwork, which a 'chpl' box cannot hold")
            if (titles[i].size > 255) refuse("chapter ${chapter.id} has a title of ${titles[i].size} bytes; the limit is 255")
            if (chapter.startMillis > Long.MAX_VALUE / 10_000) refuse("chapter ${chapter.id} starts too late to state")
            val next = chapters.getOrNull(i + 1) ?: continue
            if (next.startMillis < chapter.startMillis) refuse("the chapters are not in the order they start")
            if (chapter.endMillis != -1L && chapter.endMillis != next.startMillis) {
                refuse("chapter ${chapter.id} ends at ${chapter.endMillis} ms, but the next one starts at ${next.startMillis} ms")
            }
        }
        var size = 8 + 4 + 4 + 1
        for (title in titles) size += 9 + title.size
        val out = ByteArray(size)
        writeU32(out, 0, size.toLong())
        "chpl".forEachIndexed { k, c -> out[4 + k] = c.code.toByte() }
        out[8] = 1
        out[16] = chapters.size.toByte()
        var at = 17
        for ((i, chapter) in chapters.withIndex()) {
            val start = chapter.startMillis * 10_000
            writeU32(out, at, start ushr 32)
            writeU32(out, at + 4, start and UINT32_MAX)
            out[at + 8] = titles[i].size.toByte()
            titles[i].copyInto(out, at + 9)
            at += 9 + titles[i].size
        }
        return out
    }

    /**
     * The new `moov` box, built around [body] with the item list and chapter
     * list replaced. Everything else in the box, including the order of what
     * it holds, is carried over unchanged.
     */
    private class Layout(
        private val body: ByteArray,
        private val children: List<Mp4Box>,
        private val base: Long,
        private val items: ByteArray?,
        private val chapters: ChapterPlan,
    ) {
        private val udta = children.firstOrNull { it.type == "udta" }
        private val udtaChildren = udta?.let { Mp4Box.children(body, it.bodyOffset.toInt(), it.end.toInt(), base) }.orEmpty()
        private val meta = udta?.let { ItunesMeta.find(body, it, base) }

        fun size(padding: Piece?): Long {
            val (moov, top) = build(padding)
            return moov.size + (top?.size ?: 0L)
        }

        /** The box, and the padding to place after it when there is no `meta` to hold it. */
        fun build(padding: Piece?): Pair<Piece, Piece?> {
            var unplaced = padding
            val ilst = items?.let { Piece.Box("ilst", listOf(Piece.Bytes(it))) }

            // The meta box: its handler and other contents stay; the items and
            // any padding are replaced.
            val metaPiece: Piece? = if (meta != null) {
                val parts = ArrayList<Piece>()
                var placed = false
                var holdsOther = false
                for (child in meta.children) {
                    when (child.type) {
                        "ilst" -> if (!placed) {
                            placed = true
                            if (ilst != null) parts += ilst
                        }
                        "free", "skip" -> Unit
                        else -> {
                            if (child.type != "hdlr") holdsOther = true
                            parts += raw(child)
                        }
                    }
                }
                if (!placed && ilst != null) parts += ilst
                if (ilst != null || holdsOther) {
                    val insertAt = if (ilst != null) parts.indexOf(ilst) + 1 else parts.size
                    unplaced?.let { parts.add(insertAt, it) }
                    unplaced = null
                    val prefix = Piece.Bytes(body, meta.box.bodyOffset.toInt(), meta.box.bodyOffset.toInt() + meta.prefix)
                    Piece.Box("meta", listOf(prefix) + parts)
                } else {
                    null
                }
            } else if (ilst != null) {
                val parts = mutableListOf(Piece.Bytes(ByteArray(4)), Piece.Bytes(ITUNES_HANDLER), ilst)
                unplaced?.let { parts += it }
                unplaced = null
                Piece.Box("meta", parts)
            } else {
                null
            }
            val chpl = (chapters as? ChapterPlan.Replace)?.chpl?.let { Piece.Bytes(it) }

            // The udta box around it.
            val udtaParts = ArrayList<Piece>()
            var metaPlaced = false
            var chplPlaced = false
            for (child in udtaChildren) {
                when {
                    meta != null && child.offset == meta.box.offset -> {
                        metaPlaced = true
                        metaPiece?.let { udtaParts += it }
                    }
                    child.type == "chpl" && chapters is ChapterPlan.Replace -> if (!chplPlaced) {
                        chplPlaced = true
                        chpl?.let { udtaParts += it }
                    }
                    else -> udtaParts += raw(child)
                }
            }
            if (!metaPlaced) metaPiece?.let { udtaParts += it }
            if (!chplPlaced) chpl?.let { udtaParts += it }

            val moovParts = ArrayList<Piece>()
            var udtaPlaced = false
            for (child in children) {
                if (udta != null && child.offset == udta.offset) {
                    udtaPlaced = true
                    if (udtaParts.isNotEmpty()) moovParts += Piece.Box("udta", udtaParts)
                } else {
                    moovParts += raw(child)
                }
            }
            if (!udtaPlaced && udtaParts.isNotEmpty()) moovParts += Piece.Box("udta", udtaParts)
            return Piece.Box("moov", moovParts) to unplaced
        }

        private fun raw(box: Mp4Box): Piece = Piece.Bytes(body, box.offset.toInt(), box.end.toInt())
    }

    /**
     * The `hdlr` body iTunes gives its metadata: no predefined value, the
     * `mdir` handler, Apple as the manufacturer, and an empty name.
     */
    private val ITUNES_HANDLER: ByteArray = ByteArray(33).also { box ->
        writeU32(box, 0, 33)
        "hdlr".forEachIndexed { k, c -> box[4 + k] = c.code.toByte() }
        "mdir".forEachIndexed { k, c -> box[16 + k] = c.code.toByte() }
        "appl".forEachIndexed { k, c -> box[20 + k] = c.code.toByte() }
    }

    /** Part of the output: bytes, a run of zeros, or a box around more parts. */
    sealed class Piece {
        abstract val size: Long
        abstract fun writeTo(sink: AudioSink)

        class Bytes(val bytes: ByteArray, val from: Int = 0, val to: Int = bytes.size) : Piece() {
            override val size: Long get() = (to - from).toLong()
            override fun writeTo(sink: AudioSink) = sink.write(bytes, from, to - from)
        }

        class Zeros(override val size: Long) : Piece() {
            override fun writeTo(sink: AudioSink) {
                val zeros = ByteArray(minOf(size, COPY_CHUNK.toLong()).toInt())
                var left = size
                while (left > 0) {
                    val count = minOf(left, zeros.size.toLong()).toInt()
                    sink.write(zeros, 0, count)
                    left -= count
                }
            }
        }

        /** A box whose size field is 32 bits when it fits, and 64 when it does not. */
        class Box(val type: String, val parts: List<Piece>, val large: Boolean? = null) : Piece() {
            private val content: Long = parts.fold(0L) { total, part -> total + part.size }
            private val wide: Boolean = large ?: (content + 8 > UINT32_MAX)
            override val size: Long = content + if (wide) 16 else 8

            override fun writeTo(sink: AudioSink) {
                val header = ByteArray(if (wide) 16 else 8)
                if (wide) {
                    writeU32(header, 0, 1)
                    writeU32(header, 8, size ushr 32)
                    writeU32(header, 12, size and UINT32_MAX)
                } else {
                    writeU32(header, 0, size)
                }
                type.forEachIndexed { k, c -> header[4 + k] = c.code.toByte() }
                sink.write(header)
                for (part in parts) part.writeTo(sink)
            }
        }

        companion object {
            /** A `free` box of exactly [total] bytes, which is at least 8 and fits 32 bits. */
            fun free(total: Long): Piece = Box("free", listOf(Zeros(total - 8)), large = false)
        }
    }

    // Moving what follows.

    /**
     * Adds [delta] to every chunk offset at or past [from] in every track,
     * and to the auxiliary information offsets of encrypted tracks, which
     * are file positions in the same way. The tables keep their widths.
     */
    private fun shiftChunkOffsets(body: ByteArray, children: List<Mp4Box>, base: Long, from: Long, delta: Long) {
        fun inside(parent: Mp4Box, type: String): Mp4Box? =
            Mp4Box.children(body, parent.bodyOffset.toInt(), parent.end.toInt(), base).firstOrNull { it.type == type }
        for (trak in children) {
            if (trak.type != "trak") continue
            val stbl = inside(trak, "mdia")?.let { inside(it, "minf") }?.let { inside(it, "stbl") } ?: continue
            for (table in Mp4Box.children(body, stbl.bodyOffset.toInt(), stbl.end.toInt(), base)) {
                when (table.type) {
                    "stco" -> shiftTable(body, table, base, skip = 0, wide = false, from, delta)
                    "co64" -> shiftTable(body, table, base, skip = 0, wide = true, from, delta)
                    "saio" -> {
                        if (table.bodySize < 4) throw CorruptStreamException("the 'saio' box is cut short", base + table.offset)
                        val at = table.bodyOffset.toInt()
                        val version = body[at].toInt() and 0xFF
                        val flags = Mp4Box.readU32(body, at) and 0xFF_FFFF
                        shiftTable(body, table, base, skip = if (flags and 1L != 0L) 8 else 0, wide = version != 0, from, delta)
                    }
                }
            }
        }
    }

    private fun shiftTable(body: ByteArray, table: Mp4Box, base: Long, skip: Int, wide: Boolean, from: Long, delta: Long) {
        val cursor = Mp4Cursor(body, table.bodyOffset.toInt(), table.end.toInt(), base, "the '${table.type}' box")
        cursor.skip(4 + skip)
        val count = cursor.count()
        val width = if (wide) 8 else 4
        cursor.requireEntries(count, width)
        var at = cursor.position
        repeat(count) {
            val value = if (wide) Mp4Box.readU64(body, at) else Mp4Box.readU32(body, at)
            if (value >= from) {
                val moved = value + delta
                if (wide) {
                    if (delta > 0 && moved < value) throw CorruptStreamException("a chunk offset overflows 64 bits when moved", base + at)
                    writeU32(body, at, moved ushr 32)
                    writeU32(body, at + 4, moved and UINT32_MAX)
                } else {
                    if (moved !in 0..UINT32_MAX) {
                        throw UnsupportedFeatureException(
                            "moving MP4 audio past the reach of 32-bit chunk offsets",
                            "the tags would move the audio past 4 GiB, which this file's 32-bit chunk offset table cannot address",
                        )
                    }
                    writeU32(body, at, moved)
                }
            }
            at += width
        }
    }

    /**
     * A fragment that states an absolute position for its data, or a random
     * access index of fragment positions, would be left pointing at the old
     * place. Fragments that count from their own `moof` box move intact.
     */
    private fun checkFragmentsCanMove(reader: SourceReader, following: List<Mp4Box>) {
        fun refuse(detail: String): Nothing = throw UnsupportedFeatureException(
            "growing the metadata of this fragmented MP4 file",
            "$detail, so the tags must fit the space the file already reserves; write fewer or smaller values",
        )
        for (box in following) {
            when (box.type) {
                "mfra" -> refuse("its fragment index records absolute positions")
                "moof" -> {
                    val body = readBody(reader, box)
                    val base = box.bodyOffset
                    for (traf in Mp4Box.children(body, 0, body.size, base)) {
                        if (traf.type != "traf") continue
                        val tfhd = Mp4Box.children(body, traf.bodyOffset.toInt(), traf.end.toInt(), base)
                            .firstOrNull { it.type == "tfhd" } ?: continue
                        if (tfhd.bodySize < 4) throw CorruptStreamException("the 'tfhd' box is cut short", base + tfhd.offset)
                        if (Mp4Box.readU32(body, tfhd.bodyOffset.toInt()) and 1L != 0L) {
                            refuse("a fragment states the absolute position of its data")
                        }
                    }
                }
            }
        }
    }

    // Reading boxes.

    /**
     * The top-level boxes up to [end], stopping at the first one that does
     * not fit, which is how trailing data that is not part of the file ends
     * the walk without failing it.
     */
    private fun topLevel(reader: SourceReader, end: Long): List<Mp4Box> {
        val boxes = ArrayList<Mp4Box>()
        val header = ByteArray(16)
        var at = 0L
        while (end - at >= 8) {
            reader.seek(at)
            val got = reader.readSome(header, 0, 16)
            if (got < 8) break
            val small = Mp4Box.readU32(header, 0)
            var headerBytes = 8
            val size = when (small) {
                1L -> {
                    if (got < 16) break
                    headerBytes = 16
                    Mp4Box.readU64(header, 8)
                }
                0L -> if (reader.size >= 0) minOf(end, reader.size) - at else end - at
                else -> small
            }
            if (size < headerBytes || size > end - at) break
            boxes += Mp4Box(Mp4Box.typeOf(header, 4), at, headerBytes, size, extendsToEnd = small == 0L)
            if (boxes.size >= MAX_TOP_LEVEL_BOXES) break
            at += size
        }
        return boxes
    }

    private fun readBody(reader: SourceReader, box: Mp4Box): ByteArray {
        if (box.bodySize > Int.MAX_VALUE) {
            throw CorruptStreamException("the '${box.type}' box is ${box.size} bytes, too large to hold in memory", box.offset)
        }
        reader.options.checkAllocation(box.bodySize, "reading the '${box.type}' box")
        val bytes = ByteArray(box.bodySize.toInt())
        reader.seek(box.bodyOffset)
        val got = reader.readSome(bytes, 0, bytes.size)
        if (got < bytes.size) {
            throw TruncatedStreamException("the '${box.type}' box is cut off after $got of ${bytes.size} bytes", box.bodyOffset + got)
        }
        return bytes
    }

    private fun copy(reader: SourceReader, sink: AudioSink, from: Long, to: Long) {
        if (to <= from) return
        val buffer = ByteArray(COPY_CHUNK)
        reader.seek(from)
        var left = to - from
        while (left > 0) {
            val want = if (left < COPY_CHUNK) left.toInt() else COPY_CHUNK
            val got = reader.readSome(buffer, 0, want)
            if (got <= 0) {
                if (to != Long.MAX_VALUE) {
                    throw TruncatedStreamException("the source ended with $left bytes still to copy", reader.position)
                }
                break
            }
            sink.write(buffer, 0, got)
            left -= got
        }
    }

    private fun writeU32(into: ByteArray, at: Int, value: Long) {
        into[at] = (value shr 24).toByte()
        into[at + 1] = (value shr 16).toByte()
        into[at + 2] = (value shr 8).toByte()
        into[at + 3] = value.toByte()
    }
}

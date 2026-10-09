package io.github.yuroyami.audiokodec.internal.mp4

import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.tag.TagChapter

/**
 * Reads the structure of an MP4 file: which track carries the audio, how it
 * is coded, and where every one of its packets is.
 *
 * An MP4 file keeps its audio in one or more `mdat` boxes and describes it in
 * a single `moov` box, which may come before the audio or after it. The
 * description of a track is a set of tables, and the position, size and
 * duration of a packet are each in a different one: sizes in `stsz`, chunks of
 * consecutive packets in `stco` with their packet counts in `stsc`, and
 * durations in `stts`. A fragmented file adds more packets after the `moov`,
 * each run described by a `moof` box of its own.
 *
 * The edit list of a track says which part of its media is played. For audio
 * that is how an encoder's priming samples are left out, so it is applied.
 *
 * Written from Apple's QuickTime File Format specification and the fragment
 * definitions in ISO/IEC 14496-12.
 */
internal object Mp4Reader {

    /** What an audio sample entry in `stsd` says. */
    class SampleEntry(
        /** The entry's four-character code, which names the coding. */
        val format: String,
        val channels: Int,
        /** Sample rate, from the entry, or from an `srat` box for rates past 65535. */
        val sampleRate: Int,
        /** Bits per sample as the entry states them. */
        val sampleSize: Int,
        /** The QuickTime sound description version: 0, 1 or 2. */
        val version: Int,
        /** Version 1 and 2: bytes in one frame of every channel, or 0. */
        val bytesPerFrame: Int,
        /** Version 2: the format flags of an `lpcm` entry. */
        val formatFlags: Int,
        /** Version 2: bits per channel of an `lpcm` entry. */
        val bitsPerChannel: Int,
        /** Child boxes that configure the coding, by type, with their bodies, found also inside `wave`. */
        val boxes: Map<String, ByteArray>,
        /** Where the entry starts in the file. */
        val offset: Long,
        /** One-based entry in the track's data-reference table. */
        val dataReferenceIndex: Int,
    )

    /** The audio track. */
    class AudioTrack(
        val trackId: Int,
        val entry: SampleEntry,
        /** Units per second of the track's media times. */
        val timescale: Long,
        /** Offset of each packet in the file. */
        val offsets: LongArray,
        val sizes: IntArray,
        /** Media time each packet starts at, with one more entry for the end. */
        val times: LongArray,
        /** Media time playback starts at: the edit list's start, or 0. */
        val editStart: Long,
        /** Media time playback lasts, from the edit list, or null to play to the end. */
        val editDuration: Long?,
    )

    class Mp4File(
        /** The `ftyp` box's major brand, or null for a file without one. */
        val brand: String?,
        val audio: AudioTrack,
        val chapters: List<TagChapter>,
        /** Bytes of the packets of the audio track, for a measured bitrate. */
        val audioBytes: Long,
        /** Where the `moov` box is. */
        val moov: Mp4Box,
        val fragmented: Boolean,
        /** The gapless record iTunes stores among the tags, or null. */
        val itunesGapless: Mp4Metadata.ItunesGapless? = null,
    )

    fun parse(reader: SourceReader, options: DecodeOptions): Mp4File {
        val top = topLevelBoxes(reader, options)
        val mediaData = MediaData(top, reader, options)
        val moov = top.singleOrNull { it.type == "moov" } ?: run {
            if (top.count { it.type == "moov" } > 1) throw CorruptStreamException("the file holds more than one 'moov' box")
            throw TruncatedStreamException(
                "the file has no 'moov' box, which is where an MP4 file describes its audio. " +
                    "A recording cut off before it finished is the usual cause",
            )
        }
        val brand = top.firstOrNull { it.type == "ftyp" }?.let { box ->
            readBody(reader, box, options).takeIf { it.size >= 4 }?.let { Mp4Box.typeOf(it, 0) }
        }
        val body = readBody(reader, moov, options)
        val base = moov.bodyOffset
        val children = Mp4Box.children(body, 0, body.size, base)

        val movieTimescale = children.firstOrNull { it.type == "mvhd" }?.let { box ->
            val cursor = cursorOf(body, box, base, "the 'mvhd' box")
            val version = cursor.u8()
            cursor.skip(3)
            if (version == 1) cursor.skip(16) else cursor.skip(8)
            cursor.u32().also { timescale ->
                if (timescale == 0L) throw CorruptStreamException("the 'mvhd' box declares a zero movie timescale", base + box.offset)
            }
        } ?: throw CorruptStreamException("the 'moov' box has no 'mvhd' header", moov.offset)

        val tracks = children.filter { it.type == "trak" }.map { readTrack(body, it, base, movieTimescale, options) }
        val trex = children.firstOrNull { it.type == "mvex" }?.let { mvex ->
            Mp4Box.children(body, mvex.bodyOffset.toInt(), mvex.end.toInt(), base)
                .filter { it.type == "trex" }
                .map { readTrex(body, it, base) }
        }.orEmpty()
        val fragmented = trex.isNotEmpty()

        val audioTracks = tracks.filter { it.handler == "soun" && it.entry != null }
        val chosen = audioTracks.firstOrNull { it.enabled } ?: audioTracks.firstOrNull()
            ?: throw UnsupportedFeatureException(
                "an MP4 file without audio",
                "this MP4 file has no sound track: it holds ${tracks.map { it.handler }.distinct().joinToString(", ").ifEmpty { "no tracks" }}",
            )
        val entry = chosen.entry!!
        checkDataReference(body, chosen, base)
        if (isFramewisePcm(entry)) {
            Mp4Codecs.checkShape(entry, options, entry.sampleRate, entry.channels)
        }
        val samples = SampleList(options, coalesce = isFramewisePcm(entry))
        expandTables(body, chosen.stbl, samples, base, if (isFramewisePcm(entry)) Mp4Codecs.pcmFrameBytes(entry) else null, options)
        if (fragmented) {
            val defaults = trex.associateBy { it.trackId }
            if (defaults.size != trex.size) throw CorruptStreamException("the 'trex' boxes repeat a track's defaults", moov.offset)
            if (chosen.id !in defaults) throw CorruptStreamException("the fragmented audio track has no 'trex' defaults", moov.offset)
            for (moof in top.filter { it.type == "moof" }) {
                readFragment(reader, moof, chosen.id, defaults, samples, options)
            }
        }
        samples.checkRanges(mediaData)

        val chapterTracks = chosen.chapterTrackIds.mapNotNull { id -> tracks.firstOrNull { it.id == id } }
        val chapters = try {
            chapterTracks.firstOrNull()?.let { readChapterTrack(reader, it, body, base, options, mediaData) }
                ?: children.firstOrNull { it.type == "udta" }?.let { readNeroChapters(body, it, base) }
                ?: emptyList()
        } catch (failure: AudioKodecException) {
            // Chapters are a nicety; damage to them must not stop the audio.
            if (!options.tolerant) throw failure
            emptyList()
        }

        val track = AudioTrack(
            trackId = chosen.id,
            entry = entry,
            timescale = chosen.timescale,
            offsets = samples.offsets(),
            sizes = samples.sizes(),
            times = samples.times(),
            editStart = chosen.edit?.start ?: 0L,
            editDuration = chosen.edit?.duration,
        )
        val gapless = Mp4Metadata.itunesGapless(body, children, base, options)
        return Mp4File(brand, track, chapters, samples.totalBytes, moov, fragmented, gapless)
    }

    /**
     * Uncompressed QuickTime audio describes one frame per table entry, which
     * would make a packet of every frame; runs of them that sit together in
     * the file are read as one.
     */
    private fun isFramewisePcm(entry: SampleEntry): Boolean =
        entry.format in setOf("lpcm", "sowt", "twos", "raw ", "NONE", "in24", "in32", "fl32", "fl64", "ulaw", "alaw", "ipcm", "fpcm")

    // The file's top level.

    private fun topLevelBoxes(reader: SourceReader, options: DecodeOptions): List<Mp4Box> {
        val boxes = ArrayList<Mp4Box>()
        val size = reader.size
        val header = ByteArray(16)
        var at = 0L
        while (true) {
            reader.seek(at)
            val got = reader.readSome(header, 0, 16)
            if (got < 8) break
            val small = Mp4Box.readU32(header, 0)
            val type = Mp4Box.typeOf(header, 4)
            var headerBytes = 8
            val boxSize = when (small) {
                1L -> {
                    if (got < 16) throw TruncatedStreamException("the box '$type' is cut off inside its 64-bit size", at)
                    headerBytes = 16
                    Mp4Box.readU64(header, 8)
                }
                0L -> if (size >= 0) size - at else Long.MAX_VALUE - at
                else -> small
            }
            if (boxSize < headerBytes) {
                if (options.tolerant && boxes.any { it.type == "moov" }) break
                throw CorruptStreamException("the box '$type' states $boxSize bytes, less than its own header", at)
            }
            if (boxSize > Long.MAX_VALUE - at) {
                throw CorruptStreamException("the box '$type' overflows 64-bit file offsets", at)
            }
            boxes += Mp4Box(type, at, headerBytes, boxSize, extendsToEnd = small == 0L)
            if (boxes.size > MAX_TOP_LEVEL_BOXES) {
                throw CorruptStreamException("the file holds more than $MAX_TOP_LEVEL_BOXES top-level boxes", at)
            }
            if (size >= 0 && boxSize > size - at) {
                // An 'mdat' cut short is a cut-off recording whose index is
                // elsewhere; the packets themselves say how much is missing.
                // Anything else cut short is a box that cannot be read.
                if (type != "mdat" && !options.tolerant) {
                    throw TruncatedStreamException("the box '$type' states $boxSize bytes and the file ends first", at)
                }
                break
            }
            if (at > Long.MAX_VALUE - boxSize) break
            at += boxSize
        }
        if (boxes.isEmpty()) throw TruncatedStreamException("the file is too short to hold an MP4 box", 0)
        return boxes
    }

    private fun readBody(reader: SourceReader, box: Mp4Box, options: DecodeOptions): ByteArray {
        if (box.bodySize > Int.MAX_VALUE) {
            throw CorruptStreamException("the '${box.type}' box is ${box.size} bytes, too large to hold in memory", box.offset)
        }
        options.checkAllocation(box.bodySize, "reading the '${box.type}' box")
        val bytes = ByteArray(box.bodySize.toInt())
        reader.seek(box.bodyOffset)
        val got = reader.readSome(bytes, 0, bytes.size)
        if (got < bytes.size) {
            throw TruncatedStreamException("the '${box.type}' box is cut off after $got of ${bytes.size} bytes", box.bodyOffset + got)
        }
        return bytes
    }

    private fun cursorOf(body: ByteArray, box: Mp4Box, base: Long, what: String): Mp4Cursor =
        Mp4Cursor(body, box.bodyOffset.toInt(), box.end.toInt(), base, what)

    private fun child(body: ByteArray, parent: Mp4Box, type: String, base: Long): Mp4Box? =
        Mp4Box.children(body, parent.bodyOffset.toInt(), parent.end.toInt(), base).firstOrNull { it.type == type }

    // Tracks.

    private class Edit(val start: Long, val duration: Long?)

    private class Track(
        val id: Int,
        val enabled: Boolean,
        val handler: String,
        val timescale: Long,
        val entry: SampleEntry?,
        val edit: Edit?,
        val chapterTrackIds: List<Int>,
        val stbl: Mp4Box?,
        val dataReferences: Mp4Box?,
    )

    private fun readTrack(body: ByteArray, trak: Mp4Box, base: Long, movieTimescale: Long, options: DecodeOptions): Track {
        val tkhd = child(body, trak, "tkhd", base) ?: throw CorruptStreamException("a track has no 'tkhd' header", base + trak.offset)
        val header = cursorOf(body, tkhd, base, "the 'tkhd' box")
        val version = header.u8()
        val flags = header.u24()
        header.skip(if (version == 1) 16 else 8)
        val id = header.i32()

        val mdia = child(body, trak, "mdia", base) ?: throw CorruptStreamException("track $id has no 'mdia' box", base + trak.offset)
        val mdhd = child(body, mdia, "mdhd", base) ?: throw CorruptStreamException("track $id has no 'mdhd' header", base + mdia.offset)
        val media = cursorOf(body, mdhd, base, "the 'mdhd' box")
        val mediaVersion = media.u8()
        media.skip(3)
        media.skip(if (mediaVersion == 1) 16 else 8)
        val timescale = media.u32()
        if (timescale == 0L) throw CorruptStreamException("track $id counts time in units of zero per second", base + mdhd.offset)

        val handler = child(body, mdia, "hdlr", base)?.let {
            val cursor = cursorOf(body, it, base, "the 'hdlr' box")
            cursor.skip(8)
            cursor.fourCc()
        } ?: ""
        val minf = child(body, mdia, "minf", base)
        val stbl = minf?.let { child(body, it, "stbl", base) }
        val dataReferences = minf?.let { child(body, it, "dinf", base) }?.let { child(body, it, "dref", base) }
        val entry = if (handler == "soun" && stbl != null) {
            child(body, stbl, "stsd", base)?.let { readSampleEntry(body, it, base, id) }
        } else {
            null
        }
        val edit = child(body, trak, "edts", base)?.let { edts ->
            child(body, edts, "elst", base)?.let { readEditList(body, it, base, movieTimescale, timescale, options) }
        }
        val chapters = child(body, trak, "tref", base)?.let { tref ->
            child(body, tref, "chap", base)?.let { chap ->
                val cursor = cursorOf(body, chap, base, "the 'chap' reference")
                List(cursor.remaining / 4) { cursor.i32() }
            }
        }.orEmpty()
        return Track(id, flags and 1 != 0, handler, timescale, entry, edit, chapters, stbl, dataReferences)
    }

    private fun readSampleEntry(body: ByteArray, stsd: Mp4Box, base: Long, trackId: Int): SampleEntry? {
        val cursor = cursorOf(body, stsd, base, "the 'stsd' box")
        cursor.skip(4)
        val count = cursor.count()
        if (count < 1) throw CorruptStreamException("track $trackId describes no sample format", base + stsd.offset)
        val first = Mp4Box.at(body, cursor.position, stsd.end.toInt(), base) ?: return null
        // A track whose packets use more than one description is a stream
        // that changes format part way, which a decoder cannot follow.
        if (count > 1) {
            throw UnsupportedFeatureException(
                "an MP4 audio track with several sample descriptions",
                "track $trackId changes format part way, which AudioKodec cannot decode",
            )
        }
        val entry = cursorOf(body, first, base, "the '${first.type}' sample entry")
        entry.skip(6)
        val dataReferenceIndex = entry.u16()
        val version = entry.u16()
        entry.skip(6)
        var channels = entry.u16()
        val sampleSize = entry.u16()
        entry.skip(4)
        var sampleRate = (entry.u32() ushr 16).toInt()
        var bytesPerFrame = 0
        var formatFlags = 0
        var bitsPerChannel = 0
        when (version) {
            1 -> {
                entry.skip(8)
                bytesPerFrame = entry.i32()
                entry.skip(4)
            }
            2 -> {
                entry.skip(4)
                val rate = Double.fromBits(entry.u64())
                channels = entry.i32()
                entry.skip(4)
                bitsPerChannel = entry.i32()
                formatFlags = entry.i32()
                bytesPerFrame = entry.i32()
                entry.skip(4)
                if (rate.isNaN() || rate < 1 || rate > Int.MAX_VALUE) {
                    throw CorruptStreamException("track $trackId states a sample rate of $rate", base + first.offset)
                }
                sampleRate = rate.toInt()
            }
        }
        val boxes = LinkedHashMap<String, ByteArray>()
        collectConfiguration(body, entry.position, first.end.toInt(), base, boxes, depth = 0)
        boxes["srat"]?.let { srat ->
            if (srat.size >= 8) sampleRate = Mp4Box.readU32(srat, 4).toInt()
        }
        return SampleEntry(
            format = first.type,
            channels = channels,
            sampleRate = sampleRate,
            sampleSize = sampleSize,
            version = version,
            bytesPerFrame = bytesPerFrame,
            formatFlags = formatFlags,
            bitsPerChannel = bitsPerChannel,
            boxes = boxes,
            offset = base + first.offset,
            dataReferenceIndex = dataReferenceIndex,
        )
    }

    /** Only the selected self-contained data reference belongs to this source. */
    private fun checkDataReference(body: ByteArray, track: Track, base: Long) {
        val entry = track.entry ?: return
        val index = entry.dataReferenceIndex
        if (index < 1) throw CorruptStreamException("the audio sample entry names data reference $index", entry.offset)
        val dref = track.dataReferences
        if (dref == null) {
            // Retain legacy self-contained files which omit this table. Their
            // packet ranges must still belong to this source's media data.
            if (index != 1) throw CorruptStreamException("the audio sample entry names data reference $index without a 'dref' table", entry.offset)
            return
        }
        val cursor = cursorOf(body, dref, base, "the 'dref' box")
        cursor.skip(4)
        val count = cursor.count()
        cursor.requireEntries(count, 12)
        if (index > count) throw CorruptStreamException("the audio sample entry names data reference $index but 'dref' holds $count", entry.offset)
        var at = cursor.position
        for (i in 1..index) {
            val reference = Mp4Box.at(body, at, dref.end.toInt(), base)
                ?: throw CorruptStreamException("the 'dref' box ends before reference $i", base + at)
            at = reference.end.toInt()
            if (i != index) continue
            val fields = cursorOf(body, reference, base, "data reference $index")
            val version = fields.u8()
            val flags = fields.u24()
            if (version != 0) throw UnsupportedFeatureException("MP4 data reference version $version")
            if (flags and 0xFFFFFE != 0) throw CorruptStreamException("data reference $index sets reserved flags", base + reference.offset)
            if (flags and 1 == 0) {
                throw UnsupportedFeatureException("external MP4 data references",
                    "this audio track uses an external data reference outside its MP4 source")
            }
            if (reference.type != "url " && reference.type != "alis") {
                throw UnsupportedFeatureException("MP4 data reference '${reference.type}'",
                    "this MP4 data reference does not describe supported local media data")
            }
        }
    }

    /**
     * Gathers the boxes that configure a coding. QuickTime nests them in a
     * `wave` box, and inside that sometimes in a box named after the coding,
     * so those are searched too; the first of each type wins.
     */
    private fun collectConfiguration(body: ByteArray, from: Int, to: Int, base: Long, into: MutableMap<String, ByteArray>, depth: Int) {
        if (depth > 3) return
        val boxes = try {
            Mp4Box.children(body, from, to, base)
        } catch (failure: CorruptStreamException) {
            if (depth == 0) throw failure
            return
        }
        for (box in boxes) {
            if (box.type !in into) into[box.type] = body.copyOfRange(box.bodyOffset.toInt(), box.end.toInt())
            when (box.type) {
                "wave" -> collectConfiguration(body, box.bodyOffset.toInt(), box.end.toInt(), base, into, depth + 1)
                "mp4a" -> if (box.bodySize > 28) collectConfiguration(body, box.bodyOffset.toInt() + 28, box.end.toInt(), base, into, depth + 1)
            }
        }
    }

    private fun readEditList(
        body: ByteArray,
        elst: Mp4Box,
        base: Long,
        movieTimescale: Long,
        mediaTimescale: Long,
        options: DecodeOptions,
    ): Edit? {
        val cursor = cursorOf(body, elst, base, "the 'elst' box")
        val version = cursor.u8()
        cursor.skip(3)
        val count = cursor.count()
        cursor.requireEntries(count, if (version == 1) 20 else 12)
        class Entry(val duration: Long, val mediaTime: Long)
        val entries = List(count) {
            val duration = if (version == 1) cursor.u64() else cursor.u32()
            if (duration < 0) {
                throw CorruptStreamException("the 'elst' box states a duration beyond the supported timing range", base + elst.offset)
            }
            val mediaTime = if (version == 1) cursor.u64() else cursor.i32().toLong()
            cursor.skip(4)
            Entry(duration, mediaTime)
        }
        // An empty edit, with a media time of -1, plays nothing for a while
        // before the media starts. For audio that is a delay against other
        // tracks, not part of the sound, so it is passed over.
        val real = entries.filter { it.mediaTime != -1L }
        if (real.isEmpty()) return null
        if (real.any { it.mediaTime < 0 }) throw CorruptStreamException("the edit list starts at a negative media time", base + elst.offset)
        var duration = 0L
        var end = real.first().mediaTime
        for ((index, edit) in real.withIndex()) {
            if (edit.mediaTime != end) {
                // Several edits that skip or repeat parts of the media make a
                // different recording from the one stored. Only a run that
                // plays the media straight through is followed.
                if (!options.tolerant) {
                    throw UnsupportedFeatureException(
                        "an MP4 edit list that skips or repeats media",
                        "this file's edit list plays its audio out of order, which AudioKodec does not follow",
                    )
                }
                break
            }
            val mediaDuration = rescale(edit.duration, mediaTimescale, movieTimescale, "the 'elst' duration", base + elst.offset)
            // A zero duration in the last edit means it runs to the end of the media.
            if (edit.duration == 0L && index == real.lastIndex && real.size == 1) return Edit(edit.mediaTime, null)
            if (mediaDuration > Long.MAX_VALUE - duration || mediaDuration > Long.MAX_VALUE - edit.mediaTime) {
                throw CorruptStreamException("the 'elst' box's edit times exceed the supported timing range", base + elst.offset)
            }
            duration += mediaDuration
            end = edit.mediaTime + mediaDuration
        }
        return Edit(real.first().mediaTime, duration)
    }

    private class Trex(val trackId: Int, val description: Long, val duration: Long, val size: Long)

    private fun readTrex(body: ByteArray, trex: Mp4Box, base: Long): Trex {
        val cursor = cursorOf(body, trex, base, "the 'trex' box")
        cursor.skip(4)
        val trackId = cursor.i32()
        val description = cursor.u32()
        val duration = cursor.u32()
        val size = cursor.u32()
        return Trex(trackId, description, duration, size)
    }

    // Packets.

    /** Ordered payload ranges let each packet be checked in logarithmic time. */
    private class MediaData(top: List<Mp4Box>, private val reader: SourceReader, options: DecodeOptions) {
        private val regions: List<Mp4Box>
        init {
            val count = top.count { it.type == "mdat" }
            options.checkAllocation(count.toLong() * 16, "indexing $count MP4 media-data regions")
            regions = top.filter { it.type == "mdat" }
        }

        fun check(offset: Long, size: Long) {
            if (offset < 0 || size < 0 || size > Long.MAX_VALUE - offset) {
                throw CorruptStreamException("a packet's data offsets overflow the supported file range", offset)
            }
            var low = 0
            var high = regions.size - 1
            var found = -1
            while (low <= high) {
                val middle = (low + high) ushr 1
                if (regions[middle].bodyOffset <= offset) {
                    found = middle
                    low = middle + 1
                } else high = middle - 1
            }
            val region = regions.getOrNull(found)
            // A final zero-sized mdat has no declared end. Physical EOF is
            // checked while reading packets, preserving cut-recording recovery.
            val end = if (region?.extendsToEnd == true) Long.MAX_VALUE else region?.end
            if (end == null || offset > end || size > end - offset) {
                throw CorruptStreamException("a packet of $size bytes is outside an 'mdat' payload", reader.absoluteOffset(offset))
            }
        }
    }

    /** Gathers packets, growing as needed and checking the allocation as it does. */
    private class SampleList(private val options: DecodeOptions, private val coalesce: Boolean) {
        private var offsets = LongArray(0)
        private var sizes = IntArray(0)
        private var durations = LongArray(0)
        var count = 0
            private set
        var totalBytes = 0L
            private set

        fun reserve(more: Long) {
            val needed = count + more
            if (needed <= offsets.size) return
            options.checkAllocation(needed * 20, "indexing $needed MP4 packets")
            if (needed > Int.MAX_VALUE - 8) throw CorruptStreamException("the file indexes $needed packets")
            val capacity = maxOf(needed, minOf(offsets.size * 2L, Int.MAX_VALUE - 8L, options.maxAllocationBytes / 20)).toInt()
            offsets = offsets.copyOf(capacity)
            sizes = sizes.copyOf(capacity)
            durations = durations.copyOf(capacity)
        }

        /** Only uncoalesced samples need an index entry each. */
        fun reserveSamples(more: Long) {
            if (!coalesce) reserve(more)
        }

        fun add(offset: Long, size: Long, duration: Long) {
            if (size > Int.MAX_VALUE || size < 0) throw CorruptStreamException("a packet states $size bytes", offset)
            if (offset < 0 || size > Long.MAX_VALUE - offset) throw CorruptStreamException("a packet's data offsets overflow 64 bits", offset)
            if (duration < 0 || totalBytes > Long.MAX_VALUE - size) throw CorruptStreamException("the packets' sizes or durations overflow 64 bits", offset)
            if (coalesce && count > 0) {
                val last = count - 1
                if (offsets[last] <= Long.MAX_VALUE - sizes[last] && offsets[last] + sizes[last] == offset && sizes[last] + size <= COALESCED_BYTES) {
                    if (duration > Long.MAX_VALUE - durations[last]) throw CorruptStreamException("the packets' durations overflow 64 bits", offset)
                    sizes[last] += size.toInt()
                    durations[last] += duration
                    totalBytes += size
                    return
                }
            }
            reserve(1)
            offsets[count] = offset
            sizes[count] = size.toInt()
            durations[count] = duration
            count++
            totalBytes += size
        }

        /** Adds a constant run in work proportional to its read packets. */
        fun addRun(offset: Long, size: Long, duration: Long, samples: Int) {
            if (samples == 0) return
            if (size !in 0..Int.MAX_VALUE.toLong()) throw CorruptStreamException("a packet states $size bytes", offset)
            if (offset < 0 || size * samples > Long.MAX_VALUE - offset) throw CorruptStreamException("a packet run's data offsets overflow 64 bits", offset)
            if (duration < 0 || duration > Long.MAX_VALUE / samples) throw CorruptStreamException("a packet run's durations overflow 64 bits", offset)
            val last = count - 1
            val adjacent = coalesce && last >= 0 && sizes[last] <= COALESCED_BYTES &&
                offsets[last] <= Long.MAX_VALUE - sizes[last] && offsets[last] + sizes[last] == offset
            val prefix = if (!adjacent) 0 else if (size == 0L) samples else minOf(samples.toLong(), (COALESCED_BYTES - sizes[last]) / size).toInt()
            val perPacket = if (coalesce && size in 1..COALESCED_BYTES.toLong()) (COALESCED_BYTES / size).toInt() else 1
            var left = samples - prefix
            val packets = if (coalesce && size == 0L && left > 0) 1L else (left.toLong() + perPacket - 1) / perPacket
            reserve(packets)
            var at = offset
            if (prefix > 0) {
                add(at, size * prefix, duration * prefix)
                at += size * prefix
            }
            if (coalesce && size == 0L && left > 0) {
                add(at, 0, duration * left)
                return
            }
            while (left > 0) {
                val take = minOf(left, perPacket)
                add(at, size * take, duration * take)
                at += size * take
                left -= take
            }
        }

        fun offsets(): LongArray = offsets.copyOf(count)
        fun sizes(): IntArray = sizes.copyOf(count)

        fun times(): LongArray {
            val out = LongArray(count + 1)
            for (i in 0 until count) {
                if (out[i] > Long.MAX_VALUE - durations[i]) throw CorruptStreamException("the packets' durations overflow 64 bits")
                out[i + 1] = out[i] + durations[i]
            }
            return out
        }

        /** Keeps the first [keep] packets only, for a file cut off part way. */
        fun truncate(keep: Int) {
            for (i in keep until count) totalBytes -= sizes[i]
            count = keep
        }

        fun offsetAt(i: Int): Long = offsets[i]
        fun sizeAt(i: Int): Int = sizes[i]

        fun checkRanges(mediaData: MediaData) {
            for (i in 0 until count) mediaData.check(offsets[i], sizes[i].toLong())
        }
    }

    /**
     * Expands the tables in [stbl] into packets. [frameBytes] is the size of
     * one frame of uncompressed QuickTime audio, whose `stsz` often states
     * one byte per frame whatever the frame holds.
     */
    private fun expandTables(body: ByteArray, stbl: Mp4Box?, into: SampleList, base: Long, frameBytes: Int?, options: DecodeOptions) {
        stbl ?: return
        val boxes = Mp4Box.children(body, stbl.bodyOffset.toInt(), stbl.end.toInt(), base)
        fun find(type: String) = boxes.firstOrNull { it.type == type }

        val stsz = find("stsz")
        val stz2 = find("stz2")
        val sizes = when {
            stsz != null -> cursorOf(body, stsz, base, "the 'stsz' box")
            stz2 != null -> cursorOf(body, stz2, base, "the 'stz2' box")
            else -> return
        }
        sizes.skip(4)
        var constantSize = 0L
        val fieldBits: Int
        if (stsz != null) {
            constantSize = sizes.u32()
            fieldBits = 32
        } else {
            sizes.skip(3)
            fieldBits = sizes.u8()
            if (fieldBits != 4 && fieldBits != 8 && fieldBits != 16) {
                throw CorruptStreamException("the 'stz2' box packs its sizes in $fieldBits bits", base + stz2!!.offset)
            }
        }
        val sampleCount = sizes.count()
        if (sampleCount == 0) return
        if (constantSize == 0L) {
            val needed = (sampleCount.toLong() * fieldBits + 7) / 8
            if (needed > sizes.remaining) {
                throw CorruptStreamException("the size table lists $sampleCount packets and holds room for fewer", base + (stsz ?: stz2)!!.offset)
            }
        }
        if (frameBytes != null && constantSize != 0L) constantSize = frameBytes.toLong()
        var nibble = -1
        fun nextSize(): Long = when {
            constantSize != 0L -> constantSize
            fieldBits == 32 -> sizes.u32()
            fieldBits == 16 -> sizes.u16().toLong()
            fieldBits == 8 -> sizes.u8().toLong()
            nibble >= 0 -> (nibble and 0x0F).toLong().also { nibble = -1 }
            else -> sizes.u8().let { nibble = it; (it ushr 4).toLong() }
        }

        val stco = find("stco")
        val co64 = find("co64")
        val chunks = when {
            stco != null -> cursorOf(body, stco, base, "the 'stco' box")
            co64 != null -> cursorOf(body, co64, base, "the 'co64' box")
            else -> throw CorruptStreamException("a track lists packets and no chunk offsets", base + stbl.offset)
        }
        chunks.skip(4)
        val chunkCount = chunks.count()
        chunks.requireEntries(chunkCount, if (co64 != null) 8 else 4)

        val stsc = find("stsc") ?: throw CorruptStreamException("a track lists packets and no 'stsc' box", base + stbl.offset)
        val toChunk = cursorOf(body, stsc, base, "the 'stsc' box")
        toChunk.skip(4)
        val runs = toChunk.count()
        toChunk.requireEntries(runs, 12)
        val firstChunks = LongArray(runs)
        val perChunk = LongArray(runs)
        for (i in 0 until runs) {
            firstChunks[i] = toChunk.u32()
            perChunk[i] = toChunk.u32()
            val description = toChunk.u32()
            if (description != 1L) {
                throw CorruptStreamException("the 'stsc' box names sample description $description but the track holds one", base + stsc.offset)
            }
            if (firstChunks[i] < 1 || (i > 0 && firstChunks[i] <= firstChunks[i - 1])) {
                throw CorruptStreamException("the 'stsc' box lists its chunks out of order", base + stsc.offset)
            }
        }

        val stts = find("stts") ?: throw CorruptStreamException("a track lists packets and no 'stts' box", base + stbl.offset)
        val times = cursorOf(body, stts, base, "the 'stts' box")
        times.skip(4)
        var timeRuns = times.count()
        times.requireEntries(timeRuns, 8)
        var runLeft = 0L
        var delta = 0L
        fun nextDurationRun() {
            while (runLeft == 0L) {
                if (timeRuns == 0) {
                    if (!options.tolerant) throw CorruptStreamException("the 'stts' box gives durations for fewer packets than there are", base + stts.offset)
                    runLeft = Long.MAX_VALUE
                    return
                }
                runLeft = times.u32()
                delta = times.u32()
                timeRuns--
            }
        }
        fun nextDuration(): Long {
            nextDurationRun()
            runLeft--
            return delta
        }

        into.reserveSamples(sampleCount.toLong())
        var sample = 0
        var run = -1
        chunkLoop@ for (chunk in 1..chunkCount) {
            while (run + 1 < runs && firstChunks[run + 1] <= chunk) run++
            if (run < 0) throw CorruptStreamException("the 'stsc' box does not start at the first chunk", base + stsc.offset)
            var offset = if (co64 != null) chunks.u64() else chunks.u32()
            if (frameBytes != null && constantSize != 0L) {
                var left = minOf(perChunk[run], sampleCount.toLong() - sample)
                while (left > 0) {
                    nextDurationRun()
                    val take = minOf(left, runLeft).toInt()
                    into.addRun(offset, constantSize, delta, take)
                    offset += constantSize * take
                    sample += take
                    runLeft -= take
                    left -= take
                }
                if (sample == sampleCount) break@chunkLoop
                continue
            }
            for (k in 0L until perChunk[run]) {
                if (sample >= sampleCount) break@chunkLoop
                val size = nextSize()
                if (offset < 0) throw CorruptStreamException("a chunk of the track starts past any file", base + stbl.offset)
                into.add(offset, size, nextDuration())
                offset += size
                sample++
            }
        }
        if (sample < sampleCount && !options.tolerant) {
            throw CorruptStreamException("the chunk tables place $sample of the $sampleCount packets the track lists", base + stbl.offset)
        }
    }

    /** Adds the packets one `moof` box describes for track [trackId]. */
    private fun readFragment(reader: SourceReader, moof: Mp4Box, trackId: Int, defaults: Map<Int, Trex>, into: SampleList, options: DecodeOptions) {
        val body = readBody(reader, moof, options)
        val base = moof.bodyOffset
        var previousEnd = moof.offset
        var first = true
        for (traf in Mp4Box.children(body, 0, body.size, base).filter { it.type == "traf" }) {
            val children = Mp4Box.children(body, traf.bodyOffset.toInt(), traf.end.toInt(), base)
            val tfhd = children.firstOrNull { it.type == "tfhd" }
                ?: throw CorruptStreamException("a track fragment has no 'tfhd' header", base + traf.offset)
            val header = cursorOf(body, tfhd, base, "the 'tfhd' box")
            header.skip(1)
            val flags = header.u24()
            val id = header.i32()
            val trackDefaults = defaults[id]
                ?: throw CorruptStreamException("the 'tfhd' box names track $id without 'trex' defaults", base + tfhd.offset)
            val explicitBase = if (flags and 0x1 != 0) header.u64() else null
            if (explicitBase != null && explicitBase < 0) {
                throw CorruptStreamException("the 'tfhd' box's base data offset exceeds the supported file range", base + tfhd.offset)
            }
            val description = if (flags and 0x2 != 0) header.u32() else trackDefaults.description
            if (id == trackId && description != 1L) {
                throw CorruptStreamException("the 'tfhd' box selects sample description $description but the audio track holds one", base + tfhd.offset)
            }
            val defaultDuration = if (flags and 0x8 != 0) header.u32() else trackDefaults.duration
            val defaultSize = if (flags and 0x10 != 0) header.u32() else trackDefaults.size
            val dataBase = when {
                explicitBase != null -> explicitBase
                flags and 0x20000 != 0 || first -> moof.offset
                else -> previousEnd
            }
            first = false
            var data = dataBase
            for (trun in children.filter { it.type == "trun" }) {
                val run = cursorOf(body, trun, base, "the 'trun' box")
                run.skip(1)
                val runFlags = run.u24()
                val count = run.count()
                if (runFlags and 0x1 != 0) data = fragmentOffset(dataBase, run.i32().toLong(), base + trun.offset)
                if (runFlags and 0x4 != 0) run.skip(4)
                val perSample = listOf(0x100, 0x200, 0x400, 0x800).count { runFlags and it != 0 } * 4
                run.requireEntries(count, perSample)
                if (id != trackId && runFlags and 0x200 == 0) {
                    // Both fields are at most 32 bits, and count fits a signed
                    // Int, so their product fits Long. No sample bytes need
                    // reading when only the default size places the data.
                    data = fragmentOffset(data, count.toLong() * defaultSize, base + trun.offset)
                    // requireEntries proved the product fits remaining, an Int.
                    run.skip(count * perSample)
                    continue
                }
                if (id == trackId && perSample == 0) {
                    val end = fragmentOffset(data, count.toLong() * defaultSize, base + trun.offset)
                    into.addRun(data, defaultSize, defaultDuration, count)
                    data = end
                    continue
                }
                if (id == trackId) into.reserveSamples(count.toLong())
                repeat(count) {
                    val duration = if (runFlags and 0x100 != 0) run.u32() else defaultDuration
                    val size = if (runFlags and 0x200 != 0) run.u32() else defaultSize
                    if (runFlags and 0x400 != 0) run.skip(4)
                    if (runFlags and 0x800 != 0) run.skip(4)
                    if (id == trackId) {
                        if (data < 0) throw CorruptStreamException("a fragment's data starts before the file", base + trun.offset)
                        into.add(data, size, duration)
                    }
                    data = fragmentOffset(data, size, base + trun.offset)
                }
            }
            previousEnd = data
        }
    }

    private fun fragmentOffset(start: Long, advance: Long, offset: Long): Long {
        if (start < 0 || advance > Long.MAX_VALUE - start || advance < -start) {
            throw CorruptStreamException("the 'trun' box places data outside the supported file offset range", offset)
        }
        return start + advance
    }

    // Chapters.

    /**
     * Reads a QuickTime chapter track: a text track whose every packet is one
     * chapter title, a 16-bit length and the text, timed by the packet.
     */
    private fun readChapterTrack(reader: SourceReader, track: Track, body: ByteArray, base: Long, options: DecodeOptions, mediaData: MediaData): List<TagChapter>? {
        val samples = SampleList(options, coalesce = false)
        expandTables(body, track.stbl, samples, base, null, options)
        if (samples.count == 0) return null
        samples.checkRanges(mediaData)
        val times = samples.times()
        val chapters = ArrayList<TagChapter>(samples.count)
        for (i in 0 until samples.count) {
            val size = minOf(samples.sizeAt(i), 2 + MAX_TITLE_BYTES)
            val bytes = ByteArray(size)
            reader.seek(samples.offsetAt(i))
            if (reader.readSome(bytes, 0, size) < size) {
                throw TruncatedStreamException("chapter ${i + 1} is cut off", samples.offsetAt(i))
            }
            val title = if (size >= 2) {
                val length = minOf(Mp4Box.readU16(bytes, 0), size - 2)
                decodeTitle(bytes, 2, length)
            } else {
                null
            }
            val start = rescale(times[i], 1000, track.timescale)
            val end = rescale(times[i + 1], 1000, track.timescale)
            chapters += TagChapter("chapter${i + 1}", start, maxOf(start, end), title?.ifEmpty { null })
        }
        return chapters
    }

    /** Text in a chapter track is UTF-8, unless it opens with a UTF-16 byte order mark. */
    private fun decodeTitle(bytes: ByteArray, from: Int, length: Int): String {
        if (length >= 2) {
            val first = bytes[from].toInt() and 0xFF
            val second = bytes[from + 1].toInt() and 0xFF
            val bigEndian = first == 0xFE && second == 0xFF
            if (bigEndian || (first == 0xFF && second == 0xFE)) {
                val units = (length - 2) / 2
                val chars = CharArray(units) { k ->
                    val a = bytes[from + 2 + 2 * k].toInt() and 0xFF
                    val b = bytes[from + 3 + 2 * k].toInt() and 0xFF
                    (if (bigEndian) (a shl 8) or b else (b shl 8) or a).toChar()
                }
                return chars.concatToString()
            }
        }
        return bytes.decodeToString(from, from + length)
    }

    /**
     * Reads Nero's `chpl` box, which lists chapter starts in units of 100
     * nanoseconds with their titles, inside the movie's `udta` box.
     */
    private fun readNeroChapters(body: ByteArray, udta: Mp4Box, base: Long): List<TagChapter>? {
        val chpl = child(body, udta, "chpl", base) ?: return null
        val cursor = cursorOf(body, chpl, base, "the 'chpl' box")
        val version = cursor.u8()
        cursor.skip(3)
        if (version == 1) cursor.skip(4)
        val count = cursor.u8()
        class Start(val millis: Long, val title: String)
        val starts = List(count) {
            val start = cursor.u64()
            if (start < 0) throw CorruptStreamException("a chapter starts at a negative time", base + chpl.offset)
            val length = cursor.u8()
            Start(start / 10_000, cursor.bytes(length).decodeToString())
        }
        return starts.mapIndexed { i, start ->
            val end = starts.getOrNull(i + 1)?.millis?.takeIf { it >= start.millis } ?: -1L
            TagChapter("chapter${i + 1}", start.millis, end, start.title.ifEmpty { null })
        }
    }

    private const val MAX_TITLE_BYTES = 1024
    private const val COALESCED_BYTES = 1 shl 16
    private const val MAX_TOP_LEVEL_BOXES = 1 shl 20

    /** [value] in units of [from] per second, in units of [to] per second instead. */
    fun rescale(value: Long, to: Long, from: Long, what: String = "an MP4 time", offset: Long = -1L): Long {
        if (value < 0 || to !in 1..0xFFFF_FFFFL || from !in 1..0xFFFF_FFFFL) {
            throw CorruptStreamException("$what uses an invalid time or timescale", offset)
        }
        if (to == from) return value
        // Timescales are unsigned 32-bit fields, so the fractional product
        // fits unsigned 64 bits even when it exceeds signed 64-bit range.
        val whole = value / from
        val part = value % from
        val fraction = (part.toULong() * to.toULong() / from.toULong()).toLong()
        if (whole > (Long.MAX_VALUE - fraction) / to) {
            throw CorruptStreamException("$what exceeds the supported timing range when rescaled", offset)
        }
        return whole * to + fraction
    }
}

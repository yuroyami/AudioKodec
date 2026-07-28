package io.github.yuroyami.kiteaudio.internal.tag

import io.github.yuroyami.kiteaudio.AudioContainer
import io.github.yuroyami.kiteaudio.AllocationGuardException
import io.github.yuroyami.kiteaudio.CorruptStreamException
import io.github.yuroyami.kiteaudio.TruncatedStreamException
import io.github.yuroyami.kiteaudio.internal.io.SourceReader
import io.github.yuroyami.kiteaudio.tag.RawTagBlock
import io.github.yuroyami.kiteaudio.tag.RawTagEntry
import io.github.yuroyami.kiteaudio.tag.TagFormat

/**
 * Finds every tag block a file holds.
 *
 * Tags live in three places. Some sit in front of the audio, some sit behind
 * it, and some are chunks inside the container itself. A file can have all
 * three at once, so this looks in all three places rather than stopping at the
 * first block it finds.
 *
 * Only the head and the tail of the file are read. A tag block is small, and
 * loading a two-hour recording to find a title would be absurd.
 */
internal object TagLocator {

    fun findAll(reader: SourceReader, container: AudioContainer?): List<RawTagBlock> {
        val blocks = ArrayList<RawTagBlock>()
        val size = reader.size
        if (size == 0L) return blocks

        val containerOffset = readLeading(reader, blocks)
        if (size > 0) readTrailing(reader, size, blocks)

        val containerReader = if (containerOffset > 0) {
            SourceReader(
                source = reader.source,
                options = reader.options,
                baseOffset = reader.absoluteOffset(containerOffset),
            )
        } else {
            reader
        }
        val embedded = ArrayList<RawTagBlock>()
        when (container) {
            AudioContainer.WAV -> readRiffChunks(containerReader, embedded, rf64 = false)
            AudioContainer.RF64 -> readRiffChunks(containerReader, embedded, rf64 = true)
            AudioContainer.AIFF -> readAiffChunks(containerReader, embedded)
            AudioContainer.FLAC -> readFlacBlocks(containerReader, embedded)
            AudioContainer.OGG -> readOggBlocks(containerReader, embedded)
            else -> Unit
        }
        for (block in embedded) blocks.add(shift(block, containerReader.baseOffset))

        return blocks.sortedBy { it.offset }
    }

    /**
     * Reads blocks in front of the container and returns where it begins.
     *
     * More than one ID3 block is legal and occurs after repeated edits, so
     * each successfully parsed block advances to the next rather than making
     * the first one hide every block after it.
     */
    private fun readLeading(reader: SourceReader, into: MutableList<RawTagBlock>): Long {
        val header = ByteArray(Id3v2Reader.HEADER_SIZE)
        var offset = 0L
        while (true) {
            reader.seek(offset)
            if (reader.readSome(header, 0, header.size) < header.size) break
            val id3Size = Id3v2Reader.blockSizeAt(header, 0)
            if (id3Size <= 0) break
            if (reader.size >= 0 && id3Size.toLong() > reader.size - offset) break
            reader.options.checkAllocation(id3Size.toLong(), "reading a leading ID3v2 block")
            val block = ByteArray(id3Size)
            reader.seek(offset)
            if (reader.readSome(block, 0, id3Size) != id3Size) break
            val parsed = Id3v2Reader.read(
                block,
                0,
                reader.absoluteOffset(offset),
                reader.options,
            ) ?: break
            into.add(parsed)
            if (offset > Long.MAX_VALUE - id3Size) {
                throw CorruptStreamException("the leading tag chain overflows 64-bit offsets", offset)
            }
            offset += id3Size
        }

        // A standalone APE file can carry its tag at the front instead. Check
        // at the current front too, so an ID3 block before it does not hide it.
        val apeHeader = readRange(reader, offset, ApeTag.FOOTER_SIZE)
        if (apeHeader != null) {
            val declared = declaredApeSize(apeHeader)
            if (declared > 0 &&
                (reader.size < 0 || declared.toLong() <= reader.size - offset)
            ) {
                reader.options.checkAllocation(declared.toLong(), "reading a leading APE tag")
                val block = readRange(reader, offset, declared)
                if (block != null) {
                    ApeTag.readStartingAt(block, 0, reader.options)?.let {
                        into.add(shift(it, reader.absoluteOffset(offset)))
                        offset += declared
                    }
                }
            }
        }
        return offset
    }

    /**
     * Reads the blocks that sit behind the audio, peeling them off the end.
     *
     * The order they appear in varies between writers, so rather than assume
     * one, this takes whatever is at the current end of the file and moves the
     * end backwards past it. That handles an APE tag before an ID3v1 block,
     * a Lyrics3 block between them, and any other order somebody produced.
     */
    private fun readTrailing(reader: SourceReader, size: Long, into: MutableList<RawTagBlock>) {
        var end = size
        while (end > 0) {
            val before = end

            if (end >= Id3v1.SIZE) {
                readRange(reader, end - Id3v1.SIZE, Id3v1.SIZE)?.let { bytes ->
                    Id3v1.read(bytes, 0)?.let { block ->
                        into.add(shift(block, reader.absoluteOffset(end - Id3v1.SIZE)))
                        end -= Id3v1.SIZE
                    }
                }
            }
            if (end != before) {
                continue
            }

            if (end >= ApeTag.FOOTER_SIZE) {
                val footer = readRange(reader, end - ApeTag.FOOTER_SIZE, ApeTag.FOOTER_SIZE)
                val total = footer?.let { trailingApeSize(it) } ?: -1L
                if (total in 1..end && total <= Int.MAX_VALUE) {
                    reader.options.checkAllocation(total, "reading a trailing APE tag")
                    readRange(reader, end - total, total.toInt())?.let { bytes ->
                        ApeTag.findEndingAt(bytes, bytes.size, reader.options)?.let { block ->
                            into.add(shift(block, reader.absoluteOffset(end - total)))
                            end -= total
                        }
                    }
                }
            }
            if (end != before) {
                continue
            }

            if (end >= 15) {
                val trailer = readRange(reader, end - 15, 15)
                val total = trailer?.let { trailingLyricsSize(it) } ?: -1
                if (total > 0 && total.toLong() <= end) {
                    reader.options.checkAllocation(total.toLong(), "reading a Lyrics3 tag")
                    readRange(reader, end - total, total)?.let { bytes ->
                        Lyrics3.findEndingAt(bytes, bytes.size)?.let { block ->
                            into.add(shift(block, reader.absoluteOffset(end - total)))
                            end -= total
                        }
                    }
                }
            }
            if (end != before) {
                continue
            }

            // An ID3v2.4 block may be appended, in which case its footer is
            // the last thing before whatever came after it.
            if (end >= Id3v2Reader.HEADER_SIZE) {
                val footer = readRange(
                    reader,
                    end - Id3v2Reader.HEADER_SIZE,
                    Id3v2Reader.HEADER_SIZE,
                )
                footer?.let { actualFooter ->
                    val total = appendedId3Size(actualFooter)
                    if (total > 0 && total.toLong() <= end) {
                        val start = end - total
                        val header = readRange(reader, start, Id3v2Reader.HEADER_SIZE)
                        val mirrorsHeader = header?.let { candidate ->
                            Id3v2Reader.blockSizeAt(candidate, 0) == total &&
                                (3 until Id3v2Reader.HEADER_SIZE).all {
                                    candidate[it] == actualFooter[it]
                                }
                        } == true
                        if (mirrorsHeader) {
                            reader.options.checkAllocation(
                                total.toLong(),
                                "reading an appended ID3v2 tag",
                            )
                            readRange(reader, start, total)?.let { bytes ->
                                Id3v2Reader.read(
                                    bytes,
                                    0,
                                    reader.absoluteOffset(start),
                                    reader.options,
                                )?.let { block ->
                                    into.add(block)
                                    end -= total
                                }
                            }
                        }
                    }
                }
            }
            if (end == before) break
        }
    }

    /** Declared total of an appended ID3v2.4 block from its ten-byte footer. */
    private fun appendedId3Size(footer: ByteArray): Int {
        if (footer.size != Id3v2Reader.HEADER_SIZE ||
            footer[0].toInt() != '3'.code ||
            footer[1].toInt() != 'D'.code ||
            footer[2].toInt() != 'I'.code ||
            (footer[3].toInt() and 0xFF) != 4 ||
            (footer[4].toInt() and 0xFF) == 0xFF
        ) {
            return -1
        }
        val flags = footer[5].toInt() and 0xFF
        if (flags and 0x10 == 0 || flags and 0x0F != 0) return -1
        val bodySize = Id3v2Reader.readSyncsafe(footer, 6) ?: return -1
        return bodySize + Id3v2Reader.HEADER_SIZE * 2
    }

    private fun trailingLyricsSize(trailer: ByteArray): Int {
        if (trailer.size != 15) return -1
        val marker = "LYRICS200"
        for (index in marker.indices) {
            if ((trailer[6 + index].toInt() and 0xFF) != marker[index].code) return -1
        }
        var size = 0
        for (index in 0 until 6) {
            val digit = trailer[index].toInt() - '0'.code
            if (digit !in 0..9) return -1
            size = size * 10 + digit
        }
        return size + 15
    }

    private fun trailingApeSize(footer: ByteArray): Long {
        if (footer.size != ApeTag.FOOTER_SIZE) return -1
        val magic = "APETAGEX"
        for (index in magic.indices) {
            if ((footer[index].toInt() and 0xFF) != magic[index].code) return -1
        }
        val body = readU32Le(footer, 12)
        if (body < ApeTag.FOOTER_SIZE) return -1
        val flags = readU32Le(footer, 20)
        return body + if (flags and 0x8000_0000L != 0L) ApeTag.FOOTER_SIZE else 0
    }

    private fun declaredApeSize(header: ByteArray): Int {
        if (header.size < ApeTag.FOOTER_SIZE) return -1
        val magic = "APETAGEX"
        for (i in magic.indices) {
            if ((header[i].toInt() and 0xFF) != magic[i].code) return -1
        }
        val bodySize = readU32Le(header, 12)
        if (bodySize < ApeTag.FOOTER_SIZE) return -1
        val total = bodySize + ApeTag.FOOTER_SIZE
        return if (total <= Int.MAX_VALUE) total.toInt() else -1
    }

    private fun readRange(reader: SourceReader, at: Long, count: Int): ByteArray? {
        if (at < 0 || count < 0) return null
        if (reader.size >= 0 && count.toLong() > reader.size - at) return null
        reader.options.checkAllocation(count.toLong(), "reading a $count-byte tag region")
        val bytes = ByteArray(count)
        reader.seek(at)
        return if (reader.readSome(bytes, 0, count) == count) bytes else null
    }

    private fun readU32Le(bytes: ByteArray, at: Int): Long =
        (bytes[at].toLong() and 0xFF) or
            ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or
            ((bytes[at + 3].toLong() and 0xFF) shl 24)

    private fun shift(block: RawTagBlock, by: Long): RawTagBlock {
        if (block.offset < 0 || by < 0 || block.offset > Long.MAX_VALUE - by) {
            throw CorruptStreamException("a tag offset overflows 64 bits", block.offset)
        }
        return RawTagBlock(block.format, block.offset + by, block.size, block.entries)
    }

    /**
     * Walks a RIFF file for the chunks that hold metadata.
     *
     * Three kinds turn up: a `LIST` of type `INFO`, the Broadcast Wave `bext`
     * chunk, and an `id3 ` chunk holding a whole ID3v2 block.
     */
    private fun readRiffChunks(
        reader: SourceReader,
        into: MutableList<RawTagBlock>,
        rf64: Boolean,
    ) {
        val formEnd = readRiffFormEnd(reader, rf64) ?: return
        var dataSize = -1L
        val extraSizes = ArrayList<RiffDs64Entry>()
        reader.seek(12)
        chunkLoop@ while (true) {
            if (reader.position > formEnd || formEnd - reader.position < 8) break
            val id = try {
                reader.readFourCc()
            } catch (_: TruncatedStreamException) {
                break
            }
            val chunkStart = reader.position - 4
            val storedSize = try {
                reader.readU32Le()
            } catch (_: TruncatedStreamException) {
                break
            }
            val bodyStart = reader.position
            val chunkSize = if (rf64 && storedSize == 0xFFFF_FFFFL) {
                if (id == "data") {
                    dataSize
                } else {
                    extraSizes.firstOrNull { !it.used && it.id == id }?.let {
                        it.used = true
                        it.size
                    } ?: -1L
                }
            } else {
                storedSize
            }
            if (chunkSize < 0) break
            if (bodyStart > formEnd || chunkSize > formEnd - bodyStart) break

            try {
                when (id) {
                    "ds64" -> if (rf64 && chunkSize >= 28) {
                        reader.readI64Le()
                        dataSize = reader.readI64Le()
                        reader.readI64Le()
                        val count = reader.readU32Le()
                        val available = (chunkSize - 28L) / 12L
                        if (dataSize < 0 || count > available || count > Int.MAX_VALUE) {
                            break@chunkLoop
                        }
                        reader.options.checkAllocation(count * 24L, "the RF64 ds64 size table")
                        for (index in 0 until count.toInt()) {
                            val chunkId = reader.readFourCc()
                            val declared = reader.readI64Le()
                            if (declared < 0) break@chunkLoop
                            extraSizes.add(RiffDs64Entry(chunkId, declared))
                        }
                    }
                    "LIST" -> if (chunkSize >= 4) {
                        val listType = reader.readFourCc()
                        if (listType == "INFO") {
                            readRiffInfo(reader, bodyStart + 4, chunkSize - 4, chunkStart, into)
                        }
                    }
                    "bext" -> readBext(reader, bodyStart, chunkSize, chunkStart, into)
                    "id3 ", "ID3 " -> {
                        val body = readSizedBytes(reader, chunkSize, "reading a RIFF ID3 chunk")
                        Id3v2Reader.read(body, 0, bodyStart, reader.options)?.let { into.add(it) }
                    }
                }
            } catch (_: TruncatedStreamException) {
                break
            }

            if (chunkSize > Long.MAX_VALUE - bodyStart) break
            var next = bodyStart + chunkSize
            if (chunkSize % 2 == 1L) {
                if (next == Long.MAX_VALUE) break
                next++
            }
            if (next <= chunkStart || next > formEnd) break
            reader.seek(next)
            if (next == formEnd) break
        }
    }

    /**
     * Returns the physical-or-declared end of a RIFF form, whichever is first.
     *
     * Bytes after that boundary are external data, even when they happen to
     * begin with a plausible RIFF chunk header. RF64 stores the authoritative
     * 64-bit form size in the mandatory first `ds64` chunk.
     */
    private fun readRiffFormEnd(reader: SourceReader, rf64: Boolean): Long? {
        return try {
            reader.seek(0)
            val riffId = reader.readFourCc()
            val storedSize = reader.readU32Le()
            if (reader.readFourCc() != "WAVE") return null
            if (rf64) {
                if (riffId != "RF64" && riffId != "BW64") return null
            } else if (riffId != "RIFF") {
                return null
            }

            val declaredEnd = if (rf64) {
                if (reader.readFourCc() != "ds64") return null
                val ds64Size = reader.readU32Le()
                if (ds64Size < 28L) return null
                val riffSize = reader.readI64Le()
                if (riffSize < 4L || riffSize > Long.MAX_VALUE - 8L) return null
                riffSize + 8L
            } else {
                if (storedSize < 4L) return null
                storedSize + 8L
            }
            if (declaredEnd < 12L) return null
            if (reader.size >= 0) minOf(declaredEnd, reader.size) else declaredEnd
        } catch (_: TruncatedStreamException) {
            null
        }
    }

    private class RiffDs64Entry(
        val id: String,
        val size: Long,
        var used: Boolean = false,
    )

    private fun readRiffInfo(
        reader: SourceReader,
        from: Long,
        length: Long,
        blockStart: Long,
        into: MutableList<RawTagBlock>,
    ) {
        if (from < 0 || length < 0 || length > Long.MAX_VALUE - from) return
        val entries = ArrayList<RawTagEntry>()
        var position = from
        val end = from + length
        while (position <= end && end - position >= 8) {
            reader.seek(position)
            val key = try {
                reader.readFourCc()
            } catch (_: TruncatedStreamException) {
                break
            }
            val entrySize = try {
                reader.readU32Le()
            } catch (_: TruncatedStreamException) {
                break
            }
            val available = end - position - 8
            if (entrySize > available) break
            val body = try {
                readSizedBytes(reader, entrySize, "reading a RIFF INFO value")
            } catch (_: TruncatedStreamException) {
                break
            }
            val text = TagText.decodeLatin1(body, 0, body.size, reader.options).trim()
            if (text.isNotEmpty()) entries.add(RawTagEntry(key, text = text))
            val padded = entrySize + (entrySize and 1L)
            if (padded > Long.MAX_VALUE - position - 8) break
            position += 8 + padded
        }
        if (entries.isNotEmpty() && length <= Long.MAX_VALUE - 12) {
            into.add(RawTagBlock(TagFormat.RIFF_INFO, blockStart, length + 12, entries))
        }
    }

    /**
     * Reads the Broadcast Wave chunk, whose fields are fixed-length text.
     *
     * The chunk is 602 bytes plus however much coding history follows it. Only
     * the fields with somewhere to go in the merged view are named here; the
     * rest stay in the raw block.
     */
    private fun readBext(
        reader: SourceReader,
        from: Long,
        length: Long,
        blockStart: Long,
        into: MutableList<RawTagBlock>,
    ) {
        if (length < 602) return
        reader.seek(from)
        val body = readSizedBytes(reader, length, "reading a Broadcast Wave bext chunk")
        val entries = ArrayList<RawTagEntry>()

        fun text(key: String, offset: Int, size: Int) {
            if (offset + size > body.size) return
            val value = TagText.decodeLatin1(body, offset, size, reader.options).trim()
            if (value.isNotEmpty()) entries.add(RawTagEntry(key, text = value))
        }

        text("Description", 0, 256)
        text("Originator", 256, 32)
        text("OriginatorReference", 288, 32)
        text("OriginationDate", 320, 10)
        text("OriginationTime", 330, 8)
        if (body.size > 602) {
            text("CodingHistory", 602, body.size - 602)
        }
        if (entries.isNotEmpty() && length <= Long.MAX_VALUE - 8) {
            into.add(RawTagBlock(TagFormat.BWF_BEXT, blockStart, length + 8, entries))
        }
    }

    /**
     * Reads the comment and picture blocks of a FLAC file.
     *
     * FLAC keeps its tags in metadata blocks rather than at either end of the
     * file, so the walk is the same one the decoder does. A block chain that is
     * malformed yields no tags rather than failing the read, because a caller
     * asking for tags did not ask to be told the audio is broken.
     */
    private fun readFlacBlocks(reader: SourceReader, into: MutableList<RawTagBlock>) {
        val parsed = try {
            io.github.yuroyami.kiteaudio.internal.flac.FlacMetadata.parse(reader, reader.options)
        } catch (_: io.github.yuroyami.kiteaudio.KiteAudioException) {
            return
        }
        into.addAll(parsed.tagBlocks)
    }

    /**
     * Reads the comment header of an Ogg file's audio stream.
     *
     * Every audio mapping Ogg carries puts its tags in the second packet, in
     * the same Vorbis comment structure, so one path serves Vorbis, Opus, FLAC
     * and Speex alike.
     */
    private fun readOggBlocks(reader: SourceReader, into: MutableList<RawTagBlock>) {
        val parsed = try {
            io.github.yuroyami.kiteaudio.internal.ogg.OggAnalyser.parse(reader, reader.options)
        } catch (_: io.github.yuroyami.kiteaudio.KiteAudioException) {
            return
        }
        into.addAll(parsed.tagBlocks)
    }

    /** Walks an AIFF file for its text chunks and any ID3 chunk. */
    private fun readAiffChunks(reader: SourceReader, into: MutableList<RawTagBlock>) {
        val formEnd = readAiffFormEnd(reader) ?: return
        reader.seek(12)
        val entries = ArrayList<RawTagEntry>()
        var firstChunk = -1L
        var lastEnd = 0L

        while (true) {
            if (reader.position > formEnd || formEnd - reader.position < 8) break
            val id = try {
                reader.readFourCc()
            } catch (_: TruncatedStreamException) {
                break
            }
            val chunkStart = reader.position - 4
            val chunkSize = try {
                reader.readU32Be()
            } catch (_: TruncatedStreamException) {
                break
            }
            val bodyStart = reader.position
            if (chunkSize < 0) break
            if (bodyStart > formEnd || chunkSize > formEnd - bodyStart) break

            try {
                when (id) {
                    "NAME", "AUTH", "(c) ", "ANNO" -> {
                        val body = readSizedBytes(reader, chunkSize, "reading an AIFF text chunk")
                        val text = TagText.decodeLatin1(body, 0, body.size, reader.options).trim()
                        if (text.isNotEmpty()) {
                            entries.add(RawTagEntry(id, text = text))
                            if (firstChunk < 0) firstChunk = chunkStart
                            lastEnd = bodyStart + chunkSize
                        }
                    }
                    "ID3 ", "id3 " -> {
                        val body = readSizedBytes(reader, chunkSize, "reading an AIFF ID3 chunk")
                        Id3v2Reader.read(body, 0, bodyStart, reader.options)?.let { into.add(it) }
                    }
                }
            } catch (_: TruncatedStreamException) {
                break
            }

            if (chunkSize > Long.MAX_VALUE - bodyStart) break
            var next = bodyStart + chunkSize
            if (chunkSize % 2 == 1L) {
                if (next == Long.MAX_VALUE) break
                next++
            }
            if (next <= chunkStart || next > formEnd) break
            reader.seek(next)
            if (next == formEnd) break
        }

        if (entries.isNotEmpty()) {
            into.add(
                RawTagBlock(TagFormat.AIFF_TEXT, firstChunk, lastEnd - firstChunk, entries),
            )
        }
    }

    /** Returns the bounded end of an AIFF FORM, excluding any appended data. */
    private fun readAiffFormEnd(reader: SourceReader): Long? {
        return try {
            reader.seek(0)
            if (reader.readFourCc() != "FORM") return null
            val formSize = reader.readU32Be()
            val formType = reader.readFourCc()
            if (formType != "AIFF" && formType != "AIFC") return null
            if (formSize < 4L) return null
            val declaredEnd = formSize + 8L
            if (reader.size >= 0) minOf(declaredEnd, reader.size) else declaredEnd
        } catch (_: TruncatedStreamException) {
            null
        }
    }

    /** Reads one declared metadata body without silently truncating its value. */
    private fun readSizedBytes(reader: SourceReader, size: Long, what: String): ByteArray {
        reader.options.checkAllocation(size, what)
        if (size > Int.MAX_VALUE) {
            throw AllocationGuardException(
                "$what must fit in one Kotlin array",
                size,
                minOf(reader.options.maxAllocationBytes, Int.MAX_VALUE.toLong()),
            )
        }
        return reader.readBytes(size.toInt())
    }
}

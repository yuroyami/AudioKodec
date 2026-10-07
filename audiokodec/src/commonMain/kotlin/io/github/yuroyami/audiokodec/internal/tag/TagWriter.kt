package io.github.yuroyami.audiokodec.internal.tag

import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioSink
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagWriteOptions

/**
 * Writes tags back into a file.
 *
 * The one rule that matters: the audio is copied, never re-encoded, and never
 * touched. Every path here works by finding where the audio starts and ends,
 * writing new tag blocks around it, and copying the bytes in between exactly
 * as they were. A test checks the copied region against the original with a
 * checksum, because a tagger that quietly damages audio is worse than one that
 * refuses to write.
 */
internal object TagWriter {

    /** Bytes copied at a time when moving the audio across. */
    private const val COPY_CHUNK = 1 shl 16

    private const val UINT32_MAX = 0xFFFF_FFFFL

    fun write(
        reader: SourceReader,
        container: AudioContainer?,
        tags: TagBundle,
        sink: AudioSink,
        options: TagWriteOptions,
    ) {
        validateRequestedFormats(container, options.formats)

        // A leading ID3 block may sit in front of a real container. Container
        // writers operate on the logical view that starts at its own header,
        // so preserve and update that external block explicitly before writing
        // the container. Explicit format selection remains authoritative and
        // lets a caller remove it or request an ID3 chunk native to the
        // container instead.
        val containerHasSeparateMetadata = container == AudioContainer.WAV ||
            container == AudioContainer.RF64 ||
            container == AudioContainer.AIFF ||
            container == AudioContainer.FLAC ||
            container == AudioContainer.OGG
        val existing = TagLocator.findAll(reader, container)
        val declaredEnd = if (container == AudioContainer.WAV ||
            container == AudioContainer.RF64 ||
            container == AudioContainer.AIFF
        ) {
            declaredContainerEnd(reader, container)
        } else {
            null
        }
        if (declaredEnd != null && reader.size >= 0 && declaredEnd > reader.size) {
            throw TruncatedStreamException(
                "${container?.displayName ?: "the container"} declares $declaredEnd bytes, " +
                    "but the source holds only ${reader.size}",
                reader.size,
            )
        }
        val externalTrailing = if (containerHasSeparateMetadata) {
            externalTrailingSpans(reader, existing, declaredEnd)
        } else {
            emptyList()
        }
        val hadLeadingId3 = if (reader.baseOffset > 0 && containerHasSeparateMetadata) {
            val root = SourceReader(reader.source, reader.options)
            TagLocator.findAll(root, null).any {
                it.offset < reader.baseOffset &&
                    (it.format == TagFormat.ID3V2_2 ||
                        it.format == TagFormat.ID3V2_3 ||
                        it.format == TagFormat.ID3V2_4)
            }
        } else {
            false
        }
        val hadTrailingId3 = externalTrailing.any { isId3v2(it.block.format) }
        val nativeFormat = when (container) {
            AudioContainer.WAV, AudioContainer.RF64 -> TagFormat.RIFF_INFO
            AudioContainer.AIFF -> TagFormat.AIFF_TEXT
            else -> null
        }
        val needsId3 = options.formats == null && nativeFormat != null &&
            NativeTextCapabilities.losses(tags, nativeFormat).isNotEmpty()
        val addId3Chunk = needsId3 && !hadLeadingId3 && !hadTrailingId3 &&
            existing.none { isId3v2(it.format) }
        val willWriteId3 = if (containerHasSeparateMetadata) {
            options.formats?.any { isId3v2(it) }
                ?: (hadLeadingId3 || hadTrailingId3 || existing.any { isId3v2(it.format) } || needsId3)
        } else {
            options.formats == null || options.formats.any { isId3v2(it) }
        }
        if (nativeFormat != null && !willWriteId3 && options.formats?.isEmpty() != true) {
            NativeTextCapabilities.requireComplete(tags, nativeFormat)
        }
        val artworkTags = if (willWriteId3) {
            Id3v2Writer.normaliseArtwork(tags, requestedId3Version(options))
        } else {
            tags
        }
        // Prepare APE before the first sink write. APE has one item per
        // picture role, so extra images need a richer block in the same file.
        val wantApe = if (containerHasSeparateMetadata) {
            options.formats == null && externalTrailing.any {
                it.block.format == TagFormat.APEV1 || it.block.format == TagFormat.APEV2
            }
        } else {
            options.formats?.contains(TagFormat.APEV2)
                ?: existing.any { it.format == TagFormat.APEV1 || it.format == TagFormat.APEV2 }
        }
        val picturesPreservedElsewhere = if (containerHasSeparateMetadata) {
            container == AudioContainer.FLAC || container == AudioContainer.OGG ||
                willWriteId3
        } else {
            options.formats == null || options.formats.any { isId3v2(it) }
        }
        val ape = if (wantApe) {
            ApeTag.build(artworkTags, picturesPreservedElsewhere, chaptersPreservedElsewhere = willWriteId3)
        } else {
            null
        }
        if ((container == AudioContainer.FLAC || container == AudioContainer.OGG) &&
            options.formats?.isEmpty() != true
        ) {
            VorbisComment.requireRepresentable(artworkTags, chaptersPreservedElsewhere = willWriteId3)
        }
        val externalId3 = options.formats == null && containerHasSeparateMetadata &&
            (hadLeadingId3 || hadTrailingId3)
        // Validate and prepare complete ID3 before any output, including an
        // external prefix. Container chunks reuse the same prepared block.
        val id3 = if (willWriteId3) {
            val padding = if (!containerHasSeparateMetadata) options.padding else 0
            Id3v2Writer.build(artworkTags, requestedId3Version(options), padding)
        } else {
            null
        }
        val prefixId3 = if (externalId3) Id3v2Writer.withPadding(id3!!, options.padding) else null
        if (prefixId3 != null) sink.write(prefixId3)
        // FLAC and Ogg writers use -1 to mean "copy to EOF". Chunked
        // containers have their own finite declared end even when the source
        // cannot report a total size.
        val writerSourceEnd = reader.size
        val containerEnd = declaredEnd
            ?: externalTrailing.firstOrNull()?.offset
            ?: writerSourceEnd
        when (container) {
            AudioContainer.WAV ->
                writeRiff(
                    reader, artworkTags, sink, options, rf64 = false, sourceEnd = containerEnd,
                    preparedId3 = id3, addId3Chunk = addId3Chunk,
                )
            AudioContainer.RF64 ->
                writeRiff(
                    reader, artworkTags, sink, options, rf64 = true, sourceEnd = containerEnd,
                    preparedId3 = id3, addId3Chunk = addId3Chunk,
                )
            AudioContainer.AIFF ->
                writeAiff(
                    reader, artworkTags, sink, options, sourceEnd = containerEnd,
                    preparedId3 = id3, addId3Chunk = addId3Chunk,
                )
            AudioContainer.FLAC -> io.github.yuroyami.audiokodec.internal.flac.FlacTagWriter.write(
                reader,
                artworkTags,
                sink,
                reader.options,
                includeTags = options.formats == null ||
                    TagFormat.VORBIS_COMMENT in options.formats,
                audioEnd = containerEnd,
                chaptersPreservedElsewhere = willWriteId3,
            )
            AudioContainer.OGG -> io.github.yuroyami.audiokodec.internal.ogg.OggTagWriter.write(
                reader, artworkTags, sink, reader.options, sourceEnd = containerEnd,
                chaptersPreservedElsewhere = willWriteId3,
            )
            null,
            AudioContainer.MPEG_AUDIO,
            AudioContainer.ADTS,
            AudioContainer.AC3,
            AudioContainer.DTS,
            -> writeAroundAudio(reader, existing, artworkTags, sink, options, ape, id3)
            else -> throw UnsupportedFeatureException(
                "writing tags into ${container.displayName}",
                "AudioKodec cannot write tags into a ${container.displayName} file yet. " +
                "It can read them.",
            )
        }
        if (containerHasSeparateMetadata && containerEnd >= 0) {
            writeExternalTrailing(
                reader,
                externalTrailing,
                artworkTags,
                sink,
                options,
                suffixStart = containerEnd,
                sourceEnd = if (writerSourceEnd >= 0) writerSourceEnd else Long.MAX_VALUE,
                ape = ape,
            )
        }
    }

    /**
     * Writes a file whose tags sit in front of and behind the audio.
     *
     * That is MP3 and every other bare codec stream. The audio is whatever
     * lies between the leading blocks and the trailing ones.
     */
    private fun writeAroundAudio(
        reader: SourceReader,
        existing: List<io.github.yuroyami.audiokodec.tag.RawTagBlock>,
        tags: TagBundle,
        sink: AudioSink,
        options: TagWriteOptions,
        ape: ByteArray?,
        id3: ByteArray?,
    ) {
        val size = reader.size
        val spans = tagSpans(reader, existing)

        var audioStart = 0L
        var audioEnd = if (size >= 0) size else Long.MAX_VALUE

        // Walk from each edge. A file commonly ends in APE, Lyrics3 and ID3v1
        // blocks back to back; looking at them from the front would only peel
        // off the last one because the earlier blocks do not themselves reach
        // the end of the file.
        for (span in spans) {
            if (span.offset <= audioStart && span.end > audioStart) {
                audioStart = span.end
            }
        }
        for (span in spans.asReversed()) {
            if (span.offset <= audioEnd && span.end >= audioEnd) {
                audioEnd = span.offset
            }
        }
        if (audioEnd < audioStart) audioEnd = audioStart

        if (id3 != null) sink.write(id3)
        copy(reader, sink, audioStart, audioEnd)

        if (ape != null) sink.write(ape)

        // Formats without a writer are retained byte-for-byte. In particular,
        // an unrelated title edit must not erase a Lyrics3 block.
        for (span in spans) {
            if (span.block.format == TagFormat.LYRICS3) {
                copy(reader, sink, span.offset, span.end)
            }
        }

        // An ID3v1 block is updated when the file already had one, and never
        // created. Writing a truncated copy of a tag the file already holds in
        // full helps nobody.
        val hadId3v1 = existing.any { it.format == TagFormat.ID3V1 }
        if (hadId3v1 && options.keepId3v1) {
            sink.write(id3v1For(tags))
        }
    }

    private class TagSpan(
        val block: io.github.yuroyami.audiokodec.tag.RawTagBlock,
        val offset: Long,
        val end: Long,
    )

    private fun tagSpans(
        reader: SourceReader,
        blocks: List<io.github.yuroyami.audiokodec.tag.RawTagBlock>,
    ): List<TagSpan> = blocks.map { block ->
        if (block.offset < reader.baseOffset) {
            throw CorruptStreamException(
                "tag offset ${block.offset} precedes this source view at ${reader.baseOffset}",
                block.offset,
            )
        }
        val offset = block.offset - reader.baseOffset
        val end = if (block.size > Long.MAX_VALUE - offset) {
            Long.MAX_VALUE
        } else {
            offset + block.size
        }
        TagSpan(block, offset, end)
    }.sortedBy { it.offset }

    /**
     * Recognised tag blocks appended after a self-contained container.
     *
     * RIFF and AIFF state their own end, while FLAC and Ogg have explicit end
     * markers. These legacy blocks can legally follow that end. They must not
     * be copied as audio or parsed as chunks during a metadata rewrite.
     */
    private fun externalTrailingSpans(
        reader: SourceReader,
        blocks: List<io.github.yuroyami.audiokodec.tag.RawTagBlock>,
        declaredEnd: Long?,
    ): List<TagSpan> {
        if (reader.size < 0) return emptyList()
        val candidates = tagSpans(reader, blocks).filter {
            (declaredEnd == null || it.offset >= declaredEnd) &&
                (it.block.format == TagFormat.APEV1 ||
                    it.block.format == TagFormat.APEV2 ||
                    it.block.format == TagFormat.LYRICS3 ||
                    it.block.format == TagFormat.ID3V1 ||
                    isId3v2(it.block.format))
        }
        var end = reader.size
        val reversed = ArrayList<TagSpan>()
        for (span in candidates.asReversed()) {
            if (span.offset <= end && span.end >= end) {
                reversed.add(span)
                end = span.offset
            }
        }
        reversed.reverse()
        return reversed
    }

    private fun isId3v2(format: TagFormat): Boolean =
        format == TagFormat.ID3V2_2 ||
            format == TagFormat.ID3V2_3 ||
            format == TagFormat.ID3V2_4

    /**
     * End stated by a chunked container, relative to [reader].
     *
     * This separates an appended ID3v2 footer from an ID3 chunk that happens
     * to be the final chunk inside RIFF or AIFF.
     */
    private fun declaredContainerEnd(
        reader: SourceReader,
        container: AudioContainer?,
    ): Long? {
        return when (container) {
            AudioContainer.WAV -> {
                reader.seek(4)
                val size = reader.readU32Le()
                if (size < 4L) {
                    throw CorruptStreamException("a RIFF size must include its four-byte form type", 4)
                }
                size + 8L
            }
            AudioContainer.RF64 -> {
                reader.seek(12)
                if (reader.readFourCc() != "ds64") {
                    throw CorruptStreamException("an RF64 or BW64 file must put ds64 first", 12)
                }
                val ds64Size = reader.readU32Le()
                if (ds64Size < 28L) {
                    throw CorruptStreamException("an RF64 ds64 chunk is at least 28 bytes", 12)
                }
                val size = reader.readI64Le()
                if (size < 4L || size > Long.MAX_VALUE - 8L) {
                    throw CorruptStreamException("the RF64 ds64 RIFF size is invalid", 20)
                }
                size + 8L
            }
            AudioContainer.AIFF -> {
                reader.seek(4)
                val size = reader.readU32Be()
                if (size < 4L) {
                    throw CorruptStreamException("an AIFF FORM size must include its form type", 4)
                }
                size + 8L
            }
            else -> null
        }
    }

    /** Re-emits appended legacy tags and preserves every unknown suffix byte. */
    private fun writeExternalTrailing(
        reader: SourceReader,
        spans: List<TagSpan>,
        tags: TagBundle,
        sink: AudioSink,
        options: TagWriteOptions,
        suffixStart: Long,
        sourceEnd: Long,
        ape: ByteArray?,
    ) {
        var cursor = suffixStart
        var wroteApe = false
        for (span in spans) {
            if (span.end <= cursor) continue
            if (span.offset < cursor) {
                throw CorruptStreamException(
                    "an appended ${span.block.format.displayName} block overlaps the container",
                    reader.absoluteOffset(span.offset),
                )
            }
            copy(reader, sink, cursor, span.offset)
            when (span.block.format) {
                TagFormat.APEV1, TagFormat.APEV2 -> {
                    if (ape != null && !wroteApe) {
                        sink.write(ape)
                        wroteApe = true
                    }
                }
                TagFormat.LYRICS3 -> copy(reader, sink, span.offset, span.end)
                TagFormat.ID3V1 -> {
                    if (options.keepId3v1) sink.write(id3v1For(tags))
                }
                // Existing external ID3v2 is either replaced by the leading
                // block written above or by an explicitly requested native
                // RIFF/AIFF chunk.
                TagFormat.ID3V2_2, TagFormat.ID3V2_3, TagFormat.ID3V2_4 -> Unit
                else -> copy(reader, sink, span.offset, span.end)
            }
            cursor = span.end
        }
        copy(reader, sink, cursor, sourceEnd)
    }

    /** Rewrites a RIFF file, replacing its metadata chunks. */
    private fun writeRiff(
        reader: SourceReader,
        tags: TagBundle,
        sink: AudioSink,
        options: TagWriteOptions,
        rf64: Boolean,
        sourceEnd: Long,
        preparedId3: ByteArray?,
        addId3Chunk: Boolean,
    ) {
        val size = sourceEnd
        reader.seek(0)
        val riffId = reader.readFourCc()
        reader.readU32Le()
        val formType = reader.readFourCc()
        if (rf64 && riffId != "RF64" && riffId != "BW64") {
            throw CorruptStreamException("expected an RF64 or BW64 header, found '$riffId'", 0)
        }

        val info = if (options.formats == null || TagFormat.RIFF_INFO in options.formats) {
            buildRiffInfo(tags, valuesPreservedInId3 = preparedId3 != null)
        } else {
            null
        }
        val explicitlyWantsId3 = options.formats?.let {
            TagFormat.ID3V2_4 in it || TagFormat.ID3V2_3 in it
        } == true

        // The header is written once the body is measured, so the sizes are
        // right even on a sink that cannot be patched.
        val kept = ArrayList<LongRange>()
        var ds64: Ds64Rewrite? = null
        var hadId3Chunk = false
        reader.seek(12)
        while (true) {
            if (size >= 0) {
                val left = size - reader.position
                if (left == 0L) break
                if (left < 0L) {
                    throw CorruptStreamException(
                        "the RIFF chunk walk passed the declared form end",
                        reader.position,
                    )
                }
                if (left < 8L) {
                    throw TruncatedStreamException(
                        "the RIFF form ends with only $left bytes of a chunk header",
                        reader.position,
                    )
                }
            } else if (reader.exhausted()) {
                break
            }
            val id = reader.readFourCc()
            val chunkStart = reader.position - 4
            val storedSize = reader.readU32Le()
            val bodyStart = reader.position
            var tableEntry: Ds64Entry? = null
            val chunkSize = if (rf64 && storedSize == UINT32_MAX) {
                val sizes = ds64 ?: throw CorruptStreamException(
                    "RF64 chunk '$id' uses a placeholder before a ds64 chunk", chunkStart,
                )
                if (id == "data") {
                    sizes.dataSize
                } else {
                    sizes.take(id)?.also { tableEntry = it }?.size
                        ?: throw CorruptStreamException(
                            "RF64 chunk '$id' uses a placeholder with no ds64 table entry",
                            chunkStart,
                        )
                }
            } else {
                storedSize
            }
            if (chunkSize > Long.MAX_VALUE - bodyStart) {
                throw CorruptStreamException("RIFF chunk '$id' size overflows its offset", chunkStart)
            }
            var next = bodyStart + chunkSize
            if (chunkSize % 2 == 1L) {
                if (next == Long.MAX_VALUE) {
                    throw CorruptStreamException("RIFF chunk '$id' padding overflows", chunkStart)
                }
                next++
            }
            if (next <= chunkStart || size >= 0 && next > size) {
                throw TruncatedStreamException(
                    "RIFF chunk '$id' declares $chunkSize bytes past the end of the file",
                    chunkStart,
                )
            }

            if (id == "ds64") {
                if (!rf64) {
                    throw CorruptStreamException("a RIFF file contains an RF64 ds64 chunk", chunkStart)
                }
                if (ds64 != null) {
                    throw CorruptStreamException("an RF64 file contains more than one ds64 chunk", chunkStart)
                }
                ds64 = readDs64ForRewrite(reader, chunkSize, chunkStart)
                reader.seek(next)
                if (size in 1..next) break
                continue
            }

            val drop = when (id) {
                "id3 ", "ID3 " -> {
                    hadId3Chunk = true
                    true
                }
                "LIST" -> {
                    val listType = if (chunkSize >= 4) reader.readFourCc() else ""
                    listType == "INFO"
                }
                else -> false
            }
            if (!drop) {
                kept.add(chunkStart until next)
            } else {
                tableEntry?.keep = false
            }

            reader.seek(next)
            if (size in 1..next) break
        }
        if (rf64 && ds64 == null) {
            throw CorruptStreamException("an RF64 file has no ds64 chunk", 12)
        }
        val id3 = if (explicitlyWantsId3 || options.formats == null && hadId3Chunk || addId3Chunk) {
            // An empty model is a request to remove the tag. Unknown raw ID3
            // frames still make this larger than the ten-byte header and are
            // therefore retained.
            preparedId3?.takeIf { it.size > 10 }
        } else {
            null
        }

        var body = 4L
        val ds64Entries = ds64?.entries?.filter { it.keep }.orEmpty()
        if (rf64) {
            body = checkedAdd(
                body,
                36L + ds64Entries.size.toLong() * 12L,
                "RF64 rewrite",
            )
        }
        for (range in kept) {
            body = checkedAdd(body, range.last + 1 - range.first, "RIFF rewrite")
        }
        if (info != null) body = checkedAdd(body, info.size.toLong(), "RIFF rewrite")
        if (id3 != null) {
            body = checkedAdd(
                body,
                8L + id3.size + (id3.size % 2),
                "RIFF rewrite",
            )
        }
        if (!rf64 && body > UINT32_MAX) {
            throw UnsupportedFeatureException(
                "a RIFF tag edit that grows past 4 GiB",
                "the rewritten RIFF file needs RF64 sizes; write it as RF64 before adding these tags",
            )
        }

        val writer = io.github.yuroyami.audiokodec.internal.io.SinkWriter(sink)
        writer.writeFourCc(riffId)
        writer.writeU32Le(if (rf64) UINT32_MAX else body)
        writer.writeFourCc(formType)
        ds64?.let { sizes ->
            writer.writeFourCc("ds64")
            writer.writeU32Le(28L + ds64Entries.size.toLong() * 12L)
            writer.writeI64Le(body)
            writer.writeI64Le(sizes.dataSize)
            writer.writeI64Le(sizes.sampleCount)
            writer.writeU32Le(ds64Entries.size.toLong())
            for (entry in ds64Entries) {
                writer.writeFourCc(entry.id)
                writer.writeI64Le(entry.size)
            }
        }
        for (range in kept) {
            copy(reader, sink, range.first, range.last + 1)
        }
        info?.let { sink.write(it) }
        id3?.let {
            writer.writeFourCc("id3 ")
            writer.writeU32Le(it.size.toLong())
            sink.write(it)
            if (it.size % 2 == 1) writer.writeU8(0)
        }
    }

    private class Ds64Entry(
        val id: String,
        val size: Long,
        var keep: Boolean = true,
        var used: Boolean = false,
    )

    private class Ds64Rewrite(
        val dataSize: Long,
        val sampleCount: Long,
        val entries: List<Ds64Entry>,
    ) {
        fun take(id: String): Ds64Entry? {
            val entry = entries.firstOrNull { !it.used && it.id == id } ?: return null
            entry.used = true
            return entry
        }
    }

    /** Reads every field needed to rebuild an RF64 ds64 chunk after an edit. */
    private fun readDs64ForRewrite(
        reader: SourceReader,
        chunkSize: Long,
        at: Long,
    ): Ds64Rewrite {
        if (chunkSize < 28L) {
            throw CorruptStreamException("an RF64 ds64 chunk is at least 28 bytes", at)
        }
        reader.readI64Le() // The old RIFF size is replaced after measuring the output.
        val dataSize = reader.readI64Le()
        val sampleCount = reader.readI64Le()
        if (dataSize < 0 || sampleCount < 0) {
            throw CorruptStreamException(
                "an RF64 ds64 data size or sample count exceeds the signed 64-bit range",
                at,
            )
        }
        val count = reader.readU32Le()
        val available = (chunkSize - 28L) / 12L
        if (count > available || count > Int.MAX_VALUE) {
            throw CorruptStreamException(
                "an RF64 ds64 chunk declares $count table entries but holds $available",
                at,
            )
        }
        reader.options.checkAllocation(count * 24L, "the RF64 ds64 size table")
        val entries = ArrayList<Ds64Entry>(count.toInt())
        repeat(count.toInt()) {
            val id = reader.readFourCc()
            val entrySize = reader.readI64Le()
            if (entrySize < 0) {
                throw CorruptStreamException("RF64 ds64 size for '$id' is negative", at)
            }
            entries.add(Ds64Entry(id, entrySize))
        }
        return Ds64Rewrite(
            dataSize = dataSize,
            sampleCount = sampleCount,
            entries = entries,
        )
    }

    /**
     * Builds a `LIST INFO` chunk, or returns null when there is nothing to say.
     *
     * Modelled fields come from the edited view. Entries AudioKodec has no
     * field for are carried over from the INFO list the file already had, the
     * way the other writers carry their unknown entries, so an unrelated edit
     * does not delete them.
     */
    internal fun buildRiffInfo(tags: TagBundle, valuesPreservedInId3: Boolean = false): ByteArray? {
        if (!valuesPreservedInId3) NativeTextCapabilities.requireComplete(tags, TagFormat.RIFF_INFO)
        val entries = ArrayList<Pair<String, ByteArray>>()
        for ((field, values) in tags.fields) {
            val key = TagMapping.riffInfoKeyFor(field) ?: continue
            for (value in values) {
                if (value.isNotEmpty() && NativeTextCapabilities.fits(field, value)) {
                    entries.add(key to TagText.encodeLatin1(value))
                }
            }
        }
        for (block in tags.raw.blocksOf(TagFormat.RIFF_INFO)) {
            for (entry in block.entries) {
                if (TagMapping.fieldFor(TagFormat.RIFF_INFO, entry) != null) continue
                val key = entry.key
                if (key.length != 4 || key.any { it.code !in 0x20..0x7E }) continue
                if (entries.any { it.first == key }) continue
                val value = entry.text?.takeIf { it.isNotEmpty() } ?: continue
                entries.add(key to TagText.encodeLatin1(value))
            }
        }
        if (entries.isEmpty()) return null

        var body = 4L
        for ((_, value) in entries) {
            body = checkedAdd(
                body,
                9L + value.size + (value.size + 1) % 2,
                "RIFF INFO chunk",
            )
        }
        if (body > UINT32_MAX || body > Int.MAX_VALUE - 8L) {
            throw AudioKodecException("a RIFF INFO chunk is too large to represent")
        }
        val out = ByteArray(8 + body.toInt())
        var position = 0
        fun ascii(text: String) {
            for (c in text) out[position++] = c.code.toByte()
        }
        fun u32(value: Int) {
            out[position++] = value.toByte()
            out[position++] = (value ushr 8).toByte()
            out[position++] = (value ushr 16).toByte()
            out[position++] = (value ushr 24).toByte()
        }
        ascii("LIST")
        u32(body.toInt())
        ascii("INFO")
        for ((key, value) in entries) {
            ascii(key)
            // Every value is null terminated, and the chunk is padded to an
            // even length.
            val stated = value.size + 1
            u32(stated)
            value.copyInto(out, position)
            position += value.size
            out[position++] = 0
            if (stated % 2 == 1) out[position++] = 0
        }
        return out.copyOf(position)
    }

    /**
     * Builds repeated annotations and singular native title, author and copyright chunks.
     * Values beyond native text's limits need a complete ID3 carrier in the same file.
     */
    internal fun buildAiffTextChunks(tags: TagBundle, valuesPreservedInId3: Boolean = false): List<ByteArray> {
        if (!valuesPreservedInId3) NativeTextCapabilities.requireComplete(tags, TagFormat.AIFF_TEXT)
        val text = ArrayList<ByteArray>()
        for ((field, values) in tags.fields) {
            val id = TagMapping.aiffChunkFor(field) ?: continue
            for (value in values) {
                if (value.isEmpty() || !NativeTextCapabilities.fits(field, value)) continue
                val encoded = TagText.encodeLatin1(value)
                val chunkSize = 8L + encoded.size + encoded.size % 2
                if (chunkSize > Int.MAX_VALUE) {
                    throw AudioKodecException("an AIFF text chunk is too large for a Kotlin array")
                }
                val chunk = ByteArray(chunkSize.toInt())
                for (i in 0 until 4) chunk[i] = id[i].code.toByte()
                chunk[4] = (encoded.size ushr 24).toByte()
                chunk[5] = (encoded.size ushr 16).toByte()
                chunk[6] = (encoded.size ushr 8).toByte()
                chunk[7] = encoded.size.toByte()
                encoded.copyInto(chunk, 8)
                text.add(chunk)
                if (id != "ANNO") break
            }
        }
        return text
    }

    /** Rewrites an AIFF file, replacing its text chunks. */
    private fun writeAiff(
        reader: SourceReader,
        tags: TagBundle,
        sink: AudioSink,
        options: TagWriteOptions,
        sourceEnd: Long,
        preparedId3: ByteArray?,
        addId3Chunk: Boolean,
    ) {
        val size = sourceEnd
        reader.seek(0)
        reader.readFourCc()
        reader.readU32Be()
        val formType = reader.readFourCc()

        val text = if (options.formats == null || TagFormat.AIFF_TEXT in options.formats) {
            buildAiffTextChunks(tags, valuesPreservedInId3 = preparedId3 != null)
        } else {
            emptyList()
        }
        val explicitlyWantsId3 = options.formats?.let {
            TagFormat.ID3V2_4 in it || TagFormat.ID3V2_3 in it
        } == true

        val kept = ArrayList<LongRange>()
        var hadId3Chunk = false
        reader.seek(12)
        while (true) {
            if (size >= 0) {
                val left = size - reader.position
                if (left == 0L) break
                if (left < 0L) {
                    throw CorruptStreamException(
                        "the AIFF chunk walk passed the declared form end",
                        reader.position,
                    )
                }
                if (left < 8L) {
                    throw TruncatedStreamException(
                        "the AIFF form ends with only $left bytes of a chunk header",
                        reader.position,
                    )
                }
            } else if (reader.exhausted()) {
                break
            }
            val id = reader.readFourCc()
            val chunkStart = reader.position - 4
            val chunkSize = reader.readU32Be()
            val bodyStart = reader.position
            if (chunkSize > Long.MAX_VALUE - bodyStart) {
                throw CorruptStreamException(
                    "AIFF chunk '$id' size overflows its offset",
                    chunkStart,
                )
            }
            var next = bodyStart + chunkSize
            if (chunkSize % 2 == 1L) {
                if (next == Long.MAX_VALUE) {
                    throw CorruptStreamException(
                        "AIFF chunk '$id' padding overflows",
                        chunkStart,
                    )
                }
                next++
            }
            if (next <= chunkStart) {
                throw CorruptStreamException("AIFF chunk '$id' does not advance", chunkStart)
            }
            if (size >= 0 && next > size) {
                throw TruncatedStreamException(
                    "AIFF chunk '$id' declares $chunkSize bytes past end of file",
                    chunkStart,
                )
            }

            val isId3 = id == "ID3 " || id == "id3 "
            if (isId3) hadId3Chunk = true
            val drop = id == "NAME" || id == "AUTH" || id == "(c) " || id == "ANNO" || isId3
            if (!drop) kept.add(chunkStart until next)

            reader.seek(next)
            if (size in 1..next) break
        }
        val id3 = if (explicitlyWantsId3 || options.formats == null && hadId3Chunk || addId3Chunk) {
            preparedId3?.takeIf { it.size > 10 }
        } else {
            null
        }

        var body = 4L
        for (range in kept) {
            body = checkedAdd(body, range.last + 1 - range.first, "AIFF rewrite")
        }
        for (chunk in text) body = checkedAdd(body, chunk.size.toLong(), "AIFF rewrite")
        if (id3 != null) {
            body = checkedAdd(
                body,
                8L + id3.size + (id3.size % 2),
                "AIFF rewrite",
            )
        }
        if (body > UINT32_MAX) {
            throw UnsupportedFeatureException(
                "an AIFF tag edit that grows past 4 GiB",
                "AIFF has no 64-bit size extension for this rewritten file",
            )
        }

        val writer = io.github.yuroyami.audiokodec.internal.io.SinkWriter(sink)
        writer.writeFourCc("FORM")
        writer.writeU32Be(body)
        writer.writeFourCc(formType)
        for (range in kept) {
            copy(reader, sink, range.first, range.last + 1)
        }
        for (chunk in text) sink.write(chunk)
        id3?.let {
            writer.writeFourCc("ID3 ")
            writer.writeU32Be(it.size.toLong())
            sink.write(it)
            if (it.size % 2 == 1) writer.writeU8(0)
        }
    }

    private fun id3v1For(tags: TagBundle): ByteArray = Id3v1.write(
        title = tags[TagField.TITLE],
        artist = tags[TagField.ARTIST],
        album = tags[TagField.ALBUM],
        year = tags[TagField.DATE]?.take(4),
        comment = tags[TagField.COMMENT],
        track = tags.number(TagField.TRACK_NUMBER),
        genre = tags[TagField.GENRE],
    )

    private fun requestedId3Version(options: TagWriteOptions): Int {
        val requested = options.formats ?: return options.id3Version
        return when {
            TagFormat.ID3V2_3 in requested -> 3
            TagFormat.ID3V2_4 in requested -> 4
            else -> options.id3Version
        }
    }

    private fun validateRequestedFormats(
        container: AudioContainer?,
        requested: Set<TagFormat>?,
    ) {
        if (requested == null) return
        val allowed = when (container) {
            AudioContainer.WAV, AudioContainer.RF64 ->
                setOf(TagFormat.RIFF_INFO, TagFormat.ID3V2_3, TagFormat.ID3V2_4)
            AudioContainer.AIFF ->
                setOf(TagFormat.AIFF_TEXT, TagFormat.ID3V2_3, TagFormat.ID3V2_4)
            AudioContainer.FLAC, AudioContainer.OGG ->
                setOf(TagFormat.VORBIS_COMMENT)
            null,
            AudioContainer.MPEG_AUDIO,
            AudioContainer.ADTS,
            AudioContainer.AC3,
            AudioContainer.DTS,
            -> setOf(TagFormat.ID3V2_3, TagFormat.ID3V2_4, TagFormat.APEV2)
            else -> return
        }
        val unsupported = requested - allowed
        if (unsupported.isNotEmpty()) {
            val where = container?.displayName ?: "a bare codec stream"
            throw UnsupportedFeatureException(
                "writing ${unsupported.joinToString { it.displayName }} into $where",
                "$where supports ${allowed.joinToString { it.displayName }} tag output here",
            )
        }
        if (container == AudioContainer.OGG && TagFormat.VORBIS_COMMENT !in requested) {
            throw UnsupportedFeatureException(
                "removing the mandatory Ogg comment packet",
                "Vorbis, Opus, Speex and Ogg FLAC mappings require their comment header; " +
                    "request VORBIS_COMMENT when rewriting an Ogg file",
            )
        }
    }

    /** Copies `[from, to)` from the source to the sink, unchanged. */
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
                    throw TruncatedStreamException(
                        "the source ended with $left bytes still to copy",
                        reader.position,
                    )
                }
                break
            }
            sink.write(buffer, 0, got)
            left -= got
        }
    }

    private fun checkedAdd(left: Long, right: Long, what: String): Long {
        if (right < 0 || left > Long.MAX_VALUE - right) {
            throw AudioKodecException("$what size overflows 64 bits")
        }
        return left + right
    }
}

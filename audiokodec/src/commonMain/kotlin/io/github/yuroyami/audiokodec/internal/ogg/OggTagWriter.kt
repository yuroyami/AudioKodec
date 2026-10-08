package io.github.yuroyami.audiokodec.internal.ogg

import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.AudioSink
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.tag.FlacPicture
import io.github.yuroyami.audiokodec.internal.tag.VorbisComment
import io.github.yuroyami.audiokodec.tag.TagBundle

/**
 * Rewrites the comment header of an Ogg file.
 *
 * This is more involved than editing a tag block in a flat file, because the
 * new comment is almost never the same length as the old one, and an Ogg page
 * cannot simply grow: its size is fixed by its segment table, its checksum
 * covers the whole page, and every page after it carries a sequence number that
 * has to stay consecutive.
 *
 * So the header pages are rebuilt from their packets, and the audio pages are
 * copied with new sequence numbers and fresh checksums. The audio packets
 * themselves are never touched, which is the property that matters: editing a
 * title must not re-encode anything.
 *
 * A file carrying more than one logical stream at once is refused. Repaginating
 * one stream of an interleaved file would mean rebuilding the interleaving, and
 * getting that subtly wrong would break playback in ways a caller could not
 * see.
 */
internal object OggTagWriter {

    /** Bytes of payload a rebuilt page carries at most. */
    private const val TARGET_PAGE_PAYLOAD = 4096

    /** Validates the copy boundary before any container or external tag output. */
    fun prepare(reader: SourceReader, options: DecodeOptions): OggAnalyser.Parsed {
        val parsed = OggAnalyser.parse(reader, options)
        val stream = parsed.audio ?: throw UnsupportedFeatureException(
            "writing tags into an Ogg file with no recognised audio",
            "this Ogg file carries no audio stream AudioKodec recognises",
        )
        if (parsed.streams.size > 1) {
            throw UnsupportedFeatureException(
                "writing tags into a multiplexed Ogg file",
                "this Ogg file interleaves ${parsed.streams.size} logical streams, and " +
                    "AudioKodec only rewrites the tags of a file that carries one",
            )
        }
        if (!parsed.headersEndAtPageBoundary) {
            throw UnsupportedFeatureException(
                "writing Ogg tags when audio shares the final header page",
                "the last ${stream.codecName} header shares its Ogg page with audio; " +
                    "rewriting the headers would discard those packets",
            )
        }
        return parsed
    }

    fun write(
        reader: SourceReader,
        tags: TagBundle,
        sink: AudioSink,
        options: DecodeOptions,
        sourceEnd: Long = reader.size,
        chaptersPreservedElsewhere: Boolean = false,
        prepared: OggAnalyser.Parsed? = null,
    ) {
        val parsed = prepared ?: prepare(reader, options)
        val stream = parsed.audio ?: throw CorruptStreamException("the prepared Ogg stream has no audio")

        val serial = stream.serialNumber

        // The analyser already read and validated every header packet. Reusing
        // those exact bytes avoids a second packet walk finding a different
        // boundary in tolerant mode.
        val headers = ArrayList<ByteArray>(parsed.headerPackets.size)
        for (packet in parsed.headerPackets) headers.add(packet.copyOf())
        if (headers.size != parsed.headerPacketCount || headers.size < 2) {
            throw CorruptStreamException(
                "the Ogg stream does not contain a complete identification and comment header",
                stream.firstPageOffset,
            )
        }

        val commentIndex = commentIndex(stream.codec, headers)
        headers[commentIndex] = buildComment(stream.codec, tags, headers, commentIndex, chaptersPreservedElsewhere)
        if (stream.codec == AudioCodec.FLAC) {
            rebuildFlacPictures(headers, tags)
        }

        val writer = PageWriter(sink, serial)

        // The identification packet has a page to itself, which is what every
        // mapping requires and what a reader looking for it expects.
        writer.writePacket(headers[0], granule = 0, first = true, flush = true)

        for (index in 1 until headers.size) {
            writer.writePacket(headers[index], granule = 0, flush = index == headers.size - 1)
        }
        writer.flush(granule = 0)

        val ogg = OggReader(reader, options)
        copyAudioPages(ogg, reader, serial, parsed.audioStartOffset, sourceEnd, writer)
    }

    /** Which header packet holds the comments, for each mapping. */
    private fun commentIndex(codec: AudioCodec, headers: List<ByteArray>): Int {
        val packet = headers.getOrNull(1) ?: throw CorruptStreamException(
            "the Ogg stream is missing its comment packet",
        )
        val valid = when (codec) {
            AudioCodec.VORBIS ->
                packet.size >= 7 &&
                    packet[0].toInt() and 0xFF == 0x03 &&
                    matches(packet, 1, "vorbis")
            AudioCodec.OPUS -> matches(packet, 0, "OpusTags")
            AudioCodec.FLAC -> packet.isNotEmpty() && packet[0].toInt() and 0x7F == 4
            AudioCodec.SPEEX -> packet.size >= 8
            else -> false
        }
        if (!valid) {
            throw CorruptStreamException(
                "the second ${codec.name} packet is not its required comment header",
            )
        }
        return 1
    }

    private fun buildComment(
        codec: AudioCodec,
        tags: TagBundle,
        headers: List<ByteArray>,
        commentIndex: Int,
        chaptersPreservedElsewhere: Boolean,
    ): ByteArray {
        val vendor = existingVendor(codec, headers, commentIndex)
        val body = VorbisComment.build(
            tags,
            // Ogg FLAC has native PICTURE metadata packets. The other Ogg
            // mappings carry pictures as base64 comments.
            includePictures = codec != AudioCodec.FLAC,
            chaptersPreservedElsewhere = chaptersPreservedElsewhere,
            preservedVendor = vendor,
        )
        val suffix = existingCommentSuffix(codec, headers, commentIndex)
        return when (codec) {
            AudioCodec.VORBIS -> {
                // A Vorbis comment header is a type byte, the signature, the
                // comment structure, and a framing bit that must be set.
                val payloadSize = checkedCombinedSize(body.size, suffix.size, "Vorbis comment data")
                val out = ByteArray(checkedArraySize(payloadSize, 8, "Vorbis comment packet"))
                out[0] = 0x03
                "vorbis".forEachIndexed { i, c -> out[1 + i] = c.code.toByte() }
                body.copyInto(out, 7)
                suffix.copyInto(out, 7 + body.size)
                out[out.size - 1] = 1
                out
            }
            AudioCodec.OPUS -> {
                val payloadSize = checkedCombinedSize(body.size, suffix.size, "OpusTags data")
                val out = ByteArray(checkedArraySize(payloadSize, 8, "OpusTags packet"))
                "OpusTags".forEachIndexed { i, c -> out[i] = c.code.toByte() }
                body.copyInto(out, 8)
                suffix.copyInto(out, 8 + body.size)
                out
            }
            AudioCodec.FLAC -> {
                // Inside Ogg the comment is an ordinary FLAC metadata block.
                // Preserve whether the original block ended the metadata
                // chain; not every encoder writes a padding block after it.
                val payloadSize = checkedCombinedSize(body.size, suffix.size, "Ogg FLAC comment data")
                if (payloadSize > 0xFF_FFFF) {
                    throw UnsupportedFeatureException(
                        "an Ogg FLAC comment larger than 16,777,215 bytes",
                        "FLAC metadata lengths are 24-bit, but these tags need $payloadSize bytes",
                    )
                }
                val out = ByteArray(checkedArraySize(payloadSize, 4, "Ogg FLAC comment packet"))
                out[0] = ((headers[commentIndex][0].toInt() and 0x80) or 4).toByte()
                out[1] = (payloadSize ushr 16).toByte()
                out[2] = (payloadSize ushr 8).toByte()
                out[3] = payloadSize.toByte()
                body.copyInto(out, 4)
                suffix.copyInto(out, 4 + body.size)
                out
            }
            // Speex puts a bare comment structure in its second packet.
            else -> {
                val payloadSize = checkedCombinedSize(body.size, suffix.size, "Speex comment data")
                ByteArray(payloadSize).also {
                    body.copyInto(it, 0)
                    suffix.copyInto(it, body.size)
                }
            }
        }
    }

    /**
     * The vendor string the file already had.
     *
     * It names the program that encoded the audio, which is a fact about the
     * file rather than about its tags, so an edit keeps it.
     */
    private fun existingVendor(
        codec: AudioCodec,
        headers: List<ByteArray>,
        commentIndex: Int,
    ): ByteArray? {
        val packet = headers.getOrNull(commentIndex) ?: return null
        val from = when (codec) {
            AudioCodec.VORBIS -> 7
            AudioCodec.OPUS -> 8
            AudioCodec.FLAC -> 4
            else -> 0
        }
        if (from < 0 || from > packet.size - 4) return null
        val length = (packet[from].toInt() and 0xFF) or
            ((packet[from + 1].toInt() and 0xFF) shl 8) or
            ((packet[from + 2].toInt() and 0xFF) shl 16) or
            ((packet[from + 3].toInt() and 0xFF) shl 24)
        val bodyStart = from + 4
        if (length < 0 || length > packet.size - bodyStart) return null
        return packet.copyOfRange(bodyStart, bodyStart + length)
    }

    /** Opaque bytes after the declared comment list, preserved across edits. */
    private fun existingCommentSuffix(
        codec: AudioCodec,
        headers: List<ByteArray>,
        commentIndex: Int,
    ): ByteArray {
        val packet = headers.getOrNull(commentIndex) ?: return ByteArray(0)
        val from: Int
        val to: Int
        when (codec) {
            AudioCodec.VORBIS -> {
                from = 7
                to = packet.size - 1
            }
            AudioCodec.OPUS -> {
                from = 8
                to = packet.size
            }
            AudioCodec.FLAC -> {
                from = 4
                to = packet.size
            }
            else -> {
                from = 0
                to = packet.size
            }
        }
        val end = VorbisComment.contentEnd(packet, from, to) ?: return ByteArray(0)
        return packet.copyOfRange(end, to)
    }

    /**
     * Copies the audio pages, renumbering them.
     *
     * The payload of each page is written out unchanged, so the packets inside
     * are exactly what they were. Only the sequence number and the checksum
     * differ, because the header pages before them are a different length now.
     */
    private fun copyAudioPages(
        ogg: OggReader,
        reader: SourceReader,
        serial: Int,
        from: Long,
        sourceEnd: Long,
        writer: PageWriter,
    ) {
        var at = from
        val size = sourceEnd
        while (true) {
            if (size in 1..at) break
            val page = ogg.pageAt(at) ?: break
            if (page.offset > Long.MAX_VALUE - page.byteLength) {
                throw CorruptStreamException("an Ogg page offset overflows 64 bits", page.offset)
            }
            at = page.offset + page.byteLength
            if (page.serialNumber != serial) {
                // The initial BOS scan found only one stream. Meeting another
                // serial before this one ends means the file is multiplexed in
                // a noncanonical order; silently dropping it would lose data.
                throw UnsupportedFeatureException(
                    "writing tags into a multiplexed Ogg file",
                    "an interleaved Ogg stream with serial ${page.serialNumber} begins after the headers",
                )
            }
            writer.writeRawPage(page)
            if (page.isLast) {
                // Everything after the first logical stream belongs to later
                // chains (or is trailing data). It has no sequence-number
                // relationship with the rebuilt stream, so preserve it byte
                // for byte without even reparsing it.
                writer.copyOriginalRemainder(reader, at, sourceEnd)
                return
            }
        }
        // Rebuilding the headers of an unterminated logical stream would turn
        // a malformed input into another malformed file and, for a bounded
        // source, could silently drop bytes at the rewrite boundary. Tag
        // editing therefore requires the stream's explicit EOS page even when
        // tolerant probing was requested.
        throw TruncatedStreamException(
            "the Ogg logical stream ends before an end-of-stream page",
            at,
        )
    }

    private fun checkedArraySize(bodySize: Int, overhead: Int, what: String): Int {
        if (bodySize < 0 || bodySize > Int.MAX_VALUE - overhead) {
            throw UnsupportedFeatureException(
                "a $what larger than a Kotlin array",
                "$what needs more than ${Int.MAX_VALUE} bytes",
            )
        }
        return bodySize + overhead
    }

    private fun checkedCombinedSize(first: Int, second: Int, what: String): Int {
        if (first < 0 || second < 0 || first > Int.MAX_VALUE - second) {
            throw UnsupportedFeatureException(
                "a $what larger than a Kotlin array",
                "$what needs more than ${Int.MAX_VALUE} bytes",
            )
        }
        return first + second
    }

    /**
     * Replaces Ogg FLAC's native PICTURE packets and repairs its metadata chain.
     *
     * Editing pictures must be able to remove an old cover as well as add one.
     * Leaving the original packet in place while adding a base64 comment would
     * duplicate it and make `removePictures()` ineffective.
     */
    private fun rebuildFlacPictures(headers: MutableList<ByteArray>, tags: TagBundle) {
        val identification = headers.firstOrNull() ?: throw CorruptStreamException(
            "the Ogg FLAC identification packet is missing",
        )
        val metadata = ArrayList<ByteArray>()
        for (index in 1 until headers.size) {
            val packet = headers[index]
            if (packet.isEmpty()) {
                throw CorruptStreamException("an Ogg FLAC metadata packet is empty")
            }
            if (packet[0].toInt() and 0x7F != 6) metadata.add(packet)
        }

        // Keep conventional padding at the end by inserting new pictures just
        // before the trailing run of PADDING blocks.
        var insertAt = metadata.size
        while (insertAt > 1 && metadata[insertAt - 1][0].toInt() and 0x7F == 1) insertAt--
        val pictures = ArrayList<ByteArray>(tags.pictures.size)
        for (picture in tags.pictures) {
            val body = FlacPicture.build(picture)
            if (body.size > 0xFF_FFFF) {
                throw UnsupportedFeatureException(
                    "an Ogg FLAC picture larger than 16,777,215 bytes",
                    "FLAC metadata lengths are 24-bit, but this picture needs ${body.size} bytes",
                )
            }
            val packet = ByteArray(body.size + 4)
            packet[0] = 6
            packet[1] = (body.size ushr 16).toByte()
            packet[2] = (body.size ushr 8).toByte()
            packet[3] = body.size.toByte()
            body.copyInto(packet, 4)
            pictures.add(packet)
        }
        metadata.addAll(insertAt, pictures)

        if (metadata.isEmpty() || metadata[0][0].toInt() and 0x7F != 4) {
            throw CorruptStreamException("the Ogg FLAC metadata chain does not begin with comments")
        }
        if (metadata.size > 0xFFFF) {
            throw UnsupportedFeatureException(
                "more than 65,535 Ogg FLAC metadata packets",
                "the Ogg FLAC header count is 16-bit, but this edit needs ${metadata.size} packets",
            )
        }
        for (index in metadata.indices) {
            metadata[index][0] = (
                (metadata[index][0].toInt() and 0x7F) or
                    if (index == metadata.lastIndex) 0x80 else 0
                ).toByte()
        }

        identification[7] = (metadata.size ushr 8).toByte()
        identification[8] = metadata.size.toByte()
        headers.clear()
        headers.add(identification)
        headers.addAll(metadata)
    }

    private fun matches(bytes: ByteArray, at: Int, text: String): Boolean {
        if (at < 0 || at > bytes.size - text.length) return false
        for (i in text.indices) {
            if ((bytes[at + i].toInt() and 0xFF) != text[i].code) return false
        }
        return true
    }

    /**
     * Emits pages, keeping the sequence numbers consecutive.
     *
     * Ogg requires every page of a logical stream to be numbered in order, and
     * a reader that meets a gap is entitled to treat the stream as damaged. So
     * the counter lives here and every page written goes through it.
     */
    private class PageWriter(private val sink: AudioSink, private val serial: Int) {

        private var sequence = 0
        private val pending = ArrayList<ByteArray>()
        private var pendingBytes = 0L

        /** Queues a packet, flushing when it says so or when a page fills. */
        fun writePacket(packet: ByteArray, granule: Long, first: Boolean = false, flush: Boolean = false) {
            pending.add(packet)
            pendingBytes += packet.size
            if (flush || pendingBytes >= TARGET_PAGE_PAYLOAD.toLong()) {
                flush(granule, first)
            }
        }

        fun flush(granule: Long, first: Boolean = false) {
            if (pending.isEmpty()) return
            // Work one bounded Ogg page at a time. A packet can be close to a
            // Kotlin array's limit; building a second, packet-sized lacing list
            // would turn a harmless tag edit into avoidable memory pressure.
            val lacing = IntArray(255)
            val body = ByteArray(255 * 255)
            var laceCount = 0
            var bodyLength = 0
            var firstOutputPage = true
            var pageContinued = false

            fun emitBuffered(lastPacketFinished: Boolean) {
                emit(
                    flags =
                        (if (first && firstOutputPage) OggPage.FLAG_FIRST else 0) or
                            (if (pageContinued) OggPage.FLAG_CONTINUED else 0),
                    // All supported Ogg mappings require every header page,
                    // including continuation-only pages, to carry granule zero.
                    granule = granule,
                    lacing = lacing.copyOf(laceCount),
                    body = body.copyOf(bodyLength),
                    last = false,
                )
                firstOutputPage = false
                pageContinued = !lastPacketFinished
                laceCount = 0
                bodyLength = 0
            }

            for (packet in pending) {
                var packetOffset = 0
                while (true) {
                    val segment = minOf(255, packet.size - packetOffset)
                    lacing[laceCount++] = segment
                    if (segment > 0) {
                        packet.copyInto(body, bodyLength, packetOffset, packetOffset + segment)
                        bodyLength += segment
                        packetOffset += segment
                    }
                    val packetFinished = segment < 255
                    if (laceCount == 255) emitBuffered(packetFinished)
                    if (packetFinished) break
                }
            }
            if (laceCount > 0) emitBuffered(lastPacketFinished = true)
            pending.clear()
            pendingBytes = 0L
        }

        /** Writes an existing page's contents under a new sequence number. */
        fun writeRawPage(page: OggPage) {
            emit(
                flags = page.flags,
                granule = page.granulePosition,
                lacing = page.segments,
                body = page.payload,
                last = page.isLast,
            )
        }

        /** Copies later chained streams and trailing bytes without changing them. */
        fun copyOriginalRemainder(reader: SourceReader, from: Long, to: Long) {
            if (to >= 0 && to <= from) return
            reader.seek(from)
            val bytes = ByteArray(
                minOf(64L * 1024L, reader.options.maxAllocationBytes, Int.MAX_VALUE.toLong())
                    .toInt()
                    .coerceAtLeast(1),
            )
            var left = if (to < 0) Long.MAX_VALUE else to - from
            while (left > 0) {
                val wanted = minOf(left, bytes.size.toLong()).toInt()
                val count = reader.readSome(bytes, 0, wanted)
                if (count == 0) {
                    if (to >= 0) {
                        throw TruncatedStreamException(
                            "the Ogg source ended with $left bytes still to copy",
                            reader.position,
                        )
                    }
                    return
                }
                sink.write(bytes, 0, count)
                left -= count
            }
        }

        private fun emit(
            flags: Int,
            granule: Long,
            lacing: IntArray,
            body: ByteArray,
            last: Boolean,
        ) {
            val header = ByteArray(OggPage.HEADER_BYTES + lacing.size)
            OggPage.CAPTURE.copyInto(header, 0)
            header[4] = 0
            header[5] = (flags or if (last) OggPage.FLAG_LAST else 0).toByte()
            for (i in 0 until 8) header[6 + i] = (granule ushr (8 * i)).toByte()
            for (i in 0 until 4) header[14 + i] = (serial ushr (8 * i)).toByte()
            for (i in 0 until 4) header[18 + i] = (sequence ushr (8 * i)).toByte()
            // The checksum field stays zero while the checksum is computed.
            header[26] = lacing.size.toByte()
            for (i in lacing.indices) header[OggPage.HEADER_BYTES + i] = lacing[i].toByte()

            val whole = ByteArray(header.size + body.size)
            header.copyInto(whole, 0)
            body.copyInto(whole, header.size)
            val crc = OggCrc.of(whole, 0, whole.size)
            for (i in 0 until 4) whole[22 + i] = (crc ushr (8 * i)).toByte()

            sink.write(whole)
            sequence++
        }
    }
}

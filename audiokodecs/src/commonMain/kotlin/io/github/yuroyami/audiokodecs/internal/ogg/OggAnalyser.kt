package io.github.yuroyami.audiokodecs.internal.ogg

import io.github.yuroyami.audiokodecs.AudioCodec
import io.github.yuroyami.audiokodecs.CorruptStreamException
import io.github.yuroyami.audiokodecs.DecodeOptions
import io.github.yuroyami.audiokodecs.UnsupportedFeatureException
import io.github.yuroyami.audiokodecs.UnsupportedFormatException
import io.github.yuroyami.audiokodecs.internal.io.SourceReader
import io.github.yuroyami.audiokodecs.internal.tag.FlacPicture
import io.github.yuroyami.audiokodecs.internal.tag.VorbisComment
import io.github.yuroyami.audiokodecs.tag.RawTagBlock
import io.github.yuroyami.audiokodecs.tag.RawTagEntry
import io.github.yuroyami.audiokodecs.tag.TagFormat

/**
 * Works out what a file's Ogg streams carry, and reads their headers.
 *
 * Ogg says nothing about codecs: it carries packets, and the first packet of a
 * stream is what names the codec inside. Every audio mapping in use starts that
 * packet with a signature, so identifying one is a matter of comparing the
 * first few bytes and then reading a header whose shape the mapping defines.
 *
 * The mappings understood here are Vorbis, Opus, FLAC and Speex. Theora and
 * the other video mappings are recognised so that a file with a soundtrack can
 * have its audio found rather than its video mistaken for audio.
 */
internal object OggAnalyser {

    /** What one logical stream in a file carries. */
    class Stream(
        val serialNumber: Int,
        /** Byte offset of the stream's first page. */
        val firstPageOffset: Long,
        val codec: AudioCodec,
        /** The mapping's own name, for an error message. */
        val codecName: String,
        val sampleRate: Int,
        val channels: Int,
        /**
         * Samples Opus discards from the front of the stream.
         *
         * Opus starts every stream with a short run of samples the encoder
         * needed and the listener must not hear. Ignoring the value plays a
         * click and shifts every position by a few milliseconds.
         */
        val preSkip: Int,
        /** Opus output gain in decibels, which is almost always zero. */
        val outputGainDb: Double,
        /** True when this stream carries audio AudioKodecs recognises. */
        val isAudio: Boolean,
        /** The identification packet, kept for the decoders that need it. */
        val identification: ByteArray,
    ) {
        /**
         * The rate the codec's granule positions are counted in.
         *
         * Every mapping counts samples, and Opus counts them at 48 kHz
         * whatever the rate the material was recorded at.
         */
        val granuleRate: Int get() = if (codec == AudioCodec.OPUS) 48_000 else sampleRate
    }

    /** The result of looking at a whole file. */
    class Parsed(
        val streams: List<Stream>,
        /** The first stream carrying audio, or null when there is none. */
        val audio: Stream?,
        /** Tag blocks found in the audio stream. */
        val tagBlocks: List<RawTagBlock>,
        /** Frames the audio stream holds, or null when it cannot be worked out. */
        val durationFrames: Long?,
        /** Byte offset of the first page after the header packets. */
        val audioStartOffset: Long,
        /** Byte offset immediately after this logical stream's first EOS page. */
        val streamEndOffset: Long,
        /**
         * The mapping's header packets as they were read.
         *
         * A codec whose whole configuration lives in its headers, which is what
         * Vorbis is, needs them back rather than a summary of them.
         */
        val headerPackets: List<ByteArray>,
        /**
         * How many packets the mapping spends on headers before the audio.
         *
         * A rewriter needs exactly this number: reading one packet too many
         * pulls the first audio frame into the headers, and one too few leaves
         * a header packet in the audio.
         */
        val headerPacketCount: Int,
    )

    fun parse(reader: SourceReader, options: DecodeOptions): Parsed {
        val ogg = OggReader(reader, options)
        val heads = ogg.readStreamHeads()
        if (heads.isEmpty()) {
            throw CorruptStreamException("the file has no Ogg page at its start", 0)
        }

        val streams = heads.map { identify(it) }
        val audio = streams.firstOrNull { it.isAudio }

        if (audio == null) {
            val named = streams.joinToString(", ") { it.codecName }
            throw UnsupportedFormatException(
                io.github.yuroyami.audiokodecs.AudioContainer.OGG,
                "this Ogg file carries $named, and none of it is audio AudioKodecs recognises",
            )
        }
        options.checkStreamShape(audio.sampleRate, audio.channels, audio.firstPageOffset)

        // The header packets come before any audio, and how many there are
        // depends on the mapping.
        val packets = ogg.packets(audio.serialNumber, audio.firstPageOffset)
        val headerPackets = ArrayList<ByteArray>()
        val flacPictureBlocks = ArrayList<RawTagBlock>()
        var tagBlock: RawTagBlock? = null

        val fixedHeaderCount = when (audio.codec) {
            AudioCodec.VORBIS -> 3
            AudioCodec.OPUS -> 2
            AudioCodec.SPEEX -> speexHeaderCount(audio.identification, options, audio.firstPageOffset)
            AudioCodec.FLAC -> declaredFlacHeaderCount(audio.identification)
            else -> 2
        }
        fixedHeaderCount?.let {
            options.checkAllocation(
                it.toLong() * 32L,
                "remembering $it Ogg codec header packets",
            )
        }

        var read = 0
        var retainedBytes = 0L
        var lastHeaderPacket: OggReader.Packet? = null
        while (fixedHeaderCount == null || read < fixedHeaderCount) {
            val packet = packets.next() ?: throw io.github.yuroyami.audiokodecs.TruncatedStreamException(
                "the ${audio.codecName} stream ends after $read header packets",
                lastHeaderPacket?.pageEndOffset ?: audio.firstPageOffset,
            )
            if (retainedBytes > Long.MAX_VALUE - packet.length) {
                options.checkAllocation(Long.MAX_VALUE, "retaining Ogg codec headers")
            }
            retainedBytes += packet.length
            options.checkAllocation(retainedBytes, "retaining Ogg codec headers")
            val bytes = packet.bytes.copyOf(packet.length)
            headerPackets.add(bytes)
            lastHeaderPacket = packet
            read++
            when (audio.codec) {
                AudioCodec.VORBIS -> if (read == 2) {
                    tagBlock = vorbisCommentIn(
                        bytes,
                        packet.startOffset,
                        options,
                    ) ?: throw CorruptStreamException(
                        "the second Vorbis packet is not a valid comment header",
                        packet.startOffset,
                    )
                }
                AudioCodec.OPUS -> if (read == 2) {
                    tagBlock = opusTagsIn(
                        bytes,
                        packet.startOffset,
                        options,
                    ) ?: throw CorruptStreamException(
                        "the second Opus packet is not a valid OpusTags header",
                        packet.startOffset,
                    )
                }
                AudioCodec.SPEEX -> if (read == 2) {
                    tagBlock = speexCommentIn(
                        bytes,
                        packet.startOffset,
                        options,
                    ) ?: throw CorruptStreamException(
                        "the second Speex packet is not a valid comment header",
                        packet.startOffset,
                    )
                }
                AudioCodec.FLAC -> {
                    // The first packet is the mapping preamble plus STREAMINFO.
                    // Every packet after it is one native FLAC metadata block.
                    if (read > 1) {
                        validateFlacMetadataPacket(
                            bytes = bytes,
                            packetOffset = packet.startOffset,
                            packetNumber = read,
                            fixedHeaderCount = fixedHeaderCount,
                        )
                        if (read == 2) {
                            tagBlock = VorbisComment.parse(
                                bytes,
                                4,
                                bytes.size,
                                packet.startOffset,
                                bytes.size.toLong(),
                                options,
                            ) ?: throw CorruptStreamException(
                                "the Ogg FLAC comment metadata block is malformed",
                                packet.startOffset,
                            )
                        } else if (bytes[0].toInt() and 0x7F == 6) {
                            val body = bytes.copyOfRange(4, bytes.size)
                            FlacPicture.parse(body, options)?.let { picture ->
                                flacPictureBlocks.add(
                                    RawTagBlock(
                                        TagFormat.VORBIS_COMMENT,
                                        packet.startOffset,
                                        bytes.size.toLong(),
                                        listOf(
                                            RawTagEntry(
                                                "METADATA_BLOCK_PICTURE",
                                                text = picture.mimeType,
                                                binary = body,
                                            ),
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }
                else -> Unit
            }

            // A zero header count in Ogg FLAC means "unknown". In that case the
            // native FLAC last-metadata bit is the authoritative boundary.
            if (audio.codec == AudioCodec.FLAC &&
                fixedHeaderCount == null &&
                read > 1 &&
                bytes[0].toInt() and 0x80 != 0
            ) {
                break
            }
        }

        val lastHeader = lastHeaderPacket ?: throw CorruptStreamException(
            "the ${audio.codecName} stream has no identification packet",
            audio.firstPageOffset,
        )
        if (!lastHeader.endsPage && !options.tolerant) {
            throw CorruptStreamException(
                "the last ${audio.codecName} header does not finish its Ogg page",
                lastHeader.endOffset,
            )
        }
        if (lastHeader.granule != 0L && !options.tolerant) {
            throw CorruptStreamException(
                "the ${audio.codecName} header page has granule position ${lastHeader.granule}, not zero",
                lastHeader.pageEndOffset,
            )
        }
        val audioStart = lastHeader.pageEndOffset

        // The last page of the stream states where it ends, which is the only
        // place an Ogg file records its length.
        val last = ogg.findStreamEndPage(audio.serialNumber, audio.firstPageOffset)
        val granule = last?.granulePosition ?: -1L
        val streamEnd = if (last != null) {
            if (last.offset > Long.MAX_VALUE - last.byteLength) {
                throw CorruptStreamException("an Ogg page offset overflows 64 bits", last.offset)
            }
            last.offset + last.byteLength
        } else {
            audioStart
        }
        val duration = when {
            // A granule of zero means no audio packet has finished yet, which
            // is what the header pages carry. Reading it as a length would say
            // a file is empty, and a damaged last page is exactly how that
            // happens: the search falls back to a header page. Unknown is the
            // honest answer, and it lets the decoder meet the damage and
            // report it rather than stopping before it.
            granule <= 0 -> null
            audio.codec == AudioCodec.OPUS -> (granule - audio.preSkip).coerceAtLeast(0L)
            else -> granule
        }

        val blocks = buildList {
            tagBlock?.let { add(it) }
            addAll(flacPictureBlocks)
        }

        return Parsed(
            streams = streams,
            audio = audio,
            tagBlocks = blocks,
            durationFrames = duration,
            audioStartOffset = audioStart,
            streamEndOffset = streamEnd,
            headerPackets = headerPackets,
            headerPacketCount = headerPackets.size,
        )
    }

    /** Names the mapping a stream's first packet declares. */
    private fun identify(page: OggPage): Stream {
        val bytes = page.payload
        val length = firstPacketLength(page)

        if (matches(bytes, 0, "OpusHead") && length >= 19) {
            validateIdentificationPage(page, "Opus", requireAlone = true)
            val version = bytes[8].toInt() and 0xFF
            if (version and 0xF0 != 0) {
                throw UnsupportedFeatureException(
                    "Ogg OpusHead version $version",
                    "this Ogg stream uses OpusHead version $version; versions above 15 are not compatible",
                )
            }
            val channels = bytes[9].toInt() and 0xFF
            if (channels == 0) {
                throw CorruptStreamException("an OpusHead cannot declare zero channels", page.offset)
            }
            val preSkip = readU16Le(bytes, 10)
            val inputRate = readU32Le(bytes, 12)
            val gain = readI16Le(bytes, 16)
            val mappingFamily = bytes[18].toInt() and 0xFF
            if (mappingFamily == 0) {
                if (channels !in 1..2 || length < 19 || (version == 1 && length != 19)) {
                    throw CorruptStreamException(
                        "Opus mapping family 0 requires one or two channels and a 19-byte OpusHead",
                        page.offset,
                    )
                }
            } else {
                val required = 21 + channels
                if (length < required) {
                    throw CorruptStreamException(
                        "the OpusHead channel mapping is cut short",
                        page.offset,
                    )
                }
                if (version == 1 && length != required) {
                    throw CorruptStreamException(
                        "an OpusHead version 1 packet has unexpected trailing bytes",
                        page.offset,
                    )
                }
                val streams = bytes[19].toInt() and 0xFF
                val coupled = bytes[20].toInt() and 0xFF
                if (streams == 0 || coupled > streams || streams + coupled != channels) {
                    throw CorruptStreamException(
                        "the OpusHead stream and coupled counts do not make $channels channels",
                        page.offset,
                    )
                }
                val codedChannels = streams + coupled
                for (i in 0 until channels) {
                    val mapping = bytes[21 + i].toInt() and 0xFF
                    if (mapping != 255 && mapping >= codedChannels) {
                        throw CorruptStreamException(
                            "Opus channel $i maps to nonexistent coded channel $mapping",
                            page.offset,
                        )
                    }
                }
            }
            return Stream(
                serialNumber = page.serialNumber,
                firstPageOffset = page.offset,
                codec = AudioCodec.OPUS,
                codecName = "Opus",
                // Opus decodes at 48 kHz whatever it was recorded at. The rate
                // in the header describes the material, not the stream.
                sampleRate = 48_000,
                channels = channels,
                preSkip = preSkip,
                outputGainDb = gain / 256.0,
                isAudio = true,
                identification = bytes.copyOf(length),
            ).also {
                // The stated input rate is kept out of the format on purpose,
                // and read here so a reader of this code knows it was not
                // missed.
                if (inputRate == 0L) Unit
            }
        }

        if (length >= 30 && (bytes[0].toInt() and 0xFF) == 0x01 && matches(bytes, 1, "vorbis")) {
            validateIdentificationPage(page, "Vorbis", requireAlone = true)
            if (length != 30 || readU32Le(bytes, 7) != 0L) {
                throw CorruptStreamException(
                    "the Vorbis identification header has an invalid version or length",
                    page.offset,
                )
            }
            val channels = bytes[11].toInt() and 0xFF
            val rate = readU32Le(bytes, 12).toInt()
            val blockSizes = bytes[28].toInt() and 0xFF
            val smallBlock = blockSizes and 0x0F
            val largeBlock = blockSizes ushr 4
            if (smallBlock !in 6..13 || largeBlock !in smallBlock..13 ||
                bytes[29].toInt() and 0x01 == 0
            ) {
                throw CorruptStreamException(
                    "the Vorbis identification header has invalid block sizes or framing bit",
                    page.offset,
                )
            }
            return Stream(
                serialNumber = page.serialNumber,
                firstPageOffset = page.offset,
                codec = AudioCodec.VORBIS,
                codecName = "Vorbis",
                sampleRate = rate,
                channels = channels,
                preSkip = 0,
                outputGainDb = 0.0,
                isAudio = true,
                identification = bytes.copyOf(length),
            )
        }

        if (length >= 51 && (bytes[0].toInt() and 0xFF) == 0x7F && matches(bytes, 1, "FLAC")) {
            validateIdentificationPage(page, "FLAC", requireAlone = true)
            val major = bytes[5].toInt() and 0xFF
            if (major != 1) {
                throw UnsupportedFeatureException(
                    "Ogg FLAC mapping version $major.${bytes[6].toInt() and 0xFF}",
                    "this Ogg FLAC stream uses mapping version $major; AudioKodecs supports version 1.x",
                )
            }
            if (length != 51 || !matches(bytes, 9, "fLaC") ||
                bytes[13].toInt() and 0xFF != 0 ||
                readU24Be(bytes, 14) != 34
            ) {
                throw CorruptStreamException(
                    "the Ogg FLAC identification packet does not contain a valid STREAMINFO block",
                    page.offset,
                )
            }
            // The mapping wraps a real FLAC stream information block, and its
            // fields sit where they always do inside that block.
            val streamInfo = 13 + 4
            val packed = readU64Be(bytes, streamInfo + 10)
            val rate = ((packed ushr 44) and 0xFFFFF).toInt()
            val channels = ((packed ushr 41) and 0x7).toInt() + 1
            return Stream(
                serialNumber = page.serialNumber,
                firstPageOffset = page.offset,
                codec = AudioCodec.FLAC,
                codecName = "FLAC",
                sampleRate = rate,
                channels = channels,
                preSkip = 0,
                outputGainDb = 0.0,
                isAudio = true,
                identification = bytes.copyOf(length),
            )
        }

        if (matches(bytes, 0, "Speex   ") && length >= 80) {
            validateIdentificationPage(page, "Speex", requireAlone = false)
            val headerSize = readU32Le(bytes, 32).toInt()
            val rate = readU32Le(bytes, 36).toInt()
            val channels = readU32Le(bytes, 48).toInt()
            val framesPerPacket = readU32Le(bytes, 64).toInt()
            val extraHeaders = readU32Le(bytes, 68).toInt()
            if (headerSize < 80 || headerSize > length || framesPerPacket <= 0 ||
                extraHeaders < 0 || readU32Le(bytes, 72) != 0L || readU32Le(bytes, 76) != 0L
            ) {
                throw CorruptStreamException(
                    "the Speex identification header has invalid size, packet count, or reserved fields",
                    page.offset,
                )
            }
            return Stream(
                serialNumber = page.serialNumber,
                firstPageOffset = page.offset,
                codec = AudioCodec.SPEEX,
                codecName = "Speex",
                sampleRate = rate,
                channels = channels,
                preSkip = 0,
                outputGainDb = 0.0,
                isAudio = true,
                identification = bytes.copyOf(length),
            )
        }

        // Streams that are not audio, or are audio AudioKodecs does not know.
        // Naming them is what lets the audio stream of a video file be found.
        val name = when {
            length >= 7 && (bytes[0].toInt() and 0xFF) == 0x80 && matches(bytes, 1, "theora") -> "Theora"
            matches(bytes, 0, "\u0001video\u0000") -> "OggMNG video"
            matches(bytes, 0, "fishead") -> "Skeleton"
            matches(bytes, 0, "PCM     ") -> "OggPCM"
            matches(bytes, 0, "OVP80") -> "VP8"
            matches(bytes, 0, "\u0080kate\u0000") -> "Kate"
            matches(bytes, 0, "BBCD") -> "Dirac"
            else -> "an unrecognised mapping"
        }
        return Stream(
            serialNumber = page.serialNumber,
            firstPageOffset = page.offset,
            codec = AudioCodec.UNKNOWN,
            codecName = name,
            sampleRate = 0,
            channels = 0,
            preSkip = 0,
            outputGainDb = 0.0,
            isAudio = false,
            identification = bytes.copyOf(length),
        )
    }

    /** The length of the first packet on a page, from its lacing values. */
    private fun firstPacketLength(page: OggPage): Int {
        var total = 0
        for (segment in page.segments) {
            total += segment
            if (segment != 255) break
        }
        return total.coerceAtMost(page.payload.size)
    }

    /** How many header packets an Ogg FLAC stream declares, or null for unknown. */
    private fun declaredFlacHeaderCount(identification: ByteArray): Int? {
        val stated = ((identification[7].toInt() and 0xFF) shl 8) or
            (identification[8].toInt() and 0xFF)
        // The count excludes the first packet. Zero explicitly means that the
        // count is unknown, so the last-metadata flag must be followed instead.
        return if (stated == 0) null else stated + 1
    }

    private fun speexHeaderCount(
        identification: ByteArray,
        options: DecodeOptions,
        offset: Long,
    ): Int {
        val extra = readU32Le(identification, 68)
        if (extra > Int.MAX_VALUE - 2L) {
            throw CorruptStreamException("the Speex extra-header count is too large", offset)
        }
        val count = extra.toInt() + 2
        options.checkAllocation(count.toLong() * 32L, "remembering Speex header packets")
        return count
    }

    private fun validateFlacMetadataPacket(
        bytes: ByteArray,
        packetOffset: Long,
        packetNumber: Int,
        fixedHeaderCount: Int?,
    ) {
        if (bytes.size < 4) {
            throw CorruptStreamException("an Ogg FLAC metadata packet is shorter than its header", packetOffset)
        }
        val type = bytes[0].toInt() and 0x7F
        val bodyLength = readU24Be(bytes, 1)
        if (type == 0 || type == 127 || bodyLength != bytes.size - 4) {
            throw CorruptStreamException(
                "an Ogg FLAC metadata packet has invalid type or length",
                packetOffset,
            )
        }
        if (packetNumber == 2 && type != 4) {
            throw CorruptStreamException(
                "the first Ogg FLAC metadata packet is not VORBIS_COMMENT",
                packetOffset,
            )
        }
        if (fixedHeaderCount != null) {
            val isLast = bytes[0].toInt() and 0x80 != 0
            if (isLast != (packetNumber == fixedHeaderCount)) {
                throw CorruptStreamException(
                    "the Ogg FLAC last-metadata flag disagrees with its declared header count",
                    packetOffset,
                )
            }
        }
    }

    private fun validateIdentificationPage(page: OggPage, codec: String, requireAlone: Boolean) {
        if (page.sequenceNumber != 0 || page.isContinued || page.isLast || page.granulePosition != 0L) {
            throw CorruptStreamException(
                "the $codec identification page has invalid sequence, flags, or granule position",
                page.offset,
            )
        }
        if (!page.endsAPacket) {
            throw CorruptStreamException(
                "the $codec identification packet does not finish on its first page",
                page.offset,
            )
        }
        if (requireAlone && page.segments.count { it != 255 } != 1) {
            throw CorruptStreamException(
                "the $codec identification packet is not alone on its first page",
                page.offset,
            )
        }
    }

    private fun vorbisCommentIn(
        bytes: ByteArray,
        offset: Long,
        options: DecodeOptions,
    ): RawTagBlock? {
        val from = 7
        if (bytes.size <= from) return null
        if ((bytes[0].toInt() and 0xFF) != 0x03 || !matches(bytes, 1, "vorbis")) return null
        if (bytes.last().toInt() and 0x01 == 0) return null
        return VorbisComment.parse(
            bytes,
            from,
            bytes.size - 1,
            offset,
            bytes.size.toLong(),
            options,
        )
    }

    private fun opusTagsIn(
        bytes: ByteArray,
        offset: Long,
        options: DecodeOptions,
    ): RawTagBlock? {
        if (!matches(bytes, 0, "OpusTags")) return null
        return VorbisComment.parse(
            bytes,
            8,
            bytes.size,
            offset,
            bytes.size.toLong(),
            options,
        )
    }

    private fun speexCommentIn(
        bytes: ByteArray,
        offset: Long,
        options: DecodeOptions,
    ): RawTagBlock? {
        // Speex puts a bare comment structure in its second packet, with no
        // signature in front of it.
        if (bytes.size < 8) return null
        return VorbisComment.parse(
            bytes,
            0,
            bytes.size,
            offset,
            bytes.size.toLong(),
            options,
        )
    }

    /**
     * The bit depth an Ogg FLAC stream stores, read out of its wrapped stream
     * information block.
     */
    fun flacBitDepth(stream: Stream): Int? {
        if (stream.codec != AudioCodec.FLAC) return null
        val bytes = stream.identification
        if (bytes.size < 51) return null
        val packed = readU64Be(bytes, 13 + 4 + 10)
        return ((packed ushr 36) and 0x1F).toInt() + 1
    }

    private fun matches(bytes: ByteArray, at: Int, text: String): Boolean {
        if (at + text.length > bytes.size) return false
        for (i in text.indices) {
            if ((bytes[at + i].toInt() and 0xFF) != text[i].code) return false
        }
        return true
    }

    private fun readU16Le(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun readI16Le(bytes: ByteArray, at: Int): Int = readU16Le(bytes, at).toShort().toInt()

    private fun readU32Le(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 3 downTo 0) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        return value
    }

    private fun readU24Be(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 16) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            (bytes[at + 2].toInt() and 0xFF)

    private fun readU64Be(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        return value
    }
}

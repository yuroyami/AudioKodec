package io.github.yuroyami.audiokodec.internal.container

import io.github.yuroyami.audiokodec.AudioChannel
import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.ParsedStream
import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.AudioInfo
import io.github.yuroyami.audiokodec.internal.PacketIndex
import io.github.yuroyami.audiokodec.internal.PacketStreamDecoder
import io.github.yuroyami.audiokodec.internal.alac.AlacConfig
import io.github.yuroyami.audiokodec.internal.alac.AlacPacketCodec
import io.github.yuroyami.audiokodec.internal.codec.ChannelReorderingCodec
import io.github.yuroyami.audiokodec.internal.codec.G711Codec
import io.github.yuroyami.audiokodec.internal.codec.ImaAdpcmQuickTimeCodec
import io.github.yuroyami.audiokodec.internal.codec.LinearPcmCodec
import io.github.yuroyami.audiokodec.internal.codec.PcmLayout
import io.github.yuroyami.audiokodec.internal.codec.SampleCodec
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import kotlin.math.roundToInt

/**
 * Reads Apple's Core Audio Format.
 *
 * CAF is what AIFF should have grown into: the same chunked shape, but with
 * 64-bit sizes so a file can pass 4 GiB, and a description chunk that names the
 * coding with a four-character code instead of a numbered table. Everything is
 * big-endian, including the sample rate, which is stored as a 64-bit float.
 *
 * The codings this reads are the uncompressed ones and the two companding
 * laws, QuickTime's IMA variant, and Apple Lossless, whose packets vary in
 * size and are found through the packet table. AAC inside a CAF file is named
 * and refused, because its decoder is not here yet.
 */
internal object CafReader {

    private const val MAX_COOKIE_BYTES = 1L shl 16

    /** What a CAF file holds: a coding with fixed-size blocks, or ALAC, which is indexed by its packet table. */
    sealed class Stream {
        class Blocks(val parsed: ParsedStream) : Stream()

        class Alac(
            val config: AlacConfig,
            val sampleRate: Int,
            val index: PacketIndex,
            val primingFrames: Long,
            val validFrames: Long,
            val payloadBytes: Long,
            val dataOffset: Long,
        ) : Stream()
    }

    fun info(reader: SourceReader, options: DecodeOptions): AudioInfo = when (val stream = read(reader, options)) {
        is Stream.Blocks -> stream.parsed.toInfo(reader)
        is Stream.Alac -> alacInfo(reader, stream)
    }

    fun open(reader: SourceReader, options: DecodeOptions): AudioDecoder = when (val stream = read(reader, options)) {
        is Stream.Blocks -> stream.parsed.toDecoder(reader, options)
        is Stream.Alac -> {
            val codec = AlacPacketCodec(stream.config, stream.sampleRate, options)
            val validFrames = alacPlayFrames(reader, stream, codec, options)
            PacketStreamDecoder.of(
                reader, stream.index, codec, alacInfo(reader, stream, codec.format, validFrames),
                stream.primingFrames, validFrames, options,
            )
        }
    }

    /** FFmpeg can count a partial final ALAC packet as a full packet in `pakt`. */
    private fun alacPlayFrames(reader: SourceReader, stream: Stream.Alac, codec: AlacPacketCodec, options: DecodeOptions): Long {
        val index = stream.index
        val last = index.count - 1
        if (last < 0 || stream.validFrames <= (index.starts[last] - stream.primingFrames).coerceAtLeast(0L)) {
            return stream.validFrames
        }
        val frames = try {
            reader.seek(index.offsets[last])
            codec.decodedFrameCount(reader.readBytes(index.sizes[last]))
        } catch (failure: TruncatedStreamException) {
            if (!options.tolerant) throw failure
            return stream.validFrames
        } catch (failure: CorruptStreamException) {
            if (!options.tolerant) {
                throw CorruptStreamException("the final ALAC packet is damaged: ${failure.message}", reader.absoluteOffset(index.offsets[last]))
            }
            // Leave tolerant recovery to sequential decoding, so earlier valid
            // packets are still available even when the final one is damaged.
            return stream.validFrames
        }
        if (frames >= index.starts[last + 1] - index.starts[last]) return stream.validFrames
        index.starts[last + 1] = index.starts[last] + frames
        return minOf(stream.validFrames, (index.totalFrames - stream.primingFrames).coerceAtLeast(0L))
    }

    private fun alacInfo(
        reader: SourceReader,
        stream: Stream.Alac,
        format: AudioFormat = AlacPacketCodec(stream.config, stream.sampleRate, reader.options).format,
        validFrames: Long = stream.validFrames,
    ): AudioInfo {
        val bitrate = if (validFrames > 0) {
            (stream.payloadBytes.toDouble() * 8.0 * stream.sampleRate / validFrames).coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()
        } else {
            null
        }
        return AudioInfo(
            container = AudioContainer.CAF,
            codec = AudioCodec.ALAC,
            format = format,
            durationFrames = validFrames,
            bitDepth = stream.config.bitDepth,
            bitrate = bitrate,
            isVariableBitrate = true,
            dataOffset = reader.absoluteOffset(stream.dataOffset),
            dataSize = stream.payloadBytes,
        )
    }

    private const val FLAG_FLOAT = 1
    private const val FLAG_LITTLE_ENDIAN = 2
    private const val KNOWN_LINEAR_PCM_FLAGS = FLAG_FLOAT or FLAG_LITTLE_ENDIAN

    fun read(reader: SourceReader, options: DecodeOptions): Stream {
        reader.seek(0)
        reader.expectFourCc("caff", "CAF header")
        val version = reader.readU16Be()
        if (version != 1) {
            throw UnsupportedFeatureException(
                "CAF version $version",
                "this file is CAF version $version, and only version 1 is defined",
            )
        }
        // Version 1 defines no file flags. The specification explicitly says
        // readers must ignore unknown ones so a future extension stays
        // readable by older implementations.
        reader.readU16Be()

        var description: Description? = null
        var dataOffset = -1L
        var dataBytes = -1L
        var packetTable: PacketTable? = null
        var storedChannels: List<AudioChannel>? = null
        var sawChannelLayout = false
        var cookie: ByteArray? = null
        var packetEntriesAt = -1L
        var packetEntriesBytes = 0L
        var chunkIndex = 0

        val size = reader.size
        while (true) {
            if (size >= 0) {
                val left = size - reader.position
                if (left == 0L) break
                if (left < 12L) {
                    throw TruncatedStreamException(
                        "the CAF file ends inside a chunk header",
                        reader.position,
                    )
                }
            } else if (reader.exhausted()) {
                break
            }
            val chunkType = reader.readFourCc()
            val chunkStart = reader.position - 4
            val chunkSize = reader.readI64Be()
            val bodyStart = reader.position
            if (chunkIndex == 0 && chunkType != "desc") {
                throw CorruptStreamException(
                    "the CAF description chunk must immediately follow the file header",
                    chunkStart,
                )
            }

            // A size of -1 means the chunk runs to the end of the file, which
            // is what a program writing a stream to a pipe leaves behind.
            val bodySize = if (chunkSize == -1L) {
                if (chunkType != "data") {
                    throw CorruptStreamException(
                        "only a CAF data chunk may have an unknown size",
                        chunkStart,
                    )
                }
                if (size > 0) size - bodyStart else -1L
            } else if (chunkSize < -1L) {
                throw CorruptStreamException(
                    "a CAF chunk size may be -1 or non-negative, found $chunkSize",
                    chunkStart,
                )
            } else {
                chunkSize
            }

            when (chunkType) {
                "desc" -> {
                    if (description != null) {
                        throw CorruptStreamException(
                            "a CAF file may contain only one description chunk",
                            chunkStart,
                        )
                    }
                    if (bodySize != 32L) {
                        throw CorruptStreamException(
                            "a CAF description chunk is exactly 32 bytes, this one is $bodySize",
                            bodyStart,
                        )
                    }
                    description = readDescription(reader, options, bodyStart)
                }

                "data" -> {
                    if (description == null) {
                        throw CorruptStreamException(
                            "the CAF data chunk appears before its description",
                            chunkStart,
                        )
                    }
                    if (dataOffset >= 0) {
                        throw CorruptStreamException(
                            "a CAF file may contain only one data chunk",
                            chunkStart,
                        )
                    }
                    // The first four bytes are an edit count, not audio.
                    if (bodySize in 0..3) {
                        throw CorruptStreamException(
                            "a CAF data chunk must contain its four-byte edit count",
                            bodyStart,
                        )
                    }
                    reader.readU32Be()
                    dataOffset = bodyStart + 4
                    dataBytes = if (bodySize < 0) -1L else bodySize - 4
                }

                "pakt" -> {
                    if (packetTable != null) {
                        throw CorruptStreamException(
                            "a CAF file may contain only one packet table",
                            chunkStart,
                        )
                    }
                    if (bodySize >= 24) {
                        val packetCount = reader.readI64Be()
                        val validFrames = reader.readI64Be()
                        val primingFrames = reader.readI32Be().toLong()
                        val remainderFrames = reader.readI32Be().toLong()
                        if (packetCount < 0 || validFrames < 0 ||
                            primingFrames < 0 || remainderFrames < 0
                        ) {
                            throw CorruptStreamException(
                                "CAF packet, valid, priming and remainder counts must not be negative",
                                bodyStart,
                            )
                        }
                        packetTable = PacketTable(
                            packetCount,
                            validFrames,
                            primingFrames,
                            remainderFrames,
                        )
                        packetEntriesAt = bodyStart + 24
                        packetEntriesBytes = bodySize - 24
                    } else {
                        throw CorruptStreamException(
                            "a CAF packet table is at least 24 bytes, this one is $bodySize",
                            bodyStart,
                        )
                    }
                }

                "kuki" -> {
                    if (bodySize < 0 || bodySize > MAX_COOKIE_BYTES) {
                        throw CorruptStreamException("a CAF magic cookie of $bodySize bytes is out of range", chunkStart)
                    }
                    cookie = reader.readBytes(bodySize.toInt())
                }

                // ALAC fixes its own channel order, which its layout chunk
                // only restates, so the chunk is not needed to decode it.
                "chan" -> if (description?.formatId == "alac") {
                    sawChannelLayout = true
                } else {
                    if (sawChannelLayout) {
                        throw CorruptStreamException(
                            "a CAF file may contain only one channel layout chunk",
                            chunkStart,
                        )
                    }
                    sawChannelLayout = true
                    if (bodySize < 12) {
                        throw CorruptStreamException(
                            "a CAF channel layout chunk is at least 12 bytes, this one is $bodySize",
                            bodyStart,
                        )
                    }
                    val tag = reader.readU32Be()
                    val bitmap = reader.readU32Be()
                    val descriptionCount = reader.readU32Be()
                    val descriptionsBytes = descriptionCount * 20L
                    if (descriptionCount > Int.MAX_VALUE ||
                        descriptionsBytes > bodySize - 12L
                    ) {
                        throw CorruptStreamException(
                            "the CAF channel layout declares $descriptionCount descriptions " +
                                "in a $bodySize-byte chunk",
                            bodyStart,
                        )
                    }
                    val describedChannels = description?.channels
                        ?: throw CorruptStreamException(
                            "the CAF channel layout appears before its description",
                            chunkStart,
                        )
                    val layoutId = (tag ushr 16).toInt()
                    if (layoutId == 0 || layoutId == 1) {
                        val kind = if (layoutId == 0) {
                            "$descriptionCount explicit channel descriptions"
                        } else {
                            "channel bitmap 0x${bitmap.toString(16)}"
                        }
                        throw UnsupportedFeatureException(
                            "a CAF layout using $kind",
                            "this CAF file describes its speakers with $kind; only named " +
                                "Core Audio layout tags are currently mapped",
                        )
                    }
                    if (bitmap != 0L || descriptionCount != 0L) {
                        throw CorruptStreamException(
                            "a named CAF channel layout must not also carry a bitmap or " +
                                "channel descriptions",
                            bodyStart,
                        )
                    }
                    val mapped = storedOrderForTag(tag) ?: throw UnsupportedFeatureException(
                        "CAF channel layout tag 0x${tag.toString(16)}",
                        "this CAF file uses channel layout tag 0x${tag.toString(16)}, " +
                            "which AudioKodec cannot map without guessing speaker positions",
                    )
                    val statedChannels = (tag and 0xFFFF).toInt()
                    if (statedChannels != mapped.size ||
                        statedChannels != describedChannels
                    ) {
                        throw CorruptStreamException(
                            "CAF channel layout tag names $statedChannels channels " +
                                "for $describedChannels-channel audio",
                            bodyStart,
                        )
                    }
                    storedChannels = mapped
                }
            }

            if (bodySize < 0) break
            if (bodySize > Long.MAX_VALUE - bodyStart) {
                throw CorruptStreamException("a CAF chunk size overflows 64 bits", chunkStart)
            }
            val next = bodyStart + bodySize
            if (size >= 0 && next > size) {
                throw TruncatedStreamException(
                    "CAF chunk '$chunkType' declares $bodySize bytes past end of input",
                    chunkStart,
                )
            }
            if (next <= chunkStart) {
                throw CorruptStreamException("a CAF chunk does not advance", chunkStart)
            }
            reader.seek(next)
            chunkIndex++
            if (size in 1..next) break
        }

        val desc = description
            ?: throw CorruptStreamException("the file has no description chunk", 0)
        if (dataOffset < 0) throw CorruptStreamException("the file has no data chunk", 0)

        val available = if (size >= 0) (size - dataOffset).coerceAtLeast(0L) else -1L
        val payload = if (dataBytes < 0) available else dataBytes

        if (desc.formatId == "alac") {
            val config = AlacConfig.parse(cookie ?: throw CorruptStreamException("an ALAC CAF file has no magic cookie"), 0)
            if (config.channels != desc.channels) {
                throw CorruptStreamException("the CAF description says ${desc.channels} channels and the ALAC configuration ${config.channels}")
            }
            val table = packetTable ?: throw CorruptStreamException("an ALAC CAF file has no packet table")
            return alacStream(reader, options, desc, config, table, packetEntriesAt, packetEntriesBytes, dataOffset, payload)
        }

        val chosen = codecFor(desc)
        // CAF assumes no order above two channels, so a file without a layout
        // chunk has channels whose speakers nobody stated. A named layout is
        // stored in its own order and decoded into the canonical one.
        val stored = storedChannels
        val (layout, sampleCodec) = when {
            stored != null && stored.any { it == AudioChannel.UNKNOWN } ->
                ChannelLayout.of(stored) to chosen.sampleCodec
            stored != null -> {
                val (canonical, reordering) = ChannelReorderingCodec.canonicalOrder(chosen.sampleCodec, stored)
                ChannelLayout.of(canonical) to reordering
            }
            desc.channels == 1 -> ChannelLayout.MONO to chosen.sampleCodec
            desc.channels == 2 -> ChannelLayout.STEREO to chosen.sampleCodec
            else -> ChannelLayout.unknown(desc.channels) to chosen.sampleCodec
        }
        val format = AudioFormat(desc.sampleRate, layout)
        val codec = Chosen(chosen.codec, sampleCodec, chosen.storedBitDepth)

        val table = packetTable
        if (table != null) {
            // Every coding accepted below has constant bytes and frames per
            // packet. CAF uses a zero packet count for that case because no
            // variable-size packet table follows the 24-byte description.
            if (table.packetCount != 0L) {
                throw CorruptStreamException(
                    "a constant-size CAF stream must set its packet-table packet count to zero",
                )
            }
            val trim = checkedAdd(
                table.primingFrames,
                table.remainderFrames,
                "CAF priming and remainder counts overflow 64 bits",
            )
            val described = checkedAdd(
                table.validFrames,
                trim,
                "CAF packet-table frame counts overflow 64 bits",
            )
            val blockBytes = codec.sampleCodec.blockBytes
            val framesPerBlock = codec.sampleCodec.framesPerBlock
            if (payload >= 0 && payload % blockBytes == 0L) {
                val blocks = payload / blockBytes
                val stored = if (blocks > Long.MAX_VALUE / framesPerBlock) {
                    throw CorruptStreamException("the CAF payload frame count overflows 64 bits")
                } else {
                    blocks * framesPerBlock
                }
                if (described != stored) {
                    throw CorruptStreamException(
                        "the CAF packet table describes $described coded frames " +
                            "(${
                                table.validFrames
                            } valid, ${table.primingFrames} priming, " +
                            "${table.remainderFrames} remainder), but the payload holds $stored",
                    )
                }
            }
        }

        return Stream.Blocks(ParsedStream(
            container = AudioContainer.CAF,
            codec = codec.codec,
            sampleCodec = codec.sampleCodec,
            format = format,
            dataOffset = dataOffset,
            dataBytes = payload,
            declaredFrames = table?.validFrames,
            bitDepth = codec.storedBitDepth,
            trimStartFrames = table?.primingFrames ?: 0L,
        ))
    }

    /**
     * Builds the packet index of an ALAC stream from its packet table: one
     * variable-length size per packet, and a frame count too when the
     * description leaves frames per packet open.
     */
    private fun alacStream(
        reader: SourceReader,
        options: DecodeOptions,
        desc: Description,
        config: AlacConfig,
        table: PacketTable,
        entriesAt: Long,
        entriesBytes: Long,
        dataOffset: Long,
        payload: Long,
    ): Stream.Alac {
        val count = table.packetCount
        if (count > Int.MAX_VALUE - 1) throw CorruptStreamException("the CAF packet table lists $count packets")
        if (entriesBytes > Int.MAX_VALUE) throw CorruptStreamException("the CAF packet table is too large to read")
        options.checkAllocation(count * 20 + entriesBytes, "indexing $count ALAC packets")
        reader.seek(entriesAt)
        val entries = reader.readBytes(entriesBytes.toInt())
        var at = 0
        fun varint(): Long {
            var value = 0L
            while (true) {
                if (at >= entries.size) throw CorruptStreamException("the CAF packet table ends before its last packet", entriesAt + at)
                val b = entries[at++].toInt() and 0xFF
                value = (value shl 7) or (b and 0x7F).toLong()
                if (value > Int.MAX_VALUE) throw CorruptStreamException("a CAF packet table entry is out of range", entriesAt + at)
                if (b and 0x80 == 0) return value
            }
        }
        val packets = count.toInt()
        val offsets = LongArray(packets)
        val sizes = IntArray(packets)
        val starts = LongArray(packets + 1)
        var offset = dataOffset
        for (i in 0 until packets) {
            val bytes = if (desc.bytesPerPacket > 0) desc.bytesPerPacket.toLong() else varint()
            val frames = if (desc.framesPerPacket > 0) desc.framesPerPacket.toLong() else varint()
            offsets[i] = offset
            sizes[i] = bytes.toInt()
            starts[i + 1] = starts[i] + frames
            offset += bytes
        }
        if (payload >= 0 && offset - dataOffset > payload && !options.tolerant) {
            throw TruncatedStreamException("the CAF packet table describes ${offset - dataOffset} bytes and the data chunk holds $payload")
        }
        val coded = starts[packets]
        if (table.primingFrames + table.validFrames + table.remainderFrames != coded && !options.tolerant) {
            throw CorruptStreamException(
                "the CAF packet table describes $coded coded frames (${table.validFrames} valid, " +
                    "${table.primingFrames} priming, ${table.remainderFrames} remainder) and its packets hold $coded",
            )
        }
        val valid = minOf(table.validFrames, (coded - table.primingFrames).coerceAtLeast(0L))
        return Stream.Alac(config, desc.sampleRate, PacketIndex(offsets, sizes, starts), table.primingFrames, valid, offset - dataOffset, dataOffset)
    }

    private class PacketTable(
        val packetCount: Long,
        val validFrames: Long,
        val primingFrames: Long,
        val remainderFrames: Long,
    )

    private fun checkedAdd(left: Long, right: Long, message: String): Long {
        if (left > Long.MAX_VALUE - right) throw CorruptStreamException(message)
        return left + right
    }

    private class Description(
        val sampleRate: Int,
        val formatId: String,
        val formatFlags: Int,
        val bytesPerPacket: Int,
        val framesPerPacket: Int,
        val channels: Int,
        val bitsPerChannel: Int,
    )

    private fun readDescription(
        reader: SourceReader,
        options: DecodeOptions,
        at: Long,
    ): Description {
        val rate = reader.readF64Be()
        val formatId = reader.readFourCc()
        val formatFlags = reader.readI32Be()
        val bytesPerPacket = reader.readI32Be()
        val framesPerPacket = reader.readI32Be()
        val channels = reader.readI32Be()
        val bitsPerChannel = reader.readI32Be()

        if (rate <= 0.0 || !rate.isFinite() || rate > 1_000_000.0) {
            throw CorruptStreamException("the CAF sample rate is $rate", at)
        }
        if (rate != rate.toInt().toDouble()) {
            throw UnsupportedFeatureException(
                "a fractional CAF sample rate",
                "this CAF file uses the fractional sample rate $rate Hz, but AudioFormat " +
                    "represents whole hertz",
            )
        }
        val sampleRate = rate.roundToInt()
        options.checkStreamShape(sampleRate, channels, at)

        return Description(
            sampleRate = sampleRate,
            formatId = formatId,
            formatFlags = formatFlags,
            bytesPerPacket = bytesPerPacket,
            framesPerPacket = framesPerPacket,
            channels = channels,
            bitsPerChannel = bitsPerChannel,
        )
    }

    private class Chosen(
        val codec: AudioCodec,
        val sampleCodec: SampleCodec,
        val storedBitDepth: Int?,
    )

    private fun codecFor(desc: Description): Chosen {
        val channels = desc.channels
        return when (desc.formatId) {
            "lpcm" -> {
                if (desc.formatFlags and KNOWN_LINEAR_PCM_FLAGS.inv() != 0) {
                    throw CorruptStreamException(
                        "CAF linear PCM uses reserved format flags 0x" +
                            desc.formatFlags.toUInt().toString(16),
                    )
                }
                if (desc.framesPerPacket != 1) {
                    throw CorruptStreamException(
                        "CAF linear PCM must have one frame per packet, not " +
                            desc.framesPerPacket,
                    )
                }
                if (desc.bytesPerPacket <= 0 || desc.bytesPerPacket % channels != 0) {
                    throw CorruptStreamException(
                        "CAF linear PCM packet size ${desc.bytesPerPacket} does not divide " +
                            "across $channels channels",
                    )
                }
                val isFloat = desc.formatFlags and FLAG_FLOAT != 0
                val littleEndian = desc.formatFlags and FLAG_LITTLE_ENDIAN != 0
                val depth = desc.bitsPerChannel
                val storageBytes = desc.bytesPerPacket / channels
                if (isFloat) {
                    if (depth != 32 && depth != 64) {
                        throw CorruptStreamException(
                            "float audio is 32 or 64 bits, this file says $depth",
                        )
                    }
                    if (storageBytes != depth / 8) {
                        throw CorruptStreamException(
                            "$depth-bit CAF float samples occupy $storageBytes bytes",
                        )
                    }
                    Chosen(
                        AudioCodec.PCM_FLOAT,
                        LinearPcmCodec(
                            channels,
                            PcmLayout(depth, littleEndian, signed = true, isFloat = true),
                        ),
                        null,
                    )
                } else {
                    if (depth !in 1..32) {
                        throw CorruptStreamException(
                            "CAF integer audio is 1 to 32 bits, this file says $depth",
                        )
                    }
                    val packedBytes = (depth + 7) / 8
                    if (storageBytes !in packedBytes..4) {
                        throw CorruptStreamException(
                            "$depth-bit CAF integer samples cannot occupy $storageBytes bytes",
                        )
                    }
                    // CAF stores every integer width signed, including 8-bit,
                    // and high-aligns a width that does not fill its word.
                    Chosen(
                        AudioCodec.PCM_INTEGER,
                        LinearPcmCodec(
                            channels,
                            PcmLayout(
                                depth,
                                littleEndian,
                                signed = true,
                                storageBytes = storageBytes,
                                highAligned = storageBytes * 8 != depth,
                            ),
                        ),
                        depth,
                    )
                }
            }

            "ulaw", "alaw" -> {
                requireCompressedShape(
                    desc,
                    expectedBytesPerPacket = channels,
                    expectedFramesPerPacket = 1,
                    allowedBitsPerChannel = intArrayOf(0, 8),
                )
                Chosen(
                    if (desc.formatId == "ulaw") AudioCodec.MU_LAW else AudioCodec.A_LAW,
                    G711Codec(channels, aLaw = desc.formatId == "alaw"),
                    null,
                )
            }
            "ima4" -> {
                requireCompressedShape(
                    desc,
                    expectedBytesPerPacket = 34 * channels,
                    expectedFramesPerPacket = 64,
                    // Apple leaves compressed width unspecified; ffmpeg
                    // states the four coded bits with identical geometry.
                    allowedBitsPerChannel = intArrayOf(0, 4),
                )
                Chosen(AudioCodec.IMA_ADPCM, ImaAdpcmQuickTimeCodec(channels), null)
            }

            else -> throw UnsupportedFeatureException(
                "CAF coding '${desc.formatId}'",
                "this CAF file holds '${desc.formatId}', which AudioKodec cannot decode",
            )
        }
    }

    private fun requireCompressedShape(
        desc: Description,
        expectedBytesPerPacket: Int,
        expectedFramesPerPacket: Int,
        allowedBitsPerChannel: IntArray,
    ) {
        if (desc.formatFlags != 0 ||
            allowedBitsPerChannel.none { it == desc.bitsPerChannel } ||
            desc.bytesPerPacket != expectedBytesPerPacket ||
            desc.framesPerPacket != expectedFramesPerPacket
        ) {
            throw CorruptStreamException(
                "CAF '${desc.formatId}' must use flags 0, " +
                    "${allowedBitsPerChannel.joinToString(" or ")} bits per channel, " +
                    "$expectedBytesPerPacket bytes and $expectedFramesPerPacket frames per packet",
            )
        }
    }

    /**
     * The speakers a Core Audio channel layout tag names, in the order the
     * file interleaves them.
     *
     * The tag's high sixteen bits identify the arrangement and the low sixteen
     * hold the channel count. The orders are the ones Apple publishes in
     * `CoreAudioBaseTypes.h`. Apple's left and right surround map to the back
     * pair, matching the WAV assignment for five and six channels, and its
     * centre surround to the back centre. Arrangements that would need
     * a guess between side and back speakers are refused rather than
     * relabelled. The two tags that state no positions at all give unknown
     * channels, which is what they mean.
     */
    fun storedOrderForTag(tag: Long): List<AudioChannel>? {
        val count = (tag and 0xFFFF).toInt()
        return when ((tag ushr 16).toInt()) {
            100 -> listOf(C)
            101 -> listOf(L, R)
            108, 132 -> listOf(L, R, LS, RS) // Quadraphonic, ITU 2.2
            113 -> listOf(L, R, C) // MPEG 3.0 A
            114 -> listOf(C, L, R) // MPEG 3.0 B
            115 -> listOf(L, R, C, CS) // MPEG 4.0 A
            116 -> listOf(C, L, R, CS) // MPEG 4.0 B
            117 -> listOf(L, R, C, LS, RS) // MPEG 5.0 A
            118 -> listOf(L, R, LS, RS, C) // MPEG 5.0 B
            119 -> listOf(L, C, R, LS, RS) // MPEG 5.0 C
            120 -> listOf(C, L, R, LS, RS) // MPEG 5.0 D
            121 -> listOf(L, R, C, LFE, LS, RS) // MPEG 5.1 A
            122 -> listOf(L, R, LS, RS, C, LFE) // MPEG 5.1 B
            123 -> listOf(L, C, R, LS, RS, LFE) // MPEG 5.1 C
            124 -> listOf(C, L, R, LS, RS, LFE) // MPEG 5.1 D
            125 -> listOf(L, R, C, LFE, LS, RS, CS) // MPEG 6.1 A
            131 -> listOf(L, R, CS) // ITU 2.1
            133 -> listOf(L, R, LFE) // DVD 4
            134 -> listOf(L, R, LFE, CS) // DVD 5
            135 -> listOf(L, R, LFE, LS, RS) // DVD 6
            136 -> listOf(L, R, C, LFE) // DVD 10
            137 -> listOf(L, R, C, LFE, CS) // DVD 11
            138 -> listOf(L, R, LS, RS, LFE) // DVD 18
            141 -> listOf(C, L, R, LS, RS, CS) // AAC 6.0
            142 -> listOf(C, L, R, LS, RS, CS, LFE) // AAC 6.1
            // Discrete channels in order, and the explicitly unknown layout.
            147, 0xFFFF -> if (count > 0) List(count) { AudioChannel.UNKNOWN } else null
            else -> null
        }
    }

    private val L = AudioChannel.FRONT_LEFT
    private val R = AudioChannel.FRONT_RIGHT
    private val C = AudioChannel.FRONT_CENTER
    private val LFE = AudioChannel.LOW_FREQUENCY
    private val LS = AudioChannel.BACK_LEFT
    private val RS = AudioChannel.BACK_RIGHT
    private val CS = AudioChannel.BACK_CENTER
}

package io.github.yuroyami.audiokodecs.internal.container

import io.github.yuroyami.audiokodecs.AudioCodec
import io.github.yuroyami.audiokodecs.AudioContainer
import io.github.yuroyami.audiokodecs.AudioFormat
import io.github.yuroyami.audiokodecs.ChannelLayout
import io.github.yuroyami.audiokodecs.CorruptStreamException
import io.github.yuroyami.audiokodecs.DecodeOptions
import io.github.yuroyami.audiokodecs.TruncatedStreamException
import io.github.yuroyami.audiokodecs.UnsupportedFeatureException
import io.github.yuroyami.audiokodecs.internal.ParsedStream
import io.github.yuroyami.audiokodecs.internal.codec.G711Codec
import io.github.yuroyami.audiokodecs.internal.codec.ImaAdpcmQuickTimeCodec
import io.github.yuroyami.audiokodecs.internal.codec.LinearPcmCodec
import io.github.yuroyami.audiokodecs.internal.codec.PcmLayout
import io.github.yuroyami.audiokodecs.internal.codec.SampleCodec
import io.github.yuroyami.audiokodecs.internal.io.SourceReader
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
 * laws, plus QuickTime's IMA variant. ALAC and AAC inside a CAF file are named
 * and refused, because their decoders are not here yet.
 */
internal object CafReader {

    private const val FLAG_FLOAT = 1
    private const val FLAG_LITTLE_ENDIAN = 2
    private const val KNOWN_LINEAR_PCM_FLAGS = FLAG_FLOAT or FLAG_LITTLE_ENDIAN

    fun parse(reader: SourceReader, options: DecodeOptions): ParsedStream {
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
        var channelLayout: ChannelLayout? = null
        var sawChannelLayout = false
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
                    } else {
                        throw CorruptStreamException(
                            "a CAF packet table is at least 24 bytes, this one is $bodySize",
                            bodyStart,
                        )
                    }
                }

                "chan" -> {
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
                    val mapped = layoutForTag(tag) ?: throw UnsupportedFeatureException(
                        "CAF channel layout tag 0x${tag.toString(16)}",
                        "this CAF file uses channel layout tag 0x${tag.toString(16)}, " +
                            "which AudioKodecs cannot map without guessing speaker positions",
                    )
                    val statedChannels = (tag and 0xFFFF).toInt()
                    if (statedChannels != mapped.count ||
                        statedChannels != describedChannels
                    ) {
                        throw CorruptStreamException(
                            "CAF channel layout tag names $statedChannels channels " +
                                "for $describedChannels-channel audio",
                            bodyStart,
                        )
                    }
                    channelLayout = mapped
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

        val layout = channelLayout?.takeIf { it.count == desc.channels }
            ?: ChannelLayout.canonical(desc.channels)
        val format = AudioFormat(desc.sampleRate, layout)
        val codec = codecFor(desc)

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

        return ParsedStream(
            container = AudioContainer.CAF,
            codec = codec.codec,
            sampleCodec = codec.sampleCodec,
            format = format,
            dataOffset = dataOffset,
            dataBytes = payload,
            declaredFrames = table?.validFrames,
            bitDepth = codec.storedBitDepth,
            trimStartFrames = table?.primingFrames ?: 0L,
        )
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
                    allowedBitsPerChannel = intArrayOf(0),
                )
                Chosen(AudioCodec.IMA_ADPCM, ImaAdpcmQuickTimeCodec(channels), null)
            }

            else -> throw UnsupportedFeatureException(
                "CAF coding '${desc.formatId}'",
                "this CAF file holds '${desc.formatId}', which AudioKodecs cannot decode",
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
     * The layout a Core Audio channel layout tag names.
     *
     * The tag's high sixteen bits identify the arrangement and the low sixteen
     * hold the channel count. Only the arrangements that map onto a layout
     * AudioKodecs has are translated. Anything else is refused rather than
     * silently relabelled as the default arrangement.
     */
    private fun layoutForTag(tag: Long): ChannelLayout? = when ((tag ushr 16).toInt()) {
        100 -> ChannelLayout.MONO
        101 -> ChannelLayout.STEREO
        // 113 and 114 are the two five-channel arrangements, and 121 and 122
        // the six-channel ones, all of which start with the same speakers.
        113, 114 -> ChannelLayout.SURROUND_5_0
        121, 122 -> ChannelLayout.SURROUND_5_1
        else -> null
    }
}

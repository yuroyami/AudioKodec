package io.github.yuroyami.audiokodecs.internal.flac

import io.github.yuroyami.audiokodecs.CorruptStreamException
import io.github.yuroyami.audiokodecs.DecodeOptions
import io.github.yuroyami.audiokodecs.TruncatedStreamException
import io.github.yuroyami.audiokodecs.internal.io.SourceReader
import io.github.yuroyami.audiokodecs.internal.tag.FlacPicture
import io.github.yuroyami.audiokodecs.internal.tag.VorbisComment
import io.github.yuroyami.audiokodecs.tag.RawTagBlock
import io.github.yuroyami.audiokodecs.tag.RawTagEntry
import io.github.yuroyami.audiokodecs.tag.TagFormat

/**
 * The metadata blocks that sit between a FLAC file's magic number and its
 * audio.
 *
 * Every block has the same four-byte header: a flag saying whether it is the
 * last one, a type, and a length. That makes the chain walkable without
 * understanding any particular block, which is what lets a reader skip a
 * cue sheet it does not care about and still find the audio.
 */
internal object FlacMetadata {

    const val MAGIC: String = "fLaC"

    const val TYPE_STREAMINFO = 0
    const val TYPE_PADDING = 1
    const val TYPE_APPLICATION = 2
    const val TYPE_SEEKTABLE = 3
    const val TYPE_VORBIS_COMMENT = 4
    const val TYPE_CUESHEET = 5
    const val TYPE_PICTURE = 6

    /** The mandatory first block, which describes the whole stream. */
    class StreamInfo(
        val minimumBlockSize: Int,
        val maximumBlockSize: Int,
        val minimumFrameSize: Int,
        val maximumFrameSize: Int,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        /** Frames in the stream, or 0 when the encoder did not know. */
        val totalSamples: Long,
        /** MD5 of the unencoded audio, or all zeros when it was not computed. */
        val md5: ByteArray,
    ) {
        /** True when the file states an MD5 to check the decoding against. */
        val hasMd5: Boolean get() = md5.any { it.toInt() != 0 }
    }

    /** One entry of the seek table. */
    class SeekPoint(val firstSample: Long, val byteOffset: Long, val frameSamples: Int)

    /** Everything the metadata chain held. */
    class Parsed(
        val streamInfo: StreamInfo,
        val seekPoints: List<SeekPoint>,
        val tagBlocks: List<RawTagBlock>,
        /** First byte of the first audio frame. */
        val audioOffset: Long,
        /** Byte offset and size of every block, for a writer that rebuilds them. */
        val blocks: List<BlockLocation>,
    )

    /** Where a block sits, and what kind it is. */
    class BlockLocation(val type: Int, val offset: Long, val bodySize: Int, val isLast: Boolean)

    /**
     * Walks the chain from the start of the file.
     *
     * Throws when the magic number or the stream information block is missing,
     * because a file without those is not a FLAC file. A later block that is
     * malformed is skipped: its length field is what the chain walks on, and
     * one unreadable cue sheet should not cost the whole file.
     */
    fun parse(reader: SourceReader, options: DecodeOptions): Parsed {
        reader.seek(0)
        val magic = reader.readFourCc()
        if (magic != MAGIC) {
            throw CorruptStreamException("expected 'fLaC' but found '$magic'", 0)
        }

        var streamInfo: StreamInfo? = null
        val seekPoints = ArrayList<SeekPoint>()
        val tagBlocks = ArrayList<RawTagBlock>()
        val locations = ArrayList<BlockLocation>()
        val pictures = ArrayList<RawTagEntry>()
        var pictureBlockOffset = -1L

        var blockIndex = 0
        var foundLast = false
        while (!foundLast) {
            options.checkAllocation(
                (blockIndex.toLong() + 1L) * 32L,
                "remembering FLAC metadata block locations",
            )
            val headerAt = reader.position
            val header = reader.readI32Be()
            val isLast = (header ushr 31) and 1 == 1
            val type = (header ushr 24) and 0x7F
            val length = header and 0xFF_FFFF
            val bodyAt = reader.position
            if (blockIndex == 0 && type != TYPE_STREAMINFO) {
                throw CorruptStreamException(
                    "the first FLAC metadata block must be STREAMINFO, found type $type",
                    headerAt,
                )
            }
            if (type == 127) {
                throw CorruptStreamException("FLAC metadata block type 127 is forbidden", headerAt)
            }
            if (bodyAt > Long.MAX_VALUE - length) {
                throw CorruptStreamException("a FLAC metadata offset overflows 64 bits", bodyAt)
            }
            val bodyEnd = bodyAt + length
            if (reader.size >= 0 && bodyEnd > reader.size) {
                throw TruncatedStreamException(
                    "FLAC metadata block type $type declares $length bytes past end of input",
                    bodyAt,
                )
            }
            locations.add(BlockLocation(type, headerAt, length, isLast))

            when (type) {
                TYPE_STREAMINFO -> {
                    if (blockIndex != 0 || streamInfo != null) {
                        throw CorruptStreamException(
                            "STREAMINFO may occur only as the first FLAC metadata block",
                            headerAt,
                        )
                    }
                    if (length != 34) {
                        throw CorruptStreamException(
                            "a stream information block is exactly 34 bytes, this one is $length",
                            bodyAt,
                        )
                    }
                    streamInfo = readStreamInfo(reader, options, bodyAt)
                }

                TYPE_SEEKTABLE -> {
                    if (length % 18 != 0) {
                        throw CorruptStreamException(
                            "a FLAC seek table size must be a multiple of 18, this one is $length",
                            bodyAt,
                        )
                    }
                    val count = length / 18
                    options.checkAllocation(
                        count.toLong() * 24,
                        "reading $count FLAC seek points",
                    )
                    for (i in 0 until count) {
                        val firstSample = reader.readI64Be()
                        val byteOffset = reader.readI64Be()
                        val frameSamples = reader.readU16Be()
                        // A placeholder point states the largest possible
                        // sample number and means "not yet filled in".
                        if (firstSample != -1L) {
                            if (firstSample < 0 || byteOffset < 0 || frameSamples == 0) {
                                throw CorruptStreamException(
                                    "a FLAC seek point has an invalid sample, offset or block size",
                                    reader.position - 18,
                                )
                            }
                            val previous = seekPoints.lastOrNull()
                            if (previous != null && firstSample <= previous.firstSample) {
                                throw CorruptStreamException(
                                    "FLAC seek points are not in increasing sample order",
                                    reader.position - 18,
                                )
                            }
                            seekPoints.add(SeekPoint(firstSample, byteOffset, frameSamples))
                        }
                    }
                }

                TYPE_VORBIS_COMMENT -> {
                    if (length > 0) {
                        val body = reader.readBytes(length)
                        VorbisComment.parse(
                            body,
                            0,
                            body.size,
                            bodyAt,
                            length.toLong() + 4,
                            options,
                        )
                            ?.let { tagBlocks.add(it) }
                    }
                }

                TYPE_PICTURE -> {
                    if (length > 0) {
                        val body = reader.readBytes(length)
                        FlacPicture.parse(body, options)?.let { picture ->
                            if (pictureBlockOffset < 0) pictureBlockOffset = bodyAt
                            // Pictures are carried as the same entry shape a
                            // Vorbis comment uses, so one assembler handles
                            // both.
                            pictures.add(
                                RawTagEntry(
                                    "METADATA_BLOCK_PICTURE",
                                    binary = body,
                                    text = picture.mimeType,
                                ),
                            )
                        }
                    }
                }
            }

            reader.seek(bodyEnd)
            blockIndex++
            if (isLast) {
                foundLast = true
            }
        }

        val info = streamInfo
            ?: throw CorruptStreamException("the file has no stream information block", 4)

        if (pictures.isNotEmpty()) {
            tagBlocks.add(
                RawTagBlock(TagFormat.VORBIS_COMMENT, pictureBlockOffset, 0, pictures),
            )
        }

        return Parsed(
            streamInfo = info,
            seekPoints = seekPoints,
            tagBlocks = tagBlocks,
            audioOffset = reader.position,
            blocks = locations,
        )
    }

    /**
     * Reads a stream information block out of an array.
     *
     * Ogg carries the block inside its own first packet rather than in a file
     * at a known offset, so it arrives already in memory. Parsing it in one
     * place keeps the two carriages agreeing about what the fields mean.
     */
    fun parseStreamInfoBody(
        bytes: ByteArray,
        at: Int,
        options: DecodeOptions,
        offset: Long,
    ): StreamInfo {
        if (at + 34 > bytes.size) {
            throw CorruptStreamException(
                "a stream information block is 34 bytes, and only ${bytes.size - at} are here",
                offset,
            )
        }
        val minimumBlockSize = readU16(bytes, at)
        val maximumBlockSize = readU16(bytes, at + 2)
        val minimumFrameSize = readU24(bytes, at + 4)
        val maximumFrameSize = readU24(bytes, at + 7)
        var packed = 0L
        for (i in 0 until 8) {
            packed = (packed shl 8) or (bytes[at + 10 + i].toLong() and 0xFF)
        }
        return finishStreamInfo(
            minimumBlockSize = minimumBlockSize,
            maximumBlockSize = maximumBlockSize,
            minimumFrameSize = minimumFrameSize,
            maximumFrameSize = maximumFrameSize,
            packed = packed,
            md5 = bytes.copyOfRange(at + 18, at + 34),
            options = options,
            at = offset,
        )
    }

    private fun readU16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun readU24(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 16) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            (bytes[at + 2].toInt() and 0xFF)

    private fun readStreamInfo(
        reader: SourceReader,
        options: DecodeOptions,
        at: Long,
    ): StreamInfo {
        val minimumBlockSize = reader.readU16Be()
        val maximumBlockSize = reader.readU16Be()
        val minimumFrameSize = reader.readU24Be()
        val maximumFrameSize = reader.readU24Be()

        // Sample rate, channels, bit depth and sample count share 64 bits and
        // do not fall on byte boundaries.
        val packed = reader.readI64Be()
        val md5 = reader.readBytes(16)
        return finishStreamInfo(
            minimumBlockSize = minimumBlockSize,
            maximumBlockSize = maximumBlockSize,
            minimumFrameSize = minimumFrameSize,
            maximumFrameSize = maximumFrameSize,
            packed = packed,
            md5 = md5,
            options = options,
            at = at,
        )
    }

    /** Validates the packed fields and builds the block, however it was read. */
    private fun finishStreamInfo(
        minimumBlockSize: Int,
        maximumBlockSize: Int,
        minimumFrameSize: Int,
        maximumFrameSize: Int,
        packed: Long,
        md5: ByteArray,
        options: DecodeOptions,
        at: Long,
    ): StreamInfo {
        val sampleRate = ((packed ushr 44) and 0xFFFFF).toInt()
        val channels = ((packed ushr 41) and 0x7).toInt() + 1
        val bitsPerSample = ((packed ushr 36) and 0x1F).toInt() + 1
        val totalSamples = packed and 0xF_FFFF_FFFFL

        if (sampleRate == 0) {
            throw CorruptStreamException("a FLAC stream cannot have a sample rate of zero", at)
        }
        options.checkStreamShape(sampleRate, channels, at)
        if (bitsPerSample < 4 || bitsPerSample > 32) {
            throw CorruptStreamException(
                "FLAC carries 4 to 32 bits per sample, this file says $bitsPerSample", at,
            )
        }
        if (minimumBlockSize !in 16..65_535 || maximumBlockSize !in 16..65_535) {
            throw CorruptStreamException(
                "a FLAC block is 16 to 65535 samples, this file says " +
                    "$minimumBlockSize..$maximumBlockSize",
                at,
            )
        }
        if (minimumBlockSize > maximumBlockSize) {
            throw CorruptStreamException(
                "FLAC minimum block size $minimumBlockSize exceeds maximum $maximumBlockSize",
                at,
            )
        }
        if (minimumFrameSize != 0 && maximumFrameSize != 0 &&
            minimumFrameSize > maximumFrameSize
        ) {
            throw CorruptStreamException(
                "FLAC minimum frame size $minimumFrameSize exceeds maximum $maximumFrameSize",
                at,
            )
        }

        return StreamInfo(
            minimumBlockSize = minimumBlockSize,
            maximumBlockSize = maximumBlockSize,
            minimumFrameSize = minimumFrameSize,
            maximumFrameSize = maximumFrameSize,
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            totalSamples = totalSamples,
            md5 = md5,
        )
    }
}

package io.github.yuroyami.audiokodec.internal.flac

import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.bits.BitReaderMsb
import io.github.yuroyami.audiokodec.internal.io.SourceReader

/**
 * Decodes FLAC frames into integer samples.
 *
 * A frame stands on its own: its header states the block size, the sample
 * rate, the channel arrangement and the bit depth, so a decoder can start at
 * any frame without having read the ones before it. That is what makes
 * seeking cheap, and it is why this class takes a byte offset rather than a
 * position in a stream.
 *
 * Written from RFC 9639.
 */
internal class FlacFrameReader(
    private val reader: SourceReader,
    private val streamInfo: FlacMetadata.StreamInfo,
    private val options: DecodeOptions,
) {
    /** One decoded frame. */
    class Frame(
        /** One array per channel, each holding [blockSize] samples. */
        val channels: Array<IntArray>,
        val blockSize: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        /** Frame number for a fixed-size stream, or -1 for a variable one. */
        val frameNumber: Long,
        /** First sample of the frame for a variable-size stream, or -1. */
        val sampleNumber: Long,
        /** Bytes the frame occupied, so the caller knows where the next one is. */
        val byteLength: Int,
    )

    private var buffer = allocateInitialBuffer()

    /** Per-channel working space, reused across frames. */
    private var channelBuffers: Array<IntArray> = allocateChannelBuffers(
        streamInfo.channels,
        streamInfo.maximumBlockSize,
    )

    /**
     * Predictor residuals can be wider than the samples they reconstruct.
     *
     * A 32-bit sample following the opposite integer extreme already needs a
     * 33-bit order-one residual, and a higher-order predictor can be wider
     * still. Keeping every subframe in `Long` until reconstruction is complete
     * avoids turning a valid residual into a wrapped `Int`.
     */
    private var longChannelBuffers: Array<LongArray> = emptyArray()

    private fun allocateInitialBuffer(): ByteArray {
        val size = initialBufferSize()
        options.checkAllocation(size.toLong(), "allocating the initial FLAC frame buffer")
        return ByteArray(size)
    }

    private fun allocateChannelBuffers(channels: Int, blockSize: Int): Array<IntArray> {
        options.checkAllocation(
            channels.toLong() * blockSize * Int.SIZE_BYTES,
            "allocating FLAC channel buffers",
        )
        return Array(channels) { IntArray(blockSize) }
    }

    private fun allocateLongChannelBuffers(channels: Int, blockSize: Int): Array<LongArray> {
        options.checkAllocation(
            channels.toLong() * blockSize * Long.SIZE_BYTES,
            "allocating wide FLAC predictor buffers",
        )
        return Array(channels) { LongArray(blockSize) }
    }

    private fun initialBufferSize(): Int {
        val stated = streamInfo.maximumFrameSize
        if (stated > 0) return stated + 64
        return verbatimFrameBound().toInt()
    }

    /**
     * The size of the largest block stored verbatim: every sample whole, with
     * a byte to spare for the side channel's extra bit, plus a header. Every
     * encoder falls back to this when prediction would do worse.
     */
    private fun verbatimFrameBound(): Long {
        val perSample = (streamInfo.bitsPerSample + 7) / 8 + 1
        return streamInfo.maximumBlockSize.toLong() * streamInfo.channels * perSample + 256
    }

    /**
     * Decodes the frame that starts at [at].
     *
     * Returns null at end of stream. Throws [CorruptStreamException] when the
     * bytes at [at] are not a frame, which is what a scanning seek relies on
     * to reject a false sync word.
     */
    fun decodeAt(at: Long): Frame? {
        while (true) {
            val available = fill(at)
            if (available < 5) return null
            try {
                return decodeFrom(buffer, available, at)
            } catch (short: TruncatedStreamException) {
                // A frame that runs off the end of a full buffer may only be
                // longer than the buffer. One that runs off a short read has
                // run off the end of the source.
                if (available < buffer.size) throw short
                grow()
            }
        }
    }

    /**
     * Decodes a frame that is already in memory.
     *
     * FLAC inside Ogg arrives as packets rather than as a run of bytes in the
     * file, one frame per packet, so there is nothing to seek to. [at] is only
     * used in error messages.
     */
    fun decodePacket(bytes: ByteArray, length: Int, at: Long): Frame? {
        if (length < 5) return null
        return decodeFrom(bytes, length, at)
    }

    private fun decodeFrom(frame: ByteArray, available: Int, at: Long): Frame? {
        val reading = BitReaderMsb(frame, 0, available)
        val header = readHeader(reading, at)

        // The header checksum has to be right before anything after it is
        // believed. A frame whose header is wrong is not a frame.
        val headerBytes = reading.bytePosition
        val expectedCrc8 = frame[headerBytes - 1].toInt() and 0xFF
        val actualCrc8 = FlacCrc.crc8(frame, 0, headerBytes - 1)
        if (expectedCrc8 != actualCrc8) {
            throw CorruptStreamException(
                "the frame header checksum is $expectedCrc8 but the header hashes to $actualCrc8",
                at,
            )
        }

        val channels = header.channels
        if (channelBuffers.size < channels || channelBuffers[0].size < header.blockSize) {
            channelBuffers = allocateChannelBuffers(channels, header.blockSize)
        }
        if (longChannelBuffers.size < channels ||
            longChannelBuffers.isEmpty() ||
            longChannelBuffers[0].size < header.blockSize
        ) {
            longChannelBuffers = allocateLongChannelBuffers(channels, header.blockSize)
        }

        for (channel in 0 until channels) {
            // The side channel of a decorrelated pair carries one extra bit,
            // because a difference needs one more bit than its operands.
            val extra = when (header.stereoMode) {
                StereoMode.LEFT_SIDE, StereoMode.MID_SIDE -> if (channel == 1) 1 else 0
                StereoMode.RIGHT_SIDE -> if (channel == 0) 1 else 0
                StereoMode.INDEPENDENT -> 0
            }
            val subframeBits = header.bitsPerSample + extra
            readSubframeLong(
                reading,
                longChannelBuffers[channel],
                header.blockSize,
                subframeBits,
                at,
            )
        }

        reading.alignToByte()
        val frameBytes = reading.bytePosition + 2
        if (frameBytes > available) {
            throw TruncatedStreamException("the frame runs past the end of the file", at)
        }
        val storedCrc16 = ((frame[frameBytes - 2].toInt() and 0xFF) shl 8) or
            (frame[frameBytes - 1].toInt() and 0xFF)
        val actualCrc16 = FlacCrc.crc16(frame, 0, frameBytes - 2)
        if (storedCrc16 != actualCrc16) {
            throw CorruptStreamException(
                "the frame checksum is $storedCrc16 but the frame hashes to $actualCrc16", at,
            )
        }

        undecorrelateLong(
            header.stereoMode,
            longChannelBuffers,
            channelBuffers,
            header.blockSize,
            header.bitsPerSample,
            at,
        )

        return Frame(
            channels = channelBuffers,
            blockSize = header.blockSize,
            sampleRate = header.sampleRate,
            bitsPerSample = header.bitsPerSample,
            frameNumber = header.frameNumber,
            sampleNumber = header.sampleNumber,
            byteLength = frameBytes,
        )
    }

    /**
     * Doubles the frame buffer, for a frame that did not fit in it.
     *
     * The buffer starts at the maximum frame size the stream information
     * states, or at the size of a verbatim frame when it states none, so this
     * runs only for a frame larger than that. It grows because one frame
     * needs it, never because the source has more bytes to give, so memory
     * follows the largest frame and not the length of the recording.
     */
    private fun grow() {
        // With a stated maximum, a frame beyond both it and the size of the
        // same samples stored verbatim is damaged, or a sync word that was
        // never a frame. More memory would not help, and a seek scanning for
        // frames needs to hear that it is corrupt. Some files understate the
        // maximum, which is why the verbatim size is allowed as well.
        val stated = streamInfo.maximumFrameSize
        if (stated > 0 && buffer.size >= maxOf(stated + 64L, verbatimFrameBound())) {
            throw CorruptStreamException(
                "a FLAC frame runs past both the $stated-byte maximum its stream information " +
                    "states and the size of a verbatim frame",
            )
        }
        val next = minOf(buffer.size.toLong() * 2L, Int.MAX_VALUE.toLong() - 8)
        if (next <= buffer.size) {
            throw CorruptStreamException("a FLAC frame is larger than any array can hold")
        }
        options.checkAllocation(next, "growing the FLAC frame buffer")
        buffer = ByteArray(next.toInt())
    }

    private fun fill(at: Long): Int {
        reader.seek(at)
        return reader.readSome(buffer, 0, buffer.size).coerceAtLeast(0)
    }

    private enum class StereoMode { INDEPENDENT, LEFT_SIDE, RIGHT_SIDE, MID_SIDE }

    private class Header(
        val blockSize: Int,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val stereoMode: StereoMode,
        val frameNumber: Long,
        val sampleNumber: Long,
    )

    private fun readHeader(reading: BitReaderMsb, at: Long): Header {
        val sync = reading.readBits(14)
        if (sync != 0x3FFE) {
            throw CorruptStreamException("no frame sync word at this position", at)
        }
        if (reading.readBits(1) != 0) {
            throw CorruptStreamException("a reserved frame header bit is set", at)
        }
        val variableBlockSize = reading.readBits(1) == 1

        val blockSizeCode = reading.readBits(4)
        val sampleRateCode = reading.readBits(4)
        val channelCode = reading.readBits(4)
        val bitDepthCode = reading.readBits(3)
        if (reading.readBits(1) != 0) {
            throw CorruptStreamException("a reserved frame header bit is set", at)
        }

        // The frame or sample number is coded the way UTF-8 codes a character,
        // extended to seven bytes so a 36-bit sample number fits.
        val number = readUtf8Number(reading, at)

        val blockSize = when (blockSizeCode) {
            0 -> throw CorruptStreamException("the frame block size code is reserved", at)
            1 -> 192
            in 2..5 -> 576 shl (blockSizeCode - 2)
            6 -> reading.readBits(8) + 1
            7 -> reading.readBits(16) + 1
            else -> 256 shl (blockSizeCode - 8)
        }

        val sampleRate = when (sampleRateCode) {
            0 -> streamInfo.sampleRate
            1 -> 88_200
            2 -> 176_400
            3 -> 192_000
            4 -> 8_000
            5 -> 16_000
            6 -> 22_050
            7 -> 24_000
            8 -> 32_000
            9 -> 44_100
            10 -> 48_000
            11 -> 96_000
            12 -> reading.readBits(8) * 1_000
            13 -> reading.readBits(16)
            14 -> reading.readBits(16) * 10
            else -> throw CorruptStreamException("the frame sample rate code is invalid", at)
        }

        val channels: Int
        val stereoMode: StereoMode
        when (channelCode) {
            in 0..7 -> {
                channels = channelCode + 1
                stereoMode = StereoMode.INDEPENDENT
            }
            8 -> {
                channels = 2
                stereoMode = StereoMode.LEFT_SIDE
            }
            9 -> {
                channels = 2
                stereoMode = StereoMode.RIGHT_SIDE
            }
            10 -> {
                channels = 2
                stereoMode = StereoMode.MID_SIDE
            }
            else -> throw CorruptStreamException(
                "the frame channel arrangement code $channelCode is reserved", at,
            )
        }

        val bitsPerSample = when (bitDepthCode) {
            0 -> streamInfo.bitsPerSample
            1 -> 8
            2 -> 12
            4 -> 16
            5 -> 20
            6 -> 24
            7 -> 32
            else -> throw CorruptStreamException("the frame bit depth code is reserved", at)
        }

        // The checksum byte is consumed here so the caller's byte position is
        // past the whole header.
        reading.readBits(8)

        options.checkStreamShape(sampleRate, channels, at)
        // The final frame of a fixed-block stream may be shorter than the
        // STREAMINFO minimum (many encoders leave both fields at the regular
        // block size), but no frame may exceed the stated maximum.
        if (blockSize > streamInfo.maximumBlockSize) {
            throw CorruptStreamException(
                "a FLAC frame holds $blockSize samples, above STREAMINFO's maximum of " +
                    "${streamInfo.maximumBlockSize}",
                at,
            )
        }
        if (channels != streamInfo.channels) {
            throw CorruptStreamException(
                "a FLAC frame carries $channels channels, but STREAMINFO declares " +
                    "${streamInfo.channels}",
                at,
            )
        }
        if (bitsPerSample != streamInfo.bitsPerSample) {
            throw CorruptStreamException(
                "a FLAC frame uses $bitsPerSample bits per sample, but STREAMINFO declares " +
                    "${streamInfo.bitsPerSample}",
                at,
            )
        }
        if (sampleRate != streamInfo.sampleRate) {
            throw CorruptStreamException(
                "a FLAC frame uses $sampleRate Hz, but STREAMINFO declares " +
                    "${streamInfo.sampleRate} Hz",
                at,
            )
        }

        return Header(
            blockSize = blockSize,
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            stereoMode = stereoMode,
            frameNumber = if (variableBlockSize) -1L else number,
            sampleNumber = if (variableBlockSize) number else -1L,
        )
    }

    /**
     * Reads the extended UTF-8 number a frame header carries.
     *
     * The coding is UTF-8's, with one more length added so that a 36-bit
     * sample number fits in seven bytes.
     */
    private fun readUtf8Number(reading: BitReaderMsb, at: Long): Long {
        val first = reading.readBits(8)
        var length = 0
        var value: Long
        when {
            first and 0x80 == 0 -> return first.toLong()
            first and 0xE0 == 0xC0 -> {
                length = 1
                value = (first and 0x1F).toLong()
            }
            first and 0xF0 == 0xE0 -> {
                length = 2
                value = (first and 0x0F).toLong()
            }
            first and 0xF8 == 0xF0 -> {
                length = 3
                value = (first and 0x07).toLong()
            }
            first and 0xFC == 0xF8 -> {
                length = 4
                value = (first and 0x03).toLong()
            }
            first and 0xFE == 0xFC -> {
                length = 5
                value = (first and 0x01).toLong()
            }
            first == 0xFE -> {
                length = 6
                value = 0L
            }
            else -> throw CorruptStreamException("the frame number is not validly coded", at)
        }
        repeat(length) {
            val next = reading.readBits(8)
            if (next and 0xC0 != 0x80) {
                throw CorruptStreamException("the frame number is not validly coded", at)
            }
            value = (value shl 6) or (next and 0x3F).toLong()
        }
        return value
    }

    /**
     * The 32-bit stereo mappings carry a 33-bit side channel, and predictor
     * residuals can be wider than their reconstructed PCM samples. Keep the
     * complete subframe in `Long`s; public integer PCM remains 32-bit.
     */
    private fun readSubframeLong(
        reading: BitReaderMsb,
        out: LongArray,
        blockSize: Int,
        bitsPerSample: Int,
        at: Long,
    ) {
        if (reading.readBits(1) != 0) {
            throw CorruptStreamException("a reserved subframe bit is set", at)
        }
        val type = reading.readBits(6)
        val hasWastedBits = reading.readBits(1) == 1
        val wastedBits = if (hasWastedBits) reading.readUnary() + 1 else 0
        val effectiveBits = bitsPerSample - wastedBits
        if (effectiveBits !in 1..33) {
            throw CorruptStreamException(
                "a subframe claims $wastedBits wasted bits of $bitsPerSample", at,
            )
        }

        when {
            type == 0 -> {
                val value = reading.readSignedBitsLong(effectiveBits)
                for (i in 0 until blockSize) out[i] = value
            }

            type == 1 -> {
                for (i in 0 until blockSize) {
                    out[i] = reading.readSignedBitsLong(effectiveBits)
                }
            }

            type in 8..12 -> {
                val order = type - 8
                checkPredictorOrder(order, blockSize, at)
                for (i in 0 until order) {
                    out[i] = reading.readSignedBitsLong(effectiveBits)
                }
                readResidualLong(reading, out, blockSize, order, at)
                restoreFixedLong(out, blockSize, order)
            }

            type in 32..63 -> {
                val order = (type and 0x1F) + 1
                checkPredictorOrder(order, blockSize, at)
                for (i in 0 until order) {
                    out[i] = reading.readSignedBitsLong(effectiveBits)
                }
                val precision = reading.readBits(4) + 1
                if (precision == 16) {
                    throw CorruptStreamException("the predictor precision code is invalid", at)
                }
                val shift = reading.readSignedBits(5)
                if (shift < 0) {
                    throw CorruptStreamException("a negative predictor shift is not allowed", at)
                }
                val coefficients = IntArray(order) { reading.readSignedBits(precision) }
                readResidualLong(reading, out, blockSize, order, at)
                restoreLpcLong(out, blockSize, order, coefficients, shift)
            }

            else -> throw UnsupportedFeatureException(
                "FLAC subframe type $type",
                "this file uses subframe type $type, which the format reserves",
            )
        }

        if (wastedBits > 0) {
            val minimumBeforeShift = -(1L shl (bitsPerSample - wastedBits - 1))
            val maximumBeforeShift = (1L shl (bitsPerSample - wastedBits - 1)) - 1L
            for (i in 0 until blockSize) {
                if (out[i] !in minimumBeforeShift..maximumBeforeShift) {
                    throw CorruptStreamException(
                        "a FLAC subframe sample overflows when its wasted bits are restored",
                        at,
                    )
                }
                out[i] = out[i] shl wastedBits
            }
        }
        val minimum = -(1L shl (bitsPerSample - 1))
        val maximum = (1L shl (bitsPerSample - 1)) - 1
        for (i in 0 until blockSize) {
            if (out[i] !in minimum..maximum) {
                throw CorruptStreamException(
                    "a FLAC side-channel sample does not fit in $bitsPerSample bits", at,
                )
            }
        }
    }

    /**
     * A predictor's warm-up samples are the first samples of the block, so an
     * order larger than the block would write past its end before anything
     * else about the subframe could be checked.
     */
    private fun checkPredictorOrder(order: Int, blockSize: Int, at: Long) {
        if (order > blockSize) {
            throw CorruptStreamException(
                "a predictor of order $order cannot fit a $blockSize-sample block",
                at,
            )
        }
    }

    private fun readResidualLong(
        reading: BitReaderMsb,
        out: LongArray,
        blockSize: Int,
        predictorOrder: Int,
        at: Long,
    ) {
        val method = reading.readBits(2)
        if (method > 1) {
            throw UnsupportedFeatureException(
                "FLAC residual coding method $method",
                "this file uses residual coding method $method, which the format reserves",
            )
        }
        val parameterBits = if (method == 0) 4 else 5
        val escapeValue = if (method == 0) 0x0F else 0x1F
        val partitionOrder = reading.readBits(4)
        val partitions = 1 shl partitionOrder
        if (blockSize % partitions != 0) {
            throw CorruptStreamException(
                "a block of $blockSize samples does not divide into $partitions partitions", at,
            )
        }
        val partitionSize = blockSize shr partitionOrder
        if (partitionSize < predictorOrder) {
            throw CorruptStreamException(
                "the first partition is shorter than the predictor order", at,
            )
        }

        var position = predictorOrder
        for (partition in 0 until partitions) {
            val count = if (partition == 0) partitionSize - predictorOrder else partitionSize
            val parameter = reading.readBits(parameterBits)
            if (parameter == escapeValue) {
                val width = reading.readBits(5)
                if (width == 0) {
                    for (i in 0 until count) out[position++] = 0
                } else {
                    for (i in 0 until count) {
                        out[position++] = reading.readSignedBitsLong(width)
                    }
                }
            } else {
                for (i in 0 until count) {
                    val quotient = reading.readUnary()
                    val remainder = if (parameter > 0) {
                        reading.readBits(parameter).toLong() and 0xFFFF_FFFFL
                    } else {
                        0L
                    }
                    val folded = (quotient.toLong() shl parameter) or remainder
                    out[position++] = if (folded and 1L != 0L) {
                        -(folded ushr 1) - 1
                    } else {
                        folded ushr 1
                    }
                }
            }
        }
    }

    private fun restoreFixedLong(out: LongArray, blockSize: Int, order: Int) {
        for (i in order until blockSize) {
            val predicted = when (order) {
                0 -> 0L
                1 -> out[i - 1]
                2 -> 2L * out[i - 1] - out[i - 2]
                3 -> 3L * out[i - 1] - 3L * out[i - 2] + out[i - 3]
                else -> {
                    4L * out[i - 1] - 6L * out[i - 2] +
                        4L * out[i - 3] - out[i - 4]
                }
            }
            out[i] += predicted
        }
    }

    private fun restoreLpcLong(
        out: LongArray,
        blockSize: Int,
        order: Int,
        coefficients: IntArray,
        shift: Int,
    ) {
        for (i in order until blockSize) {
            var sum = 0L
            for (j in 0 until order) {
                sum += coefficients[j].toLong() * out[i - 1 - j]
            }
            out[i] += sum shr shift
        }
    }

    /**
     * Undoes stereo decorrelation.
     *
     * Two channels of music are highly correlated, so FLAC often stores one of
     * them as a difference. The mid and side case has to put back a bit that
     * the encoder dropped, which is what the shift and the low bit of the side
     * channel are doing.
     */
    private fun undecorrelateLong(
        mode: StereoMode,
        encoded: Array<LongArray>,
        channels: Array<IntArray>,
        blockSize: Int,
        bitsPerSample: Int,
        at: Long,
    ) {
        when (mode) {
            StereoMode.INDEPENDENT -> {
                for (channel in channels.indices) {
                    for (i in 0 until blockSize) {
                        channels[channel][i] = checkedInt(
                            encoded[channel][i],
                            bitsPerSample,
                            "a FLAC sample",
                            at,
                        )
                    }
                }
            }

            StereoMode.LEFT_SIDE -> {
                val encodedLeft = encoded[0]
                val encodedSide = encoded[1]
                val left = channels[0]
                val right = channels[1]
                for (i in 0 until blockSize) {
                    left[i] = checkedInt(
                        encodedLeft[i],
                        bitsPerSample,
                        "a decorrelated FLAC left-channel sample",
                        at,
                    )
                    right[i] = checkedInt(
                        encodedLeft[i] - encodedSide[i],
                        bitsPerSample,
                        "a decorrelated FLAC right-channel sample",
                        at,
                    )
                }
            }

            StereoMode.RIGHT_SIDE -> {
                val encodedSide = encoded[0]
                val encodedRight = encoded[1]
                val left = channels[0]
                val right = channels[1]
                for (i in 0 until blockSize) {
                    left[i] = checkedInt(
                        encodedSide[i] + encodedRight[i],
                        bitsPerSample,
                        "a decorrelated FLAC left-channel sample",
                        at,
                    )
                    right[i] = checkedInt(
                        encodedRight[i],
                        bitsPerSample,
                        "a decorrelated FLAC right-channel sample",
                        at,
                    )
                }
            }

            StereoMode.MID_SIDE -> {
                val encodedMid = encoded[0]
                val encodedSide = encoded[1]
                val left = channels[0]
                val right = channels[1]
                for (i in 0 until blockSize) {
                    val s = encodedSide[i]
                    // The encoder threw away the low bit of the mid channel,
                    // and the low bit of the side channel is what tells the
                    // decoder what it was.
                    val m = (encodedMid[i] shl 1) or (s and 1L)
                    left[i] = checkedInt(
                        (m + s) shr 1,
                        bitsPerSample,
                        "a decorrelated FLAC left-channel sample",
                        at,
                    )
                    right[i] = checkedInt(
                        (m - s) shr 1,
                        bitsPerSample,
                        "a decorrelated FLAC right-channel sample",
                        at,
                    )
                }
            }
        }
    }

    private fun checkedInt(value: Long, bits: Int, what: String, at: Long): Int {
        val minimum = -(1L shl (bits - 1))
        val maximum = (1L shl (bits - 1)) - 1
        if (value !in minimum..maximum) {
            throw CorruptStreamException("$what does not fit in $bits bits", at)
        }
        return value.toInt()
    }
}

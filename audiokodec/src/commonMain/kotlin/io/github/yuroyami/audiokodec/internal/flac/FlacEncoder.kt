package io.github.yuroyami.audiokodec.internal.flac

import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioSink
import io.github.yuroyami.audiokodec.Dither
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.IntPcmSource
import io.github.yuroyami.audiokodec.PcmSource
import io.github.yuroyami.audiokodec.readChecked
import io.github.yuroyami.audiokodec.internal.bits.BitWriterMsb
import io.github.yuroyami.audiokodec.internal.codec.IntegerPcmConverter
import io.github.yuroyami.audiokodec.internal.tag.FlacPicture
import io.github.yuroyami.audiokodec.internal.tag.VorbisComment
import io.github.yuroyami.audiokodec.tag.TagBundle
import kotlin.math.abs

/**
 * Encodes FLAC.
 *
 * The compression comes from three decisions taken per block. Which predictor
 * fits the samples best, whether the two channels of a stereo pair are cheaper
 * stored as themselves or as a difference, and how to split the residual into
 * partitions that each get their own Rice parameter. This encoder measures
 * every candidate in bits and picks the smallest, which is what the reference
 * encoder does as well.
 *
 * Written from RFC 9639.
 */
internal class FlacEncoder(
    private val format: AudioFormat,
    private val bitsPerSample: Int,
    /** Samples per block. 4096 is what every encoder uses for music. */
    private val blockSize: Int = 4096,
    /** Highest linear predictor order to try. Zero uses fixed predictors only. */
    private val maxLpcOrder: Int = 8,
    /** How finely to partition the residual, as a power of two. */
    private val maxPartitionOrder: Int = 4,
    /** Noise applied before reducing Float32 to the requested FLAC depth. */
    private val dither: Dither = Dither.None,
) {
    init {
        // The residual of a predictor needs a few bits more than the samples
        // it came from, and this encoder keeps residuals in `Int`. Twenty-four
        // bits leaves the headroom that needs; thirty-two does not, and the
        // honest answer is to say so rather than overflow quietly.
        require(bitsPerSample == 8 || bitsPerSample == 16 || bitsPerSample == 24) {
            "AudioKodec writes 8, 16 or 24-bit FLAC, not $bitsPerSample"
        }
        require(blockSize in 16..65_535) { "a FLAC block is 16 to 65535 samples, not $blockSize" }
        require(maxLpcOrder in 0..MAX_LPC_ORDER) {
            "the linear predictor order is 0 to $MAX_LPC_ORDER, not $maxLpcOrder"
        }
        require(format.channels in 1..8) {
            "FLAC carries 1 to 8 channels, not ${format.channels}"
        }
        require(format.sampleRate in 1..0xF_FFFF) {
            "FLAC carries sample rates up to 1048575 Hz, not ${format.sampleRate}"
        }
    }

    private val channels = format.channels

    /** Per-channel samples for the block being encoded. */
    private val blocks = Array(channels) { IntArray(blockSize) }

    /** Working space for the four stereo candidates. */
    private val mid = IntArray(if (channels == 2) blockSize else 0)
    private val side = IntArray(if (channels == 2) blockSize else 0)

    private val frameWriter = BitWriterMsb(blockSize * channels + 1024)
    private val hash = Md5()

    fun encode(pcm: PcmSource, tags: TagBundle?, sink: AudioSink) {
        val readBuffer = FloatArray(blockSize * channels)
        encodeBlocks(pcm.frameCount, tags, sink) {
            var filled = 0
            while (filled < blockSize) {
                val got = pcm.readChecked(readBuffer, filled * channels, blockSize - filled)
                if (got == 0) break
                filled += got
            }
            quantiseInto(readBuffer, filled)
            filled
        }
    }

    fun encode(pcm: IntPcmSource, tags: TagBundle?, sink: AudioSink) {
        val conversion = IntegerPcmConverter(pcm.bitDepth, bitsPerSample, dither)
        val readBuffer = IntArray(blockSize * channels)
        encodeBlocks(pcm.frameCount, tags, sink) {
            var filled = 0
            while (filled < blockSize) {
                val got = pcm.readChecked(readBuffer, filled * channels, blockSize - filled)
                if (got == 0) break
                filled += got
            }
            conversion.convertInPlace(readBuffer, filled * channels)
            for (frame in 0 until filled) {
                for (channel in 0 until channels) blocks[channel][frame] = readBuffer[frame * channels + channel]
            }
            filled
        }
    }

    private fun encodeBlocks(
        declaredFrames: Long?,
        tags: TagBundle?,
        sink: AudioSink,
        readBlock: () -> Int,
    ) {
        require(declaredFrames == null || declaredFrames in 0..MAX_TOTAL_SAMPLES) {
            "FLAC frameCount must be 0..$MAX_TOTAL_SAMPLES, was $declaredFrames"
        }

        val comment = tags?.let { VorbisComment.build(it, includePictures = false) }
        if (comment != null && comment.size > FlacMetadata.MAX_BODY_BYTES) {
            throw AudioKodecException(
                "the FLAC Vorbis comment is ${comment.size} bytes; metadata blocks are " +
                    "limited to ${FlacMetadata.MAX_BODY_BYTES}",
            )
        }
        // Prepare every picture before output, including size checks on a
        // later image that would otherwise leave a partly written stream.
        val pictures = tags?.pictures.orEmpty().map {
            FlacPicture.prepare(it, FlacMetadata.MAX_BODY_BYTES)
        }

        sink.write(MAGIC)

        // The stream information block is written with what is known now and
        // corrected at the end. A sink that cannot be patched needs the frame
        // count in advance, and the values that depend on the audio are then
        // left at zero, which the format allows and every decoder handles.
        val streamInfoAt = sink.position + 4
        writeMetadata(sink, declaredFrames, comment, pictures)

        var totalFrames = 0L
        var minimumFrameSize = Int.MAX_VALUE
        var maximumFrameSize = 0
        var frameNumber = 0L

        while (true) {
            val filled = readBlock()
            if (filled == 0) break

            feedHash(filled)

            val bytes = encodeFrame(frameNumber, filled)
            sink.write(frameWriter.rawBuffer(), 0, bytes)
            if (bytes < minimumFrameSize) minimumFrameSize = bytes
            if (bytes > maximumFrameSize) maximumFrameSize = bytes

            if (totalFrames > MAX_TOTAL_SAMPLES - filled) {
                throw AudioKodecException(
                    "FLAC's 36-bit sample count cannot represent more than " +
                        "$MAX_TOTAL_SAMPLES frames",
                )
            }
            totalFrames += filled
            frameNumber++
            if (filled < blockSize) break
        }

        if (declaredFrames != null && declaredFrames != totalFrames) {
            throw AudioKodecException(
                "the source promised $declaredFrames frames and produced $totalFrames; " +
                    "source frameCount must describe the complete stream",
            )
        }
        if (!sink.canPatch) return

        // Correct the stream information now that the audio is known.
        val patched = ByteArray(34)
        writeStreamInfoBody(
            patched,
            totalFrames = totalFrames,
            minimumFrameSize = if (minimumFrameSize == Int.MAX_VALUE) 0 else minimumFrameSize,
            maximumFrameSize = maximumFrameSize,
            md5 = hash.digest(),
        )
        sink.patchAt(streamInfoAt, patched)
    }

    private fun writeMetadata(
        sink: AudioSink,
        declaredFrames: Long?,
        comment: ByteArray?,
        pictures: List<FlacPicture.Prepared>,
    ) {
        val hasMore = comment != null || pictures.isNotEmpty() || PADDING_BYTES > 0

        val header = ByteArray(4)
        header[0] = (if (hasMore) FlacMetadata.TYPE_STREAMINFO else 0x80).toByte()
        header[1] = 0
        header[2] = 0
        header[3] = 34
        sink.write(header)

        val body = ByteArray(34)
        writeStreamInfoBody(
            body,
            totalFrames = declaredFrames ?: 0L,
            minimumFrameSize = 0,
            maximumFrameSize = 0,
            md5 = ByteArray(16),
        )
        sink.write(body)

        if (comment != null) {
            writeMetadataBlock(sink, FlacMetadata.TYPE_VORBIS_COMMENT, comment,
                pictures.isEmpty() && PADDING_BYTES == 0)
        }

        for ((index, picture) in pictures.withIndex()) {
            writeMetadataHeader(sink, FlacMetadata.TYPE_PICTURE, picture.size,
                index == pictures.lastIndex && PADDING_BYTES == 0)
            picture.writeTo(sink)
        }

        if (PADDING_BYTES > 0) {
            // Padding leaves room for a later tag edit to grow without the
            // audio having to move.
            writeMetadataBlock(sink, FlacMetadata.TYPE_PADDING, ByteArray(PADDING_BYTES), true)
        }
    }

    private fun writeMetadataBlock(sink: AudioSink, type: Int, body: ByteArray, last: Boolean) {
        writeMetadataHeader(sink, type, body.size, last)
        sink.write(body)
    }

    private fun writeMetadataHeader(sink: AudioSink, type: Int, size: Int, last: Boolean) {
        val header = ByteArray(4)
        header[0] = ((if (last) 0x80 else 0) or type).toByte()
        header[1] = (size ushr 16).toByte()
        header[2] = (size ushr 8).toByte()
        header[3] = size.toByte()
        sink.write(header)
    }

    private fun writeStreamInfoBody(
        out: ByteArray,
        totalFrames: Long,
        minimumFrameSize: Int,
        maximumFrameSize: Int,
        md5: ByteArray,
    ) {
        out[0] = (blockSize ushr 8).toByte()
        out[1] = blockSize.toByte()
        out[2] = (blockSize ushr 8).toByte()
        out[3] = blockSize.toByte()
        out[4] = (minimumFrameSize ushr 16).toByte()
        out[5] = (minimumFrameSize ushr 8).toByte()
        out[6] = minimumFrameSize.toByte()
        out[7] = (maximumFrameSize ushr 16).toByte()
        out[8] = (maximumFrameSize ushr 8).toByte()
        out[9] = maximumFrameSize.toByte()

        // Sample rate, channels, depth and the sample count share 64 bits and
        // do not fall on byte boundaries.
        var packed = 0L
        packed = packed or ((format.sampleRate.toLong() and 0xFFFFF) shl 44)
        packed = packed or (((channels - 1).toLong() and 0x7) shl 41)
        packed = packed or (((bitsPerSample - 1).toLong() and 0x1F) shl 36)
        packed = packed or (totalFrames and 0xF_FFFF_FFFFL)
        for (i in 0 until 8) {
            out[10 + i] = (packed ushr (8 * (7 - i))).toByte()
        }
        md5.copyInto(out, 18, 0, 16)
    }

    /** Turns the interleaved float block into per-channel integers. */
    private fun quantiseInto(samples: FloatArray, frames: Int) {
        val scale = (1L shl (bitsPerSample - 1)).toFloat()
        val step = 1f / scale
        val minimum = -(1L shl (bitsPerSample - 1)).toInt()
        val maximum = ((1L shl (bitsPerSample - 1)) - 1).toInt()
        for (channel in 0 until channels) {
            val out = blocks[channel]
            var index = channel
            for (frame in 0 until frames) {
                val value = samples[index]
                index += channels
                val rounded = if (value.isNaN()) {
                    0
                } else {
                    val noise = when (val selected = dither) {
                        Dither.None -> 0f
                        is Dither.Tpdf -> selected.nextSample() * step
                    }
                    val scaled = (value + noise) * scale
                    when {
                        scaled <= minimum.toFloat() -> minimum
                        scaled >= maximum.toFloat() -> maximum
                        else -> kotlin.math.round(scaled).toInt()
                    }
                }
                out[frame] = rounded
            }
        }
    }

    /** Feeds the block to the MD5 the stream information block carries. */
    private fun feedHash(frames: Int) {
        val bytesPerSample = (bitsPerSample + 7) / 8
        val scratch = ByteArray(frames * channels * bytesPerSample)
        var index = 0
        for (frame in 0 until frames) {
            for (channel in 0 until channels) {
                val value = blocks[channel][frame]
                for (byte in 0 until bytesPerSample) {
                    scratch[index++] = (value ushr (8 * byte)).toByte()
                }
            }
        }
        hash.update(scratch)
    }

    /** Encodes one frame and returns how many bytes it occupies. */
    private fun encodeFrame(frameNumber: Long, frames: Int): Int {
        frameWriter.reset()

        // Stereo decorrelation is decided by measuring all four arrangements.
        // The value written is the wire code, not a channel count: 0 to 7 mean
        // that many channels plus one stored independently, and 8, 9 and 10 are
        // the three ways a stereo pair can be stored as a difference.
        val channelCode: Int
        val plan: Array<SubframePlan>
        if (channels == 2) {
            for (i in 0 until frames) {
                val left = blocks[0][i]
                val right = blocks[1][i]
                side[i] = left - right
                mid[i] = (left + right) shr 1
            }
            val leftPlan = planFor(blocks[0], frames, bitsPerSample)
            val rightPlan = planFor(blocks[1], frames, bitsPerSample)
            val midPlan = planFor(mid, frames, bitsPerSample)
            val sidePlan = planFor(side, frames, bitsPerSample + 1)

            val independent = leftPlan.bits + rightPlan.bits
            val leftSide = leftPlan.bits + sidePlan.bits
            val rightSide = sidePlan.bits + rightPlan.bits
            val midSide = midPlan.bits + sidePlan.bits
            val best = minOf(independent, leftSide, rightSide, midSide)
            when (best) {
                independent -> {
                    channelCode = 1
                    plan = arrayOf(leftPlan, rightPlan)
                }
                leftSide -> {
                    channelCode = 8
                    plan = arrayOf(leftPlan, sidePlan)
                }
                rightSide -> {
                    channelCode = 9
                    plan = arrayOf(sidePlan, rightPlan)
                }
                else -> {
                    channelCode = 10
                    plan = arrayOf(midPlan, sidePlan)
                }
            }
        } else {
            channelCode = channels - 1
            plan = Array(channels) { planFor(blocks[it], frames, bitsPerSample) }
        }

        writeFrameHeader(frameNumber, frames, channelCode)
        val headerBytes = frameWriter.byteLength
        // The header checksum covers the header up to but not including itself.
        val crc8 = FlacCrc.crc8(frameWriter.rawBuffer(), 0, headerBytes)
        frameWriter.writeBits(crc8, 8)

        for (subframe in plan) {
            writeSubframe(subframe, frames)
        }
        frameWriter.alignToByte()

        val bodyBytes = frameWriter.byteLength
        val crc16 = FlacCrc.crc16(frameWriter.rawBuffer(), 0, bodyBytes)
        frameWriter.writeBits(crc16, 16)
        return frameWriter.byteLength
    }

    private fun writeFrameHeader(frameNumber: Long, frames: Int, channelCode: Int) {
        frameWriter.writeBits(0x3FFE, 14)
        frameWriter.writeBits(0, 1)
        // A fixed-size stream states a frame number, which is what this
        // encoder writes: every block is the same size except the last.
        frameWriter.writeBits(0, 1)

        val blockSizeCode: Int
        var explicitBlockSize = -1
        blockSizeCode = when (frames) {
            192 -> 1
            576 -> 2
            1152 -> 3
            2304 -> 4
            4608 -> 5
            256 -> 8
            512 -> 9
            1024 -> 10
            2048 -> 11
            4096 -> 12
            8192 -> 13
            16384 -> 14
            32768 -> 15
            else -> {
                explicitBlockSize = frames - 1
                if (frames <= 256) 6 else 7
            }
        }
        val sampleRateCode = when (format.sampleRate) {
            88_200 -> 1
            176_400 -> 2
            192_000 -> 3
            8_000 -> 4
            16_000 -> 5
            22_050 -> 6
            24_000 -> 7
            32_000 -> 8
            44_100 -> 9
            48_000 -> 10
            96_000 -> 11
            // STREAMINFO already states the exact rate with twenty bits.
            // Code zero inherits it and therefore also covers rates above the
            // sixteen-bit explicit-hertz representation.
            else -> 0
        }
        val bitDepthCode = when (bitsPerSample) {
            8 -> 1
            12 -> 2
            16 -> 4
            20 -> 5
            24 -> 6
            32 -> 7
            // A depth outside the coded list is stated in the stream
            // information block and read from there.
            else -> 0
        }

        frameWriter.writeBits(blockSizeCode, 4)
        frameWriter.writeBits(sampleRateCode, 4)
        frameWriter.writeBits(channelCode, 4)
        frameWriter.writeBits(bitDepthCode, 3)
        frameWriter.writeBits(0, 1)
        writeUtf8Number(frameNumber)
        if (explicitBlockSize >= 0) {
            if (blockSizeCode == 6) {
                frameWriter.writeBits(explicitBlockSize, 8)
            } else {
                frameWriter.writeBits(explicitBlockSize, 16)
            }
        }
    }

    /** Writes a number the way UTF-8 codes a character. */
    private fun writeUtf8Number(value: Long) {
        when {
            value < 0x80 -> frameWriter.writeBits(value.toInt(), 8)
            value < 0x800 -> {
                frameWriter.writeBits(0xC0 or ((value ushr 6).toInt() and 0x1F), 8)
                frameWriter.writeBits(0x80 or (value.toInt() and 0x3F), 8)
            }
            value < 0x10000 -> {
                frameWriter.writeBits(0xE0 or ((value ushr 12).toInt() and 0x0F), 8)
                frameWriter.writeBits(0x80 or ((value ushr 6).toInt() and 0x3F), 8)
                frameWriter.writeBits(0x80 or (value.toInt() and 0x3F), 8)
            }
            value < 0x200000 -> {
                frameWriter.writeBits(0xF0 or ((value ushr 18).toInt() and 0x07), 8)
                frameWriter.writeBits(0x80 or ((value ushr 12).toInt() and 0x3F), 8)
                frameWriter.writeBits(0x80 or ((value ushr 6).toInt() and 0x3F), 8)
                frameWriter.writeBits(0x80 or (value.toInt() and 0x3F), 8)
            }
            value < 0x4000000 -> {
                frameWriter.writeBits(0xF8 or ((value ushr 24).toInt() and 0x03), 8)
                frameWriter.writeBits(0x80 or ((value ushr 18).toInt() and 0x3F), 8)
                frameWriter.writeBits(0x80 or ((value ushr 12).toInt() and 0x3F), 8)
                frameWriter.writeBits(0x80 or ((value ushr 6).toInt() and 0x3F), 8)
                frameWriter.writeBits(0x80 or (value.toInt() and 0x3F), 8)
            }
            else -> {
                frameWriter.writeBits(0xFC or ((value ushr 30).toInt() and 0x01), 8)
                frameWriter.writeBits(0x80 or ((value ushr 24).toInt() and 0x3F), 8)
                frameWriter.writeBits(0x80 or ((value ushr 18).toInt() and 0x3F), 8)
                frameWriter.writeBits(0x80 or ((value ushr 12).toInt() and 0x3F), 8)
                frameWriter.writeBits(0x80 or ((value ushr 6).toInt() and 0x3F), 8)
                frameWriter.writeBits(0x80 or (value.toInt() and 0x3F), 8)
            }
        }
    }

    /** What to write for one channel of one block, and what it will cost. */
    private class SubframePlan(
        val samples: IntArray,
        val bitsPerSample: Int,
        /** 0 constant, 1 verbatim, 2 fixed, 3 linear. */
        val kind: Int,
        val order: Int,
        val residual: IntArray,
        val coefficients: IntArray,
        val shift: Int,
        val precision: Int,
        val partitionOrder: Int,
        val riceParameters: IntArray,
        val bits: Int,
    )

    /**
     * Chooses how to encode one channel of one block.
     *
     * Every candidate is measured in bits and the cheapest wins. That is more
     * work than guessing from the signal, and it is the only way to be sure:
     * a passage that looks smooth can still cost more under a high order once
     * the coefficients are counted.
     */
    private fun planFor(samples: IntArray, frames: Int, bits: Int): SubframePlan {
        // A block whose samples are all the same costs one sample to store.
        var constant = true
        for (i in 1 until frames) {
            if (samples[i] != samples[0]) {
                constant = false
                break
            }
        }
        if (constant) {
            return SubframePlan(
                samples, bits, kind = 0, order = 0, residual = IntArray(0),
                coefficients = IntArray(0), shift = 0, precision = 0,
                partitionOrder = 0, riceParameters = IntArray(0),
                bits = SUBFRAME_HEADER_BITS + bits,
            )
        }

        var best: SubframePlan? = null

        // The fixed predictors are cheap to try and often win on quiet or
        // simple material, where a linear predictor's coefficients would cost
        // more than they save.
        for (order in 0..4) {
            if (frames <= order) continue
            val residual = IntArray(frames)
            for (i in 0 until order) residual[i] = samples[i]
            for (i in order until frames) {
                residual[i] = samples[i] - fixedPrediction(samples, i, order)
            }
            val rice = planRice(residual, frames, order)
            val cost = SUBFRAME_HEADER_BITS + order * bits + rice.bits
            if (best == null || cost < best.bits) {
                best = SubframePlan(
                    samples, bits, kind = 2, order = order, residual = residual,
                    coefficients = IntArray(0), shift = 0, precision = 0,
                    partitionOrder = rice.partitionOrder, riceParameters = rice.parameters,
                    bits = cost,
                )
            }
        }

        if (maxLpcOrder > 0 && frames > maxLpcOrder * 2) {
            val autocorrelation = autocorrelate(samples, frames, maxLpcOrder)
            val lpc = levinsonDurbin(autocorrelation, maxLpcOrder)
            for (order in 1..maxLpcOrder) {
                val quantised = quantiseCoefficients(lpc[order - 1], order) ?: continue
                val residual = IntArray(frames)
                for (i in 0 until order) residual[i] = samples[i]
                var usable = true
                for (i in order until frames) {
                    var sum = 0L
                    for (j in 0 until order) {
                        sum += quantised.coefficients[j].toLong() * samples[i - 1 - j]
                    }
                    val predicted = (sum shr quantised.shift)
                    val difference = samples[i].toLong() - predicted
                    if (difference > Int.MAX_VALUE || difference < Int.MIN_VALUE) {
                        usable = false
                        break
                    }
                    residual[i] = difference.toInt()
                }
                if (!usable) continue
                val rice = planRice(residual, frames, order)
                val cost = SUBFRAME_HEADER_BITS + order * bits + 4 + 5 +
                    order * quantised.precision + rice.bits
                if (best == null || cost < best.bits) {
                    best = SubframePlan(
                        samples, bits, kind = 3, order = order, residual = residual,
                        coefficients = quantised.coefficients, shift = quantised.shift,
                        precision = quantised.precision,
                        partitionOrder = rice.partitionOrder, riceParameters = rice.parameters,
                        bits = cost,
                    )
                }
            }
        }

        // Storing every sample whole is the fallback, and for noise it wins.
        val verbatim = SUBFRAME_HEADER_BITS + frames * bits
        if (best == null || verbatim < best.bits) {
            return SubframePlan(
                samples, bits, kind = 1, order = 0, residual = IntArray(0),
                coefficients = IntArray(0), shift = 0, precision = 0,
                partitionOrder = 0, riceParameters = IntArray(0), bits = verbatim,
            )
        }
        return best
    }

    private fun fixedPrediction(samples: IntArray, at: Int, order: Int): Int = when (order) {
        0 -> 0
        1 -> samples[at - 1]
        2 -> 2 * samples[at - 1] - samples[at - 2]
        3 -> 3 * samples[at - 1] - 3 * samples[at - 2] + samples[at - 3]
        else -> 4 * samples[at - 1] - 6 * samples[at - 2] + 4 * samples[at - 3] - samples[at - 4]
    }

    private class RicePlan(val partitionOrder: Int, val parameters: IntArray, val bits: Int)

    /**
     * Chooses how to partition the residual and what parameter each partition
     * gets.
     *
     * More partitions mean each one can be tuned to its own stretch of audio,
     * and each one costs its own parameter. The best split is found by trying
     * every order up to the configured maximum.
     */
    private fun planRice(residual: IntArray, frames: Int, predictorOrder: Int): RicePlan {
        var bestOrder = 0
        var bestParameters = IntArray(1)
        var bestBits = Int.MAX_VALUE

        for (order in 0..maxPartitionOrder) {
            val partitions = 1 shl order
            if (frames % partitions != 0) continue
            val partitionSize = frames / partitions
            if (partitionSize <= predictorOrder) continue

            val parameters = IntArray(partitions)
            // Two bits for the method and four for the order.
            var bits = 2 + 4
            var position = predictorOrder
            for (partition in 0 until partitions) {
                val count = if (partition == 0) partitionSize - predictorOrder else partitionSize
                val chosen = bestParameterFor(residual, position, count)
                parameters[partition] = chosen.parameter
                bits += 4 + chosen.bits
                position += count
            }
            if (bits < bestBits) {
                bestBits = bits
                bestOrder = order
                bestParameters = parameters
            }
        }

        if (bestBits == Int.MAX_VALUE) {
            // Nothing divided evenly, which only happens for a very short
            // block. One partition always works.
            val chosen = bestParameterFor(residual, predictorOrder, frames - predictorOrder)
            return RicePlan(0, intArrayOf(chosen.parameter), 2 + 4 + 4 + chosen.bits)
        }
        return RicePlan(bestOrder, bestParameters, bestBits)
    }

    private class RiceChoice(val parameter: Int, val bits: Int)

    /**
     * Finds the Rice parameter that costs the fewest bits for one partition.
     *
     * The cost of a parameter is one unary bit per step of the quotient plus
     * the parameter's own width for every sample, so a parameter that is too
     * small explodes the quotients and one that is too large wastes the
     * remainder bits. Both sides are steep, so every candidate is measured.
     */
    private fun bestParameterFor(residual: IntArray, from: Int, count: Int): RiceChoice {
        if (count <= 0) return RiceChoice(0, 0)
        var sum = 0L
        for (i in from until from + count) {
            sum += zigzag(residual[i]).toLong()
        }
        // Start from the parameter the mean suggests and check its neighbours,
        // which is enough: the cost curve has one minimum.
        val mean = sum / count
        var start = 0
        while ((1L shl (start + 1)) <= mean && start < MAX_RICE_PARAMETER) start++

        var bestParameter = start
        var bestBits = Int.MAX_VALUE
        val low = if (start - 2 < 0) 0 else start - 2
        val high = if (start + 2 > MAX_RICE_PARAMETER) MAX_RICE_PARAMETER else start + 2
        for (parameter in low..high) {
            var bits = 0L
            for (i in from until from + count) {
                bits += (zigzag(residual[i]) ushr parameter) + 1 + parameter
            }
            if (bits < bestBits) {
                bestBits = if (bits > Int.MAX_VALUE) Int.MAX_VALUE else bits.toInt()
                bestParameter = parameter
            }
        }
        return RiceChoice(bestParameter, bestBits)
    }

    /** Folds a signed value so that small negatives stay small. */
    private fun zigzag(value: Int): Int =
        if (value < 0) (-value shl 1) - 1 else value shl 1

    private fun writeSubframe(plan: SubframePlan, frames: Int) {
        frameWriter.writeBits(0, 1)
        when (plan.kind) {
            0 -> {
                frameWriter.writeBits(0, 6)
                frameWriter.writeBits(0, 1)
                frameWriter.writeBits(plan.samples[0], plan.bitsPerSample)
            }
            1 -> {
                frameWriter.writeBits(1, 6)
                frameWriter.writeBits(0, 1)
                for (i in 0 until frames) {
                    frameWriter.writeBits(plan.samples[i], plan.bitsPerSample)
                }
            }
            2 -> {
                frameWriter.writeBits(8 or plan.order, 6)
                frameWriter.writeBits(0, 1)
                for (i in 0 until plan.order) {
                    frameWriter.writeBits(plan.samples[i], plan.bitsPerSample)
                }
                writeResidual(plan, frames)
            }
            else -> {
                frameWriter.writeBits(32 or (plan.order - 1), 6)
                frameWriter.writeBits(0, 1)
                for (i in 0 until plan.order) {
                    frameWriter.writeBits(plan.samples[i], plan.bitsPerSample)
                }
                frameWriter.writeBits(plan.precision - 1, 4)
                frameWriter.writeBits(plan.shift, 5)
                for (i in 0 until plan.order) {
                    frameWriter.writeBits(plan.coefficients[i], plan.precision)
                }
                writeResidual(plan, frames)
            }
        }
    }

    private fun writeResidual(plan: SubframePlan, frames: Int) {
        // Method zero is four-bit parameters, which is what this encoder
        // chooses from.
        frameWriter.writeBits(0, 2)
        frameWriter.writeBits(plan.partitionOrder, 4)
        val partitions = 1 shl plan.partitionOrder
        val partitionSize = frames shr plan.partitionOrder
        var position = plan.order
        for (partition in 0 until partitions) {
            val count = if (partition == 0) partitionSize - plan.order else partitionSize
            val parameter = plan.riceParameters[partition]
            frameWriter.writeBits(parameter, 4)
            for (i in 0 until count) {
                val folded = zigzag(plan.residual[position++])
                frameWriter.writeUnary(folded ushr parameter)
                if (parameter > 0) {
                    frameWriter.writeBits(folded and ((1 shl parameter) - 1), parameter)
                }
            }
        }
    }

    /**
     * The autocorrelation of a block, windowed first.
     *
     * A window is needed because the block is a slice of a longer signal, and
     * treating its ends as though the signal stopped there biases the
     * correlation. This uses the Welch window, which is cheap and close enough
     * to the usual Tukey for the orders this encoder tries.
     */
    private fun autocorrelate(samples: IntArray, frames: Int, order: Int): DoubleArray {
        val windowed = DoubleArray(frames)
        val half = (frames - 1) / 2.0
        for (i in 0 until frames) {
            val t = (i - half) / half
            windowed[i] = samples[i] * (1.0 - t * t)
        }
        val result = DoubleArray(order + 1)
        for (lag in 0..order) {
            var sum = 0.0
            for (i in lag until frames) {
                sum += windowed[i] * windowed[i - lag]
            }
            result[lag] = sum
        }
        return result
    }

    /**
     * Solves for the predictor coefficients of every order up to [order].
     *
     * This is the Levinson-Durbin recursion, which gets all the orders for
     * little more than the cost of the highest one. That matters because the
     * encoder wants to compare them.
     */
    private fun levinsonDurbin(autocorrelation: DoubleArray, order: Int): Array<DoubleArray> {
        val results = Array(order) { DoubleArray(it + 1) }
        var error = autocorrelation[0]
        if (error <= 0.0) return results

        val current = DoubleArray(order)
        val previous = DoubleArray(order)

        for (i in 0 until order) {
            // The reflection coefficient is how much of the new lag the
            // existing predictor has not already explained.
            var accumulator = autocorrelation[i + 1]
            for (j in 0 until i) {
                accumulator -= previous[j] * autocorrelation[i - j]
            }
            if (error == 0.0) break
            val reflection = accumulator / error

            current[i] = reflection
            for (j in 0 until i) {
                current[j] = previous[j] - reflection * previous[i - 1 - j]
            }
            for (j in 0..i) {
                results[i][j] = current[j]
                previous[j] = current[j]
            }

            error *= 1.0 - reflection * reflection
            if (error <= 0.0) break
        }
        return results
    }

    private class QuantisedCoefficients(
        val coefficients: IntArray,
        val shift: Int,
        val precision: Int,
    )

    /**
     * Turns the real coefficients into integers the format can store.
     *
     * The precision and shift are chosen so that the largest coefficient just
     * fits, which keeps as much of the predictor's accuracy as the bits allow.
     * Returns null when the coefficients cannot be represented, in which case
     * the caller falls back to another order.
     */
    private fun quantiseCoefficients(
        coefficients: DoubleArray,
        order: Int,
    ): QuantisedCoefficients? {
        // One bit of the stored width is the sign, so the magnitude has this
        // many.
        val magnitudeBits = COEFFICIENT_PRECISION - 1
        var largest = 0.0
        for (i in 0 until order) {
            val magnitude = abs(coefficients[i])
            if (magnitude > largest) largest = magnitude
        }
        if (largest <= 0.0 || largest.isNaN() || largest.isInfinite()) return null

        // Split the largest coefficient into a fraction and an exponent, then
        // shift by as much as the remaining bits allow. That keeps as much of
        // the predictor's accuracy as the stored width can hold.
        var exponent = 0
        var fraction = largest
        while (fraction >= 1.0) {
            fraction /= 2.0
            exponent++
        }
        while (fraction < 0.5) {
            fraction *= 2.0
            exponent--
        }
        var shift = magnitudeBits - (exponent - 1) - 1
        if (shift < 0) return null
        if (shift > MAX_COEFFICIENT_SHIFT) shift = MAX_COEFFICIENT_SHIFT

        val maximum = (1 shl magnitudeBits) - 1
        val minimum = -(1 shl magnitudeBits)
        val scale = (1 shl shift).toDouble()
        val out = IntArray(order)
        // The rounding error is carried into the next coefficient, so the
        // predictor as a whole stays closer to the one that was solved for.
        var error = 0.0
        for (i in 0 until order) {
            error += coefficients[i] * scale
            var value = kotlin.math.round(error).toInt()
            if (value > maximum) value = maximum
            if (value < minimum) value = minimum
            error -= value
            out[i] = value
        }
        return QuantisedCoefficients(out, shift, COEFFICIENT_PRECISION)
    }

    private companion object {
        val MAGIC = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())

        /** Bits every subframe header occupies: a pad bit, a type, a flag. */
        const val SUBFRAME_HEADER_BITS = 8

        const val MAX_LPC_ORDER = 32
        const val MAX_RICE_PARAMETER = 14
        const val COEFFICIENT_PRECISION = 15
        const val MAX_COEFFICIENT_SHIFT = 15
        const val PADDING_BYTES = 4096
        const val MAX_TOTAL_SAMPLES = 0xF_FFFF_FFFFL
    }
}

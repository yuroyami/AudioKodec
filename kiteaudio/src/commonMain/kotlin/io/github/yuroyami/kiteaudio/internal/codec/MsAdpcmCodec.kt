package io.github.yuroyami.kiteaudio.internal.codec

import io.github.yuroyami.kiteaudio.CorruptStreamException
import kotlin.math.abs

/**
 * Microsoft ADPCM, the other four-bit coding WAV carries.
 *
 * Where IMA predicts from the previous sample alone, this one predicts from
 * the two previous samples with a pair of coefficients chosen per block. The
 * file may carry its own coefficient sets in the format header; the seven
 * standard ones are used when it does not.
 *
 * Block layout, per channel first and then interleaved nibbles: one byte of
 * coefficient set index per channel, then a 16-bit delta per channel, then the
 * second sample and the first sample per channel. The first two frames of the
 * block therefore come out of the header, and the nibbles that follow are
 * high nibble first.
 */
internal class MsAdpcmCodec(
    override val channels: Int,
    override val blockBytes: Int,
    /** Coefficient pairs, scaled by 256. */
    private val coefficients: Array<IntArray> = STANDARD_COEFFICIENTS,
) : SampleCodec, SampleEncoder {

    init {
        val header = 7 * channels
        if (blockBytes <= header) {
            throw CorruptStreamException(
                "a Microsoft ADPCM block of $blockBytes bytes cannot hold the " +
                    "$header-byte header for $channels channels",
            )
        }
    }

    override val framesPerBlock: Int = 2 + (blockBytes - 7 * channels) * 2 / channels

    override val storedBitDepth: Int? = null

    override fun decode(
        src: ByteArray,
        srcOffset: Int,
        blocks: Int,
        out: FloatArray,
        outOffset: Int,
    ): Int {
        var output = outOffset
        for (block in 0 until blocks) {
            decodeOneBlock(src, srcOffset + block * blockBytes, out, output)
            output += framesPerBlock * channels
        }
        return blocks * framesPerBlock
    }

    override fun decodeInt(
        src: ByteArray,
        srcOffset: Int,
        blocks: Int,
        out: IntArray,
        outOffset: Int,
    ): Int {
        throw UnsupportedOperationException("ADPCM is lossy and has no exact integer form")
    }

    private fun decodeOneBlock(src: ByteArray, at: Int, out: FloatArray, outOffset: Int) {
        var read = at
        val coefficient1 = IntArray(channels)
        val coefficient2 = IntArray(channels)
        val delta = IntArray(channels)
        val sample1 = IntArray(channels)
        val sample2 = IntArray(channels)

        for (c in 0 until channels) {
            val index = src[read++].toInt() and 0xFF
            if (index >= coefficients.size) {
                throw CorruptStreamException(
                    "Microsoft ADPCM coefficient set $index is beyond the " +
                        "${coefficients.size} the file declares",
                    (read - 1).toLong(),
                )
            }
            coefficient1[c] = coefficients[index][0]
            coefficient2[c] = coefficients[index][1]
        }
        for (c in 0 until channels) {
            delta[c] = readInt16Le(src, read)
            read += 2
        }
        for (c in 0 until channels) {
            sample1[c] = readInt16Le(src, read)
            read += 2
        }
        for (c in 0 until channels) {
            sample2[c] = readInt16Le(src, read)
            read += 2
        }

        // The header's two samples are the first two frames, oldest first.
        var write = outOffset
        for (c in 0 until channels) out[write++] = sample2[c] * (1f / 32_768f)
        for (c in 0 until channels) out[write++] = sample1[c] * (1f / 32_768f)

        val remaining = framesPerBlock - 2
        for (frame in 0 until remaining) {
            for (c in 0 until channels) {
                val nibbleIndex = frame * channels + c
                val byte = src[read + (nibbleIndex shr 1)].toInt() and 0xFF
                val nibble = if (nibbleIndex and 1 == 0) byte shr 4 else byte and 0x0F
                val signed = if (nibble >= 8) nibble - 16 else nibble

                var predictor = predict(
                    sample1[c],
                    sample2[c],
                    coefficient1[c],
                    coefficient2[c],
                )
                predictor += signed * delta[c]
                if (predictor > 32_767) predictor = 32_767
                if (predictor < -32_768) predictor = -32_768

                sample2[c] = sample1[c]
                sample1[c] = predictor
                var nextDelta = (ADAPTATION[nibble] * delta[c]) shr 8
                if (nextDelta < 16) nextDelta = 16
                delta[c] = nextDelta

                out[write++] = predictor * (1f / 32_768f)
            }
        }
    }

    override fun encode(
        src: FloatArray,
        srcOffset: Int,
        frames: Int,
        out: ByteArray,
        outOffset: Int,
    ): Int {
        require(frames in 2..framesPerBlock) {
            "a block holds 2..$framesPerBlock frames, not $frames"
        }
        val pcm = IntArray(framesPerBlock * channels)
        for (i in 0 until frames * channels) {
            pcm[i] = clampToInt16(src[srcOffset + i])
        }

        // Coefficient set 0 is a plain two-tap predictor with a zero second
        // tap, which is the safe choice for an encoder that does not search:
        // it never amplifies a bad prediction, and every decoder has it.
        val set = 0
        val coefficient1 = coefficients[set][0]
        val coefficient2 = coefficients[set][1]

        val delta = IntArray(channels)
        val sample1 = IntArray(channels)
        val sample2 = IntArray(channels)
        for (c in 0 until channels) {
            sample2[c] = pcm[c]
            sample1[c] = pcm[channels + c]
            delta[c] = startingDelta(pcm, c)
        }

        var write = outOffset
        for (c in 0 until channels) out[write++] = set.toByte()
        for (c in 0 until channels) {
            writeInt16Le(out, write, delta[c])
            write += 2
        }
        for (c in 0 until channels) {
            writeInt16Le(out, write, sample1[c])
            write += 2
        }
        for (c in 0 until channels) {
            writeInt16Le(out, write, sample2[c])
            write += 2
        }

        val remaining = framesPerBlock - 2
        var pending = 0
        var pendingHigh = true
        for (frame in 0 until remaining) {
            for (c in 0 until channels) {
                val target = pcm[(frame + 2) * channels + c]
                var predictor = predict(sample1[c], sample2[c], coefficient1, coefficient2)
                val error = target - predictor
                var nibble = roundedDivide(error, delta[c])
                if (nibble > 7) nibble = 7
                if (nibble < -8) nibble = -8

                predictor += nibble * delta[c]
                if (predictor > 32_767) predictor = 32_767
                if (predictor < -32_768) predictor = -32_768
                sample2[c] = sample1[c]
                sample1[c] = predictor

                val code = nibble and 0x0F
                var nextDelta = (ADAPTATION[code] * delta[c]) shr 8
                if (nextDelta < 16) nextDelta = 16
                delta[c] = nextDelta

                if (pendingHigh) {
                    pending = code shl 4
                    pendingHigh = false
                } else {
                    out[write++] = (pending or code).toByte()
                    pendingHigh = true
                }
            }
        }
        if (!pendingHigh) out[write++] = pending.toByte()
        return blockBytes
    }

    /**
     * Picks a starting delta from the first difference in the block.
     *
     * The delta adapts quickly, so the only thing this has to avoid is
     * starting so small that the first few samples cannot be reached.
     */
    private fun startingDelta(pcm: IntArray, channel: Int): Int {
        if (pcm.size <= 2 * channels + channel) return 16
        val difference = abs(pcm[2 * channels + channel] - pcm[channels + channel])
        val delta = difference / 4
        return if (delta < 16) 16 else if (delta > 16_384) 16_384 else delta
    }

    internal companion object {

        /**
         * The seven coefficient pairs every decoder knows, scaled by 256.
         *
         * A file may declare its own set in the format header, in which case
         * those are used instead. In practice almost every file uses these.
         */
        val STANDARD_COEFFICIENTS: Array<IntArray> = arrayOf(
            intArrayOf(256, 0),
            intArrayOf(512, -256),
            intArrayOf(0, 0),
            intArrayOf(192, 64),
            intArrayOf(240, 0),
            intArrayOf(460, -208),
            intArrayOf(392, -232),
        )

        /** Delta adaptation, indexed by nibble, scaled by 256. */
        val ADAPTATION: IntArray = intArrayOf(
            230, 230, 230, 230, 307, 409, 512, 614,
            768, 614, 512, 409, 307, 230, 230, 230,
        )
    }
}

private fun readInt16Le(src: ByteArray, at: Int): Int {
    val low = src[at].toInt() and 0xFF
    val high = src[at + 1].toInt() and 0xFF
    return (low or (high shl 8)).toShort().toInt()
}

private fun writeInt16Le(out: ByteArray, at: Int, value: Int) {
    out[at] = value.toByte()
    out[at + 1] = (value shr 8).toByte()
}

private fun roundedDivide(numerator: Int, denominator: Int): Int {
    if (denominator == 0) return 0
    return if (numerator >= 0) {
        (numerator + denominator / 2) / denominator
    } else {
        -((-numerator + denominator / 2) / denominator)
    }
}

private fun predict(sample1: Int, sample2: Int, coefficient1: Int, coefficient2: Int): Int {
    val value =
        (sample1.toLong() * coefficient1 + sample2.toLong() * coefficient2) shr 8
    return value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}

private fun clampToInt16(value: Float): Int {
    if (value.isNaN()) return 0
    val scaled = value * 32_768f
    return when {
        scaled <= -32_768f -> -32_768
        scaled >= 32_767f -> 32_767
        else -> kotlin.math.round(scaled).toInt()
    }
}

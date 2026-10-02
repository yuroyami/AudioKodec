package io.github.yuroyami.audiokodecs.internal.codec

import io.github.yuroyami.audiokodecs.CorruptStreamException

/**
 * The step and index tables shared by every IMA and DVI ADPCM variant.
 *
 * The values come from the IMA ADPCM specification, and every implementation
 * uses the same two tables. What differs between variants is only how the
 * nibbles are packed into blocks.
 */
internal object ImaAdpcmTables {

    val STEP: IntArray = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
    )

    val INDEX_ADJUST: IntArray = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8,
    )

    /**
     * Applies one nibble to a predictor and a step index.
     *
     * [state] holds the predictor in entry 0 and the step index in entry 1,
     * and both are updated. Returning the sample as well saves the caller a
     * second read.
     */
    fun step(nibble: Int, state: IntArray): Int {
        val stepSize = STEP[state[1]]
        var difference = stepSize shr 3
        if (nibble and 1 != 0) difference += stepSize shr 2
        if (nibble and 2 != 0) difference += stepSize shr 1
        if (nibble and 4 != 0) difference += stepSize
        if (nibble and 8 != 0) difference = -difference
        var predictor = state[0] + difference
        if (predictor > 32_767) predictor = 32_767
        if (predictor < -32_768) predictor = -32_768
        var index = state[1] + INDEX_ADJUST[nibble]
        if (index < 0) index = 0
        if (index > 88) index = 88
        state[0] = predictor
        state[1] = index
        return predictor
    }

    /** Chooses the nibble that best encodes [sample], and updates [state]. */
    fun quantise(sample: Int, state: IntArray): Int {
        val stepSize = STEP[state[1]]
        var difference = sample - state[0]
        var nibble = 0
        if (difference < 0) {
            nibble = 8
            difference = -difference
        }
        var threshold = stepSize
        if (difference >= threshold) {
            nibble = nibble or 4
            difference -= threshold
        }
        threshold = threshold shr 1
        if (difference >= threshold) {
            nibble = nibble or 2
            difference -= threshold
        }
        threshold = threshold shr 1
        if (difference >= threshold) {
            nibble = nibble or 1
        }
        step(nibble, state)
        return nibble
    }
}

/**
 * IMA ADPCM in the block layout WAV uses, which is also what AVI and several
 * other Microsoft formats carry.
 *
 * Each block starts with four bytes per channel: the initial predictor as a
 * signed 16-bit value, the step index, and one unused byte. The nibbles that
 * follow are grouped in runs of four bytes per channel, low nibble first
 * inside each byte.
 *
 * A block restarts the predictor, so blocks are independent and seeking to a
 * block boundary needs no earlier data.
 */
internal class ImaAdpcmWavCodec(
    override val channels: Int,
    override val blockBytes: Int,
) : SampleCodec, SampleEncoder {

    init {
        val header = 4 * channels
        if (blockBytes <= header) {
            throw CorruptStreamException(
                "an IMA ADPCM block of $blockBytes bytes cannot hold the " +
                    "$header-byte header for $channels channels",
            )
        }
        if ((blockBytes - header) % (4 * channels) != 0) {
            throw CorruptStreamException(
                "an IMA ADPCM block of $blockBytes bytes is not a whole number of " +
                    "4-byte groups for $channels channels",
            )
        }
    }

    override val framesPerBlock: Int = 1 + (blockBytes - 4 * channels) * 2 / channels

    override val storedBitDepth: Int? = null

    /** Groups of eight samples per channel after the header. */
    private val groups: Int = (blockBytes - 4 * channels) / (4 * channels)

    override fun decode(
        src: ByteArray,
        srcOffset: Int,
        blocks: Int,
        out: FloatArray,
        outOffset: Int,
    ): Int {
        val scratch = IntArray(framesPerBlock * channels)
        var output = outOffset
        for (block in 0 until blocks) {
            decodeOneBlock(src, srcOffset + block * blockBytes, scratch)
            for (i in scratch.indices) {
                out[output++] = scratch[i] * (1f / 32_768f)
            }
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

    private fun decodeOneBlock(src: ByteArray, at: Int, out: IntArray) {
        var read = at
        // The header carries the first sample of every channel, and that
        // sample is frame 0 of the block rather than something to skip.
        val states = Array(channels) { IntArray(2) }
        for (c in 0 until channels) {
            val low = src[read].toInt() and 0xFF
            val high = src[read + 1].toInt() and 0xFF
            val predictor = (low or (high shl 8)).toShort().toInt()
            val index = src[read + 2].toInt() and 0xFF
            if (index > 88) {
                throw CorruptStreamException(
                    "IMA ADPCM step index $index is above 88", read.toLong(),
                )
            }
            states[c][0] = predictor
            states[c][1] = index
            out[c] = predictor
            read += 4
        }
        var frame = 1
        for (group in 0 until groups) {
            for (c in 0 until channels) {
                val state = states[c]
                var target = (frame * channels) + c
                for (byteIndex in 0 until 4) {
                    val byte = src[read + byteIndex].toInt() and 0xFF
                    out[target] = ImaAdpcmTables.step(byte and 0x0F, state)
                    target += channels
                    out[target] = ImaAdpcmTables.step(byte shr 4, state)
                    target += channels
                }
                read += 4
            }
            frame += 8
        }
    }

    override fun encode(
        src: FloatArray,
        srcOffset: Int,
        frames: Int,
        out: ByteArray,
        outOffset: Int,
    ): Int {
        require(frames in 1..framesPerBlock) {
            "a block holds 1..$framesPerBlock frames, not $frames"
        }
        // Short blocks are padded with silence so every block stays the size
        // the header promises. A decoder cannot tell how many frames were
        // real, so the container records the true frame count instead.
        val pcm = IntArray(framesPerBlock * channels)
        for (i in 0 until frames * channels) {
            pcm[i] = clampToInt16(src[srcOffset + i])
        }
        var write = outOffset
        val states = Array(channels) { IntArray(2) }
        for (c in 0 until channels) {
            val first = pcm[c]
            states[c][0] = first
            states[c][1] = bestStartIndex(pcm, c)
            out[write] = first.toByte()
            out[write + 1] = (first shr 8).toByte()
            out[write + 2] = states[c][1].toByte()
            out[write + 3] = 0
            write += 4
        }
        var frame = 1
        for (group in 0 until groups) {
            for (c in 0 until channels) {
                val state = states[c]
                var source = (frame * channels) + c
                for (byteIndex in 0 until 4) {
                    val low = ImaAdpcmTables.quantise(pcm[source], state)
                    source += channels
                    val high = ImaAdpcmTables.quantise(pcm[source], state)
                    source += channels
                    out[write + byteIndex] = ((high shl 4) or low).toByte()
                }
                write += 4
            }
            frame += 8
        }
        return blockBytes
    }

    /**
     * Picks a starting step index from the size of the first difference.
     *
     * Starting at zero makes the first few samples of every block ramp up
     * slowly, which is audible as a click at block boundaries. Choosing the
     * smallest step that can reach the first difference removes it.
     */
    private fun bestStartIndex(pcm: IntArray, channel: Int): Int {
        if (pcm.size <= channels + channel) return 0
        val difference = kotlin.math.abs(pcm[channels + channel] - pcm[channel])
        for (i in ImaAdpcmTables.STEP.indices) {
            if (ImaAdpcmTables.STEP[i] * 2 >= difference) return i
        }
        return 88
    }
}

/**
 * IMA ADPCM in the QuickTime layout, which AIFF-C calls `ima4`.
 *
 * A packet is 34 bytes and holds 64 samples for one channel. The two-byte
 * preamble is big-endian: the top nine bits are the initial predictor, and the
 * bottom seven are the step index. Packets for the channels alternate.
 */
internal class ImaAdpcmQuickTimeCodec(
    override val channels: Int,
) : SampleCodec {

    override val blockBytes: Int = PACKET_BYTES * channels

    override val framesPerBlock: Int = SAMPLES_PER_PACKET

    override val storedBitDepth: Int? = null

    override fun decode(
        src: ByteArray,
        srcOffset: Int,
        blocks: Int,
        out: FloatArray,
        outOffset: Int,
    ): Int {
        val state = IntArray(2)
        for (block in 0 until blocks) {
            var read = srcOffset + block * blockBytes
            val blockStart = outOffset + block * framesPerBlock * channels
            for (c in 0 until channels) {
                val preamble = ((src[read].toInt() and 0xFF) shl 8) or (src[read + 1].toInt() and 0xFF)
                // The predictor is the top nine bits, sign-extended, with its
                // low seven bits zero.
                state[0] = (preamble and 0xFF80.toInt()).toShort().toInt()
                state[1] = preamble and 0x7F
                if (state[1] > 88) {
                    throw CorruptStreamException(
                        "IMA ADPCM step index ${state[1]} is above 88", read.toLong(),
                    )
                }
                read += 2
                var target = blockStart + c
                for (byteIndex in 0 until 32) {
                    val byte = src[read + byteIndex].toInt() and 0xFF
                    out[target] = ImaAdpcmTables.step(byte and 0x0F, state) * (1f / 32_768f)
                    target += channels
                    out[target] = ImaAdpcmTables.step(byte shr 4, state) * (1f / 32_768f)
                    target += channels
                }
                read += 32
            }
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

    private companion object {
        const val PACKET_BYTES = 34
        const val SAMPLES_PER_PACKET = 64
    }
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

package io.github.yuroyami.kiteaudio.internal.codec

/**
 * ITU-T G.711 mu-law and A-law: one byte per sample, logarithmic.
 *
 * Both are companding laws from telephony. A byte holds a sign, a segment and
 * a mantissa, which together cover a much wider range than eight linear bits
 * would. Decoding is a table lookup, so this class builds the 256-entry table
 * once and then reads it.
 *
 * Written from the coding laws in ITU-T Recommendation G.711.
 */
internal class G711Codec(
    override val channels: Int,
    /** True for A-law, false for mu-law. */
    val aLaw: Boolean,
) : SampleCodec, SampleEncoder {

    override val blockBytes: Int = channels

    override val framesPerBlock: Int = 1

    // The expansion happens to produce 16-bit numbers, but they are not an
    // exact stored PCM form: 256 companded codes cannot preserve 65536 input
    // values. Lossy codecs therefore do not expose IntPcmAccess.
    override val storedBitDepth: Int? = null

    private val table: IntArray = if (aLaw) A_LAW_TABLE else MU_LAW_TABLE

    override fun decode(
        src: ByteArray,
        srcOffset: Int,
        blocks: Int,
        out: FloatArray,
        outOffset: Int,
    ): Int {
        val samples = blocks * channels
        var input = srcOffset
        var output = outOffset
        repeat(samples) {
            out[output++] = table[src[input++].toInt() and 0xFF] * (1f / 32_768f)
        }
        return blocks
    }

    override fun decodeInt(
        src: ByteArray,
        srcOffset: Int,
        blocks: Int,
        out: IntArray,
        outOffset: Int,
    ): Int {
        val samples = blocks * channels
        var input = srcOffset
        var output = outOffset
        repeat(samples) {
            out[output++] = table[src[input++].toInt() and 0xFF]
        }
        return blocks
    }

    override fun encode(
        src: FloatArray,
        srcOffset: Int,
        frames: Int,
        out: ByteArray,
        outOffset: Int,
    ): Int {
        val samples = frames * channels
        var input = srcOffset
        var output = outOffset
        repeat(samples) {
            val v = src[input++]
            val linear = when {
                v.isNaN() -> 0
                v <= -1f -> -32_768
                v >= 1f -> 32_767
                else -> (v * 32_768f).toInt().coerceIn(-32_768, 32_767)
            }
            out[output++] = (if (aLaw) encodeALaw(linear) else encodeMuLaw(linear)).toByte()
        }
        return samples
    }

    internal companion object {

        /** Segment ends for mu-law encoding, in the 14-bit domain. */
        private val MU_SEGMENT_ENDS = intArrayOf(
            0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF,
        )

        /** Segment ends for A-law encoding, in the 13-bit domain. */
        private val A_SEGMENT_ENDS = intArrayOf(
            0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF,
        )

        private const val MU_BIAS = 0x84
        private const val MU_CLIP = 8159

        val MU_LAW_TABLE: IntArray = IntArray(256) { decodeMuLaw(it) }

        val A_LAW_TABLE: IntArray = IntArray(256) { decodeALaw(it) }

        /** Expands one mu-law byte to a signed 16-bit sample. */
        fun decodeMuLaw(code: Int): Int {
            val inverted = code.inv() and 0xFF
            val mantissa = inverted and 0x0F
            val segment = (inverted and 0x70) shr 4
            var value = ((mantissa shl 3) + MU_BIAS) shl segment
            value -= MU_BIAS
            return if (inverted and 0x80 != 0) -value else value
        }

        /** Expands one A-law byte to a signed 16-bit sample. */
        fun decodeALaw(code: Int): Int {
            val toggled = code xor 0x55
            val mantissa = toggled and 0x0F
            val segment = (toggled and 0x70) shr 4
            var value = mantissa shl 4
            value += when (segment) {
                0 -> 8
                1 -> 0x108
                else -> 0x108
            }
            if (segment > 1) value = value shl (segment - 1)
            return if (toggled and 0x80 != 0) value else -value
        }

        /** Compresses a signed 16-bit sample to one mu-law byte. */
        fun encodeMuLaw(sample: Int): Int {
            // mu-law works in a 14-bit domain.
            var value = sample shr 2
            val mask: Int
            if (value < 0) {
                value = -value
                mask = 0x7F
            } else {
                mask = 0xFF
            }
            if (value > MU_CLIP) value = MU_CLIP
            value += MU_BIAS shr 2
            val segment = segmentOf(value, MU_SEGMENT_ENDS)
            if (segment >= 8) return 0x7F xor mask
            val code = (segment shl 4) or ((value shr (segment + 1)) and 0x0F)
            return code xor mask
        }

        /** Compresses a signed 16-bit sample to one A-law byte. */
        fun encodeALaw(sample: Int): Int {
            // A-law works in a 13-bit domain.
            var value = sample shr 3
            val mask: Int
            if (value >= 0) {
                mask = 0xD5
            } else {
                mask = 0x55
                value = -value - 1
            }
            val segment = segmentOf(value, A_SEGMENT_ENDS)
            if (segment >= 8) return 0x7F xor mask
            var code = segment shl 4
            code = code or if (segment < 2) {
                (value shr 1) and 0x0F
            } else {
                (value shr segment) and 0x0F
            }
            return code xor mask
        }

        private fun segmentOf(value: Int, ends: IntArray): Int {
            for (i in ends.indices) {
                if (value <= ends[i]) return i
            }
            return ends.size
        }
    }
}

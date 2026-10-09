package io.github.yuroyami.audiokodec.internal.mpeg

import kotlin.math.pow

/**
 * The tables Layers I and II decode with.
 *
 * Layer II's bit allocation is the awkward part. There is not one table but
 * five, and which applies depends on the version, the sample rate and the
 * bitrate per channel together, because the standard gives each table a list of
 * the conditions it covers rather than a rule. Picking the wrong one does not
 * fail, it reads the right number of bits from the wrong places and produces
 * noise.
 *
 * Everything here was compared across the published standards and several
 * independent renderings; `reference/REFERENCES.md` records which, including
 * the one widely copied implementation that has a wrong entry in the low rate
 * table.
 */
internal object Layer12Tables {

    /**
     * The scalefactors, `2 * 2^(-i/3)`.
     *
     * The field is six bits but the standard defines only 63 of the 64 values,
     * so the last is not a scalefactor at all and only a corrupt stream reaches
     * it. It is filled with zero here, which silences the subband rather than
     * reading past the end of the table.
     */
    val SCALEFACTORS: DoubleArray = DoubleArray(64) { i ->
        if (i < 63) 2.0 * 2.0.pow(-i / 3.0) else 0.0
    }

    /** True when this many levels are packed three samples to a codeword. */
    fun isGrouped(levels: Int): Boolean = levels == 3 || levels == 5 || levels == 9

    /**
     * Bits one codeword occupies.
     *
     * The three smallest classes would waste most of a bit each on their own,
     * so the format packs three samples into one codeword for them: three
     * levels in five bits rather than six, five in seven rather than nine, nine
     * in ten rather than twelve.
     */
    fun codeBits(levels: Int): Int = when (levels) {
        3 -> 5
        5 -> 7
        9 -> 10
        else -> widthOf(levels)
    }

    /** Bits a single sample of this class occupies when it stands alone. */
    fun widthOf(levels: Int): Int {
        var bits = 0
        var value = levels
        while (value > 0) {
            value = value shr 1
            bits++
        }
        return bits
    }

    /**
     * Turns a code into a sample.
     *
     * The standard writes this as a bit inversion, a two's complement read and
     * then a multiply and an add from a table of seventeen pairs. All of that
     * comes to the same thing as spreading the codes evenly over the range,
     * which is what this is, and which cannot be transcribed wrongly.
     */
    fun dequantise(code: Int, levels: Int): Double =
        (2.0 * code - (levels - 1)) / levels

    /**
     * How many subbands a joint stereo frame codes separately.
     *
     * Above this the two channels share one set of samples and differ only in
     * their scalefactors, which is enough to keep a stereo image at bitrates
     * where two full channels would not fit.
     */
    fun boundOf(header: MpegFrameHeader, sblimit: Int): Int {
        if (header.channelMode != MpegFrameHeader.MODE_JOINT) return sblimit
        // The standard does not say what to do when the mode extension asks for
        // more subbands than the table has. Reading past the table is the one
        // answer that is certainly wrong.
        return (4 * (header.modeExtension + 1)).coerceAtMost(sblimit)
    }

    /** Bits per sample for a Layer I allocation code, or 0 for an empty band. */
    fun layer1Width(code: Int): Int = when (code) {
        0 -> 0
        // Fifteen is not an allocation, and a stream that uses it is corrupt.
        15 -> -1
        else -> code + 1
    }

    /** Levels a Layer I allocation code stands for. */
    fun layer1Levels(code: Int): Int = (1 shl (code + 1)) - 1

    /**
     * Which allocation table a Layer II frame uses.
     *
     * MPEG-2 has only one and uses it for everything. MPEG-1 has four, chosen
     * by the bitrate each channel gets and by the sample rate, and the two low
     * rate tables differ only in how many subbands they reach: the wider one
     * exists because eight subbands at 32 kHz would throw away everything above
     * four kilohertz.
     */
    fun tableFor(header: MpegFrameHeader): Allocation {
        if (header.version != 1) return LSF
        // MPEG-1 free format uses Table 3-B.2a. Its measured byte rate
        // must not select one of the tables for a declared bitrate index.
        if (header.isFreeFormat) return TABLE_A

        val perChannel = if (header.channelMode == MpegFrameHeader.MODE_SINGLE) {
            header.bitrateKbps
        } else {
            header.bitrateKbps / 2
        }
        return when {
            // Anything below the lowest rate the tables name is out of spec.
            // Files like it exist, and the low rate table is the one that keeps
            // them in step rather than desynchronising the whole frame.
            perChannel < 56 -> if (header.sampleRate == 32_000) TABLE_D else TABLE_C
            perChannel >= 96 && header.sampleRate != 48_000 -> TABLE_B
            else -> TABLE_A
        }
    }

    /**
     * One bit allocation table.
     *
     * [nbal] is how many bits a subband's allocation field takes, and [levels]
     * maps what that field says to how many quantisation levels the subband
     * uses. An entry of zero means the subband carries nothing at all.
     */
    class Allocation(val sblimit: Int, val nbal: IntArray, val levels: Array<IntArray>)

    private fun build(sblimit: Int, rows: List<Triple<IntRange, Int, IntArray>>): Allocation {
        val nbal = IntArray(32)
        val levels = Array(32) { IntArray(0) }
        for ((range, bits, values) in rows) {
            require(values.size == 1 shl bits) {
                "a $bits bit field names ${values.size} allocations, not ${1 shl bits}"
            }
            for (sb in range) {
                nbal[sb] = bits
                levels[sb] = values
            }
        }
        return Allocation(sblimit, nbal, levels)
    }

    // The 16-bit escapes in the middle of these rows are the standard's own:
    // subbands 3 to 10 of the first two tables jump from 8191 straight to
    // 65535, and the narrow rows jump from 31 and from 5. They look like
    // transcription errors and are not.

    private val WIDE_LOW = intArrayOf(
        0, 3, 7, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383, 32767, 65535,
    )
    private val WIDE_MID = intArrayOf(
        0, 3, 5, 7, 9, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 65535,
    )
    private val NARROW = intArrayOf(0, 3, 5, 7, 9, 15, 31, 65535)
    private val NARROWEST = intArrayOf(0, 3, 5, 65535)

    private val LOWRATE_LOW = intArrayOf(
        0, 3, 5, 9, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383, 32767,
    )
    private val LOWRATE_MID = intArrayOf(0, 3, 5, 9, 15, 31, 63, 127)

    private val LSF_LOW = intArrayOf(
        0, 3, 5, 7, 9, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383,
    )
    private val LSF_MID = intArrayOf(0, 3, 5, 9, 15, 31, 63, 127)
    private val LSF_HIGH = intArrayOf(0, 3, 5, 9)

    /** 48 kHz above 56 kbps a channel, and 44.1 and 32 kHz from 56 to 80. */
    val TABLE_A: Allocation = build(
        27,
        listOf(
            Triple(0..2, 4, WIDE_LOW),
            Triple(3..10, 4, WIDE_MID),
            Triple(11..22, 3, NARROW),
            Triple(23..26, 2, NARROWEST),
        ),
    )

    /** 44.1 and 32 kHz above 96 kbps a channel. Table A plus three subbands. */
    val TABLE_B: Allocation = build(
        30,
        listOf(
            Triple(0..2, 4, WIDE_LOW),
            Triple(3..10, 4, WIDE_MID),
            Triple(11..22, 3, NARROW),
            Triple(23..29, 2, NARROWEST),
        ),
    )

    /** 44.1 and 48 kHz at 32 and 48 kbps a channel. */
    val TABLE_C: Allocation = build(
        8,
        listOf(
            Triple(0..1, 4, LOWRATE_LOW),
            Triple(2..7, 3, LOWRATE_MID),
        ),
    )

    /** 32 kHz at 32 and 48 kbps a channel. Table C reaching four more subbands. */
    val TABLE_D: Allocation = build(
        12,
        listOf(
            Triple(0..1, 4, LOWRATE_LOW),
            Triple(2..11, 3, LOWRATE_MID),
        ),
    )

    /**
     * Every MPEG-2 stream, whatever its bitrate and channel mode.
     *
     * Note it stops at 16383 levels rather than 65535, and that its second
     * group skips seven levels where the first group has them. Both look wrong
     * beside the MPEG-1 tables and are what the standard prints.
     */
    val LSF: Allocation = build(
        30,
        listOf(
            Triple(0..3, 4, LSF_LOW),
            Triple(4..10, 3, LSF_MID),
            Triple(11..29, 2, LSF_HIGH),
        ),
    )
}

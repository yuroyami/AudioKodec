package io.github.yuroyami.kiteaudio.internal.mpeg

/**
 * The constant tables Layer III decoding needs.
 *
 * Every number here comes from ISO/IEC 11172-3 and 13818-3, and every one was
 * compared across several independent renderings before it was written down.
 * `reference/REFERENCES.md` records which, and records the two places where the
 * sources disagreed and one of them was wrong.
 *
 * The synthesis window is stored as integers rather than decimals. Every value
 * the standard prints for it is an exact multiple of 1/65536, and the nine
 * decimal places it prints are that exact value rounded, so keeping the
 * numerators removes the question of how many digits to copy. Two entries,
 * D[196] and D[316], land exactly halfway and the standard rounds them one way
 * while every implementation rounds them the other; as integers there is
 * nothing to disagree about.
 */
internal object Mp3Tables {

    /**
     * Sample rates in the order the scalefactor band tables are indexed:
     * MPEG-1, then MPEG-2, then MPEG-2.5.
     */
    private val SAMPLE_RATES = intArrayOf(
        44_100, 48_000, 32_000, 22_050, 24_000, 16_000, 11_025, 12_000, 8_000,
    )

    /**
     * Long block scalefactor bands: 23 absolute boundaries into the 576 line
     * granule. Band n covers `t[n] until t[n + 1]`.
     */
    private val LONG_BANDS: Array<IntArray> = arrayOf(
        intArrayOf(0, 4, 8, 12, 16, 20, 24, 30, 36, 44, 52, 62, 74, 90, 110, 134, 162, 196, 238, 288, 342, 418, 576),
        intArrayOf(0, 4, 8, 12, 16, 20, 24, 30, 36, 42, 50, 60, 72, 88, 106, 128, 156, 190, 230, 276, 330, 384, 576),
        intArrayOf(0, 4, 8, 12, 16, 20, 24, 30, 36, 44, 54, 66, 82, 102, 126, 156, 194, 240, 296, 364, 448, 550, 576),
        intArrayOf(0, 6, 12, 18, 24, 30, 36, 44, 54, 66, 80, 96, 116, 140, 168, 200, 238, 284, 336, 396, 464, 522, 576),
        intArrayOf(0, 6, 12, 18, 24, 30, 36, 44, 54, 66, 80, 96, 114, 136, 162, 194, 232, 278, 332, 394, 464, 540, 576),
        intArrayOf(0, 6, 12, 18, 24, 30, 36, 44, 54, 66, 80, 96, 116, 140, 168, 200, 238, 284, 336, 396, 464, 522, 576),
        intArrayOf(0, 6, 12, 18, 24, 30, 36, 44, 54, 66, 80, 96, 116, 140, 168, 200, 238, 284, 336, 396, 464, 522, 576),
        intArrayOf(0, 6, 12, 18, 24, 30, 36, 44, 54, 66, 80, 96, 116, 140, 168, 200, 238, 284, 336, 396, 464, 522, 576),
        intArrayOf(0, 12, 24, 36, 48, 60, 72, 88, 108, 132, 160, 192, 232, 280, 336, 400, 476, 566, 568, 570, 572, 574, 576),
    )

    /**
     * Short block scalefactor bands: 14 boundaries inside one of the three
     * short windows, ending at 192. The granule holds three of them
     * interleaved, so an offset into the granule is `t[n] * 3`.
     */
    private val SHORT_BANDS: Array<IntArray> = arrayOf(
        intArrayOf(0, 4, 8, 12, 16, 22, 30, 40, 52, 66, 84, 106, 136, 192),
        intArrayOf(0, 4, 8, 12, 16, 22, 28, 38, 50, 64, 80, 100, 126, 192),
        intArrayOf(0, 4, 8, 12, 16, 22, 30, 42, 58, 78, 104, 138, 180, 192),
        intArrayOf(0, 4, 8, 12, 18, 24, 32, 42, 56, 74, 100, 132, 174, 192),
        intArrayOf(0, 4, 8, 12, 18, 26, 36, 48, 62, 80, 104, 136, 180, 192),
        intArrayOf(0, 4, 8, 12, 18, 26, 36, 48, 62, 80, 104, 134, 174, 192),
        intArrayOf(0, 4, 8, 12, 18, 26, 36, 48, 62, 80, 104, 134, 174, 192),
        intArrayOf(0, 4, 8, 12, 18, 26, 36, 48, 62, 80, 104, 134, 174, 192),
        intArrayOf(0, 8, 16, 24, 36, 52, 72, 96, 124, 160, 162, 164, 166, 192),
    )

    /** Which row of the band tables a sample rate uses. */
    private fun rowOf(sampleRate: Int): Int {
        for (i in SAMPLE_RATES.indices) if (SAMPLE_RATES[i] == sampleRate) return i
        // Not reachable from a parsed header, which only builds the nine rates.
        return 0
    }

    fun longBands(sampleRate: Int): IntArray = LONG_BANDS[rowOf(sampleRate)]

    fun shortBands(sampleRate: Int): IntArray = SHORT_BANDS[rowOf(sampleRate)]

    /**
     * The short band a mixed block's short half begins at.
     *
     * A mixed block is long at the bottom and short above it, and the two halves
     * meet at the same place in every layout the format defines.
     */
    const val MIXED_SHORT_START: Int = 3

    /** Spectral lines a mixed block's long half covers. */
    fun mixedLongLines(sampleRate: Int): Int = shortBands(sampleRate)[MIXED_SHORT_START] * 3

    /**
     * Long scalefactor bands inside a mixed block's long half.
     *
     * This is eight for MPEG-1 and six for MPEG-2, because MPEG-2's low bands
     * are wider, and it is derived here rather than written in because getting
     * it wrong is silent: the values still decode, into the wrong bands.
     */
    fun mixedLongBands(sampleRate: Int): Int {
        val lines = mixedLongLines(sampleRate)
        val bands = longBands(sampleRate)
        for (i in bands.indices) if (bands[i] >= lines) return i
        return bands.size - 1
    }

    /** Subbands a mixed block transforms as long ones. */
    fun mixedLongSubbands(sampleRate: Int): Int = mixedLongLines(sampleRate) / 18

    /**
     * Extra attenuation applied to the upper bands when a granule sets its
     * pre-emphasis flag, indexed by long block scalefactor band.
     */
    val PRETAB: IntArray = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 3, 2, 0,
    )

    /**
     * Alias reduction, ISO/IEC 11172-3 Table 3-B.9.
     *
     * The standard prints ci; cs and ca are derived from it. Note ci[7] is
     * -0.0037: one widely copied implementation has -0.0032 there, and it is
     * alone in that.
     */
    private val ALIAS_CI = doubleArrayOf(-0.6, -0.535, -0.33, -0.185, -0.095, -0.041, -0.0142, -0.0037)

    val ALIAS_CS: DoubleArray = DoubleArray(8) { 1.0 / kotlin.math.sqrt(1.0 + ALIAS_CI[it] * ALIAS_CI[it]) }

    val ALIAS_CA: DoubleArray = DoubleArray(8) { ALIAS_CI[it] / kotlin.math.sqrt(1.0 + ALIAS_CI[it] * ALIAS_CI[it]) }

    /**
     * MPEG-1 scalefactor field widths, indexed by `scalefac_compress`. The
     * first applies to the lower bands and the second to the upper ones.
     */
    val SLEN1: IntArray = intArrayOf(0, 0, 0, 0, 3, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4)
    val SLEN2: IntArray = intArrayOf(0, 1, 2, 3, 0, 1, 2, 3, 1, 2, 3, 1, 2, 3, 2, 3)

    /**
     * MPEG-2 scalefactor counts, `[block][blockShape][partition]`.
     *
     * MPEG-2 replaced MPEG-1's fixed grouping with one of six patterns chosen
     * by `scalefac_compress`, three of which exist only for the right channel
     * of an intensity stereo pair. The shape index is 0 for a long block, 1 for
     * a short one and 2 for a mixed one.
     */
    private val LSF_COUNTS: Array<Array<IntArray>> = arrayOf(
        arrayOf(intArrayOf(6, 5, 5, 5), intArrayOf(9, 9, 9, 9), intArrayOf(6, 9, 9, 9)),
        arrayOf(intArrayOf(6, 5, 7, 3), intArrayOf(9, 9, 12, 6), intArrayOf(6, 9, 12, 6)),
        arrayOf(intArrayOf(11, 10, 0, 0), intArrayOf(18, 18, 0, 0), intArrayOf(15, 18, 0, 0)),
        arrayOf(intArrayOf(7, 7, 7, 0), intArrayOf(12, 12, 12, 0), intArrayOf(6, 15, 12, 0)),
        arrayOf(intArrayOf(6, 6, 6, 3), intArrayOf(12, 9, 9, 6), intArrayOf(6, 12, 9, 6)),
        arrayOf(intArrayOf(8, 8, 5, 0), intArrayOf(15, 12, 9, 0), intArrayOf(6, 18, 9, 0)),
    )

    fun lsfCounts(block: Int, isShort: Boolean, isMixed: Boolean): IntArray {
        val shape = when {
            !isShort -> 0
            isMixed -> 2
            else -> 1
        }
        return LSF_COUNTS[block][shape]
    }

    /**
     * The synthesis window as numerators over 65536, which is what the
     * standard's decimals are exactly equal to.
     */
    private val WINDOW_NUMERATORS = intArrayOf(
        0, -1, -1, -1, -1, -1, -1, -2, -2, -2, -2, -3,
        -3, -4, -4, -5, -5, -6, -7, -7, -8, -9, -10, -11,
        -13, -14, -16, -17, -19, -21, -24, -26, -29, -31, -35, -38,
        -41, -45, -49, -53, -58, -63, -68, -73, -79, -85, -91, -97,
        -104, -111, -117, -125, -132, -139, -147, -154, -161, -169, -176, -183,
        -190, -196, -202, -208, 213, 218, 222, 225, 227, 228, 228, 227,
        224, 221, 215, 208, 200, 189, 177, 163, 146, 127, 106, 83,
        57, 29, -2, -36, -72, -111, -153, -197, -244, -294, -347, -401,
        -459, -519, -581, -645, -711, -779, -848, -919, -991, -1064, -1137, -1210,
        -1283, -1356, -1428, -1498, -1567, -1634, -1698, -1759, -1817, -1870, -1919, -1962,
        -2001, -2032, -2057, -2075, -2085, -2087, -2080, -2063, 2037, 2000, 1952, 1893,
        1822, 1739, 1644, 1535, 1414, 1280, 1131, 970, 794, 605, 402, 185,
        -45, -288, -545, -814, -1095, -1388, -1692, -2006, -2330, -2663, -3004, -3351,
        -3705, -4063, -4425, -4788, -5153, -5517, -5879, -6237, -6589, -6935, -7271, -7597,
        -7910, -8209, -8491, -8755, -8998, -9219, -9416, -9585, -9727, -9838, -9916, -9959,
        -9966, -9935, -9863, -9750, -9592, -9389, -9139, -8840, -8492, -8092, -7640, -7134,
        6574, 5959, 5288, 4561, 3776, 2935, 2037, 1082, 70, -998, -2122, -3300,
        -4533, -5818, -7154, -8540, -9975, -11455, -12980, -14548, -16155, -17799, -19478, -21189,
        -22929, -24694, -26482, -28289, -30112, -31947, -33791, -35640, -37489, -39336, -41176, -43006,
        -44821, -46617, -48390, -50137, -51853, -53534, -55178, -56778, -58333, -59838, -61289, -62684,
        -64019, -65290, -66494, -67629, -68692, -69679, -70590, -71420, -72169, -72835, -73415, -73908,
        -74313, -74630, -74856, -74992, 75038, 74992, 74856, 74630, 74313, 73908, 73415, 72835,
        72169, 71420, 70590, 69679, 68692, 67629, 66494, 65290, 64019, 62684, 61289, 59838,
        58333, 56778, 55178, 53534, 51853, 50137, 48390, 46617, 44821, 43006, 41176, 39336,
        37489, 35640, 33791, 31947, 30112, 28289, 26482, 24694, 22929, 21189, 19478, 17799,
        16155, 14548, 12980, 11455, 9975, 8540, 7154, 5818, 4533, 3300, 2122, 998,
        -70, -1082, -2037, -2935, -3776, -4561, -5288, -5959, 6574, 7134, 7640, 8092,
        8492, 8840, 9139, 9389, 9592, 9750, 9863, 9935, 9966, 9959, 9916, 9838,
        9727, 9585, 9416, 9219, 8998, 8755, 8491, 8209, 7910, 7597, 7271, 6935,
        6589, 6237, 5879, 5517, 5153, 4788, 4425, 4063, 3705, 3351, 3004, 2663,
        2330, 2006, 1692, 1388, 1095, 814, 545, 288, 45, -185, -402, -605,
        -794, -970, -1131, -1280, -1414, -1535, -1644, -1739, -1822, -1893, -1952, -2000,
        2037, 2063, 2080, 2087, 2085, 2075, 2057, 2032, 2001, 1962, 1919, 1870,
        1817, 1759, 1698, 1634, 1567, 1498, 1428, 1356, 1283, 1210, 1137, 1064,
        991, 919, 848, 779, 711, 645, 581, 519, 459, 401, 347, 294,
        244, 197, 153, 111, 72, 36, 2, -29, -57, -83, -106, -127,
        -146, -163, -177, -189, -200, -208, -215, -221, -224, -227, -228, -228,
        -227, -225, -222, -218, 213, 208, 202, 196, 190, 183, 176, 169,
        161, 154, 147, 139, 132, 125, 117, 111, 104, 97, 91, 85,
        79, 73, 68, 63, 58, 53, 49, 45, 41, 38, 35, 31,
        29, 26, 24, 21, 19, 17, 16, 14, 13, 11, 10, 9,
        8, 7, 7, 6, 5, 5, 4, 4, 3, 3, 2, 2,
        2, 2, 1, 1, 1, 1, 1, 1,
    )

    /**
     * The polyphase synthesis window, ISO/IEC 11172-3 Table 3-B.3.
     *
     * Signs are the standard's own. Do not take absolute values: the runs of
     * sign are part of the filter, and D[0] is zero while D[1] to D[63] are
     * negative. The peak is D[256] = 75038/65536.
     */
    val SYNTHESIS_WINDOW: DoubleArray = DoubleArray(512) { WINDOW_NUMERATORS[it] / 65536.0 }
}

package io.github.yuroyami.audiokodec.internal.mpeg

import io.github.yuroyami.audiokodec.internal.bits.BitReaderMsb
import kotlin.math.pow

/**
 * Turns one granule of Layer III bits into 576 spectral values.
 *
 * Three things happen here, in the order the format writes them. The
 * scalefactors come first: one number per band saying how loud that band is.
 * Then the spectrum itself, Huffman coded in three regions with a different
 * table each, which is how the format spends short codewords on the low
 * frequencies where the values are large and long ones on the high frequencies
 * where they are mostly zero. Then requantisation, which turns the coded
 * integers back into the numbers the encoder started from by raising them to
 * the four-thirds power and applying the scalefactors.
 *
 * Requantisation happens in the order the bits were written, which for a short
 * granule is not frequency order: the three windows of a band sit together.
 * Putting that right is [Mp3Synthesis]'s job and happens afterwards.
 *
 * Written from ISO/IEC 11172-3 and 13818-3.
 */
internal class Mp3Granule {

    /** Scalefactors for long blocks, one per band. */
    val longScalefactors = IntArray(23)

    /** Scalefactors for short blocks, one per band per window. */
    val shortScalefactors = Array(13) { IntArray(3) }

    /** The 576 spectral values, requantised. */
    val spectrum = DoubleArray(576)

    /** Coded spectral values, including trailing zeros; an upper bound for later work. */
    var nonZero = 0
        private set

    /**
     * Whether pre-emphasis applies to this granule.
     *
     * MPEG-1 transmits it as a bit. MPEG-2 has no room for one in its side
     * information and derives it from the scalefactor compression instead, so
     * it is kept here rather than read from the side information.
     */
    var preEmphasis = false
        private set

    /**
     * Which of the two intensity stereo ladders MPEG-2 uses, when this granule
     * is the right channel of an intensity pair.
     */
    var intensityScale = 0
        private set

    private val values = IntArray(576)
    private val pair = IntArray(2)
    private val quad = IntArray(4)

    /**
     * Reads the scalefactors of an MPEG-1 granule.
     *
     * A band's scalefactors may be shared with the granule before it, which is
     * what the selection bits in the side information say, and is why this
     * needs the granule number and keeps its own state between calls.
     */
    fun readScalefactorsVersion1(
        reader: BitReaderMsb,
        granule: Mp3SideInfo.Granule,
        scfsi: BooleanArray,
        granuleIndex: Int,
    ) {
        preEmphasis = granule.preflag
        val slen1 = Mp3Tables.SLEN1[granule.scalefacCompress]
        val slen2 = Mp3Tables.SLEN2[granule.scalefacCompress]

        if (granule.isShort) {
            if (granule.isMixed) {
                // A mixed block is long at the bottom and short above it, so
                // its scalefactors are read in both shapes.
                for (band in 0 until 8) longScalefactors[band] = reader.readBits(slen1)
                for (band in 3 until 6) {
                    for (window in 0 until 3) shortScalefactors[band][window] = reader.readBits(slen1)
                }
            } else {
                for (band in 0 until 6) {
                    for (window in 0 until 3) shortScalefactors[band][window] = reader.readBits(slen1)
                }
            }
            for (band in 6 until 12) {
                for (window in 0 until 3) shortScalefactors[band][window] = reader.readBits(slen2)
            }
            for (window in 0 until 3) shortScalefactors[12][window] = 0
        } else {
            // The four groups a band can belong to, and the lengths that apply.
            for (group in 0 until 4) {
                val length = if (group < 2) slen1 else slen2
                // A group whose selection bit is set keeps what the first
                // granule read, so the second granule codes nothing for it.
                if (granuleIndex != 0 && scfsi[group]) continue
                for (band in GROUP_STARTS[group] until GROUP_STARTS[group + 1]) {
                    longScalefactors[band] = reader.readBits(length)
                }
            }
            longScalefactors[21] = 0
            longScalefactors[22] = 0
        }
    }

    /**
     * Reads the scalefactors of an MPEG-2 granule.
     *
     * MPEG-2 replaced the fixed grouping with one chosen from several patterns
     * by the compression field, which is nine bits rather than four. The right
     * channel of an intensity stereo pair uses patterns of its own, because
     * there its scalefactors are positions between the speakers rather than
     * loudnesses.
     *
     * Returns the scalefactor multiplier, which MPEG-2 derives here rather than
     * taking from a bit in the side information.
     */
    fun readScalefactorsVersion2(
        reader: BitReaderMsb,
        granule: Mp3SideInfo.Granule,
        header: MpegFrameHeader,
        intensityRight: Boolean,
    ) {
        intensityScale = granule.scalefacCompress and 1
        var compress = granule.scalefacCompress
        val slen = IntArray(4)
        val block: Int

        if (intensityRight) {
            compress = compress ushr 1
            when {
                compress < 180 -> {
                    slen[0] = compress / 36
                    slen[1] = (compress % 36) / 6
                    slen[2] = compress % 6
                    block = 3
                }
                compress < 244 -> {
                    val value = compress - 180
                    slen[0] = (value ushr 4) and 3
                    slen[1] = (value ushr 2) and 3
                    slen[2] = value and 3
                    block = 4
                }
                else -> {
                    val value = compress - 244
                    slen[0] = value / 3
                    slen[1] = value % 3
                    block = 5
                }
            }
        } else {
            when {
                compress < 400 -> {
                    slen[0] = (compress ushr 4) / 5
                    slen[1] = (compress ushr 4) % 5
                    slen[2] = (compress and 15) ushr 2
                    slen[3] = compress and 3
                    block = 0
                }
                compress < 500 -> {
                    val value = compress - 400
                    slen[0] = (value ushr 2) / 5
                    slen[1] = (value ushr 2) % 5
                    slen[2] = value and 3
                    block = 1
                }
                else -> {
                    val value = compress - 500
                    slen[0] = value / 3
                    slen[1] = value % 3
                    block = 2
                }
            }
        }

        // The one pattern that codes pre-emphasis is also the one that puts its
        // partition boundary exactly where the pre-emphasis table starts.
        preEmphasis = !intensityRight && block == 2

        val counts = Mp3Tables.lsfCounts(block, granule.isShort, granule.isMixed)

        // The four partitions choose bit widths and nothing else. Where the
        // long scalefactors stop and the short ones start is a fixed position
        // in the sequence, and two of the six patterns have a partition that
        // straddles it, so splitting by partition decodes those two into the
        // wrong bands.
        var longsLeft = when {
            !granule.isShort -> Int.MAX_VALUE
            granule.isMixed -> Mp3Tables.mixedLongBands(header.sampleRate)
            else -> 0
        }
        var longBand = 0
        var shortBand = if (granule.isMixed) Mp3Tables.MIXED_SHORT_START else 0
        var window = 0

        for (group in 0 until 4) {
            val length = slen[group]
            repeat(counts[group]) {
                val value = if (length > 0) reader.readBits(length) else 0
                if (longsLeft > 0) {
                    if (longBand < longScalefactors.size) longScalefactors[longBand] = value
                    longBand++
                    longsLeft--
                } else {
                    if (shortBand < shortScalefactors.size) shortScalefactors[shortBand][window] = value
                    window++
                    if (window == 3) {
                        window = 0
                        shortBand++
                    }
                }
            }
        }
    }

    /**
     * Reads the Huffman coded spectrum and requantises it.
     *
     * [bitsAvailable] bounds the read: a granule states how many bits its
     * scalefactors and spectrum occupy together, and running past that would
     * eat the next granule's data. The format allows an encoder to write fewer
     * values than it declared, in which case the rest are zero.
     */
    fun readSpectrum(
        reader: BitReaderMsb,
        granule: Mp3SideInfo.Granule,
        header: MpegFrameHeader,
        bitsAvailable: Int,
    ) {
        spectrum.fill(0.0)
        values.fill(0)
        val end = reader.bitPosition + bitsAvailable

        val longBands = Mp3Tables.longBands(header.sampleRate)
        val shortBands = Mp3Tables.shortBands(header.sampleRate)

        val bigValues = (granule.bigValues * 2).coerceAtMost(576)
        var region1Start: Int
        var region2Start: Int
        if (granule.windowSwitching) {
            region1Start = switchedRegion1(granule, longBands, shortBands)
            region2Start = 576
        } else {
            region1Start = longBands[(granule.region0Count + 1).coerceAtMost(22)]
            region2Start = longBands[
                (granule.region0Count + granule.region1Count + 2).coerceAtMost(22),
            ]
        }
        region1Start = region1Start.coerceAtMost(bigValues)
        region2Start = region2Start.coerceAtMost(bigValues)

        var index = 0
        while (index < bigValues) {
            if (reader.bitPosition >= end) break
            val table = when {
                index < region1Start -> granule.tableSelect[0]
                index < region2Start -> granule.tableSelect[1]
                else -> granule.tableSelect[2]
            }
            if (!Mp3Huffman.decodePair(reader, table, pair)) break
            values[index] = pair[0]
            values[index + 1] = pair[1]
            index += 2
        }

        // The values above the big value region are all -1, 0 or 1, so they are
        // coded four at a time by one of two small tables. This region has no
        // stated length: it runs until the granule's bits do.
        while (index + 4 <= 576 && reader.bitPosition < end) {
            if (!Mp3Huffman.decodeQuad(reader, granule.count1TableSelect, quad)) break
            values[index] = quad[0]
            values[index + 1] = quad[1]
            values[index + 2] = quad[2]
            values[index + 3] = quad[3]
            index += 4
        }
        // The last quadruple may run past the granule's declared length. It was
        // never the encoder's, so it goes back.
        if (reader.bitPosition > end && index >= 4) {
            index -= 4
            for (i in index until index + 4) values[i] = 0
        }

        nonZero = index.coerceAtMost(576)
        reader.seekBits(end.coerceAtMost(reader.bitLength))

        requantise(granule, longBands, shortBands)
    }

    /**
     * Where region 1 begins in a granule that switched windows.
     *
     * A short block, including a mixed one, switches tables after the first
     * three short scalefactor bands across all three windows. The mixed flag
     * changes the transform at low frequencies, not this Huffman boundary.
     * Start and stop blocks use eight long bands instead. These boundaries
     * are 36 and 54 for MPEG-2, and 72 and 108 at MPEG-2.5's 8 kHz.
     */
    private fun switchedRegion1(
        granule: Mp3SideInfo.Granule,
        longBands: IntArray,
        shortBands: IntArray,
    ): Int {
        return if (granule.isShort) shortBands[3] * 3 else longBands[granule.region0Count + 1]
    }

    /**
     * Turns coded integers back into spectral values.
     *
     * The format codes the four-thirds root of each value, which spreads the
     * quantisation error the way hearing does, and scales whole bands by the
     * scalefactors so that a quiet band is not swamped by a loud one.
     */
    private fun requantise(
        granule: Mp3SideInfo.Granule,
        longBands: IntArray,
        shortBands: IntArray,
    ) {
        val scaleShift = if (granule.scalefacScale == 0) 0.5 else 1.0
        val globalScale = 2.0.pow((granule.globalGain - 210) * 0.25)

        if (!granule.isShort) {
            var band = 0
            for (i in 0 until nonZero) {
                while (band < 21 && i >= longBands[band + 1]) band++
                val pre = if (preEmphasis) Mp3Tables.PRETAB[band] else 0
                val scale = 2.0.pow(-scaleShift * (longScalefactors[band] + pre))
                spectrum[i] = power43(values[i]) * globalScale * scale
            }
            return
        }

        // A short granule is three windows written one band at a time, and a
        // mixed one is a long block at the bottom with short windows above it.
        val mixedUntil = if (granule.isMixed) shortBands[Mp3Tables.MIXED_SHORT_START] * 3 else 0
        if (mixedUntil > 0) {
            var band = 0
            for (i in 0 until mixedUntil.coerceAtMost(nonZero)) {
                while (band < 21 && i >= longBands[band + 1]) band++
                val scale = 2.0.pow(-scaleShift * longScalefactors[band])
                spectrum[i] = power43(values[i]) * globalScale * scale
            }
        }

        var i = mixedUntil
        var band = if (granule.isMixed) Mp3Tables.MIXED_SHORT_START else 0
        while (i < nonZero && band < 13) {
            val width = shortBands[band + 1] - shortBands[band]
            for (window in 0 until 3) {
                val gain = 2.0.pow(
                    (granule.globalGain - 210 - 8 * granule.subblockGain[window]) * 0.25,
                )
                val scale = 2.0.pow(-scaleShift * shortScalefactors[band][window])
                for (k in 0 until width) {
                    if (i >= nonZero) return
                    spectrum[i] = power43(values[i]) * gain * scale
                    i++
                }
            }
            band++
        }
    }

    private fun power43(value: Int): Double {
        if (value == 0) return 0.0
        val magnitude = if (value < 0) -value else value
        val result = if (magnitude < POWER_TABLE.size) {
            POWER_TABLE[magnitude]
        } else {
            magnitude.toDouble().pow(4.0 / 3.0)
        }
        return if (value < 0) -result else result
    }

    private companion object {

        /** Where each of MPEG-1's four scalefactor groups starts and ends. */
        val GROUP_STARTS = intArrayOf(0, 6, 11, 16, 21)

        /** The four-thirds power of every value a big value table can code. */
        val POWER_TABLE = DoubleArray(8207) { it.toDouble().pow(4.0 / 3.0) }
    }
}

package io.github.yuroyami.audiokodec.internal.aac

import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.internal.bits.BitReaderMsb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How one block's spectrum is divided: one long window or eight short ones,
 * the window shape, how many bands are coded, and how the short windows are
 * grouped. A channel pair with a common window shares one.
 *
 * Written from ISO/IEC 14496-3:2009, 4.5.2.3 and Table 4.6.
 */
internal class AacIcsInfo(private val tableIndex: Int) {
    var windowSequence: Int = ONLY_LONG
        private set
    var windowShape: Int = 0
        private set

    /** Bands coded in each window group. */
    var maxSfb: Int = 0
        private set
    var windowCount: Int = 1
        private set
    var groupCount: Int = 1
        private set

    /** Windows in each group. */
    val groupLength: IntArray = IntArray(8)

    /** Band offsets within one window: the long table or the short one. */
    var swbOffset: IntArray = AacTables.SWB_LONG[tableIndex]
        private set

    /** Bands a window of this kind has, coded or not. */
    val bandCount: Int get() = swbOffset.size - 1

    /** Coefficients in one window. */
    val windowLength: Int get() = if (isShort) 128 else 1024

    val isShort: Boolean get() = windowSequence == EIGHT_SHORT

    /** Reads `ics_info()`. */
    fun read(bits: BitReaderMsb) {
        if (bits.readBit() != 0) throw CorruptStreamException("an AAC block sets the reserved bit of its channel information")
        windowSequence = bits.readBits(2)
        windowShape = bits.readBit()
        if (isShort) {
            swbOffset = AacTables.SWB_SHORT[tableIndex]
            maxSfb = bits.readBits(4)
            val grouping = bits.readBits(7)
            windowCount = 8
            groupCount = 1
            groupLength.fill(0)
            groupLength[0] = 1
            // A set bit 6 - i puts window i + 1 in the same group as window i.
            for (i in 0 until 7) {
                if ((grouping ushr (6 - i)) and 1 == 1) {
                    groupLength[groupCount - 1]++
                } else {
                    groupLength[groupCount++] = 1
                }
            }
        } else {
            swbOffset = AacTables.SWB_LONG[tableIndex]
            maxSfb = bits.readBits(6)
            windowCount = 1
            groupCount = 1
            groupLength[0] = 1
            if (bits.readBit() == 1) {
                throw CorruptStreamException("an AAC-LC block signals prediction, which only AAC Main and LTP have")
            }
        }
        if (maxSfb > bandCount) {
            throw CorruptStreamException("an AAC block codes $maxSfb bands where its windows have $bandCount")
        }
    }

    fun copyFrom(other: AacIcsInfo) {
        windowSequence = other.windowSequence
        windowShape = other.windowShape
        maxSfb = other.maxSfb
        windowCount = other.windowCount
        groupCount = other.groupCount
        other.groupLength.copyInto(groupLength)
        swbOffset = other.swbOffset
    }

    companion object {
        const val ONLY_LONG: Int = 0
        const val LONG_START: Int = 1
        const val EIGHT_SHORT: Int = 2
        const val LONG_STOP: Int = 3
    }
}

/**
 * One channel of one block: its side information, its scalefactors, and the
 * spectrum they rebuild, from `individual_channel_stream()`.
 *
 * Written from ISO/IEC 14496-3:2009, 4.6.1 to 4.6.3 for the spectrum, 4.6.9
 * for temporal noise shaping and 4.6.13 for noise substitution.
 */
internal class AacChannelStream(tableIndex: Int) {

    val info: AacIcsInfo = AacIcsInfo(tableIndex)

    /**
     * The codebook of each band, by `group * MAX_BANDS + band`: 0 for none,
     * 1 to 11 for the spectral codebooks, [NOISE] and the two intensity
     * codebooks.
     */
    val codebook: IntArray = IntArray(8 * MAX_BANDS)

    /**
     * What each band's scalefactor slot carries: a scalefactor for a
     * spectral codebook, an intensity position, or a noise energy.
     */
    val bandValue: IntArray = IntArray(8 * MAX_BANDS)

    /** Coefficients in window order, each window's lowest frequency first. */
    val spectrum: FloatArray = FloatArray(1024)

    private val quantised = IntArray(1024)

    private var pulseCount = 0
    private var pulseStartBand = 0
    private val pulseOffset = IntArray(4)
    private val pulseAmplitude = IntArray(4)

    private val tnsFilterCount = IntArray(8)
    private val tnsCoefficientResolution = IntArray(8)
    private val tnsLength = IntArray(8 * 3)
    private val tnsOrder = IntArray(8 * 3)
    private val tnsDirection = IntArray(8 * 3)
    private val tnsCompress = IntArray(8 * 3)
    private val tnsCoefficients = IntArray(8 * 3 * 32)
    private var tnsPresent = false

    /**
     * Reads `individual_channel_stream()` into [spectrum]. The side
     * information is read here unless the pair it belongs to shares one,
     * and [intensityAllowed] when this is the second channel of such a pair.
     */
    fun read(bits: BitReaderMsb, commonWindow: Boolean, intensityAllowed: Boolean) {
        val globalGain = bits.readBits(8)
        if (!commonWindow) info.read(bits)
        readSections(bits, intensityAllowed)
        readScalefactors(bits, globalGain)
        pulseCount = 0
        if (bits.readBit() == 1) readPulses(bits)
        tnsPresent = bits.readBit() == 1
        if (tnsPresent) readTns(bits)
        if (bits.readBit() == 1) {
            throw CorruptStreamException("an AAC-LC block carries gain control data, which only AAC SSR has")
        }
        readSpectralData(bits)
        dequantise()
    }

    /** `section_data()`: runs of bands sharing a codebook. */
    private fun readSections(bits: BitReaderMsb, intensityAllowed: Boolean) {
        val lengthBits = if (info.isShort) 3 else 5
        val escape = (1 shl lengthBits) - 1
        for (g in 0 until info.groupCount) {
            var band = 0
            while (band < info.maxSfb) {
                val book = bits.readBits(4)
                if (book == RESERVED) throw CorruptStreamException("an AAC block uses reserved codebook 12")
                if ((book == INTENSITY || book == INTENSITY_OUT_OF_PHASE) && !intensityAllowed) {
                    throw CorruptStreamException("an AAC block uses intensity stereo outside the second channel of a pair")
                }
                var length = 0
                while (true) {
                    val increment = bits.readBits(lengthBits)
                    length += increment
                    if (increment != escape) break
                }
                if (length == 0 || band + length > info.maxSfb) {
                    throw CorruptStreamException("an AAC section runs past the bands its block codes")
                }
                for (b in band until band + length) codebook[g * MAX_BANDS + b] = book
                band += length
            }
        }
    }

    /**
     * `scale_factor_data()`. Scalefactors, intensity positions and noise
     * energies are each coded as differences from the last of their own
     * kind (4.6.2.3.2, 4.6.8.2.3, 4.6.13.3).
     */
    private fun readScalefactors(bits: BitReaderMsb, globalGain: Int) {
        var scalefactor = globalGain
        var position = 0
        var energy = globalGain - NOISE_OFFSET
        var firstNoise = true
        for (g in 0 until info.groupCount) {
            for (band in 0 until info.maxSfb) {
                val slot = g * MAX_BANDS + band
                when (codebook[slot]) {
                    ZERO -> bandValue[slot] = 0
                    INTENSITY, INTENSITY_OUT_OF_PHASE -> {
                        position += AacHuffman.readScalefactorDelta(bits)
                        bandValue[slot] = position
                    }
                    NOISE -> {
                        if (firstNoise) {
                            firstNoise = false
                            energy += bits.readBits(9) - 256
                        } else {
                            energy += AacHuffman.readScalefactorDelta(bits)
                        }
                        bandValue[slot] = energy
                    }
                    else -> {
                        scalefactor += AacHuffman.readScalefactorDelta(bits)
                        if (scalefactor !in 0..255) throw CorruptStreamException("an AAC scalefactor leaves the range 0 to 255")
                        bandValue[slot] = scalefactor
                    }
                }
            }
        }
    }

    /** `pulse_data()`, which only a long window may carry. */
    private fun readPulses(bits: BitReaderMsb) {
        if (info.isShort) throw CorruptStreamException("an AAC block of short windows carries pulse data")
        pulseCount = bits.readBits(2) + 1
        pulseStartBand = bits.readBits(6)
        if (pulseStartBand >= info.bandCount) throw CorruptStreamException("AAC pulse data starts past the last band")
        for (i in 0 until pulseCount) {
            pulseOffset[i] = bits.readBits(5)
            pulseAmplitude[i] = bits.readBits(4)
        }
    }

    /** `tns_data()`, with field widths by window length, Table 4.155. */
    private fun readTns(bits: BitReaderMsb) {
        val short = info.isShort
        for (w in 0 until info.windowCount) {
            val filters = bits.readBits(if (short) 1 else 2)
            tnsFilterCount[w] = filters
            if (filters == 0) continue
            tnsCoefficientResolution[w] = bits.readBit()
            for (f in 0 until filters) {
                val slot = w * 3 + f
                tnsLength[slot] = bits.readBits(if (short) 4 else 6)
                val order = bits.readBits(if (short) 3 else 5)
                tnsOrder[slot] = order
                if (order == 0) continue
                tnsDirection[slot] = bits.readBit()
                tnsCompress[slot] = bits.readBit()
                val width = tnsCoefficientResolution[w] + 3 - tnsCompress[slot]
                for (i in 0 until order) tnsCoefficients[slot * 32 + i] = bits.readBits(width)
            }
        }
    }

    /** `spectral_data()`, decoded straight into window order. */
    private fun readSpectralData(bits: BitReaderMsb) {
        quantised.fill(0)
        val offsets = info.swbOffset
        val stride = info.windowLength
        var firstWindow = 0
        for (g in 0 until info.groupCount) {
            val windows = info.groupLength[g]
            for (band in 0 until info.maxSfb) {
                val book = codebook[g * MAX_BANDS + band]
                if (book == ZERO || book > AacHuffman.ESCAPE_CODEBOOK) continue
                val start = offsets[band]
                val end = offsets[band + 1]
                // Bands are multiples of four wide, so no codeword spans two windows.
                for (w in firstWindow until firstWindow + windows) {
                    var k = w * stride + start
                    val stop = w * stride + end
                    while (k < stop) k += AacHuffman.readSpectral(bits, book, quantised, k)
                }
            }
            firstWindow += windows
        }
        if (pulseCount > 0) {
            var k = offsets[pulseStartBand]
            for (i in 0 until pulseCount) {
                k += pulseOffset[i]
                if (k >= 1024) throw CorruptStreamException("an AAC pulse lands past the last coefficient")
                val value = quantised[k]
                quantised[k] = if (value > 0) value + pulseAmplitude[i] else value - pulseAmplitude[i]
            }
        }
    }

    /** Rebuilds the coded coefficients, 4.6.1.3 and 4.6.2.3.3; noise and intensity bands stay zero for now. */
    private fun dequantise() {
        spectrum.fill(0f)
        val offsets = info.swbOffset
        val stride = info.windowLength
        var firstWindow = 0
        for (g in 0 until info.groupCount) {
            val windows = info.groupLength[g]
            for (band in 0 until info.maxSfb) {
                val slot = g * MAX_BANDS + band
                val book = codebook[slot]
                if (book == ZERO || book > AacHuffman.ESCAPE_CODEBOOK) continue
                val gain = SCALEFACTOR_GAIN[bandValue[slot]]
                for (w in firstWindow until firstWindow + windows) {
                    for (k in w * stride + offsets[band] until w * stride + offsets[band + 1]) {
                        val q = quantised[k]
                        if (q == 0) continue
                        val magnitude = if (abs(q) < POW_4_3.size) POW_4_3[abs(q)] else abs(q).toDouble().pow(4.0 / 3.0).toFloat()
                        spectrum[k] = if (q < 0) -magnitude * gain else magnitude * gain
                    }
                }
            }
            firstWindow += windows
        }
    }

    /** True when band [band] of group [group] is noise substituted. */
    fun isNoise(group: Int, band: Int): Boolean = codebook[group * MAX_BANDS + band] == NOISE

    /** +1 or -1 for an intensity band, by its codebook, and 0 for any other. */
    fun intensityOf(group: Int, band: Int): Int = when (codebook[group * MAX_BANDS + band]) {
        INTENSITY -> 1
        INTENSITY_OUT_OF_PHASE -> -1
        else -> 0
    }

    /**
     * Fills band [band] of group [group] with noise at its coded energy,
     * 4.6.13.3, from [random], or from [source]'s same band when the pair
     * correlates the noise of both channels.
     */
    fun substituteNoise(group: Int, band: Int, firstWindow: Int, random: AacNoise, source: AacChannelStream?) {
        val offsets = info.swbOffset
        val stride = info.windowLength
        val target = 2.0.pow(0.25 * gainExponent(bandValue[group * MAX_BANDS + band]))
        for (w in firstWindow until firstWindow + info.groupLength[group]) {
            val start = w * stride + offsets[band]
            val end = w * stride + offsets[band + 1]
            var energy = 0.0
            for (k in start until end) {
                val value = source?.spectrum?.get(k) ?: random.next()
                spectrum[k] = value
                energy += value.toDouble() * value
            }
            if (energy <= 0.0) continue
            val scale = (target / sqrt(energy)).toFloat()
            for (k in start until end) spectrum[k] *= scale
        }
    }

    /**
     * Applies the noise shaping filters, 4.6.9.3, for [objectType]'s
     * TNS_MAX_ORDER and the sampling frequency's TNS_MAX_BANDS.
     */
    fun applyTns(tableIndex: Int) {
        if (!tnsPresent) return
        val short = info.isShort
        val maxOrder = if (short) 7 else 12
        val maxBands = minOf(
            if (short) AacTables.TNS_MAX_BANDS_SHORT[tableIndex] else AacTables.TNS_MAX_BANDS_LONG[tableIndex],
            info.maxSfb,
        )
        val offsets = info.swbOffset
        val stride = info.windowLength
        val lpc = FloatArray(maxOrder + 1)
        val state = FloatArray(maxOrder)
        for (w in 0 until info.windowCount) {
            var bottom = info.bandCount
            for (f in 0 until tnsFilterCount[w]) {
                val slot = w * 3 + f
                val top = bottom
                bottom = maxOf(top - tnsLength[slot], 0)
                val order = minOf(tnsOrder[slot], maxOrder)
                if (order == 0) continue
                decodeTnsCoefficients(slot, order, tnsCoefficientResolution[w] + 3, lpc)
                val start = w * stride + offsets[minOf(bottom, maxBands)]
                val end = w * stride + offsets[minOf(top, maxBands)]
                val size = end - start
                if (size <= 0) continue
                // The all-pole filter y(n) = x(n) - lpc[1] y(n-1) - ... - lpc[order] y(n-order),
                // run upwards or downwards, with its state cleared for each filter.
                state.fill(0f)
                var k = if (tnsDirection[slot] == 1) end - 1 else start
                val step = if (tnsDirection[slot] == 1) -1 else 1
                repeat(size) {
                    var y = spectrum[k]
                    for (i in 0 until order) y -= lpc[i + 1] * state[i]
                    for (i in order - 1 downTo 1) state[i] = state[i - 1]
                    state[0] = y
                    spectrum[k] = y
                    k += step
                }
            }
        }
    }

    /** `tns_decode_coef()`: sign extension, inverse quantisation, and conversion to direct form. */
    private fun decodeTnsCoefficients(slot: Int, order: Int, resolutionBits: Int, lpc: FloatArray) {
        val width = resolutionBits - tnsCompress[slot]
        val signBit = 1 shl (width - 1)
        val positive = ((1 shl (resolutionBits - 1)) - 0.5) / (PI / 2.0)
        val negative = ((1 shl (resolutionBits - 1)) + 0.5) / (PI / 2.0)
        val reflection = DoubleArray(order)
        for (i in 0 until order) {
            val raw = tnsCoefficients[slot * 32 + i]
            val value = if (raw and signBit != 0) raw - (1 shl width) else raw
            reflection[i] = sin(value / if (value >= 0) positive else negative)
        }
        val a = DoubleArray(order + 1)
        val b = DoubleArray(order + 1)
        a[0] = 1.0
        for (m in 1..order) {
            for (i in 1 until m) b[i] = a[i] + reflection[m - 1] * a[m - i]
            for (i in 1 until m) a[i] = b[i]
            a[m] = reflection[m - 1]
        }
        for (i in 0..order) lpc[i] = a[i].toFloat()
    }

    companion object {
        /**
         * Noise energies and intensity positions are differences with no
         * stated bound, so damaged data could carry them to gains no float
         * can hold. They are held to the span scalefactors reach, from
         * 2^-25 to 2^38.75: quieter than any output can show, and louder than
         * full scale.
         */
        fun gainExponent(value: Int): Int = value.coerceIn(-100, 155)

        /** Long windows have at most 51 bands, and a group of short windows 15. */
        const val MAX_BANDS: Int = 64

        const val ZERO: Int = 0
        const val RESERVED: Int = 12
        const val NOISE: Int = 13
        const val INTENSITY_OUT_OF_PHASE: Int = 14
        const val INTENSITY: Int = 15

        /** NOISE_OFFSET, 4.6.13.3. */
        private const val NOISE_OFFSET = 90

        /** 2^(0.25 (sf - 100)) for every scalefactor, SF_OFFSET being 100. */
        private val SCALEFACTOR_GAIN = FloatArray(256) { 2.0.pow(0.25 * (it - 100)).toFloat() }

        /** |x|^(4/3) for every value a codeword can carry before pulses are added. */
        private val POW_4_3 = FloatArray(8192) { it.toDouble().pow(4.0 / 3.0).toFloat() }
    }
}

/**
 * The random numbers noise substitution scales into a band. 4.6.13.3 leaves
 * the generator open; this is a linear congruential generator, so that a
 * file decodes to the same samples every time.
 */
internal class AacNoise {
    private var state = SEED

    fun next(): Float {
        state = state * 1_664_525 + 1_013_904_223
        return state.toFloat()
    }

    fun reset() {
        state = SEED
    }

    private companion object {
        const val SEED = 0x1F2E3D4C
    }
}

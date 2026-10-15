package io.github.yuroyami.audiokodec.internal.opus.celt

import io.github.yuroyami.audiokodec.internal.opus.RangeDecoder
import io.github.yuroyami.audiokodec.internal.opus.RangeEncoder

/**
 * How CELT divides a packet's bits between its bands.
 *
 * Nothing about the split is transmitted directly. Both ends run the same
 * search over the same tables from the bits that are left, nudged by the few
 * boosts, the trim and the skip decisions the packet does carry, which is why
 * every integer here has to match the reference exactly: one bit placed
 * differently desynchronises everything after it.
 *
 * Translated from libopus `rate.c` and `rate.h`, and `init_caps` from `celt.c`.
 */
internal object CeltAllocation {

    const val BITRES: Int = RangeDecoder.BITRES
    private const val ALLOC_STEPS = 6
    private const val LOG_MAX_PSEUDO = 6
    private const val MAX_FINE_BITS = 8
    private const val FINE_OFFSET = 21

    private val LOG2_FRAC_TABLE = intArrayOf(
        0, 8, 13, 16, 19, 21, 23, 24, 26, 27, 28, 29, 30, 31, 32, 32, 33, 34, 34, 35, 36, 36, 37, 37,
    )

    private val eBands = CeltModeTables.E_BANDS
    private val NB_E_BANDS = 21
    private val NB_ALLOC_VECTORS = 11

    /** `get_pulses`: the pulse count a pseudo-pulse index stands for. */
    fun pulses(i: Int): Int = if (i < 8) i else (8 + (i and 7)) shl ((i shr 3) - 1)

    private fun cacheStart(band: Int, lm: Int): Int = CeltModeTables.CACHE_INDEX[(lm + 1) * NB_E_BANDS + band]

    /** `bits2pulses`: the most pulses [bits] eighth-bits buy in [band] at block size [lm]. */
    fun bitsToPulses(band: Int, lm: Int, bitsIn: Int): Int {
        val cache = CeltModeTables.CACHE_BITS
        val start = cacheStart(band, lm)
        var lo = 0
        var hi = cache[start]
        val bits = bitsIn - 1
        repeat(LOG_MAX_PSEUDO) {
            val mid = (lo + hi + 1) shr 1
            if (cache[start + mid] >= bits) hi = mid else lo = mid
        }
        return if (bits - (if (lo == 0) -1 else cache[start + lo]) <= cache[start + hi] - bits) lo else hi
    }

    /** `pulses2bits`: what [pulses] cost in [band] at block size [lm], in eighth-bits. */
    fun pulsesToBits(band: Int, lm: Int, pulses: Int): Int =
        if (pulses == 0) 0 else CeltModeTables.CACHE_BITS[cacheStart(band, lm) + pulses] + 1

    /** Whether a band split at [lm] still has bits beyond its cache's last entry, for `quant_partition`. */
    fun cacheLastBits(band: Int, lm: Int): Int {
        val start = cacheStart(band, lm)
        val cache = CeltModeTables.CACHE_BITS
        return cache[start + cache[start]]
    }

    /** `init_caps`: the most eighth-bits each band can usefully take. */
    fun caps(cap: IntArray, lm: Int, channels: Int) {
        for (i in 0 until NB_E_BANDS) {
            val n = (eBands[i + 1] - eBands[i]) shl lm
            cap[i] = (CeltModeTables.CACHE_CAPS[NB_E_BANDS * (2 * lm + channels - 1) + i] + 64) * channels * n shr 2
        }
    }

    /**
     * What [compute] returns beside the pulse and fine-energy arrays it fills.
     * When encoding, [intensity] and [dualStereo] go in holding the
     * encoder's wishes, and come out holding what was coded.
     */
    class Result {
        var codedBands = 0
        var intensity = 0
        var dualStereo = false
        var balance = 0
    }

    /**
     * `clt_compute_allocation`. Exactly one of [range] and [encoder] is
     * given: the decoder reads the skip, intensity and dual stereo decisions,
     * and the encoder makes and writes them, skipping bands from the top down
     * to what [signalBandwidth] and the previous frame's coded band count
     * [prev] suggest.
     */
    fun compute(
        start: Int,
        end: Int,
        offsets: IntArray,
        cap: IntArray,
        allocTrim: Int,
        totalIn: Int,
        pulses: IntArray,
        ebits: IntArray,
        finePriority: IntArray,
        channels: Int,
        lm: Int,
        range: RangeDecoder?,
        result: Result,
        encoder: RangeEncoder? = null,
        prev: Int = 0,
        signalBandwidth: Int = 0,
    ) {
        var total = maxOf(totalIn, 0)
        val len = NB_E_BANDS
        var skipStart = start
        val skipRsv = if (total >= 1 shl BITRES) 1 shl BITRES else 0
        total -= skipRsv
        var intensityRsv = 0
        var dualStereoRsv = 0
        if (channels == 2) {
            intensityRsv = LOG2_FRAC_TABLE[end - start]
            if (intensityRsv > total) {
                intensityRsv = 0
            } else {
                total -= intensityRsv
                dualStereoRsv = if (total >= 1 shl BITRES) 1 shl BITRES else 0
                total -= dualStereoRsv
            }
        }
        val bits1 = IntArray(len)
        val bits2 = IntArray(len)
        val thresh = IntArray(len)
        val trimOffset = IntArray(len)
        for (j in start until end) {
            val width = eBands[j + 1] - eBands[j]
            thresh[j] = maxOf(channels shl BITRES, (3 * width shl lm shl BITRES) shr 4)
            trimOffset[j] = channels * width * (allocTrim - 5 - lm) * (end - j - 1) * (1 shl (lm + BITRES)) shr 6
            if (width shl lm == 1) trimOffset[j] -= channels shl BITRES
        }
        val alloc = CeltModeTables.BAND_ALLOCATION
        var lo = 1
        var hi = NB_ALLOC_VECTORS - 1
        do {
            var done = false
            var psum = 0
            val mid = (lo + hi) shr 1
            for (j in end - 1 downTo start) {
                val n = eBands[j + 1] - eBands[j]
                var bitsj = channels * n * alloc[mid * len + j] shl lm shr 2
                if (bitsj > 0) bitsj = maxOf(0, bitsj + trimOffset[j])
                bitsj += offsets[j]
                if (bitsj >= thresh[j] || done) {
                    done = true
                    psum += minOf(bitsj, cap[j])
                } else if (bitsj >= channels shl BITRES) {
                    psum += channels shl BITRES
                }
            }
            if (psum > total) hi = mid - 1 else lo = mid + 1
        } while (lo <= hi)
        hi = lo--
        for (j in start until end) {
            val n = eBands[j + 1] - eBands[j]
            var bits1j = channels * n * alloc[lo * len + j] shl lm shr 2
            var bits2j = if (hi >= NB_ALLOC_VECTORS) cap[j] else channels * n * alloc[hi * len + j] shl lm shr 2
            if (bits1j > 0) bits1j = maxOf(0, bits1j + trimOffset[j])
            if (bits2j > 0) bits2j = maxOf(0, bits2j + trimOffset[j])
            if (lo > 0) bits1j += offsets[j]
            bits2j += offsets[j]
            if (offsets[j] > 0) skipStart = j
            bits2j = maxOf(0, bits2j - bits1j)
            bits1[j] = bits1j
            bits2[j] = bits2j
        }
        interpolate(
            start, end, skipStart, bits1, bits2, thresh, cap, total, skipRsv,
            intensityRsv, dualStereoRsv, pulses, ebits, finePriority, channels, lm, range, result,
            encoder, prev, signalBandwidth,
        )
    }

    /** `interp_bits2pulses`. */
    private fun interpolate(
        start: Int,
        end: Int,
        skipStart: Int,
        bits1: IntArray,
        bits2: IntArray,
        thresh: IntArray,
        cap: IntArray,
        totalIn: Int,
        skipRsv: Int,
        intensityRsvIn: Int,
        dualStereoRsvIn: Int,
        bits: IntArray,
        ebits: IntArray,
        finePriority: IntArray,
        channels: Int,
        lm: Int,
        range: RangeDecoder?,
        result: Result,
        encoder: RangeEncoder?,
        prev: Int,
        signalBandwidth: Int,
    ) {
        var total = totalIn
        var intensityRsv = intensityRsvIn
        var dualStereoRsv = dualStereoRsvIn
        val allocFloor = channels shl BITRES
        val stereo = if (channels > 1) 1 else 0
        val logM = lm shl BITRES
        var lo = 0
        var hi = 1 shl ALLOC_STEPS
        var psum: Int
        var done: Boolean
        repeat(ALLOC_STEPS) {
            val mid = (lo + hi) shr 1
            psum = 0
            done = false
            for (j in end - 1 downTo start) {
                val tmp = bits1[j] + (mid * bits2[j] shr ALLOC_STEPS)
                if (tmp >= thresh[j] || done) {
                    done = true
                    psum += minOf(tmp, cap[j])
                } else if (tmp >= allocFloor) {
                    psum += allocFloor
                }
            }
            if (psum > total) hi = mid else lo = mid
        }
        psum = 0
        done = false
        for (j in end - 1 downTo start) {
            var tmp = bits1[j] + (lo * bits2[j] shr ALLOC_STEPS)
            if (tmp < thresh[j] && !done) {
                tmp = if (tmp >= allocFloor) allocFloor else 0
            } else {
                done = true
            }
            tmp = minOf(tmp, cap[j])
            bits[j] = tmp
            psum += tmp
        }
        var codedBands = end
        while (true) {
            val j = codedBands - 1
            if (j <= skipStart) {
                total += skipRsv
                break
            }
            var left = total - psum
            val percoeff = CeltMath.udiv(left, eBands[codedBands] - eBands[start])
            left -= (eBands[codedBands] - eBands[start]) * percoeff
            val rem = maxOf(left - (eBands[j] - eBands[start]), 0)
            val bandWidth = eBands[codedBands] - eBands[j]
            var bandBits = bits[j] + percoeff * bandWidth + rem
            if (bandBits >= maxOf(thresh[j], allocFloor + (1 shl BITRES))) {
                if (encoder != null) {
                    // The one choice here the bitstream does not dictate:
                    // which bands to skip. Some hysteresis keeps bands from
                    // flickering in and out, without folding too low.
                    val depthThreshold = if (codedBands > 17) (if (j < prev) 7 else 9) else 0
                    if (codedBands <= start + 2 ||
                        (bandBits > (depthThreshold * bandWidth shl lm shl BITRES) shr 4 && j <= signalBandwidth)
                    ) {
                        encoder.encodeBitLogp(true, 1)
                        break
                    }
                    encoder.encodeBitLogp(false, 1)
                } else if (range!!.decodeBitLogp(1)) {
                    break
                }
                psum += 1 shl BITRES
                bandBits -= 1 shl BITRES
            }
            psum -= bits[j] + intensityRsv
            if (intensityRsv > 0) intensityRsv = LOG2_FRAC_TABLE[j - start]
            psum += intensityRsv
            if (bandBits >= allocFloor) {
                psum += allocFloor
                bits[j] = allocFloor
            } else {
                bits[j] = 0
            }
            codedBands--
        }
        result.intensity = when {
            intensityRsv <= 0 -> 0
            encoder != null -> minOf(result.intensity, codedBands).also {
                encoder.encodeUniform(it - start, codedBands + 1 - start)
            }
            else -> start + range!!.decodeUniform(codedBands + 1 - start)
        }
        if (result.intensity <= start) {
            total += dualStereoRsv
            dualStereoRsv = 0
        }
        result.dualStereo = when {
            dualStereoRsv <= 0 -> false
            encoder != null -> result.dualStereo.also { encoder.encodeBitLogp(it, 1) }
            else -> range!!.decodeBitLogp(1)
        }
        var left = total - psum
        val percoeff = CeltMath.udiv(left, eBands[codedBands] - eBands[start])
        left -= (eBands[codedBands] - eBands[start]) * percoeff
        for (j in start until codedBands) bits[j] += percoeff * (eBands[j + 1] - eBands[j])
        for (j in start until codedBands) {
            val tmp = minOf(left, eBands[j + 1] - eBands[j])
            bits[j] += tmp
            left -= tmp
        }
        var balance = 0
        var j = start
        while (j < codedBands) {
            val n0 = eBands[j + 1] - eBands[j]
            val n = n0 shl lm
            val bit = bits[j] + balance
            var excess: Int
            if (n > 1) {
                excess = maxOf(bit - cap[j], 0)
                bits[j] = bit - excess
                val den = channels * n +
                    if (channels == 2 && n > 2 && !result.dualStereo && j < result.intensity) 1 else 0
                val nClogN = den * (CeltModeTables.LOG_N[j] + logM)
                var offset = (nClogN shr 1) - den * FINE_OFFSET
                if (n == 2) offset += den shl BITRES shr 2
                if (bits[j] + offset < den * 2 shl BITRES) {
                    offset += nClogN shr 2
                } else if (bits[j] + offset < den * 3 shl BITRES) {
                    offset += nClogN shr 3
                }
                ebits[j] = maxOf(0, bits[j] + offset + (den shl (BITRES - 1)))
                ebits[j] = CeltMath.udiv(ebits[j], den) shr BITRES
                if (channels * ebits[j] > (bits[j] shr BITRES)) ebits[j] = bits[j] shr stereo shr BITRES
                ebits[j] = minOf(ebits[j], MAX_FINE_BITS)
                finePriority[j] = if (ebits[j] * (den shl BITRES) >= bits[j] + offset) 1 else 0
                bits[j] -= channels * ebits[j] shl BITRES
            } else {
                excess = maxOf(0, bit - (channels shl BITRES))
                bits[j] = bit - excess
                ebits[j] = 0
                finePriority[j] = 1
            }
            if (excess > 0) {
                val extraFine = minOf(excess shr (stereo + BITRES), MAX_FINE_BITS - ebits[j])
                ebits[j] += extraFine
                val extraBits = extraFine * channels shl BITRES
                finePriority[j] = if (extraBits >= excess - balance) 1 else 0
                excess -= extraBits
            }
            balance = excess
            j++
        }
        result.balance = balance
        while (j < end) {
            ebits[j] = bits[j] shr stereo shr BITRES
            bits[j] = 0
            finePriority[j] = if (ebits[j] < 1) 1 else 0
            j++
        }
        result.codedBands = codedBands
    }
}

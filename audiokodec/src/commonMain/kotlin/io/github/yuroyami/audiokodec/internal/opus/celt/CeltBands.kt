package io.github.yuroyami.audiokodec.internal.opus.celt

import io.github.yuroyami.audiokodec.internal.opus.RangeDecoder

/**
 * Decodes the shapes of CELT's bands.
 *
 * Each band is a unit-energy vector the pyramid quantiser codes, but a band
 * with more bits than one codebook can use is split in half, recursively, with
 * an angle saying how the energy divides between the halves; stereo pairs are
 * split the same way into mid and side. A band given no bits at all is filled
 * from the decoded bands below it, folded up in frequency, which keeps the
 * texture of a low-rate signal instead of leaving holes in it.
 *
 * This is the decoder side of libopus `bands.c`, translated from
 * `quant_all_bands` and the functions it calls. The C passes raw pointers
 * that alias each other freely: the folding history, a scratch region carved
 * out of the coefficient buffer itself, and the two channels' halves of one
 * array. Here each pointer is an array and an offset, which keeps exactly the
 * same sharing.
 */
internal class CeltBands(private val range: RangeDecoder) {

    private val eBands = CeltModeTables.E_BANDS

    // The band context, `struct band_ctx`, for the band being decoded.
    private var band = 0
    private var intensity = 0
    private var spread = 0
    private var tfChange = 0
    private var remainingBits = 0
    var seed: Int = 0
    private var disableInversion = false
    private var avoidSplitNoise = false

    // What `compute_theta` returns, `struct split_ctx`, and the two values
    // it updates in its caller.
    private var thetaInv = false
    private var thetaImid = 0
    private var thetaIside = 0
    private var thetaDelta = 0
    private var thetaItheta = 0
    private var thetaQalloc = 0
    private var thetaBits = 0
    private var thetaFill = 0

    /**
     * `quant_all_bands` for decoding. [x] holds both channels, the second at
     * [n] when there are two; [collapseMasks] receives one mask per band and
     * channel.
     */
    fun decodeAll(
        start: Int,
        end: Int,
        x: FloatArray,
        channels: Int,
        n: Int,
        collapseMasks: IntArray,
        pulses: IntArray,
        shortBlocks: Int,
        spread: Int,
        dualStereoIn: Boolean,
        intensity: Int,
        tfRes: IntArray,
        totalBits: Int,
        balanceIn: Int,
        lm: Int,
        codedBands: Int,
        seedIn: Int,
        disableInversion: Boolean,
    ): Int {
        var dualStereo = dualStereoIn
        var balance = balanceIn
        val m = 1 shl lm
        val blocks = if (shortBlocks != 0) m else 1
        val normOffset = m * eBands[start]
        val normSize = m * eBands[NB_E_BANDS - 1] - normOffset
        val norm = FloatArray(channels * normSize)
        val norm2Offset = normSize
        val yOffset = if (channels == 2) n else -1
        // The decoder borrows the last band's coefficients as scratch space,
        // since that band is decoded last.
        var scratchOffset = m * eBands[EFF_E_BANDS - 1]
        var scratchArray: FloatArray? = x
        var lowbandOffset = 0
        var updateLowband = true

        this.intensity = intensity
        this.spread = spread
        this.disableInversion = disableInversion
        seed = seedIn
        avoidSplitNoise = blocks > 1

        for (i in start until end) {
            band = i
            val last = i == end - 1
            var xOffset = m * eBands[i]
            var xArray = x
            var yArray: FloatArray? = if (channels == 2) x else null
            var yAt = if (channels == 2) yOffset + m * eBands[i] else 0
            val bandN = m * eBands[i + 1] - m * eBands[i]
            val tell = range.tellFraction()
            if (i != start) balance -= tell
            remainingBits = totalBits - tell - 1
            val b = if (i <= codedBands - 1) {
                val currBalance = balance / minOf(3, codedBands - i)
                maxOf(0, minOf(16383, minOf(remainingBits + 1, pulses[i] + currBalance)))
            } else {
                0
            }
            if ((m * eBands[i] - bandN >= m * eBands[start] || i == start + 1) &&
                (updateLowband || lowbandOffset == 0)
            ) {
                lowbandOffset = i
            }
            if (i == start + 1) specialHybridFolding(norm, norm2Offset, start, m, dualStereo)
            tfChange = tfRes[i]
            if (i >= EFF_E_BANDS) {
                xArray = norm
                xOffset = 0
                if (yArray != null) {
                    yArray = norm
                    yAt = 0
                }
                scratchArray = null
            }
            if (last) scratchArray = null

            var effectiveLowband = -1
            var xCm: Int
            var yCm: Int
            if (lowbandOffset != 0 && (spread != CeltVq.SPREAD_AGGRESSIVE || blocks > 1 || tfChange < 0)) {
                effectiveLowband = maxOf(0, m * eBands[lowbandOffset] - normOffset - bandN)
                var foldStart = lowbandOffset
                while (m * eBands[--foldStart] > effectiveLowband + normOffset) Unit
                var foldEnd = lowbandOffset - 1
                while (++foldEnd < i && m * eBands[foldEnd] < effectiveLowband + normOffset + bandN) Unit
                xCm = 0
                yCm = 0
                var foldI = foldStart
                do {
                    xCm = xCm or collapseMasks[foldI * channels + 0]
                    yCm = yCm or collapseMasks[foldI * channels + channels - 1]
                } while (++foldI < foldEnd)
            } else {
                xCm = (1 shl blocks) - 1
                yCm = xCm
            }

            if (dualStereo && i == intensity) {
                dualStereo = false
                for (j in 0 until m * eBands[i] - normOffset) norm[j] = 0.5f * (norm[j] + norm[norm2Offset + j])
            }
            val outAt = m * eBands[i] - normOffset
            if (dualStereo) {
                xCm = decodeBand(
                    xArray, xOffset, bandN, b / 2, blocks,
                    if (effectiveLowband != -1) norm else null, effectiveLowband, lm,
                    if (last) null else norm, outAt, 1f, scratchArray, scratchOffset, xCm,
                )
                yCm = decodeBand(
                    yArray!!, yAt, bandN, b / 2, blocks,
                    if (effectiveLowband != -1) norm else null, norm2Offset + effectiveLowband, lm,
                    if (last) null else norm, norm2Offset + outAt, 1f, scratchArray, scratchOffset, yCm,
                )
            } else {
                xCm = if (yArray != null) {
                    decodeBandStereo(
                        xArray, xOffset, yArray, yAt, bandN, b, blocks,
                        if (effectiveLowband != -1) norm else null, effectiveLowband, lm,
                        if (last) null else norm, outAt, scratchArray, scratchOffset, xCm or yCm,
                    )
                } else {
                    decodeBand(
                        xArray, xOffset, bandN, b, blocks,
                        if (effectiveLowband != -1) norm else null, effectiveLowband, lm,
                        if (last) null else norm, outAt, 1f, scratchArray, scratchOffset, xCm or yCm,
                    )
                }
                yCm = xCm
            }
            collapseMasks[i * channels + 0] = xCm and 0xFF
            collapseMasks[i * channels + channels - 1] = yCm and 0xFF
            balance += pulses[i] + tell
            updateLowband = b > (bandN shl BITRES)
            avoidSplitNoise = false
        }
        return seed
    }

    private fun specialHybridFolding(norm: FloatArray, norm2Offset: Int, start: Int, m: Int, dualStereo: Boolean) {
        val n1 = m * (eBands[start + 1] - eBands[start])
        val n2 = m * (eBands[start + 2] - eBands[start + 1])
        norm.copyInto(norm, n1, 2 * n1 - n2, 2 * n1 - n2 + (n2 - n1))
        if (dualStereo) {
            norm.copyInto(norm, norm2Offset + n1, norm2Offset + 2 * n1 - n2, norm2Offset + 2 * n1 - n2 + (n2 - n1))
        }
    }

    /** `compute_theta`, decoder side. Leaves its results in the `theta` fields. */
    private fun computeTheta(n: Int, bIn: Int, blocks: Int, blocks0: Int, lm: Int, stereo: Boolean, fillIn: Int) {
        var b = bIn
        var fill = fillIn
        var itheta = 0
        var inv = false
        val pulseCap = CeltModeTables.LOG_N[band] + lm * (1 shl BITRES)
        val offset = (pulseCap shr 1) - if (stereo && n == 2) QTHETA_OFFSET_TWOPHASE else QTHETA_OFFSET
        var qn = computeQn(n, b, offset, pulseCap, stereo)
        if (stereo && band >= intensity) qn = 1
        val tell = range.tellFraction()
        if (qn != 1) {
            if (stereo && n > 2) {
                val p0 = 3
                val x0 = qn / 2
                val ft = p0 * (x0 + 1) + x0
                val fs = range.decode(ft)
                val x = if (fs < (x0 + 1) * p0) fs / p0 else x0 + 1 + (fs - (x0 + 1) * p0)
                range.update(
                    if (x <= x0) p0 * x else (x - 1 - x0) + (x0 + 1) * p0,
                    if (x <= x0) p0 * (x + 1) else (x - x0) + (x0 + 1) * p0,
                    ft,
                )
                itheta = x
            } else if (blocks0 > 1 || stereo) {
                itheta = range.decodeUniform(qn + 1)
            } else {
                val ft = ((qn shr 1) + 1) * ((qn shr 1) + 1)
                val fm = range.decode(ft)
                val fs: Int
                val fl: Int
                if (fm < ((qn shr 1) * ((qn shr 1) + 1) shr 1)) {
                    itheta = (CeltMath.isqrt32(8L * fm + 1) - 1) shr 1
                    fs = itheta + 1
                    fl = itheta * (itheta + 1) shr 1
                } else {
                    itheta = (2 * (qn + 1) - CeltMath.isqrt32(8L * (ft - fm - 1) + 1)) shr 1
                    fs = qn + 1 - itheta
                    fl = ft - ((qn + 1 - itheta) * (qn + 2 - itheta) shr 1)
                }
                range.update(fl, fl + fs, ft)
            }
            itheta = CeltMath.udiv(itheta * 16384, qn)
        } else if (stereo) {
            inv = if (b > 2 shl BITRES && remainingBits > 2 shl BITRES) range.decodeBitLogp(2) else false
            if (disableInversion) inv = false
            itheta = 0
        }
        val qalloc = range.tellFraction() - tell
        b -= qalloc
        val imid: Int
        val iside: Int
        val delta: Int
        if (itheta == 0) {
            imid = 32767
            iside = 0
            fill = fill and ((1 shl blocks) - 1)
            delta = -16384
        } else if (itheta == 16384) {
            imid = 0
            iside = 32767
            fill = fill and (((1 shl blocks) - 1) shl blocks)
            delta = 16384
        } else {
            imid = CeltMath.bitexactCos(itheta)
            iside = CeltMath.bitexactCos(16384 - itheta)
            delta = CeltMath.fracMul16((n - 1) shl 7, CeltMath.bitexactLog2tan(iside, imid))
        }
        thetaInv = inv
        thetaImid = imid
        thetaIside = iside
        thetaDelta = delta
        thetaItheta = itheta
        thetaQalloc = qalloc
        thetaBits = b
        thetaFill = fill
    }

    private fun computeQn(n: Int, b: Int, offset: Int, pulseCap: Int, stereo: Boolean): Int {
        var n2 = 2 * n - 1
        if (stereo && n == 2) n2--
        var qb = sudiv(b + n2 * offset, n2)
        qb = minOf(b - pulseCap - (4 shl BITRES), qb)
        qb = minOf(8 shl BITRES, qb)
        return if (qb < (1 shl BITRES shr 1)) {
            1
        } else {
            val qn = EXP2_TABLE8[qb and 0x7] shr (14 - (qb shr BITRES))
            (qn + 1) shr 1 shl 1
        }
    }

    /** `quant_band_n1`: a one-coefficient band is only a sign. */
    private fun decodeBandN1(
        x: FloatArray,
        xOffset: Int,
        y: FloatArray?,
        yOffset: Int,
        lowbandOut: FloatArray?,
        lowbandOutOffset: Int,
    ): Int {
        var array = x
        var offset = xOffset
        val passes = if (y != null) 2 else 1
        for (c in 0 until passes) {
            var sign = 0
            if (remainingBits >= 1 shl BITRES) {
                sign = range.decodeRawBits(1)
                remainingBits -= 1 shl BITRES
            }
            array[offset] = if (sign != 0) -NORM_SCALING else NORM_SCALING
            if (y != null) {
                array = y
                offset = yOffset
            }
        }
        if (lowbandOut != null) lowbandOut[lowbandOutOffset] = x[xOffset]
        return 1
    }

    /** `quant_partition`, decoder side. */
    private fun decodePartition(
        x: FloatArray,
        xOffset: Int,
        nIn: Int,
        bIn: Int,
        blocksIn: Int,
        lowband: FloatArray?,
        lowbandOffset: Int,
        lmIn: Int,
        gain: Float,
        fillIn: Int,
    ): Int {
        var n = nIn
        var b = bIn
        var blocks = blocksIn
        var lm = lmIn
        var fill = fillIn
        val blocks0 = blocks
        var cm = 0
        if (lm != -1 && b > CeltAllocation.cacheLastBits(band, lm) + 12 && n > 2) {
            n = n shr 1
            val yOffset = xOffset + n
            lm -= 1
            if (blocks == 1) fill = (fill and 1) or (fill shl 1)
            blocks = (blocks + 1) shr 1
            computeTheta(n, b, blocks, blocks0, lm, false, fill)
            b = thetaBits
            fill = thetaFill
            val itheta = thetaItheta
            var delta = thetaDelta
            val qalloc = thetaQalloc
            val mid = (1f / 32768) * thetaImid
            val side = (1f / 32768) * thetaIside
            if (blocks0 > 1 && (itheta and 0x3fff) != 0) {
                delta = if (itheta > 8192) {
                    delta - (delta shr (4 - lm))
                } else {
                    minOf(0, delta + (n shl BITRES shr (5 - lm)))
                }
            }
            var mbits = maxOf(0, minOf(b, (b - delta) / 2))
            var sbits = b - mbits
            remainingBits -= qalloc
            val nextLowband2 = if (lowband != null) lowbandOffset + n else 0
            var rebalance = remainingBits
            if (mbits >= sbits) {
                cm = decodePartition(x, xOffset, n, mbits, blocks, lowband, lowbandOffset, lm, gain * mid, fill)
                rebalance = mbits - (rebalance - remainingBits)
                if (rebalance > 3 shl BITRES && itheta != 0) sbits += rebalance - (3 shl BITRES)
                cm = cm or (
                    decodePartition(x, yOffset, n, sbits, blocks, lowband, nextLowband2, lm, gain * side, fill shr blocks)
                        shl (blocks0 shr 1)
                    )
            } else {
                cm = decodePartition(x, yOffset, n, sbits, blocks, lowband, nextLowband2, lm, gain * side, fill shr blocks) shl
                    (blocks0 shr 1)
                rebalance = sbits - (rebalance - remainingBits)
                if (rebalance > 3 shl BITRES && itheta != 16384) mbits += rebalance - (3 shl BITRES)
                cm = cm or decodePartition(x, xOffset, n, mbits, blocks, lowband, lowbandOffset, lm, gain * mid, fill)
            }
        } else {
            var q = CeltAllocation.bitsToPulses(band, lm, b)
            var currBits = CeltAllocation.pulsesToBits(band, lm, q)
            remainingBits -= currBits
            while (remainingBits < 0 && q > 0) {
                remainingBits += currBits
                q--
                currBits = CeltAllocation.pulsesToBits(band, lm, q)
                remainingBits -= currBits
            }
            if (q != 0) {
                val k = CeltAllocation.pulses(q)
                cm = CeltVq.unquantise(x, xOffset, n, k, spread, blocks, range, gain)
            } else {
                val cmMask = (1 shl blocks) - 1
                fill = fill and cmMask
                if (fill == 0) {
                    x.fill(0f, xOffset, xOffset + n)
                } else {
                    if (lowband == null) {
                        for (j in 0 until n) {
                            seed = CeltMath.lcgRand(seed)
                            x[xOffset + j] = (seed shr 20).toFloat()
                        }
                        cm = cmMask
                    } else {
                        for (j in 0 until n) {
                            seed = CeltMath.lcgRand(seed)
                            val tmp = if ((seed and 0x8000) != 0) 1.0f / 256 else -(1.0f / 256)
                            x[xOffset + j] = lowband[lowbandOffset + j] + tmp
                        }
                        cm = fill
                    }
                    CeltVq.renormalise(x, xOffset, n, gain)
                }
            }
        }
        return cm
    }

    /** `quant_band`, decoder side: one channel's band, with its time-frequency changes undone. */
    private fun decodeBand(
        x: FloatArray,
        xOffset: Int,
        n: Int,
        b: Int,
        blocksIn: Int,
        lowbandIn: FloatArray?,
        lowbandOffsetIn: Int,
        lm: Int,
        lowbandOut: FloatArray?,
        lowbandOutOffset: Int,
        gain: Float,
        scratch: FloatArray?,
        scratchOffset: Int,
        fillIn: Int,
    ): Int {
        val n0 = n
        var nB = n
        var blocks = blocksIn
        val longBlocks = blocks == 1
        var fill = fillIn
        var lowband = lowbandIn
        var lowbandOffset = lowbandOffsetIn
        var timeDivide = 0
        var recombine = 0
        var tfChange = this.tfChange
        nB = CeltMath.udiv(nB, blocks)
        if (n == 1) return decodeBandN1(x, xOffset, null, 0, lowbandOut, lowbandOutOffset)
        if (tfChange > 0) recombine = tfChange
        if (scratch != null && lowband != null && (recombine != 0 || ((nB and 1) == 0 && tfChange < 0) || blocks > 1)) {
            lowband.copyInto(scratch, scratchOffset, lowbandOffset, lowbandOffset + n)
            lowband = scratch
            lowbandOffset = scratchOffset
        }
        for (k in 0 until recombine) {
            if (lowband != null) haar1(lowband, lowbandOffset, n shr k, 1 shl k)
            fill = BIT_INTERLEAVE[fill and 0xF] or (BIT_INTERLEAVE[fill shr 4] shl 2)
        }
        blocks = blocks shr recombine
        nB = nB shl recombine
        while ((nB and 1) == 0 && tfChange < 0) {
            if (lowband != null) haar1(lowband, lowbandOffset, nB, blocks)
            fill = fill or (fill shl blocks)
            blocks = blocks shl 1
            nB = nB shr 1
            timeDivide++
            tfChange++
        }
        val blocks0 = blocks
        val nB0 = nB
        if (blocks0 > 1 && lowband != null) {
            deinterleaveHadamard(lowband, lowbandOffset, nB shr recombine, blocks0 shl recombine, longBlocks)
        }
        var cm = decodePartition(x, xOffset, n, b, blocks, lowband, lowbandOffset, lm, gain, fill)
        if (blocks0 > 1) interleaveHadamard(x, xOffset, nB shr recombine, blocks0 shl recombine, longBlocks)
        nB = nB0
        blocks = blocks0
        for (k in 0 until timeDivide) {
            blocks = blocks shr 1
            nB = nB shl 1
            cm = cm or (cm shr blocks)
            haar1(x, xOffset, nB, blocks)
        }
        for (k in 0 until recombine) {
            cm = BIT_DEINTERLEAVE[cm]
            haar1(x, xOffset, n0 shr k, 1 shl k)
        }
        blocks = blocks shl recombine
        if (lowbandOut != null) {
            // The reference writes this as sqrt(N0 << 22), a fixed-point
            // shift that is the identity in the float build.
            val scale = CeltMath.sqrt(n0.toFloat())
            for (j in 0 until n0) lowbandOut[lowbandOutOffset + j] = scale * x[xOffset + j]
        }
        return cm and ((1 shl blocks) - 1)
    }

    /** `quant_band_stereo`, decoder side. */
    private fun decodeBandStereo(
        x: FloatArray,
        xOffset: Int,
        y: FloatArray,
        yOffset: Int,
        n: Int,
        bIn: Int,
        blocks: Int,
        lowband: FloatArray?,
        lowbandOffset: Int,
        lm: Int,
        lowbandOut: FloatArray?,
        lowbandOutOffset: Int,
        scratch: FloatArray?,
        scratchOffset: Int,
        fillIn: Int,
    ): Int {
        var cm: Int
        if (n == 1) return decodeBandN1(x, xOffset, y, yOffset, lowbandOut, lowbandOutOffset)
        val origFill = fillIn
        computeTheta(n, bIn, blocks, blocks, lm, true, fillIn)
        val b = thetaBits
        val fill = thetaFill
        val inv = thetaInv
        val delta = thetaDelta
        val itheta = thetaItheta
        val qalloc = thetaQalloc
        val mid = (1f / 32768) * thetaImid
        val side = (1f / 32768) * thetaIside
        if (n == 2) {
            var mbits = b
            var sbits = 0
            if (itheta != 0 && itheta != 16384) sbits = 1 shl BITRES
            mbits -= sbits
            val c = itheta > 8192
            remainingBits -= qalloc + sbits
            val x2 = if (c) y else x
            val x2Offset = if (c) yOffset else xOffset
            val y2 = if (c) x else y
            val y2Offset = if (c) xOffset else yOffset
            var sign = 0
            if (sbits != 0) sign = range.decodeRawBits(1)
            sign = 1 - 2 * sign
            cm = decodeBand(
                x2, x2Offset, n, mbits, blocks, lowband, lowbandOffset, lm,
                lowbandOut, lowbandOutOffset, 1f, scratch, scratchOffset, origFill,
            )
            y2[y2Offset] = -sign * x2[x2Offset + 1]
            y2[y2Offset + 1] = sign * x2[x2Offset]
            x[xOffset] = mid * x[xOffset]
            x[xOffset + 1] = mid * x[xOffset + 1]
            y[yOffset] = side * y[yOffset]
            y[yOffset + 1] = side * y[yOffset + 1]
            var tmp = x[xOffset]
            x[xOffset] = tmp - y[yOffset]
            y[yOffset] = tmp + y[yOffset]
            tmp = x[xOffset + 1]
            x[xOffset + 1] = tmp - y[yOffset + 1]
            y[yOffset + 1] = tmp + y[yOffset + 1]
        } else {
            var mbits = maxOf(0, minOf(b, (b - delta) / 2))
            var sbits = b - mbits
            remainingBits -= qalloc
            var rebalance = remainingBits
            if (mbits >= sbits) {
                cm = decodeBand(
                    x, xOffset, n, mbits, blocks, lowband, lowbandOffset, lm,
                    lowbandOut, lowbandOutOffset, 1f, scratch, scratchOffset, fill,
                )
                rebalance = mbits - (rebalance - remainingBits)
                if (rebalance > 3 shl BITRES && itheta != 0) sbits += rebalance - (3 shl BITRES)
                cm = cm or decodeBand(y, yOffset, n, sbits, blocks, null, 0, lm, null, 0, side, null, 0, fill shr blocks)
            } else {
                cm = decodeBand(y, yOffset, n, sbits, blocks, null, 0, lm, null, 0, side, null, 0, fill shr blocks)
                rebalance = sbits - (rebalance - remainingBits)
                if (rebalance > 3 shl BITRES && itheta != 16384) mbits += rebalance - (3 shl BITRES)
                cm = cm or decodeBand(
                    x, xOffset, n, mbits, blocks, lowband, lowbandOffset, lm,
                    lowbandOut, lowbandOutOffset, 1f, scratch, scratchOffset, fill,
                )
            }
        }
        if (n != 2) stereoMerge(x, xOffset, y, yOffset, mid, n)
        if (inv) for (j in 0 until n) y[yOffset + j] = -y[yOffset + j]
        return cm
    }

    /** `stereo_merge`: turns a decoded mid and side back into left and right. */
    private fun stereoMerge(x: FloatArray, xOffset: Int, y: FloatArray, yOffset: Int, mid: Float, n: Int) {
        var xp = 0f
        var side = 0f
        for (i in 0 until n) {
            xp += y[yOffset + i] * x[xOffset + i]
            side += y[yOffset + i] * y[yOffset + i]
        }
        xp *= mid
        val mid2 = mid
        val el = mid2 * mid2 + side - 2 * xp
        val er = mid2 * mid2 + side + 2 * xp
        if (er < 6e-4f || el < 6e-4f) {
            x.copyInto(y, yOffset, xOffset, xOffset + n)
            return
        }
        val lgain = CeltMath.rsqrt(el)
        val rgain = CeltMath.rsqrt(er)
        for (j in 0 until n) {
            val l = mid * x[xOffset + j]
            val r = y[yOffset + j]
            x[xOffset + j] = lgain * (l - r)
            y[yOffset + j] = rgain * (l + r)
        }
    }

    companion object {
        const val BITRES: Int = RangeDecoder.BITRES
        private const val NB_E_BANDS = 21
        private const val EFF_E_BANDS = 21
        private const val QTHETA_OFFSET = 4
        private const val QTHETA_OFFSET_TWOPHASE = 16
        private const val NORM_SCALING = 1f
        private val EXP2_TABLE8 = intArrayOf(16384, 17866, 19483, 21247, 23170, 25267, 27554, 30048)
        private val BIT_INTERLEAVE = intArrayOf(0, 1, 1, 1, 2, 3, 3, 3, 2, 3, 3, 3, 2, 3, 3, 3)
        private val BIT_DEINTERLEAVE = intArrayOf(
            0x00, 0x03, 0x0C, 0x0F, 0x30, 0x33, 0x3C, 0x3F, 0xC0, 0xC3, 0xCC, 0xCF, 0xF0, 0xF3, 0xFC, 0xFF,
        )
        private val ORDERY = intArrayOf(
            1, 0,
            3, 0, 2, 1,
            7, 0, 4, 3, 6, 1, 5, 2,
            15, 0, 8, 7, 12, 3, 11, 4, 14, 1, 9, 6, 13, 2, 10, 5,
        )

        /** `celt_sudiv`: signed division truncating towards zero. */
        private fun sudiv(n: Int, d: Int): Int = n / d

        /** `haar1`: a Haar transform across pairs [stride] apart, in place. */
        fun haar1(x: FloatArray, offset: Int, n0In: Int, stride: Int) {
            val n0 = n0In shr 1
            for (i in 0 until stride) {
                for (j in 0 until n0) {
                    val a = offset + stride * 2 * j + i
                    val b = offset + stride * (2 * j + 1) + i
                    val tmp1 = 0.70710678f * x[a]
                    val tmp2 = 0.70710678f * x[b]
                    x[a] = tmp1 + tmp2
                    x[b] = tmp1 - tmp2
                }
            }
        }

        private fun deinterleaveHadamard(x: FloatArray, offset: Int, n0: Int, stride: Int, hadamard: Boolean) {
            val n = n0 * stride
            val tmp = FloatArray(n)
            if (hadamard) {
                val ordery = stride - 2
                for (i in 0 until stride) for (j in 0 until n0) tmp[ORDERY[ordery + i] * n0 + j] = x[offset + j * stride + i]
            } else {
                for (i in 0 until stride) for (j in 0 until n0) tmp[i * n0 + j] = x[offset + j * stride + i]
            }
            tmp.copyInto(x, offset)
        }

        private fun interleaveHadamard(x: FloatArray, offset: Int, n0: Int, stride: Int, hadamard: Boolean) {
            val n = n0 * stride
            val tmp = FloatArray(n)
            if (hadamard) {
                val ordery = stride - 2
                for (i in 0 until stride) for (j in 0 until n0) tmp[j * stride + i] = x[offset + ORDERY[ordery + i] * n0 + j]
            } else {
                for (i in 0 until stride) for (j in 0 until n0) tmp[j * stride + i] = x[offset + i * n0 + j]
            }
            tmp.copyInto(x, offset)
        }

        /**
         * `denormalise_bands`: scales each unit-energy band to its decoded
         * energy, writing the spectrum into [freq] at [freqOffset].
         */
        fun denormalise(
            x: FloatArray,
            xOffset: Int,
            freq: FloatArray,
            freqOffset: Int,
            bandLogE: FloatArray,
            bandLogEOffset: Int,
            startIn: Int,
            endIn: Int,
            m: Int,
            downsample: Int,
            silence: Boolean,
        ) {
            val eBands = CeltModeTables.E_BANDS
            var start = startIn
            var end = endIn
            val n = m * SHORT_MDCT_SIZE
            var bound = m * eBands[end]
            if (downsample != 1) bound = minOf(bound, n / downsample)
            if (silence) {
                bound = 0
                start = 0
                end = 0
            }
            var f = freqOffset
            var xp = xOffset + m * eBands[start]
            for (i in 0 until m * eBands[start]) freq[f++] = 0f
            for (i in start until end) {
                var j = m * eBands[i]
                val bandEnd = m * eBands[i + 1]
                val lg = bandLogE[bandLogEOffset + i] + CeltEnergy.E_MEANS[i]
                val g = CeltMath.exp2(minOf(32f, lg))
                do {
                    freq[f++] = x[xp++] * g
                } while (++j < bandEnd)
            }
            freq.fill(0f, freqOffset + bound, freqOffset + n)
        }

        /**
         * `anti_collapse`: refills short blocks a transient frame left empty
         * with noise at a level derived from the band's history, so a sharp
         * attack does not leave silent gaps in the blocks around it.
         */
        fun antiCollapse(
            x: FloatArray,
            collapseMasks: IntArray,
            lm: Int,
            channels: Int,
            size: Int,
            start: Int,
            end: Int,
            logE: FloatArray,
            prev1LogE: FloatArray,
            prev2LogE: FloatArray,
            pulses: IntArray,
            seedIn: Int,
        ) {
            val eBands = CeltModeTables.E_BANDS
            var seed = seedIn
            for (i in start until end) {
                val n0 = eBands[i + 1] - eBands[i]
                val depth = CeltMath.udiv(1 + pulses[i], eBands[i + 1] - eBands[i]) shr lm
                val thresh = 0.5f * CeltMath.exp2(-0.125f * depth)
                val sqrt1 = CeltMath.rsqrt((n0 shl lm).toFloat())
                for (c in 0 until channels) {
                    var prev1 = prev1LogE[c * NB_E_BANDS + i]
                    var prev2 = prev2LogE[c * NB_E_BANDS + i]
                    if (channels == 1) {
                        prev1 = maxOf(prev1, prev1LogE[NB_E_BANDS + i])
                        prev2 = maxOf(prev2, prev2LogE[NB_E_BANDS + i])
                    }
                    var ediff = logE[c * NB_E_BANDS + i] - minOf(prev1, prev2)
                    ediff = maxOf(0f, ediff)
                    var r = 2f * CeltMath.exp2(-ediff)
                    if (lm == 3) r *= 1.41421356f
                    r = minOf(thresh, r)
                    r *= sqrt1
                    val xBase = c * size + (eBands[i] shl lm)
                    var renormalise = false
                    for (k in 0 until (1 shl lm)) {
                        if ((collapseMasks[i * channels + c] and (1 shl k)) == 0) {
                            for (j in 0 until n0) {
                                seed = CeltMath.lcgRand(seed)
                                x[xBase + (j shl lm) + k] = if ((seed and 0x8000) != 0) r else -r
                            }
                            renormalise = true
                        }
                    }
                    if (renormalise) CeltVq.renormalise(x, xBase, n0 shl lm, 1f)
                }
            }
        }

        const val SHORT_MDCT_SIZE: Int = 120
    }
}

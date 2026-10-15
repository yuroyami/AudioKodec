package io.github.yuroyami.audiokodec.internal.opus.celt

import io.github.yuroyami.audiokodec.internal.opus.RangeEncoder

/**
 * Codes the shapes of CELT's bands: the encoder's half of [CeltBands].
 *
 * Every split, angle and pulse vector is written here in the order
 * [CeltBands] reads it back, from the same allocation, so the two must make
 * the same decisions about when to split a band, how finely to code its
 * angle and how many pulses each part gets. The encoder's freedom is in the
 * values themselves: the angle between the halves of a band, or between the
 * mid and side of a stereo pair, and the pulse vector closest to the band's
 * shape.
 *
 * In stereo the reference tries each quantised stereo angle rounded both
 * down and up and keeps whichever reconstructs the band better, which needs
 * the decoder's reconstruction ("resynthesis") inside the encoder, including
 * the folding of lower bands into empty ones. That is done here exactly as
 * libopus does it at complexity 8 and above; in mono, and when the two
 * channels are coded independently, the reconstruction is skipped, as there.
 *
 * The encoder side of libopus `bands.c`, translated from `quant_all_bands`
 * and the functions it calls with `encode` set. Pointers that alias in the C
 * are arrays and offsets here, as in [CeltBands].
 */
internal class CeltBandEncoder(private val range: RangeEncoder) {

    private val eBands = CeltModeTables.E_BANDS

    // The band context, `struct band_ctx`, for the band being coded.
    private var band = 0
    private var intensity = 0
    private var spread = 0
    private var tfChange = 0
    private var remainingBits = 0
    private var seed = 0
    private var thetaRound = 0
    private var disableInversion = false
    private var avoidSplitNoise = false
    private var resynth = false
    private var bandE: FloatArray = FloatArray(0)

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
     * `quant_all_bands` for encoding. [x] holds the normalised spectrum of
     * both channels, the second at [n] when there are two, and is replaced by
     * what the decoder will reconstruct where the encoder reconstructs.
     * [collapseMasks] receives one mask per band and channel. Returns the
     * noise seed after the frame.
     */
    fun encodeAll(
        start: Int,
        end: Int,
        x: FloatArray,
        channels: Int,
        n: Int,
        collapseMasks: IntArray,
        bandE: FloatArray,
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
        complexity: Int,
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
        val thetaRdo = channels == 2 && !dualStereo && complexity >= 8
        resynth = thetaRdo
        val resynthSize = m * (eBands[NB_E_BANDS] - eBands[NB_E_BANDS - 1])
        val ownScratch = if (resynth) FloatArray(resynthSize) else null
        val xSave = if (thetaRdo) FloatArray(resynthSize) else null
        val ySave = if (thetaRdo) FloatArray(resynthSize) else null
        val xSave2 = if (thetaRdo) FloatArray(resynthSize) else null
        val ySave2 = if (thetaRdo) FloatArray(resynthSize) else null
        val normSave2 = if (thetaRdo) FloatArray(resynthSize) else null
        val rangeSave = RangeEncoder.Snapshot()
        val rangeSave2 = RangeEncoder.Snapshot()
        var lowbandOffset = 0
        var updateLowband = true

        this.bandE = bandE
        this.intensity = intensity
        this.spread = spread
        this.disableInversion = disableInversion
        seed = seedIn
        thetaRound = 0
        avoidSplitNoise = blocks > 1

        for (i in start until end) {
            band = i
            val last = i == end - 1
            val xOffset = m * eBands[i]
            val yOffset = n + m * eBands[i]
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
            if (resynth && (m * eBands[i] - bandN >= m * eBands[start] || i == start + 1) &&
                (updateLowband || lowbandOffset == 0)
            ) {
                lowbandOffset = i
            }
            if (i == start + 1) specialHybridFolding(norm, norm2Offset, start, m, dualStereo)
            tfChange = tfRes[i]
            val scratch = if (last && !thetaRdo) null else ownScratch

            var effectiveLowband = -1
            var xCm: Int
            var yCm: Int
            if (lowbandOffset != 0 && (spread != CeltVq.SPREAD_AGGRESSIVE || blocks > 1 || tfChange < 0)) {
                effectiveLowband = maxOf(0, m * eBands[lowbandOffset] - normOffset - bandN)
                var foldStart = lowbandOffset
                do foldStart-- while (m * eBands[foldStart] > effectiveLowband + normOffset)
                var foldEnd = lowbandOffset
                while (foldEnd < i && m * eBands[foldEnd] < effectiveLowband + normOffset + bandN) foldEnd++
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
                if (resynth) {
                    for (j in 0 until m * eBands[i] - normOffset) norm[j] = 0.5f * (norm[j] + norm[norm2Offset + j])
                }
            }
            val lowband = if (effectiveLowband != -1) norm else null
            val outAt = m * eBands[i] - normOffset
            if (dualStereo) {
                xCm = encodeBand(
                    x, xOffset, bandN, b / 2, blocks, lowband, effectiveLowband, lm,
                    if (last) null else norm, outAt, 1f, scratch, xCm,
                )
                yCm = encodeBand(
                    x, yOffset, bandN, b / 2, blocks, lowband, norm2Offset + effectiveLowband, lm,
                    if (last) null else norm, norm2Offset + outAt, 1f, scratch, yCm,
                )
            } else {
                if (channels == 2) {
                    if (thetaRdo && i < intensity) {
                        // Code the stereo angle rounded down, then up, and
                        // keep whichever reconstructs the band better.
                        val w0: Float
                        val w1: Float
                        run {
                            val ex = bandE[i]
                            val ey = bandE[i + NB_E_BANDS]
                            val minE = if (ex < ey) ex else ey
                            w0 = ex + minE / 3
                            w1 = ey + minE / 3
                        }
                        val cm = xCm or yCm
                        range.saveTo(rangeSave)
                        val contextSave = saveContext()
                        x.copyInto(xSave!!, 0, xOffset, xOffset + bandN)
                        x.copyInto(ySave!!, 0, yOffset, yOffset + bandN)
                        thetaRound = -1
                        xCm = encodeBandStereo(
                            x, xOffset, yOffset, bandN, b, blocks, lowband, effectiveLowband, lm,
                            if (last) null else norm, outAt, scratch, cm,
                        )
                        val dist0 = w0 * CeltMath.innerProduct(xSave, 0, x, xOffset, bandN) +
                            w1 * CeltMath.innerProduct(ySave, 0, x, yOffset, bandN)

                        val cm2 = xCm
                        range.saveTo(rangeSave2)
                        val contextSave2 = saveContext()
                        x.copyInto(xSave2!!, 0, xOffset, xOffset + bandN)
                        x.copyInto(ySave2!!, 0, yOffset, yOffset + bandN)
                        if (!last) norm.copyInto(normSave2!!, 0, outAt, outAt + bandN)
                        val startBytes = rangeSave.rangeBytes
                        val endBytes = rangeSave.storageBytes
                        val bytesSave = range.buffer.copyOfRange(startBytes, endBytes)

                        range.restoreFrom(rangeSave)
                        restoreContext(contextSave)
                        xSave.copyInto(x, xOffset, 0, bandN)
                        ySave.copyInto(x, yOffset, 0, bandN)
                        if (i == start + 1) specialHybridFolding(norm, norm2Offset, start, m, dualStereo)
                        thetaRound = 1
                        xCm = encodeBandStereo(
                            x, xOffset, yOffset, bandN, b, blocks, lowband, effectiveLowband, lm,
                            if (last) null else norm, outAt, scratch, cm,
                        )
                        val dist1 = w0 * CeltMath.innerProduct(xSave, 0, x, xOffset, bandN) +
                            w1 * CeltMath.innerProduct(ySave, 0, x, yOffset, bandN)
                        if (dist0 >= dist1) {
                            xCm = cm2
                            range.restoreFrom(rangeSave2)
                            restoreContext(contextSave2)
                            xSave2.copyInto(x, xOffset, 0, bandN)
                            ySave2.copyInto(x, yOffset, 0, bandN)
                            if (!last) normSave2!!.copyInto(norm, outAt, 0, bandN)
                            bytesSave.copyInto(range.buffer, startBytes)
                        }
                    } else {
                        thetaRound = 0
                        xCm = encodeBandStereo(
                            x, xOffset, yOffset, bandN, b, blocks, lowband, effectiveLowband, lm,
                            if (last) null else norm, outAt, scratch, xCm or yCm,
                        )
                    }
                } else {
                    xCm = encodeBand(
                        x, xOffset, bandN, b, blocks, lowband, effectiveLowband, lm,
                        if (last) null else norm, outAt, 1f, scratch, xCm or yCm,
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

    /** The parts of the band context the stereo trial has to undo. */
    private fun saveContext(): IntArray = intArrayOf(
        band, intensity, spread, tfChange, remainingBits, seed, thetaRound,
        if (avoidSplitNoise) 1 else 0,
    )

    private fun restoreContext(saved: IntArray) {
        band = saved[0]
        intensity = saved[1]
        spread = saved[2]
        tfChange = saved[3]
        remainingBits = saved[4]
        seed = saved[5]
        thetaRound = saved[6]
        avoidSplitNoise = saved[7] != 0
    }

    private fun specialHybridFolding(norm: FloatArray, norm2Offset: Int, start: Int, m: Int, dualStereo: Boolean) {
        val n1 = m * (eBands[start + 1] - eBands[start])
        val n2 = m * (eBands[start + 2] - eBands[start + 1])
        norm.copyInto(norm, n1, 2 * n1 - n2, 2 * n1 - n2 + (n2 - n1))
        if (dualStereo) {
            norm.copyInto(norm, norm2Offset + n1, norm2Offset + 2 * n1 - n2, norm2Offset + 2 * n1 - n2 + (n2 - n1))
        }
    }

    /**
     * `compute_theta`, encoder side: measures and codes the angle between
     * the two halves at [xOffset] and [yOffset] of [x], or between the two
     * channels when [stereo], which it then mixes into mid and side. Leaves
     * its results in the `theta` fields.
     */
    private fun computeTheta(
        x: FloatArray,
        xOffset: Int,
        yOffset: Int,
        n: Int,
        bIn: Int,
        blocks: Int,
        blocks0: Int,
        lm: Int,
        stereo: Boolean,
        fillIn: Int,
    ) {
        var b = bIn
        var fill = fillIn
        var inv = false
        val pulseCap = CeltModeTables.LOG_N[band] + lm * (1 shl BITRES)
        val offset = (pulseCap shr 1) -
            if (stereo && n == 2) CeltBands.QTHETA_OFFSET_TWOPHASE else CeltBands.QTHETA_OFFSET
        var qn = CeltBands.computeQn(n, b, offset, pulseCap, stereo)
        if (stereo && band >= intensity) qn = 1
        var itheta = CeltVq.stereoItheta(x, xOffset, x, yOffset, stereo, n)
        val tell = range.tellFraction()
        if (qn != 1) {
            if (!stereo || thetaRound == 0) {
                itheta = (itheta * qn + 8192) shr 14
                if (!stereo && avoidSplitNoise && itheta > 0 && itheta < qn) {
                    // Make sure an angle that would starve one side of bits
                    // leaves that side silent rather than noisy.
                    val unquantised = CeltMath.udiv(itheta * 16384, qn)
                    val imid = CeltMath.bitexactCos(unquantised.toShort().toInt())
                    val iside = CeltMath.bitexactCos((16384 - unquantised).toShort().toInt())
                    val delta = CeltMath.fracMul16((n - 1) shl 7, CeltMath.bitexactLog2tan(iside, imid))
                    if (delta > b) {
                        itheta = qn
                    } else if (delta < -b) {
                        itheta = 0
                    }
                }
            } else {
                // Bias towards the two ends of the range.
                val bias = if (itheta > 8192) 32767 / qn else -32767 / qn
                val down = minOf(qn - 1, maxOf(0, (itheta * qn + bias) shr 14))
                itheta = if (thetaRound < 0) down else down + 1
            }
            if (stereo && n > 2) {
                val p0 = 3
                val x0 = qn / 2
                val ft = p0 * (x0 + 1) + x0
                range.encode(
                    if (itheta <= x0) p0 * itheta else (itheta - 1 - x0) + (x0 + 1) * p0,
                    if (itheta <= x0) p0 * (itheta + 1) else (itheta - x0) + (x0 + 1) * p0,
                    ft,
                )
            } else if (blocks0 > 1 || stereo) {
                range.encodeUniform(itheta, qn + 1)
            } else {
                val ft = ((qn shr 1) + 1) * ((qn shr 1) + 1)
                val fs = if (itheta <= (qn shr 1)) itheta + 1 else qn + 1 - itheta
                val fl = if (itheta <= (qn shr 1)) {
                    itheta * (itheta + 1) shr 1
                } else {
                    ft - ((qn + 1 - itheta) * (qn + 2 - itheta) shr 1)
                }
                range.encode(fl, fl + fs, ft)
            }
            itheta = CeltMath.udiv(itheta * 16384, qn)
            if (stereo) {
                if (itheta == 0) intensityStereo(x, xOffset, yOffset, n) else stereoSplit(x, xOffset, yOffset, n)
            }
        } else if (stereo) {
            inv = itheta > 8192 && !disableInversion
            if (inv) for (j in 0 until n) x[yOffset + j] = -x[yOffset + j]
            intensityStereo(x, xOffset, yOffset, n)
            if (b > 2 shl BITRES && remainingBits > 2 shl BITRES) {
                range.encodeBitLogp(inv, 2)
            } else {
                inv = false
            }
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

    /** `intensity_stereo`: replaces the left channel with a mix weighted by the bands' energies. */
    private fun intensityStereo(x: FloatArray, xOffset: Int, yOffset: Int, n: Int) {
        val left = bandE[band]
        val right = bandE[band + NB_E_BANDS]
        val norm = EPSILON + CeltMath.sqrt(EPSILON + left * left + right * right)
        val a1 = left / norm
        val a2 = right / norm
        for (j in 0 until n) {
            val l = x[xOffset + j]
            val r = x[yOffset + j]
            x[xOffset + j] = a1 * l + a2 * r
        }
    }

    /** `stereo_split`: left and right to mid and side. */
    private fun stereoSplit(x: FloatArray, xOffset: Int, yOffset: Int, n: Int) {
        for (j in 0 until n) {
            val l = 0.70710678f * x[xOffset + j]
            val r = 0.70710678f * x[yOffset + j]
            x[xOffset + j] = l + r
            x[yOffset + j] = r - l
        }
    }

    /** `quant_band_n1`: a one-coefficient band is only a sign. */
    private fun encodeBandN1(
        x: FloatArray,
        xOffset: Int,
        yOffset: Int,
        stereo: Boolean,
        lowbandOut: FloatArray?,
        lowbandOutOffset: Int,
    ): Int {
        var offset = xOffset
        val passes = if (stereo) 2 else 1
        for (c in 0 until passes) {
            var sign = 0
            if (remainingBits >= 1 shl BITRES) {
                sign = if (x[offset] < 0) 1 else 0
                range.encodeRawBits(sign, 1)
                remainingBits -= 1 shl BITRES
            }
            if (resynth) x[offset] = if (sign != 0) -NORM_SCALING else NORM_SCALING
            offset = yOffset
        }
        if (lowbandOut != null) lowbandOut[lowbandOutOffset] = x[xOffset]
        return 1
    }

    /** `quant_partition`, encoder side. */
    private fun encodePartition(
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
            computeTheta(x, xOffset, yOffset, n, b, blocks, blocks0, lm, false, fill)
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
                cm = encodePartition(x, xOffset, n, mbits, blocks, lowband, lowbandOffset, lm, gain * mid, fill)
                rebalance = mbits - (rebalance - remainingBits)
                if (rebalance > 3 shl BITRES && itheta != 0) sbits += rebalance - (3 shl BITRES)
                cm = cm or (
                    encodePartition(x, yOffset, n, sbits, blocks, lowband, nextLowband2, lm, gain * side, fill shr blocks)
                        shl (blocks0 shr 1)
                    )
            } else {
                cm = encodePartition(x, yOffset, n, sbits, blocks, lowband, nextLowband2, lm, gain * side, fill shr blocks) shl
                    (blocks0 shr 1)
                rebalance = sbits - (rebalance - remainingBits)
                if (rebalance > 3 shl BITRES && itheta != 16384) mbits += rebalance - (3 shl BITRES)
                cm = cm or encodePartition(x, xOffset, n, mbits, blocks, lowband, lowbandOffset, lm, gain * mid, fill)
            }
        } else {
            var q = CeltAllocation.bitsToPulses(band, lm, b)
            var currBits = CeltAllocation.pulsesToBits(band, lm, q)
            remainingBits -= currBits
            // Never spend more than is left.
            while (remainingBits < 0 && q > 0) {
                remainingBits += currBits
                q--
                currBits = CeltAllocation.pulsesToBits(band, lm, q)
                remainingBits -= currBits
            }
            if (q != 0) {
                val k = CeltAllocation.pulses(q)
                cm = CeltVq.quantise(x, xOffset, n, k, spread, blocks, range, gain, resynth)
            } else if (resynth) {
                // No pulses: the decoder fills the band anyway, so the
                // encoder does the same to fold from it later.
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

    /** `quant_band`, encoder side: one channel's band, with its time-frequency resolution changed first. */
    private fun encodeBand(
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
        if (n == 1) return encodeBandN1(x, xOffset, 0, false, lowbandOut, lowbandOutOffset)
        if (tfChange > 0) recombine = tfChange
        if (scratch != null && lowband != null && (recombine != 0 || ((nB and 1) == 0 && tfChange < 0) || blocks > 1)) {
            lowband.copyInto(scratch, 0, lowbandOffset, lowbandOffset + n)
            lowband = scratch
            lowbandOffset = 0
        }
        for (k in 0 until recombine) {
            CeltBands.haar1(x, xOffset, n shr k, 1 shl k)
            if (lowband != null) CeltBands.haar1(lowband, lowbandOffset, n shr k, 1 shl k)
            fill = CeltBands.BIT_INTERLEAVE[fill and 0xF] or (CeltBands.BIT_INTERLEAVE[fill shr 4] shl 2)
        }
        blocks = blocks shr recombine
        nB = nB shl recombine
        while ((nB and 1) == 0 && tfChange < 0) {
            CeltBands.haar1(x, xOffset, nB, blocks)
            if (lowband != null) CeltBands.haar1(lowband, lowbandOffset, nB, blocks)
            fill = fill or (fill shl blocks)
            blocks = blocks shl 1
            nB = nB shr 1
            timeDivide++
            tfChange++
        }
        val blocks0 = blocks
        val nB0 = nB
        if (blocks0 > 1) {
            CeltBands.deinterleaveHadamard(x, xOffset, nB shr recombine, blocks0 shl recombine, longBlocks)
            if (lowband != null) {
                CeltBands.deinterleaveHadamard(lowband, lowbandOffset, nB shr recombine, blocks0 shl recombine, longBlocks)
            }
        }
        var cm = encodePartition(x, xOffset, n, b, blocks, lowband, lowbandOffset, lm, gain, fill)
        if (resynth) {
            if (blocks0 > 1) CeltBands.interleaveHadamard(x, xOffset, nB shr recombine, blocks0 shl recombine, longBlocks)
            nB = nB0
            blocks = blocks0
            for (k in 0 until timeDivide) {
                blocks = blocks shr 1
                nB = nB shl 1
                cm = cm or (cm shr blocks)
                CeltBands.haar1(x, xOffset, nB, blocks)
            }
            for (k in 0 until recombine) {
                cm = CeltBands.BIT_DEINTERLEAVE[cm]
                CeltBands.haar1(x, xOffset, n0 shr k, 1 shl k)
            }
            blocks = blocks shl recombine
            if (lowbandOut != null) {
                val scale = CeltMath.sqrt(n0.toFloat())
                for (j in 0 until n0) lowbandOut[lowbandOutOffset + j] = scale * x[xOffset + j]
            }
            cm = cm and ((1 shl blocks) - 1)
        }
        return cm
    }

    /** `quant_band_stereo`, encoder side: the pair at [xOffset] and [yOffset] of [x]. */
    private fun encodeBandStereo(
        x: FloatArray,
        xOffset: Int,
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
        fillIn: Int,
    ): Int {
        var cm: Int
        if (n == 1) return encodeBandN1(x, xOffset, yOffset, true, lowbandOut, lowbandOutOffset)
        val origFill = fillIn
        computeTheta(x, xOffset, yOffset, n, bIn, blocks, blocks, lm, true, fillIn)
        val b = thetaBits
        val fill = thetaFill
        val inv = thetaInv
        val delta = thetaDelta
        val itheta = thetaItheta
        val qalloc = thetaQalloc
        val mid = (1f / 32768) * thetaImid
        val side = (1f / 32768) * thetaIside
        if (n == 2) {
            // Mid and side are orthogonal, so one bit says which way the side points.
            var mbits = b
            var sbits = 0
            if (itheta != 0 && itheta != 16384) sbits = 1 shl BITRES
            mbits -= sbits
            val c = itheta > 8192
            remainingBits -= qalloc + sbits
            val x2 = if (c) yOffset else xOffset
            val y2 = if (c) xOffset else yOffset
            var sign = 0
            if (sbits != 0) {
                sign = if (x[x2] * x[y2 + 1] - x[x2 + 1] * x[y2] < 0) 1 else 0
                range.encodeRawBits(sign, 1)
            }
            sign = 1 - 2 * sign
            cm = encodeBand(
                x, x2, n, mbits, blocks, lowband, lowbandOffset, lm,
                lowbandOut, lowbandOutOffset, 1f, scratch, origFill,
            )
            x[y2] = -sign * x[x2 + 1]
            x[y2 + 1] = sign * x[x2]
            if (resynth) {
                x[xOffset] = mid * x[xOffset]
                x[xOffset + 1] = mid * x[xOffset + 1]
                x[yOffset] = side * x[yOffset]
                x[yOffset + 1] = side * x[yOffset + 1]
                var tmp = x[xOffset]
                x[xOffset] = tmp - x[yOffset]
                x[yOffset] = tmp + x[yOffset]
                tmp = x[xOffset + 1]
                x[xOffset + 1] = tmp - x[yOffset + 1]
                x[yOffset + 1] = tmp + x[yOffset + 1]
            }
        } else {
            var mbits = maxOf(0, minOf(b, (b - delta) / 2))
            var sbits = b - mbits
            remainingBits -= qalloc
            var rebalance = remainingBits
            if (mbits >= sbits) {
                cm = encodeBand(
                    x, xOffset, n, mbits, blocks, lowband, lowbandOffset, lm,
                    lowbandOut, lowbandOutOffset, 1f, scratch, fill,
                )
                rebalance = mbits - (rebalance - remainingBits)
                if (rebalance > 3 shl BITRES && itheta != 0) sbits += rebalance - (3 shl BITRES)
                cm = cm or encodeBand(x, yOffset, n, sbits, blocks, null, 0, lm, null, 0, side, null, fill shr blocks)
            } else {
                cm = encodeBand(x, yOffset, n, sbits, blocks, null, 0, lm, null, 0, side, null, fill shr blocks)
                rebalance = sbits - (rebalance - remainingBits)
                if (rebalance > 3 shl BITRES && itheta != 16384) mbits += rebalance - (3 shl BITRES)
                cm = cm or encodeBand(
                    x, xOffset, n, mbits, blocks, lowband, lowbandOffset, lm,
                    lowbandOut, lowbandOutOffset, 1f, scratch, fill,
                )
            }
        }
        if (resynth) {
            if (n != 2) CeltBands.stereoMerge(x, xOffset, x, yOffset, mid, n)
            if (inv) for (j in 0 until n) x[yOffset + j] = -x[yOffset + j]
        }
        return cm
    }

    private companion object {
        const val BITRES = CeltBands.BITRES
        const val NB_E_BANDS = 21
        const val NORM_SCALING = 1f
        const val EPSILON = 1e-15f
    }
}

package io.github.yuroyami.audiokodec.internal.opus.celt

import io.github.yuroyami.audiokodec.internal.opus.RangeDecoder
import io.github.yuroyami.audiokodec.internal.opus.RangeEncoder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Encodes the CELT layer of Opus, the transform codec [CeltDecoder] reads.
 *
 * A frame goes through a pitch prefilter, a transient detector that picks
 * long or short blocks, the MDCT, and then the analyses that decide what the
 * packet says: the time-frequency resolution of each band, how hard to
 * spread the pulses, which bands get extra bits, the allocation trim and the
 * stereo coding. The band energies are coded coarse then fine, and the band
 * shapes by [CeltBandEncoder], all through the same allocation the decoder
 * runs. With a target bit rate the packet size varies with how hard each
 * frame is to code, held near the target by a reservoir; without one, every
 * packet is the size it is given.
 *
 * Translated from libopus `celt_encoder.c`, float build, without the custom
 * modes, the hybrid mode's SILK coupling, surround masking or the tonality
 * analysis libopus can feed it: the encoder behaves as libopus's does when
 * that analysis has nothing to say. One instance holds one stream's state,
 * for one or two channels, at 48 kHz.
 */
internal class CeltEncoder(
    /** Channels coded, one or two. */
    val channels: Int,
    /** Analysis effort, 0 to 10, as libopus's complexity. */
    private val complexity: Int = 10,
) {
    /** The target bit rate, or [BITRATE_MAX] to fill every packet. */
    var bitrate: Int = BITRATE_MAX

    /** Whether the packet size follows the signal, with [bitrate] as the average. */
    var vbr: Boolean = false

    /** Whether variable bit rate is held within a buffer of one frame's bits. */
    var constrainedVbr: Boolean = true

    /** Bits of the input that are meaningful, which sets the silence threshold. */
    var lsbDepth: Int = 24

    /** The range coder's state after the last packet, which the decoder must reproduce. */
    var finalRange: Long = 0
        private set

    private var rng = 0
    private var spreadDecision = CeltVq.SPREAD_NORMAL
    private var delayedIntra = 1f
    private var tonalAverage = 256
    private var lastCodedBands = 0
    private var hfAverage = 0
    private var tapsetDecision = 0
    private var prefilterPeriod = 0
    private var prefilterGain = 0f
    private var prefilterTapset = 0
    private var consecTransient = 0
    private val preemphMem = FloatArray(2)
    private var vbrReservoir = 0
    private var vbrDrift = 0
    private var vbrOffset = 0
    private var vbrCount = 0
    private var overlapMax = 0f
    private var stereoSaving = 0f
    private var intensity = 0
    private var specAvg = 0f
    private val inMem = FloatArray(channels * OVERLAP)
    private val prefilterMem = FloatArray(channels * COMBFILTER_MAXPERIOD)
    private val oldBandE = FloatArray(channels * NB_E_BANDS)
    private val oldLogE = FloatArray(channels * NB_E_BANDS)
    private val oldLogE2 = FloatArray(channels * NB_E_BANDS)
    private val energyError = FloatArray(channels * NB_E_BANDS)

    init {
        require(channels == 1 || channels == 2) { "CELT codes one or two channels, not $channels" }
        reset()
    }

    /** `OPUS_RESET_STATE`: forgets everything about earlier frames. */
    fun reset() {
        rng = 0
        finalRange = 0
        spreadDecision = CeltVq.SPREAD_NORMAL
        delayedIntra = 1f
        tonalAverage = 256
        lastCodedBands = 0
        hfAverage = 0
        tapsetDecision = 0
        prefilterPeriod = 0
        prefilterGain = 0f
        prefilterTapset = 0
        consecTransient = 0
        preemphMem.fill(0f)
        vbrReservoir = 0
        vbrDrift = 0
        vbrOffset = 0
        vbrCount = 0
        overlapMax = 0f
        stereoSaving = 0f
        intensity = 0
        specAvg = 0f
        inMem.fill(0f)
        prefilterMem.fill(0f)
        oldBandE.fill(0f)
        oldLogE.fill(-28f)
        oldLogE2.fill(-28f)
        energyError.fill(0f)
    }

    /**
     * `celt_encode_with_ec`: codes [frameSize] samples per channel of [pcm],
     * interleaved and scaled to plus or minus one, into [out] from index 0,
     * using at most [maxBytes] bytes. Returns the packet's length.
     */
    fun encode(pcm: FloatArray, frameSize: Int, out: ByteArray, maxBytes: Int): Int {
        val c = channels
        val cc = channels
        val start = 0
        val end = EFF_E_BANDS
        var lm = 0
        while (lm <= MAX_LM) {
            if (SHORT_MDCT_SIZE shl lm == frameSize) break
            lm++
        }
        require(lm <= MAX_LM) { "CELT cannot code a $frameSize-sample frame" }
        require(maxBytes >= 2) { "a CELT packet needs at least two bytes" }
        val m = 1 shl lm
        val n = m * SHORT_MDCT_SIZE
        val nbFilledBytes = 0
        var tell = 1
        var nbCompressedBytes = minOf(maxBytes, 1275)
        var nbAvailableBytes = nbCompressedBytes - nbFilledBytes
        val vbrRate: Int
        var effectiveBytes: Int
        if (vbr && bitrate != BITRATE_MAX) {
            val den = 48_000 shr BITRES
            vbrRate = (bitrate * frameSize + (den shr 1)) / den
            effectiveBytes = vbrRate shr (3 + BITRES)
        } else {
            vbrRate = 0
            if (bitrate != BITRATE_MAX) {
                val tmp = bitrate * frameSize
                nbCompressedBytes = maxOf(2, minOf(nbCompressedBytes, (tmp + 4 * 48_000) / (8 * 48_000)))
            }
            effectiveBytes = nbCompressedBytes - nbFilledBytes
        }
        var equivRate = ((nbCompressedBytes * 8 * 50) shl (3 - lm)) - (40 * c + 20) * ((400 shr lm) - 50)
        if (bitrate != BITRATE_MAX) equivRate = minOf(equivRate, bitrate - (40 * c + 20) * ((400 shr lm) - 50))
        val enc = RangeEncoder(out, nbCompressedBytes)

        if (vbrRate > 0 && constrainedVbr) {
            // The most this frame may take without breaking the buffer.
            val vbrBound = vbrRate
            val maxAllowed = minOf(
                maxOf(if (tell == 1) 2 else 0, (vbrRate + vbrBound - vbrReservoir) shr (BITRES + 3)),
                nbAvailableBytes,
            )
            if (maxAllowed < nbAvailableBytes) {
                nbCompressedBytes = nbFilledBytes + maxAllowed
                nbAvailableBytes = maxAllowed
                enc.shrink(nbCompressedBytes)
            }
        }
        var totalBits = nbCompressedBytes * 8
        val effEnd = end

        val input = FloatArray(cc * (n + OVERLAP))
        var sampleMax = maxOf(overlapMax, maxAbs(pcm, 0, c * (n - OVERLAP)))
        overlapMax = maxAbs(pcm, c * (n - OVERLAP), c * OVERLAP)
        sampleMax = maxOf(sampleMax, overlapMax)
        var silence = sampleMax <= 1f / (1 shl lsbDepth)
        if (tell == 1) enc.encodeBitLogp(silence, 15) else silence = false
        if (silence) {
            // A silent frame needs nothing beyond the flag.
            if (vbrRate > 0) {
                nbCompressedBytes = minOf(nbCompressedBytes, nbFilledBytes + 2)
                effectiveBytes = nbCompressedBytes
                totalBits = nbCompressedBytes * 8
                nbAvailableBytes = 2
                enc.shrink(nbCompressedBytes)
            }
            tell = nbCompressedBytes * 8
            enc.addBits(tell - enc.tell())
        }
        for (ch in 0 until cc) {
            preemphasis(pcm, ch, input, ch * (n + OVERLAP) + OVERLAP, n, cc, ch, sampleMax > 65536f)
        }

        // The pitch prefilter, which the decoder's postfilter undoes.
        val pfEnabled = nbAvailableBytes > 12 * c && !silence && complexity >= 5
        val prefilterTapsetNew = tapsetDecision
        val prefilter = runPrefilter(input, cc, n, prefilterTapsetNew, pfEnabled, nbAvailableBytes)
        var pitchIndex = prefilter.pitchIndex
        val gain1 = prefilter.gain
        val pfOn = prefilter.on
        if (!pfOn) {
            if (tell + 16 <= totalBits) enc.encodeBitLogp(false, 1)
        } else {
            enc.encodeBitLogp(true, 1)
            pitchIndex += 1
            val octave = RangeDecoder.ilog(pitchIndex.toLong()) - 5
            enc.encodeUniform(octave, 6)
            enc.encodeRawBits(pitchIndex - (16 shl octave), 4 + octave)
            pitchIndex -= 1
            enc.encodeRawBits(prefilter.qg, 3)
            enc.encodeIcdf(prefilterTapsetNew, CeltDecoder.TAPSET_ICDF, 2)
        }

        var isTransient = false
        var shortBlocks = 0
        var tfEstimate = 0f
        var tfChan = 0
        if (complexity >= 1) {
            val transient = transientAnalysis(input, n + OVERLAP, cc)
            isTransient = transient.isTransient
            tfEstimate = transient.tfEstimate
            tfChan = transient.tfChan
        }
        var transientGotDisabled = false
        if (lm > 0 && enc.tell() + 3 <= totalBits) {
            if (isTransient) shortBlocks = m
        } else {
            isTransient = false
            transientGotDisabled = true
        }

        val freq = FloatArray(cc * n)
        val bandE = FloatArray(NB_E_BANDS * cc)
        val bandLogE = FloatArray(NB_E_BANDS * cc)
        val secondMdct = shortBlocks != 0 && complexity >= 8
        val bandLogE2 = FloatArray(c * NB_E_BANDS)
        if (secondMdct) {
            // A long transform too, whose energies drive the dynamic allocation.
            computeMdcts(0, input, freq, cc, lm)
            computeBandEnergies(freq, bandE, effEnd, c, lm)
            CeltEnergy.amplitudeToLog(effEnd, end, bandE, bandLogE2, c)
            for (ch in 0 until c) for (i in 0 until end) bandLogE2[NB_E_BANDS * ch + i] += 0.5f * lm
        }
        computeMdcts(shortBlocks, input, freq, cc, lm)
        computeBandEnergies(freq, bandE, effEnd, c, lm)
        CeltEnergy.amplitudeToLog(effEnd, end, bandE, bandLogE, c)

        // How far this frame's spectrum stands out from the recent average,
        // which lets the variable bit rate favour loud passages.
        var temporalVbr: Float
        run {
            var follow = -10f
            var frameAvg = 0f
            val offset = if (shortBlocks != 0) 0.5f * lm else 0f
            for (i in start until end) {
                follow = max16(follow - 1f, bandLogE[i] - offset)
                if (c == 2) follow = max16(follow, bandLogE[i + NB_E_BANDS] - offset)
                frameAvg += follow
            }
            frameAvg /= (end - start)
            temporalVbr = frameAvg - specAvg
            temporalVbr = min16(3f, max16(-1.5f, temporalVbr))
            specAvg += 0.02f * temporalVbr
        }
        if (!secondMdct) bandLogE.copyInto(bandLogE2, 0, 0, c * NB_E_BANDS)

        // A last chance to catch a transient the time-domain analysis missed.
        if (lm > 0 && enc.tell() + 3 <= totalBits && !isTransient && complexity >= 5) {
            if (patchTransientDecision(bandLogE, oldBandE, start, end, c)) {
                isTransient = true
                shortBlocks = m
                computeMdcts(shortBlocks, input, freq, cc, lm)
                computeBandEnergies(freq, bandE, effEnd, c, lm)
                CeltEnergy.amplitudeToLog(effEnd, end, bandE, bandLogE, c)
                for (ch in 0 until c) for (i in 0 until end) bandLogE2[NB_E_BANDS * ch + i] += 0.5f * lm
                tfEstimate = 0.2f
            }
        }
        if (lm > 0 && enc.tell() + 3 <= totalBits) enc.encodeBitLogp(isTransient, 3)

        val x = FloatArray(c * n)
        normaliseBands(freq, x, bandE, effEnd, c, m)
        val enableTfAnalysis = effectiveBytes >= 15 * c && complexity >= 2

        val offsets = IntArray(NB_E_BANDS)
        val importance = IntArray(NB_E_BANDS)
        val spreadWeight = IntArray(NB_E_BANDS)
        val dynalloc = dynallocAnalysis(
            bandLogE, bandLogE2, start, end, c, offsets, isTransient, lm, effectiveBytes, importance, spreadWeight,
        )
        val maxDepth = dynalloc.maxDepth
        val totBoost = dynalloc.totBoost

        val tfRes = IntArray(NB_E_BANDS)
        val tfSelect: Int
        if (enableTfAnalysis) {
            val lambda = maxOf(80, 20480 / effectiveBytes + 2)
            tfSelect = tfAnalysis(effEnd, isTransient, tfRes, lambda, x, n, lm, tfEstimate, tfChan, importance)
            for (i in effEnd until end) tfRes[i] = tfRes[effEnd - 1]
        } else {
            for (i in 0 until end) tfRes[i] = if (isTransient) 1 else 0
            tfSelect = 0
        }

        val error = FloatArray(c * NB_E_BANDS)
        for (ch in 0 until c) {
            for (i in start until end) {
                // When the energy is steady, lean towards the last frame's
                // error: a constant offset is better than fluctuation.
                val at = i + ch * NB_E_BANDS
                if (abs(bandLogE[at] - oldBandE[at]) < 2f) bandLogE[at] -= energyError[at] * 0.25f
            }
        }
        delayedIntra = CeltEnergy.quantiseCoarse(
            start, end, effEnd, bandLogE, oldBandE, totalBits, error, enc, c, lm, nbAvailableBytes,
            false, delayedIntra, complexity >= 4,
        )

        tfEncode(start, end, isTransient, tfRes, lm, tfSelect, enc)

        if (enc.tell() + 4 <= totalBits) {
            spreadDecision = if (shortBlocks != 0 || complexity < 3 || nbAvailableBytes < 10 * c) {
                if (complexity == 0) CeltVq.SPREAD_NONE else CeltVq.SPREAD_NORMAL
            } else {
                spreadingDecision(x, spreadDecision, pfOn && shortBlocks == 0, effEnd, c, m, spreadWeight)
            }
            enc.encodeIcdf(spreadDecision, CeltDecoder.SPREAD_ICDF, 5)
        }

        val cap = IntArray(NB_E_BANDS)
        CeltAllocation.caps(cap, lm, c)
        var dynallocLogp = 6
        totalBits = totalBits shl BITRES
        var totalBoost = 0
        tell = enc.tellFraction()
        for (i in start until end) {
            val width = c * (E_BANDS[i + 1] - E_BANDS[i]) shl lm
            // A boost is six bits, but no more than a bit a sample and no
            // less than an eighth of one.
            val quanta = minOf(width shl BITRES, maxOf(6 shl BITRES, width))
            var loopLogp = dynallocLogp
            var boost = 0
            var j = 0
            while (tell + (loopLogp shl BITRES) < totalBits - totalBoost && boost < cap[i]) {
                val flag = j < offsets[i]
                enc.encodeBitLogp(flag, loopLogp)
                tell = enc.tellFraction()
                if (!flag) break
                boost += quanta
                totalBoost += quanta
                loopLogp = 1
                j++
            }
            if (j != 0) dynallocLogp = maxOf(2, dynallocLogp - 1)
            offsets[i] = boost
        }

        var dualStereo = false
        if (c == 2) {
            // 2.5 ms frames always use mid and side.
            if (lm != 0) dualStereo = stereoAnalysis(x, lm, n)
            intensity = hysteresisDecision(
                (equivRate / 1000).toFloat(), INTENSITY_THRESHOLDS, INTENSITY_HYSTERESIS, intensity,
            )
            intensity = minOf(end, maxOf(start, intensity))
        }

        var allocTrim = 5
        if (tell + (6 shl BITRES) <= totalBits - totalBoost) {
            allocTrim = allocTrimAnalysis(x, bandLogE, end, lm, c, n, tfEstimate, intensity, equivRate)
            enc.encodeIcdf(allocTrim, CeltDecoder.TRIM_ICDF, 7)
            tell = enc.tellFraction()
        }

        if (vbrRate > 0) {
            val lmDiff = MAX_LM - lm
            // More than 510 kbit/s is of no use to the allocator, even in short frames.
            nbCompressedBytes = minOf(nbCompressedBytes, 1275 shr (3 - lm))
            var baseTarget = vbrRate - ((40 * c + 20) shl BITRES)
            if (constrainedVbr) baseTarget += vbrOffset shr lmDiff
            var target = computeVbr(
                baseTarget, lm, equivRate, c, totBoost, tfEstimate, maxDepth, temporalVbr,
            )
            target += tell
            // Never so small that the coder could run out of bits, with two
            // bytes to spare so that none of the decoder's safety checks trip.
            val minAllowed = ((tell + totalBoost + (1 shl (BITRES + 3)) - 1) shr (BITRES + 3)) + 2
            nbAvailableBytes = (target + (1 shl (BITRES + 2))) shr (BITRES + 3)
            nbAvailableBytes = maxOf(minAllowed, nbAvailableBytes)
            nbAvailableBytes = minOf(nbCompressedBytes, nbAvailableBytes)
            var delta = target - vbrRate
            target = nbAvailableBytes shl (BITRES + 3)
            if (silence) {
                // Silence does not move the drift, or the frames after a
                // silent passage would be given far too much.
                nbAvailableBytes = 2
                target = 2 * 8 shl BITRES
                delta = 0
            }
            val alpha: Float
            if (vbrCount < 970) {
                vbrCount++
                alpha = 1f / (vbrCount + 20)
            } else {
                alpha = 0.001f
            }
            if (constrainedVbr) vbrReservoir += target - vbrRate
            if (constrainedVbr) {
                vbrDrift += (alpha * (delta * (1 shl lmDiff) - vbrOffset - vbrDrift)).toInt()
                vbrOffset = -vbrDrift
            }
            if (constrainedVbr && vbrReservoir < 0) {
                // Under the minimum: spend more, unless the frame is silent.
                val adjust = (-vbrReservoir) / (8 shl BITRES)
                nbAvailableBytes += if (silence) 0 else adjust
                vbrReservoir = 0
            }
            nbCompressedBytes = minOf(nbCompressedBytes, nbAvailableBytes)
            enc.shrink(nbCompressedBytes)
        }

        val fineQuant = IntArray(NB_E_BANDS)
        val pulses = IntArray(NB_E_BANDS)
        val finePriority = IntArray(NB_E_BANDS)
        var bits = ((nbCompressedBytes * 8) shl BITRES) - enc.tellFraction() - 1
        val antiCollapseRsv = if (isTransient && lm >= 2 && bits >= ((lm + 2) shl BITRES)) 1 shl BITRES else 0
        bits -= antiCollapseRsv
        val signalBandwidth = end - 1
        val allocation = CeltAllocation.Result()
        allocation.intensity = intensity
        allocation.dualStereo = dualStereo
        CeltAllocation.compute(
            start, end, offsets, cap, allocTrim, bits, pulses, fineQuant, finePriority, c, lm,
            null, allocation, enc, lastCodedBands, signalBandwidth,
        )
        intensity = allocation.intensity
        dualStereo = allocation.dualStereo
        val codedBands = allocation.codedBands
        lastCodedBands = if (lastCodedBands != 0) {
            minOf(lastCodedBands + 1, maxOf(lastCodedBands - 1, codedBands))
        } else {
            codedBands
        }

        CeltEnergy.quantiseFine(start, end, oldBandE, error, fineQuant, enc, c)

        val collapseMasks = IntArray(c * NB_E_BANDS)
        rng = CeltBandEncoder(enc).encodeAll(
            start, end, x, c, n, collapseMasks, bandE, pulses, shortBlocks, spreadDecision,
            dualStereo, intensity, tfRes, nbCompressedBytes * (8 shl BITRES) - antiCollapseRsv,
            allocation.balance, lm, codedBands, rng, complexity, false,
        )
        if (antiCollapseRsv > 0) enc.encodeRawBits(if (consecTransient < 2) 1 else 0, 1)
        CeltEnergy.quantiseFinalise(
            start, end, oldBandE, error, fineQuant, finePriority, nbCompressedBytes * 8 - enc.tell(), enc, c,
        )
        energyError.fill(0f)
        for (ch in 0 until c) {
            for (i in start until end) {
                val at = i + ch * NB_E_BANDS
                energyError[at] = max16(-0.5f, min16(0.5f, error[at]))
            }
        }
        if (silence) oldBandE.fill(-28f, 0, c * NB_E_BANDS)

        prefilterPeriod = pitchIndex
        prefilterGain = gain1
        prefilterTapset = prefilterTapsetNew

        if (!isTransient) {
            oldLogE.copyInto(oldLogE2)
            oldBandE.copyInto(oldLogE)
        } else {
            for (i in 0 until cc * NB_E_BANDS) oldLogE[i] = min16(oldLogE[i], oldBandE[i])
        }
        for (ch in 0 until cc) {
            for (i in 0 until start) {
                oldBandE[ch * NB_E_BANDS + i] = 0f
                oldLogE[ch * NB_E_BANDS + i] = -28f
                oldLogE2[ch * NB_E_BANDS + i] = -28f
            }
            for (i in end until NB_E_BANDS) {
                oldBandE[ch * NB_E_BANDS + i] = 0f
                oldLogE[ch * NB_E_BANDS + i] = -28f
                oldLogE2[ch * NB_E_BANDS + i] = -28f
            }
        }
        if (isTransient || transientGotDisabled) consecTransient++ else consecTransient = 0
        finalRange = enc.finalRange
        rng = finalRange.toInt()
        enc.done()
        check(!enc.error) { "the CELT encoder overflowed its packet" }
        return nbCompressedBytes
    }

    /** `celt_preemphasis`: scales channel [ch] of [pcm] to 16-bit range and tilts it up in frequency. */
    private fun preemphasis(
        pcm: FloatArray,
        pcmOffset: Int,
        out: FloatArray,
        outOffset: Int,
        n: Int,
        stride: Int,
        ch: Int,
        clip: Boolean,
    ) {
        var mem = preemphMem[ch]
        for (i in 0 until n) {
            var x = pcm[pcmOffset + stride * i] * SIG_SCALE
            // Clipping keeps a wildly out-of-range input from producing a
            // stream that decodes differently on different platforms.
            if (clip) x = maxOf(-65536f, minOf(65536f, x))
            out[outOffset + i] = x - mem
            mem = CeltDecoder.PREEMPH * x
        }
        preemphMem[ch] = mem
    }

    private class Prefilter(val on: Boolean, val pitchIndex: Int, val gain: Float, val qg: Int)

    /**
     * `run_prefilter`: finds the pitch of [input] and applies the comb
     * filter that attenuates it, which the decoder's postfilter reverses.
     * Shifts the prefilter history along by a frame either way.
     */
    private fun runPrefilter(
        input: FloatArray,
        cc: Int,
        n: Int,
        tapset: Int,
        enabled: Boolean,
        nbAvailableBytes: Int,
    ): Prefilter {
        val pre = Array(cc) { ch ->
            FloatArray(n + COMBFILTER_MAXPERIOD).also {
                prefilterMem.copyInto(it, 0, ch * COMBFILTER_MAXPERIOD, (ch + 1) * COMBFILTER_MAXPERIOD)
                input.copyInto(it, COMBFILTER_MAXPERIOD, ch * (n + OVERLAP) + OVERLAP, ch * (n + OVERLAP) + OVERLAP + n)
            }
        }
        var gain1: Float
        var pitchIndex: Int
        if (enabled) {
            val pitchBuf = FloatArray((COMBFILTER_MAXPERIOD + n) shr 1)
            CeltFilters.pitchDownsample(pre, IntArray(cc), pitchBuf, COMBFILTER_MAXPERIOD + n, cc)
            // The last octave and a half of the range is left out: short-term
            // correlation gives too many false positives there.
            pitchIndex = CeltFilters.pitchSearch(
                pitchBuf, COMBFILTER_MAXPERIOD shr 1, pitchBuf, 0, n,
                COMBFILTER_MAXPERIOD - 3 * CeltFilters.COMBFILTER_MINPERIOD,
            )
            pitchIndex = COMBFILTER_MAXPERIOD - pitchIndex
            val period = IntArray(1)
            gain1 = CeltFilters.removeDoubling(
                pitchBuf, COMBFILTER_MAXPERIOD, CeltFilters.COMBFILTER_MINPERIOD, n, pitchIndex,
                prefilterPeriod, prefilterGain, period,
            )
            pitchIndex = period[0]
            if (pitchIndex > COMBFILTER_MAXPERIOD - 2) pitchIndex = COMBFILTER_MAXPERIOD - 2
            gain1 *= 0.7f
        } else {
            gain1 = 0f
            pitchIndex = CeltFilters.COMBFILTER_MINPERIOD
        }
        // The gain needed to switch the filter on depends on the rate and
        // on how much the pitch moved.
        var threshold = 0.2f
        if (abs(pitchIndex - prefilterPeriod) * 10 > pitchIndex) threshold += 0.2f
        if (nbAvailableBytes < 25) threshold += 0.1f
        if (nbAvailableBytes < 35) threshold += 0.1f
        if (prefilterGain > 0.4f) threshold -= 0.1f
        if (prefilterGain > 0.55f) threshold -= 0.1f
        threshold = max16(threshold, 0.2f)
        val on: Boolean
        val qg: Int
        if (gain1 < threshold) {
            gain1 = 0f
            on = false
            qg = 0
        } else {
            if (abs(gain1 - prefilterGain) < 0.1f) gain1 = prefilterGain
            qg = maxOf(0, minOf(7, floor(0.5f + gain1 * 32 / 3).toInt() - 1))
            gain1 = 0.09375f * (qg + 1)
            on = true
        }
        for (ch in 0 until cc) {
            val base = ch * (n + OVERLAP)
            prefilterPeriod = maxOf(prefilterPeriod, CeltFilters.COMBFILTER_MINPERIOD)
            inMem.copyInto(input, base, ch * OVERLAP, (ch + 1) * OVERLAP)
            CeltFilters.comb(
                input, base + OVERLAP, pre[ch], COMBFILTER_MAXPERIOD, prefilterPeriod, pitchIndex, n,
                -prefilterGain, -gain1, prefilterTapset, tapset, WINDOW, OVERLAP,
            )
            input.copyInto(inMem, ch * OVERLAP, base + n, base + n + OVERLAP)
            val memAt = ch * COMBFILTER_MAXPERIOD
            if (n > COMBFILTER_MAXPERIOD) {
                pre[ch].copyInto(prefilterMem, memAt, n, n + COMBFILTER_MAXPERIOD)
            } else {
                prefilterMem.copyInto(prefilterMem, memAt, memAt + n, memAt + COMBFILTER_MAXPERIOD)
                pre[ch].copyInto(prefilterMem, memAt + COMBFILTER_MAXPERIOD - n, COMBFILTER_MAXPERIOD, COMBFILTER_MAXPERIOD + n)
            }
        }
        return Prefilter(on, pitchIndex, gain1, qg)
    }

    private class Transient(val isTransient: Boolean, val tfEstimate: Float, val tfChan: Int)

    /**
     * `transient_analysis`: whether a frame holds an attack that long blocks
     * would smear into a pre-echo, judged by how the energy of the
     * high-passed signal compares with its own forward and backward masking.
     */
    private fun transientAnalysis(input: FloatArray, len: Int, cc: Int): Transient {
        val forwardDecay = 0.0625f
        val tmp = FloatArray(len)
        val len2 = len / 2
        var maskMetric = 0
        var tfChan = 0
        for (ch in 0 until cc) {
            var mem0 = 0f
            var mem1 = 0f
            // High-pass: (1 - 2z^-1 + z^-2) / (1 - z^-1 + 0.5z^-2).
            for (i in 0 until len) {
                val x = input[i + ch * len]
                val y = mem0 + x
                val mem00 = mem0
                mem0 = mem0 - x + 0.5f * mem1
                mem1 = x - mem00
                tmp[i] = y
            }
            // The first few samples are unreliable without the filter's history.
            tmp.fill(0f, 0, 12)
            var mean = 0f
            mem0 = 0f
            // Forward masking, two samples at a time.
            for (i in 0 until len2) {
                val x2 = tmp[2 * i] * tmp[2 * i] + tmp[2 * i + 1] * tmp[2 * i + 1]
                mean += x2
                mem0 = x2 + (1f - forwardDecay) * mem0
                tmp[i] = forwardDecay * mem0
            }
            mem0 = 0f
            var maxE = 0f
            // Backward masking.
            for (i in len2 - 1 downTo 0) {
                mem0 = tmp[i] + 0.875f * mem0
                tmp[i] = 0.125f * mem0
                maxE = max16(maxE, 0.125f * mem0)
            }
            // The geometric mean of the energy and half the maximum.
            mean = sqrt((mean * maxE) * 0.5 * len2).toFloat()
            val norm = len2 / (EPSILON + mean)
            var unmask = 0
            var i = 12
            while (i < len2 - 5) {
                val id = floor((64 * norm * (tmp[i] + EPSILON)).toDouble()).coerceIn(0.0, 127.0).toInt()
                unmask += INV_TABLE[id]
                i += 4
            }
            unmask = 64 * unmask * 4 / (6 * (len2 - 17))
            if (unmask > maskMetric) {
                tfChan = ch
                maskMetric = unmask
            }
        }
        val isTransient = maskMetric > 200
        val tfMax = max16(0f, CeltMath.sqrt((27 * maskMetric).toFloat()) - 42)
        val tfEstimate = sqrt(maxOf(0.0, (0.0069f * min16(163f, tfMax)).toDouble() - 0.139)).toFloat()
        return Transient(isTransient, tfEstimate, tfChan)
    }

    /** `patch_transient_decision`: a sudden rise in energy across bands also counts as a transient. */
    private fun patchTransientDecision(newE: FloatArray, oldE: FloatArray, start: Int, end: Int, c: Int): Boolean {
        val spreadOld = FloatArray(26)
        // A -6 dB per band spread keeps irrelevant bands from triggering it.
        if (c == 1) {
            spreadOld[start] = oldE[start]
            for (i in start + 1 until end) spreadOld[i] = max16(spreadOld[i - 1] - 1f, oldE[i])
        } else {
            spreadOld[start] = max16(oldE[start], oldE[start + NB_E_BANDS])
            for (i in start + 1 until end) {
                spreadOld[i] = max16(spreadOld[i - 1] - 1f, max16(oldE[i], oldE[i + NB_E_BANDS]))
            }
        }
        for (i in end - 2 downTo start) spreadOld[i] = max16(spreadOld[i], spreadOld[i + 1] - 1f)
        var meanDiff = 0f
        val from = maxOf(2, start)
        for (ch in 0 until c) {
            for (i in from until end - 1) {
                val x1 = max16(0f, newE[i + ch * NB_E_BANDS])
                val x2 = max16(0f, spreadOld[i])
                meanDiff += max16(0f, x1 - x2)
            }
        }
        meanDiff /= (c * (end - 1 - from)).toFloat()
        return meanDiff > 1f
    }

    /** `compute_mdcts`: the windowed MDCT of every block of every channel, interleaving the blocks. */
    private fun computeMdcts(shortBlocks: Int, input: FloatArray, out: FloatArray, cc: Int, lm: Int) {
        val blocks: Int
        val nb: Int
        val shift: Int
        if (shortBlocks != 0) {
            blocks = shortBlocks
            nb = SHORT_MDCT_SIZE
            shift = MAX_LM
        } else {
            blocks = 1
            nb = SHORT_MDCT_SIZE shl lm
            shift = MAX_LM - lm
        }
        for (ch in 0 until cc) {
            for (b in 0 until blocks) {
                CeltMdct.forward(
                    input, ch * (blocks * nb + OVERLAP) + b * nb, out, b + ch * nb * blocks,
                    WINDOW, OVERLAP, shift, blocks,
                )
            }
        }
    }

    /** `compute_band_energies`: the amplitude of each band. */
    private fun computeBandEnergies(x: FloatArray, bandE: FloatArray, end: Int, c: Int, lm: Int) {
        val n = SHORT_MDCT_SIZE shl lm
        for (ch in 0 until c) {
            for (i in 0 until end) {
                val from = ch * n + (E_BANDS[i] shl lm)
                val sum = 1e-27f + CeltMath.innerProduct(x, from, x, from, (E_BANDS[i + 1] - E_BANDS[i]) shl lm)
                bandE[i + ch * NB_E_BANDS] = CeltMath.sqrt(sum)
            }
        }
    }

    /** `normalise_bands`: divides each band by its amplitude, leaving unit-energy shapes. */
    private fun normaliseBands(freq: FloatArray, x: FloatArray, bandE: FloatArray, end: Int, c: Int, m: Int) {
        val n = m * SHORT_MDCT_SIZE
        for (ch in 0 until c) {
            for (i in 0 until end) {
                val g = 1f / (1e-27f + bandE[i + ch * NB_E_BANDS])
                for (j in m * E_BANDS[i] until m * E_BANDS[i + 1]) x[j + ch * n] = freq[j + ch * n] * g
            }
        }
    }

    private class Dynalloc(val maxDepth: Float, val totBoost: Int)

    /**
     * `dynalloc_analysis`: which bands stand far enough above a smoothed
     * envelope of their neighbours to deserve extra bits, in [offsets], how
     * much each band matters for the time-frequency decision, in
     * [importance], and how audible each band's spreading is, in
     * [spreadWeight].
     */
    private fun dynallocAnalysis(
        bandLogE: FloatArray,
        bandLogE2: FloatArray,
        start: Int,
        end: Int,
        c: Int,
        offsets: IntArray,
        isTransient: Boolean,
        lm: Int,
        effectiveBytes: Int,
        importance: IntArray,
        spreadWeight: IntArray,
    ): Dynalloc {
        var totBoost = 0
        val follower = FloatArray(c * NB_E_BANDS)
        val noiseFloor = FloatArray(c * NB_E_BANDS)
        val bandLogE3 = FloatArray(NB_E_BANDS)
        offsets.fill(0)
        var maxDepth = -31.9f
        for (i in 0 until end) {
            // The noise floor allows for the band means, the depth, the band
            // width and the pre-emphasis, roughly the square of the band index.
            noiseFloor[i] = 0.0625f * CeltModeTables.LOG_N[i] + 0.5f + (9 - lsbDepth) - CeltEnergy.E_MEANS[i] +
                0.0062f * ((i + 5) * (i + 5))
        }
        for (ch in 0 until c) {
            for (i in 0 until end) maxDepth = max16(maxDepth, bandLogE[ch * NB_E_BANDS + i] - noiseFloor[i])
        }
        run {
            // A simple masking model, so that bands nobody can hear do not
            // sway the spreading decision.
            val mask = FloatArray(NB_E_BANDS)
            val sig = FloatArray(NB_E_BANDS)
            for (i in 0 until end) mask[i] = bandLogE[i] - noiseFloor[i]
            if (c == 2) {
                for (i in 0 until end) mask[i] = max16(mask[i], bandLogE[NB_E_BANDS + i] - noiseFloor[i])
            }
            mask.copyInto(sig, 0, 0, end)
            for (i in 1 until end) mask[i] = max16(mask[i], mask[i - 1] - 2f)
            for (i in end - 2 downTo 0) mask[i] = max16(mask[i], mask[i + 1] - 3f)
            for (i in 0 until end) {
                val smr = sig[i] - max16(max16(0f, maxDepth - 12f), mask[i])
                val shift = minOf(5, maxOf(0, -floor(0.5f + smr).toInt()))
                spreadWeight[i] = 32 shr shift
            }
        }
        // Dynamic allocation starts at 24 kbit/s for 20 ms frames, so it can
        // never break the budget.
        if (effectiveBytes >= 30 + 5 * lm) {
            var last = 0
            for (ch in 0 until c) {
                bandLogE2.copyInto(bandLogE3, 0, ch * NB_E_BANDS, ch * NB_E_BANDS + end)
                if (lm == 0) {
                    // At 2.5 ms the lowest bands have a single bin, so the
                    // previous energy steadies them.
                    for (i in 0 until minOf(8, end)) {
                        bandLogE3[i] = max16(bandLogE2[ch * NB_E_BANDS + i], oldBandE[ch * NB_E_BANDS + i])
                    }
                }
                val f = ch * NB_E_BANDS
                follower[f] = bandLogE3[0]
                for (i in 1 until end) {
                    // The last band at least 3 dB above the one below is the
                    // last considered, which matters for band-limited input.
                    if (bandLogE3[i] > bandLogE3[i - 1] + 0.5f) last = i
                    follower[f + i] = min16(follower[f + i - 1] + 1.5f, bandLogE3[i])
                }
                for (i in last - 1 downTo 0) {
                    follower[f + i] = min16(follower[f + i], min16(follower[f + i + 1] + 2f, bandLogE3[i]))
                }
                // A median filter keeps the boost from triggering needlessly.
                val offset = 1f
                for (i in 2 until end - 2) follower[f + i] = max16(follower[f + i], medianOf5(bandLogE3, i - 2) - offset)
                var tmp = medianOf3(bandLogE3, 0) - offset
                follower[f] = max16(follower[f], tmp)
                follower[f + 1] = max16(follower[f + 1], tmp)
                tmp = medianOf3(bandLogE3, end - 3) - offset
                follower[f + end - 2] = max16(follower[f + end - 2], tmp)
                follower[f + end - 1] = max16(follower[f + end - 1], tmp)
                for (i in 0 until end) follower[f + i] = max16(follower[f + i], noiseFloor[i])
            }
            if (c == 2) {
                for (i in start until end) {
                    // Allow 24 dB of cross-talk between the channels.
                    follower[NB_E_BANDS + i] = max16(follower[NB_E_BANDS + i], follower[i] - 4f)
                    follower[i] = max16(follower[i], follower[NB_E_BANDS + i] - 4f)
                    follower[i] = 0.5f * (
                        max16(0f, bandLogE[i] - follower[i]) +
                            max16(0f, bandLogE[NB_E_BANDS + i] - follower[NB_E_BANDS + i])
                        )
                }
            } else {
                for (i in start until end) follower[i] = max16(0f, bandLogE[i] - follower[i])
            }
            for (i in start until end) follower[i] = max16(follower[i], 0f)
            for (i in start until end) {
                importance[i] = floor(0.5f + 13 * CeltMath.exp2(min16(follower[i], 4f))).toInt()
            }
            // Constant and constrained variable bit rates halve the boost
            // outside transients.
            if ((!vbr || constrainedVbr) && !isTransient) {
                for (i in start until end) follower[i] = 0.5f * follower[i]
            }
            for (i in start until end) {
                if (i < 8) follower[i] *= 2f
                if (i >= 12) follower[i] = 0.5f * follower[i]
            }
            for (i in start until end) {
                follower[i] = min16(follower[i], 4f)
                val width = c * (E_BANDS[i + 1] - E_BANDS[i]) shl lm
                val boost: Int
                val boostBits: Int
                if (width < 6) {
                    boost = follower[i].toInt()
                    boostBits = boost * width shl BITRES
                } else if (width > 48) {
                    boost = (follower[i] * 8).toInt()
                    boostBits = (boost * width shl BITRES) / 8
                } else {
                    boost = (follower[i] * width / 6).toInt()
                    boostBits = boost * 6 shl BITRES
                }
                // Constant and steady constrained rates cap the boosts at
                // two thirds of the frame.
                if ((!vbr || (constrainedVbr && !isTransient)) &&
                    (totBoost + boostBits) shr BITRES shr 3 > 2 * effectiveBytes / 3
                ) {
                    val cap = (2 * effectiveBytes / 3) shl BITRES shl 3
                    offsets[i] = cap - totBoost
                    totBoost = cap
                    break
                } else {
                    offsets[i] = boost
                    totBoost += boostBits
                }
            }
        } else {
            for (i in start until end) importance[i] = 13
        }
        return Dynalloc(maxDepth, totBoost)
    }

    /**
     * `tf_analysis`: for each band, whether finer time or finer frequency
     * resolution makes its shape sparser, which a Viterbi search turns into
     * a cheap-to-code sequence of changes. Returns `tf_select`.
     */
    private fun tfAnalysis(
        len: Int,
        isTransient: Boolean,
        tfRes: IntArray,
        lambda: Int,
        x: FloatArray,
        n0: Int,
        lm: Int,
        tfEstimate: Float,
        tfChan: Int,
        importance: IntArray,
    ): Int {
        val transient = if (isTransient) 1 else 0
        val bias = 0.04f * max16(-0.25f, 0.5f - tfEstimate)
        val metric = IntArray(len)
        val bandSize = (E_BANDS[len] - E_BANDS[len - 1]) shl lm
        val tmp = FloatArray(bandSize)
        val tmp1 = FloatArray(bandSize)
        val path0 = IntArray(len)
        val path1 = IntArray(len)
        for (i in 0 until len) {
            val n = (E_BANDS[i + 1] - E_BANDS[i]) shl lm
            // A band of one bin cannot be split down to the finest level.
            val narrow = (E_BANDS[i + 1] - E_BANDS[i]) == 1
            x.copyInto(tmp, 0, tfChan * n0 + (E_BANDS[i] shl lm), tfChan * n0 + (E_BANDS[i] shl lm) + n)
            var l1 = l1Metric(tmp, n, if (isTransient) lm else 0, bias)
            var bestL1 = l1
            var bestLevel = 0
            if (isTransient && !narrow) {
                tmp.copyInto(tmp1, 0, 0, n)
                CeltBands.haar1(tmp1, 0, n shr lm, 1 shl lm)
                l1 = l1Metric(tmp1, n, lm + 1, bias)
                if (l1 < bestL1) {
                    bestL1 = l1
                    bestLevel = -1
                }
            }
            for (k in 0 until lm + if (!(isTransient || narrow)) 1 else 0) {
                val b = if (isTransient) lm - k - 1 else k + 1
                CeltBands.haar1(tmp, 0, n shr k, 1 shl k)
                l1 = l1Metric(tmp, n, b, bias)
                if (l1 < bestL1) {
                    bestL1 = l1
                    bestLevel = k + 1
                }
            }
            // In halves, so that narrow bands can sit between two levels.
            metric[i] = if (isTransient) 2 * bestLevel else -2 * bestLevel
            if (narrow && (metric[i] == 0 || metric[i] == -2 * lm)) metric[i] -= 1
        }
        val table = CeltDecoder.TF_SELECT[lm]
        var tfSelect = 0
        val selCost = IntArray(2)
        for (sel in 0 until 2) {
            var cost0 = importance[0] * abs(metric[0] - 2 * table[4 * transient + 2 * sel + 0])
            var cost1 = importance[0] * abs(metric[0] - 2 * table[4 * transient + 2 * sel + 1]) +
                if (isTransient) 0 else lambda
            for (i in 1 until len) {
                val curr0 = minOf(cost0, cost1 + lambda)
                val curr1 = minOf(cost0 + lambda, cost1)
                cost0 = curr0 + importance[i] * abs(metric[i] - 2 * table[4 * transient + 2 * sel + 0])
                cost1 = curr1 + importance[i] * abs(metric[i] - 2 * table[4 * transient + 2 * sel + 1])
            }
            selCost[sel] = minOf(cost0, cost1)
        }
        // Only transients may use the second table, for now, as in the reference.
        if (selCost[1] < selCost[0] && isTransient) tfSelect = 1
        var cost0 = importance[0] * abs(metric[0] - 2 * table[4 * transient + 2 * tfSelect + 0])
        var cost1 = importance[0] * abs(metric[0] - 2 * table[4 * transient + 2 * tfSelect + 1]) +
            if (isTransient) 0 else lambda
        for (i in 1 until len) {
            var from0 = cost0
            var from1 = cost1 + lambda
            val curr0: Int
            if (from0 < from1) {
                curr0 = from0
                path0[i] = 0
            } else {
                curr0 = from1
                path0[i] = 1
            }
            from0 = cost0 + lambda
            from1 = cost1
            val curr1: Int
            if (from0 < from1) {
                curr1 = from0
                path1[i] = 0
            } else {
                curr1 = from1
                path1[i] = 1
            }
            cost0 = curr0 + importance[i] * abs(metric[i] - 2 * table[4 * transient + 2 * tfSelect + 0])
            cost1 = curr1 + importance[i] * abs(metric[i] - 2 * table[4 * transient + 2 * tfSelect + 1])
        }
        tfRes[len - 1] = if (cost0 < cost1) 0 else 1
        for (i in len - 2 downTo 0) {
            tfRes[i] = if (tfRes[i + 1] == 1) path1[i + 1] else path0[i + 1]
        }
        return tfSelect
    }

    /** `l1_metric`: the L1 norm of a band, biased towards frequency resolution when in doubt. */
    private fun l1Metric(tmp: FloatArray, n: Int, lm: Int, bias: Float): Float {
        var l1 = 0f
        for (i in 0 until n) l1 += abs(tmp[i])
        return l1 + lm * bias * l1
    }

    /** `tf_encode`: writes the per-band resolution changes [CeltDecoder] reads in `tf_decode`. */
    private fun tfEncode(start: Int, end: Int, isTransient: Boolean, tfRes: IntArray, lm: Int, tfSelectIn: Int, enc: RangeEncoder) {
        var tfSelect = tfSelectIn
        var budget = enc.storage * 8
        var tell = enc.tell()
        var logp = if (isTransient) 2 else 4
        val transient = if (isTransient) 1 else 0
        // Room for the tf_select flag.
        val tfSelectRsv = if (lm > 0 && tell + logp + 1 <= budget) 1 else 0
        budget -= tfSelectRsv
        var curr = 0
        var tfChanged = 0
        for (i in start until end) {
            if (tell + logp <= budget) {
                enc.encodeBitLogp((tfRes[i] xor curr) != 0, logp)
                tell = enc.tell()
                curr = tfRes[i]
                tfChanged = tfChanged or curr
            } else {
                tfRes[i] = curr
            }
            logp = if (isTransient) 4 else 5
        }
        val table = CeltDecoder.TF_SELECT[lm]
        if (tfSelectRsv != 0 && table[4 * transient + 0 + tfChanged] != table[4 * transient + 2 + tfChanged]) {
            enc.encodeBitLogp(tfSelect != 0, 1)
        } else {
            tfSelect = 0
        }
        for (i in start until end) tfRes[i] = table[4 * transient + 2 * tfSelect + tfRes[i]]
    }

    /**
     * `alloc_trim_analysis`: tilts the allocation towards low or high bands,
     * from the spectral slope, the transient estimate and, in stereo, how
     * much the two channels have in common. Updates [stereoSaving].
     */
    private fun allocTrimAnalysis(
        x: FloatArray,
        bandLogE: FloatArray,
        end: Int,
        lm: Int,
        c: Int,
        n0: Int,
        tfEstimate: Float,
        intensity: Int,
        equivRate: Int,
    ): Int {
        var trim = 5f
        // At low rates a lower trim helps.
        if (equivRate < 64000) {
            trim = 4f
        } else if (equivRate < 80000) {
            val frac = (equivRate - 64000) shr 10
            trim = 4f + (1f / 16f) * frac
        }
        if (c == 2) {
            // How correlated the channels are at low frequencies.
            var sum = 0f
            for (i in 0 until 8) {
                sum += CeltMath.innerProduct(
                    x, E_BANDS[i] shl lm, x, n0 + (E_BANDS[i] shl lm), (E_BANDS[i + 1] - E_BANDS[i]) shl lm,
                )
            }
            sum *= 1f / 8
            sum = min16(1f, abs(sum))
            var minXc = sum
            for (i in 8 until intensity) {
                val partial = CeltMath.innerProduct(
                    x, E_BANDS[i] shl lm, x, n0 + (E_BANDS[i] shl lm), (E_BANDS[i + 1] - E_BANDS[i]) shl lm,
                )
                minXc = min16(minXc, abs(partial))
            }
            minXc = min16(1f, abs(minXc))
            // What mid and side would save, from the average and from the
            // least correlated band.
            val logXc = CeltMath.log2(1.001f - sum * sum)
            val logXc2 = max16(0.5f * logXc, CeltMath.log2(1.001f - minXc * minXc))
            trim += max16(-4f, 0.75f * logXc)
            stereoSaving = min16(stereoSaving + 0.25f, -0.5f * logXc2)
        }
        // The spectral tilt.
        var diff = 0f
        for (ch in 0 until c) {
            for (i in 0 until end - 1) diff += bandLogE[i + ch * NB_E_BANDS] * (2 + 2 * i - end)
        }
        diff /= c * (end - 1)
        trim -= max16(-2f, min16(2f, (diff + 1f) / 6))
        trim -= 2 * tfEstimate
        return minOf(10, maxOf(0, floor(0.5f + trim).toInt()))
    }

    /** `stereo_analysis`: whether coding left and right separately is cheaper than mid and side. */
    private fun stereoAnalysis(x: FloatArray, lm: Int, n0: Int): Boolean {
        var sumLr = EPSILON
        var sumMs = EPSILON
        // The L1 norm stands in for the entropy of each representation.
        for (i in 0 until 13) {
            for (j in E_BANDS[i] shl lm until (E_BANDS[i + 1] shl lm)) {
                val l = x[j]
                val r = x[n0 + j]
                val mid = l + r
                val side = l - r
                sumLr += abs(l) + abs(r)
                sumMs += abs(mid) + abs(side)
            }
        }
        sumMs *= 0.707107f
        var thetas = 13
        // Short frames need no angles for the lower bands.
        if (lm <= 1) thetas -= 8
        return ((E_BANDS[13] shl (lm + 1)) + thetas) * sumMs > (E_BANDS[13] shl (lm + 1)) * sumLr
    }

    /** `hysteresis_decision`: the step of [thresholds] that [value] reaches, sticking to [prev] near a boundary. */
    private fun hysteresisDecision(value: Float, thresholds: FloatArray, hysteresis: FloatArray, prev: Int): Int {
        val count = thresholds.size
        var i = 0
        while (i < count) {
            if (value < thresholds[i]) break
            i++
        }
        if (i > prev && value < thresholds[prev] + hysteresis[prev]) i = prev
        if (i < prev && value > thresholds[prev - 1] - hysteresis[prev - 1]) i = prev
        return i
    }

    /**
     * `spreading_decision`: how much to spread the pulses, from how peaky
     * the bands are, averaged over time with hysteresis. When [updateHf],
     * also chooses the prefilter's tap set from the high bands.
     */
    private fun spreadingDecision(
        x: FloatArray,
        lastDecision: Int,
        updateHf: Boolean,
        end: Int,
        c: Int,
        m: Int,
        spreadWeight: IntArray,
    ): Int {
        var sum = 0
        var nbBands = 0
        var hfSum = 0
        val n0 = m * SHORT_MDCT_SIZE
        if (m * (E_BANDS[end] - E_BANDS[end - 1]) <= 8) return CeltVq.SPREAD_NONE
        for (ch in 0 until c) {
            for (i in 0 until end) {
                val from = m * E_BANDS[i] + ch * n0
                val n = m * (E_BANDS[i + 1] - E_BANDS[i])
                if (n <= 8) continue
                val tcount = IntArray(3)
                // A rough cumulative distribution of the coefficients' sizes.
                for (j in 0 until n) {
                    val x2n = (x[from + j] * x[from + j]) * n
                    if (x2n < 0.25f) tcount[0]++
                    if (x2n < 0.0625f) tcount[1]++
                    if (x2n < 0.015625f) tcount[2]++
                }
                // Only the four last bands, 8 kHz and up, decide the tap set.
                if (i > NB_E_BANDS - 4) hfSum += CeltMath.udiv(32 * (tcount[1] + tcount[0]), n)
                val tmp = (if (2 * tcount[2] >= n) 1 else 0) + (if (2 * tcount[1] >= n) 1 else 0) +
                    (if (2 * tcount[0] >= n) 1 else 0)
                sum += tmp * spreadWeight[i]
                nbBands += spreadWeight[i]
            }
        }
        if (updateHf) {
            if (hfSum != 0) hfSum = CeltMath.udiv(hfSum, c * (4 - NB_E_BANDS + end))
            hfAverage = (hfAverage + hfSum) shr 1
            hfSum = hfAverage
            if (tapsetDecision == 2) {
                hfSum += 4
            } else if (tapsetDecision == 0) {
                hfSum -= 4
            }
            tapsetDecision = when {
                hfSum > 22 -> 2
                hfSum > 18 -> 1
                else -> 0
            }
        }
        sum = CeltMath.udiv(sum shl 8, nbBands)
        sum = (sum + tonalAverage) shr 1
        tonalAverage = sum
        sum = (3 * sum + (((3 - lastDecision) shl 7) + 64) + 2) shr 2
        return when {
            sum < 80 -> CeltVq.SPREAD_AGGRESSIVE
            sum < 256 -> CeltVq.SPREAD_NORMAL
            sum < 384 -> CeltVq.SPREAD_LIGHT
            else -> CeltVq.SPREAD_NONE
        }
    }

    /**
     * `compute_vbr`: this frame's share of the bits, in eighth-bits, from the
     * long-run target [baseTarget] adjusted for stereo redundancy, dynamic
     * allocation, transients, the signal's depth and how loud the frame is
     * against its neighbours.
     */
    private fun computeVbr(
        baseTarget: Int,
        lm: Int,
        bitrate: Int,
        c: Int,
        totBoost: Int,
        tfEstimate: Float,
        maxDepth: Float,
        temporalVbr: Float,
    ): Int {
        val codedBands = if (lastCodedBands != 0) lastCodedBands else NB_E_BANDS
        var codedBins = E_BANDS[codedBands] shl lm
        if (c == 2) codedBins += E_BANDS[minOf(intensity, codedBands)] shl lm
        var target = baseTarget
        if (c == 2) {
            // At most this fraction of the bits is saved when the signal is mono.
            val codedStereoBands = minOf(intensity, codedBands)
            val codedStereoDof = (E_BANDS[codedStereoBands] shl lm) - codedStereoBands
            val maxFrac = 0.8f * codedStereoDof / codedBins.toFloat()
            val saving = min16(stereoSaving, 1f)
            target -= min16(maxFrac * target, (saving - 0.1f) * (codedStereoDof shl BITRES)).toInt()
        }
        // The dynamic allocation's boost, less its average.
        target += totBoost - (19 shl lm)
        // The transient boost, less its average. The fixed-point build
        // doubles this term; the float build's macro does not.
        val tfCalibration = 0.044f
        target += ((tfEstimate - tfCalibration) * target).toInt()
        run {
            val bins = E_BANDS[NB_E_BANDS - 2] shl lm
            var floorDepth = ((c * bins shl BITRES) * maxDepth).toInt()
            floorDepth = maxOf(floorDepth, target shr 2)
            target = minOf(target, floorDepth)
        }
        // Constrained variable bit rate cannot hold a higher rate for long.
        if (constrainedVbr) target = baseTarget + (0.67f * (target - baseTarget)).toInt()
        if (tfEstimate < 0.2f) {
            val amount = 0.0000031f * maxOf(0, minOf(32000, 96000 - bitrate))
            val tvbrFactor = temporalVbr * amount
            target += (tvbrFactor * target).toInt()
        }
        // Never more than double the rate.
        return minOf(2 * baseTarget, target)
    }

    companion object {
        /** `OPUS_BITRATE_MAX`: no target, so every packet takes all the bytes it is given. */
        const val BITRATE_MAX: Int = -1

        private const val BITRES = RangeDecoder.BITRES
        private const val NB_E_BANDS = 21
        private const val EFF_E_BANDS = 21
        private const val OVERLAP = 120
        private const val MAX_LM = 3
        private const val SHORT_MDCT_SIZE = CeltBands.SHORT_MDCT_SIZE
        private const val COMBFILTER_MAXPERIOD = 1024
        private const val SIG_SCALE = 32768f
        private const val EPSILON = 1e-15f

        private val E_BANDS = CeltModeTables.E_BANDS
        private val WINDOW = CeltWindowTable.WINDOW

        private val INTENSITY_THRESHOLDS = floatArrayOf(
            1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 16f, 24f, 36f, 44f, 50f, 56f, 62f, 67f, 72f, 79f, 88f, 106f, 134f,
        )
        private val INTENSITY_HYSTERESIS = floatArrayOf(
            1f, 1f, 1f, 1f, 1f, 1f, 1f, 2f, 2f, 2f, 2f, 2f, 2f, 2f, 3f, 3f, 4f, 5f, 6f, 8f, 8f,
        )

        /** Six times 64 over the index, trained on real data. */
        private val INV_TABLE = intArrayOf(
            255, 255, 156, 110, 86, 70, 59, 51, 45, 40, 37, 33, 31, 28, 26, 25,
            23, 22, 21, 20, 19, 18, 17, 16, 16, 15, 15, 14, 13, 13, 12, 12,
            12, 12, 11, 11, 11, 10, 10, 10, 9, 9, 9, 9, 9, 9, 8, 8,
            8, 8, 8, 7, 7, 7, 7, 7, 7, 6, 6, 6, 6, 6, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6, 6, 5, 5, 5, 5, 5, 5, 5,
            5, 5, 5, 5, 5, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
            4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 3, 3,
            3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 2,
        )

        private fun maxAbs(x: FloatArray, from: Int, len: Int): Float {
            var maxValue = 0f
            var minValue = 0f
            for (i in from until from + len) {
                maxValue = max16(maxValue, x[i])
                minValue = min16(minValue, x[i])
            }
            return max16(maxValue, -minValue)
        }

        /** `MAX16` as the float build has it: the second argument on a tie or a NaN. */
        private fun max16(a: Float, b: Float): Float = if (a > b) a else b

        /** `MIN16` as the float build has it. */
        private fun min16(a: Float, b: Float): Float = if (a < b) a else b

        private fun medianOf5(x: FloatArray, at: Int): Float {
            var t0: Float
            var t1: Float
            val t2 = x[at + 2]
            var t3: Float
            var t4: Float
            if (x[at] > x[at + 1]) {
                t0 = x[at + 1]
                t1 = x[at]
            } else {
                t0 = x[at]
                t1 = x[at + 1]
            }
            if (x[at + 3] > x[at + 4]) {
                t3 = x[at + 4]
                t4 = x[at + 3]
            } else {
                t3 = x[at + 3]
                t4 = x[at + 4]
            }
            if (t0 > t3) {
                var swap = t0
                t0 = t3
                t3 = swap
                swap = t1
                t1 = t4
                t4 = swap
            }
            return if (t2 > t1) {
                if (t1 < t3) min16(t2, t3) else min16(t4, t1)
            } else {
                if (t2 < t3) min16(t1, t3) else min16(t2, t4)
            }
        }

        private fun medianOf3(x: FloatArray, at: Int): Float {
            val t0: Float
            val t1: Float
            if (x[at] > x[at + 1]) {
                t0 = x[at + 1]
                t1 = x[at]
            } else {
                t0 = x[at]
                t1 = x[at + 1]
            }
            val t2 = x[at + 2]
            return if (t1 < t2) t1 else if (t0 < t2) t2 else t0
        }
    }
}

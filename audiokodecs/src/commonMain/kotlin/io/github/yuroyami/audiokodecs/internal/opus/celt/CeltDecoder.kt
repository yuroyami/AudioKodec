package io.github.yuroyami.audiokodecs.internal.opus.celt

import io.github.yuroyami.audiokodecs.CorruptStreamException
import io.github.yuroyami.audiokodecs.internal.opus.RangeDecoder

/**
 * Decodes the CELT layer of an Opus stream: the transform codec that carries
 * music, alone at wide bandwidths and above SILK in the hybrid mode.
 *
 * A frame is decoded as band energies first, then the bit allocation both ends
 * derive from them, then the band shapes, which the inverse MDCT turns back
 * into samples that overlap the previous frame's tail. A pitch postfilter and
 * a de-emphasis filter finish the job. When a packet is missing, the frame is
 * extrapolated from the decoded history instead, by pitch-periodic linear
 * prediction for a short loss and shaped noise for a long one.
 *
 * Translated from libopus `celt_decoder.c`, float build, without the custom
 * modes or the neural concealment. One instance holds one stream's state, for
 * one or two channels, and always works at 48 kHz internally.
 */
internal class CeltDecoder(
    /** Channels the decoder outputs, one or two. */
    val channels: Int,
    sampleRate: Int,
) {
    /** Every [downsample]th 48 kHz sample is output, for the lower Opus rates. */
    private val downsample: Int = when (sampleRate) {
        48_000 -> 1
        24_000 -> 2
        16_000 -> 3
        12_000 -> 4
        8_000 -> 6
        else -> throw IllegalArgumentException("Opus has no $sampleRate Hz rate")
    }

    /** Channels the stream codes, which a mono stream sets to one under stereo output. */
    var streamChannels: Int = channels

    /** First band to code; the hybrid mode starts above what SILK covers. */
    var startBand: Int = 0

    /** One past the last band to code, which the packet's bandwidth limits. */
    var endBand: Int = EFF_E_BANDS

    /** Whether stereo phase inversion is off, as it is for mono output. */
    var disableInversion: Boolean = channels == 1

    /** The range decoder's state after the last packet, for checking against the encoder's. */
    var finalRange: Long = 0
        private set

    /** Set when a packet decoded with an inconsistency the bitstream cannot hold. */
    var error: Boolean = false

    private var lastPitchIndex = 0
    private var lossDuration = 0
    private var skipPlc = true

    /** The postfilter period of the last frame, which concealment and the Opus glue read. */
    var postfilterPeriod: Int = 0
        private set
    private var postfilterPeriodOld = 0
    private var postfilterGain = 0f
    private var postfilterGainOld = 0f
    private var postfilterTapset = 0
    private var postfilterTapsetOld = 0
    private var prefilterAndFold = false
    private val preemphMem = FloatArray(2)

    /**
     * The decoder's `rng` field: the noise seed the band decoder and
     * concealment draw from, which is the range decoder's final state of the
     * previous packet.
     */
    private var finalRangeSeed: Int = 0

    private val decodeMem = Array(channels) { FloatArray(DECODE_BUFFER_SIZE + OVERLAP) }
    private val lpc = Array(channels) { FloatArray(LPC_ORDER) }
    private val oldBandE = FloatArray(2 * NB_E_BANDS)
    private val oldLogE = FloatArray(2 * NB_E_BANDS)
    private val oldLogE2 = FloatArray(2 * NB_E_BANDS)
    private val backgroundLogE = FloatArray(2 * NB_E_BANDS)

    init {
        reset()
    }

    /** `OPUS_RESET_STATE`: forgets everything about earlier packets. */
    fun reset() {
        finalRange = 0
        finalRangeSeed = 0
        error = false
        lastPitchIndex = 0
        lossDuration = 0
        postfilterPeriod = 0
        postfilterPeriodOld = 0
        postfilterGain = 0f
        postfilterGainOld = 0f
        postfilterTapset = 0
        postfilterTapsetOld = 0
        prefilterAndFold = false
        preemphMem.fill(0f)
        for (mem in decodeMem) mem.fill(0f)
        for (l in lpc) l.fill(0f)
        oldBandE.fill(0f)
        backgroundLogE.fill(0f)
        oldLogE.fill(-28f)
        oldLogE2.fill(-28f)
        skipPlc = true
    }

    /**
     * `celt_decode_with_ec`: decodes [length] bytes of [data] at [offset], or
     * conceals a lost frame when [data] is null or holds at most one byte, into
     * [pcm] at [pcmOffset], interleaved. [frameSize] is in output samples per
     * channel. [range] is the decoder SILK already read from in the hybrid
     * mode, and null to start one on the data. Returns the samples written per
     * channel.
     */
    fun decode(
        data: ByteArray?,
        offset: Int,
        length: Int,
        pcm: FloatArray,
        pcmOffset: Int,
        frameSizeIn: Int,
        rangeIn: RangeDecoder?,
    ): Int {
        val frameSize = frameSizeIn * downsample
        var lm = 0
        while (lm <= MAX_LM) {
            if (SHORT_MDCT_SIZE shl lm == frameSize) break
            lm++
        }
        if (lm > MAX_LM) throw IllegalArgumentException("CELT cannot code a $frameSizeIn-sample frame")
        val m = 1 shl lm
        if (length < 0 || length > 1275) throw CorruptStreamException("a CELT frame of $length bytes is not possible")
        val n = m * SHORT_MDCT_SIZE
        val outStart = DECODE_BUFFER_SIZE - n
        val cc = channels
        val effEnd = minOf(endBand, EFF_E_BANDS)

        if (data == null || length <= 1) {
            decodeLost(n, lm)
            deemphasis(pcm, pcmOffset, n, outStart)
            return frameSize / downsample
        }
        if (lossDuration == 0) skipPlc = false
        val range = rangeIn ?: RangeDecoder(data, offset, length)
        val c = streamChannels
        if (c == 1) {
            for (i in 0 until NB_E_BANDS) oldBandE[i] = maxOf(oldBandE[i], oldBandE[NB_E_BANDS + i])
        }
        var totalBits = length * 8
        var tell = range.tell()
        val silence = when {
            tell >= totalBits -> true
            tell == 1 -> range.decodeBitLogp(15)
            else -> false
        }
        if (silence) {
            // Pretend every bit was read, so nothing more is decoded.
            tell = length * 8
            range.addBits(tell - range.tell())
        }
        var postfilterGainNew = 0f
        var postfilterPitch = 0
        var postfilterTapsetNew = 0
        if (startBand == 0 && tell + 16 <= totalBits) {
            if (range.decodeBitLogp(1)) {
                val octave = range.decodeUniform(6)
                postfilterPitch = (16 shl octave) + range.decodeRawBits(4 + octave) - 1
                val qg = range.decodeRawBits(3)
                if (range.tell() + 2 <= totalBits) postfilterTapsetNew = range.decodeIcdf(TAPSET_ICDF, 2)
                postfilterGainNew = 0.09375f * (qg + 1)
            }
            tell = range.tell()
        }
        val isTransient: Boolean
        if (lm > 0 && tell + 3 <= totalBits) {
            isTransient = range.decodeBitLogp(3)
            tell = range.tell()
        } else {
            isTransient = false
        }
        val shortBlocks = if (isTransient) m else 0
        val intraEnergy = if (tell + 3 <= totalBits) range.decodeBitLogp(3) else false
        if (!intraEnergy && lossDuration != 0) {
            // Energies after a loss are predicted from values the encoder never
            // saw, so they are pulled towards the safe side first.
            for (ch in 0 until 2) {
                val missing = minOf(10, lossDuration shr lm)
                val safety = when (lm) {
                    0 -> 1.5f
                    1 -> 0.5f
                    else -> 0f
                }
                for (i in startBand until endBand) {
                    val at = ch * NB_E_BANDS + i
                    if (oldBandE[at] < maxOf(oldLogE[at], oldLogE2[at])) {
                        var e0 = oldBandE[at]
                        val e1 = oldLogE[at]
                        val e2 = oldLogE2[at]
                        val slope = maxOf(e1 - e0, 0.5f * (e2 - e0))
                        e0 -= maxOf(0f, (1 + missing) * slope)
                        oldBandE[at] = maxOf(-20f, e0)
                    } else {
                        oldBandE[at] = minOf(minOf(oldBandE[at], oldLogE[at]), oldLogE2[at])
                    }
                    oldBandE[at] -= safety
                }
            }
        }
        CeltEnergy.unquantiseCoarse(startBand, endBand, oldBandE, intraEnergy, range, c, lm)

        val tfRes = IntArray(NB_E_BANDS)
        tfDecode(startBand, endBand, isTransient, tfRes, lm, range)
        tell = range.tell()
        var spreadDecision = CeltVq.SPREAD_NORMAL
        if (tell + 4 <= totalBits) spreadDecision = range.decodeIcdf(SPREAD_ICDF, 5)

        val cap = IntArray(NB_E_BANDS)
        CeltAllocation.caps(cap, lm, c)
        val offsets = IntArray(NB_E_BANDS)
        var dynallocLogp = 6
        totalBits = totalBits shl BITRES
        tell = range.tellFraction()
        for (i in startBand until endBand) {
            val width = c * (E_BANDS[i + 1] - E_BANDS[i]) shl lm
            val quanta = minOf(width shl BITRES, maxOf(6 shl BITRES, width))
            var loopLogp = dynallocLogp
            var boost = 0
            while (tell + (loopLogp shl BITRES) < totalBits && boost < cap[i]) {
                val flag = range.decodeBitLogp(loopLogp)
                tell = range.tellFraction()
                if (!flag) break
                boost += quanta
                totalBits -= quanta
                loopLogp = 1
            }
            offsets[i] = boost
            if (boost > 0) dynallocLogp = maxOf(2, dynallocLogp - 1)
        }
        val fineQuant = IntArray(NB_E_BANDS)
        val allocTrim = if (tell + (6 shl BITRES) <= totalBits) range.decodeIcdf(TRIM_ICDF, 7) else 5
        var bits = ((length * 8) shl BITRES) - range.tellFraction() - 1
        val antiCollapseRsv = if (isTransient && lm >= 2 && bits >= (lm + 2) shl BITRES) 1 shl BITRES else 0
        bits -= antiCollapseRsv
        val pulses = IntArray(NB_E_BANDS)
        val finePriority = IntArray(NB_E_BANDS)
        val allocation = CeltAllocation.Result()
        CeltAllocation.compute(
            startBand, endBand, offsets, cap, allocTrim, bits,
            pulses, fineQuant, finePriority, c, lm, range, allocation,
        )
        CeltEnergy.unquantiseFine(startBand, endBand, oldBandE, fineQuant, range, c)

        for (ch in 0 until cc) {
            val mem = decodeMem[ch]
            mem.copyInto(mem, 0, n, DECODE_BUFFER_SIZE + OVERLAP)
        }

        val collapseMasks = IntArray(c * NB_E_BANDS)
        val x = FloatArray(c * n)
        val bands = CeltBands(range)
        finalRangeSeed = bands.decodeAll(
            startBand, endBand, x, c, n, collapseMasks, pulses, shortBlocks, spreadDecision,
            allocation.dualStereo, allocation.intensity, tfRes,
            length * (8 shl BITRES) - antiCollapseRsv, allocation.balance, lm,
            allocation.codedBands, finalRangeSeed, disableInversion,
        )
        val antiCollapseOn = antiCollapseRsv > 0 && range.decodeRawBits(1) != 0
        CeltEnergy.unquantiseFinalise(
            startBand, endBand, oldBandE, fineQuant, finePriority, length * 8 - range.tell(), range, c,
        )
        if (antiCollapseOn) {
            CeltBands.antiCollapse(
                x, collapseMasks, lm, c, n, startBand, endBand, oldBandE, oldLogE, oldLogE2, pulses, finalRangeSeed,
            )
        }
        if (silence) {
            for (i in 0 until c * NB_E_BANDS) oldBandE[i] = -28f
        }
        if (prefilterAndFold) prefilterAndFold(n)
        synthesis(x, outStart, startBand, effEnd, c, cc, isTransient, lm, silence)

        for (ch in 0 until cc) {
            postfilterPeriod = maxOf(postfilterPeriod, CeltFilters.COMBFILTER_MINPERIOD)
            postfilterPeriodOld = maxOf(postfilterPeriodOld, CeltFilters.COMBFILTER_MINPERIOD)
            val mem = decodeMem[ch]
            CeltFilters.comb(
                mem, outStart, mem, outStart, postfilterPeriodOld, postfilterPeriod, SHORT_MDCT_SIZE,
                postfilterGainOld, postfilterGain, postfilterTapsetOld, postfilterTapset, WINDOW, OVERLAP,
            )
            if (lm != 0) {
                CeltFilters.comb(
                    mem, outStart + SHORT_MDCT_SIZE, mem, outStart + SHORT_MDCT_SIZE,
                    postfilterPeriod, postfilterPitch, n - SHORT_MDCT_SIZE,
                    postfilterGain, postfilterGainNew, postfilterTapset, postfilterTapsetNew, WINDOW, OVERLAP,
                )
            }
        }
        postfilterPeriodOld = postfilterPeriod
        postfilterGainOld = postfilterGain
        postfilterTapsetOld = postfilterTapset
        postfilterPeriod = postfilterPitch
        postfilterGain = postfilterGainNew
        postfilterTapset = postfilterTapsetNew
        if (lm != 0) {
            postfilterPeriodOld = postfilterPeriod
            postfilterGainOld = postfilterGain
            postfilterTapsetOld = postfilterTapset
        }

        if (c == 1) oldBandE.copyInto(oldBandE, NB_E_BANDS, 0, NB_E_BANDS)
        if (!isTransient) {
            oldLogE.copyInto(oldLogE2)
            oldBandE.copyInto(oldLogE)
        } else {
            for (i in 0 until 2 * NB_E_BANDS) oldLogE[i] = minOf(oldLogE[i], oldBandE[i])
        }
        val maxBackgroundIncrease = minOf(160, lossDuration + m) * 0.001f
        for (i in 0 until 2 * NB_E_BANDS) {
            backgroundLogE[i] = minOf(backgroundLogE[i] + maxBackgroundIncrease, oldBandE[i])
        }
        for (ch in 0 until 2) {
            for (i in 0 until startBand) {
                oldBandE[ch * NB_E_BANDS + i] = 0f
                oldLogE[ch * NB_E_BANDS + i] = -28f
                oldLogE2[ch * NB_E_BANDS + i] = -28f
            }
            for (i in endBand until NB_E_BANDS) {
                oldBandE[ch * NB_E_BANDS + i] = 0f
                oldLogE[ch * NB_E_BANDS + i] = -28f
                oldLogE2[ch * NB_E_BANDS + i] = -28f
            }
        }
        finalRange = range.finalRange
        finalRangeSeed = finalRange.toInt()
        deemphasis(pcm, pcmOffset, n, outStart)
        lossDuration = 0
        prefilterAndFold = false
        if (range.tell() > 8 * length) throw CorruptStreamException("a CELT frame read past its own end")
        if (range.error) error = true
        return frameSize / downsample
    }

    /** `tf_decode`: which bands change their time-frequency resolution. */
    private fun tfDecode(start: Int, end: Int, isTransient: Boolean, tfRes: IntArray, lm: Int, range: RangeDecoder) {
        var budget = range.storage * 8
        var tell = range.tell()
        var logp = if (isTransient) 2 else 4
        val tfSelectRsv = if (lm > 0 && tell + logp + 1 <= budget) 1 else 0
        budget -= tfSelectRsv
        var tfChanged = 0
        var curr = 0
        val transient = if (isTransient) 1 else 0
        for (i in start until end) {
            if (tell + logp <= budget) {
                curr = curr xor if (range.decodeBitLogp(logp)) 1 else 0
                tell = range.tell()
                tfChanged = tfChanged or curr
            }
            tfRes[i] = curr
            logp = if (isTransient) 4 else 5
        }
        var tfSelect = 0
        if (tfSelectRsv != 0 &&
            TF_SELECT[lm][4 * transient + 0 + tfChanged] != TF_SELECT[lm][4 * transient + 2 + tfChanged]
        ) {
            tfSelect = if (range.decodeBitLogp(1)) 1 else 0
        }
        for (i in start until end) tfRes[i] = TF_SELECT[lm][4 * transient + 2 * tfSelect + tfRes[i]]
    }

    /** `celt_synthesis`: band shapes and energies to time-domain samples in [decodeMem]. */
    private fun synthesis(
        x: FloatArray,
        outStart: Int,
        start: Int,
        effEnd: Int,
        c: Int,
        cc: Int,
        isTransient: Boolean,
        lm: Int,
        silence: Boolean,
    ) {
        val m = 1 shl lm
        val n = SHORT_MDCT_SIZE shl lm
        val freq = FloatArray(n)
        val blocks: Int
        val nb: Int
        val shift: Int
        if (isTransient) {
            blocks = m
            nb = SHORT_MDCT_SIZE
            shift = MAX_LM
        } else {
            blocks = 1
            nb = SHORT_MDCT_SIZE shl lm
            shift = MAX_LM - lm
        }
        if (cc == 2 && c == 1) {
            // A mono stream decoded to stereo: the same spectrum feeds both.
            CeltBands.denormalise(x, 0, freq, 0, oldBandE, 0, start, effEnd, m, downsample, silence)
            val freq2 = decodeMem[1]
            val freq2Offset = outStart + OVERLAP / 2
            freq.copyInto(freq2, freq2Offset, 0, n)
            for (b in 0 until blocks) {
                CeltMdct.backward(freq2, freq2Offset + b, decodeMem[0], outStart + nb * b, WINDOW, OVERLAP, shift, blocks)
            }
            for (b in 0 until blocks) {
                CeltMdct.backward(freq, b, decodeMem[1], outStart + nb * b, WINDOW, OVERLAP, shift, blocks)
            }
        } else if (cc == 1 && c == 2) {
            // A stereo stream decoded to mono: the two spectra are averaged.
            val freq2 = decodeMem[0]
            val freq2Offset = outStart + OVERLAP / 2
            CeltBands.denormalise(x, 0, freq, 0, oldBandE, 0, start, effEnd, m, downsample, silence)
            CeltBands.denormalise(x, n, freq2, freq2Offset, oldBandE, NB_E_BANDS, start, effEnd, m, downsample, silence)
            for (i in 0 until n) freq[i] = 0.5f * freq[i] + 0.5f * freq2[freq2Offset + i]
            for (b in 0 until blocks) {
                CeltMdct.backward(freq, b, decodeMem[0], outStart + nb * b, WINDOW, OVERLAP, shift, blocks)
            }
        } else {
            for (ch in 0 until cc) {
                CeltBands.denormalise(x, ch * n, freq, 0, oldBandE, ch * NB_E_BANDS, start, effEnd, m, downsample, silence)
                for (b in 0 until blocks) {
                    CeltMdct.backward(freq, b, decodeMem[ch], outStart + nb * b, WINDOW, OVERLAP, shift, blocks)
                }
            }
        }
    }

    /**
     * `prefilter_and_fold`: after concealment, undoes the postfilter on the
     * overlap the next real frame will add to, and folds it the way the MDCT
     * expects, so the hand-over is seamless.
     */
    private fun prefilterAndFold(n: Int) {
        val etmp = FloatArray(OVERLAP)
        for (ch in 0 until channels) {
            val mem = decodeMem[ch]
            CeltFilters.comb(
                etmp, 0, mem, DECODE_BUFFER_SIZE - n,
                postfilterPeriodOld, postfilterPeriod, OVERLAP,
                -postfilterGainOld, -postfilterGain, postfilterTapsetOld, postfilterTapset, null, 0,
            )
            for (i in 0 until OVERLAP / 2) {
                mem[DECODE_BUFFER_SIZE - n + i] = WINDOW[i] * etmp[OVERLAP - 1 - i] + WINDOW[OVERLAP - i - 1] * etmp[i]
            }
        }
    }

    /** `celt_decode_lost`: conceals a missing frame of [n] samples. */
    private fun decodeLost(n: Int, lm: Int) {
        val c = channels
        val outStart = DECODE_BUFFER_SIZE - n
        val noiseBased = lossDuration >= 40 || startBand != 0 || skipPlc
        if (noiseBased) {
            val end = endBand
            val effEnd = maxOf(startBand, minOf(end, EFF_E_BANDS))
            val x = FloatArray(c * n)
            for (ch in 0 until c) {
                val mem = decodeMem[ch]
                mem.copyInto(mem, 0, n, DECODE_BUFFER_SIZE + OVERLAP)
            }
            if (prefilterAndFold) prefilterAndFold(n)
            val decay = if (lossDuration == 0) 1.5f else 0.5f
            for (ch in 0 until c) {
                for (i in startBand until end) {
                    val at = ch * NB_E_BANDS + i
                    oldBandE[at] = maxOf(backgroundLogE[at], oldBandE[at] - decay)
                }
            }
            var seed = finalRangeSeed
            for (ch in 0 until c) {
                for (i in startBand until effEnd) {
                    val boffs = n * ch + (E_BANDS[i] shl lm)
                    val blen = (E_BANDS[i + 1] - E_BANDS[i]) shl lm
                    for (j in 0 until blen) {
                        seed = CeltMath.lcgRand(seed)
                        x[boffs + j] = (seed shr 20).toFloat()
                    }
                    CeltVq.renormalise(x, boffs, blen, 1f)
                }
            }
            finalRangeSeed = seed
            synthesis(x, outStart, startBand, effEnd, c, c, false, lm, false)
            prefilterAndFold = false
            skipPlc = true
        } else {
            var fade = 1f
            val pitchIndex: Int
            if (lossDuration == 0) {
                pitchIndex = plcPitchSearch()
                lastPitchIndex = pitchIndex
            } else {
                pitchIndex = lastPitchIndex
                fade = 0.8f
            }
            val excLength = minOf(2 * pitchIndex, MAX_PERIOD)
            val excBuffer = FloatArray(MAX_PERIOD + LPC_ORDER)
            val firTmp = FloatArray(excLength)
            val exc = LPC_ORDER
            for (ch in 0 until c) {
                val buf = decodeMem[ch]
                var s1 = 0f
                for (i in 0 until MAX_PERIOD + LPC_ORDER) {
                    excBuffer[exc + i - LPC_ORDER] = buf[DECODE_BUFFER_SIZE - MAX_PERIOD - LPC_ORDER + i]
                }
                if (lossDuration == 0) {
                    val ac = FloatArray(LPC_ORDER + 1)
                    CeltFilters.autocorr(excBuffer, exc, ac, WINDOW, OVERLAP, LPC_ORDER, MAX_PERIOD)
                    ac[0] *= 1.0001f
                    for (i in 1..LPC_ORDER) ac[i] -= ac[i] * (0.008f * 0.008f) * i * i
                    CeltFilters.lpc(lpc[ch], 0, ac, LPC_ORDER)
                }
                CeltFilters.fir(excBuffer, exc + MAX_PERIOD - excLength, lpc[ch], 0, firTmp, 0, excLength, LPC_ORDER)
                firTmp.copyInto(excBuffer, exc + MAX_PERIOD - excLength, 0, excLength)
                var e1 = 1f
                var e2 = 1f
                val decayLength = excLength shr 1
                for (i in 0 until decayLength) {
                    var e = excBuffer[exc + MAX_PERIOD - decayLength + i]
                    e1 += e * e
                    e = excBuffer[exc + MAX_PERIOD - 2 * decayLength + i]
                    e2 += e * e
                }
                e1 = minOf(e1, e2)
                val decay = CeltMath.sqrt(e1 / e2)
                buf.copyInto(buf, 0, n, DECODE_BUFFER_SIZE)
                val extrapolationOffset = MAX_PERIOD - pitchIndex
                val extrapolationLen = n + OVERLAP
                var attenuation = fade * decay
                var j = 0
                for (i in 0 until extrapolationLen) {
                    if (j >= pitchIndex) {
                        j -= pitchIndex
                        attenuation *= decay
                    }
                    buf[DECODE_BUFFER_SIZE - n + i] = attenuation * excBuffer[exc + extrapolationOffset + j]
                    val tmp = buf[DECODE_BUFFER_SIZE - MAX_PERIOD - n + extrapolationOffset + j]
                    s1 += tmp * tmp
                    j++
                }
                val lpcMem = FloatArray(LPC_ORDER) { buf[DECODE_BUFFER_SIZE - n - 1 - it] }
                CeltFilters.iir(
                    buf, DECODE_BUFFER_SIZE - n, lpc[ch], 0, buf, DECODE_BUFFER_SIZE - n,
                    extrapolationLen, LPC_ORDER, lpcMem,
                )
                var s2 = 0f
                for (i in 0 until extrapolationLen) {
                    val tmp = buf[DECODE_BUFFER_SIZE - n + i]
                    s2 += tmp * tmp
                }
                if (!(s1 > 0.2f * s2)) {
                    for (i in 0 until extrapolationLen) buf[DECODE_BUFFER_SIZE - n + i] = 0f
                } else if (s1 < s2) {
                    val ratio = CeltMath.sqrt((s1 + 1) / (s2 + 1))
                    for (i in 0 until OVERLAP) {
                        val tmpG = 1f - WINDOW[i] * (1f - ratio)
                        buf[DECODE_BUFFER_SIZE - n + i] = tmpG * buf[DECODE_BUFFER_SIZE - n + i]
                    }
                    for (i in OVERLAP until extrapolationLen) {
                        buf[DECODE_BUFFER_SIZE - n + i] = ratio * buf[DECODE_BUFFER_SIZE - n + i]
                    }
                }
            }
            prefilterAndFold = true
        }
        lossDuration = minOf(10_000, lossDuration + (1 shl lm))
    }

    /** `celt_plc_pitch_search`. */
    private fun plcPitchSearch(): Int {
        val lpPitch = FloatArray(DECODE_BUFFER_SIZE shr 1)
        CeltFilters.pitchDownsample(decodeMem, IntArray(channels), lpPitch, DECODE_BUFFER_SIZE, channels)
        val index = CeltFilters.pitchSearch(
            lpPitch, PLC_PITCH_LAG_MAX shr 1, lpPitch, 0,
            DECODE_BUFFER_SIZE - PLC_PITCH_LAG_MAX, PLC_PITCH_LAG_MAX - PLC_PITCH_LAG_MIN,
        )
        return PLC_PITCH_LAG_MAX - index
    }

    /**
     * `deemphasis`: undoes the encoder's pre-emphasis and, below 48 kHz,
     * keeps every [downsample]th sample, writing interleaved output.
     */
    private fun deemphasis(pcm: FloatArray, pcmOffset: Int, n: Int, outStart: Int) {
        val coef0 = PREEMPH
        val cc = channels
        if (downsample == 1 && cc == 2) {
            val x0 = decodeMem[0]
            val x1 = decodeMem[1]
            var m0 = preemphMem[0]
            var m1 = preemphMem[1]
            for (j in 0 until n) {
                val tmp0 = x0[outStart + j] + VERY_SMALL + m0
                val tmp1 = x1[outStart + j] + VERY_SMALL + m1
                m0 = coef0 * tmp0
                m1 = coef0 * tmp1
                pcm[pcmOffset + 2 * j] = tmp0 * (1 / SIG_SCALE)
                pcm[pcmOffset + 2 * j + 1] = tmp1 * (1 / SIG_SCALE)
            }
            preemphMem[0] = m0
            preemphMem[1] = m1
            return
        }
        val nd = n / downsample
        val scratch = FloatArray(n)
        for (ch in 0 until cc) {
            val x = decodeMem[ch]
            var mem = preemphMem[ch]
            if (downsample > 1) {
                for (j in 0 until n) {
                    val tmp = x[outStart + j] + VERY_SMALL + mem
                    mem = coef0 * tmp
                    scratch[j] = tmp
                }
                for (j in 0 until nd) pcm[pcmOffset + j * cc + ch] = scratch[j * downsample] * (1 / SIG_SCALE)
            } else {
                for (j in 0 until n) {
                    val tmp = x[outStart + j] + VERY_SMALL + mem
                    mem = coef0 * tmp
                    pcm[pcmOffset + j * cc + ch] = tmp * (1 / SIG_SCALE)
                }
            }
            preemphMem[ch] = mem
        }
    }

    companion object {
        private const val BITRES = RangeDecoder.BITRES
        private const val DECODE_BUFFER_SIZE = 2048
        private const val MAX_PERIOD = 1024
        private const val LPC_ORDER = 24
        private const val PLC_PITCH_LAG_MAX = 720
        private const val PLC_PITCH_LAG_MIN = 100
        private const val NB_E_BANDS = 21
        private const val EFF_E_BANDS = 21
        private const val OVERLAP = 120
        private const val MAX_LM = 3
        private const val SHORT_MDCT_SIZE = CeltBands.SHORT_MDCT_SIZE
        private const val PREEMPH = 0.85000610f
        private const val VERY_SMALL = 1e-30f
        private const val SIG_SCALE = 32768f
        private val E_BANDS = CeltModeTables.E_BANDS
        private val WINDOW = CeltWindowTable.WINDOW
        private val TRIM_ICDF = intArrayOf(126, 124, 119, 109, 87, 41, 19, 9, 4, 2, 0)
        private val SPREAD_ICDF = intArrayOf(25, 23, 2, 0)
        private val TAPSET_ICDF = intArrayOf(2, 1, 0)
        private val TF_SELECT = arrayOf(
            intArrayOf(0, -1, 0, -1, 0, -1, 0, -1),
            intArrayOf(0, -1, 0, -2, 1, 0, 1, -1),
            intArrayOf(0, -2, 0, -3, 2, 0, 1, -1),
            intArrayOf(0, -2, 0, -3, 3, 0, 1, -1),
        )
    }
}

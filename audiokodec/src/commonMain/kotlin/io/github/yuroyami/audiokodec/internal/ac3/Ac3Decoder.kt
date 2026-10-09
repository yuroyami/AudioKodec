package io.github.yuroyami.audiokodec.internal.ac3

import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.internal.bits.BitReaderMsb
import io.github.yuroyami.audiokodec.internal.dsp.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Decodes AC-3 syncframes to float PCM, written from ATSC A/52:2012: the bit
 * stream syntax of section 5 and the decoding of section 7.
 *
 * Each syncframe holds six audio blocks of 256 samples per channel. A block
 * carries every channel's spectrum as exponents and mantissas: the exponents
 * are sent differentially in groups (7.1), the decoder works out from them
 * and a handful of parameters how many bits each mantissa has (the
 * parametric bit allocation of 7.2, defined exactly in integers so that
 * encoder and decoder agree), and the mantissas follow, grouped for the
 * smallest quantisers (7.3). High frequencies of several channels may share
 * one coupling channel, scaled back out per band (7.4); in stereo, bands may
 * be coded as sum and difference (7.5). A dynamic range gain may scale each
 * block (7.7). The inverse transform, window and overlap of 7.9 then give the
 * samples, with a pair of half-length transforms for a block that switched
 * for a transient.
 *
 * The standard defines the decoder in floating point, and so does this one.
 * Bit allocation is integer arithmetic, exactly as the standard gives it.
 * The output is every channel of the stream; nothing is mixed down, and the
 * dialogue level and the heavy compression word are left to the caller,
 * since neither changes the decoded signal by default.
 */
internal class Ac3Decoder(
    private val shape: Ac3Frame,
    private val tolerant: Boolean,
    private val probe: Ac3Probe? = null,
) {

    /** 2^[Ac3Probe.coefficientBits], or 0 for full precision. */
    private val gridScale: Double = probe?.coefficientBits?.takeIf { it > 0 }?.let { (1L shl it).toDouble() } ?: 0.0

    private val nfchans = shape.fullBandwidthChannels
    private val acmod = shape.codingMode
    private val lfeon = shape.hasLfe

    /** Channel indices: 0 to 4 for the full bandwidth channels, then these two. */
    private val lfe = 5
    private val cpl = 6

    // State carried from block to block within a syncframe.
    private val exps = Array(7) { IntArray(256) }
    private val bap = Array(7) { IntArray(256) }
    private val coefficients = Array(6) { FloatArray(256) }
    private val couplingValues = FloatArray(256)
    private val endmant = IntArray(5)
    private val blksw = BooleanArray(5)
    private val dithflag = BooleanArray(5)
    private val chincpl = BooleanArray(5)
    private val dynamicGain = FloatArray(2)
    private var cplinu = false
    private var phsflginu = false
    private var cplbegf = 0
    private var cplendf = 0
    private var cplstrtmant = 0
    private var cplendmant = 0
    /** The coupling band each coupling sub-band belongs to. */
    private val subbandBand = IntArray(18)
    private var ncplbnd = 0
    private val cplco = Array(5) { FloatArray(18) }
    private val cplcoValid = BooleanArray(5)
    private val phsflg = BooleanArray(18)
    private val rematflg = BooleanArray(4)
    private var nrematbd = 0
    private val exponentsValid = BooleanArray(7)
    /** The end bin the last exponents sent for each full bandwidth channel reached. */
    private val exponentEnd = IntArray(5)
    private var sdcycod = 0
    private var fdcycod = 0
    private var sgaincod = 0
    private var dbpbcod = 0
    private var floorcod = 0
    private var csnroffst = 0
    /** Fine SNR offset and fast gain code, by channel index. */
    private val fsnroffst = IntArray(7)
    private val fgaincod = IntArray(7)
    private var cplfleak = 0
    private var cplsleak = 0
    /** Whether delta bit allocation applies, by channel index, and whether that is known at all. */
    private val deltaActive = BooleanArray(7)
    private val deltaKnown = BooleanArray(7)
    /** Whether segments have been received for the channel in this syncframe, which a strategy of reuse applies again. */
    private val deltaStored = BooleanArray(7)

    /** How many bands the stored segments of each channel span in all, offsets included. */
    private val deltaSpan = IntArray(7)
    private val deltnseg = IntArray(7)
    private val deltoffst = Array(7) { IntArray(8) }
    private val deltlen = Array(7) { IntArray(8) }
    private val deltba = Array(7) { IntArray(8) }

    // The grouped mantissas of 7.3.5, shared across the exponent sets of a block.
    private val group1 = IntArray(3)
    private var group1Left = 0
    private val group2 = IntArray(3)
    private var group2Left = 0
    private val group4 = IntArray(2)
    private var group4Left = 0

    /** The second half of each channel's last block, waiting to be overlapped. */
    private val delay = Array(6) { FloatArray(256) }
    private val transform = Transform()
    private val samples = FloatArray(512)

    /**
     * Dither for zero-bit mantissas. Section 7.3.4 asks only for a reasonably
     * random sequence; this one restarts with every syncframe, so a syncframe
     * decodes to the same samples whatever was decoded before it, and a seek
     * is exact.
     */
    private var seed = 0

    /** Where each channel, in the order the bit stream carries them, goes in AudioKodec's order. */
    private val outputPosition: IntArray = run {
        val stored = shape.storedOrder
        val sorted = stored.sortedBy { it.ordinal }
        IntArray(stored.size) { sorted.indexOf(stored[it]) }
    }

    fun reset() {
        for (d in delay) d.fill(0f)
    }

    /**
     * Decodes the syncframe in [length] bytes of [bytes] from [offset] into
     * [out], 1536 frames interleaved in AudioKodec's channel order.
     */
    fun decode(bytes: ByteArray, offset: Int, length: Int, out: FloatArray) {
        val frame = Ac3Frame.parse(bytes, offset) ?: throw CorruptStreamException("an AC-3 syncframe header is damaged")
        if (!frame.matches(shape)) throw CorruptStreamException("an AC-3 stream changed its sample rate or channels")
        // A container may pad its sample past the syncframe; the syncframe is the size its header states.
        val size = frame.frameBytes
        if (length < size) throw CorruptStreamException("an AC-3 syncframe of $length bytes is shorter than the $size its header states")
        // As for MPEG audio, a tolerant decode takes the frame as stored: what the check sees damaged is often the check.
        if (!tolerant && !crcHolds(bytes, offset, size)) throw CorruptStreamException("an AC-3 syncframe fails its CRC check")
        val bits = BitReaderMsb(bytes, offset, size)
        startFrame()
        seed = 1
        try {
            readBitStreamInformation(bits)
            for (block in 0 until 6) {
                readBlock(bits, block)
                synthesise(out, block)
            }
        } catch (_: TruncatedStreamException) {
            throw CorruptStreamException("an AC-3 syncframe's audio blocks run past its end")
        }
    }

    /**
     * The check of 7.10.1: crc2 covers everything after the sync word, so
     * the register is zero at the end when it holds, and a good crc2 says the
     * first 5/8 that crc1 covers is sound too.
     */
    private fun crcHolds(bytes: ByteArray, offset: Int, length: Int): Boolean {
        var crc = 0
        for (i in 2 until length) crc = crc16(crc, bytes[offset + i].toInt() and 0xFF)
        return crc == 0
    }

    /** Block 0 carries everything needed to begin (5.5), so nothing from the last syncframe may be reused. */
    private fun startFrame() {
        cplinu = false
        chincpl.fill(false)
        exponentsValid.fill(false)
        cplcoValid.fill(false)
        deltaKnown.fill(false)
        deltaActive.fill(false)
        deltaStored.fill(false)
        cplfleak = -1
        nrematbd = 0
        exponentEnd.fill(-1)
    }

    private fun readBitStreamInformation(bits: BitReaderMsb) {
        bits.skipBits(16 + 16 + 2 + 6) // syncword, crc1, fscod, frmsizecod
        bits.skipBits(5 + 3 + 3) // bsid, bsmod, acmod
        if (acmod and 1 != 0 && acmod != 1) bits.skipBits(2) // cmixlev
        if (acmod and 4 != 0) bits.skipBits(2) // surmixlev
        if (acmod == 2) bits.skipBits(2) // dsurmod
        bits.skipBits(1) // lfeon
        repeat(if (acmod == 0) 2 else 1) {
            bits.skipBits(5) // dialnorm
            if (bits.readBit() == 1) bits.skipBits(8) // compr
            if (bits.readBit() == 1) bits.skipBits(8) // langcod
            if (bits.readBit() == 1) bits.skipBits(5 + 2) // mixlevel, roomtyp
        }
        bits.skipBits(2) // copyrightb, origbs
        // timecod1 and timecod2, or Annex D's xbsi1 and xbsi2 in the same bits.
        if (bits.readBit() == 1) bits.skipBits(14)
        if (bits.readBit() == 1) bits.skipBits(14)
        if (bits.readBit() == 1) bits.skipBits((bits.readBits(6) + 1) * 8) // addbsi
    }

    // Section 5.3.3: one audio block.

    private fun readBlock(bits: BitReaderMsb, block: Int) {
        for (ch in 0 until nfchans) blksw[ch] = bits.readBit() == 1
        probe?.onDitherFlags?.invoke(bits.bitPosition, nfchans)
        for (ch in 0 until nfchans) dithflag[ch] = bits.readBit() == 1
        for (i in 0 until if (acmod == 0) 2 else 1) {
            if (bits.readBit() == 1) {
                probe?.census?.let { it.dynamicRangeWords++ }
                dynamicGain[i] = dynamicRangeGain(bits.readBits(8))
            } else if (block == 0) {
                dynamicGain[i] = 1f
            }
        }

        readCouplingStrategy(bits, block)
        if (cplinu) readCouplingCoordinates(bits)
        if (acmod == 2) readRematrixing(bits, block)
        probe?.census?.let { census ->
            census.blocks++
            if (blksw.take(nfchans).any { it }) census.shortBlocks++
            if (cplinu) census.coupledBlocks++
            if (acmod == 2 && (0 until nrematbd).any { rematflg[it] }) census.rematrixedBlocks++
            if (cplinu && acmod == 2 && phsflginu && (0 until ncplbnd).any { phsflg[it] }) census.phaseFlagBlocks++
            if (dithflag.take(nfchans).any { it }) census.ditheredBlocks++
        }
        readExponents(bits, block)
        readBitAllocation(bits, block)
        if (bits.readBit() == 1) {
            probe?.census?.let { it.skipFields++ }
            bits.skipBits(bits.readBits(9) * 8) // skipfld
        }

        allocateBits()
        readMantissas(bits)
        if (cplinu) decouple()
        if (acmod == 2) rematrix()
        applyDynamicRange()
    }

    /** The gain a `dynrng` word states, 7.7.1.2: a shift by its top three bits, signed, and a fraction by the rest. */
    private fun dynamicRangeGain(word: Int): Float {
        val shift = ((word ushr 5) xor 4) - 4 + 1
        val fraction = (32 + (word and 0x1F)) / 64f
        return fraction * if (shift >= 0) (1 shl shift).toFloat() else 1f / (1 shl -shift)
    }

    private fun readCouplingStrategy(bits: BitReaderMsb, block: Int) {
        val wasInUse = cplinu
        if (bits.readBit() == 1) {
            cplinu = bits.readBit() == 1
            if (cplinu) {
                if (acmod < 2) throw CorruptStreamException("an AC-3 block couples a mono or dual mono stream")
                var coupled = 0
                for (ch in 0 until nfchans) {
                    chincpl[ch] = bits.readBit() == 1
                    if (chincpl[ch]) coupled++
                }
                if (coupled < 2) throw CorruptStreamException("an AC-3 block couples fewer than two channels")
                phsflginu = acmod == 2 && bits.readBit() == 1
                val begin = bits.readBits(4)
                val end = bits.readBits(4)
                if (begin > end + 2) throw CorruptStreamException("an AC-3 coupling range ends before it begins")
                val changed = begin != cplbegf || end != cplendf
                cplbegf = begin
                cplendf = end
                val nsubbands = 3 + cplendf - cplbegf
                var band = 0
                subbandBand[cplbegf] = 0
                var structureChanged = false
                for (s in cplbegf + 1 until cplbegf + nsubbands) {
                    val joined = bits.readBit() == 1
                    if (!joined) band++
                    if (subbandBand[s] != band) structureChanged = true
                    subbandBand[s] = band
                }
                ncplbnd = band + 1
                cplstrtmant = 37 + 12 * cplbegf
                cplendmant = 37 + 12 * (cplendf + 3)
                if (changed || structureChanged || !wasInUse) {
                    cplcoValid.fill(false)
                    if (changed || !wasInUse) exponentsValid[cpl] = false
                }
            } else {
                chincpl.fill(false)
            }
        } else if (block == 0) {
            throw CorruptStreamException("an AC-3 syncframe's first block has no coupling strategy")
        }
        if (cplinu && !wasInUse) {
            // Coupling switched on: nothing about it may be reused (5.5).
            cplcoValid.fill(false)
            exponentsValid[cpl] = false
            deltaKnown[cpl] = false
            cplfleak = -1
        }
        if (!cplinu) for (ch in 0 until nfchans) chincpl[ch] = false
    }

    private fun readCouplingCoordinates(bits: BitReaderMsb) {
        var anyNew = false
        for (ch in 0 until nfchans) {
            if (!chincpl[ch]) continue
            if (bits.readBit() == 1) {
                if (ch < 2) anyNew = true
                val master = 3 * bits.readBits(2)
                if (master != 0) probe?.census?.let { it.masterCoordinates++ }
                for (band in 0 until ncplbnd) {
                    val exponent = bits.readBits(4)
                    val mantissa = bits.readBits(4)
                    val temp = if (exponent == 15) mantissa / 16f else (mantissa + 16) / 32f
                    cplco[ch][band] = onGrid(temp * pow2(-(exponent + master)) * 8f)
                }
                cplcoValid[ch] = true
            } else if (!cplcoValid[ch]) {
                throw CorruptStreamException("an AC-3 block reuses coupling coordinates it never had")
            }
        }
        // In 2/0 mode, phase flags come with new coordinates for either channel.
        if (acmod == 2 && phsflginu && anyNew) {
            for (band in 0 until ncplbnd) phsflg[band] = bits.readBit() == 1
        }
    }

    private fun readRematrixing(bits: BitReaderMsb, block: Int) {
        val bands = when {
            !cplinu || cplbegf > 2 -> 4
            cplbegf > 0 -> 3
            else -> 2
        }
        if (bits.readBit() == 1) {
            for (b in 0 until bands) rematflg[b] = bits.readBit() == 1
            nrematbd = bands
        } else if (block == 0 || bands != nrematbd) {
            throw CorruptStreamException("an AC-3 block reuses rematrixing flags it never had")
        }
    }

    private fun readExponents(bits: BitReaderMsb, block: Int) {
        val cplexpstr = if (cplinu) bits.readBits(2) else 0
        val chexpstr = IntArray(nfchans) { bits.readBits(2) }
        val lfeexpstr = if (lfeon) bits.readBit() else 0
        for (ch in 0 until nfchans) {
            if (chincpl[ch]) {
                endmant[ch] = cplstrtmant
            } else if (chexpstr[ch] != 0) {
                val code = bits.readBits(6)
                if (code > 60) throw CorruptStreamException("an AC-3 channel bandwidth code of $code")
                endmant[ch] = 37 + 3 * (code + 12)
            }
        }
        if (cplinu) {
            if (cplexpstr != 0) {
                val absolute = bits.readBits(4) shl 1
                val groups = (cplendmant - cplstrtmant) / (3 shl (cplexpstr - 1))
                decodeExponents(bits, exps[cpl], cplstrtmant, absolute, groups, cplexpstr, coupling = true)
                exponentsValid[cpl] = true
            } else if (!exponentsValid[cpl]) {
                throw CorruptStreamException("an AC-3 block reuses coupling exponents it never had")
            }
        }
        for (ch in 0 until nfchans) {
            if (chexpstr[ch] != 0) {
                val absolute = bits.readBits(4)
                val groups = when (chexpstr[ch]) {
                    1 -> (endmant[ch] - 1) / 3
                    2 -> (endmant[ch] - 1 + 3) / 6
                    else -> (endmant[ch] - 1 + 9) / 12
                }
                decodeExponents(bits, exps[ch], 0, absolute, groups, chexpstr[ch], coupling = false)
                bits.skipBits(2) // gainrng
                exponentsValid[ch] = true
                exponentEnd[ch] = endmant[ch]
            } else if (!exponentsValid[ch] || exponentEnd[ch] != endmant[ch]) {
                // Exponents cannot be reused if the bins they cover changed (5.5).
                throw CorruptStreamException("an AC-3 block reuses exponents it cannot")
            }
        }
        if (lfeon) {
            if (lfeexpstr != 0) {
                decodeExponents(bits, exps[lfe], 0, bits.readBits(4), 2, 1, coupling = false)
                exponentsValid[lfe] = true
            } else if (!exponentsValid[lfe]) {
                throw CorruptStreamException("an AC-3 block reuses LFE exponents it never had")
            }
        }
    }

    /**
     * Section 7.1.3: an absolute exponent, then [groups] 7-bit words of three
     * mapped differences each, every difference shared by 1, 2 or 4 bins as
     * the strategy says. The coupling channel's absolute exponent is only a
     * reference, so its first decoded exponent lands on [start].
     */
    private fun decodeExponents(bits: BitReaderMsb, into: IntArray, start: Int, absolute: Int, groups: Int, strategy: Int, coupling: Boolean) {
        val size = 1 shl (strategy - 1)
        var previous = absolute
        var bin = start
        if (!coupling) into[bin++] = absolute
        for (g in 0 until groups) {
            val word = bits.readBits(7)
            if (word > 124) throw CorruptStreamException("an AC-3 exponent group of $word")
            val mapped = intArrayOf(word / 25, (word % 25) / 5, word % 5)
            for (m in mapped) {
                previous += m - 2
                if (previous !in 0..24) throw CorruptStreamException("an AC-3 exponent of $previous")
                repeat(size) { into[bin++] = previous }
            }
        }
    }

    private fun readBitAllocation(bits: BitReaderMsb, block: Int) {
        if (bits.readBit() == 1) {
            sdcycod = bits.readBits(2)
            fdcycod = bits.readBits(2)
            sgaincod = bits.readBits(2)
            dbpbcod = bits.readBits(2)
            floorcod = bits.readBits(3)
        } else if (block == 0) {
            throw CorruptStreamException("an AC-3 syncframe's first block has no bit allocation parameters")
        }
        if (bits.readBit() == 1) {
            csnroffst = bits.readBits(6)
            if (cplinu) {
                fsnroffst[cpl] = bits.readBits(4)
                fgaincod[cpl] = bits.readBits(3)
            }
            for (ch in 0 until nfchans) {
                fsnroffst[ch] = bits.readBits(4)
                fgaincod[ch] = bits.readBits(3)
            }
            if (lfeon) {
                fsnroffst[lfe] = bits.readBits(4)
                fgaincod[lfe] = bits.readBits(3)
            }
        } else if (block == 0) {
            throw CorruptStreamException("an AC-3 syncframe's first block has no SNR offsets")
        }
        if (cplinu) {
            if (bits.readBit() == 1) {
                cplfleak = bits.readBits(3)
                cplsleak = bits.readBits(3)
            } else if (cplfleak < 0) {
                throw CorruptStreamException("an AC-3 block reuses coupling leak values it never had")
            }
        }
        if (bits.readBit() == 1) {
            val cplStrategy = if (cplinu) bits.readBits(2) else 2
            val strategies = IntArray(nfchans) { bits.readBits(2) }
            if (cplinu) readDelta(bits, cpl, cplStrategy)
            for (ch in 0 until nfchans) readDelta(bits, ch, strategies[ch])
            probe?.census?.let { if (deltaActive.any { active -> active }) it.deltaBlocks++ }
        } else if (block == 0) {
            deltaActive.fill(false)
            deltaKnown.fill(true)
        }
        if (cplinu && !deltaKnown[cpl]) {
            throw CorruptStreamException("an AC-3 block reuses coupling delta bit allocation it never had")
        }
    }

    /** Section 5.4.3.48: 0 reuses the last state, 1 sends new segments, 2 turns delta bit allocation off. */
    private fun readDelta(bits: BitReaderMsb, ch: Int, strategy: Int) {
        when (strategy) {
            1 -> {
                deltnseg[ch] = bits.readBits(3)
                var band = 0
                for (seg in 0..deltnseg[ch]) {
                    deltoffst[ch][seg] = bits.readBits(5)
                    deltlen[ch][seg] = bits.readBits(4)
                    deltba[ch][seg] = bits.readBits(3)
                    band += deltoffst[ch][seg] + deltlen[ch][seg]
                }
                deltaSpan[ch] = band
                deltaActive[ch] = true
                deltaKnown[ch] = true
                deltaStored[ch] = true
            }
            // Section 7.2.2.6 applies the stored segments whenever the strategy is new or reuse, so reuse
            // after a block that turned delta bit allocation off brings the last new segments back.
            0 -> {
                if (!deltaKnown[ch]) throw CorruptStreamException("an AC-3 block reuses delta bit allocation it never had")
                deltaActive[ch] = deltaStored[ch]
            }
            2 -> {
                deltaActive[ch] = false
                deltaKnown[ch] = true
            }
            else -> throw CorruptStreamException("an AC-3 delta bit allocation strategy is reserved")
        }
    }

    // Section 7.2: the parametric bit allocation.

    private val allocator = Ac3BitAllocator(shape.sampleRateCode)

    private fun allocateBits() {
        var allZero = csnroffst == 0
        if (allZero) {
            for (ch in 0 until nfchans) if (fsnroffst[ch] != 0) allZero = false
            if (cplinu && fsnroffst[cpl] != 0) allZero = false
            if (lfeon && fsnroffst[lfe] != 0) allZero = false
        }
        for (ch in 0 until nfchans) allocate(ch, 0, endmant[ch], allZero)
        if (cplinu) allocate(cpl, cplstrtmant, cplendmant, allZero)
        if (lfeon) allocate(lfe, 0, 7, allZero)
    }

    private fun allocate(ch: Int, start: Int, end: Int, allZero: Boolean) {
        if (allZero) {
            bap[ch].fill(0, start, end)
            return
        }
        val delta = ch != lfe && deltaActive[ch]
        // The coupling channel's bands count from the first one it covers, and segments kept from before a new
        // coupling strategy can reach past the last band of the new one.
        val firstBand = if (ch == cpl) Ac3Tables.MASK[cplstrtmant] else 0
        if (delta && firstBand + deltaSpan[ch] > 50) {
            throw CorruptStreamException("an AC-3 delta bit allocation runs past the last band")
        }
        allocator.allocate(
            exps[ch], start, end, bap[ch],
            sdcycod, fdcycod, sgaincod, dbpbcod, floorcod, fgaincod[ch],
            snrOffset = (((csnroffst - 15) shl 4) + fsnroffst[ch]) shl 2,
            fastLeak = (cplfleak shl 8) + 768, slowLeak = (cplsleak shl 8) + 768,
            deltaSegments = if (delta) deltnseg[ch] + 1 else 0,
            deltaOffsets = deltoffst[ch], deltaLengths = deltlen[ch], deltaCodes = deltba[ch],
            deltaFirstBand = firstBand,
        )
    }

    // Section 7.3: mantissas.

    private fun readMantissas(bits: BitReaderMsb) {
        group1Left = 0
        group2Left = 0
        group4Left = 0
        var gotCoupling = false
        for (ch in 0 until nfchans) {
            val values = coefficients[ch]
            readChannel(bits, ch, 0, endmant[ch], values, dithflag[ch])
            values.fill(0f, endmant[ch], 256)
            if (cplinu && chincpl[ch] && !gotCoupling) {
                readChannel(bits, cpl, cplstrtmant, cplendmant, couplingValues, dither = false)
                gotCoupling = true
            }
        }
        if (lfeon) {
            readChannel(bits, lfe, 0, 7, coefficients[lfe], dither = false)
            coefficients[lfe].fill(0f, 7, 256)
        }
    }

    private fun readChannel(bits: BitReaderMsb, ch: Int, start: Int, end: Int, into: FloatArray, dither: Boolean) {
        val bap = bap[ch]
        val exp = exps[ch]
        for (bin in start until end) {
            val mantissa = when (val b = bap[bin]) {
                0 -> if (dither) nextDither() else 0f
                1 -> {
                    if (group1Left == 0) {
                        val code = bits.readBits(5)
                        if (code > 26) throw CorruptStreamException("an AC-3 3-level mantissa group of $code")
                        group1[0] = code / 9
                        group1[1] = (code % 9) / 3
                        group1[2] = code % 3
                        group1Left = 3
                    }
                    (group1[3 - group1Left--] - 1) * (2f / 3f)
                }
                2 -> {
                    if (group2Left == 0) {
                        val code = bits.readBits(7)
                        if (code > 124) throw CorruptStreamException("an AC-3 5-level mantissa group of $code")
                        group2[0] = code / 25
                        group2[1] = (code % 25) / 5
                        group2[2] = code % 5
                        group2Left = 3
                    }
                    (group2[3 - group2Left--] - 2) * (2f / 5f)
                }
                3 -> {
                    val code = bits.readBits(3)
                    if (code > 6) throw CorruptStreamException("an AC-3 7-level mantissa of $code")
                    (code - 3) * (2f / 7f)
                }
                4 -> {
                    if (group4Left == 0) {
                        val code = bits.readBits(7)
                        if (code > 120) throw CorruptStreamException("an AC-3 11-level mantissa group of $code")
                        group4[0] = code / 11
                        group4[1] = code % 11
                        group4Left = 2
                    }
                    (group4[2 - group4Left--] - 5) * (2f / 11f)
                }
                5 -> {
                    val code = bits.readBits(4)
                    if (code > 14) throw CorruptStreamException("an AC-3 15-level mantissa of $code")
                    (code - 7) * (2f / 15f)
                }
                else -> {
                    // Asymmetric: a two's complement fraction of qntztab[bap] bits.
                    val width = if (b <= 13) b - 1 else if (b == 14) 14 else 16
                    val raw = bits.readBits(width)
                    val signed = (raw shl (32 - width)) shr (32 - width)
                    signed * pow2(1 - width)
                }
            }
            into[bin] = onGrid(mantissa * pow2(-exp[bin]))
        }
    }

    /** [x] cut down to the grid of [Ac3Probe.coefficientBits], when there is one. */
    private fun onGrid(x: Float): Float = if (gridScale == 0.0) x else (kotlin.math.floor(x * gridScale) / gridScale).toFloat()

    /** A uniform value in -0.707 to 0.707, the scale 7.3.4 calls optimum, from a linear congruential generator. */
    private fun nextDither(): Float {
        seed = seed * 1664525 + 1013904223
        return (seed shr 8) * (0.707f / (1 shl 23))
    }

    // Section 7.4.3: decoupling, with the phase flags of 2/0 mode.

    private fun decouple() {
        for (ch in 0 until nfchans) {
            if (!chincpl[ch]) continue
            val values = coefficients[ch]
            for (s in cplbegf until 3 + cplendf) {
                val band = subbandBand[s]
                val scale = cplco[ch][band]
                // The fixed-point decoder used as a reference negates after it multiplies, which rounds the other way.
                val inverted = acmod == 2 && ch == 1 && phsflginu && phsflg[band]
                for (bin in 37 + 12 * s until 49 + 12 * s) {
                    val value = if (bap[cpl][bin] == 0) {
                        // Dither after decoupling, so each channel's is its own.
                        if (dithflag[ch]) nextDither() * pow2(-exps[cpl][bin]) else 0f
                    } else {
                        couplingValues[bin]
                    }
                    val decoupled = onGrid(value * scale)
                    values[bin] = if (inverted) -decoupled else decoupled
                }
            }
            values.fill(0f, cplendmant, 256)
        }
    }

    // Section 7.5.4: rematrixing.

    private fun rematrix() {
        val left = coefficients[0]
        val right = coefficients[1]
        val top = if (cplinu) cplstrtmant else minOf(endmant[0], endmant[1])
        for (b in 0 until nrematbd) {
            if (!rematflg[b]) continue
            val from = REMATRIX_BANDS[b]
            val to = minOf(REMATRIX_BANDS[b + 1], top)
            for (bin in from until to) {
                val l = left[bin]
                val r = right[bin]
                left[bin] = l + r
                right[bin] = l - r
            }
        }
    }

    private fun applyDynamicRange() {
        for (ch in 0 until nfchans) {
            val gain = if (acmod == 0 && ch == 1) dynamicGain[1] else dynamicGain[0]
            if (gain != 1f) for (bin in 0 until 256) coefficients[ch][bin] *= gain
        }
        if (lfeon) {
            // The standard does not say which of the two programs of a 1+1 stream owns the effects channel; ffmpeg gives it the second's.
            val gain = if (acmod == 0) dynamicGain[1] else dynamicGain[0]
            if (gain != 1f) for (bin in 0 until 7) coefficients[lfe][bin] *= gain
        }
    }

    // Section 7.9: the inverse transform, window and overlap.

    private fun synthesise(out: FloatArray, block: Int) {
        val channels = shape.channels
        for (stored in 0 until channels) {
            val ch = if (stored < nfchans) stored else lfe
            val short = ch < nfchans && blksw[ch]
            transform.inverse(coefficients[ch], short, samples)
            val pending = delay[ch]
            var at = block * 256 * channels + outputPosition[stored]
            for (n in 0 until 256) {
                out[at] = 2f * (samples[n] + pending[n])
                pending[n] = samples[256 + n]
                at += channels
            }
        }
    }

    /** The transforms of 7.9.4, as the standard writes them: an N/4-point complex IFFT between two twiddles. */
    private class Transform {
        private val n = 512
        private val window = Ac3Tables.WINDOW
        private val cos1 = FloatArray(128) { k -> (-cos(2 * PI * (8 * k + 1) / (8 * n))).toFloat() }
        private val sin1 = FloatArray(128) { k -> (-sin(2 * PI * (8 * k + 1) / (8 * n))).toFloat() }
        private val cos2 = FloatArray(64) { k -> (-cos(2 * PI * (8 * k + 1) / (4 * n))).toFloat() }
        private val sin2 = FloatArray(64) { k -> (-sin(2 * PI * (8 * k + 1) / (4 * n))).toFloat() }
        private val fft128 = Fft(128, forward = false)
        private val fft64 = Fft(64, forward = false)
        private val re1 = FloatArray(128)
        private val im1 = FloatArray(128)
        private val re2 = FloatArray(64)
        private val im2 = FloatArray(64)

        /** The 512 windowed samples of one block, from its 256 coefficients. */
        fun inverse(x: FloatArray, short: Boolean, out: FloatArray) {
            if (!short) long(x, out) else short(x, out)
        }

        private fun w(i: Int): Float = if (i < 256) window[i] else window[511 - i]

        private fun long(x: FloatArray, out: FloatArray) {
            for (k in 0 until 128) {
                val a = x[256 - 2 * k - 1]
                val b = x[2 * k]
                re1[k] = a * cos1[k] - b * sin1[k]
                im1[k] = b * cos1[k] + a * sin1[k]
            }
            fft128.transform(re1, im1)
            for (k in 0 until 128) {
                val zr = re1[k]
                val zi = im1[k]
                re1[k] = zr * cos1[k] - zi * sin1[k]
                im1[k] = zi * cos1[k] + zr * sin1[k]
            }
            val yr = re1
            val yi = im1
            for (k in 0 until 64) {
                out[2 * k] = -yi[64 + k] * w(2 * k)
                out[2 * k + 1] = yr[64 - k - 1] * w(2 * k + 1)
                out[128 + 2 * k] = -yr[k] * w(128 + 2 * k)
                out[128 + 2 * k + 1] = yi[128 - k - 1] * w(128 + 2 * k + 1)
                out[256 + 2 * k] = -yr[64 + k] * w(256 - 2 * k - 1)
                out[256 + 2 * k + 1] = yi[64 - k - 1] * w(256 - 2 * k - 2)
                out[384 + 2 * k] = yi[k] * w(128 - 2 * k - 1)
                out[384 + 2 * k + 1] = -yr[128 - k - 1] * w(128 - 2 * k - 2)
            }
        }

        private fun short(x: FloatArray, out: FloatArray) {
            for (k in 0 until 64) {
                // X1 is the even coefficients, X2 the odd.
                val a1 = x[2 * (128 - 2 * k - 1)]
                val b1 = x[2 * (2 * k)]
                re1[k] = a1 * cos2[k] - b1 * sin2[k]
                im1[k] = b1 * cos2[k] + a1 * sin2[k]
                val a2 = x[2 * (128 - 2 * k - 1) + 1]
                val b2 = x[2 * (2 * k) + 1]
                re2[k] = a2 * cos2[k] - b2 * sin2[k]
                im2[k] = b2 * cos2[k] + a2 * sin2[k]
            }
            fft64.transform(re1, im1)
            fft64.transform(re2, im2)
            for (k in 0 until 64) {
                var zr = re1[k]
                var zi = im1[k]
                re1[k] = zr * cos2[k] - zi * sin2[k]
                im1[k] = zi * cos2[k] + zr * sin2[k]
                zr = re2[k]
                zi = im2[k]
                re2[k] = zr * cos2[k] - zi * sin2[k]
                im2[k] = zi * cos2[k] + zr * sin2[k]
            }
            for (k in 0 until 64) {
                out[2 * k] = -im1[k] * w(2 * k)
                out[2 * k + 1] = re1[64 - k - 1] * w(2 * k + 1)
                out[128 + 2 * k] = -re1[k] * w(128 + 2 * k)
                out[128 + 2 * k + 1] = im1[64 - k - 1] * w(128 + 2 * k + 1)
                out[256 + 2 * k] = -re2[k] * w(256 - 2 * k - 1)
                out[256 + 2 * k + 1] = im2[64 - k - 1] * w(256 - 2 * k - 2)
                out[384 + 2 * k] = im2[k] * w(128 - 2 * k - 1)
                out[384 + 2 * k + 1] = -re2[64 - k - 1] * w(128 - 2 * k - 2)
            }
        }
    }

    private companion object {
        /** The rematrixing bands of Tables 7.25 to 7.28: bins 13 to 24, 25 to 36, 37 to 60 and 61 to 252. */
        val REMATRIX_BANDS = intArrayOf(13, 25, 37, 61, 253)

        /** 2 to the power [e], exactly, for the exponents a mantissa can have. */
        fun pow2(e: Int): Float = Float.fromBits((127 + e) shl 23)

        /** One byte through the CRC of x^16 + x^15 + x^2 + 1, most significant bit first. */
        fun crc16(crc: Int, byte: Int): Int {
            var c = crc xor (byte shl 8)
            repeat(8) {
                c = if (c and 0x8000 != 0) (c shl 1) xor 0x8005 else c shl 1
            }
            return c and 0xFFFF
        }
    }
}

/**
 * Seams for the tests, which cannot reach these from outside the decoder.
 *
 * The standard defines decoding in real arithmetic. A decoder that keeps
 * every coefficient as a fixed-point integer, as the one the tests compare
 * against does, differs from it by the rounding of those integers, and
 * [coefficientBits] reproduces that rounding so that the rest of the
 * decoding can be compared exactly.
 */
internal class Ac3Probe(
    /**
     * When positive, each coefficient is cut down to a multiple of 2^-n after it is dequantised, each coupling
     * coordinate as it is read, and each coefficient again after it is decoupled. The cut is a floor, made before
     * the sign of a phase-inverted band is applied.
     */
    val coefficientBits: Int = 0,
    /** Told the bit position, from the start of the syncframe, of each block's dither flags and how many there are. */
    val onDitherFlags: ((bitPosition: Int, count: Int) -> Unit)? = null,
    /** Counts the features the blocks decoded use, so that a test can say what a stream exercises. */
    val census: Ac3Census? = null,
)

/** How many blocks, or words, used each feature of the format. */
internal class Ac3Census {
    var blocks = 0
    var shortBlocks = 0
    var coupledBlocks = 0
    var rematrixedBlocks = 0
    var phaseFlagBlocks = 0
    var ditheredBlocks = 0
    var deltaBlocks = 0
    var skipFields = 0
    var dynamicRangeWords = 0
    var masterCoordinates = 0

    override fun toString() =
        "blocks=$blocks short=$shortBlocks coupled=$coupledBlocks rematrixed=$rematrixedBlocks phaseFlags=$phaseFlagBlocks " +
            "dithered=$ditheredBlocks delta=$deltaBlocks skip=$skipFields dynrng=$dynamicRangeWords masterCoordinates=$masterCoordinates"
}

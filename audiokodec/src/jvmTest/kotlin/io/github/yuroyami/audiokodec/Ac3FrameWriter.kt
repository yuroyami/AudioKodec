package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.ac3.Ac3BitAllocator
import io.github.yuroyami.audiokodec.internal.ac3.Ac3Tables
import io.github.yuroyami.audiokodec.internal.bits.BitWriterMsb
import kotlin.random.Random

/**
 * Writes AC-3 syncframes whose every choice is random, for the tests that
 * compare a decoder against another one.
 *
 * ffmpeg's encoder never switches blocks and never writes delta bit
 * allocation, dynamic range words, phase flags or dual mono, so no stream it
 * writes reaches those parts of a decoder. This writer does not encode audio:
 * it picks the bit stream's elements at random, within what ATSC A/52:2012
 * allows, and packs them as section 5 says, with the exponents coded as
 * section 7.1 says, the bit allocation of section 7.2 deciding how many bits
 * each mantissa gets, and the mantissas as random codes of that size. The
 * result is a valid frame of noise, which any conforming decoder must decode
 * to the same samples; a decoder that disagrees about any element shows up.
 *
 * Dither flags are always clear, because the standard leaves the noise open
 * and two decoders cannot agree on it. Exponents stay at 5 or more, so that
 * the samples are small and float rounding stays small beside them.
 *
 * Two corners where ffmpeg's decoder departs from the standard, found by
 * comparing it with this writer's frames, are kept out of them:
 *
 * - With a coarse SNR offset of zero, the standard gives every channel no
 *   bits only when every fine offset is zero too; ffmpeg does so for each
 *   channel whose own is. A frame states all fine offsets zero or none.
 * - ffmpeg refuses a delta bit allocation that reaches the last band, which
 *   the standard allows, so the segments stop a band short.
 *
 * A third, a block whose channels switch differently, is left to
 * [Options.mixedSwitching].
 *
 * The coupling channel's delta segments count their bands from the first
 * band it covers, which is how ffmpeg reads section 5.4.3.51 and how the
 * decoder does; they therefore have that much less room before the last band.
 */
internal class Ac3FrameWriter(private val seed: Long) {

    /** What a frame may use; every one defaults to on except [mixedSwitching]. */
    class Options(
        val blockSwitching: Boolean = true,
        /**
         * Whether the channels of a block may switch differently. Off by default: in such a block ffmpeg overlaps
         * one channel with another's tail, which the standard does not, so ffmpeg is no reference for it.
         */
        val mixedSwitching: Boolean = false,
        val coupling: Boolean = true,
        val rematrixing: Boolean = true,
        val deltaBitAllocation: Boolean = true,
        val dynamicRange: Boolean = true,
        /** Language codes, production information, time codes, additional information, skip fields. */
        val extras: Boolean = true,
        /** When set, each block's switch flags as a string of S and L, one letter per channel. */
        val switchScript: List<String>? = null,
    )

    /** What the last frame written chose, for a test to print when it disagrees with a decoder. */
    var description: String = ""
        private set

    /**
     * One syncframe of the stream shape given, in the smallest frame size of
     * at least [minimumSizeCode] that holds what was chosen.
     */
    fun frame(sampleRateCode: Int, codingMode: Int, hasLfe: Boolean, minimumSizeCode: Int = 0, options: Options = Options()): ByteArray {
        for (sizeCode in minimumSizeCode until 38) {
            for (snrCap in intArrayOf(63, 44, 30, 18, 8, 0)) {
                val attempt = Attempt(Random(seed), sampleRateCode, sizeCode, codingMode, hasLfe, snrCap, options)
                attempt.build()?.let {
                    description = attempt.log.toString()
                    return it
                }
            }
        }
        error("no frame size holds the frame")
    }

    private class Item(var value: Int, val width: Int)

    /** Mantissas of one coded size that share a word: [size] of them, [levels] levels each. */
    private class Group(val size: Int, val width: Int, val levels: Int, val zeroCode: Int) {
        var item: Item? = null
        val members = IntArray(3)
        var count = 0

        fun add(code: Int, items: MutableList<Item>) {
            if (count == 0) item = Item(0, width).also { items.add(it) }
            members[count++] = code
            if (count == size) finish()
        }

        /** Fills a word that ran out of mantissas with the code for zero, as the standard says to pad. */
        fun finish() {
            if (count == 0) return
            for (i in count until size) members[i] = zeroCode
            item!!.value = if (size == 3) members[0] * levels * levels + members[1] * levels + members[2] else members[0] * levels + members[1]
            count = 0
        }
    }

    private inner class Attempt(
        val random: Random,
        val sampleRateCode: Int,
        val sizeCode: Int,
        val acmod: Int,
        val lfeon: Boolean,
        val snrCap: Int,
        val options: Options,
    ) {
        val nf = FULL_BANDWIDTH[acmod]
        val lfe = 5
        val cpl = 6
        val frameBytes = 2 * when (sampleRateCode) {
            0 -> Ac3Tables.WORDS_48000
            1 -> Ac3Tables.WORDS_44100
            else -> Ac3Tables.WORDS_32000
        }[sizeCode]
        val w = BitWriterMsb(4096)
        val allocator = Ac3BitAllocator(sampleRateCode)
        val log = StringBuilder()

        // What the decoder will hold at each point.
        var cplinu = false
        val chincpl = BooleanArray(5)
        var phsflginu = false
        var cplbegf = 0
        var cplendf = 0
        var ncplbnd = 0
        var cplstrt = 0
        var cplend = 0
        val endmant = IntArray(5)
        val exps = Array(7) { IntArray(256) }
        val bap = Array(7) { IntArray(256) }
        val expKnown = BooleanArray(7)
        var rematBands = 0
        var sd = 0
        var fd = 0
        var sg = 0
        var db = 0
        var fl = 0
        var csnr = 0
        val fsnr = IntArray(7)
        val fgain = IntArray(7)
        var cplfleak = 0
        var cplsleak = 0
        val deltaActive = BooleanArray(7)
        val deltaStored = BooleanArray(7)
        val deltaSegments = IntArray(7)
        val deltaOffsets = Array(7) { IntArray(8) }
        val deltaLengths = Array(7) { IntArray(8) }
        val deltaCodes = Array(7) { IntArray(8) }

        fun build(): ByteArray? {
            w.writeBits(0x0B77, 16)
            w.writeBits(0, 16) // crc1, repaired at the end
            w.writeBits(sampleRateCode, 2)
            w.writeBits(sizeCode, 6)
            writeBitStreamInformation()
            for (block in 0 until 6) {
                writeBlock(block)
                if (w.bitLength > frameBytes * 8L - 18) return null
            }
            while (w.bitLength < frameBytes * 8L - 18) w.writeBit(0)
            w.writeBit(0) // auxdatae
            w.writeBit(0) // crcrsv
            w.writeBits(0, 16) // crc2, repaired next
            val bytes = w.toByteArray()
            check(bytes.size == frameBytes) { "${bytes.size} bytes in a frame of $frameBytes" }
            Ac3TestCrc.repair(bytes, 0, frameBytes)
            return bytes
        }

        fun flag(probability: Int = 2): Boolean = random.nextInt(probability) == 0

        fun bit(value: Boolean) = w.writeBit(if (value) 1 else 0)

        fun writeBitStreamInformation() {
            w.writeBits(8, 5) // bsid
            w.writeBits(0, 3) // bsmod
            w.writeBits(acmod, 3)
            if (acmod and 1 != 0 && acmod != 1) w.writeBits(random.nextInt(3), 2) // cmixlev, the reserved code left out
            if (acmod and 4 != 0) w.writeBits(random.nextInt(3), 2) // surmixlev
            if (acmod == 2) w.writeBits(random.nextInt(3), 2) // dsurmod
            bit(lfeon)
            repeat(if (acmod == 0) 2 else 1) {
                w.writeBits(1 + random.nextInt(31), 5) // dialnorm
                val compre = options.extras && flag()
                bit(compre)
                if (compre) w.writeBits(random.nextInt(256), 8)
                val langcode = options.extras && flag()
                bit(langcode)
                if (langcode) w.writeBits(0xFF, 8)
                val audprodie = options.extras && flag()
                bit(audprodie)
                if (audprodie) {
                    w.writeBits(random.nextInt(32), 5)
                    w.writeBits(random.nextInt(3), 2)
                }
            }
            w.writeBit(random.nextInt(2)) // copyrightb
            w.writeBit(random.nextInt(2)) // origbs
            repeat(2) {
                val timecode = options.extras && flag()
                bit(timecode)
                if (timecode) w.writeBits(random.nextInt(1 shl 14), 14)
            }
            val addbsie = options.extras && flag()
            bit(addbsie)
            if (addbsie) {
                val length = random.nextInt(8)
                w.writeBits(length, 6)
                repeat((length + 1) * 8) { w.writeBit(random.nextInt(2)) }
            }
        }

        fun writeBlock(block: Int) {
            val items = ArrayList<Item>()
            val drawn = if (options.mixedSwitching) {
                BooleanArray(nf) { options.blockSwitching && flag(3) }
            } else {
                val one = options.blockSwitching && flag(3)
                BooleanArray(nf) { one }
            }
            val switched = options.switchScript?.let { script -> BooleanArray(nf) { script[block][it] == 'S' } } ?: drawn
            for (ch in 0 until nf) bit(switched[ch])
            for (ch in 0 until nf) w.writeBit(0) // dithflag

            repeat(if (acmod == 0) 2 else 1) {
                val send = options.dynamicRange && flag(3)
                bit(send)
                if (send) w.writeBits(random.nextInt(256), 8)
            }

            // Coupling strategy. Whenever it is sent, everything that depends on it is sent anew too.
            val cplstre = block == 0 || (acmod >= 2 && flag(3))
            val fresh = cplstre
            bit(cplstre)
            if (cplstre) {
                cplinu = options.coupling && acmod >= 2 && flag()
                bit(cplinu)
                chincpl.fill(false)
                if (cplinu) {
                    do {
                        for (ch in 0 until nf) chincpl[ch] = acmod == 2 || flag()
                    } while ((0 until nf).count { chincpl[it] } < 2)
                    for (ch in 0 until nf) bit(chincpl[ch])
                    if (acmod == 2) {
                        phsflginu = flag()
                        bit(phsflginu)
                    }
                    cplbegf = random.nextInt(16)
                    cplendf = random.nextInt(maxOf(cplbegf - 2, 0), 16)
                    w.writeBits(cplbegf, 4)
                    w.writeBits(cplendf, 4)
                    ncplbnd = 1
                    repeat(3 + cplendf - cplbegf - 1) {
                        val joined = flag()
                        bit(joined)
                        if (!joined) ncplbnd++
                    }
                    cplstrt = 37 + 12 * cplbegf
                    cplend = 37 + 12 * (cplendf + 3)
                }
            }

            // Coupling coordinates.
            if (cplinu) {
                var newForFirstTwo = false
                for (ch in 0 until nf) {
                    if (!chincpl[ch]) continue
                    val send = fresh || flag(3)
                    bit(send)
                    if (send) {
                        if (ch < 2) newForFirstTwo = true
                        w.writeBits(random.nextInt(4), 2) // mstrcplco
                        repeat(ncplbnd) {
                            w.writeBits(if (flag(8)) 15 else random.nextInt(10), 4)
                            w.writeBits(random.nextInt(16), 4)
                        }
                    }
                }
                if (acmod == 2 && phsflginu && newForFirstTwo) repeat(ncplbnd) { w.writeBit(random.nextInt(2)) }
            }

            // Rematrixing.
            if (acmod == 2) {
                val bands = if (!cplinu || cplbegf > 2) 4 else if (cplbegf > 0) 3 else 2
                val send = block == 0 || bands != rematBands || flag()
                bit(send)
                if (send) {
                    repeat(bands) { w.writeBit(if (options.rematrixing) random.nextInt(2) else 0) }
                    rematBands = bands
                }
            }

            // Exponent strategies and bandwidths.
            val cplexpstr = if (cplinu) (if (fresh || !expKnown[cpl]) 1 + random.nextInt(3) else random.nextInt(4)).also { w.writeBits(it, 2) } else 0
            val chexpstr = IntArray(nf) {
                (if (block == 0 || fresh || !expKnown[it]) 1 + random.nextInt(3) else random.nextInt(4)).also { s -> w.writeBits(s, 2) }
            }
            val lfeexpstr = if (lfeon) (if (block == 0 || !expKnown[lfe]) 1 else random.nextInt(2)).also { w.writeBit(it) } else 0
            for (ch in 0 until nf) {
                if (chincpl[ch]) {
                    endmant[ch] = cplstrt
                } else if (chexpstr[ch] != 0) {
                    val code = random.nextInt(61)
                    w.writeBits(code, 6)
                    endmant[ch] = 37 + 3 * (code + 12)
                }
            }

            // Exponents.
            if (cplinu && cplexpstr != 0) {
                val size = 1 shl (cplexpstr - 1)
                val absolute = 3 + random.nextInt(10) // cplabsexp, doubled to a start between 6 and 24
                w.writeBits(absolute, 4)
                writeExponents(exps[cpl], cplstrt, absolute shl 1, (cplend - cplstrt) / (3 * size), size, writeAbsolute = false)
                expKnown[cpl] = true
            }
            for (ch in 0 until nf) {
                if (chexpstr[ch] == 0) continue
                val size = 1 shl (chexpstr[ch] - 1)
                val groups = when (chexpstr[ch]) {
                    1 -> (endmant[ch] - 1) / 3
                    2 -> (endmant[ch] - 1 + 3) / 6
                    else -> (endmant[ch] - 1 + 9) / 12
                }
                val absolute = MIN_EXPONENT + random.nextInt(16 - MIN_EXPONENT)
                w.writeBits(absolute, 4)
                writeExponents(exps[ch], 0, absolute, groups, size, writeAbsolute = true)
                w.writeBits(random.nextInt(4), 2) // gainrng
                expKnown[ch] = true
            }
            if (lfeon && lfeexpstr != 0) {
                val absolute = MIN_EXPONENT + random.nextInt(16 - MIN_EXPONENT)
                w.writeBits(absolute, 4)
                writeExponents(exps[lfe], 0, absolute, 2, 1, writeAbsolute = true)
                expKnown[lfe] = true
            }

            // Bit allocation parameters.
            val baie = block == 0 || flag(3)
            bit(baie)
            if (baie) {
                sd = random.nextInt(4)
                fd = random.nextInt(4)
                sg = random.nextInt(4)
                db = random.nextInt(4)
                fl = random.nextInt(8)
                w.writeBits(sd, 2)
                w.writeBits(fd, 2)
                w.writeBits(sg, 2)
                w.writeBits(db, 2)
                w.writeBits(fl, 3)
            }
            val snroffste = block == 0 || (fresh && cplinu) || flag(3)
            bit(snroffste)
            if (snroffste) {
                csnr = minOf(random.nextInt(64), snrCap)
                w.writeBits(csnr, 6)
                val allFineZero = csnr == 0 && flag()
                fun fine(): Int = if (allFineZero) 0 else if (csnr == 0) 1 + random.nextInt(15) else random.nextInt(16)
                if (cplinu) {
                    fsnr[cpl] = fine()
                    fgain[cpl] = random.nextInt(8)
                    w.writeBits(fsnr[cpl], 4)
                    w.writeBits(fgain[cpl], 3)
                }
                for (ch in 0 until nf) {
                    fsnr[ch] = fine()
                    fgain[ch] = random.nextInt(8)
                    w.writeBits(fsnr[ch], 4)
                    w.writeBits(fgain[ch], 3)
                }
                if (lfeon) {
                    fsnr[lfe] = fine()
                    fgain[lfe] = random.nextInt(8)
                    w.writeBits(fsnr[lfe], 4)
                    w.writeBits(fgain[lfe], 3)
                }
            }
            if (cplinu) {
                val leak = fresh || flag(3)
                bit(leak)
                if (leak) {
                    cplfleak = random.nextInt(8)
                    cplsleak = random.nextInt(8)
                    w.writeBits(cplfleak, 3)
                    w.writeBits(cplsleak, 3)
                }
            }

            writeDelta(block, fresh)

            val skip = options.extras && flag(5)
            bit(skip)
            if (skip) {
                val length = random.nextInt(9)
                w.writeBits(length, 9)
                repeat(length * 8) { w.writeBit(random.nextInt(2)) }
            }

            // Mantissas.
            val allZero = csnr == 0 && (0 until nf).all { fsnr[it] == 0 } && (!cplinu || fsnr[cpl] == 0) && (!lfeon || fsnr[lfe] == 0)
            for (ch in 0 until nf) allocate(ch, 0, endmant[ch], allZero)
            if (cplinu) allocate(cpl, cplstrt, cplend, allZero)
            if (lfeon) allocate(lfe, 0, 7, allZero)
            log.append("[b$block ${switched.joinToString("") { if (it) "S" else "L" }} coupled=$cplinu strategies=${chexpstr.toList()} ends=${endmant.toList().take(nf)} csnr=$csnr] ")

            val groups = listOf(Group(3, 5, 3, 1), Group(3, 7, 5, 2), Group(2, 7, 11, 5))
            var gotCoupling = false
            for (ch in 0 until nf) {
                mantissas(ch, 0, endmant[ch], groups, items)
                if (cplinu && chincpl[ch] && !gotCoupling) {
                    mantissas(cpl, cplstrt, cplend, groups, items)
                    gotCoupling = true
                }
            }
            if (lfeon) mantissas(lfe, 0, 7, groups, items)
            for (g in groups) g.finish()
            for (item in items) w.writeBits(item.value, item.width)
        }

        /** The exponents of one channel: [groups] words of three differences, each shared by [size] bins. */
        fun writeExponents(into: IntArray, start: Int, firstValue: Int, groups: Int, size: Int, writeAbsolute: Boolean) {
            var previous = firstValue
            var bin = start
            if (writeAbsolute) into[bin++] = firstValue
            repeat(groups) {
                var word = 0
                repeat(3) {
                    val low = maxOf(-2, MIN_EXPONENT - previous)
                    val high = minOf(2, 24 - previous)
                    val difference = low + random.nextInt(high - low + 1)
                    previous += difference
                    word = word * 5 + (difference + 2)
                    repeat(size) { into[bin++] = previous }
                }
                w.writeBits(word, 7)
            }
        }

        /**
         * Delta bit allocation. Strategy 0 reuses and 1 sends new segments, both applying the stored ones; 2 turns
         * them off. After block 0 a block that sends none changes nothing.
         */
        fun writeDelta(block: Int, fresh: Boolean) {
            // Segments sent before a new coupling strategy are not reused after it: the coupling channel's count from its first band.
            if (fresh) deltaStored.fill(false)
            // A coupling strategy sent after block 0 can change the bins a channel covers, so delta bit allocation is stated afresh then.
            val send = (fresh && block > 0) || (options.deltaBitAllocation && flag(if (block == 0) 2 else 3))
            bit(send)
            if (!send) {
                if (block == 0) deltaActive.fill(false)
                return
            }
            val channels = ArrayList<Int>()
            if (cplinu) channels.add(cpl)
            for (ch in 0 until nf) channels.add(ch)
            val strategies = IntArray(7) { 2 }
            for (ch in channels) {
                strategies[ch] = when {
                    !options.deltaBitAllocation || capacity(ch) < 2 -> 2
                    // Reuse is only meaningful for segments this frame sent; ffmpeg would apply the last frame's.
                    fresh || block == 0 || !deltaStored[ch] -> 1 + random.nextInt(2)
                    else -> random.nextInt(3)
                }
                w.writeBits(strategies[ch], 2)
            }
            for (ch in channels) {
                when (strategies[ch]) {
                    1 -> {
                        // The standard allows an expanded length of 50 bands; ffmpeg refuses one that reaches the last band.
                        var remaining = capacity(ch)
                        val offsets = ArrayList<Int>()
                        val lengths = ArrayList<Int>()
                        val codes = ArrayList<Int>()
                        val wanted = 1 + random.nextInt(8)
                        while (offsets.size < wanted && remaining >= 2) {
                            val offset = random.nextInt(minOf(remaining - 1, 8))
                            val length = 1 + random.nextInt(minOf(remaining - offset, 10))
                            remaining -= offset + length
                            offsets.add(offset)
                            lengths.add(length)
                            codes.add(random.nextInt(8))
                        }
                        w.writeBits(offsets.size - 1, 3)
                        for (i in offsets.indices) {
                            w.writeBits(offsets[i], 5)
                            w.writeBits(lengths[i], 4)
                            w.writeBits(codes[i], 3)
                            deltaOffsets[ch][i] = offsets[i]
                            deltaLengths[ch][i] = lengths[i]
                            deltaCodes[ch][i] = codes[i]
                        }
                        deltaSegments[ch] = offsets.size
                        deltaActive[ch] = true
                        deltaStored[ch] = true
                    }
                    // Reuse applies the stored segments again, even after a block that turned them off.
                    0 -> deltaActive[ch] = deltaStored[ch]
                    2 -> deltaActive[ch] = false
                }
            }
        }

        /** The first band a channel's delta segments count from: the coupling channel's own first band, band 0 for the rest. */
        fun firstBand(ch: Int): Int = if (ch == cpl) Ac3Tables.MASK[cplstrt] else 0

        /** How many bands the segments of a channel may span in all, offsets included. */
        fun capacity(ch: Int): Int = 49 - firstBand(ch)

        fun allocate(ch: Int, start: Int, end: Int, allZero: Boolean) {
            if (allZero) {
                bap[ch].fill(0, start, end)
                return
            }
            val delta = ch != lfe && deltaActive[ch]
            allocator.allocate(
                exps[ch], start, end, bap[ch],
                sd, fd, sg, db, fl, fgain[ch],
                snrOffset = (((csnr - 15) shl 4) + fsnr[ch]) shl 2,
                fastLeak = (cplfleak shl 8) + 768, slowLeak = (cplsleak shl 8) + 768,
                deltaSegments = if (delta) deltaSegments[ch] else 0,
                deltaOffsets = deltaOffsets[ch], deltaLengths = deltaLengths[ch], deltaCodes = deltaCodes[ch],
                deltaFirstBand = firstBand(ch),
            )
        }

        /** Random mantissa codes for the bins of one channel; groups of three or two share a word, placed at their first member. */
        fun mantissas(ch: Int, start: Int, end: Int, groups: List<Group>, items: MutableList<Item>) {
            for (bin in start until end) {
                when (val b = bap[ch][bin]) {
                    0 -> Unit
                    1 -> groups[0].add(random.nextInt(3), items)
                    2 -> groups[1].add(random.nextInt(5), items)
                    4 -> groups[2].add(random.nextInt(11), items)
                    3 -> items.add(Item(random.nextInt(7), 3))
                    5 -> items.add(Item(random.nextInt(15), 4))
                    else -> {
                        val width = if (b <= 13) b - 1 else if (b == 14) 14 else 16
                        items.add(Item(random.nextInt(1 shl width), width))
                    }
                }
            }
        }
    }

    private companion object {
        val FULL_BANDWIDTH = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5)

        /** The smallest exponent used, which keeps coefficients below 2^-5 so that float rounding stays far below them. */
        const val MIN_EXPONENT = 5
    }
}

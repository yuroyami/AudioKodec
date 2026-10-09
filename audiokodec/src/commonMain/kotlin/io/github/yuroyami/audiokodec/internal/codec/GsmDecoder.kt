/*
 * Translated from libgsm 1.0.22, which is Copyright 1992, 1993, 1994 by
 * Jutta Degener and Carsten Bormann, Technische Universitaet Berlin, under
 * the permission reproduced in NOTICE. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.codec

/**
 * Decodes GSM 06.10 full-rate frames, 160 samples of 8 kHz speech each, as
 * libgsm's `gsm_decode` does, in its 16-bit fixed-point arithmetic.
 *
 * A frame comes in one of two layouts. The plain one is 33 bytes, the signature
 * nibble 0xD and then the parameters most significant bit first. Microsoft's,
 * used inside WAV files and called WAV49 in libgsm, packs two frames into 65
 * bytes, least significant bit first, the second frame starting half way
 * through the 33rd byte.
 */
internal class GsmDecoder {
    private val dp0 = IntArray(280)
    private val larpp = Array(2) { IntArray(8) }
    private var j = 0
    private var nrp = 40
    private val v = IntArray(9)
    private var msr = 0

    private val larc = IntArray(8)
    private val nc = IntArray(4)
    private val bc = IntArray(4)
    private val mc = IntArray(4)
    private val xmaxc = IntArray(4)
    private val xmc = IntArray(52)
    private val erp = IntArray(40)
    private val wt = IntArray(160)
    private val xmp = IntArray(13)
    private val larp = IntArray(8)

    /** `gsm_create`'s state. */
    fun reset() {
        dp0.fill(0)
        larpp[0].fill(0)
        larpp[1].fill(0)
        j = 0
        nrp = 40
        v.fill(0)
        msr = 0
    }

    /**
     * Decodes the plain 33-byte frame at [offset] into 160 samples at
     * [outOffset], and returns false when the frame lacks the signature, as
     * the reference refuses it.
     */
    fun decodePlain(frame: ByteArray, offset: Int, out: ShortArray, outOffset: Int): Boolean {
        if (((frame[offset].toInt() shr 4) and 0x0F) != GSM_MAGIC) return false
        val bits = Bits(frame, offset, msbFirst = true)
        bits.read(4)
        unpack(bits)
        decode(out, outOffset)
        return true
    }

    /** Decodes the two frames of the 65-byte WAV49 block at [offset] into 320 samples at [outOffset]. */
    fun decodeWav49(block: ByteArray, offset: Int, out: ShortArray, outOffset: Int) {
        val bits = Bits(block, offset, msbFirst = false)
        unpack(bits)
        decode(out, outOffset)
        unpack(bits)
        decode(out, outOffset + 160)
    }

    /** Reads one frame's parameters, which both layouts store in the same order and widths. */
    private fun unpack(bits: Bits) {
        for (i in 0 until 8) larc[i] = bits.read(LAR_BITS[i])
        for (s in 0 until 4) {
            nc[s] = bits.read(7)
            bc[s] = bits.read(2)
            mc[s] = bits.read(2)
            xmaxc[s] = bits.read(6)
            for (k in 0 until 13) xmc[s * 13 + k] = bits.read(3)
        }
    }

    private class Bits(private val bytes: ByteArray, private val start: Int, private val msbFirst: Boolean) {
        private var position = 0

        fun read(count: Int): Int {
            var value = 0
            for (n in 0 until count) {
                val byte = bytes[start + (position shr 3)].toInt() and 0xFF
                val bit = if (msbFirst) (byte shr (7 - (position and 7))) and 1 else (byte shr (position and 7)) and 1
                value = if (msbFirst) (value shl 1) or bit else value or (bit shl n)
                position++
            }
            return value
        }
    }

    // decode.c

    private fun decode(out: ShortArray, outOffset: Int) {
        val drp = 120
        for (sub in 0 until 4) {
            rpeDecoding(xmaxc[sub], mc[sub], sub * 13)
            longTermSynthesis(nc[sub], bc[sub], drp)
            for (k in 0 until 40) wt[sub * 40 + k] = dp0[drp + k]
        }
        shortTermSynthesis(out, outOffset)
        // Postprocessing: de-emphasis, upscaling and truncation to 13 bits.
        var m = msr
        for (k in 0 until 160) {
            val tmp = s16(multR(m, 28180))
            m = add(out[outOffset + k].toInt(), tmp)
            out[outOffset + k] = (add(m, m) and 0xFFF8).toShort()
        }
        msr = m
    }

    // rpe.c

    private fun rpeDecoding(xmaxcr: Int, mcr: Int, xmcAt: Int) {
        // APCM_quantization_xmaxc_to_exp_mant
        var exp = 0
        if (xmaxcr > 15) exp = (xmaxcr shr 3) - 1
        var mant = xmaxcr - (exp shl 3)
        if (mant == 0) {
            exp = -4
            mant = 7
        } else {
            while (mant <= 7) {
                mant = (mant shl 1) or 1
                exp--
            }
            mant -= 8
        }
        // APCM_inverse_quantization
        val temp1 = FAC[mant]
        val temp2 = sub(6, exp)
        val temp3 = asl(1, sub(temp2, 1))
        for (i in 0 until 13) {
            var temp = (xmc[xmcAt + i] shl 1) - 7
            temp = s16(temp shl 12)
            temp = s16(multR(temp1, temp))
            temp = add(temp, temp3)
            xmp[i] = asr(temp, temp2)
        }
        // RPE_grid_positioning
        var e = 0
        for (k in 0 until mcr) erp[e++] = 0
        for (i in 0 until 13) {
            erp[e++] = xmp[i]
            if (i < 12) {
                erp[e++] = 0
                erp[e++] = 0
            }
        }
        while (e < 40) erp[e++] = 0
    }

    // long_term.c

    private fun longTermSynthesis(ncr: Int, bcr: Int, drp: Int) {
        val nr = if (ncr < 40 || ncr > 120) nrp else ncr
        nrp = nr
        val brp = QLB[bcr]
        for (k in 0 until 40) {
            val drpp = s16(multR(brp, dp0[drp + k - nr]))
            dp0[drp + k] = add(erp[k], drpp)
        }
        for (k in 0 until 120) dp0[drp - 120 + k] = dp0[drp - 80 + k]
    }

    // short_term.c

    private fun shortTermSynthesis(out: ShortArray, outOffset: Int) {
        val current = larpp[j]
        j = j xor 1
        val previous = larpp[j]
        // Decoding_of_the_coded_Log_Area_Ratios
        for (i in 0 until 8) {
            var temp1 = s16(add(larc[i], MIC[i]) shl 10)
            temp1 = sub(temp1, B_TIMES_TWO[i])
            temp1 = s16(multR(INVA[i], temp1))
            current[i] = add(temp1, temp1)
        }
        for (i in 0 until 8) larp[i] = add(add(previous[i] shr 2, current[i] shr 2), previous[i] shr 1)
        larpToRp()
        synthesisFilter(13, 0, out, outOffset)
        for (i in 0 until 8) larp[i] = add(previous[i] shr 1, current[i] shr 1)
        larpToRp()
        synthesisFilter(14, 13, out, outOffset + 13)
        for (i in 0 until 8) larp[i] = add(add(previous[i] shr 2, current[i] shr 2), current[i] shr 1)
        larpToRp()
        synthesisFilter(13, 27, out, outOffset + 27)
        for (i in 0 until 8) larp[i] = current[i]
        larpToRp()
        synthesisFilter(120, 40, out, outOffset + 40)
    }

    private fun larpToRp() {
        for (i in 0 until 8) {
            val value = larp[i]
            if (value < 0) {
                val temp = if (value == MIN_WORD) MAX_WORD else -value
                larp[i] = s16(-(if (temp < 11059) temp shl 1 else if (temp < 20070) temp + 11059 else add(temp shr 2, 26112)))
            } else {
                larp[i] = if (value < 11059) s16(value shl 1) else if (value < 20070) s16(value + 11059) else add(value shr 2, 26112)
            }
        }
    }

    private fun synthesisFilter(count: Int, wtAt: Int, out: ShortArray, outOffset: Int) {
        for (n in 0 until count) {
            var sri = wt[wtAt + n]
            for (i in 7 downTo 0) {
                val tmp1 = larp[i]
                var tmp2 = v[i]
                tmp2 = if (tmp1 == MIN_WORD && tmp2 == MIN_WORD) MAX_WORD else s16(((tmp1 * tmp2 + 16384) shr 15) and 0xFFFF)
                sri = sub(sri, tmp2)
                val t = if (tmp1 == MIN_WORD && sri == MIN_WORD) MAX_WORD else s16(((tmp1 * sri + 16384) shr 15) and 0xFFFF)
                v[i + 1] = add(v[i], t)
            }
            v[0] = sri
            out[outOffset + n] = sri.toShort()
        }
    }

    private companion object {
        const val GSM_MAGIC = 0xD
        const val MIN_WORD = -32768
        const val MAX_WORD = 32767

        val LAR_BITS = intArrayOf(6, 6, 5, 5, 4, 4, 3, 3)
        val MIC = intArrayOf(-32, -32, -16, -16, -8, -8, -4, -4)
        val B_TIMES_TWO = intArrayOf(0, 0, 4096, -5120, 188, -3584, -682, -2288)
        val INVA = intArrayOf(13107, 13107, 13107, 13107, 19223, 17476, 31454, 29708)
        val QLB = intArrayOf(3277, 11469, 21299, 32767)
        val FAC = intArrayOf(18431, 20479, 22527, 24575, 26623, 28671, 30719, 32767)

        fun s16(x: Int): Int = x.toShort().toInt()

        /** `GSM_ADD`, saturating. */
        fun add(a: Int, b: Int): Int {
            val sum = a + b
            return if (sum > MAX_WORD) MAX_WORD else if (sum < MIN_WORD) MIN_WORD else sum
        }

        /** `GSM_SUB`, saturating. */
        fun sub(a: Int, b: Int): Int {
            val diff = a - b
            return if (diff >= MAX_WORD) MAX_WORD else if (diff <= MIN_WORD) MIN_WORD else diff
        }

        /** `GSM_MULT_R`, before the caller narrows it. */
        fun multR(a: Int, b: Int): Int = (a * b + 16384) shr 15

        fun asl(a: Int, n: Int): Int = when {
            n >= 16 -> 0
            n <= -16 -> -(if (a < 0) 1 else 0)
            n < 0 -> asr(a, -n)
            else -> s16(a shl n)
        }

        fun asr(a: Int, n: Int): Int = when {
            n >= 16 -> -(if (a < 0) 1 else 0)
            n <= -16 -> 0
            n < 0 -> s16(a shl -n)
            else -> a shr n
        }
    }
}

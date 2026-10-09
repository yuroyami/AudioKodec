package io.github.yuroyami.audiokodec.internal.codec

/**
 * Decodes ITU-T G.726 ADPCM at any of its four rates, one code word at a
 * time, to the reconstructed uniform signal.
 *
 * Written from section 4 of the Recommendation (12/90), whose sub-blocks
 * define the decoder exactly in unsigned integer arithmetic on fields of
 * stated widths. Each sub-block is kept as the Recommendation writes it, with
 * its masks, so the arithmetic is the reference's on every target. The
 * quantized difference signal is carried as a 16-bit signed magnitude at
 * every rate, which the Recommendation requires at 40 kbit/s and permits at
 * the others.
 *
 * [bits] is the code word size: 2, 3, 4 or 5 for 16, 24, 32 or 40 kbit/s.
 */
internal class G726Decoder(private val bits: Int) {
    init {
        require(bits in 2..5) { "G.726 code words are 2 to 5 bits, not $bits" }
    }

    private val dqlnTable: IntArray
    private val wiTable: IntArray
    private val fiTable: IntArray

    init {
        when (bits) {
            5 -> {
                dqlnTable = DQLN_40
                wiTable = WI_40
                fiTable = FI_40
            }
            4 -> {
                dqlnTable = DQLN_32
                wiTable = WI_32
                fiTable = FI_32
            }
            3 -> {
                dqlnTable = DQLN_24
                wiTable = WI_24
                fiTable = FI_24
            }
            else -> {
                dqlnTable = DQLN_16
                wiTable = WI_16
                fiTable = FI_16
            }
        }
    }

    // The delayed variables of Table 6, with their reset values.
    private var yl = 0
    private var yu = 0
    private var dms = 0
    private var dml = 0
    private var ap = 0
    private var a1 = 0
    private var a2 = 0
    private val b = IntArray(6)
    private val dqDelayed = IntArray(6)
    private var sr1 = 0
    private var sr2 = 0
    private var pk1 = 0
    private var pk2 = 0
    private var td = 0

    /**
     * The signal estimate SE and scale factor Y the last [decode] used, which
     * the Recommendation's synchronous coding adjustment for log PCM output
     * needs; the conformance tests apply it.
     */
    var signalEstimate: Int = 0
        private set
    var scaleFactor: Int = 0
        private set

    init {
        reset()
    }

    /** The optional reset of section 4.1. */
    fun reset() {
        yl = 34816
        yu = 544
        dms = 0
        dml = 0
        ap = 0
        a1 = 0
        a2 = 0
        b.fill(0)
        dqDelayed.fill(32)
        sr1 = 32
        sr2 = 32
        pk1 = 0
        pk2 = 0
        td = 0
    }

    /**
     * Decodes code word [i] and returns the reconstructed signal SR, a
     * two's complement value whose range is that of 14-bit uniform PCM.
     */
    fun decode(i: Int): Int {
        // ACCUM, from the predictor's delayed state.
        var sezi = 0
        for (n in 0 until 6) sezi = (sezi + fmult(b[n], dqDelayed[n])) and 65535
        val sei = (((sezi + fmult(a2, sr2)) and 65535) + fmult(a1, sr1)) and 65535
        val sez = sezi shr 1
        val se = sei shr 1
        signalEstimate = if (se shr 14 == 0) se else se - 32768

        // LIMA and MIX.
        val al = if (ap >= 256) 64 else ap shr 2
        val y = mix(al)
        scaleFactor = y

        // The inverse adaptive quantizer: RECONST, ADDA and ANTILOG.
        val signBit = 1 shl (bits - 1)
        val dqs = i shr (bits - 1)
        val im = if (dqs == 0) i else (2 * signBit - 1 - i) and (signBit - 1)
        val dqln = dqlnTable[im]
        val dql = (dqln + (y shr 2)) and 4095
        val dq = antilog(dql, dqs)

        // ADDB and ADDC.
        val dqi = if (dqs == 0) dq else (65536 - (dq and 32767)) and 65535
        val seExt = if (se shr 14 == 0) se else 32768 + se
        val sr = (dqi + seExt) and 65535
        val sezExt = if (sez shr 14 == 0) sez else 32768 + sez
        val dqsez = (dqi + sezExt) and 65535
        val pk0 = dqsez shr 15
        val sigpk = if (dqsez == 0) 1 else 0

        // Quantizer scale factor adaptation: FUNCTW, FILTD, LIMB and FILTE.
        val wi = wiTable[im]
        val filtdDif = ((wi shl 5) + 131072 - y) and 131071
        val yut = (y + if (filtdDif shr 16 == 0) filtdDif shr 5 else (filtdDif shr 5) + 4096) and 8191
        val geul = ((yut + 11264) and 16383) shr 13
        val gell = ((yut + 15840) and 16383) shr 13
        val yup = if (gell == 1) 544 else if (geul == 0) 5120 else yut
        val filteDif = (yup + ((1048576 - yl) shr 6)) and 16383
        val ylp = (yl + if (filteDif shr 13 == 0) filteDif else filteDif + 507904) and 524287

        // Adaptation speed control, first half: FUNCTF, FILTA and FILTB.
        val fi = fiTable[im]
        val filtaDif = ((fi shl 9) + 8192 - dms) and 8191
        val dmsp = ((if (filtaDif shr 12 == 0) filtaDif shr 5 else (filtaDif shr 5) + 3840) + dms) and 4095
        val filtbDif = ((fi shl 11) + 32768 - dml) and 32767
        val dmlp = ((if (filtbDif shr 14 == 0) filtbDif shr 7 else (filtbDif shr 7) + 16128) + dml) and 16383

        // TRANS, from the delayed tone detect and slow scale factor.
        val tr = trans(dq)

        // The second order predictor: UPA2, LIMC, UPA1 and LIMD.
        val pks1 = pk0 xor pk1
        val pks2 = pk0 xor pk2
        val a2t = upa2(pks1, pks2, sigpk)
        val a2p = when {
            a2t in 32768..53248 -> 53248
            a2t in 12288..32767 -> 12288
            else -> a2t
        }
        val uga1 = if (sigpk == 1) 0 else if (pks1 == 0) 192 else 65344
        val ula1 = if (a1 shr 15 == 0) (65536 - (a1 shr 8)) and 65535 else (65536 - ((a1 shr 8) + 65280)) and 65535
        val a1t = (a1 + ((uga1 + ula1) and 65535)) and 65535
        val a1ul = (OME + 65536 - a2p) and 65535
        val a1ll = (a2p + 65536 - OME) and 65535
        val a1p = when {
            a1t >= 32768 && a1t <= a1ll -> a1ll
            a1t >= a1ul && a1t <= 32767 -> a1ul
            else -> a1t
        }

        // The sixth order predictor: XOR and UPB, with TRIGB, written straight into the delayed coefficients.
        val dqMag = dq and 32767
        val leakShift = if (bits == 5) 9 else 8
        val leakExtension = 65536 - (65536 shr leakShift)
        for (n in 0 until 6) {
            if (tr == 1) {
                b[n] = 0
                continue
            }
            val un = dqs xor (dqDelayed[n] shr 10)
            val ugb = if (dqMag == 0) 0 else if (un == 0) 128 else 65408
            val bn = b[n]
            val ulb = if (bn shr 15 == 0) {
                (65536 - (bn shr leakShift)) and 65535
            } else {
                (65536 - ((bn shr leakShift) + leakExtension)) and 65535
            }
            b[n] = (bn + ((ugb + ulb) and 65535)) and 65535
        }

        // TONE, then SUBTC, FILTC and TRIGA.
        val tdp = if (a2p in 32768 until 53760) 1 else 0
        val subtcDif = ((dmsp shl 2) + 32768 - dmlp) and 32767
        val difm = if (subtcDif shr 14 == 0) subtcDif else (32768 - subtcDif) and 16383
        val ax = if (y >= 1536 && difm < (dmlp shr 3) && tdp == 0) 0 else 1
        val filtcDif = ((ax shl 9) + 2048 - ap) and 2047
        val app = ((if (filtcDif shr 10 == 0) filtcDif shr 4 else (filtcDif shr 4) + 896) + ap) and 1023

        // FLOATA, FLOATB and the DELAY blocks, through TRIGB and TRIGA.
        for (n in 5 downTo 1) dqDelayed[n] = dqDelayed[n - 1]
        dqDelayed[0] = toFloat(dqs, dqMag)
        sr2 = sr1
        val srs = sr shr 15
        sr1 = toFloat(srs, if (srs == 0) sr else (65536 - sr) and 32767)
        pk2 = pk1
        pk1 = pk0
        yu = yup
        yl = ylp
        dms = dmsp
        dml = dmlp
        ap = if (tr == 1) 256 else app
        a1 = if (tr == 1) 0 else a1p
        a2 = if (tr == 1) 0 else a2p
        td = if (tr == 1) 0 else tdp

        return if (sr and 32768 != 0) sr - 65536 else sr
    }

    /** MIX: the scale factor, a combination of the fast and slow ones weighted by [al]. */
    private fun mix(al: Int): Int {
        val dif = (yu + 16384 - (yl shr 6)) and 16383
        val difs = dif shr 13
        val difm = if (difs == 0) dif else (16384 - dif) and 8191
        val prodm = (difm * al) shr 6
        val prod = if (difs == 0) prodm else (16384 - prodm) and 16383
        return ((yl shr 6) + prod) and 8191
    }

    /** ANTILOG, with the sign attached to a 16-bit signed magnitude. */
    private fun antilog(dql: Int, dqs: Int): Int {
        val ds = dql shr 11
        val dex = (dql shr 7) and 15
        val dqt = (1 shl 7) + (dql and 127)
        val dqmag = if (ds == 0) (dqt shl 7) shr (14 - dex) else 0
        return (dqs shl 15) + dqmag
    }

    /** TRANS. */
    private fun trans(dq: Int): Int {
        val dqmag = dq and 32767
        val ylint = yl shr 15
        val ylfrac = (yl shr 10) and 31
        val thr2 = if (ylint > 9) 31 shl 10 else (32 + ylfrac) shl ylint
        val dqthr = (thr2 + (thr2 shr 1)) shr 1
        return if (dqmag > dqthr && td == 1) 1 else 0
    }

    /** UPA2. */
    private fun upa2(pks1: Int, pks2: Int, sigpk: Int): Int {
        val uga2a = if (pks2 == 0) 16384 else 114688
        val fa1 = if (a1 shr 15 == 0) {
            if (a1 <= 8191) a1 shl 2 else 8191 shl 2
        } else {
            if (a1 >= 57345) (a1 shl 2) and 131071 else 24577 shl 2
        }
        val fa = if (pks1 == 1) fa1 else (131072 - fa1) and 131071
        val uga2b = (uga2a + fa) and 131071
        val uga2 = when {
            sigpk == 1 -> 0
            uga2b shr 16 == 0 -> uga2b shr 7
            else -> (uga2b shr 7) + 64512
        }
        val ula2 = if (a2 shr 15 == 0) (65536 - (a2 shr 7)) and 65535 else (65536 - ((a2 shr 7) + 65024)) and 65535
        return (a2 + ((uga2 + ula2) and 65535)) and 65535
    }

    private companion object {
        const val OME = 15360

        /** FMULT: a predictor coefficient times a delayed value held in the 11-bit floating point form. */
        fun fmult(an: Int, srn: Int): Int {
            val ans = an shr 15
            val anmag = if (ans == 0) an shr 2 else (16384 - (an shr 2)) and 8191
            val anexp = bitLength(anmag)
            val anmant = if (anmag == 0) 1 shl 5 else (anmag shl 6) shr anexp
            val srns = srn shr 10
            val srnexp = (srn shr 6) and 15
            val srnmant = srn and 63
            val wans = srns xor ans
            val wanexp = srnexp + anexp
            val wanmant = ((srnmant * anmant) + 48) shr 4
            val wanmag = if (wanexp <= 26) {
                (wanmant shl 7) shr (26 - wanexp)
            } else {
                ((wanmant shl 7) shl (wanexp - 26)) and 32767
            }
            return if (wans == 0) wanmag else (65536 - wanmag) and 65535
        }

        /** FLOATA and FLOATB: a sign and magnitude to the 11-bit floating point form. */
        fun toFloat(sign: Int, mag: Int): Int {
            val exp = bitLength(mag)
            val mant = if (mag == 0) 1 shl 5 else (mag shl 6) shr exp
            return (sign shl 10) + (exp shl 6) + mant
        }

        /** The exponent the sub-blocks compute for a magnitude: the number of bits it occupies. */
        fun bitLength(mag: Int): Int = 32 - mag.countLeadingZeroBits()

        // RECONST, FUNCTW and FUNCTF, indexed by the code word's magnitude IM.
        val DQLN_40 = intArrayOf(2048, 4030, 28, 104, 169, 224, 274, 318, 358, 395, 429, 459, 488, 514, 539, 566)
        val DQLN_32 = intArrayOf(2048, 4, 135, 213, 273, 323, 373, 425)
        val DQLN_24 = intArrayOf(2048, 135, 273, 373)
        val DQLN_16 = intArrayOf(116, 365)
        val WI_40 = intArrayOf(14, 14, 24, 39, 40, 41, 58, 100, 141, 179, 219, 280, 358, 440, 529, 696)
        val WI_32 = intArrayOf(4084, 18, 41, 64, 112, 198, 355, 1122)
        val WI_24 = intArrayOf(4092, 30, 137, 582)
        val WI_16 = intArrayOf(4074, 439)
        val FI_40 = intArrayOf(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 2, 3, 4, 5, 6, 6)
        val FI_32 = intArrayOf(0, 0, 0, 1, 1, 1, 3, 7)
        val FI_24 = intArrayOf(0, 1, 2, 7)
        val FI_16 = intArrayOf(0, 7)
    }
}

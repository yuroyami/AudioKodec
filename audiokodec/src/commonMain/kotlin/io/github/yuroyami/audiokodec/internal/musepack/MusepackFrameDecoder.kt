/*
 * Translated from libmpcdec r495, which is Copyright (c) 2005-2009 The Musepack
 * Development Team and licensed under the BSD 3-Clause licence, reproduced in
 * NOTICE. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.musepack

import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.internal.mpeg.Mp3Tables

/** A canonical Huffman table of [MusepackTables]. */
internal class MusepackCanonical(
    val codes: IntArray,
    val lengths: IntArray,
    val bytes: IntArray,
    val symbols: IntArray,
)

/**
 * Reads an SV8 audio block most significant bit first, as `mpc_bits_reader`
 * does. Reading past the block's end is damage; looking at the bits past it,
 * which Huffman decoding does to pick an entry, sees zeros, which pick the
 * same entry as any other bits would.
 */
internal class MusepackBits(private val data: ByteArray, private val start: Int, private val end: Int) {
    /** Bits read from [start]. */
    var position = 0L
        private set

    val remaining: Long get() = (end - start) * 8L - position

    private fun byteAt(index: Long): Int {
        val at = start + index
        return if (at < end) data[at.toInt()].toInt() and 0xFF else 0
    }

    /** The next 16 bits without reading them. */
    fun peek16(): Int {
        val byte = position ushr 3
        val window = (byteAt(byte) shl 16) or (byteAt(byte + 1) shl 8) or byteAt(byte + 2)
        return (window ushr (8 - (position and 7).toInt())) and 0xFFFF
    }

    fun skip(bits: Int) {
        position += bits
        if (position > (end - start) * 8L) throw CorruptStreamException("a Musepack frame's codes run past the end of its block")
    }

    /** `mpc_bits_read`: up to 31 bits. */
    fun read(bits: Int): Int {
        var value = 0
        repeat(bits) {
            val bit = (byteAt(position ushr 3) ushr (7 - (position and 7).toInt())) and 1
            value = (value shl 1) or bit
            position++
        }
        if (position > (end - start) * 8L) throw CorruptStreamException("a Musepack frame's codes run past the end of its block")
        return value
    }

    /** `mpc_bits_can_dec`. */
    fun canonical(table: MusepackCanonical): Int {
        val code = peek16()
        var i = 0
        while (code < table.codes[i]) i++
        val length = table.lengths[i]
        skip(length)
        return table.symbols[(table.bytes[i] - (code ushr (16 - length))) and 0xFF]
    }

    /** `mpc_bits_log_dec`: a value from 0 to [max] in about log2 of max bits. */
    fun logarithmic(max: Int): Int {
        if (max == 0) return 0
        val bits = max.toLong().let { 64 - (it).countLeadingZeroBits() }
        val lost = (1 shl bits) - (max + 1)
        var value = 0
        if (bits > 1) value = read(bits - 1)
        if (value >= lost) value = ((value shl 1) or read(1)) - lost
        return value
    }

    /**
     * `mpc_bits_enum_dec`: which [k] of [n] items are set, coded as the
     * combination's rank. The rank takes [ENUM_LENGTH] bits at most, one bit
     * fewer for the first [ENUM_LOST] ranks.
     */
    fun enumeration(k: Int, n: Int): Int {
        var row = k - 1
        var count = n
        var left = k
        var code = read(ENUM_LENGTH[row][count - 1] - 1)
        val lost = ENUM_LOST[row][count - 1]
        if (Integer32.compareUnsigned(code, lost) >= 0) code = ((code shl 1) or read(1)) - lost
        var bits = 0
        do {
            count--
            if (count < 0 || row < 0) throw CorruptStreamException("a Musepack frame's enumeration does not fit its count")
            val c = COMBINATIONS[row][count]
            if (Integer32.compareUnsigned(code, c) >= 0) {
                bits = bits or (1 shl count)
                code -= c
                row--
                left--
            }
        } while (left > 0)
        return bits
    }

    companion object {
        private const val MAX_ENUM = 32

        private fun binomial(n: Int, k: Int): Long {
            if (k < 0 || k > n) return 0
            var result = 1L
            for (i in 1..k) result = result * (n - k + i) / i
            return result
        }

        /** `Cnk`: row k holds C(n, k + 1) for n from 0 to 31. */
        private val COMBINATIONS = Array(MAX_ENUM / 2) { k -> IntArray(MAX_ENUM) { n -> binomial(n, k + 1).toInt() } }

        /** `Cnk_len`: the bits a rank among C(n + 1, k + 1) takes. */
        private val ENUM_LENGTH = Array(MAX_ENUM / 2) { k ->
            IntArray(MAX_ENUM) { n ->
                val total = binomial(n + 1, k + 1)
                if (total == 0L) 0 else 64 - (total - 1).countLeadingZeroBits()
            }
        }

        /**
         * `Cnk_lost`: how many ranks take one bit fewer. The reference's table
         * stops one initialiser short, so its last entry, 16 among 32, is 0
         * rather than 472661434; that entry can be read, and the decoder keeps
         * the reference's value.
         */
        private val ENUM_LOST = Array(MAX_ENUM / 2) { k ->
            IntArray(MAX_ENUM) { n ->
                val total = binomial(n + 1, k + 1)
                val length = ENUM_LENGTH[k][n]
                if (total == 0L || k == MAX_ENUM / 2 - 1 && n == MAX_ENUM - 1) 0 else ((1L shl length) - total).toInt()
            }
        }
    }
}

/**
 * Decodes Musepack SV8 frames: `mpc_decoder_read_bitstream_sv8`,
 * `mpc_decoder_requantisierung` and the float synthesis filter of
 * `synth_filter.c`, in single precision as the reference computes them.
 *
 * Within a block every frame but the first is coded against the one before,
 * and the synthesis filter carries 512 samples of history from frame to
 * frame, so this decoder carries state; [reset] clears it.
 */
internal class MusepackFrameDecoder(private val channels: Int, private val maxBand: Int, private val midSide: Boolean) {
    private val resL = IntArray(32)
    private val resR = IntArray(32)
    private val scfL = Array(32) { IntArray(3) }
    private val scfR = Array(32) { IntArray(3) }
    private val scfiL = IntArray(32)
    private val scfiR = IntArray(32)
    private val dscfL = BooleanArray(32)
    private val dscfR = BooleanArray(32)
    private val msFlag = BooleanArray(32)
    private val qL = Array(32) { ShortArray(36) }
    private val qR = Array(32) { ShortArray(36) }
    private var lastMaxBand = 0
    private var random1 = 1
    private var random2 = 1

    private val yL = FloatArray(36 * 32)
    private val yR = FloatArray(36 * 32)
    private val vL = FloatArray(V_MEM + 960)
    private val vR = FloatArray(V_MEM + 960)

    /** `mpc_decoder_scale_output(d, 1.0)`: the scalefactors, indexed by an SCF index's low byte. */
    private val scf = FloatArray(256)

    init {
        val factor = 1.0 / (1 shl (FIXED_POINT_SHIFT - 1)).toDouble()
        var f1 = factor
        var f2 = factor
        scf[1] = factor.toFloat()
        f1 *= 0.83298066476582673961
        f2 *= 1 / 0.83298066476582673961
        for (n in 1..128) {
            scf[(1 + n) and 0xFF] = f1.toFloat()
            scf[(1 - n) and 0xFF] = f2.toFloat()
            f1 *= 0.83298066476582673961
            f2 *= 1 / 0.83298066476582673961
        }
    }

    fun reset() {
        for (b in 0 until 32) {
            resL[b] = 0
            resR[b] = 0
            scfL[b].fill(0)
            scfR[b].fill(0)
            scfiL[b] = 0
            scfiR[b] = 0
            dscfL[b] = false
            dscfR[b] = false
            msFlag[b] = false
            qL[b].fill(0)
            qR[b].fill(0)
        }
        lastMaxBand = 0
        random1 = 1
        random2 = 1
        yL.fill(0f)
        yR.fill(0f)
        vL.fill(0f)
        vR.fill(0f)
    }

    /**
     * Reads one frame from [bits] and, unless [synthesise] is false, writes
     * its 1152 samples per channel to [out], interleaved, from [at].
     */
    fun decode(bits: MusepackBits, keyFrame: Boolean, synthesise: Boolean, out: FloatArray, at: Int) {
        readBitstream(bits, keyFrame)
        if (!synthesise) return
        requantise()
        synthesis(out, at, vL, yL, 0)
        if (channels > 1) synthesis(out, at, vR, yR, 1)
    }

    private fun readBitstream(r: MusepackBits, keyFrame: Boolean) {
        var maxUsed = if (keyFrame) {
            r.logarithmic(maxBand + 1)
        } else {
            lastMaxBand + r.canonical(MusepackTables.BANDS)
        }
        if (!keyFrame && maxUsed > 32) maxUsed -= 33
        lastMaxBand = maxUsed

        if (maxUsed > 0) {
            resL[maxUsed - 1] = r.canonical(MusepackTables.RES_1)
            resR[maxUsed - 1] = r.canonical(MusepackTables.RES_1)
            if (resL[maxUsed - 1] > 15) resL[maxUsed - 1] -= 17
            if (resR[maxUsed - 1] > 15) resR[maxUsed - 1] -= 17
            for (n in maxUsed - 2 downTo 0) {
                resL[n] = r.canonical(if (resL[n + 1] > 2) MusepackTables.RES_2 else MusepackTables.RES_1) + resL[n + 1]
                if (resL[n] > 15) resL[n] -= 17
                resR[n] = r.canonical(if (resR[n + 1] > 2) MusepackTables.RES_2 else MusepackTables.RES_1) + resR[n + 1]
                if (resR[n] > 15) resR[n] -= 17
            }
            if (midSide) {
                var total = 0
                for (n in 0 until maxUsed) if (resL[n] != 0 || resR[n] != 0) total++
                val count = r.logarithmic(total)
                var flags = 0
                if (count != 0 && count != total) flags = r.enumeration(minOf(count, total - count), total)
                if (count * 2 > total) flags = flags.inv()
                for (n in maxUsed - 1 downTo 0) {
                    if (resL[n] != 0 || resR[n] != 0) {
                        msFlag[n] = flags and 1 != 0
                        flags = flags ushr 1
                    }
                }
            }
        }
        for (n in maxUsed..maxBand) {
            resL[n] = 0
            resR[n] = 0
        }

        if (keyFrame) {
            dscfL.fill(true)
            dscfR.fill(true)
        }

        for (n in 0 until maxUsed) {
            var count = -1
            if (resL[n] != 0) count++
            if (resR[n] != 0) count++
            if (count >= 0) {
                val value = r.canonical(if (count == 0) MusepackTables.SCFI_1 else MusepackTables.SCFI_2)
                if (resL[n] != 0) scfiL[n] = value shr (2 * count)
                if (resR[n] != 0) scfiR[n] = value and 3
            }
        }

        for (n in 0 until maxUsed) {
            readScalefactors(r, resL[n], scfiL[n], scfL[n], dscfL, n)
            readScalefactors(r, resR[n], scfiR[n], scfR[n], dscfR, n)
        }

        for (n in 0 until maxUsed) {
            readSamples(r, resL[n], qL[n])
            readSamples(r, resR[n], qR[n])
        }
    }

    private fun readScalefactors(r: MusepackBits, res: Int, scfi: Int, index: IntArray, dscf: BooleanArray, band: Int) {
        if (res == 0) return
        if (dscf[band]) {
            index[0] = r.read(7) - 6
            dscf[band] = false
        } else {
            var value = r.canonical(MusepackTables.DSCF_2)
            if (value == 64) value += r.read(6)
            index[0] = ((index[2] - 25 + value) and 127) - 6
        }
        for (m in 0 until 2) {
            if (((scfi shl m) and 2) == 0) {
                var value = r.canonical(MusepackTables.DSCF_1)
                if (value == 31) value = 64 + r.read(6)
                index[m + 1] = ((index[m] - 25 + value) and 127) - 6
            } else {
                index[m + 1] = index[m]
            }
        }
    }

    private fun readSamples(r: MusepackBits, res: Int, q: ShortArray) {
        when {
            res == 0 -> Unit
            res == 2 -> {
                var idx = 2 * THRESHOLDS[2]
                for (k in 0 until 36 step 3) {
                    val value = r.canonical(if (idx > THRESHOLDS[2]) MusepackTables.Q2_2 else MusepackTables.Q2_1)
                    q[k] = (value % 5 - 2).toShort()
                    q[k + 1] = (value / 5 % 5 - 2).toShort()
                    q[k + 2] = (value / 25 - 2).toShort()
                    idx = (idx shr 1) + kotlin.math.abs(q[k].toInt()) + kotlin.math.abs(q[k + 1].toInt()) + kotlin.math.abs(q[k + 2].toInt())
                }
            }
            res == 1 -> {
                var k = 0
                while (k < 36) {
                    val kmax = k + 18
                    val count = r.canonical(MusepackTables.Q1)
                    var idx = 0
                    if (count in 1..17) idx = r.enumeration(if (count <= 9) count else 18 - count, 18)
                    if (count > 9) idx = idx.inv()
                    while (k < kmax) {
                        q[k] = 0
                        if (idx and (1 shl 17) != 0) q[k] = ((r.read(1) shl 1) - 1).toShort()
                        idx = idx shl 1
                        k++
                    }
                }
            }
            res == -1 -> for (k in 0 until 36) {
                val value = randomInt()
                q[k] = (((value ushr 24) and 0xFF) + ((value ushr 16) and 0xFF) + ((value ushr 8) and 0xFF) + (value and 0xFF) - 510).toShort()
            }
            res <= 4 -> {
                val table = if (res == 3) MusepackTables.Q3 else MusepackTables.Q4
                for (k in 0 until 36 step 2) {
                    val symbol = r.canonical(table)
                    // Two signed 4-bit fields of one signed byte, low first.
                    q[k] = ((symbol shl 28) shr 28).toShort()
                    q[k + 1] = ((symbol shl 24) shr 28).toShort()
                }
            }
            res <= 8 -> {
                val tables = QUANTISERS[res - 5]
                var idx = 2 * THRESHOLDS[res]
                for (k in 0 until 36) {
                    q[k] = r.canonical(tables[if (idx > THRESHOLDS[res]) 1 else 0]).toShort()
                    idx = (idx shr 1) + kotlin.math.abs(q[k].toInt())
                }
            }
            else -> for (k in 0 until 36) {
                var value = r.canonical(MusepackTables.Q9_UP) and 0xFF
                if (res != 9) value = (value shl (res - 9)) or r.read(res - 9)
                q[k] = (value.toShort() - MusepackTables.OFFSETS[res + 1]).toShort()
            }
        }
    }

    /** `mpc_random_int`: two polynomial counters of coprime periods, combined. */
    private fun randomInt(): Int {
        val t1 = PARITY[random1 and 0xF5] shl 31
        val t2 = PARITY[(random2 ushr 25) and 0x63]
        random1 = (random1 ushr 1) or t1
        random2 = (random2 + random2) or t2
        return random1 xor random2
    }

    private fun requantise() {
        for (band in 0..maxBand) {
            val l = resL[band]
            val r = resR[band]
            val ql = qL[band]
            val qr = qR[band]
            for (part in 0 until 3) {
                val facL = if (l != 0) MusepackTables.STEPS[l + 1] * scf[scfL[band][part] and 0xFF] else 0f
                val facR = if (r != 0) MusepackTables.STEPS[r + 1] * scf[scfR[band][part] and 0xFF] else 0f
                for (n in part * 12 until part * 12 + 12) {
                    val at = n * 32 + band
                    if (msFlag[band]) {
                        when {
                            l != 0 && r != 0 -> {
                                val m = facL * ql[n]
                                val s = facR * qr[n]
                                yL[at] = m + s
                                yR[at] = m - s
                            }
                            l != 0 -> {
                                val m = facL * ql[n]
                                yL[at] = m
                                yR[at] = m
                            }
                            r != 0 -> {
                                val s = facR * qr[n]
                                yL[at] = s
                                yR[at] = -s
                            }
                            else -> {
                                yL[at] = 0f
                                yR[at] = 0f
                            }
                        }
                    } else {
                        yL[at] = if (l != 0) facL * ql[n] else 0f
                        yR[at] = if (r != 0) facR * qr[n] else 0f
                    }
                }
            }
        }
    }

    /** `mpc_decoder_synthese_filter_float` for one channel. */
    private fun synthesis(out: FloatArray, at: Int, v: FloatArray, y: FloatArray, channel: Int) {
        v.copyInto(v, V_MEM, 0, 960)
        var pV = V_MEM
        var outAt = at + channel
        for (n in 0 until 36) {
            pV -= 64
            computeNewV(y, n * 32, v, pV)
            for (k in 0 until 32) {
                val d = k * 16
                val p = pV + k
                out[outAt + k * channels] =
                    v[p] * WINDOW[d] + v[p + 96] * WINDOW[d + 1] + v[p + 128] * WINDOW[d + 2] + v[p + 224] * WINDOW[d + 3] +
                    v[p + 256] * WINDOW[d + 4] + v[p + 352] * WINDOW[d + 5] + v[p + 384] * WINDOW[d + 6] + v[p + 480] * WINDOW[d + 7] +
                    v[p + 512] * WINDOW[d + 8] + v[p + 608] * WINDOW[d + 9] + v[p + 640] * WINDOW[d + 10] + v[p + 736] * WINDOW[d + 11] +
                    v[p + 768] * WINDOW[d + 12] + v[p + 864] * WINDOW[d + 13] + v[p + 896] * WINDOW[d + 14] + v[p + 992] * WINDOW[d + 15]
            }
            outAt += 32 * channels
        }
    }

    companion object {
        /** The scale the reference's float decoder gives samples, so that full scale is 1. */
        private const val FIXED_POINT_SHIFT = 16
        private const val V_MEM = 2304

        private val THRESHOLDS = intArrayOf(0, 0, 3, 0, 0, 1, 3, 4, 8)

        private val QUANTISERS = arrayOf(
            arrayOf(MusepackTables.Q5_1, MusepackTables.Q5_2),
            arrayOf(MusepackTables.Q6_1, MusepackTables.Q6_2),
            arrayOf(MusepackTables.Q7_1, MusepackTables.Q7_2),
            arrayOf(MusepackTables.Q8_1, MusepackTables.Q8_2),
        )

        private val PARITY = IntArray(256) { it.countOneBits() and 1 }

        /** `Di_opt[k][j]`, which is the MPEG-1 synthesis window's D[k + 32j]. */
        private val WINDOW = FloatArray(32 * 16) { i -> Mp3Tables.SYNTHESIS_WINDOW[i / 16 + 32 * (i % 16)].toFloat() }

        /** `mpc_compute_new_V`: Lee's fast DCT of 32 subband samples into 64 V values. */
        private fun computeNewV(s: FloatArray, at: Int, v: FloatArray, pV: Int) {
            var a00 = s[at + 0] + s[at + 31]
            var a01 = s[at + 1] + s[at + 30]
            var a02 = s[at + 2] + s[at + 29]
            var a03 = s[at + 3] + s[at + 28]
            var a04 = s[at + 4] + s[at + 27]
            var a05 = s[at + 5] + s[at + 26]
            var a06 = s[at + 6] + s[at + 25]
            var a07 = s[at + 7] + s[at + 24]
            var a08 = s[at + 8] + s[at + 23]
            var a09 = s[at + 9] + s[at + 22]
            var a10 = s[at + 10] + s[at + 21]
            var a11 = s[at + 11] + s[at + 20]
            var a12 = s[at + 12] + s[at + 19]
            var a13 = s[at + 13] + s[at + 18]
            var a14 = s[at + 14] + s[at + 17]
            var a15 = s[at + 15] + s[at + 16]

            var b00 = a00 + a15
            var b01 = a01 + a14
            var b02 = a02 + a13
            var b03 = a03 + a12
            var b04 = a04 + a11
            var b05 = a05 + a10
            var b06 = a06 + a09
            var b07 = a07 + a08
            var b08 = (a00 - a15) * 0.5024192929f
            var b09 = (a01 - a14) * 0.5224986076f
            var b10 = (a02 - a13) * 0.5669440627f
            var b11 = (a03 - a12) * 0.6468217969f
            var b12 = (a04 - a11) * 0.7881546021f
            var b13 = (a05 - a10) * 1.0606776476f
            var b14 = (a06 - a09) * 1.7224471569f
            var b15 = (a07 - a08) * 5.1011486053f

            a00 = b00 + b07
            a01 = b01 + b06
            a02 = b02 + b05
            a03 = b03 + b04
            a04 = (b00 - b07) * 0.5097956061f
            a05 = (b01 - b06) * 0.6013448834f
            a06 = (b02 - b05) * 0.8999761939f
            a07 = (b03 - b04) * 2.5629155636f
            a08 = b08 + b15
            a09 = b09 + b14
            a10 = b10 + b13
            a11 = b11 + b12
            a12 = (b08 - b15) * 0.5097956061f
            a13 = (b09 - b14) * 0.6013448834f
            a14 = (b10 - b13) * 0.8999761939f
            a15 = (b11 - b12) * 2.5629155636f

            b00 = a00 + a03
            b01 = a01 + a02
            b02 = (a00 - a03) * 0.5411961079f
            b03 = (a01 - a02) * 1.3065630198f
            b04 = a04 + a07
            b05 = a05 + a06
            b06 = (a04 - a07) * 0.5411961079f
            b07 = (a05 - a06) * 1.3065630198f
            b08 = a08 + a11
            b09 = a09 + a10
            b10 = (a08 - a11) * 0.5411961079f
            b11 = (a09 - a10) * 1.3065630198f
            b12 = a12 + a15
            b13 = a13 + a14
            b14 = (a12 - a15) * 0.5411961079f
            b15 = (a13 - a14) * 1.3065630198f

            a00 = b00 + b01
            a01 = (b00 - b01) * 0.7071067691f
            a02 = b02 + b03
            a03 = (b02 - b03) * 0.7071067691f
            a04 = b04 + b05
            a05 = (b04 - b05) * 0.7071067691f
            a06 = b06 + b07
            a07 = (b06 - b07) * 0.7071067691f
            a08 = b08 + b09
            a09 = (b08 - b09) * 0.7071067691f
            a10 = b10 + b11
            a11 = (b10 - b11) * 0.7071067691f
            a12 = b12 + b13
            a13 = (b12 - b13) * 0.7071067691f
            a14 = b14 + b15
            a15 = (b14 - b15) * 0.7071067691f

            // The assignments nest as in the reference, which fixes the order of
            // every float addition.
            v[pV + 48] = -a00
            v[pV + 0] = a01
            v[pV + 8] = a03
            v[pV + 40] = -a02 - v[pV + 8]
            v[pV + 12] = a07
            v[pV + 4] = a05 + v[pV + 12]
            v[pV + 36] = -(v[pV + 4] + a06)
            v[pV + 44] = -a04 - a06 - a07
            v[pV + 14] = a15
            v[pV + 10] = a11 + v[pV + 14]
            v[pV + 6] = v[pV + 10] + a13
            v[pV + 2] = a09 + a13 + a15
            v[pV + 34] = -v[pV + 2] - a14
            v[pV + 38] = v[pV + 34] + a09 - a10 - a11
            var tmp = -(a12 + a14 + a15)
            v[pV + 46] = tmp - a08
            v[pV + 42] = tmp - a10 - a11

            a00 = (s[at + 0] - s[at + 31]) * 0.5006030202f
            a01 = (s[at + 1] - s[at + 30]) * 0.5054709315f
            a02 = (s[at + 2] - s[at + 29]) * 0.5154473186f
            a03 = (s[at + 3] - s[at + 28]) * 0.5310425758f
            a04 = (s[at + 4] - s[at + 27]) * 0.5531039238f
            a05 = (s[at + 5] - s[at + 26]) * 0.5829349756f
            a06 = (s[at + 6] - s[at + 25]) * 0.6225041151f
            a07 = (s[at + 7] - s[at + 24]) * 0.6748083234f
            a08 = (s[at + 8] - s[at + 23]) * 0.7445362806f
            a09 = (s[at + 9] - s[at + 22]) * 0.8393496275f
            a10 = (s[at + 10] - s[at + 21]) * 0.9725682139f
            a11 = (s[at + 11] - s[at + 20]) * 1.1694399118f
            a12 = (s[at + 12] - s[at + 19]) * 1.4841645956f
            a13 = (s[at + 13] - s[at + 18]) * 2.0577809811f
            a14 = (s[at + 14] - s[at + 17]) * 3.4076085091f
            a15 = (s[at + 15] - s[at + 16]) * 10.1900081635f

            b00 = a00 + a15
            b01 = a01 + a14
            b02 = a02 + a13
            b03 = a03 + a12
            b04 = a04 + a11
            b05 = a05 + a10
            b06 = a06 + a09
            b07 = a07 + a08
            b08 = (a00 - a15) * 0.5024192929f
            b09 = (a01 - a14) * 0.5224986076f
            b10 = (a02 - a13) * 0.5669440627f
            b11 = (a03 - a12) * 0.6468217969f
            b12 = (a04 - a11) * 0.7881546021f
            b13 = (a05 - a10) * 1.0606776476f
            b14 = (a06 - a09) * 1.7224471569f
            b15 = (a07 - a08) * 5.1011486053f

            a00 = b00 + b07
            a01 = b01 + b06
            a02 = b02 + b05
            a03 = b03 + b04
            a04 = (b00 - b07) * 0.5097956061f
            a05 = (b01 - b06) * 0.6013448834f
            a06 = (b02 - b05) * 0.8999761939f
            a07 = (b03 - b04) * 2.5629155636f
            a08 = b08 + b15
            a09 = b09 + b14
            a10 = b10 + b13
            a11 = b11 + b12
            a12 = (b08 - b15) * 0.5097956061f
            a13 = (b09 - b14) * 0.6013448834f
            a14 = (b10 - b13) * 0.8999761939f
            a15 = (b11 - b12) * 2.5629155636f

            b00 = a00 + a03
            b01 = a01 + a02
            b02 = (a00 - a03) * 0.5411961079f
            b03 = (a01 - a02) * 1.3065630198f
            b04 = a04 + a07
            b05 = a05 + a06
            b06 = (a04 - a07) * 0.5411961079f
            b07 = (a05 - a06) * 1.3065630198f
            b08 = a08 + a11
            b09 = a09 + a10
            b10 = (a08 - a11) * 0.5411961079f
            b11 = (a09 - a10) * 1.3065630198f
            b12 = a12 + a15
            b13 = a13 + a14
            b14 = (a12 - a15) * 0.5411961079f
            b15 = (a13 - a14) * 1.3065630198f

            a00 = b00 + b01
            a01 = (b00 - b01) * 0.7071067691f
            a02 = b02 + b03
            a03 = (b02 - b03) * 0.7071067691f
            a04 = b04 + b05
            a05 = (b04 - b05) * 0.7071067691f
            a06 = b06 + b07
            a07 = (b06 - b07) * 0.7071067691f
            a08 = b08 + b09
            a09 = (b08 - b09) * 0.7071067691f
            a10 = b10 + b11
            a11 = (b10 - b11) * 0.7071067691f
            a12 = b12 + b13
            a13 = (b12 - b13) * 0.7071067691f
            a14 = b14 + b15
            a15 = (b14 - b15) * 0.7071067691f

            v[pV + 15] = a15
            v[pV + 13] = a07 + v[pV + 15]
            v[pV + 11] = v[pV + 13] + a11
            v[pV + 5] = v[pV + 11] + a05 + a13
            v[pV + 9] = a03 + a11 + a15
            v[pV + 7] = v[pV + 9] + a13
            v[pV + 1] = a01 + a09 + a13 + a15
            v[pV + 33] = -v[pV + 1] - a14
            v[pV + 3] = a05 + a07 + a09 + a13 + a15
            v[pV + 35] = -v[pV + 3] - a06 - a14
            tmp = -(a10 + a11 + a13 + a14 + a15)
            v[pV + 37] = tmp - a05 - a06 - a07
            v[pV + 39] = tmp - a02 - a03
            tmp += a13 - a12
            v[pV + 41] = tmp - a02 - a03
            v[pV + 43] = tmp - a04 - a06 - a07
            tmp = -(a08 + a12 + a14 + a15)
            v[pV + 47] = tmp - a00
            v[pV + 45] = tmp - a04 - a06 - a07

            for (i in 1..15) v[pV + 32 - i] = -v[pV + i]
            v[pV + 32] = -v[pV + 0]
            for (i in 1..15) v[pV + 48 + i] = v[pV + 48 - i]
        }
    }
}

/** Unsigned 32-bit comparison that every target has. */
private object Integer32 {
    fun compareUnsigned(a: Int, b: Int): Int = (a xor Int.MIN_VALUE).compareTo(b xor Int.MIN_VALUE)
}

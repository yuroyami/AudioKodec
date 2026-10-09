/*
 * Translated from Apple's ALAC reference implementation, which is
 * Copyright (c) 2011 Apple Inc. and licensed under the Apache License,
 * Version 2.0. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.alac

import io.github.yuroyami.audiokodec.internal.bits.BitWriterMsb

/**
 * Encodes ALAC packets, as Apple's reference encoder does in its default,
 * searching mode.
 *
 * A packet holds the same run of elements the decoder reads: a channel pair
 * coded as a generalised mid and side, or a single channel, each predicted by
 * an adaptive filter whose residue goes through the adaptive Golomb coder.
 * For every pair the encoder tries five mixes on the first eighth of the
 * packet, then predictor orders of four and eight, and keeps whatever codes
 * smallest; a packet that would come out no smaller than its samples is
 * stored verbatim instead.
 *
 * The predictor coefficients carry over from one packet to the next, and so
 * do the working buffers, whose leftover contents the reference's searches
 * read past the part they refreshed. Both are kept exactly, so that every
 * packet matches what the reference encoder writes for the same input, bit
 * for bit.
 */
internal class AlacEncoder(
    private val channels: Int,
    private val bitDepth: Int,
    private val frameSize: Int = DEFAULT_FRAME_SIZE,
) {
    init {
        require(channels in 1..MAX_CHANNELS) { "ALAC carries one to eight channels, not $channels" }
        require(bitDepth == 16 || bitDepth == 20 || bitDepth == 24 || bitDepth == 32) {
            "ALAC codes 16, 20, 24 or 32-bit audio, not $bitDepth-bit"
        }
        require(frameSize in 16..AlacConfig.MAX_FRAME_LENGTH) { "an ALAC packet holds 16 to ${AlacConfig.MAX_FRAME_LENGTH} frames, not $frameSize" }
    }

    // Persistent state, which the reference keeps across packets on purpose:
    // coefficients that start where the last packet left them code better.
    private val coefsU = Array(channels) { Array(MAX_SEARCHES) { initialCoefs() } }
    private val coefsV = Array(channels) { Array(MAX_SEARCHES) { initialCoefs() } }
    private val lastMixRes = IntArray(MAX_CHANNELS)

    private val mixU = IntArray(frameSize)
    private val mixV = IntArray(frameSize)
    private val predictorU = IntArray(frameSize)
    private val predictorV = IntArray(frameSize)
    private val shiftUV = IntArray(frameSize * 2)

    private val packet = BitWriterMsb(frameSize * channels * ((10 + 32) / 8) + 1)

    /** The largest packet written so far, for the configuration. */
    var maxFrameBytes: Int = 0
        private set

    /**
     * Encodes [frames] frames of [input], interleaved in ALAC's own channel
     * order with each sample right-justified at [bitDepth] bits, and returns
     * the packet.
     */
    fun encode(input: IntArray, frames: Int): ByteArray {
        require(frames in 1..frameSize) { "an ALAC packet holds 1 to $frameSize frames, not $frames" }
        val bits = packet
        bits.reset()
        when (channels) {
            2 -> {
                bits.writeBits(ID_CPE, 3)
                bits.writeBits(0, 4)
                encodeStereo(bits, input, 0, 2, 0, frames)
            }
            1 -> {
                bits.writeBits(ID_SCE, 3)
                bits.writeBits(0, 4)
                encodeMono(bits, input, 0, 1, 0, frames)
            }
            else -> {
                var stereoTag = 0
                var monoTag = 0
                var channel = 0
                while (channel < channels) {
                    val tag = (CHANNEL_MAPS[channels - 1] ushr (channel * 3)) and 7
                    bits.writeBits(tag, 3)
                    if (tag == ID_CPE) {
                        bits.writeBits(stereoTag++, 4)
                        encodeStereo(bits, input, channel, channels, channel, frames)
                        channel += 2
                    } else {
                        bits.writeBits(monoTag++, 4)
                        encodeMono(bits, input, channel, channels, channel, frames)
                        channel++
                    }
                }
            }
        }
        bits.writeBits(ID_END, 3)
        bits.alignToByte()
        val out = bits.toByteArray()
        if (out.size > maxFrameBytes) maxFrameBytes = out.size
        return out
    }

    // A channel pair.

    private fun encodeStereo(bits: BitWriterMsb, input: IntArray, offset: Int, stride: Int, channelIndex: Int, numSamples: Int) {
        val start = bits.bitLength
        val coefsU = coefsU[channelIndex]
        val coefsV = coefsV[channelIndex]

        // Matrixing adds a bit, which 32-bit input has no room for, so two
        // bytes are shifted off; 24-bit input codes better with one off.
        val bytesShifted = when {
            bitDepth == 32 -> 2
            bitDepth >= 24 -> 1
            else -> 0
        }
        val chanBits = bitDepth - bytesShifted * 8 + 1
        val partialFrame = if (numSamples == frameSize) 0 else 1

        val mixBits = DEFAULT_MIX_BITS
        var numU = DEFAULT_NUM_UV
        var numV = DEFAULT_NUM_UV
        val pbFactor = 4
        var dilate = 8

        var minBits1 = 1L shl 31
        var minBits2 = 1L shl 31

        // Find the best mix on the first eighth of the packet.
        var bestRes = lastMixRes[channelIndex]
        for (mixRes in 0..MAX_RES) {
            mix(input, offset, stride, numSamples / dilate, mixBits, mixRes, bytesShifted)
            pcBlock(mixU, predictorU, numSamples / dilate, coefsU[numU - 1], numU, chanBits, DENSHIFT_DEFAULT)
            pcBlock(mixV, predictorV, numSamples / dilate, coefsV[numV - 1], numV, chanBits, DENSHIFT_DEFAULT)
            val bits1 = dynComp(predictorU, numSamples / dilate, chanBits, null)
            val bits2 = dynComp(predictorV, numSamples / dilate, chanBits, null)
            if (bits1 + bits2 < minBits1) {
                minBits1 = bits1 + bits2
                bestRes = mixRes
            }
        }
        lastMixRes[channelIndex] = bestRes
        val mixRes = bestRes
        mix(input, offset, stride, numSamples, mixBits, mixRes, bytesShifted)

        // Then the predictor order for each channel of the mix.
        numU = MIN_UV
        numV = MIN_UV
        minBits1 = 1L shl 31
        minBits2 = 1L shl 31
        var numUV = MIN_UV
        while (numUV <= MAX_UV) {
            dilate = 32
            repeat(8) {
                pcBlock(mixU, predictorU, numSamples / dilate, coefsU[numUV - 1], numUV, chanBits, DENSHIFT_DEFAULT)
                pcBlock(mixV, predictorV, numSamples / dilate, coefsV[numUV - 1], numUV, chanBits, DENSHIFT_DEFAULT)
            }
            dilate = 8
            val bits1 = dynComp(predictorU, numSamples / dilate, chanBits, null)
            if (bits1 * dilate + 16 * numUV < minBits1) {
                minBits1 = bits1 * dilate + 16 * numUV
                numU = numUV
            }
            val bits2 = dynComp(predictorV, numSamples / dilate, chanBits, null)
            if (bits2 * dilate + 16 * numUV < minBits2) {
                minBits2 = bits2 * dilate + 16 * numUV
                numV = numUV
            }
            numUV += 4
        }

        // Store verbatim when compressing would not save anything.
        var minBits = minBits1 + minBits2 + 8 * 8 + partialFrame * 32
        if (bytesShifted != 0) minBits += numSamples.toLong() * (bytesShifted * 8) * 2
        val escapeBits = numSamples.toLong() * bitDepth * 2 + partialFrame * 32 + 2 * 8
        var doEscape = minBits >= escapeBits

        if (!doEscape) {
            bits.writeBits(0, 12)
            bits.writeBits((partialFrame shl 3) or (bytesShifted shl 1), 4)
            if (partialFrame != 0) bits.writeBits(numSamples, 32)
            bits.writeBits(mixBits, 8)
            bits.writeBits(mixRes, 8)
            bits.writeBits(DENSHIFT_DEFAULT, 8)
            bits.writeBits((pbFactor shl 5) or numU, 8)
            for (i in 0 until numU) bits.writeBits(coefsU[numU - 1][i].toInt(), 16)
            bits.writeBits(DENSHIFT_DEFAULT, 8)
            bits.writeBits((pbFactor shl 5) or numV, 8)
            for (i in 0 until numV) bits.writeBits(coefsV[numV - 1][i].toInt(), 16)

            // The shifted-off low bytes, interleaved.
            if (bytesShifted != 0) {
                val bitShift = bytesShifted * 8
                var i = 0
                while (i < numSamples * 2) {
                    bits.writeBits((shiftUV[i] shl bitShift) or shiftUV[i + 1], bitShift * 2)
                    i += 2
                }
            }

            pcBlock(mixU, predictorU, numSamples, coefsU[numU - 1], numU, chanBits, DENSHIFT_DEFAULT)
            dynComp(predictorU, numSamples, chanBits, bits)
            pcBlock(mixV, predictorV, numSamples, coefsV[numV - 1], numV, chanBits, DENSHIFT_DEFAULT)
            dynComp(predictorV, numSamples, chanBits, bits)

            // A compressed packet can still come out larger than verbatim.
            if (bits.bitLength - start >= escapeBits) {
                bits.rewindTo(start)
                doEscape = true
            }
        }
        if (doEscape) {
            bits.writeBits(0, 12)
            bits.writeBits((partialFrame shl 3) or 1, 4)
            if (partialFrame != 0) bits.writeBits(numSamples, 32)
            // The reference de-interleaves 20 and 24-bit samples through its
            // mix buffers here, which the next packet's searches can read.
            if (bitDepth == 20 || bitDepth == 24) mix(input, offset, stride, numSamples, 0, 0, 0)
            for (i in 0 until numSamples) {
                val at = offset + i * stride
                bits.writeBits(input[at], bitDepth)
                bits.writeBits(input[at + 1], bitDepth)
            }
        }
    }

    /**
     * Splits a pair into the generalised mid and side, `u = (r*L + (m-r)*R) / m`
     * and `v = L - R` with `m = 2^mixBits` and `r = mixRes`, or into the two
     * channels as they are when `mixRes` is zero. Shifted-off low bytes are
     * kept aside, interleaved.
     */
    private fun mix(input: IntArray, offset: Int, stride: Int, numSamples: Int, mixBits: Int, mixRes: Int, bytesShifted: Int) {
        val shift = bytesShifted * 8
        val mask = (1 shl shift) - 1
        val m2 = (1 shl mixBits) - mixRes
        for (j in 0 until numSamples) {
            var l = input[offset + j * stride]
            var r = input[offset + j * stride + 1]
            if (bytesShifted != 0) {
                shiftUV[j * 2] = l and mask
                shiftUV[j * 2 + 1] = r and mask
                l = l shr shift
                r = r shr shift
            }
            if (mixRes != 0) {
                mixU[j] = (mixRes * l + m2 * r) shr mixBits
                mixV[j] = l - r
            } else {
                mixU[j] = l
                mixV[j] = r
            }
        }
    }

    // A single channel.

    private fun encodeMono(bits: BitWriterMsb, input: IntArray, offset: Int, stride: Int, channelIndex: Int, numSamples: Int) {
        val start = bits.bitLength
        val coefsU = coefsU[channelIndex]
        val bytesShifted = when {
            bitDepth == 32 -> 2
            bitDepth >= 24 -> 1
            else -> 0
        }
        val shift = bytesShifted * 8
        val mask = (1 shl shift) - 1
        val chanBits = bitDepth - shift
        val partialFrame = if (numSamples == frameSize) 0 else 1

        for (i in 0 until numSamples) {
            val value = input[offset + i * stride]
            if (bytesShifted != 0) {
                shiftUV[i] = value and mask
                mixU[i] = value shr shift
            } else {
                mixU[i] = value
            }
        }

        val pbFactor = 4
        var minBits = 1L shl 31
        var bestU = MIN_UV
        var numU = MIN_UV
        while (numU <= MAX_UV) {
            var dilate = 32
            repeat(7) {
                pcBlock(mixU, predictorU, numSamples / dilate, coefsU[numU - 1], numU, chanBits, DENSHIFT_DEFAULT)
            }
            dilate = 8
            pcBlock(mixU, predictorU, numSamples / dilate, coefsU[numU - 1], numU, chanBits, DENSHIFT_DEFAULT)
            val bits1 = dynComp(predictorU, numSamples / dilate, chanBits, null)
            val numBits = dilate * bits1 + 16 * numU
            if (numBits < minBits) {
                bestU = numU
                minBits = numBits
            }
            numU += 4
        }

        minBits += 4 * 8 + partialFrame * 32
        if (bytesShifted != 0) minBits += numSamples.toLong() * (bytesShifted * 8)
        val escapeBits = numSamples.toLong() * bitDepth + partialFrame * 32 + 2 * 8
        var doEscape = minBits >= escapeBits

        if (!doEscape) {
            bits.writeBits(0, 12)
            bits.writeBits((partialFrame shl 3) or (bytesShifted shl 1), 4)
            if (partialFrame != 0) bits.writeBits(numSamples, 32)
            bits.writeBits(0, 16)
            numU = bestU
            bits.writeBits(DENSHIFT_DEFAULT, 8)
            bits.writeBits((pbFactor shl 5) or numU, 8)
            for (i in 0 until numU) bits.writeBits(coefsU[numU - 1][i].toInt(), 16)
            if (bytesShifted != 0) {
                for (i in 0 until numSamples) bits.writeBits(shiftUV[i], shift)
            }
            pcBlock(mixU, predictorU, numSamples, coefsU[numU - 1], numU, chanBits, DENSHIFT_DEFAULT)
            dynComp(predictorU, numSamples, chanBits, bits)
            if (bits.bitLength - start >= escapeBits) {
                bits.rewindTo(start)
                doEscape = true
            }
        }
        if (doEscape) {
            bits.writeBits(0, 12)
            bits.writeBits((partialFrame shl 3) or 1, 4)
            if (partialFrame != 0) bits.writeBits(numSamples, 32)
            for (i in 0 until numSamples) {
                val value = input[offset + i * stride]
                if (bitDepth == 20 || bitDepth == 24) mixU[i] = value
                bits.writeBits(value, bitDepth)
            }
        }
    }

    // The adaptive predictor, from dp_enc.c.

    /**
     * Predicts [num] samples of [input] into [pc], adapting [coefs] as it
     * goes, exactly as `pc_block` does. Like the reference, the first
     * `numActive` differences are formed even when [num] is shorter, which
     * leaves values past [num] that a later, longer read can see.
     */
    private fun pcBlock(input: IntArray, pc: IntArray, num: Int, coefs: ShortArray, numActive: Int, chanBits: Int, denShift: Int) {
        val chanShift = 32 - chanBits
        pc[0] = input[0]
        for (j in 1..numActive) {
            val del = input[j] - input[j - 1]
            pc[j] = (del shl chanShift) shr chanShift
        }
        val lim = numActive + 1
        val denHalf = 1 shl (denShift - 1)
        for (j in lim until num) {
            val top = input[j - lim]
            var sum = 0
            for (k in 0 until numActive) sum -= coefs[k] * (top - input[j - 1 - k])
            var del = input[j] - top - ((sum + denHalf) shr denShift)
            del = (del shl chanShift) shr chanShift
            pc[j] = del
            var del0 = del
            val sg = signOf(del)
            if (sg > 0) {
                for (k in numActive - 1 downTo 0) {
                    val dd = top - input[j - 1 - k]
                    val sgn = signOf(dd)
                    coefs[k] = (coefs[k] - sgn).toShort()
                    del0 -= (numActive - k) * ((sgn * dd) shr denShift)
                    if (del0 <= 0) break
                }
            } else if (sg < 0) {
                for (k in numActive - 1 downTo 0) {
                    val dd = top - input[j - 1 - k]
                    val sgn = signOf(dd)
                    coefs[k] = (coefs[k] + sgn).toShort()
                    del0 -= (numActive - k) * ((-sgn * dd) shr denShift)
                    if (del0 >= 0) break
                }
            }
        }
    }

    private fun signOf(i: Int): Int = ((-i) ushr 31) or (i shr 31)

    // The adaptive Golomb coder, from ag_enc.c.

    /**
     * Codes [numSamples] residues of [pc] at [bitSize] bits into [out], or
     * only counts the bits when [out] is null, which is how the searches
     * measure a candidate. Returns the bits.
     */
    private fun dynComp(pc: IntArray, numSamples: Int, bitSize: Int, out: BitWriterMsb?): Long {
        var bitCount = 0L
        fun put(value: Int, count: Int) {
            out?.writeBits(value, count)
            bitCount += count
        }
        var mb = MB0.toUInt()
        var zmode = 0u
        var c = 0
        var at = 0
        while (c < numSamples) {
            var k = lg3a((mb shr QBSHIFT).toInt())
            if (k > KB0) k = KB0
            val m = (1u shl k) - 1u
            val del = pc[at++]
            val n = ((abs(del) shl 1) - ((del shr 31) and 1)).toUInt() - zmode

            // dyn_code_32bit: a unary prefix and a binary remainder, or an
            // escape of nine ones and the value at the full width.
            val div = n / m
            var escaped = div >= MAX_PREFIX_32.toUInt()
            if (!escaped) {
                val mod = n - m * div
                val de = if (mod == 0u) 1 else 0
                val numBits = div.toInt() + k + 1 - de
                if (numBits > 25) {
                    escaped = true
                } else {
                    val value = (((1 shl div.toInt()) - 1) shl (numBits - div.toInt())) + mod.toInt() + 1 - de
                    put(value, numBits)
                }
            }
            if (escaped) {
                put((1 shl MAX_PREFIX_32) - 1, MAX_PREFIX_32)
                put(n.toInt(), bitSize)
            }

            c++
            mb = PB0.toUInt() * (n + zmode) + mb - ((PB0.toUInt() * mb) shr QBSHIFT)
            if (n > 0xFFFFu) mb = 0xFFFFu
            zmode = 0u

            if ((mb shl MMULSHIFT) < QB.toUInt() && c < numSamples) {
                zmode = 1u
                var nz = 0
                while (c < numSamples && pc[at] == 0) {
                    at++
                    nz++
                    c++
                    if (nz >= 65535) {
                        zmode = 0u
                        break
                    }
                }
                val kz = mb.toInt().countLeadingZeroBits() - BITOFF + ((mb + MOFF.toUInt()) shr MDENSHIFT).toInt()
                val mz = ((1 shl kz) - 1) and WB0
                dynCode(mz, kz, nz, ::put)
                mb = 0u
            }
        }
        return bitCount
    }

    /** dyn_code: a zero run's length, with an escape past nine ones. */
    private inline fun dynCode(m: Int, k: Int, n: Int, put: (Int, Int) -> Unit) {
        val div = n / m
        if (div < MAX_PREFIX_16) {
            val mod = n % m
            val de = if (mod == 0) 1 else 0
            val numBits = div + k + 1 - de
            if (numBits <= MAX_PREFIX_16 + MAX_DATATYPE_BITS_16) {
                put((((1 shl div) - 1) shl (numBits - div)) + mod + 1 - de, numBits)
                return
            }
        }
        put((((1 shl MAX_PREFIX_16) - 1) shl MAX_DATATYPE_BITS_16) + n, MAX_PREFIX_16 + MAX_DATATYPE_BITS_16)
    }

    private fun lg3a(x: Int): Int = 31 - (x + 3).countLeadingZeroBits()

    private fun abs(a: Int): Int {
        val negative = a shr 31
        return (a xor negative) - negative
    }

    companion object {
        const val DEFAULT_FRAME_SIZE: Int = 4096
        const val MAX_CHANNELS: Int = 8

        private const val MAX_SEARCHES = 16
        private const val MAX_COEFS = 16
        private const val DEFAULT_MIX_BITS = 2
        private const val MAX_RES = 4
        private const val DEFAULT_NUM_UV = 8
        private const val MIN_UV = 4
        private const val MAX_UV = 8

        const val DENSHIFT_DEFAULT: Int = 9
        private const val AINIT = 38
        private const val BINIT = -29
        private const val CINIT = -2

        const val PB0: Int = 40
        const val MB0: Int = 10
        const val KB0: Int = 14
        const val MAX_RUN_DEFAULT: Int = 255
        private const val WB0 = (1 shl KB0) - 1
        private const val QBSHIFT = 9
        private const val QB = 1 shl QBSHIFT
        private const val MMULSHIFT = 2
        private const val MDENSHIFT = QBSHIFT - MMULSHIFT - 1
        private const val MOFF = 1 shl (MDENSHIFT - 2)
        private const val BITOFF = 24
        private const val MAX_PREFIX_16 = 9
        private const val MAX_PREFIX_32 = 9
        private const val MAX_DATATYPE_BITS_16 = 16

        private const val ID_SCE = 0
        private const val ID_CPE = 1
        private const val ID_END = 7

        /**
         * The element each channel position opens, three bits per position,
         * from the reference's `sChannelMaps`: a single channel for the
         * centre, then pairs, then single channels. Its comments promise an
         * LFE element, but the table writes single channels, and so does this.
         */
        private val CHANNEL_MAPS = intArrayOf(
            ID_SCE,
            ID_CPE,
            (ID_CPE shl 3) or ID_SCE,
            (ID_SCE shl 9) or (ID_CPE shl 3) or ID_SCE,
            (ID_CPE shl 9) or (ID_CPE shl 3) or ID_SCE,
            (ID_SCE shl 15) or (ID_CPE shl 9) or (ID_CPE shl 3) or ID_SCE,
            (ID_SCE shl 18) or (ID_SCE shl 15) or (ID_CPE shl 9) or (ID_CPE shl 3) or ID_SCE,
            (ID_SCE shl 21) or (ID_CPE shl 15) or (ID_CPE shl 9) or (ID_CPE shl 3) or ID_SCE,
        )

        /** init_coefs at the default shift: the first three taps, the rest zero. */
        private fun initialCoefs(): ShortArray = ShortArray(MAX_COEFS).also {
            val den = 1 shl DENSHIFT_DEFAULT
            it[0] = ((AINIT * den) shr 4).toShort()
            it[1] = ((BINIT * den) shr 4).toShort()
            it[2] = ((CINIT * den) shr 4).toShort()
        }
    }
}

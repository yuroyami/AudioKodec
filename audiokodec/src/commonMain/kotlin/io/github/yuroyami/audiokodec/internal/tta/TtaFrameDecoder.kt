/*
 * Translated from the TTA Hardware Players Library 1.2, which is Copyright
 * (c) 2004 True Audio Software and licensed under the BSD 3-Clause licence,
 * reproduced in NOTICE. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.tta

import io.github.yuroyami.audiokodec.CorruptStreamException

/**
 * Decodes one True Audio frame, `get_samples` in the reference: the adaptive
 * Rice codes, the adaptive hybrid filter, the fixed first-order predictor,
 * and the inverse of the inter-channel decorrelation.
 *
 * Every frame starts from the same state, so a frame decodes alone. The
 * arithmetic is the reference's: 32-bit and wrapping, with the Rice sums
 * unsigned.
 */
internal class TtaFrameDecoder(private val channels: Int, private val bitsPerSample: Int) {
    private val byteSize = (bitsPerSample + 7) / 8
    private val filterShift = FILTER_SHIFTS[byteSize - 1]
    private val predictorShift = if (byteSize == 1) 4 else 5

    // Samples are coded as the whole bytes they are stored in: a 20-bit sample
    // is coded as the 24-bit value the WAV file held, its low four bits clear.
    private val minValue = -(1 shl (byteSize * 8 - 1))
    private val maxValue = (1 shl (byteSize * 8 - 1)) - 1

    private val filters = Array(channels) { Filter(filterShift) }
    private val k0 = IntArray(channels)
    private val k1 = IntArray(channels)
    private val sum0 = IntArray(channels)
    private val sum1 = IntArray(channels)
    private val last = IntArray(channels)
    private val cache = IntArray(channels)

    private lateinit var data: ByteArray
    private var end = 0
    private var position = 0
    private var bitCache = 0
    private var bitCount = 0

    /**
     * Decodes [blocks] samples per channel from the [length] bytes of [frame],
     * the last four of which are the frame's CRC, into [out] interleaved.
     */
    fun decode(frame: ByteArray, length: Int, blocks: Int, out: IntArray) {
        if (length < 4) throw CorruptStreamException("a True Audio frame is shorter than its checksum")
        val stored = (frame[length - 4].toInt() and 0xFF) or ((frame[length - 3].toInt() and 0xFF) shl 8) or
            ((frame[length - 2].toInt() and 0xFF) shl 16) or ((frame[length - 1].toInt() and 0xFF) shl 24)
        if (crc32(frame, length - 4) != stored) throw CorruptStreamException("a True Audio frame fails its checksum")

        data = frame
        end = length - 4
        position = 0
        bitCache = 0
        bitCount = 0
        for (c in 0 until channels) {
            filters[c].reset()
            k0[c] = 10
            k1[c] = 10
            sum0[c] = shift16(10)
            sum1[c] = shift16(10)
            last[c] = 0
        }

        var at = 0
        for (block in 0 until blocks) {
            for (c in 0 until channels) {
                var unary = readUnary()
                val k: Int
                val depth: Int
                if (unary == 0) {
                    depth = 0
                    k = k0[c]
                } else {
                    depth = 1
                    k = k1[c]
                    unary--
                }
                var value = if (k != 0) (unary shl k) + readBits(k) else unary

                if (depth == 1) {
                    sum1[c] += value - (sum1[c] ushr 4)
                    if (k1[c] > 0 && sum1[c].toUInt() < shift16(k1[c]).toUInt()) {
                        k1[c]--
                    } else if (sum1[c].toUInt() > shift16(k1[c] + 1).toUInt()) {
                        k1[c]++
                    }
                    value += bitShift(k0[c])
                }
                sum0[c] += value - (sum0[c] ushr 4)
                if (k0[c] > 0 && sum0[c].toUInt() < shift16(k0[c]).toUInt()) {
                    k0[c]--
                } else if (sum0[c].toUInt() > shift16(k0[c] + 1).toUInt()) {
                    k0[c]++
                }

                // DEC: odd values are positive, even ones negative or zero.
                value = if (value and 1 != 0) (value + 1) ushr 1 else -(value ushr 1)

                value = filters[c].apply(value)

                // The fixed first-order prediction, ((uint64) x << k) - x) >> k kept to 32 bits.
                value += ((last[c].toLong() * ((1L shl predictorShift) - 1)) shr predictorShift).toInt()
                last[c] = value
                // Not yet a sample: before the decorrelation is undone, a channel
                // holds a difference, which can be twice full scale.
                cache[c] = value
            }
            if (channels > 1) {
                // The last channel carries the mean of itself and the one before;
                // each earlier channel is a difference from the one after it.
                cache[channels - 1] += cache[channels - 2] / 2
                for (r in channels - 2 downTo 0) cache[r] = cache[r + 1] - cache[r]
            }
            for (c in 0 until channels) {
                if (cache[c] < minValue || cache[c] > maxValue) {
                    throw CorruptStreamException("a True Audio frame decodes to a sample its depth cannot hold")
                }
            }
            cache.copyInto(out, at)
            at += channels
        }
    }

    private fun nextByte(): Int {
        if (position >= end) throw CorruptStreamException("a True Audio frame's codes run past its end")
        return data[position++].toInt() and 0xFF
    }

    /** `GET_UNARY`: the count of one bits before the next zero, least significant bit first. */
    private fun readUnary(): Int {
        var value = 0
        while (bitCache == BIT_MASK[bitCount]) {
            value += bitCount
            bitCache = nextByte()
            bitCount = 8
        }
        while (bitCache and 1 != 0) {
            value++
            bitCache = bitCache ushr 1
            bitCount--
        }
        bitCache = bitCache ushr 1
        bitCount--
        return value
    }

    /** `GET_BINARY`: [bits] bits, least significant first. */
    private fun readBits(bits: Int): Int {
        if (bits > 32) throw CorruptStreamException("a True Audio frame's Rice parameter grows past 32 bits")
        var cacheValue = bitCache.toLong() and 0xFFFFFFFFL
        while (bitCount < bits) {
            cacheValue = cacheValue or (nextByte().toLong() shl bitCount)
            bitCount += 8
        }
        val value = (cacheValue and ((1L shl bits) - 1)).toInt()
        cacheValue = cacheValue ushr bits
        bitCount -= bits
        bitCache = (cacheValue and ((1L shl bitCount) - 1)).toInt()
        return value
    }

    /** The adaptive hybrid filter of `filter.h`: eight taps adapted by the sign of the last error. */
    private class Filter(private val shift: Int) {
        private val round = 1 shl (shift - 1)
        private var error = 0
        private val qm = IntArray(ORDER + 1)
        private val dx = IntArray(ORDER + 1)
        private val dl = IntArray(ORDER + 1)

        fun reset() {
            error = 0
            qm.fill(0)
            dx.fill(0)
            dl.fill(0)
        }

        fun apply(input: Int): Int {
            var sum = round
            when {
                error == 0 -> for (i in 0 until ORDER) sum += dl[i] * qm[i]
                error < 0 -> for (i in 0 until ORDER) {
                    qm[i] -= dx[i]
                    sum += dl[i] * qm[i]
                }
                else -> for (i in 0 until ORDER) {
                    qm[i] += dx[i]
                    sum += dl[i] * qm[i]
                }
            }

            dx[8] = ((dl[7] shr 30) or 1) shl 2
            dx[7] = ((dl[6] shr 30) or 1) shl 1
            dx[6] = ((dl[5] shr 30) or 1) shl 1
            dx[5] = (dl[4] shr 30) or 1

            error = input
            val output = input + (sum shr shift)
            dl[8] = output
            dl[7] = dl[8] - dl[7]
            dl[6] = dl[7] - dl[6]
            dl[5] = dl[6] - dl[5]

            dl.copyInto(dl, 0, 1, ORDER + 1)
            dx.copyInto(dx, 0, 1, ORDER + 1)
            return output
        }
    }

    companion object {
        private const val ORDER = 8

        /** `flt_set`: the filter's shift for one, two and three bytes per sample. */
        private val FILTER_SHIFTS = intArrayOf(10, 9, 10)

        /** `bit_mask`: the low n bits set, for n from 0 to 32. */
        private val BIT_MASK = IntArray(33) { n -> if (n == 32) -1 else (1 shl n) - 1 }

        /** `bit_shift`: 1 shl n, held at 0x80000000 from n = 31 on. */
        private fun bitShift(n: Int): Int = if (n < 31) 1 shl n else Int.MIN_VALUE

        /** `shift_16`, which is `bit_shift` four places on. */
        private fun shift16(n: Int): Int = bitShift(n + 4)

        /** The reflected CRC-32 table, polynomial 0xEDB88320, as the reference's `crc32_table` holds it. */
        private val CRC_TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }

        fun crc32(data: ByteArray, length: Int, from: Int = 0): Int {
            var crc = -1
            for (i in from until from + length) crc = (crc ushr 8) xor CRC_TABLE[(crc xor data[i].toInt()) and 0xFF]
            return crc.inv()
        }
    }
}

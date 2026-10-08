/*
 * Translated from Apple's ALAC reference implementation, which is
 * Copyright (c) 2011 Apple Inc. and licensed under the Apache License,
 * Version 2.0. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.alac

import io.github.yuroyami.audiokodec.AudioChannel
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.UnsupportedFeatureException

/**
 * What an ALAC stream's configuration, its "magic cookie", says.
 *
 * MP4 and CAF both carry it, as the body of an `alac` box or a `kuki` chunk,
 * and older encoders wrapped it in `frma` and `alac` atom headers, which are
 * skipped the way the reference decoder skips them.
 */
internal class AlacConfig(
    /** Frames in a full packet; a shorter packet says so itself. */
    val frameLength: Int,
    val bitDepth: Int,
    /** The adaptive Golomb coder's three tuning constants. */
    val pb: Int,
    val mb: Int,
    val kb: Int,
    val channels: Int,
    val maxRun: Int,
    val maxFrameBytes: Long,
    val sampleRate: Long,
) {
    companion object {
        /** Reads a cookie, refusing one the reference decoder would refuse or could not decode safely. */
        fun parse(cookie: ByteArray, offset: Long): AlacConfig {
            var at = 0
            fun atomIs(type: String) = cookie.size >= at + 8 && (0 until 4).all { cookie[at + 4 + it].toInt() == type[it].code }
            if (atomIs("frma")) at += 12
            if (atomIs("alac")) at += 12
            if (cookie.size - at < 24) throw CorruptStreamException("an ALAC configuration is 24 bytes and this one is ${cookie.size - at}", offset)
            fun u8(i: Int) = cookie[at + i].toInt() and 0xFF
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            fun u32(i: Int) = (u16(i).toLong() shl 16) or u16(i + 2).toLong()
            val frameLength = u32(0)
            val compatibleVersion = u8(4)
            val config = AlacConfig(
                frameLength = frameLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                bitDepth = u8(5),
                pb = u8(6),
                mb = u8(7),
                kb = u8(8),
                channels = u8(9),
                maxRun = u16(10),
                maxFrameBytes = u32(12),
                sampleRate = u32(20),
            )
            if (compatibleVersion > 0) {
                throw UnsupportedFeatureException(
                    "ALAC version $compatibleVersion",
                    "this ALAC stream needs decoder version $compatibleVersion, and only version 0 is defined",
                )
            }
            if (config.bitDepth !in BIT_DEPTHS) {
                throw CorruptStreamException("ALAC codes 16, 20, 24 or 32-bit audio, not ${config.bitDepth}-bit", offset)
            }
            if (config.channels !in 1..MAX_CHANNELS) {
                throw CorruptStreamException("ALAC carries one to eight channels, not ${config.channels}", offset)
            }
            if (frameLength !in 1..MAX_FRAME_LENGTH) {
                throw CorruptStreamException("an ALAC packet of $frameLength frames is out of range", offset)
            }
            // With no adaptation bits the Golomb coder shifts by the width of
            // its word, which the reference leaves undefined.
            if (config.kb !in 1..31) throw CorruptStreamException("an ALAC configuration states a coder limit of ${config.kb}", offset)
            return config
        }

        private val BIT_DEPTHS = setOf(16, 20, 24, 32)
        const val MAX_CHANNELS = 8

        /** Far beyond the 4096 every encoder uses, and small enough to allocate for. */
        const val MAX_FRAME_LENGTH = 1L shl 20

        /**
         * The speakers ALAC's channels drive, in the order it stores them, from
         * `ALACChannelLayoutTags`: centre first above two channels.
         */
        fun storedOrder(channels: Int): List<AudioChannel> {
            val c = AudioChannel.FRONT_CENTER
            val l = AudioChannel.FRONT_LEFT
            val r = AudioChannel.FRONT_RIGHT
            val ls = AudioChannel.BACK_LEFT
            val rs = AudioChannel.BACK_RIGHT
            val cs = AudioChannel.BACK_CENTER
            val lfe = AudioChannel.LOW_FREQUENCY
            return when (channels) {
                1 -> listOf(c)
                2 -> listOf(l, r)
                3 -> listOf(c, l, r)
                4 -> listOf(c, l, r, cs)
                5 -> listOf(c, l, r, ls, rs)
                6 -> listOf(c, l, r, ls, rs, lfe)
                7 -> listOf(c, l, r, ls, rs, cs, lfe)
                8 -> listOf(c, AudioChannel.FRONT_LEFT_OF_CENTER, AudioChannel.FRONT_RIGHT_OF_CENTER, l, r, ls, rs, lfe)
                else -> error("ALAC has no layout for $channels channels")
            }
        }
    }
}

/**
 * Decodes ALAC packets into integers.
 *
 * A packet holds a run of elements, each one or two channels, the way AAC
 * lays its frames out. A channel's samples are coded as the residue of an
 * adaptive linear predictor, entropy coded with an adaptive Golomb code, and
 * a pair can be mixed into a sum and a difference first. For 24 and 32-bit
 * audio the lowest one or two bytes of every sample can be sent verbatim
 * beside the coded part, because they are mostly noise that prediction cannot
 * help with.
 *
 * Translated from `ALACDecoder::Decode` and the C files it calls.
 */
internal class AlacDecoder(private val config: AlacConfig, options: DecodeOptions) {

    init {
        // The shift buffer is the largest of the configuration-sized state
        // arrays. Check it before allocating any of them.
        options.checkAllocation(
            (config.frameLength.toLong() + PREDICTOR_SLACK) * 2L * Int.SIZE_BYTES,
            "allocating ALAC predictor state",
        )
    }

    private val capacity = config.frameLength + PREDICTOR_SLACK
    private val mixU = IntArray(capacity)
    private val mixV = IntArray(capacity)
    private val predictor = IntArray(capacity)
    private val shiftBuffer = IntArray(capacity * 2)
    private val coefsU = ShortArray(32)
    private val coefsV = ShortArray(32)
    private val bits = BitReader()

    /**
     * Decodes [length] bytes of [packet] into [out], interleaved in ALAC's
     * own channel order, each sample right-justified at the stream's bit
     * depth, and returns the frames written.
     */
    fun decode(packet: ByteArray, length: Int, out: IntArray): Int {
        val channels = config.channels
        bits.reset(packet, length)
        var numSamples = config.frameLength
        var channelIndex = 0
        loop@ while (true) {
            if (bits.byte >= length) corrupt("the packet ends before its end marker")
            when (bits.read(3)) {
                ID_SCE, ID_LFE -> {
                    if (channelIndex >= channels) corrupt("the packet holds more channels than the stream")
                    numSamples = decodeSingle(numSamples, out, channelIndex)
                    channelIndex++
                }
                ID_CPE -> {
                    if (channelIndex + 2 > channels) break@loop
                    numSamples = decodePair(numSamples, out, channelIndex)
                    channelIndex += 2
                }
                ID_CCE, ID_PCE -> corrupt("the packet holds an element ALAC does not use")
                ID_DSE -> {
                    bits.read(4)
                    val align = bits.read(1)
                    var count = bits.read(8)
                    if (count == 255) count += bits.read(8)
                    if (align != 0) bits.byteAlign()
                    bits.advance(count * 8)
                    if (bits.byte > length) corrupt("a data element runs past the packet")
                }
                ID_FIL -> {
                    var count = bits.read(4)
                    if (count == 15) count += bits.read(8) - 1
                    bits.advance(count * 8)
                    if (bits.byte > length) corrupt("a fill element runs past the packet")
                }
                ID_END -> {
                    bits.byteAlign()
                    break@loop
                }
            }
            // The release build of the reference stops once every channel is
            // decoded, so whatever follows the last one is never read.
            if (channelIndex >= channels) break
        }
        // Channels a packet leaves out are silent.
        for (c in channelIndex until channels) {
            for (i in 0 until numSamples) out[i * channels + c] = 0
        }
        return numSamples
    }

    /** Reads an element header's partial-frame length, shift and escape flags. */
    private class Header(val numSamples: Int, val bytesShifted: Int, val escape: Boolean)

    private fun header(numSamples: Int): Header {
        bits.read(4)
        if (bits.read(12) != 0) corrupt("an element's reserved header bits are set")
        val flags = bits.read(4)
        val bytesShifted = (flags ushr 1) and 3
        if (bytesShifted == 3) corrupt("an element shifts off three bytes")
        var samples = numSamples
        if (flags ushr 3 != 0) {
            val count = (bits.read(16).toLong() shl 16) or bits.read(16).toLong()
            if (count > config.frameLength || count < 1) corrupt("a packet states $count frames, against a limit of ${config.frameLength}")
            samples = count.toInt()
        }
        return Header(samples, bytesShifted, flags and 1 != 0)
    }

    private fun decodeSingle(numSamplesIn: Int, out: IntArray, channelIndex: Int): Int {
        val header = header(numSamplesIn)
        val numSamples = header.numSamples
        var bytesShifted = header.bytesShifted
        val chanBits = config.bitDepth - bytesShifted * 8
        if (!header.escape) {
            bits.read(8)
            bits.read(8)
            val modeAndShift = bits.read(8)
            val factorAndCount = bits.read(8)
            val numU = factorAndCount and 0x1F
            for (i in 0 until numU) coefsU[i] = bits.read(16).toShort()
            var shiftStart = -1L
            if (bytesShifted != 0) {
                shiftStart = bits.position
                bits.advance(bytesShifted * 8 * numSamples)
            }
            dynDecomp(config.pb * (factorAndCount ushr 5) / 4, numSamples, chanBits)
            predict(modeAndShift, numSamples, coefsU, numU, chanBits, mixU)
            if (bytesShifted != 0) {
                val shiftBits = BitReader().also { it.resetAt(bits, shiftStart) }
                val shift = bytesShifted * 8
                for (i in 0 until numSamples) shiftBuffer[i] = shiftBits.read(shift)
            }
        } else {
            readVerbatim(numSamples, chanBits, mixU, null)
            bytesShifted = 0
        }
        val channels = config.channels
        val shift = bytesShifted * 8
        // The reference writes the shifted-off bytes back only at 24 and 32
        // bits; an encoder never shifts at the lower depths.
        val restore = bytesShifted != 0 && config.bitDepth >= 24
        for (i in 0 until numSamples) {
            val value = if (restore) (mixU[i] shl shift) or shiftBuffer[i] else mixU[i]
            out[i * channels + channelIndex] = narrow(value)
        }
        return numSamples
    }

    private fun decodePair(numSamplesIn: Int, out: IntArray, channelIndex: Int): Int {
        val header = header(numSamplesIn)
        val numSamples = header.numSamples
        var bytesShifted = header.bytesShifted
        var mixBits = 0
        var mixRes = 0
        if (!header.escape) {
            val chanBits = config.bitDepth - bytesShifted * 8 + 1
            if (chanBits > 32) corrupt("a channel pair codes ${chanBits}-bit differences")
            mixBits = bits.read(8)
            mixRes = bits.read(8).toByte().toInt()
            val modeAndShiftU = bits.read(8)
            val factorAndCountU = bits.read(8)
            val numU = factorAndCountU and 0x1F
            for (i in 0 until numU) coefsU[i] = bits.read(16).toShort()
            val modeAndShiftV = bits.read(8)
            val factorAndCountV = bits.read(8)
            val numV = factorAndCountV and 0x1F
            for (i in 0 until numV) coefsV[i] = bits.read(16).toShort()
            var shiftStart = -1L
            if (bytesShifted != 0) {
                shiftStart = bits.position
                bits.advance(bytesShifted * 8 * 2 * numSamples)
            }
            dynDecomp(config.pb * (factorAndCountU ushr 5) / 4, numSamples, chanBits)
            predict(modeAndShiftU, numSamples, coefsU, numU, chanBits, mixU)
            dynDecomp(config.pb * (factorAndCountV ushr 5) / 4, numSamples, chanBits)
            predict(modeAndShiftV, numSamples, coefsV, numV, chanBits, mixV)
            if (bytesShifted != 0) {
                val shiftBits = BitReader().also { it.resetAt(bits, shiftStart) }
                val shift = bytesShifted * 8
                for (i in 0 until numSamples * 2) shiftBuffer[i] = shiftBits.read(shift)
            }
        } else {
            readVerbatim(numSamples, config.bitDepth, mixU, mixV)
            bytesShifted = 0
        }
        // The mix is a generalised middle and side: u carries a weighted
        // sum and v the difference, and this undoes it exactly.
        val channels = config.channels
        val shift = bytesShifted * 8
        val restore = bytesShifted != 0 && config.bitDepth >= 24
        for (j in 0 until numSamples) {
            var l: Int
            var r: Int
            if (mixRes != 0) {
                l = mixU[j] + mixV[j] - ((mixRes * mixV[j]) shr mixBits)
                r = l - mixV[j]
            } else {
                l = mixU[j]
                r = mixV[j]
            }
            if (restore) {
                l = (l shl shift) or shiftBuffer[2 * j]
                r = (r shl shift) or shiftBuffer[2 * j + 1]
            }
            out[j * channels + channelIndex] = narrow(l)
            out[j * channels + channelIndex + 1] = narrow(r)
        }
        return numSamples
    }

    /** Reads samples stored without compression, one channel or a pair interleaved. */
    private fun readVerbatim(numSamples: Int, chanBits: Int, u: IntArray, v: IntArray?) {
        val shift = 32 - chanBits
        fun one(): Int = if (chanBits <= 16) {
            (bits.read(chanBits) shl shift) shr shift
        } else {
            val high = (bits.read(16) shl 16) shr shift
            high or bits.read(chanBits - 16)
        }
        for (i in 0 until numSamples) {
            u[i] = one()
            if (v != null) v[i] = one()
        }
        if (bits.byte > bits.length) corrupt("uncompressed samples run past the packet")
    }

    /** The stored value at the stream's depth, as the reference writes it out: the low bits, sign-extended. */
    private fun narrow(value: Int): Int = when (config.bitDepth) {
        16 -> value.toShort().toInt()
        20 -> (value shl 12) shr 12
        24 -> (value shl 8) shr 8
        else -> value
    }

    /** Runs the predictor for one channel, from [predictor] into [into]. */
    private fun predict(modeAndShift: Int, numSamples: Int, coefs: ShortArray, numActive: Int, chanBits: Int, into: IntArray) {
        val mode = modeAndShift ushr 4
        val denShift = modeAndShift and 0x0F
        if (mode == 0) {
            unpcBlock(predictor, into, numSamples, coefs, numActive, chanBits, denShift)
        } else {
            // A first-order pass in place, then the stated predictor.
            unpcBlock(predictor, predictor, numSamples, null, 31, chanBits, 0)
            unpcBlock(predictor, into, numSamples, coefs, numActive, chanBits, denShift)
        }
    }

    /**
     * Translates `dyn_decomp`: the adaptive Golomb decoder, which decodes
     * [numSamples] residues into [predictor], adapting its parameter to a
     * running mean and switching to runs of zeros when the mean falls low.
     */
    private fun dynDecomp(pb: Int, numSamples: Int, maxSize: Int) {
        val kb = config.kb
        val wb = (1 shl kb) - 1
        val data = bits.data
        val length = bits.length
        val maxPos = length.toLong() * 8
        var bitPos = bits.position
        var mb = config.mb
        var zmode = 0
        var c = 0
        while (c < numSamples) {
            if (bitPos >= maxPos) corrupt("the coded samples run past the packet")
            var k = lg3a(mb ushr QB_SHIFT)
            if (k > kb) k = kb
            val m = (1 shl k) - 1
            // dyn_get_32bit
            val n: Int
            run {
                var streamLong = read32(data, length, (bitPos ushr 3).toInt()) shl (bitPos and 7).toInt()
                var result = (streamLong.inv()).countLeadingZeroBits()
                if (result >= MAX_PREFIX_32) {
                    result = streamBits(data, length, bitPos + MAX_PREFIX_32, maxSize)
                    bitPos += MAX_PREFIX_32 + maxSize
                } else {
                    bitPos += result + 1
                    if (k != 1) {
                        streamLong = streamLong shl (result + 1)
                        val v = streamLong ushr (32 - k)
                        bitPos += k - 1
                        result *= m
                        if (v.toUInt() >= 2u) {
                            result += v - 1
                            bitPos += 1
                        }
                    }
                }
                n = result
            }
            val decoded = n + zmode
            val multiplier = (-(decoded and 1)) or 1
            predictor[c++] = ((decoded + 1) ushr 1) * multiplier
            mb = pb * (n + zmode) + mb - ((pb * mb) ushr QB_SHIFT)
            if (n.toUInt() > N_MAX_MEAN_CLAMP.toUInt()) mb = N_MEAN_CLAMP_VAL
            zmode = 0
            if ((mb shl M_MUL_SHIFT).toUInt() < QB.toUInt() && c < numSamples) {
                zmode = 1
                val kz = mb.countLeadingZeroBits() - BIT_OFF + ((mb + M_OFF) ushr M_DEN_SHIFT)
                val mz = ((1 shl kz) - 1) and wb
                // dyn_get
                var streamLong = read32(data, length, (bitPos ushr 3).toInt()) shl (bitPos and 7).toInt()
                val pre = (streamLong.inv()).countLeadingZeroBits()
                val run: Int
                if (pre >= MAX_PREFIX_16) {
                    bitPos += MAX_PREFIX_16
                    streamLong = streamLong shl MAX_PREFIX_16
                    run = streamLong ushr (32 - MAX_DATATYPE_BITS_16)
                    bitPos += MAX_DATATYPE_BITS_16
                } else {
                    bitPos += pre + 1
                    streamLong = streamLong shl (pre + 1)
                    val v = streamLong ushr (32 - kz)
                    bitPos += kz
                    var result = pre * mz + v - 1
                    if (v.toUInt() < 2u) {
                        result -= v - 1
                        bitPos -= 1
                    }
                    run = result
                }
                if (run.toUInt() > (numSamples - c).toUInt()) corrupt("a run of zeros runs past the packet's frames")
                for (j in 0 until run) predictor[c++] = 0
                if (run.toUInt() >= 65535u) zmode = 0
                mb = 0
            }
        }
        bits.position = bitPos
        if (bits.byte > bits.length) corrupt("the coded samples run past the packet")
    }

    /**
     * Translates `unpc_block`: the adaptive predictor. Each output is the
     * residue plus a prediction from the samples before it, and after each one
     * the coefficients take a sign step toward whatever would have predicted
     * better. Orders 4 and 8 have unrolled forms in the C that compute the
     * same thing as the general loop here.
     */
    private fun unpcBlock(input: IntArray, out: IntArray, num: Int, coefs: ShortArray?, numActive: Int, chanBits: Int, denShift: Int) {
        val chanShift = 32 - chanBits
        val denHalf = 1 shl (denShift - 1)
        out[0] = input[0]
        if (numActive == 0) {
            if (num > 1 && input !== out) input.copyInto(out, 1, 1, num)
            return
        }
        if (numActive == 31) {
            var previous = out[0]
            for (j in 1 until num) {
                val del = input[j] + previous
                previous = (del shl chanShift) shr chanShift
                out[j] = previous
            }
            return
        }
        coefs!!
        for (j in 1..numActive) {
            val del = input[j] + out[j - 1]
            out[j] = (del shl chanShift) shr chanShift
        }
        val lim = numActive + 1
        for (j in lim until num) {
            var sum = 0
            val top = out[j - lim]
            for (k in 0 until numActive) sum += coefs[k] * (out[j - 1 - k] - top)
            var del = input[j]
            var del0 = del
            val sign = signOf(del)
            del += top + ((sum + denHalf) shr denShift)
            out[j] = (del shl chanShift) shr chanShift
            if (sign > 0) {
                for (k in numActive - 1 downTo 0) {
                    val dd = top - out[j - 1 - k]
                    val sgn = signOf(dd)
                    coefs[k] = (coefs[k] - sgn).toShort()
                    del0 -= (numActive - k) * ((sgn * dd) shr denShift)
                    if (del0 <= 0) break
                }
            } else if (sign < 0) {
                for (k in numActive - 1 downTo 0) {
                    val dd = top - out[j - 1 - k]
                    val sgn = signOf(dd)
                    coefs[k] = (coefs[k] + sgn).toShort()
                    del0 -= (numActive - k) * ((-sgn * dd) shr denShift)
                    if (del0 >= 0) break
                }
            }
        }
    }

    private fun corrupt(message: String): Nothing = throw CorruptStreamException("ALAC: $message")

    /** A big-endian bit reader over one packet that reads zeros past its end, like `BitBuffer`. */
    private class BitReader {
        var data: ByteArray = ByteArray(0)
            private set
        var length = 0
            private set

        /** Position in bits from the start of the packet. */
        var position = 0L

        val byte: Int get() = (position ushr 3).toInt()

        fun reset(packet: ByteArray, size: Int) {
            data = packet
            length = size
            position = 0
        }

        fun resetAt(other: BitReader, at: Long) {
            data = other.data
            length = other.length
            position = at
        }

        /** Reads [count] bits, up to 16, the way `BitBufferRead` does. */
        fun read(count: Int): Int {
            if (count == 0) return 0
            val at = byte
            val window = (byteAt(at) shl 16) or (byteAt(at + 1) shl 8) or byteAt(at + 2)
            val value = ((window shl (position and 7).toInt()) and 0x00FF_FFFF) ushr (24 - count)
            position += count
            return value
        }

        fun advance(count: Int) {
            position += count
        }

        fun byteAlign() {
            position = (position + 7) and 7L.inv()
        }

        private fun byteAt(at: Int): Int = if (at < length) data[at].toInt() and 0xFF else 0
    }

    private companion object {
        const val ID_SCE = 0
        const val ID_CPE = 1
        const val ID_CCE = 2
        const val ID_LFE = 3
        const val ID_DSE = 4
        const val ID_PCE = 5
        const val ID_FIL = 6
        const val ID_END = 7

        const val QB_SHIFT = 9
        const val QB = 1 shl QB_SHIFT
        const val M_MUL_SHIFT = 2
        const val M_DEN_SHIFT = QB_SHIFT - M_MUL_SHIFT - 1
        const val M_OFF = 1 shl (M_DEN_SHIFT - 2)
        const val BIT_OFF = 24
        const val MAX_PREFIX_16 = 9
        const val MAX_PREFIX_32 = 9
        const val MAX_DATATYPE_BITS_16 = 16
        const val N_MAX_MEAN_CLAMP = 0xFFFF
        const val N_MEAN_CLAMP_VAL = 0xFFFF

        /** Room past a packet's frames for the predictor's warm-up to write into, as the reference's buffers have. */
        const val PREDICTOR_SLACK = 33

        fun lg3a(x: Int): Int = 31 - (x + 3).countLeadingZeroBits()

        fun signOf(i: Int): Int = ((-i) ushr 31) or (i shr 31)

        fun read32(data: ByteArray, length: Int, at: Int): Int {
            var value = 0
            for (k in 0 until 4) value = (value shl 8) or (if (at + k < length) data[at + k].toInt() and 0xFF else 0)
            return value
        }

        /** `getstreambits`: [count] bits from bit [at], up to 32. */
        fun streamBits(data: ByteArray, length: Int, at: Long, count: Int): Int {
            var value = 0L
            for (k in 0 until count) {
                val bit = at + k
                val byte = (bit ushr 3).toInt()
                val b = if (byte < length) (data[byte].toInt() ushr (7 - (bit and 7).toInt())) and 1 else 0
                value = (value shl 1) or b.toLong()
            }
            return value.toInt()
        }
    }
}

/**
 * ALAC for [io.github.yuroyami.audiokodec.internal.PacketStreamDecoder]:
 * decodes a packet and puts its channels into AudioKodec's order.
 */
internal class AlacPacketCodec(
    private val config: AlacConfig,
    sampleRate: Int,
    options: DecodeOptions,
) : io.github.yuroyami.audiokodec.internal.PacketCodec {

    init {
        options.checkAllocation(
            (config.frameLength.toLong() + 1L) * config.channels * Int.SIZE_BYTES,
            "allocating ALAC packet scratch storage",
        )
    }

    private val channels = config.channels
    private val decoder = AlacDecoder(config, options)
    private val stored = AlacConfig.storedOrder(channels)
    private val canonical = stored.sortedBy { it.ordinal }

    /** Which stored channel each output channel comes from. */
    private val order = IntArray(channels) { stored.indexOf(canonical[it]) }
    private val scratch = IntArray((config.frameLength + 1) * channels)
    private val scale = 1f / (1L shl (config.bitDepth - 1)).toFloat()

    override val format: io.github.yuroyami.audiokodec.AudioFormat =
        io.github.yuroyami.audiokodec.AudioFormat(sampleRate, io.github.yuroyami.audiokodec.ChannelLayout.of(canonical))
    override val storedBitDepth: Int = config.bitDepth
    override val maxFramesPerPacket: Int = config.frameLength

    /** Every packet stands alone: the predictor starts again in each. */
    override val preRollFrames: Long = 0

    /** Decodes into existing scratch storage to resolve a packet's actual length. */
    fun decodedFrameCount(packet: ByteArray): Int = decoder.decode(packet, packet.size, scratch)

    override fun decodeInt(packet: ByteArray, length: Int, out: IntArray): Int {
        val frames = decoder.decode(packet, length, scratch)
        for (f in 0 until frames) {
            val base = f * channels
            for (c in 0 until channels) out[base + c] = scratch[base + order[c]]
        }
        return frames
    }

    override fun decode(packet: ByteArray, length: Int, out: FloatArray): Int {
        val frames = decoder.decode(packet, length, scratch)
        for (f in 0 until frames) {
            val base = f * channels
            for (c in 0 until channels) out[base + c] = scratch[base + order[c]] * scale
        }
        return frames
    }

    override fun reset() = Unit
}

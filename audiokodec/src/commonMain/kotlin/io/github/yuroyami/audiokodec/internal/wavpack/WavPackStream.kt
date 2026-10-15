/*
 * Translated from WavPack 5.9.0, commit 5803634a030e2a11dba602ba057b89cc34486c67,
 * which is Copyright (c) 1998 - 2025 David Bryant and licensed under the
 * BSD 3-Clause licence, reproduced in NOTICE. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.wavpack

import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException

/**
 * Reads WavPack's bitstreams, least significant bit of each byte first.
 *
 * The reference reads past the end of a damaged stream from its start again
 * and leaves the block checksum to catch the result; this reads zeros there,
 * which the checksum catches the same way. A valid stream never consumes
 * them, though a lookahead may load them.
 */
internal class WavPackBits {
    private var data: ByteArray = ByteArray(0)
    private var position = 0
    private var end = 0
    private var register = 0L
    private var count = 0

    /** Whether a bitstream was given for this block. */
    var isOpen: Boolean = false
        private set

    fun open(bytes: ByteArray, from: Int, to: Int) {
        data = bytes
        position = from
        end = to
        register = 0
        count = 0
        isOpen = true
    }

    fun close() {
        isOpen = false
    }

    private fun load() {
        val byte = if (position < end) data[position++].toLong() and 0xFF else 0L
        register = register or (byte shl count)
        count += 8
    }

    fun bit(): Int {
        if (count == 0) load()
        val bit = (register and 1L).toInt()
        register = register ushr 1
        count--
        return bit
    }

    /** The next [n] bits, 0 to 32, without consuming them. */
    fun peek(n: Int): Long {
        while (count < n) load()
        return register and ((1L shl n) - 1)
    }

    fun skip(n: Int) {
        register = register ushr n
        count -= n
    }

    /** Reads [n] bits, 0 to 32, as an unsigned value in the low bits of an Int. */
    fun bits(n: Int): Int {
        val value = peek(n)
        skip(n)
        return value.toInt()
    }
}

/** One decorrelation pass, `struct decorr_pass` in the reference. */
internal class WavPackDecorrPass {
    var term = 0
    var delta = 0
    var weightA = 0
    var weightB = 0
    val samplesA = IntArray(MAX_TERM)
    val samplesB = IntArray(MAX_TERM)

    companion object {
        const val MAX_TERM: Int = 8
    }
}

/**
 * Decodes one WavPack block: a mono or stereo stream of the samples in one
 * block of a file, `WavpackStream` and the decoding half of `unpack.c`,
 * `read_words.c`, `entropy_utils.c`, `decorr_utils.c` and
 * `unpack_floats.c` in the reference.
 *
 * Blocks are independent, so a stream is set up from a block's own
 * metadata every time. The correction file of hybrid lossless mode is out
 * of scope, so the paths that read one are not translated, and a hybrid
 * block decodes to its lossy samples.
 */
internal class WavPackStream {
    private var flags = 0
    private var version = 0
    private var termCount = 0
    private val passes = Array(MAX_TERMS) { WavPackDecorrPass() }

    // The entropy decoder's state, `struct words_data`. Unsigned in the reference.
    private val median = Array(2) { IntArray(3) }
    private val slowLevel = IntArray(2)
    private val errorLimit = IntArray(2)
    private val bitrateDelta = IntArray(2)
    private val bitrateAcc = IntArray(2)
    private var holdingOne = 0
    private var holdingZero = 0
    private var zerosAcc = 0

    private var int32SentBits = 0
    private var int32Zeros = 0
    private var int32Ones = 0
    private var int32Dups = 0
    private var int32MaxWidth = 0
    private var floatFlags = 0
    private var floatShift = 0
    private var floatMaxExp = 0
    private var floatNormExp = 0
    private var floatMaxShiftedOnes = 0
    private var floatMinShiftedZeros = 0
    private var crcWvx = 0

    private val wvBits = WavPackBits()
    private val wvxBits = WavPackBits()

    /** File-level facts the first block's metadata states, collected for [WavPackFile]. */
    class FileFacts {
        var channels = 0
        var maxStreams = OLD_MAX_STREAMS
        var channelMask = 0
        var sampleRate = 0
        var md5: ByteArray? = null
    }

    /**
     * The exponent the encoder recorded as one for floating-point audio,
     * normally 127; [WavPackFile] scales other values to -1 to 1.
     */
    val normExponent: Int get() = floatNormExp

    /**
     * Sets the stream up from the block at [offset] in [block], whose header
     * is [header], reading its metadata: `unpack_init` and
     * `process_metadata`. File-level metadata is recorded in [facts] when
     * given. [fileChannels] is the file's channel count, or 0 before it is
     * known.
     */
    fun initialise(block: ByteArray, offset: Int, header: WavPackHeader, fileChannels: Int, facts: FileFacts?) {
        flags = header.flags
        version = header.version
        termCount = 0
        for (pass in passes) {
            pass.term = 0
            pass.delta = 0
            pass.weightA = 0
            pass.weightB = 0
            pass.samplesA.fill(0)
            pass.samplesB.fill(0)
        }
        for (c in 0 until 2) {
            median[c].fill(0)
            slowLevel[c] = 0
            errorLimit[c] = 0
            bitrateDelta[c] = 0
            bitrateAcc[c] = 0
        }
        holdingOne = 0
        holdingZero = 0
        zerosAcc = 0
        int32SentBits = 0
        int32Zeros = 0
        int32Ones = 0
        int32Dups = 0
        int32MaxWidth = 0
        floatFlags = 0
        floatShift = 0
        floatMaxExp = 0
        floatNormExp = 0
        floatMaxShiftedOnes = 0
        floatMinShiftedZeros = 0
        wvBits.close()
        wvxBits.close()

        if (flags and DSD_FLAG != 0) {
            throw UnsupportedFeatureException("WavPack DSD audio", "this WavPack file holds DSD audio, which AudioKodec does not decode")
        }
        if (flags and MONO_FLAG == 0 && fileChannels == 1 && header.blockSamples != 0) {
            throw CorruptStreamException("a WavPack block carries stereo audio in a mono file")
        }
        if (flags and MONO_DATA == MONO_DATA) throw CorruptStreamException("a WavPack block is both mono and false stereo")

        var at = offset + WavPackHeader.SIZE
        val end = offset + header.blockBytes
        while (end - at >= 2) {
            var id = block[at++].toInt() and 0xFF
            var length = (block[at++].toInt() and 0xFF) shl 1
            if (id and ID_LARGE != 0) {
                id = id and ID_LARGE.inv()
                if (end - at < 2) throw CorruptStreamException("a WavPack metadata header runs past its block")
                length += (block[at++].toInt() and 0xFF) shl 9
                length += (block[at++].toInt() and 0xFF) shl 17
            }
            if (id and ID_ODD_SIZE != 0) {
                if (length == 0) throw CorruptStreamException("a WavPack metadata item is odd-sized and empty")
                id = id and ID_ODD_SIZE.inv()
                length--
            }
            val padded = length + (length and 1)
            if (end - at < padded) throw CorruptStreamException("a WavPack metadata item runs past its block")
            if (!process(id, block, at, length, header, facts)) {
                throw CorruptStreamException("a WavPack block has metadata of type 0x${id.toString(16)} it cannot use")
            }
            at += padded
        }
        if (header.blockSamples != 0 && !wvBits.isOpen) {
            throw CorruptStreamException("a WavPack block with audio has no bitstream")
        }
    }

    private fun process(id: Int, data: ByteArray, at: Int, length: Int, header: WavPackHeader, facts: FileFacts?): Boolean {
        fun u8(i: Int) = data[at + i].toInt() and 0xFF
        fun s8(i: Int) = data[at + i].toInt()
        fun s16(i: Int) = ((u8(i) or (u8(i + 1) shl 8)).toShort()).toInt()
        val mono = flags and MONO_DATA != 0
        return when (id) {
            ID_DUMMY -> true
            ID_DECORR_TERMS -> {
                if (length > MAX_TERMS) return false
                termCount = length
                for (i in 0 until length) {
                    val pass = passes[length - 1 - i]
                    pass.term = (u8(i) and 0x1F) - 5
                    pass.delta = (u8(i) shr 5) and 7
                    val term = pass.term
                    if (term == 0 || term < -3 || (term > WavPackDecorrPass.MAX_TERM && term < 17) || term > 18 || (mono && term < 0)) return false
                }
                true
            }
            ID_DECORR_WEIGHTS -> {
                var count = if (mono) length else length / 2
                if (count > termCount) return false
                for (t in 0 until termCount) {
                    passes[t].weightA = 0
                    passes[t].weightB = 0
                }
                var p = termCount - 1
                var i = 0
                while (p >= 0 && count-- > 0) {
                    passes[p].weightA = restoreWeight(s8(i++))
                    if (!mono) passes[p].weightB = restoreWeight(s8(i++))
                    p--
                }
                true
            }
            ID_DECORR_SAMPLES -> {
                for (t in 0 until termCount) {
                    passes[t].samplesA.fill(0)
                    passes[t].samplesB.fill(0)
                }
                var i = 0
                if (version == 0x402 && flags and HYBRID_FLAG != 0) {
                    // Hybrid lossless error history, which only a correction file uses.
                    i += if (mono) 2 else 4
                    if (i > length) return false
                }
                var p = termCount - 1
                while (p >= 0 && i < length) {
                    val pass = passes[p--]
                    if (pass.term > WavPackDecorrPass.MAX_TERM) {
                        if (i + (if (mono) 4 else 8) > length) return false
                        pass.samplesA[0] = exp2s(s16(i))
                        pass.samplesA[1] = exp2s(s16(i + 2))
                        i += 4
                        if (!mono) {
                            pass.samplesB[0] = exp2s(s16(i))
                            pass.samplesB[1] = exp2s(s16(i + 2))
                            i += 4
                        }
                    } else if (pass.term < 0) {
                        if (i + 4 > length) return false
                        pass.samplesA[0] = exp2s(s16(i))
                        pass.samplesB[0] = exp2s(s16(i + 2))
                        i += 4
                    } else {
                        for (m in 0 until pass.term) {
                            if (i + (if (mono) 2 else 4) > length) return false
                            pass.samplesA[m] = exp2s(s16(i))
                            i += 2
                            if (!mono) {
                                pass.samplesB[m] = exp2s(s16(i))
                                i += 2
                            }
                        }
                    }
                }
                i == length
            }
            ID_ENTROPY_VARS -> {
                if (length != (if (mono) 6 else 12)) return false
                for (k in 0 until 3) median[0][k] = exp2s(u8(2 * k) + (u8(2 * k + 1) shl 8))
                if (!mono) for (k in 0 until 3) median[1][k] = exp2s(u8(6 + 2 * k) + (u8(7 + 2 * k) shl 8))
                true
            }
            ID_HYBRID_PROFILE -> readHybridProfile(data, at, length)
            ID_SHAPING_WEIGHTS -> length == 2 || length >= (if (mono) 4 else 8)
            ID_FLOAT_INFO -> {
                if (length != 4) return false
                floatFlags = u8(0)
                floatShift = u8(1)
                floatMaxExp = u8(2)
                floatNormExp = u8(3)
                true
            }
            ID_INT32_INFO -> {
                if (length != 4) return false
                int32SentBits = u8(0)
                int32Zeros = u8(1)
                int32Ones = u8(2)
                int32Dups = u8(3)
                true
            }
            ID_CHANNEL_INFO -> readChannelInfo(data, at, length, facts)
            ID_CHANNEL_IDENTITIES -> length != 0 && (0 until length).none { u8(it) == 0 }
            ID_CONFIG_BLOCK -> true
            ID_NEW_CONFIG_BLOCK -> {
                // The file format, its quirks, and a CAF channel layout with an
                // optional reordering, which must name no more channels than the layout has.
                if (length >= 4) length - 4 <= u8(3) else true
            }
            ID_SAMPLE_RATE -> {
                if (facts != null && (length == 3 || length == 4)) {
                    var rate = u8(0) or (u8(1) shl 8) or (u8(2) shl 16)
                    if (length == 4) rate = rate or ((u8(3) and 0x7F) shl 24)
                    facts.sampleRate = rate
                }
                true
            }
            ID_WV_BITSTREAM -> {
                if (length == 0 || length and 1 != 0) return false
                wvBits.open(data, at, at + length)
                true
            }
            ID_WVC_BITSTREAM -> length != 0 && length and 1 == 0
            ID_WVX_BITSTREAM, ID_WVX_NEW_BITSTREAM -> {
                if (length <= 4 || length and 1 != 0) return false
                crcWvx = u8(0) or (u8(1) shl 8) or (u8(2) shl 16) or (u8(3) shl 24)
                wvxBits.open(data, at + 4, at + length)
                // The newer form opens with one or two five-bit fields.
                if (id == ID_WVX_NEW_BITSTREAM) {
                    if (flags and FLOAT_DATA != 0) {
                        floatMinShiftedZeros = wvxBits.bits(5) and 0x1F
                        floatMaxShiftedOnes = wvxBits.bits(5) and 0x1F
                    } else {
                        int32MaxWidth = wvxBits.bits(5) and 0x1F
                    }
                }
                true
            }
            ID_DSD_BLOCK -> throw UnsupportedFeatureException("WavPack DSD audio", "this WavPack file holds DSD audio, which AudioKodec does not decode")
            ID_MD5_CHECKSUM -> {
                if (facts != null && length == 16) facts.md5 = data.copyOfRange(at, at + 16)
                true
            }
            else -> id and ID_OPTIONAL_DATA != 0
        }
    }

    private fun readHybridProfile(data: ByteArray, at: Int, length: Int): Boolean {
        fun u16(i: Int) = (data[at + i].toInt() and 0xFF) or ((data[at + i + 1].toInt() and 0xFF) shl 8)
        val mono = flags and MONO_DATA != 0
        val step = if (mono) 2 else 4
        var i = 0
        if (flags and HYBRID_BITRATE != 0) {
            if (i + step > length) return false
            slowLevel[0] = exp2s(u16(i))
            i += 2
            if (!mono) {
                slowLevel[1] = exp2s(u16(i))
                i += 2
            }
        }
        if (i + step > length) return false
        bitrateAcc[0] = u16(i) shl 16
        i += 2
        if (!mono) {
            bitrateAcc[1] = u16(i) shl 16
            i += 2
        }
        if (i < length) {
            if (i + step > length) return false
            bitrateDelta[0] = exp2s(u16(i).toShort().toInt())
            i += 2
            if (!mono) {
                bitrateDelta[1] = exp2s(u16(i).toShort().toInt())
                i += 2
            }
            if (i < length) return false
        } else {
            bitrateDelta[0] = 0
            bitrateDelta[1] = 0
        }
        return true
    }

    private fun readChannelInfo(data: ByteArray, at: Int, length: Int, facts: FileFacts?): Boolean {
        if (length == 0 || length > 7) return false
        if (facts == null || facts.channels != 0) return true
        fun u8(i: Int) = data[at + i].toInt() and 0xFF
        var mask: Int
        if (length >= 6) {
            // The newer form, with up to 4096 channels in as many streams.
            facts.channels = (u8(0) or ((u8(2) and 0xF) shl 8)) + 1
            facts.maxStreams = (u8(1) or ((u8(2) and 0xF0) shl 4)) + 1
            if (facts.channels < facts.maxStreams) return false
            mask = u8(3) or (u8(4) shl 8) or (u8(5) shl 16)
            if (length == 7) mask = mask or (u8(6) shl 24)
        } else {
            facts.channels = u8(0)
            mask = 0
            for (i in 1 until length) mask = mask or (u8(i) shl (8 * (i - 1)))
            // The reference accepts the older form beyond eight channels,
            // which ffmpeg 6.1 writes.
            if (facts.channels > OLD_MAX_STREAMS) facts.maxStreams = facts.channels
        }
        if (facts.channels > facts.maxStreams * 2) return false
        facts.channelMask = mask
        if (Integer32.bitCount(mask) > facts.channels) return false
        return true
    }

    /**
     * Decodes the block's [sampleCount] samples into [out]: one value per
     * sample for mono data, two interleaved for stereo, and two for false
     * stereo, the mono data repeated. Values are right-justified integers in
     * the block's byte width, or the bits of 32-bit floats. Damage, which
     * the reference mutes, is a [CorruptStreamException].
     */
    fun unpack(out: IntArray, sampleCount: Int, expectedCrc: Int) {
        val magnitude = (flags and MAG_MASK) ushr MAG_LSB
        var muteLimit = ((1L shl magnitude) + 2).toInt()
        if (flags and HYBRID_FLAG != 0) muteLimit = muteLimit * 2 + 128
        var crc = -1
        var m = 0
        if (flags and MONO_DATA != 0) {
            if (flags and HYBRID_FLAG != 0) {
                for (i in 0 until sampleCount) {
                    val word = getWord(0)
                    if (word == WORD_EOF) throw CorruptStreamException("a WavPack block's bitstream ends early")
                    out[i] = word
                }
            } else if (getWordsLossless(out, sampleCount) != sampleCount) {
                throw CorruptStreamException("a WavPack block's bitstream ends early")
            }
            for (t in 0 until termCount) decorrMonoPass(passes[t], out, sampleCount)
            for (i in 0 until sampleCount) {
                if (kotlin.math.abs(out[i].toLong()) > muteLimit) throw CorruptStreamException("a WavPack block decodes past its stated magnitude")
                crc = crc * 3 + out[i]
            }
        } else {
            if (flags and HYBRID_FLAG != 0) {
                for (i in 0 until sampleCount) {
                    val left = getWord(0)
                    if (left == WORD_EOF) throw CorruptStreamException("a WavPack block's bitstream ends early")
                    out[2 * i] = left
                    val right = getWord(1)
                    if (right == WORD_EOF) throw CorruptStreamException("a WavPack block's bitstream ends early")
                    out[2 * i + 1] = right
                }
            } else if (getWordsLossless(out, sampleCount) != sampleCount) {
                throw CorruptStreamException("a WavPack block's bitstream ends early")
            }
            for (t in 0 until termCount) decorrStereoPass(passes[t], out, sampleCount)
            m = sampleCount and (WavPackDecorrPass.MAX_TERM - 1)
            val joint = flags and JOINT_STEREO != 0
            for (i in 0 until sampleCount) {
                if (joint) {
                    out[2 * i + 1] -= out[2 * i] shr 1
                    out[2 * i] += out[2 * i + 1]
                }
                crc += (crc shl 3) + (out[2 * i] shl 1) + out[2 * i] + out[2 * i + 1]
            }
            // The reference checks every eighth sample pair against the stated magnitude.
            var i = 0
            while (i < sampleCount) {
                if (kotlin.math.abs(out[2 * i].toLong()) > muteLimit || kotlin.math.abs(out[2 * i + 1].toLong()) > muteLimit) {
                    throw CorruptStreamException("a WavPack block decodes past its stated magnitude")
                }
                i += 8
            }
        }
        // Stereo passes leave their history unrotated; this rotates it, as
        // the reference does, though nothing reads it again in this block.
        if (m != 0) {
            for (t in 0 until termCount) {
                val pass = passes[t]
                if (pass.term in 1..WavPackDecorrPass.MAX_TERM) {
                    val a = pass.samplesA.copyOf()
                    val b = pass.samplesB.copyOf()
                    var k2 = m
                    for (k in 0 until WavPackDecorrPass.MAX_TERM) {
                        pass.samplesA[k] = a[k2]
                        pass.samplesB[k] = b[k2]
                        k2 = (k2 + 1) and (WavPackDecorrPass.MAX_TERM - 1)
                    }
                }
            }
        }
        val crcX = fixup(out, sampleCount)
        if (flags and FALSE_STEREO != 0) {
            for (i in sampleCount - 1 downTo 0) {
                out[2 * i + 1] = out[i]
                out[2 * i] = out[i]
            }
        }
        if (crc != expectedCrc || (wvxBits.isOpen && crcX != crcWvx)) {
            throw CorruptStreamException("a WavPack block fails its checksum")
        }
    }

    // Decorrelation, `decorr_mono_pass` and `decorr_stereo_pass`.

    private fun decorrMonoPass(pass: WavPackDecorrPass, buffer: IntArray, count: Int) {
        val delta = pass.delta
        var weight = pass.weightA
        val samples = pass.samplesA
        when (pass.term) {
            17 -> for (i in 0 until count) {
                val sam = 2 * samples[0] - samples[1]
                samples[1] = samples[0]
                val input = buffer[i]
                samples[0] = applyWeight(weight, sam) + input
                weight = updateWeight(weight, delta, sam, input)
                buffer[i] = samples[0]
            }
            18 -> for (i in 0 until count) {
                val sam = (3 * samples[0] - samples[1]) shr 1
                samples[1] = samples[0]
                val input = buffer[i]
                samples[0] = applyWeight(weight, sam) + input
                weight = updateWeight(weight, delta, sam, input)
                buffer[i] = samples[0]
            }
            else -> {
                var m = 0
                var k = pass.term and (WavPackDecorrPass.MAX_TERM - 1)
                for (i in 0 until count) {
                    val sam = samples[m]
                    val input = buffer[i]
                    samples[k] = applyWeight(weight, sam) + input
                    weight = updateWeight(weight, delta, sam, input)
                    buffer[i] = samples[k]
                    m = (m + 1) and (WavPackDecorrPass.MAX_TERM - 1)
                    k = (k + 1) and (WavPackDecorrPass.MAX_TERM - 1)
                }
                if (m != 0) {
                    val temp = samples.copyOf()
                    for (j in 0 until WavPackDecorrPass.MAX_TERM) samples[j] = temp[(m + j) and (WavPackDecorrPass.MAX_TERM - 1)]
                }
            }
        }
        pass.weightA = weight
    }

    private fun decorrStereoPass(pass: WavPackDecorrPass, buffer: IntArray, count: Int) {
        val a = pass.samplesA
        val b = pass.samplesB
        val delta = pass.delta
        when (pass.term) {
            17 -> for (i in 0 until count) {
                var sam = 2 * a[0] - a[1]
                a[1] = a[0]
                var input = buffer[2 * i]
                a[0] = applyWeight(pass.weightA, sam) + input
                buffer[2 * i] = a[0]
                pass.weightA = updateWeight(pass.weightA, delta, sam, input)
                sam = 2 * b[0] - b[1]
                b[1] = b[0]
                input = buffer[2 * i + 1]
                b[0] = applyWeight(pass.weightB, sam) + input
                buffer[2 * i + 1] = b[0]
                pass.weightB = updateWeight(pass.weightB, delta, sam, input)
            }
            18 -> for (i in 0 until count) {
                var sam = a[0] + ((a[0] - a[1]) shr 1)
                a[1] = a[0]
                var input = buffer[2 * i]
                a[0] = applyWeight(pass.weightA, sam) + input
                buffer[2 * i] = a[0]
                pass.weightA = updateWeight(pass.weightA, delta, sam, input)
                sam = b[0] + ((b[0] - b[1]) shr 1)
                b[1] = b[0]
                input = buffer[2 * i + 1]
                b[0] = applyWeight(pass.weightB, sam) + input
                buffer[2 * i + 1] = b[0]
                pass.weightB = updateWeight(pass.weightB, delta, sam, input)
            }
            -1 -> for (i in 0 until count) {
                val left = buffer[2 * i]
                val sam = left + applyWeight(pass.weightA, a[0])
                pass.weightA = updateWeightClip(pass.weightA, delta, a[0], left)
                buffer[2 * i] = sam
                val right = buffer[2 * i + 1]
                a[0] = right + applyWeight(pass.weightB, sam)
                pass.weightB = updateWeightClip(pass.weightB, delta, sam, right)
                buffer[2 * i + 1] = a[0]
            }
            -2 -> for (i in 0 until count) {
                val right = buffer[2 * i + 1]
                val sam = right + applyWeight(pass.weightB, b[0])
                pass.weightB = updateWeightClip(pass.weightB, delta, b[0], right)
                buffer[2 * i + 1] = sam
                val left = buffer[2 * i]
                b[0] = left + applyWeight(pass.weightA, sam)
                pass.weightA = updateWeightClip(pass.weightA, delta, sam, left)
                buffer[2 * i] = b[0]
            }
            -3 -> for (i in 0 until count) {
                val left = buffer[2 * i]
                val right = buffer[2 * i + 1]
                val samA = left + applyWeight(pass.weightA, a[0])
                pass.weightA = updateWeightClip(pass.weightA, delta, a[0], left)
                val samB = right + applyWeight(pass.weightB, b[0])
                pass.weightB = updateWeightClip(pass.weightB, delta, b[0], right)
                b[0] = samA
                buffer[2 * i] = samA
                a[0] = samB
                buffer[2 * i + 1] = samB
            }
            else -> {
                var m = 0
                var k = pass.term and (WavPackDecorrPass.MAX_TERM - 1)
                for (i in 0 until count) {
                    var sam = a[m]
                    var input = buffer[2 * i]
                    a[k] = applyWeight(pass.weightA, sam) + input
                    pass.weightA = updateWeight(pass.weightA, delta, sam, input)
                    buffer[2 * i] = a[k]
                    sam = b[m]
                    input = buffer[2 * i + 1]
                    b[k] = applyWeight(pass.weightB, sam) + input
                    pass.weightB = updateWeight(pass.weightB, delta, sam, input)
                    buffer[2 * i + 1] = b[k]
                    m = (m + 1) and (WavPackDecorrPass.MAX_TERM - 1)
                    k = (k + 1) and (WavPackDecorrPass.MAX_TERM - 1)
                }
            }
        }
    }

    // The entropy decoder, `read_words.c`.

    private fun getMedian(c: Int, k: Int): Int = (median[c][k] ushr 4) + 1

    private fun incMedian(c: Int, k: Int) {
        val divisor = DIVISORS[k]
        median[c][k] += Integer32.divideUnsigned(median[c][k] + divisor, divisor) * 5
    }

    private fun decMedian(c: Int, k: Int) {
        val divisor = DIVISORS[k]
        median[c][k] -= Integer32.divideUnsigned(median[c][k] + divisor - 2, divisor) * 2
    }

    /** Reads an Elias gamma style count of up to 32 bits, or returns -1 when 33 ones mark the end. */
    private fun readCountEscape(): Int {
        var cbits = 0
        while (cbits < 33 && wvBits.bit() == 1) cbits++
        if (cbits == 33) return -1
        if (cbits < 2) return cbits
        var value = 0
        var mask = 1
        while (--cbits > 0) {
            if (wvBits.bit() == 1) value = value or mask
            mask = mask shl 1
        }
        return value or mask
    }

    /** Counts leading ones up to [LIMIT_ONES], with the escape beyond; -1 at the end of the stream. */
    private fun readOnesCount(): Int {
        var ones = 0
        while (ones < LIMIT_ONES + 1 && wvBits.bit() == 1) ones++
        if (ones >= LIMIT_ONES) {
            if (ones == LIMIT_ONES + 1) return -1
            val escape = readCountEscape()
            if (escape < 0) return -1
            ones = escape + LIMIT_ONES
        }
        return ones
    }

    /** `get_words_lossless`: returns the samples read, fewer at the end of the stream. */
    private fun getWordsLossless(buffer: IntArray, sampleCount: Int): Int {
        val mono = flags and MONO_DATA != 0
        val total = if (mono) sampleCount else sampleCount * 2
        var c = 0
        var n = 0
        while (n < total) {
            if (!mono) c = n and 1
            if (holdingZero != 0) {
                holdingZero = 0
                val low = readCode(getMedian(c, 0) - 1)
                decMedian(c, 0)
                buffer[n] = if (wvBits.bit() == 1) low.inv() else low
                if (++n == total) break
                if (!mono) c = n and 1
            }
            if (Integer32.compareUnsigned(median[0][0], 2) < 0 && holdingOne == 0 && Integer32.compareUnsigned(median[1][0], 2) < 0) {
                if (zerosAcc != 0) {
                    if (--zerosAcc != 0) {
                        buffer[n++] = 0
                        continue
                    }
                } else {
                    val run = readCountEscape()
                    if (run < 0) break
                    zerosAcc = run
                    if (zerosAcc != 0) {
                        median[0].fill(0)
                        median[1].fill(0)
                        buffer[n++] = 0
                        continue
                    }
                }
            }
            var ones = readOnesCount()
            if (ones < 0) break
            val held = holdingOne
            holdingOne = ones and 1
            holdingZero = ones.inv() and 1
            ones = (ones ushr 1) + held
            var low: Int
            val high: Int
            if (ones == 0) {
                low = 0
                high = getMedian(c, 0) - 1
                decMedian(c, 0)
            } else {
                low = getMedian(c, 0)
                incMedian(c, 0)
                if (ones == 1) {
                    high = low + getMedian(c, 1) - 1
                    decMedian(c, 1)
                } else {
                    low += getMedian(c, 1)
                    incMedian(c, 1)
                    if (ones == 2) {
                        high = low + getMedian(c, 2) - 1
                        decMedian(c, 2)
                    } else {
                        low += (ones - 2) * getMedian(c, 2)
                        high = low + getMedian(c, 2) - 1
                        incMedian(c, 2)
                    }
                }
            }
            low += readCode(high - low)
            buffer[n++] = if (wvBits.bit() == 1) low.inv() else low
        }
        return if (mono) n else n / 2
    }

    /** `get_word`, for hybrid blocks, without a correction stream. */
    private fun getWord(chan: Int): Int {
        if (median[0][0] and 1.inv() == 0 && holdingZero == 0 && holdingOne == 0 && median[1][0] and 1.inv() == 0) {
            if (zerosAcc != 0) {
                if (--zerosAcc != 0) {
                    slowLevel[chan] -= (slowLevel[chan] + SLO) ushr SLS
                    return 0
                }
            } else {
                val run = readCountEscape()
                if (run < 0) return WORD_EOF
                zerosAcc = run
                if (zerosAcc != 0) {
                    slowLevel[chan] -= (slowLevel[chan] + SLO) ushr SLS
                    median[0].fill(0)
                    median[1].fill(0)
                    return 0
                }
            }
        }
        var ones: Int
        if (holdingZero != 0) {
            holdingZero = 0
            ones = 0
        } else {
            ones = readOnesCount()
            if (ones < 0) return WORD_EOF
            if (holdingOne != 0) {
                holdingOne = ones and 1
                ones = (ones ushr 1) + 1
            } else {
                holdingOne = ones and 1
                ones = ones ushr 1
            }
            holdingZero = holdingOne.inv() and 1
        }
        if (chan == 0) updateErrorLimit()
        val c = chan
        var low: Int
        var high: Int
        if (ones == 0) {
            low = 0
            high = getMedian(c, 0) - 1
            decMedian(c, 0)
        } else {
            low = getMedian(c, 0)
            incMedian(c, 0)
            if (ones == 1) {
                high = low + getMedian(c, 1) - 1
                decMedian(c, 1)
            } else {
                low += getMedian(c, 1)
                incMedian(c, 1)
                if (ones == 2) {
                    high = low + getMedian(c, 2) - 1
                    decMedian(c, 2)
                } else {
                    low += (ones - 2) * getMedian(c, 2)
                    high = low + getMedian(c, 2) - 1
                    incMedian(c, 2)
                }
            }
        }
        low = low and 0x7FFFFFFF
        high = high and 0x7FFFFFFF
        if (low > high) high = low
        var mid = (high + low + 1) ushr 1
        val limit = errorLimit[c]
        if (limit == 0) {
            mid = readCode(high - low) + low
        } else {
            while (Integer32.compareUnsigned(high - low, limit) > 0) {
                if (wvBits.bit() == 1) {
                    low = mid
                    mid = (high + low + 1) ushr 1
                } else {
                    high = mid - 1
                    mid = (high + low + 1) ushr 1
                }
            }
        }
        val sign = wvBits.bit()
        if (flags and HYBRID_BITRATE != 0) {
            slowLevel[c] -= (slowLevel[c] + SLO) ushr SLS
            slowLevel[c] += log2(mid)
        }
        return if (sign == 1) mid.inv() else mid
    }

    private fun updateErrorLimit() {
        bitrateAcc[0] += bitrateDelta[0]
        var bitrate0 = bitrateAcc[0] ushr 16
        if (flags and MONO_DATA != 0) {
            if (flags and HYBRID_BITRATE != 0) {
                val slowLog0 = (slowLevel[0] + SLO) ushr SLS
                errorLimit[0] = if (slowLog0 - bitrate0 > -0x100) exp2s(slowLog0 - bitrate0 + 0x100) else 0
            } else {
                errorLimit[0] = exp2s(bitrate0)
            }
        } else {
            bitrateAcc[1] += bitrateDelta[1]
            var bitrate1 = bitrateAcc[1] ushr 16
            if (flags and HYBRID_BITRATE != 0) {
                val slowLog0 = (slowLevel[0] + SLO) ushr SLS
                val slowLog1 = (slowLevel[1] + SLO) ushr SLS
                if (flags and HYBRID_BALANCE != 0) {
                    val balance = (slowLog1 - slowLog0 + bitrate1 + 1) shr 1
                    if (balance > bitrate0) {
                        bitrate1 = bitrate0 * 2
                        bitrate0 = 0
                    } else if (-balance > bitrate0) {
                        bitrate0 *= 2
                        bitrate1 = 0
                    } else {
                        bitrate1 = bitrate0 + balance
                        bitrate0 -= balance
                    }
                }
                errorLimit[0] = if (slowLog0 - bitrate0 > -0x100) exp2s(slowLog0 - bitrate0 + 0x100) else 0
                errorLimit[1] = if (slowLog1 - bitrate1 > -0x100) exp2s(slowLog1 - bitrate1 + 0x100) else 0
            } else {
                errorLimit[0] = exp2s(bitrate0)
                errorLimit[1] = exp2s(bitrate1)
            }
        }
    }

    /**
     * `read_code`: a value from 0 to [maxCode], unsigned, in the fewest
     * bits, with one more bit only for the values that need it.
     */
    private fun readCode(maxCode: Int): Int {
        if (Integer32.compareUnsigned(maxCode, 2) < 0) return if (maxCode != 0) wvBits.bit() else 0
        val max = maxCode.toLong() and 0xFFFF_FFFFL
        var bitCount = 64 - max.countLeadingZeroBits()
        val extras = ((1L shl bitCount) - max - 1) and 0xFFFF_FFFFL
        val window = wvBits.peek(bitCount)
        var code = window and ((1L shl (bitCount - 1)) - 1)
        if (code >= extras) {
            code = (code shl 1) - extras + ((window ushr (bitCount - 1)) and 1L)
        } else {
            bitCount--
        }
        wvBits.skip(bitCount)
        return code.toInt()
    }

    // The final steps, `fixup_samples` and `float_values`.

    /** Applies the shifts, extended integers and float rebuilding; returns the extended stream's checksum. */
    private fun fixup(buffer: IntArray, sampleCount: Int): Int {
        val count = if (flags and MONO_DATA != 0) sampleCount else sampleCount * 2
        var crcX = -1
        if (flags and FLOAT_DATA != 0) return floatValues(buffer, count)
        val lossy = flags and HYBRID_FLAG != 0
        var shift = (flags and SHIFT_MASK) ushr SHIFT_LSB
        if (flags and INT32_DATA != 0) {
            val sentBits = int32SentBits and 0x1F
            var zeros = int32Zeros and 0x1F
            var ones = int32Ones and 0x1F
            var dups = int32Dups and 0x1F
            val mask = ((1L shl sentBits) - 1).toInt()
            if (wvxBits.isOpen) {
                val maxWidth = int32MaxWidth
                for (i in 0 until count) {
                    var value = buffer[i]
                    if (sentBits != 0) {
                        if (maxWidth != 0) {
                            val positive = if (value < 0) value.inv() else value
                            val width = (32 - positive.countLeadingZeroBits()) + sentBits
                            var toRead = sentBits
                            if (width <= maxWidth || (width - maxWidth).let { toRead -= it; toRead > 0 }) {
                                val data = wvxBits.bits(toRead) and ((1L shl toRead) - 1).toInt()
                                value = ((value shl toRead) or data) shl (sentBits - toRead)
                            } else {
                                value = value shl sentBits
                            }
                        } else {
                            value = (value shl sentBits) or (wvxBits.bits(sentBits) and mask)
                        }
                    }
                    value = extendLowBits(value, zeros, ones, dups)
                    crcX = crcX * 9 + (value and 0xFFFF) * 3 + ((value shr 16) and 0xFFFF)
                    buffer[i] = value
                }
            } else if (sentBits == 0 && zeros + ones + dups != 0) {
                while (lossy && flags and BYTES_STORED == 3 && shift < 8) {
                    if (zeros != 0) zeros-- else if (ones != 0) ones-- else if (dups != 0) dups-- else break
                    shift++
                }
                for (i in 0 until count) buffer[i] = extendLowBits(buffer[i], zeros, ones, dups)
            } else {
                shift += zeros + sentBits + ones + dups
            }
        }
        shift = shift and 0x1F
        if (lossy) {
            val (minValue, maxValue) = when (flags and BYTES_STORED) {
                0 -> (-128 shr shift) to (127 shr shift)
                1 -> (-32768 shr shift) to (32767 shr shift)
                2 -> (-8388608 shr shift) to (8388607 shr shift)
                else -> (Int.MIN_VALUE shr shift) to (Int.MAX_VALUE shr shift)
            }
            val minShifted = minValue shl shift
            val maxShifted = maxValue shl shift
            for (i in 0 until count) {
                val value = buffer[i]
                buffer[i] = when {
                    value < minValue -> minShifted
                    value > maxValue -> maxShifted
                    else -> value shl shift
                }
            }
        } else if (shift != 0) {
            for (i in 0 until count) buffer[i] = buffer[i] shl shift
        }
        return crcX
    }

    private fun extendLowBits(value: Int, zeros: Int, ones: Int, dups: Int): Int = when {
        zeros != 0 -> value shl zeros
        ones != 0 -> ((value + 1) shl ones) - 1
        dups != 0 -> ((value + (value and 1)) shl dups) - (value and 1)
        else -> value
    }

    private fun floatValues(values: IntArray, count: Int): Int {
        if (!wvxBits.isOpen) {
            floatValuesWithoutExtension(values, count)
            return -1
        }
        var crc = -1
        for (i in 0 until count) {
            var exp = floatMaxExp
            var shiftCount = 0
            var out = 0
            var value = values[i]
            if (value == 0) {
                if (floatFlags and FLOAT_ZEROS_SENT != 0) {
                    if (wvxBits.bit() == 1) {
                        out = setMantissa(out, wvxBits.bits(23))
                        if (exp >= 25) out = setExponent(out, wvxBits.bits(8) and 0xFF)
                        out = setSign(out, wvxBits.bit())
                    } else if (floatFlags and FLOAT_NEG_ZEROS != 0) {
                        out = setSign(out, wvxBits.bit())
                    }
                }
            } else {
                value = value shl (floatShift and 0x1F)
                if (value < 0) {
                    value = -value
                    out = setSign(out, 1)
                }
                if (value == 0x1000000) {
                    if (wvxBits.bit() == 1) out = setMantissa(out, wvxBits.bits(23))
                    out = setExponent(out, 255)
                } else {
                    if (exp != 0) {
                        while (value and 0x800000 == 0 && --exp != 0) {
                            shiftCount++
                            value = value shl 1
                        }
                    }
                    shiftCount = shiftCount and 0x1F
                    if (shiftCount != 0) {
                        if (floatFlags and FLOAT_SHIFT_ONES != 0 || (floatFlags and FLOAT_SHIFT_SAME != 0 && wvxBits.bit() == 1)) {
                            value = value or ((1 shl shiftCount) - 1)
                        } else if (floatFlags and FLOAT_SHIFT_SENT != 0) {
                            val mask = (1 shl shiftCount) - 1
                            var zeros = 0
                            if (floatMaxShiftedOnes != 0 && shiftCount > floatMaxShiftedOnes) zeros = shiftCount - floatMaxShiftedOnes
                            if (floatMinShiftedZeros > zeros) zeros = if (floatMinShiftedZeros > shiftCount) shiftCount else floatMinShiftedZeros
                            shiftCount -= zeros
                            if (shiftCount > 0) value = value or ((wvxBits.bits(shiftCount) shl zeros) and mask)
                        }
                    }
                    out = setMantissa(out, value)
                    out = setExponent(out, exp)
                }
            }
            crc = crc * 27 + (out and 0x7FFFFF) * 9 + ((out shr 23) and 0xFF) * 3 + ((out ushr 31) and 1)
            values[i] = out
        }
        return crc
    }

    private fun floatValuesWithoutExtension(values: IntArray, count: Int) {
        for (i in 0 until count) {
            var exp = floatMaxExp
            var shiftCount = 0
            var out = 0
            var value = values[i]
            if (value != 0) {
                value = value shl (floatShift and 0x1F)
                if (value < 0) {
                    value = -value
                    out = setSign(out, 1)
                }
                if (value >= 0x1000000) {
                    while (value and 0xF000000 != 0) {
                        value = value shr 1
                        ++exp
                    }
                } else if (exp != 0) {
                    while (value and 0x800000 == 0 && --exp != 0) {
                        shiftCount++
                        value = value shl 1
                    }
                    shiftCount = shiftCount and 0x1F
                    if (shiftCount != 0 && floatFlags and FLOAT_SHIFT_ONES != 0) value = value or ((1 shl shiftCount) - 1)
                }
                out = setMantissa(out, value)
                out = setExponent(out, exp)
            }
            values[i] = out
        }
    }

    private fun setMantissa(f: Int, v: Int): Int = f xor ((f xor v) and 0x7FFFFF)
    private fun setExponent(f: Int, v: Int): Int = f xor ((f xor (v shl 23)) and 0x7F800000)
    private fun setSign(f: Int, v: Int): Int = f xor ((f xor (v shl 31)) and Int.MIN_VALUE)

    companion object {
        const val MAX_TERMS: Int = 16
        const val OLD_MAX_STREAMS: Int = 8

        const val BYTES_STORED: Int = 3
        const val MONO_FLAG: Int = 4
        const val HYBRID_FLAG: Int = 8
        const val JOINT_STEREO: Int = 0x10
        const val FLOAT_DATA: Int = 0x80
        const val INT32_DATA: Int = 0x100
        const val HYBRID_BITRATE: Int = 0x200
        const val HYBRID_BALANCE: Int = 0x400
        const val INITIAL_BLOCK: Int = 0x800
        const val FINAL_BLOCK: Int = 0x1000
        const val SHIFT_LSB: Int = 13
        const val SHIFT_MASK: Int = 0x1F shl SHIFT_LSB
        const val MAG_LSB: Int = 18
        const val MAG_MASK: Int = 0x1F shl MAG_LSB
        const val SRATE_LSB: Int = 23
        const val SRATE_MASK: Int = 0xF shl SRATE_LSB
        const val HAS_CHECKSUM: Int = 0x10000000
        const val FALSE_STEREO: Int = 0x40000000
        const val DSD_FLAG: Int = Int.MIN_VALUE
        const val MONO_DATA: Int = MONO_FLAG or FALSE_STEREO

        const val ID_UNIQUE: Int = 0x3F
        const val ID_OPTIONAL_DATA: Int = 0x20
        const val ID_ODD_SIZE: Int = 0x40
        const val ID_LARGE: Int = 0x80
        const val ID_DUMMY: Int = 0x0
        const val ID_DECORR_TERMS: Int = 0x2
        const val ID_DECORR_WEIGHTS: Int = 0x3
        const val ID_DECORR_SAMPLES: Int = 0x4
        const val ID_ENTROPY_VARS: Int = 0x5
        const val ID_HYBRID_PROFILE: Int = 0x6
        const val ID_SHAPING_WEIGHTS: Int = 0x7
        const val ID_FLOAT_INFO: Int = 0x8
        const val ID_INT32_INFO: Int = 0x9
        const val ID_WV_BITSTREAM: Int = 0xA
        const val ID_WVC_BITSTREAM: Int = 0xB
        const val ID_WVX_BITSTREAM: Int = 0xC
        const val ID_CHANNEL_INFO: Int = 0xD
        const val ID_DSD_BLOCK: Int = 0xE
        const val ID_CONFIG_BLOCK: Int = ID_OPTIONAL_DATA or 0x5
        const val ID_MD5_CHECKSUM: Int = ID_OPTIONAL_DATA or 0x6
        const val ID_SAMPLE_RATE: Int = ID_OPTIONAL_DATA or 0x7
        const val ID_NEW_CONFIG_BLOCK: Int = ID_OPTIONAL_DATA or 0xA
        const val ID_CHANNEL_IDENTITIES: Int = ID_OPTIONAL_DATA or 0xB
        const val ID_WVX_NEW_BITSTREAM: Int = ID_OPTIONAL_DATA or ID_WVX_BITSTREAM
        const val ID_BLOCK_CHECKSUM: Int = ID_OPTIONAL_DATA or 0xF

        private const val FLOAT_SHIFT_ONES = 1
        private const val FLOAT_SHIFT_SAME = 2
        private const val FLOAT_SHIFT_SENT = 4
        private const val FLOAT_ZEROS_SENT = 8
        private const val FLOAT_NEG_ZEROS = 0x10

        private const val SLS = 8
        private const val SLO = 1 shl (SLS - 1)
        private const val LIMIT_ONES = 16
        private const val WORD_EOF = Int.MIN_VALUE
        private val DIVISORS = intArrayOf(128, 64, 32)

        fun applyWeight(weight: Int, sample: Int): Int =
            if (sample != sample.toShort().toInt()) {
                ((((sample and 0xFFFF) * weight) shr 9) + (((sample and 0xFFFF.inv()) shr 9) * weight) + 1) shr 1
            } else {
                (weight * sample + 512) shr 10
            }

        fun updateWeight(weight: Int, delta: Int, source: Int, result: Int): Int {
            if (source == 0 || result == 0) return weight
            val s = (source xor result) shr 31
            return (delta xor s) + (weight - s)
        }

        fun updateWeightClip(weight: Int, delta: Int, source: Int, result: Int): Int {
            if (source == 0 || result == 0) return weight
            val s = (source xor result) shr 31
            var w = (weight xor s) + (delta - s)
            if (w > 1024) w = 1024
            return (w xor s) - s
        }

        fun restoreWeight(weight: Int): Int {
            var result = weight * 8
            if (result > 0) result += (result + 64) shr 7
            return result
        }

        /** `wp_exp2s`: the value a WavPack logarithm stands for. */
        fun exp2s(log: Int): Int {
            if (log < 0) return (exp2s(-log) - 1).inv()
            val value = (EXP2_TABLE[log and 0xFF].toInt() and 0xFF) or 0x100
            val whole = log shr 8
            return if (whole <= 9) value ushr (9 - whole) else value shl ((whole - 9) and 0x1F)
        }

        /** `wp_log2`, of an unsigned value. */
        fun log2(input: Int): Int {
            val value = input + (input ushr 9)
            val bits: Int
            if (Integer32.compareUnsigned(value, 1 shl 8) < 0) {
                bits = 32 - value.countLeadingZeroBits()
                return (bits shl 8) + (LOG2_TABLE[(value shl (9 - bits)) and 0xFF].toInt() and 0xFF)
            }
            bits = 32 - value.countLeadingZeroBits()
            return (bits shl 8) + (LOG2_TABLE[(value ushr (bits - 9)) and 0xFF].toInt() and 0xFF)
        }

        /** `log2_table` from `entropy_utils.c`, extracted by script. */
        private val LOG2_TABLE: ByteArray = bytes(
            "000103040607090a0b0d0e10111214151618191a1c1d1e20212224252628292a" +
            "2c2d2e2f31323334363738393b3c3d3e3f41424344454748494a4b4d4e4f5051" +
            "525455565758595a5c5d5e5f6061626364666768696a6b6c6d6e6f7071727475" +
            "767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f909192939495" +
            "969798999a9b9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9a9aaabacadaeafb0b1b2b2" +
            "b3b4b5b6b7b8b9b9babbbcbdbebfc0c0c1c2c3c4c5c6c6c7c8c9cacbcbcccdce" +
            "cfd0d0d1d2d3d4d4d5d6d7d8d8d9dadbdcdcdddedfe0e0e1e2e3e4e4e5e6e7e7" +
            "e8e9eaeaebecedeeeeeff0f1f1f2f3f4f4f5f6f7f7f8f9f9fafbfcfcfdfeffff",
        )

        /** `exp2_table` from `entropy_utils.c`, extracted by script. */
        private val EXP2_TABLE: ByteArray = bytes(
            "00010102030304050606070808090a0b0b0c0d0e0e0f10101112131314151616" +
            "171819191a1b1c1d1d1e1f202021222324242526272828292a2b2c2c2d2e2f30" +
            "30313233343535363738393a3a3b3c3d3e3f4041414243444546474848494a4b" +
            "4c4d4e4f50515152535455565758595a5b5c5d5e5e5f60616263646566676869" +
            "6a6b6c6d6e6f707172737475767778797a7b7c7d7e7f8081828384858788898a" +
            "8b8c8d8e8f9091929395969798999a9b9c9d9fa0a1a2a3a4a5a6a8a9aaabacad" +
            "afb0b1b2b3b4b6b7b8b9babcbdbebfc0c2c3c4c5c6c8c9cacbcdcecfd0d2d3d4" +
            "d6d7d8d9dbdcdddee0e1e2e4e5e6e8e9eaecedeef0f1f2f4f5f6f8f9fafcfdff",
        )

        private fun bytes(hex: String): ByteArray {
            val digits = hex.filter { !it.isWhitespace() }
            require(digits.length == 512) { "a WavPack logarithm table has 256 entries, not ${digits.length / 2}" }
            return ByteArray(256) { digits.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        }
    }
}

/** Unsigned 32-bit helpers that every target has. */
internal object Integer32 {
    fun compareUnsigned(a: Int, b: Int): Int = (a xor Int.MIN_VALUE).compareTo(b xor Int.MIN_VALUE)

    fun divideUnsigned(a: Int, b: Int): Int = ((a.toLong() and 0xFFFF_FFFFL) / (b.toLong() and 0xFFFF_FFFFL)).toInt()

    fun bitCount(value: Int): Int = value.countOneBits()
}

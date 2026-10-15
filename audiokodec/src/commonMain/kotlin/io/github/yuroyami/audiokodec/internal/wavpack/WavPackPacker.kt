/*
 * Translated from WavPack 5.9.0, commit 5803634a030e2a11dba602ba057b89cc34486c67,
 * which is Copyright (c) 1998 - 2025 David Bryant and licensed under the
 * BSD 3-Clause licence, reproduced in NOTICE. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.wavpack

import io.github.yuroyami.audiokodec.internal.wavpack.WavPackStream.Companion.applyWeight
import io.github.yuroyami.audiokodec.internal.wavpack.WavPackStream.Companion.exp2s
import io.github.yuroyami.audiokodec.internal.wavpack.WavPackStream.Companion.log2
import io.github.yuroyami.audiokodec.internal.wavpack.WavPackStream.Companion.restoreWeight
import io.github.yuroyami.audiokodec.internal.wavpack.WavPackStream.Companion.updateWeight
import io.github.yuroyami.audiokodec.internal.wavpack.WavPackStream.Companion.updateWeightClip

/**
 * A WavPack block being built: the 32-byte header followed by metadata
 * items, in a buffer that grows as needed. The reference sizes its block
 * buffer in advance and fails when it overflows; this never overflows.
 */
internal class WavPackBlock(capacity: Int = 4096) {
    var bytes: ByteArray = ByteArray(capacity)
        private set

    /** Bytes written so far, header included. */
    var length: Int = 0

    fun ensure(extra: Int) {
        val needed = length + extra
        if (needed > bytes.size) bytes = bytes.copyOf(maxOf(needed, bytes.size * 2))
    }

    fun append(value: Int) {
        if (length == bytes.size) ensure(1)
        bytes[length++] = value.toByte()
    }

    fun append(data: ByteArray, from: Int = 0, count: Int = data.size - from) {
        ensure(count)
        data.copyInto(bytes, length, from, from + count)
        length += count
    }

    fun setU32(at: Int, value: Int) {
        bytes[at] = value.toByte()
        bytes[at + 1] = (value ushr 8).toByte()
        bytes[at + 2] = (value ushr 16).toByte()
        bytes[at + 3] = (value ushr 24).toByte()
    }

    fun u32(at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

    /**
     * Starts the block with a header: `ckSize` is filled in by [finish],
     * and the checksum of the samples by the packer.
     */
    fun startHeader(blockIndex: Long, totalSamples: Long, blockSamples: Int, flags: Int) {
        length = 0
        ensure(WavPackHeader.SIZE)
        append('w'.code)
        append('v'.code)
        append('p'.code)
        append('k'.code)
        append(0)
        append(0)
        append(0)
        append(0)
        append(VERSION and 0xFF)
        append(VERSION ushr 8)
        append((blockIndex ushr 32).toInt())
        // `SET_TOTAL_SAMPLES`: an unknown length is all ones in the low word.
        if (totalSamples < 0) {
            append(0)
            repeat(4) { append(0xFF) }
        } else {
            val total = totalSamples + totalSamples / 0xFFFF_FFFFL
            append((total ushr 32).toInt())
            appendU32(total.toInt())
        }
        appendU32(blockIndex.toInt())
        appendU32(blockSamples)
        appendU32(flags)
        appendU32(0)
    }

    fun appendU32(value: Int) {
        append(value)
        append(value ushr 8)
        append(value ushr 16)
        append(value ushr 24)
    }

    var flags: Int
        get() = u32(24)
        set(value) = setU32(24, value)

    var crc: Int
        get() = u32(28)
        set(value) = setU32(28, value)

    /** Writes one metadata item, padded to an even length: `copy_metadata`. */
    fun putMetadata(id: Int, data: ByteArray, count: Int = data.size) {
        val large = count > 510
        ensure(count + 5)
        append(id or (if (count and 1 != 0) WavPackStream.ID_ODD_SIZE else 0) or (if (large) WavPackStream.ID_LARGE else 0))
        append((count + 1) ushr 1)
        if (large) {
            append((count + 1) ushr 9)
            append((count + 1) ushr 17)
        }
        append(data, 0, count)
        if (count and 1 != 0) append(0)
    }

    /**
     * Fills in the header's `ckSize` and appends the block checksum with
     * [checksumBytes] of 2 or 4: `block_add_checksum`.
     */
    fun finish(checksumBytes: Int) {
        flags = flags or WavPackStream.HAS_CHECKSUM
        setU32(4, length + 2 + checksumBytes - 8)
        var sum = -1
        for (w in 0 until (length shr 1)) {
            sum = sum * 3 + ((bytes[2 * w].toInt() and 0xFF) or ((bytes[2 * w + 1].toInt() and 0xFF) shl 8))
        }
        append(WavPackStream.ID_BLOCK_CHECKSUM)
        append(checksumBytes shr 1)
        if (checksumBytes == 4) {
            appendU32(sum)
        } else {
            sum = sum xor (sum ushr 16)
            append(sum)
            append(sum ushr 8)
        }
    }

    /**
     * Recomputes the checksum of a finished block after its header or
     * metadata changed: `block_update_checksum`.
     */
    fun updateChecksum() {
        if (flags and WavPackStream.HAS_CHECKSUM == 0) return
        var at = WavPackHeader.SIZE
        while (length - at >= 2) {
            val id = bytes[at].toInt() and 0xFF
            var size = (bytes[at + 1].toInt() and 0xFF) shl 1
            at += 2
            if (id and WavPackStream.ID_LARGE != 0) {
                size += ((bytes[at].toInt() and 0xFF) shl 9) + ((bytes[at + 1].toInt() and 0xFF) shl 17)
                at += 2
            }
            if (id and WavPackStream.ID_UNIQUE == WavPackStream.ID_BLOCK_CHECKSUM) {
                var sum = -1
                for (w in 0 until ((at - 2) shr 1)) {
                    sum = sum * 3 + ((bytes[2 * w].toInt() and 0xFF) or ((bytes[2 * w + 1].toInt() and 0xFF) shl 8))
                }
                if (size == 4) {
                    setU32(at, sum)
                } else {
                    sum = sum xor (sum ushr 16)
                    bytes[at] = sum.toByte()
                    bytes[at + 1] = (sum ushr 8).toByte()
                }
                return
            }
            at += size
        }
    }

    companion object {
        /** The stream version the reference writes when it may use every feature. */
        const val VERSION: Int = 0x410
    }
}

/**
 * Writes a WavPack bitstream into a [WavPackBlock], least significant bit
 * of each byte first: `putbit`, `putbits` and `bs_close_write`.
 */
internal class WavPackBitWriter {
    private lateinit var block: WavPackBlock
    private var start = 0
    private var register = 0L
    private var count = 0

    fun open(into: WavPackBlock) {
        block = into
        start = into.length
        register = 0
        count = 0
    }

    fun bit(bit: Int) {
        if (bit != 0) register = register or (1L shl count)
        if (++count == 8) {
            block.append(register.toInt())
            register = 0
            count = 0
        }
    }

    /** Writes the low [bits] bits of [value]. */
    fun bits(value: Long, bits: Int) {
        register = register or ((value and ((1L shl bits) - 1)) shl count)
        count += bits
        while (count >= 8) {
            block.append(register.toInt())
            register = register ushr 8
            count -= 8
        }
    }

    /**
     * Pads the last byte with ones, and the stream to an even length with a
     * byte of ones, and returns the bytes written.
     */
    fun close(): Int {
        while (true) {
            while (count != 0) bit(1)
            if ((block.length - start) and 1 != 0) bit(1) else break
        }
        return block.length - start
    }
}

/**
 * Facts about the whole file that the packer of each stream needs for the
 * metadata it writes: `WavpackContext.config` in the reference.
 */
internal class WavPackConfig(
    val sampleRate: Int,
    val channels: Int,
    val channelMask: Int,
    /** The reference's `CONFIG_*` flags, of which the upper three bytes are stored. */
    val configFlags: Int,
    /** The filter each block starts from, the first of the mode's table. */
    val filter: WavPackFilter,
)

/** A decorrelation filter: `WavpackDecorrSpec`. */
internal class WavPackFilter(val jointStereo: Boolean, val delta: Int, val terms: IntArray) {
    companion object {
        // The first entry of each of the four tables in `decorr_tables.h`,
        // which is the filter each mode uses unless an extra mode searches.
        val FAST = WavPackFilter(true, 2, intArrayOf(18, 17))
        val NORMAL = WavPackFilter(true, 2, intArrayOf(18, 18, 2, 17, 3))
        val HIGH = WavPackFilter(true, 2, intArrayOf(18, 18, 18, -2, 2, 3, 5, -1, 17, 4))
        val VERY_HIGH = WavPackFilter(true, 2, intArrayOf(18, 18, 2, 3, -2, 18, 2, 4, 7, 5, 3, 6, 8, -1, 18, 2))
    }
}

/**
 * Packs the blocks of one WavPack stream, a mono or stereo pair of a file's
 * channels: `WavpackStream` and the lossless encoding half of `pack.c`,
 * `write_words.c` and the default filter search of `extra1.c` and
 * `extra2.c` in the reference.
 *
 * A stream carries its filter, weights, history and entropy medians from
 * one block to the next, writing into each block what a decoder needs to
 * start from it alone. The arithmetic is the reference's: 32-bit and
 * wrapping, with its unsigned quantities kept in signed integers and
 * compared and divided unsigned.
 */
internal class WavPackPacker(
    private val config: WavPackConfig,
    private val streamIndex: Int,
    /** The stream's flags as configured, before any block changes them. */
    val streamFlags: Int,
) {
    /** The header flags of the block being packed: `wphdr.flags`. */
    private var flags = streamFlags
    private var numTerms = 0
    private val passes = Array(MAX_NTERMS) { WavPackDecorrPass() }

    // The entropy coder's state, `struct words_data`.
    private val median = Array(2) { IntArray(3) }
    private var holdingOne = 0
    private var holdingZero = 0
    private var zerosAcc = 0
    private var pendData = 0L
    private var pendCount = 0

    private var falseStereo = false
    private var shift = 0
    private var int32SentBits = 0
    private var int32Zeros = 0
    private var int32Ones = 0
    private var int32Dups = 0
    private var crcX = 0

    private val wvBits = WavPackBitWriter()
    private val wvxBits = WavPackBitWriter()

    // Working space, grown to the largest block seen.
    private var scratch = Array(3) { IntArray(0) }
    private var saved = IntArray(0)
    private var original = IntArray(0)

    /** The first sample of the next block: `sample_index`. */
    var sampleIndex: Long = 0
        private set

    private val mono: Boolean get() = flags and WavPackStream.MONO_DATA != 0

    /**
     * Packs [sampleCount] samples of [buffer], interleaved when the stream
     * is stereo, into [out] as one complete block with its checksum:
     * `pack_stream_block` and `pack_block`. [pending] is metadata the first
     * stream's block carries before its own, the RIFF header in practice.
     * The buffer is used as working space.
     */
    fun packBlock(buffer: IntArray, sampleCount: Int, totalSamples: Long, pending: List<Pair<Int, ByteArray>>, out: WavPackBlock) {
        // `pack_streams` sets the magnitude to the most the bytes can hold.
        var localFlags = streamFlags and WavPackStream.MAG_MASK.inv()
        localFlags += (1 shl WavPackStream.MAG_LSB) * ((localFlags and WavPackStream.BYTES_STORED) * 8 + 7)
        flags = localFlags
        val savedFlags = localFlags

        // Stereo whose two channels are the same throughout goes as mono.
        if (localFlags and WavPackStream.MONO_FLAG == 0) {
            var lor = 0
            var diff = 0
            var i = 0
            while (i < sampleCount) {
                lor = lor or buffer[2 * i] or buffer[2 * i + 1]
                diff = diff or (buffer[2 * i] xor buffer[2 * i + 1])
                if (lor != 0 && diff != 0) break
                i++
            }
            if (i == sampleCount && lor != 0 && diff == 0) {
                localFlags = localFlags and (WavPackStream.JOINT_STEREO or CROSS_DECORR or WavPackStream.HYBRID_BALANCE).inv()
                localFlags = localFlags or WavPackStream.FALSE_STEREO
                flags = localFlags
                for (k in 0 until sampleCount) buffer[k] = buffer[2 * k]
                if (!falseStereo) {
                    falseStereo = true
                    numTerms = 0
                    initWords()
                }
            } else if (falseStereo) {
                falseStereo = false
                numTerms = 0
                initWords()
            }
        }

        // The audio is whole bytes, so the fixed shift of 12 or 20-bit audio
        // in wider containers never applies.
        val values = if (localFlags and WavPackStream.MONO_DATA != 0) sampleCount else sampleCount * 2
        var extended = false
        if ((localFlags and WavPackStream.MAG_MASK) ushr WavPackStream.MAG_LSB >= 24) {
            // Integers wider than 24 bits: the entropy coder takes the top
            // 24, and the rest go verbatim into an extension stream.
            if (original.size < values) original = IntArray(values)
            buffer.copyInto(original, 0, 0, values)
            extended = scanInt32Data(buffer, values)
            numTerms = 0
        } else {
            scanInt32Quick(buffer, values)
            if (shift != int32Zeros + int32Ones + int32Dups) {
                shift = int32Zeros + int32Ones + int32Dups
                numTerms = 0
            }
        }

        // A first block, a change of shape or of magnitude starts the filter
        // and the entropy coder again from what this block's samples suggest.
        if (numTerms == 0) {
            if (localFlags and WavPackStream.MONO_DATA != 0) executeMono(buffer, sampleCount) else executeStereo(buffer, sampleCount)
        }

        packSamples(buffer, sampleCount, totalSamples, pending, out)
        flags = savedFlags

        if (extended) {
            // `send_int32_data`, after the main bitstream.
            val headerAt = out.length
            out.ensure(8)
            out.length += 8
            wvxBits.open(out)
            val preShift = int32Zeros + int32Ones + int32Dups
            val sentBits = int32SentBits
            val mask = (1 shl sentBits) - 1
            for (i in 0 until values) {
                val value = (original[i] shr preShift) and mask
                wvxBits.bits(value.toLong() and 0xFFFF_FFFFL, sentBits)
            }
            val dataCount = wvxBits.close()
            if (dataCount != 0) {
                val withCrc = dataCount + 4
                out.bytes[headerAt] = (WavPackStream.ID_WVX_BITSTREAM or WavPackStream.ID_LARGE).toByte()
                out.bytes[headerAt + 1] = (withCrc ushr 1).toByte()
                out.bytes[headerAt + 2] = (withCrc ushr 9).toByte()
                out.bytes[headerAt + 3] = (withCrc ushr 17).toByte()
                out.setU32(headerAt + 4, crcX)
            } else {
                out.length = headerAt
            }
        }

        out.finish(4)
        sampleIndex += sampleCount
    }

    // Scanning for redundant low bits, `scan_int32_quick` and `scan_int32_data`.

    private fun scanInt32Quick(values: IntArray, count: Int) {
        var magData = 0
        var orData = 0
        var xorData = 0
        var andData = -1
        int32SentBits = 0
        int32Zeros = 0
        int32Ones = 0
        int32Dups = 0
        for (i in 0 until count) {
            val v = values[i]
            magData = magData or (if (v < 0) v.inv() else v)
            xorData = xorData or (v xor -(v and 1))
            andData = andData and v
            orData = orData or v
            if (orData and 1 != 0 && andData and 1 == 0 && xorData and 2 != 0) return
        }
        flags = flags and WavPackStream.MAG_MASK.inv()
        while (magData != 0) {
            flags += 1 shl WavPackStream.MAG_LSB
            magData = magData ushr 1
        }
        if (flags and WavPackStream.MAG_MASK == 0) return
        val totalShift = removeRedundantBits(orData, andData, xorData)
        if (totalShift != 0) {
            flags = flags or WavPackStream.INT32_DATA
            for (i in 0 until count) values[i] = values[i] shr totalShift
        }
    }

    /**
     * Returns whether bits must still go verbatim into an extension stream
     * to bring the samples down to the 24 the entropy coder handles.
     */
    private fun scanInt32Data(values: IntArray, count: Int): Boolean {
        var magData = 0
        var orData = 0
        var xorData = 0
        var andData = -1
        var crc = -1
        int32SentBits = 0
        int32Zeros = 0
        int32Ones = 0
        int32Dups = 0
        for (i in 0 until count) {
            val v = values[i]
            crc = crc * 9 + (v and 0xFFFF) * 3 + ((v shr 16) and 0xFFFF)
            magData = magData or (if (v < 0) v.inv() else v)
            xorData = xorData or (v xor -(v and 1))
            andData = andData and v
            orData = orData or v
        }
        crcX = crc
        flags = flags and WavPackStream.MAG_MASK.inv()
        while (magData != 0) {
            flags += 1 shl WavPackStream.MAG_LSB
            magData = magData ushr 1
        }
        if ((flags and WavPackStream.MAG_MASK) ushr WavPackStream.MAG_LSB == 0) {
            flags = flags and WavPackStream.INT32_DATA.inv()
            return false
        }
        var totalShift = removeRedundantBits(orData, andData, xorData)
        val magnitude = (flags and WavPackStream.MAG_MASK) ushr WavPackStream.MAG_LSB
        if (magnitude > 23) {
            int32SentBits = magnitude - 23
            totalShift += int32SentBits
            flags = flags and WavPackStream.MAG_MASK.inv()
            flags += 23 shl WavPackStream.MAG_LSB
        }
        if (totalShift != 0) {
            flags = flags or WavPackStream.INT32_DATA
            for (i in 0 until count) values[i] = values[i] shr totalShift
        }
        return int32SentBits != 0
    }

    /** Counts low bits that are always zero, always one, or copies of the bit above. */
    private fun removeRedundantBits(orBits: Int, andBits: Int, xorBits: Int): Int {
        var orData = orBits
        var andData = andBits
        var xorData = xorBits
        var total = 0
        if (orData and 1 == 0) {
            while (orData and 1 == 0) {
                flags -= 1 shl WavPackStream.MAG_LSB
                int32Zeros++
                total++
                orData = orData ushr 1
            }
        } else if (andData and 1 != 0) {
            while (andData and 1 != 0) {
                flags -= 1 shl WavPackStream.MAG_LSB
                int32Ones++
                total++
                andData = andData ushr 1
            }
        } else if (xorData and 2 == 0) {
            while (xorData and 2 == 0) {
                flags -= 1 shl WavPackStream.MAG_LSB
                int32Dups++
                total++
                xorData = xorData ushr 1
            }
        }
        return total
    }

    // Packing, `pack_samples`.

    private fun packSamples(buffer: IntArray, sampleCount: Int, totalSamples: Long, pending: List<Pair<Int, ByteArray>>, out: WavPackBlock) {
        val blockFlags = flags
        val isMono = blockFlags and WavPackStream.MONO_DATA != 0
        val values = if (isMono) sampleCount else sampleCount * 2

        // The checksum of the samples as they will decode, before decorrelation.
        var crc = -1
        if (isMono) {
            for (i in 0 until sampleCount) crc += (crc shl 1) + buffer[i]
        } else {
            for (i in 0 until sampleCount) {
                val left = buffer[2 * i]
                crc += (crc shl 3) + (left shl 1) + left + buffer[2 * i + 1]
            }
        }

        out.startHeader(sampleIndex, totalSamples, sampleCount, blockFlags)
        if (streamIndex == 0) for ((id, data) in pending) out.putMetadata(id, data)
        val restart = out.length

        // A residual this large in the higher modes means the filter has run
        // away; the block is packed again with only its first five terms.
        val repackPossible = numTerms > REPACK_SAFE_NUM_TERMS
        val repackMask = if ((blockFlags and WavPackStream.MAG_MASK) ushr WavPackStream.MAG_LSB >= 16) 0xF000_0000.toInt() else 0xFFF0_0000.toInt()
        val snapshot = if (repackPossible) Snapshot(this) else null
        if (repackPossible) {
            if (saved.size < values) saved = IntArray(values)
            buffer.copyInto(saved, 0, 0, values)
        }

        while (true) {
            var maxMagnitude = 0
            var m = 0
            writeDecorrTerms(out)
            writeDecorrWeights(out)
            writeDecorrSamples(out)
            writeEntropyVars(out)
            if (blockFlags and WavPackStream.INT32_DATA != 0) {
                out.putMetadata(WavPackStream.ID_INT32_INFO, byteArrayOf(int32SentBits.toByte(), int32Zeros.toByte(), int32Ones.toByte(), int32Dups.toByte()))
            }
            sendGeneralMetadata(out)

            val headerAt = out.length
            out.ensure(4)
            out.length += 4
            wvBits.open(out)
            if (isMono) {
                maxMagnitude = decorrMonoBuffer(buffer, sampleCount)
                m = sampleCount and (MAX_TERM - 1)
            } else {
                if (blockFlags and WavPackStream.JOINT_STEREO != 0) {
                    for (i in 0 until sampleCount) {
                        buffer[2 * i] -= buffer[2 * i + 1]
                        buffer[2 * i + 1] += buffer[2 * i] shr 1
                    }
                }
                for (t in 0 until numTerms) decorrStereoPass(passes[t], buffer, sampleCount)
                m = sampleCount and (MAX_TERM - 1)
                if (repackPossible) maxMagnitude = scanMaxMagnitude(buffer, values)
            }
            sendWordsLossless(buffer, sampleCount, isMono)

            // The passes leave their history unrotated; put it in order.
            if (m != 0) {
                for (t in 0 until numTerms) {
                    val pass = passes[t]
                    if (pass.term in 1..MAX_TERM) {
                        val a = pass.samplesA.copyOf()
                        val b = pass.samplesB.copyOf()
                        var k2 = m
                        for (k in 0 until MAX_TERM) {
                            pass.samplesA[k] = a[k2]
                            pass.samplesB[k] = b[k2]
                            k2 = (k2 + 1) and (MAX_TERM - 1)
                        }
                    }
                }
            }

            flushWord()
            val dataCount = wvBits.close()
            if (dataCount != 0) {
                out.bytes[headerAt] = (WavPackStream.ID_WV_BITSTREAM or WavPackStream.ID_LARGE).toByte()
                out.bytes[headerAt + 1] = (dataCount ushr 1).toByte()
                out.bytes[headerAt + 2] = (dataCount ushr 9).toByte()
                out.bytes[headerAt + 3] = (dataCount ushr 17).toByte()
            } else {
                out.length = headerAt
            }
            out.crc = crc

            if (snapshot != null && numTerms > REPACK_SAFE_NUM_TERMS && maxMagnitude and repackMask != 0) {
                snapshot.restore(this)
                numTerms = REPACK_SAFE_NUM_TERMS
                out.length = restart
                saved.copyInto(buffer, 0, 0, values)
            } else {
                if (snapshot != null && numTerms != snapshot.numTerms) {
                    for (t in numTerms until snapshot.numTerms) {
                        passes[t].weightA = 0
                        passes[t].weightB = 0
                        passes[t].samplesA.fill(0)
                        passes[t].samplesB.fill(0)
                    }
                    numTerms = snapshot.numTerms
                }
                break
            }
        }
    }

    /** What a repack restores: `saved_stream` in the reference. */
    private class Snapshot(packer: WavPackPacker) {
        val numTerms = packer.numTerms
        private val passes = Array(MAX_NTERMS) { t -> WavPackDecorrPass().also { copy(packer.passes[t], into = it) } }
        private val median = Array(2) { packer.median[it].copyOf() }
        private val holdingOne = packer.holdingOne
        private val holdingZero = packer.holdingZero
        private val zerosAcc = packer.zerosAcc
        private val pendData = packer.pendData
        private val pendCount = packer.pendCount

        fun restore(packer: WavPackPacker) {
            packer.numTerms = numTerms
            for (t in 0 until MAX_NTERMS) copy(passes[t], into = packer.passes[t])
            for (c in 0 until 2) median[c].copyInto(packer.median[c])
            packer.holdingOne = holdingOne
            packer.holdingZero = holdingZero
            packer.zerosAcc = zerosAcc
            packer.pendData = pendData
            packer.pendCount = pendCount
        }
    }

    // The metadata every block carries, `write_decorr_terms` and its siblings.

    private fun writeDecorrTerms(out: WavPackBlock) {
        val data = ByteArray(numTerms) { ((passes[it].term + 5) and 0x1F or ((passes[it].delta shl 5) and 0xE0)).toByte() }
        out.putMetadata(WavPackStream.ID_DECORR_TERMS, data)
    }

    /** Stores the weights at eight bits, and continues from what was stored. */
    private fun writeDecorrWeights(out: WavPackBlock) {
        var last = numTerms - 1
        while (last >= 0) {
            if (storeWeight(passes[last].weightA) != 0 || (!mono && storeWeight(passes[last].weightB) != 0)) break
            last--
        }
        val count = last + 1
        val data = ByteArray(count * 2)
        var at = 0
        for (t in 0 until numTerms) {
            val pass = passes[t]
            if (t < count) {
                val a = storeWeight(pass.weightA)
                data[at++] = a.toByte()
                pass.weightA = restoreWeight(a)
                if (!mono) {
                    val b = storeWeight(pass.weightB)
                    data[at++] = b.toByte()
                    pass.weightB = restoreWeight(b)
                }
            } else {
                pass.weightA = 0
                pass.weightB = 0
            }
        }
        out.putMetadata(WavPackStream.ID_DECORR_WEIGHTS, data, at)
    }

    /**
     * Stores the first term's history as logarithms, and continues from what
     * was stored; the other terms start the block with none.
     */
    private fun writeDecorrSamples(out: WavPackBlock) {
        val data = ByteArray(64)
        var at = 0
        fun put(log: Int) {
            data[at++] = log.toByte()
            data[at++] = (log shr 8).toByte()
        }
        fun stored(value: Int): Int {
            val log = log2s(value)
            put(log)
            return exp2s(log)
        }
        for (t in 0 until numTerms) {
            val pass = passes[t]
            if (t == 0) {
                if (pass.term > MAX_TERM) {
                    pass.samplesA[0] = stored(pass.samplesA[0])
                    pass.samplesA[1] = stored(pass.samplesA[1])
                    if (!mono) {
                        pass.samplesB[0] = stored(pass.samplesB[0])
                        pass.samplesB[1] = stored(pass.samplesB[1])
                    }
                } else if (pass.term < 0) {
                    pass.samplesA[0] = stored(pass.samplesA[0])
                    pass.samplesB[0] = stored(pass.samplesB[0])
                } else {
                    for (k in 0 until pass.term) {
                        pass.samplesA[k] = stored(pass.samplesA[k])
                        if (!mono) pass.samplesB[k] = stored(pass.samplesB[k])
                    }
                }
            } else {
                pass.samplesA.fill(0)
                pass.samplesB.fill(0)
            }
        }
        out.putMetadata(WavPackStream.ID_DECORR_SAMPLES, data, at)
    }

    /** Stores the medians as logarithms, and continues from what was stored. */
    private fun writeEntropyVars(out: WavPackBlock) {
        val channels = if (mono) 1 else 2
        val data = ByteArray(6 * channels)
        for (c in 0 until channels) {
            for (k in 0 until 3) {
                val log = log2(median[c][k])
                data[6 * c + 2 * k] = log.toByte()
                data[6 * c + 2 * k + 1] = (log ushr 8).toByte()
                median[c][k] = exp2s(log and 0xFFFF)
            }
        }
        out.putMetadata(WavPackStream.ID_ENTROPY_VARS, data)
    }

    /** `send_general_metadata`. */
    private fun sendGeneralMetadata(out: WavPackBlock) {
        val rate = config.sampleRate
        if (flags and WavPackStream.SRATE_MASK == WavPackStream.SRATE_MASK && rate != 44100) {
            val data = if (rate and 0x7F00_0000 != 0) {
                byteArrayOf(rate.toByte(), (rate ushr 8).toByte(), (rate ushr 16).toByte(), ((rate ushr 24) and 0x7F).toByte())
            } else {
                byteArrayOf(rate.toByte(), (rate ushr 8).toByte(), (rate ushr 16).toByte())
            }
            out.putMetadata(WavPackStream.ID_SAMPLE_RATE, data)
        }
        val initial = flags and WavPackStream.INITIAL_BLOCK != 0
        if (initial && (config.channels > 2 || config.channelMask != 0x5 - config.channels)) {
            // The older form, which every decoder reads, holds eight streams.
            val data = ArrayList<Byte>()
            data += config.channels.toByte()
            var mask = config.channelMask
            while (mask != 0) {
                data += mask.toByte()
                mask = mask ushr 8
            }
            out.putMetadata(WavPackStream.ID_CHANNEL_INFO, data.toByteArray())
        }
        if (initial && sampleIndex == 0L) {
            val f = config.configFlags
            out.putMetadata(WavPackStream.ID_CONFIG_BLOCK, byteArrayOf((f ushr 8).toByte(), (f ushr 16).toByte(), (f ushr 24).toByte()))
        }
        // Empty, which marks a file from version 5 with nothing to add.
        if (initial) out.putMetadata(WavPackStream.ID_NEW_CONFIG_BLOCK, ByteArray(0))
    }

    // Decorrelation, `decorr_mono_buffer` and `decorr_stereo_pass` of `pack.c`.

    private fun decorrMonoBuffer(buffer: IntArray, count: Int): Int {
        var maxMagnitude = 0
        for (i in 0 until count) {
            var code = buffer[i]
            for (t in 0 until numTerms) {
                val pass = passes[t]
                val a = pass.samplesA
                val sam: Int
                if (pass.term > MAX_TERM) {
                    sam = if (pass.term and 1 != 0) 2 * a[0] - a[1] else (3 * a[0] - a[1]) shr 1
                    a[1] = a[0]
                    a[0] = code
                } else {
                    sam = a[i and (MAX_TERM - 1)]
                    a[(i + pass.term) and (MAX_TERM - 1)] = code
                }
                code -= applyWeight(pass.weightA, sam)
                pass.weightA = updateWeight(pass.weightA, pass.delta, sam, code)
            }
            buffer[i] = code
            maxMagnitude = maxMagnitude or (if (code < 0) code.inv() else code)
        }
        return maxMagnitude
    }

    private fun decorrStereoPass(pass: WavPackDecorrPass, buffer: IntArray, count: Int) {
        val a = pass.samplesA
        val b = pass.samplesB
        val delta = pass.delta
        when (pass.term) {
            17 -> for (i in 0 until count) {
                var sam = 2 * a[0] - a[1]
                a[1] = a[0]
                a[0] = buffer[2 * i]
                var tmp = a[0] - applyWeight(pass.weightA, sam)
                buffer[2 * i] = tmp
                pass.weightA = updateWeight(pass.weightA, delta, sam, tmp)
                sam = 2 * b[0] - b[1]
                b[1] = b[0]
                b[0] = buffer[2 * i + 1]
                tmp = b[0] - applyWeight(pass.weightB, sam)
                buffer[2 * i + 1] = tmp
                pass.weightB = updateWeight(pass.weightB, delta, sam, tmp)
            }
            18 -> for (i in 0 until count) {
                var sam = a[0] + ((a[0] - a[1]) shr 1)
                a[1] = a[0]
                a[0] = buffer[2 * i]
                var tmp = a[0] - applyWeight(pass.weightA, sam)
                buffer[2 * i] = tmp
                pass.weightA = updateWeight(pass.weightA, delta, sam, tmp)
                sam = b[0] + ((b[0] - b[1]) shr 1)
                b[1] = b[0]
                b[0] = buffer[2 * i + 1]
                tmp = b[0] - applyWeight(pass.weightB, sam)
                buffer[2 * i + 1] = tmp
                pass.weightB = updateWeight(pass.weightB, delta, sam, tmp)
            }
            -1 -> for (i in 0 until count) {
                val samA = a[0]
                val samB = buffer[2 * i]
                var tmp = samB - applyWeight(pass.weightA, samA)
                buffer[2 * i] = tmp
                pass.weightA = updateWeightClip(pass.weightA, delta, samA, tmp)
                a[0] = buffer[2 * i + 1]
                tmp = a[0] - applyWeight(pass.weightB, samB)
                buffer[2 * i + 1] = tmp
                pass.weightB = updateWeightClip(pass.weightB, delta, samB, tmp)
            }
            -2 -> for (i in 0 until count) {
                val samB = b[0]
                val samA = buffer[2 * i + 1]
                var tmp = samA - applyWeight(pass.weightB, samB)
                buffer[2 * i + 1] = tmp
                pass.weightB = updateWeightClip(pass.weightB, delta, samB, tmp)
                b[0] = buffer[2 * i]
                tmp = b[0] - applyWeight(pass.weightA, samA)
                buffer[2 * i] = tmp
                pass.weightA = updateWeightClip(pass.weightA, delta, samA, tmp)
            }
            -3 -> for (i in 0 until count) {
                val samA = a[0]
                val samB = b[0]
                a[0] = buffer[2 * i + 1]
                var tmp = a[0] - applyWeight(pass.weightB, samB)
                buffer[2 * i + 1] = tmp
                pass.weightB = updateWeightClip(pass.weightB, delta, samB, tmp)
                b[0] = buffer[2 * i]
                tmp = b[0] - applyWeight(pass.weightA, samA)
                buffer[2 * i] = tmp
                pass.weightA = updateWeightClip(pass.weightA, delta, samA, tmp)
            }
            else -> {
                var m = 0
                var k = pass.term and (MAX_TERM - 1)
                for (i in 0 until count) {
                    var sam = a[m]
                    a[k] = buffer[2 * i]
                    var tmp = a[k] - applyWeight(pass.weightA, sam)
                    buffer[2 * i] = tmp
                    pass.weightA = updateWeight(pass.weightA, delta, sam, tmp)
                    sam = b[m]
                    b[k] = buffer[2 * i + 1]
                    tmp = b[k] - applyWeight(pass.weightB, sam)
                    buffer[2 * i + 1] = tmp
                    pass.weightB = updateWeight(pass.weightB, delta, sam, tmp)
                    m = (m + 1) and (MAX_TERM - 1)
                    k = (k + 1) and (MAX_TERM - 1)
                }
            }
        }
    }

    // Choosing the filter's starting point, the default search of `extra1.c` and `extra2.c`.

    private fun workspace(size: Int): Array<IntArray> {
        if (scratch[0].size < size) scratch = Array(3) { IntArray(size) }
        return scratch
    }

    /**
     * Starts the filter on this block's samples: the mode's filter, with
     * weights and history found by running each term backwards over the
     * start of the block and then forwards, and the entropy medians found
     * from the result. `execute_mono` with no history and one pass.
     */
    private fun executeMono(samples: IntArray, count: Int) {
        if (allZero(samples, count)) {
            for (pass in passes) clearPass(pass)
            numTerms = 0
            initWords()
            return
        }
        val logLimit = minOf((((flags and WavPackStream.MAG_MASK) ushr WavPackStream.MAG_LSB) + 4) * 256, LOG_LIMIT)
        val (first, second, _) = workspace(count)
        val temp = arrayOf(first, second)
        val filter = config.filter
        var terms = filter.terms.size
        val trial = Array(MAX_NTERMS) { WavPackDecorrPass() }
        while (true) {
            samples.copyInto(temp[0], 0, 0, count)
            for (pass in trial) clearPass(pass)
            for (j in 0 until terms) {
                val pass = WavPackDecorrPass()
                pass.delta = filter.delta
                pass.term = filter.terms[j]
                if (pass.term < 0) pass.term = 1
                extraMonoPassReverse(temp[j and 1], temp[j.inv() and 1], count, pass)
                if (j != 0) pass.samplesA.fill(0) else reverseMonoDecorr(pass)
                copy(pass, into = trial[j])
                extraMonoPass(temp[j and 1], temp[j.inv() and 1], count, pass, 1)
            }
            val size = log2Buffer(temp[terms and 1], count, logLimit)
            if (size == -1 && terms != 0) terms = terms shr 1 else break
        }
        // With one filter to try there is nothing to compare it against, so
        // the reference's size comparison always takes it.
        for (t in 0 until MAX_NTERMS) copy(trial[t], into = passes[t])
        numTerms = terms
        scanWord(temp[terms and 1], count)
    }

    /** `execute_stereo` with no history and one pass. */
    private fun executeStereo(samples: IntArray, count: Int) {
        if (allZero(samples, count * 2)) {
            flags = flags and WavPackStream.JOINT_STEREO.inv()
            for (pass in passes) clearPass(pass)
            numTerms = 0
            initWords()
            return
        }
        val logLimit = minOf((((flags and WavPackStream.MAG_MASK) ushr WavPackStream.MAG_LSB) + 4) * 256, LOG_LIMIT)
        val (first, second, joint) = workspace(count * 2)
        val temp = arrayOf(first, second)
        val filter = config.filter
        if (filter.jointStereo) {
            samples.copyInto(joint, 0, 0, count * 2)
            for (i in 0 until count) {
                joint[2 * i] -= joint[2 * i + 1]
                joint[2 * i + 1] += joint[2 * i] shr 1
            }
        }
        var terms = filter.terms.size
        val trial = Array(MAX_NTERMS) { WavPackDecorrPass() }
        while (true) {
            (if (filter.jointStereo) joint else samples).copyInto(temp[0], 0, 0, count * 2)
            for (pass in trial) clearPass(pass)
            for (j in 0 until terms) {
                val pass = WavPackDecorrPass()
                pass.delta = filter.delta
                pass.term = filter.terms[j]
                if (pass.term < 0 && flags and CROSS_DECORR == 0) pass.term = -3
                extraStereoPassReverse(temp[j and 1], temp[j.inv() and 1], count, pass)
                if (j != 0) {
                    pass.samplesA.fill(0)
                    pass.samplesB.fill(0)
                } else {
                    reverseStereoDecorr(pass)
                }
                copy(pass, into = trial[j])
                extraStereoPass(temp[j and 1], temp[j.inv() and 1], count, pass, 1)
            }
            val size = log2Buffer(temp[terms and 1], count * 2, logLimit)
            if (size == -1 && terms != 0) terms = terms shr 1 else break
        }
        for (t in 0 until MAX_NTERMS) copy(trial[t], into = passes[t])
        numTerms = terms
        flags = if (filter.jointStereo) flags or WavPackStream.JOINT_STEREO else flags and WavPackStream.JOINT_STEREO.inv()
        scanWord(temp[terms and 1], count)
    }

    /** `decorr_mono_pass` of `extra1.c`, forwards or backwards from the end. */
    private fun extraMonoPass(input: IntArray, output: IntArray, count: Int, pass: WavPackDecorrPass, dir: Int) {
        val a = pass.samplesA
        val step = if (dir < 0) -1 else 1
        var at = if (dir < 0) count - 1 else 0
        var m = 0
        pass.weightA = restoreWeight(storeWeight(pass.weightA))
        for (i in 0 until MAX_TERM) a[i] = exp2s(log2s(a[i]))
        if (pass.term > MAX_TERM) {
            repeat(count) {
                val sam = if (pass.term and 1 != 0) 2 * a[0] - a[1] else (3 * a[0] - a[1]) shr 1
                a[1] = a[0]
                a[0] = input[at]
                val left = input[at] - applyWeight(pass.weightA, sam)
                pass.weightA = updateWeight(pass.weightA, pass.delta, sam, left)
                output[at] = left
                at += step
            }
        } else if (pass.term > 0) {
            repeat(count) {
                val k = (m + pass.term) and (MAX_TERM - 1)
                val sam = a[m]
                a[k] = input[at]
                m = (m + 1) and (MAX_TERM - 1)
                val left = input[at] - applyWeight(pass.weightA, sam)
                pass.weightA = updateWeight(pass.weightA, pass.delta, sam, left)
                output[at] = left
                at += step
            }
        }
        if (m != 0 && pass.term in 1..MAX_TERM) {
            val temp = a.copyOf()
            for (k in 0 until MAX_TERM) {
                a[k] = temp[m]
                m = (m + 1) and (MAX_TERM - 1)
            }
        }
    }

    /** Runs a pass backwards over at least 2048 samples from the start of the block. */
    private fun extraMonoPassReverse(input: IntArray, output: IntArray, count: Int, pass: WavPackDecorrPass) {
        if (count < 2048) {
            repeat((2048 + count - 1) / count) { extraMonoPass(input, output, count, pass, -1) }
        } else {
            extraMonoPass(input, output, 2048, pass, -1)
        }
    }

    /** Turns the history a backwards pass ends with into a forwards one: `reverse_mono_decorr`. */
    private fun reverseMonoDecorr(pass: WavPackDecorrPass) {
        val a = pass.samplesA
        if (pass.term > MAX_TERM) {
            var sam = if (pass.term and 1 != 0) 2 * a[0] - a[1] else (3 * a[0] - a[1]) shr 1
            a[1] = a[0]
            a[0] = sam
            sam = if (pass.term and 1 != 0) 2 * a[0] - a[1] else (3 * a[0] - a[1]) shr 1
            a[1] = sam
        } else if (pass.term > 1) {
            var i = 0
            var j = pass.term - 1
            repeat(pass.term / 2) {
                i = i and (MAX_TERM - 1)
                j = j and (MAX_TERM - 1)
                val swap = a[i]
                a[i] = a[j]
                a[j] = swap
                i++
                j--
            }
        }
    }

    /** `decorr_stereo_pass` of `extra2.c`, forwards or backwards from the end. */
    private fun extraStereoPass(input: IntArray, output: IntArray, count: Int, pass: WavPackDecorrPass, dir: Int) {
        val a = pass.samplesA
        val b = pass.samplesB
        val delta = pass.delta
        val step = if (dir < 0) -2 else 2
        var at = if (dir < 0) (count - 1) * 2 else 0
        pass.weightA = restoreWeight(storeWeight(pass.weightA))
        pass.weightB = restoreWeight(storeWeight(pass.weightB))
        for (i in 0 until MAX_TERM) {
            a[i] = exp2s(log2s(a[i]))
            b[i] = exp2s(log2s(b[i]))
        }
        when (pass.term) {
            2 -> repeat(count) {
                var sam = a[0]
                a[0] = a[1]
                a[1] = input[at]
                var tmp = a[1] - applyWeight(pass.weightA, sam)
                output[at] = tmp
                pass.weightA = updateWeight(pass.weightA, delta, sam, tmp)
                sam = b[0]
                b[0] = b[1]
                b[1] = input[at + 1]
                tmp = b[1] - applyWeight(pass.weightB, sam)
                output[at + 1] = tmp
                pass.weightB = updateWeight(pass.weightB, delta, sam, tmp)
                at += step
            }
            17 -> repeat(count) {
                var sam = 2 * a[0] - a[1]
                a[1] = a[0]
                a[0] = input[at]
                var tmp = a[0] - applyWeight(pass.weightA, sam)
                output[at] = tmp
                pass.weightA = updateWeight(pass.weightA, delta, sam, tmp)
                sam = 2 * b[0] - b[1]
                b[1] = b[0]
                b[0] = input[at + 1]
                tmp = b[0] - applyWeight(pass.weightB, sam)
                output[at + 1] = tmp
                pass.weightB = updateWeight(pass.weightB, delta, sam, tmp)
                at += step
            }
            18 -> repeat(count) {
                var sam = a[0] + ((a[0] - a[1]) shr 1)
                a[1] = a[0]
                a[0] = input[at]
                var tmp = a[0] - applyWeight(pass.weightA, sam)
                output[at] = tmp
                pass.weightA = updateWeight(pass.weightA, delta, sam, tmp)
                sam = b[0] + ((b[0] - b[1]) shr 1)
                b[1] = b[0]
                b[0] = input[at + 1]
                tmp = b[0] - applyWeight(pass.weightB, sam)
                output[at + 1] = tmp
                pass.weightB = updateWeight(pass.weightB, delta, sam, tmp)
                at += step
            }
            -1 -> repeat(count) {
                val samA = a[0]
                val samB = input[at]
                var tmp = samB - applyWeight(pass.weightA, samA)
                output[at] = tmp
                pass.weightA = updateWeightClip(pass.weightA, delta, samA, tmp)
                a[0] = input[at + 1]
                tmp = a[0] - applyWeight(pass.weightB, samB)
                output[at + 1] = tmp
                pass.weightB = updateWeightClip(pass.weightB, delta, samB, tmp)
                at += step
            }
            -2 -> repeat(count) {
                val samB = b[0]
                val samA = input[at + 1]
                var tmp = samA - applyWeight(pass.weightB, samB)
                output[at + 1] = tmp
                pass.weightB = updateWeightClip(pass.weightB, delta, samB, tmp)
                b[0] = input[at]
                tmp = b[0] - applyWeight(pass.weightA, samA)
                output[at] = tmp
                pass.weightA = updateWeightClip(pass.weightA, delta, samA, tmp)
                at += step
            }
            -3 -> repeat(count) {
                val samA = a[0]
                val samB = b[0]
                a[0] = input[at + 1]
                var tmp = a[0] - applyWeight(pass.weightB, samB)
                output[at + 1] = tmp
                pass.weightB = updateWeightClip(pass.weightB, delta, samB, tmp)
                b[0] = input[at]
                tmp = b[0] - applyWeight(pass.weightA, samA)
                output[at] = tmp
                pass.weightA = updateWeightClip(pass.weightA, delta, samA, tmp)
                at += step
            }
            else -> {
                var m = 0
                var k = pass.term and (MAX_TERM - 1)
                repeat(count) {
                    var sam = a[m]
                    a[k] = input[at]
                    var tmp = a[k] - applyWeight(pass.weightA, sam)
                    output[at] = tmp
                    pass.weightA = updateWeight(pass.weightA, delta, sam, tmp)
                    sam = b[m]
                    b[k] = input[at + 1]
                    tmp = b[k] - applyWeight(pass.weightB, sam)
                    output[at + 1] = tmp
                    pass.weightB = updateWeight(pass.weightB, delta, sam, tmp)
                    at += step
                    m = (m + 1) and (MAX_TERM - 1)
                    k = (k + 1) and (MAX_TERM - 1)
                }
                if (m != 0) {
                    val tempA = a.copyOf()
                    val tempB = b.copyOf()
                    for (n in 0 until MAX_TERM) {
                        a[n] = tempA[m]
                        b[n] = tempB[m]
                        m = (m + 1) and (MAX_TERM - 1)
                    }
                }
            }
        }
    }

    private fun extraStereoPassReverse(input: IntArray, output: IntArray, count: Int, pass: WavPackDecorrPass) {
        if (count < 2048) {
            repeat((2048 + count - 1) / count) { extraStereoPass(input, output, count, pass, -1) }
        } else {
            extraStereoPass(input, output, 2048, pass, -1)
        }
    }

    /** `reverse_decorr` of `extra2.c`. */
    private fun reverseStereoDecorr(pass: WavPackDecorrPass) {
        val a = pass.samplesA
        val b = pass.samplesB
        if (pass.term > MAX_TERM) {
            val odd = pass.term and 1 != 0
            var samA = if (odd) 2 * a[0] - a[1] else (3 * a[0] - a[1]) shr 1
            var samB = if (odd) 2 * b[0] - b[1] else (3 * b[0] - b[1]) shr 1
            a[1] = a[0]
            b[1] = b[0]
            a[0] = samA
            b[0] = samB
            samA = if (odd) 2 * a[0] - a[1] else (3 * a[0] - a[1]) shr 1
            samB = if (odd) 2 * b[0] - b[1] else (3 * b[0] - b[1]) shr 1
            a[1] = samA
            b[1] = samB
        } else if (pass.term > 1) {
            var i = 0
            var j = pass.term - 1
            repeat(pass.term / 2) {
                i = i and (MAX_TERM - 1)
                j = j and (MAX_TERM - 1)
                var swap = a[i]
                a[i] = a[j]
                a[j] = swap
                swap = b[i]
                b[i] = b[j]
                b[j] = swap
                i++
                j--
            }
        }
    }

    // The entropy coder, `write_words.c`.

    private fun initWords() {
        median[0].fill(0)
        median[1].fill(0)
        holdingOne = 0
        holdingZero = 0
        zerosAcc = 0
        pendData = 0
        pendCount = 0
    }

    private fun getMed(c: Int, k: Int): Int = (median[c][k] ushr 4) + 1

    private fun incMed(c: Int, k: Int) {
        val divisor = DIVISORS[k]
        median[c][k] += Integer32.divideUnsigned(median[c][k] + divisor, divisor) * 5
    }

    private fun decMed(c: Int, k: Int) {
        val divisor = DIVISORS[k]
        median[c][k] -= Integer32.divideUnsigned(median[c][k] + divisor - 2, divisor) * 2
    }

    /** `send_words_lossless`: codes every residual exactly. */
    private fun sendWordsLossless(buffer: IntArray, sampleCount: Int, isMono: Boolean) {
        val total = if (isMono) sampleCount else sampleCount * 2
        var c = 0
        for (n in 0 until total) {
            var value = buffer[n]
            val sign = if (value < 0) 1 else 0
            if (!isMono) c = n and 1
            if (Integer32.compareUnsigned(median[0][0], 2) < 0 && holdingZero == 0 && Integer32.compareUnsigned(median[1][0], 2) < 0) {
                if (zerosAcc != 0) {
                    if (value != 0) {
                        flushWord()
                    } else {
                        zerosAcc++
                        continue
                    }
                } else if (value != 0) {
                    wvBits.bit(0)
                } else {
                    median[0].fill(0)
                    median[1].fill(0)
                    zerosAcc = 1
                    continue
                }
            }
            if (sign != 0) value = value.inv()

            var onesCount: Int
            var low: Int
            val high: Int
            if (value < getMed(c, 0)) {
                onesCount = 0
                low = 0
                high = getMed(c, 0) - 1
                decMed(c, 0)
            } else {
                low = getMed(c, 0)
                incMed(c, 0)
                if (Integer32.compareUnsigned(value - low, getMed(c, 1)) < 0) {
                    onesCount = 1
                    high = low + getMed(c, 1) - 1
                    decMed(c, 1)
                } else {
                    low += getMed(c, 1)
                    incMed(c, 1)
                    if (Integer32.compareUnsigned(value - low, getMed(c, 2)) < 0) {
                        onesCount = 2
                        high = low + getMed(c, 2) - 1
                        decMed(c, 2)
                    } else {
                        onesCount = 2 + Integer32.divideUnsigned(value - low, getMed(c, 2))
                        low += (onesCount - 2) * getMed(c, 2)
                        high = low + getMed(c, 2) - 1
                        incMed(c, 2)
                    }
                }
            }

            if (holdingZero != 0) {
                if (onesCount != 0) holdingOne++
                flushWord()
                if (onesCount != 0) {
                    holdingZero = 1
                    onesCount--
                } else {
                    holdingZero = 0
                }
            } else {
                holdingZero = 1
            }
            holdingOne = onesCount * 2

            if (high != low) {
                val code = (value - low).toLong() and 0xFFFF_FFFFL
                val span = (high - low).toLong() and 0xFFFF_FFFFL
                val bitCount = 64 - span.countLeadingZeroBits()
                val extras = (1L shl bitCount) - span - 1
                if (code < extras) {
                    pendData = pendData or (code shl pendCount)
                    pendCount += bitCount - 1
                } else {
                    pendData = pendData or (((code + extras) ushr 1) shl pendCount)
                    pendCount += bitCount - 1
                    pendData = pendData or (((code + extras) and 1L) shl pendCount++)
                }
            }
            pendData = pendData or (sign.toLong() shl pendCount++)
            if (holdingZero == 0) flushWord()
        }
    }

    /** `flush_word`: sends what the coder has been holding back. */
    private fun flushWord() {
        if (zerosAcc != 0) {
            var cbits = 32 - zerosAcc.countLeadingZeroBits()
            while (cbits-- > 0) wvBits.bit(1)
            wvBits.bit(0)
            while (zerosAcc > 1) {
                wvBits.bit(zerosAcc and 1)
                zerosAcc = zerosAcc shr 1
            }
            zerosAcc = 0
        }
        if (holdingOne != 0) {
            if (holdingOne >= LIMIT_ONES) {
                wvBits.bits((1L shl LIMIT_ONES) - 1, LIMIT_ONES + 1)
                holdingOne -= LIMIT_ONES
                var cbits = 32 - holdingOne.countLeadingZeroBits()
                while (cbits-- > 0) wvBits.bit(1)
                wvBits.bit(0)
                while (holdingOne > 1) {
                    wvBits.bit(holdingOne and 1)
                    holdingOne = holdingOne shr 1
                }
                holdingZero = 0
            } else {
                wvBits.bits((1L shl holdingOne) - 1, holdingOne)
            }
            holdingOne = 0
        }
        if (holdingZero != 0) {
            wvBits.bit(0)
            holdingZero = 0
        }
        if (pendCount != 0) {
            wvBits.bits(pendData, pendCount)
            pendData = 0
            pendCount = 0
        }
    }

    /**
     * Sets the medians from [samples], read backwards and repeated to at
     * least 2048 samples: `scan_word` and `scan_word_pass`.
     */
    private fun scanWord(samples: IntArray, count: Int) {
        initWords()
        val stereo = !mono
        repeat((2048 + count - 1) / count) {
            var at = if (stereo) (count - 1) * 2 else count - 1
            val step = if (stereo) 2 else 1
            repeat(count) {
                scanValue(0, kotlin.math.abs(samples[at].toLong()).toInt())
                if (stereo) scanValue(1, kotlin.math.abs(samples[at + 1].toLong()).toInt())
                at -= step
            }
        }
    }

    private fun scanValue(c: Int, value: Int) {
        if (Integer32.compareUnsigned(value, getMed(c, 0)) < 0) {
            decMed(c, 0)
        } else {
            var low = getMed(c, 0)
            incMed(c, 0)
            if (Integer32.compareUnsigned(value - low, getMed(c, 1)) < 0) {
                decMed(c, 1)
            } else {
                low += getMed(c, 1)
                incMed(c, 1)
                if (Integer32.compareUnsigned(value - low, getMed(c, 2)) < 0) decMed(c, 2) else incMed(c, 2)
            }
        }
    }

    private fun clearPass(pass: WavPackDecorrPass) {
        pass.term = 0
        pass.delta = 0
        pass.weightA = 0
        pass.weightB = 0
        pass.samplesA.fill(0)
        pass.samplesB.fill(0)
    }

    companion object {
        const val MAX_NTERMS: Int = 16
        private const val MAX_TERM = WavPackDecorrPass.MAX_TERM
        const val CROSS_DECORR: Int = 0x20
        private const val REPACK_SAFE_NUM_TERMS = 5
        private const val LOG_LIMIT = 6912
        private const val LIMIT_ONES = 16
        private val DIVISORS = intArrayOf(128, 64, 32)

        private fun copy(from: WavPackDecorrPass, into: WavPackDecorrPass) {
            into.term = from.term
            into.delta = from.delta
            into.weightA = from.weightA
            into.weightB = from.weightB
            from.samplesA.copyInto(into.samplesA)
            from.samplesB.copyInto(into.samplesB)
        }

        /** `store_weight`: a weight of up to plus or minus 1024 in eight bits. */
        fun storeWeight(weight: Int): Int {
            var w = weight.coerceIn(-1024, 1024)
            if (w > 0) w -= (w + 64) shr 7
            return (w + 4) shr 3
        }

        /** `wp_log2s`, of a signed value. */
        fun log2s(value: Int): Int = if (value < 0) -log2(-value) else log2(value)

        /**
         * `log2buffer`: the summed logarithms of [count] values, or -1 when
         * one of at least 256 reaches [limit], which zero turns off.
         */
        fun log2Buffer(samples: IntArray, count: Int, limit: Int): Int {
            var result = 0
            for (i in 0 until count) {
                val absolute = kotlin.math.abs(samples[i])
                val log = log2(absolute)
                result += log
                if (limit != 0 && Integer32.compareUnsigned(absolute + (absolute ushr 9), 256) >= 0 && log >= limit) return -1
            }
            return result
        }

        private fun allZero(values: IntArray, count: Int): Boolean {
            for (i in 0 until count) if (values[i] != 0) return false
            return true
        }

        /** `scan_max_magnitude`. */
        fun scanMaxMagnitude(values: IntArray, count: Int): Int {
            var magnitude = 0
            for (i in 0 until count) magnitude = magnitude or (if (values[i] < 0) values[i].inv() else values[i])
            return magnitude
        }
    }
}

package io.github.yuroyami.audiokodec.internal.aac

import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.bits.BitReaderMsb
import io.github.yuroyami.audiokodec.internal.container.AacConfig
import kotlin.math.pow

/**
 * Decodes AAC-LC raw data blocks, each into 1024 frames.
 *
 * Written from ISO/IEC 14496-3:2009, subpart 4: the block syntax of 4.4.2 and
 * the decoding of 4.6, in floating point, as the standard describes it. The
 * tools are applied in the standard's order: noiseless decoding and inverse
 * quantisation, noise substitution, mid/side, intensity stereo, temporal
 * noise shaping, and the filter bank.
 *
 * Channels are numbered in the order the elements carry them, and written
 * out in AudioKodec's order. Coupling channel elements, which no common
 * encoder writes, are refused by name, as is spectral band replication.
 */
internal class AacDecoder(private val config: AacStreamConfig) {

    val channels: Int = config.channels
    private val tableIndex = config.tableIndex

    /** The output slot of each channel, numbered as the elements carry them. */
    private val slotOf: IntArray = run {
        val stored = config.storedOrder
        if (stored == null) {
            IntArray(channels) { it }
        } else {
            val canonical = stored.sortedBy { it.ordinal }
            IntArray(channels) { canonical.indexOf(stored[it]) }
        }
    }

    private val streams = Array(2) { AacChannelStream(tableIndex) }
    private val filterBanks = Array(channels) { AacFilterBank() }
    private val scratch = AacFilterBank.Scratch()
    private val noise = AacNoise()
    private val msUsed = BooleanArray(8 * AacChannelStream.MAX_BANDS)

    fun reset() {
        for (bank in filterBanks) bank.reset()
        noise.reset()
    }

    /**
     * Decodes one `raw_data_block()` from [bits], which ends byte aligned,
     * into 1024 interleaved frames of [out] from frame [frame].
     */
    fun decodeBlock(bits: BitReaderMsb, out: FloatArray, frame: Int) {
        try {
            decodeElements(bits, out, frame * channels)
        } catch (_: TruncatedStreamException) {
            throw CorruptStreamException("an AAC block ends before its data does")
        }
    }

    private fun decodeElements(bits: BitReaderMsb, out: FloatArray, base: Int) {
        var channel = 0
        while (true) {
            when (bits.readBits(3)) {
                ID_SCE, ID_LFE -> {
                    bits.skipBits(4) // element_instance_tag
                    requireRoom(channel, 1)
                    val stream = streams[0]
                    stream.read(bits, commonWindow = false, intensityAllowed = false)
                    finishSingle(stream)
                    output(stream, channel, out, base)
                    channel++
                }
                ID_CPE -> {
                    bits.skipBits(4)
                    requireRoom(channel, 2)
                    decodePair(bits)
                    output(streams[0], channel, out, base)
                    output(streams[1], channel + 1, out, base)
                    channel += 2
                }
                ID_CCE -> throw UnsupportedFeatureException(
                    "AAC coupling channel elements",
                    "this AAC stream mixes a coupling channel into its other channels, which AudioKodec does not decode",
                )
                ID_DSE -> {
                    bits.skipBits(4)
                    val align = bits.readBit() == 1
                    var count = bits.readBits(8)
                    if (count == 255) count += bits.readBits(8)
                    if (align) bits.alignToByte()
                    bits.skipBits(8 * count)
                }
                ID_PCE -> AacConfig.readProgramConfig(bits)
                ID_FIL -> {
                    var count = bits.readBits(4)
                    if (count == 15) count += bits.readBits(8) - 1
                    if (count > 0) {
                        val type = bits.peekBits(4)
                        if (type == EXT_SBR_DATA || type == EXT_SBR_DATA_CRC) {
                            throw UnsupportedFeatureException(
                                "HE-AAC (AAC with spectral band replication)",
                                "this AAC stream carries spectral band replication data, which AudioKodec does not decode. " +
                                    "Decoding only its AAC-LC core would give muffled audio at half the sample rate.",
                            )
                        }
                        bits.skipBits(8 * count)
                    }
                }
                else -> break // ID_END
            }
        }
        bits.alignToByte()
        if (channel != channels) {
            throw CorruptStreamException("an AAC block carries $channel channels where the stream has $channels")
        }
    }

    private fun requireRoom(channel: Int, count: Int) {
        if (channel + count > channels) {
            throw CorruptStreamException("an AAC block carries more than the stream's $channels channels")
        }
    }

    private fun finishSingle(stream: AacChannelStream) {
        val info = stream.info
        var firstWindow = 0
        for (g in 0 until info.groupCount) {
            for (band in 0 until info.maxSfb) {
                if (stream.isNoise(g, band)) stream.substituteNoise(g, band, firstWindow, noise, null)
            }
            firstWindow += info.groupLength[g]
        }
        stream.applyTns(tableIndex)
    }

    /** `channel_pair_element()` and the joint stereo tools, 4.6.8.1 and 4.6.8.2. */
    private fun decodePair(bits: BitReaderMsb) {
        val left = streams[0]
        val right = streams[1]
        val common = bits.readBit() == 1
        var maskPresent = 0
        if (common) {
            left.info.read(bits)
            right.info.copyFrom(left.info)
            maskPresent = bits.readBits(2)
            if (maskPresent == 3) throw CorruptStreamException("an AAC channel pair uses the reserved mid/side mask value")
            val info = left.info
            for (g in 0 until info.groupCount) {
                for (band in 0 until info.maxSfb) {
                    msUsed[g * AacChannelStream.MAX_BANDS + band] = when (maskPresent) {
                        1 -> bits.readBit() == 1
                        2 -> true
                        else -> false
                    }
                }
            }
        }
        left.read(bits, common, intensityAllowed = false)
        right.read(bits, common, intensityAllowed = common)

        if (common) {
            val info = left.info
            val offsets = info.swbOffset
            val stride = info.windowLength
            var firstWindow = 0
            for (g in 0 until info.groupCount) {
                val windows = info.groupLength[g]
                for (band in 0 until info.maxSfb) {
                    val used = msUsed[g * AacChannelStream.MAX_BANDS + band]
                    val leftNoise = left.isNoise(g, band)
                    val rightNoise = right.isNoise(g, band)
                    // Noise in both channels of a band with the mask set is the same noise in both.
                    if (leftNoise) left.substituteNoise(g, band, firstWindow, noise, null)
                    if (rightNoise) right.substituteNoise(g, band, firstWindow, noise, if (leftNoise && used) left else null)
                    val intensity = right.intensityOf(g, band)
                    if (intensity != 0) {
                        // An intensity band takes the left channel scaled by its position; a set
                        // mid/side bit inverts the phase its codebook states, when the mask is per band.
                        val invert = if (maskPresent == 1 && used) -1 else 1
                        val position = right.bandValue[g * AacChannelStream.MAX_BANDS + band]
                        val scale = (intensity * invert * 2.0.pow(0.25 * AacChannelStream.gainExponent(-position))).toFloat()
                        for (w in firstWindow until firstWindow + windows) {
                            for (k in w * stride + offsets[band] until w * stride + offsets[band + 1]) {
                                right.spectrum[k] = scale * left.spectrum[k]
                            }
                        }
                    } else if (used && !leftNoise && !rightNoise) {
                        for (w in firstWindow until firstWindow + windows) {
                            for (k in w * stride + offsets[band] until w * stride + offsets[band + 1]) {
                                val l = left.spectrum[k]
                                val r = right.spectrum[k]
                                left.spectrum[k] = l + r
                                right.spectrum[k] = l - r
                            }
                        }
                    }
                }
                firstWindow += windows
            }
            left.applyTns(tableIndex)
            right.applyTns(tableIndex)
        } else {
            finishSingle(left)
            finishSingle(right)
        }
    }

    private fun output(stream: AacChannelStream, channel: Int, out: FloatArray, base: Int) {
        filterBanks[channel].synthesise(stream.info, stream.spectrum, out, base + slotOf[channel], channels, scratch)
    }

    private companion object {
        const val ID_SCE = 0
        const val ID_CPE = 1
        const val ID_CCE = 2
        const val ID_LFE = 3
        const val ID_DSE = 4
        const val ID_PCE = 5
        const val ID_FIL = 6

        const val EXT_SBR_DATA = 13
        const val EXT_SBR_DATA_CRC = 14
    }
}

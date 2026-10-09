package io.github.yuroyami.audiokodec.internal.aac

import io.github.yuroyami.audiokodec.AudioChannel
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.bits.BitReaderMsb
import io.github.yuroyami.audiokodec.internal.container.AacConfig

/**
 * What an AAC decoder needs to know before its first block: the coding, the
 * sampling frequency and which band tables it implies, and how many channels
 * the blocks carry and in what order.
 *
 * MP4 states this in an AudioSpecificConfig, ISO/IEC 14496-3, 1.6.2.1, and
 * ADTS in every frame header.
 */
internal class AacStreamConfig(
    /** The MPEG-4 audio object type of the core coding: 2 for AAC-LC. */
    val objectType: Int,
    /** The sampling frequency the stream states. */
    val sampleRate: Int,
    /** `channelConfiguration`, 0 when a program configuration describes the channels. */
    val channelConfiguration: Int,
    /** Channels the blocks carry. */
    val channels: Int,
    /** Spectral band replication is signalled explicitly. */
    val sbr: Boolean = false,
    /** Parametric stereo is signalled explicitly. */
    val ps: Boolean = false,
    /** 960 spectral lines per block instead of 1024. */
    val shortFrames: Boolean = false,
    /** The output rate spectral band replication states, or 0 when it is not signalled. */
    val extensionRate: Int = 0,
) {
    /** The index of the band tables, by the mapping of Table 4.82. */
    val tableIndex: Int = tableIndexFor(sampleRate)

    /** The speakers in the order the elements carry them, when a fixed configuration names them. */
    val storedOrder: List<AudioChannel>? = AacConfig.storedOrder(channelConfiguration)

    /** The layout the decoder reports: the stored speakers sorted, or unnamed speakers for a program configuration. */
    val layout: ChannelLayout = AacConfig.layoutFor(channelConfiguration) ?: ChannelLayout.unknown(channels)

    /**
     * Refuses what this decoder cannot decode, naming it: everything but the
     * low complexity profile, the 960-line frame, and spectral band
     * replication, whose core alone sounds muffled and plays at half rate.
     */
    fun requireDecodable() {
        if (sbr || ps) {
            throw UnsupportedFeatureException(
                if (ps) "HE-AAC v2 (AAC with spectral band replication and parametric stereo)" else "HE-AAC (AAC with spectral band replication)",
                "this AAC stream relies on spectral band replication, which AudioKodec does not decode. " +
                    "Decoding only its AAC-LC core would give muffled audio at half the sample rate.",
            )
        }
        val name = OBJECT_TYPE_NAMES[objectType]
        if (objectType != LC) {
            throw UnsupportedFeatureException(
                name ?: "MPEG-4 audio object type $objectType",
                "this stream is coded as ${name ?: "MPEG-4 audio object type $objectType"}; AudioKodec decodes AAC-LC",
            )
        }
        if (shortFrames) {
            throw UnsupportedFeatureException("AAC with 960-sample frames", "this AAC stream uses 960-sample frames; AudioKodec decodes 1024-sample frames")
        }
    }

    companion object {
        const val MAIN: Int = 1
        const val LC: Int = 2

        private val OBJECT_TYPE_NAMES = mapOf(
            1 to "AAC Main",
            2 to "AAC-LC",
            3 to "AAC SSR",
            4 to "AAC LTP",
            5 to "HE-AAC (AAC with spectral band replication)",
            6 to "AAC scalable",
            17 to "ER AAC-LC",
            19 to "ER AAC LTP",
            20 to "ER AAC scalable",
            23 to "ER AAC LD",
            29 to "HE-AAC v2 (AAC with spectral band replication and parametric stereo)",
            39 to "ER AAC ELD",
            42 to "USAC (xHE-AAC)",
        )

        /** The tables Table 4.82 assigns a sampling frequency, as a sampling frequency index. */
        fun tableIndexFor(rate: Int): Int = when {
            rate >= 92_017 -> 0
            rate >= 75_132 -> 1
            rate >= 55_426 -> 2
            rate >= 46_009 -> 3
            rate >= 37_566 -> 4
            rate >= 27_713 -> 5
            rate >= 23_004 -> 6
            rate >= 18_783 -> 7
            rate >= 13_856 -> 8
            rate >= 11_502 -> 9
            rate >= 9_391 -> 10
            else -> 11
        }

        /**
         * Reads an AudioSpecificConfig, with the GASpecificConfig and the
         * backward compatible extension signalling that follow it. [offset] is
         * where it sits in the file, for failures.
         */
        fun parse(config: ByteArray, offset: Long): AacStreamConfig {
            val bits = BitReaderMsb(config)
            try {
                fun objectType(): Int = bits.readBits(5).let { if (it == 31) 32 + bits.readBits(6) else it }
                fun frequency(): Int {
                    val index = bits.readBits(4)
                    if (index == 15) return bits.readBits(24)
                    return AacConfig.SAMPLE_RATES.getOrNull(index)
                        ?: throw CorruptStreamException("the AAC configuration uses reserved sampling frequency index $index", offset)
                }
                var type = objectType()
                val rate = frequency()
                val channelConfiguration = bits.readBits(4)
                var sbr = false
                var ps = false
                var extensionRate = 0
                if (type == 5 || type == 29) {
                    sbr = true
                    ps = type == 29
                    extensionRate = frequency()
                    type = objectType()
                }
                if (rate <= 0) throw CorruptStreamException("the AAC configuration states a sampling frequency of $rate Hz", offset)
                var shortFrames = false
                var channels = AAC_CHANNELS.getOrElse(channelConfiguration) {
                    throw CorruptStreamException("the AAC configuration uses reserved channel configuration $channelConfiguration", offset)
                }
                if (type in GENERAL_AUDIO) {
                    shortFrames = bits.readBit() == 1
                    if (bits.readBit() == 1) bits.skipBits(14) // coreCoderDelay
                    val extension = bits.readBit() == 1
                    if (channelConfiguration == 0) {
                        val program = AacConfig.readProgramConfig(bits)
                        channels = program.channels
                        if (channels == 0) throw CorruptStreamException("the AAC configuration's program configuration names no channels", offset)
                    }
                    if (type == 6 || type == 20) bits.skipBits(3) // layerNr
                    if (extension) {
                        if (type == 22) bits.skipBits(16)
                        if (type == 17 || type == 19 || type == 20 || type == 23) bits.skipBits(3)
                        bits.skipBits(1) // extensionFlag3
                    }
                    if (type in ERROR_RESILIENT) {
                        if (bits.readBits(2) >= 2) {
                            // ErrorProtectionSpecificConfig: the coding is refused before it would matter.
                            return AacStreamConfig(type, rate, channelConfiguration, channels, sbr, ps, shortFrames, extensionRate)
                        }
                    }
                    // Backward compatible signalling: a sync extension after the core configuration.
                    if (!sbr && bits.bitsRemaining >= 16 && bits.readBits(11) == 0x2B7) {
                        val extensionType = objectType()
                        if (extensionType == 5 && bits.readBit() == 1) {
                            sbr = true
                            extensionRate = frequency()
                            if (bits.bitsRemaining >= 12 && bits.readBits(11) == 0x548) ps = bits.readBit() == 1
                        }
                    }
                }
                if (channels == 0) throw CorruptStreamException("the AAC configuration names no channels", offset)
                return AacStreamConfig(type, rate, channelConfiguration, channels, sbr, ps, shortFrames, extensionRate)
            } catch (_: TruncatedStreamException) {
                throw CorruptStreamException("the AAC configuration is cut short", offset)
            }
        }

        /** Channels carried by `channelConfiguration` 0 to 7; 0 defers to a program configuration. */
        private val AAC_CHANNELS = intArrayOf(0, 1, 2, 3, 4, 5, 6, 8)

        /** Object types whose configuration is a GASpecificConfig. */
        private val GENERAL_AUDIO = setOf(1, 2, 3, 4, 6, 7, 17, 19, 20, 21, 22, 23)

        private val ERROR_RESILIENT = setOf(17, 19, 20, 21, 22, 23)
    }
}

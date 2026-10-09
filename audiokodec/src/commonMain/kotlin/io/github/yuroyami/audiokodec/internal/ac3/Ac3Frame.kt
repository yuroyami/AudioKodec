package io.github.yuroyami.audiokodec.internal.ac3

import io.github.yuroyami.audiokodec.AudioChannel
import io.github.yuroyami.audiokodec.ChannelLayout

/**
 * The synchronisation information and the start of the bit stream
 * information of one AC-3 syncframe, ATSC A/52:2012 sections 5.3.1 and
 * 5.3.2: enough to find the next frame and to know the stream's shape.
 */
internal class Ac3Frame(
    /** `fscod`: 0 for 48 kHz, 1 for 44.1 kHz, 2 for 32 kHz. */
    val sampleRateCode: Int,
    /** `frmsizecod`, 0 to 37. */
    val frameSizeCode: Int,
    /** `bsid`, 0 to 8 for the streams this standard's body describes. */
    val bitStreamId: Int,
    /** `acmod`, the audio coding mode of Table 5.8. */
    val codingMode: Int,
    /** `lfeon`. */
    val hasLfe: Boolean,
) {
    val sampleRate: Int get() = RATES[sampleRateCode]

    /** Bytes in the syncframe, Table 5.18. */
    val frameBytes: Int
        get() = 2 * when (sampleRateCode) {
            0 -> Ac3Tables.WORDS_48000
            1 -> Ac3Tables.WORDS_44100
            else -> Ac3Tables.WORDS_32000
        }[frameSizeCode]

    /** The nominal bit rate in bits per second. */
    val bitrate: Int get() = Ac3Tables.WORDS_48000[frameSizeCode] * 500

    /** `nfchans`: the full bandwidth channels. */
    val fullBandwidthChannels: Int get() = FULL_BANDWIDTH[codingMode]

    val channels: Int get() = fullBandwidthChannels + if (hasLfe) 1 else 0

    /** The speakers in the order the bit stream carries them, Table 5.8, with the LFE last. */
    val storedOrder: List<AudioChannel>
        get() {
            val l = AudioChannel.FRONT_LEFT
            val r = AudioChannel.FRONT_RIGHT
            val c = AudioChannel.FRONT_CENTER
            val front = when (codingMode) {
                // 1+1 carries two independent programmes, which play as a pair.
                0 -> listOf(l, r)
                1 -> listOf(c)
                2 -> listOf(l, r)
                3 -> listOf(l, c, r)
                4 -> listOf(l, r, AudioChannel.BACK_CENTER)
                5 -> listOf(l, c, r, AudioChannel.BACK_CENTER)
                6 -> listOf(l, r, AudioChannel.SIDE_LEFT, AudioChannel.SIDE_RIGHT)
                else -> listOf(l, c, r, AudioChannel.SIDE_LEFT, AudioChannel.SIDE_RIGHT)
            }
            return if (hasLfe) front + AudioChannel.LOW_FREQUENCY else front
        }

    /** The same speakers in AudioKodec's order. */
    val layout: ChannelLayout get() = ChannelLayout.of(storedOrder.sortedBy { it.ordinal })

    /** True when [other] has the same shape, as every frame of one stream must. */
    fun matches(other: Ac3Frame): Boolean =
        sampleRateCode == other.sampleRateCode && codingMode == other.codingMode && hasLfe == other.hasLfe

    companion object {
        /** Samples per channel in every syncframe: six blocks of 256. */
        const val FRAMES_PER_SYNCFRAME: Int = 1536

        /** Enough bytes to read a frame's shape: the syncinfo, and the bit stream information up to `lfeon`. */
        const val HEADER_BYTES: Int = 8

        const val SYNC_WORD: Int = 0x0B77

        private val RATES = intArrayOf(48_000, 44_100, 32_000)
        private val FULL_BANDWIDTH = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5)

        /** The `bsid` of the frame at [at], or -1 when there is no sync word there. */
        fun bitStreamIdAt(bytes: ByteArray, at: Int): Int {
            if (bytes.size - at < 6) return -1
            if (bytes[at].toInt() and 0xFF != 0x0B || bytes[at + 1].toInt() and 0xFF != 0x77) return -1
            return (bytes[at + 5].toInt() and 0xFF) ushr 3
        }

        /**
         * The frame at [at] in [bytes], or null when the bytes there are not
         * the start of an AC-3 syncframe of this standard: a sync word, a
         * sample rate and frame size that are not reserved, and a `bsid` of
         * at most 8. A larger `bsid` belongs to a later syntax, Enhanced AC-3
         * among them, which [bitStreamIdAt] lets a caller name.
         */
        fun parse(bytes: ByteArray, at: Int): Ac3Frame? {
            if (bytes.size - at < HEADER_BYTES) return null
            fun u(i: Int) = bytes[at + i].toInt() and 0xFF
            if (u(0) != 0x0B || u(1) != 0x77) return null
            val fscod = u(4) ushr 6
            val frmsizecod = u(4) and 0x3F
            if (fscod == 3 || frmsizecod >= 38) return null
            val bsid = u(5) ushr 3
            if (bsid > 8) return null
            val acmod = (u(6) ushr 5) and 0x07
            // After acmod come cmixlev, surmixlev and dsurmod as the mode has them, then lfeon.
            var bit = 3 // the position in bytes 6 and 7 just past acmod
            if (acmod and 1 != 0 && acmod != 1) bit += 2
            if (acmod and 4 != 0) bit += 2
            if (acmod == 2) bit += 2
            val word = (u(6) shl 8) or u(7)
            val lfeon = (word ushr (15 - bit)) and 1 == 1
            return Ac3Frame(fscod, frmsizecod, bsid, acmod, lfeon)
        }
    }
}

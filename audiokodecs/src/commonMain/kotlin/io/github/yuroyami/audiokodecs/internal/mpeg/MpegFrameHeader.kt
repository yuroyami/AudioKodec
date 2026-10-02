package io.github.yuroyami.audiokodecs.internal.mpeg

import io.github.yuroyami.audiokodecs.AudioCodec

/**
 * The four bytes at the front of every MPEG audio frame.
 *
 * MPEG audio has no container. A file is a run of frames, each one carrying its
 * own sample rate, bitrate and channel mode in a four-byte header, and each one
 * free to differ from the one before it. That is why a decoder for it spends as
 * much effort on finding frames as on decoding them.
 *
 * Three versions and three layers exist, and the combinations are not
 * interchangeable: the same bitrate index means different things in MPEG-1
 * Layer II and MPEG-2 Layer III, and the frame length formula differs by layer.
 *
 * Written from ISO/IEC 11172-3 (MPEG-1) and 13818-3 (MPEG-2), with the
 * unofficial MPEG-2.5 extension that only ever appeared in the wild.
 */
internal class MpegFrameHeader(
    /** 1, 2 or 25 for the half-rate extension, which is written 2.5. */
    val version: Int,
    /** 1, 2 or 3. */
    val layer: Int,
    val hasCrc: Boolean,
    val bitrateKbps: Int,
    val sampleRate: Int,
    val padded: Boolean,
    val privateBit: Boolean,
    /** 0 stereo, 1 joint stereo, 2 dual channel, 3 single channel. */
    val channelMode: Int,
    /** For joint stereo, what the mode extension bits say. */
    val modeExtension: Int,
    val copyrighted: Boolean,
    val original: Boolean,
    val emphasis: Int,
) {

    val channels: Int get() = if (channelMode == MODE_SINGLE) 1 else 2

    /** Samples one frame decodes to, which depends on the version and layer. */
    val samplesPerFrame: Int
        get() = when {
            layer == 1 -> 384
            layer == 2 -> 1152
            version == 1 -> 1152
            // MPEG-2 and 2.5 halved Layer III's granules to keep the frame
            // short at their lower sample rates.
            else -> 576
        }

    /** Bytes this frame occupies, header included. */
    val frameBytes: Int
        get() {
            val padding = if (padded) 1 else 0
            return if (layer == 1) {
                (12 * bitrateKbps * 1000 / sampleRate + padding) * 4
            } else {
                samplesPerFrame / 8 * bitrateKbps * 1000 / sampleRate + padding
            }
        }

    /** Bytes of side information Layer III puts after the header. */
    val sideInfoBytes: Int
        get() = when {
            layer != 3 -> 0
            version == 1 -> if (channels == 1) 17 else 32
            else -> if (channels == 1) 9 else 17
        }

    val codec: AudioCodec
        get() = when (layer) {
            1 -> AudioCodec.MP1
            2 -> AudioCodec.MP2
            else -> AudioCodec.MP3
        }

    /** True when [other] could be the same stream continuing. */
    fun matches(other: MpegFrameHeader): Boolean =
        version == other.version &&
            layer == other.layer &&
            sampleRate == other.sampleRate &&
            channels == other.channels

    /** A short description, for an error message. */
    val description: String
        get() {
            val name = when (version) {
                1 -> "MPEG-1"
                2 -> "MPEG-2"
                else -> "MPEG-2.5"
            }
            return "$name Layer ${"I".repeat(layer)} at $sampleRate Hz, $bitrateKbps kbps"
        }

    companion object {

        const val MODE_STEREO = 0
        const val MODE_JOINT = 1
        const val MODE_DUAL = 2
        const val MODE_SINGLE = 3

        /**
         * Reads a header from four bytes, or returns null when they are not one.
         *
         * Every reserved combination is rejected rather than guessed at, which
         * is what makes this usable as a test while scanning for a frame: eleven
         * sync bits alone turn up in ordinary audio data about once every two
         * kilobytes, and the rest of the header is what tells a real frame from
         * a coincidence.
         */
        fun parse(bytes: ByteArray, at: Int): MpegFrameHeader? {
            if (at + 4 > bytes.size) return null
            val b0 = bytes[at].toInt() and 0xFF
            val b1 = bytes[at + 1].toInt() and 0xFF
            val b2 = bytes[at + 2].toInt() and 0xFF
            val b3 = bytes[at + 3].toInt() and 0xFF

            if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null

            val versionBits = (b1 ushr 3) and 0x3
            val layerBits = (b1 ushr 1) and 0x3
            if (versionBits == 1 || layerBits == 0) return null

            val version = when (versionBits) {
                0 -> 25
                2 -> 2
                else -> 1
            }
            val layer = 4 - layerBits

            val bitrateIndex = (b2 ushr 4) and 0xF
            val sampleRateIndex = (b2 ushr 2) and 0x3
            if (sampleRateIndex == 3) return null
            if (bitrateIndex == 15) return null

            // Index 0 is the free format: the frame states no length, and a
            // decoder has to measure the distance to the next sync instead.
            // It is not a frame this can build, so the scanner is told about it
            // separately rather than being handed a header with no length.
            val bitrate = bitrateOf(version, layer, bitrateIndex)
            if (bitrate <= 0) return null

            val sampleRate = SAMPLE_RATES[sampleRateIndex] / when (version) {
                1 -> 1
                2 -> 2
                else -> 4
            }

            return MpegFrameHeader(
                version = version,
                layer = layer,
                hasCrc = (b1 and 1) == 0,
                bitrateKbps = bitrate,
                sampleRate = sampleRate,
                padded = (b2 and 0x02) != 0,
                privateBit = (b2 and 0x01) != 0,
                channelMode = (b3 ushr 6) and 0x3,
                modeExtension = (b3 ushr 4) and 0x3,
                copyrighted = (b3 and 0x08) != 0,
                original = (b3 and 0x04) != 0,
                emphasis = b3 and 0x3,
            )
        }

        /**
         * True when these four bytes are a frame header using the free format.
         *
         * Free format frames carry no length, so the distance to the next sync
         * word is the only thing that gives it. AudioKodecs does not decode them,
         * and this exists so that a file made of them is named for what it is
         * rather than reported as having no frames at all.
         */
        fun isFreeFormat(bytes: ByteArray, at: Int): Boolean {
            if (at + 4 > bytes.size) return false
            val b0 = bytes[at].toInt() and 0xFF
            val b1 = bytes[at + 1].toInt() and 0xFF
            if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return false
            if (((b1 ushr 3) and 0x3) == 1 || ((b1 ushr 1) and 0x3) == 0) return false
            val b2 = bytes[at + 2].toInt() and 0xFF
            return ((b2 ushr 4) and 0xF) == 0 && ((b2 ushr 2) and 0x3) != 3
        }

        /** True when these four bytes could begin a frame, without building one. */
        fun looksLikeSync(bytes: ByteArray, at: Int): Boolean {
            if (at + 4 > bytes.size) return false
            val b0 = bytes[at].toInt() and 0xFF
            val b1 = bytes[at + 1].toInt() and 0xFF
            if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return false
            if (((b1 ushr 3) and 0x3) == 1) return false
            if (((b1 ushr 1) and 0x3) == 0) return false
            val b2 = bytes[at + 2].toInt() and 0xFF
            val bitrateIndex = (b2 ushr 4) and 0xF
            if (bitrateIndex == 15 || bitrateIndex == 0) return false
            return ((b2 ushr 2) and 0x3) != 3
        }

        private val SAMPLE_RATES = intArrayOf(44_100, 48_000, 32_000)

        /**
         * The bitrate tables.
         *
         * There are five of them, and which one applies depends on the version
         * and the layer together. Index 0 is the free format and index 15 is
         * reserved, so both are left at zero here and handled by the caller.
         */
        private val BITRATES_V1_L1 = intArrayOf(
            0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0,
        )
        private val BITRATES_V1_L2 = intArrayOf(
            0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0,
        )
        private val BITRATES_V1_L3 = intArrayOf(
            0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0,
        )
        private val BITRATES_V2_L1 = intArrayOf(
            0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0,
        )
        private val BITRATES_V2_L23 = intArrayOf(
            0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0,
        )

        private fun bitrateOf(version: Int, layer: Int, index: Int): Int {
            val table = if (version == 1) {
                when (layer) {
                    1 -> BITRATES_V1_L1
                    2 -> BITRATES_V1_L2
                    else -> BITRATES_V1_L3
                }
            } else {
                if (layer == 1) BITRATES_V2_L1 else BITRATES_V2_L23
            }
            return table[index]
        }
    }
}

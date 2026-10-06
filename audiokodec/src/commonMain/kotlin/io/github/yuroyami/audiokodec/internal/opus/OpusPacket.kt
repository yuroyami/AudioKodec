package io.github.yuroyami.audiokodec.internal.opus

import io.github.yuroyami.audiokodec.CorruptStreamException

/**
 * How an Opus packet says what is inside it.
 *
 * Opus puts everything a decoder needs to switch behaviour into the first byte:
 * which of its two codecs to use, how wide a band to decode, how long the frame
 * is, whether it is stereo, and how many frames follow. That is why an Opus
 * stream can change from speech coding to music coding, or from narrowband to
 * fullband, between one packet and the next without any header telling it to.
 *
 * A packet may hold several frames, packed one of four ways, and the lengths
 * are coded so that the common cases cost nothing: two frames of equal size
 * state no length at all.
 *
 * Written from RFC 6716 section 3, with the checks of the reference decoder.
 */
internal class OpusPacket(
    val config: Int,
    val stereo: Boolean,
    /** Where each frame starts and ends, as offsets into the packet. */
    val frames: List<IntRange>,
    /** Padding the packet carried, which is not audio. */
    val paddingBytes: Int,
    /** Bytes the packet occupies, which a self-delimited one states itself. */
    val length: Int,
) {

    val mode: Mode get() = MODES[config]
    val bandwidth: Bandwidth get() = BANDWIDTHS[config]

    /** Samples one frame decodes to at 48 kHz. */
    val frameSize: Int get() = FRAME_SIZES[config]

    /** Samples the whole packet decodes to at 48 kHz. */
    val samples: Int get() = frameSize * frames.size

    val channels: Int get() = if (stereo) 2 else 1

    /**
     * `opus_packet_has_lbrr`: whether the first frame carries a low bitrate
     * copy of the frame before it, which a decoder that lost that frame can
     * decode instead of concealing it. Only SILK frames carry one.
     */
    fun hasLbrr(data: ByteArray): Boolean {
        if (mode == Mode.CELT) return false
        val nbFrames = if (frameSize > 960) frameSize / 960 else 1
        val first = data[frames[0].first].toInt() and 0xFF
        var lbrr = (first shr (7 - nbFrames)) and 1
        if (stereo) lbrr = lbrr or ((first shr (6 - 2 * nbFrames)) and 1)
        return lbrr != 0
    }

    enum class Mode { SILK, HYBRID, CELT }

    /** How wide a band the frame codes, named for the rate it corresponds to. */
    enum class Bandwidth(val hertz: Int) {
        NARROW(4_000),
        MEDIUM(6_000),
        WIDE(8_000),
        SUPER_WIDE(12_000),
        FULL(20_000),
    }

    companion object {

        /** The longest a packet may decode to, which is 120 ms at 48 kHz. */
        const val MAX_SAMPLES = 5_760

        /** The most bytes one frame may hold. */
        const val MAX_FRAME_BYTES = 1275

        /** The most frames a packet may hold: 48 of 2.5 ms make 120 ms. */
        private const val MAX_FRAMES = 48

        /**
         * Parses the packet of [length] bytes at [offset].
         *
         * A [selfDelimited] packet states the length of its last frame too,
         * which is how a multistream packet packs every stream but the last
         * one after another; the length it turns out to occupy is
         * [OpusPacket.length]. Follows `opus_packet_parse_impl` in libopus
         * `opus.c`, so that a packet is accepted or refused exactly as the
         * reference decoder would.
         */
        fun parse(data: ByteArray, offset: Int, length: Int, selfDelimited: Boolean = false): OpusPacket {
            fun corrupt(message: String): Nothing = throw CorruptStreamException(message, offset.toLong())
            if (length < 1) corrupt("an Opus packet must hold at least one byte")
            val toc = data[offset].toInt() and 0xFF
            val config = (toc ushr 3) and 0x1F
            val stereo = (toc and 0x4) != 0
            val code = toc and 0x3

            var at = offset + 1
            var remaining = length - 1
            var lastSize = remaining
            val sizes = IntArray(MAX_FRAMES)
            val count: Int
            var equalSizes = false
            var padding = 0

            // Reads one frame length into sizes[index] and returns the bytes
            // it took: one below 252, two from there up.
            fun readLength(index: Int): Int {
                if (remaining < 1) corrupt("an Opus frame length ran past the packet")
                val first = data[at].toInt() and 0xFF
                if (first < 252) {
                    sizes[index] = first
                    return 1
                }
                if (remaining < 2) corrupt("an Opus frame length ran past the packet")
                sizes[index] = 4 * (data[at + 1].toInt() and 0xFF) + first
                return 2
            }

            when (code) {
                0 -> count = 1

                1 -> {
                    // Two frames of the same size, which is why no length is
                    // written: what is left simply divides in two.
                    count = 2
                    equalSizes = true
                    if (!selfDelimited) {
                        if (remaining % 2 != 0) {
                            corrupt("a two frame Opus packet holds $remaining bytes, which does not divide in two")
                        }
                        lastSize = remaining / 2
                        sizes[0] = lastSize
                    }
                }

                2 -> {
                    count = 2
                    val bytes = readLength(0)
                    remaining -= bytes
                    if (sizes[0] > remaining) corrupt("an Opus frame states ${sizes[0]} bytes and the packet has fewer")
                    at += bytes
                    lastSize = remaining - sizes[0]
                }

                else -> {
                    if (remaining < 1) corrupt("an Opus packet promised a frame count and ended first")
                    val counts = data[at++].toInt() and 0xFF
                    remaining--
                    count = counts and 0x3F
                    if (count < 1) corrupt("an Opus packet states no frames at all")
                    if (count * FRAME_SIZES[config] > MAX_SAMPLES) {
                        corrupt(
                            "an Opus packet of $count frames would decode to more than the " +
                                "120 milliseconds the format allows",
                        )
                    }
                    if (counts and 0x40 != 0) {
                        // Padding states its length in as many bytes as it
                        // takes, 254 at a time, so that any amount is codable.
                        do {
                            if (remaining <= 0) corrupt("an Opus packet's padding ran past its end")
                            val step = data[at++].toInt() and 0xFF
                            remaining--
                            val bytes = if (step == 255) 254 else step
                            remaining -= bytes
                            padding += bytes
                        } while (step == 255)
                    }
                    if (remaining < 0) corrupt("an Opus packet states more padding than it holds")
                    equalSizes = counts and 0x80 == 0
                    if (!equalSizes) {
                        // Every frame but the last states its length.
                        lastSize = remaining
                        for (i in 0 until count - 1) {
                            val bytes = readLength(i)
                            remaining -= bytes
                            if (sizes[i] > remaining) corrupt("an Opus frame states ${sizes[i]} bytes and the packet has fewer")
                            at += bytes
                            lastSize -= bytes + sizes[i]
                        }
                        if (lastSize < 0) corrupt("the frames of an Opus packet state more bytes than it holds")
                    } else if (!selfDelimited) {
                        lastSize = remaining / count
                        if (lastSize * count != remaining) {
                            corrupt("an Opus packet of $count equal frames holds $remaining bytes")
                        }
                        for (i in 0 until count - 1) sizes[i] = lastSize
                    }
                }
            }

            if (selfDelimited) {
                val bytes = readLength(count - 1)
                remaining -= bytes
                val last = sizes[count - 1]
                if (last > remaining) corrupt("an Opus frame states $last bytes and the packet has fewer")
                at += bytes
                if (equalSizes) {
                    if (last * count > remaining) corrupt("an Opus packet of $count equal frames holds $remaining bytes")
                    for (i in 0 until count - 1) sizes[i] = last
                } else if (bytes + last > lastSize) {
                    corrupt("the frames of an Opus packet state more bytes than it holds")
                }
            } else {
                // Every other frame's length is coded in at most two bytes,
                // which cannot exceed 1275. The last frame's is implied by
                // what is left, so it has to be checked against the same limit.
                if (lastSize > MAX_FRAME_BYTES) {
                    corrupt("an Opus frame of $lastSize bytes exceeds the $MAX_FRAME_BYTES the format allows")
                }
                sizes[count - 1] = lastSize
            }

            val frames = ArrayList<IntRange>(count)
            for (i in 0 until count) {
                frames.add(at until at + sizes[i])
                at += sizes[i]
            }
            return OpusPacket(config, stereo, frames, padding, padding + at - offset)
        }

        /**
         * What each of the thirty-two configurations means.
         *
         * The layout is deliberate: the low twelve are the speech codec at
         * three bandwidths and four frame lengths, the next four are both
         * codecs together, and the top sixteen are the music codec at four
         * bandwidths and four frame lengths.
         */
        private val MODES: Array<Mode> = Array(32) { config ->
            when {
                config < 12 -> Mode.SILK
                config < 16 -> Mode.HYBRID
                else -> Mode.CELT
            }
        }

        private val BANDWIDTHS: Array<Bandwidth> = Array(32) { config ->
            when {
                config < 4 -> Bandwidth.NARROW
                config < 8 -> Bandwidth.MEDIUM
                config < 12 -> Bandwidth.WIDE
                config < 14 -> Bandwidth.SUPER_WIDE
                config < 16 -> Bandwidth.FULL
                config < 20 -> Bandwidth.NARROW
                config < 24 -> Bandwidth.WIDE
                config < 28 -> Bandwidth.SUPER_WIDE
                else -> Bandwidth.FULL
            }
        }

        /** Samples at 48 kHz, which is the rate Opus always counts in. */
        private val FRAME_SIZES: IntArray = IntArray(32) { config ->
            when {
                // The speech codec's four lengths are 10, 20, 40 and 60 ms.
                config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
                // Both together offer only the middle two.
                config < 16 -> intArrayOf(480, 960)[config and 1]
                // The music codec goes shorter instead, down to 2.5 ms.
                else -> intArrayOf(120, 240, 480, 960)[config and 3]
            }
        }
    }
}

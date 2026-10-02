package io.github.yuroyami.audiokodecs.internal.opus

import io.github.yuroyami.audiokodecs.CorruptStreamException

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
 * Written from RFC 6716 section 3.
 */
internal class OpusPacket(
    val config: Int,
    val stereo: Boolean,
    /** Where each frame starts and ends, as offsets into the packet. */
    val frames: List<IntRange>,
    /** Padding the packet carried, which is not audio. */
    val paddingBytes: Int,
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

        fun parse(data: ByteArray, offset: Int, length: Int): OpusPacket {
            if (length < 1) {
                throw CorruptStreamException("an Opus packet must hold at least one byte", offset.toLong())
            }
            val toc = data[offset].toInt() and 0xFF
            val config = (toc ushr 3) and 0x1F
            val stereo = (toc and 0x4) != 0
            val code = toc and 0x3

            var at = offset + 1
            val end = offset + length
            val frames = ArrayList<IntRange>(if (code == 3) 6 else 2)
            var padding = 0

            when (code) {
                0 -> frames.add(at until end)

                1 -> {
                    // Two frames of the same size, which is why no length is
                    // written: what is left simply divides in two.
                    val room = end - at
                    if (room < 0 || room % 2 != 0) {
                        throw CorruptStreamException(
                            "a two frame Opus packet holds $room bytes, which does not divide in two",
                            offset.toLong(),
                        )
                    }
                    val half = room / 2
                    frames.add(at until at + half)
                    frames.add(at + half until end)
                }

                2 -> {
                    val first = readLength(data, at, end, offset)
                    at = first.second
                    if (at + first.first > end) {
                        throw CorruptStreamException(
                            "an Opus frame states ${first.first} bytes and the packet has fewer",
                            offset.toLong(),
                        )
                    }
                    frames.add(at until at + first.first)
                    frames.add(at + first.first until end)
                }

                else -> {
                    if (at >= end) {
                        throw CorruptStreamException(
                            "an Opus packet promised a frame count and ended first",
                            offset.toLong(),
                        )
                    }
                    val counts = data[at++].toInt() and 0xFF
                    val count = counts and 0x3F
                    val variable = (counts and 0x80) != 0
                    val padded = (counts and 0x40) != 0
                    if (count < 1) {
                        throw CorruptStreamException(
                            "an Opus packet states no frames at all",
                            offset.toLong(),
                        )
                    }
                    if (count * FRAME_SIZES[config] > MAX_SAMPLES) {
                        throw CorruptStreamException(
                            "an Opus packet of $count frames would decode to more than the " +
                                "120 milliseconds the format allows",
                            offset.toLong(),
                        )
                    }

                    if (padded) {
                        // Padding states its length in as many bytes as it
                        // takes, 254 at a time, so that any amount is codable.
                        while (true) {
                            if (at >= end) {
                                throw CorruptStreamException(
                                    "an Opus packet's padding ran past its end",
                                    offset.toLong(),
                                )
                            }
                            val step = data[at++].toInt() and 0xFF
                            padding += step
                            if (step != 255) break
                            padding--
                        }
                    }

                    var body = end - padding
                    if (body < at) {
                        throw CorruptStreamException(
                            "an Opus packet states more padding than it holds",
                            offset.toLong(),
                        )
                    }

                    if (variable) {
                        // Every frame but the last states its length.
                        val lengths = IntArray(count)
                        for (i in 0 until count - 1) {
                            val read = readLength(data, at, body, offset)
                            lengths[i] = read.first
                            at = read.second
                        }
                        var cursor = at
                        for (i in 0 until count - 1) {
                            if (cursor + lengths[i] > body) {
                                throw CorruptStreamException(
                                    "an Opus frame states ${lengths[i]} bytes and the packet has fewer",
                                    offset.toLong(),
                                )
                            }
                            frames.add(cursor until cursor + lengths[i])
                            cursor += lengths[i]
                        }
                        frames.add(cursor until body)
                    } else {
                        val room = body - at
                        if (room < 0 || room % count != 0) {
                            throw CorruptStreamException(
                                "an Opus packet of $count equal frames holds $room bytes",
                                offset.toLong(),
                            )
                        }
                        val each = room / count
                        for (i in 0 until count) {
                            frames.add(at + i * each until at + (i + 1) * each)
                        }
                    }
                }
            }

            for (frame in frames) {
                if (frame.first > frame.last + 1 || frame.last >= end) {
                    throw CorruptStreamException("an Opus frame runs past its packet", offset.toLong())
                }
            }
            // Every other frame's length is coded in at most two bytes, which
            // cannot exceed 1275. The last frame's is implied by what is left,
            // so it has to be checked against the same limit.
            val last = frames.last()
            if (last.last - last.first + 1 > MAX_FRAME_BYTES) {
                throw CorruptStreamException(
                    "an Opus frame of ${last.last - last.first + 1} bytes exceeds the $MAX_FRAME_BYTES the format allows",
                    offset.toLong(),
                )
            }
            return OpusPacket(config, stereo, frames, padding)
        }

        /**
         * Reads a frame length, which is one byte below 252 and two above it.
         *
         * Returns the length and where reading stopped.
         */
        private fun readLength(data: ByteArray, at: Int, end: Int, packetAt: Int): Pair<Int, Int> {
            if (at >= end) {
                throw CorruptStreamException("an Opus frame length ran past the packet", packetAt.toLong())
            }
            val first = data[at].toInt() and 0xFF
            if (first < 252) return first to at + 1
            if (at + 1 >= end) {
                throw CorruptStreamException("an Opus frame length ran past the packet", packetAt.toLong())
            }
            val second = data[at + 1].toInt() and 0xFF
            return (second * 4 + first) to at + 2
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

package io.github.yuroyami.audiokodec.internal.mpeg

import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.internal.bits.BitReaderMsb

/** Layer I and II frame decoding shared by bare streams and packet-indexed containers. */
internal class Layer12FrameDecoder(
    private val channels: Int,
    private val layer: Int,
    private val options: DecodeOptions,
) {
    private val filter = PolyphaseFilter(channels)

    /** Levels each subband is quantised to, or zero when it carries nothing. */
    private val levels = Array(channels) { IntArray(32) }

    /** The scalefactor each of the three parts of a frame uses. */
    private val scalefactors = Array(channels) { Array(32) { IntArray(3) } }

    /** One filterbank step's worth of subband samples, three at a time. */
    private val work = Array(channels) { DoubleArray(3 * 32) }

    private var frameBytes = ByteArray(0)
    private var output = FloatArray(0)
    private var frameOffset = 0L

    /** Decodes one frame into [output], returning the sample frames it made. */
    fun decode(bytes: ByteArray, header: MpegFrameHeader, out: FloatArray, offset: Long): Int {
        frameBytes = bytes
        output = out
        frameOffset = offset
        val start = 4 + if (header.hasCrc) 2 else 0
        val bits = BitReaderMsb(frameBytes, start, header.frameBytes - start)

        return if (layer == 1) decodeLayer1(header, bits) else decodeLayer2(header, bits)
    }

    /**
     * Layer I: one width per subband, one loudness per subband, then twelve
     * samples for each of them in turn.
     */
    private fun decodeLayer1(header: MpegFrameHeader, bits: BitReaderMsb): Int {
        val bound = Layer12Tables.boundOf(header, 32)

        for (sb in 0 until 32) {
            if (sb < bound) {
                for (ch in 0 until channels) levels[ch][sb] = layer1LevelsFrom(bits.readBits(4))
            } else {
                // Above the bound one width covers both channels, because only
                // one set of samples is sent for them.
                val shared = layer1LevelsFrom(bits.readBits(4))
                for (ch in 0 until channels) levels[ch][sb] = shared
            }
        }

        checkCrc(header, bits)

        for (sb in 0 until 32) {
            for (ch in 0 until channels) {
                if (levels[ch][sb] == 0) continue
                val index = bits.readBits(6)
                for (part in 0 until 3) scalefactors[ch][sb][part] = index
            }
        }

        var write = 0
        for (slot in 0 until 12) {
            for (ch in 0 until channels) work[ch].fill(0.0, 0, 32)
            for (sb in 0 until 32) {
                if (sb < bound) {
                    for (ch in 0 until channels) {
                        val count = levels[ch][sb]
                        if (count == 0) continue
                        work[ch][sb] = sample(bits.readBits(Layer12Tables.widthOf(count)), count) *
                            Layer12Tables.SCALEFACTORS[scalefactors[ch][sb][0]]
                    }
                } else {
                    val count = levels[0][sb]
                    if (count == 0) continue
                    val value = sample(bits.readBits(Layer12Tables.widthOf(count)), count)
                    for (ch in 0 until channels) {
                        work[ch][sb] = value * Layer12Tables.SCALEFACTORS[scalefactors[ch][sb][0]]
                    }
                }
            }
            for (ch in 0 until channels) {
                filter.step(work[ch], 0, 1, ch, output, write + ch, channels)
            }
            write += 32 * channels
        }
        return 384
    }

    /**
     * Checks a protected frame once its protected bits, which end where the
     * reader now is, have been read.
     *
     * Layer I joint stereo is read two ways. The allocation actually sent is
     * shorter above the bound, and that is what the standard protects; ffmpeg
     * checks four bits for every subband of every channel instead, as though
     * there were no bound. With no encoder to settle which one files carry,
     * either is accepted, which leaves a damaged frame almost as unlikely to
     * pass as before.
     */
    private fun checkCrc(header: MpegFrameHeader, bits: BitReaderMsb) {
        if (!header.hasCrc) return
        val boundless = if (layer == 1 && header.channelMode == MpegFrameHeader.MODE_JOINT) 32 * channels * 4 else -1
        MpegCrc.verify(frameBytes, bits.bitPosition, options, frameOffset, boundless)
    }

    private fun layer1LevelsFrom(code: Int): Int {
        val width = Layer12Tables.layer1Width(code)
        // Fifteen is not an allocation. Treating the subband as empty keeps the
        // rest of the frame in step, which rejecting it would not.
        if (width <= 0) return 0
        return Layer12Tables.layer1Levels(code)
    }

    /**
     * Layer II: a width from the table the bitrate chose, up to three loudnesses
     * per subband with a code saying which of the frame's three parts each
     * covers, then twelve groups of three samples.
     */
    private fun decodeLayer2(header: MpegFrameHeader, bits: BitReaderMsb): Int {
        val table = Layer12Tables.tableFor(header)
        val sblimit = table.sblimit
        val bound = Layer12Tables.boundOf(header, sblimit)

        for (ch in 0 until channels) levels[ch].fill(0)
        for (sb in 0 until sblimit) {
            val width = table.nbal[sb]
            if (width == 0) continue
            if (sb < bound) {
                for (ch in 0 until channels) levels[ch][sb] = table.levels[sb][bits.readBits(width)]
            } else {
                val shared = table.levels[sb][bits.readBits(width)]
                for (ch in 0 until channels) levels[ch][sb] = shared
            }
        }

        // Which of the frame's three parts share a loudness. Reading this for
        // every allocated subband of both channels comes before any of the
        // values themselves.
        val selection = Array(channels) { IntArray(sblimit) }
        for (sb in 0 until sblimit) {
            for (ch in 0 until channels) {
                if (levels[ch][sb] != 0) selection[ch][sb] = bits.readBits(2)
            }
        }
        checkCrc(header, bits)

        for (sb in 0 until sblimit) {
            for (ch in 0 until channels) {
                if (levels[ch][sb] == 0) continue
                val parts = scalefactors[ch][sb]
                when (selection[ch][sb]) {
                    0 -> {
                        parts[0] = bits.readBits(6)
                        parts[1] = bits.readBits(6)
                        parts[2] = bits.readBits(6)
                    }
                    1 -> {
                        val shared = bits.readBits(6)
                        parts[0] = shared
                        parts[1] = shared
                        parts[2] = bits.readBits(6)
                    }
                    2 -> {
                        val shared = bits.readBits(6)
                        parts[0] = shared
                        parts[1] = shared
                        parts[2] = shared
                    }
                    else -> {
                        parts[0] = bits.readBits(6)
                        val shared = bits.readBits(6)
                        parts[1] = shared
                        parts[2] = shared
                    }
                }
            }
        }

        val codes = IntArray(3)
        var write = 0
        for (granule in 0 until 12) {
            // Twelve granules of three samples each, in three parts of four
            // granules, and each part has its own loudness.
            val part = granule / 4
            for (ch in 0 until channels) work[ch].fill(0.0)

            for (sb in 0 until sblimit) {
                if (sb < bound) {
                    for (ch in 0 until channels) {
                        val count = levels[ch][sb]
                        if (count == 0) continue
                        readTriple(bits, count, codes)
                        val gain = Layer12Tables.SCALEFACTORS[scalefactors[ch][sb][part]]
                        for (s in 0 until 3) work[ch][s * 32 + sb] = sample(codes[s], count) * gain
                    }
                } else {
                    val count = levels[0][sb]
                    if (count == 0) continue
                    readTriple(bits, count, codes)
                    for (ch in 0 until channels) {
                        val gain = Layer12Tables.SCALEFACTORS[scalefactors[ch][sb][part]]
                        for (s in 0 until 3) work[ch][s * 32 + sb] = sample(codes[s], count) * gain
                    }
                }
            }

            for (s in 0 until 3) {
                for (ch in 0 until channels) {
                    filter.step(work[ch], s * 32, 1, ch, output, write + ch, channels)
                }
                write += 32 * channels
            }
        }
        return 1152
    }

    /**
     * Reads three samples, which for the three smallest widths arrive as one
     * codeword holding all of them.
     */
    private fun readTriple(bits: BitReaderMsb, count: Int, into: IntArray) {
        if (Layer12Tables.isGrouped(count)) {
            var packed = bits.readBits(Layer12Tables.codeBits(count))
            for (s in 0 until 3) {
                into[s] = packed % count
                packed /= count
            }
            return
        }
        val width = Layer12Tables.widthOf(count)
        for (s in 0 until 3) into[s] = bits.readBits(width)
    }

    private fun sample(code: Int, count: Int): Double = Layer12Tables.dequantise(code, count)

    fun reset() = filter.reset()
}

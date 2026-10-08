package io.github.yuroyami.audiokodec.internal.mpeg

import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.internal.bits.BitReaderMsb

/** Layer III frame decoding shared by bare streams and packet-indexed containers. */
internal class Mp3FrameDecoder(private val channels: Int, private val options: DecodeOptions) {
    /** One per channel, kept between granules so scalefactors can carry over. */
    private val granules = Array(channels) { Mp3Granule() }
    private val synthesis = Mp3Synthesis(channels)
    private val reservoir = Mp3Reservoir()
    private val timeDomain = Array(channels) { DoubleArray(576) }
    private val mainData = ByteArray(511 + 2048)

    /**
     * Decodes one frame into [output] and returns how many sample frames it
     * produced, which is zero when the frame's data was not there to decode.
     */
    fun decode(frameBytes: ByteArray, header: MpegFrameHeader, output: FloatArray, offset: Long): Int {
        val sideInfoAt = 4 + if (header.hasCrc) 2 else 0
        val mainStart = sideInfoAt + header.sideInfoBytes
        val mainLength = header.frameBytes - mainStart
        if (mainLength <= 0) return 0
        if (header.hasCrc) {
            MpegCrc.verify(frameBytes, header.sideInfoBytes * 8, options, offset)
        }

        val sideInfo = Mp3SideInfo.parse(frameBytes, sideInfoAt, header)

        val assembled = reservoir.assemble(
            sideInfo.mainDataBegin,
            frameBytes,
            mainStart,
            mainLength,
            mainData,
        )
        reservoir.append(frameBytes, mainStart, mainLength)
        // The pool did not hold what this frame points back at, which is what
        // the first frames of a file and the first frame after a seek look
        // like. There is nothing to decode, and the next frame may well be
        // fine, so this is not an error.
        if (assembled == null) {
            synthesis.reset()
            return 0
        }

        val bits = BitReaderMsb(mainData, 0, assembled)
        // Validate the whole frame before advancing either channel's overlap
        // or filterbank. An empty granule may start exactly at the end of the
        // main data; only a granule which actually needs missing bits is bad.
        val requiredBits = sideInfo.granules.sumOf { granule -> granule.sumOf { it.part2And3Length } }
        if (requiredBits > bits.bitLength) {
            throw CorruptStreamException(
                "the MPEG frame needs $requiredBits main data bits but only ${bits.bitLength} are available",
                offset,
            )
        }
        val granuleCount = sideInfo.granules.size
        val samplesPerFrame = header.samplesPerFrame

        for (g in 0 until granuleCount) {
            for (ch in 0 until channels) {
                val granule = granules[ch]
                val info = sideInfo.granules[g][ch]
                val start = bits.bitPosition

                if (header.version == 1) {
                    granule.readScalefactorsVersion1(bits, info, sideInfo.scfsi[ch], g)
                } else {
                    // The right channel of an intensity stereo pair codes
                    // positions rather than loudnesses, and packs them
                    // differently.
                    val intensityRight = ch == 1 &&
                        header.channelMode == MpegFrameHeader.MODE_JOINT &&
                        header.modeExtension and 0x1 != 0
                    granule.readScalefactorsVersion2(bits, info, header, intensityRight)
                }

                val used = bits.bitPosition - start
                val left = (info.part2And3Length - used).coerceAtLeast(0)
                granule.readSpectrum(bits, info, header, left)
            }

            if (channels == 2) {
                synthesis.applyStereo(granules[0], granules[1], header, sideInfo.granules[g][0])
            }

            for (ch in 0 until channels) {
                val granule = granules[ch]
                val info = sideInfo.granules[g][ch]
                synthesis.reorder(granule, info, header)
                synthesis.reduceAlias(granule, info)
                synthesis.inverseTransform(granule, info, ch, timeDomain[ch])
                synthesis.synthesise(
                    timeDomain[ch],
                    ch,
                    output,
                    outOffset = g * 576 * channels + ch,
                    stride = channels,
                )
            }
        }
        return samplesPerFrame
    }

    fun reset() {
        reservoir.clear()
        synthesis.reset()
        for (granule in granules) granule.spectrum.fill(0.0)
    }
}

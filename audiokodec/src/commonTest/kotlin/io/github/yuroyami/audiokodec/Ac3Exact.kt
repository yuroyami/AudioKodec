package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.ac3.Ac3Census
import io.github.yuroyami.audiokodec.internal.ac3.Ac3Decoder
import io.github.yuroyami.audiokodec.internal.ac3.Ac3Frame
import io.github.yuroyami.audiokodec.internal.ac3.Ac3Probe

/**
 * Decodes a raw AC-3 stream the way ffmpeg's decoder does, rounding included.
 *
 * The standard defines decoding in real arithmetic; ffmpeg holds each
 * coefficient as a 24-bit fixed-point integer. [Ac3Probe] reproduces that
 * rounding, and with it the two decoders agree to float rounding wherever the
 * samples are small enough for that to be small, which is the strongest check
 * there is that the syntax, bit allocation, coupling, rematrixing and
 * transforms are the same.
 */
internal object Ac3Exact {

    /** The samples of every syncframe of [bytes], one after another, counting what the frames use in [census]. */
    fun decode(bytes: ByteArray, census: Ac3Census? = null): FloatArray {
        val first = Ac3Frame.parse(bytes, 0)!!
        val decoder = Ac3Decoder(first, tolerant = false, probe = Ac3Probe(coefficientBits = 23, census = census))
        val blocks = ArrayList<FloatArray>()
        var at = 0
        while (at + Ac3Frame.HEADER_BYTES <= bytes.size) {
            val frame = Ac3Frame.parse(bytes, at) ?: break
            val block = FloatArray(Ac3Frame.FRAMES_PER_SYNCFRAME * frame.channels)
            decoder.decode(bytes, at, frame.frameBytes, block)
            blocks.add(block)
            at += frame.frameBytes
        }
        val all = FloatArray(blocks.sumOf { it.size })
        var position = 0
        for (block in blocks) {
            block.copyInto(all, position)
            position += block.size
        }
        return all
    }
}

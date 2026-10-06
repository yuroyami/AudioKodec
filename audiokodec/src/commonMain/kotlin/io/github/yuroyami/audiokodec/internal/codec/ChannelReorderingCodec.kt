package io.github.yuroyami.audiokodec.internal.codec

import io.github.yuroyami.audiokodec.AudioChannel

/**
 * Wraps a codec whose container interleaves channels in some order other than
 * the canonical one, and moves every frame into canonical order as it is
 * decoded.
 *
 * Doing this inside the codec rather than after it means seeking, trimming and
 * the exact integer form all see canonical frames without knowing a reorder
 * happened.
 */
internal class ChannelReorderingCodec(
    private val inner: SampleCodec,
    /** For each output channel, the index of the stored channel that feeds it. */
    private val sourceOfOutput: IntArray,
) : SampleCodec by inner {

    init {
        require(sourceOfOutput.size == inner.channels) {
            "a reorder of ${sourceOfOutput.size} channels cannot wrap a ${inner.channels}-channel codec"
        }
    }

    private val floatFrame = FloatArray(inner.channels)
    private val intFrame = IntArray(inner.channels)

    override fun decode(
        src: ByteArray,
        srcOffset: Int,
        blocks: Int,
        out: FloatArray,
        outOffset: Int,
    ): Int {
        val written = inner.decode(src, srcOffset, blocks, out, outOffset)
        val channels = inner.channels
        var at = outOffset
        repeat(blocks * inner.framesPerBlock) {
            out.copyInto(floatFrame, 0, at, at + channels)
            for (channel in 0 until channels) out[at + channel] = floatFrame[sourceOfOutput[channel]]
            at += channels
        }
        return written
    }

    override fun decodeInt(
        src: ByteArray,
        srcOffset: Int,
        blocks: Int,
        out: IntArray,
        outOffset: Int,
    ): Int {
        val written = inner.decodeInt(src, srcOffset, blocks, out, outOffset)
        val channels = inner.channels
        var at = outOffset
        repeat(blocks * inner.framesPerBlock) {
            out.copyInto(intFrame, 0, at, at + channels)
            for (channel in 0 until channels) out[at + channel] = intFrame[sourceOfOutput[channel]]
            at += channels
        }
        return written
    }

    companion object {
        /**
         * The canonical order of [stored], and the codec that produces it, or
         * [inner] unchanged when the stored order is already canonical.
         *
         * Every position in [stored] must be distinct and known.
         */
        fun canonicalOrder(inner: SampleCodec, stored: List<AudioChannel>): Pair<List<AudioChannel>, SampleCodec> {
            val canonical = stored.sortedBy { it.ordinal }
            if (canonical == stored) return canonical to inner
            val sourceOfOutput = IntArray(canonical.size) { stored.indexOf(canonical[it]) }
            return canonical to ChannelReorderingCodec(inner, sourceOfOutput)
        }
    }
}

package io.github.yuroyami.audiokodec

/**
 * Knobs that change how a decoder handles a stream.
 *
 * The defaults are the safe reading: strict about malformed input, fresh
 * buffers for every block, and a one gibibyte ceiling on any single
 * allocation.
 */
public class DecodeOptions(
    /**
     * How to treat a stream that ends early or goes bad partway through.
     *
     * False, the default, throws [TruncatedStreamException] or
     * [CorruptStreamException] as soon as the problem is found. True keeps
     * every complete block decoded up to that point and reports end of stream
     * instead, throwing only when nothing at all could be decoded. Use true
     * for files off the network or out of a recovery folder.
     */
    public val tolerant: Boolean = false,

    /**
     * Whether a decoder may hand back the same array on every call.
     *
     * False, the default, allocates a fresh [FloatArray] per block, so a
     * returned [AudioFrame] stays valid forever. True reuses one buffer, which
     * removes almost all allocation from a decode loop, and makes every
     * returned frame invalid as soon as the next one is pulled.
     */
    public val reuseBuffers: Boolean = false,

    /**
     * Whether a seek may fall back to scanning the stream.
     *
     * Some files carry no seek table, such as a VBR MP3 without a useful Xing
     * TOC. Landing exactly then requires walking from the last known point.
     * False makes an unindexed seek throw [UnsupportedFeatureException].
     * MPEG seeks can use a stored Xing/Info TOC or the index built by playback.
     * Refining the quantised TOC can read a suffix of frame headers; it keeps
     * the resulting seek sample accurate.
     */
    public val allowSlowSeek: Boolean = true,

    /**
     * Highest channel count a header may claim.
     *
     * The formats themselves permit counts that no file legitimately uses.
     * This is a bound on hostile input, not a statement about what is
     * musically sensible.
     */
    public val maxChannels: Int = 64,

    /** Highest sample rate a header may claim. */
    public val maxSampleRate: Int = 768_000,

    /**
     * Largest single allocation a decode may make, in bytes.
     *
     * A header is a few dozen bytes and can ask for terabytes. Every buffer
     * AudioKodec sizes from a field in the file is checked against this first,
     * and [AllocationGuardException] is thrown before the allocation is
     * attempted.
     */
    public val maxAllocationBytes: Long = 1L shl 30,

    /**
     * The mode a G.722 stream was coded in, which no container records.
     *
     * Mode 1, the default, is 64 kbit/s of audio and decodes all six bits of
     * the lower sub-band. Modes 2 and 3 give the last one or two of those
     * bits to an auxiliary data channel, leaving 56 or 48 kbit/s of audio,
     * and decode only the rest.
     */
    public val g722Mode: Int = 1,
) {
    init {
        require(maxChannels in 1..4096) { "maxChannels must be 1..4096, was $maxChannels" }
        require(g722Mode in 1..3) { "g722Mode must be 1, 2 or 3, was $g722Mode" }
        require(maxSampleRate > 0) { "maxSampleRate must be positive, was $maxSampleRate" }
        require(maxAllocationBytes > 0) {
            "maxAllocationBytes must be positive, was $maxAllocationBytes"
        }
    }

    /**
     * Throws [AllocationGuardException] when [bytes] is over the ceiling.
     *
     * Call this before sizing any buffer from a value the file supplied.
     */
    public fun checkAllocation(bytes: Long, what: String) {
        if (bytes < 0 || bytes > maxAllocationBytes) {
            throw AllocationGuardException(what, bytes, maxAllocationBytes)
        }
    }

    /** Throws when a header's channel count or sample rate is out of bounds. */
    public fun checkStreamShape(sampleRate: Int, channels: Int, offset: Long = -1L) {
        if (channels !in 1..maxChannels) {
            throw CorruptStreamException(
                "channel count $channels is outside 1..$maxChannels", offset,
            )
        }
        if (sampleRate !in 1..maxSampleRate) {
            throw CorruptStreamException(
                "sample rate $sampleRate is outside 1..$maxSampleRate", offset,
            )
        }
    }

    /** A copy with the named values replaced. */
    public fun copy(
        tolerant: Boolean = this.tolerant,
        reuseBuffers: Boolean = this.reuseBuffers,
        allowSlowSeek: Boolean = this.allowSlowSeek,
        maxChannels: Int = this.maxChannels,
        maxSampleRate: Int = this.maxSampleRate,
        maxAllocationBytes: Long = this.maxAllocationBytes,
        g722Mode: Int = this.g722Mode,
    ): DecodeOptions = DecodeOptions(
        tolerant = tolerant,
        reuseBuffers = reuseBuffers,
        allowSlowSeek = allowSlowSeek,
        maxChannels = maxChannels,
        maxSampleRate = maxSampleRate,
        maxAllocationBytes = maxAllocationBytes,
        g722Mode = g722Mode,
    )

    public companion object {
        /** The defaults. */
        public val DEFAULT: DecodeOptions = DecodeOptions()
    }
}

package io.github.yuroyami.audiokodecs

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
     * Some files carry no seek table: a VBR MP3 without a Xing header is the
     * common case. Landing exactly on a position then means decoding from the
     * last known point, which is fast for a short file and slow for a long
     * one. False makes such a seek throw [UnsupportedFeatureException] rather
     * than block.
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
     * AudioKodecs sizes from a field in the file is checked against this first,
     * and [AllocationGuardException] is thrown before the allocation is
     * attempted.
     */
    public val maxAllocationBytes: Long = 1L shl 30,
) {
    init {
        require(maxChannels in 1..4096) { "maxChannels must be 1..4096, was $maxChannels" }
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
    ): DecodeOptions = DecodeOptions(
        tolerant = tolerant,
        reuseBuffers = reuseBuffers,
        allowSlowSeek = allowSlowSeek,
        maxChannels = maxChannels,
        maxSampleRate = maxSampleRate,
        maxAllocationBytes = maxAllocationBytes,
    )

    public companion object {
        /** The defaults. */
        public val DEFAULT: DecodeOptions = DecodeOptions()
    }
}

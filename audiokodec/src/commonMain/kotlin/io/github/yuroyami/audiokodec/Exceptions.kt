package io.github.yuroyami.audiokodec

/**
 * Base class for every failure AudioKodec reports.
 *
 * Malformed encoded input is reported through this hierarchy. Invalid API
 * arguments still use the standard argument/state exceptions, and failures
 * thrown by caller-supplied sources or sinks are not disguised as file
 * corruption.
 */
public open class AudioKodecException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The bytes were recognised, but AudioKodec has no decoder for that format.
 *
 * The message always names the format, so a caller can route the file to a
 * platform decoder instead of guessing. Sniffing is not decoding: AudioKodec
 * names more formats than it can read.
 */
public class UnsupportedFormatException(
    /** The format as named by the sniffer, or `null` when nothing matched. */
    public val format: AudioContainer?,
    message: String,
) : AudioKodecException(message)

/**
 * The format is supported, but this file uses a corner of it that is not
 * implemented.
 *
 * [feature] names the corner. AudioKodec throws this rather than returning
 * approximate audio: a partially decoded stream that sounds wrong is worse
 * than an error a caller can handle.
 */
public class UnsupportedFeatureException(
    public val feature: String,
    message: String = "Unsupported feature: $feature",
) : AudioKodecException(message)

/**
 * The stream violates its own format: a bad magic number, an impossible field
 * value, a chunk that overruns its parent, a checksum that does not match.
 *
 * [offset] is the byte position in the source where the problem was detected,
 * or -1 when the position is not meaningful.
 */
public class CorruptStreamException(
    message: String,
    public val offset: Long = -1L,
) : AudioKodecException(if (offset >= 0) "$message (at byte $offset)" else message)

/**
 * The stream ended in the middle of something AudioKodec still needed.
 *
 * With [DecodeOptions.tolerant] set, a decoder emits every complete frame it
 * managed to read and only throws this when nothing at all could be decoded.
 */
public class TruncatedStreamException(
    message: String,
    public val offset: Long = -1L,
) : AudioKodecException(if (offset >= 0) "$message (at byte $offset)" else message)

/**
 * A header asked for more memory than [DecodeOptions.maxAllocationBytes]
 * allows.
 *
 * A 40-byte header can claim four billion channels at 768 kHz. This is the
 * guard that stops that claim from becoming an allocation, and it fires before
 * any buffer is created.
 */
public class AllocationGuardException(
    message: String,
    /** Bytes the stream asked for. */
    public val requested: Long,
    /** Bytes the current [DecodeOptions] allow. */
    public val limit: Long,
) : AudioKodecException("$message (asked for $requested bytes, limit is $limit)")

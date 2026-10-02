package io.github.yuroyami.audiokodecs.internal.container

import io.github.yuroyami.audiokodecs.AudioCodec
import io.github.yuroyami.audiokodecs.AudioContainer
import io.github.yuroyami.audiokodecs.AudioFormat
import io.github.yuroyami.audiokodecs.ChannelLayout
import io.github.yuroyami.audiokodecs.CorruptStreamException
import io.github.yuroyami.audiokodecs.DecodeOptions
import io.github.yuroyami.audiokodecs.TruncatedStreamException
import io.github.yuroyami.audiokodecs.UnsupportedFeatureException
import io.github.yuroyami.audiokodecs.internal.ParsedStream
import io.github.yuroyami.audiokodecs.internal.codec.G711Codec
import io.github.yuroyami.audiokodecs.internal.codec.LinearPcmCodec
import io.github.yuroyami.audiokodecs.internal.codec.PcmLayout
import io.github.yuroyami.audiokodecs.internal.io.SourceReader

/**
 * Reads Sun and NeXT AU.
 *
 * The header is six big-endian 32-bit fields and nothing else, which makes it
 * the simplest audio container still in use. Everything after the header up to
 * the stated length is samples.
 *
 * A data size of `0xFFFFFFFF` means the writer did not know the length, which
 * is what a program piping audio to a socket writes. That case reads to end of
 * input.
 */
internal object AuReader {

    private const val MU_LAW_8 = 1L
    private const val PCM_8 = 2L
    private const val PCM_16 = 3L
    private const val PCM_24 = 4L
    private const val PCM_32 = 5L
    private const val FLOAT_32 = 6L
    private const val FLOAT_64 = 7L
    private const val A_LAW_8 = 27L

    fun parse(reader: SourceReader, options: DecodeOptions): ParsedStream {
        reader.seek(0)
        reader.expectFourCc(".snd", "AU header")
        val headerSize = reader.readU32Be()
        val declaredData = reader.readU32Be()
        val encoding = reader.readU32Be()
        val sampleRate = reader.readI32Be()
        val channels = reader.readI32Be()

        options.checkStreamShape(sampleRate, channels, 0)
        if (headerSize < 24) {
            throw CorruptStreamException("an AU header is at least 24 bytes, this one says $headerSize", 4)
        }
        if (headerSize % 8 != 0L) {
            throw CorruptStreamException(
                "an AU data offset must be a multiple of 8, this one is $headerSize",
                4,
            )
        }

        val sourceSize = reader.size
        if (sourceSize >= 0 && headerSize > sourceSize) {
            throw TruncatedStreamException(
                "the AU header runs to byte $headerSize, but only $sourceSize bytes exist",
                sourceSize,
            )
        }
        val available = if (sourceSize >= 0) (sourceSize - headerSize).coerceAtLeast(0L) else -1L
        // The marker for "length not known" is what a program writing to a
        // pipe leaves behind, and it is the only case where the file's own
        // length decides. Any other stated size is kept, so a truncated file
        // is reported as truncated rather than read as a shorter one.
        val dataBytes = if (declaredData == 0xFFFF_FFFFL) available else declaredData

        val layout = ChannelLayout.canonical(channels)
        val format = AudioFormat(sampleRate, layout)

        val (codec, sampleCodec, bitDepth) = when (encoding) {
            MU_LAW_8 -> Triple(AudioCodec.MU_LAW, G711Codec(channels, aLaw = false), null)
            A_LAW_8 -> Triple(AudioCodec.A_LAW, G711Codec(channels, aLaw = true), null)
            PCM_8 -> Triple(
                AudioCodec.PCM_INTEGER,
                LinearPcmCodec(channels, PcmLayout(8, littleEndian = false, signed = true)),
                8,
            )
            PCM_16 -> Triple(
                AudioCodec.PCM_INTEGER,
                LinearPcmCodec(channels, PcmLayout(16, littleEndian = false, signed = true)),
                16,
            )
            PCM_24 -> Triple(
                AudioCodec.PCM_INTEGER,
                LinearPcmCodec(channels, PcmLayout(24, littleEndian = false, signed = true)),
                24,
            )
            PCM_32 -> Triple(
                AudioCodec.PCM_INTEGER,
                LinearPcmCodec(channels, PcmLayout(32, littleEndian = false, signed = true)),
                32,
            )
            FLOAT_32 -> Triple(
                AudioCodec.PCM_FLOAT,
                LinearPcmCodec(
                    channels,
                    PcmLayout(32, littleEndian = false, signed = true, isFloat = true),
                ),
                null,
            )
            FLOAT_64 -> Triple(
                AudioCodec.PCM_FLOAT,
                LinearPcmCodec(
                    channels,
                    PcmLayout(64, littleEndian = false, signed = true, isFloat = true),
                ),
                null,
            )
            else -> throw UnsupportedFeatureException(
                "AU encoding $encoding",
                "this AU file uses encoding $encoding, which AudioKodecs cannot decode",
            )
        }

        return ParsedStream(
            container = AudioContainer.AU,
            codec = codec,
            sampleCodec = sampleCodec,
            format = format,
            dataOffset = headerSize,
            dataBytes = dataBytes,
            declaredFrames = null,
            bitDepth = bitDepth,
        )
    }
}

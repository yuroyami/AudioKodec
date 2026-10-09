package io.github.yuroyami.audiokodec.internal.container

import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.internal.ParsedStream
import io.github.yuroyami.audiokodec.internal.ilbc.IlbcCodec
import io.github.yuroyami.audiokodec.internal.io.SourceReader

/**
 * Reads iLBC in the storage format of RFC 3952, section 4.1: the magic
 * number `#!iLBC20\n` or `#!iLBC30\n`, which gives the frame size, and then
 * frames, one after another, to the end of the file.
 */
internal object IlbcFileReader {
    private const val MAGIC_BYTES = 9L

    fun parse(reader: SourceReader, options: DecodeOptions): ParsedStream {
        reader.seek(0)
        val magic = reader.readBytes(MAGIC_BYTES.toInt()).decodeToString()
        val mode = when (magic) {
            "#!iLBC20\n" -> 20
            "#!iLBC30\n" -> 30
            else -> throw CorruptStreamException("an iLBC file starts with #!iLBC20 or #!iLBC30 and a line feed", 0)
        }
        val size = reader.size
        return ParsedStream(
            container = AudioContainer.ILBC,
            codec = AudioCodec.ILBC,
            sampleCodec = IlbcCodec(mode),
            format = AudioFormat(8_000, ChannelLayout.MONO),
            dataOffset = MAGIC_BYTES,
            dataBytes = if (size >= 0) (size - MAGIC_BYTES).coerceAtLeast(0L) else -1L,
            declaredFrames = null,
            bitDepth = null,
        )
    }
}

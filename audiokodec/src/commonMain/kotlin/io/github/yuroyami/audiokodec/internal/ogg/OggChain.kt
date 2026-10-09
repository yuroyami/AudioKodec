package io.github.yuroyami.audiokodec.internal.ogg

import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.AudioInfo
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.AudioSource
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.UnsupportedFormatException
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.vorbis.VorbisDecoder

/** RFC 3533 complete links, each with its own headers, granule origin and end trim. */
internal object OggChain {
    class Parsed(val links: List<OggAnalyser.Parsed>) {
        val first: OggAnalyser.Stream = links.first().audio
            ?: throw CorruptStreamException("the Ogg chain has no audio")
        private val knownFrames = links.fold(0L) { total, link -> add(total, link.durationFrames ?: 0L, link.audioStartOffset) }
        val durationFrames: Long? = if (links.any { it.durationFrames == null }) null else knownFrames
        val audioBytes: Long = links.fold(0L) { total, link ->
            add(total, (link.streamEndOffset - link.audioStartOffset).coerceAtLeast(0L), link.audioStartOffset)
        }
        val codec: AudioCodec = first.codec.takeIf { codec -> links.all { it.audio?.codec == codec } } ?: AudioCodec.UNKNOWN
        val bitDepth: Int? = if (codec == AudioCodec.FLAC) {
            OggAnalyser.flacBitDepth(first)?.takeIf { bits -> links.all { OggAnalyser.flacBitDepth(it.audio!!) == bits } }
        } else null

        fun info(reader: SourceReader): AudioInfo {
            val duration = durationFrames
            val bitrate = if (duration != null && duration > 0 && audioBytes > 0) {
                (audioBytes.toDouble() * 8.0 * first.sampleRate / duration).coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()
            } else null
            return AudioInfo(AudioContainer.OGG, codec, first.format, duration, bitDepth, bitrate, true,
                reader.absoluteOffset(links.first().audioStartOffset), audioBytes)
        }
    }

    fun parse(reader: SourceReader, options: DecodeOptions): Parsed {
        val links = ArrayList<OggAnalyser.Parsed>()
        var at = 0L
        var retained = 0L
        while (true) {
            val link = try {
                OggAnalyser.parse(reader, options, at)
            } catch (failure: AudioKodecException) {
                if (links.isEmpty() || !options.tolerant ||
                    failure !is CorruptStreamException && failure !is TruncatedStreamException
                ) throw failure
                break
            }
            val stream = link.audio ?: throw CorruptStreamException("the Ogg link has no audio", at)
            if (links.isNotEmpty() && stream.format != links.first().audio!!.format) {
                throw UnsupportedFeatureException("chained Ogg output format changes",
                    "Ogg link ${links.size + 1} changes ${links.first().audio!!.format} to ${stream.format}")
            }
            retained = add(retained, 128L + link.headerPackets.sumOf { it.size.toLong() } +
                link.streams.sumOf { 64L + it.identification.size }, at)
            options.checkAllocation(retained, "retaining Ogg chained link headers")
            links += link
            if (link.terminalFailure != null) break
            val next = try {
                nextLink(reader, options, link)
            } catch (failure: AudioKodecException) {
                if (!options.tolerant || failure !is CorruptStreamException && failure !is TruncatedStreamException) throw failure
                break
            } ?: break
            if (next <= at) throw CorruptStreamException("the Ogg chain does not advance", next)
            // Retain bounded header packets, not every link's expanded
            // codebooks. Playback rebuilds only the active link's setup.
            link.vorbisConfig = null
            at = next
        }
        if (links.size > 1) links.last().vorbisConfig = null
        return Parsed(links)
    }

    /** Skip the other streams of a group, then require a complete boundary before a new BOS. */
    private fun nextLink(reader: SourceReader, options: DecodeOptions, link: OggAnalyser.Parsed): Long? {
        val ogg = OggReader(reader, options)
        val capture = ByteArray(4)
        var at = link.streamEndOffset
        while (true) {
            reader.seek(at)
            val got = reader.readSome(capture, 0, capture.size)
            if (got == 0) return null
            if (got < 4) {
                if ((0 until got).all { capture[it] == OggPage.CAPTURE[it] }) {
                    throw TruncatedStreamException("the next Ogg link's capture pattern is cut off", reader.absoluteOffset(at))
                }
                return null
            }
            if (!capture.contentEquals(OggPage.CAPTURE)) return null // Preserve ordinary trailing metadata.
            val page = ogg.pageAt(at) ?: return null
            if (page.isFirst) {
                for (other in link.streams) {
                    if (other.serialNumber == link.audio!!.serialNumber) continue
                    val end = ogg.findStreamEnd(other.serialNumber, other.firstPageOffset)
                    end.failure?.let { throw it }
                    val last = end.page ?: throw TruncatedStreamException("an Ogg group has no end page", other.firstPageOffset)
                    if (!last.isLast || add(last.offset, last.byteLength.toLong(), last.offset) > at) {
                        throw CorruptStreamException("an Ogg link begins before the preceding group ends", at)
                    }
                }
                return at
            }
            if (page.serialNumber == link.audio!!.serialNumber || link.streams.none { it.serialNumber == page.serialNumber }) {
                throw CorruptStreamException("an Ogg stream continues after EOS without a new BOS", at)
            }
            at = add(at, page.byteLength.toLong(), at)
        }
    }

    fun decoder(reader: SourceReader, parsed: Parsed, options: DecodeOptions): AudioDecoder {
        fun open(childReader: SourceReader, link: OggAnalyser.Parsed): AudioDecoder = when (link.audio?.codec) {
            AudioCodec.FLAC -> OggFlacDecoder(childReader, link, options)
            AudioCodec.VORBIS -> VorbisDecoder(childReader, link, options)
            AudioCodec.OPUS -> OggOpusDecoder(childReader, link, options)
            AudioCodec.SPEEX -> OggSpeexDecoder(childReader, link, options)
            else -> throw UnsupportedFormatException(AudioContainer.OGG,
                "this Ogg link carries ${link.audio?.codecName ?: "no audio"}, which AudioKodec cannot decode yet")
        }
        if (parsed.links.size == 1) return open(reader, parsed.links[0])
        for (link in parsed.links) {
            if (link.audio?.codec !in listOf(AudioCodec.FLAC, AudioCodec.VORBIS, AudioCodec.OPUS, AudioCodec.SPEEX)) {
                throw UnsupportedFormatException(AudioContainer.OGG,
                    "this Ogg chain carries ${link.audio?.codecName ?: "no audio"}, which AudioKodec cannot decode yet")
            }
        }
        val shared = object : AudioSource {
            override val size: Long get() = reader.source.size
            override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int =
                reader.source.readAt(position, into, offset, count)
            override fun close() = Unit
        }
        val build: (Int) -> AudioDecoder = { index ->
            open(SourceReader(shared, options, baseOffset = reader.baseOffset), parsed.links[index])
        }
        val info = parsed.info(reader)
        return if (parsed.bitDepth != null) IntOggChainDecoder(reader.source, parsed, info, options, build, parsed.bitDepth)
        else OggChainDecoder(reader.source, parsed, info, options, build)
    }

    private fun add(a: Long, b: Long, offset: Long): Long {
        if (b < 0 || a > Long.MAX_VALUE - b) throw CorruptStreamException("an Ogg chain's size or duration overflows 64 bits", offset)
        return a + b
    }
}

package io.github.yuroyami.audiokodec.internal.ac3

import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioInfo
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.UnsupportedFormatException
import io.github.yuroyami.audiokodec.internal.PacketCodec
import io.github.yuroyami.audiokodec.internal.PacketIndex
import io.github.yuroyami.audiokodec.internal.PacketStreamDecoder
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.mpeg.MpegStream

/**
 * AC-3 syncframes for [PacketStreamDecoder], one syncframe to a packet, from
 * a raw stream, MP4 or Matroska.
 */
internal class Ac3PacketCodec(shape: Ac3Frame, tolerant: Boolean) : PacketCodec {

    private val decoder = Ac3Decoder(shape, tolerant)

    override val format: AudioFormat = AudioFormat(shape.sampleRate, shape.layout)
    override val storedBitDepth: Int? = null
    override val maxFramesPerPacket: Int = Ac3Frame.FRAMES_PER_SYNCFRAME

    /**
     * A syncframe's first block overlaps the last block of the one before,
     * and nothing else carries over, so one syncframe settles the decoder
     * exactly.
     */
    override val preRollFrames: Long = Ac3Frame.FRAMES_PER_SYNCFRAME.toLong()

    override fun decode(packet: ByteArray, length: Int, out: FloatArray): Int {
        decoder.decode(packet, 0, length, out)
        return Ac3Frame.FRAMES_PER_SYNCFRAME
    }

    override fun reset() = decoder.reset()

    companion object {
        /**
         * The shape of the syncframe at the start of [bytes], refusing by
         * name the later syntaxes that share AC-3's sync word.
         */
        fun shapeOf(bytes: ByteArray, at: Int, offset: Long): Ac3Frame {
            val bsid = Ac3Frame.bitStreamIdAt(bytes, at)
            if (bsid < 0) throw CorruptStreamException("an AC-3 syncframe does not begin with the sync word", offset)
            refuseLaterSyntax(bsid)
            return Ac3Frame.parse(bytes, at) ?: throw CorruptStreamException("an AC-3 syncframe header is damaged", offset)
        }

        fun refuseLaterSyntax(bsid: Int) {
            if (bsid in 11..16) {
                throw UnsupportedFormatException(
                    AudioContainer.AC3,
                    "this stream is Enhanced AC-3, which AudioKodec cannot decode. Plain AC-3 decodes.",
                )
            }
            if (bsid > 8) {
                throw UnsupportedFeatureException(
                    "AC-3 bit stream identification $bsid",
                    "this AC-3 stream states bsid $bsid, a syntax ATSC A/52 tells decoders built to it not to decode",
                )
            }
        }

        /** A decoder over a raw AC-3 stream. */
        fun openRaw(reader: SourceReader, options: DecodeOptions): AudioDecoder {
            val stream = Ac3Stream.parse(reader, options)
            val count = stream.frameCount
            options.checkAllocation(count * 20, "indexing $count AC-3 syncframes")
            val offsets = LongArray(count.toInt())
            val sizes = IntArray(count.toInt())
            val starts = LongArray(count.toInt() + 1)
            var i = 0
            stream.frames(reader) { offset, frame ->
                offsets[i] = offset
                sizes[i] = frame.frameBytes
                starts[i + 1] = starts[i] + Ac3Frame.FRAMES_PER_SYNCFRAME
                i++
                i < offsets.size
            }
            if (i != offsets.size) throw CorruptStreamException("the AC-3 stream changed while it was being read")
            val index = PacketIndex(offsets, sizes, starts)
            return PacketStreamDecoder.of(reader, index, Ac3PacketCodec(stream.first, options.tolerant), stream.info(reader), 0L, index.totalFrames, options)
        }
    }
}

/**
 * Finds the syncframes of a raw AC-3 stream, as [io.github.yuroyami.audiokodec.internal.container.AdtsStream]
 * does for ADTS. Every syncframe states its own size and holds 1536 samples
 * per channel, so the length is the frames counted. A sync word is believed
 * only when the frames it leads to agree with it.
 */
internal class Ac3Stream(
    val audioOffset: Long,
    val audioEnd: Long,
    val first: Ac3Frame,
    val frameCount: Long,
) {
    fun info(reader: SourceReader): AudioInfo {
        val frames = frameCount * Ac3Frame.FRAMES_PER_SYNCFRAME
        return AudioInfo(
            container = AudioContainer.AC3,
            codec = AudioCodec.AC3,
            format = AudioFormat(first.sampleRate, first.layout),
            durationFrames = frames,
            bitDepth = null,
            bitrate = first.bitrate,
            isVariableBitrate = false,
            dataOffset = reader.absoluteOffset(audioOffset),
            dataSize = audioEnd - audioOffset,
        )
    }

    fun frames(reader: SourceReader, visit: (Long, Ac3Frame) -> Boolean) {
        walk(reader, audioOffset, audioEnd, first) { offset, frame -> visit(offset, frame) }
    }

    companion object {
        private const val SEARCH_BYTES = 512 * 1024
        private const val CONFIRMATIONS = 3
        private const val RESYNC_BYTES = 64 * 1024

        fun parse(reader: SourceReader, options: DecodeOptions): Ac3Stream {
            val size = reader.size
            val start = MpegStream.skipLeadingTags(reader)
            val declaredEnd = if (size < 0) Long.MAX_VALUE else MpegStream.trailingTagStart(reader, size)
            val (offset, first) = findFirstFrame(reader, start, declaredEnd)
                ?: throw CorruptStreamException("no AC-3 syncframe was found in the first ${SEARCH_BYTES / 1024} kilobytes of this file", start)
            options.checkStreamShape(first.sampleRate, first.channels, offset)
            var frames = 0L
            var end = offset
            walk(reader, offset, declaredEnd, first) { at, frame ->
                frames++
                end = at + frame.frameBytes
                true
            }
            return Ac3Stream(offset, end, first, frames)
        }

        /**
         * Walks complete syncframes from [from] that share [shape], crossing
         * junk and embedded tags the way the MPEG and ADTS readers do.
         */
        private fun walk(reader: SourceReader, from: Long, to: Long, shape: Ac3Frame, visit: (Long, Ac3Frame) -> Boolean) {
            val bytes = ByteArray(Ac3Frame.HEADER_BYTES)
            var at = from
            var resynchronising = false
            var skipped = 0
            var count = 0L
            while (at <= to && to - at >= Ac3Frame.HEADER_BYTES) {
                reader.seek(at)
                if (reader.readSome(bytes, 0, bytes.size) < bytes.size) break
                if (MpegStream.isTrailingTag(reader, at, bytes)) break
                val tagEnd = try {
                    MpegStream.embeddedTagEnd(reader, at, bytes, to)
                } catch (failure: TruncatedStreamException) {
                    if (!reader.options.tolerant || count == 0L) throw failure
                    break
                }
                if (tagEnd != null) {
                    at = tagEnd
                    resynchronising = true
                    continue
                }
                val frame = Ac3Frame.parse(bytes, 0)
                val accepted = frame != null && frame.matches(shape) && (!resynchronising || confirms(reader, at, frame, to))
                if (!accepted) {
                    at++
                    resynchronising = true
                    if (++skipped > RESYNC_BYTES) {
                        if (!reader.options.tolerant) throw CorruptStreamException("no AC-3 syncframe was found in $RESYNC_BYTES bytes", at)
                        break
                    }
                    continue
                }
                if (frame.frameBytes.toLong() > to - at || !MpegStream.framePayloadExists(reader, at, frame.frameBytes)) {
                    if (!reader.options.tolerant) {
                        throw TruncatedStreamException("the final AC-3 syncframe declares ${frame.frameBytes} bytes but ends early", at)
                    }
                    break
                }
                count++
                if (!visit(at, frame)) return
                at += frame.frameBytes
                resynchronising = false
                skipped = 0
            }
        }

        /** The first syncframe at or after [from] whose successors agree with it; a later syntax is refused by name. */
        private fun findFirstFrame(reader: SourceReader, from: Long, to: Long): Pair<Long, Ac3Frame>? {
            val window = ByteArray(64 * 1024)
            var at = from
            var scanned = 0L
            while (at < to && scanned < SEARCH_BYTES) {
                reader.seek(at)
                val got = reader.readSome(window, 0, window.size)
                if (got < Ac3Frame.HEADER_BYTES) break
                for (index in 0..got - Ac3Frame.HEADER_BYTES) {
                    val bsid = Ac3Frame.bitStreamIdAt(window, index)
                    if (bsid < 0) continue
                    val frame = Ac3Frame.parse(window, index)
                    if (frame == null) {
                        if (bsid > 8 && laterSyntaxConfirmed(reader, at + index, to)) Ac3PacketCodec.refuseLaterSyntax(bsid)
                        continue
                    }
                    if (confirms(reader, at + index, frame, to)) return (at + index) to frame
                }
                scanned += got
                at += (got - Ac3Frame.HEADER_BYTES + 1).coerceAtLeast(1)
            }
            return null
        }

        /**
         * True when an Enhanced AC-3 frame really starts at [offset]: its
         * `frmsiz` field leads to another sync word, or to the end.
         */
        private fun laterSyntaxConfirmed(reader: SourceReader, offset: Long, to: Long): Boolean {
            val head = ByteArray(4)
            reader.seek(offset)
            if (reader.readSome(head, 0, 4) < 4) return false
            val bytes = 2L * ((((head[2].toInt() and 0x07) shl 8) or (head[3].toInt() and 0xFF)) + 1)
            val next = offset + bytes
            if (next >= to || reader.size in 0..next + 1) return true
            reader.seek(next)
            if (reader.readSome(head, 0, 2) < 2) return true
            return head[0].toInt() and 0xFF == 0x0B && head[1].toInt() and 0xFF == 0x77
        }

        /** True when the frames after [offset] line up with [frame], three times or to the end. */
        private fun confirms(reader: SourceReader, offset: Long, frame: Ac3Frame, to: Long): Boolean {
            val probe = ByteArray(Ac3Frame.HEADER_BYTES)
            var at = offset
            var current = frame
            repeat(CONFIRMATIONS) {
                // The first frame must be whole. A later one cut short is the stream's truncated end, which is
                // reported as such, not a reason to doubt the frames before it.
                if (at > to || current.frameBytes.toLong() > to - at) return at != offset
                if (!MpegStream.framePayloadExists(reader, at, current.frameBytes)) return at != offset
                val next = at + current.frameBytes
                if (next > to || to - next < Ac3Frame.HEADER_BYTES) return true
                reader.seek(next)
                if (reader.readSome(probe, 0, probe.size) < probe.size) return true
                if (MpegStream.isTrailingTag(reader, next, probe)) return true
                val following = Ac3Frame.parse(probe, 0) ?: return false
                if (!following.matches(frame)) return false
                at = next
                current = following
            }
            return true
        }
    }
}

package io.github.yuroyami.audiokodec.internal.mpeg

import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.tag.Id3v2Reader

/**
 * Finds the frames in an MPEG audio file and works out its shape.
 *
 * There is no header to read. The audio begins wherever the first frame is,
 * which is after any tag block in front of it, and it ends wherever the last
 * one does. How long the file is depends on what an encoder chose to write:
 * a constant bitrate file can be measured with a division, a variable bitrate
 * one usually carries a frame count in its first frame, and one that carries
 * neither has to be counted frame by frame.
 *
 * A false sync word turns up in ordinary audio data every couple of kilobytes,
 * so a candidate is only accepted when the frame it describes is followed by
 * another frame that agrees with it. That test is what stops a decoder starting
 * halfway through a frame and playing noise.
 */
internal class MpegStream(
    /** Byte offset of the first audio frame. */
    val audioOffset: Long,
    /** Byte offset just past the last audio frame, or the file size. */
    val audioEnd: Long,
    val first: MpegFrameHeader,
    val vbr: MpegVbrHeader?,
    /** Byte offset of the frame that held the VBR header, or -1. */
    val vbrFrameOffset: Long,
    /** Frames of audio the file holds, not counting the VBR header frame. */
    val frameCount: Long,
    /** Samples the file decodes to, after gapless trimming. */
    val durationFrames: Long?,
    /** Samples to drop from the front, which is the encoder's own padding. */
    val startTrim: Int,
    /** True when every frame has the same bitrate. */
    val isConstantBitrate: Boolean,
    val averageBitrateKbps: Int,
) {

    /** Bytes of audio, excluding the VBR header frame. */
    val audioBytes: Long get() = audioEnd - audioOffset

    companion object {

        /** How far into a file to look for the first frame. */
        private const val SEARCH_BYTES = 512 * 1024

        /** How many following frames have to agree before a sync is believed. */
        private const val CONFIRMATIONS = 3

        /** Maximum damaged gap crossed while counting an unindexed stream. */
        private const val RESYNC_BYTES = 64 * 1024

        fun parse(reader: SourceReader, options: DecodeOptions): MpegStream {
            val size = reader.size
            val start = skipLeadingTags(reader)
            val declaredEnd = if (size < 0) Long.MAX_VALUE else trailingTagStart(reader, size)

            val found = findFirstFrame(reader, start, declaredEnd)
                ?: throw CorruptStreamException(
                    "no MPEG audio frame was found in the first " +
                        "${SEARCH_BYTES / 1024} kilobytes of this file",
                    start,
                )

            options.checkStreamShape(found.header.sampleRate, found.header.channels, found.offset)

            // The first frame may be a silent one holding the file's own
            // bookkeeping. If it is, it is not audio and the audio starts after
            // it, but its numbers describe everything that follows.
            val frameBytes = ByteArray(found.header.frameBytes)
            reader.seek(found.offset)
            reader.readFully(frameBytes)
            val vbr = MpegVbrHeader.parse(frameBytes, found.header)

            val audioOffset = if (vbr != null) {
                if (found.offset > Long.MAX_VALUE - found.header.frameBytes) {
                    throw CorruptStreamException("the first MPEG frame end overflows 64 bits", found.offset)
                }
                found.offset + found.header.frameBytes
            } else {
                found.offset
            }
            val header = if (vbr != null) {
                findFirstFrame(reader, audioOffset, declaredEnd)?.header ?: found.header
            } else {
                found.header
            }

            val samplesPerFrame = header.samplesPerFrame.toLong()
            // A Xing or VBRI header may leave its frame count out, and then
            // only walking the frames can say how long the stream is.
            val countedByHeader = vbr != null && vbr.frameCount > 0
            val scan = if (!countedByHeader || size < 0) {
                scanFrames(reader, audioOffset, declaredEnd, header)
            } else {
                null
            }

            val frames: Long
            val constant: Boolean
            when {
                countedByHeader -> {
                    frames = vbr.frameCount.toLong()
                    constant = vbr.isConstantBitrate
                }
                else -> {
                    // Padding changes a frame's byte length even in a CBR file,
                    // and a VBR stream is not required to carry Xing or VBRI.
                    // Dividing by the first frame therefore gives neither a
                    // reliable duration nor a reliable CBR test. Walk the frame
                    // headers instead; this reads four bytes per frame and does
                    // not decode the payload.
                    val measured = scan
                        ?: throw CorruptStreamException("the MPEG stream length could not be measured", audioOffset)
                    frames = measured.count
                    constant = measured.constantBitrate
                }
            }

            val end = scan?.endOffset ?: declaredEnd
            val audioBytes = (end - audioOffset).coerceAtLeast(0L)
            val rawSamples = if (frames > Long.MAX_VALUE / samplesPerFrame) {
                throw CorruptStreamException("the MPEG frame count overflows its sample duration")
            } else {
                frames * samplesPerFrame
            }
            var trim = 0
            var duration: Long? = if (rawSamples > 0) rawSamples else null
            if (vbr != null && vbr.hasGaplessInfo) {
                // The encoder's own delay and padding are what make one track
                // follow another with no click. The decoder adds a delay of its
                // own on top, so the first real sample arrives later by that
                // much as well.
                trim = vbr.encoderDelay + MpegVbrHeader.DECODER_DELAY
                // The decoder's delay does not shorten the audio, it moves it:
                // what it pushes off the front reappears inside the encoder's
                // padding at the back. Subtracting it twice is a mistake that
                // costs exactly 529 samples off the end of every file.
                val trimmed = (rawSamples - vbr.encoderDelay - vbr.encoderPadding)
                    .coerceAtMost(rawSamples - trim)
                duration = if (trimmed > 0) trimmed else null
            }

            val bitrate = if (duration != null && duration > 0) {
                val seconds = duration.toDouble() / header.sampleRate
                if (seconds > 0) {
                    (audioBytes.toDouble() * 8.0 / seconds / 1000.0)
                        .coerceAtMost(Int.MAX_VALUE.toDouble())
                        .toInt()
                } else {
                    header.bitrateKbps
                }
            } else {
                header.bitrateKbps
            }

            return MpegStream(
                audioOffset = audioOffset,
                audioEnd = end,
                first = header,
                vbr = vbr,
                vbrFrameOffset = if (vbr != null) found.offset else -1L,
                frameCount = frames,
                durationFrames = duration,
                startTrim = trim,
                isConstantBitrate = constant,
                averageBitrateKbps = bitrate,
            )
        }

        class Found(val offset: Long, val header: MpegFrameHeader)

        private class FrameScan(
            val count: Long,
            val constantBitrate: Boolean,
            val endOffset: Long,
        )

        /**
         * Counts complete frames and observes their bitrates.
         *
         * A few bytes of junk between otherwise valid frames are tolerated in
         * the same way the decoder resynchronises. A candidate found while
         * resynchronising still has to be confirmed, so a sync-shaped word in a
         * frame payload is not counted as audio.
         */
        private fun scanFrames(
            reader: SourceReader,
            from: Long,
            to: Long,
            shape: MpegFrameHeader,
        ): FrameScan {
            val bytes = ByteArray(4)
            var at = from
            var count = 0L
            var bitrate = -1
            var constant = true
            var resynchronising = false
            var skipped = 0
            var lastFrameEnd = from

            while (at <= to && to - at >= 4) {
                reader.seek(at)
                val got = reader.readSome(bytes, 0, bytes.size)
                // A partial header cannot identify another audio frame.
                if (got < bytes.size || isTrailingTag(reader, at, bytes)) break
                val tagEnd = try {
                    embeddedTagEnd(reader, at, bytes, to)
                } catch (failure: TruncatedStreamException) {
                    if (!reader.options.tolerant || count == 0L) throw failure
                    break
                }
                if (tagEnd != null) {
                    at = tagEnd
                    resynchronising = true
                    continue
                }
                if (bytes.all { it == 0.toByte() }) {
                    // Padding can be longer than the damaged-gap search limit.
                    // Keep confirming any frame found on its far side.
                    at += bytes.size
                    resynchronising = true
                    continue
                }
                val header = MpegFrameHeader.parse(bytes, 0)
                val accepted = header != null &&
                    header.matches(shape) &&
                    (!resynchronising || confirms(reader, at, header, to))
                if (!accepted) {
                    at++
                    resynchronising = true
                    skipped++
                    if (skipped > RESYNC_BYTES) {
                        if (!reader.options.tolerant) {
                            throw CorruptStreamException(
                                "no MPEG audio frame was found in $RESYNC_BYTES bytes",
                                at,
                            )
                        }
                        break
                    }
                    continue
                }

                if (header.frameBytes.toLong() > to - at ||
                    !framePayloadExists(reader, at, header.frameBytes)
                ) {
                    if (!reader.options.tolerant) {
                        throw TruncatedStreamException(
                            "the final MPEG frame declares ${header.frameBytes} bytes but ends early",
                            at,
                        )
                    }
                    break
                }
                if (bitrate < 0) {
                    bitrate = header.bitrateKbps
                } else if (header.bitrateKbps != bitrate) {
                    constant = false
                }
                count++
                if (at > Long.MAX_VALUE - header.frameBytes) break
                at += header.frameBytes
                lastFrameEnd = at
                resynchronising = false
                skipped = 0
            }
            return FrameScan(count, constant, lastFrameEnd)
        }

        /**
         * Finds the first frame at or after [from] whose successors agree.
         *
         * A frame is believed when the bytes at its stated length are another
         * frame of the same shape, three times over or until the file ends.
         */
        fun findFirstFrame(reader: SourceReader, from: Long, to: Long): Found? {
            val window = ByteArray(64 * 1024)
            var at = from
            var scanned = 0L
            var sawFreeFormat = false

            while (at < to && scanned < SEARCH_BYTES) {
                reader.seek(at)
                val got = reader.readSome(window, 0, window.size)
                if (got < 4) break
                var index = 0
                while (index <= got - 4) {
                    val header = MpegFrameHeader.parse(window, index)
                    if (header == null) {
                        if (MpegFrameHeader.isFreeFormat(window, index)) sawFreeFormat = true
                        index++
                        continue
                    }
                    if (at > Long.MAX_VALUE - index) return null
                    val offset = at + index
                    if (confirms(reader, offset, header, to)) return Found(offset, header)
                    index++
                }
                scanned += got
                // Overlap by three bytes so a header split across two reads is
                // still found.
                val advance = (got - 3).coerceAtLeast(1)
                if (at > Long.MAX_VALUE - advance) break
                at += advance
            }

            if (sawFreeFormat) {
                throw UnsupportedFeatureException(
                    "free format MPEG audio",
                    "this MPEG audio stream uses the free format, whose frames state no " +
                        "length and have to be measured by looking for the next one",
                )
            }
            return null
        }

        /** True when the frames after [offset] line up with [header]. */
        private fun confirms(
            reader: SourceReader,
            offset: Long,
            header: MpegFrameHeader,
            to: Long,
        ): Boolean {
            val probe = ByteArray(4)
            var at = offset
            var current = header
            for (i in 0 until CONFIRMATIONS) {
                if (at > to || current.frameBytes.toLong() > to - at) return false
                if (!framePayloadExists(reader, at, current.frameBytes)) return false
                var next = at + current.frameBytes
                // Running off the end is not a failure. A file whose last frame
                // is its first is short, not wrong.
                if (next > to || to - next < 4) return true
                while (true) {
                    reader.seek(next)
                    if (reader.readSome(probe, 0, 4) < 4) return true
                    if (isTrailingTag(reader, next, probe)) return true
                    val tagEnd = try {
                        embeddedTagEnd(reader, next, probe, to)
                    } catch (failure: TruncatedStreamException) {
                        if (!reader.options.tolerant) throw failure
                        return true
                    } ?: break
                    next = tagEnd
                    if (next > to || to - next < 4) return true
                }
                if (probe.all { it == 0.toByte() } && zeroPaddingToEnd(reader, next, to)) return true
                val following = MpegFrameHeader.parse(probe, 0) ?: return false
                if (!following.matches(current)) return false
                at = next
                current = following
            }
            return true
        }

        /** Skip recognised metadata by size, without allocating or searching its body. */
        fun embeddedTagEnd(reader: SourceReader, at: Long, prefix: ByteArray, to: Long): Long? {
            if (!matches(prefix, 0, "ID3")) return null
            val header = ByteArray(Id3v2Reader.HEADER_SIZE)
            reader.seek(at)
            if (reader.readSome(header, 0, header.size) < header.size) return null
            val size = Id3v2Reader.blockSizeAt(header, 0)
            if (size < 0) return null
            if (at > to || size.toLong() > to - at || !framePayloadExists(reader, at, size)) {
                throw TruncatedStreamException("an embedded ID3v2 block declares $size bytes past end of input", at)
            }
            return at + size
        }

        /** Trailer signatures recognised at an expected frame boundary, even without a source size. */
        fun isTrailingTag(reader: SourceReader, at: Long, header: ByteArray): Boolean {
            if (matches(header, 0, "TAG")) return true // ID3v1 and enhanced ID3v1.
            if (!matches(header, 0, "LYRI")) return false
            val marker = ByteArray(11)
            reader.seek(at)
            return reader.readSome(marker, 0, marker.size) == marker.size && matches(marker, 0, "LYRICSBEGIN")
        }

        /** Confirm a lone frame followed by padding without accepting a false sync before later audio. */
        private fun zeroPaddingToEnd(reader: SourceReader, from: Long, to: Long): Boolean {
            val bytes = ByteArray(4_096)
            var at = from
            while (at < to) {
                reader.seek(at)
                val want = minOf(bytes.size.toLong(), to - at).toInt()
                val got = reader.readSome(bytes, 0, want)
                for (index in 0 until got) if (bytes[index] != 0.toByte()) return false
                if (got < want) return true
                at += got
            }
            return true
        }

        /**
         * Checks the byte at the declared end of a frame when the source cannot
         * state its size. Seeing a four-byte header is not enough to accept a
         * final frame whose payload was cut off.
         */
        private fun framePayloadExists(reader: SourceReader, at: Long, frameBytes: Int): Boolean {
            if (reader.size >= 0) return true
            if (frameBytes <= 0 || at > Long.MAX_VALUE - frameBytes) return false
            reader.seek(at + frameBytes - 1L)
            return reader.readByteOrEof() >= 0
        }

        /**
         * Where the audio can start, which is after any tag block in front.
         *
         * ID3v2 states its own length, and several may be stacked. Anything
         * else in front is left to the frame search to skip over.
         */
        private fun skipLeadingTags(reader: SourceReader): Long {
            val header = ByteArray(10)
            var at = 0L
            while (true) {
                reader.seek(at)
                if (reader.readSome(header, 0, 10) < 10) return at
                if (header[0] != 'I'.code.toByte() ||
                    header[1] != 'D'.code.toByte() ||
                    header[2] != '3'.code.toByte()
                ) {
                    return at
                }
                val major = header[3].toInt() and 0xFF
                val revision = header[4].toInt() and 0xFF
                val flags = header[5].toInt() and 0xFF
                val reservedMask = when (major) {
                    2 -> 0x3F
                    3 -> 0x1F
                    4 -> 0x0F
                    else -> return at
                }
                if (revision == 0xFF || flags and reservedMask != 0) return at
                var size = 0
                for (i in 6 until 10) {
                    val byte = header[i].toInt() and 0xFF
                    if (byte and 0x80 != 0) return at
                    size = (size shl 7) or byte
                }
                // A footer, when the flags say there is one, is ten more bytes.
                val footer = if (flags and 0x10 != 0) 10 else 0
                val blockSize = 10L + size + footer
                if (at > Long.MAX_VALUE - blockSize) {
                    throw CorruptStreamException(
                        "the leading ID3v2 chain overflows 64-bit offsets",
                        at,
                    )
                }
                if (reader.size >= 0 && blockSize > reader.size - at) {
                    if (reader.options.tolerant) return at
                    throw TruncatedStreamException(
                        "a leading ID3v2 block declares $blockSize bytes past end of input",
                        at,
                    )
                }
                at += blockSize
            }
            return at
        }

        /**
         * Where the audio has to stop, which is before any tag block at the end.
         *
         * An ID3v1 block is 128 bytes of `TAG`, and an APE block states its own
         * size. Decoding either as audio produces a burst of noise, which is
         * what a decoder that ignores them does.
         */
        private fun trailingTagStart(reader: SourceReader, size: Long): Long {
            if (size <= 0) return size
            var end = size
            val tail = ByteArray(256)
            while (true) {
                val want = if (end < tail.size) end.toInt() else tail.size
                if (want < 32) return end
                reader.seek(end - want)
                if (reader.readSome(tail, 0, want) < want) return end

                if (want >= 128 && matches(tail, want - 128, "TAG")) {
                    end -= 128
                    continue
                }
                if (want >= 32 && matches(tail, want - 32, "APETAGEX")) {
                    var declared = 0L
                    for (i in 3 downTo 0) {
                        declared = (declared shl 8) or
                            (tail[want - 32 + 12 + i].toLong() and 0xFF)
                    }
                    // The footer states everything after the header, and the
                    // header itself is another thirty-two bytes when present.
                    val flagsHighByte = tail[want - 32 + 23].toInt() and 0xFF
                    val withHeader = declared + if (flagsHighByte and 0x80 != 0) 32L else 0L
                    if (declared >= 32L && withHeader in 32L..end) {
                        end -= withHeader
                        continue
                    }
                }
                if (want >= 15 && matches(tail, want - 9, "LYRICS200")) {
                    // Lyrics3v2 states its size in the six characters before
                    // its own marker.
                    var declared = 0
                    var valid = want >= 15
                    for (i in 0 until 6) {
                        val c = tail[want - 15 + i].toInt()
                        if (c < '0'.code || c > '9'.code) valid = false
                        declared = declared * 10 + (c - '0'.code)
                    }
                    val total = declared.toLong() + 15L
                    if (valid && declared > 0 && total <= end) {
                        end -= total
                        continue
                    }
                }
                if (want >= 10 && matches(tail, want - 10, "3DI")) {
                    val footerAt = want - 10
                    val major = tail[footerAt + 3].toInt() and 0xFF
                    val revision = tail[footerAt + 4].toInt() and 0xFF
                    val flags = tail[footerAt + 5].toInt() and 0xFF
                    var body = 0L
                    var valid = major == 4 &&
                        revision != 0xFF &&
                        flags and 0x10 != 0 &&
                        flags and 0x0F == 0
                    for (i in 6..9) {
                        val byte = tail[footerAt + i].toInt() and 0xFF
                        if (byte and 0x80 != 0) valid = false
                        body = (body shl 7) or byte.toLong()
                    }
                    val total = body + 20L
                    val start = end - total
                    if (valid && total <= end &&
                        matchingId3v24Header(reader, start, tail, footerAt)
                    ) {
                        end = start
                        continue
                    }
                }
                return end
            }
            return end
        }

        /** Confirms that a trailing ID3v2.4 footer mirrors a real leading header. */
        private fun matchingId3v24Header(
            reader: SourceReader,
            at: Long,
            footer: ByteArray,
            footerAt: Int,
        ): Boolean {
            if (at < 0) return false
            val header = ByteArray(10)
            reader.seek(at)
            if (reader.readSome(header, 0, header.size) != header.size ||
                !matches(header, 0, "ID3")
            ) {
                return false
            }
            for (i in 3 until 10) {
                if (header[i] != footer[footerAt + i]) return false
            }
            return true
        }

        private fun matches(bytes: ByteArray, at: Int, text: String): Boolean {
            if (at < 0 || at + text.length > bytes.size) return false
            for (i in text.indices) {
                if ((bytes[at + i].toInt() and 0xFF) != text[i].code) return false
            }
            return true
        }
    }
}

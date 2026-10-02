package io.github.yuroyami.audiokodecs

import io.github.yuroyami.audiokodecs.internal.Sniffer
import io.github.yuroyami.audiokodecs.internal.container.AiffReader
import io.github.yuroyami.audiokodecs.internal.container.AuReader
import io.github.yuroyami.audiokodecs.internal.container.CafReader
import io.github.yuroyami.audiokodecs.internal.container.ContainerWriter
import io.github.yuroyami.audiokodecs.internal.container.WaveReader
import io.github.yuroyami.audiokodecs.internal.container.Wave64Reader
import io.github.yuroyami.audiokodecs.internal.flac.FlacDecoder
import io.github.yuroyami.audiokodecs.internal.flac.FlacMetadata
import io.github.yuroyami.audiokodecs.internal.io.SourceReader
import io.github.yuroyami.audiokodecs.internal.mpeg.Layer12Decoder
import io.github.yuroyami.audiokodecs.internal.mpeg.Mp3Decoder
import io.github.yuroyami.audiokodecs.internal.mpeg.MpegStream
import io.github.yuroyami.audiokodecs.internal.ogg.OggAnalyser
import io.github.yuroyami.audiokodecs.internal.ogg.OggFlacDecoder
import io.github.yuroyami.audiokodecs.internal.tag.TagAssembler
import io.github.yuroyami.audiokodecs.internal.vorbis.VorbisDecoder
import io.github.yuroyami.audiokodecs.internal.tag.TagLocator
import io.github.yuroyami.audiokodecs.internal.tag.TagWriter
import io.github.yuroyami.audiokodecs.tag.TagBundle
import io.github.yuroyami.audiokodecs.tag.TagWriteOptions

/**
 * The entry point to AudioKodecs.
 *
 * Four things happen here: naming a format, reading a header, decoding audio,
 * and writing it back out. Everything else in the library is reachable from
 * what these return.
 *
 * ```kotlin
 * val audio = AudioKodecs.decode(bytes)
 * println("${audio.format.sampleRate} Hz, ${audio.durationSeconds} s")
 * ```
 */
public object AudioKodecs {

    /** Bytes worth passing to [sniff] for a reliable answer. */
    public const val SNIFF_HEADER_BYTES: Int = 64 * 1024

    /**
     * Names the format of a file from its first bytes, or returns null when
     * nothing matches.
     *
     * This never throws and never reads past what it is given. It names
     * formats AudioKodecs cannot decode as well as those it can, so check
     * [AudioContainer.isDecodable] before assuming a decoder exists. As little
     * as 16 bytes is often enough, but a file with a large tag block in front
     * of it needs more, which is what [SNIFF_HEADER_BYTES] is for.
     */
    public fun sniff(header: ByteArray): AudioContainer? = Sniffer.sniff(header)

    /**
     * Reads what a file's header says, without decoding any audio.
     *
     * This never decodes sample payloads. Formats with a global index require
     * only metadata reads; an unindexed MPEG or Ogg stream may require a
     * lightweight scan of frame or page headers across the file.
     *
     * The source is not closed.
     */
    public fun probe(source: AudioSource, options: DecodeOptions = DecodeOptions.DEFAULT): AudioInfo {
        val detected = containerOf(SourceReader(source, options))
        val reader = detected.reader
        return when (detected.container) {
            AudioContainer.FLAC ->
                FlacDecoder(reader, FlacMetadata.parse(reader, options), options).info
            AudioContainer.OGG -> oggInfo(reader, options)
            AudioContainer.MPEG_AUDIO -> mpegInfo(reader, options)
            else -> parse(reader, options, detected.container).toInfo(reader)
        }
    }

    /** Reads what an in-memory file's header says. */
    public fun probe(bytes: ByteArray, options: DecodeOptions = DecodeOptions.DEFAULT): AudioInfo =
        probe(bytes.asAudioSource(), options)

    /**
     * Opens a decoder over [source].
     *
     * The decoder takes ownership: closing it closes the source. Pull blocks
     * with [AudioDecoder.nextFrame] until it returns null.
     */
    public fun open(
        source: AudioSource,
        options: DecodeOptions = DecodeOptions.DEFAULT,
    ): AudioDecoder {
        val detected = containerOf(SourceReader(source, options))
        val reader = detected.reader
        return when (detected.container) {
            AudioContainer.FLAC ->
                FlacDecoder(reader, FlacMetadata.parse(reader, options), options)
            AudioContainer.OGG -> openOgg(reader, options)
            AudioContainer.MPEG_AUDIO -> openMpeg(reader, options)
            else -> parse(reader, options, detected.container).toDecoder(reader, options)
        }
    }

    /** Opens a decoder over an in-memory file. */
    public fun open(
        bytes: ByteArray,
        options: DecodeOptions = DecodeOptions.DEFAULT,
    ): AudioDecoder = open(bytes.asAudioSource(), options)

    /**
     * Decodes a whole file into memory.
     *
     * Use [open] instead for anything long: this allocates the entire result
     * as one array, and a decoded hour of stereo at 48 kHz is over a gigabyte
     * of Float32. [DecodeOptions.maxAllocationBytes] is what stops that from
     * happening by accident.
     *
     * The source is closed when decoding finishes, whether or not it
     * succeeded. Use [probe] when you want to keep reading from it afterwards.
     */
    public fun decode(
        source: AudioSource,
        options: DecodeOptions = DecodeOptions.DEFAULT,
    ): DecodedAudio {
        // Whole-file decoding hands the caller one array to keep, so reusing
        // the decoder's buffers would be wrong here whatever the caller asked
        // for.
        val decodeOptions = if (options.reuseBuffers) options.copy(reuseBuffers = false) else options
        val decoder = try {
            open(source, decodeOptions)
        } catch (failure: Throwable) {
            try {
                source.close()
            } catch (_: Throwable) {
                // Preserve the decoding failure, which is the useful cause.
            }
            throw failure
        }
        decoder.use {
            val channels = it.format.channels
            val expected = it.durationFrames
            val expectedSamples = expected?.let {
                checkedDecodedSampleCount(it, channels, options)
            }
            val initialSamples = if (expectedSamples != null) {
                minOf(expectedSamples, 1 shl 20)
            } else {
                minOf(4096L, options.maxAllocationBytes / 4, Int.MAX_VALUE.toLong()).toInt()
            }
            var samples = FloatArray(initialSamples)
            var frames = 0L
            while (true) {
                val block = it.nextFrame() ?: break
                if (block.format != it.format) {
                    throw CorruptStreamException(
                        "a decoder changed format from ${it.format} to ${block.format}",
                    )
                }
                if (frames > Long.MAX_VALUE - block.frameCount) {
                    throw AllocationGuardException(
                        "decoded frame count overflows 64 bits",
                        Long.MAX_VALUE,
                        options.maxAllocationBytes,
                    )
                }
                val nextFrames = frames + block.frameCount
                val needed = checkedDecodedSampleCount(nextFrames, channels, options)
                if (needed > samples.size) {
                    samples = samples.copyOf(grownCapacity(samples.size, needed, options))
                }
                val destination = (frames * channels).toInt()
                block.samples.copyInto(samples, destination, 0, block.sampleCount)
                frames = nextFrames
            }
            val finalSamples = checkedDecodedSampleCount(frames, channels, options)
            return DecodedAudio(
                format = it.format,
                samples = if (samples.size == finalSamples) samples else samples.copyOf(finalSamples),
                frames = frames.toInt(),
                info = it.info,
            )
        }
    }

    /** Decodes a whole in-memory file. */
    public fun decode(
        bytes: ByteArray,
        options: DecodeOptions = DecodeOptions.DEFAULT,
    ): DecodedAudio = decode(bytes.asAudioSource(), options)

    /**
     * Decodes a FLAC file and checks it against the MD5 its encoder stored.
     *
     * FLAC records a hash of the unencoded audio in its header, which makes a
     * decoder checkable against whichever encoder wrote the file. Returns true
     * when the decoded samples hash to the stored value, false when they do
     * not, and null when the file states no hash.
     *
     * This decodes the whole file, so it costs a full decode. It is a
     * verification tool rather than something to run before every playback.
     * The source is closed when verification finishes, whether or not it
     * succeeds.
     */
    public fun verifyFlacMd5(
        source: AudioSource,
        options: DecodeOptions = DecodeOptions.DEFAULT,
    ): Boolean? {
        val decoder = try {
            val detected = containerOf(SourceReader(source, options))
            val reader = detected.reader
            if (detected.container != AudioContainer.FLAC) {
                throw UnsupportedFormatException(
                    null,
                    "only a FLAC file carries an MD5 of its audio",
                )
            }
            FlacDecoder(reader, FlacMetadata.parse(reader, options), options)
        } catch (failure: Throwable) {
            try {
                source.close()
            } catch (_: Throwable) {
                // Preserve the verification failure, which is the useful cause.
            }
            throw failure
        }
        return decoder.use { it.verifyMd5() }
    }

    /** Checks an in-memory FLAC file against the MD5 its encoder stored. */
    public fun verifyFlacMd5(
        bytes: ByteArray,
        options: DecodeOptions = DecodeOptions.DEFAULT,
    ): Boolean? = verifyFlacMd5(bytes.asAudioSource(), options)

    /**
     * Reads every tag block a file holds, merged into one view.
     *
     * Only the head and the tail of the file are read, plus the metadata
     * chunks of containers that keep tags inside themselves. A file with more
     * than one block gets them all: the merged fields come from the block with
     * the highest precedence, and [TagBundle.raw] keeps every block as it was
     * stored.
     *
     * This also works on files whose codec AudioKodecs cannot decode, because a
     * tag block does not need that codec's decoder.
     *
     * The source is not closed.
     */
    public fun readTags(source: AudioSource, options: DecodeOptions = DecodeOptions.DEFAULT): TagBundle {
        val reader = SourceReader(source, options)
        val container = try {
            containerOf(reader).container
        } catch (_: AudioKodecsException) {
            null
        }
        return TagAssembler.assemble(TagLocator.findAll(reader, container), options)
    }

    /** Reads the tags of an in-memory file. */
    public fun readTags(bytes: ByteArray, options: DecodeOptions = DecodeOptions.DEFAULT): TagBundle =
        readTags(bytes.asAudioSource(), options)

    /**
     * Writes [tags] into a copy of [source], sent to [sink].
     *
     * The audio is copied byte for byte and never re-encoded, so this is safe
     * to run on a lossy file that would lose quality if it were decoded and
     * encoded again. Only the tag blocks are rewritten.
     *
     * This produces a new file rather than editing one in place. Write to a
     * temporary file and move it over the original, which is what makes the
     * operation safe against a crash halfway through.
     *
     * Neither the source nor the sink is closed.
     */
    public fun writeTags(
        source: AudioSource,
        tags: TagBundle,
        sink: AudioSink,
        options: TagWriteOptions = TagWriteOptions.DEFAULT,
        decodeOptions: DecodeOptions = DecodeOptions.DEFAULT,
    ) {
        val root = SourceReader(source, decodeOptions)
        val detected = try {
            containerOf(root)
        } catch (_: AudioKodecsException) {
            null
        }
        if (detected == null) {
            TagWriter.write(root, null, tags, sink, options)
        } else {
            TagWriter.write(detected.reader, detected.container, tags, sink, options)
        }
    }

    /** Writes [tags] into a copy of an in-memory file and returns the result. */
    public fun writeTags(
        bytes: ByteArray,
        tags: TagBundle,
        options: TagWriteOptions = TagWriteOptions.DEFAULT,
    ): ByteArray {
        val capacity = if (bytes.size <= Int.MAX_VALUE - 8192) bytes.size + 8192 else bytes.size
        val sink = ByteArrayAudioSink(capacity)
        writeTags(bytes.asAudioSource(), tags, sink, options)
        return sink.toByteArray()
    }

    /**
     * Encodes [pcm] into [sink] in the shape [target] describes.
     *
     * The sink is not closed. When it cannot be patched, [PcmSource.frameCount]
     * has to be known in advance, because the header holds lengths that are
     * otherwise only knowable at the end. [tags] are stored natively in WAV,
     * AIFF and FLAC; WAV and AIFF also receive an ID3v2.4 chunk so fields their
     * native text chunks cannot express are retained. AU has no structured tag
     * format and rejects a non-empty bundle.
     */
    public fun encode(
        pcm: PcmSource,
        target: EncodeTarget,
        sink: AudioSink,
        tags: TagBundle? = null,
    ) {
        ContainerWriter.write(pcm, target, sink, tags)
    }

    /** Encodes [pcm] and returns the file as a byte array. */
    public fun encode(
        pcm: PcmSource,
        target: EncodeTarget,
        tags: TagBundle? = null,
    ): ByteArray {
        val sink = ByteArrayAudioSink()
        encode(pcm, target, sink, tags)
        return sink.toByteArray()
    }

    /** Encodes interleaved Float32 samples and returns the file as a byte array. */
    public fun encode(
        samples: FloatArray,
        format: AudioFormat,
        target: EncodeTarget,
        tags: TagBundle? = null,
    ): ByteArray = encode(samples.asPcmSource(format), target, tags)

    /**
     * Reads what an MPEG audio file's frames say.
     *
     * MPEG audio has no file header, so this finds the first frame and reads
     * whatever the encoder left behind about the file as a whole: a frame
     * count, a byte count, and the samples of silence to trim from each end.
     */
    private fun mpegInfo(reader: SourceReader, options: DecodeOptions): AudioInfo =
        mpegInfoOf(MpegStream.parse(reader, options))

    private fun mpegInfoOf(stream: MpegStream): AudioInfo {
        return AudioInfo(
            container = AudioContainer.MPEG_AUDIO,
            codec = stream.first.codec,
            format = AudioFormat(
                stream.first.sampleRate,
                ChannelLayout.canonical(stream.first.channels),
            ),
            durationFrames = stream.durationFrames,
            // MPEG audio codes a spectrum rather than samples, so there is no
            // stored bit depth to report.
            bitDepth = null,
            bitrate = (stream.averageBitrateKbps.toLong() * 1000L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
            isVariableBitrate = !stream.isConstantBitrate,
            dataOffset = stream.audioOffset,
            dataSize = stream.audioBytes,
        )
    }

    /**
     * Opens an MPEG audio file.
     *
     * All three layers decode. Layer III uses its bit reservoir and needs
     * decoder pre-roll when seeking; Layers I and II have independent frames.
     */
    private fun openMpeg(reader: SourceReader, options: DecodeOptions): AudioDecoder {
        val stream = MpegStream.parse(reader, options)
        val info = mpegInfoOf(stream)
        return if (stream.first.layer == 3) {
            Mp3Decoder(reader, stream, info, options)
        } else {
            Layer12Decoder(reader, stream, info, options)
        }
    }

    /**
     * Opens an Ogg file.
     *
     * Ogg is a container, not a codec, so what it can be decoded to depends on
     * what is inside. Vorbis and FLAC decode today; the others are named and
     * refused, which is a more useful answer than a generic failure.
     */
    private fun openOgg(reader: SourceReader, options: DecodeOptions): AudioDecoder {
        val parsed = OggAnalyser.parse(reader, options)
        val stream = parsed.audio
        return when (stream?.codec) {
            AudioCodec.FLAC -> OggFlacDecoder(reader, parsed, options)
            AudioCodec.VORBIS -> VorbisDecoder(reader, parsed, options)
            else -> throw UnsupportedFormatException(
                AudioContainer.OGG,
                "this Ogg file carries ${stream?.codecName ?: "no audio"}, which AudioKodecs " +
                    "cannot decode yet. Its headers and tags are readable.",
            )
        }
    }

    /**
     * Reads what an Ogg file's headers say.
     *
     * This works for every mapping AudioKodecs recognises, decodable or not: the
     * sample rate, the channel count and the length all come from the headers
     * and the last page, none of which needs a codec.
     */
    private fun oggInfo(reader: SourceReader, options: DecodeOptions): AudioInfo {
        val parsed = OggAnalyser.parse(reader, options)
        val stream = parsed.audio ?: throw UnsupportedFormatException(
            AudioContainer.OGG,
            "this Ogg file carries no audio AudioKodecs recognises",
        )
        val duration = parsed.durationFrames
        val bytes = (parsed.streamEndOffset - parsed.audioStartOffset).coerceAtLeast(0L)
        val bitrate = if (duration != null && duration > 0 && bytes > 0) {
            val seconds = duration.toDouble() / stream.sampleRate
            if (seconds > 0) {
                (bytes.toDouble() * 8.0 / seconds)
                    .coerceIn(0.0, Int.MAX_VALUE.toDouble())
                    .toInt()
            } else {
                null
            }
        } else {
            null
        }
        return AudioInfo(
            container = AudioContainer.OGG,
            codec = stream.codec,
            format = AudioFormat(stream.sampleRate, stream.channels),
            durationFrames = duration,
            // Only the lossless mapping has a stored bit depth to report.
            bitDepth = if (stream.codec == AudioCodec.FLAC) {
                OggAnalyser.flacBitDepth(stream)
            } else {
                null
            },
            bitrate = bitrate,
            isVariableBitrate = true,
            dataOffset = reader.absoluteOffset(parsed.audioStartOffset),
            dataSize = bytes,
        )
    }

    private class DetectedContainer(
        val container: AudioContainer,
        val reader: SourceReader,
    )

    /**
     * Validates the one-array representation used by whole-file decoding.
     *
     * Both products are formed in `Long`; a saturating value is used when the
     * mathematical result is larger so an overflow cannot wrap under either
     * the configured allocation limit or Kotlin's `Int` array-size limit.
     */
    private fun checkedDecodedSampleCount(
        frames: Long,
        channels: Int,
        options: DecodeOptions,
    ): Int {
        if (frames < 0) {
            throw CorruptStreamException("a decoder reported a negative duration of $frames frames")
        }
        val samples = if (frames > Long.MAX_VALUE / channels) {
            Long.MAX_VALUE
        } else {
            frames * channels
        }
        val bytes = if (samples > Long.MAX_VALUE / 4) Long.MAX_VALUE else samples * 4
        options.checkAllocation(bytes, "decoding $frames frames of $channels channels")
        if (samples > Int.MAX_VALUE) {
            throw AllocationGuardException(
                "a whole decode must fit in one Kotlin array",
                bytes,
                minOf(options.maxAllocationBytes, Int.MAX_VALUE.toLong() * 4),
            )
        }
        return samples.toInt()
    }

    /**
     * Picks the next size of the whole-file decode buffer.
     *
     * Growth doubles, so the copies made while a result accumulates cost a
     * constant factor of its final size rather than one full copy per block.
     * Doubling stops at the allocation ceiling and at the largest array every
     * platform will hand out, and [needed] has already passed both checks in
     * [checkedDecodedSampleCount], so the fallback to exactly [needed] is
     * always allowed.
     */
    private fun grownCapacity(current: Int, needed: Int, options: DecodeOptions): Int {
        val ceiling = minOf(options.maxAllocationBytes / 4, MAX_GROWN_SAMPLES)
        val doubled = maxOf(current.toLong(), 1L) * 2
        val next = maxOf(needed.toLong(), minOf(doubled, ceiling))
        options.checkAllocation(next * 4, "growing the decode buffer")
        return next.toInt()
    }

    /**
     * The largest buffer geometric growth will ask for on its own. Some
     * virtual machines refuse arrays within a few elements of `Int.MAX_VALUE`.
     */
    private const val MAX_GROWN_SAMPLES: Long = Int.MAX_VALUE.toLong() - 8

    /**
     * Names the container and returns a logical reader positioned after any
     * leading ID3v2 blocks.
     *
     * ID3 is allowed in front of WAV, AIFF, FLAC and other containers, and a
     * tag can be much larger than a sniffing window. Reading only its ten-byte
     * header is enough to jump to the real container without allocating the
     * tag or guessing that every large one belongs to an MP3.
     */
    private fun containerOf(root: SourceReader): DetectedContainer {
        var offset = 0L
        val id3Header = ByteArray(10)
        while (true) {
            root.seek(offset)
            if (root.readSome(id3Header, 0, id3Header.size) != id3Header.size) break
            if (id3Header[0] != 'I'.code.toByte() ||
                id3Header[1] != 'D'.code.toByte() ||
                id3Header[2] != '3'.code.toByte()
            ) {
                break
            }
            val major = id3Header[3].toInt() and 0xFF
            val revision = id3Header[4].toInt() and 0xFF
            val flags = id3Header[5].toInt() and 0xFF
            val reservedMask = when (major) {
                2 -> 0x3F
                3 -> 0x1F
                4 -> 0x0F
                else -> break
            }
            if (revision == 0xFF || flags and reservedMask != 0) break
            var bodySize = 0L
            var valid = true
            for (index in 6..9) {
                val byte = id3Header[index].toInt() and 0xFF
                if (byte and 0x80 != 0) {
                    valid = false
                    break
                }
                bodySize = (bodySize shl 7) or byte.toLong()
            }
            if (!valid) break
            val footerBytes = if (flags and 0x10 != 0) 10L else 0L
            val blockSize = 10L + bodySize + footerBytes
            if (blockSize < 10L || offset > Long.MAX_VALUE - blockSize) {
                throw CorruptStreamException("the leading ID3v2 chain overflows 64-bit offsets", offset)
            }
            if (root.size >= 0 && blockSize > root.size - offset) {
                if (root.options.tolerant) break
                throw TruncatedStreamException(
                    "a leading ID3v2 block declares $blockSize bytes past end of input",
                    offset,
                )
            }
            offset += blockSize
        }

        val view = if (offset == 0L) {
            root
        } else {
            SourceReader(
                source = root.source,
                options = root.options,
                baseOffset = root.absoluteOffset(offset),
            )
        }
        val header = ByteArray(64)
        view.seek(0)
        val got = view.readSome(header, 0, header.size)
        if (got <= 0) {
            if (offset > 0) return DetectedContainer(AudioContainer.MPEG_AUDIO, root)
            throw TruncatedStreamException("the source is empty", 0)
        }
        val container = Sniffer.sniff(if (got == header.size) header else header.copyOf(got))
        if (container == null) {
            if (offset > 0) return DetectedContainer(AudioContainer.MPEG_AUDIO, root)
            throw UnsupportedFormatException(
                null,
                "these bytes do not start with any audio format AudioKodecs knows",
            )
        }
        // MPEG parsing already understands leading ID3 and reports offsets in
        // the original file, so keep its full-source reader.
        return if (container == AudioContainer.MPEG_AUDIO) {
            DetectedContainer(container, root)
        } else {
            DetectedContainer(container, view)
        }
    }

    private fun parse(
        reader: SourceReader,
        options: DecodeOptions,
        container: AudioContainer,
    ): io.github.yuroyami.audiokodecs.internal.ParsedStream {
        return when (container) {
            AudioContainer.WAV, AudioContainer.RF64 -> WaveReader.parse(reader, options)
            AudioContainer.WAVE64 -> Wave64Reader.parse(reader, options)
            AudioContainer.AIFF -> AiffReader.parse(reader, options)
            AudioContainer.AU -> AuReader.parse(reader, options)
            AudioContainer.CAF -> CafReader.parse(reader, options)
            else -> throw UnsupportedFormatException(
                container,
                "this is a ${container.displayName} file, which AudioKodecs cannot decode yet",
            )
        }
    }
}

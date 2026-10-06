package io.github.yuroyami.audiokodec.internal.container

import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioSink
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.EncodeTarget
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.PcmSource
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.fresh
import io.github.yuroyami.audiokodec.readChecked
import io.github.yuroyami.audiokodec.internal.codec.G711Codec
import io.github.yuroyami.audiokodec.internal.codec.ImaAdpcmWavCodec
import io.github.yuroyami.audiokodec.internal.codec.LinearPcmCodec
import io.github.yuroyami.audiokodec.internal.codec.MsAdpcmCodec
import io.github.yuroyami.audiokodec.internal.codec.PcmLayout
import io.github.yuroyami.audiokodec.internal.codec.SampleEncoder
import io.github.yuroyami.audiokodec.internal.flac.FlacDecoder
import io.github.yuroyami.audiokodec.internal.flac.FlacEncoder
import io.github.yuroyami.audiokodec.internal.io.SinkWriter
import io.github.yuroyami.audiokodec.internal.tag.Id3v2Writer
import io.github.yuroyami.audiokodec.internal.tag.TagWriter
import io.github.yuroyami.audiokodec.tag.TagBundle

/**
 * Writes the uncompressed containers.
 *
 * All three work the same way: a header whose length fields are not known
 * until the audio has been written, then the audio. When the sink can be
 * patched, the fields are written as zero and corrected at the end. When it
 * cannot, the frame count has to be known in advance, and the fields are
 * correct the first time.
 */
internal object ContainerWriter {

    /** The largest value a 32-bit size field can hold. */
    private const val MAX_32 = 0xFFFF_FFFFL

    fun write(
        pcm: PcmSource,
        target: EncodeTarget,
        sink: AudioSink,
        tags: TagBundle? = null,
    ) {
        validateChannelLayout(pcm.format, target)
        if (target.container == AudioContainer.AU && tags?.isEmpty == false) {
            throw UnsupportedFeatureException(
                "tags in AU",
                "AU has no structured tag format; write the audio without tags or choose " +
                    "WAV, AIFF or FLAC",
            )
        }
        if (target.container == AudioContainer.FLAC) {
            // FLAC has its own framing and its own idea of a block, so it does
            // not go through the shared encode loop below.
            FlacEncoder(
                format = pcm.format,
                bitsPerSample = target.bitDepth,
                maxLpcOrder = lpcOrderFor(target.blockAlign),
                maxPartitionOrder = partitionOrderFor(target.blockAlign),
                dither = target.dither.fresh(),
            ).encode(pcm, tags, sink)
            return
        }
        val encoder = encoderFor(pcm.format, target, target.dither.fresh())
        val writer = SinkWriter(sink)
        val declaredFrames = pcm.frameCount
        require(declaredFrames == null || declaredFrames >= 0) {
            "PcmSource.frameCount must not be negative, was $declaredFrames"
        }
        if (!writer.canPatch && declaredFrames == null) {
            throw AudioKodecException(
                "this sink cannot be patched, so the frame count has to be known before " +
                    "writing starts. Give the PcmSource a frameCount, or encode to a sink " +
                    "that supports patching.",
            )
        }
        when (target.container) {
            AudioContainer.WAV -> writeWave(pcm, target, encoder, writer, declaredFrames, tags)
            AudioContainer.AIFF -> writeAiff(pcm, target, encoder, writer, declaredFrames, tags)
            AudioContainer.AU -> writeAu(pcm, target, encoder, writer, declaredFrames)
            else -> throw AudioKodecException(
                "AudioKodec cannot write ${target.container.displayName} files",
            )
        }
    }

    /**
     * Refuses to write speaker metadata that would describe different
     * speakers from the source.
     *
     * WAV has a positional mask, but the mask also fixes interleaving to
     * ascending bit order. AIFF and AU carry only a channel count, and FLAC
     * fixes one layout for each supported count. Silently accepting anything
     * else would put the right samples through the wrong speakers.
     */
    private fun validateChannelLayout(format: AudioFormat, target: EncodeTarget) {
        val actual = format.channelLayout
        val canonical = ChannelLayout.canonical(format.channels)
        when (target.container) {
            AudioContainer.WAV -> {
                val linear = target.codec == AudioCodec.PCM_INTEGER ||
                    target.codec == AudioCodec.PCM_FLOAT
                val ascendingPositional = actual.isPositional &&
                    actual.channels.zipWithNext().all { (left, right) ->
                        left.ordinal < right.ordinal
                    }
                require(
                    actual == canonical || (linear && ascendingPositional),
                ) {
                    "WAV cannot preserve channel layout $actual with ${target.codec.displayName}; " +
                        "positional PCM/float layouts must follow WAV speaker-bit order, and " +
                        "compressed WAV uses the canonical layout"
                }
            }

            AudioContainer.AIFF, AudioContainer.AU -> require(actual == canonical) {
                "${target.container.displayName} stores only a channel count, so it cannot " +
                    "preserve non-canonical channel layout $actual"
            }

            AudioContainer.FLAC -> {
                val fixed = FlacDecoder.layoutFor(format.channels)
                require(actual == fixed) {
                    "FLAC fixes ${format.channels} channels to $fixed and cannot preserve $actual"
                }
            }

            else -> Unit
        }
    }

    /**
     * How far the FLAC encoder should look for a predictor, by effort level.
     *
     * The reference encoder's levels 0 to 2 use fixed predictors only, and the
     * higher ones raise the linear predictor order. These follow the same
     * shape, so a caller who knows what `-5` means gets what they expect.
     */
    private fun lpcOrderFor(effort: Int): Int = when (effort) {
        0, 1, 2 -> 0
        3 -> 6
        4, 5 -> 8
        6, 7 -> 8
        else -> 12
    }

    /** How finely the FLAC encoder should partition the residual, by effort. */
    private fun partitionOrderFor(effort: Int): Int = when (effort) {
        0 -> 3
        1, 2, 3 -> 3
        4, 5 -> 4
        6 -> 5
        else -> 6
    }

    private fun encoderFor(
        format: AudioFormat,
        target: EncodeTarget,
        dither: io.github.yuroyami.audiokodec.Dither,
    ): SampleEncoder {
        val channels = format.channels
        return when (target.codec) {
            AudioCodec.PCM_INTEGER -> LinearPcmCodec(
                channels,
                PcmLayout(
                    bitDepth = target.bitDepth,
                    littleEndian = target.container == AudioContainer.WAV,
                    // WAV writes 8-bit unsigned; AIFF and AU write everything
                    // signed. The one exception is not worth a flag on the
                    // target, because a caller cannot usefully change it.
                    signed = !(target.container == AudioContainer.WAV && target.bitDepth == 8),
                ),
                dither = dither,
            )

            AudioCodec.PCM_FLOAT -> LinearPcmCodec(
                channels,
                PcmLayout(
                    bitDepth = target.bitDepth,
                    littleEndian = target.container == AudioContainer.WAV,
                    signed = true,
                    isFloat = true,
                ),
            )

            AudioCodec.MU_LAW -> G711Codec(channels, aLaw = false)
            AudioCodec.A_LAW -> G711Codec(channels, aLaw = true)
            AudioCodec.IMA_ADPCM -> {
                val blockAlign = if (target.blockAlign == 0) {
                    automaticImaBlockAlign(channels)
                } else {
                    target.blockAlign
                }
                ImaAdpcmWavCodec(channels, blockAlign).also {
                    require(it.framesPerBlock <= 0xFFFF) {
                        "an IMA ADPCM block of $blockAlign bytes holds " +
                            "${it.framesPerBlock} frames, above WAV's 65535-frame limit"
                    }
                }
            }
            AudioCodec.MS_ADPCM -> MsAdpcmCodec(channels, target.blockAlign).also {
                require(it.framesPerBlock <= 0xFFFF) {
                    "a Microsoft ADPCM block of ${target.blockAlign} bytes holds " +
                        "${it.framesPerBlock} frames, above WAV's 65535-frame limit"
                }
            }
            else -> throw AudioKodecException(
                "AudioKodec cannot encode ${target.codec.displayName}",
            )
        }
    }

    /**
     * Picks an IMA block near 1024 bytes that is valid for [channels].
     *
     * The WAV layout adds samples in four-byte groups per channel, so a valid
     * block is a multiple of `4 * channels` and must contain at least one
     * group after the equally sized header.
     */
    private fun automaticImaBlockAlign(channels: Int): Int {
        val unit = 4L * channels
        val groupsIncludingHeader = maxOf(2L, 1024L / unit)
        val bytes = unit * groupsIncludingHeader
        require(bytes <= 0xFFFF) {
            "$channels channels need an IMA ADPCM block larger than WAV can represent"
        }
        return bytes.toInt()
    }

    /**
     * Runs the whole encode loop and returns the frames written.
     *
     * Codecs whose block holds one frame are fed in large chunks, and block
     * codecs one block at a time. The last block of a block codec is padded
     * with silence, which is why the frame count goes in the container.
     */
    private fun encodeAll(
        pcm: PcmSource,
        encoder: SampleEncoder,
        writer: SinkWriter,
    ): Long {
        val channels = encoder.channels
        val framesPerBlock = encoder.framesPerBlock
        val blocksPerChunk = if (framesPerBlock == 1) 4096 else 1
        val framesPerChunk = framesPerBlock * blocksPerChunk
        val inputEntries = framesPerChunk.toLong() * channels
        val outputBytes = encoder.blockBytes.toLong() * blocksPerChunk
        if (inputEntries > Int.MAX_VALUE || outputBytes > Int.MAX_VALUE) {
            throw AudioKodecException("the encoder's working block is too large for a Kotlin array")
        }
        val input = FloatArray(inputEntries.toInt())
        val output = ByteArray(outputBytes.toInt())
        var total = 0L

        while (true) {
            var filled = 0
            while (filled < framesPerChunk) {
                val got = pcm.readChecked(input, filled * channels, framesPerChunk - filled)
                if (got == 0) break
                filled += got
            }
            if (filled == 0) break
            if (framesPerBlock > 1 && filled < framesPerBlock) {
                // Pad the last block, and remember the true count so the
                // container can state it.
                input.fill(0f, filled * channels, framesPerChunk * channels)
                val written = encoder.encode(input, 0, maxOf(filled, minimumBlockFrames(encoder)), output, 0)
                writer.writeBytes(output, 0, written)
                total += filled
                break
            }
            val written = encoder.encode(input, 0, filled, output, 0)
            writer.writeBytes(output, 0, written)
            if (total > Long.MAX_VALUE - filled) {
                throw AudioKodecException("the encoded frame count overflows 64 bits")
            }
            total += filled
            if (filled < framesPerChunk) break
        }
        return total
    }

    /**
     * The fewest frames a block encoder will accept.
     *
     * Microsoft ADPCM keeps two samples in its block header, so a block of one
     * frame cannot be expressed. Padding up to the minimum writes silence,
     * which the container's frame count then excludes.
     */
    private fun minimumBlockFrames(encoder: SampleEncoder): Int =
        if (encoder is MsAdpcmCodec) 2 else 1

    // WAV

    private fun writeWave(
        pcm: PcmSource,
        target: EncodeTarget,
        encoder: SampleEncoder,
        writer: SinkWriter,
        declaredFrames: Long?,
        tags: TagBundle?,
    ) {
        val format = pcm.format
        val channels = format.channels
        require(channels <= 0xFFFF) {
            "WAV's channel-count field cannot represent $channels channels"
        }
        val linear = target.codec == AudioCodec.PCM_INTEGER ||
            target.codec == AudioCodec.PCM_FLOAT
        val extensible = linear && (
            channels > 2 ||
                format.channelLayout != ChannelLayout.canonical(channels)
            )
        val needsFact = target.codec != AudioCodec.PCM_INTEGER
        val blockAlign = encoder.blockBytes
        val framesPerBlock = encoder.framesPerBlock
        val actualTags = tags?.takeUnless { it.isEmpty }
        val info = actualTags?.let { TagWriter.buildRiffInfo(it) }
        // RIFF INFO is deliberately small and old-player friendly; ID3 keeps
        // the fields, Unicode, pictures and chapters INFO cannot represent.
        val id3 = actualTags?.let { Id3v2Writer.build(it, major = 4, padding = 0) }

        val fmtBody = buildWaveFormatChunk(format, target, encoder, extensible)
        val fmtChunkBytes = 8L + fmtBody.size + (fmtBody.size % 2)
        val factChunkBytes = if (needsFact) 12L else 0L
        val infoChunkBytes = info?.size?.toLong() ?: 0L
        val id3ChunkBytes = id3?.let { 8L + it.size + (it.size % 2) } ?: 0L
        val exactDataBytes = declaredFrames?.let {
            encodedBytesForFrames(it, framesPerBlock, blockAlign, "WAV data")
        }

        // Thirty-six bytes after 'WAVE' are always reserved for the RF64
        // sizes. When the file stays under 4 GiB they hold a JUNK chunk,
        // which every reader skips. When it does not, the same bytes become
        // the ds64 chunk. Reserving them unconditionally is what lets a
        // streamed file and a patched one come out byte for byte identical.
        val slotBytes = 36L
        val slotBodyBytes = 28

        // Everything after the size field: the form type, the reserved slot,
        // the format chunk, the optional fact chunk, and the data chunk with
        // its own header and padding. This is only knowable when the source
        // states its length.
        val exact = if (declaredFrames != null && exactDataBytes != null) {
            ExactWaveSizes(
                frames = declaredFrames,
                dataBytes = exactDataBytes,
                totalAfterSize = checkedSum(
                    "WAV file size",
                    4L,
                    slotBytes,
                    fmtChunkBytes,
                    factChunkBytes,
                    infoChunkBytes,
                    id3ChunkBytes,
                    8L,
                    exactDataBytes,
                    exactDataBytes % 2,
                ),
            )
        } else {
            null
        }
        // A file whose declared length already needs 64-bit sizes is written
        // as RF64 from the start. One whose length is not known yet is written
        // as RIFF and upgraded at the end if it turns out to need it.
        val rf64 = exact?.takeIf { it.totalAfterSize > MAX_32 || it.dataBytes > MAX_32 }

        writer.writeFourCc(if (rf64 != null) "RF64" else "RIFF")
        val riffSizeAt = writer.position
        writer.writeU32Le(if (rf64 != null) MAX_32 else (exact?.totalAfterSize ?: 0L))
        writer.writeFourCc("WAVE")

        val slotAt = writer.position
        if (rf64 != null) {
            writer.writeFourCc("ds64")
            writer.writeU32Le(slotBodyBytes.toLong())
            writer.writeI64Le(rf64.totalAfterSize)
            writer.writeI64Le(rf64.dataBytes)
            writer.writeI64Le(rf64.frames)
            writer.writeU32Le(0)
        } else {
            writer.writeFourCc("JUNK")
            writer.writeU32Le(slotBodyBytes.toLong())
            writer.writeBytes(ByteArray(slotBodyBytes))
        }

        writer.writeFourCc("fmt ")
        writer.writeU32Le(fmtBody.size.toLong())
        writer.writeBytes(fmtBody)
        if (fmtBody.size % 2 == 1) writer.writeU8(0)

        var factAt = -1L
        if (needsFact) {
            writer.writeFourCc("fact")
            writer.writeU32Le(4)
            factAt = writer.position
            writer.writeU32Le((declaredFrames ?: 0L).coerceAtMost(MAX_32))
        }

        info?.let { writer.writeBytes(it) }
        id3?.let {
            writer.writeFourCc("id3 ")
            writer.writeU32Le(it.size.toLong())
            writer.writeBytes(it)
            if (it.size % 2 == 1) writer.writeU8(0)
        }

        writer.writeFourCc("data")
        val dataSizeAt = writer.position
        writer.writeU32Le(
            when {
                rf64 != null -> MAX_32
                exact != null -> exact.dataBytes
                else -> 0L
            },
        )
        val dataStart = writer.position

        val frames = encodeAll(pcm, encoder, writer)
        val dataBytes = writer.position - dataStart
        if (dataBytes % 2 == 1L) writer.writeU8(0)

        checkPromisedFrames(declaredFrames, frames)
        if (!writer.canPatch) {
            return
        }

        val riffSize = writer.position - (riffSizeAt + 4)
        if (riffSize > MAX_32 || dataBytes > MAX_32) {
            patchFourCc(writer, 0, "RF64")
            writer.patchU32Le(riffSizeAt, MAX_32)
            patchFourCc(writer, slotAt, "ds64")
            writer.patchI64Le(slotAt + 8, riffSize)
            writer.patchI64Le(slotAt + 16, dataBytes)
            writer.patchI64Le(slotAt + 24, frames)
            writer.patchU32Le(dataSizeAt, MAX_32)
        } else {
            writer.patchU32Le(riffSizeAt, riffSize)
            writer.patchU32Le(dataSizeAt, dataBytes)
        }
        if (factAt >= 0) writer.patchU32Le(factAt, frames.coerceAtMost(MAX_32))
    }

    private fun buildWaveFormatChunk(
        format: AudioFormat,
        target: EncodeTarget,
        encoder: SampleEncoder,
        extensible: Boolean,
    ): ByteArray {
        val channels = format.channels
        val blockAlign = encoder.blockBytes
        val framesPerBlock = encoder.framesPerBlock
        require(blockAlign in 1..0xFFFF) {
            "WAV's block-alignment field cannot represent $blockAlign bytes"
        }
        require(framesPerBlock in 1..0xFFFF) {
            "WAV's samples-per-block field cannot represent $framesPerBlock frames"
        }
        val bitsPerSample = when (target.codec) {
            AudioCodec.IMA_ADPCM, AudioCodec.MS_ADPCM -> 4
            AudioCodec.MU_LAW, AudioCodec.A_LAW -> 8
            else -> target.bitDepth
        }
        val tag = when (target.codec) {
            AudioCodec.PCM_INTEGER -> 0x0001
            AudioCodec.MS_ADPCM -> 0x0002
            AudioCodec.PCM_FLOAT -> 0x0003
            AudioCodec.A_LAW -> 0x0006
            AudioCodec.MU_LAW -> 0x0007
            AudioCodec.IMA_ADPCM -> 0x0011
            else -> throw AudioKodecException("no WAVE format tag for ${target.codec.displayName}")
        }
        val bytesPerSecond = format.sampleRate.toLong() * blockAlign / framesPerBlock
        require(bytesPerSecond <= MAX_32) {
            "WAV's byte-rate field cannot represent $bytesPerSecond bytes per second"
        }

        val extra = when (target.codec) {
            AudioCodec.IMA_ADPCM -> {
                val body = ByteArray(2)
                writeU16Le(body, 0, framesPerBlock)
                body
            }
            AudioCodec.MS_ADPCM -> {
                val sets = MsAdpcmCodec.STANDARD_COEFFICIENTS
                val body = ByteArray(4 + sets.size * 4)
                writeU16Le(body, 0, framesPerBlock)
                writeU16Le(body, 2, sets.size)
                for (i in sets.indices) {
                    writeU16Le(body, 4 + i * 4, sets[i][0])
                    writeU16Le(body, 6 + i * 4, sets[i][1])
                }
                body
            }
            else -> ByteArray(0)
        }

        val hasCbSize = extensible || extra.isNotEmpty() || target.codec != AudioCodec.PCM_INTEGER
        val size = 16 + (if (hasCbSize) 2 else 0) + (if (extensible) 22 else extra.size)
        val out = ByteArray(size)
        writeU16Le(out, 0, if (extensible) 0xFFFE else tag)
        writeU16Le(out, 2, channels)
        writeU32Le(out, 4, format.sampleRate.toLong())
        writeU32Le(out, 8, bytesPerSecond)
        writeU16Le(out, 12, blockAlign)
        writeU16Le(out, 14, bitsPerSample)
        if (hasCbSize) writeU16Le(out, 16, if (extensible) 22 else extra.size)
        if (extensible) {
            writeU16Le(out, 18, bitsPerSample)
            writeU32Le(out, 20, waveMaskFor(format).toLong())
            writeU16Le(out, 24, tag)
            val suffix = byteArrayOf(
                0x00, 0x00, 0x00, 0x00,
                0x10.toByte(), 0x00, 0x80.toByte(), 0x00,
                0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71,
            )
            suffix.copyInto(out, 26)
        } else if (extra.isNotEmpty()) {
            extra.copyInto(out, 18)
        }
        return out
    }

    /**
     * The channel mask to write.
     *
     * A layout whose positions are all known becomes the mask that names them.
     * Anything else writes zero, which tells a reader to assume the default
     * assignment for that channel count rather than a wrong one.
     */
    private fun waveMaskFor(format: AudioFormat): Int = format.channelLayout.waveMask

    // AIFF

    private fun writeAiff(
        pcm: PcmSource,
        target: EncodeTarget,
        encoder: SampleEncoder,
        writer: SinkWriter,
        declaredFrames: Long?,
        tags: TagBundle?,
    ) {
        val format = pcm.format
        require(format.channels <= 0xFFFF) {
            "AIFF's channel-count field cannot represent ${format.channels} channels"
        }
        require(declaredFrames == null || declaredFrames <= MAX_32) {
            "AIFF's frame-count field cannot represent $declaredFrames frames"
        }
        val aifc = target.codec != AudioCodec.PCM_INTEGER
        val compression = when (target.codec) {
            AudioCodec.PCM_INTEGER -> null
            AudioCodec.PCM_FLOAT -> if (target.bitDepth == 32) "fl32" else "fl64"
            AudioCodec.MU_LAW -> "ulaw"
            AudioCodec.A_LAW -> "alaw"
            else -> throw AudioKodecException("AIFF cannot carry ${target.codec.displayName}")
        }
        val compressionName = when (compression) {
            "fl32" -> "IEEE 32-bit float"
            "fl64" -> "IEEE 64-bit float"
            "ulaw" -> "CCITT G.711 u-law"
            "alaw" -> "CCITT G.711 A-law"
            else -> ""
        }
        val sampleSize = when (target.codec) {
            AudioCodec.MU_LAW, AudioCodec.A_LAW -> 16
            else -> target.bitDepth
        }
        val actualTags = tags?.takeUnless { it.isEmpty }
        val textChunks = actualTags?.let { TagWriter.buildAiffTextChunks(it) }.orEmpty()
        val id3 = actualTags?.let { Id3v2Writer.build(it, major = 4, padding = 0) }
        var metadataBytes = 0L
        for (chunk in textChunks) {
            metadataBytes = checkedSum("AIFF metadata", metadataBytes, chunk.size.toLong())
        }
        if (id3 != null) {
            metadataBytes = checkedSum(
                "AIFF metadata",
                metadataBytes,
                8L,
                id3.size.toLong(),
                (id3.size % 2).toLong(),
            )
        }

        val exactDataBytes = declaredFrames?.let {
            encodedBytesForFrames(
                it,
                encoder.framesPerBlock,
                encoder.blockBytes,
                "AIFF sound data",
            )
        }

        val commBody = buildAiffCommon(
            format = format,
            frames = declaredFrames ?: 0L,
            sampleSize = sampleSize,
            compression = compression,
            compressionName = compressionName,
        )
        // Everything after the size field: the form type, the version chunk
        // when this is AIFF-C, the common chunk, and the sound chunk with its
        // two leading fields. Working it out now means a sink that cannot be
        // patched still gets a correct header.
        val totalAfterSize = exactDataBytes?.let {
            checkedSum(
                "AIFF file size",
                4L,
                if (aifc) 12L else 0L,
                8L,
                commBody.size.toLong(),
                (commBody.size % 2).toLong(),
                metadataBytes,
                8L,
                8L,
                it,
                it % 2,
            )
        }
        if (totalAfterSize != null && totalAfterSize > MAX_32) {
            throw AudioKodecException("an AIFF file cannot be larger than 4 GiB")
        }

        writer.writeFourCc("FORM")
        val formSizeAt = writer.position
        writer.writeU32Be(totalAfterSize ?: 0L)
        writer.writeFourCc(if (aifc) "AIFC" else "AIFF")

        if (aifc) {
            // The version chunk carries the date the AIFF-C specification was
            // published, which is the only value it has ever held.
            writer.writeFourCc("FVER")
            writer.writeU32Be(4)
            writer.writeU32Be(0xA2805140L)
        }

        writer.writeFourCc("COMM")
        writer.writeU32Be(commBody.size.toLong())
        val commAt = writer.position
        writer.writeBytes(commBody)
        if (commBody.size % 2 == 1) writer.writeU8(0)

        for (chunk in textChunks) writer.writeBytes(chunk)
        id3?.let {
            writer.writeFourCc("ID3 ")
            writer.writeU32Be(it.size.toLong())
            writer.writeBytes(it)
            if (it.size % 2 == 1) writer.writeU8(0)
        }

        writer.writeFourCc("SSND")
        val ssndSizeAt = writer.position
        writer.writeU32Be(exactDataBytes?.let { it + 8 } ?: 0L)
        writer.writeU32Be(0) // Offset.
        writer.writeU32Be(0) // Block size.
        val dataStart = writer.position

        val frames = encodeAll(pcm, encoder, writer)
        if (frames > MAX_32) {
            throw AudioKodecException("AIFF cannot represent more than $MAX_32 frames")
        }
        val dataBytes = writer.position - dataStart
        if (dataBytes % 2 == 1L) writer.writeU8(0)

        checkPromisedFrames(declaredFrames, frames)
        if (!writer.canPatch) {
            return
        }
        if (writer.position - (formSizeAt + 4) > MAX_32) {
            throw AudioKodecException("an AIFF file cannot be larger than 4 GiB")
        }
        writer.patchU32Be(formSizeAt, writer.position - (formSizeAt + 4))
        writer.patchU32Be(ssndSizeAt, dataBytes + 8)
        // Frames sit two bytes into the common chunk.
        writer.patchU32Be(commAt + 2, frames)
    }

    private fun buildAiffCommon(
        format: AudioFormat,
        frames: Long,
        sampleSize: Int,
        compression: String?,
        compressionName: String,
    ): ByteArray {
        val nameBytes = compressionName.encodeToByteArray()
        val compressionBytes = if (compression == null) {
            0
        } else {
            // A pascal string is a length byte then the text, padded to an
            // even total.
            val raw = 4 + 1 + nameBytes.size
            raw + (raw % 2)
        }
        val out = ByteArray(18 + compressionBytes)
        writeU16Be(out, 0, format.channels)
        writeU32Be(out, 2, frames)
        writeU16Be(out, 6, sampleSize)
        writeExtendedFloat(out, 8, format.sampleRate.toDouble())
        if (compression != null) {
            for (i in 0 until 4) out[18 + i] = compression[i].code.toByte()
            out[22] = nameBytes.size.toByte()
            nameBytes.copyInto(out, 23)
        }
        return out
    }

    // AU

    private fun writeAu(
        pcm: PcmSource,
        target: EncodeTarget,
        encoder: SampleEncoder,
        writer: SinkWriter,
        declaredFrames: Long?,
    ) {
        val format = pcm.format
        val encoding = when (target.codec) {
            AudioCodec.MU_LAW -> 1
            AudioCodec.A_LAW -> 27
            AudioCodec.PCM_INTEGER -> when (target.bitDepth) {
                8 -> 2
                16 -> 3
                24 -> 4
                else -> 5
            }
            AudioCodec.PCM_FLOAT -> if (target.bitDepth == 32) 6 else 7
            else -> throw AudioKodecException("AU cannot carry ${target.codec.displayName}")
        }
        val exactDataBytes = declaredFrames?.let {
            encodedBytesForFrames(it, encoder.framesPerBlock, encoder.blockBytes, "AU data")
        }

        writer.writeFourCc(".snd")
        writer.writeU32Be(24)
        val sizeAt = writer.position
        writer.writeU32Be(exactDataBytes?.coerceAtMost(MAX_32) ?: MAX_32)
        writer.writeU32Be(encoding.toLong())
        writer.writeU32Be(format.sampleRate.toLong())
        writer.writeU32Be(format.channels.toLong())
        val dataStart = writer.position

        val frames = encodeAll(pcm, encoder, writer)
        val dataBytes = writer.position - dataStart

        checkPromisedFrames(declaredFrames, frames)
        if (!writer.canPatch) {
            return
        }
        // A length past 4 GiB stays as the "not known" marker, which is what
        // the format offers and what every reader handles.
        writer.patchU32Be(sizeAt, if (dataBytes > MAX_32) MAX_32 else dataBytes)
    }

    /** The sizes a WAV header can state up front, once the length is known. */
    private class ExactWaveSizes(
        val frames: Long,
        val dataBytes: Long,
        val totalAfterSize: Long,
    )

    private fun checkPromisedFrames(declared: Long?, actual: Long) {
        if (declared != null && declared != actual) {
            throw AudioKodecException(
                "the source promised $declared frames and produced $actual; " +
                    "PcmSource.frameCount must describe the complete stream",
            )
        }
    }

    private fun encodedBytesForFrames(
        frames: Long,
        framesPerBlock: Int,
        blockBytes: Int,
        what: String,
    ): Long {
        val blocks = if (frames == 0L) 0L else 1L + (frames - 1L) / framesPerBlock
        if (blocks > Long.MAX_VALUE / blockBytes) {
            throw AudioKodecException("$what size overflows 64 bits")
        }
        return blocks * blockBytes
    }

    private fun checkedSum(what: String, vararg values: Long): Long {
        var total = 0L
        for (value in values) {
            if (value < 0 || total > Long.MAX_VALUE - value) {
                throw AudioKodecException("$what overflows 64 bits")
            }
            total += value
        }
        return total
    }

    private fun patchFourCc(writer: SinkWriter, at: Long, id: String) {
        val bytes = ByteArray(4)
        for (i in 0 until 4) bytes[i] = id[i].code.toByte()
        writer.patchBytes(at, bytes)
    }

    private fun writeU16Le(out: ByteArray, at: Int, value: Int) {
        out[at] = value.toByte()
        out[at + 1] = (value ushr 8).toByte()
    }

    private fun writeU32Le(out: ByteArray, at: Int, value: Long) {
        out[at] = value.toByte()
        out[at + 1] = (value ushr 8).toByte()
        out[at + 2] = (value ushr 16).toByte()
        out[at + 3] = (value ushr 24).toByte()
    }

    private fun writeU16Be(out: ByteArray, at: Int, value: Int) {
        out[at] = (value ushr 8).toByte()
        out[at + 1] = value.toByte()
    }

    private fun writeU32Be(out: ByteArray, at: Int, value: Long) {
        out[at] = (value ushr 24).toByte()
        out[at + 1] = (value ushr 16).toByte()
        out[at + 2] = (value ushr 8).toByte()
        out[at + 3] = value.toByte()
    }

    /**
     * Writes an 80-bit IEEE extended float, the type AIFF stores its sample
     * rate in.
     *
     * Sample rates are whole numbers in every file anyone writes, so this
     * handles the positive finite case and rejects the rest rather than
     * carrying code no caller can reach.
     */
    private fun writeExtendedFloat(out: ByteArray, at: Int, value: Double) {
        require(value > 0 && value.isFinite()) { "a sample rate must be positive and finite" }
        // Split the value into a fraction in 0.5 .. 1.0 and a power of two,
        // which is the form the 80-bit layout stores.
        var exponent = 0
        var fraction = value
        while (fraction >= 1.0) {
            fraction /= 2.0
            exponent++
        }
        while (fraction < 0.5) {
            fraction *= 2.0
            exponent--
        }
        // The mantissa is 64 bits with its leading one written out rather than
        // implied, so it does not fit in a signed Long. It is built as two
        // 32-bit halves for that reason.
        val scaled = fraction * 4_294_967_296.0
        val high = scaled.toLong()
        val low = ((scaled - high) * 4_294_967_296.0).toLong()
        writeU16Be(out, at, exponent + 16382)
        writeU32Be(out, at + 2, high)
        writeU32Be(out, at + 6, low)
    }
}

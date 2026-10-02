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
import io.github.yuroyami.audiokodecs.internal.codec.ImaAdpcmWavCodec
import io.github.yuroyami.audiokodecs.internal.codec.LinearPcmCodec
import io.github.yuroyami.audiokodecs.internal.codec.MsAdpcmCodec
import io.github.yuroyami.audiokodecs.internal.codec.PcmLayout
import io.github.yuroyami.audiokodecs.internal.codec.SampleCodec
import io.github.yuroyami.audiokodecs.internal.io.SourceReader

/**
 * Reads RIFF WAVE, and the two ways the format was extended past its limits.
 *
 * WAVE_FORMAT_EXTENSIBLE moves the real format tag into a GUID and adds a
 * channel mask, which is how a file says which speaker each channel drives.
 * RF64 replaces the 32-bit sizes with 64-bit ones held in a `ds64` chunk,
 * which is how a file gets past 4 GiB. Both are read here, because to a
 * caller they are still just WAV files.
 */
internal object WaveReader {

    private const val FORMAT_PCM = 0x0001
    private const val FORMAT_MS_ADPCM = 0x0002
    private const val FORMAT_IEEE_FLOAT = 0x0003
    private const val FORMAT_ALAW = 0x0006
    private const val FORMAT_MULAW = 0x0007
    private const val FORMAT_IMA_ADPCM = 0x0011
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    /**
     * The 14 bytes that follow the format tag in an extensible GUID.
     *
     * Every Microsoft-registered subformat GUID is the tag in the first two
     * bytes and then this. A GUID that ends differently is somebody else's
     * codec, and this reader says so by name rather than guessing.
     */
    private val KSDATAFORMAT_SUFFIX = byteArrayOf(
        0x00, 0x00, 0x00, 0x00,
        0x10.toByte(), 0x00, 0x80.toByte(), 0x00,
        0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71,
    )

    fun parse(reader: SourceReader, options: DecodeOptions): ParsedStream {
        reader.seek(0)
        val riffId = reader.readFourCc()
        val isRf64 = when (riffId) {
            "RIFF" -> false
            "RF64", "BW64" -> true
            else -> throw CorruptStreamException("expected 'RIFF' or 'RF64' but found '$riffId'", 0)
        }
        val declaredRiffSize = reader.readU32Le()
        reader.expectFourCc("WAVE", "RIFF form type")

        var fmt: WaveFormat? = null
        var dataOffset = -1L
        var dataBytes = -1L
        var factFrames: Long? = null
        var ds64: Ds64? = null
        var chunkIndex = 0

        val sourceSize = reader.size
        var declaredRiffEnd = if (isRf64 || declaredRiffSize == 0xFFFF_FFFFL) {
            -1L
        } else {
            declaredRiffSize + 8L
        }
        var riffEnd = if (declaredRiffEnd < 0) {
            sourceSize
        } else {
            // The size field counts everything after it, so the form ends
            // eight bytes past where it starts counting. A file whose field
            // overruns its own length is common enough that the smaller of
            // the two wins.
            if (sourceSize in 1 until declaredRiffEnd) sourceSize else declaredRiffEnd
        }

        while (true) {
            // Once the format and the payload are found, the rest of the form
            // is only read for what it adds, such as a `fact` chunk written
            // after the audio. Damage out there ends the walk rather than
            // costing a file whose audio was already found.
            val audioFound = dataOffset >= 0 && fmt != null
            try {
                // A chunk header is eight bytes. Stopping when exactly eight are
                // left would skip a final zero-length chunk, and an empty 'data'
                // chunk is exactly that.
                if (riffEnd in 1 until reader.position + 8) break
                if (sourceSize in 1 until reader.position + 8) break
                val chunkId = try {
                    reader.readFourCc()
                } catch (_: TruncatedStreamException) {
                    break
                }
                val chunkStart = reader.position - 4
                var chunkSize = try {
                    reader.readU32Le()
                } catch (_: TruncatedStreamException) {
                    break
                }
                // RF64 marks the oversized chunks with -1 and puts the real size
                // in ds64, which by specification comes before them.
                if (isRf64 && chunkSize == 0xFFFF_FFFFL) {
                    val table = ds64 ?: throw CorruptStreamException(
                        "RF64 chunk '$chunkId' has a placeholder size but no 'ds64' came before it",
                        chunkStart,
                    )
                    chunkSize = if (chunkId == "data") {
                        table.dataSize
                    } else {
                        table.take(chunkId) ?: throw CorruptStreamException(
                            "RF64 chunk '$chunkId' has no matching entry in the ds64 table",
                            chunkStart,
                        )
                    }
                }
                val bodyStart = reader.position
                if (isRf64 && chunkIndex == 0 && chunkId != "ds64") {
                    throw CorruptStreamException(
                        "an RF64 or BW64 file must put its 'ds64' chunk first",
                        chunkStart,
                    )
                }

                // Validate the complete chunk before parsing anything from its
                // body. Physical EOF is left for the decoder so tolerant mode can
                // salvage a truncated payload, but no chunk may escape the form
                // boundary stated by the container itself.
                val unknownDataSize =
                    !isRf64 && chunkId == "data" && chunkSize == 0xFFFF_FFFFL
                if (!unknownDataSize && chunkSize > Long.MAX_VALUE - bodyStart) {
                    throw CorruptStreamException("chunk '$chunkId' size overflows 64 bits", chunkStart)
                }
                val bodyEnd = if (unknownDataSize) bodyStart else bodyStart + chunkSize
                var next = bodyEnd
                if (!unknownDataSize && chunkSize % 2 == 1L) {
                    if (next == Long.MAX_VALUE) {
                        throw CorruptStreamException(
                            "chunk '$chunkId' padding overflows 64 bits",
                            chunkStart,
                        )
                    }
                    next++
                }
                if (next <= chunkStart) {
                    throw CorruptStreamException("chunk '$chunkId' does not advance", chunkStart)
                }
                if (!unknownDataSize && declaredRiffEnd >= 0 && next > declaredRiffEnd) {
                    throw CorruptStreamException(
                        "RIFF chunk '$chunkId' extends past the end of its enclosing form",
                        chunkStart,
                    )
                }

                when (chunkId) {
                    "ds64" -> {
                        if (!isRf64) {
                            throw CorruptStreamException(
                                "a RIFF WAVE file contains an RF64 'ds64' chunk",
                                chunkStart,
                            )
                        }
                        if (ds64 != null) {
                            throw CorruptStreamException(
                                "an RF64 file may contain only one 'ds64' chunk",
                                chunkStart,
                            )
                        }
                        ds64 = readDs64(reader, chunkSize, options, chunkStart)
                        declaredRiffEnd = ds64.riffSize + 8L
                        if (next > declaredRiffEnd) {
                            throw CorruptStreamException(
                                "the RF64 ds64 chunk extends past its declared RIFF size",
                                chunkStart,
                            )
                        }
                        riffEnd = if (sourceSize in 1 until declaredRiffEnd) {
                            sourceSize
                        } else {
                            declaredRiffEnd
                        }
                    }
                    "fmt " -> {
                        if (fmt != null) {
                            throw CorruptStreamException(
                                "a WAV file may contain only one 'fmt ' chunk",
                                chunkStart,
                            )
                        }
                        if (chunkSize > Int.MAX_VALUE) {
                            throw CorruptStreamException(
                                "a WAV format chunk cannot be $chunkSize bytes", chunkStart,
                            )
                        }
                        fmt = readFormat(reader, chunkSize.toInt(), options)
                    }
                    // Only the first payload is the audio.
                    "data" -> if (dataOffset < 0) {
                        dataOffset = bodyStart
                        dataBytes = chunkSize
                    }
                    "fact" -> if (chunkSize >= 4 && factFrames == null) factFrames = reader.readU32Le()
                }

                if (chunkId != "data" && reader.position > bodyEnd) {
                    throw CorruptStreamException(
                        "the '$chunkId' parser read past its declared chunk body",
                        chunkStart,
                    )
                }
                reader.seek(next)
                chunkIndex++
                if (sourceSize in 1..next) break
                // A payload whose length was never written runs to the end, so
                // there is no chunk after it to find.
                if (unknownDataSize) break
            } catch (damage: CorruptStreamException) {
                if (!audioFound) throw damage
                break
            }
        }

        if (isRf64 && ds64 == null) {
            throw CorruptStreamException("the RF64 file has no 'ds64' chunk", 12)
        }
        val format = fmt ?: throw CorruptStreamException("the file has no 'fmt ' chunk", 0)
        if (dataOffset < 0) throw CorruptStreamException("the file has no 'data' chunk", 0)

        val available = if (sourceSize >= 0) (sourceSize - dataOffset).coerceAtLeast(0L) else -1L
        // A stated size is kept even when the file is shorter than it claims.
        // Clamping here would turn a truncated file into a short but valid
        // one, and the whole point of the strict mode is to say so. The one
        // exception is the placeholder a program writing to a pipe leaves
        // behind, which means the length was never known.
        val payload = when {
            dataBytes < 0 -> available
            dataBytes == 0xFFFF_FFFFL -> available
            else -> dataBytes
        }

        val declaredFrames = ds64?.sampleCount
            ?: factFrames
                ?.takeIf { format.codec != AudioCodec.PCM_INTEGER && format.codec != AudioCodec.PCM_FLOAT }

        return ParsedStream(
            container = if (isRf64) AudioContainer.RF64 else AudioContainer.WAV,
            codec = format.codec,
            sampleCodec = format.sampleCodec,
            format = format.audioFormat,
            dataOffset = dataOffset,
            dataBytes = payload,
            declaredFrames = declaredFrames,
            bitDepth = format.storedBitDepth,
        )
    }

    private fun readDs64(
        reader: SourceReader,
        chunkSize: Long,
        options: DecodeOptions,
        at: Long,
    ): Ds64 {
        if (chunkSize < 28) {
            throw CorruptStreamException("an RF64 ds64 chunk is at least 28 bytes", at)
        }
        val riffSize = reader.readI64Le()
        val dataSize = reader.readI64Le()
        val sampleCount = reader.readI64Le()
        val entryCount = reader.readU32Le()
        val availableEntries = (chunkSize - 28) / 12
        if (entryCount > availableEntries || entryCount > Int.MAX_VALUE) {
            throw CorruptStreamException(
                "the RF64 ds64 table declares $entryCount entries in $chunkSize bytes",
                at,
            )
        }
        options.checkAllocation(entryCount * 24, "the RF64 ds64 size table")
        val entries = ArrayList<Ds64Entry>(entryCount.toInt())
        repeat(entryCount.toInt()) {
            val id = reader.readFourCc()
            val size = reader.readI64Le()
            if (size < 0) {
                throw CorruptStreamException(
                    "the RF64 ds64 size for '$id' exceeds the signed 64-bit range",
                    at,
                )
            }
            entries.add(Ds64Entry(id, size))
        }
        if (riffSize < 4 || riffSize > Long.MAX_VALUE - 8L || dataSize < 0 || sampleCount < 0) {
            throw CorruptStreamException("an RF64 ds64 size exceeds the signed 64-bit range", at)
        }
        return Ds64(
            riffSize = riffSize,
            dataSize = dataSize,
            sampleCount = if (sampleCount == 0L) null else sampleCount,
            entries = entries,
        )
    }

    private fun readFormat(reader: SourceReader, size: Int, options: DecodeOptions): WaveFormat {
        if (size < 16) {
            throw CorruptStreamException("a 'fmt ' chunk is at least 16 bytes, this one is $size")
        }
        val start = reader.position
        var formatTag = reader.readU16Le()
        val channels = reader.readU16Le()
        val sampleRate = reader.readI32Le()
        reader.readU32Le() // Average bytes per second, which is derived and not trusted.
        val blockAlign = reader.readU16Le()
        val containerBits = reader.readU16Le()
        var validBits = containerBits

        options.checkStreamShape(sampleRate, channels, start)

        var channelMask = 0
        val extensible = formatTag == FORMAT_EXTENSIBLE
        val extraStart = start + 18
        var extraSize = size - 16
        if (extraSize >= 2) {
            val declaredExtra = reader.readU16Le()
            val availableExtra = extraSize - 2
            if (declaredExtra > availableExtra) {
                throw CorruptStreamException(
                    "the WAV format header declares $declaredExtra extra bytes, " +
                        "but its chunk contains only $availableExtra",
                    start,
                )
            }
            extraSize = declaredExtra
        } else {
            extraSize = 0
        }

        if (extensible) {
            if (extraSize < 22) {
                throw CorruptStreamException(
                    "WAVE_FORMAT_EXTENSIBLE needs 22 bytes of extra format data, found $extraSize",
                    start,
                )
            }
            val declaredValidBits = reader.readU16Le()
            validBits = if (declaredValidBits == 0) containerBits else declaredValidBits
            if (validBits !in 1..containerBits) {
                throw CorruptStreamException(
                    "WAVE valid bits $validBits do not fit in a $containerBits-bit sample",
                    start,
                )
            }
            channelMask = reader.readI32Le()
            validateChannelMask(channelMask, channels, start)
            val guidTag = reader.readU16Le()
            val guidRest = reader.readBytes(14)
            if (!guidRest.contentEquals(KSDATAFORMAT_SUFFIX)) {
                throw UnsupportedFeatureException(
                    "a WAVE subformat GUID outside the Microsoft range",
                    "this file uses a subformat GUID that is not one of the standard ones",
                )
            }
            formatTag = guidTag
        }

        // The extra format bytes carry the ADPCM parameters. An extensible
        // header spends all of its extra bytes on the GUID, so there are none
        // left to read.
        val extra = if (extraSize > 0 && !extensible) {
            reader.seek(extraStart)
            reader.readBytes(extraSize)
        } else {
            ByteArray(0)
        }

        val layout = if (channelMask != 0) {
            ChannelLayout.fromWaveMask(channelMask, channels)
        } else {
            ChannelLayout.canonical(channels)
        }
        val audioFormat = AudioFormat(sampleRate, layout)

        val result = when (formatTag) {
            FORMAT_PCM -> {
                requireDepth(containerBits, intArrayOf(8, 16, 24, 32), "PCM storage", start)
                WaveFormat(
                    codec = AudioCodec.PCM_INTEGER,
                    audioFormat = audioFormat,
                    sampleCodec = LinearPcmCodec(
                        channels,
                        PcmLayout(
                            bitDepth = validBits,
                            littleEndian = true,
                            // WAV stores 8-bit samples unsigned and every
                            // wider width signed. That inconsistency is in
                            // the format, not in this reader.
                            signed = containerBits != 8,
                            storageBytes = containerBits / 8,
                            highAligned = validBits != containerBits,
                        ),
                    ),
                    storedBitDepth = validBits,
                )
            }

            FORMAT_IEEE_FLOAT -> {
                requireDepth(containerBits, intArrayOf(32, 64), "IEEE float", start)
                if (validBits != containerBits) {
                    throw CorruptStreamException(
                        "WAVE float valid bits must equal its $containerBits-bit storage width",
                        start,
                    )
                }
                WaveFormat(
                    codec = AudioCodec.PCM_FLOAT,
                    audioFormat = audioFormat,
                    sampleCodec = LinearPcmCodec(
                        channels,
                        PcmLayout(containerBits, littleEndian = true, signed = true, isFloat = true),
                    ),
                    storedBitDepth = null,
                )
            }

            FORMAT_ALAW, FORMAT_MULAW -> {
                requireDepth(containerBits, intArrayOf(8), "G.711", start)
                WaveFormat(
                    codec = if (formatTag == FORMAT_ALAW) AudioCodec.A_LAW else AudioCodec.MU_LAW,
                    audioFormat = audioFormat,
                    sampleCodec = G711Codec(channels, aLaw = formatTag == FORMAT_ALAW),
                    storedBitDepth = null,
                )
            }

            FORMAT_IMA_ADPCM -> {
                requireDepth(containerBits, intArrayOf(4), "IMA ADPCM", start)
                val block = if (blockAlign > 0) blockAlign else 4 * channels
                val codec = ImaAdpcmWavCodec(channels, block)
                if (extra.size >= 2 && readU16Le(extra, 0) != codec.framesPerBlock) {
                    throw CorruptStreamException(
                        "IMA ADPCM samples-per-block disagrees with its block alignment",
                        start,
                    )
                }
                WaveFormat(
                    codec = AudioCodec.IMA_ADPCM,
                    audioFormat = audioFormat,
                    sampleCodec = codec,
                    storedBitDepth = null,
                )
            }

            FORMAT_MS_ADPCM -> {
                requireDepth(containerBits, intArrayOf(4), "Microsoft ADPCM", start)
                val block = if (blockAlign > 0) blockAlign else 7 * channels + 1
                val coefficients = readAdpcmCoefficients(extra, start)
                val codec = MsAdpcmCodec(channels, block, coefficients)
                if (extra.size >= 2 && readU16Le(extra, 0) != codec.framesPerBlock) {
                    throw CorruptStreamException(
                        "Microsoft ADPCM samples-per-block disagrees with its block alignment",
                        start,
                    )
                }
                WaveFormat(
                    codec = AudioCodec.MS_ADPCM,
                    audioFormat = audioFormat,
                    sampleCodec = codec,
                    storedBitDepth = null,
                )
            }

            else -> throw UnsupportedFeatureException(
                "WAVE format tag 0x${formatTag.toString(16).uppercase()}",
                "this WAV file holds format tag 0x${formatTag.toString(16).uppercase()}, " +
                    "which AudioKodecs cannot decode",
            )
        }
        if (blockAlign != result.sampleCodec.blockBytes) {
            throw CorruptStreamException(
                "WAV block alignment $blockAlign disagrees with " +
                    "${result.sampleCodec.blockBytes} bytes per codec block",
                start,
            )
        }
        return result
    }

    private fun readU16Le(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    /**
     * Reads the coefficient table a Microsoft ADPCM file may carry.
     *
     * The extra format bytes hold the samples per block, then a count, then
     * that many pairs of 16-bit coefficients. Old files occasionally omit the
     * table altogether and implicitly use the standard seven pairs. Once a
     * file declares a table, however, accepting a truncated one as the
     * standard table would decode it with coefficients it never named.
     */
    private fun readAdpcmCoefficients(extra: ByteArray, at: Long): Array<IntArray> {
        if (extra.size < 4) return MsAdpcmCodec.STANDARD_COEFFICIENTS
        val count = (extra[2].toInt() and 0xFF) or ((extra[3].toInt() and 0xFF) shl 8)
        if (count == 0) {
            throw CorruptStreamException(
                "Microsoft ADPCM declares an empty coefficient table",
                at,
            )
        }
        val required = 4L + count.toLong() * 4
        if (required > extra.size) {
            throw CorruptStreamException(
                "Microsoft ADPCM declares $count coefficient pairs, but only " +
                    "${(extra.size - 4).coerceAtLeast(0) / 4} fit in its format header",
                at,
            )
        }
        return Array(count) { i ->
            val at = 4 + i * 4
            val first = ((extra[at].toInt() and 0xFF) or ((extra[at + 1].toInt() and 0xFF) shl 8))
                .toShort().toInt()
            val second = ((extra[at + 2].toInt() and 0xFF) or ((extra[at + 3].toInt() and 0xFF) shl 8))
                .toShort().toInt()
            intArrayOf(first, second)
        }
    }

    private fun requireDepth(bits: Int, allowed: IntArray, what: String, at: Long) {
        if (allowed.none { it == bits }) {
            throw CorruptStreamException(
                "$what in WAV is ${allowed.joinToString(", ")} bits, this file says $bits", at,
            )
        }
    }

    private fun validateChannelMask(mask: Int, channels: Int, at: Long) {
        if (mask == 0) return
        if (mask and KNOWN_SPEAKER_MASK.inv() != 0) {
            throw CorruptStreamException(
                "WAVE channel mask 0x${mask.toUInt().toString(16)} sets reserved speaker bits",
                at,
            )
        }
        val named = mask.countOneBits()
        if (named != channels) {
            throw CorruptStreamException(
                "WAVE channel mask names $named speakers for $channels channels",
                at,
            )
        }
    }

    private class Ds64(
        val riffSize: Long,
        val dataSize: Long,
        val sampleCount: Long?,
        private val entries: MutableList<Ds64Entry>,
    ) {
        fun take(id: String): Long? {
            val index = entries.indexOfFirst { it.id == id }
            if (index < 0) return null
            return entries.removeAt(index).size
        }
    }

    private class Ds64Entry(val id: String, val size: Long)

    private class WaveFormat(
        val codec: AudioCodec,
        val audioFormat: AudioFormat,
        val sampleCodec: SampleCodec,
        val storedBitDepth: Int?,
    )

    private const val KNOWN_SPEAKER_MASK = 0x0003_FFFF
}

package io.github.yuroyami.audiokodec.internal.container

import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.internal.ParsedStream
import io.github.yuroyami.audiokodec.internal.codec.G711Codec
import io.github.yuroyami.audiokodec.internal.codec.LinearPcmCodec
import io.github.yuroyami.audiokodec.internal.codec.PcmLayout
import io.github.yuroyami.audiokodec.internal.codec.SampleCodec
import io.github.yuroyami.audiokodec.internal.io.SourceReader

/**
 * Reads Sony Wave64.
 *
 * It is RIFF WAVE with two changes: every chunk is named by a sixteen-byte
 * GUID instead of a four-character code, and every size is 64-bit and counts
 * the header as well as the body. Chunks are padded to eight bytes rather than
 * two. The format header inside is the same structure WAV uses.
 */
internal object Wave64Reader {

    /** The 12 bytes every Wave64 GUID except the outer one ends with. */
    private val WAVE_SUFFIX = byteArrayOf(
        0xF3.toByte(), 0xAC.toByte(), 0xD3.toByte(), 0x11,
        0x8C.toByte(), 0xD1.toByte(), 0x00, 0xC0.toByte(),
        0x4F, 0x8E.toByte(), 0xDB.toByte(), 0x8A.toByte(),
    )

    /** The outer chunk is the one GUID that does not follow that pattern. */
    private val RIFF_GUID = byteArrayOf(
        0x72, 0x69, 0x66, 0x66,
        0x2E, 0x91.toByte(), 0xCF.toByte(), 0x11,
        0xA5.toByte(), 0xD6.toByte(), 0x28, 0xDB.toByte(),
        0x04, 0xC1.toByte(), 0x00, 0x00,
    )

    private val KSDATAFORMAT_SUFFIX = byteArrayOf(
        0x00, 0x00, 0x00, 0x00,
        0x10.toByte(), 0x00, 0x80.toByte(), 0x00,
        0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71,
    )

    private const val FORMAT_PCM = 0x0001
    private const val FORMAT_IEEE_FLOAT = 0x0003
    private const val FORMAT_ALAW = 0x0006
    private const val FORMAT_MULAW = 0x0007
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    fun parse(reader: SourceReader, options: DecodeOptions): ParsedStream {
        reader.seek(0)
        val riff = reader.readBytes(16)
        if (!riff.contentEquals(RIFF_GUID)) {
            throw CorruptStreamException("this is not a Wave64 file", 0)
        }
        val declaredSize = reader.readI64Le()
        if (declaredSize < 40) {
            throw CorruptStreamException(
                "a Wave64 file is at least 40 bytes, this one declares $declaredSize",
                16,
            )
        }
        if (reader.size >= 0 && declaredSize > reader.size) {
            throw TruncatedStreamException(
                "the Wave64 header declares $declaredSize bytes but only ${reader.size} exist",
                16,
            )
        }
        val waveGuid = reader.readBytes(16)
        if (!isWave64Guid(waveGuid, "wave")) {
            throw CorruptStreamException("the Wave64 form type is not 'wave'", 24)
        }

        var parsed: Wave64Format? = null
        var dataOffset = -1L
        var dataBytes = -1L
        val sourceSize = reader.size
        val formEnd = if (sourceSize >= 0) minOf(sourceSize, declaredSize) else declaredSize

        while (true) {
            val remaining = formEnd - reader.position
            if (remaining == 0L) break
            if (remaining < 24L) {
                throw CorruptStreamException(
                    "the Wave64 form ends with $remaining bytes, too few for a chunk header",
                    reader.position,
                )
            }
            val guid = try {
                reader.readBytes(16)
            } catch (_: TruncatedStreamException) {
                break
            }
            val chunkStart = reader.position - 16
            val chunkSize = try {
                reader.readI64Le()
            } catch (_: TruncatedStreamException) {
                break
            }
            if (chunkSize < 24) {
                throw CorruptStreamException(
                    "a Wave64 chunk counts its own 24-byte header, so $chunkSize is impossible",
                    chunkStart,
                )
            }
            val bodyStart = reader.position
            val bodySize = chunkSize - 24

            // Chunks are padded up to a multiple of eight bytes.
            if (chunkSize > Long.MAX_VALUE - chunkStart) {
                throw CorruptStreamException("a Wave64 chunk size overflows 64 bits", chunkStart)
            }
            var next = chunkStart + chunkSize
            val pad = next % 8
            if (pad != 0L) {
                val padding = 8 - pad
                if (next > Long.MAX_VALUE - padding) {
                    throw CorruptStreamException(
                        "a Wave64 chunk's padding overflows 64 bits",
                        chunkStart,
                    )
                }
                next += padding
            }
            if (next <= chunkStart) {
                throw CorruptStreamException("a Wave64 chunk does not advance", chunkStart)
            }
            if (next > formEnd) {
                throw CorruptStreamException(
                    "a Wave64 chunk extends past the end of its enclosing form",
                    chunkStart,
                )
            }

            when {
                isWave64Guid(guid, "fmt ") -> {
                    if (parsed != null) {
                        throw CorruptStreamException(
                            "the Wave64 file contains more than one format chunk",
                            chunkStart,
                        )
                    }
                    parsed = readFormat(reader, bodySize, options)
                }
                isWave64Guid(guid, "data") -> {
                    if (dataOffset >= 0) {
                        throw CorruptStreamException(
                            "the Wave64 file contains more than one data chunk",
                            chunkStart,
                        )
                    }
                    dataOffset = bodyStart
                    dataBytes = bodySize
                }
            }

            reader.seek(next)
            if (next == formEnd) break
            if (dataOffset >= 0 && parsed != null) break
        }

        val format = parsed ?: throw CorruptStreamException("the file has no format chunk", 0)
        if (dataOffset < 0) throw CorruptStreamException("the file has no data chunk", 0)

        // As in WaveReader, a stated size is kept even when the file falls
        // short of it, so that truncation is reported rather than absorbed.
        val payload = if (dataBytes < 0) {
            if (sourceSize >= 0) (sourceSize - dataOffset).coerceAtLeast(0L) else -1L
        } else {
            dataBytes
        }

        return ParsedStream(
            container = AudioContainer.WAVE64,
            codec = format.codec,
            sampleCodec = format.sampleCodec,
            format = format.audioFormat,
            dataOffset = dataOffset,
            dataBytes = payload,
            declaredFrames = null,
            bitDepth = format.storedBitDepth,
        )
    }

    /** True when [guid] is the Wave64 identifier for [fourCc]. */
    internal fun isWave64Guid(guid: ByteArray, fourCc: String): Boolean {
        if (guid.size < 16) return false
        for (i in 0 until 4) {
            if (guid[i].toInt() and 0xFF != fourCc[i].code) return false
        }
        for (i in 0 until 12) {
            if (guid[4 + i] != WAVE_SUFFIX[i]) return false
        }
        return true
    }

    /** True when the sixteen bytes at [offset] are the Wave64 outer identifier. */
    internal fun isWave64Header(header: ByteArray, offset: Int = 0): Boolean {
        if (offset < 0 || offset > header.size - RIFF_GUID.size) return false
        for (i in 0 until 16) {
            if (header[offset + i] != RIFF_GUID[i]) return false
        }
        return true
    }

    private fun readFormat(reader: SourceReader, size: Long, options: DecodeOptions): Wave64Format {
        if (size < 16) {
            throw CorruptStreamException("a Wave64 format chunk is at least 16 bytes, this one is $size")
        }
        val start = reader.position
        var formatTag = reader.readU16Le()
        val channels = reader.readU16Le()
        val sampleRate = reader.readI32Le()
        reader.readU32Le() // Average bytes per second, derived and not trusted.
        val blockAlign = reader.readU16Le()
        val containerBits = reader.readU16Le()

        options.checkStreamShape(sampleRate, channels, start)

        var channelMask = 0
        var validBits = containerBits
        if (formatTag == FORMAT_EXTENSIBLE) {
            if (size < 40) {
                throw CorruptStreamException(
                    "an extensible Wave64 format chunk is 40 bytes, this one is $size", start,
                )
            }
            val extraSize = reader.readU16Le()
            if (extraSize != 22) {
                throw CorruptStreamException(
                    "an extensible Wave64 format declares $extraSize extra bytes instead of 22",
                    start,
                )
            }
            val declaredValidBits = reader.readU16Le()
            validBits = if (declaredValidBits == 0) containerBits else declaredValidBits
            if (validBits !in 1..containerBits) {
                throw CorruptStreamException(
                    "Wave64 valid bits $validBits do not fit in a $containerBits-bit sample",
                    start,
                )
            }
            channelMask = reader.readI32Le()
            validateChannelMask(channelMask, channels, start)
            formatTag = reader.readU16Le()
            val guidSuffix = reader.readBytes(14)
            if (!guidSuffix.contentEquals(KSDATAFORMAT_SUFFIX)) {
                throw UnsupportedFeatureException(
                    "a Wave64 subformat GUID outside the Microsoft range",
                    "this file uses a subformat GUID that is not one of the standard ones",
                )
            }
        }

        val layout = if (channelMask != 0) {
            ChannelLayout.fromWaveMask(channelMask, channels)
        } else {
            ChannelLayout.canonical(channels)
        }
        val audioFormat = AudioFormat(sampleRate, layout)

        val result = when (formatTag) {
            FORMAT_PCM -> {
                if (containerBits != 8 && containerBits != 16 &&
                    containerBits != 24 && containerBits != 32
                ) {
                    throw CorruptStreamException(
                        "PCM storage in Wave64 is 8, 16, 24 or 32 bits, " +
                            "this file says $containerBits",
                        start,
                    )
                }
                Wave64Format(
                    codec = AudioCodec.PCM_INTEGER,
                    audioFormat = audioFormat,
                    sampleCodec = LinearPcmCodec(
                        channels,
                        PcmLayout(
                            bitDepth = validBits,
                            littleEndian = true,
                            signed = containerBits != 8,
                            storageBytes = containerBits / 8,
                            highAligned = validBits != containerBits,
                        ),
                    ),
                    storedBitDepth = validBits,
                )
            }

            FORMAT_IEEE_FLOAT -> {
                if (containerBits != 32 && containerBits != 64) {
                    throw CorruptStreamException(
                        "float in Wave64 is 32 or 64 bits, this file says $containerBits", start,
                    )
                }
                if (validBits != containerBits) {
                    throw CorruptStreamException(
                        "Wave64 float valid bits must equal its $containerBits-bit storage width",
                        start,
                    )
                }
                Wave64Format(
                    codec = AudioCodec.PCM_FLOAT,
                    audioFormat = audioFormat,
                    sampleCodec = LinearPcmCodec(
                        channels,
                        PcmLayout(
                            containerBits,
                            littleEndian = true,
                            signed = true,
                            isFloat = true,
                        ),
                    ),
                    storedBitDepth = null,
                )
            }

            FORMAT_ALAW, FORMAT_MULAW -> {
                if (containerBits != 8) {
                    throw CorruptStreamException(
                        "G.711 in Wave64 is 8 bits, this file says $containerBits",
                        start,
                    )
                }
                Wave64Format(
                    codec = if (formatTag == FORMAT_ALAW) AudioCodec.A_LAW else AudioCodec.MU_LAW,
                    audioFormat = audioFormat,
                    sampleCodec = G711Codec(channels, aLaw = formatTag == FORMAT_ALAW),
                    storedBitDepth = null,
                )
            }

            else -> throw UnsupportedFeatureException(
                "Wave64 format tag 0x${formatTag.toString(16).uppercase()}",
                "this Wave64 file holds format tag 0x${formatTag.toString(16).uppercase()}, " +
                "which AudioKodec cannot decode",
            )
        }
        if (blockAlign != result.sampleCodec.blockBytes) {
            throw CorruptStreamException(
                "Wave64 block alignment $blockAlign disagrees with " +
                    "${result.sampleCodec.blockBytes} bytes per codec block",
                start,
            )
        }
        return result
    }

    private class Wave64Format(
        val codec: AudioCodec,
        val audioFormat: AudioFormat,
        val sampleCodec: SampleCodec,
        val storedBitDepth: Int?,
    )

    private fun validateChannelMask(mask: Int, channels: Int, at: Long) {
        if (mask == 0) return
        if (mask and KNOWN_SPEAKER_MASK.inv() != 0) {
            throw CorruptStreamException(
                "Wave64 channel mask 0x${mask.toUInt().toString(16)} sets reserved speaker bits",
                at,
            )
        }
        val named = mask.countOneBits()
        if (named != channels) {
            throw CorruptStreamException(
                "Wave64 channel mask names $named speakers for $channels channels",
                at,
            )
        }
    }

    private const val KNOWN_SPEAKER_MASK = 0x0003_FFFF
}

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
import io.github.yuroyami.audiokodec.internal.codec.ImaAdpcmQuickTimeCodec
import io.github.yuroyami.audiokodec.internal.codec.LinearPcmCodec
import io.github.yuroyami.audiokodec.internal.codec.PcmLayout
import io.github.yuroyami.audiokodec.internal.codec.SampleCodec
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Reads AIFF and AIFF-C.
 *
 * AIFF stores big-endian integer PCM and nothing else. AIFF-C adds a
 * four-character compression type to the same header, and most of the types
 * in use are still uncompressed: `sowt` is little-endian PCM, `fl32` is float,
 * `twos` is plain AIFF spelled out. The genuinely compressed ones this reader
 * handles are the two G.711 laws and QuickTime's IMA variant.
 *
 * The sample rate is stored as an 80-bit extended float, a type no current
 * language has, so it is decoded by hand.
 */
internal object AiffReader {

    fun parse(reader: SourceReader, options: DecodeOptions): ParsedStream {
        reader.seek(0)
        reader.expectFourCc("FORM", "AIFF form")
        val formSize = reader.readU32Be()
        val formType = reader.readFourCc()
        val isAifc = when (formType) {
            "AIFF" -> false
            "AIFC" -> true
            else -> throw CorruptStreamException(
                "expected an 'AIFF' or 'AIFC' form but found '$formType'", 8,
            )
        }

        var common: Common? = null
        var dataOffset = -1L
        var dataBytes = -1L
        var sawAifcVersion = false

        val sourceSize = reader.size
        val declaredFormEnd = if (formSize == 0xFFFF_FFFFL) -1L else formSize + 8L
        val formEnd = if (declaredFormEnd < 0) {
            sourceSize
        } else {
            if (sourceSize in 1 until declaredFormEnd) sourceSize else declaredFormEnd
        }

        while (true) {
            // Stopping with exactly eight bytes left would skip a final
            // zero-length chunk, which is what an empty file ends with.
            if (formEnd in 1 until reader.position + 8) break
            if (sourceSize in 1 until reader.position + 8) break
            val chunkId = try {
                reader.readFourCc()
            } catch (_: TruncatedStreamException) {
                break
            }
            val chunkStart = reader.position - 4
            val chunkSize = try {
                reader.readU32Be()
            } catch (_: TruncatedStreamException) {
                break
            }
            val bodyStart = reader.position

            if (chunkSize > Long.MAX_VALUE - bodyStart) {
                throw CorruptStreamException("chunk '$chunkId' size overflows 64 bits", chunkStart)
            }
            val bodyEnd = bodyStart + chunkSize
            var next = bodyEnd
            if (chunkSize % 2 == 1L) {
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
            if (declaredFormEnd >= 0 && next > declaredFormEnd) {
                throw CorruptStreamException(
                    "AIFF chunk '$chunkId' extends past the end of its enclosing FORM",
                    chunkStart,
                )
            }

            when (chunkId) {
                "FVER" -> if (isAifc) {
                    if (sawAifcVersion) {
                        throw CorruptStreamException(
                            "an AIFF-C file may contain only one 'FVER' chunk",
                            chunkStart,
                        )
                    }
                    if (chunkSize != 4L || reader.readU32Be() != AIFC_VERSION) {
                        throw CorruptStreamException(
                            "the AIFF-C version chunk is invalid",
                            chunkStart,
                        )
                    }
                    sawAifcVersion = true
                }
                "COMM" -> {
                    if (common != null) {
                        throw CorruptStreamException(
                            "an AIFF file may contain only one 'COMM' chunk",
                            chunkStart,
                        )
                    }
                    if (isAifc && !sawAifcVersion) {
                        throw CorruptStreamException(
                            "the AIFF-C version chunk must precede its common chunk",
                            chunkStart,
                        )
                    }
                    common = readCommon(reader, chunkSize, isAifc, options)
                }
                "SSND" -> {
                    if (dataOffset >= 0) {
                        throw CorruptStreamException(
                            "an AIFF file may contain only one 'SSND' chunk",
                            chunkStart,
                        )
                    }
                    if (chunkSize < 8) {
                        throw CorruptStreamException("an 'SSND' chunk is at least 8 bytes", chunkStart)
                    }
                    val offset = reader.readU32Be()
                    reader.readU32Be() // Block size, only meaningful with offset, and unused here.
                    if (offset > chunkSize - 8) {
                        throw CorruptStreamException(
                            "the AIFF SSND offset $offset runs past its $chunkSize-byte chunk",
                            chunkStart,
                        )
                    }
                    dataOffset = bodyStart + 8 + offset
                    dataBytes = chunkSize - 8 - offset
                }
            }

            if (chunkId != "SSND" && reader.position > bodyEnd) {
                throw CorruptStreamException(
                    "the '$chunkId' parser read past its declared chunk body",
                    chunkStart,
                )
            }
            reader.seek(next)
            if (sourceSize in 1..next) break
            if (dataOffset >= 0 && common != null) break
        }

        val comm = common ?: throw CorruptStreamException("the file has no 'COMM' chunk", 0)
        if (dataOffset < 0) {
            // A COMM saying zero frames with no SSND is a valid empty file.
            if (comm.frames == 0L) {
                dataOffset = reader.position
                dataBytes = 0
            } else {
                throw CorruptStreamException("the file has no 'SSND' chunk", 0)
            }
        }

        // The stated size is kept even when the file is shorter, so that a
        // truncated file is reported as truncated rather than quietly read as
        // a shorter one. See WaveReader for the same reasoning.
        val payload = if (dataBytes < 0) {
            if (sourceSize >= 0) (sourceSize - dataOffset).coerceAtLeast(0L) else -1L
        } else {
            dataBytes
        }

        return ParsedStream(
            container = AudioContainer.AIFF,
            codec = comm.codec,
            sampleCodec = comm.sampleCodec,
            format = comm.audioFormat,
            dataOffset = dataOffset,
            dataBytes = payload,
            declaredFrames = comm.frames,
            bitDepth = comm.storedBitDepth,
        )
    }

    private fun readCommon(
        reader: SourceReader,
        size: Long,
        isAifc: Boolean,
        options: DecodeOptions,
    ): Common {
        if (size < 18) {
            throw CorruptStreamException("a 'COMM' chunk is at least 18 bytes, this one is $size")
        }
        val start = reader.position
        val channels = reader.readU16Be()
        val frames = reader.readU32Be()
        val sampleSize = reader.readU16Be()
        val sampleRate = readExtendedFloat(reader)

        if (sampleRate <= 0.0 || !sampleRate.isFinite() ||
            sampleRate > Int.MAX_VALUE.toDouble()
        ) {
            throw CorruptStreamException("the AIFF sample rate is $sampleRate", start)
        }
        if (sampleRate != sampleRate.toInt().toDouble()) {
            throw UnsupportedFeatureException(
                "a fractional AIFF sample rate",
                "this AIFF file uses the fractional sample rate $sampleRate Hz, but " +
                    "AudioFormat represents whole hertz",
            )
        }
        val rate = sampleRate.roundToInt()
        options.checkStreamShape(rate, channels, start)

        if (isAifc && size < 22) {
            throw CorruptStreamException(
                "an AIFF-C 'COMM' chunk needs a compression type, this one is $size bytes",
                start,
            )
        }
        val compression = if (isAifc) reader.readFourCc() else "NONE"
        val layout = ChannelLayout.canonical(channels)
        val audioFormat = AudioFormat(rate, layout)

        return when (compression) {
            // Big-endian integer PCM, which is what plain AIFF always is.
            "NONE", "twos", "in24", "in32" -> {
                val depth = normaliseDepth(sampleSize, compression, start)
                val storageBytes = (depth + 7) / 8
                Common(
                    codec = AudioCodec.PCM_INTEGER,
                    audioFormat = audioFormat,
                    sampleCodec = LinearPcmCodec(
                        channels,
                        PcmLayout(
                            depth,
                            littleEndian = false,
                            signed = true,
                            storageBytes = storageBytes,
                            highAligned = depth % 8 != 0,
                        ),
                    ),
                    frames = frames,
                    storedBitDepth = depth,
                )
            }

            // The same, stored the other way round. `sowt` is `twos`
            // backwards, which is the joke the format made of the name.
            "sowt", "23ni", "42ni" -> {
                val depth = normaliseDepth(sampleSize, compression, start)
                val storageBytes = (depth + 7) / 8
                Common(
                    codec = AudioCodec.PCM_INTEGER,
                    audioFormat = audioFormat,
                    sampleCodec = LinearPcmCodec(
                        channels,
                        PcmLayout(
                            depth,
                            littleEndian = true,
                            signed = true,
                            storageBytes = storageBytes,
                            highAligned = depth % 8 != 0,
                        ),
                    ),
                    frames = frames,
                    storedBitDepth = depth,
                )
            }

            // Unsigned 8-bit, the one place AIFF-C departs from signed.
            "raw " -> {
                if (sampleSize != 8) {
                    throw CorruptStreamException(
                        "AIFF-C 'raw ' samples are 8 bits, this file says $sampleSize",
                        start,
                    )
                }
                Common(
                    codec = AudioCodec.PCM_INTEGER,
                    audioFormat = audioFormat,
                    sampleCodec = LinearPcmCodec(
                        channels,
                        PcmLayout(8, littleEndian = false, signed = false),
                    ),
                    frames = frames,
                    storedBitDepth = 8,
                )
            }

            "fl32", "FL32" -> {
                requireSampleSize(sampleSize, 32, compression, start)
                Common(
                    codec = AudioCodec.PCM_FLOAT,
                    audioFormat = audioFormat,
                    sampleCodec = LinearPcmCodec(
                        channels,
                        PcmLayout(32, littleEndian = false, signed = true, isFloat = true),
                    ),
                    frames = frames,
                    storedBitDepth = null,
                )
            }

            "fl64", "FL64" -> {
                requireSampleSize(sampleSize, 64, compression, start)
                Common(
                    codec = AudioCodec.PCM_FLOAT,
                    audioFormat = audioFormat,
                    sampleCodec = LinearPcmCodec(
                        channels,
                        PcmLayout(64, littleEndian = false, signed = true, isFloat = true),
                    ),
                    frames = frames,
                    storedBitDepth = null,
                )
            }

            "alaw", "ALAW" -> {
                requireSampleSize(sampleSize, intArrayOf(8, 16), compression, start)
                Common(
                    codec = AudioCodec.A_LAW,
                    audioFormat = audioFormat,
                    sampleCodec = G711Codec(channels, aLaw = true),
                    frames = frames,
                    storedBitDepth = null,
                )
            }

            "ulaw", "ULAW" -> {
                requireSampleSize(sampleSize, intArrayOf(8, 16), compression, start)
                Common(
                    codec = AudioCodec.MU_LAW,
                    audioFormat = audioFormat,
                    sampleCodec = G711Codec(channels, aLaw = false),
                    frames = frames,
                    storedBitDepth = null,
                )
            }

            "ima4" -> {
                // Apple writes an unspecified width of zero; other writers
                // state the coded four bits or the decoded sixteen bits.
                requireSampleSize(sampleSize, intArrayOf(0, 4, 16), compression, start)
                Common(
                    codec = AudioCodec.IMA_ADPCM,
                    audioFormat = audioFormat,
                    sampleCodec = ImaAdpcmQuickTimeCodec(channels),
                    // COMM counts interleaved packet groups for this coding.
                    // Its unsigned 32-bit count times 64 fits Long exactly.
                    frames = frames * 64,
                    storedBitDepth = null,
                )
            }

            else -> throw UnsupportedFeatureException(
                "AIFF-C compression type '$compression'",
                "this AIFF-C file is compressed with '$compression', which AudioKodec cannot decode",
            )
        }
    }

    /**
     * Reconciles the declared sample size with the compression type.
     *
     * `in24` and `in32` state their own width, and files have been seen where
     * the COMM sample size disagrees with them. The compression type is the
     * more reliable of the two, because it is what the writer chose last.
     */
    private fun normaliseDepth(sampleSize: Int, compression: String, at: Long): Int = when (compression) {
        "in24", "42ni" -> 24
        "in32", "23ni" -> 32
        else -> if (sampleSize in 1..32) {
            sampleSize
        } else {
            throw CorruptStreamException(
                "AIFF sample size $sampleSize is outside 1..32", at,
            )
        }
    }

    private fun requireSampleSize(actual: Int, expected: Int, compression: String, at: Long) {
        if (actual != expected) {
            throw CorruptStreamException(
                "AIFF-C '$compression' samples are $expected bits, this file says $actual",
                at,
            )
        }
    }

    private fun requireSampleSize(actual: Int, expected: IntArray, compression: String, at: Long) {
        if (actual !in expected) {
            throw CorruptStreamException(
                "AIFF-C '$compression' sample width must be ${expected.joinToString(" or ")} bits, this file says $actual",
                at,
            )
        }
    }

    /**
     * Reads the 80-bit IEEE 754 extended float that AIFF uses for its sample
     * rate.
     *
     * The layout is one sign bit, fifteen exponent bits with a bias of 16383,
     * and a 64-bit mantissa whose top bit is stored rather than implied. No
     * language in common use has the type, so this rebuilds the value as a
     * Double.
     */
    private fun readExtendedFloat(reader: SourceReader): Double {
        val high = reader.readU16Be()
        val mantissa = reader.readI64Be()
        val sign = if (high and 0x8000 != 0) -1.0 else 1.0
        val exponent = high and 0x7FFF
        if (exponent == 0 && mantissa == 0L) return 0.0
        if (exponent == 0x7FFF) {
            throw CorruptStreamException("the AIFF sample rate is not a finite number")
        }
        // The mantissa is unsigned, and Kotlin's Long is not, so the top bit
        // is added back separately.
        val low = (mantissa ushr 1).toDouble() * 2.0 + (mantissa and 1L).toDouble()
        return sign * low * 2.0.pow(exponent - 16383 - 63)
    }

    private class Common(
        val codec: AudioCodec,
        val audioFormat: AudioFormat,
        val sampleCodec: SampleCodec,
        val frames: Long,
        val storedBitDepth: Int?,
    )

    private const val AIFC_VERSION = 0xA2805140L
}

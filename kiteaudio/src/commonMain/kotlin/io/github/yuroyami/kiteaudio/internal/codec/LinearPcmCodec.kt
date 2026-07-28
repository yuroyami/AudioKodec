package io.github.yuroyami.kiteaudio.internal.codec

import io.github.yuroyami.kiteaudio.Dither
import io.github.yuroyami.kiteaudio.Pcm
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * How a container stores one uncompressed sample.
 *
 * The three axes are width, byte order, and whether the value is signed. They
 * are independent, and containers pick different combinations: WAV stores
 * 8-bit unsigned but 16-bit signed, AIFF stores everything signed and
 * big-endian, and AIFF-C `sowt` is AIFF with little-endian samples.
 */
internal data class PcmLayout(
    /** Significant bits per sample. Integer PCM may use any width from 1 to 32. */
    val bitDepth: Int,
    /** True for little-endian storage, false for big-endian. */
    val littleEndian: Boolean,
    /** True when the stored integers are signed. Ignored when [isFloat]. */
    val signed: Boolean,
    /** True when samples are IEEE 754 floats rather than integers. */
    val isFloat: Boolean = false,
    /** Bytes occupied by one sample, which may exceed the significant width. */
    val storageBytes: Int = (bitDepth + 7) / 8,
    /** Whether significant integer bits sit at the high end of their storage word. */
    val highAligned: Boolean = false,
) {
    init {
        if (isFloat) {
            require(bitDepth == 32 || bitDepth == 64) {
                "float PCM is 32 or 64 bits, not $bitDepth"
            }
            require(storageBytes == bitDepth / 8) {
                "$bitDepth-bit float PCM occupies ${bitDepth / 8} bytes, not $storageBytes"
            }
            require(!highAligned) { "floating-point PCM cannot be high-aligned" }
        } else {
            require(bitDepth in 1..32) {
                "integer PCM is 1 to 32 bits, not $bitDepth"
            }
            require(storageBytes in ((bitDepth + 7) / 8)..4) {
                "$bitDepth-bit integer PCM cannot occupy $storageBytes bytes"
            }
        }
    }

    /** Bytes one stored sample occupies. */
    val bytesPerSample: Int get() = storageBytes

    /** How this layout is described in an error message. */
    val displayName: String get() = when {
        isFloat -> "${bitDepth}-bit float"
        signed -> "${bitDepth}-bit signed"
        else -> "${bitDepth}-bit unsigned"
    }
}

/**
 * Uncompressed PCM in any of the widths and byte orders the containers use.
 *
 * One block is one frame, so a container can seek to any sample by
 * multiplying.
 */
internal class LinearPcmCodec(
    override val channels: Int,
    val layout: PcmLayout,
    /** Noise added before rounding when encoding. Ignored when decoding. */
    private val dither: Dither = Dither.None,
) : SampleCodec, SampleEncoder {

    override val blockBytes: Int = layout.bytesPerSample * channels

    override val framesPerBlock: Int = 1

    override val storedBitDepth: Int? = if (layout.isFloat) null else layout.bitDepth

    override fun decode(
        src: ByteArray,
        srcOffset: Int,
        blocks: Int,
        out: FloatArray,
        outOffset: Int,
    ): Int {
        val samples = blocks * channels
        var input = srcOffset
        var output = outOffset
        val step = layout.bytesPerSample
        if (layout.isFloat) {
            if (layout.bitDepth == 32) {
                repeat(samples) {
                    out[output++] = Float.fromBits(readInt32(src, input, layout.littleEndian))
                    input += step
                }
            } else {
                repeat(samples) {
                    out[output++] = Double.fromBits(readInt64(src, input, layout.littleEndian))
                        .toFloat()
                    input += step
                }
            }
            return blocks
        }
        val scale = (1.0 / integerScale(layout.bitDepth)).toFloat()
        repeat(samples) {
            out[output++] = readIntSample(src, input) * scale
            input += step
        }
        return blocks
    }

    override fun decodeInt(
        src: ByteArray,
        srcOffset: Int,
        blocks: Int,
        out: IntArray,
        outOffset: Int,
    ): Int {
        if (layout.isFloat) {
            throw UnsupportedOperationException("float PCM has no exact integer form")
        }
        val samples = blocks * channels
        var input = srcOffset
        var output = outOffset
        val step = layout.bytesPerSample
        repeat(samples) {
            out[output++] = readIntSample(src, input)
            input += step
        }
        return blocks
    }

    override fun encode(
        src: FloatArray,
        srcOffset: Int,
        frames: Int,
        out: ByteArray,
        outOffset: Int,
    ): Int {
        val samples = frames * channels
        var input = srcOffset
        var output = outOffset
        if (layout.isFloat) {
            if (layout.bitDepth == 32) {
                repeat(samples) {
                    writeInt32(out, output, src[input++].toRawBits(), layout.littleEndian)
                    output += 4
                }
            } else {
                repeat(samples) {
                    writeInt64(out, output, src[input++].toDouble().toRawBits(), layout.littleEndian)
                    output += 8
                }
            }
            return samples * layout.bytesPerSample
        }
        val scale = integerScale(layout.bitDepth).toFloat()
        val min = integerMinimum(layout.bitDepth)
        val max = integerMaximum(layout.bitDepth)
        val step = (1.0 / integerScale(layout.bitDepth)).toFloat()
        repeat(samples) {
            val noise = when (val d = dither) {
                Dither.None -> 0f
                is Dither.Tpdf -> d.nextSample() * step
            }
            writeIntSample(
                out, output, quantise(src[input++] + noise, scale, min, max, layout.bitDepth),
            )
            output += layout.bytesPerSample
        }
        return samples * layout.bytesPerSample
    }

    /** Encodes samples that are already integers of the layout's depth. */
    fun encodeInt(
        src: IntArray,
        srcOffset: Int,
        frames: Int,
        out: ByteArray,
        outOffset: Int,
    ): Int {
        require(!layout.isFloat) { "encodeInt is for integer layouts only" }
        val samples = frames * channels
        var input = srcOffset
        var output = outOffset
        repeat(samples) {
            writeIntSample(out, output, src[input++])
            output += layout.bytesPerSample
        }
        return samples * layout.bytesPerSample
    }

    private fun readIntSample(src: ByteArray, at: Int): Int {
        var raw = 0L
        if (layout.littleEndian) {
            for (index in layout.storageBytes - 1 downTo 0) {
                raw = (raw shl 8) or (src[at + index].toLong() and 0xFF)
            }
        } else {
            for (index in 0 until layout.storageBytes) {
                raw = (raw shl 8) or (src[at + index].toLong() and 0xFF)
            }
        }
        val padding = layout.storageBytes * 8 - layout.bitDepth
        val significant = if (layout.highAligned) raw ushr padding else raw
        val mask = (1L shl layout.bitDepth) - 1
        val bits = significant and mask
        val midpoint = 1L shl (layout.bitDepth - 1)
        val value = if (layout.signed) {
            if (bits and midpoint != 0L) bits - (1L shl layout.bitDepth) else bits
        } else {
            bits - midpoint
        }
        return value.toInt()
    }

    private fun writeIntSample(out: ByteArray, at: Int, value: Int) {
        val mask = (1L shl layout.bitDepth) - 1
        var raw = if (layout.signed) {
            value.toLong() and mask
        } else {
            (value.toLong() + (1L shl (layout.bitDepth - 1))) and mask
        }
        if (layout.highAligned) raw = raw shl (layout.storageBytes * 8 - layout.bitDepth)
        if (layout.littleEndian) {
            for (index in 0 until layout.storageBytes) {
                out[at + index] = (raw ushr (8 * index)).toByte()
            }
        } else {
            for (index in 0 until layout.storageBytes) {
                out[at + index] =
                    (raw ushr (8 * (layout.storageBytes - 1 - index))).toByte()
            }
        }
    }
}

private fun integerScale(bitDepth: Int): Double = (1L shl (bitDepth - 1)).toDouble()

private fun integerMinimum(bitDepth: Int): Int = -(1L shl (bitDepth - 1)).toInt()

private fun integerMaximum(bitDepth: Int): Int = ((1L shl (bitDepth - 1)) - 1).toInt()

/**
 * Rounds one Float32 sample onto the integer grid of [bitDepth], clamping
 * anything outside the nominal range.
 *
 * 32-bit needs the whole `Int` range, which a Float multiply cannot reach, so
 * that one width works in Double.
 */
private fun quantise(sample: Float, scale: Float, min: Int, max: Int, bitDepth: Int): Int {
    if (sample.isNaN()) return 0
    if (bitDepth == 32) {
        val r = (sample.toDouble() * scale.toDouble()).roundToLong()
        return when {
            r <= min.toLong() -> min
            r >= max.toLong() -> max
            else -> r.toInt()
        }
    }
    val r = (sample * scale).roundToInt()
    return if (r < min) min else if (r > max) max else r
}

private fun readInt16(src: ByteArray, at: Int, littleEndian: Boolean): Int {
    val a = src[at].toInt() and 0xFF
    val b = src[at + 1].toInt() and 0xFF
    val raw = if (littleEndian) a or (b shl 8) else (a shl 8) or b
    return raw.toShort().toInt()
}

private fun readInt24(src: ByteArray, at: Int, littleEndian: Boolean): Int {
    val a = src[at].toInt() and 0xFF
    val b = src[at + 1].toInt() and 0xFF
    val c = src[at + 2].toInt() and 0xFF
    val raw = if (littleEndian) a or (b shl 8) or (c shl 16) else (a shl 16) or (b shl 8) or c
    // Sign-extend from bit 23.
    return (raw shl 8) shr 8
}

private fun readInt32(src: ByteArray, at: Int, littleEndian: Boolean): Int {
    val a = src[at].toInt() and 0xFF
    val b = src[at + 1].toInt() and 0xFF
    val c = src[at + 2].toInt() and 0xFF
    val d = src[at + 3].toInt() and 0xFF
    return if (littleEndian) {
        a or (b shl 8) or (c shl 16) or (d shl 24)
    } else {
        (a shl 24) or (b shl 16) or (c shl 8) or d
    }
}

private fun readInt64(src: ByteArray, at: Int, littleEndian: Boolean): Long {
    var result = 0L
    if (littleEndian) {
        for (i in 7 downTo 0) result = (result shl 8) or (src[at + i].toLong() and 0xFF)
    } else {
        for (i in 0 until 8) result = (result shl 8) or (src[at + i].toLong() and 0xFF)
    }
    return result
}

private fun writeInt16(out: ByteArray, at: Int, value: Int, littleEndian: Boolean) {
    if (littleEndian) {
        out[at] = value.toByte()
        out[at + 1] = (value ushr 8).toByte()
    } else {
        out[at] = (value ushr 8).toByte()
        out[at + 1] = value.toByte()
    }
}

private fun writeInt24(out: ByteArray, at: Int, value: Int, littleEndian: Boolean) {
    if (littleEndian) {
        out[at] = value.toByte()
        out[at + 1] = (value ushr 8).toByte()
        out[at + 2] = (value ushr 16).toByte()
    } else {
        out[at] = (value ushr 16).toByte()
        out[at + 1] = (value ushr 8).toByte()
        out[at + 2] = value.toByte()
    }
}

private fun writeInt32(out: ByteArray, at: Int, value: Int, littleEndian: Boolean) {
    if (littleEndian) {
        out[at] = value.toByte()
        out[at + 1] = (value ushr 8).toByte()
        out[at + 2] = (value ushr 16).toByte()
        out[at + 3] = (value ushr 24).toByte()
    } else {
        out[at] = (value ushr 24).toByte()
        out[at + 1] = (value ushr 16).toByte()
        out[at + 2] = (value ushr 8).toByte()
        out[at + 3] = value.toByte()
    }
}

private fun writeInt64(out: ByteArray, at: Int, value: Long, littleEndian: Boolean) {
    if (littleEndian) {
        for (i in 0 until 8) out[at + i] = (value ushr (8 * i)).toByte()
    } else {
        for (i in 0 until 8) out[at + i] = (value ushr (8 * (7 - i))).toByte()
    }
}

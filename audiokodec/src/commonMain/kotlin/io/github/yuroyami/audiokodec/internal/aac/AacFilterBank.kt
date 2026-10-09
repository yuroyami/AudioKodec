package io.github.yuroyami.audiokodec.internal.aac

import io.github.yuroyami.audiokodec.internal.dsp.InverseMdct
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The window halves of 4.6.11.3.2: the sine window, and the Kaiser-Bessel
 * derived window with alpha 4 for long windows and 6 for short ones.
 *
 * Each array is the rising half, `W_LEFT(n)` for n below N/2. Both windows are
 * symmetric, so the falling half is the same values read backwards. The
 * computed values agree with the Q15 window tables of the PacketVideo
 * decoder to within one step of their rounding.
 */
internal object AacWindows {
    val SINE_LONG: FloatArray = sine(2048)
    val SINE_SHORT: FloatArray = sine(256)
    val KBD_LONG: FloatArray = kaiserBesselDerived(2048, 4.0)
    val KBD_SHORT: FloatArray = kaiserBesselDerived(256, 6.0)

    fun long(shape: Int): FloatArray = if (shape == 1) KBD_LONG else SINE_LONG
    fun short(shape: Int): FloatArray = if (shape == 1) KBD_SHORT else SINE_SHORT

    private fun sine(n: Int): FloatArray = FloatArray(n / 2) { sin(PI / n * (it + 0.5)).toFloat() }

    /** The square root of the running sum of the Kaiser kernel, normalised by its total. */
    private fun kaiserBesselDerived(n: Int, alpha: Double): FloatArray {
        val quarter = n / 4.0
        val kernel = DoubleArray(n / 2 + 1) {
            val x = (it - quarter) / quarter
            besselI0(PI * alpha * sqrt(1.0 - x * x))
        }
        val total = kernel.sum()
        var running = 0.0
        return FloatArray(n / 2) {
            running += kernel[it]
            sqrt(running / total).toFloat()
        }
    }

    /** The zeroth order modified Bessel function of the first kind, by its power series. */
    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        var k = 1
        while (true) {
            val factor = x / (2.0 * k)
            term *= factor * factor
            sum += term
            if (term < sum * 1e-17) return sum
            k++
        }
    }
}

/**
 * Turns one channel's spectrum into 1024 samples: the inverse transform, the
 * window its sequence and shape call for, and the overlap with the previous
 * block's second half, 4.6.11.
 *
 * The transform is scaled by 2/N as 4.6.11.3.1 defines it, which leaves
 * samples on the 16-bit scale the standard decodes to, and then by 1/32768
 * to bring them to AudioKodec's -1 to 1.
 */
internal class AacFilterBank {
    private val overlap = FloatArray(1024)

    /** The previous block's window shape, or -1 before the first block. */
    private var previousShape = -1

    fun reset() {
        overlap.fill(0f)
        previousShape = -1
    }

    /**
     * Writes 1024 samples of [info]'s block, decoded from [spectrum], to
     * [out] from [at], every [stride]th entry.
     */
    fun synthesise(info: AacIcsInfo, spectrum: FloatArray, out: FloatArray, at: Int, stride: Int, scratch: Scratch) {
        val shape = info.windowShape
        val leftShape = if (previousShape < 0) shape else previousShape
        val z = scratch.block
        if (info.isShort) {
            z.fill(0f)
            val samples = scratch.shortBlock
            val rising = AacWindows.short(shape)
            for (j in 0 until 8) {
                for (k in 0 until 128) scratch.shortInput[k] = spectrum[j * 128 + k]
                scratch.shortTransform.transform(scratch.shortInput, samples, SHORT_SCALE)
                val left = if (j == 0) AacWindows.short(leftShape) else rising
                val base = 448 + 128 * j
                for (n in 0 until 128) z[base + n] += samples[n] * left[n]
                for (n in 0 until 128) z[base + 128 + n] += samples[128 + n] * rising[127 - n]
            }
        } else {
            scratch.longTransform.transform(spectrum, z, LONG_SCALE)
            when (info.windowSequence) {
                AacIcsInfo.LONG_STOP -> {
                    val left = AacWindows.short(leftShape)
                    for (n in 0 until 448) z[n] = 0f
                    for (n in 0 until 128) z[448 + n] *= left[n]
                }
                else -> {
                    val left = AacWindows.long(leftShape)
                    for (n in 0 until 1024) z[n] *= left[n]
                }
            }
            when (info.windowSequence) {
                AacIcsInfo.LONG_START -> {
                    val right = AacWindows.short(shape)
                    for (n in 0 until 128) z[1472 + n] *= right[127 - n]
                    for (n in 1600 until 2048) z[n] = 0f
                }
                else -> {
                    val right = AacWindows.long(shape)
                    for (n in 0 until 1024) z[1024 + n] *= right[1023 - n]
                }
            }
        }
        var o = at
        for (n in 0 until 1024) {
            out[o] = z[n] + overlap[n]
            o += stride
        }
        z.copyInto(overlap, 0, 1024, 2048)
        previousShape = shape
    }

    /** Working space every channel of a decoder can share. */
    class Scratch {
        val longTransform: InverseMdct = InverseMdct(2048)
        val shortTransform: InverseMdct = InverseMdct(256)
        val block: FloatArray = FloatArray(2048)
        val shortBlock: FloatArray = FloatArray(256)
        val shortInput: FloatArray = FloatArray(128)
    }

    private companion object {
        const val LONG_SCALE: Float = 2f / 2048f / 32768f
        const val SHORT_SCALE: Float = 2f / 256f / 32768f
    }
}

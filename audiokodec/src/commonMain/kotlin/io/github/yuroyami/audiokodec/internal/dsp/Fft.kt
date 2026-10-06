package io.github.yuroyami.audiokodec.internal.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A complex fast Fourier transform of a fixed power-of-two size.
 *
 * Radix-2, decimation in time, with the twiddle factors and the bit-reversal
 * order worked out once when the transform is built. Real and imaginary parts
 * live in separate arrays rather than in pairs of an object: an array of
 * objects would allocate per sample on the JVM and be slower still on the web
 * targets, and every transform in this library runs inside a decode loop.
 *
 * The transform works in place. Building one is the expensive part, so a codec
 * keeps one per block size for the life of the stream.
 */
internal class Fft(
    val size: Int,
    /**
     * True for the analysis sign, `exp(-2πi jk/size)`, false for the synthesis
     * sign, `exp(+2πi jk/size)`.
     *
     * Neither direction is scaled, so a forward transform followed by an
     * inverse one multiplies by [size]. Callers fold that into whatever scaling
     * they already apply.
     */
    private val forward: Boolean,
) {

    init {
        require(size >= 2 && size and (size - 1) == 0) {
            "the FFT size must be a power of two of at least 2, was $size"
        }
    }

    private val reversal = IntArray(size)
    private val twiddleCos = FloatArray(size / 2)
    private val twiddleSin = FloatArray(size / 2)

    init {
        val bits = size.countTrailingZeroBits()
        for (i in 0 until size) {
            var from = i
            var to = 0
            for (bit in 0 until bits) {
                to = (to shl 1) or (from and 1)
                from = from ushr 1
            }
            reversal[i] = to
        }
        for (k in 0 until size / 2) {
            val angle = 2.0 * PI * k / size
            twiddleCos[k] = cos(angle).toFloat()
            twiddleSin[k] = sin(angle).toFloat()
        }
    }

    /**
     * Transforms [re] and [im] in place.
     *
     * Both arrays hold [size] values. Anything beyond that is left alone, so a
     * caller may keep one oversized scratch buffer for several sizes.
     */
    fun transform(re: FloatArray, im: FloatArray) {
        require(re.size >= size && im.size >= size) {
            "the FFT needs $size values in each array"
        }

        // Reordering first is what lets the butterflies run in place. Only the
        // pairs that actually move are swapped, so each pair is touched once.
        for (i in 0 until size) {
            val j = reversal[i]
            if (j > i) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }

        val sign = if (forward) -1f else 1f
        var span = 2
        while (span <= size) {
            val half = span shr 1
            val stride = size / span
            var base = 0
            while (base < size) {
                var k = 0
                for (j in base until base + half) {
                    val wr = twiddleCos[k]
                    val wi = sign * twiddleSin[k]
                    val pair = j + half
                    val ar = re[pair]
                    val ai = im[pair]
                    val tr = ar * wr - ai * wi
                    val ti = ar * wi + ai * wr
                    re[pair] = re[j] - tr
                    im[pair] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += stride
                }
                base += span
            }
            span = span shl 1
        }
    }
}

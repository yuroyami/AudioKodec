package io.github.yuroyami.audiokodec.internal.mpeg.encoder

import io.github.yuroyami.audiokodec.internal.dsp.Fft
import io.github.yuroyami.audiokodec.internal.mpeg.Mp3Tables
import io.github.yuroyami.audiokodec.internal.psy.Hearing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Psychoacoustic model 2 of ISO/IEC 11172-3 Annex D, for Layer III.
 *
 * The model decides how much noise each scalefactor band can hide. It looks at
 * the granule through a 1024-point FFT, and through three 256-point FFTs for
 * the case that it becomes a short block, and asks two questions of every
 * part of the spectrum: how much energy is there, and how tonal is it. A
 * tone masks noise poorly and noise masks noise well, so the answer to the
 * second question sets how far below the energy the noise must stay. The
 * energy is spread across neighbouring critical bands first, because masking
 * reaches past the band it starts in.
 *
 * Tonality comes from predictability: each line's magnitude and phase are
 * extrapolated from the two previous transforms, and the further the actual
 * line lands from its prediction the noisier it is.
 *
 * What the model produces per band is a ratio, allowed noise over energy,
 * measured in the FFT's domain. The encoder multiplies it by the same band's
 * energy in the MDCT domain, which is what Annex D does to carry the result
 * across without caring how the two transforms are scaled.
 *
 * Annex D also derives the perceptual entropy, its estimate of the bits a
 * granule needs, and switches to short blocks when that passes a fixed value.
 * Here the encoder computes the entropy itself from these ratios and the MDCT
 * band energies, because it also needs it for the sum and difference channels
 * the model never sees; and the switch is made on a sharp rise in the
 * signal's high-frequency energy instead. A dense but steady passage has a
 * high entropy and gains nothing from short blocks, while a quiet onset has a
 * low one and is exactly where pre-echo is easiest to hear.
 *
 * The annex prints its partition tables per sample rate. They are computed
 * here from what they encode instead: partitions a third of a critical band
 * wide on the Bark scale, the spreading function's own formula, and the
 * threshold in quiet from Terhardt's approximation.
 */
internal class Mp3Psychoacoustics(private val sampleRate: Int, channels: Int) {

    /** What the model says about one granule of one channel. */
    class Result {
        /** Allowed noise over energy for each long scalefactor band, 0 to 21. */
        val longRatio = DoubleArray(22)

        /** The same per short scalefactor band, 0 to 12, for each window. */
        val shortRatio = Array(3) { DoubleArray(13) }

        /** True when the granule should be a short block. */
        var attack = false
    }

    private val long = Layout(1024, sampleRate)
    private val short = Layout(256, sampleRate)

    private val longFft = Fft(1024, forward = true)
    private val shortFft = Fft(256, forward = true)
    private val re = FloatArray(1024)
    private val im = FloatArray(1024)

    private val energy = DoubleArray(513)
    private val unpredictability = DoubleArray(513)

    private val previousRe = Array(channels) { Array(2) { DoubleArray(PREDICTED_LINES) } }
    private val previousIm = Array(channels) { Array(2) { DoubleArray(PREDICTED_LINES) } }
    private val previousThreshold = Array(channels) { Array(2) { DoubleArray(long.partitions) } }

    private val partitionEnergy = DoubleArray(long.partitions)
    private val partitionTonal = DoubleArray(long.partitions)
    private val threshold = DoubleArray(long.partitions)

    /** High-passed energy of each 64-sample segment, for the attack check. */
    private val segments = Array(channels) { DoubleArray(SEGMENT_HISTORY) }
    private val filterState = Array(channels) { DoubleArray(2) }

    private val longBands = Mp3Tables.longBands(sampleRate)
    private val shortBands = Mp3Tables.shortBands(sampleRate)

    /** Which long scalefactor band each line of the long FFT falls in. */
    private val longBandOfLine = IntArray(512) { line ->
        val mdctLine = ((line + 0.5) * 576.0 / 512.0).toInt()
        bandOf(longBands, mdctLine, 21)
    }

    /** Which short scalefactor band each line of a short FFT falls in. */
    private val shortBandOfLine = IntArray(128) { line ->
        val mdctLine = ((line + 0.5) * 192.0 / 128.0).toInt()
        bandOf(shortBands, mdctLine, 12)
    }

    private val bandEnergy = DoubleArray(22)
    private val bandThreshold = DoubleArray(22)

    /**
     * Analyses the block centred at [centre] in [pcm], which must hold samples
     * from `centre - 1040` to `centre + 512`.
     *
     * Calls for one channel must come in order, one granule apart: the
     * tonality prediction, the pre-echo control and the onset check all carry
     * state from the call before.
     */
    fun analyse(pcm: DoubleArray, centre: Int, channel: Int, result: Result) {
        analyseLong(pcm, centre, channel, result)
        analyseShort(pcm, centre, result)
        result.attack = detectAttack(pcm, centre, channel)
    }

    private fun analyseLong(pcm: DoubleArray, centre: Int, channel: Int, result: Result) {
        val start = centre - 512
        for (i in 0 until 1024) {
            re[i] = (pcm[start + i] * HANN_1024[i]).toFloat()
            im[i] = 0f
        }
        longFft.transform(re, im)
        for (w in 0..512) {
            val r = re[w].toDouble()
            val i = im[w].toDouble()
            energy[w] = r * r + i * i
        }

        // Unpredictability, from extrapolating the last two transforms: the
        // magnitude linearly and the phase linearly. With each line as a
        // complex number z, the predicted phase factor is the last one
        // squared over the one before, so no angle is ever computed.
        val pastRe = previousRe[channel]
        val pastIm = previousIm[channel]
        for (w in 0 until PREDICTED_LINES) {
            val re1 = pastRe[0][w]
            val im1 = pastIm[0][w]
            val re2 = pastRe[1][w]
            val im2 = pastIm[1][w]
            val r1 = sqrt(re1 * re1 + im1 * im1)
            val r2 = sqrt(re2 * re2 + im2 * im2)
            val magnitude = sqrt(energy[w])
            val predictedMagnitude = 2 * r1 - r2
            var predictedRe = 0.0
            var predictedIm = 0.0
            if (r1 > 0 && r2 > 0) {
                // (z1 / r1)^2 * conj(z2 / r2)
                val squareRe = (re1 * re1 - im1 * im1) / (r1 * r1)
                val squareIm = 2 * re1 * im1 / (r1 * r1)
                val unitRe = re2 / r2
                val unitIm = -im2 / r2
                predictedRe = predictedMagnitude * (squareRe * unitRe - squareIm * unitIm)
                predictedIm = predictedMagnitude * (squareRe * unitIm + squareIm * unitRe)
            }
            val dx = re[w] - predictedRe
            val dy = im[w] - predictedIm
            val denominator = magnitude + kotlin.math.abs(predictedMagnitude)
            unpredictability[w] = if (denominator > 0) sqrt(dx * dx + dy * dy) / denominator else 1.0
            pastRe[1][w] = re1
            pastIm[1][w] = im1
            pastRe[0][w] = re[w].toDouble()
            pastIm[0][w] = im[w].toDouble()
        }
        for (w in PREDICTED_LINES..512) unpredictability[w] = HIGH_UNPREDICTABILITY

        val layout = long
        partitionEnergy.fill(0.0)
        partitionTonal.fill(0.0)
        for (w in 0 until 512) {
            val p = layout.partitionOfLine[w]
            partitionEnergy[p] += energy[w]
            partitionTonal[p] += energy[w] * unpredictability[w]
        }

        val past = previousThreshold[channel]
        for (b in 0 until layout.partitions) {
            var spreadEnergy = 0.0
            var spreadTonal = 0.0
            val row = layout.spreading[b]
            for (k in layout.spreadFrom[b] until layout.spreadTo[b]) {
                val weight = row[k]
                spreadEnergy += partitionEnergy[k] * weight
                spreadTonal += partitionTonal[k] * weight
            }
            val tonality = if (spreadEnergy > 0) {
                (-0.299 - 0.43 * ln((spreadTonal / spreadEnergy).coerceAtLeast(1e-12))).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val snr = maxOf(layout.minimumSnr[b], tonality * TONE_MASKING_NOISE + (1 - tonality) * NOISE_MASKING_NOISE)
            val masked = spreadEnergy * layout.normalisation[b] * 10.0.pow(-snr / 10.0)
            // Pre-echo control: the threshold may not jump far above the
            // previous two granules', because noise spread backwards in time
            // ahead of a sudden onset is not masked by it.
            val controlled = minOf(masked, 2.0 * past[0][b], 16.0 * past[1][b])
            val limited = if (past[1][b] == 0.0) masked else controlled
            past[1][b] = past[0][b]
            past[0][b] = masked
            threshold[b] = maxOf(layout.quiet[b], limited)
        }

        // Carry each partition's threshold to the scalefactor bands, spread
        // evenly across its lines, and compare it with the bands' energy.
        bandEnergy.fill(0.0)
        bandThreshold.fill(0.0)
        for (w in 0 until 512) {
            val p = layout.partitionOfLine[w]
            val band = longBandOfLine[w]
            bandEnergy[band] += energy[w]
            bandThreshold[band] += threshold[p] / layout.lines[p]
        }
        for (band in 0 until 22) result.longRatio[band] = ratio(bandThreshold[band], bandEnergy[band])
    }

    private fun analyseShort(pcm: DoubleArray, centre: Int, result: Result) {
        val layout = short
        for (window in 0 until 3) {
            val start = centre - 192 + 192 * window - 128
            for (i in 0 until 256) {
                re[i] = (pcm[start + i] * HANN_256[i]).toFloat()
                im[i] = 0f
            }
            shortFft.transform(re, im)
            partitionEnergy.fill(0.0, 0, layout.partitions)
            for (w in 0 until 128) {
                val r = re[w].toDouble()
                val i = im[w].toDouble()
                energy[w] = r * r + i * i
                partitionEnergy[layout.partitionOfLine[w]] += energy[w]
            }
            for (b in 0 until layout.partitions) {
                var spreadEnergy = 0.0
                val row = layout.spreading[b]
                for (k in layout.spreadFrom[b] until layout.spreadTo[b]) spreadEnergy += partitionEnergy[k] * row[k]
                // A short block is chosen because the signal is changing, so
                // it is treated as noise rather than predicted.
                val snr = maxOf(layout.minimumSnr[b], NOISE_MASKING_NOISE)
                threshold[b] = maxOf(layout.quiet[b], spreadEnergy * layout.normalisation[b] * 10.0.pow(-snr / 10.0))
            }
            bandEnergy.fill(0.0, 0, 13)
            bandThreshold.fill(0.0, 0, 13)
            for (w in 0 until 128) {
                val p = layout.partitionOfLine[w]
                val band = shortBandOfLine[w]
                bandEnergy[band] += energy[w]
                bandThreshold[band] += threshold[p] / layout.lines[p]
            }
            for (band in 0 until 13) result.shortRatio[window][band] = ratio(bandThreshold[band], bandEnergy[band])
        }
    }

    /**
     * Looks for a sharp rise in high-passed energy in the part of the signal
     * that a short block centred at [centre] would cover.
     */
    private fun detectAttack(pcm: DoubleArray, centre: Int, channel: Int): Boolean {
        // An onset from centre - 176 to centre + 400 is better coded by this
        // block's short windows than by any long window around them: before
        // that range the previous block would have to be short, and after it
        // the following block's long window starts. The eight segments before
        // the range are kept for comparison.
        val history = segments[channel]
        val state = filterState[channel]
        val from = centre - 176 - SEGMENT * 8
        // The filter state runs continuously only when the encoder calls with
        // centres 576 apart, which it does; each call filters 576 new samples
        // and keeps the rest of the history.
        val newSegments = 576 / SEGMENT
        history.copyInto(history, 0, newSegments, SEGMENT_HISTORY)
        val firstNew = from + (SEGMENT_HISTORY - newSegments) * SEGMENT
        for (s in 0 until newSegments) {
            var sum = 0.0
            val base = firstNew + s * SEGMENT
            for (i in 0 until SEGMENT) {
                val x = pcm[base + i]
                // A second-order difference, which keeps the energy above a
                // few kilohertz where an onset stands out from what precedes it.
                val y = x - 2 * state[0] + state[1]
                state[1] = state[0]
                state[0] = x
                sum += y * y
            }
            history[SEGMENT_HISTORY - newSegments + s] = sum
        }
        // An onset is a segment far louder than the loudest of the eight
        // before it, inside the region the short windows cover.
        for (s in 8 until SEGMENT_HISTORY) {
            var before = 0.0
            for (k in s - 8 until s) before = maxOf(before, history[k])
            if (history[s] > ATTACK_RATIO * before + ATTACK_FLOOR) return true
        }
        return false
    }

    /**
     * The partitions of one FFT size: their lines, Bark values, spreading
     * matrix, normalisation and threshold in quiet.
     */
    private class Layout(size: Int, sampleRate: Int) {
        val partitionOfLine: IntArray
        val partitions: Int
        val lines: IntArray
        val spreading: Array<DoubleArray>
        val spreadFrom: IntArray
        val spreadTo: IntArray
        val normalisation: DoubleArray
        val minimumSnr: DoubleArray
        val quiet: DoubleArray

        init {
            val half = size / 2
            val lineHz = sampleRate.toDouble() / size
            partitionOfLine = IntArray(half)
            val starts = ArrayList<Int>()
            var current = -1
            var startBark = 0.0
            for (w in 0 until half) {
                val z = bark((w + 0.5) * lineHz)
                if (current < 0 || z - startBark >= PARTITION_BARK) {
                    current++
                    starts += w
                    startBark = bark(w * lineHz)
                }
                partitionOfLine[w] = current
            }
            partitions = current + 1
            starts += half
            lines = IntArray(partitions) { starts[it + 1] - starts[it] }
            val centreBark = DoubleArray(partitions) {
                bark((starts[it] + starts[it + 1]) * 0.5 * lineHz)
            }
            spreading = Array(partitions) { maskee ->
                DoubleArray(partitions) { masker -> spread(centreBark[maskee] - centreBark[masker]) }
            }
            spreadFrom = IntArray(partitions) { b -> (0 until partitions).first { spreading[b][it] > 0 } }
            spreadTo = IntArray(partitions) { b -> (0 until partitions).last { spreading[b][it] > 0 } + 1 }
            normalisation = DoubleArray(partitions) { b -> 1.0 / spreading[b].sum() }
            minimumSnr = DoubleArray(partitions) { b -> if (centreBark[b] < 13.0) 15.0 else 6.0 }
            // A full-scale sine through this window and transform has this
            // much energy, and is taken to play at 96 dB SPL.
            val fullScale = 1.5 * (size / 4.0) * (size / 4.0)
            quiet = DoubleArray(partitions) { b ->
                var sum = 0.0
                for (w in starts[b] until starts[b + 1]) {
                    val db = thresholdInQuiet(maxOf(w, 1) * lineHz) - FULL_SCALE_SPL
                    sum += fullScale * 10.0.pow(db / 10.0)
                }
                sum
            }
        }
    }

    private companion object {
        /** Lines above this are taken as unpredictable, as Annex D does. */
        const val PREDICTED_LINES = 206
        const val HIGH_UNPREDICTABILITY = 0.4

        /** Annex D's tone-masking-noise and noise-masking-noise, in dB. */
        const val TONE_MASKING_NOISE = 29.0
        const val NOISE_MASKING_NOISE = 6.0

        const val PARTITION_BARK = 1.0 / 3.0
        const val FULL_SCALE_SPL = 96.0

        /**
         * Where the threshold in quiet stops rising, in dB SPL. Terhardt's curve
         * climbs steeply above 12 kHz, and taken literally it would let the
         * encoder drop everything above 16 kHz below -30 dBFS, which some
         * listeners do hear.
         */
        const val ATH_CEILING = 20.0

        const val SEGMENT = 64
        const val SEGMENT_HISTORY = 9 + 8
        const val ATTACK_RATIO = 10.0
        const val ATTACK_FLOOR = 1e-3

        val HANN_1024 = DoubleArray(1024) { 0.5 - 0.5 * cos(2 * PI * (it + 0.5) / 1024) }
        val HANN_256 = DoubleArray(256) { 0.5 - 0.5 * cos(2 * PI * (it + 0.5) / 256) }

        fun bark(hz: Double): Double = Hearing.bark(hz)

        /** The threshold in quiet in dB SPL, held at [ATH_CEILING] at the top. */
        fun thresholdInQuiet(hz: Double): Double = Hearing.absoluteThresholdDb(hz).coerceAtMost(ATH_CEILING)

        /**
         * Annex D's spreading function, from a masker [dz] Bark below the
         * maskee, as a power ratio: Schroeder's curve, evaluated at 1.05
         * times the distance, with the annex's extra dip just above the
         * masker.
         */
        fun spread(dz: Double): Double {
            val x = 1.05 * dz
            val dip = 8.0 * minOf((x - 0.5) * (x - 0.5) - 2.0 * (x - 0.5), 0.0)
            val y = Hearing.spreadingDb(x)
            return if (y < -60.0) 0.0 else 10.0.pow((dip + y) / 10.0)
        }

        fun bandOf(bands: IntArray, line: Int, last: Int): Int {
            for (band in 0 until last) if (line < bands[band + 1]) return band
            return last
        }

        fun ratio(threshold: Double, energy: Double): Double =
            if (energy <= 0) 1.0 else (threshold / energy).coerceAtMost(1.0)
    }
}

package io.github.yuroyami.audiokodec.internal.psy

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Finds the sudden onsets that a transform coder has to switch to short
 * blocks for.
 *
 * A long transform block spreads its coding noise over its whole length. A
 * note that starts halfway through one therefore has noise in front of it, in
 * what was quiet a moment before, and nothing masks noise that comes before
 * the sound that should hide it. That is pre-echo, and short blocks are the
 * cure, at the price of coarser frequency resolution.
 *
 * The signal is split into octave bands from [lowestHz] up, each band's
 * energy is measured over short sub-blocks, and a sub-block whose energy in
 * any band exceeds [ratio] times that band's average over the few sub-blocks
 * before it is an onset. Looking band by band matters: a steady loud tone in
 * one band would otherwise hide a burst of noise in the others, and it is the
 * quiet bands where pre-echo is heard.
 */
internal class TransientDetector(
    private val channels: Int,
    sampleRate: Int,
    /** Samples per sub-block. */
    val subBlock: Int,
    /** How many earlier sub-blocks the average covers. */
    private val history: Int = 8,
    /** The energy rise, as a power ratio, that counts as an onset. */
    private val ratio: Double = 8.0,
    /** The lower edge of the lowest band, in Hz. */
    lowestHz: Double = 1_500.0,
    /** Mean energy per sample below which nothing counts as an onset. */
    private val silence: Double = 1e-9,
) {
    /**
     * A second-order filter section from Robert Bristow-Johnson's audio
     * equalizer cookbook, with its state for every channel.
     */
    private inner class Section(cornerHz: Double, highPass: Boolean, rate: Int) {
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double

        init {
            val w0 = 2.0 * PI * cornerHz / rate
            val alpha = sin(w0) / (2.0 * BUTTERWORTH_Q)
            val c = cos(w0)
            val a0 = 1.0 + alpha
            val edge = if (highPass) 1.0 + c else 1.0 - c
            b0 = edge / 2.0 / a0
            b1 = (if (highPass) -edge else edge) / a0
            b2 = edge / 2.0 / a0
            a1 = -2.0 * c / a0
            a2 = (1.0 - alpha) / a0
        }

        private val x1 = DoubleArray(channels)
        private val x2 = DoubleArray(channels)
        private val y1 = DoubleArray(channels)
        private val y2 = DoubleArray(channels)

        fun filter(channel: Int, x: Double): Double {
            val y = b0 * x + b1 * x1[channel] + b2 * x2[channel] - a1 * y1[channel] - a2 * y2[channel]
            x2[channel] = x1[channel]
            x1[channel] = x
            y2[channel] = y1[channel]
            y1[channel] = y
            return y
        }
    }

    /**
     * Each band's filter sections: two high-passes and, below the top band,
     * two low-passes. Two sections at each edge fall 24 dB an octave, enough
     * that a loud tone an octave outside a band does not drown an onset in it.
     */
    private val filters = ArrayList<Array<Section>>()

    init {
        val top = 0.45 * sampleRate
        var low = minOf(lowestHz, top / 2)
        while (true) {
            val high = 2.0 * low
            val sections = ArrayList<Section>()
            repeat(2) { sections.add(Section(low, true, sampleRate)) }
            val last = high >= top
            if (!last) repeat(2) { sections.add(Section(high, false, sampleRate)) }
            filters.add(sections.toTypedArray())
            if (last) break
            low = high
        }
    }

    private val bands = filters.size
    private val energy = DoubleArray(bands)
    private val recent = Array(bands) { DoubleArray(history) }
    private var seen = 0

    /**
     * Takes the next sub-block, `samples[c][from until from + subBlock]` for
     * each channel `c`, and says whether an onset falls in it.
     */
    fun next(samples: Array<FloatArray>, from: Int): Boolean {
        energy.fill(0.0)
        for (c in 0 until channels) {
            val data = samples[c]
            for (i in from until from + subBlock) {
                val x = data[i].toDouble()
                for (band in 0 until bands) {
                    var y = x
                    for (section in filters[band]) y = section.filter(c, y)
                    energy[band] += y * y
                }
            }
        }
        val known = minOf(seen, history)
        val quiet = silence * subBlock * channels
        var onset = false
        for (band in 0 until bands) {
            var before = 0.0
            for (i in 0 until known) before += recent[band][i]
            before = if (known > 0) before / known else 0.0
            recent[band][seen % history] = energy[band]
            if (known > 0 && energy[band] > quiet && energy[band] > ratio * maxOf(before, quiet)) onset = true
        }
        seen++
        // The very first sub-block has nothing before it to rise from. What
        // precedes a stream is not silence anyone hears, so it is no onset.
        return onset
    }

    private companion object {
        const val BUTTERWORTH_Q = 0.707_106_781_186_547_6
    }
}

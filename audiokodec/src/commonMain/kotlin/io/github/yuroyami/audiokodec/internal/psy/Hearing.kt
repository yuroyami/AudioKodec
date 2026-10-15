package io.github.yuroyami.audiokodec.internal.psy

import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The published curves that describe human hearing, as a perceptual coder
 * needs them.
 *
 * A lossy encoder hides its noise where the ear will not hear it, and these
 * are the measurements that say where that is: how the cochlea spaces
 * frequencies, how quiet a tone has to be before it disappears in silence,
 * and how far a loud sound spreads its cover to the frequencies around it.
 * Every function here is a closed-form fit from the literature, named where
 * it is defined, and none of them belongs to one codec.
 */
internal object Hearing {

    /**
     * Converts a frequency to the Bark scale, Zwicker and Terhardt's 1980 fit.
     *
     * One Bark is roughly one critical band: the width over which the ear adds
     * energy together instead of hearing two sounds. It is close to linear
     * below 500 Hz and close to logarithmic above.
     */
    fun bark(hz: Double): Double {
        val ratio = hz / 7_500.0
        return 13.0 * atan(0.000_76 * hz) + 3.5 * atan(ratio * ratio)
    }

    /**
     * The equivalent rectangular bandwidth of the auditory filter centred on
     * [hz], from Glasberg and Moore, 1990.
     */
    fun erb(hz: Double): Double = 24.7 * (4.37e-3 * hz + 1.0)

    /** The number of ERBs below [hz], the scale [erb] integrates to. */
    fun erbRate(hz: Double): Double = 21.4 * log10(4.37e-3 * hz + 1.0)

    /**
     * The absolute threshold of hearing at [hz], in dB SPL: the level below
     * which a tone is inaudible in silence. Terhardt's 1979 fit.
     *
     * It is lowest, a few dB below zero, around 3 to 4 kHz, where the ear
     * canal resonates, and rises steeply at both ends. Below 20 Hz the fit
     * is evaluated at 20 Hz, where it is already far above anything a coder
     * has to care about.
     */
    fun absoluteThresholdDb(hz: Double): Double {
        val khz = maxOf(hz, 20.0) / 1_000.0
        val dip = khz - 3.3
        return 3.64 * khz.pow(-0.8) - 6.5 * exp(-0.6 * dip * dip) + 1e-3 * khz.pow(4.0)
    }

    /**
     * How much a masker at one place masks a sound [deltaBark] above it, in
     * dB, from Schroeder, Atal and Hall, 1979.
     *
     * The function peaks at zero for a sound at the masker's own frequency and
     * falls away about 10 dB per Bark upwards and 25 dB per Bark downwards:
     * a loud sound hides higher frequencies much better than lower ones.
     */
    fun spreadingDb(deltaBark: Double): Double {
        val x = deltaBark + 0.474
        return 15.81 + 7.5 * x - 17.5 * sqrt(1.0 + x * x)
    }

    /**
     * The simpler spreading function of straight lines on the Bark scale,
     * [lowerDbPerBark] towards lower frequencies and [upperDbPerBark] towards
     * higher ones, after Zwicker and Fastl's masking patterns at moderate
     * levels.
     *
     * Near the masker it falls faster than [spreadingDb], which matters to a
     * coder below a strong low note: the smooth curve lets noise sit only a
     * dozen dB under the note an octave lower, where a listener with good low
     * frequency reproduction can hear it.
     */
    fun linearSpreadingDb(
        deltaBark: Double,
        lowerDbPerBark: Double = 27.0,
        upperDbPerBark: Double = 10.0,
    ): Double = if (deltaBark < 0) lowerDbPerBark * deltaBark else -upperDbPerBark * deltaBark
}

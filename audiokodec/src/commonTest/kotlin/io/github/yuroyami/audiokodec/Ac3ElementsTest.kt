package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.ac3.Ac3Census
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks, on every target, AC-3 decoding of frames that use the elements no
 * encoder writes: block switching, delta bit allocation, dynamic range words,
 * phase flags, master coupling coordinates, dual mono and the rest.
 *
 * The streams are in [Ac3RandomFixtures] with what ffmpeg's decoder gives for
 * them. Decoded with ffmpeg's fixed-point rounding reproduced, which
 * [Ac3Exact] does, the two agree to float rounding; the bound is a little
 * wider than the JVM oracle suite's 1e-6 because Kotlin/JS keeps a Float in a
 * double until it is stored, so a rounding step can fall the other way there.
 * Decoded as the library does, without that rounding, they agree to what the
 * rounding can do: see `Ac3RandomOracleTest` for how the bound is worked out.
 */
class Ac3ElementsTest {

    @Test
    fun framesThatUseEveryElementDecodeToWhatFfmpegDecodesThemTo() {
        for (fixture in Ac3RandomFixtures.ALL) {
            val what = fixture.name
            val channels = fixture.channels
            val census = Ac3Census()
            val exact = Ac3Exact.decode(fixture.bytes, census)
            assertEquals(2 * 1536 * channels, exact.size, what)
            val plain = AudioKodec.decode(fixture.bytes)
            assertEquals(2 * 1536, plain.frames, what)
            assertEquals(fixture.rate, plain.format.sampleRate, what)
            assertEquals(channels, plain.format.channels, what)
            for (i in fixture.digest.indices) {
                val f = (i / channels) * 8
                val c = i % channels
                val expected = fixture.digest[i] / 16777216.0
                val ours = exact[f * channels + c].toDouble()
                assertTrue(abs(ours - expected) <= EXACT_BOUND, "$what: frame $f channel $c is $ours, ffmpeg's is $expected")
                val library = plain.samples[f * channels + c].toDouble()
                assertTrue(abs(library - expected) <= ROUNDING_BOUND, "$what: the library's frame $f channel $c is $library, ffmpeg's is $expected")
            }
            for (syncframe in 0 until 2) {
                for (c in 0 until channels) {
                    var energy = 0.0
                    for (f in syncframe * 1536 until (syncframe + 1) * 1536) {
                        energy += plain.samples[f * channels + c].toDouble().let { it * it }
                    }
                    val rms = sqrt(energy / 1536).toFloat()
                    val expected = fixture.rms[syncframe * channels + c]
                    assertTrue(abs(rms - expected) <= 1e-4f + 1e-3f * expected, "$what: syncframe $syncframe channel $c has RMS $rms, ffmpeg's is $expected")
                }
            }
            for (feature in fixture.uses) {
                val used = when (feature) {
                    "sw" -> census.shortBlocks
                    "cpl" -> census.coupledBlocks
                    "remat" -> census.rematrixedBlocks
                    "phase" -> census.phaseFlagBlocks
                    "delta" -> census.deltaBlocks
                    "dynrng" -> census.dynamicRangeWords
                    "master" -> census.masterCoordinates
                    else -> error("$what: unknown feature $feature")
                }
                assertTrue(used > 0, "$what: the frames no longer use $feature: $census")
            }
        }
    }

    private companion object {
        /** Float rounding, and the fixed-point rounding on targets that keep a Float wider than 32 bits. */
        const val EXACT_BOUND = 4e-6

        /** What the fixed-point rounding of the reference can do to a decode that does not reproduce it, as `Ac3RandomOracleTest` derives. */
        const val ROUNDING_BOUND = 3e-4
    }
}

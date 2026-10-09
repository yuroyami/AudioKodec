package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.ac3.Ac3Census
import io.github.yuroyami.audiokodec.internal.ac3.Ac3Frame
import io.github.yuroyami.audiokodec.internal.ac3.Ac3Probe
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks the AC-3 decoder against ffmpeg's on frames that use every element
 * the format has, which no encoder writes: block switching, delta bit
 * allocation, dynamic range words, phase flags, dual mono, every exponent
 * strategy, coupling in every shape, skip fields and the rest of the bit
 * stream information.
 *
 * [Ac3FrameWriter] picks the elements at random and packs a valid frame of
 * noise; both decoders must give the same samples. As in
 * [Ac3DecodeOracleTest], ffmpeg's rounding of coefficients is reproduced
 * through [Ac3Probe] so the comparison can be tight, and dither flags are
 * left clear. A census of what the frames used is checked as well, so that
 * the test cannot pass by never reaching a feature.
 */
class Ac3RandomOracleTest {

    @Test
    fun framesThatUseEveryElementDecodeAsFfmpegDecodesThem() {
        val ffmpeg = findOnPath("ffmpeg")
        assumeTrue("ffmpeg is required for the AC-3 oracle", ffmpeg != null)
        val census = Ac3Census()
        var seed = 1L
        for (sampleRateCode in 0..2) {
            for (codingMode in 0..7) {
                for (hasLfe in listOf(false, true)) {
                    val name = "random_${sampleRateCode}_${codingMode}_${if (hasLfe) 1 else 0}"
                    val frames = (0 until FRAMES).map { Ac3FrameWriter(seed++).frame(sampleRateCode, codingMode, hasLfe, minimumSizeCode = 20) }
                    val stream = frames.fold(ByteArray(0)) { a, b -> a + b }
                    val file = File(workDirectory, "$name.ac3")
                    file.writeBytes(stream)

                    val ours = Ac3Exact.decode(stream, census)
                    val theirs = decodeWithFfmpeg(ffmpeg!!, file)
                    assertEquals(theirs.size, ours.size, "$name: length")
                    var worst = 0.0
                    var at = -1
                    for (i in theirs.indices) {
                        val difference = abs(ours[i].toDouble() - theirs[i])
                        if (difference > worst) {
                            worst = difference
                            at = i
                        }
                    }
                    assertTrue(worst < BOUND, "$name: largest difference $worst at sample $at (frame ${at / 1536 / Ac3Frame.parse(stream, 0)!!.channels})")

                    // The public decoder gives the same frames without ffmpeg's rounding, within what it can do.
                    val plain = AudioKodec.decode(stream)
                    assertEquals(theirs.size, plain.samples.size, "$name: public length")
                    var plainWorst = 0.0
                    for (i in theirs.indices) plainWorst = maxOf(plainWorst, abs(plain.samples[i].toDouble() - theirs[i]))
                    assertTrue(plainWorst < ROUNDING_BOUND, "$name: the public decode differs by $plainWorst")
                }
            }
        }
        println("random AC-3 frames used: $census")
        assertTrue(census.shortBlocks > 100, "block switching: $census")
        assertTrue(census.coupledBlocks > 100, "coupling: $census")
        assertTrue(census.rematrixedBlocks > 20, "rematrixing: $census")
        assertTrue(census.phaseFlagBlocks > 5, "phase flags: $census")
        assertTrue(census.deltaBlocks > 100, "delta bit allocation: $census")
        assertTrue(census.skipFields > 20, "skip fields: $census")
        assertTrue(census.dynamicRangeWords > 100, "dynamic range: $census")
        assertTrue(census.masterCoordinates > 50, "master coupling coordinates: $census")
    }

    @Test
    fun theCommittedFixturesAreWhatTheWriterWritesAndFfmpegDecodesThemAlike() {
        val ffmpeg = findOnPath("ffmpeg")
        for (fixture in Ac3RandomFixtures.ALL) {
            val written = fixture.seeds.map { seed ->
                Ac3FrameWriter(seed).frame(fixture.sampleRateCode, fixture.codingMode, fixture.hasLfe, minimumSizeCode = 20)
            }.fold(ByteArray(0)) { a, b -> a + b }
            assertContentEquals(written, fixture.bytes, "${fixture.name}: the bytes are not what the writer writes for ${fixture.seeds}")
            if (ffmpeg == null) continue
            val file = File(workDirectory, "fixture_${fixture.name}.ac3")
            file.writeBytes(fixture.bytes)
            val theirs = decodeWithFfmpeg(ffmpeg, file)
            for (i in fixture.digest.indices) {
                val frame = (i / fixture.channels) * 8
                val expected = fixture.digest[i] / 16777216.0
                val actual = theirs[frame * fixture.channels + i % fixture.channels].toDouble()
                assertTrue(abs(expected - actual) <= 1.0 / 16777216, "${fixture.name}: the digest is not what ffmpeg decodes (entry $i)")
            }
        }
    }

    private fun decodeWithFfmpeg(ffmpeg: String, file: File): FloatArray {
        val raw = File(workDirectory, file.nameWithoutExtension + ".f32")
        val process = ProcessBuilder(ffmpeg, "-y", "-v", "error", "-f", "ac3", "-i", file.path, "-f", "f32le", "-c:a", "pcm_f32le", raw.path).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "ffmpeg did not finish")
        assertEquals(0, process.exitValue(), "ffmpeg failed on ${file.name}:\n$output")
        assertEquals("", output.trim(), "ffmpeg complained about ${file.name}")
        val buffer = ByteBuffer.wrap(raw.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private companion object {
        const val FRAMES = 4

        /** Float rounding, as in the encoder-written files: the samples here are far smaller than full scale. */
        const val BOUND = 2e-6

        /**
         * What the rounding of coefficients can do to a decode that does not reproduce it. Each of the 256
         * coefficients of a block is cut down by up to one step of 2^-23 when dequantised and again when decoupled,
         * a sample sums 256 of them from each of two overlapping blocks and doubles the sum, so noise-like frames
         * that code every bin can reach 2 * 2 * 256 * 2 * 2^-23, which is 2.4e-4. The largest seen is 7e-5.
         */
        const val ROUNDING_BOUND = 3e-4

        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-ac3-random").also { it.mkdirs() }

        fun findOnPath(tool: String): String? {
            val path = System.getenv("PATH") ?: return null
            for (directory in path.split(File.pathSeparator)) {
                val candidate = File(directory, tool)
                if (candidate.canExecute()) return candidate.absolutePath
            }
            return null
        }
    }
}

package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagField
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks AudioKodec's Vorbis decoder against the reference one.
 *
 * Vorbis is lossy, so there is no original to compare against: the only right
 * answer is whatever the reference decoder produces from the same file. These
 * tests encode with libvorbis, decode with both, and require agreement to
 * within the last bits of a 32-bit float.
 *
 * Both decoders compute the same transform by different routes, so a handful of
 * least significant bits will always differ. What must not differ is anything
 * else: the sample count, the alignment, the channel order or the amplitude.
 *
 * Every test skips itself when ffmpeg is missing.
 */
class VorbisOracleTest {

    private val ffmpeg: String? by lazy { findOnPath("ffmpeg") }

    /** How far a sample may be from the reference before the decode is wrong. */
    private val tolerance = 1e-4f

    /*
     * A note on lengths. An Ogg page states how many samples of final output
     * exist once it has been decoded, and the last page of the file is what
     * says how long the file is. AudioKodec honours that, which is what makes
     * one file follow another with no gap. ffmpeg's raw output does not always
     * agree: it keeps an encoder's tail padding on some files and drops real
     * samples from the end of others, depending on how the pages fall. So the
     * length is checked against what went into the encoder, which libvorbis
     * preserves exactly, and ffmpeg is used for the sample values.
     */

    @Test
    fun vorbisDecodesLikeTheReferenceDecoder() {
        val ffmpeg = ffmpeg ?: return skip()
        for (quality in intArrayOf(0, 4, 9)) {
            for (channels in intArrayOf(1, 2)) {
                for (rate in intArrayOf(8_000, 44_100, 48_000)) {
                    val what = "Vorbis q$quality, $channels channels, $rate Hz"
                    val frames = rate
                    val signal = sweep(frames, channels, rate)
                    val ogg = encode(ffmpeg, signal, channels, rate, quality, "q${quality}_${channels}_$rate")

                    val reference = decodeWithFfmpeg(ffmpeg, ogg, channels)
                    val decoded = AudioKodec.decode(ogg.readBytes())

                    assertEquals(frames, decoded.frames, "$what: frame count")
                    assertClose(reference, decoded.samples, what)
                }
            }
        }
    }

    /**
     * A signal full of transients, which is what makes an encoder switch
     * between its two block sizes.
     *
     * Every window shape and every overlap between a long block and a short one
     * only happens in a file like this, and getting one of them wrong is
     * inaudible on a steady tone.
     */
    @Test
    fun vorbisDecodesShortBlocksAndBlockSwitchesLikeTheReference() {
        val ffmpeg = ffmpeg ?: return skip()
        val rate = 44_100
        val frames = rate
        val signal = FloatArray(frames)
        var state = 12_345
        for (i in 0 until frames) {
            state = state * 1_664_525 + 1_013_904_223
            val noise = ((state ushr 8).toFloat() / (1 shl 23).toFloat()) - 1f
            // Clicks every 1000 samples, silence between, plus a little noise.
            val click = if (i % 1_000 < 3) 0.9f else 0f
            signal[i] = click + noise * 0.02f
        }
        val ogg = encode(ffmpeg, signal, 1, rate, 6, "transients")
        val reference = decodeWithFfmpeg(ffmpeg, ogg, 1)
        val decoded = AudioKodec.decode(ogg.readBytes())
        assertEquals(frames, decoded.frames, "transients: frame count")
        assertClose(reference, decoded.samples, "transients")
    }

    @Test
    fun aVeryShortVorbisFileDecodes() {
        val ffmpeg = ffmpeg ?: return skip()
        for (frames in intArrayOf(1, 64, 500, 2_048)) {
            val what = "Vorbis of $frames frames"
            val signal = sweep(frames, 1, 44_100)
            val ogg = encode(ffmpeg, signal, 1, 44_100, 5, "short$frames")
            val reference = decodeWithFfmpeg(ffmpeg, ogg, 1)
            val decoded = AudioKodec.decode(ogg.readBytes())
            assertEquals(frames, decoded.frames, "$what: frame count")
            assertClose(reference, decoded.samples, what)
        }
    }

    @Test
    fun aSurroundVorbisFileComesOutInAudioKodecOrder() {
        val ffmpeg = ffmpeg ?: return skip()
        val rate = 48_000
        val frames = rate / 2
        // One tone per channel, an octave apart, so a swapped pair is obvious.
        val channels = 6
        val signal = FloatArray(frames * channels)
        for (c in 0 until channels) {
            val frequency = 200.0 * (c + 1)
            for (f in 0 until frames) {
                signal[f * channels + c] =
                    (0.5 * kotlin.math.sin(2.0 * Math.PI * frequency * f / rate)).toFloat()
            }
        }
        val ogg = encode(ffmpeg, signal, channels, rate, 8, "surround")

        val info = AudioKodec.probe(ogg.readBytes())
        assertEquals(channels, info.format.channels, "surround: channels")
        assertTrue(
            info.format.channelLayout == ChannelLayout.SURROUND_5_1,
            "surround: layout was ${info.format.channelLayout}",
        )

        val reference = decodeWithFfmpeg(ffmpeg, ogg, channels)
        val decoded = AudioKodec.decode(ogg.readBytes())
        assertEquals(frames, decoded.frames, "surround: frame count")
        assertClose(reference, decoded.samples, "surround")
    }

    @Test
    fun vorbisHeadersReadWhatTheEncoderWrote() {
        val ffmpeg = ffmpeg ?: return skip()
        val signal = sweep(30_000, 2, 44_100)
        val ogg = encode(ffmpeg, signal, 2, 44_100, 5, "headers")
        val info = AudioKodec.probe(ogg.readBytes())
        assertEquals(AudioContainer.OGG, info.container, "container")
        assertEquals(AudioCodec.VORBIS, info.codec, "codec")
        assertEquals(44_100, info.format.sampleRate, "sample rate")
        assertEquals(2, info.format.channels, "channels")
        assertEquals(30_000L, info.durationFrames, "duration")
        assertTrue(info.isVariableBitrate, "Vorbis is a variable bitrate format")
        assertNotNull(info.bitrate, "bitrate")
        assertEquals(null, info.bitDepth, "Vorbis has no stored bit depth")
    }

    /**
     * Every seek target across a whole file.
     *
     * Where the pages fall decides which branch a seek takes, and a handful of
     * targets can miss a branch entirely. This one walks the file in small
     * steps so that landing on a page boundary, inside a page, on a page
     * holding one packet and on a page holding many all happen.
     */
    @Test
    fun everySeekTargetAcrossAFileLandsRight() {
        val ffmpeg = ffmpeg ?: return skip()
        val rate = 44_100
        val frames = rate * 4
        val signal = sweep(frames, 1, rate)
        val ogg = encode(ffmpeg, signal, 1, rate, 7, "seeksweep")
        val whole = AudioKodec.decode(ogg.readBytes())
        assertEquals(frames, whole.frames, "frame count")

        AudioKodec.open(ogg.readBytes()).use { decoder ->
            var target = 0L
            while (target < frames) {
                assertEquals(target, decoder.seek(target), "seek to $target")
                val block = assertNotNull(decoder.nextFrame(), "a block after seeking to $target")
                assertEquals(target, block.positionFrames, "position after seeking to $target")
                val count = minOf(block.frameCount, frames - target.toInt())
                var worst = 0f
                for (i in 0 until count) {
                    worst = maxOf(worst, abs(block.samples[i] - whole.samples[target.toInt() + i]))
                }
                assertTrue(worst < tolerance, "seeking to $target gave samples off by $worst")
                target += 719
            }
        }
    }

    /**
     * Seeking backwards, and past the end.
     *
     * A decoder that only ever moves forward can pass a seek test by accident,
     * because the samples it would have produced anyway are the right ones.
     */
    @Test
    fun seekingBackwardsAndPastTheEndBehaves() {
        val ffmpeg = ffmpeg ?: return skip()
        val frames = 60_000
        val signal = sweep(frames, 2, 44_100)
        val ogg = encode(ffmpeg, signal, 2, 44_100, 5, "seekback")
        val whole = AudioKodec.decode(ogg.readBytes())
        assertEquals(frames, whole.frames, "frame count")

        AudioKodec.open(ogg.readBytes()).use { decoder ->
            for (target in longArrayOf(50_000, 3_000, 40_000, 0, 59_000, 12_345)) {
                assertEquals(target, decoder.seek(target), "seek to $target")
                val block = assertNotNull(decoder.nextFrame(), "a block after seeking to $target")
                val count = minOf(block.frameCount, frames - target.toInt())
                var worst = 0f
                for (i in 0 until count * 2) {
                    worst = maxOf(worst, abs(block.samples[i] - whole.samples[target.toInt() * 2 + i]))
                }
                assertTrue(worst < tolerance, "seeking to $target gave samples off by $worst")
            }

            // Past the end lands on the end and produces nothing more.
            assertEquals(frames.toLong(), decoder.seek(frames * 10L), "seeking past the end")
            assertEquals(null, decoder.nextFrame(), "there is nothing after the end")
        }
    }

    @Test
    fun seekingIntoVorbisLandsOnTheRightSample() {
        val ffmpeg = ffmpeg ?: return skip()
        val rate = 44_100
        val frames = rate * 3
        val signal = sweep(frames, 1, rate)
        val ogg = encode(ffmpeg, signal, 1, rate, 5, "seek")
        val reference = decodeWithFfmpeg(ffmpeg, ogg, 1)

        AudioKodec.open(ogg.readBytes()).use { decoder ->
            for (target in longArrayOf(0, 1, 4_095, 40_000, 100_000, 130_000)) {
                if (target >= reference.size) continue
                assertEquals(target, decoder.seek(target), "seek to $target")
                assertEquals(target, decoder.positionFrames, "position after seeking to $target")
                val block = assertNotNull(decoder.nextFrame(), "a block after seeking to $target")
                assertEquals(target, block.positionFrames, "the block's own position")
                val count = minOf(block.frameCount, reference.size - target.toInt())
                var worst = 0f
                for (i in 0 until count) {
                    worst = maxOf(worst, abs(block.samples[i] - reference[target.toInt() + i]))
                }
                // A seek restarts the overlap, so the first block after one is
                // reconstructed from a fresh start rather than continued. The
                // reference decoder does exactly the same, and the two agree.
                assertTrue(
                    worst < tolerance,
                    "seeking to $target gave samples off by $worst",
                )
            }
        }
    }

    @Test
    fun everySampleArrivesWhenBlocksArePulledOneAtATime() {
        val ffmpeg = ffmpeg ?: return skip()
        val signal = sweep(70_000, 2, 44_100)
        val ogg = encode(ffmpeg, signal, 2, 44_100, 4, "pull")
        val reference = decodeWithFfmpeg(ffmpeg, ogg, 2)
        val gathered = FloatArray(70_000 * 2)
        var written = 0
        AudioKodec.open(ogg.readBytes()).use { decoder ->
            while (true) {
                val block = decoder.nextFrame() ?: break
                assertEquals(
                    written / 2L, block.positionFrames,
                    "a block's position follows the one before it",
                )
                block.samples.copyInto(gathered, written, 0, block.sampleCount)
                written += block.sampleCount
            }
        }
        assertEquals(70_000 * 2, written, "samples gathered")
        assertClose(reference, gathered, "pulled one block at a time")
    }

    @Test
    fun aVorbisFileKeepsItsTagsThroughAnEdit() {
        val ffmpeg = ffmpeg ?: return skip()
        val signal = sweep(20_000, 1, 44_100)
        val ogg = encode(ffmpeg, signal, 1, 44_100, 5, "tags")
        val before = AudioKodec.decode(ogg.readBytes())

        val edited = AudioKodec.writeTags(
            ogg.readBytes(),
            AudioKodec.readTags(ogg.readBytes()).edit {
                set(TagField.TITLE, "A rewritten title")
                set(TagField.ARTIST, "Someone")
            },
        )
        val file = temp("tags-edited.ogg")
        file.writeBytes(edited)

        val read = AudioKodec.readTags(edited)
        assertEquals("A rewritten title", read[TagField.TITLE], "the title survived")
        assertEquals("Someone", read[TagField.ARTIST], "the artist survived")

        // The audio has to be untouched, which means the reference decoder
        // still accepts the file and AudioKodec still decodes it identically.
        val referenceAfter = decodeWithFfmpeg(ffmpeg, file, 1)
        val after = AudioKodec.decode(edited)
        assertEquals(before.frames, after.frames, "the frame count survived")
        assertClose(referenceAfter, after.samples, "the reference still decodes the edited file")
        for (i in 0 until before.samples.size) {
            assertEquals(before.samples[i], after.samples[i], "sample $i changed")
        }
    }

    private fun sweep(frames: Int, channels: Int, rate: Int): FloatArray {
        val out = FloatArray(frames * channels)
        for (c in 0 until channels) {
            val start = 200.0 * (c + 1)
            for (f in 0 until frames) {
                val t = f / rate.toDouble()
                val phase = 2.0 * Math.PI * (start * t + 2_000.0 * t * t)
                out[f * channels + c] = (0.7 * kotlin.math.sin(phase)).toFloat()
            }
        }
        return out
    }

    private fun encode(
        ffmpeg: String,
        signal: FloatArray,
        channels: Int,
        rate: Int,
        quality: Int,
        name: String,
    ): File {
        val wav = temp("$name.wav")
        wav.writeBytes(
            AudioKodec.encode(signal, AudioFormat(rate, channels), EncodeTarget.wav(16)),
        )
        val ogg = temp("$name.ogg")
        ogg.delete()
        run(
            listOf(
                ffmpeg, "-y", "-v", "quiet", "-i", wav.absolutePath,
                "-c:a", "libvorbis", "-q:a", quality.toString(), ogg.absolutePath,
            ),
            "encoding $name",
        )
        return ogg
    }

    private fun decodeWithFfmpeg(ffmpeg: String, ogg: File, channels: Int): FloatArray {
        val raw = temp("${ogg.nameWithoutExtension}.raw")
        raw.delete()
        run(
            listOf(
                ffmpeg, "-y", "-v", "quiet", "-i", ogg.absolutePath,
                "-f", "f32le", "-acodec", "pcm_f32le", raw.absolutePath,
            ),
            "decoding ${ogg.name}",
        )
        val bytes = raw.readBytes()
        val samples = FloatArray(bytes.size / 4)
        for (i in samples.indices) {
            var value = 0
            for (b in 0 until 4) value = value or ((bytes[i * 4 + b].toInt() and 0xFF) shl (8 * b))
            samples[i] = Float.fromBits(value)
        }
        if (channels < 1) throw IllegalStateException("channels")
        return samples
    }

    private fun assertClose(expected: FloatArray, actual: FloatArray, what: String) {
        val count = minOf(expected.size, actual.size)
        var worst = 0f
        var worstAt = -1
        var squared = 0.0
        for (i in 0 until count) {
            val difference = abs(expected[i] - actual[i])
            if (difference > worst) {
                worst = difference
                worstAt = i
            }
            squared += difference.toDouble() * difference
        }
        val rms = sqrt(squared / count.coerceAtLeast(1))
        assertTrue(
            worst < tolerance,
            "$what: sample $worstAt is off by $worst (root mean square $rms) against the reference decoder",
        )
    }

    private fun run(command: List<String>, what: String) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(180, TimeUnit.SECONDS)
        assertTrue(finished, "${command.first()} did not finish within three minutes")
        assertEquals(0, process.exitValue(), "$what: ${command.joinToString(" ")} failed:\n$output")
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private fun skip() {
        org.junit.Assume.assumeTrue("ffmpeg is not on the path, so the Vorbis comparison did not run", false)
    }

    private companion object {
        val workDirectory: File = File(
            System.getProperty("java.io.tmpdir"), "audiokodec-vorbis-oracle",
        ).also { it.mkdirs() }

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

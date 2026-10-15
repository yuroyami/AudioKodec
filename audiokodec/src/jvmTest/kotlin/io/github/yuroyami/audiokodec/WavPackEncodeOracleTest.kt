package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks the WavPack encoder against the reference `wavpack` and
 * `wvunpack`, built from the commit the port follows (see
 * `reference/REFERENCES.md`) in `reference/wavpack/build`, or found on the
 * path.
 *
 * Three claims, each made for every mode, depth and channel count. The file
 * is valid: `wvunpack` verifies every block's checksums and the stored MD5
 * sum. It holds the audio that went in: `wvunpack` gives back exactly the
 * input integers, and so does AudioKodec's own decoder. And it is the file
 * the reference writes: `wavpack -m --no-threads` given the same samples
 * as raw PCM writes the same bytes. The last is what makes the first two
 * mean something beyond these signals.
 *
 * Every test skips itself when the tools are missing.
 */
class WavPackEncodeOracleTest {

    @Test
    fun everyModeDepthAndChannelCountIsWhatTheReferenceWrites() {
        val tools = tools()
        for (effort in 0..3) {
            for (bits in intArrayOf(8, 16, 24, 32)) {
                for (channels in intArrayOf(1, 2, 6, 8)) {
                    val what = "effort $effort, $bits-bit, $channels channels"
                    val frames = 30_000 + channels * 7
                    check(tools, what, Kind.MUSIC, channels, bits, frames, effort)
                }
            }
        }
    }

    @Test
    fun silenceFalseStereoAndFullScaleNoiseAreWrittenAsTheReferenceWritesThem() {
        val tools = tools()
        for (effort in 0..3) {
            for (bits in intArrayOf(8, 16, 24, 32)) {
                check(tools, "silence, effort $effort, $bits-bit", Kind.SILENCE, 2, bits, 25_000, effort)
                check(tools, "silent mono, effort $effort, $bits-bit", Kind.SILENCE, 1, bits, 9_000, effort)
                check(tools, "false stereo, effort $effort, $bits-bit", Kind.FALSE_STEREO, 2, bits, 40_000, effort)
                check(tools, "full-scale noise, effort $effort, $bits-bit", Kind.NOISE, 2, bits, 30_000, effort)
                check(tools, "full-scale noise 5.1, effort $effort, $bits-bit", Kind.NOISE, 6, bits, 12_000, effort)
                check(tools, "low bits unused, effort $effort, $bits-bit", Kind.SHIFTED, 2, bits, 30_000, effort)
                check(tools, "silence then music, effort $effort, $bits-bit", Kind.LATE_START, 2, bits, 50_000, effort)
            }
        }
    }

    @Test
    fun aFilterThatRunsAwayIsRepackedAsTheReferenceRepacksIt() {
        val tools = tools()
        // Abrupt switches between full-scale patterns make the high modes'
        // longer filters produce residuals beyond what the reference allows,
        // and it packs those blocks again with five terms. These seeds were
        // found by searching for blocks that do that, in mono and in stereo.
        for (effort in 0..3) {
            for (channels in 1..2) {
                val what = "runaway, $channels channels, effort $effort"
                val file = check(tools, what, Kind.RUNAWAY, channels, 16, 30_000, effort)
                // A repacked block states five terms. In very high mode, whose
                // filter has sixteen, nothing else leaves five.
                if (effort == 3) assertTrue(5 in termCounts(file), "$what: no block was repacked")
            }
        }
    }

    @Test
    fun shortAndLongFilesAreWrittenAsTheReferenceWritesThem() {
        val tools = tools()
        // Fewer samples than one block, the lengths where the last block is
        // split in two, and a file of many blocks.
        for (frames in intArrayOf(1, 2, 7, 17, 1_000, 2_047, 2_049, 11_025, 11_026, 16_537, 16_538, 22_051)) {
            check(tools, "$frames frames", Kind.MUSIC, 2, 16, frames, 1)
            check(tools, "$frames frames mono, high", Kind.MUSIC, 1, 24, frames, 2)
        }
        check(tools, "a long file", Kind.MUSIC, 2, 16, 44_100 * 12 + 123, 1)
        check(tools, "a long file, very high", Kind.MUSIC, 2, 24, 44_100 * 8 + 5, 3)
        check(tools, "a long mono file, fast", Kind.MUSIC, 1, 16, 44_100 * 10, 0)
    }

    @Test
    fun everyLayoutAndRateIsWrittenAsTheReferenceWritesIt() {
        val tools = tools()
        // The reference gives raw PCM the first channels of the WAV mask.
        for (channels in 1..8) {
            check(tools, "$channels channels", Kind.MUSIC, channels, 16, 20_000, 1)
        }
        // Other layouts, named to the reference in its own terms.
        val named = listOf(
            ChannelLayout.of(AudioChannel.FRONT_LEFT) to "FL",
            ChannelLayout.of(AudioChannel.FRONT_CENTER, AudioChannel.TOP_CENTER) to "FC,TC",
            ChannelLayout.QUAD to "FL,FR,BL,BR",
            ChannelLayout.SURROUND_5_0 to "FL,FR,FC,BL,BR",
            ChannelLayout.SURROUND_7_1 to "FL,FR,FC,LFE,BL,BR,SL,SR",
            ChannelLayout.of(AudioChannel.FRONT_LEFT_OF_CENTER, AudioChannel.FRONT_RIGHT_OF_CENTER, AudioChannel.SIDE_LEFT, AudioChannel.SIDE_RIGHT, AudioChannel.TOP_FRONT_LEFT, AudioChannel.TOP_FRONT_RIGHT) to "FLC,FRC,SL,SR,TFL,TFR",
            ChannelLayout.of(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.UNKNOWN) to "FL,FR,...",
            ChannelLayout.unknown(3) to "...",
            ChannelLayout.unknown(8) to "...",
        )
        for ((layout, order) in named) {
            check(tools, "layout $order, ${layout.count}", Kind.MUSIC, layout.count, 16, 20_000, 1, layout = layout, options = listOf("--channel-order=$order"))
        }
        // Rates outside WavPack's table go in a metadata item of their own.
        for (rate in intArrayOf(8_000, 11_025, 22_050, 37_800, 44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 384_000)) {
            check(tools, "$rate Hz", Kind.MUSIC, 2, 16, 30_000, 1, rate = rate)
            check(tools, "$rate Hz, high", Kind.MUSIC, 1, 24, 30_000, 2, rate = rate)
        }
    }

    @Test
    fun anUnknownLengthIsStatedAndCorrectedAsTheReferenceDoes() {
        val tools = tools()
        for ((channels, bits) in listOf(2 to 16, 1 to 24, 6 to 16)) {
            val what = "unknown length, $channels channels, $bits-bit"
            val frames = 33_333
            val samples = signal(Kind.MUSIC, channels, bits, frames)
            val format = AudioFormat(RATE, rawLayout(channels))
            val pcm = rawPcm(samples, bits)

            // From a pipe, the reference states the length as unknown and
            // corrects the first block when it is done.
            val reference = temp("unknown-reference-$channels-$bits.wv")
            val process = ProcessBuilder(
                listOf(tools.wavpack, "-q", "-y", "-m", "--no-threads", "--raw-pcm=$RATE,${rawFormat(bits)},$channels,le", "-", "-o", reference.path),
            ).redirectErrorStream(true).oracleStart()
            process.outputStream.use { it.write(pcm) }
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "$what: wavpack did not finish")
            assertEquals(0, process.exitValue(), "$what: wavpack failed:\n$output")

            val source = unknownLength(samples.asIntPcmSource(format, bits))
            val sink = ByteArrayAudioSink()
            AudioKodec.encode(source, EncodeTarget.wavPack(bits), sink)
            val ours = sink.toByteArray()
            assertBytes(reference.readBytes(), ours, what)
            verify(tools, ours, samples, channels, bits, frames, RATE, what)

            // A sink that cannot be patched leaves the length unknown, which
            // the reference reads by walking the blocks.
            val streaming = StreamingSink()
            AudioKodec.encode(unknownLength(samples.asIntPcmSource(format, bits)), EncodeTarget.wavPack(bits), streaming)
            val unpatched = streaming.bytes()
            assertEquals(-1, ByteBuffer.wrap(unpatched, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int, "$what: the stated length")
            verify(tools, unpatched, samples, channels, bits, frames, RATE, "$what, unpatched", restoredWav = false)
        }
    }

    @Test
    fun floatSourcesAreQuantisedBeforeTheyAreWritten() {
        val tools = tools()
        val frames = 20_000
        val floats = FloatArray(frames * 2) { i ->
            val t = (i / 2).toDouble() / RATE
            (0.6 * sin(2 * PI * (330.0 + 220 * (i % 2)) * t) + 0.3 * sin(2 * PI * 41.0 * t)).toFloat()
        }
        for (bits in intArrayOf(8, 16, 24, 32)) {
            val what = "float source to $bits-bit"
            val scale = (1L shl (bits - 1)).toDouble()
            val expected = IntArray(floats.size) { kotlin.math.round(floats[it] * scale).coerceIn(-scale, scale - 1).toInt() }
            val file = AudioKodec.encode(floats, AudioFormat(RATE, 2), EncodeTarget.wavPack(bits))
            verify(tools, file, expected, 2, bits, frames, RATE, what)
        }
    }

    @Test
    fun tagsAreWrittenAfterTheAudioAndReadByTheReference() {
        val tools = tools()
        val samples = signal(Kind.MUSIC, 2, 16, 25_000)
        val tags = TagBundle(
            mapOf(
                TagField.TITLE to listOf("Encoded Title ✓"),
                TagField.ARTIST to listOf("Encoded Artist"),
                TagField.TRACK_NUMBER to listOf("4"),
            ),
        )
        val file = AudioKodec.encode(samples, AudioFormat(RATE, 2), 16, EncodeTarget.wavPack(), tags)
        verify(tools, file, samples, 2, 16, 25_000, RATE, "tagged")
        val written = temp("tagged.wv")
        written.writeBytes(file)
        assertEquals("Encoded Title ✓", capture(listOf(tools.wvunpack, "-q", "--no-utf8-convert", "-x", "Title", written.path)).trim())
        assertEquals("Encoded Artist", capture(listOf(tools.wvunpack, "-q", "--no-utf8-convert", "-x", "Artist", written.path)).trim())

        // A tag edit leaves every block of the audio as it was, which the
        // reference's verification of the stored MD5 sum confirms as well.
        val edited = AudioKodec.writeTags(file, AudioKodec.readTags(file).edit { set(TagField.TITLE, "After, and longer than before") })
        val audioEnd = blocksEnd(file)
        assertBytes(file.copyOf(audioEnd), edited.copyOf(audioEnd), "the blocks after a tag edit")
        assertEquals(audioEnd, blocksEnd(edited), "the blocks after a tag edit")
        verify(tools, edited, samples, 2, 16, 25_000, RATE, "after a tag edit")
        val rewritten = temp("retagged.wv")
        rewritten.writeBytes(edited)
        assertEquals("After, and longer than before", capture(listOf(tools.wvunpack, "-q", "--no-utf8-convert", "-x", "Title", rewritten.path)).trim())
    }

    // Helpers.

    private enum class Kind { MUSIC, SILENCE, FALSE_STEREO, NOISE, SHIFTED, LATE_START, RUNAWAY }

    private class Tools(val wavpack: String, val wvunpack: String)

    private fun tools(): Tools {
        val dir = File(System.getProperty("user.dir"))
        fun built(name: String) = findOnPath(name)
            ?: listOf(dir, dir.parentFile).map { File(it, "reference/wavpack/build/$name") }.firstOrNull { it.canExecute() }?.absolutePath
        val wavpack = built("wavpack")
        val wvunpack = built("wvunpack")
        assumeTrue("wavpack and wvunpack are required for the WavPack encoder oracle", wavpack != null && wvunpack != null)
        return Tools(wavpack!!, wvunpack!!)
    }

    /**
     * Encodes a [kind] of signal both ways and checks the result: the same
     * bytes as the reference, then [verify].
     */
    private fun check(
        tools: Tools,
        what: String,
        kind: Kind,
        channels: Int,
        bits: Int,
        frames: Int,
        effort: Int,
        rate: Int = RATE,
        layout: ChannelLayout = rawLayout(channels),
        options: List<String> = emptyList(),
    ): ByteArray {
        val samples = signal(kind, channels, bits, frames)
        val stem = what.replace(Regex("[^A-Za-z0-9]+"), "_")
        val raw = temp("$stem.raw")
        raw.writeBytes(rawPcm(samples, bits))
        val reference = temp("$stem.reference.wv")
        val mode = listOf(listOf("-f"), emptyList(), listOf("-h"), listOf("-hh"))[effort]
        run(listOf(tools.wavpack, "-q", "-y", "-m", "--no-threads") + mode + options + listOf("--raw-pcm=$rate,${rawFormat(bits)},$channels,le", raw.path, "-o", reference.path), what)

        val format = AudioFormat(rate, layout)
        val ours = AudioKodec.encode(samples, format, bits, EncodeTarget.wavPack(bits, effort))
        val oursFile = temp("$stem.wv")
        oursFile.writeBytes(ours)
        assertBytes(reference.readBytes(), ours, what)
        val partlyNamed = !layout.isPositional && layout.channels.any { it != AudioChannel.UNKNOWN }
        verify(tools, ours, samples, channels, bits, frames, rate, what, restoredWav = !partlyNamed)
        return ours
    }

    /**
     * Checks [file] with `wvunpack`: its verification of every block and of
     * the MD5 sum, the integers it decodes, and the WAV file it restores
     * from the stored header; then AudioKodec's decoding and probing of it.
     */
    private fun verify(
        tools: Tools,
        file: ByteArray,
        samples: IntArray,
        channels: Int,
        bits: Int,
        frames: Int,
        rate: Int,
        what: String,
        restoredWav: Boolean = true,
    ) {
        val stem = what.replace(Regex("[^A-Za-z0-9]+"), "_")
        val wv = temp("$stem.check.wv")
        wv.writeBytes(file)
        val verified = capture(listOf(tools.wvunpack, "-q", "-m", "-v", wv.path), what)
        val sums = Regex("(original|unpacked) md5:\\s+([0-9a-f]{32})").findAll(verified).map { it.groupValues[2] }.toList()
        assertEquals(2, sums.size, "$what: wvunpack reported no stored MD5 sum:\n$verified")
        assertEquals(sums[0], sums[1], "$what: MD5 sums")

        val raw = temp("$stem.out.raw")
        run(listOf(tools.wvunpack, "-q", "-y", "--raw", wv.path, "-o", raw.path), what)
        val reference = fromRaw(raw.readBytes(), bits)
        assertEquals(samples.size, reference.size, "$what: wvunpack's length")
        for (i in samples.indices) {
            if (samples[i] != reference[i]) throw AssertionError("$what: wvunpack gives ${reference[i]} for sample $i, not ${samples[i]}")
        }

        // The stored RIFF header restores a WAV file that holds the audio.
        // With an odd number of bytes of audio, `wvunpack` pads the data
        // chunk without counting the pad in the RIFF size, in the reference's
        // own files as in these, so that WAV is not checked. Nor is the WAV
        // of a file whose length was never corrected, whose header promises
        // the most a RIFF file holds, or whose mask names fewer speakers than
        // it has channels, as the reference's headers do; [restoredWav] says
        // which.
        if (restoredWav && (frames.toLong() * channels * (bits / 8)) % 2 == 0L) {
            val wav = temp("$stem.out.wav")
            run(listOf(tools.wvunpack, "-q", "-y", wv.path, "-o", wav.path), what)
            val restored = AudioKodec.decode(wav.readBytes())
            assertEquals(frames, restored.frames, "$what: frames of the restored WAV")
            assertEquals(channels, restored.format.channels, "$what: channels of the restored WAV")
        }

        val info = AudioKodec.probe(file)
        assertEquals(AudioContainer.WAVPACK, info.container, what)
        assertEquals(AudioCodec.WAVPACK, info.codec, what)
        assertEquals(frames.toLong(), info.durationFrames, "$what: duration")
        assertEquals(rate, info.format.sampleRate, "$what: rate")
        assertEquals(channels, info.format.channels, "$what: channels")
        assertEquals(bits, info.bitDepth, "$what: depth")
        val ours = decodeInts(file)
        assertEquals(samples.size, ours.size, "$what: AudioKodec's length")
        for (i in samples.indices) {
            if (samples[i] != ours[i]) throw AssertionError("$what: AudioKodec gives ${ours[i]} for sample $i, not ${samples[i]}")
        }
    }

    private fun assertBytes(expected: ByteArray, actual: ByteArray, what: String) {
        if (expected.contentEquals(actual)) return
        val at = (0 until minOf(expected.size, actual.size)).firstOrNull { expected[it] != actual[it] } ?: minOf(expected.size, actual.size)
        throw AssertionError("$what: ${actual.size} bytes differ from the reference's ${expected.size} first at byte $at")
    }

    /** How many decorrelation terms each block of audio states. */
    private fun termCounts(file: ByteArray): List<Int> {
        val counts = ArrayList<Int>()
        var at = 0
        while (at < blocksEnd(file)) {
            val end = at + ByteBuffer.wrap(file, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int + 8
            var item = at + 32
            while (item < end) {
                val id = file[item].toInt() and 0xFF
                var size = (file[item + 1].toInt() and 0xFF) shl 1
                item += 2
                if (id and 0x80 != 0) {
                    size += ((file[item].toInt() and 0xFF) shl 9) + ((file[item + 1].toInt() and 0xFF) shl 17)
                    item += 2
                }
                if (id and 0x3F == 0x2) counts += size - (if (id and 0x40 != 0) 1 else 0)
                item += size
            }
            at = end
        }
        return counts
    }

    /** Where the last WavPack block ends, which is where a tag would start. */
    private fun blocksEnd(file: ByteArray): Int {
        var at = 0
        while (at + 8 <= file.size && file[at] == 'w'.code.toByte() && file[at + 1] == 'v'.code.toByte()) {
            at += ByteBuffer.wrap(file, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int + 8
        }
        return at
    }

    /**
     * Seeded test signals at [bits] bits: music-like tones, sweeps, noise
     * and clicks, or the edge cases [kind] names.
     */
    private fun signal(kind: Kind, channels: Int, bits: Int, frames: Int): IntArray {
        if (kind == Kind.RUNAWAY) {
            // Mono from one seed; stereo from another, its right channel the
            // left one sample late.
            val source = segments(if (channels == 1) 8 else 28, frames, bits)
            return IntArray(frames * channels) { i ->
                val f = i / channels
                if (i % channels == 0) source[f] else if (f == 0) 0 else source[f - 1]
            }
        }
        val random = Random(0x5EED_0000L + channels * 100 + bits + frames.toLong() * 31 + kind.ordinal * 7)
        val max = ((1L shl (bits - 1)) - 1).toDouble()
        val out = IntArray(frames * channels)
        fun clamp(value: Double): Int = kotlin.math.round(value * max).coerceIn(-max - 1, max).toInt()
        for (f in 0 until frames) {
            val t = f.toDouble() / RATE
            for (c in 0 until channels) {
                out[f * channels + c] = when (kind) {
                    Kind.SILENCE -> 0
                    Kind.FALSE_STEREO -> if (c == 0) clamp(0.5 * sin(2 * PI * 440 * t) + 0.1 * (random.nextDouble() - 0.5)) else out[f * channels]
                    Kind.NOISE -> when (random.nextInt(16)) {
                        0 -> (-max - 1).toInt()
                        1 -> max.toInt()
                        else -> ((random.nextLong() shr (64 - bits)).toInt())
                    }
                    Kind.SHIFTED -> clamp(0.4 * sin(2 * PI * (300 + 50 * c) * t) + 0.05 * (random.nextDouble() - 0.5)) and (if (bits > 8) 0xFF.inv() else 0x3.inv())
                    Kind.LATE_START -> if (f < 30_000) 0 else clamp(0.5 * sin(2 * PI * (220 + 110 * c) * t) + 0.1 * (random.nextDouble() - 0.5))
                    Kind.RUNAWAY -> error("made above")
                    Kind.MUSIC -> clamp(
                        0.25 * sin(2 * PI * (220.0 * (c + 2) / 2) * t) +
                            0.15 * sin(2 * PI * (100 + (700 + 150 * c) * t) * t) +
                            0.1 * (random.nextDouble() - 0.5) +
                            (if ((f + c * 1_000) % 13_000 < 40) 0.4 * (random.nextDouble() - 0.5) else 0.0),
                    )
                }
            }
        }
        return out
    }

    /**
     * The layout the reference gives raw PCM: the canonical mono and
     * stereo, and the first speakers of the WAV mask beyond.
     */
    private fun rawLayout(channels: Int): ChannelLayout =
        if (channels <= 2) ChannelLayout.canonical(channels) else ChannelLayout.fromWaveMask((1 shl channels) - 1, channels)

    /** Random runs of full-scale and quiet patterns, from [seed]. */
    private fun segments(seed: Long, frames: Int, bits: Int): IntArray {
        val random = Random(seed)
        val max = (1 shl (bits - 1)) - 1
        val min = -max - 1
        val out = IntArray(frames)
        var i = 0
        var last = 0
        while (i < frames) {
            val length = 1 + random.nextInt(if (random.nextBoolean()) 20 else 3000)
            val pattern = random.nextInt(8)
            val amplitude = if (random.nextBoolean()) max else random.nextInt(max) + 1
            val period = 2 + random.nextInt(40)
            for (k in 0 until length) {
                if (i >= frames) break
                val value = when (pattern) {
                    0 -> if (k % 2 == 0) amplitude else -amplitude - 1
                    1 -> if ((k / period) % 2 == 0) amplitude else -amplitude - 1
                    2 -> last
                    3 -> last + (random.nextInt(2001) - 1000) * (amplitude / 1000 + 1)
                    4 -> ((k % period) * 2L * amplitude / period - amplitude).toInt()
                    5 -> if (k == 0) amplitude else 0
                    6 -> random.nextInt(2 * amplitude + 1) - amplitude
                    else -> (amplitude * sin(k * 6.283 / period)).toInt()
                }
                last = value.coerceIn(min, max)
                out[i++] = last
            }
        }
        return out
    }

    private fun rawFormat(bits: Int): String = if (bits == 8) "8u" else "${bits}s"

    private fun rawPcm(samples: IntArray, bits: Int): ByteArray {
        val bytes = bits / 8
        val out = ByteArray(samples.size * bytes)
        for (i in samples.indices) {
            val value = if (bits == 8) samples[i] + 128 else samples[i]
            for (b in 0 until bytes) out[i * bytes + b] = (value ushr (8 * b)).toByte()
        }
        return out
    }

    private fun fromRaw(raw: ByteArray, bits: Int): IntArray {
        val bytes = bits / 8
        return IntArray(raw.size / bytes) { i ->
            var value = 0
            for (b in 0 until bytes) value = value or ((raw[i * bytes + b].toInt() and 0xFF) shl (8 * b))
            if (bits == 8) value - 128 else (value shl (32 - bits)) shr (32 - bits)
        }
    }

    private fun decodeInts(bytes: ByteArray): IntArray {
        AudioKodec.open(bytes).use { decoder ->
            val access = decoder as IntPcmAccess
            val out = ArrayList<Int>()
            while (true) {
                val frame = access.nextIntFrame() ?: break
                for (i in 0 until frame.frameCount * decoder.format.channels) out += frame.samples[i]
            }
            return out.toIntArray()
        }
    }

    private fun unknownLength(source: IntPcmSource): IntPcmSource = object : IntPcmSource by source {
        override val frameCount: Long? get() = null
    }

    private class StreamingSink : AudioSink {
        private val inner = ByteArrayAudioSink()
        override val canPatch: Boolean = false
        override val position: Long get() = inner.position
        override fun write(bytes: ByteArray, offset: Int, count: Int) = inner.write(bytes, offset, count)
        override fun patchAt(position: Long, bytes: ByteArray, offset: Int, count: Int) = error("not patchable")
        override fun close() = Unit
        fun bytes(): ByteArray = inner.toByteArray()
    }

    private fun run(command: List<String>, what: String) {
        capture(command, what)
    }

    private fun capture(command: List<String>, what: String = ""): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(180, TimeUnit.SECONDS), "${command.first()} did not finish within three minutes")
        assertEquals(0, process.exitValue(), "$what: ${command.joinToString(" ")} failed:\n$output")
        return output
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private companion object {
        const val RATE = 44_100

        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-wavpack-encode").also { it.mkdirs() }

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

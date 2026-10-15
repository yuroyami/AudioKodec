package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checks MP3 encoding on every target by decoding the result with
 * AudioKodec's own decoder.
 *
 * MP3 is lossy, so a round trip cannot be exact. What it can be is exactly
 * the right length, lined up with the input, free of NaN, and recognisably
 * the same tone. The JVM oracle suite measures quality against ffmpeg and
 * libmpg123; these are kept short so that they run quickly on JS and Wasm.
 */
class Mp3EncodeTest {

    @Test
    fun aToneComesBackAtItsLengthAtEveryRateAndShape() {
        for ((rate, channels) in listOf(48_000 to 2, 44_100 to 1, 32_000 to 2)) {
            for (target in listOf(EncodeTarget.mp3(128_000), EncodeTarget.mp3Vbr(), EncodeTarget.mp3(96_000, jointStereo = false))) {
                val what = "$rate Hz, $channels ch, $target"
                val frames = rate / 4 + 123
                val input = tone(frames, channels, rate)
                val bytes = AudioKodec.encode(input, AudioFormat(rate, channels), target)
                val info = AudioKodec.probe(bytes)
                assertEquals(AudioContainer.MPEG_AUDIO, info.container, what)
                assertEquals(AudioCodec.MP3, info.codec, what)
                assertEquals(rate, info.format.sampleRate, what)
                assertEquals(channels, info.format.channels, what)
                assertEquals(frames.toLong(), info.durationFrames, what)
                val decoded = AudioKodec.decode(bytes)
                assertEquals(frames, decoded.frames, what)
                assertTrue(decoded.samples.all { it.isFinite() }, "$what: NaN or infinity")
                val snr = snr(input, decoded.samples)
                assertTrue(snr > 20, "$what: SNR $snr dB")
            }
        }
    }

    @Test
    fun everyLengthDecodesToExactlyItself() {
        for (frames in listOf(1, 100, 1_151, 1_152, 1_153, 2_304 + 529)) {
            val input = tone(frames, 1, 44_100)
            val decoded = AudioKodec.decode(AudioKodec.encode(input, AudioFormat(44_100, 1), EncodeTarget.mp3(128_000)))
            assertEquals(frames, decoded.frames, "$frames frames")
        }
    }

    @Test
    fun silenceStaysSilent() {
        val decoded = AudioKodec.decode(AudioKodec.encode(FloatArray(9_000 * 2), AudioFormat(48_000, 2), EncodeTarget.mp3Vbr()))
        assertEquals(9_000, decoded.frames)
        assertTrue(decoded.samples.all { abs(it) < 1e-4f })
    }

    @Test
    fun aSinkThatCannotBePatchedGetsTheSameAudio() {
        val format = AudioFormat(44_100, 2)
        val input = tone(20_000, 2, 44_100)
        for (target in listOf(EncodeTarget.mp3(192_000), EncodeTarget.mp3Vbr())) {
            val patched = AudioKodec.encode(input, format, target)
            // With the length known in advance, the header can be written
            // right the first time.
            val streamed = StreamingSink()
            AudioKodec.encode(input.asPcmSource(format), target, streamed)
            val known = AudioKodec.decode(streamed.bytes())
            assertEquals(20_000, known.frames, "$target, length known")
            assertContentEquals(AudioKodec.decode(patched).samples, known.samples, "$target, length known")

            // Without it, only the start can be trimmed: the end keeps the
            // encoder's padding, which is silence.
            val unknown = StreamingSink()
            AudioKodec.encode(UnknownLength(input.asPcmSource(format)), target, unknown)
            val partial = AudioKodec.decode(unknown.bytes())
            assertTrue(partial.frames in 20_000..20_000 + 1_152 + 529, "$target: ${partial.frames} frames")
            for (i in 0 until 20_000 * 2) assertEquals(known.samples[i], partial.samples[i], "$target: sample $i")
        }
    }

    @Test
    fun tagsGoIntoAnId3TagAtTheFront() {
        val tags = TagBundle(mapOf(TagField.TITLE to listOf("Encoded ✓"), TagField.ARTIST to listOf("AudioKodec")))
        val input = tone(5_000, 2, 48_000)
        val plain = AudioKodec.encode(input, AudioFormat(48_000, 2), EncodeTarget.mp3(160_000))
        val tagged = AudioKodec.encode(input, AudioFormat(48_000, 2), EncodeTarget.mp3(160_000), tags)
        assertTrue(tagged.copyOfRange(0, 3).decodeToString() == "ID3")
        val read = AudioKodec.readTags(tagged)
        assertEquals("Encoded ✓", read[TagField.TITLE])
        assertEquals("AudioKodec", read[TagField.ARTIST])
        // The audio after the tag is the same bytes as without one.
        assertContentEquals(plain, tagged.copyOfRange(tagged.size - plain.size, tagged.size))
        assertContentEquals(AudioKodec.decode(plain).samples, AudioKodec.decode(tagged).samples)
    }

    @Test
    fun whatMp3CannotCarryIsRefusedByName() {
        val refusedRate = assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.encode(FloatArray(2_000), AudioFormat(22_050, 2), EncodeTarget.mp3())
        }
        assertTrue("22050" in refusedRate.feature)
        val refusedChannels = assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.encode(FloatArray(6_000), AudioFormat(48_000, 6), EncodeTarget.mp3())
        }
        assertTrue("6 channels" in refusedChannels.feature)
        assertFailsWith<IllegalArgumentException> { EncodeTarget.mp3(100_000) }
        assertFailsWith<IllegalArgumentException> { EncodeTarget.mp3Vbr(10) }
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.encode(IntArray(2_000), AudioFormat(48_000, 2), 16, EncodeTarget.mp3())
        }
    }

    private fun tone(frames: Int, channels: Int, rate: Int): FloatArray = FloatArray(frames * channels) { i ->
        val f = i / channels
        val c = i % channels
        (0.4 * sin(2 * PI * (440.0 + 110.0 * c) * f / rate) + 0.1 * sin(2 * PI * 3_000.0 * f / rate)).toFloat()
    }

    private fun snr(input: FloatArray, decoded: FloatArray): Double {
        var signal = 0.0
        var noise = 0.0
        for (i in input.indices) {
            signal += input[i].toDouble() * input[i]
            val d = decoded[i] - input[i].toDouble()
            noise += d * d
        }
        return 10 * log10(signal / maxOf(noise, 1e-30))
    }

    private class StreamingSink : AudioSink {
        private val buffer = ByteArrayAudioSink()
        override val canPatch: Boolean = false
        override val position: Long get() = buffer.position
        override fun write(bytes: ByteArray, offset: Int, count: Int) = buffer.write(bytes, offset, count)
        override fun patchAt(position: Long, bytes: ByteArray, offset: Int, count: Int) = error("not patchable")
        override fun close() = Unit
        fun bytes(): ByteArray = buffer.toByteArray()
    }

    private class UnknownLength(private val source: PcmSource) : PcmSource {
        override val format: AudioFormat get() = source.format
        override val frameCount: Long? get() = null
        override fun read(into: FloatArray, offset: Int, frames: Int): Int = source.read(into, offset, frames)
    }
}

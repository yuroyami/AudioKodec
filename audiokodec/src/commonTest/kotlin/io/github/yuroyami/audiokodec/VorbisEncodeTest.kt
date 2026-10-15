package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.vorbis.VorbisDecoder
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagChapter
import io.github.yuroyami.audiokodec.tag.TagField
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checks Vorbis encoding on every target by decoding the result with
 * AudioKodec's own decoder.
 *
 * Vorbis is lossy, so nothing here asks for the samples that went in. It
 * asks for what has to survive any lossy coding: exactly the input's length,
 * every tone at its own frequency and in its own channel, nothing that is
 * not a number, and silence as silence. The JVM oracle suite measures the
 * quality against the reference decoder, with stated bounds.
 *
 * The signals are short, so the suite stays quick on the JavaScript and
 * WebAssembly targets.
 */
class VorbisEncodeTest {

    @Test
    fun aToneComesBackAtItsFrequencyAndExactLength() {
        val cases = listOf(
            Triple(8_000, 1, 4_001),
            Triple(22_050, 2, 11_025),
            Triple(44_100, 1, 6_000),
            Triple(48_000, 2, 9_600),
        )
        for ((rate, channels, frames) in cases) {
            val what = "$channels ch at $rate Hz, $frames frames"
            val samples = tones(rate, channels, frames)
            val file = AudioKodec.encode(samples, AudioFormat(rate, channels), EncodeTarget.vorbis())
            val info = AudioKodec.probe(file)
            assertEquals(AudioContainer.OGG, info.container, what)
            assertEquals(AudioCodec.VORBIS, info.codec, what)
            assertEquals(frames.toLong(), info.durationFrames, what)
            assertEquals(AudioFormat(rate, channels), info.format, what)
            val decoded = AudioKodec.decode(file)
            assertEquals(frames, decoded.frames, what)
            assertTrue(decoded.samples.none { it.isNaN() || it.isInfinite() }, "$what: a sample is not a number")
            for (c in 0 until channels) {
                assertEquals(toneHz(c), dominant(decoded.samples, channels, c, rate), "$what: channel $c")
            }
            val snr = snrDb(samples, decoded.samples)
            assertTrue(snr > 20.0, "$what: SNR $snr dB")
        }
    }

    @Test
    fun shortInputsKeepTheirLength() {
        for (frames in intArrayOf(0, 1, 2, 63, 64, 65, 255, 256, 1_000, 2_047, 2_048, 2_049)) {
            val file = AudioKodec.encode(tones(44_100, 2, frames), AudioFormat(44_100, 2), EncodeTarget.vorbis())
            val decoded = AudioKodec.decode(file)
            assertEquals(frames, decoded.frames, "$frames frames")
            assertTrue(decoded.samples.none { it.isNaN() }, "$frames frames: a sample is not a number")
        }
    }

    @Test
    fun silenceComesBackAsSilence() {
        val file = AudioKodec.encode(FloatArray(2 * 22_050), AudioFormat(22_050, 2), EncodeTarget.vorbis())
        val decoded = AudioKodec.decode(file)
        assertEquals(22_050, decoded.frames)
        assertTrue(decoded.samples.all { it == 0f }, "silence came back with sound in it")
    }

    @Test
    fun everyQualityDecodesAndHigherQualityCostsMore() {
        val samples = tones(22_050, 1, 22_050)
        var previous = 0
        for (quality in floatArrayOf(0f, 2.5f, 5f, 7.5f, 10f)) {
            val file = AudioKodec.encode(samples, AudioFormat(22_050, 1), EncodeTarget.vorbis(quality))
            val decoded = AudioKodec.decode(file)
            assertEquals(22_050, decoded.frames, "quality $quality")
            assertEquals(toneHz(0), dominant(decoded.samples, 1, 0, 22_050), "quality $quality")
            assertTrue(file.size > previous, "quality $quality is not larger than the quality below it")
            previous = file.size
        }
    }

    /** One tone per speaker, and each has to come back in its own channel slot. */
    @Test
    fun surroundChannelsKeepTheirSlots() {
        for (channels in 3..8) {
            val layout = VorbisDecoder.layoutFor(channels)
            val samples = tones(16_000, channels, 8_000)
            val file = AudioKodec.encode(samples, AudioFormat(16_000, layout), EncodeTarget.vorbis())
            val decoded = AudioKodec.decode(file)
            assertEquals(layout, decoded.format.channelLayout, "$channels channels")
            for (c in 0 until channels) {
                assertEquals(toneHz(c), dominant(decoded.samples, channels, c, 16_000), "$channels channels: slot $c")
            }
        }
    }

    @Test
    fun encodingIsRepeatable() {
        val samples = tones(32_000, 2, 12_345)
        val first = AudioKodec.encode(samples, AudioFormat(32_000, 2), EncodeTarget.vorbis())
        val second = AudioKodec.encode(samples, AudioFormat(32_000, 2), EncodeTarget.vorbis())
        assertContentEquals(first, second)
    }

    /** The encoder never patches, so it streams to a sink that cannot seek, from a source of unknown length. */
    @Test
    fun aSourceOfUnknownLengthStreamsToASinkThatCannotPatch() {
        val samples = tones(24_000, 2, 10_000)
        var position = 0
        val source = object : PcmSource {
            override val format: AudioFormat = AudioFormat(24_000, 2)
            override val frameCount: Long? = null
            override fun read(into: FloatArray, offset: Int, frames: Int): Int {
                // Short, uneven reads, as a live source would give.
                val count = minOf(frames, 777, 10_000 - position)
                samples.copyInto(into, offset, position * 2, (position + count) * 2)
                position += count
                return count
            }
        }
        val collected = ByteArrayAudioSink()
        val sink = object : AudioSink by collected {
            override val canPatch: Boolean get() = false
            override fun patchAt(position: Long, bytes: ByteArray, offset: Int, count: Int) {
                throw AssertionError("the Vorbis encoder patched its output")
            }
        }
        AudioKodec.encode(source, EncodeTarget.vorbis(), sink)
        val reference = AudioKodec.encode(samples, AudioFormat(24_000, 2), EncodeTarget.vorbis())
        assertContentEquals(reference, collected.toByteArray())
    }

    @Test
    fun aSourceThatBreaksItsPromiseIsRefused() {
        val source = object : PcmSource {
            override val format: AudioFormat = AudioFormat(8_000, 1)
            override val frameCount: Long = 5_000
            var served = false
            override fun read(into: FloatArray, offset: Int, frames: Int): Int {
                if (served) return 0
                served = true
                return minOf(frames, 100)
            }
        }
        assertFailsWith<AudioKodecException> { AudioKodec.encode(source, EncodeTarget.vorbis()) }
    }

    @Test
    fun tagsGoIntoTheCommentHeader() {
        val tags = TagBundle(mapOf(TagField.TITLE to listOf("Ogg ✓"), TagField.ARTIST to listOf("AudioKodec")))
        val file = AudioKodec.encode(tones(8_000, 1, 4_000), AudioFormat(8_000, 1), EncodeTarget.vorbis(), tags)
        val read = AudioKodec.readTags(file)
        assertEquals(listOf("Ogg ✓"), read.fields[TagField.TITLE])
        assertEquals(listOf("AudioKodec"), read.fields[TagField.ARTIST])
        assertEquals(4_000, AudioKodec.decode(file).frames)
    }

    @Test
    fun whatVorbisCannotCarryIsRefusedByName() {
        val nine = ChannelLayout.unknown(9)
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.encode(FloatArray(9 * 100), AudioFormat(48_000, nine), EncodeTarget.vorbis())
        }
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.encode(FloatArray(100), AudioFormat(4_000, 1), EncodeTarget.vorbis())
        }
        // Seven channels name no speakers until the caller says which.
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.encode(FloatArray(7 * 100), AudioFormat(48_000, 7), EncodeTarget.vorbis())
        }
        // Vorbis stores the left and right of a pair, not a swapped pair.
        val swapped = ChannelLayout.of(AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_LEFT)
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.encode(FloatArray(200), AudioFormat(48_000, swapped), EncodeTarget.vorbis())
        }
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.encode(IntArray(100), AudioFormat(48_000, 1), 16, EncodeTarget.vorbis())
        }
        assertFailsWith<UnsupportedFeatureException> {
            val chapters = TagBundle(emptyMap(), chapters = listOf(TagChapter("a", 0, 100, "One")))
            AudioKodec.encode(FloatArray(100), AudioFormat(48_000, 1), EncodeTarget.vorbis(), chapters)
        }
        assertFailsWith<IllegalArgumentException> { EncodeTarget.vorbis(-0.5f) }
        assertFailsWith<IllegalArgumentException> { EncodeTarget.vorbis(10.5f) }
        assertFailsWith<IllegalArgumentException> { EncodeTarget.vorbis(Float.NaN) }
    }

    @Test
    fun samplesThatAreNotNumbersDoNotBreakTheEncoder() {
        val samples = tones(16_000, 1, 4_000)
        samples[100] = Float.NaN
        samples[200] = Float.POSITIVE_INFINITY
        samples[300] = -1e30f
        val decoded = AudioKodec.decode(AudioKodec.encode(samples, AudioFormat(16_000, 1), EncodeTarget.vorbis()))
        assertEquals(4_000, decoded.frames)
        assertTrue(decoded.samples.none { it.isNaN() || it.isInfinite() })
    }

    private fun toneHz(channel: Int): Double = 400.0 + 200.0 * channel

    private fun tones(rate: Int, channels: Int, frames: Int): FloatArray = FloatArray(frames * channels) {
        val c = it % channels
        (0.4 * sin(2 * PI * toneHz(c) * (it / channels) / rate)).toFloat()
    }

    private fun snrDb(original: FloatArray, decoded: FloatArray): Double {
        var signal = 0.0
        var noise = 0.0
        for (i in original.indices) {
            signal += original[i].toDouble() * original[i]
            val d = decoded[i] - original[i].toDouble()
            noise += d * d
        }
        return 10 * log10(signal / noise)
    }

    /** The frequency, in steps of 50 Hz up to 2 kHz, with the most power in [channel]. */
    private fun dominant(samples: FloatArray, channels: Int, channel: Int, rate: Int): Double {
        val frames = samples.size / channels
        var best = 0.0
        var bestPower = -1.0
        var hz = 100.0
        while (hz <= 2_000.0) {
            val coefficient = 2 * cos(2 * PI * hz / rate)
            var s1 = 0.0
            var s2 = 0.0
            for (f in 0 until frames) {
                val s = samples[f * channels + channel] + coefficient * s1 - s2
                s2 = s1
                s1 = s
            }
            val power = abs(s1 * s1 + s2 * s2 - coefficient * s1 * s2)
            if (power > bestPower) {
                bestPower = power
                best = hz
            }
            hz += 50.0
        }
        return best
    }
}

package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.opus.RangeDecoder
import io.github.yuroyami.audiokodec.internal.opus.RangeEncoder
import io.github.yuroyami.audiokodec.internal.opus.celt.CeltEnergy
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checks the Opus encoder on every target through AudioKodec's own decoder.
 *
 * Opus is lossy, so the checks here are the ones that hold whatever the
 * platform: the range coder reads back every symbol it wrote, a file decodes
 * to exactly as many frames as went in with a tone where it was put, and
 * the container carries what it should. The oracle suite on the JVM checks
 * that the packets are the ones libopus's encoder writes, and the quality
 * against the reference decoder.
 */
class OpusEncodeTest {

    @Test
    fun theRangeCoderReadsBackEverySymbolItWrote() {
        for (seed in 0 until 8) {
            val random = Random(seed)
            val ops = ArrayList<IntArray>()
            repeat(2_000) {
                ops += when (random.nextInt(7)) {
                    0 -> intArrayOf(0, random.nextInt(2), 1 + random.nextInt(15))
                    1 -> intArrayOf(1, random.nextInt(4))
                    2 -> intArrayOf(2, 2 + random.nextInt(200)).let { intArrayOf(2, random.nextInt(it[1]), it[1]) }
                    3 -> (2 + random.nextInt(1 shl 20)).let { intArrayOf(3, random.nextInt(it), it) }
                    4 -> (1 + random.nextInt(25)).let { intArrayOf(4, random.nextInt(1 shl it), it) }
                    5 -> (2 + random.nextInt(1000)).let { total ->
                        val low = random.nextInt(total)
                        intArrayOf(5, low, low + 1 + random.nextInt(total - low), total)
                    }
                    else -> intArrayOf(6, random.nextInt(41) - 20, 1 + random.nextInt(200) shl 7, 1 + random.nextInt(11_000))
                }
            }
            val buffer = ByteArray(16_000)
            val encoder = RangeEncoder(buffer)
            val tells = IntArray(ops.size)
            val fractions = IntArray(ops.size)
            for ((index, op) in ops.withIndex()) {
                when (op[0]) {
                    0 -> encoder.encodeBitLogp(op[1] == 1, op[2])
                    1 -> encoder.encodeIcdf(op[1], ICDF, 8)
                    2 -> encoder.encodeUniform(op[1], op[2])
                    3 -> encoder.encodeUniform(op[1], op[2])
                    4 -> encoder.encodeRawBits(op[1], op[2])
                    5 -> encoder.encode(op[1], op[2], op[3])
                    else -> op[1] = CeltEnergy.laplaceEncode(encoder, op[1], op[2], op[3])
                }
                tells[index] = encoder.tell()
                fractions[index] = encoder.tellFraction()
            }
            val finalRange = encoder.finalRange
            encoder.done()
            assertTrue(!encoder.error, "seed $seed: the packet overflowed")
            val decoder = RangeDecoder(buffer)
            for ((index, op) in ops.withIndex()) {
                val what = "seed $seed, symbol $index"
                when (op[0]) {
                    0 -> assertEquals(op[1] == 1, decoder.decodeBitLogp(op[2]), what)
                    1 -> assertEquals(op[1], decoder.decodeIcdf(ICDF, 8), what)
                    2, 3 -> assertEquals(op[1], decoder.decodeUniform(op[2]), what)
                    4 -> assertEquals(op[1], decoder.decodeRawBits(op[2]), what)
                    5 -> {
                        val at = decoder.decode(op[3])
                        assertTrue(at >= op[1] && at < op[2], "$what: $at outside ${op[1]} until ${op[2]}")
                        decoder.update(op[1], op[2], op[3])
                    }
                    else -> assertEquals(op[1], CeltEnergy.laplaceDecode(decoder, op[2], op[3]), what)
                }
                assertEquals(tells[index], decoder.tell(), "$what: tell")
                assertEquals(fractions[index], decoder.tellFraction(), "$what: tellFraction")
            }
            assertEquals(finalRange, decoder.finalRange, "seed $seed: final range")
            assertTrue(!decoder.error, "seed $seed: the decoder found an error")
        }
    }

    @Test
    fun filesDecodeToExactlyTheFramesThatWentIn() {
        for (channels in 1..2) {
            for ((frames, frameMillis) in listOf(1 to 20, 959 to 20, 961 to 10, 7_777 to 20, 4_800 to 10)) {
                val what = "$channels ch, $frames frames, $frameMillis ms"
                val samples = tone(frames, channels, 1_000.0, 48_000)
                val file = AudioKodec.encode(
                    samples, AudioFormat(48_000, ChannelLayout.canonical(channels)),
                    EncodeTarget.opus(96_000, frameMillis = frameMillis),
                )
                val info = AudioKodec.probe(file)
                assertEquals(AudioContainer.OGG, info.container, what)
                assertEquals(AudioCodec.OPUS, info.codec, what)
                assertEquals(frames.toLong(), info.durationFrames, what)
                val decoded = AudioKodec.decode(file)
                assertEquals(frames, decoded.frames, what)
                assertEquals(channels, decoded.format.channels, what)
                assertTrue(decoded.samples.all { it.isFinite() }, "$what: finite samples")
            }
        }
    }

    @Test
    fun aToneComesBackAtItsFrequency() {
        for (channels in 1..2) {
            for (target in listOf(EncodeTarget.opus(64_000), EncodeTarget.opus(128_000, vbr = false))) {
                val what = "$channels ch, $target"
                val frames = 9_600
                val samples = tone(frames, channels, 1_000.0, 48_000)
                val decoded = AudioKodec.decode(
                    AudioKodec.encode(samples, AudioFormat(48_000, ChannelLayout.canonical(channels)), target),
                )
                for (c in 0 until channels) {
                    val channel = FloatArray(frames - 1_920) { decoded.samples[(it + 960) * channels + c] }
                    val at = goertzel(channel, 1_000.0, 48_000)
                    for (other in doubleArrayOf(700.0, 1_300.0, 2_000.0)) {
                        val off = goertzel(channel, other, 48_000)
                        assertTrue(at > 100 * off, "$what, channel $c: 1 kHz $at against $other Hz $off")
                    }
                    var error = 0.0
                    var power = 0.0
                    for (i in channel.indices) {
                        val expected = samples[(i + 960) * channels + c]
                        error += (channel[i] - expected).toDouble() * (channel[i] - expected)
                        power += expected.toDouble() * expected
                    }
                    // In place to within a tenth of its amplitude.
                    assertTrue(error < 0.01 * power, "$what, channel $c: error $error against $power")
                }
            }
        }
    }

    @Test
    fun otherRatesAreResampledToOpusRate() {
        val frames = 5_000
        val file = AudioKodec.encode(tone(frames, 2, 440.0, 22_050), AudioFormat(22_050, ChannelLayout.STEREO), EncodeTarget.opus())
        val decoded = AudioKodec.decode(file)
        assertEquals(48_000, decoded.format.sampleRate)
        assertEquals(((frames.toLong() * 48_000 + 22_049) / 22_050).toInt(), decoded.frames)
    }

    @Test
    fun anUnpatchableSinkAndAnUnknownLengthWriteTheSameFile() {
        val samples = tone(3_000, 2, 500.0, 48_000)
        val format = AudioFormat(48_000, ChannelLayout.STEREO)
        val expected = AudioKodec.encode(samples, format, EncodeTarget.opus())
        val source = object : PcmSource {
            private var position = 0
            override val format: AudioFormat = format
            override val frameCount: Long? = null
            override fun read(into: FloatArray, offset: Int, frames: Int): Int {
                val n = minOf(frames, 700, 3_000 - position)
                samples.copyInto(into, offset, position * 2, (position + n) * 2)
                position += n
                return n
            }
        }
        val written = ByteArrayAudioSink()
        val sink = object : AudioSink by written {
            override val canPatch: Boolean get() = false
            override fun patchAt(position: Long, bytes: ByteArray, offset: Int, count: Int) =
                throw AssertionError("the Opus writer patched its sink")
        }
        AudioKodec.encode(source, EncodeTarget.opus(), sink)
        assertContentEquals(expected, written.toByteArray())
    }

    @Test
    fun tagsGoIntoTheCommentHeader() {
        val tags = TagBundle(mapOf(TagField.TITLE to listOf("Encoded title"), TagField.ARTIST to listOf("Somebody")))
        val file = AudioKodec.encode(tone(2_000, 1, 300.0, 48_000), AudioFormat(48_000, ChannelLayout.MONO), EncodeTarget.opus(), tags)
        assertEquals(tags.fields, AudioKodec.readTags(file).fields)
        assertEquals(2_000, AudioKodec.decode(file).frames)
    }

    @Test
    fun whatOpusCannotCarryIsRefused() {
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.encode(FloatArray(300), AudioFormat(48_000, ChannelLayout.SURROUND_3_0), EncodeTarget.opus())
        }
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.encode(IntArray(200), AudioFormat(48_000, ChannelLayout.STEREO), 16, EncodeTarget.opus())
        }
        assertFailsWith<IllegalArgumentException> { EncodeTarget.opus(5_999) }
        assertFailsWith<IllegalArgumentException> { EncodeTarget.opus(510_001) }
        assertFailsWith<IllegalArgumentException> { EncodeTarget.opus(frameMillis = 5) }
    }

    @Test
    fun silenceAndNonFiniteInputStaySilent() {
        val samples = FloatArray(2 * 4_000)
        for (i in 2_000 until 2_100) samples[i] = Float.NaN
        samples[3_000] = Float.POSITIVE_INFINITY
        val decoded = AudioKodec.decode(AudioKodec.encode(samples, AudioFormat(48_000, ChannelLayout.STEREO), EncodeTarget.opus()))
        assertEquals(4_000, decoded.frames)
        assertTrue(decoded.samples.all { abs(it) < 1e-4f }, "silence and a frame of NaN decode to silence")
    }

    private companion object {
        val ICDF = intArrayOf(200, 120, 30, 0)

        fun tone(frames: Int, channels: Int, frequency: Double, rate: Int): FloatArray =
            FloatArray(frames * channels) { 0.4f * sin(2 * PI * frequency * (it / channels) / rate + it % channels).toFloat() }

        fun goertzel(x: FloatArray, frequency: Double, rate: Int): Double {
            val coefficient = 2 * cos(2 * PI * frequency / rate)
            var s1 = 0.0
            var s2 = 0.0
            for (value in x) {
                val s0 = value + coefficient * s1 - s2
                s2 = s1
                s1 = s0
            }
            return s1 * s1 + s2 * s2 - coefficient * s1 * s2
        }
    }
}

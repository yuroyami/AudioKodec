package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.PictureType
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagChapter
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagPicture
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import kotlin.math.PI
import kotlin.math.round
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks WavPack encoding on every target by decoding the result.
 *
 * WavPack is lossless, so every file has to decode to exactly the integers
 * that went in, in every mode and at every depth and channel count. The
 * oracle suite checks on the JVM that the files are byte for byte what the
 * reference encoder writes, and that `wvunpack` verifies and decodes them.
 */
class WavPackEncodeTest {

    @Test
    fun everyModeDepthAndChannelCountDecodesToWhatWentIn() {
        for (effort in 0..3) {
            for (bits in intArrayOf(8, 16, 24, 32)) {
                for (channels in intArrayOf(1, 2, 3, 6, 8)) {
                    val what = "effort $effort, $bits-bit, $channels channels"
                    val frames = 12_000 + channels * 13
                    val layout = ChannelLayout.canonical(channels).takeIf { it.isPositional } ?: ChannelLayout.unknown(channels)
                    val samples = signal(channels, bits, frames)
                    val file = AudioKodec.encode(samples, AudioFormat(44_100, layout), bits, EncodeTarget.wavPack(bits, effort))
                    val info = AudioKodec.probe(file)
                    assertEquals(AudioContainer.WAVPACK, info.container, what)
                    assertEquals(AudioCodec.WAVPACK, info.codec, what)
                    assertEquals(bits, info.bitDepth, what)
                    assertEquals(frames.toLong(), info.durationFrames, what)
                    assertEquals(layout, info.format.channelLayout, what)
                    assertContentEquals(samples, decode(file), what)
                }
            }
        }
    }

    @Test
    fun theHigherModesCompressThisSignalMore() {
        // A floor rather than a boast: tones are what a predictor handles
        // best. It notices a change that makes a mode stop doing its work.
        val samples = signal(2, 16, 60_000, noise = 0.001)
        val sizes = (0..3).map { AudioKodec.encode(samples, AudioFormat(44_100, 2), 16, EncodeTarget.wavPack(16, it)).size }
        for (effort in 1..3) assertTrue(sizes[effort] <= sizes[effort - 1], "sizes by effort: $sizes")
        assertTrue(sizes[0] < samples.size * 2 * 3 / 4, "fast mode wrote ${sizes[0]} bytes for ${samples.size * 2} of audio")
    }

    @Test
    fun extremesSilenceAndIdenticalChannelsDecodeExactly() {
        for (bits in intArrayOf(8, 16, 24, 32)) {
            val max = ((1L shl (bits - 1)) - 1).toInt()
            val min = -max - 1
            val extremes = IntArray(2 * 5_000) { if ((it / 2 + it % 2) % 3 == 0) max else min }
            val silence = IntArray(2 * 5_000)
            val mono = signal(1, bits, 9_000)
            val identical = IntArray(2 * 9_000) { mono[it / 2] }
            val stereo = signal(2, bits, 30_000)
            val lateStart = IntArray(2 * 30_000) { if (it < 2 * 15_000) 0 else stereo[it] }
            for ((name, samples) in listOf("extremes" to extremes, "silence" to silence, "identical" to identical, "late start" to lateStart)) {
                for (effort in 0..3) {
                    val what = "$name, $bits-bit, effort $effort"
                    val file = AudioKodec.encode(samples, AudioFormat(48_000, 2), bits, EncodeTarget.wavPack(bits, effort))
                    assertContentEquals(samples, decode(file), what)
                }
            }
        }
    }

    @Test
    fun shortFilesAndBlockBoundariesDecodeExactly() {
        for (frames in intArrayOf(1, 2, 3, 100, 2_047, 2_048, 2_049, 11_025, 16_537, 16_538, 33_075, 33_076)) {
            val samples = signal(2, 16, frames)
            val file = AudioKodec.encode(samples, AudioFormat(44_100, 2), 16, EncodeTarget.wavPack())
            assertEquals(frames.toLong(), AudioKodec.probe(file).durationFrames, "$frames frames")
            assertContentEquals(samples, decode(file), "$frames frames")
        }
    }

    @Test
    fun oddSampleRatesAreStored() {
        for (rate in intArrayOf(1, 7_350, 8_000, 37_800, 44_100, 352_800, 768_000)) {
            val samples = signal(1, 16, 3_000)
            val file = AudioKodec.encode(samples, AudioFormat(rate, 1), 16, EncodeTarget.wavPack())
            assertEquals(rate, AudioKodec.probe(file).format.sampleRate, "$rate Hz")
            assertContentEquals(samples, decode(file), "$rate Hz")
        }
    }

    @Test
    fun layoutsAreKeptOrRefused() {
        val kept = listOf(
            ChannelLayout.MONO, ChannelLayout.STEREO, ChannelLayout.SURROUND_3_0, ChannelLayout.QUAD,
            ChannelLayout.SURROUND_5_0, ChannelLayout.SURROUND_5_1, ChannelLayout.SURROUND_7_1,
            ChannelLayout.of(AudioChannel.FRONT_LEFT),
            ChannelLayout.of(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.UNKNOWN),
            ChannelLayout.of(AudioChannel.FRONT_CENTER, AudioChannel.TOP_CENTER),
            ChannelLayout.unknown(3), ChannelLayout.unknown(8),
        )
        for (layout in kept) {
            val samples = signal(layout.count, 16, 4_000)
            val file = AudioKodec.encode(samples, AudioFormat(48_000, layout), 16, EncodeTarget.wavPack())
            assertEquals(layout, AudioKodec.probe(file).format.channelLayout, "$layout")
            assertContentEquals(samples, decode(file), "$layout")
        }
        // Speakers out of WAV order would come back in it, and a mono or
        // stereo file cannot leave its speakers unassigned.
        val refused = listOf(
            ChannelLayout.of(AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_LEFT),
            ChannelLayout.of(AudioChannel.UNKNOWN, AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT),
            ChannelLayout.unknown(1), ChannelLayout.unknown(2), ChannelLayout.unknown(9),
        )
        for (layout in refused) {
            val sink = ByteArrayAudioSink()
            assertFailsWith<IllegalArgumentException>("$layout") {
                AudioKodec.encode(IntArray(layout.count * 10).asIntPcmSource(AudioFormat(48_000, layout), 16), EncodeTarget.wavPack(), sink)
            }
            assertEquals(0L, sink.position, "$layout")
        }
    }

    @Test
    fun floatInputIsQuantisedToTheTargetDepth() {
        val floats = FloatArray(2 * 9_000) { (0.7 * sin(2 * PI * (it / 2) * (440.0 + 110 * (it % 2)) / 44_100)).toFloat() }
        floats[0] = 1f
        floats[1] = -1f
        floats[2] = Float.NaN
        for (bits in intArrayOf(8, 16, 24, 32)) {
            val scale = (1L shl (bits - 1)).toDouble()
            val file = AudioKodec.encode(floats, AudioFormat(44_100, 2), EncodeTarget.wavPack(bits))
            val decoded = decode(file)
            for (i in floats.indices) {
                val expected = if (floats[i].isNaN()) 0 else round(floats[i] * scale).coerceIn(-scale, scale - 1).toInt()
                assertEquals(expected, decoded[i], "$bits-bit sample $i")
            }
        }
        // Dither adds at most a step either way before rounding.
        val dithered = decode(AudioKodec.encode(floats, AudioFormat(44_100, 2), EncodeTarget.wavPack(16, dither = Dither.Tpdf())))
        for (i in 3 until floats.size) {
            val exact = floats[i] * 32_768.0
            assertTrue(kotlin.math.abs(dithered[i] - exact) <= 1.5, "dithered sample $i is ${dithered[i]} for $exact")
        }
    }

    @Test
    fun deeperIntegersAreReducedToTheTargetDepth() {
        val samples = signal(2, 24, 6_000)
        val file = AudioKodec.encode(samples, AudioFormat(44_100, 2), 24, EncodeTarget.wavPack(16))
        assertEquals(16, AudioKodec.probe(file).bitDepth)
        assertContentEquals(IntArray(samples.size) { samples[it] shr 8 }, decode(file))
    }

    @Test
    fun anUnknownLengthIsCorrectedWhenTheSinkAllowsIt() {
        val samples = signal(2, 16, 25_000)
        val format = AudioFormat(44_100, 2)
        val patched = ByteArrayAudioSink()
        AudioKodec.encode(unknownLength(samples.asIntPcmSource(format, 16)), EncodeTarget.wavPack(), patched)
        val file = patched.toByteArray()
        assertEquals(25_000, readU32(file, 12), "the length the first block states")
        assertContentEquals(samples, decode(file))

        val streaming = StreamingSink()
        AudioKodec.encode(unknownLength(samples.asIntPcmSource(format, 16)), EncodeTarget.wavPack(), streaming)
        val unpatched = streaming.bytes()
        assertEquals(-1, readU32(unpatched, 12), "the length a stream that cannot be patched states")
        assertEquals(25_000L, AudioKodec.probe(unpatched).durationFrames)
        assertContentEquals(samples, decode(unpatched))
        // Apart from the stated lengths and the checksum over them, the two
        // files are the same.
        assertEquals(file.size, unpatched.size)
    }

    @Test
    fun aDecodedFileSeeksToTheSampleAsked() {
        val samples = signal(2, 24, 60_000)
        val file = AudioKodec.encode(samples, AudioFormat(44_100, 2), 24, EncodeTarget.wavPack(24, 2))
        AudioKodec.open(file).use { decoder ->
            val ints = decoder as IntPcmAccess
            for (target in longArrayOf(0, 1, 22_049, 22_050, 22_051, 44_100, 59_999)) {
                assertEquals(target, decoder.seek(target))
                val frame = assertNotNull(ints.nextIntFrame())
                assertEquals(samples[target.toInt() * 2], frame.samples[0], "left at $target")
                assertEquals(samples[target.toInt() * 2 + 1], frame.samples[1], "right at $target")
            }
        }
    }

    @Test
    fun tagsAreStoredAndEditingThemLeavesTheAudioAlone() {
        val cover = TagPicture(PictureType.FRONT_COVER, "image/png", bytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3))
        val tags = TagBundle(
            mapOf(TagField.TITLE to listOf("Lossless ✓"), TagField.ARTIST to listOf("Someone"), TagField.TRACK_NUMBER to listOf("3")),
            pictures = listOf(cover),
        )
        val samples = signal(2, 16, 30_000)
        val file = AudioKodec.encode(samples, AudioFormat(44_100, 2), 16, EncodeTarget.wavPack(), tags)
        val read = AudioKodec.readTags(file)
        assertEquals("Lossless ✓", read[TagField.TITLE])
        assertEquals("Someone", read[TagField.ARTIST])
        assertEquals("3", read[TagField.TRACK_NUMBER])
        assertContentEquals(cover.bytes, read.cover?.bytes)
        assertContentEquals(samples, decode(file))

        // The blocks are copied byte for byte; only the tag after them changes.
        val untagged = AudioKodec.encode(samples, AudioFormat(44_100, 2), 16, EncodeTarget.wavPack())
        val edited = AudioKodec.writeTags(file, read.edit { set(TagField.TITLE, "Edited, and rather longer than it was") })
        assertContentEquals(untagged, edited.copyOf(untagged.size), "the blocks after a tag edit")
        assertEquals("Edited, and rather longer than it was", AudioKodec.readTags(edited)[TagField.TITLE])
        assertEquals("Someone", AudioKodec.readTags(edited)[TagField.ARTIST])
        assertContentEquals(samples, decode(edited))

        val removed = AudioKodec.writeTags(file, TagBundle(emptyMap()))
        assertContentEquals(untagged, removed.copyOf(untagged.size), "the blocks after removing the tags")
        assertEquals(null, AudioKodec.readTags(removed)[TagField.TITLE])

        val added = AudioKodec.writeTags(untagged, TagBundle(mapOf(TagField.ALBUM to listOf("Added later"))))
        assertContentEquals(untagged, added.copyOf(untagged.size), "the blocks after adding tags")
        assertEquals("Added later", AudioKodec.readTags(added)[TagField.ALBUM])
    }

    @Test
    fun anId3BlockIsKeptUpToDateOnlyWhereThereIsOne() {
        val samples = signal(1, 16, 8_000)
        val untagged = AudioKodec.encode(samples, AudioFormat(44_100, 1), 16, EncodeTarget.wavPack())
        val plain = AudioKodec.writeTags(untagged, TagBundle(mapOf(TagField.TITLE to listOf("Plain"))))
        assertEquals(listOf(TagFormat.APEV2), AudioKodec.readTags(plain).raw.formats)

        val both = AudioKodec.writeTags(
            untagged,
            TagBundle(mapOf(TagField.TITLE to listOf("Both"))),
            TagWriteOptions(formats = setOf(TagFormat.ID3V2_4, TagFormat.APEV2)),
        )
        assertEquals(listOf(TagFormat.ID3V2_4, TagFormat.APEV2), AudioKodec.readTags(both).raw.formats)
        val edited = AudioKodec.writeTags(both, AudioKodec.readTags(both).edit { set(TagField.TITLE, "Edited") })
        val read = AudioKodec.readTags(edited)
        assertEquals(listOf(TagFormat.ID3V2_4, TagFormat.APEV2), read.raw.formats)
        assertEquals("Edited", read[TagField.TITLE])
        assertContentEquals(samples, decode(edited))
    }

    @Test
    fun whatCannotBeWrittenIsRefused() {
        val format = AudioFormat(44_100, ChannelLayout.STEREO)
        val chapters = TagBundle(mapOf(TagField.TITLE to listOf("x")), chapters = listOf(TagChapter("a", 0, 100, "One")))
        val sink = ByteArrayAudioSink()
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.encode(IntArray(200).asIntPcmSource(format, 16), EncodeTarget.wavPack(), sink, chapters)
        }
        assertEquals(0L, sink.position)

        // The reference refuses a length of zero, and so does this.
        assertFailsWith<AudioKodecException> { AudioKodec.encode(IntArray(0), format, 16, EncodeTarget.wavPack()) }
        val empty = StreamingSink()
        assertFailsWith<AudioKodecException> {
            AudioKodec.encode(unknownLength(IntArray(0).asIntPcmSource(format, 16)), EncodeTarget.wavPack(), empty)
        }
        assertEquals(0, empty.bytes().size)

        val short = object : IntPcmSource by IntArray(200).asIntPcmSource(format, 16) {
            override val frameCount: Long? get() = 150
        }
        assertFailsWith<AudioKodecException> { AudioKodec.encode(short, EncodeTarget.wavPack()) }

        assertFailsWith<IllegalArgumentException> { EncodeTarget.wavPack(20) }
        assertFailsWith<IllegalArgumentException> { EncodeTarget.wavPack(16, effort = 4) }
        assertFailsWith<IllegalArgumentException> { EncodeTarget.wavPack(16, effort = -1) }
    }

    private fun signal(channels: Int, bits: Int, frames: Int, noise: Double = 0.05): IntArray {
        val max = ((1L shl (bits - 1)) - 1).toDouble()
        var state = 0x2545F491 + channels * 977 + bits
        return IntArray(frames * channels) { i ->
            val f = i / channels
            val c = i % channels
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            val t = f / 44_100.0
            val value = 0.4 * sin(2 * PI * (180.0 + 90 * c) * t) + 0.2 * sin(2 * PI * (2_000.0 + 300 * c) * t * (1 + t)) +
                noise * (state % 1000) / 1000.0
            round(value * max).coerceIn(-max - 1, max).toInt()
        }
    }

    private fun decode(file: ByteArray): IntArray {
        AudioKodec.open(file).use { decoder ->
            val ints = decoder as IntPcmAccess
            val out = ArrayList<Int>()
            while (true) {
                val frame = ints.nextIntFrame() ?: break
                for (i in 0 until frame.frameCount * decoder.format.channels) out += frame.samples[i]
            }
            return out.toIntArray()
        }
    }

    private fun readU32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

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
}

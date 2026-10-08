package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IntegerEncodingTest {
    @Test
    fun wavAiffAndAuRoundTripsKeepEvery32BitSample() {
        val format = AudioFormat(48_000, 2)
        val samples = IntegerEncodingFixtures.samples(32, 4_099, format.channels)
        var bytes = AudioKodec.encode(samples, format, 32, EncodeTarget.wav(32))
        for (target in listOf(EncodeTarget.aiff(32), EncodeTarget.wav(32), EncodeTarget.au(32), EncodeTarget.wav(32))) {
            val exact = IntegerEncodingFixtures.read(bytes)
            assertContentEquals(samples, exact)
            bytes = AudioKodec.encode(exact, format, 32, target)
            assertContentEquals(samples, IntegerEncodingFixtures.read(bytes))
            AudioKodec.open(bytes).use { decoder ->
                assertEquals(4_099L, decoder.durationFrames)
                assertEquals(4_097L, decoder.seek(4_097))
                val block = assertNotNull(assertIs<IntPcmAccess>(decoder).nextIntFrame())
                assertEquals(4_097L, block.positionFrames)
                assertContentEquals(samples.copyOfRange(8_194, samples.size), block.samples.copyOf(block.sampleCount))
            }
        }
    }

    @Test
    fun allIntegerTargetsKeepSignedExtremesAndShortFinalBlocks() {
        for (depth in listOf(8, 16, 24, 32)) {
            for (channels in listOf(1, 2, 6)) {
                for (frames in listOf(0, 1, 4_097)) {
                    val format = AudioFormat(44_100, channels)
                    val samples = IntegerEncodingFixtures.samples(depth, frames, channels)
                    val targets = listOf(EncodeTarget.wav(depth), EncodeTarget.aiff(depth), EncodeTarget.au(depth)) +
                        if (depth < 32) listOf(EncodeTarget.flac(depth)) else emptyList()
                    for (target in targets) {
                        val bytes = AudioKodec.encode(samples, format, depth, target)
                        assertContentEquals(samples, IntegerEncodingFixtures.read(bytes), "$target, $frames frames, $channels channels")
                        val duration = if (frames == 0 && target.container == AudioContainer.FLAC) null else frames.toLong()
                        assertEquals(duration, AudioKodec.probe(bytes).durationFrames)
                        if (target.container == AudioContainer.FLAC) assertEquals(true, AudioKodec.verifyFlacMd5(bytes))
                    }
                }
            }
        }
    }

    @Test
    fun depthConversionScalesSignedValuesAndDiscardsOnlyLowBits() {
        for (sourceDepth in listOf(1, 7, 8, 16, 20, 24, 31, 32)) {
            val samples = IntegerEncodingFixtures.samples(sourceDepth, 19, 1)
            for (targetDepth in listOf(8, 16, 24, 32)) {
                val expected = samples.map {
                    if (targetDepth >= sourceDepth) it shl (targetDepth - sourceDepth) else it shr (sourceDepth - targetDepth)
                }.toIntArray()
                val targets = listOf(EncodeTarget.wav(targetDepth), EncodeTarget.aiff(targetDepth), EncodeTarget.au(targetDepth)) +
                    if (targetDepth < 32) listOf(EncodeTarget.flac(targetDepth)) else emptyList()
                for (target in targets) {
                    val bytes = AudioKodec.encode(samples, AudioFormat(8_000, 1), sourceDepth, target)
                    assertContentEquals(expected, IntegerEncodingFixtures.read(bytes), "$sourceDepth to $target")
                }
            }
        }
        // Arithmetic shifts round negative fractions down rather than towards zero.
        assertContentEquals(intArrayOf(-1, -1, 0, 0), IntegerEncodingFixtures.read(
            AudioKodec.encode(intArrayOf(-255, -1, 1, 255), AudioFormat(8_000, 1), 32, EncodeTarget.wav(24))))
    }

    @Test
    fun shortReadsKnownLengthsAndUnknownLengthsProduceTheSameAudio() {
        val format = AudioFormat(48_000, 2)
        val samples = IntegerEncodingFixtures.samples(24, 4_101, 2)
        for (target in listOf(EncodeTarget.wav(24), EncodeTarget.aiff(24), EncodeTarget.au(24), EncodeTarget.flac(24))) {
            val expected = AudioKodec.encode(samples, format, 24, target)
            for (known in listOf(false, true)) {
                val source = ShortIntSource(samples, format, 24, known)
                assertContentEquals(expected, AudioKodec.encode(source, target))
                assertEquals(0, source.read(IntArray(2), 0, 1))
            }
            val output = ByteArrayAudioSink()
            val unpatchable = object : AudioSink by output {
                override val canPatch: Boolean get() = false
                override fun patchAt(position: Long, bytes: ByteArray, offset: Int, count: Int) {
                    error("an unpatchable sink was patched")
                }
            }
            AudioKodec.encode(ShortIntSource(samples, format, 24, true), target, unpatchable)
            assertContentEquals(samples, IntegerEncodingFixtures.read(output.toByteArray()))
            if (target.container != AudioContainer.FLAC) assertContentEquals(expected, output.toByteArray())
            val unknownOutput = ByteArrayAudioSink()
            val unknownSink = object : AudioSink by unknownOutput { override val canPatch: Boolean get() = false }
            val unknown = ShortIntSource(samples, format, 24, false)
            if (target.container == AudioContainer.FLAC) {
                AudioKodec.encode(unknown, target, unknownSink)
                assertContentEquals(samples, IntegerEncodingFixtures.read(unknownOutput.toByteArray()))
            } else {
                assertFailsWith<AudioKodecException> { AudioKodec.encode(unknown, target, unknownSink) }
                assertEquals(0L, unknownOutput.position)
                assertEquals(0, unknown.calls)
            }
        }
    }

    @Test
    fun arrayReadersHonourOffsetsCountsAndTheirOwnCursor() {
        val samples = intArrayOf(11, 12, 21, 22, 31, 32)
        val source = samples.asIntPcmSource(AudioFormat(8_000, 2), 16, frameCount = 2)
        samples[0] = 13
        val output = IntArray(8) { -1 }
        assertEquals(1, source.read(output, 2, 1))
        assertContentEquals(intArrayOf(-1, -1, 13, 12, -1, -1, -1, -1), output)
        assertEquals(1, source.read(output, 4, 2))
        assertEquals(0, source.read(output, 0, 1))
        assertContentEquals(intArrayOf(-1, -1, 13, 12, 21, 22, -1, -1), output)
        assertFailsWith<IllegalArgumentException> { source.read(output, -1, 1) }
        assertFailsWith<IllegalArgumentException> { source.read(output, 0, -1) }
        assertFailsWith<IllegalArgumentException> { source.read(output, 1, Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { source.read(output, Int.MAX_VALUE, 0) }
        assertFailsWith<IllegalArgumentException> { samples.asIntPcmSource(AudioFormat(8_000, 2), 16, 4) }
        assertFailsWith<IllegalArgumentException> { samples.asIntPcmSource(AudioFormat(8_000, 2), 0) }
        assertFailsWith<IllegalArgumentException> { samples.asIntPcmSource(AudioFormat(8_000, 2), 33) }
        val stream = samples.asIntPcmSource(AudioFormat(8_000, 2), 16)
        AudioKodec.encode(stream, EncodeTarget.wav(16))
        assertFailsWith<AudioKodecException> { AudioKodec.encode(stream, EncodeTarget.wav(16)) }
    }

    @Test
    fun invalidReaderCountsRangesAndPromisesHaveClearFailures() {
        val format = AudioFormat(8_000, 1)
        val targets = listOf(EncodeTarget.wav(16), EncodeTarget.aiff(16), EncodeTarget.au(16), EncodeTarget.flac(16))
        for (target in targets) {
            for (got in listOf(-1, 4_097)) {
                val source = object : IntPcmSource {
                    override val format = format
                    override val bitDepth = 16
                    override val frameCount: Long? = null
                    override fun read(into: IntArray, offset: Int, frames: Int): Int = got
                }
                assertFailsWith<AudioKodecException> { AudioKodec.encode(source, target) }
            }
            for (depth in listOf(1, 8, 16, 20, 24)) {
                for (value in listOf((1L shl (depth - 1)).toInt(), (-(1L shl (depth - 1)) - 1).toInt())) {
                    assertFailsWith<AudioKodecException> { AudioKodec.encode(intArrayOf(value), format, depth, target) }
                }
            }
            for (count in listOf(0L, 2L, -1L)) {
                val source = object : IntPcmSource by intArrayOf(1).asIntPcmSource(format, 16) {
                    override val frameCount: Long = count
                }
                if (count < 0) {
                    val output = ByteArrayAudioSink()
                    assertFailsWith<IllegalArgumentException> { AudioKodec.encode(source, target, output) }
                    assertEquals(0L, output.position)
                } else assertFailsWith<AudioKodecException> { AudioKodec.encode(source, target) }
            }
            val invalidDepth = object : IntPcmSource by intArrayOf(1).asIntPcmSource(format, 16) { override val bitDepth = 33 }
            val output = ByteArrayAudioSink()
            assertFailsWith<IllegalArgumentException> { AudioKodec.encode(invalidDepth, target, output) }
            assertEquals(0L, output.position)
        }
    }

    @Test
    fun ditherIsDeterministicAcrossReadChunksAndOnlyAppliesToDepthReduction() {
        val format = AudioFormat(48_000, 1)
        val samples = IntArray(4_101) { ((it % 31) - 15) * 65_536 + 32_768 + it % 11 }
        for (makeTarget in listOf<(Dither) -> EncodeTarget>(
            { EncodeTarget.wav(16, it) }, { EncodeTarget.aiff(16, it) },
            { EncodeTarget.au(16, it) }, { EncodeTarget.flac(16, dither = it) },
        )) {
            val target = makeTarget(Dither.Tpdf(seed = 42))
            val first = AudioKodec.encode(samples, format, 32, target)
            assertContentEquals(first, AudioKodec.encode(ShortIntSource(samples, format, 32, true), target))
            val actual = IntegerEncodingFixtures.read(first)
            var errorSum = 0.0
            for (index in samples.indices) {
                val error = actual[index] - samples[index].toDouble() / 65_536
                assertTrue(abs(error) <= 1.5, "TPDF noise plus rounding must stay within 1.5 target steps")
                errorSum += error
            }
            assertTrue(abs(errorSum / samples.size) < 0.05, "TPDF rounding should not retain truncation's half-step bias")
            assertTrue(!actual.contentEquals(IntegerEncodingFixtures.read(AudioKodec.encode(samples, format, 32, makeTarget(Dither.None)))))
        }
        val edges = intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE, -1, 1)
        val ditheredEdges = IntegerEncodingFixtures.read(
            AudioKodec.encode(edges.copyOf(2), format, 32, EncodeTarget.wav(16, Dither.Tpdf(seed = 42))))
        assertTrue(ditheredEdges[0] in Short.MIN_VALUE.toInt()..Short.MIN_VALUE.toInt() + 1)
        assertTrue(ditheredEdges[1] in Short.MAX_VALUE.toInt() - 1..Short.MAX_VALUE.toInt())
        for (target in listOf(EncodeTarget.wav(32, Dither.Tpdf()), EncodeTarget.aiff(32, Dither.Tpdf()), EncodeTarget.au(32, Dither.Tpdf()))) {
            assertContentEquals(edges, IntegerEncodingFixtures.read(AudioKodec.encode(edges, format, 32, target)))
        }
        val shallow = intArrayOf(-128, -1, 0, 127)
        assertContentEquals(intArrayOf(-32768, -256, 0, 32512), IntegerEncodingFixtures.read(
            AudioKodec.encode(shallow, format, 8, EncodeTarget.flac(16, dither = Dither.Tpdf()))))
    }

    @Test
    fun unsupportedCodingsLayoutsAndAuTagsAreRefusedBeforeConsumingTheSource() {
        val format = AudioFormat(8_000, 1)
        val samples = intArrayOf(1, -1)
        val title = TagBundle(mapOf(TagField.TITLE to listOf("Integer audio 日本語")))
        for (target in listOf(EncodeTarget.wavFloat(), EncodeTarget.aiffFloat(), EncodeTarget.auFloat(),
            EncodeTarget.wavMuLaw(), EncodeTarget.wavALaw(), EncodeTarget.wavImaAdpcm(), EncodeTarget.wavMsAdpcm())) {
            val source = ShortIntSource(samples, format, 16, true)
            val output = ByteArrayAudioSink()
            assertFailsWith<UnsupportedFeatureException> { AudioKodec.encode(source, target, output) }
            assertEquals(0L, output.position)
            assertEquals(0, source.calls)
        }
        for (target in listOf(EncodeTarget.wav(16), EncodeTarget.aiff(16), EncodeTarget.flac(16))) {
            val bytes = AudioKodec.encode(samples, format, 16, target, title)
            assertEquals(title.fields, AudioKodec.readTags(bytes).fields)
            assertContentEquals(samples, IntegerEncodingFixtures.read(bytes))
        }
        val source = ShortIntSource(samples, format, 16, true)
        val output = ByteArrayAudioSink()
        assertFailsWith<UnsupportedFeatureException> { AudioKodec.encode(source, EncodeTarget.au(16), output, title) }
        assertEquals(0L, output.position)
        assertEquals(0, source.calls)
        val custom = AudioFormat(8_000, ChannelLayout.of(AudioChannel.BACK_CENTER))
        val wave = AudioKodec.encode(samples, custom, 16, EncodeTarget.wav(16))
        assertEquals(custom, AudioKodec.probe(wave).format)
        for (target in listOf(EncodeTarget.aiff(16), EncodeTarget.au(16), EncodeTarget.flac(16))) {
            val reader = ShortIntSource(samples, custom, 16, true)
            val sink = ByteArrayAudioSink()
            assertFailsWith<IllegalArgumentException> { AudioKodec.encode(reader, target, sink) }
            assertEquals(0L, sink.position)
            assertEquals(0, reader.calls)
        }
    }

    private class ShortIntSource(
        samples: IntArray,
        override val format: AudioFormat,
        override val bitDepth: Int,
        known: Boolean,
    ) : IntPcmSource {
        private val delegate = samples.asIntPcmSource(format, bitDepth)
        override val frameCount: Long? = if (known) delegate.frameCount else null
        var calls = 0
            private set

        override fun read(into: IntArray, offset: Int, frames: Int): Int {
            calls++
            return delegate.read(into, offset, minOf(frames, 1 + calls % 13))
        }
    }
}

internal object IntegerEncodingFixtures {
    fun samples(depth: Int, frames: Int, channels: Int): IntArray {
        val minimum = (-(1L shl (depth - 1))).toInt()
        val maximum = ((1L shl (depth - 1)) - 1).toInt()
        val lowBits = intArrayOf(minimum, maximum, -1, 0, 1, 16_777_217, 1_073_741_825,
            -16_777_217, -1_073_741_825, Int.MIN_VALUE + 1, Int.MAX_VALUE - 1)
        return IntArray(frames * channels) { index ->
            if (index < lowBits.size) lowBits[index].coerceIn(minimum, maximum)
            else (index * 1_664_525 + 1_013_904_223) shr (32 - depth)
        }
    }

    fun read(bytes: ByteArray): IntArray = AudioKodec.open(bytes).use { decoder ->
        val integer = assertIs<IntPcmAccess>(decoder)
        val chunks = mutableListOf<IntArray>()
        var size = 0
        while (true) {
            val block = integer.nextIntFrame() ?: break
            assertEquals(integer.bitDepth, block.bitDepth)
            chunks.add(block.samples.copyOf(block.sampleCount))
            size += block.sampleCount
        }
        IntArray(size).also { output ->
            var at = 0
            for (chunk in chunks) {
                chunk.copyInto(output, at)
                at += chunk.size
            }
        }
    }
}

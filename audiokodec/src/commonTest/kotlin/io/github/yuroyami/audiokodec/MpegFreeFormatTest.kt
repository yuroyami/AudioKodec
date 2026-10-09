package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagField
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpegFreeFormatTest {
    @Test
    fun everyVersionAndLayerMeasuresPaddingAndProducesTheCompletePcm() {
        for (version in listOf(1, 2, 25)) for (layer in 1..3) for (channels in 1..2) {
            val frames = MpegFreeFormatFixtures.frames(version, layer, channels)
            val bytes = frames.reduce { a, b -> a + b }
            val count = 6 * when { layer == 1 -> 384; layer == 2 || version == 1 -> 1152; else -> 576 }
            val rate = 48_000 / when (version) { 1 -> 1; 2 -> 2; else -> 4 }
            for (knownSize in listOf(false, true)) {
                val source = MpegXingFixtures.CountingSource(bytes, knownSize)
                val info = AudioKodec.probe(source)
                assertEquals(count.toLong(), info.durationFrames, "$version/$layer/$channels")
                assertEquals(rate, info.format.sampleRate)
                assertEquals(channels, info.format.channels)
                assertEquals(64_000, info.bitrate)
                assertEquals(false, info.isVariableBitrate)
                val pcm = AudioKodec.decode(source)
                assertEquals(count, pcm.frames)
                assertTrue(pcm.samples.all { it == 0f })
            }
            for (reuse in listOf(false, true)) AudioKodec.open(bytes, DecodeOptions(reuseBuffers = reuse)).use { decoder ->
                for (target in listOf(count - 1, 0, count / 2 + 1, 1, count, count + 1)) {
                    assertEquals(minOf(target, count).toLong(), decoder.seek(target.toLong()))
                    if (target >= count) assertNull(decoder.nextFrame()) else {
                        val frame = assertNotNull(decoder.nextFrame())
                        assertEquals(target.toLong(), frame.positionFrames)
                        assertTrue(frame.samples.take(frame.sampleCount).all { it == 0f })
                    }
                }
            }
        }
    }

    @Test
    fun largeFramesAndTheAllocationCeilingAreHandledBeforePlayback() {
        for (layer in 1..3) {
            val bytes = MpegFreeFormatFixtures.frames(layer = layer, base = 4096).reduce { a, b -> a + b }
            val count = 6 * if (layer == 1) 384 else 1152
            assertEquals(count, AudioKodec.decode(bytes).frames)
            assertFailsWith<AllocationGuardException> { AudioKodec.probe(bytes, DecodeOptions(maxAllocationBytes = 4095)) }
            assertFailsWith<AllocationGuardException> { AudioKodec.open(bytes, DecodeOptions(maxAllocationBytes = 4095)).close() }
        }
    }

    @Test
    fun payloadSyncWordsAndEmbeddedTagsCannotReplaceTheFirstFrame() {
        val frames = MpegFreeFormatFixtures.frames(base = 800, padded = false).map { it.copyOf() }
        frames[0].copyInto(frames[0], 40, 0, 4)
        val expected = 6 * 1152
        for (knownSize in listOf(false, true)) {
            for (tag in listOf(ByteArray(0), "ID3".encodeToByteArray() + byteArrayOf(4, 0, 0, 0, 0, 0, 0))) {
                val bytes = frames[0] + tag + frames.drop(1).reduce { a, b -> a + b }
                val source = MpegXingFixtures.CountingSource(bytes, knownSize)
                assertEquals(0L, AudioKodec.probe(source).dataOffset)
                assertEquals(expected, AudioKodec.decode(source).frames)
            }
        }
    }

    @Test
    fun changedSpacingAndTruncationKeepOnlyTheCompleteTolerantPrefix() {
        val frames = MpegFreeFormatFixtures.frames(base = 96, padded = false)
        val prefix = frames.take(3).reduce { a, b -> a + b }
        val tail = frames.drop(3).reduce { a, b -> a + b }
        val expected = AudioKodec.decode(prefix).samples
        for (gap in listOf(byteArrayOf(0), ByteArray(4))) {
            val bytes = prefix + gap + tail
            for (knownSize in listOf(false, true)) {
                assertFailsWith<CorruptStreamException> { AudioKodec.decode(MpegXingFixtures.CountingSource(bytes, knownSize)) }
                val pcm = AudioKodec.decode(MpegXingFixtures.CountingSource(bytes, knownSize), DecodeOptions(tolerant = true))
                assertEquals(3L * 1152, pcm.info.durationFrames)
                assertContentEquals(expected, pcm.samples)
            }
        }
        val short = prefix + frames[3].copyOf(50)
        assertFailsWith<TruncatedStreamException> { AudioKodec.decode(short) }
        assertContentEquals(expected, AudioKodec.decode(short, DecodeOptions(tolerant = true)).samples)
        assertEquals(6 * 1152, AudioKodec.decode(prefix + tail + ByteArray(1024)).frames)
        assertFailsWith<AudioKodecException> { AudioKodec.decode(frames[0]) }
    }

    @Test
    fun indexedPlaybackNoSlowSeekAndTagRewritingKeepTheSameAudio() {
        val bytes = MpegFreeFormatFixtures.frames(base = 96, padded = false, count = 64).reduce { a, b -> a + b }
        val source = MpegXingFixtures.CountingSource(bytes, true)
        AudioKodec.open(source, DecodeOptions(allowSlowSeek = false)).use { decoder ->
            source.transferred = 0
            assertFailsWith<UnsupportedFeatureException> { decoder.seek(50_000) }
            assertEquals(0L, decoder.positionFrames)
            assertEquals(0L, source.transferred)
            while (decoder.nextFrame() != null) { }
            assertEquals(0L, decoder.seek(0))
            assertNotNull(decoder.nextFrame())
            val at = decoder.positionFrames
            assertFailsWith<IllegalArgumentException> { decoder.seek(-1) }
            assertEquals(at, decoder.positionFrames)
        }
        val tagged = AudioKodec.writeTags(bytes, AudioKodec.readTags(bytes).edit { set(TagField.TITLE, "Free format") })
        val info = AudioKodec.probe(tagged)
        assertContentEquals(bytes, tagged.copyOfRange(info.dataOffset.toInt(), tagged.size))
        assertEquals("Free format", AudioKodec.readTags(tagged)[TagField.TITLE])
        assertContentEquals(AudioKodec.decode(bytes).samples, AudioKodec.decode(tagged).samples)
    }

    @Test
    fun mp4PacketBoundariesSupplyTheSizeAndRefuseInconsistentLaterPackets() {
        for (layer in 1..3) {
            val packets = MpegMp4Fixtures.packets(MpegTest().silentFrames(layer, count = 6, compact = true))
            val ordinary = MpegMp4Fixtures.file(packets)
            val offset = AudioKodec.probe(ordinary).dataOffset.toInt()
            val free = ordinary.copyOf()
            var at = offset
            for (packet in packets) {
                free[at + 2] = (free[at + 2].toInt() and 15).toByte()
                at += packet.size
            }
            assertContentEquals(AudioKodec.decode(ordinary).samples, AudioKodec.decode(free).samples)
            AudioKodec.open(free, DecodeOptions(allowSlowSeek = false)).use { decoder ->
                assertEquals(500L, decoder.seek(500))
                assertEquals(500L, assertNotNull(decoder.nextFrame()).positionFrames)
            }
            free[offset + packets[0].size + 2] = packets[1][2]
            assertFailsWith<CorruptStreamException> { AudioKodec.decode(free) }
        }
    }
}

/** Free-format slots and zero allocation fields assembled from MPEG frame syntax. */
internal object MpegFreeFormatFixtures {
    fun frames(version: Int = 1, layer: Int = 3, channels: Int = 1, base: Int? = null,
        padded: Boolean = true, count: Int = 6): List<ByteArray> {
        val versionBits = when (version) { 1 -> 3; 2 -> 2; else -> 0 }
        val rate = 48_000 / when (version) { 1 -> 1; 2 -> 2; else -> 4 }
        val width = base ?: if (layer == 1) 12 * 64_000 / rate * 4
            else (if (layer == 2 || version == 1) 144 else 72) * 64_000 / rate
        return List(count) { index ->
            val padding = if (padded && index % 3 != 0) 1 else 0
            ByteArray(width + padding * if (layer == 1) 4 else 1).also { frame ->
                frame[0] = 0xFF.toByte()
                frame[1] = (0xE0 or (versionBits shl 3) or ((4 - layer) shl 1) or 1).toByte()
                frame[2] = (4 or (padding shl 1)).toByte()
                frame[3] = if (channels == 1) 0xC0.toByte() else 0
            }
        }
    }

    fun hostile(layer: Int): ByteArray = frames(layer = layer, base = if (layer == 3) 48 else 24,
        padded = false, count = 3).reduce { a, b -> a + b }
}

package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.mpeg.MpegFrameHeader
import io.github.yuroyami.audiokodec.internal.mpeg.MpegStream
import io.github.yuroyami.audiokodec.internal.mpeg.MpegVbrHeader
import io.github.yuroyami.audiokodec.internal.mpeg.MpegXingSeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpegXingSeekTest {
    @Test
    fun everyLayerUsesTheTableForDistantSampleAccurateSeeks() {
        for (layer in 1..3) {
            val fixture = MpegXingFixtures.file(layer)
            for (knownSize in listOf(false, true)) {
                for (allowSlow in listOf(false, true)) {
                    val source = MpegXingFixtures.CountingSource(fixture.bytes, knownSize)
                    AudioKodec.open(source, DecodeOptions(allowSlowSeek = allowSlow)).use { decoder ->
                        val total = assertNotNull(decoder.durationFrames)
                        val target = total * 97 / 100 + 17
                        source.transferred = 0
                        assertEquals(target, decoder.seek(target))
                        assertTrue(source.transferred < fixture.bytes.size / 3 + 2 * 65_536,
                            "Layer $layer read ${source.transferred} of ${fixture.bytes.size} bytes")
                        val first = assertNotNull(decoder.nextFrame())
                        assertEquals(target, first.positionFrames)
                        assertTrue(first.samples.take(first.sampleCount).all { it == 0f })
                        source.transferred = 0
                        assertEquals(target + 1, decoder.seek(target + 1))
                        assertEquals(0L, source.transferred, "reuse the verified frame landing")
                        for (next in listOf(total - 1, 0L, total / 4, total * 3 / 4)) {
                            assertEquals(next, decoder.seek(next))
                            val block = assertNotNull(decoder.nextFrame())
                            assertEquals(next, block.positionFrames)
                            assertTrue(block.samples.take(block.sampleCount).all { it == 0f })
                        }
                    }
                }
            }
        }
    }

    @Test
    fun quantisedAndLateHintsResolveToTheActualEncodedFrameOffsets() {
        for (version in listOf(1, 2, 25)) {
            val fixture = MpegXingFixtures.file(3, count = 600, version = version)
            for (late in listOf(false, true)) {
                val bytes = fixture.bytes.copyOf()
                if (late) for (index in 1 until 100) bytes[fixture.tocOffset + index] = 250.toByte()
                val options = DecodeOptions()
                val reader = SourceReader(bytes.asAudioSource(), options)
                val table = MpegXingSeek(reader, MpegStream.parse(reader, options))
                assertTrue(table.usable)
                for (target in listOf(580L, 399L, 517L, 0L)) {
                    val result = table.find(target)
                    if (late && target != 0L) {
                        assertNull(result, "late hints must fall back to the normal prefix walk")
                        continue
                    }
                    val landing = assertNotNull(result)
                    assertEquals(target, landing.number)
                    assertEquals(fixture.offsets[target.toInt()].toLong(), landing.offset,
                        "version $version, late=$late, frame $target")
                }
            }
        }
    }

    @Test
    fun unusableTablesKeepTheExistingSlowSeekPolicy() {
        val fixture = MpegXingFixtures.file(3, count = 300)
        val backwards = fixture.bytes.copyOf().also { it[fixture.tocOffset + 90] = 0 }
        val zero = fixture.bytes.copyOf().also { it.fill(0, fixture.tocOffset, fixture.tocOffset + 100) }
        val wrongSize = fixture.bytes.copyOf().also { MpegXingFixtures.put32(it, fixture.tocOffset - 4, it.size - 1) }
        for (bytes in listOf(backwards, zero, wrongSize)) {
            val source = MpegXingFixtures.CountingSource(bytes, knownSize = true)
            AudioKodec.open(source, DecodeOptions(allowSlowSeek = false)).use { decoder ->
                source.transferred = 0
                assertFailsWith<UnsupportedFeatureException> { decoder.seek(290L * 1152) }
                if (bytes !== wrongSize) assertEquals(0L, source.transferred)
                assertEquals(0L, decoder.positionFrames)
            }
            AudioKodec.open(bytes).use { decoder ->
                assertEquals(290L * 1152, decoder.seek(290L * 1152))
                assertNotNull(decoder.nextFrame())
            }
        }
    }

    @Test
    fun optionalByteCountsAndTrailingPaddingKeepExactLandings() {
        val fixture = MpegXingFixtures.file(3, count = 300)
        for (omitBytes in listOf(false, true)) {
            for (padding in listOf(0, 3, 4096)) {
                val bytes = fixture.bytes + ByteArray(padding)
                if (omitBytes) {
                    MpegXingFixtures.put32(bytes, fixture.tocOffset - 12, 5)
                    fixture.bytes.copyInto(bytes, fixture.tocOffset - 4, fixture.tocOffset, fixture.tocOffset + 100)
                    bytes.fill(0, fixture.tocOffset + 96, fixture.tocOffset + 100)
                }
                val reader = SourceReader(bytes.asAudioSource(), DecodeOptions())
                val table = MpegXingSeek(reader, MpegStream.parse(reader, DecodeOptions()))
                assertTrue(table.usable)
                for (target in listOf(290L, 165L, 299L)) {
                    assertEquals(fixture.offsets[target.toInt()].toLong(), assertNotNull(table.find(target)).offset,
                        "omitBytes=$omitBytes, padding=$padding, frame $target")
                }
            }
        }
    }

    @Test
    fun everyTocBitFlipHasACompleteLandingOrATypedRefusal() {
        val fixture = MpegXingFixtures.file(3, count = 64)
        for (index in 0 until 100) {
            for (bit in 0..7) {
                val bytes = fixture.bytes.copyOf().also {
                    val at = fixture.tocOffset + index
                    it[at] = (it[at].toInt() xor (1 shl bit)).toByte()
                }
                try {
                    AudioKodec.open(bytes, DecodeOptions(allowSlowSeek = false)).use { decoder ->
                        assertEquals(60L * 1152 + 19, decoder.seek(60L * 1152 + 19))
                        assertNotNull(decoder.nextFrame())
                    }
                } catch (_: AudioKodecException) {
                    // An unusable optional table can be refused when walking
                    // from the beginning has explicitly been disabled.
                }
            }
        }
    }

    @Test
    fun xingByteCountsKeepTheirUnsignedThirtyTwoBitRange() {
        val fixture = MpegXingFixtures.file(3, count = 64)
        val bytes = fixture.bytes.copyOf()
        MpegXingFixtures.put32(bytes, fixture.tocOffset - 4, 0xC0000000.toInt())
        val header = assertNotNull(MpegFrameHeader.parse(bytes, 0))
        assertEquals(3L * 1024 * 1024 * 1024,
            assertNotNull(MpegVbrHeader.parse(bytes.copyOf(header.frameBytes), header)).byteCount)
    }
}

internal object MpegXingFixtures {
    class File(val bytes: ByteArray, val offsets: IntArray, val tocOffset: Int)

    fun file(layer: Int, count: Int = 6000, version: Int = 1): File {
        fun frame(bitrate: Int, padded: Boolean = false): ByteArray {
            val versionBits = when (version) { 1 -> 3; 2 -> 2; else -> 0 }
            val header = byteArrayOf(0xFF.toByte(),
                (0xE0 or (versionBits shl 3) or ((4 - layer) shl 1) or 1).toByte(),
                ((bitrate shl 4) or 4 or if (padded) 2 else 0).toByte(), 0xC0.toByte())
            val parsed = MpegFrameHeader.parse(header, 0)!!
            return ByteArray(parsed.frameBytes).also { header.copyInto(it) }
        }
        val header = frame(9)
        val headerShape = MpegFrameHeader.parse(header, 0)!!
        val at = 4 + headerShape.sideInfoBytes
        "Xing".encodeToByteArray().copyInto(header, at)
        put32(header, at + 4, 7)
        put32(header, at + 8, count)
        val frames = Array(count) { frame(intArrayOf(1, 4, 2, 3)[it % 4], it % 3 == 0) }
        val offsets = IntArray(count)
        var size = header.size
        for (index in frames.indices) {
            offsets[index] = size
            size += frames[index].size
        }
        put32(header, at + 12, size)
        val toc = at + 16
        for (index in 1 until 100) header[toc + index] =
            (offsets[index * count / 100] * 256L / size).toByte()
        val bytes = ByteArray(size)
        header.copyInto(bytes)
        for (index in frames.indices) frames[index].copyInto(bytes, offsets[index])
        return File(bytes, offsets, toc)
    }

    fun put32(bytes: ByteArray, at: Int, value: Int) {
        for (index in 0..3) bytes[at + index] = (value ushr (24 - index * 8)).toByte()
    }

    class CountingSource(bytes: ByteArray, knownSize: Boolean) : AudioSource {
        private val inner = bytes.asAudioSource()
        override val size: Long = if (knownSize) bytes.size.toLong() else -1L
        var transferred: Long = 0
        override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int =
            inner.readAt(position, into, offset, count).also { if (it > 0) transferred += it }
        override fun close() = inner.close()
    }
}

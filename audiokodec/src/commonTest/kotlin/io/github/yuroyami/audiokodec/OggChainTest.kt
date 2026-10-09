package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagField
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OggChainTest {
    @Test
    fun opusLinksApplyTheirOwnPreSkipAndEndTrim() {
        val first = OggChainFixtures.opus(31, 120, 2500)
        val second = OggChainFixtures.opus(32, 312, 2345)
        val expected = AudioKodec.decode(first).samples + AudioKodec.decode(second).samples
        val bytes = first + second
        assertEquals(4413L, AudioKodec.probe(bytes).durationFrames)
        assertContentEquals(expected, AudioKodec.decode(bytes).samples)
        checkSeeks(bytes, expected, 1, listOf(0, 1, 2379, 2380, 2381, 4412, 4413, 9000, 17))
        // Existing callers concatenate complete recordings with a reused
        // serial. BOS/EOS boundaries still keep the states independent.
        val reused = first + OggChainFixtures.opus(31, 312, 2345)
        assertContentEquals(expected, AudioKodec.decode(reused).samples)
    }

    @Test
    fun flacLinksKeepExactIntegersSeeksAndSingleSourceOwnership() {
        val a = IntArray(35) { it * 311 - 4000 }
        val b = IntArray(51) { 50000 - it * 117 }
        val first = OggChainFixtures.flac(41, a)
        val second = OggChainFixtures.flac(42, b)
        val bytes = first + second
        val expected = a + b
        for (knownSize in listOf(false, true)) {
            val source = OggChainFixtures.CountingSource(bytes, knownSize)
            assertEquals(86L, AudioKodec.probe(source).durationFrames)
            AudioKodec.readTags(source)
            assertEquals(0, source.closes)
            val decoder = AudioKodec.open(source)
            val access = decoder as IntPcmAccess
            assertEquals(24, access.bitDepth)
            val output = ArrayList<Int>()
            while (true) {
                val block = access.nextIntFrame() ?: break
                assertEquals(output.size.toLong(), block.positionFrames)
                repeat(block.sampleCount) { output += block.samples[it] }
            }
            assertContentEquals(expected, output.toIntArray())
            assertEquals(0, source.closes, "advancing links must not close the shared source")
            for (target in listOf(35, 0, 34, 36, 85, 86, 120, 1)) {
                val at = minOf(target, 86)
                assertEquals(at.toLong(), decoder.seek(target.toLong()))
                val block = access.nextIntFrame()
                if (at == 86) assertNull(block)
                else {
                    val frame = assertNotNull(block)
                    assertEquals(at.toLong(), frame.positionFrames)
                    assertContentEquals(expected.copyOfRange(at, at + frame.frameCount), frame.samples.copyOf(frame.sampleCount))
                }
            }
            val position = decoder.positionFrames
            val reads = source.reads
            assertFailsWith<IllegalArgumentException> { decoder.seek(-1) }
            assertEquals(position, decoder.positionFrames)
            assertEquals(reads, source.reads)
            decoder.close()
            decoder.close()
            assertEquals(1, source.closes)
        }
    }

    @Test
    fun compatibleCodecsAndMixedFlacDepthsUseTheSameFixedFloatFormat() {
        val a = OggChainFixtures.flac(41, IntArray(17) { it * 100 - 500 }, bits = 16)
        val b = OggChainFixtures.flac(42, IntArray(19) { it * 777 - 4000 }, bits = 24)
        val vorbis = OggGranuleFixtures.vorbis()
        for (parts in listOf(listOf(a, b), listOf(a, vorbis))) {
            val bytes = parts.reduce { first, next -> first + next }
            val expected = parts.map { AudioKodec.decode(it).samples }.reduce { first, next -> first + next }
            val info = AudioKodec.probe(bytes)
            assertNull(info.bitDepth)
            assertEquals(if (parts[1] === vorbis) AudioCodec.UNKNOWN else AudioCodec.FLAC, info.codec)
            AudioKodec.open(bytes).use { assertTrue(it !is IntPcmAccess) }
            assertContentEquals(expected, AudioKodec.decode(bytes).samples)
            checkSeeks(bytes, expected, 1, listOf(17, 0, 16, 18, expected.size, expected.size + 10))
        }
    }

    @Test
    fun formatChangesAreNamedBeforeAnyAudioIsReturned() {
        val a = OggChainFixtures.opus(31, 120, 2500)
        val b = OggChainFixtures.opus(32, 120, 2500, channels = 2)
        val c = OggChainFixtures.flac(41, IntArray(8), rate = 8000)
        val d = OggChainFixtures.flac(42, IntArray(8), rate = 16000)
        for (bytes in listOf(a + b, c + d)) {
            for (tolerant in listOf(false, true)) {
                val options = DecodeOptions(tolerant = tolerant)
                val source = OggChainFixtures.CountingSource(bytes, true)
                val failure = assertFailsWith<UnsupportedFeatureException> { AudioKodec.probe(source, options) }
                assertEquals("chained Ogg output format changes", failure.feature)
                assertEquals(0, source.closes)
                assertFailsWith<UnsupportedFeatureException> { AudioKodec.open(source, options).close() }
                assertEquals(1, source.closes)
            }
        }
    }

    @Test
    fun laterHeaderDamageKeepsOnlyTheCompleteTolerantPrefix() {
        val first = OggChainFixtures.opus(31, 120, 2500)
        val second = OggChainFixtures.opus(32, 312, 2345)
        val expected = AudioKodec.decode(first).samples
        for (tail in listOf(second.copyOf(12), second.copyOf().also { it[22] = (it[22].toInt() xor 1).toByte() })) {
            val bytes = first + tail
            assertFailsWith<AudioKodecException> { AudioKodec.decode(bytes) }
            assertContentEquals(expected, AudioKodec.decode(bytes, DecodeOptions(tolerant = true)).samples)
        }
        val damaged = first + second
        val source = OggChainFixtures.CountingSource(damaged, true)
        AudioKodec.open(source, DecodeOptions(tolerant = true)).use { decoder ->
            // Damage the second link after header inspection. Its fresh reader
            // must meet the error without throwing away the first link's PCM.
            val audio = OggDamageFixtures.pages(second)[2].first + first.size
            damaged[audio + 22] = (damaged[audio + 22].toInt() xor 1).toByte()
            val output = ArrayList<Float>()
            while (true) {
                val frame = decoder.nextFrame() ?: break
                repeat(frame.sampleCount) { output += frame.samples[it] }
            }
            assertContentEquals(expected, output.toFloatArray())
            assertEquals(0, source.closes)
        }
        assertEquals(1, source.closes)
    }

    @Test
    fun unknownLinkDurationsDeferTheSeekWalkAndKeepPastEndPositions() {
        val first = OggChainFixtures.flac(41, IntArray(17) { it * 77 }, granule = -1)
        val second = OggChainFixtures.flac(42, IntArray(19) { -it * 171 })
        val bytes = first + second
        val expected = AudioKodec.decode(bytes).samples
        assertNull(AudioKodec.probe(bytes).durationFrames)
        AudioKodec.open(bytes).use { decoder ->
            assertNull(decoder.durationFrames)
            assertEquals(18L, decoder.seek(18))
            val frame = assertNotNull(decoder.nextFrame())
            assertContentEquals(expected.copyOfRange(18, 18 + frame.frameCount), frame.samples)
            assertEquals(9000L, decoder.seek(9000))
            assertNull(decoder.nextFrame())
            assertEquals(9000L, decoder.positionFrames)
            decoder.seek(0)
            assertNotNull(decoder.nextFrame())
        }
        val source = OggChainFixtures.CountingSource(bytes, true)
        AudioKodec.open(source, DecodeOptions(allowSlowSeek = false)).use { decoder ->
            val reads = source.reads
            assertFailsWith<UnsupportedFeatureException> { decoder.seek(18) }
            assertEquals(0L, decoder.positionFrames)
            assertEquals(reads, source.reads)
            assertNotNull(decoder.nextFrame())
        }
    }

    @Test
    fun tagWritingPreservesEveryLaterLinkAndTheCompleteAudio() {
        val first = OggChainFixtures.opus(31, 120, 2500)
        val second = OggChainFixtures.opus(32, 312, 2345)
        val bytes = first + second
        val expected = AudioKodec.decode(bytes).samples
        val updated = AudioKodec.writeTags(bytes, AudioKodec.readTags(bytes).edit { set(TagField.TITLE, "Edited " + "x".repeat(3500)) })
        assertContentEquals(second, updated.copyOfRange(updated.size - second.size, updated.size))
        assertContentEquals(expected, AudioKodec.decode(updated).samples)
        assertEquals(4413L, AudioKodec.probe(updated).durationFrames)
        assertTrue(AudioKodec.readTags(updated)[TagField.TITLE].orEmpty().startsWith("Edited"))
    }

    @Test
    fun multiplexedGroupsMustFinishEveryStreamBeforeTheNextLink() {
        val first = OggChainFixtures.opus(31, 120, 2500)
        val second = OggChainFixtures.opus(32, 312, 2345)
        val videoHead = byteArrayOf(0x80.toByte()) + "theora".encodeToByteArray() + ByteArray(35)
        val head = OggBuilder().page(90, 0, 0, 2, listOf(videoHead)).build()
        val end = OggBuilder().page(90, 1, 100, 4, listOf(byteArrayOf(1, 2, 3))).build()
        val expected = AudioKodec.decode(first).samples + AudioKodec.decode(second).samples
        val bytes = head + first + end + second
        assertEquals(4413L, AudioKodec.probe(bytes).durationFrames)
        assertContentEquals(expected, AudioKodec.decode(bytes).samples)
        checkSeeks(bytes, expected, 1, listOf(2380, 2379, 4412, 0))
        for (damaged in listOf(head + first + second + end, head + first + second)) {
            assertFailsWith<AudioKodecException> { AudioKodec.decode(damaged) }
            assertContentEquals(AudioKodec.decode(first).samples,
                AudioKodec.decode(damaged, DecodeOptions(tolerant = true)).samples)
        }
    }

    @Test
    fun emptyLinksDoNotHideLaterAudioOrChangeItsPositions() {
        val first = OggChainFixtures.flac(41, IntArray(17) { it * 31 }, bits = 16)
        val empty = OggChainFixtures.flac(42, IntArray(0), bits = 16)
        val last = OggChainFixtures.flac(43, IntArray(19) { -it * 17 }, bits = 16)
        val expected = AudioKodec.decode(first).samples + AudioKodec.decode(last).samples
        val bytes = empty + first + empty + last + empty
        assertEquals(36L, AudioKodec.probe(bytes).durationFrames)
        assertContentEquals(expected, AudioKodec.decode(bytes).samples)
        checkSeeks(bytes, expected, 1, listOf(0, 16, 17, 18, 35, 36, 1))
        val limits = DecodeOptions(maxAllocationBytes = 4096)
        assertEquals(17L, AudioKodec.probe(first, limits).durationFrames)
        val many = ByteArray(first.size * 40).also { output -> repeat(40) { first.copyInto(output, it * first.size) } }
        assertFailsWith<AllocationGuardException> { AudioKodec.probe(many, limits) }
    }

    private fun checkSeeks(bytes: ByteArray, expected: FloatArray, channels: Int, targets: List<Int>) {
        for (reuse in listOf(false, true)) {
            AudioKodec.open(bytes, DecodeOptions(reuseBuffers = reuse, allowSlowSeek = false)).use { decoder ->
                for (target in targets) {
                    val at = minOf(target, expected.size / channels)
                    assertEquals(at.toLong(), decoder.seek(target.toLong()))
                    val block = decoder.nextFrame()
                    if (at == expected.size / channels) assertNull(block)
                    else {
                        val frame = assertNotNull(block)
                        assertEquals(at.toLong(), frame.positionFrames)
                        assertContentEquals(expected.copyOfRange(at * channels, (at + frame.frameCount) * channels), frame.samples.copyOf(frame.sampleCount))
                    }
                }
            }
        }
    }
}

internal object OggChainFixtures {
    fun opus(serial: Int, preSkip: Int, end: Long, channels: Int = 1): ByteArray {
        val head = "OpusHead".encodeToByteArray() + byteArrayOf(1, channels.toByte(), preSkip.toByte(), (preSkip ushr 8).toByte()) +
            byteArrayOf(0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)
        val tags = "OpusTags".encodeToByteArray() + TagStressFixtures.commentBody(listOf("TITLE=Link $serial"))
        val packet = R128TagFixtures.audioPacket
        return OggBuilder().page(serial, 0, 0, 2, listOf(head))
            .page(serial, 1, 0, 0, listOf(tags))
            .page(serial, 2, 960, 0, listOf(packet))
            .page(serial, 3, end, 4, listOf(packet, packet)).build()
    }

    fun flac(serial: Int, samples: IntArray, bits: Int = 24, rate: Int = 8000, granule: Long = samples.size.toLong()): ByteArray {
        val native = AudioKodec.encode(samples, AudioFormat(rate, 1), bits, EncodeTarget.flac(bits))
        val audio = AudioKodec.probe(native).dataOffset.toInt()
        val id = byteArrayOf(0x7F) + "FLAC".encodeToByteArray() + byteArrayOf(1, 0, 0, 1) + native.copyOf(42).also { it[4] = 0 }
        val comments = TagStressFixtures.commentBody(listOf("TITLE=Link $serial"))
        val comment = byteArrayOf(0x84.toByte(), 0, 0, comments.size.toByte()) + comments
        return OggBuilder().page(serial, 0, 0, 2, listOf(id))
            .page(serial, 1, 0, 0, listOf(comment))
            .page(serial, 2, granule, 4, if (samples.isEmpty()) emptyList() else listOf(native.copyOfRange(audio, native.size))).build()
    }

    fun hostile(): ByteArray = flac(41, intArrayOf(1, -2, 3, -4), bits = 16) +
        flac(42, intArrayOf(-5, 6, -7, 8), bits = 16)

    class CountingSource(bytes: ByteArray, knownSize: Boolean) : AudioSource {
        private val inner = bytes.asAudioSource()
        override val size: Long = if (knownSize) bytes.size.toLong() else -1L
        var closes = 0
        var reads = 0
        override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int {
            reads++
            return inner.readAt(position, into, offset, count)
        }
        override fun close() { closes++ }
    }
}

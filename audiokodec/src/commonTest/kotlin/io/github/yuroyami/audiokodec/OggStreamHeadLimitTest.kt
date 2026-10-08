package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.ogg.OggReader
import io.github.yuroyami.audiokodec.tag.TagBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OggStreamHeadLimitTest {
    @Test
    fun distinctSerialsRetainTheirOrderUpToTheStreamLimit() {
        val serials = List(256) { if (it % 2 == 0) it else Int.MIN_VALUE + it }
        val builder = OggBuilder()
        for (serial in serials) builder.page(serial, 0, 0, 2, emptyList())
        builder.page(serials[0], 1, 0, 0, emptyList())
        val options = DecodeOptions()
        val reader = OggReader(SourceReader(builder.build().asAudioSource(), options), options)
        assertEquals(serials, reader.readStreamHeads().map { it.serialNumber })
    }

    @Test
    fun everyEntryPointStopsAnExcessiveStreamListBeforeReadingTheWholeFile() {
        val builder = OggBuilder()
        repeat(20_000) { builder.page(it, 0, 0, 2, emptyList()) }
        val bytes = builder.build()
        for (knownSize in listOf(false, true)) for (tolerant in listOf(false, true)) {
            val options = DecodeOptions(tolerant = tolerant)
            val operations: List<(AudioSource) -> Unit> = listOf(
                { AudioKodec.probe(it, options) },
                { AudioKodec.open(it, options).close() },
                { AudioKodec.decode(it, options) },
                { AudioKodec.writeTags(it, TagBundle.EMPTY, ByteArrayAudioSink(), decodeOptions = options) },
            )
            for (operation in operations) {
                val source = CountingSource(bytes, knownSize)
                val failure = assertFailsWith<UnsupportedFeatureException> { operation(source) }
                assertTrue(failure.message.orEmpty().contains("256"))
                assertTrue(source.transferred < bytes.size / 2, "read ${source.transferred} of ${bytes.size} bytes")
            }
            // Tag reads keep their existing best-effort behaviour on malformed
            // Ogg headers, but must stop the same excessive list promptly.
            val source = CountingSource(bytes, knownSize)
            assertTrue(AudioKodec.readTags(source, options).raw.blocks.isEmpty())
            assertTrue(source.transferred < bytes.size / 2, "tag read transferred ${source.transferred} bytes")
        }
    }

    @Test
    fun aDistantDuplicateSerialIsStillCorruptInEitherMode() {
        val builder = OggBuilder()
        repeat(255) { builder.page(it, 0, 0, 2, emptyList()) }
        builder.page(0, 0, 0, 2, emptyList())
        val bytes = builder.build()
        for (tolerant in listOf(false, true)) {
            val options = DecodeOptions(tolerant = tolerant)
            val reader = OggReader(SourceReader(bytes.asAudioSource(), options), options)
            assertFailsWith<CorruptStreamException> { reader.readStreamHeads() }
        }
    }

    @Test
    fun retainedPagesAndTheSerialIndexRespectTheAllocationBudget() {
        val builder = OggBuilder()
        repeat(3) { builder.page(it, 0, 0, 2, listOf(ByteArray(400))) }
        val options = DecodeOptions(maxAllocationBytes = 1_024)
        val reader = OggReader(SourceReader(builder.build().asAudioSource(), options), options)
        assertFailsWith<AllocationGuardException> { reader.readStreamHeads() }
    }

    private class CountingSource(bytes: ByteArray, knownSize: Boolean) : AudioSource {
        private val delegate = bytes.asAudioSource()
        override val size: Long = if (knownSize) bytes.size.toLong() else -1L
        var transferred = 0L
        override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int =
            delegate.readAt(position, into, offset, count).also {
                if (it > 0) {
                    transferred += it
                }
            }
        override fun close() = Unit
    }
}

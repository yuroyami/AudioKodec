package io.github.yuroyami.audiokodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MpegTrailerTest {
    @Test
    fun strictPlaybackIgnoresTrailersWithKnownOrUnknownSourceSizes() {
        for (case in MpegTolerantFrameFixtures.cases().take(3)) {
            val audio = MpegTrailerFixtures.audio(case)
            val expected = AudioKodec.decode(audio)
            for (tail in MpegTrailerFixtures.tails(case)) {
                for (unknown in listOf(false, true)) {
                    val bytes = audio + tail
                    val info = AudioKodec.probe(MpegTrailerFixtures.source(bytes, unknown))
                    assertEquals(expected.frames.toLong(), info.durationFrames, case.name)
                    assertEquals(audio.size.toLong(), info.dataSize, case.name)
                    assertContentEquals(expected.samples,
                        AudioKodec.decode(MpegTrailerFixtures.source(bytes, unknown)).samples, case.name)
                    AudioKodec.open(MpegTrailerFixtures.source(bytes, unknown)).use { decoder ->
                        while (decoder.nextFrame() != null) { }
                        assertEquals(expected.frames.toLong(), decoder.positionFrames)
                        repeat(2) { assertNull(decoder.nextFrame()) }
                        assertEquals(0L, decoder.seek(0))
                        assertNotNull(decoder.nextFrame())
                    }
                }
            }
        }
    }

    @Test
    fun aStoredXingDurationStopsPlaybackBeforeReadingTheTrailer() {
        val case = MpegTolerantFrameFixtures.cases()[2]
        val audio = MpegTrailerFixtures.audio(case)
        val expected = AudioKodec.decode(audio)
        val indexed = MpegTrailerFixtures.xing(case) + audio
        for (tail in MpegTrailerFixtures.tails(case)) {
            for (unknown in listOf(false, true)) {
                val bytes = indexed + tail
                assertEquals(expected.frames.toLong(), AudioKodec.probe(MpegTrailerFixtures.source(bytes, unknown)).durationFrames)
                assertContentEquals(expected.samples, AudioKodec.decode(MpegTrailerFixtures.source(bytes, unknown)).samples)
            }
        }
    }

    @Test
    fun aSingleCompleteFrameCanBeFollowedByATagOrPadding() {
        for (case in MpegTolerantFrameFixtures.cases().take(3)) {
            for (unknown in listOf(false, true)) {
                for (tail in listOf(MpegTrailerFixtures.id3v1(), ByteArray(257))) {
                    val bytes = case.good + tail
                    assertEquals(case.frames, AudioKodec.decode(MpegTrailerFixtures.source(bytes, unknown)).frames)
                }
            }
        }
    }

    @Test
    fun aCompleteFinalHeaderWithAMissingPayloadIsStillTruncatedAudio() {
        for (case in MpegTolerantFrameFixtures.cases().take(3)) {
            for (kept in listOf(4, case.good.size - 1)) {
                val audio = MpegTrailerFixtures.audio(case)
                val damaged = audio + case.good.copyOf(kept)
                for (unknown in listOf(false, true)) {
                    assertFailsWith<TruncatedStreamException> { AudioKodec.probe(MpegTrailerFixtures.source(damaged, unknown)) }
                    assertFailsWith<TruncatedStreamException> { AudioKodec.decode(MpegTrailerFixtures.source(damaged, unknown)) }
                }
            }
        }
    }
}

internal object MpegTrailerFixtures {
    fun audio(case: MpegTolerantFrameFixtures.Case): ByteArray = ByteArray(case.good.size * 12).also { bytes ->
        repeat(12) { case.good.copyInto(bytes, it * case.good.size) }
    }

    fun id3v1(): ByteArray = ByteArray(128).also { "TAG".encodeToByteArray().copyInto(it) }

    fun tails(case: MpegTolerantFrameFixtures.Case): List<ByteArray> = listOf(
        ByteArray(0), byteArrayOf(1), byteArrayOf(1, 2, 3), ByteArray(257), ByteArray(65_540), id3v1(),
        "TAG+".encodeToByteArray() + ByteArray(223) + id3v1(),
        "LYRICSBEGIN".encodeToByteArray() + case.prefix() + "LYRICSEND".encodeToByteArray(),
    )

    fun xing(case: MpegTolerantFrameFixtures.Case): ByteArray = ByteArray(case.good.size).also { bytes ->
        case.good.copyInto(bytes, endIndex = 4)
        "Info".encodeToByteArray().copyInto(bytes, 36)
        bytes[43] = 1 // Frame count present.
        bytes[47] = 12 // Twelve audio frames, excluding this bookkeeping frame.
    }

    fun source(bytes: ByteArray, unknown: Boolean): AudioSource = object : AudioSource {
        private val delegate = bytes.asAudioSource()
        override val size: Long = if (unknown) -1 else bytes.size.toLong()
        override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int =
            delegate.readAt(position, into, offset, minOf(count, 7))
        override fun close() = delegate.close()
    }
}

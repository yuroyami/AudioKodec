package io.github.yuroyami.audiokodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class MpegEmbeddedTagTest {
    @Test
    fun largeTagsInEveryId3VersionDoNotHideAudioOrCountAsFrames() {
        val options = DecodeOptions(maxAllocationBytes = 512 * 1_024)
        for (case in MpegTolerantFrameFixtures.cases().take(3)) {
            val audio = MpegTrailerFixtures.audio(case)
            val expected = AudioKodec.decode(audio + audio)
            for (version in 2..4) {
                val tag = MpegEmbeddedTagFixtures.tag(2 * 1_024 * 1_024, version, footer = version == 4)
                // Artwork and private data can contain perfectly convincing
                // MPEG frames. None of these bytes belongs to the audio.
                repeat(4) { case.good.copyInto(tag, 1_024 + it * case.good.size) }
                val bytes = audio + tag + MpegEmbeddedTagFixtures.tag(64, 3) + audio
                for (unknown in listOf(false, true)) for (tolerant in listOf(false, true)) {
                    val settings = options.copy(tolerant = tolerant)
                    assertEquals(expected.frames.toLong(), AudioKodec.probe(MpegTrailerFixtures.source(bytes, unknown), settings).durationFrames)
                    assertContentEquals(expected.samples, AudioKodec.decode(MpegTrailerFixtures.source(bytes, unknown), settings).samples)
                    AudioKodec.open(MpegTrailerFixtures.source(bytes, unknown), settings).use { decoder ->
                        val target = 13L * case.frames + 17
                        assertEquals(target, decoder.seek(target))
                        val block = assertNotNull(decoder.nextFrame())
                        assertEquals(target, block.positionFrames)
                        assertContentEquals(expected.samples.copyOfRange(target.toInt() * case.channels,
                            target.toInt() * case.channels + block.frameCount * case.channels), block.samples)
                    }
                }
            }
        }
    }

    @Test
    fun aStoredXingCountStillCrossesTheTagDuringPlayback() {
        val case = MpegTolerantFrameFixtures.cases()[2]
        val audio = MpegTrailerFixtures.audio(case)
        val xing = MpegTrailerFixtures.xing(case).also { it[47] = 24 }
        val expected = AudioKodec.decode(audio + audio)
        val bytes = xing + audio + MpegEmbeddedTagFixtures.tag(100_000) + audio
        for (unknown in listOf(false, true)) {
            assertEquals(expected.frames.toLong(), AudioKodec.probe(MpegTrailerFixtures.source(bytes, unknown)).durationFrames)
            assertContentEquals(expected.samples, AudioKodec.decode(MpegTrailerFixtures.source(bytes, unknown)).samples)
        }
    }

    @Test
    fun frameConfirmationCanCrossTagsAfterAOneFrameFirstLink() {
        for (case in MpegTolerantFrameFixtures.cases().take(3)) {
            val bytes = case.good + MpegEmbeddedTagFixtures.tag(100_000) + case.good + case.good
            assertContentEquals(AudioKodec.decode(case.prefix()).samples, AudioKodec.decode(bytes).samples)
        }
    }

    @Test
    fun malformedHeadersAndActualJunkKeepTheResynchronisationLimit() {
        val case = MpegTolerantFrameFixtures.cases()[2]
        val audio = MpegTrailerFixtures.audio(case)
        val tag = MpegEmbeddedTagFixtures.tag(100_000)
        val gaps = listOf(
            tag.copyOf().also { it[3] = 1 },
            tag.copyOf().also { it[4] = 0xFF.toByte() },
            tag.copyOf().also { it[5] = 1 },
            tag.copyOf().also { it[6] = 0x80.toByte() },
            ByteArray(40_000) { 0x55 } + tag + ByteArray(30_000) { 0x55 },
        )
        for (gap in gaps) for (unknown in listOf(false, true)) {
            val bytes = audio + gap + audio
            assertFailsWith<CorruptStreamException> { AudioKodec.probe(MpegTrailerFixtures.source(bytes, unknown)) }
            assertContentEquals(AudioKodec.decode(audio).samples,
                AudioKodec.decode(MpegTrailerFixtures.source(bytes, unknown), DecodeOptions(tolerant = true)).samples)
        }
    }

    @Test
    fun aTruncatedEmbeddedTagKeepsOnlyTheCompleteAudioPrefixInTolerantMode() {
        for (case in MpegTolerantFrameFixtures.cases().take(3)) {
            val audio = MpegTrailerFixtures.audio(case)
            val bytes = audio + MpegEmbeddedTagFixtures.tag(100_000).copyOf(100)
            for (unknown in listOf(false, true)) {
                assertFailsWith<TruncatedStreamException> { AudioKodec.probe(MpegTrailerFixtures.source(bytes, unknown)) }
                assertContentEquals(AudioKodec.decode(audio).samples,
                    AudioKodec.decode(MpegTrailerFixtures.source(bytes, unknown), DecodeOptions(tolerant = true)).samples)
            }
        }
    }
}

internal object MpegEmbeddedTagFixtures {
    fun tag(bodySize: Int, version: Int = 4, footer: Boolean = false): ByteArray {
        val bytes = ByteArray(10 + bodySize + if (footer) 10 else 0) { 0x55 }
        "ID3".encodeToByteArray().copyInto(bytes)
        bytes[3] = version.toByte()
        bytes[4] = 0
        bytes[5] = if (footer) 0x10 else 0
        repeat(4) { bytes[6 + it] = ((bodySize ushr (7 * (3 - it))) and 0x7F).toByte() }
        val headerSize = if (version == 2) 6 else 10
        (if (version == 2) "GEO" else "PRIV").encodeToByteArray().copyInto(bytes, 10)
        val payloadSize = bodySize - headerSize
        if (version == 2) repeat(3) { bytes[13 + it] = (payloadSize ushr (8 * (2 - it))).toByte() }
        else {
            repeat(4) { bytes[14 + it] = (if (version == 4) (payloadSize ushr (7 * (3 - it))) and 0x7F else payloadSize ushr (8 * (3 - it))).toByte() }
            bytes[18] = 0
            bytes[19] = 0
        }
        if (version == 2) {
            bytes[10 + headerSize] = 0
            "application/octet-stream\u0000\u0000\u0000".encodeToByteArray().copyInto(bytes, 11 + headerSize)
        } else "example\u0000".encodeToByteArray().copyInto(bytes, 10 + headerSize)
        if (footer) {
            bytes.copyInto(bytes, bytes.size - 10, 0, 10)
            "3DI".encodeToByteArray().copyInto(bytes, bytes.size - 10)
        }
        return bytes
    }
}

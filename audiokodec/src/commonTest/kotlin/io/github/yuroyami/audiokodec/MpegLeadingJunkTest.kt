package io.github.yuroyami.audiokodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MpegLeadingJunkTest {
    @Test
    fun leadingJunkAndPartialFramesDoNotHideConfirmedAudio() {
        for (case in MpegTolerantFrameFixtures.cases().take(3)) {
            val audio = MpegTrailerFixtures.audio(case)
            val expected = AudioKodec.decode(audio)
            val prefixes = listOf(byteArrayOf(1, 2, 3), ByteArray(1_000),
                ByteArray(70_000) { 0x55 }, case.good.copyOfRange(case.good.size / 2, case.good.size))
            for (prefix in prefixes) for (unknown in listOf(false, true)) for (tolerant in listOf(false, true)) {
                val bytes = prefix + audio
                val options = DecodeOptions(tolerant = tolerant)
                val info = AudioKodec.probe(MpegTrailerFixtures.source(bytes, unknown), options)
                assertEquals(AudioContainer.MPEG_AUDIO, info.container)
                assertEquals(prefix.size.toLong(), info.dataOffset)
                assertEquals(expected.frames.toLong(), info.durationFrames)
                assertContentEquals(expected.samples, AudioKodec.decode(MpegTrailerFixtures.source(bytes, unknown), options).samples)
                AudioKodec.open(MpegTrailerFixtures.source(bytes, unknown), options).use { decoder ->
                    val target = 2L * case.frames + 17
                    assertEquals(target, decoder.seek(target))
                    val block = assertNotNull(decoder.nextFrame())
                    assertEquals(target, block.positionFrames)
                    assertContentEquals(expected.samples.copyOfRange(target.toInt() * case.channels,
                        target.toInt() * case.channels + block.frameCount * case.channels), block.samples)
                }
            }
        }
    }

    @Test
    fun unconfirmedCandidatesAndLongJunkRemainUnsupportedWithBoundedReads() {
        val frame = MpegTolerantFrameFixtures.cases()[2].good
        val inputs = listOf(ByteArray(1_024 * 1_024) { 0x55 },
            byteArrayOf(1, 2, 3) + frame + ByteArray(100) { 0x55 },
            ByteArray(524_288) { 0x55 } + MpegTrailerFixtures.audio(MpegTolerantFrameFixtures.cases()[2]))
        for (bytes in inputs) for (unknown in listOf(false, true)) {
            var read = 0L
            val delegate = bytes.asAudioSource()
            val source = object : AudioSource {
                override val size: Long = if (unknown) -1 else bytes.size.toLong()
                override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int =
                    delegate.readAt(position, into, offset, minOf(count, 257)).also { if (it > 0) read += it }
                override fun close() = Unit
            }
            assertFailsWith<UnsupportedFormatException> { AudioKodec.probe(source) }
            assertTrue(read <= 10L * 65_536, "unrecognised input read $read bytes")
        }
    }

    @Test
    fun aRecognisedContainerTakesPriorityOverMpegInsideIt() {
        val samples = TestSignals.quantise(TestSignals.sine(128, 1), 16)
        val wave = AudioKodec.encode(samples, AudioFormat(44_100, 1), EncodeTarget.wav(16))
        val bytes = wave + MpegTrailerFixtures.audio(MpegTolerantFrameFixtures.cases()[2])
        assertEquals(AudioContainer.WAV, AudioKodec.probe(bytes).container)
        assertContentEquals(samples, AudioKodec.decode(bytes).samples)
    }
}

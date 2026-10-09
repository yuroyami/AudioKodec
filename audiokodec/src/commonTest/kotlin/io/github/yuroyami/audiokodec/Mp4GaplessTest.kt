package io.github.yuroyami.audiokodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Checks that the gapless record iTunes stores in an MP4 file's `iTunSMPB`
 * item trims decoding on every target, and that the edit list, when it
 * trims anything, keeps precedence over it.
 *
 * The audio is uncompressed `sowt`, whose every sample is known, so the trim
 * is checked to the sample, and seeking is checked to land relative to the
 * trimmed start. The oracle suite checks an ALAC file ffmpeg writes on the
 * JVM, and that two decodes join without a seam.
 */
class Mp4GaplessTest {

    private val mp4 = Mp4Test()

    @Test
    fun theRecordTrimsTheDelayAndThePadding() {
        val file = file(Mp4TagFixtures.smpb(delay = 100, padding = 51, frames = 2_850))
        assertEquals(2_850L, AudioKodec.probe(file).durationFrames)
        assertDecodes(file, first = 100, frames = 2_850)
        AudioKodec.open(file).use { decoder ->
            assertEquals(2_850L, decoder.info.durationFrames)
            val ints = decoder as IntPcmAccess
            for (target in longArrayOf(0, 1, 399, 1_234, 2_849, 2_850)) {
                assertEquals(target, decoder.seek(target), "seek to $target")
                val frame = ints.nextIntFrame()
                if (target == 2_850L) {
                    assertNull(frame, "nothing after the trimmed end")
                    continue
                }
                assertNotNull(frame, "a frame after seeking to $target")
                assertEquals(target, frame.positionFrames, "position counts from the trimmed start")
                assertEquals(mp4.sample(100 + target.toInt(), 0), frame.samples[0], "first sample after seeking to $target")
            }
        }
    }

    @Test
    fun aZeroCountFallsBackToThePadding() {
        assertDecodes(file(Mp4TagFixtures.smpb(delay = 64, padding = 37, frames = 0)), first = 64, frames = 3_001 - 64 - 37)
    }

    @Test
    fun aRecordForMoreThanTheTrackHoldsStopsAtItsEnd() {
        assertDecodes(file(Mp4TagFixtures.smpb(delay = 10, padding = 0, frames = 9_999)), first = 10, frames = 2_991)
    }

    @Test
    fun anEditListThatTrimsWins() {
        val file = mp4.build(
            frames = 3_001, channels = 2, chunkFrames = 400, edit = 10L to 500L, movieTimescale = 8_000,
            moovExtra = Mp4TagFixtures.gaplessUdta(Mp4TagFixtures.smpb(delay = 100, padding = 51, frames = 2_850)),
        )
        assertDecodes(file, first = 10, frames = 500)
    }

    @Test
    fun anEditListThatPlaysEverythingLeavesTheRecordToTrim() {
        val file = mp4.build(
            frames = 3_001, channels = 2, chunkFrames = 400, edit = 0L to 3_001L, movieTimescale = 8_000,
            moovExtra = Mp4TagFixtures.gaplessUdta(Mp4TagFixtures.smpb(delay = 100, padding = 51, frames = 2_850)),
        )
        assertDecodes(file, first = 100, frames = 2_850)
    }

    @Test
    fun anUnreadableOrImpossibleRecordIsIgnored() {
        for (record in listOf(
            "not hex at all",
            " 00000000 00000064",
            " 00000000 0000006G 00000000 0000000000000B22",
            " 00000000 000000000064 00000000 0000000000000B22",
            Mp4TagFixtures.smpb(delay = 5_000, padding = 0, frames = 10),
        )) {
            assertDecodes(file(record), first = 0, frames = 3_001)
        }
    }

    @Test
    fun theRecordSurvivesATagRewrite() {
        val original = file(Mp4TagFixtures.smpb(delay = 100, padding = 51, frames = 2_850))
        val tags = AudioKodec.readTags(original)
        val written = AudioKodec.writeTags(original, tags.edit { set(io.github.yuroyami.audiokodec.tag.TagField.TITLE, "Gapless") })
        assertDecodes(written, first = 100, frames = 2_850)
    }

    private fun file(record: String): ByteArray = mp4.build(
        frames = 3_001, channels = 2, chunkFrames = 400, moovFirst = true,
        moovExtra = Mp4TagFixtures.gaplessUdta(record),
    )

    private fun assertDecodes(file: ByteArray, first: Int, frames: Int) {
        AudioKodec.open(file).use { decoder ->
            assertEquals(frames.toLong(), decoder.info.durationFrames, "reported length")
            val ints = decoder as IntPcmAccess
            var at = 0
            while (true) {
                val frame = ints.nextIntFrame() ?: break
                for (i in 0 until frame.frameCount) {
                    for (c in 0 until 2) {
                        assertEquals(mp4.sample(first + at + i, c), frame.samples[i * 2 + c], "frame ${at + i}, channel $c")
                    }
                }
                at += frame.frameCount
            }
            assertEquals(frames, at, "frames decoded")
        }
    }
}

package io.github.yuroyami.audiokodec.docs

// --8<-- [start:imports]
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.DecodeOptions
// --8<-- [end:imports]
import io.github.yuroyami.audiokodec.EncodeTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// --8<-- [start:playlist]
/** Plays [tracks] back to back into [output], adding and removing nothing between them. */
fun playGapless(tracks: List<ByteArray>, output: (samples: FloatArray, frames: Int) -> Unit) {
    var format: AudioFormat? = null
    for (track in tracks) {
        AudioKodec.open(track, DecodeOptions(reuseBuffers = true)).use { decoder ->
            // One output, opened once, needs one format. Resample a track that differs.
            check(format == null || decoder.format == format) { "track format ${decoder.format} differs from $format" }
            format = decoder.format
            while (true) {
                val block = decoder.nextFrame() ?: break
                output(block.samples, block.frameCount)
            }
        }
    }
}
// --8<-- [end:playlist]

class GaplessSample {
    /** Cuts [Fixtures.samples] into three tracks at points no codec block would choose. */
    private val cuts = listOf(0, 31_337, 60_001, 2 * Fixtures.RATE)

    private fun part(i: Int) = Fixtures.samples.copyOfRange(cuts[i] * 2, cuts[i + 1] * 2)

    /** Plays [tracks] with [playGapless] and returns what reached the output. */
    private fun played(tracks: List<ByteArray>): FloatArray {
        val out = ArrayList<Float>()
        playGapless(tracks) { samples, frames -> for (i in 0 until frames * 2) out += samples[i] }
        return out.toFloatArray()
    }

    @Test
    fun losslessTracksJoinBackIntoTheOriginal() {
        val tracks = (0 until 3).map { AudioKodec.encode(part(it), Fixtures.format, EncodeTarget.flac(24)) }
        val joined = played(tracks)
        // 24-bit FLAC holds these floats to within half a step; the join adds and loses nothing.
        assertEquals(Fixtures.samples.size, joined.size)
        for (i in joined.indices) assertEquals(Fixtures.samples[i], joined[i], 1f / (1 shl 23))
    }

    /** Each track encoded by ffmpeg with [arguments], then decoded on its own: every one must keep its exact length. */
    private fun lossyTracksKeepTheirLength(extension: String, vararg arguments: String) {
        val tracks = (0 until 3).map { Fixtures.ffmpegEncode(part(it), "gapless-$it.$extension", *arguments) }
        for ((i, track) in tracks.withIndex()) {
            val frames = AudioKodec.decode(track).frames
            assertEquals(cuts[i + 1] - cuts[i], frames, "track $i of $extension")
        }
        assertEquals(Fixtures.samples.size, played(tracks).size)
    }

    @Test
    fun mp3FromLameKeepsItsLength() = lossyTracksKeepTheirLength("mp3", "-c:a", "libmp3lame", "-b:a", "192k")

    @Test
    fun aacInMp4PlaysTheLengthItsEditListStates() {
        val tracks = (0 until 3).map { Fixtures.ffmpegEncode(part(it), "gapless-$it.m4a", "-c:a", "aac", "-b:a", "128k") }
        for ((i, track) in tracks.withIndex()) {
            val stated = AudioKodec.probe(track).durationFrames!!
            assertEquals(stated, AudioKodec.decode(track).frames.toLong(), "track $i")
            // ffmpeg's muxer states a few frames fewer than went in (26 here, against ffprobe as well).
            // The priming block and the padding to a whole block are gone either way.
            assertTrue(stated in (cuts[i + 1] - cuts[i] - 64L)..(cuts[i + 1] - cuts[i]).toLong(), "track $i: $stated")
        }
    }

    @Test
    fun opusKeepsItsLength() {
        // Opus runs at 48 kHz, so the tracks are made at that rate.
        val music = Fixtures.music(96_000, 48_000)
        val cuts = listOf(0, 34_567, 70_001, 96_000)
        val tracks = (0 until 3).map {
            Fixtures.ffmpegEncode(music.copyOfRange(cuts[it] * 2, cuts[it + 1] * 2), "gapless-$it.opus", "-c:a", "libopus", rate = 48_000)
        }
        for (i in 0 until 3) assertEquals(cuts[i + 1] - cuts[i], AudioKodec.decode(tracks[i]).frames)
    }

    @Test
    fun vorbisKeepsItsLength() = lossyTracksKeepTheirLength("ogg", "-c:a", "libvorbis")

    @Test
    fun adtsHasNowhereToSayItsLengthAndDecodesWholeBlocks() {
        val track = Fixtures.ffmpegEncode(part(1), "gapless.aac", "-c:a", "aac", "-f", "adts")
        val frames = AudioKodec.decode(track).frames
        assertEquals(0, frames % 1024)
        assertTrue(frames > cuts[2] - cuts[1])
    }

    @Test
    fun tracksOfDifferentFormatsAreRefused() {
        val a = AudioKodec.encode(part(0), Fixtures.format, EncodeTarget.flac(16))
        val b = AudioKodec.encode(part(1), AudioFormat(48_000, 2), EncodeTarget.flac(16))
        val failure = runCatching { played(listOf(a, b)) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException, "$failure")
    }
}

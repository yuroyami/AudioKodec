package io.github.yuroyami.audiokodecs

import io.github.yuroyami.audiokodecs.tag.ReplayGain
import io.github.yuroyami.audiokodecs.tag.TagBundle
import io.github.yuroyami.audiokodecs.tag.TagField
import io.github.yuroyami.audiokodecs.tag.TagFormat
import io.github.yuroyami.audiokodecs.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tag writes that must keep what the file meant, not only what the merged
 * view shows.
 *
 * Each case here is a write that once succeeded and quietly changed the
 * meaning of an entry: a URL nobody could read back, a comment that lost its
 * language, a loudness value relabelled to another reference.
 */
class TagPreservationTest {

    private val stream = ByteArray(32) { 0x55 }

    @Test
    fun urlFramesHoldTheUrlWithNoEncodingByte() {
        val urls = listOf("https://example.com/artist", "https://example.org/other")
        val tags = TagBundle(mapOf(TagField.URL to urls))
        for (version in listOf(3, 4)) {
            val bytes = AudioKodecs.writeTags(stream, tags, TagWriteOptions(id3Version = version))
            val woar = bytes.indexOfBytes("WOAR".encodeToByteArray())
            // The body begins directly after the ten-byte frame header.
            assertEquals('h'.code.toByte(), bytes[woar + 10], "ID3v2.$version URL body")
            val read = AudioKodecs.readTags(bytes)
            assertEquals(urls, read.all(TagField.URL), "ID3v2.$version")
        }
    }

    @Test
    fun replayGainValuesComeFromTheHighestPrecedenceBlockThatStatesThem() {
        val id3 = AudioKodecs.writeTags(
            stream,
            TagBundle(emptyMap(), replayGain = ReplayGain(trackGainDb = -3.0, trackPeak = 0.9)),
            TagWriteOptions(padding = 0),
        )
        val ape = AudioKodecs.writeTags(
            byteArrayOf(),
            TagBundle(
                emptyMap(),
                replayGain = ReplayGain(trackGainDb = -12.0, trackPeak = 0.2, albumGainDb = -9.0, albumPeak = 0.5),
            ),
            TagWriteOptions(formats = setOf(TagFormat.APEV2)),
        )
        val gain = assertNotNull(AudioKodecs.readTags(id3 + ape).replayGain)
        // ID3v2 outranks APE for the values it states, and APE fills the rest.
        assertEquals(-3.0, gain.trackGainDb!!, 0.005)
        assertEquals(0.9, gain.trackPeak!!, 0.0005)
        assertEquals(-9.0, gain.albumGainDb!!, 0.005)
        assertEquals(0.5, gain.albumPeak!!, 0.0005)

        // Within one block, the first statement of a value is the one kept.
        val duplicated = ApeBytes(
            listOf(
                "REPLAYGAIN_TRACK_GAIN" to "-1.5 dB".encodeToByteArray(),
                "REPLAYGAIN_TRACK_GAIN" to "-8.0 dB".encodeToByteArray(),
            ),
        )
        assertEquals(-1.5, AudioKodecs.readTags(duplicated).replayGain!!.trackGainDb!!, 0.005)
    }

    @Test
    fun gainsFoundAgainstDifferentReferencesAreStatedAgainstOne() {
        val block = ApeBytes(
            listOf(
                "R128_TRACK_GAIN" to "-1536".encodeToByteArray(),
                "REPLAYGAIN_ALBUM_GAIN" to "-4 dB".encodeToByteArray(),
            ),
        )
        val gain = assertNotNull(AudioKodecs.readTags(block).replayGain)
        // -6 dB against -23 LUFS is -1 dB against -18 LUFS.
        assertFalse(gain.isR128)
        assertEquals(-1.0, gain.trackGainDb!!, 0.001)
        assertEquals(-4.0, gain.albumGainDb!!, 0.001)
    }

    @Test
    fun writingAnR128GainConvertsOrKeepsItsReferenceButNeverRelabelsIt() {
        val tags = TagBundle(
            emptyMap(),
            replayGain = ReplayGain(trackGainDb = -6.0, trackPeak = 0.75, isR128 = true),
        )
        for (format in listOf(TagFormat.ID3V2_4, TagFormat.APEV2)) {
            val bytes = AudioKodecs.writeTags(stream, tags, TagWriteOptions(formats = setOf(format)))
            val gain = assertNotNull(AudioKodecs.readTags(bytes).replayGain, format.displayName)
            assertFalse(gain.isR128, format.displayName)
            assertEquals(-1.0, gain.trackGainDb!!, 0.005, format.displayName)
            assertEquals(0.75, gain.trackPeak!!, 0.0005, format.displayName)
        }

        // Vorbis comments carry the Opus convention, so the reference is kept,
        // and so is the peak, which that convention has no field of its own for.
        val flac = AudioKodecs.encode(FloatArray(4096), AudioFormat(44_100, 1), EncodeTarget.flac(), tags)
        val gain = assertNotNull(AudioKodecs.readTags(flac).replayGain)
        assertTrue(gain.isR128)
        assertEquals(-6.0, gain.trackGainDb!!, 0.005)
        assertEquals(0.75, gain.trackPeak!!, 0.0005)
    }
}

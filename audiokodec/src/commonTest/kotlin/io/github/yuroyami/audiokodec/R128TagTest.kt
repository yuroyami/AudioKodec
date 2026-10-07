package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.ReplayGain
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class R128TagTest {
    private val names = listOf("R128_TRACK_GAIN", "R128_ALBUM_GAIN")

    @Test
    fun signedEndpointsSignsAndLeadingZerosAreReadInEveryTagRepresentation() {
        for (name in names) {
            for ((value, integer) in R128TagFixtures.valid) {
                for (bytes in inputs(name, value)) {
                    val gain = assertNotNull(AudioKodec.readTags(bytes).replayGain, "$name=$value")
                    assertTrue(gain.isR128)
                    assertEquals(integer / 256.0, if (name == names[0]) gain.trackGainDb else gain.albumGainDb)
                }
            }
        }
    }

    @Test
    fun malformedAndOutOfRangeStoredValuesStayRawWithoutBecomingApplicableGain() {
        for (name in names) {
            for (value in R128TagFixtures.invalid) {
                for (bytes in inputs(name, value)) {
                    val tags = AudioKodec.readTags(bytes)
                    assertNull(tags.replayGain, "$name=$value")
                    assertEquals(listOf(value), tags.raw.blocks.flatMap { it.entries }
                        .filter { it.key == name || it.description == name }.map { it.text })
                }
            }
        }
    }

    @Test
    fun embeddedNullsDoNotEndAnR128VorbisValueEarly() {
        for (name in names) {
            for (value in listOf("1\u0000", "1\u00002")) {
                for (bytes in listOf(TagStressFixtures.flac(listOf("$name=$value")), R128TagFixtures.opus(listOf("$name=$value")))) {
                    val tags = AudioKodec.readTags(bytes)
                    assertNull(tags.replayGain)
                    assertEquals(value, tags.raw.blocks.flatMap { it.entries }.single { it.key == name }.text)
                }
            }
        }
    }

    @Test
    fun aMalformedEarlierGainDoesNotHideALaterValidOne() {
        val file = R128TagFixtures.opus(listOf("R128_TRACK_GAIN=32768", "r128_track_gain=-32768", "R128_ALBUM_GAIN= 0", "r128_album_gain=+32767"))
        val gain = assertNotNull(AudioKodec.readTags(file).replayGain)
        assertEquals(-128.0, gain.trackGainDb)
        assertEquals(127.99609375, gain.albumGainDb)
        assertTrue(gain.isR128)
    }

    @Test
    fun writersKeepEndpointsAndRoundInRangeValuesToTheNearestQ78Step() {
        for ((value, integer) in R128TagFixtures.rounded) {
            val tags = gainTags(value, value)
            for (original in listOf(TagStressFixtures.flac(emptyList()), R128TagFixtures.opus())) {
                val written = AudioKodec.writeTags(original, tags)
                val entries = AudioKodec.readTags(written).raw.blocks.flatMap { it.entries }
                for (name in names) assertEquals(listOf(integer.toString()), entries.filter { it.key == name }.map { it.text })
                val gain = assertNotNull(AudioKodec.readTags(written).replayGain)
                // Q7.8 quantisation loses at most half of one 1/256 dB step.
                assertEquals(value, gain.trackGainDb!!, 1.0 / 512)
                assertEquals(value, gain.albumGainDb!!, 1.0 / 512)
                if (original[0] == 'f'.code.toByte()) assertContentEquals(TagStressFixtures.flacAudio(original), TagStressFixtures.flacAudio(written))
                else assertContentEquals(R128TagFixtures.audioPacket, written.takeLast(R128TagFixtures.audioPacket.size).toByteArray())
            }
        }
    }

    @Test
    fun writersRejectOutsideTheRangeBeforeRoundingOrEmittingAnything() {
        for (value in R128TagFixtures.invalidGains) {
            for (album in listOf(false, true)) {
                val tags = if (album) gainTags(0.0, value) else gainTags(value, null)
                for (original in listOf(TagStressFixtures.flac(emptyList()), R128TagFixtures.opus())) {
                    val sink = ByteArrayAudioSink()
                    sink.write(byteArrayOf(7, 8, 9))
                    val error = assertFailsWith<UnsupportedFeatureException> {
                        AudioKodec.writeTags(original.asAudioSource(), tags, sink)
                    }
                    assertTrue(error.message.orEmpty().contains(if (album) names[1] else names[0]))
                    assertContentEquals(byteArrayOf(7, 8, 9), sink.toByteArray())
                }
                val sink = ByteArrayAudioSink()
                assertFailsWith<UnsupportedFeatureException> {
                    AudioKodec.encode(FloatArray(16).asPcmSource(AudioFormat(44_100, 1)), EncodeTarget.flac(), sink, tags)
                }
                assertEquals(0L, sink.position)
            }
        }
    }

    @Test
    fun theQ78LimitDoesNotRestrictReplayGainTextOrReferenceConversion() {
        val audio = ByteArray(32) { 0x55 }
        for (r128 in listOf(false, true)) {
            val tags = TagBundle(emptyMap(), replayGain = ReplayGain(trackGainDb = 1000.0, isR128 = r128))
            for (format in listOf(TagFormat.ID3V2_4, TagFormat.APEV2)) {
                val bytes = AudioKodec.writeTags(audio, tags, TagWriteOptions(formats = setOf(format)))
                val gain = assertNotNull(AudioKodec.readTags(bytes).replayGain)
                assertEquals(if (r128) 1005.0 else 1000.0, gain.trackGainDb)
                assertEquals(false, gain.isR128)
            }
        }
        val bytes = AudioKodec.writeTags(TagStressFixtures.flac(emptyList()), TagBundle(emptyMap(), replayGain = ReplayGain(trackGainDb = 1000.0)))
        assertEquals(1000.0, AudioKodec.readTags(bytes).replayGain!!.trackGainDb)
    }

    private fun inputs(name: String, value: String): List<ByteArray> = listOf(
        R128TagFixtures.opus(listOf("$name=$value")),
        TagStressFixtures.flac(listOf("$name=$value")),
        ByteArray(32) { 0x55 } + ApeBytes(listOf(name to value.encodeToByteArray())),
        R128TagFixtures.id3(name, value),
    )

    private fun gainTags(track: Double?, album: Double?): TagBundle = TagBundle(
        mapOf(TagField.TITLE to listOf("Changed")),
        replayGain = ReplayGain(trackGainDb = track, albumGainDb = album, isR128 = true),
    )
}

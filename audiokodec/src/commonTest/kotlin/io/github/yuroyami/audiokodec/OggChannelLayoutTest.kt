package io.github.yuroyami.audiokodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OggChannelLayoutTest {
    @Test
    fun opusSpeakerMappingsAgreeAcrossProbePlaybackAndDecodedAudio() {
        for (family in 0..1) {
            for (channels in 1..if (family == 0) 2 else 8) {
                checkFormat(opus(family, channels), AudioFormat(48_000, speakerLayout(channels)))
            }
        }
    }

    @Test
    fun ambisonicAndUnspecifiedOpusMappingsDoNotInventSpeakers() {
        for (family in listOf(2, 3, 255)) {
            val counts = if (family == 255) (1..8).toList() else listOf(4, 6)
            for (channels in counts) {
                checkFormat(opus(family, channels), AudioFormat(48_000, ChannelLayout.unknown(channels)))
            }
        }
    }

    @Test
    fun everyOggFlacChannelCountAgreesWithTheNativeMapping() {
        for (channels in 1..8) {
            val expected = AudioFormat(44_100, speakerLayout(channels))
            val native = AudioKodec.encode(FloatArray(64 * channels), expected, EncodeTarget.flac(16))
            val identification = byteArrayOf(0x7F) + "FLAC".encodeToByteArray() + byteArrayOf(1, 0, 0, 1) + native.copyOf(42)
            val body = TagStressFixtures.commentBody(emptyList())
            val comments = byteArrayOf(0x84.toByte(), 0, 0, body.size.toByte()) + body
            val bytes = OggBuilder()
                .page(1, 0, 0, 2, listOf(identification))
                .page(1, 1, 0, 0, listOf(comments))
                .page(1, 2, 64, 4, listOf(TagStressFixtures.flacAudio(native)))
                .build()
            checkFormat(bytes, expected)
            assertEquals(expected, AudioKodec.probe(native).format)
        }
    }

    private fun checkFormat(bytes: ByteArray, expected: AudioFormat) {
        assertEquals(expected, AudioKodec.probe(bytes).format)
        AudioKodec.open(bytes).use { decoder ->
            assertEquals(expected, decoder.format)
            assertEquals(expected, decoder.info.format)
            assertEquals(expected, assertNotNull(decoder.nextFrame()).format)
        }
        assertEquals(expected, AudioKodec.decode(bytes).format)
    }

    private fun opus(family: Int, channels: Int): ByteArray {
        val extra = if (family == 0) 0 else if (family == 3) 2 + 2 * channels * channels else 2 + channels
        val head = ByteArray(19 + extra)
        "OpusHead".encodeToByteArray().copyInto(head)
        head[8] = 1
        head[9] = channels.toByte()
        head[18] = family.toByte()
        if (family != 0) {
            head[19] = channels.toByte() // One mono stream for each output channel.
            for (channel in 0 until channels) {
                if (family == 3) {
                    val at = 21 + 2 * (channel * channels + channel)
                    head[at] = 0xFF.toByte()
                    head[at + 1] = 0x7F // Q15 identity demixing matrix.
                } else {
                    head[21 + channel] = channel.toByte()
                }
            }
        }
        val silence = byteArrayOf(0xF8.toByte(), 0xFF.toByte(), 0xFE.toByte())
        val packet = ByteArrayAudioSink()
        if (family != 0) repeat(channels - 1) {
            packet.write(byteArrayOf(0xF8.toByte(), 2, 0xFF.toByte(), 0xFE.toByte()))
        }
        packet.write(silence)
        return OggBuilder()
            .page(1, 0, 0, 2, listOf(head))
            .page(1, 1, 0, 0, listOf("OpusTags".encodeToByteArray() + TagStressFixtures.commentBody(emptyList())))
            .page(1, 2, 960, 4, listOf(packet.toByteArray()))
            .build()
    }

    private fun speakerLayout(channels: Int): ChannelLayout = if (channels == 7) {
        ChannelLayout.of(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_CENTER,
            AudioChannel.LOW_FREQUENCY, AudioChannel.BACK_CENTER, AudioChannel.SIDE_LEFT, AudioChannel.SIDE_RIGHT)
    } else {
        ChannelLayout.canonical(channels)
    }
}

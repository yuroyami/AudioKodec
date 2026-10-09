package io.github.yuroyami.audiokodec

/** Small valid files; each new decoder container must be represented here. */
internal object HostileInputFixtures {
    fun files(): List<Pair<String, ByteArray>> {
        val format = AudioFormat(44_100, 1)
        val samples = FloatArray(16) { (it * 997 - 8000) / 32768f }
        return listOf(
            "WAV" to AudioKodec.encode(samples, format, EncodeTarget.wav(16)),
            "AIFF" to AudioKodec.encode(samples, format, EncodeTarget.aiff(16)),
            "AU" to AudioKodec.encode(samples, format, EncodeTarget.au(16)),
            "FLAC" to shortFlac(samples, format),
            "Ogg Vorbis" to VorbisTest().file(),
            "Ogg Vorbis floor 0" to VorbisFloor0Fixtures.file(order = 3, packets = 3),
            "Ogg FLAC" to OggGranuleFixtures.flac(),
            "Ogg Opus" to OggSharedHeaderFixtures.opus(),
            "Ogg chained FLAC" to OggChainFixtures.hostile(),
            "CAF ALAC" to CafAlacTest().hostileFixture(),
            "CAF IMA" to CafImaTest().file(1, 4),
            "ADTS AAC" to AacTest().hostileFixture(),
            "WavPack" to WavPackTest().hostileFixture(),
            "Monkey's Audio" to ApeTest().hostileFixture(),
            "True Audio" to TtaTest().hostileFixture(),
            "Musepack" to MusepackTest().hostileFixture(),
            "Ogg Speex" to SpeexTest().hostileFixture(),
            "AMR" to AmrTest().hostileFixture(),
            "AMR-WB" to AmrTest().hostileWidebandFixture(),
            "MP4 MPEG" to MpegMp4Fixtures.file(MpegMp4Fixtures.packets(MpegTest().silentFrames(3, count = 2, compact = true))),
        ) + HandBuiltFileTest().hostileFixtures() + Mp4Test().hostileFixtures() +
            (1..3).map { "MPEG Layer $it" to MpegTest().silentFrames(it, count = 2, compact = true) } +
            (1..3).map { "MPEG free-format Layer $it" to MpegFreeFormatFixtures.hostile(it) }
    }

    private fun shortFlac(samples: FloatArray, format: AudioFormat): ByteArray {
        val encoded = AudioKodec.encode(samples, format, EncodeTarget.flac(16))
        val audioOffset = AudioKodec.probe(encoded).dataOffset.toInt()
        // STREAMINFO is 34 bytes. Drop optional metadata, including the
        // encoder's 4 KiB padding, and mark STREAMINFO as the last block.
        val streamInfo = encoded.copyOfRange(0, 42).also { it[4] = 0x80.toByte() }
        return streamInfo + encoded.copyOfRange(audioOffset, encoded.size)
    }

    /** Preserve the sniffer's signature while randomising every other byte. */
    fun magicRanges(container: AudioContainer): List<IntRange> = when (container) {
        AudioContainer.WAV, AudioContainer.RF64, AudioContainer.AIFF -> listOf(0..3, 8..11)
        AudioContainer.WAVE64 -> listOf(0..15, 24..39)
        AudioContainer.MP4 -> listOf(4..7)
        AudioContainer.CAF -> listOf(0..7)
        else -> listOf(0..3)
    }
}

package io.github.yuroyami.audiokodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SniffWindowTest {
    @Test
    fun largeLeadingTagsMakeShortWindowsInconclusive() {
        val samples = TestSignals.quantise(TestSignals.sine(128, 1), 16)
        val format = AudioFormat(44_100, 1)
        for (target in listOf(EncodeTarget.wav(16), EncodeTarget.flac(16), EncodeTarget.aiff(16))) {
            val audio = AudioKodec.encode(samples, format, target)
            val expected = AudioKodec.sniff(audio)
            for (major in 2..4) {
                val tagged = SniffWindowFixtures.prefix(70_000, major) + audio
                assertNull(AudioKodec.sniff(tagged.copyOf(AudioKodec.SNIFF_HEADER_BYTES)), "$target, ID3v2.$major")
                assertEquals(expected, AudioKodec.sniff(tagged))
            }
        }
    }

    @Test
    fun metadataAloneNeverIdentifiesTheAudioContainer() {
        for (major in 2..4) {
            for (size in listOf(0, 128, 70_000, 0x0FFF_FFFF)) {
                assertNull(AudioKodec.sniff(SniffWindowFixtures.header(size, major)))
            }
            for (size in listOf(0, 128)) assertNull(AudioKodec.sniff(SniffWindowFixtures.prefix(size, major)))
        }
    }

    @Test
    fun visibleBytesHaveTheSameMeaningWithOrWithoutLeadingTags() {
        val signatures = listOf("RIFFxxxxWAVE".encodeToByteArray(), "fLaC".encodeToByteArray(),
            byteArrayOf(-1, -5, -112, 0), byteArrayOf(-1, -15, 80, -128), byteArrayOf(1, 2, 3, 4, 5))
        for (major in 2..4) {
            for (signature in signatures) {
                for (length in 0..signature.size) {
                    val visible = signature.copyOf(length)
                    assertEquals(AudioKodec.sniff(visible), AudioKodec.sniff(SniffWindowFixtures.prefix(128, major) + visible),
                        "major=$major, length=$length")
                }
            }
        }
    }

    @Test
    fun footerBytesAndStackedTagsMustFitBeforeNamingTheContainer() {
        val prefix = SniffWindowFixtures.prefix(128, 4, footer = true) + SniffWindowFixtures.prefix(257, 3)
        val wave = "RIFFxxxxWAVE".encodeToByteArray()
        val tagged = prefix + wave
        for (length in prefix.size - 12 until tagged.size) assertNull(AudioKodec.sniff(tagged.copyOf(length)))
        assertEquals(AudioContainer.WAV, AudioKodec.sniff(tagged))
        val footerTag = SniffWindowFixtures.prefix(128, 4, footer = true)
        for (length in footerTag.size - 10..footerTag.size) assertNull(AudioKodec.sniff(footerTag.copyOf(length)))
    }

    @Test
    fun aTruncatedStackNeverBecomesAnMpegGuess() {
        val tag = SniffWindowFixtures.prefix(0, 4)
        val prefix = ByteArray(tag.size * 301)
        repeat(301) { tag.copyInto(prefix, tag.size * it) }
        for (length in 0..prefix.size) assertNull(AudioKodec.sniff(prefix.copyOf(length)), "length=$length")
        assertEquals(AudioContainer.FLAC, AudioKodec.sniff(prefix + "fLaC".encodeToByteArray()))
    }

    @Test
    fun invalidTagHeadersDoNotIdentifyMpegAudio() {
        for ((index, byte) in listOf(3 to 0, 3 to 5, 3 to 255, 4 to 255, 5 to 1, 6 to 128, 7 to 128, 8 to 128, 9 to 128)) {
            val prefix = SniffWindowFixtures.prefix(128, 4).also { it[index] = byte.toByte() }
            assertNull(AudioKodec.sniff(prefix + "RIFFxxxxWAVE".encodeToByteArray()), "index=$index, byte=$byte")
        }
    }

    @Test
    fun validSourceDetectionStillReadsPastLargeTags() {
        val samples = TestSignals.quantise(TestSignals.sine(128, 1), 16)
        val format = AudioFormat(44_100, 1)
        for (target in listOf(EncodeTarget.wav(16), EncodeTarget.flac(16), EncodeTarget.aiff(16), EncodeTarget.aiffFloat(32))) {
            val audio = AudioKodec.encode(samples, format, target)
            val info = AudioKodec.probe(audio)
            for (major in 2..4) {
                val prefix = SniffWindowFixtures.prefix(70_000, major, footer = major == 4)
                val tagged = prefix + audio
                val read = AudioKodec.probe(tagged)
                assertEquals(info.container, read.container)
                assertEquals(info.dataOffset + prefix.size, read.dataOffset)
                AudioKodec.open(tagged).use { assertEquals(info.format, it.format) }
                TestSignals.assertExact(samples, AudioKodec.decode(tagged).samples)
                assertEquals(info.container, AudioKodec.sniff(tagged))
            }
        }
    }
}

internal object SniffWindowFixtures {
    fun header(body: Int, major: Int, footer: Boolean = false): ByteArray = ByteArray(10).also {
        "ID3".encodeToByteArray().copyInto(it)
        it[3] = major.toByte()
        it[5] = if (footer) 0x10 else 0
        for (index in 0..3) it[6 + index] = ((body ushr ((3 - index) * 7)) and 127).toByte()
    }
    fun prefix(body: Int, major: Int, footer: Boolean = false): ByteArray {
        val header = header(body, major, footer)
        return header + ByteArray(body) + if (footer) header.copyOf().also { "3DI".encodeToByteArray().copyInto(it) } else byteArrayOf()
    }
}

package io.github.yuroyami.audiokodec.docs

import io.github.yuroyami.audiokodec.AudioChannel
import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.Dither
import io.github.yuroyami.audiokodec.EncodeTarget
import io.github.yuroyami.audiokodec.IntPcmAccess
import io.github.yuroyami.audiokodec.Levels
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.tag.ReplayGain
import io.github.yuroyami.audiokodec.tag.TagChapter
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The samples of the longer guides (decoding, encoding, tags and files)
 * that the task pages do not already cover. Each is the code block of its
 * guide, run on an input made here.
 */
class GuideSamples {
    private val printed = ArrayList<String>()

    private fun println(line: Any?) {
        printed += line.toString()
    }

    @Test
    fun quickStart() {
        val bytes = Fixtures.flac

        // --8<-- [start:quick-start]
        val audio = AudioKodec.decode(bytes)
        println("${audio.format.sampleRate} Hz, ${audio.format.channels} channels")

        val tags = AudioKodec.readTags(bytes)
        println(tags[TagField.TITLE])
        // --8<-- [end:quick-start]

        assertEquals(listOf("44100 Hz, 2 channels", "Two Seconds"), printed)
    }

    @Test
    fun nameAFormat() {
        val firstBytes = Fixtures.flac.copyOf(AudioKodec.SNIFF_HEADER_BYTES)

        // --8<-- [start:sniff]
        val container = AudioKodec.sniff(firstBytes)
        if (container?.isDecodable == true) {
            println("AudioKodec can read this ${container.displayName} file")
        }
        // --8<-- [end:sniff]

        assertEquals(AudioContainer.FLAC, container)
        assertEquals(listOf("AudioKodec can read this FLAC file"), printed)
        assertEquals(false, AudioKodec.sniff("MThd".encodeToByteArray() + ByteArray(12))?.isDecodable)
    }

    @Test
    fun exactIntegers() {
        val bytes = AudioKodec.encode(intArrayOf(1, -1, 0x7FFFFFFF, Int.MIN_VALUE), AudioFormat(48_000, 2), 32, EncodeTarget.wav(32))

        // --8<-- [start:int-access]
        AudioKodec.open(bytes).use { decoder ->
            if (decoder is IntPcmAccess) {
                val block = decoder.nextIntFrame()   // exactly what the file holds
                println(block?.samples?.take(4))     // [1, -1, 2147483647, -2147483648]
            }
        }
        // --8<-- [end:int-access]

        assertEquals(listOf("[1, -1, 2147483647, -2147483648]"), printed)
    }

    @Test
    fun encodeIntegers() {
        val integerSamples = intArrayOf(1, -1, 0x7FFFFFFF, Int.MIN_VALUE)
        val format = AudioFormat(48_000, 2)

        // --8<-- [start:int-encode]
        val exact = AudioKodec.encode(integerSamples, format, bitDepth = 32, target = EncodeTarget.aiff(32))
        // --8<-- [end:int-encode]

        AudioKodec.open(exact).use { decoder ->
            val block = assertNotNull((decoder as IntPcmAccess).nextIntFrame())
            assertContentEquals(integerSamples, block.samples.copyOf(block.sampleCount))
        }
    }

    @Test
    fun channelLayouts() {
        // --8<-- [start:layout]
        val format = AudioFormat(48_000, ChannelLayout.SURROUND_5_1)
        println(format.channelLayout.channels)  // [FRONT_LEFT, FRONT_RIGHT, ...]
        // --8<-- [end:layout]

        assertEquals(AudioChannel.FRONT_LEFT, format.channelLayout.channels.first())
        assertTrue(printed.single().startsWith("[FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, LOW_FREQUENCY"))
    }

    @Test
    fun salvageATruncatedFile() {
        val bytes = Fixtures.flac.copyOf(Fixtures.flac.size * 3 / 4)
        assertFailsWith<TruncatedStreamException> { AudioKodec.decode(bytes) }

        // --8<-- [start:tolerant]
        val salvaged = AudioKodec.decode(bytes, DecodeOptions(tolerant = true))
        // --8<-- [end:tolerant]

        assertTrue(salvaged.frames in 1 until 88_200)
    }

    @Test
    fun encodeSeveralFormats() {
        val samples = Fixtures.samples
        val bytes = Fixtures.flac

        // --8<-- [start:encode-wav]
        val wav = AudioKodec.encode(samples, AudioFormat(44_100, 2), EncodeTarget.wav(16))
        // --8<-- [end:encode-wav]
        assertEquals(AudioContainer.WAV, AudioKodec.probe(wav).container)

        // --8<-- [start:encode-decoded]
        val decoded = AudioKodec.decode(bytes)
        val asWav = AudioKodec.encode(decoded, EncodeTarget.wav(16))
        val asFlac = AudioKodec.encode(decoded, EncodeTarget.flac(16))
        // --8<-- [end:encode-decoded]
        assertContentEquals(AudioKodec.decode(asWav).samples, AudioKodec.decode(asFlac).samples)

        val tags = AudioKodec.readTags(bytes)
        // --8<-- [start:encode-alac]
        val m4a = AudioKodec.encode(decoded, EncodeTarget.alac(24), tags = tags)
        // --8<-- [end:encode-alac]
        assertEquals(AudioContainer.MP4, AudioKodec.probe(m4a).container)
        assertEquals("Two Seconds", AudioKodec.readTags(m4a)[TagField.TITLE])

        val format = AudioFormat(44_100, 2)
        // --8<-- [start:encode-dither]
        val dithered = AudioKodec.encode(samples, format, EncodeTarget.wav(16, dither = Dither.Tpdf()))
        // --8<-- [end:encode-dither]
        assertEquals(16, AudioKodec.probe(dithered).bitDepth)

        // --8<-- [start:encode-flac]
        val flac = AudioKodec.encode(samples, format, EncodeTarget.flac(bitDepth = 16, effort = 5))
        // --8<-- [end:encode-flac]
        assertEquals(true, AudioKodec.verifyFlacMd5(flac))

        // --8<-- [start:encode-wavpack]
        val wv = AudioKodec.encode(samples, format, EncodeTarget.wavPack(bitDepth = 24, effort = 2))
        // --8<-- [end:encode-wavpack]
        assertEquals(AudioCodec.WAVPACK, AudioKodec.probe(wv).codec)
        // Lossless: 24 bits hold these floats to within half a step.
        val unpacked = AudioKodec.decode(wv).samples
        for (i in samples.indices) assertEquals(samples[i], unpacked[i], 1f / (1 shl 23))

        // --8<-- [start:encode-vorbis]
        val ogg = AudioKodec.encode(samples, format, EncodeTarget.vorbis(quality = 4f))
        // --8<-- [end:encode-vorbis]
        assertEquals(AudioCodec.VORBIS, AudioKodec.probe(ogg).codec)
        assertEquals(samples.size / 2, AudioKodec.decode(ogg).frames)

        // --8<-- [start:encode-opus]
        val opus = AudioKodec.encode(decoded, EncodeTarget.opus(bitrate = 128_000), tags = tags)
        // --8<-- [end:encode-opus]
        val opusInfo = AudioKodec.probe(opus)
        assertEquals(AudioCodec.OPUS, opusInfo.codec)
        assertEquals("Two Seconds", AudioKodec.readTags(opus)[TagField.TITLE])
        // Resampled to 48 kHz, and exactly as long as the input at that rate.
        val rate = decoded.format.sampleRate.toLong()
        assertEquals((decoded.frames * 48_000L + rate - 1) / rate, opusInfo.durationFrames)
        assertEquals(opusInfo.durationFrames, AudioKodec.decode(opus).frames.toLong())

        // --8<-- [start:encode-mp3]
        val mp3 = AudioKodec.encode(decoded, EncodeTarget.mp3(bitrate = 192_000), tags = tags)
        val smaller = AudioKodec.encode(decoded, EncodeTarget.mp3Vbr(quality = 5))
        // --8<-- [end:encode-mp3]
        val mp3Info = AudioKodec.probe(mp3)
        assertEquals(AudioCodec.MP3, mp3Info.codec)
        assertEquals("Two Seconds", AudioKodec.readTags(mp3)[TagField.TITLE])
        // Gapless: exactly as long as the input, at either setting.
        assertEquals(decoded.frames.toLong(), mp3Info.durationFrames)
        assertEquals(decoded.frames, AudioKodec.decode(mp3).frames)
        assertEquals(decoded.frames, AudioKodec.decode(smaller).frames)
        assertTrue(smaller.size < mp3.size)
    }

    @Test
    fun editChapters() {
        val mp3 = Fixtures.ffmpegEncode(Fixtures.samples, "chapters.mp3", "-c:a", "libmp3lame")
        val bytes = AudioKodec.writeTags(
            mp3,
            AudioKodec.readTags(mp3).edit {
                setChapters(listOf(TagChapter("ch0", 0, 1000, "Intro"), TagChapter("ch1", 1000, 2000, "Body")))
            },
        )

        // --8<-- [start:chapters]
        val tags = AudioKodec.readTags(bytes)
        val first = tags.chapters.first().copy(title = "A new title", startMillis = 500)
        val edited = tags.edit {
            setChapters(listOf(first) + tags.chapters.drop(1))
        }
        val written = AudioKodec.writeTags(bytes, edited, TagWriteOptions(id3Version = 4))
        // --8<-- [end:chapters]

        val chapters = AudioKodec.readTags(written).chapters
        assertEquals(listOf("A new title", "Body"), chapters.map { it.title })
        assertEquals(500, chapters.first().startMillis)
    }

    @Test
    fun applyAStoredGain() {
        val bytes = AudioKodec.writeTags(Fixtures.flac, AudioKodec.readTags(Fixtures.flac).edit { setReplayGain(ReplayGain(trackGainDb = -6.0)) })
        val audio = AudioKodec.decode(bytes)
        val before = audio.samples.copyOf()
        val tags = AudioKodec.readTags(bytes)

        // --8<-- [start:replay-gain]
        val gain = tags.replayGain
        val trackGain = gain?.trackGainDb
        if (gain != null && trackGain != null) {
            // An R128 gain aims at -23 LUFS. Add 5 dB to play it at the -18 LUFS of ReplayGain 2.
            val gainDb = if (gain.isR128) trackGain + 5.0 else trackGain
            Levels.applyGain(audio.samples, audio.format.channels, gainDb)
        }
        // --8<-- [end:replay-gain]

        // -6 dB is a factor of about a half.
        assertEquals(before[1001] * 0.501f, audio.samples[1001], 0.001f)
    }
}

package io.github.yuroyami.audiokodecs

import io.github.yuroyami.audiokodecs.internal.tag.Base64
import io.github.yuroyami.audiokodecs.internal.tag.FlacPicture
import io.github.yuroyami.audiokodecs.internal.tag.TagAssembler
import io.github.yuroyami.audiokodecs.internal.tag.TagText
import io.github.yuroyami.audiokodecs.tag.RawTagBlock
import io.github.yuroyami.audiokodecs.tag.RawTagEntry
import io.github.yuroyami.audiokodecs.tag.ReplayGain
import io.github.yuroyami.audiokodecs.tag.TagChapter
import io.github.yuroyami.audiokodecs.tag.TagField
import io.github.yuroyami.audiokodecs.tag.TagFormat
import io.github.yuroyami.audiokodecs.tag.TagPicture
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiInvariantTest {

    @Test
    fun channelLayoutsRejectImpossibleShapes() {
        assertFailsWith<IllegalArgumentException> { ChannelLayout.of() }
        assertFailsWith<IllegalArgumentException> { ChannelLayout.unknown(0) }
        assertFailsWith<IllegalArgumentException> { ChannelLayout.unknown(4097) }
        assertFailsWith<IllegalArgumentException> {
            ChannelLayout.of(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_LEFT)
        }
        assertFailsWith<IllegalArgumentException> {
            ChannelLayout.fromWaveMask(0, -1)
        }
    }

    private val mono = AudioFormat(44_100, 1)
    private val stereo = AudioFormat(44_100, 2)

    @Test
    fun frameLengthsAreCheckedWithoutIntegerOverflow() {
        assertFailsWith<IllegalArgumentException> {
            AudioFrame(stereo, FloatArray(0), Int.MAX_VALUE, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            IntAudioFrame(stereo, IntArray(0), Int.MAX_VALUE, 0, 16)
        }
        assertFailsWith<IllegalArgumentException> {
            IntAudioFrame(mono, IntArray(1), 1, 0, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AudioFrame(mono, FloatArray(1), 1, -1)
        }
    }

    @Test
    fun arrayPcmSourcesValidateTheirShapeAndReadWindow() {
        assertFailsWith<IllegalArgumentException> {
            FloatArray(2).asPcmSource(stereo, frameCount = 2)
        }
        val source = FloatArray(4).asPcmSource(stereo)
        assertFailsWith<IllegalArgumentException> {
            source.read(FloatArray(4), 0, -1)
        }
        assertFailsWith<IllegalArgumentException> {
            source.read(FloatArray(3), 0, 2)
        }
        assertEquals(0, source.read(FloatArray(0), 0, 0))
    }

    @Test
    fun pcmUtilitiesRejectCountsOutsideTheirInput() {
        assertFailsWith<IllegalArgumentException> {
            Pcm.floatFromInt(IntArray(1), 16, count = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            Pcm.intFromFloat(FloatArray(1), 16, count = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            Pcm.deinterleave(FloatArray(0), 2, Int.MAX_VALUE)
        }
        assertFailsWith<IllegalArgumentException> {
            Pcm.clampInPlace(FloatArray(1), count = 2)
        }
        assertContentEquals(
            intArrayOf(0, Int.MAX_VALUE, Int.MIN_VALUE),
            Pcm.intFromFloat(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY), 32),
        )
    }

    @Test
    fun byteArraySourcesAndSinksRejectInvalidWindows() {
        val source = byteArrayOf(1).asAudioSource()
        assertFailsWith<IllegalArgumentException> {
            source.readAt(-1, ByteArray(1), 0, 1)
        }
        assertEquals(0, source.readAt(1, ByteArray(0), 0, 0))

        val sink = ByteArrayAudioSink()
        sink.write(byteArrayOf(1, 2))
        assertFailsWith<IllegalArgumentException> {
            sink.patchAt(0, byteArrayOf(3), offset = 1, count = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            ByteArrayAudioSink(-1)
        }
    }

    @Test
    fun encodersRejectBrokenPcmSourceCountsAndPromises() {
        val overReporting = object : PcmSource {
            override val format: AudioFormat = mono
            override val frameCount: Long? = null
            override fun read(into: FloatArray, offset: Int, frames: Int): Int = frames + 1
        }
        assertFailsWith<AudioKodecsException> {
            AudioKodecs.encode(overReporting, EncodeTarget.wav())
        }

        var served = false
        val brokenPromise = object : PcmSource {
            override val format: AudioFormat = mono
            override val frameCount: Long = 2
            override fun read(into: FloatArray, offset: Int, frames: Int): Int {
                if (served) return 0
                served = true
                into[offset] = 0.25f
                return 1
            }
        }
        assertFailsWith<AudioKodecsException> {
            AudioKodecs.encode(brokenPromise, EncodeTarget.wav())
        }
    }

    @Test
    fun tagModelsRejectImpossibleNumericValues() {
        assertFailsWith<IllegalArgumentException> {
            TagPicture(mimeType = "image/png", bytes = ByteArray(0), width = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            TagChapter("", 0)
        }
        assertFailsWith<IllegalArgumentException> {
            TagChapter("chapter", 10, 9)
        }
        assertFailsWith<IllegalArgumentException> {
            ReplayGain(trackGainDb = Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            ReplayGain(trackPeak = -0.1)
        }
        assertFailsWith<IllegalArgumentException> {
            RawTagBlock(TagFormat.ID3V2_4, -1, 10, emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            RawTagEntry("", text = "value")
        }
    }

    @Test
    fun audioInfoRejectsImpossiblePublicState() {
        assertFailsWith<IllegalArgumentException> {
            AudioInfo(
                AudioContainer.WAV,
                AudioCodec.PCM_INTEGER,
                mono,
                durationFrames = -1,
                bitDepth = 16,
                bitrate = 1,
                isVariableBitrate = false,
                dataOffset = 0,
                dataSize = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AudioInfo(
                AudioContainer.WAV,
                AudioCodec.PCM_INTEGER,
                mono,
                durationFrames = 0,
                bitDepth = 33,
                bitrate = 1,
                isVariableBitrate = false,
                dataOffset = 0,
                dataSize = 0,
            )
        }
    }

    @Test
    fun tagCollectionInputsAreSnapshotted() {
        val values = mutableListOf("Original")
        val fields = mutableMapOf(TagField.TITLE to values)
        val blocks = mutableListOf<RawTagBlock>()
        val tags = io.github.yuroyami.audiokodecs.tag.TagBundle(fields, raw = io.github.yuroyami.audiokodecs.tag.RawTags(blocks))
        values[0] = "Changed"
        fields.clear()
        blocks.add(RawTagBlock(TagFormat.ID3V2_4, 0, 10, emptyList()))
        assertEquals("Original", tags[TagField.TITLE])
        assertEquals(0, tags.raw.blocks.size)

        val channels = mutableListOf(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT)
        val layout = ChannelLayout.of(channels)
        channels.clear()
        assertEquals(2, layout.count)

        val formats = mutableSetOf(TagFormat.ID3V2_4)
        val writeOptions = io.github.yuroyami.audiokodecs.tag.TagWriteOptions(formats = formats)
        formats.clear()
        assertEquals(setOf(TagFormat.ID3V2_4), writeOptions.formats)

        assertTrue(
            io.github.yuroyami.audiokodecs.tag.TagBundle(
                mapOf(TagField.TITLE to emptyList()),
            ).isEmpty,
        )
    }

    @Test
    fun malformedBase64AndPictureNumbersAreRejectedCanonically() {
        assertNull(Base64.decodeOrNull("A"))
        assertNull(Base64.decodeOrNull("AA=A"))
        assertNull(Base64.decodeOrNull("AB=="))
        assertContentEquals(byteArrayOf(0), Base64.decodeOrNull("AA"))

        val picture = ByteArray(32)
        picture[12] = 0x80.toByte() // width = 0x80000000, not a negative pixel count
        assertNull(FlacPicture.parse(picture))
    }

    @Test
    fun strictUtf8RejectsNonCanonicalAndOutOfRangeSequences() {
        val valid = "plain \u20AC \uD83D\uDE00".encodeToByteArray()
        assertEquals(
            "plain \u20AC \uD83D\uDE00",
            TagText.decodeUtf8OrNull(valid, 0, valid.size),
        )
        assertNull(TagText.decodeUtf8OrNull(byteArrayOf(0xC0.toByte(), 0x80.toByte()), 0, 2))
        assertNull(
            TagText.decodeUtf8OrNull(
                byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
                0,
                3,
            ),
        )
        assertNull(
            TagText.decodeUtf8OrNull(
                byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
                0,
                4,
            ),
        )
        assertNull(TagText.decodeUtf8OrNull(byteArrayOf(0xE2.toByte(), 0x82.toByte()), 0, 2))
    }

    @Test
    fun tagPictureDecodingHonoursTheAllocationGuard() {
        assertFailsWith<AllocationGuardException> {
            Base64.decodeOrNull(
                "AAAA",
                DecodeOptions(maxAllocationBytes = 2),
            )
        }
    }

    @Test
    fun wholeFileDecodeClosesTheSourceWhenOpeningFails() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        var closes = 0
        val source = object : AudioSource {
            override val size: Long = bytes.size.toLong()

            override fun readAt(
                position: Long,
                into: ByteArray,
                offset: Int,
                count: Int,
            ): Int {
                if (count == 0) return 0
                if (position >= bytes.size) return -1
                val copied = minOf(count, bytes.size - position.toInt())
                bytes.copyInto(into, offset, position.toInt(), position.toInt() + copied)
                return copied
            }

            override fun close() {
                closes++
            }
        }

        assertFailsWith<AudioKodecsException> { AudioKodecs.decode(source) }
        assertEquals(1, closes)
    }

    @Test
    fun flacVerificationClosesTheSourceWhenOpeningFails() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        var closes = 0
        val source = object : AudioSource {
            override val size: Long = bytes.size.toLong()

            override fun readAt(
                position: Long,
                into: ByteArray,
                offset: Int,
                count: Int,
            ): Int {
                if (count == 0) return 0
                if (position >= bytes.size) return -1
                val copied = minOf(count, bytes.size - position.toInt())
                bytes.copyInto(into, offset, position.toInt(), position.toInt() + copied)
                return copied
            }

            override fun close() {
                closes++
            }
        }

        assertFailsWith<AudioKodecsException> { AudioKodecs.verifyFlacMd5(source) }
        assertEquals(1, closes)
    }

    @Test
    fun malformedReplayGainTextDoesNotEraseAValidEarlierValue() {
        val tags = TagAssembler.assemble(
            listOf(
                RawTagBlock(
                    TagFormat.VORBIS_COMMENT,
                    0,
                    0,
                    listOf(
                        RawTagEntry("REPLAYGAIN_TRACK_GAIN", text = "-7.5 dB"),
                        RawTagEntry("REPLAYGAIN_TRACK_GAIN", text = "NaN"),
                        RawTagEntry("REPLAYGAIN_TRACK_PEAK", text = "-1"),
                        RawTagEntry("TITLE", text = "Still readable"),
                    ),
                ),
            ),
        )
        assertEquals(-7.5, tags.replayGain?.trackGainDb)
        assertNull(tags.replayGain?.trackPeak)
        assertEquals("Still readable", tags[TagField.TITLE])
    }
}

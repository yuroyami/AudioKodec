package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.alac.AlacConfig
import io.github.yuroyami.audiokodec.tag.PictureType
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagChapter
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagPicture
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Checks ALAC encoding into MP4 on every target by decoding the result.
 *
 * ALAC is lossless, so every file has to decode to exactly the integers that
 * went in, at every depth and channel count, in the source's own layout. The
 * oracle suite checks on the JVM that the packets are the ones Apple's
 * reference encoder writes, and that ffmpeg and Apple's decoder read them.
 */
class AlacEncodeTest {

    @Test
    fun everyDepthAndChannelCountDecodesToWhatWentIn() {
        for (channels in 1..8) {
            val layout = ChannelLayout.of(AlacConfig.storedOrder(channels).sortedBy { it.ordinal })
            for (bits in intArrayOf(16, 20, 24, 32)) {
                val what = "$channels ch, $bits-bit"
                val frames = 4096 + 300 + channels * 17
                val samples = signal(channels, bits, frames)
                val file = AudioKodec.encode(samples, AudioFormat(44_100, layout), bits, EncodeTarget.alac(bits))
                val info = AudioKodec.probe(file)
                assertEquals(AudioContainer.MP4, info.container, what)
                assertEquals(AudioCodec.ALAC, info.codec, what)
                assertEquals(bits, info.bitDepth, what)
                assertEquals(frames.toLong(), info.durationFrames, what)
                assertEquals(layout, info.format.channelLayout, what)
                assertContentEquals(samples, decode(file), what)
            }
        }
    }

    @Test
    fun theCanonicalLayoutsMapOntoAlacsSpeakers() {
        for (layout in listOf(ChannelLayout.MONO, ChannelLayout.STEREO, ChannelLayout.SURROUND_3_0, ChannelLayout.SURROUND_5_0, ChannelLayout.SURROUND_5_1)) {
            val channels = layout.channels.size
            val samples = signal(channels, 16, 5_000)
            val file = AudioKodec.encode(samples, AudioFormat(48_000, layout), 16, EncodeTarget.alac())
            assertEquals(layout, AudioKodec.probe(file).format.channelLayout)
            assertContentEquals(samples, decode(file), "$layout")
        }
        // Quadraphonic and 7.1 name speakers ALAC does not store.
        for (layout in listOf(ChannelLayout.QUAD, ChannelLayout.SURROUND_7_1, ChannelLayout.unknown(7))) {
            assertFailsWith<IllegalArgumentException>("$layout") {
                AudioKodec.encode(IntArray(layout.channels.size * 10), AudioFormat(48_000, layout), 16, EncodeTarget.alac())
            }
        }
    }

    @Test
    fun floatInputIsQuantisedToTheTargetDepth() {
        val format = AudioFormat(44_100, ChannelLayout.STEREO)
        val floats = FloatArray(2 * 9_000) { (0.7 * sin(2 * PI * (it / 2) * (440.0 + 110 * (it % 2)) / 44_100)).toFloat() }
        val file = AudioKodec.encode(floats, format, EncodeTarget.alac(24))
        val decoded = decode(file)
        for (i in floats.indices) {
            assertEquals(kotlin.math.round(floats[i] * 8_388_608.0).toInt(), decoded[i], "sample $i")
        }
    }

    @Test
    fun aDecodedFileSeeksToTheSampleAsked() {
        val samples = signal(2, 24, 20_000)
        val file = AudioKodec.encode(samples, AudioFormat(44_100, ChannelLayout.STEREO), 24, EncodeTarget.alac(24))
        AudioKodec.open(file).use { decoder ->
            val ints = decoder as IntPcmAccess
            for (target in longArrayOf(0, 1, 4_095, 4_096, 12_345, 19_999)) {
                assertEquals(target, decoder.seek(target))
                val frame = assertNotNull(ints.nextIntFrame())
                assertEquals(samples[target.toInt() * 2], frame.samples[0], "left at $target")
                assertEquals(samples[target.toInt() * 2 + 1], frame.samples[1], "right at $target")
            }
        }
    }

    @Test
    fun tagsArtworkAndChaptersAreStored() {
        val cover = TagPicture(PictureType.FRONT_COVER, "image/png", bytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3))
        val tags = TagBundle(
            mapOf(TagField.TITLE to listOf("Lossless ✓"), TagField.TRACK_NUMBER to listOf("3"), TagField.TRACK_TOTAL to listOf("9")),
            pictures = listOf(cover),
            chapters = listOf(TagChapter("a", 0, 100, "One"), TagChapter("b", 100, -1, "Two")),
        )
        val samples = signal(2, 16, 10_000)
        val file = AudioKodec.encode(samples, AudioFormat(44_100, ChannelLayout.STEREO), 16, EncodeTarget.alac(), tags)
        val read = AudioKodec.readTags(file)
        assertEquals("Lossless ✓", read[TagField.TITLE])
        assertEquals("3", read[TagField.TRACK_NUMBER])
        assertEquals("9", read[TagField.TRACK_TOTAL])
        assertContentEquals(cover.bytes, read.cover?.bytes)
        assertEquals(listOf("One", "Two"), read.chapters.map { it.title })
        assertContentEquals(samples, decode(file))

        val rewritten = AudioKodec.writeTags(file, read.edit { set(TagField.TITLE, "Edited") })
        assertEquals("Edited", AudioKodec.readTags(rewritten)[TagField.TITLE])
        assertContentEquals(samples, decode(rewritten))
    }

    @Test
    fun whatCannotBeWrittenIsRefusedBeforeOutput() {
        val format = AudioFormat(44_100, ChannelLayout.STEREO)
        val back = TagBundle(emptyMap(), listOf(TagPicture(PictureType.BACK_COVER, "image/png", bytes = byteArrayOf(1))))
        val sink = ByteArrayAudioSink()
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.encode(IntArray(200).asIntPcmSource(format, 16), EncodeTarget.alac(), sink, back)
        }
        assertEquals(0L, sink.position)

        val streaming = object : AudioSink {
            override val canPatch: Boolean = false
            override var position: Long = 0
            override fun write(bytes: ByteArray, offset: Int, count: Int) { position += count }
            override fun patchAt(position: Long, bytes: ByteArray, offset: Int, count: Int) = error("not patchable")
            override fun close() = Unit
        }
        assertFailsWith<AudioKodecException> {
            AudioKodec.encode(IntArray(200).asIntPcmSource(format, 16), EncodeTarget.alac(), streaming)
        }
        assertEquals(0L, streaming.position)
        assertFailsWith<IllegalArgumentException> { EncodeTarget.alac(8) }
    }

    @Test
    fun anEmptySourceWritesAnEmptyTrack() {
        val file = AudioKodec.encode(IntArray(0), AudioFormat(44_100, ChannelLayout.STEREO), 16, EncodeTarget.alac())
        assertEquals(0L, AudioKodec.probe(file).durationFrames)
        assertEquals(0, decode(file).size)
    }

    private fun decode(file: ByteArray): IntArray {
        val out = ArrayList<Int>()
        AudioKodec.open(file).use { decoder ->
            val access = decoder as IntPcmAccess
            while (true) {
                val frame = access.nextIntFrame() ?: break
                for (i in 0 until frame.sampleCount) out += frame.samples[i]
            }
        }
        return out.toIntArray()
    }

    /** A tone per channel over noise, a stretch of silence, and the extremes of the range. */
    private fun signal(channels: Int, bits: Int, frames: Int): IntArray {
        val peak = (1L shl (bits - 1)) - 1
        var state = channels * 1_000 + bits
        return IntArray(frames * channels) { i ->
            val c = i % channels
            val f = i / channels
            state = state * 1_103_515_245 + 12_345
            when {
                f < 4 -> if ((f + c) % 2 == 0) peak.toInt() else (-peak - 1).toInt()
                f in 1_000..1_499 -> 0
                else -> (0.5 * sin(2 * PI * (220.0 + 60.0 * c) * f / 44_100) * peak).toLong().toInt() +
                    ((state ushr 20) - 2_048) shr (24 - minOf(bits, 24) + 4)
            }
        }
    }
}

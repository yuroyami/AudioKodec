package io.github.yuroyami.audiokodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks the MPEG audio framing layer on every target.
 *
 * MPEG audio has no file header. Everything a caller learns about a file comes
 * from finding its frames and reading what an encoder left inside the first
 * one, so that is what these test: the frame header in every version and layer
 * the format defines, the two ways a file states its own length, the samples
 * an encoder pads each end with, and the tag blocks that sit either side of the
 * audio and must not be decoded as part of it.
 *
 * The files are built here rather than encoded, which is the only way to reach
 * combinations no encoder in use still produces, such as MPEG-2.5 or Layer I.
 */
class MpegTest {

    /** Serves [bytes], counting what is read and how often it is closed. */
    private class CountingSource(private val bytes: ByteArray) : AudioSource {
        var transferred = 0L
        var closes = 0
        private val inner = bytes.asAudioSource()
        override val size: Long get() = bytes.size.toLong()
        override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int =
            inner.readAt(position, into, offset, count).also { if (it > 0) transferred += it }
        override fun close() {
            closes++
        }
    }

    /** Five hundred silent 44.1 kHz frames of [layer], with no Xing or VBRI header. */
    internal fun silentFrames(layer: Int, count: Int = 500, compact: Boolean = false): ByteArray {
        val header = byteArrayOf(0xFF.toByte(), (0xE0 or (3 shl 3) or ((4 - layer) shl 1) or 1).toByte(),
            if (compact) 0x14 else 0x90.toByte(), if (compact) 0xC0.toByte() else 0)
        val length = io.github.yuroyami.audiokodec.internal.mpeg.MpegFrameHeader.parse(header, 0)!!.frameBytes
        val frame = ByteArray(length).also { header.copyInto(it) }
        return ByteArray(length * count).also { all -> repeat(count) { frame.copyInto(all, it * length) } }
    }

    @Test
    fun negativeSeekTargetsAreRejectedWithoutChangingPlaybackInEveryLayer() {
        for (layer in 1..3) {
            val source = CountingSource(silentFrames(layer))
            AudioKodec.open(source).use { decoder ->
                assertNotNull(decoder.nextFrame())
                for (target in longArrayOf(-1, Long.MIN_VALUE)) {
                    val before = decoder.positionFrames
                    source.transferred = 0
                    assertFailsWith<IllegalArgumentException>("Layer $layer, target $target") { decoder.seek(target) }
                    assertEquals(before, decoder.positionFrames, "a rejected seek must preserve playback")
                    assertEquals(0L, source.transferred, "a rejected seek must not read the source")
                    assertEquals(before, assertNotNull(decoder.nextFrame()).positionFrames)
                }
            }
        }
    }

    @Test
    fun slowSeekingOffIsHonouredByEveryLayer() {
        for (layer in 1..3) {
            val source = CountingSource(silentFrames(layer))
            AudioKodec.open(source, DecodeOptions(allowSlowSeek = false)).use { decoder ->
                val end = decoder.durationFrames!! - 100
                source.transferred = 0
                assertFailsWith<UnsupportedFeatureException>("Layer $layer") { decoder.seek(end) }
                assertEquals(0L, source.transferred, "Layer $layer read the stream before refusing")

                // A landing inside what playing has already indexed stays cheap
                // and allowed.
                repeat(200) { decoder.nextFrame() }
                assertEquals(1_000L, decoder.seek(1_000), "Layer $layer")
                assertNotNull(decoder.nextFrame(), "Layer $layer")
                assertEquals(0L, decoder.seek(0), "Layer $layer")
            }
            // With slow seeking allowed, the same seek walks the stream.
            AudioKodec.open(silentFrames(layer)).use { decoder ->
                val end = decoder.durationFrames!! - 100
                assertEquals(end, decoder.seek(end), "Layer $layer")
            }
        }
    }

    @Test
    fun aClosedMpegDecoderNeitherReadsNorClosesAgain() {
        for (layer in 1..3) {
            val source = CountingSource(silentFrames(layer))
            val decoder = AudioKodec.open(source)
            assertNotNull(decoder.nextFrame(), "Layer $layer")
            decoder.close()
            val read = source.transferred
            assertFailsWith<IllegalStateException>("Layer $layer") { decoder.nextFrame() }
            assertFailsWith<IllegalStateException>("Layer $layer") { decoder.seek(0) }
            decoder.close()
            assertEquals(read, source.transferred, "Layer $layer read after closing")
            assertEquals(1, source.closes, "Layer $layer")
        }
    }

    @Test
    fun aXingHeaderWithoutAFrameCountFallsBackToCountingFrames() {
        // Four 417-byte MPEG-1 Layer III frames at 128 kbit/s and 44.1 kHz.
        // The first carries a Xing header whose flags state nothing at all.
        val frames = List(4) {
            ByteArray(417).also { frame ->
                frame[0] = 0xFF.toByte()
                frame[1] = 0xFB.toByte()
                frame[2] = 0x90.toByte()
            }
        }
        "Xing".encodeToByteArray().copyInto(frames[0], 36)
        val bytes = frames.reduce { all, frame -> all + frame }
        val info = AudioKodec.probe(bytes)
        // The Xing frame is bookkeeping rather than audio.
        assertEquals(3L * 1152, info.durationFrames)
    }

    /** Builds an MPEG audio frame with a silent payload. */
    private fun frame(
        version: Int = 1,
        layer: Int = 3,
        bitrateIndex: Int = 9,
        sampleRateIndex: Int = 0,
        padded: Boolean = false,
        channelMode: Int = 0,
    ): ByteArray {
        val versionBits = when (version) {
            1 -> 3
            2 -> 2
            else -> 0
        }
        val layerBits = 4 - layer
        val b1 = 0xE0 or (versionBits shl 3) or (layerBits shl 1) or 1
        val b2 = (bitrateIndex shl 4) or (sampleRateIndex shl 2) or (if (padded) 2 else 0)
        val b3 = (channelMode shl 6)
        val header = byteArrayOf(0xFF.toByte(), b1.toByte(), b2.toByte(), b3.toByte())

        val rate = intArrayOf(44_100, 48_000, 32_000)[sampleRateIndex] / when (version) {
            1 -> 1
            2 -> 2
            else -> 4
        }
        val bitrate = bitrateFor(version, layer, bitrateIndex)
        val samples = when {
            layer == 1 -> 384
            layer == 2 -> 1152
            version == 1 -> 1152
            else -> 576
        }
        val length = if (layer == 1) {
            (12 * bitrate * 1000 / rate + if (padded) 1 else 0) * 4
        } else {
            samples / 8 * bitrate * 1000 / rate + if (padded) 1 else 0
        }
        val out = ByteArray(length)
        header.copyInto(out, 0)
        return out
    }

    private fun bitrateFor(version: Int, layer: Int, index: Int): Int {
        val v1l1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0)
        val v1l2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0)
        val v1l3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        val v2l1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0)
        val v2l23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
        return if (version == 1) {
            when (layer) {
                1 -> v1l1[index]
                2 -> v1l2[index]
                else -> v1l3[index]
            }
        } else {
            if (layer == 1) v2l1[index] else v2l23[index]
        }
    }

    private fun file(count: Int, vararg lead: Byte, builder: () -> ByteArray = { frame() }): ByteArray {
        val frames = ArrayList<ByteArray>()
        repeat(count) { frames.add(builder()) }
        var total = lead.size
        for (f in frames) total += f.size
        val out = ByteArray(total)
        lead.copyInto(out, 0)
        var at = lead.size
        for (f in frames) {
            f.copyInto(out, at)
            at += f.size
        }
        return out
    }

    @Test
    fun aConstantBitrateFileIsMeasuredFromItsFrames() {
        val bytes = file(20)
        val info = AudioKodec.probe(bytes)
        assertEquals(AudioContainer.MPEG_AUDIO, info.container, "container")
        assertEquals(AudioCodec.MP3, info.codec, "codec")
        assertEquals(44_100, info.format.sampleRate, "sample rate")
        assertEquals(2, info.format.channels, "channels")
        assertEquals(20L * 1152, info.durationFrames, "duration")
        assertEquals(0L, info.dataOffset, "the audio starts at the beginning")
        assertTrue(!info.isVariableBitrate, "no header means constant bitrate")
    }

    @Test
    fun aSingleCompleteFrameIsValidButAPartialOneIsNot() {
        val complete = frame()
        assertEquals(1152L, AudioKodec.probe(complete).durationFrames)

        val partial = complete.copyOf(complete.size - 1)
        assertFailsWith<AudioKodecException> { AudioKodec.probe(partial) }
        assertFailsWith<AudioKodecException> {
            AudioKodec.probe(partial, DecodeOptions(tolerant = true))
        }
    }

    @Test
    fun anUnknownLengthSourceIsScannedToItsActualEnd() {
        val bytes = file(20)
        val source = object : AudioSource {
            private val delegate = bytes.asAudioSource()
            override val size: Long = -1
            override fun readAt(
                position: Long,
                into: ByteArray,
                offset: Int,
                count: Int,
            ): Int = delegate.readAt(position, into, offset, count)

            override fun close() = Unit
        }
        val info = AudioKodec.probe(source)
        assertEquals(20L * 1152, info.durationFrames)
        assertEquals(bytes.size.toLong(), info.dataSize)
    }

    @Test
    fun variableBitrateWithoutAnIndexIsCountedFrameByFrame() {
        val frames = ArrayList<ByteArray>()
        repeat(40) { index ->
            frames.add(frame(bitrateIndex = if (index % 2 == 0) 7 else 11))
        }
        val bytes = ByteArray(frames.sumOf { it.size })
        var at = 0
        for (frame in frames) {
            frame.copyInto(bytes, at)
            at += frame.size
        }

        val info = AudioKodec.probe(bytes)
        assertEquals(40L * 1152, info.durationFrames, "every frame contributes to duration")
        assertTrue(info.isVariableBitrate, "changing frame bitrates make the stream VBR")
    }

    @Test
    fun everyVersionAndLayerIsRead() {
        val cases = listOf(
            Triple(1, 1, AudioCodec.MP1),
            Triple(1, 2, AudioCodec.MP2),
            Triple(1, 3, AudioCodec.MP3),
            Triple(2, 1, AudioCodec.MP1),
            Triple(2, 2, AudioCodec.MP2),
            Triple(2, 3, AudioCodec.MP3),
            Triple(25, 3, AudioCodec.MP3),
        )
        for ((version, layer, codec) in cases) {
            val what = "MPEG-$version Layer $layer"
            val bytes = file(12) { frame(version = version, layer = layer, bitrateIndex = 8) }
            val info = AudioKodec.probe(bytes)
            assertEquals(codec, info.codec, "$what: codec")
            val expected = when (version) {
                1 -> 44_100
                2 -> 22_050
                else -> 11_025
            }
            assertEquals(expected, info.format.sampleRate, "$what: sample rate")
            val samples = when {
                layer == 1 -> 384L
                layer == 2 -> 1152L
                version == 1 -> 1152L
                else -> 576L
            }
            assertEquals(12 * samples, info.durationFrames, "$what: duration")
        }
    }

    @Test
    fun aSingleChannelFrameIsMono() {
        val bytes = file(8) { frame(channelMode = 3) }
        assertEquals(1, AudioKodec.probe(bytes).format.channels, "channels")
    }

    /**
     * The frame that holds a file's own bookkeeping is not audio.
     *
     * Decoding it produces a burst of silence at the front of every track, and
     * counting it makes every file one frame too long.
     */
    @Test
    fun aXingHeaderIsReadAndItsFrameIsNotCounted() {
        val header = frame()
        // The Xing structure sits after the header and the side information.
        val at = 4 + 32
        "Xing".forEachIndexed { i, c -> header[at + i] = c.code.toByte() }
        header[at + 7] = 0x03 // frames and bytes present
        writeInt(header, at + 8, 500)
        writeInt(header, at + 12, 123_456)

        val bytes = file(4).let { rest ->
            val out = ByteArray(header.size + rest.size)
            header.copyInto(out, 0)
            rest.copyInto(out, header.size)
            out
        }

        val info = AudioKodec.probe(bytes)
        assertEquals(500L * 1152, info.durationFrames, "the stored frame count is used")
        assertEquals(header.size.toLong(), info.dataOffset, "the audio starts after the header frame")
        assertTrue(info.isVariableBitrate, "a Xing header means variable bitrate")
    }

    @Test
    fun anInfoHeaderMeansConstantBitrate() {
        val header = frame()
        val at = 4 + 32
        "Info".forEachIndexed { i, c -> header[at + i] = c.code.toByte() }
        header[at + 7] = 0x01
        writeInt(header, at + 8, 100)
        val bytes = header + file(4)
        assertTrue(!AudioKodec.probe(bytes).isVariableBitrate, "Info means constant bitrate")
    }

    /**
     * The samples an encoder adds at each end come off again.
     *
     * Without this every track begins with a fraction of a second of silence
     * and ends with another, which is what a gap between two tracks of one
     * recording is made of.
     */
    @Test
    fun theEncodersOwnPaddingIsTrimmed() {
        val header = frame()
        val at = 4 + 32
        "Xing".forEachIndexed { i, c -> header[at + i] = c.code.toByte() }
        header[at + 7] = 0x01
        writeInt(header, at + 8, 100)
        // The encoder's block starts where the Xing data ends, and states the
        // delay and the padding in three packed bytes twenty-one bytes in.
        val lame = at + 12
        "LAME3.100".forEachIndexed { i, c -> header[lame + i] = c.code.toByte() }
        val delay = 576
        val padding = 1_800
        header[lame + 21] = (delay ushr 4).toByte()
        header[lame + 22] = (((delay and 0xF) shl 4) or (padding ushr 8)).toByte()
        header[lame + 23] = (padding and 0xFF).toByte()

        val bytes = header + file(4)
        val info = AudioKodec.probe(bytes)
        // The decoder's own delay of 529 samples does not appear here. It
        // shifts the audio later rather than shortening it, so what it pushes
        // off the front comes back out of the encoder's padding at the end and
        // the two cancel. Only the encoder's own two figures count.
        val expected = 100L * 1152 - delay - padding
        assertEquals(expected, info.durationFrames, "the trimmed length")
    }

    @Test
    fun aTagBlockInFrontIsSkipped() {
        // An ID3v2 header states its size in seven bits per byte.
        val tagSize = 1000
        val tag = ByteArray(10 + tagSize)
        tag[0] = 'I'.code.toByte()
        tag[1] = 'D'.code.toByte()
        tag[2] = '3'.code.toByte()
        tag[3] = 4
        tag[6] = ((tagSize ushr 21) and 0x7F).toByte()
        tag[7] = ((tagSize ushr 14) and 0x7F).toByte()
        tag[8] = ((tagSize ushr 7) and 0x7F).toByte()
        tag[9] = (tagSize and 0x7F).toByte()
        // Bytes inside the tag that look like a frame header, which is what a
        // scanner that starts at zero would find first.
        tag[500] = 0xFF.toByte()
        tag[501] = 0xFB.toByte()
        tag[502] = 0x90.toByte()

        val bytes = tag + file(10)
        val info = AudioKodec.probe(bytes)
        assertEquals((10 + tagSize).toLong(), info.dataOffset, "the audio starts after the tag")
        assertEquals(10L * 1152, info.durationFrames, "duration")
    }

    @Test
    fun aTagBlockAtTheEndIsNotAudio() {
        val audio = file(10)
        val id3v1 = ByteArray(128)
        "TAG".forEachIndexed { i, c -> id3v1[i] = c.code.toByte() }
        val info = AudioKodec.probe(audio + id3v1)
        assertEquals(10L * 1152, info.durationFrames, "the trailing tag is not counted")
    }

    @Test
    fun anApeHeaderAndFooterAtTheEndAreBothExcludedFromAudio() {
        val audio = file(10)
        val ape = ApeBytes(listOf("Title" to "Tagged".encodeToByteArray()))
        val info = AudioKodec.probe(audio + ape)
        assertEquals(audio.size.toLong(), info.dataSize, "the APE header leaked into audio")
        assertEquals(10L * 1152, info.durationFrames)
    }

    @Test
    fun anAppendedId3v24TagWithAFooterIsExcludedFromAudio() {
        val audio = file(10)
        val bodySize = 80
        val tag = ByteArray(20 + bodySize)
        "ID3".forEachIndexed { index, char -> tag[index] = char.code.toByte() }
        tag[3] = 4
        tag[5] = 0x10
        tag[6] = ((bodySize ushr 21) and 0x7F).toByte()
        tag[7] = ((bodySize ushr 14) and 0x7F).toByte()
        tag[8] = ((bodySize ushr 7) and 0x7F).toByte()
        tag[9] = (bodySize and 0x7F).toByte()
        // A sync-shaped word in the tag must never become an audio frame.
        tag[30] = 0xFF.toByte()
        tag[31] = 0xFB.toByte()
        tag[32] = 0x90.toByte()
        val footerAt = 10 + bodySize
        "3DI".forEachIndexed { index, char -> tag[footerAt + index] = char.code.toByte() }
        for (index in 3 until 10) tag[footerAt + index] = tag[index]

        val info = AudioKodec.probe(audio + tag)
        assertEquals(audio.size.toLong(), info.dataSize)
        assertEquals(10L * 1152, info.durationFrames)
    }

    /**
     * A run of bytes that happens to contain a sync word is not a frame.
     *
     * Eleven set bits turn up in ordinary data often enough that a scanner
     * which believes the first one it sees starts decoding mid-frame.
     */
    @Test
    fun aFalseSyncIsNotMistakenForAFrame() {
        // A sync word at the very front, with nothing where its next frame
        // would be. A scanner that believes the first one it sees starts here
        // and decodes the rest of the file half a frame out of step.
        val junk = ByteArray(600)
        junk[0] = 0xFF.toByte()
        junk[1] = 0xFB.toByte()
        junk[2] = 0x90.toByte()
        junk[3] = 0x00
        val bytes = junk + file(8)
        val info = AudioKodec.probe(bytes)
        assertEquals(600L, info.dataOffset, "the real audio was found")
        assertEquals(8L * 1152, info.durationFrames, "duration")
    }

    @Test
    fun aFreeFormatFileIsNamedRatherThanGuessedAt() {
        // A free format frame states no bitrate, so its length is whatever the
        // distance to the next sync word turns out to be. This one is 800
        // bytes, which nothing in the file says.
        val bytes = ByteArray(800 * 6)
        for (f in 0 until 6) {
            val at = f * 800
            bytes[at] = 0xFF.toByte()
            bytes[at + 1] = 0xFB.toByte()
            bytes[at + 2] = 0x00
            bytes[at + 3] = 0x00
        }
        val failure = assertFailsWith<UnsupportedFeatureException> { AudioKodec.probe(bytes) }
        assertTrue(
            failure.message!!.contains("free format"),
            "the error should name the feature: ${failure.message}",
        )
    }

    @Test
    fun aFileWithNoFramesSaysSo() {
        assertFailsWith<AudioKodecException> {
            AudioKodec.probe(ByteArray(4096) { 0x11 })
        }
    }

    @Test
    fun aPaddedFrameIsOneByteLonger() {
        val plain = frame(padded = false)
        val padded = frame(padded = true)
        assertEquals(plain.size + 1, padded.size, "padding adds a byte")
        // A file alternating the two still measures correctly, which it only
        // does if the length is read from each frame rather than assumed.
        var toggle = false
        val bytes = file(12) {
            toggle = !toggle
            frame(padded = toggle)
        }
        assertNotNull(AudioKodec.probe(bytes).durationFrames, "duration")
    }

    @Test
    fun aTruncatedOrDamagedFileNeverEscapesItsOwnExceptions() {
        val whole = file(20)
        for (keep in intArrayOf(1, 4, 100, 500, 2_000)) {
            try {
                AudioKodec.probe(whole.copyOf(keep))
            } catch (e: AudioKodecException) {
                // The only kind of failure allowed.
            }
        }
        var state = 7
        repeat(300) {
            val copy = whole.copyOf()
            repeat(6) {
                state = state * 1_664_525 + 1_013_904_223
                copy[((state ushr 8) and 0x7FFFFFFF) % copy.size] = (state ushr 16).toByte()
            }
            try {
                AudioKodec.probe(copy)
            } catch (e: AudioKodecException) {
                // Likewise.
            }
        }
    }

    private fun writeInt(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value ushr 24).toByte()
        bytes[at + 1] = (value ushr 16).toByte()
        bytes[at + 2] = (value ushr 8).toByte()
        bytes[at + 3] = value.toByte()
    }
}

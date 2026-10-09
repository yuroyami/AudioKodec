package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.container.AdtsStream
import io.github.yuroyami.audiokodec.internal.io.SourceReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checks the ADTS and ADIF framing on every target, against streams built
 * here bit by bit from ISO/IEC 14496-3 rather than written by AudioKodec.
 * The payloads are filler: framing is read without decoding, so nothing
 * looks inside them. The oracle suite checks ffmpeg's files against ffprobe
 * on the JVM.
 */
class AdtsTest {

    @Test
    fun theLengthIsEveryBlockOfEveryFrame() {
        for ((rateIndex, rate) in listOf(3 to 48_000, 4 to 44_100, 11 to 8_000, 12 to 7_350, 0 to 96_000)) {
            val file = stream(frames = 40, rateIndex = rateIndex, configuration = 2)
            val info = AudioKodec.probe(file)
            assertEquals(AudioContainer.ADTS, info.container)
            assertEquals(AudioCodec.AAC, info.codec)
            assertEquals(rate, info.format.sampleRate)
            assertEquals(ChannelLayout.STEREO, info.format.channelLayout)
            assertEquals(40L * 1024, info.durationFrames)
            assertNull(info.bitDepth)
            assertEquals(0L, info.dataOffset)
            assertEquals(file.size.toLong(), info.dataSize)
        }
        // Several raw data blocks in a frame, with and without the checksum.
        for (crc in listOf(false, true)) {
            val file = stream(frames = 10, blocks = 3, crc = crc)
            assertEquals(10L * 3 * 1024, AudioKodec.probe(file).durationFrames, "crc $crc")
        }
    }

    @Test
    fun everyFixedChannelConfigurationNamesItsSpeakers() {
        val expected = mapOf(
            1 to ChannelLayout.MONO,
            2 to ChannelLayout.STEREO,
            3 to ChannelLayout.SURROUND_3_0,
            5 to ChannelLayout.SURROUND_5_0,
            6 to ChannelLayout.SURROUND_5_1,
        )
        for (configuration in 1..7) {
            val info = AudioKodec.probe(stream(frames = 5, configuration = configuration))
            assertEquals(if (configuration == 7) 8 else configuration, info.format.channels, "configuration $configuration")
            expected[configuration]?.let { assertEquals(it, info.format.channelLayout, "configuration $configuration") }
            assertTrue(AudioChannel.UNKNOWN !in info.format.channelLayout.channels)
        }
    }

    @Test
    fun aProgramConfigurationStatesTheChannelsWhenTheHeaderDoesNot() {
        // Front: one pair and one single; back: one pair; one LFE.
        val pce = programConfig(rateIndex = 4, front = listOf(false, true), back = listOf(true), lfe = 1)
        val file = stream(frames = 6, configuration = 0, firstPayload = bitsOf(listOf(5 to 3) + pce))
        val info = AudioKodec.probe(file)
        assertEquals(6, info.format.channels)
        assertEquals(44_100, info.format.sampleRate)

        val noPce = stream(frames = 6, configuration = 0, firstPayload = byteArrayOf(0x20, 0, 0, 0))
        assertFailsWith<UnsupportedFeatureException> { AudioKodec.probe(noPce) }
    }

    @Test
    fun tagsJunkAndDamageAreHandledAsForMpegAudio() {
        val body = stream(frames = 30)
        val id3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 20) + ByteArray(20)
        val id3v1 = "TAG".encodeToByteArray() + ByteArray(125)
        val tagged = id3 + body + id3v1
        val info = AudioKodec.probe(tagged)
        assertEquals(30L * 1024, info.durationFrames)
        assertEquals(id3.size.toLong(), info.dataOffset)
        assertEquals(body.size.toLong(), info.dataSize)

        // Junk between frames is crossed and the frames after it counted.
        val boundary = (0 until 10).sumOf { frameBytes(it) }
        val withJunk = body.copyOfRange(0, boundary) + ByteArray(37) { 0x55 } + body.copyOfRange(boundary, body.size)
        assertEquals(30L * 1024, AudioKodec.probe(withJunk).durationFrames)

        // A final frame cut short is truncation, or left out when tolerant.
        val cut = body.copyOf(body.size - 5)
        assertFailsWith<TruncatedStreamException> { AudioKodec.probe(cut) }
        assertEquals(29L * 1024, AudioKodec.probe(cut, DecodeOptions(tolerant = true)).durationFrames)
    }

    @Test
    fun framesAreVisitedWhereTheyAre() {
        val file = stream(frames = 12, crc = true)
        val reader = SourceReader(file.asAudioSource(), DecodeOptions.DEFAULT)
        val parsed = AdtsStream.parse(reader, DecodeOptions.DEFAULT)
        val offsets = ArrayList<Long>()
        parsed.frames(reader) { frame ->
            assertEquals(9, frame.header.headerBytes)
            assertEquals(frame.offset + 9, frame.payloadOffset)
            offsets += frame.offset
            true
        }
        var expected = 0L
        val wanted = List(12) { i -> expected.also { expected += frameBytes(i) } }
        assertEquals(wanted, offsets)
    }

    @Test
    fun framesWhosePayloadIsNotAacDecodeAsDamage() {
        // The filler payloads frame correctly but do not decode.
        AudioKodec.open(stream(frames = 3)).use { decoder ->
            assertFailsWith<CorruptStreamException> { decoder.nextFrame() }
        }
        // A header whose stated length leads to something that is not a frame.
        val lone = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), 0x10, 0x1F, 0xFC.toByte()) + ByteArray(600) { 0x13 }
        assertEquals(AudioContainer.ADTS, AudioKodec.sniff(lone))
        assertFailsWith<CorruptStreamException> { AudioKodec.probe(lone) }
    }

    @Test
    fun theAdifHeaderGivesRateChannelsAndBitrateButNoLength() {
        val pce = programConfig(rateIndex = 3, front = listOf(true), back = emptyList(), lfe = 0)
        for (constant in listOf(true, false)) {
            val header = bitsOf(
                listOf(0x41444946 to 32, 1 to 1, 0 to 32, 0 to 32, 0 to 8, 0 to 1, 0 to 1, (if (constant) 0 else 1) to 1, 128_000 to 23, 0 to 4) +
                    (if (constant) listOf(0 to 20) else emptyList()) + pce,
            )
            val file = header + ByteArray(500) { (it * 7).toByte() }
            assertEquals(AudioContainer.ADIF, AudioKodec.sniff(file))
            val info = AudioKodec.probe(file)
            assertEquals(AudioContainer.ADIF, info.container)
            assertEquals(48_000, info.format.sampleRate)
            assertEquals(2, info.format.channels)
            assertEquals(128_000, info.bitrate)
            assertEquals(!constant, info.isVariableBitrate)
            assertNull(info.durationFrames)
            assertEquals(header.size.toLong(), info.dataOffset)
            assertFailsWith<UnsupportedFormatException> { AudioKodec.open(file) }
        }
    }

    @Test
    fun truncatedAndMutatedStreamsFailOnlyWithTheLibrarysOwnExceptions() {
        val pce = programConfig(rateIndex = 4, front = listOf(true), back = emptyList(), lfe = 0)
        val adif = bitsOf(listOf(0x41444946 to 32, 0 to 1, 0 to 2, 0 to 1, 96_000 to 23, 0 to 4, 0 to 20) + pce) + ByteArray(40)
        for (file in listOf(stream(frames = 6, crc = true), stream(frames = 4, configuration = 0, firstPayload = bitsOf(listOf(5 to 3) + pce)), adif)) {
            for (keep in 0..file.size) {
                val cut = file.copyOf(keep)
                for (tolerant in listOf(false, true)) {
                    expectOnlyOwnFailures("cut to $keep") { AudioKodec.probe(cut, DecodeOptions(tolerant = tolerant)) }
                }
                expectOnlyOwnFailures("decode cut to $keep") { AudioKodec.decode(cut) }
            }
            for (at in file.indices) {
                for (bit in 0 until 8) {
                    val mutated = file.copyOf().also { it[at] = (it[at].toInt() xor (1 shl bit)).toByte() }
                    expectOnlyOwnFailures("byte $at bit $bit") { AudioKodec.probe(mutated) }
                    expectOnlyOwnFailures("tolerant byte $at bit $bit") { AudioKodec.probe(mutated, DecodeOptions(tolerant = true)) }
                }
            }
        }
    }

    // Building streams.

    /** Bytes of frame [index], which vary so that the stream is not periodic. */
    private fun frameBytes(index: Int): Int = 60 + (index * 37) % 50

    private fun stream(
        frames: Int,
        rateIndex: Int = 4,
        configuration: Int = 2,
        blocks: Int = 1,
        crc: Boolean = false,
        firstPayload: ByteArray? = null,
    ): ByteArray {
        var out = ByteArray(0)
        for (i in 0 until frames) {
            val headerBytes = if (crc) 7 + 2 * (blocks - 1) + 2 else 7
            val payload = if (i == 0 && firstPayload != null) firstPayload else ByteArray(frameBytes(i) - headerBytes) { (i * 31 + it * 7).toByte() }
            val length = headerBytes + payload.size
            val header = bitsOf(
                listOf(
                    0xFFF to 12, 0 to 1, 0 to 2, (if (crc) 0 else 1) to 1,
                    1 to 2, rateIndex to 4, 0 to 1, configuration to 3, 0 to 1, 0 to 1,
                    0 to 1, 0 to 1, length to 13, 0x7FF to 11, (blocks - 1) to 2,
                ) + if (crc) List(blocks) { 0x1234 to 16 } else emptyList(),
            )
            out += header + payload
        }
        return out
    }

    /** A `program_config_element` after its element tag, with no comment. */
    private fun programConfig(rateIndex: Int, front: List<Boolean>, back: List<Boolean>, lfe: Int): List<Pair<Int, Int>> {
        val fields = mutableListOf(
            0 to 4, 1 to 2, rateIndex to 4, front.size to 4, 0 to 4, back.size to 4, lfe to 2,
            0 to 3, 0 to 4, 0 to 1, 0 to 1, 0 to 1,
        )
        for (cpe in front + back) {
            fields += (if (cpe) 1 else 0) to 1
            fields += 0 to 4
        }
        repeat(lfe) { fields += 0 to 4 }
        fields += ALIGN to 0
        fields += 0 to 8
        return fields
    }

    /** Packs fields most significant bit first; [ALIGN] pads to a byte boundary. */
    private fun bitsOf(fields: List<Pair<Int, Int>>): ByteArray {
        val bits = ArrayList<Int>()
        for ((value, count) in fields) {
            if (value == ALIGN && count == 0) {
                while (bits.size % 8 != 0) bits += 0
                continue
            }
            for (k in count - 1 downTo 0) bits += (value ushr k) and 1
        }
        while (bits.size % 8 != 0) bits += 0
        return ByteArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, k -> (acc shl 1) or bits[i * 8 + k] }.toByte() }
    }

    private inline fun expectOnlyOwnFailures(what: String, block: () -> Unit) {
        try {
            block()
        } catch (_: AudioKodecException) {
            // Every failure has to be one of the library's own.
        } catch (e: Throwable) {
            throw AssertionError("$what threw ${e::class.simpleName}: ${e.message}", e)
        }
    }

    private companion object {
        const val ALIGN = Int.MIN_VALUE
    }
}

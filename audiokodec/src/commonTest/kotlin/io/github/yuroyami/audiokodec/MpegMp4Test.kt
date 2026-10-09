package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.mpeg.MpegFrameHeader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpegMp4Test {
    @Test
    fun everyMpegVersionAndSampleEntryKeepsTheBareSamples() {
        for (rate in listOf(44_100, 22_050, 8_000)) {
            for (shape in Mp3SwitchedBlockFixtures.Shape.entries) {
                val bare = Mp3SwitchedBlockFixtures.file(rate, shape)
                val expected = AudioKodec.decode(bare)
                for ((entry, type) in listOf(".mp3" to 0x6B, "mp4a" to 0x69, "mp4a" to 0x6B)) {
                    val file = MpegMp4Fixtures.file(MpegMp4Fixtures.packets(bare), entry, type)
                    val info = AudioKodec.probe(file)
                    assertEquals(AudioCodec.MP3, info.codec)
                    assertEquals(expected.format, info.format)
                    assertEquals(expected.frames.toLong(), info.durationFrames)
                    assertNull(info.bitDepth)
                    TestSignals.assertExact(expected.samples, AudioKodec.decode(file).samples, "$rate $shape $entry $type")
                    TestSignals.assertExact(expected.samples,
                        AudioKodec.decode(OggDamageFixtures.source(file, knownSize = false)).samples)
                }
            }
        }
    }

    @Test
    fun layersOneAndTwoAlsoRetainTheirBareTimelineAndSamples() {
        for (layer in 1..2) {
            val bare = if (layer == 1) MpegMp4Fixtures.layerOne(12, 44_100)
            else MpegTest().silentFrames(layer, count = 12, compact = true)
            val expected = AudioKodec.decode(bare)
            val bytes = MpegMp4Fixtures.file(MpegMp4Fixtures.packets(bare), "mp4a")
            assertEquals(if (layer == 1) AudioCodec.MP1 else AudioCodec.MP2, AudioKodec.probe(bytes).codec)
            assertEquals(expected.frames.toLong(), AudioKodec.probe(bytes).durationFrames)
            assertContentEquals(expected.samples, AudioKodec.decode(bytes).samples)
            checkSeeks(bytes, expected.samples, expected.format.channels)
        }
    }

    @Test
    fun reservoirPrerollKeepsOpeningEditsAndRepeatedSeeksExact() {
        val bare = MpegMp4Fixtures.borrowingFrames(80)
        val original = AudioKodec.decode(bare)
        val start = 29L * 1152 + 73
        val duration = 31L * 1152 - 37
        for (edit in listOf(null, start to duration)) {
            val bytes = MpegMp4Fixtures.file(MpegMp4Fixtures.packets(bare), edit = edit)
            val from = (edit?.first ?: 0).toInt()
            val count = (edit?.second ?: original.frames.toLong()).toInt()
            val expected = original.samples.copyOfRange(from, from + count)
            assertEquals(count.toLong(), AudioKodec.probe(bytes).durationFrames)
            assertContentEquals(expected, AudioKodec.decode(bytes).samples)
            checkSeeks(bytes, expected, 1)
        }
    }

    @Test
    fun unavailableReservoirAudioIsDiscardedOnlyBeforeThePlayableStart() {
        val packets = MpegMp4Fixtures.packets(MpegMp4Fixtures.borrowingFrames(4)).map { it.copyOf() }
        packets[0][4] = 0
        packets[0][5] = (packets[0][5].toInt() or 0x80).toByte() // main_data_begin = 1
        for (start in listOf(0L, 1L, 1151L)) {
            val bytes = MpegMp4Fixtures.file(packets, edit = start to (4608L - start))
            for (tolerant in listOf(false, true)) {
                assertFailsWith<CorruptStreamException> { AudioKodec.decode(bytes, DecodeOptions(tolerant = tolerant)) }
            }
        }
        val bytes = MpegMp4Fixtures.file(packets, edit = 1152L to 3456L)
        assertEquals(3456, AudioKodec.decode(bytes).frames)
        checkSeeks(bytes, AudioKodec.decode(bytes).samples, 1)

        val damaged = MpegMp4Fixtures.packets(MpegMp4Fixtures.borrowingFrames(4)).map { it.copyOf() }
        damaged[1][4] = 0xFF.toByte()
        damaged[1][5] = (damaged[1][5].toInt() or 0x80).toByte() // 511 exceeds one frame's 396 bytes.
        val later = MpegMp4Fixtures.file(damaged)
        assertFailsWith<CorruptStreamException> { AudioKodec.decode(later) }
        val prefix = AudioKodec.decode(later, DecodeOptions(tolerant = true))
        assertEquals(1152, prefix.frames)
        assertContentEquals(AudioKodec.decode(MpegMp4Fixtures.borrowingFrames(4)).samples.copyOf(1152), prefix.samples)
    }

    @Test
    fun malformedPacketsReportTheirOffsetsAndRetainOnlyCompleteTolerantAudio() {
        val first = MpegMp4Fixtures.packets(Mp3SwitchedBlockFixtures.file(44_100, Mp3SwitchedBlockFixtures.Shape.NORMAL))[0]
        val invalid = listOf(
            first.copyOf(3), first.copyOf(first.size - 1), first + byteArrayOf(0), first + first,
            first.copyOf().also { it[0] = 0 },
            MpegMp4Fixtures.packets(Mp3SwitchedBlockFixtures.file(22_050, Mp3SwitchedBlockFixtures.Shape.NORMAL))[0],
            MpegMp4Fixtures.packets(MpegTest().silentFrames(2, count = 1))[0],
            first.copyOf().also { it[3] = 0 },
        )
        for ((i, second) in invalid.withIndex()) {
            val bytes = MpegMp4Fixtures.file(listOf(first, second))
            val info = AudioKodec.probe(bytes)
            val failure = assertFailsWith<CorruptStreamException>("packet case $i") { AudioKodec.decode(bytes) }
            assertEquals(info.dataOffset + first.size, failure.offset)
            val prefix = AudioKodec.decode(bytes, DecodeOptions(tolerant = true))
            assertEquals(1152, prefix.frames)
            assertContentEquals(AudioKodec.decode(MpegMp4Fixtures.file(listOf(first))).samples, prefix.samples)
        }
    }

    @Test
    fun firstHeaderShapeAndAllocationChecksStayTyped() {
        val packets = MpegMp4Fixtures.packets(Mp3SwitchedBlockFixtures.file(44_100, Mp3SwitchedBlockFixtures.Shape.NORMAL))
        for (bytes in listOf(
            MpegMp4Fixtures.file(packets, channels = 2), MpegMp4Fixtures.file(packets, rate = 48_000),
        )) {
            assertFailsWith<CorruptStreamException> { AudioKodec.probe(bytes) }
            assertFailsWith<CorruptStreamException> { AudioKodec.open(bytes).close() }
        }
        val valid = MpegMp4Fixtures.file(packets)
        assertFailsWith<CorruptStreamException> { AudioKodec.probe(valid, DecodeOptions(maxSampleRate = 44_099)) }
        assertFailsWith<AllocationGuardException> { AudioKodec.decode(valid, DecodeOptions(maxAllocationBytes = 8191)) }
        val offset = AudioKodec.probe(valid).dataOffset.toInt()
        val invalid = valid.copyOf().also { it[offset] = 0 }
        assertFailsWith<CorruptStreamException> { AudioKodec.probe(invalid) }
        val free = valid.copyOf().also { it[offset + 2] = (it[offset + 2].toInt() and 0x0F).toByte() }
        assertFailsWith<CorruptStreamException> { AudioKodec.decode(free) }
    }

    @Test
    fun aFarSeekUsesThePacketIndexAndBoundedPreroll() {
        val bare = MpegMp4Fixtures.borrowingFrames(256)
        val bytes = MpegMp4Fixtures.file(MpegMp4Fixtures.packets(bare))
        val expected = AudioKodec.decode(bare)
        val source = MpegXingFixtures.CountingSource(bytes, true)
        AudioKodec.open(source, DecodeOptions(allowSlowSeek = false)).use { decoder ->
            source.transferred = 0
            val target = 235L * 1152 + 37
            assertEquals(target, decoder.seek(target))
            val block = assertNotNull(decoder.nextFrame())
            assertContentEquals(expected.samples.copyOfRange(target.toInt(), target.toInt() + block.frameCount), block.samples)
            assertTrue(source.transferred in 1 until 65_536, "seek read ${source.transferred} bytes")
            val before = decoder.positionFrames
            assertFailsWith<IllegalArgumentException> { decoder.seek(-1) }
            assertEquals(before, decoder.positionFrames)
        }
    }

    private fun checkSeeks(bytes: ByteArray, expected: FloatArray, channels: Int) {
        val frames = expected.size / channels
        for (reuse in listOf(false, true)) {
            AudioKodec.open(bytes, DecodeOptions(allowSlowSeek = false, reuseBuffers = reuse)).use { decoder ->
                for (target in listOf(frames / 2, 0, 1, frames - 1, frames, frames + 77, 37, frames / 3)) {
                    val at = minOf(target, frames)
                    assertEquals(at.toLong(), decoder.seek(target.toLong()))
                    val block = decoder.nextFrame()
                    if (at == frames) {
                        assertNull(block)
                    } else {
                        val audio = assertNotNull(block)
                        assertEquals(at.toLong(), audio.positionFrames)
                        assertContentEquals(expected.copyOfRange(at * channels, (at + audio.frameCount) * channels),
                            audio.samples.copyOf(audio.sampleCount))
                    }
                }
            }
        }
    }
}

/** ISO sample tables and edit lists around the exact supplied MPEG packets. */
internal object MpegMp4Fixtures {
    fun packets(bytes: ByteArray): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        var at = 0
        while (at < bytes.size) {
            val header = requireNotNull(MpegFrameHeader.parse(bytes, at))
            result += bytes.copyOfRange(at, at + header.frameBytes)
            at += header.frameBytes
        }
        return result
    }

    fun borrowingFrames(count: Int): ByteArray {
        val first = packets(Mp3SwitchedBlockFixtures.file(44_100, Mp3SwitchedBlockFixtures.Shape.NORMAL))[0]
        val borrowed = first.copyOf()
        // The 396-byte main data is identical in every packet. Point at the
        // preceding packet's main data instead, to exercise a real back-reference.
        borrowed[4] = (396 ushr 1).toByte()
        borrowed[5] = (borrowed[5].toInt() and 0x7F).toByte()
        return ByteArray(first.size * count).also { out ->
            repeat(count) { (if (it == 0) first else borrowed).copyInto(out, it * first.size) }
        }
    }

    fun file(
        packets: List<ByteArray>,
        entry: String = ".mp3",
        objectType: Int = 0x6B,
        edit: Pair<Long, Long>? = null,
        channels: Int = packetHeader(packets[0]).channels,
        rate: Int = packetHeader(packets[0]).sampleRate,
    ): ByteArray {
        val header = packetHeader(packets[0])
        val total = packets.size * header.samplesPerFrame
        val ftyp = box("ftyp", "isom".encodeToByteArray(), u32(0), "isom".encodeToByteArray())
        val payload = ByteArray(packets.sumOf { it.size })
        var at = 0
        for (packet in packets) { packet.copyInto(payload, at); at += packet.size }
        val configuration = if (entry == "mp4a") {
            full("esds", byteArrayOf(3, 18, 0, 1, 0, 4, 13, objectType.toByte(), 0x15), ByteArray(11))
        } else ByteArray(0)
        val sampleEntry = box(entry, ByteArray(6), u16(1), ByteArray(8), u16(channels), u16(16), ByteArray(4), u32(rate shl 16), configuration)
        val stbl = box("stbl",
            full("stsd", u32(1), sampleEntry),
            full("stts", u32(1), u32(packets.size), u32(header.samplesPerFrame)),
            full("stsc", u32(1), u32(1), u32(packets.size), u32(1)),
            full("stsz", u32(0), u32(packets.size), *packets.map { u32(it.size) }.toTypedArray()),
            full("stco", u32(1), u32(ftyp.size + 8)))
        val mdia = box("mdia",
            full("mdhd", u32(0), u32(0), u32(header.sampleRate), u32(total), ByteArray(4)),
            full("hdlr", u32(0), "soun".encodeToByteArray(), ByteArray(13)),
            box("minf", full("smhd", u32(0)), stbl))
        val edts = edit?.let { box("edts", full("elst", u32(1), u32(it.second.toInt()), u32(it.first.toInt()), u32(0x10000))) } ?: ByteArray(0)
        val trak = box("trak", full("tkhd", u32(0), u32(0), u32(1), u32(0), u32(total), ByteArray(60)), edts, mdia)
        val moov = box("moov", full("mvhd", u32(0), u32(0), u32(header.sampleRate), u32(total), ByteArray(80)), trak)
        return ftyp + box("mdat", payload) + moov
    }

    private fun packetHeader(packet: ByteArray): MpegFrameHeader {
        val fields = requireNotNull(MpegFrameHeader.parse(packet, 0, 4))
        return requireNotNull(MpegFrameHeader.parse(packet, 0, packet.size - fields.paddingBytes))
    }

    /** Nonzero ISO Layer I allocations, originally checked in MpegOracleTest. */
    fun layerOne(frames: Int, rate: Int): ByteArray {
        // 448 kbps, which is bitrate index 14 and leaves room for every
        // allocation width the test uses.
        val bitrateIndex = 14
        val bitrate = 448
        // Layer I counts its frames in four-byte slots.
        val frameBytes = (12 * bitrate * 1000 / rate) * 4
        val out = ByteArray(frameBytes * frames)
        for (f in 0 until frames) {
            val frame = ByteArray(frameBytes)
            // MPEG-1 Layer I, no CRC, 448 kbps, 44100 Hz, mono.
            frame[0] = 0xFF.toByte()
            frame[1] = 0xFF.toByte()
            frame[2] = ((bitrateIndex shl 4) or (0 shl 2)).toByte()
            frame[3] = (3 shl 6).toByte()

            val writer = io.github.yuroyami.audiokodec.internal.bits.BitWriterMsb()
            val widths = IntArray(32) { if (it < 16) (it % 14) + 1 else 0 }
            for (sb in 0 until 32) writer.writeBits(widths[sb], 4)
            for (sb in 0 until 32) {
                if (widths[sb] != 0) writer.writeBits((sb * 3 + f) % 63, 6)
            }
            for (slot in 0 until 12) {
                for (sb in 0 until 32) {
                    val width = widths[sb]
                    if (width == 0) continue
                    val bits = width + 1
                    val value = (slot * 37 + sb * 11 + f * 5) and ((1 shl bits) - 1)
                    writer.writeBits(value, bits)
                }
            }
            writer.alignToByte()
            val payload = writer.toByteArray()
            payload.copyInto(frame, 4, 0, minOf(payload.size, frameBytes - 4))
            frame.copyInto(out, f * frameBytes)
        }
        return out
    }

    private fun full(type: String, vararg body: ByteArray): ByteArray = box(type, ByteArray(4), *body)
    private fun box(type: String, vararg body: ByteArray): ByteArray =
        u32(8 + body.sumOf { it.size }) + type.encodeToByteArray() + body.fold(ByteArray(0)) { a, b -> a + b }
    private fun u16(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())
    private fun u32(value: Int): ByteArray = ByteArray(4) { (value ushr (24 - it * 8)).toByte() }
}

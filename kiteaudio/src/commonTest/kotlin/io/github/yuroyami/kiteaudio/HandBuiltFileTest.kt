package io.github.yuroyami.kiteaudio

import io.github.yuroyami.kiteaudio.tag.TagBundle
import io.github.yuroyami.kiteaudio.tag.TagField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Container fixtures assembled independently of KiteAudio's writers.
 *
 * Building them byte by byte tests something a round trip cannot: that the
 * reader agrees with the format rather than merely with our own writer.
 */
class HandBuiltFileTest {

    @Test
    fun aWave64FileReadsBackAsTheSamplesItHolds() {
        val samples = shortArrayOf(0, 1000, -1000, 32_767, -32_768, 12_345, -12_345, 7)
        val file = buildWave64(sampleRate = 48_000, channels = 2, samples = samples)

        assertEquals(AudioContainer.WAVE64, KiteAudio.sniff(file))
        val info = KiteAudio.probe(file)
        assertEquals(AudioContainer.WAVE64, info.container)
        assertEquals(AudioCodec.PCM_INTEGER, info.codec)
        assertEquals(48_000, info.format.sampleRate)
        assertEquals(2, info.format.channels)
        assertEquals(16, info.bitDepth)
        assertEquals(4L, info.durationFrames)

        val decoded = KiteAudio.decode(file)
        assertEquals(4, decoded.frames)
        val expected = Pcm.floatFromInt(IntArray(samples.size) { samples[it].toInt() }, 16)
        TestSignals.assertExact(expected, decoded.samples)
    }

    @Test
    fun wave64ValidatesItsOuterSizeAndExtensibleSubformatGuid() {
        val samples = shortArrayOf(1, -1, 2, -2)
        val valid = buildWave64(
            sampleRate = 48_000,
            channels = 1,
            samples = samples,
            extensible = true,
        )
        assertEquals(4, KiteAudio.decode(valid).frames)

        val overlong = valid.copyOf()
        writeI64Le(overlong, 16, valid.size.toLong() + 100)
        assertFailsWith<TruncatedStreamException> { KiteAudio.probe(overlong) }

        val alienGuid = buildWave64(
            sampleRate = 48_000,
            channels = 1,
            samples = samples,
            extensible = true,
            invalidSubformat = true,
        )
        assertFailsWith<UnsupportedFeatureException> { KiteAudio.probe(alienGuid) }

        val chunkPastForm = buildWave64(
            sampleRate = 48_000,
            channels = 1,
            samples = samples,
        )
        // Non-extensible files put the data chunk's 64-bit size at byte 96.
        writeI64Le(chunkPastForm, 96, readI64Le(chunkPastForm, 96) + 8)
        assertFailsWith<CorruptStreamException> { KiteAudio.probe(chunkPastForm) }
    }

    @Test
    fun anRf64FileReadsItsSizesFromTheDs64Chunk() {
        val samples = shortArrayOf(100, -100, 200, -200, 300, -300)
        val file = buildRf64(sampleRate = 44_100, channels = 1, samples = samples)

        assertEquals(AudioContainer.RF64, KiteAudio.sniff(file))
        val info = KiteAudio.probe(file)
        assertEquals(AudioContainer.RF64, info.container)
        assertEquals(6L, info.durationFrames)
        assertEquals(12L, info.dataSize)

        val decoded = KiteAudio.decode(file)
        assertEquals(6, decoded.frames)
        val expected = Pcm.floatFromInt(IntArray(samples.size) { samples[it].toInt() }, 16)
        TestSignals.assertExact(expected, decoded.samples)
    }

    @Test
    fun chunkedAudioReadersRejectChunksThatEscapeTheirEnclosingForms() {
        val samples = floatArrayOf(0f, 0.25f, -0.25f)

        val wave = KiteAudio.encode(samples, AudioFormat(44_100, 1), EncodeTarget.wav())
        writeU32Le(wave, 4, readU32Le(wave, 4) - 2L)
        assertFailsWith<CorruptStreamException> { KiteAudio.probe(wave) }

        val aiff = KiteAudio.encode(samples, AudioFormat(44_100, 1), EncodeTarget.aiff())
        writeU32Be(aiff, 4, readU32Be(aiff, 4) - 2L)
        assertFailsWith<CorruptStreamException> { KiteAudio.probe(aiff) }

        val rf64 = buildRf64(
            sampleRate = 44_100,
            channels = 1,
            samples = shortArrayOf(1, 2, 3),
        )
        writeI64Le(rf64, 20, readI64Le(rf64, 20) - 2L)
        assertFailsWith<CorruptStreamException> { KiteAudio.probe(rf64) }

        val overflowingRf64 = buildRf64(
            sampleRate = 44_100,
            channels = 1,
            samples = shortArrayOf(1),
        )
        writeI64Le(overflowingRf64, 20, Long.MAX_VALUE)
        assertFailsWith<CorruptStreamException> { KiteAudio.probe(overflowingRf64) }
    }

    @Test
    fun rf64ResolvesNonDataPlaceholderSizesFromTheDs64Table() {
        val samples = shortArrayOf(100, -100, 200, -200)
        val file = buildRf64(
            sampleRate = 44_100,
            channels = 1,
            samples = samples,
            extraPlaceholder = true,
        )
        val info = KiteAudio.probe(file)
        assertEquals(samples.size.toLong(), info.durationFrames)
        val expected = Pcm.floatFromInt(IntArray(samples.size) { samples[it].toInt() }, 16)
        TestSignals.assertExact(expected, KiteAudio.decode(file).samples)
    }

    @Test
    fun taggingRf64KeepsTheSentinelAndRebuildsDs64() {
        val samples = shortArrayOf(100, -100, 200, -200, 300, -300)
        val file = buildRf64(sampleRate = 44_100, channels = 1, samples = samples)
        val tagged = KiteAudio.writeTags(
            file,
            TagBundle(mapOf(TagField.TITLE to listOf("RF64 title"))),
        )

        assertEquals("RF64", tagged.decodeToString(0, 4))
        assertEquals(0xFFFF_FFFFL, readU32Le(tagged, 4), "the RF64 size sentinel")
        assertEquals("ds64", tagged.decodeToString(12, 16))
        assertEquals((tagged.size - 8).toLong(), readI64Le(tagged, 20), "the rebuilt RIFF size")
        assertEquals(samples.size.toLong() * 2, readI64Le(tagged, 28), "the data size")
        assertEquals("RF64 title", KiteAudio.readTags(tagged)[TagField.TITLE])

        val decoded = KiteAudio.decode(tagged)
        val expected = Pcm.floatFromInt(IntArray(samples.size) { samples[it].toInt() }, 16)
        TestSignals.assertExact(expected, decoded.samples)
    }

    @Test
    fun rf64RewriteMatchesOutOfOrderDs64TableEntriesByIdentity() {
        val samples = shortArrayOf(100, -100, 200, -200)
        val file = buildRf64(
            sampleRate = 44_100,
            channels = 1,
            samples = samples,
            outOfOrderPlaceholders = true,
        )
        val tagged = KiteAudio.writeTags(
            file,
            TagBundle(mapOf(TagField.TITLE to listOf("Still valid"))),
        )

        assertEquals("Still valid", KiteAudio.readTags(tagged)[TagField.TITLE])
        val expected = Pcm.floatFromInt(IntArray(samples.size) { samples[it].toInt() }, 16)
        TestSignals.assertExact(expected, KiteAudio.decode(tagged).samples)
    }

    @Test
    fun anAiffCFileWithQuickTimeAdpcmDecodesToTheRightLength() {
        // One packet per channel: 34 bytes in, 64 frames out.
        val packet = ByteArray(34)
        // A predictor of zero and a step index of zero, then nibbles that
        // walk the predictor up and down.
        packet[0] = 0
        packet[1] = 0
        for (i in 2 until 34) packet[i] = 0x18
        val file = buildAiffC("ima4", sampleRate = 22_050, channels = 1, frames = 64, data = packet)

        val info = KiteAudio.probe(file)
        assertEquals(AudioCodec.IMA_ADPCM, info.codec)
        assertEquals(64L, info.durationFrames)
        val decoded = KiteAudio.decode(file)
        assertEquals(64, decoded.frames)
        // The nibbles alternate a small step up and a large one, so the
        // result has to move rather than sit at zero.
        assertTrue(decoded.samples.any { it != 0f }, "the decoded packet is silent")
    }

    @Test
    fun aiffRejectsAnSsndOffsetThatRunsPastTheChunk() {
        val file = buildAiffC(
            "NONE",
            sampleRate = 44_100,
            channels = 1,
            frames = 1,
            data = byteArrayOf(0, 0),
        )
        val ssnd = file.indexOfBytes("SSND".encodeToByteArray())
        assertTrue(ssnd >= 0)
        writeU32Be(file, ssnd + 8, 100)
        assertFailsWith<CorruptStreamException> { KiteAudio.probe(file) }
    }

    @Test
    fun cafDecodesPackedAndUnpackedHighAlignedIntegerWidths() {
        val cases = listOf(
            CafPcmCase(24, 3, littleEndian = false, intArrayOf(-8_388_608, -1, 0, 1, 8_388_607)),
            CafPcmCase(24, 4, littleEndian = false, intArrayOf(-8_388_608, -1, 0, 1, 8_388_607)),
            CafPcmCase(24, 4, littleEndian = true, intArrayOf(-8_388_608, -1, 0, 1, 8_388_607)),
            CafPcmCase(12, 2, littleEndian = false, intArrayOf(-2_048, -17, 0, 17, 2_047)),
            CafPcmCase(18, 3, littleEndian = true, intArrayOf(-131_072, -3, 0, 3, 131_071)),
        )

        for (case in cases) {
            val file = buildIntegerCaf(case)
            val what = "${case.bits}-in-${case.storageBytes * 8}, " +
                if (case.littleEndian) "little-endian" else "big-endian"
            val info = KiteAudio.probe(file)
            assertEquals(AudioContainer.CAF, info.container, what)
            assertEquals(case.bits, info.bitDepth, what)
            assertEquals(case.samples.size.toLong(), info.durationFrames, what)

            KiteAudio.open(file).use { decoder ->
                val integers = assertIs<IntPcmAccess>(decoder, what)
                assertEquals(case.bits, integers.bitDepth, what)
                val frame = integers.nextIntFrame()
                    ?: throw AssertionError("$what produced no samples")
                assertEquals(case.samples.size, frame.frameCount, what)
                for (index in case.samples.indices) {
                    assertEquals(case.samples[index], frame.samples[index], "$what sample $index")
                }
            }
        }
    }

    @Test
    fun cafRejectsADataChunkTooShortForItsEditCount() {
        val out = Builder()
        out.ascii("caff")
        out.u16Be(1)
        out.u16Be(0)
        out.ascii("desc")
        out.i64Be(32)
        out.f64Be(44_100.0)
        out.ascii("lpcm")
        out.u32Be(0)
        out.u32Be(2)
        out.u32Be(1)
        out.u32Be(1)
        out.u32Be(16)
        out.ascii("data")
        out.i64Be(3)
        out.bytes(byteArrayOf(0, 0, 0))

        assertFailsWith<CorruptStreamException> { KiteAudio.probe(out.toByteArray()) }
    }

    @Test
    fun cafRequiresItsExactDescriptionFirstAndValidCompressedGeometry() {
        val valid = buildIntegerCaf(
            CafPcmCase(16, 2, littleEndian = false, intArrayOf(0)),
        )

        val unknownDescriptionSize = valid.copyOf()
        for (index in 12..19) unknownDescriptionSize[index] = 0xFF.toByte()
        assertFailsWith<CorruptStreamException> {
            KiteAudio.probe(unknownDescriptionSize)
        }

        val free = Builder().also {
            it.ascii("free")
            it.i64Be(0)
        }.toByteArray()
        val misplaced = valid.copyOfRange(0, 8) + free + valid.copyOfRange(8, valid.size)
        assertFailsWith<CorruptStreamException> { KiteAudio.probe(misplaced) }

        val mismatchedLayout = Builder().also {
            it.ascii("chan")
            it.i64Be(12)
            it.u32Be((101L shl 16) or 2L) // Stereo layout, two channels.
            it.u32Be(0)
            it.u32Be(0)
        }.toByteArray()
        val withLayout = valid.copyOfRange(0, 52) +
            mismatchedLayout +
            valid.copyOfRange(52, valid.size)
        assertFailsWith<CorruptStreamException> { KiteAudio.probe(withLayout) }

        val unknownLayout = Builder().also {
            it.ascii("chan")
            it.i64Be(12)
            it.u32Be((999L shl 16) or 1L)
            it.u32Be(0)
            it.u32Be(0)
        }.toByteArray()
        val withUnknownLayout = valid.copyOfRange(0, 52) +
            unknownLayout +
            valid.copyOfRange(52, valid.size)
        assertFailsWith<UnsupportedFeatureException> {
            KiteAudio.probe(withUnknownLayout)
        }

        val truncatedDescriptions = Builder().also {
            it.ascii("chan")
            it.i64Be(12)
            it.u32Be(0) // Use explicit descriptions.
            it.u32Be(0)
            it.u32Be(1) // One 20-byte description is not present.
        }.toByteArray()
        val withTruncatedDescriptions = valid.copyOfRange(0, 52) +
            truncatedDescriptions +
            valid.copyOfRange(52, valid.size)
        assertFailsWith<CorruptStreamException> {
            KiteAudio.probe(withTruncatedDescriptions)
        }

        val fractionalRate = valid.copyOf()
        writeF64Be(fractionalRate, 20, 44_100.5)
        assertFailsWith<UnsupportedFeatureException> {
            KiteAudio.probe(fractionalRate)
        }

        val badIma = Builder().also {
            it.ascii("caff")
            it.u16Be(1)
            it.u16Be(0)
            it.ascii("desc")
            it.i64Be(32)
            it.f64Be(44_100.0)
            it.ascii("ima4")
            it.u32Be(0)
            it.u32Be(33) // IMA4 requires 34 bytes per channel.
            it.u32Be(64)
            it.u32Be(1)
            it.u32Be(0)
            it.ascii("data")
            it.i64Be(4)
            it.u32Be(0)
        }.toByteArray()
        assertFailsWith<CorruptStreamException> { KiteAudio.probe(badIma) }
    }

    @Test
    fun cafPacketTablesTrimPrimingAndRemainderFramesAndRemainSeekable() {
        val file = buildTrimmedCaf()
        val info = KiteAudio.probe(file)
        assertEquals(3L, info.durationFrames)

        KiteAudio.open(file).use { decoder ->
            val integers = assertIs<IntPcmAccess>(decoder)
            val first = integers.nextIntFrame()
                ?: throw AssertionError("the trimmed CAF produced no samples")
            assertEquals(3, first.frameCount)
            assertEquals(0L, first.positionFrames)
            val expected = intArrayOf(1_000, 2_000, 3_000)
            for (index in expected.indices) {
                assertEquals(expected[index], first.samples[index], "trimmed sample $index")
            }

            assertEquals(1L, decoder.seek(1))
            val sought = integers.nextIntFrame()
                ?: throw AssertionError("the trimmed CAF produced no sample after seeking")
            assertEquals(1L, sought.positionFrames)
            assertEquals(2, sought.frameCount)
            assertEquals(2_000, sought.samples[0])
            assertEquals(3_000, sought.samples[1])
        }

        assertFailsWith<CorruptStreamException> {
            KiteAudio.probe(buildTrimmedCaf(validFrames = 4))
        }
        assertFailsWith<CorruptStreamException> {
            KiteAudio.probe(buildTrimmedCaf(packetCount = 6))
        }
        assertFailsWith<CorruptStreamException> {
            KiteAudio.probe(buildTrimmedCaf(appendMismatchedLayout = true))
        }
    }

    private data class CafPcmCase(
        val bits: Int,
        val storageBytes: Int,
        val littleEndian: Boolean,
        val samples: IntArray,
    )

    private fun buildIntegerCaf(case: CafPcmCase): ByteArray {
        val out = Builder()
        out.ascii("caff")
        out.u16Be(1)
        out.u16Be(0)

        out.ascii("desc")
        out.i64Be(32)
        out.f64Be(48_000.0)
        out.ascii("lpcm")
        out.u32Be(if (case.littleEndian) 2 else 0)
        out.u32Be(case.storageBytes.toLong())
        out.u32Be(1)
        out.u32Be(1)
        out.u32Be(case.bits.toLong())

        out.ascii("data")
        out.i64Be(4L + case.samples.size * case.storageBytes)
        out.u32Be(0)
        val mask = (1L shl case.bits) - 1
        val padding = case.storageBytes * 8 - case.bits
        for (sample in case.samples) {
            val stored = (sample.toLong() and mask) shl padding
            if (case.littleEndian) {
                for (byte in 0 until case.storageBytes) {
                    out.u8((stored ushr (byte * 8)).toInt())
                }
            } else {
                for (byte in case.storageBytes - 1 downTo 0) {
                    out.u8((stored ushr (byte * 8)).toInt())
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * Six coded PCM frames: two priming, three valid, and one remainder.
     * The packet table deliberately follows `data`, as CAF permits.
     */
    private fun buildTrimmedCaf(
        validFrames: Long = 3,
        packetCount: Long = 0,
        appendMismatchedLayout: Boolean = false,
    ): ByteArray {
        val coded = intArrayOf(-20_000, -10_000, 1_000, 2_000, 3_000, 20_000)
        val out = Builder()
        out.ascii("caff")
        out.u16Be(1)
        out.u16Be(0)

        out.ascii("desc")
        out.i64Be(32)
        out.f64Be(48_000.0)
        out.ascii("lpcm")
        out.u32Be(0)
        out.u32Be(2)
        out.u32Be(1)
        out.u32Be(1)
        out.u32Be(16)

        out.ascii("data")
        out.i64Be(4L + coded.size * 2L)
        out.u32Be(0)
        for (sample in coded) out.u16Be(sample and 0xFFFF)

        out.ascii("pakt")
        out.i64Be(24)
        out.i64Be(packetCount) // Constant-size packets need no packet-size table.
        out.i64Be(validFrames)
        out.u32Be(2) // Priming frames.
        out.u32Be(1) // Remainder frame.

        if (appendMismatchedLayout) {
            out.ascii("chan")
            out.i64Be(12)
            out.u32Be((101L shl 16) or 2L) // Stereo layout for mono audio.
            out.u32Be(0)
            out.u32Be(0)
        }
        return out.toByteArray()
    }

    private fun buildWave64(
        sampleRate: Int,
        channels: Int,
        samples: ShortArray,
        extensible: Boolean = false,
        invalidSubformat: Boolean = false,
    ): ByteArray {
        val waveSuffix = byteArrayOf(
            0xF3.toByte(), 0xAC.toByte(), 0xD3.toByte(), 0x11,
            0x8C.toByte(), 0xD1.toByte(), 0x00, 0xC0.toByte(),
            0x4F, 0x8E.toByte(), 0xDB.toByte(), 0x8A.toByte(),
        )
        fun guid(name: String) = ByteArray(16).also {
            for (i in 0 until 4) it[i] = name[i].code.toByte()
            waveSuffix.copyInto(it, 4)
        }

        val out = Builder()
        // The outer identifier is the one GUID with its own suffix.
        out.bytes(
            byteArrayOf(
                0x72, 0x69, 0x66, 0x66,
                0x2E, 0x91.toByte(), 0xCF.toByte(), 0x11,
                0xA5.toByte(), 0xD6.toByte(), 0x28, 0xDB.toByte(),
                0x04, 0xC1.toByte(), 0x00, 0x00,
            ),
        )
        val totalAt = out.size
        out.i64Le(0)
        out.bytes(guid("wave"))

        // The format chunk, whose body is the same structure WAV uses.
        out.bytes(guid("fmt "))
        out.i64Le(24L + if (extensible) 40L else 16L)
        out.u16Le(if (extensible) 0xFFFE else 1)
        out.u16Le(channels)
        out.i32Le(sampleRate)
        out.i32Le(sampleRate * channels * 2)
        out.u16Le(channels * 2)
        out.u16Le(16)
        if (extensible) {
            out.u16Le(22)
            out.u16Le(16)
            out.i32Le(0)
            out.u16Le(1)
            val suffix = byteArrayOf(
                0x00, 0x00, 0x00, 0x00,
                0x10, 0x00, 0x80.toByte(), 0x00,
                0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71,
            )
            if (invalidSubformat) suffix[suffix.lastIndex] = 0
            out.bytes(suffix)
        }
        out.padTo(8)

        out.bytes(guid("data"))
        out.i64Le(24L + samples.size * 2)
        for (s in samples) out.u16Le(s.toInt() and 0xFFFF)
        out.padTo(8)

        val bytes = out.toByteArray()
        writeI64Le(bytes, totalAt, bytes.size.toLong())
        return bytes
    }

    private fun buildRf64(
        sampleRate: Int,
        channels: Int,
        samples: ShortArray,
        extraPlaceholder: Boolean = false,
        outOfOrderPlaceholders: Boolean = false,
    ): ByteArray {
        val out = Builder()
        out.ascii("RF64")
        out.u32Le(0xFFFF_FFFFL)
        out.ascii("WAVE")

        val dataBytes = samples.size.toLong() * 2
        out.ascii("ds64")
        val tableCount = when {
            outOfOrderPlaceholders -> 2
            extraPlaceholder -> 1
            else -> 0
        }
        out.u32Le(28L + tableCount * 12L)
        val riffSizeAt = out.size
        out.i64Le(0)
        out.i64Le(dataBytes)
        out.i64Le(samples.size.toLong() / channels)
        out.u32Le(tableCount.toLong())
        if (outOfOrderPlaceholders) {
            // The table need not use the same ID order as the chunks. This is
            // deliberately ABCD/JUNK while the chunks below are JUNK/ABCD.
            out.ascii("ABCD")
            out.i64Le(4)
            out.ascii("JUNK")
            out.i64Le(4)
        } else if (extraPlaceholder) {
            out.ascii("JUNK")
            out.i64Le(4)
        }
        if (extraPlaceholder || outOfOrderPlaceholders) {
            out.ascii("JUNK")
            out.u32Le(0xFFFF_FFFFL)
            out.bytes(byteArrayOf(1, 2, 3, 4))
        }
        if (outOfOrderPlaceholders) {
            out.ascii("ABCD")
            out.u32Le(0xFFFF_FFFFL)
            out.bytes(byteArrayOf(5, 6, 7, 8))
        }

        out.ascii("fmt ")
        out.u32Le(16)
        out.u16Le(1)
        out.u16Le(channels)
        out.i32Le(sampleRate)
        out.i32Le(sampleRate * channels * 2)
        out.u16Le(channels * 2)
        out.u16Le(16)

        out.ascii("data")
        out.u32Le(0xFFFF_FFFFL)
        for (s in samples) out.u16Le(s.toInt() and 0xFFFF)

        val bytes = out.toByteArray()
        writeI64Le(bytes, riffSizeAt, (bytes.size - 8).toLong())
        return bytes
    }

    private fun buildAiffC(
        compression: String,
        sampleRate: Int,
        channels: Int,
        frames: Int,
        data: ByteArray,
    ): ByteArray {
        val out = Builder()
        out.ascii("FORM")
        val formSizeAt = out.size
        out.u32Be(0)
        out.ascii("AIFC")

        out.ascii("FVER")
        out.u32Be(4)
        out.u32Be(0xA2805140L)

        val name = "test"
        val commBody = 18 + 4 + 1 + name.length + ((1 + name.length) % 2)
        out.ascii("COMM")
        out.u32Be(commBody.toLong())
        out.u16Be(channels)
        out.u32Be(frames.toLong())
        out.u16Be(16)
        out.extended(sampleRate.toDouble())
        out.ascii(compression)
        out.u8(name.length)
        out.ascii(name)
        if ((1 + name.length) % 2 == 1) out.u8(0)

        out.ascii("SSND")
        out.u32Be(data.size.toLong() + 8)
        out.u32Be(0)
        out.u32Be(0)
        out.bytes(data)

        val bytes = out.toByteArray()
        writeU32Be(bytes, formSizeAt, (bytes.size - 8).toLong())
        return bytes
    }
}

/** A growable byte buffer with the field widths the containers use. */
private class Builder {
    private var buffer = ByteArray(256)
    var size = 0
        private set

    fun bytes(value: ByteArray) {
        ensure(size + value.size)
        value.copyInto(buffer, size)
        size += value.size
    }

    fun ascii(text: String) {
        ensure(size + text.length)
        for (c in text) buffer[size++] = c.code.toByte()
    }

    fun u8(value: Int) {
        ensure(size + 1)
        buffer[size++] = value.toByte()
    }

    fun u16Le(value: Int) {
        u8(value)
        u8(value ushr 8)
    }

    fun u16Be(value: Int) {
        u8(value ushr 8)
        u8(value)
    }

    fun i32Le(value: Int) {
        u16Le(value)
        u16Le(value ushr 16)
    }

    fun u32Le(value: Long) {
        i32Le(value.toInt())
    }

    fun u32Be(value: Long) {
        val v = value.toInt()
        u8(v ushr 24)
        u8(v ushr 16)
        u8(v ushr 8)
        u8(v)
    }

    fun i64Le(value: Long) {
        u32Le(value and 0xFFFF_FFFFL)
        u32Le(value ushr 32)
    }

    fun i64Be(value: Long) {
        u32Be(value ushr 32)
        u32Be(value and 0xFFFF_FFFFL)
    }

    fun f64Be(value: Double) {
        i64Be(value.toRawBits())
    }

    /** The 80-bit float AIFF stores a sample rate in. */
    fun extended(value: Double) {
        var exponent = 0
        var fraction = value
        while (fraction >= 1.0) {
            fraction /= 2.0
            exponent++
        }
        while (fraction < 0.5) {
            fraction *= 2.0
            exponent--
        }
        val scaled = fraction * 4_294_967_296.0
        val high = scaled.toLong()
        val low = ((scaled - high) * 4_294_967_296.0).toLong()
        u16Be(exponent + 16382)
        u32Be(high)
        u32Be(low)
    }

    /** Pads with zeros until the length is a multiple of [alignment]. */
    fun padTo(alignment: Int) {
        while (size % alignment != 0) u8(0)
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun ensure(needed: Int) {
        if (needed <= buffer.size) return
        var next = buffer.size
        while (next < needed) next *= 2
        buffer = buffer.copyOf(next)
    }
}

private fun writeI64Le(out: ByteArray, at: Int, value: Long) {
    for (i in 0 until 8) out[at + i] = (value ushr (8 * i)).toByte()
}

private fun readI64Le(bytes: ByteArray, at: Int): Long {
    var value = 0L
    for (i in 0 until 8) value = value or ((bytes[at + i].toLong() and 0xFF) shl (8 * i))
    return value
}

private fun readU32Le(bytes: ByteArray, at: Int): Long {
    var value = 0L
    for (i in 0 until 4) value = value or ((bytes[at + i].toLong() and 0xFF) shl (8 * i))
    return value
}

private fun writeU32Le(out: ByteArray, at: Int, value: Long) {
    for (i in 0 until 4) out[at + i] = (value ushr (8 * i)).toByte()
}

private fun readU32Be(bytes: ByteArray, at: Int): Long =
    ((bytes[at].toLong() and 0xFF) shl 24) or
        ((bytes[at + 1].toLong() and 0xFF) shl 16) or
        ((bytes[at + 2].toLong() and 0xFF) shl 8) or
        (bytes[at + 3].toLong() and 0xFF)

private fun writeU32Be(out: ByteArray, at: Int, value: Long) {
    val v = value.toInt()
    out[at] = (v ushr 24).toByte()
    out[at + 1] = (v ushr 16).toByte()
    out[at + 2] = (v ushr 8).toByte()
    out[at + 3] = v.toByte()
}

private fun writeF64Be(out: ByteArray, at: Int, value: Double) {
    val bits = value.toRawBits()
    for (i in 0 until 8) out[at + i] = (bits ushr (56 - i * 8)).toByte()
}

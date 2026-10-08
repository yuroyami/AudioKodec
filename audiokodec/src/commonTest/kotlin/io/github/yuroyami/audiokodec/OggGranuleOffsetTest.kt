package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.ogg.OggReader
import io.github.yuroyami.audiokodec.tag.TagField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class OggGranuleOffsetTest {
    @Test
    fun aPositiveStartKeepsDurationSamplesAndSeekPositions() {
        for (original in listOf(OggGranuleFixtures.vorbis(), OggGranuleFixtures.flac())) {
            val expected = AudioKodec.decode(original)
            for (offset in listOf(321L, 20_000L, 99_000L)) {
                val shifted = OggGranuleFixtures.shift(original, offset)
                val edited = AudioKodec.writeTags(shifted, AudioKodec.readTags(shifted).edit { set(TagField.TITLE, "Rebased") })
                TestSignals.assertExact(expected.samples, AudioKodec.decode(edited).samples)
                for (knownSize in listOf(false, true)) {
                    for (tolerant in listOf(false, true)) {
                        val options = DecodeOptions(tolerant = tolerant)
                        val info = AudioKodec.probe(OggDamageFixtures.source(shifted, knownSize), options)
                        assertEquals(expected.frames.toLong(), info.durationFrames)
                        val decoded = AudioKodec.decode(OggDamageFixtures.source(shifted, knownSize), options)
                        TestSignals.assertExact(expected.samples, decoded.samples)
                        AudioKodec.open(OggDamageFixtures.source(shifted, knownSize), options).use { decoder ->
                            for (target in listOf(0L, 1L, expected.frames / 3L, expected.frames / 2L, expected.frames - 1L)) {
                                assertEquals(target, decoder.seek(target))
                                val block = assertNotNull(decoder.nextFrame())
                                assertEquals(target, block.positionFrames)
                                val wanted = expected.samples.copyOfRange(target.toInt(), target.toInt() + block.frameCount)
                                val actual = block.samples.copyOf(block.sampleCount)
                                if (info.codec == AudioCodec.FLAC) TestSignals.assertExact(wanted, actual)
                                else TestSignals.assertClose(wanted, actual, 1e-4f)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun anAmbiguousSinglePageVorbisOriginIsNamedAndRefused() {
        val shifted = OggGranuleFixtures.shift(VorbisTest().file(), 20_000)
        for (tolerant in listOf(false, true)) {
            assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.decode(shifted, DecodeOptions(tolerant = tolerant))
            }
        }
    }
}

internal object OggGranuleFixtures {
    /** The committed 8 kHz fixture uses 512-sample windows in all nine packets. */
    fun vorbis(): ByteArray {
        val options = DecodeOptions.DEFAULT
        val ogg = OggReader(SourceReader(VorbisTest().file().asAudioSource(), options), options)
        val serial = ogg.readStreamHeads().first().serialNumber
        val reader = ogg.packets(serial, 0)
        val packets = buildList {
            while (true) {
                val packet = reader.next() ?: break
                add(packet.bytes.copyOf(packet.length))
            }
        }
        assertEquals(12, packets.size)
        val builder = OggBuilder().page(serial, 0, 0, 2, listOf(packets[0]))
            .page(serial, 1, 0, 0, listOf(packets[1], packets[2]))
        repeat(9) { index ->
            builder.page(serial, index + 2, if (index == 8) 2_000L else index * 256L,
                if (index == 8) 4 else 0, listOf(packets[index + 3]))
        }
        return builder.build()
    }

    fun flac(): ByteArray {
        val native = FlacContinuityFixtures.valid()
        val audio = AudioKodec.probe(native).dataOffset.toInt()
        val id = byteArrayOf(0x7F) + "FLAC".encodeToByteArray() + byteArrayOf(1, 0, 0, 1) +
            native.copyOf(42).also { it[4] = 0 }
        val comments = TagStressFixtures.commentBody(listOf("TITLE=Offset"))
        val comment = byteArrayOf(0x84.toByte(), 0, 0, comments.size.toByte()) + comments
        return OggBuilder().page(41, 0, 0, 2, listOf(id))
            .page(41, 1, 0, 0, listOf(comment))
            .page(41, 2, 4096, 0, listOf(native.copyOfRange(audio, audio + 11)))
            .page(41, 3, 8192, 4, listOf(native.copyOfRange(audio + 11, native.size)))
            .build()
    }

    /** RFC 3533 granule and checksum changes leave every packet byte untouched. */
    fun shift(original: ByteArray, offset: Long): ByteArray {
        val audio = AudioKodec.probe(original).dataOffset
        return original.copyOf().also { bytes ->
            for (range in OggDamageFixtures.pages(bytes)) {
                if (range.first.toLong() < audio) continue
                val old = OggDamageFixtures.granule(bytes, range)
                if (old < 0) continue
                repeat(8) { bytes[range.first + 6 + it] = ((old + offset) ushr (8 * it)).toByte() }
                bytes.fill(0, range.first + 22, range.first + 26)
                var crc = 0
                for (at in range) {
                    crc = crc xor ((bytes[at].toInt() and 255) shl 24)
                    repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04C11DB7 else 0 }
                }
                repeat(4) { bytes[range.first + 22 + it] = (crc ushr (8 * it)).toByte() }
            }
        }
    }
}

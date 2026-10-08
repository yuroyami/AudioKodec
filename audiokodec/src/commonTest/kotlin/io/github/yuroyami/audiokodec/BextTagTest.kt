package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.io.SinkWriter
import io.github.yuroyami.audiokodec.tag.RawTagBlock
import io.github.yuroyami.audiokodec.tag.RawTagEntry
import io.github.yuroyami.audiokodec.tag.RawTags
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BextTagTest {
    @Test
    fun createsEveryFieldWithoutChangingPcmInRiffRf64AndBw64() {
        for (id in listOf("RIFF", "RF64", "BW64")) {
            val original = BextFixtures.audio(id)
            val bytes = AudioKodec.writeTags(original, BextFixtures.tags(), BextFixtures.options)
            assertEquals(id, bytes.decodeToString(0, 4))
            assertEquals(BextFixtures.values, BextFixtures.text(bytes))
            assertContentEquals(BextFixtures.payload(original), BextFixtures.payload(bytes))
            assertContentEquals(AudioKodec.decode(original).samples, AudioKodec.decode(bytes).samples)
            val body = BextFixtures.body(bytes)
            assertTrue(body.copyOfRange(338, 346).all { it == 0xFF.toByte() }, "unsigned 64-bit time reference")
            assertTrue(body.copyOfRange(348, 602).all { it == 0.toByte() }, "version zero's reserved area")
            if (id == "RIFF") assertEquals(bytes.size - 8L, BextFixtures.u32(bytes, 4))
            else assertEquals(bytes.size - 8L, BextFixtures.i64(bytes, 20))
            for (knownSize in listOf(false, true)) {
                AudioKodec.open(OggDamageFixtures.source(bytes, knownSize)).use { decoder ->
                    assertEquals(64L, decoder.durationFrames)
                    assertEquals(17L, decoder.seek(17))
                    assertEquals(17L, assertNotNull(decoder.nextFrame()).positionFrames)
                }
            }
        }
    }

    @Test
    fun truncatesFixedWidthsPreservesSpacesAndAddsTheHistoryLineEnding() {
        val requested = mapOf("Description" to " d".repeat(150), "Originator" to "Originator".repeat(8),
            "OriginatorReference" to "reference".repeat(8), "OriginationDate" to "2026-10-08 ignored",
            "OriginationTime" to "12:34:56 ignored", "TimeReference" to "4294967297", "Version" to "0",
            "CodingHistory" to "A")
        val written = AudioKodec.writeTags(BextFixtures.audio(), BextFixtures.tags(requested), BextFixtures.options)
        val expected = requested.toMutableMap().also {
            it["Description"] = requested.getValue("Description").take(256)
            it["Originator"] = requested.getValue("Originator").take(32)
            it["OriginatorReference"] = requested.getValue("OriginatorReference").take(32)
            it["OriginationDate"] = "2026-10-08"
            it["OriginationTime"] = "12:34:56"
            it["CodingHistory"] = "A\r\n"
        }
        assertEquals(expected, BextFixtures.text(written))
        assertEquals(605, BextFixtures.body(written).size)
        // An odd body has a pad byte outside its declared size.
        assertEquals(0.toByte(), written[12 + 8 + 605])
        assertContentEquals(written, AudioKodec.writeTags(written, AudioKodec.readTags(written), BextFixtures.options))
    }

    @Test
    fun editsKeepVersionOneAndTwoExtensionsAndDefaultEditsKeepTheEntireChunk() {
        for (version in 1..2) {
            val extension = ByteArray(264)
            extension[8] = version.toByte()
            for (index in 10 until 74) extension[index] = (index * 7).toByte()
            if (version == 2) for (index in 74 until 84) extension[index] = (index * 13).toByte()
            val original = AudioKodec.writeTags(BextFixtures.audio(), BextFixtures.tags(
                BextFixtures.values + ("Version" to version.toString()), extension), BextFixtures.options)
            val read = AudioKodec.readTags(original)
            val block = read.raw.blocksOf(TagFormat.BWF_BEXT).single()
            val entries = block.entries.map { if (it.key == "Description") RawTagEntry(it.key, text = "Edited") else it }
            val edited = TagBundle(emptyMap(), raw = RawTags(listOf(RawTagBlock(TagFormat.BWF_BEXT, 0, 0, entries))))
            val written = AudioKodec.writeTags(original, edited, BextFixtures.options)
            assertEquals("Edited", BextFixtures.text(written)["Description"])
            assertContentEquals(BextFixtures.body(original).copyOfRange(338, 602),
                BextFixtures.body(written).copyOfRange(338, 602))
            for (formats in listOf<Set<TagFormat>?>(null, setOf(TagFormat.RIFF_INFO), emptySet())) {
                val ordinary = AudioKodec.writeTags(original, TagBundle(mapOf(TagField.TITLE to listOf("Ordinary edit"))),
                    TagWriteOptions(formats = formats))
                assertContentEquals(BextFixtures.body(original), BextFixtures.body(ordinary))
            }
        }
    }

    @Test
    fun optInRemovalAndUnknownLoudnessAreExplicit() {
        val audio = BextFixtures.audio()
        val added = AudioKodec.writeTags(audio, BextFixtures.tags(), BextFixtures.options)
        val removed = AudioKodec.writeTags(added, TagBundle.EMPTY, BextFixtures.options)
        assertTrue(AudioKodec.readTags(removed).raw.blocksOf(TagFormat.BWF_BEXT).isEmpty())
        assertContentEquals(audio, removed)
        val versionTwo = AudioKodec.writeTags(audio, BextFixtures.tags(BextFixtures.values + ("Version" to "2")), BextFixtures.options)
        val body = BextFixtures.body(versionTwo)
        for (offset in 412 until 422 step 2) {
            assertEquals(0xFF.toByte(), body[offset])
            assertEquals(0x7F.toByte(), body[offset + 1])
        }
    }

    @Test
    fun broadcastAndMusicMetadataKeepTheirOwnCarriers() {
        val values = BextFixtures.tags()
        val tags = TagBundle(mapOf(TagField.TITLE to listOf("Music title")), raw = values.raw)
        for (carrier in listOf(TagFormat.RIFF_INFO, TagFormat.ID3V2_4)) {
            val bytes = AudioKodec.writeTags(BextFixtures.audio(), tags,
                TagWriteOptions(formats = setOf(TagFormat.BWF_BEXT, carrier)))
            assertEquals("Music title", AudioKodec.readTags(bytes)[TagField.TITLE])
            assertEquals(BextFixtures.values, BextFixtures.text(bytes))
            assertContentEquals(BextFixtures.payload(BextFixtures.audio()), BextFixtures.payload(bytes))
        }
    }

    @Test
    fun rf64RewritesDropTheOldBextSizeTableEntry() {
        val tagged = AudioKodec.writeTags(BextFixtures.audio("RF64"), BextFixtures.tags(), BextFixtures.options)
        val block = AudioKodec.readTags(tagged).raw.blocksOf(TagFormat.BWF_BEXT).single()
        assertEquals(48L, block.offset)
        val sink = ByteArrayAudioSink()
        val writer = SinkWriter(sink)
        sink.write(tagged, 0, 48)
        writer.writeFourCc("bext")
        writer.writeI64Le(block.size - 8)
        sink.write(tagged, 48, tagged.size - 48)
        val placeholder = sink.toByteArray()
        fun put32(at: Int, value: Long) {
            repeat(4) { placeholder[at + it] = (value ushr (8 * it)).toByte() }
        }
        put32(16, 40)
        put32(44, 1)
        put32(64, 0xFFFF_FFFFL)
        repeat(8) { placeholder[20 + it] = ((placeholder.size - 8L) ushr (8 * it)).toByte() }
        assertEquals(BextFixtures.values, BextFixtures.text(placeholder))
        val rewritten = AudioKodec.writeTags(placeholder, AudioKodec.readTags(placeholder), BextFixtures.options)
        assertEquals(0L, BextFixtures.u32(rewritten, 44))
        assertContentEquals(tagged, rewritten)
        assertContentEquals(BextFixtures.payload(placeholder), BextFixtures.payload(rewritten))
    }

    @Test
    fun refusesUnrepresentableMetadataBeforeOutputEvenWhenTheInputHasAnId3Prefix() {
        val prefixed = AudioKodec.writeTags(byteArrayOf(), TagBundle(mapOf(TagField.TITLE to listOf("External")))) +
            BextFixtures.audio()
        val invalid = listOf(BextFixtures.tags(BextFixtures.values + ("Originator" to "日本語")),
            BextFixtures.tags(BextFixtures.values + ("Description" to "null\u0000suffix")),
            BextFixtures.tags(BextFixtures.values + ("TimeReference" to "-1")),
            BextFixtures.tags(BextFixtures.values + ("TimeReference" to "18446744073709551616")),
            BextFixtures.tags(BextFixtures.values + ("CodingHistory" to "A\nB")),
            BextFixtures.tags(BextFixtures.values + ("Version" to "3")),
            BextFixtures.tags(BextFixtures.values + ("Unknown" to "lost")),
            BextFixtures.tags(extension = ByteArray(263)))
        for (tags in invalid) {
            val sink = ByteArrayAudioSink()
            assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags(prefixed.asAudioSource(), tags, sink,
                    TagWriteOptions(formats = setOf(TagFormat.BWF_BEXT, TagFormat.ID3V2_4)))
            }
            assertEquals(0L, sink.position)
        }
        val sink = ByteArrayAudioSink()
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.writeTags(BextFixtures.audio().asAudioSource(),
                TagBundle(mapOf(TagField.TITLE to listOf("Lost title"))), sink, BextFixtures.options)
        }
        assertEquals(0L, sink.position)
        assertFailsWith<AllocationGuardException> {
            AudioKodec.writeTags(BextFixtures.audio().asAudioSource(), BextFixtures.tags(), sink, BextFixtures.options,
                DecodeOptions(maxAllocationBytes = 608))
        }
        assertEquals(0L, sink.position)
    }
}

internal object BextFixtures {
    val options = TagWriteOptions(formats = setOf(TagFormat.BWF_BEXT))
    val values = linkedMapOf("Description" to "Broadcast recording", "Originator" to "Station",
        "OriginatorReference" to "GB-STATION-20261008-001", "OriginationDate" to "2026-10-08",
        "OriginationTime" to "12:34:56", "TimeReference" to "18446744073709551615", "Version" to "0",
        "CodingHistory" to "A=PCM,F=8000,W=16,M=mono,T=Test\r\n")

    fun tags(values: Map<String, String> = this.values, extension: ByteArray? = null): TagBundle =
        TagBundle(emptyMap(), raw = RawTags(listOf(RawTagBlock(TagFormat.BWF_BEXT, 0, 0,
            values.map { RawTagEntry(it.key, text = it.value) } +
                listOfNotNull(extension?.let { RawTagEntry("Extension", binary = it) })))))

    fun audio(id: String = "RIFF"): ByteArray {
        val samples = FloatArray(64) { (it - 32) / 32768f }
        val wave = AudioKodec.encode(samples, AudioFormat(8_000, 1), EncodeTarget.wav(16))
        if (id == "RIFF") return wave
        val info = AudioKodec.probe(wave)
        repeat(4) { wave[info.dataOffset.toInt() - 4 + it] = 0xFF.toByte() }
        val sink = ByteArrayAudioSink()
        val writer = SinkWriter(sink)
        writer.writeFourCc(id)
        writer.writeU32Le(0xFFFF_FFFFL)
        writer.writeFourCc("WAVE")
        writer.writeFourCc("ds64")
        writer.writeU32Le(28)
        writer.writeI64Le(wave.size + 36L - 8)
        writer.writeI64Le(info.dataSize)
        writer.writeI64Le(64)
        writer.writeU32Le(0)
        sink.write(wave, 12, wave.size - 12)
        return sink.toByteArray()
    }

    fun text(bytes: ByteArray): Map<String, String> = AudioKodec.readTags(bytes).raw.blocksOf(TagFormat.BWF_BEXT)
        .single().entries.mapNotNull { entry -> entry.text?.let { entry.key to it } }.toMap()

    fun body(bytes: ByteArray): ByteArray {
        val block = AudioKodec.readTags(bytes).raw.blocksOf(TagFormat.BWF_BEXT).single()
        return bytes.copyOfRange(block.offset.toInt() + 8, (block.offset + block.size).toInt())
    }

    fun payload(bytes: ByteArray): ByteArray {
        val info = AudioKodec.probe(bytes)
        return bytes.copyOfRange(info.dataOffset.toInt(), (info.dataOffset + info.dataSize).toInt())
    }

    fun u32(bytes: ByteArray, at: Int): Long = (0..3).sumOf { (bytes[at + it].toLong() and 255) shl (8 * it) }
    fun i64(bytes: ByteArray, at: Int): Long = (0..7).fold(0L) { value, index ->
        value or ((bytes[at + index].toLong() and 255) shl (8 * index))
    }
}

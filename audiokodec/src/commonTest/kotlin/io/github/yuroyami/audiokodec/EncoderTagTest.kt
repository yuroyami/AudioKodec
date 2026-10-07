package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.internal.tag.TagAssembler
import io.github.yuroyami.audiokodec.tag.RawTagBlock
import io.github.yuroyami.audiokodec.tag.RawTagEntry
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EncoderTagTest {
    @Test
    fun softwareHasTheSameMeaningInEveryId3Version() {
        for (version in 2..4) {
            for (encoding in if (version == 4) 0..3 else 0..1) {
                val tags = AudioKodec.readTags(EncoderTagFixtures.id3(version, encoding))
                assertEquals(EncoderTagFixtures.fields, tags.fields, "ID3v2.$version encoding=$encoding")
            }
        }
    }

    @Test
    fun nativeAliasesKeepSoftwareAndHumanCreditsSeparate() {
        val cases = listOf(
            TagFormat.VORBIS_COMMENT to listOf("ENCODER", "ENCODING", "ENCODEDBY", "ENCODED-BY", "ENCODED_BY", "ENCODERSETTINGS"),
            TagFormat.APEV1 to listOf("ENCODER", "ENCODING", "ENCODEDBY", "ENCODED-BY", "ENCODED_BY", "ENCODERSETTINGS"),
            TagFormat.APEV2 to listOf("ENCODER", "ENCODING", "ENCODEDBY", "ENCODED-BY", "ENCODED_BY", "ENCODERSETTINGS"),
            TagFormat.RIFF_INFO to listOf("ISFT", "ISFT", "IENC", "ITCH"),
        )
        for ((format, keys) in cases) {
            val entries = keys.mapIndexed { index, key ->
                RawTagEntry(key, text = when {
                    index < 2 -> EncoderTagFixtures.software
                    keys.size > 4 && index == keys.lastIndex -> EncoderTagFixtures.settings
                    else -> EncoderTagFixtures.credit
                })
            }
            val tags = TagAssembler.assemble(listOf(RawTagBlock(format, 0, 0, entries)))
            assertEquals(listOf(EncoderTagFixtures.software), tags.all(TagField.ENCODER), format.displayName)
            assertEquals(listOf(EncoderTagFixtures.credit), tags.all(TagField.ENCODED_BY), format.displayName)
            assertEquals(if (keys.size > 4) listOf(EncoderTagFixtures.settings) else emptyList(), tags.all(TagField.ENCODING_SETTINGS))
            assertEquals(entries, tags.raw.blocks.single().entries)
        }
    }

    @Test
    fun versionConversionAndEachFieldEditPreserveTheOtherEncodingFacts() {
        for (input in 2..4) {
            val original = EncoderTagFixtures.id3(input, 1)
            val before = AudioKodec.readTags(original)
            for (output in 3..4) {
                val options = TagWriteOptions(id3Version = output, padding = 0)
                val unchanged = AudioKodec.writeTags(original, before.edit { set(TagField.TITLE, "Changed") }, options)
                val after = AudioKodec.readTags(unchanged)
                for ((field, values) in EncoderTagFixtures.fields) assertEquals(values, after.all(field))
                val entries = after.raw.blocks.single().entries
                assertEquals(listOf(EncoderTagFixtures.software), entries.filter { it.key == "TSSE" }.map { it.text })
                assertEquals(listOf(EncoderTagFixtures.credit), entries.filter { it.key == "TENC" }.map { it.text })
                assertEquals(listOf(EncoderTagFixtures.settings), entries.filter { it.key == "TXXX" && it.description == "ENCODERSETTINGS" }.map { it.text })
                assertContentEquals(EncoderTagFixtures.audio, unchanged.takeLast(EncoderTagFixtures.audio.size).toByteArray())
                for (field in EncoderTagFixtures.fields.keys) {
                    val removed = AudioKodec.writeTags(original, before.edit { remove(field) }, options)
                    assertEquals(EncoderTagFixtures.fields - field, AudioKodec.readTags(removed).fields)
                    val changed = AudioKodec.writeTags(original, before.edit { set(field, "Replacement 日本語") }, options)
                    assertEquals(EncoderTagFixtures.fields + (field to listOf("Replacement 日本語")), AudioKodec.readTags(changed).fields)
                    for (bytes in listOf(removed, changed)) assertContentEquals(EncoderTagFixtures.audio, bytes.takeLast(EncoderTagFixtures.audio.size).toByteArray())
                }
            }
        }
    }

    @Test
    fun mixedAndNativeWritesKeepTheThreeFactsStable() {
        val wav = AudioKodec.encode(FloatArray(64), AudioFormat(8_000, 1), EncodeTarget.wav(16))
        val sources = listOf(EncoderTagFixtures.audio, TagStressFixtures.flac(emptyList()), R128TagFixtures.opus(), wav)
        for (source in sources) {
            val options = when {
                source === EncoderTagFixtures.audio -> TagWriteOptions(formats = setOf(TagFormat.ID3V2_4, TagFormat.APEV2), padding = 0)
                source === wav -> TagWriteOptions(formats = setOf(TagFormat.RIFF_INFO, TagFormat.ID3V2_4), padding = 0)
                else -> TagWriteOptions(padding = 0)
            }
            var bytes = AudioKodec.writeTags(source, TagBundle(EncoderTagFixtures.fields), options)
            repeat(4) { index ->
                val read = AudioKodec.readTags(bytes)
                for ((field, values) in EncoderTagFixtures.fields) assertEquals(values, read.all(field))
                bytes = AudioKodec.writeTags(bytes, read.edit { set(TagField.TITLE, "Edit $index") }, options)
            }
            when {
                source === EncoderTagFixtures.audio -> {
                    val read = AudioKodec.readTags(bytes)
                    val at = read.raw.blocks.first().size.toInt()
                    assertContentEquals(source, bytes.copyOfRange(at, at + source.size))
                }
                source === wav -> assertContentEquals(EncoderTagFixtures.waveAudio(source), EncoderTagFixtures.waveAudio(bytes))
                else -> if (source[0] == 'f'.code.toByte()) {
                    assertContentEquals(TagStressFixtures.flacAudio(source), TagStressFixtures.flacAudio(bytes))
                } else assertContentEquals(R128TagFixtures.audioPacket, bytes.takeLast(R128TagFixtures.audioPacket.size).toByteArray())
            }
        }
        // Native INFO carries software and a human credit, while explicit
        // settings need the ID3 chunk included above.
        val native = AudioKodec.writeTags(wav, TagBundle(EncoderTagFixtures.fields - TagField.ENCODING_SETTINGS))
        assertEquals(EncoderTagFixtures.fields - TagField.ENCODING_SETTINGS, AudioKodec.readTags(native).fields)
        assertContentEquals(EncoderTagFixtures.waveAudio(wav), EncoderTagFixtures.waveAudio(native))
    }

    @Test
    fun separateMultiValuesRemainLosslessOrAreRefusedBeforeOutput() {
        val fields = EncoderTagFixtures.fields.mapValues { (_, values) -> values + "Second 日本語" }
        for ((source, options) in listOf(
            EncoderTagFixtures.audio to TagWriteOptions(id3Version = 4, padding = 0),
            EncoderTagFixtures.audio to TagWriteOptions(formats = setOf(TagFormat.APEV2), padding = 0),
            TagStressFixtures.flac(emptyList()) to TagWriteOptions.DEFAULT,
        )) {
            val bytes = AudioKodec.writeTags(source, TagBundle(fields), options)
            assertEquals(fields, AudioKodec.readTags(bytes).fields)
        }
        for ((field, values) in fields) {
            val sink = ByteArrayAudioSink()
            assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags(EncoderTagFixtures.audio.asAudioSource(), TagBundle(mapOf(field to values)), sink, TagWriteOptions(id3Version = 3))
            }
            assertEquals(0L, sink.position)
        }
        val softwareOnly = AudioKodec.readTags(AudioKodec.writeTags(EncoderTagFixtures.audio, TagBundle(mapOf(TagField.ENCODER to listOf(EncoderTagFixtures.software)))))
        assertNull(softwareOnly[TagField.ENCODED_BY])
        assertNull(softwareOnly[TagField.ENCODING_SETTINGS])
    }
}

/** Independent text-frame layouts, including the older three-letter keys. */
internal object EncoderTagFixtures {
    const val software = "Codec 1.2 -quality 5"
    const val credit = "Some Person"
    const val settings = "quality=5"
    val audio = ByteArray(32) { 0x55 }
    val fields: Map<TagField, List<String>> get() = mapOf(
        TagField.ENCODER to listOf(software),
        TagField.ENCODED_BY to listOf(credit),
        TagField.ENCODING_SETTINGS to listOf(settings),
    )

    fun waveAudio(bytes: ByteArray): ByteArray {
        var at = 12
        while (at + 8 <= bytes.size) {
            val size = (0..3).fold(0) { value, index -> value or ((bytes[at + 4 + index].toInt() and 255) shl (index * 8)) }
            if (bytes.copyOfRange(at, at + 4).decodeToString() == "data") return bytes.copyOfRange(at + 8, at + 8 + size)
            at += 8 + size + size % 2
        }
        error("fixture has no WAVE data")
    }

    fun id3(version: Int, encoding: Int = 0): ByteArray {
        fun text(value: String): ByteArray = when (encoding) {
            0 -> ByteArray(value.length) { value[it].code.toByte() }
            1 -> byteArrayOf(-1, -2) + ByteArray(value.length * 2) {
                (value[it / 2].code ushr if (it % 2 == 0) 0 else 8).toByte()
            }
            2 -> ByteArray(value.length * 2) {
                (value[it / 2].code ushr if (it % 2 == 0) 8 else 0).toByte()
            }
            else -> value.encodeToByteArray()
        }
        fun syncsafe(value: Int) = ByteArray(4) { ((value ushr (21 - it * 7)) and 127).toByte() }
        fun frame(key: String, body: ByteArray): ByteArray {
            val size = when (version) {
                2 -> ByteArray(3) { (body.size ushr (16 - it * 8)).toByte() }
                3 -> ByteArray(4) { (body.size ushr (24 - it * 8)).toByte() } + byteArrayOf(0, 0)
                else -> syncsafe(body.size) + byteArrayOf(0, 0)
            }
            return key.encodeToByteArray() + size + body
        }
        val terminator = ByteArray(if (encoding == 1 || encoding == 2) 2 else 1)
        val frames = frame(if (version == 2) "TSS" else "TSSE", byteArrayOf(encoding.toByte()) + text(software)) +
            frame(if (version == 2) "TEN" else "TENC", byteArrayOf(encoding.toByte()) + text(credit)) +
            frame(if (version == 2) "TXX" else "TXXX", byteArrayOf(encoding.toByte()) + text("ENCODERSETTINGS") + terminator + text(settings))
        return "ID3".encodeToByteArray() + byteArrayOf(version.toByte(), 0, 0) + syncsafe(frames.size) + frames + audio
    }
}

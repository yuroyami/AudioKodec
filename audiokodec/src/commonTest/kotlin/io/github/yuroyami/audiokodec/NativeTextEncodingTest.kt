package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeTextEncodingTest {
    @Test
    fun undeclaredUtf8ReadsInEveryNativeContainer() {
        for (base in NativeTextEncodingFixtures.sources()) {
            val bytes = NativeTextEncodingFixtures.text(base, NativeTextEncodingFixtures.title.encodeToByteArray())
            assertEquals(NativeTextEncodingFixtures.title, AudioKodec.readTags(bytes)[TagField.TITLE])
        }
    }

    @Test
    fun csetAppliesBeforeAndAfterInfoAndOverridesUtf8Guessing() {
        for (base in listOf(NativeTextListFixtures.wave(), NativeTextListFixtures.rf64())) {
            for (after in listOf(false, true)) {
                for (codePage in listOf(0, 28591)) {
                    val value = "Björk Ã¶"
                    val bytes = NativeTextEncodingFixtures.text(base, value.map { it.code.toByte() }.toByteArray(), codePage, after)
                    assertEquals(value, AudioKodec.readTags(bytes)[TagField.TITLE])
                }
                val value = "€ “quoted”"
                val bytes = NativeTextEncodingFixtures.text(base, byteArrayOf(0x80.toByte(), 32, 0x93.toByte()) + "quoted".encodeToByteArray() + byteArrayOf(0x94.toByte()), 1252, after)
                assertEquals(value, AudioKodec.readTags(bytes)[TagField.TITLE])
            }
        }
    }

    @Test
    fun nativeWaveOnlyCanWriteUnicodeWithoutReplacement() {
        for (base in listOf(NativeTextListFixtures.wave(), NativeTextListFixtures.rf64())) {
            val expected = mapOf(TagField.TITLE to listOf(NativeTextEncodingFixtures.title))
            val written = AudioKodec.writeTags(base, TagBundle(expected), TagWriteOptions(formats = setOf(TagFormat.RIFF_INFO)))
            assertEquals(expected, AudioKodec.readTags(written).fields)
            assertContentEquals(NativeTextListFixtures.payload(base), NativeTextListFixtures.payload(written))
        }
    }
    @Test
    fun legacyLatin1FallsBackAndIsRewrittenAsUtf8WithoutADeclaration() {
        for (base in NativeTextEncodingFixtures.sources()) {
            val source = NativeTextEncodingFixtures.text(base, byteArrayOf(0xF6.toByte()))
            assertEquals("ö", AudioKodec.readTags(source)[TagField.TITLE])
            val written = AudioKodec.writeTags(source, AudioKodec.readTags(source))
            assertEquals("ö", AudioKodec.readTags(written)[TagField.TITLE])
            assertContentEquals(NativeTextListFixtures.payload(base), NativeTextListFixtures.payload(written))
        }
    }

    @Test
    fun declaredEncodingAndUnknownInfoSurviveRepeatedEdits() {
        for (base in listOf(NativeTextListFixtures.wave(), NativeTextListFixtures.rf64())) {
            for (codePage in listOf<Int?>(null, 65001, 0, 28591, 1252)) {
                val value = if (codePage == null || codePage == 65001) NativeTextEncodingFixtures.title else "Björk Ã¶"
                val encoded = if (codePage == null || codePage == 65001) value.encodeToByteArray() else value.map { it.code.toByte() }.toByteArray()
                var source = NativeTextEncodingFixtures.text(base, encoded, codePage, after = true, key = "IZZZ")
                val originalCset = NativeTextListFixtures.chunks(source).filter { it.first == "CSET" }
                repeat(3) { edit ->
                    source = AudioKodec.writeTags(source, AudioKodec.readTags(source).edit { set(TagField.ARTIST, "Edit $edit") })
                    val read = AudioKodec.readTags(source)
                    assertEquals(value, read.raw.blocksOf(TagFormat.RIFF_INFO).flatMap { it.entries }.single { it.key == "IZZZ" }.text)
                    assertEquals(originalCset.map { it.first }, NativeTextListFixtures.chunks(source).filter { it.first == "CSET" }.map { it.first })
                    if (codePage != null) assertContentEquals(originalCset.single().second, NativeTextListFixtures.chunks(source).single { it.first == "CSET" }.second)
                    assertContentEquals(NativeTextListFixtures.payload(base), NativeTextListFixtures.payload(source))
                    assertEquals(source.size, AudioKodec.writeTags(source, read).size)
                }
            }
        }
    }

    @Test
    fun declaredUnicodeCanWriteNativeOnlyAndLegacyDeclarationsUseId3ForUnicode() {
        val fields = mapOf(TagField.TITLE to listOf(NativeTextEncodingFixtures.title))
        for (base in listOf(NativeTextListFixtures.wave(), NativeTextListFixtures.rf64())) {
            for (codePage in listOf(0, 28591, 1252, 65001)) {
                val source = NativeTextListFixtures.append(base, listOf(NativeTextEncodingFixtures.cset(codePage)))
                val written = AudioKodec.writeTags(source, TagBundle(fields))
                val read = AudioKodec.readTags(written)
                assertEquals(fields, read.fields)
                assertEquals(codePage == 65001, read.raw.blocksOf(TagFormat.ID3V2_4).isEmpty())
                assertContentEquals(NativeTextListFixtures.chunks(source).single { it.first == "CSET" }.second,
                    NativeTextListFixtures.chunks(written).single { it.first == "CSET" }.second)
                assertContentEquals(NativeTextListFixtures.payload(base), NativeTextListFixtures.payload(written))
                val sink = ByteArrayAudioSink()
                if (codePage == 65001) {
                    AudioKodec.writeTags(source.asAudioSource(), TagBundle(fields), sink, TagWriteOptions(formats = setOf(TagFormat.RIFF_INFO)))
                    assertEquals(fields, AudioKodec.readTags(sink.toByteArray()).fields)
                } else {
                    val error = assertFailsWith<UnsupportedFeatureException> {
                        AudioKodec.writeTags(source.asAudioSource(), TagBundle(fields), sink, TagWriteOptions(formats = setOf(TagFormat.RIFF_INFO)))
                    }
                    assertTrue(error.message.orEmpty().contains("TITLE"))
                    assertEquals(0L, sink.position)
                }
            }
        }
    }

    @Test
    fun aiffNonAsciiUsesId3AndNativeOnlyRefusesBeforeOutput() {
        for (base in listOf(NativeTextListFixtures.aiff(), NativeTextListFixtures.aiff(EncodeTarget.aiffFloat()))) {
            for (value in listOf("Björk", "Ã¶", NativeTextEncodingFixtures.title)) {
                val fields = mapOf(TagField.TITLE to listOf(value))
                val written = AudioKodec.writeTags(base, TagBundle(fields))
                assertEquals(fields, AudioKodec.readTags(written).fields)
                assertTrue(AudioKodec.readTags(written).raw.blocksOf(TagFormat.AIFF_TEXT).isEmpty())
                assertContentEquals(NativeTextListFixtures.payload(base), NativeTextListFixtures.payload(written))
                val sink = ByteArrayAudioSink()
                assertFailsWith<UnsupportedFeatureException> {
                    AudioKodec.writeTags(base.asAudioSource(), TagBundle(fields), sink, TagWriteOptions(formats = setOf(TagFormat.AIFF_TEXT)))
                }
                assertEquals(0L, sink.position)
            }
        }
    }

    @Test
    fun malformedOrConflictingDeclarationsAndDeclaredTextAreTypedFailures() {
        val base = NativeTextListFixtures.wave()
        for (body in listOf(ByteArray(0), ByteArray(2), ByteArray(7), ByteArray(9))) {
            val source = NativeTextListFixtures.append(base, listOf(NativeTextListFixtures.chunk("CSET", body, false)))
            assertFailsWith<CorruptStreamException> { AudioKodec.readTags(source) }
        }
        val duplicate = NativeTextListFixtures.append(base, List(2) { NativeTextEncodingFixtures.cset(65001) })
        assertTrue(AudioKodec.readTags(duplicate).fields.isEmpty())
        val conflict = NativeTextListFixtures.append(base, listOf(NativeTextEncodingFixtures.cset(65001), NativeTextEncodingFixtures.cset(1252)))
        assertFailsWith<CorruptStreamException> { AudioKodec.readTags(conflict) }
        for (value in listOf(byteArrayOf(0xC0.toByte(), 0xAF.toByte()), byteArrayOf(0xF0.toByte(), 0x9F.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()))) {
            assertFailsWith<CorruptStreamException> { AudioKodec.readTags(NativeTextEncodingFixtures.text(base, value, 65001)) }
        }
        for (byte in listOf(0x81, 0x8D, 0x8F, 0x90, 0x9D)) {
            assertFailsWith<CorruptStreamException> { AudioKodec.readTags(NativeTextEncodingFixtures.text(base, byteArrayOf(byte.toByte()), 1252)) }
        }
    }

    @Test
    fun unsupportedDeclaredCodePagesAreNamedOnReadAndWrite() {
        val base = NativeTextListFixtures.wave()
        val error = assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.readTags(NativeTextEncodingFixtures.text(base, "test".encodeToByteArray(), 932))
        }
        assertTrue(error.message.orEmpty().contains("932"))
        val source = NativeTextListFixtures.append(base, listOf(NativeTextEncodingFixtures.cset(932)))
        val sink = ByteArrayAudioSink()
        val write = assertFailsWith<UnsupportedFeatureException> {
            AudioKodec.writeTags(source.asAudioSource(), TagBundle(mapOf(TagField.TITLE to listOf("test"))), sink)
        }
        assertTrue(write.message.orEmpty().contains("932"))
        assertEquals(0L, sink.position)
        val stripped = AudioKodec.writeTags(source, TagBundle.EMPTY, TagWriteOptions(formats = emptySet()))
        assertContentEquals(NativeTextListFixtures.payload(base), NativeTextListFixtures.payload(stripped))
    }

    @Test
    fun unknownInfoCannotBeReplacedEvenWhenAnExternalId3PrefixWouldBeWritten() {
        val base = NativeTextListFixtures.append(NativeTextListFixtures.wave(), listOf(NativeTextEncodingFixtures.cset(0)))
        val unicode = AudioKodec.readTags(NativeTextEncodingFixtures.text(NativeTextListFixtures.wave(),
            NativeTextEncodingFixtures.title.encodeToByteArray(), key = "IZZZ"))
        val fields = mapOf(TagField.TITLE to listOf("prefix"))
        val tagged = AudioKodec.writeTags(ByteArray(32) { 0x55 }, TagBundle(fields), TagWriteOptions(padding = 0))
        val size = 10 + (0..3).fold(0) { value, index -> (value shl 7) or (tagged[6 + index].toInt() and 127) }
        for (source in listOf(base, tagged.copyOf(size) + base)) {
            val sink = ByteArrayAudioSink()
            val error = assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags(source.asAudioSource(), TagBundle(fields, raw = unicode.raw), sink)
            }
            assertTrue(error.message.orEmpty().contains("IZZZ"))
            assertEquals(0L, sink.position)
        }
    }

    @Test
    fun unknownSizeSourcesUseTheSameCharsetAndPreserveOpaqueChunks() {
        val base = NativeTextListFixtures.rf64()
        val source = NativeTextListFixtures.append(NativeTextEncodingFixtures.text(base, NativeTextEncodingFixtures.title.encodeToByteArray(), 65001, true),
            listOf(NativeTextListFixtures.chunk("zzzz", byteArrayOf(1, 2, 3), false)))
        val delegate = source.asAudioSource()
        val unknown = object : AudioSource {
            override val size: Long = -1
            override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int = delegate.readAt(position, into, offset, count)
            override fun close() = Unit
        }
        val tags = AudioKodec.readTags(unknown)
        assertEquals(NativeTextEncodingFixtures.title, tags[TagField.TITLE])
        val sink = ByteArrayAudioSink()
        AudioKodec.writeTags(unknown, tags, sink)
        assertEquals(tags.fields, AudioKodec.readTags(sink.toByteArray()).fields)
        assertContentEquals(byteArrayOf(1, 2, 3), NativeTextListFixtures.chunks(sink.toByteArray()).single { it.first == "zzzz" }.second)
        assertContentEquals(NativeTextListFixtures.payload(base), NativeTextListFixtures.payload(sink.toByteArray()))
    }

    @Test
    fun freshEncodingKeepsUnicodeWithExactAudioAndNoQuestionMarks() {
        val fields = mapOf(TagField.TITLE to listOf(NativeTextEncodingFixtures.title))
        for (target in listOf(EncodeTarget.wav(), EncodeTarget.aiff(), EncodeTarget.aiffFloat())) {
            val base = AudioKodec.encode(NativeTextListFixtures.samples, NativeTextListFixtures.format, target)
            val written = AudioKodec.encode(NativeTextListFixtures.samples, NativeTextListFixtures.format, target, TagBundle(fields))
            assertEquals(fields, AudioKodec.readTags(written).fields)
            assertContentEquals(NativeTextListFixtures.payload(base), NativeTextListFixtures.payload(written))
            val native = if (target.container == AudioContainer.WAV) TagFormat.RIFF_INFO else TagFormat.AIFF_TEXT
            val entries = AudioKodec.readTags(written).raw.blocksOf(native).flatMap { it.entries }
            if (native == TagFormat.RIFF_INFO) assertEquals(listOf(NativeTextEncodingFixtures.title), entries.map { it.text })
            else assertTrue(entries.isEmpty())
        }
    }

}

internal object NativeTextEncodingFixtures {
    const val title = "Björk 日本語 🎵"
    fun sources(): List<ByteArray> = listOf(NativeTextListFixtures.wave(), NativeTextListFixtures.rf64(),
        NativeTextListFixtures.aiff(), NativeTextListFixtures.aiff(EncodeTarget.aiffFloat()))
    fun cset(codePage: Int): ByteArray = NativeTextListFixtures.chunk("CSET", byteArrayOf(codePage.toByte(), (codePage ushr 8).toByte(), 33, 0, 1, 0, 2, 0), false)
    fun text(base: ByteArray, value: ByteArray, codePage: Int? = null, after: Boolean = false, key: String = "INAM"): ByteArray {
        val big = base[0] == 'F'.code.toByte()
        val text = if (big) NativeTextListFixtures.chunk("NAME", value, true) else {
            NativeTextListFixtures.chunk("LIST", "INFO".encodeToByteArray() + NativeTextListFixtures.chunk(key, value + byteArrayOf(0), false), false)
        }
        val parts = if (codePage == null) listOf(text) else if (after) listOf(text, cset(codePage)) else listOf(cset(codePage), text)
        return NativeTextListFixtures.append(base, parts)
    }
}

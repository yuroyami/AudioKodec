package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.tag.ApeTag
import io.github.yuroyami.audiokodec.tag.PictureType
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagPicture
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeTextListTest {
    @Test
    fun riffCommentsSurviveAnUnrelatedEdit() {
        for (source in listOf(NativeTextListFixtures.wave(), NativeTextListFixtures.rf64())) {
            checkComments(source)
        }
    }

    @Test
    fun aiffCommentsSurviveAnUnrelatedEdit() {
        for (target in listOf(EncodeTarget.aiff(), EncodeTarget.aiffFloat())) {
            checkComments(NativeTextListFixtures.aiff(target))
        }
    }

    private fun checkComments(source: ByteArray) {
        var bytes = NativeTextListFixtures.withComments(source)
        val payload = NativeTextListFixtures.payload(bytes)
        repeat(4) { index ->
            val before = AudioKodec.readTags(bytes)
            assertEquals(NativeTextListFixtures.comments, before.all(TagField.COMMENT))
            bytes = AudioKodec.writeTags(bytes, before.edit { set(TagField.TITLE, "Edit $index") })
            assertEquals(NativeTextListFixtures.comments, AudioKodec.readTags(bytes).all(TagField.COMMENT))
            assertContentEquals(payload, NativeTextListFixtures.payload(bytes))
        }
    }

    @Test
    fun modelledRiffFieldsKeepEveryNonEmptyValueInOrder() {
        val fields = listOf(TagField.TITLE, TagField.ARTIST, TagField.ALBUM, TagField.COPYRIGHT,
            TagField.COMMENT, TagField.ENCODER, TagField.ENCODED_BY)
        for (source in listOf(NativeTextListFixtures.wave(), NativeTextListFixtures.rf64())) {
            val expected = fields.associateWith { listOf("first", "second value", "third") }
            val written = AudioKodec.writeTags(source, TagBundle(expected))
            assertEquals(expected, AudioKodec.readTags(written).fields)
            assertContentEquals(NativeTextListFixtures.payload(source), NativeTextListFixtures.payload(written))
            assertEquals(21, AudioKodec.readTags(written).raw.blocksOf(TagFormat.RIFF_INFO).single().entries.size)
        }
    }

    @Test
    fun freshEncodingAndExplicitSelectionsKeepAllNativeComments() {
        for (target in listOf(EncodeTarget.wav(), EncodeTarget.aiff(), EncodeTarget.aiffFloat(), EncodeTarget.aiffMuLaw())) {
            val tags = TagBundle(mapOf(TagField.COMMENT to NativeTextListFixtures.comments))
            val original = AudioKodec.encode(NativeTextListFixtures.samples, NativeTextListFixtures.format, target)
            val encoded = AudioKodec.encode(NativeTextListFixtures.samples, NativeTextListFixtures.format, target, tags)
            assertEquals(NativeTextListFixtures.comments, AudioKodec.readTags(encoded).all(TagField.COMMENT))
            assertContentEquals(NativeTextListFixtures.payload(original), NativeTextListFixtures.payload(encoded))
            val native = if (target.container == AudioContainer.WAV) TagFormat.RIFF_INFO else TagFormat.AIFF_TEXT
            for (formats in listOf(setOf(native), setOf(native, TagFormat.ID3V2_3), setOf(native, TagFormat.ID3V2_4))) {
                val written = AudioKodec.writeTags(original, tags, TagWriteOptions(formats = formats, padding = 0))
                val read = AudioKodec.readTags(written)
                assertEquals(NativeTextListFixtures.comments, read.all(TagField.COMMENT))
                assertEquals(NativeTextListFixtures.comments, read.raw.blocksOf(native).flatMap { it.entries }.mapNotNull { it.text })
                assertContentEquals(NativeTextListFixtures.payload(original), NativeTextListFixtures.payload(written))
            }
        }
    }

    @Test
    fun aiffSingletonListsUseId3WithoutRepeatingTheNativeChunk() {
        for ((field, key) in listOf(TagField.TITLE to "NAME", TagField.ARTIST to "AUTH", TagField.COPYRIGHT to "(c) ")) {
            val fields = mapOf(field to listOf("first", "second", "third"))
            val source = NativeTextListFixtures.aiff()
            for (options in listOf(TagWriteOptions.DEFAULT, TagWriteOptions(formats = setOf(TagFormat.AIFF_TEXT, TagFormat.ID3V2_4), padding = 0))) {
                var written = AudioKodec.writeTags(source, TagBundle(fields), options)
                repeat(3) {
                    val read = AudioKodec.readTags(written)
                    assertEquals(fields, read.fields)
                    assertEquals(listOf("first"), read.raw.blocksOf(TagFormat.AIFF_TEXT).flatMap { it.entries }.filter { it.key == key }.map { it.text })
                    assertEquals(1, read.raw.blocksOf(TagFormat.ID3V2_4).size)
                    assertContentEquals(NativeTextListFixtures.payload(source), NativeTextListFixtures.payload(written))
                    val size = written.size
                    written = AudioKodec.writeTags(written, read, options)
                    assertEquals(size, written.size)
                }
            }
            val encoded = AudioKodec.encode(NativeTextListFixtures.samples, NativeTextListFixtures.format, EncodeTarget.aiff(), TagBundle(fields))
            assertEquals(fields, AudioKodec.readTags(encoded).fields)
            assertEquals(1, NativeTextListFixtures.chunks(encoded).count { it.first == key })
        }
    }

    @Test
    fun unrepresentableNativeSingletonListsFailBeforeOutput() {
        for (field in listOf(TagField.TITLE, TagField.ARTIST, TagField.COPYRIGHT)) {
            val sink = ByteArrayAudioSink()
            val error = assertFailsWith<UnsupportedFeatureException> {
                AudioKodec.writeTags(NativeTextListFixtures.aiff().asAudioSource(), TagBundle(mapOf(field to listOf("first", "second"))),
                    sink, TagWriteOptions(formats = setOf(TagFormat.AIFF_TEXT)))
            }
            assertEquals(0L, sink.position)
            assertTrue(error.message.orEmpty().contains(field.name))
            if (field != TagField.ARTIST) {
                val old = ByteArrayAudioSink()
                assertFailsWith<UnsupportedFeatureException> {
                    AudioKodec.writeTags(NativeTextListFixtures.aiff().asAudioSource(), TagBundle(mapOf(field to listOf("first", "second"))),
                        old, TagWriteOptions(id3Version = 3))
                }
                assertEquals(0L, old.position)
            }
        }
    }

    @Test
    fun singletonFallbackUsesExistingId3CarriersAndNormalisesDuplicateNativeChunks() {
        val fields = mapOf(TagField.TITLE to listOf("first", "second", "third"))
        val tags = TagBundle(fields)
        val base = NativeTextListFixtures.append(NativeTextListFixtures.aiff(), fields.getValue(TagField.TITLE).map {
            NativeTextListFixtures.chunk("NAME", it.encodeToByteArray(), true)
        })
        val tagged = AudioKodec.writeTags(ByteArray(32) { 0x55 }, tags, TagWriteOptions(padding = 0))
        val tagSize = 10 + (0..3).fold(0) { value, index -> (value shl 7) or (tagged[6 + index].toInt() and 127) }
        val id3 = tagged.copyOf(tagSize)
        val inputs = listOf(base, id3 + base, base + id3,
            NativeTextListFixtures.append(base, listOf(NativeTextListFixtures.chunk("ID3 ", id3, true))))
        for (source in inputs) {
            assertEquals(fields, AudioKodec.readTags(source).fields)
            val written = AudioKodec.writeTags(source, AudioKodec.readTags(source))
            val read = AudioKodec.readTags(written)
            assertEquals(fields, read.fields)
            assertEquals(1, read.raw.blocksOf(TagFormat.ID3V2_4).size)
            assertEquals(listOf("first"), read.raw.blocksOf(TagFormat.AIFF_TEXT).flatMap { it.entries }.map { it.text })
            assertContentEquals(NativeTextListFixtures.payload(base), NativeTextListFixtures.payload(NativeTextListFixtures.containerOnly(written)))
            assertEquals(written.size, AudioKodec.writeTags(written, read).size)
        }
        for (source in listOf(inputs.first(), inputs.last())) {
            val delegate = source.asAudioSource()
            val unknown = object : AudioSource {
                override val size: Long = -1
                override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int =
                    delegate.readAt(position, into, offset, count)
                override fun close() = Unit
            }
            val sink = ByteArrayAudioSink()
            AudioKodec.writeTags(unknown, tags, sink)
            assertEquals(fields, AudioKodec.readTags(sink.toByteArray()).fields)
            assertContentEquals(NativeTextListFixtures.payload(base), NativeTextListFixtures.payload(sink.toByteArray()))
        }
    }

    @Test
    fun identicalSingletonValuesNeedNoId3Fallback() {
        val source = NativeTextListFixtures.aiff()
        for (field in listOf(TagField.TITLE, TagField.ARTIST, TagField.COPYRIGHT)) {
            val written = AudioKodec.writeTags(source, TagBundle(mapOf(field to listOf("", "first", "first", ""))),
                TagWriteOptions(formats = setOf(TagFormat.AIFF_TEXT)))
            val read = AudioKodec.readTags(written)
            assertEquals(listOf("first"), read.all(field))
            assertTrue(read.raw.blocksOf(TagFormat.ID3V2_4).isEmpty())
            assertEquals(1, read.raw.blocksOf(TagFormat.AIFF_TEXT).flatMap { it.entries }.size)
            assertContentEquals(NativeTextListFixtures.payload(source), NativeTextListFixtures.payload(written))
        }
    }

    @Test
    fun singletonFallbackAlsoKeepsArtworkBesideAnExistingApeTag() {
        val first = TagPicture(PictureType.FRONT_COVER, "image/jpeg", bytes = byteArrayOf(-1, -40, 0, 1))
        val second = TagPicture(PictureType.FRONT_COVER, "image/jpeg", bytes = byteArrayOf(-1, -40, 0, 2))
        val base = NativeTextListFixtures.aiff()
        val source = base + ApeTag.build(TagBundle(mapOf(TagField.TITLE to listOf("Before")), pictures = listOf(first)))
        val tags = AudioKodec.readTags(source).edit {
            set(TagField.TITLE, listOf("first", "second", "third"))
            addPicture(second)
        }
        var written = AudioKodec.writeTags(source, tags)
        repeat(3) {
            val read = AudioKodec.readTags(written)
            assertEquals(listOf("first", "second", "third"), read.all(TagField.TITLE))
            assertEquals(2, read.pictures.size)
            assertContentEquals(first.bytes, read.pictures[0].bytes)
            assertContentEquals(second.bytes, read.pictures[1].bytes)
            assertEquals(1, read.raw.blocksOf(TagFormat.ID3V2_4).size)
            assertEquals(1, read.raw.blocksOf(TagFormat.APEV2).size)
            assertContentEquals(NativeTextListFixtures.payload(base), NativeTextListFixtures.payload(NativeTextListFixtures.containerOnly(written)))
            val size = written.size
            written = AudioKodec.writeTags(written, read)
            assertEquals(size, written.size)
        }
    }

    @Test
    fun anEmptyFirstValueDoesNotHideLaterNativeValues() {
        val values = listOf("", "first", "", "second")
        for (source in listOf(NativeTextListFixtures.wave(), NativeTextListFixtures.aiff())) {
            val written = AudioKodec.writeTags(source, TagBundle(mapOf(TagField.COMMENT to values)))
            assertEquals(listOf("first", "second"), AudioKodec.readTags(written).all(TagField.COMMENT))
        }
        val source = NativeTextListFixtures.aiff()
        val written = AudioKodec.writeTags(source, TagBundle(mapOf(TagField.TITLE to values)))
        assertEquals(listOf("first", "second"), AudioKodec.readTags(written).all(TagField.TITLE))
    }
}

/** Independent native text chunks, with odd/even sizes and split INFO lists. */
internal object NativeTextListFixtures {
    val comments = listOf("first note", "second note", "odd")
    val samples = FloatArray(32) { (it % 8 - 4) / 8f }
    val format = AudioFormat(8_000, 1)
    fun wave(): ByteArray = AudioKodec.encode(samples, format, EncodeTarget.wav())
    fun aiff(target: EncodeTarget = EncodeTarget.aiff()): ByteArray = AudioKodec.encode(samples, format, target)

    fun withComments(source: ByteArray): ByteArray {
        val big = source.copyOfRange(0, 4).decodeToString() == "FORM"
        val parts = if (big) comments.map { chunk("ANNO", it.encodeToByteArray(), true) } else {
            comments.map { chunk("LIST", "INFO".encodeToByteArray() + chunk("ICMT", it.encodeToByteArray() + byteArrayOf(0), false), false) }
        }
        return append(source, parts)
    }

    fun append(source: ByteArray, parts: List<ByteArray>): ByteArray {
        var bytes = source
        for (part in parts) bytes += part
        if (bytes.copyOfRange(0, 4).decodeToString() == "RF64") {
            u64(bytes, 20, bytes.size - 8L)
        } else u32(bytes, 4, bytes.size - 8L, bytes[0] == 'F'.code.toByte())
        return bytes
    }

    fun chunk(key: String, body: ByteArray, big: Boolean): ByteArray = ByteArray(8 + body.size + body.size % 2).also {
        key.encodeToByteArray().copyInto(it)
        u32(it, 4, body.size.toLong(), big)
        body.copyInto(it, 8)
    }

    fun chunks(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val big = bytes[0] == 'F'.code.toByte()
        val result = ArrayList<Pair<String, ByteArray>>()
        var at = 12
        while (at + 8 <= bytes.size) {
            val key = bytes.copyOfRange(at, at + 4).decodeToString()
            val stated = readU32(bytes, at + 4, big)
            val size = if (stated == 0xFFFF_FFFFL && key == "data") readU32(bytes, 28, false).toInt() else stated.toInt()
            result.add(key to bytes.copyOfRange(at + 8, at + 8 + size))
            at += 8 + size + size % 2
        }
        assertEquals(bytes.size, at, "chunk walk must end exactly at the form boundary")
        return result
    }

    fun payload(bytes: ByteArray): ByteArray = chunks(bytes).single { it.first == "data" || it.first == "SSND" }.second

    fun containerOnly(bytes: ByteArray): ByteArray {
        val at = if (bytes.copyOfRange(0, 3).decodeToString() == "ID3") {
            10 + (0..3).fold(0) { value, index -> (value shl 7) or (bytes[6 + index].toInt() and 127) }
        } else 0
        val size = readU32(bytes, at + 4, true).toInt()
        return bytes.copyOfRange(at, at + 8 + size)
    }

    fun rf64(): ByteArray {
        val wave = wave()
        val data = payload(wave)
        val ds64 = ByteArray(28).also { u64(it, 8, data.size.toLong()); u64(it, 16, samples.size.toLong()) }
        val bytes = "RF64".encodeToByteArray() + ByteArray(4) { -1 } + "WAVE".encodeToByteArray() + chunk("ds64", ds64, false) + wave.copyOfRange(12, wave.size)
        u64(bytes, 20, bytes.size - 8L)
        var at = 48
        while (at + 8 <= bytes.size) {
            val key = bytes.copyOfRange(at, at + 4).decodeToString()
            if (key == "data") { u32(bytes, at + 4, 0xFFFF_FFFFL, false); break }
            val size = readU32(bytes, at + 4, false).toInt()
            at += 8 + size + size % 2
        }
        return bytes
    }

    private fun readU32(bytes: ByteArray, at: Int, big: Boolean): Long = (0..3).fold(0L) { value, index ->
        value or ((bytes[at + index].toLong() and 255) shl ((if (big) 3 - index else index) * 8))
    }
    private fun u32(bytes: ByteArray, at: Int, value: Long, big: Boolean) {
        for (index in 0..3) bytes[at + index] = (value ushr ((if (big) 3 - index else index) * 8)).toByte()
    }
    private fun u64(bytes: ByteArray, at: Int, value: Long) {
        for (index in 0..7) bytes[at + index] = (value ushr (index * 8)).toByte()
    }
}

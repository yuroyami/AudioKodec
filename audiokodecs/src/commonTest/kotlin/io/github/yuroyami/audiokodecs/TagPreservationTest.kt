package io.github.yuroyami.audiokodecs

import io.github.yuroyami.audiokodecs.tag.ReplayGain
import io.github.yuroyami.audiokodecs.tag.TagBundle
import io.github.yuroyami.audiokodecs.tag.TagChapter
import io.github.yuroyami.audiokodecs.tag.TagField
import io.github.yuroyami.audiokodecs.tag.TagFormat
import io.github.yuroyami.audiokodecs.tag.TagPicture
import io.github.yuroyami.audiokodecs.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tag writes that must keep what the file meant, not only what the merged
 * view shows.
 *
 * Each case here is a write that once succeeded and quietly changed the
 * meaning of an entry: a URL nobody could read back, a comment that lost its
 * language, a loudness value relabelled to another reference.
 */
class TagPreservationTest {

    private val stream = ByteArray(32) { 0x55 }

    @Test
    fun urlFramesHoldTheUrlWithNoEncodingByte() {
        val urls = listOf("https://example.com/artist", "https://example.org/other")
        val tags = TagBundle(mapOf(TagField.URL to urls))
        for (version in listOf(3, 4)) {
            val bytes = AudioKodecs.writeTags(stream, tags, TagWriteOptions(id3Version = version))
            val woar = bytes.indexOfBytes("WOAR".encodeToByteArray())
            // The body begins directly after the ten-byte frame header.
            assertEquals('h'.code.toByte(), bytes[woar + 10], "ID3v2.$version URL body")
            val read = AudioKodecs.readTags(bytes)
            assertEquals(urls, read.all(TagField.URL), "ID3v2.$version")
        }
    }

    @Test
    fun replayGainValuesComeFromTheHighestPrecedenceBlockThatStatesThem() {
        val id3 = AudioKodecs.writeTags(
            stream,
            TagBundle(emptyMap(), replayGain = ReplayGain(trackGainDb = -3.0, trackPeak = 0.9)),
            TagWriteOptions(padding = 0),
        )
        val ape = AudioKodecs.writeTags(
            byteArrayOf(),
            TagBundle(
                emptyMap(),
                replayGain = ReplayGain(trackGainDb = -12.0, trackPeak = 0.2, albumGainDb = -9.0, albumPeak = 0.5),
            ),
            TagWriteOptions(formats = setOf(TagFormat.APEV2)),
        )
        val gain = assertNotNull(AudioKodecs.readTags(id3 + ape).replayGain)
        // ID3v2 outranks APE for the values it states, and APE fills the rest.
        assertEquals(-3.0, gain.trackGainDb!!, 0.005)
        assertEquals(0.9, gain.trackPeak!!, 0.0005)
        assertEquals(-9.0, gain.albumGainDb!!, 0.005)
        assertEquals(0.5, gain.albumPeak!!, 0.0005)

        // Within one block, the first statement of a value is the one kept.
        val duplicated = ApeBytes(
            listOf(
                "REPLAYGAIN_TRACK_GAIN" to "-1.5 dB".encodeToByteArray(),
                "REPLAYGAIN_TRACK_GAIN" to "-8.0 dB".encodeToByteArray(),
            ),
        )
        assertEquals(-1.5, AudioKodecs.readTags(duplicated).replayGain!!.trackGainDb!!, 0.005)
    }

    @Test
    fun gainsFoundAgainstDifferentReferencesAreStatedAgainstOne() {
        val block = ApeBytes(
            listOf(
                "R128_TRACK_GAIN" to "-1536".encodeToByteArray(),
                "REPLAYGAIN_ALBUM_GAIN" to "-4 dB".encodeToByteArray(),
            ),
        )
        val gain = assertNotNull(AudioKodecs.readTags(block).replayGain)
        // -6 dB against -23 LUFS is -1 dB against -18 LUFS.
        assertFalse(gain.isR128)
        assertEquals(-1.0, gain.trackGainDb!!, 0.001)
        assertEquals(-4.0, gain.albumGainDb!!, 0.001)
    }

    @Test
    fun writingAnR128GainConvertsOrKeepsItsReferenceButNeverRelabelsIt() {
        val tags = TagBundle(
            emptyMap(),
            replayGain = ReplayGain(trackGainDb = -6.0, trackPeak = 0.75, isR128 = true),
        )
        for (format in listOf(TagFormat.ID3V2_4, TagFormat.APEV2)) {
            val bytes = AudioKodecs.writeTags(stream, tags, TagWriteOptions(formats = setOf(format)))
            val gain = assertNotNull(AudioKodecs.readTags(bytes).replayGain, format.displayName)
            assertFalse(gain.isR128, format.displayName)
            assertEquals(-1.0, gain.trackGainDb!!, 0.005, format.displayName)
            assertEquals(0.75, gain.trackPeak!!, 0.0005, format.displayName)
        }

        // Vorbis comments carry the Opus convention, so the reference is kept,
        // and so is the peak, which that convention has no field of its own for.
        val flac = AudioKodecs.encode(FloatArray(4096), AudioFormat(44_100, 1), EncodeTarget.flac(), tags)
        val gain = assertNotNull(AudioKodecs.readTags(flac).replayGain)
        assertTrue(gain.isR128)
        assertEquals(-6.0, gain.trackGainDb!!, 0.005)
        assertEquals(0.75, gain.trackPeak!!, 0.0005)
    }

    @Test
    fun anUnrelatedEditKeepsTheLanguageAndDescriptionOfEveryCommentAndLyric() {
        fun commentLike(language: String, description: String, text: String): ByteArray =
            byteArrayOf(0) + language.encodeToByteArray() + description.encodeToByteArray() +
                byteArrayOf(0) + text.encodeToByteArray()
        val original = id3v24(
            listOf(
                Frame("COMM", commentLike("fra", "review", "bonjour")),
                Frame("COMM", commentLike("eng", "", "hello")),
                // Equal text, told apart only by the description.
                Frame("COMM", commentLike("deu", "a", "gleich")),
                Frame("COMM", commentLike("deu", "b", "gleich")),
                Frame("USLT", commentLike("eng", "verse", "la la")),
                Frame("USLT", commentLike("fra", "verse", "la la")),
            ),
        ) + stream
        val before = AudioKodecs.readTags(original)
        val written = AudioKodecs.writeTags(original, before.edit { set(TagField.TITLE, "Changed") })
        val after = AudioKodecs.readTags(written)

        fun identities(tags: TagBundle) = tags.raw.blocks
            .flatMap { it.entries }
            .filter { it.key == "COMM" || it.key == "USLT" }
            .map { listOf(it.key, it.language, it.description, it.text) }
            .toSet()
        assertEquals(6, identities(before).size)
        assertEquals(identities(before), identities(after))
        assertEquals("Changed", after[TagField.TITLE])

        // A new comment added beside them takes an identity no other frame has.
        val added = AudioKodecs.writeTags(
            original,
            before.edit { add(TagField.COMMENT, "new"); add(TagField.COMMENT, "newer") },
        )
        val comments = AudioKodecs.readTags(added).raw.blocks.flatMap { it.entries }.filter { it.key == "COMM" }
        assertEquals(6, comments.size)
        assertEquals(comments.size, comments.map { it.language to it.description }.toSet().size)
    }

    @Test
    fun compressedAndEncryptedFramesSurviveAnUnrelatedEdit() {
        // An encryption-method byte and four bytes of ciphertext.
        val payload = byteArrayOf(0x80.toByte(), 1, 2, 3, 4)
        val original = id3v24(
            listOf(
                Frame("TIT2", byteArrayOf(0) + "Old".encodeToByteArray()),
                Frame("PRIV", payload, flags = 0x0004),
                // Encrypted artwork is ciphertext, not a picture.
                Frame("APIC", payload, flags = 0x0004),
                // Asked to be discarded whenever the tag is altered.
                Frame("GEOB", payload, flags = 0x4004),
            ),
        ) + stream
        val before = AudioKodecs.readTags(original)
        assertTrue(before.pictures.isEmpty())
        val kept = before.raw.blocks.flatMap { it.entries }.filter { it.id3FrameFlags != null }
        assertEquals(listOf("PRIV", "APIC", "GEOB"), kept.map { it.key })

        val written = AudioKodecs.writeTags(original, before.edit { set(TagField.TITLE, "Changed") })
        val after = AudioKodecs.readTags(written)
        assertEquals("Changed", after[TagField.TITLE])
        val opaque = after.raw.blocks.flatMap { it.entries }.filter { it.id3FrameFlags != null }
        assertEquals(listOf("PRIV", "APIC"), opaque.map { it.key })
        for (entry in opaque) {
            assertEquals(0x0004, entry.id3FrameFlags)
            assertContentEquals(payload, entry.binary)
        }
        assertTrue(after.pictures.isEmpty())

        // Version 2.3 lays the same flags out differently, so changing version
        // would have to drop the frames. That is refused instead.
        assertFailsWith<UnsupportedFeatureException> {
            AudioKodecs.writeTags(original, before, TagWriteOptions(id3Version = 3))
        }
    }

    @Test
    fun anUnrelatedEditKeepsUnknownRiffInfoEntries() {
        val wav = AudioKodecs.encode(
            TestSignals.quantise(TestSignals.sine(500, 1), 16),
            AudioFormat(44_100, 1),
            EncodeTarget.wav(16),
        )
        fun entry(key: String, value: String): ByteArray {
            val text = value.encodeToByteArray() + byteArrayOf(0)
            val padded = if (text.size % 2 == 1) text + byteArrayOf(0) else text
            return key.encodeToByteArray() + u32Le(text.size) + padded
        }
        val infoBody = "INFO".encodeToByteArray() +
            entry("INAM", "Old") + entry("IART", "Someone") + entry("IXXX", "secret")
        val original = wav + "LIST".encodeToByteArray() + u32Le(infoBody.size) + infoBody
        u32Le(original.size - 8).copyInto(original, 4)

        val before = AudioKodecs.readTags(original)
        assertTrue(before.raw.blocks.flatMap { it.entries }.any { it.key == "IXXX" })
        val edited = before.edit {
            set(TagField.TITLE, "Changed")
            remove(TagField.ARTIST)
        }
        val written = AudioKodecs.writeTags(original, edited)
        val after = AudioKodecs.readTags(written)
        assertEquals("Changed", after[TagField.TITLE])
        assertEquals(null, after[TagField.ARTIST])
        val unknown = after.raw.blocks.flatMap { it.entries }.filter { it.key == "IXXX" }
        assertEquals(listOf("secret"), unknown.map { it.text })

        // The audio payload is copied, not rebuilt.
        fun payload(file: ByteArray): ByteArray {
            val at = file.indexOfBytes("data".encodeToByteArray())
            val size = (file[at + 4].toInt() and 0xFF) or ((file[at + 5].toInt() and 0xFF) shl 8) or
                ((file[at + 6].toInt() and 0xFF) shl 16) or ((file[at + 7].toInt() and 0xFF) shl 24)
            return file.copyOfRange(at + 8, at + 8 + size)
        }
        assertContentEquals(payload(original), payload(written))

        // Choosing formats that leave INFO out is still a way to remove it.
        val removed = AudioKodecs.writeTags(original, edited, TagWriteOptions(formats = emptySet()))
        assertTrue(AudioKodecs.readTags(removed).raw.blocksOf(TagFormat.RIFF_INFO).isEmpty())
    }

    @Test
    fun chapterArtworkSurvivesWritingReadingAndEditing() {
        val image = byteArrayOf(-1, -40, 1, 2)
        val tags = TagBundle(
            emptyMap(),
            chapters = listOf(
                TagChapter(
                    id = "chapter1",
                    startMillis = 0,
                    endMillis = 1000,
                    title = "Chapter",
                    pictures = listOf(TagPicture(mimeType = "image/jpeg", description = "art", bytes = image)),
                ),
            ),
        )
        for (version in listOf(3, 4)) {
            val written = AudioKodecs.writeTags(stream, tags, TagWriteOptions(id3Version = version))
            val read = AudioKodecs.readTags(written)
            val edited = AudioKodecs.writeTags(
                written,
                read.edit { set(TagField.TITLE, "Changed") },
                TagWriteOptions(id3Version = version),
            )
            for (bundle in listOf(read, AudioKodecs.readTags(edited))) {
                val chapter = bundle.chapters.single()
                assertEquals("Chapter", chapter.title, "ID3v2.$version")
                val picture = chapter.pictures.single()
                assertEquals("image/jpeg", picture.mimeType, "ID3v2.$version")
                assertEquals("art", picture.description, "ID3v2.$version")
                assertContentEquals(image, picture.bytes, "ID3v2.$version")
            }
            // Chapter artwork belongs to its chapter, not to the file.
            assertTrue(read.pictures.isEmpty(), "ID3v2.$version")
        }
    }

    private fun u32Le(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())

    private class Frame(val id: String, val body: ByteArray, val flags: Int = 0)

    /** An ID3v2.4 block holding [frames] exactly as given, with no padding. */
    private fun id3v24(frames: List<Frame>): ByteArray {
        var body = byteArrayOf()
        for (frame in frames) {
            val header = ByteArray(10)
            frame.id.encodeToByteArray().copyInto(header)
            syncsafe(frame.body.size).copyInto(header, 4)
            header[8] = (frame.flags ushr 8).toByte()
            header[9] = frame.flags.toByte()
            body += header + frame.body
        }
        return "ID3".encodeToByteArray() + byteArrayOf(4, 0, 0) + syncsafe(body.size) + body
    }

    private fun syncsafe(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )
}

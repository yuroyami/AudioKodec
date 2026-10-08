package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Id3OpaquePreservationTest {
    private val audio = ByteArray(32) { 0x55 }

    @Test
    fun sameVersionEditsKeepOpaquePayloadsAndRetentionFlags() {
        for (major in 3..4) {
            val fileAlter = if (major == 4) 0x2000 else 0x4000
            val readOnly = if (major == 4) 0x1000 else 0x2000
            for (base in opaqueFrames(major)) {
                for (status in listOf(0, fileAlter, readOnly)) {
                    val opaque = Frame("PRIV", base.payload, base.flags or status)
                    val original = tag(major, listOf(title(), opaque)) + audio
                    val tags = AudioKodec.readTags(original).edit { set(TagField.TITLE, "Changed") }
                    val written = AudioKodec.writeTags(original, tags, TagWriteOptions(id3Version = major, padding = 0))
                    val stored = frames(written).single { it.id == "PRIV" }
                    assertEquals(opaque.flags, stored.flags)
                    assertContentEquals(opaque.payload, stored.payload)
                    assertEquals("Changed", AudioKodec.readTags(written)[TagField.TITLE])
                    assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
                    val sink = ByteArrayAudioSink()
                    assertFailsWith<UnsupportedFeatureException> {
                        AudioKodec.writeTags(original.asAudioSource(), tags, sink, TagWriteOptions(id3Version = 7 - major))
                    }
                    assertEquals(0L, sink.position)
                }
            }
        }
    }

    @Test
    fun discardOnTagAlterIsHonouredBeforeVersionConversion() {
        for (major in 3..4) {
            val discard = if (major == 4) 0x4000 else 0x8000
            for (base in opaqueFrames(major)) {
                val original = tag(major, listOf(title(), Frame("GEOB", base.payload, base.flags or discard))) + audio
                val tags = AudioKodec.readTags(original).edit { set(TagField.TITLE, "Changed") }
                val written = AudioKodec.writeTags(original, tags, TagWriteOptions(id3Version = 7 - major))
                assertTrue(frames(written).none { it.id == "GEOB" })
                assertContentEquals(audio, written.takeLast(audio.size).toByteArray())
            }
        }
    }

    @Test
    fun collidingClearFramesCannotSilentlyReplaceOpaqueFrames() {
        val clearFrames = listOf(
            Frame("PRIV", "owner\u0000private".encodeToByteArray()),
            Frame("GEOB", byteArrayOf(0) + "application/octet-stream\u0000file\u0000object\u0000data".encodeToByteArray()),
            Frame("APIC", byteArrayOf(0) + "image/png\u0000".encodeToByteArray() + byteArrayOf(3, 0, 1, 2, 3)),
            Frame("COMM", byteArrayOf(0) + "eng\u0000comment".encodeToByteArray()),
            Frame("USLT", byteArrayOf(0) + "eng\u0000lyrics".encodeToByteArray()),
        )
        for (major in 3..4) {
            for (clear in clearFrames) {
                for (base in opaqueFrames(major)) {
                    val original = tag(major, listOf(title(), clear, Frame(clear.id, base.payload, base.flags))) + audio
                    val tags = AudioKodec.readTags(original).edit { set(TagField.TITLE, "Changed") }
                    val sink = ByteArrayAudioSink()
                    assertFailsWith<UnsupportedFeatureException>("ID3v2.$major ${clear.id}, flags ${base.flags}") {
                        AudioKodec.writeTags(original.asAudioSource(), tags, sink, TagWriteOptions(id3Version = major))
                    }
                    assertEquals(0L, sink.position, "a refused rewrite must not emit a partial file")
                }
            }
        }
    }

    private class Frame(val id: String, val payload: ByteArray, val flags: Int = 0)

    private fun title(): Frame = Frame("TIT2", byteArrayOf(0) + "Old".encodeToByteArray())

    private fun opaqueFrames(major: Int): List<Frame> {
        val encrypted = Frame("PRIV", byteArrayOf(7, 1, 2, 0xFF.toByte(), 0xE0.toByte()), if (major == 4) 4 else 0x40)
        // zlib-compressed "owner\u0000opaque", made through Python's public zlib API.
        val compressed = intArrayOf(120, 156, 203, 47, 207, 75, 45, 98, 200, 47, 72, 44, 44, 77, 5, 0, 30, 182, 4, 183)
            .map { it.toByte() }.toByteArray()
        return listOf(encrypted, Frame("PRIV", size(12, major) + compressed, if (major == 4) 9 else 0x80))
    }

    private fun tag(major: Int, frames: List<Frame>): ByteArray {
        val body = frames.fold(ByteArray(0)) { bytes, frame ->
            bytes + frame.id.encodeToByteArray() + size(frame.payload.size, major) +
                byteArrayOf((frame.flags ushr 8).toByte(), frame.flags.toByte()) + frame.payload
        }
        return "ID3".encodeToByteArray() + byteArrayOf(major.toByte(), 0, 0) + size(body.size, 4) + body
    }

    /** Inspect stored bytes independently of the production raw-tag reader. */
    private fun frames(bytes: ByteArray): List<Frame> {
        val major = bytes[3].toInt()
        val end = 10 + readSize(bytes, 6, 4)
        val result = ArrayList<Frame>()
        var at = 10
        while (at + 10 <= end && bytes[at] != 0.toByte()) {
            val count = readSize(bytes, at + 4, major)
            val flags = ((bytes[at + 8].toInt() and 255) shl 8) or (bytes[at + 9].toInt() and 255)
            result.add(Frame(bytes.decodeToString(at, at + 4), bytes.copyOfRange(at + 10, at + 10 + count), flags))
            at += 10 + count
        }
        return result
    }

    private fun size(value: Int, major: Int): ByteArray = ByteArray(4) { at ->
        val bits = if (major == 4) 7 else 8
        (value ushr ((3 - at) * bits) and ((1 shl bits) - 1)).toByte()
    }

    private fun readSize(bytes: ByteArray, at: Int, major: Int): Int = (0..3).fold(0) { value, index ->
        (value shl (if (major == 4) 7 else 8)) or (bytes[at + index].toInt() and 255)
    }
}

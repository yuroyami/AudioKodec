package io.github.yuroyami.audiokodecs.internal.tag

import io.github.yuroyami.audiokodecs.tag.RawTagEntry
import io.github.yuroyami.audiokodecs.tag.TagBundle
import io.github.yuroyami.audiokodecs.tag.TagChapter
import io.github.yuroyami.audiokodecs.tag.TagField
import io.github.yuroyami.audiokodecs.tag.TagFormat
import io.github.yuroyami.audiokodecs.tag.TagPicture
import io.github.yuroyami.audiokodecs.AudioKodecsException
import io.github.yuroyami.audiokodecs.UnsupportedFeatureException

/**
 * Builds an ID3v2 block.
 *
 * Version 2.4 is written by default and version 2.3 on request, because some
 * hardware players never learned the newer one. Version 2.2 is read and never
 * written: it has been obsolete since 1998, and a file that had one gets a
 * version 2.3 block instead, which is what every other tagger does.
 *
 * Unsynchronisation is not applied. It exists so that a player scanning for an
 * MPEG frame sync cannot find one inside the tag, and every player written
 * since the 1990s reads the tag size instead of scanning. Writing it would
 * only make the block larger and harder to read.
 */
internal object Id3v2Writer {

    fun build(tags: TagBundle, major: Int, padding: Int): ByteArray {
        require(major == 3 || major == 4) { "AudioKodecs writes ID3v2.3 or 2.4, not 2.$major" }
        val frames = ArrayList<ByteArray>()

        for ((field, values) in tags.fields) {
            if (values.isEmpty()) continue
            when (field) {
                // The totals are written into the frame that holds the number.
                TagField.TRACK_TOTAL, TagField.DISC_TOTAL -> continue
                TagField.TRACK_NUMBER -> frames.add(
                    textFrame(
                        TagMapping.id3FrameFor(field, major)!!,
                        listOf(joinWithTotal(values.first(), tags[TagField.TRACK_TOTAL])),
                        major,
                    ),
                )
                TagField.DISC_NUMBER -> frames.add(
                    textFrame(
                        TagMapping.id3FrameFor(field, major)!!,
                        listOf(joinWithTotal(values.first(), tags[TagField.DISC_TOTAL])),
                        major,
                    ),
                )
                TagField.COMMENT -> frames.addAll(commentLikeFrames("COMM", "COM", values, tags, major))
                TagField.LYRICS -> frames.addAll(commentLikeFrames("USLT", "ULT", values, tags, major))
                else -> {
                    val frameId = TagMapping.id3FrameFor(field, major)
                    if (frameId != null && frameId.startsWith("W")) {
                        // URL frames hold the URL and nothing else: no
                        // encoding byte, which a reader would take for an
                        // empty URL, and one frame per value.
                        for (value in values) frames.add(urlFrame(frameId, value, major))
                    } else if (frameId != null) {
                        if (major >= 4) {
                            frames.add(textFrame(frameId, values, major))
                        } else {
                            // Version 2.3 has no multi-value form, so each
                            // value gets its own frame. Readers accept that,
                            // and joining them would invent a separator the
                            // format does not have.
                            for (value in values) frames.add(textFrame(frameId, listOf(value), major))
                        }
                    } else {
                        val description = TagMapping.userTextDescriptionFor(field) ?: field.name
                        for (value in values) {
                            frames.add(userTextFrame(description, value, major))
                        }
                    }
                }
            }
        }

        // ID3 has no Opus loudness convention, so an R128 gain is restated
        // against the ReplayGain reference rather than relabelled.
        tags.replayGain?.inReplayGainReference()?.let { gain ->
            gain.trackGainDb?.let {
                frames.add(userTextFrame("replaygain_track_gain", formatDecibels(it), major))
            }
            gain.trackPeak?.let {
                frames.add(userTextFrame("replaygain_track_peak", it.toString(), major))
            }
            gain.albumGainDb?.let {
                frames.add(userTextFrame("replaygain_album_gain", formatDecibels(it), major))
            }
            gain.albumPeak?.let {
                frames.add(userTextFrame("replaygain_album_peak", it.toString(), major))
            }
        }

        for (picture in tags.pictures) {
            frames.add(pictureFrame(picture, major))
        }
        for (chapter in tags.chapters) {
            frames.add(chapterFrame(chapter, major))
        }
        frames.addAll(unmodelledFrames(tags, major))

        var body = 0L
        for (frame in frames) body += frame.size
        val pad = if (padding < 0) 0 else padding
        val payload = body + pad
        if (payload > MAX_SYNCSAFE_SIZE) {
            throw AudioKodecsException(
                "an ID3v2 tag body is $payload bytes; its header can represent at most " +
                    "$MAX_SYNCSAFE_SIZE",
            )
        }
        val total = HEADER + payload.toInt()

        val out = ByteArray(total)
        out[0] = 'I'.code.toByte()
        out[1] = 'D'.code.toByte()
        out[2] = '3'.code.toByte()
        out[3] = major.toByte()
        out[4] = 0
        out[5] = 0
        writeSyncsafe(out, 6, payload.toInt())

        var position = HEADER
        for (frame in frames) {
            frame.copyInto(out, position)
            position += frame.size
        }
        return out
    }

    /** Bytes in the header, which the declared size excludes. */
    private const val HEADER = 10

    private fun joinWithTotal(number: String, total: String?): String =
        if (total.isNullOrBlank()) number else "$number/$total"

    /**
     * Chooses the narrowest encoding that can hold every value.
     *
     * ISO-8859-1 is used whenever it fits, because it is the one encoding
     * every reader ever written handles. Beyond it, version 2.4 has UTF-8 and
     * version 2.3 has only UTF-16.
     */
    private fun encodingFor(values: List<String>, major: Int): Int {
        val allLatin1 = values.all { TagText.isLatin1(it) }
        return when {
            allLatin1 -> TagText.LATIN1
            major >= 4 -> TagText.UTF8
            else -> TagText.UTF16_BOM
        }
    }

    private fun encodeText(value: String, encoding: Int): ByteArray = when (encoding) {
        TagText.LATIN1 -> TagText.encodeLatin1(value)
        TagText.UTF16_BOM -> TagText.encodeUtf16WithBom(value)
        else -> value.encodeToByteArray()
    }

    private fun textFrame(id: String, values: List<String>, major: Int): ByteArray {
        val encoding = encodingFor(values, major)
        val terminator = TagText.terminatorSize(encoding)
        val parts = values.map { encodeText(it, encoding) }
        var size = 1L
        for (i in parts.indices) {
            size += parts[i].size
            if (i < parts.size - 1) size += terminator
        }
        val body = ByteArray(checkedArraySize(size, "an ID3 text frame"))
        body[0] = encoding.toByte()
        var position = 1
        for (i in parts.indices) {
            parts[i].copyInto(body, position)
            position += parts[i].size
            if (i < parts.size - 1) position += terminator
        }
        return frame(id, body, major)
    }

    /** A URL link frame, whose body is the Latin-1 URL with no encoding byte. */
    private fun urlFrame(id: String, url: String, major: Int): ByteArray =
        frame(id, TagText.encodeLatin1(url), major)

    private fun userTextFrame(description: String, value: String, major: Int): ByteArray {
        val encoding = encodingFor(listOf(description, value), major)
        val descriptionBytes = encodeText(description, encoding)
        val valueBytes = encodeText(value, encoding)
        val terminator = TagText.terminatorSize(encoding)
        val body = ByteArray(
            checkedArraySize(
                1L + descriptionBytes.size + terminator + valueBytes.size,
                "an ID3 user-text frame",
            ),
        )
        body[0] = encoding.toByte()
        descriptionBytes.copyInto(body, 1)
        valueBytes.copyInto(body, 1 + descriptionBytes.size + terminator)
        return frame("TXXX", body, major)
    }

    /**
     * Comment or lyric frames for [values], keeping the identity each one had.
     *
     * ID3 tells these frames apart by language and description, and the
     * merged view keeps only their text. So every original frame whose text is
     * still one of the values is written back with its own language and
     * description, including several frames that share one text. A value no
     * original frame held is new, and gets the undetermined language and an
     * empty description, or a numbered description when that identity is
     * already taken, because two frames may not share one.
     */
    private fun commentLikeFrames(
        id: String,
        version22Id: String,
        values: List<String>,
        tags: TagBundle,
        major: Int,
    ): List<ByteArray> {
        val originals = ArrayList<RawTagEntry>()
        for (block in tags.raw.blocks) {
            if (!isId3(block.format)) continue
            for (entry in block.entries) {
                if ((entry.key == id || entry.key == version22Id) && entry.text in values) {
                    originals.add(entry)
                }
            }
        }
        val frames = ArrayList<ByteArray>()
        val used = HashSet<Pair<String, String>>()
        for (entry in originals) {
            val language = entry.language?.takeIf { it.length == 3 && TagText.isLatin1(it) } ?: UNDETERMINED
            val identity = language to (entry.description ?: "")
            if (!used.add(identity)) continue
            frames.add(commentFrame(id, entry.text!!, identity.first, identity.second, major))
        }
        for (value in values) {
            if (originals.any { it.text == value }) continue
            var description = ""
            var next = 2
            while ((UNDETERMINED to description) in used) description = (next++).toString()
            used.add(UNDETERMINED to description)
            frames.add(commentFrame(id, value, UNDETERMINED, description, major))
        }
        return frames
    }

    private fun commentFrame(
        id: String,
        value: String,
        language: String,
        description: String,
        major: Int,
    ): ByteArray {
        val encoding = encodingFor(listOf(description, value), major)
        val descriptionBytes = encodeText(description, encoding)
        val valueBytes = encodeText(value, encoding)
        val terminator = TagText.terminatorSize(encoding)
        // Encoding, the three-letter language, the description, then the text.
        val body = ByteArray(
            checkedArraySize(
                4L + descriptionBytes.size + terminator + valueBytes.size,
                "an ID3 comment frame",
            ),
        )
        body[0] = encoding.toByte()
        for (i in 0 until 3) body[1 + i] = language[i].code.toByte()
        descriptionBytes.copyInto(body, 4)
        valueBytes.copyInto(body, 4 + descriptionBytes.size + terminator)
        return frame(id, body, major)
    }

    /** The language code ID3 writers use when nobody stated one. */
    private const val UNDETERMINED = "xxx"

    private fun isId3(format: TagFormat): Boolean =
        format == TagFormat.ID3V2_2 || format == TagFormat.ID3V2_3 || format == TagFormat.ID3V2_4

    private fun pictureFrame(picture: TagPicture, major: Int): ByteArray {
        val encoding = encodingFor(listOf(picture.description), major)
        val mime = TagText.encodeLatin1(picture.mimeType)
        val description = encodeText(picture.description, encoding)
        val terminator = TagText.terminatorSize(encoding)
        val body = ByteArray(
            checkedArraySize(
                3L + mime.size + description.size + terminator + picture.bytes.size,
                "an ID3 picture frame",
            ),
        )
        var position = 0
        body[position++] = encoding.toByte()
        mime.copyInto(body, position)
        position += mime.size
        body[position++] = 0
        body[position++] = picture.type.ordinal.toByte()
        description.copyInto(body, position)
        position += description.size + terminator
        picture.bytes.copyInto(body, position)
        return frame("APIC", body, major)
    }

    /** Writes a CHAP frame, including its title and chapter-local pictures. */
    private fun chapterFrame(chapter: TagChapter, major: Int): ByteArray {
        require('\u0000' !in chapter.id) { "an ID3 chapter id cannot contain a null character" }
        val id = TagText.encodeLatin1(chapter.id)
        val nested = ArrayList<ByteArray>()
        chapter.title?.takeIf { it.isNotEmpty() }?.let {
            nested.add(textFrame("TIT2", listOf(it), major))
        }
        for (picture in chapter.pictures) nested.add(pictureFrame(picture, major))

        var size = id.size.toLong() + 1 + 16
        for (child in nested) size += child.size
        val body = ByteArray(checkedArraySize(size, "an ID3 chapter frame"))
        id.copyInto(body, 0)
        var position = id.size + 1
        writeUInt32Be(body, position, chapter.startMillis.coerceIn(0, 0xFFFF_FFFFL))
        writeUInt32Be(
            body,
            position + 4,
            if (chapter.endMillis < 0) 0xFFFF_FFFFL else chapter.endMillis.coerceAtMost(0xFFFF_FFFFL),
        )
        // Byte offsets are unknown and therefore use the value the ID3
        // specification reserves for "not stated".
        writeUInt32Be(body, position + 8, 0xFFFF_FFFFL)
        writeUInt32Be(body, position + 12, 0xFFFF_FFFFL)
        position += 16
        for (child in nested) {
            child.copyInto(body, position)
            position += child.size
        }
        return frame("CHAP", body, major)
    }

    /**
     * Re-emits entries AudioKodecs does not model.
     *
     * Modelled fields and pictures are rebuilt above from the edited view.
     * Binary/private frames have no such view, so dropping them would make an
     * unrelated title edit destructive.
     */
    private fun unmodelledFrames(tags: TagBundle, major: Int): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        for (block in tags.raw.blocks) {
            val isId3 = block.format == TagFormat.ID3V2_2 ||
                block.format == TagFormat.ID3V2_3 ||
                block.format == TagFormat.ID3V2_4
            if (!isId3) continue
            for (entry in block.entries) {
                if (TagMapping.fieldFor(block.format, entry) != null) continue
                if (TagMapping.numberAndTotalFor(block.format, entry.key) != null) continue
                if (entry.key == "APIC" || entry.key == "PIC" || entry.key == "CHAP") continue
                if (isReplayGain(entry)) continue

                val key = when {
                    entry.key.length == 4 -> entry.key
                    entry.key == "UFI" -> "UFID"
                    entry.key == "TXX" -> "TXXX"
                    else -> throw UnsupportedFeatureException(
                        "preserving unknown ID3v2.2 frame '${entry.key}'",
                        "this tag contains a three-character frame with no safe ID3v2.3/2.4 equivalent",
                    )
                }
                rawFrame(key, entry, major)?.let { frames.add(it) }
            }
        }
        return frames
    }

    private fun rawFrame(
        key: String,
        entry: io.github.yuroyami.audiokodecs.tag.RawTagEntry,
        major: Int,
    ): ByteArray? {
        if (key == "UFID") {
            val owner = TagText.encodeLatin1(entry.description ?: "")
            val value = entry.binary ?: entry.text?.let(TagText::encodeLatin1) ?: return null
            val body = ByteArray(
                checkedArraySize(
                    owner.size.toLong() + 1 + value.size,
                    "an ID3 unique-file-id frame",
                ),
            )
            owner.copyInto(body, 0)
            value.copyInto(body, owner.size + 1)
            return frame(key, body, major)
        }
        if (key == "TXXX") {
            val text = entry.text ?: return entry.binary?.let { frame(key, it, major) }
            return userTextFrame(entry.description ?: "", text, major)
        }
        if (key == "WXXX") {
            val text = entry.text ?: return entry.binary?.let { frame(key, it, major) }
            val description = entry.description ?: ""
            val encoding = encodingFor(listOf(description), major)
            val descriptionBytes = encodeText(description, encoding)
            val url = TagText.encodeLatin1(text)
            val terminator = TagText.terminatorSize(encoding)
            val body = ByteArray(
                checkedArraySize(
                    1L + descriptionBytes.size + terminator + url.size,
                    "an ID3 user-URL frame",
                ),
            )
            body[0] = encoding.toByte()
            descriptionBytes.copyInto(body, 1)
            url.copyInto(body, 1 + descriptionBytes.size + terminator)
            return frame(key, body, major)
        }
        entry.binary?.let { return frame(key, it, major) }
        val text = entry.text ?: return null
        return when {
            key.startsWith("T") -> textFrame(key, listOf(text), major)
            key.startsWith("W") -> urlFrame(key, text, major)
            else -> frame(key, TagText.encodeLatin1(text), major)
        }
    }

    private fun isReplayGain(entry: io.github.yuroyami.audiokodecs.tag.RawTagEntry): Boolean {
        val key = entry.description?.uppercase() ?: entry.key.uppercase()
        return key.startsWith("REPLAYGAIN_") || key.startsWith("R128_")
    }

    private fun frame(id: String, body: ByteArray, major: Int): ByteArray {
        require(id.length == 4 && id.all { it in 'A'..'Z' || it in '0'..'9' }) {
            "an ID3v2.3/2.4 frame id must be four uppercase ASCII letters or digits, was '$id'"
        }
        if (major >= 4 && body.size > MAX_SYNCSAFE_SIZE) {
            throw AudioKodecsException(
                "ID3v2.4 frame '$id' is too large for its syncsafe size field",
            )
        }
        val out = ByteArray(checkedArraySize(10L + body.size, "ID3 frame '$id'"))
        for (i in 0 until 4) out[i] = id[i].code.toByte()
        if (major >= 4) {
            writeSyncsafe(out, 4, body.size)
        } else {
            out[4] = (body.size ushr 24).toByte()
            out[5] = (body.size ushr 16).toByte()
            out[6] = (body.size ushr 8).toByte()
            out[7] = body.size.toByte()
        }
        body.copyInto(out, 10)
        return out
    }

    private fun writeSyncsafe(out: ByteArray, at: Int, value: Int) {
        require(value in 0..MAX_SYNCSAFE_SIZE) {
            "a syncsafe integer is 0..$MAX_SYNCSAFE_SIZE, was $value"
        }
        out[at] = ((value shr 21) and 0x7F).toByte()
        out[at + 1] = ((value shr 14) and 0x7F).toByte()
        out[at + 2] = ((value shr 7) and 0x7F).toByte()
        out[at + 3] = (value and 0x7F).toByte()
    }

    private fun writeUInt32Be(out: ByteArray, at: Int, value: Long) {
        val v = value.toInt()
        out[at] = (v ushr 24).toByte()
        out[at + 1] = (v ushr 16).toByte()
        out[at + 2] = (v ushr 8).toByte()
        out[at + 3] = v.toByte()
    }

    /** Formats a gain the way every ReplayGain writer does. */
    fun formatDecibels(value: Double): String {
        val rounded = (value * 100).toLong() / 100.0
        val sign = if (rounded >= 0) "+" else ""
        return "$sign$rounded dB"
    }

    private fun checkedArraySize(size: Long, what: String): Int {
        if (size !in 0..Int.MAX_VALUE.toLong()) {
            throw AudioKodecsException("$what is too large for a Kotlin array")
        }
        return size.toInt()
    }

    private const val MAX_SYNCSAFE_SIZE = 0x0FFF_FFFF
}

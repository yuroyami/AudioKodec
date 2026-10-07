package io.github.yuroyami.audiokodec.internal.tag

import io.github.yuroyami.audiokodec.tag.RawTagEntry
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagChapter
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagPicture
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException

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
        require(major == 3 || major == 4) { "AudioKodec writes ID3v2.3 or 2.4, not 2.$major" }
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
                TagField.MUSICBRAINZ_RECORDING_ID -> frames.add(recordingFrame(values, major))
                TagField.GENRE -> frames.add(textFrame("TCON", Id3Genres.storedValues(values, major), major))
                else -> {
                    val frameId = TagMapping.id3FrameFor(field, major)
                    if (frameId != null && frameId.startsWith("W")) {
                        // URL frames hold the URL and nothing else: no
                        // encoding byte, which a reader would take for an
                        // empty URL, and one frame per value.
                        for (value in values) frames.add(urlFrame(frameId, value, major))
                    } else if (frameId != null) {
                        frames.add(textFrame(frameId, textValues(frameId, values, major), major))
                    } else {
                        val description = TagMapping.userTextDescriptionFor(field) ?: field.name
                        frames.add(userTextFrame(description, values, major))
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
        frames.addAll(opaqueFrames(tags, major, frames))

        var body = 0L
        for (frame in frames) body += frame.size
        val pad = if (padding < 0) 0 else padding
        val payload = body + pad
        if (payload > MAX_SYNCSAFE_SIZE) {
            throw AudioKodecException(
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

    private fun recordingFrame(values: List<String>, major: Int): ByteArray {
        val identifiers = values.distinct()
        if (identifiers.size != 1 || !TagMapping.isRecordingIdentifier(identifiers.single())) {
            throw UnsupportedFeatureException(
                "representing MusicBrainz recording identifiers in ID3",
                "ID3 requires one MusicBrainz recording identifier of 1 to 64 ASCII bytes " +
                    "without whitespace or control characters",
            )
        }
        val body = TagMapping.MUSICBRAINZ_OWNER.encodeToByteArray() + byteArrayOf(0) + identifiers.single().encodeToByteArray()
        return frame("UFID", body, major)
    }

    private fun textValues(id: String, values: List<String>, major: Int): List<String> {
        if (major >= 4) return values
        val distinct = values.distinct()
        if (TagMapping.id3UsesSlashList(id, major)) {
            if (distinct.any { '/' in it }) {
                throw UnsupportedFeatureException(
                    "a literal slash in ID3v2.3 '$id'",
                    "ID3v2.3 '$id' uses slashes between values; write ID3v2.4 to preserve a literal slash",
                )
            }
            return listOf(distinct.joinToString("/"))
        }
        if (distinct.size > 1) {
            throw UnsupportedFeatureException(
                "multiple values in ID3v2.3 '$id'",
                "ID3v2.3 '$id' has no unambiguous list representation; write ID3v2.4 to preserve these values",
            )
        }
        return distinct
    }

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

    private fun userTextFrame(description: String, value: String, major: Int): ByteArray =
        userTextFrame(description, listOf(value), major)

    private fun userTextFrame(description: String, values: List<String>, major: Int): ByteArray {
        val supported = textValues("TXXX:$description", values, major)
        val encoding = encodingFor(listOf(description) + supported, major)
        val descriptionBytes = encodeText(description, encoding)
        val parts = supported.map { encodeText(it, encoding) }
        val terminator = TagText.terminatorSize(encoding)
        var size = 1L + descriptionBytes.size + terminator
        for (part in parts) size += part.size
        size += maxOf(0, parts.size - 1).toLong() * terminator
        val body = ByteArray(
            checkedArraySize(size, "an ID3 user-text frame"),
        )
        body[0] = encoding.toByte()
        descriptionBytes.copyInto(body, 1)
        var position = 1 + descriptionBytes.size + terminator
        for (i in parts.indices) {
            parts[i].copyInto(body, position)
            position += parts[i].size
            if (i < parts.lastIndex) position += terminator
        }
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
        val requested = TagValueIndex<String>(naturalOrder())
        for (value in values) requested.add(value)
        val originalText = TagValueIndex<String>(naturalOrder())
        val originals = ArrayList<RawTagEntry>()
        for (block in tags.raw.blocks) {
            if (!isId3(block.format)) continue
            for (entry in block.entries) {
                if ((entry.key == id || entry.key == version22Id) && entry.text != null && entry.text in requested) {
                    originals.add(entry)
                    originalText.add(entry.text)
                }
            }
        }
        val frames = ArrayList<ByteArray>()
        val used = TagValueIndex(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        for (entry in originals) {
            val language = entry.language?.takeIf { it.length == 3 && TagText.isLatin1(it) } ?: UNDETERMINED
            val identity = language to (entry.description ?: "")
            if (!used.add(identity)) continue
            frames.add(commentFrame(id, entry.text!!, identity.first, identity.second, major))
        }
        // Every smaller candidate has already been used or found occupied.
        // Advancing once preserves the first-unused identity without rescans.
        var next = 2
        for (value in values) {
            if (value in originalText) continue
            var description = ""
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
     * Re-emits entries AudioKodec does not model.
     *
     * Modelled fields and pictures are rebuilt above from the edited view.
     * Binary/private frames have no such view, so dropping them would make an
     * unrelated title edit destructive.
     */
    private fun unmodelledFrames(tags: TagBundle, major: Int): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        val userText = LinkedHashMap<String, MutableList<String>>()
        val ordinaryText = LinkedHashMap<String, MutableList<String>>()
        for (block in tags.raw.blocks) {
            val isId3 = block.format == TagFormat.ID3V2_2 ||
                block.format == TagFormat.ID3V2_3 ||
                block.format == TagFormat.ID3V2_4
            if (!isId3) continue
            for (entry in block.entries) {
                if (entry.id3FrameFlags != null) continue
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
                if (key == "UFID" && entry.description == TagMapping.MUSICBRAINZ_OWNER &&
                    !tags.all(TagField.MUSICBRAINZ_RECORDING_ID).isEmpty()
                ) continue
                if (entry.binary == null && entry.text != null) {
                    if (key == "TXXX") {
                        userText.getOrPut(entry.description ?: "") { mutableListOf() }.add(entry.text)
                        continue
                    }
                    if (key.startsWith("T")) {
                        ordinaryText.getOrPut(key) { mutableListOf() }.add(entry.text)
                        continue
                    }
                }
                rawFrame(key, entry, major)?.let { frames.add(it) }
            }
        }
        for ((description, values) in userText) frames.add(userTextFrame(description, values.distinct(), major))
        for ((key, values) in ordinaryText) frames.add(textFrame(key, textValues(key, values.distinct(), major), major))
        return frames
    }

    /**
     * Writes back the frames the reader kept as stored because they are
     * compressed or encrypted.
     *
     * Their payload is never interpreted, so it can only be copied. That is
     * safe within one ID3 version and not across two: versions 2.3 and 2.4 put
     * these flags in different bits and the bytes they announce in a different
     * order. A rewrite that would change the version is refused rather than
     * written without the frames. A frame flagged to be discarded when the
     * tag is altered is discarded, as the specification asks, and one whose
     * identifier this writer has just written from the caller's values is
     * superseded by it.
     */
    private fun opaqueFrames(tags: TagBundle, major: Int, written: List<ByteArray>): List<ByteArray> {
        val writtenIds = written.mapTo(HashSet()) { it.decodeToString(0, 4) }
        val frames = ArrayList<ByteArray>()
        for (block in tags.raw.blocks) {
            if (!isId3(block.format)) continue
            val sourceMajor = when (block.format) {
                TagFormat.ID3V2_3 -> 3
                TagFormat.ID3V2_4 -> 4
                else -> continue
            }
            for (entry in block.entries) {
                val flags = entry.id3FrameFlags ?: continue
                val payload = entry.binary ?: continue
                val discardOnTagAlter = if (sourceMajor == 4) 0x4000 else 0x8000
                if (flags and discardOnTagAlter != 0) continue
                if (entry.key in writtenIds && (entry.key == "UFID" || entry.key == "TXXX")) {
                    throw UnsupportedFeatureException(
                        "combining opaque ID3 '${entry.key}' identities",
                        "this tag holds an unreadable '${entry.key}' owner or description; " +
                            "it cannot be combined safely with another frame of that kind",
                    )
                }
                if (entry.key in writtenIds) continue
                if (sourceMajor != major) {
                    throw UnsupportedFeatureException(
                        "converting a compressed or encrypted ID3 frame between versions",
                        "this tag holds a compressed or encrypted ID3v2.$sourceMajor '${entry.key}' " +
                            "frame, which cannot be rewritten as ID3v2.$major without decoding it; " +
                            "write ID3v2.$sourceMajor to keep it",
                    )
                }
                frames.add(frame(entry.key, payload, major, flags))
            }
        }
        return frames
    }

    private fun rawFrame(
        key: String,
        entry: io.github.yuroyami.audiokodec.tag.RawTagEntry,
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

    private fun isReplayGain(entry: io.github.yuroyami.audiokodec.tag.RawTagEntry): Boolean {
        val key = entry.description?.uppercase() ?: entry.key.uppercase()
        return key.startsWith("REPLAYGAIN_") || key.startsWith("R128_")
    }

    private fun frame(id: String, body: ByteArray, major: Int, flags: Int = 0): ByteArray {
        require(id.length == 4 && id.all { it in 'A'..'Z' || it in '0'..'9' }) {
            "an ID3v2.3/2.4 frame id must be four uppercase ASCII letters or digits, was '$id'"
        }
        if (major >= 4 && body.size > MAX_SYNCSAFE_SIZE) {
            throw AudioKodecException(
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
        out[8] = (flags ushr 8).toByte()
        out[9] = flags.toByte()
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
            throw AudioKodecException("$what is too large for a Kotlin array")
        }
        return size.toInt()
    }

    private const val MAX_SYNCSAFE_SIZE = 0x0FFF_FFFF
}

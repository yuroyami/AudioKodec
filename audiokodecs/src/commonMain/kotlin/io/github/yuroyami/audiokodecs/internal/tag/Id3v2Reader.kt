package io.github.yuroyami.audiokodecs.internal.tag

import io.github.yuroyami.audiokodecs.AllocationGuardException
import io.github.yuroyami.audiokodecs.DecodeOptions
import io.github.yuroyami.audiokodecs.tag.PictureType
import io.github.yuroyami.audiokodecs.tag.RawTagBlock
import io.github.yuroyami.audiokodecs.tag.RawTagEntry
import io.github.yuroyami.audiokodecs.tag.TagChapter
import io.github.yuroyami.audiokodecs.tag.TagFormat
import io.github.yuroyami.audiokodecs.tag.TagPicture

/**
 * Reads an ID3v2 block, in any of the three versions still found in files.
 *
 * The three differ more than their shared name suggests. Version 2.2 uses
 * three-character frame names and three-byte sizes. Version 2.3 moved to four
 * of each and added frame flags. Version 2.4 kept that shape but made frame
 * sizes syncsafe, added two text encodings, and allowed one text frame to hold
 * several values. All three are handled here, and the differences are marked
 * where they arise.
 *
 * A malformed frame stops the frame, not the block. Tag blocks written by
 * decades of different programs are the least reliable bytes in any audio
 * file, and a reader that gave up on the first bad frame would return nothing
 * for a great many real files.
 */
internal object Id3v2Reader {

    /** Bytes in the header that precedes every ID3v2 block. */
    const val HEADER_SIZE: Int = 10
    private const val MAX_CHAPTER_DEPTH: Int = 16

    /**
     * Reads the declared size of the block starting at [offset], including its
     * header and footer, or -1 when there is no block there.
     *
     * This is what a container reader uses to skip the tag and find the audio,
     * so it does no parsing beyond the header.
     */
    fun blockSizeAt(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset > bytes.size - HEADER_SIZE) return -1
        if (bytes[offset].toInt() != 'I'.code ||
            bytes[offset + 1].toInt() != 'D'.code ||
            bytes[offset + 2].toInt() != '3'.code
        ) {
            return -1
        }
        val major = bytes[offset + 3].toInt() and 0xFF
        val revision = bytes[offset + 4].toInt() and 0xFF
        if (major !in 2..4 || revision == 0xFF) return -1
        val flags = bytes[offset + 5].toInt() and 0xFF
        val reservedMask = when (major) {
            2 -> 0x3F
            3 -> 0x1F
            else -> 0x0F
        }
        if (flags and reservedMask != 0) return -1
        val size = readSyncsafe(bytes, offset + 6) ?: return -1
        val footer = if (flags and 0x10 != 0) HEADER_SIZE else 0
        val total = HEADER_SIZE + size + footer
        if (total <= 0) return -1
        return total
    }

    /**
     * Parses the block at [offset].
     *
     * Returns null when there is no readable block there. [fileOffset] is
     * where the block sits in the file, which only affects what the returned
     * block reports about itself.
     */
    fun read(
        bytes: ByteArray,
        offset: Int,
        fileOffset: Long,
        options: DecodeOptions,
    ): RawTagBlock? {
        val total = blockSizeAt(bytes, offset)
        if (total < 0) return null
        if (total > bytes.size - offset) return null

        val major = bytes[offset + 3].toInt() and 0xFF
        val format = when (major) {
            2 -> TagFormat.ID3V2_2
            3 -> TagFormat.ID3V2_3
            4 -> TagFormat.ID3V2_4
            else -> return null
        }
        val flags = bytes[offset + 5].toInt() and 0xFF
        val declaredSize = readSyncsafe(bytes, offset + 6) ?: return null

        if (flags and 0x10 != 0) {
            val footer = offset + HEADER_SIZE + declaredSize
            if (footer > bytes.size - HEADER_SIZE ||
                bytes[footer].toInt() != '3'.code ||
                bytes[footer + 1].toInt() != 'D'.code ||
                bytes[footer + 2].toInt() != 'I'.code ||
                bytes[footer + 3] != bytes[offset + 3] ||
                bytes[footer + 4] != bytes[offset + 4] ||
                bytes[footer + 5] != bytes[offset + 5]
            ) {
                return null
            }
            for (index in 6..9) {
                if (bytes[footer + index] != bytes[offset + index]) return null
            }
        }

        // A tag compressed as a whole is a version 2.2 feature that no writer
        // ever produced, and the specification does not say how to undo it.
        if (major == 2 && flags and 0x40 != 0) return null

        val bodyStart = offset + HEADER_SIZE
        val bodyEnd = bodyStart + minOf(declaredSize, bytes.size - bodyStart)
        if (bodyEnd <= bodyStart) {
            return RawTagBlock(format, fileOffset, total.toLong(), emptyList())
        }
        options.checkAllocation(
            (bodyEnd - bodyStart).toLong(),
            "reading an ID3v2 tag body",
        )

        // Version 2.3 and earlier unsynchronise the whole block, so it has to
        // be undone before anything is read. Version 2.4 moved the flag to
        // each frame, which is why this only runs for the older versions.
        var body = bytes.copyOfRange(bodyStart, bodyEnd)
        if (flags and 0x80 != 0 && major < 4) {
            body = undoUnsynchronisation(body)
        }

        var position = 0
        if (flags and 0x40 != 0) {
            position = skipExtendedHeader(body, major)
            if (position == 0) return null
        }

        val budget = EntryBudget(options.maxAllocationBytes)
        val entries = EntryList(budget)
        val nameSize = if (major == 2) 3 else 4
        val headerSize = if (major == 2) 6 else 10

        val globalUnsynchronised = major == 4 && flags and 0x80 != 0
        while (position <= body.size - headerSize) {
            // Padding is a run of zero bytes, and it means the frames are over.
            if (body[position].toInt() == 0) break

            val name = frameName(body, position, nameSize)
            var size = when (major) {
                2 -> readUInt24(body, position + 3)
                4 -> readSyncsafe(body, position + 4) ?: break
                else -> readUInt32(body, position + 4)
            }
            var frameFlags = 0
            if (major >= 3) {
                frameFlags = ((body[position + 8].toInt() and 0xFF) shl 8) or
                    (body[position + 9].toInt() and 0xFF)
            }
            var dataStart = position + headerSize

            // A version 2.4 writer that used plain sizes is a known bug in
            // several programs. When the syncsafe reading runs past the end
            // and the plain reading fits exactly, the plain one was meant.
            if (major == 4 && (size < 0 || size > body.size - dataStart)) {
                val plain = readUInt32(body, position + 4)
                if (plain in 0..(body.size - dataStart)) size = plain
            }

            if (size < 0 || size > body.size - dataStart) break

            val dataEnd = dataStart + size
            if (major >= 3) {
                val reservedFlags = if (major == 4) {
                    frameFlags and 0x8FB0
                } else {
                    frameFlags and 0x1F1F
                }
                if (reservedFlags != 0) {
                    position = dataEnd
                    continue
                }

                // The two versions put the same flags in different bits, and
                // then write the extra bytes those flags announce in a
                // different order. Both differences matter, so both are here.
                val compressed: Boolean
                val encrypted: Boolean
                val grouped: Boolean
                val hasDataLength: Boolean
                val frameUnsynchronised: Boolean
                if (major == 4) {
                    grouped = frameFlags and 0x0040 != 0
                    compressed = frameFlags and 0x0008 != 0
                    encrypted = frameFlags and 0x0004 != 0
                    frameUnsynchronised = frameFlags and 0x0002 != 0
                    hasDataLength = frameFlags and 0x0001 != 0
                } else {
                    compressed = frameFlags and 0x0080 != 0
                    encrypted = frameFlags and 0x0040 != 0
                    grouped = frameFlags and 0x0020 != 0
                    frameUnsynchronised = false
                    hasDataLength = false
                }

                if (compressed || encrypted) {
                    // These need inflate or a format-specific decryptor, which
                    // this module does not carry. Parsing their payload as if
                    // it were clear text would be worse than losing one frame.
                    position = dataEnd
                    continue
                }
                if (major == 4) {
                    val prefixBytes =
                        (if (grouped) 1 else 0) +
                            (if (encrypted) 1 else 0) +
                            (if (hasDataLength) 4 else 0)
                    if (prefixBytes > dataEnd - dataStart) {
                        position = dataEnd
                        continue
                    }
                    dataStart += prefixBytes
                } else {
                    val prefixBytes = (if (encrypted) 1 else 0) + (if (grouped) 1 else 0)
                    if (prefixBytes > dataEnd - dataStart) {
                        position = dataEnd
                        continue
                    }
                    dataStart += prefixBytes
                }
                // Version 2.4 unsynchronises one frame at a time.
                if (frameUnsynchronised || globalUnsynchronised) {
                    val raw = body.copyOfRange(dataStart, dataEnd)
                    val clean = undoUnsynchronisation(raw)
                    if (name.isNotEmpty() && clean.isNotEmpty()) {
                        parseFrame(name, clean, 0, clean.size, major, entries, 0, options)
                    }
                    position = dataEnd
                    continue
                }
            }

            if (name.isNotEmpty()) {
                parseFrame(name, body, dataStart, dataEnd, major, entries, 0, options)
            }
            position = dataEnd
        }

        return RawTagBlock(format, fileOffset, total.toLong(), entries.snapshot())
    }

    /** The frame identifier, or an empty string when the bytes are not one. */
    private fun frameName(body: ByteArray, at: Int, length: Int): String {
        val chars = CharArray(length)
        for (i in 0 until length) {
            val code = body[at + i].toInt() and 0xFF
            val valid = (code in 'A'.code..'Z'.code) || (code in '0'.code..'9'.code)
            if (!valid) return ""
            chars[i] = code.toChar()
        }
        return chars.concatToString()
    }

    private fun parseFrame(
        name: String,
        body: ByteArray,
        start: Int,
        end: Int,
        major: Int,
        into: MutableList<RawTagEntry>,
        chapterDepth: Int,
        options: DecodeOptions,
    ) {
        if (end <= start) return
        val carriesEncoding = name.startsWith("T") ||
            name == "WXX" ||
            name == "WXXX" ||
            name == "COM" ||
            name == "COMM" ||
            name == "ULT" ||
            name == "USLT" ||
            name == "PIC" ||
            name == "APIC"
        if (carriesEncoding) {
            val encoding = body[start].toInt() and 0xFF
            val highest = if (major == 4) TagText.UTF8 else TagText.UTF16_BOM
            if (encoding !in TagText.LATIN1..highest) return
        }
        when {
            // Text frames, which is most of them. TXXX carries its own key.
            name == "TXX" || name == "TXXX" ->
                readUserText(name, body, start, end, into, options)

            name.startsWith("T") -> {
                val encoding = body[start].toInt() and 0xFF
                forEachTextValue(body, start + 1, end, encoding, major, options) { value ->
                    if (value.isNotEmpty()) into.add(RawTagEntry(name, text = value))
                }
            }

            name == "WXX" || name == "WXXX" ->
                readUserUrl(name, body, start, end, into, options)

            // The other URL frames hold one Latin-1 string and nothing else.
            name.startsWith("W") -> {
                into.add(
                    RawTagEntry(
                        name,
                        text = TagText.decodeLatin1(body, start, end - start, options),
                    ),
                )
            }

            name == "COM" || name == "COMM" || name == "ULT" || name == "USLT" ->
                readCommentLike(name, body, start, end, into, options)

            name == "PIC" || name == "APIC" ->
                readPicture(name, body, start, end, into, options)

            name == "UFI" || name == "UFID" ->
                readUniqueFileId(name, body, start, end, into, options)

            name == "CHAP" ->
                readChapter(name, body, start, end, major, into, chapterDepth, options)

            name == "CTOC" -> {
                // The table of contents only orders chapters that are already
                // in their own frames, so it is kept raw and not modelled.
                into.add(RawTagEntry(name, binary = body.copyOfRange(start, end)))
            }

            else -> into.add(RawTagEntry(name, binary = body.copyOfRange(start, end)))
        }
    }

    /**
     * Splits the values of a text frame.
     *
     * Version 2.4 allows several values in one frame, separated by the
     * encoding's terminator. Earlier versions hold one value, and a null in
     * the middle of one of those is a writer's mistake rather than a
     * separator, so only version 2.4 splits.
     */
    private inline fun forEachTextValue(
        body: ByteArray,
        start: Int,
        end: Int,
        encoding: Int,
        major: Int,
        options: DecodeOptions,
        consume: (String) -> Unit,
    ) {
        if (major < 4) {
            consume(TagText.decode(body, start, end - start, encoding, options))
            return
        }
        var position = start
        while (position < end) {
            val terminator = TagText.findTerminator(body, position, end, encoding)
            consume(TagText.decode(body, position, terminator - position, encoding, options))
            position = terminator + TagText.terminatorSize(encoding)
        }
    }

    private fun readUserText(
        name: String,
        body: ByteArray,
        start: Int,
        end: Int,
        into: MutableList<RawTagEntry>,
        options: DecodeOptions,
    ) {
        val encoding = body[start].toInt() and 0xFF
        val descriptionEnd = TagText.findTerminator(body, start + 1, end, encoding)
        val description = TagText.decode(
            body,
            start + 1,
            descriptionEnd - start - 1,
            encoding,
            options,
        )
        val valueStart = descriptionEnd + TagText.terminatorSize(encoding)
        if (valueStart > end) return
        val value = TagText.decode(body, valueStart, end - valueStart, encoding, options)
        into.add(RawTagEntry(name, text = value, description = description))
    }

    private fun readUserUrl(
        name: String,
        body: ByteArray,
        start: Int,
        end: Int,
        into: MutableList<RawTagEntry>,
        options: DecodeOptions,
    ) {
        val encoding = body[start].toInt() and 0xFF
        val descriptionEnd = TagText.findTerminator(body, start + 1, end, encoding)
        val description = TagText.decode(
            body,
            start + 1,
            descriptionEnd - start - 1,
            encoding,
            options,
        )
        val valueStart = descriptionEnd + TagText.terminatorSize(encoding)
        if (valueStart > end) return
        // The URL itself is always Latin-1, whatever the description used.
        val value = TagText.decodeLatin1(body, valueStart, end - valueStart, options)
        into.add(RawTagEntry(name, text = value, description = description))
    }

    /** Comments and unsynchronised lyrics share a layout. */
    private fun readCommentLike(
        name: String,
        body: ByteArray,
        start: Int,
        end: Int,
        into: MutableList<RawTagEntry>,
        options: DecodeOptions,
    ) {
        if (end - start < 4) return
        val encoding = body[start].toInt() and 0xFF
        val language = TagText.decodeLatin1(body, start + 1, 3, options)
        val descriptionEnd = TagText.findTerminator(body, start + 4, end, encoding)
        val description = TagText.decode(
            body,
            start + 4,
            descriptionEnd - start - 4,
            encoding,
            options,
        )
        val valueStart = descriptionEnd + TagText.terminatorSize(encoding)
        if (valueStart > end) return
        val value = TagText.decode(body, valueStart, end - valueStart, encoding, options)
        into.add(RawTagEntry(name, text = value, language = language, description = description))
    }

    private fun readUniqueFileId(
        name: String,
        body: ByteArray,
        start: Int,
        end: Int,
        into: MutableList<RawTagEntry>,
        options: DecodeOptions,
    ) {
        val ownerEnd = TagText.findTerminator(body, start, end, TagText.LATIN1)
        val owner = TagText.decodeLatin1(body, start, ownerEnd - start, options)
        val valueStart = ownerEnd + 1
        if (valueStart >= end) return
        // The identifier is bytes by specification and text in every file that
        // uses it, so it is offered as both.
        val raw = body.copyOfRange(valueStart, end)
        into.add(
            RawTagEntry(
                name,
                text = TagText.decodeLatin1(raw, 0, raw.size, options),
                binary = raw,
                description = owner,
            ),
        )
    }

    private fun readPicture(
        name: String,
        body: ByteArray,
        start: Int,
        end: Int,
        into: MutableList<RawTagEntry>,
        options: DecodeOptions,
    ) {
        val encoding = body[start].toInt() and 0xFF
        var position = start + 1
        val mimeType: String
        if (name == "PIC") {
            // Version 2.2 stores a three-character image format such as JPG.
            if (position + 3 > end) return
            val kind = TagText.decodeLatin1(body, position, 3, options)
            mimeType = when (kind.uppercase()) {
                "JPG" -> "image/jpeg"
                "PNG" -> "image/png"
                "GIF" -> "image/gif"
                "BMP" -> "image/bmp"
                "WEB" -> "image/webp"
                else -> "image/$kind"
            }
            position += 3
        } else {
            val mimeEnd = TagText.findTerminator(body, position, end, TagText.LATIN1)
            val stated = TagText.decodeLatin1(body, position, mimeEnd - position, options)
            // Writers that put "JPEG" or "PNG" here instead of a media type
            // are common enough to be worth understanding.
            mimeType = when {
                stated.contains('/') -> stated
                stated.equals("JPEG", true) || stated.equals("JPG", true) -> "image/jpeg"
                stated.equals("PNG", true) -> "image/png"
                stated.isEmpty() -> "image/jpeg"
                else -> "image/${stated.lowercase()}"
            }
            position = mimeEnd + 1
        }
        if (position >= end) return
        val pictureType = body[position].toInt() and 0xFF
        position += 1
        val descriptionEnd = TagText.findTerminator(body, position, end, encoding)
        val description = TagText.decode(
            body,
            position,
            descriptionEnd - position,
            encoding,
            options,
        )
        val dataStart = descriptionEnd + TagText.terminatorSize(encoding)
        if (dataStart >= end) return
        into.add(
            RawTagEntry(
                name,
                text = mimeType,
                binary = body.copyOfRange(dataStart, end),
                description = description,
                language = pictureType.toString(),
            ),
        )
    }

    /**
     * Reads a chapter frame, which carries its own frames inside it.
     *
     * The layout is an identifier, four timing fields, and then a run of
     * ordinary frames that describe the chapter. The nested title is the one
     * worth having.
     */
    private fun readChapter(
        name: String,
        body: ByteArray,
        start: Int,
        end: Int,
        major: Int,
        into: MutableList<RawTagEntry>,
        chapterDepth: Int,
        options: DecodeOptions,
    ) {
        if (chapterDepth >= MAX_CHAPTER_DEPTH) return
        val idEnd = TagText.findTerminator(body, start, end, TagText.LATIN1)
        val id = TagText.decodeLatin1(body, start, idEnd - start, options)
        var position = idEnd + 1
        if (position < 0 || position > end - 16) return
        val startTime = readUInt32(body, position).toLong() and 0xFFFF_FFFFL
        val endTime = readUInt32(body, position + 4).toLong() and 0xFFFF_FFFFL
        position += 16

        val nested = EntryList((into as? EntryList)?.budget ?: EntryBudget(options.maxAllocationBytes))
        while (position <= end - 10) {
            if (body[position].toInt() == 0) break
            val nestedName = frameName(body, position, 4)
            if (nestedName.isEmpty()) break
            val size = if (major == 4) {
                readSyncsafe(body, position + 4) ?: break
            } else {
                readUInt32(body, position + 4)
            }
            val nestedStart = position + 10
            if (size < 0 || size > end - nestedStart) break
            val nestedEnd = nestedStart + size
            parseFrame(
                nestedName,
                body,
                nestedStart,
                nestedEnd,
                major,
                nested,
                chapterDepth + 1,
                options,
            )
            position = nestedEnd
        }

        val title = nested.firstOrNull { it.key == "TIT2" || it.key == "TT2" }?.text
        // Chapters are carried as one entry whose text is the title and whose
        // description holds the timing, so the raw view stays flat.
        into.add(
            RawTagEntry(
                name,
                text = title,
                description = id,
                language = "$startTime..$endTime",
            ),
        )
    }

    /**
     * Metadata entries are objects in addition to the bytes already held by
     * the tag body. Charge a conservative amount for each one so a tag made
     * of millions of one-byte frames reaches the caller's allocation ceiling
     * through a typed failure instead of exhausting the heap.
     */
    private class EntryBudget(private val limit: Long) {
        private var count = 0L

        fun consume() {
            val requested = if (count >= Long.MAX_VALUE / 64L) {
                Long.MAX_VALUE
            } else {
                (count + 1L) * 64L
            }
            if (requested > limit) {
                throw AllocationGuardException(
                    "remembering ID3v2 tag entries",
                    requested,
                    limit,
                )
            }
            count++
        }
    }

    private class EntryList(
        internal val budget: EntryBudget,
        private val values: ArrayList<RawTagEntry> = ArrayList(),
    ) : MutableList<RawTagEntry> by values {

        override val size: Int get() = values.size

        override fun get(index: Int): RawTagEntry = values[index]

        override fun add(element: RawTagEntry): Boolean {
            budget.consume()
            return values.add(element)
        }

        override fun add(index: Int, element: RawTagEntry) {
            budget.consume()
            values.add(index, element)
        }

        override fun addAll(elements: Collection<RawTagEntry>): Boolean {
            repeat(elements.size) { budget.consume() }
            return values.addAll(elements)
        }

        override fun addAll(index: Int, elements: Collection<RawTagEntry>): Boolean {
            repeat(elements.size) { budget.consume() }
            return values.addAll(index, elements)
        }

        fun snapshot(): List<RawTagEntry> = values.toList()
    }

    /** Turns the chapter entries of a block into the modelled form. */
    fun chaptersOf(block: RawTagBlock): List<TagChapter> {
        val chapters = ArrayList<TagChapter>()
        for (entry in block.entries) {
            if (entry.key != "CHAP") continue
            val timing = entry.language ?: continue
            val separator = timing.indexOf("..")
            if (separator <= 0) continue
            val start = timing.substring(0, separator).toLongOrNull() ?: continue
            val finish = timing.substring(separator + 2).toLongOrNull() ?: -1L
            val id = entry.description ?: continue
            val end = if (finish == 0xFFFF_FFFFL) -1L else finish
            if (id.isEmpty() || start < 0 || (end != -1L && end < start)) continue
            chapters.add(
                TagChapter(
                    id = id,
                    startMillis = start,
                    // A chapter that runs to the end of the file writes the
                    // largest value the field can hold, which is not a time.
                    endMillis = end,
                    title = entry.text,
                ),
            )
        }
        return chapters.sortedBy { it.startMillis }
    }

    /** Turns the picture entries of a block into the modelled form. */
    fun picturesOf(block: RawTagBlock): List<TagPicture> {
        val pictures = ArrayList<TagPicture>()
        for (entry in block.entries) {
            if (entry.key != "APIC" && entry.key != "PIC") continue
            val data = entry.binary ?: continue
            pictures.add(
                TagPicture(
                    type = PictureType.fromCode(entry.language?.toIntOrNull() ?: 3),
                    mimeType = entry.text ?: "image/jpeg",
                    description = entry.description ?: "",
                    bytes = data,
                ),
            )
        }
        return pictures
    }

    /**
     * Skips the extended header and returns where the frames start.
     *
     * The two versions that have one disagree about whether its size field
     * counts itself, which is the kind of detail that makes a reader written
     * against one version fail on the other.
     */
    private fun skipExtendedHeader(body: ByteArray, major: Int): Int {
        if (body.size < 4) return 0
        return if (major == 4) {
            // Version 2.4: syncsafe, and the size includes itself.
            val size = readSyncsafe(body, 0) ?: return 0
            if (size in 6..body.size) size else 0
        } else {
            // Version 2.3: a plain integer, and the size excludes itself.
            val size = readUInt32(body, 0)
            if (size in 6..(body.size - 4)) size + 4 else 0
        }
    }

    /**
     * Undoes unsynchronisation, which replaces every `FF 00` with `FF`.
     *
     * The transformation exists so that a player looking for an MPEG frame
     * sync never finds one inside a tag. It has to be undone before the frames
     * can be read.
     */
    fun undoUnsynchronisation(bytes: ByteArray): ByteArray {
        var found = false
        var i = 0
        while (i + 1 < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) == 0xFF && bytes[i + 1].toInt() == 0) {
                found = true
                break
            }
            i++
        }
        if (!found) return bytes

        val out = ByteArray(bytes.size)
        var written = 0
        var read = 0
        while (read < bytes.size) {
            val b = bytes[read]
            out[written++] = b
            read++
            if ((b.toInt() and 0xFF) == 0xFF && read < bytes.size && bytes[read].toInt() == 0) {
                read++
            }
        }
        return out.copyOf(written)
    }

    /**
     * Reads a syncsafe integer, which holds seven bits in each byte.
     *
     * Returns null when a byte has its top bit set, because that is not a
     * syncsafe integer and reading it as one would give a wrong size.
     */
    fun readSyncsafe(bytes: ByteArray, at: Int): Int? {
        if (at < 0 || at > bytes.size - 4) return null
        var value = 0
        for (i in 0 until 4) {
            val b = bytes[at + i].toInt() and 0xFF
            if (b and 0x80 != 0) return null
            value = (value shl 7) or b
        }
        return value
    }

    private fun readUInt32(bytes: ByteArray, at: Int): Int {
        if (at < 0 || at > bytes.size - 4) return -1
        return ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
    }

    private fun readUInt24(bytes: ByteArray, at: Int): Int {
        if (at < 0 || at > bytes.size - 3) return -1
        return ((bytes[at].toInt() and 0xFF) shl 16) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            (bytes[at + 2].toInt() and 0xFF)
    }
}

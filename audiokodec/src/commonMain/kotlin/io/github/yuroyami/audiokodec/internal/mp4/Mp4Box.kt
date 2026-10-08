package io.github.yuroyami.audiokodec.internal.mp4

import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.TruncatedStreamException

/**
 * One box of an ISO base media file, which is what MP4, M4A, M4B, 3GP and
 * QuickTime files are made of.
 *
 * Every box starts with its size and a four-character type, and a box either
 * holds data or holds more boxes, so the whole file is a tree. A size of one
 * means a 64-bit size follows, for a box past 4 GiB, and a size of zero means
 * the box runs to the end of whatever holds it, which only the last box of a
 * file does in practice.
 *
 * Written from Apple's QuickTime File Format specification.
 */
internal class Mp4Box(
    val type: String,
    /** Offset of the box's first byte, in the coordinates of whatever holds it. */
    val offset: Long,
    /** Bytes of size and type before the body. */
    val headerBytes: Int,
    /** Bytes the whole box occupies, header included. */
    val size: Long,
    /** A zero-sized top-level box occupies the remainder of its source. */
    val extendsToEnd: Boolean = false,
) {
    /** Offset of the first byte of the body. */
    val bodyOffset: Long get() = offset + headerBytes

    /** Bytes in the body. */
    val bodySize: Long get() = size - headerBytes

    /** Offset of the first byte after the box. */
    val end: Long get() = offset + size

    override fun toString(): String = "'$type' at $offset, $size bytes"

    companion object {
        /**
         * Reads the header of the box at [at] in [bytes], which holds the
         * range up to [limit]. [base] is where [bytes] sits in the file, for
         * error messages. Returns null when fewer than eight bytes remain,
         * which is how a list of boxes ends.
         */
        fun at(bytes: ByteArray, at: Int, limit: Int, base: Long): Mp4Box? {
            if (limit - at < 8) return null
            val small = readU32(bytes, at)
            val type = typeOf(bytes, at + 4)
            var headerBytes = 8
            val size = when (small) {
                1L -> {
                    if (limit - at < 16) {
                        throw TruncatedStreamException("the box '$type' is cut off inside its 64-bit size", base + at)
                    }
                    headerBytes = 16
                    readU64(bytes, at + 8)
                }
                0L -> (limit - at).toLong()
                else -> small
            }
            if (size < headerBytes) {
                throw CorruptStreamException("the box '$type' states $size bytes, less than its own header", base + at)
            }
            if (size > limit - at) {
                throw CorruptStreamException(
                    "the box '$type' states $size bytes and only ${limit - at} remain in what holds it",
                    base + at,
                )
            }
            return Mp4Box(type, at.toLong(), headerBytes, size)
        }

        /** The boxes in `bytes[from until to]`, in order. */
        fun children(bytes: ByteArray, from: Int, to: Int, base: Long): List<Mp4Box> {
            val out = ArrayList<Mp4Box>()
            var at = from
            while (true) {
                val box = at(bytes, at, to, base) ?: break
                out += box
                at = box.end.toInt()
            }
            return out
        }

        /** The four characters at [at], read as Latin-1 so that `©` comes through. */
        fun typeOf(bytes: ByteArray, at: Int): String {
            val chars = CharArray(4) { (bytes[at + it].toInt() and 0xFF).toChar() }
            return chars.concatToString()
        }

        fun readU16(bytes: ByteArray, at: Int): Int =
            ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

        fun readU32(bytes: ByteArray, at: Int): Long =
            ((bytes[at].toLong() and 0xFF) shl 24) or
                ((bytes[at + 1].toLong() and 0xFF) shl 16) or
                ((bytes[at + 2].toLong() and 0xFF) shl 8) or
                (bytes[at + 3].toLong() and 0xFF)

        fun readI32(bytes: ByteArray, at: Int): Int = readU32(bytes, at).toInt()

        fun readU64(bytes: ByteArray, at: Int): Long = (readU32(bytes, at) shl 32) or readU32(bytes, at + 4)
    }
}

/**
 * Reads big-endian fields out of one box's body, refusing to read past it.
 *
 * MP4 fields are all big-endian, and every box states its own size, so a
 * field that runs past the end of its box is damage rather than something
 * to read from the next box. [base] is where [bytes] sits in the file, so
 * that an error can name the byte.
 */
internal class Mp4Cursor(
    private val bytes: ByteArray,
    from: Int,
    private val end: Int,
    private val base: Long,
    /** What the box is, for error messages. */
    private val what: String,
) {
    var position: Int = from
        private set

    val remaining: Int get() = end - position

    private fun need(count: Int) {
        if (count < 0 || count > end - position) {
            throw CorruptStreamException("$what ends before a field it promises", base + position)
        }
    }

    fun skip(count: Int) {
        need(count)
        position += count
    }

    fun u8(): Int {
        need(1)
        return bytes[position++].toInt() and 0xFF
    }

    fun u16(): Int {
        need(2)
        return Mp4Box.readU16(bytes, position).also { position += 2 }
    }

    fun i16(): Int = u16().toShort().toInt()

    fun u24(): Int {
        need(3)
        val value = ((bytes[position].toInt() and 0xFF) shl 16) or Mp4Box.readU16(bytes, position + 1)
        position += 3
        return value
    }

    fun u32(): Long {
        need(4)
        return Mp4Box.readU32(bytes, position).also { position += 4 }
    }

    fun i32(): Int = u32().toInt()

    fun u64(): Long {
        need(8)
        return Mp4Box.readU64(bytes, position).also { position += 8 }
    }

    /** A 32-bit field that counts something, refused when it would not fit an `Int`. */
    fun count(): Int {
        val value = u32()
        if (value > Int.MAX_VALUE) throw CorruptStreamException("$what states a count of $value", base + position - 4)
        return value.toInt()
    }

    fun fourCc(): String {
        need(4)
        return Mp4Box.typeOf(bytes, position).also { position += 4 }
    }

    fun bytes(count: Int): ByteArray {
        need(count)
        return bytes.copyOfRange(position, position + count).also { position += count }
    }

    /** Fails unless [count] entries of [entryBytes] each fit in what is left. */
    fun requireEntries(count: Int, entryBytes: Int) {
        if (count.toLong() * entryBytes > remaining) {
            throw CorruptStreamException(
                "$what states $count entries of $entryBytes bytes and holds only $remaining bytes",
                base + position,
            )
        }
    }
}

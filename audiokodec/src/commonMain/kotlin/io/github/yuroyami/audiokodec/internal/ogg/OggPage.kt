package io.github.yuroyami.audiokodec.internal.ogg

/**
 * The page checksum Ogg uses.
 *
 * It is a 32-bit cyclic redundancy check with the polynomial `0x04C11DB7`, no
 * reflection at either end, an initial value of zero and no final inversion.
 * That combination is unusual enough that a general-purpose CRC-32 will not
 * match it, which is worth knowing before reaching for one.
 *
 * The checksum covers the whole page with its own four bytes zeroed.
 */
internal object OggCrc {

    private val T0: IntArray = IntArray(256) { index ->
        var value = index shl 24
        repeat(8) {
            value = if (value and 0x8000_0000.toInt() != 0) {
                (value shl 1) xor 0x04C1_1DB7
            } else {
                value shl 1
            }
        }
        value
    }

    // Tk[b] is the checksum of byte b followed by k zero bytes. Together the
    // eight tables fold eight bytes into the checksum in one step.
    private val T1 = shifted(T0)
    private val T2 = shifted(T1)
    private val T3 = shifted(T2)
    private val T4 = shifted(T3)
    private val T5 = shifted(T4)
    private val T6 = shifted(T5)
    private val T7 = shifted(T6)

    private fun shifted(previous: IntArray): IntArray =
        IntArray(256) { (previous[it] shl 8) xor T0[previous[it] ushr 24] }

    fun of(bytes: ByteArray, from: Int, to: Int): Int = update(0, bytes, from, to)

    /** Continues the checksum [crc] over `from until to`. */
    fun update(crc: Int, bytes: ByteArray, from: Int, to: Int): Int {
        var value = crc
        var i = from
        while (i <= to - 8) {
            val word = value xor
                ((bytes[i].toInt() and 0xFF) shl 24) xor
                ((bytes[i + 1].toInt() and 0xFF) shl 16) xor
                ((bytes[i + 2].toInt() and 0xFF) shl 8) xor
                (bytes[i + 3].toInt() and 0xFF)
            value = T7[word ushr 24] xor
                T6[(word ushr 16) and 0xFF] xor
                T5[(word ushr 8) and 0xFF] xor
                T4[word and 0xFF] xor
                T3[bytes[i + 4].toInt() and 0xFF] xor
                T2[bytes[i + 5].toInt() and 0xFF] xor
                T1[bytes[i + 6].toInt() and 0xFF] xor
                T0[bytes[i + 7].toInt() and 0xFF]
            i += 8
        }
        while (i < to) {
            value = (value shl 8) xor T0[((value ushr 24) xor (bytes[i].toInt() and 0xFF)) and 0xFF]
            i++
        }
        return value
    }

    /**
     * The checksum of a page whose own checksum field is not yet zero.
     *
     * A page read from a file still holds the value the writer computed, so
     * checking it means computing over the same bytes with those four treated
     * as zero. Copying the page to do that would double the work, so the four
     * bytes are skipped instead.
     */
    fun ofPage(bytes: ByteArray, from: Int, to: Int, checksumAt: Int): Int {
        val skipFrom = checksumAt.coerceIn(from, to)
        val skipTo = (checksumAt + 4).coerceIn(skipFrom, to)
        var crc = update(0, bytes, from, skipFrom)
        repeat(skipTo - skipFrom) { crc = (crc shl 8) xor T0[crc ushr 24] }
        return update(crc, bytes, skipTo, to)
    }
}

/**
 * One Ogg page: the unit a file is physically made of.
 *
 * A page carries part of one or more packets, belongs to exactly one logical
 * stream, and states a granule position that means whatever the codec inside
 * says it means. Packets are the unit a codec cares about, and they do not line
 * up with pages: one page can hold twenty small packets, and one large packet
 * can span several pages.
 *
 * Written from RFC 3533.
 */
internal class OggPage(
    /** Byte offset of the page's capture pattern. */
    val offset: Long,
    /** Header type flags: continued, beginning of stream, end of stream. */
    val flags: Int,
    /**
     * Position in the stream that this page reaches, in units the codec
     * defines.
     *
     * For every audio codec Ogg carries it counts samples. A value of -1 means
     * no packet finishes on this page, so the position is unchanged.
     */
    val granulePosition: Long,
    /** Which logical stream the page belongs to. */
    val serialNumber: Int,
    /** Position of the page within its logical stream, counting from zero. */
    val sequenceNumber: Int,
    /** The lacing values, which say where the packets inside end. */
    val segments: IntArray,
    /** The page's payload, without the header. */
    val payload: ByteArray,
    /** Total bytes the page occupies, header included. */
    val byteLength: Int,
) {
    /** True when the first packet on this page continues one from the last. */
    val isContinued: Boolean get() = flags and FLAG_CONTINUED != 0

    /** True when this is the first page of its logical stream. */
    val isFirst: Boolean get() = flags and FLAG_FIRST != 0

    /** True when this is the last page of its logical stream. */
    val isLast: Boolean get() = flags and FLAG_LAST != 0

    /**
     * True when a packet ends on this page.
     *
     * A page whose last lacing value is 255 leaves its final packet open, and
     * the granule position of such a page is not a position.
     */
    val endsAPacket: Boolean
        get() = segments.isNotEmpty() && segments.last() != 255

    companion object {
        const val FLAG_CONTINUED = 0x01
        const val FLAG_FIRST = 0x02
        const val FLAG_LAST = 0x04

        /** Bytes in a page header before the segment table. */
        const val HEADER_BYTES = 27

        /** The largest a page can be: a full segment table of full segments. */
        const val MAX_PAGE_BYTES = HEADER_BYTES + 255 + 255 * 255

        val CAPTURE = byteArrayOf(
            'O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte(),
        )
    }
}

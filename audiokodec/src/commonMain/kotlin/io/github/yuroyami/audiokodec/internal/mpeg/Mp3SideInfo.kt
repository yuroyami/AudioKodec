package io.github.yuroyami.audiokodec.internal.mpeg

import io.github.yuroyami.audiokodec.internal.bits.BitReaderMsb

/**
 * What Layer III puts between the frame header and the coded audio.
 *
 * Layer III does not keep a frame's audio inside that frame. It keeps a shared
 * pool of bits instead, so that a hard passage can borrow room from the quiet
 * one before it, and the side information is what says where in that pool each
 * granule's data starts and how much of it there is. Everything else here
 * describes how to read the granule once it has been found: which Huffman
 * tables, which window, how the scalefactors were packed.
 *
 * MPEG-1 puts two granules in a frame and MPEG-2 puts one, and the two spell
 * the side information differently, which is why the parse is split.
 */
internal class Mp3SideInfo(
    /** Bytes before this frame's main data where the first granule starts. */
    val mainDataBegin: Int,
    /** Scalefactor selection: whether a band's scalefactors carry over. */
    val scfsi: Array<BooleanArray>,
    val granules: Array<Array<Granule>>,
) {

    /** One granule of one channel. */
    class Granule(
        /** Bits this granule's scalefactors and Huffman data occupy together. */
        val part2And3Length: Int,
        /** Value pairs coded by the big-value tables. */
        val bigValues: Int,
        val globalGain: Int,
        val scalefacCompress: Int,
        val windowSwitching: Boolean,
        /** 0 normal, 1 start, 2 short, 3 end. */
        val blockType: Int,
        val mixedBlock: Boolean,
        val tableSelect: IntArray,
        val subblockGain: IntArray,
        val region0Count: Int,
        val region1Count: Int,
        val preflag: Boolean,
        val scalefacScale: Int,
        val count1TableSelect: Int,
    ) {
        val isShort: Boolean get() = windowSwitching && blockType == 2
        val isMixed: Boolean get() = isShort && mixedBlock
    }

    companion object {

        fun parse(bytes: ByteArray, offset: Int, header: MpegFrameHeader): Mp3SideInfo {
            val reader = BitReaderMsb(bytes, offset, header.sideInfoBytes)
            val channels = header.channels
            return if (header.version == 1) {
                parseVersion1(reader, channels)
            } else {
                parseVersion2(reader, channels)
            }
        }

        private fun parseVersion1(reader: BitReaderMsb, channels: Int): Mp3SideInfo {
            val mainDataBegin = reader.readBits(9)
            reader.skipBits(if (channels == 1) 5 else 3)
            val scfsi = Array(channels) { BooleanArray(4) }
            for (channel in 0 until channels) {
                for (band in 0 until 4) scfsi[channel][band] = reader.readBits(1) == 1
            }
            val granules = Array(2) { Array(channels) { readGranule(reader, version1 = true) } }
            return Mp3SideInfo(mainDataBegin, scfsi, granules)
        }

        private fun parseVersion2(reader: BitReaderMsb, channels: Int): Mp3SideInfo {
            val mainDataBegin = reader.readBits(8)
            reader.skipBits(if (channels == 1) 1 else 2)
            // MPEG-2 has one granule and no scalefactor selection, because
            // there is no second granule to carry anything over to.
            val granules = Array(1) { Array(channels) { readGranule(reader, version1 = false) } }
            return Mp3SideInfo(mainDataBegin, Array(channels) { BooleanArray(4) }, granules)
        }

        private fun readGranule(reader: BitReaderMsb, version1: Boolean): Granule {
            val part2And3Length = reader.readBits(12)
            val bigValues = reader.readBits(9)
            val globalGain = reader.readBits(8)
            val scalefacCompress = reader.readBits(if (version1) 4 else 9)
            val windowSwitching = reader.readBits(1) == 1

            var blockType = 0
            var mixedBlock = false
            val tableSelect = IntArray(3)
            val subblockGain = IntArray(3)
            var region0Count = 0
            var region1Count = 0

            if (windowSwitching) {
                blockType = reader.readBits(2)
                mixedBlock = reader.readBits(1) == 1
                tableSelect[0] = reader.readBits(5)
                tableSelect[1] = reader.readBits(5)
                for (window in 0 until 3) subblockGain[window] = reader.readBits(3)
                // A switched granule has two regions rather than three, and
                // where the first ends is fixed rather than coded.
                region0Count = if (blockType == 2 && !mixedBlock) 8 else 7
                region1Count = 20 - region0Count
            } else {
                for (region in 0 until 3) tableSelect[region] = reader.readBits(5)
                region0Count = reader.readBits(4)
                region1Count = reader.readBits(3)
            }

            val preflag = if (version1) reader.readBits(1) == 1 else false
            val scalefacScale = reader.readBits(1)
            val count1TableSelect = reader.readBits(1)

            return Granule(
                part2And3Length = part2And3Length,
                bigValues = bigValues,
                globalGain = globalGain,
                scalefacCompress = scalefacCompress,
                windowSwitching = windowSwitching,
                blockType = blockType,
                mixedBlock = mixedBlock,
                tableSelect = tableSelect,
                subblockGain = subblockGain,
                region0Count = region0Count,
                region1Count = region1Count,
                preflag = preflag,
                scalefacScale = scalefacScale,
                count1TableSelect = count1TableSelect,
            )
        }
    }
}

/**
 * The pool of bits a Layer III frame draws its audio from.
 *
 * A frame's audio is not inside that frame. The encoder writes each granule
 * wherever there was room, up to 511 bytes earlier, and the frame's
 * `main_data_begin` says how far back to look. That is what lets a constant
 * bitrate file spend more bits on a hard passage than one frame can hold.
 *
 * The consequence for a decoder is that a frame cannot be decoded on its own,
 * and that seeking has to decode a frame or two before the target to fill this
 * up again. The first frame after a seek is therefore discarded rather than
 * played.
 */
internal class Mp3Reservoir {

    private val buffer = ByteArray(CAPACITY)
    private var length = 0

    /** Bytes currently held. */
    val size: Int get() = length

    fun clear() {
        length = 0
    }

    /** Adds a frame's main data to the pool, dropping the oldest if it is full. */
    fun append(bytes: ByteArray, offset: Int, count: Int) {
        if (count <= 0) return
        if (count >= CAPACITY) {
            bytes.copyInto(buffer, 0, offset + count - CAPACITY, offset + count)
            length = CAPACITY
            return
        }
        if (length + count > CAPACITY) {
            val drop = length + count - CAPACITY
            buffer.copyInto(buffer, 0, drop, length)
            length -= drop
        }
        bytes.copyInto(buffer, length, offset, offset + count)
        length += count
    }

    /**
     * Assembles the bytes a frame's granules are coded in.
     *
     * [begin] is the frame's `main_data_begin`: how many bytes of the pool,
     * counted back from its end, come before this frame's own main data.
     * Returns null when the pool does not hold that much, which is what the
     * first frames of a file and the first frame after a seek look like.
     */
    fun assemble(begin: Int, frameData: ByteArray, offset: Int, count: Int, into: ByteArray): Int? {
        if (begin > length) return null
        var written = 0
        if (begin > 0) {
            buffer.copyInto(into, 0, length - begin, length)
            written = begin
        }
        val take = count.coerceAtMost(into.size - written)
        if (take > 0) {
            frameData.copyInto(into, written, offset, offset + take)
            written += take
        }
        return written
    }

    private companion object {
        /** The most a frame may reach back, which the format fixes at 511. */
        const val CAPACITY = 511 + 1
    }
}

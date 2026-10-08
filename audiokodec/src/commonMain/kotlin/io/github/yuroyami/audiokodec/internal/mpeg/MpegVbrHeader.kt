package io.github.yuroyami.audiokodec.internal.mpeg

/**
 * The bookkeeping an encoder hides inside the first frame.
 *
 * MPEG audio states nothing about a file as a whole: no length, no frame count,
 * nothing to seek by. Encoders worked around that by writing a silent first
 * frame whose payload holds the numbers instead. Three spellings exist, and
 * between them they cover everything in the wild.
 *
 * `Xing` and `Info` are the same structure under two names, the second meaning
 * the file is constant bitrate. `VBRI` is Fraunhofer's, and sits at a different
 * offset. On top of either, LAME appends its own block, which is where a file's
 * gapless information lives: the samples of silence the encoder added at each
 * end, which have to be removed or every track gains a click.
 *
 * The frame holding this is not audio and must not be decoded.
 */
internal class MpegVbrHeader(
    /** True when the header says the file is constant bitrate. */
    val isConstantBitrate: Boolean,
    /** Frames the file holds, or -1. The header frame is not one of them. */
    val frameCount: Int,
    /** Bytes from the VBR header frame through the last audio frame, unsigned 32-bit, or -1. */
    val byteCount: Long,
    /** 100 entries, each a 256th of the file at a hundredth of the duration. */
    val tableOfContents: ByteArray?,
    /** Samples the encoder put in front of the recording, or -1. */
    val encoderDelay: Int,
    /** Samples the encoder put after it, or -1. */
    val encoderPadding: Int,
    /** The encoder's own name, when it wrote one. */
    val encoder: String?,
) {

    /** True when the gapless numbers are both present. */
    val hasGaplessInfo: Boolean get() = encoderDelay >= 0 && encoderPadding >= 0

    companion object {

        /**
         * A decoder's own delay, in samples.
         *
         * The polyphase synthesis filter of every MPEG audio layer holds 529
         * samples before it produces its first real output, and LAME's stored
         * delay does not include it. Every decoder that plays gapless adds it.
         */
        const val DECODER_DELAY = 529

        /**
         * Reads whatever the first frame holds, or returns null.
         *
         * [frame] starts at the frame header.
         */
        fun parse(frame: ByteArray, header: MpegFrameHeader): MpegVbrHeader? {
            xingAt(frame, 4 + header.sideInfoBytes)?.let { return it }
            // VBRI ignores the side information and always sits 32 bytes after
            // the header, which is why it needs its own offset.
            return vbriAt(frame, 4 + 32)
        }

        private fun xingAt(frame: ByteArray, at: Int): MpegVbrHeader? {
            if (at + 8 > frame.size) return null
            val constant = when {
                matches(frame, at, "Xing") -> false
                matches(frame, at, "Info") -> true
                else -> return null
            }
            val flags = readInt(frame, at + 4)
            var cursor = at + 8
            var frames = -1
            var bytes = -1L
            var toc: ByteArray? = null
            if (flags and 0x1 != 0) {
                if (cursor + 4 > frame.size) return null
                frames = readInt(frame, cursor)
                cursor += 4
            }
            if (flags and 0x2 != 0) {
                if (cursor + 4 > frame.size) return null
                bytes = readInt(frame, cursor).toLong() and 0xFFFF_FFFFL
                cursor += 4
            }
            if (flags and 0x4 != 0) {
                if (cursor + 100 > frame.size) return null
                toc = frame.copyOfRange(cursor, cursor + 100)
                cursor += 100
            }
            if (flags and 0x8 != 0) cursor += 4

            var delay = -1
            var padding = -1
            var encoder: String? = null
            // LAME and the encoders that copied it append a block of their own
            // here. Its first nine bytes name the encoder, and the gapless
            // numbers sit twenty-one bytes in.
            if (cursor + 24 <= frame.size) {
                val name = frame.decodeToString(cursor, cursor + 9).trim()
                if (name.isNotEmpty() && name.all { it.code in 32..126 }) {
                    encoder = name
                    val packed = ((frame[cursor + 21].toInt() and 0xFF) shl 16) or
                        ((frame[cursor + 22].toInt() and 0xFF) shl 8) or
                        (frame[cursor + 23].toInt() and 0xFF)
                    val storedDelay = (packed ushr 12) and 0xFFF
                    val storedPadding = packed and 0xFFF
                    // A block of zeroes is an encoder that wrote the field
                    // without filling it in, not a file that needs no trimming.
                    if (storedDelay != 0 || storedPadding != 0) {
                        delay = storedDelay
                        padding = storedPadding
                    }
                }
            }

            return MpegVbrHeader(
                isConstantBitrate = constant,
                frameCount = frames,
                byteCount = bytes,
                tableOfContents = toc,
                encoderDelay = delay,
                encoderPadding = padding,
                encoder = encoder,
            )
        }

        private fun vbriAt(frame: ByteArray, at: Int): MpegVbrHeader? {
            if (at + 26 > frame.size) return null
            if (!matches(frame, at, "VBRI")) return null
            val bytes = readInt(frame, at + 10).toLong() and 0xFFFF_FFFFL
            val frames = readInt(frame, at + 14)
            return MpegVbrHeader(
                // Fraunhofer only ever wrote this on variable bitrate files.
                isConstantBitrate = false,
                frameCount = frames,
                byteCount = bytes,
                // The VBRI table is not the Xing one: its entries are byte
                // counts per run of frames rather than positions in the file,
                // so it is not read as one. A frame count is enough to seek by
                // scanning, which is what a file this rare gets.
                tableOfContents = null,
                encoderDelay = -1,
                encoderPadding = -1,
                encoder = "Fraunhofer",
            )
        }

        private fun matches(bytes: ByteArray, at: Int, text: String): Boolean {
            if (at + text.length > bytes.size) return false
            for (i in text.indices) {
                if ((bytes[at + i].toInt() and 0xFF) != text[i].code) return false
            }
            return true
        }

        private fun readInt(bytes: ByteArray, at: Int): Int {
            if (at + 4 > bytes.size) return -1
            return ((bytes[at].toInt() and 0xFF) shl 24) or
                ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                ((bytes[at + 2].toInt() and 0xFF) shl 8) or
                (bytes[at + 3].toInt() and 0xFF)
        }

    }
}

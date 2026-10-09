package io.github.yuroyami.audiokodec.internal.codec

/**
 * G.726 ADPCM as a block coding: the fewest whole bytes that hold a whole
 * number of code words, one byte of two or four code words at 32 and
 * 16 kbit/s, and three or five bytes of eight at 24 and 40 kbit/s.
 *
 * Containers disagree on the order of the code words inside a byte. WAV
 * files, as ffmpeg writes and reads them, start each byte's first code word
 * at its most significant bit; Sun's AU files start it at the least
 * significant bit, [lsbFirst].
 *
 * The decoder's state runs through the whole stream, so a seek decodes from
 * the start, [SampleCodec.FROM_START], and lands on exactly the samples a
 * continuous decode gives.
 */
internal class G726Codec(private val bits: Int, private val lsbFirst: Boolean) : SampleCodec {
    private val decoder = G726Decoder(bits)
    private val mask = (1 shl bits) - 1

    override val channels: Int = 1
    override val blockBytes: Int = if (bits % 2 == 0) 1 else bits
    override val framesPerBlock: Int = blockBytes * 8 / bits
    override val storedBitDepth: Int? = null
    override val preRollBlocks: Int = SampleCodec.FROM_START

    override fun reset() = decoder.reset()

    // ffmpeg ends a stream after its last whole code word, part way through a block of eight.
    override fun framesInShortBlock(bytes: Int): Int = bytes * 8 / bits

    override fun decode(src: ByteArray, srcOffset: Int, blocks: Int, out: FloatArray, outOffset: Int): Int {
        var output = outOffset
        var input = srcOffset
        repeat(blocks) {
            var buffer = 0
            var buffered = 0
            repeat(blockBytes) {
                val byte = src[input++].toInt() and 0xFF
                if (lsbFirst) buffer = buffer or (byte shl buffered) else buffer = (buffer shl 8) or byte
                buffered += 8
                while (buffered >= bits) {
                    buffered -= bits
                    val code = if (lsbFirst) {
                        (buffer and mask).also { buffer = buffer ushr bits }
                    } else {
                        (buffer ushr buffered) and mask
                    }
                    out[output++] = uniform(decoder.decode(code)) * SCALE
                }
            }
        }
        return blocks * framesPerBlock
    }

    override fun decodeInt(src: ByteArray, srcOffset: Int, blocks: Int, out: IntArray, outOffset: Int): Int =
        throw UnsupportedOperationException("G.726 has no exact integer form")

    internal companion object {
        /** The reconstructed signal is 14-bit uniform PCM. */
        const val SCALE = 1f / 8192f

        /**
         * The reconstructed signal SR limited to 14-bit PCM. An overloaded
         * stream can carry SR past that range, and the Recommendation's
         * 16-bit arithmetic even wraps it; its own PCM output stage
         * saturates such a value to the largest code word, and so does this.
         * The wrapped value of -32768, which that stage reads as zero, is
         * zero here too.
         */
        fun uniform(sr: Int): Int = if (sr == -32768) 0 else sr.coerceIn(-8192, 8191)
    }
}

/*
 * Translated from libspeex 1.2.1, which is Copyright 2002-2008 Xiph.org
 * Foundation, Jean-Marc Valin and others and licensed under the BSD 3-Clause
 * licence, reproduced in NOTICE. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.speex

/**
 * `SpeexBits` as the decoder uses it: a packet read most significant bit
 * first. Reading past the end sets [overflow] and gives zeros from then on,
 * as the reference does, and the caller checks it.
 */
internal class SpeexBits {
    private var data = ByteArray(0)
    private var bitCount = 0
    private var position = 0

    var overflow = false
        private set

    /** `speex_bits_read_from`. */
    fun readFrom(bytes: ByteArray, offset: Int, length: Int) {
        data = bytes.copyOfRange(offset, offset + length)
        bitCount = length * 8
        position = 0
        overflow = false
    }

    /** `speex_bits_remaining`: bits left, or -1 after an overflow. */
    val remaining: Int get() = if (overflow) -1 else bitCount - position

    private fun bit(at: Int): Int = (data[at ushr 3].toInt() ushr (7 - (at and 7))) and 1

    /** `speex_bits_unpack_unsigned`. */
    fun unpack(bits: Int): Int {
        if (position + bits > bitCount) overflow = true
        if (overflow) return 0
        var value = 0
        repeat(bits) { value = (value shl 1) or bit(position++) }
        return value
    }

    /** `speex_bits_peek`: the next bit without reading it. */
    fun peek(): Int {
        if (position + 1 > bitCount) overflow = true
        if (overflow) return 0
        return bit(position)
    }

    /**
     * `speex_bits_advance`. A narrowband decoder skipping a wideband layer of
     * the null sub-mode moves back four bits, as the reference does; moving
     * back past the start counts as an overflow here.
     */
    fun advance(bits: Int) {
        if (position + bits > bitCount || position + bits < 0 || overflow) {
            overflow = true
            return
        }
        position += bits
    }
}

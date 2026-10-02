package io.github.yuroyami.audiokodecs.internal.io

import io.github.yuroyami.audiokodecs.AudioSink

/**
 * Endian-aware writing on top of an [AudioSink].
 *
 * Encoders write forwards and then correct the length fields they could not
 * know in advance. [patchU32Le] and [patchU32Be] do that correcting, and every
 * encoder checks [AudioSink.canPatch] before it plans on being able to.
 */
internal class SinkWriter(private val sink: AudioSink) {

    private val scratch = ByteArray(8)

    val position: Long get() = sink.position

    val canPatch: Boolean get() = sink.canPatch

    fun writeBytes(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset) {
        sink.write(bytes, offset, count)
    }

    fun writeU8(value: Int) {
        scratch[0] = value.toByte()
        sink.write(scratch, 0, 1)
    }

    fun writeU16Le(value: Int) {
        scratch[0] = value.toByte()
        scratch[1] = (value ushr 8).toByte()
        sink.write(scratch, 0, 2)
    }

    fun writeU16Be(value: Int) {
        scratch[0] = (value ushr 8).toByte()
        scratch[1] = value.toByte()
        sink.write(scratch, 0, 2)
    }

    fun writeU24Le(value: Int) {
        scratch[0] = value.toByte()
        scratch[1] = (value ushr 8).toByte()
        scratch[2] = (value ushr 16).toByte()
        sink.write(scratch, 0, 3)
    }

    fun writeU24Be(value: Int) {
        scratch[0] = (value ushr 16).toByte()
        scratch[1] = (value ushr 8).toByte()
        scratch[2] = value.toByte()
        sink.write(scratch, 0, 3)
    }

    fun writeI32Le(value: Int) {
        scratch[0] = value.toByte()
        scratch[1] = (value ushr 8).toByte()
        scratch[2] = (value ushr 16).toByte()
        scratch[3] = (value ushr 24).toByte()
        sink.write(scratch, 0, 4)
    }

    fun writeI32Be(value: Int) {
        scratch[0] = (value ushr 24).toByte()
        scratch[1] = (value ushr 16).toByte()
        scratch[2] = (value ushr 8).toByte()
        scratch[3] = value.toByte()
        sink.write(scratch, 0, 4)
    }

    fun writeU32Le(value: Long) {
        writeI32Le(value.toInt())
    }

    fun writeU32Be(value: Long) {
        writeI32Be(value.toInt())
    }

    fun writeI64Le(value: Long) {
        writeI32Le(value.toInt())
        writeI32Le((value ushr 32).toInt())
    }

    fun writeI64Be(value: Long) {
        writeI32Be((value ushr 32).toInt())
        writeI32Be(value.toInt())
    }

    fun writeF32Le(value: Float) {
        writeI32Le(value.toRawBits())
    }

    fun writeF32Be(value: Float) {
        writeI32Be(value.toRawBits())
    }

    fun writeF64Le(value: Double) {
        writeI64Le(value.toRawBits())
    }

    fun writeF64Be(value: Double) {
        writeI64Be(value.toRawBits())
    }

    /**
     * Writes a four-character chunk identifier.
     *
     * The argument must be four characters that fit in a byte each, which
     * every identifier in every format this library writes does.
     */
    fun writeFourCc(id: String) {
        require(id.length == 4) { "a chunk identifier is four characters, '$id' is ${id.length}" }
        for (i in 0 until 4) {
            val code = id[i].code
            require(code in 0..255) { "'$id' has a character outside the byte range" }
            scratch[i] = code.toByte()
        }
        sink.write(scratch, 0, 4)
    }

    /** Writes [text] as ASCII, replacing anything outside it with '?'. */
    fun writeAscii(text: String) {
        val bytes = ByteArray(text.length)
        for (i in text.indices) {
            val code = text[i].code
            bytes[i] = if (code in 0..127) code.toByte() else '?'.code.toByte()
        }
        sink.write(bytes)
    }

    fun patchBytes(at: Long, bytes: ByteArray) {
        sink.patchAt(at, bytes)
    }

    fun patchU32Le(at: Long, value: Long) {
        val bytes = ByteArray(4)
        val v = value.toInt()
        bytes[0] = v.toByte()
        bytes[1] = (v ushr 8).toByte()
        bytes[2] = (v ushr 16).toByte()
        bytes[3] = (v ushr 24).toByte()
        sink.patchAt(at, bytes)
    }

    fun patchU32Be(at: Long, value: Long) {
        val bytes = ByteArray(4)
        val v = value.toInt()
        bytes[0] = (v ushr 24).toByte()
        bytes[1] = (v ushr 16).toByte()
        bytes[2] = (v ushr 8).toByte()
        bytes[3] = v.toByte()
        sink.patchAt(at, bytes)
    }

    fun patchI64Le(at: Long, value: Long) {
        val bytes = ByteArray(8)
        for (i in 0 until 8) bytes[i] = (value ushr (8 * i)).toByte()
        sink.patchAt(at, bytes)
    }
}

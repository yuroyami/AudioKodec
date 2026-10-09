package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagField
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checks DSF and DSDIFF reading and the DSD to PCM conversion on every
 * target, with files built here from a sigma delta modulator's bits.
 *
 * The conversion is in double precision from tables of literals, and the
 * modulator's input comes from an oscillator of additions and
 * multiplications only, so every target computes the same samples and a
 * hash of them holds everywhere.
 */
class DsdTest {

    @Test
    fun bothContainersDecodeTheSameBitsToTheSameSamples() {
        val bits = listOf(tone(N, 0.25, COS_1K, SIN_1K), tone(N, 0.3, COS_3K, SIN_3K))
        val dsf = dsf(bits, channelType = 2)
        val dff = dff(bits, listOf("SLFT", "SRGT"))
        val a = AudioKodec.decode(dsf)
        val b = AudioKodec.decode(dff)
        for ((file, container) in listOf(dsf to AudioContainer.DSF, dff to AudioContainer.DFF)) {
            val info = AudioKodec.probe(file)
            assertEquals(container, info.container)
            assertEquals(AudioCodec.DSD, info.codec)
            assertEquals(88_200, info.format.sampleRate)
            assertEquals(2, info.format.channels)
            assertEquals((N / 32).toLong(), info.durationFrames)
        }
        assertEquals(N / 32 * 2, a.samples.size)
        assertContentEquals(a.samples, b.samples)
        assertEquals(HASH, hash(a.samples))
    }

    @Test
    fun aToneComesOutAtItsLevelAndAConstantAtExactlyItsValue() {
        val samples = AudioKodec.decode(dsf(listOf(tone(N, 0.25, COS_1K, SIN_1K)), channelType = 1)).samples
        // Away from the ends, where the signal starts and stops abruptly.
        val peak = samples.copyOfRange(samples.size / 4, samples.size * 3 / 4).maxOf { abs(it) }
        assertTrue(abs(peak - 0.25f) < 0.001f, "a 1 kHz tone at 0.25 peaks at $peak")
        // Every bit set is a constant +1, which the filters pass with unit gain.
        val ones = AudioKodec.decode(dsf(listOf(BooleanArray(N) { true }), channelType = 1)).samples
        for (i in 200 until ones.size - 200) assertEquals(1f, ones[i], 1e-6f, "sample $i")
    }

    @Test
    fun aSeekLandsOnExactlyTheContinuousDecode() {
        val bits = listOf(tone(N, 0.25, COS_1K, SIN_1K), tone(N, 0.3, COS_3K, SIN_3K))
        for (file in listOf(dsf(bits, channelType = 2), dff(bits, listOf("SLFT", "SRGT")))) {
            val whole = AudioKodec.decode(file).samples
            AudioKodec.open(file).use { decoder ->
                for (target in listOf(3_000L, 0L, 1L, 4_095L, 4_097L, 2_999L)) {
                    assertEquals(target, decoder.seek(target))
                    val frame = decoder.nextFrame()!!
                    for (i in 0 until frame.frameCount * 2) {
                        assertEquals(whole[target.toInt() * 2 + i], frame.samples[i], "seek to $target, sample $i")
                    }
                }
            }
        }
    }

    @Test
    fun channelsComeOutInTheCanonicalOrder() {
        // A constant per speaker, so each output channel shows where it came from.
        val levels = mapOf(
            AudioChannel.FRONT_LEFT to 0.1, AudioChannel.FRONT_RIGHT to 0.2, AudioChannel.FRONT_CENTER to 0.3,
            AudioChannel.LOW_FREQUENCY to 0.4, AudioChannel.BACK_LEFT to -0.1, AudioChannel.BACK_RIGHT to -0.2,
        )
        val n = 32 * 2_000
        // DSF type 7 is already in the canonical order; DSDIFF here states another.
        val dsfOrder = listOf(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_CENTER, AudioChannel.LOW_FREQUENCY, AudioChannel.BACK_LEFT, AudioChannel.BACK_RIGHT)
        val dffIds = listOf("C   " to AudioChannel.FRONT_CENTER, "LS  " to AudioChannel.BACK_LEFT, "SLFT" to AudioChannel.FRONT_LEFT, "LFE " to AudioChannel.LOW_FREQUENCY, "SRGT" to AudioChannel.FRONT_RIGHT, "RS  " to AudioChannel.BACK_RIGHT)
        val files = listOf(
            dsf(dsfOrder.map { constant(n, levels.getValue(it)) }, channelType = 7),
            dff(dffIds.map { constant(n, levels.getValue(it.second)) }, dffIds.map { it.first }),
        )
        for (file in files) {
            val decoded = AudioKodec.decode(file)
            assertEquals(dsfOrder, decoded.format.channelLayout.channels)
            for ((c, speaker) in dsfOrder.withIndex()) {
                val value = decoded.samples[1_000 * 6 + c]
                assertTrue(abs(value - levels.getValue(speaker)) < 0.01, "$speaker came out at $value")
            }
        }
    }

    @Test
    fun theId3BlockOfEitherContainerIsRead() {
        val bits = listOf(constant(32 * 100, 0.0))
        val tag = id3("A DSD title")
        val dsf = dsf(bits, channelType = 1, id3 = tag)
        val dff = dff(bits, listOf("C   "), id3 = tag)
        for (file in listOf(dsf, dff)) {
            assertEquals("A DSD title", AudioKodec.readTags(file)[TagField.TITLE])
            assertEquals(100, AudioKodec.decode(file).samples.size)
        }
    }

    @Test
    fun malformedFilesAreRefused() {
        val bits = listOf(tone(N, 0.25, COS_1K, SIN_1K))
        val dsf = dsf(bits, channelType = 1)
        // Data cut short: refused, or in tolerant mode kept to the last whole block.
        val cut = dsf.copyOf(dsf.size - 5_000)
        assertFailsWith<TruncatedStreamException> { AudioKodec.decode(cut) }
        assertEquals(20 * 4_096 * 8 / 32, AudioKodec.decode(cut, DecodeOptions(tolerant = true)).samples.size)
        // A channel type that is not defined.
        assertFailsWith<CorruptStreamException> { AudioKodec.probe(dsf.copyOf().also { it[48] = 9 }) }
        // A channel count that disagrees with the channel type.
        assertFailsWith<CorruptStreamException> { AudioKodec.probe(dsf.copyOf().also { it[52] = 2 }) }
        // DST compression is named and refused.
        val dst = dff(bits, listOf("C   ")).let { file ->
            val at = file.size - N / 8 - 12
            file.copyOf().also { "DST ".encodeToByteArray().copyInto(it, at) }
        }
        assertFailsWith<UnsupportedFeatureException> { AudioKodec.probe(dst) }
        // A DSDIFF file with no sound data at all.
        val dff = dff(bits, listOf("C   "))
        assertFailsWith<CorruptStreamException> { AudioKodec.probe(dff.copyOf(dff.size - N / 8 - 12)) }
    }

    // The format fixes the block at 4096 bytes; a smaller one keeps the shared sweep, which mutates every byte, short.
    internal fun hostileFixture(): ByteArray = dsf(listOf(constant(32 * 64, 0.5)), channelType = 1, blockSize = 64)

    internal fun hostileDffFixture(): ByteArray = dff(listOf(constant(32 * 32, 0.5)), listOf("C   "))

    // Helpers.

    private fun hash(samples: FloatArray): Int {
        var h = 0x811C9DC5.toInt()
        for (s in samples) h = (h xor ((s * 32768f).toInt() and 0xFFFF)) * 16777619
        return h
    }

    /** A sine at [amplitude], from a rotating phasor, through a second-order sigma delta modulator. */
    private fun tone(n: Int, amplitude: Double, cos: Double, sin: Double): BooleanArray {
        var re = 1.0
        var im = 0.0
        return modulate(n) {
            val value = amplitude * im
            val next = re * cos - im * sin
            im = re * sin + im * cos
            re = next
            value
        }
    }

    private fun constant(n: Int, level: Double): BooleanArray = modulate(n) { level }

    private fun modulate(n: Int, next: () -> Double): BooleanArray {
        val out = BooleanArray(n)
        var i1 = 0.0
        var i2 = 0.0
        var y = 0.0
        for (k in 0 until n) {
            i1 += next() - y
            i2 += i1 - y
            y = if (i2 >= 0) 1.0 else -1.0
            out[k] = y > 0
        }
        return out
    }

    private class Bytes {
        private var data = ByteArray(1_024)
        var size = 0
            private set

        fun byte(v: Int) = apply {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v.toByte()
        }

        fun bytes(v: ByteArray) = apply { for (b in v) byte(b.toInt()) }
        fun text(v: String) = bytes(v.encodeToByteArray())
        fun le(v: Long, count: Int) = apply { for (i in 0 until count) byte((v ushr (8 * i)).toInt()) }
        fun be(v: Long, count: Int) = apply { for (i in count - 1 downTo 0) byte((v ushr (8 * i)).toInt()) }
        fun array(): ByteArray = data.copyOf(size)
    }

    private fun dsf(channels: List<BooleanArray>, channelType: Int, id3: ByteArray? = null, blockSize: Int = 4096): ByteArray {
        val n = channels[0].size
        val bytesPerChannel = (n + 7) / 8
        val blocks = (bytesPerChannel + blockSize - 1) / blockSize
        val data = ByteArray(blocks * blockSize * channels.size)
        for ((c, bits) in channels.withIndex()) {
            for (k in 0 until n) if (bits[k]) {
                val byte = k / 8
                val at = (byte / blockSize) * blockSize * channels.size + c * blockSize + byte % blockSize
                data[at] = (data[at].toInt() or (1 shl (k % 8))).toByte()
            }
        }
        val audioEnd = 28L + 52 + 12 + data.size
        val total = audioEnd + (id3?.size ?: 0)
        return Bytes().text("DSD ").le(28, 8).le(total, 8).le(if (id3 != null) audioEnd else 0, 8)
            .text("fmt ").le(52, 8).le(1, 4).le(0, 4).le(channelType.toLong(), 4).le(channels.size.toLong(), 4)
            .le(2_822_400, 4).le(1, 4).le(n.toLong(), 8).le(blockSize.toLong(), 4).le(0, 4)
            .text("data").le(12L + data.size, 8).bytes(data)
            .apply { if (id3 != null) bytes(id3) }
            .array()
    }

    private fun dff(channels: List<BooleanArray>, ids: List<String>, id3: ByteArray? = null): ByteArray {
        val n = channels[0].size
        val data = ByteArray(n / 8 * channels.size)
        for ((c, bits) in channels.withIndex()) {
            for (k in 0 until n / 8 * 8) if (bits[k]) {
                val at = (k / 8) * channels.size + c
                data[at] = (data[at].toInt() or (0x80 ushr (k % 8))).toByte()
            }
        }
        val chnl = 2 + 4 * ids.size
        val prop = 4 + (12 + 4) + (12 + chnl) + (12 + 4 + 1 + 14 + 1)
        val tag = id3?.let { 12 + it.size + it.size % 2 } ?: 0
        val body = 4 + (12 + 4) + (12 + prop) + (12 + data.size) + tag
        return Bytes().text("FRM8").be(body.toLong(), 8).text("DSD ")
            .text("FVER").be(4, 8).be(0x01050000, 4)
            .text("PROP").be(prop.toLong(), 8).text("SND ")
            .text("FS  ").be(4, 8).be(2_822_400, 4)
            .text("CHNL").be(chnl.toLong(), 8).be(ids.size.toLong(), 2).apply { for (id in ids) text(id) }
            .text("CMPR").be(4 + 1 + 14 + 1, 8).text("DSD ").byte(14).text("not compressed").byte(0)
            .text("DSD ").be(data.size.toLong(), 8).bytes(data)
            .apply {
                if (id3 != null) {
                    text("ID3 ").be(id3.size.toLong(), 8).bytes(id3)
                    if (id3.size % 2 == 1) byte(0)
                }
            }
            .array()
    }

    private fun id3(title: String): ByteArray {
        val body = byteArrayOf(0) + title.encodeToByteArray()
        val frame = "TIT2".encodeToByteArray() + ByteArray(4) { (body.size ushr (24 - it * 8)).toByte() } + byteArrayOf(0, 0) + body
        val size = ByteArray(4) { ((frame.size ushr (21 - it * 7)) and 127).toByte() }
        return "ID3".encodeToByteArray() + byteArrayOf(3, 0, 0) + size + frame
    }

    private companion object {
        /** A quarter of a second of DSD64. */
        const val N = 2_822_400 / 4

        /** The cosine and sine of one step of 1 kHz and 3 kHz at 2.8224 MHz. */
        const val COS_1K = 0.9999975220505943
        const val SIN_1K = 0.0022261834316091374
        const val COS_3K = 0.9999776985290314
        const val SIN_3K = 0.006678506163922799

        const val HASH = -246952838
    }
}

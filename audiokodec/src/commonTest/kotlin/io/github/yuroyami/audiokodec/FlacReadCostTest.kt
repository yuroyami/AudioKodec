package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.flac.FlacCrc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlacReadCostTest {
    @Test
    fun anOverstatedMaximumFrameSizeDoesNotMultiplySourceReads() {
        for (knownSize in listOf(false, true)) {
            for (maximum in listOf(14, 0xFF_FFFF)) {
                val bytes = FlacReadCostFixtures.file(maximum)
                val source = CountingSource(bytes, knownSize)
                AudioKodec.open(source).use { decoder ->
                    var frames = 0L
                    while (true) {
                        val block = decoder.nextFrame() ?: break
                        assertEquals(frames, block.positionFrames)
                        assertEquals(16, block.frameCount)
                        val expected = FlacReadCostFixtures.value((frames / 16).toInt()) / 32768f
                        repeat(block.sampleCount) { assertEquals(expected, block.samples[it]) }
                        frames += block.frameCount
                    }
                    assertEquals(128_000L, frames)
                }
                assertTrue(source.transferred < bytes.size * 8L + 65_536,
                    "maximum $maximum, known size $knownSize: ${bytes.size} bytes required ${source.transferred} source bytes")
            }
        }
    }

    @Test
    fun anOverstatedMaximumDoesNotDemandAnOversizedInitialAllocation() {
        val bytes = FlacReadCostFixtures.file(0xFF_FFFF)
        AudioKodec.open(bytes, DecodeOptions(maxAllocationBytes = 1_024)).use { decoder ->
            val first = assertNotNull(decoder.nextFrame())
            assertEquals(16, first.frameCount)
            assertEquals(FlacReadCostFixtures.value(0) / 32768f, first.samples[0])
            val target = 120_003L
            assertEquals(target, decoder.seek(target))
            val block = assertNotNull(decoder.nextFrame())
            assertEquals(target, block.positionFrames)
            assertEquals(FlacReadCostFixtures.value((target / 16).toInt()) / 32768f, block.samples[0])
        }
    }

    private class CountingSource(bytes: ByteArray, knownSize: Boolean) : AudioSource {
        private val delegate = bytes.asAudioSource()
        override val size: Long = if (knownSize) bytes.size.toLong() else -1L
        var transferred = 0L
        override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int =
            delegate.readAt(position, into, offset, count).also { if (it > 0) transferred += it }
        override fun close() = Unit
    }
}

internal object FlacReadCostFixtures {
    fun value(frame: Int): Int = if (frame % 2 == 0) 4096 else -8192

    fun file(maximum: Int): ByteArray {
        val header = AudioKodec.encode(FloatArray(16), AudioFormat(44_100, 1), EncodeTarget.flac()).copyOf(42)
        header[4] = 0x80.toByte()
        for (at in 8..11) header[at] = if (at % 2 == 0) 0 else 16
        for (at in 12..14) header[at] = 0
        repeat(3) { header[15 + it] = (maximum ushr (16 - it * 8)).toByte() }
        header[21] = (header[21].toInt() and 0xF0).toByte()
        repeat(4) { header[22 + it] = (128_000 ushr (24 - it * 8)).toByte() }
        for (at in 26..41) header[at] = 0 // No MD5 is claimed for this synthetic file.
        val sink = ByteArrayAudioSink()
        sink.write(header, 0, header.size)
        repeat(8_000) { number ->
            // Fixed-size 16-sample mono frames, 44.1 kHz and 16-bit PCM.
            val prefix = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x69, 0x08) + utf8Number(number) + byteArrayOf(15)
            val sample = value(number)
            val body = prefix + byteArrayOf(FlacCrc.crc8(prefix, 0, prefix.size).toByte(),
                0, (sample ushr 8).toByte(), sample.toByte())
            val checksum = FlacCrc.crc16(body, 0, body.size)
            val frame = body + byteArrayOf((checksum ushr 8).toByte(), checksum.toByte())
            sink.write(frame, 0, frame.size)
        }
        return sink.toByteArray()
    }

    private fun utf8Number(number: Int): ByteArray = when {
        number < 128 -> byteArrayOf(number.toByte())
        number < 2048 -> byteArrayOf((0xC0 or (number ushr 6)).toByte(), (0x80 or (number and 63)).toByte())
        else -> byteArrayOf((0xE0 or (number ushr 12)).toByte(),
            (0x80 or ((number ushr 6) and 63)).toByte(), (0x80 or (number and 63)).toByte())
    }
}

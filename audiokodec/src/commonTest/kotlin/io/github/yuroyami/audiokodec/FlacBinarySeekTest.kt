package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.flac.FlacCrc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlacBinarySeekTest {
    @Test
    fun anUnindexedSeekDoesNotReadEveryPrecedingFrame() {
        val samples = FlacBinarySeekFixtures.noise()
        val bytes = AudioKodec.encode(samples, AudioFormat(44_100, 1), EncodeTarget.flac(16, effort = 0))
        FlacBinarySeekFixtures.assertCheapSeeks(bytes, samples)
    }

    @Test
    fun falseChecksummedHeadersInsideVerbatimSamplesDoNotChangeTheSeekResult() {
        val (bytes, samples) = FlacBinarySeekFixtures.embeddedHeaders()
        TestSignals.assertExact(samples, AudioKodec.decode(bytes).samples)
        for (knownSize in listOf(false, true)) {
            AudioKodec.open(OggDamageFixtures.source(bytes, knownSize)).use { decoder ->
                decoder.seek(samples.lastIndex.toLong())
                decoder.seek(13)
                val first = assertNotNull(decoder.nextFrame())
                TestSignals.assertExact(samples.copyOfRange(13, 13 + first.frameCount), first.samples.copyOf(first.sampleCount))
                for (block in 127 downTo 1 step 7) {
                    val target = block * 1024L + 13
                    assertEquals(target, decoder.seek(target))
                    val frame = assertNotNull(decoder.nextFrame())
                    assertEquals(target, frame.positionFrames)
                    TestSignals.assertExact(samples.copyOfRange(target.toInt(), target.toInt() + frame.frameCount),
                        frame.samples.copyOf(frame.sampleCount))
                }
            }
        }
    }
}

internal object FlacBinarySeekFixtures {
    fun noise(): FloatArray {
        var state = 0x12345678
        return FloatArray(1_048_576) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            ((state ushr 16) - 32768) / 32768f
        }
    }

    fun assertCheapSeeks(bytes: ByteArray, samples: FloatArray) {
        for (percent in listOf(10, 50, 90, 100)) {
            val source = CountingSource(bytes)
            AudioKodec.open(source).use { decoder ->
                source.transferred = 0
                val target = samples.size.toLong() * percent / 100
                assertEquals(target, decoder.seek(target))
                if (percent == 100) {
                    assertNull(decoder.nextFrame())
                    assertEquals(0L, source.transferred)
                } else {
                    val block = assertNotNull(decoder.nextFrame())
                    assertEquals(target, block.positionFrames)
                    TestSignals.assertExact(samples.copyOfRange(target.toInt(), target.toInt() + block.frameCount),
                        block.samples.copyOf(block.sampleCount))
                }
                assertTrue(source.transferred < bytes.size / 3L + 65_536,
                    "seeking to $percent% transferred ${source.transferred} of ${bytes.size} bytes")
                println("FLAC seek $percent%: ${source.transferred} source bytes of ${bytes.size}")
            }
        }
    }

    /** RFC 9639 verbatim frames with plausible CRC-8-valid headers in their PCM. */
    fun embeddedHeaders(): Pair<ByteArray, FloatArray> {
        val metadata = AudioKodec.encode(FloatArray(1024), AudioFormat(44_100, 1), EncodeTarget.flac()).copyOf(42)
        metadata[4] = 0x80.toByte()
        for (at in 8..11) metadata[at] = if (at % 2 == 0) 4 else 0
        metadata.fill(0, 12, 18)
        metadata.fill(0, 26, 42) // The fixture makes no MD5 claim.
        val sink = ByteArrayAudioSink()
        sink.write(FlacContinuityFixtures.withTotal(metadata, 128 * 1024))
        val samples = FloatArray(128 * 1024)
        repeat(128) { number ->
            val pcm = ByteArray(2048) { ((it * 17 + number * 31) and 255).toByte() }
            val fakeHeader = header((number + 9) % 128)
            fakeHeader.copyInto(pcm, 512)
            repeat(1024) {
                val value = (pcm[it * 2].toInt() shl 8) or (pcm[it * 2 + 1].toInt() and 255)
                samples[number * 1024 + it] = value / 32768f
            }
            val body = header(number) + byteArrayOf(2) + pcm
            val crc = FlacCrc.crc16(body, 0, body.size)
            sink.write(body + byteArrayOf((crc ushr 8).toByte(), crc.toByte()))
        }
        return sink.toByteArray() to samples
    }

    private fun header(number: Int): ByteArray {
        val prefix = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0xA9.toByte(), 8, number.toByte())
        return prefix + byteArrayOf(FlacCrc.crc8(prefix, 0, prefix.size).toByte())
    }

    private class CountingSource(bytes: ByteArray) : AudioSource {
        private val delegate = bytes.asAudioSource()
        override val size: Long = bytes.size.toLong()
        var transferred = 0L
        override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int =
            delegate.readAt(position, into, offset, count).also { if (it > 0) transferred += it }
        override fun close() = Unit
    }
}

package io.github.yuroyami.audiokodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WholeFileAllocationTest {
    private val format = AudioFormat(8_000, 1)
    private val limit = 65_536L

    @Test
    fun tolerantDecodingRecoversAudioDespiteAnOversizedLengthHint() {
        val original = TestSignals.quantise(TestSignals.sine(8_000, 1, sampleRate = 8_000), 16)
        for (target in listOf(EncodeTarget.wav(16), EncodeTarget.flac(16))) {
            val pristine = AudioKodec.encode(original, format, target)
            val expected = AudioKodec.decode(pristine).samples
            val damaged = overstateDuration(pristine)
            for (knownSize in listOf(false, true)) {
                val source = ObservedSource(damaged, knownSize)
                val decoded = AudioKodec.decode(source, DecodeOptions(tolerant = true, maxAllocationBytes = limit))
                assertEquals(8_000, decoded.frames)
                assertContentEquals(expected, decoded.samples)
                assertTrue(source.closed)
            }
        }
    }

    @Test
    fun strictDecodingReportsTruncationRatherThanRejectingTheLengthHint() {
        for (target in listOf(EncodeTarget.wav(16), EncodeTarget.flac(16))) {
            val damaged = overstateDuration(AudioKodec.encode(FloatArray(8_000), format, target))
            for (knownSize in listOf(false, true)) {
                val source = ObservedSource(damaged, knownSize)
                assertFailsWith<TruncatedStreamException> {
                    AudioKodec.decode(source, DecodeOptions(maxAllocationBytes = limit))
                }
                assertTrue(source.closed)
            }
        }
    }

    @Test
    fun actualDecodedAudioStillCannotExceedTheAllocationLimit() {
        for (target in listOf(EncodeTarget.wav(16), EncodeTarget.flac(16))) {
            val bytes = AudioKodec.encode(FloatArray(17_000), format, target)
            for (tolerant in listOf(false, true)) {
                val source = ObservedSource(bytes, true)
                assertFailsWith<AllocationGuardException> {
                    AudioKodec.decode(source, DecodeOptions(tolerant = tolerant, maxAllocationBytes = limit))
                }
                assertTrue(source.closed)
            }
        }
    }

    private fun overstateDuration(bytes: ByteArray): ByteArray {
        when (AudioKodec.probe(bytes).container) {
            AudioContainer.WAV -> {
                val sizeAt = AudioKodec.probe(bytes).dataOffset.toInt() - 4
                val claimedBytes = 700_000_000
                repeat(4) { bytes[sizeAt + it] = (claimedBytes ushr (8 * it)).toByte() }
                val claimedFormSize = claimedBytes + sizeAt - 4
                repeat(4) { bytes[4 + it] = (claimedFormSize ushr (8 * it)).toByte() }
            }
            AudioContainer.FLAC -> {
                // STREAMINFO packs a 36-bit sample count after the format.
                bytes[21] = (bytes[21].toInt() or 0x0F).toByte()
                for (at in 22..25) bytes[at] = 0xFF.toByte()
            }
            else -> error("unsupported test container")
        }
        return bytes
    }

    private class ObservedSource(bytes: ByteArray, knownSize: Boolean) : AudioSource {
        private val delegate = bytes.asAudioSource()
        override val size: Long = if (knownSize) bytes.size.toLong() else -1L
        var closed = false
        override fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int =
            delegate.readAt(position, into, offset, count)
        override fun close() { closed = true }
    }
}

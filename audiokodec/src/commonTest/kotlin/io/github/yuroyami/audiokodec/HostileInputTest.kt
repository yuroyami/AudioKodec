package io.github.yuroyami.audiokodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What AudioKodec does with input that is not a well-formed file.
 *
 * The contract is narrow and worth stating: no input produces a throwable
 * outside [AudioKodecException], no input makes a decoder loop forever, and no
 * header can make AudioKodec allocate more than the options allow. These tests
 * are what hold that contract up.
 */
class HostileInputTest {

    private val format = AudioFormat(44_100, 2)
    private val goodFile = AudioKodec.encode(
        TestSignals.quantise(TestSignals.sine(600, 2), 16), format, EncodeTarget.wav(16),
    )

    @Test
    fun aHeaderClaimingMoreChannelsThanTheOptionsAllowIsRejected() {
        val file = goodFile.copyOf()
        val fmtAt = file.indexOfChunk("fmt ")
        // Channel count sits two bytes into the format chunk body.
        file[fmtAt + 10] = 0xFF.toByte()
        file[fmtAt + 11] = 0xFF.toByte()
        assertFailsWith<CorruptStreamException> { AudioKodec.probe(file) }
    }

    @Test
    fun aHeaderClaimingAnAbsurdSampleRateIsRejected() {
        val file = goodFile.copyOf()
        val fmtAt = file.indexOfChunk("fmt ")
        for (i in 12..15) file[fmtAt + i] = 0x7F
        assertFailsWith<CorruptStreamException> { AudioKodec.probe(file) }
    }

    @Test
    fun theAllocationGuardFiresBeforeTheAllocationDoes() {
        // A file that says it holds far more audio than the guard allows.
        val tiny = DecodeOptions(maxAllocationBytes = 4096)
        assertFailsWith<AllocationGuardException> {
            AudioKodec.decode(goodFile, tiny)
        }
        // Streaming the same file inside the same limit is fine, because no
        // single buffer is over it.
        AudioKodec.open(goodFile, tiny).use { decoder ->
            var frames = 0L
            while (true) {
                val block = decoder.nextFrame() ?: break
                frames += block.frameCount
            }
            assertEquals(600L, frames)
        }
    }

    @Test
    fun everyTruncationPointIsHandledWithoutAnUnexpectedFailure() {
        for (length in 0 until goodFile.size step 7) {
            val cut = goodFile.copyOf(length)
            expectOnlyKiteFailures("truncated to $length bytes") {
                AudioKodec.decode(cut)
            }
            expectOnlyKiteFailures("truncated to $length bytes, tolerant") {
                AudioKodec.decode(cut, DecodeOptions(tolerant = true))
            }
            expectOnlyKiteFailures("probing $length bytes") {
                AudioKodec.probe(cut)
            }
        }
    }

    @Test
    fun strictModeRejectsAWholeMissingPcmFrame() {
        // The WAV header still declares 600 stereo frames. Removing exactly
        // four bytes leaves no partial PCM block to make the truncation obvious.
        val cut = goodFile.copyOf(goodFile.size - 4)
        assertFailsWith<TruncatedStreamException> { AudioKodec.decode(cut) }

        val salvaged = AudioKodec.decode(cut, DecodeOptions(tolerant = true))
        assertEquals(599, salvaged.frames)
    }

    @Test
    fun mutatingSingleBytesOfAValidFileNeverProducesAnUnexpectedFailure() {
        var state = 987_654_321
        repeat(4_000) {
            state = state * 1_103_515_245 + 12_345
            val at = ((state ushr 8) and 0x7FFF_FFFF) % goodFile.size
            state = state * 1_103_515_245 + 12_345
            val value = ((state ushr 16) and 0xFF).toByte()
            val mutated = goodFile.copyOf()
            mutated[at] = value
            expectOnlyKiteFailures("byte $at set to $value") {
                AudioKodec.decode(mutated, DecodeOptions(tolerant = true))
            }
        }
    }

    @Test
    fun randomBytesBehindAValidMagicNumberAreRejectedCleanly() {
        var state = 42
        repeat(2_000) {
            val bytes = ByteArray(96)
            "RIFF".forEachIndexed { i, c -> bytes[i] = c.code.toByte() }
            "WAVE".forEachIndexed { i, c -> bytes[8 + i] = c.code.toByte() }
            for (i in 12 until bytes.size) {
                state = state * 1_103_515_245 + 12_345
                bytes[i] = ((state ushr 16) and 0xFF).toByte()
            }
            // The size field is left random on purpose: it is the field most
            // likely to send a reader somewhere it should not go.
            expectOnlyKiteFailures("random RIFF body") {
                AudioKodec.decode(bytes, DecodeOptions(tolerant = true))
            }
        }
    }

    @Test
    fun aChunkClaimingAnEnormousSizeDoesNotHang() {
        val file = goodFile.copyOf()
        val fmtAt = file.indexOfChunk("fmt ")
        // Make the format chunk claim it is four gigabytes long.
        for (i in 4..7) file[fmtAt + i] = 0xFF.toByte()
        expectOnlyKiteFailures("format chunk claiming 4 GiB") {
            AudioKodec.probe(file)
        }
    }

    @Test
    fun anAdpcmBlockSizeThatCannotHoldItsOwnHeaderIsRejected() {
        val file = goodFile.copyOf()
        val fmtAt = file.indexOfChunk("fmt ")
        // Format tag 0x11 is IMA ADPCM, and the block alignment left over
        // from the PCM header is four bytes, which is the header size for
        // one channel and too small for two.
        file[fmtAt + 8] = 0x11
        file[fmtAt + 9] = 0x00
        expectOnlyKiteFailures("IMA ADPCM with an impossible block size") {
            AudioKodec.probe(file)
        }
    }

    @Test
    fun anEmptySourceIsReportedRatherThanCrashing() {
        assertFailsWith<TruncatedStreamException> { AudioKodec.probe(ByteArray(0)) }
    }

    @Test
    fun bytesThatAreNotAudioAtAllAreNamedAsSuch() {
        val failure = assertFailsWith<UnsupportedFormatException> {
            AudioKodec.probe("this is a text file, not audio at all".encodeToByteArray())
        }
        assertTrue(failure.format == null)
    }

    /**
     * Runs [block] and fails when anything other than an AudioKodec failure
     * comes out.
     *
     * An [IndexOutOfBoundsException] from a hostile file is a bug in the
     * reader, not an acceptable outcome, and this is what says so.
     */
    private inline fun expectOnlyKiteFailures(what: String, block: () -> Unit) {
        try {
            block()
        } catch (_: AudioKodecException) {
            // The whole point: every failure has to be one of ours.
        } catch (e: Throwable) {
            throw AssertionError("$what threw ${e::class.simpleName}: ${e.message}", e)
        }
    }
}

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
    private val fixtures by lazy { HostileInputFixtures.files() }
    private val strictSweep = DecodeOptions(maxAllocationBytes = 1L shl 20)
    private val tolerantSweep = strictSweep.copy(tolerant = true)

    @Test
    fun everyDecodableContainerHasAValidFixtureInTheSharedSweep() {
        val covered = fixtures.map { (name, bytes) ->
            val decoded = AudioKodec.decode(bytes, strictSweep)
            assertTrue(decoded.frames > 0, "$name has no valid baseline audio")
            TestSignals.assertExact(decoded.samples,
                AudioKodec.decode(OggDamageFixtures.source(bytes, knownSize = false), strictSweep).samples, name)
            decoded.info.container
        }.toSet()
        assertEquals(AudioContainer.entries.filter { it.isDecodable }.toSet(), covered,
            "add a short valid fixture when a new container gains a decoder")
        val bytes = fixtures.sumOf { it.second.size }
        println("Hostile coverage: ${fixtures.size} fixtures, $bytes truncation boundaries, ${bytes * 9} byte mutations, ${fixtures.size * 100} random bodies")
    }

    @Test
    fun leadingTagHeaderMutationsAndTruncationsNeverMakeSniffThrow() {
        for (major in 2..4) {
            val tag = SniffWindowFixtures.prefix(64, major, footer = major == 4)
            val source = tag + "RIFFxxxxWAVE".encodeToByteArray()
            for (length in 0..source.size) AudioKodec.sniff(source.copyOf(length))
            val positions = (0 until 10).toList() + if (major == 4) (tag.size - 10 until tag.size).toList() else emptyList()
            for (at in positions) {
                for (byte in 0..255) {
                    val mutated = source.copyOf().also { it[at] = byte.toByte() }
                    AudioKodec.sniff(mutated)
                }
            }
        }
    }

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
        for ((name, original) in fixtures) {
            println("Truncation sweep: $name (${original.size} bytes)")
            for (length in 0 until original.size) {
                val cut = original.copyOf(length)
                for (options in listOf(strictSweep, tolerantSweep)) {
                    exercise("$name truncated to $length bytes", cut, options)
                }
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
        for ((name, original) in fixtures) {
            println("Mutation sweep: $name (${original.size} bytes)")
            for (at in original.indices) {
                for (mask in (0..7).map { 1 shl it } + 0xFF) {
                    val mutated = original.copyOf().also { it[at] = (it[at].toInt() xor mask).toByte() }
                    for (options in listOf(strictSweep, tolerantSweep)) {
                        exercise("$name byte $at XOR $mask", mutated, options)
                    }
                }
            }
        }
    }

    @Test
    fun randomBytesBehindAValidMagicNumberAreRejectedCleanly() {
        var state = 42
        for ((name, original) in fixtures) {
            println("Random-body sweep: $name")
            val container = AudioKodec.probe(original).container
            repeat(100) {
                val bytes = ByteArray(minOf(original.size, 256)) {
                    state = state * 1_103_515_245 + 12_345
                    ((state ushr 16) and 0xFF).toByte()
                }
                for (range in HostileInputFixtures.magicRanges(container)) {
                    original.copyInto(bytes, range.first, range.first, range.last + 1)
                }
                for (options in listOf(strictSweep, tolerantSweep)) {
                    exercise("random $name body", bytes, options)
                }
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

    private fun exercise(what: String, bytes: ByteArray, options: DecodeOptions) {
        expectOnlyKiteFailures("$what streaming, tolerant=${options.tolerant}") {
            AudioKodec.open(bytes, options.copy(reuseBuffers = true)).use { decoder ->
                var position = 0L
                repeat(4096) {
                    val block = decoder.nextFrame() ?: return@use
                    assertTrue(block.frameCount > 0, "$what returned an empty block without ending")
                    assertEquals(position, block.positionFrames, "$what did not advance continuously")
                    position += block.frameCount
                    // Whole-file decoding reaches its allocation guard at
                    // this size; there is no need to retain a longer stream.
                    if (position > options.maxAllocationBytes / 4 / decoder.format.channels) return@use
                }
                throw AssertionError("$what did not end within 4096 decoded blocks")
            }
        }
        expectOnlyKiteFailures("$what decoding, tolerant=${options.tolerant}") {
            AudioKodec.decode(bytes, options)
        }
        expectOnlyKiteFailures("$what probing, tolerant=${options.tolerant}") {
            AudioKodec.probe(bytes, options)
        }
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

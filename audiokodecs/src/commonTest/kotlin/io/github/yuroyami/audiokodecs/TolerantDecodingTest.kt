package io.github.yuroyami.audiokodecs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * What [DecodeOptions.tolerant] promises when a codec block, rather than the
 * byte stream, is what is broken: every complete block before the damage, and
 * then a clean end.
 */
class TolerantDecodingTest {

    private val framesPerBlock = 9 // An eight-byte mono IMA ADPCM block.
    private val blocks = 10

    /**
     * A file whose block [bad] has a step index above the table's end, and the
     * source offset of that block's header, which is where the codec reports
     * the damage.
     */
    private fun damaged(bad: Int): Pair<ByteArray, Long> {
        val bytes = AudioKodecs.encode(
            FloatArray(framesPerBlock * blocks) { (it % 7) / 10f },
            AudioFormat(8_000, 1),
            EncodeTarget.wavImaAdpcm(8),
        )
        val data = AudioKodecs.probe(bytes).dataOffset
        val at = data + bad * 8L
        bytes[at.toInt() + 2] = 89
        return bytes to at
    }

    private val tolerant = DecodeOptions(tolerant = true)

    @Test
    fun strictDecodingThrowsWithTheSourcePositionOfTheBadByte() {
        val (bytes, at) = damaged(4)
        val failure = assertFailsWith<CorruptStreamException> { AudioKodecs.decode(bytes) }
        assertEquals(at, failure.offset)
    }

    @Test
    fun corruptionLaterInOneReadKeepsTheBlocksBeforeIt() {
        val (bytes, _) = damaged(4)
        AudioKodecs.open(bytes, tolerant).use { decoder ->
            val frame = decoder.nextFrame() ?: throw AssertionError("nothing decoded")
            assertEquals(4 * framesPerBlock, frame.frameCount)
            assertNull(decoder.nextFrame())
            assertNull(decoder.nextFrame())
        }
        assertEquals(4 * framesPerBlock, AudioKodecs.decode(bytes, tolerant).frames)
    }

    @Test
    fun corruptionAfterAReturnedFrameEndsTheStream() {
        val (bytes, _) = damaged(1)
        // A tiny allocation ceiling makes every call read a single block.
        AudioKodecs.open(bytes, DecodeOptions(tolerant = true, maxAllocationBytes = 64)).use { decoder ->
            assertEquals(framesPerBlock, decoder.nextFrame()?.frameCount)
            assertNull(decoder.nextFrame())
            // Seeking back before the damage decodes again.
            decoder.seek(0)
            assertEquals(framesPerBlock, decoder.nextFrame()?.frameCount)
        }
    }

    @Test
    fun corruptionInTheFirstBlockLeavesNothingToTolerate() {
        val (bytes, at) = damaged(0)
        val failure = assertFailsWith<CorruptStreamException> {
            AudioKodecs.open(bytes, tolerant).use { it.nextFrame() }
        }
        assertEquals(at, failure.offset)
    }
}

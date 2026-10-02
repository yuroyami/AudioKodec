package io.github.yuroyami.audiokodecs

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Microsoft ADPCM blocks built to drive the adaptive step as far as it goes.
 *
 * The expected samples were produced by ffmpeg's decoder from these exact
 * blocks. A step that wrapped through a 32-bit product used to collapse to the
 * floor of 16, which reads as a waveform that is fine and also wrong.
 */
class MsAdpcmStepTest {

    /**
     * One mono block, coefficient set 0, initial step 32767, two zero
     * samples, then [codes] filling the block in order.
     */
    private fun block(blockAlign: Int, codes: (index: Int) -> Int): IntArray {
        val frames = 2 + (blockAlign - 7) * 2
        val bytes = AudioKodecs.encode(FloatArray(frames), AudioFormat(8_000, 1), EncodeTarget.wavMsAdpcm(blockAlign))
        val data = AudioKodecs.probe(bytes).dataOffset.toInt()
        bytes[data] = 0
        bytes[data + 1] = 0xFF.toByte()
        bytes[data + 2] = 0x7F
        for (i in 3 until 7) bytes[data + i] = 0
        for (i in 0 until blockAlign - 7) bytes[data + 7 + i] = codes(i).toByte()
        return Pcm.intFromFloat(AudioKodecs.decode(bytes).samples, 16)
    }

    @Test
    fun aGrowingStepSaturatesInsteadOfWrapping() {
        // Codes 7 and 8 alternately, each of which at least doubles the step.
        val samples = block(15) { 0x78 }
        val expected = IntArray(18) { if (it < 2) 0 else if (it % 2 == 0) 32_767 else -32_768 }
        assertEquals(expected.toList(), samples.toList())
    }

    @Test
    fun aStepAtItsBoundDecaysTheWayTheOracleDoes() {
        // Thirty-three bytes of growth, far past the bound, then codes +1 and
        // -1 alternately, which shrink the step until the waveform comes back.
        val samples = block(256) { if (it < 33) 0x78 else 0x1F }
        assertEquals(500, samples.size)
        assertEquals(listOf(32_767, -32_768, 32_767, -32_768, 26_401, -26_758, 21_002, -21_907), samples.slice(100 until 108))
        assertEquals(listOf(-1_593, -1_609, -1_593, -1_609), samples.takeLast(4))

        // Less growth reaches the bound sooner, and decays to the same place.
        val shorter = block(256) { if (it < 6) 0x78 else 0x1F }
        assertEquals(listOf(32_767, -32_768, 26_401, -26_758, 21_002, -21_907, 16_644, -17_991), shorter.slice(48 until 56))
        assertEquals(listOf(-1_470, -1_716, -1_495, -1_693, -1_516, -1_675, -1_533, -1_660), shorter.slice(100 until 108))
        assertEquals(listOf(-1_593, -1_609, -1_593, -1_609), shorter.takeLast(4))
    }

    @Test
    fun negativeCorrectionsSaturateDownwardsToo() {
        val samples = block(15) { 0x87 }
        val expected = IntArray(18) { if (it < 2) 0 else if (it % 2 == 0) -32_768 else 32_767 }
        assertEquals(expected.toList(), samples.toList())
    }
}

package io.github.yuroyami.audiokodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MsAdpcmPredictionTest {

    @Test
    fun allSevenPredictorsMatchCapturedOracleSamples() {
        for (predictor in 0..6) {
            for (sign in intArrayOf(-1, 1)) {
                val file = MsAdpcmPredictionFixture.file(intArrayOf(predictor), sign)
                val actual = Pcm.intFromFloat(AudioKodec.decode(file).samples, 16)
                assertContentEquals(expected(predictor, sign), actual, "predictor $predictor, sign $sign")
            }
        }
    }

    @Test
    fun seekingIntoBothBlocksKeepsTheOracleSamples() {
        for (predictor in 0..6) {
            val file = MsAdpcmPredictionFixture.file(intArrayOf(predictor), -1, blocks = 2)
            val expected = expected(predictor, -1) + expected(predictor, 1)
            AudioKodec.open(file).use { decoder ->
                assertEquals(expected.size.toLong(), decoder.durationFrames)
                for (target in intArrayOf(0, 1, 2, 17, 18, 19, 20, 35)) {
                    assertEquals(target.toLong(), decoder.seek(target.toLong()))
                    val frame = assertNotNull(decoder.nextFrame())
                    assertEquals(target.toLong(), frame.positionFrames)
                    val samples = Pcm.intFromFloat(frame.samples.copyOf(frame.sampleCount), 16)
                    assertContentEquals(expected.copyOfRange(target, target + samples.size), samples, "predictor $predictor at $target")
                }
                assertEquals(expected.size.toLong(), decoder.seek(expected.size.toLong()))
                assertNull(decoder.nextFrame())
            }
        }
    }

    @Test
    fun aPredictorBeyondTheDeclaredTableIsRejected() {
        val file = MsAdpcmPredictionFixture.file(intArrayOf(7), -1)
        assertFailsWith<CorruptStreamException> { AudioKodec.decode(file) }
    }

    private fun expected(predictor: Int, sign: Int): IntArray = if (sign < 0) NEGATIVE[predictor] else POSITIVE[predictor]

    private companion object {
        // Captured with ffmpeg 8.0 from these hand-built blocks, using:
        // ffmpeg -v error -i fixture.wav -f s16le oracle.raw
        // Seeds are +/-1 and +/-3; codes visit every signed nibble, including saturation.
        val NEGATIVE = arrayOf(
            intArrayOf(
                -1, -3, 13, 45, 93, 157,
                252, 432, 852, -292, -3295, -9463,
                -19743, -32768, -32768, -32768, -32768, -32768,
            ),
            intArrayOf(
                -1, -3, 11, 57, 151, 309,
                562, 995, 1848, 1557, -1737, -11199,
                -30941, -32768, -32768, -32768, -32768, -32768,
            ),
            intArrayOf(
                -1, -3, 16, 32, 48, 64,
                95, 180, 420, -1144, -3003, -6168,
                -10280, -13136, -11814, -7076, -3178, 0,
            ),
            intArrayOf(
                -1, -3, 14, 41, 82, 135,
                216, 375, 755, -484, -3177, -8671,
                -17577, -28486, -32768, -32768, -32768, -32768,
            ),
            intArrayOf(
                -1, -3, 14, 45, 90, 148,
                233, 398, 793, -401, -3378, -9334,
                -19030, -30976, -32768, -32768, -32768, -30720,
            ),
            intArrayOf(
                -1, -3, 12, 56, 138, 266,
                460, 790, 1465, 846, -2674, -11660,
                -29058, -32768, -32768, -32768, -32768, -32256,
            ),
            intArrayOf(
                -1, -3, 13, 54, 118, 195,
                286, 441, 836, -264, -4164, -12304,
                -25346, -32768, -32768, -27556, -15677, 967,
            ),
        )
        val POSITIVE = arrayOf(
            intArrayOf(
                1, 3, 19, 51, 99, 163,
                258, 438, 858, -286, -3289, -9457,
                -19737, -32768, -32768, -32768, -32768, -32768,
            ),
            intArrayOf(
                1, 3, 21, 71, 169, 331,
                588, 1025, 1882, 1595, -1695, -11153,
                -30891, -32768, -32768, -32768, -32768, -32768,
            ),
            intArrayOf(
                1, 3, 16, 32, 48, 64,
                95, 180, 420, -1144, -3003, -6168,
                -10280, -13136, -11814, -7076, -3178, 0,
            ),
            intArrayOf(
                1, 3, 18, 46, 87, 140,
                221, 380, 760, -479, -3172, -8666,
                -17572, -28481, -32768, -32768, -32768, -32768,
            ),
            intArrayOf(
                1, 3, 18, 48, 93, 151,
                236, 401, 795, -399, -3377, -9333,
                -19029, -30975, -32768, -32768, -32768, -30720,
            ),
            intArrayOf(
                1, 3, 20, 65, 148, 277,
                472, 803, 1479, 861, -2658, -11643,
                -29041, -32768, -32768, -32768, -32768, -32256,
            ),
            intArrayOf(
                1, 3, 19, 58, 119, 193,
                282, 436, 832, -266, -4164, -12303,
                -25345, -32768, -32768, -27556, -15677, 967,
            ),
        )
    }
}

/** Builds only the WAV framing; the ADPCM codes are independent of the encoder. */
internal object MsAdpcmPredictionFixture {
    private val codes = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte(), 0xDE.toByte(), 0xF0.toByte())
    private val coefficients = intArrayOf(256, 0, 512, -256, 0, 0, 192, 64, 240, 0, 460, -208, 392, -232)

    fun file(predictors: IntArray, sign: Int, blocks: Int = 1): ByteArray {
        val channels = predictors.size
        val blockBytes = channels * 7 + codes.size
        val framesPerBlock = 2 + codes.size * 2 / channels
        val data = ByteArray(blockBytes * blocks)
        for (block in 0 until blocks) {
            var at = block * blockBytes
            for (predictor in predictors) data[at++] = predictor.toByte()
            for (c in 0 until channels) { u16(16).copyInto(data, at); at += 2 }
            val blockSign = if (block % 2 == 0) sign else -sign
            for (factor in intArrayOf(3, 1)) {
                for (c in 0 until channels) {
                    val channelSign = if (c % 2 == 0) blockSign else -blockSign
                    u16(channelSign * factor * (c + 1)).copyInto(data, at)
                    at += 2
                }
            }
            codes.copyInto(data, at)
        }
        val fmt = u16(2) + u16(channels) + u32(8_000) + u32(8_000 * blockBytes / framesPerBlock) +
            u16(blockBytes) + u16(4) + u16(32) + u16(framesPerBlock) + u16(7) +
            coefficients.flatMap { u16(it).toList() }.toByteArray()
        val body = "WAVE".encodeToByteArray() + chunk("fmt ", fmt) +
            chunk("fact", u32(framesPerBlock * blocks)) + chunk("data", data)
        return "RIFF".encodeToByteArray() + u32(body.size) + body
    }

    private fun chunk(name: String, data: ByteArray): ByteArray =
        name.encodeToByteArray() + u32(data.size) + data + if (data.size % 2 == 0) ByteArray(0) else byteArrayOf(0)

    private fun u16(value: Int): ByteArray = byteArrayOf(value.toByte(), (value shr 8).toByte())
    private fun u32(value: Int): ByteArray = ByteArray(4) { (value shr (it * 8)).toByte() }
}

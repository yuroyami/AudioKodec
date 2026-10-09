package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.codec.G711Codec
import io.github.yuroyami.audiokodec.internal.codec.G726Decoder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Runs the G.726 decoder over the digital test sequences of Appendix II to
 * the Recommendation, which the ITU publishes for checking implementations.
 *
 * The sequences check a decoder's log PCM output, A-law or mu-law, after the
 * synchronous coding adjustment of section 4.2.8, where AudioKodec gives the
 * uniform signal the adjustment starts from. This test therefore adds that
 * last stage, COMPRESS, EXPAND, SUBTA, LOG, SUBTB and SYNC, as the
 * Recommendation specifies it, and compares every output word: starting from
 * reset, after the homing sequences, for normal, overload and the
 * Appendix's ADPCM-only inputs, and for the cross vectors decoded to the
 * other law.
 *
 * The sequences are fetched from the ITU by CI into `reference/g72x/g726-appii`;
 * see `reference/REFERENCES.md`.
 */
class G726ConformanceTest {

    @Test
    fun everyTestSequenceDecodesToItsOutputWords() {
        val root = root()
        var compared = 0
        for (rate in listOf(16, 24, 32, 40)) {
            for (inputLaw in listOf(MU, A)) {
                val disk = if (inputLaw == MU) "DISK1" else "DISK2"
                val l = if (inputLaw == MU) "M" else "A"
                for (homing in listOf(false, true)) {
                    val dir = File(root, "$disk/${if (homing) "HOMING" else "RESET"}/$rate")
                    val p = if (homing) "H" else "R"
                    val homingCodes = if (homing) read(File(dir, "I_INI_$rate.$l")) else null
                    val crossHoming = if (homing) {
                        read(File(root, "${if (inputLaw == MU) "DISK2" else "DISK1"}/HOMING/$rate/I_INI_$rate.${if (inputLaw == MU) "A" else "M"}"))
                    } else {
                        null
                    }
                    val cross = if (inputLaw == MU) "C" else "X"
                    val other = if (inputLaw == MU) A else MU
                    val cases = listOf(
                        Triple(File(dir, "${p}N${rate}F$l.I"), File(dir, "${p}N${rate}F$l.O"), inputLaw),
                        Triple(File(dir, "${p}V${rate}F$l.I"), File(dir, "${p}V${rate}F$l.O"), inputLaw),
                        Triple(File(root, "$disk/INPUT/I$rate"), File(dir, "${p}I${rate}F$l.O"), inputLaw),
                        Triple(File(dir, "${p}N${rate}F$l.I"), File(dir, "${p}N${rate}F$cross.O"), other),
                        Triple(File(dir, "${p}V${rate}F$l.I"), File(dir, "${p}V${rate}F$cross.O"), other),
                    )
                    for ((input, output, law) in cases) {
                        val decoder = G726Decoder(bits = rate / 8)
                        val sync = Sync(rate / 8, law)
                        val home = if (law == inputLaw) homingCodes else crossHoming
                        home?.forEach { sync.decode(decoder, it) }
                        val codes = read(input)
                        val expected = read(output)
                        assertEquals(expected.size, codes.size, output.name)
                        for (i in codes.indices) {
                            val actual = sync.decode(decoder, codes[i])
                            if (actual != expected[i]) {
                                throw AssertionError(
                                    "${output.name}, from ${input.name}: word $i is " +
                                        "${actual.toString(16)}, not ${expected[i].toString(16)}",
                                )
                            }
                        }
                        compared++
                    }
                }
            }
        }
        assertEquals(80, compared)
    }

    /** The decoder's output stage for log PCM, section 4.2.8. */
    private class Sync(private val bits: Int, private val law: Int) {
        private val table = when (bits) {
            5 -> ID_40
            4 -> ID_32
            3 -> ID_24
            else -> ID_16
        }

        fun decode(decoder: G726Decoder, i: Int): Int {
            val sr = decoder.decode(i)
            val se = decoder.signalEstimate
            val y = decoder.scaleFactor
            val sp = compress(sr)
            // EXPAND, to the 14-bit uniform value of the PCM word.
            val slx = if (law == MU) G711Codec.decodeMuLaw(sp) shr 2 else G711Codec.decodeALaw(sp) shr 2
            // SUBTA and LOG.
            val dx = (slx - se) and 65535
            val dsx = dx shr 15
            val dqm = if (dsx == 0) dx else (65536 - dx) and 32767
            val exp = if (dqm <= 1) 0 else 31 - dqm.countLeadingZeroBits()
            val mant = ((dqm shl 7) shr exp) and 127
            val dlx = (exp shl 7) + mant
            // SUBTB.
            val dlnx = (dlx + 4096 - (y shr 2)) and 4095
            // SYNC.
            var id = -1
            for (row in 0 until table.size / 4) {
                if (table[row * 4] == dsx && dlnx in table[row * 4 + 1]..table[row * 4 + 2]) id = table[row * 4 + 3]
            }
            check(id >= 0)
            val half = 1 shl (bits - 1)
            val im = if (i shr (bits - 1) == 0) i + half else i and (half - 1)
            return when {
                id < im -> step(sp, up = true)
                id > im -> step(sp, up = false)
                else -> sp
            }
        }

        /** COMPRESS. */
        private fun compress(sr: Int): Int {
            val negative = sr < 0
            // The Recommendation's magnitude, (65536 - SR) & 32767, which takes -32768 to zero.
            val im = if (negative) -sr and 32767 else sr
            if (law == MU) return G711Codec.encodeMuLaw(if (negative) -(im shl 2) else im shl 2)
            val imag = if (negative) (im + 1) shr 1 else im shr 1
            // A negative magnitude on a decision value belongs to the interval below it, as Table 15 shows.
            val level = if (negative) (imag - 1).coerceAtLeast(0) else imag
            val (segment, mantissa) = when {
                level > 4095 -> 7 to 15
                level < 32 -> 0 to (level shr 1)
                else -> {
                    val s = 31 - level.countLeadingZeroBits() - 4
                    s to ((level shr s) and 15)
                }
            }
            return ((if (negative) 0 else 0x80) or (segment shl 4) or mantissa) xor 0x55
        }

        /** SP+ or SP-, the word for the next more positive or more negative output level. */
        private fun step(sp: Int, up: Boolean): Int {
            if (law == A) {
                val t = sp xor 0x55
                val positive = t and 0x80 != 0
                val m = t and 0x7F
                val next = when {
                    up && positive -> if (m < 127) 0x80 or (m + 1) else t
                    up -> if (m > 0) m - 1 else 0x80
                    positive -> if (m > 0) 0x80 or (m - 1) else 0
                    else -> if (m < 127) m + 1 else t
                }
                return next xor 0x55
            }
            val t = sp.inv() and 0xFF
            val negative = t and 0x80 != 0
            val m = t and 0x7F
            // Mu-law's two words for zero are one level, so stepping across zero passes over the other.
            val next = when {
                up && !negative -> if (m < 127) m + 1 else t
                up -> if (m > 0) 0x80 or (m - 1) else 1
                !negative -> if (m > 0) m - 1 else 0x81
                else -> if (m < 127) 0x80 or (m + 1) else t
            }
            return next.inv() and 0xFF
        }
    }

    private fun read(file: File): IntArray {
        val hex = file.readText().filter { it.isLetterOrDigit() }
        val values = IntArray(hex.length / 2 - 1) { hex.substring(2 * it, 2 * it + 2).toInt(16) }
        // The last two characters are a checksum, the values' sum modulo 255.
        assertEquals(hex.takeLast(2).toInt(16), values.sum() % 255, "${file.name}: checksum")
        return values
    }

    private fun root(): File {
        val dir = File(System.getProperty("user.dir"))
        val root = listOf(dir, dir.parentFile)
            .map { File(it, "reference/g72x/g726-appii") }
            .firstOrNull { File(it, "DISK1/INPUT/I32").isFile }
        assumeTrue("the G.726 Appendix II test sequences are required, under reference/g72x/g726-appii", root != null)
        assertTrue(File(root, "DISK2/INPUT/I32").isFile)
        return root!!
    }

    private companion object {
        const val MU = 0
        const val A = 1

        // Extracted from the Recommendation's text by reference/g72x/gen_tables.py: rows of DSX, lowest DLNX, highest DLNX, ID.
        val ID_40 = intArrayOf(0, 553, 2047, 31, 0, 528, 552, 30, 0, 502, 527, 29, 0, 475, 501, 28, 0, 445, 474, 27, 0, 413, 444, 26, 0, 378, 412, 25, 0, 339, 377, 24, 0, 298, 338, 23, 0, 250, 297, 22, 0, 198, 249, 21, 0, 139, 197, 20, 0, 68, 138, 19, 0, 0, 67, 18, 0, 4080, 4095, 18, 0, 3974, 4079, 17, 0, 2048, 3973, 15, 1, 2048, 3973, 15, 1, 3974, 4079, 14, 1, 4080, 4095, 13, 1, 0, 67, 13, 1, 68, 138, 12, 1, 139, 197, 11, 1, 198, 249, 10, 1, 250, 297, 9, 1, 298, 338, 8, 1, 339, 377, 7, 1, 378, 412, 6, 1, 413, 444, 5, 1, 445, 474, 4, 1, 475, 501, 3, 1, 502, 527, 2, 1, 528, 552, 1, 1, 553, 2047, 0)
        val ID_32 = intArrayOf(0, 400, 2047, 15, 0, 349, 399, 14, 0, 300, 348, 13, 0, 246, 299, 12, 0, 178, 245, 11, 0, 80, 177, 10, 0, 0, 79, 9, 0, 3972, 4095, 9, 0, 2048, 3971, 7, 1, 2048, 3971, 7, 1, 3972, 4095, 6, 1, 0, 79, 6, 1, 80, 177, 5, 1, 178, 245, 4, 1, 246, 299, 3, 1, 300, 348, 2, 1, 349, 399, 1, 1, 400, 2047, 0)
        val ID_24 = intArrayOf(0, 331, 2047, 7, 0, 218, 330, 6, 0, 8, 217, 5, 0, 0, 7, 3, 0, 2048, 4095, 3, 1, 2048, 4095, 3, 1, 0, 7, 3, 1, 8, 217, 2, 1, 218, 330, 1, 1, 331, 2047, 0)
        val ID_16 = intArrayOf(0, 261, 2047, 3, 0, 0, 260, 2, 0, 2048, 4095, 2, 1, 2048, 4095, 1, 1, 0, 260, 1, 1, 261, 2047, 0)
    }
}

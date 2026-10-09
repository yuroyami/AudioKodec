package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.RawTagBlock
import io.github.yuroyami.audiokodec.tag.RawTagEntry
import io.github.yuroyami.audiokodec.tag.RawTags
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Checks the iTunes gapless trim on ALAC files ffmpeg encodes.
 *
 * The input is a run of priming samples, an exact whole number of sine
 * periods, and a run of padding. ffmpeg encodes it without an edit list and
 * the file is given an `iTunSMPB` record naming the delay, the padding and
 * the sine's length. AudioKodec must decode exactly the sine periods, and
 * ffmpeg 6.1, which does not apply the record, must decode the same samples
 * once the stated delay and padding are cut from its output. Two decodes
 * joined end to end must continue the sine without a seam, which is the point
 * of the trim.
 *
 * Apple's `afconvert`, on macOS, must produce files that decode to exactly
 * the frames that went in.
 *
 * Every test skips itself when the tool it compares against is missing.
 */
class Mp4GaplessOracleTest {

    @Test
    fun anExactNumberOfPeriodsComesBackAndJoinsWithoutASeam() {
        val ffmpeg = findOnPath("ffmpeg")
        assumeTrue("ffmpeg is required for the MP4 gapless oracle", ffmpeg != null)
        for ((delay, padding) in listOf(2_112 to 1_000, 0 to 333, 4_096 to 0)) {
            val signal = IntArray(delay + FRAMES + padding)
            for (i in 0 until delay) signal[i] = 7_000
            for (i in 0 until FRAMES) signal[delay + i] = sine(i)
            for (i in 0 until padding) signal[delay + FRAMES + i] = -7_000
            val raw = temp("input-$delay-$padding.s16")
            raw.writeBytes(ByteBuffer.allocate(signal.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { buffer ->
                for (value in signal) buffer.putShort(value.toShort())
            }.array())
            val encoded = temp("encoded-$delay-$padding.m4a")
            run(listOf(ffmpeg!!, "-y", "-v", "error", "-f", "s16le", "-ar", "44100", "-ac", "1", "-i", raw.path,
                "-c:a", "alac", "-use_editlist", "0", encoded.path))

            val record = " 00000000 %08X %08X %016X 00000000 00000000".format(delay, padding, FRAMES.toLong())
            val original = encoded.readBytes()
            val read = AudioKodec.readTags(original)
            val withRecord = TagBundle(
                read.fields,
                raw = RawTags(read.raw.blocks + RawTagBlock(TagFormat.MP4_ILST, 0, 0, listOf(RawTagEntry("----:com.apple.iTunes:iTunSMPB", text = record)))),
            )
            val tagged = temp("gapless-$delay-$padding.m4a").also { it.writeBytes(AudioKodec.writeTags(original, withRecord)) }
            val bytes = tagged.readBytes()

            assertEquals(FRAMES.toLong(), AudioKodec.probe(bytes).durationFrames, "delay $delay, padding $padding")
            val decoded = ints(bytes)
            assertContentEquals(IntArray(FRAMES) { sine(it) }, decoded, "exactly the sine periods")
            val reference = pcm(ffmpeg, tagged)
            assertEquals(signal.size, reference.size, "ffmpeg 6.1 does not apply the record")
            assertContentEquals(reference.copyOfRange(delay, delay + FRAMES), decoded, "ffmpeg's samples, trimmed as stated")

            val joined = decoded + decoded
            assertContentEquals(IntArray(2 * FRAMES) { sine(it) }, joined, "two decodes join without a seam")

            AudioKodec.open(bytes).use { decoder ->
                val access = decoder as IntPcmAccess
                assertEquals(12_345L, decoder.seek(12_345))
                assertEquals(sine(12_345), access.nextIntFrame()!!.samples[0], "seeking counts from the trimmed start")
            }
        }
    }

    @Test
    fun appleAfconvertFilesDecodeToExactlyWhatWentIn() {
        val afconvert = findOnPath("afconvert")
        assumeTrue("afconvert is required for Apple's own MP4 files, and exists only on macOS", afconvert != null)
        val input = temp("afconvert-input.wav")
        input.writeBytes(AudioKodec.encode(
            FloatArray(FRAMES) { sine(it) / 32_768f }, AudioFormat(44_100, 1), EncodeTarget.wav(16),
        ))
        val encoded = temp("afconvert.m4a")
        encoded.delete()
        run(listOf(afconvert!!, "-f", "m4af", "-d", "alac", input.path, encoded.path))
        val bytes = encoded.readBytes()
        assertEquals(FRAMES.toLong(), AudioKodec.probe(bytes).durationFrames)
        val decoded = ints(bytes)
        assertContentEquals(IntArray(FRAMES) { sine(it) }, decoded)
        assertContentEquals(IntArray(2 * FRAMES) { sine(it) }, decoded + decoded, "two decodes join without a seam")
    }

    private fun sine(i: Int): Int = (12_000 * sin(2 * PI * i / PERIOD)).roundToInt()

    private fun ints(file: ByteArray): IntArray {
        val out = ArrayList<Int>()
        AudioKodec.open(file).use { decoder ->
            val access = decoder as IntPcmAccess
            while (true) {
                val frame = access.nextIntFrame() ?: break
                for (i in 0 until frame.sampleCount) out += frame.samples[i]
            }
        }
        return out.toIntArray()
    }

    private fun pcm(ffmpeg: String, file: File): IntArray {
        val raw = temp(file.name + ".s16")
        run(listOf(ffmpeg, "-y", "-v", "error", "-i", file.path, "-f", "s16le", raw.path))
        val buffer = ByteBuffer.wrap(raw.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(buffer.remaining() / 2) { buffer.short.toInt() }
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "${command.first()} did not finish within two minutes")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed:\n$output")
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private companion object {
        /** A whole number of 100-sample periods at 44.1 kHz. */
        const val PERIOD = 100
        const val FRAMES = 441 * PERIOD

        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-mp4-gapless-oracle").also { it.mkdirs() }

        fun findOnPath(tool: String): String? {
            val path = System.getenv("PATH") ?: return null
            for (directory in path.split(File.pathSeparator)) {
                val candidate = File(directory, tool)
                if (candidate.canExecute()) return candidate.absolutePath
            }
            return null
        }
    }
}

package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.bits.BitWriterMsb
import io.github.yuroyami.audiokodec.internal.mpeg.MpegCrc
import io.github.yuroyami.audiokodec.internal.mpeg.MpegFrameHeader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checks the MPEG frame checksum against ffmpeg's.
 *
 * ffmpeg decodes with `-err_detect crccheck+explode` as an independent judge:
 * every protected file here must pass it, and the same file with one checksum
 * bit flipped must fail it. Only then is AudioKodec's verdict on the same
 * bytes compared. Layer II comes protected from libtwolame. Nothing installable
 * writes protected Layer I or III, so those frames are built or converted here,
 * which is exactly why ffmpeg has to agree before they count.
 *
 * Every test skips itself when ffmpeg, or its libtwolame encoder, is missing.
 */
class MpegCrcOracleTest {

    private val ffmpeg: String? by lazy { findOnPath("ffmpeg") }

    @Test
    fun protectedLayerTwoFromTwolameIsCheckedFrameByFrame() {
        val ffmpeg = ffmpeg ?: return skip()
        for ((name, args) in listOf(
            "l2_stereo" to listOf("-ar", "44100", "-ac", "2", "-b:a", "192k"),
            "l2_lsf_mono" to listOf("-ar", "22050", "-ac", "1", "-b:a", "64k"),
        )) {
            val file = temp("$name.mp2")
            val made = runTool(
                listOf(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=997:duration=0.5") +
                    args + listOf("-c:a", "libtwolame", "-error_protection", "1", file.path),
            )
            if (!made) return skip()
            checkAgainstFfmpeg(ffmpeg, name, file.readBytes())
        }
    }

    @Test
    fun layerThreeSideInformationIsCheckedFrameByFrame() {
        val ffmpeg = ffmpeg ?: return skip()
        for ((name, args) in listOf(
            "l3_stereo" to listOf("-ar", "44100", "-ac", "2", "-b:a", "128k"),
            "l3_lsf_mono" to listOf("-ar", "22050", "-ac", "1", "-b:a", "32k"),
        )) {
            val plain = temp("$name.mp3")
            runTool(
                listOf(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=0.5") +
                    args + listOf("-c:a", "libmp3lame", "-write_xing", "0", plain.path),
            )
            checkAgainstFfmpeg(ffmpeg, name, protectLayerThree(plain.readBytes()))
        }
    }

    @Test
    fun layerOneBitAllocationIsCheckedFrameByFrame() {
        val ffmpeg = ffmpeg ?: return skip()
        // Plain stereo, and joint stereo whose bound shares allocations above
        // subband 4, which changes how many bits the checksum covers.
        checkAgainstFfmpeg(ffmpeg, "l1_stereo", layerOneFrames(modeByte = 0x00, bound = 32))
        // ffmpeg checks a joint stereo frame's allocation as though it had no
        // bound, four bits for every subband of both channels.
        checkAgainstFfmpeg(ffmpeg, "l1_joint", layerOneFrames(modeByte = 0x40, bound = 4, protectedBits = 256))
    }

    @Test
    fun layerOneJointStereoAlsoPassesWithTheAllocationActuallySent() {
        // Four subbands of both channels, then twenty-eight shared ones: the
        // allocation as transmitted, which is what the standard protects.
        val protected = layerOneFrames(modeByte = 0x40, bound = 4, protectedBits = 4 * 2 * 4 + 28 * 4)
        AudioKodec.decode(protected)
        val damaged = protected.copyOf().also { it[4] = (it[4].toInt() xor 1).toByte() }
        assertFailsWith<CorruptStreamException> { AudioKodec.decode(damaged) }
    }

    /**
     * Requires ffmpeg to accept [protected] and reject it with one checksum
     * bit flipped, then requires AudioKodec to do the same strictly and to
     * decode both alike tolerantly.
     */
    private fun checkAgainstFfmpeg(ffmpeg: String, name: String, protected: ByteArray) {
        val damaged = protected.copyOf().also { it[4] = (it[4].toInt() xor 1).toByte() }
        val good = temp("${name}_crc.bin").also { it.writeBytes(protected) }
        val bad = temp("${name}_badcrc.bin").also { it.writeBytes(damaged) }
        assertTrue(ffmpegAcceptsChecksums(ffmpeg, good), "$name: ffmpeg rejects the protected frames")
        assertTrue(!ffmpegAcceptsChecksums(ffmpeg, bad), "$name: ffmpeg accepts a damaged checksum")

        val decoded = AudioKodec.decode(protected)
        val failure = assertFailsWith<CorruptStreamException>(name) { AudioKodec.decode(damaged) }
        assertTrue("checksum" in failure.message.orEmpty(), "$name: ${failure.message}")
        val tolerant = DecodeOptions(tolerant = true)
        assertEquals(
            AudioKodec.decode(protected, tolerant).samples.toList(),
            AudioKodec.decode(damaged, tolerant).samples.toList(),
            name,
        )
        assertEquals(decoded.samples.toList(), AudioKodec.decode(protected, tolerant).samples.toList(), name)
    }

    /**
     * Turns unprotected Layer III frames into protected ones of the same
     * length: the checksum goes after the header and the last two bytes of
     * main data make room for it. The audio is damaged by that, and it does
     * not matter, because only the side information is checked.
     */
    private fun protectLayerThree(plain: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        var at = 0
        while (at + 4 <= plain.size) {
            val header = MpegFrameHeader.parse(plain, at)
            if (header == null || at + header.frameBytes > plain.size) {
                at++
                continue
            }
            val frame = ByteArray(header.frameBytes)
            plain.copyInto(frame, 0, at, at + 4)
            frame[1] = (frame[1].toInt() and 0xFE).toByte()
            plain.copyInto(frame, 6, at + 4, at + header.frameBytes - 2)
            val crc = MpegCrc.compute(frame, header.sideInfoBytes * 8)
            frame[4] = (crc ushr 8).toByte()
            frame[5] = crc.toByte()
            frame.forEach { out.add(it) }
            at += header.frameBytes
        }
        return out.toByteArray()
    }

    /**
     * Forty protected MPEG-1 Layer I frames at 384 kbit/s and 44.1 kHz, with
     * random allocations, scalefactors and samples. The allocations stay
     * narrow so that every frame's samples fit in its 416 bytes.
     */
    private fun layerOneFrames(
        modeByte: Int,
        bound: Int,
        protectedBits: Int = bound * 2 * 4 + (32 - bound) * 4,
    ): ByteArray {
        val random = Random(1_234 + modeByte)
        val frames = ArrayList<Byte>()
        repeat(40) {
            val bits = BitWriterMsb(512)
            val allocation = Array(2) { IntArray(32) }
            for (sb in 0 until 32) {
                if (sb < bound) {
                    for (ch in 0 until 2) allocation[ch][sb] = random.nextInt(0, 3)
                    for (ch in 0 until 2) bits.writeBits(allocation[ch][sb], 4)
                } else {
                    val shared = random.nextInt(0, 3)
                    allocation[0][sb] = shared
                    allocation[1][sb] = shared
                    bits.writeBits(shared, 4)
                }
            }
            for (sb in 0 until 32) for (ch in 0 until 2) {
                if (allocation[ch][sb] != 0) bits.writeBits(random.nextInt(0, 63), 6)
            }
            repeat(12) {
                for (sb in 0 until 32) {
                    val channels = if (sb < bound) 2 else 1
                    for (ch in 0 until channels) {
                        val width = allocation[ch][sb].let { if (it == 0) 0 else it + 1 }
                        // A code of all ones is reserved.
                        if (width > 0) bits.writeBits(random.nextInt(0, (1 shl width) - 1), width)
                    }
                }
            }
            val frame = ByteArray(416)
            check(bits.byteLength <= frame.size - 6) { "a Layer I test frame overflows" }
            frame[0] = 0xFF.toByte()
            frame[1] = 0xFE.toByte()
            frame[2] = 0xC0.toByte()
            frame[3] = modeByte.toByte()
            bits.alignToByte()
            val payload = bits.toByteArray()
            payload.copyInto(frame, 6)
            val crc = MpegCrc.compute(frame, protectedBits)
            frame[4] = (crc ushr 8).toByte()
            frame[5] = crc.toByte()
            frame.forEach { frames.add(it) }
        }
        return frames.toByteArray()
    }

    private fun ffmpegAcceptsChecksums(ffmpeg: String, file: File): Boolean {
        val process = ProcessBuilder(
            ffmpeg, "-v", "error", "-err_detect", "crccheck+explode", "-i", file.path, "-f", "null", "-",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "ffmpeg did not finish")
        return !output.contains("crc", ignoreCase = true)
    }

    private fun runTool(command: List<String>): Boolean {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "${command.first()} did not finish")
        return process.exitValue() == 0
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private fun skip() {
        org.junit.Assume.assumeTrue("ffmpeg or its libtwolame encoder is missing, so the checksum comparison did not run", false)
    }

    private companion object {
        val workDirectory: File = File(
            System.getProperty("java.io.tmpdir"), "audiokodec-mpeg-crc-oracle",
        ).also { it.mkdirs() }

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

package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.ogg.OggCrc
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** Uses executable tools and libopusfile's documented public API, never their source. */
class OggChainOracleTest {
    @Test
    fun opusChainsAgreeWithLibopusfileIncludingSurroundGainAndPerLinkTrimming() = inDirectory { directory ->
        val ffmpeg = tool("ffmpeg")
        val python = System.getenv("AUDIOKODEC_MUTAGEN_PYTHON") ?: tool("python3")
        for (channels in listOf(1, 2, 6)) {
            val lengths = listOf(24_000, 57_517)
            val parts = lengths.mapIndexed { index, frames ->
                encode(ffmpeg, directory, "opus-$channels-$index", "libopus", frames, channels, index)
                    .readBytes().let { bytes ->
                        if (index == 0) bytes else adjustOpus(bytes, extraPreSkip = 97, gainQ8 = -768, endTrim = 31)
                    }
            }
            val expected = parts.map { AudioKodec.decode(it).samples }.reduce { a, b -> a + b }
            val chain = File(directory, "chain-$channels.opus").also { it.writeBytes(parts[0] + parts[1]) }
            val output = File(directory, "libopusfile-$channels.f32")
            val result = run(listOf(python, "-c", OPUSFILE_BRIDGE, chain.path, output.path, channels.toString()), directory, allowMissing = true)
            if (result.first == 2 && System.getenv("AUDIOKODEC_OPUSFILE_LIBRARY") == null) {
                assumeTrue("libopusfile is required for the chained Opus comparison", false)
            }
            assertEquals(0, result.first, result.second)
            val total = lengths.sum() - 97 - 31
            assertEquals("2 $total", result.second.trim(), "independent link count and PCM duration")
            val decoded = AudioKodec.decode(chain.readBytes())
            assertEquals(total, decoded.frames)
            assertEquals(total.toLong(), decoded.info.durationFrames)
            assertContentEquals(expected, decoded.samples, "each link starts with independent codec state")
            // The existing Ogg oracle permits 1e-4 per sample for floating
            // reconstruction. Apply it independently to every surround channel.
            TestSignals.assertClose(floats(output), decoded.samples, 1e-4f, "libopusfile, $channels channels")
            checkSeeks(chain.readBytes(), expected, channels, lengths[0], opus = true, parts = parts)
        }
    }

    @Test
    fun theIssueReproducerReturnsBothTonesAndTheFullFfmpegDuration() = inDirectory { directory ->
        val ffmpeg = tool("ffmpeg")
        val parts = (0..1).map { index ->
            val file = File(directory, "reproducer-$index.opus")
            run(listOf(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i",
                "sine=frequency=${997 - 498 * index}:sample_rate=48000:duration=0.5", "-c:a", "libopus",
                "-serial_offset", "${index + 1}", file.path), directory)
            file.readBytes()
        }
        val file = File(directory, "reproducer-chain.opus").also { it.writeBytes(parts[0] + parts[1]) }
        val output = File(directory, "reproducer.f32")
        run(listOf(ffmpeg, "-v", "error", "-y", "-i", file.path, "-f", "f32le", output.path), directory)
        assertEquals(48_000 * 4L, output.length(), "FFmpeg sees both half-second links")
        val decoded = AudioKodec.decode(file.readBytes())
        assertEquals(48_000, decoded.frames)
        assertContentEquals(AudioKodec.decode(parts[0]).samples + AudioKodec.decode(parts[1]).samples, decoded.samples)
        // FFmpeg can retain the preceding link's Opus predictor state. PCM
        // correctness across the boundary is checked with libopusfile above.
    }

    @Test
    fun vorbisSetupChangesAndPositiveGranuleOriginsKeepEveryLink() = inDirectory { directory ->
        val ffmpeg = tool("ffmpeg")
        for (channels in listOf(1, 2, 6)) {
            val lengths = listOf(48_123, 81_017)
            val parts = lengths.mapIndexed { index, frames ->
                val original = encode(ffmpeg, directory, "vorbis-$channels-$index", "libvorbis", frames, channels, index)
                val shifted = if (index == 0) original.readBytes() else OggGranuleFixtures.shift(original.readBytes(), 20_000)
                val file = File(directory, "shifted-vorbis-$channels-$index.ogg").also { it.writeBytes(shifted) }
                val output = File(directory, "vorbis-$channels-$index.f32")
                run(listOf(ffmpeg, "-v", "error", "-y", "-i", file.path, "-f", "f32le", output.path), directory)
                val ours = AudioKodec.decode(shifted)
                assertEquals(frames, ours.frames)
                val reference = floats(output)
                // FFmpeg does not consistently honour Vorbis terminal overlap.
                // The encoded input gives the length; compare all PCM shared
                // with each independently opened reference link, within 1e-4.
                val shared = minOf(reference.size, ours.samples.size)
                TestSignals.assertClose(reference.copyOf(shared), ours.samples.copyOf(shared), 1e-4f, file.name)
                shifted
            }
            val bytes = parts[0] + parts[1]
            val expected = parts.map { AudioKodec.decode(it).samples }.reduce { a, b -> a + b }
            assertEquals(lengths.sum().toLong(), AudioKodec.probe(bytes).durationFrames)
            assertContentEquals(expected, AudioKodec.decode(bytes).samples)
            checkSeeks(bytes, expected, channels, lengths[0], opus = false)
        }
    }

    @Test
    fun referenceFlacChainsRetainEveryIntegerIncluding32BitExtremes() = inDirectory { directory ->
        val flac = tool("flac")
        val ffmpeg = tool("ffmpeg")
        for (depth in listOf(16, 24, 32)) for (channels in listOf(1, 2)) {
            val samples = listOf(5_003, 8_117).mapIndexed { index, frames ->
                IntegerEncodingFixtures.samples(depth, frames, channels).also { values ->
                    if (index == 1) for (at in values.indices) values[at] = values[at].inv()
                }
            }
            val parts = samples.mapIndexed { index, values ->
                val raw = File(directory, "flac-$depth-$channels-$index.raw")
                raw.writeBytes(ByteArray(values.size * (depth / 8)).also { bytes ->
                    for (at in values.indices) repeat(depth / 8) { byte ->
                        bytes[at * (depth / 8) + byte] = (values[at] ushr (8 * byte)).toByte()
                    }
                })
                val file = File(directory, "flac-$depth-$channels-$index.ogg")
                run(listOf(flac, "--silent", "--force", "-0", "--ogg", "--serial-number=${index + 101}",
                    "--force-raw-format", "--endian=little", "--sign=signed", "--channels=$channels", "--bps=$depth",
                    "--sample-rate=48000", "-o", file.path, raw.path), directory)
                file.readBytes().let { if (index == 0) it else OggGranuleFixtures.shift(it, 10_000) }
            }
            val bytes = parts[0] + parts[1]
            val expected = samples[0] + samples[1]
            val file = File(directory, "flac-chain-$depth-$channels.ogg").also { it.writeBytes(bytes) }
            val output = File(directory, "flac-chain-$depth-$channels.s32")
            run(listOf(ffmpeg, "-v", "error", "-y", "-i", file.path, "-c:a", "pcm_s32le", "-f", "s32le", output.path), directory)
            val integers = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            val reference = IntArray(integers.remaining()) { integers.get() shr (32 - depth) }
            assertContentEquals(expected, reference, "independent lossless chained PCM, $depth bits")
            for (reuse in listOf(false, true)) {
                AudioKodec.open(bytes, DecodeOptions(reuseBuffers = reuse, allowSlowSeek = false)).use { decoder ->
                    assertEquals(13_120L, decoder.durationFrames)
                    val access = decoder as IntPcmAccess
                    assertEquals(depth, access.bitDepth)
                    val got = ArrayList<Int>()
                    while (true) {
                        val block = access.nextIntFrame() ?: break
                        assertEquals((got.size / channels).toLong(), block.positionFrames)
                        repeat(block.sampleCount) { got += block.samples[it] }
                    }
                    assertContentEquals(expected, got.toIntArray())
                    for (target in listOf(5_003, 5_002, 5_004, 10_000, 13_119, 0, 13_120)) {
                        assertEquals(target.toLong(), decoder.seek(target.toLong()))
                        val block = access.nextIntFrame()
                        if (target == 13_120) assertNull(block) else {
                            val frame = assertNotNull(block)
                            assertEquals(target.toLong(), frame.positionFrames)
                            assertContentEquals(expected.copyOfRange(target * channels, target * channels + frame.sampleCount),
                                frame.samples.copyOf(frame.sampleCount))
                        }
                    }
                }
            }
        }
    }

    private fun encode(ffmpeg: String, directory: File, name: String, encoder: String, frames: Int, channels: Int, index: Int): File {
        val input = File(directory, "$name.wav")
        val signal = FloatArray(frames * channels) { at ->
            val t = (at / channels) / 48_000.0
            (0.35 * kotlin.math.sin(2 * Math.PI * ((311 + index * 173 + (at % channels) * 251) * t + 137 * t * t))).toFloat()
        }
        input.writeBytes(AudioKodec.encode(signal, AudioFormat(48_000, channels), EncodeTarget.wav(16)))
        val file = File(directory, "$name.ogg")
        val coding = if (encoder == "libvorbis") listOf("-q:a", if (index == 0) "0" else "9")
            else listOf("-b:a", "${channels * 64}k")
        run(listOf(ffmpeg, "-v", "error", "-y", "-i", input.path, "-c:a", encoder) + coding +
            listOf("-page_duration", "100000", "-serial_offset", "${index + 1}", "-f", "ogg", file.path), directory)
        return file
    }

    private fun adjustOpus(original: ByteArray, extraPreSkip: Int, gainQ8: Int, endTrim: Long): ByteArray = original.copyOf().also { bytes ->
        val pages = OggDamageFixtures.pages(bytes)
        val head = 27 + (bytes[26].toInt() and 255)
        val preSkip = (bytes[head + 10].toInt() and 255) or ((bytes[head + 11].toInt() and 255) shl 8)
        repeat(2) { bytes[head + 10 + it] = ((preSkip + extraPreSkip) ushr (8 * it)).toByte() }
        repeat(2) { bytes[head + 16 + it] = (gainQ8 ushr (8 * it)).toByte() }
        val end = OggDamageFixtures.granule(bytes, pages.last()) - endTrim
        repeat(8) { bytes[pages.last().first + 6 + it] = (end ushr (8 * it)).toByte() }
        for (page in listOf(pages.first(), pages.last())) {
            val crc = OggCrc.ofPage(bytes, page.first, page.last + 1, page.first + 22)
            repeat(4) { bytes[page.first + 22 + it] = (crc ushr (8 * it)).toByte() }
        }
    }

    private fun checkSeeks(bytes: ByteArray, whole: FloatArray, channels: Int, boundary: Int, opus: Boolean,
        parts: List<ByteArray> = emptyList()) {
        val total = whole.size / channels
        for (reuse in listOf(false, true)) AudioKodec.open(bytes, DecodeOptions(reuseBuffers = reuse, allowSlowSeek = false)).use { decoder ->
            for (target in listOf(boundary, boundary - 1, boundary + 73, boundary + 16_000, total - 500, 0, 100)) {
                assertEquals(target.toLong(), decoder.seek(target.toLong()))
                val got = ArrayList<Float>()
                val count = minOf(9_600, total - target)
                while (got.size < count * channels) {
                    val block = assertNotNull(decoder.nextFrame())
                    assertEquals((target + got.size / channels).toLong(), block.positionFrames)
                    repeat(block.sampleCount) { got += block.samples[it] }
                }
                if (!opus) TestSignals.assertClose(whole.copyOfRange(target * channels, (target + count) * channels),
                    got.take(count * channels).toFloatArray(), 1e-4f, "chained Vorbis seek to $target")
                else {
                    // Compare the first samples to an independent seek inside
                    // the selected link. This separates chain positioning from
                    // the codec's existing predictor convergence after preroll.
                    val selected = if (target < boundary) 0 else 1
                    AudioKodec.open(parts[selected], DecodeOptions(reuseBuffers = reuse, allowSlowSeek = false)).use { separate ->
                        separate.seek((target - if (selected == 0) 0 else boundary).toLong())
                        val independent = ArrayList<Float>()
                        while (independent.size < count * channels) {
                            val frame = separate.nextFrame() ?: break
                            repeat(frame.sampleCount) { independent += frame.samples[it] }
                        }
                        val shared = minOf(independent.size, count * channels)
                        assertContentEquals(independent.take(shared).toFloatArray(), got.take(shared).toFloatArray(),
                            "chained Opus seek to $target equals a seek within link $selected")
                    }
                    fun worst(lag: Int, from: Int): Float = (from * channels until count * channels).maxOf { at ->
                        val index = (target + lag) * channels + at
                        if (index in whole.indices) abs(got[at] - whole[index]) else 0f
                    }
                    val from = if (count == 9_600) 7_200 else 0
                    val aligned = worst(0, from)
                    // A 500-frame tail cannot supply the settling window.
                    // It must still align better than either one-frame shift;
                    // complete windows use the existing fourfold margin.
                    val margin = if (count == 9_600) 4 else 1
                    assertTrue(aligned * margin < minOf(worst(-1, from), worst(1, from)),
                        "Opus seek to $target, $channels channels: aligned=$aligned, shifted=${worst(-1, from)}, ${worst(1, from)}")
                    // Match the existing Opus seek oracle's 200 ms window:
                    // the 80 ms preroll must settle within 1e-3 by 150 ms.
                    if (count == 9_600) assertTrue(worst(0, 7_200) < 1e-3f, "Opus predictor state after seek to $target")
                }
            }
            assertEquals(total.toLong(), decoder.seek(total + 10_000L))
            assertNull(decoder.nextFrame())
        }
    }

    private fun floats(file: File): FloatArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()) { buffer.get() }
    }

    private fun tool(name: String): String {
        val found = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }
        assumeTrue("$name is required for chained Ogg comparisons", found != null)
        return found!!.path
    }

    private fun run(command: List<String>, directory: File, allowMissing: Boolean = false): Pair<Int, String> {
        val log = File(directory, "oracle.log")
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).oracleStart()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            throw AssertionError("${command.first()} did not finish")
        }
        val output = log.readText()
        if (!allowMissing) assertEquals(0, process.exitValue(), output)
        return process.exitValue() to output
    }

    private fun inDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("audiokodec-ogg-chain-oracle-").toFile()
        try { test(directory) } finally { directory.deleteRecursively() }
    }

    private companion object {
        // Public APIs documented at opus-codec.org/docs/opusfile_api-0.12/.
        const val OPUSFILE_BRIDGE = """
import ctypes as c, ctypes.util, os, struct, sys
path = os.environ.get('AUDIOKODEC_OPUSFILE_LIBRARY') or ctypes.util.find_library('opusfile')
if not path: sys.exit(2)
try: lib = c.CDLL(path)
except OSError:
    if os.environ.get('AUDIOKODEC_OPUSFILE_LIBRARY'): raise
    sys.exit(2)
lib.op_open_file.argtypes = [c.c_char_p, c.POINTER(c.c_int)]
lib.op_open_file.restype = c.c_void_p
lib.op_free.argtypes = [c.c_void_p]
lib.op_link_count.argtypes = [c.c_void_p]
lib.op_pcm_total.argtypes = [c.c_void_p, c.c_int]
lib.op_pcm_total.restype = c.c_int64
lib.op_read_float.argtypes = [c.c_void_p, c.POINTER(c.c_float), c.c_int, c.POINTER(c.c_int)]
lib.op_channel_count.argtypes = [c.c_void_p, c.c_int]
error = c.c_int()
file = lib.op_open_file(os.fsencode(sys.argv[1]), c.byref(error))
assert file, error.value
channels = int(sys.argv[3])
order = [0, 2, 1, 5, 3, 4] if channels == 6 else list(range(channels))
buffer = (c.c_float * (5760 * channels))()
link = c.c_int()
try:
    print(lib.op_link_count(file), lib.op_pcm_total(file, -1))
    with open(sys.argv[2], 'wb') as output:
        while True:
            count = lib.op_read_float(file, buffer, len(buffer), c.byref(link))
            assert count >= 0, count
            if not count: break
            assert lib.op_channel_count(file, link.value) == channels
            values = [buffer[frame * channels + channel] for frame in range(count) for channel in order]
            output.write(struct.pack('<' + 'f' * len(values), *values))
finally: lib.op_free(file)
"""
    }
}

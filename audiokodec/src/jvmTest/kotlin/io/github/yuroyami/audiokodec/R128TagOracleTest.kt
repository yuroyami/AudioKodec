package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.ogg.OggReader
import io.github.yuroyami.audiokodec.tag.ReplayGain
import io.github.yuroyami.audiokodec.tag.TagBundle
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Calls libopusfile's documented gain helpers as an executable oracle. */
class R128TagOracleTest {
    private val python: String by lazy {
        val executable = System.getenv("AUDIOKODEC_MUTAGEN_PYTHON") ?: findOnPath("python3")
        assumeTrue("Python is required for the libopusfile oracle", executable != null)
        val result = execute(listOf(executable!!, "-c", BRIDGE, "probe"))
        if (System.getenv("AUDIOKODEC_OPUSFILE_LIBRARY") == null) {
            assumeTrue("Install libopusfile or set AUDIOKODEC_OPUSFILE_LIBRARY", result.first != 2)
        }
        assertEquals(0, result.first, result.second)
        executable
    }

    @Test
    fun parsedGainsAgreeWithTheReferenceForConformingSyntaxAndOverflow() {
        val python = python
        val values = R128TagFixtures.valid.map { it.first } +
            listOf("32768", "-32769", "2147483647", "-2147483648", " 0", "0 ", "\t0", "0\n", "١", "１", "−1", "0 dB", "1.0", "1e2")
        val packet = File(directory, "reader-tags.bin")
        for (value in values) {
            val comments = listOf("R128_TRACK_GAIN=$value", "R128_ALBUM_GAIN=$value")
            packet.writeBytes("OpusTags".encodeToByteArray() + TagStressFixtures.commentBody(comments))
            val expected = query(python, packet)
            val actual = AudioKodec.readTags(R128TagFixtures.opus(comments)).replayGain
            if (expected.first == null && expected.second == null) assertNull(actual, value)
            else {
                val gain = assertNotNull(actual, value)
                assertEquals(expected.first?.div(256.0), gain.trackGainDb, value)
                assertEquals(expected.second?.div(256.0), gain.albumGainDb, value)
                assertEquals(true, gain.isR128)
            }
        }
    }

    @Test
    fun rewrittenOpusGainsRemainReadableByTheReferenceAndAudioPacketsAreUnchanged() {
        val python = python
        val ffmpeg = findOnPath("ffmpeg")
        assumeTrue("ffmpeg is required for the Opus audio fixture", ffmpeg != null)
        val file = File(directory, "gain.opus")
        val command = listOf(ffmpeg!!, "-y", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=0.1", "-c:a", "libopus", file.path)
        val encoded = execute(command)
        assertEquals(0, encoded.first, encoded.second)
        val original = file.readBytes()
        val originalPackets = packets(original)
        val packet = File(directory, "written-tags.bin")
        for ((value, integer) in R128TagFixtures.rounded) {
            val written = AudioKodec.writeTags(original, TagBundle(emptyMap(), replayGain = ReplayGain(trackGainDb = value, albumGainDb = value, isR128 = true)))
            val actualPackets = packets(written)
            packet.writeBytes(actualPackets[1].first)
            assertEquals(integer to integer, query(python, packet))
            assertContentEquals(originalPackets[0].first, actualPackets[0].first)
            assertEquals(originalPackets.size, actualPackets.size)
            for (index in 2 until originalPackets.size) {
                assertContentEquals(originalPackets[index].first, actualPackets[index].first)
                assertEquals(originalPackets[index].second, actualPackets[index].second)
            }
        }
    }

    private fun query(python: String, packet: File): Pair<Int?, Int?> {
        val result = execute(listOf(python, "-c", BRIDGE, packet.path))
        assertEquals(0, result.first, result.second)
        val values = result.second.trim().split('\n').map {
            if (it == "null") null else assertNotNull(it.toIntOrNull(), "unexpected reference output: $it")
        }
        assertEquals(2, values.size, result.second)
        return values[0] to values[1]
    }

    private fun packets(bytes: ByteArray): List<Pair<ByteArray, Long>> {
        val reader = OggReader(SourceReader(bytes.asAudioSource(), DecodeOptions.DEFAULT), DecodeOptions.DEFAULT)
        val serial = reader.readStreamHeads().single().serialNumber
        val cursor = reader.packets(serial, 0L)
        val result = ArrayList<Pair<ByteArray, Long>>()
        while (true) {
            val packet = cursor.next() ?: break
            result.add(packet.bytes.copyOf(packet.length) to packet.granule)
        }
        return result
    }

    private fun execute(command: List<String>): Pair<Int, String> {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("${command.first()} did not finish")
        }
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    }

    private fun findOnPath(name: String): String? = System.getenv("PATH")?.split(File.pathSeparator)
        ?.map { File(it, name) }?.firstOrNull { it.canExecute() }?.absolutePath

    private companion object {
        val directory = File(System.getProperty("java.io.tmpdir"), "audiokodec-r128-oracle").also { it.mkdirs() }
        const val BRIDGE = """
import ctypes as c, ctypes.util, os, sys
from pathlib import Path
path = os.environ.get('AUDIOKODEC_OPUSFILE_LIBRARY') or ctypes.util.find_library('opusfile')
if not path: sys.exit(2)
try: lib = c.CDLL(path)
except OSError:
    if os.environ.get('AUDIOKODEC_OPUSFILE_LIBRARY'): raise
    sys.exit(2)
class Tags(c.Structure):
    _fields_ = [('user_comments', c.POINTER(c.c_char_p)), ('comment_lengths', c.POINTER(c.c_int)), ('comments', c.c_int), ('vendor', c.c_char_p)]
lib.opus_tags_parse.argtypes = [c.POINTER(Tags), c.c_void_p, c.c_size_t]
lib.opus_tags_clear.argtypes = [c.POINTER(Tags)]
helpers = [lib.opus_tags_get_track_gain, lib.opus_tags_get_album_gain]
for helper in helpers: helper.argtypes = [c.POINTER(Tags), c.POINTER(c.c_int)]
if sys.argv[1] == 'probe': sys.exit(0)
body = Path(sys.argv[1]).read_bytes()
buffer = c.create_string_buffer(body)
tags = Tags()
assert lib.opus_tags_parse(c.byref(tags), buffer, len(body)) == 0
try:
    for helper in helpers:
        gain = c.c_int()
        result = helper(c.byref(tags), c.byref(gain))
        print(gain.value if result == 0 else 'null')
finally: lib.opus_tags_clear(c.byref(tags))
"""
    }
}

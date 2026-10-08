package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat
import io.github.yuroyami.audiokodec.tag.TagWriteOptions
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Only public executables and Python's standard charset codecs are used. */
class NativeTextEncodingOracleTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "audiokodec-native-encoding-oracle").also { it.mkdirs() }

    @Test
    fun ffmpegUtf8NativeTagsSurviveRepeatedEditsInEveryContainer() {
        val ffmpeg = tool("ffmpeg")
        val ffprobe = tool("ffprobe")
        for (extension in listOf("wav", "rf64", "aiff", "aifc")) {
            val file = independentAudio(ffmpeg, extension, tagged = true)
            val source = file.readBytes()
            val payload = NativeTextListFixtures.payload(source)
            val decoded = pcm(ffmpeg, file, "original-$extension")
            repeat(3) { edit ->
                val read = AudioKodec.readTags(file.readBytes())
                assertEquals(NativeTextEncodingFixtures.title, read[TagField.TITLE])
                assertEquals("Unicode 日本語 comment", read[TagField.COMMENT])
                file.writeBytes(AudioKodec.writeTags(file.readBytes(), read.edit { set(TagField.ARTIST, "Edit $edit") }))
                assertEquals(NativeTextEncodingFixtures.title, run(listOf(ffprobe, "-v", "error", "-show_entries",
                    "format_tags=title", "-of", "default=noprint_wrappers=1:nokey=1", file.path)).trim())
                assertContentEquals(payload, NativeTextListFixtures.payload(file.readBytes()))
                assertContentEquals(decoded, pcm(ffmpeg, file, "edited-$extension-$edit"))
            }
        }
    }

    @Test
    fun ffprobeAndExiftoolReadNativeUtf8BeforeAndAfterDeclaredRewrites() {
        val ffmpeg = tool("ffmpeg")
        val ffprobe = tool("ffprobe")
        val exiftool = tool("exiftool")
        for (extension in listOf("wav", "rf64")) {
            val original = independentAudio(ffmpeg, extension, tagged = false).readBytes()
            for (codePage in listOf<Int?>(null, 65001)) {
                val file = File(directory, "native-$codePage.$extension")
                var bytes = if (codePage == null) original else NativeTextListFixtures.append(original,
                    listOf(NativeTextEncodingFixtures.cset(codePage)))
                val expected = mapOf(TagField.TITLE to listOf(NativeTextEncodingFixtures.title))
                repeat(3) {
                    bytes = AudioKodec.writeTags(bytes, TagBundle(expected), TagWriteOptions(formats = setOf(TagFormat.RIFF_INFO)))
                    file.writeBytes(bytes)
                    assertEquals(expected, AudioKodec.readTags(bytes).fields)
                    assertEquals(NativeTextEncodingFixtures.title, run(listOf(ffprobe, "-v", "error", "-show_entries",
                        "format_tags=title", "-of", "default=noprint_wrappers=1:nokey=1", file.path)).trim())
                    // Undeclared UTF-8 needs an explicit hint for ExifTool.
                    // CSET 65001 is checked with its automatic interpretation.
                    val hint = if (codePage == null) listOf("-charset", "RIFF=UTF8") else emptyList()
                    assertEquals(NativeTextEncodingFixtures.title,
                        run(listOf(exiftool, "-s3", "-RIFF:Title") + hint + file.path).trim())
                    assertContentEquals(NativeTextListFixtures.payload(original), NativeTextListFixtures.payload(bytes))
                }
            }
        }
    }

    @Test
    fun pythonCodecsAgreeWithEveryDefinedLegacyHighByteAndRewrite() {
        val ffmpeg = tool("ffmpeg")
        val python = tool("python3")
        for (extension in listOf("wav", "rf64")) {
            val base = independentAudio(ffmpeg, extension, tagged = false).readBytes()
            for ((codePage, charset) in listOf(0 to "latin1", 28591 to "latin1", 1252 to "cp1252")) {
                val raw = File(directory, "all-$codePage-$extension.bin")
                val expected = run(listOf(python, "-c", LEGACY, charset, raw.path, "write"))
                var source = NativeTextEncodingFixtures.text(base, raw.readBytes(), codePage, after = true)
                val file = File(directory, "legacy-$codePage.$extension")
                val cset = NativeTextListFixtures.chunks(source).single { it.first == "CSET" }.second
                repeat(3) { edit ->
                    val tags = AudioKodec.readTags(source)
                    assertEquals(expected, tags[TagField.TITLE])
                    source = AudioKodec.writeTags(source, tags.edit { set(TagField.ARTIST, "Edit $edit") },
                        TagWriteOptions(formats = setOf(TagFormat.RIFF_INFO)))
                    assertEquals(expected, AudioKodec.readTags(source)[TagField.TITLE])
                    assertContentEquals(cset, NativeTextListFixtures.chunks(source).single { it.first == "CSET" }.second)
                    assertContentEquals(NativeTextListFixtures.payload(base), NativeTextListFixtures.payload(source))
                    file.writeBytes(source)
                    assertEquals(expected, run(listOf(python, "-c", LEGACY, charset, file.path, "check")))
                }
            }
        }
    }

    private fun independentAudio(ffmpeg: String, extension: String, tagged: Boolean): File {
        val file = File(directory, "source-$tagged.$extension")
        val codec = when (extension) { "wav", "rf64" -> "pcm_s16le"; "aifc" -> "pcm_f32be"; else -> "pcm_s16be" }
        val format = if (extension == "wav" || extension == "rf64") "wav" else "aiff"
        val rf64 = if (extension == "rf64") listOf("-rf64", "always") else emptyList()
        val tags = if (tagged) listOf("-metadata", "title=${NativeTextEncodingFixtures.title}",
            "-metadata", "comment=Unicode 日本語 comment") else listOf("-map_metadata", "-1", "-bitexact")
        run(listOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
            "sine=frequency=400:sample_rate=8000:duration=0.01", "-c:a", codec, "-f", format) + rf64 + tags + file.path)
        return file
    }

    private fun pcm(ffmpeg: String, file: File, name: String): ByteArray {
        val output = File(directory, "$name.f32")
        run(listOf(ffmpeg, "-v", "error", "-y", "-i", file.path, "-f", "f32le", output.path))
        return output.readBytes()
    }

    private fun tool(name: String): String {
        val found = System.getenv("PATH")?.split(File.pathSeparator)?.map { File(it, name) }?.firstOrNull { it.canExecute() }
        assumeTrue("$name is required for native charset checks", found != null)
        return found!!.path
    }

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).oracleStart()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue(finished, "${command.first()} did not finish")
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        assertEquals(0, process.exitValue(), output)
        return output
    }

    companion object {
        private val LEGACY = """
import pathlib, struct, sys
charset, file, mode = sys.argv[1:]
p = pathlib.Path(file)
if mode == 'write':
    values = []
    for byte in range(128, 256):
        try: bytes([byte]).decode(charset, errors='strict')
        except UnicodeDecodeError: continue
        values.append(byte)
    body = b'bytes:' + bytes(values) + b':end'
    p.write_bytes(body)
else:
    data = p.read_bytes()
    at = 12
    while at + 8 <= len(data):
        key = data[at:at+4]
        size = struct.unpack_from('<I', data, at+4)[0]
        if size == 0xffffffff and key == b'data': size = struct.unpack_from('<Q', data, 28)[0]
        if key == b'LIST' and data[at+8:at+12] == b'INFO':
            pos, end = at+12, at+8+size
            while pos+8 <= end:
                count = struct.unpack_from('<I', data, pos+4)[0]
                if data[pos:pos+4] == b'INAM':
                    body = data[pos+8:pos+8+count].split(b'\0', 1)[0]
                    break
                pos += 8 + count + count % 2
            else:
                at += 8 + size + size % 2
                continue
            break
        at += 8 + size + size % 2
    else: raise AssertionError('missing native INAM')
sys.stdout.buffer.write(body.decode(charset, errors='strict').encode('utf8'))
""".trimIndent()
    }
}

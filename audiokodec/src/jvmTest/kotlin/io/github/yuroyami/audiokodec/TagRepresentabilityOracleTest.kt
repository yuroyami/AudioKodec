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

/** Independent public readers check fields that exceed the native carrier's limits. */
class TagRepresentabilityOracleTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "audiokodec-tag-representability").also { it.mkdirs() }

    @Test
    fun mutagenReadsCompleteDefaultBundlesInChunkedFiles() {
        val python = python()
        for (extension in listOf("wav", "rf64", "aiff", "aifc")) {
            val source = independentAudio(extension)
            val original = source.readBytes()
            val audio = NativeTextListFixtures.payload(original)
            val edited = File(directory, "rich.$extension")
            var bytes = AudioKodec.writeTags(original, TagRepresentabilityFixtures.rich)
            repeat(3) {
                edited.writeBytes(bytes)
                run(listOf(python, "-c", RICH_CHUNKS, edited.path, extension))
                assertContentEquals(audio, NativeTextListFixtures.payload(bytes))
                assertContentEquals(decoded(source), decoded(edited))
                bytes = AudioKodec.writeTags(bytes, AudioKodec.readTags(bytes).edit { set(TagField.TITLE, "Edit $it") })
            }
        }
        for (target in listOf(EncodeTarget.wav(), EncodeTarget.aiff(), EncodeTarget.aiffFloat())) {
            val file = File(directory, "fresh-${target.codec}." + if (target.container == AudioContainer.WAV) "wav" else "aiff")
            file.writeBytes(AudioKodec.encode(NativeTextListFixtures.samples, NativeTextListFixtures.format, target,
                TagRepresentabilityFixtures.rich))
            run(listOf(python, "-c", RICH_CHUNKS, file.path, file.extension))
        }
    }

    @Test
    fun nativeWebsiteAliasesAndCanonicalOutputAgreeWithMutagen() {
        val python = python()
        val urls = listOf("https://example.org/first", "https://example.org/second")
        for (kind in listOf("flac", "opus", "ape")) {
            val source = independentAudio(if (kind == "ape") "mp3" else kind)
            for (alias in if (kind == "ape") listOf("WEBSITE", "URL", "Weblink") else listOf("WEBSITE", "URL")) {
                val file = File(directory, "website-$alias.${if (kind == "ape") "mp3" else kind}").also { it.writeBytes(source.readBytes()) }
                run(listOf(python, "-c", WEBSITE, file.path, kind, "write", alias))
                assertEquals(urls, AudioKodec.readTags(file.readBytes()).all(TagField.URL))
                val options = if (kind == "ape") TagWriteOptions(formats = setOf(TagFormat.APEV2)) else TagWriteOptions.DEFAULT
                repeat(3) {
                    val before = file.readBytes()
                    file.writeBytes(AudioKodec.writeTags(before, AudioKodec.readTags(before), options))
                    run(listOf(python, "-c", WEBSITE, file.path, kind, "check", alias))
                    assertEquals(urls, AudioKodec.readTags(file.readBytes()).all(TagField.URL))
                    if (kind == "flac") assertContentEquals(TagStressFixtures.flacAudio(before), TagStressFixtures.flacAudio(file.readBytes()))
                    assertContentEquals(decoded(source), decoded(file))
                    if (kind == "ape") run(listOf(python, "-c", MPEG_AUDIO, source.path, file.path))
                }
            }
        }
    }

    @Test
    fun standaloneTotalsAndNumberListsAgreeWithIndependentId3AndApeReaders() {
        val python = python()
        val source = independentAudio("mp3")
        for ((kind, format) in listOf("id3" to TagFormat.ID3V2_3, "id3" to TagFormat.ID3V2_4, "ape" to TagFormat.APEV2)) {
            for (withNumbers in listOf(false, true)) {
                if (withNumbers && format == TagFormat.ID3V2_3) continue
                val fields = buildMap {
                    put(TagField.TRACK_TOTAL, if (withNumbers) listOf("12", "13") else listOf("12"))
                    put(TagField.DISC_TOTAL, if (withNumbers) listOf("2", "3") else listOf("2"))
                    if (withNumbers) {
                        put(TagField.TRACK_NUMBER, listOf("3", "4"))
                        put(TagField.DISC_NUMBER, listOf("1", "2"))
                    }
                }
                val options = TagWriteOptions(formats = setOf(format), padding = 0)
                for (fromMutagen in listOf(false, true)) {
                    val file = File(directory, "totals-$format-$withNumbers-$fromMutagen.mp3").also { it.writeBytes(source.readBytes()) }
                    val mode = if (withNumbers) "multiple" else "standalone"
                    if (fromMutagen) run(listOf(python, "-c", TOTALS, file.path, kind, "write", mode,
                        if (format == TagFormat.ID3V2_3) "3" else "4"))
                    else file.writeBytes(AudioKodec.writeTags(file.readBytes(), TagBundle(fields), options))
                    repeat(3) {
                        val read = AudioKodec.readTags(file.readBytes())
                        for ((field, values) in fields) assertEquals(values, read.all(field))
                        file.writeBytes(AudioKodec.writeTags(file.readBytes(), read, options))
                        run(listOf(python, "-c", TOTALS, file.path, kind, "check", mode, "4"))
                        assertContentEquals(decoded(source), decoded(file))
                        run(listOf(python, "-c", MPEG_AUDIO, source.path, file.path))
                    }
                }
            }
        }
    }

    private fun independentAudio(extension: String): File {
        val file = File(directory, "source.$extension")
        val codec = when (extension) {
            "wav", "rf64" -> "pcm_s16le"
            "aiff" -> "pcm_s16be"
            "aifc" -> "pcm_f32be"
            "opus" -> "libopus"
            "mp3" -> "libmp3lame"
            else -> "flac"
        }
        val format = when (extension) { "rf64" -> "wav"; "aifc" -> "aiff"; else -> extension }
        val extra = if (extension == "rf64") listOf("-rf64", "always") else emptyList()
        run(listOf(tool("ffmpeg"), "-v", "error", "-y", "-f", "lavfi", "-i",
            "sine=frequency=400:sample_rate=48000:duration=0.1", "-c:a", codec, "-f", format) + extra + file.path)
        return file
    }

    private fun decoded(file: File): ByteArray {
        val output = File(directory, "decoded-${file.name}.pcm")
        // Include MP3 priming and padding so metadata-dependent automatic
        // trimming in ffmpeg cannot change the samples being compared.
        val flags = if (file.extension == "mp3") listOf("-flags2", "+skip_manual") else emptyList()
        run(listOf(tool("ffmpeg"), "-v", "error", "-y") + flags +
            listOf("-i", file.path, "-map", "0:a:0", "-f", "f32le", output.path))
        return output.readBytes()
    }

    private fun python(): String {
        val configured = System.getenv("AUDIOKODEC_MUTAGEN_PYTHON")
        val python = configured ?: tool("python3")
        val check = ProcessBuilder(python, "-c", "import mutagen.id3, mutagen.apev2, mutagen.flac, mutagen.oggopus")
            .redirectErrorStream(true).start()
        assertTrue(check.waitFor(30, TimeUnit.SECONDS))
        val output = check.inputStream.bufferedReader().readText()
        if (configured != null) assertEquals(0, check.exitValue(), output)
        else assumeTrue("Mutagen is required for tag representability: $output", check.exitValue() == 0)
        return python
    }

    private fun tool(name: String): String {
        val file = System.getenv("PATH")?.split(File.pathSeparator)?.map { File(it, name) }?.firstOrNull { it.canExecute() }
        assumeTrue("$name is required for tag representability", file != null)
        return file!!.path
    }

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "${command.first()} did not finish")
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.exitValue(), output)
        return output
    }

    companion object {
        private val RICH_CHUNKS = """
import sys, io, struct
from mutagen.id3 import ID3
from mutagen.wave import WAVE
from mutagen.aiff import AIFF
path, kind = sys.argv[1:]
if kind == 'rf64':
    data = open(path, 'rb').read(); at = 12; blocks = []
    while at < len(data):
        key = data[at:at+4]; size = struct.unpack_from('<I', data, at+4)[0]
        if size == 0xffffffff:
            assert key == b'data'
            size = struct.unpack_from('<Q', data, 28)[0]
        if key.lower() == b'id3 ': blocks.append(data[at+8:at+8+size])
        at += 8 + size + size % 2
    assert len(blocks) == 1
    tag = ID3(fileobj=io.BytesIO(blocks[0]))
else:
    tag = (WAVE(path) if kind == 'wav' else AIFF(path)).tags
for key, values in [('TPE2', ['Album artist']), ('TRCK', ['3/12']), ('TBPM', ['128'])]:
    assert list(tag[key].text) == values, (key, tag[key])
assert [f.url for f in tag.getall('WOAR')] == ['https://example.org/artist']
assert tag['APIC:'].data == bytes.fromhex('ffd80001')
chapter = tag['CHAP:one']
assert (chapter.start_time, chapter.end_time) == (0, 1000)
assert list(chapter.sub_frames['TIT2'].text) == ['Opening']
assert float(tag['TXXX:replaygain_track_gain'].text[0].split()[0]) == -3.0
assert float(tag['TXXX:replaygain_track_peak'].text[0]) == 0.9
""".trimIndent()
        private val WEBSITE = """
import sys
from mutagen.flac import FLAC
from mutagen.oggopus import OggOpus
from mutagen.apev2 import APEv2
path, kind, mode, alias = sys.argv[1:]
cls = {'flac': FLAC, 'opus': OggOpus, 'ape': APEv2}[kind]
tag = APEv2() if kind == 'ape' and mode == 'write' else cls(path)
values = ['https://example.org/first', 'https://example.org/second']
if mode == 'write':
    tag.clear(); tag[alias] = values
    if kind == 'ape': tag.save(path)
    else: tag.save()
else:
    key = 'Weblink' if kind == 'ape' else 'WEBSITE'
    assert list(tag[key]) == values, (key, tag[key])
    assert len([k for k in tag if k.lower() in ('weblink', 'website', 'url')]) == 1
""".trimIndent()
        private val MPEG_AUDIO = """
import sys, struct
def payload(path):
    data = open(path, 'rb').read()
    if data[:3] == b'ID3':
        size = 0
        for value in data[6:10]:
            assert value < 128
            size = (size << 7) | value
        data = data[10+size:]
    if data[-32:-24] == b'APETAGEX':
        size = struct.unpack_from('<I', data, len(data)-20)[0]
        flags = struct.unpack_from('<I', data, len(data)-12)[0]
        if flags & 0x80000000: size += 32
        data = data[:-size]
    assert data[:2] == b'\xff\xfb'
    return data
assert payload(sys.argv[1]) == payload(sys.argv[2])
""".trimIndent()
        private val TOTALS = """
import sys
from mutagen.id3 import ID3, TXXX, TRCK, TPOS
from mutagen.apev2 import APEv2
path, kind, mode, shape, version = sys.argv[1:]
multiple = shape == 'multiple'
expected = {'TRACKTOTAL': ['12', '13'] if multiple else ['12'], 'DISCTOTAL': ['2', '3'] if multiple else ['2']}
if kind == 'id3':
    tag = ID3() if mode == 'write' else ID3(path)
    if mode == 'write':
        for key, values in expected.items(): tag.add(TXXX(encoding=1, desc=key, text=values))
        if multiple:
            tag.add(TRCK(encoding=3, text=['3', '4'])); tag.add(TPOS(encoding=3, text=['1', '2']))
        tag.save(path, v2_version=int(version))
    else:
        for key, values in expected.items(): assert list(tag['TXXX:' + key].text) == values
        for key, values in [('TRCK', ['3', '4']), ('TPOS', ['1', '2'])]:
            assert [list(f.text) for f in tag.getall(key)] == ([values] if multiple else [])
else:
    tag = APEv2() if mode == 'write' else APEv2(path)
    if mode == 'write':
        for key, values in expected.items(): tag[key] = values
        if multiple: tag['Track'] = ['3', '4']; tag['Disc'] = ['1', '2']
        tag.save(path)
    else:
        for key, values in expected.items(): assert list(tag[key]) == values
        for key, values in [('Track', ['3', '4']), ('Disc', ['1', '2'])]:
            assert (list(tag[key]) if key in tag else []) == (values if multiple else [])
""".trimIndent()
    }
}

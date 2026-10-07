package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.tag.PictureType
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagPicture
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileOutputStream
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Native artwork is checked through the public reference metadata and decoder interfaces. */
class FlacPictureBlockOracleTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "audiokodec-flac-native-picture").also { it.mkdirs() }

    @Test
    fun referencePictureBlocksAndExportsAgreeWithBothSinkModes() {
        val flac = tool("flac")
        val metaflac = tool("metaflac")
        val ffmpeg = tool("ffmpeg")
        val image = File(directory, "cover.png")
        val bitmap = BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB)
        for (y in 0..2) for (x in 0..1) bitmap.setRGB(x, y, if (x == 0) 0xFF2211 else 0x1122FF)
        assertTrue(ImageIO.write(bitmap, "png", image))
        val pictures = listOf(
            TagPicture(PictureType.FRONT_COVER, "image/png", "Cover 日本語", image.readBytes(), 2, 3, 24),
            TagPicture(PictureType.BACK_COVER, "image/png", "Back", image.readBytes(), 2, 3, 24),
        )
        val format = AudioFormat(44_100, 2)
        for (depth in listOf(8, 16, 24)) {
            val samples = TestSignals.quantise(TestSignals.sine(137, 2), depth)
            val reference = File(directory, "reference-$depth.flac")
            reference.writeBytes(AudioKodec.encode(samples, format, EncodeTarget.flac(depth)))
            for (picture in pictures) {
                run(listOf(metaflac, "--no-utf8-convert", "--import-picture-from=" +
                    "${picture.type.ordinal}|${picture.mimeType}|${picture.description}|2x3x24|${image.path}", reference.path))
            }
            val referenceBytes = reference.readBytes()
            val referenceBodies = bodies(referenceBytes)
            val referenceTags = AudioKodec.readTags(referenceBytes)
            assertEquals(2, referenceTags.pictures.size)
            pictures.zip(referenceTags.pictures).forEach { (expected, actual) -> FlacPictureBlockFixtures.assertPicture(expected, actual) }
            for (patching in listOf(true, false)) {
                val output = File(directory, "written-$depth-$patching.flac")
                val tags = TagBundle(mapOf(TagField.TITLE to listOf("Native pictures")), pictures)
                val bytes = if (patching) AudioKodec.encode(samples, format, EncodeTarget.flac(depth), tags) else {
                    val sink = FlacPictureBlockFixtures.ForwardSink()
                    AudioKodec.encode(samples.asPcmSource(format), EncodeTarget.flac(depth), sink, tags)
                    sink.bytes()
                }
                output.writeBytes(bytes)
                assertBodies(referenceBodies, bodies(bytes))
                assertEquals("", run(listOf(metaflac, "--show-tag=METADATA_BLOCK_PICTURE", output.path)))
                assertEquals("TITLE=Native pictures", run(listOf(metaflac, "--show-tag=TITLE", output.path)).trim())
                val listed = run(listOf(metaflac, "--list", "--block-type=PICTURE", output.path))
                val numbers = Regex("(?m)^METADATA block #(\\d+)").findAll(listed).map { it.groupValues[1] }.toList()
                assertEquals(2, numbers.size, listed)
                assertTrue(listed.contains("description: Cover 日本語"), listed)
                for (field in listOf("width: 2", "height: 3", "depth: 24", "colors: 0")) assertTrue(listed.contains(field), listed)
                for ((index, number) in numbers.withIndex()) {
                    val exported = File(directory, "export-$depth-$patching-$index.png")
                    run(listOf(metaflac, "--block-number=$number", "--export-picture-to=${exported.path}", output.path))
                    assertContentEquals(image.readBytes(), exported.readBytes())
                }
                run(listOf(flac, "--silent", "--test", output.path))
                assertContentEquals(pcm(samples), decode(ffmpeg, output))
                TestSignals.assertExact(samples, AudioKodec.decode(bytes).samples)
                if (depth == 16 && patching) {
                    val originalAudio = FlacPictureBlockFixtures.audio(bytes)
                    repeat(3) { edit ->
                        val before = output.readBytes()
                        val edited = AudioKodec.writeTags(before, AudioKodec.readTags(before).edit { set(TagField.TITLE, "Edit $edit") })
                        output.writeBytes(edited)
                        assertBodies(referenceBodies, bodies(edited))
                        assertContentEquals(originalAudio, FlacPictureBlockFixtures.audio(edited))
                        assertContentEquals(pcm(samples), decode(ffmpeg, output))
                        run(listOf(flac, "--silent", "--test", output.path))
                    }
                }
            }
        }
    }

    @Test
    fun aLargeNativePictureStreamsWithoutAFullCopyAndExportsIntact() {
        val metaflac = tool("metaflac")
        val flac = tool("flac")
        // Image bytes are opaque to the audio encoder and metadata exporter.
        val bytes = ByteArray(13 * 1024 * 1024).also { it[0] = -1; it[1] = -40; it[it.lastIndex - 1] = -1; it[it.lastIndex] = -39 }
        val picture = TagPicture(mimeType = "image/jpeg", description = "Large", bytes = bytes)
        val file = File(directory, "large-native.flac")
        val samples = FloatArray(16)
        val format = AudioFormat(8_000, 1)
        val target = EncodeTarget.flac()
        val tags = TagBundle(emptyMap(), listOf(picture))
        repeat(5) { AudioKodec.encode(samples, format, target) }
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue(threads.isThreadAllocatedMemorySupported, "thread allocation accounting is required")
        threads.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val allocated = FileOutputStream(file).use { output ->
            val sink = object : AudioSink {
                override val canPatch = false
                override var position = 0L
                    private set

                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    output.write(bytes, offset, count)
                    position += count
                }

                override fun patchAt(position: Long, bytes: ByteArray, offset: Int, count: Int) =
                    error("a forward-only file sink cannot be patched")

                override fun close() = Unit
            }
            val before = threads.getThreadAllocatedBytes(thread)
            AudioKodec.encode(samples.asPcmSource(format), target, sink, tags)
            threads.getThreadAllocatedBytes(thread) - before
        }
        // An extra image-sized body allocation would exceed this bound.
        assertTrue(allocated in 0 until bytes.size.toLong(), "streaming ${bytes.size} image bytes allocated $allocated bytes")
        println("Streaming ${bytes.size} image bytes allocated $allocated bytes")
        val exported = File(directory, "large-export.jpg")
        run(listOf(metaflac, "--export-picture-to=${exported.path}", file.path))
        assertContentEquals(bytes, exported.readBytes())
        assertEquals("", run(listOf(metaflac, "--show-tag=METADATA_BLOCK_PICTURE", file.path)))
        run(listOf(flac, "--silent", "--test", file.path))
    }

    private fun bodies(bytes: ByteArray): List<ByteArray> = FlacPictureBlockFixtures.blocks(bytes).filter { it.type == 6 }
        .map { bytes.copyOfRange(it.bodyAt, it.bodyAt + it.size) }

    private fun assertBodies(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (before, after) -> assertContentEquals(before, after) }
    }

    private fun pcm(samples: FloatArray): ByteArray = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        .also { buffer -> samples.forEach { buffer.putFloat(it) } }.array()

    private fun decode(ffmpeg: String, file: File): ByteArray {
        val output = File(directory, "${file.name}.pcm")
        run(listOf(ffmpeg, "-v", "error", "-y", "-i", file.path, "-map", "0:a:0", "-f", "f32le", output.path))
        return output.readBytes()
    }

    private fun tool(name: String): String {
        val file = System.getenv("PATH")?.split(File.pathSeparator)?.map { File(it, name) }?.firstOrNull { it.canExecute() }
        assumeTrue("$name is required for native FLAC artwork checks", file != null)
        return file!!.path
    }

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue(finished, "${command.first()} did not finish")
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.exitValue(), output)
        return output
    }
}

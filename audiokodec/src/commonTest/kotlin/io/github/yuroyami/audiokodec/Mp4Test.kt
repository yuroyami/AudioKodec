package io.github.yuroyami.audiokodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks the MP4 reader on every target, against files assembled here box by
 * box rather than written by AudioKodec, so that the reader is not only
 * agreeing with itself.
 *
 * The audio is uncompressed 16-bit QuickTime `sowt`, which decodes to exactly
 * the integers that went in, so the reader's placing of every packet can be
 * checked to the sample: with the `moov` box before the audio and after it,
 * with chunks separated by unrelated bytes, with an edit list, and as a
 * fragmented file. The oracle suite checks real encoders' files against
 * ffmpeg on the JVM.
 */
class Mp4Test {

    @Test
    fun theSamplesComeBackWhereverTheIndexSits() {
        for (channels in intArrayOf(1, 2)) {
            for (moovFirst in listOf(true, false)) {
                for (gap in intArrayOf(0, 13)) {
                    val what = "$channels ch, moov ${if (moovFirst) "first" else "last"}, gap $gap"
                    val file = build(frames = 3_001, channels = channels, chunkFrames = 400, moovFirst = moovFirst, gap = gap)
                    val info = AudioKodec.probe(file)
                    assertEquals(AudioContainer.MP4, info.container, "$what: container")
                    assertEquals(AudioCodec.PCM_INTEGER, info.codec, "$what: codec")
                    assertEquals(RATE, info.format.sampleRate, "$what: rate")
                    assertEquals(channels, info.format.channels, "$what: channels")
                    assertEquals(3_001L, info.durationFrames, "$what: length")
                    assertEquals(16, info.bitDepth, "$what: bit depth")
                    assertSamples(file, channels, 0, 3_001, what)
                }
            }
        }
    }

    @Test
    fun anEditListChoosesWhichPartPlays() {
        // Start 250 frames in and play 2000, stated in the movie's
        // millisecond timescale as 2000 / 8 = 250 ms.
        val file = build(frames = 3_001, channels = 2, chunkFrames = 512, edit = 250L to 250L)
        assertEquals(2_000L, AudioKodec.probe(file).durationFrames, "length after the edit")
        assertSamples(file, 2, 250, 2_000, "edited")
    }

    @Test
    fun anEditListLongerThanTheMediaStopsAtTheMedia() {
        val file = build(frames = 1_000, channels = 1, chunkFrames = 300, edit = 0L to 900L)
        assertEquals(1_000L, AudioKodec.probe(file).durationFrames)
        assertSamples(file, 1, 0, 1_000, "an overlong edit")
    }

    @Test
    fun aFragmentedFileIsReadFromItsFragments() {
        val file = buildFragmented(frames = 2_500, channels = 2, fragmentFrames = 700)
        assertEquals(2_500L, AudioKodec.probe(file).durationFrames, "length")
        assertSamples(file, 2, 0, 2_500, "fragmented")
    }

    @Test
    fun seekingLandsOnTheSampleAsked() {
        val file = build(frames = 3_001, channels = 2, chunkFrames = 400, gap = 5)
        AudioKodec.open(file).use { decoder ->
            val ints = decoder as IntPcmAccess
            for (target in longArrayOf(0, 1, 399, 400, 401, 2_999, 3_000, 3_001)) {
                assertEquals(target, decoder.seek(target), "seek to $target")
                val frame = ints.nextIntFrame()
                if (target == 3_001L) {
                    assertEquals(null, frame, "nothing after the end")
                    continue
                }
                assertNotNull(frame, "a frame after seeking to $target")
                assertEquals(target, frame.positionFrames, "position after seeking to $target")
                for (i in 0 until frame.frameCount) {
                    for (c in 0 until 2) assertEquals(sample(target.toInt() + i, c), frame.samples[i * 2 + c], "frame ${target + i}, channel $c")
                }
            }
        }
    }

    @Test
    fun neroChaptersAreRead() {
        val chapters = listOf(0L to "Opening", 120L to "Middle", 250L to "")
        val file = build(frames = 3_001, channels = 1, chunkFrames = 1_000, chapters = chapters)
        val read = AudioKodec.readTags(file).chapters
        assertEquals(listOf(0L, 120L, 250L), read.map { it.startMillis })
        assertEquals(listOf(120L, 250L, -1L), read.map { it.endMillis })
        assertEquals(listOf("Opening", "Middle", null), read.map { it.title })
    }

    @Test
    fun aCodingWithoutADecoderIsNamedAndRefused() {
        val file = build(frames = 1_024, channels = 2, chunkFrames = 1_024, format = "mp4a")
        val info = AudioKodec.probe(file)
        assertEquals(AudioCodec.AAC, info.codec)
        val refusal = assertFailsWith<UnsupportedFormatException> { AudioKodec.open(file) }
        assertTrue("AAC" in refusal.message.orEmpty(), "the refusal names the coding: ${refusal.message}")
    }

    @Test
    fun aFileWithoutItsIndexSaysSo() {
        val file = box("ftyp", ascii("M4A "), u32(0)) + box("mdat", ByteArray(64))
        assertFailsWith<TruncatedStreamException> { AudioKodec.probe(file) }
    }

    @Test
    fun truncatedAndMutatedFilesFailOnlyWithTheLibrarysOwnExceptions() {
        for (file in listOf(
            build(frames = 600, channels = 2, chunkFrames = 128, moovFirst = true, gap = 3, chapters = listOf(0L to "A", 40L to "B")),
            build(frames = 600, channels = 2, chunkFrames = 128, moovFirst = false, edit = 10L to 30L),
            buildFragmented(frames = 600, channels = 2, fragmentFrames = 200),
        )) {
            for (keep in 0 until file.size step 5) {
                val cut = file.copyOf(keep)
                expectOnlyOwnFailures("truncated to $keep") { AudioKodec.decode(cut) }
                expectOnlyOwnFailures("truncated to $keep, tolerant") { AudioKodec.decode(cut, DecodeOptions(tolerant = true)) }
                expectOnlyOwnFailures("probing $keep") { AudioKodec.probe(cut) }
                expectOnlyOwnFailures("tags of $keep") { AudioKodec.readTags(cut) }
            }
            var state = 17
            repeat(1_500) {
                state = state * 1_103_515_245 + 12_345
                val at = ((state ushr 8) and 0x7FFF_FFFF) % file.size
                state = state * 1_103_515_245 + 12_345
                val mutated = file.copyOf().also { it[at] = (state ushr 16).toByte() }
                expectOnlyOwnFailures("byte $at changed") { AudioKodec.decode(mutated, DecodeOptions(tolerant = true)) }
                expectOnlyOwnFailures("tags with byte $at changed") { AudioKodec.readTags(mutated) }
            }
        }
    }

    // Checking.

    private fun sample(frame: Int, channel: Int): Int = ((frame * 37 + channel * 1_001) % 30_001) - 15_000

    private fun assertSamples(file: ByteArray, channels: Int, firstFrame: Int, frames: Int, what: String) {
        AudioKodec.open(file).use { decoder ->
            val ints = decoder as IntPcmAccess
            var at = 0
            while (true) {
                val frame = ints.nextIntFrame() ?: break
                assertEquals(at.toLong(), frame.positionFrames, "$what: position")
                for (i in 0 until frame.frameCount) {
                    for (c in 0 until channels) {
                        assertEquals(sample(firstFrame + at + i, c), frame.samples[i * channels + c], "$what: frame ${at + i}, channel $c")
                    }
                }
                at += frame.frameCount
            }
            assertEquals(frames, at, "$what: frames decoded")
        }
    }

    private inline fun expectOnlyOwnFailures(what: String, block: () -> Unit) {
        try {
            block()
        } catch (_: AudioKodecException) {
            // Every failure has to be one of the library's own.
        } catch (e: Throwable) {
            throw AssertionError("$what threw ${e::class.simpleName}: ${e.message}", e)
        }
    }

    // Building files.

    private fun pcm(from: Int, frames: Int, channels: Int): ByteArray {
        val out = ByteArray(frames * channels * 2)
        for (i in 0 until frames) {
            for (c in 0 until channels) {
                val value = sample(from + i, c)
                out[(i * channels + c) * 2] = value.toByte()
                out[(i * channels + c) * 2 + 1] = (value shr 8).toByte()
            }
        }
        return out
    }

    /**
     * A QuickTime file of [frames] frames of `sowt` audio in chunks of
     * [chunkFrames], with [gap] unrelated bytes between chunks, and the
     * `stsz` table stating one byte per frame as QuickTime's own writers do.
     */
    private fun build(
        frames: Int,
        channels: Int,
        chunkFrames: Int,
        moovFirst: Boolean = false,
        gap: Int = 0,
        edit: Pair<Long, Long>? = null,
        chapters: List<Pair<Long, String>> = emptyList(),
        format: String = "sowt",
    ): ByteArray {
        val chunkCount = (frames + chunkFrames - 1) / chunkFrames
        val ftyp = box("ftyp", ascii("qt  "), u32(0), ascii("qt  "))
        fun payload(): Pair<ByteArray, IntArray> {
            val out = ArrayList<ByteArray>()
            val starts = IntArray(chunkCount)
            var size = 0
            for (k in 0 until chunkCount) {
                if (gap > 0) {
                    out += ByteArray(gap) { 0x5A }
                    size += gap
                }
                starts[k] = size
                val count = minOf(chunkFrames, frames - k * chunkFrames)
                out += pcm(k * chunkFrames, count, channels)
                size += count * channels * 2
            }
            return out.reduce { a, b -> a + b } to starts
        }
        val (data, starts) = payload()
        fun moov(dataAt: Int): ByteArray {
            val lastFrames = frames - (chunkCount - 1) * chunkFrames
            val stsc = if (lastFrames == chunkFrames || chunkCount == 1) {
                fullBox("stsc", 0, 0, u32(1), u32(1), u32(minOf(chunkFrames, frames)), u32(1))
            } else {
                fullBox("stsc", 0, 0, u32(2), u32(1), u32(chunkFrames), u32(1), u32(chunkCount), u32(lastFrames), u32(1))
            }
            val entry = box(
                format, ByteArray(6), u16(1), u16(0), u16(0), u32(0), u16(channels), u16(16), u16(0), u16(0), u32(RATE shl 16),
            )
            val stbl = box(
                "stbl",
                fullBox("stsd", 0, 0, u32(1), entry),
                fullBox("stts", 0, 0, u32(1), u32(frames), u32(1)),
                stsc,
                fullBox("stsz", 0, 0, u32(1), u32(frames)),
                fullBox("stco", 0, 0, u32(chunkCount), *starts.map { u32(dataAt + it) }.toTypedArray()),
            )
            val mdia = box(
                "mdia",
                fullBox("mdhd", 0, 0, u32(0), u32(0), u32(RATE), u32(frames), u16(0), u16(0)),
                fullBox("hdlr", 0, 0, u32(0), ascii("soun"), ByteArray(12), byteArrayOf(0)),
                box("minf", fullBox("smhd", 0, 0, u32(0)), stbl),
            )
            val edts = edit?.let { (start, millis) -> box("edts", fullBox("elst", 0, 0, u32(1), u32(millis.toInt()), u32(start.toInt()), u32(0x10000))) }
            val trak = box("trak", *listOfNotNull(fullBox("tkhd", 0, 7, u32(0), u32(0), u32(1), u32(0), u32(frames), ByteArray(60)), edts, mdia).toTypedArray())
            val udta = if (chapters.isEmpty()) null else box(
                "udta",
                fullBox(
                    "chpl", 1, 0, u32(0), byteArrayOf(chapters.size.toByte()),
                    *chapters.map { (millis, title) -> u64(millis * 10_000) + byteArrayOf(title.length.toByte()) + ascii(title) }.toTypedArray(),
                ),
            )
            return box("moov", *listOfNotNull(fullBox("mvhd", 0, 0, u32(0), u32(0), u32(1000), u32(0), ByteArray(80)), trak, udta).toTypedArray())
        }
        return if (moovFirst) {
            val size = moov(0).size
            val dataAt = ftyp.size + size + 8
            ftyp + moov(dataAt) + box("mdat", data)
        } else {
            val dataAt = ftyp.size + 8
            ftyp + box("mdat", data) + moov(dataAt)
        }
    }

    /** A fragmented file: an empty `moov` with defaults in `trex`, then one `moof` and `mdat` per fragment. */
    private fun buildFragmented(frames: Int, channels: Int, fragmentFrames: Int): ByteArray {
        val frameBytes = channels * 2
        val entry = box("sowt", ByteArray(6), u16(1), u16(0), u16(0), u32(0), u16(channels), u16(16), u16(0), u16(0), u32(RATE shl 16))
        val stbl = box(
            "stbl",
            fullBox("stsd", 0, 0, u32(1), entry),
            fullBox("stts", 0, 0, u32(0)),
            fullBox("stsc", 0, 0, u32(0)),
            fullBox("stsz", 0, 0, u32(0), u32(0)),
            fullBox("stco", 0, 0, u32(0)),
        )
        val moov = box(
            "moov",
            fullBox("mvhd", 0, 0, u32(0), u32(0), u32(1000), u32(0), ByteArray(80)),
            box(
                "trak",
                fullBox("tkhd", 0, 7, u32(0), u32(0), u32(1), u32(0), u32(0), ByteArray(60)),
                box(
                    "mdia",
                    fullBox("mdhd", 0, 0, u32(0), u32(0), u32(RATE), u32(0), u16(0), u16(0)),
                    fullBox("hdlr", 0, 0, u32(0), ascii("soun"), ByteArray(12), byteArrayOf(0)),
                    box("minf", fullBox("smhd", 0, 0, u32(0)), stbl),
                ),
            ),
            box("mvex", fullBox("trex", 0, 0, u32(1), u32(1), u32(1), u32(frameBytes), u32(0))),
        )
        var out = box("ftyp", ascii("iso6"), u32(0), ascii("iso6")) + moov
        var at = 0
        var sequence = 1
        while (at < frames) {
            val count = minOf(fragmentFrames, frames - at)
            fun moof(dataOffset: Int) = box(
                "moof",
                fullBox("mfhd", 0, 0, u32(sequence)),
                box("traf", fullBox("tfhd", 0, 0x020000, u32(1)), fullBox("trun", 0, 0x1, u32(count), u32(dataOffset))),
            )
            val moofSize = moof(0).size
            out += moof(moofSize + 8) + box("mdat", pcm(at, count, channels))
            at += count
            sequence++
        }
        return out
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        return u32(body.size + 8) + ascii(type) + body
    }

    private fun fullBox(type: String, version: Int, flags: Int, vararg parts: ByteArray): ByteArray =
        box(type, byteArrayOf(version.toByte(), (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte()), *parts)

    private fun ascii(text: String): ByteArray = ByteArray(text.length) { text[it].code.toByte() }
    private fun u16(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())
    private fun u32(value: Int): ByteArray = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
    private fun u64(value: Long): ByteArray = u32((value ushr 32).toInt()) + u32(value.toInt())

    private companion object {
        const val RATE = 8_000
    }
}

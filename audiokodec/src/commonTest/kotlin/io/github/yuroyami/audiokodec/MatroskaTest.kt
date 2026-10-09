package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.tag.Base64
import io.github.yuroyami.audiokodec.tag.TagField
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Checks Matroska reading on every target, with files built here: every
 * lacing scheme, both content encodings, a Cluster of unknown size, tags,
 * chapters, seeking, and damage. The JVM oracle suite checks files ffmpeg
 * writes against ffprobe and against each coding's own container.
 */
class MatroskaTest {

    /** The 16-bit stereo sample pair of frame [k], the same signal throughout. */
    private fun pair(k: Int) = (((k * 37) % 2000) - 1000) to (((k * 53) % 3000) - 1500)

    private fun pcm(from: Int, frames: Int): ByteArray {
        val out = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val (l, r) = pair(from + i)
            out[4 * i] = l.toByte(); out[4 * i + 1] = (l shr 8).toByte()
            out[4 * i + 2] = r.toByte(); out[4 * i + 3] = (r shr 8).toByte()
        }
        return out
    }

    private fun expected(frames: Int) = FloatArray(frames * 2) { i ->
        val (l, r) = pair(i / 2)
        (if (i % 2 == 0) l else r) / 32768f
    }

    @Test
    fun everyLacingSchemeGivesTheSameFrames() {
        // Nine frames of 100 samples, in three Blocks of three frames each.
        val frames = List(9) { pcm(it * 100, 100) }
        for (lacing in listOf(Lacing.NONE, Lacing.XIPH, Lacing.EBML, Lacing.FIXED)) {
            val blocks = if (lacing == Lacing.NONE) frames.map { listOf(it) } else frames.chunked(3)
            val file = matroska(track = pcmTrack(), blocks = blocks, lacing = lacing)
            val decoded = AudioKodec.decode(file)
            assertEquals(AudioContainer.MATROSKA, decoded.info.container, "$lacing")
            assertEquals(AudioCodec.PCM_INTEGER, decoded.info.codec)
            assertEquals(900L, decoded.info.durationFrames)
            assertContentEquals(expected(900), decoded.samples, "$lacing")
        }
    }

    @Test
    fun zlibAndHeaderStrippingAreUndone() {
        val compressed = ZLIB_FRAMES.map { Base64.decodeOrNull(it)!! }
        val zlib = matroska(track = pcmTrack(encoding = encoding(algorithm = 0)), blocks = compressed.map { listOf(it) })
        assertContentEquals(expected(300), AudioKodec.decode(zlib).samples)
        // Header stripping: every frame's first four bytes, here always the same, are stored once.
        val frames = List(3) { pcm(0, 100) }
        val prefix = frames[0].copyOfRange(0, 4)
        val stripped = matroska(
            track = pcmTrack(encoding = encoding(algorithm = 3, settings = prefix)),
            blocks = frames.map { listOf(it.copyOfRange(4, it.size)) },
        )
        val whole = FloatArray(600) { i -> val (l, r) = pair((i / 2) % 100); (if (i % 2 == 0) l else r) / 32768f }
        assertContentEquals(whole, AudioKodec.decode(stripped).samples)
    }

    @Test
    fun aClusterOfUnknownSizeRunsToTheEndOfTheSegment() {
        val frames = List(4) { pcm(it * 100, 100) }
        val file = matroska(track = pcmTrack(), blocks = frames.map { listOf(it) }, unknownSizes = true)
        assertContentEquals(expected(400), AudioKodec.decode(file).samples)
    }

    @Test
    fun clustersOfUnknownSizeEachEndAtTheNext() {
        // As a browser's MediaRecorder writes WebM: every Cluster of unknown size, and an element after the last.
        val frames = List(6) { pcm(it * 100, 100) }
        val file = matroska(track = pcmTrack(), blocks = frames.map { listOf(it) }, unknownSizes = true, clusters = 3, trailer = element(CUES))
        assertContentEquals(expected(600), AudioKodec.decode(file).samples)
        assertEquals(600L, AudioKodec.probe(file).durationFrames)
    }

    @Test
    fun onlyAClusterMayHaveAnUnknownSizeBelowTheSegment() {
        val file = matroska(track = pcmTrack(), blocks = listOf(listOf(pcm(0, 100))), trailer = unknownElement(TAGS, element(TAG)))
        assertFailsWith<CorruptStreamException> { AudioKodec.decode(file) }
    }

    @Test
    fun aSeekLandsOnExactlyTheContinuousDecode() {
        val file = matroska(track = pcmTrack(), blocks = List(9) { listOf(pcm(it * 100, 100)) })
        val whole = AudioKodec.decode(file).samples
        AudioKodec.open(file).use { decoder ->
            for (target in listOf(450L, 0L, 899L, 100L, 333L)) {
                assertEquals(target, decoder.seek(target))
                val frame = decoder.nextFrame()!!
                for (i in 0 until frame.frameCount * 2) assertEquals(whole[target.toInt() * 2 + i], frame.samples[i], "seek to $target")
            }
        }
    }

    @Test
    fun tagsAndChaptersAreRead() {
        val file = matroska(
            track = pcmTrack(),
            blocks = List(3) { listOf(pcm(it * 100, 100)) },
            title = "Segment Title",
            tags = listOf("ARTIST" to "An Artist", "PART_NUMBER" to "7", "DATE_RELEASED" to "1999"),
            chapters = listOf(Triple(0L, 1_000_000_000L, "One"), Triple(1_000_000_000L, 6_250_000_000L, "Two")),
        )
        val tags = AudioKodec.readTags(file)
        assertEquals("Segment Title", tags[TagField.TITLE])
        assertEquals("An Artist", tags[TagField.ARTIST])
        assertEquals("7", tags[TagField.TRACK_NUMBER])
        assertEquals("1999", tags[TagField.DATE])
        assertEquals(listOf("One", "Two"), tags.chapters.map { it.title })
        assertEquals(listOf(0L, 1_000L), tags.chapters.map { it.startMillis })
        assertEquals(listOf(1_000L, 6_250L), tags.chapters.map { it.endMillis })
        val sink = ByteArrayAudioSink()
        assertFailsWith<UnsupportedFeatureException> { AudioKodec.writeTags(file.asAudioSource(), tags.edit { set(TagField.TITLE, "New") }, sink) }
        assertEquals(0L, sink.position, "a refused rewrite writes nothing")
    }

    @Test
    fun damagedAndUnsupportedFilesAreRefused() {
        val good = matroska(track = pcmTrack(), blocks = List(3) { listOf(pcm(it * 100, 100)) })
        // Cut inside the last frame.
        assertFailsWith<AudioKodecException> { AudioKodec.decode(good.copyOf(good.size - 10)) }
        // A codec AudioKodec does not have.
        val other = matroska(track = pcmTrack(codecId = "A_REAL/COOK"), blocks = List(3) { listOf(pcm(it * 100, 100)) })
        assertEquals(AudioCodec.UNKNOWN, AudioKodec.probe(other).codec)
        assertFailsWith<UnsupportedFormatException> { AudioKodec.decode(other) }
        // An encrypted track.
        val encrypted = matroska(track = pcmTrack(encoding = element(CONTENT_ENCODING, element(CONTENT_ENCODING_TYPE, byteArrayOf(1)))), blocks = listOf(listOf(pcm(0, 100))))
        assertFailsWith<UnsupportedFeatureException> { AudioKodec.probe(encrypted) }
    }

    @Test
    fun ac3SyncframesDecodeAsTheyDoFromARawStream() {
        val fixture = Ac3Fixtures.SURROUND
        val track = element(
            TRACK_ENTRY,
            uint(TRACK_NUMBER, 1), uint(TRACK_UID, 77), uint(TRACK_TYPE, 2), text(CODEC_ID, "A_AC3"),
            element(AUDIO, float(SAMPLING_FREQUENCY, 48_000.0), uint(CHANNELS, 6)),
        )
        val file = matroska(track = track, blocks = fixture.frames().map { listOf(it) })
        val info = AudioKodec.probe(file)
        assertEquals(AudioContainer.MATROSKA, info.container)
        assertEquals(AudioCodec.AC3, info.codec)
        assertEquals(6, info.format.channels)
        assertEquals(4608L, info.durationFrames)
        assertEquals(AudioKodec.decode(fixture.bytes).samples.toList(), AudioKodec.decode(file).samples.toList())
        AudioKodec.open(file).use { decoder ->
            assertEquals(2000L, decoder.seek(2000))
            val whole = AudioKodec.decode(fixture.bytes).samples
            val frame = decoder.nextFrame()!!
            for (i in 0 until 300 * 6) assertEquals(whole[2000 * 6 + i], frame.samples[i], "seek to 2000, sample $i")
        }
    }

    internal fun hostileFixture(): ByteArray = matroska(track = pcmTrack(), blocks = List(2) { listOf(pcm(it * 8, 8)) }, lacing = Lacing.NONE)

    // Building files.

    private enum class Lacing(val bits: Int) { NONE(0), XIPH(1), FIXED(2), EBML(3) }

    private fun id(value: Long): ByteArray {
        var length = 1
        while (length < 4 && value ushr (8 * length) != 0L) length++
        return ByteArray(length) { (value ushr (8 * (length - 1 - it))).toByte() }
    }

    private fun size(value: Long): ByteArray = ByteArray(8) { if (it == 0) 0x01 else (value ushr (8 * (7 - it))).toByte() }

    private fun element(id: Long, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { a, b -> a + b }
        return id(id) + size(body.size.toLong()) + body
    }

    private fun unknownElement(id: Long, vararg children: ByteArray): ByteArray =
        id(id) + byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1) + children.fold(ByteArray(0)) { a, b -> a + b }

    private fun uint(id: Long, value: Long): ByteArray = element(id, ByteArray(8) { (value ushr (8 * (7 - it))).toByte() })

    private fun text(id: Long, value: String): ByteArray = element(id, value.encodeToByteArray())

    private fun float(id: Long, value: Double): ByteArray = element(id, ByteArray(8) { (value.toRawBits() ushr (8 * (7 - it))).toByte() })

    private fun encoding(algorithm: Long, settings: ByteArray? = null): ByteArray = element(
        CONTENT_ENCODING,
        element(CONTENT_COMPRESSION, uint(CONTENT_COMP_ALGO, algorithm), settings?.let { element(CONTENT_COMP_SETTINGS, it) } ?: ByteArray(0)),
    )

    private fun pcmTrack(codecId: String = "A_PCM/INT/LIT", encoding: ByteArray? = null): ByteArray = element(
        TRACK_ENTRY,
        uint(TRACK_NUMBER, 1), uint(TRACK_UID, 77), uint(TRACK_TYPE, 2), text(CODEC_ID, codecId),
        element(AUDIO, float(SAMPLING_FREQUENCY, 48_000.0), uint(CHANNELS, 2), uint(BIT_DEPTH, 16)),
        encoding?.let { element(CONTENT_ENCODINGS, it) } ?: ByteArray(0),
    )

    private fun block(frames: List<ByteArray>, lacing: Lacing, time: Int): ByteArray {
        val head = byteArrayOf(0x81.toByte(), (time shr 8).toByte(), time.toByte())
        if (frames.size == 1 && lacing == Lacing.NONE) return element(SIMPLE_BLOCK, head + byteArrayOf(0x80.toByte()) + frames[0])
        val flags = (0x80 or (lacing.bits shl 1)).toByte()
        var sizes = ByteArray(0)
        when (lacing) {
            Lacing.XIPH -> for (f in frames.dropLast(1)) {
                var n = f.size
                while (n >= 255) { sizes += byteArrayOf(-1); n -= 255 }
                sizes += byteArrayOf(n.toByte())
            }
            Lacing.EBML -> {
                // The first size as a two-byte VINT, then each difference as a signed two-byte VINT.
                sizes += byteArrayOf((0x40 or (frames[0].size shr 8)).toByte(), frames[0].size.toByte())
                for (i in 1 until frames.size - 1) {
                    val raw = frames[i].size - frames[i - 1].size + 8191
                    sizes += byteArrayOf((0x40 or (raw shr 8)).toByte(), raw.toByte())
                }
            }
            else -> Unit
        }
        return element(SIMPLE_BLOCK, head + byteArrayOf(flags, (frames.size - 1).toByte()) + sizes + frames.fold(ByteArray(0)) { a, b -> a + b })
    }

    private fun matroska(
        track: ByteArray,
        blocks: List<List<ByteArray>>,
        lacing: Lacing = Lacing.NONE,
        unknownSizes: Boolean = false,
        clusters: Int = 1,
        trailer: ByteArray = ByteArray(0),
        title: String? = null,
        tags: List<Pair<String, String>> = emptyList(),
        chapters: List<Triple<Long, Long, String>> = emptyList(),
    ): ByteArray {
        val header = element(EBML, text(DOC_TYPE, "matroska"))
        val info = element(INFO, uint(TIMESTAMP_SCALE, 1_000_000), title?.let { text(TITLE, it) } ?: ByteArray(0))
        val tagBlock = if (tags.isEmpty()) ByteArray(0) else element(
            TAGS,
            element(TAG, element(TARGETS), *tags.map { (k, v) -> element(SIMPLE_TAG, text(TAG_NAME, k), text(TAG_STRING, v)) }.toTypedArray()),
        )
        val chapterBlock = if (chapters.isEmpty()) ByteArray(0) else element(
            CHAPTERS,
            element(
                EDITION_ENTRY,
                *chapters.mapIndexed { i, (start, end, name) ->
                    element(
                        CHAPTER_ATOM, uint(CHAPTER_UID, i + 1L), uint(CHAPTER_TIME_START, start), uint(CHAPTER_TIME_END, end),
                        element(CHAPTER_DISPLAY, text(CHAP_STRING, name)),
                    )
                }.toTypedArray(),
            ),
        )
        var time = 0
        val perCluster = (blocks.size + clusters - 1) / clusters
        val clusterElements = blocks.chunked(maxOf(1, perCluster)).map { chunk ->
            val clusterBody = ArrayList<ByteArray>()
            clusterBody.add(uint(TIMESTAMP, time.toLong()))
            var relative = 0
            for (frames in chunk) {
                clusterBody.add(block(frames, lacing, relative))
                relative += frames.size * 2
            }
            time += relative
            if (unknownSizes) unknownElement(CLUSTER, *clusterBody.toTypedArray()) else element(CLUSTER, *clusterBody.toTypedArray())
        }
        val children = arrayOf(info, element(TRACKS, track), tagBlock, chapterBlock, *clusterElements.toTypedArray(), trailer)
        val segment = if (unknownSizes) unknownElement(SEGMENT, *children) else element(SEGMENT, *children)
        return header + segment
    }

    private companion object {
        const val EBML = 0x1A45DFA3L
        const val DOC_TYPE = 0x4282L
        const val SEGMENT = 0x18538067L
        const val INFO = 0x1549A966L
        const val TIMESTAMP_SCALE = 0x2AD7B1L
        const val TITLE = 0x7BA9L
        const val TRACKS = 0x1654AE6BL
        const val TRACK_ENTRY = 0xAEL
        const val TRACK_NUMBER = 0xD7L
        const val TRACK_UID = 0x73C5L
        const val TRACK_TYPE = 0x83L
        const val CODEC_ID = 0x86L
        const val AUDIO = 0xE1L
        const val SAMPLING_FREQUENCY = 0xB5L
        const val CHANNELS = 0x9FL
        const val BIT_DEPTH = 0x6264L
        const val CONTENT_ENCODINGS = 0x6D80L
        const val CONTENT_ENCODING = 0x6240L
        const val CONTENT_ENCODING_TYPE = 0x5033L
        const val CONTENT_COMPRESSION = 0x5034L
        const val CONTENT_COMP_ALGO = 0x4254L
        const val CONTENT_COMP_SETTINGS = 0x4255L
        const val CLUSTER = 0x1F43B675L
        const val TIMESTAMP = 0xE7L
        const val SIMPLE_BLOCK = 0xA3L
        const val TAGS = 0x1254C367L
        const val CUES = 0x1C53BB6BL
        const val TAG = 0x7373L
        const val TARGETS = 0x63C0L
        const val SIMPLE_TAG = 0x67C8L
        const val TAG_NAME = 0x45A3L
        const val TAG_STRING = 0x4487L
        const val CHAPTERS = 0x1043A770L
        const val EDITION_ENTRY = 0x45B9L
        const val CHAPTER_ATOM = 0xB6L
        const val CHAPTER_UID = 0x73C4L
        const val CHAPTER_TIME_START = 0x91L
        const val CHAPTER_TIME_END = 0x92L
        const val CHAPTER_DISPLAY = 0x80L
        const val CHAP_STRING = 0x85L

        /** Three frames of the test signal, each zlib-compressed by Python's zlib at level 9. */
        val ZLIB_FRAMES = listOf(
            "eNoFwTtIkFEUAOBzzv1/iKKWogw1KZREMGgxSjTNsCwlEpeEIBRFURRF0SWpodQke5JFDUkuilIoSmEIUdBSlJGmSfYQMmoJDMt7z6PvS+BUn8OnfAX3+k5+7of5n5/mzLDKFSFR+kKevApVgnxJsvih1PF7uc9rMsM7dL0U6EGp0Ra5rIMyqovyQTcr61HdaWe10Ea1zpb1qiXZuJ20j3bRzCYtFX5bEaRBA5TDDbgCj+EFfII1INyDu7ESi/E2NuFrvIVEk7iPvmA9xdRPGTRLJ2iDa6E8d4da3RQNuSX67Na5LVGmK4pKXUfU5saie+5H9Mwlx99daZzNnfEZfhpf4P1+iBv8G37g//Cc3y4bQ64cCpXSFrpkOIzI1/BOtvJfOc5Jeo7zdZyr9Sf3aIo80jKZ0W7xOiUptiKHLV1r7bT22nUds5c6Z6xie20XVNsRuGv18NauQQwTcAAWoBEAByAN5+EYbsJGLMCb2I5PcAQX8Rs62kbpVEwldJ6aaYL66Bf9BxeE0nY=",
            "eNoFwUtIUEEUANA7c+eJ4cooDMMwBUGMgkjEyA+GPwKTIAoSy0wsNRDFlRKIoiRKUSgikbQps8i0IAtDCoJIDEUxBEOKgjZGWWrO3E/nTNlk/GJPYwz2YAZOYzn+xRZMd0NY6abxtvuG7102saukw1EH1UYjdCeapfloncp8Anf4Yzzpq3jNd3FKeMRnwhz3hg1+ExJlM+RJBl2SC3Rd+umJfKAFUfonRzhJr3CB3uVaXeBejZVxzZElbZKgDyQZVqQQ4rUOivQGtOpzeKrL8F0FEiHVnIQS0wlXzUu4ZX7CC5NqVsxZY2yfSbNvzQm7ZRrtAdtvq+wrO2BX7Yx1CJiOmViGddiMwziIi/gad7ivmOuyqNlV0Ihrp8/uPu2MZqg4+kVt0W4ej47yQX+eq30nD/qHPOs/sg1/OCvskYaQI/fCRVkK3RJHjyWf5qWFNmWU9uoq5esurtFS7tFrPKbPeFF/8LYmyT44JcehWy7DlPTBb5mANP0E55Tgpu4377TIeK03h+A/mmbURg==",
            "eNoFwVtoCFAYAOD/cg4pJbkMyS1a5Mmapq1pWcmaJ9FyGYvccltKPGhMtCyytlaK1DJkTQpDEnnA3C2iLVo8ySJra3XOf/F9F3AH3MOL0I/vwJFxIZXgGtqPB6gDW+kLPqCJ9I0qiPgIFXIXVfMg1fM0bucqfsQNPMh3uVh+8UaZExpkXbgqTaFXHoc/MhymaGEs0c1xi7bERn0er+uq9EaPpn/anabbj1RqBXmbVefTdjLftJ783n7nEZsnM329lPtZ2e5PpMlHpNsXa5/X6pi36mx4qRWguhOWWTPssttwyT7DR0swzudiqVfiId+DnX4e+/0OToKvWAmKx2AB3YLV9BP20QxsobXYQ404QPcReAgX8Xyq4g10kJupjZ/SQx6l77yEi2Qr10gbH5de7hDjF1IUhmR3mKyXw3LtC5t0fDyhZbFT6+MrvRb/6kCcajVphZ1LtfYsnbKxdMOW5rdWl4etPRf461zmIHVeLGd8r3T5Ffngn2TUJ+gsKNeVcFj/A+Cnx0E=",
        )
    }
}

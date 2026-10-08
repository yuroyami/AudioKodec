package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.mp4.Mp4Reader
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Checks how MP4 files are read against ffmpeg's demuxer, on files ffmpeg's
 * muxer wrote.
 *
 * The demuxer is checked packet by packet: every packet of the audio track
 * has to be found at the offset and with the size `ffprobe` lists, in files
 * with the `moov` box before the audio and after it, in fragmented files, and
 * in 3GP. The length reported has to be the one `ffprobe` reports after it
 * applies the edit list, and the chapters the ones it lists. Where AudioKodec
 * decodes the coding, the samples are compared too: exactly for uncompressed
 * audio and FLAC, and closely for Opus, whose libopus inside ffmpeg is
 * another version built differently.
 *
 * The tests skip themselves when ffmpeg is missing.
 */
class Mp4OracleTest {

    private val ffmpeg: String? by lazy { findOnPath("ffmpeg") }
    private val ffprobe: String? by lazy { findOnPath("ffprobe") }

    @Test
    fun unbrandedQuickTimePcmMatchesFfprobeAndFfmpeg() {
        val ffmpeg = ffmpeg ?: return org.junit.Assume.assumeTrue("ffmpeg is required", false)
        val ffprobe = ffprobe ?: return org.junit.Assume.assumeTrue("ffprobe is required", false)
        for (fastStart in listOf(false, true)) {
            val options = listOf("-c:a", "pcm_s16le") + if (fastStart) listOf("-movflags", "+faststart") else emptyList()
            val file = encode(ffmpeg, "unbranded_$fastStart.mov", 2, options, seconds = 0.5)
            val bytes = file.readBytes()
            assertEquals("ftyp", bytes.decodeToString(4, 8))
            "free".encodeToByteArray().copyInto(bytes, 4)
            file.writeBytes(bytes)
            val stream = streamOf(ffprobe, file)
            assertEquals(AudioContainer.MP4, AudioKodec.sniff(bytes))
            assertEquals(stream.getValue("duration_ts").toLong(), AudioKodec.probe(bytes).durationFrames)
            val decoded = AudioKodec.decode(bytes)
            val reference = temp("unbranded_$fastStart.f32")
            run(listOf(ffmpeg, "-y", "-v", "error", "-i", file.path, "-f", "f32le", reference.path))
            val samples = ByteBuffer.wrap(reference.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            assertEquals(decoded.samples.size, samples.remaining())
            for (i in decoded.samples.indices) assertEquals(samples[i], decoded.samples[i], "sample $i")
        }
    }

    private class Case(val name: String, val codec: AudioCodec, val options: List<String>, val channels: Int = 2)

    private val cases = listOf(
        Case("aac_moov_last.m4a", AudioCodec.AAC, listOf("-c:a", "aac", "-b:a", "128k")),
        Case("aac_moov_first.m4a", AudioCodec.AAC, listOf("-c:a", "aac", "-movflags", "+faststart")),
        Case("aac_fragmented.mp4", AudioCodec.AAC, listOf("-c:a", "aac", "-movflags", "frag_keyframe+empty_moov", "-frag_duration", "400000")),
        Case("aac_fragmented_with_video.mp4", AudioCodec.AAC, listOf("-f", "lavfi", "-i", "color=c=blue:s=32x32:r=10:d=3", "-map", "1:v", "-map", "0:a", "-c:v", "mpeg4", "-c:a", "aac", "-movflags", "frag_keyframe+empty_moov+omit_tfhd_offset", "-frag_duration", "400000")),
        Case("aac_51.m4a", AudioCodec.AAC, listOf("-c:a", "aac", "-b:a", "256k"), channels = 6),
        Case("aac.3gp", AudioCodec.AAC, listOf("-c:a", "aac", "-ar", "22050", "-f", "3gp")),
        Case("alac.m4a", AudioCodec.ALAC, listOf("-c:a", "alac")),
        Case("alac_fragmented.m4a", AudioCodec.ALAC, listOf("-c:a", "alac", "-movflags", "frag_keyframe+empty_moov", "-frag_duration", "400000")),
        Case("opus.mp4", AudioCodec.OPUS, listOf("-c:a", "libopus", "-b:a", "96k")),
        Case("opus_fragmented.mp4", AudioCodec.OPUS, listOf("-c:a", "libopus", "-movflags", "frag_keyframe+empty_moov", "-frag_duration", "400000")),
        Case("flac.mp4", AudioCodec.FLAC, listOf("-c:a", "flac")),
        Case("flac_fragmented.mp4", AudioCodec.FLAC, listOf("-c:a", "flac", "-movflags", "frag_keyframe+empty_moov", "-frag_duration", "400000")),
    )

    @Test
    fun fiveSecondPcmMovStreamsUnderOneMibAndMatchesFfmpeg() {
        val ffmpeg = ffmpeg ?: return skip()
        val ffprobe = ffprobe ?: return skip()
        val file = encode(ffmpeg, "pcm_small_index.mov", 1, listOf("-ar", "48000", "-c:a", "pcm_s16le"), seconds = 5.0)
        val bytes = file.readBytes()
        val options = DecodeOptions(maxAllocationBytes = 1L shl 20)
        val parsed = Mp4Reader.parse(SourceReader(bytes.asAudioSource(), options), options)
        assertEquals(8, parsed.audio.sizes.size)
        assertTrue(parsed.audio.sizes.all { it in 1..65_536 })
        assertEquals(streamOf(ffprobe, file).getValue("duration_ts").toLong(), AudioKodec.probe(bytes, options).durationFrames)
        val ours = AudioKodec.decode(bytes, options)
        val reference = temp("pcm_small_index.f32")
        run(listOf(ffmpeg, "-y", "-v", "error", "-i", file.path, "-f", "f32le", reference.path))
        val theirs = ByteBuffer.wrap(reference.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        assertEquals(240_000, ours.frames)
        assertEquals(ours.frames, theirs.remaining())
        for (i in 0 until ours.frames) assertEquals(theirs[i], ours.samples[i], "PCM sample $i")
    }

    @Test
    fun everyPacketIsWhereFfprobeFindsIt() {
        val ffmpeg = ffmpeg ?: return skip()
        val ffprobe = ffprobe ?: return skip()
        for (case in cases) {
            val file = encode(ffmpeg, case.name, case.channels, case.options)
            val bytes = file.readBytes()
            val parsed = Mp4Reader.parse(SourceReader(bytes.asAudioSource(), DecodeOptions.DEFAULT), DecodeOptions.DEFAULT)
            val packets = runCapturing(
                listOf(ffprobe, "-v", "error", "-select_streams", "a:0", "-show_packets", "-show_entries", "packet=pos,size", "-of", "csv=p=0", file.path),
            ).lines().filter { it.isNotBlank() }.map { line -> line.split(',').let { it[1].toLong() to it[0].toInt() } }
            assertEquals(packets.size, parsed.audio.offsets.size, "${case.name}: packets")
            for ((i, packet) in packets.withIndex()) {
                assertEquals(packet.first, parsed.audio.offsets[i], "${case.name}: offset of packet $i")
                assertEquals(packet.second, parsed.audio.sizes[i], "${case.name}: size of packet $i")
            }

            val stream = streamOf(ffprobe, file)
            val info = AudioKodec.probe(bytes)
            assertEquals(case.codec, info.codec, "${case.name}: codec")
            assertEquals(stream.getValue("sample_rate").toInt(), info.format.sampleRate, "${case.name}: sample rate")
            assertEquals(stream.getValue("channels").toInt(), info.format.channels, "${case.name}: channels")
            // ffprobe reports the length after the edit list. A fragmented
            // Opus file has none, and its pre-skip still precedes the audio,
            // which AudioKodec leaves out and ffprobe counts.
            val preSkip = if (case.codec == AudioCodec.OPUS && "frag" in case.name) 312 else 0
            assertEquals(stream.getValue("duration_ts").toLong() - preSkip, info.durationFrames, "${case.name}: length")
        }
    }

    @Test
    fun decodableCodingsDecodeAsFfmpegDecodesThem() {
        val ffmpeg = ffmpeg ?: return skip()
        val decodable = listOf(
            Case("pcm_s16le.mov", AudioCodec.PCM_INTEGER, listOf("-c:a", "pcm_s16le")),
            Case("pcm_s16be.mov", AudioCodec.PCM_INTEGER, listOf("-c:a", "pcm_s16be")),
            Case("pcm_s24be.mov", AudioCodec.PCM_INTEGER, listOf("-c:a", "pcm_s24be")),
            Case("pcm_s24le.mov", AudioCodec.PCM_INTEGER, listOf("-c:a", "pcm_s24le")),
            Case("pcm_s32be.mov", AudioCodec.PCM_INTEGER, listOf("-c:a", "pcm_s32be")),
            Case("pcm_f32le.mov", AudioCodec.PCM_FLOAT, listOf("-c:a", "pcm_f32le")),
            Case("pcm_f64be.mov", AudioCodec.PCM_FLOAT, listOf("-c:a", "pcm_f64be")),
            Case("pcm_u8.mov", AudioCodec.PCM_INTEGER, listOf("-c:a", "pcm_u8")),
            Case("pcm_mulaw.mov", AudioCodec.MU_LAW, listOf("-c:a", "pcm_mulaw")),
            Case("pcm_alaw.mov", AudioCodec.A_LAW, listOf("-c:a", "pcm_alaw")),
            Case("pcm_51.mov", AudioCodec.PCM_INTEGER, listOf("-c:a", "pcm_s16le"), channels = 6),
            Case("flac.mp4", AudioCodec.FLAC, listOf("-c:a", "flac")),
            Case("flac_fragmented.mp4", AudioCodec.FLAC, listOf("-c:a", "flac", "-movflags", "frag_keyframe+empty_moov", "-frag_duration", "400000")),
            Case("flac_24.mp4", AudioCodec.FLAC, listOf("-c:a", "flac", "-sample_fmt", "s32", "-bits_per_raw_sample", "24", "-strict", "experimental")),
            Case("opus.mp4", AudioCodec.OPUS, listOf("-c:a", "libopus", "-b:a", "96k")),
            Case("opus_51.mp4", AudioCodec.OPUS, listOf("-c:a", "libopus", "-b:a", "256k"), channels = 6),
            Case("alac_16.m4a", AudioCodec.ALAC, listOf("-c:a", "alac")),
            Case("alac_24.m4a", AudioCodec.ALAC, listOf("-c:a", "alac", "-sample_fmt", "s32p")),
            Case("alac_mono.m4a", AudioCodec.ALAC, listOf("-c:a", "alac"), channels = 1),
            Case("alac_51.m4a", AudioCodec.ALAC, listOf("-c:a", "alac", "-sample_fmt", "s32p"), channels = 6),
            Case("alac_fragmented.m4a", AudioCodec.ALAC, listOf("-c:a", "alac", "-movflags", "frag_keyframe+empty_moov", "-frag_duration", "400000")),
        )
        for (case in decodable) {
            val file = encode(ffmpeg, case.name, case.channels, case.options)
            val ours = AudioKodec.decode(file.readBytes())
            assertEquals(case.codec, ours.info.codec, "${case.name}: codec")
            if (case.name.endsWith(".mov")) {
                // These cases are fixed-width PCM and G.711 from a real muxer.
                assertFalse(ours.info.isVariableBitrate, "${case.name}: decode bitrate classification")
                assertFalse(AudioKodec.probe(file.readBytes()).isVariableBitrate, "${case.name}: probe bitrate classification")
            }
            val reference = temp(case.name + ".f32")
            run(listOf(ffmpeg, "-y", "-v", "error", "-request_sample_fmt", "flt", "-i", file.path, "-f", "f32le", reference.path))
            val theirs = ByteBuffer.wrap(reference.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val channels = ours.format.channels
            val tolerance = if (case.codec == AudioCodec.OPUS) 1e-5f else 0f
            if (case.codec == AudioCodec.OPUS) {
                // ffmpeg's command line drops the pre-skip but not the
                // padding at the end, so its decode runs longer.
                assertTrue(theirs.remaining() / channels >= ours.frames, "${case.name}: ffmpeg decoded fewer frames")
            } else {
                assertEquals(theirs.remaining() / channels, ours.frames, "${case.name}: frames")
            }
            // ffmpeg names 5.1 in its own order, which for these codings is
            // AudioKodec's order too: front left, front right, centre, low
            // frequency, then the back or side pair.
            var worst = 0f
            for (i in 0 until ours.frames * channels) worst = maxOf(worst, abs(ours.samples[i] - theirs[i]))
            assertTrue(worst <= tolerance, "${case.name}: decoded samples differ from ffmpeg's by up to $worst")
        }
    }

    @Test
    fun anEditListThatStartsPartWayIsFollowed() {
        val ffmpeg = ffmpeg ?: return skip()
        val ffprobe = ffprobe ?: return skip()
        val original = encode(ffmpeg, "flac_for_edit.mp4", 2, listOf("-c:a", "flac")).readBytes()
        val whole = AudioKodec.decode(original)
        // Start a thousand samples in and play two seconds, which is what a
        // file trimmed without re-encoding looks like.
        val edited = original.copyOf()
        val elst = indexOfType(edited, "elst")
        assertTrue(elst > 0, "ffmpeg wrote no edit list to change")
        writeU32(edited, elst + 12, 2_000)
        writeU32(edited, elst + 16, 1_000)
        val file = temp("flac_edited.mp4").also { it.writeBytes(edited) }
        val stream = streamOf(ffprobe, file)
        val decoded = AudioKodec.decode(edited)
        assertEquals(88_200, decoded.frames, "frames after the edit")
        assertEquals(stream.getValue("duration_ts").toInt(), decoded.frames, "the length ffprobe reports for the edit")
        for (i in 0 until decoded.frames * 2) {
            assertEquals(whole.samples[2_000 + i], decoded.samples[i], "sample $i after the edit")
        }
    }

    @Test
    fun openingAnOpusEditHasTheSameRunUpAsSeekingToZero() {
        val ffmpeg = ffmpeg ?: return skip()
        for (channels in listOf(1, 2)) {
            val rate = 48_000
            val input = temp("opus_run_up_$channels.wav")
            val samples = FloatArray(rate * 3 * channels) { i ->
                (0.125 * kotlin.math.sin(2 * Math.PI * (997 + 73 * (i % channels)) * (i / channels) / rate)).toFloat()
            }
            input.writeBytes(AudioKodec.encode(samples, AudioFormat(rate, channels), EncodeTarget.wav(16)))
            val original = temp("opus_run_up_$channels.mp4")
            run(listOf(ffmpeg, "-y", "-v", "error", "-i", input.path, "-c:a", "libopus", "-b:a", "96k", original.path))
            val edited = original.readBytes()
            val elst = indexOfType(edited, "elst")
            assertTrue(elst > 0)
            assertEquals(0, edited[elst + 4].toInt())
            val start = ByteBuffer.wrap(edited, elst + 16, 4).order(ByteOrder.BIG_ENDIAN).int
            writeU32(edited, elst + 12, 2_000)
            writeU32(edited, elst + 16, start + rate)
            val file = temp("opus_run_up_edit_$channels.mp4").also { it.writeBytes(edited) }
            fun reference(source: File): FloatArray {
                val output = temp(source.name + ".f32")
                run(listOf(ffmpeg, "-y", "-v", "error", "-i", source.path, "-f", "f32le", output.path))
                val buffer = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                return FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
            val whole = reference(original)
            val expected = reference(file)
            // FFmpeg retains coded end padding, as in the other MP4 Opus
            // comparisons. Its complete edit still equals its full PCM slice.
            assertTrue(expected.size >= rate * 2 * channels)
            TestSignals.assertExact(whole.copyOfRange(rate * channels, whole.size), expected,
                "FFmpeg's edit is an exact slice of its full decode")
            assertEquals(rate * 2L, AudioKodec.probe(edited).durationFrames)
            AudioKodec.open(edited).use { decoder ->
                val first = assertNotNull(decoder.nextFrame())
                assertEquals(0L, first.positionFrames)
                assertEquals(0L, decoder.seek(0))
                val sought = assertNotNull(decoder.nextFrame())
                assertEquals(first.frameCount, sought.frameCount)
                TestSignals.assertExact(first.samples, sought.samples, "opening and seeking use identical packet state")
                val squareError = first.samples.indices.sumOf { i ->
                    val error = (first.samples[i] - expected[i]).toDouble()
                    error * error
                }
                // Opus's recommended 80 ms run-up approximates the full-stream
                // state. The 0.01 RMS bound on this 0.125-amplitude tone allows
                // initial settling; opening versus seek(0) above independently
                // catches omitted run-up without claiming exact seeking.
                val rms = kotlin.math.sqrt(squareError / first.samples.size)
                assertTrue(rms < 0.01, "$channels channels: initial RMS difference $rms")
            }
            val decoded = AudioKodec.decode(edited)
            assertEquals(rate * 2, decoded.frames)
            // Use the existing seek comparison's 150 ms settling window and
            // 1e-3 maximum sample difference for the rest of this edit.
            val worst = (7_200 * channels until decoded.samples.size).maxOf { abs(decoded.samples[it] - expected[it]) }
            assertTrue(worst <= 1e-3f, "$channels channels: settled difference $worst")
        }
    }

    @Test
    fun chaptersAgreeWithFfprobe() {
        val ffmpeg = ffmpeg ?: return skip()
        val ffprobe = ffprobe ?: return skip()
        val metadata = temp("chapters.txt")
        metadata.writeText(
            ";FFMETADATA1\ntitle=Book\n" +
                listOf(0 to 1_200, 1_200 to 2_000, 2_000 to 3_000).withIndex().joinToString("") { (i, span) ->
                    "[CHAPTER]\nTIMEBASE=1/1000\nSTART=${span.first}\nEND=${span.second}\ntitle=Part ${i + 1} été\n"
                },
        )
        val input = temp("chapter_input.wav")
        writeWav(input, 2, 3.0)
        val file = temp("audiobook.m4b")
        run(
            listOf(
                ffmpeg, "-y", "-v", "error", "-i", input.path, "-i", metadata.path, "-map", "0:a", "-map_metadata", "1",
                "-map_chapters", "1", "-c:a", "alac", "-f", "ipod", file.path,
            ),
        )
        val bytes = file.readBytes()
        val expected = chaptersOf(ffprobe, file)
        val withTrack = AudioKodec.readTags(bytes).chapters
        assertEquals(expected.map { it.first }, withTrack.map { it.startMillis }, "chapter starts from the chapter track")
        assertEquals(expected.map { it.second }, withTrack.map { it.endMillis }, "chapter ends from the chapter track")
        assertEquals(expected.map { it.third }, withTrack.map { it.title }, "chapter titles from the chapter track")

        // With the reference to the chapter track hidden, only Nero's
        // 'chpl' list is left, which states starts and titles.
        val neroOnly = bytes.copyOf()
        val tref = indexOfType(neroOnly, "tref")
        assertTrue(tref > 0, "ffmpeg wrote no chapter track")
        "free".forEachIndexed { i, c -> neroOnly[tref + i] = c.code.toByte() }
        val nero = AudioKodec.readTags(neroOnly).chapters
        assertEquals(expected.map { it.first }, nero.map { it.startMillis }, "chapter starts from 'chpl'")
        assertEquals(expected.map { it.third }, nero.map { it.title }, "chapter titles from 'chpl'")
        assertEquals(expected.dropLast(1).map { it.second }, nero.dropLast(1).map { it.endMillis }, "chapter ends from 'chpl'")
    }

    @Test
    fun seekingLandsWhereDecodingFromTheStartWould() {
        val ffmpeg = ffmpeg ?: return skip()
        for ((name, options, tolerance) in listOf(
            Triple("seek_pcm.mov", listOf("-c:a", "pcm_s16le"), 0f),
            Triple("seek_flac.mp4", listOf("-c:a", "flac"), 0f),
            Triple("seek_flac_fragmented.mp4", listOf("-c:a", "flac", "-movflags", "frag_keyframe+empty_moov", "-frag_duration", "400000"), 0f),
            Triple("seek_opus.mp4", listOf("-c:a", "libopus"), 1e-3f),
        )) {
            val bytes = encode(ffmpeg, name, 2, options, seconds = 4.0).readBytes()
            val whole = AudioKodec.decode(bytes)
            AudioKodec.open(bytes).use { decoder ->
                for (target in longArrayOf(0, 1, 4_095, 4_096, 44_100, 100_003, whole.frames - 500L)) {
                    assertEquals(target, decoder.seek(target), "$name: seek to $target")
                    val got = ArrayList<Float>()
                    while (got.size < 9_600 * 2) {
                        val frame = decoder.nextFrame() ?: break
                        assertEquals(target + got.size / 2, frame.positionFrames, "$name: position after seeking to $target")
                        for (i in 0 until frame.frameCount * 2) got += frame.samples[i]
                    }
                    val count = minOf(got.size, (whole.frames - target.toInt()) * 2)
                    // Opus restarts 80 ms before the target and settles by
                    // 150 ms; the others start exactly where they left off.
                    val from = if (tolerance > 0f) minOf(count, 2 * 7_200) else 0
                    var worst = 0f
                    for (i in from until count) worst = maxOf(worst, abs(got[i] - whole.samples[target.toInt() * 2 + i]))
                    assertTrue(worst <= tolerance, "$name: after seeking to $target the samples differ by $worst")
                }
            }
        }
    }

    private fun encode(ffmpeg: String, name: String, channels: Int, options: List<String>, seconds: Double = 3.0): File {
        val input = temp("input_${channels}_$seconds.wav")
        if (!input.isFile) writeWav(input, channels, seconds)
        val out = temp(name)
        run(listOf(ffmpeg, "-y", "-v", "error", "-i", input.path) + options + out.path)
        return out
    }

    /** A different tone in each channel, so that a swapped channel shows. */
    private fun writeWav(file: File, channels: Int, seconds: Double) {
        val rate = 44_100
        val frames = (rate * seconds).toInt()
        val samples = FloatArray(frames * channels) { i ->
            val c = i % channels
            (0.4 * kotlin.math.sin(2 * Math.PI * (220.0 + 110.0 * c) * (i / channels) / rate)).toFloat()
        }
        file.writeBytes(AudioKodec.encode(samples, AudioFormat(rate, ChannelLayout.canonical(channels)), EncodeTarget.wav(16)))
    }

    private fun streamOf(ffprobe: String, file: File): Map<String, String> =
        runCapturing(
            listOf(ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=sample_rate,channels,duration_ts", "-of", "default=nw=1", file.path),
        ).lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

    /** Start and end in milliseconds and title of each chapter `ffprobe` lists. */
    private fun chaptersOf(ffprobe: String, file: File): List<Triple<Long, Long, String?>> =
        runCapturing(listOf(ffprobe, "-v", "error", "-show_chapters", "-of", "csv=p=0", "-show_entries", "chapter=start_time,end_time:chapter_tags=title", file.path))
            .lines().filter { it.isNotBlank() }.map { line ->
                val parts = line.split(',', limit = 3)
                Triple(
                    Math.round(parts[0].toDouble() * 1000),
                    Math.round(parts[1].toDouble() * 1000),
                    parts.getOrNull(2)?.ifEmpty { null },
                )
            }

    private fun indexOfType(bytes: ByteArray, type: String): Int {
        val pattern = type.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in 4..bytes.size - 4) {
            for (k in 0 until 4) if (bytes[i + k] != pattern[k]) continue@outer
            return i
        }
        return -1
    }

    private fun writeU32(bytes: ByteArray, at: Int, value: Int) {
        for (k in 0 until 4) bytes[at + k] = (value ushr (24 - 8 * k)).toByte()
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(180, TimeUnit.SECONDS), "${command.first()} did not finish")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed:\n$output")
    }

    private fun runCapturing(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(180, TimeUnit.SECONDS), "${command.first()} did not finish")
        return output
    }

    private fun temp(name: String): File = File(workDirectory, name)

    private fun skip() {
        org.junit.Assume.assumeTrue("ffmpeg or ffprobe is not on the path, so the MP4 comparison did not run", false)
    }

    private companion object {
        val workDirectory: File = File(System.getProperty("java.io.tmpdir"), "audiokodec-mp4-oracle").also { it.mkdirs() }

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

package io.github.yuroyami.audiokodec.sample

import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioFrame
import io.github.yuroyami.audiokodec.AudioInfo
import io.github.yuroyami.audiokodec.AudioCodec
import io.github.yuroyami.audiokodec.ChannelLayout
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewerTest {

    /** Checks what the viewer shows for [sample], and that its whole waveform decodes. */
    private fun check(sample: SampleFile) {
        val report = openSource(sample.file).use { describe(sample.file.name, it) }
        val what = "${sample.label}: ${report.asText()}"
        assertNull(report.problem, what)
        assertNull(report.tagProblem, what)
        val info = assertNotNull(report.info, what)
        assertEquals(sample.container, report.container, what)
        assertEquals(sample.codec, info.codec, what)
        assertEquals(sample.sampleRate, info.format.sampleRate, what)
        assertEquals(sample.channels, info.format.channels, what)
        val facts = report.facts().toMap()
        assertEquals(sample.container.displayName, facts["Container"], what)
        assertTrue(facts.getValue("Coding").startsWith(sample.codec.displayName), what)
        assertEquals(sampleRateText(sample.sampleRate), facts["Sample rate"], what)
        assertEquals(sample.file.length(), report.sizeBytes, what)
        if (sample.headerFrames != null) {
            assertEquals(sample.headerFrames, info.durationFrames, what)
        } else {
            val seconds = assertNotNull(info.durationSeconds, what)
            assertTrue(abs(seconds - sample.seconds) < 0.06, "$what: $seconds s")
        }
        if (sample.title != null) {
            assertContains(report.tagRows(), "Title" to sample.title, what)
            assertContains(report.tagRows(), "Artist" to "First Artist", what)
        }

        val peaks = openDecoder(sample.file).use { waveformPeaks(it, 400) }
        assertEquals(sample.channels, peaks.channels, what)
        assertTrue(peaks.buckets in 1..400, "$what: ${peaks.buckets} buckets")
        sample.frames?.let { assertEquals(it, peaks.totalFrames, what) }
        assertTrue(peaks.max.any { it > 0.01f }, "$what: the waveform is silent")
    }

    @Test
    fun everyContainerTheLibraryWritesIsDescribedAndDecoded() {
        for (sample in ViewerFixtures.library()) check(sample)
    }

    @Test
    fun aFileTheLibraryTaggedShowsEveryValueAndItsCover() {
        val sample = ViewerFixtures.library().first { it.label == "FLAC" }
        val report = openSource(sample.file).use { describe(sample.file.name, it) }
        val rows = report.tagRows()
        assertContains(rows, "Title" to ViewerFixtures.TITLE)
        assertContains(rows, "Artist" to "First Artist")
        assertContains(rows, "Artist" to "Second Artist")
        assertContains(rows, "Album" to "Test Signals")
        assertContains(rows, "Track number" to "3")
        assertContains(rows, "Picture" to "Front cover, image/png, 300 x 300, ${sizeText(ViewerFixtures.coverPng.size.toLong())}")
        assertContentEquals(ViewerFixtures.coverPng, report.cover?.bytes)
        val facts = report.facts().toMap()
        assertEquals("FLAC (lossless)", facts["Coding"])
        assertEquals("44.1 kHz", facts["Sample rate"])
        assertEquals("stereo: L, R", facts["Layout"])
        assertEquals("16-bit", facts["Bit depth"])
        assertEquals("0:02.000 (88200 frames)", facts["Duration"])
        assertEquals("Vorbis comment", facts["Tag blocks"])
        assertEquals("FLAC / FLAC, 44.1 kHz, stereo", report.headline)
    }

    @Test
    fun everyContainerFfmpegWritesIsDescribedAndDecoded() {
        val ffmpeg = Ffmpeg.find()
        assumeTrue("ffmpeg is not on PATH", ffmpeg != null)
        for (sample in ViewerFixtures.ffmpeg(ffmpeg!!)) check(sample)
    }

    @Test
    fun theCodingsNothingHereEncodesAreDescribedAndDecoded() {
        for (sample in ViewerFixtures.vectors()) check(sample)
    }

    @Test
    fun surroundNamesItsSpeakersInOrder() {
        val report = openSource(ViewerFixtures.surroundWav()).use { describe("surround.wav", it) }
        assertEquals("5.1: L, R, C, LFE, Lb, Rb", report.facts().toMap()["Layout"])
        assertEquals("WAV / PCM, 48 kHz, 5.1", report.headline)
    }

    @Test
    fun aFormatWithoutADecoderIsNamedAndExplained() {
        val report = openSource(ViewerFixtures.midi()).use { describe("empty.mid", it) }
        assertEquals(AudioContainer.MIDI, report.container)
        assertNull(report.info)
        assertFalse(report.looksDecodable)
        assertEquals("This is a MIDI file, which AudioKodec cannot decode yet.", report.problem)
        assertEquals("MIDI, which AudioKodec cannot read", report.headline)
    }

    @Test
    fun aDamagedFileSaysSoInWordsWhenItDecodes() {
        val file = ViewerFixtures.truncatedFlac()
        val report = openSource(file).use { describe(file.name, it) }
        // The header is intact, so the damage shows only once the audio is read.
        assertNull(report.problem)
        val failure = assertFailsWith<Exception> { openDecoder(file).use { waveformPeaks(it, 100) } }
        val message = readableProblem(failure, report.container)
        assertTrue(message.startsWith("This FLAC file ends early: "), message)
    }

    @Test
    fun bytesThatAreNotAudioAreNotMistakenForAudio() {
        val file = File(ViewerFixtures.directory, "notes.txt").apply { writeText("Nothing to hear here.\n".repeat(20)) }
        val report = openSource(file).use { describe(file.name, it) }
        assertNull(report.container)
        assertNull(report.info)
        assertEquals("AudioKodec does not recognise this file as audio.", report.problem)
        assertEquals("Not an audio file AudioKodec recognises", report.headline)
    }

    @Test
    fun anUnknownLengthIsReducedAndMergedToTheWidthAsked() {
        val samples = FloatArray(10_000 * 2) { i -> if (i % 2 == 0) (i / 2 % 100) / 100f else -(i / 2 % 50) / 50f }
        val known = waveformPeaks(FakeDecoder(samples, declared = true), 10)
        val unknown = waveformPeaks(FakeDecoder(samples, declared = false), 10)
        for (peaks in listOf(known, unknown)) {
            assertEquals(10_000, peaks.totalFrames)
            assertTrue(peaks.buckets in 1..10)
            assertEquals(0.99f, peaks.max.filterIndexed { i, _ -> i % 2 == 0 }.max())
            assertEquals(-0.98f, peaks.min.filterIndexed { i, _ -> i % 2 == 1 }.min())
        }
        assertEquals(10, known.buckets)
    }

    @Test
    fun floatsBecomeClampedLittleEndianShorts() {
        val samples = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f, 1.5f, -2f, 1f / 32768)
        val bytes = ByteArray(samples.size * 2)
        pcm16LittleEndian(samples, samples.size, IntArray(samples.size), bytes)
        val shorts = (samples.indices).map { (bytes[2 * it].toInt() and 0xFF) or (bytes[2 * it + 1].toInt() shl 8) }
        assertEquals(listOf(0, 16384, -16384, 32767, -32768, 32767, -32768, 1), shorts)
    }

    @Test
    fun theOutputPlanKeepsTheFileWhenItCanAndMixesOrConvertsWhenItMust() {
        val stereo = AudioFormat(44_100, 2)
        val surround = AudioFormat(48_000, ChannelLayout.SURROUND_5_1)
        val anything: (Int, Int) -> Boolean = { _, _ -> true }
        val stereoOnly: (Int, Int) -> Boolean = { rate, channels -> channels == 2 && rate in setOf(44_100, 48_000) }

        val asIs = planOutput(stereo, anything)
        assertEquals(44_100, asIs.sampleRate)
        assertNull(asIs.mixTo)
        assertNull(asIs.note)

        val mixed = planOutput(surround, stereoOnly)
        assertEquals(ChannelLayout.STEREO, mixed.mixTo)
        assertEquals(48_000, mixed.sampleRate)
        assertEquals("The audio device did not accept this file as it is, so playback is mixed from 5.1 to stereo.", mixed.note)

        val dsd = planOutput(AudioFormat(88_200, 2), stereoOnly)
        assertEquals(48_000, dsd.sampleRate)
        assertEquals("The audio device did not accept this file as it is, so playback is converted from 88.2 kHz to 48 kHz.", dsd.note)

        val unnamed = assertFailsWith<PlaybackRefused> { planOutput(AudioFormat(48_000, ChannelLayout.unknown(3)), stereoOnly) }
        assertEquals(
            "The audio device cannot play 3 channels at 48 kHz, and the file does not say which speaker each channel is for, so it cannot be mixed down.",
            unnamed.message,
        )
        val none = assertFailsWith<PlaybackRefused> { planOutput(stereo) { _, _ -> false } }
        assertEquals("No audio output is available on this machine.", none.message)
    }

    @Test
    fun javaSoundAnswersWithoutThrowingWhetherOrNotThereIsADevice() {
        // This machine may have no audio device at all; the answer must still be a plain yes or no.
        JavaSoundDevice.accepts(44_100, 2)
    }

    /** Plays [file] on [device] and waits for the player to stop, returning the plan, whether it reached the end, and any problem. */
    private fun playToEnd(file: File, device: OutputDevice): Triple<OutputPlan?, Boolean, String?> {
        val ended = CountDownLatch(1)
        val chosen = AtomicReference<OutputPlan>()
        val outcome = AtomicReference<Pair<Boolean, String?>>()
        Player(device).play({ openDecoder(file) }, object : PlaybackListener {
            override fun started(plan: OutputPlan) {
                chosen.set(plan)
            }

            override fun stopped(reachedEnd: Boolean, problem: String?) {
                outcome.set(reachedEnd to problem)
                ended.countDown()
            }
        })
        assertTrue(ended.await(30, TimeUnit.SECONDS), "the player did not stop")
        return Triple(chosen.get(), outcome.get().first, outcome.get().second)
    }

    @Test
    fun thePlayerStreamsTheWholeFileInTheShapeTheDeviceAccepts() {
        val device = RecordingDevice(accept = { rate, channels -> channels == 2 && rate == 48_000 })
        val (plan, reachedEnd, problem) = playToEnd(ViewerFixtures.surroundWav(), device)
        assertNull(problem)
        assertTrue(reachedEnd)
        assertEquals(ChannelLayout.STEREO, plan?.mixTo)
        // One second at 48 kHz, two channels of two bytes, mixed down from 5.1.
        assertEquals(48_000 * 2 * 2, device.written)
        assertTrue(device.drained)
        assertTrue(device.closed)
    }

    @Test
    fun stopEndsPlaybackPartWayAndClosesTheOutput() {
        val firstWrite = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val outcome = AtomicReference<Pair<Boolean, String?>>()
        val device = RecordingDevice({ _, _ -> true }) {
            firstWrite.countDown()
            // A device plays in real time, so a write takes a while.
            Thread.sleep(5)
        }
        val player = Player(device)
        player.play({ openDecoder(ViewerFixtures.library().first { it.label == "FLAC" }.file) }, object : PlaybackListener {
            override fun stopped(reachedEnd: Boolean, problem: String?) {
                outcome.set(reachedEnd to problem)
                ended.countDown()
            }
        })
        assertTrue(firstWrite.await(30, TimeUnit.SECONDS))
        player.stop()
        assertTrue(ended.await(30, TimeUnit.SECONDS))
        assertEquals(false to null, outcome.get())
        assertTrue(device.written < ViewerFixtures.SECONDS * ViewerFixtures.RATE * 4)
        assertTrue(device.discarded)
        assertTrue(device.closed)
        assertFalse(player.isPlaying)
    }

    @Test
    fun thePlayerResamplesForADeviceThatRefusesTheRate() {
        val sample = ViewerFixtures.vectors().first { it.label == "DSF" }
        val device = RecordingDevice(accept = { rate, channels -> rate == 48_000 && channels <= 2 })
        val decoder = openDecoder(sample.file)
        val plan = planOutput(decoder.format, device::accepts)
        val output = device.open(plan.sampleRate, plan.channels)
        val reachedEnd = decoder.use { stream(it, PcmConverter(it.format, plan), output, { false }) }
        assertTrue(reachedEnd)
        // The resampler writes ceil(frames * 48000 / 88200) frames in all.
        val expected = (sample.frames!! * 48_000 + 88_199) / 88_200
        assertEquals(expected * 2, device.written.toLong())
    }

    @Test
    fun aRefusalReachesTheListenerAsWords() {
        val (plan, reachedEnd, problem) = playToEnd(ViewerFixtures.library().first().file, RecordingDevice(accept = { _, _ -> false }))
        assertNull(plan)
        assertFalse(reachedEnd)
        assertEquals("No audio output is available on this machine.", problem)
    }
}

/** An output device that keeps count of what it was given instead of playing it. */
private class RecordingDevice(
    private val accept: (Int, Int) -> Boolean,
    private val onWrite: () -> Unit = {},
) : OutputDevice {
    @Volatile var written = 0
    @Volatile var drained = false
    @Volatile var discarded = false
    @Volatile var closed = false

    override fun accepts(rate: Int, channels: Int) = accept(rate, channels)

    override fun open(rate: Int, channels: Int): PcmOutput = object : PcmOutput {
        override fun write(bytes: ByteArray, count: Int) {
            written += count
            onWrite()
        }
        override fun drain() { drained = true }
        override fun discard() { discarded = true }
        override fun close() { closed = true }
    }
}

/** A decoder over [samples], two channels, in blocks of 777 frames, with or without a stated length. */
private class FakeDecoder(private val samples: FloatArray, declared: Boolean) : AudioDecoder {
    override val format = AudioFormat(1_000, 2)
    override val durationFrames: Long? = if (declared) samples.size / 2L else null
    override val info = AudioInfo(AudioContainer.WAV, AudioCodec.PCM_FLOAT, format, durationFrames, 32, null, false, 0, -1)
    override var positionFrames = 0L
        private set

    override fun nextFrame(): AudioFrame? {
        val total = samples.size / 2
        if (positionFrames >= total) return null
        val count = minOf(777, total - positionFrames.toInt())
        val start = positionFrames.toInt()
        val frame = AudioFrame(format, samples.copyOfRange(start * 2, (start + count) * 2), count, positionFrames)
        positionFrames += count
        return frame
    }

    override fun seek(frameIndex: Long): Long = frameIndex.also { positionFrames = it }

    override fun close() {}
}

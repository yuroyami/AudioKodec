package io.github.yuroyami.audiokodec.sample

import io.github.yuroyami.audiokodec.AudioDecoder
import javax.sound.sampled.AudioFormat as SoundFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine

/** Where the player sends its 16-bit PCM. Java Sound in the application, a recording in the tests. */
internal interface OutputDevice {
    /** True when the device says it can take 16-bit PCM at [rate] Hz with [channels] channels. */
    fun accepts(rate: Int, channels: Int): Boolean

    /** Opens an output for that shape, or throws [PlaybackRefused] saying why it cannot. */
    fun open(rate: Int, channels: Int): PcmOutput
}

/** The audio interface the JVM already has: a [SourceDataLine] from [AudioSystem]. */
internal object JavaSoundDevice : OutputDevice {
    private fun format(rate: Int, channels: Int) = SoundFormat(rate.toFloat(), 16, channels, true, false)

    override fun accepts(rate: Int, channels: Int): Boolean = try {
        AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, format(rate, channels)))
    } catch (_: Exception) {
        // A machine with no sound system at all can throw rather than answer.
        false
    }

    override fun open(rate: Int, channels: Int): PcmOutput {
        val format = format(rate, channels)
        val line = try {
            AudioSystem.getSourceDataLine(format).apply {
                // About a quarter of a second, so that Stop is heard promptly.
                open(format, (rate / 4) * format.frameSize)
                start()
            }
        } catch (failure: LineUnavailableException) {
            throw PlaybackRefused("The audio device is busy or unavailable: ${failure.message ?: "no reason given"}.")
        } catch (failure: IllegalArgumentException) {
            throw PlaybackRefused("The audio device cannot play this: ${failure.message ?: "no reason given"}.")
        }
        return object : PcmOutput {
            override fun write(bytes: ByteArray, count: Int) {
                var at = 0
                while (at < count) {
                    val wrote = line.write(bytes, at, count - at)
                    // A line that was stopped and flushed from another thread writes nothing.
                    if (wrote <= 0) return
                    at += wrote
                }
            }

            override fun drain() = line.drain()

            override fun discard() {
                line.stop()
                line.flush()
            }

            override fun close() = line.close()
        }
    }
}

/** What the player tells the window, from its own thread. */
internal interface PlaybackListener {
    /** The output was opened in [plan]'s shape. */
    fun started(plan: OutputPlan) {}

    /** The decoder has reached [frame]. */
    fun position(frame: Long) {}

    /** Playback ended, at the end of the audio if [reachedEnd], or with [problem] in words if it failed. */
    fun stopped(reachedEnd: Boolean, problem: String?) {}
}

/**
 * Plays one file at a time on a background thread.
 *
 * The decoder streams, so a file of any length plays in the memory of a few
 * blocks. [stop] returns at once; the thread discards what the device has
 * not played yet and closes the decoder and the output.
 */
internal class Player(private val device: OutputDevice) {
    @Volatile private var current: Session? = null

    private class Session {
        @Volatile var stopped = false
        @Volatile var output: PcmOutput? = null
        lateinit var thread: Thread
    }

    val isPlaying: Boolean get() = current?.thread?.isAlive == true

    /**
     * Stops whatever is playing and plays the decoder that [open] returns,
     * from [startFrame]. [open] runs on the player's thread.
     */
    fun play(open: () -> AudioDecoder, listener: PlaybackListener, startFrame: Long = 0) {
        stop()
        val session = Session()
        session.thread = Thread({ run(session, open, listener, startFrame) }, "AudioKodec playback").apply { isDaemon = true }
        current = session
        session.thread.start()
    }

    /** Stops playback, if any, without waiting for the thread to finish. */
    fun stop() {
        val session = current ?: return
        current = null
        session.stopped = true
        try {
            session.output?.discard()
        } catch (_: Exception) {
            // The playback thread closes the output itself; a failure to cut it short changes nothing.
        }
    }

    private fun run(session: Session, open: () -> AudioDecoder, listener: PlaybackListener, startFrame: Long) {
        var problem: String? = null
        var reachedEnd = false
        try {
            open().use { decoder ->
                if (startFrame > 0) decoder.seek(startFrame)
                val plan = planOutput(decoder.format, device::accepts)
                device.open(plan.sampleRate, plan.channels).use { output ->
                    session.output = output
                    if (session.stopped) return@use
                    listener.started(plan)
                    reachedEnd = stream(decoder, PcmConverter(decoder.format, plan), output, { session.stopped }, listener::position)
                    if (reachedEnd) output.drain()
                }
            }
        } catch (failure: PlaybackRefused) {
            problem = failure.message
        } catch (failure: Exception) {
            problem = readableProblem(failure, null)
        }
        listener.stopped(reachedEnd && !session.stopped, problem)
        if (current === session) current = null
    }
}

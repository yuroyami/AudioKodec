package io.github.yuroyami.audiokodec.sample

import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.Pcm
import io.github.yuroyami.audiokodec.Resampler

/**
 * How decoded audio reaches an output that takes 16-bit signed little-endian
 * PCM: at which rate and channel count, mixed into which layout if the
 * output cannot take the file's own.
 */
internal class OutputPlan(
    val sampleRate: Int,
    val channels: Int,
    /** The layout to mix into, or null when the file's channels go out as they are. */
    val mixTo: ChannelLayout?,
    /** What was changed to make the output accept the audio, in words, or null when nothing was. */
    val note: String?,
)

/** The output cannot play the audio in any shape the plan tried; [message] says why, in words. */
internal class PlaybackRefused(message: String) : Exception(message)

/**
 * Chooses how to play [format] on an output that [accepts] some rates and
 * channel counts.
 *
 * The file's own shape is tried first. An output that refuses it is offered
 * a mix to stereo and then to mono, when the file names its speakers so that
 * a mix is defined, each at the file's rate, then at 48 and 44.1 kHz. Audio
 * whose speakers are not named is never guessed at: it plays as it is or not
 * at all.
 *
 * @throws PlaybackRefused when nothing is accepted.
 */
internal fun planOutput(format: AudioFormat, accepts: (rate: Int, channels: Int) -> Boolean): OutputPlan {
    val layout = format.channelLayout
    val layouts = buildList<ChannelLayout?> {
        add(null)
        if (layout.isPositional) {
            for (target in listOf(ChannelLayout.STEREO, ChannelLayout.MONO)) {
                if (target != layout && mixable(layout, target)) add(target)
            }
        }
    }
    val rates = listOf(format.sampleRate, 48_000, 44_100).distinct()
    for (mixTo in layouts) {
        val channels = mixTo?.count ?: format.channels
        for (rate in rates) {
            if (!accepts(rate, channels)) continue
            val changes = listOfNotNull(
                mixTo?.let { "mixed from ${layoutName(layout)} to ${layoutName(it)}" },
                if (rate != format.sampleRate) "converted from ${sampleRateText(format.sampleRate)} to ${sampleRateText(rate)}" else null,
            )
            val note = if (changes.isEmpty()) null else "The audio device did not accept this file as it is, so playback is " + changes.joinToString(" and ") + "."
            return OutputPlan(rate, channels, mixTo, note)
        }
    }
    if (!accepts(48_000, 2) && !accepts(44_100, 2) && !accepts(48_000, 1) && !accepts(44_100, 1)) {
        throw PlaybackRefused("No audio output is available on this machine.")
    }
    val why = if (layout.isPositional) "" else ", and the file does not say which speaker each channel is for, so it cannot be mixed down"
    throw PlaybackRefused(
        "The audio device cannot play ${format.channels} channels at ${sampleRateText(format.sampleRate)}$why.",
    )
}

private fun mixable(from: ChannelLayout, to: ChannelLayout): Boolean = try {
    Pcm.mixMatrix(from, to)
    true
} catch (_: AudioKodecException) {
    false
}

/**
 * Turns decoded Float32 blocks into the bytes an [OutputPlan] calls for:
 * mixed if the plan says so, resampled if the rate differs, then clamped and
 * rounded to 16-bit signed little-endian samples.
 *
 * Buffers grow to the largest block seen and are reused after that, so a
 * steady stream allocates nothing.
 */
internal class PcmConverter(private val from: AudioFormat, val plan: OutputPlan) {
    private val resampler = if (plan.sampleRate != from.sampleRate) Resampler(from.sampleRate, plan.sampleRate, plan.channels) else null
    private var mixed = FloatArray(0)
    private var resampled = FloatArray(0)
    private var ints = IntArray(0)
    private var bytes = ByteArray(0)

    /** Converts [frameCount] frames of interleaved [samples] and hands the bytes to [emit], which must not keep the array. */
    fun convert(samples: FloatArray, frameCount: Int, emit: (ByteArray, Int) -> Unit) {
        var data = samples
        if (plan.mixTo != null) {
            val needed = frameCount * plan.channels
            if (mixed.size < needed) mixed = FloatArray(needed)
            data = Pcm.mixChannels(samples, frameCount, from.channelLayout, plan.mixTo, mixed)
        }
        if (resampler == null) {
            emit16(data, frameCount * plan.channels, emit)
            return
        }
        val room = resampler.maxOutputFrames(frameCount) * plan.channels
        if (resampled.size < room) resampled = FloatArray(room)
        val written = resampler.process(data, 0, frameCount, resampled, 0)
        emit16(resampled, written * plan.channels, emit)
    }

    /** Emits what the resampler still holds at the end of the stream. */
    fun finish(emit: (ByteArray, Int) -> Unit) {
        val resampler = resampler ?: return
        val room = resampler.pendingOutputFrames * plan.channels
        if (resampled.size < room) resampled = FloatArray(room)
        val written = resampler.flush(resampled)
        emit16(resampled, written * plan.channels, emit)
    }

    private fun emit16(samples: FloatArray, count: Int, emit: (ByteArray, Int) -> Unit) {
        if (count == 0) return
        if (ints.size < count) ints = IntArray(count)
        if (bytes.size < count * 2) bytes = ByteArray(count * 2)
        pcm16LittleEndian(samples, count, ints, bytes)
        emit(bytes, count * 2)
    }
}

/**
 * Writes [count] Float32 samples into [out] as 16-bit signed little-endian
 * integers, clamped and rounded by [Pcm.intFromFloat]. [scratch] holds the
 * integers on the way and must have room for [count].
 */
internal fun pcm16LittleEndian(samples: FloatArray, count: Int, scratch: IntArray, out: ByteArray) {
    val ints = Pcm.intFromFloat(samples, 16, count, scratch)
    for (i in 0 until count) {
        val v = ints[i]
        out[2 * i] = v.toByte()
        out[2 * i + 1] = (v shr 8).toByte()
    }
}

/** Somewhere 16-bit PCM can be written, such as a Java Sound line. */
internal interface PcmOutput : AutoCloseable {
    /** Writes [count] bytes of [bytes], blocking while the output is full. */
    fun write(bytes: ByteArray, count: Int)

    /** Waits until everything written has been heard. */
    fun drain()

    /** Throws away whatever has not been heard yet. */
    fun discard()

    override fun close()
}

/**
 * Decodes [decoder] to its end, or until [stopped] says so, through
 * [converter] into [output]. [progress] receives the decoder's position
 * after each block. Returns true when the end of the audio was reached.
 *
 * Neither the decoder nor the output is closed.
 */
internal fun stream(
    decoder: AudioDecoder,
    converter: PcmConverter,
    output: PcmOutput,
    stopped: () -> Boolean,
    progress: (Long) -> Unit = {},
): Boolean {
    val write: (ByteArray, Int) -> Unit = { bytes, count -> output.write(bytes, count) }
    while (!stopped()) {
        val block = decoder.nextFrame()
        if (block == null) {
            converter.finish(write)
            return true
        }
        converter.convert(block.samples, block.frameCount, write)
        progress(decoder.positionFrames)
    }
    return false
}

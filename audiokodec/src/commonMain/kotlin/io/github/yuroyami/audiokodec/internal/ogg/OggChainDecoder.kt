package io.github.yuroyami.audiokodec.internal.ogg

import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioFrame
import io.github.yuroyami.audiokodec.AudioInfo
import io.github.yuroyami.audiokodec.AudioSource
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.IntAudioFrame
import io.github.yuroyami.audiokodec.IntPcmAccess
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException

/** One fixed output format over consecutive, independently trimmed Ogg links. */
internal open class OggChainDecoder(
    private val source: AudioSource,
    private val parsed: OggChain.Parsed,
    final override val info: AudioInfo,
    private val options: DecodeOptions,
    private val build: (Int) -> AudioDecoder,
) : AudioDecoder {
    final override val format: AudioFormat = info.format
    final override val durationFrames: Long? = info.durationFrames
    final override var positionFrames: Long = 0L
        private set

    private var link = 0
    private var current: AudioDecoder? = build(0)
    private var pendingSkip = 0L
    private var ended = false
    private var closed = false
    private var decodedAny = false

    override fun nextFrame(): AudioFrame? = readSafely {
        while (canRead()) {
            val decoder = current ?: build(link).also { current = it }
            val frame = decoder.nextFrame()
            if (frame == null) { advance(); continue }
            val drop = skip(frame.frameCount)
            val count = frame.frameCount - drop
            if (count == 0) continue
            val start = positionFrames
            progressed(count)
            val samples = if (drop == 0) frame.samples else {
                val from = drop * format.channels
                val to = frame.sampleCount
                if (options.reuseBuffers) frame.samples.also { it.copyInto(it, 0, from, to) }
                else frame.samples.copyOfRange(from, to)
            }
            return@readSafely AudioFrame(format, samples, count, start)
        }
        null
    }

    protected fun nextIntFrameInternal(bitDepth: Int): IntAudioFrame? = readSafely {
        while (canRead()) {
            val decoder = current ?: build(link).also { current = it }
            val access = decoder as? IntPcmAccess ?: throw CorruptStreamException("an Ogg FLAC link has no integer samples")
            val frame = access.nextIntFrame()
            if (frame == null) { advance(); continue }
            val drop = skip(frame.frameCount)
            val count = frame.frameCount - drop
            if (count == 0) continue
            val start = positionFrames
            progressed(count)
            val samples = if (drop == 0) frame.samples else {
                val from = drop * format.channels
                val to = frame.sampleCount
                if (options.reuseBuffers) frame.samples.also { it.copyInto(it, 0, from, to) }
                else frame.samples.copyOfRange(from, to)
            }
            return@readSafely IntAudioFrame(format, samples, count, start, bitDepth)
        }
        null
    }

    private inline fun <T> readSafely(read: () -> T?): T? {
        check(!closed) { "this decoder is closed" }
        try {
            return read()
        } catch (failure: CorruptStreamException) {
            if (!options.tolerant || !decodedAny) throw failure
        } catch (failure: TruncatedStreamException) {
            if (!options.tolerant || !decodedAny) throw failure
        }
        ended = true
        return null
    }

    private fun canRead(): Boolean = !ended && link < parsed.links.size &&
        (durationFrames == null || positionFrames < durationFrames)

    private fun advance() {
        current?.close()
        current = null
        link++
        if (link == parsed.links.size) ended = true
    }

    private fun skip(count: Int): Int = minOf(pendingSkip, count.toLong()).toInt().also { pendingSkip -= it }

    private fun progressed(count: Int) {
        if (positionFrames > Long.MAX_VALUE - count) throw CorruptStreamException("an Ogg playback position overflows 64 bits")
        positionFrames += count
        decodedAny = true
    }

    override fun seek(frameIndex: Long): Long {
        check(!closed) { "this decoder is closed" }
        require(frameIndex >= 0) { "frameIndex must not be negative, was $frameIndex" }
        val target = durationFrames?.let { frameIndex.coerceAtMost(it) } ?: frameIndex
        if (durationFrames != null && target == durationFrames) {
            current?.close()
            current = null
            link = parsed.links.size
            pendingSkip = 0
            positionFrames = target
            ended = true
            return target
        }
        var selected = 0
        var start = 0L
        while (selected < parsed.links.lastIndex) {
            val duration = parsed.links[selected].durationFrames ?: break
            if (target < start + duration) break
            start += duration
            selected++
        }
        val local = target - start
        val unknown = parsed.links[selected].durationFrames == null
        if (unknown && local > 0 && !options.allowSlowSeek) {
            throw UnsupportedFeatureException("seeking through an Ogg link with unknown duration",
                "the chain must be decoded from this link's start to locate frame $target, and slow seeking is off")
        }
        val replacement = build(selected)
        try {
            replacement.seek(if (unknown) 0L else local)
        } catch (failure: Throwable) {
            replacement.close()
            throw failure
        }
        current?.close()
        current = replacement
        link = selected
        pendingSkip = if (unknown) local else 0L
        positionFrames = target
        ended = false
        return target
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            current?.close()
        } finally {
            current = null
            source.close()
        }
    }
}

internal class IntOggChainDecoder(
    source: AudioSource,
    parsed: OggChain.Parsed,
    info: AudioInfo,
    options: DecodeOptions,
    build: (Int) -> AudioDecoder,
    override val bitDepth: Int,
) : OggChainDecoder(source, parsed, info, options, build), IntPcmAccess {
    override fun nextIntFrame(): IntAudioFrame? = nextIntFrameInternal(bitDepth)
}

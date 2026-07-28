package io.github.yuroyami.kiteaudio.internal

import io.github.yuroyami.kiteaudio.AudioCodec
import io.github.yuroyami.kiteaudio.AudioContainer
import io.github.yuroyami.kiteaudio.AudioFormat
import io.github.yuroyami.kiteaudio.AudioInfo
import io.github.yuroyami.kiteaudio.DecodeOptions
import io.github.yuroyami.kiteaudio.internal.codec.SampleCodec
import io.github.yuroyami.kiteaudio.internal.io.SourceReader

/**
 * What a container parser found: where the audio is, how it is coded, and
 * everything a caller can learn without decoding.
 *
 * Probing and decoding share this, which is what keeps
 * [io.github.yuroyami.kiteaudio.KiteAudio.probe] honest. Anything `probe`
 * reports came out of the same parse that `open` would run.
 */
internal class ParsedStream(
    val container: AudioContainer,
    val codec: AudioCodec,
    val sampleCodec: SampleCodec,
    val format: AudioFormat,
    /** First byte of the audio payload. */
    val dataOffset: Long,
    /** Length of the audio payload in bytes, or -1 when it runs to end of input. */
    val dataBytes: Long,
    /** Frames the container states, or null when it does not state one. */
    val declaredFrames: Long?,
    /** Stored bits per sample, or null for codings where the idea does not apply. */
    val bitDepth: Int?,
    /** Decoded frames at the start that are codec priming rather than audio. */
    val trimStartFrames: Long = 0L,
) {
    /** Frames the payload holds, working it out from its size when needed. */
    fun frameCount(reader: SourceReader): Long? {
        declaredFrames?.let { return it }
        val bytes = if (dataBytes >= 0) {
            dataBytes
        } else {
            val size = reader.size
            if (size < 0) return null
            (size - dataOffset).coerceAtLeast(0L)
        }
        val blocks = bytes / sampleCodec.blockBytes
        if (blocks > Long.MAX_VALUE / sampleCodec.framesPerBlock) return null
        return (blocks * sampleCodec.framesPerBlock - trimStartFrames).coerceAtLeast(0L)
    }

    fun toInfo(reader: SourceReader): AudioInfo {
        val frames = frameCount(reader)
        val bytes = if (dataBytes >= 0) {
            dataBytes
        } else {
            val size = reader.size
            if (size < 0) -1L else (size - dataOffset).coerceAtLeast(0L)
        }
        val bitrate = if (frames != null && frames > 0 && bytes > 0) {
            val seconds = frames.toDouble() / format.sampleRate
            if (seconds > 0) {
                (bytes.toDouble() * 8.0 / seconds)
                    .coerceIn(0.0, Int.MAX_VALUE.toDouble())
                    .toInt()
            } else {
                null
            }
        } else {
            null
        }
        return AudioInfo(
            container = container,
            codec = codec,
            format = format,
            durationFrames = frames,
            bitDepth = bitDepth,
            bitrate = bitrate,
            // Every coding in this build has a fixed block size, so the rate
            // never varies. Formats where it does arrive with their codecs.
            isVariableBitrate = false,
            dataOffset = reader.absoluteOffset(dataOffset),
            dataSize = bytes,
        )
    }

    fun toDecoder(reader: SourceReader, options: DecodeOptions): BlockStreamDecoder {
        val info = toInfo(reader)
        return if (sampleCodec.storedBitDepth != null) {
            IntBlockStreamDecoder(
                reader = reader,
                codec = sampleCodec,
                format = format,
                info = info,
                dataOffset = dataOffset,
                dataBytes = dataBytes,
                declaredFrames = declaredFrames,
                trimStartFrames = trimStartFrames,
                options = options,
            )
        } else {
            BlockStreamDecoder(
                reader = reader,
                codec = sampleCodec,
                format = format,
                info = info,
                dataOffset = dataOffset,
                dataBytes = dataBytes,
                declaredFrames = declaredFrames,
                trimStartFrames = trimStartFrames,
                options = options,
            )
        }
    }
}

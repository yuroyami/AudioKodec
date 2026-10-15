package io.github.yuroyami.audiokodec.docs

// --8<-- [start:imports]
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.DecodedAudio
import io.github.yuroyami.audiokodec.Pcm
// --8<-- [end:imports]
import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.EncodeTarget
import kotlin.test.Test
import kotlin.test.assertEquals

// --8<-- [start:web-audio]
/**
 * Copies [audio] into a new Web Audio `AudioBuffer` made by [context], an
 * `AudioContext`. Both are browser objects, reached here through `dynamic`.
 */
fun toAudioBuffer(context: dynamic, audio: DecodedAudio): dynamic {
    val channels = audio.format.channels
    val buffer = context.createBuffer(channels, audio.frames, audio.format.sampleRate)
    // Web Audio keeps one Float32Array per channel; a Kotlin/JS FloatArray is one.
    val planes = Pcm.deinterleave(audio.samples, channels, audio.frames)
    for (c in 0 until channels) buffer.copyToChannel(planes[c], c)
    return buffer
}
// --8<-- [end:web-audio]

/**
 * The documentation's Web Audio sample, compiled for Kotlin/JS and run in
 * Node. Node has no Web Audio, so the test hands it a stand-in context that
 * records what it is given.
 */
class WebAudioSample {

    @Test
    fun audioReachesABufferOneChannelAtATime() {
        val bytes = AudioKodec.encode(floatArrayOf(0.5f, -0.5f, 0.25f, -0.25f, 0f, 1f), AudioFormat(48_000, 2), EncodeTarget.wavFloat())
        val context: dynamic = js(
            """({
                createBuffer: function (channels, frames, rate) {
                    return { channels: channels, frames: frames, rate: rate, data: [],
                             copyToChannel: function (source, channel) { this.data[channel] = Array.from(source); } };
                }
            })""",
        )

        val buffer = toAudioBuffer(context, AudioKodec.decode(bytes))

        assertEquals(2, buffer.channels)
        assertEquals(3, buffer.frames)
        assertEquals(48_000, buffer.rate)
        assertEquals("0.5,0.25,0", buffer.data[0].join(",") as String)
        assertEquals("-0.5,-0.25,1", buffer.data[1].join(",") as String)
    }
}

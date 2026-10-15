package io.github.yuroyami.audiokodec.docs

// --8<-- [start:imports]
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.io.openAudioSource
import io.github.yuroyami.audiokodec.tag.TagField
import kotlinx.io.files.Path
// --8<-- [end:imports]
import io.github.yuroyami.audiokodec.EncodeTarget
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The samples of the page on seekable file input. */
class FileInputSample {
    private val printed = ArrayList<String>()

    private fun println(line: String) {
        printed += line
    }

    @Test
    fun seekAndProbeAFile() {
        val file = File(Fixtures.directory, "recording.wav")
        file.writeBytes(AudioKodec.encode(Fixtures.samples, Fixtures.format, EncodeTarget.wav(16), Fixtures.tags))
        val path = Path(file.path)

        // --8<-- [start:seek]
        AudioKodec.open(path.openAudioSource()).use { decoder ->
            decoder.seek(1L * decoder.format.sampleRate)  // one second in
            val block = decoder.nextFrame()
            println("${block?.frameCount} frames from frame ${block?.positionFrames}")
        }
        // --8<-- [end:seek]

        assertEquals("from frame 44100", printed.removeFirst().substringAfter(" frames "))

        // --8<-- [start:probe]
        path.openAudioSource().use { source ->
            val info = AudioKodec.probe(source)
            val tags = AudioKodec.readTags(source)
            println("${tags[TagField.TITLE]}: ${info.durationSeconds} s")
        }
        // --8<-- [end:probe]

        assertEquals(listOf("Two Seconds: 2.0 s"), printed)
    }
}

package io.github.yuroyami.audiokodec.sample

import org.junit.Assume.assumeTrue
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Paints the viewer off screen for a few files and saves each picture, so
 * that a person can look at the layout without a display. The pictures go to
 * the directory in `AUDIOKODEC_SAMPLE_SCREENS`, or to `build/sample-screens`.
 */
class ViewerPanelTest {
    private val directory = File(System.getenv("AUDIOKODEC_SAMPLE_SCREENS") ?: "build/sample-screens").apply { mkdirs() }

    /** Shows [file] as the window would and saves the picture as [name]. */
    private fun screen(name: String, file: File, playhead: Double = 0.0): BufferedImage {
        val report = openSource(file).use { describe(file.name, it) }
        val outcome = if (report.looksDecodable) {
            runCatching { openDecoder(file).use { waveformPeaks(it, 2_000) } }
        } else {
            null
        }
        lateinit var image: BufferedImage
        SwingUtilities.invokeAndWait {
            val panel = ViewerPanel()
            panel.showReport(report)
            outcome?.onSuccess { peaks ->
                panel.showPeaks(peaks, report.info!!.format.sampleRate)
                panel.setPosition((peaks.totalFrames * playhead).toLong())
            }?.onFailure { panel.showDecodeProblem(readableProblem(it, report.container)) }
            image = panel.render(1040, 720)
        }
        ImageIO.write(image, "png", File(directory, "$name.png"))
        return image
    }

    /** True when the band of rows from [top] to [bottom] holds more than [colours] distinct colours. */
    private fun BufferedImage.busy(top: Int, bottom: Int, colours: Int): Boolean {
        val seen = HashSet<Int>()
        for (y in top until bottom) for (x in 0 until width step 3) seen += getRGB(x, y)
        return seen.size > colours
    }

    @Test
    fun aTaggedFileShowsItsCoverTagsAndWaveform() {
        val flac = ViewerFixtures.library().first { it.label == "FLAC" }
        val image = screen("tagged-flac", flac.file, playhead = 0.35)
        // The waveform sits along the bottom: background, centre line, peaks, RMS and playhead.
        assertTrue(image.busy(image.height - 200, image.height - 60, 4))
        // The cover is drawn at the top left.
        assertTrue(image.busy(80, 280, 50))
    }

    @Test
    fun surroundDrawsOneLanePerChannel() {
        screen("surround-wav", ViewerFixtures.surroundWav(), playhead = 0.5)
    }

    @Test
    fun lossyFilesFromFfmpegShowTheirFormat() {
        val ffmpeg = Ffmpeg.find()
        assumeTrue("ffmpeg is not on PATH", ffmpeg != null)
        val files = ViewerFixtures.ffmpeg(ffmpeg!!)
        screen("ffmpeg-mp3", files.first { it.label == "MP3" }.file)
        screen("ffmpeg-caf", files.first { it.label == "CAF" }.file)
    }

    @Test
    fun aFileWithoutADecoderAndADamagedFileShowWordsInsteadOfAWaveform() {
        screen("midi-not-decodable", ViewerFixtures.midi())
        screen("truncated-flac", ViewerFixtures.truncatedFlac())
    }

    @Test
    fun beforeAFileIsOpenedThePanelSaysWhereToDropOne() {
        lateinit var image: BufferedImage
        SwingUtilities.invokeAndWait { image = ViewerPanel().render(1040, 720) }
        ImageIO.write(image, "png", File(directory, "empty.png"))
        assertTrue(image.busy(300, 380, 2))
    }
}

package io.github.yuroyami.audiokodec.sample

import io.github.yuroyami.audiokodec.AudioDecoder
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.io.openAudioSource
import io.github.yuroyami.audiokodec.tag.TagBundle
import kotlinx.io.files.Path
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyEvent
import java.io.File
import java.util.concurrent.Executors
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import javax.swing.UIManager
import javax.swing.WindowConstants
import kotlin.system.exitProcess

/**
 * The sample viewer: drop an audio file on the window, or open one from the
 * File menu, to see its container and coding, its format and duration, its
 * tags and cover art, and its waveform, and to play it.
 *
 * `./gradlew :sample:run` starts it, and a path in `--args` opens that file
 * at once. On a machine without a display it prints the summary of the file
 * named in the arguments instead.
 */
fun main(args: Array<String>) {
    val path = args.firstOrNull()
    if (GraphicsEnvironment.isHeadless()) {
        if (path == null) {
            System.err.println("There is no display to open the viewer on. Pass a file path to print its summary instead.")
            exitProcess(2)
        }
        val ok = printSummary(File(path))
        exitProcess(if (ok) 0 else 1)
    }
    try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    } catch (_: Exception) {
        // The cross-platform look is a fine fallback.
    }
    SwingUtilities.invokeLater {
        val window = ViewerWindow()
        window.isVisible = true
        path?.let { window.open(File(it)) }
    }
}

/** Opens [file] as a seekable source, which reads only what is asked of it. */
internal fun openSource(file: File) = Path(file.path).openAudioSource()

/** Opens a streaming decoder over [file] that reuses its buffers, as a player should. */
internal fun openDecoder(file: File): AudioDecoder = AudioKodec.open(openSource(file), DecodeOptions(reuseBuffers = true))

/** Prints what the viewer would show about [file], and returns false when it could not be decoded. */
internal fun printSummary(file: File): Boolean {
    val report = try {
        openSource(file).use { describe(file.name, it) }
    } catch (failure: Exception) {
        System.err.println(readableProblem(failure, null))
        return false
    }
    print(report.asText())
    if (!report.looksDecodable) return false
    return try {
        val peaks = openDecoder(file).use { waveformPeaks(it, 1) }
        var peak = 0f
        for (i in 0 until peaks.channels) peak = maxOf(peak, -peaks.min[i], peaks.max[i])
        println()
        println("Decoded ${peaks.totalFrames} frames; the highest sample is ${"%.4f".format(peak)} of full scale.")
        true
    } catch (failure: Exception) {
        System.err.println(readableProblem(failure, report.container))
        false
    }
}

/** The title bar for a window showing [name], or no file. */
internal fun windowTitle(name: String?): String = if (name == null) "AudioKodec viewer" else "$name - AudioKodec viewer"

/**
 * The window: a [ViewerPanel], a File menu and a drop target. Reading the
 * header, reducing the waveform and playing all happen off the event thread;
 * results come back to it, and a result for a file that has since been
 * replaced is dropped.
 */
internal class ViewerWindow : JFrame(windowTitle(null)) {
    private val panel = ViewerPanel()
    private val player = Player(JavaSoundDevice)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "AudioKodec reader").apply { isDaemon = true } }
    private var file: File? = null
    private var generation = 0
    @Volatile private var loading = 0
    private var startFrame = 0L

    init {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        contentPane = panel
        jMenuBar = JMenuBar().apply {
            add(JMenu("File").apply {
                mnemonic = KeyEvent.VK_F
                add(JMenuItem("Open...").apply {
                    accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_O, toolkit.menuShortcutKeyMaskEx)
                    addActionListener { choose() }
                })
                addSeparator()
                add(JMenuItem("Quit").apply {
                    accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_Q, toolkit.menuShortcutKeyMaskEx)
                    addActionListener { dispose(); exitProcess(0) }
                })
            })
        }
        transferHandler = FileDrop { open(it) }
        panel.transferHandler = transferHandler
        panel.onPlay = { play() }
        panel.onStop = { stopPlayback() }
        panel.waveform.onSeek = { frame ->
            startFrame = frame
            panel.setPosition(frame)
            if (player.isPlaying) play()
        }
        size = Dimension(1040, 720)
        minimumSize = Dimension(760, 520)
        setLocationRelativeTo(null)
    }

    private fun choose() {
        val chooser = JFileChooser(file?.parentFile)
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) open(chooser.selectedFile)
    }

    /** Shows [file], replacing whatever was shown. */
    fun open(file: File) {
        player.stop()
        this.file = file
        val ticket = ++generation
        loading = ticket
        startFrame = 0
        title = windowTitle(file.name)
        panel.setPlaying(false)
        worker.execute {
            val report = try {
                openSource(file).use { describe(file.name, it) }
            } catch (failure: Exception) {
                FileReport(file.name, file.length(), null, null, TagBundle.EMPTY, readableProblem(failure, null), null)
            }
            onEventThread(ticket) { panel.showReport(report) }
            if (!report.looksDecodable) return@execute
            try {
                val peaks = openDecoder(file).use { waveformPeaks(it, WAVEFORM_BUCKETS) { loading != ticket } }
                val rate = report.info?.format?.sampleRate ?: 1
                onEventThread(ticket) { panel.showPeaks(peaks, rate) }
            } catch (failure: Exception) {
                onEventThread(ticket) { panel.showDecodeProblem(readableProblem(failure, report.container)) }
            }
        }
    }

    private fun onEventThread(ticket: Int, block: () -> Unit) {
        SwingUtilities.invokeLater { if (ticket == generation) block() }
    }

    private fun play() {
        val file = file ?: return
        val ticket = generation
        panel.setPlaying(true)
        panel.setStatus(" ")
        player.play({ openDecoder(file) }, object : PlaybackListener {
            override fun started(plan: OutputPlan) {
                onEventThread(ticket) { plan.note?.let { panel.setStatus(it) } }
            }

            override fun position(frame: Long) {
                onEventThread(ticket) { panel.setPosition(frame) }
            }

            override fun stopped(reachedEnd: Boolean, problem: String?) {
                onEventThread(ticket) {
                    if (player.isPlaying) return@onEventThread
                    panel.setPlaying(false)
                    if (problem != null) panel.setStatus(problem, isError = true)
                    if (reachedEnd) {
                        startFrame = 0
                        panel.setPosition(0)
                    }
                }
            }
        }, startFrame)
    }

    private fun stopPlayback() {
        player.stop()
        panel.setPlaying(false)
        startFrame = 0
        panel.setPosition(0)
    }

    private companion object {
        /** Enough columns for a wide window; narrower ones merge them as they draw. */
        const val WAVEFORM_BUCKETS = 2_000
    }
}

/** Accepts a file dropped from the desktop and hands the first one to [open]. */
private class FileDrop(private val open: (File) -> Unit) : TransferHandler() {
    override fun canImport(support: TransferSupport): Boolean = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

    override fun importData(support: TransferSupport): Boolean {
        if (!canImport(support)) return false
        val files = try {
            @Suppress("UNCHECKED_CAST")
            support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
        } catch (_: Exception) {
            return false
        }
        val first = files.firstOrNull { it.isFile } ?: return false
        open(first)
        return true
    }
}

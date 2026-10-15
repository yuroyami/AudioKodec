package io.github.yuroyami.audiokodec.sample

import io.github.yuroyami.audiokodec.tag.TagPicture
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.table.DefaultTableModel
import kotlin.math.max
import kotlin.math.min

private val ERROR_COLOUR = Color(0xB0, 0x20, 0x20)
private val MUTED_COLOUR = Color(0x60, 0x60, 0x68)

/**
 * The viewer's content: a header naming the file, the cover and the format
 * on the left, the tags on the right, and the waveform with the playback
 * controls along the bottom. Before a file is opened it shows where to drop
 * one.
 *
 * It holds no file and plays nothing itself; the window feeds it a
 * [FileReport] and [Peaks] and listens to its buttons. That keeps it
 * paintable off screen, which is how the tests look at it.
 */
internal class ViewerPanel : JPanel(CardLayout()) {
    /** Called when Play is pressed. */
    var onPlay: () -> Unit = {}

    /** Called when Stop is pressed. */
    var onStop: () -> Unit = {}

    /** The file shown, for refreshing its facts once the audio has been decoded. */
    private var report: FileReport? = null

    private val title = JLabel(" ").apply { font = font.deriveFont(Font.BOLD, 20f) }
    private val headline = JLabel(" ").apply { font = font.deriveFont(Font.PLAIN, 14f) }
    private val problem = JLabel().apply { foreground = ERROR_COLOUR; isVisible = false }
    private val cover = CoverView()
    private val facts = JPanel(GridBagLayout())
    private val tagModel = object : DefaultTableModel(arrayOf("Field", "Value"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val tagTable = JTable(tagModel).apply {
        fillsViewportHeight = true
        rowHeight = 22
        columnModel.getColumn(0).preferredWidth = 140
        columnModel.getColumn(1).preferredWidth = 360
    }
    private val tagsTitle = sectionLabel("Tags")
    val waveform = WaveformView()
    private val play = JButton("Play").apply { addActionListener { onPlay() } }
    private val stop = JButton("Stop").apply { addActionListener { onStop() }; isEnabled = false }
    private val time = JLabel(" ")
    private val status = JLabel(" ").apply { foreground = MUTED_COLOUR }

    init {
        add(emptyCard(), EMPTY)
        add(fileCard(), FILE)
        showEmpty()
    }

    private fun emptyCard(): JComponent {
        val box = JPanel(GridBagLayout())
        val inner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JLabel("Drop an audio file here").apply { font = font.deriveFont(Font.BOLD, 22f); alignmentX = CENTER_ALIGNMENT })
            add(JLabel("or choose File > Open").apply { foreground = MUTED_COLOUR; alignmentX = CENTER_ALIGNMENT })
        }
        box.add(inner)
        return box
    }

    private fun fileCard(): JComponent {
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(12, 14, 8, 14)
            add(title)
            add(headline)
            add(problem)
        }
        val left = JPanel(BorderLayout(0, 10)).apply {
            border = BorderFactory.createEmptyBorder(0, 14, 0, 10)
            add(cover, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                add(sectionLabel("Format"), BorderLayout.NORTH)
                add(facts, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            preferredSize = Dimension(400, 10)
        }
        val right = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, 0, 0, 14)
            add(tagsTitle, BorderLayout.NORTH)
            add(JScrollPane(tagTable), BorderLayout.CENTER)
        }
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(play)
            add(stop)
            add(time)
            add(status)
        }
        val bottom = JPanel(BorderLayout(0, 4)).apply {
            border = BorderFactory.createEmptyBorder(10, 14, 10, 14)
            add(waveform, BorderLayout.CENTER)
            add(controls, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                add(left, BorderLayout.WEST)
                add(right, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
    }

    private fun showFacts(rows: List<Pair<String, String>>) {
        facts.removeAll()
        val labelConstraints = GridBagConstraints().apply { gridx = 0; anchor = GridBagConstraints.NORTHWEST; insets = Insets(1, 0, 1, 10) }
        val valueConstraints = GridBagConstraints().apply {
            gridx = 1; anchor = GridBagConstraints.NORTHWEST; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(1, 0, 1, 0)
        }
        for ((row, fact) in rows.withIndex()) {
            labelConstraints.gridy = row
            valueConstraints.gridy = row
            facts.add(JLabel(fact.first).apply { foreground = MUTED_COLOUR }, labelConstraints)
            facts.add(JLabel(fact.second), valueConstraints)
        }
        // Takes up the slack below the last row, so the rows stay at the top.
        facts.add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply { gridy = 1000; weighty = 1.0 })
        facts.revalidate()
    }

    /** Shows where to drop a file. */
    fun showEmpty() {
        (layout as CardLayout).show(this, EMPTY)
    }

    /** Shows [report], with the waveform waiting for [showPeaks]. */
    fun showReport(report: FileReport) {
        (layout as CardLayout).show(this, FILE)
        this.report = report
        title.text = report.name
        headline.text = report.headline
        val trouble = listOfNotNull(report.problem, report.tagProblem?.let { "Tags: $it" })
        problem.text = if (trouble.isEmpty()) "" else "<html>${trouble.joinToString("<br>") { escape(it) }}</html>"
        problem.isVisible = trouble.isNotEmpty()

        showFacts(report.facts())

        tagModel.rowCount = 0
        val rows = report.tagRows()
        for ((label, value) in rows) tagModel.addRow(arrayOf(label, value.replace('\n', ' ')))
        tagsTitle.text = if (rows.isEmpty()) "Tags: none" else "Tags"
        cover.picture = report.cover

        waveform.peaks = null
        waveform.position = 0
        waveform.message = if (report.looksDecodable) "Reading the waveform..." else "No waveform: the audio cannot be decoded."
        play.isEnabled = false
        stop.isEnabled = false
        time.text = report.info?.durationSeconds?.let { "0:00.000 / ${durationText(it)}" } ?: " "
        setStatus(" ")
        revalidate()
        repaint()
    }

    /** Shows the waveform and allows playback. */
    fun showPeaks(peaks: Peaks, sampleRate: Int) {
        report?.let { showFacts(it.facts(decodedFrames = peaks.totalFrames)) }
        waveform.peaks = peaks
        waveform.sampleRate = sampleRate
        waveform.message = null
        play.isEnabled = true
        time.text = "${durationText(0.0)} / ${durationText(peaks.totalFrames.toDouble() / sampleRate)}"
    }

    /** Shows why the audio could not be decoded, where the waveform would be. */
    fun showDecodeProblem(message: String) {
        waveform.peaks = null
        waveform.message = "No waveform: the audio could not be decoded."
        play.isEnabled = false
        setStatus(message, isError = true)
    }

    /** Switches the buttons between playing and stopped. */
    fun setPlaying(playing: Boolean) {
        play.isEnabled = !playing && waveform.peaks != null
        stop.isEnabled = playing
    }

    /** Moves the playhead to [frame]. */
    fun setPosition(frame: Long) {
        waveform.position = frame
        val peaks = waveform.peaks ?: return
        val rate = waveform.sampleRate
        time.text = "${durationText(frame.toDouble() / rate)} / ${durationText(peaks.totalFrames.toDouble() / rate)}"
    }

    /** A line of status under the waveform, in red when [isError]. */
    fun setStatus(text: String, isError: Boolean = false) {
        status.text = text.ifEmpty { " " }
        status.foreground = if (isError) ERROR_COLOUR else MUTED_COLOUR
    }

    /**
     * Lays the panel out at [width] by [height] and paints it into an image,
     * without a window or a display.
     */
    fun render(width: Int, height: Int): BufferedImage {
        setSize(width, height)
        layOut(this)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        paint(g)
        g.dispose()
        return image
    }

    private fun layOut(component: Component) {
        if (component is Container) {
            component.doLayout()
            for (child in component.components) layOut(child)
        }
    }

    private companion object {
        const val EMPTY = "empty"
        const val FILE = "file"
    }
}

private fun sectionLabel(text: String) = JLabel(text).apply {
    font = font.deriveFont(Font.BOLD, 13f)
    border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
}

private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** The cover, scaled to fit a square and centred, or a note saying why there is none. */
internal class CoverView : JComponent() {
    private var image: BufferedImage? = null
    private var note: String = "No cover art"

    var picture: TagPicture? = null
        set(value) {
            field = value
            image = value?.let { decodeImage(it.bytes) }
            note = when {
                value == null -> "No cover art"
                image == null -> "${value.mimeType} picture, which Java cannot draw"
                else -> ""
            }
            repaint()
        }

    init {
        preferredSize = Dimension(SIDE, SIDE)
        minimumSize = preferredSize
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        val side = min(width, SIDE)
        g2.color = Color(0xEC, 0xEC, 0xF0)
        g2.fillRect(0, 0, side, side)
        val image = image
        if (image == null) {
            g2.color = MUTED_COLOUR
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val metrics = g2.fontMetrics
            g2.drawString(note, max(4, (side - metrics.stringWidth(note)) / 2), side / 2)
            return
        }
        val scale = min(side.toDouble() / image.width, side.toDouble() / image.height)
        val w = (image.width * scale).toInt()
        val h = (image.height * scale).toInt()
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g2.drawImage(image, (side - w) / 2, (side - h) / 2, w, h, null)
    }

    private companion object {
        const val SIDE = 180

        /** The picture as an image, or null when no installed reader takes its format. */
        fun decodeImage(bytes: ByteArray): BufferedImage? = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Draws [Peaks] as one lane per channel: the range between the lowest and
 * highest sample in a light shade, the root mean square inside it darker, and
 * the playhead as a line. A click reports the frame under the pointer.
 */
internal class WaveformView : JComponent() {
    var peaks: Peaks? = null
        set(value) { field = value; repaint() }
    var sampleRate: Int = 1
    var position: Long = 0
        set(value) { field = value; repaint() }
    var message: String? = null
        set(value) { field = value; repaint() }

    /** Called with the frame a click points at. */
    var onSeek: (Long) -> Unit = {}

    init {
        preferredSize = Dimension(600, 170)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val peaks = peaks ?: return
                if (width <= 0) return
                onSeek((peaks.totalFrames * e.x.coerceIn(0, width) / width))
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.color = Color(0x1C, 0x1C, 0x24)
        g2.fillRect(0, 0, width, height)
        val peaks = peaks
        if (peaks == null || peaks.buckets == 0) {
            g2.color = Color(0xB0, 0xB0, 0xBC)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val text = message ?: if (peaks != null) "No audio" else ""
            g2.drawString(text, max(8, (width - g2.fontMetrics.stringWidth(text)) / 2), height / 2)
            return
        }
        val lanes = min(peaks.channels, MAX_LANES)
        val laneHeight = height.toDouble() / lanes
        for (lane in 0 until lanes) {
            val top = lane * laneHeight
            val mid = top + laneHeight / 2
            val half = laneHeight / 2 - 2
            g2.color = Color(0x34, 0x34, 0x40)
            g2.drawLine(0, mid.toInt(), width, mid.toInt())
            for (x in 0 until width) {
                val from = (x.toLong() * peaks.buckets / width).toInt()
                val to = max(from + 1, ((x + 1).toLong() * peaks.buckets / width).toInt()).coerceAtMost(peaks.buckets)
                var lo = Float.POSITIVE_INFINITY
                var hi = Float.NEGATIVE_INFINITY
                var rms = 0f
                for (b in from until to) {
                    val i = b * peaks.channels + lane
                    lo = min(lo, peaks.min[i])
                    hi = max(hi, peaks.max[i])
                    rms = max(rms, peaks.rms[i])
                }
                if (lo > hi) continue
                fun y(v: Float) = (mid - v.coerceIn(-1f, 1f) * half).toInt()
                g2.color = Color(0x7E, 0x8C, 0xE0)
                g2.drawLine(x, y(hi), x, y(lo))
                g2.color = Color(0xC8, 0xD0, 0xFF)
                g2.drawLine(x, y(rms), x, y(-rms))
            }
            if (lane > 0) {
                g2.color = Color(0x10, 0x10, 0x16)
                g2.drawLine(0, top.toInt(), width, top.toInt())
            }
        }
        if (peaks.channels > MAX_LANES) {
            g2.color = Color.WHITE
            g2.drawString("First $MAX_LANES of ${peaks.channels} channels", 6, 14)
        }
        if (peaks.totalFrames > 0 && position > 0) {
            val x = (position * width / peaks.totalFrames).toInt()
            g2.color = Color(0xFF, 0xB0, 0x40)
            g2.stroke = BasicStroke(2f)
            g2.drawLine(x, 0, x, height)
        }
    }

    private companion object {
        const val MAX_LANES = 8
    }
}
